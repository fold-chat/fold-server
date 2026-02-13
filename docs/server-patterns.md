# Server Patterns Reference

## Configuration
SmallRye `@ConfigMapping` interfaces under `chat.kith.config`.
- One interface per domain: `KithConfig` (db), `KithAuthConfig` (auth), `KithFileConfig` (files), `KithRateLimitConfig` (rate limits).
- Prefix convention: `kith.<domain>` → `KithConfig` = `kith.db`, `KithAuthConfig` = `kith.auth`, etc.
- Every config property maps to a `KITH_*` env var in `application.properties`. Pattern: `kith.some.prop=${KITH_SOME_PROP:default}`.
- Use `@WithDefault("...")` for sensible defaults. Use `Optional<String>` for optional overrides (empty = not set).
- Dev profile (`%dev.`) overrides where needed (e.g. `%dev.kith.auth.dev=true` disables secure cookies).

## API Resources
JAX-RS resources under `chat.kith.api`. One resource per entity.
- Path: `@Path("/api/v0/<entity>")`. JSON in/out (`@Produces/@Consumes MediaType.APPLICATION_JSON`).
- DTOs: Java `record` types nested inside the resource class (e.g. `CreateChannelRequest`, `UpdateProfileRequest`).
- Error responses: `Map.of("error", "<code>", "message", "<human-readable>")` with appropriate HTTP status.
- Auth context: `(KithSecurityContext) requestContext.getSecurityContext()` — gives `getUserId()` + `getUsername()`. Resources define a `private KithSecurityContext sc()` helper.
- CRUD pattern: Create returns 201 + entity, Update returns 200 + entity, Delete returns 204 (no content), Not found returns 404 + `{"error": "not_found"}`.
- Nested sub-resources on parent path (e.g. messages under `/channels/{channelId}/messages`).

## Rate Limiting
Token-bucket based, in-memory (`ConcurrentHashMap<String, TokenBucket>` in `RateLimitService`).
- Defaults defined as `static final` constants on `RateLimitPolicy` record (e.g. `RateLimitPolicy.LOGIN = 5/60s`).
- Config overrides via `KithRateLimitConfig` — each action can be overridden with `"count/windowSeconds"` format (e.g. `KITH_RATE_LIMIT_LOGIN=10/60`).
- Key convention: `"ip:<addr>:<action>"` for unauthenticated, `"user:<id>:<action>"` for authenticated.
- Resources call `rateLimitService.resolvePolicy(actionName, defaultPolicy)` then `rateLimitService.check(key, policy)`.
- Result stored as request property (`RateLimitFilter.RATE_LIMIT_RESULT_KEY`), picked up by `RateLimitFilter` which adds `X-RateLimit-*` headers.
- 429 response: `{"error": "rate_limited", "retry_after": <seconds>}`.
- Stale buckets cleaned every 5m via `@Scheduled`.
- Rate limit can be globally disabled via `kith.rate-limit.enabled=false`.

## Auth Flow
- `AuthFilter` (JAX-RS `@Provider`, `@Priority(AUTHENTICATION)`) checks `kith_access` cookie on all non-public paths. Sets `KithSecurityContext` on success.
- Public paths defined in `AuthFilter.isPublicPath()`: login, register, refresh, status, setup, file serving, invite GET.
- Login: verify password → create session row → issue access JWT + refresh token (SHA-256 hashed in DB). Failed logins increment counter → lockout after threshold.
- Refresh: validate old refresh hash → rotate to new token + update session row → issue new access JWT.
- Password: Argon2id via BouncyCastle. Encoded format: `$argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>`.
- JWT secret: config → DB `server_config` table → auto-generate. Persistent across restarts.
- Password change revokes all other sessions.

## Database & Repositories
`DatabaseService` is the single DB access point. `@ApplicationScoped`, thread-local connection pool.
- Methods: `execute(sql, params)` (write, returns rows changed), `query(sql, params)` (read, returns `List<Map<String, Object>>`), `batch(sql)` (multi-statement), `transaction(Function<TxContext, T>)` / `transactionVoid(Consumer<TxContext>)`.
- Param binding: automatic type dispatch (null → null, Long/Integer → integer, Double/Float → real, String → text, byte[] → blob).
- Repositories: one per entity under `chat.kith.db`. `@ApplicationScoped`, inject `DatabaseService`. Methods return `Optional<Map<String, Object>>` for single rows, `List<Map<String, Object>>` for lists.
- Pattern: `findById`, `create`, `update`, `delete`/`softDelete`, `listAll`, domain-specific queries.
- Soft deletes: `deleted_at TEXT` column, queries filter `WHERE deleted_at IS NULL` (users, files).
- Message IDs: UUIDv7 via `java-uuid-generator` (`Generators.timeBasedEpochGenerator()`). Lexicographic sort = time sort. Used for cursor pagination.

## Migrations
Custom `MigrationRunner` (`@Startup`). Scans `db/migration/V###__name.sql` from classpath.
- Naming: `V<NNN>__<description>.sql` (zero-padded 3-digit version, double underscore separator).
- Tracks applied versions in `_migrations` table.
- SQL conventions: `CREATE TABLE IF NOT EXISTS`, `INSERT OR IGNORE` for seeds, `TEXT NOT NULL DEFAULT (datetime('now'))` for timestamps, `TEXT PRIMARY KEY` (UUID strings), foreign keys with `ON DELETE CASCADE` or `ON DELETE SET NULL`.
- Indexes: explicit `CREATE INDEX IF NOT EXISTS idx_<table>_<column>`.

## WebSocket
`KithWebSocket` at `/api/ws`. Auth via `kith_access` cookie parsed from handshake `Cookie` header.
- On connect: verify JWT → register in `SessionRegistry` → send `HELLO` payload (user, channels, categories, members, read_states, heartbeat_interval).
- Client ops: `HEARTBEAT` (→ `HEARTBEAT_ACK`), `TYPING` / `TYPING_STOP`.
- `SessionRegistry`: userId → Set<WebSocketConnection>, bidirectional lookup.
- `EventBus.publish(Event)`: resolves targets from `Scope`, serializes `{"op", "d", "s"}` JSON, dispatches on Vert.x event loop. Sequence counter for ordering.

## Events
- `Event` record: `(EventType, Object data, Scope, excludeUserId)`.
- `EventType` enum: HELLO, MESSAGE_CREATE/UPDATE/DELETE, CHANNEL_CREATE/UPDATE/DELETE, CATEGORY_CREATE/UPDATE/DELETE, TYPING_START/STOP, PRESENCE_UPDATE, MEMBER_JOIN/LEAVE/UPDATE, READ_STATE_UPDATE.
- `Scope` sealed interface: `Server` (all connections), `Channel(channelId)`, `User(userId)`, `Users(Set<userIds>)`.
- Resources publish events after successful CRUD ops: `eventBus.publish(Event.of(EventType.X, data, Scope.y()))`.

## GraalVM Native Image
- `LibSqlNativeFeature` (from external `io.github.conorrr:libsql-java` dependency) registers all FFM downcall descriptors at build time.
- `application.properties` native config:
  - `--enable-native-access=ALL-UNNAMED`
  - `--initialize-at-run-time=` for classes that load native libs or use SecureRandom at init.
  - `--features=uk.co.rstl.libsql.LibSqlNativeFeature`
  - `@RegisterForReflection` on `JwtService` for JJWT internal classes.
- Any new class using `SecureRandom`, native libs, or runtime-only resources in `@PostConstruct` must be added to `--initialize-at-run-time`.
