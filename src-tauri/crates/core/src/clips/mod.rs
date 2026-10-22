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
pub mod edit;
pub mod encoder;
pub mod ffmpeg;
pub mod library;
pub mod media;
pub mod recorder;
pub mod serve;
pub mod session;
pub mod settings;
pub mod share;
pub mod window;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use serde::Serialize;
use tokio::sync::mpsc;

use crate::paths::Paths;
use crate::{Error, Result};
use encoder::Codec;
use crate::link::{self, LinkCommand, LinkConfig, LinkEvent, LinkState, TrsLink};
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
    /// Clips wurden aus dem Spiel eingeschaltet (die Einstellungen haben sich geändert).
    #[serde(rename_all = "camelCase")]
    Enabled { instance_id: String },
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
    /// Länge eines Sofort-Clips in Sekunden (Puffer-Anzeige im Overlay).
    pub clip_seconds: u32,
    /// Das Spiel überlässt Puffer-Anzeige und Clip-Meldungen dem Overlay des Launchers
    /// (TRS Client mit `clips.overlay`); sonst zeichnet die Mod sie selbst.
    pub overlay: bool,
    /// Puffer-Anzeige im Overlay gewünscht (Einstellung der Mod, Standard an).
    pub buffer_hud: bool,
    /// Lage des HUD-Elements „Clips“ im Spiel – dort liegt das Overlay (`None` = oben links).
    pub hud_place: Option<link::HudPlace>,
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
    /// Download-Fortschritt von FFmpeg in Prozent ([`NO_PROGRESS`] = keiner).
    ffmpeg_progress: AtomicU8,
    /// Letzter fehlgeschlagener Download (dann erst nach [`FFMPEG_RETRY`] erneut).
    ffmpeg_failed_at: Mutex<Option<Instant>>,
    /// Vorschaubilder/-leisten: höchstens zwei FFmpeg-Läufe gleichzeitig (die Galerie fragt viele auf einmal an).
    previews: tokio::sync::Semaphore,
}

const NO_PROGRESS: u8 = u8::MAX;
/// Nach einem fehlgeschlagenen FFmpeg-Download so lange warten (nicht jede Sekunde neu laden).
const FFMPEG_RETRY: Duration = Duration::from_secs(60);

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Gleicher Stand für die Oberfläche? (Die laufende Aufnahmezeit zählt sie selbst weiter.)
fn same_view(a: &ClipState, b: &ClipState) -> bool {
    a.buffer == b.buffer
        && a.recording == b.recording
        && a.reason == b.reason
        && a.encoder == b.encoder
        && a.clip_seconds == b.clip_seconds
        && a.overlay == b.overlay
        && a.buffer_hud == b.buffer_hud
        && a.hud_place == b.hud_place
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
        let hud = self.link.hud(instance_id);
        let view = ClipState {
            instance_id: instance_id.to_owned(),
            buffer: state.buffer,
            recording: state.recording,
            recording_ms: state.recording_ms,
            reason: state.reason,
            encoder: codec,
            clip_seconds: state.clip_seconds,
            overlay: hud.overlay,
            buffer_hud: hud.buffer,
            hud_place: hud.place,
        };
        self.link.set_state(instance_id, state);
        // Oberfläche nur bei echten Änderungen (die Zeit zählt sie selbst weiter).
        let changed = {
            let mut states = lock(&self.states);
            let changed = states.get(instance_id).is_none_or(|o| !same_view(o, &view));
            states.insert(instance_id.to_owned(), view.clone());
            changed
        };
        if changed {
            self.emit(ClipEvent::State(view));
        }
    }

    /// Overlay-Stand aus dem Link (Mod mit `clips.overlay` verbunden/getrennt, `clips.hud`)
    /// in den Stand der Oberfläche übernehmen – nur für Spiele mit laufender Aufnahme-Sitzung.
    fn hud_changed(&self, instance_id: &str) {
        let hud = self.link.hud(instance_id);
        let view = {
            let mut states = lock(&self.states);
            let Some(state) = states.get_mut(instance_id) else { return };
            if state.overlay == hud.overlay && state.buffer_hud == hud.buffer && state.hud_place == hud.place {
                return;
            }
            state.overlay = hud.overlay;
            state.buffer_hud = hud.buffer;
            state.hud_place = hud.place;
            state.clone()
        };
        self.emit(ClipEvent::State(view));
    }

    pub(crate) fn ended(&self, instance_id: &str) {
        lock(&self.states).remove(instance_id);
        self.emit(ClipEvent::Ended { instance_id: instance_id.to_owned() });
    }

    pub(crate) async fn waiting_reason(&self) -> &'static str {
        if self.installing.load(Ordering::Relaxed) {
            "ffmpeg"
        } else if self.ffmpeg_failed_recently() {
            "ffmpegFailed"
        } else {
            "starting"
        }
    }

    /// Download-Fortschritt von FFmpeg (nur während des Downloads).
    pub(crate) fn ffmpeg_progress(&self) -> Option<u8> {
        let p = self.ffmpeg_progress.load(Ordering::Relaxed);
        (self.installing.load(Ordering::Relaxed) && p != NO_PROGRESS).then_some(p)
    }

    fn ffmpeg_failed_recently(&self) -> bool {
        lock(&self.ffmpeg_failed_at).is_some_and(|t| t.elapsed() < FFMPEG_RETRY)
    }

    /// FFmpeg bereit? Sonst im Hintergrund laden (einmal gleichzeitig, nach einem
    /// Fehlschlag erst wieder nach [`FFMPEG_RETRY`]) und `None`.
    pub(crate) async fn ffmpeg_ready_or_install(self: &Arc<Self>) -> Option<PathBuf> {
        if let Some(exe) = self.ffmpeg.ready().await {
            return Some(exe);
        }
        if self.ffmpeg_failed_recently() {
            return None;
        }
        if !self.installing.swap(true, Ordering::SeqCst) {
            let shared = Arc::clone(self);
            self.ffmpeg_progress.store(0, Ordering::Relaxed);
            tokio::spawn(async move {
                shared.emit(ClipEvent::Ffmpeg { state: "downloading" });
                let progress = |p: f64| {
                    // Nur ganze Prozent (0–100); die Mod bekommt sie mit der Statuszeile.
                    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
                    let p = p.clamp(0.0, 100.0) as u8;
                    shared.ffmpeg_progress.store(p, Ordering::Relaxed);
                };
                let result = shared.ffmpeg.install_with(&progress).await;
                shared.installing.store(false, Ordering::SeqCst);
                shared.ffmpeg_progress.store(NO_PROGRESS, Ordering::Relaxed);
                match result {
                    Ok(_) => {
                        *lock(&shared.ffmpeg_failed_at) = None;
                        shared.emit(ClipEvent::Ffmpeg { state: "ready" });
                    }
                    Err(e) => {
                        tracing::warn!("FFmpeg konnte nicht geladen werden: {e}");
                        *lock(&shared.ffmpeg_failed_at) = Some(Instant::now());
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
            ffmpeg_progress: AtomicU8::new(NO_PROGRESS),
            ffmpeg_failed_at: Mutex::default(),
            previews: tokio::sync::Semaphore::new(2),
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
        let weak = Arc::downgrade(&shared);
        link.set_hud_sink(Arc::new(move |instance_id: &str| {
            if let Some(shared) = weak.upgrade() {
                shared.hud_changed(instance_id);
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
        let mut config = self.shared.link.clips_config(instance_id, settings.enabled);
        self.add_clips_dir(&mut config, instance_id, settings);
        let state = if settings.enabled {
            LinkState { reason: Some("starting"), clip_seconds: settings.buffer_seconds, ..Default::default() }
        } else {
            LinkState::disabled()
        };
        let state = state.capturing(settings.system_audio, settings.microphone);
        self.shared.link.set_state(instance_id, state);
        if let Err(e) = link::write_config(game_dir, &config).await {
            tracing::warn!("clips.json konnte nicht geschrieben werden: {e}");
        }
    }

    /// Protokoll 2: Clip-Ordner der Instanz mitgeben (die Mod zeigt die Clips im Spiel an).
    fn add_clips_dir(&self, config: &mut LinkConfig, instance_id: &str, settings: &ClipSettings) {
        if config.version < link::PROTOCOL_VERSION || config.port.is_none() {
            return;
        }
        let root = library::clips_root(&self.shared.paths, settings);
        if let Ok(dir) = library::instance_dir(&root, instance_id) {
            config.clips_dir = dir.to_str().map(str::to_owned);
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
    async fn disable(&self, instance_id: &str, settings: &ClipSettings) {
        let game_dir = lock(&self.shared.sessions).get(instance_id).map(|s| s.game_dir.clone());
        self.game_exited(instance_id);
        self.shared.link.set_state(instance_id, LinkState::disabled().capturing(settings.system_audio, settings.microphone));
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
                    self.disable(&game.instance_id, settings).await;
                }
                (None, true) => {
                    self.prepare(&game.instance_id, &game.game_dir, settings).await;
                    self.game_started(game, settings);
                }
                (None, false) => {
                    // Nur den Hinweis der Mod aktualisieren (was beim Einschalten aufgenommen würde).
                    self.shared
                        .link
                        .set_state(&game.instance_id, LinkState::disabled().capturing(settings.system_audio, settings.microphone));
                }
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

    /// Clips wurden aus dem Spiel eingeschaltet: Oberfläche informieren (lädt die Einstellungen neu).
    pub fn announce_enabled(&self, instance_id: &str) {
        self.shared.emit(ClipEvent::Enabled { instance_id: instance_id.to_owned() });
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
        let key = Self::cache_key(video).await?;
        let dir = self.shared.paths.root().join("cache").join("clip-thumbs");
        let thumb = dir.join(format!("{key}.jpg"));
        if tokio::fs::metadata(&thumb).await.is_ok_and(|m| m.len() > 0) {
            return Ok(Some(thumb));
        }
        let _permit = self.shared.previews.acquire().await.map_err(|e| Error::Internal(e.to_string()))?;
        // Während des Wartens evtl. schon von einer anderen Anfrage erzeugt.
        if tokio::fs::metadata(&thumb).await.is_ok_and(|m| m.len() > 0) {
            return Ok(Some(thumb));
        }
        crate::fsutil::ensure_dir(&dir).await?;
        let seek = if library::mp4_duration_ms(video).unwrap_or(0) > 2500 { 1.0 } else { 0.0 };
        let tmp = dir.join(format!("{key}.{}.part", uuid::Uuid::new_v4().simple()));
        let result = recorder::run(&exe, encoder::thumbnail_args(video, &tmp, seek), Duration::from_secs(20)).await;
        if let Err(e) = result {
            let _ = tokio::fs::remove_file(&tmp).await;
            return Err(e);
        }
        tokio::fs::rename(&tmp, &thumb).await.map_err(|e| Error::io(&thumb, e))?;
        Ok(Some(thumb))
    }

    /// Vorschau-Leiste eines Clips (PNG-Raster im Cache), `None` ohne FFmpeg.
    pub async fn strip(&self, video: &Path) -> Result<Option<(PathBuf, edit::ClipStrip)>> {
        let Some(exe) = self.shared.ffmpeg.ready().await else { return Ok(None) };
        // Versionssuffix: alte Leisten (winzige 160×90-Raster) werden einmalig neu in voller Auflösung erzeugt.
        let key = format!("{}-v2", Self::cache_key(video).await?);
        let dir = self.shared.paths.root().join("cache").join("clip-strips");
        if let Some(cached) = edit::cached_strip(&dir, &key).await {
            return Ok(Some(cached));
        }
        let _permit = self.shared.previews.acquire().await.map_err(|e| Error::Internal(e.to_string()))?;
        let runner = edit::ProcessRunner { exe };
        Ok(Some(edit::strip(&runner, video, &dir, &key).await?))
    }

    /// FFmpeg (falls bereit) – zum Zuschneiden.
    pub async fn ffmpeg_exe(&self) -> Option<PathBuf> {
        self.shared.ffmpeg.ready().await
    }

    /// Encoder zum Neukodieren beim Zuschneiden (wie bei der Aufnahme ausprobiert).
    pub async fn trim_codecs(&self, exe: &Path, settings: &ClipSettings) -> Vec<Codec> {
        self.shared.usable_codecs(exe, &Codec::candidates(settings.encoder)).await
    }

    async fn cache_key(video: &Path) -> Result<String> {
        let meta = tokio::fs::metadata(video).await.map_err(|e| Error::io(video, e))?;
        let stamp = meta.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map_or(0, |d| d.as_secs());
        Ok(edit::cache_key(video, meta.len(), stamp))
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
        let dir = config.clips_dir.clone().expect("Clip-Ordner");
        assert!(dir.ends_with("survival"), "{dir}");
        assert_eq!(config, LinkConfig { version: 2, enabled: true, port: Some(handoff.port), token: None, clips_dir: Some(dir) });
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
        assert_eq!(serde_json::from_str::<LinkConfig>(&text).unwrap(), LinkConfig { version: 1, enabled: false, port: None, token: None, clips_dir: None });
    }

    /// „Clips einschalten“ aus dem Spiel: wie der Schalter in den Einstellungen –
    /// gespeichert, Oberfläche informiert, Aufnahme-Sitzung des laufenden Spiels
    /// gestartet und clips.json auf „an“.
    #[cfg(windows)]
    #[tokio::test]
    async fn einschalten_aus_dem_spiel_speichert_und_startet_die_aufnahme() {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;

        let dir = tempfile::tempdir().unwrap();
        // Ein „Spiel“ ohne Fenster (die Sitzung wartet dann auf das Fenster und lädt kein FFmpeg).
        let mut game = std::process::Command::new("cmd")
            .args(["/c", "ping -n 120 127.0.0.1 >nul"])
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .unwrap();
        let handle = crate::platform::ProcessHandle::open(game.id()).unwrap();
        let record = serde_json::json!([{
            "instanceId": "survival",
            "pid": game.id(),
            "startedAt": "2026-09-25T10:00:00Z",
            "creationTime": handle.creation_time().unwrap(),
            "stdoutLog": dir.path().join("stdout.log"),
            "stderrLog": dir.path().join("stderr.log"),
        }]);
        std::fs::write(dir.path().join("running.json"), record.to_string()).unwrap();
        let launcher = Arc::new(crate::Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap());
        assert!(launcher.games().is_running("survival"), "Testspiel übernommen");
        let events = Arc::new(Mutex::new(Vec::new()));
        let sink = events.clone();
        launcher.clips().set_sink(Arc::new(move |e| sink.lock().unwrap().push(e)));
        // Der Link ist beim Spielstart immer offen (auch mit Clips aus).
        launcher.link.open_session("survival", false).await.unwrap();
        assert!(!launcher.settings().await.clips.enabled);

        assert_eq!(launcher.enable_clips_from_game("survival").await, Ok(30));
        assert!(launcher.settings().await.clips.enabled);
        let saved = crate::settings::Settings::load(&launcher.paths().settings_file()).await.unwrap();
        assert!(saved.clips.enabled, "dauerhaft gespeichert");
        assert!(events.lock().unwrap().contains(&ClipEvent::Enabled { instance_id: "survival".into() }), "Oberfläche lädt neu");
        let text = std::fs::read_to_string(launcher.paths().instance_game_dir("survival").join("config/trsclient/clips.json")).unwrap();
        let config: LinkConfig = serde_json::from_str(&text).unwrap();
        assert!(config.enabled && config.version == 2, "{text}");
        // Die Aufnahme-Sitzung läuft (wartet auf das Spielfenster).
        let mut started = false;
        for _ in 0..50 {
            if launcher.clips().states().iter().any(|s| s.instance_id == "survival") {
                started = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert!(started, "Aufnahme-Sitzung gestartet");

        // Schon an: kein zweites Ereignis, Sitzung bleibt.
        let before = events.lock().unwrap().iter().filter(|e| matches!(e, ClipEvent::Enabled { .. })).count();
        assert_eq!(launcher.enable_clips_from_game("survival").await, Ok(30));
        assert_eq!(events.lock().unwrap().iter().filter(|e| matches!(e, ClipEvent::Enabled { .. })).count(), before);
        // Ein Spiel, das nicht läuft, darf nichts einschalten.
        assert_eq!(launcher.enable_clips_from_game("fremd").await, Err("not_allowed"));

        launcher.clips_shutdown().await;
        let _ = game.kill();
        let _ = game.wait();
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
            clip_seconds: 30,
            overlay: true,
            buffer_hud: false,
            hud_place: Some(link::HudPlace { anchor_x: 1000, anchor_y: 500, offset_x: 0, offset_y: -200 }),
        });
        let v = serde_json::to_value(&s).unwrap();
        assert_eq!(v["type"], "state");
        assert_eq!(v["encoder"], "nvenc");
        assert_eq!(v["recordingMs"], 0);
        assert_eq!(v["clipSeconds"], 30);
        assert_eq!(v["overlay"], true);
        assert_eq!(v["bufferHud"], false);
        assert_eq!(v["hudPlace"], serde_json::json!({ "anchorX": 1000, "anchorY": 500, "offsetX": 0, "offsetY": -200 }));
    }

    /// Verbindet sich ein TRS Client mit `clips.overlay` bzw. schaltet er die Puffer-Anzeige um,
    /// bekommt die Oberfläche (Overlay) einen neuen Stand – nur für Spiele mit Aufnahme-Sitzung.
    #[tokio::test]
    async fn overlay_stand_folgt_dem_link() {
        let dir = tempfile::tempdir().unwrap();
        let (service, link) = service(dir.path());
        let events = Arc::new(Mutex::new(Vec::new()));
        let sink = events.clone();
        service.set_sink(Arc::new(move |e| sink.lock().unwrap().push(e)));
        link.open_session("survival", false).await.unwrap();
        let state = LinkState { available: true, buffer: true, clip_seconds: 45, ..Default::default() };
        service.shared.publish("survival", state.clone(), None);
        let last = |events: &Arc<Mutex<Vec<ClipEvent>>>| match events.lock().unwrap().last() {
            Some(ClipEvent::State(s)) => s.clone(),
            other => panic!("kein Status: {other:?}"),
        };
        let first = last(&events);
        assert_eq!((first.clip_seconds, first.overlay, first.buffer_hud), (45, false, true), "ohne Mod: Standard");

        // Gleicher Stand → kein neues Ereignis.
        service.shared.publish("survival", state.clone(), None);
        assert_eq!(events.lock().unwrap().len(), 1);
        // Link meldet eine Änderung, die es nicht gibt → nichts.
        service.shared.hud_changed("survival");
        assert_eq!(events.lock().unwrap().len(), 1);

        // Mod mit `clips.overlay` verbunden, Puffer-Anzeige aus → sofort neuer Stand für das Overlay.
        link.fake_hud("survival", link::LinkHud { overlay: true, buffer: false, place: None });
        let now = last(&events);
        assert_eq!((now.overlay, now.buffer_hud, now.clip_seconds), (true, false, 45));
        assert_eq!(events.lock().unwrap().len(), 2);
        // Der nächste Status der Aufnahme behält ihn.
        service.shared.publish("survival", LinkState { recording: true, ..state }, None);
        let now = last(&events);
        assert!(now.recording && now.overlay && !now.buffer_hud);

        // Ohne Aufnahme-Sitzung (kein Stand) → nichts.
        link.open_session("fremd", false).await.unwrap();
        link.fake_hud("fremd", link::LinkHud { overlay: true, buffer: true, place: None });
        assert_eq!(events.lock().unwrap().len(), 3);
    }
}
