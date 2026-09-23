//! PNG-Prüfung vor dem Senden (Umhang-Upload) und für geladene Texturen.
//! Gleiche Regeln wie der Server (`png.ts`): Signatur, Chunk-CRCs, Whitelist,
//! keine Animation, nichts nach `IEND`, erlaubte Maße.

use crate::{Error, Result};

pub const MAX_UPLOAD_BYTES: usize = 256 * 1024;
const MAX_SCALE: u32 = 4;
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

/// Erlaubte Maße für Uploads: 64k×32k oder 22k×17k (k = 1…4). Liefert k.
pub fn upload_scale(width: u32, height: u32) -> Option<u32> {
    (1..=MAX_SCALE).find(|&k| (width == 64 * k && height == 32 * k) || (width == 22 * k && height == 17 * k))
}

/// Prüft eine Upload-Datei vollständig. Fehlertexte sind für den Nutzer.
pub fn validate_upload(bytes: &[u8]) -> Result<(u32, u32)> {
    if bytes.len() > MAX_UPLOAD_BYTES {
        return Err(Error::validation("Die Datei ist zu groß (höchstens 256 KB)."));
    }
    let invalid = || Error::validation("Die Datei ist kein gültiges PNG-Bild.");
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
            return Err(Error::validation("Animierte PNGs sind für eigene Umhänge nicht erlaubt."));
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
    let (w, h) = header.filter(|_| saw_end).ok_or_else(invalid)?;
    if upload_scale(w, h).is_none() {
        return Err(Error::validation(
            "Der Umhang muss 64×32 (oder 128×64, 192×96, 256×128) bzw. im Umhang-Format 22×17 \
             (oder 44×34, 66×51, 88×68) Pixel groß sein.",
        ));
    }
    Ok((w, h))
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

    fn chunk(kind: &[u8; 4], data: &[u8]) -> Vec<u8> {
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

    #[test]
    fn crc_matches_reference() {
        assert_eq!(crc32(b"IEND"), 0xAE42_6082);
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    }

    #[test]
    fn accepts_allowed_sizes() {
        for (w, h) in [(64, 32), (128, 64), (256, 128), (22, 17), (88, 68)] {
            assert_eq!(validate_upload(&png_with(w, h, &[])).unwrap(), (w, h), "{w}×{h}");
        }
        assert_eq!(size(&png_with(128, 64, &[])), Some((128, 64)));
    }

    #[test]
    fn rejects_bad_files() {
        assert!(validate_upload(b"GIF89a").is_err());
        assert!(validate_upload(&png_with(64, 64, &[])).is_err(), "Skin-Maße");
        assert!(validate_upload(&png_with(320, 160, &[])).is_err(), "zu groß skaliert");
        let apng = png_with(64, 32, &[(b"acTL", &[0, 0, 0, 2, 0, 0, 0, 0])]);
        assert!(validate_upload(&apng).unwrap_err().to_string().contains("Animierte"));
        let mut trailing = png_with(64, 32, &[]);
        trailing.extend_from_slice(b"<?php echo 1; ?>");
        assert!(validate_upload(&trailing).is_err(), "Daten nach IEND");
        let mut broken = png_with(64, 32, &[]);
        let at = broken.len() - 20;
        broken[at] ^= 0xFF;
        assert!(validate_upload(&broken).is_err(), "CRC");
        assert!(validate_upload(&png_with(64, 32, &[(b"zzzz", b"x")])).is_err(), "unbekannter Chunk");
        let mut huge = png_with(64, 32, &[]);
        huge.resize(MAX_UPLOAD_BYTES + 1, 0);
        assert!(validate_upload(&huge).unwrap_err().to_string().contains("zu groß"));
    }
}
