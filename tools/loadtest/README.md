# Load Test Harness

Simulates hundreds of concurrent users against a running Fold instance — WebSocket connections, messaging, reactions, typing indicators, read state updates, and token refreshes.

## Quick Start

```bash
cd tools/loadtest
pnpm install
```

### 1. Seed users

**Preferred:** use the `fold-db` MCP tool (bypasses rate limits):

```
seed_users(500, "password123")
```

**Fallback:** seed via the REST API (slow, hits registration rate limits):

```bash
pnpm seed --baseUrl=http://localhost:8080 --users=500 --password=password123 --serverPassword=YOUR_SERVER_PASSWORD
```

### 2. Run the test

```bash
pnpm start --skipSeed=true
```

Or with a YAML profile:

```bash
pnpm start --profile=profiles/default.yaml --skipSeed=true
```

Press `Ctrl+C` at any time — the harness will generate a final report before exiting.

## Configuration

All options can be set via CLI flags (`--key=value`) or in a YAML profile.

| Option | Default | Description |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | Target Fold server |
| `users` | `500` | Number of simulated users |
| `rampUpSeconds` | `600` | Time to stagger user connections (10 min) |
| `durationSeconds` | `14400` | Test duration after ramp-up (4 hours) |
| `messageIntervalMs` | `5000` | Base message interval (overridden by persona) |
| `skipSeed` | `false` | Skip user provisioning, assume users exist |
| `password` | `password123` | Shared password for all test users |
| `serverPassword` | `""` | Server join password (for API registration fallback) |
| `personas` | `{"active":15,"normal":55,"lurker":25,"spammer":5}` | Persona weight percentages (must sum to 100) |

CLI flags take precedence over YAML profiles.

## User Personas

Each simulated user is assigned a persona that determines their behaviour:

- **Active chatter** — messages every 5-15s, types in multiple channels, reacts to ~30% of messages
- **Normal user** — messages every 30-90s, sticks to 2-3 channels, occasional reactions
- **Lurker** — never messages, just holds a WebSocket connection, receives events, sends heartbeats
- **Spammer** — messages every ~1s, tests rate limit backpressure

The persona distribution is configurable. For example, to test with mostly lurkers:

```bash
pnpm start --skipSeed=true --personas='{"active":5,"normal":10,"lurker":80,"spammer":5}'
```

## What It Does

Each simulated user:

1. **Logs in** via `POST /api/v0/auth/login`
2. **Connects WebSocket** at `/api/ws`, sends `IDENTIFY`, receives `HELLO` with channel list
3. **Sends heartbeats** every 30s
4. **Posts messages** to random channels at persona-defined intervals
5. **Sends typing indicators** before messages (active + normal personas)
6. **Reacts** to received messages with random emoji
7. **Updates read state** periodically
8. **Switches channels** and fetches message history
9. **Refreshes tokens** before the 15min access token expiry (jittered to avoid thundering herd)
10. **Simulates disconnects** and reconnects using the RESUME protocol

## Preflight Checks

Before starting, the harness verifies:

- **File descriptor limit** — must be ≥ `users * 2 + 100`. If too low, run `ulimit -n 10240`.
- **Server reachable** — `GET /api/v0/info` must return 200.

## Output

### Live (stdout)

Every 5 seconds during the test:

```
[00:05:30] conn=487 sent=12340 recv=6170000 429s=23 err=2 reconn=5 refresh=487 p50=45ms p95=120ms p99=350ms
```

### Final Report (stdout + files)

- `report.json` — full metrics (latency percentiles, throughput, error counts)
- `report.csv` — time-series of 5s snapshots for graphing

## YAML Profiles

Create profiles in `profiles/` for different scenarios:

```yaml
# profiles/spike.yaml
users: 200
rampUpSeconds: 10
durationSeconds: 300
personas:
  active: 40
  normal: 40
  lurker: 10
  spammer: 10
```

```bash
pnpm start --profile=profiles/spike.yaml --skipSeed=true
```

## File Structure

```
tools/loadtest/
  src/
    index.ts       — entry point, orchestrator, preflight checks
    config.ts      — config types, defaults, CLI/YAML parsing
    client.ts      — single simulated user (HTTP + WebSocket)
    personas.ts    — persona definitions and behaviour parameters
    metrics.ts     — stats collection, live reporting, JSON/CSV export
    preseed.ts     — bulk user registration (API fallback)
  profiles/
    default.yaml   — default test profile
```
