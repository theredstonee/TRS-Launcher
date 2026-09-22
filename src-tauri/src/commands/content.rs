use tauri::State;
use tauri::ipc::Channel;
use trs_core::content::{self, ContentItem, ContentKind};
use trs_core::instance::Instance;
use trs_core::modpack::PackProgress;
use trs_core::modrinth::{self, SearchParams, SearchResult, UpdateInfo, VersionSummary};

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

/// Installiert eine Version samt Pflicht-Abhängigkeiten; ohne `version_id` die
/// neueste passende.
#[tauri::command]
pub async fn modrinth_install(
    launcher: State<'_, LauncherState>,
    id: String,
    project_id: String,
    kind: ContentKind,
    version_id: Option<String>,
) -> CommandResult<Vec<String>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(modrinth::install(launcher.http(), launcher.paths(), &instance, &project_id, kind, version_id.as_deref())
        .await?)
}

#[tauri::command]
pub async fn modrinth_versions(
    launcher: State<'_, LauncherState>,
    id: String,
    project_id: String,
    kind: ContentKind,
) -> CommandResult<Vec<VersionSummary>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(modrinth::list_versions(launcher.http(), &instance, &project_id, kind).await?)
}

#[tauri::command]
pub async fn check_content_updates(
    launcher: State<'_, LauncherState>,
    id: String,
) -> CommandResult<Vec<UpdateInfo>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(modrinth::check_updates(launcher.http(), launcher.paths(), &instance).await?)
}

#[tauri::command]
pub async fn apply_content_update(
    launcher: State<'_, LauncherState>,
    id: String,
    kind: ContentKind,
    file_name: String,
    version_id: String,
) -> CommandResult<String> {
    let instance = launcher.instances().get(&id).await?;
    Ok(modrinth::apply_update(launcher.http(), launcher.paths(), &instance, kind, &file_name, &version_id).await?)
}

/// Sodium, Lithium & Co. in einem Rutsch – was es für die Instanz nicht gibt,
/// wird übersprungen.
#[tauri::command]
pub async fn install_performance_pack(
    launcher: State<'_, LauncherState>,
    id: String,
) -> CommandResult<Vec<String>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(modrinth::install_performance_pack(launcher.http(), launcher.paths(), &instance).await?)
}

/// Legt aus einem Modrinth-Modpack eine neue Instanz an.
#[tauri::command]
pub async fn install_modpack(
    launcher: State<'_, LauncherState>,
    project_id: String,
    on_progress: Channel<PackProgress>,
) -> CommandResult<Instance> {
    Ok(launcher
        .install_modpack(&project_id, None, &move |progress| {
            let _ = on_progress.send(progress);
        })
        .await?)
}
