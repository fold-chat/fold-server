<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="graphics/dark-logo.svg">
    <source media="(prefers-color-scheme: light)" srcset="graphics/light-logo.svg">
    <img alt="Fold" src="graphics/light-logo.svg" width="400">
  </picture>
</p>

<h3 align="center">Modern chat. Simple hosting. Your community.</h3>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-blue" alt="License"></a>
  <img src="https://img.shields.io/badge/status-in%20development-orange" alt="Status">
</p>

---

> [!WARNING]
> **Fold is early-stage software.** It is rough around the edges, has known bugs, and is under active development. There is no guarantee that features work as expected or that stored data will persist between versions, bugs may cause data loss. Whilst great care has been taken in design and implementation, fold has not been battle-tested and makes no guarantees of security. **Do not use fold to store sensitive information or in any context where data loss or a security compromise would be a problem.**

---

## What is Fold?

Fold is an open-source community chat application. It's built for small communities who want a private, self-hosted space to talk.

You install Fold on your own server (or even a spare computer). Your messages, files, and data stay on your machine, no third-party services, no accounts with big tech companies. One server, one community.

<!-- TODO: add screenshot -->

<p align="center">
  <a href="https://fold.chat">Website</a> -
  <a href="https://fold.chat/getting-started/quickstart/">Docs</a> -
  <a href="https://fold.chat/getting-started/quickstart/">Quick Start</a>
</p>

---

## Why Fold?

**Built for small communities, not scale.** Fold is designed for groups. It's a focused space for your people, not a platform chasing millions of users.

**You own everything.** Fold runs on your hardware. Your data stays on your machine, under your control. No dependency on external accounts or services.

**No tracking, no ads, no telemetry.** Zero analytics, zero data collection. Fold doesn't phone home.

**All features included.** Every feature is available to everyone. No paywalls, no premium tiers that lock out functionality.

**Open source.** Audit the code, contribute, or fork it. The full source is here.

**Lightweight** The server requires as little as 70MB of RAM to run and the client is designed to be quick and responsive.

---

## Features

- **Real-time messaging** - instant chat with markdown, syntax highlighting, and link previews
- **Channels & categories** - organise conversations into channels grouped by topic
- **Voice & video** - built-in voice channels with group calls
- **Screen Sharing** - up to 4k 60hz high quality screen sharing as standard
- **Threads** - threaded conversations and forum-style thread channels with tags
- **Roles & permissions** - custom roles with granular permissions and channel-level overrides
- **Search** - full-text search across all messages
- **Invites** - the only way someone can access your community is if they are invited
- **Reactions & emoji** - emoji reactions, plus custom emoji support and 
- **Gifs** - private and powered by KLIPY (Optional)
- **File uploads** - share files, images and videos directly in chat
- **Mentions** - @user and @role mentions with unread counts
- **Themes** - default set of colourful themes and customisability to style the client however you want
- **Keyboard shortcuts** - navigate without touching your mouse

---

## Mixed Hosting (In Development)

Fold is designed to be fully self-hosted, your server, your data, your rules. But voice and video infrastructure is hard to run yourself.

**Mixed hosting** lets you self-host everything (messages, files, user data) while optionally connecting to [fold.chat](https://fold.chat) for managed voice and video. Audio and video traffic is end-to-end encrypted, the hosted infrastructure can't read or record your calls.

Your messages and files never leave your server. The hosted service only handles the real-time media relay.

This isn't a SaaS, it's an optional add-on for the one piece of infrastructure that's genuinely difficult to self-host.

Of course if you would rather have full control the fold comes with an embedded voice and video server that it's easy to enable and confiure through the UI; alternatively you can host and run a separate LiveKit server/cluster and have it work seemlessly with fold.

---

## Database

Fold uses [libSQL](https://github.com/tursodatabase/libsql) as its embedded database, a fork of SQLite with additional capabilities. Rather than using a JNI binding, Fold accesses the native libSQL library via a custom Java wrapper — [libsql-java](https://github.com/Conorrr/libsql-java) — that uses the Java Foreign Function & Memory API (Project Panama / FFM) introduced in Java 22. This avoids JNI entirely and keeps the integration lightweight.

> [!NOTE]
> `libsql-java` is currently published only on GitHub Packages. If you are building from source you will need to add GitHub Packages as a Maven repository. See the [Development](#development) section and [CONTRIBUTING.md](CONTRIBUTING.md) for details.

---

## Roadmap

The current focus is **bug fixes and stability**.

Planned:
- Direct messages (1:1)
- Voice & video stability improvements
- Desktop Applications
- iOS and Android Apps
- Internationalisation

Track progress on [GitHub Issues](https://github.com/fold-chat/fold-server/issues).

---

## Getting Started

Fold runs as a single Docker container or a standalone binary. You'll need a server (a VPS, home server, or even a local machine) with at least 512 MB of RAM.

### Docker (recommended)

```bash
docker pull ghcr.io/fold-chat/fold-server:latest

docker run -d \
  --name fold \
  -p 8080:8080 \
  -v /opt/fold/data:/data \
  -e FOLD_DATA_DIR=/data \
  ghcr.io/fold-chat/fold-server:latest
```

Set up a domain and reverse proxy. See [offical docs](https://fold.chat/getting-started/reverse-proxy/) for more detail.

N.B. fold requires https connections to run.

Open your site and create your admin account and set up the server.

### Standalone binary

Download the binary for your platform from [GitHub Releases](https://github.com/fold-chat/fold-server/releases), make it executable, and run it.

For the full setup guide, including HTTPS, reverse proxies, and voice configuration see the [Quick Start docs](https://fold.chat/getting-started/quickstart/).

---

## Development

For contributors looking to work on Fold locally.

### Prerequisites

- Java 25+
- Node.js 24+ with pnpm
- Gradle (wrapper included)

### Running locally

**Server:**

> [!IMPORTANT]
> The server depends on [libsql-java](https://github.com/Conorrr/libsql-java), which is published on GitHub Packages only. Before running, add GitHub Packages as a repository in your Gradle configuration. See [CONTRIBUTING.md](CONTRIBUTING.md) for setup details.

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

open `http://localhost:5173`

---

## Tools

### [Load Test Harness](tools/loadtest/)

Simulates up to 500+ concurrent users with WebSocket connections, messaging, reactions, and more. Useful for stress-testing the server under realistic load. See [`tools/loadtest/README.md`](tools/loadtest/README.md).

---

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for setup instructions, code style, and how to submit changes.

---

## License

[AGPL-3.0](LICENSE)
