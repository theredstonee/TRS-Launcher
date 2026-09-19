//! Mojang-Metadaten (piston-meta).

pub mod manifest;
pub mod version;

pub use manifest::{ManifestVersion, VersionManifest, VersionType};
pub use version::VersionInfo;

/// IDs aus Metadaten (Versionen, Asset-Indexe, Log-Configs) werden zu Datei-
/// und Ordnernamen – deshalb nur ein enger Zeichensatz.
pub fn is_safe_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id.trim() == id
        && !id.contains("..")
        && id.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+' | ' '))
}
