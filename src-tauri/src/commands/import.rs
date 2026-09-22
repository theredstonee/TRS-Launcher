use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::import::{ImportCandidate, ImportProgress};
use trs_core::instance::{Instance, Loader};

use crate::LauncherState;
use crate::error::CommandResult;

/// Installationen anderer Launcher auf diesem Rechner.
#[tauri::command]
pub async fn scan_imports(launcher: State<'_, LauncherState>) -> CommandResult<Vec<ImportCandidate>> {
    Ok(launcher.scan_imports().await?)
}

/// Öffnet den Windows-Ordnerdialog und durchsucht den gewählten Ordner.
/// Der Pfad kommt aus dem nativen Dialog, nicht aus dem Webview.
/// `None` = Dialog abgebrochen.
#[tauri::command]
pub async fn pick_import_folder(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
) -> CommandResult<Option<Vec<ImportCandidate>>> {
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog().file().set_title("Ordner mit Minecraft-Daten wählen").blocking_pick_folder()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());

    match picked {
        Some(dir) => Ok(Some(launcher.add_import_folder(dir).await?)),
        None => Ok(None),
    }
}

/// Importiert per ID aus `scan_imports` – das Webview kann keine beliebigen
/// Pfade angeben. Version/Loader nur bei geschätzten Kandidaten änderbar.
#[tauri::command]
pub async fn import_instance(
    launcher: State<'_, LauncherState>,
    id: String,
    game_version: Option<String>,
    loader: Option<Loader>,
    on_progress: Channel<ImportProgress>,
) -> CommandResult<Instance> {
    Ok(launcher
        .import_instance(&id, game_version, loader, &move |progress| {
            let _ = on_progress.send(progress);
        })
        .await?)
}
