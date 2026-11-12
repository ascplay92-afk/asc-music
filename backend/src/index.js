import { authenticateRequest } from "./auth.js";
import { getDiscovery, getSimilarDiscovery, SECTIONS } from "./discovery.js";

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

function asTrack(row) {
  return {
    title: row.title,
    artist: row.artist,
    album: row.album,
    durationSeconds: row.duration_seconds,
    isrc: row.isrc,
    sourceUrl: row.source_url,
    ...JSON.parse(row.metadata_json || "{}"),
  };
}

function validTrack(track) {
  return track
    && typeof track.title === "string"
    && track.title.trim().length > 0
    && track.title.length <= 500
    && (track.artist === undefined || (typeof track.artist === "string" && track.artist.length <= 500))
    && (track.album === undefined || (typeof track.album === "string" && track.album.length <= 500))
    && (track.sourceUrl === undefined || (typeof track.sourceUrl === "string" && track.sourceUrl.length <= 2048));
}

export async function readJson(request) {
  const maximumBytes = 1024 * 1024;
  const contentLength = request.headers.get("content-length");
  if (contentLength !== null && Number(contentLength) > maximumBytes) {
    return { error: "Request body exceeds 1 MiB.", status: 413 };
  }
  const reader = request.body?.getReader();
  if (!reader) return { error: "Request body must be valid JSON.", status: 400 };
  const chunks = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > maximumBytes) {
        try {
          await reader.cancel();
        } catch {
          // A failed cancellation does not change the oversized-body response.
        }
        return { error: "Request body exceeds 1 MiB.", status: 413 };
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  let body;
  try {
    body = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  } catch {
    return { error: "Request body must be valid UTF-8 JSON.", status: 400 };
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) return { error: "A JSON object is required.", status: 400 };
  return body;
}

async function requireIdentity(request, env, options) {
  return authenticateRequest(request, env, options);
}

async function listPlaylists(userId, env) {
  const rows = await env.DB.prepare(
    "SELECT id, name, description, source, source_url, created_at, updated_at FROM playlists WHERE user_id = ? ORDER BY updated_at DESC",
  ).bind(userId).all();
  const tracks = await env.DB.prepare(
    "SELECT playlist_id, title, artist, album, duration_seconds, isrc, source_url, metadata_json FROM playlist_tracks WHERE user_id = ? ORDER BY playlist_id, position",
  ).bind(userId).all();
  const tracksByPlaylist = new Map();
  for (const track of tracks.results ?? []) {
    const playlistTracks = tracksByPlaylist.get(track.playlist_id) ?? [];
    playlistTracks.push(asTrack(track));
    tracksByPlaylist.set(track.playlist_id, playlistTracks);
  }
  return (rows.results ?? []).map((playlist) => ({
    id: playlist.id,
    name: playlist.name,
    description: playlist.description,
    source: playlist.source,
    sourceUrl: playlist.source_url,
    createdAt: playlist.created_at,
    updatedAt: playlist.updated_at,
    tracks: tracksByPlaylist.get(playlist.id) ?? [],
  }));
}

async function createPlaylist(request, userId, env) {
  const body = await readJson(request);
  if (body.error) return jsonResponse({ error: body.error }, body.status ?? 400);
  if (
    typeof body.name !== "string"
    || !body.name.trim()
    || body.name.length > 200
    || !Array.isArray(body.tracks)
    || body.tracks.length > 1000
    || !body.tracks.every(validTrack)
  ) {
    return jsonResponse({ error: "Playlist name or tracks are invalid." }, 400);
  }
  const id = crypto.randomUUID();
  const timestamp = new Date().toISOString();
  const name = body.name.trim();
  const source = typeof body.source === "string" && body.source.length <= 40 ? body.source : "manual";
  const sourceUrl = typeof body.sourceUrl === "string" && body.sourceUrl.length <= 2048 ? body.sourceUrl : null;
  const statements = [
    env.DB.prepare(
      "INSERT INTO playlists (user_id, id, name, description, source, source_url, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    ).bind(userId, id, name, String(body.description ?? "").slice(0, 2000), source, sourceUrl, timestamp, timestamp),
    ...body.tracks.map((track, position) => env.DB.prepare(
      "INSERT INTO playlist_tracks (user_id, playlist_id, position, title, artist, album, duration_seconds, isrc, source_url, metadata_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ).bind(
      userId,
      id,
      position,
      track.title.trim(),
      String(track.artist ?? "").slice(0, 500),
      String(track.album ?? "").slice(0, 500),
      Number.isFinite(track.durationSeconds) ? track.durationSeconds : null,
      typeof track.isrc === "string" ? track.isrc.slice(0, 40) : null,
      typeof track.sourceUrl === "string" ? track.sourceUrl.slice(0, 2048) : null,
      JSON.stringify({
        artists: Array.isArray(track.artists) ? track.artists.slice(0, 20).map((artist) => String(artist).slice(0, 200)) : [],
        authorizedSourceAvailable: track.authorizedSourceAvailable !== false,
      }),
    )),
  ];
  await env.DB.batch(statements);
  return jsonResponse({ id, name, createdAt: timestamp }, 201);
}

async function updatePlaylist(request, userId, env, playlistId) {
  const body = await readJson(request);
  if (body.error) return jsonResponse({ error: body.error }, body.status ?? 400);
  if (
    typeof body.name !== "string"
    || !body.name.trim()
    || body.name.length > 200
    || !Array.isArray(body.tracks)
    || body.tracks.length > 1000
    || !body.tracks.every(validTrack)
  ) {
    return jsonResponse({ error: "Playlist name or tracks are invalid." }, 400);
  }
  const timestamp = new Date().toISOString();
  const statements = [
    env.DB.prepare(
      "UPDATE playlists SET name = ?, description = ?, source = ?, source_url = ?, updated_at = ? WHERE user_id = ? AND id = ?",
    ).bind(
      body.name.trim(),
      String(body.description ?? "").slice(0, 2000),
      typeof body.source === "string" && body.source.length <= 40 ? body.source : "manual",
      typeof body.sourceUrl === "string" && body.sourceUrl.length <= 2048 ? body.sourceUrl : null,
      timestamp,
      userId,
      playlistId,
    ),
    env.DB.prepare("DELETE FROM playlist_tracks WHERE user_id = ? AND playlist_id = ?").bind(userId, playlistId),
    ...body.tracks.map((track, position) => env.DB.prepare(
      `INSERT INTO playlist_tracks (user_id, playlist_id, position, title, artist, album, duration_seconds, isrc, source_url, metadata_json)
       SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
       WHERE EXISTS (SELECT 1 FROM playlists WHERE user_id = ? AND id = ?)`,
    ).bind(
      userId,
      playlistId,
      position,
      track.title.trim(),
      String(track.artist ?? "").slice(0, 500),
      String(track.album ?? "").slice(0, 500),
      Number.isFinite(track.durationSeconds) ? track.durationSeconds : null,
      typeof track.isrc === "string" ? track.isrc.slice(0, 40) : null,
      typeof track.sourceUrl === "string" ? track.sourceUrl.slice(0, 2048) : null,
      JSON.stringify({
        artists: Array.isArray(track.artists) ? track.artists.slice(0, 20).map((artist) => String(artist).slice(0, 200)) : [],
        authorizedSourceAvailable: track.authorizedSourceAvailable !== false,
      }),
      userId,
      playlistId,
    )),
  ];
  const [changed] = await env.DB.batch(statements);
  if (!changed.meta?.changes) return jsonResponse({ error: "Playlist not found." }, 404);
  return jsonResponse({ id: playlistId, name: body.name.trim(), updatedAt: timestamp });
}

async function syncLibrary(request, userId, env) {
  const body = await readJson(request);
  if (body.error) return jsonResponse({ error: body.error }, body.status ?? 400);
  if (
    !Array.isArray(body.files)
    || body.files.length > 500
    || (body.removedIds !== undefined && (
      !Array.isArray(body.removedIds)
      || body.removedIds.length > 500
      || !body.removedIds.every((id) => typeof id === "string" && id.length > 0 && id.length <= 512)
    ))
  ) {
    return jsonResponse({ error: "A library sync must include up to 500 files." }, 400);
  }
  const invalidFile = body.files.find((file) => (
    !file
    || typeof file.id !== "string"
    || !file.id
    || file.id.length > 512
    || typeof file.name !== "string"
    || !file.name.trim()
    || file.name.length > 1000
    || !["drive", "local"].includes(file.source)
  ));
  if (invalidFile) return jsonResponse({ error: "Each library file needs a valid ID, name, and source." }, 400);
  const statements = [
    ...(body.removedIds || []).map((id) => env.DB.prepare(
      "DELETE FROM library_files WHERE user_id = ? AND file_id = ?",
    ).bind(userId, id)),
    ...body.files.map((file) => env.DB.prepare(
      `INSERT INTO library_files (user_id, file_id, source, name, title, artist, album, duration_seconds, format, size_bytes, revision, metadata_json, checked_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(user_id, file_id) DO UPDATE SET
         source = excluded.source, name = excluded.name, title = excluded.title,
         artist = excluded.artist, album = excluded.album, duration_seconds = excluded.duration_seconds,
         format = excluded.format, size_bytes = excluded.size_bytes, revision = excluded.revision,
         metadata_json = excluded.metadata_json, checked_at = excluded.checked_at`,
    ).bind(
      userId,
      file.id,
      file.source,
      file.name.trim(),
      typeof file.title === "string" ? file.title.slice(0, 500) : null,
      typeof file.artist === "string" ? file.artist.slice(0, 500) : null,
      typeof file.album === "string" ? file.album.slice(0, 500) : null,
      Number.isFinite(file.durationSeconds) ? file.durationSeconds : null,
      typeof file.format === "string" ? file.format.slice(0, 30) : null,
      Number.isSafeInteger(file.sizeBytes) ? file.sizeBytes : null,
      typeof file.revision === "string" ? file.revision.slice(0, 200) : null,
      JSON.stringify({
        isrc: typeof file.isrc === "string" ? file.isrc.slice(0, 40) : "",
        artists: Array.isArray(file.artists) ? file.artists.slice(0, 20).map((artist) => String(artist).slice(0, 200)) : [],
        metadataError: typeof file.metadataError === "string" ? file.metadataError.slice(0, 500) : "",
      }),
      new Date().toISOString(),
    )),
  ];
  if (statements.length) await env.DB.batch(statements);
  return jsonResponse({ updated: body.files.length, removed: body.removedIds?.length ?? 0 });
}

const SYNC_ENTITY_TYPES = new Set(["playlist", "favorite", "history", "library", "setting"]);
const ACCOUNT_SETTING_KEYS = new Set(["shuffle", "repeat", "volume"]);

function normalizeSyncPayload(type, id, payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
  if (type === "playlist") {
    if (
      typeof payload.name !== "string"
      || !payload.name.trim()
      || payload.name.length > 200
      || !Array.isArray(payload.tracks)
      || payload.tracks.length > 1000
      || !payload.tracks.every(validTrack)
    ) return null;
    return {
      name: payload.name.trim(),
      description: typeof payload.description === "string" ? payload.description.slice(0, 2000) : "",
      source: typeof payload.source === "string" && payload.source.length <= 40 ? payload.source : "manual",
      sourceUrl: typeof payload.sourceUrl === "string" ? payload.sourceUrl.slice(0, 2048) : "",
      tracks: payload.tracks.map((track) => ({
        title: track.title.trim(),
        artist: typeof track.artist === "string" ? track.artist.slice(0, 500) : "",
        artists: Array.isArray(track.artists) ? track.artists.slice(0, 20).filter((artist) => typeof artist === "string").map((artist) => artist.slice(0, 200)) : [],
        album: typeof track.album === "string" ? track.album.slice(0, 500) : "",
        durationSeconds: Number.isFinite(track.durationSeconds) ? track.durationSeconds : null,
        isrc: typeof track.isrc === "string" ? track.isrc.slice(0, 40) : "",
        sourceUrl: typeof track.sourceUrl === "string" ? track.sourceUrl.slice(0, 2048) : "",
        authorizedSourceAvailable: track.authorizedSourceAvailable !== false,
        explicit: track.explicit === true,
      })),
    };
  }
  if (type === "favorite") {
    if (typeof payload.favorite !== "boolean") return null;
    return { key: id, favorite: payload.favorite };
  }
  if (type === "history") {
    if (
      typeof payload.trackId !== "string" || !payload.trackId || payload.trackId.length > 512
      || typeof payload.title !== "string" || !payload.title.trim() || payload.title.length > 500
      || (payload.artist !== undefined && (typeof payload.artist !== "string" || payload.artist.length > 500))
      || (payload.album !== undefined && (typeof payload.album !== "string" || payload.album.length > 500))
      || !Number.isSafeInteger(payload.playedAt)
    ) return null;
    return {
      trackId: payload.trackId,
      title: payload.title.trim(),
      artist: typeof payload.artist === "string" ? payload.artist : "",
      album: typeof payload.album === "string" ? payload.album : "",
      playedAt: payload.playedAt,
    };
  }
  if (type === "library") {
    if (
      typeof payload.source !== "string" || !["drive", "local"].includes(payload.source)
      || typeof payload.name !== "string" || !payload.name.trim() || payload.name.length > 1000
      || (payload.title !== undefined && (typeof payload.title !== "string" || payload.title.length > 500))
      || (payload.artist !== undefined && (typeof payload.artist !== "string" || payload.artist.length > 500))
      || (payload.album !== undefined && (typeof payload.album !== "string" || payload.album.length > 500))
    ) return null;
    return {
      source: payload.source,
      name: payload.name.trim(),
      title: typeof payload.title === "string" ? payload.title : "",
      artist: typeof payload.artist === "string" ? payload.artist : "",
      album: typeof payload.album === "string" ? payload.album : "",
      durationSeconds: Number.isFinite(payload.durationSeconds) ? payload.durationSeconds : null,
      format: typeof payload.format === "string" ? payload.format.slice(0, 30) : "",
      sizeBytes: Number.isSafeInteger(payload.sizeBytes) && payload.sizeBytes >= 0 ? payload.sizeBytes : null,
      revision: typeof payload.revision === "string" ? payload.revision.slice(0, 200) : "",
      isrc: typeof payload.isrc === "string" ? payload.isrc.slice(0, 40) : "",
      artists: Array.isArray(payload.artists) ? payload.artists.slice(0, 20).filter((artist) => typeof artist === "string").map((artist) => artist.slice(0, 200)) : [],
    };
  }
  if (type === "setting") {
    if (!ACCOUNT_SETTING_KEYS.has(id)) return null;
    if (id === "shuffle" && typeof payload.value === "boolean") return { value: payload.value };
    if (id === "repeat" && ["off", "all", "one"].includes(payload.value)) return { value: payload.value };
    if (id === "volume" && typeof payload.value === "number" && Number.isFinite(payload.value) && payload.value >= 0 && payload.value <= 1) {
      return { value: payload.value };
    }
  }
  return null;
}

function asSyncRecord(row) {
  return {
    sequence: row.sequence,
    type: row.entity_type,
    id: row.entity_id,
    version: row.version,
    deleted: row.deleted === 1,
    payload: JSON.parse(row.payload_json || "{}"),
    updatedAt: row.updated_at,
  };
}

async function syncAccount(request, userId, env) {
  const url = new URL(request.url);
  if (request.method === "GET") {
    const rawCursor = url.searchParams.get("cursor") ?? "0";
    const cursor = Number(rawCursor);
    const limit = Number(url.searchParams.get("limit") ?? "200");
    if (!Number.isSafeInteger(cursor) || cursor < 0 || !Number.isInteger(limit) || limit < 1 || limit > 500) {
      return jsonResponse({ error: "Sync cursor or page limit is invalid." }, 400);
    }
    const rows = await env.DB.prepare(
      "SELECT sequence, entity_type, entity_id, version, payload_json, deleted, updated_at FROM sync_events WHERE user_id = ? AND sequence > ? ORDER BY sequence LIMIT ?",
    ).bind(userId, cursor, limit + 1).all();
    const events = rows.results ?? [];
    const hasMore = events.length > limit;
    const page = events.slice(0, limit).map(asSyncRecord);
    return jsonResponse({
      changes: page,
      cursor: page.at(-1)?.sequence ?? cursor,
      hasMore,
    });
  }

  const body = await readJson(request);
  if (body.error) return jsonResponse({ error: body.error }, body.status ?? 400);
  if (!Array.isArray(body.changes) || body.changes.length < 1 || body.changes.length > 100) {
    return jsonResponse({ error: "Sync requires between 1 and 100 changes." }, 400);
  }
  const results = [];
  for (const change of body.changes) {
    if (
      !change || typeof change !== "object" || Array.isArray(change)
      || typeof change.operationId !== "string" || !/^[A-Za-z0-9_-]{1,100}$/.test(change.operationId)
      || !SYNC_ENTITY_TYPES.has(change.type)
      || typeof change.id !== "string" || !change.id || change.id.length > 512
      || /[\\/\u0000-\u001f]/.test(change.id)
      || !Number.isSafeInteger(change.baseVersion) || change.baseVersion < 0
      || typeof change.deleted !== "boolean"
    ) return jsonResponse({ error: "A sync change has invalid identity, version, or operation fields." }, 400);
    if (change.deleted && (change.type === "history" || change.type === "setting")) {
      return jsonResponse({ error: "History and shared settings cannot be deleted through sync." }, 400);
    }
    const payload = change.deleted ? {} : normalizeSyncPayload(change.type, change.id, change.payload);
    if (!change.deleted && !payload) {
      return jsonResponse({ error: `The ${change.type} sync payload is invalid.` }, 400);
    }
    const payloadJson = JSON.stringify(payload);
    if (payloadJson.length > 48 * 1024) return jsonResponse({ error: "A sync entity exceeds 48 KiB." }, 413);

    const operationDigest = await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(JSON.stringify({
        type: change.type,
        id: change.id,
        baseVersion: change.baseVersion,
        deleted: change.deleted,
        payload,
      })),
    );
    const requestHash = Array.from(new Uint8Array(operationDigest), (byte) => byte.toString(16).padStart(2, "0")).join("");
    const priorOperation = await env.DB.prepare(
      "SELECT entity_type, entity_id, resulting_version, request_hash FROM sync_operations WHERE user_id = ? AND operation_id = ?",
    ).bind(userId, change.operationId).first();
    if (priorOperation) {
      if (
        priorOperation.entity_type !== change.type
        || priorOperation.entity_id !== change.id
        || priorOperation.request_hash !== requestHash
      ) {
        return jsonResponse({ error: "An operation ID cannot be reused for a different sync change." }, 409);
      }
      results.push({ operationId: change.operationId, status: "applied", version: priorOperation.resulting_version });
      continue;
    }
    const current = await env.DB.prepare(
      "SELECT version, payload_json, deleted, updated_at FROM sync_entities WHERE user_id = ? AND entity_type = ? AND entity_id = ?",
    ).bind(userId, change.type, change.id).first();
    const currentVersion = current?.version ?? 0;
    if (currentVersion !== change.baseVersion) {
      results.push({
        operationId: change.operationId,
        status: "conflict",
        current: current ? {
          type: change.type,
          id: change.id,
          version: current.version,
          deleted: current.deleted === 1,
          payload: JSON.parse(current.payload_json || "{}"),
          updatedAt: current.updated_at,
        } : null,
      });
      continue;
    }
    const nextVersion = currentVersion + 1;
    const updatedAt = new Date().toISOString();
    const batch = await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO sync_entities (user_id, entity_type, entity_id, version, payload_json, deleted, updated_at, last_operation_id)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(user_id, entity_type, entity_id) DO UPDATE SET
           version = excluded.version, payload_json = excluded.payload_json, deleted = excluded.deleted,
           updated_at = excluded.updated_at, last_operation_id = excluded.last_operation_id
         WHERE sync_entities.version = ?
         RETURNING version`,
      ).bind(userId, change.type, change.id, nextVersion, payloadJson, change.deleted ? 1 : 0, updatedAt, change.operationId, currentVersion),
      env.DB.prepare(
        `INSERT INTO sync_events (user_id, entity_type, entity_id, version, payload_json, deleted, updated_at)
         SELECT ?, ?, ?, ?, ?, ?, ?
         WHERE EXISTS (SELECT 1 FROM sync_entities WHERE user_id = ? AND entity_type = ? AND entity_id = ? AND last_operation_id = ? AND version = ?)`,
      ).bind(userId, change.type, change.id, nextVersion, payloadJson, change.deleted ? 1 : 0, updatedAt, userId, change.type, change.id, change.operationId, nextVersion),
      env.DB.prepare(
        `INSERT INTO sync_operations (user_id, operation_id, entity_type, entity_id, resulting_version, request_hash)
         SELECT ?, ?, ?, ?, ?, ?
         WHERE EXISTS (SELECT 1 FROM sync_entities WHERE user_id = ? AND entity_type = ? AND entity_id = ? AND last_operation_id = ? AND version = ?)`,
      ).bind(userId, change.operationId, change.type, change.id, nextVersion, requestHash, userId, change.type, change.id, change.operationId, nextVersion),
    ]);
    const changed = batch[0]?.results?.length ?? batch[0]?.meta?.changes ?? 0;
    if (!changed) {
      const latest = await env.DB.prepare(
        "SELECT version, payload_json, deleted, updated_at FROM sync_entities WHERE user_id = ? AND entity_type = ? AND entity_id = ?",
      ).bind(userId, change.type, change.id).first();
      results.push({
        operationId: change.operationId,
        status: "conflict",
        current: latest ? {
          type: change.type,
          id: change.id,
          version: latest.version,
          deleted: latest.deleted === 1,
          payload: JSON.parse(latest.payload_json || "{}"),
          updatedAt: latest.updated_at,
        } : null,
      });
    } else {
      results.push({ operationId: change.operationId, status: "applied", version: nextVersion });
    }
  }
  return jsonResponse({ results });
}

async function enforceRateLimit(userId, env) {
  const windowStart = Math.floor(Date.now() / 60_000);
  const result = await env.DB.prepare(
    `INSERT INTO api_rate_limits (user_id, window_start, request_count) VALUES (?, ?, 1)
     ON CONFLICT(user_id) DO UPDATE SET
       request_count = CASE WHEN window_start = excluded.window_start THEN request_count + 1 ELSE 1 END,
       window_start = excluded.window_start
     RETURNING request_count`,
  ).bind(userId, windowStart).first();
  return Number(result?.request_count || 0) <= 120;
}

export async function handleRequest(request, env, options = {}) {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/api/health") {
    return jsonResponse({ ok: true, service: "ASC Music API", version: "0.1.0" });
  }
  if (request.method === "GET" && url.pathname === "/api/discovery") {
    return jsonResponse({ sections: Object.entries(SECTIONS).map(([id, section]) => ({ id, label: section.label })) });
  }
  const discoveryMatch = url.pathname.match(/^\/api\/discovery\/([a-z-]+)$/);
  if (request.method === "GET" && discoveryMatch && discoveryMatch[1] !== "similar") {
    const result = await getDiscovery(discoveryMatch[1], env, options.fetch ?? fetch);
    return result.error
      ? jsonResponse({ error: result.error }, result.status)
      : jsonResponse(result);
  }

  const identity = await requireIdentity(request, env, options);
  if (!identity.ok) return jsonResponse({ error: identity.error }, identity.status);
  if (!env.DB) return jsonResponse({ error: "The D1 database is not configured." }, 503);
  if (!await enforceRateLimit(identity.userId, env)) {
    return jsonResponse({ error: "Rate limit exceeded. Try again in a minute." }, 429);
  }
  if (request.method === "GET" && url.pathname === "/api/discovery/similar") {
    const result = await getSimilarDiscovery(url.searchParams.get("artists"), env, options.fetch ?? fetch);
    return jsonResponse(result);
  }
  if (!["GET", "HEAD"].includes(request.method)) {
    const origin = request.headers.get("Origin");
    const bearer = /^Bearer [^\s]+$/i.test(request.headers.get("Authorization") ?? "");
    if ((origin && (!env.APP_ORIGIN || origin !== env.APP_ORIGIN)) || (!origin && !bearer)) {
      return jsonResponse({ error: "The request origin is not allowed." }, 403);
    }
  }
  if (request.method === "GET" && url.pathname === "/api/playlists") {
    return jsonResponse({ playlists: await listPlaylists(identity.userId, env) });
  }
  if (url.pathname === "/api/sync" && ["GET", "POST"].includes(request.method)) {
    return syncAccount(request, identity.userId, env);
  }
  if (request.method === "POST" && url.pathname === "/api/playlists") {
    return createPlaylist(request, identity.userId, env);
  }
  const updatePlaylistMatch = url.pathname.match(/^\/api\/playlists\/([A-Za-z0-9-]+)$/);
  if (request.method === "PUT" && updatePlaylistMatch) {
    return updatePlaylist(request, identity.userId, env, updatePlaylistMatch[1]);
  }
  if (request.method === "POST" && url.pathname === "/api/library/sync") {
    return syncLibrary(request, identity.userId, env);
  }
  if (request.method === "GET" && url.pathname === "/api/library") {
    const rows = await env.DB.prepare(
      "SELECT file_id, source, name, title, artist, album, duration_seconds, format, size_bytes, revision, metadata_json, checked_at FROM library_files WHERE user_id = ? ORDER BY artist, title, name",
    ).bind(identity.userId).all();
    return jsonResponse({
      files: (rows.results ?? []).map((file) => ({
        id: file.file_id,
        source: file.source,
        name: file.name,
        title: file.title,
        artist: file.artist,
        album: file.album,
        durationSeconds: file.duration_seconds,
        format: file.format,
        sizeBytes: file.size_bytes,
        revision: file.revision,
        checkedAt: file.checked_at,
        ...JSON.parse(file.metadata_json || "{}"),
      })),
    });
  }
  const deletePlaylist = url.pathname.match(/^\/api\/playlists\/([A-Za-z0-9-]+)$/);
  if (request.method === "DELETE" && deletePlaylist) {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM playlist_tracks WHERE user_id = ? AND playlist_id = ?")
        .bind(identity.userId, deletePlaylist[1]),
      env.DB.prepare("DELETE FROM playlists WHERE user_id = ? AND id = ?")
        .bind(identity.userId, deletePlaylist[1]),
    ]);
    return new Response(null, { status: 204 });
  }
  return jsonResponse({ error: "Not found." }, 404);
}

export default {
  async fetch(request, env, context) {
    const origin = request.headers.get("Origin");
    if (request.method === "OPTIONS") {
      if (!env.APP_ORIGIN || origin !== env.APP_ORIGIN) {
        return jsonResponse({ error: "The request origin is not allowed." }, 403);
      }
      return new Response(null, {
        status: 204,
        headers: {
          "access-control-allow-origin": env.APP_ORIGIN,
          "access-control-allow-credentials": "true",
          "access-control-allow-methods": "GET, POST, PUT, DELETE, OPTIONS",
          "access-control-allow-headers": "Authorization, Content-Type, Cf-Access-Jwt-Assertion",
          "access-control-max-age": "600",
          vary: "Origin",
        },
      });
    }
    try {
      const response = await handleRequest(request, env);
      if (!origin || origin !== env.APP_ORIGIN) return response;
      const headers = new Headers(response.headers);
      headers.set("access-control-allow-origin", env.APP_ORIGIN);
      headers.set("access-control-allow-credentials", "true");
      headers.set("vary", "Origin");
      return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers,
      });
    } catch (error) {
      console.error("ASC Music API request failed:", error instanceof Error ? error.message : "Unknown error");
      return jsonResponse({ error: "The request could not be completed." }, 500);
    }
  },
};
