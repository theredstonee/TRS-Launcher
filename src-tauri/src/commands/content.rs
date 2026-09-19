use tauri::State;
use trs_core::content::{self, ContentItem, ContentKind};
use trs_core::modrinth::{self, SearchParams, SearchResult};

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn list_content(
    launcher: State<'_, LauncherState>,
    id: String,
    kind: ContentKind,
) -> CommandResult<Vec<ContentItem>> {
    // `get` stellt sicher, dass die Instanz existiert.
    let instance = launcher.instances().get(&id).await?;
    Ok(content::list(launcher.paths(), &instance.id, kind).await?)
}

#[tauri::command]
pub async fn set_content_enabled(
    launcher: State<'_, LauncherState>,
    id: String,
    kind: ContentKind,
    file_name: String,
    enabled: bool,
) -> CommandResult<()> {
    let instance = launcher.instances().get(&id).await?;
    Ok(content::set_enabled(launcher.paths(), &instance.id, kind, &file_name, enabled).await?)
}

#[tauri::command]
pub async fn delete_content(
    launcher: State<'_, LauncherState>,
    id: String,
    kind: ContentKind,
    file_name: String,
) -> CommandResult<()> {
    let instance = launcher.instances().get(&id).await?;
    Ok(content::delete(launcher.paths(), &instance.id, kind, &file_name).await?)
}

#[tauri::command]
pub async fn installed_projects(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Vec<String>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(content::installed_project_ids(launcher.paths(), &instance.id).await?)
}

#[tauri::command]
pub async fn modrinth_search(
    launcher: State<'_, LauncherState>,
    params: SearchParams,
) -> CommandResult<SearchResult> {
    Ok(modrinth::search(launcher.http(), &params).await?)
}

/// Installiert die neueste passende Version samt Pflicht-Abhängigkeiten.
#[tauri::command]
pub async fn modrinth_install(
    launcher: State<'_, LauncherState>,
    id: String,
    project_id: String,
    kind: ContentKind,
) -> CommandResult<Vec<String>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(modrinth::install(launcher.http(), launcher.paths(), &instance, &project_id, kind).await?)
}
