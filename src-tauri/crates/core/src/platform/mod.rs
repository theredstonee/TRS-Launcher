//! Alles, was vom Betriebssystem abhängt, an einer Stelle: Prozesse starten
//! und wiederfinden, Java-/Natives-Namen, Papierkorb, GPU-Wahl, Systemname
//! und der Schlüssel für die Token-Verschlüsselung.
//!
//! Die übrigen Module rufen nur die Funktionen hier auf – so bleibt der Rest
//! des Kerns frei von `cfg(windows)`-Verzweigungen. Unterstützt werden
//! Windows und Linux; andere Unix-Systeme bekommen die Linux-Variante.

pub mod dns;
pub mod env;

#[cfg(windows)]
mod windows;
#[cfg(windows)]
pub use self::windows::*;

#[cfg(unix)]
mod unix;
#[cfg(unix)]
pub use self::unix::*;

use serde::Serialize;

/// Betriebssystem-Name in Mojangs Regeln (`rules[].os.name`) und im alten
/// Natives-Format (`natives.<os>`).
pub const MOJANG_OS: &str = if cfg!(windows) {
    "windows"
} else if cfg!(target_os = "macos") {
    "osx"
} else {
    "linux"
};

/// Trennzeichen im Java-Classpath.
pub const CLASSPATH_SEPARATOR: &str = if cfg!(windows) { ";" } else { ":" };

/// Java ohne Konsolenfenster (Spielstart). Unter Linux gibt es nur `java`.
pub const JAVA_GUI_BIN: &str = if cfg!(windows) { "javaw.exe" } else { "java" };

/// Java mit Konsole (Forge-Processors brauchen die Ausgabe).
pub const JAVA_CONSOLE_BIN: &str = if cfg!(windows) { "java.exe" } else { "java" };

/// Ist `name` ein Java-Programm dieses Systems (`java.exe`/`javaw.exe` bzw. `java`)?
pub fn is_java_binary_name(name: &str) -> bool {
    if cfg!(windows) {
        name.eq_ignore_ascii_case("java.exe") || name.eq_ignore_ascii_case("javaw.exe")
    } else {
        name == "java"
    }
}

/// Schlüssel in Mojangs Java-Runtime-Liste (`all.json`). `None`: Mojang
/// liefert für diese Plattform keine Runtime – dann muss der Nutzer Java
/// selbst angeben.
pub fn java_runtime_platform() -> Option<&'static str> {
    let arch = std::env::consts::ARCH;
    match (MOJANG_OS, arch) {
        ("windows", "aarch64") => Some("windows-arm64"),
        ("windows", "x86") => Some("windows-x86"),
        ("windows", _) => Some("windows-x64"),
        ("linux", "x86_64") => Some("linux"),
        ("linux", "x86") => Some("linux-i386"),
        ("osx", "aarch64") => Some("mac-os-arm64"),
        ("osx", _) => Some("mac-os"),
        _ => None,
    }
}

/// Classifier der LWJGL-Natives im neuen Format (1.19+): Die Regeln nennen
/// nur das OS, die Architektur steckt im Classifier.
pub fn natives_classifier() -> &'static str {
    match (MOJANG_OS, std::env::consts::ARCH) {
        ("windows", "aarch64") => "natives-windows-arm64",
        ("windows", "x86") => "natives-windows-x86",
        ("windows", _) => "natives-windows",
        ("linux", "aarch64") => "natives-linux-arm64",
        ("linux", "arm") => "natives-linux-arm32",
        ("linux", _) => "natives-linux",
        ("osx", "aarch64") => "natives-macos-arm64",
        _ => "natives-macos",
    }
}

/// Welche Funktionen es auf diesem System gibt – das Frontend blendet den
/// Rest aus.
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Capabilities {
    /// `windows` | `linux` | `macos`
    pub platform: &'static str,
    /// Windows-Firewall-Freigabe für die Java-Runtimes.
    pub firewall: bool,
    /// Gelöschte Screenshots landen im Papierkorb.
    pub trash: bool,
    /// Spiel-Clips aufnehmen (derzeit nur Windows).
    pub clips: bool,
    /// Wie der Launcher sich aktualisiert: `auto` (eingebauter Updater),
    /// `package` (Paketverwaltung: .deb/.rpm/AUR) oder `flatpak`.
    pub updates: &'static str,
}

pub fn capabilities() -> Capabilities {
    Capabilities {
        platform: if cfg!(windows) {
            "windows"
        } else if cfg!(target_os = "macos") {
            "macos"
        } else {
            "linux"
        },
        firewall: cfg!(windows),
        trash: true,
        clips: cfg!(windows),
        updates: update_mode(),
    }
}

/// Unter Linux kann sich nur das AppImage selbst ersetzen; Pakete aus
/// .deb/.rpm/AUR/Flatpak aktualisiert die Paketverwaltung.
fn update_mode() -> &'static str {
    if cfg!(windows) {
        return "auto";
    }
    if std::env::var_os("FLATPAK_ID").is_some() || std::path::Path::new("/.flatpak-info").exists() {
        "flatpak"
    } else if std::env::var_os("APPIMAGE").is_some_and(|p| !p.is_empty()) {
        "auto"
    } else {
        "package"
    }
}

/// `MemTotal:       16314280 kB` aus `/proc/meminfo` → MB.
#[cfg_attr(not(unix), allow(dead_code))]
pub(crate) fn parse_meminfo(text: &str) -> Option<u32> {
    let line = text.lines().find_map(|l| l.strip_prefix("MemTotal:"))?;
    let mut parts = line.split_whitespace();
    let kb: u64 = parts.next()?.parse().ok()?;
    if !matches!(parts.next(), Some("kB") | None) {
        return None;
    }
    u32::try_from(kb / 1024).ok().filter(|mb| *mb > 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn memory_is_detected() {
        assert_eq!(parse_meminfo("MemTotal:       16314280 kB
MemFree: 1 kB"), Some(15931));
        assert_eq!(parse_meminfo("MemFree: 1 kB"), None);
        assert_eq!(parse_meminfo("MemTotal: viel kB"), None);
        assert_eq!(parse_meminfo("MemTotal: 0 kB"), None);
        // Auf dem Test-Rechner gibt es Speicher (Windows und Linux).
        if cfg!(any(windows, target_os = "linux")) {
            assert!(total_memory_mb().is_some_and(|mb| mb >= 512));
        }
    }

    #[test]
    fn host_names_are_consistent() {
        assert!(is_java_binary_name(JAVA_GUI_BIN));
        assert!(is_java_binary_name(JAVA_CONSOLE_BIN));
        assert!(!is_java_binary_name("cmd.exe"));
        assert!(!is_java_binary_name("sh"));
        assert!(natives_classifier().starts_with(&format!("natives-{}", if MOJANG_OS == "osx" { "macos" } else { MOJANG_OS })));
        if cfg!(all(target_os = "linux", target_arch = "x86_64")) {
            assert_eq!(java_runtime_platform(), Some("linux"));
            assert_eq!(CLASSPATH_SEPARATOR, ":");
        }
        if cfg!(all(windows, target_arch = "x86_64")) {
            assert_eq!(java_runtime_platform(), Some("windows-x64"));
            assert_eq!(CLASSPATH_SEPARATOR, ";");
        }
        let caps = capabilities();
        assert_eq!(caps.firewall, cfg!(windows));
        assert!(matches!(caps.updates, "auto" | "package" | "flatpak"));
    }
}
