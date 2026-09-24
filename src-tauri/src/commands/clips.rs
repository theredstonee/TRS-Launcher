//! Clips & Aufnahme: Bibliothek, Abspielen (Asset-Protokoll, nur einzelne
//! Dateien), FFmpeg laden, Speicherort wählen, Aufnahme-Knöpfe.

use std::path::PathBuf;

use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;
use trs_core::clips::api::ClipView;
use trs_core::clips::ffmpeg::FfmpegStatus;
use trs_core::clips::library::ClipUsage;
use trs_core::clips::ClipState;

use crate::LauncherState;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

/// Alle Clips aller Instanzen, neueste zuerst.
#[tauri::command]
pub async fn list_clips(launcher: State<'_, LauncherState>) -> CommandResult<Vec<ClipView>> {
    Ok(launcher.list_clips().await?)
}

#[tauri::command]
pub async fn clip_usage(launcher: State<'_, LauncherState>) -> CommandResult<ClipUsage> {
    Ok(launcher.clip_usage().await?)
}

/// Gibt genau dieses Video fürs Abspielen frei.
#[tauri::command]
pub async fn clip_video(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
) -> CommandResult<Option<PathBuf>> {
    let path = launcher.clip_path(&id, &file_name).await?;
    Ok(super::extras::allow(&app, &path).then_some(path))
}

/// Vorschaubild (wird beim ersten Mal mit FFmpeg erzeugt). `None` = keins.
#[tauri::command]
pub async fn clip_thumbnail(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
) -> CommandResult<Option<PathBuf>> {
    match launcher.clip_thumbnail(&id, &file_name).await {
        Ok(Some(path)) => Ok(super::extras::allow(&app, &path).then_some(path)),
        Ok(None) => Ok(None),
        Err(e) => {
            log::debug!("Clip-Vorschau übersprungen: {e}");
            Ok(None)
        }
    }
}

#[tauri::command]
pub async fn rename_clip(
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
    new_name: String,
) -> CommandResult<String> {
    Ok(launcher.rename_clip(&id, &file_name, &new_name).await?)
}

#[tauri::command]
pub async fn trash_clip(launcher: State<'_, LauncherState>, id: String, file_name: String) -> CommandResult<()> {
    Ok(launcher.trash_clip(&id, &file_name).await?)
}

/// Öffnet den Ordner und markiert den Clip darin.
#[tauri::command]
pub async fn reveal_clip(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
) -> CommandResult<()> {
    let path = launcher.clip_path(&id, &file_name).await?;
    app.opener().reveal_item_in_dir(path)?;
    Ok(())
}

/// Öffnet den Clip-Ordner (wird bei Bedarf angelegt).
#[tauri::command]
pub async fn open_clips_folder(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<()> {
    let root = launcher.clips_root().await;
    trs_core::fsutil::ensure_dir(&root).await?;
    crate::open::path(&app, root.display().to_string())?;
    Ok(())
}

/// Aufnahmestand der laufenden Spiele.
#[tauri::command]
pub fn clip_states(launcher: State<'_, LauncherState>) -> Vec<ClipState> {
    launcher.clips().states()
}

/// Knopf im Launcher: `record = false` speichert einen Clip, `true` startet/stoppt die Aufnahme.
#[tauri::command]
pub fn clip_action(launcher: State<'_, LauncherState>, id: String, record: bool) -> CommandResult<()> {
    Ok(launcher.clips().command(&id, record)?)
}

#[tauri::command]
pub async fn ffmpeg_status(launcher: State<'_, LauncherState>) -> CommandResult<FfmpegStatus> {
    Ok(launcher.clips().ffmpeg().status().await)
}

/// Lädt FFmpeg als Aufgabe (Fortschritt, Pause, Abbruch).
#[tauri::command]
pub async fn install_ffmpeg(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    on_progress: Channel<f64>,
    task_id: Option<String>,
) -> CommandResult<()> {
    // Nur ganze Prozentschritte senden (der Download meldet jedes Stück).
    let last = std::sync::atomic::AtomicI32::new(-1);
    let report = move |p: f64| {
        let percent = p.floor() as i32;
        if last.swap(percent, std::sync::atomic::Ordering::Relaxed) != percent {
            let _ = on_progress.send(f64::from(percent));
        }
    };
    let work = launcher.clips().ffmpeg().install_with(&report);
    crate::commands::tasks::tracked(&app, task_id, work).await?;
    Ok(())
}

/// Windows-Ordnerdialog für den Clip-Speicherort. `None` = abgebrochen.
#[tauri::command]
pub async fn pick_clips_folder(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<Option<String>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog().file().set_title(DialogText::PickClipsFolder.text(lang)).blocking_pick_folder()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    Ok(picked.map(|p| p.display().to_string()))
}
