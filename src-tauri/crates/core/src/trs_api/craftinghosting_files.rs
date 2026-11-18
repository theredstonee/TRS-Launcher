//! Dateien, alle Server-Einstellungen (server.properties) und Plugins/Mods des Partner-Servers
//! (Vertrag: `docs/craftinghosting.md`, letzter Abschnitt). Änderungen gelten ab dem nächsten Start.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::json;

use super::craftinghosting::BASE;
use super::ops::encode_query;
use super::{Req, validate};
use crate::{Error, Launcher, Result};

pub const PATH_MAX: usize = 512;
/// Größter Text, den der Editor speichert.
pub const CONTENT_MAX: usize = 1024 * 1024;
pub const PROPERTY_KEY_MAX: usize = 64;
pub const PROPERTY_VALUE_MAX: usize = 500;
pub const DELETE_MAX: usize = 50;
const ENTRIES_MAX: usize = 5000;
const PROPERTIES_MAX: usize = 500;
const NAME_MAX: usize = 255;
/// JSON mit 1 MB Text kann durch Escapes deutlich größer werden.
const CONTENT_RESPONSE_LIMIT: usize = 8 * 1024 * 1024;

fn invalid_path() -> Error {
    Error::validation(crate::msg!("craftinghosting.invalidPath", "Ungültiger Pfad auf dem Server."))
}

fn invalid_property() -> Error {
    Error::validation(crate::msg!(
        "craftinghosting.invalidProperty",
        "Einstellung: Schlüssel aus a–z, 0–9, . und -, Wert höchstens 500 Zeichen in einer Zeile."
    ))
}

/// Pfad im Server: absolut, ohne `..`/`.`, ohne `\` und Steuerzeichen, höchstens 512 Zeichen.
/// Ein `/` am Ende fällt weg (außer bei `/`).
pub fn server_path(input: &str) -> Result<String> {
    let ok = input.starts_with('/')
        && input.chars().count() <= PATH_MAX
        && !input.contains('\\')
        && !input.chars().any(|c| c.is_control())
        && input.trim() == input;
    if !ok {
        return Err(invalid_path());
    }
    let trimmed = input.trim_end_matches('/');
    if trimmed.is_empty() {
        return Ok("/".into());
    }
    let bad_segment = trimmed[1..].split('/').any(|s| s.is_empty() || s == "." || s == ".." || s.trim() != s);
    if bad_segment { Err(invalid_path()) } else { Ok(trimmed.to_owned()) }
}

/// Wie [`server_path`], aber nicht die Wurzel selbst (Löschen, Ordner, Speichern).
fn target_path(input: &str) -> Result<String> {
    let path = server_path(input)?;
    if path == "/" { Err(invalid_path()) } else { Ok(path) }
}

/// Einzelner Datei-/Ordnername (kein `/`, `\`, `.`/`..`, keine Steuerzeichen).
fn file_name(input: &str) -> bool {
    !input.is_empty()
        && input.chars().count() <= NAME_MAX
        && input != "."
        && input != ".."
        && !input.contains(['/', '\\'])
        && !input.chars().any(|c| c.is_control())
}

fn join(dir: &str, name: &str) -> String {
    if dir == "/" { format!("/{name}") } else { format!("{dir}/{name}") }
}

pub fn property_key(key: &str) -> bool {
    (1..=PROPERTY_KEY_MAX).contains(&key.len())
        && key.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'.' | b'-'))
}

fn property_value(value: &str) -> bool {
    value.chars().count() <= PROPERTY_VALUE_MAX && !value.chars().any(|c| c.is_control())
}

/// Geänderte Einstellungen für PATCH (mindestens eine).
pub fn properties_patch(input: &BTreeMap<String, String>) -> Result<serde_json::Value> {
    if input.is_empty() || input.len() > PROPERTIES_MAX {
        return Err(invalid_property());
    }
    let mut body = serde_json::Map::new();
    for (key, value) in input {
        if !property_key(key) || !property_value(value) {
            return Err(invalid_property());
        }
        body.insert(key.clone(), value.clone().into());
    }
    Ok(json!({ "properties": body }))
}

#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn amount(v: Option<f64>) -> u64 {
    v.filter(|n| n.is_finite() && *n >= 0.0).map_or(0, |n| n as u64)
}

fn time(value: Option<String>) -> Option<String> {
    value.map(|t| validate::text(t.trim(), 40)).filter(|t| !t.is_empty())
}

// --- Ansichten --------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    /// `file` oder `directory`.
    #[serde(rename = "type")]
    pub kind: String,
    pub size: u64,
    pub modified_at: Option<String>,
    /// Textdatei, die der Editor öffnen darf.
    pub editable: bool,
    /// Von TRS verwaltet: nicht bearbeiten, nicht löschen.
    pub protected: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct DirListing {
    pub dir: String,
    pub entries: Vec<FileEntry>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct FileContent {
    pub path: String,
    pub content: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SavedFile {
    pub path: String,
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Properties {
    pub properties: BTreeMap<String, String>,
    pub locked: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Addon {
    pub file: String,
    pub size: u64,
    pub modified_at: Option<String>,
    pub protected: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Addons {
    /// `plugins`, `mods` oder `None` (Vanilla).
    pub kind: Option<String>,
    pub dir: String,
    pub items: Vec<Addon>,
}

// --- Draht ------------------------------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiEntry {
    name: String,
    path: String,
    #[serde(rename = "type")]
    kind: String,
    size: Option<f64>,
    modified_at: Option<String>,
    editable: bool,
    protected: bool,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiListing {
    dir: String,
    entries: Vec<ApiEntry>,
}

impl ApiListing {
    fn cleaned(self, asked: &str) -> DirListing {
        let dir = server_path(&self.dir).unwrap_or_else(|_| asked.to_owned());
        let entries = self
            .entries
            .into_iter()
            .filter(|e| file_name(&e.name) && matches!(e.kind.as_str(), "file" | "directory"))
            .map(|e| {
                let path = server_path(&e.path).ok().filter(|p| p != "/").unwrap_or_else(|| join(&dir, &e.name));
                let directory = e.kind == "directory";
                FileEntry {
                    name: e.name,
                    path,
                    size: amount(e.size),
                    modified_at: time(e.modified_at),
                    editable: e.editable && !directory && !e.protected,
                    protected: e.protected,
                    kind: e.kind,
                }
            })
            .take(ENTRIES_MAX)
            .collect();
        DirListing { dir, entries }
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiContent {
    content: String,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiSaved {
    path: String,
    size: Option<f64>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiProperties {
    properties: BTreeMap<String, serde_json::Value>,
    locked: Vec<String>,
}

impl ApiProperties {
    fn cleaned(self) -> Properties {
        let properties = self
            .properties
            .into_iter()
            .filter(|(k, _)| property_key(k))
            .filter_map(|(k, v)| {
                let value = match v {
                    serde_json::Value::String(s) => s,
                    serde_json::Value::Number(n) => n.to_string(),
                    serde_json::Value::Bool(b) => b.to_string(),
                    serde_json::Value::Null => String::new(),
                    _ => return None,
                };
                Some((k, validate::text(&value, PROPERTY_VALUE_MAX)))
            })
            .take(PROPERTIES_MAX)
            .collect();
        let mut locked: Vec<String> = self.locked.into_iter().filter(|k| property_key(k)).take(PROPERTIES_MAX).collect();
        locked.sort();
        locked.dedup();
        Properties { properties, locked }
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiAddon {
    file: String,
    size: Option<f64>,
    modified_at: Option<String>,
    protected: bool,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiAddons {
    kind: Option<String>,
    dir: String,
    items: Vec<ApiAddon>,
}

impl ApiAddons {
    fn cleaned(self) -> Addons {
        let kind = self.kind.filter(|k| matches!(k.as_str(), "plugins" | "mods"));
        let fallback = kind.as_deref().map_or("/".to_owned(), |k| format!("/{k}"));
        let dir = server_path(&self.dir).unwrap_or(fallback);
        let items = if kind.is_none() {
            Vec::new()
        } else {
            self.items
                .into_iter()
                .filter(|a| file_name(&a.file))
                .map(|a| Addon { size: amount(a.size), modified_at: time(a.modified_at), protected: a.protected, file: a.file })
                .take(ENTRIES_MAX)
                .collect()
        };
        Addons { kind, dir, items }
    }
}

// --- Operationen ------------------------------------------------------------------------

impl Launcher {
    /// Inhalt eines Ordners auf dem Server.
    pub async fn craftinghosting_files(&self, dir: &str) -> Result<DirListing> {
        let dir = server_path(dir)?;
        let (_, bytes) = self.ch_send(Req::get(format!("{BASE}/server/files?dir={}", encode_query(&dir)))).await?;
        let raw: ApiListing = super::parse(&bytes, "craftinghosting/files")?;
        Ok(raw.cleaned(&dir))
    }

    /// Textdatei lesen (`file_not_text` / `file_too_large` kommen von der API).
    pub async fn craftinghosting_file(&self, path: &str) -> Result<FileContent> {
        let path = target_path(path)?;
        let mut req = Req::get(format!("{BASE}/server/files/content?path={}", encode_query(&path)));
        req.limit = CONTENT_RESPONSE_LIMIT;
        let (_, bytes) = self.ch_send(req).await?;
        let raw: ApiContent = super::parse(&bytes, "craftinghosting/files/content")?;
        if raw.content.len() > CONTENT_MAX {
            return Err(file_too_large());
        }
        Ok(FileContent { path, content: raw.content })
    }

    /// Textdatei speichern (höchstens 1 MB). `server.properties` bearbeitet die API nach.
    pub async fn craftinghosting_save_file(&self, path: &str, content: &str) -> Result<SavedFile> {
        let path = target_path(path)?;
        if content.len() > CONTENT_MAX {
            return Err(file_too_large());
        }
        let req = Req::put(format!("{BASE}/server/files/content?path={}", encode_query(&path)), json!({ "content": content }));
        let (_, bytes) = self.ch_send(req).await?;
        let raw: ApiSaved = super::parse(&bytes, "craftinghosting/files/content")?;
        let size = raw.size.map_or(content.len() as u64, |s| amount(Some(s)));
        Ok(SavedFile { path: server_path(&raw.path).unwrap_or(path), size })
    }

    pub async fn craftinghosting_delete_files(&self, paths: &[String]) -> Result<()> {
        if paths.is_empty() || paths.len() > DELETE_MAX {
            return Err(invalid_path());
        }
        let mut clean: Vec<String> = Vec::with_capacity(paths.len());
        for p in paths {
            let p = target_path(p)?;
            if !clean.contains(&p) {
                clean.push(p);
            }
        }
        self.ch_send(Req::post(format!("{BASE}/server/files/delete"), json!({ "paths": clean }))).await.map(|_| ())
    }

    pub async fn craftinghosting_create_folder(&self, path: &str) -> Result<()> {
        let path = target_path(path)?;
        self.ch_send(Req::post(format!("{BASE}/server/files/folder"), json!({ "path": path }))).await.map(|_| ())
    }

    /// Alle Schlüssel aus server.properties (als Text) und die gesperrten.
    pub async fn craftinghosting_properties(&self) -> Result<Properties> {
        let (_, bytes) = self.ch_send(Req::get(format!("{BASE}/server/properties"))).await?;
        let raw: ApiProperties = super::parse(&bytes, "craftinghosting/properties")?;
        Ok(raw.cleaned())
    }

    /// Nur geänderte Schlüssel; gesperrte lehnt die API mit `property_locked` ab.
    pub async fn craftinghosting_save_properties(&self, changes: &BTreeMap<String, String>) -> Result<Properties> {
        let body = properties_patch(changes)?;
        let (_, bytes) = self.ch_send(Req::patch(format!("{BASE}/server/properties"), body)).await?;
        let raw: ApiProperties = super::parse(&bytes, "craftinghosting/properties")?;
        Ok(raw.cleaned())
    }

    /// Installierte Plugins bzw. Mods (Vanilla: `kind = None`).
    pub async fn craftinghosting_addons(&self) -> Result<Addons> {
        let (_, bytes) = self.ch_send(Req::get(format!("{BASE}/server/addons"))).await?;
        let raw: ApiAddons = super::parse(&bytes, "craftinghosting/addons")?;
        Ok(raw.cleaned())
    }

    pub async fn craftinghosting_remove_addon(&self, file: &str) -> Result<()> {
        if !file_name(file) {
            return Err(invalid_path());
        }
        self.ch_send(Req::delete(format!("{BASE}/server/addons?file={}", encode_query(file)))).await.map(|_| ())
    }
}

fn file_too_large() -> Error {
    Error::validation(crate::msg!("craftinghosting.fileTooLarge", "Die Datei ist zu groß (höchstens 1 MB)."))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths() {
        assert_eq!(server_path("/").unwrap(), "/");
        assert_eq!(server_path("/plugins/").unwrap(), "/plugins");
        assert_eq!(server_path("/plugins/Foo/config.yml").unwrap(), "/plugins/Foo/config.yml");
        for bad in ["", "plugins", "/../etc", "/a/../b", "/a/./b", "/a//b", "\\a", "/a\\b", "/a\nb", " /a", "/a /b"] {
            assert!(server_path(bad).is_err(), "{bad:?}");
        }
        assert!(server_path(&format!("/{}", "a".repeat(511))).is_ok());
        assert!(server_path(&format!("/{}", "a".repeat(512))).is_err());
        assert!(target_path("/").is_err());
        assert!(target_path("//").is_err());
    }

    #[test]
    fn properties() {
        assert!(property_key("server-port"));
        assert!(property_key("query.port"));
        for bad in ["", "Motd", "a b", "a_b", "x=y", &"a".repeat(65)] {
            assert!(!property_key(bad), "{bad:?}");
        }
        let mut ok = BTreeMap::new();
        ok.insert("pvp".to_owned(), "false".to_owned());
        assert_eq!(properties_patch(&ok).unwrap(), json!({ "properties": { "pvp": "false" } }));
        assert!(properties_patch(&BTreeMap::new()).is_err());
        let mut long = BTreeMap::new();
        long.insert("motd".to_owned(), "x".repeat(501));
        assert!(properties_patch(&long).is_err());
        let mut multi = BTreeMap::new();
        multi.insert("motd".to_owned(), "a\nb".to_owned());
        assert!(properties_patch(&multi).is_err());
    }

    #[test]
    fn names() {
        assert!(file_name("TRS-Server-Rules-1.1.0.jar"));
        for bad in ["", ".", "..", "a/b", "a\\b", "a\u{0}"] {
            assert!(!file_name(bad), "{bad:?}");
        }
        assert_eq!(join("/", "a"), "/a");
        assert_eq!(join("/plugins", "a"), "/plugins/a");
    }
}
