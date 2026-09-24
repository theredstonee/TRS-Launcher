//! Mod-Presets: benannte Listen von Mods, Ressourcen- und Shaderpaketen, die
//! sich beim Anlegen einer Instanz (oder später) in einem Rutsch installieren
//! lassen – jeweils nur, was es für Minecraft-Version + Modloader gibt.
//!
//! Eigene Presets liegen in `<daten>/presets.json`. Die fertigen TRS-Presets
//! („FPS-Boost“, „Nvidium“, „Voice Chat“, „Replay“) stehen im Code; von ihnen
//! merkt sich die Datei nur Position und „immer automatisch“.
//!
//! Installiert wird in zwei Schritten: Erst wird alles aufgelöst (passende
//! Version, Pflicht-Abhängigkeiten, Duplikate, Konflikte) – ohne etwas
//! anzufassen –, dann wird geladen. Was nicht passt, wird übersprungen und
//! im Bericht mit Grund genannt.

use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::content::{self, ContentKind};
use crate::error::UserError;
use crate::icon::is_allowed_icon_url;
use crate::instance::{Instance, LoaderKind, UpdateChannel};
use crate::modrinth::{self, Version};
use crate::paths::Paths;
use crate::{Error, Result, fsutil, task};

pub const MAX_PRESETS: usize = 50;
pub const MAX_ITEMS: usize = 100;
pub const MAX_NAME_CHARS: usize = 48;
const MAX_TITLE_CHARS: usize = 100;
/// Export-Dateien sind klein – mehr wird beim Import nicht gelesen.
pub const MAX_IMPORT_BYTES: u64 = 256 * 1024;
const FILE_NAME: &str = "presets.json";
const FILE_VERSION: u32 = 1;
const EXPORT_FORMAT: &str = "trs-preset";
const EXPORT_VERSION: u32 = 1;

static WRITE_LOCK: Mutex<()> = Mutex::const_new(());

// --- Datenmodell -------------------------------------------------------------------

/// Woher ein Projekt stammt. Später kommt CurseForge dazu.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PresetSource {
    Modrinth,
}

/// Ein Eintrag eines Presets.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PresetItem {
    pub source: PresetSource,
    pub project_id: String,
    pub title: String,
    #[serde(default)]
    pub icon_url: Option<String>,
    pub kind: ContentKind,
}

/// Die fertigen Presets des Launchers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Builtin {
    FpsBoost,
    Nvidium,
    VoiceChat,
    Replay,
}

impl Builtin {
    pub const ALL: [Self; 4] = [Self::FpsBoost, Self::Nvidium, Self::VoiceChat, Self::Replay];

    pub fn id(self) -> &'static str {
        match self {
            Self::FpsBoost => "trs-fps-boost",
            Self::Nvidium => "trs-nvidium",
            Self::VoiceChat => "trs-voice-chat",
            Self::Replay => "trs-replay",
        }
    }

    pub fn from_id(id: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|b| b.id() == id)
    }

    /// FPS-Boost ist von Anfang an bei neuen Instanzen vorausgewählt.
    fn default_auto(self) -> bool {
        matches!(self, Self::FpsBoost)
    }

    /// Darf zu fertigen Modpacks dazu? Performance-Mods beißen sich dort
    /// leicht mit dem, was der Packautor gewählt hat.
    pub fn modpack_safe(self) -> bool {
        matches!(self, Self::VoiceChat | Self::Replay)
    }

    fn groups(self) -> &'static [Group] {
        match self {
            Self::FpsBoost => FPS_BOOST,
            Self::Nvidium => NVIDIUM,
            Self::VoiceChat => VOICE_CHAT,
            Self::Replay => REPLAY,
        }
    }
}

/// Ein Mod eines fertigen Presets (Modrinth-ID, damit nichts verwechselt wird).
struct BuiltinMod {
    id: &'static str,
    title: &'static str,
    icon: Option<&'static str>,
    /// Erst ab Minecraft 1.20 installieren.
    from_1_20: bool,
}

/// Eine Zeile eines fertigen Presets: Es wird die erste Mod genommen, die es
/// für Version + Loader gibt – so landet nie Sodium UND Embeddium (oder
/// VintageFix UND FoamFix) in derselben Instanz.
struct Group {
    mods: &'static [BuiltinMod],
    /// Liegt eine Mod-Datei mit diesem Namensteil in der Instanz (z. B. von
    /// Hand installiertes OptiFine), wird die Zeile übersprungen.
    file_conflicts: &'static [&'static str],
}

const fn m(id: &'static str, title: &'static str, icon: &'static str) -> BuiltinMod {
    BuiltinMod { id, title, icon: Some(icon), from_1_20: false }
}

const fn single(mods: &'static [BuiltinMod]) -> Group {
    Group { mods, file_conflicts: &[] }
}

const CDN: &str = "https://cdn.modrinth.com/data/";

/// Bewährte Client-Optimierungen, wichtigste zuerst. Was es für eine Version
/// oder einen Loader nicht gibt, fällt einfach weg (Lithium etwa nicht für
/// Forge, ModernFix erst ab 1.20, VintageFix nur für 1.12.2).
const FPS_BOOST: &[Group] = &[
    Group {
        mods: &[
            m("AANobbMI", "Sodium", "AANobbMI/295862f4724dc3f78df3447ad6072b2dcd3ef0c9_96.webp"),
            m("sk9rgfiA", "Embeddium", "sk9rgfiA/55f9c50284f8abbbe2a485abfd6a16209201e451_96.webp"),
        ],
        file_conflicts: &["optifine"],
    },
    single(&[m("gvQqBUqZ", "Lithium", "gvQqBUqZ/bcc8686c13af0143adf4285d741256af824f70b7_96.webp")]),
    single(&[m("uXXizFIs", "FerriteCore", "uXXizFIs/222a126f26f8f9ae1eb339f3b767677f18bff31f_96.webp")]),
    // ModernFix blockiert vor 1.20 zusammen mit Lithium die Weltenerstellung
    // („Spawn-Bereich wird vorbereitet: 0 %“) – dort weglassen.
    single(&[BuiltinMod {
        id: "nmDcB62a",
        title: "ModernFix",
        icon: Some("nmDcB62a/2af94de5e08ae54567ee86b968fc7ce076d9fee5_96.webp"),
        from_1_20: true,
    }]),
    single(&[m("NNAgCjsB", "Entity Culling", "NNAgCjsB/7873452d6cede4daed12da3d7d8c193ab88b4fd6_96.webp")]),
    single(&[m("5ZwdcRci", "ImmediatelyFast", "5ZwdcRci/e57b6b451425692ac17ad322d5e14bea686a383a_96.webp")]),
    single(&[m("51shyZVL", "More Culling", "51shyZVL/c51b07193b56e952269ef50101d12aecba2b4747_96.webp")]),
    single(&[m("LQ3K71Q1", "Dynamic FPS", "LQ3K71Q1/5056368d0d87c1a9f3efead0cb48ab39a4ea87bf_96.webp")]),
    single(&[BuiltinMod { id: "g96Z4WVZ", title: "BadOptimizations", icon: None, from_1_20: false }]),
    // Nur für ältere Versionen veröffentlicht.
    single(&[m("hvFnDODi", "LazyDFU", "hvFnDODi/48fa17d2335d565ac51cd9d75b3755cee061e409_96.webp")]),
    // Forge 1.12.2 / 1.8.9: VintageFix ersetzt FoamFix – nie beide.
    single(&[
        m("e6vNsbAm", "VintageFix", "e6vNsbAm/f4301c04ac94af39af8bfcc61cbcb20a505d459b.png"),
        m("eQQWhoFq", "VanillaFix", "eQQWhoFq/99d2373a9346b7414e6761feee4ada037faf9f05_96.webp"),
        m("jupr7Bf5", "FoamFix", "jupr7Bf5/64add3680442efc10fe427bb28d63b657bceda4d_96.webp"),
    ]),
    single(&[m("YknNc5nN", "PolyPatcher", "YknNc5nN/28c08fdc63482c25735ec6a2ee965347dfdadd4d_96.webp")]),
];

/// Nvidium rendert Gelände über Mesh-Shader – nur NVIDIA ab GTX 16xx/RTX 20xx.
const NVIDIUM: &[Group] = &[Group {
    mods: &[m("SfMw2IZN", "Nvidium", "SfMw2IZN/2db76d464a0f67cdb9e30fd99040eb096ac62016_96.webp")],
    file_conflicts: &["optifine"],
}];

const VOICE_CHAT: &[Group] = &[single(&[m("9eGKb6K1", "Simple Voice Chat", "9eGKb6K1/icon.png")])];

/// Flashback wo verfügbar (Fabric, neuere Versionen), sonst ReplayMod.
const REPLAY: &[Group] = &[single(&[
    m("4das1Fjq", "Flashback", "4das1Fjq/e81c66aacf2e12c09a95e4f971ea5e2b9f608317_96.webp"),
    m("Nv2fQJo5", "ReplayMod", "Nv2fQJo5/icon.png"),
])];

/// Ein Preset, wie das Frontend es sieht.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Preset {
    pub id: String,
    /// Eigener Name; bei fertigen Presets leer (das Frontend übersetzt sie).
    pub name: String,
    pub builtin: Option<Builtin>,
    /// Bei jeder neuen Instanz vorausgewählt.
    pub auto: bool,
    /// Wird bei Modpacks mit angeboten bzw. automatisch ergänzt.
    pub modpack_safe: bool,
    /// Passt zu diesem PC (Nvidium nur mit passender NVIDIA-Karte).
    pub available: bool,
    pub items: Vec<PresetItem>,
}

/// Eingabe beim Anlegen/Ändern eines eigenen Presets.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PresetInput {
    pub name: String,
    #[serde(default)]
    pub auto: bool,
    #[serde(default)]
    pub items: Vec<PresetItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredPreset {
    id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    name: String,
    #[serde(default)]
    auto: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    items: Vec<PresetItem>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredFile {
    #[serde(default)]
    presets: Vec<StoredPreset>,
}

/// Wie [`StoredFile`], zum Schreiben (Reihenfolge der Felder bleibt lesbar).
#[derive(Serialize)]
struct StoredFileRef<'a> {
    version: u32,
    presets: &'a [StoredPreset],
}

/// Aufbau einer geteilten Preset-Datei.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ExportFile<'a> {
    format: &'static str,
    version: u32,
    name: &'a str,
    items: &'a [PresetItem],
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawExportFile {
    format: String,
    #[serde(default)]
    version: u32,
    name: String,
    #[serde(default)]
    items: Vec<serde_json::Value>,
}

// --- Prüfen ------------------------------------------------------------------------

fn clean_text(text: &str) -> String {
    text.chars().filter(|c| !c.is_control()).collect::<String>().trim().to_owned()
}

fn validate_name(name: &str) -> Result<String> {
    let name = clean_text(name);
    let len = name.chars().count();
    if len == 0 || len > MAX_NAME_CHARS {
        return Err(Error::validation(crate::msg!(
            "presets.nameLength",
            "Der Name muss zwischen 1 und {max} Zeichen lang sein.",
            max = MAX_NAME_CHARS
        )));
    }
    Ok(name)
}

/// Prüft einen Eintrag und bringt ihn in Form (Titel gekürzt, fremde Bilder weg).
fn validate_item(item: PresetItem) -> Result<PresetItem> {
    if !modrinth::is_safe_project_id(&item.project_id) {
        return Err(Error::validation(crate::msg!("presets.invalidProject", "Ungültiges Projekt im Preset.")));
    }
    let mut title: String = clean_text(&item.title).chars().take(MAX_TITLE_CHARS).collect();
    if title.is_empty() {
        title.clone_from(&item.project_id);
    }
    Ok(PresetItem {
        source: item.source,
        title,
        icon_url: item.icon_url.filter(|u| is_allowed_icon_url(u)),
        project_id: item.project_id,
        kind: item.kind,
    })
}

/// Alle Einträge prüfen; doppelte Projekte zählen nur einmal.
fn validate_items(items: Vec<PresetItem>) -> Result<Vec<PresetItem>> {
    if items.len() > MAX_ITEMS {
        return Err(Error::validation(crate::msg!(
            "presets.tooManyItems",
            "Ein Preset kann höchstens {max} Einträge haben.",
            max = MAX_ITEMS
        )));
    }
    let mut seen = HashSet::new();
    let mut out = Vec::with_capacity(items.len());
    for item in items {
        let item = validate_item(item)?;
        if seen.insert((item.source, item.project_id.clone())) {
            out.push(item);
        }
    }
    Ok(out)
}

/// IDs eigener Presets: 32 Hex-Zeichen (UUID ohne Striche).
fn is_user_id(id: &str) -> bool {
    id.len() == 32 && id.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn not_found() -> Error {
    Error::validation(crate::msg!("presets.notFound", "Dieses Preset gibt es nicht mehr."))
}

fn read_only() -> Error {
    Error::validation(crate::msg!(
        "presets.builtinReadOnly",
        "Fertige TRS-Presets lassen sich nicht ändern oder löschen."
    ))
}

// --- Speichern ---------------------------------------------------------------------

fn file(paths: &Paths) -> PathBuf {
    paths.root().join(FILE_NAME)
}

async fn read_stored(paths: &Paths) -> Vec<StoredPreset> {
    match fsutil::read_json::<StoredFile>(&file(paths)).await {
        Ok(Some(f)) => normalize(f.presets),
        Ok(None) => normalize(Vec::new()),
        Err(e) => {
            // Kaputte Datei: mit den Standard-Presets weiter, nichts abstürzen lassen.
            tracing::warn!("presets.json ist unlesbar – Standard wird benutzt: {e}");
            normalize(Vec::new())
        }
    }
}

async fn write_stored(paths: &Paths, presets: &[StoredPreset]) -> Result<()> {
    fsutil::write_json(&file(paths), &StoredFileRef { version: FILE_VERSION, presets }).await
}

/// Bringt die gespeicherte Liste in Form: jedes fertige Preset genau einmal
/// (fehlende hinter den vorhandenen fertigen, beim ersten Mal vorne), eigene
/// nur gültig, ohne doppelte IDs und höchstens [`MAX_PRESETS`].
fn normalize(stored: Vec<StoredPreset>) -> Vec<StoredPreset> {
    let mut out: Vec<StoredPreset> = Vec::new();
    let mut ids = HashSet::new();
    let mut own = 0;
    for p in stored {
        if !ids.insert(p.id.clone()) {
            continue;
        }
        if Builtin::from_id(&p.id).is_some() {
            out.push(StoredPreset { id: p.id, name: String::new(), auto: p.auto, items: Vec::new() });
            continue;
        }
        if !is_user_id(&p.id) || own >= MAX_PRESETS {
            continue;
        }
        let Ok(name) = validate_name(&p.name) else { continue };
        // Einzelne kaputte Einträge fallen weg, der Rest bleibt.
        let mut seen = HashSet::new();
        let items: Vec<PresetItem> = p
            .items
            .into_iter()
            .filter_map(|i| validate_item(i).ok())
            .filter(|i| seen.insert((i.source, i.project_id.clone())))
            .take(MAX_ITEMS)
            .collect();
        own += 1;
        out.push(StoredPreset { id: p.id, name, auto: p.auto, items });
    }
    let missing: Vec<StoredPreset> = Builtin::ALL
        .into_iter()
        .filter(|b| !ids.contains(b.id()))
        .map(|b| StoredPreset { id: b.id().to_owned(), name: String::new(), auto: b.default_auto(), items: Vec::new() })
        .collect();
    let at = out.iter().rposition(|p| Builtin::from_id(&p.id).is_some()).map_or(0, |i| i + 1);
    out.splice(at..at, missing);
    out
}

/// Einträge eines fertigen Presets für die Anzeige: je Zeile ein Eintrag,
/// Alternativen im Titel („Sodium / Embeddium“).
fn builtin_items(builtin: Builtin) -> Vec<PresetItem> {
    builtin
        .groups()
        .iter()
        .map(|g| {
            let first = &g.mods[0];
            let mut title = g.mods.iter().map(|m| m.title).collect::<Vec<_>>().join(" / ");
            if first.from_1_20 {
                title.push_str(" (1.20+)");
            }
            PresetItem {
                source: PresetSource::Modrinth,
                project_id: first.id.to_owned(),
                title,
                icon_url: first.icon.map(|i| format!("{CDN}{i}")),
                kind: ContentKind::Mod,
            }
        })
        .collect()
}

fn view(p: &StoredPreset, nvidium_ok: bool) -> Preset {
    match Builtin::from_id(&p.id) {
        Some(b) => Preset {
            id: p.id.clone(),
            name: String::new(),
            builtin: Some(b),
            auto: p.auto,
            modpack_safe: b.modpack_safe(),
            available: b != Builtin::Nvidium || nvidium_ok,
            items: builtin_items(b),
        },
        None => Preset {
            id: p.id.clone(),
            name: p.name.clone(),
            builtin: None,
            auto: p.auto,
            modpack_safe: true,
            available: true,
            items: p.items.clone(),
        },
    }
}

fn views(stored: &[StoredPreset]) -> Vec<Preset> {
    let nvidium_ok = crate::gpu::nvidium_capable();
    stored.iter().map(|p| view(p, nvidium_ok)).collect()
}

/// Alle Presets in der gespeicherten Reihenfolge.
pub async fn list(paths: &Paths) -> Result<Vec<Preset>> {
    Ok(views(&read_stored(paths).await))
}

/// Liest, ändert und schreibt die Liste unter der Schreibsperre.
async fn modify<T>(paths: &Paths, change: impl FnOnce(&mut Vec<StoredPreset>) -> Result<T>) -> Result<T> {
    let _guard = WRITE_LOCK.lock().await;
    let mut list = read_stored(paths).await;
    let out = change(&mut list)?;
    write_stored(paths, &list).await?;
    Ok(out)
}

fn own_count(list: &[StoredPreset]) -> usize {
    list.iter().filter(|p| Builtin::from_id(&p.id).is_none()).count()
}

fn too_many() -> Error {
    Error::validation(crate::msg!(
        "presets.tooMany",
        "Mehr als {max} eigene Presets werden nicht unterstützt.",
        max = MAX_PRESETS
    ))
}

pub async fn create(paths: &Paths, input: PresetInput) -> Result<Preset> {
    let name = validate_name(&input.name)?;
    let items = validate_items(input.items)?;
    let stored = modify(paths, |list| {
        if own_count(list) >= MAX_PRESETS {
            return Err(too_many());
        }
        let preset = StoredPreset { id: uuid::Uuid::new_v4().simple().to_string(), name, auto: input.auto, items };
        list.push(preset.clone());
        Ok(preset)
    })
    .await?;
    Ok(view(&stored, true))
}

pub async fn update(paths: &Paths, id: &str, input: PresetInput) -> Result<Preset> {
    if Builtin::from_id(id).is_some() {
        return Err(read_only());
    }
    let name = validate_name(&input.name)?;
    let items = validate_items(input.items)?;
    let stored = modify(paths, |list| {
        let preset = list.iter_mut().find(|p| p.id == id).ok_or_else(not_found)?;
        preset.name = name;
        preset.auto = input.auto;
        preset.items = items;
        Ok(preset.clone())
    })
    .await?;
    Ok(view(&stored, true))
}

/// „Immer automatisch“ – auch für die fertigen Presets.
pub async fn set_auto(paths: &Paths, id: &str, auto: bool) -> Result<Preset> {
    let stored = modify(paths, |list| {
        let preset = list.iter_mut().find(|p| p.id == id).ok_or_else(not_found)?;
        preset.auto = auto;
        Ok(preset.clone())
    })
    .await?;
    Ok(view(&stored, crate::gpu::nvidium_capable()))
}

pub async fn delete(paths: &Paths, id: &str) -> Result<()> {
    if Builtin::from_id(id).is_some() {
        return Err(read_only());
    }
    modify(paths, |list| {
        let before = list.len();
        list.retain(|p| p.id != id);
        if list.len() == before { Err(not_found()) } else { Ok(()) }
    })
    .await
}

/// Neue Reihenfolge – `ids` muss genau die vorhandenen Presets enthalten.
pub async fn reorder(paths: &Paths, ids: &[String]) -> Result<Vec<Preset>> {
    let list = modify(paths, |list| {
        let current: HashSet<&str> = list.iter().map(|p| p.id.as_str()).collect();
        let wanted: HashSet<&str> = ids.iter().map(String::as_str).collect();
        if ids.len() != list.len() || wanted != current {
            return Err(Error::validation(crate::msg!(
                "presets.invalidOrder",
                "Die Reihenfolge passt nicht zur Preset-Liste."
            )));
        }
        let mut by_id: HashMap<String, StoredPreset> = list.drain(..).map(|p| (p.id.clone(), p)).collect();
        list.extend(ids.iter().filter_map(|id| by_id.remove(id)));
        Ok(list.clone())
    })
    .await?;
    Ok(views(&list))
}

/// Vorschlag für den Dateinamen beim Export.
pub fn export_file_name(name: &str) -> String {
    let base: String = name
        .chars()
        .map(|c| if c.is_alphanumeric() || matches!(c, ' ' | '-' | '_') { c } else { '_' })
        .collect::<String>()
        .trim()
        .chars()
        .take(MAX_NAME_CHARS)
        .collect();
    let base = if base.trim_matches('_').is_empty() { "preset".to_owned() } else { base };
    format!("{base}.trs-preset.json")
}

/// Eigenes Preset als kleine JSON-Datei (Name + Einträge) zum Teilen.
/// Liefert Dateinamen-Vorschlag und Inhalt.
pub async fn export(paths: &Paths, id: &str) -> Result<(String, Vec<u8>)> {
    if Builtin::from_id(id).is_some() {
        return Err(read_only());
    }
    let list = read_stored(paths).await;
    let preset = list.iter().find(|p| p.id == id).ok_or_else(not_found)?;
    let bytes = serde_json::to_vec_pretty(&ExportFile {
        format: EXPORT_FORMAT,
        version: EXPORT_VERSION,
        name: &preset.name,
        items: &preset.items,
    })
    .map_err(|e| Error::Internal(e.to_string()))?;
    Ok((export_file_name(&preset.name), bytes))
}

fn invalid_file() -> Error {
    Error::validation(crate::msg!("presets.invalidFile", "Das ist keine gültige Preset-Datei."))
}

fn file_too_large() -> Error {
    Error::validation(crate::msg!("presets.fileTooLarge", "Die Preset-Datei ist zu groß."))
}

/// Liest eine geteilte Preset-Datei. Unbekannte Einträge (z. B. aus einer
/// neueren Launcher-Version) werden übersprungen.
fn parse_export(bytes: &[u8]) -> Result<(String, Vec<PresetItem>)> {
    if bytes.len() as u64 > MAX_IMPORT_BYTES {
        return Err(file_too_large());
    }
    let raw: RawExportFile = serde_json::from_slice(bytes).map_err(|_| invalid_file())?;
    if raw.format != EXPORT_FORMAT || raw.version == 0 {
        return Err(invalid_file());
    }
    let name = validate_name(&raw.name)?;
    let items: Vec<PresetItem> = raw
        .items
        .into_iter()
        .take(MAX_ITEMS * 2)
        .filter_map(|v| serde_json::from_value::<PresetItem>(v).ok())
        .filter_map(|i| validate_item(i).ok())
        .collect();
    let items = validate_items(items.into_iter().take(MAX_ITEMS).collect())?;
    if items.is_empty() {
        return Err(invalid_file());
    }
    Ok((name, items))
}

/// Gleicher Name schon vergeben? Dann „Name (2)“, „Name (3)“ …
fn unique_name(name: &str, taken: &[&str]) -> String {
    if !taken.contains(&name) {
        return name.to_owned();
    }
    (2..=MAX_PRESETS + 1)
        .map(|n| {
            let suffix = format!(" ({n})");
            let base: String = name.chars().take(MAX_NAME_CHARS - suffix.chars().count()).collect();
            format!("{}{suffix}", base.trim_end())
        })
        .find(|candidate| !taken.contains(&candidate.as_str()))
        .unwrap_or_else(|| name.to_owned())
}

/// Legt aus einer geteilten Datei ein neues eigenes Preset an.
pub async fn import(paths: &Paths, bytes: &[u8]) -> Result<Preset> {
    let (name, items) = parse_export(bytes)?;
    let stored = modify(paths, |list| {
        if own_count(list) >= MAX_PRESETS {
            return Err(too_many());
        }
        let taken: Vec<&str> = list.iter().map(|p| p.name.as_str()).collect();
        let preset = StoredPreset {
            id: uuid::Uuid::new_v4().simple().to_string(),
            name: unique_name(&name, &taken),
            auto: false,
            items,
        };
        list.push(preset.clone());
        Ok(preset)
    })
    .await?;
    Ok(view(&stored, true))
}

/// Wie [`import`], liest aber die Datei (höchstens [`MAX_IMPORT_BYTES`]).
pub async fn import_file(paths: &Paths, file: &Path) -> Result<Preset> {
    let size = tokio::fs::metadata(file).await.map_err(|e| Error::io(file, e))?.len();
    if size > MAX_IMPORT_BYTES {
        return Err(file_too_large());
    }
    let bytes = tokio::fs::read(file).await.map_err(|e| Error::io(file, e))?;
    import(paths, &bytes).await
}

// --- Auflösen ------------------------------------------------------------------------

/// 1.20+ oder die neue Jahres-Zählung (26.x). Unbekanntes Format zählt als neu.
fn at_least_1_20(game_version: &str) -> bool {
    let mut parts = game_version.split(['.', '-', ' ']).map(|p| p.parse::<u32>().ok());
    match (parts.next().flatten(), parts.next().flatten()) {
        (Some(1), Some(minor)) => minor >= 20,
        (Some(major), _) => major > 1,
        _ => true,
    }
}

/// Eine Mod-Wahl innerhalb eines Eintrags.
#[derive(Debug, Clone)]
pub(crate) struct Candidate {
    pub project_id: String,
    pub title: String,
    pub icon_url: Option<String>,
    pub from_1_20: bool,
}

/// Ein Eintrag, der installiert werden soll – mit Alternativen in Reihenfolge.
#[derive(Debug, Clone)]
pub(crate) struct Wanted {
    pub preset_id: String,
    pub kind: ContentKind,
    pub candidates: Vec<Candidate>,
    pub file_conflicts: Vec<String>,
    /// Teil einer Sammlung (FPS-Boost): fehlt es für die Version, ist das normal.
    pub optional: bool,
}

fn wanted_of(preset: &Preset) -> Vec<Wanted> {
    match preset.builtin {
        Some(b) => b
            .groups()
            .iter()
            .map(|g| Wanted {
                preset_id: preset.id.clone(),
                kind: ContentKind::Mod,
                candidates: g
                    .mods
                    .iter()
                    .map(|m| Candidate {
                        project_id: m.id.to_owned(),
                        title: m.title.to_owned(),
                        icon_url: m.icon.map(|i| format!("{CDN}{i}")),
                        from_1_20: m.from_1_20,
                    })
                    .collect(),
                file_conflicts: g.file_conflicts.iter().map(|f| (*f).to_owned()).collect(),
                optional: b == Builtin::FpsBoost,
            })
            .collect(),
        None => preset
            .items
            .iter()
            .map(|i| Wanted {
                preset_id: preset.id.clone(),
                kind: i.kind,
                candidates: vec![Candidate {
                    project_id: i.project_id.clone(),
                    title: i.title.clone(),
                    icon_url: i.icon_url.clone(),
                    from_1_20: false,
                }],
                file_conflicts: Vec::new(),
                optional: false,
            })
            .collect(),
    }
}

/// Wofür installiert wird.
#[derive(Debug, Clone)]
pub(crate) struct Target {
    pub game_version: String,
    /// Loader-Namen bei Modrinth; leer = Vanilla (keine Mods).
    pub loaders: &'static [&'static str],
    pub channel: UpdateChannel,
}

impl Target {
    fn of(instance: &Instance) -> Self {
        // Vanilla bleibt Vanilla: Mods brauchen einen echten Modloader. Die
        // TRS-Optimierung von Vanilla-Instanzen kümmert sich selbst um ihre Mods.
        let loaders = match instance.loader.kind {
            LoaderKind::Vanilla => &[][..],
            kind => modrinth::loader_tags(kind),
        };
        Self { game_version: instance.game_version.clone(), loaders, channel: instance.overrides.channel() }
    }

    /// Passt eine (fest vorgegebene) Version zu Spielversion und Loader?
    fn fits(&self, version: &Version, kind: ContentKind) -> bool {
        version.game_versions.iter().any(|g| g == &self.game_version)
            && (kind != ContentKind::Mod || version.loaders.iter().any(|l| self.loaders.contains(&l.as_str())))
    }
}

/// Was schon in der Instanz ist.
#[derive(Debug, Clone, Default)]
pub(crate) struct Existing {
    /// Projekt-ID → installierte Versions-ID (falls bekannt).
    pub projects: HashMap<String, Option<String>>,
    /// Dateinamen der Mods, klein geschrieben (für Konflikte wie OptiFine).
    pub mod_files: Vec<String>,
}

async fn existing(paths: &Paths, instance_id: &str) -> Result<Existing> {
    let mut projects = HashMap::new();
    for project_id in content::installed_project_ids(paths, instance_id).await? {
        let version = content::source_of_project(paths, instance_id, &project_id).await.map(|s| s.version_id);
        projects.insert(project_id, version.filter(|v| !v.is_empty()));
    }
    let mod_files =
        content::list(paths, instance_id, ContentKind::Mod).await?.into_iter().map(|i| i.file_name.to_lowercase()).collect();
    Ok(Existing { projects, mod_files })
}

/// Woher die Versionsdaten kommen – in Tests eine Attrappe.
pub(crate) trait VersionLookup: Sync {
    /// Zur Instanz passende Versionen, neueste zuerst; unbekanntes Projekt = leer.
    fn versions(&self, project_id: &str, kind: ContentKind) -> impl Future<Output = Result<Vec<Version>>> + Send;
    /// Eine bestimmte Version (für festgelegte Abhängigkeiten).
    fn version(&self, version_id: &str) -> impl Future<Output = Result<Option<Version>>> + Send;
    /// Anzeigename eines Projekts (für den Bericht).
    fn title(&self, project_id: &str) -> impl Future<Output = Option<String>> + Send;
}

struct ModrinthLookup<'a> {
    http: &'a reqwest::Client,
    instance: &'a Instance,
}

fn not_found_is_empty<T: Default>(result: Result<T>) -> Result<T> {
    match result {
        Err(Error::Http(e)) if e.status() == Some(reqwest::StatusCode::NOT_FOUND) => Ok(T::default()),
        other => other,
    }
}

impl VersionLookup for ModrinthLookup<'_> {
    async fn versions(&self, project_id: &str, kind: ContentKind) -> Result<Vec<Version>> {
        not_found_is_empty(modrinth::compatible_versions(self.http, project_id, kind, self.instance).await)
    }

    async fn version(&self, version_id: &str) -> Result<Option<Version>> {
        not_found_is_empty(modrinth::version_by_id(self.http, version_id).await.map(Some))
    }

    async fn title(&self, project_id: &str) -> Option<String> {
        let cards = modrinth::project_cards(self.http, &[project_id.to_owned()]).await.ok()?;
        cards.into_iter().next().map(|c| c.title).filter(|t| !t.is_empty())
    }
}

/// Ergebnis je Eintrag.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum ItemStatus {
    /// Installiert (im Plan: wird installiert).
    Installed,
    /// War schon in der Instanz.
    AlreadyInstalled,
    /// Kommt schon über ein anderes Preset oder als Abhängigkeit.
    Duplicate,
    /// Keine passende Version für Spielversion + Loader.
    NotAvailable,
    /// Eine Pflicht-Abhängigkeit gibt es nicht passend.
    MissingDependency,
    /// Verträgt sich nicht mit etwas in der Instanz (`detail`).
    Incompatible,
    /// Mods brauchen einen Modloader (Vanilla).
    NeedsLoader,
    /// Download o. Ä. fehlgeschlagen (`error`).
    Failed,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ItemOutcome {
    pub preset_id: String,
    pub project_id: Option<String>,
    pub title: String,
    pub icon_url: Option<String>,
    pub kind: ContentKind,
    pub status: ItemStatus,
    /// Nur Teil einer Sammlung – „gibt es hier nicht“ ist dann kein Problem.
    pub optional: bool,
    pub version_number: Option<String>,
    /// Bei `missingDependency`/`incompatible`: um welches Projekt bzw. welche Datei es geht.
    pub detail: Option<String>,
    pub error: Option<UserError>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ApplyReport {
    pub game_version: String,
    pub loader: LoaderKind,
    pub items: Vec<ItemOutcome>,
    /// Neu installierte Dateien (mit Abhängigkeiten).
    pub files: Vec<String>,
    /// Davon Abhängigkeiten.
    pub dependencies: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ApplyPhase {
    Resolve,
    Install,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ApplyProgress {
    pub phase: ApplyPhase,
    pub done: u32,
    pub total: u32,
    /// Was gerade geladen wird.
    pub title: Option<String>,
}

/// Ein Download des Plans.
#[derive(Debug, Clone)]
pub(crate) struct Step {
    pub kind: ContentKind,
    pub version: Version,
    pub dependency: bool,
    /// Version von einer anderen Mod fest vorgegeben.
    pub pinned: bool,
    /// Eintrag im Bericht, zu dem der Download gehört.
    pub item: usize,
}

#[derive(Debug, Clone, Default)]
pub(crate) struct Plan {
    pub steps: Vec<Step>,
    pub items: Vec<ItemOutcome>,
}

/// Was beim Einsammeln einer Mod samt Abhängigkeiten herauskam.
enum Collected {
    Ok { steps: Vec<Step>, replace: Vec<(usize, Version)> },
    Missing(String),
    Incompatible(String),
}

type Dep = (String, Option<String>);

fn required_deps(version: &Version, kind: ContentKind, depth: u8) -> Vec<(Dep, u8)> {
    if kind != ContentKind::Mod || depth >= modrinth::MAX_DEPENDENCY_DEPTH {
        return Vec::new();
    }
    version
        .dependencies
        .iter()
        .filter(|d| d.dependency_type == "required")
        .filter_map(|d| {
            let id = d.project_id.clone().filter(|id| modrinth::is_safe_project_id(id))?;
            Some(((id, d.version_id.clone().filter(|v| modrinth::is_safe_project_id(v))), depth + 1))
        })
        .collect()
}

fn incompatible_ids(version: &Version) -> impl Iterator<Item = &str> {
    version
        .dependencies
        .iter()
        .filter(|d| d.dependency_type == "incompatible")
        .filter_map(|d| d.project_id.as_deref())
}

/// Sammelt `root` mit allen Pflicht-Abhängigkeiten ein – oder sagt, woran es scheitert.
async fn collect<L: VersionLookup>(
    lookup: &L,
    target: &Target,
    existing: &Existing,
    plan: &Plan,
    planned: &HashMap<String, usize>,
    kind: ContentKind,
    root: Version,
) -> Result<Collected> {
    let item = plan.items.len();
    let mut local_ids: HashSet<String> = HashSet::from([root.project_id.clone()]);
    let mut queue = required_deps(&root, kind, 0);
    let mut steps = vec![Step { kind, version: root, dependency: false, pinned: false, item }];
    let mut replace = Vec::new();

    while let Some(((id, pin), depth)) = queue.pop() {
        if local_ids.contains(&id) {
            continue;
        }
        if let Some(have) = existing.projects.get(&id) {
            // Schon installiert – außer die Mod verlangt ausdrücklich eine andere Version.
            if let (Some(pin), Some(have)) = (&pin, have)
                && pin != have
            {
                return Ok(Collected::Incompatible(id));
            }
            continue;
        }
        if let Some(&index) = planned.get(&id) {
            let step = &plan.steps[index];
            if let Some(pin) = pin.as_ref().filter(|p| **p != step.version.id) {
                if step.pinned {
                    return Ok(Collected::Incompatible(id));
                }
                // Festgelegte Version geht vor „neueste passende“.
                match lookup.version(pin).await?.filter(|v| target.fits(v, ContentKind::Mod)) {
                    Some(v) => replace.push((index, v)),
                    None => return Ok(Collected::Missing(id)),
                }
            }
            continue;
        }
        let version = match &pin {
            Some(pin) => lookup.version(pin).await?.filter(|v| target.fits(v, ContentKind::Mod)),
            None => modrinth::newest_in_channel(lookup.versions(&id, ContentKind::Mod).await?, target.channel),
        };
        let Some(version) = version else { return Ok(Collected::Missing(id)) };
        local_ids.insert(id);
        local_ids.insert(version.project_id.clone());
        queue.extend(required_deps(&version, ContentKind::Mod, depth));
        steps.push(Step { kind: ContentKind::Mod, version, dependency: true, pinned: pin.is_some(), item });
    }

    // Unverträglichkeiten in beide Richtungen: mit der Instanz, dem Plan und untereinander.
    for step in &steps {
        if let Some(other) = incompatible_ids(&step.version)
            .find(|id| existing.projects.contains_key(*id) || planned.contains_key(*id) || local_ids.contains(*id))
        {
            return Ok(Collected::Incompatible(other.to_owned()));
        }
    }
    for step in &plan.steps {
        if incompatible_ids(&step.version).any(|id| local_ids.contains(id)) {
            return Ok(Collected::Incompatible(step.version.project_id.clone()));
        }
    }
    Ok(Collected::Ok { steps, replace })
}

async fn resolve_one<L: VersionLookup>(
    lookup: &L,
    target: &Target,
    existing: &Existing,
    plan: &mut Plan,
    planned: &mut HashMap<String, usize>,
    wanted: &Wanted,
) -> Result<ItemOutcome> {
    let group_title = wanted.candidates.iter().map(|c| c.title.as_str()).collect::<Vec<_>>().join(" / ");
    let outcome = |status, candidate: Option<&Candidate>| ItemOutcome {
        preset_id: wanted.preset_id.clone(),
        project_id: candidate.map(|c| c.project_id.clone()),
        title: candidate.map_or_else(|| group_title.clone(), |c| c.title.clone()),
        icon_url: candidate.or(wanted.candidates.first()).and_then(|c| c.icon_url.clone()),
        kind: wanted.kind,
        status,
        optional: wanted.optional,
        version_number: None,
        detail: None,
        error: None,
    };

    if wanted.kind == ContentKind::Mod && target.loaders.is_empty() {
        return Ok(outcome(ItemStatus::NeedsLoader, None));
    }
    if let Some(c) = wanted.candidates.iter().find(|c| existing.projects.contains_key(&c.project_id)) {
        return Ok(outcome(ItemStatus::AlreadyInstalled, Some(c)));
    }
    if let Some(c) = wanted.candidates.iter().find(|c| planned.contains_key(&c.project_id)) {
        return Ok(outcome(ItemStatus::Duplicate, Some(c)));
    }
    if let Some(file) = existing.mod_files.iter().find(|f| wanted.file_conflicts.iter().any(|c| f.contains(c.as_str()))) {
        return Ok(ItemOutcome { detail: Some(file.clone()), ..outcome(ItemStatus::Incompatible, None) });
    }

    let mut last = outcome(ItemStatus::NotAvailable, None);
    for candidate in wanted.candidates.iter().filter(|c| !c.from_1_20 || at_least_1_20(&target.game_version)) {
        let versions = lookup.versions(&candidate.project_id, wanted.kind).await?;
        let Some(root) = modrinth::newest_in_channel(versions, target.channel) else { continue };
        let version_number = Some(root.version_number.clone()).filter(|v| !v.is_empty());
        match collect(lookup, target, existing, plan, planned, wanted.kind, root).await? {
            Collected::Ok { steps, replace } => {
                for (index, version) in replace {
                    plan.steps[index].version = version;
                    plan.steps[index].pinned = true;
                }
                // Die Wurzel kommt zuerst; auch unter der Preset-ID (evtl. ein Slug) merken.
                planned.insert(candidate.project_id.clone(), plan.steps.len());
                for step in steps {
                    planned.insert(step.version.project_id.clone(), plan.steps.len());
                    plan.steps.push(step);
                }
                return Ok(ItemOutcome { version_number, ..outcome(ItemStatus::Installed, Some(candidate)) });
            }
            Collected::Missing(id) => {
                let detail = lookup.title(&id).await.unwrap_or(id);
                last = ItemOutcome { detail: Some(detail), ..outcome(ItemStatus::MissingDependency, Some(candidate)) };
            }
            Collected::Incompatible(id) => {
                let detail = lookup.title(&id).await.unwrap_or(id);
                last = ItemOutcome { detail: Some(detail), ..outcome(ItemStatus::Incompatible, Some(candidate)) };
            }
        }
    }
    Ok(last)
}

/// Löst alle Einträge auf, ohne etwas zu installieren. `progress(fertig, gesamt)`.
pub(crate) async fn resolve<L: VersionLookup>(
    lookup: &L,
    target: &Target,
    existing: &Existing,
    wanted: &[Wanted],
    progress: &(dyn Fn(u32, u32) + Sync),
) -> Result<Plan> {
    let mut plan = Plan::default();
    let mut planned: HashMap<String, usize> = HashMap::new();
    let total = u32::try_from(wanted.len()).unwrap_or(u32::MAX);
    for (n, w) in wanted.iter().enumerate() {
        progress(u32::try_from(n).unwrap_or(u32::MAX), total);
        task::checkpoint().await?;
        let outcome = resolve_one(lookup, target, existing, &mut plan, &mut planned, w).await?;
        plan.items.push(outcome);
    }
    progress(total, total);
    // Festgelegte Versionen können die Wurzel eines Eintrags ersetzt haben.
    for step in plan.steps.iter().filter(|s| !s.dependency) {
        plan.items[step.item].version_number = Some(step.version.version_number.clone()).filter(|v| !v.is_empty());
    }
    Ok(plan)
}

// --- Installieren --------------------------------------------------------------------

/// Lädt alle Downloads des Plans. Scheitert einer, wird der Eintrag als
/// `failed` markiert und der Rest geladen; Abbrechen beendet sofort.
async fn execute(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
    plan: Plan,
    progress: &(dyn Fn(ApplyProgress) + Sync),
) -> Result<ApplyReport> {
    let Plan { steps, mut items } = plan;
    let total = u32::try_from(steps.len()).unwrap_or(u32::MAX);
    let mut installed = Vec::new();
    let mut failed: HashSet<usize> = HashSet::new();
    let mut dependencies = 0;

    for (n, step) in steps.iter().enumerate() {
        let title = if step.dependency { step.version.name.clone() } else { items[step.item].title.clone() };
        progress(ApplyProgress {
            phase: ApplyPhase::Install,
            done: u32::try_from(n).unwrap_or(u32::MAX),
            total,
            title: Some(title),
        });
        if failed.contains(&step.item) {
            continue;
        }
        let result = match task::checkpoint().await {
            Ok(()) => modrinth::install_version(http, paths, instance, step.kind, &step.version, None, step.dependency).await,
            Err(e) => Err(e),
        };
        match result {
            Ok(done) => {
                if step.dependency {
                    dependencies += 1;
                }
                installed.push(done);
            }
            Err(Error::Cancelled) => {
                // Was schon da ist, bleibt – samt Verlauf.
                modrinth::after_install(http, paths, &instance.id, &installed).await;
                return Err(Error::Cancelled);
            }
            Err(e) => {
                tracing::warn!("Preset-Download fehlgeschlagen ({}): {e}", step.version.project_id);
                items[step.item].status = ItemStatus::Failed;
                items[step.item].error = Some(e.to_user());
                failed.insert(step.item);
            }
        }
    }
    modrinth::after_install(http, paths, &instance.id, &installed).await;
    progress(ApplyProgress { phase: ApplyPhase::Install, done: total, total, title: None });
    Ok(ApplyReport {
        game_version: instance.game_version.clone(),
        loader: instance.loader.kind,
        items,
        files: installed.into_iter().map(|i| i.file_name).collect(),
        dependencies,
    })
}

async fn run(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
    wanted: &[Wanted],
    progress: &(dyn Fn(ApplyProgress) + Sync),
) -> Result<ApplyReport> {
    let existing = existing(paths, &instance.id).await?;
    let target = Target::of(instance);
    let lookup = ModrinthLookup { http, instance };
    let resolve_progress = |done, total| progress(ApplyProgress { phase: ApplyPhase::Resolve, done, total, title: None });
    let plan = resolve(&lookup, &target, &existing, wanted, &resolve_progress).await?;
    execute(http, paths, instance, plan, progress).await
}

/// Installiert die gewählten Presets in die Instanz – jede Mod nur, wenn es
/// eine passende Version (samt Pflicht-Abhängigkeiten) gibt.
pub async fn apply(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
    preset_ids: &[String],
    progress: &(dyn Fn(ApplyProgress) + Sync),
) -> Result<ApplyReport> {
    if preset_ids.is_empty() {
        return Err(Error::validation(crate::msg!("presets.noneSelected", "Kein Preset ausgewählt.")));
    }
    if preset_ids.len() > MAX_PRESETS + Builtin::ALL.len() {
        return Err(too_many());
    }
    let all = list(paths).await?;
    let mut wanted = Vec::new();
    let mut seen = HashSet::new();
    for id in preset_ids {
        if !seen.insert(id.as_str()) {
            continue;
        }
        let preset = all.iter().find(|p| &p.id == id).ok_or_else(not_found)?;
        wanted.extend(wanted_of(preset));
    }
    run(http, paths, instance, &wanted, progress).await
}

/// Das FPS-Boost-Preset (für die TRS-Optimierung und den Einrichtungs-
/// Assistenten). Liefert die neu installierten Dateien; Fehler nur, wenn es
/// für diese Version gar nichts davon gibt.
pub async fn install_fps_boost(http: &reqwest::Client, paths: &Paths, instance: &Instance) -> Result<Vec<String>> {
    let preset = view(
        &StoredPreset { id: Builtin::FpsBoost.id().to_owned(), name: String::new(), auto: true, items: Vec::new() },
        false,
    );
    let report = run(http, paths, instance, &wanted_of(&preset), &|_| {}).await?;
    let usable = report.items.iter().any(|i| {
        matches!(i.status, ItemStatus::Installed | ItemStatus::AlreadyInstalled | ItemStatus::Duplicate)
    });
    if !usable {
        if report.items.iter().all(|i| i.status == ItemStatus::NeedsLoader) {
            return Err(Error::validation(crate::msg!(
                "modrinth.vanillaNoMods",
                "Diese Instanz ist Vanilla – Mods brauchen eine Instanz mit Fabric, Quilt, Forge oder NeoForge."
            )));
        }
        return Err(Error::validation(crate::msg!(
            "modrinth.noPerformanceMods",
            "Für diese Version gibt es keine der Optimierungs-Mods."
        )));
    }
    Ok(report.files)
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex as StdMutex;

    use super::*;

    /// Attrappe: Versionen je Projekt (schon für Version + Loader gefiltert).
    #[derive(Default)]
    struct Mock {
        versions: HashMap<String, Vec<Version>>,
        by_id: HashMap<String, Version>,
        calls: StdMutex<Vec<String>>,
    }

    impl Mock {
        fn with(mut self, v: Version) -> Self {
            self.by_id.insert(v.id.clone(), v.clone());
            self.versions.entry(v.project_id.clone()).or_default().push(v);
            self
        }
        /// Nur per ID abrufbar (ältere, festgelegte Version).
        fn pinned(mut self, v: Version) -> Self {
            self.by_id.insert(v.id.clone(), v);
            self
        }
    }

    impl VersionLookup for Mock {
        fn versions(&self, project_id: &str, _kind: ContentKind) -> impl Future<Output = Result<Vec<Version>>> + Send {
            self.calls.lock().unwrap().push(project_id.to_owned());
            std::future::ready(Ok(self.versions.get(project_id).cloned().unwrap_or_default()))
        }
        fn version(&self, version_id: &str) -> impl Future<Output = Result<Option<Version>>> + Send {
            std::future::ready(Ok(self.by_id.get(version_id).cloned()))
        }
        fn title(&self, project_id: &str) -> impl Future<Output = Option<String>> + Send {
            std::future::ready(Some(format!("Titel {project_id}")))
        }
    }

    /// `deps`: (Typ, Projekt, festgelegte Version).
    fn version(id: &str, project: &str, deps: &[(&str, &str, Option<&str>)]) -> Version {
        serde_json::from_value(serde_json::json!({
            "id": id, "project_id": project, "version_number": format!("{id}-nr"), "version_type": "release",
            "game_versions": ["1.21.1"], "loaders": ["fabric"],
            "files": [{"url": format!("https://cdn.modrinth.com/{id}.jar"), "filename": format!("{id}.jar"),
                       "primary": true, "size": 1, "hashes": {"sha1": "a"}}],
            "dependencies": deps.iter().map(|(t, p, v)| serde_json::json!({
                "project_id": p, "version_id": v, "dependency_type": t
            })).collect::<Vec<_>>(),
        }))
        .unwrap()
    }

    fn target(game_version: &str) -> Target {
        Target { game_version: game_version.into(), loaders: &["fabric"], channel: UpdateChannel::Release }
    }

    fn want(preset: &str, ids: &[&str]) -> Wanted {
        Wanted {
            preset_id: preset.into(),
            kind: ContentKind::Mod,
            candidates: ids
                .iter()
                .map(|id| Candidate { project_id: (*id).into(), title: format!("T-{id}"), icon_url: None, from_1_20: false })
                .collect(),
            file_conflicts: Vec::new(),
            optional: false,
        }
    }

    async fn plan_for(mock: &Mock, target: &Target, existing: &Existing, wanted: &[Wanted]) -> Plan {
        resolve(mock, target, existing, wanted, &|_, _| {}).await.unwrap()
    }

    fn statuses(plan: &Plan) -> Vec<ItemStatus> {
        plan.items.iter().map(|i| i.status).collect()
    }

    fn step_ids(plan: &Plan) -> Vec<&str> {
        plan.steps.iter().map(|s| s.version.id.as_str()).collect()
    }

    #[tokio::test]
    async fn skips_what_is_not_available_and_says_why() {
        let mock = Mock::default().with(version("svc1", "voice", &[]));
        let wanted = [want("p", &["voice"]), want("p", &["flashback", "replaymod"])];
        let plan = plan_for(&mock, &target("1.12.2"), &Existing::default(), &wanted).await;
        assert_eq!(statuses(&plan), [ItemStatus::Installed, ItemStatus::NotAvailable]);
        assert_eq!(plan.items[1].title, "T-flashback / T-replaymod");
        assert_eq!(step_ids(&plan), ["svc1"]);
        assert_eq!(plan.items[0].version_number.as_deref(), Some("svc1-nr"));
    }

    #[tokio::test]
    async fn takes_the_first_available_alternative() {
        let mock = Mock::default().with(version("rm1", "replaymod", &[]));
        let plan = plan_for(&mock, &target("1.12.2"), &Existing::default(), &[want("p", &["flashback", "replaymod"])]).await;
        assert_eq!(statuses(&plan), [ItemStatus::Installed]);
        assert_eq!(plan.items[0].title, "T-replaymod");
        assert_eq!(step_ids(&plan), ["rm1"]);
    }

    #[tokio::test]
    async fn brings_required_dependencies_once() {
        let mock = Mock::default()
            .with(version("fb1", "flashback", &[("required", "fabric-api", None), ("optional", "extra", None)]))
            .with(version("svc1", "voice", &[("required", "fabric-api", None)]))
            .with(version("api1", "fabric-api", &[]));
        let wanted = [want("a", &["flashback"]), want("b", &["voice"]), want("c", &["fabric-api"])];
        let plan = plan_for(&mock, &target("1.21.1"), &Existing::default(), &wanted).await;
        assert_eq!(step_ids(&plan), ["fb1", "api1", "svc1"]);
        assert!(plan.steps[1].dependency);
        // Fabric API steht selbst im Preset, kommt aber schon als Abhängigkeit.
        assert_eq!(statuses(&plan), [ItemStatus::Installed, ItemStatus::Installed, ItemStatus::Duplicate]);
    }

    #[tokio::test]
    async fn missing_dependency_skips_the_mod_but_tries_alternatives() {
        let mock = Mock::default()
            .with(version("fb1", "flashback", &[("required", "gone", None)]))
            .with(version("rm1", "replaymod", &[]));
        let plan = plan_for(&mock, &target("1.21.1"), &Existing::default(), &[want("p", &["flashback", "replaymod"])]).await;
        assert_eq!(step_ids(&plan), ["rm1"]);

        let mock = Mock::default().with(version("fb1", "flashback", &[("required", "gone", None)]));
        let plan = plan_for(&mock, &target("1.21.1"), &Existing::default(), &[want("p", &["flashback"])]).await;
        assert!(plan.steps.is_empty());
        assert_eq!(statuses(&plan), [ItemStatus::MissingDependency]);
        assert_eq!(plan.items[0].detail.as_deref(), Some("Titel gone"));
    }

    #[tokio::test]
    async fn already_installed_and_duplicates_are_not_loaded_again() {
        let mock = Mock::default().with(version("s1", "sodium", &[])).with(version("l1", "lithium", &[]));
        let existing = Existing { projects: HashMap::from([("embeddium".into(), None)]), mod_files: Vec::new() };
        let wanted = [want("a", &["sodium", "embeddium"]), want("a", &["lithium"]), want("b", &["lithium"])];
        let plan = plan_for(&mock, &target("1.21.1"), &existing, &wanted).await;
        assert_eq!(statuses(&plan), [ItemStatus::AlreadyInstalled, ItemStatus::Installed, ItemStatus::Duplicate]);
        assert_eq!(plan.items[0].title, "T-embeddium");
        assert_eq!(step_ids(&plan), ["l1"]);
        // Sodium wurde gar nicht erst nachgeschlagen.
        assert!(!mock.calls.lock().unwrap().contains(&"sodium".to_owned()));
    }

    #[tokio::test]
    async fn vanilla_gets_no_mods_but_packs() {
        let mock = Mock::default().with(version("rp1", "faithful", &[]));
        let vanilla = Target { loaders: &[], ..target("1.21.1") };
        let mut pack = want("p", &["faithful"]);
        pack.kind = ContentKind::ResourcePack;
        let plan = plan_for(&mock, &vanilla, &Existing::default(), &[want("p", &["voice"]), pack]).await;
        assert_eq!(statuses(&plan), [ItemStatus::NeedsLoader, ItemStatus::Installed]);
    }

    #[tokio::test]
    async fn modernfix_only_from_1_20() {
        let mock = Mock::default().with(version("mf1", "modernfix", &[]));
        let mut wanted = want("p", &["modernfix"]);
        wanted.candidates[0].from_1_20 = true;
        let plan_old = plan_for(&mock, &target("1.19.4"), &Existing::default(), std::slice::from_ref(&wanted)).await;
        assert_eq!(statuses(&plan_old), [ItemStatus::NotAvailable]);
        let plan_new = plan_for(&mock, &target("1.20.1"), &Existing::default(), &[wanted]).await;
        assert_eq!(statuses(&plan_new), [ItemStatus::Installed]);
        assert!(at_least_1_20("26.1") && at_least_1_20("1.21.11") && !at_least_1_20("1.16.5"));
    }

    #[tokio::test]
    async fn optifine_blocks_renderer_mods() {
        let mock = Mock::default().with(version("s1", "sodium", &[]));
        let mut wanted = want("p", &["sodium"]);
        wanted.file_conflicts = vec!["optifine".into()];
        let existing = Existing { projects: HashMap::new(), mod_files: vec!["optifine_1.21.1_hd_u_j1.jar".into()] };
        let plan = plan_for(&mock, &target("1.21.1"), &existing, &[wanted]).await;
        assert_eq!(statuses(&plan), [ItemStatus::Incompatible]);
        assert!(plan.steps.is_empty());
    }

    #[tokio::test]
    async fn declared_incompatibilities_are_respected() {
        let mock = Mock::default()
            .with(version("a1", "alpha", &[("incompatible", "beta", None)]))
            .with(version("b1", "beta", &[]));
        let plan = plan_for(&mock, &target("1.21.1"), &Existing::default(), &[want("p", &["beta"]), want("p", &["alpha"])]).await;
        assert_eq!(statuses(&plan), [ItemStatus::Installed, ItemStatus::Incompatible]);
        assert_eq!(step_ids(&plan), ["b1"]);
    }

    #[tokio::test]
    async fn pinned_dependency_wins_over_newest() {
        // FPS-Boost plant die neueste Sodium-Version, Nvidium verlangt eine bestimmte.
        let old_sodium = version("s-old", "sodium", &[]);
        let mock = Mock::default()
            .with(version("s-new", "sodium", &[]))
            .pinned(old_sodium)
            .with(version("nv1", "nvidium", &[("required", "sodium", Some("s-old"))]));
        let plan = plan_for(&mock, &target("1.21.1"), &Existing::default(), &[want("a", &["sodium"]), want("b", &["nvidium"])]).await;
        assert_eq!(statuses(&plan), [ItemStatus::Installed, ItemStatus::Installed]);
        assert_eq!(step_ids(&plan), ["s-old", "nv1"]);
        assert_eq!(plan.items[0].version_number.as_deref(), Some("s-old-nr"));

        // Installiert ist eine andere Version: nicht einfach ersetzen.
        let existing = Existing { projects: HashMap::from([("sodium".into(), Some("s-new".into()))]), mod_files: Vec::new() };
        let plan = plan_for(&mock, &target("1.21.1"), &existing, &[want("b", &["nvidium"])]).await;
        assert_eq!(statuses(&plan), [ItemStatus::Incompatible]);
    }

    #[test]
    fn builtin_presets_are_well_formed() {
        for b in Builtin::ALL {
            assert_eq!(Builtin::from_id(b.id()), Some(b));
            for g in b.groups() {
                assert!(!g.mods.is_empty());
                for m in g.mods {
                    assert!(modrinth::is_safe_project_id(m.id), "{}", m.id);
                    if let Some(icon) = m.icon {
                        assert!(is_allowed_icon_url(&format!("{CDN}{icon}")), "{icon}");
                    }
                }
            }
        }
        // Renderer immer in einer Gruppe: nie Sodium und Embeddium zusammen.
        let renderer = FPS_BOOST.iter().find(|g| g.mods.iter().any(|m| m.title == "Sodium")).unwrap();
        assert!(renderer.mods.iter().any(|m| m.title == "Embeddium"));
        let legacy = FPS_BOOST.iter().find(|g| g.mods.iter().any(|m| m.title == "VintageFix")).unwrap();
        assert!(legacy.mods.iter().any(|m| m.title == "FoamFix"));
        assert!(!Builtin::FpsBoost.modpack_safe() && Builtin::VoiceChat.modpack_safe());
    }

    #[test]
    fn normalize_keeps_builtins_once_and_drops_garbage() {
        let own = |id: &str, name: &str| StoredPreset { id: id.into(), name: name.into(), auto: false, items: Vec::new() };
        let good = "0123456789abcdef0123456789abcdef";
        let list = normalize(vec![
            own(good, "Meine Basics"),
            own("trs-replay", "egal"),
            own(good, "doppelt"),
            own("../böse", "x"),
            own("fedcba9876543210fedcba9876543210", "   "),
        ]);
        let ids: Vec<&str> = list.iter().map(|p| p.id.as_str()).collect();
        // Fehlende fertige Presets landen hinter den vorhandenen fertigen.
        assert_eq!(ids, [good, "trs-replay", "trs-fps-boost", "trs-nvidium", "trs-voice-chat"]);
        assert!(list[1].name.is_empty());
        assert!(list.iter().find(|p| p.id == "trs-fps-boost").unwrap().auto);

        let fresh: Vec<String> = normalize(Vec::new()).into_iter().map(|p| p.id).collect();
        assert_eq!(fresh, Builtin::ALL.map(|b| b.id().to_owned()));
    }

    fn item(id: &str) -> PresetItem {
        PresetItem {
            source: PresetSource::Modrinth,
            project_id: id.into(),
            title: format!("  Titel {id}\n"),
            icon_url: Some("https://evil.example/x.png".into()),
            kind: ContentKind::Mod,
        }
    }

    #[test]
    fn items_are_validated_and_deduplicated() {
        let items = validate_items(vec![item("abc"), item("abc"), item("def")]).unwrap();
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].title, "Titel abc");
        assert!(items[0].icon_url.is_none());
        assert!(validate_items(vec![item("../x")]).is_err());
        assert!(validate_items((0..=MAX_ITEMS).map(|i| item(&format!("p{i}"))).collect()).is_err());
        assert!(validate_name(" ").is_err());
        assert!(validate_name(&"x".repeat(MAX_NAME_CHARS + 1)).is_err());
        assert_eq!(validate_name("  Meine Basics ").unwrap(), "Meine Basics");
    }

    #[test]
    fn export_round_trip_and_bad_files() {
        let items = vec![validate_item(item("abc")).unwrap()];
        let bytes = serde_json::to_vec(&ExportFile { format: EXPORT_FORMAT, version: 1, name: "Basics", items: &items }).unwrap();
        let (name, parsed) = parse_export(&bytes).unwrap();
        assert_eq!((name.as_str(), parsed), ("Basics", items));

        // Unbekannte Quelle (neuere Version) wird übersprungen, der Rest bleibt.
        let mixed = br#"{"format":"trs-preset","version":2,"name":"X","items":[
            {"source":"curseforge","projectId":"123","title":"CF","kind":"mod"},
            {"source":"modrinth","projectId":"AANobbMI","title":"Sodium","kind":"mod"}]}"#;
        assert_eq!(parse_export(mixed).unwrap().1.len(), 1);

        for bad in [&b"{}"[..], b"null", br#"{"format":"other","version":1,"name":"X","items":[]}"#,
            br#"{"format":"trs-preset","version":1,"name":"X","items":[]}"#] {
            assert!(parse_export(bad).is_err());
        }
        assert!(parse_export(&vec![b' '; MAX_IMPORT_BYTES as usize + 1]).is_err());
        assert_eq!(export_file_name("Meine/Basics"), "Meine_Basics.trs-preset.json");
        assert_eq!(export_file_name("???"), "preset.trs-preset.json");
    }

    #[test]
    fn unique_names() {
        assert_eq!(unique_name("A", &["B"]), "A");
        assert_eq!(unique_name("A", &["A", "A (2)"]), "A (3)");
        let long = "x".repeat(MAX_NAME_CHARS);
        assert_eq!(unique_name(&long, &[long.as_str()]).chars().count(), MAX_NAME_CHARS);
    }

    #[tokio::test]
    async fn store_round_trip() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        let listed = list(&paths).await.unwrap();
        assert_eq!(listed.len(), Builtin::ALL.len());

        let created = create(&paths, PresetInput { name: "Basics".into(), auto: true, items: vec![item("abc")] }).await.unwrap();
        assert!(is_user_id(&created.id));
        let updated =
            update(&paths, &created.id, PresetInput { name: "Neu".into(), auto: false, items: vec![item("def")] }).await.unwrap();
        assert_eq!((updated.name.as_str(), updated.items[0].project_id.as_str()), ("Neu", "def"));
        assert!(update(&paths, "trs-replay", PresetInput { name: "x".into(), auto: false, items: vec![] }).await.is_err());
        assert!(delete(&paths, "trs-fps-boost").await.is_err());
        assert!(set_auto(&paths, "trs-voice-chat", true).await.unwrap().auto);

        let mut ids: Vec<String> = list(&paths).await.unwrap().into_iter().map(|p| p.id).collect();
        ids.reverse();
        assert_eq!(reorder(&paths, &ids).await.unwrap()[0].id, created.id);
        assert!(reorder(&paths, &ids[1..]).await.is_err());

        let (file_name, bytes) = export(&paths, &created.id).await.unwrap();
        assert_eq!(file_name, "Neu.trs-preset.json");
        let imported = import(&paths, &bytes).await.unwrap();
        assert_eq!(imported.name, "Neu (2)");

        delete(&paths, &created.id).await.unwrap();
        let after = list(&paths).await.unwrap();
        assert_eq!(after.len(), Builtin::ALL.len() + 1);
        assert!(after.iter().find(|p| p.id == "trs-voice-chat").unwrap().auto);
    }
}
