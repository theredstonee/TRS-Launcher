//! Version-JSON (`client.json`) – Mojangs Beschreibung, wie eine Version
//! heruntergeladen und gestartet wird. Modloader liefern dasselbe Format mit
//! `inheritsFrom`, siehe [`VersionInfo::merge_onto`].

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use crate::meta::manifest::ManifestVersion;
use crate::paths::Paths;
use crate::{Error, Result, download, fsutil};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionInfo {
    pub id: String,
    pub inherits_from: Option<String>,
    pub main_class: Option<String>,
    /// Bis 1.12.2: ein einzelner String statt `arguments`.
    pub minecraft_arguments: Option<String>,
    pub arguments: Option<Arguments>,
    pub asset_index: Option<AssetIndexRef>,
    pub assets: Option<String>,
    pub downloads: Option<VersionDownloads>,
    pub java_version: Option<JavaVersion>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    pub logging: Option<Logging>,
    #[serde(rename = "type")]
    pub kind: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<Argument>,
    #[serde(default)]
    pub jvm: Vec<Argument>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Argument {
    Plain(String),
    Conditional { rules: Vec<Rule>, value: OneOrMany },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum OneOrMany {
    One(String),
    Many(Vec<String>),
}

impl OneOrMany {
    pub fn iter(&self) -> impl Iterator<Item = &str> {
        match self {
            Self::One(s) => std::slice::from_ref(s).iter(),
            Self::Many(v) => v.iter(),
        }
        .map(String::as_str)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RuleAction {
    Allow,
    Disallow,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rule {
    pub action: RuleAction,
    pub os: Option<OsRule>,
    pub features: Option<HashMap<String, bool>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OsRule {
    pub name: Option<String>,
    pub arch: Option<String>,
    pub version: Option<String>,
}

/// Feature-Flags, gegen die `rules.features` geprüft wird.
#[derive(Debug, Clone, Copy, Default)]
pub struct Features {
    pub demo_user: bool,
    pub custom_resolution: bool,
}

impl Features {
    fn get(&self, name: &str) -> bool {
        match name {
            "is_demo_user" => self.demo_user,
            "has_custom_resolution" => self.custom_resolution,
            // Quick-Play & Co. unterstützen wir (noch) nicht.
            _ => false,
        }
    }
}

impl Rule {
    fn matches(&self, features: &Features) -> bool {
        let os_ok = self.os.as_ref().is_none_or(OsRule::matches_host);
        let features_ok = self
            .features
            .as_ref()
            .is_none_or(|f| f.iter().all(|(name, want)| features.get(name) == *want));
        os_ok && features_ok
    }
}

impl OsRule {
    /// Der Launcher läuft nur auf Windows; `arch: "x86"` meint 32 Bit.
    fn matches_host(&self) -> bool {
        if self.name.as_deref().is_some_and(|n| n != "windows") {
            return false;
        }
        if let Some(arch) = self.arch.as_deref() {
            let host = if cfg!(target_arch = "x86") { "x86" } else { std::env::consts::ARCH };
            if arch != host {
                return false;
            }
        }
        // Einziges Vorkommen in Mojangs Daten: `^10\.` (Windows 10 und neuer).
        // Wir unterstützen nur Windows 10+, daher trifft das immer zu; andere
        // Muster behandeln wir konservativ als "passt nicht".
        self.version.as_deref().is_none_or(|v| v == r"^10\.")
    }
}

/// Mojangs Semantik: ohne Regeln erlaubt; sonst gewinnt die letzte passende Regel.
pub fn rules_allow(rules: &[Rule], features: &Features) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in rules {
        if rule.matches(features) {
            allowed = rule.action == RuleAction::Allow;
        }
    }
    allowed
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetIndexRef {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub total_size: Option<u64>,
    pub url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionDownloads {
    pub client: Option<Artifact>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaVersion {
    pub component: String,
    pub major_version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Logging {
    pub client: Option<LoggingClient>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoggingClient {
    pub argument: String,
    pub file: LoggingFile,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoggingFile {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Artifact {
    pub path: Option<String>,
    pub sha1: Option<String>,
    pub size: Option<u64>,
    pub url: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct LibraryDownloads {
    pub artifact: Option<Artifact>,
    pub classifiers: Option<HashMap<String, Artifact>>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Extract {
    #[serde(default)]
    pub exclude: Vec<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Library {
    pub name: String,
    pub downloads: Option<LibraryDownloads>,
    /// Maven-Repo-Basis, wenn `downloads` fehlt (Fabric, Quilt, altes Forge).
    pub url: Option<String>,
    /// Altes Natives-Format (bis 1.18): OS → Classifier.
    pub natives: Option<HashMap<String, String>>,
    pub extract: Option<Extract>,
    pub rules: Option<Vec<Rule>>,
}

/// `group:artifact:version[:classifier][@ext]`
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MavenCoord {
    pub group: String,
    pub artifact: String,
    pub version: String,
    pub classifier: Option<String>,
    pub ext: String,
}

impl MavenCoord {
    pub fn parse(name: &str) -> Result<Self> {
        let (coords, ext) = name.split_once('@').unwrap_or((name, "jar"));
        let parts: Vec<&str> = coords.split(':').collect();
        let safe = |s: &str| {
            !s.is_empty()
                && !s.contains("..")
                && s.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+'))
        };
        if !(3..=4).contains(&parts.len()) || !parts.iter().all(|p| safe(p)) || !safe(ext) {
            return Err(Error::validation(format!("Ungültige Library-Koordinate: {name}")));
        }
        Ok(Self {
            group: parts[0].into(),
            artifact: parts[1].into(),
            version: parts[2].into(),
            classifier: parts.get(3).map(|s| (*s).into()),
            ext: ext.into(),
        })
    }

    /// Relativer Pfad im Maven-Layout, immer mit `/`.
    pub fn path(&self) -> String {
        let classifier = self.classifier.as_ref().map(|c| format!("-{c}")).unwrap_or_default();
        format!(
            "{}/{}/{}/{}-{}{}.{}",
            self.group.replace('.', "/"),
            self.artifact,
            self.version,
            self.artifact,
            self.version,
            classifier,
            self.ext
        )
    }

    /// Schlüssel zum Deduplizieren beim `inheritsFrom`-Merge.
    fn dedupe_key(&self) -> (String, String, Option<String>) {
        (self.group.clone(), self.artifact.clone(), self.classifier.clone())
    }
}

/// Was von einer Library tatsächlich gebraucht wird.
#[derive(Debug, Clone)]
pub struct ResolvedLibrary {
    /// Pfad relativ zu `libraries/`.
    pub path: String,
    pub url: String,
    pub sha1: Option<String>,
    pub size: Option<u64>,
    /// Kommt auf den Classpath.
    pub on_classpath: bool,
    /// Muss ins Natives-Verzeichnis entpackt werden (altes Format).
    pub extract: Option<Extract>,
}

const DEFAULT_LIBRARY_REPO: &str = "https://libraries.minecraft.net/";

impl Library {
    pub fn resolve(&self, features: &Features) -> Result<Vec<ResolvedLibrary>> {
        if let Some(rules) = &self.rules
            && !rules_allow(rules, features)
        {
            return Ok(Vec::new());
        }

        let coord = MavenCoord::parse(&self.name)?;
        let mut out = Vec::new();

        // Neues Format (1.19+): Natives sind eigene Einträge, die Regeln kennen
        // aber nur das OS – die Architektur steckt im Classifier.
        if let Some(classifier) = &coord.classifier
            && classifier.starts_with("natives-")
            && classifier != host_natives_classifier()
        {
            return Ok(out);
        }

        let downloads = self.downloads.as_ref();
        let is_native_only = self.natives.is_some() && downloads.is_some_and(|d| d.artifact.is_none());

        if !is_native_only {
            match downloads.and_then(|d| d.artifact.as_ref()) {
                Some(a) => out.push(ResolvedLibrary {
                    path: checked_rel_path(a.path.clone().unwrap_or_else(|| coord.path()))?,
                    url: a.url.clone(),
                    sha1: a.sha1.clone(),
                    size: a.size,
                    on_classpath: true,
                    extract: None,
                }),
                // Ohne `downloads`: URL aus Repo-Basis + Maven-Pfad bauen.
                None if downloads.is_none() => {
                    let base = self.url.as_deref().unwrap_or(DEFAULT_LIBRARY_REPO);
                    let base = if base.ends_with('/') { base.to_owned() } else { format!("{base}/") };
                    out.push(ResolvedLibrary {
                        path: coord.path(),
                        url: format!("{base}{}", coord.path()),
                        sha1: None,
                        size: None,
                        on_classpath: true,
                        extract: None,
                    });
                }
                None => {}
            }
        }

        if let Some(natives) = &self.natives
            && let Some(template) = natives.get("windows")
        {
            let bits = if cfg!(target_pointer_width = "64") { "64" } else { "32" };
            let classifier = template.replace("${arch}", bits);
            if let Some(a) = downloads.and_then(|d| d.classifiers.as_ref()).and_then(|c| c.get(&classifier)) {
                let fallback = MavenCoord { classifier: Some(classifier.clone()), ..coord.clone() }.path();
                out.push(ResolvedLibrary {
                    path: checked_rel_path(a.path.clone().unwrap_or(fallback))?,
                    url: a.url.clone(),
                    sha1: a.sha1.clone(),
                    size: a.size,
                    on_classpath: false,
                    extract: Some(self.extract.clone().unwrap_or_default()),
                });
            }
        }

        Ok(out)
    }
}

fn host_natives_classifier() -> &'static str {
    match std::env::consts::ARCH {
        "aarch64" => "natives-windows-arm64",
        "x86" => "natives-windows-x86",
        _ => "natives-windows",
    }
}

/// Pfade aus fremden JSONs dürfen nicht aus `libraries/` ausbrechen.
fn checked_rel_path(path: String) -> Result<String> {
    let bad = path.is_empty()
        || path.starts_with('/')
        || path.contains('\\')
        || path.contains(':')
        || path.split('/').any(|seg| seg.is_empty() || seg == "." || seg == "..");
    if bad { Err(Error::validation(format!("Unsicherer Library-Pfad: {path}"))) } else { Ok(path) }
}

impl VersionInfo {
    /// Legt `self` (Kind, z. B. Fabric-Profil) über `parent` (Vanilla).
    pub fn merge_onto(self, parent: VersionInfo) -> VersionInfo {
        let mut seen = std::collections::HashSet::new();
        let mut libraries = Vec::with_capacity(self.libraries.len() + parent.libraries.len());
        // Kind zuerst: bei gleicher group:artifact gewinnt die Loader-Version.
        for lib in self.libraries.into_iter().chain(parent.libraries) {
            let key = MavenCoord::parse(&lib.name).map(|c| c.dedupe_key()).ok();
            if key.is_none_or(|k| seen.insert(k)) {
                libraries.push(lib);
            }
        }

        let arguments = match (parent.arguments, self.arguments) {
            (Some(mut p), Some(c)) => {
                p.game.extend(c.game);
                p.jvm.extend(c.jvm);
                Some(p)
            }
            (p, c) => c.or(p),
        };

        VersionInfo {
            id: self.id,
            inherits_from: None,
            main_class: self.main_class.or(parent.main_class),
            minecraft_arguments: self.minecraft_arguments.or(parent.minecraft_arguments),
            arguments,
            asset_index: self.asset_index.or(parent.asset_index),
            assets: self.assets.or(parent.assets),
            downloads: self.downloads.or(parent.downloads),
            java_version: self.java_version.or(parent.java_version),
            libraries,
            logging: self.logging.or(parent.logging),
            kind: self.kind.or(parent.kind),
        }
    }
}

/// Lädt das Version-JSON einer Vanilla-Version (SHA1-geprüft, gecacht unter
/// `versions/<id>/<id>.json`).
pub async fn fetch_vanilla(
    http: &reqwest::Client,
    paths: &Paths,
    entry: &ManifestVersion,
) -> Result<VersionInfo> {
    let file = paths.version_json(&entry.id);
    let task = download::Task {
        url: entry.url.clone(),
        path: file.clone(),
        sha1: Some(entry.sha1.clone()),
        size: None,
    };
    // Die SHA1 im Manifest ändert sich, wenn Mojang das JSON aktualisiert –
    // deshalb hier immer gegen den Hash prüfen statt nur auf Existenz.
    if !download::is_valid(&task, true).await {
        download::fetch_one(http, &task).await?;
    }
    fsutil::read_json::<VersionInfo>(&file)
        .await?
        .ok_or_else(|| Error::UnknownGameVersion(entry.id.clone()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lib(json: &str) -> Library {
        serde_json::from_str(json).unwrap()
    }

    #[test]
    fn maven_paths() {
        let c = MavenCoord::parse("org.lwjgl:lwjgl:3.3.3:natives-windows").unwrap();
        assert_eq!(c.path(), "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar");
        let c = MavenCoord::parse("de.oceanlabs.mcp:mcp_config:1.20.1@zip").unwrap();
        assert_eq!(c.path(), "de/oceanlabs/mcp/mcp_config/1.20.1/mcp_config-1.20.1.zip");
        assert!(MavenCoord::parse("a:b").is_err());
        assert!(MavenCoord::parse("../x:b:1").is_err());
        assert!(MavenCoord::parse("a:b:1/../../x").is_err());
    }

    #[test]
    fn rule_semantics() {
        let rules: Vec<Rule> = serde_json::from_str(
            r#"[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]"#,
        )
        .unwrap();
        assert!(rules_allow(&rules, &Features::default()));

        let osx_only: Vec<Rule> =
            serde_json::from_str(r#"[{"action":"allow","os":{"name":"osx"}}]"#).unwrap();
        assert!(!rules_allow(&osx_only, &Features::default()));

        let demo: Vec<Rule> =
            serde_json::from_str(r#"[{"action":"allow","features":{"is_demo_user":true}}]"#).unwrap();
        assert!(!rules_allow(&demo, &Features::default()));
        assert!(rules_allow(&demo, &Features { demo_user: true, ..Default::default() }));
    }

    #[test]
    fn resolves_modern_library_and_filters_foreign_natives() {
        let f = Features::default();
        let normal = lib(r#"{"name":"com.mojang:brigadier:1.3.10","downloads":{"artifact":{
            "path":"com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar","sha1":"aa","size":10,
            "url":"https://libraries.minecraft.net/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar"}}}"#);
        let r = normal.resolve(&f).unwrap();
        assert_eq!(r.len(), 1);
        assert!(r[0].on_classpath && r[0].extract.is_none());

        let arm = lib(r#"{"name":"org.lwjgl:lwjgl:3.3.3:natives-windows-arm64",
            "downloads":{"artifact":{"path":"x/y.jar","sha1":"aa","size":1,"url":"https://x/y.jar"}},
            "rules":[{"action":"allow","os":{"name":"windows"}}]}"#);
        if std::env::consts::ARCH == "x86_64" {
            assert!(arm.resolve(&f).unwrap().is_empty());
        }

        let linux = lib(r#"{"name":"org.lwjgl:lwjgl:3.3.3:natives-linux",
            "downloads":{"artifact":{"path":"x/z.jar","sha1":"aa","size":1,"url":"https://x/z.jar"}},
            "rules":[{"action":"allow","os":{"name":"linux"}}]}"#);
        assert!(linux.resolve(&f).unwrap().is_empty());
    }

    #[test]
    fn resolves_legacy_natives() {
        let l = lib(r#"{"name":"org.lwjgl.lwjgl:lwjgl-platform:2.9.1",
            "natives":{"linux":"natives-linux","windows":"natives-windows-${arch}"},
            "extract":{"exclude":["META-INF/"]},
            "downloads":{"classifiers":{
              "natives-windows-64":{"path":"p/lwjgl-platform-2.9.1-natives-windows-64.jar","sha1":"aa","size":1,"url":"https://x/n.jar"}}}}"#);
        let r = l.resolve(&Features::default()).unwrap();
        if cfg!(target_pointer_width = "64") {
            assert_eq!(r.len(), 1);
            assert!(!r[0].on_classpath);
            assert_eq!(r[0].extract.as_ref().unwrap().exclude, vec!["META-INF/"]);
        }
    }

    #[test]
    fn resolves_maven_style_library() {
        let l = lib(r#"{"name":"net.fabricmc:fabric-loader:0.16.10","url":"https://maven.fabricmc.net"}"#);
        let r = l.resolve(&Features::default()).unwrap();
        assert_eq!(r[0].url, "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar");
    }

    #[test]
    fn rejects_path_traversal_in_artifact_path() {
        let l = lib(r#"{"name":"a:b:1","downloads":{"artifact":{"path":"../../evil.jar","url":"https://x/e.jar"}}}"#);
        assert!(l.resolve(&Features::default()).is_err());
    }

    #[test]
    fn inherits_from_merge() {
        let parent: VersionInfo = serde_json::from_str(
            r#"{"id":"1.21.1","mainClass":"net.minecraft.client.main.Main","assets":"17",
                "arguments":{"game":["--username","${auth_player_name}"],"jvm":["-cp","${classpath}"]},
                "javaVersion":{"component":"java-runtime-delta","majorVersion":21},
                "libraries":[{"name":"org.ow2.asm:asm:9.6"},{"name":"com.mojang:brigadier:1.3.10"}]}"#,
        )
        .unwrap();
        let child: VersionInfo = serde_json::from_str(
            r#"{"id":"fabric-loader-0.16.10-1.21.1","inheritsFrom":"1.21.1",
                "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
                "arguments":{"game":[],"jvm":["-DFabricMcEmu= net.minecraft.client.main.Main "]},
                "libraries":[{"name":"org.ow2.asm:asm:9.7.1","url":"https://maven.fabricmc.net/"}]}"#,
        )
        .unwrap();

        let merged = child.merge_onto(parent);
        assert_eq!(merged.id, "fabric-loader-0.16.10-1.21.1");
        assert_eq!(merged.main_class.as_deref(), Some("net.fabricmc.loader.impl.launch.knot.KnotClient"));
        assert_eq!(merged.assets.as_deref(), Some("17"));
        assert_eq!(merged.java_version.unwrap().major_version, 21);
        let names: Vec<_> = merged.libraries.iter().map(|l| l.name.as_str()).collect();
        assert_eq!(names, ["org.ow2.asm:asm:9.7.1", "com.mojang:brigadier:1.3.10"]);
        let args = merged.arguments.unwrap();
        assert_eq!(args.jvm.len(), 3);
        assert_eq!(args.game.len(), 2);
    }
}
