//! Instanz als `.mrpack` exportieren und Pack-Dateien wieder importieren.
//! Der Zielpfad kommt aus dem nativen Speichern-Dialog und bleibt in Rust.

use std::sync::Arc;

use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::modpack::PackProgress;
use trs_core::modpack_export::{ExportEntry, ExportOptions, ExportProgress, ExportSummary, suggested_file_name};

use crate::LauncherState;
use crate::commands::tasks::tracked;
use crate::error::CommandResult;

/// Was im Spielordner liegt und mitexportiert werden kann.
#[tauri::command]
pub async fn export_candidates(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Vec<ExportEntry>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(trs_core::modpack_export::export_candidates(launcher.paths(), &instance.id).await?)
}

/// Fragt nach dem Speicherort und schreibt das Modpack. `None` = abgebrochen.
#[tauri::command]
pub async fn export_modpack(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    options: ExportOptions,
    on_progress: Channel<ExportProgress>,
) -> CommandResult<Option<ExportSummary>> {
    let suggestion = suggested_file_name(&options.name, &options.version);
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title("Modpack speichern")
            .set_file_name(suggestion)
            .add_filter("Modrinth-Modpack", &["mrpack"])
            .blocking_save_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(dest) = picked else { return Ok(None) };

    let progress: trs_core::modpack_export::ExportProgressFn = Arc::new(move |p| {
        let _ = on_progress.send(p);
    });
    Ok(Some(launcher.export_modpack(&id, &options, &dest, &progress).await?))
}

/// Öffnet eine `.mrpack`-Datei und legt daraus eine neue Instanz an.
/// `None` = abgebrochen.
#[tauri::command]
pub async fn import_modpack_file(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    on_progress: Channel<PackProgress>,
    task_id: Option<String>,
) -> CommandResult<Option<String>> {
    let dialog = app.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || {
        dialog
            .dialog()
            .file()
            .set_title("Modpack-Datei wählen")
            .add_filter("Modrinth-Modpack", &["mrpack"])
            .blocking_pick_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(file) = picked else { return Ok(None) };

    let report = move |progress| {
        let _ = on_progress.send(progress);
    };
    let work = launcher.import_modpack_file(&file, &report);
    let instance = tracked(&app, task_id, work).await?;
    Ok(Some(instance.id))
}
