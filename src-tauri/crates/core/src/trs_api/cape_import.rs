//! Quellen für eigene Umhänge (Zuschneide-Dialog im Launcher).
//!
//! Der Nutzer wählt die Dateien im Dateidialog (der Pfad verlässt Rust nie). Hier
//! wird das Format an den Magic Bytes erkannt (nicht an der Endung), die Maße
//! werden aus dem Dateikopf geprüft, BEVOR irgendwer dekodiert, und GIFs werden
//! direkt im Kern in Einzelbilder zerlegt – mit der `image`-Crate (gif-Feature,
//! gepflegt vom image-rs-Projekt, schon Abhängigkeit des Kerns, Speichergrenzen
//! über `Limits`). Das Webview bekommt nur geprüfte Data-URLs; der fertige
//! Streifen wird vor dem Hochladen noch einmal von `png::validate_upload` geprüft.

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
/// GIFs: größte Leinwand, größte Kante der gelieferten Frames, Frame- und Pixel-Budget.
const MAX_GIF_SIDE: u32 = 2048;
const GIF_OUTPUT_SIDE: u32 = 1024;
const MAX_GIF_FRAMES: usize = 256;
const MAX_GIF_WORK_PIXELS: u64 = 256 * 1024 * 1024;
/// Studio-Exporte beschreiben nur Frames + Faktor – mehr als ein paar KB sind es nie.
const MAX_JSON_BYTES: usize = 64 * 1024;

/// Eine gewählte Datei, fertig fürs Webview.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "kind", rename_all = "lowercase", rename_all_fields = "camelCase")]
pub enum CapeSource {
    /// PNG/JPEG/WebP – das Webview dekodiert die (geprüfte) Datei selbst.
    Image { name: String, data_url: String, width: u32, height: u32 },
    /// GIF, im Kern zerlegt: höchstens 16 gleichmäßig verteilte Frames als PNG.
    Gif { name: String, width: u32, height: u32, frames: Vec<String>, duration_ms: u32, source_frames: u32 },
    /// JSON eines TRS-Studio-Exports (Frames + Faktor des zugehörigen PNG-Streifens).
    Studio { name: String, frames: u32, scale: u32 },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Png,
    Jpeg,
    Webp,
    Gif,
    Json,
}

/// Format an den ersten Bytes erkennen. JSON nur mit passender Endung und `{` am Anfang.
fn sniff(bytes: &[u8], json_ext: bool) -> Option<Kind> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some(Kind::Png)
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
        "„{name}“ ist kein unterstütztes Bild (PNG, JPEG, WebP, GIF oder TRS-Studio-JSON).",
        name = name
    ))
}

fn too_big(name: &str) -> Error {
    Error::validation(crate::msg!(
        "trsCapeImport.imageTooLarge",
        "„{name}“ ist zu groß (höchstens 4096×4096 Pixel, als GIF 2048×2048).",
        name = name
    ))
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
        Kind::Gif => decode_gif(name, bytes),
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
            if width > MAX_SIDE || height > MAX_SIDE || u64::from(width) * u64::from(height) > MAX_IMAGE_PIXELS {
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
        return Err(Error::validation(crate::msg!("trsPng.fileTooLarge", "Die Datei ist zu groß (höchstens 5 MB).")));
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

fn gif_decoder(bytes: &[u8]) -> image::ImageResult<image::codecs::gif::GifDecoder<Cursor<&[u8]>>> {
    let mut decoder = image::codecs::gif::GifDecoder::new(Cursor::new(bytes))?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(MAX_GIF_SIDE);
    limits.max_image_height = Some(MAX_GIF_SIDE);
    limits.max_alloc = Some(128 * 1024 * 1024);
    decoder.set_limits(limits)?;
    Ok(decoder)
}

/// GIF zerlegen: 1. Durchgang zählt Frames und Dauer, 2. behält höchstens 16
/// gleichmäßig verteilte (fertig zusammengesetzte) Frames als PNG.
fn decode_gif(name: String, bytes: &[u8]) -> Result<CapeSource> {
    let too_long = |name: &str| {
        Error::validation(crate::msg!(
            "trsCapeImport.gifTooLong",
            "„{name}“ hat zu viele Frames (höchstens {max}).",
            name = name,
            max = MAX_GIF_FRAMES
        ))
    };
    let first = gif_decoder(bytes).map_err(|e| match e {
        image::ImageError::Limits(_) => too_big(&name),
        _ => broken(&name),
    })?;
    let (width, height) = first.dimensions();
    if width == 0 || height == 0 {
        return Err(broken(&name));
    }
    let per_frame = u64::from(width) * u64::from(height);
    let mut total = 0usize;
    let mut duration_ms = 0u32;
    for frame in first.into_frames() {
        let frame = frame.map_err(|_| broken(&name))?;
        total += 1;
        if total > MAX_GIF_FRAMES || per_frame * total as u64 > MAX_GIF_WORK_PIXELS {
            return Err(too_long(&name));
        }
        duration_ms = duration_ms.saturating_add(delay_ms(&frame));
    }
    if total == 0 {
        return Err(broken(&name));
    }
    let keep = sample_frames(total, png::MAX_UPLOAD_FRAMES as usize);
    let (out_w, out_h) = fit(width, height, GIF_OUTPUT_SIDE);
    let mut frames = Vec::with_capacity(keep.len());
    let decoder = gif_decoder(bytes).map_err(|_| broken(&name))?;
    for (index, frame) in decoder.into_frames().enumerate() {
        if frames.len() == keep.len() {
            break;
        }
        let frame = frame.map_err(|_| broken(&name))?;
        if !keep.contains(&index) {
            continue;
        }
        let mut buffer = frame.into_buffer();
        if (out_w, out_h) != (width, height) {
            buffer = image::imageops::resize(&buffer, out_w, out_h, image::imageops::FilterType::Triangle);
        }
        let mut out = Cursor::new(Vec::new());
        image::DynamicImage::ImageRgba8(buffer)
            .write_to(&mut out, image::ImageFormat::Png)
            .map_err(|_| broken(&name))?;
        frames.push(format!("data:image/png;base64,{}", STANDARD.encode(out.into_inner())));
    }
    if frames.len() != keep.len() {
        return Err(broken(&name));
    }
    Ok(CapeSource::Gif { name, width: out_w, height: out_h, frames, duration_ms, source_frames: total as u32 })
}

/// Frame-Dauer wie Browser: unter 20 ms gilt als 100 ms.
fn delay_ms(frame: &image::Frame) -> u32 {
    let (num, den) = frame.delay().numer_denom_ms();
    let ms = num.checked_div(den).unwrap_or(0);
    if ms < 20 { 100 } else { ms.min(60_000) }
}

/// Maße so verkleinern, dass die längere Kante höchstens `max` ist.
fn fit(width: u32, height: u32, max: u32) -> (u32, u32) {
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
    fn gif_frames_are_sampled() {
        let src = source_from_bytes("anim".into(), false, &gif(40, 20, 32, 50)).unwrap();
        let CapeSource::Gif { frames, duration_ms, source_frames, width, height, .. } = src else { panic!() };
        assert_eq!(source_frames, 40);
        assert_eq!(frames.len(), 16);
        assert_eq!(duration_ms, 40 * 50);
        assert_eq!((width, height), (20, 32));
        assert!(frames.iter().all(|f| f.starts_with("data:image/png;base64,")));

        let short = source_from_bytes("kurz".into(), false, &gif(3, 8, 8, 0)).unwrap();
        let CapeSource::Gif { frames, duration_ms, .. } = short else { panic!() };
        assert_eq!(frames.len(), 3);
        assert_eq!(duration_ms, 300, "0 ms gilt wie im Browser als 100 ms");
    }

    #[test]
    fn big_gifs_are_scaled_down() {
        let src = source_from_bytes("gross".into(), false, &gif(1, 1500, 300, 100)).unwrap();
        let CapeSource::Gif { width, height, .. } = src else { panic!() };
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
        let json = serde_json::to_value(CapeSource::Gif {
            name: "g".into(),
            width: 1,
            height: 1,
            frames: vec![],
            duration_ms: 5,
            source_frames: 1,
        })
        .unwrap();
        assert_eq!(json["durationMs"], 5);
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
