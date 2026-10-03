//! Android/iOS: Update-Kanal `mobile` und die APK-Installation.
//!
//! Die Commands gibt es auf allen Plattformen (eine Liste im Handler);
//! installieren kann nur Android. Die APK lädt der Kern geprüft (signiertes
//! Manifest, SHA-256, Größe) nach `<daten>/mobile-update/`, danach übergibt
//! ein kleines Kotlin-Plugin (`TrsMobilePlugin`) sie dem PackageInstaller –
//! das System fragt den Nutzer, bevor etwas installiert wird.

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, State};
use trs_core::mobile_update::{MobileUpdater, Target, UpdateStatus};

use crate::LauncherState;
use crate::commands::tasks::tracked;
use crate::error::CommandResult;

/// Kanal-Prüfung und Download (ein Updater pro App).
pub struct UpdateState(MobileUpdater);

impl UpdateState {
    pub fn new(launcher: &LauncherState) -> trs_core::Result<Self> {
        Ok(Self(MobileUpdater::new(launcher.paths())?))
    }
}

/// Was nach „Installieren“ passiert ist.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct InstallOutcome {
    /// `started`: Das System zeigt den Installationsdialog.
    /// `permissionRequired`: Erst „Unbekannte Apps installieren“ für den Launcher erlauben
    /// (die Einstellungsseite ist schon offen), dann erneut versuchen.
    pub status: String,
}

/// Prüft den Kanal `mobile` (Signatur wie beim TRS-Client-Kanal).
#[tauri::command]
pub async fn mobile_update_check(state: State<'_, UpdateState>) -> CommandResult<UpdateStatus> {
    Ok(state.0.check(Target::current()).await?)
}

/// Android: lädt die neue APK (Aufgabe mit Fortschritt, abbrechbar) und
/// startet die Installation. Andere Plattformen: Fehler.
#[tauri::command]
pub async fn mobile_update_install(
    app: AppHandle,
    state: State<'_, UpdateState>,
    task_id: Option<String>,
) -> CommandResult<InstallOutcome> {
    if !cfg!(target_os = "android") {
        return Err(trs_core::Error::validation(trs_core::msg!(
            "mobileUpdate.androidOnly",
            "Updates installiert die App nur unter Android selbst."
        ))
        .into());
    }
    let path = tracked(&app, task_id, state.0.download_apk()).await?;
    if !state.0.owns(&path) {
        return Err(trs_core::Error::Internal("APK liegt nicht im Update-Ordner".into()).into());
    }
    install(&app, path).await
}

#[cfg(target_os = "android")]
async fn install(app: &AppHandle, path: std::path::PathBuf) -> CommandResult<InstallOutcome> {
    use tauri::Manager;

    #[derive(Serialize)]
    struct Args {
        path: String,
    }
    let Some(installer) = app.try_state::<android::Installer>() else {
        return Err(trs_core::Error::Internal("Installer-Plugin fehlt".into()).into());
    };
    let handle = installer.0.clone();
    let args = Args { path: path.display().to_string() };
    let outcome = tauri::async_runtime::spawn_blocking(move || handle.run_mobile_plugin::<InstallOutcome>("installApk", args))
        .await
        .map_err(|e| trs_core::Error::Internal(e.to_string()))?
        .map_err(|e| {
            log::error!("APK-Installation fehlgeschlagen: {e}");
            trs_core::Error::validation(trs_core::msg!("mobileUpdate.installFailed", "Die Installation konnte nicht gestartet werden."))
        })?;
    if !matches!(outcome.status.as_str(), "started" | "permissionRequired") {
        return Err(trs_core::Error::Internal(format!("Unbekannter Installer-Status: {}", outcome.status)).into());
    }
    Ok(outcome)
}

#[cfg(not(target_os = "android"))]
async fn install(_app: &AppHandle, _path: std::path::PathBuf) -> CommandResult<InstallOutcome> {
    Err(trs_core::Error::UnsupportedOnMobile.into())
}

#[cfg(target_os = "android")]
mod android {
    /// Zugriff auf das Kotlin-Plugin `dev.theredstonee.trslauncher.TrsMobilePlugin`.
    pub struct Installer(pub tauri::plugin::PluginHandle<tauri::Wry>);
}

/// Plugin `trs-mobile`: meldet unter Android das Kotlin-Plugin an. Das
/// Webview darf es nicht direkt aufrufen (keine Rechte in den Capabilities) –
/// nur Rust über [`mobile_update_install`].
#[cfg(mobile)]
pub fn init() -> tauri::plugin::TauriPlugin<tauri::Wry> {
    tauri::plugin::Builder::new("trs-mobile")
        .setup(|app, api| {
            #[cfg(target_os = "android")]
            {
                use tauri::Manager;
                let handle = api.register_android_plugin("dev.theredstonee.trslauncher", "TrsMobilePlugin")?;
                app.manage(android::Installer(handle));
            }
            #[cfg(not(target_os = "android"))]
            let _ = (app, api);
            Ok(())
        })
        .build()
}
