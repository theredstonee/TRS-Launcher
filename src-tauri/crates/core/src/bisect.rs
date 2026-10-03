//! „Schuldige Mod finden“: binäre Suche über die aktivierten Mods einer Instanz.
//!
//! Je Runde bleibt etwa die Hälfte der Verdächtigen an – immer zusammen mit
//! allem, was sie zum Laden brauchen (`depends` aus `fabric.mod.json`,
//! `quilt.mod.json`, `mods.toml`, auch per Jar-in-Jar geliefert). Der TRS Client
//! und Loader-Bibliotheken (Fabric API, Kotlin …) bleiben immer an. Der Nutzer
//! sagt danach „Fehler noch da“ oder „läuft“ (ein Absturz zählt automatisch als
//! Fehler), bis eine Mod – oder eine untrennbare kleinste Gruppe – übrig ist.
//!
//! Der ursprüngliche Zustand ALLER Mod-Dateien steht in `bisect.json` im
//! Instanz-Ordner, bevor irgendetwas umbenannt wird. Am Ende, beim Abbrechen
//! oder nach einem Absturz des Launchers wird er genau so wiederhergestellt.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::path::PathBuf;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::content::{self, ContentKind, Source};
use crate::instance::validate_id;
use crate::modcompat::ModInfo;
use crate::paths::Paths;
use crate::{Error, Launcher, Result, fsutil};

const STATE_FILE: &str = "bisect.json";
const STATE_VERSION: u32 = 1;
/// So viele Mod-Dateien nimmt die Suche höchstens auf.
const MAX_MODS: usize = 2000;
const MIN_CANDIDATES: usize = 2;

/// Bibliotheken, die der Loader praktisch immer braucht – nie verdächtig.
const LOADER_LIBS: &[&str] = &[
    "fabric-api",
    "fabric",
    "quilted_fabric_api",
    "qsl",
    "fabric-language-kotlin",
    "fabric_language_kotlin",
    "fabric-language-scala",
    "kotlinforforge",
    "kotlinlangforge",
];

/// Immer nur eine Änderung gleichzeitig (Knopf doppelt geklickt, Absturz-Automatik).
static LOCK: Mutex<()> = Mutex::const_new(());

// --- Abhängigkeiten ----------------------------------------------------------------

/// Was eine Mod-Datei über sich sagt.
#[derive(Debug, Clone, Default)]
pub(crate) struct JarInfo {
    pub file: String,
    pub mods: Vec<ModInfo>,
    /// Mod-IDs eingebetteter Jars (liefern Abhängigkeiten mit).
    pub nested: Vec<String>,
}

/// Eine Mod-Datei im Graphen: welche anderen Dateien sie zum Laden braucht.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Node {
    pub file: String,
    #[serde(default)]
    pub requires: Vec<String>,
}

type Graph = BTreeMap<String, Vec<String>>;

/// Mod-IDs, die eine Datei liefert (eigene, `provides`, eingebettete).
fn provided_ids(jar: &JarInfo) -> HashSet<&str> {
    let mut ids: HashSet<&str> = jar.nested.iter().map(String::as_str).collect();
    for m in &jar.mods {
        ids.insert(m.id.as_str());
        ids.extend(m.provides.iter().map(String::as_str));
    }
    ids
}

/// Baut den Graphen: Datei → Dateien, die ihre Pflicht-Abhängigkeiten liefern.
/// Was keine Datei liefert (Minecraft, Loader, eingebaute Mods), zählt als da.
pub(crate) fn build_nodes(jars: &[JarInfo]) -> Vec<Node> {
    let mut providers: HashMap<&str, Vec<&str>> = HashMap::new();
    for jar in jars {
        for id in provided_ids(jar) {
            providers.entry(id).or_default().push(jar.file.as_str());
        }
    }
    let platform: HashSet<&str> = crate::modcompat::PLATFORM_IDS.iter().copied().collect();
    jars.iter()
        .map(|jar| {
            let own = provided_ids(jar);
            let mut requires: BTreeSet<String> = BTreeSet::new();
            for c in jar.mods.iter().flat_map(|m| &m.depends) {
                let id = c.id.as_str();
                if platform.contains(id) || own.contains(id) {
                    continue;
                }
                for file in providers.get(id).into_iter().flatten().filter(|f| **f != jar.file) {
                    requires.insert((*file).to_owned());
                }
            }
            Node { file: jar.file.clone(), requires: requires.into_iter().collect() }
        })
        .collect()
}

/// Alles, was `start` (transitiv) zum Laden braucht – einschließlich `start`.
fn closure<'a>(graph: &Graph, start: impl IntoIterator<Item = &'a String>) -> BTreeSet<String> {
    let mut out: BTreeSet<String> = BTreeSet::new();
    let mut stack: Vec<&String> = start.into_iter().collect();
    while let Some(file) = stack.pop() {
        if !out.insert(file.clone()) {
            continue;
        }
        stack.extend(graph.get(file).into_iter().flatten());
    }
    out
}

/// Alles, was (transitiv) von `of` abhängt – einschließlich `of`.
fn dependents(graph: &Graph, of: &BTreeSet<String>) -> BTreeSet<String> {
    let mut out = of.clone();
    loop {
        let before = out.len();
        for (file, requires) in graph {
            if requires.iter().any(|r| out.contains(r)) {
                out.insert(file.clone());
            }
        }
        if out.len() == before {
            return out;
        }
    }
}

/// TRS Client und Loader-Bibliotheken samt ihren Abhängigkeiten.
fn protected_files(jars: &[JarInfo], graph: &Graph) -> BTreeSet<String> {
    let roots: Vec<&String> = jars
        .iter()
        .filter(|j| {
            crate::client_mod::is_client_mod_file(ContentKind::Mod, &j.file)
                || provided_ids(j).iter().any(|id| LOADER_LIBS.contains(id))
        })
        .map(|j| &j.file)
        .collect();
    closure(graph, roots)
}

fn ceil_log2(n: usize) -> u32 {
    if n <= 1 { 0 } else { usize::BITS - (n - 1).leading_zeros() }
}

// --- Zustand -----------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RoundLog {
    pub round: u32,
    /// Verdächtige, die in dieser Runde an waren.
    pub tested: usize,
    pub suspects: usize,
    pub failed: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub(crate) enum Outcome {
    /// Eine Mod – oder eine kleinste Gruppe, die sich nicht weiter trennen lässt.
    Found { files: Vec<String> },
    /// Ohne jede Verdächtige trat der Fehler nicht mehr auf.
    NotFound,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BisectState {
    pub version: u32,
    pub instance_id: String,
    pub started_at: DateTime<Utc>,
    /// Ursprünglicher Zustand aller Mod-Dateien (Datei → aktiviert).
    pub original: BTreeMap<String, bool>,
    /// Ursprünglich aktivierte Mods mit ihren Abhängigkeiten.
    pub nodes: Vec<Node>,
    /// Bleibt immer an (TRS Client, Loader-Bibliotheken).
    pub protected: BTreeSet<String>,
    pub suspects: BTreeSet<String>,
    /// Ohne Fehler gelaufen – bleiben an.
    #[serde(default)]
    pub innocent: BTreeSet<String>,
    /// Für den Fehler nicht nötig – bleiben aus.
    #[serde(default)]
    pub cleared: BTreeSet<String>,
    /// Frühere Funde („Weitersuchen“) samt allem, was sie braucht – bleiben aus.
    #[serde(default)]
    pub excluded: BTreeSet<String>,
    /// Verdächtige, die in dieser Runde an sind.
    #[serde(default)]
    pub testing: BTreeSet<String>,
    #[serde(default)]
    pub rounds: Vec<RoundLog>,
    #[serde(default)]
    pub outcome: Option<Outcome>,
    /// Trat der Fehler mit genau den jetzigen Verdächtigen auf? Sonst wird vor
    /// dem Fund noch einmal bestätigt (sonst gäbe es nie „nicht gefunden“).
    #[serde(default)]
    pub confirmed: bool,
}

fn too_few() -> Error {
    Error::validation(crate::msg!(
        "bisect.tooFewMods",
        "Für die Suche braucht es mindestens zwei aktivierte Mods (außer dem TRS Client und Bibliotheken)."
    ))
}

fn not_running() -> Error {
    Error::validation(crate::msg!("bisect.notRunning", "Für diese Instanz läuft keine Fehlersuche."))
}

impl BisectState {
    /// Neue Suche über alle aktivierten Mods (`original`: alle Mod-Dateien).
    pub(crate) fn new(instance_id: &str, original: BTreeMap<String, bool>, jars: &[JarInfo], now: DateTime<Utc>) -> Result<Self> {
        let jars: Vec<JarInfo> = jars.iter().filter(|j| original.get(&j.file) == Some(&true)).cloned().collect();
        let nodes = build_nodes(&jars);
        let graph: Graph = nodes.iter().map(|n| (n.file.clone(), n.requires.clone())).collect();
        let protected = protected_files(&jars, &graph);
        let suspects: BTreeSet<String> = graph.keys().filter(|f| !protected.contains(*f)).cloned().collect();
        if suspects.len() < MIN_CANDIDATES {
            return Err(too_few());
        }
        let mut state = Self {
            version: STATE_VERSION,
            instance_id: instance_id.to_owned(),
            started_at: now,
            original,
            nodes,
            protected,
            suspects,
            innocent: BTreeSet::new(),
            cleared: BTreeSet::new(),
            excluded: BTreeSet::new(),
            testing: BTreeSet::new(),
            rounds: Vec::new(),
            outcome: None,
            confirmed: false,
        };
        state.plan();
        Ok(state)
    }

    fn graph(&self) -> Graph {
        self.nodes.iter().map(|n| (n.file.clone(), n.requires.clone())).collect()
    }

    /// Alle Mods, über die gesucht wird (ohne TRS Client und Bibliotheken).
    fn candidates(&self) -> BTreeSet<String> {
        self.nodes.iter().map(|n| &n.file).filter(|f| !self.protected.contains(*f)).cloned().collect()
    }

    /// Übrig sind nur noch untrennbare Verdächtige: Fund – oder erst bestätigen.
    fn settle(&mut self) {
        if self.confirmed {
            self.outcome = Some(Outcome::Found { files: self.suspects.iter().cloned().collect() });
        } else {
            self.testing = self.suspects.clone();
        }
    }

    /// Wählt die Verdächtigen der nächsten Runde – oder beendet die Suche.
    fn plan(&mut self) {
        self.testing.clear();
        match self.suspects.len() {
            0 => {
                self.outcome = Some(Outcome::NotFound);
                return;
            }
            1 => {
                self.settle();
                return;
            }
            _ => {}
        }
        let graph = self.graph();
        // Was ohnehin an ist (Unschuldige, TRS Client …), bringt seine Abhängigkeiten mit.
        let base = closure(&graph, self.innocent.iter().chain(&self.protected));
        let forced: BTreeSet<String> = base.intersection(&self.suspects).cloned().collect();
        // Je freie Verdächtige: welche Verdächtigen sie mitbringt – kleinste zuerst.
        let mut options: Vec<(BTreeSet<String>, &String)> = self
            .suspects
            .iter()
            .filter(|f| !forced.contains(*f))
            .map(|f| (closure(&graph, [f]).intersection(&self.suspects).cloned().collect(), f))
            .collect();
        options.sort_by(|a, b| a.0.len().cmp(&b.0.len()).then_with(|| a.1.cmp(b.1)));
        let target = self.suspects.len().div_ceil(2);
        let mut on = forced;
        for (brings, _) in &options {
            if on.union(brings).count() <= target {
                on.extend(brings.iter().cloned());
            }
        }
        if on.is_empty()
            && let Some((brings, _)) = options.first()
        {
            on.clone_from(brings);
        }
        if on.len() >= self.suspects.len() {
            // Nicht weiter trennbar (z. B. gegenseitige Abhängigkeiten): kleinste Gruppe.
            self.settle();
            return;
        }
        self.testing = on;
    }

    /// Was in dieser Runde an ist: Verdächtige der Runde, Unschuldige, Geschützte – samt Abhängigkeiten.
    fn enabled_set(&self, graph: &Graph) -> BTreeSet<String> {
        closure(graph, self.testing.iter().chain(&self.innocent).chain(&self.protected))
    }

    /// Ziel-Zustand der Mod-Dateien: während der Suche die Runde, danach das Original.
    pub(crate) fn desired(&self) -> BTreeMap<String, bool> {
        let mut out = self.original.clone();
        if self.outcome.is_some() {
            return out;
        }
        let enabled = self.enabled_set(&self.graph());
        for file in self.candidates() {
            out.insert(file.clone(), enabled.contains(&file));
        }
        out
    }

    /// Laufende Runde (1-basiert).
    pub(crate) fn round(&self) -> u32 {
        u32::try_from(self.rounds.len()).unwrap_or(u32::MAX).saturating_add(1)
    }

    /// Ergebnis der Runde `round` eintragen und die nächste planen.
    pub(crate) fn record(&mut self, round: u32, failed: bool) -> Result<()> {
        if self.outcome.is_some() || self.testing.is_empty() || round != self.round() {
            return Err(Error::validation(crate::msg!(
                "bisect.staleRound",
                "Diese Runde ist schon beantwortet – bitte neu laden."
            )));
        }
        let tested: BTreeSet<String> = self.enabled_set(&self.graph()).intersection(&self.suspects).cloned().collect();
        self.rounds.push(RoundLog { round, tested: tested.len(), suspects: self.suspects.len(), failed });
        self.confirmed = failed;
        if failed {
            // Der Fehler kam mit genau diesen Mods – der Rest ist nicht nötig.
            self.cleared.extend(self.suspects.difference(&tested).cloned());
            self.suspects = tested;
        } else {
            self.innocent.extend(tested.iter().cloned());
            self.suspects.retain(|f| !tested.contains(f));
        }
        self.plan();
        Ok(())
    }

    /// Nach einem Fund weitersuchen: der Fund (und was ihn braucht) bleibt aus,
    /// alle übrigen Mods sind wieder verdächtig.
    pub(crate) fn continue_search(&mut self) -> Result<()> {
        let Some(Outcome::Found { files }) = &self.outcome else { return Err(not_running()) };
        let found: BTreeSet<String> = files.iter().cloned().collect();
        let graph = self.graph();
        self.excluded.extend(dependents(&graph, &found));
        // Was der TRS Client braucht, bleibt trotzdem an.
        self.excluded.retain(|f| !self.protected.contains(f));
        self.suspects = self.candidates().into_iter().filter(|f| !self.excluded.contains(f)).collect();
        self.innocent.clear();
        self.cleared.clear();
        self.outcome = None;
        self.confirmed = false;
        self.plan();
        Ok(())
    }

    fn estimated_rounds(&self) -> u32 {
        let done = u32::try_from(self.rounds.len()).unwrap_or(u32::MAX);
        if self.outcome.is_some() { done } else { done.saturating_add(ceil_log2(self.suspects.len()).max(1)) }
    }
}

// --- Speichern und Umschalten -------------------------------------------------------

fn state_path(paths: &Paths, instance_id: &str) -> PathBuf {
    paths.instance_dir(instance_id).join(STATE_FILE)
}

async fn load(paths: &Paths, instance_id: &str) -> Result<Option<BisectState>> {
    validate_id(instance_id)?;
    match fsutil::read_json::<BisectState>(&state_path(paths, instance_id)).await {
        Ok(state) => Ok(state.filter(|s| s.version == STATE_VERSION && s.instance_id == instance_id)),
        Err(e) => {
            tracing::warn!("Fehlersuche von '{instance_id}' nicht lesbar: {e}");
            Ok(None)
        }
    }
}

async fn save(paths: &Paths, state: &BisectState) -> Result<()> {
    fsutil::write_json(&state_path(paths, &state.instance_id), state).await
}

async fn remove(paths: &Paths, instance_id: &str) -> Result<()> {
    let path = state_path(paths, instance_id);
    match tokio::fs::remove_file(&path).await {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(Error::io(&path, e)),
        _ => Ok(()),
    }
}

/// Schaltet die Mod-Dateien wie gewünscht um (ohne Verlaufseinträge).
/// Verschwundene Dateien werden übersprungen.
async fn apply(paths: &Paths, instance_id: &str, desired: &BTreeMap<String, bool>) -> Result<()> {
    let mut failed = 0usize;
    for (file, enabled) in desired {
        if content::existing_file(paths, instance_id, ContentKind::Mod, file).is_none() {
            continue;
        }
        if let Err(e) = content::rename_enabled(paths, instance_id, ContentKind::Mod, file, *enabled).await {
            tracing::warn!("Fehlersuche: {file} nicht umgeschaltet: {e}");
            failed += 1;
        }
    }
    if failed > 0 {
        return Err(Error::launch(crate::msg!(
            "bisect.applyFailed",
            "{count} Mods konnten nicht umgeschaltet werden. Läuft das Spiel noch?",
            count = failed
        )));
    }
    Ok(())
}

/// Liest Mod-Infos aller aktivierten Mod-Dateien und startet die Suche.
pub(crate) async fn start(paths: &Paths, instance_id: &str) -> Result<BisectState> {
    validate_id(instance_id)?;
    let _guard = LOCK.lock().await;
    if load(paths, instance_id).await?.is_some() {
        return Err(Error::validation(crate::msg!("bisect.alreadyRunning", "Für diese Instanz läuft schon eine Fehlersuche.")));
    }
    let items = content::list(paths, instance_id, ContentKind::Mod).await?;
    if items.len() > MAX_MODS {
        return Err(Error::validation(crate::msg!("bisect.tooManyMods", "Zu viele Mods für die Suche (höchstens {max}).", max = MAX_MODS)));
    }
    let original: BTreeMap<String, bool> = items.iter().map(|i| (i.file_name.clone(), i.enabled)).collect();
    let mut jars = Vec::new();
    for item in items.iter().filter(|i| i.enabled) {
        let Some(path) = content::existing_file(paths, instance_id, ContentKind::Mod, &item.file_name) else { continue };
        let (mods, nested) = crate::modcompat::remote::local_mod_details(path).await;
        jars.push(JarInfo { file: item.file_name.clone(), mods, nested });
    }
    let state = BisectState::new(instance_id, original, &jars, Utc::now())?;
    // Erst den Ursprung sichern, dann umbenennen – so lässt sich alles wiederherstellen.
    save(paths, &state).await?;
    if let Err(e) = apply(paths, instance_id, &state.desired()).await {
        if apply(paths, instance_id, &state.original).await.is_ok() {
            let _ = remove(paths, instance_id).await;
        }
        return Err(e);
    }
    Ok(state)
}

/// Antwort auf Runde `round`: `failed` = Fehler trat noch auf (oder Absturz).
pub(crate) async fn answer(paths: &Paths, instance_id: &str, round: u32, failed: bool) -> Result<BisectState> {
    let _guard = LOCK.lock().await;
    let mut state = load(paths, instance_id).await?.ok_or_else(not_running)?;
    state.record(round, failed)?;
    save(paths, &state).await?;
    apply(paths, instance_id, &state.desired()).await?;
    Ok(state)
}

/// Nach einem Fund weitersuchen.
pub(crate) async fn continue_search(paths: &Paths, instance_id: &str) -> Result<BisectState> {
    let _guard = LOCK.lock().await;
    let mut state = load(paths, instance_id).await?.ok_or_else(not_running)?;
    state.continue_search()?;
    save(paths, &state).await?;
    apply(paths, instance_id, &state.desired()).await?;
    Ok(state)
}

/// Beendet die Suche (auch Abbrechen): stellt den ursprünglichen Zustand genau
/// wieder her; mit `disable_result` danach die gefundenen Mods aus.
/// Schlägt das Wiederherstellen fehl, bleibt der Zustand gespeichert.
pub(crate) async fn finish(paths: &Paths, instance_id: &str, disable_result: bool) -> Result<()> {
    let _guard = LOCK.lock().await;
    let Some(state) = load(paths, instance_id).await? else { return Ok(()) };
    apply(paths, instance_id, &state.original).await?;
    remove(paths, instance_id).await?;
    if disable_result && let Some(Outcome::Found { files }) = &state.outcome {
        for file in files {
            if let Err(e) = content::set_enabled(paths, instance_id, ContentKind::Mod, file, false).await {
                tracing::warn!("Fehlersuche: {file} nicht deaktiviert: {e}");
            }
        }
    }
    Ok(())
}

// --- Ansicht ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum BisectPhase {
    Testing,
    Found,
    NotFound,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BisectMod {
    pub file_name: String,
    pub title: Option<String>,
    pub icon_url: Option<String>,
    pub source: Option<Source>,
    pub slug: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BisectView {
    pub instance_id: String,
    pub started_at: DateTime<Utc>,
    /// Laufende Runde (1-basiert); nach dem Ende die Zahl der Runden.
    pub round: u32,
    /// Geschätzte Rundenzahl insgesamt („Runde 2 von ~5“).
    pub estimated_rounds: u32,
    pub phase: BisectPhase,
    /// Mods, über die gesucht wird.
    pub total: usize,
    /// Noch verdächtig.
    pub suspects: Vec<BisectMod>,
    /// Davon in dieser Runde an.
    pub testing: usize,
    /// Mods, die in dieser Runde aus sind.
    pub disabled: usize,
    pub result: Vec<BisectMod>,
}

async fn view(paths: &Paths, state: &BisectState) -> BisectView {
    let items = content::list(paths, &state.instance_id, ContentKind::Mod).await.unwrap_or_default();
    let by_file: HashMap<&str, &content::ContentItem> = items.iter().map(|i| (i.file_name.as_str(), i)).collect();
    let describe = |file: &String, icon: bool| {
        let item = by_file.get(file.as_str());
        BisectMod {
            file_name: file.clone(),
            title: item.and_then(|i| i.title.clone()),
            icon_url: item.and_then(|i| i.icon_url.clone()).filter(|_| icon),
            source: item.and_then(|i| i.source.clone()),
            slug: item.and_then(|i| i.slug.clone()),
        }
    };
    let (phase, result) = match &state.outcome {
        None => (BisectPhase::Testing, Vec::new()),
        Some(Outcome::NotFound) => (BisectPhase::NotFound, Vec::new()),
        Some(Outcome::Found { files }) => (BisectPhase::Found, files.iter().map(|f| describe(f, true)).collect()),
    };
    let desired = state.desired();
    let candidates = state.candidates();
    BisectView {
        instance_id: state.instance_id.clone(),
        started_at: state.started_at,
        round: if state.outcome.is_some() { u32::try_from(state.rounds.len()).unwrap_or(u32::MAX) } else { state.round() },
        estimated_rounds: state.estimated_rounds(),
        phase,
        total: candidates.len(),
        suspects: if state.outcome.is_some() { Vec::new() } else { state.suspects.iter().map(|f| describe(f, false)).collect() },
        testing: state.testing.len(),
        disabled: if state.outcome.is_some() { 0 } else { candidates.iter().filter(|f| desired.get(*f) == Some(&false)).count() },
        result,
    }
}

impl Launcher {
    fn bisect_idle(&self, instance_id: &str) -> Result<()> {
        if self.games().is_running(instance_id) || self.is_preparing(instance_id) {
            return Err(Error::launch(crate::msg!("bisect.gameRunning", "Bitte beende zuerst das Spiel.")));
        }
        Ok(())
    }

    pub async fn bisect_status(&self, instance_id: &str) -> Result<Option<BisectView>> {
        let instance = self.instances().get(instance_id).await?;
        Ok(match load(self.paths(), &instance.id).await? {
            Some(state) => Some(view(self.paths(), &state).await),
            None => None,
        })
    }

    /// Alle laufenden Suchen – auch nach einem Neustart des Launchers (zum Fortsetzen oder Wiederherstellen).
    pub async fn bisect_active(&self) -> Result<Vec<BisectView>> {
        let mut out = Vec::new();
        for instance in self.instances().list().await? {
            if let Some(state) = load(self.paths(), &instance.id).await? {
                out.push(view(self.paths(), &state).await);
            }
        }
        Ok(out)
    }

    pub async fn bisect_start(&self, instance_id: &str) -> Result<BisectView> {
        let instance = self.instances().get(instance_id).await?;
        self.bisect_idle(&instance.id)?;
        let state = start(self.paths(), &instance.id).await?;
        Ok(view(self.paths(), &state).await)
    }

    pub async fn bisect_answer(&self, instance_id: &str, round: u32, failed: bool) -> Result<BisectView> {
        let instance = self.instances().get(instance_id).await?;
        self.bisect_idle(&instance.id)?;
        let state = answer(self.paths(), &instance.id, round, failed).await?;
        Ok(view(self.paths(), &state).await)
    }

    pub async fn bisect_continue(&self, instance_id: &str) -> Result<BisectView> {
        let instance = self.instances().get(instance_id).await?;
        self.bisect_idle(&instance.id)?;
        let state = continue_search(self.paths(), &instance.id).await?;
        Ok(view(self.paths(), &state).await)
    }

    /// Beenden oder Abbrechen – stellt immer den Ursprung wieder her.
    pub async fn bisect_finish(&self, instance_id: &str, disable_result: bool) -> Result<()> {
        let instance = self.instances().get(instance_id).await?;
        self.bisect_idle(&instance.id)?;
        finish(self.paths(), &instance.id, disable_result).await
    }
}

#[cfg(test)]
#[path = "bisect_tests.rs"]
mod tests;
