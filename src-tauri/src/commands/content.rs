use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use trs_core::content::{self, ContentItem, ContentKind, Platform};
use trs_core::depcheck::{self, DependencyFix};
use trs_core::modcompat::{self, CompatReport};
use trs_core::modpack::PackProgress;
use trs_core::modrinth::{
    self, CategoryTag, MigrationItem, MigrationStatus, ProjectCard, ProjectDetails, SearchParams, SearchResult,
    UpdateInfo, VersionSummary,
};

use crate::commands::instances::{InstanceView, view};
use crate::commands::tasks::tracked;

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

/// Mehrere Inhalte auf einmal (de)aktivieren oder löschen.
#[tauri::command]
pub async fn bulk_content(
    launcher: State<'_, LauncherState>,
    id: String,
    action: content::BulkAction,
    targets: Vec<content::BulkTarget>,
) -> CommandResult<content::BulkResult> {
    let instance = launcher.instances().get(&id).await?;
    Ok(content::bulk(launcher.paths(), &instance.id, action, &targets).await?)
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

/// Modrinth-Kategorien für die Filterleiste (im Kern einen Tag gecacht).
#[tauri::command]
pub async fn modrinth_categories(launcher: State<'_, LauncherState>) -> CommandResult<Vec<CategoryTag>> {
    Ok(modrinth::categories(launcher.http()).await?)
}

/// Installiert eine Version samt Pflicht-Abhängigkeiten; ohne `version_id` die
/// neueste passende.
#[tauri::command]
pub async fn modrinth_install(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    project_id: String,
    kind: ContentKind,
    version_id: Option<String>,
    task_id: Option<String>,
) -> CommandResult<Vec<String>> {
    let instance = launcher.instances().get(&id).await?;
    let work = modrinth::install(launcher.http(), launcher.paths(), &instance, &project_id, kind, version_id.as_deref());
    Ok(tracked(&app, task_id, work).await?)
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

/// Updates von Modrinth und – für von dort installierte Inhalte – CurseForge.
/// Fällt eine Quelle aus, zählen die Ergebnisse der anderen.
#[tauri::command]
pub async fn check_content_updates(
    launcher: State<'_, LauncherState>,
    id: String,
) -> CommandResult<Vec<UpdateInfo>> {
    let instance = launcher.instances().get(&id).await?;
    let modrinth = modrinth::check_updates(launcher.http(), launcher.paths(), &instance).await;
    let curseforge = match launcher.curseforge() {
        Ok(cf) => cf.check_updates(launcher.paths(), &instance).await,
        Err(_) => Ok(Vec::new()),
    };
    let mut updates = match (modrinth, curseforge) {
        (Ok(mut a), Ok(b)) => {
            a.extend(b);
            a
        }
        (Ok(list), Err(e)) | (Err(e), Ok(list)) => {
            log::warn!("Update-Prüfung einer Quelle fehlgeschlagen: {e}");
            list
        }
        (Err(e), Err(_)) => return Err(e.into()),
    };
    updates.sort_by_key(|u| u.file_name.to_lowercase());
    Ok(updates)
}

/// Tauscht Mods, die sich laut ihren Jars nicht vertragen, gegen passende
/// Versionen (Knopf in der Absturz-Diagnose). `prefer` = Mod-ID aus der
/// Fehlermeldung, die bevorzugt getauscht wird.
#[tauri::command]
pub async fn fix_mod_conflicts(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    prefer: Option<String>,
    task_id: Option<String>,
) -> CommandResult<CompatReport> {
    let instance = launcher.instances().get(&id).await?;
    let work = modcompat::fix_instance(launcher.http(), launcher.paths(), &instance, prefer.as_deref());
    Ok(tracked(&app, task_id, work).await?)
}

/// Installiert Mods, die laut Absturz fehlen (Knopf in der Absturz-Diagnose,
/// z. B. `cloth-config` für More Culling). `declarer` = Mod-ID der Mod, die sie verlangt.
#[tauri::command]
pub async fn install_missing_dependencies(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    declarer: Option<String>,
    dependencies: Vec<String>,
    task_id: Option<String>,
) -> CommandResult<DependencyFix> {
    let instance = launcher.instances().get(&id).await?;
    let builds = launcher.client_mod_builds().await;
    // Vanilla mit TRS-Optimierung startet als Fabric – die Abhängigkeit muss dazu passen.
    let effective = trs_core::boost::effective_instance(launcher.http(), launcher.paths(), &builds, &instance).await;
    let work = depcheck::install_missing(
        launcher.http(),
        launcher.paths(),
        &builds,
        &effective,
        declarer.as_deref(),
        &dependencies,
        &|_| {},
    );
    Ok(tracked(&app, task_id, work).await?)
}

#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub async fn apply_content_update(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    kind: ContentKind,
    file_name: String,
    version_id: String,
    platform: Option<Platform>,
    task_id: Option<String>,
) -> CommandResult<String> {
    let instance = launcher.instances().get(&id).await?;
    if platform == Some(Platform::CurseForge) {
        let cf = launcher.curseforge()?;
        let work = cf.apply_update(launcher.paths(), &instance, kind, &file_name, &version_id);
        return Ok(tracked(&app, task_id, work).await?);
    }
    let work = modrinth::apply_update(launcher.http(), launcher.paths(), &instance, kind, &file_name, &version_id);
    Ok(tracked(&app, task_id, work).await?)
}

/// Öffnet den Inhaltsordner einer Art (z. B. `mods`) im Explorer – etwa um
/// von Hand geladene Dateien hineinzulegen.
#[tauri::command]
pub async fn open_content_dir(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    kind: ContentKind,
) -> CommandResult<()> {
    let instance = launcher.instances().get(&id).await?;
    let dir = content::content_dir(launcher.paths(), &instance.id, kind);
    trs_core::fsutil::ensure_dir(&dir).await?;
    crate::open::path(&app, dir.display().to_string())?;
    Ok(())
}

/// Das FPS-Boost-Preset (Sodium, Lithium & Co.) in einem Rutsch – was es für
/// die Instanz nicht gibt, wird übersprungen.
#[tauri::command]
pub async fn install_performance_pack(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    task_id: Option<String>,
) -> CommandResult<Vec<String>> {
    let instance = launcher.instances().get(&id).await?;
    let builds = launcher.client_mod_builds().await;
    let work = trs_core::presets::install_fps_boost(launcher.http(), launcher.paths(), &builds, &instance);
    Ok(tracked(&app, task_id, work).await?)
}

/// Legt aus einem Modrinth-Modpack eine neue Instanz an.
#[tauri::command]
pub async fn install_modpack(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    project_id: String,
    on_progress: Channel<PackProgress>,
    task_id: Option<String>,
) -> CommandResult<InstanceView> {
    // Sendefehler ignorieren: Ist die Seite weg, läuft die Installation trotzdem weiter.
    let report = move |progress| {
        let _ = on_progress.send(progress);
    };
    let work = launcher.install_modpack(&project_id, None, &report);
    let instance = tracked(&app, task_id, work).await?;
    Ok(view(&app, &launcher, instance))
}

/// Ergänzt Icons, Titel und Autoren von Modrinth (erkennt auch von Hand
/// abgelegte Dateien per Hash) und CurseForge. `true` = Liste neu laden.
#[tauri::command]
pub async fn refresh_content_meta(launcher: State<'_, LauncherState>, id: String) -> CommandResult<bool> {
    let instance = launcher.instances().get(&id).await?;
    let curseforge = match launcher.curseforge() {
        Ok(cf) => cf.refresh_metadata(launcher.paths(), &instance).await.unwrap_or_else(|e| {
            log::debug!("CurseForge-Infos nicht geladen: {e}");
            false
        }),
        Err(_) => false,
    };
    let modrinth = modrinth::refresh_metadata(launcher.http(), launcher.paths(), &instance).await?;
    Ok(modrinth || curseforge)
}

/// Neuere passende Versionen seit der installierten – mit Changelog.
#[tauri::command]
pub async fn content_changelog(
    launcher: State<'_, LauncherState>,
    id: String,
    project_id: String,
    kind: ContentKind,
    installed_version_id: String,
    platform: Option<Platform>,
) -> CommandResult<Vec<VersionSummary>> {
    let instance = launcher.instances().get(&id).await?;
    if platform == Some(Platform::CurseForge) {
        let cf = launcher.curseforge()?;
        return Ok(cf.changelog_since(&instance, &project_id, kind, &installed_version_id).await?);
    }
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
    let mut plan = modrinth::plan_migration(launcher.http(), launcher.paths(), &instance).await?;
    if let Ok(cf) = launcher.curseforge() {
        plan.extend(cf.plan_migration(launcher.paths(), &instance).await?);
    }
    plan.sort_by_key(|p| (p.status != MigrationStatus::Missing, p.title.to_lowercase()));
    Ok(plan)
}

/// Öffnet einen Link (Modrinth-Seite, Quelltext, Links aus Beschreibungen)
/// im Standardbrowser – nur HTTPS.
#[tauri::command]
pub fn open_external_url(app: AppHandle, url: String) -> CommandResult<()> {
    if !modrinth::is_safe_external_url(&url) {
        return Err(trs_core::Error::validation(trs_core::msg!(
            "commands.linkNotAllowed",
            "Dieser Link kann nicht geöffnet werden."
        )).into());
    }
    crate::open::url(&app, url)?;
    Ok(())
}
