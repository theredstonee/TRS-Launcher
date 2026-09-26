mod commands;
mod dialog_text;
mod error;
mod open;

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
        .plugin(tauri_plugin_notification::init())
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
            // Kontowechsel im Spiel: „Konto hinzufügen“ öffnet den Browser über die App,
            // danach lädt die Oberfläche die Accounts neu.
            let handle = app.handle().clone();
            launcher.set_url_opener(Arc::new(move |url: &str| {
                if let Err(e) = crate::open::url(&handle, url) {
                    log::error!("Browser konnte nicht geöffnet werden: {e}");
                }
            }));
            let handle = app.handle().clone();
            launcher.set_accounts_sink(Arc::new(move || {
                if let Err(e) = handle.emit("accounts-changed", ()) {
                    log::warn!("accounts-changed konnte nicht gesendet werden: {e}");
                }
            }));
            // TRS-Synchronisation: nach jedem Abgleich (Änderungen + Status) ans Frontend.
            let handle = app.handle().clone();
            launcher.set_trs_sync_sink(Arc::new(move |event| {
                if let Err(e) = handle.emit("trs-sync", &event) {
                    log::warn!("trs-sync konnte nicht gesendet werden: {e}");
                }
            }));
            // „Im Launcher öffnen“ aus dem Spiel: Fenster nach vorn, Player mit dem Clip.
            let handle = app.handle().clone();
            launcher.set_clip_open_sink(Arc::new(move |request| {
                if let Some(window) = handle.get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.unminimize();
                    let _ = window.set_focus();
                }
                if let Err(e) = handle.emit("clip-open", &request) {
                    log::warn!("clip-open konnte nicht gesendet werden: {e}");
                }
            }));
            // Echtzeit-Kanal der TRS API: Ereignisse (Chat, Freunde, Präsenz …) und der
            // Zustand der Verbindung gehen sofort ans Frontend.
            let handle = app.handle().clone();
            launcher.set_trs_live_sink(Arc::new(move |out| {
                let result = match out {
                    trs_core::trs_api::live::LiveOut::Event(event) => handle.emit("trs-live", &event),
                    trs_core::trs_api::live::LiveOut::Status(status) => handle.emit("trs-live-status", &status),
                };
                if let Err(e) = result {
                    log::warn!("trs-live konnte nicht gesendet werden: {e}");
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
            // Skins/Presets/Theme/Sprache mit dem TRS-Konto abgleichen (nur mit Einwilligung + Schalter).
            tauri::async_runtime::spawn(Arc::clone(&launcher).run_trs_sync());
            // Echtzeit-Kanal `/v1/events/me` (nur mit Einwilligung und Account).
            tauri::async_runtime::spawn(Arc::clone(&launcher).run_trs_live());
            // Spiele mit verbundenem TRS Client: Der Launcher schweigt dann zu Sozial-Hinweisen.
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(Arc::clone(&launcher).run_social_game_clients(Arc::new(move |clients| {
                if let Err(e) = handle.emit("trs-client-linked", &clients) {
                    log::warn!("trs-client-linked konnte nicht gesendet werden: {e}");
                }
            })));
            // Discord-Status (nur lokal mit der Discord-App; läuft Discord nicht, passiert nichts).
            tauri::async_runtime::spawn(Arc::clone(&launcher).run_discord());
            app.manage::<LauncherState>(launcher);
            app.manage(commands::system::DropState::default());
            app.manage(commands::tasks::TaskRegistry::default());
            Ok(())
        })
        // Clips abspielen: `trsclip://localhost/<art>/<instanz>/<datei>` – nur Dateien im
        // Clip-Ordner, Videos in Stücken (Range), Vorschaubilder/-leisten bei Bedarf erzeugt.
        .register_asynchronous_uri_scheme_protocol(trs_core::clips::serve::SCHEME, |ctx, request, responder| {
            let app = ctx.app_handle().clone();
            let path = request.uri().path().to_owned();
            let range = request.headers().get("range").and_then(|v| v.to_str().ok()).map(str::to_owned);
            let head = request.method() == tauri::http::Method::HEAD;
            let readonly = head || request.method() == tauri::http::Method::GET;
            tauri::async_runtime::spawn(async move {
                let response = match app.try_state::<LauncherState>() {
                    Some(launcher) if readonly => launcher.serve_clip(&path, range).await,
                    Some(_) => trs_core::clips::serve::Response::status(405),
                    None => trs_core::clips::serve::Response::status(503),
                };
                let mut builder = tauri::http::Response::builder().status(response.status);
                for (name, value) in &response.headers {
                    builder = builder.header(*name, value);
                }
                let body = if head { Vec::new() } else { response.body };
                match builder.body(body) {
                    Ok(r) => responder.respond(r),
                    Err(e) => log::warn!("trsclip-Antwort fehlerhaft: {e}"),
                }
            });
        })
        // Chat-Bilder: `trschat://localhost/<a|t|l|e>/…` – der Kern holt sie mit dem Token
        // (der nie ins Webview gelangt) und liefert nur geprüfte PNG/JPEG/WebP aus.
        .register_asynchronous_uri_scheme_protocol(trs_core::trs_api::media::SCHEME, |ctx, request, responder| {
            let app = ctx.app_handle().clone();
            let path = request.uri().path().to_owned();
            let readonly = request.method() == tauri::http::Method::GET;
            tauri::async_runtime::spawn(async move {
                let response = match app.try_state::<LauncherState>() {
                    Some(launcher) if readonly => launcher.serve_chat_image(&path).await,
                    Some(_) => trs_core::clips::serve::Response::status(405),
                    None => trs_core::clips::serve::Response::status(503),
                };
                let mut builder = tauri::http::Response::builder().status(response.status);
                for (name, value) in &response.headers {
                    builder = builder.header(*name, value);
                }
                match builder.body(response.body) {
                    Ok(r) => responder.respond(r),
                    Err(e) => log::warn!("trschat-Antwort fehlerhaft: {e}"),
                }
            });
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
            commands::instances::get_fps_mode,
            commands::instances::set_fps_mode,
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
            commands::content::fix_mod_conflicts,
            commands::content::install_missing_dependencies,
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
            commands::presets::fps_boost_suggested,
            commands::servers::list_servers,
            commands::servers::add_server,
            commands::servers::update_server,
            commands::servers::remove_server,
            commands::servers::ping_server,
            commands::extras::list_screenshots,
            commands::extras::open_screenshot,
            commands::extras::delete_screenshot,
            commands::extras::duplicate_instance,
            commands::files::list_instance_files,
            commands::files::create_instance_folder,
            commands::files::create_instance_file,
            commands::files::rename_instance_file,
            commands::files::trash_instance_files,
            commands::files::open_instance_file,
            commands::files::reveal_instance_file,
            commands::files::pick_instance_upload,
            commands::files::import_dropped_instance_files,
            commands::worlds::instance_worlds,
            commands::worlds::open_world_folder,
            commands::worlds::backup_world,
            commands::worlds::trash_world,
            commands::worlds::instance_servers,
            commands::worlds::add_instance_server,
            commands::worlds::update_instance_server,
            commands::worlds::remove_instance_server,
            commands::logs::list_log_sources,
            commands::logs::read_log_source,
            commands::logs::share_log_source,
            commands::logs::qr_code,
            commands::import::scan_imports,
            commands::import::import_overview,
            commands::import::pick_import_folder,
            commands::import::import_instance,
            commands::skins::skin_profile,
            commands::skins::player_skin_url,
            commands::skins::skin_library,
            commands::skins::pick_skin_files,
            commands::skins::stage_dropped_skins,
            commands::skins::stage_skin_url,
            commands::skins::stage_player_skin,
            commands::skins::scan_launcher_skins,
            commands::skins::import_staged_skins,
            commands::skins::discard_staged_skins,
            commands::skins::save_active_skin,
            commands::skins::delete_skin,
            commands::skins::rename_skin,
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
            commands::clips::rename_clip,
            commands::clips::trash_clip,
            commands::clips::reveal_clip,
            commands::clips::open_clips_folder,
            commands::clips::clip_states,
            commands::clips::clip_action,
            commands::clips::ffmpeg_status,
            commands::clips::install_ffmpeg,
            commands::clips::pick_clips_folder,
            commands::clips::clip_details,
            commands::clips::clip_strip,
            commands::clips::plan_clip_trim,
            commands::clips::trim_clip,
            commands::clips::export_clip,
            commands::clips::copy_clip_file,
            commands::clips::open_clip_external,
            commands::export::export_candidates,
            commands::export::export_modpack,
            commands::export::import_modpack_file,
            commands::trs::trs_status,
            commands::trs::trs_sync_status,
            commands::trs::trs_set_consent,
            commands::trs::trs_me,
            commands::trs::trs_update_me,
            commands::trs::trs_delete_me,
            commands::trs::trs_capes,
            commands::trs::trs_set_cape,
            commands::trs::trs_pick_cape_sources,
            commands::trs::trs_upload_cape,
            commands::trs::trs_delete_cape,
            commands::trs::trs_report_cape,
            commands::trs::trs_redeem,
            commands::trs::trs_hats,
            commands::trs::trs_set_hat,
            commands::trs::trs_player_capes,
            commands::trs::trs_friends,
            commands::trs::trs_blocks,
            commands::trs::trs_friend_request,
            commands::trs::trs_friend_accept,
            commands::trs::trs_friend_decline,
            commands::trs::trs_friend_cancel,
            commands::trs::trs_friend_remove,
            commands::trs::trs_cape_offers,
            commands::trs::trs_offer_cape,
            commands::trs::trs_accept_cape_offer,
            commands::trs::trs_decline_cape_offer,
            commands::trs::trs_cape_holders,
            commands::trs::trs_revoke_cape_share,
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
            commands::social::trs_live_status,
            commands::social::trs_live_reconnect,
            commands::social::chat_conversations,
            commands::social::chat_conversation,
            commands::social::chat_open_dm,
            commands::social::chat_unread,
            commands::social::chat_create_group,
            commands::social::chat_rename_group,
            commands::social::chat_add_members,
            commands::social::chat_remove_member,
            commands::social::chat_leave_group,
            commands::social::chat_transfer_group,
            commands::social::chat_delete_group,
            commands::social::chat_messages,
            commands::social::chat_send,
            commands::social::chat_edit,
            commands::social::chat_delete,
            commands::social::chat_react,
            commands::social::chat_read,
            commands::social::chat_mark_unread,
            commands::social::chat_mute,
            commands::social::chat_typing,
            commands::social::chat_server_status,
            commands::social::chat_pick_images,
            commands::social::chat_stage_dropped,
            commands::social::chat_stage_pasted,
            commands::social::chat_local_images,
            commands::social::chat_forget_local,
            commands::social::chat_upload,
            commands::social::screenshot_favorites,
            commands::social::set_screenshot_favorite,
            commands::social::chat_report,
            commands::social::chat_my_reports,
            commands::social::chat_my_moderation,
            commands::social::admin_reports,
            commands::social::admin_report,
            commands::social::admin_report_status,
            commands::social::admin_report_action,
            commands::social::admin_report_note,
            commands::social::admin_moderation_user,
            commands::social::admin_mute,
            commands::social::admin_unmute,
            commands::social::admin_warn,
            commands::social::admin_word_filter,
            commands::social::admin_add_word,
            commands::social::admin_delete_word,
            commands::social::admin_audit,
            commands::moderation::trs_my_sanctions,
            commands::moderation::trs_appeal,
            commands::moderation::admin_dashboard,
            commands::moderation::admin_search,
            commands::moderation::admin_players,
            commands::moderation::admin_player,
            commands::moderation::admin_player_note,
            commands::moderation::admin_player_note_delete,
            commands::moderation::admin_sanctions,
            commands::moderation::admin_sanction,
            commands::moderation::admin_sanction_create,
            commands::moderation::admin_sanction_lift,
            commands::moderation::admin_sanction_duration,
            commands::moderation::admin_appeals,
            commands::moderation::admin_appeal_decide,
            commands::moderation::admin_roles,
            commands::moderation::admin_role_set,
            commands::moderation::admin_role_remove,
            commands::moderation::admin_rooms,
            commands::moderation::admin_room_close,
            commands::moderation::admin_bulk,
            commands::moderation::admin_cosmetics,
            commands::moderation::admin_delete_cosmetic,
            commands::moderation::admin_capes,
            commands::social::social_quiet_hours,
            commands::social::social_notify_native,
            commands::social::social_focus_window,
            commands::social::social_game_clients,
            commands::hosting::hosting_friends_rooms,
            commands::hosting::hosting_my_rooms,
            commands::hosting::hosting_room,
            commands::hosting::hosting_join,
            commands::hosting::hosting_leave,
            commands::hosting::hosting_delivery,
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
                // (trs_shutdown hat eigene kurze Timeouts, höchstens ~3 s) und den
                // Discord-Status (höchstens ~1,5 s, parallel dazu).
                tauri::async_runtime::block_on(async move {
                    let discord = Arc::clone(&launcher);
                    let discord = tauri::async_runtime::spawn(async move { discord.discord_shutdown().await });
                    launcher.clips_shutdown().await;
                    launcher.trs_shutdown().await;
                    let _ = discord.await;
                });
            }
        });
}
