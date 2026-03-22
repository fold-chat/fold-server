use crate::servers::{self, ServerEntry, ServerInfo};
use std::path::PathBuf;
use tauri::Manager;
use tauri::menu::ContextMenu;
use tauri::webview::WebviewBuilder;

const SIDEBAR_WIDTH: f64 = 72.0;

/// Derive a stable 16-byte data store identifier from a server UUID.
/// Used for WKWebView storage isolation on macOS.
fn server_data_store_id(server_id: &str) -> [u8; 16] {
    uuid::Uuid::parse_str(server_id)
        .map(|u| *u.as_bytes())
        .unwrap_or([0u8; 16])
}

/// Per-server data directory for webview storage isolation (Windows/Linux).
pub fn server_data_dir(app: &tauri::AppHandle, server_id: &str) -> PathBuf {
    app.path()
        .app_data_dir()
        .expect("no app data dir")
        .join("servers")
        .join(server_id)
}

/// Determine the webview URL for a server. Checks bundle cache first,
/// falls back to loading directly from the server.
pub fn resolve_webview_url(app: &tauri::AppHandle, server: &ServerEntry) -> tauri::WebviewUrl {
    if let Some(version) = &server.last_version {
        if crate::bundle_cache::is_cached(app, version) {
            let url = format!("fold-client://{}/index.html", version);
            log::info!("Using cached client bundle v{} for {}", version, server.name);
            return tauri::WebviewUrl::External(url.parse().unwrap());
        }
        log::debug!("No cached bundle for v{}, loading from server", version);
    }
    tauri::WebviewUrl::External(server.url.parse().unwrap())
}

/// Enable fullscreen for embedded iframes (YouTube, etc.) on macOS.
/// WKWebView disables element fullscreen by default.
#[cfg(target_os = "macos")]
pub fn enable_element_fullscreen(webview: &tauri::Webview) {
    let _ = webview.with_webview(|wv| {
        unsafe {
            use objc2::msg_send;
            use objc2::rc::Retained;
            use objc2::runtime::{AnyObject, Bool};

            let inner = &*(wv.inner() as *const AnyObject);
            let config: Retained<AnyObject> = msg_send![inner, configuration];
            let prefs: Retained<AnyObject> = msg_send![&*config, preferences];
            let _: () = msg_send![&*prefs, setElementFullscreenEnabled: Bool::YES];
        }
    });
}

/// Injected before page load (currently a no-op, kept as extension point).
pub const BG_INIT_SCRIPT: &str = "";

/// Decide whether a navigation should stay in the webview or open in default browser.
pub fn should_allow_navigation(url: &str, server_origin: &str) -> bool {
    // Cached client bundles served via custom protocol
    if url.starts_with("fold-client://") {
        return true;
    }
    // Same-origin, internal schemes, embeds
    if url.starts_with(server_origin)
        || url.starts_with("about:")
        || url.starts_with("blob:")
        || url.starts_with("data:")
    {
        return true;
    }

    // Allow embed-specific URL patterns (iframes), not full domains
    const EMBED_PREFIXES: &[&str] = &[
        "https://www.youtube.com/embed/",
        "https://youtube.com/embed/",
        "https://www.youtube-nocookie.com/embed/",
        "https://player.vimeo.com/video/",
        "https://open.spotify.com/embed/",
        "https://w.soundcloud.com/player/",
        "https://media.tenor.com/",
        "https://media.giphy.com/",
        "https://giphy.com/embed/",
    ];
    for prefix in EMBED_PREFIXES {
        if url.starts_with(prefix) {
            return true;
        }
    }
    // Also allow LiveKit domains entirely (voice/video infrastructure)
    if let Ok(parsed) = url::Url::parse(url) {
        if let Some(host) = parsed.host_str() {
            if host.ends_with("livekit.io") || host.ends_with("livekit.cloud") {
                return true;
            }
        }
    }

    // Everything else → open in default browser
    log::info!("Opening external URL in browser: {}", url);
    let _ = open::that(url);
    false
}

fn webview_label(server_id: &str) -> String {
    format!("sv-{}", server_id)
}

fn get_content_size(window: &tauri::Window) -> Result<(f64, f64), String> {
    let scale = window.scale_factor().unwrap_or(1.0);
    let size = window.inner_size().map_err(|e| format!("{}", e))?;
    let w = size.width as f64 / scale;
    let h = size.height as f64 / scale;
    Ok((w, h))
}

/// Hide a webview by zeroing its size. No position change = no recomposite flash.
fn hide_webview(wv: &tauri::Webview) {
    let _ = wv.set_size(tauri::LogicalSize::new(0.0, 0.0));
}

/// Show a webview in the content area.
fn show_webview(wv: &tauri::Webview, w: f64, h: f64) {
    let _ = wv.set_position(tauri::LogicalPosition::new(SIDEBAR_WIDTH, 0.0));
    let _ = wv.set_size(tauri::LogicalSize::new(w - SIDEBAR_WIDTH, h));
}

#[tauri::command]
pub async fn list_servers(app: tauri::AppHandle) -> Vec<ServerEntry> {
    servers::load_servers(&app)
}

#[tauri::command]
pub async fn add_server(app: tauri::AppHandle, url: String) -> Result<ServerEntry, String> {
    let url = url.trim_end_matches('/').to_string();
    log::info!("[cmd] add_server: url={}", url);
    let info = servers::fetch_server_info(&url).await?;
    log::info!("[cmd] add_server: got info version={} name={:?}", info.version, info.name);

    let entry = ServerEntry {
        id: uuid::Uuid::now_v7().to_string(),
        url: url.clone(),
        name: info.name.unwrap_or_else(|| "Fold Server".to_string()),
        icon_url: info.icon_url,
        last_version: Some(info.version),
    };

    servers::add_server(&app, entry.clone())?;
    log::info!("[cmd] add_server: done, id={}", entry.id);
    Ok(entry)
}

#[tauri::command]
pub async fn remove_server(app: tauri::AppHandle, id: String) -> Result<(), String> {
    log::info!("[cmd] remove_server: id={}", id);
    let label = webview_label(&id);
    if let Some(wv) = app.get_webview(&label) {
        log::info!("[cmd] remove_server: closing webview {}", label);
        let _ = wv.close();
    }
    servers::remove_server(&app, &id);
    Ok(())
}

#[tauri::command]
pub async fn fetch_server_info(url: String) -> Result<ServerInfo, String> {
    let url = url.trim_end_matches('/').to_string();
    servers::fetch_server_info(&url).await
}

/// Switch to a server. Creates webview on first visit, shows existing on revisit.
/// All other server webviews are hidden (not destroyed) to keep WS alive.
#[tauri::command]
pub async fn open_server_in_content(
    app: tauri::AppHandle,
    webview: tauri::Webview,
    id: String,
) -> Result<(), String> {
    log::info!("[cmd] open_server_in_content: id={}", id);
    let window = webview.window();
    let (w, h) = get_content_size(&window)?;
    log::debug!("[cmd] open_server_in_content: window size {}x{}", w, h);
    let label = webview_label(&id);
    let servers = servers::load_servers(&app);

    // Show the target webview FIRST (or create it), then hide others — prevents flicker
    if let Some(wv) = app.get_webview(&label) {
        log::info!("[cmd] open_server_in_content: showing existing webview {}", label);
        show_webview(&wv, w, h);
    } else {
        // First visit — create webview at the correct position immediately
        log::info!("[cmd] open_server_in_content: creating new webview {}", label);
        let server = servers.iter().find(|s| s.id == id)
            .ok_or_else(|| {
                let ids: Vec<_> = servers.iter().map(|s| s.id.as_str()).collect();
                let msg = format!("Server '{}' not found in store. Available: {:?}", id, ids);
                log::error!("{}", msg);
                msg
            })?;
        let server_url = server.url.clone();
        let webview_url = resolve_webview_url(&app, server);
        log::info!("[cmd] open_server_in_content: loading in webview {}", label);

        let origin = {
            let parsed: url::Url = server_url.parse().unwrap();
            format!("{}://{}", parsed.scheme(), parsed.host_str().unwrap_or(""))
        };
        let new_wv = window.add_child(
            WebviewBuilder::new(&label, webview_url)
                .initialization_script(BG_INIT_SCRIPT)
                .data_store_identifier(server_data_store_id(&id))
                .data_directory(server_data_dir(&app, &id))
                .on_navigation(move |url| {
                    should_allow_navigation(url.as_str(), &origin)
                }),
            tauri::LogicalPosition::new(SIDEBAR_WIDTH, 0.0),
            tauri::LogicalSize::new(w - SIDEBAR_WIDTH, h),
        )
        .map_err(|e| {
            log::error!("[cmd] open_server_in_content: add_child failed: {}", e);
            format!("Webview failed: {}", e)
        })?;
        #[cfg(target_os = "macos")]
        enable_element_fullscreen(&new_wv);
        // Update version in background (non-blocking)
        let app2 = app.clone();
        let id2 = id.clone();
        let url2 = server_url.clone();
        tauri::async_runtime::spawn(async move {
            if let Ok(info) = servers::fetch_server_info(&url2).await {
                servers::update_server_version(&app2, &id2, &info.version, info.name.as_deref());
            }
        });

        log::info!("[cmd] open_server_in_content: webview {} created", label);
    }

    // Now hide all other server webviews
    for s in &servers {
        let other_label = webview_label(&s.id);
        if other_label != label {
            if let Some(wv) = app.get_webview(&other_label) {
                log::debug!("[cmd] hiding webview {}", other_label);
                hide_webview(&wv);
            }
        }
    }

    Ok(())
}

/// Show a native OS context menu for a server.
#[tauri::command]
pub async fn show_server_context_menu(
    app: tauri::AppHandle,
    webview: tauri::Webview,
    id: String,
) -> Result<(), String> {
    use tauri::menu::{MenuBuilder, MenuItemBuilder, PredefinedMenuItem};

    log::info!("[cmd] show_server_context_menu: id={}", id);

    let refresh_item = MenuItemBuilder::new("Refresh")
        .id(format!("ctx-refresh-{}", id))
        .build(&app)
        .map_err(|e| format!("Menu item failed: {}", e))?;

    let separator = PredefinedMenuItem::separator(&app)
        .map_err(|e| format!("Separator failed: {}", e))?;

    let remove_item = MenuItemBuilder::new("Remove Server")
        .id(format!("ctx-remove-{}", id))
        .build(&app)
        .map_err(|e| format!("Menu item failed: {}", e))?;

    let menu = MenuBuilder::new(&app)
        .items(&[&refresh_item, &separator, &remove_item])
        .build()
        .map_err(|e| format!("Menu build failed: {}", e))?;

    let window = webview.window();
    menu.popup(window)
        .map_err(|e| format!("Popup failed: {}", e))?;

    Ok(())
}

/// Reorder servers by providing the full list of IDs in desired order.
#[tauri::command]
pub async fn reorder_servers(app: tauri::AppHandle, ids: Vec<String>) -> Result<(), String> {
    log::info!("[cmd] reorder_servers: {:?}", ids);
    let servers = servers::load_servers(&app);
    let mut reordered = Vec::with_capacity(ids.len());
    for id in &ids {
        if let Some(s) = servers.iter().find(|s| s.id == *id) {
            reordered.push(s.clone());
        }
    }
    // Append any servers not in the ID list (shouldn't happen, but safe)
    for s in &servers {
        if !ids.contains(&s.id) {
            reordered.push(s.clone());
        }
    }
    servers::save_servers(&app, &reordered);
    Ok(())
}

/// Refresh a server: re-fetch info, update store, reload its webview.
#[tauri::command]
pub async fn refresh_server(app: tauri::AppHandle, id: String) -> Result<ServerEntry, String> {
    log::info!("[cmd] refresh_server: id={}", id);
    let servers = servers::load_servers(&app);
    let server = servers.iter().find(|s| s.id == id)
        .ok_or_else(|| format!("Server '{}' not found", id))?;
    let server_url = server.url.clone();

    let info = servers::fetch_server_info(&server_url).await?;
    servers::update_server_version(&app, &id, &info.version, info.name.as_deref());

    // Update icon_url
    let icon_url = info.icon_url.clone();
    {
        let mut servers = servers::load_servers(&app);
        if let Some(s) = servers.iter_mut().find(|s| s.id == id) {
            s.icon_url = icon_url;
        }
        servers::save_servers(&app, &servers);
    }

    // Reload the webview if it exists
    let label = webview_label(&id);
    if let Some(wv) = app.get_webview(&label) {
        log::info!("[cmd] refresh_server: reloading webview {}", label);
        let url: tauri::Url = server_url.parse().unwrap();
        let _ = wv.navigate(url);
    }

    // Return updated entry
    let servers = servers::load_servers(&app);
    servers.into_iter().find(|s| s.id == id)
        .ok_or_else(|| "Server disappeared after refresh".to_string())
}

/// Navigate a server's webview to a specific URL.
#[tauri::command]
pub async fn navigate_server(
    app: tauri::AppHandle,
    id: String,
    url: String,
) -> Result<(), String> {
    log::info!("[cmd] navigate_server: id={} url={}", id, url);
    let label = webview_label(&id);
    let wv = app.get_webview(&label)
        .ok_or_else(|| format!("No webview for server '{}'", id))?;
    let parsed: tauri::Url = url.parse()
        .map_err(|e: url::ParseError| format!("Invalid URL: {}", e))?;
    let _ = wv.navigate(parsed);
    Ok(())
}

/// Set theme across all server webviews by injecting localStorage values + sync event.
#[tauri::command]
pub async fn set_global_theme(
    app: tauri::AppHandle,
    theme_pref: String,
    custom_themes_json: String,
) -> Result<(), String> {
    log::info!("[cmd] set_global_theme: pref={}", theme_pref);
    let servers = servers::load_servers(&app);
    let script = format!(
        r#"localStorage.setItem('fold_theme',{});localStorage.setItem('fold_custom_themes',{});if(window.__foldSyncTheme)window.__foldSyncTheme();"#,
        serde_json::to_string(&theme_pref).unwrap_or_default(),
        serde_json::to_string(&custom_themes_json).unwrap_or_default(),
    );
    for s in &servers {
        let label = webview_label(&s.id);
        if let Some(wv) = app.get_webview(&label) {
            let _ = wv.eval(&script);
        }
    }
    Ok(())
}

/// Hide all server webviews (returning to the add-server form).
#[tauri::command]
pub async fn close_server_webview(app: tauri::AppHandle) -> Result<(), String> {
    log::info!("[cmd] close_server_webview: hiding all server webviews");
    let servers = servers::load_servers(&app);
    for s in &servers {
        let label = webview_label(&s.id);
        if let Some(wv) = app.get_webview(&label) {
            log::debug!("[cmd] close_server_webview: hiding {}", label);
            hide_webview(&wv);
        }
    }
    Ok(())
}
