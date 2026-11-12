import test from "node:test";
import assert from "node:assert/strict";
import { authenticateRequest } from "../src/auth.js";
import { handleRequest } from "../src/index.js";

const now = Date.parse("2026-11-11T12:00:00Z");
const teamDomain = "unit-test.cloudflareaccess.com";
const audience = "asc-music-api-test";

function base64Url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function encodeJson(value) {
  return base64Url(new TextEncoder().encode(JSON.stringify(value)));
}

async function signedToken(privateKey, claims) {
  const header = encodeJson({ alg: "RS256", kid: "asc-unit-test-key" });
  const payload = encodeJson(claims);
  const content = `${header}.${payload}`;
  const signature = new Uint8Array(await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    privateKey,
    new TextEncoder().encode(content),
  ));
  return `${content}.${base64Url(signature)}`;
}

test("Cloudflare Access authentication verifies signature, issuer, audience, expiry and subject", async () => {
  const keyPair = await crypto.subtle.generateKey({
    name: "RSASSA-PKCS1-v1_5",
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash: "SHA-256",
  }, true, ["sign", "verify"]);
  const publicKey = { ...await crypto.subtle.exportKey("jwk", keyPair.publicKey), kid: "asc-unit-test-key" };
  let fetchCount = 0;
  const options = {
    now,
    fetch: async () => {
      fetchCount += 1;
      return Response.json({ keys: [publicKey] });
    },
  };
  const env = { ACCESS_TEAM_DOMAIN: teamDomain, ACCESS_AUD: audience };
  const claims = {
    iss: `https://${teamDomain}`,
    aud: [audience],
    exp: Math.floor(now / 1000) + 60,
    nbf: Math.floor(now / 1000) - 60,
    sub: "user-123",
  };
  const valid = await signedToken(keyPair.privateKey, claims);
  const validRequest = new Request("https://api.example/api/playlists", {
    headers: { "Cf-Access-Jwt-Assertion": valid },
  });
  assert.deepEqual(await authenticateRequest(validRequest, env, options), { ok: true, userId: "user-123" });
  const nativeRequest = new Request("https://api.example/api/playlists", {
    headers: { Authorization: `Bearer ${valid}` },
  });
  assert.deepEqual(await authenticateRequest(nativeRequest, env, options), { ok: true, userId: "user-123" });

  const invalidAudience = await signedToken(keyPair.privateKey, { ...claims, aud: "another-application" });
  const rejectedAudience = await authenticateRequest(new Request("https://api.example/api/playlists", {
    headers: { "Cf-Access-Jwt-Assertion": invalidAudience },
  }), env, options);
  assert.equal(rejectedAudience.status, 401);

  const tokenParts = valid.split(".");
  tokenParts[2] = `${tokenParts[2][0] === "A" ? "B" : "A"}${tokenParts[2].slice(1)}`;
  const invalidSignature = tokenParts.join(".");
  const rejectedSignature = await authenticateRequest(new Request("https://api.example/api/playlists", {
    headers: { "Cf-Access-Jwt-Assertion": invalidSignature },
  }), env, options);
  assert.equal(rejectedSignature.status, 401);

  const missingIdentity = await authenticateRequest(new Request("https://api.example/api/playlists"), env, options);
  assert.equal(missingIdentity.status, 401);
  assert.equal(fetchCount, 1);
});

test("private API authentication fails closed without Access configuration", async () => {
  const result = await authenticateRequest(new Request("https://api.example/api/playlists"), {});
  assert.deepEqual(result, {
    ok: false,
    status: 503,
    error: "Cloudflare Access authentication is not configured.",
  });
});

test("native Bearer requests can sync without Origin while unbound assertion writes cannot", async () => {
  const keyPair = await crypto.subtle.generateKey({
    name: "RSASSA-PKCS1-v1_5",
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash: "SHA-256",
  }, true, ["sign", "verify"]);
  const nativeTeamDomain = "native-test.cloudflareaccess.com";
  const publicKey = { ...await crypto.subtle.exportKey("jwk", keyPair.publicKey), kid: "asc-unit-test-key" };
  const nativeAudience = "native-client-test";
  const token = await signedToken(keyPair.privateKey, {
    iss: `https://${nativeTeamDomain}`,
    aud: [nativeAudience],
    exp: Math.floor(Date.now() / 1000) + 60,
    nbf: Math.floor(Date.now() / 1000) - 60,
    sub: "native-user",
  });
  const env = {
    ACCESS_TEAM_DOMAIN: nativeTeamDomain,
    ACCESS_AUD: nativeAudience,
    DB: {
      prepare() {
        return {
          bind() {
            return this;
          },
          async first() {
            return { request_count: 1 };
          },
        };
      },
    },
  };
  const options = { fetch: async () => Response.json({ keys: [publicKey] }) };
  const request = (headers) => new Request("https://api.example/api/library/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify({ files: [] }),
  });

  const nativeResponse = await handleRequest(request({ Authorization: `Bearer ${token}` }), env, options);
  assert.equal(nativeResponse.status, 200);

  const browserAssertionResponse = await handleRequest(request({ "Cf-Access-Jwt-Assertion": token }), env, options);
  assert.equal(browserAssertionResponse.status, 403);
  assert.match((await browserAssertionResponse.json()).error, /origin/);
});

test("Supabase ES256 access tokens map to a namespaced verified D1 subject", async () => {
  const projectUrl = "https://asc-test.supabase.co";
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const jwk = { ...await crypto.subtle.exportKey("jwk", pair.publicKey), kid: "supabase-test-key" };
  const claims = {
    iss: `${projectUrl}/auth/v1`,
    aud: "authenticated",
    role: "authenticated",
    sub: "a7fd7e6c-bf11-4aa9-8a09-0882fcb4a123",
    exp: Math.floor(now / 1000) + 60,
    iat: Math.floor(now / 1000),
  };
  const header = encodeJson({ alg: "ES256", kid: jwk.kid, typ: "JWT" });
  const payload = encodeJson(claims);
  const content = `${header}.${payload}`;
  const signature = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    pair.privateKey,
    new TextEncoder().encode(content),
  ));
  const token = `${content}.${base64Url(signature)}`;
  const identity = await authenticateRequest(new Request("https://api.example/api/sync", {
    headers: {
      Authorization: `Bearer ${token}`,
      "X-User-Id": "attacker-controlled",
    },
  }), { SUPABASE_URL: projectUrl }, {
    now,
    fetch: async (url) => {
      assert.equal(url, `${projectUrl}/auth/v1/.well-known/jwks.json`);
      return Response.json({ keys: [jwk] });
    },
  });
  assert.deepEqual(identity, { ok: true, userId: `supabase:${claims.sub}` });

  const badHeader = encodeJson({ alg: "ES256", kid: jwk.kid });
  const badPayload = encodeJson({ ...claims, role: "service_role" });
  const badContent = `${badHeader}.${badPayload}`;
  const badSignature = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    pair.privateKey,
    new TextEncoder().encode(badContent),
  ));
  const denied = await authenticateRequest(new Request("https://api.example/api/sync", {
    headers: { Authorization: `Bearer ${badContent}.${base64Url(badSignature)}` },
  }), { SUPABASE_URL: projectUrl }, {
    now,
    fetch: async () => Response.json({ keys: [jwk] }),
  });
  assert.equal(denied.status, 401);
});

test("Supabase JWKS refreshes once when key rotation introduces an unknown kid", async () => {
  const projectUrl = "https://rotation-test.supabase.co";
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const currentJwk = { ...await crypto.subtle.exportKey("jwk", pair.publicKey), kid: "rotated-key" };
  const claims = {
    iss: `${projectUrl}/auth/v1`,
    aud: "authenticated",
    role: "authenticated",
    sub: "rotation-user",
    exp: Math.floor(now / 1000) + 60,
  };
  const header = encodeJson({ alg: "ES256", kid: currentJwk.kid, typ: "JWT" });
  const payload = encodeJson(claims);
  const content = `${header}.${payload}`;
  const signature = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    pair.privateKey,
    new TextEncoder().encode(content),
  ));
  let fetchCount = 0;
  const result = await authenticateRequest(new Request("https://api.example/api/sync", {
    headers: { Authorization: `Bearer ${content}.${base64Url(signature)}` },
  }), { SUPABASE_URL: projectUrl }, {
    now,
    fetch: async () => {
      fetchCount += 1;
      return Response.json({ keys: fetchCount === 1 ? [{ kid: "previous-key" }] : [currentJwk] });
    },
  });

  assert.deepEqual(result, { ok: true, userId: "supabase:rotation-user" });
  assert.equal(fetchCount, 2);
});
