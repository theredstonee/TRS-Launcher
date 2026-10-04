//! Datentypen des Engine-Vertrags (Android und iOS gleich): Startbeschreibung,
//! Runtime-Info und Events (serde camelCase).

use std::collections::BTreeMap;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::{Error, Result};

/// Event-Namen (an das Frontend und an Rust-Listener).
pub const EVENT_STATE: &str = "trs-game://state";
pub const EVENT_LOG: &str = "trs-game://log";
pub const EVENT_RUNTIME_PROGRESS: &str = "trs-game://runtime-progress";

/// Java-Hauptversionen, für die es Runtimes gibt.
pub const JAVA_MAJORS: [u8; 4] = [8, 17, 21, 25];

/// Grenzen gegen unsinnige Eingaben (Pfade/Argumente kommen aus dem Kern).
const MAX_ARGS: usize = 4096;
const MAX_ARG_LEN: usize = 64 * 1024;
const MAX_CLASSPATH: usize = 4096;
const MAX_ENV: usize = 128;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Renderer {
    /// MobileGlues ab 1.17, darunter GL4ES.
    #[default]
    Auto,
    Gl4es,
    Mobileglues,
    Zink,
}

impl Renderer {
    /// `auto` auflösen. Ohne Versionsangabe: MobileGlues (aktuelle Versionen).
    pub fn resolve(self, game_version: Option<&str>) -> Renderer {
        match self {
            Renderer::Auto => match game_version.and_then(minor_version) {
                Some(minor) if minor < 17 => Renderer::Gl4es,
                _ => Renderer::Mobileglues,
            },
            other => other,
        }
    }
}

/// `1.16.5` → 16, `1.21` → 21, Snapshots (`24w14a`) → `None`.
fn minor_version(version: &str) -> Option<u32> {
    let mut parts = version.split(['.', '-', ' ']);
    if parts.next()? != "1" {
        return None;
    }
    parts.next()?.parse().ok()
}

/// Alles, was zum Start nötig ist – vom Kern aus der Desktop-Startlogik gebaut.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GameLaunchSpec {
    pub game_dir: PathBuf,
    pub assets_dir: PathBuf,
    pub natives_dir: PathBuf,
    pub classpath: Vec<String>,
    pub main_class: String,
    pub jvm_args: Vec<String>,
    pub game_args: Vec<String>,
    pub java_major: u8,
    #[serde(default)]
    pub renderer: Renderer,
    pub memory_mb: u32,
    #[serde(default)]
    pub extra_env: BTreeMap<String, String>,
    #[serde(default)]
    pub touch_profile: Option<String>,
    /// Ordner der Touch-Layouts (`<Launcher-Daten>/controls`), liest das Overlay im Spiel.
    #[serde(default)]
    pub controls_dir: Option<PathBuf>,
    #[serde(default)]
    pub trs_client: bool,
    /// Minecraft-Version (für `renderer: auto`).
    #[serde(default)]
    pub game_version: Option<String>,
    /// LWJGL-Version laut Version-JSON (`2.9.4`, `3.3.3` …) – wählt den passenden Fork.
    #[serde(default)]
    pub lwjgl_version: Option<String>,
    /// Spiel nutzt SDL3 statt GLFW (Minecraft 26.3+): Engine bindet SDL an und bevorzugt Vulkan.
    #[serde(default)]
    pub uses_sdl: bool,
}

impl GameLaunchSpec {
    /// Prüft Grenzen und Pflichtfelder (keine Panik bei kaputten Eingaben).
    pub fn validate(&self) -> Result<()> {
        if !JAVA_MAJORS.contains(&self.java_major) {
            return Err(Error::UnsupportedJava(self.java_major));
        }
        let bad_main = self.main_class.is_empty()
            || self.main_class.len() > 256
            || !self.main_class.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '$'));
        if bad_main {
            return Err(Error::InvalidSpec("mainClass"));
        }
        if self.classpath.is_empty() || self.classpath.len() > MAX_CLASSPATH {
            return Err(Error::InvalidSpec("classpath"));
        }
        let args = self.jvm_args.iter().chain(&self.game_args).chain(&self.classpath);
        if self.jvm_args.len() + self.game_args.len() > MAX_ARGS || args.clone().any(|a| a.len() > MAX_ARG_LEN || a.contains('\0')) {
            return Err(Error::InvalidSpec("args"));
        }
        if self.classpath.iter().any(|p| p.contains(':')) {
            return Err(Error::InvalidSpec("classpath"));
        }
        for dir in [&self.game_dir, &self.assets_dir].into_iter().chain(self.controls_dir.as_ref()) {
            if !dir.is_absolute() {
                return Err(Error::InvalidSpec("dirs"));
            }
        }
        if !(256..=65_536).contains(&self.memory_mb) {
            return Err(Error::InvalidSpec("memoryMb"));
        }
        let bad_env = self.extra_env.len() > MAX_ENV
            || self.extra_env.iter().any(|(k, v)| {
                k.is_empty() || !k.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') || v.len() > MAX_ARG_LEN || v.contains('\0')
            });
        if bad_env {
            return Err(Error::InvalidSpec("extraEnv"));
        }
        if self.touch_profile.as_deref().is_some_and(|p| p.len() > 64 || !p.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_'))) {
            return Err(Error::InvalidSpec("touchProfile"));
        }
        Ok(())
    }

    /// LWJGL-Fork der Engine: 3.4.1 ab LWJGL 3.4, sonst 3.3.3 (auch für LWJGL 2 über lwjglx).
    pub fn engine_lwjgl(&self) -> &'static str {
        let version = self.lwjgl_version.as_deref().unwrap_or("3.3.3");
        let mut parts = version.split('.').map(|p| p.parse::<u32>().unwrap_or(0));
        let (major, minor) = (parts.next().unwrap_or(3), parts.next().unwrap_or(3));
        if (major, minor) >= (3, 4) { "3.4.1" } else { "3.3.3" }
    }

    /// LWJGL 2 (bis 1.12.2) braucht zusätzlich die lwjglx-Brücke.
    pub fn needs_lwjglx(&self) -> bool {
        self.lwjgl_version.as_deref().is_some_and(|v| v.starts_with("2."))
    }
}

/// Eine installierte Java-Runtime im App-Datenordner.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeInfo {
    pub java_major: u8,
    pub home: PathBuf,
    /// `JAVA_VERSION` aus der `release`-Datei, z. B. `21.0.8`.
    pub version: Option<String>,
    /// `aarch64` oder `x86_64`.
    pub arch: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RuntimePhase {
    Download,
    Verify,
    Unpack,
    Done,
}

/// Payload von `trs-game://runtime-progress`.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeProgress {
    pub java_major: u8,
    pub phase: RuntimePhase,
    /// 0–100 innerhalb der Phase.
    pub percent: f64,
    pub done_bytes: u64,
    pub total_bytes: u64,
}

/// Kennung einer Spielsitzung.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct SessionId(pub String);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum GameState {
    Starting,
    Running,
    Exited,
    Crashed,
}

/// Payload von `trs-game://state`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GameStateEvent {
    pub session: String,
    pub state: GameState,
    #[serde(default)]
    pub exit_code: Option<i32>,
    #[serde(default)]
    pub log_tail: Vec<String>,
}

/// Payload von `trs-game://log` (gesammelte Zeilen).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GameLogEvent {
    pub session: String,
    pub lines: Vec<String>,
}

/// Kopflose JVM (Forge-/NeoForge-Processors, `java -version`): eigener Prozess,
/// kein Fenster, endet mit der `main`-Methode.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaRunSpec {
    pub java_major: u8,
    pub classpath: Vec<String>,
    /// Leer = nur JVM-Argumente (z. B. `-version`).
    #[serde(default)]
    pub main_class: String,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub jvm_args: Vec<String>,
    pub cwd: PathBuf,
    #[serde(default = "default_java_memory")]
    pub memory_mb: u32,
}

fn default_java_memory() -> u32 {
    1024
}

impl JavaRunSpec {
    pub fn validate(&self) -> Result<()> {
        if !JAVA_MAJORS.contains(&self.java_major) {
            return Err(Error::UnsupportedJava(self.java_major));
        }
        let main_ok = self.main_class.len() <= 256
            && self.main_class.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '$'));
        if !main_ok || (self.main_class.is_empty() && self.jvm_args.is_empty()) {
            return Err(Error::InvalidSpec("mainClass"));
        }
        let all = self.classpath.iter().chain(&self.args).chain(&self.jvm_args);
        if self.classpath.len() > MAX_CLASSPATH
            || self.args.len() + self.jvm_args.len() > MAX_ARGS
            || all.clone().any(|a| a.len() > MAX_ARG_LEN || a.contains('\0'))
            || self.classpath.iter().any(|p| p.contains(':'))
        {
            return Err(Error::InvalidSpec("args"));
        }
        if !self.cwd.is_absolute() {
            return Err(Error::InvalidSpec("cwd"));
        }
        if !(128..=16_384).contains(&self.memory_mb) {
            return Err(Error::InvalidSpec("memoryMb"));
        }
        Ok(())
    }
}

/// Android: Ende einer Sitzung, die der Launcher-Prozess nicht mehr mitbekommen hat.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionEnd {
    /// Spielprozess läuft noch.
    pub running: bool,
    #[serde(default)]
    pub crashed: bool,
    /// Ende laut Android (ms seit 1970).
    #[serde(default)]
    pub ended_at_ms: Option<i64>,
    /// Log-Ende der Sitzung plus Androids Ende-Grund / nativer Stack.
    #[serde(default)]
    pub log_tail: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaRunResult {
    pub exit_code: i32,
    #[serde(default)]
    pub log_tail: Vec<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Absolut auf jedem System (Windows braucht ein Laufwerk).
    fn abs(name: &str) -> PathBuf {
        std::env::temp_dir().join(name)
    }

    fn spec() -> GameLaunchSpec {
        GameLaunchSpec {
            game_dir: abs("game"),
            assets_dir: abs("assets"),
            natives_dir: abs("natives"),
            classpath: vec!["/data/libraries/a.jar".into(), "/data/versions/1.21.4/1.21.4.jar".into()],
            main_class: "net.minecraft.client.main.Main".into(),
            jvm_args: vec!["-Dfoo=bar".into()],
            game_args: vec!["--username".into(), "Steve".into()],
            java_major: 21,
            renderer: Renderer::Auto,
            memory_mb: 2048,
            ..Default::default()
        }
    }

    #[test]
    fn auto_renderer_by_version() {
        assert_eq!(Renderer::Auto.resolve(Some("1.21.4")), Renderer::Mobileglues);
        assert_eq!(Renderer::Auto.resolve(Some("1.17")), Renderer::Mobileglues);
        assert_eq!(Renderer::Auto.resolve(Some("1.16.5")), Renderer::Gl4es);
        assert_eq!(Renderer::Auto.resolve(Some("1.8.9")), Renderer::Gl4es);
        assert_eq!(Renderer::Auto.resolve(Some("24w14a")), Renderer::Mobileglues);
        assert_eq!(Renderer::Auto.resolve(None), Renderer::Mobileglues);
        assert_eq!(Renderer::Gl4es.resolve(Some("1.21.4")), Renderer::Gl4es);
    }

    #[test]
    fn lwjgl_fork_choice() {
        let mut s = spec();
        assert_eq!(s.engine_lwjgl(), "3.3.3");
        s.lwjgl_version = Some("3.3.3".into());
        assert_eq!(s.engine_lwjgl(), "3.3.3");
        s.lwjgl_version = Some("3.4.1".into());
        assert_eq!(s.engine_lwjgl(), "3.4.1");
        s.lwjgl_version = Some("2.9.4-nightly-20150209".into());
        assert_eq!(s.engine_lwjgl(), "3.3.3");
        assert!(s.needs_lwjglx());
    }

    #[test]
    fn spec_is_camel_case() {
        let json = serde_json::to_value(spec()).unwrap();
        assert!(json.get("gameDir").is_some());
        assert!(json.get("mainClass").is_some());
        assert_eq!(json["renderer"], "auto");
        let back: GameLaunchSpec = serde_json::from_value(json).unwrap();
        assert_eq!(back, spec());
    }

    #[test]
    fn controls_dir_must_be_absolute() {
        let mut s = spec();
        s.controls_dir = Some(abs("controls"));
        assert!(s.validate().is_ok());
        assert_eq!(serde_json::to_value(&s).unwrap()["controlsDir"], serde_json::json!(abs("controls")));
        s.controls_dir = Some("controls".into());
        assert!(s.validate().is_err());
        // Ältere Kerne kennen das Feld nicht.
        let mut json = serde_json::to_value(spec()).unwrap();
        json.as_object_mut().unwrap().remove("controlsDir");
        assert_eq!(serde_json::from_value::<GameLaunchSpec>(json).unwrap().controls_dir, None);
    }

    #[test]
    fn validation_rejects_bad_input() {
        assert!(spec().validate().is_ok());
        let mut s = spec();
        s.java_major = 11;
        assert!(s.validate().is_err());
        let mut s = spec();
        s.main_class = "a;rm -rf".into();
        assert!(s.validate().is_err());
        let mut s = spec();
        s.classpath.push("/x:/y".into());
        assert!(s.validate().is_err());
        let mut s = spec();
        s.game_dir = PathBuf::from("relative");
        assert!(s.validate().is_err());
        let mut s = spec();
        s.extra_env.insert("BAD KEY".into(), "1".into());
        assert!(s.validate().is_err());
        let mut s = spec();
        s.memory_mb = 10;
        assert!(s.validate().is_err());
    }

    #[test]
    fn java_run_validation() {
        let ok = JavaRunSpec { java_major: 17, jvm_args: vec!["-version".into()], cwd: abs("cwd"), memory_mb: 256, ..Default::default() };
        assert!(ok.validate().is_ok());
        let none = JavaRunSpec { java_major: 17, cwd: abs("cwd"), memory_mb: 256, ..Default::default() };
        assert!(none.validate().is_err());
    }
}
