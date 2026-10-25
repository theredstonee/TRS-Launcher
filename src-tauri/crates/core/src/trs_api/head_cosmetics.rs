//! Kopf-Kosmetik für die Skins-Seite: alle Teile (besessen und gesperrt) mit
//! Vorschaubildern und – für das Kosmetik-Format v2 – Modell + Texturen für die
//! 3D-Vorschau. Alles kommt nur von der TRS API (vertrauenswürdige Adressen,
//! feste Pfade), mit Größenlimits, und landet nach Inhalts-Hash im Cache
//! `<daten>/cache/trs-cosmetics/<id>/<version>-<datei>`. Das Webview bekommt
//! nur geprüftes JSON und PNG-Data-URLs (CSP bleibt eng, WebGL braucht kein CORS).

use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde_json::Value;

use super::types::{
    ApiCosmeticCatalog, ApiCosmeticRef, CosmeticUnlock, HeadCosmetic, HeadCosmeticModel, MAX_OPTIONS, MAX_PET_EXTRAS, PetExtra,
    clean_count, clean_options, clean_scale, option_id,
};
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

/// Kann der TRS Client dieses Teil zeichnen (Kopf-Kosmetik oder Begleiter)?
pub(crate) fn wearable_hat(c: &ApiCosmeticRef) -> bool {
    match c.slot.as_str() {
        "hat" => c.is_v2() || c.template.as_deref().is_some_and(|t| WEARABLE_HAT_TEMPLATES.contains(&t)),
        // Begleiter (§11, Halloween): gibt es nur als v2-Modell.
        "companion" => c.is_v2(),
        // Flügel (seit 2026-10-16, `attach: body`): nur v2.
        "wings" => c.is_v2(),
        // Begleiter am Boden (Pinguin, seit 2026-10-24, `attach: ground`): nur v2.
        "pet" => c.is_v2(),
        _ => false,
    }
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
    // Hut/Begleiter hängen am Kopf, Flügel am Rumpf, Begleiter am Boden stehen (§3 `SLOT_ATTACH`, Anhang D).
    match (o.get("slot")?.as_str()?, o.get("attach")?.as_str()?) {
        ("hat" | "companion", "head") | ("wings", "body") | ("pet", "ground") => {}
        _ => return None,
    }
    if !options_ok(o) {
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
        && list_len(o, "animations", 0, 12)
        && list_len(o, "halos", 0, 16);
    lists.then_some(ModelInfo { texture: (pw, ph * frames), glow })
}

/// Abschaltbare Teile (Anhang D, optional): höchstens 4, je gültige eindeutige `id`, `default`
/// true/false und mindestens ein Knochen-Name. Ob die Knochen existieren, prüft der Renderer.
fn options_ok(o: &serde_json::Map<String, Value>) -> bool {
    let list = match o.get("options") {
        None => return true,
        Some(Value::Array(a)) if a.len() <= MAX_OPTIONS => a,
        Some(_) => return false,
    };
    let mut seen = std::collections::HashSet::new();
    list.iter().all(|opt| {
        let Some(opt) = opt.as_object() else { return false };
        let id = opt.get("id").and_then(Value::as_str).filter(|id| option_id(id));
        let bones = opt
            .get("bones")
            .and_then(Value::as_array)
            .is_some_and(|b| !b.is_empty() && b.len() <= 32 && b.iter().all(Value::is_string));
        id.is_some_and(|id| seen.insert(id.to_owned())) && bones && opt.get("default").is_some_and(Value::is_boolean)
    })
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
            // Gefolge-Modelle (nicht im Katalog) bleiben bekannt; alles andere wird ersetzt.
            known.retain(|id, _| self.extra_ids.lock().is_ok_and(|e| e.contains(id)));
            known.extend(sources.clone());
        }
        sources
    }

    /// Zusätzliche Begleiter (`equipped.pet.extras`) prüfen und ihre Quellen merken – sie stehen oft nicht im Katalog.
    fn remember_extras(&self, raw: &[Value]) -> Vec<PetExtra> {
        let trusted = self.trusted_bases();
        let mut out = Vec::new();
        let mut ids = Vec::new();
        for v in raw.iter().take(MAX_PET_EXTRAS) {
            let Some(mut o) = v.as_object().cloned() else { continue };
            o.entry("slot").or_insert_with(|| "pet".into());
            o.entry("format").or_insert_with(|| 2.into());
            let Ok(c) = serde_json::from_value::<ApiCosmeticRef>(Value::Object(o)) else { continue };
            if c.slot != "pet" || !validate::cape_id(&c.id) {
                continue;
            }
            let source = v2_source(&self.base, &trusted, &c);
            out.push(PetExtra {
                name: validate::cape_name(&c.name, &c.id),
                pet_scale: clean_scale(c.pet_scale.as_ref()).unwrap_or(1.0),
                hash: source.as_ref().map(|s| s.hash.clone()),
                preview: source.is_some(),
                id: c.id.clone(),
            });
            if let Some(s) = source {
                ids.push(c.id.clone());
                if let Ok(mut known) = self.head_sources.lock() {
                    known.insert(c.id, s);
                }
            }
        }
        if let Ok(mut e) = self.extra_ids.lock() {
            *e = ids;
        }
        out
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

/// Extras eines getragenen Teils aus `GET /v1/me/cosmetics` (Anhang D): Optionen und – beim
/// Begleiter am Boden – die Anzahl (Creator-Stufe).
#[derive(Debug, Default, Clone)]
pub(crate) struct Worn {
    pub options: BTreeMap<String, bool>,
    pub count: Option<u8>,
    pub creator: bool,
    /// Größe des Begleiters am Boden (`equipped.pet.petScale`).
    pub pet_scale: Option<f32>,
    /// Gefolge (`equipped.pet.extras`), roh – geprüft wird in [`TrsApi::remember_extras`].
    pub extras: Vec<Value>,
}

/// `equipped.<slot>` = CosmeticView (`{ id, options?, count?, source? }`) je Fach; ältere Antworten
/// ohne Extras ergeben leere Einträge. Ein oberstes `options: { <slot>: {…} }` gilt als Rückfall.
pub(crate) fn worn_extras(me: &Value) -> HashMap<String, Worn> {
    let mut out = HashMap::new();
    let Some(equipped) = me.get("equipped").and_then(Value::as_object) else { return out };
    for (slot, view) in equipped {
        let Some(id) = view.get("id").and_then(Value::as_str).filter(|id| validate::cape_id(id)) else { continue };
        let mut options = clean_options(view.get("options"));
        if options.is_empty() {
            options = clean_options(me.get("options").and_then(|o| o.get(slot)));
        }
        let count = if slot == "pet" { clean_count(view.get("count")) } else { None };
        let creator = view.get("source").and_then(Value::as_str) == Some("creator") || count.is_some_and(|n| n > 1);
        let pet_scale = if slot == "pet" { clean_scale(view.get("petScale")) } else { None };
        let extras = if slot == "pet" {
            view.get("extras").and_then(Value::as_array).map(|a| a.iter().take(MAX_PET_EXTRAS).cloned().collect()).unwrap_or_default()
        } else {
            Vec::new()
        };
        out.insert(id.to_owned(), Worn { options, count, creator, pet_scale, extras });
    }
    out
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
        // Getragenes mit Optionen/Anzahl kommt aus `me/cosmetics` – fehlt das (ältere API), geht es ohne.
        let (catalog, me) = futures::future::join(
            self.trs_get::<ApiCosmeticCatalog>(Req::get("/v1/cosmetics")),
            self.trs_get::<Value>(Req::get("/v1/me/cosmetics")),
        )
        .await;
        let catalog = catalog?;
        let worn = me.map(|m| worn_extras(&m)).unwrap_or_else(|e| {
            tracing::debug!("Getragene Kosmetik (Optionen/Anzahl) nicht geladen: {e}");
            HashMap::new()
        });
        let sources = self.trs.remember_sources(&catalog.cosmetics);
        let extras = worn.values().find(|w| !w.extras.is_empty()).map(|w| self.trs.remember_extras(&w.extras)).unwrap_or_default();
        let trs = &self.trs;
        let jobs = catalog.cosmetics.into_iter().filter(listed).take(MAX_ITEMS).map(|c| {
            let source = sources.get(&c.id).cloned();
            let gang = extras.clone();
            let extra = worn.get(&c.id).cloned().filter(|_| c.equipped).unwrap_or_else(|| Worn {
                // Rückfall: Angaben direkt am Katalog-Eintrag.
                options: if c.equipped { clean_options(c.options.as_ref()) } else { BTreeMap::new() },
                count: if c.equipped && c.slot == "pet" { clean_count(c.count.as_ref()) } else { None },
                creator: c.source.as_deref() == Some("creator"),
                pet_scale: None,
                extras: Vec::new(),
            });
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
                let worn_pet = c.equipped && c.slot == "pet";
                HeadCosmetic {
                    pet_scale: if c.slot == "pet" { extra.pet_scale.or_else(|| clean_scale(c.pet_scale.as_ref())).unwrap_or(1.0) } else { 1.0 },
                    extras: if worn_pet { gang } else { Vec::new() },
                    name: validate::cape_name(&c.name, &c.id),
                    format: if v2 { 2 } else { 1 },
                    template: if v2 { None } else { c.template.as_deref().map(|t| validate::text(t, 32)) },
                    unlock: c.unlock_kind().unwrap_or(CosmeticUnlock::Other),
                    unlock_event: c.unlock_event(),
                    slot: c.slot.clone(),
                    owned: c.owned,
                    equipped: c.equipped,
                    preview: source.is_some(),
                    hash: source.as_ref().map(|s| s.hash.clone()),
                    card,
                    card_night,
                    frames: c.frames.unwrap_or(1).clamp(1, MAX_FRAMES),
                    glow_frames: c.glow_frames.unwrap_or(0).min(MAX_FRAMES),
                    count: extra.count,
                    creator: extra.creator || c.source.as_deref() == Some("creator"),
                    options: extra.options,
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
