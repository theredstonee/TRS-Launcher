use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;
use trs_core::content::{self, ContentItem, ContentKind};
use trs_core::modpack::PackProgress;
use trs_core::modrinth::{
    self, MigrationItem, ProjectCard, ProjectDetails, SearchParams, SearchResult, UpdateInfo, VersionSummary,
};

use crate::commands::instances::{InstanceView, view};

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
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    project_id: String,
    on_progress: Channel<PackProgress>,
) -> CommandResult<InstanceView> {
    let instance = launcher
        .install_modpack(&project_id, None, &move |progress| {
            let _ = on_progress.send(progress);
        })
        .await?;
    Ok(view(&app, &launcher, instance))
}

/// Ergänzt Icons, Titel und Autoren von Modrinth (erkennt auch von Hand
/// abgelegte Dateien per Hash). `true` = Liste neu laden.
#[tauri::command]
pub async fn refresh_content_meta(launcher: State<'_, LauncherState>, id: String) -> CommandResult<bool> {
    let instance = launcher.instances().get(&id).await?;
    Ok(modrinth::refresh_metadata(launcher.http(), launcher.paths(), &instance).await?)
}

/// Neuere passende Versionen seit der installierten – mit Changelog.
#[tauri::command]
pub async fn content_changelog(
    launcher: State<'_, LauncherState>,
    id: String,
    project_id: String,
    kind: ContentKind,
    installed_version_id: String,
) -> CommandResult<Vec<VersionSummary>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(modrinth::changelog_since(launcher.http(), &instance, &project_id, kind, &installed_version_id).await?)
}

#[tauri::command]
pub async fn modrinth_project(launcher: State<'_, LauncherState>, project_id: String) -> CommandResult<ProjectDetails> {
    Ok(modrinth::project_details(launcher.http(), &project_id).await?)
}

/// Alle Versionen eines Projekts (die Detailansicht filtert selbst).
#[tauri::command]
pub async fn modrinth_project_versions(
    launcher: State<'_, LauncherState>,
    project_id: String,
) -> CommandResult<Vec<VersionSummary>> {
    Ok(modrinth::project_versions(launcher.http(), &project_id).await?)
}

#[tauri::command]
pub async fn modrinth_projects(launcher: State<'_, LauncherState>, ids: Vec<String>) -> CommandResult<Vec<ProjectCard>> {
    Ok(modrinth::project_cards(launcher.http(), &ids).await?)
}

/// Nach einem Versionswechsel: welche Inhalte passen noch, wo gibt es eine
/// passende Version, wo keine?
#[tauri::command]
pub async fn plan_content_migration(
    launcher: State<'_, LauncherState>,
    id: String,
) -> CommandResult<Vec<MigrationItem>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(modrinth::plan_migration(launcher.http(), launcher.paths(), &instance).await?)
}

/// Öffnet einen Link (Modrinth-Seite, Quelltext, Links aus Beschreibungen)
/// im Standardbrowser – nur HTTPS.
#[tauri::command]
pub fn open_external_url(app: AppHandle, url: String) -> CommandResult<()> {
    if !modrinth::is_safe_external_url(&url) {
        return Err(trs_core::Error::validation("Dieser Link kann nicht geöffnet werden.").into());
    }
    app.opener().open_url(url, None::<&str>)?;
    Ok(())
}
