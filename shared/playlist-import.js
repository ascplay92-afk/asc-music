function parseCsvRow(row, delimiter) {
  const fields = [];
  let value = "";
  let quoted = false;
  for (let index = 0; index < row.length; index += 1) {
    const character = row[index];
    if (character === '"') {
      if (quoted && row[index + 1] === '"') {
        value += '"';
        index += 1;
      } else {
        quoted = !quoted;
      }
    } else if (character === delimiter && !quoted) {
      fields.push(value.trim());
      value = "";
    } else {
      value += character;
    }
  }
  if (quoted) throw new Error("CSV contains an unclosed quoted field.");
  fields.push(value.trim());
  return fields;
}

function parseDuration(value) {
  if (!value) return undefined;
  const seconds = Number(value);
  if (Number.isFinite(seconds)) return seconds;
  const parts = value.split(":").map(Number);
  if (parts.length === 2 && parts.every(Number.isFinite)) return parts[0] * 60 + parts[1];
  if (parts.length === 3 && parts.every(Number.isFinite)) return parts[0] * 3600 + parts[1] * 60 + parts[2];
  return undefined;
}

function normalizeHeader(value) {
  return value.normalize("NFD").replace(/\p{Diacritic}/gu, "").toLowerCase().replace(/[^a-z0-9]/g, "");
}

export function parsePlaylistCsv(text) {
  const rows = String(text).replace(/^\uFEFF/, "").split(/\r?\n/).filter((row) => row.trim());
  if (rows.length < 2) throw new Error("CSV must include a header and at least one track.");
  const delimiter = (rows[0].match(/;/g) ?? []).length > (rows[0].match(/,/g) ?? []).length ? ";" : ",";
  const headers = parseCsvRow(rows[0], delimiter).map(normalizeHeader);
  const indexOf = (...names) => headers.findIndex((header) => names.includes(header));
  const titleIndex = indexOf("title", "track", "trackname", "name", "song");
  const artistIndex = indexOf("artist", "artists", "artistname");
  if (titleIndex < 0) throw new Error("CSV needs a Title, Track, Name, or Song column.");

  const records = rows.slice(1).map((row, rowNumber) => {
    const cells = parseCsvRow(row, delimiter);
    const cell = (index) => index >= 0 ? cells[index] ?? "" : "";
    const title = cell(titleIndex);
    if (!title) throw new Error(`CSV row ${rowNumber + 2} is missing a track title.`);
    return {
      title,
      artist: cell(artistIndex),
      album: cell(indexOf("album", "albumname")),
      durationSeconds: parseDuration(cell(indexOf("duration", "durationseconds", "length"))),
      isrc: cell(indexOf("isrc")),
      sourceUrl: cell(indexOf("url", "trackurl", "externalurl")),
    };
  });
  return { name: "", description: "", source: "CSV", tracks: records };
}

export function parseTrackList(text) {
  const tracks = String(text)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const separator = line.match(/\s+[—–-]\s+/);
      if (!separator) return { title: line, artist: "" };
      const [artist, title] = line.split(separator[0], 2);
      return { title: title.trim(), artist: artist.trim() };
    });
  if (!tracks.length) throw new Error("Enter at least one song.");
  return { name: "", description: "", source: "Text", tracks };
}

export function spotifyPlaylistId(value) {
  const input = String(value).trim();
  if (/^[A-Za-z0-9]{22}$/.test(input)) return input;
  let url;
  try {
    url = new URL(input);
  } catch {
    return null;
  }
  if (!["open.spotify.com", "www.open.spotify.com"].includes(url.hostname)) return null;
  return url.pathname.match(/^\/playlist\/([A-Za-z0-9]{22})(?:\/|$)/)?.[1] ?? null;
}
