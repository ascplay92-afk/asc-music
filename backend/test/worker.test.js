import test from "node:test";
import assert from "node:assert/strict";
import worker, { handleRequest } from "../src/index.js";

test("health endpoint is public and does not require the database", async () => {
  const response = await handleRequest(new Request("https://api.example/api/health"), {});
  assert.equal(response.status, 200);
  assert.equal((await response.json()).ok, true);
});

test("discovery sections are discoverable without OAuth credentials", async () => {
  const response = await handleRequest(new Request("https://api.example/api/discovery"), {});
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.ok(body.sections.some((section) => section.id === "brazil"));
  assert.ok(body.sections.some((section) => section.id === "releases"));
});

test("private API requests fail closed when Cloudflare Access is not configured", async () => {
  const response = await handleRequest(new Request("https://api.example/api/playlists"), {});
  assert.equal(response.status, 503);
  assert.match((await response.json()).error, /not configured/);
});

test("unknown discovery categories return a clear not-found response", async () => {
  const response = await handleRequest(new Request("https://api.example/api/discovery/not-a-section"), {});
  assert.equal(response.status, 404);
  assert.match((await response.json()).error, /Unknown discovery section/);
});

test("personalized discovery is behind Access rather than a public upstream proxy", async () => {
  const response = await handleRequest(new Request("https://api.example/api/discovery/similar?artists=Anitta"), {});
  assert.equal(response.status, 503);
  assert.match((await response.json()).error, /not configured/);
});

test("Worker preflight only allows the exact configured Web origin", async () => {
  const env = { APP_ORIGIN: "https://music.example" };
  const allowed = await worker.fetch(new Request("https://api.example/api/playlists", {
    method: "OPTIONS",
    headers: { Origin: "https://music.example" },
  }), env);
  assert.equal(allowed.status, 204);
  assert.equal(allowed.headers.get("access-control-allow-origin"), "https://music.example");
  assert.match(allowed.headers.get("access-control-allow-headers"), /Authorization/);

  const denied = await worker.fetch(new Request("https://api.example/api/playlists", {
    method: "OPTIONS",
    headers: { Origin: "https://attacker.example" },
  }), env);
  assert.equal(denied.status, 403);
});
