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
- HELLO populates channels, categories, read_states. `capabilities.voice_mode` string (off/embedded/external/managed).
- `SERVER_CONFIG_UPDATE` event — pushed when admin changes runtime config. Updates `voiceMode` store if `fold.livekit.mode` changed. HELLO `capabilities` delivers voice/video settings live per connection.

## Routing
- Public: `/login`, `/register`, `/setup`, `/invite/[code]`.
- Auth: `/(app)/`, `/(app)/channels/[id]`, `/settings/profile`.

## Desktop (Tauri v2)
Cross-platform desktop app wrapping the web client in an OS-native webview. Lives in `src-tauri/`.

### Why Tauri
- Uses OS native webview (WebKit/macOS, WebView2/Windows, WebKitGTK/Linux) — ~3MB binary vs Electron's ~150MB.
- Existing SvelteKit SPA loads directly in webview — no fork of the web client needed.
- Rust backend for native OS integrations.

### Architecture
Single window, Discord-style layout:
- **Sidebar webview** (72px, left) — server icon bar + add-server form. Served from `src-tauri/picker/index.html` via Tauri's asset protocol.
- **Server webviews** (content area, right) — one per server, pointed at the server's URL. All webviews stay alive (hidden via 0×0 size) so WebSocket connections persist across switches. Switching just resizes the target to fill the content area.
- **Multi-server** — users can add multiple Fold servers. Each gets its own isolated webview with independent cookies/storage. Servers are reorderable via drag-and-drop.
- **External links** — `on_navigation` handler opens non-server, non-embed URLs in the default browser. Embed domains (YouTube `/embed/`, Vimeo, Spotify, etc.) are allowed inline.
- **macOS** — NSWindow background set to `#1a1a2e` via `objc2` to prevent white flash during resize/transitions.

### Structure
```
src-tauri/
  src/
    lib.rs          # app setup, plugin registration, window events
    commands.rs     # Tauri IPC commands (add/remove/switch server, context menu, reorder)
    servers.rs      # server list CRUD, tauri-plugin-store persistence, fetch_server_info HTTP
    bundle_cache.rs # versioned client bundle download + cache + eviction
    tray.rs         # system tray icon, menu, badge updates, dock badge (macOS)
  picker/
    index.html      # server picker UI (sidebar + add-server form)
  tauri.conf.json
  Cargo.toml
```

### Platform Abstraction Layer
`src/lib/platform/` — detects Tauri vs browser at runtime (`window.__TAURI__`). Unified interface so existing code works on both web and desktop:
- `platform/index.ts` — `isDesktop()` helper, re-exports.
- `platform/notifications.ts` — native notifications in Tauri, web `Notification` API in browser.
- `platform/shortcuts.ts` — global shortcuts via `tauri-plugin-global-shortcut`, no-op in browser. Keybind persistence via `tauri-plugin-store`.
- `platform/tray.ts` — badge updates via Tauri IPC, no-op in browser.
- `platform/voice-shortcuts.ts` — connects global shortcuts to voice PTT/mute/deafen.

### Tauri Plugins
- `tauri-plugin-store` — server list + keybind persistence.
- `tauri-plugin-single-instance` — prevent duplicate windows.
- `tauri-plugin-notification` — native OS notifications.
- `tauri-plugin-global-shortcut` — OS-level hotkeys (PTT, mute toggle).
- `tauri-plugin-autostart` — launch on boot.
- `tauri-plugin-updater` — auto-update from GitHub releases.
- `tauri-plugin-deep-link` — `fold://` protocol handler.
- `tauri-plugin-window-state` — persist window position/size.
- `tauri-plugin-process` — required by updater.
- `tauri-plugin-log` — structured logging.

### OS Features
- **System tray** — unread badge tooltip, Show/Quit menu, click to focus. Minimize-to-tray on window close.
- **macOS** — dock badge count via NSApplication/NSDockTile objc2 FFI.
- **Windows** — taskbar flash on unread via `request_user_attention`.
- **Global shortcuts** — push-to-talk works when window unfocused. Configurable in Settings → Keybinds (desktop only).

### IPC Commands
- `list_servers` / `add_server` / `remove_server` — server list CRUD.
- `open_server_in_content` — switch to server (show/create webview).
- `close_server_webview` — hide all server webviews (show add-server form).
- `reorder_servers` — persist new server order.
- `refresh_server` — re-fetch `/api/v0/info`, update icon/name, reload webview.
- `show_server_context_menu` — native OS right-click menu (Refresh / Remove).
- `update_tray_badge` — update tray tooltip + dock badge + taskbar overlay.

### Server Info Endpoint
`GET /api/v0/info` — public, unauthenticated. Returns `{ version, name, icon_url }`. Used by desktop to validate servers on add and fetch metadata. Defined in `server/.../api/InfoResource.java`, added to `AuthFilter.isPublicPath`.

### Prerequisites
- Rust toolchain (`rustc`, `cargo`) — install via [rustup](https://rustup.rs)
- pnpm (for SvelteKit client)
- macOS: Xcode Command Line Tools
- Windows: WebView2 (included in Win10+), MSVC toolchain
- Linux: `webkit2gtk-4.1`, `libappindicator3`, `librsvg2`

### Running
```
# Terminal 1: start Quarkus server
cd server && ./gradlew quarkusDev

# Terminal 2: start desktop app
cd client && pnpm tauri dev
```
The desktop app opens with the server picker. Add `http://localhost:8080` to connect to the local dev server.

### Building
```
cd client && pnpm tauri build
```
Outputs platform-specific installers in `src-tauri/target/release/bundle/` (.dmg, .app on macOS; .msi, .exe on Windows; .AppImage, .deb on Linux).
