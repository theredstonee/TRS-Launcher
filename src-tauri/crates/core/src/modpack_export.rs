//! Instanz als Modrinth-Modpack (`.mrpack`) exportieren.
//!
//! Dateien, die Modrinth per Prüfsumme kennt, landen als Download-Eintrag in
//! `modrinth.index.json` (das Pack bleibt dadurch klein und legal weitergebbar);
//! alles andere wandert nach `overrides/`. Welche Ordner mitkommen, entscheidet
//! der Nutzer.

use std::collections::HashMap;
use std::io::Write;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::instance::{Instance, LoaderKind, validate_id};
use crate::paths::Paths;
use crate::{Error, Launcher, Result, loaders, modrinth};

/// Ordner/Dateien, die standardmäßig angehakt sind.
const RECOMMENDED: &[&str] =
    &["mods", "config", "resourcepacks", "shaderpacks", "datapacks", "options.txt", "servers.dat", "kubejs"];
/// Nie zum Export angeboten: Logs, Abstürze, Zwischenspeicher, Konto-Reste.
const NEVER: &[&str] = &[
    "logs",
    "crash-reports",
    "launcher-logs",
    ".fabric",
    ".mixin.out",
    ".cache",
    "usercache.json",
    "usernamecache.json",
    "realms_persistence.json",
    "launcher_accounts.json",
    "screenshots",
];
/// Diese Ordner werden gegen Modrinth geprüft (dort liegen Downloads).
const LOOKUP_DIRS: &[&str] = &["mods", "resourcepacks", "shaderpacks", "datapacks"];

const MAX_FILES: usize = 10_000;
const MAX_TOTAL_BYTES: u64 = 4 << 30;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    pub files: u64,
    /// Wird vorgeschlagen (angehakt), weil es zum Modpack gehört.
    pub recommended: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportOptions {
    pub name: String,
    pub version: String,
    #[serde(default)]
    pub summary: Option<String>,
    /// Oberste Ordner/Dateien im Spielordner, die mitgehen sollen.
    #[serde(default)]
    pub include: Vec<String>,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ExportPhase {
    /// Dateien einlesen und Prüfsummen bilden.
    Hashing,
    /// Modrinth nach den Prüfsummen fragen.
    Lookup,
    /// `.mrpack` schreiben.
    Writing,
}

#[derive(Debug, Clone, Copy, Serialize)]
pub struct ExportProgress {
    pub phase: ExportPhase,
    pub percent: f64,
}

/// Wird geteilt (auch in Blocking-Tasks) – daher `Arc`.
pub type ExportProgressFn = std::sync::Arc<dyn Fn(ExportProgress) + Send + Sync>;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportSummary {
    /// Dateien, die als Modrinth-Download im Index stehen.
    pub downloads: usize,
    /// Dateien, die als Kopie im Pack liegen.
    pub overrides: usize,
    pub bytes: u64,
    pub file_name: String,
}

// --- Auswahl ---------------------------------------------------------------------

fn is_plain_entry(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 120
        && name != "."
        && name != ".."
        && !name.chars().any(|c| c.is_control() || matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|'))
}

fn is_offered(name: &str) -> bool {
    is_plain_entry(name) && !NEVER.iter().any(|n| n.eq_ignore_ascii_case(name))
}

/// Was im Spielordner liegt und mitexportiert werden kann.
pub async fn export_candidates(paths: &Paths, instance_id: &str) -> Result<Vec<ExportEntry>> {
    validate_id(instance_id)?;
    let game_dir = paths.instance_game_dir(instance_id);
    let entries = tokio::task::spawn_blocking(move || -> Vec<ExportEntry> {
        let Ok(read) = std::fs::read_dir(&game_dir) else { return Vec::new() };
        let mut list = Vec::new();
        for entry in read.flatten() {
            let Ok(name) = entry.file_name().into_string() else { continue };
            if !is_offered(&name) {
                continue;
            }
            let Ok(kind) = entry.file_type() else { continue };
            if kind.is_symlink() {
                continue;
            }
            let (size, files) = if kind.is_dir() {
                let mut size = 0;
                let mut files = 0;
                collect_dir(&entry.path(), &PathBuf::from(&name), &mut |_, len| {
                    size += len;
                    files += 1;
                });
                (size, files)
            } else {
                (entry.metadata().map(|m| m.len()).unwrap_or(0), 1)
            };
            list.push(ExportEntry {
                recommended: RECOMMENDED.iter().any(|r| r.eq_ignore_ascii_case(&name)),
                name,
                is_dir: kind.is_dir(),
                size,
                files,
            });
        }
        list.sort_by(|a, b| b.recommended.cmp(&a.recommended).then_with(|| a.name.cmp(&b.name)));
        list
    })
    .await
    .map_err(|e| Error::Internal(e.to_string()))?;
    Ok(entries)
}

/// Läuft rekursiv durch einen Ordner; `visit` bekommt den Pfad relativ zum
/// Spielordner und die Dateigröße. Verknüpfungen werden ausgelassen.
fn collect_dir(dir: &Path, rel: &Path, visit: &mut impl FnMut(PathBuf, u64)) {
    let Ok(read) = std::fs::read_dir(dir) else { return };
    for entry in read.flatten() {
        let Ok(name) = entry.file_name().into_string() else { continue };
        if !is_plain_entry(&name) {
            continue;
        }
        let Ok(kind) = entry.file_type() else { continue };
        let child = rel.join(&name);
        if kind.is_dir() {
            collect_dir(&entry.path(), &child, visit);
        } else if kind.is_file() {
            let len = entry.metadata().map(|m| m.len()).unwrap_or(0);
            visit(child, len);
        }
    }
}

/// Sammelt alle Dateien der gewählten Einträge (relativ zum Spielordner).
fn plan_files(game_dir: &Path, include: &[String]) -> Result<Vec<(PathBuf, u64)>> {
    let mut files = Vec::new();
    let mut total = 0u64;
    for name in include {
        if !is_offered(name) {
            return Err(Error::validation(crate::msg!(
                "modpackExport.selectionNotExportable",
                "Diese Auswahl kann nicht exportiert werden."
            )));
        }
        let path = game_dir.join(name);
        let Ok(meta) = std::fs::symlink_metadata(&path) else { continue };
        if meta.is_symlink() {
            continue;
        }
        if meta.is_dir() {
            collect_dir(&path, &PathBuf::from(name), &mut |rel, len| {
                total += len;
                files.push((rel, len));
            });
        } else if meta.is_file() {
            total += meta.len();
            files.push((PathBuf::from(name), meta.len()));
        }
    }
    if files.len() > MAX_FILES {
        return Err(Error::validation(crate::msg!(
            "modpackExport.tooManyFiles",
            "Zu viele Dateien für ein Modpack – bitte weniger Ordner auswählen."
        )));
    }
    if total > MAX_TOTAL_BYTES {
        return Err(Error::validation(crate::msg!(
            "modpackExport.tooLarge",
            "Die Auswahl ist zu groß (mehr als 4 GB)."
        )));
    }
    files.sort();
    Ok(files)
}

/// Pfad im Pack: immer mit `/`, nie mit `\`.
fn pack_path(rel: &Path) -> String {
    rel.components()
        .filter_map(|c| match c {
            std::path::Component::Normal(part) => part.to_str(),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("/")
}

/// Kommt die Datei aus einem Ordner, für den es Modrinth-Downloads gibt?
fn is_lookup_candidate(rel: &Path) -> bool {
    let path = pack_path(rel).to_ascii_lowercase();
    let Some((dir, rest)) = path.split_once('/') else { return false };
    LOOKUP_DIRS.contains(&dir) && !rest.contains('/') && (rest.ends_with(".jar") || rest.ends_with(".zip"))
}

// --- Index -------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct IndexFile {
    path: String,
    hashes: IndexHashes,
    env: IndexEnv,
    downloads: Vec<String>,
    file_size: u64,
}

#[derive(Debug, Clone, Serialize)]
struct IndexHashes {
    sha1: String,
    sha512: String,
}

#[derive(Debug, Clone, Serialize)]
struct IndexEnv {
    client: &'static str,
    server: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PackIndex {
    format_version: u32,
    game: &'static str,
    version_id: String,
    name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    summary: Option<String>,
    files: Vec<IndexFile>,
    dependencies: std::collections::BTreeMap<String, String>,
}

/// Datei, die Modrinth kennt: Download-Adresse und Prüfsummen.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Resolved {
    pub url: String,
    pub sha1: String,
    pub sha512: String,
    pub size: u64,
}

fn clean_text(value: &str, max: usize) -> String {
    value.trim().chars().filter(|c| !c.is_control()).take(max).collect()
}

fn validate_options(options: &ExportOptions) -> Result<(String, String, Option<String>)> {
    let name = clean_text(&options.name, 64);
    if name.is_empty() {
        return Err(Error::validation(crate::msg!(
            "modpackExport.nameRequired",
            "Bitte einen Namen für das Modpack eingeben."
        )));
    }
    let version = clean_text(&options.version, 32);
    let version_ok = !version.is_empty()
        && version.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+'));
    if !version_ok {
        return Err(Error::validation(crate::msg!(
            "modpackExport.invalidVersion",
            "Die Version darf nur Buchstaben, Ziffern und . - _ + enthalten."
        )));
    }
    let summary = options.summary.as_deref().map(|s| clean_text(s, 512)).filter(|s| !s.is_empty());
    if options.include.len() > 100 {
        return Err(Error::validation(crate::msg!("modpackExport.tooManyEntries", "Zu viele Einträge ausgewählt.")));
    }
    Ok((name, version, summary))
}

fn dependencies(instance: &Instance, loader_version: Option<String>) -> std::collections::BTreeMap<String, String> {
    let mut deps = std::collections::BTreeMap::new();
    deps.insert("minecraft".to_owned(), instance.game_version.clone());
    let key = match instance.loader.kind {
        LoaderKind::Vanilla => None,
        LoaderKind::Fabric => Some("fabric-loader"),
        LoaderKind::Quilt => Some("quilt-loader"),
        LoaderKind::Forge => Some("forge"),
        LoaderKind::NeoForge => Some("neoforge"),
    };
    if let (Some(key), Some(version)) = (key, instance.loader.version.clone().or(loader_version)) {
        deps.insert(key.to_owned(), version);
    }
    deps
}

/// Baut den Index; `resolved` ordnet Pack-Pfaden die Modrinth-Downloads zu.
fn build_index(
    name: &str,
    version: &str,
    summary: Option<&str>,
    deps: std::collections::BTreeMap<String, String>,
    resolved: &HashMap<String, Resolved>,
) -> PackIndex {
    let mut files: Vec<IndexFile> = resolved
        .iter()
        .map(|(path, r)| IndexFile {
            path: path.clone(),
            hashes: IndexHashes { sha1: r.sha1.clone(), sha512: r.sha512.clone() },
            // Für einen Client-Export ist alles clientseitig Pflicht; Server
            // entscheiden beim Import selbst.
            env: IndexEnv { client: "required", server: "optional" },
            downloads: vec![r.url.clone()],
            file_size: r.size,
        })
        .collect();
    files.sort_by(|a, b| a.path.cmp(&b.path));
    PackIndex {
        format_version: 1,
        game: "minecraft",
        version_id: version.to_owned(),
        name: name.to_owned(),
        summary: summary.map(str::to_owned),
        files,
        dependencies: deps,
    }
}

/// Schreibt das `.mrpack`: Index + alle übrigen Dateien unter `overrides/`.
fn write_pack(
    game_dir: &Path,
    index: &PackIndex,
    overrides: &[(PathBuf, u64)],
    dest: &Path,
    on_progress: &dyn Fn(ExportProgress),
) -> Result<u64> {
    let tmp = dest.with_extension(format!("part-{}", uuid::Uuid::new_v4().simple()));
    let result = (|| -> Result<u64> {
        let file = std::fs::File::create(&tmp).map_err(|e| Error::io(&tmp, e))?;
        let mut zip = zip::ZipWriter::new(std::io::BufWriter::new(file));
        let options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);

        let json = serde_json::to_vec_pretty(index).map_err(|e| Error::Internal(e.to_string()))?;
        zip.start_file("modrinth.index.json", options).map_err(|e| Error::Internal(e.to_string()))?;
        zip.write_all(&json).map_err(|e| Error::io(&tmp, e))?;

        for (done, (rel, _)) in overrides.iter().enumerate() {
            let source = game_dir.join(rel);
            let mut input = match std::fs::File::open(&source) {
                Ok(file) => file,
                // Datei ist zwischendurch verschwunden – das Pack bleibt trotzdem gültig.
                Err(_) => continue,
            };
            zip.start_file(format!("overrides/{}", pack_path(rel)), options)
                .map_err(|e| Error::Internal(e.to_string()))?;
            std::io::copy(&mut input, &mut zip).map_err(|e| Error::io(&source, e))?;
            if done % 8 == 0 {
                let percent = (done as f64 / overrides.len().max(1) as f64) * 100.0;
                on_progress(ExportProgress { phase: ExportPhase::Writing, percent });
            }
        }
        zip.finish().map_err(|e| Error::Internal(e.to_string()))?;
        let size = std::fs::metadata(&tmp).map(|m| m.len()).unwrap_or(0);
        std::fs::rename(&tmp, dest).map_err(|e| Error::io(dest, e))?;
        Ok(size)
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    result
}

/// SHA1 und SHA512 einer Datei in einem Durchgang.
fn hash_file(path: &Path) -> Result<(String, String)> {
    use sha1::Digest as _;
    let mut file = std::fs::File::open(path).map_err(|e| Error::io(path, e))?;
    let mut sha1 = sha1::Sha1::new();
    let mut sha512 = sha2::Sha512::new();
    let mut buf = vec![0u8; 128 * 1024];
    loop {
        let read = std::io::Read::read(&mut file, &mut buf).map_err(|e| Error::io(path, e))?;
        if read == 0 {
            break;
        }
        sha1.update(&buf[..read]);
        sha2::Digest::update(&mut sha512, &buf[..read]);
    }
    let hex = |bytes: &[u8]| -> String { bytes.iter().map(|b| format!("{b:02x}")).collect() };
    Ok((hex(&sha1.finalize()), hex(&sha2::Digest::finalize(sha512))))
}

/// Vorgeschlagener Dateiname des Packs.
pub fn suggested_file_name(name: &str, version: &str) -> String {
    let safe: String = name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | ' ') { c } else { '-' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join("-");
    let stem = if safe.is_empty() { "modpack".to_owned() } else { safe };
    format!("{stem}-{version}.mrpack").chars().take(120).collect()
}

impl Launcher {
    /// Exportiert eine Instanz als `.mrpack` nach `dest`.
    pub async fn export_modpack(
        &self,
        instance_id: &str,
        options: &ExportOptions,
        dest: &Path,
        on_progress: &ExportProgressFn,
    ) -> Result<ExportSummary> {
        let (name, version, summary) = validate_options(options)?;
        let instance = self.instances().get(instance_id).await?;
        let game_dir = self.paths().instance_game_dir(&instance.id);

        let include = options.include.clone();
        let dir = game_dir.clone();
        let files = tokio::task::spawn_blocking(move || plan_files(&dir, &include))
            .await
            .map_err(|e| Error::Internal(e.to_string()))??;
        if files.is_empty() {
            return Err(Error::validation(crate::msg!(
                "modpackExport.nothingSelected",
                "Es wurde nichts zum Exportieren ausgewählt."
            )));
        }

        // 1. Kandidaten hashen (Mods & Co.) – die können als Download ins Pack.
        on_progress(ExportProgress { phase: ExportPhase::Hashing, percent: 0.0 });
        let candidates: Vec<(PathBuf, u64)> =
            files.iter().filter(|(rel, _)| is_lookup_candidate(rel)).cloned().collect();
        let dir = game_dir.clone();
        let list = candidates.clone();
        let progress = on_progress.clone();
        let hashed: Vec<(PathBuf, u64, String, String)> = tokio::task::spawn_blocking(move || {
            let total = list.len().max(1);
            let mut out = Vec::new();
            for (done, (rel, size)) in list.into_iter().enumerate() {
                if let Ok((sha1, sha512)) = hash_file(&dir.join(&rel)) {
                    out.push((rel, size, sha1, sha512));
                }
                progress(ExportProgress {
                    phase: ExportPhase::Hashing,
                    percent: ((done + 1) as f64 / total as f64) * 100.0,
                });
            }
            out
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))?;

        // 2. Modrinth fragen, welche dieser Dateien es dort zum Download gibt.
        on_progress(ExportProgress { phase: ExportPhase::Lookup, percent: 0.0 });
        let sha1s: Vec<String> = hashed.iter().map(|(_, _, sha1, _)| sha1.clone()).collect();
        let known = match modrinth::versions_by_hashes(self.http(), &sha1s).await {
            Ok(found) => found,
            // Ohne Netz kommt eben alles in die Overrides – der Export klappt trotzdem.
            Err(e) => {
                tracing::warn!("Modrinth-Abgleich beim Export fehlgeschlagen: {e}");
                HashMap::new()
            }
        };
        let mut resolved: HashMap<String, Resolved> = HashMap::new();
        for (rel, size, sha1, sha512) in &hashed {
            let Some(version) = known.get(sha1) else { continue };
            let Some(file) = version.files.iter().find(|f| f.hashes.sha1.eq_ignore_ascii_case(sha1)) else { continue };
            if !file.url.starts_with(modrinth::CDN_PREFIX) {
                continue;
            }
            resolved.insert(pack_path(rel), Resolved {
                url: file.url.clone(),
                sha1: sha1.clone(),
                sha512: sha512.clone(),
                size: if file.size > 0 { file.size } else { *size },
            });
        }
        on_progress(ExportProgress { phase: ExportPhase::Lookup, percent: 100.0 });

        // 3. Rest als Overrides ins Archiv schreiben.
        let loader_version = match (&instance.loader.version, instance.loader.kind) {
            (None, kind) if kind != LoaderKind::Vanilla => {
                loaders::latest_stable(self.http(), kind, &instance.game_version).await.ok().flatten()
            }
            _ => None,
        };
        let index = build_index(&name, &version, summary.as_deref(), dependencies(&instance, loader_version), &resolved);
        let overrides: Vec<(PathBuf, u64)> =
            files.into_iter().filter(|(rel, _)| !resolved.contains_key(&pack_path(rel))).collect();

        on_progress(ExportProgress { phase: ExportPhase::Writing, percent: 0.0 });
        let dest = dest.to_owned();
        let downloads = index.files.len();
        let override_count = overrides.len();
        let file_name = dest.file_name().and_then(|n| n.to_str()).unwrap_or("modpack.mrpack").to_owned();
        let progress = on_progress.clone();
        let dir = game_dir.clone();
        let bytes = tokio::task::spawn_blocking(move || write_pack(&dir, &index, &overrides, &dest, &*progress))
            .await
            .map_err(|e| Error::Internal(e.to_string()))??;
        on_progress(ExportProgress { phase: ExportPhase::Writing, percent: 100.0 });

        Ok(ExportSummary { downloads, overrides: override_count, bytes, file_name })
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;

    fn game_dir_with_files(root: &Path) -> PathBuf {
        let game = root.join("minecraft");
        std::fs::create_dir_all(game.join("mods")).unwrap();
        std::fs::create_dir_all(game.join("config/sub")).unwrap();
        std::fs::create_dir_all(game.join("logs")).unwrap();
        std::fs::create_dir_all(game.join("saves/Welt")).unwrap();
        std::fs::write(game.join("mods/bekannt.jar"), b"bekannter mod").unwrap();
        std::fs::write(game.join("mods/eigen.jar"), b"selbstgebauter mod").unwrap();
        std::fs::write(game.join("mods/aus.jar.disabled"), b"deaktiviert").unwrap();
        std::fs::write(game.join("config/sub/a.toml"), b"wert=1").unwrap();
        std::fs::write(game.join("options.txt"), b"fov:80").unwrap();
        std::fs::write(game.join("logs/latest.log"), b"token=geheim").unwrap();
        std::fs::write(game.join("saves/Welt/level.dat"), b"welt").unwrap();
        game
    }

    #[test]
    fn plan_skips_never_exported_folders() {
        let dir = tempfile::tempdir().unwrap();
        let game = game_dir_with_files(dir.path());

        let files = plan_files(&game, &["mods".into(), "config".into(), "options.txt".into()]).unwrap();
        let names: Vec<String> = files.iter().map(|(p, _)| pack_path(p)).collect();
        assert!(names.contains(&"mods/bekannt.jar".to_owned()));
        assert!(names.contains(&"config/sub/a.toml".to_owned()));
        assert!(names.contains(&"options.txt".to_owned()));
        assert!(!names.iter().any(|n| n.contains("latest.log")));

        // Logs & Co. lassen sich nicht über die Auswahl hineinschmuggeln.
        assert!(plan_files(&game, &["logs".into()]).is_err());
        assert!(plan_files(&game, &["../../geheim".into()]).is_err());
        assert!(plan_files(&game, &["mods/../logs".into()]).is_err());
    }

    #[test]
    fn only_content_folders_are_looked_up() {
        assert!(is_lookup_candidate(Path::new("mods/a.jar")));
        assert!(is_lookup_candidate(Path::new("resourcepacks/pack.zip")));
        assert!(!is_lookup_candidate(Path::new("mods/sub/a.jar")));
        assert!(!is_lookup_candidate(Path::new("config/a.toml")));
        assert!(!is_lookup_candidate(Path::new("mods/a.jar.disabled")));
    }

    #[test]
    fn validates_name_and_version() {
        let base = ExportOptions { name: "Mein Pack".into(), version: "1.0.0".into(), summary: None, include: vec![] };
        assert!(validate_options(&base).is_ok());
        assert!(validate_options(&ExportOptions { name: "  ".into(), ..base.clone() }).is_err());
        assert!(validate_options(&ExportOptions { version: "1.0 beta".into(), ..base.clone() }).is_err());
        assert!(validate_options(&ExportOptions { version: String::new(), ..base.clone() }).is_err());
        assert_eq!(suggested_file_name("Mein Pack!", "1.0.0"), "Mein-Pack--1.0.0.mrpack");
    }

    /// Export → Import: das erzeugte Pack muss unser eigener Importer lesen können.
    #[test]
    fn round_trip_through_the_importer() {
        let dir = tempfile::tempdir().unwrap();
        let game = game_dir_with_files(dir.path());
        let files = plan_files(&game, &["mods".into(), "config".into(), "options.txt".into()]).unwrap();

        // „bekannt.jar“ tut so, als käme es von Modrinth.
        let (sha1, sha512) = hash_file(&game.join("mods/bekannt.jar")).unwrap();
        let mut resolved = HashMap::new();
        resolved.insert("mods/bekannt.jar".to_owned(), Resolved {
            url: "https://cdn.modrinth.com/data/AAAA/versions/BBBB/bekannt.jar".into(),
            sha1: sha1.clone(),
            sha512,
            size: 13,
        });

        let mut deps = std::collections::BTreeMap::new();
        deps.insert("minecraft".to_owned(), "1.21.1".to_owned());
        deps.insert("fabric-loader".to_owned(), "0.16.10".to_owned());
        let index = build_index("Testpack", "1.2.3", Some("Kurz"), deps, &resolved);
        assert_eq!(index.files.len(), 1);
        assert_eq!(index.files[0].hashes.sha1, sha1);

        let overrides: Vec<(PathBuf, u64)> =
            files.into_iter().filter(|(rel, _)| !resolved.contains_key(&pack_path(rel))).collect();
        let dest = dir.path().join("test.mrpack");
        let size = write_pack(&game, &index, &overrides, &dest, &|_| {}).unwrap();
        assert!(size > 0 && dest.is_file());

        // Jetzt mit dem Importer aus `modpack.rs` gegenlesen.
        let parsed = crate::modpack::read_index(&dest).unwrap();
        assert_eq!(parsed.game_version().unwrap(), "1.21.1");
        assert_eq!(parsed.loader().unwrap().version.as_deref(), Some("0.16.10"));
        let target = dir.path().join("neu");
        let tasks = crate::modpack::download_tasks(&parsed, &target).unwrap();
        assert_eq!(tasks.len(), 1);
        assert!(tasks[0].url.starts_with("https://cdn.modrinth.com/"));
        assert_eq!(tasks[0].sha1.as_deref(), Some(sha1.as_str()));

        crate::modpack::extract_overrides(&dest, &target).unwrap();
        assert_eq!(std::fs::read_to_string(target.join("options.txt")).unwrap(), "fov:80");
        assert_eq!(std::fs::read_to_string(target.join("config/sub/a.toml")).unwrap(), "wert=1");
        assert_eq!(std::fs::read_to_string(target.join("mods/eigen.jar")).unwrap(), "selbstgebauter mod");
        assert!(!target.join("mods/bekannt.jar").exists(), "bekannte Mods werden geladen, nicht kopiert");
        assert!(!target.join("saves").exists(), "Welten waren nicht ausgewählt");
    }

    #[tokio::test]
    async fn candidates_mark_recommended_and_hide_logs() {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();
        let instance = launcher
            .instances()
            .create(crate::instance::NewInstance {
                name: "Export".into(),
                game_version: "1.21.1".into(),
                loader: crate::instance::Loader::vanilla(),
            })
            .await
            .unwrap();
        let game = launcher.paths().instance_game_dir(&instance.id);
        std::fs::create_dir_all(game.join("mods")).unwrap();
        std::fs::create_dir_all(game.join("logs")).unwrap();
        std::fs::write(game.join("mods/a.jar"), b"x").unwrap();
        std::fs::write(game.join("logs/latest.log"), b"x").unwrap();

        let entries = export_candidates(launcher.paths(), &instance.id).await.unwrap();
        let names: Vec<&str> = entries.iter().map(|e| e.name.as_str()).collect();
        assert!(names.contains(&"mods"));
        assert!(!names.contains(&"logs"));
        assert!(entries.iter().find(|e| e.name == "mods").unwrap().recommended);
    }
}
