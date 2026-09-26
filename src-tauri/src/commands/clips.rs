//! Clips & Aufnahme: Bibliothek, Zuschneiden, Teilen, FFmpeg laden,
//! Speicherort wählen, Aufnahme-Knöpfe. Abgespielt wird über das eigene
//! Protokoll `trsclip:` (siehe `lib.rs`) – das Webview bekommt keine Pfade.

use std::sync::Arc;

use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;
use trs_core::clips::api::ClipView;
use trs_core::clips::edit::{ClipStrip, TrimMode, TrimPlan, TrimRequest};
use trs_core::clips::media::MediaInfo;
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

/// Dauer, Größe und Keyframes eines Clips (für Zeitleiste und Zuschneiden).
#[tauri::command]
pub async fn clip_details(launcher: State<'_, LauncherState>, id: String, file_name: String) -> CommandResult<MediaInfo> {
    Ok(launcher.clip_details(&id, &file_name).await?)
}

/// Aufbau der Vorschau-Leiste (das Bild selbst kommt über `trsclip:`). `None` = keine (ohne FFmpeg).
#[tauri::command]
pub async fn clip_strip(launcher: State<'_, LauncherState>, id: String, file_name: String) -> CommandResult<Option<ClipStrip>> {
    match launcher.clip_strip(&id, &file_name).await {
        Ok(strip) => Ok(strip.map(|(_, s)| s)),
        Err(e) => {
            log::debug!("Vorschau-Leiste übersprungen: {e}");
            Ok(None)
        }
    }
}

/// Was beim Zuschneiden passieren würde (kopieren oder neu kodieren).
#[tauri::command]
pub async fn plan_clip_trim(
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
    start_ms: u64,
    end_ms: u64,
    mode: TrimMode,
) -> CommandResult<TrimPlan> {
    Ok(launcher.plan_clip_trim(&id, &file_name, start_ms, end_ms, mode).await?)
}

/// Bereich als neuen Clip speichern (das Original bleibt). Fortschritt 0–100 nur beim Neukodieren.
#[tauri::command]
pub async fn trim_clip(
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
    request: TrimRequest,
    on_progress: Channel<f64>,
) -> CommandResult<ClipView> {
    let last = Arc::new(std::sync::atomic::AtomicI32::new(-1));
    let progress: Arc<dyn Fn(f64) + Send + Sync> = Arc::new(move |p: f64| {
        let percent = p.floor() as i32;
        if last.swap(percent, std::sync::atomic::Ordering::Relaxed) != percent {
            let _ = on_progress.send(f64::from(percent));
        }
    });
    Ok(launcher.trim_clip(&id, &file_name, &request, Some(progress)).await?)
}

/// „Speichern unter …“: Zielpfad aus dem nativen Dialog (bleibt in Rust). `false` = abgebrochen.
#[tauri::command]
pub async fn export_clip(app: AppHandle, launcher: State<'_, LauncherState>, id: String, file_name: String) -> CommandResult<bool> {
    launcher.clip_path(&id, &file_name).await?;
    let lang = dialog_text::language(&launcher).await;
    let suggestion = file_name.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::SaveClip.text(lang))
            .set_file_name(suggestion)
            .add_filter(DialogText::Mp4Video.text(lang), &["mp4"])
            .blocking_save_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(mut dest) = picked else { return Ok(false) };
    if !dest.extension().is_some_and(|e| e.eq_ignore_ascii_case("mp4")) {
        dest.set_extension("mp4");
    }
    launcher.export_clip(&id, &file_name, &dest).await?;
    Ok(true)
}

/// Clip als Datei in die Zwischenablage (Einfügen in Explorer, Discord …).
#[tauri::command]
pub async fn copy_clip_file(launcher: State<'_, LauncherState>, id: String, file_name: String) -> CommandResult<()> {
    Ok(launcher.copy_clip_file(&id, &file_name).await?)
}

/// Mit dem Standard-Player des Systems öffnen (Rückfall, wenn das Webview das Video nicht abspielt).
#[tauri::command]
pub async fn open_clip_external(app: AppHandle, launcher: State<'_, LauncherState>, id: String, file_name: String) -> CommandResult<()> {
    let path = launcher.clip_path(&id, &file_name).await?;
    crate::open::path(&app, path.display().to_string())?;
    Ok(())
}
