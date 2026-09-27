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
use std::io::Read;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha1::Digest as _;

use crate::download::{self, Task};
use crate::history::{HistoryEntry, HistoryKind};
use crate::instance::{Instance, validate_id};
use crate::modpack::{PackPhase, PackPreview, PackProgress, PackProgressFn, safe_relative};
use crate::modpack_export::{ExportOptions, ExportPhase, ExportProgress, ExportProgressFn};
use crate::paths::Paths;
use crate::trs_api::packs::{MAX_PACK_BYTES, OwnPack, SharedPack, normalize_code, pack_id};
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

#[derive(Debug, Clone, Deserialize)]
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

/// Ergebnis von „Teilen“: geteilt – oder erst bestätigen, dass eigene Mod-Dateien mitgehen.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "status", rename_all = "camelCase")]
pub enum SharePackOutcome {
    Shared { pack: Box<OwnPack> },
    #[serde(rename_all = "camelCase")]
    ConfirmOwnJars { files: Vec<String> },
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

    /// Instanz packen und teilen (`update` = neue Version des schon geteilten Packs, gleicher Code).
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
        let dest = dir.join(format!("share-{}.mrpack", uuid::Uuid::new_v4().simple()));
        let export = ExportOptions {
            name: options.name.clone(),
            version: options.version.clone(),
            summary: options.summary.clone(),
            include: options.include.clone(),
        };
        let result = async {
            self.export_modpack(&instance.id, &export, &dest, on_progress).await?;
            let path = dest.clone();
            let own = tokio::task::spawn_blocking(move || {
                crate::modpack::override_mod_names(&path, &["overrides/", "client-overrides/"])
                    .into_iter()
                    .filter(|n| n.to_ascii_lowercase().ends_with(".jar"))
                    .collect::<Vec<_>>()
            })
            .await
            .map_err(|e| Error::Internal(e.to_string()))?;
            if !own.is_empty() && !options.allow_own_jars {
                return Ok(SharePackOutcome::ConfirmOwnJars { files: own.into_iter().take(200).collect() });
            }
            let bytes = tokio::fs::read(&dest).await.map_err(|e| Error::io(&dest, e))?;
            if bytes.len() > MAX_PACK_BYTES {
                return Err(invalid(crate::msg!(
                    "packShare.tooLarge",
                    "Das Modpack ist größer als 50 MB – wähle weniger Ordner aus (z. B. ohne Resource Packs)."
                )));
            }
            on_progress(ExportProgress { phase: ExportPhase::Uploading, percent: 0.0 });
            let pack = match &target {
                Some(id) => self.trs_pack_upload_version(id, bytes).await?,
                None => self.trs_pack_upload(bytes, &options.duration).await?,
            };
            on_progress(ExportProgress { phase: ExportPhase::Uploading, percent: 100.0 });
            let link = PackLink {
                role: PackRole::Shared,
                pack_id: pack.pack.id.clone(),
                code: pack.pack.code.clone(),
                revision: pack.pack.revision,
                name: pack.pack.name.clone(),
                include: options.include.clone(),
                files: BTreeMap::new(),
            };
            write_link(self.paths(), &instance.id, &link).await?;
            Ok(SharePackOutcome::Shared { pack: Box::new(pack) })
        }
        .await;
        let _ = tokio::fs::remove_file(&dest).await;
        result
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

    /// Pack herunterladen und gegen die Prüfsumme der API prüfen. Liegt es schon im Zwischenspeicher, wird es
    /// nicht neu geladen. Rückgabe: Datei + aktueller Stand.
    async fn fetch_pack(&self, code: &str) -> Result<(PathBuf, SharedPack)> {
        let mut pack = self.trs_pack_by_code(code).await?;
        let dir = crate::modpack::pack_cache_dir(self.paths());
        fsutil::ensure_dir(&dir).await?;
        for attempt in 0..2 {
            let path = dir.join(format!("trs-{}.mrpack", pack.sha256));
            if let Ok(bytes) = tokio::fs::read(&path).await
                && sha256_hex(&bytes) == pack.sha256
            {
                return Ok((path, pack));
            }
            let bytes = self.trs_pack_download(&pack.code).await?;
            if sha256_hex(&bytes) == pack.sha256 {
                let tmp = dir.join(format!("trs-{}.part", uuid::Uuid::new_v4().simple()));
                tokio::fs::write(&tmp, &bytes).await.map_err(|e| Error::io(&tmp, e))?;
                tokio::fs::rename(&tmp, &path).await.map_err(|e| Error::io(&path, e))?;
                return Ok((path, pack));
            }
            // Während des Ladens kam eine neue Version – einmal neu fragen.
            if attempt == 0 {
                pack = self.trs_pack_by_code(&pack.code).await?;
            }
        }
        Err(invalid(crate::msg!("packShare.changed", "Das Modpack hat sich gerade geändert – bitte noch einmal versuchen.")))
    }

    /// Vorschau zu einem Code: Pack laden (bleibt für die Installation liegen) und prüfen, was drinsteckt.
    pub async fn preview_pack_code(&self, code: &str) -> Result<PackCodePreview> {
        let (path, pack) = self.fetch_pack(code).await?;
        let mut preview = self.preview_pack_file(&path).await?;
        preview.name.clone_from(&pack.name);
        Ok(PackCodePreview { pack, preview })
    }

    /// Pack per Code als neue Instanz installieren und für Updates merken.
    pub async fn install_pack_code(&self, code: &str, trs_client: Option<bool>, on_progress: &PackProgressFn) -> Result<Instance> {
        on_progress(PackProgress { phase: PackPhase::Pack, percent: 0.0 });
        let (path, pack) = self.fetch_pack(code).await?;
        on_progress(PackProgress { phase: PackPhase::Pack, percent: 100.0 });
        let instance = self.install_local_pack(&path, trs_client, on_progress).await?;
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
        on_progress(PackProgress { phase: PackPhase::Pack, percent: 0.0 });
        let (path, pack) = self.fetch_pack(&link.code).await?;
        if pack.id != link.pack_id {
            return Err(not_linked());
        }
        on_progress(PackProgress { phase: PackPhase::Pack, percent: 100.0 });

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
        let tasks: Vec<Task> = crate::modpack::download_tasks(&index, &game_dir)?
            .into_iter()
            .filter(|t| t.path.strip_prefix(&game_dir).ok().is_some_and(|rel| wanted.contains(rel.to_string_lossy().replace('\\', "/").as_str())))
            .collect();
        let concurrency = usize::from(self.settings().await.concurrent_downloads);
        download::fetch_all(self.http(), tasks, concurrency, &|p| {
            on_progress(PackProgress { phase: PackPhase::Files, percent: p.percent() });
        })
        .await?;

        // Overrides schreiben, Altes löschen.
        on_progress(PackProgress { phase: PackPhase::Overrides, percent: 0.0 });
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
        on_progress(PackProgress { phase: PackPhase::Overrides, percent: 100.0 });

        let mut files = downloads;
        files.extend(overrides);
        let new_link = PackLink { revision: pack.revision, name: pack.name.clone(), files, ..link.clone() };
        write_link(self.paths(), &instance.id, &new_link).await?;
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

    use super::*;

    fn map(items: &[(&str, &str)]) -> BTreeMap<String, String> {
        items.iter().map(|(k, v)| ((*k).to_owned(), (*v).to_owned())).collect()
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
}
