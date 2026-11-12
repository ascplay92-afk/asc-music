# Architecture

## Monorepo boundaries

```text
web/                 Vite, semantic HTML, CSS, browser app/integrations
shared/              pure matching, playlist import, incremental-index helpers
backend/src/         Cloudflare Worker REST API, Access JWT verification, provider adapters
backend/migrations/  D1 schema, metadata only
android/             Kotlin + Compose + Media3 + Android document picker
test/                shared and Web-domain tests
backend/test/         Worker and external-provider behavior tests
```

The Web app uses IndexedDB for a per-browser metadata index and local storage for playlists/preferences. Browser audio bytes are read transiently for tag extraction or playback; the index stores the file reference, not a second audio copy. Drive IDs, revisions, metadata, size, format, and last-check timestamps are retained.

The Android app independently indexes Storage Access Framework URI references and metadata in SQLite; its audio remains in the selected document provider. It runs Media3 in a `MediaSessionService`. Supabase Auth sessions are encrypted with an Android Keystore AES-GCM key. A SQLite outbox, entity-version table, and per-account cursor support partial Worker synchronization. Android Drive OAuth and Spotify PKCE integrations are still absent.

## Match pipeline

1. Import an authorized Spotify playlist, CSV, or manual text list.
2. Keep playlist metadata, IDs/ISRC, duration, artists/features, and original version descriptors when supplied.
3. Index a local/Drive file and parse supported metadata; retain an explicit tag-read error and filename-derived title if tags are absent.
4. Normalize case, Unicode diacritics, whitespace, and punctuation for comparison, while extracting recording-version signals separately.
5. Score title, artists, album, duration, and ISRC. Auto-accept only a unique high-confidence identity; exact duplicates are identified. Version mismatch or incomplete identity stays `POSSIBLE_MATCH`.
6. Compare against the current index each time the playlist is shown. Reverification replaces changed revisions and removes files missing from the selected Drive folder.

`AVAILABLE` and `DUPLICATE` count as playable for completeness. `POSSIBLE_MATCH`, `MISSING`, and `UNAVAILABLE_SOURCE` do not. Manual confirmation is kept per playlist track and library file.

## Discovery

Worker sections choose a provider based on the requested section:

- Last.fm API: Brazilian country chart, genres/tags, artist highlights, and similar-artist seeds.
- ListenBrainz public weekly site-wide recording statistics: global/discovery fallback.
- MusicBrainz official release-date metadata: recent releases, only when a real contact URL is configured.

Every section response contains source, update timestamp, and provider caveats. Missing keys and unavailable providers do not trigger fake chart rows. Spotify is used only via its official PKCE/Web API surfaces for playlists.

## API and account boundary

Supabase Auth provides end-user email/password identity shared between Web and Android. The Worker independently verifies Supabase ES256/RS256 JWTs against the configured public JWKS, issuer, authenticated audience/role, expiry, and subject; the verified `sub` is namespaced for D1 tenancy. Cloudflare Access remains a separate edge/workforce control and must not block consumer bearer requests. Legacy Access assertions remain supported on existing routes. Mutations validate Web origin where applicable, request bodies are size/schema limited, and authenticated API calls have a D1-backed per-user rate bucket.

Public provider/health endpoints are exposed by Worker code, but an Access application covering the entire `/api/*` path will require a signed-in user at the edge for those paths too.

## Persistence

D1 contains `library_files`, `playlists`, `playlist_tracks`, `api_rate_limits`, and versioned sync entities, operation fingerprints, and append-only events. No audio blob is accepted by the API. All per-user queries/updates include the identity derived from a verified token.

The Web outbox/cursor is in IndexedDB and is partitioned by Supabase user. Android outbox/cursor/entity versions are in SQLite and partitioned by Supabase user. Web applies playlists/favorites/history; Android applies playlists and locally matched favorites, while preserving other incoming metadata records without wiring them to app features. There is no completed conflict-resolution UX, automatic migration of pre-account local data, or cross-client integration validation.

## Playback

- Web: HTML audio element, local object URLs, or a transient Drive media response for a selected file.
- Android: Media3 ExoPlayer and MediaSession notification/lock-screen controls.
- Spotify never supplies playable audio. Playlist missing states remain visible even when the playable queue contains only available tracks.
