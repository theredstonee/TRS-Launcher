//! CurseForge-Modpacks (Zip mit `manifest.json` + `overrides/`) und die
//! Liste der Dateien, die der Nutzer selbst laden muss, weil ihr Autor
//! Downloads über andere Apps nicht erlaubt.

use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::path::{Path, PathBuf};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use super::install::{cf_key, cf_source, meta_of, register_file};
use super::{
    CLASS_MODPACKS, CurseForge, RawFile, RawMod, content_kind_of_class, invalid_file_id, invalid_project_id,
    is_allowed_download_url, mods_by_id, newest_in_channel, parse_id,
};
use crate::content::{self, ContentKind, ProjectMeta};
use crate::download::{self, Task};
use crate::history::{self, HistoryEntry, HistoryKind};
use crate::instance::{Instance, Loader, LoaderKind, NewInstance, UpdateChannel, validate_id};
use crate::modpack::{PackPhase, PackProgress, PackProgressFn};
use crate::modrinth::{self, clip};
use crate::paths::Paths;
use crate::{Error, Launcher, Result, fsutil};

const BLOCKED_FILE: &str = "curseforge-blocked.json";
const MAX_BLOCKED: usize = 500;
const MAX_MANIFEST_BYTES: u64 = 16 << 20;
const MAX_PACK_FILES: usize = 5000;
/// So viele Einträge im Download-Ordner werden höchstens angesehen.
const MAX_DOWNLOAD_ENTRIES: usize = 20_000;

/// Serialisiert Änderungen an den Listen (Installation und Abgleich parallel).
static BLOCKED_LOCK: Mutex<()> = Mutex::const_new(());

/// Eine Datei, die der Nutzer selbst von CurseForge laden muss.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BlockedFile {
    pub project_id: String,
    pub file_id: String,
    pub title: String,
    pub file_name: String,
    pub kind: ContentKind,
    /// SHA1 laut CurseForge – nur Dateien mit passender Prüfsumme werden übernommen.
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: u64,
    /// Seite der Datei auf curseforge.com (dort lädt man sie).
    pub url: String,
    #[serde(default)]
    pub icon_url: Option<String>,
    /// Wird beim Übernehmen ersetzt (Update einer installierten Datei).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub replace: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version_number: Option<String>,
}

impl BlockedFile {
    pub(super) fn new(m: &RawMod, file: &RawFile, kind: ContentKind, replace: Option<&str>) -> Self {
        Self {
            project_id: m.id.to_string(),
            file_id: file.id.to_string(),
            title: if m.name.is_empty() { clip(file.display_name.clone(), 100) } else { m.title() },
            file_name: file.file_name.clone(),
            kind,
            sha1: file.sha1(),
            size: file.file_length,
            url: m.file_page_url(file.id),
            icon_url: m.icon_url(),
            replace: replace.map(str::to_owned),
            version_number: Some(file.version_label()).filter(|v| !v.is_empty()),
        }
    }

    /// Die Liste liegt im Instanz-Ordner – beim Lesen alles erneut prüfen.
    fn is_valid(&self) -> bool {
        parse_id(&self.project_id).is_some()
            && parse_id(&self.file_id).is_some()
            && content::validate_file_name(self.kind, &self.file_name).is_ok()
            && self.url.starts_with("https://www.curseforge.com/")
            && modrinth::is_safe_external_url(&self.url)
            && self.sha1.as_ref().is_none_or(|h| h.len() == 40 && h.bytes().all(|b| b.is_ascii_hexdigit()))
            && self.icon_url.as_ref().is_none_or(|u| crate::icon::is_allowed_icon_url(u))
            && self.replace.as_ref().is_none_or(|r| content::validate_file_name(self.kind, r).is_ok())
    }

    /// Als Datei-Eintrag, wie ihn [`register_file`] erwartet.
    fn as_file(&self) -> RawFile {
        RawFile {
            id: parse_id(&self.file_id).unwrap_or_default(),
            mod_id: parse_id(&self.project_id).unwrap_or_default(),
            display_name: self.version_number.clone().unwrap_or_default(),
            file_name: self.file_name.clone(),
            release_type: 1,
            hashes: Vec::new(),
            file_date: None,
            file_length: self.size,
            download_url: None,
            game_versions: Vec::new(),
            dependencies: Vec::new(),
            is_server_pack: None,
            is_available: None,
        }
    }
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct BlockedList {
    #[serde(default)]
    files: Vec<BlockedFile>,
}

fn blocked_path(paths: &Paths, instance_id: &str) -> PathBuf {
    paths.instance_dir(instance_id).join(BLOCKED_FILE)
}

async fn read_list(paths: &Paths, instance_id: &str) -> Vec<BlockedFile> {
    let list: BlockedList = fsutil::read_json(&blocked_path(paths, instance_id)).await.ok().flatten().unwrap_or_default();
    list.files.into_iter().filter(BlockedFile::is_valid).take(MAX_BLOCKED).collect()
}

async fn write_list(paths: &Paths, instance_id: &str, files: Vec<BlockedFile>) -> Result<()> {
    let path = blocked_path(paths, instance_id);
    if files.is_empty() {
        match tokio::fs::remove_file(&path).await {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(Error::io(&path, e)),
        }
    } else {
        fsutil::write_json(&path, &BlockedList { files }).await
    }
}

/// Merkt Dateien zum Selbst-Laden vor (neuere Datei desselben Projekts ersetzt die ältere).
pub(crate) async fn remember_blocked(paths: &Paths, instance_id: &str, files: &[BlockedFile]) -> Result<()> {
    if files.is_empty() {
        return Ok(());
    }
    validate_id(instance_id)?;
    let _guard = BLOCKED_LOCK.lock().await;
    let mut list = read_list(paths, instance_id).await;
    for file in files.iter().filter(|f| f.is_valid()) {
        list.retain(|f| f.project_id != file.project_id);
        list.push(file.clone());
    }
    list.truncate(MAX_BLOCKED);
    write_list(paths, instance_id, list).await
}

/// Dateien, die der Nutzer für diese Instanz noch selbst laden muss.
pub async fn blocked_files(paths: &Paths, instance_id: &str) -> Result<Vec<BlockedFile>> {
    validate_id(instance_id)?;
    Ok(read_list(paths, instance_id).await)
}

/// Einträge verwerfen (`None` = alle) – der Nutzer verzichtet auf die Datei.
pub async fn dismiss_blocked(paths: &Paths, instance_id: &str, file_id: Option<&str>) -> Result<Vec<BlockedFile>> {
    validate_id(instance_id)?;
    if let Some(id) = file_id
        && parse_id(id).is_none()
    {
        return Err(invalid_file_id());
    }
    let _guard = BLOCKED_LOCK.lock().await;
    let mut list = read_list(paths, instance_id).await;
    match file_id {
        Some(id) => list.retain(|f| f.file_id != id),
        None => list.clear(),
    }
    write_list(paths, instance_id, list.clone()).await?;
    Ok(list)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdoptResult {
    /// Titel der gerade übernommenen Dateien.
    pub adopted: Vec<String>,
    /// Was noch fehlt.
    pub pending: Vec<BlockedFile>,
    /// Der beobachtete Download-Ordner (zur Anzeige).
    pub watch_folder: Option<String>,
}

/// Der Download-Ordner des Nutzers.
pub fn downloads_dir() -> Option<PathBuf> {
    known_downloads_dir()
        .or_else(|| std::env::var_os("USERPROFILE").map(|home| PathBuf::from(home).join("Downloads")))
        .filter(|p| p.is_dir())
}

#[cfg(windows)]
fn known_downloads_dir() -> Option<PathBuf> {
    use windows::Win32::System::Com::CoTaskMemFree;
    use windows::Win32::UI::Shell::{FOLDERID_Downloads, KF_FLAG_DEFAULT, SHGetKnownFolderPath};
    // SAFETY: Der zurückgegebene Puffer gehört uns und wird genau einmal freigegeben.
    unsafe {
        let raw = SHGetKnownFolderPath(&FOLDERID_Downloads, KF_FLAG_DEFAULT, None).ok()?;
        let path = raw.to_string().ok();
        CoTaskMemFree(Some(raw.0 as *const _));
        path.map(PathBuf::from)
    }
}

/// Linux: `XDG_DOWNLOAD_DIR` aus `user-dirs.dirs`, sonst `~/Downloads`.
#[cfg(not(windows))]
fn known_downloads_dir() -> Option<PathBuf> {
    crate::platform::downloads_dir()
}

/// Heißt die Datei im Download-Ordner so wie erwartet? Auch „name (1).jar“,
/// wie Browser doppelte Downloads nennen.
pub(crate) fn name_matches(candidate: &str, expected: &str) -> bool {
    if candidate.eq_ignore_ascii_case(expected) {
        return true;
    }
    let (stem, ext) = match expected.rfind('.') {
        Some(dot) => (&expected[..dot], &expected[dot..]),
        None => (expected, ""),
    };
    let lower = candidate.to_ascii_lowercase();
    let Some(rest) = lower.strip_prefix(&stem.to_ascii_lowercase()) else { return false };
    let Some(middle) = rest.strip_suffix(&ext.to_ascii_lowercase()) else { return false };
    let middle = middle.trim_start();
    middle.len() >= 3
        && middle.starts_with('(')
        && middle.ends_with(')')
        && middle[1..middle.len() - 1].bytes().all(|b| b.is_ascii_digit())
}

/// Stimmt die Datei (Größe + SHA1)? Ohne bekannte Prüfsumme nie.
async fn content_matches(path: &Path, file: &BlockedFile) -> bool {
    let Some(expected) = &file.sha1 else { return false };
    let Ok(meta) = tokio::fs::metadata(path).await else { return false };
    if !meta.is_file() || (file.size > 0 && meta.len() != file.size) {
        return false;
    }
    download::sha1_of_file(path).await.is_ok_and(|h| h.eq_ignore_ascii_case(expected))
}

fn download_entries(dir: &Path) -> Vec<(String, PathBuf, u64)> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    entries
        .flatten()
        .take(MAX_DOWNLOAD_ENTRIES)
        .filter_map(|e| {
            let meta = e.metadata().ok()?;
            meta.is_file().then(|| (e.file_name().to_string_lossy().into_owned(), e.path(), meta.len()))
        })
        .collect()
}

/// Übernimmt Dateien, die der Nutzer geladen hat: aus dem Download-Ordner
/// (Name + SHA1 müssen passen) oder direkt im Zielordner abgelegt.
pub async fn adopt_downloads(paths: &Paths, instance_id: &str) -> Result<AdoptResult> {
    adopt_from(paths, instance_id, downloads_dir()).await
}

/// Wie [`adopt_downloads`] mit frei wählbarem Ordner (Tests).
pub(crate) async fn adopt_from(paths: &Paths, instance_id: &str, watch: Option<PathBuf>) -> Result<AdoptResult> {
    validate_id(instance_id)?;
    let _guard = BLOCKED_LOCK.lock().await;
    let list = read_list(paths, instance_id).await;
    if list.is_empty() {
        return Ok(AdoptResult { adopted: Vec::new(), pending: Vec::new(), watch_folder: watch.map(|p| p.display().to_string()) });
    }
    let entries = match &watch {
        Some(dir) => {
            let dir = dir.clone();
            tokio::task::spawn_blocking(move || download_entries(&dir)).await.unwrap_or_default()
        }
        None => Vec::new(),
    };

    let mut adopted = Vec::new();
    let mut pending = Vec::new();
    for file in list {
        let target_dir = content::content_dir(paths, instance_id, file.kind);
        let target = target_dir.join(&file.file_name);
        let in_place = content_matches(&target, &file).await;
        let mut source = None;
        if !in_place {
            for (name, path, size) in &entries {
                if name_matches(name, &file.file_name)
                    && (file.size == 0 || *size == file.size)
                    && content_matches(path, &file).await
                {
                    source = Some(path.clone());
                    break;
                }
            }
        }
        if !in_place && source.is_none() {
            pending.push(file);
            continue;
        }
        if let Some(src) = source {
            fsutil::ensure_dir(&target_dir).await?;
            let tmp = target.with_extension(format!("part-{}", uuid::Uuid::new_v4().simple()));
            tokio::fs::copy(&src, &tmp).await.map_err(|e| Error::io(&src, e))?;
            if let Err(e) = tokio::fs::rename(&tmp, &target).await {
                let _ = tokio::fs::remove_file(&tmp).await;
                return Err(Error::io(&target, e));
            }
        }
        let raw = file.as_file();
        let project = raw.mod_id;
        let previous = register_file(paths, instance_id, file.kind, project, &raw, file.replace.as_deref()).await?;
        // Titel/Icon gleich zeigen; die vollen Infos lädt der nächste Abgleich (alter Zeitstempel).
        content::modify_index(paths, instance_id, |index| {
            index.projects.entry(cf_key(project)).or_insert_with(|| ProjectMeta {
                title: file.title.clone(),
                slug: String::new(),
                author: None,
                description: None,
                icon_url: file.icon_url.clone(),
                fetched_at: DateTime::<Utc>::UNIX_EPOCH,
            });
        })
        .await?;
        let version = file.version_number.clone().unwrap_or_default();
        let entry = match previous {
            Some(prev) => {
                let e = HistoryEntry::new(HistoryKind::ModUpdated).subject(&file.title).to(&version);
                match &prev.version_number {
                    Some(from) => e.from(from),
                    None => e,
                }
            }
            None => HistoryEntry::new(HistoryKind::ModInstalled).subject(&file.title).to(&version),
        };
        history::record(paths, instance_id, entry).await;
        adopted.push(file.title);
    }
    write_list(paths, instance_id, pending.clone()).await?;
    Ok(AdoptResult { adopted, pending, watch_folder: watch.map(|p| p.display().to_string()) })
}

// --- Modpacks ----------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Manifest {
    pub minecraft: ManifestMinecraft,
    #[serde(default)]
    pub manifest_type: String,
    #[serde(default)]
    pub manifest_version: u32,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub files: Vec<ManifestFile>,
    #[serde(default)]
    pub overrides: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ManifestMinecraft {
    pub version: String,
    #[serde(default)]
    pub mod_loaders: Vec<ManifestLoader>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ManifestLoader {
    pub id: String,
    #[serde(default)]
    pub primary: bool,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ManifestFile {
    #[serde(rename = "projectID")]
    pub project_id: u64,
    #[serde(rename = "fileID")]
    pub file_id: u64,
    #[serde(default = "yes")]
    pub required: bool,
}

fn yes() -> bool {
    true
}

fn corrupt() -> Error {
    Error::validation(crate::msg!("modpack.corrupt", "Das Modpack ist beschädigt."))
}

fn manifest_invalid() -> Error {
    Error::validation(crate::msg!("curseforge.manifestInvalid", "Die manifest.json des Modpacks ist ungültig."))
}

impl Manifest {
    pub(crate) fn loader(&self) -> Result<Loader> {
        let loaders = &self.minecraft.mod_loaders;
        let Some(pick) = loaders.iter().find(|l| l.primary).or(loaders.first()) else { return Ok(Loader::vanilla()) };
        let loader = crate::import::parse_curse_loader(&pick.id, &self.minecraft.version);
        if loader.kind == LoaderKind::Vanilla {
            return Err(Error::validation(crate::msg!(
                "curseforge.unsupportedLoader",
                "Das Modpack nutzt einen Modloader, den der Launcher nicht kennt: {loader}",
                loader = clip(pick.id.clone(), 60)
            )));
        }
        loader.validate()?;
        Ok(loader)
    }

    /// Ordner mit den Zusatzdateien im Zip – nur ein schlichter Ordnername.
    pub(crate) fn overrides_prefix(&self) -> Result<String> {
        let name = self.overrides.as_deref().map(str::trim).filter(|n| !n.is_empty()).unwrap_or("overrides");
        let ok = name.len() <= 64
            && name != "."
            && name != ".."
            && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, ' ' | '_' | '-' | '.'));
        if ok { Ok(format!("{name}/")) } else { Err(manifest_invalid()) }
    }
}

fn open_archive(pack: &Path) -> Result<zip::ZipArchive<std::fs::File>> {
    let file = std::fs::File::open(pack).map_err(|e| Error::io(pack, e))?;
    zip::ZipArchive::new(file).map_err(|_| corrupt())
}

/// CurseForge-Pack (hat `manifest.json`, aber kein `modrinth.index.json`)?
pub(crate) fn is_curseforge_pack(pack: &Path) -> bool {
    let Ok(mut archive) = open_archive(pack) else { return false };
    archive.by_name("modrinth.index.json").is_err() && archive.by_name("manifest.json").is_ok()
}

pub(crate) fn read_manifest(pack: &Path) -> Result<Manifest> {
    let mut archive = open_archive(pack)?;
    let entry = archive.by_name("manifest.json").map_err(|_| {
        Error::validation(crate::msg!("curseforge.notAModpack", "Das ist kein CurseForge-Modpack."))
    })?;
    if entry.size() > MAX_MANIFEST_BYTES {
        return Err(corrupt());
    }
    let mut bytes = Vec::new();
    entry.take(MAX_MANIFEST_BYTES).read_to_end(&mut bytes).map_err(|_| corrupt())?;
    // Manche Packs speichern die Datei mit BOM.
    let text = bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(&bytes);
    let manifest: Manifest = serde_json::from_slice(text).map_err(|e| {
        tracing::debug!("manifest.json nicht lesbar: {e}");
        manifest_invalid()
    })?;
    if manifest.manifest_type != "minecraftModpack" || manifest.manifest_version != 1 {
        return Err(Error::validation(crate::msg!(
            "modpack.unsupportedFormat",
            "Dieses Modpack-Format wird nicht unterstützt."
        )));
    }
    if !crate::meta::is_safe_id(&manifest.minecraft.version) || manifest.minecraft.version.len() > 32 {
        return Err(manifest_invalid());
    }
    if manifest.files.len() > MAX_PACK_FILES {
        return Err(Error::validation(crate::msg!("modpack.tooManyFiles", "Das Modpack enthält zu viele Dateien.")));
    }
    manifest.overrides_prefix()?;
    Ok(manifest)
}

/// Eine aufgelöste Datei des Packs.
pub(crate) struct PackEntry {
    pub m: RawMod,
    pub file: RawFile,
    pub kind: ContentKind,
}

/// Wohin eine Datei gehört – nach CurseForge-Klasse, sonst nach Endung.
fn kind_for(m: &RawMod, file: &RawFile) -> Option<ContentKind> {
    match m.class_id.and_then(content_kind_of_class) {
        Some(kind) => Some(kind),
        None if file.file_name.to_ascii_lowercase().ends_with(".jar") => Some(ContentKind::Mod),
        None => None,
    }
}

/// Löst die Pflicht-Dateien des Manifests in Dateien + Projekte auf.
pub(crate) async fn resolve(cf: &CurseForge, manifest: &Manifest) -> Result<Vec<PackEntry>> {
    let wanted: Vec<&ManifestFile> = manifest.files.iter().filter(|f| f.required && f.file_id > 0).collect();
    let file_ids: Vec<u64> = wanted.iter().map(|f| f.file_id).collect();
    let files: HashMap<u64, RawFile> = cf.files(&file_ids).await?.into_iter().map(|f| (f.id, f)).collect();
    let mut mod_ids: Vec<u64> = wanted.iter().map(|f| f.project_id).collect();
    mod_ids.sort_unstable();
    mod_ids.dedup();
    let mods = mods_by_id(cf, &mod_ids).await?;

    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for entry in wanted {
        if !seen.insert(entry.file_id) {
            continue;
        }
        let Some(file) = files.get(&entry.file_id).cloned() else {
            return Err(Error::validation(crate::msg!(
                "curseforge.packFileMissing",
                "Eine Datei des Modpacks gibt es auf CurseForge nicht mehr (Projekt {project}).",
                project = entry.project_id
            )));
        };
        if file.mod_id != 0 && file.mod_id != entry.project_id {
            return Err(manifest_invalid());
        }
        let m = mods.get(&entry.project_id).cloned().unwrap_or_else(|| RawMod {
            id: entry.project_id,
            name: file.display_name.clone(),
            ..RawMod::default()
        });
        let Some(kind) = kind_for(&m, &file) else {
            tracing::warn!("Pack-Datei {} ({:?}) passt in keinen Inhaltsordner – übersprungen", file.file_name, m.class_id);
            continue;
        };
        if content::validate_file_name(kind, &file.file_name).is_err() {
            tracing::warn!("Pack-Datei mit unbrauchbarem Namen übersprungen: {:?}", file.file_name);
            continue;
        }
        out.push(PackEntry { m, file, kind });
    }
    Ok(out)
}

/// Download-Aufträge und gesperrte Dateien eines aufgelösten Packs.
pub(crate) fn plan_downloads(entries: &[PackEntry], game_dir: &Path) -> Result<(Vec<Task>, Vec<BlockedFile>)> {
    let mut tasks = Vec::new();
    let mut blocked = Vec::new();
    for e in entries {
        match e.m.download_url(&e.file) {
            None => blocked.push(BlockedFile::new(&e.m, &e.file, e.kind, None)),
            Some(url) if is_allowed_download_url(url) => tasks.push(Task {
                url: url.to_owned(),
                path: game_dir.join(e.kind.dir_name()).join(&e.file.file_name),
                sha1: e.file.sha1(),
                size: Some(e.file.file_length).filter(|s| *s > 0),
            }),
            Some(_) => {
                return Err(Error::validation(crate::msg!(
                    "modpack.disallowedSource",
                    "Das Modpack verweist auf eine nicht erlaubte Download-Quelle."
                )));
            }
        }
    }
    Ok((tasks, blocked))
}

/// Ergebnis einer Modpack-Installation.
#[derive(Debug, Clone)]
pub struct PackOutcome {
    pub instance: Instance,
    /// Vom Autor gesperrte Dateien – stehen auch in der Liste der Instanz.
    pub blocked: Vec<BlockedFile>,
}

fn pack_blocked(m: &RawMod, file_id: u64) -> Error {
    let url = m.file_page_url(file_id);
    Error::validation(crate::msg!(
        "curseforge.packBlocked",
        "Der Autor von „{name}“ erlaubt keine Downloads über andere Apps – bitte das Modpack auf CurseForge herunterladen und hier importieren.",
        name = m.title(),
        url = url
    ))
}

impl Launcher {
    /// Lädt ein CurseForge-Modpack und legt daraus eine neue Instanz an.
    pub async fn install_curseforge_modpack(
        &self,
        project_id: &str,
        file_id: Option<&str>,
        on_progress: &PackProgressFn,
    ) -> Result<PackOutcome> {
        let cf = self.curseforge()?;
        let id = parse_id(project_id).ok_or_else(invalid_project_id)?;
        on_progress(PackProgress { phase: PackPhase::Pack, percent: 0.0 });
        let m = cf.mod_info(id).await?;
        if m.class_id != Some(CLASS_MODPACKS) {
            return Err(Error::validation(crate::msg!("curseforge.notAModpack", "Das ist kein CurseForge-Modpack.")));
        }
        let file = match file_id {
            Some(f) => cf.file(id, parse_id(f).ok_or_else(invalid_file_id)?).await?,
            None => newest_in_channel(cf.project_files(id, None, 50).await?, UpdateChannel::Release).ok_or_else(|| {
                Error::validation(crate::msg!("modpack.noVersion", "Dieses Modpack hat keine Version."))
            })?,
        };
        let Some(url) = m.download_url(&file).map(str::to_owned) else { return Err(pack_blocked(&m, file.id)) };
        if !is_allowed_download_url(&url) {
            return Err(Error::download(&url, "Download liegt nicht auf CurseForges CDN"));
        }

        let pack_path = self.paths().meta_dir().join(format!("cfpack-{}.zip", uuid::Uuid::new_v4().simple()));
        let task = Task { url, path: pack_path.clone(), sha1: file.sha1(), size: Some(file.file_length).filter(|s| *s > 0) };
        let result = async {
            download::fetch_all(cf.download_client(), vec![task], 1, &|p| {
                on_progress(PackProgress { phase: PackPhase::Pack, percent: p.percent() });
            })
            .await?;
            self.install_curseforge_pack(cf, &pack_path, on_progress).await
        }
        .await;
        let _ = tokio::fs::remove_file(&pack_path).await;
        let mut outcome = result?;

        // Das Pack-Logo wird zum Instanz-Bild (nur von CurseForges Bild-CDN).
        if let Some(icon) = m.icon_url() {
            match self.set_instance_icon_from_url(&outcome.instance.id, &icon).await {
                Ok(with_icon) => outcome.instance = with_icon,
                Err(e) => tracing::debug!("Modpack-Icon übersprungen: {e}"),
            }
        }
        Ok(outcome)
    }

    /// Legt aus einem CurseForge-Pack auf der Platte eine Instanz an.
    pub(crate) async fn install_curseforge_pack(
        &self,
        cf: &CurseForge,
        pack: &Path,
        on_progress: &PackProgressFn,
    ) -> Result<PackOutcome> {
        let manifest = {
            let path = pack.to_owned();
            tokio::task::spawn_blocking(move || read_manifest(&path)).await.map_err(|e| Error::Internal(e.to_string()))??
        };
        let loader = manifest.loader()?;
        crate::task::checkpoint().await?;

        let name: String = manifest.name.chars().filter(|c| !c.is_control()).take(64).collect::<String>().trim().to_owned();
        let name = if name.is_empty() { "CurseForge".to_owned() } else { name };
        let instance = self
            .create_instance_as(
                NewInstance { name: name.clone(), game_version: manifest.minecraft.version.clone(), loader },
                HistoryEntry::new(HistoryKind::Created).subject(&name).detail("modpack"),
            )
            .await?;

        let game_dir = self.paths().instance_game_dir(&instance.id);
        let work = async {
            on_progress(PackProgress { phase: PackPhase::Files, percent: 0.0 });
            let entries = resolve(cf, &manifest).await?;
            let (tasks, blocked) = plan_downloads(&entries, &game_dir)?;
            let concurrency = usize::from(self.settings().await.concurrent_downloads);
            download::fetch_all(cf.download_client(), tasks, concurrency, &|p| {
                on_progress(PackProgress { phase: PackPhase::Files, percent: p.percent() });
            })
            .await?;

            on_progress(PackProgress { phase: PackPhase::Overrides, percent: 0.0 });
            let prefix = manifest.overrides_prefix()?;
            let (pack, dir) = (pack.to_owned(), game_dir.clone());
            tokio::task::spawn_blocking(move || crate::modpack::extract_folders(&pack, &dir, &[prefix.as_str()]))
                .await
                .map_err(|e| Error::Internal(e.to_string()))??;
            fsutil::ensure_dir(&game_dir).await?;

            // Herkunft merken – Grundlage für Updates und Projekt-Infos.
            let downloaded: Vec<&PackEntry> = entries.iter().filter(|e| e.m.download_url(&e.file).is_some()).collect();
            content::modify_index(self.paths(), &instance.id, |index| {
                for e in &downloaded {
                    index.files.insert(content::index_key(e.kind, &e.file.file_name), cf_source(e.m.id, &e.file));
                    if !e.m.name.is_empty() {
                        index.projects.insert(cf_key(e.m.id), meta_of(&e.m));
                    }
                }
            })
            .await?;
            remember_blocked(self.paths(), &instance.id, &blocked).await?;
            on_progress(PackProgress { phase: PackPhase::Overrides, percent: 100.0 });
            // Während des Entpackens abgebrochen: trotzdem aufräumen.
            if crate::task::is_cancelled() {
                return Err(Error::Cancelled);
            }
            Ok::<_, Error>(blocked)
        }
        .await;

        match work {
            Ok(blocked) => Ok(PackOutcome { instance, blocked }),
            Err(e) => {
                // Halb installierte (oder abgebrochene) Packs nicht herumliegen lassen.
                if let Err(cleanup) = self.instances().delete(&instance.id).await {
                    tracing::warn!("Halb installiertes Modpack '{}' konnte nicht entfernt werden: {cleanup}", instance.id);
                }
                Err(e)
            }
        }
    }
}
