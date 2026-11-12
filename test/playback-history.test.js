import assert from "node:assert/strict";
import test from "node:test";
import { appendPlaybackHistory, MAX_PLAYBACK_HISTORY } from "../shared/playback-history.js";

test("playback history stores bounded metadata entries without audio or artwork payloads", () => {
  const first = appendPlaybackHistory([], {
    id: "drive-file-1",
    title: "Caju",
    artist: "Liniker",
    album: "Indigo Borboleta Anil",
    source: "drive",
    audioBytes: new Uint8Array([1, 2]),
    coverArt: "data:image/jpeg;base64,private-artwork",
  }, 1794398400000);
  assert.deepEqual(first, [{
    id: "drive-file-1",
    title: "Caju",
    artist: "Liniker",
    album: "Indigo Borboleta Anil",
    source: "drive",
    playedAt: 1794398400000,
  }]);

  const history = Array.from({ length: MAX_PLAYBACK_HISTORY }, (_, index) => ({ id: `old-${index}` }));
  const updated = appendPlaybackHistory(history, { id: "newest", title: "New" });
  assert.equal(updated.length, MAX_PLAYBACK_HISTORY);
  assert.equal(updated[0].id, "newest");
  assert.equal(updated.at(-1).id, "old-98");
});
