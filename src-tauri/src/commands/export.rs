//! Instanz als `.mrpack` exportieren und Pack-Dateien wieder importieren.
//! Der Zielpfad kommt aus dem nativen Speichern-Dialog und bleibt in Rust.

use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use serde::Serialize;

use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::modpack::{PackPreview, PackProgress};
use trs_core::modpack_export::{ExportEntry, ExportOptions, ExportProgress, ExportSummary, suggested_file_name};

use crate::LauncherState;
use crate::commands::tasks::tracked;
use crate::dialog_text::{self, DialogText};
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
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::SaveModpack.text(lang))
            .set_file_name(suggestion)
            .add_filter(DialogText::ModrinthModpack.text(lang), &["mrpack"])
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

/// Zuletzt gewählte Modpack-Datei. Das Webview bekommt nur eine Marke und die
/// Vorschau – der Pfad bleibt hier (wie bei [`crate::commands::system::DropState`]).
#[derive(Default)]
pub struct PackPickState {
    next: AtomicU64,
    pending: Mutex<Option<(u64, PathBuf)>>,
}

impl PackPickState {
    fn store(&self, path: PathBuf) -> u64 {
        let token = self.next.fetch_add(1, Ordering::Relaxed) + 1;
        *self.pending.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some((token, path));
        token
    }

    fn take(&self, token: u64) -> Option<PathBuf> {
        let mut pending = self.pending.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        match pending.take() {
            Some((t, path)) if t == token => Some(path),
            other => {
                *pending = other;
                None
            }
        }
    }
}

/// Gewählte Pack-Datei: Marke für den Import und was drin ist.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PickedPack {
    token: u64,
    file_name: String,
    preview: PackPreview,
}

/// Öffnet den Dateidialog für eine `.mrpack` bzw. ein CurseForge-Zip und
/// liest das Pack (für die Frage „mit oder ohne TRS Client“). `None` = abgebrochen.
#[tauri::command]
pub async fn pick_modpack_file(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    picks: State<'_, PackPickState>,
) -> CommandResult<Option<PickedPack>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::PickModpack.text(lang))
            .add_filter(DialogText::AnyModpack.text(lang), &["mrpack", "zip"])
            .add_filter(DialogText::ModrinthModpack.text(lang), &["mrpack"])
            .blocking_pick_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(file) = picked else { return Ok(None) };

    let preview = launcher.preview_pack_file(&file).await?;
    let file_name = file.file_name().map(|n| n.to_string_lossy().chars().take(200).collect()).unwrap_or_default();
    let token = picks.store(file);
    Ok(Some(PickedPack { token, file_name, preview }))
}

/// Legt aus der zuvor gewählten Pack-Datei eine neue Instanz an (ID der Instanz).
/// `trs_client`: Wahl aus dem Dialog (`None` = Einstellung bzw. Vorauswahl).
#[tauri::command]
pub async fn import_modpack_file(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    picks: State<'_, PackPickState>,
    token: u64,
    trs_client: Option<bool>,
    on_progress: Channel<PackProgress>,
    task_id: Option<String>,
) -> CommandResult<String> {
    let file = picks.take(token).ok_or_else(|| {
        trs_core::Error::validation(trs_core::msg!(
            "commands.packPickExpired",
            "Die gewählte Datei ist nicht mehr verfügbar – bitte erneut wählen."
        ))
    })?;
    let report = move |progress| {
        let _ = on_progress.send(progress);
    };
    let work = launcher.import_modpack_file(&file, trs_client, &report);
    let instance = tracked(&app, task_id, work).await?;
    Ok(instance.id)
}
