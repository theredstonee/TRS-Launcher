mod commands;
mod error;

use std::path::PathBuf;
use std::sync::Arc;

use tauri::Manager;
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
            let launcher = tauri::async_runtime::block_on(Launcher::init(root))?;
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
        ])
        .run(tauri::generate_context!())
        .expect("TRS Launcher konnte nicht gestartet werden");
}
