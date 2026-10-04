//! Gemeinsame Ordner zwischen Instanzen (wie bei Prism): Shader, Ressourcenpakete,
//! Screenshots, Welten und Schematics liegen einmal unter
//! `<daten>/shared-folders/<art>/`. In jeder Instanz, die einen davon teilt,
//! steht an seiner Stelle ein Link – unter Windows eine Junction (braucht keine
//! Adminrechte), unter Linux ein Symlink.
//!
//! Sicherheitsregeln:
//! - Einschalten verschiebt die Dateien der Instanz in den gemeinsamen Ordner.
//!   Gibt es den Namen dort schon, bleiben beide (Suffix „ (2)“) – überschrieben
//!   wird nie. Nur exakt gleiche Inhalte werden einmal behalten.
//! - Ausschalten ersetzt den Link durch einen echten Ordner: mit einer Kopie des
//!   gemeinsamen Inhalts oder leer. Der gemeinsame Ordner bleibt, wie er ist.
//! - Entfernt wird immer nur der Link, nie das, worauf er zeigt (auch beim
//!   Löschen einer Instanz).
//! - Fremde Links (z. B. selbst auf eine andere Platte gelegt) bleiben unangetastet.

use std::collections::HashSet;
use std::io::Read;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Deserializer, Serialize};

use crate::instance::Instance;
use crate::sync::packs::{Pool, Renames};
use crate::paths::Paths;
use crate::task::TaskControl;
use crate::{Error, Launcher, Result, platform};

/// Was zwischen Instanzen geteilt werden kann.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SharedFolder {
    Shaderpacks,
    Resourcepacks,
    Screenshots,
    Saves,
    Schematics,
}

impl SharedFolder {
    pub const ALL: [Self; 5] = [Self::Shaderpacks, Self::Resourcepacks, Self::Screenshots, Self::Saves, Self::Schematics];

    /// Name des gemeinsamen Ordners unter `shared-folders/`.
    pub fn key(self) -> &'static str {
        match self {
            Self::Shaderpacks => "shaderpacks",
            Self::Resourcepacks => "resourcepacks",
            Self::Screenshots => "screenshots",
            Self::Saves => "saves",
            Self::Schematics => "schematics",
        }
    }

    /// Stellen im Spielordner (mit `/`), die auf den gemeinsamen Ordner zeigen.
    /// Schematics: Litematica und WorldEdit teilen sich einen Ordner (Litematica
    /// liest auch `.schem` von WorldEdit).
    pub fn locations(self) -> &'static [&'static str] {
        match self {
            Self::Shaderpacks => &["shaderpacks"],
            Self::Resourcepacks => &["resourcepacks"],
            Self::Screenshots => &["screenshots"],
            Self::Saves => &["saves"],
            Self::Schematics => &["schematics", "config/worldedit/schematics"],
        }
    }
}

/// Doppelte raus, feste Reihenfolge.
pub fn normalize(list: &[SharedFolder]) -> Vec<SharedFolder> {
    SharedFolder::ALL.into_iter().filter(|k| list.contains(k)).collect()
}

/// Unbekannte Einträge (etwa aus einer neueren Version) fallen weg, statt die
/// ganze Datei unlesbar zu machen.
pub fn lenient<'de, D: Deserializer<'de>>(deserializer: D) -> std::result::Result<Vec<SharedFolder>, D::Error> {
    let raw = Option::<Vec<serde_json::Value>>::deserialize(deserializer)?.unwrap_or_default();
    let list: Vec<SharedFolder> = raw.into_iter().filter_map(|v| serde_json::from_value(v).ok()).collect();
    Ok(normalize(&list))
}

fn location_path(game_dir: &Path, rel: &str) -> PathBuf {
    rel.split('/').fold(game_dir.to_path_buf(), |path, part| path.join(part))
}

/// Zu welcher Art gehört diese Stelle (Komponenten ab dem Spielordner)?
pub fn location_kind<S: AsRef<str>>(rel: &[S]) -> Option<SharedFolder> {
    SharedFolder::ALL.into_iter().find(|kind| {
        kind.locations().iter().any(|loc| {
            let parts: Vec<&str> = loc.split('/').collect();
            parts.len() == rel.len() && parts.iter().zip(rel).all(|(a, b)| a.eq_ignore_ascii_case(b.as_ref()))
        })
    })
}

/// Was an einer Link-Stelle gerade liegt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LinkState {
    Missing,
    /// Link auf genau diesen gemeinsamen Ordner.
    Linked,
    /// Link, dessen Ziel es nicht (mehr) gibt.
    Broken,
    /// Link woandershin – gehört nicht uns.
    Foreign,
    /// Echter Ordner der Instanz.
    RealDir,
    /// Eine Datei o. Ä. steht im Weg.
    NotADir,
}

pub fn link_state(link: &Path, pool: &Path) -> LinkState {
    let meta = match std::fs::symlink_metadata(link) {
        Ok(meta) => meta,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return LinkState::Missing,
        Err(_) => return LinkState::Foreign,
    };
    // Unter Windows gelten Junctions als Symlink (Reparse-Punkt mit Namensersatz).
    if meta.file_type().is_symlink() {
        return match (std::fs::canonicalize(link), std::fs::canonicalize(pool)) {
            (Ok(target), Ok(pool)) if target == pool => LinkState::Linked,
            (Err(_), _) => LinkState::Broken,
            _ => LinkState::Foreign,
        };
    }
    if meta.is_dir() { LinkState::RealDir } else { LinkState::NotADir }
}

/// Erkennt die Links auf gemeinsame Ordner – für den Dateibrowser, Export und
/// Screenshots, die sonst jeden Link meiden.
#[derive(Debug, Clone, Default)]
pub struct SharedLinks {
    /// Kanonische gemeinsame Ordner, die es gibt.
    pools: Vec<(SharedFolder, PathBuf)>,
}

impl SharedLinks {
    pub fn new(paths: &Paths) -> Self {
        let pools = SharedFolder::ALL
            .into_iter()
            .filter_map(|kind| std::fs::canonicalize(paths.shared_folder(kind)).ok().map(|p| (kind, p)))
            .collect();
        Self { pools }
    }

    /// Kanonisches Ziel, wenn an `path` (Stelle `rel` ab dem Spielordner) ein
    /// Link auf den passenden gemeinsamen Ordner steht – sonst `None`.
    pub fn target<S: AsRef<str>>(&self, rel: &[S], path: &Path) -> Option<PathBuf> {
        let kind = location_kind(rel)?;
        let pool = &self.pools.iter().find(|(k, _)| *k == kind)?.1;
        let meta = std::fs::symlink_metadata(path).ok()?;
        if !meta.file_type().is_symlink() {
            return None;
        }
        let target = std::fs::canonicalize(path).ok()?;
        (target == *pool).then_some(target)
    }

    /// Wie [`Self::target`] mit einem relativen Pfad.
    pub fn is_link(&self, rel: &Path, path: &Path) -> bool {
        let parts: Vec<String> = rel.components().filter_map(|c| c.as_os_str().to_str().map(str::to_owned)).collect();
        self.target(&parts, path).is_some()
    }
}

fn io(path: &Path) -> impl FnOnce(std::io::Error) -> Error + '_ {
    move |e| Error::io(path, e)
}

fn foreign_link(rel: &str) -> Error {
    Error::validation(crate::msg!(
        "sharedFolders.foreignLink",
        "„{folder}“ ist in dieser Instanz schon mit einem anderen Ort verknüpft – bitte erst selbst lösen.",
        folder = rel
    ))
}

fn not_a_folder(rel: &str) -> Error {
    Error::validation(crate::msg!(
        "sharedFolders.notAFolder",
        "An der Stelle von „{folder}“ liegt eine Datei – bitte umbenennen oder entfernen.",
        folder = rel
    ))
}

/// Fortschritt und Abbruch für die blockierenden Schritte.
pub struct Work<'a> {
    pub progress: &'a dyn Fn(u8),
    pub control: Option<&'a TaskControl>,
}

impl Work<'_> {
    fn cancelled(&self) -> bool {
        self.control.is_some_and(TaskControl::is_cancelled)
    }

    fn percent(&self, done: u64, total: u64) {
        let p = done.saturating_mul(100).checked_div(total).map_or(100, |p| p.min(100));
        (self.progress)(p as u8);
    }
}

// --- Einschalten ------------------------------------------------------------------------

/// Teilt `kind` in diesem Spielordner: vorhandene Dateien wandern verlustfrei in
/// den gemeinsamen Ordner, an ihre Stelle kommt der Link. Schon verlinkte
/// Stellen bleiben, kaputte Links werden neu gesetzt.
pub fn link_blocking(game_dir: &Path, pool: &Path, kind: SharedFolder, work: &Work<'_>) -> Result<()> {
    std::fs::create_dir_all(pool).map_err(io(pool))?;
    let locations = kind.locations();
    for (i, rel) in locations.iter().enumerate() {
        let link = location_path(game_dir, rel);
        let step = |p: u8| {
            let overall = (i as u64 * 100 + u64::from(p)) / locations.len() as u64;
            (work.progress)(overall as u8);
        };
        let packs = kind == SharedFolder::Resourcepacks;
        let renames = link_one(&link, pool, rel, packs, &Work { progress: &step, control: work.control })?;
        if !renames.is_empty() {
            // Doppelte Pakete sind weg – options.txt zeigt auf die Datei im gemeinsamen Ordner.
            let options = game_dir.join("options.txt");
            crate::sync::packs::rewrite_options(&options, pool, &renames).map_err(io(&options))?;
        }
    }
    (work.progress)(100);
    Ok(())
}

fn link_one(link: &Path, pool: &Path, rel: &str, packs: bool, work: &Work<'_>) -> Result<Renames> {
    let mut renames = Renames::new();
    match link_state(link, pool) {
        LinkState::Linked => return Ok(renames),
        LinkState::Foreign => return Err(foreign_link(rel)),
        LinkState::NotADir => return Err(not_a_folder(rel)),
        // Ziel gibt es nicht mehr: am Link hängt nichts – nur ihn ersetzen.
        LinkState::Broken => platform::remove_dir_link(link).map_err(io(link))?,
        LinkState::RealDir => {
            renames = merge_into(link, pool, packs, work)?;
            // Nur ein leerer Ordner wird entfernt – nie etwas mit Inhalt.
            std::fs::remove_dir(link).map_err(io(link))?;
        }
        LinkState::Missing => {}
    }
    if let Some(parent) = link.parent() {
        std::fs::create_dir_all(parent).map_err(io(parent))?;
    }
    platform::create_dir_link(pool, link).map_err(io(link))?;
    Ok(renames)
}

/// Verschiebt alles aus `from` nach `pool`. Gleiche Namen: gleicher Inhalt →
/// einmal behalten, sonst beide (freier Name). Nichts wird überschrieben.
/// Ressourcenpakete (`packs`): liegt genau dieser Inhalt im Pool schon unter
/// anderem Namen, wird er nicht doppelt abgelegt. Liefert, welcher Name jetzt
/// für welchen gilt (für `options.txt`).
fn merge_into(from: &Path, pool: &Path, packs: bool, work: &Work<'_>) -> Result<Renames> {
    let entries: Vec<std::fs::DirEntry> = std::fs::read_dir(from).map_err(io(from))?.filter_map(|e| e.ok()).collect();
    let total = entries.len() as u64;
    let existing = packs.then(|| Pool::scan(pool));
    let mut renames = Renames::new();
    for (done, entry) in entries.into_iter().enumerate() {
        let src = entry.path();
        let name = entry.file_name();
        let dst = pool.join(&name);
        let taken = std::fs::symlink_metadata(&dst).is_ok();
        if taken && same_tree(&src, &dst) {
            remove_entry(&src)?;
        } else if let Some(name) = name.to_str()
            && let Some(same) = existing.as_ref().and_then(|pool| pool.identical(&src))
        {
            remove_entry(&src)?;
            renames.insert(name.to_owned(), same);
        } else if !taken {
            move_entry(&src, &dst)?;
        } else {
            let free = match name.to_str() {
                Some(name) => crate::instance_files::free_name(pool, name),
                None => format!("shared-{}", uuid::Uuid::new_v4().simple()),
            };
            move_entry(&src, &pool.join(&free))?;
            if packs && let Some(name) = name.to_str() {
                // Die eigene Fassung bleibt eingeschaltet, nicht die gleichnamige aus dem Pool.
                renames.insert(name.to_owned(), free);
            }
        }
        work.percent(done as u64 + 1, total);
    }
    Ok(renames)
}

/// Umbenennen; klappt das nicht (anderes Laufwerk), kopieren und dann die Quelle entfernen.
fn move_entry(src: &Path, dst: &Path) -> Result<()> {
    if std::fs::rename(src, dst).is_ok() {
        return Ok(());
    }
    let meta = std::fs::symlink_metadata(src).map_err(io(src))?;
    if meta.file_type().is_symlink() {
        // Links werden nicht kopiert – lieber abbrechen als etwas verlieren.
        return Err(Error::io(src, std::io::Error::other("link cannot be moved")));
    }
    if let Err(e) = copy_tree(src, dst, None, &|| false) {
        // Nur die halbe, frisch angelegte Kopie wieder weg – die Quelle bleibt.
        let _ = if dst.is_dir() { std::fs::remove_dir_all(dst) } else { std::fs::remove_file(dst) };
        return Err(e);
    }
    remove_entry(src)
}

/// Entfernt einen Eintrag; Links nur als Link.
fn remove_entry(path: &Path) -> Result<()> {
    let meta = std::fs::symlink_metadata(path).map_err(io(path))?;
    let result = if meta.file_type().is_symlink() {
        platform::remove_dir_link(path)
    } else if meta.is_dir() {
        // std::fs::remove_dir_all folgt keinen Links/Junctions darin.
        std::fs::remove_dir_all(path)
    } else {
        std::fs::remove_file(path)
    };
    result.map_err(io(path))
}

/// Gleicher Inhalt (Dateien Byte für Byte, Ordner rekursiv)? Links zählen nie als gleich.
fn same_tree(a: &Path, b: &Path) -> bool {
    let (Ok(ma), Ok(mb)) = (std::fs::symlink_metadata(a), std::fs::symlink_metadata(b)) else { return false };
    if ma.file_type().is_symlink() || mb.file_type().is_symlink() {
        return false;
    }
    if ma.is_file() && mb.is_file() {
        return ma.len() == mb.len() && same_content(a, b);
    }
    if !(ma.is_dir() && mb.is_dir()) {
        return false;
    }
    let names = |dir: &Path| -> Option<HashSet<std::ffi::OsString>> {
        Some(std::fs::read_dir(dir).ok()?.filter_map(|e| e.ok().map(|e| e.file_name())).collect())
    };
    match (names(a), names(b)) {
        (Some(na), Some(nb)) if na == nb => na.iter().all(|n| same_tree(&a.join(n), &b.join(n))),
        _ => false,
    }
}

fn same_content(a: &Path, b: &Path) -> bool {
    let (Ok(mut fa), Ok(mut fb)) = (std::fs::File::open(a), std::fs::File::open(b)) else { return false };
    let (mut ba, mut bb) = (vec![0u8; 64 * 1024], vec![0u8; 64 * 1024]);
    loop {
        let Ok(n) = fill(&mut fa, &mut ba) else { return false };
        let Ok(m) = fill(&mut fb, &mut bb) else { return false };
        if n != m || ba[..n] != bb[..m] {
            return false;
        }
        if n == 0 {
            return true;
        }
    }
}

/// Liest, bis der Puffer voll oder die Datei zu Ende ist.
fn fill(file: &mut std::fs::File, buf: &mut [u8]) -> std::io::Result<usize> {
    let mut filled = 0;
    while filled < buf.len() {
        match file.read(&mut buf[filled..])? {
            0 => break,
            n => filled += n,
        }
    }
    Ok(filled)
}

/// Kopiert Datei oder Ordner (ohne Links); `bytes` zählt mit, `cancelled` bricht ab.
fn copy_tree(src: &Path, dst: &Path, bytes: Option<&dyn Fn(u64)>, cancelled: &dyn Fn() -> bool) -> Result<()> {
    if cancelled() {
        return Err(Error::Cancelled);
    }
    let meta = std::fs::symlink_metadata(src).map_err(io(src))?;
    if meta.file_type().is_symlink() {
        return Ok(());
    }
    if meta.is_file() {
        let n = std::fs::copy(src, dst).map_err(io(dst))?;
        if let Some(count) = bytes {
            count(n);
        }
        return Ok(());
    }
    std::fs::create_dir(dst).map_err(io(dst))?;
    for entry in std::fs::read_dir(src).map_err(io(src))? {
        let entry = entry.map_err(io(src))?;
        copy_tree(&entry.path(), &dst.join(entry.file_name()), bytes, cancelled)?;
    }
    Ok(())
}

/// Größe ohne Links (für den Fortschritt).
fn tree_size(path: &Path) -> u64 {
    let Ok(meta) = std::fs::symlink_metadata(path) else { return 0 };
    if meta.is_file() {
        return meta.len();
    }
    if !meta.is_dir() {
        return 0;
    }
    std::fs::read_dir(path).map(|r| r.flatten().map(|e| tree_size(&e.path())).sum()).unwrap_or(0)
}

// --- Ausschalten ------------------------------------------------------------------------

/// Löst `kind` in diesem Spielordner: an die Stelle des Links kommt ein echter
/// Ordner – mit einer Kopie des gemeinsamen Inhalts (`copy`) oder leer. Erst
/// wenn die Kopie fertig ist, wird der Link entfernt; ein Abbruch lässt alles,
/// wie es war.
pub fn unlink_blocking(game_dir: &Path, pool: &Path, kind: SharedFolder, copy: bool, work: &Work<'_>) -> Result<()> {
    let links: Vec<(PathBuf, LinkState)> = kind
        .locations()
        .iter()
        .map(|rel| {
            let link = location_path(game_dir, rel);
            let state = link_state(&link, pool);
            (link, state)
        })
        .collect();
    let copies = links.iter().filter(|(_, s)| *s == LinkState::Linked).count() as u64;
    let total = if copy { tree_size(pool).saturating_mul(copies) } else { 0 };
    if let Some(control) = work.control {
        control.add_total(total);
    }
    let done = std::sync::atomic::AtomicU64::new(0);
    let count = |n: u64| {
        let now = done.fetch_add(n, std::sync::atomic::Ordering::Relaxed) + n;
        if let Some(control) = work.control {
            control.add_done(i64::try_from(n).unwrap_or(i64::MAX));
        }
        work.percent(now, total);
    };
    let cancelled = || work.cancelled();
    for (link, state) in links {
        match state {
            LinkState::Linked | LinkState::Broken => {
                let parent = link.parent().unwrap_or(game_dir);
                let name = link.file_name().and_then(|n| n.to_str()).unwrap_or("shared");
                let tmp = parent.join(format!(".{name}.trs-unlink-{}", uuid::Uuid::new_v4().simple()));
                let filled = if copy && state == LinkState::Linked {
                    copy_tree(pool, &tmp, Some(&count), &cancelled)
                } else {
                    std::fs::create_dir(&tmp).map_err(io(&tmp))
                };
                if let Err(e) = filled {
                    let _ = std::fs::remove_dir_all(&tmp);
                    return Err(e);
                }
                if let Err(e) = platform::remove_dir_link(&link) {
                    let _ = std::fs::remove_dir_all(&tmp);
                    return Err(Error::io(&link, e));
                }
                std::fs::rename(&tmp, &link).map_err(io(&link))?;
            }
            LinkState::Missing => std::fs::create_dir_all(&link).map_err(io(&link))?,
            // Schon ein eigener Ordner – oder etwas Fremdes, das wir nicht anfassen.
            LinkState::RealDir | LinkState::Foreign | LinkState::NotADir => {}
        }
    }
    (work.progress)(100);
    Ok(())
}

/// Entfernt alle Links auf gemeinsame Ordner in diesem Spielordner – nur die
/// Links, der gemeinsame Inhalt bleibt. Vor dem Löschen einer Instanz.
pub fn remove_links(game_dir: &Path) -> std::io::Result<()> {
    for kind in SharedFolder::ALL {
        for rel in kind.locations() {
            let link = location_path(game_dir, rel);
            if std::fs::symlink_metadata(&link).is_ok_and(|m| m.file_type().is_symlink()) {
                platform::remove_dir_link(&link)?;
            }
        }
    }
    Ok(())
}

// --- Launcher ---------------------------------------------------------------------------

/// Zustand einer Art in einer Instanz (Bereich „Gemeinsame Ordner“).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SharedFolderStatus {
    pub kind: SharedFolder,
    /// In den Einstellungen der Instanz eingeschaltet.
    pub enabled: bool,
    /// Alle Links stehen (sonst: wird beim nächsten Start eingerichtet).
    pub linked: bool,
    /// Ein fremder Link oder eine Datei steht im Weg.
    pub blocked: bool,
    /// So viele Instanzen teilen diesen Ordner (diese mitgezählt).
    pub instances: u32,
}

fn running_error() -> Error {
    Error::launch(crate::msg!("launcher.instanceRunningStopFirst", "Die Instanz läuft gerade – bitte erst beenden."))
}

impl Launcher {
    /// Zustand aller gemeinsamen Ordner einer Instanz.
    pub async fn shared_folders(&self, id: &str) -> Result<Vec<SharedFolderStatus>> {
        let instance = self.instances().get(id).await?;
        let all = self.instances().list().await?;
        let paths = self.paths().clone();
        let game_dir = paths.instance_game_dir(&instance.id);
        let enabled = instance.overrides.shared_folders.clone();
        let counts: Vec<u32> = SharedFolder::ALL
            .iter()
            .map(|k| all.iter().filter(|i| i.overrides.shared_folders.contains(k)).count() as u32)
            .collect();
        tokio::task::spawn_blocking(move || {
            SharedFolder::ALL
                .into_iter()
                .zip(counts)
                .map(|(kind, instances)| {
                    let pool = paths.shared_folder(kind);
                    let states: Vec<LinkState> =
                        kind.locations().iter().map(|rel| link_state(&location_path(&game_dir, rel), &pool)).collect();
                    SharedFolderStatus {
                        kind,
                        enabled: enabled.contains(&kind),
                        linked: states.iter().all(|s| *s == LinkState::Linked),
                        blocked: states.iter().any(|s| matches!(s, LinkState::Foreign | LinkState::NotADir)),
                        instances,
                    }
                })
                .collect()
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))
    }

    /// Schaltet einen gemeinsamen Ordner für eine Instanz ein oder aus.
    /// `copy` (nur beim Ausschalten): Inhalt des gemeinsamen Ordners mitnehmen.
    pub async fn set_shared_folder(
        &self,
        id: &str,
        kind: SharedFolder,
        enabled: bool,
        copy: bool,
        on_progress: impl Fn(u8) + Send + 'static,
    ) -> Result<Instance> {
        let instance = self.instances().get(id).await?;
        if self.games().is_running(&instance.id) || self.is_preparing(&instance.id) {
            return Err(running_error());
        }
        let _guard = self.shared_folders_lock.lock().await;
        // Kopieren, während ein anderes Spiel in denselben Ordner schreibt, ergäbe halbe Welten.
        if !enabled && copy && self.other_game_uses(&instance.id, kind).await {
            return Err(Error::launch(crate::msg!(
                "sharedFolders.inUse",
                "Ein anderes Spiel nutzt diesen gemeinsamen Ordner gerade – bitte erst beenden."
            )));
        }
        let game_dir = self.paths().instance_game_dir(&instance.id);
        let pool = self.paths().shared_folder(kind);
        let control = crate::task::current();
        tokio::task::spawn_blocking(move || {
            let work = Work { progress: &on_progress, control: control.as_ref() };
            if enabled {
                link_blocking(&game_dir, &pool, kind, &work)
            } else {
                unlink_blocking(&game_dir, &pool, kind, copy, &work)
            }
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))??;

        let mut list = instance.overrides.shared_folders.clone();
        list.retain(|k| *k != kind);
        if enabled {
            list.push(kind);
        }
        let updated = self.instances().set_shared_folders(&instance.id, &list).await?;
        tracing::info!("Gemeinsamer Ordner '{}' für '{}' {}", kind.key(), instance.id, if enabled { "an" } else { "aus" });
        Ok(updated)
    }

    async fn other_game_uses(&self, id: &str, kind: SharedFolder) -> bool {
        for game in self.games().running() {
            if game.instance_id == id {
                continue;
            }
            if self.instances().get(&game.instance_id).await.is_ok_and(|i| i.overrides.shared_folders.contains(&kind)) {
                return true;
            }
        }
        false
    }

    /// Vor dem Start (und nach Kopie/Anlegen): eingeschaltete gemeinsame Ordner
    /// sicherstellen – fehlende oder kaputte Links neu setzen, ein echter Ordner an
    /// ihrer Stelle wird erst zusammengeführt. Liefert die Arten, die nicht klappten.
    pub(crate) async fn ensure_shared_folders(&self, instance: &Instance) -> Vec<SharedFolder> {
        let kinds = instance.overrides.shared_folders.clone();
        if kinds.is_empty() {
            return Vec::new();
        }
        let _guard = self.shared_folders_lock.lock().await;
        let paths = self.paths().clone();
        let game_dir = paths.instance_game_dir(&instance.id);
        let id = instance.id.clone();
        tokio::task::spawn_blocking(move || {
            let quiet = |_: u8| {};
            let work = Work { progress: &quiet, control: None };
            kinds
                .into_iter()
                .filter(|kind| match link_blocking(&game_dir, &paths.shared_folder(*kind), *kind, &work) {
                    Ok(()) => false,
                    Err(e) => {
                        tracing::warn!("Gemeinsamer Ordner '{}' in '{id}' nicht eingerichtet: {e}", kind.key());
                        true
                    }
                })
                .collect()
        })
        .await
        .unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::*;

    fn write(path: &Path, text: &str) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, text).unwrap();
    }

    fn read(path: &Path) -> String {
        fs::read_to_string(path).unwrap()
    }

    fn quiet() -> Work<'static> {
        Work { progress: &|_| {}, control: None }
    }

    fn is_link(path: &Path) -> bool {
        fs::symlink_metadata(path).is_ok_and(|m| m.file_type().is_symlink())
    }

    #[test]
    fn kinds_and_locations() {
        assert_eq!(location_kind(&["saves"]), Some(SharedFolder::Saves));
        assert_eq!(location_kind(&["config", "worldedit", "schematics"]), Some(SharedFolder::Schematics));
        assert_eq!(location_kind(&["Schematics"]), Some(SharedFolder::Schematics));
        assert_eq!(location_kind(&["mods"]), None);
        assert_eq!(location_kind::<&str>(&[]), None);
        assert_eq!(normalize(&[SharedFolder::Saves, SharedFolder::Shaderpacks, SharedFolder::Saves]), [
            SharedFolder::Shaderpacks,
            SharedFolder::Saves
        ]);
        #[derive(Deserialize)]
        struct Wrap {
            #[serde(deserialize_with = "lenient", default)]
            list: Vec<SharedFolder>,
        }
        let parsed: Wrap = serde_json::from_str(r#"{"list":["saves","neu-in-v9","saves","shaderpacks"]}"#).unwrap();
        assert_eq!(parsed.list, [SharedFolder::Shaderpacks, SharedFolder::Saves]);
        let parsed: Wrap = serde_json::from_str(r#"{"list":null}"#).unwrap();
        assert!(parsed.list.is_empty());
    }

    #[test]
    fn linking_merges_without_loss_and_keeps_both_on_conflicts() {
        let root = tempfile::tempdir().unwrap();
        let pool = root.path().join("pool");
        let a = root.path().join("a");
        let b = root.path().join("b");
        write(&a.join("shaderpacks/BSL.zip"), "bsl");
        write(&a.join("shaderpacks/gleich.zip"), "same");
        write(&b.join("shaderpacks/Complementary.zip"), "comp");
        write(&b.join("shaderpacks/BSL.zip"), "bsl-anders");
        write(&b.join("shaderpacks/gleich.zip"), "same");
        write(&b.join("shaderpacks/entpackt/shaders/x.fsh"), "x");

        link_blocking(&a, &pool, SharedFolder::Shaderpacks, &quiet()).unwrap();
        assert!(is_link(&a.join("shaderpacks")));
        assert_eq!(link_state(&a.join("shaderpacks"), &pool), LinkState::Linked);
        assert_eq!(read(&pool.join("BSL.zip")), "bsl");

        link_blocking(&b, &pool, SharedFolder::Shaderpacks, &quiet()).unwrap();
        assert!(is_link(&b.join("shaderpacks")));
        // Gleicher Name, anderer Inhalt: beide bleiben.
        assert_eq!(read(&pool.join("BSL.zip")), "bsl");
        assert_eq!(read(&pool.join("BSL (2).zip")), "bsl-anders");
        // Gleicher Inhalt nur einmal.
        assert!(!pool.join("gleich (2).zip").exists());
        assert_eq!(read(&pool.join("Complementary.zip")), "comp");
        assert_eq!(read(&pool.join("entpackt/shaders/x.fsh")), "x");
        // Beide Instanzen sehen denselben Inhalt.
        assert_eq!(read(&a.join("shaderpacks/Complementary.zip")), "comp");
        // Nochmal einschalten ändert nichts.
        link_blocking(&b, &pool, SharedFolder::Shaderpacks, &quiet()).unwrap();
        assert_eq!(fs::read_dir(&pool).unwrap().count(), 5);
    }

    #[test]
    fn resource_packs_are_pooled_once_and_options_follow() {
        let root = tempfile::tempdir().unwrap();
        let pool = root.path().join("pool");
        let a = root.path().join("a");
        let b = root.path().join("b");
        write(&a.join("resourcepacks/Faithful.zip"), "faithful");
        write(&a.join("resourcepacks/Eigenes.zip"), "a");
        link_blocking(&a, &pool, SharedFolder::Resourcepacks, &quiet()).unwrap();

        write(&b.join("resourcepacks/faithful-kopie.zip"), "faithful");
        write(&b.join("resourcepacks/Eigenes.zip"), "b");
        write(&b.join("options.txt"), "resourcePacks:[\"vanilla\",\"file/faithful-kopie.zip\",\"file/Eigenes.zip\"]\n");
        link_blocking(&b, &pool, SharedFolder::Resourcepacks, &quiet()).unwrap();
        // Gleicher Inhalt unter anderem Namen nur einmal; B behält sein eigenes Paket.
        assert!(!pool.join("faithful-kopie.zip").exists());
        assert_eq!(read(&pool.join("Eigenes (2).zip")), "b");
        assert_eq!(
            read(&b.join("options.txt")),
            "resourcePacks:[\"vanilla\",\"file/Faithful.zip\",\"file/Eigenes (2).zip\"]\n"
        );
    }

    #[test]
    fn worlds_with_the_same_name_are_both_kept() {
        let root = tempfile::tempdir().unwrap();
        let pool = root.path().join("pool");
        let (a, b) = (root.path().join("a"), root.path().join("b"));
        write(&a.join("saves/Neue Welt/level.dat"), "a");
        write(&b.join("saves/Neue Welt/level.dat"), "b");
        link_blocking(&a, &pool, SharedFolder::Saves, &quiet()).unwrap();
        link_blocking(&b, &pool, SharedFolder::Saves, &quiet()).unwrap();
        assert_eq!(read(&pool.join("Neue Welt/level.dat")), "a");
        assert_eq!(read(&pool.join("Neue Welt (2)/level.dat")), "b");
    }

    #[test]
    fn unlinking_copies_or_starts_empty_and_leaves_the_pool() {
        let root = tempfile::tempdir().unwrap();
        let pool = root.path().join("pool");
        let (a, b) = (root.path().join("a"), root.path().join("b"));
        write(&a.join("screenshots/1.png"), "1");
        link_blocking(&a, &pool, SharedFolder::Screenshots, &quiet()).unwrap();
        link_blocking(&b, &pool, SharedFolder::Screenshots, &quiet()).unwrap();

        let seen = std::sync::Mutex::new(Vec::new());
        let progress = |p: u8| seen.lock().unwrap().push(p);
        unlink_blocking(&a, &pool, SharedFolder::Screenshots, true, &Work { progress: &progress, control: None }).unwrap();
        assert!(!is_link(&a.join("screenshots")));
        assert_eq!(read(&a.join("screenshots/1.png")), "1");
        assert_eq!(seen.lock().unwrap().last(), Some(&100));
        // Kopie ist eigenständig.
        write(&a.join("screenshots/nur-a.png"), "a");
        assert!(!pool.join("nur-a.png").exists());

        unlink_blocking(&b, &pool, SharedFolder::Screenshots, false, &quiet()).unwrap();
        assert!(b.join("screenshots").is_dir() && !is_link(&b.join("screenshots")));
        assert_eq!(fs::read_dir(b.join("screenshots")).unwrap().count(), 0);
        assert_eq!(read(&pool.join("1.png")), "1", "der gemeinsame Ordner bleibt");

        // Wieder einschalten: identische Kopien werden nicht verdoppelt.
        link_blocking(&a, &pool, SharedFolder::Screenshots, &quiet()).unwrap();
        assert!(!pool.join("1 (2).png").exists());
        assert_eq!(read(&pool.join("nur-a.png")), "a");
    }

    #[test]
    fn cancelled_unlink_keeps_the_link() {
        let root = tempfile::tempdir().unwrap();
        let pool = root.path().join("pool");
        let a = root.path().join("a");
        write(&a.join("saves/w/level.dat"), "w");
        link_blocking(&a, &pool, SharedFolder::Saves, &quiet()).unwrap();
        let control = TaskControl::new();
        control.cancel();
        let work = Work { progress: &|_| {}, control: Some(&control) };
        assert!(matches!(unlink_blocking(&a, &pool, SharedFolder::Saves, true, &work), Err(Error::Cancelled)));
        assert!(is_link(&a.join("saves")));
        assert_eq!(read(&a.join("saves/w/level.dat")), "w");
        // Keine Reste des Abbruchs.
        assert_eq!(fs::read_dir(&a).unwrap().count(), 1);
    }

    #[test]
    fn schematics_link_both_places_into_one_pool() {
        let root = tempfile::tempdir().unwrap();
        let pool = root.path().join("pool");
        let a = root.path().join("a");
        write(&a.join("schematics/haus.litematic"), "L");
        write(&a.join("config/worldedit/schematics/tor.schem"), "W");
        write(&a.join("config/worldedit/worldedit.properties"), "p");
        link_blocking(&a, &pool, SharedFolder::Schematics, &quiet()).unwrap();
        assert!(is_link(&a.join("schematics")) && is_link(&a.join("config/worldedit/schematics")));
        assert_eq!(read(&a.join("schematics/tor.schem")), "W");
        assert_eq!(read(&a.join("config/worldedit/schematics/haus.litematic")), "L");
        assert_eq!(read(&a.join("config/worldedit/worldedit.properties")), "p");
    }

    #[test]
    fn repair_relinks_and_merges_a_new_real_folder() {
        let root = tempfile::tempdir().unwrap();
        let pool = root.path().join("pool");
        let a = root.path().join("a");
        link_blocking(&a, &pool, SharedFolder::Resourcepacks, &quiet()).unwrap();
        // Jemand hat den Link entfernt und das Spiel einen echten Ordner angelegt.
        platform::remove_dir_link(&a.join("resourcepacks")).unwrap();
        write(&a.join("resourcepacks/neu.zip"), "n");
        assert_eq!(link_state(&a.join("resourcepacks"), &pool), LinkState::RealDir);
        link_blocking(&a, &pool, SharedFolder::Resourcepacks, &quiet()).unwrap();
        assert_eq!(link_state(&a.join("resourcepacks"), &pool), LinkState::Linked);
        assert_eq!(read(&pool.join("neu.zip")), "n");

        // Fehlt der Link ganz, wird er einfach neu gesetzt.
        platform::remove_dir_link(&a.join("resourcepacks")).unwrap();
        assert_eq!(link_state(&a.join("resourcepacks"), &pool), LinkState::Missing);
        link_blocking(&a, &pool, SharedFolder::Resourcepacks, &quiet()).unwrap();
        assert_eq!(read(&a.join("resourcepacks/neu.zip")), "n");
    }

    #[test]
    fn foreign_links_and_files_are_left_alone() {
        let root = tempfile::tempdir().unwrap();
        let pool = root.path().join("pool");
        let elsewhere = root.path().join("andere-platte");
        write(&elsewhere.join("welt/level.dat"), "x");
        let a = root.path().join("a");
        fs::create_dir_all(&a).unwrap();
        platform::create_dir_link(&elsewhere, &a.join("saves")).unwrap();
        assert_eq!(link_state(&a.join("saves"), &pool), LinkState::Foreign);
        assert!(link_blocking(&a, &pool, SharedFolder::Saves, &quiet()).is_err());
        assert_eq!(read(&a.join("saves/welt/level.dat")), "x");
        // Ausschalten fasst ihn auch nicht an.
        unlink_blocking(&a, &pool, SharedFolder::Saves, true, &quiet()).unwrap();
        assert!(is_link(&a.join("saves")));

        write(&a.join("shaderpacks"), "datei");
        assert!(link_blocking(&a, &pool, SharedFolder::Shaderpacks, &quiet()).is_err());
        assert_eq!(read(&a.join("shaderpacks")), "datei");
    }

    #[test]
    fn removing_links_never_touches_the_pool() {
        let root = tempfile::tempdir().unwrap();
        let pool = root.path().join("pool");
        let a = root.path().join("a");
        write(&a.join("saves/w/level.dat"), "w");
        write(&a.join("options.txt"), "o");
        link_blocking(&a, &pool, SharedFolder::Saves, &quiet()).unwrap();
        remove_links(&a).unwrap();
        assert!(!a.join("saves").exists());
        fs::remove_dir_all(&a).unwrap();
        assert_eq!(read(&pool.join("w/level.dat")), "w");
    }

    #[test]
    fn std_remove_dir_all_does_not_follow_links() {
        // Zweite Sicherung: selbst ohne remove_links bleibt das Ziel eines Links heil.
        let root = tempfile::tempdir().unwrap();
        let target = root.path().join("ziel");
        write(&target.join("wichtig.txt"), "w");
        let a = root.path().join("a");
        fs::create_dir_all(a.join("tief")).unwrap();
        platform::create_dir_link(&target, &a.join("tief/link")).unwrap();
        assert_eq!(read(&a.join("tief/link/wichtig.txt")), "w");
        fs::remove_dir_all(&a).unwrap();
        assert_eq!(read(&target.join("wichtig.txt")), "w");
    }

    #[cfg(windows)]
    #[test]
    fn junctions_need_no_admin_and_resolve_like_folders() {
        let root = tempfile::tempdir().unwrap();
        let target = root.path().join("ziel mit leerzeichen ä");
        write(&target.join("a.txt"), "a");
        let link = root.path().join("link");
        platform::create_dir_link(&target, &link).unwrap();
        use std::os::windows::fs::MetadataExt;
        let meta = fs::symlink_metadata(&link).unwrap();
        assert!(meta.file_type().is_symlink());
        assert!(meta.file_attributes() & 0x400 != 0, "Reparse-Punkt");
        assert_eq!(fs::canonicalize(&link).unwrap(), fs::canonicalize(&target).unwrap());
        assert_eq!(read(&link.join("a.txt")), "a");
        // Schreiben durch die Junction landet im Ziel.
        fs::write(link.join("b.txt"), "b").unwrap();
        assert_eq!(read(&target.join("b.txt")), "b");
        platform::remove_dir_link(&link).unwrap();
        assert!(!link.exists());
        assert_eq!(read(&target.join("a.txt")), "a");
        // Ein echter Ordner wird nicht als Link entfernt.
        assert!(platform::remove_dir_link(&target).is_err());
        // Fehlschlag (Ziel fehlt) hinterlässt keinen leeren Ordner.
        assert!(platform::create_dir_link(&root.path().join("fehlt"), &link).is_err());
        assert!(!link.exists());
    }

    #[test]
    fn shared_links_are_only_recognized_at_their_places() {
        let root = tempfile::tempdir().unwrap();
        let paths = Paths::new(root.path().join("daten"));
        let game = root.path().join("game");
        link_blocking(&game, &paths.shared_folder(SharedFolder::Saves), SharedFolder::Saves, &quiet()).unwrap();
        let links = SharedLinks::new(&paths);
        assert!(links.target(&["saves"], &game.join("saves")).is_some());
        assert!(links.is_link(Path::new("saves"), &game.join("saves")));
        // Gleicher Link an anderer Stelle: nicht erkannt.
        platform::create_dir_link(&paths.shared_folder(SharedFolder::Saves), &game.join("mods")).unwrap();
        assert!(links.target(&["mods"], &game.join("mods")).is_none());
        // Echter Ordner an der richtigen Stelle: kein Link.
        fs::create_dir_all(game.join("shaderpacks")).unwrap();
        assert!(links.target(&["shaderpacks"], &game.join("shaderpacks")).is_none());
    }
}
