use serde::{Deserialize, Serialize};
use tauri_plugin_store::StoreExt;

const STORE_FILE: &str = "servers.json";
const STORE_KEY: &str = "servers";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerEntry {
    pub id: String,
    pub url: String,
    pub name: String,
    pub icon_url: Option<String>,
    pub last_version: Option<String>,
}

/// Server info returned by GET /api/v0/info
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerInfo {
    pub version: String,
    pub name: Option<String>,
    pub icon_url: Option<String>,
}

fn get_store(app: &tauri::AppHandle) -> std::sync::Arc<tauri_plugin_store::Store<tauri::Wry>> {
    // Always use store_builder to ensure the store is created if it doesn't exist
    app.store_builder(STORE_FILE)
        .build()
        .expect("failed to create/open store")
}

pub fn load_servers(app: &tauri::AppHandle) -> Vec<ServerEntry> {
    let store = get_store(app);
    let raw = store.get(STORE_KEY);
    log::debug!("store.get('{}') = {:?}", STORE_KEY, raw);
    let servers: Vec<ServerEntry> = raw
        .and_then(|v| serde_json::from_value(v).ok())
        .unwrap_or_default();
    log::info!("Loaded {} servers from store", servers.len());
    for s in &servers {
        log::debug!("  server: id={} name={} url={}", s.id, s.name, s.url);
    }
    servers
}

pub fn save_servers(app: &tauri::AppHandle, servers: &[ServerEntry]) {
    log::info!("Saving {} servers to store", servers.len());
    let store = get_store(app);
    store.set(STORE_KEY, serde_json::to_value(servers).unwrap());
    match store.save() {
        Ok(_) => log::info!("Store saved to disk"),
        Err(e) => log::error!("Failed to save store to disk: {}", e),
    }
}

/// Extract host + optional non-default port for duplicate comparison.
/// Ignores scheme so http and https of the same host are treated as one server.
fn server_host_key(raw: &str) -> Option<String> {
    url::Url::parse(raw).ok().and_then(|u| {
        u.host_str().map(|h| match u.port() {
            Some(p) => format!("{}:{}", h, p),
            None => h.to_string(),
        })
    })
}

pub fn add_server(app: &tauri::AppHandle, entry: ServerEntry) -> Result<(), String> {
    log::info!("add_server: id={} url={} name={}", entry.id, entry.url, entry.name);
    let mut servers = load_servers(app);
    let new_key = server_host_key(&entry.url);
    if let Some(existing) = servers.iter().find(|s| {
        new_key.is_some() && server_host_key(&s.url) == new_key
    }) {
        log::warn!("add_server: duplicate host '{}', skipping", entry.url);
        return Err(format!("'{}' is already added", existing.name));
    }
    servers.push(entry);
    save_servers(app, &servers);
    Ok(())
}

pub fn remove_server(app: &tauri::AppHandle, id: &str) {
    let mut servers = load_servers(app);
    servers.retain(|s| s.id != id);
    save_servers(app, &servers);
}

pub fn update_server_version(app: &tauri::AppHandle, id: &str, version: &str, name: Option<&str>) {
    let mut servers = load_servers(app);
    if let Some(s) = servers.iter_mut().find(|s| s.id == id) {
        s.last_version = Some(version.to_string());
        if let Some(n) = name {
            s.name = n.to_string();
        }
    }
    save_servers(app, &servers);
}

/// Fetch server info from a remote Fold server.
pub async fn fetch_server_info(server_url: &str) -> Result<ServerInfo, String> {
    let url = format!("{}/api/v0/info", server_url.trim_end_matches('/'));
    log::info!("Fetching server info: GET {}", url);
    let resp = reqwest::get(&url)
        .await
        .map_err(|e| {
            log::error!("HTTP request to {} failed: {}", url, e);
            format!("Failed to connect to {}: {}", url, e)
        })?;
    let status = resp.status();
    log::info!("Received {} from {}", status, url);
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        log::error!("Non-success response from {}: {} body={}", url, status, body);
        return Err(format!("Server returned {} from {}", status, url));
    }
    let text = resp.text().await.map_err(|e| {
        log::error!("Failed to read response body from {}: {}", url, e);
        format!("Failed to read response: {}", e)
    })?;
    log::debug!("Response body from {}: {}", url, text);
    serde_json::from_str::<ServerInfo>(&text).map_err(|e| {
        log::error!("Failed to parse JSON from {}: {} body={}", url, e, text);
        format!("Invalid JSON from {}: {}", url, e)
    })
}
