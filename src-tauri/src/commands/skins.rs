//! Skins & Umhänge. Das Webview sieht nur Bilder als Data-URL – das
//! Minecraft-Token und alle Dateipfade bleiben im Kern.

use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::skin_sync::{SkinChanges, SkinSyncStatus};
use trs_core::skins::{LibrarySkinView, Profile, SkinVariant};

use crate::LauncherState;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

/// Profil des aktiven Accounts: aktiver Skin, Modell und verfügbare Umhänge.
#[tauri::command]
pub async fn skin_profile(launcher: State<'_, LauncherState>) -> CommandResult<Profile> {
    Ok(launcher.skin_profile().await?)
}

/// Eigene Skin-Sammlung (lokal, auch ohne Internet).
#[tauri::command]
pub async fn skin_library(launcher: State<'_, LauncherState>) -> CommandResult<Vec<LibrarySkinView>> {
    Ok(launcher.skin_library().await?)
}

/// Öffnet den Dateidialog und nimmt das PNG in die Sammlung auf.
/// `None` = abgebrochen.
#[tauri::command]
pub async fn add_skin_file(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    name: String,
    variant: SkinVariant,
) -> CommandResult<Option<LibrarySkinView>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::PickSkin.text(lang))
            .add_filter(DialogText::Skins.text(lang), &["png"])
            .blocking_pick_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(file) = picked else { return Ok(None) };
    Ok(Some(launcher.add_skin_from_file(&file, &name, variant).await?))
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
