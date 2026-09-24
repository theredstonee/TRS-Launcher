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

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum GameEvent {
    Started { instance_id: String, pid: u32 },
    Logs { instance_id: String, lines: Vec<LogLine> },
    Exited {
        instance_id: String,
        exit_code: Option<i32>,
        crashed: bool,
        play_seconds: u64,
        diagnosis: Option<Diagnosis>,
    },
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

pub type EventSink = Arc<dyn Fn(GameEvent) + Send + Sync>;

/// Was nach dem Spielende passieren soll (Spielzeit verbuchen).
pub type OnExit = Box<dyn FnOnce(u64) + Send>;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunningGame {
    pub instance_id: String,
    pub pid: u32,
    pub started_at: DateTime<Utc>,
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
    /// „Dateien prüfen & reparieren“ anbieten.
    pub can_repair: bool,
}

/// Sucht in den letzten Log-Zeilen nach bekannten Absturzursachen.
pub fn diagnose(lines: &[LogLine]) -> Option<Diagnosis> {
    let text: String = lines.iter().map(|l| l.message.as_str()).collect::<Vec<_>>().join("\n");
    let has = |needle: &str| text.contains(needle);

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
    } else if has("requires") && (has("which is missing") || has("but it is missing"))
        || has("Missing or unsupported mandatory dependencies")
        || has("Could not find required mod")
    {
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
    Some(Diagnosis { kind, message: message.text, code: message.code, can_repair })
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
    pid: u32,
    started_at: DateTime<Utc>,
    creation_time: u64,
    stdout_log: PathBuf,
    stderr_log: PathBuf,
}

struct Running {
    info: RunningGame,
    process: Arc<ProcessHandle>,
    killed: Arc<AtomicBool>,
}

#[derive(Default)]
struct State {
    running: HashMap<String, Running>,
    logs: HashMap<String, VecDeque<LogLine>>,
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

    pub fn is_running(&self, instance_id: &str) -> bool {
        self.lock().running.contains_key(instance_id)
    }

    pub fn logs(&self, instance_id: &str) -> Vec<LogLine> {
        self.lock().logs.get(instance_id).map(|l| l.iter().cloned().collect()).unwrap_or_default()
    }

    pub fn kill(&self, instance_id: &str) -> bool {
        let state = self.lock();
        let Some(running) = state.running.get(instance_id) else { return false };
        running.killed.store(true, Ordering::Relaxed);
        running.process.terminate()
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
    ) -> Result<u32> {
        if self.is_running(instance_id) {
            return Err(Error::launch(crate::msg!("launcher.alreadyRunning", "Diese Instanz läuft bereits.")));
        }
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
        platform::detach(&mut cmd);
        let child = cmd.spawn().map_err(|e| {
            tracing::error!("Java konnte nicht gestartet werden ({}): {e}", command.program.display());
            Error::launch(crate::msg!("process.javaStartFailed", "Java konnte nicht gestartet werden."))
        })?;
        let pid = child.id();
        let process = ProcessHandle::from_child(child)
            .ok_or_else(|| Error::launch(crate::msg!("process.exitedImmediately", "Das Spiel wurde sofort wieder beendet.")))?;

        let record = SessionRecord {
            instance_id: instance_id.to_owned(),
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
    pub fn recover(&self, on_exit: impl Fn(&str) -> OnExit) {
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
            let exit = on_exit(&record.instance_id);
            self.attach(record, Arc::new(process), Vec::new(), exit);
        }
        self.write_sessions(&alive);
    }

    fn attach(&self, record: SessionRecord, process: Arc<ProcessHandle>, secrets: Vec<String>, on_exit: OnExit) {
        let id = record.instance_id.clone();
        let killed = Arc::new(AtomicBool::new(false));
        {
            let mut state = self.lock();
            state.logs.insert(id.clone(), VecDeque::new());
            state.running.insert(
                id.clone(),
                Running {
                    info: RunningGame { instance_id: id.clone(), pid: record.pid, started_at: record.started_at },
                    process: process.clone(),
                    killed: killed.clone(),
                },
            );
        }
        (self.sink)(GameEvent::Started { instance_id: id.clone(), pid: record.pid });

        let exited = Arc::new(AtomicBool::new(false));
        let secrets: Arc<Vec<String>> = Arc::new(secrets.into_iter().filter(|s| s.len() >= 8).collect());
        let (line_tx, line_rx) = mpsc::unbounded_channel::<LogLine>();
        let tails = [
            tokio::spawn(tail(record.stdout_log.clone(), LogParser::stdout(), line_tx.clone(), secrets.clone(), exited.clone())),
            tokio::spawn(tail(record.stderr_log.clone(), LogParser::stderr(), line_tx, secrets, exited.clone())),
        ];
        let forwarder = tokio::spawn(forward(line_rx, self.state.clone(), self.sink.clone(), id.clone()));

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
            let diagnosis = {
                let mut state = state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                state.running.remove(&id);
                if crashed {
                    let history = state.logs.get(&id).map(|h| {
                        let skip = h.len().saturating_sub(DIAGNOSIS_LINES);
                        h.iter().skip(skip).cloned().collect::<Vec<_>>()
                    });
                    history.as_deref().and_then(diagnose)
                } else {
                    None
                }
            };
            sessions.remove(&id);
            on_exit(play_seconds);
            sink(GameEvent::Exited { instance_id: id, exit_code, crashed, play_seconds, diagnosis });
        });
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

    fn remove(&self, instance_id: &str) {
        self.update(|list| list.retain(|r| r.instance_id != instance_id));
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
async fn forward(mut rx: mpsc::UnboundedReceiver<LogLine>, state: Arc<Mutex<State>>, sink: EventSink, id: String) {
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
        sink(GameEvent::Logs { instance_id: id.clone(), lines: batch });
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
        LogLine { time: 0, level: Level::Error, thread: None, message: msg.into() }
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
        let command = Command { program: PathBuf::from(program), args, cwd: dir.path().to_owned(), env: Vec::new() };
        manager.spawn("test", command, &dir.path().join("logs"), vec![], Box::new(|_| {})).unwrap();
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
}
