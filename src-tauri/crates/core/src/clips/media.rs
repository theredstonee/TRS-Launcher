//! Clip-Index aus der MP4-Datei selbst (ohne FFmpeg/ffprobe): Dauer, Bildgröße,
//! Ton ja/nein und die Keyframe-Zeiten der Videospur.
//!
//! Die Keyframes entscheiden beim Zuschneiden, ob FFmpeg den Clip ohne
//! Neukodierung kopieren kann (Schnitt beginnt genau auf einem Keyframe) oder
//! neu kodieren muss. Gelesen wird nur der `moov`-Kasten (bei `+faststart` am
//! Dateianfang); `mdat` wird übersprungen.

use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use serde::Serialize;

/// `moov` größer als das ist kein Clip von uns (Schutz vor riesigen Allokationen).
const MAX_MOOV: u64 = 64 * 1024 * 1024;
/// So viele Keyframes werden höchstens gemeldet (≈ 3 Stunden bei 2-s-Abstand).
pub const MAX_KEYFRAMES: usize = 6000;

/// Was die Oberfläche und das Zuschneiden über einen Clip wissen müssen.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaInfo {
    pub duration_ms: u64,
    pub width: u32,
    pub height: u32,
    pub has_video: bool,
    pub has_audio: bool,
    /// Präsentationszeiten der Keyframes (ms, aufsteigend, beginnt meist bei 0).
    pub keyframes_ms: Vec<u64>,
}

/// Liest den `moov`-Kasten einer MP4-Datei (blockierend).
pub fn read_moov(path: &Path) -> Option<Vec<u8>> {
    let mut file = std::fs::File::open(path).ok()?;
    let len = file.metadata().ok()?.len();
    let mut pos = 0u64;
    while pos + 8 <= len {
        file.seek(SeekFrom::Start(pos)).ok()?;
        let (size, kind, header) = read_box_header(&mut file, len - pos)?;
        if &kind == b"moov" {
            let body = size.checked_sub(header)?;
            if body > MAX_MOOV {
                return None;
            }
            let mut buf = vec![0u8; usize::try_from(body).ok()?];
            file.read_exact(&mut buf).ok()?;
            return Some(buf);
        }
        pos = pos.checked_add(size)?;
    }
    None
}

fn read_box_header(file: &mut std::fs::File, remaining: u64) -> Option<(u64, [u8; 4], u64)> {
    let mut head = [0u8; 8];
    file.read_exact(&mut head).ok()?;
    let size32 = u32::from_be_bytes([head[0], head[1], head[2], head[3]]);
    let kind = [head[4], head[5], head[6], head[7]];
    let (size, header) = match size32 {
        0 => (remaining, 8),
        1 => {
            let mut large = [0u8; 8];
            file.read_exact(&mut large).ok()?;
            (u64::from_be_bytes(large), 16)
        }
        n => (u64::from(n), 8),
    };
    (size >= header && size <= remaining).then_some((size, kind, header))
}

/// Kästen innerhalb eines Puffers: `(Typ, Inhalt)`. Bricht bei kaputten Größen ab.
fn boxes(buf: &[u8]) -> impl Iterator<Item = ([u8; 4], &[u8])> {
    let mut pos = 0usize;
    std::iter::from_fn(move || {
        if pos + 8 > buf.len() {
            return None;
        }
        let size32 = u32::from_be_bytes(buf[pos..pos + 4].try_into().ok()?) as usize;
        let kind: [u8; 4] = buf[pos + 4..pos + 8].try_into().ok()?;
        let (size, header) = match size32 {
            0 => (buf.len() - pos, 8),
            1 => {
                let large = u64::from_be_bytes(buf.get(pos + 8..pos + 16)?.try_into().ok()?);
                (usize::try_from(large).ok()?, 16)
            }
            n => (n, 8),
        };
        if size < header || pos + size > buf.len() {
            return None;
        }
        let body = &buf[pos + header..pos + size];
        pos += size;
        Some((kind, body))
    })
}

fn child<'a>(buf: &'a [u8], kind: &[u8; 4]) -> Option<&'a [u8]> {
    boxes(buf).find(|(k, _)| k == kind).map(|(_, b)| b)
}

fn u32_at(b: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_be_bytes(b.get(at..at + 4)?.try_into().ok()?))
}

fn u64_at(b: &[u8], at: usize) -> Option<u64> {
    Some(u64::from_be_bytes(b.get(at..at + 8)?.try_into().ok()?))
}

/// Dauer aus `mvhd` (ms).
pub fn mvhd_duration_ms(moov: &[u8]) -> Option<u64> {
    let b = child(moov, b"mvhd")?;
    let (timescale, duration) =
        if *b.first()? == 1 { (u32_at(b, 20)?, u64_at(b, 24)?) } else { (u32_at(b, 12)?, u64::from(u32_at(b, 16)?)) };
    (timescale != 0).then(|| duration.saturating_mul(1000) / u64::from(timescale))
}

/// Dauer, Größe, Spuren und Keyframes einer MP4-Datei (blockierend).
pub fn probe(path: &Path) -> Option<MediaInfo> {
    parse_moov(&read_moov(path)?)
}

pub fn parse_moov(moov: &[u8]) -> Option<MediaInfo> {
    let mut info = MediaInfo { duration_ms: mvhd_duration_ms(moov)?, ..Default::default() };
    for (kind, trak) in boxes(moov) {
        if &kind != b"trak" {
            continue;
        }
        let Some(mdia) = child(trak, b"mdia") else { continue };
        let handler = child(mdia, b"hdlr").and_then(|h| h.get(8..12));
        match handler {
            Some(b"soun") => info.has_audio = true,
            Some(b"vide") if !info.has_video => {
                info.has_video = true;
                if let Some((w, h)) = child(trak, b"tkhd").and_then(track_size) {
                    info.width = w;
                    info.height = h;
                }
                info.keyframes_ms = video_keyframes(trak, mdia).unwrap_or_default();
            }
            _ => {}
        }
    }
    Some(info)
}

/// Breite/Höhe aus `tkhd` (16.16-Festkomma am Ende des Kastens).
fn track_size(tkhd: &[u8]) -> Option<(u32, u32)> {
    let at = if *tkhd.first()? == 1 { 88 } else { 76 };
    Some((u32_at(tkhd, at)? >> 16, u32_at(tkhd, at + 4)? >> 16))
}

/// Keyframe-Zeiten: `stss` (Sync-Samples, 1-basiert; fehlt er, ist jedes
/// Sample ein Keyframe) + `stts` (Dauer je Sample) + `ctts` (Versatz der
/// Anzeigezeit bei B-Frames) − Versatz aus `elst`.
fn video_keyframes(trak: &[u8], mdia: &[u8]) -> Option<Vec<u64>> {
    let mdhd = child(mdia, b"mdhd")?;
    let timescale = if *mdhd.first()? == 1 { u32_at(mdhd, 20)? } else { u32_at(mdhd, 12)? };
    if timescale == 0 {
        return None;
    }
    let stbl = child(child(mdia, b"minf")?, b"stbl")?;
    let stts = child(stbl, b"stts")?;
    // Zeitstempel (DTS) je Sample aus den Läufen in `stts`.
    let runs = u32_at(stts, 4)? as usize;
    let mut run_list = Vec::with_capacity(runs.min(100_000));
    for i in 0..runs {
        let count = u32_at(stts, 8 + i * 8)?;
        let delta = u32_at(stts, 12 + i * 8)?;
        run_list.push((count, delta));
    }
    let total: u64 = run_list.iter().map(|(c, _)| u64::from(*c)).sum();
    let sync: Vec<u64> = match child(stbl, b"stss") {
        Some(stss) => {
            let n = u32_at(stss, 4)? as usize;
            (0..n.min(1_000_000)).filter_map(|i| u32_at(stss, 8 + i * 4).map(u64::from)).collect()
        }
        // Ohne `stss` ist jedes Sample ein Sync-Sample – dann nur grob (höchstens MAX_KEYFRAMES).
        None => (1..=total).collect(),
    };
    let offset = edit_offset(trak).unwrap_or(0);
    let mut composition = CompositionOffsets::new(child(stbl, b"ctts"));
    let mut out = Vec::new();
    let (mut sample, mut dts) = (1u64, 0u64);
    let mut runs = run_list.into_iter();
    let mut current = runs.next();
    for target in sync {
        // Bis zum gesuchten Sample vorlaufen.
        while let Some((count, delta)) = current {
            let end = sample + u64::from(count);
            if target < end {
                break;
            }
            dts += u64::from(count) * u64::from(delta);
            sample = end;
            current = runs.next();
        }
        let Some((_, delta)) = current else { break };
        let t = dts + (target - sample) * u64::from(delta);
        let pts = i128::from(t) + i128::from(composition.at(target)) - i128::from(offset);
        let ms = u64::try_from(pts.max(0)).unwrap_or(0).saturating_mul(1000) / u64::from(timescale);
        if out.last().is_none_or(|last| ms > *last) {
            out.push(ms);
        }
        if out.len() >= MAX_KEYFRAMES {
            break;
        }
    }
    Some(out)
}

/// `ctts`: Anzeigezeit − Dekodierzeit je Sample (Läufe). Wird mit aufsteigenden
/// Sample-Nummern abgefragt (die Sync-Samples sind sortiert).
struct CompositionOffsets<'a> {
    ctts: Option<&'a [u8]>,
    signed: bool,
    entries: usize,
    entry: usize,
    /// Erstes Sample des aktuellen Laufs (1-basiert).
    first: u64,
}

impl<'a> CompositionOffsets<'a> {
    fn new(ctts: Option<&'a [u8]>) -> Self {
        let signed = ctts.and_then(|c| c.first().copied()) == Some(1);
        let entries = ctts.and_then(|c| u32_at(c, 4)).unwrap_or(0) as usize;
        Self { ctts, signed, entries, entry: 0, first: 1 }
    }

    fn at(&mut self, sample: u64) -> i64 {
        let Some(ctts) = self.ctts else { return 0 };
        while self.entry < self.entries {
            let Some(count) = u32_at(ctts, 8 + self.entry * 8) else { return 0 };
            if sample < self.first + u64::from(count) {
                let raw = u32_at(ctts, 12 + self.entry * 8).unwrap_or(0);
                return if self.signed { i64::from(i32::from_be_bytes(raw.to_be_bytes())) } else { i64::from(raw) };
            }
            self.first += u64::from(count);
            self.entry += 1;
        }
        0
    }
}

/// Startversatz der Medienzeit aus der ersten Edit-Liste (in Medien-Zeiteinheiten).
fn edit_offset(trak: &[u8]) -> Option<u64> {
    let elst = child(child(trak, b"edts")?, b"elst")?;
    let version = *elst.first()?;
    let count = u32_at(elst, 4)?;
    // Leere Edits (media_time = -1) am Anfang überspringen.
    for i in 0..count as usize {
        let media_time = if version == 1 {
            let v = u64_at(elst, 8 + i * 20 + 8)?;
            i64::from_be_bytes(v.to_be_bytes())
        } else {
            i64::from(i32::from_be_bytes(u32_at(elst, 8 + i * 12 + 4)?.to_be_bytes()))
        };
        if media_time >= 0 {
            return u64::try_from(media_time).ok();
        }
    }
    None
}

/// Letzter Keyframe bei oder vor `ms` (ohne Keyframes: 0).
pub fn keyframe_at_or_before(keyframes: &[u64], ms: u64) -> u64 {
    match keyframes.binary_search(&ms) {
        Ok(i) => keyframes[i],
        Err(0) => 0,
        Err(i) => keyframes[i - 1],
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub(crate) fn mk(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
        let mut v = u32::try_from(body.len() + 8).unwrap().to_be_bytes().to_vec();
        v.extend_from_slice(kind);
        v.extend_from_slice(body);
        v
    }

    fn full(version: u8, rest: &[u8]) -> Vec<u8> {
        let mut v = vec![version, 0, 0, 0];
        v.extend_from_slice(rest);
        v
    }

    fn be(values: &[u32]) -> Vec<u8> {
        values.iter().flat_map(|v| v.to_be_bytes()).collect()
    }

    fn mvhd(timescale: u32, duration: u32) -> Vec<u8> {
        let mut rest = be(&[0, 0, timescale, duration]);
        rest.extend_from_slice(&[0u8; 80]);
        mk(b"mvhd", &full(0, &rest))
    }

    fn tkhd(w: u32, h: u32) -> Vec<u8> {
        // Version 0: 4 (ver/flags) + 72 Bytes bis Breite/Höhe.
        let mut rest = vec![0u8; 72];
        rest.extend_from_slice(&be(&[w << 16, h << 16]));
        mk(b"tkhd", &full(0, &rest))
    }

    fn hdlr(kind: &[u8; 4]) -> Vec<u8> {
        let mut rest = be(&[0]);
        rest.extend_from_slice(kind);
        rest.extend_from_slice(&[0u8; 13]);
        mk(b"hdlr", &full(0, &rest))
    }

    fn mdhd(timescale: u32) -> Vec<u8> {
        mk(b"mdhd", &full(0, &be(&[0, 0, timescale, 0, 0])))
    }

    /// Videospur: `samples` Bilder mit `delta` je Bild, Keyframes an `sync` (1-basiert).
    pub(crate) fn video_trak(timescale: u32, samples: u32, delta: u32, sync: Option<&[u32]>, elst: Option<i32>) -> Vec<u8> {
        video_trak_ctts(timescale, samples, delta, sync, elst, None)
    }

    /// Wie [`video_trak`], dazu ein `ctts` mit einem festen Versatz für alle Bilder (B-Frames).
    pub(crate) fn video_trak_ctts(
        timescale: u32,
        samples: u32,
        delta: u32,
        sync: Option<&[u32]>,
        elst: Option<i32>,
        ctts: Option<u32>,
    ) -> Vec<u8> {
        let stts = mk(b"stts", &full(0, &be(&[1, samples, delta])));
        let mut stbl_body = stts;
        if let Some(offset) = ctts {
            stbl_body.extend(mk(b"ctts", &full(0, &be(&[1, samples, offset]))));
        }
        if let Some(sync) = sync {
            let mut rest = be(&[u32::try_from(sync.len()).unwrap()]);
            rest.extend(be(sync));
            stbl_body.extend(mk(b"stss", &full(0, &rest)));
        }
        let minf = mk(b"minf", &mk(b"stbl", &stbl_body));
        let mdia = mk(b"mdia", &[mdhd(timescale), hdlr(b"vide"), minf].concat());
        let mut trak = tkhd(1920, 1080);
        if let Some(t) = elst {
            let entry = [be(&[0, 1, 0]), t.to_be_bytes().to_vec(), be(&[0x0001_0000])].concat();
            trak.extend(mk(b"edts", &mk(b"elst", &full(0, &entry[4..]))));
        }
        trak.extend(mdia);
        mk(b"trak", &trak)
    }

    fn audio_trak() -> Vec<u8> {
        let mdia = mk(b"mdia", &[mdhd(48_000), hdlr(b"soun")].concat());
        mk(b"trak", &mdia)
    }

    /// Kleine, gültige MP4 (ftyp + moov + mdat) mit Video (30 fps, Keyframe alle 2 s) und Ton.
    pub(crate) fn sample_mp4(seconds: u32) -> Vec<u8> {
        let frames = seconds * 30;
        let sync: Vec<u32> = (0..frames).step_by(60).map(|i| i + 1).collect();
        let moov = mk(b"moov", &[mvhd(1000, seconds * 1000), video_trak(15_360, frames, 512, Some(&sync), None), audio_trak()].concat());
        let ftyp = mk(b"ftyp", b"isom\0\0\x02\0");
        [ftyp, moov, mk(b"mdat", &[0u8; 64])].concat()
    }

    #[test]
    fn index_mit_keyframes_groesse_und_ton() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("c.mp4");
        std::fs::write(&p, sample_mp4(10)).unwrap();
        let info = probe(&p).unwrap();
        assert_eq!(info.duration_ms, 10_000);
        assert_eq!((info.width, info.height), (1920, 1080));
        assert!(info.has_video && info.has_audio);
        assert_eq!(info.keyframes_ms, vec![0, 2000, 4000, 6000, 8000]);
    }

    #[test]
    fn edit_liste_verschiebt_die_keyframes() {
        // B-Frames: Medienzeit beginnt bei 1024 (= 2 Bilder bei 15360/s → 66 ms).
        let moov = [mvhd(1000, 4000), video_trak(15_360, 120, 512, Some(&[1, 61]), Some(1024))].concat();
        let info = parse_moov(&moov).unwrap();
        assert_eq!(info.keyframes_ms, vec![0, 1933]);
        assert!(!info.has_audio);
    }

    #[test]
    fn b_frames_versatz_aus_ctts_und_edit_liste() {
        // Wie x264/NVENC mit B-Frames: Keyframes dekodieren 2 Bilder früher (ctts = 1024), die Edit-Liste gleicht aus.
        let moov = [mvhd(1000, 4000), video_trak_ctts(15_360, 120, 512, Some(&[1, 61]), Some(1024), Some(1024))].concat();
        assert_eq!(parse_moov(&moov).unwrap().keyframes_ms, vec![0, 2000]);
    }

    #[test]
    fn ohne_stss_ist_jedes_bild_ein_keyframe() {
        let moov = [mvhd(1000, 1000), video_trak(10, 5, 2, None, None)].concat();
        let info = parse_moov(&moov).unwrap();
        assert_eq!(info.keyframes_ms, vec![0, 200, 400, 600, 800]);
    }

    #[test]
    fn kaputte_dateien_liefern_nichts() {
        assert!(parse_moov(b"garbage").is_none());
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("x.mp4");
        std::fs::write(&p, b"\0\0\0\x10ftypisom\0\0\0\0\xff\xff\xff\xffmoov").unwrap();
        assert!(probe(&p).is_none());
        // Stark verkürzte Spur: kein Absturz, keine Keyframes jenseits der Daten.
        let moov = [mvhd(1000, 1000), video_trak(10, 3, 2, Some(&[1, 99]), None)].concat();
        assert_eq!(parse_moov(&moov).unwrap().keyframes_ms, vec![0]);
    }

    #[test]
    fn keyframe_vor_einer_zeit() {
        let k = [0, 2000, 4000];
        assert_eq!(keyframe_at_or_before(&k, 0), 0);
        assert_eq!(keyframe_at_or_before(&k, 1999), 0);
        assert_eq!(keyframe_at_or_before(&k, 2000), 2000);
        assert_eq!(keyframe_at_or_before(&k, 9000), 4000);
        assert_eq!(keyframe_at_or_before(&[], 500), 0);
    }
}
