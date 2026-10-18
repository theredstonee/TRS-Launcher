//! Quellen für eigene Umhänge (Zuschneide-Dialog im Launcher).
//!
//! Der Nutzer wählt die Dateien im Dateidialog (der Pfad verlässt Rust nie). Hier
//! wird das Format an den Magic Bytes erkannt (nicht an der Endung), die Maße
//! werden aus dem Dateikopf geprüft, BEVOR irgendwer dekodiert, und GIFs/APNGs
//! werden direkt im Kern in Einzelbilder zerlegt – mit der `image`-Crate
//! (gif-/png-Feature, gepflegt vom image-rs-Projekt, schon Abhängigkeit des
//! Kerns, Speichergrenzen über `Limits`). Videos zerlegt `cape_video` mit FFmpeg.
//! Das Webview bekommt nur geprüfte Data-URLs; der fertige Streifen wird vor dem
//! Hochladen noch einmal von `png::validate_upload` geprüft.

use std::io::Cursor;
use std::path::PathBuf;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use image::{AnimationDecoder, ImageDecoder};
use serde::{Deserialize, Serialize};

use super::{png, validate};
use crate::{Error, Result};

/// Höchstens so viele Dateien auf einmal (Frames als Einzelbilder + Studio-JSON).
pub const MAX_SOURCE_FILES: usize = 64;
/// Größte einzelne Datei.
pub const MAX_SOURCE_BYTES: u64 = 20 * 1024 * 1024;
/// Alle gewählten Dateien zusammen.
pub const MAX_TOTAL_BYTES: u64 = 64 * 1024 * 1024;
/// Größte Kante / Pixelzahl eines Bildes, das ans Webview geht.
const MAX_SIDE: u32 = 8192;
const MAX_IMAGE_PIXELS: u64 = 4096 * 4096;
/// GIF/APNG: größte Leinwand, größte Kante der gelieferten Frames, Frame- und Pixel-Budget.
const MAX_GIF_SIDE: u32 = 2048;
pub(crate) const ANIMATION_OUTPUT_SIDE: u32 = 1024;
const MAX_GIF_FRAMES: usize = 256;
const MAX_GIF_WORK_PIXELS: u64 = 256 * 1024 * 1024;
/// Studio-Exporte beschreiben nur Frames + Faktor – mehr als ein paar KB sind es nie.
const MAX_JSON_BYTES: usize = 64 * 1024;

/// Woher eine zerlegte Animation stammt (nur für die Anzeige).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum AnimationFormat {
    Gif,
    Apng,
    Video,
}

/// Eine gewählte Datei, fertig fürs Webview.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "kind", rename_all = "lowercase", rename_all_fields = "camelCase")]
pub enum CapeSource {
    /// PNG/JPEG/WebP – das Webview dekodiert die (geprüfte) Datei selbst.
    Image { name: String, data_url: String, width: u32, height: u32 },
    /// GIF, APNG oder Video, im Kern zerlegt: höchstens 32 gleichmäßig verteilte
    /// Frames als PNG; `delays_ms` = wie lange jeder gelieferte Frame steht.
    Animation {
        name: String,
        format: AnimationFormat,
        width: u32,
        height: u32,
        frames: Vec<String>,
        delays_ms: Vec<u32>,
        duration_ms: u32,
        source_frames: u32,
    },
    /// JSON eines TRS-Studio-Exports (Frames + Faktor des zugehörigen PNG-Streifens).
    Studio { name: String, frames: u32, scale: u32 },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Png,
    Apng,
    Jpeg,
    Webp,
    Gif,
    Json,
}

/// Hat das PNG einen `acTL`-Chunk vor den Bilddaten (= APNG)? Liest nur die Chunk-Köpfe.
fn is_apng(bytes: &[u8]) -> bool {
    let mut off = 8usize;
    for _ in 0..64 {
        let Some(head) = bytes.get(off..off + 8) else { return false };
        let len = u32::from_be_bytes([head[0], head[1], head[2], head[3]]) as usize;
        match &head[4..8] {
            b"acTL" => return true,
            b"IDAT" | b"IEND" => return false,
            _ => {}
        }
        off = match off.checked_add(12 + len) {
            Some(next) => next,
            None => return false,
        };
    }
    false
}

/// Format an den ersten Bytes erkennen. JSON nur mit passender Endung und `{` am Anfang.
fn sniff(bytes: &[u8], json_ext: bool) -> Option<Kind> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some(if is_apng(bytes) { Kind::Apng } else { Kind::Png })
    } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some(Kind::Jpeg)
    } else if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some(Kind::Webp)
    } else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        Some(Kind::Gif)
    } else if json_ext && bytes.trim_ascii_start().starts_with(b"{") {
        Some(Kind::Json)
    } else {
        None
    }
}

fn unsupported(name: &str) -> Error {
    Error::validation(crate::msg!(
        "trsCapeImport.unsupported",
        "„{name}“ ist kein unterstütztes Bild (PNG, APNG, JPEG, WebP, GIF oder TRS-Studio-JSON).",
        name = name
    ))
}

fn too_big(name: &str) -> Error {
    Error::validation(crate::msg!(
        "trsCapeImport.imageTooLarge",
        "„{name}“ ist zu groß. Bilder dürfen höchstens 4096×4096 Pixel haben, GIF/APNG höchstens 2048×2048; \
         fertige Umhang-Streifen (64·k × 32·k je Frame, bis 1024×512 und 32 Frames) bis 1024×16384.",
        name = name
    ))
}

/// Darf ein Einzelbild (PNG/JPEG/WebP) ins Webview? Beliebige Bilder bis 4096×4096 (Speicher),
/// fertige Umhang-Streifen im Upload-Format (64k×32k·n bzw. 22k×17k·n, k ≤ 16, n ≤ 32) auch
/// länger – z. B. 1024×16384 – denn genau so nimmt sie der Server an.
fn image_size_ok(width: u32, height: u32) -> bool {
    if png::upload_layout(width, height).is_some() {
        return true;
    }
    width <= MAX_SIDE && height <= MAX_SIDE && u64::from(width) * u64::from(height) <= MAX_IMAGE_PIXELS
}

fn broken(name: &str) -> Error {
    Error::validation(crate::msg!("trsCapeImport.broken", "„{name}“ konnte nicht gelesen werden.", name = name))
}

/// Liest die gewählten Dateien (Reihenfolge bleibt; das Webview sortiert Frames selbst).
pub async fn read_sources(paths: Vec<PathBuf>) -> Result<Vec<CapeSource>> {
    if paths.len() > MAX_SOURCE_FILES {
        return Err(Error::validation(crate::msg!(
            "trsCapeImport.tooManyFiles",
            "Bitte höchstens {max} Dateien auf einmal wählen.",
            max = MAX_SOURCE_FILES
        )));
    }
    let mut total = 0u64;
    let mut files = Vec::with_capacity(paths.len());
    for path in paths {
        let name = display_name(&path);
        let meta = tokio::fs::metadata(&path).await.map_err(|e| Error::io(&path, e))?;
        if !meta.is_file() {
            return Err(unsupported(&name));
        }
        if meta.len() > MAX_SOURCE_BYTES {
            return Err(Error::validation(crate::msg!(
                "trsCapeImport.fileTooLarge",
                "„{name}“ ist zu groß (höchstens 20 MB je Datei).",
                name = name
            )));
        }
        total += meta.len();
        if total > MAX_TOTAL_BYTES {
            return Err(Error::validation(crate::msg!(
                "trsCapeImport.totalTooLarge",
                "Zusammen sind die Dateien zu groß (höchstens 64 MB)."
            )));
        }
        let json_ext = path.extension().is_some_and(|e| e.eq_ignore_ascii_case("json"));
        let bytes = tokio::fs::read(&path).await.map_err(|e| Error::io(&path, e))?;
        files.push((name, json_ext, bytes));
    }
    tokio::task::spawn_blocking(move || {
        files.into_iter().map(|(name, json_ext, bytes)| source_from_bytes(name, json_ext, &bytes)).collect()
    })
    .await
    .map_err(|e| Error::Internal(e.to_string()))?
}

fn display_name(path: &std::path::Path) -> String {
    let stem = path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let clean = validate::text(stem.trim(), 64);
    if clean.is_empty() { "?".into() } else { clean }
}

/// Eine Datei prüfen und fürs Webview aufbereiten.
pub fn source_from_bytes(name: String, json_ext: bool, bytes: &[u8]) -> Result<CapeSource> {
    match sniff(bytes, json_ext).ok_or_else(|| unsupported(&name))? {
        Kind::Json => studio_meta(name, bytes),
        Kind::Gif => decode_animation(name, bytes, AnimationFormat::Gif),
        Kind::Apng => decode_animation(name, bytes, AnimationFormat::Apng),
        kind @ (Kind::Png | Kind::Jpeg | Kind::Webp) => {
            let (format, mime) = match kind {
                Kind::Png => (image::ImageFormat::Png, "image/png"),
                Kind::Jpeg => (image::ImageFormat::Jpeg, "image/jpeg"),
                _ => (image::ImageFormat::WebP, "image/webp"),
            };
            // Nur den Kopf lesen – dekodiert wird erst im Webview, und nur, was hier durchkommt.
            let (width, height) = image::ImageReader::with_format(Cursor::new(bytes), format)
                .into_dimensions()
                .map_err(|_| broken(&name))?;
            if width == 0 || height == 0 {
                return Err(broken(&name));
            }
            if !image_size_ok(width, height) {
                return Err(too_big(&name));
            }
            let data_url = format!("data:{mime};base64,{}", STANDARD.encode(bytes));
            Ok(CapeSource::Image { name, data_url, width, height })
        }
    }
}

#[derive(Deserialize)]
struct StudioJson {
    #[serde(default)]
    target: Option<String>,
    frames: u32,
    scale: u32,
}

fn studio_meta(name: String, bytes: &[u8]) -> Result<CapeSource> {
    let invalid = |name: &str| {
        Error::validation(crate::msg!(
            "trsCapeImport.studioInvalid",
            "„{name}“ ist kein Umhang-Export aus TRS Studio.",
            name = name
        ))
    };
    if bytes.len() > MAX_JSON_BYTES {
        return Err(invalid(&name));
    }
    let meta: StudioJson = serde_json::from_slice(bytes).map_err(|_| invalid(&name))?;
    let ok = meta.target.as_deref().is_none_or(|t| t == "cape")
        && (1..=64).contains(&meta.frames)
        && (1..=16).contains(&meta.scale);
    if !ok {
        return Err(invalid(&name));
    }
    Ok(CapeSource::Studio { name, frames: meta.frames, scale: meta.scale })
}

/// Fertiger Streifen aus dem Webview (Base64) → Bytes. Die Länge wird vor dem
/// Dekodieren geprüft; den Inhalt prüft danach `png::validate_upload`.
pub fn decode_upload(encoded: &str) -> Result<Vec<u8>> {
    if encoded.len() > png::MAX_UPLOAD_BYTES.div_ceil(3) * 4 {
        return Err(Error::validation(crate::msg!("trsPng.fileTooLarge", "Die Datei ist zu groß (höchstens 20 MB).")));
    }
    STANDARD
        .decode(encoded.trim())
        .map_err(|_| Error::validation(crate::msg!("trsPng.invalidPng", "Die Datei ist kein gültiges PNG-Bild.")))
}

/// Gleichmäßig verteilte Frame-Nummern: höchstens `keep` aus `total`.
pub fn sample_frames(total: usize, keep: usize) -> Vec<usize> {
    if total <= keep {
        return (0..total).collect();
    }
    (0..keep).map(|i| i * total / keep).collect()
}

fn animation_limits() -> image::Limits {
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(MAX_GIF_SIDE);
    limits.max_image_height = Some(MAX_GIF_SIDE);
    limits.max_alloc = Some(128 * 1024 * 1024);
    limits
}

/// Maße + Frames einer Animation (GIF bzw. APNG), mit festen Speichergrenzen.
fn animation_frames(bytes: &[u8], format: AnimationFormat) -> image::ImageResult<((u32, u32), image::Frames<'_>)> {
    if format == AnimationFormat::Apng {
        let decoder = image::codecs::png::PngDecoder::with_limits(Cursor::new(bytes), animation_limits())?;
        let size = decoder.dimensions();
        return Ok((size, decoder.apng()?.into_frames()));
    }
    let mut decoder = image::codecs::gif::GifDecoder::new(Cursor::new(bytes))?;
    decoder.set_limits(animation_limits())?;
    let size = decoder.dimensions();
    Ok((size, decoder.into_frames()))
}

/// GIF/APNG zerlegen: 1. Durchgang zählt Frames und Dauer, 2. behält höchstens 32
/// gleichmäßig verteilte (fertig zusammengesetzte) Frames als PNG.
fn decode_animation(name: String, bytes: &[u8], format: AnimationFormat) -> Result<CapeSource> {
    let too_long = |name: &str| {
        Error::validation(crate::msg!(
            "trsCapeImport.gifTooLong",
            "„{name}“ hat zu viele Frames (höchstens {max}).",
            name = name,
            max = MAX_GIF_FRAMES
        ))
    };
    let ((width, height), first) = animation_frames(bytes, format).map_err(|e| match e {
        image::ImageError::Limits(_) => too_big(&name),
        _ => broken(&name),
    })?;
    if width == 0 || height == 0 {
        return Err(broken(&name));
    }
    let per_frame = u64::from(width) * u64::from(height);
    let mut delays = Vec::new();
    for frame in first {
        let frame = frame.map_err(|_| broken(&name))?;
        if delays.len() >= MAX_GIF_FRAMES || per_frame * (delays.len() as u64 + 1) > MAX_GIF_WORK_PIXELS {
            return Err(too_long(&name));
        }
        delays.push(delay_ms(&frame));
    }
    let total = delays.len();
    if total == 0 {
        return Err(broken(&name));
    }
    let keep = sample_frames(total, png::MAX_UPLOAD_FRAMES as usize);
    let (out_w, out_h) = fit(width, height, ANIMATION_OUTPUT_SIDE);
    let mut frames = Vec::with_capacity(keep.len());
    let (_, decoder) = animation_frames(bytes, format).map_err(|_| broken(&name))?;
    for (index, frame) in decoder.enumerate() {
        if frames.len() == keep.len() {
            break;
        }
        let frame = frame.map_err(|_| broken(&name))?;
        if !keep.contains(&index) {
            continue;
        }
        let buffer = frame.into_buffer();
        frames.push(png_data_url(buffer, out_w, out_h).ok_or_else(|| broken(&name))?);
    }
    if frames.len() != keep.len() {
        return Err(broken(&name));
    }
    let duration_ms = delays.iter().fold(0u32, |sum, d| sum.saturating_add(*d));
    Ok(CapeSource::Animation {
        name,
        format,
        width: out_w,
        height: out_h,
        frames,
        delays_ms: kept_delays(&delays, &keep),
        duration_ms,
        source_frames: total as u32,
    })
}

/// Frame (RGBA) auf `width`×`height` bringen und als PNG-Data-URL ausgeben.
pub(crate) fn png_data_url(mut buffer: image::RgbaImage, width: u32, height: u32) -> Option<String> {
    if buffer.dimensions() != (width, height) {
        buffer = image::imageops::resize(&buffer, width, height, image::imageops::FilterType::Triangle);
    }
    let mut out = Cursor::new(Vec::new());
    image::DynamicImage::ImageRgba8(buffer).write_to(&mut out, image::ImageFormat::Png).ok()?;
    Some(format!("data:image/png;base64,{}", STANDARD.encode(out.into_inner())))
}

/// Standzeit je behaltenem Frame: seine Dauer plus die der ausgelassenen bis zum nächsten.
fn kept_delays(delays: &[u32], keep: &[usize]) -> Vec<u32> {
    keep.iter()
        .enumerate()
        .map(|(i, &start)| {
            let end = keep.get(i + 1).copied().unwrap_or(delays.len());
            delays[start..end].iter().fold(0u32, |sum, d| sum.saturating_add(*d))
        })
        .collect()
}

/// Frame-Dauer wie Browser: unter 20 ms gilt als 100 ms.
fn delay_ms(frame: &image::Frame) -> u32 {
    let (num, den) = frame.delay().numer_denom_ms();
    let ms = num.checked_div(den).unwrap_or(0);
    if ms < 20 { 100 } else { ms.min(60_000) }
}

/// Maße so verkleinern, dass die längere Kante höchstens `max` ist.
pub(crate) fn fit(width: u32, height: u32, max: u32) -> (u32, u32) {
    if width <= max && height <= max {
        return (width, height);
    }
    if width >= height {
        (max, ((u64::from(height) * u64::from(max)) / u64::from(width)).max(1) as u32)
    } else {
        (((u64::from(width) * u64::from(max)) / u64::from(height)).max(1) as u32, max)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::codecs::gif::GifEncoder;
    use image::{Delay, Frame, Rgba, RgbaImage};

    fn gif(frames: usize, width: u32, height: u32, delay_ms: u32) -> Vec<u8> {
        let mut out = Vec::new();
        {
            let mut enc = GifEncoder::new(&mut out);
            let list = (0..frames).map(|i| {
                let img = RgbaImage::from_pixel(width, height, Rgba([(i * 10) as u8, 0, 0, 255]));
                Frame::from_parts(img, 0, 0, Delay::from_numer_denom_ms(delay_ms, 1))
            });
            enc.encode_frames(list).unwrap();
        }
        out
    }

    fn png_bytes(width: u32, height: u32) -> Vec<u8> {
        let mut out = Cursor::new(Vec::new());
        image::DynamicImage::ImageRgba8(RgbaImage::new(width, height))
            .write_to(&mut out, image::ImageFormat::Png)
            .unwrap();
        out.into_inner()
    }

    #[test]
    fn samples_evenly() {
        assert_eq!(sample_frames(3, 16), vec![0, 1, 2]);
        assert_eq!(sample_frames(32, 16), (0..16).map(|i| i * 2).collect::<Vec<_>>());
        assert_eq!(sample_frames(20, 16).len(), 16);
        assert!(sample_frames(0, 16).is_empty());
    }

    #[test]
    fn sniffs_by_magic_bytes() {
        assert_eq!(sniff(&png_bytes(2, 2), false), Some(Kind::Png));
        assert_eq!(sniff(b"GIF89a....", false), Some(Kind::Gif));
        assert_eq!(sniff(&[0xFF, 0xD8, 0xFF, 0xE0], false), Some(Kind::Jpeg));
        assert_eq!(sniff(b"RIFF\0\0\0\0WEBPVP8 ", false), Some(Kind::Webp));
        assert_eq!(sniff(b" {\"frames\":1}", true), Some(Kind::Json));
        assert_eq!(sniff(b"{\"frames\":1}", false), None, "JSON nur mit Endung");
        assert_eq!(sniff(b"<svg/>", false), None);
    }

    #[test]
    fn images_are_checked_before_decoding() {
        let src = source_from_bytes("a".into(), false, &png_bytes(64, 32)).unwrap();
        match src {
            CapeSource::Image { width, height, data_url, .. } => {
                assert_eq!((width, height), (64, 32));
                assert!(data_url.starts_with("data:image/png;base64,"));
            }
            other => panic!("{other:?}"),
        }
        // Nur der Kopf zählt: ein riesiges PNG wird abgewiesen, ohne es zu dekodieren.
        let bomb = super::png::tests::png_with(20_000, 20_000, &[]);
        let err = source_from_bytes("bomb".into(), false, &bomb).unwrap_err();
        assert_eq!(err.message_code(), "trsCapeImport.imageTooLarge");
        let err = source_from_bytes("x".into(), false, b"<svg onload=alert(1)>").unwrap_err();
        assert_eq!(err.message_code(), "trsCapeImport.unsupported");
    }

    #[test]
    fn finished_cape_strips_pass_the_size_check() {
        // Fertige HD-Streifen im Server-Format: 1024 breit, 512 je Frame, bis 32 Frames.
        for (w, h) in [(1024, 512 * 24), (1024, 512 * 32), (512, 256 * 32), (64, 32 * 32), (352, 272 * 32), (1024, 512)] {
            assert!(image_size_ok(w, h), "{w}×{h} ist ein gültiger Streifen");
        }
        // Beliebige Bilder bleiben begrenzt; Streifen nur in genau passenden Maßen.
        for (w, h) in [(1024, 512 * 33), (1000, 12288), (4097, 4097), (8193, 10), (1088, 544 * 30)] {
            assert!(!image_size_ok(w, h), "{w}×{h} wird abgewiesen");
        }
        assert!(image_size_ok(4096, 4096) && image_size_ok(1600, 1600));
        // Nur der Kopf zählt: der Streifen kommt durch, ohne dekodiert zu werden.
        let strip = super::png::tests::png_with(1024, 512 * 24, &[]);
        let src = source_from_bytes("streifen".into(), false, &strip).unwrap();
        assert!(matches!(src, CapeSource::Image { width: 1024, height: 12288, .. }));
    }

    /// Echtes APNG: `frames` einfarbige Frames (`delay_ms` je Frame), von Hand aus Chunks gebaut.
    fn apng(frames: u32, width: u32, height: u32, delay_ms: u16) -> Vec<u8> {
        use std::io::Write;
        let chunk = super::png::tests::chunk;
        let mut out = b"\x89PNG\r\n\x1a\n".to_vec();
        let mut ihdr = width.to_be_bytes().to_vec();
        ihdr.extend_from_slice(&height.to_be_bytes());
        ihdr.extend_from_slice(&[8, 6, 0, 0, 0]);
        out.extend(chunk(b"IHDR", &ihdr));
        let mut actl = frames.to_be_bytes().to_vec();
        actl.extend_from_slice(&0u32.to_be_bytes());
        out.extend(chunk(b"acTL", &actl));
        let mut seq = 0u32;
        for f in 0..frames {
            let mut fctl = seq.to_be_bytes().to_vec();
            seq += 1;
            for v in [width, height, 0, 0] {
                fctl.extend_from_slice(&v.to_be_bytes());
            }
            fctl.extend_from_slice(&delay_ms.to_be_bytes());
            fctl.extend_from_slice(&1000u16.to_be_bytes());
            fctl.extend_from_slice(&[0, 0]);
            out.extend(chunk(b"fcTL", &fctl));
            // Zeile = Filterbyte 0 + Pixel; alle Zeilen gleich.
            let pixel = [(f * 20) as u8, 10, 10, 255];
            let mut row = vec![0u8];
            row.extend(pixel.iter().cycle().take(width as usize * 4));
            let raw = row.repeat(height as usize);
            let mut z = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::fast());
            z.write_all(&raw).unwrap();
            let data = z.finish().unwrap();
            if f == 0 {
                out.extend(chunk(b"IDAT", &data));
            } else {
                let mut fdat = seq.to_be_bytes().to_vec();
                seq += 1;
                fdat.extend_from_slice(&data);
                out.extend(chunk(b"fdAT", &fdat));
            }
        }
        out.extend(chunk(b"IEND", &[]));
        out
    }

    #[test]
    fn gif_frames_are_sampled() {
        let src = source_from_bytes("anim".into(), false, &gif(40, 20, 32, 50)).unwrap();
        let CapeSource::Animation { frames, delays_ms, duration_ms, source_frames, width, height, format, .. } = src else {
            panic!()
        };
        assert_eq!(format, AnimationFormat::Gif);
        assert_eq!(source_frames, 40);
        assert_eq!(frames.len(), 32, "bis zu 32 Frames bleiben");
        assert_eq!(delays_ms.len(), 32);
        assert_eq!(delays_ms.iter().sum::<u32>(), 40 * 50, "Standzeiten decken die ganze Dauer");
        assert_eq!(duration_ms, 40 * 50);
        assert_eq!((width, height), (20, 32));
        assert!(frames.iter().all(|f| f.starts_with("data:image/png;base64,")));

        let exact = source_from_bytes("genau".into(), false, &gif(32, 8, 8, 50)).unwrap();
        let CapeSource::Animation { frames, source_frames, .. } = exact else { panic!() };
        assert_eq!((frames.len(), source_frames), (32, 32), "32 Frames werden nicht ausgedünnt");

        let short = source_from_bytes("kurz".into(), false, &gif(3, 8, 8, 0)).unwrap();
        let CapeSource::Animation { frames, duration_ms, delays_ms, .. } = short else { panic!() };
        assert_eq!(frames.len(), 3);
        assert_eq!(delays_ms, vec![100, 100, 100]);
        assert_eq!(duration_ms, 300, "0 ms gilt wie im Browser als 100 ms");
    }

    #[test]
    fn apng_is_split_like_a_gif() {
        let bytes = apng(3, 6, 4, 80);
        assert_eq!(sniff(&bytes, false), Some(Kind::Apng));
        let src = source_from_bytes("ani".into(), false, &bytes).unwrap();
        let CapeSource::Animation { frames, format, delays_ms, width, height, source_frames, .. } = src else {
            panic!()
        };
        assert_eq!(format, AnimationFormat::Apng);
        assert_eq!((width, height, source_frames), (6, 4, 3));
        assert_eq!(frames.len(), 3);
        assert_eq!(delays_ms, vec![80, 80, 80]);

        let long = source_from_bytes("lang".into(), false, &apng(40, 4, 4, 50)).unwrap();
        let CapeSource::Animation { frames, source_frames, .. } = long else { panic!() };
        assert_eq!((frames.len(), source_frames), (32, 40), "über 32 Frames wird gleichmäßig ausgedünnt");
        // Ein normales PNG bleibt ein Bild.
        assert_eq!(sniff(&png_bytes(4, 4), false), Some(Kind::Png));
    }

    #[test]
    fn kept_delays_cover_skipped_frames() {
        assert_eq!(kept_delays(&[10, 20, 30, 40], &[0, 2]), vec![30, 70]);
        assert_eq!(kept_delays(&[10, 20], &[0, 1]), vec![10, 20]);
        assert!(!is_apng(b"\x89PNG\r\n\x1a\n\0\0"), "abgeschnittener Kopf");
    }

    #[test]
    fn big_gifs_are_scaled_down() {
        let src = source_from_bytes("gross".into(), false, &gif(1, 1500, 300, 100)).unwrap();
        let CapeSource::Animation { width, height, .. } = src else { panic!() };
        assert_eq!((width, height), (1024, 204));
        assert_eq!(fit(100, 3000, 1000), (33, 1000));
    }

    #[test]
    fn studio_json() {
        let ok = source_from_bytes(
            "ender".into(),
            true,
            br#"{"name":"ender","target":"cape","frames":4,"scale":8,"quality":"hdpixel"}"#,
        )
        .unwrap();
        assert_eq!(ok, CapeSource::Studio { name: "ender".into(), frames: 4, scale: 8 });
        for bad in [
            &br#"{"target":"banner","frames":1,"scale":4}"#[..],
            br#"{"frames":0,"scale":8}"#,
            br#"{"frames":1,"scale":99}"#,
            b"{nope",
        ] {
            let err = source_from_bytes("x".into(), true, bad).unwrap_err();
            assert_eq!(err.message_code(), "trsCapeImport.studioInvalid");
        }
    }

    #[test]
    fn serializes_for_the_webview() {
        let json = serde_json::to_value(CapeSource::Studio { name: "a".into(), frames: 2, scale: 8 }).unwrap();
        assert_eq!(json, serde_json::json!({ "kind": "studio", "name": "a", "frames": 2, "scale": 8 }));
        let json = serde_json::to_value(CapeSource::Animation {
            name: "g".into(),
            format: AnimationFormat::Apng,
            width: 1,
            height: 1,
            frames: vec![],
            delays_ms: vec![5],
            duration_ms: 5,
            source_frames: 1,
        })
        .unwrap();
        assert_eq!(json["kind"], "animation");
        assert_eq!(json["format"], "apng");
        assert_eq!(json["durationMs"], 5);
        assert_eq!(json["delaysMs"], serde_json::json!([5]));
        assert_eq!(json["sourceFrames"], 1);
    }

    #[test]
    fn upload_base64_is_bounded() {
        assert_eq!(decode_upload(&STANDARD.encode(b"abc")).unwrap(), b"abc");
        assert_eq!(decode_upload("!!").unwrap_err().message_code(), "trsPng.invalidPng");
        let huge = "A".repeat(png::MAX_UPLOAD_BYTES.div_ceil(3) * 4 + 4);
        assert_eq!(decode_upload(&huge).unwrap_err().message_code(), "trsPng.fileTooLarge");
    }

    #[tokio::test]
    async fn reads_files_with_limits() {
        let dir = tempfile::tempdir().unwrap();
        let a = dir.path().join("frame 2.png");
        std::fs::write(&a, png_bytes(64, 32)).unwrap();
        let sources = read_sources(vec![a.clone()]).await.unwrap();
        assert!(matches!(&sources[0], CapeSource::Image { name, .. } if name == "frame 2"));
        let many = vec![a; MAX_SOURCE_FILES + 1];
        let err = read_sources(many).await.unwrap_err();
        assert_eq!(err.message_code(), "trsCapeImport.tooManyFiles");
    }
}
