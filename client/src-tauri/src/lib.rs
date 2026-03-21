mod bundle_cache;
mod commands;
mod servers;
mod tray;

use tauri::Manager;
use tauri::Emitter;
use tauri::WebviewWindowBuilder;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(
            tauri_plugin_log::Builder::default()
                .level(log::LevelFilter::Debug)
                .build(),
        )
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            // Focus the main window if a second instance is launched
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.set_focus();
            }
        }))
        .plugin(tauri_plugin_store::Builder::default().build())
        .register_uri_scheme_protocol("fold-client", |ctx, request| {
            let app = ctx.app_handle();
            let uri = request.uri().to_string();

            if let Ok(parsed) = url::Url::parse(&uri) {
                let version = parsed.host_str().unwrap_or("unknown");
                let path = parsed.path().trim_start_matches('/');
                let path = if path.is_empty() { "index.html" } else { path };

                let cache_dir = crate::bundle_cache::bundle_path(app, version);
                let file = cache_dir.join(path);

                if let Ok(content) = std::fs::read(&file) {
                    let mime = crate::bundle_cache::mime_for_path(&file);
                    return tauri::http::Response::builder()
                        .header("content-type", mime)
                        .body(content)
                        .unwrap();
                }
            }

            tauri::http::Response::builder()
                .status(404)
                .body(b"Not Found".to_vec())
                .unwrap()
        })
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .invoke_handler(tauri::generate_handler![
            commands::list_servers,
            commands::add_server,
            commands::remove_server,
            commands::fetch_server_info,
            commands::open_server_in_content,
            commands::close_server_webview,
            commands::reorder_servers,
            commands::refresh_server,
            commands::show_server_context_menu,
            tray::update_tray_badge,
        ])
        .setup(|app| {
            let handle = app.handle().clone();

            // Picker is served from frontendDist (./picker) via Tauri's asset protocol
            let _window = WebviewWindowBuilder::new(
                &handle,
                "main",
                tauri::WebviewUrl::App("index.html".into()),
            )
            .title("Fold")
            .inner_size(900.0, 600.0)
            .min_inner_size(400.0, 300.0)
            .build()?;

            // System tray
            tray::setup_tray(app)?;

            // Deep link: register fold:// protocol
            #[cfg(desktop)]
            {
                use tauri_plugin_deep_link::DeepLinkExt;
                let _ = app.deep_link().register("fold");
            }

            // Global shortcuts (PTT + mute toggle)
            #[cfg(desktop)]
            {
                use tauri_plugin_global_shortcut::ShortcutState;
                let handle_gs = handle.clone();
                app.handle().plugin(
                    tauri_plugin_global_shortcut::Builder::new()
                        .with_handler(move |_app, shortcut, event| {
                            let key = format!("{:?}", shortcut);
                            let state = match event.state() {
                                ShortcutState::Pressed => "pressed",
                                ShortcutState::Released => "released",
                            };
                            let _ = handle_gs.emit("global-shortcut", serde_json::json!({
                                "shortcut": key,
                                "state": state,
                            }));
                        })
                        .build(),
                )?;
            }

            // Handle native context menu events
            let handle_menu = handle.clone();
            _window.on_menu_event(move |_window, event| {
                let id_str = event.id().0.as_str();
                if let Some(server_id) = id_str.strip_prefix("ctx-refresh-") {
                    let h = handle_menu.clone();
                    let sid = server_id.to_string();
                    tauri::async_runtime::spawn(async move {
                        let servers = crate::servers::load_servers(&h);
                        if let Some(s) = servers.iter().find(|s| s.id == sid) {
                            let url = s.url.clone();
                            if let Ok(info) = crate::servers::fetch_server_info(&url).await {
                                crate::servers::update_server_version(&h, &sid, &info.version, info.name.as_deref());
                                // Update icon
                                let mut servers = crate::servers::load_servers(&h);
                                if let Some(s) = servers.iter_mut().find(|s| s.id == sid) {
                                    s.icon_url = info.icon_url;
                                }
                                crate::servers::save_servers(&h, &servers);
                            }
                            // Reload webview
                            let label = format!("sv-{}", sid);
                            if let Some(wv) = h.get_webview(&label) {
                                let _ = wv.navigate(url.parse().unwrap());
                            }
                        }
                        // Notify picker to re-render sidebar
                        let _ = h.emit("servers-changed", ());
                    });
                } else if let Some(server_id) = id_str.strip_prefix("ctx-remove-") {
                    let h = handle_menu.clone();
                    let sid = server_id.to_string();
                    let label = format!("sv-{}", sid);
                    if let Some(wv) = h.get_webview(&label) {
                        let _ = wv.close();
                    }
                    crate::servers::remove_server(&h, &sid);
                    let _ = h.emit("servers-changed", ());
                }
            });

            // Window event handler: resize server webviews + minimize-to-tray
            let handle2 = handle.clone();
            let window_ref = _window.as_ref().window().clone();
            let window_for_event = window_ref.clone();
            window_ref.on_window_event(move |event| {
                match event {
                    tauri::WindowEvent::Resized(size) => {
                        let scale = window_for_event.scale_factor().unwrap_or(1.0);
                        let w = size.width as f64 / scale;
                        let h = size.height as f64 / scale;

                        let servers = crate::servers::load_servers(&handle2);
                        for s in &servers {
                            let label = format!("sv-{}", s.id);
                            if let Some(wv) = handle2.get_webview(&label) {
                                if let Ok(size) = wv.size() {
                                    if size.width > 0 {
                                        let _ = wv.set_size(tauri::LogicalSize::new(w - 72.0, h));
                                    }
                                }
                            }
                        }
                    }
                    tauri::WindowEvent::CloseRequested { api, .. } => {
                        // Hide to tray instead of quitting
                        api.prevent_close();
                        if let Some(w) = handle2.get_webview_window("main") {
                            let _ = w.hide();
                        }
                    }
                    _ => {}
                }
            });

            // Pre-create hidden webviews for all saved servers so they load in the background
            let window_for_preload = _window.as_ref().window().clone();
            let servers = servers::load_servers(&handle);
            for s in &servers {
                let label = format!("sv-{}", s.id);
                let webview_url = crate::commands::resolve_webview_url(&handle, s);
                if let Ok(url) = s.url.parse::<tauri::Url>() {
                    let origin = format!("{}://{}", url.scheme(), url.host_str().unwrap_or(""));
                    match window_for_preload.add_child(
                        tauri::webview::WebviewBuilder::new(&label, webview_url)
                            .initialization_script(crate::commands::BG_INIT_SCRIPT)
                            .data_store_identifier(
                                uuid::Uuid::parse_str(&s.id)
                                    .map(|u| *u.as_bytes())
                                    .unwrap_or([0u8; 16]),
                            )
                            .data_directory(crate::commands::server_data_dir(&handle, &s.id))
                            .on_navigation(move |nav_url| {
                                crate::commands::should_allow_navigation(nav_url.as_str(), &origin)
                            }),
                        tauri::LogicalPosition::new(72.0, 0.0),
                        tauri::LogicalSize::new(0.0, 0.0),
                    ) {
                    Ok(wv) => {
                        #[cfg(target_os = "macos")]
                        crate::commands::enable_element_fullscreen(&wv);
                        log::info!("Pre-loaded webview for server {}", s.name);
                    }
                        Err(e) => log::warn!("Failed to pre-load {}: {}", s.name, e),
                    }
                }
            }

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
