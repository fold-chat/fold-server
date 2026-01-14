# Fray

Open-source, self-hostable community tool for small, privacy-conscious communities.

## Implementation Status

### Implemented
- **Auth** — JWT + cookie-based sessions, Argon2id passwords, lockout protection
- **Messaging** — CRUD with UUIDv7 time-sorted IDs, real-time updates
- **Channels & Categories** — Organize conversations
- **File Uploads** — Content-addressed storage
- **Invites** — Join via invite codes
- **User Profiles** — Profile settings
- **Rate Limiting** — Token-bucket based, configurable
- **WebSocket** — Real-time events, heartbeat, typing indicators, auto-reconnect
- **First-run Setup** — Initial server configuration
- **Role Seeding** — Owner/admin/moderator/member roles
- **Read State Tracking** — Per-channel read status

### Planned
- **Voice & video** — LiveKit integration
- **Permissions** — Channel-level role overrides
- **Message search** — Full-text search with FTS5
- **Presence** — Online/offline status

## Tech Stack

- **Server:** Quarkus (Java), GraalVM native image support
- **Client:** SvelteKit (TypeScript)
- **Database:** Turso / libSQL (embedded or managed)
- **Voice/Video:** LiveKit
- **Build:** Gradle 9.1+ (server), Vite (client)

## Project Structure

```
fray-app/
├── server/          # Quarkus REST API + WebSocket
└── client/          # SvelteKit web client
```

## Getting Started

### Prerequisites

- Java 25+
- Node.js 24+
- pnpm
- Rust (for libsql wrapper development)

### Running Locally

**Server:**
```bash
cd server
./gradlew quarkusDev
```

**Client:**
```bash
cd client
pnpm install
pnpm dev
```

Client proxies API requests to `localhost:8080`.

## Configuration

Set env vars prefixed with `FRAY_` or use config file. See docs for details.

## License

[AGPL-3.0](LICENSE)
