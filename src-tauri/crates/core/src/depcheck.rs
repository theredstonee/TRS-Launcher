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

/// Hier prüfen wir Versions-Bedingungen (`depends`/`breaks` aus den Jars).
fn checks_versions(instance: &Instance) -> bool {
    matches!(instance.loader.kind, LoaderKind::Fabric | LoaderKind::Quilt | LoaderKind::Forge | LoaderKind::NeoForge)
}

/// Würde der Loader den Start bei diesem Konflikt sicher ablehnen (Fabric:
/// „Incompatible mods found!“)? Dann starten wir gar nicht erst.
pub fn blocks_launch(instance: &Instance) -> bool {
    matches!(instance.loader.kind, LoaderKind::Fabric | LoaderKind::Quilt)
}

/// Vor dem Start: Passen die Versionen der eingeschalteten Mods nicht
/// zusammen (Iris verlangt Sodium 0.9.x, da liegt 0.8.9; eine Mod für eine
/// andere Minecraft-Version), werden passende Versionen von Modrinth
/// getauscht – erst die verlangte Mod, sonst die, die sie verlangt. Zählt
/// auch eingebettete Mods und die des TRS Clients (der selbst nie getauscht
/// wird). Was sich nicht lösen lässt, steht in `unresolved`.
pub async fn ensure_versions_before_launch(
    http: &reqwest::Client,
    paths: &Paths,
    builds: &[Build],
    instance: &Instance,
    progress: &(dyn Fn(ApplyProgress) + Sync),
) -> Result<modcompat::CompatReport> {
    if !checks_versions(instance) {
        return Ok(modcompat::CompatReport::default());
    }
    let mut entries = modcompat::installed_entries(paths, &instance.id).await?;
    modcompat::obf::mark_old_builds(paths, instance, &mut entries).await;
    let builtins = builtins(paths, builds, instance).await;
    let conflicts = modcompat::find_conflicts(&entries, &builtins);
    if conflicts.is_empty() {
        return Ok(modcompat::CompatReport::default());
    }
    tracing::info!(
        "Mod-Versionen in '{}' passen nicht zusammen: {}",
        instance.id,
        conflicts.iter().map(modcompat::Conflict::describe).collect::<Vec<_>>().join(", ")
    );
    let lookup = presets::ModrinthLookup::new(http, paths, instance);
    modcompat::resolve_and_apply(http, paths, &lookup, instance, entries, &builtins, None, progress).await
}

/// [`ensure_versions_before_launch`] für den Start: Hinweis, was getauscht
/// wurde. Bleibt ein Konflikt, den Fabric/Quilt sicher ablehnen würde, startet
/// das Spiel nicht – statt Fabrics Fehlerfenster ein klarer Hinweis. Offline
/// oder bei anderen Fehlern startet es wie bisher.
///
/// `bypass`: „Trotzdem starten“ aus dem Konflikt-Helfer – für diesen einen
/// Start wird weder geprüft noch getauscht (gegen Fehlalarme).
pub async fn versions_for_launch(
    http: &reqwest::Client,
    paths: &Paths,
    builds: &[Build],
    instance: &Instance,
    bypass: bool,
    progress: &(dyn Fn(ApplyProgress) + Sync),
) -> Result<Option<crate::error::Msg>> {
    if bypass {
        tracing::warn!("Mod-Versions-Prüfung für diesen Start von '{}' übersprungen (Trotzdem starten)", instance.id);
        return Ok(None);
    }
    let report = match ensure_versions_before_launch(http, paths, builds, instance, progress).await {
        Ok(report) => report,
        Err(Error::Cancelled) => return Err(Error::Cancelled),
        Err(e) => {
            tracing::warn!("Mod-Versionen konnten nicht abgeglichen werden: {e}");
            return Ok(None);
        }
    };
    let conflicts = report.unresolved.join(", ");
    if !report.unresolved.is_empty() && blocks_launch(instance) {
        return Err(Error::launch(crate::msg!(
            "launcher.modVersionsConflict",
            "Diese Mods passen in den installierten Versionen nicht zusammen, und es gibt keine passende Version: {list}. Deaktiviere oder aktualisiere eine davon in der Mod-Liste.",
            list = conflicts
        )));
    }
    let changed = report
        .changes
        .iter()
        .map(|c| match &c.from {
            Some(from) => format!("{} {from} → {}", c.title, c.to),
            None => format!("{} → {}", c.title, c.to),
        })
        .collect::<Vec<_>>()
        .join(", ");
    Ok(match (changed.is_empty(), conflicts.is_empty()) {
        (true, true) => None,
        (false, true) => Some(crate::msg!(
            "launcher.modVersionsAdjusted",
            "Mod-Versionen angepasst, damit alles zusammenpasst: {list}.",
            list = changed
        )),
        (_, false) => Some(crate::msg!(
            "launcher.modVersionsUnresolved",
            "Diese Mods passen in den installierten Versionen vielleicht nicht zusammen: {list}.",
            list = conflicts
        )),
    })
}

// --- Konflikt-Helfer ------------------------------------------------------------------

/// Eine Seite eines Konflikts, wie der Konflikt-Helfer sie zeigt.
#[derive(Debug, Clone, Default, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ConflictParty {
    /// `mod` (Datei der Instanz), `game` (Minecraft), `loader` oder `bundled`
    /// (kommt mit dem TRS Client).
    pub kind: &'static str,
    pub mod_id: String,
    pub name: String,
    pub version: String,
    /// Datei im Mods-Ordner (nur `mod`).
    pub file_name: Option<String>,
    /// Steckt nur eingebettet in dieser Mod (Jar-in-Jar), z. B. „Fabric API“.
    pub bundled_in: Option<String>,
    /// Herkunft (Modrinth-ID bzw. CurseForge-Zahl) – für „Mod-Seite öffnen“.
    pub project_id: Option<String>,
    pub platform: Option<content::Platform>,
    pub slug: Option<String>,
    pub icon_url: Option<String>,
    pub enabled: bool,
    /// Lässt sich über Modrinth gegen eine andere Version tauschen.
    pub adjustable: bool,
}

/// Ein Versions-Konflikt: `declarer` verlangt (`depends`) bzw. schließt aus
/// (`breaks`) `other` in den Versionen `ranges`.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ModConflict {
    pub kind: &'static str,
    pub declarer: ConflictParty,
    pub other: ConflictParty,
    pub ranges: Vec<String>,
    /// „Better Advancements 0.4.8.54 ↔ Minecraft 26.1“ (wie im Hinweis/Log).
    pub text: String,
}

#[derive(Debug, Clone, Default, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ConflictReport {
    pub conflicts: Vec<ModConflict>,
    /// Lehnt der Loader den Start dabei sicher ab (Fabric/Quilt)?
    pub blocks_launch: bool,
}

const LOADER_IDS: &[&str] = &["fabricloader", "fabric-loader", "quilt_loader", "forge", "neoforge"];

fn entry_party(entry: &Entry, m: &modcompat::ModRef, items: &[content::ContentItem]) -> ConflictParty {
    let item = entry.file_name.as_deref().and_then(|f| items.iter().find(|i| i.file_name == f));
    // Nur eingebettet (z. B. ein Modul der Fabric API in einer anderen Mod)?
    let bundled_in = if entry.mods.iter().any(|o| o.id == m.id) {
        None
    } else {
        item.and_then(|i| i.title.clone()).or_else(|| entry.mods.first().map(|o| o.display_name().to_owned()))
    };
    let source = item.and_then(|i| i.source.as_ref());
    ConflictParty {
        kind: "mod",
        mod_id: m.id.clone(),
        name: m.name.clone(),
        version: m.version.clone(),
        file_name: entry.file_name.clone(),
        bundled_in,
        project_id: source.map(|s| s.project_id.clone()),
        platform: source.map(|s| s.platform),
        slug: item.and_then(|i| i.slug.clone()),
        icon_url: item.and_then(|i| i.icon_url.clone()),
        enabled: item.is_none_or(|i| i.enabled),
        adjustable: entry.adjustable,
    }
}

fn builtin_party(m: &modcompat::ModRef) -> ConflictParty {
    let kind = if m.id == "minecraft" {
        "game"
    } else if LOADER_IDS.contains(&m.id.as_str()) {
        "loader"
    } else {
        "bundled"
    };
    ConflictParty { kind, mod_id: m.id.clone(), name: m.name.clone(), version: m.version.clone(), enabled: true, ..Default::default() }
}

/// Konflikte als Daten für den Konflikt-Helfer. `items`: Inhalte der Instanz
/// (Titel, Herkunft, Symbol).
pub(crate) fn describe_conflicts(entries: &[Entry], items: &[content::ContentItem], conflicts: &[modcompat::Conflict]) -> Vec<ModConflict> {
    conflicts
        .iter()
        .filter_map(|c| {
            let declarer = entry_party(entries.get(c.declarer)?, &c.declarer_mod, items);
            let other = match c.target {
                modcompat::Party::Entry(t) => entry_party(entries.get(t)?, &c.target_mod, items),
                modcompat::Party::Builtin(_) => builtin_party(&c.target_mod),
            };
            Some(ModConflict {
                kind: match c.kind {
                    modcompat::ConflictKind::Depends => "depends",
                    modcompat::ConflictKind::Breaks => "breaks",
                    modcompat::ConflictKind::OldBuild => "oldBuild",
                },
                declarer,
                other,
                ranges: c.ranges.iter().take(8).cloned().collect(),
                text: c.describe(),
            })
        })
        .collect()
}

/// Konflikt-Helfer: Welche eingeschalteten Mods passen in den installierten
/// Versionen nicht zusammen? Nur lesen – nichts wird getauscht, nichts geladen.
/// `instance` ist die Instanz, wie sie startet (siehe [`ensure_before_launch`]).
pub async fn conflict_report(paths: &Paths, builds: &[Build], instance: &Instance) -> Result<ConflictReport> {
    let blocks = blocks_launch(instance);
    if !checks_versions(instance) {
        return Ok(ConflictReport { conflicts: Vec::new(), blocks_launch: blocks });
    }
    let mut entries = modcompat::installed_entries(paths, &instance.id).await?;
    modcompat::obf::mark_old_builds(paths, instance, &mut entries).await;
    let builtins = builtins(paths, builds, instance).await;
    let conflicts = modcompat::find_conflicts(&entries, &builtins);
    if conflicts.is_empty() {
        return Ok(ConflictReport { conflicts: Vec::new(), blocks_launch: blocks });
    }
    let items = content::list(paths, &instance.id, ContentKind::Mod).await?;
    Ok(ConflictReport { conflicts: describe_conflicts(&entries, &items, &conflicts), blocks_launch: blocks })
}

/// Ergebnis von „Passende Version suchen“.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FitResult {
    /// `installed`, `none` (es gibt noch keine passende) oder `unsupported`
    /// (nicht über Modrinth installiert – Versionen lassen sich nicht vergleichen).
    pub status: &'static str,
    pub title: String,
    pub from: Option<String>,
    pub to: Option<String>,
}

/// Was „Passende Version suchen“ tun würde (ohne zu installieren).
#[derive(Debug)]
pub(crate) enum Fit {
    Unsupported { title: String },
    None { title: String },
    Found { version: Box<modrinth::Version>, change: modcompat::CompatChange },
}

pub(crate) async fn plan_fit<L: VersionLookup>(
    lookup: &L,
    channel: UpdateChannel,
    mut entries: Vec<Entry>,
    builtins: &[ModInfo],
    file_name: &str,
) -> Result<Fit> {
    let Some(idx) = entries.iter().position(|e| e.file_name.as_deref() == Some(file_name)) else {
        return Err(Error::validation(crate::msg!("modConflicts.modGone", "Diese Mod ist nicht (mehr) eingeschaltet.")));
    };
    let title = entries[idx].mods.first().map_or_else(|| file_name.to_owned(), |m| m.display_name().to_owned());
    if !entries[idx].adjustable {
        return Ok(Fit::Unsupported { title });
    }
    let from = entries[idx].mods.first().map(|m| m.version.clone());
    match modcompat::fitting_version(lookup, channel, &mut entries, builtins, idx).await? {
        Some((version, mods)) => {
            let to = mods.first().map_or_else(|| version.version_number.clone(), |m| m.version.clone());
            Ok(Fit::Found { version: Box::new(version), change: modcompat::CompatChange { title, from, to, because: String::new() } })
        }
        None => Ok(Fit::None { title }),
    }
}

/// Konflikt-Helfer „Passende Version suchen“: tauscht die Mod `file_name`
/// gegen eine Version, die zu Minecraft, Loader und den übrigen Mods passt
/// (Modrinth). Gibt es keine, bleibt alles, wie es ist.
pub async fn install_fitting_version(
    http: &reqwest::Client,
    paths: &Paths,
    builds: &[Build],
    instance: &Instance,
    file_name: &str,
) -> Result<FitResult> {
    modrinth::ensure_mods_allowed(ContentKind::Mod, instance)?;
    content::validate_file_name(ContentKind::Mod, file_name)?;
    let mut entries = modcompat::installed_entries(paths, &instance.id).await?;
    modcompat::obf::mark_old_builds(paths, instance, &mut entries).await;
    let builtins = builtins(paths, builds, instance).await;
    let lookup = presets::ModrinthLookup::new(http, paths, instance);
    match plan_fit(&lookup, instance.overrides.channel(), entries, &builtins, file_name).await? {
        Fit::Unsupported { title } => Ok(FitResult { status: "unsupported", title, from: None, to: None }),
        Fit::None { title } => Ok(FitResult { status: "none", title, from: None, to: None }),
        Fit::Found { version, change } => {
            modrinth::install_with_dependencies(http, paths, instance, ContentKind::Mod, &version, Some(file_name)).await?;
            tracing::info!("Konflikt-Helfer: {} {:?} → {}", change.title, change.from, change.to);
            Ok(FitResult { status: "installed", title: change.title, from: change.from, to: Some(change.to) })
        }
    }
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
