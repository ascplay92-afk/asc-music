export const MAX_PLAYBACK_HISTORY = 100;

export function appendPlaybackHistory(history, track, playedAt = Date.now()) {
  const entry = {
    id: String(track.id || ""),
    title: String(track.title || ""),
    artist: String(track.artist || ""),
    album: String(track.album || ""),
    source: String(track.source || ""),
    playedAt,
  };
  return [entry, ...history].slice(0, MAX_PLAYBACK_HISTORY);
}
