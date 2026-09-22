use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::{Error, Result, fsutil};

pub const MIN_MEMORY_MB: u32 = 512;
pub const MAX_MEMORY_MB: u32 = 131_072;
pub const MAX_JVM_ARGS_LEN: usize = 4096;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Resolution {
    pub width: u32,
    pub height: u32,
}

impl Resolution {
    pub fn validate(&self) -> Result<()> {
        if !(320..=16_384).contains(&self.width) || !(240..=16_384).contains(&self.height) {
            return Err(Error::validation("Auflösung liegt außerhalb des erlaubten Bereichs"));
        }
        Ok(())
    }
}

/// Globale Einstellungen; Instanzen können einzelne Werte überschreiben.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub min_memory_mb: u32,
    pub max_memory_mb: u32,
    /// `None` = passende Runtime automatisch wählen/installieren.
    pub java_path: Option<String>,
    pub jvm_args: String,
    pub resolution: Resolution,
    pub concurrent_downloads: u8,
    pub close_on_launch: bool,
    pub show_snapshots: bool,
    /// Windows soll dem Spiel die leistungsstarke Grafikkarte geben.
    pub prefer_dedicated_gpu: bool,
    /// Netzwerkzugriff für neue Java-Versionen automatisch freigeben (eine Admin-Abfrage).
    pub auto_firewall: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            min_memory_mb: 512,
            max_memory_mb: 4096,
            java_path: None,
            jvm_args: String::new(),
            resolution: Resolution { width: 1280, height: 720 },
            concurrent_downloads: 8,
            close_on_launch: false,
            show_snapshots: false,
            prefer_dedicated_gpu: true,
            auto_firewall: true,
        }
    }
}

impl Settings {
    /// Eine kaputte oder ungültige Datei darf den Start nicht verhindern –
    /// dann gelten die Standardwerte.
    pub async fn load(path: &Path) -> Result<Self> {
        match fsutil::read_json::<Self>(path).await {
            Ok(Some(s)) if s.validate().is_ok() => Ok(s),
            Ok(Some(_)) => {
                tracing::warn!("settings.json enthält ungültige Werte – verwende Standardwerte");
                Ok(Self::default())
            }
            Ok(None) => Ok(Self::default()),
            Err(Error::Json { .. }) => {
                tracing::warn!("settings.json ist beschädigt – verwende Standardwerte");
                Ok(Self::default())
            }
            Err(e) => Err(e),
        }
    }

    pub async fn save(&self, path: &Path) -> Result<()> {
        fsutil::write_json(path, self).await
    }

    pub fn validate(&self) -> Result<()> {
        validate_memory(self.max_memory_mb)?;
        if self.min_memory_mb < 128 || self.min_memory_mb > self.max_memory_mb {
            return Err(Error::validation(
                "Minimaler Arbeitsspeicher muss zwischen 128 MB und dem Maximum liegen",
            ));
        }
        if !(1..=64).contains(&self.concurrent_downloads) {
            return Err(Error::validation("Parallele Downloads müssen zwischen 1 und 64 liegen"));
        }
        validate_jvm_args(&self.jvm_args)?;
        if let Some(p) = &self.java_path {
            validate_java_path(p)?;
        }
        self.resolution.validate()
    }
}

pub fn validate_memory(mb: u32) -> Result<()> {
    if !(MIN_MEMORY_MB..=MAX_MEMORY_MB).contains(&mb) {
        return Err(Error::validation(format!(
            "Arbeitsspeicher muss zwischen {MIN_MEMORY_MB} und {MAX_MEMORY_MB} MB liegen"
        )));
    }
    Ok(())
}

pub fn validate_jvm_args(args: &str) -> Result<()> {
    if args.len() > MAX_JVM_ARGS_LEN || args.contains(['\0', '\n', '\r']) {
        return Err(Error::validation("JVM-Argumente sind ungültig oder zu lang"));
    }
    Ok(())
}

pub fn validate_java_path(path: &str) -> Result<()> {
    if path.is_empty() || path.len() > 1024 || path.contains('\0') {
        return Err(Error::validation("Java-Pfad ist ungültig"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_valid() {
        Settings::default().validate().unwrap();
    }

    #[test]
    fn rejects_min_above_max() {
        let s = Settings { min_memory_mb: 8192, max_memory_mb: 4096, ..Default::default() };
        assert!(s.validate().is_err());
    }

    #[tokio::test]
    async fn corrupt_file_falls_back_to_defaults() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("settings.json");
        tokio::fs::write(&file, b"{ not json").await.unwrap();
        assert_eq!(Settings::load(&file).await.unwrap(), Settings::default());
    }

    #[tokio::test]
    async fn roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("settings.json");
        let s = Settings { max_memory_mb: 6144, show_snapshots: true, ..Default::default() };
        s.save(&file).await.unwrap();
        assert_eq!(Settings::load(&file).await.unwrap(), s);
    }
}
