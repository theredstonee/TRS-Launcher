//! Import aus anderen Launchern. Erkannte Installationen werden als neue
//! TRS-Instanz angelegt; Welten, Mods, Einstellungen, Ressourcen-/Shaderpakete
//! und die Serverliste werden kopiert. Fremde Daten werden nur gelesen, nie
//! verändert; Zugangsdaten anderer Launcher werden nie gelesen oder kopiert.
//!
//! Formate (jeweils an echten Dateien auf dem Entwicklungs-PC bzw. am offenen
//! Quellcode des Launchers geprüft):
//! - offizieller Launcher: `launcher_profiles.json` + `versions/<id>/<id>.json`
//!   (Standardformat von Mojang, auch von TLauncher genutzt),
//! - Prism/MultiMC: `instances/<x>/mmc-pack.json` + `instance.cfg`,
//! - CurseForge-App: `Instances/<x>/minecraftinstance.json` (`installedAddons`
//!   mit Projekt-/Datei-IDs → Herkunft bleibt für Updates erhalten),
//! - Modrinth App: `app.db` (SQLite, nur lesend),
//! - Lunar Client: `~/.lunarclient/db/profiles.db` (SQLite; enthält auch die
//!   Badlion-Profile des Lunar-Launchers), siehe [`clients`],
//! - Badlion Client, Feather, TLauncher: siehe [`clients`],
//! - OneClient/OneLauncher, ATLauncher, GDLauncher (alt + Carbon): siehe [`launchers`].

mod clients;
mod copy;
mod launchers;
#[cfg(test)]
mod tests;

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::content::{self, ContentKind, Platform, Source};
use crate::instance::{Instance, Loader, LoaderKind, NewInstance};
use crate::{Error, Launcher, Result};

pub use copy::ImportProgress;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ImportSource {
    Vanilla,
    Prism,
    MultiMc,
    CurseForge,
    Modrinth,
    /// Von Hand gewählter Ordner (anderer Client, Backup, …).
    Folder,
    Lunar,
    Badlion,
    Feather,
    OneClient,
    AtLauncher,
    GdLauncher,
    GdLauncherCarbon,
    TLauncher,
}

impl ImportSource {
    /// Liest dieser Launcher eine Liste, die wir verstehen? (Feather nicht.)
    pub fn supported(self) -> bool {
        self != Self::Feather
    }
}

/// Hinweise, die das Frontend in der Vorschau erklärt.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum ImportNote {
    /// Mods des Clients selbst (Lunar, Badlion) sind fest eingebaut und
    /// bleiben zurück – nur eigene Inhalte kommen mit.
    ClientModsSkipped,
    /// Der Spielordner wird mit anderen Profilen geteilt: Welten, Pakete und
    /// Einstellungen aller dieser Profile kommen mit.
    SharedGameDir,
    /// Den Modloader gibt es in TRS nicht (Legacy Fabric, Ornithe) → Fabric.
    LoaderMapped,
    /// Einige Dateien fehlen im Ordner und werden von CurseForge nachgeladen.
    CurseForgeDownloads,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportCandidate {
    /// Stabil über mehrere Scans – das Frontend importiert nur per ID, nie per Pfad.
    pub id: String,
    pub source: ImportSource,
    pub name: String,
    pub game_version: String,
    pub loader: Loader,
    pub mod_count: u32,
    pub world_count: u32,
    pub resource_pack_count: u32,
    pub shader_pack_count: u32,
    /// `options.txt` (Einstellungen, Tastenbelegung) ist vorhanden.
    pub has_options: bool,
    /// `servers.dat` (Serverliste) ist vorhanden.
    pub has_servers: bool,
    /// Inhalte mit bekannter Herkunft (CurseForge/Modrinth) – bleiben aktualisierbar.
    pub tracked_count: u32,
    /// Fehlende Dateien, die beim Import von CurseForge geladen werden.
    pub missing_count: u32,
    pub notes: Vec<ImportNote>,
    /// Version/Loader nur geschätzt – der Nutzer soll sie vor dem Import prüfen.
    pub version_guessed: bool,
    #[serde(skip)]
    game_dir: PathBuf,
    #[serde(skip)]
    extras: CopyExtras,
}

/// Datei mit bekannter Herkunft, die nach dem Kopieren im Index landet.
#[derive(Debug, Clone)]
pub(crate) struct TrackedFile {
    pub kind: ContentKind,
    pub file_name: String,
    pub source: Source,
}

/// Datei, die laut Launcher installiert ist, aber im Ordner fehlt.
#[derive(Debug, Clone)]
pub(crate) struct MissingFile {
    pub kind: ContentKind,
    pub project_id: String,
    pub file_id: String,
}

/// Was beim Kopieren über den Spielordner hinaus zu beachten ist.
#[derive(Debug, Clone, Default)]
pub(crate) struct CopyExtras {
    /// Mods liegen woanders (Lunar, OneClient) – nur `.jar`-Dateien, flach.
    pub mods_dir: Option<PathBuf>,
    /// `mods` im Spielordner gehört nicht zu diesem Profil (geteiltes `.minecraft`).
    pub skip_game_mods: bool,
    /// Ordner, in die Symlinks zeigen dürfen (außer dem Quellordner selbst).
    pub roots: Vec<PathBuf>,
    /// Zusätzliche Launcher-Dateien auf oberster Ebene, die nicht mitkommen.
    pub excludes: &'static [&'static str],
    /// Ordner mit deaktivierten Mods (ATLauncher `disabledmods`) → `mods/*.disabled`.
    pub disabled_mods_dir: Option<&'static str>,
    pub tracked: Vec<TrackedFile>,
    pub missing: Vec<MissingFile>,
}

/// Ein Launcher, dessen Daten auf diesem PC liegen.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DetectedLauncher {
    pub source: ImportSource,
    /// Gefundene, importierbare Installationen.
    pub instances: u32,
    /// `false` = Launcher erkannt, speichert aber keine lesbare Liste.
    pub supported: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportOverview {
    pub candidates: Vec<ImportCandidate>,
    pub launchers: Vec<DetectedLauncher>,
}

/// Ergebnis eines Imports.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportResult {
    pub instance: Instance,
    /// Inhalte, deren Herkunft übernommen wurde (Updates möglich).
    pub tracked: u32,
    /// Von CurseForge nachgeladene Dateien.
    pub downloaded: u32,
    /// Dateien, die der Autor nur über CurseForge selbst verteilt (von Hand laden).
    pub blocked: u32,
    /// Nachladen fehlgeschlagen.
    pub failed: u32,
}

pub type ImportProgressFn = dyn Fn(ImportProgress) + Send + Sync;

/// Was nie mitkopiert wird: Launcher-Interna, Caches, Zugangsdaten und
/// Daten anderer Clients. Vergleich ohne Groß-/Kleinschreibung.
const EXCLUDE_TOP: &[&str] = &[
    "versions",
    "libraries",
    "assets",
    "logs",
    "crash-reports",
    "bin",
    "debug",
    "downloads",
    "staging",
    "webcache",
    "webcache2",
    "natives",
    "quickplay",
    "server-resource-packs",
    ".mixin.out",
    ".fabric",
    ".cache",
    ".curseclient",
    ".dedicated_directory",
    "minecraftinstance.json",
    "usercache.json",
    "usernamecache.json",
    "clientid_v2.txt",
    "command_history.txt",
    "output-client.log",
    "output-client.log.lck",
];

/// Präfixe für ganze Datei-/Ordnerfamilien (Launcher-Profile, Badlion, …).
const EXCLUDE_PREFIXES: &[&str] =
    &["launcher_", "blclient", "badlion", "treatment_tags", "feather", ".oneclient", ".gdl_", "tlauncher"];

fn is_excluded(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    EXCLUDE_TOP.contains(&lower.as_str()) || EXCLUDE_PREFIXES.iter().any(|p| lower.starts_with(p))
}

/// Dateien mit Zugangsdaten – egal in welcher Tiefe, egal von welchem Launcher
/// oder Mod (Essential, Badlion, TLauncher, …). Sie werden nie gelesen.
pub(crate) fn is_secret_file(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    const EXACT: &[&str] = &[
        "accounts.json",
        "accounts.dat",
        "microsoft_accounts.json",
        "login_cache.dat",
        "tlauncherprofiles.json",
        "auth.json",
        "launcher_accounts.json",
    ];
    let secretish_ext = [".json", ".dat", ".bin", ".txt", ".enc"].iter().any(|e| lower.ends_with(e));
    EXACT.contains(&lower.as_str())
        || lower.contains("credential")
        || lower.contains("_accounts")
        || lower.contains("accounts_")
        || (secretish_ext && (lower.contains("token") || lower.contains("msa_")))
}

fn candidate_id(source: ImportSource, dir: &Path, name: &str) -> String {
    // FNV-1a genügt – es geht nur um eine stabile, kurze Kennung.
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    let key = format!("{source:?}|{}|{name}", dir.display());
    for byte in key.bytes() {
        hash ^= u64::from(byte);
        hash = hash.wrapping_mul(0x0100_0000_01b3);
    }
    format!("{hash:016x}")
}

fn count_entries(dir: &Path, predicate: impl Fn(&std::fs::DirEntry) -> bool) -> u32 {
    std::fs::read_dir(dir).map(|it| it.flatten().filter(|e| predicate(e)).count() as u32).unwrap_or(0)
}

fn lower_name(e: &std::fs::DirEntry) -> String {
    e.file_name().to_string_lossy().to_ascii_lowercase()
}

fn count_jars(dir: &Path) -> u32 {
    count_entries(dir, |e| {
        let n = lower_name(e);
        n.ends_with(".jar") || n.ends_with(".jar.disabled")
    })
}

/// Pakete: Zip-Dateien oder entpackte Ordner.
fn count_packs(dir: &Path) -> u32 {
    count_entries(dir, |e| lower_name(e).ends_with(".zip") || e.path().is_dir())
}

/// Ein einzelner Ordner-/Dateiname aus fremden Daten – nichts, was aus dem Ordner führt.
pub(crate) fn safe_file_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 200
        && name != "."
        && name != ".."
        && !name.contains(['/', '\\', ':', '\0'])
        && !name.chars().any(char::is_control)
}

/// Absoluter, existierender Ordner aus fremden Daten (ohne Steuerzeichen).
pub(crate) fn existing_abs_dir(raw: &str) -> Option<PathBuf> {
    let raw = raw.trim().trim_matches('"');
    if raw.is_empty() || raw.len() > 1024 || raw.chars().any(char::is_control) {
        return None;
    }
    let path = PathBuf::from(raw);
    (path.is_absolute() && path.is_dir()).then_some(path)
}

/// Liest eine kleine Datei (Konfiguration) – größere werden ignoriert.
pub(crate) fn read_small(path: &Path, max: u64) -> Option<Vec<u8>> {
    let meta = std::fs::metadata(path).ok()?;
    if !meta.is_file() || meta.len() > max {
        return None;
    }
    std::fs::read(path).ok()
}

pub(crate) fn read_json(path: &Path, max: u64) -> Option<serde_json::Value> {
    serde_json::from_slice(&read_small(path, max)?).ok()
}

/// Öffnet eine KOPIE einer fremden SQLite-Datenbank (samt `-wal`) in einem
/// temporären Ordner. So wird die Datei des anderen Launchers nie geöffnet,
/// gesperrt oder verändert – auch nicht deren `-shm`.
pub(crate) fn with_db_copy<T>(db: &Path, read: impl FnOnce(&rusqlite::Connection) -> Option<T>) -> Option<T> {
    const MAX_DB: u64 = 64 * 1024 * 1024;
    let small_file = |p: &Path| std::fs::metadata(p).is_ok_and(|m| m.is_file() && m.len() <= MAX_DB);
    if !small_file(db) {
        return None;
    }
    /// Eigener Temp-Ordner, der beim Verlassen wieder gelöscht wird.
    struct Scratch(PathBuf);
    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    let tmp = Scratch(std::env::temp_dir().join(format!("trs-import-{}", uuid::Uuid::new_v4().simple())));
    std::fs::create_dir(&tmp.0).ok()?;
    let copy = tmp.0.join("copy.db");
    std::fs::copy(db, &copy).ok()?;
    let wal = PathBuf::from(format!("{}-wal", db.display()));
    if small_file(&wal) {
        std::fs::copy(&wal, tmp.0.join("copy.db-wal")).ok()?;
    }
    let conn = rusqlite::Connection::open(&copy).ok()?;
    let _ = conn.execute_batch("PRAGMA query_only = ON;");
    let result = read(&conn);
    drop(conn);
    result
}

/// Sieht ein Ordner nach einem Minecraft-Spielordner aus?
fn looks_like_game_dir(dir: &Path) -> bool {
    ["mods", "saves", "config", "options.txt", "resourcepacks"].iter().any(|n| dir.join(n).exists())
}

fn candidate(source: ImportSource, name: &str, game_version: &str, loader: Loader, game_dir: PathBuf) -> Option<ImportCandidate> {
    if !crate::meta::is_safe_id(game_version) || loader.validate().is_err() || !game_dir.is_dir() {
        return None;
    }
    let name: String = name.trim().chars().filter(|c| !c.is_control()).take(64).collect();
    let mut c = ImportCandidate {
        id: candidate_id(source, &game_dir, &name),
        source,
        name: if name.is_empty() { game_version.to_owned() } else { name },
        game_version: game_version.to_owned(),
        loader,
        mod_count: 0,
        world_count: 0,
        resource_pack_count: 0,
        shader_pack_count: 0,
        has_options: false,
        has_servers: false,
        tracked_count: 0,
        missing_count: 0,
        notes: Vec::new(),
        version_guessed: false,
        game_dir,
        extras: CopyExtras::default(),
    };
    c.refresh();
    Some(c)
}

impl ImportCandidate {
    /// Zählt neu, was mitkommt (nach Änderungen an `extras`).
    fn refresh(&mut self) {
        let game = &self.game_dir;
        let game_mods = if self.extras.skip_game_mods {
            0
        } else {
            count_jars(&game.join("mods")) + self.extras.disabled_mods_dir.map_or(0, |d| count_jars(&game.join(d)))
        };
        self.mod_count = self.extras.mods_dir.as_deref().map_or(game_mods, count_jars);
        self.world_count = count_entries(&game.join("saves"), |e| e.path().join("level.dat").is_file());
        self.resource_pack_count = count_packs(&game.join("resourcepacks"));
        self.shader_pack_count = count_packs(&game.join("shaderpacks"));
        self.has_options = game.join("options.txt").is_file();
        self.has_servers = game.join("servers.dat").is_file();
        self.tracked_count = self.extras.tracked.len() as u32;
        self.missing_count = self.extras.missing.len() as u32;
        if self.missing_count > 0 {
            self.add_note(ImportNote::CurseForgeDownloads);
        }
    }

    fn add_note(&mut self, note: ImportNote) {
        if !self.notes.contains(&note) {
            self.notes.push(note);
        }
    }

    fn note(mut self, note: ImportNote) -> Self {
        self.add_note(note);
        self
    }

    fn guessed(mut self) -> Self {
        self.version_guessed = true;
        self
    }

    fn with_extras(mut self, change: impl FnOnce(&mut CopyExtras)) -> Self {
        change(&mut self.extras);
        self.refresh();
        self
    }
}

// --- Offizieller Launcher ----------------------------------------------------

#[derive(Deserialize)]
struct LauncherProfiles {
    #[serde(default)]
    profiles: HashMap<String, LauncherProfile>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LauncherProfile {
    #[serde(rename = "type")]
    kind: Option<String>,
    name: Option<String>,
    last_version_id: Option<String>,
    game_dir: Option<String>,
}

/// Leitet aus einer Launcher-Versions-ID Spielversion und Modloader ab, z. B.
/// `fabric-loader-0.16.10-1.21.1`, `1.20.1-forge-47.3.0`, `neoforge-21.1.77`.
pub fn parse_version_id(id: &str) -> Option<(String, Loader)> {
    let modded = |kind, version: &str| Loader { kind, version: Some(version.to_owned()) };
    if let Some(rest) = id.strip_prefix("fabric-loader-") {
        let (loader, game) = rest.split_once('-')?;
        return Some((game.to_owned(), modded(LoaderKind::Fabric, loader)));
    }
    if let Some(rest) = id.strip_prefix("quilt-loader-") {
        let (loader, game) = rest.split_once('-')?;
        return Some((game.to_owned(), modded(LoaderKind::Quilt, loader)));
    }
    if let Some((game, forge)) = id.split_once("-forge-") {
        return Some((game.to_owned(), modded(LoaderKind::Forge, forge)));
    }
    if let Some(neo) = id.strip_prefix("neoforge-") {
        // 21.1.77 → 1.21.1, 20.4.x → 1.20.4, 21.0.x → 1.21
        let mut parts = neo.split('.');
        let (major, minor) = (parts.next()?, parts.next()?);
        let game = if minor == "0" { format!("1.{major}") } else { format!("1.{major}.{minor}") };
        return Some((game, modded(LoaderKind::NeoForge, neo)));
    }
    Some((id.to_owned(), Loader::vanilla()))
}

/// Versions-ID eines Profils → Spielversion + Loader. Eigene Namen (z. B.
/// „Fabric 1.20.1“ von TLauncher) werden über die Versions-JSON aufgelöst.
fn resolve_version(minecraft: &Path, id: &str) -> Option<(String, Loader)> {
    let parsed = parse_version_id(id)?;
    if parsed.1.kind == LoaderKind::Vanilla
        && let Some(found) = clients::inspect_version_json(minecraft, id)
    {
        return Some(found);
    }
    Some(parsed)
}

fn scan_vanilla(minecraft: &Path, latest_release: Option<&str>) -> Vec<ImportCandidate> {
    let profiles: LauncherProfiles = read_small(&minecraft.join("launcher_profiles.json"), 16 * 1024 * 1024)
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or(LauncherProfiles { profiles: HashMap::new() });

    let mut out = Vec::new();
    for profile in profiles.profiles.values() {
        let version = match (profile.kind.as_deref(), profile.last_version_id.as_deref()) {
            (Some("latest-snapshot"), _) => continue,
            (Some("latest-release"), _) | (_, Some("latest-release")) => latest_release,
            (_, Some(v)) => Some(v),
            _ => None,
        };
        let Some((game_version, loader)) = version.and_then(|v| resolve_version(minecraft, v)) else { continue };
        let game_dir = profile
            .game_dir
            .as_deref()
            .filter(|d| !d.trim().is_empty())
            .map_or_else(|| minecraft.to_owned(), PathBuf::from);
        let name = match profile.name.as_deref().filter(|n| !n.trim().is_empty()) {
            Some(n) => n.to_owned(),
            None => format!("Minecraft {game_version}"),
        };
        out.extend(candidate(ImportSource::Vanilla, &name, &game_version, loader, game_dir));
    }
    out
}

// --- Prism / MultiMC -------------------------------------------------------

#[derive(Deserialize)]
struct MmcPack {
    #[serde(default)]
    components: Vec<MmcComponent>,
}

#[derive(Deserialize)]
struct MmcComponent {
    uid: String,
    version: Option<String>,
}

fn parse_mmc_pack(pack: &MmcPack) -> Option<(String, Loader)> {
    let version_of = |uid: &str| pack.components.iter().find(|c| c.uid == uid).and_then(|c| c.version.clone());
    let game = version_of("net.minecraft")?;
    let loader = [
        ("net.fabricmc.fabric-loader", LoaderKind::Fabric),
        ("org.quiltmc.quilt-loader", LoaderKind::Quilt),
        ("net.neoforged", LoaderKind::NeoForge),
        ("net.minecraftforge", LoaderKind::Forge),
    ]
    .into_iter()
    .find_map(|(uid, kind)| version_of(uid).map(|v| Loader { kind, version: Some(v) }))
    .unwrap_or_else(Loader::vanilla);
    Some((game, loader))
}

fn scan_mmc(instances: &Path, source: ImportSource) -> Vec<ImportCandidate> {
    let Ok(entries) = std::fs::read_dir(instances) else { return Vec::new() };
    entries.flatten().filter_map(|entry| mmc_instance(&entry.path(), source)).collect()
}

fn mmc_instance(dir: &Path, source: ImportSource) -> Option<ImportCandidate> {
    let pack: MmcPack = read_small(&dir.join("mmc-pack.json"), 4 * 1024 * 1024).and_then(|b| serde_json::from_slice(&b).ok())?;
    let (game_version, loader) = parse_mmc_pack(&pack)?;
    let cfg = read_small(&dir.join("instance.cfg"), 1024 * 1024).map(|b| String::from_utf8_lossy(&b).into_owned()).unwrap_or_default();
    let fallback = dir.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let name = cfg.lines().find_map(|l| l.strip_prefix("name=")).map_or(fallback, str::to_owned);
    let game_dir = [".minecraft", "minecraft"].iter().map(|d| dir.join(d)).find(|d| d.is_dir())?;
    candidate(source, &name, &game_version, loader, game_dir)
}

// --- CurseForge ------------------------------------------------------------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CurseInstance {
    name: String,
    game_version: String,
    base_mod_loader: Option<CurseLoader>,
    #[serde(default)]
    installed_addons: Vec<CurseAddon>,
}

#[derive(Deserialize)]
struct CurseLoader {
    name: String,
}

/// Eintrag aus `installedAddons` (Feldnamen wie in einer echten Datei der CurseForge-App).
#[derive(Deserialize)]
struct CurseAddon {
    #[serde(rename = "addonID")]
    addon_id: Option<u64>,
    #[serde(rename = "categoryClassID")]
    category_class_id: Option<u32>,
    #[serde(rename = "installedFile")]
    installed_file: Option<CurseAddonFile>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CurseAddonFile {
    id: Option<u64>,
    file_name: Option<String>,
    file_name_on_disk: Option<String>,
}

/// `forge-47.3.33`, `neoforge-21.1.77`, `fabric-0.16.10-1.21.1`, `quilt-0.26.0-1.20.1`
pub(crate) fn parse_curse_loader(name: &str, game_version: &str) -> Loader {
    let (kind, rest) = match name.split_once('-') {
        Some(("forge", rest)) => (LoaderKind::Forge, rest),
        Some(("neoforge", rest)) => (LoaderKind::NeoForge, rest),
        Some(("fabric", rest)) => (LoaderKind::Fabric, rest),
        Some(("quilt", rest)) => (LoaderKind::Quilt, rest),
        _ => return Loader::vanilla(),
    };
    let version = rest.strip_suffix(&format!("-{game_version}")).unwrap_or(rest);
    Loader { kind, version: Some(version.to_owned()) }
}

fn scan_curseforge(instances: &Path) -> Vec<ImportCandidate> {
    let Ok(entries) = std::fs::read_dir(instances) else { return Vec::new() };
    entries.flatten().filter_map(|entry| curse_instance(&entry.path())).collect()
}

/// Sucht eine Datei (auch deaktiviert) in `<spielordner>/<art>/`.
fn content_present(game_dir: &Path, kind: ContentKind, file_name: &str) -> bool {
    let dir = game_dir.join(kind.dir_name());
    dir.join(file_name).is_file() || dir.join(format!("{file_name}.disabled")).is_file()
}

/// Herkunft der installierten Inhalte: vorhandene Dateien behalten ihre
/// CurseForge-IDs, fehlende werden zum Nachladen vorgemerkt.
fn curse_tracking(addons: &[CurseAddon], game_dir: &Path, loader: &Loader) -> (Vec<TrackedFile>, Vec<MissingFile>) {
    let mut tracked = Vec::new();
    let mut missing = Vec::new();
    for addon in addons.iter().take(3000) {
        let (Some(project), Some(file)) = (addon.addon_id, addon.installed_file.as_ref()) else { continue };
        let Some(file_id) = file.id else { continue };
        let Some(kind) = addon.category_class_id.and_then(crate::curseforge::content_kind_of_class) else { continue };
        let Some(name) = file.file_name_on_disk.as_deref().or(file.file_name.as_deref()) else { continue };
        if content::validate_file_name(kind, name).is_err() {
            continue;
        }
        if content_present(game_dir, kind, name) {
            tracked.push(TrackedFile {
                kind,
                file_name: name.to_owned(),
                source: Source {
                    project_id: project.to_string(),
                    version_id: file_id.to_string(),
                    version_number: None,
                    platform: Platform::CurseForge,
                },
            });
        } else if kind != ContentKind::Mod || loader.kind != LoaderKind::Vanilla {
            missing.push(MissingFile { kind, project_id: project.to_string(), file_id: file_id.to_string() });
        }
    }
    (tracked, missing)
}

fn curse_instance(dir: &Path) -> Option<ImportCandidate> {
    let inst: CurseInstance =
        read_small(&dir.join("minecraftinstance.json"), 64 * 1024 * 1024).and_then(|b| serde_json::from_slice(&b).ok())?;
    let loader =
        inst.base_mod_loader.as_ref().map_or_else(Loader::vanilla, |l| parse_curse_loader(&l.name, &inst.game_version));
    let (tracked, missing) = curse_tracking(&inst.installed_addons, dir, &loader);
    // Bewusst der Ordner des Eintrags und nicht `installPath` aus der Datei:
    // kopierte CurseForge-Instanzen zeigen dort oft noch auf das Original.
    Some(candidate(ImportSource::CurseForge, &inst.name, &inst.game_version, loader, dir.to_owned())?.with_extras(|x| {
        x.tracked = tracked;
        x.missing = missing;
    }))
}

// --- Modrinth App -----------------------------------------------------------------

fn modrinth_loader(loader: Option<&str>, version: Option<String>) -> Loader {
    let kind = match loader.unwrap_or("vanilla") {
        "fabric" => LoaderKind::Fabric,
        "quilt" => LoaderKind::Quilt,
        "forge" => LoaderKind::Forge,
        "neoforge" => LoaderKind::NeoForge,
        _ => return Loader::vanilla(),
    };
    Loader { kind, version }
}

/// Die Modrinth App führt ihre Instanzen in `app.db` (SQLite); der
/// Profilordner kann in den App-Einstellungen verlegt sein (`custom_dir`).
/// Wir öffnen die Datenbank nur lesend.
fn scan_modrinth(app_dir: &Path) -> Vec<ImportCandidate> {
    let db_file = app_dir.join("app.db");
    let Ok(db) = rusqlite::Connection::open_with_flags(
        &db_file,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
    ) else {
        return Vec::new();
    };
    let base = db
        .query_row("SELECT custom_dir FROM settings", [], |r| r.get::<_, Option<String>>(0))
        .ok()
        .flatten()
        .map(PathBuf::from)
        .filter(|p| p.is_dir())
        .unwrap_or_else(|| app_dir.to_owned());
    let profiles = base.join("profiles");

    // Neues Schema (Instanzen + Content-Sets), sonst das alte `profiles`-Schema.
    let queries = [
        "SELECT i.path, i.name, c.game_version, c.loader, c.loader_version FROM instances i \
         JOIN instance_content_sets c ON c.id = i.applied_content_set_id",
        "SELECT path, name, game_version, mod_loader, mod_loader_version FROM profiles",
    ];
    for sql in queries {
        let Ok(mut stmt) = db.prepare(sql) else { continue };
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, Option<String>>(3)?,
                r.get::<_, Option<String>>(4)?,
            ))
        });
        let Ok(rows) = rows else { continue };
        return rows
            .flatten()
            .filter_map(|(path, name, game_version, loader, loader_version)| {
                // `path` ist ein einzelner Ordnername – nichts, was aus `profiles` herausführt.
                safe_file_name(&path).then_some(())?;
                let dir = profiles.join(&path);
                candidate(ImportSource::Modrinth, &name, &game_version, modrinth_loader(loader.as_deref(), loader_version), dir)
            })
            .collect();
    }
    Vec::new()
}

/// Erkennt, was in einem von Hand gewählten Ordner liegt.
pub fn scan_folder(dir: &Path, latest_release: Option<&str>) -> Vec<ImportCandidate> {
    if dir.join("launcher_profiles.json").is_file() {
        return scan_vanilla(dir, latest_release);
    }
    // Datenordner eines Launchers direkt gewählt?
    if dir.join("db").join("profiles.db").is_file() {
        return clients::scan_lunar(dir, None);
    }
    if dir.join("user_data.db").is_file() {
        return launchers::scan_oneclient(dir);
    }
    if let Some(c) = single_instance(dir) {
        return vec![c];
    }
    // Ein Ordner voller Instanzen (z. B. `PrismLauncher/instances` oder CurseForge `Instances`)
    // bzw. der Datenordner eines Launchers mit `instances` darin?
    let mut nested = nested_instances(dir);
    if nested.is_empty() {
        nested = nested_instances(&dir.join("instances"));
    }
    if !nested.is_empty() {
        return nested;
    }
    // Sonst: ein nackter Spielordner – Version unbekannt, Loader anhand der Mods geraten.
    let Some(version) = latest_release.filter(|_| looks_like_game_dir(dir)) else { return Vec::new() };
    let name = dir.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "Import".into());
    candidate(ImportSource::Folder, &name, version, guess_loader(&dir.join("mods")), dir.to_owned())
        .map(ImportCandidate::guessed)
        .into_iter()
        .collect()
}

/// Eine einzelne Instanz eines bekannten Launchers.
fn single_instance(dir: &Path) -> Option<ImportCandidate> {
    mmc_instance(dir, ImportSource::Prism)
        .or_else(|| curse_instance(dir))
        .or_else(|| launchers::atl_instance(dir))
        .or_else(|| launchers::gdl_instance(dir))
        .or_else(|| launchers::carbon_instance(dir))
}

fn nested_instances(dir: &Path) -> Vec<ImportCandidate> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    entries.flatten().take(500).filter_map(|e| single_instance(&e.path())).collect()
}

/// Schaut in bis zu 20 Mod-Jars, für welchen Loader sie gebaut sind.
fn guess_loader(mods: &Path) -> Loader {
    let Ok(entries) = std::fs::read_dir(mods) else { return Loader::vanilla() };
    for entry in entries.flatten().take(20) {
        if !lower_name(&entry).ends_with(".jar") {
            continue;
        }
        let Ok(file) = std::fs::File::open(entry.path()) else { continue };
        let Ok(mut zip) = zip::ZipArchive::new(file) else { continue };
        let has = |zip: &mut zip::ZipArchive<std::fs::File>, name: &str| zip.by_name(name).is_ok();
        let kind = if has(&mut zip, "quilt.mod.json") {
            LoaderKind::Quilt
        } else if has(&mut zip, "fabric.mod.json") {
            LoaderKind::Fabric
        } else if has(&mut zip, "META-INF/neoforge.mods.toml") {
            LoaderKind::NeoForge
        } else if has(&mut zip, "META-INF/mods.toml") || has(&mut zip, "mcmod.info") {
            LoaderKind::Forge
        } else {
            continue;
        };
        return Loader { kind, version: None };
    }
    Loader::vanilla()
}

/// Loader-Version ohne vorangestellte Spielversion (`1.20.1-47.3.0` → `47.3.0`).
fn strip_game_prefix(version: &str, game: &str) -> String {
    let v = version.strip_prefix(&format!("{game}-")).unwrap_or(version);
    v.strip_suffix(&format!("-{game}")).unwrap_or(v).to_owned()
}

/// Loader aus einem Namen wie `Fabric`, `NeoForge`, `LegacyFabric`, `quilt`.
/// Der zweite Wert sagt, ob der Loader auf Fabric umgelegt wurde.
fn loader_from_name(name: &str, version: Option<&str>, game: &str) -> Option<(Loader, bool)> {
    let version = version.map(|v| strip_game_prefix(v, game)).filter(|v| crate::instance::is_safe_version_string(v));
    let lower = name.to_ascii_lowercase();
    let (kind, mapped) = match lower.as_str() {
        "" | "vanilla" | "minecraft" | "none" => return Some((Loader::vanilla(), false)),
        "fabric" => (LoaderKind::Fabric, false),
        "quilt" => (LoaderKind::Quilt, false),
        "forge" => (LoaderKind::Forge, false),
        "neoforge" => (LoaderKind::NeoForge, false),
        "legacyfabric" | "legacy_fabric" | "ornithe" => (LoaderKind::Fabric, true),
        _ => return None,
    };
    // Legacy Fabric/Ornithe haben eigene Versionsnummern – die neueste Fabric-Version nehmen.
    Some((Loader { kind, version: if mapped { None } else { version } }, mapped))
}

// --- Importieren --------------------------------------------------------------------

impl Launcher {
    /// Sucht Installationen anderer Launcher auf diesem Rechner.
    pub async fn scan_imports(&self) -> Result<Vec<ImportCandidate>> {
        Ok(self.import_overview().await?.candidates)
    }

    /// Wie [`Self::scan_imports`], dazu die erkannten Launcher (auch ohne Installationen).
    pub async fn import_overview(&self) -> Result<ImportOverview> {
        let latest = self.version_manifest(false).await.ok().map(|m| m.latest.release);
        let roots = ImportRoots::detect();
        let custom = self.import_folders.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
        let (mut found, present) = tokio::task::spawn_blocking(move || {
            let mut all = roots.scan(latest.as_deref());
            for dir in &custom {
                all.extend(scan_folder(dir, latest.as_deref()));
            }
            // Derselbe Ordner kann über mehrere Wege gefunden werden.
            let mut seen = std::collections::HashSet::new();
            all.retain(|c| seen.insert(c.id.clone()));
            (all, roots.present())
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))?;

        // Nur Versionen anbieten, die es wirklich gibt (Snapshots aus alten Profilen etc.).
        if let Ok(manifest) = self.version_manifest(false).await {
            found.retain(|c| manifest.find(&c.game_version).is_some());
        }
        found.sort_by_key(|c| c.name.to_lowercase());
        let launchers = detected_launchers(&present, &found);
        Ok(ImportOverview { candidates: found, launchers })
    }

    /// Nimmt einen vom Nutzer gewählten Ordner in die Suche auf und liefert,
    /// was darin gefunden wurde.
    pub async fn add_import_folder(&self, dir: PathBuf) -> Result<Vec<ImportCandidate>> {
        if !dir.is_dir() {
            return Err(Error::validation(crate::msg!("import.folderMissing", "Dieser Ordner existiert nicht.")));
        }
        {
            let mut folders = self.import_folders.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if !folders.contains(&dir) {
                folders.push(dir.clone());
            }
        }
        let latest = self.version_manifest(false).await.ok().map(|m| m.latest.release);
        let found = tokio::task::spawn_blocking(move || scan_folder(&dir, latest.as_deref()))
            .await
            .map_err(|e| Error::Internal(e.to_string()))?;
        if found.is_empty() {
            return Err(Error::validation(crate::msg!(
                "import.noInstallation",
                "In diesem Ordner wurde keine Minecraft-Installation gefunden. Wähle den Ordner, in dem \
                 mods, saves oder options.txt liegen."
            )));
        }
        Ok(found)
    }

    /// `game_version`/`loader` dürfen nur bei geschätzten Kandidaten gesetzt werden.
    pub async fn import_instance(
        &self,
        candidate_id: &str,
        game_version: Option<String>,
        loader: Option<Loader>,
        on_progress: &ImportProgressFn,
    ) -> Result<ImportResult> {
        let mut candidate = self
            .scan_imports()
            .await?
            .into_iter()
            .find(|c| c.id == candidate_id)
            .ok_or_else(|| Error::validation(crate::msg!("import.installationGone", "Diese Installation wurde nicht mehr gefunden.")))?;
        if candidate.version_guessed {
            if let Some(v) = game_version {
                candidate.game_version = v;
            }
            if let Some(l) = loader {
                l.validate()?;
                candidate.loader = l;
            }
        }

        let source = serde_json::to_value(candidate.source).ok().and_then(|v| v.as_str().map(str::to_owned));
        let instance = self
            .create_instance_as(
                NewInstance {
                    name: candidate.name.clone(),
                    game_version: candidate.game_version.clone(),
                    loader: candidate.loader.clone(),
                },
                crate::history::HistoryEntry::new(crate::history::HistoryKind::Imported).subject(source.unwrap_or_default()),
            )
            .await?;

        let from = candidate.game_dir.clone();
        let to = self.paths().instance_game_dir(&instance.id);
        // Mods passen nur zum selben Modloader – bei Vanilla bleiben sie weg.
        let include_mods = candidate.loader.kind != LoaderKind::Vanilla;
        let extras = candidate.extras.clone();
        let result = tokio::task::block_in_place(|| copy::copy_candidate(&from, &to, &extras, include_mods, on_progress));
        if let Err(e) = result {
            let _ = self.instances().delete(&instance.id).await;
            return Err(e);
        }

        let tracked = self.remember_import_sources(&instance, &extras.tracked).await;
        let mut outcome = ImportResult { instance, tracked, downloaded: 0, blocked: 0, failed: 0 };
        if include_mods || extras.missing.iter().any(|m| m.kind != ContentKind::Mod) {
            self.fetch_missing(&mut outcome, &extras.missing).await;
        }
        Ok(outcome)
    }

    /// Schreibt die Herkunft kopierter Dateien in den Inhalts-Index der Instanz.
    async fn remember_import_sources(&self, instance: &Instance, tracked: &[TrackedFile]) -> u32 {
        let present: Vec<&TrackedFile> = tracked
            .iter()
            .filter(|t| content::existing_file(self.paths(), &instance.id, t.kind, &t.file_name).is_some())
            .collect();
        if present.is_empty() {
            return 0;
        }
        let count = present.len() as u32;
        let saved = content::modify_index(self.paths(), &instance.id, |index| {
            for t in present {
                let key = content::index_key(t.kind, &t.file_name);
                index.unknown.remove(&key);
                index.files.insert(key, t.source.clone());
            }
        })
        .await;
        match saved {
            Ok(()) => count,
            Err(e) => {
                tracing::warn!("Herkunft der importierten Inhalte nicht gespeichert: {e}");
                0
            }
        }
    }

    /// Lädt Dateien, die laut CurseForge-App installiert sind, aber fehlen.
    async fn fetch_missing(&self, outcome: &mut ImportResult, missing: &[MissingFile]) {
        const MAX_FETCH: usize = 500;
        if missing.is_empty() {
            return;
        }
        let Ok(cf) = self.curseforge() else {
            outcome.failed = missing.len() as u32;
            return;
        };
        for m in missing.iter().take(MAX_FETCH) {
            if m.kind == ContentKind::Mod && outcome.instance.loader.kind == LoaderKind::Vanilla {
                continue;
            }
            match cf.install(self.paths(), &outcome.instance, &m.project_id, m.kind, Some(&m.file_id)).await {
                Ok(o) => {
                    outcome.downloaded += o.files.len() as u32;
                    outcome.blocked += o.blocked.len() as u32;
                }
                Err(e) => {
                    tracing::warn!("CurseForge-Datei {}/{} nicht geladen: {e}", m.project_id, m.file_id);
                    outcome.failed += 1;
                }
            }
        }
        outcome.failed += missing.len().saturating_sub(MAX_FETCH) as u32;
    }
}

/// Erkannte Launcher mit der Zahl ihrer importierbaren Installationen.
fn detected_launchers(present: &[ImportSource], found: &[ImportCandidate]) -> Vec<DetectedLauncher> {
    let mut sources: Vec<ImportSource> = present.to_vec();
    for c in found {
        if c.source != ImportSource::Folder && !sources.contains(&c.source) {
            sources.push(c.source);
        }
    }
    sources
        .into_iter()
        .map(|source| DetectedLauncher {
            source,
            instances: found.iter().filter(|c| c.source == source).count() as u32,
            supported: source.supported(),
        })
        .collect()
}

/// Prism/MultiMC erlauben einen eigenen Instanz-Ordner (`InstanceDir=` in
/// `prismlauncher.cfg`/`multimc.cfg`, absolut oder relativ zum Datenordner).
fn mmc_instance_dir(data_dir: PathBuf, cfg_name: &str) -> PathBuf {
    let configured = read_small(&data_dir.join(cfg_name), 1024 * 1024).and_then(|bytes| {
        String::from_utf8_lossy(&bytes)
            .lines()
            .find_map(|l| l.trim().strip_prefix("InstanceDir="))
            .map(|v| v.trim().trim_matches('"').to_owned())
            .filter(|v| !v.is_empty() && !v.chars().any(char::is_control))
    });
    match configured {
        Some(dir) if Path::new(&dir).is_absolute() => PathBuf::from(dir),
        Some(dir) if !dir.split(['/', '\\']).any(|s| s == "..") => data_dir.join(dir),
        _ => data_dir.join("instances"),
    }
}

/// Datenordner anderer Launcher (Prism/MultiMC: der Datenordner selbst, noch
/// nicht der Instanz-Ordner). Je Quelle kann es mehrere Orte geben (Linux:
/// normale Installation und Flatpak). Genutzt vom Instanz- und vom Skin-Import.
#[derive(Default)]
pub(crate) struct LauncherHomes {
    pub minecraft: Vec<PathBuf>,
    pub prism: Vec<PathBuf>,
    pub multimc: Vec<PathBuf>,
    pub curseforge: Vec<PathBuf>,
    pub modrinth: Vec<PathBuf>,
    /// `~/.lunarclient`
    pub lunar: Vec<PathBuf>,
    /// `%APPDATA%\Badlion Client` (Spielordner ist `.minecraft`).
    pub badlion: Vec<PathBuf>,
    pub feather: Vec<PathBuf>,
    /// Datenordner von OneClient (aktuell und alter Ort).
    pub oneclient: Vec<PathBuf>,
    pub atlauncher: Vec<PathBuf>,
    /// GDLauncher (alt): `gdlauncher_next`.
    pub gdlauncher: Vec<PathBuf>,
    /// GDLauncher Carbon: `gdlauncher_carbon` (Laufzeitordner darunter).
    pub carbon: Vec<PathBuf>,
    /// `.tlauncher` (Spielordner ist `.minecraft`).
    pub tlauncher: Vec<PathBuf>,
}

impl LauncherHomes {
    /// Windows: `%APPDATA%`, `%LOCALAPPDATA%` bzw. das Benutzerprofil.
    #[cfg(windows)]
    pub fn candidates() -> Self {
        let appdata = std::env::var_os("APPDATA").map(PathBuf::from);
        let local = std::env::var_os("LOCALAPPDATA").map(PathBuf::from);
        let home = std::env::var_os("USERPROFILE").map(PathBuf::from);
        let under = |base: &Option<PathBuf>, rel: &[&str]| -> Vec<PathBuf> {
            base.iter().map(|b| rel.iter().fold(b.clone(), |p, seg| p.join(seg))).collect()
        };
        Self {
            minecraft: under(&appdata, &[".minecraft"]),
            prism: under(&appdata, &["PrismLauncher"]),
            multimc: under(&appdata, &["MultiMC"]),
            curseforge: under(&home, &["curseforge", "minecraft", "Instances"]),
            modrinth: ["ModrinthApp", "com.modrinth.theseus"].iter().flat_map(|d| under(&appdata, &[d])).collect(),
            lunar: under(&home, &[".lunarclient"]),
            badlion: under(&appdata, &["Badlion Client"]),
            feather: under(&appdata, &[".feather"]),
            oneclient: [under(&appdata, &["Polyfrost", "OneClient"]), under(&local, &["Polyfrost", "OneClient", "data"])].concat(),
            atlauncher: under(&appdata, &["ATLauncher"]),
            gdlauncher: under(&appdata, &["gdlauncher_next"]),
            carbon: under(&appdata, &["gdlauncher_carbon"]),
            tlauncher: under(&appdata, &[".tlauncher"]),
        }
    }

    /// Linux: XDG-Datenordner (`~/.local/share`), Flatpak-Ordner
    /// (`~/.var/app/<id>/…`) und die üblichen Orte von MultiMC.
    #[cfg(not(windows))]
    pub fn candidates() -> Self {
        let Some(home) = std::env::var_os("HOME").map(PathBuf::from) else { return Self::default() };
        let data = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .filter(|p| p.is_absolute())
            .unwrap_or_else(|| home.join(".local/share"));
        let config = std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .filter(|p| p.is_absolute())
            .unwrap_or_else(|| home.join(".config"));
        let flatpak = |id: &str, rel: &str| home.join(".var/app").join(id).join(rel);
        Self {
            minecraft: vec![
                home.join(".minecraft"),
                flatpak("com.mojang.Minecraft", ".minecraft"),
                flatpak("com.mojang.Minecraft", "data/minecraft"),
            ],
            prism: vec![data.join("PrismLauncher"), flatpak("org.prismlauncher.PrismLauncher", "data/PrismLauncher")],
            multimc: vec![data.join("multimc"), home.join("MultiMC"), home.join(".local/share/multimc")],
            curseforge: vec![home.join("curseforge/minecraft/Instances")],
            modrinth: vec![
                data.join("ModrinthApp"),
                data.join("com.modrinth.theseus"),
                flatpak("com.modrinth.ModrinthApp", "data/ModrinthApp"),
            ],
            lunar: vec![home.join(".lunarclient")],
            badlion: Vec::new(),
            feather: Vec::new(),
            oneclient: vec![data.join("Polyfrost/OneClient"), data.join("oneclient")],
            atlauncher: vec![data.join("atlauncher")],
            // Der alte GDLauncher hielt sich unter Linux nicht an XDG_DATA_HOME.
            gdlauncher: vec![config.join("gdlauncher_next")],
            carbon: vec![data.join("gdlauncher_carbon")],
            tlauncher: vec![home.join(".tlauncher")],
        }
    }
}

/// Wo andere Launcher ihre Instanzen ablegen (nur existierende Ordner).
#[derive(Default)]
struct ImportRoots {
    minecraft: Vec<PathBuf>,
    prism: Vec<PathBuf>,
    multimc: Vec<PathBuf>,
    curseforge: Vec<PathBuf>,
    modrinth: Vec<PathBuf>,
    lunar: Vec<PathBuf>,
    badlion: Vec<PathBuf>,
    feather: Vec<PathBuf>,
    oneclient: Vec<PathBuf>,
    atlauncher: Vec<PathBuf>,
    gdlauncher: Vec<PathBuf>,
    carbon: Vec<PathBuf>,
    tlauncher: Vec<PathBuf>,
}

impl ImportRoots {
    fn detect() -> Self {
        let existing = |candidates: Vec<PathBuf>| -> Vec<PathBuf> {
            let mut out: Vec<PathBuf> = Vec::new();
            for p in candidates.into_iter().filter(|p| p.is_dir()) {
                let key = std::fs::canonicalize(&p).unwrap_or_else(|_| p.clone());
                if !out.iter().any(|o| std::fs::canonicalize(o).unwrap_or_else(|_| o.clone()) == key) {
                    out.push(p);
                }
            }
            out
        };
        let homes = LauncherHomes::candidates();
        Self {
            minecraft: existing(homes.minecraft),
            prism: existing(homes.prism.into_iter().map(|d| mmc_instance_dir(d, "prismlauncher.cfg")).collect()),
            multimc: existing(homes.multimc.into_iter().map(|d| mmc_instance_dir(d, "multimc.cfg")).collect()),
            curseforge: existing(homes.curseforge),
            modrinth: existing(homes.modrinth).into_iter().filter(|p| p.join("app.db").is_file()).collect(),
            lunar: existing(homes.lunar),
            badlion: existing(homes.badlion),
            feather: existing(homes.feather),
            oneclient: existing(homes.oneclient.into_iter().map(|d| launchers::oneclient_data_dir(&d)).collect()),
            atlauncher: existing(homes.atlauncher),
            gdlauncher: existing(homes.gdlauncher.into_iter().map(|d| launchers::gdl_base(&d)).collect()),
            carbon: existing(homes.carbon.into_iter().map(|d| launchers::carbon_runtime(&d)).collect()),
            tlauncher: existing(homes.tlauncher),
        }
    }

    /// Quellen, deren Ordner auf diesem PC liegen.
    fn present(&self) -> Vec<ImportSource> {
        [
            (ImportSource::Vanilla, &self.minecraft),
            (ImportSource::Prism, &self.prism),
            (ImportSource::MultiMc, &self.multimc),
            (ImportSource::CurseForge, &self.curseforge),
            (ImportSource::Modrinth, &self.modrinth),
            (ImportSource::Lunar, &self.lunar),
            (ImportSource::Badlion, &self.badlion),
            (ImportSource::Feather, &self.feather),
            (ImportSource::OneClient, &self.oneclient),
            (ImportSource::AtLauncher, &self.atlauncher),
            (ImportSource::GdLauncher, &self.gdlauncher),
            (ImportSource::GdLauncherCarbon, &self.carbon),
            (ImportSource::TLauncher, &self.tlauncher),
        ]
        .into_iter()
        .filter(|(_, dirs)| !dirs.is_empty())
        .map(|(source, _)| source)
        .collect()
    }

    fn scan(&self, latest_release: Option<&str>) -> Vec<ImportCandidate> {
        let mut out = Vec::new();
        for dir in &self.minecraft {
            out.extend(scan_vanilla(dir, latest_release));
        }
        for dir in &self.prism {
            out.extend(scan_mmc(dir, ImportSource::Prism));
        }
        for dir in &self.multimc {
            out.extend(scan_mmc(dir, ImportSource::MultiMc));
        }
        for dir in &self.curseforge {
            out.extend(scan_curseforge(dir));
        }
        for dir in &self.modrinth {
            out.extend(scan_modrinth(dir));
        }
        let minecraft = self.minecraft.first().map(PathBuf::as_path);
        for dir in &self.lunar {
            out.extend(clients::scan_lunar(dir, minecraft));
        }
        if let Some(mc) = minecraft {
            for dir in &self.badlion {
                out.extend(clients::scan_badlion(dir, mc, latest_release));
            }
            if !self.tlauncher.is_empty() {
                out.extend(clients::scan_tlauncher(mc, latest_release));
            }
        }
        for dir in &self.oneclient {
            out.extend(launchers::scan_oneclient(dir));
        }
        for dir in &self.atlauncher {
            out.extend(launchers::scan_atlauncher(dir));
        }
        for dir in &self.gdlauncher {
            out.extend(launchers::scan_gdlauncher(dir));
        }
        for dir in &self.carbon {
            out.extend(launchers::scan_carbon(dir));
        }
        out
    }
}
