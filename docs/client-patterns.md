# Client Patterns Reference

## Structure
- `src/lib/api/` — API client functions. One file per domain (auth, channels, messages, users, invites, upload).
- `src/lib/stores/` — Svelte 5 runes-based state (`.svelte.ts`). One store per domain (auth, channels, messages, ws).
- `src/lib/components/` — Svelte components (Sidebar, MessageList, MessageCompose).
- `src/lib/utils/` — Utilities (e.g. markdown).
- `src/routes/` — SvelteKit file-based routing. `(app)/` group for authenticated layout.

## API Client
- `api<T>(path, options)` wrapper around fetch. Base path `/api/v0`. Credentials: `same-origin` (cookie auth).
- Auto-refresh: on 401 → call `/auth/refresh` once → retry original request. If refresh fails → redirect to `/login`.
- `apiRaw(path, options)` for non-JSON responses (e.g. file uploads).
- Error type: `ApiError { error: string; message?: string; retry_after?: number }`.
- API functions are thin typed wrappers: `export function getChannels() { return api<Channel[]>('/channels'); }`.

## State (Svelte 5 Runes)
- `$state` for reactive state, exported getter/setter functions (not raw state). Pattern:
  ```
  let foo = $state<T>(initial);
  export function getFoo(): T { return foo; }
  export function setFoo(v: T) { foo = v; }
  ```
- Immutable updates: always create new Map/Array (`new Map(existing)`, `[...existing, item]`) to trigger reactivity.
- No classes or Svelte 4 stores. Pure functions + runes.

## WebSocket Client
- `ws.svelte.ts`: connect/disconnect/send. Auto-reconnect with exponential backoff (max 30s).
- Heartbeat on interval from HELLO payload.
- `handleEvent` switch dispatches ops to store functions (e.g. `MESSAGE_CREATE` → `appendMessage`).
- HELLO populates channels, categories, read_states stores.

## Routing
- `(app)/` route group for authenticated pages (layout checks auth, connects WS).
- Public routes: `/login`, `/register`, `/setup`, `/invite/[code]`.
- Authenticated: `/(app)/` (home), `/(app)/channels/[id]`, `/settings/profile`.
