# Fold

Open-source, self-hostable community tool for small, privacy-conscious communities.

## Implementation Status

### Implemented
- **Auth** — JWT + cookie-based sessions, Argon2id passwords, lockout protection
- **Messaging** — CRUD with UUIDv7 time-sorted IDs, real-time updates
- **Channels & Categories** — Organize, reorder, rename, delete
- **File Uploads** — Content-addressed storage
- **Invites** — Join via invite codes
- **User Profiles** — Profile settings
- **Rate Limiting** — Token-bucket based, configurable
- **WebSocket** — Real-time events, heartbeat, typing indicators, auto-reconnect
- **First-run Setup** — Initial server configuration
- **Read State Tracking** — Per-channel/thread read status with mention counts
- **Permissions** — Bitmask RBAC, channel-level overrides, management UI
- **Threads** — Chat threads + forum-style thread channels with tags
- **Search** — Full-text message search (FTS5)
- **Reactions** — Emoji reactions with picker
- **Mentions** — @user/@role with unread mention counts
- **Banning** — User bans + IP bans
- **Audit Log** — Action logging with admin viewer
- **Server Settings** — Name, icon, description management
- **GIF Search** — Media proxy with GIF picker
- **Themes** — Dark/light mode
- **Keyboard Shortcuts** — Navigable via shortcuts
- **Notifications** — Desktop notification support
- **Roles** — Full CRUD, assignment, 4 seeded defaults (owner/admin/moderator/member)

### Planned
- **Voice & video** — LiveKit integration
- **Direct messages** — 1:1 messaging
- **Presence** — Online/offline/idle status

## Tech Stack

- **Server:** Quarkus (Java), GraalVM native image support
- **Client:** SvelteKit (TypeScript)
- **Database:** Turso / libSQL (embedded or managed)
- **Voice/Video:** LiveKit
- **Build:** Gradle 9.1+ (server), Vite (client)

## Project Structure

```
fold-server/
├── server/          # Quarkus REST API + WebSocket
└── client/          # SvelteKit web client
```

## Getting Started

### Prerequisites

- Java 25+
- Node.js 24+
- pnpm

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

Set env vars prefixed with `FOLD_` or use config file. See docs for details.

## License

[AGPL-3.0](LICENSE)
