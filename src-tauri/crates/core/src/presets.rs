//! Mod-Presets: benannte Listen von Mods, Ressourcen- und Shaderpaketen, die
//! sich beim Anlegen einer Instanz (oder später) in einem Rutsch installieren
//! lassen – jeweils nur, was es für Minecraft-Version + Modloader gibt.
//!
//! Eigene Presets liegen in `<daten>/presets.json`. Die fertigen TRS-Presets
//! („FPS-Boost“ in drei Stufen – Max FPS, Shader leicht, Shader schön –,
//! „Nvidium“, „Voice Chat“, „Replay“) stehen im Code; von ihnen merkt sich die
//! Datei nur Position und „immer automatisch“.
//!
//! Installiert wird in zwei Schritten: Erst wird alles aufgelöst (passende
//! Version, Pflicht-Abhängigkeiten, Duplikate, Konflikte) – ohne etwas
//! anzufassen –, dann wird geladen. Was nicht passt, wird übersprungen und
//! im Bericht mit Grund genannt.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::future::Future;
use std::path::{Path, PathBuf};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::client_mod::{self, BundledMod};
use crate::content::{self, ContentKind};
use crate::error::UserError;
use crate::icon::is_allowed_icon_url;
use crate::instance::{Instance, LoaderKind, UpdateChannel};
use crate::modcompat::{self, ModInfo};
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
    /// FPS-Boost, Stufe „Max FPS“: nur Optimierungs-Mods.
    FpsBoost,
    /// FPS-Boost, Stufe „Shader leicht“: dazu Iris + MakeUp – Ultra Fast.
    FpsShaderLite,
    /// FPS-Boost, Stufe „Shader schön“: dazu Iris + Complementary Reimagined.
    FpsShader,
    Nvidium,
    VoiceChat,
    Replay,
}

impl Builtin {
    pub const ALL: [Self; 6] =
        [Self::FpsBoost, Self::FpsShaderLite, Self::FpsShader, Self::Nvidium, Self::VoiceChat, Self::Replay];

    pub fn id(self) -> &'static str {
        match self {
            Self::FpsBoost => "trs-fps-boost",
            Self::FpsShaderLite => "trs-fps-shader-lite",
            Self::FpsShader => "trs-fps-shader",
            Self::Nvidium => "trs-nvidium",
            Self::VoiceChat => "trs-voice-chat",
            Self::Replay => "trs-replay",
        }
    }

    pub fn from_id(id: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|b| b.id() == id)
    }

    /// Eine der drei FPS-Boost-Stufen – davon ist höchstens eine gewählt.
    pub fn is_fps_tier(self) -> bool {
        matches!(self, Self::FpsBoost | Self::FpsShaderLite | Self::FpsShader)
    }

    /// Stufe mit Shadern (Iris + Shaderpaket).
    pub fn has_shaders(self) -> bool {
        matches!(self, Self::FpsShaderLite | Self::FpsShader)
    }

    /// FPS-Boost „Max FPS“ ist von Anfang an bei neuen Instanzen vorausgewählt.
    fn default_auto(self) -> bool {
        matches!(self, Self::FpsBoost)
    }

    /// Darf zu fertigen Modpacks dazu? Performance-Mods beißen sich dort
    /// leicht mit dem, was der Packautor gewählt hat.
    pub fn modpack_safe(self) -> bool {
        matches!(self, Self::VoiceChat | Self::Replay)
    }

    fn groups(self) -> Vec<&'static Group> {
        let extra: &'static [Group] = match self {
            Self::FpsShaderLite => SHADER_LITE,
            Self::FpsShader => SHADER_PRETTY,
            _ => &[],
        };
        let base: &'static [Group] = match self {
            Self::FpsBoost | Self::FpsShaderLite | Self::FpsShader => FPS_BOOST,
            Self::Nvidium => NVIDIUM,
            Self::VoiceChat => VOICE_CHAT,
            Self::Replay => REPLAY,
        };
        base.iter().chain(extra).collect()
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
    /// Mod (Standard) oder z. B. Shaderpaket.
    kind: ContentKind,
    /// Liegt eine Mod-Datei mit diesem Namensteil in der Instanz (z. B. von
    /// Hand installiertes OptiFine), wird die Zeile übersprungen.
    file_conflicts: &'static [&'static str],
    /// Verträgt sich nicht mit diesen Projekten (in der Instanz oder im Plan).
    project_conflicts: &'static [&'static str],
    /// Nur sinnvoll, wenn eines dieser Projekte da ist (Shaderpaket → Iris).
    requires: &'static [&'static str],
}

const fn m(id: &'static str, title: &'static str, icon: &'static str) -> BuiltinMod {
    BuiltinMod { id, title, icon: Some(icon), from_1_20: false }
}

const fn single(mods: &'static [BuiltinMod]) -> Group {
    Group { mods, kind: ContentKind::Mod, file_conflicts: &[], project_conflicts: &[], requires: &[] }
}

const IRIS_ID: &str = "YL57xq9U";
/// `detail` eingebauter Mods im Bericht.
const BUNDLED_DETAIL: &str = "TRS Client";
const IRIS_TITLE: &str = "Iris Shaders";
const NVIDIUM_ID: &str = "SfMw2IZN";

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
        kind: ContentKind::Mod,
        file_conflicts: &["optifine"],
        project_conflicts: &[],
        requires: &[],
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

/// Iris lädt Shaderpakete (Fabric, Quilt, NeoForge) – zusammen mit Sodium.
/// Nvidium ersetzt den Gelände-Renderer und verträgt sich nicht mit Shadern.
const IRIS: Group = Group {
    mods: &[m(IRIS_ID, IRIS_TITLE, "YL57xq9U/18d0e7f076d3d6ed5bedd472b853909aac5da202_96.webp")],
    kind: ContentKind::Mod,
    file_conflicts: &["optifine"],
    project_conflicts: &[NVIDIUM_ID],
    requires: &[],
};

/// Ein Shaderpaket, das nur zusammen mit Iris Sinn ergibt.
const fn shader_pack(mods: &'static [BuiltinMod]) -> Group {
    Group { mods, kind: ContentKind::ShaderPack, file_conflicts: &[], project_conflicts: &[], requires: &[IRIS_ID] }
}

/// „Shader leicht“: sehr sparsamer Shader, läuft auch auf schwachen PCs.
const SHADER_LITE: &[Group] = &[
    IRIS,
    shader_pack(&[m("izsIPI7a", "MakeUp – Ultra Fast", "izsIPI7a/a08432baa86b8ffd58c08f4b3a001ef976ff764d_96.webp")]),
];

/// „Shader schön“: Licht, Schatten und Wasser – braucht eine bessere Grafikkarte.
const SHADER_PRETTY: &[Group] = &[
    IRIS,
    shader_pack(&[m("HVnmMxH1", "Complementary Reimagined", "HVnmMxH1/79cb7c8123bbc54945305b2ebad6b8881efdf5f8_96.webp")]),
];

/// Nvidium rendert Gelände über Mesh-Shader – nur NVIDIA ab GTX 16xx/RTX 20xx.
const NVIDIUM: &[Group] = &[Group {
    mods: &[m(NVIDIUM_ID, "Nvidium", "SfMw2IZN/2db76d464a0f67cdb9e30fd99040eb096ac62016_96.webp")],
    kind: ContentKind::Mod,
    file_conflicts: &["optifine"],
    project_conflicts: &[IRIS_ID],
    requires: &[],
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
    /// Letzte Änderung (nur eigene Presets) – für den Abgleich mit dem TRS-Konto.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    updated_at: Option<DateTime<Utc>>,
}

impl StoredPreset {
    fn new(id: impl Into<String>, name: impl Into<String>, auto: bool, items: Vec<PresetItem>) -> Self {
        Self { id: id.into(), name: name.into(), auto, items, updated_at: None }
    }
}

/// Merkt sich gelöschte eigene Presets, damit die Löschung beim Abgleich auch
/// auf anderen PCs ankommt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PresetTombstone {
    pub id: String,
    pub deleted_at: DateTime<Utc>,
}

/// So lange bleiben Grabsteine gelöschter Presets liegen.
const TOMBSTONE_DAYS: i64 = 90;
const MAX_TOMBSTONES: usize = 200;

/// Alles außer der Liste selbst: Grabsteine und wann Reihenfolge bzw.
/// „immer automatisch“ der fertigen Presets zuletzt geändert wurden.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct Meta {
    deleted: Vec<PresetTombstone>,
    layout_updated_at: Option<DateTime<Utc>>,
}

impl Meta {
    fn bury(&mut self, id: &str, at: DateTime<Utc>) {
        self.deleted.retain(|t| t.id != id);
        self.deleted.push(PresetTombstone { id: id.to_owned(), deleted_at: at });
        self.deleted = prune_tombstones(std::mem::take(&mut self.deleted));
    }
}

fn prune_tombstones(mut deleted: Vec<PresetTombstone>) -> Vec<PresetTombstone> {
    let cutoff = Utc::now() - chrono::Duration::days(TOMBSTONE_DAYS);
    deleted.retain(|t| t.deleted_at > cutoff && is_user_id(&t.id));
    deleted.sort_by(|a, b| b.deleted_at.cmp(&a.deleted_at).then_with(|| a.id.cmp(&b.id)));
    deleted.dedup_by(|b, a| a.id == b.id);
    deleted.truncate(MAX_TOMBSTONES);
    deleted.sort_by(|a, b| a.id.cmp(&b.id));
    deleted
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredFile {
    #[serde(default)]
    presets: Vec<StoredPreset>,
    #[serde(default)]
    deleted: Vec<PresetTombstone>,
    #[serde(default)]
    layout_updated_at: Option<DateTime<Utc>>,
}

/// Wie [`StoredFile`], zum Schreiben (Reihenfolge der Felder bleibt lesbar).
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StoredFileRef<'a> {
    version: u32,
    presets: &'a [StoredPreset],
    #[serde(skip_serializing_if = "<[_]>::is_empty")]
    deleted: &'a [PresetTombstone],
    #[serde(skip_serializing_if = "Option::is_none")]
    layout_updated_at: Option<DateTime<Utc>>,
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
    read_all(paths).await.0
}

async fn read_all(paths: &Paths) -> (Vec<StoredPreset>, Meta) {
    match fsutil::read_json::<StoredFile>(&file(paths)).await {
        Ok(Some(f)) => (
            normalize(f.presets),
            Meta { deleted: prune_tombstones(f.deleted), layout_updated_at: f.layout_updated_at },
        ),
        Ok(None) => (normalize(Vec::new()), Meta::default()),
        Err(e) => {
            // Kaputte Datei: mit den Standard-Presets weiter, nichts abstürzen lassen.
            tracing::warn!("presets.json ist unlesbar – Standard wird benutzt: {e}");
            (normalize(Vec::new()), Meta::default())
        }
    }
}

async fn write_all(paths: &Paths, presets: &[StoredPreset], meta: &Meta) -> Result<()> {
    let file_ref = StoredFileRef {
        version: FILE_VERSION,
        presets,
        deleted: &meta.deleted,
        layout_updated_at: meta.layout_updated_at,
    };
    fsutil::write_json(&file(paths), &file_ref).await
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
            out.push(StoredPreset::new(p.id, "", p.auto, Vec::new()));
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
        out.push(StoredPreset { id: p.id, name, auto: p.auto, items, updated_at: p.updated_at });
    }
    let is_builtin = |p: &StoredPreset, tier_only: bool| Builtin::from_id(&p.id).is_some_and(|b| !tier_only || b.is_fps_tier());
    for b in Builtin::ALL.into_iter().filter(|b| !ids.contains(b.id())) {
        // Neue FPS-Stufen direkt hinter die vorhandene(n), sonst hinter das letzte fertige Preset.
        let after = b
            .is_fps_tier()
            .then(|| out.iter().rposition(|p| is_builtin(p, true)))
            .flatten()
            .or_else(|| out.iter().rposition(|p| is_builtin(p, false)));
        let at = after.map_or(0, |i| i + 1);
        out.insert(at, StoredPreset::new(b.id(), "", b.default_auto(), Vec::new()));
    }
    // Von den FPS-Stufen ist höchstens eine automatisch (die erste gewinnt).
    let mut tier_auto = false;
    for p in out.iter_mut().filter(|p| p.auto && is_builtin(p, true)) {
        p.auto = !std::mem::replace(&mut tier_auto, true);
    }
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
                kind: g.kind,
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
async fn modify<T>(paths: &Paths, change: impl FnOnce(&mut Vec<StoredPreset>, &mut Meta) -> Result<T>) -> Result<T> {
    let _guard = WRITE_LOCK.lock().await;
    let (mut list, mut meta) = read_all(paths).await;
    let out = change(&mut list, &mut meta)?;
    write_all(paths, &list, &meta).await?;
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
    let stored = modify(paths, |list, _| {
        if own_count(list) >= MAX_PRESETS {
            return Err(too_many());
        }
        let preset = StoredPreset {
            id: uuid::Uuid::new_v4().simple().to_string(),
            name,
            auto: input.auto,
            items,
            updated_at: Some(Utc::now()),
        };
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
    let stored = modify(paths, |list, _| {
        let preset = list.iter_mut().find(|p| p.id == id).ok_or_else(not_found)?;
        preset.name = name;
        preset.auto = input.auto;
        preset.items = items;
        preset.updated_at = Some(Utc::now());
        Ok(preset.clone())
    })
    .await?;
    Ok(view(&stored, true))
}

/// „Immer automatisch“ – auch für die fertigen Presets. Von den FPS-Stufen
/// ist höchstens eine automatisch: Eine anschalten schaltet die anderen ab.
pub async fn set_auto(paths: &Paths, id: &str, auto: bool) -> Result<Preset> {
    let stored = modify(paths, |list, meta| {
        if !list.iter().any(|p| p.id == id) {
            return Err(not_found());
        }
        if auto && Builtin::from_id(id).is_some_and(Builtin::is_fps_tier) {
            for other in list.iter_mut().filter(|p| p.id != id && Builtin::from_id(&p.id).is_some_and(Builtin::is_fps_tier)) {
                other.auto = false;
            }
        }
        let preset = list.iter_mut().find(|p| p.id == id).ok_or_else(not_found)?;
        preset.auto = auto;
        // Fertige Presets: nur ihre Schalter werden abgeglichen (zusammen mit der Reihenfolge).
        if Builtin::from_id(id).is_some() {
            meta.layout_updated_at = Some(Utc::now());
        } else {
            preset.updated_at = Some(Utc::now());
        }
        Ok(preset.clone())
    })
    .await?;
    Ok(view(&stored, crate::gpu::nvidium_capable()))
}

pub async fn delete(paths: &Paths, id: &str) -> Result<()> {
    if Builtin::from_id(id).is_some() {
        return Err(read_only());
    }
    modify(paths, |list, meta| {
        let before = list.len();
        list.retain(|p| p.id != id);
        if list.len() == before {
            return Err(not_found());
        }
        meta.bury(id, Utc::now());
        Ok(())
    })
    .await
}

/// Neue Reihenfolge – `ids` muss genau die vorhandenen Presets enthalten.
pub async fn reorder(paths: &Paths, ids: &[String]) -> Result<Vec<Preset>> {
    let list = modify(paths, |list, meta| {
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
        meta.layout_updated_at = Some(Utc::now());
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
    let stored = modify(paths, |list, _| {
        if own_count(list) >= MAX_PRESETS {
            return Err(too_many());
        }
        let taken: Vec<&str> = list.iter().map(|p| p.name.as_str()).collect();
        let preset = StoredPreset {
            id: uuid::Uuid::new_v4().simple().to_string(),
            name: unique_name(&name, &taken),
            auto: false,
            items,
            updated_at: Some(Utc::now()),
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

// --- Abgleich mit dem TRS-Konto ------------------------------------------------------
//
// Abgeglichen werden nur die eigenen Presets (IDs, Namen, Projekt-IDs – keine
// Dateien oder Pfade) sowie Reihenfolge und „immer automatisch“ der fertigen
// Presets. Zusammengeführt wird je Preset: die jüngere Änderung gewinnt,
// Grabsteine löschen ältere Stände. Gleich alt = der Stand vom Konto (damit
// sich zwei PCs nicht gegenseitig überschreiben).

/// Version des Formats in `data` auf dem TRS-Konto.
pub const SYNC_VERSION: u32 = 1;

/// Ein eigenes Preset im Abgleich.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncPreset {
    pub id: String,
    pub name: String,
    pub auto: bool,
    pub items: Vec<PresetItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub updated_at: Option<DateTime<Utc>>,
}

/// Reihenfolge aller Presets und „immer automatisch“ der fertigen.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncLayout {
    pub order: Vec<String>,
    pub auto: BTreeMap<String, bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub updated_at: Option<DateTime<Utc>>,
}

/// Inhalt von `data` beim Abgleich der Presets.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncData {
    pub version: u32,
    /// Nach ID sortiert.
    pub presets: Vec<SyncPreset>,
    /// Nach ID sortiert.
    pub deleted: Vec<PresetTombstone>,
    pub layout: SyncLayout,
}

fn parse_time(value: Option<&serde_json::Value>) -> Option<DateTime<Utc>> {
    let text = value?.as_str()?;
    DateTime::parse_from_rfc3339(text).ok().map(|d| d.with_timezone(&Utc))
}

impl SyncData {
    /// Liest `data` vom TRS-Konto nachsichtig: Kaputtes oder Unbekanntes fällt
    /// einzeln weg, der Rest bleibt.
    pub fn from_value(value: &serde_json::Value) -> Self {
        let mut presets: Vec<SyncPreset> = Vec::new();
        for raw in value.get("presets").and_then(|p| p.as_array()).into_iter().flatten() {
            let Some(id) = raw.get("id").and_then(|v| v.as_str()).filter(|id| is_user_id(id)) else { continue };
            let Ok(name) = validate_name(raw.get("name").and_then(|v| v.as_str()).unwrap_or_default()) else { continue };
            if presets.iter().any(|p| p.id == id) || presets.len() >= MAX_PRESETS {
                continue;
            }
            let mut seen = HashSet::new();
            let items: Vec<PresetItem> = raw
                .get("items")
                .and_then(|v| v.as_array())
                .into_iter()
                .flatten()
                .filter_map(|i| serde_json::from_value::<PresetItem>(i.clone()).ok())
                .filter_map(|i| validate_item(i).ok())
                .filter(|i| seen.insert((i.source, i.project_id.clone())))
                .take(MAX_ITEMS)
                .collect();
            presets.push(SyncPreset {
                id: id.to_owned(),
                name,
                auto: raw.get("auto").and_then(serde_json::Value::as_bool).unwrap_or(false),
                items,
                updated_at: parse_time(raw.get("updatedAt")),
            });
        }
        presets.sort_by(|a, b| a.id.cmp(&b.id));

        let deleted = value
            .get("deleted")
            .and_then(|d| d.as_array())
            .into_iter()
            .flatten()
            .filter_map(|t| {
                let id = t.get("id")?.as_str()?.to_owned();
                Some(PresetTombstone { id, deleted_at: parse_time(t.get("deletedAt"))? })
            })
            .collect();

        let layout = value.get("layout");
        let mut order: Vec<String> = Vec::new();
        for id in layout.and_then(|l| l.get("order")).and_then(|o| o.as_array()).into_iter().flatten().filter_map(|v| v.as_str()) {
            if (Builtin::from_id(id).is_some() || is_user_id(id)) && !order.iter().any(|o| o == id) && order.len() < 200 {
                order.push(id.to_owned());
            }
        }
        let auto = layout
            .and_then(|l| l.get("auto"))
            .and_then(|a| a.as_object())
            .into_iter()
            .flatten()
            .filter(|(id, _)| Builtin::from_id(id).is_some())
            .filter_map(|(id, on)| Some((id.clone(), on.as_bool()?)))
            .collect();
        Self {
            version: SYNC_VERSION,
            presets,
            deleted: prune_tombstones(deleted),
            layout: SyncLayout { order, auto, updated_at: parse_time(layout.and_then(|l| l.get("updatedAt"))) },
        }
    }

    /// Serialisiert (für `PUT /v1/me/sync/presets`).
    pub fn to_value(&self) -> serde_json::Value {
        serde_json::to_value(self).unwrap_or(serde_json::Value::Null)
    }

    /// Ohne Symbol-Adressen (falls die Daten sonst zu groß würden).
    pub fn without_icons(&self) -> Self {
        let mut out = self.clone();
        for item in out.presets.iter_mut().flat_map(|p| p.items.iter_mut()) {
            item.icon_url = None;
        }
        out
    }
}

/// Lokaler Stand in der Form des Abgleichs.
fn sync_view(list: &[StoredPreset], meta: &Meta) -> SyncData {
    let mut presets: Vec<SyncPreset> = list
        .iter()
        .filter(|p| Builtin::from_id(&p.id).is_none())
        .map(|p| SyncPreset {
            id: p.id.clone(),
            name: p.name.clone(),
            auto: p.auto,
            items: p.items.clone(),
            updated_at: p.updated_at,
        })
        .collect();
    presets.sort_by(|a, b| a.id.cmp(&b.id));
    SyncData {
        version: SYNC_VERSION,
        presets,
        deleted: meta.deleted.clone(),
        layout: SyncLayout {
            order: list.iter().map(|p| p.id.clone()).collect(),
            auto: list.iter().filter(|p| Builtin::from_id(&p.id).is_some()).map(|p| (p.id.clone(), p.auto)).collect(),
            updated_at: meta.layout_updated_at,
        },
    }
}

/// Führt lokalen und entfernten Stand zusammen (rein, getestet).
pub fn merge(local: &SyncData, remote: &SyncData) -> SyncData {
    // Grabsteine: Vereinigung, je ID der jüngste.
    let mut deleted: HashMap<String, DateTime<Utc>> = HashMap::new();
    for t in local.deleted.iter().chain(&remote.deleted) {
        let at = deleted.entry(t.id.clone()).or_insert(t.deleted_at);
        *at = (*at).max(t.deleted_at);
    }
    // Presets: Vereinigung, je ID die jüngere Änderung (gleich alt → Konto).
    let mut presets: BTreeMap<String, SyncPreset> = local.presets.iter().map(|p| (p.id.clone(), p.clone())).collect();
    for p in &remote.presets {
        match presets.get(&p.id) {
            Some(existing) if existing.updated_at > p.updated_at => {}
            _ => {
                presets.insert(p.id.clone(), p.clone());
            }
        }
    }
    // Gelöscht nach der letzten Änderung → weg; danach geändert → Grabstein weg.
    presets.retain(|id, p| match deleted.get(id) {
        Some(at) if p.updated_at.is_none_or(|u| u <= *at) => false,
        Some(_) => {
            deleted.remove(id);
            true
        }
        None => true,
    });
    let (winner, other) =
        if local.layout.updated_at > remote.layout.updated_at { (&local.layout, &remote.layout) } else { (&remote.layout, &local.layout) };
    let mut order: Vec<String> = Vec::new();
    for id in winner.order.iter().chain(&other.order).chain(presets.keys()) {
        let known = Builtin::from_id(id).is_some() || presets.contains_key(id);
        if known && !order.contains(id) {
            order.push(id.clone());
        }
    }
    let mut auto = other.auto.clone();
    auto.extend(winner.auto.iter().map(|(k, v)| (k.clone(), *v)));
    SyncData {
        version: SYNC_VERSION,
        presets: presets.into_values().collect(),
        deleted: prune_tombstones(deleted.into_iter().map(|(id, deleted_at)| PresetTombstone { id, deleted_at }).collect()),
        layout: SyncLayout { order, auto, updated_at: winner.updated_at.max(other.updated_at) },
    }
}

/// Baut aus einem zusammengeführten Stand die gespeicherte Liste.
fn from_sync(data: &SyncData) -> (Vec<StoredPreset>, Meta) {
    let by_id: HashMap<&str, &SyncPreset> = data.presets.iter().map(|p| (p.id.as_str(), p)).collect();
    let list = data
        .layout
        .order
        .iter()
        .filter_map(|id| match Builtin::from_id(id) {
            Some(b) => Some(StoredPreset::new(id.clone(), "", data.layout.auto.get(id).copied().unwrap_or(b.default_auto()), Vec::new())),
            None => by_id.get(id.as_str()).map(|p| StoredPreset {
                id: p.id.clone(),
                name: p.name.clone(),
                auto: p.auto,
                items: p.items.clone(),
                updated_at: p.updated_at,
            }),
        })
        .collect();
    (normalize(list), Meta { deleted: data.deleted.clone(), layout_updated_at: data.layout.updated_at })
}

/// Lokaler Stand für den Abgleich.
pub(crate) async fn sync_local(paths: &Paths) -> SyncData {
    let (list, meta) = read_all(paths).await;
    sync_view(&list, &meta)
}

/// Übernimmt den Stand vom Konto (zusammengeführt mit dem lokalen). Liefert
/// den neuen lokalen Stand und ob sich lokal etwas geändert hat.
pub(crate) async fn sync_merge(paths: &Paths, remote: &SyncData) -> Result<(SyncData, bool)> {
    let _guard = WRITE_LOCK.lock().await;
    let (list, meta) = read_all(paths).await;
    let before = sync_view(&list, &meta);
    let (list, meta) = from_sync(&merge(&before, remote));
    let after = sync_view(&list, &meta);
    let changed = after != before;
    if changed {
        write_all(paths, &list, &meta).await?;
    }
    Ok((after, changed))
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
    /// Verträgt sich nicht mit diesen Projekten (Instanz oder Plan).
    pub project_conflicts: Vec<String>,
    /// Nur, wenn eines dieser Projekte in der Instanz oder im Plan ist.
    pub requires: Vec<String>,
    /// Wird bewusst nicht installiert – wegen dieses Projekts (Titel).
    pub blocked_by: Option<String>,
    /// Teil einer Sammlung (FPS-Boost): fehlt es für die Version, ist das normal.
    pub optional: bool,
}

fn wanted_of(preset: &Preset) -> Vec<Wanted> {
    match preset.builtin {
        Some(b) => b
            .groups()
            .into_iter()
            .map(|g| Wanted {
                preset_id: preset.id.clone(),
                kind: g.kind,
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
                project_conflicts: g.project_conflicts.iter().map(|f| (*f).to_owned()).collect(),
                requires: g.requires.iter().map(|f| (*f).to_owned()).collect(),
                blocked_by: None,
                optional: b.is_fps_tier(),
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
                project_conflicts: Vec::new(),
                requires: Vec::new(),
                blocked_by: None,
                optional: false,
            })
            .collect(),
    }
}

/// Mod-ID (fabric.mod.json) → Modrinth-Projekt der Optimierungs-Mods, die der TRS
/// Client per Jar-in-Jar einbauen kann (`client-mod/fabric/bundled-mods.json`).
const BUNDLED_PROJECTS: &[(&str, &str)] = &[
    ("lithium", "gvQqBUqZ"),
    ("ferritecore", "uXXizFIs"),
    ("immediatelyfast", "5ZwdcRci"),
    ("modernfix", "nmDcB62a"),
    ("badoptimizations", "g96Z4WVZ"),
];

fn bundled_project(mod_id: &str) -> Option<&'static str> {
    BUNDLED_PROJECTS.iter().find(|(id, _)| *id == mod_id).map(|(_, project)| *project)
}

/// Wofür installiert wird.
#[derive(Debug, Clone)]
pub(crate) struct Target {
    pub game_version: String,
    /// Loader-Namen bei Modrinth; leer = Vanilla (keine Mods).
    pub loaders: &'static [&'static str],
    pub channel: UpdateChannel,
    /// Der Modloader als „Mod“ (z. B. `fabricloader` mit Version) für die Verträglichkeitsprüfung –
    /// dazu die im TRS Client eingebauten Mods (siehe [`Target::with_bundled`]).
    pub builtins: Vec<ModInfo>,
    /// Modrinth-Projekte, die der TRS Client in dieser Instanz schon eingebaut mitbringt:
    /// werden nicht extra geladen, zählen aber als vorhanden.
    pub bundled: HashSet<String>,
}

impl Target {
    fn of(instance: &Instance) -> Self {
        // Vanilla bleibt Vanilla: Mods brauchen einen echten Modloader. Die
        // TRS-Optimierung von Vanilla-Instanzen kümmert sich selbst um ihre Mods.
        let loaders = match instance.loader.kind {
            LoaderKind::Vanilla => &[][..],
            kind => modrinth::loader_tags(kind),
        };
        Self {
            game_version: instance.game_version.clone(),
            loaders,
            channel: instance.overrides.channel(),
            builtins: modcompat::loader_builtins(instance),
            bundled: HashSet::new(),
        }
    }

    /// Nimmt die eingebauten Mods des TRS Clients auf (leer = keine, z. B. wenn der
    /// Spieler „Eingebaute Optimierungen“ ausgeschaltet hat oder der TRS Client aus ist).
    fn with_bundled(mut self, mods: &[BundledMod]) -> Self {
        for m in mods {
            if let Some(project) = bundled_project(&m.id) {
                self.bundled.insert(project.to_owned());
            }
            // Auch ohne bekanntes Projekt: Für `depends`/`breaks` anderer Mods ist sie da.
            if modcompat::meta::is_mod_id(&m.id) && !self.builtins.iter().any(|b| b.id == m.id) {
                self.builtins.push(ModInfo {
                    id: m.id.clone(),
                    name: if m.name.is_empty() { m.id.clone() } else { m.name.clone() },
                    version: m.version.clone(),
                    scheme: modcompat::meta::Scheme::Fabric,
                    provides: Vec::new(),
                    depends: Vec::new(),
                    breaks: Vec::new(),
                });
            }
        }
        self
    }

    /// Bringt der TRS Client dieses Projekt schon mit?
    fn is_bundled(&self, project_id: &str) -> bool {
        self.bundled.contains(project_id)
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
    /// Aktivierte Mods mit ihren Angaben aus dem Jar (für die Verträglichkeitsprüfung).
    pub mods: Vec<modcompat::Entry>,
}

async fn existing(paths: &Paths, instance_id: &str) -> Result<Existing> {
    let mut projects = HashMap::new();
    for project_id in content::installed_project_ids(paths, instance_id).await? {
        let version = content::source_of_project(paths, instance_id, &project_id).await.map(|s| s.version_id);
        projects.insert(project_id, version.filter(|v| !v.is_empty()));
    }
    let mod_files =
        content::list(paths, instance_id, ContentKind::Mod).await?.into_iter().map(|i| i.file_name.to_lowercase()).collect();
    Ok(Existing { projects, mod_files, mods: Vec::new() })
}

/// Woher die Versionsdaten kommen – in Tests eine Attrappe.
pub(crate) trait VersionLookup: Sync {
    /// Zur Instanz passende Versionen, neueste zuerst; unbekanntes Projekt = leer.
    fn versions(&self, project_id: &str, kind: ContentKind) -> impl Future<Output = Result<Vec<Version>>> + Send;
    /// Eine bestimmte Version (für festgelegte Abhängigkeiten).
    fn version(&self, version_id: &str) -> impl Future<Output = Result<Option<Version>>> + Send;
    /// Anzeigename eines Projekts (für den Bericht).
    fn title(&self, project_id: &str) -> impl Future<Output = Option<String>> + Send;
    /// Was das Jar einer Version über sich sagt (`depends`/`breaks`); leer = unbekannt.
    fn mod_info(&self, version: &Version) -> impl Future<Output = Vec<ModInfo>> + Send;
}

/// Echte Daten von Modrinth – Versionslisten und Jar-Angaben werden je
/// Durchgang zwischengespeichert (weniger Anfragen beim Durchprobieren).
pub(crate) struct ModrinthLookup<'a> {
    http: &'a reqwest::Client,
    instance: &'a Instance,
    cache_dir: PathBuf,
    versions: std::sync::Mutex<HashMap<String, Vec<Version>>>,
    infos: std::sync::Mutex<HashMap<String, Vec<ModInfo>>>,
}

impl<'a> ModrinthLookup<'a> {
    pub(crate) fn new(http: &'a reqwest::Client, paths: &Paths, instance: &'a Instance) -> Self {
        Self {
            http,
            instance,
            cache_dir: modcompat::cache_dir(paths),
            versions: std::sync::Mutex::default(),
            infos: std::sync::Mutex::default(),
        }
    }
}

fn not_found_is_empty<T: Default>(result: Result<T>) -> Result<T> {
    match result {
        Err(Error::Http(e)) if e.status() == Some(reqwest::StatusCode::NOT_FOUND) => Ok(T::default()),
        other => other,
    }
}

impl VersionLookup for ModrinthLookup<'_> {
    async fn versions(&self, project_id: &str, kind: ContentKind) -> Result<Vec<Version>> {
        let key = format!("{kind:?}/{project_id}");
        if let Some(list) = self.versions.lock().unwrap_or_else(std::sync::PoisonError::into_inner).get(&key) {
            return Ok(list.clone());
        }
        let list = not_found_is_empty(modrinth::compatible_versions(self.http, project_id, kind, self.instance).await)?;
        self.versions.lock().unwrap_or_else(std::sync::PoisonError::into_inner).insert(key, list.clone());
        Ok(list)
    }

    async fn version(&self, version_id: &str) -> Result<Option<Version>> {
        not_found_is_empty(modrinth::version_by_id(self.http, version_id).await.map(Some))
    }

    async fn title(&self, project_id: &str) -> Option<String> {
        let cards = modrinth::project_cards(self.http, &[project_id.to_owned()]).await.ok()?;
        cards.into_iter().next().map(|c| c.title).filter(|t| !t.is_empty())
    }

    async fn mod_info(&self, version: &Version) -> Vec<ModInfo> {
        if let Some(infos) = self.infos.lock().unwrap_or_else(std::sync::PoisonError::into_inner).get(&version.id) {
            return infos.clone();
        }
        let Some(file) = version.primary_file() else { return Vec::new() };
        let infos = modcompat::remote::mod_info(self.http, &self.cache_dir, file).await;
        self.infos.lock().unwrap_or_else(std::sync::PoisonError::into_inner).insert(version.id.clone(), infos.clone());
        infos
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
    /// Schon installiert, aber gegen eine verträgliche Version getauscht (`compatWith`).
    Swapped,
    /// Im TRS Client eingebaut – wird nicht extra installiert (`detail` = „TRS Client“).
    Bundled,
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
    /// Version bewusst so gewählt, damit es mit dieser Mod läuft („Iris 1.10.7+mc1.21.11“).
    pub compat_with: Option<String>,
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
    /// Shaderpaket, das jetzt in Iris eingeschaltet ist (Dateiname).
    pub shader_pack: Option<String>,
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
        // Bringt der TRS Client schon mit (Jar-in-Jar) – nicht noch einmal laden.
        if target.is_bundled(&id) && !existing.projects.contains_key(&id) {
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
            .find(|id| {
                existing.projects.contains_key(*id)
                    || planned.contains_key(*id)
                    || local_ids.contains(*id)
                    || target.is_bundled(id)
            })
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
        compat_with: None,
    };

    if wanted.kind == ContentKind::Mod && target.loaders.is_empty() {
        return Ok(outcome(ItemStatus::NeedsLoader, None));
    }
    if let Some(other) = &wanted.blocked_by {
        return Ok(ItemOutcome { detail: Some(other.clone()), ..outcome(ItemStatus::Incompatible, None) });
    }
    let present =
        |id: &String| existing.projects.contains_key(id) || planned.contains_key(id) || target.is_bundled(id);
    // Shaderpaket ohne Iris (Forge, Vanilla, Iris gibt es hier nicht): fällt weg.
    if !wanted.requires.is_empty() && !wanted.requires.iter().any(present) {
        return Ok(outcome(ItemStatus::NotAvailable, None));
    }
    if let Some(c) = wanted.candidates.iter().find(|c| existing.projects.contains_key(&c.project_id)) {
        return Ok(outcome(ItemStatus::AlreadyInstalled, Some(c)));
    }
    // Eingebaut im TRS Client (eine eigene Kopie des Spielers geht oben vor).
    if wanted.kind == ContentKind::Mod
        && let Some(c) = wanted.candidates.iter().find(|c| target.is_bundled(&c.project_id))
    {
        return Ok(ItemOutcome { detail: Some(BUNDLED_DETAIL.to_owned()), ..outcome(ItemStatus::Bundled, Some(c)) });
    }
    if let Some(c) = wanted.candidates.iter().find(|c| planned.contains_key(&c.project_id)) {
        return Ok(outcome(ItemStatus::Duplicate, Some(c)));
    }
    if let Some(file) = existing.mod_files.iter().find(|f| wanted.file_conflicts.iter().any(|c| f.contains(c.as_str()))) {
        return Ok(ItemOutcome { detail: Some(file.clone()), ..outcome(ItemStatus::Incompatible, None) });
    }
    if let Some(other) = wanted.project_conflicts.iter().find(|id| present(id)) {
        let detail = lookup.title(other).await.unwrap_or_else(|| other.clone());
        return Ok(ItemOutcome { detail: Some(detail), ..outcome(ItemStatus::Incompatible, None) });
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
    // Modrinth kennt keine Versions-Bereiche: jetzt prüfen, was die Jars selbst verlangen.
    harmonize(lookup, target, existing, wanted, &mut plan).await?;
    progress(total, total);
    // Festgelegte Versionen können die Wurzel eines Eintrags ersetzt haben.
    for step in plan.steps.iter().filter(|s| !s.dependency) {
        plan.items[step.item].version_number = Some(step.version.version_number.clone()).filter(|v| !v.is_empty());
    }
    Ok(plan)
}

// --- Verträglichkeit (depends/breaks aus den Jars) -----------------------------------

/// Woher ein Prüf-Eintrag stammt.
#[derive(Debug, Clone, Copy)]
enum Origin {
    Step(usize),
    Existing,
}

/// So oft darf ein unverträglicher Eintrag höchstens herausfallen.
const MAX_DROPS: usize = 8;

/// Geplante Mods + die übrigen Mods der Instanz als Prüf-Einträge.
async fn compat_entries<L: VersionLookup>(lookup: &L, existing: &Existing, plan: &Plan) -> (Vec<modcompat::Entry>, Vec<Origin>) {
    let steps: Vec<usize> = (0..plan.steps.len()).filter(|&i| plan.steps[i].kind == ContentKind::Mod).collect();
    let infos = futures::future::join_all(steps.iter().map(|&i| lookup.mod_info(&plan.steps[i].version))).await;
    let mut entries = Vec::new();
    let mut origins = Vec::new();
    let mut planned: HashSet<&str> = HashSet::new();
    for (&i, mods) in steps.iter().zip(infos) {
        let step = &plan.steps[i];
        planned.insert(step.version.project_id.as_str());
        entries.push(modcompat::Entry {
            project_id: Some(step.version.project_id.clone()),
            version_id: Some(step.version.id.clone()),
            version: Some(step.version.clone()),
            adjustable: !step.pinned && !mods.is_empty(),
            mods,
            ..Default::default()
        });
        origins.push(Origin::Step(i));
    }
    // Was ersetzt wird, zählt nicht mehr.
    for entry in &existing.mods {
        if entry.project_id.as_deref().is_some_and(|p| planned.contains(p)) {
            continue;
        }
        entries.push(entry.clone());
        origins.push(Origin::Existing);
    }
    (entries, origins)
}

/// Welcher geplante Eintrag fällt bei einem unlösbaren Konflikt heraus? Der
/// spätere (die Grundausstattung wie Sodium hat Vorrang vor Iris). Liefert
/// Eintrag + die Mod, mit der er sich nicht verträgt.
fn droppable(conflicts: &[modcompat::Conflict], origins: &[Origin], plan: &Plan) -> Option<(usize, String)> {
    let item_of = |idx: usize| match origins.get(idx) {
        Some(Origin::Step(si)) => Some(plan.steps[*si].item),
        _ => None,
    };
    conflicts
        .iter()
        .flat_map(|c| {
            let mut out = vec![(item_of(c.declarer), c.target_label.clone())];
            if let modcompat::Party::Entry(t) = c.target {
                out.push((item_of(t), c.declarer_label.clone()));
            }
            out
        })
        .filter_map(|(item, other)| Some((item?, other)))
        .max_by_key(|(item, _)| *item)
}

/// Nimmt einen Eintrag samt Downloads aus dem Plan – und Shaderpakete, denen
/// damit Iris fehlt.
fn drop_item(plan: &mut Plan, existing: &Existing, wanted: &[Wanted], item: usize, status: ItemStatus, detail: Option<String>) {
    plan.steps.retain(|s| s.item != item);
    let outcome = &mut plan.items[item];
    outcome.status = status;
    outcome.detail = detail;
    outcome.version_number = None;
    outcome.compat_with = None;
    for (j, w) in wanted.iter().enumerate() {
        if j == item || w.requires.is_empty() || !plan.steps.iter().any(|s| s.item == j) {
            continue;
        }
        let still = w
            .requires
            .iter()
            .any(|id| existing.projects.contains_key(id) || plan.steps.iter().any(|s| &s.version.project_id == id));
        if !still {
            drop_item(plan, existing, wanted, j, ItemStatus::NotAvailable, None);
        }
    }
}

/// Schreibt die getauschten Versionen in den Plan (geplante Downloads bzw.
/// Tausch installierter Mods).
fn apply_swaps(plan: &mut Plan, entries: Vec<modcompat::Entry>, origins: Vec<Origin>) {
    for (entry, origin) in entries.into_iter().zip(origins) {
        let (Some(because), Some(version)) = (entry.because, entry.version) else { continue };
        match origin {
            Origin::Step(si) => {
                let step = &mut plan.steps[si];
                step.version = version;
                step.pinned = true;
                if !step.dependency {
                    plan.items[step.item].compat_with = Some(because);
                }
            }
            Origin::Existing => {
                let Some(project) = entry.project_id else { continue };
                let found = plan
                    .items
                    .iter()
                    .position(|i| i.project_id.as_deref() == Some(project.as_str()) && i.status == ItemStatus::AlreadyInstalled);
                let item = found.unwrap_or_else(|| {
                    let title = entry.mods.first().map_or_else(|| version.name.clone(), |m| m.display_name().to_owned());
                    plan.items.push(ItemOutcome {
                        preset_id: plan.items.first().map(|i| i.preset_id.clone()).unwrap_or_default(),
                        project_id: Some(project.clone()),
                        title,
                        icon_url: None,
                        kind: ContentKind::Mod,
                        status: ItemStatus::Swapped,
                        optional: false,
                        version_number: None,
                        detail: None,
                        error: None,
                        compat_with: None,
                    });
                    plan.items.len() - 1
                });
                let outcome = &mut plan.items[item];
                outcome.status = ItemStatus::Swapped;
                outcome.compat_with = Some(because);
                plan.steps.push(Step { kind: ContentKind::Mod, version, dependency: false, pinned: true, item });
            }
        }
    }
}

/// Prüft `depends`/`breaks` der Jars gegeneinander und gegen die Instanz.
/// Konflikte werden mit einer anderen Version gelöst (siehe [`modcompat::settle`]);
/// geht das nicht, fällt der spätere Eintrag als `incompatible` heraus –
/// lieber ohne Iris als ein Spiel, das nicht startet.
async fn harmonize<L: VersionLookup>(
    lookup: &L,
    target: &Target,
    existing: &Existing,
    wanted: &[Wanted],
    plan: &mut Plan,
) -> Result<()> {
    if target.loaders.is_empty() {
        return Ok(());
    }
    for _ in 0..=MAX_DROPS {
        let (mut entries, origins) = compat_entries(lookup, existing, plan).await;
        if entries.is_empty() {
            return Ok(());
        }
        let unresolved = modcompat::settle(lookup, target.channel, &mut entries, &target.builtins, None).await?;
        if let Some((item, other)) = droppable(&unresolved, &origins, plan) {
            tracing::info!("Preset-Eintrag {} fällt heraus – verträgt sich nicht mit {other}", plan.items[item].title);
            drop_item(plan, existing, wanted, item, ItemStatus::Incompatible, Some(other));
            continue;
        }
        for c in &unresolved {
            tracing::warn!("Mod-Konflikt in der Instanz ohne Lösung: {} ↔ {}", c.declarer_label, c.target_label);
        }
        apply_swaps(plan, entries, origins);
        return Ok(());
    }
    Ok(())
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
        shader_pack: None,
    })
}

async fn run(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
    builds: &[client_mod::Build],
    wanted: &[Wanted],
    progress: &(dyn Fn(ApplyProgress) + Sync),
) -> Result<ApplyReport> {
    let mut existing = existing(paths, &instance.id).await?;
    existing.mods = modcompat::installed_entries(paths, &instance.id).await?;
    // Was der TRS Client hier schon eingebaut mitbringt, lädt das Preset nicht noch einmal.
    let bundled = client_mod::builtin_mods(paths, builds, instance).await;
    let target = Target::of(instance).with_bundled(&bundled);
    let lookup = ModrinthLookup::new(http, paths, instance);
    let resolve_progress = |done, total| progress(ApplyProgress { phase: ApplyPhase::Resolve, done, total, title: None });
    let plan = resolve(&lookup, &target, &existing, wanted, &resolve_progress).await?;
    execute(http, paths, instance, plan, progress).await
}

/// Installiert die gewählten Presets in die Instanz – jede Mod nur, wenn es
/// eine passende Version (samt Pflicht-Abhängigkeiten) gibt. `builds`: die
/// TRS-Client-Builds (im TRS Client eingebaute Mods fallen weg).
pub async fn apply(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
    builds: &[client_mod::Build],
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
    if preset_ids.iter().any(|id| !all.iter().any(|p| &p.id == id)) {
        return Err(not_found());
    }
    let chosen: Vec<&Preset> = all.iter().filter(|p| preset_ids.contains(&p.id)).collect();
    let wanted = wanted_for(&chosen);
    let mut report = run(http, paths, instance, builds, &wanted, progress).await?;
    // Shader-Stufe: das Paket gleich in Iris einschalten.
    let tier = chosen.iter().filter_map(|p| p.builtin).find(|b| b.is_fps_tier());
    if let Some(shader_id) = tier.and_then(shader_project) {
        report.shader_pack = activate_shader(paths, instance, &report, shader_id).await;
    }
    Ok(report)
}

/// Was installiert werden soll – in der gespeicherten Reihenfolge der Presets.
/// Von den FPS-Stufen zählt nur die erste; mit einer Shader-Stufe bleibt
/// Nvidium draußen (es verträgt sich nicht mit Iris).
fn wanted_for(chosen: &[&Preset]) -> Vec<Wanted> {
    let tier = chosen.iter().filter_map(|p| p.builtin).find(|b| b.is_fps_tier());
    let shaders = tier.is_some_and(Builtin::has_shaders);
    let mut wanted = Vec::new();
    for preset in chosen {
        match preset.builtin {
            Some(b) if b.is_fps_tier() && Some(b) != tier => {
                tracing::info!("FPS-Stufe {} übersprungen – es gilt {}", b.id(), tier.map_or("", Builtin::id));
            }
            Some(Builtin::Nvidium) if shaders => {
                wanted.extend(wanted_of(preset).into_iter().map(|w| Wanted { blocked_by: Some(IRIS_TITLE.to_owned()), ..w }));
            }
            _ => wanted.extend(wanted_of(preset)),
        }
    }
    wanted
}

/// Projekt-ID des Shaderpakets einer FPS-Stufe.
fn shader_project(tier: Builtin) -> Option<&'static str> {
    tier.groups().into_iter().find(|g| g.kind == ContentKind::ShaderPack).map(|g| g.mods[0].id)
}

/// Schaltet das Shaderpaket in `config/iris.properties` ein – nur, wenn Iris
/// und das Paket da sind. Liefert den Dateinamen des Pakets.
async fn activate_shader(paths: &Paths, instance: &Instance, report: &ApplyReport, shader_id: &str) -> Option<String> {
    let ok = |id: &str| {
        report.items.iter().any(|i| {
            i.project_id.as_deref() == Some(id)
                && matches!(i.status, ItemStatus::Installed | ItemStatus::AlreadyInstalled | ItemStatus::Duplicate | ItemStatus::Swapped)
        })
    };
    if !ok(IRIS_ID) || !ok(shader_id) {
        return None;
    }
    let file = content::files_of_project(paths, &instance.id, shader_id)
        .await
        .into_iter()
        .find(|(kind, _)| *kind == ContentKind::ShaderPack)
        .map(|(_, file)| file)?;
    let config = paths.instance_game_dir(&instance.id).join("config").join("iris.properties");
    let before = tokio::fs::read(&config).await.map(|b| String::from_utf8_lossy(&b).into_owned()).unwrap_or_default();
    let text = set_properties(&before, &[("enableShaders", "true"), ("shaderPack", &file)]);
    match fsutil::write_atomic(&config, text.as_bytes()).await {
        Ok(()) => Some(file),
        Err(e) => {
            tracing::warn!("iris.properties konnte nicht geschrieben werden: {e}");
            None
        }
    }
}

/// Setzt Schlüssel in einer Java-Properties-Datei (andere Zeilen bleiben).
fn set_properties(text: &str, pairs: &[(&str, &str)]) -> String {
    let mut lines: Vec<String> = text.lines().map(str::to_owned).collect();
    for (key, value) in pairs {
        let line = format!("{key}={}", escape_property(value));
        match lines.iter().position(|l| property_key(l) == Some(*key)) {
            Some(i) => {
                lines[i] = line;
                // Doppelte Einträge desselben Schlüssels entfernen (sonst gilt der letzte).
                let mut n = i + 1;
                while n < lines.len() {
                    if property_key(&lines[n]) == Some(*key) {
                        lines.remove(n);
                    } else {
                        n += 1;
                    }
                }
            }
            None => lines.push(line),
        }
    }
    let mut out = lines.join("\n");
    out.push('\n');
    out
}

/// Schlüssel einer Properties-Zeile (ohne Kommentare/Leerzeilen).
fn property_key(line: &str) -> Option<&str> {
    let line = line.trim_start();
    if line.is_empty() || line.starts_with(['#', '!']) {
        return None;
    }
    let end = line.find(['=', ':', ' ', '\t']).unwrap_or(line.len());
    Some(&line[..end])
}

/// Wert für `Properties.load` (ISO-8859-1): Sonderzeichen und alles außerhalb ASCII escapen.
fn escape_property(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for (i, c) in value.chars().enumerate() {
        match c {
            '\\' => out.push_str("\\\\"),
            '=' | ':' | '#' | '!' => {
                out.push('\\');
                out.push(c);
            }
            ' ' if i == 0 => out.push_str("\\ "),
            c if c.is_ascii() && !c.is_ascii_control() => out.push(c),
            c => {
                let mut buf = [0u16; 2];
                for unit in c.encode_utf16(&mut buf) {
                    out.push_str(&format!("\\u{unit:04x}"));
                }
            }
        }
    }
    out
}

/// Namensteile von Mod-Dateien, die schon einen eigenen Renderer mitbringen.
const RENDERER_FILES: &[&str] = &["sodium", "embeddium", "rubidium", "optifine", "optifabric", "magnesium", "celeritas"];

/// Soll die Instanzseite „FPS-Boost anwenden“ vorschlagen? Nur bei Instanzen
/// mit Modloader, die weder Sodium/Embeddium noch OptiFine haben – und nie
/// bei Modpacks (die bringen ihre eigene Auswahl mit).
pub async fn suggest_fps_boost(paths: &Paths, instance: &Instance) -> Result<bool> {
    if !matches!(instance.loader.kind, LoaderKind::Fabric | LoaderKind::Quilt | LoaderKind::Forge | LoaderKind::NeoForge) {
        return Ok(false);
    }
    let from_modpack = crate::history::list(paths, &instance.id).await?.iter().any(|e| {
        e.kind == crate::history::HistoryKind::Created && e.detail.as_deref() == Some("modpack")
    });
    if from_modpack {
        return Ok(false);
    }
    let existing = existing(paths, &instance.id).await?;
    Ok(!has_renderer(&existing))
}

fn has_renderer(existing: &Existing) -> bool {
    let renderer_ids = FPS_BOOST[0].mods.iter().map(|m| m.id);
    renderer_ids.into_iter().any(|id| existing.projects.contains_key(id))
        || existing.mod_files.iter().any(|f| RENDERER_FILES.iter().any(|r| f.contains(r)))
}

/// Das FPS-Boost-Preset (für die TRS-Optimierung und den Einrichtungs-
/// Assistenten). Liefert die neu installierten Dateien; Fehler nur, wenn es
/// für diese Version gar nichts davon gibt.
pub async fn install_fps_boost(
    http: &reqwest::Client,
    paths: &Paths,
    builds: &[client_mod::Build],
    instance: &Instance,
) -> Result<Vec<String>> {
    let preset = view(
        &StoredPreset::new(Builtin::FpsBoost.id(), "", true, Vec::new()),
        false,
    );
    let report = run(http, paths, instance, builds, &wanted_of(&preset), &|_| {}).await?;
    let usable = report.items.iter().any(|i| {
        matches!(
            i.status,
            ItemStatus::Installed
                | ItemStatus::AlreadyInstalled
                | ItemStatus::Duplicate
                | ItemStatus::Swapped
                | ItemStatus::Bundled
        )
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
        /// Jar-Angaben je Versions-ID (`fabric.mod.json`).
        infos: HashMap<String, Vec<ModInfo>>,
    }

    impl Mock {
        fn with(mut self, v: Version) -> Self {
            self.by_id.insert(v.id.clone(), v.clone());
            self.versions.entry(v.project_id.clone()).or_default().push(v);
            self
        }
        /// Was im Jar der Version steht.
        fn jar(mut self, version_id: &str, fabric_mod_json: &str) -> Self {
            self.infos.insert(version_id.into(), vec![modcompat::meta::parse_fabric(fabric_mod_json).unwrap()]);
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
        fn mod_info(&self, version: &Version) -> impl Future<Output = Vec<ModInfo>> + Send {
            std::future::ready(self.infos.get(&version.id).cloned().unwrap_or_default())
        }
    }

    const SODIUM_14: &str = r#"{"id":"sodium","name":"Sodium","version":"0.8.14+mc1.21.11","breaks":{"iris":"<=1.10.7"}}"#;
    const SODIUM_13: &str = r#"{"id":"sodium","name":"Sodium","version":"0.8.13+mc1.21.11","breaks":{"iris":"<=1.10.7"}}"#;
    const SODIUM_12: &str = r#"{"id":"sodium","name":"Sodium","version":"0.8.12+mc1.21.11","breaks":{"iris":"<=1.10.6"}}"#;
    const IRIS_7: &str = r#"{"id":"iris","name":"Iris","version":"1.10.7+mc1.21.11","depends":{"sodium":["0.8.x"]}}"#;

    /// Sodium 0.8.14/0.8.13 brechen Iris 1.10.7, 0.8.12 nicht (wie auf Modrinth für 1.21.11).
    fn sodium_iris_mock() -> Mock {
        Mock::default()
            .with(version("s14", "sodium", &[]))
            .with(version("s13", "sodium", &[]))
            .with(version("s12", "sodium", &[]))
            .with(version("i7", IRIS_ID, &[]))
            .jar("s14", SODIUM_14)
            .jar("s13", SODIUM_13)
            .jar("s12", SODIUM_12)
            .jar("i7", IRIS_7)
    }

    #[tokio::test]
    async fn shader_tier_gets_a_sodium_that_iris_can_live_with() {
        let mock = sodium_iris_mock();
        let wanted = [want("tier", &["sodium"]), want("tier", &[IRIS_ID])];
        let plan = plan_for(&mock, &target("1.21.11"), &Existing::default(), &wanted).await;
        assert_eq!(statuses(&plan), [ItemStatus::Installed, ItemStatus::Installed]);
        assert_eq!(step_ids(&plan), ["s12", "i7"]);
        assert_eq!(plan.items[0].version_number.as_deref(), Some("s12-nr"));
        assert_eq!(plan.items[0].compat_with.as_deref(), Some("Iris 1.10.7+mc1.21.11"));
        assert_eq!(plan.items[1].compat_with, None);
    }

    #[tokio::test]
    async fn broken_instance_is_repaired_when_the_preset_is_applied_again() {
        let mock = sodium_iris_mock();
        let entry = |project: &str, id: &str, text: &str| modcompat::Entry {
            project_id: Some(project.into()),
            version_id: Some(id.into()),
            mods: vec![modcompat::meta::parse_fabric(text).unwrap()],
            adjustable: true,
            installed: Some((id.into(), vec![modcompat::meta::parse_fabric(text).unwrap()])),
            file_name: Some(format!("{id}.jar")),
            ..Default::default()
        };
        let existing = Existing {
            projects: HashMap::from([("sodium".into(), Some("s14".into())), (IRIS_ID.into(), Some("i7".into()))]),
            mod_files: vec!["s14.jar".into(), "i7.jar".into()],
            mods: vec![entry("sodium", "s14", SODIUM_14), entry(IRIS_ID, "i7", IRIS_7)],
        };
        let wanted = [want("tier", &["sodium"]), want("tier", &[IRIS_ID])];
        let plan = plan_for(&mock, &target("1.21.11"), &existing, &wanted).await;
        assert_eq!(statuses(&plan), [ItemStatus::Swapped, ItemStatus::AlreadyInstalled]);
        assert_eq!(step_ids(&plan), ["s12"]);
        assert_eq!(plan.items[0].version_number.as_deref(), Some("s12-nr"));
        assert_eq!(plan.items[0].compat_with.as_deref(), Some("Iris 1.10.7+mc1.21.11"));

        // Passt alles, bleibt auch alles, wie es ist.
        let existing = Existing {
            projects: HashMap::from([("sodium".into(), Some("s12".into())), (IRIS_ID.into(), Some("i7".into()))]),
            mod_files: Vec::new(),
            mods: vec![entry("sodium", "s12", SODIUM_12), entry(IRIS_ID, "i7", IRIS_7)],
        };
        let plan = plan_for(&mock, &target("1.21.11"), &existing, &wanted).await;
        assert_eq!(statuses(&plan), [ItemStatus::AlreadyInstalled, ItemStatus::AlreadyInstalled]);
        assert!(plan.steps.is_empty());
    }

    /// Gegen das echte Modrinth (nur lesend): Shader-Stufe für Fabric 1.21.11.
    /// `cargo test -p trs-core real_modrinth_shader_tier -- --ignored --nocapture`
    #[tokio::test]
    #[ignore = "braucht Internet (Modrinth)"]
    async fn real_modrinth_shader_tier_1_21_11() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        let instance = Instance {
            id: "real".into(),
            name: "Real".into(),
            game_version: "1.21.11".into(),
            loader: crate::instance::Loader { kind: LoaderKind::Fabric, version: Some("0.19.5".into()) },
            created_at: chrono::Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            icon: None,
            group: None,
            overrides: Default::default(),
        };
        let http = reqwest::Client::builder().user_agent("theredstonee/trs-launcher (compat test)").build().unwrap();
        let lookup = ModrinthLookup::new(&http, &paths, &instance);
        let wanted = wanted_for(&[&builtin_view(Builtin::FpsShaderLite)]);
        let plan = resolve(&lookup, &Target::of(&instance), &Existing::default(), &wanted, &|_, _| {}).await.unwrap();
        for step in &plan.steps {
            println!("{} {} {}", step.version.project_id, step.version.version_number, if step.pinned { "(festgelegt)" } else { "" });
        }
        for item in &plan.items {
            println!("{:?} {} {:?} {:?} {:?}", item.status, item.title, item.version_number, item.compat_with, item.detail);
        }
        let version_of = |project: &str| plan.steps.iter().find(|s| s.version.project_id == project).map(|s| s.version.version_number.clone());
        let sodium = version_of("AANobbMI").expect("Sodium im Plan");
        assert!(version_of(IRIS_ID).is_some(), "Iris im Plan");
        // Die geplanten Jars dürfen sich nicht widersprechen.
        let mut entries = Vec::new();
        for step in plan.steps.iter().filter(|s| s.kind == ContentKind::Mod) {
            entries.push(modcompat::Entry { mods: lookup.mod_info(&step.version).await, ..Default::default() });
        }
        let conflicts = modcompat::find_conflicts(&entries, &modcompat::loader_builtins(&instance));
        assert!(conflicts.is_empty(), "{conflicts:?}");
        assert!(!sodium.contains("0.8.14"), "{sodium}");

        // Die kaputte Kombination aus dem Fehlerbericht: Sodium 0.8.14 + Iris 1.10.7.
        let mut broken = Vec::new();
        for id in ["rkdTcxoT", "fDpuVzVr"] {
            let version = lookup.version(id).await.unwrap().unwrap();
            broken.push(modcompat::Entry {
                project_id: Some(version.project_id.clone()),
                version_id: Some(version.id.clone()),
                mods: lookup.mod_info(&version).await,
                version: Some(version),
                adjustable: true,
                ..Default::default()
            });
        }
        assert_eq!(modcompat::find_conflicts(&broken, &[]).len(), 1);
        let left = modcompat::settle(&lookup, UpdateChannel::Release, &mut broken, &[], None).await.unwrap();
        assert!(left.is_empty(), "{left:?}");
        let fixed = broken[0].version.as_ref().unwrap();
        println!("Tausch: Sodium → {} (wegen {:?})", fixed.version_number, broken[0].because);
        assert!(broken[1].because.is_none());
    }

    #[tokio::test]
    async fn without_a_consistent_set_the_later_item_is_left_out() {
        // Nur Sodium 0.8.14 – keine Version verträgt sich mit Iris 1.10.7.
        let mock = Mock::default()
            .with(version("s14", "sodium", &[]))
            .with(version("i7", IRIS_ID, &[]))
            .with(version("mu1", "makeup", &[]))
            .jar("s14", SODIUM_14)
            .jar("i7", IRIS_7);
        let [iris, pack] = shader_wanted();
        let wanted = [want("tier", &["sodium"]), iris, pack];
        let plan = plan_for(&mock, &target("1.21.11"), &Existing::default(), &wanted).await;
        assert_eq!(statuses(&plan), [ItemStatus::Installed, ItemStatus::Incompatible, ItemStatus::NotAvailable]);
        assert_eq!(plan.items[1].detail.as_deref(), Some("Sodium 0.8.14+mc1.21.11"));
        assert_eq!(step_ids(&plan), ["s14"]);
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
        Target {
            game_version: game_version.into(),
            loaders: &["fabric"],
            channel: UpdateChannel::Release,
            builtins: Vec::new(),
            bundled: HashSet::new(),
        }
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
            project_conflicts: Vec::new(),
            requires: Vec::new(),
            blocked_by: None,
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
        let existing = Existing { projects: HashMap::from([("embeddium".into(), None)]), ..Default::default() };
        let wanted = [want("a", &["sodium", "embeddium"]), want("a", &["lithium"]), want("b", &["lithium"])];
        let plan = plan_for(&mock, &target("1.21.1"), &existing, &wanted).await;
        assert_eq!(statuses(&plan), [ItemStatus::AlreadyInstalled, ItemStatus::Installed, ItemStatus::Duplicate]);
        assert_eq!(plan.items[0].title, "T-embeddium");
        assert_eq!(step_ids(&plan), ["l1"]);
        // Sodium wurde gar nicht erst nachgeschlagen.
        assert!(!mock.calls.lock().unwrap().contains(&"sodium".to_owned()));
    }

    const LITHIUM: &str = "gvQqBUqZ";

    fn bundled_lithium() -> Vec<BundledMod> {
        vec![
            BundledMod { id: "lithium".into(), name: "Lithium".into(), version: "0.15.4".into() },
            // Unbekannt (kein Modrinth-Projekt hinterlegt): zählt nur für die Verträglichkeit.
            BundledMod { id: "somethingelse".into(), name: String::new(), version: "1.0.0".into() },
        ]
    }

    #[tokio::test]
    async fn mods_built_into_the_trs_client_are_not_installed_again() {
        let mock = Mock::default().with(version("s1", "sodium", &[])).with(version("l1", LITHIUM, &[]));
        let bundled = target("1.21.1").with_bundled(&bundled_lithium());
        assert!(bundled.is_bundled(LITHIUM));
        assert_eq!(bundled.builtins.iter().map(|b| b.id.as_str()).collect::<Vec<_>>(), ["lithium", "somethingelse"]);
        let wanted = [want("fps", &["sodium"]), want("fps", &[LITHIUM])];
        let plan = plan_for(&mock, &bundled, &Existing::default(), &wanted).await;
        assert_eq!(statuses(&plan), [ItemStatus::Installed, ItemStatus::Bundled]);
        assert_eq!(plan.items[1].detail.as_deref(), Some("TRS Client"));
        assert_eq!(plan.items[1].project_id.as_deref(), Some(LITHIUM));
        assert_eq!(step_ids(&plan), ["s1"]);
        assert!(!mock.calls.lock().unwrap().contains(&LITHIUM.to_owned()), "gar nicht erst nachgeschlagen");

        // Eigene Kopie des Spielers: bleibt „schon installiert“.
        let existing = Existing { projects: HashMap::from([(LITHIUM.into(), None)]), ..Default::default() };
        let plan = plan_for(&mock, &bundled, &existing, &wanted[1..]).await;
        assert_eq!(statuses(&plan), [ItemStatus::AlreadyInstalled]);

        // Eingebaute Optimierungen aus bzw. TRS Client aus (leere Liste): wird wieder installiert.
        let plan = plan_for(&mock, &target("1.21.1").with_bundled(&[]), &Existing::default(), &wanted).await;
        assert_eq!(statuses(&plan), [ItemStatus::Installed, ItemStatus::Installed]);
        assert_eq!(step_ids(&plan), ["s1", "l1"]);
    }

    #[tokio::test]
    async fn a_dependency_built_into_the_trs_client_is_not_downloaded() {
        let mock = Mock::default()
            .with(version("x1", "xmod", &[("required", LITHIUM, None)]))
            .with(version("l1", LITHIUM, &[]))
            .with(version("y1", "ymod", &[("incompatible", LITHIUM, None)]));
        let bundled = target("1.21.1").with_bundled(&bundled_lithium());
        let plan = plan_for(&mock, &bundled, &Existing::default(), &[want("p", &["xmod"]), want("p", &["ymod"])]).await;
        assert_eq!(statuses(&plan), [ItemStatus::Installed, ItemStatus::Incompatible]);
        assert_eq!(step_ids(&plan), ["x1"]);
    }

    #[tokio::test]
    async fn constraints_against_built_in_mods_are_still_checked() {
        let mock = Mock::default()
            .with(version("x1", "xmod", &[]))
            .with(version("y1", "ymod", &[]))
            .with(version("z1", "zmod", &[]))
            .jar("x1", r#"{"id":"xmod","name":"X","version":"1.0.0","breaks":{"lithium":"<0.20"}}"#)
            .jar("y1", r#"{"id":"ymod","name":"Y","version":"1.0.0","depends":{"lithium":">=0.20"}}"#)
            .jar("z1", r#"{"id":"zmod","name":"Z","version":"1.0.0","depends":{"lithium":">=0.15"}}"#);
        let wanted = [want("p", &["xmod"]), want("p", &["ymod"]), want("p", &["zmod"])];
        let bundled = target("1.21.1").with_bundled(&bundled_lithium());
        let plan = plan_for(&mock, &bundled, &Existing::default(), &wanted).await;
        assert_eq!(statuses(&plan), [ItemStatus::Incompatible, ItemStatus::Incompatible, ItemStatus::Installed]);
        assert_eq!(plan.items[0].detail.as_deref(), Some("Lithium 0.15.4"));
        assert_eq!(step_ids(&plan), ["z1"]);
        // Ohne eingebautes Lithium gibt es nichts zu beanstanden.
        let plan = plan_for(&mock, &target("1.21.1"), &Existing::default(), &wanted).await;
        assert_eq!(statuses(&plan), [ItemStatus::Installed, ItemStatus::Installed, ItemStatus::Installed]);
    }

    #[test]
    fn bundled_projects_are_fps_boost_mods() {
        for (mod_id, project) in BUNDLED_PROJECTS {
            assert!(modcompat::meta::is_mod_id(mod_id), "{mod_id}");
            assert!(FPS_BOOST.iter().any(|g| g.mods.iter().any(|m| m.id == *project)), "{mod_id} fehlt im FPS-Boost");
        }
        assert_eq!(bundled_project("lithium"), Some(LITHIUM));
        assert_eq!(bundled_project("sodium"), None, "Sodium baut der TRS Client nicht ein");
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
        let existing = Existing { projects: HashMap::new(), mod_files: vec!["optifine_1.21.1_hd_u_j1.jar".into()], ..Default::default() };
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
        let existing = Existing { projects: HashMap::from([("sodium".into(), Some("s-new".into()))]), ..Default::default() };
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

        // Stufen: alle mit dem ganzen FPS-Boost; Shader-Stufen dazu Iris + genau ein Shaderpaket, das Iris braucht.
        let tiers: Vec<Builtin> = Builtin::ALL.into_iter().filter(|b| b.is_fps_tier()).collect();
        assert_eq!(tiers, [Builtin::FpsBoost, Builtin::FpsShaderLite, Builtin::FpsShader]);
        for tier in tiers {
            assert!(!tier.modpack_safe());
            assert_eq!(tier.default_auto(), tier == Builtin::FpsBoost, "Standard bleibt „Max FPS“");
            let groups = tier.groups();
            assert!(FPS_BOOST.iter().all(|g| groups.iter().any(|x| x.mods[0].id == g.mods[0].id)));
            let packs: Vec<_> = groups.iter().filter(|g| g.kind == ContentKind::ShaderPack).collect();
            let has_iris = groups.iter().any(|g| g.mods[0].id == IRIS_ID);
            assert_eq!(tier.has_shaders(), has_iris);
            assert_eq!(packs.len(), usize::from(tier.has_shaders()));
            for p in packs {
                assert_eq!(p.requires, [IRIS_ID]);
            }
        }
        assert_eq!(shader_project(Builtin::FpsShaderLite), Some("izsIPI7a"));
        assert_eq!(shader_project(Builtin::FpsShader), Some("HVnmMxH1"));
        assert_eq!(shader_project(Builtin::FpsBoost), None);
        // Nvidium und Iris schließen sich gegenseitig aus.
        assert_eq!(NVIDIUM[0].project_conflicts, [IRIS_ID]);
        assert_eq!(IRIS.project_conflicts, [NVIDIUM_ID]);
        let items = builtin_items(Builtin::FpsShader);
        assert_eq!(items.last().unwrap().kind, ContentKind::ShaderPack);
    }

    fn builtin_view(b: Builtin) -> Preset {
        view(&StoredPreset::new(b.id(), "", false, Vec::new()), true)
    }

    fn ids_of(wanted: &[Wanted]) -> Vec<&str> {
        wanted.iter().map(|w| w.candidates[0].project_id.as_str()).collect()
    }

    #[test]
    fn only_one_fps_tier_and_no_nvidium_with_shaders() {
        let (max, lite, pretty, nv) = (
            builtin_view(Builtin::FpsBoost),
            builtin_view(Builtin::FpsShaderLite),
            builtin_view(Builtin::FpsShader),
            builtin_view(Builtin::Nvidium),
        );
        // Zwei Stufen gewählt: nur die erste zählt (kein doppeltes Shaderpaket).
        let wanted = wanted_for(&[&lite, &pretty]);
        let ids = ids_of(&wanted);
        assert!(ids.contains(&"izsIPI7a") && !ids.contains(&"HVnmMxH1"));
        assert_eq!(ids.iter().filter(|id| **id == IRIS_ID).count(), 1);
        assert!(wanted.iter().all(|w| w.optional));

        // Shader + Nvidium: Nvidium wird mit Grund übersprungen.
        let wanted = wanted_for(&[&pretty, &nv]);
        let nvidium = wanted.iter().find(|w| w.candidates[0].project_id == NVIDIUM_ID).unwrap();
        assert_eq!(nvidium.blocked_by.as_deref(), Some(IRIS_TITLE));
        // Max FPS + Nvidium: geht wie bisher.
        let wanted = wanted_for(&[&max, &nv]);
        assert!(wanted.iter().all(|w| w.blocked_by.is_none()));
        assert!(!ids_of(&wanted).contains(&IRIS_ID));
    }

    fn shader_wanted() -> [Wanted; 2] {
        let mut iris = want("tier", &[IRIS_ID]);
        iris.project_conflicts = vec![NVIDIUM_ID.into()];
        iris.optional = true;
        let mut pack = want("tier", &["makeup"]);
        pack.kind = ContentKind::ShaderPack;
        pack.requires = vec![IRIS_ID.into()];
        pack.optional = true;
        [iris, pack]
    }

    #[tokio::test]
    async fn shader_pack_needs_iris() {
        let mock = Mock::default().with(version("iris1", IRIS_ID, &[])).with(version("mu1", "makeup", &[]));
        let plan = plan_for(&mock, &target("1.21.1"), &Existing::default(), &shader_wanted()).await;
        assert_eq!(statuses(&plan), [ItemStatus::Installed, ItemStatus::Installed]);
        assert_eq!(plan.steps[1].kind, ContentKind::ShaderPack);

        // Kein Iris (z. B. Forge): Paket fällt still weg, ohne nachzuschlagen.
        let mock = Mock::default().with(version("mu1", "makeup", &[]));
        let plan = plan_for(&mock, &target("1.20.1"), &Existing::default(), &shader_wanted()).await;
        assert_eq!(statuses(&plan), [ItemStatus::NotAvailable, ItemStatus::NotAvailable]);
        assert!(plan.items.iter().all(|i| i.optional) && plan.steps.is_empty());
        assert!(!mock.calls.lock().unwrap().contains(&"makeup".to_owned()));

        // Iris schon in der Instanz: Paket kommt dazu.
        let existing = Existing { projects: HashMap::from([(IRIS_ID.into(), None)]), ..Default::default() };
        let plan = plan_for(&mock, &target("1.21.1"), &existing, &shader_wanted()).await;
        assert_eq!(statuses(&plan), [ItemStatus::AlreadyInstalled, ItemStatus::Installed]);
    }

    #[tokio::test]
    async fn nvidium_in_the_instance_blocks_iris() {
        let mock = Mock::default().with(version("iris1", IRIS_ID, &[])).with(version("mu1", "makeup", &[]));
        let existing = Existing { projects: HashMap::from([(NVIDIUM_ID.into(), None)]), ..Default::default() };
        let plan = plan_for(&mock, &target("1.21.1"), &existing, &shader_wanted()).await;
        assert_eq!(statuses(&plan), [ItemStatus::Incompatible, ItemStatus::NotAvailable]);
        assert_eq!(plan.items[0].detail.as_deref(), Some(format!("Titel {NVIDIUM_ID}").as_str()));

        // Bewusst ausgeschlossen: Grund steht im Bericht.
        let mut nv = want("nv", &[NVIDIUM_ID]);
        nv.blocked_by = Some(IRIS_TITLE.into());
        let plan = plan_for(&mock, &target("1.21.1"), &Existing::default(), &[nv]).await;
        assert_eq!(statuses(&plan), [ItemStatus::Incompatible]);
        assert_eq!(plan.items[0].detail.as_deref(), Some(IRIS_TITLE));
    }

    #[test]
    fn iris_properties_keep_other_keys() {
        let before = "# Iris\ncolorSpace=SRGB\nenableShaders=false\nshaderPack=Alt.zip\nshaderPack=Doppelt.zip\n";
        let after = set_properties(before, &[("enableShaders", "true"), ("shaderPack", "MakeUp-UltraFast-9.5e.zip")]);
        assert_eq!(after, "# Iris\ncolorSpace=SRGB\nenableShaders=true\nshaderPack=MakeUp-UltraFast-9.5e.zip\n");
        assert_eq!(set_properties("", &[("enableShaders", "true")]), "enableShaders=true\n");
        // Sonderzeichen und Umlaute so, wie Properties.load sie liest.
        assert_eq!(escape_property("a=b:c#ä \\"), "a\\=b\\:c\\#\\u00e4 \\\\");
        assert_eq!(escape_property(" x"), "\\ x");
        assert_eq!(property_key("  shaderPack = x"), Some("shaderPack"));
        assert_eq!(property_key("# shaderPack=x"), None);
    }

    #[tokio::test]
    async fn shader_is_switched_on_after_install() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        let inst = Instance {
            id: "shader".into(),
            name: "S".into(),
            game_version: "1.21.1".into(),
            loader: crate::instance::Loader { kind: LoaderKind::Fabric, version: None },
            created_at: chrono::Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            icon: None,
            group: None,
            overrides: Default::default(),
        };
        let file = "MakeUp-UltraFast-9.5e.zip";
        content::remember_source(
            &paths,
            &inst.id,
            ContentKind::ShaderPack,
            file,
            content::Source::modrinth("izsIPI7a".into(), "v1".into(), None),
        )
        .await
        .unwrap();
        let config = paths.instance_game_dir(&inst.id).join("config/iris.properties");
        tokio::fs::create_dir_all(config.parent().unwrap()).await.unwrap();
        tokio::fs::write(&config, "maxShadowRenderDistance=16\n").await.unwrap();

        let item = |id: &str, status| ItemOutcome {
            preset_id: "trs-fps-shader-lite".into(),
            project_id: Some(id.into()),
            title: id.into(),
            icon_url: None,
            kind: ContentKind::Mod,
            status,
            optional: true,
            version_number: None,
            detail: None,
            error: None,
            compat_with: None,
        };
        let mut report = ApplyReport {
            game_version: "1.21.1".into(),
            loader: LoaderKind::Fabric,
            items: vec![item(IRIS_ID, ItemStatus::Installed), item("izsIPI7a", ItemStatus::AlreadyInstalled)],
            files: Vec::new(),
            dependencies: 0,
            shader_pack: None,
        };
        assert_eq!(activate_shader(&paths, &inst, &report, "izsIPI7a").await.as_deref(), Some(file));
        let text = tokio::fs::read_to_string(&config).await.unwrap();
        assert_eq!(text, format!("maxShadowRenderDistance=16\nenableShaders=true\nshaderPack={file}\n"));

        // Iris fehlt: nichts einschalten.
        report.items[0].status = ItemStatus::NotAvailable;
        assert_eq!(activate_shader(&paths, &inst, &report, "izsIPI7a").await, None);
    }

    #[test]
    fn renderer_mods_hide_the_boost_hint() {
        assert!(!has_renderer(&Existing::default()));
        let sodium = Existing { projects: HashMap::from([("AANobbMI".into(), None)]), ..Default::default() };
        assert!(has_renderer(&sodium));
        for file in ["optifine_1.20.1_hd_u_i6.jar", "rubidium-0.7.1.jar", "embeddium-1.0.jar"] {
            assert!(has_renderer(&Existing { projects: HashMap::new(), mod_files: vec![file.into()], ..Default::default() }), "{file}");
        }
        assert!(!has_renderer(&Existing { projects: HashMap::new(), mod_files: vec!["lithium.jar".into()], ..Default::default() }));
    }

    #[tokio::test]
    async fn boost_hint_only_for_modded_non_pack_instances() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        let mut inst = Instance {
            id: "hint".into(),
            name: "H".into(),
            game_version: "1.21.1".into(),
            loader: crate::instance::Loader { kind: LoaderKind::Fabric, version: None },
            created_at: chrono::Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            icon: None,
            group: None,
            overrides: Default::default(),
        };
        tokio::fs::create_dir_all(paths.instance_game_dir(&inst.id)).await.unwrap();
        tokio::fs::write(paths.instance_file(&inst.id), b"{}").await.unwrap();
        assert!(suggest_fps_boost(&paths, &inst).await.unwrap());
        inst.loader = crate::instance::Loader::vanilla();
        assert!(!suggest_fps_boost(&paths, &inst).await.unwrap());
        inst.loader = crate::instance::Loader { kind: LoaderKind::Forge, version: None };
        assert!(suggest_fps_boost(&paths, &inst).await.unwrap());
        // Aus einem Modpack: kein Hinweis.
        crate::history::record(
            &paths,
            &inst.id,
            crate::history::HistoryEntry::new(crate::history::HistoryKind::Created).detail("modpack"),
        )
        .await;
        assert!(!suggest_fps_boost(&paths, &inst).await.unwrap());
    }

    #[test]
    fn normalize_keeps_builtins_once_and_drops_garbage() {
        let own = |id: &str, name: &str| StoredPreset::new(id, name, false, Vec::new());
        let good = "0123456789abcdef0123456789abcdef";
        let list = normalize(vec![
            own(good, "Meine Basics"),
            own("trs-replay", "egal"),
            own(good, "doppelt"),
            own("../böse", "x"),
            own("fedcba9876543210fedcba9876543210", "   "),
        ]);
        let ids: Vec<&str> = list.iter().map(|p| p.id.as_str()).collect();
        // Fehlende fertige Presets landen hinter den vorhandenen fertigen, die Stufen beisammen.
        assert_eq!(
            ids,
            [good, "trs-replay", "trs-fps-boost", "trs-fps-shader-lite", "trs-fps-shader", "trs-nvidium", "trs-voice-chat"]
        );
        assert!(list[1].name.is_empty());
        assert!(list.iter().find(|p| p.id == "trs-fps-boost").unwrap().auto);
        assert!(!list.iter().find(|p| p.id == "trs-fps-shader").unwrap().auto);

        let fresh: Vec<String> = normalize(Vec::new()).into_iter().map(|p| p.id).collect();
        assert_eq!(fresh, Builtin::ALL.map(|b| b.id().to_owned()));

        // Bestehende Liste aus 0.5.0: neue Stufen direkt hinter „FPS-Boost“.
        let old = normalize(["trs-voice-chat", "trs-fps-boost", "trs-nvidium", "trs-replay"].map(|id| own(id, "")).to_vec());
        let ids: Vec<&str> = old.iter().map(|p| p.id.as_str()).collect();
        assert_eq!(ids, ["trs-voice-chat", "trs-fps-boost", "trs-fps-shader-lite", "trs-fps-shader", "trs-nvidium", "trs-replay"]);

        // Zwei Stufen automatisch (von Hand bearbeitet): nur die erste bleibt es.
        let auto = |id: &str| StoredPreset::new(id, "", true, Vec::new());
        let fixed = normalize(vec![auto("trs-fps-shader"), auto("trs-fps-boost")]);
        let on: Vec<&str> = fixed.iter().filter(|p| p.auto).map(|p| p.id.as_str()).collect();
        assert_eq!(on, ["trs-fps-shader"]);
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

    fn t(minutes: i64) -> Option<DateTime<Utc>> {
        Some(Utc::now() - chrono::Duration::days(1) + chrono::Duration::minutes(minutes))
    }

    fn sp(id: &str, name: &str, at: Option<DateTime<Utc>>) -> SyncPreset {
        SyncPreset { id: id.into(), name: name.into(), auto: false, items: vec![validate_item(item("abc")).unwrap()], updated_at: at }
    }

    fn data(presets: Vec<SyncPreset>, deleted: Vec<(&str, i64)>, order: &[&str], layout_at: Option<DateTime<Utc>>) -> SyncData {
        let mut presets = presets;
        presets.sort_by(|a, b| a.id.cmp(&b.id));
        SyncData {
            version: SYNC_VERSION,
            presets,
            deleted: deleted.into_iter().map(|(id, m)| PresetTombstone { id: id.into(), deleted_at: t(m).unwrap() }).collect(),
            layout: SyncLayout { order: order.iter().map(|s| (*s).to_owned()).collect(), auto: BTreeMap::new(), updated_at: layout_at },
        }
    }

    const P1: &str = "11111111111111111111111111111111";
    const P2: &str = "22222222222222222222222222222222";
    const P3: &str = "33333333333333333333333333333333";

    #[test]
    fn sync_merge_is_a_union_with_last_writer_wins() {
        // Erster Abgleich: Vereinigung, nichts geht verloren.
        let local = data(vec![sp(P1, "Hier", t(1))], vec![], &[P1], None);
        let remote = data(vec![sp(P2, "Dort", t(1))], vec![], &[P2], None);
        let merged = merge(&local, &remote);
        assert_eq!(merged.presets.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(), [P1, P2]);
        // Gleich alte Reihenfolge → die vom Konto, Lokales hinten dran.
        assert_eq!(merged.layout.order, [P2, P1]);

        // Gleiche ID: die jüngere Änderung gewinnt – in beide Richtungen.
        let local = data(vec![sp(P1, "Neu hier", t(5)), sp(P2, "Alt hier", t(1))], vec![], &[P1, P2], t(9));
        let remote = data(vec![sp(P1, "Alt dort", t(2)), sp(P2, "Neu dort", t(3))], vec![], &[P2, P1], t(4));
        let merged = merge(&local, &remote);
        let names: Vec<&str> = merged.presets.iter().map(|p| p.name.as_str()).collect();
        assert_eq!(names, ["Neu hier", "Neu dort"]);
        assert_eq!(merged.layout.order, [P1, P2], "jüngere Reihenfolge gewinnt");
    }

    #[test]
    fn sync_merge_respects_tombstones_on_both_sides() {
        // Hier gelöscht nach der letzten Änderung dort → bleibt gelöscht, Grabstein bleibt.
        let local = data(vec![], vec![(P1, 5)], &[], None);
        let remote = data(vec![sp(P1, "Dort", t(2)), sp(P2, "Dort", t(2))], vec![(P3, 4)], &[P1, P2], None);
        let merged = merge(&local, &remote);
        assert_eq!(merged.presets.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(), [P2]);
        assert_eq!(merged.deleted.iter().map(|d| d.id.as_str()).collect::<Vec<_>>(), [P1, P3]);
        assert!(!merged.layout.order.contains(&P1.to_owned()));

        // Dort gelöscht → hier weg; hier danach noch geändert → bleibt, Grabstein fällt.
        let local = data(vec![sp(P3, "Alt", t(1)), sp(P2, "Neuer", t(8))], vec![], &[P3, P2], None);
        let remote = data(vec![], vec![(P3, 4), (P2, 6)], &[], None);
        let merged = merge(&local, &remote);
        assert_eq!(merged.presets.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(), [P2]);
        assert_eq!(merged.deleted.iter().map(|d| d.id.as_str()).collect::<Vec<_>>(), [P3]);

        // Presets ohne Änderungszeit (aus älteren Versionen) verlieren gegen jeden Grabstein.
        let local = data(vec![sp(P1, "Alt", None)], vec![], &[P1], None);
        let remote = data(vec![], vec![(P1, 0)], &[], None);
        assert!(merge(&local, &remote).presets.is_empty());
    }

    #[test]
    fn sync_data_from_the_account_is_checked() {
        let value = serde_json::json!({
            "version": 1,
            "presets": [
                { "id": P1, "name": " Basics ", "auto": true, "updatedAt": "2026-09-25T10:00:00.000Z",
                  "items": [ { "source": "modrinth", "projectId": "AANobbMI", "title": "Sodium", "kind": "mod" },
                             { "source": "curseforge", "projectId": "1", "title": "x", "kind": "mod" },
                             { "source": "modrinth", "projectId": "../böse", "title": "x", "kind": "mod" } ] },
                { "id": "../x", "name": "kaputt" },
                { "id": P2, "name": "" },
                "nicht mal ein Objekt"
            ],
            "deleted": [ { "id": P3, "deletedAt": Utc::now().to_rfc3339() }, { "id": P3 } ],
            "layout": { "order": ["trs-replay", "../x", P1, "trs-replay"], "auto": { "trs-voice-chat": true, "böse": true },
                        "updatedAt": "kaputt" }
        });
        let parsed = SyncData::from_value(&value);
        assert_eq!(parsed.presets.len(), 1);
        assert_eq!(parsed.presets[0].name, "Basics");
        assert_eq!(parsed.presets[0].items.len(), 1);
        assert!(parsed.presets[0].updated_at.is_some());
        assert_eq!(parsed.deleted.len(), 1);
        assert_eq!(parsed.layout.order, ["trs-replay", P1]);
        assert_eq!(parsed.layout.auto.keys().collect::<Vec<_>>(), ["trs-voice-chat"]);
        assert_eq!(parsed.layout.updated_at, None);
        // Unsinn ergibt einfach nichts.
        assert!(SyncData::from_value(&serde_json::json!("x")).presets.is_empty());
        // Ohne Symbole kleiner, sonst gleich.
        let slim = parsed.without_icons();
        assert_eq!(slim.presets[0].items[0].project_id, "AANobbMI");
    }

    #[tokio::test]
    async fn sync_merge_updates_the_store() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        let own = create(&paths, PresetInput { name: "Hier".into(), auto: false, items: vec![item("abc")] }).await.unwrap();
        let local = sync_local(&paths).await;
        assert_eq!(local.presets.len(), 1);
        assert!(local.presets[0].updated_at.is_some());

        // Vom Konto: ein neues Preset, „Voice Chat“ immer automatisch (neuer eingestellt).
        let mut remote = data(vec![sp(P2, "Dort", t(1))], vec![], &[P2, "trs-voice-chat"], Some(Utc::now()));
        remote.layout.auto.insert("trs-voice-chat".into(), true);
        let (after, changed) = sync_merge(&paths, &remote).await.unwrap();
        assert!(changed);
        let listed = list(&paths).await.unwrap();
        assert_eq!(listed[0].id, P2, "Reihenfolge vom Konto");
        assert!(listed.iter().any(|p| p.id == own.id), "eigenes Preset bleibt");
        assert!(listed.iter().find(|p| p.id == "trs-voice-chat").unwrap().auto);
        // Nochmal derselbe Stand → keine Änderung mehr.
        let (again, changed) = sync_merge(&paths, &after).await.unwrap();
        assert!(!changed);
        assert_eq!(again, after);

        // Löschen hinterlässt einen Grabstein, der mit abgeglichen wird.
        delete(&paths, &own.id).await.unwrap();
        let local = sync_local(&paths).await;
        assert_eq!(local.deleted.iter().map(|d| d.id.as_str()).collect::<Vec<_>>(), [own.id.as_str()]);
        // Reihenfolge ändern zählt als Änderung der Anordnung.
        let ids: Vec<String> = list(&paths).await.unwrap().into_iter().rev().map(|p| p.id).collect();
        reorder(&paths, &ids).await.unwrap();
        assert!(sync_local(&paths).await.layout.updated_at > after.layout.updated_at);
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
        // FPS-Stufen: eine automatisch schaltet die anderen ab.
        assert!(set_auto(&paths, "trs-fps-shader-lite", true).await.unwrap().auto);
        let auto_tiers: Vec<String> =
            list(&paths).await.unwrap().into_iter().filter(|p| p.auto && p.builtin.is_some_and(Builtin::is_fps_tier)).map(|p| p.id).collect();
        assert_eq!(auto_tiers, ["trs-fps-shader-lite"]);
        assert!(set_auto(&paths, "gibt-es-nicht", true).await.is_err());

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
