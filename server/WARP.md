# Server Conventions
Quarkus (Java 25). Full patterns reference: `docs/server-patterns.md`.

## Config
- `@ConfigMapping` interfaces in `chat.kith.config`, prefix `kith.<domain>`.
- Every property → `KITH_*` env var in `application.properties`.
- `@WithDefault` for defaults, `Optional<String>` for optional.

### Runtime Config
- `RuntimeConfigService` (`chat.kith.config`) — CDI bean, in-memory `ConcurrentHashMap` cache backed by `server_config` table.
- Use for admin-overridable keys (e.g. `kith.livekit.max-participants`, `kith.livekit.e2ee`). Use `@ConfigMapping` for bootstrap/static config.
- `getString(key, default)` / `getInt(key, default)` / `getBoolean(key, default)` — reads from cache.
- `refresh()` — reloads cache from DB. Called after admin writes via `ConfigResource`.
- Whitelist in `RuntimeConfigService.WHITELISTED_KEYS` controls which keys the admin API can set.
- Admin API: `GET/PATCH /api/v0/config` (requires `MANAGE_SERVER`). Publishes `SERVER_CONFIG_UPDATE` event.

## API Resources
- `chat.kith.api`, `@Path("/api/v0/<entity>")`, JSON in/out.
- DTOs: `record` types nested in resource class.
- Errors: `Map.of("error", "<code>", "message", "<text>")`.
- Auth: `sc()` helper returns `KithSecurityContext` → `getUserId()`, `getUsername()`.
- CRUD: 201 create, 200 update, 204 delete, 404 not found.

## Database
- `DatabaseService`: `execute()` (write), `query()` (read), `transaction()`.
- Repos in `chat.kith.db`, `@ApplicationScoped`. Return `Optional<Map<String, Object>>` / `List<Map<String, Object>>`.
- Soft deletes: `deleted_at TEXT`, filter `WHERE deleted_at IS NULL`.
- IDs: UUIDv7 (`Generators.timeBasedEpochGenerator()`).

## Migrations
- `db/migration/V<NNN>__<description>.sql` (3-digit, double underscore).
- SQL: `TEXT PRIMARY KEY`, `TEXT NOT NULL DEFAULT (datetime('now'))` for timestamps, `ON DELETE CASCADE`/`SET NULL`, `CREATE INDEX IF NOT EXISTS idx_<table>_<column>`.

## Events
- `eventBus.publish(Event.of(EventType.X, data, Scope.y()))` after CRUD ops.
- Scopes: `Server`, `Channel(id)`, `User(id)`, `Users(Set<id>)`.

## Rate Limiting
- `rateLimitService.resolvePolicy(action, default)` then `.check(key, policy)`.
- Key: `"ip:<addr>:<action>"` (unauth) or `"user:<id>:<action>"` (auth).

## GraalVM Gotchas
- New `SecureRandom`/native lib usage in `@PostConstruct` → add to `--initialize-at-run-time`.
- libsql-java FFM bindings and `LibSqlNativeFeature` provided by external `io.github.conorrr:libsql-java` dependency.
