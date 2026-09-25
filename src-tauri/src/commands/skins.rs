//! Skins & Umhänge. Das Webview sieht nur Bilder als Data-URL – das
//! Minecraft-Token und alle Dateipfade bleiben im Kern.

use std::path::PathBuf;

use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::skin_import::{ImportBatch, ImportCandidate, ImportReport, ImportRequest, LauncherScan};
use trs_core::skin_sync::{SkinChanges, SkinSyncStatus};
use trs_core::skins::{LibrarySkinView, Profile};

use crate::LauncherState;
use crate::commands::system::DropState;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

/// Profil des aktiven Accounts: aktiver Skin, Modell und verfügbare Umhänge.
#[tauri::command]
pub async fn skin_profile(launcher: State<'_, LauncherState>) -> CommandResult<Profile> {
    Ok(launcher.skin_profile().await?)
}

/// Skin-Link eines anderen Spielers (Gesicht in Freundesliste und Admin-Suche); `None` = Standard-Skin.
#[tauri::command]
pub async fn player_skin_url(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<Option<String>> {
    Ok(launcher.player_skin_url(&uuid).await?)
}

/// Eigene Skin-Sammlung (lokal, auch ohne Internet).
#[tauri::command]
pub async fn skin_library(launcher: State<'_, LauncherState>) -> CommandResult<Vec<LibrarySkinView>> {
    Ok(launcher.skin_library().await?)
}

// --- Skins hinzufügen: erst vormerken (geprüft, mit Vorschau), dann übernehmen ---

/// Öffnet den Dateidialog (Mehrfachauswahl) und merkt die PNGs vor.
/// `None` = abgebrochen.
#[tauri::command]
pub async fn pick_skin_files(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<Option<ImportBatch>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::PickSkin.text(lang))
            .add_filter(DialogText::Skins.text(lang), &["png"])
            .blocking_pick_files()
    })
    .await
    .ok()
    .flatten();
    let Some(picked) = picked else { return Ok(None) };
    let files: Vec<PathBuf> = picked.into_iter().filter_map(|p| p.into_path().ok()).collect();
    Ok(Some(launcher.stage_skin_files(files).await?))
}

/// Merkt die zuletzt ins Fenster gezogenen Dateien vor (Marke aus dem
/// `file-drop`-Event – die Pfade selbst bleiben in Rust).
#[tauri::command]
pub async fn stage_dropped_skins(
    launcher: State<'_, LauncherState>,
    drops: State<'_, DropState>,
    token: u64,
) -> CommandResult<ImportBatch> {
    let files = drops.take(token).ok_or_else(|| {
        trs_core::Error::validation(trs_core::msg!(
            "commands.dropExpired",
            "Die gezogenen Dateien sind nicht mehr verfügbar – bitte erneut ziehen."
        ))
    })?;
    Ok(launcher.stage_skin_files(files).await?)
}

/// Lädt einen Skin von einem öffentlichen https-Link (geprüft im Kern).
#[tauri::command]
pub async fn stage_skin_url(launcher: State<'_, LauncherState>, url: String) -> CommandResult<ImportCandidate> {
    Ok(launcher.stage_skin_url(&url).await?)
}

/// Holt den Skin eines Spielers über seinen Namen.
#[tauri::command]
pub async fn stage_player_skin(launcher: State<'_, LauncherState>, name: String) -> CommandResult<ImportCandidate> {
    Ok(launcher.stage_player_skin(&name).await?)
}

/// Sucht Skins in anderen Launchern (offizieller Launcher, Prism, Modrinth App).
#[tauri::command]
pub async fn scan_launcher_skins(launcher: State<'_, LauncherState>) -> CommandResult<LauncherScan> {
    Ok(launcher.scan_launcher_skins().await?)
}

/// Übernimmt vorgemerkte Skins in die Sammlung (und stößt den TRS-Abgleich an).
#[tauri::command]
pub async fn import_staged_skins(
    launcher: State<'_, LauncherState>,
    items: Vec<ImportRequest>,
) -> CommandResult<ImportReport> {
    Ok(launcher.import_staged_skins(items).await?)
}

/// Verwirft nicht übernommene Vormerkungen.
#[tauri::command]
pub fn discard_staged_skins(launcher: State<'_, LauncherState>, tokens: Vec<String>) {
    launcher.discard_staged_skins(&tokens);
}

/// Legt den gerade getragenen Skin in der Sammlung ab.
#[tauri::command]
pub async fn save_active_skin(launcher: State<'_, LauncherState>, name: String) -> CommandResult<LibrarySkinView> {
    Ok(launcher.save_active_skin(&name).await?)
}

#[tauri::command]
pub async fn delete_skin(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.delete_skin(&id).await?)
}

#[tauri::command]
pub async fn rename_skin(launcher: State<'_, LauncherState>, id: String, name: String) -> CommandResult<LibrarySkinView> {
    Ok(launcher.rename_skin(&id, &name).await?)
}

/// Nimmt den fertigen Entwurf (nur den Unterschied zum Konto) entgegen und
/// kehrt sofort zurück. Gesendet wird im Kern über eine Warteschlange, die bei
/// Mojang-429 selbst wartet – den Fortschritt liefert `skin_sync_status`.
#[tauri::command]
pub async fn apply_skin_changes(
    launcher: State<'_, LauncherState>,
    account: String,
    changes: SkinChanges,
) -> CommandResult<SkinSyncStatus> {
    Ok(launcher.inner().submit_skin_changes(&account, changes).await?)
}

/// Stand der Warteschlange (Warten mit Countdown, fertiges Profil, Fehler).
#[tauri::command]
pub fn skin_sync_status(launcher: State<'_, LauncherState>) -> SkinSyncStatus {
    launcher.skin_sync_status()
}

/// Noch nicht gesendete Änderungen verwerfen.
#[tauri::command]
pub fn cancel_skin_sync(launcher: State<'_, LauncherState>) -> SkinSyncStatus {
    launcher.cancel_skin_sync()
}
