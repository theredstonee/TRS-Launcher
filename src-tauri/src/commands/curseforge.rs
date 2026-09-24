//! CurseForge-Commands. Alle Aufrufe laufen im Kern – der API-Schlüssel
//! erreicht das Webview nie. Ohne Schlüssel im Build liefern sie den Fehler
//! `curseforge.disabled` (die Oberfläche blendet CurseForge dann aus).

use serde::Serialize;
use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use trs_core::content::ContentKind;
use trs_core::curseforge::{self, AdoptResult, BlockedFile, CurseForgeStatus, InstallOutcome};
use trs_core::modpack::PackProgress;
use trs_core::modrinth::{CategoryTag, ProjectCard, ProjectDetails, SearchParams, SearchResult, VersionSummary};

use crate::LauncherState;
use crate::commands::instances::{InstanceView, view};
use crate::commands::tasks::tracked;
use crate::error::CommandResult;

#[tauri::command]
pub fn curseforge_status(launcher: State<'_, LauncherState>) -> CurseForgeStatus {
    launcher.curseforge_status()
}

#[tauri::command]
pub async fn curseforge_search(launcher: State<'_, LauncherState>, params: SearchParams) -> CommandResult<SearchResult> {
    Ok(launcher.curseforge()?.search(&params).await?)
}

/// Kategorien für die Filterleiste (im Kern einen Tag gecacht).
#[tauri::command]
pub async fn curseforge_categories(launcher: State<'_, LauncherState>) -> CommandResult<Vec<CategoryTag>> {
    Ok(launcher.curseforge()?.categories().await?)
}

#[tauri::command]
pub async fn curseforge_project(launcher: State<'_, LauncherState>, project_id: String) -> CommandResult<ProjectDetails> {
    Ok(launcher.curseforge()?.project_details(&project_id).await?)
}

/// Alle Dateien eines Projekts (die Detailansicht filtert selbst).
#[tauri::command]
pub async fn curseforge_project_versions(
    launcher: State<'_, LauncherState>,
    project_id: String,
) -> CommandResult<Vec<VersionSummary>> {
    Ok(launcher.curseforge()?.project_versions(&project_id).await?)
}

#[tauri::command]
pub async fn curseforge_projects(launcher: State<'_, LauncherState>, ids: Vec<String>) -> CommandResult<Vec<ProjectCard>> {
    Ok(launcher.curseforge()?.project_cards(&ids).await?)
}

/// Zur Instanz passende Dateien eines Projekts.
#[tauri::command]
pub async fn curseforge_versions(
    launcher: State<'_, LauncherState>,
    id: String,
    project_id: String,
    kind: ContentKind,
) -> CommandResult<Vec<VersionSummary>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(launcher.curseforge()?.list_versions(&instance, &project_id, kind).await?)
}

/// Changelog einer Datei (HTML – nur bereinigt anzeigen).
#[tauri::command]
pub async fn curseforge_changelog(
    launcher: State<'_, LauncherState>,
    project_id: String,
    file_id: String,
) -> CommandResult<String> {
    Ok(launcher.curseforge()?.changelog(&project_id, &file_id).await?)
}

/// Installiert eine Datei samt Pflicht-Abhängigkeiten; ohne `file_id` die
/// neueste passende. Gesperrte Dateien kommen als `blocked` zurück.
#[tauri::command]
pub async fn curseforge_install(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    project_id: String,
    kind: ContentKind,
    file_id: Option<String>,
    task_id: Option<String>,
) -> CommandResult<InstallOutcome> {
    let instance = launcher.instances().get(&id).await?;
    let cf = launcher.curseforge()?;
    let work = cf.install(launcher.paths(), &instance, &project_id, kind, file_id.as_deref());
    Ok(tracked(&app, task_id, work).await?)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CurseForgePackResult {
    instance: InstanceView,
    blocked: Vec<BlockedFile>,
}

/// Legt aus einem CurseForge-Modpack eine neue Instanz an.
#[tauri::command]
pub async fn install_curseforge_modpack(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    project_id: String,
    file_id: Option<String>,
    on_progress: Channel<PackProgress>,
    task_id: Option<String>,
) -> CommandResult<CurseForgePackResult> {
    // Sendefehler ignorieren: Ist die Seite weg, läuft die Installation trotzdem weiter.
    let report = move |progress| {
        let _ = on_progress.send(progress);
    };
    let work = launcher.install_curseforge_modpack(&project_id, file_id.as_deref(), &report);
    let outcome = tracked(&app, task_id, work).await?;
    Ok(CurseForgePackResult { instance: view(&app, &launcher, outcome.instance), blocked: outcome.blocked })
}

/// Dateien, die der Nutzer für diese Instanz noch selbst laden muss.
#[tauri::command]
pub async fn curseforge_blocked(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Vec<BlockedFile>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(curseforge::blocked_files(launcher.paths(), &instance.id).await?)
}

/// Übernimmt passende Dateien aus dem Download-Ordner (Name + SHA1).
#[tauri::command]
pub async fn curseforge_adopt_downloads(launcher: State<'_, LauncherState>, id: String) -> CommandResult<AdoptResult> {
    let instance = launcher.instances().get(&id).await?;
    Ok(curseforge::adopt_downloads(launcher.paths(), &instance.id).await?)
}

/// Verzichtet auf eine (oder alle) der Dateien zum Selbst-Laden.
#[tauri::command]
pub async fn curseforge_dismiss_blocked(
    launcher: State<'_, LauncherState>,
    id: String,
    file_id: Option<String>,
) -> CommandResult<Vec<BlockedFile>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(curseforge::dismiss_blocked(launcher.paths(), &instance.id, file_id.as_deref()).await?)
}
