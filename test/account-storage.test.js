import test from "node:test";
import assert from "node:assert/strict";
import { accountStorageKey, readAccountLocalState } from "../web/src/account-storage.js";

class MemoryStorage {
  values = new Map();

  getItem(key) {
    return this.values.get(key) ?? null;
  }

  setItem(key, value) {
    this.values.set(key, String(value));
  }
}

test("account local state stays isolated across account switches", () => {
  const storage = new MemoryStorage();
  storage.setItem(accountStorageKey("playlists", "user/a"), JSON.stringify([{ id: "a" }]));
  storage.setItem(accountStorageKey("favorites", "user/a"), JSON.stringify(["track-a"]));
  storage.setItem(accountStorageKey("history", "user/a"), JSON.stringify(["history-a"]));
  storage.setItem(accountStorageKey("playlists", "user-b"), JSON.stringify([{ id: "b" }]));
  storage.setItem(accountStorageKey("favorites", "user-b"), JSON.stringify(["track-b"]));

  const accountA = readAccountLocalState(storage, "user/a");
  const accountB = readAccountLocalState(storage, "user-b");

  assert.deepEqual(accountA.playlists, [{ id: "a" }]);
  assert.deepEqual([...accountA.favorites], ["track-a"]);
  assert.deepEqual(accountA.history, ["history-a"]);
  assert.deepEqual(accountB.playlists, [{ id: "b" }]);
  assert.deepEqual([...accountB.favorites], ["track-b"]);
  assert.deepEqual(accountB.history, []);
});

test("signed-in state does not inherit legacy device-local state", () => {
  const storage = new MemoryStorage();
  storage.setItem("asc.playlists.v1", JSON.stringify([{ id: "legacy" }]));
  storage.setItem("asc.favorites.v1", JSON.stringify(["legacy-track"]));

  assert.deepEqual(readAccountLocalState(storage, "new-user"), {
    playlists: [],
    favorites: new Set(),
    history: [],
  });
  assert.deepEqual(readAccountLocalState(storage, null), {
    playlists: [{ id: "legacy" }],
    favorites: new Set(["legacy-track"]),
    history: [],
  });
});
