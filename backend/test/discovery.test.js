import test from "node:test";
import assert from "node:assert/strict";
import { getDiscovery, getSimilarDiscovery } from "../src/discovery.js";

test("13. discovery explicitly reports that unavailable chart credentials produced no chart data", async () => {
  const result = await getDiscovery("brazil", {});
  assert.equal(result.tracks.length, 0);
  assert.equal(result.source, "No configured data source");
  assert.match(result.note, /No chart data is fabricated/);
  assert.ok(result.updatedAt);
});

test("recent releases identify release dates and never claim catalog-addition history", async () => {
  const result = await getDiscovery("releases", {
    MUSICBRAINZ_CONTACT_URL: "https://music.example/contact",
  }, async () => new Response(JSON.stringify({
    releases: [{
      id: "release-id",
      title: "New Album",
      date: "2026-11-10",
      status: "Official",
      "artist-credit": [{ name: "An Artist" }],
    }],
  })), new Date("2026-11-11T12:00:00Z"));
  assert.equal(result.source, "MusicBrainz");
  assert.equal(result.tracks[0].title, "New Album");
  assert.match(result.note, /not Spotify catalog-addition history/);
});

test("MusicBrainz is not queried without an explicit valid contact URL", async () => {
  const result = await getDiscovery("releases", {});
  assert.equal(result.tracks.length, 0);
  assert.match(result.note, /Set MUSICBRAINZ_CONTACT_URL/);
});

test("external provider failures are surfaced rather than replaced with fabricated data", async () => {
  await assert.rejects(
    getDiscovery("world", {}, async () => new Response("unavailable", { status: 503 })),
    /ListenBrainz returned HTTP 503/,
  );
});

test("similar discoveries do not claim personalization without credentials or seed artists", async () => {
  const missingKey = await getSimilarDiscovery("Anitta", {});
  assert.equal(missingKey.tracks.length, 0);
  assert.match(missingKey.note, /No recommendations are fabricated/);

  const missingSeeds = await getSimilarDiscovery("", { LASTFM_API_KEY: "configured" });
  assert.equal(missingSeeds.tracks.length, 0);
  assert.match(missingSeeds.note, /Index a library/);
});
