# Fray

Open-source, self-hostable community platform. Discord alternative for small, privacy-conscious communities.

## Project Structure
- `server/` — Quarkus server (Java). REST API + WebSocket.
- `client/` — SvelteKit web client (TypeScript). Bundled with server for self-hosting.
- `docs/plan/` — Project planning docs. Refer to these for feature specs, architecture decisions, and roadmap.

## Tech Stack
- **Server:** Quarkus (Java), GraalVM native image builds
- **Client:** SvelteKit, TypeScript
- **Database:** Turso / libSQL (embedded for self-hosting, managed for hosted)
- **Voice/Video:** LiveKit
- **Package manager:** pnpm (client)
- **Build:** Gradle (server), Vite (client)

## Key Architecture Decisions
- Each Fray instance = one independent community. No shared auth, no federation.
- JWT auth with short-lived access tokens + refresh tokens.
- WebSocket for real-time events (messages, presence, typing). REST for CRUD.
- Internal EventBus for async event dispatch — services publish events, bus handles permission-filtered fan-out to WebSocket sessions.
- Centralised PermissionService for all permission checks (API guards + event recipient filtering).
- Permissions are bitmask-based with channel-level overrides.
- FTS5 (SQLite full-text search) for message search.
- SQL-first — Flyway migrations, no heavy ORM.
- File storage: local filesystem (default) or S3-compatible.

## Conventions
- Server package: `chat.fray`
- API base path: `/api/v0`
- WebSocket endpoint: `/api/ws`
- Config via env vars prefixed `FRAY_` or config file.
- Server code: Quarkus standard conventions. Use Java.
- Client code: TypeScript strict mode. Svelte stores for state. Custom UI components (minimal external deps). Create separate clients

## Running Locally
- Server: `./gradlew quarkusDev` from `server/`
- Client: `pnpm dev` from `client/`
- Client proxies API to `localhost:8080`

## Plan Documents
See `docs/plan/` for detailed specs:
- `00-overview.md` — Vision, problem, principles
- `01-business.md` — Competitors, revenue, go-to-market
- `02-features.md` — MVP features, roadmap phases
- `03-architecture.md` — Tech stack, data model, API design, permissions
- `04-infrastructure.md` — Self-hosting, hosted platform, tunneling
- `05-development.md` — Dev workflow, milestones, open source strategy
