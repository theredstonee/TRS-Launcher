//! Links und Ordner im System öffnen.
//!
//! Normalerweise über das Opener-Plugin. Im AppImage würde `xdg-open` aber die
//! Umgebung des eingehängten Abbilds erben (`LD_LIBRARY_PATH`, `GTK_PATH`, …) –
//! Browser und Dateimanager laden dann fremde Bibliotheken und starten nicht.
//! Dort startet der Launcher `xdg-open` selbst, mit bereinigter Umgebung.

use tauri::AppHandle;
use tauri_plugin_opener::OpenerExt;

type Result = std::result::Result<(), tauri_plugin_opener::Error>;

/// Läuft der Launcher als AppImage (nur dann ist die Umgebung „verschmutzt“)?
fn in_appimage() -> bool {
    cfg!(target_os = "linux") && std::env::var_os("APPIMAGE").is_some() && std::env::var_os("APPDIR").is_some()
}

fn xdg_open(target: &str) -> Result {
    let mut cmd = std::process::Command::new("xdg-open");
    trs_core::platform::env::clean_std(&mut cmd);
    cmd.arg(target)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null());
    let mut child = cmd.spawn()?;
    // Nicht blockieren, aber auch keinen Zombie hinterlassen.
    std::thread::spawn(move || {
        let _ = child.wait();
    });
    Ok(())
}

pub fn url(app: &AppHandle, url: impl AsRef<str>) -> Result {
    if in_appimage() {
        return xdg_open(url.as_ref());
    }
    app.opener().open_url(url.as_ref(), None::<&str>)
}

pub fn path(app: &AppHandle, path: impl AsRef<str>) -> Result {
    if in_appimage() {
        return xdg_open(path.as_ref());
    }
    app.opener().open_path(path.as_ref(), None::<&str>)
}
