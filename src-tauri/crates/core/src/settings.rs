use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::clips::settings::ClipSettings;
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
            return Err(Error::validation(crate::msg!(
                "settings.resolutionRange",
                "Auflösung liegt außerhalb des erlaubten Bereichs"
            )));
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
    /// FPS-Boost für den Start: abgestimmte GC-Flags je Java-Version und
    /// Xms = Xmx. Eigene JVM-Argumente gewinnen bei Überschneidungen.
    /// Instanzen können abweichen.
    pub performance_tuning: bool,
    /// Spiel mit Prozesspriorität „Höher als normal“ starten.
    pub high_priority: bool,
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
    /// Discord-Status („Spielt TRS Launcher“) zeigen – ab Werk an.
    pub discord_presence: bool,
    /// Eigene Java-Installationen je Hauptversion; leer = automatisch.
    pub java: JavaPaths,
    /// Clips & Aufnahme (Standard aus).
    #[serde(deserialize_with = "crate::clips::settings::lenient")]
    pub clips: ClipSettings,
    /// Eigene Skins, eigene Presets sowie Theme, Akzentfarbe und Sprache mit
    /// dem TRS-Konto abgleichen – nur mit eingeschalteten TRS-Diensten. Ab
    /// Werk an; ältere Dateien ohne Feld ebenfalls an.
    pub trs_sync: bool,
    /// Benachrichtigungen aus „Sozial“ (Nachrichten, Einladungen, Anfragen …).
    pub social: SocialSettings,
}

/// Ecken für Benachrichtigungen.
pub const TOAST_CORNERS: [&str; 4] = ["top-right", "top-left", "bottom-right", "bottom-left"];

/// Benachrichtigungen aus „Sozial“ – alles lokal, nichts geht an den Server.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SocialSettings {
    /// Benachrichtigungen im Launcher überhaupt zeigen.
    pub toasts: bool,
    /// Ecke: `top-right`, `top-left`, `bottom-right`, `bottom-left`.
    pub corner: String,
    /// Anzeigedauer in Sekunden (3–10).
    pub duration_secs: u8,
    /// Kurzer Ton bei einer Benachrichtigung.
    pub sound: bool,
    /// Nicht stören (von Hand): keine Benachrichtigungen, nur Zähler.
    pub do_not_disturb: bool,
    /// Automatisch still, solange eine Vollbild-Anwendung (z. B. das Spiel) läuft.
    pub quiet_in_fullscreen: bool,
    /// Ist das Launcher-Fenster nicht im Vordergrund: Windows-Benachrichtigung.
    pub native: bool,
    /// Direkt aus der Benachrichtigung antworten.
    pub quick_reply: bool,
    pub messages: bool,
    pub invites: bool,
    pub friend_requests: bool,
    pub cape_offers: bool,
    pub friend_online: bool,
}

impl Default for SocialSettings {
    fn default() -> Self {
        Self {
            toasts: true,
            corner: "top-right".into(),
            duration_secs: 5,
            sound: true,
            do_not_disturb: false,
            quiet_in_fullscreen: true,
            native: true,
            quick_reply: true,
            messages: true,
            invites: true,
            friend_requests: true,
            cape_offers: true,
            friend_online: true,
        }
    }
}

impl SocialSettings {
    /// Unbekannte Ecke → oben rechts, Dauer auf 3–10 s begrenzt.
    pub fn normalized(mut self) -> Self {
        if !TOAST_CORNERS.contains(&self.corner.as_str()) {
            self.corner = "top-right".into();
        }
        self.duration_secs = self.duration_secs.clamp(3, 10);
        self
    }
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

/// Animationen der Oberfläche (Redstone-Hintergrund, Lauflichter, Übergänge).
///
/// Ab Werk `Full`: Windows meldet „Bewegung reduzieren“ schon, wenn nur die
/// Animationseffekte aus sind (Leistungsoptionen, Remotedesktop, Tuning-Tools) –
/// der Launcher stand dann still, obwohl niemand das wollte. `System` folgt
/// weiter dieser Einstellung, `Reduced` zeigt immer Standbilder.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Motion {
    #[default]
    Full,
    System,
    Reduced,
}

/// Ein unbekannter Wert (z. B. aus einer neueren Version) setzt nur die
/// Animationen auf den Standard zurück, nicht die ganze `settings.json`.
fn lenient_motion<'de, D: serde::Deserializer<'de>>(deserializer: D) -> std::result::Result<Motion, D::Error> {
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(serde_json::from_value(value).unwrap_or_default())
}

/// Sprache der Oberfläche. Neue Installationen starten auf Englisch (der
/// Einrichtungs-Assistent schlägt die Windows-Sprache vor); bestehende
/// Einstellungen ohne Sprachfeld bleiben Deutsch (siehe [`Settings::load`]).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum Language {
    #[default]
    #[serde(rename = "en")]
    En,
    #[serde(rename = "de")]
    De,
    #[serde(rename = "es")]
    Es,
    #[serde(rename = "fr")]
    Fr,
    #[serde(rename = "pl")]
    Pl,
    #[serde(rename = "pt-BR")]
    PtBr,
    #[serde(rename = "tr")]
    Tr,
    #[serde(rename = "nl")]
    Nl,
}

impl Language {
    pub const ALL: [Language; 8] = [Self::En, Self::De, Self::Es, Self::Fr, Self::Pl, Self::PtBr, Self::Tr, Self::Nl];

    /// BCP-47-Code wie in `app/locales/<code>.json`.
    pub fn code(self) -> &'static str {
        match self {
            Self::En => "en",
            Self::De => "de",
            Self::Es => "es",
            Self::Fr => "fr",
            Self::Pl => "pl",
            Self::PtBr => "pt-BR",
            Self::Tr => "tr",
            Self::Nl => "nl",
        }
    }
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
    /// Animationen immer, wie im System eingestellt oder reduziert.
    #[serde(deserialize_with = "lenient_motion")]
    pub motion: Motion,
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
            motion: Motion::Full,
            worlds_tab: true,
            screenshots_tab: true,
            history_tab: true,
            sidebar_recent: true,
            sidebar_account: true,
            hide_right_sidebar: false,
            compact_library: false,
            show_play_time: true,
            language: Language::En,
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
            performance_tuning: true,
            high_priority: false,
            auto_firewall: true,
            fullscreen: false,
            hooks: LaunchHooks::default(),
            env: Vec::new(),
            sync: SyncSettings::default(),
            ui: UiSettings::default(),
            allow_log_upload: true,
            discord_presence: true,
            java: JavaPaths::default(),
            clips: ClipSettings::default(),
            trs_sync: true,
            social: SocialSettings::default(),
        }
    }
}

/// Vorschlag für den maximalen Arbeitsspeicher je nach eingebautem RAM.
/// Rechner melden etwas weniger als auf dem Aufkleber steht (die Grafik
/// reserviert einen Teil) – deshalb die Schwellen knapp darunter.
pub fn recommended_memory_mb(total_mb: Option<u32>) -> u32 {
    match total_mb {
        None => 4096,
        Some(total) if total >= 15 * 1024 => 6144,
        Some(total) if total >= 7 * 1024 => 4096,
        // Die Hälfte, auf 256 MB abgerundet – aber nie unter 2 GB.
        Some(total) => (total / 2 / 256 * 256).max(2048),
    }
}

impl Settings {
    /// Standardwerte für eine neue Installation: der Arbeitsspeicher passt
    /// sich an diesen PC an. Bestehende Einstellungen bleiben unberührt.
    pub fn for_this_pc() -> Self {
        Self { max_memory_mb: recommended_memory_mb(crate::platform::total_memory_mb()), ..Self::default() }
    }

    /// Eine kaputte oder ungültige Datei darf den Start nicht verhindern –
    /// dann gelten die Standardwerte.
    pub async fn load(path: &Path) -> Result<Self> {
        match fsutil::read_json::<serde_json::Value>(path).await {
            Ok(Some(value)) => Ok(Self::from_value(migrate(value))),
            // Noch keine Datei: neue Installation.
            Ok(None) => Ok(Self::for_this_pc()),
            Err(Error::Json { .. }) => {
                tracing::warn!("settings.json ist beschädigt – verwende Standardwerte");
                Ok(Self::default())
            }
            Err(e) => Err(e),
        }
    }

    fn from_value(value: serde_json::Value) -> Self {
        match serde_json::from_value::<Self>(value).map(|s| Self { social: s.social.clone().normalized(), ..s }) {
            Ok(s) if s.validate().is_ok() => s,
            // Nur ein einzelner Wert ungültig (z. B. ein Java-Pfad aus einer
            // älteren Version, die noch nicht so streng prüfte)? Dann nur den
            // zurücksetzen statt alle Einstellungen.
            Ok(s) if s.clone().repaired().validate().is_ok() => {
                tracing::warn!("settings.json: ungültigen Java-Pfad bzw. Hooks zurückgesetzt");
                s.repaired()
            }
            Ok(_) => {
                tracing::warn!("settings.json enthält ungültige Werte – verwende Standardwerte");
                Self::default()
            }
            Err(e) => {
                tracing::warn!("settings.json ist beschädigt ({e}) – verwende Standardwerte");
                Self::default()
            }
        }
    }

    pub async fn save(&self, path: &Path) -> Result<()> {
        fsutil::write_json(path, self).await
    }

    /// Normalisiert Eingaben (leere Hook-Befehle werden zu `None`).
    pub fn normalized(mut self) -> Self {
        self.hooks = self.hooks.normalized();
        self.env = hooks::normalize_env(self.env);
        self.clips = self.clips.normalized();
        self.social = self.social.normalized();
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
        if self.clips.validate().is_err() {
            self.clips = ClipSettings::default();
        }
        self
    }

    pub fn validate(&self) -> Result<()> {
        validate_memory(self.max_memory_mb)?;
        self.hooks.validate()?;
        hooks::validate_env(&self.env)?;
        self.java.validate()?;
        self.clips.validate()?;
        if self.min_memory_mb < 128 || self.min_memory_mb > self.max_memory_mb {
            return Err(Error::validation(crate::msg!(
                "settings.minMemoryRange",
                "Minimaler Arbeitsspeicher muss zwischen 128 MB und dem Maximum liegen"
            )));
        }
        if !(1..=64).contains(&self.concurrent_downloads) {
            return Err(Error::validation(crate::msg!(
                "settings.downloadsRange",
                "Parallele Downloads müssen zwischen 1 und 64 liegen"
            )));
        }
        if !(3..=10).contains(&self.social.duration_secs) || !TOAST_CORNERS.contains(&self.social.corner.as_str()) {
            return Err(Error::validation(crate::msg!(
                "settings.socialToasts",
                "Benachrichtigungen: Dauer 3 bis 10 Sekunden, Ecke oben/unten links/rechts."
            )));
        }
        validate_jvm_args(&self.jvm_args)?;
        if let Some(p) = &self.java_path {
            validate_java_path(p)?;
        }
        self.resolution.validate()
    }
}

/// Bestehende Einstellungen stammen aus der Zeit, als der Launcher nur
/// Deutsch konnte: Fehlt die Sprache, bleibt es Deutsch. Unbekannte Sprachen
/// (z. B. aus einer neueren Version) fallen auf Englisch zurück, statt die
/// ganze Datei zu verwerfen.
fn migrate(mut value: serde_json::Value) -> serde_json::Value {
    let Some(root) = value.as_object_mut() else { return value };
    let ui = root.entry("ui").or_insert_with(|| serde_json::json!({}));
    if let Some(ui) = ui.as_object_mut() {
        let known = ui
            .get("language")
            .and_then(|l| l.as_str())
            .map(|code| Language::ALL.iter().any(|l| l.code() == code));
        match known {
            None => {
                ui.insert("language".into(), Language::De.code().into());
            }
            Some(false) => {
                ui.insert("language".into(), Language::En.code().into());
            }
            Some(true) => {}
        }
    }
    value
}

pub fn validate_memory(mb: u32) -> Result<()> {
    if !(MIN_MEMORY_MB..=MAX_MEMORY_MB).contains(&mb) {
        return Err(Error::validation(crate::msg!(
            "settings.memoryRange",
            "Arbeitsspeicher muss zwischen {min} und {max} MB liegen",
            min = MIN_MEMORY_MB,
            max = MAX_MEMORY_MB
        )));
    }
    Ok(())
}

pub fn validate_jvm_args(args: &str) -> Result<()> {
    if args.len() > MAX_JVM_ARGS_LEN || args.contains(['\0', '\n', '\r']) {
        return Err(Error::validation(crate::msg!(
            "settings.jvmArgsInvalid",
            "JVM-Argumente sind ungültig oder zu lang"
        )));
    }
    Ok(())
}

/// Muss als absoluter Pfad auf `java.exe` oder `javaw.exe` zeigen (Linux: `…/java`).
pub fn validate_java_path(path: &str) -> Result<()> {
    if path.is_empty() || path.len() > 1024 || path.chars().any(char::is_control) {
        return Err(Error::validation(crate::msg!("settings.javaPathInvalid", "Java-Pfad ist ungültig")));
    }
    let p = Path::new(path);
    let file = p.file_name().and_then(|n| n.to_str()).unwrap_or_default();
    if !p.is_absolute() || !crate::platform::is_java_binary_name(file) {
        return Err(Error::validation(crate::msg!(
            "settings.javaPathNotJava",
            "Der Java-Pfad muss auf java.exe oder javaw.exe zeigen"
        )));
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
        if cfg!(windows) {
            assert!(validate_java_path(r"C:\Program Files\Java\bin\javaw.exe").is_ok());
            assert!(validate_java_path(r"C:\jdk\bin\JAVA.EXE").is_ok());
            for bad in [r"C:\Windows\System32\cmd.exe", "javaw.exe", r"C:\jdk\bin\java", "C:\\x\\javaw.exe\n", ""] {
                assert!(validate_java_path(bad).is_err(), "{bad:?}");
            }
        } else {
            assert!(validate_java_path("/usr/lib/jvm/java-21-openjdk/bin/java").is_ok());
            for bad in ["/bin/sh", "java", "/usr/bin/javaw.exe", "/usr/bin/java\n", "", "/usr/bin/JAVA"] {
                assert!(validate_java_path(bad).is_err(), "{bad:?}");
            }
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
        // Ohne Feld (ältere Versionen) ist der Discord-Status an.
        assert!(old.discord_presence);
        let off: Settings = serde_json::from_str(r#"{"discordPresence":false}"#).unwrap();
        assert!(!off.discord_presence);
        // TRS-Synchronisation: ohne Feld (ältere Versionen) an, abschaltbar.
        assert!(old.trs_sync && Settings::default().trs_sync);
        let off: Settings = serde_json::from_str(r#"{"trsSync":false}"#).unwrap();
        assert!(!off.trs_sync);
        assert_eq!(serde_json::to_value(Theme::Oled).unwrap(), "oled");
        assert!(serde_json::from_str::<UiSettings>(r#"{"theme":"neon"}"#).is_err());
    }

    #[test]
    fn memory_follows_the_pc_only_for_new_installs() {
        assert_eq!(recommended_memory_mb(Some(32 * 1024)), 6144);
        assert_eq!(recommended_memory_mb(Some(15_931)), 6144, "16-GB-Rechner melden etwas weniger");
        assert_eq!(recommended_memory_mb(Some(7_900)), 4096);
        assert_eq!(recommended_memory_mb(Some(6_000)), 2816);
        assert_eq!(recommended_memory_mb(Some(3_000)), 2048);
        assert_eq!(recommended_memory_mb(None), 4096);
        let fresh = Settings::for_this_pc();
        assert_eq!(fresh.max_memory_mb, recommended_memory_mb(crate::platform::total_memory_mb()));
        fresh.validate().unwrap();
    }

    #[tokio::test]
    async fn existing_memory_value_is_kept() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("settings.json");
        tokio::fs::write(&file, r#"{"maxMemoryMb":3072}"#).await.unwrap();
        assert_eq!(Settings::load(&file).await.unwrap().max_memory_mb, 3072);
        // Ohne Datei: Vorschlag für diesen PC.
        let missing = dir.path().join("neu.json");
        assert_eq!(Settings::load(&missing).await.unwrap().max_memory_mb, Settings::for_this_pc().max_memory_mb);
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
    async fn language_migration() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("settings.json");

        // Neue Installation: Englisch.
        assert_eq!(Settings::load(&file).await.unwrap().ui.language, Language::En);

        // Alte Datei ohne `ui` bzw. ohne Sprache: bleibt Deutsch.
        tokio::fs::write(&file, r#"{"maxMemoryMb":6144}"#).await.unwrap();
        let old = Settings::load(&file).await.unwrap();
        assert_eq!((old.ui.language, old.max_memory_mb), (Language::De, 6144));
        tokio::fs::write(&file, r#"{"ui":{"theme":"light"}}"#).await.unwrap();
        let old = Settings::load(&file).await.unwrap();
        assert_eq!((old.ui.language, old.ui.theme), (Language::De, Theme::Light));

        // Bisher gespeichertes "de" bleibt, neue Sprachen werden gelesen.
        tokio::fs::write(&file, r#"{"ui":{"language":"de"}}"#).await.unwrap();
        assert_eq!(Settings::load(&file).await.unwrap().ui.language, Language::De);
        tokio::fs::write(&file, r#"{"ui":{"language":"pt-BR","accent":"lapis"}}"#).await.unwrap();
        let s = Settings::load(&file).await.unwrap();
        assert_eq!((s.ui.language, s.ui.accent), (Language::PtBr, Accent::Lapis));

        // Unbekannte Sprache verwirft nicht die übrigen Einstellungen.
        tokio::fs::write(&file, r#"{"maxMemoryMb":8192,"ui":{"language":"xx"}}"#).await.unwrap();
        let s = Settings::load(&file).await.unwrap();
        assert_eq!((s.ui.language, s.max_memory_mb), (Language::En, 8192));

        // Nach dem Speichern steht die Sprache in der Datei.
        let s = Settings { ui: UiSettings { language: Language::Es, ..Default::default() }, ..Default::default() };
        s.save(&file).await.unwrap();
        assert_eq!(Settings::load(&file).await.unwrap().ui.language, Language::Es);
        for lang in Language::ALL {
            assert_eq!(serde_json::to_value(lang).unwrap(), lang.code());
        }
    }

    #[tokio::test]
    async fn motion_defaults_to_full_and_survives_unknown_values() {
        assert_eq!(UiSettings::default().motion, Motion::Full);
        // Ältere Dateien ohne Feld: Animationen an (nicht dem System folgen).
        let old: Settings = serde_json::from_str(r#"{"ui":{"theme":"light"}}"#).unwrap();
        assert_eq!((old.ui.motion, old.ui.theme), (Motion::Full, Theme::Light));
        for (json, motion) in [("full", Motion::Full), ("system", Motion::System), ("reduced", Motion::Reduced)] {
            assert_eq!(serde_json::to_value(motion).unwrap(), json);
            let ui: UiSettings = serde_json::from_value(serde_json::json!({ "motion": json })).unwrap();
            assert_eq!(ui.motion, motion);
        }

        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("settings.json");
        // Unbekannter Wert (neuere Version) verwirft nicht die übrigen Einstellungen.
        tokio::fs::write(&file, r#"{"maxMemoryMb":8192,"ui":{"motion":"wobbly","accent":"lapis","language":"de"}}"#)
            .await
            .unwrap();
        let s = Settings::load(&file).await.unwrap();
        assert_eq!((s.ui.motion, s.ui.accent, s.max_memory_mb), (Motion::Full, Accent::Lapis, 8192));
        let s = Settings { ui: UiSettings { motion: Motion::Reduced, ..Default::default() }, ..Default::default() };
        s.save(&file).await.unwrap();
        assert_eq!(Settings::load(&file).await.unwrap().ui.motion, Motion::Reduced);
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
