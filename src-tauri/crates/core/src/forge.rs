//! Forge und NeoForge – eigener Installer-Parser und Processor-Runner.
//!
//! Anders als Fabric/Quilt liefern die beiden kein fertiges Profil über eine
//! Meta-API. Stattdessen laden wir den offiziellen Installer, lesen daraus
//! `install_profile.json` + `version.json` und führen die Client-Processors
//! (Mappings laden, Jar aufteilen, Binärpatches anwenden …) selbst aus.
//!
//! Drei Installer-Generationen werden unterstützt:
//! - **modern** (`processors`, ab 1.13): Libraries laden, Processors ausführen
//! - **modern ohne Processors** (neuere 1.12.2-Builds): nur `maven/` entpacken
//! - **alt** (`install` + `versionInfo`, 1.7–1.12.2): Universal-Jar entpacken
//!
//! Eine fertige Installation wird über eine Marker-Datei neben dem Installer
//! erkannt; der zweite Start braucht dann weder Netzwerk noch Java-Prozesse.

use std::cmp::Ordering;
use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering as AtomicOrdering};

use futures::StreamExt;
use serde::{Deserialize, Serialize};

use crate::download::{self, Progress, Task};
use crate::instance::{Loader, LoaderKind};
use crate::meta::is_safe_id;
use crate::meta::version::{Features, Library, MavenCoord, VersionInfo};
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

const FORGE_PROMOTIONS_URL: &str = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
const FORGE_METADATA_URL: &str = "https://files.minecraftforge.net/net/minecraftforge/forge/maven-metadata.json";
const FORGE_MAVEN: &str = "https://maven.minecraftforge.net/";
const NEOFORGE_VERSIONS_URL: &str = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";
/// Für Minecraft 1.20.1 hieß NeoForge noch `net.neoforged:forge`.
const NEOFORGE_LEGACY_VERSIONS_URL: &str = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/forge";
const NEOFORGE_MAVEN: &str = "https://maven.neoforged.net/releases/";
const MOJANG_LIBRARIES: &str = "https://libraries.minecraft.net/";

const NEOFORGE_LEGACY_GAME_VERSION: &str = "1.20.1";
const MARKER_FILE: &str = "trs-install.json";
const MARKER_FORMAT: u32 = 1;
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
/// Obergrenze für JSON-/Manifest-Einträge aus Jars (Schutz vor Zip-Bomben).
const MAX_TEXT_ENTRY: u64 = 16 * 1024 * 1024;

// Anteile der Teilschritte am Fortschritt der Loader-Stufe.
const PERCENT_INSTALLER: f64 = 15.0;
const PERCENT_LIBRARIES: f64 = 55.0;

/// Zwei Instanzen mit demselben Loader dürfen nicht gleichzeitig installieren.
static INSTALL_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// Fortschritt der Loader-Installation (0–100).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct InstallProgress {
    pub percent: f64,
    /// Erledigte Schritte des aktuellen Teilschritts (Dateien bzw. Processors).
    pub done: u64,
    pub total: u64,
}

/// Alles, was die Installation von außen braucht.
pub struct InstallContext<'a> {
    pub http: &'a reqwest::Client,
    pub paths: &'a Paths,
    pub game_version: &'a str,
    pub loader: &'a Loader,
    /// Das bereits heruntergeladene Vanilla-Client-Jar.
    pub client_jar: &'a Path,
    /// Java der Spielversion (`javaw.exe` oder `java.exe`).
    pub java: &'a Path,
    pub concurrency: usize,
}

// --- Versionsauflösung -------------------------------------------------------

/// Wo der Installer einer Loader-Version liegt.
#[derive(Debug, Clone, PartialEq, Eq)]
struct InstallerRef {
    repo: &'static str,
    /// Verzeichnis des Artefakts im Maven-Layout, z. B. `net/minecraftforge/forge`.
    artifact_dir: &'static str,
    artifact: &'static str,
    /// Maven-Version, z. B. `1.20.1-47.4.10` oder `21.1.251`.
    version: String,
}

impl InstallerRef {
    fn forge(version: String) -> Self {
        Self { repo: FORGE_MAVEN, artifact_dir: "net/minecraftforge/forge", artifact: "forge", version }
    }

    fn neoforge(version: String) -> Self {
        Self { repo: NEOFORGE_MAVEN, artifact_dir: "net/neoforged/neoforge", artifact: "neoforge", version }
    }

    fn neoforge_legacy(version: String) -> Self {
        Self { repo: NEOFORGE_MAVEN, artifact_dir: "net/neoforged/forge", artifact: "forge", version }
    }

    fn rel_dir(&self) -> String {
        format!("{}/{}", self.artifact_dir, self.version)
    }

    fn rel_path(&self) -> String {
        format!("{}/{}-{}-installer.jar", self.rel_dir(), self.artifact, self.version)
    }

    fn url(&self) -> String {
        format!("{}{}", self.repo, self.rel_path())
    }
}

#[derive(Deserialize)]
struct ForgePromotions {
    promos: HashMap<String, String>,
}

#[derive(Deserialize)]
struct MavenVersions {
    versions: Vec<String>,
}

/// Loader-Versionen landen in URLs und Pfaden – enger als die Instanz-Prüfung.
fn is_safe_version(v: &str) -> bool {
    !v.is_empty()
        && v.len() <= 64
        && !v.contains("..")
        && v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+'))
}

/// Alle Zahlen einer Version, für einen numerischen Vergleich
/// (`47.4.10` > `47.4.9`, was als Text nicht stimmt).
fn numeric_key(v: &str) -> Vec<u64> {
    v.split(|c: char| !c.is_ascii_digit()).filter(|s| !s.is_empty()).map(|s| s.parse().unwrap_or(u64::MAX)).collect()
}

fn cmp_versions(a: &str, b: &str) -> Ordering {
    numeric_key(a).cmp(&numeric_key(b))
}

fn newest<'a>(versions: impl Iterator<Item = &'a String>) -> Option<&'a String> {
    versions.max_by(|a, b| cmp_versions(a, b))
}

/// Empfohlene Forge-Version, sonst die neueste.
fn pick_forge_promo(promos: &HashMap<String, String>, game_version: &str) -> Option<String> {
    promos
        .get(&format!("{game_version}-recommended"))
        .or_else(|| promos.get(&format!("{game_version}-latest")))
        .filter(|v| is_safe_version(v))
        .cloned()
}

/// Sucht zur kurzen Forge-Version (`10.13.4.1614`) den vollen Maven-String.
/// Alte Versionen hängen die Spielversion noch einmal an
/// (`1.7.10-10.13.4.1614-1.7.10`), deshalb wird die Liste wörtlich übernommen.
fn forge_full_version(listed: &[String], game_version: &str, forge_version: &str) -> Option<String> {
    let exact = format!("{game_version}-{forge_version}");
    let with_suffix = format!("{exact}-");
    listed.iter().find(|v| **v == exact || v.starts_with(&with_suffix)).filter(|v| is_safe_version(v)).cloned()
}

/// NeoForge-Versionen beginnen mit der Spielversion ohne die führende `1.`:
/// 1.21.1 → `21.1.`, 1.21 → `21.0.`. Die jahresbasierten Versionen (26.1,
/// 26.1.2 …) tragen alle drei Stellen: `26.1.0.`, `26.1.2.`.
fn neoforge_prefix(game_version: &str) -> Option<String> {
    let parts: Vec<u32> = game_version.split('.').map(|p| p.parse().ok()).collect::<Option<_>>()?;
    match parts.as_slice() {
        [1, minor] if *minor >= 20 => Some(format!("{minor}.0.")),
        [1, minor, patch] if *minor >= 20 => Some(format!("{minor}.{patch}.")),
        [year, drop] if *year >= 26 => Some(format!("{year}.{drop}.0.")),
        [year, drop, patch] if *year >= 26 => Some(format!("{year}.{drop}.{patch}.")),
        _ => None,
    }
}

/// Neueste stabile NeoForge-Version für die Spielversion; Betas nur, wenn es
/// noch keine stabile gibt. Alphas/Snapshots (`-alpha…+snapshot`) nie.
fn pick_neoforge(versions: &[String], game_version: &str) -> Option<String> {
    let prefix = if game_version == NEOFORGE_LEGACY_GAME_VERSION {
        format!("{NEOFORGE_LEGACY_GAME_VERSION}-")
    } else {
        neoforge_prefix(game_version)?
    };
    let candidates: Vec<&String> =
        versions.iter().filter(|v| v.starts_with(&prefix) && is_safe_version(v) && !v.contains('+')).collect();
    let stable = newest(candidates.iter().copied().filter(|v| !v[prefix.len()..].contains('-')));
    stable.or_else(|| newest(candidates.iter().copied().filter(|v| v.ends_with("-beta")))).cloned()
}

fn not_available(game_version: &str) -> Error {
    Error::launch(format!("Für Minecraft {game_version} gibt es diesen Modloader (noch) nicht."))
}

async fn fetch_json<T: serde::de::DeserializeOwned>(http: &reqwest::Client, url: &str) -> Result<T> {
    Ok(http.get(url).send().await?.error_for_status()?.json().await?)
}

/// Bereits heruntergeladene Installer-Versionen unter `libraries/<artifact_dir>/`.
async fn installed_versions(paths: &Paths, make: fn(String) -> InstallerRef, prefix: &str) -> Vec<String> {
    let probe = make(String::new());
    let dir = probe.artifact_dir.split('/').fold(paths.libraries_dir(), |p, seg| p.join(seg));
    let Ok(mut entries) = tokio::fs::read_dir(&dir).await else { return Vec::new() };

    let mut found = Vec::new();
    while let Ok(Some(entry)) = entries.next_entry().await {
        let Ok(name) = entry.file_name().into_string() else { continue };
        if name.starts_with(prefix)
            && is_safe_version(&name)
            && library_file(paths, &make(name.clone()).rel_path()).is_file()
        {
            found.push(name);
        }
    }
    found
}

async fn resolve_forge(ctx: &InstallContext<'_>) -> Result<InstallerRef> {
    let mc = ctx.game_version;
    let prefix = format!("{mc}-");

    let short = match ctx.loader.version.as_deref() {
        Some(v) if v.starts_with(&prefix) => return Ok(InstallerRef::forge(v.to_owned())),
        Some(v) => v.to_owned(),
        None => match fetch_json::<ForgePromotions>(ctx.http, FORGE_PROMOTIONS_URL).await {
            Ok(p) => match pick_forge_promo(&p.promos, mc) {
                Some(v) => v,
                // Ohne Promotion-Eintrag: die neueste gelistete Version.
                None => {
                    let listed: HashMap<String, Vec<String>> = fetch_json(ctx.http, FORGE_METADATA_URL).await?;
                    let versions = listed.get(mc).ok_or_else(|| not_available(mc))?;
                    let full = newest(versions.iter().filter(|v| is_safe_version(v)));
                    return full.cloned().map(InstallerRef::forge).ok_or_else(|| not_available(mc));
                }
            },
            // Offline: mit der neuesten bereits geladenen Version weiterstarten.
            Err(e) => {
                tracing::warn!("Forge-Versionsliste nicht erreichbar: {e}");
                let local = installed_versions(ctx.paths, InstallerRef::forge, &prefix).await;
                return newest(local.iter()).cloned().map(InstallerRef::forge).ok_or(e);
            }
        },
    };

    // Schon auf der Platte? Dann kennen wir den vollen Versionsstring.
    let local = installed_versions(ctx.paths, InstallerRef::forge, &prefix).await;
    if let Some(full) = forge_full_version(&local, mc, &short) {
        return Ok(InstallerRef::forge(full));
    }
    match fetch_json::<HashMap<String, Vec<String>>>(ctx.http, FORGE_METADATA_URL).await {
        Ok(listed) => listed
            .get(mc)
            .and_then(|versions| forge_full_version(versions, mc, &short))
            .map(InstallerRef::forge)
            .ok_or_else(|| Error::launch(format!("Forge {short} gibt es für Minecraft {mc} nicht."))),
        Err(e) => {
            tracing::warn!("Forge-Metadaten nicht erreichbar, verwende Standardschema: {e}");
            Ok(InstallerRef::forge(format!("{mc}-{short}")))
        }
    }
}

async fn resolve_neoforge(ctx: &InstallContext<'_>) -> Result<InstallerRef> {
    let mc = ctx.game_version;
    let legacy = mc == NEOFORGE_LEGACY_GAME_VERSION;
    let make: fn(String) -> InstallerRef = if legacy { InstallerRef::neoforge_legacy } else { InstallerRef::neoforge };

    if let Some(v) = ctx.loader.version.as_deref() {
        let prefix = format!("{NEOFORGE_LEGACY_GAME_VERSION}-");
        let version = if legacy && !v.starts_with(&prefix) { format!("{prefix}{v}") } else { v.to_owned() };
        return Ok(make(version));
    }

    let prefix = if legacy { format!("{mc}-") } else { neoforge_prefix(mc).ok_or_else(|| not_available(mc))? };
    let url = if legacy { NEOFORGE_LEGACY_VERSIONS_URL } else { NEOFORGE_VERSIONS_URL };
    match fetch_json::<MavenVersions>(ctx.http, url).await {
        Ok(list) => pick_neoforge(&list.versions, mc).map(make).ok_or_else(|| not_available(mc)),
        Err(e) => {
            tracing::warn!("NeoForge-Versionsliste nicht erreichbar: {e}");
            let local = installed_versions(ctx.paths, make, &prefix).await;
            pick_neoforge(&local, mc).map(make).ok_or(e)
        }
    }
}

async fn resolve_installer(ctx: &InstallContext<'_>) -> Result<InstallerRef> {
    if let Some(v) = ctx.loader.version.as_deref()
        && !is_safe_version(v)
    {
        return Err(Error::launch("Die Loader-Version enthält ungültige Zeichen."));
    }
    let installer = match ctx.loader.kind {
        LoaderKind::Forge => resolve_forge(ctx).await?,
        LoaderKind::NeoForge => resolve_neoforge(ctx).await?,
        _ => return Err(Error::Internal("forge::ensure_installed nur für Forge/NeoForge".into())),
    };
    if is_safe_version(&installer.version) {
        Ok(installer)
    } else {
        Err(Error::launch("Die Loader-Version enthält ungültige Zeichen."))
    }
}

/// Welche Version „neueste stabile“ derzeit bedeutet (nur zur Anzeige –
/// beim Start löst [`resolve_installer`] selbst auf). Forge: empfohlene
/// Version als voller Maven-String, NeoForge: neueste stabile.
pub async fn latest_version(http: &reqwest::Client, kind: LoaderKind, game_version: &str) -> Result<String> {
    match kind {
        LoaderKind::Forge => {
            let promos: ForgePromotions = fetch_json(http, FORGE_PROMOTIONS_URL).await?;
            match pick_forge_promo(&promos.promos, game_version) {
                Some(short) => Ok(format!("{game_version}-{short}")),
                None => available_versions(http, kind, game_version)
                    .await?
                    .into_iter()
                    .next()
                    .ok_or_else(|| not_available(game_version)),
            }
        }
        LoaderKind::NeoForge => {
            let url = if game_version == NEOFORGE_LEGACY_GAME_VERSION { NEOFORGE_LEGACY_VERSIONS_URL } else { NEOFORGE_VERSIONS_URL };
            let list: MavenVersions = fetch_json(http, url).await?;
            pick_neoforge(&list.versions, game_version).ok_or_else(|| not_available(game_version))
        }
        _ => Err(Error::Internal("latest_version nur für Forge/NeoForge".into())),
    }
}

/// Auswählbare Loader-Versionen für eine Spielversion, neueste zuerst.
/// Forge liefert den vollen Maven-String (`1.20.1-47.4.10`), der so auch als
/// `Loader::version` akzeptiert wird.
pub async fn available_versions(
    http: &reqwest::Client,
    kind: LoaderKind,
    game_version: &str,
) -> Result<Vec<String>> {
    let mut versions: Vec<String> = match kind {
        LoaderKind::Forge => {
            let mut listed: HashMap<String, Vec<String>> = fetch_json(http, FORGE_METADATA_URL).await?;
            listed.remove(game_version).unwrap_or_default()
        }
        LoaderKind::NeoForge => {
            let (url, prefix) = if game_version == NEOFORGE_LEGACY_GAME_VERSION {
                (NEOFORGE_LEGACY_VERSIONS_URL, Some(format!("{game_version}-")))
            } else {
                (NEOFORGE_VERSIONS_URL, neoforge_prefix(game_version))
            };
            let Some(prefix) = prefix else { return Ok(Vec::new()) };
            let list: MavenVersions = fetch_json(http, url).await?;
            list.versions.into_iter().filter(|v| v.starts_with(&prefix) && !v.contains('+')).collect()
        }
        _ => return Err(Error::Internal("available_versions nur für Forge/NeoForge".into())),
    };
    versions.retain(|v| is_safe_version(v));
    versions.sort_by(|a, b| cmp_versions(b, a));
    Ok(versions)
}

// --- Installer-Format --------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstallProfile {
    minecraft: Option<String>,
    /// Name des Version-JSONs im Installer (modern), meist `/version.json`.
    json: Option<String>,
    #[serde(default)]
    data: HashMap<String, SidedValue>,
    #[serde(default)]
    processors: Vec<Processor>,
    #[serde(default)]
    libraries: Vec<Library>,
    /// Nur im alten Format (bis 1.12.2).
    install: Option<LegacyInstall>,
    version_info: Option<serde_json::Value>,
}

#[derive(Debug, Deserialize)]
struct SidedValue {
    client: Option<String>,
}

#[derive(Debug, Deserialize)]
struct Processor {
    sides: Option<Vec<String>>,
    jar: String,
    #[serde(default)]
    classpath: Vec<String>,
    #[serde(default)]
    args: Vec<String>,
    #[serde(default)]
    outputs: HashMap<String, String>,
}

impl Processor {
    fn runs_on_client(&self) -> bool {
        self.sides.as_ref().is_none_or(|sides| sides.iter().any(|s| s == "client"))
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LegacyInstall {
    /// Maven-Koordinate, unter der das Universal-Jar abgelegt wird.
    path: String,
    /// Name des Universal-Jars im Installer.
    file_path: String,
    minecraft: String,
}

/// Library im alten `versionInfo`: nur Name, optionale Repo-Basis und eine
/// Liste erlaubter Prüfsummen (Jar und gepackte Variante).
#[derive(Debug, Deserialize)]
struct LegacyLibrary {
    name: String,
    url: Option<String>,
    #[serde(default)]
    checksums: Vec<String>,
}

/// Wert aus `install_profile.data` für die Client-Seite.
#[derive(Debug, PartialEq, Eq)]
enum DataValue {
    /// `[group:artifact:version]` → Datei unter `libraries/` (relativer Pfad).
    Library(String),
    /// `'literal'`
    Literal(String),
    /// Eintrag im Installer-Jar, der entpackt werden muss.
    InstallerEntry(String),
}

fn parse_data_value(raw: &str) -> Result<DataValue> {
    if let Some(coord) = raw.strip_prefix('[').and_then(|r| r.strip_suffix(']')) {
        return Ok(DataValue::Library(MavenCoord::parse(coord)?.path()));
    }
    if raw.len() >= 2
        && let Some(literal) = raw.strip_prefix('\'').and_then(|r| r.strip_suffix('\''))
    {
        return Ok(DataValue::Literal(literal.to_owned()));
    }
    let entry = raw.trim_start_matches('/');
    if is_safe_rel_path(entry) {
        Ok(DataValue::InstallerEntry(entry.to_owned()))
    } else {
        Err(Error::launch("Das Installer-Profil enthält einen ungültigen Dateiverweis."))
    }
}

fn is_safe_rel_path(path: &str) -> bool {
    !path.is_empty()
        && !path.starts_with('/')
        && !path.contains('\\')
        && !path.contains(':')
        && path.split('/').all(|seg| !seg.is_empty() && seg != "." && seg != "..")
}

fn library_file(paths: &Paths, rel: &str) -> PathBuf {
    rel.split('/').fold(paths.libraries_dir(), |p, seg| p.join(seg))
}

/// Ersetzt `{KEY}` aus der Data-Map. Unbekannte Schlüssel sind ein Fehler –
/// ein Processor mit halb ersetzten Argumenten würde Unsinn schreiben.
fn substitute_tokens(template: &str, data: &HashMap<String, String>) -> Result<String> {
    let mut out = String::with_capacity(template.len());
    let mut rest = template;
    while let Some(start) = rest.find('{') {
        out.push_str(&rest[..start]);
        let Some(len) = rest[start + 1..].find('}') else {
            return Err(Error::launch("Das Installer-Profil enthält ein fehlerhaftes Argument."));
        };
        let key = &rest[start + 1..start + 1 + len];
        let value = data.get(key).ok_or_else(|| {
            tracing::error!("Unbekannter Platzhalter im Installer-Profil: {{{key}}}");
            Error::launch("Das Installer-Profil verwendet einen unbekannten Platzhalter.")
        })?;
        out.push_str(value);
        rest = &rest[start + 2 + len..];
    }
    out.push_str(rest);
    Ok(out)
}

/// Processor-Argument: `[coord]` → Library-Pfad, sonst `{KEY}`-Ersetzung.
fn resolve_arg(arg: &str, data: &HashMap<String, String>, paths: &Paths) -> Result<String> {
    if let Some(coord) = arg.strip_prefix('[').and_then(|r| r.strip_suffix(']')) {
        let rel = MavenCoord::parse(coord)?.path();
        return Ok(library_file(paths, &rel).display().to_string());
    }
    substitute_tokens(arg, data)
}

/// `outputs`: Datei → erwartete SHA1 (als `'literal'` oder `{KEY}`).
fn resolve_outputs(
    outputs: &HashMap<String, String>,
    data: &HashMap<String, String>,
    paths: &Paths,
) -> Result<Vec<(PathBuf, String)>> {
    outputs
        .iter()
        .map(|(file, sha1)| {
            let file = PathBuf::from(resolve_arg(file, data, paths)?);
            let sha1 = resolve_arg(sha1, data, paths)?;
            Ok((file, sha1.trim_matches('\'').to_ascii_lowercase()))
        })
        .collect()
}

/// Nur wenn es Outputs gibt und ALLE mit passender SHA1 vorhanden sind, darf
/// der Processor übersprungen werden.
async fn outputs_up_to_date(outputs: &[(PathBuf, String)]) -> bool {
    if outputs.is_empty() {
        return false;
    }
    for (file, expected) in outputs {
        if !download::sha1_of_file(file).await.is_ok_and(|actual| actual.eq_ignore_ascii_case(expected)) {
            return false;
        }
    }
    true
}

/// `Main-Class` aus einem Jar-Manifest. Lange Werte werden im Manifest
/// umbrochen; Folgezeilen beginnen mit einem Leerzeichen.
fn parse_main_class(manifest: &str) -> Option<String> {
    let mut lines: Vec<String> = Vec::new();
    for raw in manifest.lines() {
        // Der Hauptabschnitt endet an der ersten Leerzeile.
        if raw.is_empty() {
            break;
        }
        match (raw.strip_prefix(' '), lines.last_mut()) {
            (Some(continued), Some(last)) => last.push_str(continued),
            _ => lines.push(raw.to_owned()),
        }
    }
    lines
        .iter()
        .find_map(|l| l.strip_prefix("Main-Class:"))
        .map(|v| v.trim().to_owned())
        .filter(|v| !v.is_empty() && v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '$')))
}

/// Processors brauchen die Konsolen-Variante: `java.exe` neben `javaw.exe`.
fn console_java(java: &Path) -> PathBuf {
    let is_javaw = java.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.eq_ignore_ascii_case("javaw.exe"));
    if is_javaw {
        let console = java.with_file_name("java.exe");
        if console.is_file() {
            return console;
        }
    }
    java.to_owned()
}

// --- Zip-Helfer (blockierend, laufen in `spawn_blocking`) ---------------------

fn corrupt_installer() -> Error {
    Error::launch("Der Modloader-Installer ist beschädigt. Bitte erneut versuchen.")
}

fn open_archive(path: &Path) -> Result<zip::ZipArchive<std::fs::File>> {
    let file = std::fs::File::open(path).map_err(|e| Error::io(path, e))?;
    zip::ZipArchive::new(file).map_err(|e| {
        tracing::error!("Archiv {} nicht lesbar: {e}", path.display());
        corrupt_installer()
    })
}

fn read_text_entry(archive: &mut zip::ZipArchive<std::fs::File>, name: &str) -> Result<Option<String>> {
    let entry = match archive.by_name(name) {
        Ok(entry) => entry,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(e) => {
            tracing::error!("Archiv-Eintrag {name} nicht lesbar: {e}");
            return Err(corrupt_installer());
        }
    };
    let mut text = String::new();
    entry.take(MAX_TEXT_ENTRY).read_to_string(&mut text).map_err(|e| {
        tracing::error!("Archiv-Eintrag {name} nicht lesbar: {e}");
        corrupt_installer()
    })?;
    Ok(Some(text))
}

/// Schreibt über eine Temp-Datei, damit ein Abbruch keine halbe Datei hinterlässt.
fn write_entry_atomic(entry: &mut impl Read, dest: &Path) -> Result<()> {
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
    }
    let tmp = dest.with_extension(format!("part-{}", uuid::Uuid::new_v4().simple()));
    let result = (|| {
        let mut out = std::fs::File::create(&tmp).map_err(|e| Error::io(&tmp, e))?;
        std::io::copy(entry, &mut out).map_err(|e| Error::io(&tmp, e))?;
        drop(out);
        std::fs::rename(&tmp, dest).map_err(|e| Error::io(dest, e))
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    result
}

struct InstallerContents {
    profile: InstallProfile,
    /// Inhalt des per `json` benannten Eintrags (modernes Format).
    version_json: Option<String>,
}

fn read_installer(installer: &Path) -> Result<InstallerContents> {
    let mut archive = open_archive(installer)?;
    let raw = read_text_entry(&mut archive, "install_profile.json")?.ok_or_else(corrupt_installer)?;
    let profile: InstallProfile = serde_json::from_str(&raw).map_err(|e| Error::json("install_profile.json", e))?;

    let version_json = match &profile.json {
        Some(name) => {
            let name = name.trim_start_matches('/');
            if !is_safe_rel_path(name) {
                return Err(corrupt_installer());
            }
            Some(read_text_entry(&mut archive, name)?.ok_or_else(corrupt_installer)?)
        }
        None => None,
    };
    Ok(InstallerContents { profile, version_json })
}

/// Entpackt `maven/**` aus dem Installer nach `libraries/`.
fn extract_maven(installer: &Path, libraries_dir: &Path) -> Result<()> {
    let mut archive = open_archive(installer)?;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|_| corrupt_installer())?;
        // `enclosed_name` verhindert Zip-Slip.
        let Some(name) = entry.enclosed_name() else { continue };
        let Ok(rel) = name.strip_prefix("maven") else { continue };
        if entry.is_dir() || rel.as_os_str().is_empty() {
            continue;
        }
        let dest = libraries_dir.join(rel);
        if dest.metadata().is_ok_and(|m| m.is_file() && m.len() == entry.size()) {
            continue;
        }
        write_entry_atomic(&mut entry, &dest)?;
    }
    Ok(())
}

fn extract_entry(installer: &Path, name: &str, dest: &Path) -> Result<()> {
    let mut archive = open_archive(installer)?;
    let mut entry = archive.by_name(name).map_err(|e| {
        tracing::error!("Installer-Eintrag {name} fehlt: {e}");
        corrupt_installer()
    })?;
    if dest.metadata().is_ok_and(|m| m.is_file() && m.len() == entry.size()) {
        return Ok(());
    }
    write_entry_atomic(&mut entry, dest)
}

fn read_jar_main_class(jar: &Path) -> Result<Option<String>> {
    let mut archive = open_archive(jar)?;
    Ok(read_text_entry(&mut archive, "META-INF/MANIFEST.MF")?.as_deref().and_then(parse_main_class))
}

async fn blocking<T: Send + 'static>(f: impl FnOnce() -> Result<T> + Send + 'static) -> Result<T> {
    tokio::task::spawn_blocking(f).await.map_err(|e| Error::Internal(e.to_string()))?
}

// --- Marker ------------------------------------------------------------------

/// Hält fest, dass eine Installation vollständig durchgelaufen ist – samt der
/// Dateien, die sie erzeugt hat. Fehlt später eine davon, wird neu installiert.
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstallMarker {
    format: u32,
    profile_id: String,
    files: Vec<MarkerFile>,
}

#[derive(Debug, Serialize, Deserialize)]
struct MarkerFile {
    /// Relativ zu `libraries/`.
    path: String,
    size: u64,
}

fn marker_path(paths: &Paths, installer: &InstallerRef) -> PathBuf {
    library_file(paths, &installer.rel_dir()).join(MARKER_FILE)
}

/// Liefert das gecachte Profil, wenn die Installation vollständig ist.
async fn installed_profile(paths: &Paths, marker_file: &Path) -> Option<VersionInfo> {
    let marker: InstallMarker = fsutil::read_json(marker_file).await.ok().flatten()?;
    if marker.format != MARKER_FORMAT || !is_safe_id(&marker.profile_id) {
        return None;
    }
    for file in &marker.files {
        if !is_safe_rel_path(&file.path) {
            return None;
        }
        let meta = tokio::fs::metadata(library_file(paths, &file.path)).await.ok()?;
        if !meta.is_file() || meta.len() != file.size {
            return None;
        }
    }
    let profile: VersionInfo = fsutil::read_json(&paths.version_json(&marker.profile_id)).await.ok().flatten()?;
    (profile.id == marker.profile_id).then_some(profile)
}

async fn write_marker(paths: &Paths, marker_file: &Path, profile_id: &str, tracked: Vec<String>) -> Result<()> {
    let mut files = Vec::with_capacity(tracked.len());
    let mut seen = std::collections::HashSet::new();
    for path in tracked {
        if !seen.insert(path.clone()) {
            continue;
        }
        // Zwischenprodukte, die ein Processor nicht angelegt hat, sind kein Fehler.
        if let Ok(meta) = tokio::fs::metadata(library_file(paths, &path)).await
            && meta.is_file()
        {
            files.push(MarkerFile { path, size: meta.len() });
        }
    }
    let marker = InstallMarker { format: MARKER_FORMAT, profile_id: profile_id.to_owned(), files };
    fsutil::write_json(marker_file, &marker).await
}

// --- Installation ------------------------------------------------------------

/// Stellt sicher, dass Forge/NeoForge für die Spielversion installiert ist, und
/// liefert das Loader-Profil (noch nicht mit Vanilla gemergt).
pub async fn ensure_installed(
    ctx: &InstallContext<'_>,
    on_progress: &(dyn Fn(InstallProgress) + Sync),
) -> Result<VersionInfo> {
    let _guard = INSTALL_LOCK.lock().await;
    let report = |percent: f64, done: u64, total: u64| on_progress(InstallProgress { percent, done, total });
    report(0.0, 0, 0);

    let installer = resolve_installer(ctx).await?;
    let marker_file = marker_path(ctx.paths, &installer);
    if let Some(profile) = installed_profile(ctx.paths, &marker_file).await {
        report(100.0, 0, 0);
        return Ok(profile);
    }

    tracing::info!("Installiere {:?} {} für Minecraft {}", ctx.loader.kind, installer.version, ctx.game_version);
    // Ein alter Marker gilt nicht mehr, sobald wir an den Dateien arbeiten.
    let _ = tokio::fs::remove_file(&marker_file).await;

    let installer_jar = download_installer(ctx, &installer, &report).await?;
    let contents = {
        let jar = installer_jar.clone();
        blocking(move || read_installer(&jar)).await?
    };

    let (profile, tracked) = match contents {
        InstallerContents { profile, version_json: Some(version_json) } => {
            install_modern(ctx, &installer_jar, profile, &version_json, &report).await?
        }
        InstallerContents { profile: InstallProfile { install: Some(install), version_info: Some(info), .. }, .. } => {
            install_legacy(ctx, &installer_jar, install, info, &report).await?
        }
        _ => return Err(Error::launch("Dieses Installer-Format wird nicht unterstützt.")),
    };

    // Für Offline-Starts cachen; der Marker kommt zuletzt.
    fsutil::write_json(&ctx.paths.version_json(&profile.id), &profile).await?;
    write_marker(ctx.paths, &marker_file, &profile.id, tracked).await?;
    report(100.0, 0, 0);
    Ok(profile)
}

async fn download_installer(
    ctx: &InstallContext<'_>,
    installer: &InstallerRef,
    report: &(dyn Fn(f64, u64, u64) + Sync),
) -> Result<PathBuf> {
    let path = library_file(ctx.paths, &installer.rel_path());
    if path.is_file() {
        return Ok(path);
    }
    let url = installer.url();
    let sha1 = fetch_sha1(ctx.http, &url).await;
    if sha1.is_none() {
        tracing::warn!("Keine Prüfsumme für den Installer verfügbar");
    }
    let task = Task { url, path: path.clone(), sha1, size: None };
    download::fetch_all(ctx.http, vec![task], 1, &|p: Progress| {
        report(p.percent() / 100.0 * PERCENT_INSTALLER, p.done_files, p.total_files);
    })
    .await
    .map_err(|e| {
        tracing::error!("Installer-Download fehlgeschlagen: {e}");
        Error::launch(
            "Der Modloader-Installer konnte nicht heruntergeladen werden. \
             Bitte Internetverbindung und Loader-Version prüfen.",
        )
    })?;
    Ok(path)
}

/// Maven-Repos legen neben jede Datei eine `.sha1`.
async fn fetch_sha1(http: &reqwest::Client, url: &str) -> Option<String> {
    let text = http.get(format!("{url}.sha1")).send().await.ok()?.error_for_status().ok()?.text().await.ok()?;
    let hash = text.trim().get(..40)?;
    hash.bytes().all(|b| b.is_ascii_hexdigit()).then(|| hash.to_ascii_lowercase())
}

fn parse_profile(json: &str, game_version: &str) -> Result<VersionInfo> {
    let profile: VersionInfo = serde_json::from_str(json).map_err(|e| Error::json("Loader-Profil", e))?;
    if !is_safe_id(&profile.id) {
        return Err(Error::launch("Das Loader-Profil enthält eine ungültige ID."));
    }
    if profile.inherits_from.as_deref().is_some_and(|parent| parent != game_version) {
        return Err(Error::launch("Der Modloader-Installer passt nicht zur Minecraft-Version."));
    }
    Ok(profile)
}

async fn install_modern(
    ctx: &InstallContext<'_>,
    installer_jar: &Path,
    install: InstallProfile,
    version_json: &str,
    report: &(dyn Fn(f64, u64, u64) + Sync),
) -> Result<(VersionInfo, Vec<String>)> {
    let paths = ctx.paths;
    if install.minecraft.as_deref().is_some_and(|mc| mc != ctx.game_version) {
        return Err(Error::launch("Der Modloader-Installer passt nicht zur Minecraft-Version."));
    }
    let profile = parse_profile(version_json, ctx.game_version)?;

    {
        let (jar, libraries_dir) = (installer_jar.to_owned(), paths.libraries_dir());
        blocking(move || extract_maven(&jar, &libraries_dir)).await?;
    }

    // Libraries für die Processors. Einträge ohne URL stecken im Installer
    // (`maven/`) und müssen nach dem Entpacken vorhanden sein.
    let mut tracked = Vec::new();
    let mut tasks = Vec::new();
    for lib in &install.libraries {
        for resolved in lib.resolve(&Features::default())? {
            let path = library_file(paths, &resolved.path);
            if resolved.url.is_empty() {
                if !path.is_file() {
                    tracing::error!("Im Installer fehlt die Library {}", resolved.path);
                    return Err(corrupt_installer());
                }
                tracked.push(resolved.path);
            } else {
                tasks.push(Task { url: resolved.url, path, sha1: resolved.sha1, size: resolved.size });
            }
        }
    }
    download::fetch_all(ctx.http, tasks, ctx.concurrency, &|p: Progress| {
        let span = PERCENT_LIBRARIES - PERCENT_INSTALLER;
        report(PERCENT_INSTALLER + p.percent() / 100.0 * span, p.done_files, p.total_files);
    })
    .await?;

    let processors: Vec<&Processor> = install.processors.iter().filter(|p| p.runs_on_client()).collect();
    if !processors.is_empty() {
        let work_dir = paths.meta_dir().join("loader-install").join(&profile.id);
        let data = build_data_map(ctx, installer_jar, &install.data, &work_dir, &mut tracked).await?;
        let java = console_java(ctx.java);

        let total = processors.len() as u64;
        for (index, processor) in processors.iter().enumerate() {
            let done = index as u64;
            report(PERCENT_LIBRARIES + (100.0 - PERCENT_LIBRARIES) * done as f64 / total as f64, done, total);
            run_processor(paths, &java, &work_dir, processor, &data).await?;
        }
        report(100.0, total, total);
        let _ = tokio::fs::remove_dir_all(&work_dir).await;
    }

    // Manche Profil-Libraries entstehen erst durch die Processors (z. B. der
    // gepatchte Client) und haben deshalb keine URL.
    for lib in &profile.libraries {
        for resolved in lib.resolve(&Features::default())? {
            if resolved.url.is_empty() {
                if !library_file(paths, &resolved.path).is_file() {
                    tracing::error!("Nach der Installation fehlt die Library {}", resolved.path);
                    return Err(Error::launch("Die Modloader-Installation ist unvollständig. Bitte erneut versuchen."));
                }
                tracked.push(resolved.path);
            }
        }
    }
    Ok((profile, tracked))
}

/// Baut die Platzhalter für die Processors. Library-Verweise landen zusätzlich
/// in `tracked`, damit der Marker die erzeugten Dateien kennt.
async fn build_data_map(
    ctx: &InstallContext<'_>,
    installer_jar: &Path,
    entries: &HashMap<String, SidedValue>,
    work_dir: &Path,
    tracked: &mut Vec<String>,
) -> Result<HashMap<String, String>> {
    let paths = ctx.paths;
    let mut data = HashMap::from([
        ("SIDE".to_owned(), "client".to_owned()),
        ("MINECRAFT_JAR".to_owned(), ctx.client_jar.display().to_string()),
        ("MINECRAFT_VERSION".to_owned(), ctx.game_version.to_owned()),
        ("ROOT".to_owned(), paths.root().display().to_string()),
        ("INSTALLER".to_owned(), installer_jar.display().to_string()),
        ("LIBRARY_DIR".to_owned(), paths.libraries_dir().display().to_string()),
    ]);

    for (key, value) in entries {
        let Some(raw) = value.client.as_deref() else { continue };
        let resolved = match parse_data_value(raw)? {
            DataValue::Library(rel) => {
                let file = library_file(paths, &rel);
                tracked.push(rel);
                file.display().to_string()
            }
            DataValue::Literal(literal) => literal,
            DataValue::InstallerEntry(entry) => {
                let dest = entry.split('/').fold(work_dir.to_owned(), |p, seg| p.join(seg));
                let (jar, target) = (installer_jar.to_owned(), dest.clone());
                blocking(move || extract_entry(&jar, &entry, &target)).await?;
                dest.display().to_string()
            }
        };
        data.insert(key.clone(), resolved);
    }
    Ok(data)
}

async fn run_processor(
    paths: &Paths,
    java: &Path,
    work_dir: &Path,
    processor: &Processor,
    data: &HashMap<String, String>,
) -> Result<()> {
    let outputs = resolve_outputs(&processor.outputs, data, paths)?;
    if outputs_up_to_date(&outputs).await {
        tracing::debug!("Processor {} übersprungen – Ausgaben sind aktuell", processor.jar);
        return Ok(());
    }

    let jar = library_file(paths, &MavenCoord::parse(&processor.jar)?.path());
    let mut classpath = vec![jar.clone()];
    for coord in &processor.classpath {
        classpath.push(library_file(paths, &MavenCoord::parse(coord)?.path()));
    }
    if let Some(missing) = classpath.iter().find(|p| !p.is_file()) {
        tracing::error!("Processor-Library fehlt: {}", missing.display());
        return Err(Error::launch("Die Modloader-Installation ist unvollständig. Bitte erneut versuchen."));
    }

    let main_class = {
        let jar = jar.clone();
        blocking(move || read_jar_main_class(&jar)).await?
    }
    .ok_or_else(|| {
        tracing::error!("Processor {} hat keine Main-Class", processor.jar);
        corrupt_installer()
    })?;

    let args = processor.args.iter().map(|a| resolve_arg(a, data, paths)).collect::<Result<Vec<_>>>()?;
    let classpath = classpath.iter().map(|p| p.display().to_string()).collect::<Vec<_>>().join(";");

    fsutil::ensure_dir(work_dir).await?;
    tracing::info!("Starte Processor {} ({main_class})", processor.jar);
    let output = tokio::process::Command::new(java)
        .arg("-cp")
        .arg(&classpath)
        .arg(&main_class)
        .args(&args)
        .current_dir(work_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .creation_flags(CREATE_NO_WINDOW)
        // Bricht der Start ab, soll kein verwaister Java-Prozess weiterlaufen.
        .kill_on_drop(true)
        .output()
        .await
        .map_err(|e| {
            tracing::error!("Java für Processor nicht startbar ({}): {e}", java.display());
            Error::launch("Java konnte für die Modloader-Installation nicht gestartet werden.")
        })?;

    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);
    for line in stdout.lines().chain(stderr.lines()) {
        tracing::trace!("[{}] {line}", processor.jar);
    }

    let failed = Error::launch("Die Modloader-Installation ist fehlgeschlagen. Details stehen im Launcher-Log.");
    if !output.status.success() {
        tracing::error!("Processor {} endete mit {}", processor.jar, output.status);
        let lines: Vec<&str> = stdout.lines().chain(stderr.lines()).collect();
        for line in lines.iter().skip(lines.len().saturating_sub(40)) {
            tracing::error!("[{}] {line}", processor.jar);
        }
        return Err(failed);
    }
    if !outputs.is_empty() && !outputs_up_to_date(&outputs).await {
        tracing::error!("Processor {} hat unerwartete Ausgaben erzeugt (SHA1 stimmt nicht)", processor.jar);
        return Err(failed);
    }
    Ok(())
}

// --- Altes Format (bis 1.12.2) -----------------------------------------------

struct LegacyDownload {
    /// Kandidaten in Reihenfolge; alte Profile nennen nicht immer das richtige Repo.
    urls: Vec<String>,
    path: PathBuf,
    checksums: Vec<String>,
}

/// Alte Profile verweisen auf `files.minecraftforge.net/maven`, das heute nur
/// noch weiterleitet.
fn normalize_legacy_repo(url: &str) -> String {
    let https = url.replacen("http://", "https://", 1);
    let base = if https.starts_with("https://files.minecraftforge.net/maven") { FORGE_MAVEN.to_owned() } else { https };
    if base.ends_with('/') { base } else { format!("{base}/") }
}

/// Download-Kandidaten einer alten Library: genanntes Repo zuerst, dann das
/// jeweils andere der beiden üblichen.
fn legacy_candidates(lib: &LegacyLibrary, rel: &str) -> Vec<String> {
    let mut bases: Vec<String> = Vec::new();
    if let Some(url) = lib.url.as_deref().filter(|u| !u.is_empty()) {
        bases.push(normalize_legacy_repo(url));
    }
    for fallback in [MOJANG_LIBRARIES, FORGE_MAVEN] {
        if !bases.iter().any(|b| b == fallback) {
            bases.push(fallback.to_owned());
        }
    }
    bases.into_iter().map(|base| format!("{base}{rel}")).collect()
}

fn is_safe_file_name(name: &str) -> bool {
    is_safe_rel_path(name) && !name.contains('/')
}

async fn install_legacy(
    ctx: &InstallContext<'_>,
    installer_jar: &Path,
    install: LegacyInstall,
    version_info: serde_json::Value,
    report: &(dyn Fn(f64, u64, u64) + Sync),
) -> Result<(VersionInfo, Vec<String>)> {
    let paths = ctx.paths;
    if install.minecraft != ctx.game_version {
        return Err(Error::launch("Der Modloader-Installer passt nicht zur Minecraft-Version."));
    }

    let legacy_libs: Vec<LegacyLibrary> = version_info
        .get("libraries")
        .cloned()
        .map(serde_json::from_value)
        .transpose()
        .map_err(|e| Error::json("Loader-Profil", e))?
        .unwrap_or_default();
    let mut profile = parse_profile(&version_info.to_string(), ctx.game_version)?;
    // Ohne `inheritsFrom` (vor 1.7) bringt das Profil eigene Natives im ganz
    // alten Format mit – das unterstützen wir nicht.
    if profile.inherits_from.is_none() {
        return Err(Error::launch(
            "Forge wird für diese Minecraft-Version noch nicht unterstützt (erst ab 1.7).",
        ));
    }
    for lib in &mut profile.libraries {
        if let Some(url) = &lib.url {
            lib.url = Some(normalize_legacy_repo(url));
        }
    }

    // Das Universal-Jar steckt im Installer und gehört an seinen Maven-Pfad.
    let universal_rel = MavenCoord::parse(&install.path)?.path();
    if !is_safe_file_name(&install.file_path) {
        return Err(corrupt_installer());
    }
    {
        let (jar, entry, dest) =
            (installer_jar.to_owned(), install.file_path.clone(), library_file(paths, &universal_rel));
        blocking(move || extract_entry(&jar, &entry, &dest)).await?;
    }

    let mut tracked = vec![universal_rel.clone()];
    let mut downloads = Vec::new();
    for lib in &legacy_libs {
        let rel = MavenCoord::parse(&lib.name)?.path();
        if rel == universal_rel {
            continue;
        }
        let path = library_file(paths, &rel);
        if !path.is_file() {
            downloads.push(LegacyDownload {
                urls: legacy_candidates(lib, &rel),
                path,
                checksums: lib.checksums.clone(),
            });
        }
        tracked.push(rel);
    }

    let total = downloads.len() as u64;
    let done = AtomicU64::new(0);
    let tick = || {
        let done = done.load(AtomicOrdering::Relaxed);
        let share = if total == 0 { 1.0 } else { done as f64 / total as f64 };
        report(PERCENT_INSTALLER + (100.0 - PERCENT_INSTALLER) * share, done, total);
    };
    let tick: &(dyn Fn() + Sync) = &tick;
    tick();

    // Eager sammeln statt Closure im Stream – sonst ist das Future nicht `Send`.
    let jobs: Vec<_> = downloads.iter().map(|d| fetch_legacy_library(ctx.http, d, &done, tick)).collect();
    let mut results = futures::stream::iter(jobs).buffer_unordered(ctx.concurrency.max(1));
    while let Some(result) = results.next().await {
        result?;
    }
    drop(results);

    Ok((profile, tracked))
}

async fn fetch_legacy_library(
    http: &reqwest::Client,
    lib: &LegacyDownload,
    done: &AtomicU64,
    tick: &(dyn Fn() + Sync),
) -> Result<()> {
    let mut last_err = None;
    for url in &lib.urls {
        let task = Task { url: url.clone(), path: lib.path.clone(), sha1: None, size: None };
        match download::fetch_one(http, &task).await {
            Ok(()) => {
                if checksum_allowed(&lib.path, &lib.checksums).await {
                    done.fetch_add(1, AtomicOrdering::Relaxed);
                    tick();
                    return Ok(());
                }
                let _ = tokio::fs::remove_file(&lib.path).await;
                last_err = Some(Error::download(url, "Prüfsumme stimmt nicht"));
            }
            Err(e) => last_err = Some(e),
        }
    }
    Err(last_err.unwrap_or_else(|| Error::Internal("Library ohne Download-Kandidaten".into())))
}

/// Alte Profile listen mehrere gültige SHA1 (Jar und gepackte Variante).
async fn checksum_allowed(path: &Path, checksums: &[String]) -> bool {
    if checksums.is_empty() {
        return true;
    }
    download::sha1_of_file(path).await.is_ok_and(|actual| checksums.iter().any(|c| c.eq_ignore_ascii_case(&actual)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings(items: &[&str]) -> Vec<String> {
        items.iter().map(|s| (*s).to_owned()).collect()
    }

    fn test_paths() -> Paths {
        Paths::new(r"C:\trs")
    }

    #[test]
    fn version_ordering_is_numeric() {
        assert_eq!(cmp_versions("47.4.10", "47.4.9"), Ordering::Greater);
        assert_eq!(cmp_versions("21.1.251", "21.1.99"), Ordering::Greater);
        assert_eq!(cmp_versions("1.12.2-14.23.5.2859", "1.12.2-14.23.5.2864"), Ordering::Less);
        assert!(is_safe_version("1.7.10-10.13.4.1614-1.7.10"));
        assert!(is_safe_version("26.1.0.0-alpha.1+snapshot-1"));
        for bad in ["", "../x", "1.0/evil", "1 0", "a\\b", "1..2"] {
            assert!(!is_safe_version(bad), "{bad:?}");
        }
    }

    #[test]
    fn forge_promotions_prefer_recommended() {
        let promos: ForgePromotions = serde_json::from_str(
            r#"{"homepage":"x","promos":{"1.20.1-recommended":"47.4.10","1.20.1-latest":"47.4.23",
                "1.21.11-latest":"61.0.3","1.0-latest":"../evil"}}"#,
        )
        .unwrap();
        assert_eq!(pick_forge_promo(&promos.promos, "1.20.1").as_deref(), Some("47.4.10"));
        assert_eq!(pick_forge_promo(&promos.promos, "1.21.11").as_deref(), Some("61.0.3"));
        assert_eq!(pick_forge_promo(&promos.promos, "1.0"), None);
        assert_eq!(pick_forge_promo(&promos.promos, "1.19"), None);
    }

    #[test]
    fn forge_full_version_handles_triple_form() {
        let old = strings(&["1.7.10-10.13.4.1566-1.7.10", "1.7.10-10.13.4.1614-1.7.10", "1.7.10-10.13.0.1150"]);
        assert_eq!(
            forge_full_version(&old, "1.7.10", "10.13.4.1614").as_deref(),
            Some("1.7.10-10.13.4.1614-1.7.10")
        );
        assert_eq!(forge_full_version(&old, "1.7.10", "10.13.0.1150").as_deref(), Some("1.7.10-10.13.0.1150"));
        // Kein Präfix-Treffer auf eine andere Build-Nummer.
        assert_eq!(forge_full_version(&old, "1.7.10", "10.13.4.16"), None);

        let installer = InstallerRef::forge("1.7.10-10.13.4.1614-1.7.10".into());
        assert_eq!(
            installer.url(),
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-installer.jar"
        );
    }

    #[test]
    fn neoforge_version_selection() {
        assert_eq!(neoforge_prefix("1.21.1").as_deref(), Some("21.1."));
        assert_eq!(neoforge_prefix("1.21").as_deref(), Some("21.0."));
        assert_eq!(neoforge_prefix("26.1").as_deref(), Some("26.1.0."));
        assert_eq!(neoforge_prefix("26.1.2").as_deref(), Some("26.1.2."));
        assert_eq!(neoforge_prefix("1.12.2"), None);
        assert_eq!(neoforge_prefix("25w14craftmine"), None);

        let list: MavenVersions = serde_json::from_str(
            r#"{"isSnapshot":false,"versions":["20.2.3-beta","21.0.167","21.1.9","21.1.250","21.1.251",
                "21.1.252-beta","21.10.5","26.1.0.0-alpha.15+pre-3","26.1.0.19-beta","26.1.0.2-beta",
                "26.1.2.109","26.1.2.12-beta","26.3.0.7-beta","26.3.0.10-beta"]}"#,
        )
        .unwrap();
        let v = &list.versions;
        // Stabil schlägt Beta, numerisch statt alphabetisch, `21.1.` trifft nicht `21.10.`.
        assert_eq!(pick_neoforge(v, "1.21.1").as_deref(), Some("21.1.251"));
        assert_eq!(pick_neoforge(v, "1.21").as_deref(), Some("21.0.167"));
        assert_eq!(pick_neoforge(v, "1.20.2").as_deref(), Some("20.2.3-beta"));
        assert_eq!(pick_neoforge(v, "26.1.2").as_deref(), Some("26.1.2.109"));
        // Nur Betas vorhanden; Alphas/Snapshots nie.
        assert_eq!(pick_neoforge(v, "26.1").as_deref(), Some("26.1.0.19-beta"));
        assert_eq!(pick_neoforge(v, "26.3").as_deref(), Some("26.3.0.10-beta"));
        assert_eq!(pick_neoforge(v, "1.19.2"), None);

        let legacy = strings(&["1.20.1-47.1.5", "1.20.1-47.1.106", "1.20.1-47.1.99", "47.1.82"]);
        assert_eq!(pick_neoforge(&legacy, "1.20.1").as_deref(), Some("1.20.1-47.1.106"));

        assert_eq!(
            InstallerRef::neoforge("21.1.251".into()).url(),
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.251/neoforge-21.1.251-installer.jar"
        );
        assert_eq!(
            InstallerRef::neoforge_legacy("1.20.1-47.1.106".into()).rel_path(),
            "net/neoforged/forge/1.20.1-47.1.106/forge-1.20.1-47.1.106-installer.jar"
        );
    }

    #[test]
    fn data_values() {
        assert_eq!(
            parse_data_value("[de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412:mappings@txt]").unwrap(),
            DataValue::Library(
                "de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412-mappings.txt"
                    .into()
            )
        );
        assert_eq!(
            parse_data_value("'de86b035d2da0f78940796bb95c39a932ed84834'").unwrap(),
            DataValue::Literal("de86b035d2da0f78940796bb95c39a932ed84834".into())
        );
        assert_eq!(parse_data_value("''").unwrap(), DataValue::Literal(String::new()));
        assert_eq!(parse_data_value("/data/client.lzma").unwrap(), DataValue::InstallerEntry("data/client.lzma".into()));
        assert!(parse_data_value("/../evil").is_err());
        assert!(parse_data_value("/data/../../evil").is_err());
        assert!(parse_data_value("[../x:b:1]").is_err());
        assert!(parse_data_value("c:/windows/x").is_err());
    }

    #[test]
    fn argument_substitution() {
        let data = HashMap::from([
            ("ROOT".to_owned(), r"C:\trs".to_owned()),
            ("SIDE".to_owned(), "client".to_owned()),
            ("MC_SLIM_SHA".to_owned(), "abc".to_owned()),
        ]);
        let paths = test_paths();
        assert_eq!(resolve_arg("{ROOT}/libraries/", &data, &paths).unwrap(), r"C:\trs/libraries/");
        assert_eq!(resolve_arg("--side={SIDE}-{SIDE}", &data, &paths).unwrap(), "--side=client-client");
        assert_eq!(resolve_arg("--task", &data, &paths).unwrap(), "--task");
        assert_eq!(
            resolve_arg("[net.minecraftforge:forge:1.20.1-47.4.10:client]", &data, &paths).unwrap(),
            r"C:\trs\libraries\net\minecraftforge\forge\1.20.1-47.4.10\forge-1.20.1-47.4.10-client.jar"
        );
        assert!(resolve_arg("{UNKNOWN}", &data, &paths).is_err());
        assert!(resolve_arg("{ROOT", &data, &paths).is_err());
        assert!(resolve_arg("[a:b:1/../../x]", &data, &paths).is_err());

        let outputs = HashMap::from([("{ROOT}/out.jar".to_owned(), "{MC_SLIM_SHA}".to_owned())]);
        assert_eq!(
            resolve_outputs(&outputs, &data, &paths).unwrap(),
            vec![(PathBuf::from(r"C:\trs/out.jar"), "abc".to_owned())]
        );
        let literal = HashMap::from([("[a:b:1]".to_owned(), "'ABCDEF'".to_owned())]);
        assert_eq!(resolve_outputs(&literal, &data, &paths).unwrap()[0].1, "abcdef");
    }

    #[test]
    fn manifest_main_class() {
        let manifest = "Manifest-Version: 1.0\r\nMain-Class: net.minecraftforge.installertools.ConsoleTool\r\n\r\n\
                        Name: x/Y.class\r\nMain-Class: evil.Other\r\n";
        assert_eq!(parse_main_class(manifest).as_deref(), Some("net.minecraftforge.installertools.ConsoleTool"));

        // Umbrochene Zeile (72-Byte-Grenze des Manifest-Formats).
        let wrapped = "Manifest-Version: 1.0\nMain-Class: net.neoforged.installertools.binarypatcher.Cons\n oleTool\nMulti-Release: true\n";
        assert_eq!(
            parse_main_class(wrapped).as_deref(),
            Some("net.neoforged.installertools.binarypatcher.ConsoleTool")
        );

        assert_eq!(parse_main_class("Manifest-Version: 1.0\n"), None);
        assert_eq!(parse_main_class("Main-Class: \n"), None);
        assert_eq!(parse_main_class("Main-Class: a b;calc.exe\n"), None);
    }

    #[test]
    fn processor_sides() {
        let profile: InstallProfile = serde_json::from_str(
            r#"{"spec":1,"minecraft":"1.20.1","json":"/version.json","path":null,
                "data":{"BINPATCH":{"client":"/data/client.lzma","server":"/data/server.lzma"}},
                "processors":[
                  {"sides":["server"],"jar":"a:b:1","classpath":[],"args":[]},
                  {"jar":"a:b:1","classpath":["c:d:2"],"args":["--x","{BINPATCH}"]},
                  {"sides":["client"],"jar":"a:b:1","classpath":[],"args":[],"outputs":{"{X}":"{X_SHA}"}}],
                "libraries":[{"name":"a:b:1","downloads":{"artifact":{"path":"a/b/1/b-1.jar","url":"","sha1":"00","size":1}}}]}"#,
        )
        .unwrap();
        let client: Vec<bool> = profile.processors.iter().map(Processor::runs_on_client).collect();
        assert_eq!(client, [false, true, true]);
        assert_eq!(profile.data["BINPATCH"].client.as_deref(), Some("/data/client.lzma"));
        assert!(profile.install.is_none() && profile.version_info.is_none());

        // Libraries ohne URL werden aufgelöst, aber nicht heruntergeladen.
        let resolved = profile.libraries[0].resolve(&Features::default()).unwrap();
        assert!(resolved[0].url.is_empty());
    }

    const LEGACY_PROFILE: &str = r#"{
        "install":{"profileName":"forge","target":"1.12.2-forge1.12.2-14.23.5.2847",
            "path":"net.minecraftforge:forge:1.12.2-14.23.5.2847","version":"forge 1.12.2-14.23.5.2847",
            "filePath":"forge-1.12.2-14.23.5.2847-universal.jar","minecraft":"1.12.2"},
        "versionInfo":{"id":"1.12.2-forge1.12.2-14.23.5.2847","type":"release",
            "minecraftArguments":"--username ${auth_player_name} --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker",
            "mainClass":"net.minecraft.launchwrapper.Launch","inheritsFrom":"1.12.2","jar":"1.12.2","logging":{},
            "libraries":[
              {"name":"net.minecraftforge:forge:1.12.2-14.23.5.2847","url":"http://files.minecraftforge.net/maven/"},
              {"name":"net.minecraft:launchwrapper:1.12","serverreq":true},
              {"name":"org.jline:jline:3.5.1","url":"https://maven.minecraftforge.net/",
               "checksums":["51800e9d7a13608894a5a28eed0f5c7fa2f300fb"],"serverreq":true,"clientreq":false}]}}"#;

    #[test]
    fn legacy_profile() {
        let install: InstallProfile = serde_json::from_str(LEGACY_PROFILE).unwrap();
        assert!(install.json.is_none() && install.processors.is_empty());
        let legacy = install.install.unwrap();
        assert_eq!(legacy.file_path, "forge-1.12.2-14.23.5.2847-universal.jar");
        assert_eq!(
            MavenCoord::parse(&legacy.path).unwrap().path(),
            "net/minecraftforge/forge/1.12.2-14.23.5.2847/forge-1.12.2-14.23.5.2847.jar"
        );

        let info = install.version_info.unwrap();
        let profile = parse_profile(&info.to_string(), "1.12.2").unwrap();
        assert_eq!(profile.main_class.as_deref(), Some("net.minecraft.launchwrapper.Launch"));
        assert_eq!(profile.libraries.len(), 3);
        assert!(parse_profile(&info.to_string(), "1.12.1").is_err());

        let libs: Vec<LegacyLibrary> = serde_json::from_value(info["libraries"].clone()).unwrap();
        assert_eq!(libs[2].checksums, ["51800e9d7a13608894a5a28eed0f5c7fa2f300fb"]);
        // Genanntes Repo zuerst (altes Repo wird umgeschrieben), dann das andere.
        assert_eq!(
            legacy_candidates(&libs[0], "x/y.jar"),
            ["https://maven.minecraftforge.net/x/y.jar", "https://libraries.minecraft.net/x/y.jar"]
        );
        assert_eq!(
            legacy_candidates(&libs[1], "x/y.jar"),
            ["https://libraries.minecraft.net/x/y.jar", "https://maven.minecraftforge.net/x/y.jar"]
        );
        assert_eq!(normalize_legacy_repo("https://example.org/repo"), "https://example.org/repo/");

        assert!(is_safe_file_name("forge-1.7.10-universal.jar"));
        for bad in ["", "../x.jar", "a/b.jar", "c:x.jar", "a\\b.jar"] {
            assert!(!is_safe_file_name(bad), "{bad:?}");
        }
    }

    #[tokio::test]
    async fn outputs_skip_logic() {
        let dir = tempfile::tempdir().unwrap();
        let a = dir.path().join("a.jar");
        let b = dir.path().join("b.jar");
        tokio::fs::write(&a, b"hello").await.unwrap();
        let hello = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d".to_owned();

        // Ohne Outputs nie überspringen.
        assert!(!outputs_up_to_date(&[]).await);
        assert!(outputs_up_to_date(&[(a.clone(), hello.to_uppercase())]).await);
        // Eine fehlende oder falsche Datei reicht, damit der Processor läuft.
        assert!(!outputs_up_to_date(&[(a.clone(), hello.clone()), (b.clone(), hello.clone())]).await);
        tokio::fs::write(&b, b"other").await.unwrap();
        assert!(!outputs_up_to_date(&[(a.clone(), hello.clone()), (b, hello.clone())]).await);

        assert!(checksum_allowed(&a, &[]).await);
        assert!(checksum_allowed(&a, &["00".repeat(20), hello]).await);
        assert!(!checksum_allowed(&a, &["00".repeat(20)]).await);
    }

    #[tokio::test]
    async fn marker_detects_missing_files() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        let installer = InstallerRef::neoforge("21.1.251".into());
        let marker_file = marker_path(&paths, &installer);
        let rel = "net/neoforged/neoforge/21.1.251/neoforge-21.1.251-client.jar";
        let jar = library_file(&paths, rel);

        assert!(installed_profile(&paths, &marker_file).await.is_none());

        tokio::fs::create_dir_all(jar.parent().unwrap()).await.unwrap();
        tokio::fs::write(&jar, b"patched").await.unwrap();
        let profile = VersionInfo { id: "neoforge-21.1.251".into(), ..Default::default() };
        fsutil::write_json(&paths.version_json(&profile.id), &profile).await.unwrap();
        // Nicht vorhandene Zwischenprodukte landen nicht im Marker.
        write_marker(&paths, &marker_file, &profile.id, vec![rel.into(), rel.into(), "a/b/missing.jar".into()])
            .await
            .unwrap();
        assert_eq!(installed_profile(&paths, &marker_file).await.unwrap().id, "neoforge-21.1.251");

        // Geänderte Größe oder fehlende Datei → Neuinstallation.
        tokio::fs::write(&jar, b"kaputt").await.unwrap();
        assert!(installed_profile(&paths, &marker_file).await.is_none());
        tokio::fs::remove_file(&jar).await.unwrap();
        assert!(installed_profile(&paths, &marker_file).await.is_none());
    }

    #[test]
    fn console_java_falls_back_to_given_path() {
        let missing = Path::new(r"C:\does-not-exist\bin\javaw.exe");
        assert_eq!(console_java(missing), missing);
        let custom = Path::new(r"C:\jdk\bin\java.exe");
        assert_eq!(console_java(custom), custom);
    }
}
