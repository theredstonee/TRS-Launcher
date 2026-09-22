//! Skins & Umhänge. Das Webview sieht nur Bilder als Data-URL – das
//! Minecraft-Token und alle Dateipfade bleiben im Kern.

use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::skins::{LibrarySkinView, Profile, SkinVariant};

use crate::LauncherState;
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
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog().file().set_title("Skin-Datei wählen (PNG, 64×64)").add_filter("Skins", &["png"]).blocking_pick_file()
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

/// Setzt einen Skin aus der Sammlung auf das Mojang-Konto.
#[tauri::command]
pub async fn apply_skin(
    launcher: State<'_, LauncherState>,
    id: String,
    variant: Option<SkinVariant>,
) -> CommandResult<Profile> {
    Ok(launcher.apply_skin(&id, variant).await?)
}

/// Zurück zum Standard-Skin.
#[tauri::command]
pub async fn reset_skin(launcher: State<'_, LauncherState>) -> CommandResult<Profile> {
    Ok(launcher.reset_skin().await?)
}

/// Umhang wählen; `None` = keinen tragen.
#[tauri::command]
pub async fn choose_cape(launcher: State<'_, LauncherState>, cape_id: Option<String>) -> CommandResult<Profile> {
    Ok(launcher.choose_cape(cape_id.as_deref()).await?)
}
