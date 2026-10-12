//! Videos als Quelle für eigene Umhänge (MP4, WebM …). Das mitgeladene FFmpeg
//! der Clips zerlegt einen gewählten Bereich in höchstens 32 Einzelbilder
//! (passend verkleinert, als PNG). Der Pfad verlässt den Kern nie: das Webview
//! bekommt nur eine zufällige Kennung und die Eckdaten des Videos.

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Mutex;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use futures::future::BoxFuture;
use serde::{Deserialize, Serialize};

use super::cape_import::{ANIMATION_OUTPUT_SIDE, AnimationFormat, CapeSource, fit};
use super::{png, validate};
use crate::clips::edit::{self, FfmpegRunner, Progress};
use crate::clips::recorder;
use crate::{Error, Result};

/// Dateiendungen im Dateidialog (erkannt wird der Inhalt von FFmpeg).
pub const VIDEO_EXTENSIONS: [&str; 6] = ["mp4", "webm", "mkv", "mov", "m4v", "avi"];
/// Bildrate, die man wählen kann.
pub const MIN_FPS: u32 = 1;
pub const MAX_FPS: u32 = 30;
/// Kürzester Bereich (ms).
const MIN_RANGE_MS: u64 = 50;
/// Nur so viele gewählte Videos merkt sich der Kern (älteste fallen heraus).
const MAX_PICKS: usize = 4;
const PROBE_TIMEOUT: Duration = Duration::from_secs(30);
const EXTRACT_TIMEOUT: Duration = Duration::from_secs(300);
/// Größtes Einzelbild von FFmpeg (1024×1024 RGBA, unkomprimiert wäre 4 MiB).
const MAX_FRAME_BYTES: u64 = 8 * 1024 * 1024;
/// So viel von der Fehlerausgabe wird behalten.
const STDERR_TAIL: usize = 8 * 1024;

/// Eckdaten eines gewählten Videos (für die Auswahl von Bereich und Bildrate).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoInfo {
    pub token: String,
    pub name: String,
    pub duration_ms: u64,
    pub width: u32,
    pub height: u32,
}

/// Auftrag aus der Oberfläche: Bereich und Bildrate.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoRequest {
    pub start_ms: u64,
    pub end_ms: u64,
    pub fps: u32,
}

/// Geprüfter Auftrag: Bereich im Video, Bildrate, Zahl der Frames, Ausgabegröße.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ExtractPlan {
    pub start_ms: u64,
    pub end_ms: u64,
    pub fps: u32,
    pub frames: u32,
    pub width: u32,
    pub height: u32,
}

/// Gewählte Videos (Kennung → Pfad), nur im Kern.
#[derive(Default)]
pub struct VideoPicks {
    picks: Mutex<VecDeque<(PathBuf, VideoInfo)>>,
}

impl VideoPicks {
    fn lock(&self) -> std::sync::MutexGuard<'_, VecDeque<(PathBuf, VideoInfo)>> {
        self.picks.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Merkt sich ein Video und gibt seine Eckdaten (mit neuer Kennung) zurück.
    pub fn insert(&self, path: PathBuf, name: String, duration_ms: u64, width: u32, height: u32) -> VideoInfo {
        let info = VideoInfo { token: uuid::Uuid::new_v4().simple().to_string(), name, duration_ms, width, height };
        let mut picks = self.lock();
        while picks.len() >= MAX_PICKS {
            picks.pop_front();
        }
        picks.push_back((path, info.clone()));
        info
    }

    pub fn get(&self, token: &str) -> Result<(PathBuf, VideoInfo)> {
        let valid = token.len() == 32 && token.bytes().all(|b| b.is_ascii_hexdigit());
        valid
            .then(|| self.lock().iter().find(|(_, info)| info.token == token).cloned())
            .flatten()
            .ok_or_else(|| {
                Error::validation(crate::msg!("trsCapeVideo.expired", "Das Video ist nicht mehr gewählt – bitte erneut auswählen."))
            })
    }
}

/// Videos gibt es nur, wo FFmpeg mitgeladen werden kann (wie Clips: Windows).
pub fn supported() -> Result<()> {
    if cfg!(windows) {
        Ok(())
    } else {
        Err(Error::validation(crate::msg!("trsCapeVideo.unsupported", "Videos lassen sich hier nicht als Umhang verwenden.")))
    }
}

pub fn ffmpeg_missing() -> Error {
    Error::validation(crate::msg!("trsCapeVideo.ffmpegMissing", "Für Videos wird zuerst FFmpeg geladen."))
}

fn unreadable(name: &str) -> Error {
    Error::validation(crate::msg!("trsCapeVideo.unreadable", "„{name}“ lässt sich nicht als Video lesen.", name = name))
}

/// Eigener, frischer Arbeitsordner für einen Lauf (`<daten>/cache/cape-video/<zufall>`).
pub fn work_dir(root: &Path) -> PathBuf {
    root.join("cache").join("cape-video").join(uuid::Uuid::new_v4().simple().to_string())
}

fn secs(ms: u64) -> String {
    format!("{}.{:03}", ms / 1000, ms % 1000)
}

/// Dauer und Bildgröße aus der Ausgabe von `ffmpeg -i <video>` (stderr).
pub fn parse_probe(stderr: &str) -> Option<(u64, u32, u32)> {
    let duration = stderr.lines().find_map(|line| {
        let rest = line.trim().strip_prefix("Duration:")?.trim();
        let stamp = rest.split(',').next()?.trim();
        let mut parts = stamp.split(':');
        let (h, m, s) = (parts.next()?, parts.next()?, parts.next()?);
        let (h, m) = (h.parse::<u64>().ok()?, m.parse::<u64>().ok()?);
        let (whole, frac) = s.split_once('.').unwrap_or((s, "0"));
        let whole = whole.parse::<u64>().ok()?;
        let frac: String = frac.chars().chain("000".chars()).take(3).collect();
        let ms = frac.parse::<u64>().ok()?;
        Some(((h * 60 + m) * 60 + whole) * 1000 + ms)
    })?;
    let (width, height) = stderr.lines().filter(|l| l.contains("Video:")).find_map(|line| {
        line.split([' ', ',', '[', ']']).find_map(|token| {
            let (w, h) = token.split_once('x')?;
            if w.starts_with('0') || h.starts_with('0') {
                return None;
            }
            let (w, h) = (w.parse::<u32>().ok()?, h.parse::<u32>().ok()?);
            ((1..=16_384).contains(&w) && (1..=16_384).contains(&h)).then_some((w, h))
        })
    })?;
    (duration > 0).then_some((duration, width, height))
}

/// Eckdaten mit FFmpeg lesen (`ffmpeg -i` endet ohne Ausgabe mit Fehler – das ist gewollt).
pub async fn probe(exe: &Path, video: &Path, name: &str) -> Result<(u64, u32, u32)> {
    let mut cmd = tokio::process::Command::from(recorder::command(exe));
    cmd.args(["-hide_banner", "-nostdin", "-i"]).arg(video).kill_on_drop(true);
    let output = tokio::time::timeout(PROBE_TIMEOUT, cmd.output())
        .await
        .map_err(|_| unreadable(name))?
        .map_err(|e| Error::Internal(format!("ffmpeg: {e}")))?;
    parse_probe(&String::from_utf8_lossy(&output.stderr)).ok_or_else(|| unreadable(name))
}

/// Auftrag prüfen: Bildrate 1–30, Bereich im Video, höchstens 32 Frames.
pub fn plan(info: &VideoInfo, request: &VideoRequest) -> Result<ExtractPlan> {
    let invalid = || Error::validation(crate::msg!("trsCapeVideo.badRange", "Bitte einen gültigen Bereich im Video wählen."));
    if !(MIN_FPS..=MAX_FPS).contains(&request.fps) {
        return Err(invalid());
    }
    let end_ms = request.end_ms.min(info.duration_ms);
    if request.start_ms >= end_ms || end_ms - request.start_ms < MIN_RANGE_MS {
        return Err(invalid());
    }
    let wanted = ((end_ms - request.start_ms) * u64::from(request.fps)).div_ceil(1000);
    let frames = u32::try_from(wanted).unwrap_or(u32::MAX).clamp(1, png::MAX_UPLOAD_FRAMES);
    let (width, height) = fit(info.width, info.height, ANIMATION_OUTPUT_SIDE);
    Ok(ExtractPlan { start_ms: request.start_ms, end_ms, fps: request.fps, frames, width, height })
}

/// FFmpeg: Bereich mit fester Bildrate, verkleinert, als nummerierte PNGs.
pub fn extract_args(video: &Path, out_dir: &Path, plan: &ExtractPlan) -> Vec<String> {
    let mut args: Vec<String> = ["-hide_banner", "-loglevel", "error", "-nostdin", "-y"].map(String::from).to_vec();
    args.extend(["-ss".to_owned(), secs(plan.start_ms), "-t".to_owned(), secs(plan.end_ms - plan.start_ms)]);
    args.extend(["-i".to_owned(), video.display().to_string(), "-an".to_owned(), "-sn".to_owned(), "-dn".to_owned()]);
    args.push("-vf".to_owned());
    args.push(format!("fps={},scale={}:{}:flags=area,format=rgba", plan.fps, plan.width, plan.height));
    args.extend(["-frames:v".to_owned(), plan.frames.to_string(), "-progress".to_owned(), "pipe:1".to_owned()]);
    args.extend(["-f".to_owned(), "image2".to_owned(), out_dir.join("f%03d.png").display().to_string()]);
    args
}

/// Nummerierte Bilder `f001.png` … in Reihenfolge (höchstens `max`).
fn frame_files(dir: &Path, max: u32) -> Vec<PathBuf> {
    let mut files: Vec<(u32, PathBuf)> = std::fs::read_dir(dir)
        .map(|entries| {
            entries
                .filter_map(std::result::Result::ok)
                .filter_map(|e| {
                    let name = e.file_name().to_string_lossy().into_owned();
                    let n = name.strip_prefix('f')?.strip_suffix(".png")?.parse::<u32>().ok()?;
                    Some((n, e.path()))
                })
                .collect()
        })
        .unwrap_or_default();
    files.sort_by_key(|(n, _)| *n);
    files.into_iter().take(max as usize).map(|(_, p)| p).collect()
}

/// Zerlegt den Bereich in Frames. Läuft als Aufgabe (Abbrechen beendet FFmpeg);
/// der Arbeitsordner wird immer wieder gelöscht.
pub async fn extract(
    runner: &dyn FfmpegRunner,
    video: &Path,
    work_dir: &Path,
    info: &VideoInfo,
    request: &VideoRequest,
    progress: Option<Progress>,
) -> Result<CapeSource> {
    let plan = plan(info, request)?;
    crate::fsutil::ensure_dir(work_dir).await?;
    let result = extract_in(runner, video, work_dir, info, &plan, progress).await;
    let _ = tokio::fs::remove_dir_all(work_dir).await;
    result
}

async fn extract_in(
    runner: &dyn FfmpegRunner,
    video: &Path,
    work_dir: &Path,
    info: &VideoInfo,
    plan: &ExtractPlan,
    progress: Option<Progress>,
) -> Result<CapeSource> {
    crate::task::or_cancel(runner.run(extract_args(video, work_dir, plan), progress, EXTRACT_TIMEOUT)).await??;
    let dir = work_dir.to_owned();
    let max = plan.frames;
    let files = tokio::task::spawn_blocking(move || frame_files(&dir, max)).await.map_err(|e| Error::Internal(e.to_string()))?;
    if files.is_empty() {
        return Err(unreadable(&info.name));
    }
    let mut frames = Vec::with_capacity(files.len());
    for file in &files {
        crate::task::checkpoint().await?;
        let meta = tokio::fs::metadata(file).await.map_err(|e| Error::io(file, e))?;
        if meta.len() > MAX_FRAME_BYTES {
            return Err(unreadable(&info.name));
        }
        let bytes = tokio::fs::read(file).await.map_err(|e| Error::io(file, e))?;
        // Nur Bilder in der geplanten Größe gehen ans Webview.
        if png::size(&bytes) != Some((plan.width, plan.height)) {
            return Err(unreadable(&info.name));
        }
        frames.push(format!("data:image/png;base64,{}", STANDARD.encode(&bytes)));
    }
    let frame_ms = 1000 / plan.fps;
    let count = frames.len() as u32;
    Ok(CapeSource::Animation {
        name: validate::text(&info.name, 64),
        format: AnimationFormat::Video,
        width: plan.width,
        height: plan.height,
        frames,
        delays_ms: vec![frame_ms; count as usize],
        duration_ms: frame_ms * count,
        source_frames: count,
    })
}

/// FFmpeg als Kindprozess, der beim Abbrechen (Future verworfen) beendet wird.
pub struct KillableRunner {
    pub exe: PathBuf,
}

impl FfmpegRunner for KillableRunner {
    fn run(&self, args: Vec<String>, progress: Option<Progress>, timeout: Duration) -> BoxFuture<'_, Result<()>> {
        Box::pin(async move {
            use tokio::io::{AsyncBufReadExt, AsyncReadExt};
            let mut cmd = tokio::process::Command::from(recorder::command(&self.exe));
            cmd.args(&args).stdout(Stdio::piped()).stderr(Stdio::piped()).kill_on_drop(true);
            let mut child = cmd.spawn().map_err(|e| Error::Internal(format!("ffmpeg: {e}")))?;
            let stdout = child.stdout.take();
            let stderr = child.stderr.take();
            let read_progress = async move {
                let Some(out) = stdout else { return };
                let mut lines = tokio::io::BufReader::new(out).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    if let (Some(us), Some(report)) = (edit::parse_progress_line(&line), progress.as_ref()) {
                        report(us);
                    }
                }
            };
            let read_errors = async move {
                let mut tail = Vec::new();
                let Some(mut err) = stderr else { return tail };
                let mut chunk = [0u8; 4096];
                while let Ok(n) = err.read(&mut chunk).await {
                    if n == 0 {
                        break;
                    }
                    tail.extend_from_slice(&chunk[..n]);
                    if tail.len() > STDERR_TAIL {
                        tail.drain(..tail.len() - STDERR_TAIL);
                    }
                }
                tail
            };
            let work = async { tokio::join!(read_progress, read_errors, child.wait()) };
            let (_, tail, status) = tokio::time::timeout(timeout, work)
                .await
                .map_err(|_| Error::Internal("ffmpeg: Zeitüberschreitung".into()))?;
            let status = status.map_err(|e| Error::Internal(format!("ffmpeg: {e}")))?;
            if status.success() {
                Ok(())
            } else {
                Err(Error::Internal(format!("ffmpeg ({status}): {}", String::from_utf8_lossy(&tail).trim())))
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PROBE: &str = "Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'clip.mp4':\n  Metadata:\n    major_brand     : isom\n  \
        Duration: 00:01:02.50, start: 0.000000, bitrate: 2178 kb/s\n  Stream #0:0[0x1](und): Video: h264 (High) \
        (avc1 / 0x31637661), yuv420p(progressive), 1920x1080 [SAR 1:1 DAR 16:9], 30 fps, 30 tbr\n  \
        Stream #0:1[0x2](und): Audio: aac (LC)\nAt least one output file must be specified\n";

    fn info(duration_ms: u64) -> VideoInfo {
        VideoInfo { token: "a".repeat(32), name: "clip".into(), duration_ms, width: 1920, height: 1080 }
    }

    #[test]
    fn reads_duration_and_size() {
        assert_eq!(parse_probe(PROBE), Some((62_500, 1920, 1080)));
        let webm = "  Duration: 00:00:03.04, start: -0.007000, bitrate: 600 kb/s\n  Stream #0:0: Video: vp9 (Profile 0), yuv420p(tv), 640x360, SAR 1:1 DAR 16:9, 25 fps\n";
        assert_eq!(parse_probe(webm), Some((3040, 640, 360)));
        assert_eq!(parse_probe("Duration: N/A\nStream #0:0: Video: png, 64x32"), None, "ohne Dauer");
        assert_eq!(parse_probe("Duration: 00:00:01.00\nStream #0:0: Audio: mp3"), None, "ohne Bild");
        assert_eq!(parse_probe("garbage"), None);
    }

    #[test]
    fn plans_at_most_32_frames() {
        let p = plan(&info(10_000), &VideoRequest { start_ms: 1000, end_ms: 2000, fps: 10 }).unwrap();
        assert_eq!((p.frames, p.width, p.height), (10, 1024, 576));
        let long = plan(&info(60_000), &VideoRequest { start_ms: 0, end_ms: 60_000, fps: 30 }).unwrap();
        assert_eq!(long.frames, 32, "nie mehr als 32 Frames");
        let clamped = plan(&info(1500), &VideoRequest { start_ms: 0, end_ms: 9000, fps: 2 }).unwrap();
        assert_eq!((clamped.end_ms, clamped.frames), (1500, 3), "Ende auf die Videolänge begrenzt");
        for bad in [
            VideoRequest { start_ms: 0, end_ms: 1000, fps: 0 },
            VideoRequest { start_ms: 0, end_ms: 1000, fps: 31 },
            VideoRequest { start_ms: 900, end_ms: 900, fps: 10 },
            VideoRequest { start_ms: 0, end_ms: 20, fps: 10 },
            VideoRequest { start_ms: 20_000, end_ms: 30_000, fps: 10 },
        ] {
            assert_eq!(plan(&info(10_000), &bad).unwrap_err().message_code(), "trsCapeVideo.badRange", "{bad:?}");
        }
    }

    #[test]
    fn ffmpeg_args_trim_and_scale() {
        let p = plan(&info(10_000), &VideoRequest { start_ms: 1500, end_ms: 4250, fps: 8 }).unwrap();
        let args = extract_args(Path::new("in.mp4"), Path::new("out"), &p).join(" ");
        assert!(args.contains("-ss 1.500 -t 2.750 -i in.mp4"), "{args}");
        assert!(args.contains("fps=8,scale=1024:576:flags=area,format=rgba"), "{args}");
        assert!(args.contains("-frames:v 22"), "{args}");
        assert!(args.contains("-progress pipe:1"));
    }

    #[test]
    fn picks_are_bounded_and_checked() {
        let picks = VideoPicks::default();
        let first = picks.insert("a.mp4".into(), "a".into(), 1000, 10, 10);
        assert!(picks.get(&first.token).is_ok());
        for i in 0..MAX_PICKS {
            picks.insert(format!("{i}.mp4").into(), "b".into(), 1000, 10, 10);
        }
        assert_eq!(picks.get(&first.token).unwrap_err().message_code(), "trsCapeVideo.expired", "älteste fällt heraus");
        assert!(picks.get("../../etc").is_err());
    }

    /// Attrappe: schreibt statt FFmpeg fertige PNGs in den Ordner.
    struct FakeRunner {
        frames: u32,
        size: (u32, u32),
    }

    impl FfmpegRunner for FakeRunner {
        fn run(&self, args: Vec<String>, progress: Option<Progress>, _timeout: Duration) -> BoxFuture<'_, Result<()>> {
            Box::pin(async move {
                let pattern = PathBuf::from(args.last().unwrap());
                let dir = pattern.parent().unwrap().to_owned();
                for i in 1..=self.frames {
                    let img = image::RgbaImage::from_pixel(self.size.0, self.size.1, image::Rgba([i as u8, 0, 0, 255]));
                    img.save(dir.join(format!("f{i:03}.png"))).unwrap();
                }
                if let Some(p) = progress {
                    p(1_000_000);
                }
                Ok(())
            })
        }
    }

    #[tokio::test]
    async fn extracts_frames_and_cleans_up() {
        let dir = tempfile::tempdir().unwrap();
        let work = dir.path().join("work");
        let info = VideoInfo { width: 64, height: 32, ..info(5000) };
        let request = VideoRequest { start_ms: 0, end_ms: 400, fps: 10 };
        let runner = FakeRunner { frames: 4, size: (64, 32) };
        let src = extract(&runner, Path::new("v.mp4"), &work, &info, &request, None).await.unwrap();
        let CapeSource::Animation { frames, delays_ms, format, width, height, .. } = src else { panic!() };
        assert_eq!(format, AnimationFormat::Video);
        assert_eq!((frames.len(), width, height), (4, 64, 32));
        assert_eq!(delays_ms, vec![100; 4]);
        assert!(!work.exists(), "Arbeitsordner wird gelöscht");

        // Falsche Größe (nicht von uns geplant) wird abgewiesen.
        let wrong = FakeRunner { frames: 2, size: (10, 10) };
        let err = extract(&wrong, Path::new("v.mp4"), &work, &info, &request, None).await.unwrap_err();
        assert_eq!(err.message_code(), "trsCapeVideo.unreadable");
        assert!(!work.exists());
    }
}
