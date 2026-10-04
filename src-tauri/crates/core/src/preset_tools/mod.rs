//! Werkzeuge für den Preset-Editor:
//!
//! - „Aus Modpack übernehmen“: Inhalte einer eigenen Instanz, eines Modrinth-/
//!   CurseForge-Modpacks (nur Index bzw. `manifest.json` lesen, nichts
//!   installieren) oder eines TRS-Pack-Codes als Auswahlliste.
//! - Prüfen: welche Pflicht-Abhängigkeiten ein Eintrag mitbringt (versionsunabhängig,
//!   je Loader der neuesten Version) und welche Einträge sich bekanntermaßen beißen.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};

use futures::StreamExt;
use serde::{Deserialize, Serialize};

use crate::content::{self, ContentKind, Platform};
use crate::curseforge::{CurseForge, RawMod, content_kind_of_class, loader_tag_of};
use crate::icon::is_allowed_icon_url;
use crate::instance::LoaderKind;
use crate::modrinth;
use crate::presets::{MAX_ITEMS, PresetItem, PresetSource, simple_name};
use crate::trs_choice::{self, ConflictKind, ModHint};
use crate::{Error, Launcher, Result};

#[cfg(test)]
mod tests;

/// So viele Einträge zeigt die Auswahl höchstens (große Packs haben ein paar Hundert).
pub const MAX_PICK_ITEMS: usize = 1500;
const MAX_TITLE_CHARS: usize = 100;
const MAX_CATEGORIES: usize = 6;
/// Gleichzeitige Anfragen beim Nachschlagen der Abhängigkeiten.
const LOOKUP_CONCURRENCY: usize = 6;
/// Abhängigkeiten eines Projekts so lange merken.
const DEPS_CACHE_SECS: u64 = 30 * 60;
const DEPS_CACHE_MAX: usize = 400;
/// Loader, für die Abhängigkeiten gezeigt werden (Modrinth-Schreibweise).
const LOADERS: [&str; 4] = ["fabric", "quilt", "forge", "neoforge"];

// --- Auswahlliste ------------------------------------------------------------------

/// Ein Inhalt, der sich ins Preset übernehmen lässt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PickItem {
    /// `None`: Datei ohne bekanntes Projekt – wird angezeigt, lässt sich aber nicht übernehmen.
    pub source: Option<PresetSource>,
    pub project_id: Option<String>,
    pub title: String,
    pub icon_url: Option<String>,
    pub kind: ContentKind,
    /// Modrinth-Kategorien (Slugs) bzw. CurseForge-Kategorienamen.
    pub categories: Vec<String>,
    /// Optimierungs-Mod (Kategorie „optimization“ bzw. „Performance“).
    pub performance: bool,
    /// Nur im Client nötig (der Server braucht es nicht); `None` = unbekannt.
    pub client_only: Option<bool>,
    pub file_name: Option<String>,
}

impl PickItem {
    fn file(file_name: &str, kind: ContentKind) -> Self {
        Self {
            source: None,
            project_id: None,
            title: title_of_file(file_name),
            icon_url: None,
            kind,
            categories: Vec::new(),
            performance: false,
            client_only: None,
            file_name: Some(clip(file_name, 200)),
        }
    }
}

/// Inhalt einer Quelle für „Aus Modpack übernehmen“.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PickList {
    pub name: String,
    pub game_version: Option<String>,
    pub loader: Option<LoaderKind>,
    pub items: Vec<PickItem>,
}

fn clip(text: &str, max: usize) -> String {
    text.chars().filter(|c| !c.is_control()).take(max).collect::<String>().trim().to_owned()
}

/// Lesbarer Titel aus einem Dateinamen („sodium-fabric-0.6.0+mc1.21.jar“ → „sodium-fabric-0.6.0+mc1.21“).
fn title_of_file(file_name: &str) -> String {
    let name = file_name.strip_suffix(".disabled").unwrap_or(file_name);
    let base = [".jar", ".zip"].iter().find_map(|ext| name.strip_suffix(ext)).unwrap_or(name);
    let title = clip(base, MAX_TITLE_CHARS);
    if title.is_empty() { clip(file_name, MAX_TITLE_CHARS) } else { title }
}

/// Doppelte Projekte (gleiche Quelle + ID) nur einmal, Reihenfolge bleibt.
fn dedupe(items: Vec<PickItem>) -> Vec<PickItem> {
    let mut seen = HashSet::new();
    items
        .into_iter()
        .filter(|i| match (&i.source, &i.project_id) {
            (Some(s), Some(id)) => seen.insert((*s, id.clone())),
            _ => true,
        })
        .take(MAX_PICK_ITEMS)
        .collect()
}

/// Einträge eines `.mrpack` (aus [`crate::modpack::PackIndex::picks`]).
pub(crate) fn picks_of_pack(picks: Vec<crate::modpack::PackPick>) -> Vec<PickItem> {
    picks
        .into_iter()
        .map(|p| {
            let mut item = PickItem::file(&p.file_name, p.kind);
            item.client_only = p.client_only;
            if let Some(id) = p.modrinth {
                item.source = Some(PresetSource::Modrinth);
                item.project_id = Some(id);
            }
            item
        })
        .collect()
}

/// Einträge einer CurseForge-`manifest.json`: Projekte mit ihren Angaben (Titel,
/// Art, Kategorien); unbekannte Projekte bleiben als Mod ohne Titel stehen,
/// Modpacks/Welten im Pack fallen weg.
pub(crate) fn picks_of_manifest(project_ids: &[u64], mods: &HashMap<u64, RawMod>) -> Vec<PickItem> {
    project_ids
        .iter()
        .filter_map(|id| {
            let m = mods.get(id);
            let kind = match m.and_then(|m| m.class_id) {
                Some(class) => content_kind_of_class(class)?,
                None => ContentKind::Mod,
            };
            let mut item = PickItem {
                source: Some(PresetSource::Curseforge),
                project_id: Some(id.to_string()),
                title: format!("CurseForge #{id}"),
                ..PickItem::file("", kind)
            };
            item.file_name = None;
            if let Some(m) = m {
                apply_curseforge(&mut item, m);
            }
            Some(item)
        })
        .collect()
}

fn apply_curseforge(item: &mut PickItem, m: &RawMod) {
    let title = clip(&m.name, MAX_TITLE_CHARS);
    if !title.is_empty() {
        item.title = title;
    }
    item.icon_url = m.icon_url();
    item.categories =
        m.categories.iter().filter(|c| c.is_class != Some(true)).map(|c| clip(&c.name, 40)).take(MAX_CATEGORIES).collect();
    item.performance = m.categories.iter().any(|c| c.name.to_lowercase().contains("performance"));
}

/// Was Modrinth über ein Projekt sagt (aus `GET /projects?ids=`).
#[derive(Debug, Clone, Deserialize)]
pub(crate) struct ModrinthMeta {
    pub id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub categories: Vec<String>,
    #[serde(default)]
    pub client_side: String,
    #[serde(default)]
    pub server_side: String,
}

fn apply_modrinth(item: &mut PickItem, meta: &ModrinthMeta) {
    let title = clip(&meta.title, MAX_TITLE_CHARS);
    if !title.is_empty() {
        item.title = title;
    }
    item.icon_url = meta.icon_url.clone().filter(|u| is_allowed_icon_url(u)).or_else(|| item.icon_url.take());
    item.categories = meta.categories.iter().map(|c| clip(c, 40)).take(MAX_CATEGORIES).collect();
    item.performance = meta.categories.iter().any(|c| c == "optimization");
    if item.client_only.is_none() && !meta.server_side.is_empty() {
        item.client_only = Some(meta.server_side == "unsupported" && meta.client_side != "unsupported");
    }
}

/// Projekt-Angaben von Modrinth in Stapeln; Fehler → leer (die Liste bleibt nutzbar).
pub(crate) async fn modrinth_meta(http: &reqwest::Client, ids: &[String]) -> HashMap<String, ModrinthMeta> {
    let ids: Vec<&String> = ids.iter().filter(|id| modrinth::is_safe_project_id(id)).collect::<HashSet<_>>().into_iter().collect();
    let mut out = HashMap::new();
    for chunk in ids.chunks(100) {
        let Ok(list) = serde_json::to_string(chunk) else { continue };
        let result: Result<Vec<ModrinthMeta>> = async {
            Ok(http.get(format!("{}/projects", modrinth::API)).query(&[("ids", list)]).send().await?.error_for_status()?.json().await?)
        }
        .await;
        match result {
            Ok(metas) => out.extend(metas.into_iter().map(|m| (m.id.clone(), m))),
            Err(e) => tracing::debug!("Modrinth-Projektangaben nicht geladen: {e}"),
        }
    }
    out
}

/// Ergänzt Titel, Symbole, Kategorien und Seite von Modrinth bzw. CurseForge.
async fn enrich(http: &reqwest::Client, curseforge: Option<&CurseForge>, items: &mut [PickItem]) {
    let modrinth_ids: Vec<String> = items
        .iter()
        .filter(|i| i.source == Some(PresetSource::Modrinth))
        .filter_map(|i| i.project_id.clone())
        .collect();
    if !modrinth_ids.is_empty() {
        let metas = modrinth_meta(http, &modrinth_ids).await;
        for item in items.iter_mut().filter(|i| i.source == Some(PresetSource::Modrinth)) {
            if let Some(meta) = item.project_id.as_ref().and_then(|id| metas.get(id)) {
                apply_modrinth(item, meta);
            }
        }
    }
    let cf_ids: Vec<u64> = items
        .iter()
        .filter(|i| i.source == Some(PresetSource::Curseforge))
        .filter_map(|i| i.project_id.as_deref()?.parse().ok())
        .collect();
    if let (Some(cf), false) = (curseforge, cf_ids.is_empty()) {
        match cf.mods(&cf_ids).await {
            Ok(mods) => {
                let by_id: HashMap<u64, RawMod> = mods.into_iter().map(|m| (m.id, m)).collect();
                for item in items.iter_mut().filter(|i| i.source == Some(PresetSource::Curseforge)) {
                    if let Some(m) = item.project_id.as_deref().and_then(|id| id.parse::<u64>().ok()).and_then(|id| by_id.get(&id)) {
                        apply_curseforge(item, m);
                    }
                }
            }
            Err(e) => tracing::debug!("CurseForge-Projektangaben nicht geladen: {e}"),
        }
    }
}

/// Inhalte einer Instanz: Projekt-IDs aus den gespeicherten Herkunftsangaben.
fn picks_of_content(list: Vec<content::ContentItem>) -> Vec<PickItem> {
    list.into_iter()
        .map(|c| {
            let mut item = PickItem::file(&c.file_name, c.kind);
            if let Some(title) = c.title.as_deref().map(|t| clip(t, MAX_TITLE_CHARS)).filter(|t| !t.is_empty()) {
                item.title = title;
            }
            item.icon_url = c.icon_url.filter(|u| is_allowed_icon_url(u));
            if let Some(source) = c.source.filter(|s| !s.project_id.is_empty()) {
                let preset_source = match source.platform {
                    Platform::Modrinth => PresetSource::Modrinth,
                    Platform::CurseForge => PresetSource::Curseforge,
                };
                if preset_source.is_valid_id(&source.project_id) {
                    item.source = Some(preset_source);
                    item.project_id = Some(source.project_id);
                }
            }
            item
        })
        .collect()
}

impl Launcher {
    /// Mods, Ressourcen- und Shaderpakete einer eigenen Instanz.
    pub async fn preset_pick_instance(&self, instance_id: &str) -> Result<PickList> {
        let instance = self.instances().get(instance_id).await?;
        let mut items = Vec::new();
        for kind in [ContentKind::Mod, ContentKind::ResourcePack, ContentKind::ShaderPack] {
            items.extend(picks_of_content(content::list(self.paths(), &instance.id, kind).await?));
        }
        let mut items = dedupe(items);
        enrich(self.http(), self.curseforge().ok(), &mut items).await;
        Ok(PickList {
            name: instance.name.clone(),
            game_version: Some(instance.game_version.clone()),
            loader: Some(instance.loader.kind),
            items,
        })
    }

    /// Inhalt der neuesten Version eines Modrinth- oder CurseForge-Modpacks –
    /// gelesen wird nur der Index, installiert wird nichts.
    pub async fn preset_pick_modpack(&self, platform: Platform, project_id: &str) -> Result<PickList> {
        let mut list = match platform {
            Platform::Modrinth => {
                let (_, task) = self.modrinth_pack_task(project_id, None).await?;
                crate::download::fetch_all(self.http(), vec![task.clone()], 1, &|_| {}).await?;
                read_mrpack(task.path).await?
            }
            Platform::CurseForge => {
                let cf = self.curseforge()?;
                let (_, _, task) = self.curseforge_pack_task(cf, project_id, None).await?;
                crate::download::fetch_all(cf.download_client(), vec![task.clone()], 1, &|_| {}).await?;
                let path = task.path.clone();
                let manifest = tokio::task::spawn_blocking(move || crate::curseforge::read_manifest(&path))
                    .await
                    .map_err(|e| Error::Internal(e.to_string()))??;
                let ids: Vec<u64> = manifest.files.iter().map(|f| f.project_id).filter(|id| *id > 0).take(MAX_PICK_ITEMS).collect();
                let mods: HashMap<u64, RawMod> = cf.mods(&ids).await?.into_iter().map(|m| (m.id, m)).collect();
                let loader = manifest.loader().ok().map(|l| l.kind);
                PickList {
                    name: clip(&manifest.name, 64),
                    game_version: Some(clip(&manifest.minecraft.version, 32)).filter(|v| !v.is_empty()),
                    loader,
                    items: dedupe(picks_of_manifest(&ids, &mods)),
                }
            }
        };
        if platform == Platform::Modrinth {
            enrich(self.http(), None, &mut list.items).await;
        }
        Ok(list)
    }

    /// Inhalt eines mit TRS geteilten Packs (Code).
    pub async fn preset_pick_pack_code(&self, code: &str) -> Result<PickList> {
        let (path, pack) = self.fetch_pack(code, &|_, _| {}).await?;
        let mut list = read_mrpack(path).await?;
        list.name = clip(&pack.name, 64);
        enrich(self.http(), None, &mut list.items).await;
        Ok(list)
    }

    /// Pflicht-Abhängigkeiten und bekannte Konflikte der Einträge eines Presets.
    pub async fn preset_check(&self, items: &[PresetItem]) -> Result<PresetCheck> {
        if items.len() > MAX_ITEMS {
            return Err(Error::validation(crate::msg!(
                "presets.tooManyItems",
                "Ein Preset kann höchstens {max} Einträge haben.",
                max = MAX_ITEMS
            )));
        }
        let items: Vec<&PresetItem> = items.iter().filter(|i| i.source.is_valid_id(&i.project_id)).collect();
        let mods: Vec<&PresetItem> = items.iter().copied().filter(|i| i.kind == ContentKind::Mod).collect();
        let mut infos: HashMap<(PresetSource, String), ProjectDeps> = HashMap::new();
        let modrinth_ids: Vec<String> =
            mods.iter().filter(|i| i.source == PresetSource::Modrinth).map(|i| i.project_id.clone()).collect();
        for (id, deps) in modrinth_deps(self.http(), &modrinth_ids).await {
            infos.insert((PresetSource::Modrinth, id), deps);
        }
        let cf_ids: Vec<u64> =
            mods.iter().filter(|i| i.source == PresetSource::Curseforge).filter_map(|i| i.project_id.parse().ok()).collect();
        if let (Ok(cf), false) = (self.curseforge(), cf_ids.is_empty()) {
            for (id, deps) in curseforge_deps(cf, &cf_ids).await {
                infos.insert((PresetSource::Curseforge, id.to_string()), deps);
            }
        }
        // Titel + Symbole der Abhängigkeiten.
        let dep_mr: Vec<String> = infos
            .iter()
            .filter(|((s, _), _)| *s == PresetSource::Modrinth)
            .flat_map(|(_, d)| d.required.keys().cloned())
            .collect();
        let mr_meta = modrinth_meta(self.http(), &dep_mr).await;
        let dep_cf: Vec<u64> = infos
            .iter()
            .filter(|((s, _), _)| *s == PresetSource::Curseforge)
            .flat_map(|(_, d)| d.required.keys().filter_map(|k| k.parse().ok()))
            .collect::<HashSet<u64>>()
            .into_iter()
            .collect();
        let cf_meta: HashMap<u64, RawMod> = match (self.curseforge(), dep_cf.is_empty()) {
            (Ok(cf), false) => cf.mods(&dep_cf).await.map(|m| m.into_iter().map(|m| (m.id, m)).collect()).unwrap_or_default(),
            _ => HashMap::new(),
        };
        let deps = items
            .iter()
            .filter_map(|item| {
                let info = infos.get(&(item.source, item.project_id.clone()))?;
                let deps: Vec<DepRef> = info
                    .required
                    .iter()
                    .map(|(id, loaders)| {
                        let (title, icon_url) = match item.source {
                            PresetSource::Modrinth => mr_meta
                                .get(id)
                                .map(|m| (clip(&m.title, MAX_TITLE_CHARS), m.icon_url.clone().filter(|u| is_allowed_icon_url(u))))
                                .unwrap_or_else(|| (id.clone(), None)),
                            PresetSource::Curseforge => id
                                .parse::<u64>()
                                .ok()
                                .and_then(|n| cf_meta.get(&n))
                                .map(|m| (m.title(), m.icon_url()))
                                .unwrap_or_else(|| (id.clone(), None)),
                        };
                        DepRef { source: item.source, project_id: id.clone(), title, icon_url, loaders: loaders.clone() }
                    })
                    .collect();
                (!deps.is_empty()).then(|| ItemDeps { source: item.source, project_id: item.project_id.clone(), deps })
            })
            .collect();
        Ok(PresetCheck { deps, conflicts: conflicts(&items, &infos) })
    }
}

/// Liest eine `.mrpack` aus dem Zwischenspeicher (blockierend im Hintergrund).
async fn read_mrpack(path: std::path::PathBuf) -> Result<PickList> {
    let (index, bundled) = tokio::task::spawn_blocking(move || crate::modpack::read_index_and_mods(&path))
        .await
        .map_err(|e| Error::Internal(e.to_string()))??;
    Ok(PickList {
        name: clip(index.name(), 64),
        game_version: index.game_version().ok().map(|v| clip(v, 32)),
        loader: index.loader().ok().map(|l| l.kind),
        items: dedupe(picks_of_pack(index.picks(bundled))),
    })
}

// --- Abhängigkeiten ----------------------------------------------------------------

/// Eine Pflicht-Abhängigkeit, die beim Installieren automatisch mitkommt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DepRef {
    pub source: PresetSource,
    pub project_id: String,
    pub title: String,
    pub icon_url: Option<String>,
    /// Nur bei diesen Loadern nötig; leer = bei allen.
    pub loaders: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ItemDeps {
    pub source: PresetSource,
    pub project_id: String,
    pub deps: Vec<DepRef>,
}

/// Was die neuesten Versionen eines Projekts verlangen bzw. ausschließen.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct ProjectDeps {
    /// Abhängigkeit → Loader, bei denen sie nötig ist (leer = bei allen, die das Projekt kann).
    pub required: BTreeMap<String, Vec<String>>,
    pub incompatible: HashSet<String>,
}

/// Eine Version, verkürzt auf das, was für Abhängigkeiten zählt.
#[derive(Debug, Clone, Deserialize)]
pub(crate) struct SlimVersion {
    #[serde(default)]
    pub loaders: Vec<String>,
    #[serde(default)]
    pub version_type: String,
    #[serde(default)]
    pub dependencies: Vec<SlimDependency>,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct SlimDependency {
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub dependency_type: String,
}

/// Je Loader die neueste Version (Release vor Beta), daraus die Pflicht-Abhängigkeiten.
/// Versionen kommen neueste zuerst (wie von Modrinth).
pub(crate) fn deps_by_loader(versions: &[SlimVersion]) -> ProjectDeps {
    let mut per_dep: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut incompatible = HashSet::new();
    let mut supported: Vec<String> = Vec::new();
    for loader in LOADERS {
        let fits = |v: &&SlimVersion| v.loaders.iter().any(|l| l == loader);
        let Some(newest) = versions.iter().filter(fits).find(|v| v.version_type == "release").or_else(|| versions.iter().find(fits))
        else {
            continue;
        };
        supported.push(loader.to_owned());
        for dep in &newest.dependencies {
            let Some(id) = dep.project_id.as_deref().filter(|id| modrinth::is_safe_project_id(id)) else { continue };
            match dep.dependency_type.as_str() {
                "required" => per_dep.entry(id.to_owned()).or_default().push(loader.to_owned()),
                "incompatible" => {
                    incompatible.insert(id.to_owned());
                }
                _ => {}
            }
        }
    }
    let required = per_dep
        .into_iter()
        .map(|(id, loaders)| {
            let all = supported.iter().all(|l| loaders.contains(l));
            (id, if all { Vec::new() } else { loaders })
        })
        .collect();
    ProjectDeps { required, incompatible }
}

type DepsCache = std::sync::Mutex<HashMap<String, (Instant, Arc<ProjectDeps>)>>;
static DEPS_CACHE: LazyLock<DepsCache> = LazyLock::new(DepsCache::default);

fn cached(key: &str) -> Option<Arc<ProjectDeps>> {
    let cache = DEPS_CACHE.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    cache.get(key).filter(|(at, _)| at.elapsed() < Duration::from_secs(DEPS_CACHE_SECS)).map(|(_, d)| d.clone())
}

fn remember(key: String, deps: Arc<ProjectDeps>) {
    let mut cache = DEPS_CACHE.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if cache.len() >= DEPS_CACHE_MAX {
        cache.retain(|_, (at, _)| at.elapsed() < Duration::from_secs(DEPS_CACHE_SECS));
        if cache.len() >= DEPS_CACHE_MAX {
            cache.clear();
        }
    }
    cache.insert(key, (Instant::now(), deps));
}

async fn modrinth_deps(http: &reqwest::Client, ids: &[String]) -> Vec<(String, ProjectDeps)> {
    futures::stream::iter(ids.iter().filter(|id| modrinth::is_safe_project_id(id)).cloned().collect::<HashSet<_>>())
        .map(|id| async move {
            let key = format!("mr:{id}");
            if let Some(deps) = cached(&key) {
                return Some((id, (*deps).clone()));
            }
            let result: Result<Vec<SlimVersion>> = async {
                Ok(http
                    .get(format!("{}/project/{id}/version", modrinth::API))
                    .query(&[("include_changelog", "false")])
                    .send()
                    .await?
                    .error_for_status()?
                    .json()
                    .await?)
            }
            .await;
            match result {
                Ok(versions) => {
                    let deps = deps_by_loader(&versions);
                    remember(key, Arc::new(deps.clone()));
                    Some((id, deps))
                }
                Err(e) => {
                    tracing::debug!("Abhängigkeiten von {id} nicht geladen: {e}");
                    None
                }
            }
        })
        .buffer_unordered(LOOKUP_CONCURRENCY)
        .filter_map(std::future::ready)
        .collect()
        .await
}

/// CurseForge: je Loader die neueste Datei aus den Datei-Indizes, deren Abhängigkeiten
/// (3 = Pflicht, 5 = unverträglich).
async fn curseforge_deps(cf: &CurseForge, ids: &[u64]) -> Vec<(u64, ProjectDeps)> {
    let mut out = Vec::new();
    let mut missing = Vec::new();
    for id in ids {
        match cached(&format!("cf:{id}")) {
            Some(deps) => out.push((*id, (*deps).clone())),
            None => missing.push(*id),
        }
    }
    if missing.is_empty() {
        return out;
    }
    let Ok(mods) = cf.mods(&missing).await else { return out };
    // Datei-ID → (Projekt, Loader)
    let mut files: HashMap<u64, (u64, &'static str)> = HashMap::new();
    for m in &mods {
        for loader in LOADERS {
            let newest = m
                .latest_files_indexes
                .iter()
                .filter(|i| i.file_id > 0 && i.mod_loader.and_then(loader_tag_of) == Some(loader))
                .max_by_key(|i| i.file_id);
            if let Some(ix) = newest {
                files.insert(ix.file_id, (m.id, loader));
            }
        }
    }
    let ids: Vec<u64> = files.keys().copied().collect();
    let Ok(raw) = cf.files(&ids).await else { return out };
    let mut per_mod: HashMap<u64, Vec<SlimVersion>> = HashMap::new();
    for file in raw {
        let Some((mod_id, loader)) = files.get(&file.id) else { continue };
        let dependencies = file
            .dependencies
            .iter()
            .filter(|d| d.mod_id > 0)
            .map(|d| SlimDependency {
                project_id: Some(d.mod_id.to_string()),
                dependency_type: match d.relation_type {
                    3 => "required",
                    5 => "incompatible",
                    _ => "other",
                }
                .to_owned(),
            })
            .collect();
        per_mod.entry(*mod_id).or_default().push(SlimVersion {
            loaders: vec![(*loader).to_owned()],
            version_type: "release".into(),
            dependencies,
        });
    }
    for m in &mods {
        let deps = deps_by_loader(per_mod.get(&m.id).map(Vec::as_slice).unwrap_or_default());
        remember(format!("cf:{}", m.id), Arc::new(deps.clone()));
        out.push((m.id, deps));
    }
    out
}

// --- Bekannte Konflikte --------------------------------------------------------------

/// Warum zwei Einträge nicht zusammenpassen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum ConflictReason {
    /// Zwei Render-Mods (Sodium, Embeddium, OptiFine …).
    Renderer,
    /// Zwei Shader-Lader (Iris, Oculus, OptiFine).
    Shaders,
    /// Zwei Minimaps.
    Minimap,
    /// Zwei Zoom-Mods (gleiche Taste).
    Zoom,
    /// Nvidium ersetzt den Gelände-Renderer – keine Shader.
    Nvidium,
    /// Das Projekt selbst nennt den anderen Eintrag „unverträglich“.
    Declared,
    /// Verträgt sich nicht mit dem TRS Client (z. B. Essential).
    TrsClient,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ItemRef {
    pub source: PresetSource,
    pub project_id: String,
    pub title: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct KnownConflict {
    pub a: ItemRef,
    /// `None` bei [`ConflictReason::TrsClient`].
    pub b: Option<ItemRef>,
    pub reason: ConflictReason,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PresetCheck {
    pub deps: Vec<ItemDeps>,
    pub conflicts: Vec<KnownConflict>,
}

/// Eine bekannte Mod: Modrinth-IDs, CurseForge-IDs und vereinfachte Namen.
struct KnownMod {
    key: &'static str,
    modrinth: &'static [&'static str],
    curseforge: &'static [u64],
    names: &'static [&'static str],
}

const fn km(key: &'static str, modrinth: &'static [&'static str], curseforge: &'static [u64], names: &'static [&'static str]) -> KnownMod {
    KnownMod { key, modrinth, curseforge, names }
}

/// IDs am 2026-10-04 geprüft (Modrinth-API); CurseForge-IDs wie in `trs_choice`.
const KNOWN: &[KnownMod] = &[
    km("sodium", &["AANobbMI"], &[394_468], &["sodium"]),
    km("embeddium", &["sk9rgfiA"], &[908_741], &["embeddium"]),
    km("rubidium", &[], &[574_856], &["rubidium"]),
    km("magnesium", &[], &[], &["magnesium", "magnesiumextras"]),
    km("celeritas", &[], &[], &["celeritas"]),
    km("optifine", &[], &[], &["optifine", "optifabric"]),
    km("iris", &["YL57xq9U"], &[455_508], &["iris", "irisshaders"]),
    km("oculus", &["GchcoXML"], &[581_495], &["oculus"]),
    km("nvidium", &["SfMw2IZN"], &[], &["nvidium"]),
    km("xaerominimap", &["1bokaNcj"], &[263_420], &["xaerosminimap"]),
    km("xaerominimapfair", &["JkSi2Fzx"], &[], &["xaerosminimapfairplay", "xaerosminimapfairplayedition"]),
    km("journeymap", &["lfHFW1mp"], &[32_274], &["journeymap"]),
    km("voxelmap", &["wkzK5379"], &[], &["voxelmap", "voxelmapupdated"]),
    km("ftbchunks", &[], &[314_906, 472_657], &["ftbchunks"]),
    km("okzoomer", &["aXf2OSFU"], &[354_047], &["okzoomer", "okzoomeritszoom"]),
    km("zoomify", &["w7ThoJFB"], &[574_741], &["zoomify"]),
    km("logicalzoom", &["8bOImuGU"], &[], &["logicalzoom"]),
];

/// Gruppen, deren Mitglieder sich gegenseitig ausschließen.
const GROUPS: &[(ConflictReason, &[&str])] = &[
    (ConflictReason::Renderer, &["sodium", "embeddium", "rubidium", "magnesium", "celeritas", "optifine"]),
    (ConflictReason::Shaders, &["iris", "oculus", "optifine"]),
    (ConflictReason::Nvidium, &["nvidium", "iris"]),
    (ConflictReason::Minimap, &["xaerominimap", "xaerominimapfair", "journeymap", "voxelmap", "ftbchunks"]),
    (ConflictReason::Zoom, &["okzoomer", "zoomify", "logicalzoom"]),
];

fn known_key(item: &PresetItem) -> Option<&'static str> {
    let name = simple_name(&item.title);
    KNOWN
        .iter()
        .find(|k| match item.source {
            PresetSource::Modrinth => k.modrinth.contains(&item.project_id.as_str()),
            PresetSource::Curseforge => item.project_id.parse::<u64>().is_ok_and(|id| k.curseforge.contains(&id)),
        })
        .or_else(|| KNOWN.iter().find(|k| k.names.contains(&name.as_str())))
        .map(|k| k.key)
}

fn item_ref(item: &PresetItem) -> ItemRef {
    ItemRef { source: item.source, project_id: item.project_id.clone(), title: item.title.clone() }
}

/// Bekannte Paare, vom Projekt selbst erklärte Unverträglichkeiten und Mods,
/// die sich nicht mit dem TRS Client vertragen.
pub(crate) fn conflicts(items: &[&PresetItem], infos: &HashMap<(PresetSource, String), ProjectDeps>) -> Vec<KnownConflict> {
    let mods: Vec<&PresetItem> = items.iter().copied().filter(|i| i.kind == ContentKind::Mod).collect();
    let keys: Vec<Option<&str>> = mods.iter().map(|i| known_key(i)).collect();
    let mut out: Vec<KnownConflict> = Vec::new();
    let mut seen: HashSet<(usize, usize)> = HashSet::new();
    for a in 0..mods.len() {
        for b in a + 1..mods.len() {
            let reason = match (keys[a], keys[b]) {
                (Some(ka), Some(kb)) if ka != kb => {
                    GROUPS.iter().find(|(_, members)| members.contains(&ka) && members.contains(&kb)).map(|(r, _)| *r)
                }
                _ => None,
            }
            .or_else(|| {
                let declares = |x: &PresetItem, y: &PresetItem| {
                    x.source == y.source
                        && infos.get(&(x.source, x.project_id.clone())).is_some_and(|d| d.incompatible.contains(&y.project_id))
                };
                (declares(mods[a], mods[b]) || declares(mods[b], mods[a])).then_some(ConflictReason::Declared)
            });
            if let Some(reason) = reason
                && seen.insert((a, b))
            {
                out.push(KnownConflict { a: item_ref(mods[a]), b: Some(item_ref(mods[b])), reason });
            }
        }
    }
    // Wissen aus „mit oder ohne TRS Client“: nur die harten Fälle (eigene Client-Mods).
    for item in &mods {
        let hint = ModHint {
            file_name: None,
            modrinth: (item.source == PresetSource::Modrinth).then(|| item.project_id.clone()),
            curseforge: (item.source == PresetSource::Curseforge).then(|| item.project_id.parse().ok()).flatten(),
        };
        if trs_choice::detect(&[hint]).iter().any(|c| c.kind == ConflictKind::ClientMod) {
            out.push(KnownConflict { a: item_ref(item), b: None, reason: ConflictReason::TrsClient });
        }
    }
    out
}
