//! Instanz als Minecraft-Server exportieren: Server-Jar bzw. Modloader, die
//! Mods für den Server (reine Client-Mods bleiben weg), auf Wunsch Configs und
//! eine Welt, `server.properties`, Startskripte und eine Anleitung – als ZIP
//! und/oder als lokaler Server unter `<root>/servers/<id>/`.
//!
//! Gebaut wird immer erst in einem Zwischenordner `servers/.staging-<uuid>`;
//! danach wird gepackt und/oder umbenannt. Bei Fehler oder Abbruch bleibt nichts liegen.

pub mod files;
mod install;
pub mod sides;
#[cfg(test)]
mod tests;

use std::collections::{HashMap, HashSet};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use serde::{Deserialize, Serialize};

use self::files::{ReadmeInfo, ServerProperties};
use self::sides::{ModSide, SideSignals, SideSource};
use crate::instance::{LoaderKind, validate_id};
use crate::local_servers::{self, LocalServerInfo, ServerMeta};
use crate::meta::version::VersionInfo;
use crate::{Error, Launcher, Result, java, meta, modpack_export, modrinth, worlds};

/// Höchstens so viele Mods werden angesehen.
const MAX_MODS: usize = 1_500;
/// Configs, die mitkommen können (sofern vorhanden).
pub const CONFIG_DIRS: &[&str] = &["config", "defaultconfigs", "kubejs", "scripts"];
const MAX_COPY_FILES: usize = 100_000;
/// Java für Installer, wenn das Spiel älteres Java braucht (alte Installer scheitern mit 8u51).
const INSTALLER_COMPONENT: &str = "java-runtime-delta";
const INSTALLER_MAJOR: u32 = 21;
/// Nach so viel Zeit wird ein Server beim Beenden des Launchers hart beendet.
pub const SHUTDOWN_GRACE: std::time::Duration = std::time::Duration::from_secs(20);

/// Eine Mod in der Auswahl.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerMod {
    pub file_name: String,
    pub name: String,
    pub side: ModSide,
    pub source: SideSource,
    /// Ab Werk angehakt.
    pub included: bool,
    /// Darf nicht mit (TRS Client).
    pub locked: bool,
    pub size: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldChoice {
    pub folder: String,
    pub name: String,
}

/// Was der Dialog zeigt.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerExportPlan {
    pub suggested_name: String,
    pub game_version: String,
    pub loader: LoaderKind,
    pub loader_version: Option<String>,
    /// Gibt es zu dieser Version einen Server?
    pub supported: bool,
    pub java_major: u32,
    pub default_ram_mb: u32,
    pub mods: Vec<ServerMod>,
    pub worlds: Vec<WorldChoice>,
    /// Vorhandene Config-Ordner.
    pub config_dirs: Vec<String>,
    /// Modrinth/CurseForge waren nicht erreichbar – Einordnung nur aus den Jars.
    pub offline: bool,
}

/// Auswahl des Nutzers.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerExportOptions {
    pub name: String,
    /// Dateinamen der Mods (aus `mods/`), die auf den Server kommen.
    #[serde(default)]
    pub mods: Vec<String>,
    #[serde(default)]
    pub include_configs: bool,
    #[serde(default)]
    pub world: Option<String>,
    pub port: u16,
    pub motd: String,
    pub max_players: u32,
    pub online_mode: bool,
    pub ram_mb: u32,
    /// Der Nutzer hat die Minecraft-EULA ausdrücklich angenommen.
    pub eula_accepted: bool,
    /// Als ZIP speichern.
    #[serde(default)]
    pub zip: bool,
    /// Lokal anlegen und starten.
    #[serde(default)]
    pub local: bool,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ServerExportPhase {
    Java,
    Server,
    Files,
    Zip,
    Start,
}

#[derive(Debug, Clone, Copy, Serialize)]
pub struct ServerExportProgress {
    pub phase: ServerExportPhase,
    pub percent: f64,
}

pub type ServerExportProgressFn = Arc<dyn Fn(ServerExportProgress) + Send + Sync>;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerExportResult {
    /// Name der ZIP-Datei (ohne Pfad).
    pub zip_file: Option<String>,
    pub zip_bytes: u64,
    /// Der angelegte lokale Server.
    pub local: Option<LocalServerInfo>,
    pub mods: usize,
    pub left_out: usize,
    /// Start fehlgeschlagen (Server ist trotzdem angelegt).
    pub start_error: Option<crate::error::UserError>,
}

// --- Prüfen ------------------------------------------------------------------------

fn invalid_mods() -> Error {
    Error::validation(crate::msg!("serverExport.invalidMods", "Die Mod-Auswahl ist ungültig – bitte den Dialog neu öffnen."))
}

fn invalid_world() -> Error {
    Error::validation(crate::msg!("serverExport.invalidWorld", "Diese Welt gibt es nicht (mehr)."))
}

/// Normalisierte, geprüfte Auswahl.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CheckedOptions {
    pub name: String,
    pub mods: Vec<String>,
    pub world: Option<String>,
    pub props: ServerProperties,
}

pub fn check_options(options: &ServerExportOptions) -> Result<CheckedOptions> {
    let name: String = options.name.trim().chars().filter(|c| !c.is_control()).take(48).collect();
    if name.trim().is_empty() {
        return Err(Error::validation(crate::msg!("serverExport.nameRequired", "Bitte einen Namen für den Server eingeben.")));
    }
    if !options.zip && !options.local {
        return Err(Error::validation(crate::msg!(
            "serverExport.noTarget",
            "Bitte wählen: als ZIP speichern und/oder lokal anlegen."
        )));
    }
    if options.local && !options.eula_accepted {
        return Err(Error::validation(crate::msg!(
            "serverExport.eulaRequired",
            "Um den Server hier zu starten, musst du die Minecraft-EULA akzeptieren."
        )));
    }
    if options.port < 1024 {
        return Err(Error::validation(crate::msg!("serverExport.invalidPort", "Der Port muss zwischen 1024 und 65535 liegen.")));
    }
    if !(1..=1000).contains(&options.max_players) {
        return Err(Error::validation(crate::msg!(
            "serverExport.invalidMaxPlayers",
            "Die Spielerzahl muss zwischen 1 und 1000 liegen."
        )));
    }
    crate::settings::validate_memory(options.ram_mb)?;
    let motd: String = options.motd.chars().filter(|c| !c.is_control()).take(100).collect();
    if options.mods.len() > MAX_MODS {
        return Err(invalid_mods());
    }
    let mut seen = HashSet::new();
    let mut mods = Vec::new();
    for file in &options.mods {
        let ok = modpack_export::is_plain_entry(file) && file.to_ascii_lowercase().ends_with(".jar") && !sides::is_trs_client_file(file);
        if !ok {
            return Err(invalid_mods());
        }
        if seen.insert(file.to_ascii_lowercase()) {
            mods.push(file.clone());
        }
    }
    let world = match options.world.as_deref().map(str::trim).filter(|w| !w.is_empty()) {
        Some(w) if modpack_export::is_plain_entry(w) => Some(w.to_owned()),
        Some(_) => return Err(invalid_world()),
        None => None,
    };
    Ok(CheckedOptions {
        name: name.trim().to_owned(),
        mods,
        world,
        props: ServerProperties {
            port: options.port,
            motd: motd.trim().to_owned(),
            max_players: options.max_players,
            online_mode: options.online_mode,
        },
    })
}

// --- Mods einordnen ------------------------------------------------------------------

/// Eine Mod-Datei mit allem, was lokal bekannt ist.
struct ScannedMod {
    file_name: String,
    size: u64,
    facts: sides::JarFacts,
    sha1: String,
    sha512: String,
}

/// `hash` = Prüfsummen bilden (für Modrinth/CurseForge); ohne nur Name und Angaben aus dem Jar.
fn scan_mods(mods_dir: &Path, hash: bool) -> Vec<ScannedMod> {
    let Ok(read) = std::fs::read_dir(mods_dir) else { return Vec::new() };
    let mut out = Vec::new();
    for entry in read.flatten() {
        let Ok(name) = entry.file_name().into_string() else { continue };
        if !modpack_export::is_plain_entry(&name) || !name.to_ascii_lowercase().ends_with(".jar") {
            continue;
        }
        if !entry.file_type().is_ok_and(|t| t.is_file()) {
            continue;
        }
        let path = entry.path();
        let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
        let facts = std::fs::File::open(&path).map(sides::read_jar_facts).unwrap_or_default();
        let (sha1, sha512) = if hash { modpack_export::hash_file(&path).unwrap_or_default() } else { Default::default() };
        out.push(ScannedMod { file_name: name, size, facts, sha1, sha512 });
        if out.len() >= MAX_MODS {
            break;
        }
    }
    out.sort_by_key(|m| m.file_name.to_lowercase());
    out
}

/// Ordnet die Mods ein. `modrinth` = SHA-512 → (Projekt-ID, Seiten), `curseforge` = SHA-1 → Umgebung.
fn classify_mods(
    scanned: &[ScannedMod],
    modrinth: &HashMap<String, (String, modrinth::ProjectSide)>,
    curseforge: &HashMap<String, (bool, bool)>,
) -> Vec<ServerMod> {
    scanned
        .iter()
        .map(|m| {
            let project = modrinth.get(&m.sha512);
            let mut ids = m.facts.ids.clone();
            if let Some((id, side)) = project {
                ids.push(id.clone());
                ids.push(side.slug.clone());
            }
            let trs = sides::is_trs_client_file(&m.file_name) || m.facts.ids.iter().any(|id| id == "trsclient");
            let signals = SideSignals {
                trs_client: trs,
                known_client: sides::is_known_client(&ids),
                jar: m.facts.side,
                modrinth: project.map(|(_, s)| (s.client_side.clone(), s.server_side.clone())),
                curseforge: curseforge.get(&m.sha1).copied(),
            };
            let (side, source) = sides::classify(&signals);
            let name = m
                .facts
                .name
                .clone()
                .or_else(|| project.map(|(_, s)| s.title.clone()).filter(|t| !t.is_empty()))
                .unwrap_or_else(|| m.file_name.trim_end_matches(".jar").to_owned());
            ServerMod {
                file_name: m.file_name.clone(),
                name,
                side,
                source,
                included: !trs && sides::included_by_default(side),
                locked: trs,
                size: m.size,
            }
        })
        .collect()
}

// --- Dateien kopieren -------------------------------------------------------------------

/// Kopiert `src` nach `dest` (rekursiv, ohne Verknüpfungen und private Dateien).
/// `rel` ist der Pfad im Spielordner (für die Privat-Prüfung).
fn copy_tree(src: &Path, rel: &Path, dest: &Path, skip: &dyn Fn(&Path) -> bool, cancelled: &dyn Fn() -> bool) -> Result<u64> {
    let mut files = Vec::new();
    modpack_export::collect_dir(src, rel, &crate::shared_folders::SharedLinks::default(), &mut |child, _| {
        if !skip(&child) && files.len() < MAX_COPY_FILES {
            files.push(child);
        }
    });
    let mut copied = 0;
    for child in files {
        if cancelled() {
            return Err(Error::Cancelled);
        }
        let Ok(inner) = child.strip_prefix(rel) else { continue };
        let from = src.join(inner);
        let to = dest.join(inner);
        if let Some(parent) = to.parent() {
            std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
        }
        match std::fs::copy(&from, &to) {
            Ok(_) => copied += 1,
            // Zwischendurch verschwunden (z. B. Config neu geschrieben) – egal.
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(Error::io(&to, e)),
        }
    }
    Ok(copied)
}

/// Packt den Server-Ordner als ZIP (alles unter `<root_name>/`).
fn write_zip(dir: &Path, root_name: &str, dest: &Path, report: &dyn Fn(f64), cancelled: &dyn Fn() -> bool) -> Result<u64> {
    let mut entries = Vec::new();
    modpack_export::collect_dir(dir, Path::new(""), &crate::shared_folders::SharedLinks::default(), &mut |rel, len| entries.push((rel, len)));
    entries.sort();
    let total: u64 = entries.iter().map(|(_, len)| *len).sum::<u64>().max(1);
    let tmp = dest.with_extension(format!("part-{}", uuid::Uuid::new_v4().simple()));
    let result = (|| -> Result<u64> {
        let file = std::fs::File::create(&tmp).map_err(|e| Error::io(&tmp, e))?;
        let mut zip = zip::ZipWriter::new(std::io::BufWriter::new(file));
        let base = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated).large_file(true);
        let mut done = 0u64;
        for (rel, len) in &entries {
            if cancelled() {
                return Err(Error::Cancelled);
            }
            let name = rel.components().filter_map(|c| c.as_os_str().to_str()).collect::<Vec<_>>().join("/");
            let options = if name == "start.sh" { base.unix_permissions(0o755) } else { base.unix_permissions(0o644) };
            let source = dir.join(rel);
            let Ok(mut input) = std::fs::File::open(&source) else { continue };
            zip.start_file(format!("{root_name}/{name}"), options).map_err(|e| Error::Internal(e.to_string()))?;
            std::io::copy(&mut input, &mut zip).map_err(|e| Error::io(&source, e))?;
            done += len;
            report(done as f64 / total as f64 * 100.0);
        }
        zip.finish().map_err(|e| Error::Internal(e.to_string()))?.flush().map_err(|e| Error::io(&tmp, e))?;
        let size = std::fs::metadata(&tmp).map(|m| m.len()).unwrap_or(0);
        std::fs::rename(&tmp, dest).map_err(|e| Error::io(dest, e))?;
        Ok(size)
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    result
}

/// Räumt den Zwischenordner weg, falls er nicht übernommen wurde.
struct Staging(Option<PathBuf>);

impl Drop for Staging {
    fn drop(&mut self) {
        if let Some(dir) = self.0.take() {
            let _ = std::fs::remove_dir_all(dir);
        }
    }
}

fn blocking_err(e: tokio::task::JoinError) -> Error {
    Error::Internal(e.to_string())
}

/// Abbruch-Prüfung für Blocking-Code (das Task-Local gibt es dort nicht).
fn cancel_probe() -> impl Fn() -> bool + Send + Sync + 'static {
    let control = crate::task::current();
    move || control.as_ref().is_some_and(crate::task::TaskControl::is_cancelled)
}

/// Konsolen-Java statt `javaw.exe` (Server/Installer brauchen Ausgabe auf stdout).
pub fn console_java(java: &Path) -> PathBuf {
    let is_gui = java.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.eq_ignore_ascii_case("javaw.exe"));
    if is_gui {
        let console = java.with_file_name(crate::platform::JAVA_CONSOLE_BIN);
        if console.is_file() {
            return console;
        }
    }
    java.to_owned()
}

/// Mojang-Runtime für eine Java-Hauptversion (mit Rückfall auf die des Version-JSON).
fn component_for(version: &VersionInfo) -> (u32, String) {
    match &version.java_version {
        Some(j) => (j.major_version, j.component.clone()),
        None => (8, java::LEGACY_COMPONENT.to_owned()),
    }
}

impl Launcher {
    pub fn local_servers(&self) -> &Arc<local_servers::LocalServers> {
        &self.local_servers
    }

    /// Vanilla-Version-JSON (mit Server-Download und Java-Version).
    async fn vanilla_version(&self, game_version: &str) -> Result<VersionInfo> {
        let manifest = meta::manifest::fetch(self.http(), self.paths(), false).await?;
        let entry = manifest.find(game_version).ok_or_else(|| Error::UnknownGameVersion(game_version.to_owned()))?;
        meta::version::fetch_vanilla(self.http(), self.paths(), entry).await
    }

    /// Java für Server und Installer: eigenes Java je Hauptversion aus den Einstellungen,
    /// sonst die passende Mojang-Runtime. Immer die Konsolen-Variante.
    async fn server_java(&self, major: u32, component: &str, report: &(dyn Fn(f64) + Sync)) -> Result<PathBuf> {
        let settings = self.settings().await;
        if let Some(custom) = settings.java.get(major).map(PathBuf::from).filter(|p| p.is_file()) {
            return Ok(console_java(&custom));
        }
        let concurrency = usize::from(settings.concurrent_downloads);
        let java = java::ensure_runtime(self.http(), self.paths(), component, concurrency, &|p| report(p.percent())).await?;
        Ok(console_java(&java))
    }

    /// Mods einordnen, Welten und Configs auflisten – für den Dialog.
    pub async fn server_export_plan(&self, instance_id: &str) -> Result<ServerExportPlan> {
        validate_id(instance_id)?;
        let instance = self.instances().get(instance_id).await?;
        let game_dir = self.paths().instance_game_dir(&instance.id);

        let (supported, java_major) = match self.vanilla_version(&instance.game_version).await {
            Ok(v) => (v.downloads.as_ref().is_some_and(|d| d.server.is_some()), component_for(&v).0),
            Err(e) => {
                tracing::warn!("Version-JSON für den Server-Export nicht verfügbar: {e}");
                (false, 8)
            }
        };

        let mods_dir = game_dir.join("mods");
        let scanned = tokio::task::spawn_blocking(move || scan_mods(&mods_dir, true)).await.map_err(blocking_err)?;
        let (modrinth, curseforge, offline) = self.lookup_sides(&game_dir, &scanned).await;
        let mods = classify_mods(&scanned, &modrinth, &curseforge);

        let worlds = worlds::list(self.paths(), &instance.id)
            .await
            .unwrap_or_default()
            .into_iter()
            .map(|w| WorldChoice { folder: w.folder, name: w.name })
            .collect();
        let config_dirs = CONFIG_DIRS
            .iter()
            .filter(|d| std::fs::symlink_metadata(game_dir.join(d)).is_ok_and(|m| m.is_dir()))
            .map(|d| (*d).to_owned())
            .collect();
        let half = crate::platform::total_memory_mb().map_or(4096, |t| (t / 2 / 256 * 256).max(1024));
        let wanted = if instance.loader.kind == LoaderKind::Vanilla { 2048 } else { 4096 };
        Ok(ServerExportPlan {
            suggested_name: instance.name.clone(),
            game_version: instance.game_version.clone(),
            loader: instance.loader.kind,
            loader_version: instance.loader.version.clone(),
            supported,
            java_major,
            default_ram_mb: wanted.min(half).max(1024),
            mods,
            worlds,
            config_dirs,
            offline,
        })
    }

    /// Modrinth (per SHA-512) und CurseForge (per Fingerprint) nach den Seiten fragen.
    /// Ohne Netz: leer und `offline = true`.
    async fn lookup_sides(
        &self,
        game_dir: &Path,
        scanned: &[ScannedMod],
    ) -> (HashMap<String, (String, modrinth::ProjectSide)>, HashMap<String, (bool, bool)>, bool) {
        let mut offline = false;
        let mut by_hash = HashMap::new();
        let hashes: Vec<String> = scanned.iter().filter(|m| !m.sha512.is_empty()).map(|m| m.sha512.clone()).collect();
        if !hashes.is_empty() {
            match modrinth::versions_by_sha512(self.http(), &hashes).await {
                Ok(versions) => {
                    let ids: Vec<String> = {
                        let set: HashSet<&String> = versions.values().map(|v| &v.project_id).collect();
                        set.into_iter().cloned().collect()
                    };
                    match modrinth::project_sides(self.http(), &ids).await {
                        Ok(projects) => {
                            for (hash, version) in versions {
                                if let Some(side) = projects.get(&version.project_id) {
                                    by_hash.insert(hash, (version.project_id.clone(), side.clone()));
                                }
                            }
                        }
                        Err(e) => {
                            tracing::warn!("Modrinth-Projekte für den Server-Export nicht abrufbar: {e}");
                            offline = true;
                        }
                    }
                }
                Err(e) => {
                    tracing::warn!("Modrinth-Abgleich für den Server-Export fehlgeschlagen: {e}");
                    offline = true;
                }
            }
        }

        let mut cf_env = HashMap::new();
        let open: Vec<(PathBuf, u64, String, String)> = scanned
            .iter()
            .filter(|m| !by_hash.contains_key(&m.sha512) && !m.sha1.is_empty())
            .map(|m| (PathBuf::from("mods").join(&m.file_name), m.size, m.sha1.clone(), m.sha512.clone()))
            .collect();
        if !open.is_empty()
            && let Some(cf) = self.curseforge.as_ref()
        {
            match self.curseforge_env(cf, game_dir, &open).await {
                Ok(found) => cf_env = found,
                Err(e) => tracing::warn!("CurseForge-Abgleich für den Server-Export fehlgeschlagen: {e}"),
            }
        }
        (by_hash, cf_env, offline)
    }

    async fn curseforge_env(
        &self,
        cf: &crate::curseforge::CurseForge,
        game_dir: &Path,
        files: &[(PathBuf, u64, String, String)],
    ) -> Result<HashMap<String, (bool, bool)>> {
        let game_dir = game_dir.to_owned();
        let list = files.to_vec();
        let fingerprinted = tokio::task::spawn_blocking(move || {
            list.into_iter()
                .filter_map(|(rel, _, sha1, _)| {
                    let bytes = std::fs::read(game_dir.join(&rel)).ok()?;
                    Some((sha1, crate::hosting_mods::murmur::curseforge_fingerprint(&bytes)))
                })
                .collect::<Vec<_>>()
        })
        .await
        .map_err(blocking_err)?;
        let fps: Vec<u32> = fingerprinted.iter().map(|(_, fp)| *fp).collect();
        if fps.is_empty() {
            return Ok(HashMap::new());
        }
        let matches = cf.fingerprint_matches(&fps).await?;
        let mut out = HashMap::new();
        for (sha1, fp) in fingerprinted {
            let env = matches
                .iter()
                .find(|m| m.file.file_fingerprint == u64::from(fp))
                .and_then(|m| sides::curseforge_env(&m.file.game_versions));
            if let Some(env) = env {
                out.insert(sha1, env);
            }
        }
        Ok(out)
    }

    /// Baut den Server; `zip_dest` = Ziel der ZIP-Datei (aus dem Speichern-Dialog).
    pub async fn export_server(
        &self,
        instance_id: &str,
        options: &ServerExportOptions,
        zip_dest: Option<&Path>,
        on_progress: &ServerExportProgressFn,
    ) -> Result<ServerExportResult> {
        validate_id(instance_id)?;
        let checked = check_options(options)?;
        if options.zip != zip_dest.is_some() {
            return Err(Error::Internal("ZIP-Ziel fehlt oder ist überflüssig".into()));
        }
        let instance = self.instances().get(instance_id).await?;
        let game_dir = self.paths().instance_game_dir(&instance.id);
        let progress = |phase, percent: f64| on_progress(ServerExportProgress { phase, percent: percent.clamp(0.0, 100.0) });

        // Gibt es alles, was gewählt wurde?
        for file in &checked.mods {
            let path = game_dir.join("mods").join(file);
            if !std::fs::symlink_metadata(&path).is_ok_and(|m| m.is_file()) {
                return Err(invalid_mods());
            }
        }
        let world_dir = match &checked.world {
            Some(folder) => Some(worlds::world_dir(self.paths(), &instance.id, folder).map_err(|_| invalid_world())?),
            None => None,
        };

        // 1. Version + Java
        progress(ServerExportPhase::Java, 0.0);
        let version = self.vanilla_version(&instance.game_version).await?;
        let server = version.downloads.as_ref().and_then(|d| d.server.clone()).ok_or_else(|| {
            Error::validation(crate::msg!(
                "serverExport.noServerJar",
                "Für Minecraft {version} gibt es keinen offiziellen Server.",
                version = &instance.game_version
            ))
        })?;
        let (java_major, component) = component_for(&version);
        let needs_installer = matches!(instance.loader.kind, LoaderKind::Quilt | LoaderKind::Forge | LoaderKind::NeoForge);
        let server_java = if options.local {
            Some(self.server_java(java_major, &component, &|p| progress(ServerExportPhase::Java, p)).await?)
        } else {
            None
        };
        let installer_java = if !needs_installer {
            None
        } else if java_major >= 17 {
            match &server_java {
                Some(java) => Some(java.clone()),
                None => Some(self.server_java(java_major, &component, &|p| progress(ServerExportPhase::Java, p)).await?),
            }
        } else {
            Some(self.server_java(INSTALLER_MAJOR, INSTALLER_COMPONENT, &|p| progress(ServerExportPhase::Java, p)).await?)
        };
        progress(ServerExportPhase::Java, 100.0);
        crate::task::checkpoint().await?;

        // 2. Zwischenordner + Server/Loader
        let base = local_servers::servers_dir(self.paths());
        crate::fsutil::ensure_dir(&base).await?;
        let staging_dir = base.join(format!(".staging-{}", uuid::Uuid::new_v4().simple()));
        tokio::fs::create_dir_all(&staging_dir).await.map_err(|e| Error::io(&staging_dir, e))?;
        let mut staging = Staging(Some(staging_dir.clone()));

        let concurrency = usize::from(self.settings().await.concurrent_downloads);
        let input = install::InstallInput {
            http: self.http(),
            paths: self.paths(),
            game_version: &instance.game_version,
            loader: &instance.loader,
            dir: &staging_dir,
            server: &server,
            installer_java: installer_java.as_deref(),
            java_major,
            concurrency,
        };
        progress(ServerExportPhase::Server, 0.0);
        let installed = install::install(&input, &|p| progress(ServerExportPhase::Server, p)).await?;
        crate::task::checkpoint().await?;

        // 3. Mods, Configs, Welt und eigene Dateien
        progress(ServerExportPhase::Files, 0.0);
        let plan_mods = {
            let mods_dir = game_dir.join("mods");
            tokio::task::spawn_blocking(move || scan_mods(&mods_dir, false)).await.map_err(blocking_err)?
        };
        let chosen: HashSet<String> = checked.mods.iter().map(|m| m.to_ascii_lowercase()).collect();
        let left_out: Vec<String> = plan_mods
            .iter()
            .filter(|m| !chosen.contains(&m.file_name.to_ascii_lowercase()) && !sides::is_trs_client_file(&m.file_name))
            .map(|m| m.facts.name.clone().unwrap_or_else(|| m.file_name.clone()))
            .collect();
        {
            let (game_dir, dest, mods, world_dir, include_configs) =
                (game_dir.clone(), staging_dir.clone(), checked.mods.clone(), world_dir.clone(), options.include_configs);
            let cancelled = cancel_probe();
            let report = {
                let on_progress = on_progress.clone();
                move |p: f64| on_progress(ServerExportProgress { phase: ServerExportPhase::Files, percent: p })
            };
            tokio::task::spawn_blocking(move || -> Result<()> {
                let mods_dest = dest.join("mods");
                if !mods.is_empty() {
                    std::fs::create_dir_all(&mods_dest).map_err(|e| Error::io(&mods_dest, e))?;
                }
                for (i, file) in mods.iter().enumerate() {
                    if cancelled() {
                        return Err(Error::Cancelled);
                    }
                    let to = mods_dest.join(file);
                    std::fs::copy(game_dir.join("mods").join(file), &to).map_err(|e| Error::io(&to, e))?;
                    report((i + 1) as f64 / mods.len().max(1) as f64 * 40.0);
                }
                if include_configs {
                    for dir in CONFIG_DIRS {
                        let src = game_dir.join(dir);
                        if std::fs::symlink_metadata(&src).is_ok_and(|m| m.is_dir()) {
                            copy_tree(&src, Path::new(dir), &dest.join(dir), &modpack_export::is_private_file, &cancelled)?;
                        }
                    }
                }
                report(60.0);
                if let Some(world) = world_dir {
                    let skip = |rel: &Path| rel.file_name().is_some_and(|n| n == "session.lock");
                    copy_tree(&world, Path::new("world"), &dest.join("world"), &skip, &cancelled)?;
                }
                report(100.0);
                Ok(())
            })
            .await
            .map_err(blocking_err)??;
        }

        let eula_at = chrono::Utc::now();
        let readme = files::readme(&ReadmeInfo {
            name: &checked.name,
            game_version: &instance.game_version,
            loader: instance.loader.kind,
            loader_version: installed.loader_version.as_deref(),
            java_major,
            port: checked.props.port,
            ram_mb: options.ram_mb,
            eula_accepted: options.eula_accepted,
            mods: checked.mods.len(),
            left_out: &left_out,
            needs_internet_first_start: installed.needs_internet,
        });
        let mut own_files: Vec<(&str, String)> = vec![
            ("server.properties", files::server_properties(&checked.props, checked.world.is_some())),
            ("start.bat", files::start_bat(&installed.launch, options.ram_mb, java_major)),
            ("start.sh", files::start_sh(&installed.launch, options.ram_mb, java_major)),
            ("README.txt", readme),
        ];
        if options.eula_accepted {
            own_files.push(("eula.txt", files::eula_txt(&eula_at.to_rfc3339())));
        }
        for (name, text) in own_files {
            let path = staging_dir.join(name);
            tokio::fs::write(&path, text).await.map_err(|e| Error::io(&path, e))?;
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let script = staging_dir.join("start.sh");
            let _ = std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755));
        }
        crate::task::checkpoint().await?;

        // 4. ZIP
        let slug = files::slug(&checked.name);
        let mut result = ServerExportResult {
            zip_file: None,
            zip_bytes: 0,
            local: None,
            mods: checked.mods.len(),
            left_out: left_out.len(),
            start_error: None,
        };
        if let Some(dest) = zip_dest {
            progress(ServerExportPhase::Zip, 0.0);
            let (dir, dest_owned, root) = (staging_dir.clone(), dest.to_owned(), slug.clone());
            let cancelled = cancel_probe();
            let on = on_progress.clone();
            let bytes = tokio::task::spawn_blocking(move || {
                write_zip(&dir, &root, &dest_owned, &|p| on(ServerExportProgress { phase: ServerExportPhase::Zip, percent: p }), &cancelled)
            })
            .await
            .map_err(blocking_err)??;
            result.zip_bytes = bytes;
            result.zip_file = dest.file_name().and_then(|n| n.to_str()).map(str::to_owned);
        }

        // 5. Lokal anlegen und starten
        if options.local {
            progress(ServerExportPhase::Start, 0.0);
            let id = local_servers::free_id(self.paths(), &slug);
            let meta = ServerMeta {
                name: checked.name.clone(),
                game_version: instance.game_version.clone(),
                loader: instance.loader.kind,
                loader_version: installed.loader_version.clone(),
                java_major,
                java_component: component.clone(),
                ram_mb: options.ram_mb,
                port: checked.props.port,
                max_players: checked.props.max_players,
                launch: installed.launch.clone(),
                instance_id: Some(instance.id.clone()),
                created_at: chrono::Utc::now(),
            };
            let meta_path = staging_dir.join(local_servers::META_FILE);
            let json = serde_json::to_vec_pretty(&meta).map_err(|e| Error::Internal(e.to_string()))?;
            tokio::fs::write(&meta_path, json).await.map_err(|e| Error::io(&meta_path, e))?;
            let final_dir = base.join(&id);
            tokio::fs::rename(&staging_dir, &final_dir).await.map_err(|e| Error::io(&final_dir, e))?;
            staging.0 = None;

            let java = server_java.ok_or_else(|| Error::Internal("Server-Java fehlt".into()))?;
            if let Err(e) = self.local_servers.start(&id, &final_dir, &meta, &java) {
                tracing::warn!("Lokaler Server '{id}' wurde angelegt, aber nicht gestartet: {e}");
                result.start_error = Some(e.to_user());
            }
            result.local = self.local_servers.list(self.paths()).await?.into_iter().find(|s| s.id == id);
            progress(ServerExportPhase::Start, 100.0);
        }
        drop(staging);
        Ok(result)
    }

    /// Startet einen lokalen Server (Java wird bei Bedarf geladen).
    pub async fn start_local_server(&self, id: &str) -> Result<local_servers::ServerStatus> {
        let dir = local_servers::server_dir(self.paths(), id)?;
        let meta = {
            let dir = dir.clone();
            tokio::task::spawn_blocking(move || local_servers::read_meta(&dir)).await.map_err(blocking_err)??
        };
        if self.local_servers.is_running(id) {
            return Ok(self.local_servers.status(id, meta.max_players));
        }
        let java = self.server_java(meta.java_major, &meta.java_component, &|_| {}).await?;
        self.local_servers.start(id, &dir, &meta, &java)
    }

    /// Stoppt und startet neu (wartet höchstens die Stopp-Frist).
    pub async fn restart_local_server(&self, id: &str) -> Result<local_servers::ServerStatus> {
        local_servers::validate_server_id(id)?;
        if self.local_servers.is_running(id) {
            self.local_servers.stop(id)?;
            if !self.local_servers.wait_stopped(id, std::time::Duration::from_secs(65)).await {
                return Err(Error::launch(crate::msg!("localServer.stopTimeout", "Der Server hat sich nicht rechtzeitig beendet.")));
            }
        }
        self.start_local_server(id).await
    }

    /// Server-Ordner in den Papierkorb (nur, wenn er nicht läuft).
    pub async fn delete_local_server(&self, id: &str) -> Result<()> {
        let dir = local_servers::server_dir(self.paths(), id)?;
        if self.local_servers.is_running(id) {
            return Err(Error::validation(crate::msg!(
                "localServer.stillRunning",
                "Bitte den Server zuerst stoppen."
            )));
        }
        tokio::task::spawn_blocking(move || crate::platform::move_to_trash(&dir)).await.map_err(blocking_err)?
    }

    /// Beim Beenden des Launchers: lokale Server sauber stoppen.
    pub async fn local_servers_shutdown(&self) {
        self.local_servers.shutdown_all(SHUTDOWN_GRACE).await;
    }
}

/// Vorgeschlagener Dateiname der ZIP-Datei.
pub fn suggested_zip_name(name: &str) -> String {
    format!("{}-server.zip", files::slug(name))
}

/// Für Tests: Einordnung ohne Netz.
#[cfg(test)]
fn classify_offline(scanned: &[ScannedMod]) -> Vec<ServerMod> {
    classify_mods(scanned, &HashMap::new(), &HashMap::new())
}
