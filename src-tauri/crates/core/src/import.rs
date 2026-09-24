//! Import aus anderen Launchern: offizieller Launcher (`.minecraft`),
//! Prism/MultiMC und die CurseForge-App. Erkannte Installationen werden als
//! neue TRS-Instanz angelegt; Welten, Mods, Configs & Co. werden kopiert.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use serde::{Deserialize, Serialize};

use crate::instance::{Instance, Loader, LoaderKind, NewInstance};
use crate::{Error, Launcher, Result};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ImportSource {
    Vanilla,
    Prism,
    MultiMc,
    CurseForge,
    Modrinth,
    /// Von Hand gewählter Ordner (anderer Client, Backup, …).
    Folder,
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
    /// Version/Loader nur geschätzt – der Nutzer soll sie vor dem Import prüfen.
    pub version_guessed: bool,
    #[serde(skip)]
    game_dir: PathBuf,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportProgress {
    pub percent: f64,
    pub done_files: u64,
    pub total_files: u64,
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
    "minecraftinstance.json",
    "usercache.json",
    "usernamecache.json",
    "clientid_v2.txt",
    "command_history.txt",
];

/// Präfixe für ganze Datei-/Ordnerfamilien (Launcher-Profile, Badlion, …).
const EXCLUDE_PREFIXES: &[&str] = &["launcher_", "blclient", "badlion", "treatment_tags", "feather"];

fn is_excluded(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    EXCLUDE_TOP.contains(&lower.as_str()) || EXCLUDE_PREFIXES.iter().any(|p| lower.starts_with(p))
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

fn stats(game_dir: &Path) -> (u32, u32) {
    let mods = count_entries(&game_dir.join("mods"), |e| {
        e.file_name().to_string_lossy().to_ascii_lowercase().ends_with(".jar")
    });
    let worlds = count_entries(&game_dir.join("saves"), |e| e.path().join("level.dat").is_file());
    (mods, worlds)
}

fn candidate(source: ImportSource, name: &str, game_version: &str, loader: Loader, game_dir: PathBuf) -> Option<ImportCandidate> {
    if !crate::meta::is_safe_id(game_version) || loader.validate().is_err() || !game_dir.is_dir() {
        return None;
    }
    let (mod_count, world_count) = stats(&game_dir);
    let name: String = name.trim().chars().filter(|c| !c.is_control()).take(64).collect();
    Some(ImportCandidate {
        id: candidate_id(source, &game_dir, &name),
        source,
        name: if name.is_empty() { game_version.to_owned() } else { name },
        game_version: game_version.to_owned(),
        loader,
        mod_count,
        world_count,
        version_guessed: false,
        game_dir,
    })
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

fn scan_vanilla(minecraft: &Path, latest_release: Option<&str>) -> Vec<ImportCandidate> {
    let file = minecraft.join("launcher_profiles.json");
    let profiles: LauncherProfiles = std::fs::read(&file)
        .ok()
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
        let Some((game_version, loader)) = version.and_then(parse_version_id) else { continue };
        let game_dir = profile.game_dir.as_deref().filter(|d| !d.trim().is_empty()).map_or_else(|| minecraft.to_owned(), PathBuf::from);
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
    let pack: MmcPack = std::fs::read(dir.join("mmc-pack.json")).ok().and_then(|b| serde_json::from_slice(&b).ok())?;
    let (game_version, loader) = parse_mmc_pack(&pack)?;
    let cfg = std::fs::read_to_string(dir.join("instance.cfg")).unwrap_or_default();
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
}

#[derive(Deserialize)]
struct CurseLoader {
    name: String,
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

fn curse_instance(dir: &Path) -> Option<ImportCandidate> {
    let inst: CurseInstance =
        std::fs::read(dir.join("minecraftinstance.json")).ok().and_then(|b| serde_json::from_slice(&b).ok())?;
    let loader =
        inst.base_mod_loader.as_ref().map_or_else(Loader::vanilla, |l| parse_curse_loader(&l.name, &inst.game_version));
    // Bewusst der Ordner des Eintrags und nicht `installPath` aus der Datei:
    // kopierte CurseForge-Instanzen zeigen dort oft noch auf das Original.
    candidate(ImportSource::CurseForge, &inst.name, &inst.game_version, loader, dir.to_owned())
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
                let safe = !path.is_empty() && !path.contains(['/', '\\', ':']) && path != "." && path != "..";
                let dir = profiles.join(&path);
                safe.then_some(())?;
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
    if let Some(c) = mmc_instance(dir, ImportSource::Prism).or_else(|| curse_instance(dir)) {
        return vec![c];
    }
    // Ein Ordner voller Instanzen (z. B. `PrismLauncher/instances` oder CurseForge `Instances`)?
    let nested: Vec<_> = scan_mmc(dir, ImportSource::Prism).into_iter().chain(scan_curseforge(dir)).collect();
    if !nested.is_empty() {
        return nested;
    }
    // Sonst: ein nackter Spielordner – Version unbekannt, Loader anhand der Mods geraten.
    let looks_like_game = ["mods", "saves", "config", "options.txt", "resourcepacks"].iter().any(|n| dir.join(n).exists());
    let Some(version) = latest_release.filter(|_| looks_like_game) else { return Vec::new() };
    let name = dir.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "Import".into());
    candidate(ImportSource::Folder, &name, version, guess_loader(&dir.join("mods")), dir.to_owned())
        .map(|c| ImportCandidate { version_guessed: true, ..c })
        .into_iter()
        .collect()
}

/// Schaut in bis zu 20 Mod-Jars, für welchen Loader sie gebaut sind.
fn guess_loader(mods: &Path) -> Loader {
    let Ok(entries) = std::fs::read_dir(mods) else { return Loader::vanilla() };
    for entry in entries.flatten().take(20) {
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

// --- Kopieren ----------------------------------------------------------------

fn collect_files(root: &Path, dir: &Path, top: bool, out: &mut Vec<(PathBuf, u64)>) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if top && is_excluded(&name) {
            continue;
        }
        // Symlinks nicht folgen – sie könnten aus dem Ordner herausführen.
        let Ok(kind) = entry.file_type() else { continue };
        if kind.is_dir() {
            collect_files(root, &entry.path(), false, out);
        } else if kind.is_file() {
            let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
            if let Ok(rel) = entry.path().strip_prefix(root) {
                out.push((rel.to_owned(), size));
            }
        }
    }
}

fn copy_tree(from: &Path, to: &Path, include_mods: bool, on_progress: &ImportProgressFn) -> std::io::Result<()> {
    let mut files = Vec::new();
    collect_files(from, from, true, &mut files);
    if !include_mods {
        files.retain(|(rel, _)| !rel.starts_with("mods"));
    }

    let total_bytes: u64 = files.iter().map(|(_, s)| s).sum::<u64>().max(1);
    let total_files = files.len() as u64;
    let done_bytes = AtomicU64::new(0);
    let mut last_percent = -1.0;

    for (i, (rel, size)) in files.iter().enumerate() {
        let target = to.join(rel);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::copy(from.join(rel), &target)?;
        let done = done_bytes.fetch_add(*size, Ordering::Relaxed) + size;
        let percent = (done as f64 / total_bytes as f64 * 100.0).min(100.0).floor();
        if percent > last_percent {
            last_percent = percent;
            on_progress(ImportProgress { percent, done_files: i as u64 + 1, total_files });
        }
    }
    on_progress(ImportProgress { percent: 100.0, done_files: total_files, total_files });
    Ok(())
}

impl Launcher {
    /// Sucht Installationen anderer Launcher auf diesem Rechner.
    pub async fn scan_imports(&self) -> Result<Vec<ImportCandidate>> {
        let latest = self.version_manifest(false).await.ok().map(|m| m.latest.release);
        let roots = ImportRoots::detect();
        let custom = self.import_folders.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
        let mut found = tokio::task::spawn_blocking(move || {
            let mut all = roots.scan(latest.as_deref());
            for dir in &custom {
                all.extend(scan_folder(dir, latest.as_deref()));
            }
            // Derselbe Ordner kann über mehrere Wege gefunden werden.
            let mut seen = std::collections::HashSet::new();
            all.retain(|c| seen.insert(c.id.clone()));
            all
        })
            .await
            .map_err(|e| Error::Internal(e.to_string()))?;

        // Nur Versionen anbieten, die es wirklich gibt (Snapshots aus alten Profilen etc.).
        if let Ok(manifest) = self.version_manifest(false).await {
            found.retain(|c| manifest.find(&c.game_version).is_some());
        }
        found.sort_by_key(|c| c.name.to_lowercase());
        Ok(found)
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
            return Err(Error::validation(crate::msg!("import.noInstallation", "In diesem Ordner wurde keine Minecraft-Installation gefunden. Wähle den Ordner, in dem \
                 mods, saves oder options.txt liegen.")));
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
    ) -> Result<Instance> {
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
                crate::history::HistoryEntry::new(crate::history::HistoryKind::Imported)
                    .subject(source.unwrap_or_default()),
            )
            .await?;

        let from = candidate.game_dir.clone();
        let to = self.paths().instance_game_dir(&instance.id);
        // Mods passen nur zum selben Modloader – bei Vanilla bleiben sie weg.
        let include_mods = candidate.loader.kind != LoaderKind::Vanilla;
        let result = tokio::task::block_in_place(|| copy_tree(&from, &to, include_mods, on_progress));

        if let Err(e) = result {
            let _ = self.instances().delete(&instance.id).await;
            return Err(Error::io(to, e));
        }
        Ok(instance)
    }
}

struct ImportRoots {
    minecraft: Option<PathBuf>,
    prism: Option<PathBuf>,
    multimc: Option<PathBuf>,
    curseforge: Option<PathBuf>,
    modrinth: Option<PathBuf>,
}

impl ImportRoots {
    fn detect() -> Self {
        let appdata = std::env::var_os("APPDATA").map(PathBuf::from);
        let home = std::env::var_os("USERPROFILE").map(PathBuf::from);
        let existing = |p: Option<PathBuf>| p.filter(|p| p.is_dir());
        Self {
            minecraft: existing(appdata.as_ref().map(|a| a.join(".minecraft"))),
            prism: existing(appdata.as_ref().map(|a| a.join("PrismLauncher").join("instances"))),
            multimc: existing(appdata.as_ref().map(|a| a.join("MultiMC").join("instances"))),
            curseforge: existing(home.as_ref().map(|h| h.join("curseforge").join("minecraft").join("Instances"))),
            modrinth: ["ModrinthApp", "com.modrinth.theseus"]
                .iter()
                .find_map(|d| existing(appdata.as_ref().map(|a| a.join(d))).filter(|p| p.join("app.db").is_file())),
        }
    }

    fn scan(&self, latest_release: Option<&str>) -> Vec<ImportCandidate> {
        let mut out = Vec::new();
        if let Some(dir) = &self.minecraft {
            out.extend(scan_vanilla(dir, latest_release));
        }
        if let Some(dir) = &self.prism {
            out.extend(scan_mmc(dir, ImportSource::Prism));
        }
        if let Some(dir) = &self.multimc {
            out.extend(scan_mmc(dir, ImportSource::MultiMc));
        }
        if let Some(dir) = &self.curseforge {
            out.extend(scan_curseforge(dir));
        }
        if let Some(dir) = &self.modrinth {
            out.extend(scan_modrinth(dir));
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_ids() {
        let (g, l) = parse_version_id("fabric-loader-0.16.10-1.21.1").unwrap();
        assert_eq!((g.as_str(), l.kind, l.version.as_deref()), ("1.21.1", LoaderKind::Fabric, Some("0.16.10")));
        let (g, l) = parse_version_id("1.20.1-forge-47.3.0").unwrap();
        assert_eq!((g.as_str(), l.kind, l.version.as_deref()), ("1.20.1", LoaderKind::Forge, Some("47.3.0")));
        let (g, l) = parse_version_id("neoforge-21.1.77").unwrap();
        assert_eq!((g.as_str(), l.kind), ("1.21.1", LoaderKind::NeoForge));
        assert_eq!(parse_version_id("neoforge-21.0.10").unwrap().0, "1.21");
        let (g, l) = parse_version_id("1.21.11").unwrap();
        assert_eq!((g.as_str(), l.kind), ("1.21.11", LoaderKind::Vanilla));
    }

    #[test]
    fn curse_loaders() {
        let l = parse_curse_loader("forge-47.3.33", "1.20.1");
        assert_eq!((l.kind, l.version.as_deref()), (LoaderKind::Forge, Some("47.3.33")));
        let l = parse_curse_loader("fabric-0.16.10-1.21.1", "1.21.1");
        assert_eq!((l.kind, l.version.as_deref()), (LoaderKind::Fabric, Some("0.16.10")));
        assert_eq!(parse_curse_loader("unbekannt", "1.20.1").kind, LoaderKind::Vanilla);
    }

    #[test]
    fn folder_scan_detects_formats() {
        let dir = tempfile::tempdir().unwrap();
        // Nackter Spielordner mit Fabric-Mod
        let game = dir.path().join("Lunar-Profil");
        std::fs::create_dir_all(game.join("mods")).unwrap();
        {
            use std::io::Write;
            let mut zip = zip::ZipWriter::new(std::fs::File::create(game.join("mods/a.jar")).unwrap());
            zip.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(b"{}").unwrap();
            zip.finish().unwrap();
        }
        let found = scan_folder(&game, Some("1.21.1"));
        assert_eq!(found.len(), 1);
        assert!(found[0].version_guessed);
        assert_eq!((found[0].source, found[0].loader.kind), (ImportSource::Folder, LoaderKind::Fabric));

        // Leerer Ordner: nichts
        let empty = dir.path().join("leer");
        std::fs::create_dir_all(&empty).unwrap();
        assert!(scan_folder(&empty, Some("1.21.1")).is_empty());

        // Einzelne CurseForge-Instanz direkt gewählt
        let cf = dir.path().join("cf");
        std::fs::create_dir_all(&cf).unwrap();
        std::fs::write(cf.join("minecraftinstance.json"), r#"{"name":"CF","gameVersion":"1.20.1","baseModLoader":{"name":"forge-47.3.0"}}"#).unwrap();
        let found = scan_folder(&cf, None);
        assert_eq!((found.len(), found[0].version_guessed), (1, false));
    }

    #[test]
    fn reads_modrinth_app_database() {
        let dir = tempfile::tempdir().unwrap();
        let app = dir.path().join("ModrinthApp");
        let custom = dir.path().join("Modrinth");
        std::fs::create_dir_all(&app).unwrap();
        std::fs::create_dir_all(custom.join("profiles/Freizeitpark/mods")).unwrap();
        std::fs::create_dir_all(custom.join("profiles/Neo")).unwrap();
        let db = rusqlite::Connection::open(app.join("app.db")).unwrap();
        db.execute_batch(&format!(
            "CREATE TABLE settings (id INTEGER, custom_dir TEXT);
             INSERT INTO settings VALUES (0, '{}');
             CREATE TABLE instances (id TEXT, path TEXT, applied_content_set_id TEXT, name TEXT);
             CREATE TABLE instance_content_sets (id TEXT, game_version TEXT, loader TEXT, loader_version TEXT);
             INSERT INTO instances VALUES ('a', 'Freizeitpark', 'ca', 'Freizeitpark');
             INSERT INTO instances VALUES ('b', 'Neo', 'cb', 'Create Live');
             INSERT INTO instances VALUES ('c', '..', 'ca', 'Böse');
             INSERT INTO instance_content_sets VALUES ('ca', '1.21.1', 'fabric', '0.16.13');
             INSERT INTO instance_content_sets VALUES ('cb', '1.21.1', 'neoforge', '21.1.180');",
            custom.display().to_string().replace('\'', "''")
        ))
        .unwrap();
        drop(db);

        let found = scan_modrinth(&app);
        assert_eq!(found.len(), 2, "{found:#?}");
        let fp = found.iter().find(|c| c.name == "Freizeitpark").unwrap();
        assert_eq!((fp.loader.kind, fp.loader.version.as_deref()), (LoaderKind::Fabric, Some("0.16.13")));
        assert!(found.iter().any(|c| c.loader.kind == LoaderKind::NeoForge));
    }

    #[test]
    fn exclusions() {
        for name in ["versions", "Libraries", "launcher_profiles.json", "BLClient-Menu-Styles", "badlion_settings.json", "webcache2"] {
            assert!(is_excluded(name), "{name}");
        }
        for name in ["saves", "mods", "config", "options.txt", "servers.dat", "resourcepacks", "kubejs"] {
            assert!(!is_excluded(name), "{name}");
        }
    }

    #[test]
    fn scans_all_formats_and_copies() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();

        // Offizieller Launcher
        let mc = root.join(".minecraft");
        std::fs::create_dir_all(mc.join("saves/Welt")).unwrap();
        std::fs::write(mc.join("saves/Welt/level.dat"), b"x").unwrap();
        std::fs::create_dir_all(mc.join("versions/1.21.1")).unwrap();
        std::fs::write(mc.join("options.txt"), b"fov:90").unwrap();
        std::fs::write(mc.join("launcher_accounts.json"), b"geheim").unwrap();
        std::fs::write(
            mc.join("launcher_profiles.json"),
            r#"{"profiles":{
                "a":{"type":"custom","name":"Mein Profil","lastVersionId":"1.21.1"},
                "b":{"type":"latest-release","lastVersionId":"latest-release"},
                "c":{"type":"latest-snapshot","lastVersionId":"latest-snapshot"}}}"#,
        )
        .unwrap();

        // Prism
        let prism = root.join("prism");
        let inst = prism.join("Fabric Pack");
        std::fs::create_dir_all(inst.join(".minecraft/mods")).unwrap();
        std::fs::write(inst.join(".minecraft/mods/sodium.jar"), b"jar").unwrap();
        std::fs::write(inst.join("instance.cfg"), "[General]\nname=Fabric Pack\n").unwrap();
        std::fs::write(
            inst.join("mmc-pack.json"),
            r#"{"components":[{"uid":"net.minecraft","version":"1.21.1"},{"uid":"net.fabricmc.fabric-loader","version":"0.16.10"}]}"#,
        )
        .unwrap();

        // CurseForge
        let curse = root.join("curse");
        let cf = curse.join("ATM9");
        std::fs::create_dir_all(cf.join("mods")).unwrap();
        std::fs::write(cf.join("mods/a.jar"), b"jar").unwrap();
        std::fs::write(
            cf.join("minecraftinstance.json"),
            r#"{"name":"ATM9","gameVersion":"1.20.1","baseModLoader":{"name":"forge-47.3.33"},"installPath":"C:\\woanders"}"#,
        )
        .unwrap();

        let roots = ImportRoots { minecraft: Some(mc.clone()), prism: Some(prism), multimc: None, curseforge: Some(curse), modrinth: None };
        let found = roots.scan(Some("1.21.4"));
        assert_eq!(found.len(), 4, "{found:#?}");

        let vanilla = found.iter().find(|c| c.name == "Mein Profil").unwrap();
        assert_eq!(vanilla.world_count, 1);
        assert!(found.iter().any(|c| c.source == ImportSource::Vanilla && c.game_version == "1.21.4"));
        let fabric = found.iter().find(|c| c.source == ImportSource::Prism).unwrap();
        assert_eq!((fabric.loader.kind, fabric.mod_count), (LoaderKind::Fabric, 1));
        let atm = found.iter().find(|c| c.source == ImportSource::CurseForge).unwrap();
        assert_eq!(atm.game_dir, cf);

        // IDs bleiben über Scans hinweg gleich.
        assert_eq!(roots.scan(Some("1.21.4")).iter().find(|c| c.name == "Mein Profil").unwrap().id, vanilla.id);

        let target = root.join("ziel");
        copy_tree(&mc, &target, false, &|_| {}).unwrap();
        assert!(target.join("saves/Welt/level.dat").is_file());
        assert!(target.join("options.txt").is_file());
        assert!(!target.join("launcher_accounts.json").exists(), "Zugangsdaten dürfen nicht mitkopiert werden");
        assert!(!target.join("versions").exists());
    }
}
