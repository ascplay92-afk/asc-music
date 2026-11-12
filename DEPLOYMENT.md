# Deployment

## Web

Build from the repository root with `npm.cmd ci` and `npm.cmd run build`. Configure Cloudflare Pages with:

- Build command: `npm run build`
- Output directory: `dist`
- Root directory: repository root
- Optional build variable: `VITE_API_BASE_URL` (leave empty for same-origin `/api`)
- Local development reads ignored `.env.local`; use `.env.example` as the names-only template. `VITE_SUPABASE_PUBLISHABLE_KEY` is a public client key; never put the service-role key in a Vite variable.

Use the exact Web origin as the Spotify redirect URI and Google OAuth authorized JavaScript origin. Configure a same-origin Cloudflare Worker route for `/api/*` where possible.

## Worker and D1

1. Run `npx wrangler d1 create asc-music`, copy the created ID into `backend/wrangler.toml`.
2. Set `APP_ORIGIN` to the exact deployed HTTPS Web origin. Configure `ACCESS_TEAM_DOMAIN` and `ACCESS_AUD` only if retaining legacy routes that explicitly use Cloudflare Access; these are identifiers/settings, not client secrets.
3. Keep Cloudflare Access as an optional edge/workforce control, not as the end-user account provider. Do not require an interactive Access session on `/api/*` calls from signed-in Web/Android users; the Worker validates their Supabase bearer JWT. If legacy Access-authenticated routes remain, scope their edge policy separately.
4. Run `npm run db:migrate:remote`, then `npm run api:deploy`.
5. Set optional `LASTFM_API_KEY` using `npx wrangler secret put LASTFM_API_KEY --config backend/wrangler.toml`.
6. Set `MUSICBRAINZ_CONTACT_URL` to a real HTTPS contact URL in Worker environment variables before using recent releases.
7. Configure edge rate limiting for the public API routes and review CORS/origin behavior in the deployed domain.

### Shared identity configuration inventory

The real project URL and public publishable key are configured in this workspace's ignored `.env.local` and `android/supabase.properties`. The Worker URL is set in `backend/wrangler.toml`. These are public client settings; no secret key is used.

- Web build environment: `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`; the older `VITE_SUPABASE_ANON_KEY` remains a compatibility fallback. Optional `VITE_API_BASE_URL` selects a non-same-origin Worker.
- Android build environment: `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, and `ASC_API_BASE_URL`, or Gradle properties `supabaseUrl`, `supabasePublishableKey`, and `ascApiBaseUrl`. Local builds can use ignored `android/supabase.properties` keys `supabase.url`, `supabase.publishableKey`, and `asc.apiBaseUrl`. `ASC_API_BASE_URL` must be the final HTTPS Worker base URL before Android sync works. These become public APK configuration; never put service-role keys or JWT signing secrets here.
- Worker environment: `SUPABASE_URL`, exact `APP_ORIGIN`, and the configured D1 database binding. The Worker fetches public signing keys from the project's JWKS endpoint; no Supabase signing secret or service-role key is needed.
- Optional Cloudflare Access configuration: `ACCESS_TEAM_DOMAIN` and `ACCESS_AUD` only for routes still protected by Access. Do not put an Access requirement in front of signed-in client requests unless the deployment is deliberately configured to support both layers.
- Web-only provider configuration: Spotify Developer Client ID/redirect URI; Google OAuth Client ID, restricted browser API key, enabled Drive/Picker APIs; optional Worker `LASTFM_API_KEY` and HTTPS `MUSICBRAINZ_CONTACT_URL`. Native Android Drive/Spotify OAuth is not implemented.

The Web recovery link returns to the current Web origin and uses the Supabase SDK recovery event. Android signup and recovery links return to `ascmusic://auth/confirm` and `ascmusic://auth/recovery`; the app validates scheme/host/path/type, requests the user profile from Supabase over HTTPS before storing a session, and sends password changes over HTTPS. Custom URI schemes can be claimed by another installed app; use verified HTTPS Android App Links when the final app domain and production signing certificate are available.

### Supabase dashboard actions still required

1. In **Authentication → URL Configuration**, set **Site URL** to the final HTTPS Web origin. Add exact redirect allowlist entries for that origin/root, `http://localhost:5173/` for Vite development if desired, `ascmusic://auth/confirm`, and `ascmusic://auth/recovery`. Do not use wildcard or untrusted redirects.
2. In **Authentication → Providers → Email**, review email/password, signup confirmation, and recovery settings. Public settings currently indicate signups and the email provider are enabled, but no confirmation or recovery email has been sent/tested. Configure production SMTP and a sender domain before relying on delivery.
3. In **Authentication → JWT Signing Keys**, keep asymmetric ES256/RS256 signing. The current public JWKS exposes ES256; Worker JWKS caching is one hour and forces a refresh when a token has an unknown `kid`.
4. ASC Music stores records in Cloudflare D1, not Supabase Postgres. No application SQL/RLS migration is needed. Inspect any tables created independently in the dashboard and enable RLS with per-user policies before exposing them through Supabase Data API. Public API schema enumeration was unavailable with the publishable key.

Check `GET /api/health`, `GET /api/discovery`, signed-in `GET /api/playlists`/`GET /api/library`, and `/api/sync` after configuration. A successful health response alone does not verify D1, identity, or provider connections. Apply all checked-in D1 migrations, including `0002_sync.sql` and `0003_sync_operation_fingerprints.sql`, before deploying.

The Web and Android apps call `/api/sync` for partial playlist/favorite workflows. The Worker has not been deployed to Cloudflare and neither client has completed login using a real account. Android history/library/settings are not integrated into application behavior; Web IndexedDB/outbox behavior and current-version native SQLite migration still need dedicated executed tests. Conflicts preserve local outbox entries but have no merge UI.

## Google Drive

Enable the official Drive and Picker APIs. Create a browser OAuth client, set the precise allowed origin, and restrict the API key to the origin and required APIs. In Web Settings add the Client ID and API key, authorize, and choose a music folder. Permission is read-only. Verify a file add, changed revision, and removal. Large changed media files are read transiently for metadata; they are not stored in D1.

## Spotify

Create a Spotify Developer application, add the exact Web origin as redirect URI, and enter the Client ID in Settings. OAuth is PKCE with playlist-read scopes. Test an authorized playlist on the actual Spotify app/account; API availability/eligibility can vary. CSV and text import are independent fallbacks. Spotify URLs are never scraped.

## Android

Release validation and final installation use the `release` build type (R8 minification and resource shrinking enabled):

```powershell
.\android\gradlew.bat -p android :app:testDebugUnitTest :app:connectedDebugAndroidTest
.\android\gradlew.bat -p android :app:assembleRelease
```

The package ID is `com.ascmusic`; the current version is `0.2.0` (`versionCode` 2). Override versioning with Gradle properties `-PascVersionCode=<integer>` and `-PascVersionName=<semantic-version>`.

The release signing configuration reads all values from the process environment and fails closed if only some are present:

- `ASC_RELEASE_STORE_FILE` — absolute path to the private `.jks`/`.keystore` file.
- `ASC_RELEASE_STORE_PASSWORD`
- `ASC_RELEASE_KEY_ALIAS`
- `ASC_RELEASE_KEY_PASSWORD`

Store the keystore and passwords only in a protected local secret manager or CI secret store; exclude them from source control and backups shared with the repository. Preserve an encrypted offline backup of the production key because Android updates must keep the same signing identity. If the owner has no key, create one once with JDK `keytool` in a protected environment, then retain it securely; do not substitute a debug or temporary test key. With signing variables unset, Gradle can validate an **unsigned** release build, but that artifact is not a distributable final APK. The signed artifact path is `android/app/build/outputs/apk/release/app-release.apk`; verify it with `apksigner verify --verbose --print-certs` and calculate SHA-256 before delivery.

### Safe production keystore creation and signing

Do not create a production key until the owner has chosen a protected storage location and encrypted offline backup procedure. Keep it outside the repository and OneDrive-synchronized project directory. On a secured workstation, use JDK `keytool` and let it prompt for the store/key passwords (do not put passwords in the command line or shell history):

```powershell
New-Item -ItemType Directory -Force "$HOME\ASC-Music-Secrets" | Out-Null
keytool -genkeypair -v -keystore "$HOME\ASC-Music-Secrets\asc-music-release.jks" `
  -alias asc-music-release -keyalg RSA -keysize 4096 -validity 10000
```

Record the alias, identity details, and password-manager item; create and verify an encrypted offline backup before signing. Supply the four `ASC_RELEASE_*` values through a protected local secret manager or CI secret injection, run `:app:assembleRelease`, and verify the resulting signature and SHA-256. Do not paste passwords into chat, commit them, or place the keystore in the project tree. This procedure is documented only; no key was generated.

Android supports Supabase email signup/login/logout, refresh, signup/recovery callbacks, password update, Keystore-encrypted session persistence, and partial playlist/favorite sync. The redirect handling is implemented but has not been tested with a real email or account. Drive/Spotify integrations and history/library/settings sync remain incomplete.

### Shared Supabase identity configuration

1. Supabase project `bodlzdmtoaezftooewqi` exists; its public JWKS is reachable and currently advertises ES256. Confirm this setting remains asymmetric and configure Site URL, redirect allowlist, email confirmation, recovery, and SMTP in the dashboard.
2. The local Web configuration uses `VITE_SUPABASE_URL` plus `VITE_SUPABASE_PUBLISHABLE_KEY` in ignored `.env.local`; Pages production must receive those variables as build settings.
3. Android reads `supabase.url`, `supabase.publishableKey`, and `asc.apiBaseUrl` from ignored `android/supabase.properties`, with Gradle property/environment overrides. `ASC_API_BASE_URL` must be the HTTPS Worker origin, not a route ending in `/api`.
4. The Worker project URL is set in `backend/wrangler.toml`. Configure the real D1 ID, `APP_ORIGIN`, Worker route, and bindings in Cloudflare; deploy only with owner authorization. The Worker does not need a Supabase signing or service-role key.
5. The Supabase publishable key is public. Do not use the service-role key or JWT signing secret in either client. No user login, email confirmation/recovery, Worker deployment, or cross-client sync has been performed.

## Consolidated external setup inventory

| Area | Required owner configuration |
|---|---|
| Google Drive (Web) | Google Cloud project; Drive API and Picker API enabled; browser OAuth Client ID authorized for the exact Web origin; API key restricted to that origin and the required APIs. The Web client uses read-only scope. No Google client secret belongs in the browser or APK. |
| Spotify (Web) | Spotify Developer application and Client ID; exact deployed Web origin registered as Redirect URI; account/API access permitted by Spotify. PKCE is used; no client secret is needed in the Web app. |
| Cloudflare | Pages project/domain; Worker route for `/api/*`; real D1 database ID; migrations applied; exact `APP_ORIGIN`; configure/deploy `SUPABASE_URL` from `backend/wrangler.toml`; edge rate limiting/WAF. Cloudflare Access should not block end-user Supabase bearer calls. |
| Supabase identity | Project URL/publishable key are configured locally; owner must set Site URL, exact Web/Android redirect allowlist, email confirmation/recovery delivery and SMTP. The public JWKS currently shows ES256. Never expose service-role key or signing secret. |
| Worker data sync | Apply migrations `0002_sync.sql` and `0003_sync_operation_fingerprints.sql`; the Worker validates Supabase JWTs from JWKS. Client implementation and live cross-client behavior remain partially unverified. |
| Discovery providers | Optional Last.fm API key stored as a Worker secret; HTTPS `MUSICBRAINZ_CONTACT_URL` and a valid contact identity. ListenBrainz public endpoints require no key. |
| Android signing | Production keystore, alias, store/key passwords, secured backup, and the four environment variables above. No signing key is currently supplied or generated for production. |
| Android native cloud identity/sync | Public Supabase config is in ignored `android/supabase.properties`; set the real HTTPS `asc.apiBaseUrl` before building. Signup/recovery callback support is implemented but needs authorized-email testing, App Links hardening, further sync integration, and real-device/service validation. No service secret should be embedded in the APK. |
| Android Google Drive / Spotify | Not activatable yet in the native app. Existing Google Drive and Spotify authorization/API flows are Web-only; Android-specific clients, redirect registration, API access tests, and streaming/import paths are still required. |
| Release operations | HTTPS Web/API domains, exact OAuth redirect/origin allowlists, live-service verification accounts, privacy/support contact, and distribution/update channel. |

No live Google, Spotify, Cloudflare, or production OAuth integration is considered validated until exercised with the owner’s configured services and authorized accounts.
