//! Inhalte einer Instanz: Mods, Ressourcenpakete, Shader. Deaktivieren
//! funktioniert wie bei anderen Launchern über die Endung `.disabled`.

use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use tokio::fs;

use crate::instance::validate_id;
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

const DISABLED_SUFFIX: &str = ".disabled";
const MAX_FILE_NAME_LEN: usize = 200;
/// Mod-Metadaten sind winzig; alles darüber ist kaputt oder böswillig.
const MAX_METADATA_BYTES: u64 = 512 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ContentKind {
    Mod,
    ResourcePack,
    ShaderPack,
}

impl ContentKind {
    pub fn dir_name(self) -> &'static str {
        match self {
            Self::Mod => "mods",
            Self::ResourcePack => "resourcepacks",
            Self::ShaderPack => "shaderpacks",
        }
    }

    fn extensions(self) -> &'static [&'static str] {
        match self {
            Self::Mod => &[".jar"],
            Self::ResourcePack | Self::ShaderPack => &[".zip"],
        }
    }

    /// So nennt Modrinth den Projekttyp.
    pub fn modrinth_type(self) -> &'static str {
        match self {
            Self::Mod => "mod",
            Self::ResourcePack => "resourcepack",
            Self::ShaderPack => "shader",
        }
    }
}

/// Woher eine Datei stammt – Grundlage für „bereits installiert“ und Updates.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Source {
    pub project_id: String,
    pub version_id: String,
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
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct ContentIndex {
    /// Schlüssel: `<ordner>/<dateiname>`.
    #[serde(default)]
    files: HashMap<String, Source>,
}

fn index_file(paths: &Paths, instance_id: &str) -> PathBuf {
    paths.instance_dir(instance_id).join("content.json")
}

fn index_key(kind: ContentKind, file_name: &str) -> String {
    format!("{}/{file_name}", kind.dir_name())
}

async fn read_index(paths: &Paths, instance_id: &str) -> ContentIndex {
    fsutil::read_json(&index_file(paths, instance_id)).await.ok().flatten().unwrap_or_default()
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

        let metadata =
            if kind == ContentKind::Mod { read_mod_metadata(entry.path()).await } else { None }.unwrap_or_default();
        items.push(ContentItem {
            source: index.files.get(&index_key(kind, &file_name)).cloned(),
            file_name,
            kind,
            enabled,
            size: meta.len(),
            title: metadata.name,
            version: metadata.version,
            description: metadata.description,
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
    fs::rename(&current, &target).await.map_err(|e| Error::io(&current, e))
}

pub async fn delete(paths: &Paths, instance_id: &str, kind: ContentKind, file_name: &str) -> Result<()> {
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
    let mut index = read_index(paths, instance_id).await;
    // Alte Version desselben Projekts vergessen (wurde beim Update ersetzt).
    index.files.retain(|_, s| s.project_id != source.project_id);
    index.files.insert(index_key(kind, file_name), source);
    fsutil::write_json(&index_file(paths, instance_id), &index).await
}

async fn forget_source(paths: &Paths, instance_id: &str, kind: ContentKind, file_name: &str) -> Result<()> {
    let mut index = read_index(paths, instance_id).await;
    if index.files.remove(&index_key(kind, file_name)).is_some() {
        fsutil::write_json(&index_file(paths, instance_id), &index).await?;
    }
    Ok(())
}

/// Dateien, die zu einem Modrinth-Projekt gehören (für Updates: alte Datei ersetzen).
pub async fn files_of_project(paths: &Paths, instance_id: &str, project_id: &str) -> Vec<(ContentKind, String)> {
    let index = read_index(paths, instance_id).await;
    index
        .files
        .iter()
        .filter(|(_, s)| s.project_id == project_id)
        .filter_map(|(key, _)| {
            let (dir, file) = key.split_once('/')?;
            let kind = [ContentKind::Mod, ContentKind::ResourcePack, ContentKind::ShaderPack]
                .into_iter()
                .find(|k| k.dir_name() == dir)?;
            Some((kind, file.to_owned()))
        })
        .collect()
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

// --- Mod-Metadaten aus dem Jar -----------------------------------------------

#[derive(Debug, Default, PartialEq, Eq)]
struct ModMetadata {
    name: Option<String>,
    version: Option<String>,
    description: Option<String>,
}

async fn read_mod_metadata(jar: PathBuf) -> Option<ModMetadata> {
    tokio::task::spawn_blocking(move || {
        let file = std::fs::File::open(&jar).ok()?;
        let mut archive = zip::ZipArchive::new(file).ok()?;
        let read = |archive: &mut zip::ZipArchive<std::fs::File>, name: &str| -> Option<String> {
            let entry = archive.by_name(name).ok()?;
            if entry.size() > MAX_METADATA_BYTES {
                return None;
            }
            let mut text = String::new();
            entry.take(MAX_METADATA_BYTES).read_to_string(&mut text).ok()?;
            Some(text)
        };
        if let Some(text) = read(&mut archive, "fabric.mod.json") {
            return parse_fabric(&text);
        }
        read(&mut archive, "quilt.mod.json").and_then(|t| parse_quilt(&t))
    })
    .await
    .ok()
    .flatten()
}

fn clean(value: Option<&serde_json::Value>, max: usize) -> Option<String> {
    let text = value?.as_str()?.trim();
    (!text.is_empty()).then(|| text.chars().filter(|c| !c.is_control()).take(max).collect())
}

fn parse_fabric(text: &str) -> Option<ModMetadata> {
    // Manche Mods haben rohe Zeilenumbrüche in Strings – strenges JSON lehnt
    // das ab, dann bleibt es eben beim Dateinamen.
    let json: serde_json::Value = serde_json::from_str(text).ok()?;
    Some(ModMetadata {
        name: clean(json.get("name"), 100),
        version: clean(json.get("version"), 50),
        description: clean(json.get("description"), 300),
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
    })
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

    fn write_jar(path: &Path, metadata_file: &str, metadata: &str) {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        zip.start_file(metadata_file, zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(metadata.as_bytes()).unwrap();
        zip.finish().unwrap();
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
            "fabric.mod.json",
            r#"{"schemaVersion":1,"id":"sodium","name":"Sodium","version":"0.6.0","description":"Schnell."}"#,
        );
        write_jar(&mods.join("unbekannt.jar.disabled"), "irgendwas.txt", "x");
        tokio::fs::write(mods.join("notiz.txt"), "kein Mod").await.unwrap();

        remember_source(&paths, "test", ContentKind::Mod, "sodium.jar", Source { project_id: "AANobbMI".into(), version_id: "v1".into() })
            .await
            .unwrap();

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
    async fn empty_when_folder_missing() {
        let (_dir, paths) = setup().await;
        assert!(list(&paths, "test", ContentKind::ShaderPack).await.unwrap().is_empty());
    }

    #[test]
    fn quilt_metadata() {
        let m = parse_quilt(r#"{"quilt_loader":{"id":"x","version":"1.2.3","metadata":{"name":"Beispiel","description":"Text"}}}"#).unwrap();
        assert_eq!(m, ModMetadata { name: Some("Beispiel".into()), version: Some("1.2.3".into()), description: Some("Text".into()) });
    }
}
