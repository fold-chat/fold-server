# Fray

Open-source, self-hostable community tool for small, privacy-conscious communities.

## Features

- **Real-time messaging** — WebSocket-based chat with typing indicators and presence
- **Voice & video** — LiveKit integration for calls
- **Channels & roles** — Organize conversations with fine-grained permissions
- **Message search** — Full-text search with SQLite FTS5
- **Self-hosted** — Single community per instance, no federation
- **Privacy-first** — Own your data, control your platform

## Tech Stack

- **Server:** Quarkus (Java), GraalVM native image support
- **Client:** SvelteKit (TypeScript)
- **Database:** Turso / libSQL (embedded or managed)
- **Voice/Video:** LiveKit
- **Build:** Gradle (server), Vite (client)

## Project Structure

```
fray-app/
├── server/          # Quarkus REST API + WebSocket
└── client/          # SvelteKit web client
```

## Getting Started

### Prerequisites

- Java 17+
- Node.js 18+
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

Set env vars prefixed with `FRAY_` or use config file. See docs for details.

## License

TBD
