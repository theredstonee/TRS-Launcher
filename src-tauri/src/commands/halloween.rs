//! Halloween 2026 (API §36): Kürbis-Suche, Süßes oder Saures, Spuk-Effekte, Sticker und
//! Abzeichen-Bilder. Prüfung der Eingaben im Kern; Bilder kommen als Data-URL.

use tauri::State;
use trs_core::trs_api::halloween::{BadgeImage, HalloweenState, PumpkinFound, SpookyEffects, Sticker, TrickOutcome};

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn halloween_state(launcher: State<'_, LauncherState>) -> CommandResult<HalloweenState> {
    Ok(launcher.halloween_state().await?)
}

#[tauri::command]
pub async fn halloween_find_pumpkin(launcher: State<'_, LauncherState>, id: String) -> CommandResult<PumpkinFound> {
    Ok(launcher.halloween_find_pumpkin(&id).await?)
}

#[tauri::command]
pub async fn halloween_trick(launcher: State<'_, LauncherState>) -> CommandResult<TrickOutcome> {
    Ok(launcher.halloween_trick().await?)
}

#[tauri::command]
pub async fn halloween_set_effects(launcher: State<'_, LauncherState>, effects: SpookyEffects) -> CommandResult<()> {
    Ok(launcher.halloween_set_effects(effects).await?)
}

#[tauri::command]
pub async fn halloween_choose_wings(launcher: State<'_, LauncherState>, id: String) -> CommandResult<HalloweenState> {
    Ok(launcher.halloween_choose_wings(&id).await?)
}

#[tauri::command]
pub async fn trs_set_wings(launcher: State<'_, LauncherState>, id: Option<String>) -> CommandResult<()> {
    Ok(launcher.trs_set_wings(id).await?)
}

#[tauri::command]
pub async fn halloween_stickers(launcher: State<'_, LauncherState>) -> CommandResult<Vec<Sticker>> {
    Ok(launcher.halloween_stickers().await?)
}

#[tauri::command]
pub async fn trs_badge_images(launcher: State<'_, LauncherState>, ids: Vec<String>) -> CommandResult<Vec<BadgeImage>> {
    Ok(launcher.trs_badge_images(&ids).await?)
}
