//! Screenshots als Link teilen (Vertrag `api/API.md` §23).
//!
//! - **Welche Datei:** nur Screenshots einer Instanz (`screenshots/<name>.png`)
//!   oder Panoramen (`screenshots/panorama/<name>.png`). Der Kern prüft Instanz,
//!   Dateinamen, folgt keinen Verknüpfungen (Symlinks/Junctions) und vergleicht
//!   den echten Pfad mit dem Screenshot-Ordner.
//! - **Vorbereiten:** Bilder bis 10 MiB und 4096 px gehen unverändert hoch (der
//!   Server kodiert ohnehin neu), größere werden hier verkleinert und als JPEG
//!   neu kodiert – so verlässt auch keine Metadatenzeile den Rechner.
//! - **Antworten:** Links werden nur übernommen, wenn sie genau auf die eigene
//!   API-Adresse zeigen; sonst baut der Kern sie selbst aus der ID.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::media::{PreparedImage, encode_jpeg, encode_png, inspect};
use super::{Body, Req, validate};
use crate::paths::Paths;
use crate::{Error, Launcher, Result};

/// Größte Datei, die an die API geht (§23).
pub const MAX_SHARE_BYTES: usize = 10 * 1024 * 1024;
/// Größte Kante, die die API behält.
pub const SHARE_SIDE: u32 = 4096;
/// Größte Datei, die überhaupt gelesen wird.
const MAX_INPUT_BYTES: u64 = 64 * 1024 * 1024;
/// Unterordner für Panoramen im Screenshot-Ordner.
pub const PANORAMA_DIR: &str = "panorama";

fn invalid(msg: crate::error::Msg) -> Error {
    Error::validation(msg)
}

fn gone() -> Error {
    invalid(crate::msg!("extras.screenshotGone", "Der Screenshot existiert nicht mehr."))
}

/// ID eines geteilten Bildes: 22 Zeichen base64url (`^[A-Za-z0-9_-]{22}$`).
pub fn share_id(input: &str) -> bool {
    input.len() == 22 && input.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

// --- Datei ---------------------------------------------------------------------------

fn is_link(path: &Path) -> bool {
    std::fs::symlink_metadata(path).map(|m| m.file_type().is_symlink()).unwrap_or(true) || junction(path)
}

#[cfg(windows)]
fn junction(path: &Path) -> bool {
    use std::os::windows::fs::MetadataExt;
    const REPARSE_POINT: u32 = 0x400;
    std::fs::symlink_metadata(path).map(|m| m.file_attributes() & REPARSE_POINT != 0).unwrap_or(true)
}

#[cfg(not(windows))]
fn junction(_path: &Path) -> bool {
    false
}

/// Geprüfter Pfad zu einem teilbaren Bild: `name.png` oder `panorama/name.png`.
pub fn shot_path(paths: &Paths, instance_id: &str, file_name: &str) -> Result<PathBuf> {
    crate::instance::validate_id(instance_id)?;
    let bad_name = || invalid(crate::msg!("content.invalidFileName", "Ungültiger Dateiname"));
    let (sub, name) = match file_name.split_once('/') {
        Some((dir, name)) if dir == PANORAMA_DIR => (Some(PANORAMA_DIR), name),
        Some(_) => return Err(bad_name()),
        None => (None, file_name),
    };
    if !crate::extras::is_plain_file_name(name, ".png") {
        return Err(bad_name());
    }
    let root = crate::extras::screenshots_dir(paths, instance_id);
    let dir = match sub {
        Some(s) => root.join(s),
        None => root.clone(),
    };
    let path = dir.join(name);
    // Keine Verknüpfungen – weder der Ordner noch die Datei selbst. Einzige Ausnahme:
    // der Screenshot-Ordner als Link auf den gemeinsamen Ordner.
    let shared_root = || crate::shared_folders::SharedLinks::new(paths).is_link(Path::new("screenshots"), &root);
    if !path.is_file() || is_link(&path) || (sub.is_some() && is_link(&dir)) || (is_link(&root) && !shared_root()) {
        return Err(gone());
    }
    let (Ok(real), Ok(real_root)) = (path.canonicalize(), root.canonicalize()) else { return Err(gone()) };
    if !real.starts_with(&real_root) {
        return Err(bad_name());
    }
    Ok(path)
}

/// Bild für den Upload vorbereiten (blockierend – über `spawn_blocking` aufrufen).
pub fn prepare_share(bytes: Vec<u8>) -> Result<PreparedImage> {
    let too_large = || invalid(crate::msg!("chatImage.fileTooLarge", "Die Datei ist zu groß."));
    if bytes.len() as u64 > MAX_INPUT_BYTES {
        return Err(too_large());
    }
    let (mime, width, height) = inspect(&bytes)?;
    if bytes.len() <= MAX_SHARE_BYTES && width.max(height) <= SHARE_SIDE {
        return Ok(PreparedImage { mime, bytes, width, height });
    }
    let decoded = image::load_from_memory(&bytes)
        .map_err(|_| invalid(crate::msg!("chatImage.broken", "Das Bild ist beschädigt.")))?;
    drop(bytes);
    let scaled = if width.max(height) > SHARE_SIDE {
        decoded.resize(SHARE_SIDE, SHARE_SIDE, image::imageops::FilterType::Triangle)
    } else {
        decoded
    };
    let transparent = scaled.color().has_alpha() && scaled.to_rgba8().pixels().any(|p| p.0[3] < 255);
    if transparent {
        let png = encode_png(&scaled)?;
        if png.len() <= MAX_SHARE_BYTES {
            return Ok(PreparedImage { mime: "image/png", bytes: png, width: scaled.width(), height: scaled.height() });
        }
    }
    for quality in [90u8, 82, 72] {
        let jpeg = encode_jpeg(&scaled, quality)?;
        if jpeg.len() <= MAX_SHARE_BYTES {
            return Ok(PreparedImage { mime: "image/jpeg", bytes: jpeg, width: scaled.width(), height: scaled.height() });
        }
    }
    Err(too_large())
}

// --- Ansichten -----------------------------------------------------------------------

/// Ein eigenes geteiltes Bild (§23 `ShareView`, gesäubert).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SharedImage {
    pub id: String,
    /// Öffentlicher Link `https://…/s/<id>`.
    pub url: String,
    pub image_url: String,
    pub thumb_url: String,
    pub mime: String,
    pub width: u32,
    pub height: u32,
    pub bytes: u64,
    pub created_at: Option<String>,
    pub expires_at: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShareLimits {
    #[serde(default)]
    pub active: u32,
    #[serde(default)]
    pub max_active: u32,
    #[serde(default)]
    pub uploads_today: u32,
    #[serde(default)]
    pub max_per_day: u32,
}

/// Eigene geteilte Bilder + Grenzen.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SharesPage {
    pub shares: Vec<SharedImage>,
    pub limits: ShareLimits,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiShare {
    id: String,
    #[serde(default)]
    url: Option<String>,
    #[serde(default)]
    image_url: Option<String>,
    #[serde(default)]
    thumb_url: Option<String>,
    #[serde(default)]
    mime: String,
    #[serde(default)]
    width: u32,
    #[serde(default)]
    height: u32,
    #[serde(default)]
    bytes: u64,
    #[serde(default)]
    created_at: Option<String>,
    #[serde(default)]
    expires_at: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ApiShareEnvelope {
    share: ApiShare,
}

#[derive(Debug, Deserialize)]
struct ApiSharesPage {
    #[serde(default)]
    shares: Vec<ApiShare>,
    #[serde(default)]
    limits: ShareLimits,
}

/// Link der API übernehmen, wenn er genau so aussieht wie erwartet – sonst selbst bauen.
fn pick_url(given: Option<String>, bases: &[&str], own: &str, path: &str) -> String {
    given
        .filter(|u| bases.iter().any(|b| b.starts_with("https://") && *u == format!("{b}{path}")))
        .unwrap_or_else(|| format!("{own}{path}"))
}

fn time(value: Option<String>) -> Option<String> {
    value.map(|t| validate::text(&t, 40)).filter(|t| !t.is_empty())
}

impl ApiShare {
    fn cleaned(self, bases: &[&str], own: &str) -> Option<SharedImage> {
        if !share_id(&self.id) {
            return None;
        }
        let id = self.id;
        let side = |v: u32| v.min(16_384);
        Some(SharedImage {
            url: pick_url(self.url, bases, own, &format!("/s/{id}")),
            image_url: pick_url(self.image_url, bases, own, &format!("/v1/shares/{id}/image")),
            thumb_url: pick_url(self.thumb_url, bases, own, &format!("/v1/shares/{id}/image?thumb=1")),
            mime: if self.mime == "image/png" { self.mime } else { "image/jpeg".into() },
            width: side(self.width),
            height: side(self.height),
            bytes: self.bytes.min(64 * 1024 * 1024),
            created_at: time(self.created_at),
            expires_at: time(self.expires_at),
            id,
        })
    }
}

/// Eigene Texte für die Fehler des Teilens
/// (507 wäre sonst „offline“, 429 die allgemeine Ratenbegrenzung).
fn share_error(e: Error) -> Error {
    let Error::TrsApi { kind, code, msg } = e else { return e };
    let msg = match code.as_str() {
        "shared_image_limit" => crate::msg!(
            "shareLink.limit",
            "Du hast schon 50 geteilte Bilder – lösche zuerst ein altes unter „Meine geteilten Bilder“."
        ),
        "share_daily_limit" => crate::msg!("shareLink.dailyLimit", "Heute hast du schon 20 Bilder geteilt – morgen geht es weiter."),
        "storage_full" => crate::msg!("shareLink.storageFull", "Der TRS-Server hat gerade keinen Platz – bitte später erneut versuchen."),
        "share_not_found" => crate::msg!("shareLink.notFound", "Dieses geteilte Bild gibt es nicht (mehr)."),
        _ => msg,
    };
    let kind = if code == "storage_full" || code == "share_daily_limit" { "trs_api" } else { kind };
    Error::TrsApi { kind, code, msg }
}

fn share_arg(id: &str) -> Result<&str> {
    if share_id(id) {
        Ok(id)
    } else {
        Err(invalid(crate::msg!("shareLink.invalidId", "Ungültiger Link.")))
    }
}

impl Launcher {
    fn share_bases(&self) -> (Vec<&str>, &str) {
        (self.trs.trusted_bases(), self.trs.base())
    }

    /// Screenshot (oder Panorama) prüfen, ggf. verkleinern und als öffentlichen Link teilen.
    pub async fn share_screenshot(&self, instance_id: &str, file_name: &str) -> Result<SharedImage> {
        let path = shot_path(&self.paths, instance_id, file_name)?;
        let meta = tokio::fs::metadata(&path).await.map_err(|_| gone())?;
        if meta.len() > MAX_INPUT_BYTES {
            return Err(invalid(crate::msg!("chatImage.fileTooLarge", "Die Datei ist zu groß.")));
        }
        let bytes = tokio::fs::read(&path).await.map_err(|_| gone())?;
        let prepared = tokio::task::spawn_blocking(move || prepare_share(bytes))
            .await
            .map_err(|_| invalid(crate::msg!("chatImage.broken", "Das Bild ist beschädigt.")))??;
        let req = Req::with(reqwest::Method::POST, "/v1/shares", Body::Image(prepared.mime, prepared.bytes), super::MAX_JSON_BYTES);
        let result: ApiShareEnvelope = self.trs_get(req).await.map_err(share_error)?;
        let (bases, own) = self.share_bases();
        result.share.cleaned(&bases, own).ok_or_else(super::bad_response)
    }

    /// Eigene geteilte Bilder (neueste zuerst) und Grenzen.
    pub async fn shares_list(&self) -> Result<SharesPage> {
        let page: ApiSharesPage = self.trs_get(Req::get("/v1/shares")).await.map_err(share_error)?;
        let (bases, own) = self.share_bases();
        Ok(SharesPage {
            shares: page.shares.into_iter().filter_map(|s| s.cleaned(&bases, own)).take(500).collect(),
            limits: ShareLimits {
                active: page.limits.active.min(10_000),
                max_active: page.limits.max_active.min(10_000),
                uploads_today: page.limits.uploads_today.min(10_000),
                max_per_day: page.limits.max_per_day.min(10_000),
            },
        })
    }

    /// Eigenen Link vorzeitig löschen (Bild ist danach weg).
    pub async fn share_delete(&self, id: &str) -> Result<()> {
        let id = share_arg(id)?;
        self.trs_do(Req::delete(format!("/v1/shares/{id}"))).await.map_err(share_error)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn share_ids() {
        assert!(share_id("Qm9vLWJhei1xdXV4LTEyMw"));
        assert!(share_id("abc-DEF_0123456789abcd"));
        assert!(!share_id("Qm9vLWJhei1xdXV4LTEyM"));
        assert!(!share_id("../../../../etc/passwd.."));
        assert!(!share_id("Qm9vLWJhei1xdXV4LTEy/w"));
    }

    #[test]
    fn urls_are_only_taken_from_the_own_api() {
        let bases = ["https://trs-launcher.theredstonee.de", "https://api.theredstonee.de"];
        let own = "https://trs-launcher.theredstonee.de";
        let id = "Qm9vLWJhei1xdXV4LTEyMw";
        let path = format!("/s/{id}");
        assert_eq!(pick_url(Some(format!("https://api.theredstonee.de{path}")), &bases, own, &path), format!("https://api.theredstonee.de{path}"));
        assert_eq!(pick_url(Some(format!("https://evil.example{path}")), &bases, own, &path), format!("{own}{path}"));
        assert_eq!(pick_url(Some("javascript:alert(1)".into()), &bases, own, &path), format!("{own}{path}"));
        assert_eq!(pick_url(None, &bases, own, &path), format!("{own}{path}"));
        // http-Links der API nie übernehmen.
        assert_eq!(pick_url(Some(format!("http://trs-launcher.theredstonee.de{path}")), &bases, own, &path), format!("{own}{path}"));
    }

    #[test]
    fn small_images_pass_large_ones_are_reencoded() {
        let small = super::super::media::tests::png(64, 32, 255);
        let p = prepare_share(small.clone()).unwrap();
        assert_eq!(p.bytes, small);
        let big = super::super::media::tests::png(5000, 20, 255);
        let p = prepare_share(big).unwrap();
        assert_eq!((p.mime, p.width), ("image/jpeg", SHARE_SIDE));
        let transparent = super::super::media::tests::png(5000, 20, 10);
        let p = prepare_share(transparent).unwrap();
        assert_eq!(p.mime, "image/png");
        assert!(prepare_share(b"GIF89a".to_vec()).is_err());
    }
}
