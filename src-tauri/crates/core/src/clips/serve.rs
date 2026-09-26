//! Eigenes Protokoll `trsclip:` für die Wiedergabe im Webview.
//!
//! Das Webview bekommt nie Dateipfade, nur Adressen der Form
//! `trsclip://localhost/<art>/<Instanz-ID>/<Dateiname>` (unter Windows
//! `http://trsclip.localhost/…`). Aufgelöst wird ausschließlich innerhalb des
//! Clip-Ordners – mit denselben strengen Prüfungen wie überall
//! ([`library::clip_path`]: gültige Instanz-ID, `*.mp4`-Name ohne Pfadteile,
//! echte Datei, keine Verknüpfung). Videos werden in Stücken mit
//! HTTP-Range-Anfragen ausgeliefert (Spulen ohne die ganze Datei zu laden).

use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

use super::library;
use crate::Result;

pub const SCHEME: &str = "trsclip";
/// Höchstens so viel je Antwort (offene Bereiche `bytes=X-` werden gekürzt).
pub const MAX_CHUNK: u64 = 4 * 1024 * 1024;
/// Anfragen ohne `Range` bekommen die ganze Datei nur bis zu dieser Größe.
const MAX_WHOLE: u64 = 32 * 1024 * 1024;

/// Was angefragt wird.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Asset {
    /// Das Video selbst.
    Video,
    /// Vorschaubild (JPEG, wird bei Bedarf erzeugt).
    Poster,
    /// Vorschau-Leiste (PNG-Raster, wird bei Bedarf erzeugt).
    Strip,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClipRequest {
    pub asset: Asset,
    pub instance_id: String,
    pub file_name: String,
}

/// Prozent-Dekodierung eines Pfadteils; `None` bei ungültigen Sequenzen/UTF-8.
fn percent_decode(part: &str) -> Option<String> {
    let bytes = part.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = std::str::from_utf8(bytes.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

/// Pfad der Anfrage (`/v/<id>/<datei>`, Abfrage `?…` wird ignoriert) → Anfrage.
/// Alles andere (Ordner, `..`, Laufwerke, andere Endungen, doppelte Teile) → `None`.
pub fn parse_path(path: &str) -> Option<ClipRequest> {
    let path = path.split(['?', '#']).next()?;
    let mut parts = path.strip_prefix('/').unwrap_or(path).split('/');
    let (kind, id, file) = (parts.next()?, parts.next()?, parts.next()?);
    if parts.next().is_some() {
        return None;
    }
    let asset = match kind {
        "v" => Asset::Video,
        "p" => Asset::Poster,
        "s" => Asset::Strip,
        _ => return None,
    };
    let (instance_id, file_name) = (percent_decode(id)?, percent_decode(file)?);
    // Kodierte Trenner (`%2F`, `%5C`) oder NUL nie zulassen – auch wenn die Prüfungen danach sie abfangen würden.
    if [&instance_id, &file_name].iter().any(|s| s.contains(['/', '\\', '\0'])) {
        return None;
    }
    crate::instance::validate_id(&instance_id).ok()?;
    library::is_clip_file_name(&file_name).then_some(ClipRequest { asset, instance_id, file_name })
}

/// Geprüfter Pfad des Videos im Clip-Ordner.
pub fn resolve(root: &Path, request: &ClipRequest) -> Result<PathBuf> {
    library::clip_path(root, &request.instance_id, &request.file_name)
}

/// `Range: bytes=…` → eingeschlossener Bereich `(start, end)`, gekürzt auf [`MAX_CHUNK`].
/// `Err(())` = nicht erfüllbar (416). Ohne bzw. mit nicht verstandenem Header → `Ok(None)`.
#[allow(clippy::result_unit_err)]
pub fn parse_range(header: Option<&str>, len: u64) -> std::result::Result<Option<(u64, u64)>, ()> {
    let Some(spec) = header.and_then(|h| h.trim().strip_prefix("bytes=")) else { return Ok(None) };
    // Mehrere Bereiche: nur den ersten bedienen.
    let first = spec.split(',').next().unwrap_or("").trim();
    let Some((a, b)) = first.split_once('-') else { return Ok(None) };
    let (a, b) = (a.trim(), b.trim());
    if len == 0 {
        return Err(());
    }
    let (start, end) = if a.is_empty() {
        // Letzte n Bytes.
        let n: u64 = b.parse().map_err(|_| ())?;
        if n == 0 {
            return Err(());
        }
        (len.saturating_sub(n), len - 1)
    } else {
        let start: u64 = a.parse().map_err(|_| ())?;
        let end = if b.is_empty() { len - 1 } else { b.parse::<u64>().map_err(|_| ())?.min(len - 1) };
        (start, end)
    };
    if start >= len || end < start {
        return Err(());
    }
    Ok(Some((start, end.min(start + MAX_CHUNK - 1))))
}

/// Antwort unabhängig vom Webview-Framework.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Response {
    pub status: u16,
    pub headers: Vec<(&'static str, String)>,
    pub body: Vec<u8>,
}

impl Response {
    pub fn status(status: u16) -> Self {
        Self { status, headers: vec![("Cache-Control", "no-store".into())], body: Vec::new() }
    }
}

pub fn content_type(asset: Asset) -> &'static str {
    match asset {
        Asset::Video => "video/mp4",
        Asset::Poster => "image/jpeg",
        Asset::Strip => "image/png",
    }
}

/// Liest die Datei (ganz oder den angefragten Bereich) – blockierend.
pub fn respond_file(path: &Path, asset: Asset, range: Option<&str>) -> Response {
    let Ok(mut file) = std::fs::File::open(path) else { return Response::status(404) };
    let Ok(len) = file.metadata().map(|m| m.len()) else { return Response::status(404) };
    let mut range = match parse_range(range, len) {
        Ok(r) => r,
        Err(()) => {
            let mut r = Response::status(416);
            r.headers.push(("Content-Range", format!("bytes */{len}")));
            return r;
        }
    };
    // Große Videos nie am Stück: dann wie `bytes=0-` behandeln.
    if range.is_none() && asset == Asset::Video && len > MAX_WHOLE {
        range = Some((0, (MAX_CHUNK - 1).min(len.saturating_sub(1))));
    }
    let mut headers = vec![
        ("Content-Type", content_type(asset).to_owned()),
        ("Accept-Ranges", "bytes".to_owned()),
        ("Cache-Control", if asset == Asset::Video { "no-store" } else { "max-age=3600" }.to_owned()),
        ("X-Content-Type-Options", "nosniff".to_owned()),
    ];
    let (status, start, count) = match range {
        Some((start, end)) => {
            headers.push(("Content-Range", format!("bytes {start}-{end}/{len}")));
            (206, start, end - start + 1)
        }
        None => (200, 0, len),
    };
    let mut body = vec![0u8; usize::try_from(count).unwrap_or(0)];
    if file.seek(SeekFrom::Start(start)).is_err() || file.read_exact(&mut body).is_err() {
        return Response::status(500);
    }
    headers.push(("Content-Length", body.len().to_string()));
    Response { status, headers, body }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nur_clips_im_clip_ordner() {
        let ok = parse_path("/v/survival/Survival%202026-09-24%2015-30-12.mp4").unwrap();
        assert_eq!(ok, ClipRequest { asset: Asset::Video, instance_id: "survival".into(), file_name: "Survival 2026-09-24 15-30-12.mp4".into() });
        assert_eq!(parse_path("/p/survival/a.mp4?t=1").unwrap().asset, Asset::Poster);
        assert_eq!(parse_path("s/survival/a.mp4").unwrap().asset, Asset::Strip);
        for bad in [
            "/v/survival/../../secret.mp4",
            "/v/../survival/a.mp4",
            "/v/survival/..%2F..%2Fx.mp4",
            "/v/survival/..%5Cx.mp4",
            "/v/survival%2F..%2Fother/a.mp4",
            "/v/C:%5CWindows/a.mp4",
            "/v/survival/a.txt",
            "/v/survival/a.mp4/extra",
            "/v/survival",
            "/x/survival/a.mp4",
            "/v//a.mp4",
            "/v/survival/con.mp4",
            "/v/survival/a%00.mp4",
            "/v/survival/%ZZ.mp4",
            "/v/Survival Welt/a.mp4",
        ] {
            assert!(parse_path(bad).is_none(), "{bad} muss abgelehnt werden");
        }
    }

    #[test]
    fn aufloesen_nur_vorhandener_echter_dateien() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("clips");
        std::fs::create_dir_all(root.join("survival")).unwrap();
        std::fs::write(root.join("survival/a.mp4"), b"0123456789").unwrap();
        std::fs::write(dir.path().join("geheim.mp4"), b"x").unwrap();
        let req = parse_path("/v/survival/a.mp4").unwrap();
        assert_eq!(resolve(&root, &req).unwrap(), root.join("survival/a.mp4"));
        assert!(resolve(&root, &parse_path("/v/survival/b.mp4").unwrap()).is_err());
        assert!(resolve(&root, &parse_path("/v/other/a.mp4").unwrap()).is_err());
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(dir.path().join("geheim.mp4"), root.join("survival/link.mp4")).unwrap();
            assert!(resolve(&root, &parse_path("/v/survival/link.mp4").unwrap()).is_err(), "keine Verknüpfungen");
        }
    }

    #[test]
    fn bereiche() {
        assert_eq!(parse_range(None, 100), Ok(None));
        assert_eq!(parse_range(Some("bytes=0-"), 100), Ok(Some((0, 99))));
        assert_eq!(parse_range(Some("bytes=10-19"), 100), Ok(Some((10, 19))));
        assert_eq!(parse_range(Some("bytes=90-500"), 100), Ok(Some((90, 99))));
        assert_eq!(parse_range(Some("bytes=-30"), 100), Ok(Some((70, 99))));
        assert_eq!(parse_range(Some("bytes=0-9, 20-29"), 100), Ok(Some((0, 9))));
        assert_eq!(parse_range(Some("bytes=100-"), 100), Err(()));
        assert_eq!(parse_range(Some("bytes=20-10"), 100), Err(()));
        assert_eq!(parse_range(Some("bytes=x-"), 100), Err(()));
        assert_eq!(parse_range(Some("items=0-1"), 100), Ok(None));
        let big = MAX_CHUNK * 3;
        assert_eq!(parse_range(Some("bytes=0-"), big), Ok(Some((0, MAX_CHUNK - 1))), "offene Bereiche in Stücken");
    }

    #[test]
    fn antworten_mit_und_ohne_bereich() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("a.mp4");
        std::fs::write(&file, b"0123456789").unwrap();
        let whole = respond_file(&file, Asset::Video, None);
        assert_eq!((whole.status, whole.body.as_slice()), (200, b"0123456789".as_slice()));
        let part = respond_file(&file, Asset::Video, Some("bytes=2-5"));
        assert_eq!((part.status, part.body.as_slice()), (206, b"2345".as_slice()));
        assert!(part.headers.contains(&("Content-Range", "bytes 2-5/10".into())));
        assert!(part.headers.contains(&("Content-Type", "video/mp4".into())));
        assert_eq!(respond_file(&file, Asset::Video, Some("bytes=50-")).status, 416);
        assert_eq!(respond_file(&dir.path().join("fehlt.mp4"), Asset::Video, None).status, 404);
        assert!(respond_file(&file, Asset::Strip, None).headers.contains(&("Content-Type", "image/png".into())));
    }
}
