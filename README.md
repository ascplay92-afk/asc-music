# ASC Music

ASC Music is a personal music library and playlist-availability workspace. It compares playlists with metadata indexed from files the user already owns; it does not download protected music or stream from Spotify.

> **Current status: not ready for installation or release.** The actual Supabase project URL and public publishable key are configured in ignored local build files, and its public JWKS advertises ES256. No authorized user login, Cloudflare Worker/D1 deployment, production signing key, or real cross-client sync validation has been performed. Android Drive/Spotify are wired locally but require provider configuration/live validation, and portions of cross-device sync remain incomplete; see [PROJECT_STATE.md](./PROJECT_STATE.md).

## What works

- Responsive Web app with browser-local library/playlist storage, CSV and text playlist import, Spotify playlist import using official OAuth PKCE, search, favorites, playlist editing, and an HTML5 player.
- Google Drive folder selection and recursive, read-only audio indexing. Unchanged Drive revisions skip audio parsing; removed files leave the index on reverification. Audio contents are never stored in D1.
- Music-tag extraction with `music-metadata`, metadata/filename fallback, artwork where available, and incremental index helpers.
- ASC Music Match scores title, artists/features, album, duration, identifiers, and version markers. High-confidence matches are automatic; variants and incomplete metadata require confirmation. Duplicate files remain visible.
- Completeness, a missing-tracks view, authorized search links, “mark acquired and verify”, and full playlist visibility even when tracks are missing.
- Cloudflare Worker + D1 REST API. Supabase bearer JWTs are validated against the project's asymmetric JWKS and private data is scoped to the verified subject; legacy Access JWT validation remains supported.
- Discovery adapters for Last.fm, ListenBrainz, and MusicBrainz. Each response names its source and update time. Missing provider configuration/failures are explicit; recent release dates are not described as Spotify catalog-addition dates.
- Native Android app using Kotlin, Jetpack Compose, Media3, SAF tree selection with persisted access and recursive rescans, incremental metadata extraction, SQLite persistence/migration, playlist text/CSV import, library search, and background MediaSession controls. Lost folder grants are shown with a reauthorization action; incomplete provider scans preserve prior index rows.
- Web playback history is retained locally and new playback events are queued for sync when signed in. It has not been validated as shared with Android.
- Supabase Auth is the selected Web/Android identity, and the Worker derives account isolation from the verified JWT subject.
- Web and Android partially consume `/api/sync`: Web queues playlists, favorites, and playback history; Android queues playlists/favorites and stores other remote record types without applying them to application features. This has not been tested end-to-end or against live services.

## Requirements

- Node.js 22.5+ and npm (the Worker sync tests use the built-in SQLite module).
- JDK 17+ and Android SDK Platform 36 for Android builds. The Gradle 8.13 wrapper is included.
- Cloudflare account with Workers and D1 for hosted account sync; Access is optional and separate from user identity.
- Supabase project with email auth and asymmetric JWT signing; this workspace contains local ignored URL/publishable-key config for Web and Android. See [Deployment](./DEPLOYMENT.md) for dashboard setup and callback URLs.
- Optional Google Cloud OAuth client and restricted API key, Spotify Developer client ID, and Last.fm API key.

## Run and test the Web app

```powershell
npm.cmd install
npm.cmd run dev
npm.cmd test
npm.cmd run build
```

Open the Vite URL printed in the terminal. `.env.local` supplies the public Supabase configuration for this workspace; use `.env.example` as the names-only template. Cloud login still needs Supabase dashboard URL/email settings, while Worker sync is unavailable until Cloudflare is configured and deployed.

`music-metadata` parses changed audio blobs in browser memory. Only the resulting metadata is persisted in IndexedDB/D1; local file bytes are not copied into a database.

## Android

```powershell
.\android\gradlew.bat -p android :app:testDebugUnitTest
.\android\gradlew.bat -p android :app:connectedDebugAndroidTest
.\android\gradlew.bat -p android :app:assembleRelease
```

Release builds use R8/resource shrinking and require the signing variables documented in [Deployment](./DEPLOYMENT.md) before an APK can be distributed. Never use a debug or unsigned release APK as the user-facing delivery. Android reads public Supabase and optional provider Client IDs from ignored `android/supabase.properties`; cloud identity/provider callbacks need dashboard/provider redirect settings and real account validation. Worker sync, physical-device behavior, and live Drive/Spotify provider validation remain incomplete.

## Cloudflare API and D1

1. Create a D1 database named `asc-music` with `npx wrangler d1 create asc-music`.
2. Replace `database_id` in `backend/wrangler.toml` with the returned ID. Set `APP_ORIGIN` and `SUPABASE_URL`; Cloudflare Access settings apply only if legacy Access-protected routes are retained.
3. Configure the Worker `SUPABASE_URL`, Pages build variables, and Android public Supabase/API configuration as documented in [Deployment](./DEPLOYMENT.md). Do not require an interactive Cloudflare Access session on consumer `/api/*` requests.
4. Apply the migration and deploy:

   ```powershell
   npm.cmd run db:migrate:remote
   npm.cmd run api:deploy
   ```

5. Add optional provider settings as Worker secrets/variables. Never put private credentials in Web/Android source:

   ```powershell
   npx wrangler secret put LASTFM_API_KEY --config backend/wrangler.toml
   ```

6. Route the Worker to the same `/api/*` origin as the Cloudflare Pages app, or set `VITE_API_BASE_URL` at Web build time and set the exact `APP_ORIGIN` on the Worker. Same-origin routing avoids cross-site cookie issues.

The checked-in D1 ID, team domain, audience, and app origin are placeholders. Deployment is not complete until they are replaced.

## Optional integrations

- **Google Drive:** enable Drive API and Picker API; create a browser OAuth client; allow the exact Web origin; restrict the API key by origin/API; enter both values in Web Settings. The Web client uses read-only Drive access and keeps the OAuth token in memory. Selecting a folder recursively scans audio files; updated file contents are read transiently to extract tags.
- **Spotify:** create an app and enter its Client ID in Web Settings; allow the exact Vite/deployed origin as the Redirect URI. Authorization uses PKCE and playlist-read scopes; no client secret or scraping is used. Import by CSV/text if Spotify does not authorize or serve the playlist.
- **Last.fm:** add `LASTFM_API_KEY` as a Worker secret for country/tag charts and similar-artist discovery. Without it, these sections return an explicitly empty result.
- **MusicBrainz:** set `MUSICBRAINZ_CONTACT_URL` to a real HTTPS project or maintainer contact before querying. The recent-releases section uses official release dates from the preceding week and never claims Spotify catalog-addition history.
- **ListenBrainz:** the public site-wide weekly recording statistics endpoint is used as an explicitly labeled fallback for global trends/discovery.

## Further reading

- [Architecture](./ARCHITECTURE.md)
- [Deployment](./DEPLOYMENT.md)
- [Testing](./TESTING.md)
- [Security](./SECURITY.md)
- [Roadmap](./ROADMAP.md)
- [Project state and known gaps](./PROJECT_STATE.md)
