//! Grafikkarte: erkennen (DXGI) und Windows sagen, dass Minecraft die
//! leistungsstarke Karte nehmen soll („Grafikeinstellungen“ in Windows).
//!
//! Die GPU-Präferenz wird nur für die Java-Runtimes des Launchers gesetzt
//! (nie für fremde Programme), nur unter HKCU und lässt sich rückgängig
//! machen. Die Registry steckt hinter [`GpuPreferences`], damit Tests nichts
//! Echtes schreiben.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

/// Wert für „Hohe Leistung“ unter `UserGpuPreferences`.
pub const HIGH_PERFORMANCE: &str = "GpuPreference=2;";
#[cfg(windows)]
const PREFERENCES_KEY: &str = "Software\\Microsoft\\DirectX\\UserGpuPreferences";
const NVIDIA: u32 = 0x10DE;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Adapter {
    pub name: String,
    pub vendor_id: u32,
    /// Software-Renderer (Microsoft Basic Render Driver).
    pub software: bool,
}

/// Linux: NVIDIA-Karten mit Modellnamen aus `/proc/driver/nvidia/gpus/*/information`
/// (proprietärer Treiber), alle anderen nur mit Hersteller-ID aus `/sys/class/drm`.
#[cfg(not(windows))]
pub fn adapters() -> Vec<Adapter> {
    let mut out = Vec::new();
    if let Ok(gpus) = std::fs::read_dir("/proc/driver/nvidia/gpus") {
        for gpu in gpus.flatten() {
            let info = std::fs::read_to_string(gpu.path().join("information")).unwrap_or_default();
            if let Some(model) = info.lines().find_map(|l| l.strip_prefix("Model:")) {
                out.push(Adapter { name: model.trim().to_owned(), vendor_id: NVIDIA, software: false });
            }
        }
    }
    let named_nvidia = !out.is_empty();
    if let Ok(cards) = std::fs::read_dir("/sys/class/drm") {
        for card in cards.flatten() {
            let name = card.file_name().to_string_lossy().into_owned();
            if !name.strip_prefix("card").is_some_and(|n| !n.is_empty() && n.chars().all(|c| c.is_ascii_digit())) {
                continue;
            }
            let vendor = std::fs::read_to_string(card.path().join("device/vendor")).unwrap_or_default();
            let Ok(vendor_id) = u32::from_str_radix(vendor.trim().trim_start_matches("0x"), 16) else { continue };
            // NVIDIA-Karten stehen schon oben (mit Namen).
            if vendor_id != NVIDIA || !named_nvidia {
                out.push(Adapter { name: format!("{name} ({vendor_id:04x})"), vendor_id, software: false });
            }
        }
    }
    out
}

/// Alle Grafikadapter laut DXGI; leer, wenn die Abfrage scheitert.
#[cfg(windows)]
pub fn adapters() -> Vec<Adapter> {
    use windows::Win32::Graphics::Dxgi::{CreateDXGIFactory1, DXGI_ADAPTER_FLAG_SOFTWARE, IDXGIFactory1};

    // SAFETY: reine COM-Abfragen ohne geteilte Zeiger; Fehler beenden die Schleife.
    unsafe {
        let Ok(factory) = CreateDXGIFactory1::<IDXGIFactory1>() else { return Vec::new() };
        let mut out = Vec::new();
        for index in 0..16 {
            let Ok(adapter) = factory.EnumAdapters1(index) else { break };
            let Ok(desc) = adapter.GetDesc1() else { continue };
            let len = desc.Description.iter().position(|&c| c == 0).unwrap_or(desc.Description.len());
            out.push(Adapter {
                name: String::from_utf16_lossy(&desc.Description[..len]).trim().to_owned(),
                vendor_id: desc.VendorId,
                software: desc.Flags & (DXGI_ADAPTER_FLAG_SOFTWARE.0 as u32) != 0,
            });
        }
        out
    }
}

/// Kann die Karte Mesh-Shader (für Nvidium)? NVIDIA ab Turing: GTX 16xx,
/// alle RTX-Karten (auch Quadro RTX / RTX A…).
pub fn nvidia_mesh_shaders(adapter: &Adapter) -> bool {
    if adapter.vendor_id != NVIDIA || adapter.software {
        return false;
    }
    let name = adapter.name.to_ascii_uppercase();
    name.contains("RTX")
        || name.split_whitespace().collect::<Vec<_>>().windows(2).any(|w| w[0] == "GTX" && w[1].starts_with("16"))
}

/// Gibt es in diesem PC eine Karte für Nvidium? (einmal ermittelt)
pub fn nvidium_capable() -> bool {
    static CAPABLE: OnceLock<bool> = OnceLock::new();
    *CAPABLE.get_or_init(|| adapters().iter().any(nvidia_mesh_shaders))
}

/// Zugriff auf `UserGpuPreferences` (in Tests eine Attrappe).
pub trait GpuPreferences {
    fn get(&self, program: &Path) -> Option<String>;
    fn set(&self, program: &Path, value: &str) -> bool;
    fn remove(&self, program: &Path) -> bool;
}

/// Die echte Registry (HKCU). Unter Linux gibt es sie nicht – dort wählt
/// [`crate::platform::dedicated_gpu_env`] die GPU über PRIME-Variablen.
pub struct WindowsGpuPreferences;

#[cfg(not(windows))]
impl GpuPreferences for WindowsGpuPreferences {
    fn get(&self, _program: &Path) -> Option<String> {
        None
    }
    fn set(&self, _program: &Path, _value: &str) -> bool {
        false
    }
    fn remove(&self, _program: &Path) -> bool {
        false
    }
}

#[cfg(windows)]
impl GpuPreferences for WindowsGpuPreferences {
    fn get(&self, program: &Path) -> Option<String> {
        use windows::Win32::System::Registry::{HKEY_CURRENT_USER, RRF_RT_REG_SZ, RegGetValueW};
        use windows::core::HSTRING;

        let mut buf = [0u16; 128];
        let mut len = (buf.len() * 2) as u32;
        // SAFETY: Puffer und Länge (in Bytes) passen zusammen; Strings sind nullterminiert.
        let status = unsafe {
            RegGetValueW(
                HKEY_CURRENT_USER,
                &HSTRING::from(PREFERENCES_KEY),
                &HSTRING::from(program.as_os_str()),
                RRF_RT_REG_SZ,
                None,
                Some(buf.as_mut_ptr().cast()),
                Some(&mut len),
            )
        };
        if status.is_err() {
            return None;
        }
        let chars = (len as usize / 2).min(buf.len());
        Some(String::from_utf16_lossy(&buf[..chars]).trim_end_matches('\0').to_owned())
    }

    fn set(&self, program: &Path, value: &str) -> bool {
        use windows::Win32::System::Registry::{HKEY_CURRENT_USER, REG_SZ, RegSetKeyValueW};
        use windows::core::HSTRING;

        let data: Vec<u16> = value.encode_utf16().chain(std::iter::once(0)).collect();
        // SAFETY: alle Strings sind nullterminiert, die Datenlänge stimmt in Bytes.
        let status = unsafe {
            RegSetKeyValueW(
                HKEY_CURRENT_USER,
                &HSTRING::from(PREFERENCES_KEY),
                &HSTRING::from(program.as_os_str()),
                REG_SZ.0,
                Some(data.as_ptr().cast()),
                (data.len() * 2) as u32,
            )
        };
        status.is_ok()
    }

    fn remove(&self, program: &Path) -> bool {
        use windows::Win32::System::Registry::{HKEY_CURRENT_USER, RegDeleteKeyValueW};
        use windows::core::HSTRING;

        // SAFETY: nullterminierte Strings; ein fehlender Wert liefert nur einen Fehlercode.
        let status = unsafe {
            RegDeleteKeyValueW(HKEY_CURRENT_USER, &HSTRING::from(PREFERENCES_KEY), &HSTRING::from(program.as_os_str()))
        };
        status.is_ok()
    }
}

/// Liegt das Programm in einer Java-Runtime des Launchers?
pub fn is_own_runtime(java_dir: &Path, program: &Path) -> bool {
    let is_java = program
        .file_name()
        .and_then(|n| n.to_str())
        .is_some_and(|n| n.eq_ignore_ascii_case("java.exe") || n.eq_ignore_ascii_case("javaw.exe"));
    is_java && !program.components().any(|c| matches!(c, std::path::Component::ParentDir)) && program.starts_with(java_dir)
}

/// Vor dem Start: „Hohe Leistung“ für die Runtime des Launchers setzen.
/// Eigene Java-Installationen des Nutzers bleiben unangetastet. Eine Wahl,
/// die der Nutzer in Windows selbst getroffen hat, wird nicht überschrieben.
pub fn prefer_for_launch(store: &dyn GpuPreferences, java_dir: &Path, program: &Path) -> bool {
    if !is_own_runtime(java_dir, program) {
        return false;
    }
    match store.get(program) {
        Some(current) if current == HIGH_PERFORMANCE => true,
        Some(current) if !current.trim().is_empty() => false,
        _ => {
            let ok = store.set(program, HIGH_PERFORMANCE);
            if !ok {
                tracing::warn!("GPU-Präferenz konnte nicht gesetzt werden: {}", program.display());
            }
            ok
        }
    }
}

/// Einstellung abgeschaltet: unsere Einträge wieder entfernen – nur die mit
/// genau unserem Wert, nur für Runtimes des Launchers. Liefert die Anzahl.
pub fn revert(store: &dyn GpuPreferences, java_dir: &Path, programs: &[PathBuf]) -> usize {
    programs
        .iter()
        .filter(|p| is_own_runtime(java_dir, p))
        .filter(|p| store.get(p).as_deref() == Some(HIGH_PERFORMANCE))
        .filter(|p| store.remove(p))
        .count()
}

/// Alle `java.exe`/`javaw.exe` unter dem Java-Ordner des Launchers.
pub fn own_runtimes(java_dir: &Path) -> Vec<PathBuf> {
    fn walk(dir: &Path, depth: u8, out: &mut Vec<PathBuf>) {
        let Ok(entries) = std::fs::read_dir(dir) else { return };
        for entry in entries.flatten() {
            let Ok(kind) = entry.file_type() else { continue };
            let path = entry.path();
            if kind.is_dir() && depth > 0 {
                walk(&path, depth - 1, out);
            } else if kind.is_file()
                && path.file_name().and_then(|n| n.to_str()).is_some_and(|n| {
                    n.eq_ignore_ascii_case("java.exe") || n.eq_ignore_ascii_case("javaw.exe")
                })
            {
                out.push(path);
            }
        }
    }
    let mut out = Vec::new();
    walk(java_dir, 5, &mut out);
    out
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;
    use std::collections::HashMap;

    use super::*;

    #[derive(Default)]
    #[cfg_attr(not(windows), allow(dead_code))]
    struct FakeRegistry(RefCell<HashMap<PathBuf, String>>);

    impl GpuPreferences for FakeRegistry {
        fn get(&self, program: &Path) -> Option<String> {
            self.0.borrow().get(program).cloned()
        }
        fn set(&self, program: &Path, value: &str) -> bool {
            self.0.borrow_mut().insert(program.to_owned(), value.to_owned());
            true
        }
        fn remove(&self, program: &Path) -> bool {
            self.0.borrow_mut().remove(program).is_some()
        }
    }

    fn adapter(name: &str, vendor_id: u32) -> Adapter {
        Adapter { name: name.into(), vendor_id, software: false }
    }

    #[test]
    fn detects_mesh_shader_cards() {
        for yes in ["NVIDIA GeForce RTX 4070", "NVIDIA GeForce GTX 1660 SUPER", "NVIDIA GeForce GTX 1650", "NVIDIA RTX A4000", "Quadro RTX 5000"] {
            assert!(nvidia_mesh_shaders(&adapter(yes, NVIDIA)), "{yes}");
        }
        for no in ["NVIDIA GeForce GTX 1080 Ti", "NVIDIA GeForce GTX 970", "NVIDIA GeForce MX150"] {
            assert!(!nvidia_mesh_shaders(&adapter(no, NVIDIA)), "{no}");
        }
        assert!(!nvidia_mesh_shaders(&adapter("AMD Radeon RX 7900 XTX", 0x1002)));
        assert!(!nvidia_mesh_shaders(&Adapter { software: true, ..adapter("NVIDIA GeForce RTX 3060", NVIDIA) }));
    }

    // Windows-Pfade und Registry-Semantik – unter Linux gibt es beides nicht.
    #[cfg(windows)]
    #[test]
    fn only_own_runtimes_get_a_preference() {
        let java_dir = Path::new(r"C:\trs\java");
        let own = java_dir.join(r"java-runtime-delta\bin\javaw.exe");
        let foreign = PathBuf::from(r"C:\Program Files\Java\jdk-21\bin\javaw.exe");
        let reg = FakeRegistry::default();

        assert!(prefer_for_launch(&reg, java_dir, &own));
        assert_eq!(reg.get(&own).as_deref(), Some(HIGH_PERFORMANCE));
        assert!(!prefer_for_launch(&reg, java_dir, &foreign));
        assert!(reg.get(&foreign).is_none());
        assert!(!is_own_runtime(java_dir, &java_dir.join(r"..\evil\javaw.exe")));
        assert!(!is_own_runtime(java_dir, &java_dir.join("cmd.exe")));

        // Eigene Wahl in Windows („Energiesparen“) bleibt.
        let chosen = java_dir.join(r"java-runtime-gamma\bin\javaw.exe");
        reg.set(&chosen, "GpuPreference=1;");
        assert!(!prefer_for_launch(&reg, java_dir, &chosen));
        assert_eq!(reg.get(&chosen).as_deref(), Some("GpuPreference=1;"));

        // Abschalten entfernt nur unsere Einträge.
        reg.set(&foreign, HIGH_PERFORMANCE);
        assert_eq!(revert(&reg, java_dir, &[own.clone(), chosen.clone(), foreign.clone()]), 1);
        assert!(reg.get(&own).is_none());
        assert_eq!(reg.get(&chosen).as_deref(), Some("GpuPreference=1;"));
        assert_eq!(reg.get(&foreign).as_deref(), Some(HIGH_PERFORMANCE));
    }

    #[test]
    fn finds_runtimes_on_disk() {
        let dir = tempfile::tempdir().unwrap();
        let bin = dir.path().join("java-runtime-delta").join("windows-x64").join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        std::fs::write(bin.join("javaw.exe"), b"").unwrap();
        std::fs::write(bin.join("java.exe"), b"").unwrap();
        std::fs::write(bin.join("keytool.exe"), b"").unwrap();
        let mut found = own_runtimes(dir.path());
        found.sort();
        assert_eq!(found, [bin.join("java.exe"), bin.join("javaw.exe")]);
    }
}
