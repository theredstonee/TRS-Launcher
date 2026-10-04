//! Absturz-Helfer: liest nach einem Absturz Log und Crash-Report und erklärt,
//! was los ist – mit Vorschlägen, die der Launcher selbst ausführen kann
//! (Mod deaktivieren, Abhängigkeit installieren, RAM erhöhen …).
//!
//! Die Erkennung ist eine reine Regel-Engine ([`analyze`]): Text rein,
//! Befunde raus. Alles läuft lokal, nichts wird gesendet. Texte für den
//! Nutzer entstehen erst im Frontend aus `kind`/`variant`/`params`.
//! Auszüge, die angezeigt werden, sind maskiert (Pfade mit Benutzernamen,
//! Spielername, Tokens) – wie beim Log-Teilen.
//!
//! * [`parse`]: Log-Zeilen normalisieren, Mod-Listen, Ausnahmen, Stackzeilen.
//! * [`rules`]: die Regeln (bekannte Kombi-Probleme, Mixin, Abhängigkeiten …).
//! * [`store`]: Launcher-Anbindung – Mods der Instanz einlesen, Crash-Report
//!   finden, Ergebnis speichern (`instances/<id>/crashes/`), Behebungen.

mod parse;
mod rules;
mod store;
#[cfg(test)]
mod tests;

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

pub use store::{CrashContext, CrashSummary, analyze_after_exit, validate_crash_id};

/// Art eines Befunds. Reihenfolge = grobe Wichtigkeit (siehe `score`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CrashKind {
    /// Bekanntes Kombi-Problem (z. B. Sodium + OptiFine, TRS Client 0.9.0 + Essential).
    KnownIssue,
    DuplicateMod,
    WrongGameVersion,
    WrongLoaderVersion,
    IncompatibleMod,
    MissingDependency,
    WrongJava,
    OutOfMemory,
    CorruptFiles,
    GraphicsDriver,
    MixinConflict,
    Unknown,
}

impl CrashKind {
    /// Kurzname wie im Verlauf (`history.json` → `detail`).
    pub fn as_str(self) -> &'static str {
        match self {
            Self::KnownIssue => "known_issue",
            Self::DuplicateMod => "duplicate_mod",
            Self::WrongGameVersion => "wrong_game_version",
            Self::WrongLoaderVersion => "wrong_loader_version",
            Self::IncompatibleMod => "incompatible_mod",
            Self::MissingDependency => "missing_dependency",
            Self::WrongJava => "wrong_java",
            Self::OutOfMemory => "out_of_memory",
            Self::CorruptFiles => "corrupt_files",
            Self::GraphicsDriver => "graphics_driver",
            Self::MixinConflict => "mixin_conflict",
            Self::Unknown => "unknown",
        }
    }
}

/// Was der Launcher auf Knopfdruck tun kann. Jede Änderung bestätigt der
/// Nutzer vorher; sie landet im Verlauf der Instanz.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum CrashAction {
    /// Dateien im Mods-Ordner zu `.disabled` umbenennen (rückgängig machbar).
    DisableMods { files: Vec<String> },
    /// Fehlende Mods über Modrinth installieren (bestehende Installation).
    InstallDependencies { declarer: Option<String>, dependencies: Vec<String> },
    /// Ältere Kopien derselben Mod deaktivieren, `keep` bleibt.
    RemoveDuplicates { files: Vec<String>, keep: Vec<String> },
    /// Arbeitsspeicher der Instanz ändern.
    SetMemory { from_mb: u32, to_mb: u32 },
    /// Java wechseln: `major` = diese Hauptversion installieren und für die
    /// Instanz einstellen; `None` = eigenen Java-Pfad entfernen (automatisch).
    SwitchJava { major: Option<u32> },
    /// TRS Client sofort aus dem Update-Kanal holen.
    UpdateTrsClient,
    /// Unverträgliche Version gegen eine passende tauschen (modcompat).
    FixConflict { mod_id: String },
    /// „Dateien prüfen & reparieren“.
    Repair,
}

/// Ein Befund einer Regel.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Finding {
    pub kind: CrashKind,
    /// Genauere Art für den Text, z. B. `trsclient_essential`, `amd`, `reserve`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub variant: Option<String>,
    /// 0–100: Wie sicher/wichtig. Der höchste ist die Hauptursache.
    pub score: u8,
    /// Werte für die Übersetzung (`{name}`, `{required}` …).
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub params: BTreeMap<String, String>,
    /// Beteiligte Mods (IDs aus [`CrashAnalysis::mods`]).
    #[serde(default)]
    pub mods: Vec<String>,
    /// Die Log-Zeilen, an denen die Regel es erkannt hat (maskiert).
    #[serde(default)]
    pub evidence: Vec<String>,
    #[serde(default)]
    pub actions: Vec<CrashAction>,
}

impl Finding {
    fn new(kind: CrashKind, score: u8) -> Self {
        Self { kind, variant: None, score, params: BTreeMap::new(), mods: Vec::new(), evidence: Vec::new(), actions: Vec::new() }
    }

    fn variant(mut self, variant: &str) -> Self {
        self.variant = Some(variant.to_owned());
        self
    }

    fn param(mut self, key: &str, value: impl Into<String>) -> Self {
        let value: String = value.into();
        if !value.is_empty() {
            self.params.insert(key.to_owned(), parse::clip(&value, 120));
        }
        self
    }

    fn with_mods(mut self, ids: impl IntoIterator<Item = String>) -> Self {
        for id in ids {
            if !self.mods.contains(&id) {
                self.mods.push(id);
            }
        }
        self
    }

    fn evidence(mut self, line: &str) -> Self {
        let line = parse::mask(line);
        if self.evidence.len() < MAX_EVIDENCE && !line.is_empty() && !self.evidence.contains(&line) {
            self.evidence.push(line);
        }
        self
    }

    fn action(mut self, action: CrashAction) -> Self {
        if !self.actions.contains(&action) {
            self.actions.push(action);
        }
        self
    }
}

const MAX_EVIDENCE: usize = 4;

/// Eine Mod, die im Befund vorkommt – mit Name/Symbol aus der Instanz.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModRef {
    pub id: String,
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    /// Datei im Mods-Ordner (ohne `.disabled`), wenn zuordenbar.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub file: Option<String>,
    #[serde(default)]
    pub enabled: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon_url: Option<String>,
    /// Steckt in einer anderen Mod (Jar-in-Jar), z. B. MixinExtras in Essential.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bundled_in: Option<String>,
}

/// Eine Mod-Datei der Instanz, wie die Engine sie braucht.
#[derive(Debug, Clone, Default)]
pub struct InstalledMod {
    /// Dateiname ohne `.disabled`.
    pub file_name: String,
    pub enabled: bool,
    /// Haupt-Mod-ID aus dem Jar (`fabric.mod.json`, `mods.toml`).
    pub id: Option<String>,
    /// Weitere IDs (`provides`, weitere Mods im selben Jar).
    pub provides: Vec<String>,
    /// IDs eingebetteter Jars (Jar-in-Jar).
    pub nested: Vec<String>,
    pub name: Option<String>,
    pub version: Option<String>,
    pub icon_url: Option<String>,
    /// Java-Pakete (bis 3 Ebenen, z. B. `gg.essential.loader`).
    pub packages: Vec<String>,
    /// Mixin-Konfigurationen im Jar (`sodium.mixins.json`).
    pub mixin_configs: Vec<String>,
    /// Änderungszeit (Unix-Sekunden) – bei Duplikaten gewinnt sonst die neuere.
    pub modified: Option<i64>,
}

impl InstalledMod {
    fn ids(&self) -> impl Iterator<Item = &str> {
        self.id.iter().chain(self.provides.iter()).map(String::as_str)
    }
}

/// Eingabe der Engine.
#[derive(Debug, Clone, Default)]
pub struct CrashInput<'a> {
    /// Log-Text (Zeilen, gern mit `[12:00:00] [main/INFO]: `-Präfix).
    pub log: &'a str,
    /// Crash-Report (`crash-reports/*.txt`), falls einer zum Absturz gehört.
    pub report: Option<&'a str>,
    pub installed: &'a [InstalledMod],
    /// Eingestellter Arbeitsspeicher der Instanz (MB).
    pub memory_mb: Option<u32>,
    /// Arbeitsspeicher des PCs (MB).
    pub system_memory_mb: Option<u32>,
    /// Die Instanz hat einen eigenen Java-Pfad.
    pub custom_java: bool,
    /// Minecraft-Version der Instanz (sonst aus der Mod-Liste im Log).
    pub game_version: Option<&'a str>,
}

/// Ergebnis der Engine (ohne Instanz-Daten).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Analysis {
    /// Nach `score` absteigend; nie leer (Rückfall: [`CrashKind::Unknown`]).
    pub findings: Vec<Finding>,
    pub mods: Vec<ModRef>,
    /// Hauptursache (letztes „Caused by“ bzw. Beschreibung), maskiert.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cause: Option<String>,
    /// Erste Stackzeile, die nicht zu Java/Minecraft/Loader gehört, maskiert.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub first_frame: Option<String>,
    /// Die wichtigsten Zeilen (maskiert) für „Details“.
    #[serde(default)]
    pub excerpt: Vec<String>,
}

impl Analysis {
    pub fn primary(&self) -> &Finding {
        &self.findings[0]
    }
}

/// Ein gespeicherter Absturz einer Instanz (`instances/<id>/crashes/<id>.json`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CrashAnalysis {
    pub id: String,
    pub instance_id: String,
    pub at: chrono::DateTime<chrono::Utc>,
    #[serde(default)]
    pub exit_code: Option<i32>,
    #[serde(default)]
    pub play_seconds: Option<u64>,
    /// Woraus analysiert wurde (Log-Quelle wie im Logs-Tab, z. B. `crash-reports/…`).
    #[serde(default)]
    pub sources: Vec<String>,
    #[serde(flatten)]
    pub analysis: Analysis,
}

/// Wertet Log und Crash-Report aus. Rein – ohne Dateizugriffe.
pub fn analyze(input: &CrashInput<'_>) -> Analysis {
    let text = parse::Text::new(input.log, input.report);
    let index = parse::ModIndex::new(input.installed, &text);
    let mut findings = rules::run(input, &text, &index);
    findings.sort_by(|a, b| b.score.cmp(&a.score).then(a.kind.cmp(&b.kind)));
    // Gleiche Art + gleiche Mods nur einmal (die stärkste bleibt).
    let mut seen = Vec::new();
    findings.retain(|f| {
        let key = (f.kind, f.variant.clone(), f.mods.clone());
        let fresh = !seen.contains(&key);
        seen.push(key);
        fresh
    });
    findings.truncate(6);

    let cause = text.root_cause().map(parse::mask);
    let own_frame = text.first_own_frame();
    if findings.is_empty() {
        findings.push(rules::unknown(&text, &index, own_frame.as_ref()));
    }
    let first_frame = own_frame.map(|f| parse::mask(&f.line));
    let excerpt = text.excerpt();

    let mut ids: Vec<String> = Vec::new();
    for f in &findings {
        for id in &f.mods {
            if !ids.contains(id) {
                ids.push(id.clone());
            }
        }
    }
    let mods = ids.iter().map(|id| index.resolve(id)).collect();
    Analysis { findings, mods, cause, first_frame, excerpt }
}
