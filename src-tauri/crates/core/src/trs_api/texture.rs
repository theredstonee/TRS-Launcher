//! Umhang-Texturen der TRS API → Data-URL fürs Webview (CSP bleibt eng, WebGL
//! braucht kein CORS). Freigegebene Texturen landen im Cache
//! `<daten>/cache/trs-capes/<id>-<v>.png`; noch nicht freigegebene (nur für
//! Uploader/Admin sichtbar) werden nie auf die Platte geschrieben.

use std::path::PathBuf;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;

use super::types::{ApiCape, CapeStatus};
use super::{TrsApi, png, validate};
use crate::fsutil;

/// Animierte HD-Umhänge können groß werden (256 × 128·64 px).
const MAX_TEXTURE_BYTES: usize = 8 * 1024 * 1024;

/// Was an einer Textur-URL erlaubt ist: `<base>/v1/capes/<id>.png[?v=<token>]`
/// mit einer der vertrauenswürdigen Adressen (neue und alte API-Adresse).
#[cfg(test)]
pub(crate) fn parse_texture_url<'a>(bases: &[&str], url: &'a str, id: &str) -> Option<Option<&'a str>> {
    parse_asset_url(bases, url, "capes", id)
}

/// Wie [`parse_texture_url`] für `<base>/v1/<segment>/<id>.png[?v=…]` (`capes` oder `cosmetics`).
pub(crate) fn parse_asset_url<'a>(bases: &[&str], url: &'a str, segment: &str, id: &str) -> Option<Option<&'a str>> {
    let rest = bases.iter().find_map(|base| url.strip_prefix(base)?.strip_prefix("/v1/")?.strip_prefix(segment)?.strip_prefix('/'))?;
    let (file, query) = match rest.split_once('?') {
        Some((f, q)) => (f, Some(q)),
        None => (rest, None),
    };
    if file.strip_suffix(".png")? != id || !validate::cape_id(id) {
        return None;
    }
    match query {
        None => Some(None),
        Some(q) => {
            let v = q.strip_prefix("v=")?;
            ((1..=64).contains(&v.len()) && v.bytes().all(|b| b.is_ascii_alphanumeric())).then_some(Some(v))
        }
    }
}

pub(crate) struct TextureSpec<'a> {
    /// Pfadteil der API: `capes` oder `cosmetics`.
    pub segment: &'static str,
    pub id: &'a str,
    pub url: &'a str,
    pub width: u32,
    /// Höhe **aller** Frames zusammen.
    pub total_height: u32,
    pub cacheable: bool,
}

impl<'a> TextureSpec<'a> {
    pub fn of(cape: &'a ApiCape) -> Self {
        Self {
            segment: "capes",
            id: &cape.id,
            url: &cape.url,
            width: cape.width,
            total_height: cape.height.saturating_mul(cape.frames.max(1)),
            cacheable: cape.status == CapeStatus::Approved,
        }
    }
}

impl TrsApi {
    fn texture_dir(&self) -> PathBuf {
        self.paths.root().join("cache").join("trs-capes")
    }

    /// Lädt eine Umhang-Textur (mit Token für eigene/zu prüfende Uploads).
    /// `None`, wenn sie fehlt oder nicht zu den Metadaten passt.
    pub(crate) async fn texture(&self, spec: &TextureSpec<'_>, token: Option<&str>) -> Option<String> {
        let version = parse_asset_url(&self.trusted_bases(), spec.url, spec.segment, spec.id)?;
        let valid = |bytes: &[u8]| png::size(bytes) == Some((spec.width, spec.total_height));
        let cache = match version {
            Some(v) if spec.cacheable => Some(self.texture_dir().join(format!("{}-{v}.png", spec.id))),
            _ => None,
        }
        .filter(|_| spec.segment == "capes");
        if let Some(file) = &cache
            && let Ok(bytes) = tokio::fs::read(file).await
            && valid(&bytes)
        {
            return Some(data_url(&bytes));
        }

        let mut request = self.http.get(spec.url).header("Accept", "image/png");
        if let Some(token) = token.filter(|_| !spec.cacheable) {
            request = request.bearer_auth(token);
        }
        let response = request.send().await.ok()?;
        if !response.status().is_success() || response.content_length().is_some_and(|l| l > MAX_TEXTURE_BYTES as u64) {
            return None;
        }
        let bytes = response.bytes().await.ok()?;
        if bytes.len() > MAX_TEXTURE_BYTES || !valid(&bytes) {
            tracing::warn!("Umhang-Textur '{}' passt nicht zu den Metadaten", spec.id);
            return None;
        }
        if let Some(file) = &cache {
            self.prune_old_versions(spec.id, file).await;
            if let Err(e) = fsutil::write_atomic(file, &bytes).await {
                tracing::debug!("Umhang-Textur konnte nicht gecacht werden: {e}");
            }
        }
        Some(data_url(&bytes))
    }

    /// Ältere Versionen desselben Umhangs aus dem Cache räumen.
    async fn prune_old_versions(&self, id: &str, keep: &std::path::Path) {
        let Ok(mut entries) = tokio::fs::read_dir(self.texture_dir()).await else { return };
        let prefix = format!("{id}-");
        while let Ok(Some(entry)) = entries.next_entry().await {
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if name.starts_with(&prefix) && name.ends_with(".png") && entry.path() != keep {
                let _ = tokio::fs::remove_file(entry.path()).await;
            }
        }
    }
}

fn data_url(bytes: &[u8]) -> String {
    format!("data:image/png;base64,{}", STANDARD.encode(bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    const BASES: [&str; 2] = crate::trs_api::KNOWN_BASES;

    #[test]
    fn only_api_texture_urls_are_loaded() {
        // Neue und alte Adresse der API sind gleichwertig.
        for host in ["https://trs-launcher.theredstonee.de", "https://api.theredstonee.de"] {
            let url = |rest: &str| format!("{host}{rest}");
            assert_eq!(parse_texture_url(&BASES, &url("/v1/capes/team.png?v=61749d72f375"), "team"), Some(Some("61749d72f375")));
            assert_eq!(parse_texture_url(&BASES, &url("/v1/capes/team.png"), "team"), Some(None));
            assert_eq!(parse_texture_url(&BASES, &url("/v1/capes/other.png"), "team"), None);
            assert_eq!(parse_texture_url(&BASES, &url("/v1/capes/team.png?v=../x"), "team"), None);
            assert_eq!(parse_texture_url(&BASES, &url(".evil/v1/capes/team.png"), "team"), None);
            assert_eq!(parse_texture_url(&BASES, &url("@evil.example/v1/capes/team.png"), "team"), None);
        }
        // Fremde Hosts bleiben verboten.
        assert_eq!(parse_texture_url(&BASES, "https://evil.example/v1/capes/team.png", "team"), None);
        assert_eq!(parse_texture_url(&BASES, "https://theredstonee.de/v1/capes/team.png", "team"), None);
        assert_eq!(parse_texture_url(&BASES, "http://trs-launcher.theredstonee.de/v1/capes/team.png", "team"), None, "nur HTTPS");
        assert_eq!(parse_texture_url(&[], "https://trs-launcher.theredstonee.de/v1/capes/team.png", "team"), None);
    }

    #[test]
    fn cosmetic_previews_use_their_own_path() {
        let url = "https://trs-launcher.theredstonee.de/v1/cosmetics/krone.png?v=abc123";
        assert_eq!(parse_asset_url(&BASES, url, "cosmetics", "krone"), Some(Some("abc123")));
        assert_eq!(parse_asset_url(&BASES, url, "capes", "krone"), None, "Pfad muss zur Art passen");
        assert_eq!(parse_asset_url(&BASES, "https://trs-launcher.theredstonee.de/v1/cosmeticsx/krone.png", "cosmetics", "krone"), None);
    }
}
