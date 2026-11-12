# ASC Music identity and synchronization architecture

## Decision

Use **Supabase Auth** as the end-user identity provider shared by Web and Android. The Web and Android clients authenticate with the same Supabase project and send its short-lived access JWT to the ASC Worker as `Authorization: Bearer`. The Worker validates the signature against the project's public JWKS, issuer, audience, role, and time claims, then derives the D1 tenant key from the verified `sub`. A client-supplied `user_id` is never used for authorization.

Use asymmetric JWT signing keys (ES256 or RS256) in Supabase. The Worker must not accept legacy HS256 tokens because validating them would require placing the Supabase JWT secret in the Worker and creates an unnecessary shared-secret blast radius. Supabase's publishable/anon API key is a public client identifier, not a server credential; it may be configured in Web build settings and Android build configuration. The Supabase service-role key and JWT signing secret must never be exposed to either client or the Worker.

## Identity audit before this implementation

- At the start of this work, the Web app had no ASC account, registration, login, or session manager. Playlists, favorites, history, and preferences were browser-local; existing API calls used Cloudflare Access.
- At the start of this work, Android had no user account or auth session. Playlists, favorites, library metadata, and history were device-local SQLite. Spotify PKCE and Google Drive OAuth authorize those providers, not an ASC account.
- The Worker currently verifies Cloudflare Access RS256 assertions and uses their `sub` for D1 tenancy. That is edge/workspace identity, not a portable consumer sign-in.
- `/api/sync` stores account-scoped, versioned data using the authenticated subject. Its local contract tests do not establish or acquire a user identity.

## Provider comparison

| Option | Shared Web/Android identity | Fits current Worker | Decision |
|---|---|---|---|
| Cloudflare Access | No suitable consumer sign-up/password reset/mobile session lifecycle; intended here as an edge/workspace gate | Current Worker already verifies its JWT, but mobile would need an unsuitable Access login | Keep separate from end-user identity; do not make it the mobile account system |
| Google or Spotify OAuth | Provider identity and scopes only; does not provide an ASC account with a common lifecycle | Would require a separate token validation and account-mapping system | Keep limited to Drive/Spotify features |
| Supabase Auth | Shared email/password, recovery, and supported OAuth sessions for Web and native clients | Public JWKS and JWT claims can be verified at the Worker without a server secret | **Selected** |

The owner has created the Supabase project `bodlzdmtoaezftooewqi`. The HTTPS URL and publishable client key are configured locally for Web/Android; the Worker config contains the public project URL. A read-only check on 2026-11-11 confirmed its public JWKS endpoint serves an ES256 P-256 signing key, and public Auth settings report email signups enabled. The owner subsequently confirmed that the dashboard contains Site URL `http://localhost:5173/`, Web redirect URL `http://localhost:5173/`, Android redirects `ascmusic://auth/confirm` and `ascmusic://auth/recovery`, and the Email provider enabled. This records configuration only; it does not validate user login, signup, email confirmation, password recovery, email delivery, Worker deployment, D1, or any secret key.

### Dashboard configuration status

The following Supabase Auth settings are confirmed as manually saved in the dashboard:

- Site URL: `http://localhost:5173/`
- Web redirect URL: `http://localhost:5173/`
- Android confirmation redirect: `ascmusic://auth/confirm`
- Android recovery redirect: `ascmusic://auth/recovery`
- Email provider: enabled

These settings must remain classified as configuration evidence, not functional validation. A real test account and controlled tests are still required for signup, login, confirmation, recovery, refresh, password update, and Worker bearer-token acceptance.

## Token and session rules

1. Supabase access tokens are short-lived bearer credentials. Clients refresh through Supabase Auth's refresh-token endpoint over HTTPS; the Worker never issues or refreshes them.
2. Web session persistence must be limited to the public Supabase client SDK's supported session mechanism. The refresh token is sensitive; mitigate XSS with strict CSP, escaped HTML, dependency review, and no token logging. Do not copy tokens to `localStorage` manually.
3. Android stores refresh credentials only in an Android Keystore-backed encrypted store; never in SQLite, ordinary preferences, logs, backups, or source/build files. Clear the stored session on logout and invalid refresh.
4. The Worker accepts only signed access JWTs with the configured Supabase issuer and `authenticated` audience/role. It derives tenant identity from `sub`; it does not trust body/query user IDs.
5. Cloudflare Access may remain an infrastructure gate for operator/admin surfaces. Do not require an interactive Access session on the end-user `/api/*` route used by native clients. Network edge policy and end-user identity are distinct controls.

## Tenant and legacy-data migration

New synchronized records use a namespaced tenant key derived from the verified Supabase subject, distinct from legacy Cloudflare Access subjects. Never merge accounts by matching email: email can be mutable, unverified, or shared.

The existing Access-scoped Web records cannot be safely assigned to a Supabase identity automatically. If live legacy records exist, migration requires a one-time linking operation that proves control of both the old Access identity and the new Supabase identity, and transactionally moves all account-scoped rows. No such linking endpoint or live migration has been performed. Until then, legacy Access data remains separate and users may see an empty Supabase sync account.

## Sync contract and client responsibilities

`/api/sync` is the canonical shared data API. It supports playlist, favorite, history, library-metadata, and allowlisted setting records; per-entity base versions; operation IDs with request fingerprints; explicit tombstones; and paginated event cursors.

Each client must have a durable outbox and cursor, apply local writes before attempting network work, retain outbox entries until acknowledged, retry with the exact same operation ID and payload, apply remote deltas transactionally, and preserve/resolve conflict records instead of discarding offline edits. Transactional apply means that the local playlist/favorite/history/library/settings mutation, remote-entity cache update, entity version, and cursor advance are committed as one recoverable unit, or the cursor is deliberately advanced only after an idempotent replay-safe apply. A transient provider scan or unavailable file is not a deletion. Device-specific document URIs and audio bytes must not be uploaded. Local file favorites require a stable privacy-preserving identifier before cross-device mapping.

The Web and Android clients now have Supabase sign-in/session handling and account-scoped durable outboxes/cursors. Web local playlists, favorites, history, playback queue, playback preferences, and Drive folder selection use account keys when a session exists; Android local personal tables use the authenticated owner ID. The physical music library remains device-shared by design. Both clients preserve pending operations and reject owner changes during synchronization. Android applies remote domain data, remote entities, versions, and cursor in one SQLite transaction; Web applies idempotent local changes before committing versions/cursor and deliberately replays the page when interrupted. Both clients expose a conflict choice: accept the remote record or rebase the local operation on the remote version. These are still partial synchronization implementations, not completed end-to-end synchronization.

Preserved anonymous Web data is never assigned automatically to a new account. The Web settings screen provides an explicit import action that remaps colliding playlist IDs, copies eligible personal records to the authenticated account, leaves the original local keys untouched, and uses the account outbox for remote transfer. Android exposes the same explicit import policy for its legacy SQLite/SharedPreferences personal data; because the old history schema has no reliable timestamp, imported legacy history is stamped at import time rather than assigned a fabricated historical time.

Pre-existing browser/device data is not automatically uploaded when a user signs in. This avoids silently assigning or sharing data that was created before an account was selected. Account linking, legacy Access-data migration, and explicit local-data import remain unsupported.

## Required external setup

- Supabase project with Auth enabled and asymmetric ES256 signing. The project already publishes an ES256 key. The owner confirmed the localhost Site URL, Web redirect URL, Android confirmation/recovery redirects, and Email provider are configured in the dashboard. Email delivery and all authentication flows still require functional testing.
- The public key is configured locally as `VITE_SUPABASE_PUBLISHABLE_KEY` for Web and `supabase.publishableKey` in ignored `android/supabase.properties`. Compatibility aliases for the old anon-key variable names remain. Never use a service-role key in either client.
- Worker `SUPABASE_URL` is set in `backend/wrangler.toml` to the same project so it can load the public JWKS. This file change is not a Cloudflare deployment.
- For the current local environment, the dashboard allowlist contains `http://localhost:5173/`, `ascmusic://auth/confirm`, and `ascmusic://auth/recovery`. Before production, replace or supplement the localhost Web origin with the exact HTTPS Web origin. The Android custom-scheme callback is strictly path/type checked; use verified HTTPS Android App Links instead when a final app domain and signing identity are available.
- Cloudflare Access edge policy adjusted so it does not block end-user Supabase bearer requests to `/api/*`; Worker JWT validation remains mandatory.
- Existing Access-tenant data, if any, requires the dual-proof migration described above.
- Native provider configuration is separate from Supabase identity: Android reads `GOOGLE_DRIVE_CLIENT_ID`/`google.drive.clientId` and `SPOTIFY_CLIENT_ID`/`spotify.clientId` from ignored local configuration. The corresponding provider consoles must register `ascmusic://drive/auth` and `ascmusic://spotify/auth` for the installed-app PKCE clients. No client ID was invented or committed; provider callback acceptance and account access remain unvalidated.

The ASC Music application stores synchronized data in Cloudflare D1, not Supabase Postgres. No application tables or RLS policies are required in Supabase for this architecture, and no Supabase SQL migration was added. Supabase Auth's internal schema is provider-managed. The publishable REST endpoint did not expose a schema listing; manually inspect any owner-created tables in the Supabase dashboard and enable RLS with per-user policies before exposing them. No login, password recovery, email confirmation, Cloudflare D1 deployment, Android device, or Web↔Android data round-trip has been tested with a user account.
