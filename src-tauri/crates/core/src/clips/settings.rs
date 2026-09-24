//! Einstellungen für Clips & Aufnahme (Teil von `settings.json`, Feld `clips`).

use std::path::Path;

use serde::{Deserialize, Deserializer, Serialize};

use crate::{Error, Result};

pub const MIN_BUFFER_SECONDS: u32 = 15;
pub const MAX_BUFFER_SECONDS: u32 = 120;
pub const MIN_STORAGE_GB: u32 = 1;
pub const MAX_STORAGE_GB: u32 = 2000;

/// Ausgabeauflösung. `Native` = Fenstergröße des Spiels (gerade Pixelzahl).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum ClipResolution {
    #[serde(rename = "native")]
    Native,
    #[default]
    #[serde(rename = "1080p")]
    P1080,
    #[serde(rename = "720p")]
    P720,
}

impl ClipResolution {
    /// Höchste Bildhöhe (größere Fenster werden verkleinert, kleinere bleiben).
    pub fn max_height(self) -> Option<u32> {
        match self {
            Self::Native => None,
            Self::P1080 => Some(1080),
            Self::P720 => Some(720),
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ClipQuality {
    Low,
    #[default]
    Medium,
    High,
}

impl ClipQuality {
    /// Ziel-Bitrate in kbit/s für 1080p bei 60 Bildern/s; skaliert mit Pixeln und Bildrate.
    pub fn base_kbps(self) -> u32 {
        match self {
            Self::Low => 8_000,
            Self::Medium => 15_000,
            Self::High => 30_000,
        }
    }
}

/// Welcher Encoder – `Auto` probiert NVENC → AMF → QSV → x264.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ClipEncoder {
    #[default]
    Auto,
    Nvenc,
    Amf,
    Qsv,
    X264,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ClipSettings {
    /// Standard AUS – erst nach dem Einschalten wird überhaupt aufgenommen.
    pub enabled: bool,
    /// Länge des Sofort-Clips (Ringpuffer), 15–120 s.
    pub buffer_seconds: u32,
    pub resolution: ClipResolution,
    /// 30 oder 60 Bilder/s.
    pub fps: u32,
    pub quality: ClipQuality,
    pub encoder: ClipEncoder,
    /// Systemton (WASAPI-Loopback des Standard-Ausgabegeräts).
    pub system_audio: bool,
    /// Mikrofon (Standard-Aufnahmegerät) – Standard aus.
    pub microphone: bool,
    /// Eigener Speicherort; `None` = `<Launcher-Daten>/clips`.
    pub folder: Option<String>,
    /// Höchstgröße des Clip-Ordners; darüber wandern die ältesten Clips in den Papierkorb.
    pub max_storage_gb: u32,
}

impl Default for ClipSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            buffer_seconds: 30,
            resolution: ClipResolution::default(),
            fps: 60,
            quality: ClipQuality::default(),
            encoder: ClipEncoder::default(),
            system_audio: true,
            microphone: false,
            folder: None,
            max_storage_gb: 20,
        }
    }
}

impl ClipSettings {
    pub fn normalized(mut self) -> Self {
        if self.folder.as_deref().is_some_and(|f| f.trim().is_empty()) {
            self.folder = None;
        }
        if let Some(folder) = &mut self.folder {
            *folder = folder.trim().to_owned();
        }
        self
    }

    pub fn validate(&self) -> Result<()> {
        if !(MIN_BUFFER_SECONDS..=MAX_BUFFER_SECONDS).contains(&self.buffer_seconds) {
            return Err(Error::validation(crate::msg!(
                "clips.bufferRange",
                "Die Clip-Länge muss zwischen {min} und {max} Sekunden liegen",
                min = MIN_BUFFER_SECONDS,
                max = MAX_BUFFER_SECONDS
            )));
        }
        if self.fps != 30 && self.fps != 60 {
            return Err(Error::validation(crate::msg!("clips.fpsInvalid", "Bilder pro Sekunde: nur 30 oder 60")));
        }
        if !(MIN_STORAGE_GB..=MAX_STORAGE_GB).contains(&self.max_storage_gb) {
            return Err(Error::validation(crate::msg!(
                "clips.storageRange",
                "Der Speicherplatz für Clips muss zwischen {min} und {max} GB liegen",
                min = MIN_STORAGE_GB,
                max = MAX_STORAGE_GB
            )));
        }
        if let Some(folder) = &self.folder {
            validate_folder(folder)?;
        }
        Ok(())
    }

    pub fn max_storage_bytes(&self) -> u64 {
        u64::from(self.max_storage_gb) * 1024 * 1024 * 1024
    }
}

/// Eigener Clip-Ordner: absoluter, lokaler Pfad ohne Steuerzeichen.
pub fn validate_folder(folder: &str) -> Result<()> {
    let invalid = || Error::validation(crate::msg!("clips.folderInvalid", "Der Clip-Ordner muss ein vollständiger lokaler Pfad sein"));
    if folder.len() > 400 || folder.chars().any(char::is_control) || folder.starts_with("\\\\") {
        return Err(invalid());
    }
    let path = Path::new(folder);
    if !path.is_absolute() {
        return Err(invalid());
    }
    if path.components().any(|c| matches!(c, std::path::Component::ParentDir)) {
        return Err(invalid());
    }
    Ok(())
}

/// Ein unbekannter Wert (z. B. aus einer neueren Version) setzt nur die Clip-
/// Einstellungen zurück, nicht die ganze `settings.json`.
pub fn lenient<'de, D: Deserializer<'de>>(deserializer: D) -> std::result::Result<ClipSettings, D::Error> {
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(serde_json::from_value(value).unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn standard_ist_aus_und_gueltig() {
        let s = ClipSettings::default();
        assert!(!s.enabled, "Clips müssen standardmäßig aus sein");
        assert!(!s.microphone, "Mikrofon muss standardmäßig aus sein");
        assert_eq!(s.buffer_seconds, 30);
        s.validate().unwrap();
    }

    #[test]
    fn grenzen_werden_geprueft() {
        let mut s = ClipSettings { buffer_seconds: 14, ..Default::default() };
        assert!(s.validate().is_err());
        s.buffer_seconds = 121;
        assert!(s.validate().is_err());
        s.buffer_seconds = 120;
        s.fps = 45;
        assert!(s.validate().is_err());
        s.fps = 30;
        s.max_storage_gb = 0;
        assert!(s.validate().is_err());
        s.max_storage_gb = 5;
        s.validate().unwrap();
    }

    // Windows-Pfade (Laufwerksbuchstaben, UNC) – Clips gibt es nur unter Windows.
    #[cfg(windows)]
    #[test]
    fn ordner_muss_absolut_und_lokal_sein() {
        assert!(validate_folder("clips").is_err());
        assert!(validate_folder("\\\\server\\share").is_err());
        assert!(validate_folder("C:\\Videos\\..\\Windows").is_err());
        validate_folder("D:\\Videos\\TRS Clips").unwrap();
        let s = ClipSettings { folder: Some("   ".into()), ..Default::default() }.normalized();
        assert_eq!(s.folder, None);
    }

    #[test]
    fn unbekannte_werte_setzen_nur_clips_zurueck() {
        #[derive(Deserialize)]
        struct Wrapper {
            #[serde(deserialize_with = "lenient")]
            clips: ClipSettings,
        }
        let w: Wrapper = serde_json::from_str(r#"{"clips":{"enabled":true,"encoder":"zukunft"}}"#).unwrap();
        assert_eq!(w.clips, ClipSettings::default());
        let w: Wrapper = serde_json::from_str(r#"{"clips":{"enabled":true,"resolution":"720p"}}"#).unwrap();
        assert!(w.clips.enabled);
        assert_eq!(w.clips.resolution, ClipResolution::P720);
    }
}
