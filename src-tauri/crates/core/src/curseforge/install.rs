//! Inhalte von CurseForge in eine Instanz: installieren (mit
//! Pflicht-Abhängigkeiten), Updates finden, Versionen wechseln.

use std::collections::{HashMap, HashSet};

use chrono::{Duration, Utc};
use serde::Serialize;

use super::pack::{self, BlockedFile};
use super::{
    CLASS_MODS, CurseForge, RawFile, RawFileIndex, RawMod, content_kind_of_class, invalid_file_id, invalid_project_id,
    is_allowed_download_url, loader_tag_of, mods_by_id, newest_in_channel, not_found, parse_id, release_type_name,
    summarize,
};
use crate::content::{self, ContentItem, ContentKind, Platform, ProjectMeta, Source};
use crate::download::{self, Task};
use crate::history::{self, HistoryEntry, HistoryKind};
use crate::instance::Instance;
use crate::modrinth::{self, MigrationItem, MigrationStatus, UpdateInfo, VersionSummary, clip};
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

const MAX_DEPENDENCY_DEPTH: u8 = 4;
/// Projekt-Infos (Titel, Icon) so lange nicht neu laden.
const META_MAX_AGE_DAYS: i64 = 7;
/// Changelogs lädt CurseForge nur einzeln – nicht mehr als so viele auf einmal.
const MAX_CHANGELOG_VERSIONS: usize = 10;

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallOutcome {
    /// Neu installierte Dateien.
    pub files: Vec<String>,
    /// Dateien, deren Autor Downloads über andere Apps nicht erlaubt – der
    /// Nutzer lädt sie selbst (sie stehen danach in der Liste der Instanz).
    pub blocked: Vec<BlockedFile>,
}

struct Planned {
    m: RawMod,
    file: RawFile,
    kind: ContentKind,
    dependency: bool,
}

/// Was eine einzelne Installation verändert hat (für Verlauf und Projekt-Infos).
pub(super) struct Installed {
    pub file_name: String,
    pub file_id: u64,
    pub key: String,
    pub meta: ProjectMeta,
    pub version_number: String,
    pub previous: Option<Source>,
    pub dependency: bool,
}

pub(super) fn meta_of(m: &RawMod) -> ProjectMeta {
    ProjectMeta {
        title: m.title(),
        slug: clip(m.slug.clone(), 100),
        author: m.author(),
        description: Some(clip(m.summary.clone(), 300)).filter(|d| !d.is_empty()),
        icon_url: m.icon_url(),
        fetched_at: Utc::now(),
    }
}

pub(super) fn cf_key(project_id: u64) -> String {
    content::project_key(Platform::CurseForge, &project_id.to_string())
}

pub(super) fn cf_source(project_id: u64, file: &RawFile) -> Source {
    Source {
        project_id: project_id.to_string(),
        version_id: file.id.to_string(),
        version_number: Some(file.version_label()).filter(|v| !v.is_empty()),
        platform: Platform::CurseForge,
    }
}

pub(super) fn blocked_error(m: &RawMod) -> Error {
    Error::validation(crate::msg!(
        "curseforge.downloadBlocked",
        "Der Autor von „{name}“ erlaubt keine Downloads über andere Apps – bitte die Datei auf CurseForge herunterladen.",
        name = m.title()
    ))
}

fn no_compatible(instance: &Instance) -> Error {
    Error::validation(crate::msg!(
        "curseforge.noCompatibleFile",
        "Für Minecraft {version} mit diesem Modloader gibt es auf CurseForge keine passende Datei.",
        version = &instance.game_version
    ))
}

fn ensure_kind(m: &RawMod, kind: ContentKind) -> Result<()> {
    if m.class_id.and_then(content_kind_of_class) == Some(kind) {
        Ok(())
    } else {
        Err(Error::validation(crate::msg!(
            "curseforge.wrongKind",
            "Dieses CurseForge-Projekt passt nicht zu dieser Inhaltsart."
        )))
    }
}

fn required_dependencies(file: &RawFile, depth: u8) -> Vec<(u64, u8)> {
    if depth > MAX_DEPENDENCY_DEPTH {
        return Vec::new();
    }
    file.dependencies.iter().filter(|d| d.relation_type == 3 && d.mod_id > 0).map(|d| (d.mod_id, depth)).collect()
}

/// Neuester Datei-Index eines Projekts für Version + Loader der Instanz.
fn newest_index<'a>(
    m: &'a RawMod,
    instance: &Instance,
    kind: ContentKind,
    allow: impl Fn(&str) -> bool,
) -> Option<&'a RawFileIndex> {
    let tags = modrinth::loader_tags(modrinth::content_loader(instance));
    m.latest_files_indexes
        .iter()
        .filter(|i| i.game_version == instance.game_version && i.file_id > 0 && allow(release_type_name(i.release_type)))
        .filter(|i| {
            kind != ContentKind::Mod
                || match i.mod_loader.and_then(loader_tag_of) {
                    Some(tag) => tags.contains(&tag),
                    // Alte Forge-Dateien nennen keinen Loader.
                    None => tags.contains(&"forge"),
                }
        })
        .max_by_key(|i| i.file_id)
}

/// Anzeige-Version aus dem Dateinamen eines Index-Eintrags.
fn index_label(ix: &RawFileIndex) -> String {
    let file = RawFile {
        id: ix.file_id,
        mod_id: 0,
        display_name: String::new(),
        file_name: ix.filename.clone(),
        release_type: ix.release_type,
        hashes: Vec::new(),
        file_date: None,
        file_length: 0,
        download_url: None,
        game_versions: Vec::new(),
        dependencies: Vec::new(),
        is_server_pack: None,
        is_available: None,
    };
    file.version_label()
}

/// Ersetzt ältere Dateien desselben Projekts und merkt sich die Herkunft.
/// Liefert die bisherige Quelle (für „Update von … auf …“).
pub(super) async fn register_file(
    paths: &Paths,
    instance_id: &str,
    kind: ContentKind,
    project_id: u64,
    file: &RawFile,
    replace: Option<&str>,
) -> Result<Option<Source>> {
    let key = cf_key(project_id);
    let previous = content::source_of_project(paths, instance_id, &key).await;
    let mut obsolete = content::files_of_project(paths, instance_id, &key).await;
    if let Some(old) = replace {
        obsolete.push((kind, old.to_owned()));
    }
    let replaced_any = obsolete.iter().any(|(k, old)| *k != kind || old != &file.file_name);
    for (old_kind, old_file) in obsolete {
        if old_kind != kind || old_file != file.file_name {
            content::remove_file(paths, instance_id, old_kind, &old_file).await?;
        }
    }
    content::remember_source(paths, instance_id, kind, &file.file_name, cf_source(project_id, file)).await?;
    // Von Hand ersetzte Datei ohne Index-Eintrag zählt auch als Update.
    Ok(previous.or_else(|| replaced_any.then(|| Source::modrinth(String::new(), String::new(), None))))
}

/// Alle von CurseForge installierten Inhalte der Instanz.
async fn curseforge_items(paths: &Paths, instance: &Instance) -> Result<Vec<(ContentItem, Source)>> {
    let mut out = Vec::new();
    for kind in ContentKind::ALL {
        for item in content::list(paths, &instance.id, kind).await? {
            if let Some(source) = item.source.clone().filter(|s| s.platform == Platform::CurseForge) {
                out.push((item, source));
            }
        }
    }
    Ok(out)
}

impl CurseForge {
    /// Installiert eine Datei samt Pflicht-Abhängigkeiten; ohne `file_id` die
    /// neueste passende. Gesperrte Dateien werden nicht geladen, sondern als
    /// „von Hand laden“ gemeldet und bei der Instanz vorgemerkt.
    pub async fn install(
        &self,
        paths: &Paths,
        instance: &Instance,
        project_id: &str,
        kind: ContentKind,
        file_id: Option<&str>,
    ) -> Result<InstallOutcome> {
        let id = parse_id(project_id).ok_or_else(invalid_project_id)?;
        modrinth::ensure_mods_allowed(kind, instance)?;
        let root = self.mod_info(id).await?;
        ensure_kind(&root, kind)?;
        let file = match file_id {
            Some(f) => self.file(id, parse_id(f).ok_or_else(invalid_file_id)?).await?,
            None => newest_in_channel(self.compatible_files(id, kind, instance).await?, instance.overrides.channel())
                .ok_or_else(|| no_compatible(instance))?,
        };
        let plan = self.plan_with_dependencies(paths, instance, root, file, kind).await?;

        let mut outcome = InstallOutcome::default();
        let mut installed = Vec::new();
        let mut result = Ok(());
        // Abhängigkeiten zuerst (tiefste zuerst), die Mod zuletzt: Scheitert ein
        // Download, liegt keine Mod ohne ihre Abhängigkeit im Ordner.
        for p in plan.into_iter().rev() {
            if p.m.download_url(&p.file).is_none() {
                outcome.blocked.push(BlockedFile::new(&p.m, &p.file, p.kind, None));
                continue;
            }
            match self.install_file(paths, instance, p.kind, &p.m, &p.file, None, p.dependency).await {
                Ok(item) => {
                    outcome.files.push(item.file_name.clone());
                    installed.push(item);
                }
                Err(e) => {
                    result = Err(e);
                    break;
                }
            }
        }
        pack::remember_blocked(paths, &instance.id, &outcome.blocked).await?;
        record_installed(paths, &instance.id, &installed).await;
        result.map(|()| outcome)
    }

    async fn plan_with_dependencies(
        &self,
        paths: &Paths,
        instance: &Instance,
        root: RawMod,
        file: RawFile,
        kind: ContentKind,
    ) -> Result<Vec<Planned>> {
        // Eine deaktivierte Abhängigkeit zählt nicht – das Spiel lädt sie nicht.
        let installed: HashSet<String> = content::enabled_project_ids(paths, &instance.id).await?.into_iter().collect();
        // Schon von Modrinth installiert? Gleicher Slug oder Titel reicht als Hinweis –
        // sonst läge z. B. die Fabric API doppelt im Mods-Ordner.
        let index = content::read_index(paths, &instance.id).await;
        let known: HashSet<String> = index
            .projects
            .values()
            .flat_map(|p| [p.slug.to_lowercase(), p.title.to_lowercase()])
            .filter(|s| !s.is_empty())
            .collect();
        let channel = instance.overrides.channel();

        let mut visited: HashSet<u64> = HashSet::from([root.id]);
        let mut queue = if kind == ContentKind::Mod { required_dependencies(&file, 1) } else { Vec::new() };
        let mut plan = vec![Planned { m: root, file, kind, dependency: false }];
        while let Some((dep, depth)) = queue.pop() {
            if !visited.insert(dep) || installed.contains(&cf_key(dep)) {
                continue;
            }
            let m = match self.mod_info(dep).await {
                Ok(m) => m,
                Err(e) => {
                    tracing::warn!("CurseForge-Abhängigkeit {dep} nicht gefunden – übersprungen: {e}");
                    continue;
                }
            };
            if m.class_id != Some(CLASS_MODS) {
                continue;
            }
            if known.contains(&m.slug.to_lowercase()) || known.contains(&m.name.to_lowercase()) {
                tracing::debug!("Abhängigkeit „{}“ ist schon installiert (andere Quelle)", m.name);
                continue;
            }
            let candidates = self.compatible_files(dep, ContentKind::Mod, instance).await?;
            let Some(dep_file) = newest_in_channel(candidates, channel) else {
                tracing::warn!("Abhängigkeit {dep} hat keine passende Datei – übersprungen");
                continue;
            };
            queue.extend(required_dependencies(&dep_file, depth + 1));
            plan.push(Planned { m, file: dep_file, kind: ContentKind::Mod, dependency: true });
        }
        Ok(plan)
    }

    /// Lädt eine (freigegebene) Datei und trägt sie ein.
    #[allow(clippy::too_many_arguments)]
    async fn install_file(
        &self,
        paths: &Paths,
        instance: &Instance,
        kind: ContentKind,
        m: &RawMod,
        file: &RawFile,
        replace: Option<&str>,
        dependency: bool,
    ) -> Result<Installed> {
        content::validate_file_name(kind, &file.file_name)?;
        let url = m.download_url(file).ok_or_else(|| blocked_error(m))?;
        if !is_allowed_download_url(url) {
            return Err(Error::download(url, "Download liegt nicht auf CurseForges CDN"));
        }
        let dir = content::content_dir(paths, &instance.id, kind);
        fsutil::ensure_dir(&dir).await?;
        download::fetch_one(
            self.download_client(),
            &Task {
                url: url.to_owned(),
                path: dir.join(&file.file_name),
                sha1: file.sha1(),
                size: Some(file.file_length).filter(|s| *s > 0),
            },
        )
        .await?;
        let previous = register_file(paths, &instance.id, kind, m.id, file, replace).await?;
        Ok(Installed {
            file_name: file.file_name.clone(),
            file_id: file.id,
            key: cf_key(m.id),
            meta: meta_of(m),
            version_number: file.version_label(),
            previous,
            dependency,
        })
    }

    /// Ersetzt `file_name` durch die Datei `file_id` (Update, Wechsel, Downgrade).
    pub async fn apply_update(
        &self,
        paths: &Paths,
        instance: &Instance,
        kind: ContentKind,
        file_name: &str,
        file_id: &str,
    ) -> Result<String> {
        content::validate_file_name(kind, file_name)?;
        let fid = parse_id(file_id).ok_or_else(invalid_file_id)?;
        let file = self.files(&[fid]).await?.into_iter().find(|f| f.id == fid).ok_or_else(not_found)?;
        let m = self.mod_info(file.mod_id).await?;
        ensure_kind(&m, kind)?;
        if m.download_url(&file).is_none() {
            // Gesperrt: vormerken, damit der Nutzer sie selbst laden kann.
            pack::remember_blocked(paths, &instance.id, &[BlockedFile::new(&m, &file, kind, Some(file_name))]).await?;
            return Err(blocked_error(&m));
        }
        let installed = self.install_file(paths, instance, kind, &m, &file, Some(file_name), false).await?;
        let name = installed.file_name.clone();
        record_installed(paths, &instance.id, &[installed]).await;
        Ok(name)
    }

    /// Neuere Dateien im Update-Kanal – über die Datei-Indizes eines einzigen
    /// Sammelabrufs statt einer Anfrage je Mod.
    pub async fn check_updates(&self, paths: &Paths, instance: &Instance) -> Result<Vec<UpdateInfo>> {
        let items = curseforge_items(paths, instance).await?;
        if items.is_empty() {
            return Ok(Vec::new());
        }
        let mut ids: Vec<u64> = items.iter().filter_map(|(_, s)| parse_id(&s.project_id)).collect();
        ids.sort_unstable();
        ids.dedup();
        let mods = mods_by_id(self, &ids).await?;
        let channel = instance.overrides.channel();
        let mods_allowed = !modrinth::loader_tags(modrinth::content_loader(instance)).is_empty();

        let mut updates = Vec::new();
        for (item, source) in items {
            if item.kind == ContentKind::Mod && !mods_allowed {
                continue;
            }
            let (Some(pid), Some(installed)) = (parse_id(&source.project_id), parse_id(&source.version_id)) else { continue };
            let Some(m) = mods.get(&pid) else { continue };
            if let Some(ix) = newest_index(m, instance, item.kind, |t| channel.allows(t))
                && ix.file_id > installed
            {
                updates.push(UpdateInfo {
                    platform: Platform::CurseForge,
                    kind: item.kind,
                    file_name: item.file_name,
                    project_id: pid.to_string(),
                    version_id: ix.file_id.to_string(),
                    version_number: index_label(ix),
                    compat_with: None,
                });
            }
        }
        updates.sort_by_key(|u| u.file_name.to_lowercase());
        Ok(updates)
    }

    /// Änderungen zwischen der installierten und der neuesten passenden Datei.
    pub async fn changelog_since(
        &self,
        instance: &Instance,
        project_id: &str,
        kind: ContentKind,
        installed_file_id: &str,
    ) -> Result<Vec<VersionSummary>> {
        let id = parse_id(project_id).ok_or_else(invalid_project_id)?;
        let installed = parse_id(installed_file_id).ok_or_else(invalid_file_id)?;
        let channel = instance.overrides.channel();
        let mut files = self.compatible_files(id, kind, instance).await?;
        files.retain(|f| f.id == installed || channel.allows(f.release_type()));
        let newer: Vec<RawFile> = match files.iter().position(|f| f.id == installed) {
            Some(pos) => files.into_iter().take(pos).collect(),
            // Installierte Datei passt nicht mehr zur Instanz: nach ID (steigt mit der Zeit).
            None => files.into_iter().filter(|f| f.id > installed).collect(),
        };
        let mut out = Vec::new();
        for file in newer.into_iter().take(MAX_CHANGELOG_VERSIONS) {
            let file_id = file.id.to_string();
            let Some(mut summary) = summarize(file, kind == ContentKind::Mod) else { continue };
            summary.changelog = self.changelog(project_id, &file_id).await.ok().filter(|c| !c.trim().is_empty());
            out.push(summary);
        }
        Ok(out)
    }

    /// Lädt fehlende oder veraltete Projekt-Infos (Titel, Autor, Icon) der
    /// CurseForge-Inhalte. `true`, wenn sich etwas geändert hat.
    pub async fn refresh_metadata(&self, paths: &Paths, instance: &Instance) -> Result<bool> {
        let index = content::read_index(paths, &instance.id).await;
        let stale = Utc::now() - Duration::days(META_MAX_AGE_DAYS);
        let mut ids: Vec<u64> = index
            .files
            .values()
            .filter(|s| s.platform == Platform::CurseForge)
            .filter(|s| index.projects.get(&s.project_key()).is_none_or(|p| p.fetched_at < stale))
            .filter_map(|s| parse_id(&s.project_id))
            .collect();
        ids.sort_unstable();
        ids.dedup();
        if ids.is_empty() {
            return Ok(false);
        }
        let mods = self.mods(&ids).await?;
        content::modify_index(paths, &instance.id, |index| {
            for m in &mods {
                index.projects.insert(cf_key(m.id), meta_of(m));
            }
        })
        .await?;
        Ok(!mods.is_empty())
    }

    /// Nach einem Versionswechsel: passt die CurseForge-Datei noch, gibt es
    /// eine passende, oder keine?
    pub async fn plan_migration(&self, paths: &Paths, instance: &Instance) -> Result<Vec<MigrationItem>> {
        let items = curseforge_items(paths, instance).await?;
        if items.is_empty() {
            return Ok(Vec::new());
        }
        let mods_allowed = !modrinth::loader_tags(modrinth::content_loader(instance)).is_empty();
        let mut ids: Vec<u64> = items.iter().filter_map(|(_, s)| parse_id(&s.project_id)).collect();
        ids.sort_unstable();
        ids.dedup();
        let mods = mods_by_id(self, &ids).await?;
        let file_ids: Vec<u64> = items.iter().filter_map(|(_, s)| parse_id(&s.version_id)).collect();
        let files: HashMap<u64, RawFile> = self.files(&file_ids).await?.into_iter().map(|f| (f.id, f)).collect();

        let mut plan = Vec::new();
        for (item, source) in items {
            if item.kind == ContentKind::Mod && !mods_allowed {
                continue;
            }
            let Some(pid) = parse_id(&source.project_id) else { continue };
            let installed = parse_id(&source.version_id).and_then(|id| files.get(&id));
            let target = mods.get(&pid).and_then(|m| newest_index(m, instance, item.kind, |_| true));
            let (status, target) = if installed.is_some_and(|f| f.fits(instance, item.kind)) {
                (MigrationStatus::Compatible, None)
            } else if let Some(ix) = target {
                (MigrationStatus::Update, Some(ix))
            } else {
                (MigrationStatus::Missing, None)
            };
            plan.push(MigrationItem {
                platform: Platform::CurseForge,
                kind: item.kind,
                title: item.title.clone().unwrap_or_else(|| item.file_name.clone()),
                icon_url: item.icon_url.clone().filter(|u| crate::icon::is_allowed_icon_url(u)),
                file_name: item.file_name,
                project_id: pid.to_string(),
                current_version: item.version.or(source.version_number),
                status,
                target_version_id: target.map(|ix| ix.file_id.to_string()),
                target_version_number: target.map(index_label),
            });
        }
        Ok(plan)
    }
}

/// Projekt-Infos speichern und den Verlauf schreiben.
pub(super) async fn record_installed(paths: &Paths, instance_id: &str, installed: &[Installed]) {
    if installed.is_empty() {
        return;
    }
    let stored = content::modify_index(paths, instance_id, |index| {
        for item in installed {
            index.projects.insert(item.key.clone(), item.meta.clone());
        }
    })
    .await;
    if let Err(e) = stored {
        tracing::debug!("CurseForge-Projekt-Infos konnten nicht gespeichert werden: {e}");
    }
    for item in installed {
        let entry = match &item.previous {
            Some(prev) => {
                let mut e = HistoryEntry::new(HistoryKind::ModUpdated).subject(&item.meta.title).to(&item.version_number);
                if let Some(from) = &prev.version_number {
                    e = e.from(from);
                }
                // CurseForge-Datei-IDs steigen mit der Zeit: kleinere ID = ältere Datei.
                let older = prev.platform == Platform::CurseForge
                    && parse_id(&prev.version_id).is_some_and(|old| old > item.file_id);
                if older { e.detail("downgrade") } else { e }
            }
            None => {
                let e = HistoryEntry::new(HistoryKind::ModInstalled).subject(&item.meta.title).to(&item.version_number);
                if item.dependency { e.detail("dependency") } else { e }
            }
        };
        history::record(paths, instance_id, entry).await;
    }
}
