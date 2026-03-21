use std::path::PathBuf;
use tauri::Manager;

/// Base directory for cached client bundles: ~/.fold/clients/<version>/
fn cache_base(app: &tauri::AppHandle) -> PathBuf {
    app.path()
        .app_data_dir()
        .expect("no app data dir")
        .join("clients")
}

/// Path for a specific version's cached client bundle.
pub fn bundle_path(app: &tauri::AppHandle, version: &str) -> PathBuf {
    cache_base(app).join(version)
}

/// Check if a client bundle for the given version is already cached.
pub fn is_cached(app: &tauri::AppHandle, version: &str) -> bool {
    let path = bundle_path(app, version);
    // Check for index.html as a simple existence marker
    path.join("index.html").exists()
}

/// Download and extract a client bundle tarball (.tar.gz) for the given version.
///
/// For local dev, `cdn_base` can be a file:// URL or a local HTTP server.
/// Production: something like https://releases.fold.chat/client/
pub async fn download_bundle(
    app: &tauri::AppHandle,
    version: &str,
    cdn_base: &str,
) -> Result<PathBuf, String> {
    let dest = bundle_path(app, version);
    if dest.join("index.html").exists() {
        return Ok(dest);
    }

    let url = format!(
        "{}/{}.tar.gz",
        cdn_base.trim_end_matches('/'),
        version
    );

    log::info!("Downloading client bundle: {}", url);

    let resp = reqwest::get(&url)
        .await
        .map_err(|e| format!("Download failed: {}", e))?;

    if !resp.status().is_success() {
        return Err(format!("CDN returned {}", resp.status()));
    }

    let bytes = resp
        .bytes()
        .await
        .map_err(|e| format!("Failed to read body: {}", e))?;

    // Create destination directory
    tokio::fs::create_dir_all(&dest)
        .await
        .map_err(|e| format!("Failed to create dir: {}", e))?;

    // Extract tar.gz
    let dest_clone = dest.clone();
    tokio::task::spawn_blocking(move || {
        let decoder = flate2::read::GzDecoder::new(&bytes[..]);
        let mut archive = tar::Archive::new(decoder);
        archive
            .unpack(&dest_clone)
            .map_err(|e| format!("Failed to extract: {}", e))
    })
    .await
    .map_err(|e| format!("Task failed: {}", e))??;

    log::info!("Cached client bundle {} at {}", version, dest.display());
    Ok(dest)
}

/// For local development: copy the local SvelteKit build output into the cache
/// as if it were a downloaded bundle. This avoids needing a CDN during dev.
pub async fn cache_local_build(
    app: &tauri::AppHandle,
    version: &str,
    build_dir: &std::path::Path,
) -> Result<PathBuf, String> {
    let dest = bundle_path(app, version);
    if dest.join("index.html").exists() {
        return Ok(dest);
    }

    if !build_dir.join("index.html").exists() {
        return Err(format!(
            "Local build not found at {}. Run `pnpm build` first.",
            build_dir.display()
        ));
    }

    // Copy recursively
    let src = build_dir.to_path_buf();
    let dst = dest.clone();
    tokio::task::spawn_blocking(move || copy_dir_recursive(&src, &dst))
        .await
        .map_err(|e| format!("Task failed: {}", e))??;

    log::info!("Cached local build as version {} at {}", version, dest.display());
    Ok(dest)
}

/// Map file extension to MIME type for the custom protocol handler.
pub fn mime_for_path(path: &std::path::Path) -> &'static str {
    match path.extension().and_then(|e| e.to_str()) {
        Some("html") => "text/html",
        Some("js" | "mjs") => "application/javascript",
        Some("css") => "text/css",
        Some("json") => "application/json",
        Some("svg") => "image/svg+xml",
        Some("png") => "image/png",
        Some("jpg" | "jpeg") => "image/jpeg",
        Some("gif") => "image/gif",
        Some("webp") => "image/webp",
        Some("woff") => "font/woff",
        Some("woff2") => "font/woff2",
        Some("ico") => "image/x-icon",
        Some("wasm") => "application/wasm",
        _ => "application/octet-stream",
    }
}

/// Evict cached client bundles no longer referenced by any server entry.
pub fn evict_stale_bundles(app: &tauri::AppHandle) {
    let servers = crate::servers::load_servers(app);
    let referenced: std::collections::HashSet<String> = servers
        .iter()
        .filter_map(|s| s.last_version.clone())
        .collect();

    let base = cache_base(app);
    if !base.exists() {
        return;
    }

    let entries = match std::fs::read_dir(&base) {
        Ok(e) => e,
        Err(_) => return,
    };

    for entry in entries.flatten() {
        if let Some(name) = entry.file_name().to_str() {
            if !referenced.contains(name) {
                log::info!("Evicting stale client bundle: {}", name);
                let _ = std::fs::remove_dir_all(entry.path());
            }
        }
    }
}

fn copy_dir_recursive(src: &std::path::Path, dst: &std::path::Path) -> Result<(), String> {
    std::fs::create_dir_all(dst).map_err(|e| format!("mkdir: {}", e))?;
    for entry in std::fs::read_dir(src).map_err(|e| format!("readdir: {}", e))? {
        let entry = entry.map_err(|e| format!("entry: {}", e))?;
        let ty = entry.file_type().map_err(|e| format!("filetype: {}", e))?;
        let dest_path = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_recursive(&entry.path(), &dest_path)?;
        } else {
            std::fs::copy(entry.path(), &dest_path)
                .map_err(|e| format!("copy: {}", e))?;
        }
    }
    Ok(())
}
