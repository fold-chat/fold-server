# Kith
Open-source, self-hostable community platform. Discord alternative for small, privacy-conscious communities.

## Project Structure
- `server/` — Quarkus server (Java 25). REST API + WebSocket. See `server/WARP.md`.
- `client/` — SvelteKit web client (TypeScript). See `client/WARP.md`.
- `docs/plan/` — Feature specs, architecture, roadmap.
- `docs/server-patterns.md` — Detailed server patterns reference.
- `docs/client-patterns.md` — Detailed client patterns reference.

## Tech Stack
- **Server:** Quarkus (Java 25), GraalVM native image, Gradle 9.1+ / Kotlin DSL
- **Client:** SvelteKit, TypeScript, Vite, pnpm
- **Database:** libSQL via FFM (`libsql-c` Rust wrapper). Embedded for self-hosting.

## Key Architecture Decisions
- Single instance = one community. No federation.
- JWT access (15m, `kith_access` cookie) + rotating refresh (30d, `kith_refresh`). JJWT, not SmallRye JWT.
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
- Client: `pnpm dev` from `client/`
- Client proxies API to `localhost:8080`
