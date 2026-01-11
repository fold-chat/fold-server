# Fray

Open-source, self-hostable community platform. Discord alternative for small, privacy-conscious communities.

## Project Structure
- `server/` — Quarkus server (Java 25). REST API + WebSocket.
- `client/` — SvelteKit web client (TypeScript). Bundled into server for self-hosting.
- `docs/plan/` — Project planning docs. Feature specs, architecture decisions, roadmap.
- `Dockerfile.jvm` / `Dockerfile.native` — Docker builds (JVM uber-jar or GraalVM native).
- `.github/workflows/` — CI (build + test) and Release (multi-platform native + Docker + GitHub Release).

## Tech Stack
- **Server:** Quarkus (Java 25), GraalVM native image builds
- **Client:** SvelteKit, TypeScript, Vite
- **Database:** libSQL via JNI (`libsql-c` Rust wrapper). Embedded for self-hosting, Turso-compatible for managed.
- **Voice/Video:** LiveKit (planned)
- **Package manager:** pnpm (client)
- **Build:** Gradle 9.1+ / Kotlin DSL (server), Vite (client)

## Current State (Skeleton)
Server has: StatusResource (health), libSQL JNI wrapper (LibSql, LibSqlLoader, DatabaseService), custom MigrationRunner (not Flyway), FrayConfig, SpaFallbackHandler. One migration: `V001__server_config.sql`.
Client has: bare SvelteKit scaffold (default page + layout).
No auth, messaging, channels, permissions, or WebSocket logic implemented yet.

## Key Architecture Decisions
- Each Fray instance = one independent community. No shared auth, no federation.
- JWT auth with short-lived access tokens + refresh tokens (SmallRye JWT dep included, not yet wired).
- WebSocket for real-time events (messages, presence, typing). REST for CRUD. (`quarkus-websockets-next` dep included.)
- Internal EventBus for async event dispatch — services publish events, bus handles permission-filtered fan-out to WebSocket sessions.
- Centralised PermissionService for all permission checks (API guards + event recipient filtering).
- Permissions are bitmask-based with channel-level overrides.
- FTS5 (SQLite full-text search) for message search.
- SQL-first — custom MigrationRunner (versioned SQL files in `db/migration/`), no ORM.
- libSQL native lib built from `libsql-c` Rust crate. Loaded via JNI at runtime. Platform-specific binaries in `server/native/<os-arch>/`.
- File storage: local filesystem (default) or S3-compatible.

## Conventions
- Server package: `chat.fray`
- API base path: `/api/v0`
- WebSocket endpoint: `/api/ws`
- Config via env vars prefixed `FRAY_` (mapped in `application.properties`).
- Server code: Quarkus standard conventions. Java 25.
- Client code: TypeScript strict mode. Svelte stores for state. Custom UI components (minimal external deps).

## Running Locally
- Server: `./gradlew quarkusDev` from `server/` (auto-builds libsql-c if missing)
- Client: `pnpm dev` from `client/`
- Client proxies API to `localhost:8080`
- Bundled build: `./gradlew buildClient` copies client output into server resources.

## CI/CD
- **CI:** Builds libsql (Rust), client (pnpm), server (Gradle) on push/PR to main.
- **Release:** On tag push (`v*`), builds native binaries for linux-amd64, linux-arm64, macos-arm64, windows-amd64. Publishes Docker images to `ghcr.io/fray-chat/fray` + GitHub Release with fat JAR and all native binaries.

## Plan Documents
See `docs/plan/` for detailed specs:
- `00-overview.md` — Vision, problem, principles
- `01-business.md` — Competitors, revenue, go-to-market
- `02-features.md` — MVP features, roadmap phases
- `03-architecture.md` — Tech stack, data model, API design, permissions
- `04-infrastructure.md` — Self-hosting, hosted platform, tunneling
- `05-development.md` — Dev workflow, milestones, open source strategy
