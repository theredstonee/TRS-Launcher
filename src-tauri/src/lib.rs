mod commands;
mod error;

use std::path::PathBuf;
use std::sync::Arc;

use tauri::{Emitter, Manager};
use trs_core::Launcher;

/// Überschreibt das Datenverzeichnis – praktisch für Entwicklung und Tests.
const HOME_ENV: &str = "TRS_LAUNCHER_HOME";

pub type LauncherState = Arc<Launcher>;

fn data_root(app: &tauri::App) -> Result<PathBuf, Box<dyn std::error::Error>> {
    if let Some(custom) = std::env::var_os(HOME_ENV).filter(|v| !v.is_empty()) {
        return Ok(PathBuf::from(custom));
    }
    Ok(app.path().data_dir()?.join(trs_core::LAUNCHER_NAME))
}

pub fn run() {
    tauri::Builder::default()
        // Muss als erstes Plugin registriert werden.
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .plugin(
            tauri_plugin_log::Builder::new()
                .level(log::LevelFilter::Info)
                .level_for("trs_core", log::LevelFilter::Debug)
                .level_for("trs_launcher_lib", log::LevelFilter::Debug)
                .max_file_size(5_000_000)
                .build(),
        )
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .setup(|app| {
            let root = data_root(app)?;
            log::info!("Datenverzeichnis: {}", root.display());
            // Spielstart, Logs und Spielende gehen als Event ans Frontend.
            let handle = app.handle().clone();
            let events = Arc::new(move |event: trs_core::launch::GameEvent| {
                if let Err(e) = handle.emit("game-event", &event) {
                    log::warn!("game-event konnte nicht gesendet werden: {e}");
                }
            });
            let launcher = tauri::async_runtime::block_on(Launcher::init(root, events))?;
            app.manage::<LauncherState>(Arc::new(launcher));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::app::app_info,
            commands::app::open_data_dir,
            commands::settings::get_settings,
            commands::settings::update_settings,
            commands::instances::list_instances,
            commands::instances::get_instance,
            commands::instances::create_instance,
            commands::instances::update_instance,
            commands::instances::delete_instance,
            commands::instances::open_instance_dir,
            commands::meta::get_version_manifest,
            commands::games::launch_instance,
            commands::games::stop_instance,
            commands::games::running_games,
            commands::games::get_game_logs,
            commands::accounts::list_accounts,
            commands::accounts::login_browser,
            commands::accounts::login_device_code,
            commands::accounts::cancel_login,
            commands::accounts::set_active_account,
            commands::accounts::remove_account,
        ])
        .run(tauri::generate_context!())
        .expect("TRS Launcher konnte nicht gestartet werden");
}
