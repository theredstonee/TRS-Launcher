//! Clips & Aufnahme wie ShadowPlay/Medal: Der **Launcher** nimmt auf, die Mod
//! meldet nur Tastendrücke.
//!
//! - Bild: FFmpeg-Filter `gfxcapture` (Windows Graphics Capture) – nur das
//!   Spielfenster, nie der Bildschirm – direkt in den Hardware-Encoder
//!   (NVENC → AMF → QSV → Media Foundation → x264).
//! - Ton: WASAPI-Loopback (Systemton), optional Mikrofon, über stdin.
//! - Sofort-Clip: Ringpuffer aus 2-s-Segmenten auf der Platte (kein RAM-Puffer),
//!   Export ohne Neukodierung. Normale Aufnahme: dieselben Segmente von Start bis Stopp.
//! - Kanal zur Mod: der gemeinsame [`crate::link::TrsLink`] (nur 127.0.0.1, Schlüssel je Spielstart).
//!
//! Aufgenommen wird nur, solange ein Spiel dieses Launchers läuft und Clips
//! eingeschaltet sind. Nichts verlässt den PC.

pub mod api;
pub mod audio;
pub mod encoder;
pub mod ffmpeg;
pub mod library;
pub mod recorder;
pub mod session;
pub mod settings;
pub mod window;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use serde::Serialize;
use tokio::sync::mpsc;

use crate::paths::Paths;
use crate::{Error, Result};
use encoder::Codec;
use crate::link::{self, LinkCommand, LinkEvent, LinkState, TrsLink};
use session::Command;
use settings::ClipSettings;

/// Meldungen an die Oberfläche (Tauri-Ereignis `clip-event`).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum ClipEvent {
    #[serde(rename_all = "camelCase")]
    State(ClipState),
    #[serde(rename_all = "camelCase")]
    Saved { instance_id: String, file_name: String, kind: &'static str, seconds: u32, removed: u32 },
    #[serde(rename_all = "camelCase")]
    Failed { instance_id: String, code: &'static str },
    #[serde(rename_all = "camelCase")]
    Ended { instance_id: String },
    /// FFmpeg wird im Hintergrund geladen bzw. ist fertig/fehlgeschlagen.
    #[serde(rename_all = "camelCase")]
    Ffmpeg { state: &'static str },
}

/// Aufnahmestand eines laufenden Spiels.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipState {
    pub instance_id: String,
    pub buffer: bool,
    pub recording: bool,
    pub recording_ms: u64,
    pub reason: Option<&'static str>,
    pub encoder: Option<Codec>,
}

pub type ClipSink = Arc<dyn Fn(ClipEvent) + Send + Sync>;

struct SessionEntry {
    tx: mpsc::UnboundedSender<Command>,
    game_dir: PathBuf,
}

/// Gemeinsamer Zustand aller Sitzungen.
pub struct Shared {
    pub(crate) paths: Paths,
    pub(crate) ffmpeg: ffmpeg::Ffmpeg,
    link: Arc<TrsLink>,
    sink: RwLock<Option<ClipSink>>,
    sessions: Mutex<HashMap<String, SessionEntry>>,
    states: Mutex<HashMap<String, ClipState>>,
    /// Probe-Ergebnisse je Encoder (für diese Launcher-Sitzung).
    codecs: tokio::sync::Mutex<HashMap<Codec, bool>>,
    installing: AtomicBool,
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

impl Shared {
    pub(crate) fn emit(&self, event: ClipEvent) {
        let sink = self.sink.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
        if let Some(sink) = sink {
            sink(event);
        }
    }

    pub(crate) fn link_send(&self, instance_id: &str, event: LinkEvent) {
        self.link.send(instance_id, event);
    }

    /// Fehler an Mod und Oberfläche melden.
    pub(crate) fn fail(&self, instance_id: &str, code: &'static str) {
        self.link_send(instance_id, LinkEvent::Failed { kind: "clip", code });
        self.emit(ClipEvent::Failed { instance_id: instance_id.to_owned(), code });
    }

    pub(crate) fn publish(&self, instance_id: &str, state: LinkState, codec: Option<Codec>) {
        let view = ClipState {
            instance_id: instance_id.to_owned(),
            buffer: state.buffer,
            recording: state.recording,
            recording_ms: state.recording_ms,
            reason: state.reason,
            encoder: codec,
        };
        self.link.set_state(instance_id, state);
        // Oberfläche nur bei echten Änderungen (die Zeit zählt sie selbst weiter).
        let changed = {
            let mut states = lock(&self.states);
            let old = states.get(instance_id);
            let changed = old.is_none_or(|o| {
                o.buffer != view.buffer || o.recording != view.recording || o.reason != view.reason || o.encoder != view.encoder
            });
            states.insert(instance_id.to_owned(), view.clone());
            changed
        };
        if changed {
            self.emit(ClipEvent::State(view));
        }
    }

    pub(crate) fn ended(&self, instance_id: &str) {
        lock(&self.states).remove(instance_id);
        self.emit(ClipEvent::Ended { instance_id: instance_id.to_owned() });
    }

    pub(crate) async fn waiting_reason(&self) -> &'static str {
        if self.installing.load(Ordering::Relaxed) { "ffmpeg" } else { "starting" }
    }

    /// FFmpeg bereit? Sonst im Hintergrund laden (einmal) und `None`.
    pub(crate) async fn ffmpeg_ready_or_install(self: &Arc<Self>) -> Option<PathBuf> {
        if let Some(exe) = self.ffmpeg.ready().await {
            return Some(exe);
        }
        if !self.installing.swap(true, Ordering::SeqCst) {
            let shared = Arc::clone(self);
            tokio::spawn(async move {
                shared.emit(ClipEvent::Ffmpeg { state: "downloading" });
                let result = shared.ffmpeg.install().await;
                shared.installing.store(false, Ordering::SeqCst);
                match result {
                    Ok(_) => shared.emit(ClipEvent::Ffmpeg { state: "ready" }),
                    Err(e) => {
                        tracing::warn!("FFmpeg konnte nicht geladen werden: {e}");
                        shared.emit(ClipEvent::Ffmpeg { state: "failed" });
                    }
                }
            });
        }
        None
    }

    /// Encoder, die hier kodieren können (Probelauf einmal je Launcher-Sitzung).
    pub(crate) async fn usable_codecs(&self, exe: &Path, candidates: &[Codec]) -> Vec<Codec> {
        let mut known = self.codecs.lock().await;
        let mut usable = Vec::new();
        for codec in candidates {
            let ok = match known.get(codec) {
                Some(ok) => *ok,
                None => {
                    let ok = recorder::run(exe, encoder::probe_args(*codec), Duration::from_secs(20)).await.is_ok();
                    tracing::info!("Encoder {}: {}", codec.ffmpeg_name(), if ok { "verfügbar" } else { "nicht verfügbar" });
                    known.insert(*codec, ok);
                    ok
                }
            };
            if ok {
                usable.push(*codec);
            }
        }
        usable
    }
}

/// Clips-Dienst des Launchers.
pub struct ClipService {
    shared: Arc<Shared>,
}

/// Ein laufendes Spiel (für Einstellungsänderungen und nach einem Neustart).
#[derive(Debug, Clone)]
pub struct RunningGame {
    pub instance_id: String,
    pub instance_name: String,
    pub pid: u32,
    pub game_dir: PathBuf,
}

impl ClipService {
    /// `link`: der gemeinsame TRS-Link (Clips melden darüber Status und empfangen die Tasten).
    pub fn new(paths: &Paths, link: Arc<TrsLink>) -> Self {
        // Reste alter Sitzungen (Absturz, hartes Beenden) entfernen.
        let buffers = paths.root().join("cache").join("clip-buffer");
        let _ = std::fs::remove_dir_all(&buffers);
        let shared = Arc::new(Shared {
            paths: paths.clone(),
            ffmpeg: ffmpeg::Ffmpeg::new(paths),
            link: link.clone(),
            sink: RwLock::default(),
            sessions: Mutex::default(),
            states: Mutex::default(),
            codecs: tokio::sync::Mutex::default(),
            installing: AtomicBool::new(false),
        });
        let weak = Arc::downgrade(&shared);
        link.set_command_sink(Arc::new(move |instance_id: &str, command: LinkCommand| {
            let Some(shared) = weak.upgrade() else { return };
            let command = match command {
                LinkCommand::SaveClip => Command::SaveClip,
                LinkCommand::ToggleRecording => Command::ToggleRecording,
            };
            let sent = lock(&shared.sessions).get(instance_id).is_some_and(|s| s.tx.send(command).is_ok());
            if !sent {
                shared.fail(instance_id, "disabled");
            }
        }));
        Self { shared }
    }

    pub fn set_sink(&self, sink: ClipSink) {
        *self.shared.sink.write().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(sink);
    }

    pub fn ffmpeg(&self) -> &ffmpeg::Ffmpeg {
        &self.shared.ffmpeg
    }

    pub fn paths(&self) -> &Paths {
        &self.shared.paths
    }

    /// Vor dem Spielstart (bzw. beim Einschalten): `config/trsclient/clips.json`
    /// schreiben – mit Port (v2) bzw. altem Token für Mods ≤ 0.5.0 – und den
    /// Clip-Status im Link setzen. Die Link-Sitzung selbst öffnet der Launcher.
    /// Fehler verhindern den Start nie.
    pub async fn prepare(&self, instance_id: &str, game_dir: &Path, settings: &ClipSettings) {
        let config = self.shared.link.clips_config(instance_id, settings.enabled);
        let state = if settings.enabled {
            LinkState { reason: Some("starting"), ..Default::default() }
        } else {
            LinkState::disabled()
        };
        self.shared.link.set_state(instance_id, state);
        if let Err(e) = link::write_config(game_dir, &config).await {
            tracing::warn!("clips.json konnte nicht geschrieben werden: {e}");
        }
    }

    /// Spiel läuft (PID bekannt): Aufnahme-Sitzung starten, falls Clips an sind.
    pub fn game_started(&self, game: RunningGame, settings: &ClipSettings) {
        if !settings.enabled {
            return;
        }
        let (tx, rx) = mpsc::unbounded_channel();
        let ctx = session::Context {
            instance_id: game.instance_id.clone(),
            instance_name: game.instance_name.clone(),
            pid: game.pid,
            settings: settings.clone(),
            shared: self.shared.clone(),
        };
        let old = lock(&self.shared.sessions).insert(game.instance_id.clone(), SessionEntry { tx, game_dir: game.game_dir });
        if let Some(old) = old {
            let _ = old.tx.send(Command::Stop);
        }
        tokio::spawn(session::run(ctx, rx));
    }

    /// Spiel beendet: Aufnahme-Sitzung stoppen (eine laufende Aufnahme wird
    /// noch gespeichert). Link-Sitzung und clips.json räumt der Launcher auf.
    pub fn game_exited(&self, instance_id: &str) {
        let entry = lock(&self.shared.sessions).remove(instance_id);
        if let Some(entry) = entry {
            let _ = entry.tx.send(Command::Stop);
        }
    }

    /// Clips für ein laufendes Spiel ausgeschaltet: Aufnahme stoppen, der Mod
    /// „aus“ melden (die Link-Sitzung bleibt – Konten brauchen sie weiter).
    async fn disable(&self, instance_id: &str) {
        let game_dir = lock(&self.shared.sessions).get(instance_id).map(|s| s.game_dir.clone());
        self.game_exited(instance_id);
        self.shared.link.set_state(instance_id, LinkState::disabled());
        if let Some(game_dir) = game_dir {
            let config = self.shared.link.clips_config(instance_id, false);
            if let Err(e) = link::write_config(&game_dir, &config).await {
                tracing::warn!("clips.json konnte nicht geschrieben werden: {e}");
            }
        }
    }

    /// Einstellungen geändert: laufende Sitzungen anpassen, bei Bedarf starten/stoppen.
    pub async fn settings_changed(&self, settings: &ClipSettings, running: Vec<RunningGame>) {
        for game in running {
            let tx = lock(&self.shared.sessions).get(&game.instance_id).map(|s| s.tx.clone());
            match (tx, settings.enabled) {
                (Some(tx), true) => {
                    let _ = tx.send(Command::Settings(settings.clone()));
                }
                (Some(_), false) => {
                    self.disable(&game.instance_id).await;
                }
                (None, true) => {
                    self.prepare(&game.instance_id, &game.game_dir, settings).await;
                    self.game_started(game, settings);
                }
                (None, false) => {}
            }
        }
    }

    /// Knopf im Launcher (gleiche Wirkung wie die Taste im Spiel).
    pub fn command(&self, instance_id: &str, record: bool) -> Result<()> {
        let tx = lock(&self.shared.sessions).get(instance_id).map(|s| s.tx.clone());
        let Some(tx) = tx else {
            return Err(Error::validation(crate::msg!("clips.notRecording", "Für dieses Spiel läuft keine Aufnahme.")));
        };
        let _ = tx.send(if record { Command::ToggleRecording } else { Command::SaveClip });
        Ok(())
    }

    pub fn states(&self) -> Vec<ClipState> {
        lock(&self.shared.states).values().cloned().collect()
    }

    /// Beim Beenden des Launchers: alle Sitzungen stoppen (Aufnahmen werden gesichert).
    pub async fn shutdown(&self) {
        let ids: Vec<String> = lock(&self.shared.sessions).keys().cloned().collect();
        for id in &ids {
            self.game_exited(id);
        }
        // Kurz Zeit geben, laufende Aufnahmen abzuschließen.
        for _ in 0..50 {
            if lock(&self.shared.states).is_empty() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }

    /// Vorschaubild eines Clips (JPEG im Cache), `None` ohne FFmpeg.
    pub async fn thumbnail(&self, video: &Path) -> Result<Option<PathBuf>> {
        let Some(exe) = self.shared.ffmpeg.ready().await else { return Ok(None) };
        let meta = tokio::fs::metadata(video).await.map_err(|e| Error::io(video, e))?;
        let stamp = meta.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map_or(0, |d| d.as_secs());
        let key = {
            use sha1::Digest;
            let mut h = sha1::Sha1::new();
            h.update(video.display().to_string().as_bytes());
            h.update(format!("|{}|{stamp}", meta.len()).as_bytes());
            h.finalize().iter().take(8).map(|b| format!("{b:02x}")).collect::<String>()
        };
        let dir = self.shared.paths.root().join("cache").join("clip-thumbs");
        let thumb = dir.join(format!("{key}.jpg"));
        if tokio::fs::metadata(&thumb).await.is_ok_and(|m| m.len() > 0) {
            return Ok(Some(thumb));
        }
        crate::fsutil::ensure_dir(&dir).await?;
        let seek = if library::mp4_duration_ms(video).unwrap_or(0) > 2500 { 1.0 } else { 0.0 };
        let tmp = dir.join(format!("{key}.part"));
        recorder::run(&exe, encoder::thumbnail_args(video, &tmp, seek), Duration::from_secs(20)).await?;
        tokio::fs::rename(&tmp, &thumb).await.map_err(|e| Error::io(&thumb, e))?;
        Ok(Some(thumb))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::link::LinkConfig;

    fn service(dir: &Path) -> (ClipService, Arc<TrsLink>) {
        let paths = Paths::new(dir);
        let link = Arc::new(TrsLink::new(None));
        (ClipService::new(&paths, link.clone()), link)
    }

    #[tokio::test]
    async fn ausgeschaltet_schreibt_nur_enabled_false() {
        let dir = tempfile::tempdir().unwrap();
        let (service, _link) = service(dir.path());
        let game = dir.path().join("game");
        service.prepare("survival", &game, &ClipSettings::default()).await;
        let text = std::fs::read_to_string(game.join("config/trsclient/clips.json")).unwrap();
        let config: LinkConfig = serde_json::from_str(&text).unwrap();
        assert_eq!(config, LinkConfig::disabled());
        // Ohne Clips wird auch keine Sitzung gestartet.
        service.game_started(
            RunningGame { instance_id: "survival".into(), instance_name: "Survival".into(), pid: 1, game_dir: game },
            &ClipSettings::default(),
        );
        assert!(service.states().is_empty());
        assert!(service.command("survival", false).is_err());
    }

    #[tokio::test]
    async fn eingeschaltet_schreibt_nur_den_port() {
        let dir = tempfile::tempdir().unwrap();
        let (service, link) = service(dir.path());
        let handoff = link.open_session("survival", false).await.unwrap();
        let game = dir.path().join("game");
        let settings = ClipSettings { enabled: true, ..Default::default() };
        service.prepare("survival", &game, &settings).await;
        let text = std::fs::read_to_string(game.join("config/trsclient/clips.json")).unwrap();
        assert!(!text.contains(handoff.secret()), "der Link-Schlüssel steht nie in der Datei");
        let config: LinkConfig = serde_json::from_str(&text).unwrap();
        assert_eq!(config, LinkConfig { version: 2, enabled: true, port: Some(handoff.port), token: None });
        service.game_exited("survival");
    }

    #[tokio::test]
    async fn alte_mod_bekommt_weiter_das_token() {
        let dir = tempfile::tempdir().unwrap();
        let (service, link) = service(dir.path());
        let handoff = link.open_session("survival", true).await.unwrap();
        let game = dir.path().join("game");
        service.prepare("survival", &game, &ClipSettings { enabled: true, ..Default::default() }).await;
        let text = std::fs::read_to_string(game.join("config/trsclient/clips.json")).unwrap();
        let config: LinkConfig = serde_json::from_str(&text).unwrap();
        assert_eq!((config.version, config.enabled, config.port), (1, true, Some(handoff.port)));
        assert_eq!(config.token.as_deref().map(str::len), Some(64));
        service.prepare("survival", &game, &ClipSettings::default()).await;
        let text = std::fs::read_to_string(game.join("config/trsclient/clips.json")).unwrap();
        assert_eq!(serde_json::from_str::<LinkConfig>(&text).unwrap(), LinkConfig { version: 1, enabled: false, port: None, token: None });
    }

    #[test]
    fn ereignisse_sind_camel_case() {
        let e = ClipEvent::Saved {
            instance_id: "a".into(),
            file_name: "x.mp4".into(),
            kind: "clip",
            seconds: 30,
            removed: 0,
        };
        let v = serde_json::to_value(&e).unwrap();
        assert_eq!(v["type"], "saved");
        assert_eq!(v["instanceId"], "a");
        assert_eq!(v["fileName"], "x.mp4");
        let s = ClipEvent::State(ClipState {
            instance_id: "a".into(),
            buffer: true,
            recording: false,
            recording_ms: 0,
            reason: None,
            encoder: Some(Codec::Nvenc),
        });
        let v = serde_json::to_value(&s).unwrap();
        assert_eq!(v["type"], "state");
        assert_eq!(v["encoder"], "nvenc");
        assert_eq!(v["recordingMs"], 0);
    }
}
