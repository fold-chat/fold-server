# Fold
Open-source, self-hostable community platform. Discord alternative for small, privacy-conscious communities.

## Project Structure
- `server/` — Quarkus server (Java 25). REST API + WebSocket. See `server/WARP.md`.
- `client/` — SvelteKit web client (TypeScript) + Tauri desktop shell (Rust). See `client/WARP.md`.
- `client/src-tauri/` — Tauri v2 desktop app. See `client/WARP.md` § Desktop.
- `docs/plan/` — Feature specs, architecture, roadmap.
- `docs/server-patterns.md` — Detailed server patterns reference.
- `docs/client-patterns.md` — Detailed client patterns reference.

## Tech Stack
- **Server:** Quarkus (Java 25), GraalVM native image, Gradle 9.1+ / Kotlin DSL
- **Client:** SvelteKit, TypeScript, Vite, pnpm
- **Desktop:** Tauri v2 (Rust), OS-native webview (WebKit/WebView2/WebKitGTK)
- **Database:** libSQL via FFM (`libsql-c` Rust wrapper). Embedded for self-hosting.

## Key Architecture Decisions
- Single instance = one community. No federation.
- JWT access (15m, `fold_access` cookie) + rotating refresh (30d, `fold_refresh`). JJWT, not SmallRye JWT.
- WebSocket for real-time, REST for CRUD.
- SQL-first — no ORM, custom MigrationRunner, repos return `Map<String, Object>`.
- Bitmask roles (owner/admin/moderator/member). Channel overrides planned.
- libSQL loaded via FFM. External `io.github.conorrr:libsql-java` dependency. `LibSqlNativeFeature` for GraalVM native image.

## Agent Pipeline
- `dispatcher/` — CF Worker receives Linear webhooks, dispatches Oz agents per ticket state change
- `.warp/skills/` — individual agent skills (dev, reviewer, tester, security, etc.)
- See `docs/agent-workflow.md` for full pipeline docs

## Running Locally
- Server: `./gradlew quarkusDev` from `server/`
- Client (web): `pnpm dev` from `client/` — proxies API to `localhost:8080`
- Client (desktop): `pnpm tauri dev` from `client/` — requires Rust toolchain, see `client/WARP.md` § Desktop
