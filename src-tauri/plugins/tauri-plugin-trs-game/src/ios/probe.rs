//! Gerätedaten, die das Swift-Plugin (`probe`) liefert.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct DeviceProbe {
    /// `libtrsengine.dylib` liegt in der App und ließ sich laden.
    pub engine_installed: bool,
    /// In diesem Prozess lief schon eine JVM (lässt sich nicht neu starten).
    pub engine_used: bool,
    pub jit: JitProbe,
    pub entitlements: Entitlements,
    pub memory: MemoryProbe,
    /// z. B. `17.5.1`
    pub os_version: String,
    pub screen: ScreenProbe,
    /// Sichere Ränder in Pixeln (links, oben, rechts, unten), Querformat.
    pub safe_insets_px: [u32; 4],
    /// Pfad der `.app`.
    pub bundle_path: String,
    /// `Documents` der App (für resolv.conf, JIT-Skript, Logs).
    pub home_dir: String,
    /// IANA-Zeitzone, z. B. `Europe/Berlin`.
    pub time_zone: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct JitProbe {
    /// Ergebnis von `isJITEnabled(true)` aus der Engine.
    pub enabled: bool,
    /// `CS_DEBUGGED` ist gesetzt (Debugger war mal angehängt).
    pub cs_debugged: bool,
    /// Bits aus `DeviceGetJITFlags` (siehe `jit::FLAG_*`).
    pub flags: u32,
    /// Ein Debugger hängt gerade am Prozess.
    pub debugger_attached: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Entitlements {
    pub get_task_allow: bool,
    pub increased_memory_limit: bool,
    pub extended_virtual_addressing: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct MemoryProbe {
    /// Physischer Speicher des Geräts.
    pub physical_mb: u64,
    /// `os_proc_available_memory()` – was die App noch belegen darf (0 = unbekannt).
    pub available_mb: u64,
    /// Größter zusammenhängender Adressbereich, der sich reservieren ließ (0 = unbekannt).
    pub max_contiguous_mb: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ScreenProbe {
    pub width_px: u32,
    pub height_px: u32,
    pub scale: f64,
    pub max_fps: u32,
}

impl DeviceProbe {
    /// Hauptversion von iOS (0 = unbekannt).
    pub fn os_major(&self) -> u32 {
        self.os_version.split('.').next().and_then(|m| m.trim().parse().ok()).unwrap_or(0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_swift_probe() {
        let p: DeviceProbe = serde_json::from_str(
            r#"{"engineInstalled":true,"jit":{"enabled":false,"csDebugged":true,"flags":7,"debuggerAttached":false},
                "entitlements":{"increasedMemoryLimit":true},"memory":{"physicalMb":6144,"availableMb":3000},
                "osVersion":"26.0.1","screen":{"widthPx":2556,"heightPx":1179,"scale":3.0,"maxFps":120},
                "safeInsetsPx":[177,0,177,63],"bundlePath":"/a/TRS.app","homeDir":"/h/Documents","timeZone":"Europe/Berlin"}"#,
        )
        .unwrap();
        assert!(p.engine_installed);
        assert_eq!(p.jit.flags, 7);
        assert!(p.entitlements.increased_memory_limit);
        assert!(!p.entitlements.extended_virtual_addressing);
        assert_eq!(p.os_major(), 26);
        assert_eq!(p.safe_insets_px, [177, 0, 177, 63]);
    }

    #[test]
    fn os_major_fallback() {
        let p = DeviceProbe { os_version: "x".into(), ..Default::default() };
        assert_eq!(p.os_major(), 0);
    }
}
