//! Launcher-Anbindung des Absturz-Helfers: nach einem Absturz die Mods der
//! Instanz einlesen, den passenden Crash-Report suchen, analysieren,
//! speichern (`instances/<id>/crashes/<crash-id>.json`), im Verlauf
//! vermerken und ans Frontend melden. Dazu die Behebungen, die nicht schon
//! als eigene Befehle existieren (RAM, Java, TRS Client).

use std::collections::BTreeSet;
use std::io::Read;
use std::path::{Path, PathBuf};

use chrono::{DateTime, Utc};
use futures::StreamExt;
use serde::Serialize;

use super::{CrashAnalysis, CrashInput, CrashKind, InstalledMod, analyze, parse};
use crate::content::{self, ContentKind};
use crate::gamelog::LogLine;
use crate::history::{self, HistoryEntry, HistoryKind};
use crate::instance::{InstanceStore, UpdateInstance, validate_id};
use crate::paths::Paths;
use crate::process::{EventSink, GameEvent};
use crate::settings::Settings;
use crate::{Error, Launcher, Result, client_mod, client_mod_update, fsutil, logfiles, modcompat, platform};

/// So viele Abstürze je Instanz bleiben gespeichert.
const MAX_CRASHES: usize = 30;
/// Crash-Reports größer als das werden nur angelesen.
const MAX_REPORT_BYTES: u64 = 4 << 20;
/// Log von der Platte (wenn nichts mitgelesen wurde): höchstens das Ende.
const MAX_LOG_BYTES: u64 = 4 << 20;
const MAX_PACKAGES: usize = 96;
const MAX_JAR_ENTRIES: usize = 60_000;
/// Crash-Reports, die bis zu so viele Sekunden vor dem Start entstanden, zählen nicht.
const REPORT_SLACK_SECONDS: i64 = 5;

/// Was das Spielende für die Analyse mitbringt (geht nicht ans Webview).
#[derive(Debug, Clone)]
pub struct CrashContext {
    pub crash_id: String,
    /// Die letzten Log-Zeilen (geschwärzt wie im Log-Tab).
    pub lines: Vec<LogLine>,
    pub started_at: DateTime<Utc>,
    pub exit_code: Option<i32>,
    pub play_seconds: u64,
}

impl CrashContext {
    /// Neue, zeitlich sortierbare ID (`20260927-094205-3fa1`).
    pub fn new_id(now: DateTime<Utc>) -> String {
        let salt = now.timestamp_subsec_nanos() & 0xffff;
        format!("{}-{salt:04x}", now.format("%Y%m%d-%H%M%S"))
    }
}

/// Für Listen (Verlauf, Logs-Tab).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CrashSummary {
    pub id: String,
    pub at: DateTime<Utc>,
    pub kind: CrashKind,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub variant: Option<String>,
    pub mods: Vec<String>,
}

pub fn validate_crash_id(id: &str) -> Result<()> {
    let ok = !id.is_empty() && id.len() <= 40 && id.bytes().all(|b| b.is_ascii_digit() || b.is_ascii_lowercase() || b == b'-');
    if ok { Ok(()) } else { Err(Error::validation(crate::msg!("crash.notFound", "Diesen Absturz gibt es nicht mehr."))) }
}

fn crashes_dir(paths: &Paths, instance_id: &str) -> PathBuf {
    paths.instance_dir(instance_id).join("crashes")
}

// --- Mods der Instanz ---------------------------------------------------------------

/// Pakete (bis 3 Ebenen) und Mixin-Konfigurationen eines Jars – nur das
/// Inhaltsverzeichnis wird gelesen.
fn jar_layout(path: &Path) -> (Vec<String>, Vec<String>) {
    let Ok(file) = std::fs::File::open(path) else { return (Vec::new(), Vec::new()) };
    let Ok(archive) = zip::ZipArchive::new(file) else { return (Vec::new(), Vec::new()) };
    let mut packages = BTreeSet::new();
    let mut configs = BTreeSet::new();
    for name in archive.file_names().take(MAX_JAR_ENTRIES) {
        if let Some((dir, file)) = name.rsplit_once('/') {
            if !file.ends_with(".class") || dir.starts_with("META-INF") {
                continue;
            }
            let segments: Vec<&str> = dir.split('/').take(3).collect();
            if segments.len() < 2 {
                continue;
            }
            let package = segments.join(".");
            // Mitgelieferte Bibliotheken (Gson, MixinExtras …) sagen nichts über die Mod.
            if !parse::is_framework_class(&format!("{package}.X")) && packages.len() < MAX_PACKAGES {
                packages.insert(package);
            }
        } else if name.ends_with(".json") && name.contains("mixins") && configs.len() < 32 {
            configs.insert(name.to_owned());
        }
    }
    (packages.into_iter().collect(), configs.into_iter().collect())
}

/// Alle Mods der Instanz (auch deaktivierte) mit IDs, Paketen und Mixin-Konfigurationen.
pub(crate) async fn installed_mods(paths: &Paths, instance_id: &str) -> Vec<InstalledMod> {
    let Ok(items) = content::list(paths, instance_id, ContentKind::Mod).await else { return Vec::new() };
    let with_paths: Vec<_> = items
        .into_iter()
        .filter_map(|i| content::existing_file(paths, instance_id, ContentKind::Mod, &i.file_name).map(|p| (i, p)))
        .collect();
    futures::stream::iter(with_paths.into_iter().map(|(item, path)| async move {
        let (mods, nested) = modcompat::remote::local_mod_details(path.clone()).await;
        let layout_path = path.clone();
        let (packages, mixin_configs) = tokio::task::spawn_blocking(move || jar_layout(&layout_path)).await.unwrap_or_default();
        let modified = tokio::fs::metadata(&path).await.ok().and_then(|m| m.modified().ok()).map(|t| DateTime::<Utc>::from(t).timestamp());
        let main = mods.first();
        InstalledMod {
            id: main.map(|m| m.id.clone()),
            provides: mods.iter().skip(1).map(|m| m.id.clone()).chain(mods.iter().flat_map(|m| m.provides.clone())).collect(),
            nested,
            name: item.title.clone().or_else(|| main.map(|m| m.display_name().to_owned())),
            version: main.map(|m| m.version.clone()).or(item.version.clone()),
            icon_url: item.icon_url.clone(),
            file_name: item.file_name,
            enabled: item.enabled,
            packages,
            mixin_configs,
            modified,
        }
    }))
    .buffered(8)
    .collect()
    .await
}

// --- Log und Crash-Report ---------------------------------------------------------

fn read_tail(path: &Path, max: u64) -> Option<String> {
    use std::io::{Seek, SeekFrom};
    let mut file = std::fs::File::open(path).ok()?;
    let len = file.metadata().ok()?.len();
    if len > max {
        file.seek(SeekFrom::Start(len - max)).ok()?;
    }
    let mut bytes = Vec::new();
    file.take(max).read_to_end(&mut bytes).ok()?;
    Some(String::from_utf8_lossy(&bytes).into_owned())
}

/// Neuester Crash-Report, der nach dem Start entstanden ist: (Quelle-ID, Text).
fn find_report(game_dir: &Path, since: DateTime<Utc>) -> Option<(String, String)> {
    let dir = game_dir.join("crash-reports");
    let newest = std::fs::read_dir(&dir)
        .ok()?
        .flatten()
        .filter(|e| e.file_type().is_ok_and(|t| t.is_file()))
        .filter_map(|e| {
            let name = e.file_name().to_str()?.to_owned();
            let modified = DateTime::<Utc>::from(e.metadata().ok()?.modified().ok()?);
            (name.ends_with(".txt") && modified.timestamp() >= since.timestamp() - REPORT_SLACK_SECONDS).then_some((modified, name))
        })
        .max()?;
    let text = read_tail(&dir.join(&newest.1), MAX_REPORT_BYTES)?;
    Some((format!("crash-reports/{}", newest.1), text))
}

/// Log von der Platte, wenn nichts mitgelesen wurde (z. B. nach einem Launcher-Neustart).
fn log_from_disk(paths: &Paths, instance_id: &str) -> Option<(String, String)> {
    let latest = paths.instance_game_dir(instance_id).join("logs").join("latest.log");
    let stdout = paths.instance_dir(instance_id).join("launcher-logs").join("launcher-stdout.log");
    [(latest, "logs/latest.log"), (stdout, "launcher/launcher-stdout.log")]
        .into_iter()
        .filter(|(p, _)| p.is_file())
        .max_by_key(|(p, _)| std::fs::metadata(p).and_then(|m| m.modified()).ok())
        .and_then(|(p, id)| Some((id.to_owned(), read_tail(&p, MAX_LOG_BYTES)?)))
}

/// Analysiert Log + Crash-Report mit dem Wissen über die Instanz.
async fn analyze_instance(paths: &Paths, instance_id: &str, log: &str, report: Option<&str>) -> Result<super::Analysis> {
    let instance = InstanceStore::new(paths.clone()).get(instance_id).await?;
    let settings = Settings::load(&paths.settings_file()).await.unwrap_or_default();
    let installed = installed_mods(paths, &instance.id).await;
    let input = CrashInput {
        log,
        report,
        installed: &installed,
        memory_mb: Some(instance.overrides.max_memory_mb.unwrap_or(settings.max_memory_mb)),
        system_memory_mb: platform::total_memory_mb(),
        custom_java: instance.overrides.java_path.is_some(),
    };
    Ok(analyze(&input))
}

async fn save(paths: &Paths, crash: &CrashAnalysis) -> Result<()> {
    let dir = crashes_dir(paths, &crash.instance_id);
    fsutil::write_json(&dir.join(format!("{}.json", crash.id)), crash).await?;
    // Nur die neuesten behalten (IDs sind nach Zeit sortierbar).
    let mut names: Vec<String> = std::fs::read_dir(&dir)
        .map(|r| r.flatten().filter_map(|e| e.file_name().to_str().map(str::to_owned)).filter(|n| n.ends_with(".json")).collect())
        .unwrap_or_default();
    names.sort();
    if names.len() > MAX_CRASHES {
        for old in &names[..names.len() - MAX_CRASHES] {
            let _ = std::fs::remove_file(dir.join(old));
        }
    }
    Ok(())
}

/// Nach einem Absturz im Hintergrund analysieren, speichern, im Verlauf
/// vermerken und `crashAnalyzed` melden. `false`, wenn keine Runtime läuft –
/// dann schreibt der Aufrufer den Verlaufseintrag selbst.
pub fn analyze_after_exit(paths: &Paths, sink: EventSink, instance_id: &str, ctx: CrashContext) -> bool {
    let Ok(handle) = tokio::runtime::Handle::try_current() else { return false };
    let (paths, instance_id) = (paths.clone(), instance_id.to_owned());
    handle.spawn(async move {
        let mut sources = Vec::new();
        let mut log: String = ctx.lines.iter().map(|l| l.message.as_str()).collect::<Vec<_>>().join("\n");
        if log.trim().is_empty() {
            let (p, i) = (paths.clone(), instance_id.clone());
            if let Ok(Some((id, text))) = tokio::task::spawn_blocking(move || log_from_disk(&p, &i)).await {
                sources.push(id);
                log = text;
            }
        } else {
            sources.push("live".to_owned());
        }
        let game_dir = paths.instance_game_dir(&instance_id);
        let started = ctx.started_at;
        let report = tokio::task::spawn_blocking(move || find_report(&game_dir, started)).await.ok().flatten();
        if let Some((id, _)) = &report {
            sources.insert(0, id.clone());
        }
        let analysis = match analyze_instance(&paths, &instance_id, &log, report.as_ref().map(|(_, t)| t.as_str())).await {
            Ok(a) => a,
            Err(e) => {
                tracing::warn!("Absturz von '{instance_id}' konnte nicht analysiert werden: {e}");
                history::record(&paths, &instance_id, HistoryEntry::new(HistoryKind::Crashed).seconds(ctx.play_seconds)).await;
                return;
            }
        };
        let crash = CrashAnalysis {
            id: ctx.crash_id.clone(),
            instance_id: instance_id.clone(),
            at: Utc::now(),
            exit_code: ctx.exit_code,
            play_seconds: Some(ctx.play_seconds),
            sources,
            analysis,
        };
        if let Err(e) = save(&paths, &crash).await {
            tracing::warn!("Absturz-Analyse konnte nicht gespeichert werden: {e}");
        }
        let entry = HistoryEntry::new(HistoryKind::Crashed)
            .seconds(ctx.play_seconds)
            .detail(crash.analysis.primary().kind.as_str())
            .crash(&crash.id);
        history::record(&paths, &instance_id, entry).await;
        sink(GameEvent::CrashAnalyzed { instance_id, crash: Box::new(crash) });
    });
    true
}

// --- Befehle ------------------------------------------------------------------------

impl Launcher {
    /// Gespeicherte Abstürze einer Instanz, neueste zuerst.
    pub async fn list_crashes(&self, instance_id: &str) -> Result<Vec<CrashSummary>> {
        let instance = self.instances().get(instance_id).await?;
        let dir = crashes_dir(self.paths(), &instance.id);
        let mut names: Vec<String> = std::fs::read_dir(&dir)
            .map(|r| r.flatten().filter_map(|e| e.file_name().to_str().map(str::to_owned)).filter(|n| n.ends_with(".json")).collect())
            .unwrap_or_default();
        names.sort_by(|a, b| b.cmp(a));
        let mut out = Vec::new();
        for name in names.into_iter().take(MAX_CRASHES) {
            let Ok(Some(crash)) = fsutil::read_json::<CrashAnalysis>(&dir.join(&name)).await else { continue };
            let primary = crash.analysis.primary();
            out.push(CrashSummary {
                id: crash.id.clone(),
                at: crash.at,
                kind: primary.kind,
                variant: primary.variant.clone(),
                mods: primary.mods.iter().filter_map(|id| crash.analysis.mods.iter().find(|m| &m.id == id)).map(|m| m.name.clone()).collect(),
            });
        }
        Ok(out)
    }

    pub async fn get_crash(&self, instance_id: &str, crash_id: &str) -> Result<CrashAnalysis> {
        let instance = self.instances().get(instance_id).await?;
        validate_crash_id(crash_id)?;
        let path = crashes_dir(self.paths(), &instance.id).join(format!("{crash_id}.json"));
        fsutil::read_json(&path)
            .await
            .ok()
            .flatten()
            .ok_or_else(|| Error::validation(crate::msg!("crash.notFound", "Diesen Absturz gibt es nicht mehr.")))
    }

    /// Eine Log-Datei bzw. einen Crash-Report aus dem Logs-Tab analysieren
    /// (wird nicht gespeichert).
    pub async fn analyze_log_source(&self, instance_id: &str, source: &str) -> Result<CrashAnalysis> {
        let instance = self.instances().get(instance_id).await?;
        let text = logfiles::read_source(self.paths(), &instance.id, source).await?.text;
        let is_report = source.starts_with("crash-reports/");
        let (log, report) = if is_report { ("", Some(text.as_str())) } else { (text.as_str(), None) };
        let analysis = analyze_instance(self.paths(), &instance.id, log, report).await?;
        Ok(CrashAnalysis {
            id: CrashContext::new_id(Utc::now()),
            instance_id: instance.id,
            at: Utc::now(),
            exit_code: None,
            play_seconds: None,
            sources: vec![source.to_owned()],
            analysis,
        })
    }

    /// „RAM erhöhen“: Arbeitsspeicher der Instanz setzen (mit Verlaufseintrag).
    pub async fn set_instance_memory(&self, instance_id: &str, mb: u32) -> Result<()> {
        crate::settings::validate_memory(mb)?;
        let instance = self.instances().get(instance_id).await?;
        let before = instance.overrides.max_memory_mb.unwrap_or(self.settings().await.max_memory_mb);
        let mut overrides = instance.overrides.clone();
        overrides.max_memory_mb = Some(mb);
        self.instances().update(&instance.id, UpdateInstance { name: instance.name.clone(), overrides }).await?;
        let entry = HistoryEntry::new(HistoryKind::SettingsChanged).detail("memory").from(format!("{before} MB")).to(format!("{mb} MB"));
        history::record(self.paths(), &instance.id, entry).await;
        Ok(())
    }

    /// „Java wechseln“: `major` installieren (Mojang-Runtime) und für die Instanz
    /// einstellen, `None` = eigenen Java-Pfad entfernen. Liefert die Java-Version.
    pub async fn switch_instance_java(
        &self,
        instance_id: &str,
        major: Option<u32>,
        on_progress: &(dyn Fn(crate::download::Progress) + Sync),
    ) -> Result<Option<u32>> {
        let instance = self.instances().get(instance_id).await?;
        validate_id(&instance.id)?;
        let path = match major {
            Some(major) => Some(self.install_java(major, on_progress).await?.display().to_string()),
            None => None,
        };
        let before = if instance.overrides.java_path.is_some() { "custom" } else { "auto" };
        let mut overrides = instance.overrides.clone();
        overrides.java_path = path;
        self.instances().update(&instance.id, UpdateInstance { name: instance.name.clone(), overrides }).await?;
        let after = major.map_or_else(|| "auto".to_owned(), |m| format!("Java {m}"));
        let entry = HistoryEntry::new(HistoryKind::SettingsChanged).detail("java").from(before).to(after);
        history::record(self.paths(), &instance.id, entry).await;
        Ok(major)
    }

    /// „TRS Client aktualisieren“: sofort im Update-Kanal nachsehen und die
    /// neueste Version in die Instanz legen. Liefert die installierte Version.
    pub async fn update_trs_client_now(&self, instance_id: &str) -> Result<Option<String>> {
        let instance = self.instances().get(instance_id).await?;
        let dir = self.bundled_client_mod_dir();
        let bundled = dir.as_deref().map(client_mod::load_manifest).unwrap_or_default();
        let bundled_version = Some(bundled.version.as_str()).filter(|v| !v.is_empty());
        if self.client_mod_updates.check_now(bundled_version).await == client_mod_update::CheckOutcome::Failed {
            tracing::warn!("TRS-Client-Update-Kanal nicht erreichbar – nehme, was da ist.");
        }
        let settings = self.settings().await;
        let trs_enabled = self.trs.enabled().await;
        client_mod::sync(&self.http, &self.paths, dir.as_deref(), Some(&self.client_mod_updates), &instance, &settings.ui, trs_enabled).await?;
        Ok(client_mod::installed_version(&self.paths, &instance.id).await)
    }
}
