mod commands;
mod dialog_text;
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
    if let Ok(exe) = std::env::current_exe() {
        trs_core::firewall::set_helper_exe(exe);
    }
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
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
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
            match app.path().resource_dir() {
                Ok(dir) => launcher.set_client_mod_dir(dir.join("client-mod")),
                Err(e) => log::warn!("Ressourcen-Ordner nicht gefunden: {e}"),
            }
            // Clips: Status, gespeicherte Clips und Fehler gehen als Event ans Frontend.
            let handle = app.handle().clone();
            launcher.clips().set_sink(Arc::new(move |event| {
                if let Err(e) = handle.emit("clip-event", &event) {
                    log::warn!("clip-event konnte nicht gesendet werden: {e}");
                }
            }));
            let launcher = Arc::new(launcher);
            // Spiele, die beim letzten Schließen noch liefen, wieder aufnehmen.
            tauri::async_runtime::spawn(Arc::clone(&launcher).resume_clips());
            // Neuer TRS Client im Update-Kanal? Läuft im Hintergrund, offline egal.
            let updates = Arc::clone(&launcher);
            tauri::async_runtime::spawn(async move {
                updates.check_client_mod_updates().await;
            });
            // TRS-Präsenz im 60-s-Takt (ohne Einwilligung passiert nichts).
            tauri::async_runtime::spawn(Arc::clone(&launcher).run_trs_presence());
            app.manage::<LauncherState>(launcher);
            app.manage(commands::system::DropState::default());
            app.manage(commands::tasks::TaskRegistry::default());
            Ok(())
        })
        // Dateien, die ins Fenster gezogen werden: Pfade bleiben in Rust,
        // das Webview bekommt nur Namen und eine Marke.
        .on_window_event(|window, event| {
            use commands::system::{DropEvent, DropState};
            let payload = match event {
                tauri::WindowEvent::DragDrop(tauri::DragDropEvent::Enter { .. }) => DropEvent::Enter,
                tauri::WindowEvent::DragDrop(tauri::DragDropEvent::Leave) => DropEvent::Leave,
                tauri::WindowEvent::DragDrop(tauri::DragDropEvent::Drop { paths, .. }) => {
                    window.state::<DropState>().store(paths.clone())
                }
                _ => return,
            };
            if let Err(e) = window.emit("file-drop", payload) {
                log::warn!("file-drop konnte nicht gesendet werden: {e}");
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::tasks::cancel_task,
            commands::tasks::pause_task,
            commands::tasks::task_history,
            commands::tasks::record_task,
            commands::tasks::remove_task_record,
            commands::tasks::clear_task_history,
            commands::system::add_dropped_files,
            commands::system::pick_content_files,
            commands::system::pick_java_path,
            commands::system::check_java,
            commands::system::detect_java,
            commands::system::install_java,
            commands::system::storage_stats,
            commands::system::clean_unused_storage,
            commands::system::verify_storage,
            commands::instances::set_instance_group,
            commands::instances::loader_versions,
            commands::instances::latest_loader_version,
            commands::content::bulk_content,
            commands::games::reinstall_instance,
            commands::app::app_info,
            commands::app::client_mod_status,
            commands::app::open_data_dir,
            commands::app::firewall_status,
            commands::app::firewall_allow_all,
            commands::settings::get_settings,
            commands::settings::update_settings,
            commands::instances::list_instances,
            commands::instances::get_instance,
            commands::instances::create_instance,
            commands::instances::pick_instance_icon,
            commands::instances::remove_instance_icon,
            commands::instances::pick_instance_banner,
            commands::instances::set_instance_banner_screenshot,
            commands::instances::remove_instance_banner,
            commands::instances::change_instance_version,
            commands::instances::instance_history,
            commands::content::refresh_content_meta,
            commands::content::content_changelog,
            commands::content::modrinth_project,
            commands::content::modrinth_project_versions,
            commands::content::modrinth_projects,
            commands::content::plan_content_migration,
            commands::content::open_external_url,
            commands::instances::update_instance,
            commands::instances::delete_instance,
            commands::instances::open_instance_dir,
            commands::meta::get_version_manifest,
            commands::games::launch_instance,
            commands::games::stop_instance,
            commands::games::running_games,
            commands::games::get_game_logs,
            commands::games::repair_instance,
            commands::games::share_log,
            commands::accounts::list_accounts,
            commands::accounts::login_browser,
            commands::accounts::login_device_code,
            commands::accounts::cancel_login,
            commands::accounts::set_active_account,
            commands::accounts::remove_account,
            commands::content::list_content,
            commands::content::set_content_enabled,
            commands::content::delete_content,
            commands::content::installed_projects,
            commands::content::modrinth_search,
            commands::content::modrinth_categories,
            commands::content::modrinth_install,
            commands::content::modrinth_versions,
            commands::content::check_content_updates,
            commands::content::apply_content_update,
            commands::content::install_performance_pack,
            commands::content::install_modpack,
            commands::content::open_content_dir,
            commands::curseforge::curseforge_status,
            commands::curseforge::curseforge_search,
            commands::curseforge::curseforge_categories,
            commands::curseforge::curseforge_project,
            commands::curseforge::curseforge_project_versions,
            commands::curseforge::curseforge_projects,
            commands::curseforge::curseforge_versions,
            commands::curseforge::curseforge_changelog,
            commands::curseforge::curseforge_install,
            commands::curseforge::install_curseforge_modpack,
            commands::curseforge::curseforge_blocked,
            commands::curseforge::curseforge_adopt_downloads,
            commands::curseforge::curseforge_dismiss_blocked,
            commands::presets::list_presets,
            commands::presets::create_preset,
            commands::presets::update_preset,
            commands::presets::set_preset_auto,
            commands::presets::delete_preset,
            commands::presets::reorder_presets,
            commands::presets::export_preset,
            commands::presets::import_preset,
            commands::presets::apply_presets,
            commands::servers::list_servers,
            commands::servers::add_server,
            commands::servers::update_server,
            commands::servers::remove_server,
            commands::servers::ping_server,
            commands::extras::list_screenshots,
            commands::extras::open_screenshot,
            commands::extras::delete_screenshot,
            commands::extras::list_worlds,
            commands::extras::duplicate_instance,
            commands::import::scan_imports,
            commands::import::pick_import_folder,
            commands::import::import_instance,
            commands::skins::skin_profile,
            commands::skins::skin_library,
            commands::skins::add_skin_file,
            commands::skins::save_active_skin,
            commands::skins::delete_skin,
            commands::skins::apply_skin_changes,
            commands::skins::skin_sync_status,
            commands::skins::cancel_skin_sync,
            commands::news::get_news,
            commands::news::news_image,
            commands::news::patch_notes_body,
            commands::screenshots::all_screenshots,
            commands::screenshots::screenshot_thumbnail,
            commands::screenshots::screenshot_image,
            commands::screenshots::copy_screenshot,
            commands::screenshots::reveal_screenshot,
            commands::screenshots::trash_screenshot,
            commands::clips::list_clips,
            commands::clips::clip_usage,
            commands::clips::clip_video,
            commands::clips::clip_thumbnail,
            commands::clips::rename_clip,
            commands::clips::trash_clip,
            commands::clips::reveal_clip,
            commands::clips::open_clips_folder,
            commands::clips::clip_states,
            commands::clips::clip_action,
            commands::clips::ffmpeg_status,
            commands::clips::install_ffmpeg,
            commands::clips::pick_clips_folder,
            commands::export::export_candidates,
            commands::export::export_modpack,
            commands::export::import_modpack_file,
            commands::trs::trs_status,
            commands::trs::trs_set_consent,
            commands::trs::trs_me,
            commands::trs::trs_update_me,
            commands::trs::trs_delete_me,
            commands::trs::trs_capes,
            commands::trs::trs_set_cape,
            commands::trs::trs_upload_cape,
            commands::trs::trs_delete_cape,
            commands::trs::trs_report_cape,
            commands::trs::trs_redeem,
            commands::trs::trs_player_capes,
            commands::trs::trs_friends,
            commands::trs::trs_blocks,
            commands::trs::trs_friend_request,
            commands::trs::trs_friend_accept,
            commands::trs::trs_friend_decline,
            commands::trs::trs_friend_cancel,
            commands::trs::trs_friend_remove,
            commands::trs::trs_block,
            commands::trs::trs_unblock,
            commands::trs::trs_web_login_approve,
            commands::trs::trs_admin_stats,
            commands::trs::trs_admin_capes,
            commands::trs::trs_admin_approve,
            commands::trs::trs_admin_reject,
            commands::trs::trs_admin_delete_cape,
            commands::trs::trs_admin_codes,
            commands::trs::trs_admin_create_codes,
            commands::trs::trs_admin_revoke_code,
            commands::trs::trs_admin_user,
            commands::trs::trs_admin_grant,
            commands::trs::trs_admin_revoke_grant,
            commands::trs::trs_admin_ban,
            commands::trs::trs_admin_unban,
        ])
        .build(tauri::generate_context!())
        .expect("TRS Launcher konnte nicht gestartet werden")
        .run(|app, event| {
            // Beim Beenden die TRS-Präsenz zurücknehmen (kurz, offline egal).
            if let tauri::RunEvent::Exit = event
                && let Some(launcher) = app.try_state::<LauncherState>()
            {
                let launcher = Arc::clone(&launcher);
                // Laufende Aufnahmen sichern (höchstens ~5 s), dann die TRS-Präsenz
                // (trs_shutdown hat eigene kurze Timeouts, höchstens ~3 s).
                tauri::async_runtime::block_on(async move {
                    launcher.clips_shutdown().await;
                    launcher.trs_shutdown().await;
                });
            }
        });
}
