//! Welt-Hosting mit Mods und Resource Pack – Seite des Launchers
//! (`api/API.md` §21.10, `docs/hosting-files.md`).
//!
//! * **Host:** Das Spiel fragt über den TRS-Link `hosting.mods`; der Launcher
//!   erkennt die Mods der Instanz selbst (SHA-1/SHA-512 bei Modrinth,
//!   Murmur2-Fingerprint bei CurseForge – der Schlüssel liegt nur hier) und
//!   antwortet mit Quelle + IDs je SHA-1 ([`Launcher::identify_instance_mods`]).
//! * **Gast:** Beim Beitreten zeigt die Oberfläche die Mod-Liste; mit
//!   [`Launcher::hosting_prepare`] legt der Launcher eine neue Instanz an bzw.
//!   ergänzt eine **Kopie** einer vorhandenen, lädt Store-Mods aus der
//!   offiziellen Quelle (Hash-Prüfung) und holt Dateien „direkt vom Host“ über
//!   eine eigene Relay-Verbindung ([`relay::FileChannel`], SHA-256 + Größe).
//!   Ohne „Ich vertraue diesem Host“ werden keine Host-Dateien übernommen.
//!
//! Geschrieben wird ausschließlich in den `mods`-Ordner der neuen bzw. kopierten
//! Instanz, unter einem gesäuberten Dateinamen (nie ein Pfad aus der Liste).

pub mod murmur;
pub mod relay;
#[cfg(test)]
mod tests;

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use sha2::Digest;

use crate::content::{self, ContentKind, Platform, Source};
use crate::instance::{Instance, Loader, LoaderKind, NewInstance};
use crate::link::LinkModSource;
use crate::trs_api::hosting::{HostingRoom, loaders_compatible, room_arg};
use crate::{Error, Launcher, Result, download};

/// Höchstens so viele Mods je Welt.
pub const MAX_MODS: usize = 300;
/// Eine Mod direkt vom Host.
pub const MAX_HOST_FILE: u64 = 64 * 1024 * 1024;
/// Alle Mods direkt vom Host zusammen.
pub const MAX_HOST_TOTAL: u64 = 512 * 1024 * 1024;
/// Eine Store-Mod.
pub const MAX_STORE_FILE: u64 = 512 * 1024 * 1024;
/// Resource Pack (Grenze wie Vanilla).
pub const MAX_PACK: u64 = 250 * 1024 * 1024;

// --- Liste aus der API ------------------------------------------------------------------

/// Woher ein Gast eine Mod bekommt.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ModSource {
    Modrinth,
    Curseforge,
    /// Direkt vom Host – nicht geprüft.
    Host,
    /// Weder im Store noch übertragen: „musst du selbst besorgen“.
    Manual,
}

impl ModSource {
    pub fn is_store(self) -> bool {
        matches!(self, Self::Modrinth | Self::Curseforge)
    }
}

/// Eine geteilte Mod (gesäubert, siehe [`SharedMod::checked`]).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SharedMod {
    pub name: String,
    #[serde(default)]
    pub version: String,
    pub file: String,
    pub size: u64,
    pub required: bool,
    pub source: ModSource,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub project_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub file_id: Option<String>,
    pub sha1: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sha512: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sha256: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fingerprint: Option<u32>,
}

/// Das geteilte Resource Pack (holt das Spiel selbst über den Hosting-Kanal).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SharedPack {
    pub name: String,
    pub size: u64,
    pub sha1: String,
    pub sha256: String,
}

/// Mod-Liste + Pack einer Welt (`GET /v1/hosting/rooms/{id}/content`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomContent {
    pub room_id: String,
    pub mods: Vec<SharedMod>,
    pub pack: Option<SharedPack>,
}

pub(crate) fn is_hex(s: &str, len: usize) -> bool {
    s.len() == len && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// Anzeigetext: eine Zeile, ohne Steuer-/Formatzeichen und `§`, höchstens `max` Zeichen.
pub(crate) fn shown(input: &str, max: usize) -> String {
    let cleaned: String = input
        .chars()
        .filter(|c| !c.is_control() && !is_format(*c) && *c != '§')
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    cleaned.chars().take(max).collect()
}

/// Unicode-Formatzeichen (Richtung, unsichtbar), die in Namen nichts verloren haben.
fn is_format(c: char) -> bool {
    matches!(c as u32, 0x200B..=0x200F | 0x202A..=0x202E | 0x2060..=0x2069 | 0xFEFF | 0x00AD | 0xFFF9..=0xFFFB | 0xE0000..=0xE007F)
}

/// Reiner Dateiname einer Mod wie in der API (kein Pfad, kein „..“, endet auf `.jar`).
pub fn valid_jar_name(f: &str) -> bool {
    let b = f.as_bytes();
    (5..=128).contains(&b.len())
        && f.ends_with(".jar")
        && b[0].is_ascii_alphanumeric()
        && !f.contains("..")
        && b.iter().all(|c| c.is_ascii_alphanumeric() || b" ._+()[]{}'!,&~@#$%=-".contains(c))
}

/// Dateiname für die Platte: nur `[A-Za-z0-9._+-]` (Rest → `_`), endet auf `.jar`.
pub fn safe_file_name(f: &str) -> Option<String> {
    let base = f.strip_suffix(".jar").unwrap_or(f);
    let mut out: String =
        base.chars().take(100).map(|c| if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '+' | '-') { c } else { '_' }).collect();
    while out.contains("..") {
        out = out.replace("..", "_");
    }
    let mut out = out.trim_start_matches(|c: char| !c.is_ascii_alphanumeric()).to_owned();
    if out.is_empty() {
        return None;
    }
    // Windows-Gerätenamen (auch mit Endung) nie als Dateiname.
    let stem = out.split('.').next().unwrap_or_default().to_ascii_uppercase();
    let reserved = matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL" | "CONIN$" | "CONOUT$")
        || ((stem.starts_with("COM") || stem.starts_with("LPT")) && stem.len() == 4 && stem.as_bytes()[3].is_ascii_digit());
    if reserved {
        out = format!("mod-{out}");
    }
    let name = format!("{out}.jar");
    (Path::new(&name).components().count() == 1).then_some(name)
}

impl SharedMod {
    /// Gleiche Regeln wie die API (`hostingModEntry`); `None` = verwerfen.
    pub fn checked(self) -> Option<Self> {
        let name = shown(&self.name, 64);
        let version = shown(&self.version, 64);
        if name.is_empty() || !valid_jar_name(&self.file) || !is_hex(&self.sha1, 40) {
            return None;
        }
        let max = if self.source == ModSource::Host { MAX_HOST_FILE } else { MAX_STORE_FILE };
        if self.size == 0 || self.size > max {
            return None;
        }
        if self.sha512.as_deref().is_some_and(|h| !is_hex(h, 128)) || self.sha256.as_deref().is_some_and(|h| !is_hex(h, 64)) {
            return None;
        }
        let id = |v: &Option<String>, len: std::ops::RangeInclusive<usize>, digits: bool| {
            v.as_deref().is_some_and(|s| {
                len.contains(&s.len()) && s.bytes().all(|b| if digits { b.is_ascii_digit() } else { b.is_ascii_alphanumeric() })
            })
        };
        match self.source {
            ModSource::Modrinth => {
                if !id(&self.project_id, 8..=8, false) || !id(&self.file_id, 8..=8, false) || self.sha512.is_none() {
                    return None;
                }
            }
            ModSource::Curseforge => {
                if !id(&self.project_id, 1..=10, true) || !id(&self.file_id, 1..=10, true) {
                    return None;
                }
            }
            ModSource::Host | ModSource::Manual => {
                if self.project_id.is_some() || self.file_id.is_some() || (self.source == ModSource::Host && self.sha256.is_none()) {
                    return None;
                }
            }
        }
        Some(Self { name, version, ..self })
    }
}

impl SharedPack {
    pub fn checked(self) -> Option<Self> {
        let name = shown(&self.name, 64);
        (!name.is_empty() && (1..=MAX_PACK).contains(&self.size) && is_hex(&self.sha1, 40) && is_hex(&self.sha256, 64))
            .then_some(Self { name, ..self })
    }
}

/// Rohantwort der API – jeder Eintrag einzeln geprüft (ein kaputter macht nicht alles kaputt).
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiContent {
    #[serde(default)]
    pub room_id: String,
    #[serde(default)]
    pub mods: Vec<serde_json::Value>,
    #[serde(default)]
    pub pack: Option<serde_json::Value>,
}

impl ApiContent {
    /// Säubern: ungültige/doppelte Einträge fallen weg, höchstens 300 Mods, Host-Summe ≤ 512 MB.
    pub(crate) fn cleaned(self, room_id: &str) -> Option<RoomContent> {
        if self.room_id != room_id {
            return None;
        }
        let mut seen = HostHashes::default();
        let mut mods = Vec::new();
        for raw in self.mods {
            if mods.len() >= MAX_MODS {
                break;
            }
            let Some(m) = serde_json::from_value::<SharedMod>(raw).ok().and_then(SharedMod::checked) else { continue };
            if !seen.sha1.insert(m.sha1.clone()) {
                continue;
            }
            if m.source == ModSource::Host {
                if seen.host_total + m.size > MAX_HOST_TOTAL {
                    continue;
                }
                seen.host_total += m.size;
            }
            mods.push(m);
        }
        let pack = self.pack.and_then(|p| serde_json::from_value::<SharedPack>(p).ok()).and_then(SharedPack::checked);
        Some(RoomContent { room_id: self.room_id, mods, pack })
    }
}

#[derive(Default)]
struct HostHashes {
    sha1: HashSet<String>,
    host_total: u64,
}

// --- Gast: Plan -------------------------------------------------------------------------

/// Neue Instanz oder vorhandene als Kopie ergänzen (das Original bleibt unverändert).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PrepareMode {
    New,
    Copy,
}

/// Wahl des Gasts im Dialog (vom Webview – wird gegen die frische Liste geprüft).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PreparePlan {
    pub room_id: String,
    pub mode: PrepareMode,
    #[serde(default)]
    pub base_instance_id: Option<String>,
    #[serde(default)]
    pub name: Option<String>,
    /// SHA-1 der gewählten Mods.
    #[serde(default)]
    pub mods: Vec<String>,
    /// Häkchen „Ich vertraue diesem Host“ (bei jedem Beitritt neu).
    #[serde(default)]
    pub trust_host: bool,
}

/// Was installiert wird – Pflicht-Mods immer, optionale nach Wahl; Host-Dateien nur mit Vertrauen.
pub fn selection<'a>(content: &'a RoomContent, plan: &PreparePlan) -> Result<Vec<&'a SharedMod>> {
    let wanted: HashSet<&str> = plan.mods.iter().map(String::as_str).collect();
    let mut out = Vec::new();
    for m in &content.mods {
        if m.source == ModSource::Manual {
            continue;
        }
        if m.required || wanted.contains(m.sha1.as_str()) {
            out.push(m);
        }
    }
    let host: u64 = out.iter().filter(|m| m.source == ModSource::Host).map(|m| m.size).sum();
    if host > 0 && !plan.trust_host {
        return Err(Error::validation(crate::msg!(
            "hosting.trustRequired",
            "Diese Mods kommen direkt vom Host und wurden nicht geprüft. Bestätige erst „Ich vertraue diesem Host“."
        )));
    }
    if host > MAX_HOST_TOTAL {
        return Err(Error::validation(crate::msg!("hosting.hostFilesTooLarge", "Die Dateien vom Host sind zu groß (höchstens 512 MB).")));
    }
    Ok(out)
}

/// Fortschritt von [`Launcher::hosting_prepare`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PrepareProgress {
    /// `instance`, `store`, `host` oder `done`.
    pub step: &'static str,
    pub done: u32,
    pub total: u32,
    pub name: Option<String>,
    pub bytes: u64,
    pub total_bytes: u64,
}

/// Ergebnis: die Instanz, mit der das Spiel startet.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PrepareResult {
    pub instance_id: String,
    pub installed: u32,
    pub already: u32,
}

type ProgressFn<'a> = &'a (dyn Fn(PrepareProgress) + Send + Sync);

fn progress(p: ProgressFn<'_>, step: &'static str, done: usize, total: usize, name: Option<&str>, bytes: u64, total_bytes: u64) {
    p(PrepareProgress {
        step,
        done: done as u32,
        total: total as u32,
        name: name.map(str::to_owned),
        bytes,
        total_bytes,
    });
}

/// SHA-1 aller aktiven Mod-JARs eines Ordners (für „vorhanden“ im Dialog).
pub async fn jar_hashes(dir: &Path) -> Result<Vec<(PathBuf, String)>> {
    let mut out = Vec::new();
    let Ok(mut rd) = tokio::fs::read_dir(dir).await else { return Ok(out) };
    while let Ok(Some(entry)) = rd.next_entry().await {
        let path = entry.path();
        let is_jar = path.extension().is_some_and(|e| e == "jar");
        let hidden = path.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.starts_with('.'));
        if !is_jar || hidden || !entry.file_type().await.is_ok_and(|t| t.is_file()) {
            continue;
        }
        if let Ok(sha1) = download::sha1_of_file(&path).await {
            out.push((path, sha1));
        }
        if out.len() >= 500 {
            break;
        }
    }
    Ok(out)
}

/// Rechnet SHA-512 einer Datei.
async fn sha512_of_file(path: &Path) -> Result<String> {
    let path = path.to_owned();
    tokio::task::spawn_blocking(move || {
        let data = std::fs::read(&path).map_err(|e| Error::io(&path, e))?;
        Ok(relay::hex(&sha2::Sha512::digest(&data)))
    })
    .await
    .map_err(|e| Error::Internal(e.to_string()))?
}

/// Wohin eine Mod kommt: ihr (gesäuberter) Name; ist der schon mit anderem
/// Inhalt belegt, mit SHA-1-Anhang.
async fn target_path(dir: &Path, m: &SharedMod) -> Result<PathBuf> {
    let name = safe_file_name(&m.file).unwrap_or_else(|| format!("mod-{}.jar", &m.sha1[..12]));
    let path = dir.join(&name);
    if tokio::fs::try_exists(&path).await.unwrap_or(false) {
        let stem = name.trim_end_matches(".jar");
        return Ok(dir.join(format!("{stem}-{}.jar", &m.sha1[..8])));
    }
    Ok(path)
}

fn temp_path(dir: &Path) -> PathBuf {
    dir.join(format!(".trs-hosting-{}.part", uuid::Uuid::new_v4().simple()))
}

/// Holt die Zugangsdaten zum Relay (erst, wenn wirklich Host-Dateien geladen werden).
pub type GrantSource<'a> = futures::future::BoxFuture<'a, Result<relay::RelayGrant>>;

/// Lädt die gewählten Mods in `mods_dir` (Store: offizielle Quelle + Hash; Host: Relay +
/// SHA-256/SHA-1 + Größe). Schon vorhandene Dateien (gleicher SHA-1) werden übersprungen.
/// Liefert (installiert, schon da) und die Quellen der Store-Mods für `content.json`.
pub(crate) async fn install_into(
    http: &reqwest::Client,
    cf: Option<&crate::curseforge::CurseForge>,
    mods_dir: &Path,
    mods: &[&SharedMod],
    grant: GrantSource<'_>,
    on_progress: ProgressFn<'_>,
) -> Result<(u32, u32, Vec<(String, Source)>)> {
    tokio::fs::create_dir_all(mods_dir).await.map_err(|e| Error::io(mods_dir, e))?;
    let present: HashSet<String> = jar_hashes(mods_dir).await?.into_iter().map(|(_, h)| h).collect();
    let todo: Vec<&&SharedMod> = mods.iter().filter(|m| !present.contains(&m.sha1)).collect();
    let already = (mods.len() - todo.len()) as u32;
    let mut installed = 0u32;
    let mut sources = Vec::new();
    let store: Vec<&&SharedMod> = todo.iter().copied().filter(|m| m.source.is_store()).collect();
    let host: Vec<&&SharedMod> = todo.iter().copied().filter(|m| m.source == ModSource::Host).collect();

    for (i, m) in store.iter().enumerate() {
        progress(on_progress, "store", i, store.len(), Some(&m.name), 0, m.size);
        let dest = target_path(mods_dir, m).await?;
        let source = fetch_store(http, cf, m, &dest).await?;
        sources.push((dest.file_name().and_then(|n| n.to_str()).unwrap_or_default().to_owned(), source));
        installed += 1;
    }
    if !host.is_empty() {
        let grant = grant.await?;
        let mut channel = relay::FileChannel::open(&grant).await?;
        let total_bytes: u64 = host.iter().map(|m| m.size).sum();
        let mut before = 0u64;
        for (i, m) in host.iter().enumerate() {
            let sha256 = m.sha256.as_deref().ok_or_else(|| Error::Internal("sha256 fehlt".into()))?;
            let tmp = temp_path(mods_dir);
            let name = m.name.clone();
            channel
                .fetch(relay::KIND_MOD, sha256, m.size, MAX_HOST_FILE, Some(&m.sha1), &tmp, |got, _| {
                    progress(on_progress, "host", i, host.len(), Some(&name), before + got, total_bytes);
                })
                .await?;
            let dest = target_path(mods_dir, m).await?;
            tokio::fs::rename(&tmp, &dest).await.map_err(|e| Error::io(&dest, e))?;
            before += m.size;
            installed += 1;
        }
        channel.close().await;
    }
    Ok((installed, already, sources))
}

/// Store-Mod aus der offiziellen Quelle laden und prüfen (SHA-1 beim Laden, SHA-512 bei Modrinth danach).
async fn fetch_store(
    http: &reqwest::Client,
    cf: Option<&crate::curseforge::CurseForge>,
    m: &SharedMod,
    dest: &Path,
) -> Result<Source> {
    let dir = dest.parent().unwrap_or(Path::new("."));
    let tmp = temp_path(dir);
    let (task_client, url, source) = match m.source {
        ModSource::Modrinth => {
            let project = m.project_id.clone().unwrap_or_default();
            let version_id = m.file_id.clone().unwrap_or_default();
            let version = crate::modrinth::version_by_id(http, &version_id).await?;
            if version.project_id != project {
                return Err(store_mismatch(&m.name));
            }
            let file = version.files.iter().find(|f| f.hashes.sha1.eq_ignore_ascii_case(&m.sha1)).ok_or_else(|| store_mismatch(&m.name))?;
            if !file.url.starts_with(crate::modrinth::CDN_PREFIX) || file.size > MAX_STORE_FILE {
                return Err(store_mismatch(&m.name));
            }
            let source = Source::modrinth(project, version_id, Some(version.version_number.clone()).filter(|v| !v.is_empty()));
            (http, file.url.clone(), source)
        }
        ModSource::Curseforge => {
            let cf = cf.ok_or_else(|| {
                Error::validation(crate::msg!("hosting.curseforgeUnavailable", "CurseForge ist in diesem Build nicht verfügbar."))
            })?;
            let project: u64 = m.project_id.as_deref().and_then(|p| p.parse().ok()).ok_or_else(|| store_mismatch(&m.name))?;
            let file_id: u64 = m.file_id.as_deref().and_then(|p| p.parse().ok()).ok_or_else(|| store_mismatch(&m.name))?;
            let file = cf.file(project, file_id).await?;
            if file.sha1().is_some_and(|h| h != m.sha1) {
                return Err(store_mismatch(&m.name));
            }
            let url = file.download_url.clone().filter(|u| crate::curseforge::is_allowed_download_url(u)).ok_or_else(|| {
                let name = m.name.clone();
                Error::validation(crate::msg!(
                    "hosting.curseforgeBlocked",
                    "„{name}“ darf bei CurseForge nur von Hand heruntergeladen werden.",
                    name = name
                ))
            })?;
            let source = Source {
                project_id: project.to_string(),
                version_id: file_id.to_string(),
                version_number: Some(file.display_name.clone()).filter(|v| !v.is_empty()),
                platform: Platform::CurseForge,
            };
            (cf.download_client(), url, source)
        }
        _ => return Err(Error::Internal("keine Store-Mod".into())),
    };
    let task = download::Task { url, path: tmp.clone(), sha1: Some(m.sha1.clone()), size: Some(m.size) };
    let result = async {
        download::fetch_one(task_client, &task).await?;
        let len = tokio::fs::metadata(&tmp).await.map_err(|e| Error::io(&tmp, e))?.len();
        if len != m.size || len > MAX_STORE_FILE {
            return Err(store_mismatch(&m.name));
        }
        if let Some(expected) = &m.sha512
            && sha512_of_file(&tmp).await? != *expected
        {
            return Err(store_mismatch(&m.name));
        }
        tokio::fs::rename(&tmp, dest).await.map_err(|e| Error::io(dest, e))
    }
    .await;
    if result.is_err() {
        let _ = tokio::fs::remove_file(&tmp).await;
    }
    result.map(|()| source)
}

/// Antwort von `GET …/content` prüfen (auch für Werkzeuge/Tests außerhalb des Launchers).
pub fn parse_content(room_id: &str, value: serde_json::Value) -> Option<RoomContent> {
    serde_json::from_value::<ApiContent>(value).ok()?.cleaned(room_id)
}

/// Werkzeug/Ende-zu-Ende-Test: Auswahl prüfen (Warn-Pflicht) und in `mods_dir` laden –
/// Store-Mods aus Modrinth (ohne CurseForge-Schlüssel), Host-Dateien über `grant`.
pub async fn install_with_grant(
    http: &reqwest::Client,
    mods_dir: &Path,
    content: &RoomContent,
    plan: &PreparePlan,
    grant: relay::RelayGrant,
    on_progress: &(dyn Fn(PrepareProgress) + Send + Sync),
) -> Result<(u32, u32)> {
    let chosen = selection(content, plan)?;
    let (installed, already, _) = install_into(http, None, mods_dir, &chosen, Box::pin(async move { Ok(grant) }), on_progress).await?;
    Ok((installed, already))
}

fn store_mismatch(name: &str) -> Error {
    let name = name.to_owned();
    Error::validation(crate::msg!(
        "hosting.storeMismatch",
        "„{name}“ passt nicht zur Datei des Hosts (Hash oder Größe weichen ab).",
        name = name
    ))
}

// --- Launcher ---------------------------------------------------------------------------

impl Launcher {
    /// SHA-1 der Mods einer Instanz (für „vorhanden“ im Dialog).
    pub async fn hosting_instance_mods(&self, instance_id: &str) -> Result<Vec<String>> {
        self.instances().get(instance_id).await?;
        let dir = content::content_dir(self.paths(), instance_id, ContentKind::Mod);
        Ok(jar_hashes(&dir).await?.into_iter().map(|(_, h)| h).collect())
    }

    /// Instanz für eine Welt mit Mods vorbereiten: neue Instanz oder Kopie einer
    /// vorhandenen, dann die gewählten Mods laden und prüfen. Bei einem Fehler wird
    /// die gerade angelegte Instanz wieder gelöscht – das Original bleibt immer unberührt.
    pub async fn hosting_prepare(
        self: &Arc<Self>,
        plan: PreparePlan,
        on_progress: impl Fn(PrepareProgress) + Send + Sync,
    ) -> Result<PrepareResult> {
        let room_id = room_arg(&plan.room_id)?.to_owned();
        let room = self.hosting_room(&room_id).await?.ok_or_else(room_gone)?;
        let content = self.hosting_room_content(&room_id).await?;
        let chosen = selection(&content, &plan)?;
        progress(&on_progress, "instance", 0, 1, None, 0, 0);
        let instance = self.hosting_instance_for(&room, &plan).await?;
        let mods_dir = content::content_dir(self.paths(), &instance.id, ContentKind::Mod);
        let cf = self.curseforge().ok();
        let grant: GrantSource<'_> = Box::pin(self.hosting_relay_grant(&room_id));
        let result = install_into(self.http(), cf, &mods_dir, &chosen, grant, &on_progress).await;
        let (installed, already, sources) = match result {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("Welt-Mods für '{}' nicht vollständig: {e}", instance.id);
                let _ = self.instances().delete(&instance.id).await;
                return Err(e);
            }
        };
        for (file, source) in sources {
            if let Err(e) = content::remember_source(self.paths(), &instance.id, ContentKind::Mod, &file, source).await {
                tracing::debug!("Herkunft von {file} nicht gemerkt: {e}");
            }
        }
        progress(&on_progress, "done", 1, 1, None, 0, 0);
        tracing::info!("Welt-Mods: {installed} geladen, {already} schon da ('{}')", instance.id);
        Ok(PrepareResult { instance_id: instance.id, installed, already })
    }

    async fn hosting_instance_for(&self, room: &HostingRoom, plan: &PreparePlan) -> Result<Instance> {
        let name = plan
            .name
            .as_deref()
            .map(|n| shown(n, 48))
            .filter(|n| !n.is_empty())
            .unwrap_or_else(|| shown(&format!("{} ({})", room.name, room.host.name), 48));
        match plan.mode {
            PrepareMode::Copy => {
                let base_id = plan.base_instance_id.as_deref().ok_or_else(|| Error::validation(no_base()))?;
                let base = self.instances().get(base_id).await?;
                if base.game_version != room.mc_version || !loaders_compatible(&room.loader, loader_name(base.loader.kind)) {
                    return Err(Error::validation(crate::msg!(
                        "hosting.instanceMismatch",
                        "Diese Instanz passt nicht zur Welt (Version oder Loader)."
                    )));
                }
                self.duplicate_instance(base_id, &name).await
            }
            PrepareMode::New => {
                // Vanilla-Welten mit Mods laufen beim Host unter der Haube als Fabric.
                let kind = match room.loader.as_str() {
                    "forge" => LoaderKind::Forge,
                    "neoforge" => LoaderKind::NeoForge,
                    "quilt" => LoaderKind::Quilt,
                    _ => LoaderKind::Fabric,
                };
                self.create_instance(NewInstance { name, game_version: room.mc_version.clone(), loader: Loader { kind, version: None } })
                    .await
            }
        }
    }

    /// `hosting.mods` aus dem Spiel des Hosts: Mods dieser Instanz in den Stores erkennen.
    pub(crate) async fn identify_instance_mods(&self, instance_id: &str) -> Result<Vec<LinkModSource>> {
        let dir = content::content_dir(self.paths(), instance_id, ContentKind::Mod);
        let files = jar_hashes(&dir).await?;
        let mut locals: Vec<LocalJar> = Vec::new();
        for (path, sha1) in files.into_iter().take(MAX_MODS + 100) {
            let p = path.clone();
            let hashed = tokio::task::spawn_blocking(move || -> Option<(String, u32)> {
                let meta = std::fs::metadata(&p).ok()?;
                if meta.len() > MAX_STORE_FILE {
                    return None;
                }
                let data = std::fs::read(&p).ok()?;
                Some((relay::hex(&sha2::Sha512::digest(&data)), murmur::curseforge_fingerprint(&data)))
            })
            .await
            .ok()
            .flatten();
            if let Some((sha512, fingerprint)) = hashed {
                locals.push(LocalJar { sha1, sha512, fingerprint });
            }
        }
        let mut out: Vec<LinkModSource> = Vec::new();
        let sha512s: Vec<String> = locals.iter().map(|l| l.sha512.clone()).collect();
        match crate::modrinth::versions_by_sha512(self.http(), &sha512s).await {
            Ok(found) => out.extend(modrinth_matches(&locals, &found)),
            Err(e) => tracing::warn!("Welt-Mods: Modrinth nicht erreichbar: {e}"),
        }
        let known: HashSet<String> = out.iter().map(|m| m.sha1.clone()).collect();
        let rest: Vec<&LocalJar> = locals.iter().filter(|l| !known.contains(&l.sha1)).collect();
        if !rest.is_empty()
            && let Ok(cf) = self.curseforge()
        {
            let fps: Vec<u32> = rest.iter().map(|l| l.fingerprint).collect();
            match cf.fingerprint_matches(&fps).await {
                Ok(matches) => out.extend(curseforge_matches(&rest, &matches)),
                Err(e) => tracing::warn!("Welt-Mods: CurseForge nicht erreichbar: {e}"),
            }
        }
        Ok(out)
    }
}

fn room_gone() -> Error {
    Error::validation(crate::msg!("trsApi.room_not_found", "Diese Welt gibt es nicht (mehr) oder du kannst sie nicht sehen."))
}

fn no_base() -> crate::error::Msg {
    crate::msg!("hosting.noBaseInstance", "Wähle eine Instanz, die kopiert und ergänzt werden soll.")
}

fn loader_name(kind: LoaderKind) -> &'static str {
    match kind {
        LoaderKind::Vanilla => "vanilla",
        LoaderKind::Fabric => "fabric",
        LoaderKind::Quilt => "quilt",
        LoaderKind::Forge => "forge",
        LoaderKind::NeoForge => "neoforge",
    }
}

/// Eine Mod-Datei des Hosts mit allen Hashes für die Store-Suche.
pub(crate) struct LocalJar {
    pub sha1: String,
    pub sha512: String,
    pub fingerprint: u32,
}

/// Modrinth-Treffer: Version enthält eine Datei mit genau unserem SHA-1 und SHA-512.
pub(crate) fn modrinth_matches(locals: &[LocalJar], found: &HashMap<String, crate::modrinth::Version>) -> Vec<LinkModSource> {
    let mut out = Vec::new();
    for l in locals {
        let Some(v) = found.get(&l.sha512) else { continue };
        let exact = v.files.iter().any(|f| {
            f.hashes.sha1.eq_ignore_ascii_case(&l.sha1) && f.hashes.sha512.as_deref().is_some_and(|h| h.eq_ignore_ascii_case(&l.sha512))
        });
        let ok_id = |s: &str| s.len() == 8 && s.bytes().all(|b| b.is_ascii_alphanumeric());
        if exact && ok_id(&v.project_id) && ok_id(&v.id) {
            out.push(LinkModSource {
                sha1: l.sha1.clone(),
                source: "modrinth",
                project_id: v.project_id.clone(),
                file_id: v.id.clone(),
                fingerprint: None,
            });
        }
    }
    out
}

/// CurseForge-Treffer: gleicher Fingerprint und (falls angegeben) gleicher SHA-1.
pub(crate) fn curseforge_matches(locals: &[&LocalJar], matches: &[crate::curseforge::FingerprintMatch]) -> Vec<LinkModSource> {
    let mut out = Vec::new();
    for l in locals {
        let hit = matches.iter().find(|m| {
            m.file.file_fingerprint == u64::from(l.fingerprint) && m.file.sha1().is_none_or(|h| h == l.sha1) && m.id > 0 && m.file.id > 0
        });
        if let Some(m) = hit {
            out.push(LinkModSource {
                sha1: l.sha1.clone(),
                source: "curseforge",
                project_id: m.id.to_string(),
                file_id: m.file.id.to_string(),
                fingerprint: Some(l.fingerprint),
            });
        }
    }
    out
}
