import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { handleRequest } from "../src/index.js";

const domain = "sync-test.cloudflareaccess.com";
const audience = "asc-music-sync-test";

function base64Url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function encodeJson(value) {
  return base64Url(new TextEncoder().encode(JSON.stringify(value)));
}

async function signedToken(privateKey, subject, issuerDomain = domain) {
  const content = `${encodeJson({ alg: "RS256", kid: "sync-test-key" })}.${encodeJson({
    iss: `https://${issuerDomain}`,
    aud: [audience],
    exp: Math.floor(Date.now() / 1000) + 60,
    nbf: Math.floor(Date.now() / 1000) - 60,
    sub: subject,
  })}`;
  const signature = new Uint8Array(await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    privateKey,
    new TextEncoder().encode(content),
  ));
  return `${content}.${base64Url(signature)}`;
}

class SqliteD1 {
  constructor() {
    this.sqlite = new DatabaseSync(":memory:");
    this.sqlite.exec(readFileSync(new URL("../migrations/0001_init.sql", import.meta.url), "utf8"));
    this.sqlite.exec(readFileSync(new URL("../migrations/0002_sync.sql", import.meta.url), "utf8"));
    this.sqlite.exec(readFileSync(new URL("../migrations/0003_sync_operation_fingerprints.sql", import.meta.url), "utf8"));
  }

  prepare(sql) {
    let values = [];
    const statement = this;
    return {
      sql,
      get values() {
        return values;
      },
      bind(...args) {
        values = args;
        return this;
      },
      first() {
        return statement.sqlite.prepare(sql).get(...values) ?? null;
      },
      all() {
        return { results: statement.sqlite.prepare(sql).all(...values) };
      },
    };
  }

  async batch(statements) {
    this.sqlite.exec("BEGIN");
    try {
      const results = statements.map((statement) => {
        const sql = statement.sql;
        const values = statement.values;
        const rows = this.sqlite.prepare(sql).all(...values);
        return { results: rows, meta: { changes: rows.length } };
      });
      this.sqlite.exec("COMMIT");
      return results;
    } catch (error) {
      this.sqlite.exec("ROLLBACK");
      throw error;
    }
  }
}

test("sync applies idempotent changes, returns optimistic conflicts, and paginates account-scoped events", async (t) => {
  const keyPair = await crypto.subtle.generateKey({
    name: "RSASSA-PKCS1-v1_5",
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash: "SHA-256",
  }, true, ["sign", "verify"]);
  const publicKey = { ...await crypto.subtle.exportKey("jwk", keyPair.publicKey), kid: "sync-test-key" };
  const database = new SqliteD1();
  t.after(() => database.sqlite.close());
  const env = {
    ACCESS_TEAM_DOMAIN: domain,
    ACCESS_AUD: audience,
    DB: database,
  };
  const tokenFor = (subject) => signedToken(keyPair.privateKey, subject);
  const post = async (subject, changes) => handleRequest(new Request("https://api.example/api/sync", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${await tokenFor(subject)}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ changes }),
  }), env, { fetch: async () => Response.json({ keys: [publicKey] }) });
  const get = async (subject, cursor = 0, limit = 200) => handleRequest(new Request(
    `https://api.example/api/sync?cursor=${cursor}&limit=${limit}`,
    { headers: { Authorization: `Bearer ${await tokenFor(subject)}` } },
  ), env, { fetch: async () => Response.json({ keys: [publicKey] }) });

  const firstChange = {
    operationId: "operation-one",
    type: "favorite",
    id: "file:track-1",
    baseVersion: 0,
    deleted: false,
    payload: { favorite: true, unwantedField: "not stored" },
  };
  const first = await post("user-a", [firstChange]);
  assert.equal(first.status, 200);
  assert.deepEqual((await first.json()).results, [{
    operationId: "operation-one",
    status: "applied",
    version: 1,
  }]);

  const retried = await post("user-a", [firstChange]);
  const retryResult = await retried.json();
  assert.equal(retryResult.results[0].status, "applied");
  assert.equal(retryResult.results[0].version, 1);
  const reusedOperation = await post("user-a", [{
    ...firstChange,
    payload: { favorite: false },
  }]);
  assert.equal(reusedOperation.status, 409);

  const conflict = await post("user-a", [{
    ...firstChange,
    operationId: "operation-two",
    payload: { favorite: false },
  }]);
  const conflictResult = (await conflict.json()).results[0];
  assert.equal(conflictResult.status, "conflict");
  assert.equal(conflictResult.current.version, 1);
  assert.deepEqual(conflictResult.current.payload, { key: "file:track-1", favorite: true });

  const second = await post("user-a", [{
    operationId: "operation-three",
    type: "setting",
    id: "shuffle",
    baseVersion: 0,
    deleted: false,
    payload: { value: true },
  }]);
  assert.equal((await second.json()).results[0].status, "applied");

  const page = await get("user-a", 0, 1);
  const pageBody = await page.json();
  assert.equal(pageBody.changes.length, 1);
  assert.equal(pageBody.changes[0].payload.favorite, true);
  assert.equal(pageBody.hasMore, true);

  const otherAccount = await get("user-b");
  assert.deepEqual((await otherAccount.json()).changes, []);

  const tombstone = await post("user-a", [{
    operationId: "operation-four",
    type: "favorite",
    id: "file:track-1",
    baseVersion: 1,
    deleted: true,
    payload: {},
  }]);
  assert.equal((await tombstone.json()).results[0].version, 2);
  const afterTombstone = await get("user-a", pageBody.cursor + 1);
  const tombstoneEvent = (await afterTombstone.json()).changes[0];
  assert.equal(tombstoneEvent.deleted, true);
  assert.deepEqual(tombstoneEvent.payload, {});
});

test("sync rejects invalid cursors, unbounded payloads, and accidental history deletion", async (t) => {
  const keyPair = await crypto.subtle.generateKey({
    name: "RSASSA-PKCS1-v1_5",
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash: "SHA-256",
  }, true, ["sign", "verify"]);
  const publicKey = { ...await crypto.subtle.exportKey("jwk", keyPair.publicKey), kid: "sync-test-key" };
  const database = new SqliteD1();
  t.after(() => database.sqlite.close());
  const isolatedDomain = "sync-validation-test.cloudflareaccess.com";
  const env = { ACCESS_TEAM_DOMAIN: isolatedDomain, ACCESS_AUD: audience, DB: database };
  const token = await signedToken(keyPair.privateKey, "user-a", isolatedDomain);
  const options = { fetch: async () => Response.json({ keys: [publicKey] }) };
  const invalidCursor = await handleRequest(new Request("https://api.example/api/sync?cursor=-1", {
    headers: { Authorization: `Bearer ${token}` },
  }), env, options);
  assert.equal(invalidCursor.status, 400);

  const post = (change) => handleRequest(new Request("https://api.example/api/sync", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ changes: [change] }),
  }), env, options);
  const deletedHistory = await post({
    operationId: "delete-history",
    type: "history",
    id: "history-1",
    baseVersion: 0,
    deleted: true,
    payload: {},
  });
  assert.equal(deletedHistory.status, 400);

  const oversizedPlaylist = await post({
    operationId: "oversized-playlist",
    type: "playlist",
    id: "playlist-1",
    baseVersion: 0,
    deleted: false,
    payload: {
      name: "Large playlist",
      tracks: Array.from({ length: 200 }, (_, index) => ({
        title: `Track ${index}`,
        artist: "a".repeat(500),
      })),
    },
  });
  assert.equal(oversizedPlaylist.status, 413);
});
