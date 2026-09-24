//! Aufnahme-Sitzung eines laufenden Spiels: wartet auf das Fenster, startet
//! FFmpeg, hält den Ringpuffer klein und exportiert Clips und Aufnahmen.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tokio::sync::mpsc;

use super::encoder::{self, Codec, Plan, SEGMENT_SECONDS};
use super::link::{LinkEvent, LinkState};
use super::recorder::{self, Recorder};
use super::settings::ClipSettings;
use super::{ClipEvent, Shared, library, window};
use crate::{Error, Result};

/// Befehle an eine Sitzung.
#[derive(Debug, Clone)]
pub enum Command {
    SaveClip,
    ToggleRecording,
    Settings(ClipSettings),
    Stop,
}

/// Was eine Sitzung braucht.
pub struct Context {
    pub instance_id: String,
    pub instance_name: String,
    pub pid: u32,
    pub settings: ClipSettings,
    pub shared: Arc<Shared>,
}

const WINDOW_POLL: Duration = Duration::from_secs(1);
const FIRST_SEGMENT_TIMEOUT: Duration = Duration::from_secs(20);
/// So lange wartet ein Clip auf das Ende des laufenden Segments.
const SEGMENT_WAIT: Duration = Duration::from_millis(3200);
/// Unter so viel freiem Speicher endet eine normale Aufnahme von selbst.
const MIN_FREE_BYTES: u64 = 2 * 1024 * 1024 * 1024;

/// Segmente, die gerade exportiert werden (nicht löschen).
#[derive(Default)]
struct Pins(Vec<(u64, u64)>);

impl Pins {
    fn contains(&self, n: u64) -> bool {
        self.0.iter().any(|(a, b)| (*a..=*b).contains(&n))
    }
}


pub async fn run(mut ctx: Context, mut commands: mpsc::UnboundedReceiver<Command>) {
    let dir = ctx
        .shared
        .paths
        .root()
        .join("cache")
        .join("clip-buffer")
        .join(format!("{}-{}", ctx.instance_id, &uuid::Uuid::new_v4().simple().to_string()[..8]));
    let pins: Arc<Mutex<Pins>> = Arc::default();
    let mut live: Option<Recorder> = None;
    let mut recording: Option<(u64, Instant)> = None;
    let mut next_segment: u64 = 0;
    let mut failed_codecs: Vec<Codec> = Vec::new();
    let mut retry_at = Instant::now();
    let mut reason: Option<&'static str> = Some("starting");
    let mut last_disk_check = Instant::now();
    let mut announced_error = false;
    let mut exports = tokio::task::JoinSet::new();

    loop {
        // 1. Aufnahme am Laufen halten.
        if let Some(l) = live.as_mut()
            && !l.alive()
        {
            tracing::info!("FFmpeg beendet ({}): {}", ctx.instance_id, l.tail());
            let segs = recorder::segments(&dir);
            next_segment = segs.last().map_or(next_segment, |n| n + 1);
            live = None;
            reason = Some("starting");
        }
        if live.is_none() && ctx.settings.enabled && Instant::now() >= retry_at {
            match start(&ctx, &dir, next_segment, &mut failed_codecs).await {
                Ok(Some(l)) => {
                    live = Some(l);
                    reason = None;
                    announced_error = false;
                }
                Ok(None) => {
                    // Noch kein Fenster oder FFmpeg lädt noch.
                    reason = Some(ctx.shared.waiting_reason().await);
                    retry_at = Instant::now() + WINDOW_POLL;
                }
                Err(e) => {
                    tracing::warn!("Aufnahme für '{}' startet nicht: {e}", ctx.instance_id);
                    reason = Some("error");
                    if !announced_error {
                        announced_error = true;
                        ctx.shared.emit(ClipEvent::Failed { instance_id: ctx.instance_id.clone(), code: "error" });
                    }
                    retry_at = Instant::now() + Duration::from_secs(30);
                }
            }
        }

        // 2. Befehle.
        let wait = tokio::time::sleep(Duration::from_millis(250));
        tokio::pin!(wait);
        let command = tokio::select! {
            c = commands.recv() => Some(c.unwrap_or(Command::Stop)),
            Some(done) = exports.join_next() => {
                if let Ok(result) = done {
                    finish_export(&ctx, result);
                }
                None
            }
            () = &mut wait => None,
        };
        if let Some(c) = &command {
            tracing::debug!("Clip-Sitzung '{}': {c:?}", ctx.instance_id);
        }
        match command {
            Some(Command::Stop) => break,
            Some(Command::Settings(new)) => {
                let restart = pipeline_changed(&ctx.settings, &new);
                ctx.settings = new;
                if !ctx.settings.enabled {
                    break;
                }
                if restart && let Some(mut l) = live.take() {
                    stop_live(&mut l, &dir, &mut next_segment);
                    failed_codecs.clear();
                    retry_at = Instant::now();
                }
            }
            Some(Command::SaveClip) => {
                if let Some(l) = live.as_ref() {
                    let segs = recorder::segments(&dir);
                    if let Some(&current) = segs.last() {
                        let count = u64::from(ctx.settings.buffer_seconds.div_ceil(SEGMENT_SECONDS));
                        let from = current.saturating_sub(count.saturating_sub(1)).max(segs[0]);
                        exports.spawn(export(Export::for_ctx(&ctx, &dir, &pins, "clip", from, current, l.pid())));
                    } else {
                        ctx.shared.fail(&ctx.instance_id, "noFrames");
                    }
                } else {
                    ctx.shared.fail(&ctx.instance_id, reason.unwrap_or("starting"));
                }
            }
            Some(Command::ToggleRecording) => {
                if let Some((from, _)) = recording.take() {
                    let current = recorder::segments(&dir).last().copied().unwrap_or(from);
                    let pid = live.as_ref().map_or(0, |l| l.pid());
                    exports.spawn(export(Export::for_ctx(&ctx, &dir, &pins, "recording", from, current, pid)));
                } else if live.is_some() {
                    let current = recorder::segments(&dir).last().copied().unwrap_or(next_segment);
                    pins.lock().unwrap_or_else(std::sync::PoisonError::into_inner).0.push((current, u64::MAX));
                    recording = Some((current, Instant::now()));
                } else {
                    ctx.shared.fail(&ctx.instance_id, reason.unwrap_or("starting"));
                }
            }
            None => {}
        }
        // Die Sperre einer beendeten Aufnahme wieder freigeben.
        if recording.is_none() {
            pins.lock().unwrap_or_else(std::sync::PoisonError::into_inner).0.retain(|(_, b)| *b != u64::MAX);
        }

        // 3. Alte Segmente löschen, Speicher prüfen, Status melden.
        cleanup(&dir, &ctx.settings, &pins);
        if recording.is_some() && last_disk_check.elapsed() > Duration::from_secs(30) {
            last_disk_check = Instant::now();
            if library::free_space(&dir).is_some_and(|free| free < MIN_FREE_BYTES) {
                tracing::warn!("Wenig Speicherplatz – Aufnahme wird beendet");
                if let Some((from, _)) = recording.take() {
                    let current = recorder::segments(&dir).last().copied().unwrap_or(from);
                    let pid = live.as_ref().map_or(0, |l| l.pid());
                    exports.spawn(export(Export::for_ctx(&ctx, &dir, &pins, "recording", from, current, pid)));
                }
            }
        }
        let state = LinkState {
            available: live.is_some(),
            reason: if live.is_some() { None } else { reason },
            buffer: live.is_some(),
            recording: recording.is_some(),
            recording_ms: recording.as_ref().map_or(0, |(_, since)| since.elapsed().as_millis() as u64),
            clip_seconds: ctx.settings.buffer_seconds,
        };
        ctx.shared.publish(&ctx.instance_id, state, live.as_ref().map(|l| l.codec));
    }

    // Ende: laufende Exporte abwarten, FFmpeg beenden (schließt das letzte Segment ab)
    // und eine laufende Aufnahme vollständig sichern, dann aufräumen.
    while let Some(done) = exports.join_next().await {
        if let Ok(result) = done {
            finish_export(&ctx, result);
        }
    }
    if let Some(mut l) = live.take() {
        stop_live(&mut l, &dir, &mut next_segment);
    }
    if let Some((from, _)) = recording.take() {
        let current = recorder::segments(&dir).last().copied().unwrap_or(from);
        finish_export(&ctx, export(Export::for_ctx(&ctx, &dir, &pins, "recording", from, current, 0)).await);
    }
    let _ = tokio::fs::remove_dir_all(&dir).await;
    ctx.shared.ended(&ctx.instance_id);
}

/// Einstellungen, für die FFmpeg neu gestartet werden muss.
fn pipeline_changed(old: &ClipSettings, new: &ClipSettings) -> bool {
    old.resolution != new.resolution
        || old.fps != new.fps
        || old.quality != new.quality
        || old.encoder != new.encoder
        || old.system_audio != new.system_audio
        || old.microphone != new.microphone
}

fn stop_live(l: &mut Recorder, dir: &Path, next_segment: &mut u64) {
    l.stop();
    *next_segment = recorder::segments(dir).last().map_or(*next_segment, |n| n + 1);
}

/// Fenster suchen, FFmpeg sicherstellen, Encoder wählen und starten.
async fn start(ctx: &Context, dir: &Path, first_segment: u64, failed: &mut Vec<Codec>) -> Result<Option<Recorder>> {
    let tree = window::process_tree(ctx.pid);
    let Some(win) = window::find(&tree) else { return Ok(None) };
    let Some(exe) = ctx.shared.ffmpeg_ready_or_install().await else { return Ok(None) };
    let candidates: Vec<Codec> =
        encoder::Codec::candidates(ctx.settings.encoder).into_iter().filter(|c| !failed.contains(c)).collect();
    if candidates.is_empty() {
        failed.clear();
        return Err(Error::Internal("kein Encoder funktioniert".into()));
    }
    for codec in ctx.shared.usable_codecs(&exe, &candidates).await {
        let mut plan: Plan = Plan::new(&ctx.settings, win.hwnd, (win.width, win.height), codec, super::audio::wall_us());
        plan.first_segment = first_segment;
        let mut rec = Recorder::spawn(&exe, dir, &plan, ctx.settings.system_audio, ctx.settings.microphone)?;
        // Läuft er an? (erstes Segment angefangen, Prozess lebt)
        let started = Instant::now();
        let ok = loop {
            if !rec.alive() {
                break false;
            }
            if recorder::segments(dir).last().is_some_and(|n| *n >= first_segment) {
                break true;
            }
            if started.elapsed() > FIRST_SEGMENT_TIMEOUT {
                break false;
            }
            tokio::time::sleep(Duration::from_millis(200)).await;
        };
        if ok {
            return Ok(Some(rec));
        }
        tracing::warn!("{} liefert keine Aufnahme: {}", codec.ffmpeg_name(), rec.tail());
        rec.stop();
        failed.push(codec);
        // Halbe Segmente des Fehlversuchs entfernen.
        for n in recorder::segments(dir).into_iter().filter(|n| *n >= first_segment) {
            let _ = std::fs::remove_file(dir.join(encoder::segment_name(n)));
        }
    }
    Err(Error::Internal("Aufnahme startet mit keinem Encoder".into()))
}

/// Löscht Segmente, die weder im Puffer noch in einer Aufnahme/einem Export gebraucht werden.
fn cleanup(dir: &Path, settings: &ClipSettings, pins: &Mutex<Pins>) {
    let segs = recorder::segments(dir);
    let Some(&newest) = segs.last() else { return };
    let keep = u64::from(settings.buffer_seconds.div_ceil(SEGMENT_SECONDS)) + 3;
    let pins = pins.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    for n in segs {
        if n + keep < newest && !pins.contains(n) {
            let _ = std::fs::remove_file(dir.join(encoder::segment_name(n)));
        }
    }
}

struct Export {
    shared: Arc<Shared>,
    dir: PathBuf,
    pins: Arc<Mutex<Pins>>,
    instance_id: String,
    instance_name: String,
    kind: &'static str,
    from: u64,
    /// Segment, das beim Tastendruck gerade geschrieben wurde.
    current: u64,
    ffmpeg_pid: u32,
    root: PathBuf,
    limit: u64,
}

impl Export {
    fn for_ctx(ctx: &Context, dir: &Path, pins: &Arc<Mutex<Pins>>, kind: &'static str, from: u64, current: u64, ffmpeg_pid: u32) -> Self {
        pins.lock().unwrap_or_else(std::sync::PoisonError::into_inner).0.push((from, current + 1));
        Self {
            shared: ctx.shared.clone(),
            dir: dir.to_owned(),
            pins: pins.clone(),
            instance_id: ctx.instance_id.clone(),
            instance_name: ctx.instance_name.clone(),
            kind,
            from,
            current,
            ffmpeg_pid,
            root: library::clips_root(&ctx.shared.paths, &ctx.settings),
            limit: ctx.settings.max_storage_bytes(),
        }
    }
}

struct Exported {
    kind: &'static str,
    result: Result<(PathBuf, u32, u32)>,
}

async fn export(job: Export) -> Exported {
    let result = export_inner(&job).await;
    job.pins
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .0
        .retain(|r| *r != (job.from, job.current + 1));
    Exported { kind: job.kind, result }
}

async fn export_inner(job: &Export) -> Result<(PathBuf, u32, u32)> {
    // Warten, bis das beim Tastendruck laufende Segment fertig ist (dann ist der Moment drin).
    let started = Instant::now();
    let mut last = job.current;
    while started.elapsed() < SEGMENT_WAIT {
        if recorder::segments(&job.dir).last().is_some_and(|n| *n > job.current) {
            last = job.current;
            break;
        }
        if job.ffmpeg_pid == 0 {
            // FFmpeg läuft nicht mehr – das letzte Segment ist abgeschlossen.
            last = job.current;
            break;
        }
        last = job.current.saturating_sub(1);
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    let segs: Vec<u64> = recorder::segments(&job.dir).into_iter().filter(|n| (job.from..=last).contains(n)).collect();
    if segs.is_empty() {
        return Err(Error::validation(crate::msg!("clips.noFrames", "Noch nichts aufgenommen.")));
    }
    let exe = job.shared.ffmpeg.ready().await.ok_or_else(|| Error::Internal("ffmpeg fehlt".into()))?;
    let list = job.dir.join(format!("export-{}.txt", uuid::Uuid::new_v4().simple()));
    tokio::fs::write(&list, encoder::concat_list(&segs)).await.map_err(|e| Error::io(&list, e))?;
    let target = library::new_clip_path(&job.root, &job.instance_id, &job.instance_name, chrono::Local::now())?;
    let parent = target.parent().expect("Instanzordner").to_owned();
    crate::fsutil::ensure_dir(&parent).await?;
    let tmp = parent.join(format!(".export-{}.part", uuid::Uuid::new_v4().simple()));
    let timeout = Duration::from_secs(60 + segs.len() as u64);
    let result = recorder::run(&exe, encoder::concat_args(&list, &tmp), timeout).await;
    let _ = tokio::fs::remove_file(&list).await;
    if let Err(e) = result {
        let _ = tokio::fs::remove_file(&tmp).await;
        return Err(e);
    }
    tokio::fs::rename(&tmp, &target).await.map_err(|e| Error::io(&target, e))?;
    let seconds = library::mp4_duration_ms(&target).map_or(segs.len() as u32 * SEGMENT_SECONDS, |ms| ((ms + 500) / 1000) as u32);
    let (root, limit, keep) = (job.root.clone(), job.limit, target.clone());
    let removed = tokio::task::spawn_blocking(move || library::enforce_limit(&root, limit, &keep).len())
        .await
        .unwrap_or(0);
    Ok((target, seconds, u32::try_from(removed).unwrap_or(u32::MAX)))
}

fn finish_export(ctx: &Context, done: Exported) {
    match done.result {
        Ok((path, seconds, removed)) => {
            let file_name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            tracing::info!("{} gespeichert: {} ({seconds} s)", done.kind, path.display());
            ctx.shared.link_send(&ctx.instance_id, LinkEvent::Saved { kind: done.kind, seconds });
            ctx.shared.emit(ClipEvent::Saved {
                instance_id: ctx.instance_id.clone(),
                file_name,
                kind: done.kind,
                seconds,
                removed,
            });
        }
        Err(e) => {
            tracing::warn!("{} konnte nicht gespeichert werden: {e}", done.kind);
            let code = if matches!(&e, Error::Validation(m) if m.code == "clips.noFrames") { "noFrames" } else { "error" };
            ctx.shared.fail(&ctx.instance_id, code);
        }
    }
}
