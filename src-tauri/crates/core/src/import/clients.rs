//! Clients mit eigenem, geschlossenem Launcher: Lunar Client, Badlion Client,
//! Feather, TLauncher. Ihre eigenen Mods sind fest eingebaut und nicht frei –
//! übernommen werden nur die Inhalte des Spielers (Welten, Ressourcen-/Shader-
//! pakete, Einstellungen, Serverliste und selbst hinzugefügte Standard-Mods).
//!
//! Geprüft an echten Dateien (Lunar-Launcher 3.7, mit Badlion-Integration):
//! - `~/.lunarclient/db/profiles.db`, Tabelle `profiles` (`name`, `path`,
//!   `type` ∈ lunar/modrinth/user-modpack/vanilla/curseforge/badlion,
//!   `game_version`, `loaders` (JSON), `loader_version`, `game_directory`,
//!   `mods_directory`, `is_badlion`); die Datenbank liegt im WAL-Modus, daher
//!   wird eine Kopie samt `-wal` gelesen,
//! - `~/.lunarclient/settings/launcher.json` → `settings.gameDirectory`,
//! - `~/.lunarclient/settings/game/accounts.json` = Zugangsdaten → nie gelesen.
//! - Badlion: `%APPDATA%\Badlion Client\Data\<version>.jar` (gestartete
//!   Version), Spielordner `.minecraft` (`badlion_settings.json`,
//!   `BLClient-*`); `accounts.dat`/`login_cache.dat` → nie gelesen.
//! - Feather: auf dem Entwicklungs-PC nicht installiert, keine öffentliche
//!   Beschreibung einer Profil-Liste → nur erkannt, nicht importiert.
//! - TLauncher: nicht installiert; nutzt `.minecraft` mit dem Standard-Format
//!   `versions/<id>/<id>.json` von Mojang (hier ausgewertet).
//!   `TlauncherProfiles.json` (Konten) → nie gelesen.

use std::path::{Path, PathBuf};

use super::{
    ImportCandidate, ImportNote, ImportSource, candidate, existing_abs_dir, guess_loader,
    parse_version_id, read_json, safe_file_name, with_db_copy,
};
use crate::instance::{Loader, LoaderKind};

// --- Lunar ---------------------------------------------------------------------------

/// Spielordner aus den Lunar-Einstellungen (nur dieses eine Feld wird benutzt).
fn lunar_game_dir(home: &Path) -> Option<PathBuf> {
    let settings = read_json(&home.join("settings").join("launcher.json"), 1024 * 1024)?;
    existing_abs_dir(settings.get("settings")?.get("gameDirectory")?.as_str()?)
}

/// Loader aus der `loaders`-Spalte (JSON-Liste aus Namen oder Objekten).
pub(super) fn lunar_loader(loaders_json: &str, loader_version: Option<String>, mods_dir: Option<&Path>) -> Loader {
    let value: serde_json::Value = serde_json::from_str(loaders_json).unwrap_or(serde_json::Value::Null);
    let names: Vec<String> = value
        .as_array()
        .map(|items| {
            items
                .iter()
                .filter_map(|x| {
                    x.as_str().map(str::to_owned).or_else(|| {
                        ["type", "name", "id", "loader"].iter().find_map(|k| x.get(k)?.as_str().map(str::to_owned))
                    })
                })
                .map(|n| n.to_ascii_lowercase())
                .collect()
        })
        .unwrap_or_default();
    let kind = [("neoforge", LoaderKind::NeoForge), ("forge", LoaderKind::Forge), ("quilt", LoaderKind::Quilt), ("fabric", LoaderKind::Fabric)]
        .into_iter()
        .find(|(n, _)| names.iter().any(|x| x.contains(n)))
        .map(|(_, k)| k);
    if let Some(kind) = kind {
        let version = loader_version.filter(|v| crate::instance::is_safe_version_string(v));
        return Loader { kind, version };
    }
    // Keine Angabe: eigene Mods im Mod-Ordner verraten den Loader (Lunar = Fabric).
    mods_dir.map_or_else(Loader::vanilla, guess_loader)
}

struct LunarProfile {
    name: String,
    path: String,
    kind: String,
    game_version: String,
    loaders: String,
    loader_version: Option<String>,
    game_directory: Option<String>,
    mods_directory: Option<String>,
    is_badlion: bool,
}

fn read_lunar_profiles(db: &Path) -> Option<Vec<LunarProfile>> {
    with_db_copy(db, |conn| {
        let mut stmt = conn
            .prepare(
                "SELECT name, path, type, game_version, loaders, loader_version, game_directory, mods_directory, is_badlion \
                 FROM profiles ORDER BY created_at",
            )
            .ok()?;
        let rows = stmt
            .query_map([], |r| {
                Ok(LunarProfile {
                    name: r.get(0)?,
                    path: r.get(1)?,
                    kind: r.get(2)?,
                    game_version: r.get(3)?,
                    loaders: r.get::<_, Option<String>>(4)?.unwrap_or_default(),
                    loader_version: r.get(5)?,
                    game_directory: r.get(6)?,
                    mods_directory: r.get(7)?,
                    is_badlion: r.get::<_, Option<i64>>(8)?.unwrap_or(0) != 0,
                })
            })
            .ok()?;
        Some(rows.flatten().take(500).collect())
    })
}

/// Lunar Client: Profile aus `db/profiles.db`. `minecraft` = `.minecraft`, falls
/// weder Profil noch Einstellungen einen Spielordner nennen.
pub(crate) fn scan_lunar(home: &Path, minecraft: Option<&Path>) -> Vec<ImportCandidate> {
    let setting = lunar_game_dir(home);
    let shared = setting.clone().or_else(|| minecraft.map(Path::to_path_buf));
    let Some(profiles) = read_lunar_profiles(&home.join("db").join("profiles.db")) else {
        return legacy_lunar(home, shared);
    };
    let mut out = Vec::new();
    for p in profiles {
        let profile_dir = safe_file_name(&p.path).then(|| home.join("profiles").join(&p.path));
        // Ein eigener Profilordner zählt nur als Spielordner, wenn dort schon gespielt wurde
        // (ein `mods`-Ordner allein ist bei Lunar nur der Mod-Ordner des Profils).
        let own_dir = profile_dir.clone().filter(|d| d.join("options.txt").is_file() || d.join("saves").is_dir());
        let game_dir = p.game_directory.as_deref().and_then(existing_abs_dir).or(own_dir).or_else(|| shared.clone());
        let Some(game_dir) = game_dir else { continue };
        let mods_dir = p
            .mods_directory
            .as_deref()
            .and_then(existing_abs_dir)
            .or_else(|| profile_dir.as_ref().map(|d| d.join("mods")).filter(|d| d.is_dir()));
        let loader = lunar_loader(&p.loaders, p.loader_version.clone(), mods_dir.as_deref());
        let source = if p.is_badlion || p.kind == "badlion" { ImportSource::Badlion } else { ImportSource::Lunar };
        let is_shared = profile_dir.as_ref().is_none_or(|d| !game_dir.starts_with(d));
        let Some(c) = candidate(source, &p.name, &p.game_version, loader, game_dir) else { continue };
        let mut c = c
            .with_extras(|x| {
                x.mods_dir = mods_dir;
                x.skip_game_mods = true;
                x.roots = vec![home.to_owned()];
            })
            .note(ImportNote::ClientModsSkipped);
        if is_shared {
            c = c.note(ImportNote::SharedGameDir);
        }
        out.push(c);
    }
    out
}

/// Ältere Lunar-Versionen ohne Profil-Datenbank: ein Eintrag für den
/// Spielordner, Version wählt der Nutzer.
fn legacy_lunar(home: &Path, game_dir: Option<PathBuf>) -> Vec<ImportCandidate> {
    let Some(game_dir) = game_dir.filter(|_| home.join("settings").is_dir()) else { return Vec::new() };
    let Some(version) = newest_plain_version(&game_dir) else { return Vec::new() };
    candidate(ImportSource::Lunar, "Lunar Client", &version, Loader::vanilla(), game_dir)
        .map(|c| {
            c.with_extras(|x| x.skip_game_mods = true)
                .note(ImportNote::ClientModsSkipped)
                .note(ImportNote::SharedGameDir)
                .guessed()
        })
        .into_iter()
        .collect()
}

// --- Badlion -------------------------------------------------------------------------

/// Ist das der Name einer Spielversion (`1.21.10`, `1.8.9`, `26.1`)?
fn looks_like_release(id: &str) -> bool {
    crate::meta::is_safe_id(id) && id.starts_with(|c: char| c.is_ascii_digit()) && id.contains('.') && !id.contains(' ')
}

/// Zuletzt gestartete Version: die jüngste `Data/<version>.jar`.
pub(super) fn badlion_version(badlion: &Path) -> Option<String> {
    let entries = std::fs::read_dir(badlion.join("Data")).ok()?;
    entries
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_str()?.to_owned();
            let id = name.strip_suffix(".jar")?.to_owned();
            let modified = e.metadata().ok()?.modified().ok()?;
            looks_like_release(&id).then_some((modified, id))
        })
        .max()
        .map(|(_, id)| id)
}

/// Badlion Client (eigener Launcher): spielt in `.minecraft`.
pub(super) fn scan_badlion(badlion: &Path, minecraft: &Path, latest: Option<&str>) -> Vec<ImportCandidate> {
    // Ohne diese Datei wurde Badlion in diesem Spielordner nie gestartet.
    if !minecraft.join("badlion_settings.json").is_file() {
        return Vec::new();
    }
    let Some(version) = badlion_version(badlion).or_else(|| latest.map(str::to_owned)) else { return Vec::new() };
    candidate(ImportSource::Badlion, "Badlion Client", &version, Loader::vanilla(), minecraft.to_owned())
        .map(|c| {
            c.with_extras(|x| x.skip_game_mods = true)
                .note(ImportNote::ClientModsSkipped)
                .note(ImportNote::SharedGameDir)
                .guessed()
        })
        .into_iter()
        .collect()
}

// --- TLauncher / Versions-JSON -------------------------------------------------------------

/// Wertet `versions/<id>/<id>.json` aus (Standardformat von Mojang, das auch
/// Fabric/Forge/NeoForge-Installer und TLauncher schreiben): `inheritsFrom` =
/// Spielversion, Bibliotheken verraten Loader und Loader-Version.
pub(crate) fn inspect_version_json(minecraft: &Path, id: &str) -> Option<(String, Loader)> {
    if !safe_file_name(id) {
        return None;
    }
    let json = read_json(&minecraft.join("versions").join(id).join(format!("{id}.json")), 4 * 1024 * 1024)?;
    let game = json.get("inheritsFrom")?.as_str()?.to_owned();
    if !crate::meta::is_safe_id(&game) {
        return None;
    }
    let names: Vec<&str> = json
        .get("libraries")
        .and_then(|l| l.as_array())
        .map(|libs| libs.iter().filter_map(|l| l.get("name")?.as_str()).collect())
        .unwrap_or_default();
    let version_of = |prefix: &str| -> Option<String> {
        names.iter().find_map(|n| n.strip_prefix(prefix).map(|rest| rest.split(':').next().unwrap_or(rest).to_owned()))
    };
    let clean = |v: String| Some(super::strip_game_prefix(&v, &game)).filter(|v| crate::instance::is_safe_version_string(v));
    let loader = if let Some(v) = version_of("net.fabricmc:fabric-loader:") {
        Loader { kind: LoaderKind::Fabric, version: clean(v) }
    } else if let Some(v) = version_of("org.quiltmc:quilt-loader:") {
        Loader { kind: LoaderKind::Quilt, version: clean(v) }
    } else if let Some(v) = version_of("net.neoforged:neoforge:") {
        Loader { kind: LoaderKind::NeoForge, version: clean(v) }
    } else if let Some(v) = version_of("net.minecraftforge:forge:").or_else(|| version_of("net.minecraftforge:fmlloader:")) {
        Loader { kind: LoaderKind::Forge, version: clean(v) }
    } else if version_of("net.neoforged:forge:").is_some() {
        // NeoForge für 1.20.1 hieß noch „forge“ – als Forge mit neuester Version.
        Loader { kind: LoaderKind::Forge, version: None }
    } else {
        // OptiFine & Co. ohne Modloader.
        Loader::vanilla()
    };
    Some((game, loader))
}

/// Jüngste reine Spielversion im `versions`-Ordner (nach Änderungszeit).
fn newest_plain_version(minecraft: &Path) -> Option<String> {
    let entries = std::fs::read_dir(minecraft.join("versions")).ok()?;
    entries
        .flatten()
        .take(1000)
        .filter_map(|e| {
            let id = e.file_name().to_str()?.to_owned();
            let json = e.path().join(format!("{id}.json"));
            let modified = std::fs::metadata(&json).ok()?.modified().ok()?;
            (looks_like_release(&id) && parse_version_id(&id)?.1.kind == LoaderKind::Vanilla).then_some((modified, id))
        })
        .max()
        .map(|(_, id)| id)
}

/// TLauncher: jede installierte Modloader-Version in `.minecraft/versions` plus
/// ein Eintrag für den Spielordner selbst (Version wählt der Nutzer).
pub(super) fn scan_tlauncher(minecraft: &Path, latest: Option<&str>) -> Vec<ImportCandidate> {
    let mut out = Vec::new();
    if let Ok(entries) = std::fs::read_dir(minecraft.join("versions")) {
        let mut ids: Vec<String> = entries.flatten().take(1000).filter_map(|e| e.file_name().to_str().map(str::to_owned)).collect();
        ids.sort();
        for id in ids {
            let Some((game, loader)) = inspect_version_json(minecraft, &id) else { continue };
            if loader.kind == LoaderKind::Vanilla {
                continue;
            }
            if let Some(c) = candidate(ImportSource::TLauncher, &id, &game, loader, minecraft.to_owned()) {
                out.push(c.note(ImportNote::SharedGameDir));
            }
        }
    }
    let version = newest_plain_version(minecraft).or_else(|| latest.map(str::to_owned));
    if let Some(c) = version.and_then(|v| candidate(ImportSource::TLauncher, "TLauncher", &v, Loader::vanilla(), minecraft.to_owned())) {
        out.push(c.note(ImportNote::SharedGameDir).guessed());
    }
    out
}
