# Roadmap

## Delivered and locally verified

- [x] Monorepo foundation: Web client, Android app, shared matching/import logic, Worker, D1 schema, and automated tests.
- [x] Responsive Web library/player, playlist workflows, CSV/text/manual imports, source-labelled discovery, missing-track center, Spotify PKCE/API path, and Google Drive picker/indexing adapter.
- [x] Native Compose/Media3 local-library and playlist experience; SQLite metadata index with one-time migration from the old JSON preference store.
- [x] Android SAF folder/file indexing, startup/manual incremental folder reconciliation, metadata/artwork fallbacks, duplicate identification, index-only removal, and SQLite v4 persistence for local library/player data plus account-scoped sync queues and caches.
- [x] Android Media3 background playback service and wired player controls/queue UI; debug APK and SQLite instrumentation-test APK compile successfully.
- [x] Added Android regression tests for queue restoration, repeat modes, duplicate recordings, SAF audio filtering, folder deltas, SQLite persistence, migration, and index-only removal.
- [x] Match safety for live venues, remix subtypes, remaster years, featured artists, missing tags, and duplicate files.
- [x] Bounded CSV and Worker JSON inputs, D1 playlist batch writes, reduced query count, incremental Web sync batches, and generated CSP for a configured API origin.
- [x] Round-two regression fixes for Android playlist matching/player interactions and re-verification permission errors, incomplete Spotify import signaling, and local Web library reconciliation.
- [x] Mobile navigation, spacing, and availability reporting refinements; responsive Web checks across phone, tablet, and desktop widths; additional empty/partial match-summary regression tests.
- [x] Validation: Node/Web tests and build, Android JVM tests and debug APK build, local D1 migration check, Wrangler dry-run, production dependency audit, CSP origin checks, and a temporary Web manual-playlist smoke test.
- [x] Android release configuration: API 36/target 36, AGP 8.13.2, Gradle 8.13, R8/resource shrinking, configurable app version, and environment-only signing; Release Kotlin compilation and lint pass. A signed production package remains blocked on the owner's key.
- [x] Emulator verification: 4 isolated SQLite instrumentation tests, 2 Media3 WAV playback/error tests, and 1 SAF folder-picker contract test passed on API 37; cold activity launch stayed alive. Test databases no longer collide with the app service's persistent SQLite database.
- [x] SAF failure handling now preserves previous index rows on incomplete scans and surfaces lost persisted read grants with a reauthorization action. Added JVM coverage for partial-scan reconciliation, grant status, and failed permission persistence, plus an instrumentation test for the tree-picker contract.
- [x] Worker private routes accept verified Cloudflare Access JWTs as Bearer tokens for native clients while retaining strict JWT checks and Web-origin validation. Android does not yet acquire or use these tokens.
- [x] Android backup/data-transfer rules explicitly exclude local metadata and preferences; Media3 trust API lint opt-in is declared.
- [x] Selected Supabase Auth as shared Web/Android identity; Worker verifies asymmetric Supabase JWTs from JWKS and isolates D1 tenants by verified subject.
- [x] Added partial Web and Android `/api/sync` clients with durable account-scoped outboxes, cursors, entity versions, replay-safe operation IDs, and explicit conflicts. Web applies playlists/favorites/history; Android applies playlists and mapped favorites and retains other entity types as account-scoped remote records.
- [x] Completed the local Web account-isolation wiring for playlists, favorites, history, playback queue, playback settings, and Drive folder selection; Web sync now rejects account changes during a run and replays remote pages safely after an interrupted local apply. Web history defaults to real millisecond timestamps and queue persistence rebinds entries to the shared physical library.
- [x] Corrected Android Worker payload adapters for account history/settings and queued playback history events with millisecond timestamps and metadata snapshots; added a regression test for the remote payload contract.
- [x] Connected Android playback preference changes and physical-library metadata changes to the account outbox without synchronizing audio bytes or SAF URIs; added local-vs-remote conflict resolution actions on Android and Web.
- [x] Added an explicit non-destructive Web import path for preserved anonymous legacy playlists, favorites, and history; automatic legacy assignment remains disabled.
- [x] Configured the real Supabase project URL and public publishable key in ignored local Web/Android configuration; wired Worker issuer/JWKS config and implemented Android signup/recovery callbacks and password update. The public JWKS reports ES256; no user login or confirmation/recovery flow has been tested.
- [x] Added tests for Android auth callback parsing and Worker JWKS refresh after an unknown signing-key ID.
- [ ] Complete Web/Android end-to-end sync, account-local migration UX, atomic remote apply, and a tested conflict-resolution workflow. No real Supabase/Cloudflare service or cross-client round-trip has been validated.

## Implementação Android preparada para validação

- [x] Fase 1.1: consumidores Android migrados para dados account-scoped; sessões sem proprietário não leem nem gravam dados pessoais.
- [x] Serviço Media3 integrado à identidade autenticada para histórico, fila e preferências, com recarga explícita na troca de conta.
- [x] Worker Android protegido contra troca de sessão durante a operação e aplicação remota transacional com cursor e versões.
- [x] Histórico com timestamps ordenáveis e snapshots de metadados para faixas que deixaram de estar indexadas.
- [ ] Executar a suíte Android autorizada e validar troca de contas, logout/login, sincronização e recuperação após interrupção.

## External configuration recorded but not functionally validated

- [x] The owner configured Supabase project `bodlzdmtoaezftooewqi` in the dashboard with Site URL `http://localhost:5173/`, Web redirect URL `http://localhost:5173/`, Android redirects `ascmusic://auth/confirm` and `ascmusic://auth/recovery`, and the Email provider enabled.
- [ ] Authentication remains unvalidated: do not treat the dashboard settings as proof of signup, login, email confirmation, recovery, refresh, or password update behavior.

## Implemented but not yet validated

- [x] Phase 1 Android SQLite isolation implementation: database version 5 creates account-scoped playlists, playlist tracks, favorites, playback history, playback queue, and playback preferences while preserving the shared physical library and legacy v1–v4 personal tables without automatic account assignment.
- [ ] Phase 1 Android migration and account-isolation tests have not been executed; the remaining validation covers account switching, playback-service restart and real Worker/Supabase round trips.

## Remaining work before full product criteria are met

1. **Android device validation (release gate):** on a real device, test SAF folder permission persistence and rescanning with actual music, Media3 service playback and format compatibility, notification/lock-screen controls, Android 13+ notification behavior, audio interruptions, and restart/recovery. The emulator's UIAutomator session was blocked by a system-UI ANR; only generated-WAV engine playback, not physical-device playback, has been validated.
2. **Identity and sync completion (partial):** review/configure production email delivery and any final environment-specific redirects, configure/deploy Cloudflare Worker/D1, test real signup/login/confirmation/recovery, validate Android account-scoped database and playback-service behavior; execute Web IndexedDB and Android SQLite migration/outbox tests; validate conflict actions and legacy import semantics; and prove Android → Worker → Web → Worker → Android flows against real services and account isolation.
3. **Google Drive:** validate Web Picker and Android OAuth/folder listing/playback with real accounts/scopes. Web Changes API checkpoints and safe structural fallback are implemented; Android now has configurable PKCE, encrypted tokens, paginated folder traversal, cancellation, metadata indexing, and Media3 range streaming. Remaining work is provider validation, richer Web progress UI, and large-file seeking validation.
4. **Spotify:** validate registered-app OAuth/API entitlement, authorized playlist listing, and actual Web/Android playlist import. Both clients now have official PKCE, encrypted Android token storage, pagination, disconnect, and access/rate-limit handling. Use CSV/text fallback when an official endpoint is unavailable.
5. **Discovery:** validate each configured source and regional availability using live providers; keep explicit source, retrieval time, and unavailable states.
6. **Automated integration coverage:** add Web IndexedDB/outbox tests and Android sync DB migration/session tests, including interrupted remote-apply recovery, account-switch isolation, and cursor/version atomicity; add a fake DocumentsProvider traversal/persisted-grant test, browser E2E/accessibility tests, Android service/device playback tests, and OAuth/Drive/Spotify tests using authorized test accounts. Validate SAF, audio, notification, and interruption behavior on physical hardware without asking users to install development APKs.
7. **Reliability/product:** improve large-file Drive seeking, add playlist collaboration/sharing and backups, and verify acquisition links/statuses for supported regions.
8. **Release readiness:** obtain the production signing keystore and retain an encrypted backup; configure Cloudflare resources/secrets/domains/Access; configure OAuth provider origins/redirects; establish CI/CD/version updates; and commission an independent security/privacy review.

Local test/build success does not satisfy the external-service or physical-device validation gates above. Credentials, provider accounts, production Cloudflare configuration, and signing keys require owner setup. Do not distribute the unsigned `app-release-unsigned.apk`.
