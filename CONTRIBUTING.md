# Contributing to Fold

Thanks for your interest in contributing! Fold is open source and welcomes contributions of all kinds, bug reports, feature suggestions, documentation improvements, and code.

## Getting Started

1. Check [open issues](https://github.com/fold-chat/fold-server/issues) for something to work on. If you want to introduce a new feature create an issue first and discuss the idea and the approach.
2. Fork the repo and create a branch from `main`.
3. Make your changes and open a pull request against `main`.

## AI Usage

We are open to contiributors using AI. However, make sure that, you fully understand what your code is doing, all code is fully reviewed, and that existing features are not broken.

## Gotchas

The main risk when making changes to the backend quarkus application is that the code no-longer compiles to native executables or it has runtime issues that are not present during development. Quarkus does a good job at catching most of these issues so it's recommended you keep an eye on the logs and look for warnings, and that you test native compilation before raising a PR.

## General Rules

- Favour simpilicity
- Consider performance and resource usage
- Keep external dependencies to a minimum
- Don't rewrite whole features or areas of the system unless you are explicitly working on a pre-approved refactoring item.

## Local Development Setup

### Prerequisites

- Java 25+ (GraalVM recommended for native builds)
- Node.js 24+ with [pnpm](https://pnpm.io/)
- Gradle (wrapper included, no separate install needed)

### Clone and run

```bash
git clone https://github.com/fold-chat/fold-server.git
cd fold-server
```

**Server** (Quarkus, Java):

The server depends on [libsql-java](https://github.com/Conorrr/libsql-java), a Java FFM (Project Panama) wrapper for libSQL. This library is currently published on [GitHub Packages](https://github.com/Conorrr/libsql-java/packages) only, not Maven Central.

Before building, add GitHub Packages as a repository. In `server/build.gradle.kts` (or your local `~/.gradle/gradle.properties`) you will need a GitHub personal access token with `read:packages` scope:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/Conorrr/libsql-java")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
```

This is already present in `server/build.gradle.kts` — you just need to supply the credentials.

Then add to `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PAT
```

```bash
cd server
./gradlew quarkusDev
```
Runs at `http://localhost:8080` with live reload.

**Client** (SvelteKit, TypeScript):
```bash
cd client
pnpm install
pnpm dev
```
Runs at `http://localhost:5173` with HMR. API requests are proxied to the server.

In dev mode, the `Secure` cookie flag is relaxed for localhost and CORS is permissive.

## Testing

- **Server:** `cd server && ./gradlew test` JUnit 5 + Quarkus test framework
- **Client:** `cd client && pnpm test` Vitest for unit tests

Both test suites run on every PR via CI. Please make sure tests pass before submitting.

## Code Style

- **Server (Java):** Standard Quarkus conventions. Java throughout (no Kotlin/Groovy).
- **Client (TypeScript/Svelte):** TypeScript strict mode. Prettier + ESLint.

Follow existing patterns in the codebase. When in doubt, look at how nearby code is structured.

## Building

### Docker image

```bash
docker build -t fold .
```

### Native binary (GraalVM)

```bash
cd server
./gradlew build -Dquarkus.native.enabled=true
```

Produces platform-specific binaries in `server/build/`.

## Project Structure

```
fold-server/
├── server/          # Quarkus REST API + WebSocket (Java)
└── client/          # SvelteKit web client (TypeScript)
```

For deeper architecture details, see the [fold.chat docs](https://fold.chat/contributing/development/):
- [Architecture](https://fold.chat/contributing/architecture/)
- [Server Patterns](https://fold.chat/contributing/server-patterns/)
- [Client Patterns](https://fold.chat/contributing/client-patterns/)
