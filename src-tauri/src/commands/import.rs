use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use trs_core::import::{ImportCandidate, ImportOverview, ImportProgress, ImportResult};
use trs_core::instance::Loader;
use trs_core::trs_choice::TrsOffer;

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
        crate::commands::system::blocking_pick_folder(&app, DialogText::PickImportFolder.text(lang))
    })
    .await
    .ok()
    .flatten();

    match picked {
        Some(dir) => Ok(Some(launcher.add_import_folder(dir).await?)),
        None => Ok(None),
    }
}

/// „Mit oder ohne TRS Client“ für Loader und Version (ohne Blick auf Mods) –
/// etwa, wenn beim Import eines Ordners Version oder Loader geändert werden.
#[tauri::command]
pub async fn trs_client_offer(
    launcher: State<'_, LauncherState>,
    game_version: String,
    loader: Loader,
) -> CommandResult<TrsOffer> {
    trs_core::platform::desktop_only()?;
    loader.validate()?;
    Ok(launcher.trs_client_offer(&loader, &game_version, &[]).await)
}

/// Importiert per ID aus `scan_imports` – das Webview kann keine beliebigen
/// Pfade angeben. Version/Loader nur bei geschätzten Kandidaten änderbar.
#[tauri::command]
pub async fn import_instance(
    launcher: State<'_, LauncherState>,
    id: String,
    game_version: Option<String>,
    loader: Option<Loader>,
    trs_client: Option<bool>,
    on_progress: Channel<ImportProgress>,
) -> CommandResult<ImportResult> {
    Ok(launcher
        .import_instance(&id, game_version, loader, trs_client, &move |progress| {
            let _ = on_progress.send(progress);
        })
        .await?)
}
