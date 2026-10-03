//! Modpacks teilen – Launcher-Seite (Vertrag `api/API.md` §27).
//!
//! - **Teilen:** Instanz wie beim Export als `.mrpack` packen (Mod-Liste + gewählte Ordner; TRS-Client-Dateien
//!   und Geheimnisse bleiben draußen), eigene Mod-Dateien (nicht von Modrinth) erst nach Bestätigung, hochladen.
//!   Eine neue Version behält den Code.
//! - **Installieren per Code:** Pack laden (Prüfsumme gegen die API), wie ein Modrinth-Pack installieren und in
//!   `<instanz>/trs-pack.json` merken, welche Datei aus dem Pack kam (Pfad → SHA-1).
//! - **Update:** Neue Version laden; Dateien, die der Spieler selbst geändert hat, bleiben (und werden gemeldet),
//!   unveränderte alte Dateien, die nicht mehr im Pack sind, werden gelöscht.

use std::collections::{BTreeMap, HashSet};
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use futures::StreamExt;
use serde::{Deserialize, Serialize};
use tokio::io::AsyncWriteExt;
use sha1::Digest as _;

use crate::download::{self, Task};
use crate::history::{HistoryEntry, HistoryKind};
use crate::instance::{Instance, validate_id};
use crate::modpack::{PackPhase, PackPreview, PackProgress, PackProgressFn, safe_relative};
use crate::modpack_export::{ExportOptions, ExportProgressFn};
use crate::paths::Paths;
use crate::trs_api::packs::{
    chunk_retryable, normalize_code, pack_id, upload_expired, upload_gone, DOWNLOAD_TIMEOUT, MAX_PACK_BYTES, OwnPack, SharedPack,
    UploadTicket,
};
use crate::{Error, Launcher, Result, fsutil, history};

/// Merkzettel im Instanz-Ordner (nicht im Spielordner – das Spiel sieht ihn nicht).
pub const LINK_FILE: &str = "trs-pack.json";
/// Höchstens so viele Dateien merkt sich eine Instanz.
const MAX_TRACKED: usize = 25_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PackRole {
    /// Eigene Instanz, als Pack geteilt.
    Shared,
    /// Aus einem geteilten Pack installiert (bekommt Updates).
    Installed,
}

/// Inhalt von `trs-pack.json`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PackLink {
    pub role: PackRole,
    pub pack_id: String,
    pub code: String,
    pub revision: u32,
    pub name: String,
    /// Nur `shared`: zuletzt gewählte Ordner (Vorschlag für die nächste Version).
    #[serde(default)]
    pub include: Vec<String>,
    /// Nur `installed`: Pfad im Spielordner → SHA-1, wie das Pack die Datei geliefert hat.
    #[serde(default)]
    pub files: BTreeMap<String, String>,
}

/// Was die Oberfläche über die Verknüpfung einer Instanz erfährt (ohne Dateiliste).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PackLinkView {
    pub instance_id: String,
    pub role: PackRole,
    pub pack_id: String,
    pub code: String,
    pub revision: u32,
    pub name: String,
    pub include: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SharePackOptions {
    pub name: String,
    pub version: String,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub include: Vec<String>,
    /// `1d`, `7d`, `30d` oder `forever` (nur neue Packs).
    #[serde(default = "default_duration")]
    pub duration: String,
    /// Eigene Mod-Dateien (nicht von Modrinth) dürfen mit.
    #[serde(default)]
    pub allow_own_jars: bool,
}

fn default_duration() -> String {
    "7d".into()
}

/// Ergebnis von „Vorbereiten“: das Pack liegt bereit – oder eigene Mod-Dateien müssen erst bestätigt werden.
/// Hochgeladen wird danach mit [`Launcher::upload_shared_pack`] (der `token` zeigt auf die vorbereitete Datei).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "status", rename_all = "camelCase")]
pub enum SharePackOutcome {
    Ready { token: String, downloads: u32, uploaded: u32, bytes: u64 },
    #[serde(rename_all = "camelCase")]
    ConfirmOwnJars { files: Vec<String>, token: String, downloads: u32, uploaded: u32, bytes: u64 },
}

/// Merkzettel neben der vorbereiteten `.mrpack`, damit ein abgebrochener Upload weitergeht.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ShareDraft {
    instance_id: String,
    update: bool,
    #[serde(default)]
    pack_id: Option<String>,
    options: SharePackOptions,
    downloads: u32,
    uploaded: u32,
    bytes: u64,
    sha256: String,
    #[serde(default)]
    upload_id: Option<String>,
    #[serde(default)]
    upload_expires: Option<String>,
}

/// Vorschau vor „Per Code installieren“: was die API sagt + was im Pack steckt (für „mit/ohne TRS Client“).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PackCodePreview {
    pub pack: SharedPack,
    pub preview: PackPreview,
}

/// Eine installierte Instanz mit neuerer Pack-Version.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PackUpdateInfo {
    pub instance_id: String,
    pub revision: u32,
    pub latest: SharedPack,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PackUpdateResult {
    pub revision: u32,
    /// Neu geladen oder überschrieben.
    pub updated: u32,
    pub removed: u32,
    /// Vom Spieler geänderte Dateien, die das Update nicht angefasst hat.
    pub kept: Vec<String>,
}

/// Link-Protokoll des Launchers (Website-Knopf „Im Launcher öffnen“).
pub const LINK_SCHEME: &str = "trs-launcher";

/// `trs-launcher://pack/TRS-XXXX-XXXX` → Code. Alles andere (andere Pfade, Anhänge, kaputte Codes) → `None`.
/// Der Link öffnet nur den Dialog „Modpack per Code“ – installiert wird nie ohne Klick.
pub fn pack_code_from_link(url: &str) -> Option<String> {
    let url = url.trim();
    if url.len() > 200 {
        return None;
    }
    let (scheme, rest) = url.split_once("://")?;
    if !scheme.eq_ignore_ascii_case(LINK_SCHEME) {
        return None;
    }
    let rest = rest.trim_end_matches('/');
    let (kind, code) = rest.split_once('/')?;
    if !kind.eq_ignore_ascii_case("pack") || code.contains(['/', '?', '#', '\\']) {
        return None;
    }
    normalize_code(code)
}

fn invalid(msg: crate::error::Msg) -> Error {
    Error::validation(msg)
}

fn not_linked() -> Error {
    invalid(crate::msg!("packShare.notLinked", "Diese Instanz gehört zu keinem geteilten Modpack."))
}

// --- Merkzettel ------------------------------------------------------------------------

fn link_path(paths: &Paths, instance_id: &str) -> PathBuf {
    paths.instance_dir(instance_id).join(LINK_FILE)
}

/// Liest `trs-pack.json`; kaputte oder verdächtige Einträge → `None` bzw. werden verworfen.
pub async fn read_link(paths: &Paths, instance_id: &str) -> Option<PackLink> {
    validate_id(instance_id).ok()?;
    let text = tokio::fs::read_to_string(link_path(paths, instance_id)).await.ok()?;
    let mut link: PackLink = serde_json::from_str(&text).ok()?;
    if !pack_id(&link.pack_id) {
        return None;
    }
    link.code = normalize_code(&link.code)?;
    link.name = crate::trs_api::validate::text(&link.name, 64);
    link.files.retain(|path, sha| safe_relative(path).is_some() && is_sha1(sha));
    link.include.retain(|n| n.len() <= 120 && !n.contains(['/', '\\']) && n != ".." && n != ".");
    Some(link)
}

async fn write_link(paths: &Paths, instance_id: &str, link: &PackLink) -> Result<()> {
    fsutil::write_json(&link_path(paths, instance_id), link).await
}

async fn remove_link(paths: &Paths, instance_id: &str) {
    let _ = tokio::fs::remove_file(link_path(paths, instance_id)).await;
}

fn is_sha1(s: &str) -> bool {
    s.len() == 40 && s.bytes().all(|b| b.is_ascii_hexdigit())
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// SHA-1 einer Datei im Spielordner (`None`, wenn es sie nicht gibt oder sie eine Verknüpfung ist).
fn file_sha1(path: &Path) -> Option<String> {
    let meta = std::fs::symlink_metadata(path).ok()?;
    if !meta.is_file() {
        return None;
    }
    let mut file = std::fs::File::open(path).ok()?;
    let mut hasher = sha1::Sha1::new();
    let mut buf = vec![0u8; 128 * 1024];
    loop {
        let n = file.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Some(hex(&hasher.finalize()))
}

// --- Pack lesen -------------------------------------------------------------------------

/// Dateien, die eine Installation aus diesem Pack in den Spielordner legt: Pfad → SHA-1.
/// Overrides gewinnen über Downloads, `client-overrides/` über `overrides/` (wie bei der Installation).
pub(crate) fn pack_files(pack: &Path) -> Result<(BTreeMap<String, String>, BTreeMap<String, String>)> {
    let index = crate::modpack::read_index(pack)?;
    let mut downloads: BTreeMap<String, String> = BTreeMap::new();
    for (path, sha) in index.client_files() {
        if safe_relative(&path).is_some() && is_sha1(&sha) {
            downloads.insert(path, sha);
        }
    }
    let overrides = override_hashes(pack)?;
    for path in overrides.keys() {
        downloads.remove(path);
    }
    if downloads.len() + overrides.len() > MAX_TRACKED {
        return Err(invalid(crate::msg!("modpack.tooManyFiles", "Das Modpack enthält zu viele Dateien.")));
    }
    Ok((downloads, overrides))
}

fn corrupt() -> Error {
    invalid(crate::msg!("modpack.corrupt", "Das Modpack ist beschädigt."))
}

fn open_zip(pack: &Path) -> Result<zip::ZipArchive<std::fs::File>> {
    let file = std::fs::File::open(pack).map_err(|e| Error::io(pack, e))?;
    zip::ZipArchive::new(file).map_err(|_| corrupt())
}

const OVERRIDE_DIRS: [&str; 2] = ["overrides/", "client-overrides/"];

/// Pfad eines Zip-Eintrags in einem der Override-Ordner → (Rang, Pfad im Spielordner).
fn override_rel(name: &str) -> Option<(usize, String)> {
    let name = name.replace('\\', "/");
    OVERRIDE_DIRS.iter().enumerate().find_map(|(rank, prefix)| {
        let rel = name.strip_prefix(prefix)?;
        safe_relative(rel).map(|_| (rank, rel.to_owned()))
    })
}

fn override_hashes(pack: &Path) -> Result<BTreeMap<String, String>> {
    let mut archive = open_zip(pack)?;
    let mut best: BTreeMap<String, (usize, String)> = BTreeMap::new();
    let mut buf = vec![0u8; 128 * 1024];
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|_| corrupt())?;
        if entry.is_dir() || entry.enclosed_name().is_none() {
            continue;
        }
        let Some((rank, rel)) = override_rel(entry.name()) else { continue };
        if best.get(&rel).is_some_and(|(r, _)| *r > rank) {
            continue;
        }
        let mut hasher = sha1::Sha1::new();
        loop {
            let n = entry.read(&mut buf).map_err(|_| corrupt())?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
        }
        best.insert(rel, (rank, hex(&hasher.finalize())));
    }
    Ok(best.into_iter().map(|(k, (_, sha))| (k, sha)).collect())
}

/// Nur diese Overrides entpacken (spätere Ordner gewinnen). Verknüpfungen am Ziel werden nicht überschrieben.
fn extract_selected(pack: &Path, game_dir: &Path, wanted: &HashSet<String>) -> Result<()> {
    if wanted.is_empty() {
        return Ok(());
    }
    let mut archive = open_zip(pack)?;
    for rank in 0..OVERRIDE_DIRS.len() {
        for i in 0..archive.len() {
            let mut entry = archive.by_index(i).map_err(|_| corrupt())?;
            if entry.is_dir() || entry.enclosed_name().is_none() {
                continue;
            }
            let Some((r, rel)) = override_rel(entry.name()) else { continue };
            if r != rank || !wanted.contains(&rel) {
                continue;
            }
            let Some(rel_path) = safe_relative(&rel) else { continue };
            let dest = game_dir.join(rel_path);
            if std::fs::symlink_metadata(&dest).is_ok_and(|m| m.file_type().is_symlink()) {
                continue;
            }
            if let Some(parent) = dest.parent() {
                std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
            }
            let mut out = std::fs::File::create(&dest).map_err(|e| Error::io(&dest, e))?;
            std::io::copy(&mut entry, &mut out).map_err(|e| Error::io(&dest, e))?;
        }
    }
    Ok(())
}

// --- Update planen ----------------------------------------------------------------------

#[derive(Debug, Default, PartialEq, Eq)]
pub(crate) struct UpdatePlan {
    /// Unveränderte alte Dateien, die nicht mehr im Pack sind.
    pub delete: Vec<String>,
    /// Downloads (Modrinth), die fehlen oder anders sind.
    pub download: Vec<String>,
    /// Overrides, die (neu) geschrieben werden.
    pub write: Vec<String>,
    /// Vom Spieler geänderte Dateien, die bleiben, wie sie sind.
    pub kept: Vec<String>,
}

/// `current(path)` = SHA-1 der Datei im Spielordner oder `None`.
pub(crate) fn plan_update(
    old: &BTreeMap<String, String>,
    downloads: &BTreeMap<String, String>,
    overrides: &BTreeMap<String, String>,
    current: &dyn Fn(&str) -> Option<String>,
) -> UpdatePlan {
    let mut plan = UpdatePlan::default();
    for (path, old_sha) in old {
        if downloads.contains_key(path) || overrides.contains_key(path) {
            continue;
        }
        match current(path) {
            Some(sha) if sha == *old_sha => plan.delete.push(path.clone()),
            Some(_) => plan.kept.push(path.clone()),
            None => {}
        }
    }
    for (path, sha) in downloads {
        if current(path).as_deref() != Some(sha.as_str()) {
            plan.download.push(path.clone());
        }
    }
    for (path, sha) in overrides {
        match current(path) {
            Some(now) if now == *sha => {}
            None => plan.write.push(path.clone()),
            // Unverändert seit der letzten Version → neue Fassung; sonst gehört die Datei jetzt dem Spieler.
            Some(now) if old.get(path) == Some(&now) => plan.write.push(path.clone()),
            Some(_) => plan.kept.push(path.clone()),
        }
    }
    plan
}

fn sha256_hex(bytes: &[u8]) -> String {
    hex(&sha2::Sha256::digest(bytes))
}

fn too_large() -> Error {
    invalid(crate::msg!(
        "packShare.tooLarge",
        "Das Modpack ist größer als 1 GB – wähle weniger Ordner aus (z. B. ohne Resource Packs)."
    ))
}

/// 32 Hex-Zeichen, nichts anderes – der Token wird Teil eines Dateinamens.
fn share_token(token: &str) -> Result<&str> {
    let ok = token.len() == 32 && token.bytes().all(|b| b.is_ascii_hexdigit());
    if ok { Ok(token) } else { Err(invalid(crate::msg!("packShare.notFound", "Dieses Modpack gibt es nicht (mehr)."))) }
}

fn share_paths(paths: &Paths, token: &str) -> Result<(PathBuf, PathBuf)> {
    share_paths_in(&crate::modpack::pack_cache_dir(paths), token)
}

fn share_paths_in(dir: &Path, token: &str) -> Result<(PathBuf, PathBuf)> {
    let token = share_token(token)?;
    Ok((dir.join(format!("share-{token}.mrpack")), dir.join(format!("share-{token}.json"))))
}

fn sha256_file(path: &Path) -> impl std::future::Future<Output = Result<String>> + Send {
    let path = path.to_owned();
    async move {
        tokio::task::spawn_blocking(move || {
            let mut file = std::fs::File::open(&path).map_err(|e| Error::io(&path, e))?;
            let mut hasher = sha2::Sha256::new();
            let mut buf = vec![0u8; 128 * 1024];
            loop {
                let n = std::io::Read::read(&mut file, &mut buf).map_err(|e| Error::io(&path, e))?;
                if n == 0 {
                    break;
                }
                sha2::Digest::update(&mut hasher, &buf[..n]);
            }
            Ok(hex(&sha2::Digest::finalize(hasher)))
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))?
    }
}

fn hash_prefix(path: &Path, len: u64) -> impl std::future::Future<Output = Result<sha2::Sha256>> + Send {
    let path = path.to_owned();
    async move {
        tokio::task::spawn_blocking(move || {
            let mut file = std::fs::File::open(&path).map_err(|e| Error::io(&path, e))?;
            let mut hasher = sha2::Sha256::new();
            let mut left = len;
            let mut buf = vec![0u8; 128 * 1024];
            while left > 0 {
                let want = usize::try_from(left.min(buf.len() as u64)).unwrap_or(buf.len());
                let n = std::io::Read::read(&mut file, &mut buf[..want]).map_err(|e| Error::io(&path, e))?;
                if n == 0 {
                    return Err(Error::io(&path, std::io::Error::new(std::io::ErrorKind::UnexpectedEof, "kurz")));
                }
                sha2::Digest::update(&mut hasher, &buf[..n]);
                left -= n as u64;
            }
            Ok(hasher)
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))?
    }
}

fn read_at(path: &Path, offset: u64, len: usize) -> Result<Vec<u8>> {
    let mut file = std::fs::File::open(path).map_err(|e| Error::io(path, e))?;
    file.seek(SeekFrom::Start(offset)).map_err(|e| Error::io(path, e))?;
    let mut buf = vec![0u8; len];
    file.read_exact(&mut buf).map_err(|e| Error::io(path, e))?;
    Ok(buf)
}

async fn cached_pack(path: &Path, size: u64, sha256: &str) -> Result<bool> {
    let Ok(meta) = tokio::fs::metadata(path).await else { return Ok(false) };
    if !meta.is_file() || meta.len() != size {
        return Ok(false);
    }
    Ok(sha256_file(path).await?.eq_ignore_ascii_case(sha256))
}

/// `bytes 12-34/100` → 12. Alles andere ist kein gültiger Anfang.
fn content_range_start(header: Option<&str>) -> Option<u64> {
    let rest = header?.strip_prefix("bytes ")?;
    let (range, _) = rest.split_once('/')?;
    let (start, _) = range.split_once('-')?;
    start.parse().ok()
}

impl Launcher {
    fn ensure_idle(&self, instance_id: &str) -> Result<()> {
        if self.games().is_running(instance_id) || self.is_preparing(instance_id) {
            return Err(Error::launch(crate::msg!("launcher.instanceRunningStopFirst", "Die Instanz läuft gerade – bitte erst beenden.")));
        }
        Ok(())
    }

    /// Verknüpfung einer Instanz (für die Oberfläche).
    pub async fn pack_link(&self, instance_id: &str) -> Option<PackLinkView> {
        let link = read_link(self.paths(), instance_id).await?;
        Some(view(instance_id, link))
    }

    /// Alle Instanzen mit Verknüpfung.
    pub async fn pack_links(&self) -> Result<Vec<PackLinkView>> {
        let mut out = Vec::new();
        for instance in self.instances().list().await? {
            if let Some(link) = read_link(self.paths(), &instance.id).await {
                out.push(view(&instance.id, link));
            }
        }
        Ok(out)
    }

    /// Verknüpfung lösen (keine Update-Hinweise mehr bzw. „nicht mehr geteilt“).
    pub async fn pack_unlink(&self, instance_id: &str) -> Result<()> {
        validate_id(instance_id)?;
        remove_link(self.paths(), instance_id).await;
        Ok(())
    }

    /// Instanz packen und zum Hochladen bereitlegen (`update` = neue Version des schon geteilten Packs).
    /// Die Datei bleibt liegen, bis [`Self::upload_shared_pack`] oder [`Self::discard_share_pack`] sie wegräumt.
    pub async fn share_pack(
        &self,
        instance_id: &str,
        options: &SharePackOptions,
        update: bool,
        on_progress: &ExportProgressFn,
    ) -> Result<SharePackOutcome> {
        let instance = self.instances().get(instance_id).await?;
        let existing = read_link(self.paths(), &instance.id).await;
        let target = if update {
            match &existing {
                Some(l) if l.role == PackRole::Shared => Some(l.pack_id.clone()),
                _ => return Err(not_linked()),
            }
        } else {
            None
        };
        let dir = crate::modpack::pack_cache_dir(self.paths());
        fsutil::ensure_dir(&dir).await?;
        let token = uuid::Uuid::new_v4().simple().to_string();
        let (dest, draft_path) = share_paths_in(&dir, &token)?;
        let export = ExportOptions {
            name: options.name.clone(),
            version: options.version.clone(),
            summary: options.summary.clone(),
            include: options.include.clone(),
        };
        let result = async {
            let summary = self.export_modpack(&instance.id, &export, &dest, on_progress).await?;
            let bytes = tokio::fs::metadata(&dest).await.map_err(|e| Error::io(&dest, e))?.len();
            if bytes > MAX_PACK_BYTES {
                return Err(too_large());
            }
            let sha256 = sha256_file(&dest).await?;
            let path = dest.clone();
            let own = tokio::task::spawn_blocking(move || {
                crate::modpack::override_mod_names(&path, &["overrides/", "client-overrides/"])
                    .into_iter()
                    .filter(|n| n.to_ascii_lowercase().ends_with(".jar"))
                    .collect::<Vec<_>>()
            })
            .await
            .map_err(|e| Error::Internal(e.to_string()))?;
            let draft = ShareDraft {
                instance_id: instance.id.clone(),
                update: target.is_some(),
                pack_id: target.clone(),
                options: options.clone(),
                downloads: u32::try_from(summary.downloads).unwrap_or(u32::MAX),
                uploaded: u32::try_from(summary.overrides).unwrap_or(u32::MAX),
                bytes,
                sha256,
                upload_id: None,
                upload_expires: None,
            };
            fsutil::write_json(&draft_path, &draft).await?;
            let plan = (token.clone(), draft.downloads, draft.uploaded, bytes);
            if !own.is_empty() && !options.allow_own_jars {
                return Ok(SharePackOutcome::ConfirmOwnJars {
                    files: own.into_iter().take(200).collect(),
                    token: plan.0,
                    downloads: plan.1,
                    uploaded: plan.2,
                    bytes: plan.3,
                });
            }
            Ok(SharePackOutcome::Ready { token: plan.0, downloads: plan.1, uploaded: plan.2, bytes: plan.3 })
        }
        .await;
        if result.is_err() {
            let _ = tokio::fs::remove_file(&dest).await;
            let _ = tokio::fs::remove_file(&draft_path).await;
        }
        result
    }

    /// Vorbereitetes Pack in Chunks hochladen (fortsetzbar) und teilen.
    pub async fn upload_shared_pack(&self, token: &str) -> Result<OwnPack> {
        let (pack_path, draft_path) = share_paths(self.paths(), token)?;
        let mut draft: ShareDraft = fsutil::read_json(&draft_path)
            .await?
            .ok_or_else(|| invalid(crate::msg!("packShare.notFound", "Dieses Modpack gibt es nicht (mehr).")))?;
        validate_id(&draft.instance_id)?;
        let len = tokio::fs::metadata(&pack_path).await.map_err(|e| Error::io(&pack_path, e))?.len();
        if len != draft.bytes || len > MAX_PACK_BYTES || sha256_file(&pack_path).await? != draft.sha256.to_ascii_lowercase() {
            return Err(too_large());
        }
        crate::task::add_total(draft.bytes);
        let mut restarted = false;
        let upload_token = loop {
            let (session, received) = match self.share_session(&mut draft, &draft_path).await {
                Ok(session) => session,
                Err(e) if upload_gone(&e) && !restarted => {
                    restarted = true;
                    draft.upload_id = None;
                    draft.upload_expires = None;
                    fsutil::write_json(&draft_path, &draft).await?;
                    continue;
                }
                Err(e) => return Err(e),
            };
            match self.send_share_chunks(&draft, &pack_path, &session, &received).await {
                Ok(()) => match self.trs_pack_upload_complete(&session.upload_id).await {
                    Ok(token) => break token,
                    Err(e) if upload_gone(&e) && !restarted => {
                        restarted = true;
                        draft.upload_id = None;
                        draft.upload_expires = None;
                        fsutil::write_json(&draft_path, &draft).await?;
                        continue;
                    }
                    Err(e) => return Err(e),
                },
                Err(e) if upload_gone(&e) && !restarted => {
                    restarted = true;
                    draft.upload_id = None;
                    draft.upload_expires = None;
                    fsutil::write_json(&draft_path, &draft).await?;
                    continue;
                }
                Err(e) => return Err(e),
            }
        };
        let update_id = draft.update.then(|| draft.pack_id.clone()).flatten();
        let pack = self.trs_pack_finish_token(&upload_token, update_id.as_deref(), &draft.options.duration).await?;
        let link = PackLink {
            role: PackRole::Shared,
            pack_id: pack.pack.id.clone(),
            code: pack.pack.code.clone(),
            revision: pack.pack.revision,
            name: pack.pack.name.clone(),
            include: draft.options.include.clone(),
            files: BTreeMap::new(),
        };
        write_link(self.paths(), &draft.instance_id, &link).await?;
        let _ = tokio::fs::remove_file(&pack_path).await;
        let _ = tokio::fs::remove_file(&draft_path).await;
        Ok(pack)
    }

    /// Vorbereitetes Pack verwerfen (Dialog zu, ohne Upload).
    pub async fn discard_share_pack(&self, token: &str) -> Result<()> {
        let (pack_path, draft_path) = share_paths(self.paths(), token)?;
        let _ = tokio::fs::remove_file(&pack_path).await;
        let _ = tokio::fs::remove_file(&draft_path).await;
        Ok(())
    }

    /// Laufende Sitzung weiterverwenden oder eine neue anlegen und im Merkzettel merken.
    /// Die Liste sind die Chunk-Indizes, die der Server schon hat.
    async fn share_session(&self, draft: &mut ShareDraft, draft_path: &Path) -> Result<(UploadTicket, Vec<u32>)> {
        if let Some(id) = draft.upload_id.clone()
            && draft.upload_expires.as_deref().is_some_and(|t| !upload_expired(t))
        {
            match self.trs_pack_upload_status(&id).await {
                Ok(progress) if progress.size == draft.bytes => {
                    return Ok((
                        UploadTicket {
                            upload_id: id,
                            chunk_size: progress.chunk_size,
                            expires_at: draft.upload_expires.clone().unwrap_or_default(),
                        },
                        progress.received,
                    ));
                }
                Ok(_) => {}
                Err(e) if upload_gone(&e) => {}
                Err(e) => return Err(e),
            }
        }
        let ticket = self.trs_pack_upload_begin(draft.bytes, &draft.sha256, Some(&draft.options.name)).await?;
        if upload_expired(&ticket.expires_at) {
            return Err(invalid(crate::msg!("packShare.notFound", "Dieses Modpack gibt es nicht (mehr).")));
        }
        draft.upload_id = Some(ticket.upload_id.clone());
        draft.upload_expires = Some(ticket.expires_at.clone());
        fsutil::write_json(draft_path, draft).await?;
        Ok((ticket, Vec::new()))
    }

    /// Fehlende Chunks schicken. Schon empfangene werden übersprungen (Fortsetzen). Pause zwischen den Chunks.
    async fn send_share_chunks(&self, draft: &ShareDraft, pack_path: &Path, session: &UploadTicket, received: &[u32]) -> Result<()> {
        let UploadTicket { upload_id, chunk_size, .. } = session;
        if *chunk_size == 0 || draft.bytes.div_ceil(*chunk_size) > 4096 {
            return Err(crate::trs_api::bad_response());
        }
        let have: HashSet<u32> = received.iter().copied().collect();
        let count = u32::try_from(draft.bytes.div_ceil(*chunk_size)).unwrap_or(u32::MAX);
        for index in 0..count {
            crate::task::checkpoint().await?;
            let start = u64::from(index) * *chunk_size;
            let len = usize::try_from((draft.bytes - start).min(*chunk_size)).unwrap_or(usize::MAX);
            if have.contains(&index) {
                crate::task::add_done(len as i64);
                continue;
            }
            let path = pack_path.to_owned();
            let bytes = tokio::task::spawn_blocking(move || read_at(&path, start, len))
                .await
                .map_err(|e| Error::Internal(e.to_string()))??;
            let hash = sha256_hex(&bytes);
            let mut attempt = 0u32;
            loop {
                attempt += 1;
                match self.trs_pack_upload_chunk(upload_id, index, bytes.clone(), &hash).await {
                    Ok(()) => break,
                    Err(e) if attempt < 3 && chunk_retryable(&e) => {
                        let wait = if attempt == 1 { Duration::from_millis(500) } else { Duration::from_secs(2) };
                        crate::task::sleep(wait).await?;
                    }
                    Err(e) => return Err(e),
                }
            }
            crate::task::add_done(len as i64);
        }
        Ok(())
    }

    /// Eigenes Pack löschen; Instanzen, die es geteilt haben, verlieren die Verknüpfung.
    pub async fn pack_delete(&self, id: &str) -> Result<()> {
        self.trs_pack_delete(id).await?;
        for instance in self.instances().list().await.unwrap_or_default() {
            if read_link(self.paths(), &instance.id).await.is_some_and(|l| l.role == PackRole::Shared && l.pack_id == id) {
                remove_link(self.paths(), &instance.id).await;
            }
        }
        Ok(())
    }

    /// Pack herunterladen (mit `Range`, fortsetzbar) und gegen Größe und SHA-256 der API prüfen.
    /// Liegt es schon im Zwischenspeicher, wird es nicht neu geladen.
    async fn fetch_pack(&self, code: &str, on_progress: &(dyn Fn(u64, u64) + Sync)) -> Result<(PathBuf, SharedPack)> {
        let mut pack = self.trs_pack_by_code(code).await?;
        let dir = crate::modpack::pack_cache_dir(self.paths());
        fsutil::ensure_dir(&dir).await?;
        for attempt in 0..2 {
            if pack.bytes > MAX_PACK_BYTES || pack.sha256.len() != 64 {
                return Err(invalid(crate::msg!("packShare.invalidPack", "Der Server hat das Modpack abgelehnt – es ist kein gültiges Modrinth-Pack.")));
            }
            let path = dir.join(format!("trs-{}.mrpack", pack.sha256));
            if cached_pack(&path, pack.bytes, &pack.sha256).await? {
                crate::task::add_total(pack.bytes);
                crate::task::add_done(pack.bytes as i64);
                on_progress(pack.bytes, pack.bytes);
                return Ok((path, pack));
            }
            let _ = tokio::fs::remove_file(&path).await;
            match self.stream_pack(&pack, &path, on_progress).await {
                Ok(()) => return Ok((path, pack)),
                Err(e) if attempt == 0 => {
                    tracing::warn!("Pack-Download, neuer Versuch: {e}");
                    pack = self.trs_pack_by_code(&pack.code).await?;
                }
                Err(e) => return Err(e),
            }
        }
        Err(invalid(crate::msg!("packShare.changed", "Das Modpack hat sich gerade geändert – bitte noch einmal versuchen.")))
    }

    /// Schreibt die Pack-Datei nach `dest`. Eine halbfertige Datei (`*.part`) wird per `Range` fortgesetzt.
    async fn stream_pack(&self, pack: &SharedPack, dest: &Path, on_progress: &(dyn Fn(u64, u64) + Sync)) -> Result<()> {
        let part = dest.with_extension("part");
        let mut have = tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
        if have > pack.bytes {
            let _ = tokio::fs::remove_file(&part).await;
            have = 0;
        }
        crate::task::add_total(pack.bytes);
        let mut hasher = if have > 0 { hash_prefix(&part, have).await? } else { sha2::Sha256::new() };
        if have > 0 {
            crate::task::add_done(have as i64);
            on_progress(have, pack.bytes);
        }
        if have < pack.bytes {
            let response = self.trs_pack_open_file(&pack.code, (have > 0).then_some(have)).await?;
            let status = response.status();
            if status.as_u16() == 200 && have > 0 {
                crate::task::add_done(-(have as i64));
                have = 0;
                hasher = sha2::Sha256::new();
                let _ = tokio::fs::remove_file(&part).await;
            } else if status.as_u16() == 206 {
                let start = content_range_start(response.headers().get(reqwest::header::CONTENT_RANGE).and_then(|v| v.to_str().ok()));
                if start != Some(have) {
                    let _ = tokio::fs::remove_file(&part).await;
                    return Err(Error::download(&pack.code, "unerwartete Dateigröße"));
                }
            } else if !status.is_success() {
                return Err(Error::download(&pack.code, format!("HTTP {}", status.as_u16())));
            }
            let mut file = tokio::fs::OpenOptions::new().create(true).append(have > 0).write(true).open(&part).await.map_err(|e| Error::io(&part, e))?;
            if have == 0 {
                file.set_len(0).await.map_err(|e| Error::io(&part, e))?;
            }
            let deadline = Instant::now() + DOWNLOAD_TIMEOUT;
            let mut stream = response.bytes_stream();
            loop {
                if Instant::now() >= deadline {
                    return Err(Error::download(&pack.code, "Zeitüberschreitung"));
                }
                crate::task::checkpoint().await?;
                let next = tokio::time::timeout(Duration::from_secs(45), stream.next()).await.map_err(|_| Error::download(&pack.code, "Zeitüberschreitung"))?;
                let Some(chunk) = next else { break };
                let chunk = chunk.map_err(|e| Error::download(&pack.code, e.without_url().to_string()))?;
                let next_len = have.saturating_add(chunk.len() as u64);
                if next_len > pack.bytes {
                    let _ = tokio::fs::remove_file(&part).await;
                    return Err(Error::download(&pack.code, "unerwartete Dateigröße"));
                }
                sha2::Digest::update(&mut hasher, &chunk);
                file.write_all(&chunk).await.map_err(|e| Error::io(&part, e))?;
                have = next_len;
                crate::task::add_done(chunk.len() as i64);
                on_progress(have, pack.bytes);
            }
            file.flush().await.map_err(|e| Error::io(&part, e))?;
        }
        let got = hex(&sha2::Digest::finalize(hasher));
        if have != pack.bytes || !got.eq_ignore_ascii_case(&pack.sha256) {
            let _ = tokio::fs::remove_file(&part).await;
            let reason = if have != pack.bytes { "unerwartete Dateigröße" } else { "Prüfsumme stimmt nicht" };
            return Err(Error::download(&pack.code, reason));
        }
        tokio::fs::rename(&part, dest).await.map_err(|e| Error::io(dest, e))?;
        Ok(())
    }

    /// Vorschau zu einem Code: Pack laden (bleibt für die Installation liegen) und prüfen, was drinsteckt.
    pub async fn preview_pack_code(&self, code: &str) -> Result<PackCodePreview> {
        let (path, pack) = self.fetch_pack(code, &|_, _| {}).await?;
        let mut preview = self.preview_pack_file(&path).await?;
        preview.name.clone_from(&pack.name);
        Ok(PackCodePreview { pack, preview })
    }

    /// Pack per Code als neue Instanz installieren und für Updates merken.
    pub async fn install_pack_code(&self, code: &str, trs_client: Option<bool>, on_progress: &PackProgressFn) -> Result<Instance> {
        on_progress(PackProgress::new(PackPhase::Pack, 0.0));
        let (path, pack) = self.fetch_pack(code, &|done, total| {
            let percent = if total == 0 { 100.0 } else { (done as f64 / total as f64 * 100.0).min(100.0) };
            on_progress(PackProgress::new(PackPhase::Pack, percent));
        })
        .await?;
        let instance = self.install_local_pack(&path, trs_client, true, on_progress).await?;
        let p = path.clone();
        let files = tokio::task::spawn_blocking(move || pack_files(&p))
            .await
            .map_err(|e| Error::Internal(e.to_string()))?
            .map(|(mut downloads, overrides)| {
                downloads.extend(overrides);
                downloads
            })
            .unwrap_or_default();
        let link = PackLink {
            role: PackRole::Installed,
            pack_id: pack.id.clone(),
            code: pack.code.clone(),
            revision: pack.revision,
            name: pack.name.clone(),
            include: Vec::new(),
            files,
        };
        if let Err(e) = write_link(self.paths(), &instance.id, &link).await {
            tracing::warn!("Pack-Verknüpfung für '{}' nicht gespeichert: {e}", instance.id);
        }
        let _ = tokio::fs::remove_file(&path).await;
        Ok(instance)
    }

    /// Installierte Packs mit neuerer Version.
    pub async fn pack_updates(&self) -> Result<Vec<PackUpdateInfo>> {
        let installed: Vec<(String, PackLink)> = {
            let mut v = Vec::new();
            for instance in self.instances().list().await? {
                if let Some(link) = read_link(self.paths(), &instance.id).await
                    && link.role == PackRole::Installed
                {
                    v.push((instance.id, link));
                }
            }
            v
        };
        if installed.is_empty() {
            return Ok(Vec::new());
        }
        let codes: Vec<String> = installed.iter().map(|(_, l)| l.code.clone()).collect();
        let latest = self.trs_packs_lookup(&codes).await?;
        Ok(installed
            .into_iter()
            .filter_map(|(instance_id, link)| {
                let pack = latest.iter().find(|p| p.id == link.pack_id)?;
                (pack.revision > link.revision).then(|| PackUpdateInfo { instance_id, revision: link.revision, latest: pack.clone() })
            })
            .collect())
    }

    /// Neue Version eines installierten Packs übernehmen.
    pub async fn update_pack_instance(&self, instance_id: &str, on_progress: &PackProgressFn) -> Result<PackUpdateResult> {
        let instance = self.instances().get(instance_id).await?;
        self.ensure_idle(&instance.id)?;
        let link = read_link(self.paths(), &instance.id).await.filter(|l| l.role == PackRole::Installed).ok_or_else(not_linked)?;
        on_progress(PackProgress::new(PackPhase::Pack, 0.0));
        let (path, pack) = self.fetch_pack(&link.code, &|done, total| {
            let percent = if total == 0 { 100.0 } else { (done as f64 / total as f64 * 100.0).min(100.0) };
            on_progress(PackProgress::new(PackPhase::Pack, percent));
        })
        .await?;
        if pack.id != link.pack_id {
            return Err(not_linked());
        }
        on_progress(PackProgress::new(PackPhase::Pack, 100.0));

        let game_dir = self.paths().instance_game_dir(&instance.id);
        let (p, g, old) = (path.clone(), game_dir.clone(), link.files.clone());
        let (index, downloads, overrides, plan) = tokio::task::spawn_blocking(move || -> Result<_> {
            let index = crate::modpack::read_index(&p)?;
            let (downloads, overrides) = pack_files(&p)?;
            let current = |rel: &str| safe_relative(rel).and_then(|r| file_sha1(&g.join(r)));
            let plan = plan_update(&old, &downloads, &overrides, &current);
            Ok((index, downloads, overrides, plan))
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))??;
        crate::task::checkpoint().await?;

        // Version/Loader wie im neuen Pack.
        let (game_version, loader) = (index.game_version()?.to_owned(), index.loader()?);
        if instance.game_version != game_version || instance.loader != loader {
            self.change_instance_version(&instance.id, &game_version, loader).await?;
        }

        // Downloads (Modrinth, per SHA-1 geprüft).
        let wanted: HashSet<&str> = plan.download.iter().map(String::as_str).collect();
        let tasks: Vec<Task> = crate::modpack::download_tasks(&index, &game_dir, true)?
            .into_iter()
            .filter(|t| t.path.strip_prefix(&game_dir).ok().is_some_and(|rel| wanted.contains(rel.to_string_lossy().replace('\\', "/").as_str())))
            .collect();
        let concurrency = usize::from(self.settings().await.concurrent_downloads);
        download::fetch_all(self.http(), tasks, concurrency, &|p| {
            on_progress(PackProgress::files(&p));
        })
        .await?;

        // Overrides schreiben, Altes löschen.
        on_progress(PackProgress::new(PackPhase::Overrides, 0.0));
        let (p, g) = (path.clone(), game_dir.clone());
        let write: HashSet<String> = plan.write.iter().cloned().collect();
        let delete = plan.delete.clone();
        let removed = tokio::task::spawn_blocking(move || -> Result<u32> {
            extract_selected(&p, &g, &write)?;
            let mut removed = 0;
            for rel in &delete {
                let Some(r) = safe_relative(rel) else { continue };
                let target = g.join(r);
                if std::fs::symlink_metadata(&target).is_ok_and(|m| m.is_file()) && std::fs::remove_file(&target).is_ok() {
                    removed += 1;
                }
            }
            Ok(removed)
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))??;
        on_progress(PackProgress::new(PackPhase::Overrides, 100.0));

        let mut files = downloads;
        files.extend(overrides);
        let new_link = PackLink { revision: pack.revision, name: pack.name.clone(), files, ..link.clone() };
        write_link(self.paths(), &instance.id, &new_link).await?;
        let pack_files: Vec<String> = new_link.files.keys().cloned().collect();
        if let Err(e) = crate::content_groups::record_pack_paths(self.paths(), &instance.id, &pack_files).await {
            tracing::warn!("Modpack-Herkunft für '{}' nicht gespeichert: {e}", instance.id);
        }
        history::record(
            self.paths(),
            &instance.id,
            HistoryEntry::new(HistoryKind::PackUpdated)
                .subject(&pack.name)
                .from(link.revision.to_string())
                .to(pack.revision.to_string()),
        )
        .await;
        let _ = tokio::fs::remove_file(&path).await;
        Ok(PackUpdateResult {
            revision: pack.revision,
            updated: u32::try_from(plan.download.len() + plan.write.len()).unwrap_or(u32::MAX),
            removed,
            kept: plan.kept.into_iter().take(200).collect(),
        })
    }
}

fn view(instance_id: &str, link: PackLink) -> PackLinkView {
    PackLinkView {
        instance_id: instance_id.to_owned(),
        role: link.role,
        pack_id: link.pack_id,
        code: link.code,
        revision: link.revision,
        name: link.name,
        include: link.include,
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    use super::*;
    use crate::trs_api::testkit::{MockServer, Response};
    use crate::trs_api::tests::signed_in;

    fn map(items: &[(&str, &str)]) -> BTreeMap<String, String> {
        items.iter().map(|(k, v)| ((*k).to_owned(), (*v).to_owned())).collect()
    }

    #[test]
    fn links_only_open_pack_codes() {
        assert_eq!(pack_code_from_link("trs-launcher://pack/TRS-7K2M-Q9XA").as_deref(), Some("TRS-7K2M-Q9XA"));
        assert_eq!(pack_code_from_link("TRS-Launcher://pack/trs-7k2m-q9xa/").as_deref(), Some("TRS-7K2M-Q9XA"));
        for bad in [
            "trs-launcher://pack/",
            "trs-launcher://pack/TRS-7K2M-Q9XA/x",
            "trs-launcher://pack/TRS-7K2M-Q9XA?install=1",
            "trs-launcher://evil/TRS-7K2M-Q9XA",
            "https://pack/TRS-7K2M-Q9XA",
            "trs-launcher://pack/..%2F..",
            "trs-launcher:pack/TRS-7K2M-Q9XA",
        ] {
            assert_eq!(pack_code_from_link(bad), None, "{bad}");
        }
    }

    #[test]
    fn update_keeps_what_the_player_changed() {
        let old = map(&[("mods/a.jar", "a1"), ("mods/gone.jar", "g1"), ("mods/gone-edited.jar", "e1"), ("config/x.toml", "x1"), ("config/y.toml", "y1")]);
        let downloads = map(&[("mods/a.jar", "a2"), ("mods/new.jar", "n1")]);
        let overrides = map(&[("config/x.toml", "x2"), ("config/y.toml", "y2"), ("config/z.toml", "z1")]);
        let disk = map(&[
            ("mods/a.jar", "a1"),
            ("mods/gone.jar", "g1"),
            ("mods/gone-edited.jar", "e-mine"),
            ("config/x.toml", "x1"),
            ("config/y.toml", "y-mine"),
        ]);
        let plan = plan_update(&old, &downloads, &overrides, &|p| disk.get(p).cloned());
        assert_eq!(plan.delete, vec!["mods/gone.jar"]);
        assert_eq!(plan.download, vec!["mods/a.jar", "mods/new.jar"]);
        assert_eq!(plan.write, vec!["config/x.toml", "config/z.toml"]);
        assert_eq!(plan.kept, vec!["mods/gone-edited.jar", "config/y.toml"]);
    }

    #[test]
    fn up_to_date_files_are_left_alone() {
        let old = map(&[("config/x.toml", "x1")]);
        let overrides = map(&[("config/x.toml", "x2")]);
        let disk = map(&[("config/x.toml", "x2")]);
        let plan = plan_update(&old, &BTreeMap::new(), &overrides, &|p| disk.get(p).cloned());
        assert_eq!(plan, UpdatePlan::default());
    }

    fn write_pack(path: &Path, extra: &[(&str, &[u8])]) {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        let opts = zip::write::SimpleFileOptions::default();
        zip.start_file("modrinth.index.json", opts).unwrap();
        zip.write_all(br#"{"formatVersion":1,"game":"minecraft","versionId":"1","name":"P","dependencies":{"minecraft":"1.21.1"},
            "files":[{"path":"mods/a.jar","hashes":{"sha1":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","sha512":"x"},"downloads":["https://cdn.modrinth.com/a.jar"]},
                     {"path":"mods/s.jar","hashes":{"sha1":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","sha512":"x"},"env":{"client":"unsupported","server":"required"},"downloads":["https://cdn.modrinth.com/s.jar"]}]}"#)
            .unwrap();
        for (name, data) in extra {
            zip.start_file(*name, opts).unwrap();
            zip.write_all(data).unwrap();
        }
        zip.finish().unwrap();
    }

    #[test]
    fn pack_files_layer_like_the_installer() {
        let dir = tempfile::tempdir().unwrap();
        let pack = dir.path().join("p.mrpack");
        write_pack(&pack, &[
            ("overrides/config/a.toml", b"base"),
            ("client-overrides/config/a.toml", b"client"),
            ("overrides/options.txt", b"fov"),
            ("overrides/../evil.txt", b"x"),
            ("server-overrides/server.properties", b"nope"),
        ]);
        let (downloads, overrides) = pack_files(&pack).unwrap();
        assert_eq!(downloads, map(&[("mods/a.jar", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]));
        assert_eq!(overrides.keys().collect::<Vec<_>>(), vec!["config/a.toml", "options.txt"]);
        assert_eq!(overrides["config/a.toml"], hex(&sha1::Sha1::digest(b"client")));

        let game = dir.path().join("game");
        let wanted: HashSet<String> = ["config/a.toml".to_owned()].into();
        extract_selected(&pack, &game, &wanted).unwrap();
        assert_eq!(std::fs::read_to_string(game.join("config/a.toml")).unwrap(), "client");
        assert!(!game.join("options.txt").exists());
        assert!(!dir.path().join("evil.txt").exists());
    }

    #[tokio::test]
    async fn link_file_is_cleaned_on_read() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().to_path_buf());
        std::fs::create_dir_all(paths.instance_dir("inst")).unwrap();
        let text = serde_json::json!({
            "role": "installed", "packId": "Qm9vLWJhei1xdXV4LTEyMw", "code": "trs-ab12-cd34", "revision": 3, "name": "P\u{7}ack",
            "files": { "mods/a.jar": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "../../evil": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "config/x": "nope" }
        });
        std::fs::write(link_path(&paths, "inst"), text.to_string()).unwrap();
        let link = read_link(&paths, "inst").await.unwrap();
        assert_eq!(link.code, "TRS-AB12-CD34");
        assert_eq!(link.name, "Pack");
        assert_eq!(link.files.keys().collect::<Vec<_>>(), vec!["mods/a.jar"]);

        std::fs::write(link_path(&paths, "inst"), text.to_string().replace("Qm9vLWJhei1xdXV4LTEyMw", "../x")).unwrap();
        assert!(read_link(&paths, "inst").await.is_none());
        assert!(read_link(&paths, "../inst").await.is_none());
    }

    #[test]
    fn content_range_start_reads_the_first_byte() {
        assert_eq!(content_range_start(Some("bytes 12-34/100")), Some(12));
        assert_eq!(content_range_start(Some("bytes 0-0/1")), Some(0));
        assert_eq!(content_range_start(Some("bytes */100")), None);
        assert_eq!(content_range_start(Some("12-34/100")), None);
        assert_eq!(content_range_start(None), None);
    }

    const SHARE_TOKEN: &str = "0123456789abcdef0123456789abcdef";
    const PACK_CODE: &str = "TRS-7K2M-Q9XA";
    const CHUNK: u64 = 8;

    fn sha256_of(bytes: &[u8]) -> String {
        let mut hasher = sha2::Sha256::new();
        sha2::Digest::update(&mut hasher, bytes);
        hex(&sha2::Digest::finalize(hasher))
    }

    fn sample_bytes() -> Vec<u8> {
        b"abcdefghijklmnopqrstuvwx".to_vec()
    }

    /// Legt `.mrpack` + Merkzettel so ab, wie `share_pack` sie hinterlässt.
    fn plant_share(paths: &Paths, bytes: &[u8], upload_id: Option<&str>, upload_expires: Option<&str>) -> String {
        let dir = crate::modpack::pack_cache_dir(paths);
        std::fs::create_dir_all(&dir).unwrap();
        let (pack, draft) = share_paths_in(&dir, SHARE_TOKEN).unwrap();
        std::fs::write(&pack, bytes).unwrap();
        let sha = sha256_of(bytes);
        let mut value = serde_json::json!({
            "instanceId": "testpack",
            "update": false,
            "options": { "name": "Pack", "version": "1.0.0", "include": ["config"], "duration": "7d" },
            "downloads": 2,
            "uploaded": 1,
            "bytes": bytes.len(),
            "sha256": sha
        });
        if let Some(id) = upload_id {
            value["uploadId"] = serde_json::json!(id);
        }
        if let Some(exp) = upload_expires {
            value["uploadExpires"] = serde_json::json!(exp);
        }
        std::fs::write(&draft, value.to_string()).unwrap();
        sha
    }

    fn own_pack_json() -> serde_json::Value {
        serde_json::json!({
            "pack": {
                "id": "Qm9vLWJhei1xdXV4LTEyMw", "code": PACK_CODE, "name": "Pack", "packVersion": "1.0.0",
                "revision": 1, "mcVersion": "1.21.1", "loader": { "kind": "fabric", "version": "0.16.9" },
                "bytes": 24, "sha256": "ab".repeat(32),
                "owner": { "uuid": "0123456789abcdef0123456789abcdef", "name": "Alex" },
                "duration": "7d", "installs": 0, "sentTo": 0
            }
        })
    }

    fn upload_created(id: &str) -> Response {
        Response::json(201, serde_json::json!({
            "uploadId": id, "chunkSize": CHUNK, "expiresAt": "2099-01-01T00:00:00Z"
        }))
    }

    fn shared_pack_json(sha: &str, bytes: usize) -> serde_json::Value {
        serde_json::json!({
            "pack": {
                "id": "Qm9vLWJhei1xdXV4LTEyMw", "code": PACK_CODE, "name": "Pack", "packVersion": "1.0.0",
                "revision": 2, "mcVersion": "1.21.1", "loader": { "kind": "fabric" },
                "bytes": bytes, "sha256": sha,
                "owner": { "uuid": "0123456789abcdef0123456789abcdef", "name": "Alex" }
            }
        })
    }

    fn file_response(status: u16, body: Vec<u8>, content_range: Option<&str>) -> Response {
        let mut headers = Vec::new();
        if let Some(range) = content_range {
            headers.push(("content-range".into(), range.into()));
        }
        Response { status, headers, body }
    }

    fn chunk_paths(server: &MockServer) -> Vec<String> {
        server.requests().into_iter().filter(|r| r.method == "PUT").map(|r| r.path).collect()
    }

    #[tokio::test]
    async fn chunk_upload_sends_in_order_and_retries_the_same_chunk() {
        let fails = Arc::new(AtomicUsize::new(0));
        let seen = Arc::clone(&fails);
        let server = MockServer::start(move |req| {
            let path = req.path.split('?').next().unwrap_or("");
            match (req.method.as_str(), path) {
                ("POST", "/v1/packs/uploads") => upload_created("upload-01"),
                ("PUT", p) if p == "/v1/packs/uploads/upload-01/chunks/1" && seen.fetch_add(1, Ordering::SeqCst) == 0 => {
                    Response::error(500, "database_unavailable")
                }
                ("PUT", p) if p.starts_with("/v1/packs/uploads/upload-01/chunks/") => Response::empty(204),
                ("POST", "/v1/packs/uploads/upload-01/complete") => {
                    Response::json(200, serde_json::json!({ "uploadToken": "token-abc" }))
                }
                ("POST", "/v1/packs") => Response::json(201, own_pack_json()),
                _ => Response::error(404, "not_found"),
            }
        })
        .await;
        let (_dir, launcher) = signed_in(&server).await;
        let bytes = sample_bytes();
        let sha = plant_share(launcher.paths(), &bytes, None, None);

        let pack = launcher.upload_shared_pack(SHARE_TOKEN).await.unwrap();
        assert_eq!(pack.pack.code, PACK_CODE);

        let begin = server.hits("POST", "/v1/packs/uploads");
        assert_eq!(begin.len(), 1);
        assert_eq!(begin[0].json()["size"], bytes.len());
        assert_eq!(begin[0].json()["sha256"], sha);
        assert_eq!(begin[0].json()["name"], "Pack");

        let puts = server.requests().into_iter().filter(|r| r.method == "PUT").collect::<Vec<_>>();
        let indexes: Vec<_> = puts.iter().map(|r| r.path.rsplit('/').next().unwrap()).collect();
        assert_eq!(indexes, vec!["0", "1", "1", "2"]);
        assert_eq!(puts[0].body, bytes[0..8]);
        assert_eq!(puts[1].body, bytes[8..16]);
        assert_eq!(puts[2].body, bytes[8..16]);
        assert_eq!(puts[3].body, bytes[16..24]);
        for put in &puts {
            assert_eq!(put.header("x-chunk-sha256"), Some(sha256_of(&put.body).as_str()));
            assert_eq!(put.header("content-type"), Some("application/octet-stream"));
        }

        let finish = server.hits("POST", "/v1/packs");
        assert_eq!(finish.len(), 1);
        assert_eq!(finish[0].json()["uploadToken"], "token-abc");
        assert!(finish[0].path.contains("duration=7d"));

        let link = read_link(launcher.paths(), "testpack").await.unwrap();
        assert_eq!(link.role, PackRole::Shared);
        assert_eq!(link.include, vec!["config".to_owned()]);
        let (pack_path, draft_path) = share_paths(launcher.paths(), SHARE_TOKEN).unwrap();
        assert!(!pack_path.exists());
        assert!(!draft_path.exists());
    }

    #[tokio::test]
    async fn chunk_upload_resumes_the_missing_indexes() {
        let server = MockServer::start(|req| {
            let path = req.path.split('?').next().unwrap_or("");
            match (req.method.as_str(), path) {
                ("GET", "/v1/packs/uploads/upload-01") => Response::json(
                    200,
                    serde_json::json!({ "received": [0, 2], "size": 24, "chunkSize": CHUNK }),
                ),
                ("PUT", "/v1/packs/uploads/upload-01/chunks/1") => Response::empty(204),
                ("POST", "/v1/packs/uploads/upload-01/complete") => {
                    Response::json(200, serde_json::json!({ "uploadToken": "token-abc" }))
                }
                ("POST", "/v1/packs") => Response::json(201, own_pack_json()),
                _ => Response::error(404, "not_found"),
            }
        })
        .await;
        let (_dir, launcher) = signed_in(&server).await;
        let bytes = sample_bytes();
        plant_share(launcher.paths(), &bytes, Some("upload-01"), Some("2099-01-01T00:00:00Z"));

        launcher.upload_shared_pack(SHARE_TOKEN).await.unwrap();
        assert!(server.hits("POST", "/v1/packs/uploads").is_empty());
        let puts = server.requests().into_iter().filter(|r| r.method == "PUT").collect::<Vec<_>>();
        assert_eq!(puts.len(), 1);
        assert_eq!(puts[0].path, "/v1/packs/uploads/upload-01/chunks/1");
        assert_eq!(puts[0].body, bytes[8..16]);
        assert_eq!(puts[0].header("x-chunk-sha256"), Some(sha256_of(&puts[0].body).as_str()));
    }

    #[tokio::test]
    async fn chunk_upload_does_not_retry_a_bad_hash() {
        let server = MockServer::start(|req| {
            let path = req.path.split('?').next().unwrap_or("");
            match (req.method.as_str(), path) {
                ("POST", "/v1/packs/uploads") => upload_created("upload-01"),
                ("PUT", _) => Response::error(400, "chunk_hash"),
                _ => Response::error(404, "not_found"),
            }
        })
        .await;
        let (_dir, launcher) = signed_in(&server).await;
        plant_share(launcher.paths(), &sample_bytes(), None, None);

        let err = launcher.upload_shared_pack(SHARE_TOKEN).await.unwrap_err();
        assert_eq!(err.code(), Some("chunk_hash"));
        assert_eq!(chunk_paths(&server), vec!["/v1/packs/uploads/upload-01/chunks/0".to_owned()]);
        let (_, draft_path) = share_paths(launcher.paths(), SHARE_TOKEN).unwrap();
        let draft: serde_json::Value = serde_json::from_slice(&std::fs::read(draft_path).unwrap()).unwrap();
        assert_eq!(draft["uploadId"], "upload-01");
    }

    #[tokio::test]
    async fn oversized_pack_never_starts_an_upload() {
        let server = MockServer::start(|_| Response::error(500, "nope")).await;
        let (_dir, launcher) = signed_in(&server).await;
        let sha = "ab".repeat(32);
        let err = launcher.trs_pack_upload_begin(MAX_PACK_BYTES + 1, &sha, None).await.unwrap_err();
        assert_eq!(err.message_code(), "packShare.tooLarge");

        let dir = crate::modpack::pack_cache_dir(launcher.paths());
        std::fs::create_dir_all(&dir).unwrap();
        let (pack, draft) = share_paths_in(&dir, SHARE_TOKEN).unwrap();
        std::fs::File::create(&pack).unwrap().set_len(MAX_PACK_BYTES + 1).unwrap();
        std::fs::write(
            &draft,
            serde_json::json!({
                "instanceId": "testpack",
                "update": false,
                "options": { "name": "Pack", "version": "1.0.0", "duration": "7d" },
                "downloads": 0,
                "uploaded": 0,
                "bytes": MAX_PACK_BYTES + 1,
                "sha256": sha
            })
            .to_string(),
        )
        .unwrap();
        let err = launcher.upload_shared_pack(SHARE_TOKEN).await.unwrap_err();
        assert_eq!(err.message_code(), "packShare.tooLarge");
        assert!(server.requests().is_empty());
    }

    #[tokio::test]
    async fn expired_session_starts_one_new_upload() {
        let server = MockServer::start(|req| {
            let path = req.path.split('?').next().unwrap_or("");
            match (req.method.as_str(), path) {
                ("POST", "/v1/packs/uploads") => upload_created("uploadnew"),
                ("PUT", p) if p.starts_with("/v1/packs/uploads/uploadnew/chunks/") => Response::empty(204),
                ("POST", "/v1/packs/uploads/uploadnew/complete") => {
                    Response::json(200, serde_json::json!({ "uploadToken": "token-abc" }))
                }
                ("POST", "/v1/packs") => Response::json(201, own_pack_json()),
                _ => Response::error(404, "not_found"),
            }
        })
        .await;
        let (_dir, launcher) = signed_in(&server).await;
        plant_share(launcher.paths(), &sample_bytes(), Some("oldupload"), Some("2000-01-01T00:00:00Z"));

        launcher.upload_shared_pack(SHARE_TOKEN).await.unwrap();
        assert_eq!(server.hits("POST", "/v1/packs/uploads").len(), 1);
        assert!(server.requests().iter().all(|r| !r.path.contains("oldupload")));
        assert_eq!(chunk_paths(&server).len(), 3);
    }

    #[tokio::test]
    async fn expired_complete_restarts_the_session_once() {
        let begins = Arc::new(AtomicUsize::new(0));
        let count = Arc::clone(&begins);
        let server = MockServer::start(move |req| {
            let path = req.path.split('?').next().unwrap_or("");
            match (req.method.as_str(), path) {
                ("POST", "/v1/packs/uploads") => {
                    let n = count.fetch_add(1, Ordering::SeqCst);
                    upload_created(if n == 0 { "uploadold" } else { "uploadnew" })
                }
                ("PUT", p) if p.contains("/chunks/") => Response::empty(204),
                ("POST", "/v1/packs/uploads/uploadold/complete") => Response::error(404, "upload_expired"),
                ("POST", "/v1/packs/uploads/uploadnew/complete") => {
                    Response::json(200, serde_json::json!({ "uploadToken": "token-abc" }))
                }
                ("POST", "/v1/packs") => Response::json(201, own_pack_json()),
                _ => Response::error(404, "not_found"),
            }
        })
        .await;
        let (_dir, launcher) = signed_in(&server).await;
        plant_share(launcher.paths(), &sample_bytes(), None, None);

        launcher.upload_shared_pack(SHARE_TOKEN).await.unwrap();
        assert_eq!(begins.load(Ordering::SeqCst), 2);
        let puts = chunk_paths(&server);
        assert_eq!(puts.len(), 6);
        assert!(puts[..3].iter().all(|p| p.contains("uploadold")));
        assert!(puts[3..].iter().all(|p| p.contains("uploadnew")));
        assert_eq!(server.hits("POST", "/v1/packs/uploads/uploadold/complete").len(), 1);
        assert_eq!(server.hits("POST", "/v1/packs/uploads/uploadnew/complete").len(), 1);
    }

    #[tokio::test]
    async fn pack_download_resumes_with_range() {
        let body = b"0123456789abcdef".to_vec();
        let sha = sha256_of(&body);
        let file_sha = sha.clone();
        let file_body = body.clone();
        let server = MockServer::start(move |req| {
            let path = req.path.split('?').next().unwrap_or("");
            if req.method == "GET" && path == format!("/v1/packs/code/{PACK_CODE}") {
                return Response::json(200, shared_pack_json(&file_sha, file_body.len()));
            }
            if req.method == "GET" && path == format!("/v1/packs/code/{PACK_CODE}/file") {
                let rest = file_body[6..].to_vec();
                return file_response(206, rest, Some("bytes 6-15/16"));
            }
            Response::error(404, "not_found")
        })
        .await;
        let (_dir, launcher) = signed_in(&server).await;
        let dir = crate::modpack::pack_cache_dir(launcher.paths());
        std::fs::create_dir_all(&dir).unwrap();
        let part = dir.join(format!("trs-{sha}.mrpack")).with_extension("part");
        std::fs::write(&part, &b"0123456789abcdef"[..6]).unwrap();

        let (path, pack) = launcher.fetch_pack(PACK_CODE, &|_, _| {}).await.unwrap();
        assert_eq!(pack.sha256, sha);
        assert_eq!(std::fs::read(&path).unwrap(), b"0123456789abcdef");
        assert!(!part.exists());
        let files = server.hits("GET", &format!("/v1/packs/code/{PACK_CODE}/file"));
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].header("range"), Some("bytes=6-"));
    }

    #[tokio::test]
    async fn pack_download_rewrites_when_the_server_ignores_range() {
        let body = b"0123456789abcdef".to_vec();
        let sha = sha256_of(&body);
        let file_sha = sha.clone();
        let len = body.len();
        let server = MockServer::start(move |req| {
            let path = req.path.split('?').next().unwrap_or("");
            if req.method == "GET" && path == format!("/v1/packs/code/{PACK_CODE}") {
                return Response::json(200, shared_pack_json(&file_sha, len));
            }
            if req.method == "GET" && path == format!("/v1/packs/code/{PACK_CODE}/file") {
                return file_response(200, body.clone(), None);
            }
            Response::error(404, "not_found")
        })
        .await;
        let (_dir, launcher) = signed_in(&server).await;
        let dir = crate::modpack::pack_cache_dir(launcher.paths());
        std::fs::create_dir_all(&dir).unwrap();
        let part = dir.join(format!("trs-{sha}.mrpack")).with_extension("part");
        std::fs::write(&part, b"XXXXXX").unwrap();

        let (path, _) = launcher.fetch_pack(PACK_CODE, &|_, _| {}).await.unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"0123456789abcdef");
    }

    #[tokio::test]
    async fn cached_pack_is_not_downloaded_again() {
        let body = b"0123456789abcdef".to_vec();
        let sha = sha256_of(&body);
        let file_sha = sha.clone();
        let len = body.len();
        let server = MockServer::start(move |req| {
            let path = req.path.split('?').next().unwrap_or("");
            if req.method == "GET" && path == format!("/v1/packs/code/{PACK_CODE}") {
                return Response::json(200, shared_pack_json(&file_sha, len));
            }
            Response::error(500, "nope")
        })
        .await;
        let (_dir, launcher) = signed_in(&server).await;
        let dir = crate::modpack::pack_cache_dir(launcher.paths());
        std::fs::create_dir_all(&dir).unwrap();
        let dest = dir.join(format!("trs-{sha}.mrpack"));
        std::fs::write(&dest, &body).unwrap();

        let (path, pack) = launcher.fetch_pack(PACK_CODE, &|done, total| {
            assert_eq!((done, total), (body.len() as u64, body.len() as u64));
        })
        .await
        .unwrap();
        assert_eq!(path, dest);
        assert_eq!(pack.bytes, body.len() as u64);
        assert!(server.hits("GET", &format!("/v1/packs/code/{PACK_CODE}/file")).is_empty());
    }

    #[tokio::test]
    async fn pack_download_aborts_when_the_checksum_does_not_match() {
        let body = b"0123456789abcdef".to_vec();
        let sha = sha256_of(&body);
        let file_sha = sha.clone();
        let len = body.len();
        let server = MockServer::start(move |req| {
            let path = req.path.split('?').next().unwrap_or("");
            if req.method == "GET" && path == format!("/v1/packs/code/{PACK_CODE}") {
                return Response::json(200, shared_pack_json(&file_sha, len));
            }
            if req.method == "GET" && path == format!("/v1/packs/code/{PACK_CODE}/file") {
                return file_response(200, vec![0; len], None);
            }
            Response::error(404, "not_found")
        })
        .await;
        let (_dir, launcher) = signed_in(&server).await;
        let err = launcher.fetch_pack(PACK_CODE, &|_, _| {}).await.unwrap_err();
        assert_eq!(err.message_code(), "checksum");
        let dir = crate::modpack::pack_cache_dir(launcher.paths());
        let dest = dir.join(format!("trs-{sha}.mrpack"));
        assert!(!dest.exists());
        assert!(!dest.with_extension("part").exists());
        assert_eq!(server.hits("GET", &format!("/v1/packs/code/{PACK_CODE}/file")).len(), 2);
    }
}
