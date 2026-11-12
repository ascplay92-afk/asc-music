import test from "node:test";
import assert from "node:assert/strict";
import { parsePlaylistCsv, parseTrackList, spotifyPlaylistId } from "../shared/playlist-import.js";

test("11. CSV playlists support quoted commas, metadata and duration fields", () => {
  const playlist = parsePlaylistCsv([
    "Title,Artist,Album,Duration,ISRC,URL",
    '"Caju, parte 2","Liniker, feat. Milton Nascimento","CAJU","3:42","BRABC2400001","https://open.spotify.com/track/example"',
  ].join("\n"));
  assert.equal(playlist.source, "CSV");
  assert.equal(playlist.tracks.length, 1);
  assert.equal(playlist.tracks[0].title, "Caju, parte 2");
  assert.equal(playlist.tracks[0].durationSeconds, 222);
  assert.equal(playlist.tracks[0].isrc, "BRABC2400001");
});

test("CSV import reports missing required columns and malformed quoting", () => {
  assert.throws(() => parsePlaylistCsv("Artist,Album\nNara,Album"), /needs a Title/);
  assert.throws(() => parsePlaylistCsv('Title,Artist\n"unclosed,Nara'), /unclosed quoted field/);
});

test("manual track lists accept artist-title and title-only entries", () => {
  assert.deepEqual(parseTrackList("Liniker — Caju\nApenas o título").tracks, [
    { title: "Caju", artist: "Liniker" },
    { title: "Apenas o título", artist: "" },
  ]);
});

test("Spotify playlist URL parsing rejects unrelated hosts and invalid paths", () => {
  const id = "0123456789012345678901";
  assert.equal(spotifyPlaylistId(`https://open.spotify.com/playlist/${id}?si=abc`), id);
  assert.equal(spotifyPlaylistId(`https://evil.example/playlist/${id}`), null);
  assert.equal(spotifyPlaylistId(`https://open.spotify.com/track/${id}`), null);
});
