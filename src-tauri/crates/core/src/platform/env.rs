//! Umgebung für Kindprozesse (Spiel, Hooks, Forge-Processors).
//!
//! Ein AppImage hängt beim Start Pfade aus seinem eingehängten Abbild an
//! Variablen wie `LD_LIBRARY_PATH`, `XDG_DATA_DIRS` oder `GTK_PATH`. Das
//! Spiel würde sonst Bibliotheken aus dem Launcher-Abbild laden – und
//! abstürzen, sobald der Launcher geschlossen und das Abbild ausgehängt ist.
//! Deshalb werden diese Einträge für Kindprozesse wieder entfernt.

use std::ffi::{OsStr, OsString};

/// Nur für das AppImage selbst gedacht – im Spiel haben sie nichts verloren.
const APPIMAGE_ONLY: [&str; 4] = ["APPDIR", "APPIMAGE", "ARGV0", "OWD"];

/// Eine Änderung an der geerbten Umgebung: `None` = Variable entfernen.
pub type EnvChange = (OsString, Option<OsString>);

/// Berechnet die Änderungen für eine Umgebung, die in einem AppImage unter
/// `appdir` läuft. Ohne `appdir` (kein AppImage, Windows) gibt es nichts zu tun.
pub fn appimage_cleanup(vars: impl IntoIterator<Item = (OsString, OsString)>, appdir: Option<&OsStr>) -> Vec<EnvChange> {
    let Some(appdir) = appdir.and_then(OsStr::to_str).map(|d| d.trim_end_matches('/')).filter(|d| d.len() > 1) else {
        return Vec::new();
    };
    let mut changes = Vec::new();
    for (key, value) in vars {
        let Some(name) = key.to_str() else { continue };
        if APPIMAGE_ONLY.contains(&name) {
            changes.push((key, None));
            continue;
        }
        let Some(text) = value.to_str() else { continue };
        if !text.contains(appdir) {
            continue;
        }
        let inside = |entry: &str| entry == appdir || entry.starts_with(&format!("{appdir}/"));
        // Pfadlisten (`a:b:c`) behalten ihre übrigen Einträge, Einzelwerte fallen weg.
        let entries: Vec<&str> = text.split(':').filter(|e| !e.is_empty()).collect();
        let kept: Vec<&str> = entries.iter().copied().filter(|e| !inside(e)).collect();
        if kept.len() == entries.len() {
            continue;
        }
        changes.push((key, (!kept.is_empty()).then(|| OsString::from(kept.join(":")))));
    }
    changes
}

/// Änderungen für die aktuelle Prozess-Umgebung.
pub fn child_env_changes() -> Vec<EnvChange> {
    if cfg!(windows) {
        return Vec::new();
    }
    let appdir = std::env::var_os("APPDIR").filter(|_| std::env::var_os("APPIMAGE").is_some());
    appimage_cleanup(std::env::vars_os(), appdir.as_deref())
}

/// Wendet [`child_env_changes`] auf einen `std`-Befehl an.
pub fn clean_std(cmd: &mut std::process::Command) {
    for (key, value) in child_env_changes() {
        match value {
            Some(v) => cmd.env(key, v),
            None => cmd.env_remove(key),
        };
    }
}

/// Wendet [`child_env_changes`] auf einen tokio-Befehl an.
pub fn clean_tokio(cmd: &mut tokio::process::Command) {
    for (key, value) in child_env_changes() {
        match value {
            Some(v) => cmd.env(key, v),
            None => cmd.env_remove(key),
        };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vars(list: &[(&str, &str)]) -> Vec<(OsString, OsString)> {
        list.iter().map(|(k, v)| (OsString::from(k), OsString::from(v))).collect()
    }

    #[test]
    fn strips_appimage_paths() {
        let env = vars(&[
            ("APPDIR", "/tmp/.mount_TRSabc"),
            ("APPIMAGE", "/home/a/TRS.AppImage"),
            ("XDG_DATA_DIRS", "/tmp/.mount_TRSabc/usr/share:/usr/local/share:/usr/share"),
            ("LD_LIBRARY_PATH", "/tmp/.mount_TRSabc/usr/lib"),
            ("GTK_PATH", "/tmp/.mount_TRSabc/usr/lib/gtk-3.0"),
            ("HOME", "/home/a"),
            // Nur ein ähnlicher Präfix – bleibt.
            ("OTHER", "/tmp/.mount_TRSabcdef/x"),
        ]);
        let changes = appimage_cleanup(env, Some(OsStr::new("/tmp/.mount_TRSabc/")));
        let get = |k: &str| changes.iter().find(|(key, _)| key == k).map(|(_, v)| v.clone());
        assert_eq!(get("APPDIR"), Some(None));
        assert_eq!(get("APPIMAGE"), Some(None));
        assert_eq!(get("XDG_DATA_DIRS"), Some(Some(OsString::from("/usr/local/share:/usr/share"))));
        assert_eq!(get("LD_LIBRARY_PATH"), Some(None));
        assert_eq!(get("GTK_PATH"), Some(None));
        assert_eq!(get("HOME"), None);
        assert_eq!(get("OTHER"), None, "fremder Pfad mit gleichem Anfang bleibt unverändert");
    }

    #[test]
    fn nothing_without_appimage() {
        assert!(appimage_cleanup(vars(&[("PATH", "/usr/bin")]), None).is_empty());
        assert!(appimage_cleanup(vars(&[("PATH", "/usr/bin")]), Some(OsStr::new("/"))).is_empty());
    }
}
