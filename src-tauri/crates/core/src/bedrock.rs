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

/// App-ID der GDK-Fassung (seit 1.21.120); die alte UWP-Fassung hieß `App`.
const DEFAULT_APP_ID: &str = "Game";

/// Verknüpfung, mit der Windows die App startet (`shell:AppsFolder\<Familie>!<App-ID>`).
#[cfg_attr(not(windows), allow(dead_code))]
pub(crate) fn launch_target(app_id: &str) -> String {
    format!("shell:AppsFolder\\{PACKAGE_FAMILY}!{app_id}")
}

/// `Id` des ersten `<Application>` im AppxManifest.
pub(crate) fn app_id_from_manifest(xml: &str) -> Option<String> {
    let start = xml.find("<Application ")?;
    let tag = &xml[start..];
    let tag = &tag[..tag.find('>')?];
    let rest = &tag[tag.find(" Id=\"")? + 5..];
    let id = &rest[..rest.find('"')?];
    (!id.is_empty() && id.len() <= 64 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '.')).then(|| id.to_owned())
}

/// App-ID der installierten Fassung; ohne lesbares Manifest die der aktuellen GDK-Fassung.
fn installed_app_id() -> String {
    platform::appx_package(is_bedrock_package)
        .and_then(|name| platform::appx_package_root(&name))
        .and_then(|root| {
            let file = root.join("AppxManifest.xml");
            let len = std::fs::metadata(&file).ok()?.len();
            (len <= 1024 * 1024).then(|| std::fs::read_to_string(&file).ok()).flatten()
        })
        .and_then(|xml| app_id_from_manifest(&xml))
        .unwrap_or_else(|| DEFAULT_APP_ID.to_owned())
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
    spawn_shell(&launch_target(&installed_app_id()))
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
        assert_eq!(launch_target("Game"), "shell:AppsFolder\\Microsoft.MinecraftUWP_8wekyb3d8bbwe!Game");
    }

    #[test]
    fn reads_the_app_id_from_the_manifest() {
        let gdk = r#"<Package><Applications><Application Id="Game" Executable="GameLaunchHelper.exe" EntryPoint="Windows.FullTrustApplication"><uap:VisualElements /></Application></Applications></Package>"#;
        assert_eq!(app_id_from_manifest(gdk).as_deref(), Some("Game"));
        let uwp = r#"<Applications><Application Executable="Minecraft.Windows.exe" Id="App" EntryPoint="Minecraft_Win10.App">"#;
        assert_eq!(app_id_from_manifest(uwp).as_deref(), Some("App"));
        assert_eq!(app_id_from_manifest("<Applications></Applications>"), None);
        assert_eq!(app_id_from_manifest(r#"<Application Id="a!b&c" >"#), None);
        assert_eq!(app_id_from_manifest(r#"<Application Executable="x.exe"><Foo Id="Nope">"#), None);
    }

    #[test]
    #[ignore = "nur auf einem PC mit Bedrock"]
    fn finds_the_installed_app_id() {
        println!("{}", installed_app_id());
    }

    #[test]
    fn not_available_without_windows() {
        if cfg!(not(windows)) {
            assert!(!is_installed());
            assert!(launch().is_err());
        }
    }
}
