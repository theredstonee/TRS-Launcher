//! Kopf-Kosmetik für die Skins-Seite: alle Teile (besessen und gesperrt) mit
//! Vorschaubildern und – für das Kosmetik-Format v2 – Modell + Texturen für die
//! 3D-Vorschau. Alles kommt nur von der TRS API (vertrauenswürdige Adressen,
//! feste Pfade), mit Größenlimits, und landet nach Inhalts-Hash im Cache
//! `<daten>/cache/trs-cosmetics/<id>/<version>-<datei>`. Das Webview bekommt
//! nur geprüftes JSON und PNG-Data-URLs (CSP bleibt eng, WebGL braucht kein CORS).

use std::collections::HashMap;
use std::path::PathBuf;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde_json::Value;

use super::types::{ApiCosmeticCatalog, ApiCosmeticRef, CosmeticUnlock, HeadCosmetic, HeadCosmeticModel};
use super::{Req, TrsApi, png, validate};
use crate::{Error, Launcher, Result, fsutil};

/// Kopf-Vorlagen (v1), die der TRS Client zeichnen kann – v2-Modelle kann er alle.
pub const WEARABLE_HAT_TEMPLATES: &[&str] = &["duck"];

/// Größtes `model.json` (64 Würfel mit UVs brauchen gut 60 KB).
pub(crate) const MAX_MODEL_BYTES: usize = 512 * 1024;
/// Texturen: höchstens 1024 × 4096 px (Streifen).
const MAX_TEXTURE_BYTES: usize = 8 * 1024 * 1024;
/// Vorschaubilder der Karten (die API liefert höchstens 512 px).
const MAX_CARD_BYTES: usize = 2 * 1024 * 1024;
const MAX_CARD_EDGE: u32 = 1024;
/// So viele Einträge zeigt die Liste höchstens.
const MAX_ITEMS: usize = 200;

/// Formatgrenzen (siehe `cosmetic-format.md` §10).
const MAX_EDGE: u32 = 1024;
const MAX_STRIP: u32 = 4096;
const MAX_FRAMES: u32 = 16;
const SCALES: [u32; 5] = [1, 2, 4, 8, 16];

/// Kann der TRS Client dieses Teil auf dem Kopf zeichnen?
pub(crate) fn wearable_hat(c: &ApiCosmeticRef) -> bool {
    c.slot == "hat" && (c.is_v2() || c.template.as_deref().is_some_and(|t| WEARABLE_HAT_TEMPLATES.contains(&t)))
}

/// Gehört das Teil in die Liste? Versteckte Teile nur, wenn man sie besitzt.
fn listed(c: &ApiCosmeticRef) -> bool {
    wearable_hat(c) && validate::cape_id(&c.id) && (!c.hidden || c.owned || c.equipped)
}

/// Dateien eines v2-Teils.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Asset {
    Model,
    Texture,
    Glow,
    Card,
    CardNight,
}

impl Asset {
    /// Pfad hinter `/v1/cosmetics/<id>`.
    fn suffix(self) -> &'static str {
        match self {
            Self::Model => "/model.json",
            Self::Texture => ".png",
            Self::Glow => "/glow.png",
            Self::Card => "/card.png",
            Self::CardNight => "/card-night.png",
        }
    }

    fn file(self) -> &'static str {
        match self {
            Self::Model => "model.json",
            Self::Texture => "texture.png",
            Self::Glow => "glow.png",
            Self::Card => "card.png",
            Self::CardNight => "card-night.png",
        }
    }

    fn limit(self) -> usize {
        match self {
            Self::Model => MAX_MODEL_BYTES,
            Self::Texture | Self::Glow => MAX_TEXTURE_BYTES,
            Self::Card | Self::CardNight => MAX_CARD_BYTES,
        }
    }

    fn accept(self) -> &'static str {
        if self == Self::Model { "application/json" } else { "image/png" }
    }
}

/// Geprüfte Adresse einer Datei: absolute URL + Version (`?v=`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct AssetUrl {
    pub url: String,
    pub version: Option<String>,
}

/// `?v=`-Werte und Hashes: 1–64 Zeichen `A–Z a–z 0–9`.
fn version_ok(v: &str) -> bool {
    (1..=64).contains(&v.len()) && v.bytes().all(|b| b.is_ascii_alphanumeric())
}

/// Adresse einer v2-Datei: absolut oder API-relativ (`/v1/…`), nur bei einer der
/// vertrauenswürdigen API-Adressen und genau mit dem erwarteten Pfad
/// `/v1/cosmetics/<id><suffix>[?v=<version>]`.
pub(crate) fn asset_url(base: &str, trusted: &[&str], raw: &str, id: &str, asset: Asset) -> Option<AssetUrl> {
    if !validate::cape_id(id) || raw.len() > 512 {
        return None;
    }
    let url = if raw.starts_with('/') && !raw.starts_with("//") { format!("{base}{raw}") } else { raw.to_owned() };
    let rest = trusted.iter().find_map(|b| url.strip_prefix(b)?.strip_prefix("/v1/cosmetics/"))?;
    let (file, query) = match rest.split_once('?') {
        Some((f, q)) => (f, Some(q)),
        None => (rest, None),
    };
    if file.strip_prefix(id)? != asset.suffix() {
        return None;
    }
    let version = match query {
        None => None,
        Some(q) => Some(q.strip_prefix("v=").filter(|v| version_ok(v))?.to_owned()),
    };
    Some(AssetUrl { url, version })
}

/// Woher die Dateien eines v2-Teils kommen (aus dem Katalog).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct V2Source {
    pub hash: String,
    pub model: AssetUrl,
    pub texture: AssetUrl,
    pub glow: Option<AssetUrl>,
    pub card: Option<AssetUrl>,
    pub card_night: Option<AssetUrl>,
}

/// v2-Angaben eines Katalog-Eintrags prüfen. Ohne gültiges Modell/Textur: `None`
/// (keine 3D-Vorschau); fehlerhafte Glow-/Karten-Adressen fallen einzeln weg.
pub(crate) fn v2_source(base: &str, trusted: &[&str], c: &ApiCosmeticRef) -> Option<V2Source> {
    if !c.is_v2() {
        return None;
    }
    let url = |raw: Option<&str>, asset| raw.and_then(|r| asset_url(base, trusted, r, &c.id, asset));
    let model = url(c.model.as_deref(), Asset::Model)?;
    let texture = url(c.texture_url(), Asset::Texture)?;
    let hash = c
        .hash
        .as_deref()
        .map(str::to_ascii_lowercase)
        .filter(|h| version_ok(h))
        .or_else(|| model.version.clone())?;
    Some(V2Source {
        hash,
        glow: url(c.glow.as_deref(), Asset::Glow),
        card: url(c.card.as_deref(), Asset::Card),
        card_night: url(c.card_night.as_deref(), Asset::CardNight),
        model,
        texture,
    })
}

/// Bildgrößen, die das Modell verlangt (echte Pixel, ganzer Streifen).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct ModelInfo {
    pub texture: (u32, u32),
    pub glow: Option<(u32, u32)>,
}

fn uint(o: &serde_json::Map<String, Value>, key: &str) -> Option<u32> {
    o.get(key)?.as_u64().and_then(|v| u32::try_from(v).ok())
}

/// Frames + Frame-Dauer eines Streifens (fehlt = 1 Frame).
fn frames_of(o: &serde_json::Map<String, Value>) -> Option<u32> {
    let frames = match o.get("frames") {
        None | Some(Value::Null) => 1,
        Some(_) => uint(o, "frames")?,
    };
    if !(1..=MAX_FRAMES).contains(&frames) {
        return None;
    }
    if frames > 1 && !uint(o, "frameTimeMs").is_some_and(|t| (16..=10_000).contains(&t)) {
        return None;
    }
    Some(frames)
}

fn list_len(o: &serde_json::Map<String, Value>, key: &str, min: usize, max: usize) -> bool {
    match o.get(key) {
        None | Some(Value::Null) => min == 0,
        Some(Value::Array(a)) => (min..=max).contains(&a.len()),
        Some(_) => false,
    }
}

/// Grobe Prüfung von `model.json` (Format, Kennung, Grenzen, Bildmaße). Die
/// vollständige Prüfung (Knochen, UVs, Animationen) macht der Renderer vor dem Bauen.
pub(crate) fn check_model(model: &Value, id: &str) -> Option<ModelInfo> {
    let o = model.as_object()?;
    if uint(o, "format")? != 2 || o.get("id")?.as_str()? != id {
        return None;
    }
    if o.get("slot")?.as_str()? != "hat" || o.get("attach")?.as_str()? != "head" {
        return None;
    }
    let tex = o.get("texture")?.as_object()?;
    let (w, h, scale) = (uint(tex, "width")?, uint(tex, "height")?, uint(tex, "scale")?);
    if w < 8 || h < 8 || w % 8 != 0 || h % 8 != 0 || !SCALES.contains(&scale) {
        return None;
    }
    let (pw, ph) = (w * scale, h * scale);
    if pw > MAX_EDGE || ph > MAX_EDGE {
        return None;
    }
    let frames = frames_of(tex)?;
    if ph * frames > MAX_STRIP {
        return None;
    }
    let glow = match o.get("glow") {
        None | Some(Value::Null) => None,
        Some(g) => {
            let g = g.as_object()?;
            let frames = frames_of(g)?;
            if ph * frames > MAX_STRIP {
                return None;
            }
            Some((pw, ph * frames))
        }
    };
    let lists = list_len(o, "bones", 1, 32)
        && list_len(o, "cubes", 1, 64)
        && list_len(o, "animations", 0, 8)
        && list_len(o, "halos", 0, 16);
    lists.then_some(ModelInfo { texture: (pw, ph * frames), glow })
}

fn data_url(bytes: &[u8]) -> String {
    format!("data:image/png;base64,{}", STANDARD.encode(bytes))
}

fn card_ok(bytes: &[u8]) -> bool {
    png::size(bytes).is_some_and(|(w, h)| (1..=MAX_CARD_EDGE).contains(&w) && (1..=MAX_CARD_EDGE).contains(&h))
}

fn preview_failed() -> Error {
    super::trs_error(
        "trs_api",
        "cosmetic_unavailable",
        crate::msg!("trs.cosmeticPreviewFailed", "Die Vorschau dieses Teils konnte nicht geladen werden."),
    )
}

impl TrsApi {
    fn cosmetic_dir(&self, id: &str) -> PathBuf {
        self.paths.root().join("cache").join("trs-cosmetics").join(id)
    }

    /// v2-Quellen aus einem Katalog merken (für die spätere Modell-Abfrage).
    fn remember_sources(&self, catalog: &[ApiCosmeticRef]) -> HashMap<String, V2Source> {
        let trusted = self.trusted_bases();
        let sources: HashMap<_, _> = catalog
            .iter()
            .filter(|c| listed(c))
            .filter_map(|c| Some((c.id.clone(), v2_source(&self.base, &trusted, c)?)))
            .collect();
        if let Ok(mut known) = self.head_sources.lock() {
            known.clone_from(&sources);
        }
        sources
    }

    fn known_source(&self, id: &str) -> Option<V2Source> {
        self.head_sources.lock().ok()?.get(id).cloned()
    }

    /// Eine Datei holen: erst aus dem Cache (Version = `?v=` bzw. Hash), sonst
    /// ohne Token von der API (öffentliche Route), mit Größenlimit und Prüfung.
    async fn cosmetic_file(
        &self,
        id: &str,
        hash: &str,
        source: &AssetUrl,
        asset: Asset,
        valid: impl Fn(&[u8]) -> bool,
    ) -> Option<Vec<u8>> {
        let version = source.version.as_deref().unwrap_or(hash);
        let dir = self.cosmetic_dir(id);
        let file = dir.join(format!("{version}-{}", asset.file()));
        if let Ok(bytes) = tokio::fs::read(&file).await
            && bytes.len() <= asset.limit()
            && valid(&bytes)
        {
            return Some(bytes);
        }
        let response = self.http.get(&source.url).header("Accept", asset.accept()).send().await.ok()?;
        if !response.status().is_success() || response.content_length().is_some_and(|l| l > asset.limit() as u64) {
            return None;
        }
        let bytes = response.bytes().await.ok()?;
        if bytes.len() > asset.limit() || !valid(&bytes) {
            tracing::warn!("Kosmetik-Datei '{id}' ({}) passt nicht zu den Angaben", asset.file());
            return None;
        }
        prune_versions(&dir, asset, &file).await;
        if let Err(e) = fsutil::write_atomic(&file, &bytes).await {
            tracing::debug!("Kosmetik-Datei konnte nicht gecacht werden: {e}");
        }
        Some(bytes.to_vec())
    }

    async fn card(&self, id: &str, hash: &str, source: Option<&AssetUrl>, asset: Asset) -> Option<String> {
        let bytes = self.cosmetic_file(id, hash, source?, asset, card_ok).await?;
        Some(data_url(&bytes))
    }

    /// Modell + Texturen eines v2-Teils laden und gegeneinander prüfen.
    async fn head_model(&self, id: &str, source: &V2Source) -> Result<HeadCosmeticModel> {
        let parse = |bytes: &[u8]| serde_json::from_slice::<Value>(bytes).ok().filter(|m| check_model(m, id).is_some());
        let bytes = self
            .cosmetic_file(id, &source.hash, &source.model, Asset::Model, |b| parse(b).is_some())
            .await
            .ok_or_else(preview_failed)?;
        let model = parse(&bytes).ok_or_else(preview_failed)?;
        let info = check_model(&model, id).ok_or_else(preview_failed)?;
        let texture = self
            .cosmetic_file(id, &source.hash, &source.texture, Asset::Texture, |b| png::size(b) == Some(info.texture))
            .await
            .ok_or_else(preview_failed)?;
        // Ohne Leucht-Schicht geht es auch (nur ohne Glühen).
        let glow = match (info.glow, &source.glow) {
            (Some(size), Some(url)) => {
                self.cosmetic_file(id, &source.hash, url, Asset::Glow, |b| png::size(b) == Some(size)).await
            }
            _ => None,
        };
        Ok(HeadCosmeticModel {
            id: id.to_owned(),
            hash: source.hash.clone(),
            model,
            texture: data_url(&texture),
            glow: glow.map(|g| data_url(&g)),
        })
    }
}

/// Ältere Versionen derselben Datei aus dem Cache räumen.
async fn prune_versions(dir: &std::path::Path, asset: Asset, keep: &std::path::Path) {
    let Ok(mut entries) = tokio::fs::read_dir(dir).await else { return };
    let suffix = format!("-{}", asset.file());
    while let Ok(Some(entry)) = entries.next_entry().await {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        // `card.png` darf `card-night.png` nicht mitnehmen: der Teil vor dem Suffix ist die Version (ohne `-`).
        let version = name.strip_suffix(&suffix);
        if version.is_some_and(version_ok) && entry.path() != keep {
            let _ = tokio::fs::remove_file(entry.path()).await;
        }
    }
}

impl Launcher {
    /// Alle Kopf-Kosmetik-Teile, die der TRS Client zeichnen kann – besessene und
    /// gesperrte (versteckte nur, wenn man sie hat) – mit Vorschaubildern.
    pub async fn trs_head_cosmetics(&self) -> Result<Vec<HeadCosmetic>> {
        let catalog: ApiCosmeticCatalog = self.trs_get(Req::get("/v1/cosmetics")).await?;
        let sources = self.trs.remember_sources(&catalog.cosmetics);
        let trs = &self.trs;
        let jobs = catalog.cosmetics.into_iter().filter(listed).take(MAX_ITEMS).map(|c| {
            let source = sources.get(&c.id).cloned();
            async move {
                let (card, card_night) = match &source {
                    Some(s) => {
                        futures::future::join(
                            trs.card(&c.id, &s.hash, s.card.as_ref(), Asset::Card),
                            trs.card(&c.id, &s.hash, s.card_night.as_ref(), Asset::CardNight),
                        )
                        .await
                    }
                    None => (None, None),
                };
                let v2 = c.is_v2();
                HeadCosmetic {
                    name: validate::cape_name(&c.name, &c.id),
                    format: if v2 { 2 } else { 1 },
                    template: if v2 { None } else { c.template.as_deref().map(|t| validate::text(t, 32)) },
                    unlock: c.unlock.unwrap_or(CosmeticUnlock::Other),
                    owned: c.owned,
                    equipped: c.equipped,
                    preview: source.is_some(),
                    hash: source.as_ref().map(|s| s.hash.clone()),
                    card,
                    card_night,
                    frames: c.frames.unwrap_or(1).clamp(1, MAX_FRAMES),
                    glow_frames: c.glow_frames.unwrap_or(0).min(MAX_FRAMES),
                    id: c.id,
                }
            }
        });
        Ok(futures::future::join_all(jobs).await)
    }

    /// Modell + Texturen eines v2-Teils für die 3D-Vorschau (auch gesperrte Teile – zum Anprobieren).
    pub async fn trs_head_cosmetic_model(&self, id: &str) -> Result<HeadCosmeticModel> {
        if !validate::cape_id(id) {
            return Err(Error::validation(crate::msg!("trsOps.invalidCosmeticId", "Ungültige Kosmetik-ID.")));
        }
        let source = match self.trs.known_source(id) {
            Some(s) => s,
            None => {
                let catalog: ApiCosmeticCatalog = self.trs_get(Req::get("/v1/cosmetics")).await?;
                self.trs.remember_sources(&catalog.cosmetics).remove(id).ok_or_else(preview_failed)?
            }
        };
        self.trs.head_model(id, &source).await
    }
}

#[cfg(test)]
#[path = "head_cosmetics_tests.rs"]
mod tests;
