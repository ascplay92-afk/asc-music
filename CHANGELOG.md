# Changelog

## Autonomous integration pass — 2026-11-11

- Added account-folder Google Drive Changes API checkpoints with pagination, moved/deleted-file reconciliation, cancellation support, and conservative full-tree fallback for structural changes.
- Connected the existing official Spotify PKCE integration to authorized playlist listing and one-click import from the Web settings flow; no client secret or scraping was introduced.
- Added explicit provider-facing messages for Spotify entitlement/rate limits and Google Drive permission/quota failures.
- Added Android native Google Drive and Spotify PKCE clients with encrypted Keystore token stores, refresh/expiration handling, callback validation, pagination, disconnect/revocation behavior, and configurable external client IDs.
- Connected provider actions to the Android account interface: connect/disconnect, Drive folder listing/indexing, Spotify playlist listing/import, progress, cancellation, and retry-preserving interruption behavior.
- Added a Media3 range-aware Google Drive data source so remote Drive tracks can start playback without a complete upfront download.
- Added cancellation to Android Worker synchronization, preserving the durable outbox and cursor for a later retry.
- Corrected project-state wording so implemented local Android preference outbox behavior and remaining runtime validation are represented accurately.

## Autonomous development pass — 2026-11-11

- Completed Web account-scoped persistence for playlists, favorites, history, playback queue, playback settings, and Drive folder selection without changing the shared physical library store.
- Added Web synchronization owner checks and recoverable remote-page acknowledgement; existing IndexedDB stores are now created idempotently during future upgrades.
- Standardized newly recorded Web playback timestamps on millisecond integers while retaining display compatibility with older ISO history entries.
- Rebound restored Web queue entries to the current shared library index and retained a legacy queue fallback for existing local data.
- Corrected Android remote history/settings payload mapping and queued playback history events with account ownership, millisecond timestamps, and metadata snapshots.
- Connected Android playback preferences and library metadata to the account outbox, preserving the shared physical library and excluding audio bytes/SAF URIs from synchronization.
- Added explicit local-rebase or remote-accept conflict actions to Android and Web synchronization flows.
- Added a non-destructive, user-initiated Web import path for preserved anonymous legacy data, including playlist collision remapping and account-scoped sync enqueueing.
- Added the equivalent explicit Android legacy-data import action with preserved legacy tables, account-scoped copy, and documented import-time timestamps for old history rows without reliable timestamps.
- No builds, tests, APKs, installations, deployments, or external configuration changes were performed in this pass.

## 0.1.0 — 2026-11-11

- Created the ASC Music Web/Worker/D1/Android monorepo foundation.
- Added CSV/text and official Spotify PKCE playlist import.
- Added Google Drive read-only folder indexing, metadata parsing, and revision-aware verification.
- Added shared match scoring, version safeguards, duplicate detection, and completeness/missing views.
- Added Last.fm, ListenBrainz, and MusicBrainz discovery adapters with provenance and explicit unavailability.
- Added Cloudflare Access JWT validation, D1 tenant keys, origin checks, rate limits, and migrations.
- Added Android Compose/Media3 local library/player and local playlist support.
- Added deterministic unit/API tests and architecture/security/deployment documentation.

This is a foundation release; see [PROJECT_STATE.md](./PROJECT_STATE.md) for unverified external integrations and remaining platform work.

## Unreleased — Release hardening and library refinements

- Selected Supabase Auth as the shared Web/Android identity; Worker now validates asymmetric Supabase JWTs and namespaces verified subjects for D1 isolation.
- Added Web signup/login/logout/recovery request, SDK-managed sessions, IndexedDB outbox/cursors, and partial playlist/favorite/history synchronization.
- Added Android account UI, Supabase REST signup/login/logout/refresh/recovery request, Keystore-encrypted session storage, SQLite v4 sync tables, and partial playlist/favorite Worker synchronization.
- Added stable cross-client track IDs and retained unsupported remote Android sync entities rather than silently dropping them.
- Partial client implementations are not considered complete: no real Supabase/Cloudflare setup or Android↔Web end-to-end synchronization test has been performed; conflict resolution and Android history/library/settings integration remain open.
- Added a server-side account-scoped `/api/sync` contract with per-entity versions, request-fingerprinted retry-idempotent operations, paginated event deltas, explicit tombstones, and conflict responses; added D1 migrations `0002_sync.sql` and `0003_sync_operation_fingerprints.sql` and local SQLite/JWT tests. Client wiring and cross-device validation are tracked separately below.
- Hardened SAF folder rescans against cursor/provider exceptions; incomplete scans preserve prior records, and missing persisted read grants now show a reauthorization action. Added regression coverage for partial scans, grant status, permission-persistence failure, and the tree-picker contract.
- Added Cloudflare Access Bearer JWT support for native API clients using the existing signature/claim verification; this is not yet wired to Android login or data synchronization.
- Explicitly excluded Android app data from cloud backup and device transfer, and declared the Media3 unstable-API opt-in.
- Corrected the local Windows Android SDK property escaping needed by Release lint.
- Made persisted SAF folder access the primary Android library import path and added incremental rescanning, metadata reuse, exclusion handling, and safe index/queue cleanup.
- Added Android library search and clarified local playback and Web history limitations.
- Hardened Web Drive expired-token handling and added bounded local playback-history metadata.
- Prepared Android release minification, version properties, and environment-based signing without adding a keystore or credentials to the repository.
- Updated Android build tooling for API 36 and documented release/configuration gates.
- Connected Web and Android clients to the versioned `/api/sync` contract for partial account-scoped playlist/favorite flows; added Supabase identity handling and durable sync state without treating legacy local data as an automatic cloud upload.
- Added Web account-switch storage regression tests and an Android owner-isolation/tombstone instrumentation test. The Android regression test compiles but awaits emulator/device execution; no real-service cross-client sync has been demonstrated.
- Fixed Android favorite tombstone application so a deleted favorite is not overlaid as active.
- Configured local ignored Web/Android settings with the provided public Supabase URL/publishable key and pointed the Worker config at that project's JWKS issuer; no service-role/signing key was used.
- Switched Web/Android configuration naming to Supabase publishable key (retaining anon-key compatibility), set explicit Web and Android auth callback destinations, and implemented Android email-confirmation/password-recovery callback handling and password update.
- Added Android auth callback parser regression tests and Worker JWKS key-rotation refresh coverage. Public JWKS/Auth settings were checked read-only; user authentication and Cloudflare deployment remain untested.
- Fixed Vite environment loading so root `.env.local` values reach the client bundle as well as CSP generation; verified the Web/Android outputs contain only the intended public Supabase client configuration.

Local release validation passed, but the produced APK is unsigned and is not a distribution release. See [PROJECT_STATE.md](./PROJECT_STATE.md) for verified results and remaining requirements.

## 0.2.0 — Android player and library implementation

- Added persisted SAF folder access, bounded folder indexing with startup/manual rescan reconciliation, metadata/artwork extraction, duplicate handling, and safe index-only removal.
- Added SQLite v2 persistence and v1 migration for library metadata, playlists, favorites, playback queue/preferences, and history.
- Added Media3 background playback session and connected Compose playback, seek, queue, shuffle, repeat, and volume controls.
- Added JVM and Android instrumentation regression coverage. Four SQLite tests and two Media3 generated-WAV playback/error tests passed on an API 37 emulator; physical-device playback, SAF folder picking, and notification behavior remain unverified.

## Unreleased — Android SQLite account isolation phase 1

- Implemented Android SQLite database version 5 with account-scoped playlist, playlist-track, favorite, playback-history, playback-queue, and playback-preference tables.
- Preserved the shared physical library and legacy v1–v4 personal tables without assigning ownerless data to any account.
- Added owner-required account-scoped database access methods and migration coverage for database versions 1, 2, 3, and 4.
- Builds and tests were intentionally not run. `MainActivity.kt` and `WorkerSyncClient.kt` remain to be migrated in the next phase.

## Unreleased — Android account isolation integration

- Migrated Android personal-data consumers from global playlist, favorite, history, queue and preference APIs to the authenticated account-scoped tables.
- Added SQLite version 6 migration with historical metadata snapshots and timestamp ordering; legacy tables remain intact and unassigned.
- Updated the Media3 playback service to resolve the owner from the encrypted Supabase session before restoring or persisting account state.
- Added account reload handling for logout and account changes without deleting either account's local data.
- Guarded asynchronous Media3 history/queue writes with the captured active owner and a second session check during account changes.
- Made Worker remote-page application atomic with account state, remote records, entity versions and cursor, while rejecting session changes during synchronization.
- Prepared Android instrumentation coverage for history snapshots, playlist upserts and remote-apply rollback. Builds and tests were not run in this implementation pass.
