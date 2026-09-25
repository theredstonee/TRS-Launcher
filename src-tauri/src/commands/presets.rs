//! Mod-Presets: verwalten, teilen (Export/Import als kleine JSON-Datei) und
//! in eine Instanz installieren. Dateipfade aus den Dialogen bleiben in Rust.

use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::presets::{self, ApplyProgress, ApplyReport, Preset, PresetInput};

use crate::LauncherState;
use crate::commands::tasks::tracked;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

#[tauri::command]
pub async fn list_presets(launcher: State<'_, LauncherState>) -> CommandResult<Vec<Preset>> {
    Ok(presets::list(launcher.paths()).await?)
}

/// Nach einer erfolgreichen Änderung: mit dem TRS-Konto abgleichen (entprellt).
fn synced<T>(launcher: &LauncherState, result: trs_core::Result<T>) -> CommandResult<T> {
    let value = result?;
    launcher.trs_sync_touch();
    Ok(value)
}

#[tauri::command]
pub async fn create_preset(launcher: State<'_, LauncherState>, preset: PresetInput) -> CommandResult<Preset> {
    synced(&launcher, presets::create(launcher.paths(), preset).await)
}

#[tauri::command]
pub async fn update_preset(launcher: State<'_, LauncherState>, id: String, preset: PresetInput) -> CommandResult<Preset> {
    synced(&launcher, presets::update(launcher.paths(), &id, preset).await)
}

/// „Immer automatisch“ an/aus – auch für die fertigen TRS-Presets.
#[tauri::command]
pub async fn set_preset_auto(launcher: State<'_, LauncherState>, id: String, auto: bool) -> CommandResult<Preset> {
    synced(&launcher, presets::set_auto(launcher.paths(), &id, auto).await)
}

#[tauri::command]
pub async fn delete_preset(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    synced(&launcher, presets::delete(launcher.paths(), &id).await)
}

#[tauri::command]
pub async fn reorder_presets(launcher: State<'_, LauncherState>, ids: Vec<String>) -> CommandResult<Vec<Preset>> {
    synced(&launcher, presets::reorder(launcher.paths(), &ids).await)
}

/// Fragt nach dem Speicherort und schreibt das Preset. `false` = abgebrochen.
#[tauri::command]
pub async fn export_preset(app: AppHandle, launcher: State<'_, LauncherState>, id: String) -> CommandResult<bool> {
    let (file_name, bytes) = presets::export(launcher.paths(), &id).await?;
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::SavePreset.text(lang))
            .set_file_name(file_name)
            .add_filter(DialogText::PresetFile.text(lang), &["json"])
            .blocking_save_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(dest) = picked else { return Ok(false) };
    trs_core::fsutil::write_atomic(&dest, &bytes).await?;
    Ok(true)
}

/// Öffnet eine Preset-Datei und legt daraus ein eigenes Preset an.
/// `None` = abgebrochen.
#[tauri::command]
pub async fn import_preset(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<Option<Preset>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::PickPreset.text(lang))
            .add_filter(DialogText::PresetFile.text(lang), &["json"])
            .blocking_pick_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(file) = picked else { return Ok(None) };
    synced(&launcher, presets::import_file(launcher.paths(), &file).await).map(Some)
}

/// Soll die Instanzseite „FPS-Boost anwenden“ vorschlagen? (Modloader, kein
/// Sodium/Embeddium/OptiFine, kein Modpack.)
#[tauri::command]
pub async fn fps_boost_suggested(launcher: State<'_, LauncherState>, id: String) -> CommandResult<bool> {
    let instance = launcher.instances().get(&id).await?;
    Ok(presets::suggest_fps_boost(launcher.paths(), &instance).await?)
}

/// Installiert die gewählten Presets in die Instanz (als Aufgabe abbrechbar).
/// Was es für Version + Loader nicht gibt, steht mit Grund im Bericht.
#[tauri::command]
pub async fn apply_presets(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    preset_ids: Vec<String>,
    on_progress: Channel<ApplyProgress>,
    task_id: Option<String>,
) -> CommandResult<ApplyReport> {
    let instance = launcher.instances().get(&id).await?;
    // Sendefehler ignorieren: Ist die Seite weg, läuft die Installation weiter.
    let report = move |progress| {
        let _ = on_progress.send(progress);
    };
    let work = presets::apply(launcher.http(), launcher.paths(), &instance, &preset_ids, &report);
    Ok(tracked(&app, task_id, work).await?)
}
