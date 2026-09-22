//! Inhalte einer Instanz: Mods, Ressourcenpakete, Shader. Deaktivieren
//! funktioniert wie bei anderen Launchern über die Endung `.disabled`.

use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::fs;
use tokio::sync::Mutex;

use crate::history::{self, HistoryEntry, HistoryKind};
use crate::icon;
use crate::instance::validate_id;
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

const DISABLED_SUFFIX: &str = ".disabled";
const MAX_FILE_NAME_LEN: usize = 200;
/// Mod-Metadaten sind winzig; alles darüber ist kaputt oder böswillig.
const MAX_METADATA_BYTES: u64 = 512 * 1024;
/// Eingebettete Icons gehen als Data-URL ans Webview – klein halten.
const MAX_EMBEDDED_ICON_BYTES: u64 = 64 * 1024;

/// Serialisiert Änderungen am Herkunfts-Index (parallele Installationen).
static INDEX_LOCK: Mutex<()> = Mutex::const_new(());

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ContentKind {
    Mod,
    ResourcePack,
    ShaderPack,
    /// Datenpakete landen wie in der Modrinth App im Instanz-Ordner
    /// datapacks/. Minecraft liest sie dort nicht selbst – sie werden beim
    /// Anlegen einer Welt ausgewählt bzw. in saves/<welt>/datapacks/
    /// kopiert, oder von Mods wie „Global Packs“/„Paxi“ global geladen.
    DataPack,
}

impl ContentKind {
    pub const ALL: [Self; 4] = [Self::Mod, Self::ResourcePack, Self::ShaderPack, Self::DataPack];

    pub fn dir_name(self) -> &'static str {
        match self {
            Self::Mod => "mods",
            Self::ResourcePack => "resourcepacks",
            Self::ShaderPack => "shaderpacks",
            Self::DataPack => "datapacks",
        }
    }

    fn extensions(self) -> &'static [&'static str] {
        match self {
            Self::Mod => &[".jar"],
            Self::ResourcePack | Self::ShaderPack | Self::DataPack => &[".zip"],
        }
    }

    /// So nennt Modrinth den Projekttyp.
    pub fn modrinth_type(self) -> &'static str {
        match self {
            Self::Mod => "mod",
            Self::ResourcePack => "resourcepack",
            Self::ShaderPack => "shader",
            Self::DataPack => "datapack",
        }
    }

    fn from_dir_name(dir: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|k| k.dir_name() == dir)
    }
}

/// Woher eine Datei stammt – Grundlage für „bereits installiert“ und Updates.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Source {
    pub project_id: String,
    pub version_id: String,
    /// Anzeige-Version laut Modrinth (z. B. `mc1.21.1-0.6.0`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version_number: Option<String>,
}

/// Zwischengespeicherte Projekt-Infos von Modrinth (Titel, Autor, Icon).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectMeta {
    pub title: String,
    #[serde(default)]
    pub slug: String,
    #[serde(default)]
    pub author: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    /// Nur `https://cdn.modrinth.com/…` (siehe [`icon::is_allowed_icon_url`]).
    #[serde(default)]
    pub icon_url: Option<String>,
    pub fetched_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ContentItem {
    /// Dateiname ohne `.disabled`.
    pub file_name: String,
    pub kind: ContentKind,
    pub enabled: bool,
    pub size: u64,
    pub title: Option<String>,
    pub version: Option<String>,
    pub description: Option<String>,
    pub source: Option<Source>,
    pub author: Option<String>,
    /// Modrinth-CDN-URL oder `data:image/png;base64,…` aus der Datei selbst.
    pub icon_url: Option<String>,
    pub slug: Option<String>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub(crate) struct ContentIndex {
    /// Schlüssel: `<ordner>/<dateiname>`.
    #[serde(default)]
    pub files: HashMap<String, Source>,
    /// Schlüssel: Modrinth-Projekt-ID.
    #[serde(default)]
    pub projects: HashMap<String, ProjectMeta>,
    /// Dateien, die Modrinth per Hash nicht kennt – Schlüssel wie `files`,
    /// Wert: Fingerabdruck (Größe + Änderungszeit), damit nicht jedes Mal neu
    /// gehasht und gefragt wird.
    #[serde(default)]
    pub unknown: HashMap<String, String>,
}

fn index_file(paths: &Paths, instance_id: &str) -> PathBuf {
    paths.instance_dir(instance_id).join("content.json")
}

pub(crate) fn index_key(kind: ContentKind, file_name: &str) -> String {
    format!("{}/{file_name}", kind.dir_name())
}

pub(crate) fn split_key(key: &str) -> Option<(ContentKind, &str)> {
    let (dir, file) = key.split_once('/')?;
    Some((ContentKind::from_dir_name(dir)?, file))
}

pub(crate) async fn read_index(paths: &Paths, instance_id: &str) -> ContentIndex {
    fsutil::read_json(&index_file(paths, instance_id)).await.ok().flatten().unwrap_or_default()
}

/// Liest, ändert und schreibt den Index unter Sperre.
pub(crate) async fn modify_index<T>(
    paths: &Paths,
    instance_id: &str,
    change: impl FnOnce(&mut ContentIndex) -> T,
) -> Result<T> {
    let _guard = INDEX_LOCK.lock().await;
    let mut index = read_index(paths, instance_id).await;
    let result = change(&mut index);
    fsutil::write_json(&index_file(paths, instance_id), &index).await?;
    Ok(result)
}

pub fn content_dir(paths: &Paths, instance_id: &str, kind: ContentKind) -> PathBuf {
    paths.instance_game_dir(instance_id).join(kind.dir_name())
}

/// Dateinamen kommen vom Frontend bzw. von Modrinth – sie dürfen den Ordner
/// nicht verlassen und müssen zur Inhaltsart passen.
pub fn validate_file_name(kind: ContentKind, file_name: &str) -> Result<()> {
    let lower = file_name.to_ascii_lowercase();
    let ok = !file_name.is_empty()
        && file_name.len() <= MAX_FILE_NAME_LEN
        && file_name.trim() == file_name
        && !file_name.starts_with('.')
        && !file_name.ends_with('.')
        && !file_name.chars().any(|c| c.is_control() || matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|'))
        && kind.extensions().iter().any(|ext| lower.ends_with(ext));
    if ok { Ok(()) } else { Err(Error::validation("Ungültiger Dateiname")) }
}

pub async fn list(paths: &Paths, instance_id: &str, kind: ContentKind) -> Result<Vec<ContentItem>> {
    validate_id(instance_id)?;
    let dir = content_dir(paths, instance_id, kind);
    let index = read_index(paths, instance_id).await;

    let mut entries = match fs::read_dir(&dir).await {
        Ok(entries) => entries,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(Error::io(&dir, e)),
    };

    let mut items = Vec::new();
    while let Some(entry) = entries.next_entry().await.map_err(|e| Error::io(&dir, e))? {
        let Some(raw_name) = entry.file_name().to_str().map(str::to_owned) else { continue };
        let Ok(meta) = entry.metadata().await else { continue };
        if !meta.is_file() {
            continue;
        }
        let (file_name, enabled) = match raw_name.strip_suffix(DISABLED_SUFFIX) {
            Some(base) => (base.to_owned(), false),
            None => (raw_name.clone(), true),
        };
        if validate_file_name(kind, &file_name).is_err() {
            continue;
        }

        let source = index.files.get(&index_key(kind, &file_name)).cloned();
        let project = source.as_ref().and_then(|s| index.projects.get(&s.project_id));
        let remote_icon = project.and_then(|p| p.icon_url.clone()).filter(|u| icon::is_allowed_icon_url(u));
        // Das eingebettete Icon nur lesen, wenn Modrinth keins liefert.
        let local = match kind {
            ContentKind::Mod => read_mod_metadata(entry.path(), remote_icon.is_none()).await,
            ContentKind::ResourcePack | ContentKind::DataPack if remote_icon.is_none() => read_pack_icon(entry.path()).await,
            _ => None,
        }
        .unwrap_or_default();

        items.push(ContentItem {
            title: local.name.or_else(|| project.map(|p| p.title.clone())),
            version: local.version.or_else(|| source.as_ref().and_then(|s| s.version_number.clone())),
            description: local.description.or_else(|| project.and_then(|p| p.description.clone())),
            author: project.and_then(|p| p.author.clone()).or(local.author),
            icon_url: remote_icon.or(local.icon),
            slug: project.map(|p| p.slug.clone()).filter(|s| !s.is_empty()),
            source,
            file_name,
            kind,
            enabled,
            size: meta.len(),
        });
    }

    items.sort_by_key(|i| i.title.clone().unwrap_or_else(|| i.file_name.clone()).to_lowercase());
    Ok(items)
}

fn existing_path(dir: &Path, file_name: &str) -> Option<(PathBuf, bool)> {
    let enabled = dir.join(file_name);
    let disabled = dir.join(format!("{file_name}{DISABLED_SUFFIX}"));
    if enabled.is_file() {
        Some((enabled, true))
    } else if disabled.is_file() {
        Some((disabled, false))
    } else {
        None
    }
}

/// Pfad der Datei, egal ob aktiviert oder deaktiviert.
pub fn existing_file(paths: &Paths, instance_id: &str, kind: ContentKind, file_name: &str) -> Option<PathBuf> {
    validate_file_name(kind, file_name).ok()?;
    existing_path(&content_dir(paths, instance_id, kind), file_name).map(|(path, _)| path)
}

/// Name für den Verlauf: Modrinth-Titel, sonst der Dateiname.
pub(crate) async fn display_name(paths: &Paths, instance_id: &str, kind: ContentKind, file_name: &str) -> String {
    let index = read_index(paths, instance_id).await;
    index
        .files
        .get(&index_key(kind, file_name))
        .and_then(|s| index.projects.get(&s.project_id))
        .map(|p| p.title.clone())
        .unwrap_or_else(|| file_name.to_owned())
}

pub async fn set_enabled(
    paths: &Paths,
    instance_id: &str,
    kind: ContentKind,
    file_name: &str,
    enabled: bool,
) -> Result<()> {
    validate_id(instance_id)?;
    validate_file_name(kind, file_name)?;
    let dir = content_dir(paths, instance_id, kind);
    let (current, is_enabled) =
        existing_path(&dir, file_name).ok_or_else(|| Error::validation("Die Datei existiert nicht mehr."))?;
    if is_enabled == enabled {
        return Ok(());
    }
    let target =
        if enabled { dir.join(file_name) } else { dir.join(format!("{file_name}{DISABLED_SUFFIX}")) };
    fs::rename(&current, &target).await.map_err(|e| Error::io(&current, e))?;

    let name = display_name(paths, instance_id, kind, file_name).await;
    let kind_entry = if enabled { HistoryKind::ModEnabled } else { HistoryKind::ModDisabled };
    history::record(paths, instance_id, HistoryEntry::new(kind_entry).subject(name)).await;
    Ok(())
}

/// Löscht eine Datei auf Wunsch des Nutzers (mit Verlaufseintrag).
pub async fn delete(paths: &Paths, instance_id: &str, kind: ContentKind, file_name: &str) -> Result<()> {
    validate_id(instance_id)?;
    validate_file_name(kind, file_name)?;
    let name = display_name(paths, instance_id, kind, file_name).await;
    remove_file(paths, instance_id, kind, file_name).await?;
    history::record(paths, instance_id, HistoryEntry::new(HistoryKind::ModRemoved).subject(name)).await;
    Ok(())
}

/// Entfernt Datei und Index-Eintrag ohne Verlaufseintrag (z. B. beim Update).
pub(crate) async fn remove_file(paths: &Paths, instance_id: &str, kind: ContentKind, file_name: &str) -> Result<()> {
    validate_id(instance_id)?;
    validate_file_name(kind, file_name)?;
    let dir = content_dir(paths, instance_id, kind);
    if let Some((path, _)) = existing_path(&dir, file_name) {
        fs::remove_file(&path).await.map_err(|e| Error::io(&path, e))?;
    }
    forget_source(paths, instance_id, kind, file_name).await
}

pub async fn remember_source(
    paths: &Paths,
    instance_id: &str,
    kind: ContentKind,
    file_name: &str,
    source: Source,
) -> Result<()> {
    let key = index_key(kind, file_name);
    modify_index(paths, instance_id, |index| {
        // Alte Version desselben Projekts vergessen (wurde beim Update ersetzt).
        index.files.retain(|_, s| s.project_id != source.project_id);
        index.unknown.remove(&key);
        index.files.insert(key, source);
    })
    .await
}

async fn forget_source(paths: &Paths, instance_id: &str, kind: ContentKind, file_name: &str) -> Result<()> {
    let key = index_key(kind, file_name);
    let index = read_index(paths, instance_id).await;
    if !index.files.contains_key(&key) && !index.unknown.contains_key(&key) {
        return Ok(());
    }
    modify_index(paths, instance_id, |index| {
        index.files.remove(&key);
        index.unknown.remove(&key);
    })
    .await
}

/// Dateien, die zu einem Modrinth-Projekt gehören (für Updates: alte Datei ersetzen).
pub async fn files_of_project(paths: &Paths, instance_id: &str, project_id: &str) -> Vec<(ContentKind, String)> {
    let index = read_index(paths, instance_id).await;
    index
        .files
        .iter()
        .filter(|(_, s)| s.project_id == project_id)
        .filter_map(|(key, _)| split_key(key).map(|(kind, file)| (kind, file.to_owned())))
        .collect()
}

/// Installierte Quelle eines Projekts (für „Update von … auf …“).
pub(crate) async fn source_of_project(paths: &Paths, instance_id: &str, project_id: &str) -> Option<Source> {
    read_index(paths, instance_id).await.files.into_values().find(|s| s.project_id == project_id)
}

pub async fn installed_project_ids(paths: &Paths, instance_id: &str) -> Result<Vec<String>> {
    validate_id(instance_id)?;
    let index = read_index(paths, instance_id).await;
    let mut ids = Vec::new();
    for (key, source) in &index.files {
        let Some((dir, file)) = key.split_once('/') else { continue };
        // Von Hand gelöschte Dateien zählen nicht mehr als installiert.
        if existing_path(&paths.instance_game_dir(instance_id).join(dir), file).is_some() {
            ids.push(source.project_id.clone());
        }
    }
    Ok(ids)
}

/// Fingerabdruck einer Datei, um unveränderte „unbekannte“ Dateien nicht
/// ständig neu zu hashen.
pub(crate) fn fingerprint(meta: &std::fs::Metadata) -> String {
    let modified = meta.modified().ok().map(|t| DateTime::<Utc>::from(t).timestamp_millis()).unwrap_or_default();
    format!("{}:{modified}", meta.len())
}

// --- Mod-Metadaten aus dem Jar -----------------------------------------------

#[derive(Debug, Default, PartialEq, Eq)]
struct ModMetadata {
    name: Option<String>,
    version: Option<String>,
    description: Option<String>,
    author: Option<String>,
    /// Pfad des Icons im Archiv (vor dem Einlesen).
    icon_path: Option<String>,
    /// Fertige Data-URL.
    icon: Option<String>,
}

fn read_entry_text(archive: &mut zip::ZipArchive<std::fs::File>, name: &str) -> Option<String> {
    let entry = archive.by_name(name).ok()?;
    if entry.size() > MAX_METADATA_BYTES {
        return None;
    }
    let mut text = String::new();
    entry.take(MAX_METADATA_BYTES).read_to_string(&mut text).ok()?;
    Some(text)
}

/// Liest ein PNG aus dem Archiv als Data-URL – nur PNG, nur klein.
fn read_png_data_url(archive: &mut zip::ZipArchive<std::fs::File>, name: &str) -> Option<String> {
    let name = name.trim_start_matches('/');
    if name.is_empty() || name.contains("..") || !name.to_ascii_lowercase().ends_with(".png") {
        return None;
    }
    let entry = archive.by_name(name).ok()?;
    if entry.size() > MAX_EMBEDDED_ICON_BYTES {
        return None;
    }
    let mut bytes = Vec::new();
    entry.take(MAX_EMBEDDED_ICON_BYTES).read_to_end(&mut bytes).ok()?;
    (icon::sniff(&bytes) == Some(icon::ImageFormat::Png))
        .then(|| format!("data:image/png;base64,{}", STANDARD.encode(&bytes)))
}

async fn read_mod_metadata(jar: PathBuf, want_icon: bool) -> Option<ModMetadata> {
    tokio::task::spawn_blocking(move || {
        let file = std::fs::File::open(&jar).ok()?;
        let mut archive = zip::ZipArchive::new(file).ok()?;
        let mut meta = if let Some(text) = read_entry_text(&mut archive, "fabric.mod.json") {
            parse_fabric(&text)?
        } else if let Some(text) = read_entry_text(&mut archive, "quilt.mod.json") {
            parse_quilt(&text)?
        } else {
            let text = read_entry_text(&mut archive, "META-INF/neoforge.mods.toml")
                .or_else(|| read_entry_text(&mut archive, "META-INF/mods.toml"))?;
            parse_mods_toml(&text)
        };
        if want_icon && let Some(path) = meta.icon_path.take() {
            meta.icon = read_png_data_url(&mut archive, &path);
        }
        Some(meta)
    })
    .await
    .ok()
    .flatten()
}

async fn read_pack_icon(zip_path: PathBuf) -> Option<ModMetadata> {
    tokio::task::spawn_blocking(move || {
        let file = std::fs::File::open(&zip_path).ok()?;
        let mut archive = zip::ZipArchive::new(file).ok()?;
        Some(ModMetadata { icon: read_png_data_url(&mut archive, "pack.png"), ..Default::default() })
    })
    .await
    .ok()
    .flatten()
}

fn clean(value: Option<&serde_json::Value>, max: usize) -> Option<String> {
    clean_str(value?.as_str()?, max)
}

fn clean_str(text: &str, max: usize) -> Option<String> {
    let text = text.trim();
    (!text.is_empty()).then(|| text.chars().filter(|c| !c.is_control()).take(max).collect())
}

/// `icon` ist ein Pfad oder ein Objekt `{ "16": "...", "128": "..." }` – dann
/// das größte bis 256 px.
fn icon_from_json(value: Option<&serde_json::Value>) -> Option<String> {
    match value? {
        serde_json::Value::String(s) => clean_str(s, 200),
        serde_json::Value::Object(map) => map
            .iter()
            .filter_map(|(size, path)| Some((size.parse::<u32>().ok()?, path.as_str()?)))
            .filter(|(size, _)| *size <= 256)
            .max_by_key(|(size, _)| *size)
            .and_then(|(_, path)| clean_str(path, 200)),
        _ => None,
    }
}

fn first_author(value: Option<&serde_json::Value>) -> Option<String> {
    match value?.as_array()?.first()? {
        serde_json::Value::String(s) => clean_str(s, 60),
        serde_json::Value::Object(o) => clean(o.get("name"), 60),
        _ => None,
    }
}

fn parse_fabric(text: &str) -> Option<ModMetadata> {
    // Manche Mods haben rohe Zeilenumbrüche in Strings – strenges JSON lehnt
    // das ab, dann bleibt es eben beim Dateinamen.
    let json: serde_json::Value = serde_json::from_str(text).ok()?;
    Some(ModMetadata {
        name: clean(json.get("name"), 100),
        version: clean(json.get("version"), 50),
        description: clean(json.get("description"), 300),
        author: first_author(json.get("authors")),
        icon_path: icon_from_json(json.get("icon")),
        icon: None,
    })
}

fn parse_quilt(text: &str) -> Option<ModMetadata> {
    let json: serde_json::Value = serde_json::from_str(text).ok()?;
    let loader = json.get("quilt_loader")?;
    let meta = loader.get("metadata");
    Some(ModMetadata {
        name: clean(meta.and_then(|m| m.get("name")), 100),
        version: clean(loader.get("version"), 50),
        description: clean(meta.and_then(|m| m.get("description")), 300),
        author: meta.and_then(|m| m.get("contributors")).and_then(|c| c.as_object()).and_then(|c| {
            c.keys().next().and_then(|k| clean_str(k, 60))
        }),
        icon_path: icon_from_json(meta.and_then(|m| m.get("icon"))),
        icon: None,
    })
}

/// Minimaler Leser für (Neo)Forge-`mods.toml`: nur einfache `key = "wert"`-Zeilen
/// des ersten `[[mods]]`-Blocks plus `logoFile` auf oberster Ebene.
fn parse_mods_toml(text: &str) -> ModMetadata {
    let mut meta = ModMetadata::default();
    let mut section = "";
    let mut seen_mods = false;
    let mut lines = text.lines();
    while let Some(line) = lines.next() {
        let line = line.trim();
        if line.starts_with('[') {
            section = line;
            if line == "[[mods]]" {
                if seen_mods {
                    break;
                }
                seen_mods = true;
            }
            continue;
        }
        let Some((key, value)) = line.split_once('=') else { continue };
        let (key, value) = (key.trim(), value.trim());
        let value = if let Some(rest) = value.strip_prefix("'''").or_else(|| value.strip_prefix("\"\"\"")) {
            // Mehrzeiliger String: bis zum Ende einsammeln.
            let mut out = rest.to_owned();
            if !(rest.ends_with("'''") || rest.ends_with("\"\"\"")) {
                for next in lines.by_ref() {
                    out.push(' ');
                    out.push_str(next.trim());
                    if next.contains("'''") || next.contains("\"\"\"") {
                        break;
                    }
                }
            }
            out.replace("'''", "").replace("\"\"\"", "")
        } else {
            let unquoted = value.split('#').next().unwrap_or("").trim();
            unquoted.trim_matches('"').trim_matches('\'').to_owned()
        };
        // Platzhalter wie `${file.jarVersion}` sind keine brauchbare Anzeige.
        if value.contains("${") {
            continue;
        }
        let in_mods = section == "[[mods]]";
        match key {
            "displayName" if in_mods => meta.name = clean_str(&value, 100),
            "version" if in_mods => meta.version = clean_str(&value, 50),
            "description" if in_mods => meta.description = clean_str(&value, 300),
            "authors" if in_mods => meta.author = clean_str(value.split(',').next().unwrap_or(""), 60),
            "logoFile" if in_mods || section.is_empty() => meta.icon_path = clean_str(&value, 200),
            _ => {}
        }
    }
    meta
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;

    async fn setup() -> (tempfile::TempDir, Paths) {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        fsutil::ensure_dir(&content_dir(&paths, "test", ContentKind::Mod)).await.unwrap();
        (dir, paths)
    }

    fn write_jar(path: &Path, files: &[(&str, &[u8])]) {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        for (name, data) in files {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(data).unwrap();
        }
        zip.finish().unwrap();
    }

    fn source(project: &str, version: &str) -> Source {
        Source { project_id: project.into(), version_id: version.into(), version_number: None }
    }

    #[test]
    fn file_name_validation() {
        assert!(validate_file_name(ContentKind::Mod, "sodium-fabric-0.6.0+mc1.21.1.jar").is_ok());
        assert!(validate_file_name(ContentKind::ResourcePack, "Faithful 32x.zip").is_ok());
        for bad in ["", "../evil.jar", "a/b.jar", "a\\b.jar", "c:evil.jar", ".hidden.jar", "mod.exe", "mod.jar.", "x.zip"] {
            assert!(validate_file_name(ContentKind::Mod, bad).is_err(), "{bad:?}");
        }
        assert!(validate_file_name(ContentKind::ShaderPack, "shader.jar").is_err());
    }

    #[tokio::test]
    async fn list_toggle_delete() {
        let (_dir, paths) = setup().await;
        let mods = content_dir(&paths, "test", ContentKind::Mod);
        write_jar(
            &mods.join("sodium.jar"),
            &[(
                "fabric.mod.json",
                br#"{"schemaVersion":1,"id":"sodium","name":"Sodium","version":"0.6.0","description":"Schnell."}"#,
            )],
        );
        write_jar(&mods.join("unbekannt.jar.disabled"), &[("irgendwas.txt", b"x")]);
        tokio::fs::write(mods.join("notiz.txt"), "kein Mod").await.unwrap();

        remember_source(&paths, "test", ContentKind::Mod, "sodium.jar", source("AANobbMI", "v1")).await.unwrap();

        let items = list(&paths, "test", ContentKind::Mod).await.unwrap();
        assert_eq!(items.len(), 2);
        let sodium = items.iter().find(|i| i.file_name == "sodium.jar").unwrap();
        assert_eq!((sodium.title.as_deref(), sodium.version.as_deref(), sodium.enabled), (Some("Sodium"), Some("0.6.0"), true));
        assert_eq!(sodium.source.as_ref().unwrap().project_id, "AANobbMI");
        let other = items.iter().find(|i| i.file_name == "unbekannt.jar").unwrap();
        assert!(!other.enabled && other.title.is_none());

        set_enabled(&paths, "test", ContentKind::Mod, "sodium.jar", false).await.unwrap();
        assert!(mods.join("sodium.jar.disabled").is_file() && !mods.join("sodium.jar").exists());
        // Deaktiviert zählt weiter als installiert.
        assert_eq!(installed_project_ids(&paths, "test").await.unwrap(), ["AANobbMI"]);
        set_enabled(&paths, "test", ContentKind::Mod, "sodium.jar", true).await.unwrap();
        assert!(mods.join("sodium.jar").is_file());

        delete(&paths, "test", ContentKind::Mod, "sodium.jar").await.unwrap();
        assert!(!mods.join("sodium.jar").exists());
        assert!(installed_project_ids(&paths, "test").await.unwrap().is_empty());

        assert!(set_enabled(&paths, "test", ContentKind::Mod, "../../settings.jar", false).await.is_err());
        assert!(delete(&paths, "../x", ContentKind::Mod, "a.jar").await.is_err());
    }

    #[tokio::test]
    async fn uses_cached_project_meta_and_embedded_icons() {
        let (_dir, paths) = setup().await;
        let mods = content_dir(&paths, "test", ContentKind::Mod);
        let png: &[u8] = b"\x89PNG\r\n\x1a\nrest";
        write_jar(
            &mods.join("local.jar"),
            &[
                ("fabric.mod.json", br#"{"id":"l","name":"Lokal","icon":{"16":"a.png","128":"assets/l/icon.png"},"authors":["Ich"]}"#),
                ("assets/l/icon.png", png),
            ],
        );
        write_jar(&mods.join("fake-icon.jar"), &[("fabric.mod.json", br#"{"id":"f","icon":"x.png"}"#), ("x.png", b"<svg/>")]);
        write_jar(&mods.join("remote.jar"), &[("fabric.mod.json", br#"{"id":"r","icon":"i.png"}"#), ("i.png", png)]);
        remember_source(&paths, "test", ContentKind::Mod, "remote.jar", source("P7dR8mSH", "v2")).await.unwrap();
        modify_index(&paths, "test", |index| {
            index.projects.insert(
                "P7dR8mSH".into(),
                ProjectMeta {
                    title: "Fabric API".into(),
                    slug: "fabric-api".into(),
                    author: Some("modmuss50".into()),
                    description: None,
                    icon_url: Some("https://cdn.modrinth.com/data/P7dR8mSH/icon.png".into()),
                    fetched_at: Utc::now(),
                },
            );
        })
        .await
        .unwrap();

        let items = list(&paths, "test", ContentKind::Mod).await.unwrap();
        let local = items.iter().find(|i| i.file_name == "local.jar").unwrap();
        assert!(local.icon_url.as_deref().unwrap().starts_with("data:image/png;base64,"));
        assert_eq!(local.author.as_deref(), Some("Ich"));
        let fake = items.iter().find(|i| i.file_name == "fake-icon.jar").unwrap();
        assert!(fake.icon_url.is_none(), "nur echte PNGs werden eingebettet");
        let remote = items.iter().find(|i| i.file_name == "remote.jar").unwrap();
        assert_eq!(remote.title.as_deref(), Some("Fabric API"));
        assert_eq!(remote.icon_url.as_deref(), Some("https://cdn.modrinth.com/data/P7dR8mSH/icon.png"));
        assert_eq!(remote.slug.as_deref(), Some("fabric-api"));
    }

    #[tokio::test]
    async fn empty_when_folder_missing() {
        let (_dir, paths) = setup().await;
        assert!(list(&paths, "test", ContentKind::ShaderPack).await.unwrap().is_empty());
    }

    #[test]
    fn quilt_metadata() {
        let m = parse_quilt(r#"{"quilt_loader":{"id":"x","version":"1.2.3","metadata":{"name":"Beispiel","description":"Text"}}}"#).unwrap();
        assert_eq!(m.name.as_deref(), Some("Beispiel"));
        assert_eq!(m.version.as_deref(), Some("1.2.3"));
        assert_eq!(m.description.as_deref(), Some("Text"));
    }

    #[test]
    fn mods_toml_metadata() {
        let m = parse_mods_toml(
            "modLoader=\"javafml\"\nlogoFile=\"logo.png\" # Kommentar\n[[mods]]\nmodId=\"jei\"\nversion=\"${file.jarVersion}\"\n\
             displayName=\"Just Enough Items\"\nauthors=\"mezz, Nobody\"\ndescription='''\nZeigt Rezepte\nan.\n'''\n\
             [[dependencies.jei]]\nmodId=\"forge\"\n[[mods]]\ndisplayName=\"Zweiter\"\n",
        );
        assert_eq!(m.name.as_deref(), Some("Just Enough Items"));
        assert_eq!(m.version, None, "Platzhalter werden ignoriert");
        assert_eq!(m.author.as_deref(), Some("mezz"));
        assert_eq!(m.description.as_deref(), Some("Zeigt Rezepte an."));
        assert_eq!(m.icon_path.as_deref(), Some("logo.png"));
    }
}
