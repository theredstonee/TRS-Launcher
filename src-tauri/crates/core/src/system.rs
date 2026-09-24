//! Angaben zum System für die Anzeige (z. B. „Windows 11 (24H2, Build 26100)“
//! oder „Arch Linux (Kernel 6.10.2-arch1-1)“).

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
    crate::platform::os_description()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn describes_windows() {
        assert_eq!(describe(Some(26100), Some("24H2")), "Windows 11 (24H2, Build 26100)");
        assert_eq!(describe(Some(19045), None), "Windows 10 (Build 19045)");
        assert_eq!(describe(None, None), "Windows");
        if cfg!(windows) {
            assert!(os_description().starts_with("Windows"));
        } else {
            assert!(!os_description().is_empty());
        }
    }
}
