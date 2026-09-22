//! Angaben zum System für die Anzeige (z. B. „Windows 11 (24H2, Build 26100)“).

use windows::Win32::System::Registry::{HKEY_LOCAL_MACHINE, RRF_RT_REG_SZ, RegGetValueW};
use windows::core::{HSTRING, PCWSTR};

fn read_string(name: &str) -> Option<String> {
    let key = HSTRING::from("SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion");
    let value = HSTRING::from(name);
    let mut buf = [0u16; 128];
    let mut len = (buf.len() * 2) as u32;
    // SAFETY: Puffer und Länge (in Bytes) passen zusammen; Strings sind nullterminiert.
    let status = unsafe {
        RegGetValueW(
            HKEY_LOCAL_MACHINE,
            PCWSTR(key.as_ptr()),
            PCWSTR(value.as_ptr()),
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
    let text = String::from_utf16_lossy(&buf[..chars]);
    let text = text.trim_end_matches('\0').trim().to_owned();
    (!text.is_empty()).then_some(text)
}

/// Build-Nummer ab 22000 ist Windows 11 – `ProductName` sagt dort noch „10“.
pub fn describe(build: Option<u32>, display_version: Option<&str>) -> String {
    let name = match build {
        Some(b) if b >= 22_000 => "Windows 11",
        Some(_) => "Windows 10",
        None => "Windows",
    };
    match (display_version, build) {
        (Some(v), Some(b)) => format!("{name} ({v}, Build {b})"),
        (None, Some(b)) => format!("{name} (Build {b})"),
        _ => name.to_owned(),
    }
}

pub fn os_description() -> String {
    let build = read_string("CurrentBuild").and_then(|b| b.parse().ok());
    let display = read_string("DisplayVersion").filter(|v| v.len() <= 16 && v.chars().all(|c| c.is_ascii_alphanumeric()));
    describe(build, display.as_deref())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn describes_windows() {
        assert_eq!(describe(Some(26100), Some("24H2")), "Windows 11 (24H2, Build 26100)");
        assert_eq!(describe(Some(19045), None), "Windows 10 (Build 19045)");
        assert_eq!(describe(None, None), "Windows");
        assert!(os_description().starts_with("Windows"));
    }
}
