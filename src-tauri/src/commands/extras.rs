use std::path::{Path, PathBuf};

use serde::Serialize;
use tauri::{AppHandle, Manager, State};
use trs_core::extras;
use trs_core::instance::Instance;

use crate::LauncherState;
use crate::error::CommandResult;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImageEntry {
    name: String,
    /// Für `convertFileSrc` – die Datei ist einzeln fürs Webview freigegeben.
    path: Option<PathBuf>,
    size: u64,
    date: Option<chrono::DateTime<chrono::Utc>>,
}

/// Gibt genau diese eine Datei für das Asset-Protokoll frei – das Webview
/// bekommt keinen Zugriff auf ganze Ordner.
pub(crate) fn allow(app: &AppHandle, path: &Path) -> bool {
    app.asset_protocol_scope().allow_file(path).is_ok()
}

#[tauri::command]
pub async fn list_screenshots(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
) -> CommandResult<Vec<ImageEntry>> {
    let instance = launcher.instances().get(&id).await?;
    let shots = extras::list_screenshots(launcher.paths(), &instance.id).await?;
    Ok(shots
        .into_iter()
        .map(|s| ImageEntry {
            path: allow(&app, &s.path).then_some(s.path),
            name: s.file_name,
            size: s.size,
            date: s.taken_at,
        })
        .collect())
}

#[tauri::command]
pub async fn open_screenshot(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
) -> CommandResult<()> {
    let path = extras::screenshot_path(launcher.paths(), &id, &file_name)?;
    crate::open::path(&app, path.display().to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn delete_screenshot(
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
) -> CommandResult<()> {
    Ok(extras::delete_screenshot(launcher.paths(), &id, &file_name).await?)
}

#[tauri::command]
pub async fn duplicate_instance(
    launcher: State<'_, LauncherState>,
    id: String,
    name: String,
) -> CommandResult<Instance> {
    Ok(launcher.duplicate_instance(&id, &name).await?)
}
