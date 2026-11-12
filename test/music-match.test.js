import test from "node:test";
import assert from "node:assert/strict";
import { comparePlaylist, matchTrack, normalizeText, TRACK_STATUS } from "../shared/music.js";

const file = (id, values = {}) => ({
  id,
  name: `${values.title || "Track"}.mp3`,
  source: "drive",
  title: "Luz do Sol",
  artist: "Nara Leão",
  album: "Meus Momentos",
  durationSeconds: 198,
  ...values,
});
const requested = (values = {}) => ({
  title: "Luz do Sol",
  artist: "Nara Leão",
  album: "Meus Momentos",
  durationSeconds: 198,
  ...values,
});

test("1. exact title and artist are automatically available", () => {
  assert.equal(matchTrack(requested(), [file("one")]).status, TRACK_STATUS.AVAILABLE);
});

test("2. a filename unrelated to metadata still matches the recording", () => {
  const libraryFile = file("two", { name: "faixa_07_arquivo.mp3" });
  assert.equal(matchTrack(requested(), [libraryFile]).status, TRACK_STATUS.AVAILABLE);
});

test("3. diacritic differences are normalized", () => {
  assert.equal(normalizeText("Corações à Deriva"), "coracoes a deriva");
  assert.equal(
    matchTrack(requested({ title: "Corações à deriva", artist: "Marisa Monte" }), [
      file("three", { title: "Coracoes a deriva", artist: "Marisa Monte" }),
    ]).status,
    TRACK_STATUS.AVAILABLE,
  );
});

test("4. a featured artist is included in artist matching", () => {
  assert.equal(
    matchTrack(requested({ title: "Downtown", artist: "Anitta", artists: ["Anitta", "J Balvin"] }), [
      file("four", { title: "Downtown", artist: "Anitta", artists: ["Anitta", "J Balvin"] }),
    ]).status,
    TRACK_STATUS.AVAILABLE,
  );
});

test("5. a live recording is never silently matched to the studio original", () => {
  assert.equal(
    matchTrack(requested({ title: "Luz do Sol (Ao Vivo)" }), [
      file("five", { title: "Luz do Sol" }),
    ]).status,
    TRACK_STATUS.POSSIBLE_MATCH,
  );
});

test("6. a remix is not silently matched to the original recording", () => {
  assert.equal(
    matchTrack(requested({ title: "Luz do Sol", version: "Club Remix" }), [
      file("six", { title: "Luz do Sol" }),
    ]).status,
    TRACK_STATUS.POSSIBLE_MATCH,
  );
});

test("parenthetical text that is not a version label stays part of the track identity", () => {
  const result = matchTrack(requested({ title: "Luz do Sol (Parte 2)" }), [
    file("part-one", { title: "Luz do Sol" }),
  ]);
  assert.equal(result.status, TRACK_STATUS.POSSIBLE_MATCH);
});

test("different live venues remain ambiguous instead of collapsing to one recording", () => {
  const result = matchTrack(requested({ title: "Luz do Sol (Live at Wembley)" }), [
    file("live-tokyo", { title: "Luz do Sol (Live at Tokyo)" }),
  ]);
  assert.equal(result.status, TRACK_STATUS.POSSIBLE_MATCH);
});

test("distinct remix subtypes remain candidates instead of automatic matches", () => {
  const result = matchTrack(requested({ version: "Club Mix" }), [
    file("extended-remix", { version: "Extended Mix" }),
  ]);
  assert.equal(result.status, TRACK_STATUS.POSSIBLE_MATCH);
});

test("different remaster years remain candidates instead of automatic matches", () => {
  const result = matchTrack(requested({ title: "Luz do Sol (2018 Remaster)" }), [
    file("2020-remaster", { title: "Luz do Sol (2020 Remaster)" }),
  ]);
  assert.equal(result.status, TRACK_STATUS.POSSIBLE_MATCH);
});

test("7. multiple exact library files are reported as duplicates", () => {
  assert.equal(matchTrack(requested(), [file("seven-a"), file("seven-b")]).status, TRACK_STATUS.DUPLICATE);
});

test("8. files without artist metadata remain reviewable, not automatically available", () => {
  const result = matchTrack(requested(), [file("eight", { title: "Luz do Sol", artist: "", album: "" })]);
  assert.equal(result.status, TRACK_STATUS.POSSIBLE_MATCH);
  assert.equal(result.candidates[0].file.id, "eight");
});

test("9. a Drive file removed from the current scan no longer matches", async () => {
  const { applyLibraryDelta } = await import("../shared/library-index.js");
  const remaining = applyLibraryDelta([file("nine")], [], ["nine"]);
  assert.equal(matchTrack(requested(), remaining).status, TRACK_STATUS.MISSING);
});

test("10. songs added after playlist creation are picked up by a fresh comparison", () => {
  const playlist = { id: "ten", name: "Later", tracks: [requested()] };
  assert.equal(comparePlaylist(playlist, []).tracks[0].match.status, TRACK_STATUS.MISSING);
  assert.equal(comparePlaylist(playlist, [file("ten")]).tracks[0].match.status, TRACK_STATUS.AVAILABLE);
});

test("12. partial playlist completeness counts only usable available recordings", () => {
  const playlist = {
    id: "twelve",
    name: "Partial",
    tracks: [
      requested(),
      requested({ title: "Unknown song", artist: "Unknown artist" }),
      requested({ title: "Luz do Sol (Ao Vivo)" }),
    ],
  };
  const result = comparePlaylist(playlist, [file("twelve-file")]);
  assert.deepEqual(result.tracks.map((track) => track.match.status), [
    TRACK_STATUS.AVAILABLE,
    TRACK_STATUS.MISSING,
    TRACK_STATUS.POSSIBLE_MATCH,
  ]);
  assert.equal(result.summary.completeness, 33);
  assert.equal(result.summary.total, 3);
  assert.equal(result.summary.possibleMatches, 1);
});

test("an empty playlist reports no analyzed songs even when the library contains files", () => {
  const result = comparePlaylist({ id: "empty", name: "Empty", tracks: [] }, [file("indexed-but-not-analyzed")]);
  assert.equal(result.summary.total, 0);
  assert.equal(result.summary.available, 0);
  assert.equal(result.summary.missing, 0);
  assert.equal(result.summary.completeness, 0);
});

test("an identified track with no authorized source has a distinct status", () => {
  assert.equal(
    matchTrack(requested({ authorizedSourceAvailable: false }), []).status,
    TRACK_STATUS.UNAVAILABLE_SOURCE,
  );
});
