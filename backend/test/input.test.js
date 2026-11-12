import test from "node:test";
import assert from "node:assert/strict";
import { readJson } from "../src/index.js";

test("JSON request bodies are parsed within the byte limit", async () => {
  const request = new Request("https://api.example/api/playlists", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ name: "Library", tracks: [] }),
  });
  assert.deepEqual(await readJson(request), { name: "Library", tracks: [] });
});

test("oversized chunked request bodies are cancelled and rejected with 413", async () => {
  let cancelled = false;
  const stream = new ReadableStream({
    pull(controller) {
      controller.enqueue(new Uint8Array(1024 * 1024 + 1));
    },
    cancel() {
      cancelled = true;
    },
  });
  const request = new Request("https://api.example/api/playlists", {
    method: "POST",
    body: stream,
    duplex: "half",
  });

  const result = await readJson(request);

  assert.equal(result.status, 413);
  assert.equal(cancelled, true);
});

test("oversized bodies are rejected from Content-Length before reading", async () => {
  const request = new Request("https://api.example/api/playlists", {
    method: "POST",
    headers: { "content-length": String(1024 * 1024 + 1) },
    body: "{}",
  });
  const result = await readJson(request);
  assert.equal(result.status, 413);
});

test("invalid UTF-8 and non-object JSON are rejected", async () => {
  const invalidUtf8 = new Request("https://api.example/api/playlists", {
    method: "POST",
    body: new Uint8Array([0x7b, 0xff, 0x7d]),
    duplex: "half",
  });
  const scalar = new Request("https://api.example/api/playlists", { method: "POST", body: "[]" });

  assert.equal((await readJson(invalidUtf8)).status, 400);
  assert.equal((await readJson(scalar)).status, 400);
});
