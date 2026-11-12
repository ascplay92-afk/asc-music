export function accountStorageKey(name, ownerId) {
  return ownerId ? `asc.${name}.account.${encodeURIComponent(ownerId)}` : `asc.${name}.v1`;
}

export function readAccountLocalState(storage, ownerId) {
  return {
    playlists: JSON.parse(storage.getItem(accountStorageKey("playlists", ownerId)) || "[]"),
    favorites: new Set(JSON.parse(storage.getItem(accountStorageKey("favorites", ownerId)) || "[]")),
    history: JSON.parse(storage.getItem(accountStorageKey("history", ownerId)) || "[]"),
  };
}
