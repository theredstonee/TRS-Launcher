//! Minecraft Bedrock (Microsoft Store / Xbox-App) – nur Windows. Der Launcher erkennt die
//! Installation und startet das Spiel über die App-Verknüpfung; Instanzen, Mods und Konten
//! gibt es dafür nicht.

use serde::Serialize;

use crate::platform;
use crate::{Error, Result};

/// Paketfamilie von Minecraft Bedrock (Windows 10/11 Edition).
pub const PACKAGE_FAMILY: &str = "Microsoft.MinecraftUWP_8wekyb3d8bbwe";
/// Name des Pakets (Vollname = `Name_Version_Architektur__Herausgeber`).
const PACKAGE_NAME: &str = "Microsoft.MinecraftUWP";
const PUBLISHER_ID: &str = "8wekyb3d8bbwe";

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct BedrockInfo {
    pub installed: bool,
}

/// Passt der Paket-Vollname aus der Paketverwaltung zu Minecraft Bedrock?
pub(crate) fn is_bedrock_package(full_name: &str) -> bool {
    full_name.len() <= 200
        && full_name.split('_').next().is_some_and(|name| name.eq_ignore_ascii_case(PACKAGE_NAME))
        && full_name.rsplit('_').next().is_some_and(|publisher| publisher.eq_ignore_ascii_case(PUBLISHER_ID))
}

/// Verknüpfung, mit der Windows die App startet (`shell:AppsFolder\<Familie>!<App-ID>`).
#[cfg_attr(not(windows), allow(dead_code))]
pub(crate) fn launch_target() -> String {
    format!("shell:AppsFolder\\{PACKAGE_FAMILY}!App")
}

/// Ist Minecraft Bedrock für diesen Benutzer installiert? (Linux: nie.)
pub fn is_installed() -> bool {
    cfg!(windows) && platform::appx_package_installed(is_bedrock_package)
}

pub fn info() -> BedrockInfo {
    BedrockInfo { installed: is_installed() }
}

/// Startet Bedrock über den Explorer (so wie ein Klick im Startmenü).
pub fn launch() -> Result<()> {
    if !is_installed() {
        return Err(Error::launch(crate::msg!("bedrock.notInstalled", "Minecraft Bedrock ist auf diesem PC nicht installiert.")));
    }
    spawn_shell(&launch_target())
}

#[cfg(windows)]
fn spawn_shell(target: &str) -> Result<()> {
    use std::os::windows::process::CommandExt;
    // CREATE_NO_WINDOW: kein Konsolenfenster. Der Explorer meldet oft Exit-Code 1, obwohl die App startet.
    std::process::Command::new("explorer.exe")
        .arg(target)
        .creation_flags(0x0800_0000)
        .spawn()
        .map(|_| ())
        .map_err(|_| Error::launch(crate::msg!("bedrock.startFailed", "Minecraft Bedrock konnte nicht gestartet werden.")))
}

#[cfg(not(windows))]
fn spawn_shell(_target: &str) -> Result<()> {
    Err(Error::launch(crate::msg!("bedrock.notInstalled", "Minecraft Bedrock ist auf diesem PC nicht installiert.")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recognises_the_store_package() {
        assert!(is_bedrock_package("Microsoft.MinecraftUWP_1.21.9301.0_x64__8wekyb3d8bbwe"));
        assert!(is_bedrock_package("microsoft.minecraftuwp_1.0.0.0_x64__8wekyb3d8bbwe"));
        // Andere Pakete, Preview, falscher Herausgeber.
        assert!(!is_bedrock_package("Microsoft.MinecraftWindowsBeta_1.21.0.0_x64__8wekyb3d8bbwe"));
        assert!(!is_bedrock_package("Microsoft.MinecraftUWPX_1.0_x64__8wekyb3d8bbwe"));
        assert!(!is_bedrock_package("Microsoft.MinecraftUWP_1.0_x64__evil"));
        assert!(!is_bedrock_package(""));
    }

    #[test]
    fn launch_target_is_the_apps_folder_link() {
        assert_eq!(launch_target(), "shell:AppsFolder\\Microsoft.MinecraftUWP_8wekyb3d8bbwe!App");
    }

    #[test]
    fn not_available_without_windows() {
        if cfg!(not(windows)) {
            assert!(!is_installed());
            assert!(launch().is_err());
        }
    }
}
