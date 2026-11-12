import { defineConfig, loadEnv } from "vite";

function apiOriginFrom(value) {
  if (!value) return "";
  const url = new URL(value);
  const localHttp = url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (url.protocol !== "https:" && !localHttp) {
    throw new Error("VITE_API_BASE_URL must use HTTPS except for localhost development.");
  }
  return url.origin;
}

function supabaseOriginFrom(value) {
  if (!value) return "";
  const url = new URL(value);
  const localHttp = url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (url.protocol !== "https:" && !localHttp) {
    throw new Error("VITE_SUPABASE_URL must use HTTPS except for localhost development.");
  }
  return url.origin;
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const apiOrigin = apiOriginFrom(env.VITE_API_BASE_URL);
  const supabaseOrigin = supabaseOriginFrom(env.VITE_SUPABASE_URL);
  const connectSources = ["'self'", apiOrigin, supabaseOrigin, "https://accounts.spotify.com", "https://api.spotify.com", "https://*.googleapis.com", "https://accounts.google.com"]
    .filter(Boolean)
    .join(" ");

  return {
    root: "web",
    envDir: process.cwd(),
    publicDir: "public",
    plugins: [{
      name: "asc-music-security-headers",
      apply: "build",
      generateBundle() {
        this.emitFile({
          type: "asset",
          fileName: "_headers",
          source: `/*
  X-Content-Type-Options: nosniff
  Referrer-Policy: strict-origin-when-cross-origin
  X-Frame-Options: DENY
  Permissions-Policy: camera=(), microphone=(), geolocation=()
  Content-Security-Policy: default-src 'self'; script-src 'self' https://accounts.google.com https://apis.google.com https://*.gstatic.com; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com data:; connect-src ${connectSources}; img-src 'self' data: https:; media-src 'self' blob:; frame-src https://accounts.google.com https://*.google.com https://*.googleusercontent.com; object-src 'none'; base-uri 'self'; form-action 'self' https://accounts.spotify.com; frame-ancestors 'none'
`,
        });
      },
    }],
    build: {
      outDir: "../dist",
      emptyOutDir: true,
    },
  };
});
