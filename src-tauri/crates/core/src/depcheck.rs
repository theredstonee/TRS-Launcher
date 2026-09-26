//! Fehlende Pflicht-Abhängigkeiten ergänzen – vor jedem Start (Fabric, auch
//! Vanilla mit TRS-Optimierung) und als Ein-Klick-Lösung der Absturz-Diagnose
//! („Mod 'More Culling' … requires … cloth-config, which is missing!“).
//!
//! Was fehlt, sagen die Jars selbst (`depends`, siehe
//! [`modcompat::missing_dependencies`]). Welches Modrinth-Projekt eine fehlende
//! Mod-ID liefert, steht nirgends direkt. Gesucht wird in den Modrinth-
//! Abhängigkeiten der Mod, die sie verlangt, dann ein Projekt mit der ID als
//! Slug. Übernommen wird nur, was die ID nachweislich liefert (Angaben im Jar)
//! oder was Modrinth für diese Mod ausdrücklich als Pflicht führt. Liegt die
//! Abhängigkeit nur deaktiviert in der Instanz, wird sie eingeschaltet.

use std::collections::HashSet;

use serde::Serialize;

use crate::client_mod::{self, Build};
use crate::content::{self, ContentKind};
use crate::instance::{Instance, LoaderKind, UpdateChannel};
use crate::modcompat::{self, Entry, MissingDep, ModInfo};
use crate::modrinth;
use crate::paths::Paths;
use crate::presets::{self, ApplyProgress, ItemStatus, VersionLookup};
use crate::{Error, Result, task};

/// So viele fehlende Mod-IDs werden je Durchgang höchstens nachgeschlagen.
const MAX_LOOKUPS: usize = 12;
/// So viele Modrinth-Projekte werden je fehlender Mod-ID höchstens angesehen.
const MAX_CANDIDATES: usize = 6;

/// Wodurch eine fehlende Mod-ID geliefert wird.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Provider {
    /// Liegt deaktiviert in der Instanz – einschalten.
    Enable { file_name: String },
    /// Dieses Modrinth-Projekt (samt Abhängigkeiten) laden.
    Install { project_id: String, title: String },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Found {
    pub missing: MissingDep,
    pub provider: Provider,
}

/// Eine deaktivierte Mod der Instanz.
#[derive(Debug, Clone, Default)]
pub(crate) struct DisabledMod {
    pub file_name: String,
    pub project_id: Option<String>,
    pub mods: Vec<ModInfo>,
    pub nested: Vec<String>,
}

impl DisabledMod {
    fn provides(&self, id: &str) -> bool {
        provides(&self.mods, id) || self.nested.iter().any(|n| n == id)
    }
}

fn provides(mods: &[ModInfo], id: &str) -> bool {
    mods.iter().any(|m| m.id == id || m.provides.iter().any(|p| p == id))
}

/// Was ergänzt wurde – für Hinweis und Absturz-Knopf.
#[derive(Debug, Clone, Default, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DependencyFix {
    /// Neu installierte oder wieder eingeschaltete Mods („Cloth Config API“).
    pub added: Vec<String>,
    /// Für diese Mods („More Culling“).
    pub needed_by: Vec<String>,
    /// Mod-IDs, für die sich nichts Passendes fand.
    pub unresolved: Vec<String>,
}

impl DependencyFix {
    pub fn is_empty(&self) -> bool {
        self.added.is_empty()
    }

    fn note_needed_by(&mut self, label: &str) {
        if !label.is_empty() && !self.needed_by.iter().any(|n| n == label) {
            self.needed_by.push(label.to_owned());
        }
    }
}

/// Sucht zu jeder fehlenden Mod-ID, wer sie liefert. `enabled`: eingeschaltete
/// Modrinth-Projekte der Instanz. Liefert Funde und was offen bleibt.
pub(crate) async fn find_providers<L: VersionLookup>(
    lookup: &L,
    channel: UpdateChannel,
    entries: &[Entry],
    missing: &[MissingDep],
    enabled: &HashSet<String>,
    disabled: &[DisabledMod],
) -> Result<(Vec<Found>, Vec<MissingDep>)> {
    let mut found: Vec<Found> = Vec::new();
    // Schon gefundene Projekte samt Jar-Angaben (liefern evtl. weitere IDs mit).
    let mut known: Vec<(Provider, Vec<ModInfo>)> = Vec::new();
    let mut open = Vec::new();
    for (n, m) in missing.iter().enumerate() {
        task::checkpoint().await?;
        // 1. Liegt deaktiviert da.
        if let Some(d) = disabled.iter().find(|d| d.provides(&m.id)) {
            found.push(Found { missing: m.clone(), provider: Provider::Enable { file_name: d.file_name.clone() } });
            continue;
        }
        // 2. Kommt schon mit einem gefundenen Projekt (`cloth-config2` mit Cloth Config).
        if let Some((provider, _)) = known.iter().find(|(_, infos)| provides(infos, &m.id)) {
            found.push(Found { missing: m.clone(), provider: provider.clone() });
            continue;
        }
        if n >= MAX_LOOKUPS {
            open.push(m.clone());
            continue;
        }
        // 3. Auf Modrinth.
        match provider_on_modrinth(lookup, channel, entries.get(m.declarer), &m.id, enabled, disabled, &known).await? {
            Some((provider, infos)) => {
                found.push(Found { missing: m.clone(), provider: provider.clone() });
                known.push((provider, infos));
            }
            None => open.push(m.clone()),
        }
    }
    Ok((found, open))
}

/// Modrinth-Projekte, die die Mod `declarer` laut Modrinth braucht (Pflicht zuerst).
async fn declared_projects<L: VersionLookup>(lookup: &L, declarer: Option<&Entry>) -> Result<Vec<(String, bool)>> {
    let Some(version_id) = declarer.and_then(|e| e.version_id.as_deref()) else { return Ok(Vec::new()) };
    let version = match declarer.and_then(|e| e.version.clone()) {
        Some(v) => Some(v),
        None => match lookup.version(version_id).await {
            Ok(v) => v,
            Err(Error::Cancelled) => return Err(Error::Cancelled),
            Err(e) => {
                tracing::debug!("Version {version_id} nicht nachschlagbar: {e}");
                None
            }
        },
    };
    let Some(version) = version else { return Ok(Vec::new()) };
    let mut out: Vec<(String, bool)> = Vec::new();
    for required in [true, false] {
        for d in &version.dependencies {
            let wanted = if required { d.dependency_type == "required" } else { d.dependency_type == "optional" };
            if let Some(id) = d.project_id.as_ref().filter(|id| wanted && modrinth::is_safe_project_id(id))
                && !out.iter().any(|(o, _)| o == id)
            {
                out.push((id.clone(), required));
            }
        }
    }
    Ok(out)
}

async fn provider_on_modrinth<L: VersionLookup>(
    lookup: &L,
    channel: UpdateChannel,
    declarer: Option<&Entry>,
    mod_id: &str,
    enabled: &HashSet<String>,
    disabled: &[DisabledMod],
    known: &[(Provider, Vec<ModInfo>)],
) -> Result<Option<(Provider, Vec<ModInfo>)>> {
    let mut candidates = declared_projects(lookup, declarer).await?;
    // Viele Mods heißen auf Modrinth so wie ihre ID (`cloth-config`, `sodium` …).
    if modrinth::is_safe_project_id(mod_id) && !candidates.iter().any(|(c, _)| c == mod_id) {
        candidates.push((mod_id.to_owned(), false));
    }
    for (project, required) in candidates.into_iter().take(MAX_CANDIDATES) {
        // Schon eingeschaltet (liefert die ID also nicht) – oder schon gefunden.
        if enabled.contains(&project) {
            continue;
        }
        if let Some(k) = known.iter().find(|(p, _)| matches!(p, Provider::Install { project_id, .. } if *project_id == project)) {
            return Ok(Some(k.clone()));
        }
        if let Some(d) = disabled.iter().find(|d| d.project_id.as_deref() == Some(project.as_str())) {
            if required || d.provides(mod_id) {
                return Ok(Some((Provider::Enable { file_name: d.file_name.clone() }, d.mods.clone())));
            }
            continue;
        }
        let versions = match lookup.versions(&project, ContentKind::Mod).await {
            Ok(v) => v,
            Err(Error::Cancelled) => return Err(Error::Cancelled),
            Err(e) => {
                tracing::debug!("Versionen von {project} nicht abrufbar: {e}");
                continue;
            }
        };
        let Some(version) = modrinth::newest_in_channel(versions, channel) else { continue };
        if enabled.contains(&version.project_id) {
            continue;
        }
        // Per Slug gefunden, liegt aber deaktiviert da: einschalten statt neu laden.
        if let Some(d) = disabled.iter().find(|d| d.project_id.as_deref() == Some(version.project_id.as_str())) {
            if required || d.provides(mod_id) {
                return Ok(Some((Provider::Enable { file_name: d.file_name.clone() }, d.mods.clone())));
            }
            continue;
        }
        let infos = lookup.mod_info(&version).await;
        if !(provides(&infos, mod_id) || required) {
            continue;
        }
        let title = match lookup.title(&version.project_id).await {
            Some(t) => t,
            None => infos.first().map_or_else(|| version.name.clone(), |m| m.display_name().to_owned()),
        };
        return Ok(Some((Provider::Install { project_id: version.project_id, title }, infos)));
    }
    Ok(None)
}

/// Deaktivierte Mods der Instanz samt Jar-Angaben.
async fn disabled_mods(paths: &Paths, instance_id: &str) -> Result<Vec<DisabledMod>> {
    let mut out = Vec::new();
    for item in content::list(paths, instance_id, ContentKind::Mod).await?.into_iter().filter(|i| !i.enabled) {
        let Some(path) = content::existing_file(paths, instance_id, ContentKind::Mod, &item.file_name) else { continue };
        let (mods, nested) = modcompat::remote::local_mod_details(path).await;
        let project_id = item
            .source
            .filter(|s| s.platform == content::Platform::Modrinth && modrinth::is_safe_project_id(&s.project_id))
            .map(|s| s.project_id);
        out.push(DisabledMod { file_name: item.file_name, project_id, mods, nested });
    }
    Ok(out)
}

/// Loader und eingebaute Mods des TRS Clients als „Mods“.
async fn builtins(paths: &Paths, builds: &[Build], instance: &Instance) -> Vec<ModInfo> {
    let mut out = modcompat::loader_builtins(instance);
    for b in client_mod::builtin_mods(paths, builds, instance).await {
        if modcompat::meta::is_mod_id(&b.id) {
            out.push(ModInfo {
                name: b.name,
                version: b.version,
                scheme: modcompat::meta::Scheme::Fabric,
                provides: Vec::new(),
                depends: Vec::new(),
                breaks: Vec::new(),
                id: b.id,
            });
        }
    }
    out
}

/// Schon erfolglos gesucht (in dieser Sitzung): nicht bei jedem Start erneut fragen.
static UNRESOLVED: std::sync::LazyLock<std::sync::Mutex<HashSet<String>>> = std::sync::LazyLock::new(Default::default);
const MAX_UNRESOLVED: usize = 2000;

fn unresolved_key(instance: &Instance, m: &MissingDep, entries: &[Entry]) -> String {
    let declarer = entries.get(m.declarer).and_then(|e| e.file_name.as_deref()).unwrap_or("");
    format!("{}|{}|{declarer}|{}", instance.id, instance.game_version, m.id)
}

/// Schaltet ein bzw. installiert, was gefunden wurde.
async fn apply(
    http: &reqwest::Client,
    paths: &Paths,
    builds: &[Build],
    instance: &Instance,
    found: &[Found],
    progress: &(dyn Fn(ApplyProgress) + Sync),
) -> Result<DependencyFix> {
    let mut fix = DependencyFix::default();
    let mut enabled_files: HashSet<&str> = HashSet::new();
    for f in found {
        if let Provider::Enable { file_name } = &f.provider
            && enabled_files.insert(file_name)
        {
            content::set_enabled(paths, &instance.id, ContentKind::Mod, file_name, true).await?;
            tracing::info!("Abhängigkeit {} für {} wieder eingeschaltet ({file_name})", f.missing.id, f.missing.declarer_label);
            fix.added.push(title_of_file(paths, &instance.id, file_name).await);
            fix.note_needed_by(&f.missing.declarer_label);
        }
    }
    let mut projects: Vec<(String, String)> = Vec::new();
    for f in found {
        if let Provider::Install { project_id, title } = &f.provider
            && !projects.iter().any(|(p, _)| p == project_id)
        {
            projects.push((project_id.clone(), title.clone()));
        }
    }
    if projects.is_empty() {
        return Ok(fix);
    }
    let report = presets::install_projects(http, paths, instance, builds, &projects, progress).await?;
    for (item, (project_id, _)) in report.items.iter().zip(&projects) {
        let ok = matches!(item.status, ItemStatus::Installed | ItemStatus::Duplicate);
        if !ok {
            tracing::warn!("Abhängigkeit {project_id} nicht installiert: {:?} {:?}", item.status, item.detail);
            continue;
        }
        tracing::info!("Abhängigkeit {} ({project_id}) nachinstalliert", item.title);
        fix.added.push(item.title.clone());
        for f in found.iter().filter(|f| matches!(&f.provider, Provider::Install { project_id: p, .. } if p == project_id)) {
            fix.note_needed_by(&f.missing.declarer_label);
        }
    }
    Ok(fix)
}

async fn title_of_file(paths: &Paths, instance_id: &str, file_name: &str) -> String {
    let items = content::list(paths, instance_id, ContentKind::Mod).await.unwrap_or_default();
    items.into_iter().find(|i| i.file_name == file_name).and_then(|i| i.title).unwrap_or_else(|| file_name.to_owned())
}

/// Nur Fabric: Dort wissen wir, was eingebettet ist und was der Loader selbst bringt.
fn checks(instance: &Instance) -> bool {
    instance.loader.kind == LoaderKind::Fabric
}

/// Vor dem Start: Fehlt einer eingeschalteten Mod eine Pflicht-Abhängigkeit, wird
/// sie ergänzt (eingeschaltet oder von Modrinth geladen). `instance` ist die
/// Instanz, wie sie startet (Vanilla mit TRS-Optimierung also als Fabric).
/// Ohne Fund bleibt alles, wie es ist – das Spiel meldet es dann selbst.
pub async fn ensure_before_launch(
    http: &reqwest::Client,
    paths: &Paths,
    builds: &[Build],
    instance: &Instance,
    progress: &(dyn Fn(ApplyProgress) + Sync),
) -> Result<DependencyFix> {
    if !checks(instance) {
        return Ok(DependencyFix::default());
    }
    let entries = modcompat::installed_entries(paths, &instance.id).await?;
    let builtins = builtins(paths, builds, instance).await;
    let known_open = UNRESOLVED.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let missing: Vec<MissingDep> = modcompat::missing_dependencies(&entries, &builtins)
        .into_iter()
        .filter(|m| !known_open.contains(&unresolved_key(instance, m, &entries)))
        .collect();
    if missing.is_empty() {
        return Ok(DependencyFix::default());
    }
    tracing::info!(
        "Fehlende Abhängigkeiten in '{}': {}",
        instance.id,
        missing.iter().map(|m| format!("{} ← {}", m.id, m.declarer_label)).collect::<Vec<_>>().join(", ")
    );
    let lookup = presets::ModrinthLookup::new(http, paths, instance);
    let enabled: HashSet<String> = content::enabled_project_ids(paths, &instance.id).await?.into_iter().collect();
    let disabled = disabled_mods(paths, &instance.id).await?;
    let (found, open) = find_providers(&lookup, instance.overrides.channel(), &entries, &missing, &enabled, &disabled).await?;
    {
        let mut known = UNRESOLVED.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if known.len() > MAX_UNRESOLVED {
            known.clear();
        }
        known.extend(open.iter().map(|m| unresolved_key(instance, m, &entries)));
    }
    let mut fix = apply(http, paths, builds, instance, &found, progress).await?;
    fix.unresolved = open.into_iter().map(|m| m.id).collect();
    Ok(fix)
}

/// Absturz-Diagnose „… which is missing!“: installiert die genannten Mod-IDs
/// (`dependencies`, z. B. `cloth-config`). `declarer`: Mod-ID der Mod, die sie
/// verlangt (hilft beim Finden über deren Modrinth-Abhängigkeiten).
pub async fn install_missing(
    http: &reqwest::Client,
    paths: &Paths,
    builds: &[Build],
    instance: &Instance,
    declarer: Option<&str>,
    dependencies: &[String],
    progress: &(dyn Fn(ApplyProgress) + Sync),
) -> Result<DependencyFix> {
    modrinth::ensure_mods_allowed(ContentKind::Mod, instance)?;
    let wanted: Vec<&str> = dependencies.iter().map(String::as_str).filter(|d| modcompat::meta::is_mod_id(d)).take(MAX_LOOKUPS).collect();
    if wanted.is_empty() {
        return Err(Error::validation(crate::msg!("depcheck.nothingToInstall", "Keine fehlende Mod angegeben.")));
    }
    let entries = modcompat::installed_entries(paths, &instance.id).await?;
    let builtins = builtins(paths, builds, instance).await;
    let analysed = modcompat::missing_dependencies(&entries, &builtins);
    let declarer_index = declarer.and_then(|d| entries.iter().position(|e| provides(&e.mods, d)));
    let missing: Vec<MissingDep> = wanted
        .iter()
        .map(|id| {
            analysed.iter().find(|m| m.id == *id).cloned().unwrap_or_else(|| MissingDep {
                declarer: declarer_index.unwrap_or(usize::MAX),
                id: (*id).to_owned(),
                declarer_label: declarer_index
                    .and_then(|i| entries[i].mods.first())
                    .map_or_else(|| declarer.unwrap_or_default().to_owned(), |m| m.display_name().to_owned()),
            })
        })
        .collect();
    let lookup = presets::ModrinthLookup::new(http, paths, instance);
    let enabled: HashSet<String> = content::enabled_project_ids(paths, &instance.id).await?.into_iter().collect();
    let disabled = disabled_mods(paths, &instance.id).await?;
    let (found, open) = find_providers(&lookup, instance.overrides.channel(), &entries, &missing, &enabled, &disabled).await?;
    // Nach einem Fund darf der Start-Check es wieder versuchen.
    UNRESOLVED.lock().unwrap_or_else(std::sync::PoisonError::into_inner).retain(|k| !k.starts_with(&format!("{}|", instance.id)));
    let mut fix = apply(http, paths, builds, instance, &found, progress).await?;
    fix.unresolved = open.into_iter().map(|m| m.id).collect();
    if fix.added.is_empty() {
        return Err(Error::validation(crate::msg!(
            "depcheck.notFound",
            "{list} wurde auf Modrinth nicht gefunden – bitte von Hand installieren.",
            list = wanted.join(", ")
        )));
    }
    Ok(fix)
}

#[cfg(test)]
mod tests;
