//! PNG-Prüfung vor dem Senden (Umhang-Upload) und für geladene Texturen.
//! Gleiche Regeln wie der Server (`png.ts`): Signatur, Chunk-CRCs, Whitelist,
//! keine APNG, nichts nach `IEND`, erlaubte Maße. Animierte Umhänge sind ein
//! senkrechter Streifen aus bis zu 32 Frames (wie die mitgelieferten).

use crate::{Error, Result};

/// Größte Upload-Datei (20 MiB, wie der Server).
pub const MAX_UPLOAD_BYTES: usize = 20 * 1024 * 1024;
/// Größter Faktor gegenüber 64×32 (→ 1024×512 je Frame).
pub const MAX_SCALE: u32 = 16;
/// Höchstens so viele Frames im Streifen.
pub const MAX_UPLOAD_FRAMES: u32 = 32;
/// Bildtempo, das der Launcher für eigene Umhänge anbietet (ms je Frame).
pub const MIN_FRAME_TIME_MS: u32 = 50;
pub const MAX_FRAME_TIME_MS: u32 = 1000;
/// Obergrenze der Pixel im ganzen Streifen (1024 × 512·32) – vor dem Dekodieren geprüft.
const MAX_PIXELS: u64 = 1024 * 512 * 32;
const SIGNATURE: &[u8; 8] = b"\x89PNG\r\n\x1a\n";

const ALLOWED: &[&[u8; 4]] = &[
    b"IHDR", b"PLTE", b"IDAT", b"IEND", b"tRNS", b"gAMA", b"cHRM", b"sRGB", b"iCCP", b"sBIT", b"pHYs", b"bKGD",
    b"tIME", b"tEXt", b"zTXt", b"iTXt", b"hIST", b"sPLT", b"eXIf",
];
const ANIMATION: &[&[u8; 4]] = &[b"acTL", b"fcTL", b"fdAT"];

/// Breite × Höhe aus dem IHDR (nur Kopf geprüft).
pub fn size(bytes: &[u8]) -> Option<(u32, u32)> {
    if bytes.len() < 24 || &bytes[..8] != SIGNATURE || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let w = u32::from_be_bytes(bytes[16..20].try_into().ok()?);
    let h = u32::from_be_bytes(bytes[20..24].try_into().ok()?);
    Some((w, h))
}

/// Aufbau eines Upload-Streifens.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UploadLayout {
    /// Faktor k (1…16).
    pub scale: u32,
    /// Höhe eines Frames in der Datei (32k bzw. 17k im reinen Umhang-Format).
    pub frame_height: u32,
    pub frames: u32,
    /// `true` = reines Umhang-Format 22k×17k (der Server legt es in 64k×32k).
    pub cape_only: bool,
}

/// Erlaubte Maße für Uploads: Breite 64k (Frame 64k×32k) oder 22k (Frame 22k×17k),
/// k = 1…16, 1…32 Frames untereinander.
pub fn upload_layout(width: u32, height: u32) -> Option<UploadLayout> {
    (1..=MAX_SCALE).find_map(|k| {
        let (frame_height, cape_only) = if width == 64 * k {
            (32 * k, false)
        } else if width == 22 * k {
            (17 * k, true)
        } else {
            return None;
        };
        let frames = height / frame_height;
        (height.is_multiple_of(frame_height) && (1..=MAX_UPLOAD_FRAMES).contains(&frames))
            .then_some(UploadLayout { scale: k, frame_height, frames, cape_only })
    })
}

fn invalid_size() -> Error {
    Error::validation(crate::msg!(
        "trsPng.invalidSize",
        "Der Umhang muss 64×32 Pixel groß sein (oder ein Vielfaches bis 1024×512) bzw. im Umhang-Format 22×17 \
         (bis 352×272); animiert höchstens 32 Frames untereinander."
    ))
}

/// Prüft eine Upload-Datei vollständig (Aufbau, Maße, Frames, Bildtempo und die
/// Pixel selbst). Fehlertexte sind für den Nutzer.
pub fn validate_upload(bytes: &[u8], frames: u32, frame_time_ms: Option<u32>) -> Result<UploadLayout> {
    if bytes.len() > MAX_UPLOAD_BYTES {
        return Err(Error::validation(crate::msg!("trsPng.fileTooLarge", "Die Datei ist zu groß (höchstens 20 MB).")));
    }
    let invalid = invalid_png;
    let (w, h) = structure(bytes)?;
    // Maße VOR dem Dekodieren prüfen (Schutz vor Dekompressionsbomben).
    if u64::from(w) * u64::from(h) > MAX_PIXELS {
        return Err(invalid_size());
    }
    let layout = upload_layout(w, h).ok_or_else(invalid_size)?;
    if layout.frames != frames {
        return Err(invalid_size());
    }
    if frames > 1 && !frame_time_ms.is_some_and(|t| (MIN_FRAME_TIME_MS..=MAX_FRAME_TIME_MS).contains(&t)) {
        return Err(Error::validation(crate::msg!(
            "trsPng.frameTime",
            "Das Bildtempo muss zwischen 50 und 1000 ms je Frame liegen."
        )));
    }
    let pixels = decode_rgba(bytes, w, h).ok_or_else(invalid)?;
    if !cape_visible(&pixels, w, layout) {
        return Err(Error::validation(crate::msg!("trsPng.emptyCape", "Der Umhang ist komplett durchsichtig.")));
    }
    Ok(layout)
}

/// Fan-Symbol (Creator-Programm, §35.1): quadratische Frames `s` × `s` (s = 16…64)
/// senkrecht untereinander, höchstens 8 Frames, höchstens 256 KiB.
pub const SYMBOL_MAX_BYTES: usize = 256 * 1024;
pub const SYMBOL_MIN_SIZE: u32 = 16;
pub const SYMBOL_MAX_SIZE: u32 = 64;
pub const SYMBOL_MAX_FRAMES: u32 = 8;

/// Aufbau eines Fan-Symbols: Kantenlänge eines Frames und Zahl der Frames.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SymbolLayout {
    pub size: u32,
    pub frames: u32,
}

/// Erlaubte Maße eines Fan-Symbol-Streifens (`max_frames` je nach Stufe: 1 oder 8).
pub fn symbol_layout(width: u32, height: u32, max_frames: u32) -> Option<SymbolLayout> {
    if !(SYMBOL_MIN_SIZE..=SYMBOL_MAX_SIZE).contains(&width) || height == 0 || !height.is_multiple_of(width) {
        return None;
    }
    let frames = height / width;
    (1..=max_frames.min(SYMBOL_MAX_FRAMES)).contains(&frames).then_some(SymbolLayout { size: width, frames })
}

/// Prüft ein Fan-Symbol vollständig (Aufbau, Maße, Frames, Pixel) wie der Server.
pub fn validate_symbol(bytes: &[u8], max_frames: u32) -> Result<SymbolLayout> {
    if bytes.len() > SYMBOL_MAX_BYTES {
        return Err(Error::validation(crate::msg!("trsPng.symbolTooLarge", "Das Fan-Symbol ist zu groß (höchstens 256 KB).")));
    }
    let (w, h) = structure(bytes)?;
    let layout = symbol_layout(w, h, max_frames).ok_or_else(|| {
        Error::validation(crate::msg!(
            "trsPng.symbolSize",
            "Das Fan-Symbol muss quadratisch sein (16×16 bis 64×64), animiert höchstens {frames} Bilder untereinander.",
            frames = max_frames.clamp(1, SYMBOL_MAX_FRAMES)
        ))
    })?;
    let pixels = decode_rgba(bytes, w, h).ok_or_else(invalid_png)?;
    if !pixels.as_chunks::<4>().0.iter().any(|p| p[3] > 0) {
        return Err(Error::validation(crate::msg!("trsPng.emptySymbol", "Das Fan-Symbol ist komplett durchsichtig.")));
    }
    Ok(layout)
}

fn invalid_png() -> Error {
    Error::validation(crate::msg!("trsPng.invalidPng", "Die Datei ist kein gültiges PNG-Bild."))
}

/// Prüft den Aufbau (Signatur, Chunk-CRCs, Whitelist, keine APNG, nichts nach `IEND`)
/// und liefert Breite × Höhe aus dem IHDR – ohne zu dekodieren.
fn structure(bytes: &[u8]) -> Result<(u32, u32)> {
    let invalid = invalid_png;
    if bytes.len() < 8 + 25 + 12 || &bytes[..8] != SIGNATURE {
        return Err(invalid());
    }
    let mut off = 8usize;
    let mut chunks = 0usize;
    let mut header: Option<(u32, u32)> = None;
    let mut saw_end = false;
    while off < bytes.len() {
        if saw_end {
            // Daten hinter IEND (Polyglot-Dateien) lehnt auch der Server ab.
            return Err(invalid());
        }
        chunks += 1;
        if chunks > 1000 || off + 12 > bytes.len() {
            return Err(invalid());
        }
        let len = u32::from_be_bytes(bytes[off..off + 4].try_into().map_err(|_| invalid())?) as usize;
        let kind: &[u8; 4] = bytes[off + 4..off + 8].try_into().map_err(|_| invalid())?;
        if !kind.iter().all(u8::is_ascii_alphabetic) || off + 12 + len > bytes.len() {
            return Err(invalid());
        }
        let crc_stored = u32::from_be_bytes(bytes[off + 8 + len..off + 12 + len].try_into().map_err(|_| invalid())?);
        if crc32(&bytes[off + 4..off + 8 + len]) != crc_stored {
            return Err(invalid());
        }
        if chunks == 1 && kind != b"IHDR" {
            return Err(invalid());
        }
        if ANIMATION.contains(&kind) {
            return Err(Error::validation(crate::msg!(
                "trsPng.animated",
                "Animierte PNGs (APNG) werden nicht unterstützt – Animationen als Streifen hochladen."
            )));
        }
        if !ALLOWED.contains(&kind) {
            return Err(invalid());
        }
        let data = &bytes[off + 8..off + 8 + len];
        match kind {
            b"IHDR" => {
                if header.is_some() || len != 13 {
                    return Err(invalid());
                }
                let w = u32::from_be_bytes(data[0..4].try_into().map_err(|_| invalid())?);
                let h = u32::from_be_bytes(data[4..8].try_into().map_err(|_| invalid())?);
                header = Some((w, h));
            }
            b"IEND" => saw_end = true,
            _ => {}
        }
        off += 12 + len;
    }
    header.filter(|_| saw_end).ok_or_else(invalid)
}

/// Dekodiert mit festen Grenzen (Maße sind vorher geprüft) zu RGBA8.
fn decode_rgba(bytes: &[u8], width: u32, height: u32) -> Option<Vec<u8>> {
    let mut reader = image::ImageReader::with_format(std::io::Cursor::new(bytes), image::ImageFormat::Png);
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(width);
    limits.max_image_height = Some(height);
    // Größter Streifen: 1024 × 16384 RGBA = 64 MiB, dazu Puffer des Dekoders.
    limits.max_alloc = Some(160 * 1024 * 1024);
    reader.limits(limits);
    let image = reader.decode().ok()?;
    (image.width() == width && image.height() == height).then(|| image.into_rgba8().into_raw())
}

/// Hat irgendein Frame sichtbare Pixel im Umhang-Bereich (22k×17k oben links)?
fn cape_visible(rgba: &[u8], width: u32, layout: UploadLayout) -> bool {
    let (cw, ch) = (22 * layout.scale, 17 * layout.scale);
    (0..layout.frames).any(|f| {
        (0..ch).any(|y| {
            let row = ((f * layout.frame_height + y) * width) as usize * 4;
            (0..cw as usize).any(|x| rgba.get(row + x * 4 + 3).is_some_and(|&a| a > 0))
        })
    })
}

/// CRC-32 (ISO-HDLC), wie ihn PNG-Chunks verwenden.
fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFFu32;
    for &byte in data {
        crc ^= u32::from(byte);
        for _ in 0..8 {
            let mask = (crc & 1).wrapping_neg();
            crc = (crc >> 1) ^ (0xEDB8_8320 & mask);
        }
    }
    !crc
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub(crate) fn chunk(kind: &[u8; 4], data: &[u8]) -> Vec<u8> {
        let mut out = (data.len() as u32).to_be_bytes().to_vec();
        out.extend_from_slice(kind);
        out.extend_from_slice(data);
        let mut crc_input = kind.to_vec();
        crc_input.extend_from_slice(data);
        out.extend_from_slice(&crc32(&crc_input).to_be_bytes());
        out
    }

    /// Strukturell gültiges PNG (IDAT-Inhalt ist für die Prüfung egal).
    pub(crate) fn png_with(width: u32, height: u32, extra: &[(&[u8; 4], &[u8])]) -> Vec<u8> {
        let mut ihdr = width.to_be_bytes().to_vec();
        ihdr.extend_from_slice(&height.to_be_bytes());
        ihdr.extend_from_slice(&[8, 6, 0, 0, 0]);
        let mut out = SIGNATURE.to_vec();
        out.extend(chunk(b"IHDR", &ihdr));
        for (kind, data) in extra {
            out.extend(chunk(kind, data));
        }
        out.extend(chunk(b"IDAT", &[0x78, 0x9c, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01]));
        out.extend(chunk(b"IEND", &[]));
        out
    }

    /// Echtes, dekodierbares PNG: ein deckender Fleck oben links in jedem Frame.
    pub(crate) fn real_png(width: u32, height: u32) -> Vec<u8> {
        let img = image::RgbaImage::from_fn(width, height, |x, y| {
            if x < 4 && y % 17 < 4 { image::Rgba([200, 20, 20, 255]) } else { image::Rgba([0, 0, 0, 0]) }
        });
        encode(img)
    }

    fn encode(img: image::RgbaImage) -> Vec<u8> {
        let mut out = std::io::Cursor::new(Vec::new());
        image::DynamicImage::ImageRgba8(img).write_to(&mut out, image::ImageFormat::Png).unwrap();
        out.into_inner()
    }

    #[test]
    fn crc_matches_reference() {
        assert_eq!(crc32(b"IEND"), 0xAE42_6082);
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    }

    #[test]
    fn layouts() {
        assert_eq!(upload_layout(64, 32), Some(UploadLayout { scale: 1, frame_height: 32, frames: 1, cape_only: false }));
        assert_eq!(upload_layout(512, 256).map(|l| (l.scale, l.frames)), Some((8, 1)));
        assert_eq!(upload_layout(512, 4096).map(|l| (l.scale, l.frames)), Some((8, 16)));
        assert_eq!(upload_layout(1024, 512).map(|l| (l.scale, l.frames)), Some((16, 1)));
        assert_eq!(upload_layout(1024, 512 * 32).map(|l| (l.scale, l.frames)), Some((16, 32)));
        assert_eq!(upload_layout(176, 136 * 3).map(|l| (l.scale, l.frames, l.cape_only)), Some((8, 3, true)));
        assert_eq!(upload_layout(352, 272).map(|l| (l.scale, l.cape_only)), Some((16, true)));
        assert_eq!(upload_layout(64, 64).map(|l| l.frames), Some(2), "2 Frames à 64×32");
        assert!(upload_layout(512, 256 * 33).is_none(), "33 Frames");
        assert!(upload_layout(64, 32 * 33).is_none(), "33 Frames, Faktor 1");
        assert!(upload_layout(1088, 544).is_none(), "Faktor 17");
        assert!(upload_layout(64, 48).is_none(), "halber Frame");
        assert!(upload_layout(0, 0).is_none());
    }

    #[test]
    fn accepts_allowed_sizes() {
        for (w, h) in [(64, 32), (128, 64), (256, 128), (512, 256), (1024, 512), (22, 17), (176, 136), (352, 272)] {
            let layout = validate_upload(&real_png(w, h), 1, None).unwrap();
            assert_eq!(layout.frames, 1, "{w}×{h}");
        }
        let strip = validate_upload(&real_png(512, 4096), 16, Some(80)).unwrap();
        assert_eq!((strip.scale, strip.frames), (8, 16));
        // 32 Frames (echt dekodiert) und die Maße des größten Streifens (32 Frames à 1024×512).
        let long = validate_upload(&real_png(64, 32 * 32), 32, Some(50)).unwrap();
        assert_eq!((long.scale, long.frames), (1, 32));
        assert_eq!(MAX_PIXELS, 1024 * 512 * 32);
        assert!(validate_upload(&real_png(64, 32 * 33), 33, Some(50)).is_err(), "33 Frames");
        assert_eq!(size(&png_with(128, 64, &[])), Some((128, 64)));
    }

    #[test]
    fn checks_frames_and_frame_time() {
        let strip = real_png(64, 32 * 4);
        assert!(validate_upload(&strip, 3, Some(100)).is_err(), "Frame-Zahl passt nicht");
        let no_time = validate_upload(&strip, 4, None).unwrap_err();
        assert_eq!(no_time.message_code(), "trsPng.frameTime");
        assert!(validate_upload(&strip, 4, Some(20)).is_err(), "zu schnell");
        assert!(validate_upload(&strip, 4, Some(5000)).is_err(), "zu langsam");
        assert!(validate_upload(&strip, 4, Some(50)).is_ok());
        assert!(validate_upload(&real_png(64, 32), 1, Some(123)).is_ok(), "statisch: Tempo egal");
    }

    #[test]
    fn rejects_transparent_and_undecodable() {
        let clear = encode(image::RgbaImage::new(64, 32));
        assert_eq!(validate_upload(&clear, 1, None).unwrap_err().message_code(), "trsPng.emptyCape");
        // Nur außerhalb des Umhang-Bereichs sichtbar (Elytra-Teil) zählt nicht.
        let elytra_only = encode(image::RgbaImage::from_fn(64, 32, |x, _| {
            if x >= 22 { image::Rgba([1, 2, 3, 255]) } else { image::Rgba([0, 0, 0, 0]) }
        }));
        assert_eq!(validate_upload(&elytra_only, 1, None).unwrap_err().message_code(), "trsPng.emptyCape");
        // Aufbau stimmt, Bilddaten sind leer → nicht dekodierbar.
        assert_eq!(validate_upload(&png_with(64, 32, &[]), 1, None).unwrap_err().message_code(), "trsPng.invalidPng");
    }

    #[test]
    fn rejects_bad_files() {
        assert!(validate_upload(b"GIF89a", 1, None).is_err());
        assert!(validate_upload(&png_with(64, 48, &[]), 1, None).is_err(), "kein Umhang-Maß");
        assert!(validate_upload(&png_with(1088, 544, &[]), 1, None).is_err(), "zu groß skaliert");
        assert!(validate_upload(&png_with(1024, 512 * 33, &[]), 33, Some(50)).is_err(), "33 Frames");
        // Riesiger Kopf wird vor dem Dekodieren abgewiesen (Dekompressionsbombe).
        let bomb = validate_upload(&png_with(65_536, 32_768, &[]), 1, None).unwrap_err();
        assert_eq!(bomb.message_code(), "trsPng.invalidSize");
        let apng = png_with(64, 32, &[(b"acTL", &[0, 0, 0, 2, 0, 0, 0, 0])]);
        let animated = validate_upload(&apng, 1, None).unwrap_err();
        assert!(animated.to_string().contains("APNG"));
        assert_eq!(animated.message_code(), "trsPng.animated");
        let mut trailing = real_png(64, 32);
        trailing.extend_from_slice(b"<?php echo 1; ?>");
        assert!(validate_upload(&trailing, 1, None).is_err(), "Daten nach IEND");
        let mut broken = real_png(64, 32);
        let at = broken.len() - 20;
        broken[at] ^= 0xFF;
        assert!(validate_upload(&broken, 1, None).is_err(), "CRC");
        assert!(validate_upload(&png_with(64, 32, &[(b"zzzz", b"x")]), 1, None).is_err(), "unbekannter Chunk");
        let mut huge = real_png(64, 32);
        huge.resize(MAX_UPLOAD_BYTES + 1, 0);
        let err = validate_upload(&huge, 1, None).unwrap_err();
        assert_eq!(err.message_code(), "trsPng.fileTooLarge");
        assert!(err.to_string().contains("20 MB"));
        assert_eq!(MAX_UPLOAD_BYTES, 20 * 1024 * 1024);
    }

    #[test]
    fn symbols_are_square_strips() {
        assert_eq!(symbol_layout(16, 16, 1), Some(SymbolLayout { size: 16, frames: 1 }));
        assert_eq!(symbol_layout(64, 64 * 8, 8), Some(SymbolLayout { size: 64, frames: 8 }));
        assert_eq!(symbol_layout(24, 48, 8).map(|l| l.frames), Some(2));
        assert!(symbol_layout(24, 48, 1).is_none(), "Starter: nur ein Bild");
        assert!(symbol_layout(32, 32 * 9, 8).is_none(), "höchstens 8 Bilder");
        assert!(symbol_layout(15, 15, 1).is_none() && symbol_layout(65, 65, 1).is_none());
        assert!(symbol_layout(32, 40, 8).is_none(), "nicht quadratisch");

        let ok = validate_symbol(&real_png(32, 64), 8).unwrap();
        assert_eq!((ok.size, ok.frames), (32, 2));
        let starter = validate_symbol(&real_png(32, 64), 1).unwrap_err();
        assert_eq!(starter.message_code(), "trsPng.symbolSize");
        assert_eq!(starter.message_params()["frames"], "1");
        let clear = encode(image::RgbaImage::new(16, 16));
        assert_eq!(validate_symbol(&clear, 1).unwrap_err().message_code(), "trsPng.emptySymbol");
        let apng = png_with(16, 16, &[(b"acTL", &[0, 0, 0, 2, 0, 0, 0, 0])]);
        assert_eq!(validate_symbol(&apng, 8).unwrap_err().message_code(), "trsPng.animated");
        let mut big = real_png(16, 16);
        big.resize(SYMBOL_MAX_BYTES + 1, 0);
        assert_eq!(validate_symbol(&big, 1).unwrap_err().message_code(), "trsPng.symbolTooLarge");
    }
}
