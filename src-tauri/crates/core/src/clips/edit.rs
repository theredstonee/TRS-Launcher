//! Clips bearbeiten und für die Wiedergabe aufbereiten – alles mit dem
//! mitgeladenen FFmpeg, alles lokal:
//!
//! - **Vorschau-Leiste** (`strip`): bis zu 60 kleine Bilder (160×90) gleichmäßig
//!   über den Clip verteilt, als ein PNG-Raster. Der Launcher zeigt daraus die
//!   Vorschau beim Überfahren der Zeitleiste, die Mod spielt sie im Spiel als
//!   kleine Animation ab (`clips.preview` über den TRS-Link).
//! - **Zuschneiden** (`trim`): immer als *neuer* Clip, das Original bleibt.
//!   Beginnt der Schnitt auf einem Keyframe, kopiert FFmpeg die Daten nur
//!   (`-c copy`, Sekundenbruchteile). Sonst müsste der Clip ab dem vorherigen
//!   Keyframe beginnen – dann wird neu kodiert (genau, aber langsamer), außer
//!   der Nutzer wählt ausdrücklich „schnell“ (Start rutscht auf den Keyframe).

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures::future::BoxFuture;
use serde::{Deserialize, Serialize};

use super::encoder::Codec;
use super::media::{self, MediaInfo};
use super::{library, recorder};
use crate::{Error, Result};

// --- FFmpeg-Aufrufe (austauschbar für Tests) ---------------------------------

/// Fortschritt eines Laufs: verarbeitete Ausgabezeit in Mikrosekunden.
pub type Progress = Arc<dyn Fn(u64) + Send + Sync>;

/// Führt FFmpeg mit Argumenten aus. Im Launcher ein echter Prozess, in Tests eine Attrappe.
pub trait FfmpegRunner: Send + Sync {
    fn run(&self, args: Vec<String>, progress: Option<Progress>, timeout: Duration) -> BoxFuture<'_, Result<()>>;
}

/// Echter FFmpeg-Prozess (ohne Konsolenfenster).
pub struct ProcessRunner {
    pub exe: PathBuf,
}

impl FfmpegRunner for ProcessRunner {
    fn run(&self, args: Vec<String>, progress: Option<Progress>, timeout: Duration) -> BoxFuture<'_, Result<()>> {
        let exe = self.exe.clone();
        Box::pin(async move {
            let Some(progress) = progress else { return recorder::run(&exe, args, timeout).await };
            tokio::task::spawn_blocking(move || run_with_progress(&exe, &args, &progress, timeout))
                .await
                .map_err(|e| Error::Internal(e.to_string()))?
        })
    }
}

/// Wie [`recorder::run`], liest aber `-progress pipe:1` (Zeilen `out_time_us=…`).
fn run_with_progress(exe: &Path, args: &[String], progress: &Progress, timeout: Duration) -> Result<()> {
    use std::io::BufRead;
    let mut child = recorder::command(exe)
        .args(args)
        .stdout(Stdio::piped())
        .spawn()
        .map_err(|e| Error::Internal(format!("ffmpeg: {e}")))?;
    let tail = Arc::new(Mutex::new(std::collections::VecDeque::new()));
    let err_reader = child.stderr.take().map(|s| recorder::spawn_tail(s, tail.clone()));
    let out_reader = child.stdout.take().map(|out| {
        let progress = progress.clone();
        std::thread::spawn(move || {
            for line in std::io::BufReader::new(out).lines().map_while(std::result::Result::ok) {
                if let Some(us) = parse_progress_line(&line) {
                    progress(us);
                }
            }
        })
    });
    let started = std::time::Instant::now();
    let status = loop {
        if let Some(status) = child.try_wait().map_err(|e| Error::Internal(format!("ffmpeg: {e}")))? {
            break status;
        }
        if started.elapsed() > timeout {
            let _ = child.kill();
            let _ = child.wait();
            return Err(Error::Internal("ffmpeg: Zeitüberschreitung".into()));
        }
        std::thread::sleep(Duration::from_millis(30));
    };
    for reader in [err_reader, out_reader].into_iter().flatten() {
        let _ = reader.join();
    }
    if status.success() {
        Ok(())
    } else {
        let text = tail.lock().unwrap_or_else(std::sync::PoisonError::into_inner).iter().cloned().collect::<Vec<_>>().join(" | ");
        Err(Error::Internal(format!("ffmpeg ({status}): {text}")))
    }
}

/// `out_time_us=5000000` → 5 000 000 (`N/A` und andere Zeilen → `None`).
pub fn parse_progress_line(line: &str) -> Option<u64> {
    line.trim().strip_prefix("out_time_us=")?.parse().ok()
}

fn secs(ms: u64) -> String {
    format!("{}.{:03}", ms / 1000, ms % 1000)
}

fn base_args() -> Vec<String> {
    ["-hide_banner", "-loglevel", "error", "-nostdin", "-y"].map(String::from).to_vec()
}

// --- Vorschau-Leiste ---------------------------------------------------------

pub const STRIP_FRAME_W: u32 = 160;
pub const STRIP_FRAME_H: u32 = 90;
pub const STRIP_COLS: u32 = 10;
const STRIP_MIN_FRAMES: u32 = 8;
const STRIP_MAX_FRAMES: u32 = 60;
/// Ab so viel Abstand zwischen den Bildern reichen Keyframes (alle 2 s) – viel schneller.
const KEYFRAMES_ONLY_FROM_MS: u64 = 2000;
const STRIP_TIMEOUT: Duration = Duration::from_secs(180);

/// Aufbau des PNG-Rasters (für Launcher und Mod).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipStrip {
    pub frames: u32,
    pub cols: u32,
    pub rows: u32,
    pub frame_width: u32,
    pub frame_height: u32,
    /// Clip-Zeit zwischen zwei Bildern (ms); Bild `i` zeigt etwa `i × intervalMs`.
    pub interval_ms: u32,
    pub duration_ms: u64,
}

/// Wie viele Bilder: etwa eins pro Sekunde, mindestens 8, höchstens 60.
pub fn strip_frames(duration_ms: u64) -> u32 {
    let per_second = u32::try_from(duration_ms.div_ceil(1000)).unwrap_or(u32::MAX);
    per_second.clamp(STRIP_MIN_FRAMES, STRIP_MAX_FRAMES)
}

/// FFmpeg: gleichmäßig verteilte Bilder, auf 160×90 eingepasst (schwarzer Rand), als rohe RGB-Daten.
pub fn strip_args(video: &Path, raw_out: &Path, duration_ms: u64, frames: u32) -> Vec<String> {
    let duration_ms = duration_ms.max(1);
    let mut args = base_args();
    if duration_ms / u64::from(frames.max(1)) >= KEYFRAMES_ONLY_FROM_MS {
        args.extend(["-skip_frame", "nokey"].map(String::from));
    }
    args.extend(["-i".to_owned(), video.display().to_string(), "-an".to_owned(), "-sn".to_owned(), "-vf".to_owned()]);
    let (w, h) = (STRIP_FRAME_W, STRIP_FRAME_H);
    args.push(format!(
        "fps=fps={}/{duration_ms},scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black,format=rgb24",
        u64::from(frames) * 1000
    ));
    args.extend(["-frames:v".to_owned(), frames.to_string(), "-f".to_owned(), "rawvideo".to_owned()]);
    args.push(raw_out.display().to_string());
    args
}

/// Rohe RGB-Bilder → PNG-Raster (höchstens `max_frames`). `None`, wenn kein ganzes Bild da ist.
pub fn compose_strip(raw: &[u8], max_frames: u32, duration_ms: u64) -> Option<(Vec<u8>, ClipStrip)> {
    let (w, h) = (STRIP_FRAME_W, STRIP_FRAME_H);
    let frame_bytes = (w * h * 3) as usize;
    let count = u32::try_from(raw.len() / frame_bytes).ok()?.min(max_frames);
    if count == 0 {
        return None;
    }
    let cols = count.min(STRIP_COLS);
    let rows = count.div_ceil(cols);
    let mut sheet = image::RgbImage::new(cols * w, rows * h);
    for i in 0..count {
        let frame = &raw[i as usize * frame_bytes..(i as usize + 1) * frame_bytes];
        let (ox, oy) = ((i % cols) * w, (i / cols) * h);
        for y in 0..h {
            let row = &frame[(y * w * 3) as usize..((y + 1) * w * 3) as usize];
            for x in 0..w {
                let p = &row[(x * 3) as usize..(x * 3 + 3) as usize];
                sheet.put_pixel(ox + x, oy + y, image::Rgb([p[0], p[1], p[2]]));
            }
        }
    }
    let mut png = Vec::new();
    sheet.write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png).ok()?;
    let interval_ms = u32::try_from(duration_ms / u64::from(count)).unwrap_or(u32::MAX).max(1);
    Some((png, ClipStrip { frames: count, cols, rows, frame_width: w, frame_height: h, interval_ms, duration_ms }))
}

/// Cache-Schlüssel einer Clip-Datei (Pfad + Größe + Änderungszeit).
pub fn cache_key(video: &Path, len: u64, modified_secs: u64) -> String {
    use sha1::Digest;
    let mut h = sha1::Sha1::new();
    h.update(video.display().to_string().as_bytes());
    h.update(format!("|{len}|{modified_secs}").as_bytes());
    h.finalize().iter().take(8).map(|b| format!("{b:02x}")).collect()
}

/// Schon erzeugte Vorschau-Leiste aus dem Cache.
pub async fn cached_strip(cache_dir: &Path, key: &str) -> Option<(PathBuf, ClipStrip)> {
    let png = cache_dir.join(format!("{key}.png"));
    let text = tokio::fs::read_to_string(cache_dir.join(format!("{key}.json"))).await.ok()?;
    let strip = serde_json::from_str::<ClipStrip>(&text).ok()?;
    tokio::fs::metadata(&png).await.is_ok_and(|m| m.len() > 0).then_some((png, strip))
}

/// Vorschau-Leiste erzeugen bzw. aus dem Cache holen: `(PNG-Pfad, Aufbau)`.
pub async fn strip(runner: &dyn FfmpegRunner, video: &Path, cache_dir: &Path, key: &str) -> Result<(PathBuf, ClipStrip)> {
    if let Some(cached) = cached_strip(cache_dir, key).await {
        return Ok(cached);
    }
    let png = cache_dir.join(format!("{key}.png"));
    let meta = cache_dir.join(format!("{key}.json"));
    crate::fsutil::ensure_dir(cache_dir).await?;
    let video_owned = video.to_owned();
    let info = tokio::task::spawn_blocking(move || media::probe(&video_owned))
        .await
        .map_err(|e| Error::Internal(e.to_string()))?
        .ok_or_else(|| Error::validation(crate::msg!("clips.unreadable", "Der Clip lässt sich nicht lesen.")))?;
    let frames = strip_frames(info.duration_ms);
    let raw = cache_dir.join(format!("{key}.raw.part"));
    let result = runner.run(strip_args(video, &raw, info.duration_ms, frames), None, STRIP_TIMEOUT).await;
    let bytes = tokio::fs::read(&raw).await;
    let _ = tokio::fs::remove_file(&raw).await;
    result?;
    let bytes = bytes.map_err(|e| Error::io(&raw, e))?;
    let duration = info.duration_ms;
    let (data, strip) = tokio::task::spawn_blocking(move || compose_strip(&bytes, frames, duration))
        .await
        .map_err(|e| Error::Internal(e.to_string()))?
        .ok_or_else(|| Error::validation(crate::msg!("clips.unreadable", "Der Clip lässt sich nicht lesen.")))?;
    crate::fsutil::write_atomic(&png, &data).await?;
    let json = serde_json::to_vec(&strip).map_err(|e| Error::json("strip", e))?;
    crate::fsutil::write_atomic(&meta, &json).await?;
    Ok((png, strip))
}

// --- Zuschneiden -------------------------------------------------------------

/// Wie geschnitten werden soll (Wahl in der Oberfläche).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub enum TrimMode {
    /// Kopieren, wenn der Start auf einem Keyframe liegt, sonst neu kodieren.
    #[default]
    Auto,
    /// Immer kopieren – der Start rutscht auf den Keyframe davor.
    Fast,
    /// Immer neu kodieren (bildgenau).
    Exact,
}

/// Auftrag aus der Oberfläche: Bereich, Art und Name des neuen Clips.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TrimRequest {
    pub start_ms: u64,
    pub end_ms: u64,
    #[serde(default)]
    pub mode: TrimMode,
    /// Name des neuen Clips (mit oder ohne `.mp4`).
    pub name: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum TrimMethod {
    Copy,
    Reencode,
}

/// Was tatsächlich passiert.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrimPlan {
    pub start_ms: u64,
    pub end_ms: u64,
    pub method: TrimMethod,
}

/// So nah (ms) nach einem Keyframe gilt ein Startpunkt noch als „auf dem Keyframe“.
pub const KEYFRAME_TOLERANCE_MS: u64 = 120;
/// Kürzer geht nicht.
pub const MIN_TRIM_MS: u64 = 500;

pub fn plan_trim(info: &MediaInfo, start_ms: u64, end_ms: u64, mode: TrimMode) -> Result<TrimPlan> {
    let end_ms = end_ms.min(info.duration_ms);
    if start_ms >= end_ms || info.duration_ms == 0 {
        return Err(Error::validation(crate::msg!("clips.trimRange", "Der Endpunkt muss nach dem Startpunkt liegen.")));
    }
    if end_ms - start_ms < MIN_TRIM_MS {
        return Err(Error::validation(crate::msg!("clips.trimTooShort", "Der neue Clip muss mindestens eine halbe Sekunde lang sein.")));
    }
    let keyframe = media::keyframe_at_or_before(&info.keyframes_ms, start_ms);
    let on_keyframe = start_ms == 0 || (!info.keyframes_ms.is_empty() && start_ms - keyframe <= KEYFRAME_TOLERANCE_MS);
    let (start_ms, method) = match mode {
        TrimMode::Exact => (start_ms, TrimMethod::Reencode),
        TrimMode::Fast => (keyframe, TrimMethod::Copy),
        TrimMode::Auto if on_keyframe => (keyframe, TrimMethod::Copy),
        TrimMode::Auto => (start_ms, TrimMethod::Reencode),
    };
    Ok(TrimPlan { start_ms, end_ms, method })
}

/// Ohne Neukodierung: ab dem Keyframe kopieren, Metadaten nach vorn.
pub fn copy_args(src: &Path, dst: &Path, plan: &TrimPlan) -> Vec<String> {
    let mut args = base_args();
    args.extend(["-ss".to_owned(), secs(plan.start_ms), "-i".to_owned(), src.display().to_string()]);
    args.extend(["-t".to_owned(), secs(plan.end_ms - plan.start_ms)]);
    args.extend(
        ["-map", "0:v:0?", "-map", "0:a?", "-c", "copy", "-avoid_negative_ts", "make_zero", "-movflags", "+faststart", "-f", "mp4"]
            .map(String::from),
    );
    args.push(dst.display().to_string());
    args
}

/// Neu kodieren (bildgenau). `kbps` ≈ Bitrate des Originals, damit die Qualität bleibt.
pub fn reencode_args(src: &Path, dst: &Path, plan: &TrimPlan, codec: Codec, kbps: u32) -> Vec<String> {
    let mut args = base_args();
    args.extend(["-progress".to_owned(), "pipe:1".to_owned(), "-nostats".to_owned()]);
    args.extend(["-ss".to_owned(), secs(plan.start_ms), "-i".to_owned(), src.display().to_string()]);
    args.extend(["-t".to_owned(), secs(plan.end_ms - plan.start_ms)]);
    args.extend(["-map", "0:v:0?", "-map", "0:a?", "-c:v", codec.ffmpeg_name()].map(String::from));
    let extra: &[&str] = match codec {
        Codec::Nvenc => &["-preset", "p5", "-rc", "vbr", "-cq", "19", "-pix_fmt", "yuv420p"],
        Codec::Amf => &["-quality", "quality", "-rc", "vbr_peak", "-pix_fmt", "yuv420p"],
        Codec::Qsv => &["-preset", "medium", "-pix_fmt", "nv12"],
        Codec::Mf => &["-hw_encoding", "1", "-pix_fmt", "nv12"],
        Codec::X264 => &["-preset", "veryfast", "-crf", "19", "-pix_fmt", "yuv420p"],
    };
    args.extend(extra.iter().map(|s| (*s).to_owned()));
    let kbps = kbps.clamp(2000, 80_000);
    args.extend(["-b:v".to_owned(), format!("{kbps}k"), "-maxrate".to_owned(), format!("{}k", kbps * 3 / 2)]);
    args.extend(["-bufsize".to_owned(), format!("{}k", kbps * 2)]);
    args.extend(["-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", "-f", "mp4"].map(String::from));
    args.push(dst.display().to_string());
    args
}

/// Bitrate des Originals in kbit/s (Größe ÷ Dauer).
pub fn source_kbps(size: u64, duration_ms: u64) -> u32 {
    if duration_ms == 0 {
        return 8000;
    }
    u32::try_from(size.saturating_mul(8) / duration_ms).unwrap_or(u32::MAX)
}

/// Freier Name für den neuen Clip im selben Ordner (`Name.mp4`, `Name (2).mp4`, …).
pub fn free_clip_name(dir: &Path, wanted: &str) -> Result<String> {
    let mut stem = wanted.trim().to_owned();
    if stem.to_ascii_lowercase().ends_with(".mp4") {
        stem.truncate(stem.len() - 4);
    }
    let stem = stem.trim().to_owned();
    if !library::is_clip_file_name(&format!("{stem}.mp4")) {
        return Err(Error::validation(crate::msg!("clips.invalidName", "Dieser Name ist als Dateiname nicht erlaubt.")));
    }
    for n in 1..1000 {
        let name = if n == 1 { format!("{stem}.mp4") } else { format!("{stem} ({n}).mp4") };
        if library::is_clip_file_name(&name) && std::fs::symlink_metadata(dir.join(&name)).is_err() {
            return Ok(name);
        }
    }
    Err(Error::validation(crate::msg!("clips.nameTaken", "Es gibt schon einen Clip mit diesem Namen.")))
}

/// Schneidet `src` als neuen Clip `name` in denselben Ordner. Nie wird das
/// Original (oder ein anderer Clip) überschrieben: erst in eine `.part`-Datei,
/// dann unter einem freien Namen ablegen. `codecs`: Encoder zum Neukodieren in
/// der Reihenfolge des Ausprobierens. Liefert den Dateinamen des neuen Clips.
pub async fn trim(
    runner: &dyn FfmpegRunner,
    src: &Path,
    plan: &TrimPlan,
    name: &str,
    codecs: &[Codec],
    kbps: u32,
    progress: Option<Progress>,
) -> Result<String> {
    let dir = src.parent().ok_or_else(|| Error::Internal("Clip ohne Ordner".into()))?;
    free_clip_name(dir, name)?; // früh prüfen, bevor gerechnet wird
    let tmp = dir.join(format!(".trim-{}.part", uuid::Uuid::new_v4().simple()));
    let result = match plan.method {
        TrimMethod::Copy => runner.run(copy_args(src, &tmp, plan), None, Duration::from_secs(300)).await,
        TrimMethod::Reencode => {
            let mut last = Err(Error::validation(crate::msg!("clips.trimFailed", "Der Clip konnte nicht zugeschnitten werden.")));
            for codec in codecs.iter().copied().chain((!codecs.contains(&Codec::X264)).then_some(Codec::X264)) {
                last = runner.run(reencode_args(src, &tmp, plan, codec, kbps), progress.clone(), Duration::from_secs(3600)).await;
                if last.is_ok() {
                    break;
                }
                tracing::warn!("Zuschneiden mit {} fehlgeschlagen: {:?}", codec.ffmpeg_name(), last.as_ref().err());
            }
            last
        }
    };
    let finish = async {
        result?;
        if !tokio::fs::metadata(&tmp).await.is_ok_and(|m| m.len() > 0) {
            return Err(Error::Internal("ffmpeg: leere Ausgabe".into()));
        }
        // Namen erst jetzt vergeben (während des Schneidens könnte einer entstanden sein).
        let final_name = free_clip_name(dir, name)?;
        let target = dir.join(&final_name);
        if tokio::fs::symlink_metadata(&target).await.is_ok() {
            return Err(Error::validation(crate::msg!("clips.nameTaken", "Es gibt schon einen Clip mit diesem Namen.")));
        }
        tokio::fs::rename(&tmp, &target).await.map_err(|e| Error::io(&target, e))?;
        Ok(final_name)
    }
    .await;
    if finish.is_err() {
        let _ = tokio::fs::remove_file(&tmp).await;
    }
    finish
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Attrappe: merkt sich die Argumente und schreibt etwas in die Ausgabedatei (letztes Argument).
    #[derive(Default)]
    struct FakeRunner {
        calls: Mutex<Vec<Vec<String>>>,
        /// Diese Encoder „scheitern“.
        failing: Vec<&'static str>,
        output: Vec<u8>,
    }

    impl FfmpegRunner for FakeRunner {
        fn run(&self, args: Vec<String>, progress: Option<Progress>, _timeout: Duration) -> BoxFuture<'_, Result<()>> {
            Box::pin(async move {
                self.calls.lock().unwrap().push(args.clone());
                if self.failing.iter().any(|c| args.iter().any(|a| a == c)) {
                    return Err(Error::Internal("kaputt".into()));
                }
                if let Some(p) = progress {
                    p(1_000_000);
                }
                std::fs::write(args.last().unwrap(), if self.output.is_empty() { b"mp4".to_vec() } else { self.output.clone() }).unwrap();
                Ok(())
            })
        }
    }

    fn info() -> MediaInfo {
        MediaInfo {
            duration_ms: 30_000,
            width: 1920,
            height: 1080,
            has_video: true,
            has_audio: true,
            keyframes_ms: (0..15).map(|i| i * 2000).collect(),
        }
    }

    #[test]
    fn schnitt_auf_keyframe_wird_kopiert() {
        let p = plan_trim(&info(), 4000, 9000, TrimMode::Auto).unwrap();
        assert_eq!(p, TrimPlan { start_ms: 4000, end_ms: 9000, method: TrimMethod::Copy });
        // Knapp danach zählt noch als Keyframe (rutscht exakt darauf).
        let p = plan_trim(&info(), 4100, 9000, TrimMode::Auto).unwrap();
        assert_eq!((p.start_ms, p.method), (4000, TrimMethod::Copy));
        // Anfang des Clips geht immer ohne Neukodierung.
        assert_eq!(plan_trim(&info(), 0, 5000, TrimMode::Auto).unwrap().method, TrimMethod::Copy);
    }

    #[test]
    fn schnitt_zwischen_keyframes_wird_neu_kodiert_ausser_schnell() {
        let p = plan_trim(&info(), 5300, 12_000, TrimMode::Auto).unwrap();
        assert_eq!(p, TrimPlan { start_ms: 5300, end_ms: 12_000, method: TrimMethod::Reencode });
        let p = plan_trim(&info(), 5300, 12_000, TrimMode::Fast).unwrap();
        assert_eq!(p, TrimPlan { start_ms: 4000, end_ms: 12_000, method: TrimMethod::Copy });
        let p = plan_trim(&info(), 4000, 12_000, TrimMode::Exact).unwrap();
        assert_eq!(p.method, TrimMethod::Reencode);
        // Ohne Keyframe-Index: nur der Anfang ist sicher.
        let none = MediaInfo { keyframes_ms: Vec::new(), ..info() };
        assert_eq!(plan_trim(&none, 100, 5000, TrimMode::Auto).unwrap().method, TrimMethod::Reencode);
    }

    #[test]
    fn ungueltige_bereiche_werden_abgelehnt() {
        assert!(plan_trim(&info(), 5000, 5000, TrimMode::Auto).is_err());
        assert!(plan_trim(&info(), 6000, 5000, TrimMode::Auto).is_err());
        assert!(plan_trim(&info(), 1000, 1400, TrimMode::Auto).is_err(), "zu kurz");
        assert!(plan_trim(&info(), 40_000, 50_000, TrimMode::Auto).is_err(), "hinter dem Ende");
        // Ende hinter dem Clip wird auf das Ende begrenzt.
        assert_eq!(plan_trim(&info(), 28_000, 99_000, TrimMode::Auto).unwrap().end_ms, 30_000);
    }

    #[test]
    fn befehle_fuer_kopieren_und_neukodieren() {
        let plan = TrimPlan { start_ms: 4000, end_ms: 9500, method: TrimMethod::Copy };
        let args = copy_args(Path::new("in.mp4"), Path::new("out.part"), &plan);
        let joined = args.join(" ");
        assert!(joined.contains("-ss 4.000 -i in.mp4 -t 5.500"), "{joined}");
        assert!(joined.contains("-c copy") && joined.contains("+faststart") && joined.contains("-f mp4"), "{joined}");
        assert_eq!(args.last().unwrap(), "out.part");
        assert!(!joined.contains("-c:v"));

        let plan = TrimPlan { start_ms: 5300, end_ms: 12_000, method: TrimMethod::Reencode };
        let args = reencode_args(Path::new("in.mp4"), Path::new("out.part"), &plan, Codec::Nvenc, 12_000);
        let joined = args.join(" ");
        assert!(joined.contains("-ss 5.300 -i in.mp4 -t 6.700"), "{joined}");
        assert!(joined.contains("-c:v h264_nvenc") && joined.contains("-b:v 12000k") && joined.contains("-c:a aac"), "{joined}");
        assert!(joined.contains("-progress pipe:1"), "{joined}");
        let low = reencode_args(Path::new("a"), Path::new("b"), &plan, Codec::X264, 10);
        assert!(low.join(" ").contains("-b:v 2000k"), "untere Grenze");
        assert_eq!(source_kbps(15_000_000, 10_000), 12_000);
        assert_eq!(source_kbps(1, 0), 8000);
    }

    #[test]
    fn fortschritt_wird_gelesen() {
        assert_eq!(parse_progress_line("out_time_us=5000000"), Some(5_000_000));
        assert_eq!(parse_progress_line("out_time_us=N/A"), None);
        assert_eq!(parse_progress_line("frame=12"), None);
    }

    #[tokio::test]
    async fn zuschneiden_legt_einen_neuen_clip_an_und_laesst_das_original() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("Survival.mp4");
        std::fs::write(&src, b"original").unwrap();
        std::fs::write(dir.path().join("Survival (gekürzt).mp4"), b"schon da").unwrap();
        let runner = FakeRunner::default();
        let plan = plan_trim(&info(), 4000, 9000, TrimMode::Auto).unwrap();
        let name = trim(&runner, &src, &plan, "Survival (gekürzt)", &[Codec::Nvenc], 8000, None).await.unwrap();
        assert_eq!(name, "Survival (gekürzt) (2).mp4", "vorhandener Clip bleibt");
        assert_eq!(std::fs::read(&src).unwrap(), b"original");
        assert_eq!(std::fs::read(dir.path().join("Survival (gekürzt).mp4")).unwrap(), b"schon da");
        assert_eq!(std::fs::read(dir.path().join(&name)).unwrap(), b"mp4");
        let calls = runner.calls.lock().unwrap();
        assert_eq!(calls.len(), 1);
        assert!(calls[0].contains(&"copy".to_owned()));
        assert!(calls[0].last().unwrap().ends_with(".part"), "nie direkt in den Zielnamen");
        // Keine Reste.
        let parts = std::fs::read_dir(dir.path()).unwrap().filter(|e| e.as_ref().unwrap().file_name().to_string_lossy().ends_with(".part")).count();
        assert_eq!(parts, 0);
    }

    #[tokio::test]
    async fn neukodieren_faellt_auf_x264_zurueck_und_meldet_fortschritt() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("a.mp4");
        std::fs::write(&src, b"original").unwrap();
        let runner = FakeRunner { failing: vec!["h264_nvenc"], ..Default::default() };
        let seen = Arc::new(Mutex::new(Vec::new()));
        let sink = seen.clone();
        let progress: Progress = Arc::new(move |us| sink.lock().unwrap().push(us));
        let plan = plan_trim(&info(), 5300, 9000, TrimMode::Auto).unwrap();
        let name = trim(&runner, &src, &plan, "b", &[Codec::Nvenc], 8000, Some(progress)).await.unwrap();
        assert_eq!(name, "b.mp4");
        let calls = runner.calls.lock().unwrap();
        assert_eq!(calls.len(), 2);
        assert!(calls[1].contains(&"libx264".to_owned()));
        assert_eq!(*seen.lock().unwrap(), vec![1_000_000]);
    }

    #[tokio::test]
    async fn fehlschlag_hinterlaesst_nichts_und_ungueltige_namen_starten_nicht() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("a.mp4");
        std::fs::write(&src, b"original").unwrap();
        let runner = FakeRunner { failing: vec!["copy"], ..Default::default() };
        let plan = plan_trim(&info(), 0, 9000, TrimMode::Auto).unwrap();
        assert!(trim(&runner, &src, &plan, "neu", &[], 8000, None).await.is_err());
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1, "nur das Original");
        let runner = FakeRunner::default();
        assert!(trim(&runner, &src, &plan, "../böse", &[], 8000, None).await.is_err());
        assert!(trim(&runner, &src, &plan, "con", &[], 8000, None).await.is_err());
        assert!(runner.calls.lock().unwrap().is_empty(), "ungültiger Name → FFmpeg startet gar nicht");
    }

    #[test]
    fn bilder_der_leiste() {
        assert_eq!(strip_frames(0), 8);
        assert_eq!(strip_frames(3_000), 8);
        assert_eq!(strip_frames(30_000), 30);
        assert_eq!(strip_frames(30_001), 31);
        assert_eq!(strip_frames(600_000), 60);
        let args = strip_args(Path::new("c.mp4"), Path::new("o.raw"), 30_000, 30).join(" ");
        assert!(args.contains("fps=fps=30000/30000,scale=160:90"), "{args}");
        assert!(args.contains("-frames:v 30 -f rawvideo o.raw"), "{args}");
        assert!(!args.contains("nokey"), "kurze Clips: alle Bilder dekodieren");
        let long = strip_args(Path::new("c.mp4"), Path::new("o.raw"), 600_000, 60).join(" ");
        assert!(long.contains("-skip_frame nokey -i c.mp4"), "lange Clips: nur Keyframes");
    }

    #[test]
    fn raster_aus_rohen_bildern() {
        let frame = (STRIP_FRAME_W * STRIP_FRAME_H * 3) as usize;
        // 12 volle Bilder + ein halbes (wird ignoriert).
        let mut raw = vec![0u8; frame * 12 + frame / 2];
        raw[frame * 11] = 255; // erstes Pixel des 12. Bildes rot
        let (png, strip) = compose_strip(&raw, 60, 24_000).unwrap();
        assert_eq!((strip.frames, strip.cols, strip.rows), (12, 10, 2));
        assert_eq!(strip.interval_ms, 2000);
        let img = image::load_from_memory(&png).unwrap().to_rgb8();
        assert_eq!(img.dimensions(), (1600, 180));
        assert_eq!(img.get_pixel(160, 90).0, [255, 0, 0], "Bild 11 → Spalte 1, Zeile 1");
        assert!(compose_strip(&raw[..100], 60, 1000).is_none());
        assert_eq!(compose_strip(&raw, 4, 8000).unwrap().1.frames, 4, "höchstens so viele wie bestellt");
    }

    #[tokio::test]
    async fn leiste_wird_erzeugt_und_zwischengespeichert() {
        let dir = tempfile::tempdir().unwrap();
        let video = dir.path().join("c.mp4");
        std::fs::write(&video, media::tests::sample_mp4(10)).unwrap();
        let frame = (STRIP_FRAME_W * STRIP_FRAME_H * 3) as usize;
        let runner = FakeRunner { output: vec![7u8; frame * 10], ..Default::default() };
        let cache = dir.path().join("cache");
        let (png, made) = strip(&runner, &video, &cache, "k").await.unwrap();
        assert!(png.is_file());
        assert_eq!((made.frames, made.interval_ms, made.duration_ms), (10, 1000, 10_000));
        // Zweiter Aufruf: aus dem Cache, FFmpeg läuft nicht noch einmal.
        let again = strip(&runner, &video, &cache, "k").await.unwrap();
        assert_eq!(again.1, made);
        assert_eq!(runner.calls.lock().unwrap().len(), 1);
        assert!(!cache.join("k.raw.part").exists());
    }
}

/// Mit echtem FFmpeg (nur von Hand: `TRS_FFMPEG=<pfad zu ffmpeg.exe> cargo test -p trs-core echtes_ffmpeg -- --ignored`).
/// Erzeugt einen stummen Testclip, baut die Vorschau-Leiste und schneidet einmal kopierend und einmal neu kodierend.
#[cfg(test)]
mod real_ffmpeg {
    use super::*;

    fn ffmpeg() -> Option<PathBuf> {
        std::env::var_os("TRS_FFMPEG").map(PathBuf::from).filter(|p| p.is_file())
    }

    #[tokio::test]
    #[ignore = "braucht TRS_FFMPEG"]
    async fn echtes_ffmpeg_leiste_und_zuschneiden() {
        let Some(exe) = ffmpeg() else { return };
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("Test.mp4");
        // 12 s, 30 fps, Keyframe alle 2 s, stummer Ton – wie ein aufgenommener Clip.
        let args: Vec<String> = [
            "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=12",
            "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-shortest", "-c:v", "libx264", "-g", "60", "-keyint_min", "60",
            "-sc_threshold", "0", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart",
        ]
        .map(String::from)
        .into_iter()
        .chain([src.display().to_string()])
        .collect();
        recorder::run(&exe, args, Duration::from_secs(60)).await.unwrap();
        let info = media::probe(&src).unwrap();
        assert!((11_900..=12_100).contains(&info.duration_ms), "{info:?}");
        assert!(info.has_audio && info.has_video);
        assert_eq!((info.width, info.height), (640, 360));
        assert_eq!(&info.keyframes_ms[..3], &[0, 2000, 4000], "{info:?}");

        let runner = ProcessRunner { exe: exe.clone() };
        let (png, made) = strip(&runner, &src, &dir.path().join("cache"), "k").await.unwrap();
        assert_eq!(made.frames, 12);
        let img = image::open(&png).unwrap();
        assert_eq!((img.width(), img.height()), (1600, 180));

        // Kopieren (Start auf Keyframe).
        let plan = plan_trim(&info, 4000, 9000, TrimMode::Auto).unwrap();
        assert_eq!(plan.method, TrimMethod::Copy);
        let name = trim(&runner, &src, &plan, "Kopie", &[], 1000, None).await.unwrap();
        let copy = media::probe(&dir.path().join(&name)).unwrap();
        assert!((4_800..=5_300).contains(&copy.duration_ms), "{copy:?}");
        assert!(copy.has_audio);
        assert_eq!(copy.keyframes_ms.first(), Some(&0));

        // Neu kodieren (Start zwischen Keyframes), mit Fortschritt.
        let seen = Arc::new(Mutex::new(0u64));
        let sink = seen.clone();
        let progress: Progress = Arc::new(move |us| *sink.lock().unwrap() = us);
        let plan = plan_trim(&info, 5300, 9300, TrimMode::Auto).unwrap();
        assert_eq!(plan.method, TrimMethod::Reencode);
        let name = trim(&runner, &src, &plan, "Genau", &[Codec::X264], source_kbps(1_000_000, 12_000), Some(progress)).await.unwrap();
        let exact = media::probe(&dir.path().join(&name)).unwrap();
        assert!((3_900..=4_150).contains(&exact.duration_ms), "{exact:?}");
        assert!(*seen.lock().unwrap() > 0, "Fortschritt gemeldet");
        assert!(src.is_file(), "Original bleibt");
    }
}
