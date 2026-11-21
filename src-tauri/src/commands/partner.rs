//! Partner-Programm (API §44): gemeinsames Code-Feld, eigener Partner, Beenden.

use tauri::State;
use trs_core::trs_api::partner::{MyPartner, RedeemOutcome};

use crate::LauncherState;
use crate::error::CommandResult;

/// Creator-, Partner- oder Umhang-Code einlösen.
#[tauri::command]
pub async fn trs_redeem_any(launcher: State<'_, LauncherState>, code: String) -> CommandResult<RedeemOutcome> {
    Ok(launcher.trs_redeem_any(&code).await?)
}

#[tauri::command]
pub async fn partner_me(launcher: State<'_, LauncherState>) -> CommandResult<MyPartner> {
    Ok(launcher.partner_me().await?)
}

#[tauri::command]
pub async fn partner_end_support(launcher: State<'_, LauncherState>) -> CommandResult<()> {
    Ok(launcher.partner_end_support().await?)
}
