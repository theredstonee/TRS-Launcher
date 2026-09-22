use tauri::State;
use tauri::ipc::Channel;
use trs_core::import::{ImportCandidate, ImportProgress};
use trs_core::instance::Instance;

use crate::LauncherState;
use crate::error::CommandResult;

/// Installationen anderer Launcher auf diesem Rechner.
#[tauri::command]
pub async fn scan_imports(launcher: State<'_, LauncherState>) -> CommandResult<Vec<ImportCandidate>> {
    Ok(launcher.scan_imports().await?)
}

/// Importiert per ID aus `scan_imports` – das Webview kann keine beliebigen
/// Pfade angeben.
#[tauri::command]
pub async fn import_instance(
    launcher: State<'_, LauncherState>,
    id: String,
    on_progress: Channel<ImportProgress>,
) -> CommandResult<Instance> {
    Ok(launcher
        .import_instance(&id, &move |progress| {
            let _ = on_progress.send(progress);
        })
        .await?)
}
