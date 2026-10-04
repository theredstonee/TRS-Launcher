//! Instanz als Minecraft-Server exportieren und lokale Server steuern.
//! Der ZIP-Zielpfad kommt aus dem nativen Speichern-Dialog und bleibt in Rust.

use std::sync::Arc;

use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::local_servers::{self, LocalServerInfo, ServerStatus};
use trs_core::server_export::{ServerExportOptions, ServerExportPlan, ServerExportProgress, ServerExportResult, suggested_zip_name};
use trs_core::server_share::{ServerAddresses, ShareKind, ShareStatus};
use trs_core::trs_api::hosting::InviteOutcome;

use crate::LauncherState;
use crate::commands::tasks::tracked;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

/// Mods (eingeordnet), Welten und Configs für den Dialog.
#[tauri::command]
pub async fn server_export_plan(launcher: State<'_, LauncherState>, id: String) -> CommandResult<ServerExportPlan> {
    Ok(launcher.server_export_plan(&id).await?)
}

/// Baut den Server. Mit ZIP wird zuerst nach dem Speicherort gefragt; `None` = abgebrochen.
#[tauri::command]
pub async fn export_server(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    options: ServerExportOptions,
    on_progress: Channel<ServerExportProgress>,
    task_id: Option<String>,
) -> CommandResult<Option<ServerExportResult>> {
    // Fehler in der Auswahl sofort melden – nicht erst nach dem Speichern-Dialog.
    trs_core::server_export::check_options(&options)?;
    let dest = if options.zip {
        let suggestion = suggested_zip_name(&options.name);
        let lang = dialog_text::language(&launcher).await;
        let dialog = app.clone();
        let picked = tauri::async_runtime::spawn_blocking(move || {
            dialog
                .dialog()
                .file()
                .set_title(DialogText::SaveServer.text(lang))
                .set_file_name(suggestion)
                .add_filter(DialogText::ZipArchive.text(lang), &["zip"])
                .blocking_save_file()
        })
        .await
        .ok()
        .flatten()
        .and_then(|p| p.into_path().ok());
        match picked {
            Some(path) => Some(path),
            None => return Ok(None),
        }
    } else {
        None
    };
    let progress: trs_core::server_export::ServerExportProgressFn = Arc::new(move |p| {
        let _ = on_progress.send(p);
    });
    let launcher = Arc::clone(&launcher);
    let work = async move { launcher.export_server(&id, &options, dest.as_deref(), &progress).await };
    Ok(Some(tracked(&app, task_id, work).await?))
}

#[tauri::command]
pub async fn local_servers_list(launcher: State<'_, LauncherState>) -> CommandResult<Vec<LocalServerInfo>> {
    Ok(launcher.local_servers().list(launcher.paths()).await?)
}

/// Startet den Server (lädt bei Bedarf die passende Java-Version).
#[tauri::command]
pub async fn local_server_start(app: AppHandle, launcher: State<'_, LauncherState>, id: String, task_id: Option<String>) -> CommandResult<ServerStatus> {
    let launcher = Arc::clone(&launcher);
    Ok(tracked(&app, task_id, async move { launcher.start_local_server(&id).await }).await?)
}

/// Schickt `stop` (nach einer Frist wird hart beendet).
#[tauri::command]
pub async fn local_server_stop(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    local_servers::validate_server_id(&id)?;
    Ok(launcher.local_servers().stop(&id)?)
}

#[tauri::command]
pub async fn local_server_kill(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    local_servers::validate_server_id(&id)?;
    Ok(launcher.local_servers().kill(&id)?)
}

#[tauri::command]
pub async fn local_server_restart(launcher: State<'_, LauncherState>, id: String) -> CommandResult<ServerStatus> {
    Ok(launcher.restart_local_server(&id).await?)
}

#[tauri::command]
pub async fn local_server_command(launcher: State<'_, LauncherState>, id: String, command: String) -> CommandResult<()> {
    local_servers::validate_server_id(&id)?;
    Ok(launcher.local_servers().command(&id, &command)?)
}

/// Log des laufenden bzw. zuletzt beendeten Laufs.
#[tauri::command]
pub async fn local_server_logs(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Vec<String>> {
    local_servers::validate_server_id(&id)?;
    Ok(launcher.local_servers().logs(&id))
}

#[tauri::command]
pub async fn local_server_open_folder(app: AppHandle, launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    let dir = local_servers::server_dir(launcher.paths(), &id)?;
    crate::open::path(&app, dir.display().to_string())?;
    Ok(())
}

/// Server-Ordner in den Papierkorb (nur gestoppt).
#[tauri::command]
pub async fn local_server_delete(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.delete_local_server(&id).await?)
}

// --- Teilen (Adressen, TRS Relay, e4mc, Freunde einladen) ----------------------------

/// Wie man den Server erreicht (`refresh` = öffentliche IP neu abfragen).
#[tauri::command]
pub async fn local_server_addresses(launcher: State<'_, LauncherState>, id: String, refresh: Option<bool>) -> CommandResult<ServerAddresses> {
    Ok(launcher.local_server_addresses(&id, refresh.unwrap_or(false)).await?)
}

#[tauri::command]
pub fn local_server_share_status(launcher: State<'_, LauncherState>, id: String) -> CommandResult<ShareStatus> {
    Ok(launcher.local_server_share_status(&id)?)
}

/// Weg einschalten (Relay-Raum bzw. e4mc-Link); der Stand kommt danach per `local-server-share`.
#[tauri::command]
pub async fn local_server_share(launcher: State<'_, LauncherState>, id: String, kind: ShareKind) -> CommandResult<ShareStatus> {
    let launcher = Arc::clone(&launcher);
    Ok(match kind {
        ShareKind::Relay => launcher.local_server_share_relay(&id).await?,
        ShareKind::E4mc => launcher.local_server_share_e4mc(&id).await?,
    })
}

#[tauri::command]
pub async fn local_server_unshare(launcher: State<'_, LauncherState>, id: String, kind: ShareKind) -> CommandResult<ShareStatus> {
    Ok(launcher.local_server_unshare(&id, kind).await?)
}

/// TRS-Freunde in den Relay-Raum einladen (höchstens 20 auf einmal).
#[tauri::command]
pub async fn local_server_invite(launcher: State<'_, LauncherState>, id: String, friends: Vec<String>) -> CommandResult<Vec<InviteOutcome>> {
    Ok(launcher.local_server_invite(&id, &friends).await?)
}
