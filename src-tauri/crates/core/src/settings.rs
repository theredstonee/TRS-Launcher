use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::hooks::{self, EnvVar, LaunchHooks};
use crate::sync::SyncSettings;
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
    /// Spiel im Vollbild starten (`--fullscreen`); Instanzen können abweichen.
    pub fullscreen: bool,
    /// Globale Start-Hooks; Instanzen können eigene setzen.
    pub hooks: LaunchHooks,
    /// Zusätzliche Umgebungsvariablen für Spiel und Hooks.
    pub env: Vec<EnvVar>,
    /// Was zwischen den Instanzen synchronisiert wird.
    pub sync: SyncSettings,
    /// Darstellung, sichtbare Bereiche und Verhalten der Oberfläche.
    pub ui: UiSettings,
    /// Logs dürfen (geschwärzt) auf mclo.gs hochgeladen werden.
    pub allow_log_upload: bool,
    /// Eigene Java-Installationen je Hauptversion; leer = automatisch.
    pub java: JavaPaths,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Theme {
    #[default]
    Dark,
    Oled,
    Light,
    System,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Accent {
    #[default]
    Redstone,
    Lamp,
    Emerald,
    Lapis,
    Amethyst,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Language {
    #[default]
    De,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct UiSettings {
    pub theme: Theme,
    pub accent: Accent,
    /// Unschärfe-Effekte (Backdrop-Blur) – aus spart GPU auf schwachen Rechnern.
    pub advanced_rendering: bool,
    /// Animierte Redstone-Schaltung als Hintergrund aller Seiten.
    pub animated_background: bool,
    pub worlds_tab: bool,
    pub screenshots_tab: bool,
    pub history_tab: bool,
    /// „Zuletzt gespielt“ in der linken Leiste.
    pub sidebar_recent: bool,
    /// Account-Kachel mit Skin in der linken Leiste.
    pub sidebar_account: bool,
    pub hide_right_sidebar: bool,
    pub compact_library: bool,
    pub show_play_time: bool,
    pub language: Language,
}

impl Default for UiSettings {
    fn default() -> Self {
        Self {
            theme: Theme::Dark,
            accent: Accent::Redstone,
            advanced_rendering: true,
            animated_background: true,
            worlds_tab: true,
            screenshots_tab: true,
            history_tab: true,
            sidebar_recent: true,
            sidebar_account: true,
            hide_right_sidebar: false,
            compact_library: false,
            show_play_time: true,
            language: Language::De,
        }
    }
}

/// Java je Hauptversion – Minecraft braucht 8 (bis 1.16), 17 (1.17–1.20.4),
/// 21 (1.20.5–1.21.x) oder 25 (26.x).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct JavaPaths {
    pub java8: Option<String>,
    pub java17: Option<String>,
    pub java21: Option<String>,
    pub java25: Option<String>,
}

impl JavaPaths {
    pub const MAJORS: [u32; 4] = [8, 17, 21, 25];

    /// Welche Einstellung für eine benötigte Java-Hauptversion gilt.
    pub fn slot_for(required_major: u32) -> u32 {
        match required_major {
            0..=8 => 8,
            9..=17 => 17,
            18..=21 => 21,
            _ => 25,
        }
    }

    pub fn get(&self, required_major: u32) -> Option<&str> {
        match Self::slot_for(required_major) {
            8 => self.java8.as_deref(),
            17 => self.java17.as_deref(),
            21 => self.java21.as_deref(),
            _ => self.java25.as_deref(),
        }
    }

    fn all(&self) -> [&Option<String>; 4] {
        [&self.java8, &self.java17, &self.java21, &self.java25]
    }

    fn validate(&self) -> Result<()> {
        self.all().into_iter().flatten().try_for_each(|p| validate_java_path(p))
    }
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
            fullscreen: false,
            hooks: LaunchHooks::default(),
            env: Vec::new(),
            sync: SyncSettings::default(),
            ui: UiSettings::default(),
            allow_log_upload: true,
            java: JavaPaths::default(),
        }
    }
}

impl Settings {
    /// Eine kaputte oder ungültige Datei darf den Start nicht verhindern –
    /// dann gelten die Standardwerte.
    pub async fn load(path: &Path) -> Result<Self> {
        match fsutil::read_json::<Self>(path).await {
            Ok(Some(s)) if s.validate().is_ok() => Ok(s),
            // Nur ein einzelner Wert ungültig (z. B. ein Java-Pfad aus einer
            // älteren Version, die noch nicht so streng prüfte)? Dann nur den
            // zurücksetzen statt alle Einstellungen.
            Ok(Some(s)) if s.clone().repaired().validate().is_ok() => {
                tracing::warn!("settings.json: ungültigen Java-Pfad bzw. Hooks zurückgesetzt");
                Ok(s.repaired())
            }
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

    /// Normalisiert Eingaben (leere Hook-Befehle werden zu `None`).
    pub fn normalized(mut self) -> Self {
        self.hooks = self.hooks.normalized();
        self.env = hooks::normalize_env(self.env);
        self
    }

    fn repaired(mut self) -> Self {
        if self.java_path.as_deref().is_some_and(|p| validate_java_path(p).is_err()) {
            self.java_path = None;
        }
        if self.hooks.validate().is_err() {
            self.hooks = LaunchHooks::default();
        }
        if self.java.validate().is_err() {
            self.java = JavaPaths::default();
        }
        if hooks::validate_env(&self.env).is_err() {
            self.env = Vec::new();
        }
        self
    }

    pub fn validate(&self) -> Result<()> {
        validate_memory(self.max_memory_mb)?;
        self.hooks.validate()?;
        hooks::validate_env(&self.env)?;
        self.java.validate()?;
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

/// Muss als absoluter Pfad auf `java.exe` oder `javaw.exe` zeigen.
pub fn validate_java_path(path: &str) -> Result<()> {
    if path.is_empty() || path.len() > 1024 || path.chars().any(char::is_control) {
        return Err(Error::validation("Java-Pfad ist ungültig"));
    }
    let p = Path::new(path);
    let file = p.file_name().and_then(|n| n.to_str()).unwrap_or_default().to_ascii_lowercase();
    if !p.is_absolute() || !matches!(file.as_str(), "java.exe" | "javaw.exe") {
        return Err(Error::validation("Der Java-Pfad muss auf java.exe oder javaw.exe zeigen"));
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
    fn java_path_must_be_java_exe() {
        assert!(validate_java_path(r"C:\Program Files\Java\bin\javaw.exe").is_ok());
        assert!(validate_java_path(r"C:\jdk\bin\JAVA.EXE").is_ok());
        for bad in [r"C:\Windows\System32\cmd.exe", "javaw.exe", r"C:\jdk\bin\java", "C:\\x\\javaw.exe\n", ""] {
            assert!(validate_java_path(bad).is_err(), "{bad:?}");
        }
    }

    #[tokio::test]
    async fn invalid_java_path_only_resets_that_value() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("settings.json");
        let json = serde_json::json!({ "maxMemoryMb": 6144, "javaPath": "java" });
        tokio::fs::write(&file, json.to_string()).await.unwrap();
        let loaded = Settings::load(&file).await.unwrap();
        assert_eq!(loaded.max_memory_mb, 6144);
        assert_eq!(loaded.java_path, None);
    }

    #[test]
    fn java_slots_and_ui_defaults() {
        assert_eq!(JavaPaths::slot_for(8), 8);
        assert_eq!(JavaPaths::slot_for(16), 17);
        assert_eq!(JavaPaths::slot_for(17), 17);
        assert_eq!(JavaPaths::slot_for(21), 21);
        assert_eq!(JavaPaths::slot_for(25), 25);
        let paths = JavaPaths { java17: Some(r"C:\j17\bin\javaw.exe".into()), ..Default::default() };
        assert_eq!(paths.get(16), Some(r"C:\j17\bin\javaw.exe"));
        assert_eq!(paths.get(21), None);

        let bad = Settings { java: JavaPaths { java8: Some("x".into()), ..Default::default() }, ..Default::default() };
        assert!(bad.validate().is_err());

        // Alte settings.json ohne `ui` bekommt sinnvolle Standardwerte.
        let old: Settings = serde_json::from_str(r#"{"maxMemoryMb":4096}"#).unwrap();
        assert!(old.ui.worlds_tab && old.ui.show_play_time && old.allow_log_upload);
        assert_eq!(serde_json::to_value(Theme::Oled).unwrap(), "oled");
        assert!(serde_json::from_str::<UiSettings>(r#"{"theme":"neon"}"#).is_err());
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
