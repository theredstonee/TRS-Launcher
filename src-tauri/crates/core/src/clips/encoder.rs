//! FFmpeg-Befehlszeilen für Aufnahme, Clip-Export und Vorschaubilder.
//!
//! Bild: `gfxcapture` (Windows Graphics Capture) nimmt nur das Spielfenster
//! auf und liefert D3D11-Bilder direkt an den Hardware-Encoder. Die Zeitstempel
//! werden auf dieselbe Wanduhr wie der Ton gelegt (`setpts` mit `RTCTIME`), so
//! bleiben Bild und Ton synchron, egal wie lange die Aufnahme zum Start braucht.
//! Ausgabe: 2-Sekunden-MPEG-TS-Segmente mit Keyframes auf den Segmentgrenzen.

use std::path::Path;

use serde::Serialize;

use super::settings::{ClipEncoder, ClipQuality, ClipResolution, ClipSettings};

/// Länge eines Segments im Ringpuffer (Sekunden) – zugleich der Keyframe-Abstand.
pub const SEGMENT_SECONDS: u32 = 2;
pub const SAMPLE_RATE: u32 = 48_000;
pub const CHANNELS: u32 = 2;

/// Tatsächlich verwendeter Encoder.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Codec {
    Nvenc,
    Amf,
    Qsv,
    /// Media Foundation (Hardware-Encoder über Windows, z. B. Intel).
    Mf,
    X264,
}

impl Codec {
    pub fn ffmpeg_name(self) -> &'static str {
        match self {
            Self::Nvenc => "h264_nvenc",
            Self::Amf => "h264_amf",
            Self::Qsv => "h264_qsv",
            Self::Mf => "h264_mf",
            Self::X264 => "libx264",
        }
    }

    /// Reihenfolge zum Ausprobieren. Eine feste Wahl fällt notfalls auf x264 zurück.
    pub fn candidates(setting: ClipEncoder) -> Vec<Codec> {
        match setting {
            ClipEncoder::Auto => vec![Self::Nvenc, Self::Amf, Self::Qsv, Self::Mf, Self::X264],
            ClipEncoder::Nvenc => vec![Self::Nvenc, Self::X264],
            ClipEncoder::Amf => vec![Self::Amf, Self::X264],
            ClipEncoder::Qsv => vec![Self::Qsv, Self::Mf, Self::X264],
            ClipEncoder::X264 => vec![Self::X264],
        }
    }
}

/// Ausgabegröße: gerade Pixelzahlen, höchstens die gewählte Höhe (Seitenverhältnis bleibt).
pub fn output_size(width: u32, height: u32, resolution: ClipResolution) -> (u32, u32) {
    let (w, h) = (width.max(16), height.max(16));
    let (w, h) = match resolution.max_height() {
        Some(max) if h > max => {
            let scaled = (u64::from(w) * u64::from(max) + u64::from(h) / 2) / u64::from(h);
            (u32::try_from(scaled).unwrap_or(w), max)
        }
        _ => (w, h),
    };
    (w & !1, h & !1)
}

/// Ziel-Bitrate (kbit/s), skaliert mit Pixeln und Bildrate.
pub fn bitrate_kbps(quality: ClipQuality, width: u32, height: u32, fps: u32) -> u32 {
    let pixels = f64::from(width) * f64::from(height);
    let factor = (pixels / (1920.0 * 1080.0)) * (f64::from(fps) / 60.0);
    let kbps = f64::from(quality.base_kbps()) * factor.clamp(0.15, 4.0);
    (kbps.round() as u32).max(1500)
}

/// Qualitätsstufe für CQ/CRF (kleiner = besser).
fn cq(quality: ClipQuality) -> u32 {
    match quality {
        ClipQuality::Low => 28,
        ClipQuality::Medium => 23,
        ClipQuality::High => 19,
    }
}

/// Alles, was eine Aufnahme braucht.
#[derive(Debug, Clone)]
pub struct Plan {
    pub hwnd: u64,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub quality: ClipQuality,
    pub codec: Codec,
    /// Ton über stdin (f32le, 48 kHz, Stereo).
    pub audio: bool,
    /// Wanduhr (µs seit 1970), auf die Bild- und Tonzeit 0 gelegt werden.
    pub origin_us: i64,
    /// Nummer des ersten Segments (nach einem Neustart geht es weiter).
    pub first_segment: u64,
}

impl Plan {
    pub fn new(settings: &ClipSettings, hwnd: u64, client: (u32, u32), codec: Codec, origin_us: i64) -> Self {
        let (width, height) = output_size(client.0, client.1, settings.resolution);
        Self {
            hwnd,
            width,
            height,
            fps: settings.fps,
            quality: settings.quality,
            codec,
            audio: settings.system_audio || settings.microphone,
            origin_us,
            first_segment: 0,
        }
    }

    /// Filterkette: Fenster aufnehmen → Zeit auf die Wanduhr → feste Bildrate → ggf. Umwandlung.
    pub fn filter(&self) -> String {
        let mut f = format!(
            "gfxcapture=hwnd={}:max_framerate={}:width={}:height={}:resize_mode=scale_aspect:display_border=0:capture_border=0,\
             setpts='if(eq(N,0),st(0,RTCTIME-{}),0);PTS+ld(0)/(TB*1000000)',fps={}",
            self.hwnd, self.fps, self.width, self.height, self.origin_us, self.fps
        );
        match self.codec {
            Codec::X264 => f.push_str(",hwdownload,format=bgra,format=yuv420p"),
            Codec::Qsv => f.push_str(",hwmap=derive_device=qsv,format=qsv"),
            Codec::Nvenc | Codec::Amf | Codec::Mf => {}
        }
        f.push_str("[v]");
        f
    }

    pub fn encoder_args(&self) -> Vec<String> {
        let k = bitrate_kbps(self.quality, self.width, self.height, self.fps);
        let rate = [
            "-b:v".to_owned(),
            format!("{k}k"),
            "-maxrate".to_owned(),
            format!("{}k", k * 3 / 2),
            "-bufsize".to_owned(),
            format!("{}k", k * 2),
        ];
        let q = cq(self.quality).to_string();
        let mut args: Vec<String> = vec!["-c:v".into(), self.codec.ffmpeg_name().into()];
        let extra: &[&str] = match self.codec {
            Codec::Nvenc => &["-preset", "p4", "-tune", "ll", "-rc", "vbr", "-forced-idr", "1"],
            Codec::Amf => &["-usage", "transcoding", "-quality", "balanced", "-rc", "vbr_peak"],
            Codec::Qsv => &["-preset", "veryfast"],
            Codec::Mf => &["-hw_encoding", "1", "-scenario", "live_streaming"],
            Codec::X264 => &["-preset", "veryfast", "-pix_fmt", "yuv420p"],
        };
        args.extend(extra.iter().map(|s| (*s).to_owned()));
        match self.codec {
            Codec::Nvenc => args.extend(["-cq".to_owned(), q]),
            Codec::X264 => args.extend(["-crf".to_owned(), q]),
            _ => {}
        }
        args.extend(rate);
        let gop = (self.fps * SEGMENT_SECONDS).to_string();
        args.extend([
            "-g".to_owned(),
            gop,
            "-force_key_frames".to_owned(),
            format!("expr:gte(t,n_forced*{SEGMENT_SECONDS})"),
        ]);
        args
    }

    /// Vollständige Argumente für die laufende Aufnahme in Segmente.
    pub fn record_args(&self, segment_dir: &Path) -> Vec<String> {
        let mut args: Vec<String> = ["-hide_banner", "-loglevel", "warning", "-nostats", "-y"].map(String::from).to_vec();
        if self.audio {
            args.extend(
                [
                    "-nostdin",
                    "-thread_queue_size",
                    "1024",
                    "-f",
                    "f32le",
                    "-ar",
                    "48000",
                    "-ac",
                    "2",
                    "-i",
                    "pipe:0",
                ]
                .map(String::from),
            );
        }
        args.extend(["-filter_complex".to_owned(), self.filter(), "-map".to_owned(), "[v]".to_owned()]);
        args.extend(self.encoder_args());
        if self.audio {
            args.extend(["-map", "0:a", "-c:a", "aac", "-b:a", "160k"].map(String::from));
        }
        args.extend(
            [
                "-max_muxing_queue_size",
                "2048",
                "-f",
                "segment",
                "-segment_time",
                "2",
                "-segment_format",
                "mpegts",
            ]
            .map(String::from),
        );
        args.extend(["-segment_start_number".to_owned(), self.first_segment.to_string()]);
        args.push(segment_dir.join("seg%06d.ts").display().to_string());
        args
    }
}

/// Kurzer Probelauf: Kann dieser Encoder hier überhaupt kodieren?
pub fn probe_args(codec: Codec) -> Vec<String> {
    let mut args: Vec<String> =
        ["-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "color=c=black:s=320x240:r=30"]
            .map(String::from)
            .to_vec();
    args.extend(["-frames:v", "5", "-pix_fmt", "yuv420p", "-c:v", codec.ffmpeg_name(), "-f", "null", "-"].map(String::from));
    args
}

/// Segmente → MP4 ohne Neukodierung, Metadaten vorn (schnelles Abspielen).
pub fn concat_args(list: &Path, output: &Path) -> Vec<String> {
    [
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-y",
        "-f",
        "concat",
        "-safe",
        "0",
        "-i",
    ]
    .map(String::from)
    .into_iter()
    .chain([list.display().to_string()])
    .chain(["-map", "0", "-c", "copy", "-movflags", "+faststart", "-f", "mp4"].map(String::from))
    .chain([output.display().to_string()])
    .collect()
}

/// Inhalt der Concat-Liste (nur eigene Segmentnamen, relativ zur Liste).
pub fn concat_list(segments: &[u64]) -> String {
    let mut s = String::from("ffconcat version 1.0\n");
    for n in segments {
        s.push_str(&format!("file '{}'\n", segment_name(*n)));
    }
    s
}

pub fn segment_name(n: u64) -> String {
    format!("seg{n:06}.ts")
}

/// `seg000123.ts` → 123.
pub fn parse_segment_name(name: &str) -> Option<u64> {
    let digits = name.strip_prefix("seg")?.strip_suffix(".ts")?;
    (digits.len() >= 6 && digits.bytes().all(|b| b.is_ascii_digit())).then(|| digits.parse().ok()).flatten()
}

/// Vorschaubild (JPEG, 480 px breit) aus der ersten Sekunde.
pub fn thumbnail_args(video: &Path, output: &Path, seek_seconds: f64) -> Vec<String> {
    ["-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-ss"]
        .map(String::from)
        .into_iter()
        .chain([format!("{seek_seconds:.2}"), "-i".to_owned(), video.display().to_string()])
        .chain(["-frames:v", "1", "-vf", "scale=480:-2", "-q:v", "4", "-f", "image2"].map(String::from))
        .chain([output.display().to_string()])
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ausgabegroesse_ist_gerade_und_begrenzt() {
        assert_eq!(output_size(2560, 1440, ClipResolution::P1080), (1920, 1080));
        assert_eq!(output_size(2560, 1440, ClipResolution::P720), (1280, 720));
        assert_eq!(output_size(854, 480, ClipResolution::P1080), (854, 480));
        assert_eq!(output_size(855, 481, ClipResolution::Native), (854, 480));
        assert_eq!(output_size(3440, 1440, ClipResolution::P1080), (2580, 1080));
    }

    #[test]
    fn bitrate_skaliert_mit_groesse_und_bildrate() {
        let full = bitrate_kbps(ClipQuality::Medium, 1920, 1080, 60);
        assert_eq!(full, 15_000);
        assert!(bitrate_kbps(ClipQuality::Medium, 1280, 720, 30) < full / 3);
        assert!(bitrate_kbps(ClipQuality::High, 1920, 1080, 60) > full);
        assert!(bitrate_kbps(ClipQuality::Low, 64, 64, 30) >= 1500);
    }

    // Fensteraufnahme (gdigrab) gibt es nur unter Windows.
    #[cfg(windows)]
    #[test]
    fn aufnahme_nimmt_nur_das_fenster_auf() {
        let settings = ClipSettings { enabled: true, ..Default::default() };
        let plan = Plan::new(&settings, 0x1234, (1920, 1080), Codec::Nvenc, 1_700_000_000_000_000);
        let filter = plan.filter();
        assert!(filter.starts_with("gfxcapture=hwnd=4660:"), "{filter}");
        assert!(!filter.contains("monitor"), "nie den ganzen Bildschirm");
        assert!(filter.contains("RTCTIME-1700000000000000"));
        assert!(filter.contains("fps=60"));
        assert!(filter.ends_with("[v]"));
        let args = plan.record_args(Path::new("C:\\buf"));
        assert!(args.windows(2).any(|w| w == ["-i", "pipe:0"]), "Ton über stdin");
        assert!(args.contains(&"h264_nvenc".to_owned()));
        assert!(args.contains(&"expr:gte(t,n_forced*2)".to_owned()));
        assert_eq!(args.last().unwrap(), "C:\\buf\\seg%06d.ts");

        let silent = ClipSettings { system_audio: false, microphone: false, ..settings.clone() };
        let plan = Plan::new(&silent, 1, (1280, 720), Codec::X264, 0);
        let args = plan.record_args(Path::new("buf"));
        assert!(!args.contains(&"pipe:0".to_owned()));
        assert!(!args.contains(&"-nostdin".to_owned()), "ohne Ton bleibt stdin für 'q' frei");
        assert!(plan.filter().contains("hwdownload,format=bgra,format=yuv420p"));
    }

    #[test]
    fn encoder_reihenfolge() {
        assert_eq!(Codec::candidates(ClipEncoder::Auto)[0], Codec::Nvenc);
        assert_eq!(*Codec::candidates(ClipEncoder::Auto).last().unwrap(), Codec::X264);
        assert_eq!(Codec::candidates(ClipEncoder::Amf), vec![Codec::Amf, Codec::X264]);
    }

    #[test]
    fn segmentnamen() {
        assert_eq!(segment_name(42), "seg000042.ts");
        assert_eq!(parse_segment_name("seg000042.ts"), Some(42));
        assert_eq!(parse_segment_name("seg1234567.ts"), Some(1_234_567));
        assert_eq!(parse_segment_name("seg42.ts"), None);
        assert_eq!(parse_segment_name("x.ts"), None);
        let list = concat_list(&[1, 2]);
        assert_eq!(list, "ffconcat version 1.0\nfile 'seg000001.ts'\nfile 'seg000002.ts'\n");
    }
}
