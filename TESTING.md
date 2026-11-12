# Testing

## Commands

```powershell
npm.cmd test
npm.cmd run build
npm.cmd run check:all
npm.cmd run db:migrate:local
npm.cmd exec -- wrangler deploy --dry-run --config backend/wrangler.toml
npm.cmd audit --omit=dev
.\android\gradlew.bat -p android --no-daemon --max-workers=1 :app:testDebugUnitTest
.\android\gradlew.bat -p android --no-daemon --max-workers=1 :app:connectedDebugAndroidTest
.\android\gradlew.bat -p android --no-daemon --max-workers=1 :app:compileReleaseKotlin :app:lintRelease
# Only after production signing credentials are configured:
.\android\gradlew.bat -p android --no-daemon --max-workers=1 :app:assembleRelease
```

`npm.cmd run check:all` runs Node tests, the production Web build, and Android JVM unit tests. Tests use deterministic local/in-memory fixtures; they do not need Spotify, Google, Last.fm, MusicBrainz, Cloudflare, or a real music library. The sync-contract test uses Node.js 22.5+ `node:sqlite`.
`connectedDebugAndroidTest` installs its test build only into the configured emulator/device; do not ask users to install development APKs. Run `assembleRelease` only with the production signing configuration and do not deliver an unsigned output.

## Latest verified results (2026-11-11)

- Node: **50 passed, 0 failed** (`npm.cmd run check:all`; includes local SQLite Worker sync-contract tests, Web account-partition regression tests, and JWKS key-rotation refresh).
- Android JVM: **25 passed, 0 failed** (`MusicModelsTest` and Supabase auth callback URI tests).
- Vite production build: passed with default CSP and with a configured HTTPS API origin; both generated policies were inspected.
- The production Web bundle and Android Release BuildConfig were verified to contain the configured public Supabase URL/publishable key; CSP includes the Supabase origin. The key was not printed in test output.
- Previously verified Android `:app:testDebugUnitTest :app:connectedDebugAndroidTest`: **BUILD SUCCESSFUL** with compile/target SDK 36. **7/7 instrumented tests passed** on `Medium_Phone_API_37.0`: 4 SQLite tests, 2 Media3 playback/error tests, and 1 SAF folder-picker contract test.
- Instrumented database tests use unique per-test SQLite filenames to avoid deleting or mutating the app service's live database.
- Media3 instrumentation decoded a generated local WAV and exercised ready/playing, seek, pause/resume, natural completion, and missing-file error handling. This validates the playback engine in an emulator, not the service notification path or physical-device audio.
- Release Kotlin compilation and `lintRelease` passed this round. A full Release package was not assembled because no production key is configured and an unsigned APK must not be delivered. A pre-existing `app-release-unsigned.apk` in the build output is not distributable and is not final user delivery.
- This turn's `:app:connectedDebugAndroidTest` attempt stopped before instrumentation because there were no connected devices. The previously recorded 7/7 API 37 emulator results remain prior evidence; no new device test was completed.
- A cold emulator launch returned `Status: ok` and a live app process. Earlier UI hierarchy inspection showed a system/UI “isn't responding” dialog; no actual SAF selection or real-file indexing is claimed from this check.
- Wrangler Worker deployment dry-run: passed using placeholder D1/Access bindings; no live deployment was attempted.
- Local D1 migration command: passed; no unapplied migrations remained.
- Production dependency audit: 0 vulnerabilities.
- Browser smoke: created a temporary two-track manual playlist; verified full track visibility, 0% availability, and two missing tracks; deleted the temporary playlist.
- Responsive browser checks at **320, 360, 390, 412, 430, 768, 1024, and 1280 px** found no document-level horizontal overflow. At phone widths, navigation labels stayed on one line, touch targets were at least 52 px high, the horizontal nav scrolled to expose the last tab, and the hero began 16 px below the mobile header.
- Android Match-summary JVM coverage verifies empty analysis is distinct from indexed files and checks found, missing, review, duplicate, and completeness counts.
- New SAF JVM coverage verifies incomplete provider scans do not delete older indexed tracks, preserve favorites, report missing persisted folder grants, and surface failed persistence. `SafFolderContractTest` ran on the API 37 emulator and verifies the Android picker uses `ACTION_OPEN_DOCUMENT_TREE`; it does not simulate a human selection, actual permission grant, or physical document provider.
- Worker authentication tests verify Access and Supabase ES256 JWT validation, including a namespaced verified subject. `backend/test/sync.test.js` exercises idempotent retry, operation-ID reuse rejection, optimistic conflicts, event pagination, tombstones, account isolation, validation, and payload limits against local SQLite and signed test JWTs.
- Android JVM compilation/test and lint cover the current auth/sync sources, callback URI parsing, and stable track identity, but do not exercise Android Keystore, Supabase REST, Worker HTTP, or live synchronization. Android instrumentation for owner-scoped sync records/outboxes/cursors compiles but has not run because no emulator/device is connected. SQLite v4 migration remains without a dedicated current-schema migration test.
- `test/account-storage.test.js` verifies Web account switching for local playlists, favorites, and history and confirms signed-in state does not inherit legacy anonymous state. This is a local storage-helper test, not a browser auth or remote sync test.
- `android/app/src/test/java/com/ascmusic/SupabaseAuthCallbackTest.kt` tests strict callback scheme/host/path/type and token-presence parsing. It does not simulate Supabase login or validate tokens; the Worker remains responsible for signed-token verification.
- `android/app/src/androidTest/java/com/ascmusic/LibraryDatabaseTest.kt` also prepares coverage for applying Worker-shaped history (`playedAt`, metadata snapshots) and account setting (`id` plus `payload.value`) records transactionally; it was not executed in this pass.
- Worker auth tests verify unknown-`kid` key-rotation recovery by refetching JWKS; the real project's public JWKS was separately fetched read-only and returned an ES256 P-256 key. Neither check proves a real user's JWT is accepted by a deployed Worker.
- Supabase dashboard configuration recorded by the owner: project `bodlzdmtoaezftooewqi`, Site URL `http://localhost:5173/`, Web redirect `http://localhost:5173/`, Android redirects `ascmusic://auth/confirm` and `ascmusic://auth/recovery`, and Email provider enabled. No account creation, email confirmation, password reset, Postgres table/policy, live Worker, or D1 operation was performed. PostgREST schema listing was unavailable using the publishable key.
- Client sync verification is source/build-only: no test has demonstrated Android → Worker → Web → Worker → Android playlist/favorite changes, interrupted requests, or conflict recovery.

## Required ASC Music Match/import/discovery cases

| Requirement | Coverage |
|---|---|
| Identical title and artist | `test/music-match.test.js`, `android/app/src/test/java/com/ascmusic/MusicModelsTest.kt` |
| Filename differs from audio metadata | `test/music-match.test.js`, Android JVM test |
| Accent variation | `test/music-match.test.js`, Android JVM test |
| Featured artists | `test/music-match.test.js`, Android JVM test |
| Live vs. original; distinct live venues | `test/music-match.test.js`, Android JVM tests |
| Remix vs. original; distinct remix subtypes | `test/music-match.test.js`, Android JVM tests |
| Duplicate files | `test/music-match.test.js`, Android JVM test |
| Missing tags | `test/music-match.test.js`, `test/library-index.test.js`, Android JVM test |
| Removed Drive file | `test/music-match.test.js`, `test/library-index.test.js` |
| Added after playlist creation | `test/music-match.test.js` |
| CSV import, quoting, metadata, malformed input | `test/playlist-import.test.js`, Android JVM tests |
| Partial availability/completeness | `test/music-match.test.js` |
| Unavailable discovery source | `backend/test/discovery.test.js` |
| Worker body limits, invalid JSON, stream cancellation | `backend/test/input.test.js` |
| Access authentication, private API behavior, origin checks | `backend/test/*.test.js` |
| Incremental metadata sync and removed IDs | `test/library-index.test.js` |
| Android duplicate detection, playback queue restoration, repeat modes, folder reconciliation, incomplete-scan retention, persisted-grant status, manual playlists, and SAF audio extension filtering | `android/app/src/test/java/com/ascmusic/MusicModelsTest.kt` |
| Android SAF folder-picker whole-tree action | `android/app/src/androidTest/java/com/ascmusic/SafFolderContractTest.kt` (instrumentation test; not yet confirmed on a physical device) |
| Android SQLite playlist/favorite/queue/history/preferences persistence, v1 schema migration, index-only removal, folder removal and queue cleanup | `android/app/src/androidTest/java/com/ascmusic/LibraryDatabaseTest.kt` (4 prior instrumented tests passed on API 37 emulator) |
| Android account-scoped remote playlists/favorites, outboxes/cursors, and favorite tombstones | `android/app/src/androidTest/java/com/ascmusic/LibraryDatabaseTest.kt` (new regression test compiles; execution awaits an emulator/device) |
| Android Media3 local WAV playback, pause/seek/resume, natural completion, and unavailable-file error | `android/app/src/androidTest/java/com/ascmusic/Media3PlayerTest.kt` (2 instrumented tests passed on API 37 emulator) |

## Integration gaps

- No live Supabase account/JWKS, Cloudflare Access/D1, Spotify OAuth/API, Google Picker/Drive account, provider quota, or production deployment test has been performed.
- Prepared coverage still needed for Drive Changes API pagination/checkpoint recovery, moved-folder fallback, cancellation, and large-library delta indexing; Spotify authorized-playlist listing/import remains dependent on a real PKCE test account.
- Native-provider coverage still needs Android JVM/instrumentation tests for PKCE state rejection, encrypted token refresh/clear, Google Drive pagination and cancellation, Spotify pagination/import limits, Media3 Drive range requests, account-scoped Drive metadata, and interrupted sync retry. These tests were not executed in this pass.
- Actual selected-folder grant persistence and recursive traversal through real/fake DocumentsProviders, MediaSessionService/notification behavior, audio interruptions, and recovery remain unverified. UIAutomator folder-screen inspection was blocked by the emulator system-UI ANR in the previous session.
- No automated browser E2E/accessibility suite exists yet; the reported browser check is a manual smoke test.
- No signed release APK has been produced; the production keystore and credentials are not present and must remain outside the repository.
- Native Android account UI and partial playlist/favorite sync are implemented but not service-tested; Android Drive/Spotify are now wired locally but require provider configuration and live validation. Web and Android have durable outboxes/cursors in code, but no client-level tests validate retry, conflict, or offline resume.
- Web applies playlists/favorites/history/library metadata and account playback settings locally; Android applies playlists/local favorites/history/library metadata/settings and stores remote records by account. Both clients now expose conflict choices, but no cross-client flow is validated.

## Tests prepared but not executed in the autonomous development pass

- `test/playback-history.test.js` now asserts that explicit and default Web history entries use millisecond timestamps while remaining metadata-only and bounded.
- `test/account-storage.test.js` continues to cover account partitioning and non-inheritance of legacy anonymous state; queue/settings/Drive-key behavior is covered by the implementation path but still needs browser IndexedDB/localStorage integration coverage.
- A browser IndexedDB test is still required for store-upgrade idempotence, outbox owner filtering, owner-change cancellation, replay after failure between local apply and cursor acknowledgement, and atomic entity-version/cursor persistence.
- Web conflict tests still need to cover accepting a remote record and rebasing a local outbox operation against the returned server version; Android conflict-dialog actions need instrumentation coverage for both choices.
- Web legacy import needs coverage for explicit opt-in, playlist ID collision remapping, preserved legacy keys, and retry behavior when synchronization is interrupted.
- Android legacy import needs instrumentation coverage for explicit opt-in, playlist collision remapping, preservation of legacy tables, current-time stamping of timestamp-less legacy history, and account isolation.
- Android playback preference and library-metadata outbox behavior is implemented but requires tests for coalescing, stable metadata IDs, tombstones, and account switching before the sync gate can close.
- Android account-isolation instrumentation remains prepared in `android/app/src/androidTest/java/com/ascmusic/LibraryDatabaseTest.kt`; it has not been executed in this pass.

No build, test, APK installation, deployment, or external-service validation was performed in this pass.
## Android account-isolation validation pending

The following instrumentation tests were prepared but intentionally not executed in the account-isolation implementation pass:

- account history keeps the real millisecond timestamp and metadata snapshot after the physical track is removed from the local index;
- updating one account playlist does not remove another playlist owned by the same account;
- malformed remote records roll back account state and the synchronization cursor together;
- existing migration, legacy-data preservation, account collision and owner-isolation tests remain in `android/app/src/androidTest/java/com/ascmusic/LibraryDatabaseTest.kt`.

The required validation still includes two-account logout/login switching, Media3 reload, Worker interruption/retry, authentication/session refresh and a real Supabase/Worker round trip. No build or test command was run for this implementation pass.
