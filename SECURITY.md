# Security

## Implemented safeguards

- Cloudflare Access JWT verification checks RS256 signature/JWKS, issuer, configured audience, expiry/not-before, and a non-empty subject. D1 calls bind all private rows/deletes to that subject.
- Supabase user JWT verification accepts only ES256/RS256 keys from the configured project's public JWKS, checks issuer, authenticated audience/role, expiry/not-before, and derives the D1 identity from verified `sub`. Supabase tenants use a `supabase:` namespace separate from legacy Access subjects.
- Android refresh/access tokens are encrypted with AES-GCM using a non-exportable Android Keystore key; Android auth and Worker requests require HTTPS. The Supabase anon/publishable key is public; no service-role key or signing secret belongs in clients.
- Native API clients may send the same Access JWT in `Authorization: Bearer`; the Worker applies the identical verification. A write with no browser `Origin` is accepted only when a Bearer header is present. This does not provide Android login, token acquisition, or refresh.
- Worker mutation requests require an exact configured `APP_ORIGIN`; request bodies are limited to 1 MiB and playlist/library fields have bounds.
- Current native behavior supersedes the older Access-only note above: Android authenticates through Supabase Auth and sends its Supabase access JWT; the Worker validates it using the configured project's asymmetric JWKS. Cloudflare Access remains a separate legacy/edge identity path.
- `/api/sync` scopes entities, operations, and events by the verified subject; validates allowlisted entity payloads; uses optimistic versions and idempotent operation IDs; and retains explicit tombstones in a paginated event log. It stores metadata only, not media bytes. Clients must use opaque non-path IDs for local files and must not send SAF content URIs or credentials.
- A stale `baseVersion` is rejected with the current server record; clients retain pending changes and surface conflicts. Neither client yet provides a merge/resolve workflow.
- One D1-backed rolling per-user bucket limits authenticated API requests to 120 per minute.
- Spotify uses Authorization Code + PKCE and public Client ID only; Web tokens remain in memory and Android tokens are encrypted with a provider-specific Android Keystore key. OAuth state is checked, and Android refresh/disconnect paths clear invalid credentials.
- Google Drive uses an explicit read-only OAuth scope; Web tokens remain in memory and Android tokens are encrypted with a provider-specific Android Keystore key. Android revokes the stored Google access token when disconnecting where the provider endpoint supports it. API keys/client IDs are public identifiers, not credentials, and must be restricted by origin/API or registered as installed-app clients.
- No audio payload is accepted by D1. Metadata parser output is size-limited for embedded artwork.
- User/provider text is HTML-escaped. External links are restricted to HTTP(S), opened with `noopener noreferrer`.
- Worker secrets are not logged. Errors returned to clients are generic for unexpected failures; provider/user-visible configuration errors are specific.
- Production dependency audit (`npm audit --omit=dev`) is expected to pass before release.
- Android backups are disabled; the local index contains metadata and document URIs, not credentials or copied songs.
- Android 12+ cloud-backup and device-transfer rules, plus legacy backup rules, exclude app data. SAF rescans retain prior index rows if a provider read/cursor failure makes the scan incomplete; missing persisted read grants are surfaced for reauthorization.
- Android release builds enable R8/resource shrinking and accept signing material only through four process environment variables; a partially configured signing identity fails the Gradle configuration. The production keystore is not present in the workspace.
- `.jks` and `.keystore` files are excluded by `.gitignore`; signing credentials should still be held in an external secret store rather than the project.
- Android requests read-only Storage Access Framework access; no broad media/storage permission is declared. Android cleartext traffic is disabled.

## Review status

- A read-only review found a medium-confidence cross-account local-state isolation risk. Web account data now uses account-specific keys, and Android personal tables, remote records, outboxes, and cursors are keyed by the authenticated owner ID. The physical music library remains intentionally shared on each device. Web partitioning has local regression coverage; an Android owner-isolation/tombstone instrumentation test compiles but has not yet run on a device. This is not a penetration test and does not validate live identity/deployment configuration.
- `npm audit --omit=dev` reported zero production dependency vulnerabilities on 2026-11-11.

## Deployment requirements

1. Set the actual Cloudflare `APP_ORIGIN`, D1 database ID, route, and bindings. `SUPABASE_URL` now points to the configured project in `backend/wrangler.toml`, but this config has not been deployed. Keep Access edge restrictions separate from end-user Supabase bearer authentication; do not block mobile/Web API requests at the edge.
2. Set `APP_ORIGIN` to the exact HTTPS Web origin. Do not use wildcard origins.
3. Set provider API keys using `wrangler secret put`; never place them in `VITE_*`, JavaScript, APK, or Git.
4. Limit the Google API key to the deployed referrers and Picker/Drive APIs. Use a dedicated OAuth client with only required redirect origins.
5. Configure a Cloudflare WAF/edge rate-limiting rule for unauthenticated public discovery and health endpoints. The Worker D1 bucket applies after verified end-user identity.
6. Use HTTPS for the Web app, Worker API, and `MUSICBRAINZ_CONTACT_URL`.
7. Protect account cookies, store Android signing keys outside the repository, and review provider terms/quotas before enabling public access.

## Known gaps

- Android account sessions are encrypted with an Android Keystore AES-GCM key. Personal playlists, favorites, history, queue, preferences, sync outbox, and sync cursor are isolated by the verified account ID; the physical library index remains shared by policy.
- Web and Android call `/api/sync` for partial workflows. Both clients now expose local-rebase/remote-accept conflict actions, but no end-to-end isolation/replay test against live Supabase and D1 exists.
- Android now connects natively to Google Drive and Spotify through configurable PKCE clients, account-scoped Drive folder settings, metadata-only library indexing, and Media3 range streaming. Provider configuration and end-to-end account testing remain incomplete.
- Last.fm/ListenBrainz/MusicBrainz endpoints are not mocked in deployed builds; availability depends on the provider.
- Supabase is an external end-user identity dependency; Cloudflare Access is kept separate as an optional edge/workforce control. Client-to-Worker calls require the correct deployment edge policy and Worker JWT verification.
- Supabase's public JWKS currently exposes ES256. Android signup/recovery callbacks accept only the ASC Music scheme/host and matching callback path/type, then retrieve the user profile over HTTPS before storing a session. The custom scheme may be claimed by another installed app; verified HTTPS App Links remain a production hardening task.
- Cross-origin Access cookie behavior depends on the deployment domain; same-origin `/api/*` routing is recommended.
- No external penetration test, OAuth account test, or signed-release verification has been performed.
- A production signing key has not been supplied; the release signing mechanism is only prepared. Do not distribute unsigned APKs or use a temporary/debug keystore for production.
