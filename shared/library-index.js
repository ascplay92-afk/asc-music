export function applyLibraryDelta(existing, changes, removedIds = []) {
  const byId = new Map(existing.map((file) => [file.id, file]));
  for (const file of changes) {
    if (!file?.id) throw new Error("Drive file changes must include a file ID.");
    const previous = byId.get(file.id);
    if (previous && previous.revision === file.revision) continue;
    byId.set(file.id, { ...file, checkedAt: new Date().toISOString() });
  }
  for (const id of removedIds) byId.delete(id);
  return [...byId.values()];
}

export function isUnchangedDriveFile(indexed, remote) {
  return Boolean(
    indexed
    && indexed.revision === remote.revision
    && indexed.modifiedTime === remote.modifiedTime,
  );
}
