use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::menu::{MenuBuilder, MenuItemBuilder, PredefinedMenuItem};
use tauri::Manager;

/// Build and attach the system tray icon. Call once during app setup.
pub fn setup_tray(app: &tauri::App) -> Result<(), Box<dyn std::error::Error>> {
    let quit = MenuItemBuilder::new("Quit Fold")
        .id("tray-quit")
        .build(app)?;
    let show = MenuItemBuilder::new("Show Window")
        .id("tray-show")
        .build(app)?;
    let sep = PredefinedMenuItem::separator(app)?;

    let menu = MenuBuilder::new(app)
        .items(&[&show, &sep, &quit])
        .build()?;

    let _tray = TrayIconBuilder::with_id("fold-tray")
        .icon(app.default_window_icon().cloned().expect("no app icon"))
        .menu(&menu)
        .tooltip("Fold")
        .on_menu_event(move |app: &tauri::AppHandle, event| {
            let id = event.id().0.as_str();
            match id {
                "tray-quit" => {
                    app.exit(0);
                }
                "tray-show" => {
                    show_main_window(app);
                }
                _ => {}
            }
        })
        .on_tray_icon_event(|tray: &tauri::tray::TrayIcon, event| {
            // Left-click on tray icon: show/focus main window
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        })
        .build(app)?;

    Ok(())
}

fn show_main_window(app: &tauri::AppHandle) {
    #[cfg(target_os = "macos")]
    set_activation_policy_regular();
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

/// macOS: switch to accessory policy (hides from dock + Cmd+Tab).
#[cfg(target_os = "macos")]
pub fn set_activation_policy_accessory() {
    use objc2_app_kit::{NSApplication, NSApplicationActivationPolicy};
    use objc2::MainThreadMarker;
    if let Some(mtm) = MainThreadMarker::new() {
        let app = NSApplication::sharedApplication(mtm);
        app.setActivationPolicy(NSApplicationActivationPolicy::Accessory);
    }
}

/// macOS: switch back to regular policy (visible in dock + Cmd+Tab).
#[cfg(target_os = "macos")]
pub fn set_activation_policy_regular() {
    use objc2_app_kit::{NSApplication, NSApplicationActivationPolicy};
    use objc2::MainThreadMarker;
    if let Some(mtm) = MainThreadMarker::new() {
        let app = NSApplication::sharedApplication(mtm);
        app.setActivationPolicy(NSApplicationActivationPolicy::Regular);
        #[allow(deprecated)]
        app.activateIgnoringOtherApps(true);
    }
}

/// Update the tray tooltip with unread count. Called from JS via IPC.
#[tauri::command]
pub fn update_tray_badge(app: tauri::AppHandle, count: u32) {
    // Update tray tooltip
    if let Some(tray) = app.tray_by_id("fold-tray") {
        let tooltip = if count > 0 {
            format!("Fold ({} unread)", count)
        } else {
            "Fold".to_string()
        };
        let _ = tray.set_tooltip(Some(&tooltip));
    }

    // macOS: dock badge
    #[cfg(target_os = "macos")]
    {
        set_dock_badge(count);
    }

    // Windows: taskbar flash when new unreads appear
    #[cfg(target_os = "windows")]
    {
        if count > 0 {
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.request_user_attention(Some(tauri::UserAttentionType::Informational));
            }
        }
    }
}

#[cfg(target_os = "macos")]
fn set_dock_badge(count: u32) {
    unsafe {
        use objc2::msg_send;
        use objc2::runtime::AnyObject;
        use objc2::rc::Retained;

        let app: Retained<AnyObject> = msg_send![
            objc2::class!(NSApplication),
            sharedApplication
        ];
        let dock_tile: Retained<AnyObject> = msg_send![&*app, dockTile];
        if count > 0 {
            let badge_str = objc2_foundation::NSString::from_str(&count.to_string());
            let _: () = msg_send![&*dock_tile, setBadgeLabel: &*badge_str];
        } else {
            let nil: *const AnyObject = std::ptr::null();
            let _: () = msg_send![&*dock_tile, setBadgeLabel: nil];
        }
    }
}
