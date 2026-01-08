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
