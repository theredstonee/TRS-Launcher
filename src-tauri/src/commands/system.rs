//! Java-Installationen, Speicherverwaltung und Dateien per Drag & Drop.

use std::path::PathBuf;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

use serde::Serialize;
use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::download::Progress;
use trs_core::java::JavaInstall;
use trs_core::settings::validate_java_path;
use trs_core::storage::{StorageStats, VerifyReport};
use trs_core::upload::{self, UploadResult};

use crate::LauncherState;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

/// Zuletzt ins Fenster gezogene Dateien. Das Webview bekommt nur die Namen
/// und eine Marke – die Pfade bleiben hier, so kann das Webview keine
/// beliebigen Pfade zum Kopieren unterschieben.
#[derive(Default)]
pub struct DropState {
    next: AtomicU64,
    pending: Mutex<Option<(u64, Vec<PathBuf>)>>,
}

#[derive(Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum DropEvent {
    Enter,
    Leave,
    Drop { token: u64, names: Vec<String> },
}

impl DropState {
    pub fn store(&self, paths: Vec<PathBuf>) -> DropEvent {
        let token = self.next.fetch_add(1, Ordering::Relaxed) + 1;
        let paths: Vec<PathBuf> = paths.into_iter().take(upload::MAX_UPLOAD_FILES).collect();
        let names = paths
            .iter()
            .filter_map(|p| p.file_name().and_then(|n| n.to_str()).map(|n| n.chars().take(200).collect()))
            .collect();
        *self.pending.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some((token, paths));
        DropEvent::Drop { token, names }
    }

    fn take(&self, token: u64) -> Option<Vec<PathBuf>> {
        let mut pending = self.pending.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        match pending.take() {
            Some((t, paths)) if t == token => Some(paths),
            other => {
                *pending = other;
                None
            }
        }
    }
}

/// Übernimmt die zuletzt ins Fenster gezogenen Dateien in die Instanz.
#[tauri::command]
pub async fn add_dropped_files(
    launcher: State<'_, LauncherState>,
    drops: State<'_, DropState>,
    id: String,
    token: u64,
) -> CommandResult<Vec<UploadResult>> {
    let instance = launcher.instances().get(&id).await?;
    let files = drops
        .take(token)
        .ok_or_else(|| trs_core::Error::validation(trs_core::msg!(
            "commands.dropExpired",
            "Die gezogenen Dateien sind nicht mehr verfügbar – bitte erneut ziehen."
        )))?;
    Ok(upload::import_files(launcher.paths(), &instance, files).await?)
}

/// Öffnet den Dateidialog (Mehrfachauswahl) und fügt die Dateien hinzu.
/// `None` = abgebrochen.
#[tauri::command]
pub async fn pick_content_files(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
) -> CommandResult<Option<Vec<UploadResult>>> {
    let instance = launcher.instances().get(&id).await?;
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::AddContent.text(lang))
            .add_filter(DialogText::MinecraftContent.text(lang), &["jar", "zip"])
            .blocking_pick_files()
    })
    .await
    .ok()
    .flatten();
    let Some(picked) = picked else { return Ok(None) };
    let files: Vec<PathBuf> = picked.into_iter().filter_map(|p| p.into_path().ok()).collect();
    Ok(Some(upload::import_files(launcher.paths(), &instance, files).await?))
}

/// Wählt `java.exe`/`javaw.exe` (Linux: `bin/java`) im nativen Dialog. `None` = abgebrochen.
#[tauri::command]
pub async fn pick_java_path(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<Option<String>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        let dialog = app.dialog().file().set_title(DialogText::pick_java().text(lang));
        // Unter Linux hat `java` keine Dateiendung – dort gibt es keinen Filter.
        let dialog = if cfg!(windows) { dialog.add_filter("Java", &["exe"]) } else { dialog };
        dialog.blocking_pick_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(path) = picked else { return Ok(None) };
    let text = path.display().to_string();
    validate_java_path(&text)?;
    Ok(Some(text))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaCheck {
    pub major: Option<u32>,
    pub version: Option<String>,
}

/// Prüft einen Java-Pfad (existiert, Hauptversion laut `release`-Datei).
#[tauri::command]
pub fn check_java(path: String) -> CommandResult<JavaCheck> {
    validate_java_path(&path)?;
    let exe = PathBuf::from(&path);
    if !exe.is_file() {
        return Err(trs_core::Error::validation(trs_core::msg!(
            "commands.noJavaAtPath",
            "Unter diesem Pfad gibt es keine Java-Datei."
        )).into());
    }
    let found = trs_core::java::inspect(&exe);
    Ok(JavaCheck { major: found.as_ref().map(|f| f.0), version: found.map(|f| f.1) })
}

#[tauri::command]
pub async fn detect_java(launcher: State<'_, LauncherState>) -> CommandResult<Vec<JavaInstall>> {
    Ok(launcher.detect_java().await)
}

#[tauri::command]
pub async fn install_java(
    app: tauri::AppHandle,
    launcher: State<'_, LauncherState>,
    major: u32,
    on_progress: Channel<f64>,
    task_id: Option<String>,
) -> CommandResult<String> {
    let report = move |p: Progress| {
        let _ = on_progress.send(p.percent().floor());
    };
    let work = launcher.install_java(major, &report);
    let path = crate::commands::tasks::tracked(&app, task_id, work).await?;
    Ok(path.display().to_string())
}

#[tauri::command]
pub async fn storage_stats(launcher: State<'_, LauncherState>) -> CommandResult<StorageStats> {
    Ok(launcher.storage_stats().await?)
}

#[tauri::command]
pub async fn clean_unused_storage(launcher: State<'_, LauncherState>) -> CommandResult<u64> {
    Ok(launcher.clean_unused_storage().await?)
}

#[tauri::command]
pub async fn verify_storage(launcher: State<'_, LauncherState>) -> CommandResult<VerifyReport> {
    Ok(launcher.verify_storage().await?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn drop_tokens_are_single_use() {
        let state = DropState::default();
        let DropEvent::Drop { token, names } = state.store(vec![std::env::temp_dir().join("x").join("a.jar")]) else { panic!() };
        assert_eq!(names, ["a.jar"]);
        assert!(state.take(token + 1).is_none(), "falsche Marke");
        assert_eq!(state.take(token).unwrap().len(), 1);
        assert!(state.take(token).is_none(), "nur einmal verwendbar");
        let DropEvent::Drop { names, .. } = state.store((0..80).map(|i| PathBuf::from(format!("{i}.jar"))).collect()) else { panic!() };
        assert_eq!(names.len(), upload::MAX_UPLOAD_FILES);
    }
}
