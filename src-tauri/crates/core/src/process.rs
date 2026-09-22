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
use windows::Win32::Foundation::{CloseHandle, FILETIME, HANDLE, WAIT_OBJECT_0};
use windows::Win32::System::Threading::{
    GetExitCodeProcess, GetProcessTimes, INFINITE, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION,
    PROCESS_SYNCHRONIZE, PROCESS_TERMINATE, TerminateProcess, WaitForSingleObject,
};

use crate::gamelog::{LogLine, LogParser};
use crate::launch::Command;
use crate::{Error, Result};

const LOG_HISTORY: usize = 5000;
const LOG_BATCH_MAX: usize = 400;
const LOG_BATCH_INTERVAL: Duration = Duration::from_millis(60);
const TAIL_INTERVAL: Duration = Duration::from_millis(150);
/// Für die Diagnose reicht das Ende des Logs.
const DIAGNOSIS_LINES: usize = 400;

const DETACHED_PROCESS: u32 = 0x0000_0008;
const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;

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
    /// Für den Nutzer formuliert – was los ist und was hilft.
    pub message: String,
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
            "Eine Spieldatei ist beschädigt (z. B. nach einem abgebrochenen Download). \
             „Dateien prüfen“ lädt sie neu herunter.",
            true,
        )
    } else if has("java.lang.OutOfMemoryError") {
        (
            DiagnosisKind::OutOfMemory,
            "Dem Spiel ist der Arbeitsspeicher ausgegangen. Erhöhe den Arbeitsspeicher der Instanz \
             in den Einstellungen.",
            false,
        )
    } else if has("UnsupportedClassVersionError") || has("has been compiled by a more recent version of the Java Runtime") {
        (
            DiagnosisKind::WrongJava,
            "Eine Mod braucht eine neuere Java-Version. Entferne den eigenen Java-Pfad in den \
             Einstellungen, dann wählt der Launcher automatisch die passende.",
            false,
        )
    } else if has("requires") && (has("which is missing") || has("but it is missing"))
        || has("Missing or unsupported mandatory dependencies")
        || has("Could not find required mod")
    {
        (
            DiagnosisKind::MissingDependency,
            "Einer Mod fehlt eine benötigte andere Mod. Welche, steht im Log direkt über dem Fehler.",
            false,
        )
    } else if has("Mixin apply failed") || has("MixinApplyError") || has("InvalidInjectionException") || has("Incompatible mods found") {
        (
            DiagnosisKind::ModConflict,
            "Zwei Mods vertragen sich nicht. Deaktiviere zuletzt hinzugefügte Mods und starte erneut.",
            false,
        )
    } else if has("EXCEPTION_ACCESS_VIOLATION") && (has("atio6axx") || has("nvoglv") || has("ig9icd") || has("ig7icd"))
        || has("Pixel format not accelerated")
        || has("GLFW error 65542")
        || has("WGL: The driver does not appear to support OpenGL")
    {
        (
            DiagnosisKind::GraphicsDriver,
            "Der Grafiktreiber ist abgestürzt oder unterstützt OpenGL nicht. Aktualisiere den \
             Treiber deiner Grafikkarte.",
            false,
        )
    } else {
        return None;
    };
    Some(Diagnosis { kind, message: message.to_owned(), can_repair })
}

// --- Windows-Prozess-Handle ------------------------------------------------------

struct ProcessHandle(HANDLE);

// SAFETY: Ein Prozess-Handle ist ein Kernel-Objekt; es darf von beliebigen
// Threads benutzt werden. Geschlossen wird es genau einmal im Drop.
unsafe impl Send for ProcessHandle {}
unsafe impl Sync for ProcessHandle {}

impl ProcessHandle {
    fn open(pid: u32) -> Option<Self> {
        let access = PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE | PROCESS_TERMINATE;
        // SAFETY: reiner API-Aufruf; ein ungültiger PID liefert einen Fehler.
        unsafe { OpenProcess(access, false, pid) }.ok().map(Self)
    }

    /// Startzeit als FILETIME-Wert – unterscheidet einen wiederverwendeten PID.
    fn creation_time(&self) -> Option<u64> {
        let (mut created, mut exited, mut kernel, mut user) =
            (FILETIME::default(), FILETIME::default(), FILETIME::default(), FILETIME::default());
        // SAFETY: gültiges Handle, alle Ausgabezeiger zeigen auf lokale Werte.
        unsafe { GetProcessTimes(self.0, &mut created, &mut exited, &mut kernel, &mut user) }.ok()?;
        Some((u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime))
    }

    fn is_alive(&self) -> bool {
        // SAFETY: gültiges Handle; Timeout 0 fragt nur den Zustand ab.
        (unsafe { WaitForSingleObject(self.0, 0) }) != WAIT_OBJECT_0
    }

    /// Blockiert bis zum Prozessende.
    fn wait(&self) -> Option<i32> {
        // SAFETY: gültiges Handle.
        unsafe {
            WaitForSingleObject(self.0, INFINITE);
            let mut code = 0u32;
            GetExitCodeProcess(self.0, &mut code).ok()?;
            Some(code as i32)
        }
    }

    fn terminate(&self) -> bool {
        // SAFETY: gültiges Handle mit PROCESS_TERMINATE.
        unsafe { TerminateProcess(self.0, 1) }.is_ok()
    }
}

impl Drop for ProcessHandle {
    fn drop(&mut self) {
        // SAFETY: Handle stammt aus OpenProcess und wird nur hier geschlossen.
        unsafe {
            let _ = CloseHandle(self.0);
        }
    }
}

/// Windows soll für dieses Programm die leistungsstarke Grafikkarte nehmen
/// (dieselbe Einstellung wie unter „Grafikeinstellungen“ in Windows).
pub fn prefer_dedicated_gpu(program: &Path) {
    use windows::Win32::System::Registry::{HKEY_CURRENT_USER, REG_SZ, RegSetKeyValueW};
    use windows::core::{HSTRING, w};

    let value: Vec<u16> = "GpuPreference=2;".encode_utf16().chain(std::iter::once(0)).collect();
    // SAFETY: alle Strings sind nullterminiert, die Datenlänge stimmt in Bytes.
    let status = unsafe {
        RegSetKeyValueW(
            HKEY_CURRENT_USER,
            w!("Software\\Microsoft\\DirectX\\UserGpuPreferences"),
            &HSTRING::from(program.as_os_str()),
            REG_SZ.0,
            Some(value.as_ptr().cast()),
            (value.len() * 2) as u32,
        )
    };
    if status.is_err() {
        tracing::warn!("GPU-Präferenz konnte nicht gesetzt werden: {status:?}");
    }
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
        use std::os::windows::process::CommandExt;

        if self.is_running(instance_id) {
            return Err(Error::launch("Diese Instanz läuft bereits."));
        }
        std::fs::create_dir_all(log_dir).map_err(|e| Error::io(log_dir, e))?;
        let stdout_log = log_dir.join("launcher-stdout.log");
        let stderr_log = log_dir.join("launcher-stderr.log");
        let stdout = std::fs::File::create(&stdout_log).map_err(|e| Error::io(&stdout_log, e))?;
        let stderr = std::fs::File::create(&stderr_log).map_err(|e| Error::io(&stderr_log, e))?;

        let child = std::process::Command::new(&command.program)
            .args(&command.args)
            .current_dir(&command.cwd)
            .stdin(std::process::Stdio::null())
            .stdout(stdout)
            .stderr(stderr)
            // Eigene Prozessgruppe ohne Konsole: Das Spiel überlebt den Launcher.
            .creation_flags(DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP)
            .spawn()
            .map_err(|e| {
                tracing::error!("Java konnte nicht gestartet werden ({}): {e}", command.program.display());
                Error::launch("Java konnte nicht gestartet werden.")
            })?;
        let pid = child.id();
        // Solange `child` lebt, existiert das Prozessobjekt sicher – erst öffnen, dann loslassen.
        let process = ProcessHandle::open(pid).ok_or_else(|| Error::launch("Das Spiel wurde sofort wieder beendet."))?;
        drop(child);

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
            let crashed = !was_killed && exit_code != Some(0);
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

/// `C:\Users\<Name>\…` → `C:\Users\<user>\…` (Namen dürfen Leerzeichen enthalten).
fn hide_user_dirs(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut rest = line;
    loop {
        let lower = rest.to_ascii_lowercase();
        let hit = [r"\users\", "/users/"].iter().filter_map(|m| lower.find(m).map(|i| (i, *m))).min();
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
        return Err(Error::validation("Es gibt noch keinen Log zum Teilen."));
    }
    let response: MclogsResponse =
        http.post(MCLOGS_API).form(&[("content", content)]).send().await?.error_for_status()?.json().await?;
    match response.url {
        Some(url) if response.success && url.starts_with("https://mclo.gs/") => Ok(url),
        _ => Err(Error::validation("Der Log konnte nicht hochgeladen werden.")),
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
    }

    #[test]
    fn redaction() {
        let raw = "Setting user: Steve\n(Session ID is token:abcdefghijklmnop:1234)\n\
                   args --accessToken eyJhbGciOi.xyz.abc --version 1.8.9\n\
                   Loading C:\\Users\\Max Mustermann\\AppData\\x.jar and /Users/max/y.jar\n\
                   geheim-token-12345 im Text";
        let out = redact(raw, &["geheim-token-12345".into()]);
        assert!(!out.contains("abcdefghijklmnop"));
        assert!(!out.contains("eyJhbGciOi"));
        assert!(!out.contains("geheim-token-12345"));
        assert!(out.contains("--version 1.8.9"));
        assert!(out.contains("C:\\Users\\<user>\\AppData"), "{out}");
        assert!(out.contains("/Users/<user>/y.jar"));
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
        let command = Command {
            program: PathBuf::from(r"C:\Windows\System32\PING.EXE"),
            args: vec!["-n".into(), "30".into(), "127.0.0.1".into()],
            cwd: dir.path().to_owned(),
        };
        manager.spawn("test", command, &dir.path().join("logs"), vec![], Box::new(|_| {})).unwrap();
        assert!(manager.is_running("test"));
        let saved = std::fs::read_to_string(dir.path().join("running.json")).unwrap();
        assert!(saved.contains("\"instanceId\": \"test\""));

        tokio::time::sleep(Duration::from_millis(1500)).await;
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
