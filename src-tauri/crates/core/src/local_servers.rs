//! Lokale Minecraft-Server unter `<root>/servers/<id>/`: starten, stoppen,
//! Befehle schicken, Log mitlesen und Spieler zählen.
//!
//! Der Server läuft als Kindprozess mit Pipes (kein eigenes Fenster). Stoppen
//! schickt `stop` und beendet den Prozess erst nach einer Frist hart – so wird
//! die Welt gespeichert. Beim Beenden des Launchers passiert das für alle.

use std::collections::{BTreeSet, HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt};
use tokio::sync::{Notify, mpsc, watch};

use crate::instance::LoaderKind;
use crate::paths::Paths;
use crate::server_export::files::{LaunchSpec, memory_args};
use crate::{Error, Result};

/// Name der Beschreibung im Server-Ordner.
pub const META_FILE: &str = "trs-server.json";
const MAX_LOG_LINES: usize = 5_000;
const MAX_LINE_CHARS: usize = 4_000;
const MAX_COMMAND_CHARS: usize = 256;
const MAX_SERVERS: usize = 200;
/// So lange darf `stop` dauern, bevor der Prozess beendet wird.
const STOP_GRACE: Duration = Duration::from_secs(60);
const FLUSH_EVERY: Duration = Duration::from_millis(150);

/// Beschreibung eines lokalen Servers (`trs-server.json`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerMeta {
    pub name: String,
    pub game_version: String,
    pub loader: LoaderKind,
    #[serde(default)]
    pub loader_version: Option<String>,
    pub java_major: u32,
    /// Mojang-Runtime für den Server (`java-runtime-delta` …).
    pub java_component: String,
    pub ram_mb: u32,
    pub port: u16,
    pub max_players: u32,
    pub launch: LaunchSpec,
    #[serde(default)]
    pub instance_id: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ServerState {
    Starting,
    Running,
    Stopping,
    Stopped,
}

/// Zustand für die Oberfläche.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerStatus {
    pub state: ServerState,
    /// Spieler online (aus dem Log; `None` = unbekannt).
    pub players: Option<u32>,
    pub player_names: Vec<String>,
    pub max_players: u32,
    pub started_at: Option<DateTime<Utc>>,
    /// Exit-Code nach dem Ende (nicht 0 = Fehler).
    pub exit_code: Option<i32>,
}

impl ServerStatus {
    fn stopped(max_players: u32) -> Self {
        Self { state: ServerState::Stopped, players: None, player_names: Vec::new(), max_players, started_at: None, exit_code: None }
    }
}

/// Ein lokaler Server für die Liste.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalServerInfo {
    pub id: String,
    pub name: String,
    pub game_version: String,
    pub loader: LoaderKind,
    pub loader_version: Option<String>,
    pub port: u16,
    pub ram_mb: u32,
    pub java_major: u32,
    pub eula_accepted: bool,
    pub status: ServerStatus,
}

/// Ereignis an die Oberfläche (Tauri: `local-server`).
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum LocalServerEvent {
    #[serde(rename_all = "camelCase")]
    Logs { id: String, lines: Vec<String> },
    #[serde(rename_all = "camelCase")]
    Status { id: String, status: ServerStatus },
}

pub type LocalServerSink = Arc<dyn Fn(LocalServerEvent) + Send + Sync>;

/// Ein laufender Server.
struct Running {
    commands: mpsc::UnboundedSender<String>,
    kill: Notify,
    exited: watch::Receiver<bool>,
    status: Mutex<ServerStatus>,
    log: Mutex<VecDeque<String>>,
}

impl Running {
    fn status(&self) -> ServerStatus {
        self.status.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone()
    }
}

#[derive(Default)]
pub struct LocalServers {
    running: Mutex<HashMap<String, Arc<Running>>>,
    /// Log des zuletzt beendeten Laufs (damit Fehler sichtbar bleiben).
    last_logs: Mutex<HashMap<String, Vec<String>>>,
    sink: RwLock<Option<LocalServerSink>>,
}

/// Ordnername eines Servers: wie [`crate::server_export::files::slug`] erzeugt.
pub fn validate_server_id(id: &str) -> Result<()> {
    let ok = !id.is_empty()
        && id.len() <= 64
        && !id.starts_with(['.', '-'])
        && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'-' | b'_'));
    if ok {
        Ok(())
    } else {
        Err(Error::validation(crate::msg!("localServer.notFound", "Diesen Server gibt es nicht (mehr).")))
    }
}

pub fn servers_dir(paths: &Paths) -> PathBuf {
    paths.root().join("servers")
}

/// Ordner eines Servers (geprüft, muss existieren und darf keine Verknüpfung sein).
pub fn server_dir(paths: &Paths, id: &str) -> Result<PathBuf> {
    validate_server_id(id)?;
    let dir = servers_dir(paths).join(id);
    match std::fs::symlink_metadata(&dir) {
        Ok(meta) if meta.is_dir() => Ok(dir),
        _ => Err(Error::validation(crate::msg!("localServer.notFound", "Diesen Server gibt es nicht (mehr)."))),
    }
}

pub fn read_meta(dir: &Path) -> Result<ServerMeta> {
    let file = dir.join(META_FILE);
    let text = std::fs::read_to_string(&file).map_err(|e| Error::io(&file, e))?;
    serde_json::from_str(&text).map_err(|e| Error::json("trs-server.json", e))
}

/// Steht in `eula.txt` `eula=true`?
pub fn eula_accepted(dir: &Path) -> bool {
    std::fs::read_to_string(dir.join("eula.txt"))
        .map(|t| t.lines().any(|l| l.trim().eq_ignore_ascii_case("eula=true")))
        .unwrap_or(false)
}

/// Freier Ordnername unter `servers/` für `slug` (`slug`, `slug-2`, …).
pub fn free_id(paths: &Paths, slug: &str) -> String {
    let base = servers_dir(paths);
    let mut id = slug.to_owned();
    let mut n = 2;
    while base.join(&id).exists() {
        id = format!("{slug}-{n}");
        n += 1;
    }
    id
}

/// ANSI-Farbcodes und Steuerzeichen raus, Länge begrenzen.
pub fn clean_line(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len().min(MAX_LINE_CHARS));
    let mut chars = raw.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\u{1b}' {
            // CSI: ESC [ … Endbuchstabe
            if chars.peek() == Some(&'[') {
                chars.next();
                for c in chars.by_ref() {
                    if c.is_ascii_alphabetic() {
                        break;
                    }
                }
            }
            continue;
        }
        if c == '\t' {
            out.push(' ');
        } else if !c.is_control() {
            out.push(c);
        }
        if out.len() >= MAX_LINE_CHARS {
            break;
        }
    }
    out
}

/// Was eine Log-Zeile über den Zustand verrät.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LineEvent {
    Ready,
    Joined(String),
    Left(String),
    /// Antwort auf `list`: Anzahl (und ggf. Maximum).
    Count(u32, Option<u32>),
}

fn is_player_name(name: &str) -> bool {
    (1..=16).contains(&name.len()) && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

/// Nachricht hinter dem Log-Kopf (`[12:00:00] [Server thread/INFO]: …`).
fn message(line: &str) -> Option<&str> {
    let (head, msg) = line.split_once("]: ")?;
    head.contains("INFO").then_some(msg.trim())
}

pub fn parse_line(line: &str) -> Option<LineEvent> {
    let msg = message(line)?;
    if msg.starts_with("Done (") && msg.contains("For help, type") {
        return Some(LineEvent::Ready);
    }
    if let Some(name) = msg.strip_suffix(" joined the game") {
        return is_player_name(name).then(|| LineEvent::Joined(name.to_owned()));
    }
    if let Some(name) = msg.strip_suffix(" left the game") {
        return is_player_name(name).then(|| LineEvent::Left(name.to_owned()));
    }
    // 1.13+: „There are 2 of a max of 20 players online: …“ – älter: „There are 2/20 players online:“
    let rest = msg.strip_prefix("There are ")?;
    let (count, rest) = rest.split_once(' ').unwrap_or((rest, ""));
    if let Some((now, max)) = count.split_once('/') {
        return Some(LineEvent::Count(now.parse().ok()?, max.parse().ok()));
    }
    let now = count.parse().ok()?;
    let max = rest.strip_prefix("of a max of ").and_then(|r| r.split(' ').next()).and_then(|m| m.parse().ok());
    Some(LineEvent::Count(now, max))
}

/// Befehl für die Server-Konsole: eine Zeile, ohne `/`, ohne Steuerzeichen.
pub fn clean_command(command: &str) -> Result<String> {
    let text = command.trim().trim_start_matches('/').trim();
    if text.is_empty() || text.chars().count() > MAX_COMMAND_CHARS || text.chars().any(char::is_control) {
        return Err(Error::validation(crate::msg!(
            "localServer.invalidCommand",
            "Der Befehl ist leer, zu lang oder enthält ungültige Zeichen."
        )));
    }
    Ok(text.to_owned())
}

impl LocalServers {
    pub fn set_sink(&self, sink: LocalServerSink) {
        *self.sink.write().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(sink);
    }

    fn emit(&self, event: LocalServerEvent) {
        let sink = self.sink.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
        if let Some(sink) = sink {
            sink(event);
        }
    }

    fn get(&self, id: &str) -> Option<Arc<Running>> {
        self.running.lock().unwrap_or_else(std::sync::PoisonError::into_inner).get(id).cloned()
    }

    pub fn is_running(&self, id: &str) -> bool {
        self.get(id).is_some()
    }

    pub fn status(&self, id: &str, max_players: u32) -> ServerStatus {
        self.get(id).map_or_else(|| ServerStatus::stopped(max_players), |r| r.status())
    }

    /// Wird `true`, sobald der laufende Server beendet ist (`None` = läuft nicht).
    pub fn exit_watch(&self, id: &str) -> Option<watch::Receiver<bool>> {
        self.get(id).map(|r| r.exited.clone())
    }

    /// Alle Server im Ordner `servers/` (mit gültiger Beschreibung).
    pub async fn list(&self, paths: &Paths) -> Result<Vec<LocalServerInfo>> {
        let base = servers_dir(paths);
        let found = tokio::task::spawn_blocking(move || {
            let mut found = Vec::new();
            let Ok(read) = std::fs::read_dir(&base) else { return found };
            for entry in read.flatten().take(MAX_SERVERS * 2) {
                let Ok(id) = entry.file_name().into_string() else { continue };
                if validate_server_id(&id).is_err() || !entry.file_type().is_ok_and(|t| t.is_dir()) {
                    continue;
                }
                let dir = entry.path();
                let Ok(meta) = read_meta(&dir) else { continue };
                found.push((id, meta, eula_accepted(&dir)));
                if found.len() >= MAX_SERVERS {
                    break;
                }
            }
            found
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))?;
        let mut list: Vec<LocalServerInfo> = found
            .into_iter()
            .map(|(id, meta, eula)| LocalServerInfo {
                status: self.status(&id, meta.max_players),
                id,
                name: meta.name,
                game_version: meta.game_version,
                loader: meta.loader,
                loader_version: meta.loader_version,
                port: meta.port,
                ram_mb: meta.ram_mb,
                java_major: meta.java_major,
                eula_accepted: eula,
            })
            .collect();
        list.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()).then_with(|| a.id.cmp(&b.id)));
        Ok(list)
    }

    /// Log des laufenden (oder zuletzt beendeten) Servers.
    pub fn logs(&self, id: &str) -> Vec<String> {
        if let Some(running) = self.get(id) {
            return running.log.lock().unwrap_or_else(std::sync::PoisonError::into_inner).iter().cloned().collect();
        }
        self.last_logs.lock().unwrap_or_else(std::sync::PoisonError::into_inner).get(id).cloned().unwrap_or_default()
    }

    /// Startet den Server mit `java` (Konsolen-Variante) im Ordner `dir`.
    pub fn start(self: &Arc<Self>, id: &str, dir: &Path, meta: &ServerMeta, java: &Path) -> Result<ServerStatus> {
        if self.is_running(id) {
            return Ok(self.status(id, meta.max_players));
        }
        if !eula_accepted(dir) {
            return Err(Error::validation(crate::msg!(
                "localServer.eulaMissing",
                "Der Server startet erst, wenn die Minecraft-EULA akzeptiert ist."
            )));
        }
        if std::net::TcpListener::bind(("0.0.0.0", meta.port)).is_err() {
            return Err(Error::validation(crate::msg!(
                "localServer.portInUse",
                "Port {port} ist schon belegt – läuft dort noch ein anderer Server?",
                port = meta.port
            )));
        }

        let mut command = tokio::process::Command::new(java);
        crate::platform::hide_console(&mut command);
        crate::platform::env::clean_tokio(&mut command);
        command
            .args(memory_args(meta.ram_mb))
            .args(meta.launch.args(cfg!(windows)))
            .current_dir(dir)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        let mut child = command.spawn().map_err(|e| {
            tracing::error!("Server '{id}' konnte nicht gestartet werden: {e}");
            Error::launch(crate::msg!("localServer.startFailed", "Der Server konnte nicht gestartet werden."))
        })?;
        let (Some(mut stdin), Some(stdout), Some(stderr)) = (child.stdin.take(), child.stdout.take(), child.stderr.take())
        else {
            return Err(Error::Internal("Server ohne Pipes".into()));
        };

        let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<String>();
        let (line_tx, mut line_rx) = mpsc::unbounded_channel::<String>();
        let (exit_tx, exit_rx) = watch::channel(false);
        let status = ServerStatus {
            state: ServerState::Starting,
            players: Some(0),
            player_names: Vec::new(),
            max_players: meta.max_players,
            started_at: Some(Utc::now()),
            exit_code: None,
        };
        let running = Arc::new(Running {
            commands: cmd_tx,
            kill: Notify::new(),
            exited: exit_rx,
            status: Mutex::new(status.clone()),
            log: Mutex::new(VecDeque::new()),
        });
        self.running.lock().unwrap_or_else(std::sync::PoisonError::into_inner).insert(id.to_owned(), running.clone());
        self.emit(LocalServerEvent::Status { id: id.to_owned(), status: status.clone() });

        for reader in [Box::new(stdout) as Box<dyn tokio::io::AsyncRead + Unpin + Send>, Box::new(stderr)] {
            let tx = line_tx.clone();
            tokio::spawn(async move {
                let mut reader = tokio::io::BufReader::new(reader);
                let mut buf = Vec::new();
                loop {
                    buf.clear();
                    match reader.read_until(b'\n', &mut buf).await {
                        Ok(0) | Err(_) => break,
                        Ok(_) => {
                            let line = clean_line(String::from_utf8_lossy(&buf).trim_end());
                            if tx.send(line).is_err() {
                                break;
                            }
                        }
                    }
                }
            });
        }
        drop(line_tx);
        tokio::spawn(async move {
            while let Some(command) = cmd_rx.recv().await {
                if stdin.write_all(format!("{command}\n").as_bytes()).await.is_err() || stdin.flush().await.is_err() {
                    break;
                }
            }
        });

        let servers = Arc::clone(self);
        let id = id.to_owned();
        tokio::spawn(async move {
            let mut pending: Vec<String> = Vec::new();
            let mut tick = tokio::time::interval(FLUSH_EVERY);
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            let mut lines_open = true;
            let exit = loop {
                tokio::select! {
                    exit = child.wait() => break exit,
                    () = running.kill.notified() => {
                        let _ = child.start_kill();
                    }
                    line = line_rx.recv(), if lines_open => match line {
                        Some(line) => servers.on_line(&id, &running, line, &mut pending),
                        None => lines_open = false,
                    },
                    _ = tick.tick() => servers.flush(&id, &mut pending),
                }
            };
            // Restliche Zeilen (kurz) noch mitnehmen.
            let drain = async {
                while let Some(line) = line_rx.recv().await {
                    servers.on_line(&id, &running, line, &mut pending);
                }
            };
            let _ = tokio::time::timeout(Duration::from_secs(2), drain).await;
            servers.flush(&id, &mut pending);

            let code = exit.ok().and_then(|s| s.code());
            let status = {
                let mut status = running.status.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                *status = ServerStatus { exit_code: code, ..ServerStatus::stopped(status.max_players) };
                status.clone()
            };
            let log: Vec<String> = running.log.lock().unwrap_or_else(std::sync::PoisonError::into_inner).iter().cloned().collect();
            servers.last_logs.lock().unwrap_or_else(std::sync::PoisonError::into_inner).insert(id.clone(), log);
            servers.running.lock().unwrap_or_else(std::sync::PoisonError::into_inner).remove(&id);
            let _ = exit_tx.send(true);
            servers.emit(LocalServerEvent::Status { id, status });
        });
        Ok(status)
    }

    fn on_line(&self, id: &str, running: &Running, line: String, pending: &mut Vec<String>) {
        if let Some(event) = parse_line(&line) {
            let changed = {
                let mut status = running.status.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                let before = status.clone();
                apply_line_event(&mut status, event);
                (*status != before).then(|| status.clone())
            };
            if let Some(status) = changed {
                self.emit(LocalServerEvent::Status { id: id.to_owned(), status });
            }
        }
        {
            let mut log = running.log.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if log.len() >= MAX_LOG_LINES {
                log.pop_front();
            }
            log.push_back(line.clone());
        }
        pending.push(line);
        if pending.len() >= 500 {
            self.flush(id, pending);
        }
    }

    fn flush(&self, id: &str, pending: &mut Vec<String>) {
        if !pending.is_empty() {
            self.emit(LocalServerEvent::Logs { id: id.to_owned(), lines: std::mem::take(pending) });
        }
    }

    /// Schickt einen Befehl an die Server-Konsole.
    pub fn command(&self, id: &str, command: &str) -> Result<()> {
        let command = clean_command(command)?;
        let running = self.get(id).ok_or_else(not_running)?;
        running.commands.send(command).map_err(|_| not_running())
    }

    /// `stop` schicken; nach der Frist hart beenden. Kehrt sofort zurück.
    pub fn stop(&self, id: &str) -> Result<()> {
        let running = self.get(id).ok_or_else(not_running)?;
        self.begin_stop(id, &running);
        let mut exited = running.exited.clone();
        tokio::spawn(async move {
            if tokio::time::timeout(STOP_GRACE, exited.wait_for(|done| *done)).await.is_err() {
                running.kill.notify_one();
            }
        });
        Ok(())
    }

    fn begin_stop(&self, id: &str, running: &Running) {
        let status = {
            let mut status = running.status.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            status.state = ServerState::Stopping;
            status.clone()
        };
        self.emit(LocalServerEvent::Status { id: id.to_owned(), status });
        if running.commands.send("stop".into()).is_err() {
            running.kill.notify_one();
        }
    }

    /// Sofort beenden (ohne Speichern).
    pub fn kill(&self, id: &str) -> Result<()> {
        let running = self.get(id).ok_or_else(not_running)?;
        running.kill.notify_one();
        Ok(())
    }

    /// Wartet, bis der Server beendet ist (höchstens `timeout`). `true` = beendet.
    pub async fn wait_stopped(&self, id: &str, timeout: Duration) -> bool {
        let Some(running) = self.get(id) else { return true };
        let mut exited = running.exited.clone();
        tokio::time::timeout(timeout, exited.wait_for(|done| *done)).await.is_ok()
    }

    /// Beim Beenden des Launchers: alle stoppen, nach `grace` hart beenden.
    pub async fn shutdown_all(&self, grace: Duration) {
        let all: Vec<(String, Arc<Running>)> =
            self.running.lock().unwrap_or_else(std::sync::PoisonError::into_inner).iter().map(|(k, v)| (k.clone(), v.clone())).collect();
        if all.is_empty() {
            return;
        }
        for (id, running) in &all {
            self.begin_stop(id, running);
        }
        let waits = all.iter().map(|(_, running)| {
            let mut exited = running.exited.clone();
            async move {
                if tokio::time::timeout(grace, exited.wait_for(|done| *done)).await.is_err() {
                    running.kill.notify_one();
                    let _ = tokio::time::timeout(Duration::from_secs(3), exited.wait_for(|done| *done)).await;
                }
            }
        });
        futures::future::join_all(waits).await;
    }
}

/// Wendet ein Log-Ereignis auf den Zustand an.
pub fn apply_line_event(status: &mut ServerStatus, event: LineEvent) {
    match event {
        LineEvent::Ready => {
            if status.state == ServerState::Starting {
                status.state = ServerState::Running;
            }
        }
        LineEvent::Joined(name) => {
            let mut names: BTreeSet<String> = status.player_names.drain(..).collect();
            names.insert(name);
            status.player_names = names.into_iter().collect();
            status.players = Some(status.player_names.len() as u32);
        }
        LineEvent::Left(name) => {
            status.player_names.retain(|n| n != &name);
            status.players = Some(status.player_names.len() as u32);
        }
        LineEvent::Count(now, max) => {
            status.players = Some(now);
            if let Some(max) = max {
                status.max_players = max;
            }
        }
    }
}

fn not_running() -> Error {
    Error::validation(crate::msg!("localServer.notRunning", "Der Server läuft nicht."))
}
