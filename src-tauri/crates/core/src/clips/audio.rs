//! Ton für die Aufnahme: Systemton (WASAPI-Loopback des Standard-Ausgabegeräts)
//! und optional das Mikrofon, gemischt zu 48 kHz Stereo f32 und über stdin an
//! FFmpeg geschickt.
//!
//! Zeitachse: Probe `i` gehört zur Wanduhrzeit `origin + i / 48000 s` – dieselbe
//! Null wie die Bildzeitstempel (`setpts` mit `RTCTIME`). Lücken (Loopback
//! liefert nichts, solange nichts zu hören ist) werden mit Stille gefüllt, damit
//! der Ton nicht gegen das Bild wandert. Gerätewechsel (Kopfhörer) übersteht
//! die Aufnahme: die Quelle öffnet einfach neu.

use std::collections::VecDeque;
use std::io::Write;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use super::encoder::SAMPLE_RATE;

/// So weit hinter "jetzt" schreibt der Mischer – Pakete haben so lange Zeit anzukommen.
const LATENCY_US: i64 = 120_000;
/// Kleine Zeitsprünge der Quelle werden geschluckt, größere mit Stille/Verwerfen ausgeglichen.
const JITTER_SAMPLES: i64 = 960;
/// Höchstens so viel Ton je Quelle puffern (Schutz, falls der Schreiber hängt).
const MAX_BUFFERED_SAMPLES: usize = SAMPLE_RATE as usize * 4;

/// Aktuelle Wanduhr in µs seit 1970 (gleiche Uhr wie FFmpegs `RTCTIME`).
pub fn wall_us() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| i64::try_from(d.as_micros()).unwrap_or(i64::MAX))
        .unwrap_or(0)
}

/// Tonspur einer Quelle auf dem Raster der Ausgabe (Stereo, verschachtelt).
#[derive(Debug, Default)]
pub struct Track {
    /// Probenindex (Ausgabe-Raster) des ersten gepufferten Frames.
    base: i64,
    samples: VecDeque<f32>,
}

impl Track {
    fn end(&self) -> i64 {
        self.base + (self.samples.len() / 2) as i64
    }

    /// Paket mit Stereo-Frames, deren erster Frame zum Probenindex `index` gehört.
    pub fn push(&mut self, index: i64, frames: &[f32], written: i64) {
        let mut frames = frames;
        let mut index = index;
        // Schon Geschriebenes nicht noch einmal.
        if index < written {
            let skip = ((written - index) as usize * 2).min(frames.len());
            frames = &frames[skip..];
            index = written;
        }
        if frames.is_empty() {
            return;
        }
        if self.samples.is_empty() {
            self.base = index.max(written);
        } else {
            let gap = index - self.end();
            if gap > JITTER_SAMPLES {
                // Lücke: mit Stille füllen.
                self.samples.extend(std::iter::repeat_n(0.0, gap as usize * 2));
            } else if gap < -JITTER_SAMPLES {
                // Überlappung: Doppeltes verwerfen.
                let skip = ((-gap) as usize * 2).min(frames.len());
                frames = &frames[skip..];
            }
        }
        self.samples.extend(frames.iter().copied());
        let over = self.samples.len().saturating_sub(MAX_BUFFERED_SAMPLES * 2);
        if over > 0 {
            self.samples.drain(..over);
            self.base += (over / 2) as i64;
        }
    }

    /// Mischt die Frames `[from, to)` in `out` (Stereo); fehlende = Stille.
    pub fn mix_into(&mut self, from: i64, to: i64, out: &mut [f32]) {
        if self.base < from {
            let drop = (((from - self.base) as usize) * 2).min(self.samples.len());
            self.samples.drain(..drop);
            self.base = from;
        }
        if self.samples.is_empty() {
            self.base = self.base.max(to);
            return;
        }
        let start = (self.base - from).max(0) as usize;
        let wanted = (to - from).max(0) as usize;
        if start >= wanted {
            return;
        }
        let available = self.samples.len() / 2;
        let n = (wanted - start).min(available);
        for (i, v) in self.samples.drain(..n * 2).enumerate() {
            out[start * 2 + i] += v;
        }
        self.base += n as i64;
    }
}

/// Probenindex zur Wanduhrzeit.
pub fn index_at(origin_us: i64, time_us: i64) -> i64 {
    ((time_us - origin_us) as i128 * i128::from(SAMPLE_RATE) / 1_000_000) as i64
}

/// Laufende Tonaufnahme; beim Drop wird alles beendet.
pub struct AudioPipe {
    stop: Arc<AtomicBool>,
    threads: Vec<std::thread::JoinHandle<()>>,
}

impl AudioPipe {
    /// Startet die Quellen und den Mischer, der nach `out` schreibt (FFmpegs stdin).
    pub fn start(system: bool, microphone: bool, origin_us: i64, out: impl Write + Send + 'static) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let mut tracks = Vec::new();
        let mut threads = Vec::new();
        for (enabled, kind) in [(system, Source::Loopback), (microphone, Source::Microphone)] {
            if !enabled {
                continue;
            }
            let track = Arc::new(Mutex::new(Track::default()));
            let written = Arc::new(std::sync::atomic::AtomicI64::new(0));
            tracks.push((track.clone(), written.clone()));
            let stop = stop.clone();
            threads.push(
                std::thread::Builder::new()
                    .name(format!("trs-clips-{kind:?}"))
                    .spawn(move || capture_loop(kind, origin_us, &track, &written, &stop))
                    .expect("Audio-Thread"),
            );
        }
        let stop_mixer = stop.clone();
        threads.push(
            std::thread::Builder::new()
                .name("trs-clips-mix".into())
                .spawn(move || mix_loop(origin_us, &tracks, out, &stop_mixer))
                .expect("Mischer-Thread"),
        );
        Self { stop, threads }
    }

    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        for t in self.threads.drain(..) {
            let _ = t.join();
        }
    }
}

impl Drop for AudioPipe {
    fn drop(&mut self) {
        self.stop();
    }
}

type Tracks = Vec<(Arc<Mutex<Track>>, Arc<std::sync::atomic::AtomicI64>)>;

fn mix_loop(origin_us: i64, tracks: &Tracks, mut out: impl Write, stop: &AtomicBool) {
    // Probe 0 gehört zu `origin_us`; alles bis "jetzt − Latenz" wird geschrieben.
    let mut written: i64 = 0;
    let mut buf: Vec<f32> = Vec::new();
    let mut bytes: Vec<u8> = Vec::new();
    while !stop.load(Ordering::Relaxed) {
        let target = index_at(origin_us, wall_us() - LATENCY_US);
        if target > written {
            // Höchstens 1 s auf einmal (nach einem Hänger nicht riesig puffern).
            let to = target.min(written + i64::from(SAMPLE_RATE));
            let frames = (to - written) as usize;
            buf.clear();
            buf.resize(frames * 2, 0.0);
            for (track, done) in tracks {
                let mut track = track.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                track.mix_into(written, to, &mut buf);
                done.store(to, Ordering::Relaxed);
            }
            bytes.clear();
            bytes.reserve(buf.len() * 4);
            for v in &buf {
                bytes.extend_from_slice(&v.clamp(-1.0, 1.0).to_le_bytes());
            }
            if out.write_all(&bytes).is_err() {
                // FFmpeg ist weg – die Aufnahme endet ohnehin.
                return;
            }
            written = to;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let _ = out.flush();
}

#[derive(Debug, Clone, Copy)]
enum Source {
    Loopback,
    Microphone,
}

#[cfg(windows)]
fn capture_loop(
    kind: Source,
    origin_us: i64,
    track: &Mutex<Track>,
    written: &std::sync::atomic::AtomicI64,
    stop: &AtomicBool,
) {
    // COM je Thread (MTA); Fehler beim Öffnen → kurz warten und neu versuchen
    // (kein Gerät, Gerät gewechselt, Treiber neu gestartet).
    let _ = wasapi::initialize_mta().ok();
    let mut logged = false;
    while !stop.load(Ordering::Relaxed) {
        if let Err(e) = capture_once(kind, origin_us, track, written, stop)
            && !logged
        {
            tracing::info!("Ton-Aufnahme ({kind:?}) nicht verfügbar: {e}");
            logged = true;
        }
        for _ in 0..10 {
            if stop.load(Ordering::Relaxed) {
                break;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
    }
    wasapi::deinitialize();
}

#[cfg(windows)]
fn capture_once(
    kind: Source,
    origin_us: i64,
    track: &Mutex<Track>,
    written: &std::sync::atomic::AtomicI64,
    stop: &AtomicBool,
) -> Result<(), Box<dyn std::error::Error>> {
    use wasapi::{DeviceEnumerator, Direction, SampleType, StreamMode, WaveFormat};

    let enumerator = DeviceEnumerator::new()?;
    let device = match kind {
        Source::Loopback => enumerator.get_default_device(&Direction::Render)?,
        Source::Microphone => enumerator.get_default_device(&Direction::Capture)?,
    };
    let mut client = device.get_iaudioclient()?;
    let format = WaveFormat::new(32, 32, &SampleType::Float, SAMPLE_RATE as usize, 2, None);
    // Loopback = Render-Gerät im Capture-Modus. Automatische Umwandlung in unser Format.
    let mode = StreamMode::PollingShared { autoconvert: true, buffer_duration_hns: 2_000_000 };
    client.initialize_client(&format, &Direction::Capture, &mode)?;
    let capture = client.get_audiocaptureclient()?;
    client.start_stream()?;
    let clock = QpcClock::new();
    let mut data: VecDeque<u8> = VecDeque::new();
    let mut floats: Vec<f32> = Vec::new();
    while !stop.load(Ordering::Relaxed) {
        std::thread::sleep(Duration::from_millis(5));
        while capture.get_next_packet_size()?.unwrap_or(0) > 0 {
            data.clear();
            let info = capture.read_from_device_to_deque(&mut data)?;
            floats.clear();
            if info.flags.silent {
                floats.resize(data.len() / 4, 0.0);
            } else {
                let (a, b) = data.as_slices();
                let mut all = Vec::with_capacity(a.len() + b.len());
                all.extend_from_slice(a);
                all.extend_from_slice(b);
                floats.extend(all.as_chunks::<4>().0.iter().map(|c| f32::from_le_bytes(*c)));
            }
            // Zeit des ersten Frames: QPC-Position des Pakets (100 ns) → Wanduhr.
            let time_us = if info.timestamp > 0 && !info.flags.timestamp_error {
                clock.to_wall_us(info.timestamp)
            } else {
                wall_us() - (floats.len() / 2) as i64 * 1_000_000 / i64::from(SAMPLE_RATE)
            };
            let index = index_at(origin_us, time_us);
            let done = written.load(Ordering::Relaxed);
            track.lock().unwrap_or_else(std::sync::PoisonError::into_inner).push(index, &floats, done);
        }
    }
    let _ = client.stop_stream();
    Ok(())
}

#[cfg(not(windows))]
fn capture_loop(_: Source, _: i64, _: &Mutex<Track>, _: &std::sync::atomic::AtomicI64, _: &AtomicBool) {}

/// Umrechnung QPC (100-ns-Einheiten wie von WASAPI) → Wanduhr (µs).
#[cfg(windows)]
struct QpcClock {
    offset_us: i64,
}

#[cfg(windows)]
impl QpcClock {
    fn new() -> Self {
        let qpc_us = qpc_100ns() / 10;
        Self { offset_us: wall_us() - qpc_us }
    }

    fn to_wall_us(&self, qpc_100ns: u64) -> i64 {
        i64::try_from(qpc_100ns / 10).unwrap_or(0) + self.offset_us
    }
}

#[cfg(windows)]
fn qpc_100ns() -> i64 {
    use windows::Win32::System::Performance::{QueryPerformanceCounter, QueryPerformanceFrequency};
    let (mut count, mut freq) = (0i64, 0i64);
    // SAFETY: beide Zeiger zeigen auf gültige lokale Variablen.
    unsafe {
        let _ = QueryPerformanceCounter(&raw mut count);
        let _ = QueryPerformanceFrequency(&raw mut freq);
    }
    if freq <= 0 {
        return 0;
    }
    (i128::from(count) * 10_000_000 / i128::from(freq)) as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn luecken_werden_mit_stille_gefuellt() {
        let mut t = Track::default();
        t.push(0, &[0.5, 0.5, 0.5, 0.5], 0); // 2 Frames bei 0
        t.push(2002, &[0.25, 0.25], 0); // Lücke > Jitter → Stille dazwischen
        let mut out = vec![0.0; 2003 * 2];
        t.mix_into(0, 2003, &mut out);
        assert_eq!(&out[0..4], &[0.5, 0.5, 0.5, 0.5]);
        assert!(out[4..4004].iter().all(|v| *v == 0.0));
        assert_eq!(&out[4004..4006], &[0.25, 0.25]);
    }

    #[test]
    fn kleines_zittern_bleibt_lueckenlos() {
        let mut t = Track::default();
        t.push(100, &[1.0; 20], 0); // 10 Frames: 100..110
        t.push(105, &[2.0; 20], 0); // 5 Frames zu früh (< Jitter) → direkt anhängen
        let mut out = vec![0.0; 40];
        t.mix_into(100, 120, &mut out);
        assert!(out.iter().take(20).all(|v| *v == 1.0));
        assert!(out.iter().skip(20).all(|v| *v == 2.0));
    }

    #[test]
    fn geschriebenes_wird_nicht_doppelt_geliefert() {
        let mut t = Track::default();
        t.push(0, &[1.0; 200], 50); // 100 Frames ab 0, aber 50 sind schon geschrieben
        let mut out = vec![0.0; 100];
        t.mix_into(50, 100, &mut out);
        assert!(out.iter().all(|v| *v == 1.0));
        // Späte Daten ohne Puffer → Stille, Zeiger läuft mit.
        let mut out = vec![0.0; 20];
        t.mix_into(100, 110, &mut out);
        assert!(out.iter().all(|v| *v == 0.0));
    }

    #[test]
    fn zwei_quellen_werden_gemischt() {
        let mut a = Track::default();
        let mut b = Track::default();
        a.push(0, &[0.25; 8], 0);
        b.push(2, &[0.5; 4], 0);
        let mut out = vec![0.0; 8];
        a.mix_into(0, 4, &mut out);
        b.mix_into(0, 4, &mut out);
        assert_eq!(out, vec![0.25, 0.25, 0.25, 0.25, 0.75, 0.75, 0.75, 0.75]);
    }

    #[test]
    fn index_und_wanduhr() {
        assert_eq!(index_at(1_000_000, 1_000_000), 0);
        assert_eq!(index_at(1_000_000, 2_000_000), 48_000);
        assert_eq!(index_at(1_000_000, 1_500_000), 24_000);
        assert!(wall_us() > 1_700_000_000_000_000);
    }
}
