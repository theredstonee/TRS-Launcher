use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::import::{ImportCandidate, ImportOverview, ImportProgress, ImportResult};
use trs_core::instance::Loader;

use crate::LauncherState;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

/// Installationen anderer Launcher auf diesem Rechner.
#[tauri::command]
pub async fn scan_imports(launcher: State<'_, LauncherState>) -> CommandResult<Vec<ImportCandidate>> {
    Ok(launcher.scan_imports().await?)
}

/// Wie `scan_imports`, dazu die erkannten Launcher (auch nicht unterstützte).
#[tauri::command]
pub async fn import_overview(launcher: State<'_, LauncherState>) -> CommandResult<ImportOverview> {
    Ok(launcher.import_overview().await?)
}

/// Öffnet den Windows-Ordnerdialog und durchsucht den gewählten Ordner.
/// Der Pfad kommt aus dem nativen Dialog, nicht aus dem Webview.
/// `None` = Dialog abgebrochen.
#[tauri::command]
pub async fn pick_import_folder(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
) -> CommandResult<Option<Vec<ImportCandidate>>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog().file().set_title(DialogText::PickImportFolder.text(lang)).blocking_pick_folder()
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
) -> CommandResult<ImportResult> {
    Ok(launcher
        .import_instance(&id, game_version, loader, &move |progress| {
            let _ = on_progress.send(progress);
        })
        .await?)
}
