import test from "node:test";
import assert from "node:assert/strict";
import { applyLibraryDelta, isUnchangedDriveFile } from "../shared/library-index.js";

test("incremental indexing skips unchanged file revisions", () => {
  const current = [{ id: "drive-1", revision: "4", title: "Keep me" }];
  const result = applyLibraryDelta(current, [{ id: "drive-1", revision: "4", title: "Should not replace" }]);
  assert.equal(result[0].title, "Keep me");
  assert.equal(isUnchangedDriveFile(current[0], { revision: "4", modifiedTime: current[0].modifiedTime }), true);
});

test("incremental indexing replaces changed revisions and removes deleted Drive IDs", () => {
  const current = [{ id: "drive-1", revision: "4", title: "Old" }, { id: "drive-2", title: "Removed" }];
  const result = applyLibraryDelta(current, [{ id: "drive-1", revision: "5", title: "New" }], ["drive-2"]);
  assert.deepEqual(result.map((file) => file.title), ["New"]);
});

test("an audio file without tags can be indexed by original filename without copying audio", () => {
  const result = applyLibraryDelta([], [{ id: "local-1", source: "local", name: "untagged.flac", title: "untagged", metadataError: "No tags" }]);
  assert.equal(result[0].name, "untagged.flac");
  assert.equal(result[0].metadataError, "No tags");
  assert.equal("audio" in result[0], false);
});
