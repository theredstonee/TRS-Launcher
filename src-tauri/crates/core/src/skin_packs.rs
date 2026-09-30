//! Offizielle Skin-Pakete von Mojang zum Auswählen auf der Skins-Seite.
//!
//! Mojang verschenkt zu Updates und Community-Aktionen Skins für die Java
//! Edition – als ZIP mit 64×64-PNGs zum Download auf minecraft.net. Genau diese
//! Dateien lädt der Kern: nur die fest eingetragenen Adressen (Allowlist),
//! nur HTTPS, Weiterleitungen nur innerhalb von minecraft.net, ZIP höchstens
//! 1 MB, jeder Skin wird wie ein eigener geprüft (PNG, 64×64/64×32, 128 KB).
//! Das Ergebnis liegt im Cache (`<daten>/cache/skin-packs/`); ohne Internet
//! wird der Cache benutzt, ohne Cache fehlt das Paket einfach.
//!
//! Ausgewählt wird per ID `pack/<paket>/<skin>`; beim Anwenden lädt der Kern
//! die Textur aus dem Cache hoch (siehe [`crate::skin_sync`]).

use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::Duration;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::skin_import::detect_variant;
use crate::skins::{self, SkinVariant};
use crate::{Error, Launcher, Result, fsutil};

/// Ablage der Java-Skins auf minecraft.net (Zielort nach Mojangs eigenen Weiterleitungen).
const BASE: &str = "https://www.minecraft.net/content/dam/minecraftnet/games/minecraft/software/";
/// Hosts, auf die Downloads (auch nach einer Weiterleitung) zeigen dürfen.
const HOSTS: [&str; 2] = ["www.minecraft.net", "minecraft.net"];
const MAX_REDIRECTS: usize = 3;
/// Ein Paket-ZIP ist wenige KB groß – mehr als 1 MB ist keins.
const MAX_ZIP_BYTES: usize = 1024 * 1024;
const MAX_ENTRIES: usize = 64;
const MAX_SKINS: usize = 24;
/// So lange gilt der Cache, danach wird (still) neu geladen.
const TTL: Duration = Duration::from_secs(7 * 24 * 60 * 60);

/// Ein offizielles Paket: Name, Erscheinungstag, ZIP-Dateien und die offiziellen Skin-Namen.
struct PackDef {
    id: &'static str,
    name: &'static str,
    released: &'static str,
    files: &'static [&'static str],
    /// Dateiname (ohne `.png`) → Name des Skins; fehlt ein Eintrag, wird der Dateiname lesbar gemacht.
    names: &'static [(&'static str, &'static str)],
}

/// Neueste zuerst. Nur Pakete, die Mojang heute noch selbst ausliefert
/// (MINECON Earth 2017 lag auf einem inzwischen abgeschalteten Server).
const PACKS: &[PackDef] = &[
    PackDef {
        id: "chaos-cubed",
        name: "Chaos Cubed",
        released: "2026-06-17",
        files: &["summer-drop-2026-java-skins.zip"],
        names: &[("skin_brawler", "Brawler"), ("skin_builder", "Builder"), ("skin_rancher", "Rancher")],
    },
    PackDef {
        id: "tiny-takeover",
        name: "Tiny Takeover",
        released: "2026-04-06",
        files: &["Java_Skins.zip"],
        names: &[],
    },
    PackDef {
        id: "mounts-of-mayhem",
        name: "Mounts of Mayhem",
        released: "2025-12-15",
        files: &["Zombie_Horse_Onesie_Skin.zip"],
        names: &[("Zombie_Horse_Onesie_Skin", "Zombie Horse Onesie")],
    },
    PackDef {
        id: "the-copper-age",
        name: "The Copper Age",
        released: "2025-10-08",
        files: &["CopperChemist-Java-Skin.zip", "CopperWelder-Java-Skin.zip"],
        names: &[("CopperChemist-Java-Skin", "Copper Chemist"), ("CopperWelder-Java-Skin", "Copper Welder")],
    },
    PackDef {
        id: "chase-the-skies",
        name: "Chase the Skies",
        released: "2025-06-26",
        files: &["ChaseTheSkies_JavaSkins.zip"],
        names: &[("ghastswimmerskin", "Ghast Riding Swimmer"), ("ghastpilotskin", "Happy Ghast Pilot")],
    },
    PackDef {
        id: "the-garden-awakens",
        name: "The Garden Awakens",
        released: "2024-12-05",
        files: &["TheGardenAwakens_JavaSkin_CreakingSkin_B.zip"],
        names: &[("TheGardenAwakens_JavaSkin_CreakingSkin_B", "Creaking Skin"), ("PaleLumberjack_02", "Pale Lumberjack")],
    },
    PackDef {
        id: "striding-hero",
        name: "Striding Hero",
        released: "2020-12-14",
        files: &["striding-hero-skinpack.zip"],
        names: &[],
    },
    PackDef {
        id: "builders-and-biomes",
        name: "Builders & Biomes",
        released: "2020-10-13",
        files: &["farmers-market-skin-files.zip"],
        names: &[],
    },
];

/// Ein Skin aus einem Paket.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
struct PackSkin {
    /// Kurzname aus dem Dateinamen (`[a-z0-9-]`), eindeutig im Paket.
    key: String,
    name: String,
    variant: SkinVariant,
    #[serde(with = "b64")]
    png: Vec<u8>,
}

mod b64 {
    use base64::Engine;
    use base64::engine::general_purpose::STANDARD;
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn serialize<S: Serializer>(bytes: &[u8], s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&STANDARD.encode(bytes))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<u8>, D::Error> {
        let text = String::deserialize(d)?;
        STANDARD.decode(text).map_err(serde::de::Error::custom)
    }
}

/// Cache-Datei eines Pakets.
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CachedPack {
    fetched_at: DateTime<Utc>,
    skins: Vec<PackSkin>,
}

/// Was das Webview sieht: Paket mit Skins als Data-URL.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SkinPackView {
    pub id: String,
    pub name: String,
    /// Erscheinungstag `YYYY-MM-DD`.
    pub released: String,
    pub skins: Vec<SelectableSkin>,
}

/// Ein auswählbarer Skin (Standard-Skin oder aus einem Paket).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SelectableSkin {
    /// `wide/steve` bzw. `pack/<paket>/<skin>` – so geht er beim Anwenden an den Kern.
    pub id: String,
    pub name: String,
    pub variant: SkinVariant,
    pub texture: String,
}

/// Alle Standard-Skins aus einem installierten Client.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BuiltinSkinsView {
    pub version: Option<String>,
    pub skins: Vec<SelectableSkin>,
}

// --- Prüfen -------------------------------------------------------------------------------

/// Nur HTTPS, nur minecraft.net, Standard-Port, keine Zugangsdaten.
fn is_allowed_url(url: &reqwest::Url) -> bool {
    url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none()
        && url.host_str().is_some_and(|h| HOSTS.contains(&h.to_ascii_lowercase().as_str()))
}

/// Kurzname für Dateinamen: `Barn Builder skin` → `barn-builder-skin`.
fn slug(stem: &str) -> String {
    let mut out = String::new();
    for c in stem.chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c.to_ascii_lowercase());
        } else if !out.ends_with('-') && !out.is_empty() {
            out.push('-');
        }
    }
    out.trim_end_matches('-').chars().take(48).collect()
}

/// Lesbarer Name aus einem Dateinamen: `villager1` → `Villager 1`, `Barn Builder skin` → `Barn Builder`.
fn pretty_name(stem: &str) -> String {
    let mut words: Vec<String> = Vec::new();
    for raw in stem.split(|c: char| c == '_' || c.is_whitespace()) {
        if raw.is_empty() || raw.eq_ignore_ascii_case("skin") {
            continue;
        }
        // Ziffern am Ende abtrennen (villager1 → villager 1).
        let split = raw.find(|c: char| c.is_ascii_digit()).filter(|&i| i > 0 && raw[i..].chars().all(|c| c.is_ascii_digit()));
        let parts: Vec<&str> = match split {
            Some(i) => vec![&raw[..i], &raw[i..]],
            None => vec![raw],
        };
        for part in parts {
            let mut chars = part.chars();
            if let Some(first) = chars.next() {
                words.push(first.to_uppercase().chain(chars).collect());
            }
        }
    }
    let name = words.join(" ");
    if name.is_empty() { "Skin".to_owned() } else { name.chars().take(48).collect() }
}

/// Liest die Skins aus einem Paket-ZIP: nur `.png` (ohne `__MACOSX`/versteckte Dateien),
/// jeder wie ein eigener Skin geprüft; kaputte Einträge fallen weg.
fn skins_from_zip(bytes: &[u8], def: &PackDef) -> Vec<PackSkin> {
    let Ok(mut archive) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) else {
        return Vec::new();
    };
    let mut skins: Vec<PackSkin> = Vec::new();
    for index in 0..archive.len().min(MAX_ENTRIES) {
        let Ok(entry) = archive.by_index(index) else { continue };
        if !entry.is_file() || entry.size() > skins::MAX_SKIN_BYTES as u64 {
            continue;
        }
        let path = entry.name().replace('\\', "/");
        if path.split('/').any(|part| part == "__MACOSX" || part.starts_with('.')) {
            continue;
        }
        let file = path.rsplit('/').next().unwrap_or_default().to_owned();
        let Some(stem) = file.strip_suffix(".png").or_else(|| file.strip_suffix(".PNG")) else { continue };
        let stem = stem.to_owned();
        let mut png = Vec::new();
        if entry.take(skins::MAX_SKIN_BYTES as u64 + 1).read_to_end(&mut png).is_err()
            || skins::validate_skin_png(&png).is_err()
        {
            continue;
        }
        let key = slug(&stem);
        if key.is_empty() || skins.iter().any(|s| s.key == key) {
            continue;
        }
        let name = def
            .names
            .iter()
            .find(|(file, _)| file.eq_ignore_ascii_case(&stem))
            .map_or_else(|| pretty_name(&stem), |(_, name)| (*name).to_owned());
        skins.push(PackSkin { key, name, variant: detect_variant(&png), png });
        if skins.len() >= MAX_SKINS {
            break;
        }
    }
    skins
}

// --- Laden ---------------------------------------------------------------------------------

fn client() -> Result<reqwest::Client> {
    let policy = reqwest::redirect::Policy::custom(|attempt| {
        if attempt.previous().len() > MAX_REDIRECTS || !is_allowed_url(attempt.url()) {
            attempt.stop()
        } else {
            attempt.follow()
        }
    });
    Ok(reqwest::Client::builder()
        .user_agent(crate::USER_AGENT)
        .redirect(policy)
        .https_only(true)
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        .build()?)
}

/// Lädt ein ZIP (höchstens [`MAX_ZIP_BYTES`], nur von minecraft.net).
async fn download(http: &reqwest::Client, file: &str) -> Result<Vec<u8>> {
    let url = reqwest::Url::parse(&format!("{BASE}{file}")).map_err(|e| Error::Internal(e.to_string()))?;
    if !is_allowed_url(&url) {
        return Err(Error::Internal(format!("Skin-Paket-Adresse nicht erlaubt: {url}")));
    }
    let mut response = http.get(url).send().await?.error_for_status()?;
    if !is_allowed_url(response.url()) {
        return Err(Error::Internal("Skin-Paket: Weiterleitung nicht erlaubt".into()));
    }
    let too_large = || Error::Internal("Skin-Paket ist zu groß".into());
    if response.content_length().is_some_and(|len| len > MAX_ZIP_BYTES as u64) {
        return Err(too_large());
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await? {
        if bytes.len() + chunk.len() > MAX_ZIP_BYTES {
            return Err(too_large());
        }
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}

fn cache_dir(cache_root: &Path) -> PathBuf {
    cache_root.join("skin-packs")
}

fn cache_file(cache_root: &Path, id: &str) -> PathBuf {
    cache_dir(cache_root).join(format!("{id}.json"))
}

async fn read_cache(cache_root: &Path, id: &str) -> Option<CachedPack> {
    let bytes = tokio::fs::read(cache_file(cache_root, id)).await.ok()?;
    let cached: CachedPack = serde_json::from_slice(&bytes).ok()?;
    // Auch aus dem Cache nur, was die Prüfung besteht.
    let valid = cached.skins.iter().all(|s| skins::validate_skin_png(&s.png).is_ok() && !s.key.is_empty());
    valid.then_some(cached)
}

async fn fetch_pack(http: &reqwest::Client, def: &PackDef) -> Result<Vec<PackSkin>> {
    let mut all: Vec<PackSkin> = Vec::new();
    for file in def.files {
        for skin in skins_from_zip(&download(http, file).await?, def) {
            if !all.iter().any(|s| s.key == skin.key) && all.len() < MAX_SKINS {
                all.push(skin);
            }
        }
    }
    Ok(all)
}

/// Ein Paket: frischer Cache direkt, sonst laden (bei Fehler den alten Cache nehmen).
async fn load_pack(http: &reqwest::Client, cache_root: &Path, def: &PackDef) -> Option<Vec<PackSkin>> {
    let cached = read_cache(cache_root, def.id).await;
    let fresh = cached
        .as_ref()
        .is_some_and(|c| Utc::now().signed_duration_since(c.fetched_at).to_std().is_ok_and(|age| age < TTL));
    if fresh {
        return cached.map(|c| c.skins);
    }
    match fetch_pack(http, def).await {
        Ok(skins) if !skins.is_empty() => {
            let entry = CachedPack { fetched_at: Utc::now(), skins };
            match serde_json::to_vec(&entry) {
                Ok(json) => {
                    if let Err(e) = fsutil::write_atomic(&cache_file(cache_root, def.id), &json).await {
                        tracing::warn!("Skin-Paket {} nicht zwischengespeichert: {e}", def.id);
                    }
                }
                Err(e) => tracing::warn!("Skin-Paket {} nicht serialisierbar: {e}", def.id),
            }
            Some(entry.skins)
        }
        Ok(_) => cached.map(|c| c.skins),
        Err(e) => {
            tracing::info!("Skin-Paket {} nicht geladen: {e}", def.id);
            cached.map(|c| c.skins)
        }
    }
}

/// Alle offiziellen Pakete, die gerade verfügbar sind (online oder aus dem Cache).
/// `cache_root` = `<daten>/cache`.
pub async fn load_all(cache_root: &Path) -> Vec<SkinPackView> {
    static LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());
    let _guard = LOCK.lock().await;
    let http = match client() {
        Ok(http) => http,
        Err(e) => {
            tracing::warn!("Skin-Pakete: kein HTTP-Client: {e}");
            return Vec::new();
        }
    };
    let loaded = futures::future::join_all(PACKS.iter().map(|def| load_pack(&http, cache_root, def))).await;
    PACKS
        .iter()
        .zip(loaded)
        .filter_map(|(def, skins)| {
            let skins = skins?;
            (!skins.is_empty()).then(|| SkinPackView {
                id: def.id.to_owned(),
                name: def.name.to_owned(),
                released: def.released.to_owned(),
                skins: skins
                    .into_iter()
                    .map(|s| SelectableSkin {
                        id: format!("pack/{}/{}", def.id, s.key),
                        name: s.name,
                        variant: s.variant,
                        texture: skins::data_url(&s.png),
                    })
                    .collect(),
            })
        })
        .collect()
}

/// Textur eines Paket-Skins aus dem Cache (`<paket>/<skin>`) – ohne Netzwerk.
async fn cached_bytes(cache_root: &Path, id: &str) -> Option<Vec<u8>> {
    let (pack, key) = id.split_once('/')?;
    let def = PACKS.iter().find(|d| d.id == pack)?;
    let cached = read_cache(cache_root, def.id).await?;
    cached.skins.into_iter().find(|s| s.key == key).map(|s| s.png)
}

impl Launcher {
    /// Alle Standard-Skins (aus dem neuesten installierten Client) zum Auswählen.
    pub async fn builtin_skins(&self) -> BuiltinSkinsView {
        let all = crate::default_skin::all(self.paths()).await;
        BuiltinSkinsView {
            version: all.version,
            skins: all
                .skins
                .into_iter()
                .map(|s| SelectableSkin { texture: skins::data_url(&s.png), id: s.id, name: s.name, variant: s.variant })
                .collect(),
        }
    }

    /// Offizielle Skin-Pakete (leer ohne Internet und ohne Cache).
    pub async fn skin_packs(&self) -> Vec<SkinPackView> {
        load_all(&self.paths().root().join("cache")).await
    }

    /// Bytes eines auswählbaren Skins (`wide/steve` oder `pack/<paket>/<skin>`) zum Hochladen.
    pub(crate) async fn selectable_skin_bytes(&self, id: &str) -> Result<Vec<u8>> {
        let bytes = match id.strip_prefix("pack/") {
            Some(rest) => cached_bytes(&self.paths().root().join("cache"), rest).await,
            None => crate::default_skin::builtin_bytes(self.paths(), id).await,
        };
        bytes.ok_or_else(|| {
            Error::validation(crate::msg!(
                "skins.builtinMissing",
                "Dieser Skin ist gerade nicht verfügbar – bitte die Seite neu laden."
            ))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png(slim: bool) -> Vec<u8> {
        let mut img = image::RgbaImage::from_pixel(64, 64, image::Rgba([90, 60, 30, 255]));
        if slim {
            for &(x0, y0, w, h) in &[(50u32, 16u32, 2u32, 4u32), (54, 20, 2, 12)] {
                for y in y0..y0 + h {
                    for x in x0..x0 + w {
                        img.put_pixel(x, y, image::Rgba([0, 0, 0, 0]));
                    }
                }
            }
        }
        let mut cursor = std::io::Cursor::new(Vec::new());
        img.write_to(&mut cursor, image::ImageFormat::Png).unwrap();
        cursor.into_inner()
    }

    fn zip_of(files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut cursor = std::io::Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut cursor);
            let opts = zip::write::SimpleFileOptions::default();
            for (name, bytes) in files {
                zip.start_file(*name, opts).unwrap();
                std::io::Write::write_all(&mut zip, bytes).unwrap();
            }
            zip.finish().unwrap();
        }
        cursor.into_inner()
    }

    fn def(id: &str) -> &'static PackDef {
        PACKS.iter().find(|d| d.id == id).unwrap()
    }

    #[test]
    fn every_pack_url_is_on_the_allowlist() {
        for pack in PACKS {
            assert!(!pack.files.is_empty());
            for file in pack.files {
                let url = reqwest::Url::parse(&format!("{BASE}{file}")).unwrap();
                assert!(is_allowed_url(&url), "{url}");
            }
        }
        let bad = ["http://www.minecraft.net/x.zip", "https://evil.example/x.zip", "https://www.minecraft.net:8443/x.zip", "https://user@www.minecraft.net/x.zip", "https://minecraft.net.evil.example/x.zip"];
        for url in bad {
            assert!(!is_allowed_url(&reqwest::Url::parse(url).unwrap()), "{url}");
        }
    }

    #[test]
    fn zip_entries_are_filtered_and_named() {
        let wide = png(false);
        let slim = png(true);
        let bytes = zip_of(&[
            ("Java_Skins/", b""),
            ("Java_Skins/baby_bee_skin.png", slim.as_slice()),
            ("__MACOSX/Java_Skins/._baby_bee_skin.png", b"junk"),
            ("Java_Skins/readme.txt", b"hello"),
            ("Java_Skins/broken.png", b"\x89PNG not really"),
            ("Java_Skins/villager1.png", wide.as_slice()),
        ]);
        let skins = skins_from_zip(&bytes, def("tiny-takeover"));
        let names: Vec<(&str, &str, SkinVariant)> = skins.iter().map(|s| (s.key.as_str(), s.name.as_str(), s.variant)).collect();
        assert_eq!(
            names,
            [
                ("baby-bee-skin", "Baby Bee", SkinVariant::Slim),
                ("villager1", "Villager 1", SkinVariant::Classic),
            ]
        );
        assert!(skins_from_zip(b"not a zip", def("tiny-takeover")).is_empty());
    }

    #[test]
    fn names_from_files_are_readable() {
        assert_eq!(pretty_name("Barn Builder skin"), "Barn Builder");
        assert_eq!(pretty_name("wither_skeleton"), "Wither Skeleton");
        assert_eq!(pretty_name("villager2"), "Villager 2");
        assert_eq!(pretty_name("skin"), "Skin");
        assert_eq!(slug("Bee-Friender Alternate skin"), "bee-friender-alternate-skin");
        assert_eq!(slug("../..//x"), "x");
    }

    #[tokio::test]
    async fn cached_packs_are_used_and_checked() {
        let root = tempfile::tempdir().unwrap();
        let skin = PackSkin { key: "copper-chemist".into(), name: "Copper Chemist".into(), variant: SkinVariant::Slim, png: png(true) };
        let entry = CachedPack { fetched_at: Utc::now(), skins: vec![skin.clone()] };
        let file = cache_file(root.path(), "the-copper-age");
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(&file, serde_json::to_vec(&entry).unwrap()).unwrap();

        // Frischer Cache → kein Netzwerk nötig.
        let http = client().unwrap();
        let loaded = load_pack(&http, root.path(), def("the-copper-age")).await.unwrap();
        assert_eq!(loaded, vec![skin.clone()]);
        assert_eq!(cached_bytes(root.path(), "the-copper-age/copper-chemist").await, Some(skin.png.clone()));
        assert_eq!(cached_bytes(root.path(), "the-copper-age/nope").await, None);
        assert_eq!(cached_bytes(root.path(), "unknown/copper-chemist").await, None);
        assert_eq!(cached_bytes(root.path(), "../x/copper-chemist").await, None);

        // Kaputter Cache-Inhalt wird nicht benutzt.
        let bad = CachedPack { fetched_at: Utc::now(), skins: vec![PackSkin { png: b"nope".to_vec(), ..skin }] };
        std::fs::write(&file, serde_json::to_vec(&bad).unwrap()).unwrap();
        assert!(read_cache(root.path(), "the-copper-age").await.is_none());
    }
}
