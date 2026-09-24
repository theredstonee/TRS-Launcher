use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use trs_core::auth::{Account, DeviceCode};

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn list_accounts(launcher: State<'_, LauncherState>) -> CommandResult<Vec<Account>> {
    Ok(launcher.accounts().list().await?)
}

/// Öffnet die Microsoft-Anmeldung im Standardbrowser und wartet auf den Redirect.
#[tauri::command]
pub async fn login_browser(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<Account> {
    let open = move |url: &str| {
        if let Err(e) = crate::open::url(&app, url) {
            log::error!("Browser konnte nicht geöffnet werden: {e}");
        }
    };
    Ok(launcher.accounts().login_browser(&open).await?)
}

/// Device-Code-Login: Der Code geht über den Channel ans Frontend, die
/// Microsoft-Seite zum Eingeben wird gleich mit geöffnet.
#[tauri::command]
pub async fn login_device_code(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    on_code: Channel<DeviceCode>,
) -> CommandResult<Account> {
    let show = move |code: &DeviceCode| {
        let _ = on_code.send(code.clone());
        // Die URL kommt von Microsoft; trotzdem nur bekannte Hosts öffnen.
        let trusted = ["https://www.microsoft.com/", "https://microsoft.com/", "https://login.microsoftonline.com/", "https://login.live.com/"]
            .iter()
            .any(|prefix| code.verification_uri.starts_with(prefix));
        if trusted && let Err(e) = crate::open::url(&app, &code.verification_uri) {
            log::error!("Browser konnte nicht geöffnet werden: {e}");
        }
    };
    Ok(launcher.accounts().login_device_code(&show).await?)
}

#[tauri::command]
pub async fn cancel_login(launcher: State<'_, LauncherState>) -> CommandResult<()> {
    launcher.accounts().cancel_login().await;
    Ok(())
}

#[tauri::command]
pub async fn set_active_account(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    launcher.accounts().set_active(&id).await?;
    // Präsenz sofort auf den neuen Account umstellen.
    launcher.trs().presence_kick();
    Ok(())
}

#[tauri::command]
pub async fn remove_account(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    // Vorher bei der TRS API abmelden (Präsenz zurück, Token widerrufen).
    launcher.trs_forget_account(&id).await;
    Ok(launcher.accounts().remove(&id).await?)
}
