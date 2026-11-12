export const TRACK_STATUS = Object.freeze({
  AVAILABLE: "AVAILABLE",
  MISSING: "MISSING",
  POSSIBLE_MATCH: "POSSIBLE_MATCH",
  DUPLICATE: "DUPLICATE",
  UNAVAILABLE_SOURCE: "UNAVAILABLE_SOURCE",
});

const VERSION_GROUPS = Object.freeze({
  live: /\b(live|ao vivo|en vivo|concert|acoustic live)\b/giu,
  remix: /\b(remix|remixed|mix version|club mix|extended mix|radio edit)\b/giu,
  acoustic: /\b(acoustic|acoustic version|acustic[oa])\b/giu,
  instrumental: /\b(instrumental|karaoke|backing track)\b/giu,
  remastered: /\b(remaster(?:ed)?|remasterizada?|201[0-9] remaster)\b/giu,
  explicit: /\b(explicit|explicit version|dirty version)\b/giu,
  clean: /\b(clean|clean version|radio version|censored)\b/giu,
});
const VERSION_LABELS = new Set([
  "live", "ao vivo", "en vivo", "concert", "acoustic live",
  "remix", "remixed", "mix version", "club mix", "extended mix", "radio edit",
  "acoustic", "acoustic version", "acustico", "acustica",
  "instrumental", "karaoke", "backing track",
  "remaster", "remastered", "remasterizada", "remasterizado",
  "explicit", "explicit version", "dirty version",
  "clean", "clean version", "radio version", "censored",
].map(normalizeText));

const FEAT_SEPARATOR = /\s+(?:feat(?:uring)?\.?|ft\.?|with|w\/)\s+/i;

export function normalizeText(value = "") {
  return String(value)
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLocaleLowerCase("en")
    .replace(/&/g, " and ")
    .replace(/[^\p{Letter}\p{Number}]+/gu, " ")
    .trim()
    .replace(/\s+/g, " ");
}

export function getTrackVariants(track = {}) {
  const values = [
    track.title ?? "",
    track.version ?? "",
    track.subtitle ?? "",
    track.explicit === true ? "explicit" : "",
    track.clean === true ? "clean" : "",
  ];
  const text = values.join(" ");
  const variants = new Set();
  for (const [variant, pattern] of Object.entries(VERSION_GROUPS)) {
    pattern.lastIndex = 0;
    if (pattern.test(text)) variants.add(variant);
  }
  for (const value of values) {
    const normalized = normalizeText(value);
    const liveLocation = normalized.match(/\b(?:live|concert|ao vivo|en vivo)\s+(?:at|from|in|em|no|na|de|do|da|desde)\s+(.+)$/);
    if (liveLocation) variants.add(`live-location:${liveLocation[1]}`);
    const remixType = normalized.match(/\b(club mix|extended mix|radio edit|mix version)\s*$/);
    if (remixType) variants.add(`remix-type:${remixType[1]}`);
    const remasterYear = normalized.match(/\b((?:19|20)\d{2})\s+remaster(?:ed)?\s*$/);
    if (remasterYear) variants.add(`remaster-year:${remasterYear[1]}`);
  }
  return variants;
}

export function normalizeTrackTitle(value = "") {
  let text = String(value);
  while (true) {
    const suffix = text.match(/\s*[\[(]([^\[\]()]+)[\])]\s*$/);
    if (!suffix || !VERSION_LABELS.has(normalizeText(suffix[1]))) break;
    text = text.slice(0, suffix.index);
  }
  return normalizeText(text);
}

export function artistNames(track = {}) {
  const values = [track.artist, ...(Array.isArray(track.artists) ? track.artists : [])]
    .filter(Boolean)
    .flatMap((value) => String(value).split(FEAT_SEPARATOR))
    .flatMap((value) => value.split(/\s*[,;&]\s*/))
    .map(normalizeText)
    .filter(Boolean);
  return [...new Set(values)];
}

function tokenSimilarity(left, right) {
  if (!left || !right) return 0;
  if (left === right) return 1;
  const a = new Set(left.split(" "));
  const b = new Set(right.split(" "));
  const intersection = [...a].filter((word) => b.has(word)).length;
  return intersection / (a.size + b.size - intersection);
}

function titleSimilarity(left, right) {
  const a = normalizeTrackTitle(left);
  const b = normalizeTrackTitle(right);
  if (a === b) return a ? 1 : 0;
  return tokenSimilarity(a, b);
}

function artistsSimilarity(leftTrack, rightTrack) {
  const left = artistNames(leftTrack);
  const right = artistNames(rightTrack);
  if (left.length === 0 || right.length === 0) return 0;
  const exact = left.some((artist) => right.includes(artist));
  if (exact) return 1;
  return Math.max(...left.map((a) => Math.max(...right.map((b) => tokenSimilarity(a, b)))));
}

function durationSimilarity(left, right) {
  const a = Number(left);
  const b = Number(right);
  if (!Number.isFinite(a) || !Number.isFinite(b) || a <= 0 || b <= 0) return null;
  const difference = Math.abs(a - b);
  if (difference <= 2) return 1;
  if (difference >= 20) return 0;
  return 1 - (difference - 2) / 18;
}

function variantComparison(playlistTrack, libraryTrack) {
  const expected = getTrackVariants(playlistTrack);
  const found = getTrackVariants(libraryTrack);
  const mismatches = [...new Set([...expected, ...found])].filter(
    (variant) => expected.has(variant) !== found.has(variant),
  );
  return mismatches;
}

export function scoreTrackMatch(playlistTrack, libraryTrack) {
  const isrc = normalizeText(playlistTrack.isrc);
  const fileIsrc = normalizeText(libraryTrack.isrc);
  if (isrc && fileIsrc && isrc === fileIsrc) {
    const variants = variantComparison(playlistTrack, libraryTrack);
    return { score: variants.length ? 84 : 100, variants, exactIdentifier: true };
  }

  const title = titleSimilarity(playlistTrack.title, libraryTrack.title);
  const artist = artistsSimilarity(playlistTrack, libraryTrack);
  const album = tokenSimilarity(normalizeText(playlistTrack.album), normalizeText(libraryTrack.album));
  const duration = durationSimilarity(playlistTrack.durationSeconds, libraryTrack.durationSeconds);
  let score = title * 48 + artist * 38 + album * 8 + (duration ?? 0.5) * 6;
  const variants = variantComparison(playlistTrack, libraryTrack);

  if (variants.length) score = Math.min(score, 67);
  if (!artist) score = Math.min(score, 74);
  if (!title) score = 0;
  return { score: Math.round(score), variants, exactIdentifier: false };
}

function hasTrustedIdentity(track) {
  return Boolean(normalizeText(track.isrc) || (normalizeTrackTitle(track.title) && artistNames(track).length));
}

export function matchTrack(playlistTrack, library = []) {
  const scored = library
    .map((file) => ({ file, ...scoreTrackMatch(playlistTrack, file) }))
    .sort((a, b) => b.score - a.score);
  const exact = scored.filter((candidate) => candidate.score >= 88 && candidate.variants.length === 0);

  if (exact.length > 1 && hasTrustedIdentity(playlistTrack)) {
    return { status: TRACK_STATUS.DUPLICATE, confidence: exact[0].score, candidates: exact };
  }
  if (exact.length === 1 && hasTrustedIdentity(playlistTrack)) {
    return { status: TRACK_STATUS.AVAILABLE, confidence: exact[0].score, candidates: exact };
  }
  const likely = scored.filter((candidate) => candidate.score >= 48).slice(0, 5);
  if (likely.length) {
    return { status: TRACK_STATUS.POSSIBLE_MATCH, confidence: likely[0].score, candidates: likely };
  }
  return {
    status: playlistTrack.authorizedSourceAvailable === false
      ? TRACK_STATUS.UNAVAILABLE_SOURCE
      : TRACK_STATUS.MISSING,
    confidence: 0,
    candidates: [],
  };
}

export function comparePlaylist(playlist, library = []) {
  const tracks = playlist.tracks.map((track) => ({
    ...track,
    match: matchTrack(track, library),
  }));
  const count = (status) => tracks.filter((track) => track.match.status === status).length;
  const available = count(TRACK_STATUS.AVAILABLE);
  const duplicates = count(TRACK_STATUS.DUPLICATE);
  const total = tracks.length;
  return {
    ...playlist,
    tracks,
    summary: {
      total,
      available,
      missing: count(TRACK_STATUS.MISSING),
      possibleMatches: count(TRACK_STATUS.POSSIBLE_MATCH),
      duplicates,
      unavailableSource: count(TRACK_STATUS.UNAVAILABLE_SOURCE),
      completeness: total ? Math.round(((available + duplicates) / total) * 100) : 0,
    },
  };
}
