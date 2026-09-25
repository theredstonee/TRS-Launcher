//! Was eine Mod-Datei über sich selbst sagt: ID, Version, was sie mitbringt
//! (`provides`) und womit sie (nicht) läuft (`depends`/`breaks`) – aus
//! `fabric.mod.json`, `quilt.mod.json` und (Neo)Forges `mods.toml`.

use std::io::{Read, Seek};

use serde::{Deserialize, Serialize};

/// Größer sind diese Dateien nie; mehr wird nicht gelesen.
pub(crate) const MAX_ENTRY_BYTES: u64 = 512 * 1024;
const MAX_CONSTRAINTS: usize = 200;
const MAX_TEXT: usize = 100;

/// Dateien im Jar, die wir brauchen (in dieser Reihenfolge gesucht).
pub(crate) const FABRIC: &str = "fabric.mod.json";
pub(crate) const QUILT: &str = "quilt.mod.json";
pub(crate) const NEOFORGE_TOML: &str = "META-INF/neoforge.mods.toml";
pub(crate) const FORGE_TOML: &str = "META-INF/mods.toml";
pub(crate) const MANIFEST: &str = "META-INF/MANIFEST.MF";
pub(crate) const WANTED: [&str; 5] = [FABRIC, QUILT, NEOFORGE_TOML, FORGE_TOML, MANIFEST];

/// Wie Versionen und Bedingungen zu lesen sind.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Scheme {
    /// Fabric/Quilt: SemVer + Fabric-Bedingungen.
    Fabric,
    /// (Neo)Forge: Maven-Versionen und -Bereiche.
    Maven,
}

/// Eine Bedingung an eine andere Mod: passt, wenn eine der Angaben passt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Constraint {
    pub id: String,
    #[serde(default)]
    pub any_of: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModInfo {
    pub id: String,
    #[serde(default)]
    pub name: String,
    pub version: String,
    pub scheme: Scheme,
    #[serde(default)]
    pub provides: Vec<String>,
    #[serde(default)]
    pub depends: Vec<Constraint>,
    #[serde(default)]
    pub breaks: Vec<Constraint>,
}

impl ModInfo {
    /// „Iris 1.10.7+mc1.21.11“ – für Berichte.
    pub fn label(&self) -> String {
        format!("{} {}", self.display_name(), self.version)
    }

    pub fn display_name(&self) -> &str {
        if self.name.is_empty() { &self.id } else { &self.name }
    }
}

pub(crate) fn is_mod_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.'))
}

fn clean(text: &str, max: usize) -> String {
    text.chars().filter(|c| !c.is_control()).take(max).collect::<String>().trim().to_owned()
}

/// Liest alle Mod-Infos aus einem Jar. Leer = keine (lesbaren) Angaben.
pub(crate) fn read_jar<R: Read + Seek>(reader: R) -> Vec<ModInfo> {
    let Ok(mut archive) = zip::ZipArchive::new(reader) else { return Vec::new() };
    from_entries(|name| {
        let entry = archive.by_name(name).ok()?;
        if entry.size() > MAX_ENTRY_BYTES {
            return None;
        }
        let mut bytes = Vec::new();
        entry.take(MAX_ENTRY_BYTES).read_to_end(&mut bytes).ok()?;
        Some(String::from_utf8_lossy(&bytes).into_owned())
    })
}

/// Wie [`read_jar`], aber mit einer eigenen Lese-Funktion (auch für Teil-Downloads).
pub(crate) fn from_entries(mut read: impl FnMut(&str) -> Option<String>) -> Vec<ModInfo> {
    if let Some(text) = read(FABRIC) {
        return parse_fabric(&text).into_iter().collect();
    }
    if let Some(text) = read(QUILT) {
        return parse_quilt(&text).into_iter().collect();
    }
    if let Some(text) = read(NEOFORGE_TOML).or_else(|| read(FORGE_TOML)) {
        let jar_version = read(MANIFEST).and_then(|m| manifest_value(&m, "Implementation-Version"));
        return parse_mods_toml(&text, jar_version.as_deref());
    }
    Vec::new()
}

/// JSON streng lesen; manche Mods haben rohe Zeilenumbrüche in Strings, die
/// der Loader duldet – dann ein zweiter Versuch ohne Steuerzeichen.
fn lenient_json(text: &str) -> Option<serde_json::Value> {
    let text = text.trim_start_matches('\u{feff}');
    serde_json::from_str(text).ok().or_else(|| {
        let cleaned: String = text.chars().map(|c| if c.is_control() { ' ' } else { c }).collect();
        serde_json::from_str(&cleaned).ok()
    })
}

/// Bedingungen aus `{ "id": "pred" | ["pred", …] }`.
fn fabric_constraints(value: Option<&serde_json::Value>) -> Vec<Constraint> {
    let Some(map) = value.and_then(|v| v.as_object()) else { return Vec::new() };
    map.iter()
        .filter(|(id, _)| is_mod_id(id))
        .filter_map(|(id, v)| {
            let any_of = match v {
                serde_json::Value::String(s) => vec![clean(s, MAX_TEXT)],
                serde_json::Value::Array(list) => {
                    list.iter().filter_map(|p| p.as_str()).map(|s| clean(s, MAX_TEXT)).take(20).collect()
                }
                _ => return None,
            };
            Some(Constraint { id: id.clone(), any_of })
        })
        .take(MAX_CONSTRAINTS)
        .collect()
}

pub(crate) fn parse_fabric(text: &str) -> Option<ModInfo> {
    let json = lenient_json(text)?;
    let id = json.get("id")?.as_str().filter(|id| is_mod_id(id))?.to_owned();
    let provides = json
        .get("provides")
        .and_then(|p| p.as_array())
        .into_iter()
        .flatten()
        .filter_map(|p| p.as_str())
        .filter(|p| is_mod_id(p))
        .map(str::to_owned)
        .take(50)
        .collect();
    Some(ModInfo {
        name: json.get("name").and_then(|n| n.as_str()).map(|n| clean(n, MAX_TEXT)).unwrap_or_default(),
        version: clean(json.get("version")?.as_str()?, MAX_TEXT),
        scheme: Scheme::Fabric,
        provides,
        depends: fabric_constraints(json.get("depends")),
        breaks: fabric_constraints(json.get("breaks")),
        id,
    })
}

/// Quilt: `versions` ist Text, Liste (ODER) oder `{ "any": […] }` / `{ "all": […] }`.
fn quilt_versions(value: Option<&serde_json::Value>) -> Option<Vec<String>> {
    let texts = |list: &Vec<serde_json::Value>| -> Vec<String> {
        list.iter().filter_map(|p| p.as_str()).map(|s| clean(s, MAX_TEXT)).take(20).collect()
    };
    match value {
        None => Some(Vec::new()),
        Some(serde_json::Value::String(s)) => Some(vec![clean(s, MAX_TEXT)]),
        Some(serde_json::Value::Array(list)) => Some(texts(list)),
        Some(serde_json::Value::Object(o)) => {
            if let Some(any) = o.get("any").and_then(|a| a.as_array()) {
                Some(texts(any))
            } else {
                // UND: eine Fabric-Bedingung mit Leerzeichen.
                o.get("all").and_then(|a| a.as_array()).map(|all| vec![texts(all).join(" ")])
            }
        }
        Some(_) => None,
    }
}

fn quilt_constraints(value: Option<&serde_json::Value>, skip_optional: bool) -> Vec<Constraint> {
    let Some(list) = value.and_then(|v| v.as_array()) else { return Vec::new() };
    list.iter()
        .filter_map(|entry| match entry {
            serde_json::Value::String(s) => Some((s.as_str(), Vec::new())),
            serde_json::Value::Object(o) => {
                let optional = o.get("optional").and_then(serde_json::Value::as_bool).unwrap_or(false);
                if (skip_optional && optional) || o.contains_key("unless") {
                    return None;
                }
                Some((o.get("id")?.as_str()?, quilt_versions(o.get("versions"))?))
            }
            _ => None,
        })
        .filter_map(|(id, any_of)| {
            // `gruppe:id` → nur die ID zählt.
            let id = id.rsplit(':').next().unwrap_or(id);
            is_mod_id(id).then(|| Constraint { id: id.to_owned(), any_of })
        })
        .take(MAX_CONSTRAINTS)
        .collect()
}

pub(crate) fn parse_quilt(text: &str) -> Option<ModInfo> {
    let json = lenient_json(text)?;
    let loader = json.get("quilt_loader")?;
    let id = loader.get("id")?.as_str().filter(|id| is_mod_id(id))?.to_owned();
    let provides = loader
        .get("provides")
        .and_then(|p| p.as_array())
        .into_iter()
        .flatten()
        .filter_map(|p| p.as_str().or_else(|| p.get("id").and_then(|i| i.as_str())))
        .filter(|p| is_mod_id(p))
        .map(str::to_owned)
        .take(50)
        .collect();
    Some(ModInfo {
        name: loader
            .get("metadata")
            .and_then(|m| m.get("name"))
            .and_then(|n| n.as_str())
            .map(|n| clean(n, MAX_TEXT))
            .unwrap_or_default(),
        version: clean(loader.get("version")?.as_str()?, MAX_TEXT),
        scheme: Scheme::Fabric,
        provides,
        depends: quilt_constraints(loader.get("depends"), true),
        breaks: quilt_constraints(loader.get("breaks"), false),
        id,
    })
}

/// Wert einer Zeile `Schlüssel: Wert` aus `MANIFEST.MF`.
fn manifest_value(text: &str, key: &str) -> Option<String> {
    text.lines().find_map(|line| {
        let (k, v) = line.split_once(':')?;
        (k.trim() == key).then(|| clean(v, MAX_TEXT)).filter(|v| !v.is_empty())
    })
}

/// (Neo)Forge: `[[mods]]` mit `modId`/`version`/`displayName` und
/// `[[dependencies.<modid>]]` mit `modId`, `type` (`required`/`incompatible`,
/// NeoForge) bzw. `mandatory` (Forge) und `versionRange`.
pub(crate) fn parse_mods_toml(text: &str, jar_version: Option<&str>) -> Vec<ModInfo> {
    let Ok(table) = text.parse::<toml::Table>() else { return Vec::new() };
    let deps_of = |mod_id: &str| -> (Vec<Constraint>, Vec<Constraint>) {
        let mut depends = Vec::new();
        let mut breaks = Vec::new();
        let list = table.get("dependencies").and_then(|d| d.get(mod_id)).and_then(|d| d.as_array());
        for dep in list.into_iter().flatten().filter_map(|d| d.as_table()).take(MAX_CONSTRAINTS) {
            let Some(id) = dep.get("modId").and_then(|v| v.as_str()).filter(|id| is_mod_id(id)) else { continue };
            let range = dep.get("versionRange").and_then(|v| v.as_str()).map(|r| clean(r, MAX_TEXT)).unwrap_or_default();
            let kind = dep.get("type").and_then(|v| v.as_str()).map(str::to_ascii_lowercase);
            let mandatory = dep.get("mandatory").and_then(toml::Value::as_bool).unwrap_or(false);
            let constraint = Constraint { id: id.to_owned(), any_of: vec![range] };
            match kind.as_deref() {
                Some("incompatible") => breaks.push(constraint),
                Some("required") => depends.push(constraint),
                None if mandatory => depends.push(constraint),
                _ => {}
            }
        }
        (depends, breaks)
    };
    let mods = table.get("mods").and_then(|m| m.as_array());
    mods.into_iter()
        .flatten()
        .filter_map(|m| m.as_table())
        .take(20)
        .filter_map(|m| {
            let id = m.get("modId")?.as_str().filter(|id| is_mod_id(id))?.to_owned();
            let raw = m.get("version").and_then(|v| v.as_str()).unwrap_or("");
            // `${file.jarVersion}` steht im Manifest.
            let version = if raw.contains("${") { jar_version?.to_owned() } else { clean(raw, MAX_TEXT) };
            if version.is_empty() {
                return None;
            }
            let (depends, breaks) = deps_of(&id);
            Some(ModInfo {
                name: m.get("displayName").and_then(|n| n.as_str()).map(|n| clean(n, MAX_TEXT)).unwrap_or_default(),
                version,
                scheme: Scheme::Maven,
                provides: Vec::new(),
                depends,
                breaks,
                id,
            })
        })
        .collect()
}

#[cfg(test)]
pub(crate) mod tests {
    use std::io::Write;

    use super::*;

    pub(crate) fn jar(entries: &[(&str, &str)]) -> Vec<u8> {
        let mut out = std::io::Cursor::new(Vec::new());
        let mut zip = zip::ZipWriter::new(&mut out);
        for (name, text) in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(text.as_bytes()).unwrap();
        }
        zip.finish().unwrap();
        out.into_inner()
    }

    #[test]
    fn reads_fabric_depends_and_breaks() {
        let bytes = jar(&[(
            FABRIC,
            r#"{"id":"sodium","name":"Sodium","version":"0.8.14+mc1.21.11","provides":["indium"],
                "depends":{"fabricloader":">=0.16.0","fabric-rendering-fluids-v1":">=2.0.0"},
                "breaks":{"iris":"<=1.10.7","embeddium":"*","bad id!":"*"}}"#,
        )]);
        let infos = read_jar(std::io::Cursor::new(bytes));
        assert_eq!(infos.len(), 1);
        let s = &infos[0];
        assert_eq!((s.id.as_str(), s.version.as_str(), s.scheme), ("sodium", "0.8.14+mc1.21.11", Scheme::Fabric));
        assert_eq!(s.provides, ["indium"]);
        assert!(s.breaks.contains(&Constraint { id: "iris".into(), any_of: vec!["<=1.10.7".into()] }));
        assert_eq!(s.breaks.len(), 2);
        assert_eq!(s.label(), "Sodium 0.8.14+mc1.21.11");

        let iris = parse_fabric("{\"id\":\"iris\",\"version\":\"1.10.7\",\"description\":\"a\nb\",\"depends\":{\"sodium\":[\"0.8.x\"]}}").unwrap();
        assert_eq!(iris.depends, [Constraint { id: "sodium".into(), any_of: vec!["0.8.x".into()] }]);
        assert_eq!(iris.display_name(), "iris");
        assert!(parse_fabric(r#"{"version":"1"}"#).is_none());
        assert!(read_jar(std::io::Cursor::new(b"kein zip".to_vec())).is_empty());
    }

    #[test]
    fn reads_quilt() {
        let q = parse_quilt(
            r#"{"quilt_loader":{"id":"qmod","version":"2.0.0","metadata":{"name":"Q"},
                "provides":["alias",{"id":"other"}],
                "depends":["qsl",{"id":"org.x:lib","versions":{"all":[">=1.0","<2.0"]}},{"id":"opt","optional":true}],
                "breaks":[{"id":"bad","versions":[">=3","<1"]}]}}"#,
        )
        .unwrap();
        assert_eq!(q.provides, ["alias", "other"]);
        assert_eq!(q.depends.len(), 2);
        assert_eq!(q.depends[1], Constraint { id: "lib".into(), any_of: vec![">=1.0 <2.0".into()] });
        assert_eq!(q.breaks[0].any_of, [">=3", "<1"]);
    }

    #[test]
    fn reads_mods_toml_with_manifest_version() {
        let toml = r#"
modLoader="javafml"
loaderVersion="[4,)"
[[mods]]
modId="embeddium"
version="${file.jarVersion}"
displayName="Embeddium"
[[dependencies.embeddium]]
modId="oculus"
type="incompatible"
versionRange="(,1.6.9]"
[[dependencies.embeddium]]
modId="minecraft"
mandatory=true
versionRange="[1.20.1,1.20.2)"
[[dependencies.embeddium]]
modId="optional_one"
type="optional"
versionRange="*"
"#;
        let bytes = jar(&[(FORGE_TOML, toml), (MANIFEST, "Manifest-Version: 1.0\r\nImplementation-Version: 0.3.31+mc1.20.1\r\n")]);
        let infos = read_jar(std::io::Cursor::new(bytes));
        assert_eq!(infos.len(), 1);
        let e = &infos[0];
        assert_eq!((e.id.as_str(), e.version.as_str(), e.scheme), ("embeddium", "0.3.31+mc1.20.1", Scheme::Maven));
        assert_eq!(e.breaks, [Constraint { id: "oculus".into(), any_of: vec!["(,1.6.9]".into()] }]);
        assert_eq!(e.depends.len(), 1);
        // Platzhalter ohne Manifest: nicht verwertbar.
        assert!(parse_mods_toml(toml, None).is_empty());
    }
}
