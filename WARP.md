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
- **Database:** libSQL via FFM/JNI (`libsql-c` Rust wrapper). Embedded for self-hosting, Turso-compatible for managed.
- **Voice/Video:** LiveKit (planned)
- **Package manager:** pnpm (client)
- **Build:** Gradle 9.1+ / Kotlin DSL (server), Vite (client)

## Current State
Server: Auth (JWT + cookie-based sessions, Argon2id passwords, lockout), messaging (CRUD + UUIDv7 time-sorted IDs), channels, categories, invites, file uploads (content-addressed), user profiles, rate limiting, WebSocket (HELLO, heartbeat, typing, real-time event fan-out), first-run setup flow, role seeding.
Client: Login, register, setup, channel/category sidebar, message list + compose, typing indicators, file upload/attachments, invite flow, profile settings, read-state tracking, auto-reconnecting WebSocket.
10 migrations (V001–V010): server_config, user, session, role, invite, file, category, channel, message, channel_read_state.

## Key Architecture Decisions
- Each Fray instance = one independent community. No shared auth, no federation.
- JWT access tokens (15m, HttpOnly cookie `fray_access`) + rotating refresh tokens (30d, `fray_refresh`). JJWT library, not SmallRye JWT.
- WebSocket for real-time events (messages, presence, typing). REST for CRUD.
- Internal EventBus publishes `Event(type, data, scope)` — scope determines recipients (Server, Channel, User, Users). Fan-out via `SessionRegistry`.
- Permissions: bitmask-based roles seeded in V004 (owner/admin/moderator/member). Channel-level overrides planned.
- SQL-first — custom MigrationRunner, no ORM. Repositories return `Map<String, Object>` rows.
- libSQL native lib loaded via FFM (`java.lang.foreign`). `LibSqlNativeFeature` registers downcall descriptors for GraalVM native image.
- File storage: local filesystem, content-addressed (SHA-256 hash as filename).

---

## Server Patterns

### Configuration
SmallRye `@ConfigMapping` interfaces under `chat.fray.config`.
- One interface per domain: `FrayConfig` (db), `FrayAuthConfig` (auth), `FrayFileConfig` (files), `FrayRateLimitConfig` (rate limits).
- Prefix convention: `fray.<domain>` → `FrayConfig` = `fray.db`, `FrayAuthConfig` = `fray.auth`, etc.
- Every config property maps to a `FRAY_*` env var in `application.properties`. Pattern: `fray.some.prop=${FRAY_SOME_PROP:default}`.
- Use `@WithDefault("...")` for sensible defaults. Use `Optional<String>` for optional overrides (empty = not set).
- Dev profile (`%dev.`) overrides where needed (e.g. `%dev.fray.auth.dev=true` disables secure cookies).

### API Resources
JAX-RS resources under `chat.fray.api`. One resource per entity.
- Path: `@Path("/api/v0/<entity>")`. JSON in/out (`@Produces/@Consumes MediaType.APPLICATION_JSON`).
- DTOs: Java `record` types nested inside the resource class (e.g. `CreateChannelRequest`, `UpdateProfileRequest`).
- Error responses: `Map.of("error", "<code>", "message", "<human-readable>")` with appropriate HTTP status.
- Auth context: `(FraySecurityContext) requestContext.getSecurityContext()` — gives `getUserId()` + `getUsername()`. Resources define a `private FraySecurityContext sc()` helper.
- CRUD pattern: Create returns 201 + entity, Update returns 200 + entity, Delete returns 204 (no content), Not found returns 404 + `{"error": "not_found"}`.
- Nested sub-resources on parent path (e.g. messages under `/channels/{channelId}/messages`).

### Rate Limiting
Token-bucket based, in-memory (`ConcurrentHashMap<String, TokenBucket>` in `RateLimitService`).
- Defaults defined as `static final` constants on `RateLimitPolicy` record (e.g. `RateLimitPolicy.LOGIN = 5/60s`).
- Config overrides via `FrayRateLimitConfig` — each action can be overridden with `"count/windowSeconds"` format (e.g. `FRAY_RATE_LIMIT_LOGIN=10/60`).
- Key convention: `"ip:<addr>:<action>"` for unauthenticated, `"user:<id>:<action>"` for authenticated.
- Resources call `rateLimitService.resolvePolicy(actionName, defaultPolicy)` then `rateLimitService.check(key, policy)`.
- Result stored as request property (`RateLimitFilter.RATE_LIMIT_RESULT_KEY`), picked up by `RateLimitFilter` which adds `X-RateLimit-*` headers.
- 429 response: `{"error": "rate_limited", "retry_after": <seconds>}`.
- Stale buckets cleaned every 5m via `@Scheduled`.
- Rate limit can be globally disabled via `fray.rate-limit.enabled=false`.

### Auth Flow
- `AuthFilter` (JAX-RS `@Provider`, `@Priority(AUTHENTICATION)`) checks `fray_access` cookie on all non-public paths. Sets `FraySecurityContext` on success.
- Public paths defined in `AuthFilter.isPublicPath()`: login, register, refresh, status, setup, file serving, invite GET.
- Login: verify password → create session row → issue access JWT + refresh token (SHA-256 hashed in DB). Failed logins increment counter → lockout after threshold.
- Refresh: validate old refresh hash → rotate to new token + update session row → issue new access JWT.
- Password: Argon2id via BouncyCastle. Encoded format: `$argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>`.
- JWT secret: config → DB `server_config` table → auto-generate. Persistent across restarts.
- Password change revokes all other sessions.

### Database & Repositories
`DatabaseService` is the single DB access point. `@ApplicationScoped`, thread-local connection pool.
- Methods: `execute(sql, params)` (write, returns rows changed), `query(sql, params)` (read, returns `List<Map<String, Object>>`), `batch(sql)` (multi-statement), `transaction(Function<TxContext, T>)` / `transactionVoid(Consumer<TxContext>)`.
- Param binding: automatic type dispatch (null → null, Long/Integer → integer, Double/Float → real, String → text, byte[] → blob).
- Repositories: one per entity under `chat.fray.db`. `@ApplicationScoped`, inject `DatabaseService`. Methods return `Optional<Map<String, Object>>` for single rows, `List<Map<String, Object>>` for lists.
- Pattern: `findById`, `create`, `update`, `delete`/`softDelete`, `listAll`, domain-specific queries.
- Soft deletes: `deleted_at TEXT` column, queries filter `WHERE deleted_at IS NULL` (users, files).
- Message IDs: UUIDv7 via `java-uuid-generator` (`Generators.timeBasedEpochGenerator()`). Lexicographic sort = time sort. Used for cursor pagination.

### Migrations
Custom `MigrationRunner` (`@Startup`). Scans `db/migration/V###__name.sql` from classpath.
- Naming: `V<NNN>__<description>.sql` (zero-padded 3-digit version, double underscore separator).
- Tracks applied versions in `_migrations` table.
- SQL conventions: `CREATE TABLE IF NOT EXISTS`, `INSERT OR IGNORE` for seeds, `TEXT NOT NULL DEFAULT (datetime('now'))` for timestamps, `TEXT PRIMARY KEY` (UUID strings), foreign keys with `ON DELETE CASCADE` or `ON DELETE SET NULL`.
- Indexes: explicit `CREATE INDEX IF NOT EXISTS idx_<table>_<column>`.

### WebSocket
`FrayWebSocket` at `/api/ws`. Auth via `fray_access` cookie parsed from handshake `Cookie` header.
- On connect: verify JWT → register in `SessionRegistry` → send `HELLO` payload (user, channels, categories, members, read_states, heartbeat_interval).
- Client ops: `HEARTBEAT` (→ `HEARTBEAT_ACK`), `TYPING` / `TYPING_STOP`.
- `SessionRegistry`: userId → Set<WebSocketConnection>, bidirectional lookup.
- `EventBus.publish(Event)`: resolves targets from `Scope`, serializes `{"op", "d", "s"}` JSON, dispatches on Vert.x event loop. Sequence counter for ordering.

### Events
- `Event` record: `(EventType, Object data, Scope, excludeUserId)`.
- `EventType` enum: HELLO, MESSAGE_CREATE/UPDATE/DELETE, CHANNEL_CREATE/UPDATE/DELETE, CATEGORY_CREATE/UPDATE/DELETE, TYPING_START/STOP, PRESENCE_UPDATE, MEMBER_JOIN/LEAVE/UPDATE, READ_STATE_UPDATE.
- `Scope` sealed interface: `Server` (all connections), `Channel(channelId)`, `User(userId)`, `Users(Set<userIds>)`.
- Resources publish events after successful CRUD ops: `eventBus.publish(Event.of(EventType.X, data, Scope.y()))`.

### GraalVM Native Image
- `LibSqlNativeFeature implements Feature` registers all FFM downcall descriptors at build time. Struct layouts duplicated from `LibSql` (must stay in sync).
- `application.properties` native config:
  - `--enable-native-access=ALL-UNNAMED`
  - `--initialize-at-run-time=` for classes that load native libs or use SecureRandom at init.
  - `--features=chat.fray.db.LibSqlNativeFeature`
  - `@RegisterForReflection` on `JwtService` for JJWT internal classes.
- Any new class using `SecureRandom`, native libs, or runtime-only resources in `@PostConstruct` must be added to `--initialize-at-run-time`.
- Any new FFM `FunctionDescriptor` in `LibSql` must be mirrored in `LibSqlNativeFeature.duringSetup()`.

---

## Client Patterns

### Structure
- `src/lib/api/` — API client functions. One file per domain (auth, channels, messages, users, invites, upload).
- `src/lib/stores/` — Svelte 5 runes-based state (`.svelte.ts`). One store per domain (auth, channels, messages, ws).
- `src/lib/components/` — Svelte components (Sidebar, MessageList, MessageCompose).
- `src/lib/utils/` — Utilities (e.g. markdown).
- `src/routes/` — SvelteKit file-based routing. `(app)/` group for authenticated layout.

### API Client
- `api<T>(path, options)` wrapper around fetch. Base path `/api/v0`. Credentials: `same-origin` (cookie auth).
- Auto-refresh: on 401 → call `/auth/refresh` once → retry original request. If refresh fails → redirect to `/login`.
- `apiRaw(path, options)` for non-JSON responses (e.g. file uploads).
- Error type: `ApiError { error: string; message?: string; retry_after?: number }`.
- API functions are thin typed wrappers: `export function getChannels() { return api<Channel[]>('/channels'); }`.

### State (Svelte 5 Runes)
- `$state` for reactive state, exported getter/setter functions (not raw state). Pattern:
  ```
  let foo = $state<T>(initial);
  export function getFoo(): T { return foo; }
  export function setFoo(v: T) { foo = v; }
  ```
- Immutable updates: always create new Map/Array (`new Map(existing)`, `[...existing, item]`) to trigger reactivity.
- No classes or Svelte 4 stores. Pure functions + runes.

### WebSocket Client
- `ws.svelte.ts`: connect/disconnect/send. Auto-reconnect with exponential backoff (max 30s).
- Heartbeat on interval from HELLO payload.
- `handleEvent` switch dispatches ops to store functions (e.g. `MESSAGE_CREATE` → `appendMessage`).
- HELLO populates channels, categories, read_states stores.

### Routing
- `(app)/` route group for authenticated pages (layout checks auth, connects WS).
- Public routes: `/login`, `/register`, `/setup`, `/invite/[code]`.
- Authenticated: `/(app)/` (home), `/(app)/channels/[id]`, `/settings/profile`.

---

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
