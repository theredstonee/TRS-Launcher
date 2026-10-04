//! Passen die Mods einer Instanz zusammen? Modrinths Abhängigkeits-Angaben
//! kennen keine Versions-Bereiche – was eine Mod wirklich verlangt oder
//! ausschließt, steht nur in ihrem Jar (`fabric.mod.json` `depends`/`breaks`,
//! `quilt.mod.json`, (Neo)Forges `mods.toml`). Sodium 0.8.14 etwa „bricht“
//! Iris bis 1.10.7 – beide einzeln als neueste Version geladen, startet das
//! Spiel nicht.
//!
//! Dieses Modul liest diese Angaben (auch aus noch nicht geladenen Jars, siehe
//! [`remote`]), findet Konflikte und tauscht – wenn möglich – eine der
//! beteiligten Mods gegen eine passende Version: erst ältere Versionen der
//! Mod, die den Bruch erklärt, dann neuere der anderen. Pro Projekt werden
//! höchstens [`MAX_TRIES_PER_PROJECT`] Versionen angesehen.

pub(crate) mod meta;
pub(crate) mod remote;
pub mod version;

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;

use serde::Serialize;

pub use meta::ModInfo;
use meta::{Constraint, Scheme};

use crate::content::{self, ContentKind, Platform};
use crate::instance::{Instance, LoaderKind, UpdateChannel};
use crate::modrinth::{self, Version};
use crate::paths::Paths;
use crate::presets::VersionLookup;
use crate::{Result, task};

/// So viele Versionen eines Projekts werden höchstens ausprobiert.
pub(crate) const MAX_TRIES_PER_PROJECT: usize = 8;
const MAX_ROUNDS: usize = 24;

/// Cache-Ordner für gelesene Mod-Infos (je SHA1).
pub(crate) fn cache_dir(paths: &Paths) -> PathBuf {
    paths.root().join("cache").join("mod-meta")
}

/// Eine Mod-Datei in der Prüfung – installiert oder geplant.
#[derive(Debug, Clone, Default)]
pub(crate) struct Entry {
    /// Modrinth-Projekt; nur dann lässt sich die Version tauschen.
    pub project_id: Option<String>,
    /// Aktuell gewählte Modrinth-Version (ID).
    pub version_id: Option<String>,
    /// Aktuell gewählte Version, falls schon nachgeschlagen.
    pub version: Option<Version>,
    pub mods: Vec<ModInfo>,
    /// Darf gegen eine andere Version getauscht werden?
    pub adjustable: bool,
    /// Installierte Version (ID + Infos) – spart das Nachladen beim Zurückgehen.
    pub installed: Option<(String, Vec<ModInfo>)>,
    /// Dateiname in der Instanz (nur installierte).
    pub file_name: Option<String>,
    /// Vom Auflösen gesetzt: getauscht, damit es mit dieser Mod läuft („Iris 1.10.7“).
    pub because: Option<String>,
    /// Mod-IDs eingebetteter Jars (Jar-in-Jar, nur installierte) – zählen für
    /// fehlende Abhängigkeiten als vorhanden.
    pub nested: Vec<String>,
    /// Eingebettete Mods samt Version (nur installierte) – zählen für
    /// Versions-Bedingungen anderer Mods mit (der Loader wählt unter allen Kopien).
    pub nested_mods: Vec<ModInfo>,
}

impl Entry {
    fn provides(&self, id: &str) -> bool {
        self.mods.iter().any(|m| m.id == id || m.provides.iter().any(|p| p == id))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) enum Party {
    Entry(usize),
    /// Der Modloader selbst (z. B. `fabricloader`).
    Builtin(usize),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) enum ConflictKind {
    /// `breaks`: Die andere Mod ist in einer ausgeschlossenen Version da.
    Breaks,
    /// `depends`: Die andere Mod ist da, aber in keiner passenden Version.
    Depends,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Conflict {
    /// Die Mod, die die Bedingung stellt.
    pub declarer: usize,
    pub target: Party,
    pub kind: ConflictKind,
    /// „Sodium 0.8.14+mc1.21.11“
    pub declarer_label: String,
    /// „Iris 1.10.7+mc1.21.11“
    pub target_label: String,
}

impl Conflict {
    /// „Iris 1.11.4+mc26.1.2 ↔ Sodium 0.8.9+mc26.1.1“ – für Hinweise.
    pub(crate) fn describe(&self) -> String {
        format!("{} ↔ {}", self.declarer_label, self.target_label)
    }

    fn key(&self) -> (usize, Party, ConflictKind) {
        (self.declarer, self.target, self.kind)
    }

    fn involves(&self, idx: usize) -> bool {
        self.declarer == idx || self.target == Party::Entry(idx)
    }
}

/// Erfüllt `version` die Bedingung? `None` = nicht auswertbar.
fn satisfies(scheme: Scheme, constraint: &Constraint, version: &str) -> Option<bool> {
    match scheme {
        Scheme::Fabric => version::fabric_matches(&constraint.any_of, version),
        Scheme::Maven => {
            if constraint.any_of.is_empty() {
                return Some(true);
            }
            let mut unknown = false;
            for range in &constraint.any_of {
                match version::maven_matches(range, version) {
                    Some(true) => return Some(true),
                    Some(false) => {}
                    None => unknown = true,
                }
            }
            if unknown { None } else { Some(false) }
        }
    }
}

/// Alle Konflikte der Menge (je Paar und Art höchstens einmal).
pub(crate) fn find_conflicts(entries: &[Entry], builtins: &[ModInfo]) -> Vec<Conflict> {
    // Welche IDs sind da – und in welcher Version?
    let mut present: HashMap<&str, Vec<(Party, &ModInfo)>> = HashMap::new();
    for (i, e) in entries.iter().enumerate() {
        for m in e.mods.iter().chain(&e.nested_mods) {
            present.entry(m.id.as_str()).or_default().push((Party::Entry(i), m));
            for p in &m.provides {
                present.entry(p.as_str()).or_default().push((Party::Entry(i), m));
            }
        }
    }
    for (i, b) in builtins.iter().enumerate() {
        present.entry(b.id.as_str()).or_default().push((Party::Builtin(i), b));
    }

    let mut out: Vec<Conflict> = Vec::new();
    let mut push = |c: Conflict| {
        if !out.iter().any(|o| o.key() == c.key()) {
            out.push(c);
        }
    };
    for (i, e) in entries.iter().enumerate() {
        for m in &e.mods {
            for c in &m.breaks {
                for (party, other) in present.get(c.id.as_str()).into_iter().flatten() {
                    if *party == Party::Entry(i) {
                        continue;
                    }
                    if satisfies(m.scheme, c, &other.version) == Some(true) {
                        push(Conflict {
                            declarer: i,
                            target: *party,
                            kind: ConflictKind::Breaks,
                            declarer_label: m.label(),
                            target_label: other.label(),
                        });
                    }
                }
            }
            for c in &m.depends {
                // Eigene eingebettete Kopien zählen mit (der Loader nimmt auch die).
                let own = |p: &Party, o: &ModInfo| *p == Party::Entry(i) && e.mods.iter().any(|m| std::ptr::eq(m, o));
                let candidates: Vec<&(Party, &ModInfo)> =
                    present.get(c.id.as_str()).into_iter().flatten().filter(|(p, o)| !own(p, o)).collect();
                // Fehlt die Mod ganz, kümmern sich Modrinths Abhängigkeiten darum.
                let Some((party, other)) = candidates.iter().find(|(p, _)| *p != Party::Entry(i)).or(candidates.first()) else {
                    continue;
                };
                if candidates.iter().all(|(_, o)| satisfies(m.scheme, c, &o.version) == Some(false)) {
                    push(Conflict {
                        declarer: i,
                        target: *party,
                        kind: ConflictKind::Depends,
                        declarer_label: m.label(),
                        target_label: other.label(),
                    });
                }
            }
        }
    }
    out
}

/// Mod-IDs, die Spiel und Modloader selbst mitbringen – nie eine eigene Datei.
pub(crate) const PLATFORM_IDS: &[&str] = &[
    "minecraft",
    "java",
    "fabricloader",
    "fabric-loader",
    "mixinextras",
    "quilt_loader",
    "quilt_base",
    "forge",
    "neoforge",
    "fml",
    "javafml",
    "lowcodefml",
    "mcp",
];

/// Eine Pflicht-Abhängigkeit aus dem Jar, die in der Menge ganz fehlt.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct MissingDep {
    /// Eintrag, dessen Mod die Abhängigkeit verlangt.
    pub declarer: usize,
    /// Fehlende Mod-ID (z. B. `cloth-config`).
    pub id: String,
    /// „More Culling 1.6.2“
    pub declarer_label: String,
}

/// Pflicht-Abhängigkeiten (`depends`), die keine Mod der Menge (auch nicht per
/// `provides` oder als eingebettetes Jar), kein eingebauter Mod und nicht der
/// Loader selbst erfüllt – je Mod-ID höchstens einmal. Bei geplanten Mods
/// (ohne gelesene Jar-in-Jar-IDs) kann das zu viel melden – wer daraus handelt,
/// ordnet die ID erst einem Projekt zu (siehe [`crate::depcheck`]).
pub(crate) fn missing_dependencies(entries: &[Entry], builtins: &[ModInfo]) -> Vec<MissingDep> {
    let mut present: HashSet<&str> = PLATFORM_IDS.iter().copied().collect();
    for m in entries.iter().flat_map(|e| &e.mods).chain(builtins) {
        present.insert(m.id.as_str());
        present.extend(m.provides.iter().map(String::as_str));
    }
    present.extend(entries.iter().flat_map(|e| &e.nested).map(String::as_str));
    let mut out: Vec<MissingDep> = Vec::new();
    for (i, e) in entries.iter().enumerate() {
        for m in &e.mods {
            for c in &m.depends {
                if present.contains(c.id.as_str()) || out.iter().any(|o| o.id == c.id) {
                    continue;
                }
                out.push(MissingDep { declarer: i, id: c.id.clone(), declarer_label: m.label() });
            }
        }
    }
    out
}

/// Der Modloader als „Mod“, damit `depends: { fabricloader: ">=0.16" }` zählt –
/// bei Fabric/Quilt auch Minecraft selbst (nur Vollversionen wie `26.1.2`:
/// Snapshots schreibt der Loader anders, da raten wir nicht).
pub(crate) fn loader_builtins(instance: &Instance) -> Vec<ModInfo> {
    let builtin = |id: &str, name: &str, version: &str| ModInfo {
        id: id.into(),
        name: name.into(),
        version: version.to_owned(),
        scheme: Scheme::Fabric,
        provides: Vec::new(),
        depends: Vec::new(),
        breaks: Vec::new(),
    };
    let mut out = Vec::new();
    if let (LoaderKind::Fabric, Some(version)) = (instance.loader.kind, &instance.loader.version)
        && !version.is_empty()
    {
        out.push(builtin("fabricloader", "Fabric Loader", version));
    }
    if matches!(instance.loader.kind, LoaderKind::Fabric | LoaderKind::Quilt) && is_release_version(&instance.game_version) {
        out.push(builtin(GAME_ID, "Minecraft", &instance.game_version));
    }
    out
}

const GAME_ID: &str = "minecraft";

/// `1.21.11`, `26.1.2` – keine Snapshots/Vorabversionen.
fn is_release_version(version: &str) -> bool {
    let parts: Vec<&str> = version.split('.').collect();
    (2..=4).contains(&parts.len()) && parts.iter().all(|p| !p.is_empty() && p.len() <= 4 && p.bytes().all(|b| b.is_ascii_digit()))
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Role {
    /// Die Mod, die die Bedingung stellt: erst ältere Versionen.
    Declarer,
    /// Wie `Declarer`, aber erst neuere (passt nicht zur Minecraft-Version).
    DeclarerNewer,
    /// Die Mod, an die die Bedingung geht: erst neuere Versionen.
    Target,
}

/// Merkt sich Versionslisten und Versuche über mehrere Runden.
#[derive(Default)]
struct Budget {
    lists: HashMap<String, Vec<Version>>,
    tries: HashMap<String, usize>,
    /// Gelesene Jar-Angaben je Version (auch über den zweiten Durchgang).
    infos: HashMap<String, Vec<ModInfo>>,
    /// Per Umweg getauscht – wird in diesem Durchgang nicht mehr angefasst.
    locked: HashSet<usize>,
}

/// Lässt sich der Konflikt `c` von `idx` in einer späteren Runde lösen, indem
/// die andere Mod getauscht wird (Iris 1.11 will Sodium 0.9.x, Sodium 0.9.x
/// passt nicht zur alten Sodium Extra → die kommt als Nächstes dran)?
fn fixable_later(c: &Conflict, idx: usize, entries: &[Entry], locked: &HashSet<usize>) -> bool {
    let other = if c.declarer == idx { c.target } else { Party::Entry(c.declarer) };
    c.kind == ConflictKind::Depends && matches!(other, Party::Entry(o) if o != idx && entries[o].adjustable && !locked.contains(&o))
}

/// Versionen im Kanal, neueste zuerst (keine im Kanal → alle).
async fn channel_versions<L: VersionLookup>(
    lookup: &L,
    channel: UpdateChannel,
    budget: &mut Budget,
    project: &str,
) -> Result<Vec<Version>> {
    if let Some(list) = budget.lists.get(project) {
        return Ok(list.clone());
    }
    let all = lookup.versions(project, ContentKind::Mod).await?;
    let in_channel: Vec<Version> = all.iter().filter(|v| channel.allows(&v.version_type)).cloned().collect();
    let list = if in_channel.is_empty() { all } else { in_channel };
    budget.lists.insert(project.to_owned(), list.clone());
    Ok(list)
}

/// Stabile Veröffentlichungen vor Vorabversionen (spart Versuche).
fn releases_first(mut list: Vec<Version>) -> Vec<Version> {
    list.sort_by_key(|v| v.version_type != "release");
    list
}

/// Sucht für `idx` eine Version ohne Konflikte. Liefert Version + Infos und ob
/// es ein Umweg ist (`lenient`: Es bleiben nur Konflikte, die sich durch Tausch
/// einer anderen Mod lösen lassen – siehe [`fixable_later`]).
#[allow(clippy::too_many_arguments)]
async fn alternative<L: VersionLookup>(
    lookup: &L,
    channel: UpdateChannel,
    entries: &mut [Entry],
    builtins: &[ModInfo],
    idx: usize,
    role: Role,
    lenient: bool,
    budget: &mut Budget,
) -> Result<Option<(Version, Vec<ModInfo>, bool)>> {
    let Some(project) = entries[idx].project_id.clone() else { return Ok(None) };
    let list = channel_versions(lookup, channel, budget, &project).await?;
    let current_id = entries[idx].version_id.clone();
    let (newer, older): (Vec<Version>, Vec<Version>) = match list.iter().position(|v| Some(&v.id) == current_id.as_ref()) {
        Some(p) => (list[..p].to_vec(), list[p + 1..].to_vec()),
        None => match entries[idx].version.as_ref().and_then(|v| v.date_published) {
            Some(date) => list.into_iter().partition(|v| v.date_published.is_some_and(|d| d > date)),
            None => (Vec::new(), list),
        },
    };
    let order = match role {
        // Die Mod, die den Bruch erklärt: erst ältere Versionen.
        Role::Declarer => [releases_first(older), releases_first(newer)].concat(),
        // Die andere (oder eine Mod für eine ältere Minecraft-Version): erst neuere, dann ältere.
        Role::Target | Role::DeclarerNewer => [releases_first(newer), releases_first(older)].concat(),
    };
    // Erster Umweg-Kandidat – nur, wenn sich nichts ohne Umweg findet.
    let mut detour: Option<(Version, Vec<ModInfo>)> = None;
    for candidate in order {
        if Some(&candidate.id) == current_id.as_ref() {
            continue;
        }
        let used = budget.tries.entry(project.clone()).or_default();
        if *used >= MAX_TRIES_PER_PROJECT {
            break;
        }
        *used += 1;
        task::checkpoint().await?;
        let is_installed = matches!(&entries[idx].installed, Some((id, _)) if *id == candidate.id);
        let mods = match &entries[idx].installed {
            Some((id, mods)) if *id == candidate.id => mods.clone(),
            _ => match budget.infos.get(&candidate.id) {
                Some(mods) => mods.clone(),
                None => {
                    let mods = lookup.mod_info(&candidate).await;
                    budget.infos.insert(candidate.id.clone(), mods.clone());
                    mods
                }
            },
        };
        if mods.is_empty() {
            continue;
        }
        // Eingebettete Mods einer anderen Version kennen wir nicht.
        let nested = if is_installed { entries[idx].nested_mods.clone() } else { Vec::new() };
        let before = std::mem::replace(&mut entries[idx].mods, mods);
        let nested_before = std::mem::replace(&mut entries[idx].nested_mods, nested);
        let left: Vec<Conflict> = find_conflicts(entries, builtins).into_iter().filter(|c| c.involves(idx)).collect();
        let mods = std::mem::replace(&mut entries[idx].mods, before);
        entries[idx].nested_mods = nested_before;
        if left.is_empty() {
            return Ok(Some((candidate, mods, false)));
        }
        if lenient && detour.is_none() && left.iter().all(|c| fixable_later(c, idx, entries, &budget.locked)) {
            detour = Some((candidate, mods));
        }
    }
    Ok(detour.map(|(v, m)| (v, m, true)))
}

/// Tauscht Versionen, bis die Menge zusammenpasst (oder nichts mehr hilft).
/// `prefer`: Mod-ID, die bevorzugt getauscht wird (aus der Absturz-Meldung).
/// Liefert die Konflikte, die bleiben.
///
/// Erst mit Umwegen (Ketten wie Iris → Sodium → Sodium Extra: neuere
/// Versionen ziehen weitere nach), bleibt dabei etwas offen, noch einmal ohne
/// – das Ergebnis mit weniger offenen Konflikten zählt.
pub(crate) async fn settle<L: VersionLookup>(
    lookup: &L,
    channel: UpdateChannel,
    entries: &mut [Entry],
    builtins: &[ModInfo],
    prefer: Option<&str>,
) -> Result<Vec<Conflict>> {
    let mut budget = Budget::default();
    let original = entries.to_vec();
    let left = settle_pass(lookup, channel, entries, builtins, prefer, true, &mut budget).await?;
    if left.is_empty() {
        return Ok(left);
    }
    let mut strict = original;
    budget.tries.clear();
    budget.locked.clear();
    let strict_left = settle_pass(lookup, channel, &mut strict, builtins, prefer, false, &mut budget).await?;
    if strict_left.len() < left.len() {
        entries.clone_from_slice(&strict);
        return Ok(strict_left);
    }
    Ok(left)
}

async fn settle_pass<L: VersionLookup>(
    lookup: &L,
    channel: UpdateChannel,
    entries: &mut [Entry],
    builtins: &[ModInfo],
    prefer: Option<&str>,
    lenient: bool,
    budget: &mut Budget,
) -> Result<Vec<Conflict>> {
    let mut given_up: HashSet<(usize, Party, ConflictKind)> = HashSet::new();
    for _ in 0..MAX_ROUNDS {
        let conflicts = find_conflicts(entries, builtins);
        let Some(conflict) = conflicts.into_iter().find(|c| !given_up.contains(&c.key())) else { break };
        // Passt eine Mod nicht zur Minecraft-Version, hilft eher eine neuere.
        let declarer_role = match conflict.target {
            Party::Builtin(b) if conflict.kind == ConflictKind::Depends && builtins.get(b).is_some_and(|m| m.id == GAME_ID) => {
                Role::DeclarerNewer
            }
            _ => Role::Declarer,
        };
        let mut order = vec![(conflict.declarer, declarer_role)];
        if let Party::Entry(t) = conflict.target {
            // Falsche Version einer Abhängigkeit (Iris will Sodium 0.9.x, da ist 0.8.9):
            // erst die Abhängigkeit tauschen, dann erst die Mod, die sie verlangt.
            if conflict.kind == ConflictKind::Depends {
                order.insert(0, (t, Role::Target));
            } else {
                order.push((t, Role::Target));
            }
        }
        if let Some(id) = prefer {
            // Stabil: die bevorzugte Mod nach vorn.
            order.sort_by_key(|(i, _)| !entries[*i].provides(id));
        }
        let mut fixed = false;
        for (idx, role) in order {
            if !entries[idx].adjustable || budget.locked.contains(&idx) {
                continue;
            }
            if let Some((version, mods, detour)) = alternative(lookup, channel, entries, builtins, idx, role, lenient, budget).await? {
                if detour {
                    budget.locked.insert(idx);
                }
                let because = if role == Role::Target { &conflict.declarer_label } else { &conflict.target_label };
                tracing::info!(
                    "Mod-Konflikt {} ↔ {}: {:?} → {}",
                    conflict.declarer_label,
                    conflict.target_label,
                    entries[idx].project_id,
                    version.version_number
                );
                let entry = &mut entries[idx];
                entry.because = Some(because.clone());
                entry.version_id = Some(version.id.clone());
                entry.version = Some(version);
                entry.mods = mods;
                if entry.installed.as_ref().is_none_or(|(id, _)| Some(id) != entry.version_id.as_ref()) {
                    entry.nested_mods.clear();
                }
                fixed = true;
                break;
            }
        }
        if !fixed {
            given_up.insert(conflict.key());
        }
    }
    Ok(find_conflicts(entries, builtins))
}

/// Die aktivierten Mods einer Instanz als Prüf-Einträge.
pub(crate) async fn installed_entries(paths: &Paths, instance_id: &str) -> Result<Vec<Entry>> {
    use futures::StreamExt;

    let items = content::list(paths, instance_id, ContentKind::Mod).await?;
    let files: Vec<(content::ContentItem, PathBuf)> = items
        .into_iter()
        .filter(|i| i.enabled)
        .filter_map(|i| content::existing_file(paths, instance_id, ContentKind::Mod, &i.file_name).map(|p| (i, p)))
        .collect();
    // Mehrere Jars gleichzeitig lesen (beim ersten Mal je Sitzung; danach aus dem Cache).
    let read: Vec<_> = futures::stream::iter(files.into_iter().map(|(item, path)| async move {
        (item, remote::local_jar_details(path).await)
    }))
    .buffered(8)
    .collect()
    .await;
    let mut out = Vec::new();
    for (item, (mods, nested_mods)) in read {
        // Den TRS Client tauscht nur sein eigenes Update.
        let own = crate::client_mod::is_client_mod_file(ContentKind::Mod, &item.file_name);
        let source = item
            .source
            .filter(|s| s.platform == Platform::Modrinth && modrinth::is_safe_project_id(&s.project_id))
            .filter(|s| modrinth::is_safe_project_id(&s.version_id))
            .filter(|_| !own);
        let (project_id, version_id) = source.map(|s| (s.project_id, s.version_id)).unzip();
        out.push(Entry {
            adjustable: project_id.is_some() && !mods.is_empty(),
            installed: version_id.clone().map(|id| (id, mods.clone())),
            project_id,
            version_id,
            version: None,
            mods,
            file_name: Some(item.file_name),
            because: None,
            nested: meta::nested_ids(&nested_mods),
            nested_mods,
        });
    }
    Ok(out)
}

// --- Reparieren (Absturz-Diagnose) -----------------------------------------------

/// Ein getauschter Mod.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompatChange {
    pub title: String,
    pub from: Option<String>,
    pub to: String,
    /// Damit läuft es jetzt zusammen („Iris 1.10.7+mc1.21.11“).
    pub because: String,
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompatReport {
    pub changes: Vec<CompatChange>,
    /// Konflikte, für die es keine passende Version gibt („Sodium 0.8.14 ↔ Iris 1.10.7“).
    pub unresolved: Vec<String>,
}

/// Macht die Mods einer Instanz verträglich: tauscht (über Modrinth
/// installierte) Mods gegen passende Versionen. `prefer` = Mod-ID aus der
/// Absturz-Meldung, die zuerst getauscht werden soll.
pub async fn fix_instance(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
    prefer: Option<&str>,
) -> Result<CompatReport> {
    modrinth::ensure_mods_allowed(ContentKind::Mod, instance)?;
    let entries = installed_entries(paths, &instance.id).await?;
    let builtins = loader_builtins(instance);
    let lookup = crate::presets::ModrinthLookup::new(http, paths, instance);
    let prefer = prefer.filter(|p| meta::is_mod_id(p));
    resolve_and_apply(http, paths, &lookup, instance, entries, &builtins, prefer, &|_| {}).await
}

/// Tauscht Versionen, bis `entries` zusammenpassen, und ersetzt die Dateien.
/// Jede Datei für sich: Die alte bleibt liegen, bis die neue geladen und ihre
/// Prüfsumme bestätigt ist (Schalter, Gruppen und Herkunft bleiben am Projekt).
#[allow(clippy::too_many_arguments)]
pub(crate) async fn resolve_and_apply<L: VersionLookup>(
    http: &reqwest::Client,
    paths: &Paths,
    lookup: &L,
    instance: &Instance,
    mut entries: Vec<Entry>,
    builtins: &[ModInfo],
    prefer: Option<&str>,
    progress: &(dyn Fn(crate::presets::ApplyProgress) + Sync),
) -> Result<CompatReport> {
    let unresolved = settle(lookup, instance.overrides.channel(), &mut entries, builtins, prefer).await?;
    let mut report = CompatReport { unresolved: unresolved.iter().map(Conflict::describe).collect(), ..Default::default() };
    let changed = changed_entries(entries);
    let total = u32::try_from(changed.len()).unwrap_or(u32::MAX);
    for (n, (change, version, file)) in changed.into_iter().enumerate() {
        task::checkpoint().await?;
        progress(crate::presets::ApplyProgress {
            phase: crate::presets::ApplyPhase::Install,
            done: u32::try_from(n).unwrap_or(u32::MAX),
            total,
            title: Some(change.title.clone()),
        });
        // Die getauschte Version kann andere Pflicht-Abhängigkeiten haben.
        modrinth::install_with_dependencies(http, paths, instance, ContentKind::Mod, &version, Some(&file)).await?;
        tracing::info!("Mod-Version getauscht: {} {:?} → {} (wegen {})", change.title, change.from, change.to, change.because);
        report.changes.push(change);
    }
    Ok(report)
}

/// Was [`settle`] getauscht hat: Änderung, neue Version, bisherige Datei.
pub(crate) fn changed_entries(entries: Vec<Entry>) -> Vec<(CompatChange, Version, String)> {
    let mut out = Vec::new();
    for entry in entries {
        let (Some(because), Some(version), Some(file)) = (entry.because, entry.version, entry.file_name) else { continue };
        // Am Ende doch wieder die installierte Version.
        if entry.installed.as_ref().is_some_and(|(id, _)| *id == version.id) {
            continue;
        }
        let title = entry.mods.first().map(|m| m.display_name().to_owned()).unwrap_or_else(|| version.name.clone());
        let from = entry.installed.and_then(|(_, mods)| mods.first().map(|m| m.version.clone()));
        let to = entry.mods.first().map(|m| m.version.clone()).unwrap_or_else(|| version.version_number.clone());
        out.push((CompatChange { title, from, to, because }, version, file));
    }
    out
}

// --- Update-Prüfung ------------------------------------------------------------------

/// Prüft angebotene Mod-Updates gegen die übrigen Mods: Ein Update, das die
/// Instanz kaputt machen würde, wird durch die neueste passende Version
/// ersetzt (oder fällt weg). Passt die Instanz schon jetzt nicht zusammen,
/// kommen Tausch-Vorschläge dazu. `updates`: Dateiname → angebotene Version.
/// Liefert Dateiname → (Version, Grund des Tauschs).
pub(crate) async fn vet_updates<L: VersionLookup>(
    lookup: &L,
    instance: &Instance,
    mut entries: Vec<Entry>,
    updates: HashMap<String, Version>,
) -> Result<HashMap<String, (Version, Option<String>)>> {
    let builtins = loader_builtins(instance);
    let untouched: HashMap<String, (Version, Option<String>)> =
        updates.iter().map(|(f, v)| (f.clone(), (v.clone(), None))).collect();
    if updates.is_empty() && find_conflicts(&entries, &builtins).is_empty() {
        return Ok(untouched);
    }
    for entry in &mut entries {
        let Some(update) = entry.file_name.as_ref().and_then(|f| updates.get(f)) else { continue };
        let mods = lookup.mod_info(update).await;
        // Unbekannter Inhalt: mit den alten Angaben weiterprüfen.
        if !mods.is_empty() {
            entry.mods = mods;
        }
        entry.version_id = Some(update.id.clone());
        entry.version = Some(update.clone());
    }
    settle(lookup, instance.overrides.channel(), &mut entries, &builtins, None).await?;
    // Deaktivierte Mods laufen nicht mit – deren Updates bleiben, wie sie sind.
    let checked: HashSet<&String> = entries.iter().filter_map(|e| e.file_name.as_ref()).collect();
    let mut out: HashMap<String, (Version, Option<String>)> =
        untouched.into_iter().filter(|(file, _)| !checked.contains(file)).collect();
    for entry in entries {
        let (Some(file), Some(version)) = (entry.file_name, entry.version) else { continue };
        if entry.installed.as_ref().is_some_and(|(id, _)| *id == version.id) {
            continue;
        }
        out.insert(file, (version, entry.because));
    }
    Ok(out)
}

#[cfg(test)]
mod tests;
