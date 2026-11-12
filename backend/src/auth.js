const KEYS_TTL_MS = 60 * 60 * 1000;
const keyCache = new Map();

function decodeBase64Url(value) {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(normalized), (character) => character.charCodeAt(0));
}

function decodeJson(value) {
  return JSON.parse(new TextDecoder().decode(decodeBase64Url(value)));
}

function normalizeSupabaseUrl(value) {
  const url = new URL(value);
  if (url.protocol !== "https:" && !(url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname))) {
    throw new Error("SUPABASE_URL must use HTTPS.");
  }
  return url.origin;
}

async function getJwks(cacheKey, url, fetchImpl, now, forceRefresh = false) {
  const cached = keyCache.get(cacheKey);
  if (!forceRefresh && cached && now - cached.fetchedAt < KEYS_TTL_MS) return cached.keys;
  const response = await fetchImpl(url);
  if (!response.ok) throw new Error("Identity signing keys could not be loaded.");
  const document = await response.json();
  if (!Array.isArray(document.keys) || !document.keys.length) {
    throw new Error("The identity provider returned no signing keys.");
  }
  keyCache.set(cacheKey, { keys: document.keys, fetchedAt: now });
  return document.keys;
}

function validTimeClaims(claims, nowSeconds) {
  return Number.isFinite(claims.exp)
    && claims.exp > nowSeconds
    && (!Number.isFinite(claims.nbf) || claims.nbf <= nowSeconds)
    && (!Number.isFinite(claims.iat) || claims.iat <= nowSeconds + 60)
    && typeof claims.sub === "string"
    && claims.sub.length > 0
    && claims.sub.length <= 255;
}

async function verifyJwt({ token, issuer, audience, jwksUrl, cacheKey, fetchImpl, now, supabase = false }) {
  const parts = token.split(".");
  if (parts.length !== 3) return { ok: false, status: 401, error: "The identity token is invalid." };

  let header;
  let claims;
  try {
    header = decodeJson(parts[0]);
    claims = decodeJson(parts[1]);
  } catch {
    return { ok: false, status: 401, error: "The identity token is invalid." };
  }
  if (
    !header || typeof header.kid !== "string"
    || !["RS256", "ES256"].includes(header.alg)
    || (header.crit !== undefined)
  ) {
    return { ok: false, status: 401, error: "The identity token uses an unsupported signature." };
  }
  const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  if (
    claims.iss !== issuer
    || !audiences.includes(audience)
    || !validTimeClaims(claims, Math.floor(now / 1000))
    || (supabase && claims.role !== "authenticated")
  ) {
    return { ok: false, status: 401, error: "The identity token is expired or has invalid claims." };
  }

  try {
    let keys = await getJwks(cacheKey, jwksUrl, fetchImpl, now);
    let jwk = keys.find((key) => key.kid === header.kid);
    if (!jwk) {
      keys = await getJwks(cacheKey, jwksUrl, fetchImpl, now, true);
      jwk = keys.find((key) => key.kid === header.kid);
    }
    if (!jwk) return { ok: false, status: 401, error: "The identity token signing key is unknown." };

    let key;
    let algorithm;
    if (header.alg === "RS256" && jwk.kty === "RSA") {
      algorithm = { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" };
      key = await crypto.subtle.importKey("jwk", jwk, algorithm, false, ["verify"]);
    } else if (header.alg === "ES256" && jwk.kty === "EC" && jwk.crv === "P-256") {
      algorithm = { name: "ECDSA", hash: "SHA-256" };
      key = await crypto.subtle.importKey("jwk", jwk, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    } else {
      return { ok: false, status: 401, error: "The identity token signing key is incompatible." };
    }
    const valid = await crypto.subtle.verify(
      algorithm,
      key,
      decodeBase64Url(parts[2]),
      new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
    );
    if (!valid) return { ok: false, status: 401, error: "The identity token signature is invalid." };
    return { ok: true, userId: supabase ? `supabase:${claims.sub}` : claims.sub };
  } catch {
    return { ok: false, status: 503, error: "Identity verification is temporarily unavailable." };
  }
}

async function authenticateAccess(token, env, options) {
  if (!env.ACCESS_TEAM_DOMAIN || !env.ACCESS_AUD) {
    return { ok: false, status: 503, error: "Cloudflare Access authentication is not configured." };
  }
  return verifyJwt({
    token,
    issuer: `https://${env.ACCESS_TEAM_DOMAIN}`,
    audience: env.ACCESS_AUD,
    jwksUrl: `https://${env.ACCESS_TEAM_DOMAIN}/cdn-cgi/access/certs`,
    cacheKey: `access:${env.ACCESS_TEAM_DOMAIN}`,
    fetchImpl: options.fetch ?? fetch,
    now: options.now ?? Date.now(),
  });
}

async function authenticateSupabase(token, env, options) {
  if (!env.SUPABASE_URL) {
    return { ok: false, status: 503, error: "Supabase identity verification is not configured." };
  }
  try {
    const baseUrl = normalizeSupabaseUrl(env.SUPABASE_URL);
    const issuer = `${baseUrl}/auth/v1`;
    return await verifyJwt({
      token,
      issuer,
      audience: "authenticated",
      jwksUrl: `${issuer}/.well-known/jwks.json`,
      cacheKey: `supabase:${issuer}`,
      fetchImpl: options.fetch ?? fetch,
      now: options.now ?? Date.now(),
      supabase: true,
    });
  } catch {
    return { ok: false, status: 503, error: "Supabase identity verification is not configured correctly." };
  }
}

function tokenIssuer(token) {
  try {
    return decodeJson(token.split(".")[1]).iss;
  } catch {
    return null;
  }
}

export async function authenticateRequest(request, env, options = {}) {
  const accessAssertion = request.headers.get("Cf-Access-Jwt-Assertion");
  const bearer = request.headers.get("Authorization")?.match(/^Bearer\s+(.+)$/i)?.[1];
  if (bearer && env.SUPABASE_URL) {
    try {
      const expectedIssuer = `${normalizeSupabaseUrl(env.SUPABASE_URL)}/auth/v1`;
      if (tokenIssuer(bearer) === expectedIssuer) return authenticateSupabase(bearer, env, options);
    } catch {
      return { ok: false, status: 503, error: "Supabase identity verification is not configured correctly." };
    }
  }
  if (accessAssertion) return authenticateAccess(accessAssertion, env, options);
  if (!bearer) {
    if (!env.ACCESS_TEAM_DOMAIN || !env.ACCESS_AUD) {
      return { ok: false, status: 503, error: "Cloudflare Access authentication is not configured." };
    }
    return { ok: false, status: 401, error: "An authenticated identity is required." };
  }

  return authenticateAccess(bearer, env, options);
}
