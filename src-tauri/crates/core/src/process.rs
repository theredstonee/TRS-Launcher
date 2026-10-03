//! Laufende Spiele. Das Spiel läuft als losgelöster Prozess und schreibt in
//! Log-Dateien, die der Launcher mitliest – so überlebt es das Schließen
//! des Launchers und wird nach einem Neustart wiedergefunden.
//!
//! Idee für Losgelöst-Starten, Datei-Tail und das Wiederfinden per
//! Prozess-Startzeit angelehnt an Polyfrost OneLauncher (GPL-3.0-only):
//! `packages/oneclient_core/src/game/{launch,tail,reattach}.rs`.

use std::collections::{HashMap, VecDeque};
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;

use crate::error::Msg;
use crate::gamelog::{LogLine, LogParser};
use crate::launch::Command;
use crate::platform::{self, ProcessHandle};
use crate::{Error, Result};

const LOG_HISTORY: usize = 5000;
const LOG_BATCH_MAX: usize = 400;
const LOG_BATCH_INTERVAL: Duration = Duration::from_millis(60);
const TAIL_INTERVAL: Duration = Duration::from_millis(150);
/// Für die Diagnose reicht das Ende des Logs.
const DIAGNOSIS_LINES: usize = 400;
/// Der Absturz-Helfer liest mehr (Mod-Liste steht am Anfang des Logs).
const CRASH_HELPER_LINES: usize = LOG_HISTORY;

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum GameEvent {
    /// `key`: Prozess-Schlüssel – die Instanz-ID beim ersten Prozess, `<id>~2` usw. bei weiteren
    /// Starts derselben Instanz ([`extra_key`]).
    Started { instance_id: String, key: String, pid: u32 },
    Logs { instance_id: String, key: String, lines: Vec<LogLine> },
    Exited {
        instance_id: String,
        key: String,
        exit_code: Option<i32>,
        crashed: bool,
        play_seconds: u64,
        diagnosis: Option<Box<Diagnosis>>,
        /// Nach einem Absturz: ID der Analyse, die gleich als `crashAnalyzed` folgt.
        #[serde(skip_serializing_if = "Option::is_none")]
        crash_id: Option<String>,
        /// Log-Ende für den Absturz-Helfer (bleibt im Kern).
        #[serde(skip)]
        crash: Option<Arc<crate::crash::CrashContext>>,
    },
    /// Der Absturz-Helfer ist fertig (siehe [`crate::crash`]).
    CrashAnalyzed { instance_id: String, crash: Box<crate::crash::CrashAnalysis> },
    /// Ein Start-Hook oder die Synchronisierung nach dem Beenden ist
    /// fehlgeschlagen – das Frontend zeigt die Meldung als Hinweis.
    /// `message` ist die deutsche Rückfall-Meldung, `code`/`params` die
    /// übersetzbare Fassung (`errors.<code>`).
    Notice {
        instance_id: String,
        message: String,
        code: String,
        #[serde(skip_serializing_if = "serde_json::Map::is_empty")]
        params: serde_json::Map<String, serde_json::Value>,
    },
}

impl GameEvent {
    /// Hinweis aus einer übersetzbaren Meldung.
    pub fn notice(instance_id: String, msg: &Msg) -> Self {
        Self::Notice { instance_id, message: msg.text.clone(), code: msg.code.to_owned(), params: msg.params_json() }
    }

    /// Hinweis aus einem Fehler – ohne Pfade oder interne Details.
    pub fn notice_error(instance_id: String, err: &Error) -> Self {
        let user = err.to_user();
        Self::Notice { instance_id, message: user.message, code: user.code, params: user.params }
    }
}

/// Siehe [`GameManager::running_probe`].
pub type RunningProbe = Arc<dyn Fn(&str) -> bool + Send + Sync>;

pub type EventSink = Arc<dyn Fn(GameEvent) + Send + Sync>;

/// Was nach dem Spielende passieren soll (Spielzeit verbuchen).
pub type OnExit = Box<dyn FnOnce(u64) + Send>;

/// Beendet ein Spiel der mobilen Engine (kein Prozess im Launcher); `true` = Anfrage gestellt.
pub type StopFn = Arc<dyn Fn() -> bool + Send + Sync>;

/// Höchstens so viele Engine-Sitzungen merkt sich der Launcher (eine läuft, ältere sind Reste).
const MAX_ENGINE_RECORDS: usize = 8;
/// Beginn des Ende-Berichts der Android-Engine (Grund + nativer Stack, `CrashInfo.kt`).
const ENGINE_EXIT_MARKER: &str = "[TRS] Spielprozess beendet";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunningGame {
    pub instance_id: String,
    /// Prozess-Schlüssel (siehe [`GameEvent::Started`]).
    pub key: String,
    pub pid: u32,
    pub started_at: DateTime<Utc>,
}

/// Schlüssel eines weiteren Prozesses derselben Instanz („Nochmal starten“).
pub fn extra_key(instance_id: &str, n: u32) -> String {
    format!("{instance_id}~{n}")
}

/// Ist das ein zusätzlicher Prozess (nicht der erste Start der Instanz)?
pub fn is_extra_key(instance_id: &str, key: &str) -> bool {
    key != instance_id
}

// --- Absturz-Diagnose ----------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DiagnosisKind {
    CorruptFiles,
    OutOfMemory,
    WrongJava,
    MissingDependency,
    ModConflict,
    GraphicsDriver,
    /// Eine Mod verlangt/verbietet eine bestimmte Version einer anderen
    /// (Fabric: „is incompatible with version … of mod …“) – lässt sich durch
    /// Tausch der Version beheben (`conflict`).
    IncompatibleMod,
}

/// Welche zwei Mods sich laut Loader nicht vertragen.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModConflictInfo {
    /// Die Mod, die getauscht werden sollte (Mod-ID aus dem Jar, z. B. `sodium`).
    pub mod_id: String,
    pub mod_name: String,
    pub mod_version: String,
    pub other_id: String,
    pub other_name: String,
    pub other_version: Option<String>,
}

/// Welche Mods fehlen (Fabric: „… of cloth-config, which is missing!“ /
/// „Install cloth-config, version 16.0.0 or later.“; Forge: „Mod ID: 'x' … [MISSING]“).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MissingModInfo {
    /// Die Mod, die sie verlangt (Mod-ID aus dem Jar), falls genannt.
    pub mod_id: Option<String>,
    pub mod_name: Option<String>,
    /// Fehlende Mod-IDs (z. B. `cloth-config`) – das Frontend bietet „installieren“ an.
    pub dependencies: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Diagnosis {
    pub kind: DiagnosisKind,
    /// Für den Nutzer formuliert – was los ist und was hilft (deutsche
    /// Rückfall-Meldung).
    pub message: String,
    /// Übersetzungs-Code der Meldung (`errors.<code>`).
    pub code: &'static str,
    /// Werte für die Übersetzung (`{name}` …).
    #[serde(skip_serializing_if = "serde_json::Map::is_empty")]
    pub params: serde_json::Map<String, serde_json::Value>,
    /// „Dateien prüfen & reparieren“ anbieten.
    pub can_repair: bool,
    /// Bei `incompatible_mod`: welche Mods – das Frontend bietet den Tausch an.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub conflict: Option<ModConflictInfo>,
    /// Bei `missing_dependency`: was fehlt – das Frontend bietet „installieren“ an.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub missing: Option<MissingModInfo>,
}

fn clip_part(text: &str) -> String {
    text.trim().chars().filter(|c| !c.is_control()).take(100).collect()
}

fn is_mod_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.'))
}

/// `'Name' (id) rest` → (Name, id, rest).
fn mod_ref(text: &str) -> Option<(&str, &str, &str)> {
    let rest = text.strip_prefix('\'')?;
    let (name, rest) = rest.split_once("' (")?;
    let (id, rest) = rest.split_once(')')?;
    is_mod_id(id).then_some((name, id, rest.trim_start()))
}

/// Liest Fabrics Meldungen über unverträgliche Versionen:
/// * `Mod 'Sodium' (sodium) 0.8.14 is incompatible with version 1.10.7 or earlier of mod 'Iris' (iris), yet a conflicting version is present: 1.10.7!`
/// * `Mod 'A' (a) 1.0 requires version 2.0 or later of mod 'B' (b), but only the wrong version is present: 1.5!`
/// * `Replace mod 'Sodium' (sodium) 0.8.14 with any 0.8.x version that is compatible with: iris 1.10.7.`
pub fn parse_mod_conflict(text: &str) -> Option<ModConflictInfo> {
    let lines = || text.lines().map(|l| l.trim().trim_start_matches(['-', '\t', ' ']).trim());
    for line in lines() {
        let Some(rest) = line.strip_prefix("Mod ") else { continue };
        let Some((name, id, rest)) = mod_ref(rest) else { continue };
        let Some((version, rest)) = rest.split_once(' ') else { continue };
        let wrong_version = rest.starts_with("requires") && rest.contains("wrong version is present");
        if !rest.starts_with("is incompatible with") && !wrong_version {
            continue;
        }
        let Some((_, other)) = rest.split_once("of mod ") else { continue };
        let Some((other_name, other_id, after)) = mod_ref(other) else { continue };
        let other_version = after.rsplit_once(": ").map(|(_, v)| clip_part(v.trim_end_matches(['!', '.'])));
        return Some(ModConflictInfo {
            mod_id: id.to_owned(),
            mod_name: clip_part(name),
            mod_version: clip_part(version),
            other_id: other_id.to_owned(),
            other_name: clip_part(other_name),
            other_version,
        });
    }
    for line in lines() {
        let Some(rest) = line.strip_prefix("Replace mod ") else { continue };
        let Some((name, id, rest)) = mod_ref(rest) else { continue };
        let Some((version, rest)) = rest.split_once(' ') else { continue };
        let Some((_, other)) = rest.split_once("compatible with: ") else { continue };
        let mut parts = other.trim_end_matches('.').split_whitespace();
        let Some(other_id) = parts.next().filter(|i| is_mod_id(i)) else { continue };
        return Some(ModConflictInfo {
            mod_id: id.to_owned(),
            mod_name: clip_part(name),
            mod_version: clip_part(version),
            other_id: other_id.to_owned(),
            other_name: other_id.to_owned(),
            other_version: parts.next().map(clip_part),
        });
    }
    None
}

/// So viele fehlende Mods werden höchstens übernommen.
const MAX_MISSING: usize = 8;

/// `cloth-config` bzw. `mod 'Cloth Config' (cloth-config)` → Mod-ID.
fn missing_id(text: &str) -> Option<String> {
    let text = text.trim().trim_start_matches("mod ").trim();
    if let Some((_, id, _)) = mod_ref(text) {
        return Some(id.to_owned());
    }
    let id = text.trim_matches(['\'', '"']);
    is_mod_id(id).then(|| id.to_owned())
}

/// Liest, welche Mod welche andere vermisst:
/// * `Mod 'More Culling' (moreculling) 1.6.2 requires version 16.0.0 or later of cloth-config, which is missing!`
/// * `Mod 'Sodium Extra' (sodium-extra) requires any version of sodium, which is missing!`
/// * `Install cloth-config, version 16.0.0 or later.`
/// * (Neo)Forge: `Mod ID: 'cloth_config', Requested by: 'moreculling', Expected range: '[15,)', Actual version: '[MISSING]'`
pub fn parse_missing_dependency(text: &str) -> Option<MissingModInfo> {
    let mut info = MissingModInfo { mod_id: None, mod_name: None, dependencies: Vec::new() };
    let add = |info: &mut MissingModInfo, id: String| {
        if !info.dependencies.contains(&id) && info.dependencies.len() < MAX_MISSING {
            info.dependencies.push(id);
        }
    };
    for line in text.lines().map(|l| l.trim().trim_start_matches(['-', '\t', ' ']).trim()) {
        if let Some(rest) = line.strip_prefix("Mod ")
            && let Some((name, id, rest)) = mod_ref(rest)
            && let Some(before) = rest.strip_suffix("which is missing!").or_else(|| rest.strip_suffix("which is missing"))
            && let Some((_, dep)) = before.trim_end().trim_end_matches(',').rsplit_once(" of ")
            && let Some(dep) = missing_id(dep)
        {
            if info.mod_id.is_none() {
                info.mod_id = Some(id.to_owned());
                info.mod_name = Some(clip_part(name));
            }
            add(&mut info, dep);
        } else if let Some(rest) = line.strip_prefix("Install ")
            && let Some((dep, _)) = rest.split_once(',').or_else(|| rest.split_once('.'))
            && let Some(dep) = missing_id(dep)
        {
            add(&mut info, dep);
        } else if line.contains("[MISSING]")
            && let Some(rest) = line.strip_prefix("Mod ID: '")
            && let Some((dep, rest)) = rest.split_once('\'')
            && is_mod_id(dep)
        {
            if info.mod_id.is_none()
                && let Some((_, by)) = rest.split_once("Requested by: '")
                && let Some((by, _)) = by.split_once('\'')
                && is_mod_id(by)
            {
                info.mod_id = Some(by.to_owned());
            }
            add(&mut info, dep.to_owned());
        }
    }
    (!info.dependencies.is_empty()).then_some(info)
}

/// Sucht in den letzten Log-Zeilen nach bekannten Absturzursachen.
pub fn diagnose(lines: &[LogLine]) -> Option<Diagnosis> {
    let text: String = lines.iter().map(|l| l.message.as_str()).collect::<Vec<_>>().join("\n");
    let has = |needle: &str| text.contains(needle);

    if let Some(conflict) = parse_mod_conflict(&text) {
        let message = crate::msg!(
            "process.crashIncompatibleMod",
            "{name} verträgt sich in dieser Version nicht mit {other}. Der Launcher kann {name} gegen eine passende Version tauschen.",
            name = format!("{} {}", conflict.mod_name, conflict.mod_version),
            other = match &conflict.other_version {
                Some(v) => format!("{} {v}", conflict.other_name),
                None => conflict.other_name.clone(),
            }
        );
        return Some(Diagnosis {
            kind: DiagnosisKind::IncompatibleMod,
            params: message.params_json(),
            message: message.text,
            code: message.code,
            can_repair: false,
            conflict: Some(conflict),
            missing: None,
        });
    }
    let missing_dependency = has("requires") && (has("which is missing") || has("but it is missing"))
        || has("Missing or unsupported mandatory dependencies")
        || has("Could not find required mod");
    if missing_dependency && let Some(missing) = parse_missing_dependency(&text) {
        let deps = missing.dependencies.join(", ");
        let message = match &missing.mod_name.clone().or_else(|| missing.mod_id.clone()) {
            Some(name) => crate::msg!(
                "process.crashMissingDependencyNamed",
                "{name} braucht {deps} – die Mod fehlt in der Instanz. Der Launcher kann sie installieren.",
                name = name,
                deps = &deps
            ),
            None => crate::msg!(
                "process.crashMissingDependencyOnly",
                "Es fehlt {deps} – eine andere Mod braucht sie. Der Launcher kann sie installieren.",
                deps = &deps
            ),
        };
        return Some(Diagnosis {
            kind: DiagnosisKind::MissingDependency,
            params: message.params_json(),
            message: message.text,
            code: message.code,
            can_repair: false,
            conflict: None,
            missing: Some(missing),
        });
    }
    let (kind, message, can_repair) = if has("java.util.zip.ZipException")
        || has("Invalid or corrupt jarfile")
        || has("zip END header not found")
        || has("ZipException: invalid")
        || has("java.lang.ClassFormatError: Truncated class file")
    {
        (
            DiagnosisKind::CorruptFiles,
            crate::msg!(
                "process.crashCorruptFiles",
                "Eine Spieldatei ist beschädigt (z. B. nach einem abgebrochenen Download). \
                 „Dateien prüfen“ lädt sie neu herunter."
            ),
            true,
        )
    } else if has("java.lang.OutOfMemoryError") {
        (
            DiagnosisKind::OutOfMemory,
            crate::msg!(
                "process.crashOutOfMemory",
                "Dem Spiel ist der Arbeitsspeicher ausgegangen. Erhöhe den Arbeitsspeicher der Instanz \
                 in den Einstellungen."
            ),
            false,
        )
    } else if has("UnsupportedClassVersionError") || has("has been compiled by a more recent version of the Java Runtime") {
        (
            DiagnosisKind::WrongJava,
            crate::msg!(
                "process.crashWrongJava",
                "Eine Mod braucht eine neuere Java-Version. Entferne den eigenen Java-Pfad in den \
                 Einstellungen, dann wählt der Launcher automatisch die passende."
            ),
            false,
        )
    } else if missing_dependency {
        (
            DiagnosisKind::MissingDependency,
            crate::msg!(
                "process.crashMissingDependency",
                "Einer Mod fehlt eine benötigte andere Mod. Welche, steht im Log direkt über dem Fehler."
            ),
            false,
        )
    } else if has("Mixin apply failed") || has("MixinApplyError") || has("InvalidInjectionException") || has("Incompatible mods found") {
        (
            DiagnosisKind::ModConflict,
            crate::msg!(
                "process.crashModConflict",
                "Zwei Mods vertragen sich nicht. Deaktiviere zuletzt hinzugefügte Mods und starte erneut."
            ),
            false,
        )
    } else if has("EXCEPTION_ACCESS_VIOLATION") && (has("atio6axx") || has("nvoglv") || has("ig9icd") || has("ig7icd"))
        || has("Pixel format not accelerated")
        || has("GLFW error 65542")
        || has("WGL: The driver does not appear to support OpenGL")
    {
        (
            DiagnosisKind::GraphicsDriver,
            crate::msg!(
                "process.crashGraphicsDriver",
                "Der Grafiktreiber ist abgestürzt oder unterstützt OpenGL nicht. Aktualisiere den \
                 Treiber deiner Grafikkarte."
            ),
            false,
        )
    } else {
        return None;
    };
    Some(Diagnosis {
        kind,
        params: message.params_json(),
        message: message.text,
        code: message.code,
        can_repair,
        conflict: None,
        missing: None,
    })
}

/// Für dieses Programm die leistungsstarke Grafikkarte wählen (Windows:
/// Grafikeinstellungen in der Registry, Linux: PRIME-Umgebungsvariablen).
/// Liefert zusätzliche Umgebungsvariablen für den Spielprozess.
pub fn prefer_dedicated_gpu(program: &Path) -> Vec<(String, String)> {
    platform::dedicated_gpu_env(program)
}

// --- Verwaltung ---------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SessionRecord {
    instance_id: String,
    /// Prozess-Schlüssel; ältere Dateien kennen ihn nicht (dann = Instanz-ID).
    #[serde(default)]
    key: String,
    pid: u32,
    started_at: DateTime<Utc>,
    creation_time: u64,
    stdout_log: PathBuf,
    stderr_log: PathBuf,
}

impl SessionRecord {
    fn key(&self) -> &str {
        if self.key.is_empty() { &self.instance_id } else { &self.key }
    }
}

/// Wie sich ein laufendes Spiel beenden lässt.
#[derive(Clone)]
enum Control {
    /// Eigener Java-Prozess (Desktop).
    Process(Arc<ProcessHandle>),
    /// Mobile Spiel-Engine: Beenden über das Plugin.
    Engine(StopFn),
}

impl Control {
    fn terminate(&self) -> bool {
        match self {
            Self::Process(process) => process.terminate(),
            Self::Engine(stop) => stop(),
        }
    }
}

struct Running {
    info: RunningGame,
    control: Control,
    killed: Arc<AtomicBool>,
}

/// Engine-Sitzung (mobil): Zeilen kommen von der Engine statt aus Log-Dateien.
struct EngineSession {
    instance_id: String,
    key: String,
    started_at: DateTime<Utc>,
    parser: LogParser,
    secrets: Vec<String>,
    on_exit: Option<OnExit>,
}

/// Gemerkte Engine-Sitzung (`engine-sessions.json`): Endet das Spiel, während der Launcher
/// nicht läuft (iOS beendet die App mit dem Spiel), wird die Spielzeit beim nächsten Start verbucht.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineRecord {
    pub session: String,
    pub instance_id: String,
    pub started_at: DateTime<Utc>,
}

#[derive(Default)]
struct State {
    running: HashMap<String, Running>,
    logs: HashMap<String, VecDeque<LogLine>>,
    /// Engine-Sitzungs-ID → Sitzung.
    engine: HashMap<String, EngineSession>,
}

pub struct GameManager {
    state: Arc<Mutex<State>>,
    sink: EventSink,
    sessions_file: PathBuf,
    sessions_lock: Arc<Mutex<()>>,
}

impl GameManager {
    pub fn new(sink: EventSink, sessions_file: PathBuf) -> Self {
        Self { state: Arc::default(), sink, sessions_file, sessions_lock: Arc::default() }
    }

    /// Kanal zum Frontend (für Hinweise außerhalb des Prozesslebens).
    pub fn sink(&self) -> EventSink {
        self.sink.clone()
    }

    pub fn running(&self) -> Vec<RunningGame> {
        self.lock().running.values().map(|r| r.info.clone()).collect()
    }

    /// Läuft mindestens ein Prozess der Instanz?
    pub fn is_running(&self, instance_id: &str) -> bool {
        self.lock().running.values().any(|r| r.info.instance_id == instance_id)
    }

    /// Abfrage „läuft die Instanz noch?“ für Nachlauf-Code, der den Manager nicht festhalten kann.
    pub fn running_probe(&self) -> RunningProbe {
        let state = self.state.clone();
        Arc::new(move |instance_id: &str| {
            state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).running.values().any(|r| r.info.instance_id == instance_id)
        })
    }

    /// Wie viele Prozesse der Instanz laufen?
    pub fn count(&self, instance_id: &str) -> usize {
        self.lock().running.values().filter(|r| r.info.instance_id == instance_id).count()
    }

    /// Erster freier Schlüssel für einen weiteren Prozess der Instanz.
    fn free_extra_key(&self, instance_id: &str) -> String {
        let state = self.lock();
        (2u32..).map(|n| extra_key(instance_id, n)).find(|k| !state.running.contains_key(k)).unwrap_or_else(|| extra_key(instance_id, 2))
    }

    pub fn logs(&self, instance_id: &str) -> Vec<LogLine> {
        self.lock().logs.get(instance_id).map(|l| l.iter().cloned().collect()).unwrap_or_default()
    }

    /// Beendet alle Prozesse der Instanz.
    pub fn kill(&self, instance_id: &str) -> bool {
        // Erst die Sperre lösen: Die Engine meldet das Ende evtl. sofort (gleicher Thread).
        let controls: Vec<Control> = {
            let state = self.lock();
            state
                .running
                .values()
                .filter(|r| r.info.instance_id == instance_id)
                .map(|r| {
                    r.killed.store(true, Ordering::Relaxed);
                    r.control.clone()
                })
                .collect()
        };
        controls.iter().fold(false, |any, c| c.terminate() | any)
    }

    /// Beendet genau einen Prozess (Schlüssel aus [`RunningGame::key`]) der Instanz.
    pub fn kill_key(&self, instance_id: &str, key: &str) -> bool {
        let control = {
            let state = self.lock();
            let Some(running) = state.running.get(key).filter(|r| r.info.instance_id == instance_id) else { return false };
            running.killed.store(true, Ordering::Relaxed);
            running.control.clone()
        };
        control.terminate()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Startet das Spiel losgelöst. `log_dir` nimmt stdout/stderr auf;
    /// `secrets` werden aus allen Log-Zeilen entfernt.
    pub fn spawn(
        &self,
        instance_id: &str,
        command: Command,
        log_dir: &Path,
        secrets: Vec<String>,
        on_exit: OnExit,
        extra: bool,
    ) -> Result<u32> {
        // Ein weiterer Prozess bekommt Schlüssel `<id>~n` und eigene Log-Dateien.
        let (key, log_dir) = if extra && self.is_running(instance_id) {
            let key = self.free_extra_key(instance_id);
            let dir = log_dir.join(key.rsplit('~').next().map_or_else(|| "extra".to_owned(), |n| format!("extra-{n}")));
            (key, dir)
        } else if self.is_running(instance_id) {
            return Err(Error::launch(crate::msg!("launcher.alreadyRunning", "Diese Instanz läuft bereits.")));
        } else {
            (instance_id.to_owned(), log_dir.to_owned())
        };
        let log_dir = log_dir.as_path();
        std::fs::create_dir_all(log_dir).map_err(|e| Error::io(log_dir, e))?;
        let stdout_log = log_dir.join("launcher-stdout.log");
        let stderr_log = log_dir.join("launcher-stderr.log");
        let stdout = std::fs::File::create(&stdout_log).map_err(|e| Error::io(&stdout_log, e))?;
        let stderr = std::fs::File::create(&stderr_log).map_err(|e| Error::io(&stderr_log, e))?;

        let mut cmd = std::process::Command::new(&command.program);
        // AppImage-Pfade gehören nicht ins Spiel (siehe `platform::env`).
        platform::env::clean_std(&mut cmd);
        cmd.args(&command.args)
            .current_dir(&command.cwd)
            .envs(command.env.iter().map(|(k, v)| (k, v)))
            .stdin(std::process::Stdio::null())
            .stdout(stdout)
            .stderr(stderr);
        // Eigene Prozessgruppe ohne Konsole: Das Spiel überlebt den Launcher.
        // „Hohe Priorität“ gibt es nur unter Windows (Linux bräuchte Root-Rechte).
        platform::detach(&mut cmd, command.high_priority);
        let child = cmd.spawn().map_err(|e| {
            tracing::error!("Java konnte nicht gestartet werden ({}): {e}", command.program.display());
            Error::launch(crate::msg!("process.javaStartFailed", "Java konnte nicht gestartet werden."))
        })?;
        let pid = child.id();
        let process = ProcessHandle::from_child(child)
            .ok_or_else(|| Error::launch(crate::msg!("process.exitedImmediately", "Das Spiel wurde sofort wieder beendet.")))?;

        let record = SessionRecord {
            instance_id: instance_id.to_owned(),
            key,
            pid,
            started_at: Utc::now(),
            creation_time: process.creation_time().unwrap_or_default(),
            stdout_log,
            stderr_log,
        };
        self.update_sessions(|list| list.push(record.clone()));
        self.attach(record, Arc::new(process), secrets, on_exit);
        Ok(pid)
    }

    /// Findet Spiele wieder, die beim letzten Schließen des Launchers noch liefen.
    pub fn recover(&self, on_exit: impl Fn(&str, bool) -> OnExit) {
        let records: Vec<SessionRecord> =
            std::fs::read(&self.sessions_file).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        let mut alive = Vec::new();
        for record in records {
            let Some(process) = ProcessHandle::open(record.pid) else { continue };
            // Gleicher PID, aber anderer Prozess? Dann ist unser Spiel längst beendet.
            if process.creation_time() != Some(record.creation_time) || !process.is_alive() {
                continue;
            }
            tracing::info!("Laufendes Spiel wiedergefunden: {} (PID {})", record.instance_id, record.pid);
            alive.push(record.clone());
            let exit = on_exit(&record.instance_id, is_extra_key(&record.instance_id, record.key()));
            self.attach(record, Arc::new(process), Vec::new(), exit);
        }
        self.write_sessions(&alive);
    }

    fn attach(&self, record: SessionRecord, process: Arc<ProcessHandle>, secrets: Vec<String>, on_exit: OnExit) {
        let instance = record.instance_id.clone();
        let id = record.key().to_owned();
        let killed = Arc::new(AtomicBool::new(false));
        {
            let mut state = self.lock();
            state.logs.insert(id.clone(), VecDeque::new());
            state.running.insert(
                id.clone(),
                Running {
                    info: RunningGame { instance_id: instance.clone(), key: id.clone(), pid: record.pid, started_at: record.started_at },
                    control: Control::Process(process.clone()),
                    killed: killed.clone(),
                },
            );
        }
        (self.sink)(GameEvent::Started { instance_id: instance.clone(), key: id.clone(), pid: record.pid });

        let exited = Arc::new(AtomicBool::new(false));
        let secrets: Arc<Vec<String>> = Arc::new(secrets.into_iter().filter(|s| s.len() >= 8).collect());
        let (line_tx, line_rx) = mpsc::unbounded_channel::<LogLine>();
        let tails = [
            tokio::spawn(tail(record.stdout_log.clone(), LogParser::stdout(), line_tx.clone(), secrets.clone(), exited.clone())),
            tokio::spawn(tail(record.stderr_log.clone(), LogParser::stderr(), line_tx, secrets, exited.clone())),
        ];
        let forwarder = tokio::spawn(forward(line_rx, self.state.clone(), self.sink.clone(), instance.clone(), id.clone()));

        let (state, sink, sessions) = (self.state.clone(), self.sink.clone(), self.sessions_handle());
        tokio::spawn(async move {
            let waiter = process.clone();
            let exit_code = tokio::task::spawn_blocking(move || waiter.wait()).await.ok().flatten();
            exited.store(true, Ordering::Relaxed);
            // Erst alle Logs ausliefern, dann das Ende melden.
            for t in tails {
                let _ = t.await;
            }
            let _ = forwarder.await;

            let play_seconds = (Utc::now() - record.started_at).num_seconds().max(0) as u64;
            let was_killed = killed.load(Ordering::Relaxed);
            // Wiedergefundene Spiele unter Linux: Exit-Code unbekannt – dann kein Absturz melden.
            let crashed = !was_killed && exit_code.map_or(process.exit_code_known(), |code| code != 0);
            let event = {
                let mut state = state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                state.running.remove(&id);
                exit_event(&state, instance, id.clone(), record.started_at, exit_code, crashed, play_seconds)
            };
            sessions.remove(&id);
            on_exit(play_seconds);
            sink(event);
        });
    }

    // --- Mobile Spiel-Engine ----------------------------------------------------

    fn engine_file(&self) -> PathBuf {
        self.sessions_file.with_file_name("engine-sessions.json")
    }

    fn update_engine_records(&self, change: impl FnOnce(&mut Vec<EngineRecord>)) {
        let _guard = self.sessions_lock.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let path = self.engine_file();
        let mut list: Vec<EngineRecord> = std::fs::read(&path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        change(&mut list);
        let len = list.len();
        if len > MAX_ENGINE_RECORDS {
            list.drain(..len - MAX_ENGINE_RECORDS);
        }
        let written = if list.is_empty() {
            match std::fs::remove_file(&path) {
                Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
                _ => Ok(()),
            }
        } else {
            serde_json::to_vec_pretty(&list).map_err(std::io::Error::other).and_then(|b| std::fs::write(&path, b))
        };
        if let Err(e) = written {
            tracing::warn!("Engine-Sitzungen konnten nicht gespeichert werden: {e}");
        }
    }

    /// Spiel in der mobilen Engine übernehmen: meldet `started` (PID 0 – es gibt keinen
    /// Prozess im Launcher). Zeilen und Ende kommen über [`Self::engine_logs`] / [`Self::engine_exited`].
    pub fn attach_engine(&self, instance_id: &str, session: &str, secrets: Vec<String>, stop: StopFn, on_exit: OnExit) -> Result<()> {
        let key = instance_id.to_owned();
        let started_at = Utc::now();
        {
            let mut state = self.lock();
            if state.running.contains_key(&key) {
                return Err(Error::launch(crate::msg!("launcher.alreadyRunning", "Diese Instanz läuft bereits.")));
            }
            state.logs.insert(key.clone(), VecDeque::new());
            state.running.insert(
                key.clone(),
                Running {
                    info: RunningGame { instance_id: key.clone(), key: key.clone(), pid: 0, started_at },
                    control: Control::Engine(stop),
                    killed: Arc::new(AtomicBool::new(false)),
                },
            );
            state.engine.insert(
                session.to_owned(),
                EngineSession {
                    instance_id: key.clone(),
                    key: key.clone(),
                    started_at,
                    parser: LogParser::stdout(),
                    secrets: secrets.into_iter().filter(|s| s.len() >= 8).collect(),
                    on_exit: Some(on_exit),
                },
            );
        }
        let record = EngineRecord { session: session.to_owned(), instance_id: key.clone(), started_at };
        self.update_engine_records(|list| {
            list.retain(|r| r.session != record.session);
            list.push(record);
        });
        (self.sink)(GameEvent::Started { instance_id: key.clone(), key, pid: 0 });
        Ok(())
    }

    /// Log-Zeilen einer Engine-Sitzung (unbekannte Sitzungen werden ignoriert).
    pub fn engine_logs(&self, session: &str, lines: &[String]) {
        let now = Utc::now().timestamp_millis();
        let (instance_id, key, batch) = {
            let mut guard = self.lock();
            let state = &mut *guard;
            let Some(engine) = state.engine.get_mut(session) else { return };
            let mut batch = Vec::new();
            for raw in lines {
                let Some(mut line) = engine.parser.feed(raw, now) else { continue };
                for secret in &engine.secrets {
                    if line.message.contains(secret.as_str()) {
                        line.message = line.message.replace(secret.as_str(), "********");
                    }
                }
                batch.push(line);
            }
            if batch.is_empty() {
                return;
            }
            let history = state.logs.entry(engine.key.clone()).or_default();
            history.extend(batch.iter().cloned());
            while history.len() > LOG_HISTORY {
                history.pop_front();
            }
            (engine.instance_id.clone(), engine.key.clone(), batch)
        };
        (self.sink)(GameEvent::Logs { instance_id, key, lines: batch });
    }

    /// Ende einer Engine-Sitzung: Spielzeit verbuchen, Absturz auswerten, `exited` melden.
    /// `tail`: letzte Zeilen der Engine (falls unterwegs keine Zeilen ankamen).
    pub fn engine_exited(&self, session: &str, exit_code: Option<i32>, crashed: bool, tail: &[String]) {
        // Leeres Log: das Log-Ende der Engine. Sonst nur ihr Ende-Bericht (Androids Grund, nativer Stack).
        let has_logs = {
            let state = self.lock();
            state.engine.get(session).is_some_and(|e| state.logs.get(&e.key).is_some_and(|l| !l.is_empty()))
        };
        let new = if has_logs {
            tail.iter().position(|l| l.starts_with(ENGINE_EXIT_MARKER)).map_or(&[][..], |i| &tail[i..])
        } else {
            tail
        };
        if !new.is_empty() {
            self.engine_logs(session, new);
        }
        let (event, on_exit, play_seconds) = {
            let mut state = self.lock();
            let Some(mut engine) = state.engine.remove(session) else { return };
            let killed = state.running.remove(&engine.key).is_some_and(|r| r.killed.load(Ordering::Relaxed));
            let play_seconds = (Utc::now() - engine.started_at).num_seconds().max(0) as u64;
            let crashed = crashed && !killed;
            let event = exit_event(&state, engine.instance_id.clone(), engine.key.clone(), engine.started_at, exit_code, crashed, play_seconds);
            (event, engine.on_exit.take(), play_seconds)
        };
        self.update_engine_records(|list| list.retain(|r| r.session != session));
        if let Some(on_exit) = on_exit {
            on_exit(play_seconds);
        }
        (self.sink)(event);
    }

    /// Gemerkte Engine-Sitzungen (nach einem App-Neustart) – danach vergessen.
    pub fn take_engine_records(&self) -> Vec<EngineRecord> {
        let mut out = Vec::new();
        self.update_engine_records(|list| out = std::mem::take(list));
        out
    }

    /// Sitzung, die ohne laufenden Launcher endete (iOS: Spielende beendet die App): wie ein
    /// normales Ende melden, Spielzeit bis `ended_at`.
    pub fn engine_recovered(&self, record: &EngineRecord, ended_at: DateTime<Utc>, exit_code: Option<i32>, crashed: bool, tail: &[String], on_exit: OnExit) {
        let play_seconds = (ended_at - record.started_at).num_seconds().max(0) as u64;
        let event = {
            let mut state = self.lock();
            let mut parser = LogParser::stdout();
            let now = Utc::now().timestamp_millis();
            let lines: VecDeque<LogLine> = tail.iter().filter_map(|l| parser.feed(l, now)).collect();
            state.logs.insert(record.instance_id.clone(), lines);
            exit_event(&state, record.instance_id.clone(), record.instance_id.clone(), record.started_at, exit_code, crashed, play_seconds)
        };
        on_exit(play_seconds);
        (self.sink)(event);
    }

    fn sessions_handle(&self) -> SessionsFile {
        SessionsFile { path: self.sessions_file.clone(), lock: self.sessions_lock.clone() }
    }

    fn update_sessions(&self, change: impl FnOnce(&mut Vec<SessionRecord>)) {
        self.sessions_handle().update(change);
    }

    fn write_sessions(&self, list: &[SessionRecord]) {
        self.sessions_handle().update(|current| *current = list.to_vec());
    }
}

/// `exited`-Ereignis; nach einem Absturz mit Diagnose und Log-Ende für den Absturz-Helfer.
fn exit_event(
    state: &State,
    instance_id: String,
    key: String,
    started_at: DateTime<Utc>,
    exit_code: Option<i32>,
    crashed: bool,
    play_seconds: u64,
) -> GameEvent {
    let (diagnosis, crash) = if crashed {
        let tail = |n: usize| state.logs.get(&key).map(|h| h.iter().skip(h.len().saturating_sub(n)).cloned().collect::<Vec<_>>());
        let diagnosis = tail(DIAGNOSIS_LINES).as_deref().and_then(diagnose).map(Box::new);
        let context = crate::crash::CrashContext {
            crash_id: crate::crash::CrashContext::new_id(Utc::now()),
            lines: tail(CRASH_HELPER_LINES).unwrap_or_default(),
            started_at,
            exit_code,
            play_seconds,
        };
        (diagnosis, Some(Arc::new(context)))
    } else {
        (None, None)
    };
    let crash_id = crash.as_ref().map(|c| c.crash_id.clone());
    GameEvent::Exited { instance_id, key, exit_code, crashed, play_seconds, diagnosis, crash_id, crash }
}

struct SessionsFile {
    path: PathBuf,
    lock: Arc<Mutex<()>>,
}

impl SessionsFile {
    fn update(&self, change: impl FnOnce(&mut Vec<SessionRecord>)) {
        let _guard = self.lock.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut list: Vec<SessionRecord> =
            std::fs::read(&self.path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        change(&mut list);
        match serde_json::to_vec_pretty(&list) {
            Ok(bytes) => {
                if let Err(e) = std::fs::write(&self.path, bytes) {
                    tracing::warn!("Spielsitzungen konnten nicht gespeichert werden: {e}");
                }
            }
            Err(e) => tracing::warn!("Spielsitzungen konnten nicht serialisiert werden: {e}"),
        }
    }

    fn remove(&self, key: &str) {
        self.update(|list| list.retain(|r| r.key() != key));
    }
}

/// Liest eine wachsende Log-Datei mit, bis das Spiel beendet und alles gelesen ist.
async fn tail(
    path: PathBuf,
    mut parser: LogParser,
    tx: mpsc::UnboundedSender<LogLine>,
    secrets: Arc<Vec<String>>,
    exited: Arc<AtomicBool>,
) {
    let mut offset = 0u64;
    let mut pending: Vec<u8> = Vec::new();
    loop {
        let finished = exited.load(Ordering::Relaxed);
        let chunk = {
            let path = path.clone();
            tokio::task::spawn_blocking(move || read_from(&path, offset)).await.ok().flatten()
        };
        match chunk {
            Some((bytes, new_offset)) if !bytes.is_empty() => {
                offset = new_offset;
                pending.extend_from_slice(&bytes);
                while let Some(pos) = pending.iter().position(|&b| b == b'\n') {
                    let line: Vec<u8> = pending.drain(..=pos).collect();
                    if !emit(&mut parser, &line, &secrets, &tx) {
                        return;
                    }
                }
                continue;
            }
            // Datei wurde neu angelegt/gekürzt: von vorn lesen.
            Some((_, new_offset)) if new_offset < offset => offset = 0,
            _ => {}
        }
        if finished {
            if !pending.is_empty() {
                emit(&mut parser, &std::mem::take(&mut pending), &secrets, &tx);
            }
            return;
        }
        tokio::time::sleep(TAIL_INTERVAL).await;
    }
}

/// Liest ab `offset` höchstens 1 MiB. Liefert die Bytes und das neue Offset
/// (bzw. die aktuelle Dateigröße, falls sie kleiner geworden ist).
fn read_from(path: &Path, offset: u64) -> Option<(Vec<u8>, u64)> {
    let mut file = std::fs::File::open(path).ok()?;
    let len = file.metadata().ok()?.len();
    if len < offset {
        return Some((Vec::new(), len));
    }
    file.seek(SeekFrom::Start(offset)).ok()?;
    let mut buf = Vec::new();
    file.take(1 << 20).read_to_end(&mut buf).ok()?;
    let new_offset = offset + buf.len() as u64;
    Some((buf, new_offset))
}

fn emit(parser: &mut LogParser, raw: &[u8], secrets: &[String], tx: &mpsc::UnboundedSender<LogLine>) -> bool {
    // Nicht jede Ausgabe ist gültiges UTF-8 (alte Versionen, native Libs).
    let text = String::from_utf8_lossy(raw);
    let Some(mut line) = parser.feed(&text, Utc::now().timestamp_millis()) else { return true };
    for secret in secrets {
        if line.message.contains(secret.as_str()) {
            line.message = line.message.replace(secret.as_str(), "********");
        }
    }
    tx.send(line).is_ok()
}

/// Reicht Log-Zeilen gebündelt weiter, damit das Frontend bei Ausgabe-Stürmen
/// nicht mit Einzel-Events geflutet wird.
async fn forward(mut rx: mpsc::UnboundedReceiver<LogLine>, state: Arc<Mutex<State>>, sink: EventSink, instance: String, id: String) {
    while let Some(first) = rx.recv().await {
        let mut batch = vec![first];
        while batch.len() < LOG_BATCH_MAX
            && let Ok(line) = rx.try_recv()
        {
            batch.push(line);
        }
        {
            let mut state = state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            let history = state.logs.entry(id.clone()).or_default();
            history.extend(batch.iter().cloned());
            while history.len() > LOG_HISTORY {
                history.pop_front();
            }
        }
        sink(GameEvent::Logs { instance_id: instance.clone(), key: id.clone(), lines: batch });
        tokio::time::sleep(LOG_BATCH_INTERVAL).await;
    }
}

// --- Log teilen (mclo.gs) --------------------------------------------------------

const MCLOGS_API: &str = "https://api.mclo.gs/1/log";
const MAX_SHARE_LINES: usize = 25_000;
const MAX_SHARE_BYTES: usize = 10 << 20;

/// Entfernt Tokens und den Windows-Benutzernamen aus Pfaden, bevor ein Log
/// öffentlich hochgeladen wird.
pub fn redact(text: &str, secrets: &[String]) -> String {
    let mut out = text.to_owned();
    for secret in secrets.iter().filter(|s| s.len() >= 8) {
        out = out.replace(secret.as_str(), "********");
    }
    let lines: Vec<String> = out.lines().map(|l| redact_line(&hide_user_dirs(l))).collect();
    lines.join("\n")
}

fn redact_line(line: &str) -> String {
    let mut words: Vec<String> = Vec::new();
    let mut hide_next = false;
    for word in line.split(' ') {
        if hide_next {
            words.push("********".into());
            hide_next = false;
            continue;
        }
        if matches!(word, "--accessToken" | "--session") {
            hide_next = true;
            words.push(word.to_owned());
            continue;
        }
        // Session-ID alter Versionen: token:<access>:<uuid>
        if let Some(rest) = word.strip_prefix("token:")
            && rest.len() > 8
        {
            words.push("token:********".into());
            continue;
        }
        // JWTs (Minecraft- und Xbox-Tokens)
        if word.starts_with("eyJ") && word.matches('.').count() >= 2 && word.len() > 40 {
            words.push("********".into());
            continue;
        }
        words.push(word.to_owned());
    }
    words.join(" ")
}

/// `C:\Users\<Name>\…` → `C:\Users\<user>\…`, ebenso `/home/<name>/…` unter
/// Linux (Namen dürfen Leerzeichen enthalten).
fn hide_user_dirs(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut rest = line;
    loop {
        let lower = rest.to_ascii_lowercase();
        let hit = [r"\users\", "/users/", "/home/"].iter().filter_map(|m| lower.find(m).map(|i| (i, *m))).min();
        let Some((start, marker)) = hit else {
            out.push_str(rest);
            return out;
        };
        let name_start = start + marker.len();
        let sep = if marker.starts_with('\\') { '\\' } else { '/' };
        let name_end = rest[name_start..].find(sep).map_or(rest.len(), |i| name_start + i);
        out.push_str(&rest[..name_start]);
        out.push_str("<user>");
        rest = &rest[name_end..];
    }
}

#[derive(Deserialize)]
struct MclogsResponse {
    success: bool,
    url: Option<String>,
}

/// Lädt einen (bereits geschwärzten) Log hoch und liefert den Link.
pub async fn share_log(http: &reqwest::Client, text: &str) -> Result<String> {
    let lines: Vec<&str> = text.lines().collect();
    let start = lines.len().saturating_sub(MAX_SHARE_LINES);
    let mut content = lines[start..].join("\n");
    if content.len() > MAX_SHARE_BYTES {
        let cut = content.len() - MAX_SHARE_BYTES;
        let cut = (cut..content.len()).find(|&i| content.is_char_boundary(i)).unwrap_or(content.len());
        content = content[cut..].to_owned();
    }
    if content.trim().is_empty() {
        return Err(Error::validation(crate::msg!("launcher.noLogToShare", "Es gibt noch keinen Log zum Teilen.")));
    }
    let response: MclogsResponse =
        http.post(MCLOGS_API).form(&[("content", content)]).send().await?.error_for_status()?.json().await?;
    match response.url {
        Some(url) if response.success && url.starts_with("https://mclo.gs/") => Ok(url),
        _ => Err(Error::validation(crate::msg!("process.logUploadFailed", "Der Log konnte nicht hochgeladen werden."))),
    }
}

/// Neueste Log-Datei der Instanz: Spiel-Log, sonst die vom Launcher mitgeschriebene Ausgabe.
pub fn latest_log_file(game_dir: &Path, launcher_logs: &Path) -> Option<PathBuf> {
    [game_dir.join("logs").join("latest.log"), launcher_logs.join("launcher-stdout.log")]
        .into_iter()
        .filter(|p| p.is_file())
        .max_by_key(|p| std::fs::metadata(p).and_then(|m| m.modified()).ok())
}

pub async fn read_log_file(path: &Path) -> Result<String> {
    let bytes = tokio::fs::read(path).await.map_err(|e| Error::io(path, e))?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gamelog::Level;

    fn line(msg: &str) -> LogLine {
        LogLine { time: 0, level: Level::Error, thread: None, logger: None, message: msg.into() }
    }

    #[test]
    fn diagnoses_common_crashes() {
        let d = diagnose(&[line("Caused by: java.util.zip.ZipException: zip END header not found")]).unwrap();
        assert_eq!((d.kind, d.can_repair), (DiagnosisKind::CorruptFiles, true));
        assert_eq!(diagnose(&[line("java.lang.OutOfMemoryError: Java heap space")]).unwrap().kind, DiagnosisKind::OutOfMemory);
        assert_eq!(
            diagnose(&[line("java.lang.UnsupportedClassVersionError: x has been compiled by a more recent version of the Java Runtime")]).unwrap().kind,
            DiagnosisKind::WrongJava
        );
        assert_eq!(
            diagnose(&[line("Mod 'Sodium Extra' (sodium-extra) requires any version of sodium, which is missing!")]).unwrap().kind,
            DiagnosisKind::MissingDependency
        );
        assert_eq!(diagnose(&[line("Mixin apply failed foo.mixins.json:BarMixin")]).unwrap().kind, DiagnosisKind::ModConflict);
        assert!(diagnose(&[line("Stopping!")]).is_none());
        assert_eq!(d.code, "process.crashCorruptFiles");
        let json = serde_json::to_value(&d).unwrap();
        assert_eq!(json["code"], "process.crashCorruptFiles");
        assert!(json["message"].as_str().unwrap().starts_with("Eine Spieldatei ist beschädigt"));
    }

    /// Genau die Meldung aus dem Fehlerbericht (Fabric Loader 0.19.5, MC 1.21.11).
    const SODIUM_IRIS: &str = "Incompatible mods found!
net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!
A potential solution has been determined, this may resolve your problem:
\t - Replace mod 'Sodium' (sodium) 0.8.14+mc1.21.11 with any 0.8.x version that is compatible with: iris 1.10.7+mc1.21.11.
More details:
\t - Mod 'Sodium' (sodium) 0.8.14+mc1.21.11 is incompatible with version 1.10.7 or earlier of mod 'Iris' (iris), yet a conflicting version is present: 1.10.7+mc1.21.11!";

    #[test]
    fn diagnoses_incompatible_mod_versions() {
        let d = diagnose(&[line(SODIUM_IRIS)]).unwrap();
        assert_eq!(d.kind, DiagnosisKind::IncompatibleMod);
        let c = d.conflict.as_ref().unwrap();
        assert_eq!(
            (c.mod_id.as_str(), c.mod_name.as_str(), c.mod_version.as_str()),
            ("sodium", "Sodium", "0.8.14+mc1.21.11")
        );
        assert_eq!((c.other_id.as_str(), c.other_name.as_str()), ("iris", "Iris"));
        assert_eq!(c.other_version.as_deref(), Some("1.10.7+mc1.21.11"));
        assert_eq!(d.code, "process.crashIncompatibleMod");
        assert!(d.message.contains("Sodium 0.8.14+mc1.21.11") && d.message.contains("Iris 1.10.7+mc1.21.11"));
        let json = serde_json::to_value(&d).unwrap();
        assert_eq!(json["kind"], "incompatible_mod");
        assert_eq!(json["conflict"]["modId"], "sodium");

        // Auch über mehrere Log-Zeilen verteilt und nur mit dem Lösungsvorschlag.
        let lines: Vec<LogLine> = SODIUM_IRIS.lines().map(line).collect();
        assert_eq!(diagnose(&lines).unwrap().conflict.unwrap().other_id, "iris");
        let only_hint = parse_mod_conflict(
            "\t - Replace mod 'Sodium' (sodium) 0.8.14+mc1.21.11 with any 0.8.x version that is compatible with: iris 1.10.7+mc1.21.11.",
        )
        .unwrap();
        assert_eq!((only_hint.mod_id.as_str(), only_hint.other_id.as_str()), ("sodium", "iris"));
        assert_eq!(only_hint.other_version.as_deref(), Some("1.10.7+mc1.21.11"));

        // Falsche Version einer Abhängigkeit.
        let c = parse_mod_conflict(
            "Mod 'Sodium Extra' (sodium-extra) 0.6.0 requires version 0.9.0 or later of mod 'Sodium' (sodium), but only the wrong version is present: 0.8.14!",
        )
        .unwrap();
        assert_eq!((c.mod_id.as_str(), c.other_id.as_str(), c.other_version.as_deref()), ("sodium-extra", "sodium", Some("0.8.14")));
        // Fehlende Mod bleibt „fehlende Abhängigkeit“, Mixin-Fehler bleibt „Konflikt“.
        assert_eq!(
            diagnose(&[line("Mod 'Sodium Extra' (sodium-extra) requires any version of sodium, which is missing!")]).unwrap().kind,
            DiagnosisKind::MissingDependency
        );
        assert_eq!(diagnose(&[line("Incompatible mods found!")]).unwrap().kind, DiagnosisKind::ModConflict);
        assert!(parse_mod_conflict("Mod '../x' (bad id!) 1 is incompatible with version 1 of mod 'Y' (y), yet: 1!").is_none());
    }

    /// Genau der Log aus dem Fehlerbericht (mclo.gs SFN97JF, Fabric Loader 0.19.5, MC 1.21.11).
    const MORE_CULLING_CLOTH: &str = "Loading Minecraft 1.21.11 with Fabric Loader 0.19.5
Mod resolution failed
Immediate reason: [HARD_DEP_NO_CANDIDATE moreculling 1.6.2 {depends cloth-config @ [>=16.0.0]}, ROOT_FORCELOAD_SINGLE moreculling 1.6.2]
Reason: [HARD_DEP moreculling 1.6.2 {depends cloth-config @ [>=16.0.0]}]
Fix: add [add:cloth-config 16.0.0 ([[16.0.0,∞)])], remove [], replace []
Incompatible mods found!
net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!
A potential solution has been determined, this may resolve your problem:
\t - Install cloth-config, version 16.0.0 or later.
More details:
\t - Mod 'More Culling' (moreculling) 1.6.2 requires version 16.0.0 or later of cloth-config, which is missing!
\tat net.fabricmc.loader.impl.FormattedException.ofLocalized(FormattedException.java:51)";

    #[test]
    fn diagnoses_missing_dependencies_with_a_fix() {
        let lines: Vec<LogLine> = MORE_CULLING_CLOTH.lines().map(line).collect();
        let d = diagnose(&lines).unwrap();
        assert_eq!(d.kind, DiagnosisKind::MissingDependency);
        let m = d.missing.as_ref().unwrap();
        assert_eq!(m.mod_id.as_deref(), Some("moreculling"));
        assert_eq!(m.mod_name.as_deref(), Some("More Culling"));
        assert_eq!(m.dependencies, ["cloth-config"]);
        assert_eq!(d.code, "process.crashMissingDependencyNamed");
        assert!(d.message.contains("More Culling") && d.message.contains("cloth-config"));
        let json = serde_json::to_value(&d).unwrap();
        assert_eq!(json["kind"], "missing_dependency");
        assert_eq!(json["missing"]["dependencies"][0], "cloth-config");
        assert_eq!(json["params"]["deps"], "cloth-config");

        // Ältere Wortwahl, mehrere fehlende Mods, nur der Lösungsvorschlag, (Neo)Forge.
        let m = parse_missing_dependency(
            "Mod 'Sodium Extra' (sodium-extra) requires any version of sodium, which is missing!\n\
             Mod 'X' (xmod) 1.0 requires any version of mod fabric-api, which is missing!",
        )
        .unwrap();
        assert_eq!((m.mod_id.as_deref(), m.dependencies.as_slice()), (Some("sodium-extra"), &["sodium".to_owned(), "fabric-api".to_owned()][..]));
        let m = parse_missing_dependency("\t - Install fabric-api, any version.").unwrap();
        assert_eq!((m.mod_id, m.dependencies), (None, vec!["fabric-api".to_owned()]));
        let m = parse_missing_dependency(
            "Missing or unsupported mandatory dependencies:\n\tMod ID: 'cloth_config', Requested by: 'moreculling', Expected range: '[15,)', Actual version: '[MISSING]'",
        )
        .unwrap();
        assert_eq!((m.mod_id.as_deref(), m.dependencies.as_slice()), (Some("moreculling"), &["cloth_config".to_owned()][..]));
        let only = diagnose(&[line("Mod 'A' (a) requires any version of b, which is missing!")]).unwrap();
        assert_eq!(only.params["name"], "A");
        // Unsinn wird nicht übernommen.
        assert!(parse_missing_dependency("Mod 'A' (a) requires any version of ../../evil path, which is missing!").is_none());
        assert!(parse_missing_dependency("Install the latest drivers.").is_none());
    }

    #[test]
    fn notice_carries_code_and_params() {
        let err = Error::launch(crate::msg!("hooks.postExitExitCode", "Fehlgeschlagen (Exit-Code {code}).", code = 3));
        let json = serde_json::to_value(GameEvent::notice_error("a".into(), &err)).unwrap();
        assert_eq!(json["type"], "notice");
        assert_eq!(json["instanceId"], "a");
        assert_eq!(json["code"], "hooks.postExitExitCode");
        assert_eq!(json["params"]["code"], "3");
        assert_eq!(json["message"], "Fehlgeschlagen (Exit-Code 3).");

        // Fehler ohne eigene Meldung: fester Code, keine Pfade, keine leeren Parameter.
        let io = Error::io("C:\\geheim", std::io::Error::other("x"));
        let json = serde_json::to_value(GameEvent::notice_error("a".into(), &io)).unwrap();
        assert_eq!(json["code"], "io");
        assert!(json.get("params").is_none());
        assert!(!json["message"].as_str().unwrap().contains("geheim"));
    }

    #[test]
    fn redaction() {
        let raw = "Setting user: Steve\n(Session ID is token:abcdefghijklmnop:1234)\n\
                   args --accessToken eyJhbGciOi.xyz.abc --version 1.8.9\n\
                   Loading C:\\Users\\Max Mustermann\\AppData\\x.jar and /Users/max/y.jar\n\
                   Linux: /home/max/.local/share/TRS-Launcher/z.jar\n\
                   geheim-token-12345 im Text";
        let out = redact(raw, &["geheim-token-12345".into()]);
        assert!(!out.contains("abcdefghijklmnop"));
        assert!(!out.contains("eyJhbGciOi"));
        assert!(!out.contains("geheim-token-12345"));
        assert!(out.contains("--version 1.8.9"));
        assert!(out.contains("C:\\Users\\<user>\\AppData"), "{out}");
        assert!(out.contains("/Users/<user>/y.jar"));
        assert!(out.contains("/home/<user>/.local/share/TRS-Launcher/z.jar"), "{out}");
    }

    #[test]
    fn tail_reads_growing_file_and_partial_lines() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("out.log");
        std::fs::write(&path, b"[12:00:00] [main/INFO]: eins\n[12:00:01] [main/WA").unwrap();
        let (bytes, offset) = read_from(&path, 0).unwrap();
        assert!(bytes.ends_with(b"[main/WA"));
        std::fs::OpenOptions::new().append(true).open(&path).unwrap();
        let mut f = std::fs::OpenOptions::new().append(true).open(&path).unwrap();
        std::io::Write::write_all(&mut f, b"RN]: zwei\n").unwrap();
        let (more, _) = read_from(&path, offset).unwrap();
        assert_eq!(more, b"RN]: zwei\n");
        // Gekürzte Datei → neues, kleineres Offset
        std::fs::write(&path, b"x").unwrap();
        assert_eq!(read_from(&path, offset).unwrap().1, 1);
    }

    #[tokio::test]
    async fn detached_game_is_tracked_and_can_be_killed() {
        let dir = tempfile::tempdir().unwrap();
        let events = Arc::new(Mutex::new(Vec::new()));
        let sink_events = events.clone();
        let manager = GameManager::new(
            Arc::new(move |e: GameEvent| sink_events.lock().unwrap().push(e)),
            dir.path().join("running.json"),
        );
        // Ein harmloser Dauerläufer statt Java.
        let (program, args): (&str, Vec<String>) = if cfg!(windows) {
            (r"C:\Windows\System32\PING.EXE", vec!["-n".into(), "30".into(), "127.0.0.1".into()])
        } else {
            ("/bin/sh", vec!["-c".into(), "while true; do echo tick; sleep 1; done".into()])
        };
        let command =
            Command { program: PathBuf::from(program), args, cwd: dir.path().to_owned(), env: Vec::new(), high_priority: false };
        manager.spawn("test", command, &dir.path().join("logs"), vec![], Box::new(|_| {}), false).unwrap();
        assert!(manager.is_running("test"));
        let saved = std::fs::read_to_string(dir.path().join("running.json")).unwrap();
        assert!(saved.contains("\"instanceId\": \"test\""));

        // Unter Last (parallele Tests) kann die erste Ausgabe etwas dauern.
        for _ in 0..50 {
            if !manager.logs("test").is_empty() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert!(!manager.logs("test").is_empty(), "stdout sollte mitgelesen werden");
        assert!(manager.kill("test"));
        for _ in 0..50 {
            if !manager.is_running("test") {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert!(!manager.is_running("test"));
        tokio::time::sleep(Duration::from_millis(200)).await;
        let exited = events.lock().unwrap().iter().any(|e| matches!(e, GameEvent::Exited { crashed: false, .. }));
        assert!(exited, "Stoppen ist kein Absturz");
        assert!(!std::fs::read_to_string(dir.path().join("running.json")).unwrap().contains("test"));
    }

    fn engine_manager() -> (tempfile::TempDir, Arc<Mutex<Vec<GameEvent>>>, GameManager) {
        let dir = tempfile::tempdir().unwrap();
        let events = Arc::new(Mutex::new(Vec::new()));
        let sink_events = events.clone();
        let manager =
            GameManager::new(Arc::new(move |e: GameEvent| sink_events.lock().unwrap().push(e)), dir.path().join("running.json"));
        (dir, events, manager)
    }

    #[test]
    fn engine_session_runs_logs_and_books_play_time() {
        let (dir, events, manager) = engine_manager();
        let stopped = Arc::new(AtomicBool::new(false));
        let stop_flag = stopped.clone();
        let booked = Arc::new(Mutex::new(None));
        let booked_in = booked.clone();
        manager
            .attach_engine(
                "inst",
                "g1",
                vec!["geheimes-token-123".into()],
                Arc::new(move || {
                    stop_flag.store(true, Ordering::Relaxed);
                    true
                }),
                Box::new(move |secs| *booked_in.lock().unwrap() = Some(secs)),
            )
            .unwrap();
        // Läuft wie ein Desktop-Spiel (PID 0), die Sitzung ist gemerkt.
        assert!(manager.is_running("inst"));
        assert_eq!(manager.running()[0].pid, 0);
        assert!(std::fs::read_to_string(dir.path().join("engine-sessions.json")).unwrap().contains("\"session\": \"g1\""));
        // Zweiter Start derselben Instanz geht nicht.
        assert!(manager.attach_engine("inst", "g2", vec![], Arc::new(|| true), Box::new(|_| {})).is_err());

        manager.engine_logs("g1", &["[12:00:00] [Render thread/INFO]: token geheimes-token-123".into(), String::new()]);
        manager.engine_logs("unbekannt", &["nichts".into()]);
        let logs = manager.logs("inst");
        assert_eq!(logs.len(), 1);
        assert!(logs[0].message.contains("********") && !logs[0].message.contains("geheimes"));

        assert!(manager.kill("inst"));
        assert!(stopped.load(Ordering::Relaxed));
        // Beendet über den Launcher: kein Absturz, auch wenn die Engine „crashed“ meldet.
        manager.engine_exited("g1", Some(1), true, &[]);
        assert!(!manager.is_running("inst"));
        assert!(booked.lock().unwrap().is_some());
        assert!(!dir.path().join("engine-sessions.json").exists());
        let events = events.lock().unwrap();
        assert!(matches!(events.first(), Some(GameEvent::Started { pid: 0, .. })));
        assert!(events.iter().any(|e| matches!(e, GameEvent::Logs { lines, .. } if lines.len() == 1)));
        assert!(matches!(events.last(), Some(GameEvent::Exited { crashed: false, exit_code: Some(1), .. })));
        // Doppeltes Ende wird ignoriert.
        drop(events);
        manager.engine_exited("g1", Some(0), false, &[]);
    }

    #[test]
    fn engine_stop_may_report_the_end_right_away() {
        // Die Engine meldet „exited“ noch im Stop-Aufruf (gleicher Thread) – darf nicht hängen.
        let (_dir, _events, manager) = engine_manager();
        let manager = Arc::new(manager);
        let weak = Arc::downgrade(&manager);
        let stop: StopFn = Arc::new(move || {
            if let Some(m) = weak.upgrade() {
                m.engine_exited("s", Some(0), false, &[]);
            }
            true
        });
        manager.attach_engine("x", "s", vec![], stop, Box::new(|_| {})).unwrap();
        assert!(manager.kill("x"));
        assert!(!manager.is_running("x"));
    }

    #[test]
    fn engine_crash_uses_log_tail_and_offline_end_is_booked() {
        let (_dir, events, manager) = engine_manager();
        manager.attach_engine("a", "s1", vec![], Arc::new(|| true), Box::new(|_| {})).unwrap();
        // Keine Zeilen unterwegs: das Log-Ende der Engine reicht für die Absturz-Auswertung.
        manager.engine_exited("s1", Some(-1), true, &["java.lang.OutOfMemoryError: Java heap space".into()]);
        assert!(!manager.logs("a").is_empty());
        assert!(matches!(events.lock().unwrap().last(), Some(GameEvent::Exited { crashed: true, crash_id: Some(_), .. })));

        // Log schon da: nur der Ende-Bericht der Engine kommt dazu, keine doppelten Zeilen.
        manager.attach_engine("c", "s3", vec![], Arc::new(|| true), Box::new(|_| {})).unwrap();
        manager.engine_logs("s3", &["[12:00:00] [Render thread/INFO]: hello".into()]);
        let before = manager.logs("c").len();
        let tail: Vec<String> = vec![
            "[12:00:00] [Render thread/INFO]: hello".into(),
            format!("{ENGINE_EXIT_MARKER}: nativer Absturz (Status 11)"),
            "[TRS] signal 11 (SIGSEGV)".into(),
        ];
        manager.engine_exited("s3", Some(-1), true, &tail);
        assert_eq!(manager.logs("c").len(), before + 2);

        // iOS: Spiel endete ohne laufenden Launcher – beim nächsten Start nachtragen.
        manager.attach_engine("b", "s2", vec![], Arc::new(|| true), Box::new(|_| {})).unwrap();
        let records = manager.take_engine_records();
        assert_eq!(records.len(), 1);
        assert!(manager.take_engine_records().is_empty());
        let booked = Arc::new(Mutex::new(0));
        let booked_in = booked.clone();
        let ended = records[0].started_at + chrono::Duration::seconds(90);
        manager.engine_recovered(&records[0], ended, Some(0), false, &[], Box::new(move |secs| *booked_in.lock().unwrap() = secs));
        assert_eq!(*booked.lock().unwrap(), 90);
        assert!(matches!(events.lock().unwrap().last(), Some(GameEvent::Exited { play_seconds: 90, crashed: false, .. })));
    }

    #[test]
    fn extra_keys() {
        assert_eq!(extra_key("a", 2), "a~2");
        assert!(is_extra_key("a", "a~2") && !is_extra_key("a", "a"));
        // Alte running.json ohne `key`.
        let old: SessionRecord = serde_json::from_str(
            r#"{"instanceId":"a","pid":1,"startedAt":"2026-01-01T00:00:00Z","creationTime":1,"stdoutLog":"x","stderrLog":"y"}"#,
        )
        .unwrap();
        assert_eq!(old.key(), "a");
    }

    #[tokio::test]
    async fn second_process_of_an_instance_has_its_own_key_logs_and_exit() {
        let dir = tempfile::tempdir().unwrap();
        let events = Arc::new(Mutex::new(Vec::new()));
        let sink_events = events.clone();
        let manager = GameManager::new(
            Arc::new(move |e: GameEvent| sink_events.lock().unwrap().push(e)),
            dir.path().join("running.json"),
        );
        let (program, args): (&str, Vec<String>) = if cfg!(windows) {
            (r"C:\Windows\System32\PING.EXE", vec!["-n".into(), "30".into(), "127.0.0.1".into()])
        } else {
            ("/bin/sh", vec!["-c".into(), "while true; do echo tick; sleep 1; done".into()])
        };
        let command = || Command {
            program: PathBuf::from(program),
            args: args.clone(),
            cwd: dir.path().to_owned(),
            env: Vec::new(),
            high_priority: false,
        };
        let logs = dir.path().join("logs");
        manager.spawn("inst", command(), &logs, vec![], Box::new(|_| {}), false).unwrap();
        // Ohne `extra` bleibt es bei einem Prozess je Instanz.
        assert!(manager.spawn("inst", command(), &logs, vec![], Box::new(|_| {}), false).is_err());
        manager.spawn("inst", command(), &logs, vec![], Box::new(|_| {}), true).unwrap();
        manager.spawn("inst", command(), &logs, vec![], Box::new(|_| {}), true).unwrap();
        assert_eq!(manager.count("inst"), 3);
        let mut keys: Vec<String> = manager.running().into_iter().map(|g| g.key).collect();
        keys.sort();
        assert_eq!(keys, ["inst", "inst~2", "inst~3"]);
        assert!(logs.join("extra-2").join("launcher-stdout.log").exists());
        assert!(!manager.is_running("other"));

        // Einen Prozess beenden: die anderen laufen weiter.
        assert!(!manager.kill_key("other", "inst~2"));
        assert!(manager.kill_key("inst", "inst~2"));
        for _ in 0..50 {
            if manager.count("inst") == 2 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert_eq!(manager.count("inst"), 2);
        assert!(manager.is_running("inst"));
        // Die Sitzungsdatei vergisst nur den beendeten Prozess.
        tokio::time::sleep(Duration::from_millis(300)).await;
        let saved = std::fs::read_to_string(dir.path().join("running.json")).unwrap();
        assert!(saved.contains("\"key\": \"inst\"") && saved.contains("\"key\": \"inst~3\"") && !saved.contains("inst~2"), "{saved}");
        // Der freie Schlüssel wird wieder vergeben.
        manager.spawn("inst", command(), &logs, vec![], Box::new(|_| {}), true).unwrap();
        assert!(manager.running().iter().any(|g| g.key == "inst~2"));

        // Alle auf einmal beenden.
        assert!(manager.kill("inst"));
        for _ in 0..50 {
            if !manager.is_running("inst") {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert!(!manager.is_running("inst"));
        tokio::time::sleep(Duration::from_millis(300)).await;
        let exited: Vec<String> = events
            .lock()
            .unwrap()
            .iter()
            .filter_map(|e| match e {
                GameEvent::Exited { key, crashed: false, .. } => Some(key.clone()),
                _ => None,
            })
            .collect();
        assert_eq!(exited.len(), 4, "{exited:?}");
    }
}
