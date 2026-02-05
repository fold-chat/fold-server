# Client Conventions
SvelteKit + TypeScript. Full patterns reference: `docs/client-patterns.md`.

## Structure
- `src/lib/api/` — one file per domain, thin typed wrappers around `api<T>()`.
- `src/lib/stores/` — Svelte 5 runes (`.svelte.ts`), one per domain.
- `src/lib/components/` — Svelte components.
- `src/routes/` — `(app)/` group = authenticated, layout checks auth + connects WS.

## API Client
- `api<T>(path, options)` — base `/api/v0`, cookie auth, auto-refresh on 401.
- `apiRaw()` for non-JSON (file uploads).
- Error: `ApiError { error, message?, retry_after? }`.

## State (Svelte 5 Runes)
- `$state` + exported getter/setter functions. No raw state exports.
- Immutable updates: new Map/Array to trigger reactivity.
- No classes, no Svelte 4 stores. Pure functions + runes only.

## WebSocket
- `ws.svelte.ts`: auto-reconnect w/ exponential backoff.
- `handleEvent` dispatches ops to store functions.
- HELLO populates channels, categories, read_states.

## Routing
- Public: `/login`, `/register`, `/setup`, `/invite/[code]`.
- Auth: `/(app)/`, `/(app)/channels/[id]`, `/settings/profile`.
