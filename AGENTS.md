# ASC Music contributor instructions

## Project shape

- `web/` is the responsive Vite frontend. Keep browser integrations in `web/src/`.
- `shared/` contains browser/Node music matching, playlist import, and incremental-index logic. Keep this layer dependency-light so Node tests can exercise real behavior.
- `backend/` is the Cloudflare Worker and D1 migration. Authenticate private requests at the Worker boundary and include `user_id` in every query/write.
- `android/` is a native Kotlin/Compose/Media3 application. Do not claim cloud or provider behavior until implemented and tested on Android.
- `test/` and `backend/test/` use Node's built-in test runner.

## Change and safety rules

- Never scrape Spotify or use unofficial download/conversion services.
- Do not put credentials in the Web, Android, database, or committed configuration. Browser OAuth uses PKCE; Cloudflare credentials belong in Worker secrets.
- Preserve complete playlist track lists and report missing/ambiguous versions; do not silently substitute live/remix/clean/explicit recordings.
- Store audio metadata and references, never full audio payloads in D1.
- Sanitize URLs before rendering and escape all user/provider text in HTML.
- Keep provider source and update timestamps visible. A release date is not a Spotify catalog-addition timestamp.
- Document unavailable external configuration instead of substituting fabricated or success-shaped data.
- Keep `PROJECT_STATE.md`, `ROADMAP.md`, and `CHANGELOG.md` accurate.

## Validation

Run `npm.cmd test` and `npm.cmd run build` for Web/shared/backend changes. Run `.\android\gradlew.bat -p android :app:assembleDebug` for Android changes. See [TESTING.md](./TESTING.md) for what is and is not covered.
