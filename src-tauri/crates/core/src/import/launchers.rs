//! Offene Launcher, deren Formate am Quellcode geprüft wurden:
//!
//! - **OneClient/OneLauncher** (Polyfrost, GPL-3): Datenordner
//!   `%APPDATA%\Polyfrost\OneClient` bzw. früher `%LOCALAPPDATA%\Polyfrost\OneClient\data`
//!   (`oneclient_common/src/paths.rs`), verlegbar über `data_dir` in
//!   `settings.json`. Instanzen („Cluster“) in `user_data.db`, Tabelle
//!   `clusters` (`name`, `folder_name`, `mc_version`, `mc_loader` 0 Vanilla,
//!   1 Forge, 2 NeoForge, 3 Quilt, 4 Fabric, 5 Ornithe, `mc_loader_version`).
//!   Spielordner = `clusters/<ordner>` mit `.dedicated_directory`, sonst das
//!   geteilte `<daten>/.minecraft`; Mods liegen in `clusters/<ordner>/mods`
//!   (Windows: Hardlinks, Linux: Symlinks in `metadata/packages`).
//!   `auth.json` = Zugangsdaten → nie gelesen.
//! - **ATLauncher** (GPL-3): Ordner der Installation, unter Windows standardmäßig
//!   `%APPDATA%\ATLauncher` (Installer), Linux `~/.local/share/atlauncher` (AUR).
//!   `instances/<x>/instance.json`: `id` = Spielversion, `launcher.name`,
//!   `launcher.loaderVersion.{type,version}` (Fabric, LegacyFabric, Quilt,
//!   Forge, NeoForge; Paper/Purpur = Server), `launcher.mods[]` mit
//!   `file`, `type`, `disabled`, `curseForgeProjectId/FileId`,
//!   `modrinthProject.id`/`modrinthVersion.id`; deaktivierte Mods liegen in
//!   `disabledmods/`. `configs/accounts.json` → nie gelesen.
//! - **GDLauncher (alt)** (GPL-3): `%APPDATA%\gdlauncher_next` (Linux
//!   `~/.config/gdlauncher_next`), verlegbar über `override.data`;
//!   `instances/<name>/config.json` mit `loader.{loaderType, mcVersion,
//!   loaderVersion}` (ältere Dateien: `modloader`-Liste) und `mods[]`
//!   (`projectID`, `fileID`, `fileName` – CurseForge).
//! - **GDLauncher Carbon** (GPL-3): `%APPDATA%\gdlauncher_carbon\data` (bzw. Pfad in
//!   `runtime_path_override`), `instances/<x>/instance.json` mit `name` und
//!   `game_configuration.version = {release, modloaders[{type, version}]}`,
//!   Spielordner `instances/<x>/instance`.

use std::path::{Path, PathBuf};

use super::{
    ImportCandidate, ImportNote, ImportSource, TrackedFile, candidate, content_present, existing_abs_dir, loader_from_name,
    parse_version_id, read_json, read_small, safe_file_name, with_db_copy,
};
use crate::content::{self, ContentKind, Platform, Source};
use crate::instance::{Loader, LoaderKind};

const MAX_CONFIG: u64 = 16 * 1024 * 1024;

fn folder_name(dir: &Path) -> String {
    dir.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

fn child_dirs(dir: &Path) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    let mut dirs: Vec<PathBuf> =
        entries.flatten().take(1000).filter(|e| e.file_type().is_ok_and(|t| t.is_dir())).map(|e| e.path()).collect();
    dirs.sort();
    dirs
}

/// Text-Datei mit einem Pfad darin (Umleitung des Datenordners).
fn redirect(file: &Path) -> Option<PathBuf> {
    let bytes = read_small(file, 4096)?;
    existing_abs_dir(&String::from_utf8_lossy(&bytes))
}

// --- OneClient -------------------------------------------------------------------------

/// Datenordner: `data_dir` aus `settings.json`, sonst der Ordner selbst. Aus der
/// Datei wird nur dieses Feld benutzt (sie enthält auch einen API-Schlüssel).
pub(super) fn oneclient_data_dir(dir: &Path) -> PathBuf {
    read_json(&dir.join("settings.json"), 1024 * 1024)
        .and_then(|s| s.get("data_dir")?.as_str().and_then(existing_abs_dir))
        .unwrap_or_else(|| dir.to_owned())
}

pub(super) fn oneclient_loader(id: i64, version: Option<String>) -> Option<(Loader, bool)> {
    let name = match id {
        0 => "vanilla",
        1 => "forge",
        2 => "neoforge",
        3 => "quilt",
        4 => "fabric",
        5 => "ornithe",
        _ => return None,
    };
    loader_from_name(name, version.as_deref(), "")
}

pub(crate) fn scan_oneclient(data: &Path) -> Vec<ImportCandidate> {
    type Row = (String, String, String, i64, Option<String>);
    let rows: Vec<Row> = with_db_copy(&data.join("user_data.db"), |conn| {
        let mut stmt = conn.prepare("SELECT name, folder_name, mc_version, mc_loader, mc_loader_version FROM clusters").ok()?;
        let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))).ok()?;
        Some(rows.flatten().take(500).collect())
    })
    .unwrap_or_default();

    let mut out = Vec::new();
    for (name, folder, mc_version, loader_id, loader_version) in rows {
        if !safe_file_name(&folder) {
            continue;
        }
        let cluster = data.join("clusters").join(&folder);
        if !cluster.is_dir() {
            continue;
        }
        let Some((loader, mapped)) = oneclient_loader(loader_id, loader_version) else { continue };
        let dedicated = cluster.join(".dedicated_directory").exists();
        let game_dir = if dedicated { cluster.clone() } else { data.join(".minecraft") };
        let mods = cluster.join("mods");
        let Some(c) = candidate(ImportSource::OneClient, &name, &mc_version, loader, game_dir) else { continue };
        let mut c = c.with_extras(|x| {
            x.roots = vec![data.to_owned()];
            if !dedicated {
                x.skip_game_mods = true;
                x.mods_dir = mods.is_dir().then_some(mods);
            }
        });
        if !dedicated {
            c = c.note(ImportNote::SharedGameDir);
        }
        if mapped {
            c = c.note(ImportNote::LoaderMapped);
        }
        out.push(c);
    }
    out
}

// --- ATLauncher --------------------------------------------------------------------------

pub(super) fn scan_atlauncher(root: &Path) -> Vec<ImportCandidate> {
    child_dirs(&root.join("instances")).iter().filter_map(|d| atl_instance(d)).collect()
}

fn atl_kind(kind: &str) -> Option<ContentKind> {
    match kind {
        "mods" => Some(ContentKind::Mod),
        "resourcepack" | "texturepack" => Some(ContentKind::ResourcePack),
        "shaderpack" => Some(ContentKind::ShaderPack),
        _ => None,
    }
}

/// Herkunft der Mods laut `launcher.mods` – Modrinth vor CurseForge.
fn atl_tracking(mods: &[serde_json::Value], game_dir: &Path) -> Vec<TrackedFile> {
    let mut out = Vec::new();
    for m in mods.iter().take(3000) {
        let Some(kind) = m.get("type").and_then(|t| t.as_str()).and_then(atl_kind) else { continue };
        let Some(file) = m.get("file").and_then(|f| f.as_str()) else { continue };
        if content::validate_file_name(kind, file).is_err() {
            continue;
        }
        let disabled_copy = kind == ContentKind::Mod && game_dir.join("disabledmods").join(file).is_file();
        if !content_present(game_dir, kind, file) && !disabled_copy {
            continue;
        }
        let str_of = |v: Option<&serde_json::Value>| v.and_then(|x| x.as_str().map(str::to_owned).or_else(|| x.as_u64().map(|n| n.to_string())));
        let modrinth = str_of(m.get("modrinthProject").and_then(|p| p.get("id")))
            .zip(str_of(m.get("modrinthVersion").and_then(|v| v.get("id"))))
            .map(|(p, v)| Source::modrinth(p, v, None));
        let curse = str_of(m.get("curseForgeProjectId")).zip(str_of(m.get("curseForgeFileId"))).map(|(p, v)| Source {
            project_id: p,
            version_id: v,
            version_number: None,
            platform: Platform::CurseForge,
        });
        if let Some(source) = modrinth.or(curse) {
            out.push(TrackedFile { kind, file_name: file.to_owned(), source });
        }
    }
    out
}

pub(crate) fn atl_instance(dir: &Path) -> Option<ImportCandidate> {
    let json = read_json(&dir.join("instance.json"), MAX_CONFIG)?;
    let launcher = json.get("launcher")?.as_object()?;
    let game = json.get("id")?.as_str()?.to_owned();
    let name = launcher.get("name").and_then(|n| n.as_str()).map_or_else(|| folder_name(dir), str::to_owned);
    let (loader, mapped) = match launcher.get("loaderVersion").filter(|l| !l.is_null()) {
        Some(l) => loader_from_name(l.get("type")?.as_str()?, l.get("version").and_then(|v| v.as_str()), &game)?,
        None => (Loader::vanilla(), false),
    };
    let mods = launcher.get("mods").and_then(|m| m.as_array()).cloned().unwrap_or_default();
    let tracked = atl_tracking(&mods, dir);
    let c = candidate(ImportSource::AtLauncher, &name, &game, loader, dir.to_owned())?.with_extras(|x| {
        x.excludes = &["instance.json", "instance.png", "jarmods"];
        x.disabled_mods_dir = Some("disabledmods");
        x.tracked = tracked;
    });
    Some(if mapped { c.note(ImportNote::LoaderMapped) } else { c })
}

// --- GDLauncher (alt) -------------------------------------------------------------------------

/// Datenordner des alten GDLauncher (evtl. über `override.data` verlegt).
pub(super) fn gdl_base(dir: &Path) -> PathBuf {
    redirect(&dir.join("override.data")).unwrap_or_else(|| dir.to_owned())
}

pub(super) fn scan_gdlauncher(base: &Path) -> Vec<ImportCandidate> {
    child_dirs(&base.join("instances")).iter().filter_map(|d| gdl_instance(d)).collect()
}

pub(crate) fn gdl_instance(dir: &Path) -> Option<ImportCandidate> {
    let json = read_json(&dir.join("config.json"), MAX_CONFIG)?;
    let text = |v: Option<&serde_json::Value>| v.and_then(|x| x.as_str()).map(str::to_owned);
    let (kind, game, version) = if let Some(loader) = json.get("loader").filter(|l| l.is_object()) {
        (text(loader.get("loaderType"))?, text(loader.get("mcVersion"))?, text(loader.get("loaderVersion")))
    } else {
        // Ältere Dateien: [loaderType, mcVersion, loaderVersion, projectID, fileID, source]
        let list = json.get("modloader")?.as_array()?;
        (text(list.first())?, text(list.get(1))?, text(list.get(2)))
    };
    let (loader, mapped) = loader_from_name(&kind, version.as_deref(), &game)?;

    let mut tracked = Vec::new();
    for m in json.get("mods").and_then(|m| m.as_array()).map(Vec::as_slice).unwrap_or_default().iter().take(3000) {
        let id = |k: &str| m.get(k).and_then(|v| v.as_u64().map(|n| n.to_string()).or_else(|| v.as_str().map(str::to_owned)));
        let (Some(project), Some(file_id), Some(file)) = (id("projectID"), id("fileID"), m.get("fileName").and_then(|f| f.as_str()))
        else {
            continue;
        };
        let kind = [ContentKind::Mod, ContentKind::ResourcePack]
            .into_iter()
            .find(|k| content::validate_file_name(*k, file).is_ok() && content_present(dir, *k, file));
        if let Some(kind) = kind {
            let source = Source { project_id: project, version_id: file_id, version_number: None, platform: Platform::CurseForge };
            tracked.push(TrackedFile { kind, file_name: file.to_owned(), source });
        }
    }
    let c = candidate(ImportSource::GdLauncher, &folder_name(dir), &game, loader, dir.to_owned())?.with_extras(|x| {
        x.excludes = &["config.json", "config.bak.json", "config_new_temp.json", "installing.lock"];
        x.tracked = tracked;
    });
    Some(if mapped { c.note(ImportNote::LoaderMapped) } else { c })
}

// --- GDLauncher Carbon -----------------------------------------------------------------------

/// Laufzeitordner von Carbon: Pfad aus `runtime_path_override`, sonst `data`.
pub(super) fn carbon_runtime(dir: &Path) -> PathBuf {
    redirect(&dir.join("runtime_path_override")).unwrap_or_else(|| dir.join("data"))
}

pub(super) fn scan_carbon(runtime: &Path) -> Vec<ImportCandidate> {
    child_dirs(&runtime.join("instances")).iter().filter_map(|d| carbon_instance(d)).collect()
}

pub(crate) fn carbon_instance(dir: &Path) -> Option<ImportCandidate> {
    let json = read_json(&dir.join("instance.json"), MAX_CONFIG)?;
    let config = json.get("game_configuration")?;
    let name = json.get("name").and_then(|n| n.as_str()).map_or_else(|| folder_name(dir), str::to_owned);
    let version = config.get("version")?;
    let (game, loader, mapped) = if let Some(custom) = version.as_str() {
        let (game, loader) = parse_version_id(custom)?;
        (game, loader, false)
    } else {
        let game = version.get("release")?.as_str()?.to_owned();
        let first = version.get("modloaders").and_then(|m| m.as_array()).and_then(|m| m.first());
        let (loader, mapped) = match first {
            Some(m) => loader_from_name(m.get("type")?.as_str()?, m.get("version").and_then(|v| v.as_str()), &game)?,
            None => (Loader { kind: LoaderKind::Vanilla, version: None }, false),
        };
        (game, loader, mapped)
    };
    let c = candidate(ImportSource::GdLauncherCarbon, &name, &game, loader, dir.join("instance"))?;
    Some(if mapped { c.note(ImportNote::LoaderMapped) } else { c })
}
