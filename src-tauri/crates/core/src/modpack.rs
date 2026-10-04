//! Modrinth-Modpacks (`.mrpack`): legt aus einem Pack eine neue Instanz an.

use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::content::ContentKind;
use crate::download::{self, Task};
use crate::instance::{Instance, Loader, LoaderKind, NewInstance};
use crate::modrinth::{self, CDN_PREFIX};
use crate::trs_choice::{self, ModHint, TrsOffer};
use crate::{Error, Launcher, Result, fsutil};

const MAX_INDEX_BYTES: u64 = 16 << 20;
const MAX_PACK_FILES: usize = 5000;

#[derive(Debug, Clone, Copy, Default, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum PackPhase {
    #[default]
    Pack,
    Files,
    Overrides,
}

/// Fortschritt einer Modpack-Installation. `instance_id` kommt einmal, sobald die Instanz angelegt ist (die Oberfläche
/// zeigt sie dann als „wird installiert“ statt als fertig); `done_files`/`total_files` beim Laden der Dateien.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PackProgress {
    pub phase: PackPhase,
    pub percent: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub done_files: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total_files: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub instance_id: Option<String>,
}

impl PackProgress {
    pub fn new(phase: PackPhase, percent: f64) -> Self {
        Self { phase, percent, ..Self::default() }
    }

    /// Stand beim Laden der Pack-Dateien (Prozent nach Bytes, dazu Dateien).
    pub fn files(p: &download::Progress) -> Self {
        Self { phase: PackPhase::Files, percent: p.percent(), done_files: Some(p.done_files), total_files: Some(p.total_files), instance_id: None }
    }

    /// Die Instanz ist angelegt – ab jetzt gehört sie zu dieser Installation.
    pub fn created(instance_id: &str) -> Self {
        Self { phase: PackPhase::Files, percent: 0.0, instance_id: Some(instance_id.to_owned()), ..Self::default() }
    }
}

pub type PackProgressFn = dyn Fn(PackProgress) + Send + Sync;

/// Gleichzeitige Downloads beim Installieren eines Packs: das Doppelte der Einstellung (höchstens 32). Packs bestehen
/// aus Hunderten Dateien, und CDNs wie CurseForges liefern je Verbindung nur wenige MB/s.
pub(crate) fn pack_concurrency(setting: u8) -> usize {
    (usize::from(setting) * 2).clamp(1, 32)
}

/// Große Dateien zuerst, damit am Ende nicht eine einzelne große Mod allein nachlädt.
pub(crate) fn pack_download_order(mut tasks: Vec<Task>) -> Vec<Task> {
    tasks.sort_by_key(|t| std::cmp::Reverse(t.size.unwrap_or(0)));
    tasks
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct PackIndex {
    format_version: u32,
    game: String,
    name: String,
    #[serde(default)]
    files: Vec<PackFile>,
    dependencies: HashMap<String, String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PackFile {
    path: String,
    hashes: PackHashes,
    env: Option<PackEnv>,
    #[serde(default)]
    downloads: Vec<String>,
    file_size: Option<u64>,
}

#[derive(Debug, Deserialize)]
struct PackHashes {
    sha1: String,
    /// SHA-512, wenn der Index einen nennt. Fehlt er, reicht SHA-1.
    #[serde(default)]
    sha512: Option<String>,
}

fn hex_len(value: &str, len: usize) -> bool {
    value.len() == len && value.bytes().all(|b| b.is_ascii_hexdigit())
}

#[derive(Debug, Deserialize)]
struct PackEnv {
    client: Option<String>,
    #[serde(default)]
    server: Option<String>,
}

/// Ein Inhalt des Packs für den Preset-Editor („Aus Modpack übernehmen“).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct PackPick {
    pub file_name: String,
    pub kind: ContentKind,
    /// Modrinth-Projekt aus dem Download-Link (`cdn.modrinth.com/data/<projekt>/…`).
    pub modrinth: Option<String>,
    /// Laut Index nur im Client (`env.server = unsupported`); `None` = keine Angabe.
    pub client_only: Option<bool>,
}

impl PackIndex {
    pub(crate) fn loader(&self) -> Result<Loader> {
        let pick = |key: &str, kind| self.dependencies.get(key).map(|v| Loader { kind, version: Some(v.clone()) });
        Ok(pick("fabric-loader", LoaderKind::Fabric)
            .or_else(|| pick("quilt-loader", LoaderKind::Quilt))
            .or_else(|| pick("neoforge", LoaderKind::NeoForge))
            .or_else(|| pick("forge", LoaderKind::Forge))
            .unwrap_or_else(Loader::vanilla))
    }

    /// Dateien, die der Client bekommt: (Pfad im Spielordner, SHA-1 klein geschrieben).
    pub(crate) fn client_files(&self) -> Vec<(String, String)> {
        self.files
            .iter()
            .filter(|f| f.env.as_ref().and_then(|e| e.client.as_deref()) != Some("unsupported"))
            .map(|f| (f.path.clone(), f.hashes.sha1.to_ascii_lowercase()))
            .collect()
    }

    /// Mods, Ressourcen- und Shaderpakete des Packs, die der Client bekommt (ohne Netz).
    /// `bundled`: Jars, die das Pack direkt in seinen Zusatzordnern mitbringt (ohne Projekt).
    pub(crate) fn picks(&self, bundled: Vec<String>) -> Vec<PackPick> {
        let kind_of = |path: &str| {
            [ContentKind::Mod, ContentKind::ResourcePack, ContentKind::ShaderPack]
                .into_iter()
                .find(|k| path.strip_prefix(k.dir_name()).is_some_and(|rest| rest.starts_with('/') && !rest[1..].contains('/')))
        };
        self.files
            .iter()
            .filter(|f| f.env.as_ref().and_then(|e| e.client.as_deref()) != Some("unsupported"))
            .filter_map(|f| {
                let kind = kind_of(&f.path)?;
                let file_name = f.path.rsplit('/').next().filter(|n| !n.is_empty())?.to_owned();
                let modrinth = f.downloads.iter().find_map(|u| trs_choice::modrinth_project_of(u));
                let client_only = f.env.as_ref().and_then(|e| e.server.as_deref()).map(|s| s == "unsupported");
                Some(PackPick { file_name, kind, modrinth, client_only })
            })
            .chain(bundled.into_iter().map(|file_name| PackPick { file_name, kind: ContentKind::Mod, modrinth: None, client_only: None }))
            .collect()
    }

    pub(crate) fn name(&self) -> &str {
        &self.name
    }

    pub(crate) fn game_version(&self) -> Result<&str> {
        self.dependencies
            .get("minecraft")
            .map(String::as_str)
            .ok_or_else(|| Error::validation(crate::msg!(
                "modpack.noGameVersion",
                "Das Modpack nennt keine Minecraft-Version."
            )))
    }
}

/// Pfade im Pack sind relativ zum Spielordner und dürfen ihn nicht verlassen.
pub(crate) fn safe_relative(path: &str) -> Option<PathBuf> {
    let ok = !path.is_empty()
        && path.len() <= 260
        && !path.starts_with('/')
        && !path.contains('\\')
        && !path.contains(':')
        && path.split('/').all(|seg| {
            !seg.is_empty() && seg != "." && seg != ".." && !seg.ends_with('.') && !seg.chars().any(char::is_control)
        });
    ok.then(|| path.split('/').collect())
}

pub(crate) fn read_index(pack: &Path) -> Result<PackIndex> {
    let file = std::fs::File::open(pack).map_err(|e| Error::io(pack, e))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| Error::validation(crate::msg!(
        "modpack.corrupt",
        "Das Modpack ist beschädigt."
    )))?;
    let entry = archive
        .by_name("modrinth.index.json")
        .map_err(|_| Error::validation(crate::msg!(
            "modpack.notModrinthPack",
            "Das ist kein gültiges Modrinth-Modpack."
        )))?;
    if entry.size() > MAX_INDEX_BYTES {
        return Err(Error::validation(crate::msg!("modpack.corrupt", "Das Modpack ist beschädigt.")));
    }
    let mut text = String::new();
    entry
        .take(MAX_INDEX_BYTES)
        .read_to_string(&mut text)
        .map_err(|_| Error::validation(crate::msg!("modpack.corrupt", "Das Modpack ist beschädigt.")))?;
    let index: PackIndex =
        serde_json::from_str(&text).map_err(|e| Error::json("modrinth.index.json", e))?;
    if index.format_version != 1 || index.game != "minecraft" {
        return Err(Error::validation(crate::msg!(
            "modpack.unsupportedFormat",
            "Dieses Modpack-Format wird nicht unterstützt."
        )));
    }
    if index.files.len() > MAX_PACK_FILES {
        return Err(Error::validation(crate::msg!("modpack.tooManyFiles", "Das Modpack enthält zu viele Dateien.")));
    }
    Ok(index)
}

/// Entpackt `overrides/` und danach `client-overrides/` in den Spielordner.
pub(crate) fn extract_overrides(pack: &Path, game_dir: &Path) -> Result<()> {
    extract_folders(pack, game_dir, &["overrides/", "client-overrides/"])
}

/// Entpackt die Ordner `prefixes` (in dieser Reihenfolge, spätere gewinnen)
/// in den Spielordner – nur sichere, relative Pfade.
pub(crate) fn extract_folders(pack: &Path, game_dir: &Path, prefixes: &[&str]) -> Result<()> {
    let file = std::fs::File::open(pack).map_err(|e| Error::io(pack, e))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| Error::validation(crate::msg!(
        "modpack.corrupt",
        "Das Modpack ist beschädigt."
    )))?;

    for prefix in prefixes.iter().copied() {
        for i in 0..archive.len() {
            let mut entry = archive.by_index(i).map_err(|_| Error::validation(crate::msg!(
                "modpack.corrupt",
                "Das Modpack ist beschädigt."
            )))?;
            // `enclosed_name` verhindert Zip-Slip.
            let Some(name) = entry.enclosed_name() else { continue };
            let name = name.to_string_lossy().replace('\\', "/");
            let Some(rel) = name.strip_prefix(prefix).and_then(safe_relative) else { continue };
            if entry.is_dir() {
                continue;
            }
            let dest = game_dir.join(rel);
            if let Some(parent) = dest.parent() {
                std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
            }
            let mut out = std::fs::File::create(&dest).map_err(|e| Error::io(&dest, e))?;
            std::io::copy(&mut entry, &mut out).map_err(|e| Error::io(&dest, e))?;
        }
    }
    Ok(())
}

pub(crate) fn download_tasks(index: &PackIndex, game_dir: &Path, strict_size: bool) -> Result<Vec<Task>> {
    let mut tasks = Vec::new();
    for file in &index.files {
        if file.env.as_ref().and_then(|e| e.client.as_deref()) == Some("unsupported") {
            continue;
        }
        let rel = safe_relative(&file.path)
            .ok_or_else(|| Error::validation(crate::msg!(
                "modpack.invalidFilePath",
                "Das Modpack enthält einen ungültigen Dateipfad."
            )))?;
        let url = file
            .downloads
            .iter()
            .find(|u| download::allowed_pack_url(u))
            .ok_or_else(|| Error::validation(crate::msg!(
                "modpack.disallowedSource",
                "Das Modpack verweist auf eine nicht erlaubte Download-Quelle."
            )))?;
        if !hex_len(&file.hashes.sha1, 40) {
            return Err(Error::validation(crate::msg!(
                "modpack.invalidChecksum",
                "Das Modpack enthält eine ungültige Prüfsumme."
            )));
        }
        let sha512 = match file.hashes.sha512.as_deref() {
            None => None,
            Some("") => None,
            Some(s) if hex_len(s, 128) => Some(s.to_owned()),
            Some(_) => {
                return Err(Error::validation(crate::msg!(
                    "modpack.invalidChecksum",
                    "Das Modpack enthält eine ungültige Prüfsumme."
                )));
            }
        };
        tasks.push(Task {
            url: url.clone(),
            path: game_dir.join(rel),
            sha1: Some(file.hashes.sha1.clone()),
            size: file.file_size,
            sha512,
            strict_size,
            pack: true,
        });
    }
    Ok(tasks)
}

/// Was der Installations- bzw. Import-Dialog über ein Pack zeigt, bevor es
/// eine Instanz wird – vor allem die Frage „mit oder ohne TRS Client“.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PackPreview {
    pub name: String,
    pub game_version: String,
    pub loader: Loader,
    /// Mods im Pack (Dateien in `mods/`).
    pub mod_count: u32,
    /// Gelesene Version (Modrinth-Versions- bzw. CurseForge-Datei-ID) – die
    /// Installation nimmt genau diese.
    pub version_id: Option<String>,
    pub trs_client: TrsOffer,
}

/// Was über die Mods eines `.mrpack` bekannt ist: Dateiname + Modrinth-Projekt
/// aus dem Index, dazu Jars, die das Pack direkt in `overrides/mods/` mitbringt.
pub(crate) fn pack_hints(index: &PackIndex, bundled: Vec<String>) -> Vec<ModHint> {
    index
        .files
        .iter()
        .filter(|f| f.path.starts_with("mods/"))
        .filter(|f| f.env.as_ref().and_then(|e| e.client.as_deref()) != Some("unsupported"))
        .map(|f| ModHint::from_pack_file(&f.path, &f.downloads))
        .chain(bundled.into_iter().map(ModHint::file))
        .collect()
}

/// Jar-Namen, die ein Pack direkt in seinen Zusatzordnern mitbringt
/// (`<ordner>/mods/*.jar`) – nur Namen, nichts wird entpackt.
pub(crate) fn override_mod_names(pack: &Path, prefixes: &[&str]) -> Vec<String> {
    const MAX_NAMES: usize = 2000;
    let Ok(file) = std::fs::File::open(pack) else { return Vec::new() };
    let Ok(archive) = zip::ZipArchive::new(file) else { return Vec::new() };
    archive
        .file_names()
        .filter_map(|name| {
            let name = name.replace('\\', "/");
            prefixes.iter().find_map(|p| Some(name.strip_prefix(p)?.strip_prefix("mods/")?.to_owned()))
        })
        .filter(|n| {
            let lower = n.to_ascii_lowercase();
            !n.contains('/') && (lower.ends_with(".jar") || lower.ends_with(".jar.disabled"))
        })
        .take(MAX_NAMES)
        .collect()
}

/// Index und mitgebrachte Mod-Jars einer `.mrpack` (blockierend).
pub(crate) fn read_index_and_mods(pack: &Path) -> Result<(PackIndex, Vec<String>)> {
    Ok((read_index(pack)?, override_mod_names(pack, &["overrides/", "client-overrides/"])))
}

/// Zwischenspeicher für Packs zwischen Vorschau und Installation.
pub(crate) fn pack_cache_dir(paths: &crate::paths::Paths) -> PathBuf {
    paths.meta_dir().join("packs")
}

/// Übrig gebliebene Packs (Vorschau ohne Installation) nach einem Tag löschen.
pub(crate) async fn prune_pack_cache(dir: &Path) {
    const MAX_AGE: std::time::Duration = std::time::Duration::from_secs(24 * 60 * 60);
    let Ok(mut entries) = tokio::fs::read_dir(dir).await else { return };
    while let Ok(Some(entry)) = entries.next_entry().await {
        let old = entry
            .metadata()
            .await
            .ok()
            .and_then(|m| m.modified().ok())
            .and_then(|t| t.elapsed().ok())
            .is_some_and(|age| age > MAX_AGE);
        if old {
            let _ = tokio::fs::remove_file(entry.path()).await;
        }
    }
}

impl Launcher {
    /// Die Version eines Modrinth-Modpacks samt Download-Auftrag für die
    /// `.mrpack` (Ziel im Pack-Zwischenspeicher, damit Vorschau und
    /// Installation dieselbe Datei nehmen).
    pub(crate) async fn modrinth_pack_task(&self, project_id: &str, version_id: Option<&str>) -> Result<(modrinth::Version, Task)> {
        if !modrinth::is_safe_project_id(project_id) {
            return Err(Error::validation(crate::msg!("modrinth.invalidProjectId", "Ungültige Projekt-ID")));
        }
        let http = self.http();
        let version = match version_id {
            Some(id) => modrinth::version_by_id(http, id).await?,
            None => {
                let versions: Vec<modrinth::Version> = http
                    .get(format!("{}/project/{project_id}/version", modrinth::API))
                    .send()
                    .await?
                    .error_for_status()?
                    .json()
                    .await?;
                versions.into_iter().next().ok_or_else(|| Error::validation(crate::msg!(
                    "modpack.noVersion",
                    "Dieses Modpack hat keine Version."
                )))?
            }
        };
        let file = version
            .files
            .iter()
            .find(|f| f.primary && f.filename.ends_with(".mrpack"))
            .or_else(|| version.files.iter().find(|f| f.filename.ends_with(".mrpack")))
            .ok_or_else(|| Error::validation(crate::msg!(
                "modpack.versionHasNoPack",
                "Diese Version enthält kein Modpack."
            )))?;
        if !file.url.starts_with(CDN_PREFIX) {
            return Err(Error::download(&file.url, "Download liegt nicht auf Modrinths CDN"));
        }
        let sha1 = file.hashes.sha1.to_ascii_lowercase();
        if sha1.len() != 40 || !sha1.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(Error::validation(crate::msg!(
                "modpack.invalidChecksum",
                "Das Modpack enthält eine ungültige Prüfsumme."
            )));
        }
        let path = pack_cache_dir(self.paths()).join(format!("mr-{sha1}.mrpack"));
        let task = Task { url: file.url.clone(), path, sha1: Some(sha1), size: Some(file.size), sha512: None, strict_size: false, pack: false };
        Ok((version, task))
    }

    /// Lädt das Pack (für die Installation danach zwischengespeichert) und
    /// sagt, was drin ist und ob der TRS Client dazu passt.
    pub async fn preview_modpack(&self, project_id: &str, version_id: Option<&str>) -> Result<PackPreview> {
        let (version, task) = self.modrinth_pack_task(project_id, version_id).await?;
        prune_pack_cache(&pack_cache_dir(self.paths())).await;
        download::fetch_all(self.http(), vec![task.clone()], 1, &|_| {}).await?;
        let mut preview = self.preview_pack_file(&task.path).await?;
        preview.version_id = Some(version.id);
        Ok(preview)
    }

    /// Vorschau einer Pack-Datei auf der Platte (`.mrpack` oder CurseForge-Zip).
    pub async fn preview_pack_file(&self, pack: &Path) -> Result<PackPreview> {
        let path = pack.to_owned();
        let curseforge = tokio::task::spawn_blocking(move || crate::curseforge::is_curseforge_pack(&path))
            .await
            .map_err(|e| Error::Internal(e.to_string()))?;
        if curseforge {
            return self.preview_curseforge_pack(pack).await;
        }
        let path = pack.to_owned();
        let (index, bundled) = tokio::task::spawn_blocking(move || read_index_and_mods(&path))
            .await
            .map_err(|e| Error::Internal(e.to_string()))??;
        let loader = index.loader()?;
        let game_version = index.game_version()?.to_owned();
        let hints = pack_hints(&index, bundled);
        let trs_client = self.trs_client_offer(&loader, &game_version, &hints).await;
        Ok(PackPreview {
            name: index.name.chars().filter(|c| !c.is_control()).take(64).collect(),
            game_version,
            loader,
            mod_count: hints.len() as u32,
            version_id: None,
            trs_client,
        })
    }

    /// Lädt ein Modpack von Modrinth und legt daraus eine neue Instanz an.
    /// `trs_client`: Wahl aus dem Dialog (`None` = Einstellung bzw. Vorauswahl).
    pub async fn install_modpack(
        &self,
        project_id: &str,
        version_id: Option<&str>,
        trs_client: Option<bool>,
        on_progress: &PackProgressFn,
    ) -> Result<Instance> {
        on_progress(PackProgress::new(PackPhase::Pack, 0.0));
        let (version, pack_task) = self.modrinth_pack_task(project_id, version_id).await?;
        if version.project_id != project_id {
            return Err(Error::validation(crate::msg!("modpack.noVersion", "Dieses Modpack hat keine Version.")));
        }
        let result = self.install_pack_file(&pack_task, trs_client, on_progress).await;
        let _ = tokio::fs::remove_file(&pack_task.path).await;
        let instance = result?;

        // Das Pack-Icon wird zum Instanz-Bild (nur von Modrinths CDN, siehe `icon`).
        let icon_url = modrinth::project_cards(self.http(), &[project_id.to_owned()])
            .await
            .ok()
            .and_then(|cards| cards.into_iter().next())
            .and_then(|card| card.icon_url);
        if let Some(url) = icon_url {
            match self.set_instance_icon_from_url(&instance.id, &url).await {
                Ok(with_icon) => return Ok(with_icon),
                Err(e) => tracing::debug!("Modpack-Icon übersprungen: {e}"),
            }
        }
        Ok(instance)
    }

    /// Legt aus einer Modpack-Datei auf der Platte eine Instanz an – `.mrpack`
    /// (etwa ein eigener Export) oder ein CurseForge-Zip mit `manifest.json`.
    pub async fn import_modpack_file(
        &self,
        pack: &Path,
        trs_client: Option<bool>,
        on_progress: &PackProgressFn,
    ) -> Result<Instance> {
        let meta = tokio::fs::metadata(pack).await.map_err(|e| Error::io(pack, e))?;
        if !meta.is_file() {
            return Err(Error::validation(crate::msg!("modpack.notPackFile", "Das ist keine Modpack-Datei.")));
        }
        on_progress(PackProgress::new(PackPhase::Pack, 100.0));
        let path = pack.to_owned();
        let curseforge = tokio::task::spawn_blocking(move || crate::curseforge::is_curseforge_pack(&path))
            .await
            .map_err(|e| Error::Internal(e.to_string()))?;
        if curseforge {
            // Die Dateien des Packs löst nur die CurseForge-API auf.
            let cf = self.curseforge()?;
            return Ok(self.install_curseforge_pack(cf, pack, trs_client, on_progress).await?.instance);
        }
        self.install_local_pack(pack, trs_client, false, on_progress).await
    }

    async fn install_pack_file(
        &self,
        pack_task: &Task,
        trs_client: Option<bool>,
        on_progress: &PackProgressFn,
    ) -> Result<Instance> {
        download::fetch_all(self.http(), vec![pack_task.clone()], 1, &|p| {
            on_progress(PackProgress::new(PackPhase::Pack, p.percent()));
        })
        .await?;
        self.install_local_pack(&pack_task.path, trs_client, false, on_progress).await
    }

    pub(crate) async fn install_local_pack(
        &self,
        pack: &Path,
        trs_client: Option<bool>,
        strict_size: bool,
        on_progress: &PackProgressFn,
    ) -> Result<Instance> {
        let pack_path = pack.to_owned();
        let (index, bundled) = {
            let path = pack_path.clone();
            tokio::task::spawn_blocking(move || read_index_and_mods(&path))
                .await
                .map_err(|e| Error::Internal(e.to_string()))??
        };

        // Abgebrochen, während das Pack lud? Dann gar nicht erst eine Instanz anlegen.
        crate::task::checkpoint().await?;
        let name: String = index.name.chars().filter(|c| !c.is_control()).take(64).collect();
        let new = NewInstance { name: name.clone(), game_version: index.game_version()?.to_owned(), loader: index.loader()? };
        // Mit oder ohne TRS Client – gewählt im Dialog, sonst Einstellung bzw. Vorauswahl.
        let offer = self.trs_client_offer(&new.loader, &new.game_version, &pack_hints(&index, bundled)).await;
        let instance = self
            .create_instance_with(
                new,
                crate::history::HistoryEntry::new(crate::history::HistoryKind::Created).subject(&name).detail("modpack"),
                trs_choice::decide(&offer, trs_client),
            )
            .await?;
        on_progress(PackProgress::created(&instance.id));

        let game_dir = self.paths().instance_game_dir(&instance.id);
        let files = async {
            let tasks = pack_download_order(download_tasks(&index, &game_dir, strict_size)?);
            let concurrency = pack_concurrency(self.settings().await.concurrent_downloads);
            download::fetch_all(self.http(), tasks, concurrency, &|p| {
                on_progress(PackProgress::files(&p));
            })
            .await?;

            on_progress(PackProgress::new(PackPhase::Overrides, 0.0));
            let (pack, dir) = (pack_path.clone(), game_dir.clone());
            tokio::task::spawn_blocking(move || extract_overrides(&pack, &dir))
                .await
                .map_err(|e| Error::Internal(e.to_string()))??;
            on_progress(PackProgress::new(PackPhase::Overrides, 100.0));
            fsutil::ensure_dir(&game_dir).await?;
            // Während des Entpackens abgebrochen: trotzdem aufräumen.
            if crate::task::is_cancelled() {
                return Err(Error::Cancelled);
            }
            Ok::<_, Error>(())
        }
        .await;

        // Halb installierte (oder abgebrochene) Packs nicht herumliegen lassen.
        if let Err(e) = files {
            if let Err(cleanup) = self.instances().delete(&instance.id).await {
                tracing::warn!("Halb installiertes Modpack '{}' konnte nicht entfernt werden: {cleanup}", instance.id);
            }
            return Err(e);
        }
        // Herkunft merken: alles, was jetzt in der Instanz liegt, kam aus dem Pack.
        if let Err(e) = crate::content_groups::record_pack_contents(self.paths(), &instance.id, &[]).await {
            tracing::warn!("Modpack-Herkunft für '{}' nicht gespeichert: {e}", instance.id);
        }
        self.trs_achievement_event(crate::trs_api::achievements::ReportKind::ModpackInstalled, None).await;
        Ok(instance)
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;

    const INDEX: &str = r#"{
        "formatVersion": 1, "game": "minecraft", "versionId": "1.0", "name": "Testpack",
        "dependencies": { "minecraft": "1.21.1", "fabric-loader": "0.16.10" },
        "files": [
          { "path": "mods/a.jar", "hashes": { "sha1": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "sha512": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" },
            "env": { "client": "required", "server": "required" },
            "downloads": ["https://evil.example/a.jar", "https://cdn.modrinth.com/data/x/versions/y/a.jar"], "fileSize": 10 },
          { "path": "mods/server-only.jar", "hashes": { "sha1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" },
            "env": { "client": "unsupported", "server": "required" },
            "downloads": ["https://cdn.modrinth.com/data/x/versions/y/s.jar"], "fileSize": 10 }
        ] }"#;

    fn write_pack(path: &Path, index: &str) {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        let opts = zip::write::SimpleFileOptions::default();
        zip.start_file("modrinth.index.json", opts).unwrap();
        zip.write_all(index.as_bytes()).unwrap();
        zip.start_file("overrides/config/a.toml", opts).unwrap();
        zip.write_all(b"base").unwrap();
        zip.start_file("overrides/options.txt", opts).unwrap();
        zip.write_all(b"base").unwrap();
        zip.start_file("client-overrides/options.txt", opts).unwrap();
        zip.write_all(b"client").unwrap();
        zip.start_file("server-overrides/server.properties", opts).unwrap();
        zip.write_all(b"nope").unwrap();
        zip.finish().unwrap();
    }

    #[test]
    fn reads_index_and_builds_tasks() {
        let dir = tempfile::tempdir().unwrap();
        let pack = dir.path().join("p.mrpack");
        write_pack(&pack, INDEX);

        let index = read_index(&pack).unwrap();
        assert_eq!(index.game_version().unwrap(), "1.21.1");
        assert_eq!(index.loader().unwrap(), Loader { kind: LoaderKind::Fabric, version: Some("0.16.10".into()) });

        let tasks = download_tasks(&index, dir.path(), false).unwrap();
        assert_eq!(tasks.len(), 1, "server-only Dateien werden übersprungen");
        assert!(tasks[0].url.starts_with("https://cdn.modrinth.com/"), "nicht erlaubter Host wird ignoriert");
        assert!(tasks[0].pack && !tasks[0].strict_size);
        assert_eq!(tasks[0].sha512.as_deref().map(str::len), Some(128));
        assert!(tasks[0].path.ends_with("mods/a.jar") || tasks[0].path.ends_with("mods\\a.jar"));
    }

    #[test]
    fn overrides_are_layered_and_server_files_skipped() {
        let dir = tempfile::tempdir().unwrap();
        let pack = dir.path().join("p.mrpack");
        write_pack(&pack, INDEX);
        let game = dir.path().join("game");
        extract_overrides(&pack, &game).unwrap();

        assert_eq!(std::fs::read_to_string(game.join("config/a.toml")).unwrap(), "base");
        assert_eq!(std::fs::read_to_string(game.join("options.txt")).unwrap(), "client");
        assert!(!game.join("server.properties").exists());
    }

    #[test]
    fn rejects_bad_paths_and_hosts() {
        for bad in ["../x.jar", "/abs.jar", "mods/../../x", "c:/x", "mods\\x.jar", "mods//x", "mods/x."] {
            assert!(safe_relative(bad).is_none(), "{bad:?}");
        }
        assert!(safe_relative("config/sub/a.toml").is_some());

        let dir = tempfile::tempdir().unwrap();
        let evil = INDEX.replace("mods/a.jar", "../../evil.jar");
        let index: PackIndex = serde_json::from_str(&evil).unwrap();
        assert!(download_tasks(&index, dir.path(), false).is_err());

        let only_evil = INDEX.replace("https://cdn.modrinth.com/data/x/versions/y/a.jar", "https://evil.example/b.jar");
        let index: PackIndex = serde_json::from_str(&only_evil).unwrap();
        assert!(download_tasks(&index, dir.path(), false).is_err());

        let lookalike = INDEX.replace("https://cdn.modrinth.com/", "https://cdn.modrinth.com.evil/");
        let index: PackIndex = serde_json::from_str(&lookalike).unwrap();
        assert!(download_tasks(&index, dir.path(), false).is_err());

        let forge = INDEX.replace(
            "https://cdn.modrinth.com/data/x/versions/y/a.jar",
            "https://edge.forgecdn.net/files/1/2/a.jar",
        );
        let index: PackIndex = serde_json::from_str(&forge).unwrap();
        let tasks = download_tasks(&index, dir.path(), true).unwrap();
        assert!(tasks[0].url.starts_with("https://edge.forgecdn.net/"));
        assert!(tasks[0].strict_size);

        let bad_sha = INDEX.replace(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "x",
        );
        let index: PackIndex = serde_json::from_str(&bad_sha).unwrap();
        assert!(download_tasks(&index, dir.path(), false).is_err());
    }

    #[test]
    fn rejects_foreign_formats() {
        let dir = tempfile::tempdir().unwrap();
        let pack = dir.path().join("p.mrpack");
        write_pack(&pack, &INDEX.replace("\"formatVersion\": 1", "\"formatVersion\": 2"));
        assert!(read_index(&pack).is_err());
    }
}
