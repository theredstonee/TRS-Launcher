//! UI-unabhängiger Kern des TRS Launchers.
//!
//! Alles, was nicht Tauri-spezifisch ist, lebt hier: Verzeichnisstruktur,
//! Einstellungen, Instanzen, Mojang-Metadaten, Downloads, Accounts und der
//! Spielstart. Die Tauri-App ist nur eine dünne Command-Schicht darüber.

pub mod auth;
pub mod boost;
pub mod client_mod;
pub mod clips;
pub mod client_mod_update;
pub mod content;
pub mod curseforge;
pub mod discord;
pub mod download;
pub mod error;
pub mod extras;
pub mod firewall;
pub mod forge;
pub mod fsutil;
pub mod gamelog;
pub mod gpu;
pub mod history;
pub mod hooks;
pub mod icon;
pub mod import;
pub mod instance;
pub mod java;
pub mod launch;
pub mod loaders;
pub mod meta;
pub mod modpack;
pub mod modpack_export;
pub mod modrinth;
pub mod news;
pub mod nbt;
pub mod paths;
pub mod platform;
pub mod prepare;
pub mod presets;
pub mod process;
pub mod screenshots;
pub mod servers;
pub mod settings;
pub mod skin_sync;
pub mod skins;
pub mod storage;
pub mod sync;
pub mod system;
pub mod task;
pub mod task_history;
pub mod trs_api;
pub mod upload;

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::RwLock;

use auth::AccountStore;
pub use error::{Error, Result};
use history::{HistoryEntry, HistoryKind};
use hooks::{HookContext, HookKind};
use instance::{Instance, InstanceStore, Loader, LoaderKind, NewInstance, UpdateInstance};
use launch::{EventSink, GameEvent, GameManager, Session};
use paths::Paths;
use servers::ServerStore;
use prepare::{ProgressFn, Stage, StageProgress};
use settings::Settings;

pub const LAUNCHER_NAME: &str = "TRS-Launcher";
pub const LAUNCHER_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Modrinth & Co. blocken generische User-Agents – immer diesen verwenden.
pub const USER_AGENT: &str = concat!("theredstonee/TRS-Launcher/", env!("CARGO_PKG_VERSION"));

pub struct Launcher {
    paths: Paths,
    http: reqwest::Client,
    settings: RwLock<Settings>,
    instances: InstanceStore,
    accounts: AccountStore,
    games: GameManager,
    servers: ServerStore,
    /// Von Hand gewählte Import-Ordner (nur für diese Sitzung).
    import_folders: Mutex<Vec<PathBuf>>,
    /// Mitgelieferte TRS-Client-Jars (Tauri-Ressourcen).
    client_mod_dir: std::sync::RwLock<Option<PathBuf>>,
    /// Update-Kanal für den TRS Client (GitHub-Release `client-mod`).
    client_mod_updates: client_mod_update::ClientModUpdater,
    /// Instanzen, die gerade vorbereitet werden (Schutz vor Doppelklicks).
    preparing: Mutex<HashSet<String>>,
    /// Warteschlange für Skin-/Umhang-Änderungen.
    skin_sync: skin_sync::SkinSync,
    /// TRS API (Umhänge, Freunde, Präsenz) – nur mit Einwilligung.
    trs: trs_api::TrsApi,
    /// CurseForge – nur, wenn der Build einen API-Schlüssel hat.
    curseforge: Option<curseforge::CurseForge>,
    /// Clips & Aufnahme (nimmt das Spielfenster auf, die Mod meldet nur Tasten).
    clips: Arc<clips::ClipService>,
    /// Discord-Status („Spielt TRS Launcher“) über die lokale Discord-App.
    discord: Arc<discord::DiscordPresence>,
}

impl Launcher {
    /// Legt die Verzeichnisstruktur unter `root` an und lädt die Einstellungen.
    /// Über `events` meldet der Kern Spielstart, Logs und Spielende.
    pub async fn init(root: impl Into<PathBuf>, events: EventSink) -> Result<Self> {
        let paths = Paths::new(root);
        paths.ensure().await?;
        // Schlüssel für die Token-Verschlüsselung (Linux: Schlüsselbund, ggf. mit
        // dessen Entsperr-Dialog – deshalb blockierend abseits der Runtime).
        let key_root = paths.root().to_owned();
        tokio::task::spawn_blocking(move || auth::crypto::init(&key_root))
            .await
            .map_err(|e| Error::Internal(e.to_string()))?;

        let http = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(60))
            .build()?;

        let settings = Settings::load(&paths.settings_file()).await?;

        // Spielende landet zusätzlich im Verlauf der Instanz.
        let history_paths = paths.clone();
        let events: EventSink = Arc::new(move |event: GameEvent| {
            if let GameEvent::Exited { instance_id, crashed, play_seconds, diagnosis, exit_code } = &event {
                let entry = if *crashed {
                    let detail = diagnosis
                        .as_ref()
                        .and_then(|d| serde_json::to_value(d.kind).ok())
                        .and_then(|v| v.as_str().map(str::to_owned))
                        .or_else(|| exit_code.map(|c| format!("exit:{c}")));
                    let entry = HistoryEntry::new(HistoryKind::Crashed).seconds(*play_seconds);
                    match detail {
                        Some(d) => entry.detail(d),
                        None => entry,
                    }
                } else {
                    HistoryEntry::new(HistoryKind::Stopped).seconds(*play_seconds)
                };
                history::record_detached(&history_paths, instance_id, entry);
            }
            events(event);
        });

        let clips = Arc::new(clips::ClipService::new(&paths));
        let discord = Arc::new(discord::DiscordPresence::from_build());
        discord.configure(settings.discord_presence, settings.ui.language);
        let launcher = Self {
            clips: clips.clone(),
            discord: discord.clone(),
            instances: InstanceStore::new(paths.clone()),
            accounts: AccountStore::new(paths.clone(), http.clone()),
            games: GameManager::new(events, paths.root().join("running.json")),
            servers: ServerStore::new(paths.clone()),
            import_folders: Mutex::default(),
            client_mod_dir: std::sync::RwLock::default(),
            client_mod_updates: client_mod_update::ClientModUpdater::new(&paths)?,
            preparing: Mutex::default(),
            skin_sync: skin_sync::SkinSync::default(),
            trs: trs_api::TrsApi::new(paths.clone())?,
            curseforge: curseforge::CurseForge::from_build()?,
            settings: RwLock::new(settings),
            paths,
            http,
        };

        // Spiele, die beim letzten Schließen noch liefen, wieder übernehmen –
        // nach ihrem Ende auch synchronisieren und den Nach-Beenden-Hook ausführen.
        let paths = launcher.paths.clone();
        let sink = launcher.games.sink();
        let presence = Arc::clone(&launcher.trs.presence);
        launcher.games.recover(|id| {
            // Mit welchem Account das Spiel lief, ist nach dem Neustart unbekannt.
            presence.game_started(id, None);
            let (paths, id, sink, presence, clips, discord) =
                (paths.clone(), id.to_owned(), sink.clone(), presence.clone(), clips.clone(), discord.clone());
            Box::new(move |seconds| {
                presence.game_exited(&id);
                clips.game_exited(&id);
                discord.game_exited(&id);
                tokio::spawn(async move {
                    let store = InstanceStore::new(paths.clone());
                    if let Err(e) = store.add_play_time(&id, seconds).await {
                        tracing::warn!("Spielzeit für '{id}' konnte nicht gespeichert werden: {e}");
                    }
                    let Ok(instance) = store.get(&id).await else { return };
                    let settings = Settings::load(&paths.settings_file()).await.unwrap_or_default();
                    let plan = ExitPlan::new(&paths, &instance, &settings, None);
                    plan.run(sink).await;
                });
            })
        });
        launcher.discord_recovered_games().await;
        Ok(launcher)
    }

    /// Übernommene Spiele auch im Discord-Status zeigen (mit ihrer echten Startzeit).
    async fn discord_recovered_games(&self) {
        let mut games = self.games.running();
        games.sort_by_key(|g| g.started_at);
        for game in games {
            let Ok(instance) = self.instances.get(&game.instance_id).await else { continue };
            self.discord.game_started(discord::GameInfo {
                instance_id: instance.id.clone(),
                game_version: instance.game_version.clone(),
                loader: instance.loader.kind,
                started_at: game.started_at.timestamp(),
            });
            // Inzwischen beendet? Dann kam die Abmeldung schon – wieder entfernen.
            if !self.games.is_running(&instance.id) {
                self.discord.game_exited(&instance.id);
            }
        }
    }

    /// Hintergrund-Schleife für den Discord-Status (still, solange Discord nicht läuft).
    pub async fn run_discord(self: Arc<Self>) {
        self.discord.run(discord::ipc::IpcConnector).await;
    }

    /// Beim Beenden des Launchers: Discord-Status löschen (höchstens ~1,5 s).
    pub async fn discord_shutdown(&self) {
        self.discord.shutdown().await;
    }

    pub fn paths(&self) -> &Paths {
        &self.paths
    }

    pub fn http(&self) -> &reqwest::Client {
        &self.http
    }

    pub fn instances(&self) -> &InstanceStore {
        &self.instances
    }

    pub fn accounts(&self) -> &AccountStore {
        &self.accounts
    }

    pub fn games(&self) -> &GameManager {
        &self.games
    }

    /// CurseForge-Zugang; Fehler, wenn dieser Build keinen API-Schlüssel hat.
    pub fn curseforge(&self) -> Result<&curseforge::CurseForge> {
        self.curseforge.as_ref().ok_or_else(curseforge::disabled)
    }

    /// Ob CurseForge in diesem Build verfügbar ist (ohne Schlüssel preiszugeben).
    pub fn curseforge_status(&self) -> curseforge::CurseForgeStatus {
        curseforge::CurseForgeStatus { available: self.curseforge.is_some() }
    }

    /// Ordner mit den mitgelieferten TRS-Client-Jars.
    pub fn set_client_mod_dir(&self, dir: PathBuf) {
        *self.client_mod_dir.write().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(dir);
    }

    fn bundled_client_mod_dir(&self) -> Option<PathBuf> {
        self.client_mod_dir.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone()
    }

    /// Sieht im Update-Kanal des TRS Clients nach (beim Launcher-Start und vor
    /// jedem Spielstart, höchstens alle 30 Minuten). Offline passiert nichts.
    pub async fn check_client_mod_updates(&self) -> client_mod_update::CheckOutcome {
        let bundled = self.bundled_client_mod_dir().as_deref().map(client_mod::load_manifest).unwrap_or_default();
        let bundled_version = Some(bundled.version.as_str()).filter(|v| !v.is_empty());
        self.client_mod_updates.check_if_due(bundled_version).await
    }

    /// Welche TRS-Client-Version gerade gilt (für die Einstellungen).
    pub async fn client_mod_status(&self) -> client_mod::ClientModStatus {
        let dir = self.bundled_client_mod_dir();
        client_mod::Catalog::load(dir.as_deref(), Some(&self.client_mod_updates)).await.status()
    }

    async fn client_mod_catalog(&self) -> (Option<PathBuf>, client_mod::Catalog) {
        let dir = self.bundled_client_mod_dir();
        let catalog = client_mod::Catalog::load(dir.as_deref(), Some(&self.client_mod_updates)).await;
        (dir, catalog)
    }

    pub fn servers(&self) -> &ServerStore {
        &self.servers
    }

    pub async fn settings(&self) -> Settings {
        self.settings.read().await.clone()
    }

    pub async fn update_settings(&self, new: Settings) -> Result<Settings> {
        let new = new.normalized();
        new.validate()?;
        let mut guard = self.settings.write().await;
        new.save(&self.paths.settings_file()).await?;
        if guard.prefer_dedicated_gpu && !new.prefer_dedicated_gpu {
            // Abgeschaltet: unsere GPU-Einträge in Windows wieder entfernen.
            let java_dir = self.paths.java_dir();
            let removed = tokio::task::spawn_blocking(move || {
                gpu::revert(&gpu::WindowsGpuPreferences, &java_dir, &gpu::own_runtimes(&java_dir))
            })
            .await
            .unwrap_or_default();
            tracing::info!("GPU-Präferenz für {removed} Java-Runtimes entfernt");
        }
        let clips_changed = guard.clips != new.clips;
        *guard = new.clone();
        drop(guard);
        self.discord.configure(new.discord_presence, new.ui.language);
        if clips_changed {
            let running = self.running_for_clips().await;
            self.clips.settings_changed(&new.clips, running).await;
        }
        Ok(new)
    }

    /// Clips & Aufnahme.
    pub fn clips(&self) -> &clips::ClipService {
        &self.clips
    }

    /// Laufende Spiele mit Name und Spielordner (für die Aufnahme).
    async fn running_for_clips(&self) -> Vec<clips::RunningGame> {
        let mut games = Vec::new();
        for game in self.games.running() {
            let name = self.instances.get(&game.instance_id).await.map(|i| i.name).unwrap_or_else(|_| game.instance_id.clone());
            games.push(clips::RunningGame {
                game_dir: self.paths.instance_game_dir(&game.instance_id),
                instance_name: name,
                pid: game.pid,
                instance_id: game.instance_id,
            });
        }
        games
    }

    /// Nach einem Launcher-Neustart: Aufnahme für übernommene Spiele wieder aufnehmen
    /// (neues Token – die Mod liest die Datei beim nächsten Verbindungsversuch neu).
    pub async fn resume_clips(self: Arc<Self>) {
        let settings = self.settings().await.clips;
        if settings.enabled {
            let running = self.running_for_clips().await;
            self.clips.settings_changed(&settings, running).await;
        }
    }

    /// Beim Beenden des Launchers: laufende Aufnahmen sichern und FFmpeg beenden.
    pub async fn clips_shutdown(&self) {
        self.clips.shutdown().await;
    }

    pub async fn version_manifest(&self, force_refresh: bool) -> Result<meta::VersionManifest> {
        meta::manifest::fetch(&self.http, &self.paths, force_refresh).await
    }

    /// Wie [`InstanceStore::create`], prüft aber vorher gegen das Manifest,
    /// dass es die Spielversion wirklich gibt.
    ///
    /// Neue Instanzen bekommen gleich die Standard-`options.txt`
    /// ([`instance::DEFAULT_GAME_OPTIONS`]: unbegrenzte Bildrate, kein VSync).
    /// Import, Modpacks und Kopien bringen eigene Optionen mit – dort greift das
    /// erst beim ersten Start, und nur, wenn dann noch keine da ist.
    pub async fn create_instance(&self, new: NewInstance) -> Result<Instance> {
        let instance = self.create_instance_as(new, HistoryEntry::new(HistoryKind::Created)).await?;
        if let Err(e) = instance::seed_game_options(&self.paths.instance_game_dir(&instance.id), None).await {
            tracing::warn!("Standard-Optionen für '{}' nicht geschrieben: {e}", instance.id);
        }
        Ok(instance)
    }

    /// Wie [`Self::create_instance`] mit eigenem ersten Verlaufseintrag
    /// (Import, Modpack, Kopie).
    pub(crate) async fn create_instance_as(&self, new: NewInstance, first: HistoryEntry) -> Result<Instance> {
        let manifest = self.version_manifest(false).await?;
        if manifest.find(&new.game_version).is_none() {
            return Err(Error::UnknownGameVersion(new.game_version));
        }
        let instance = self.instances.create(new).await?;
        let first = first.to(describe_version(&instance.game_version, &instance.loader));
        history::record(&self.paths, &instance.id, first).await;
        Ok(instance)
    }

    pub async fn delete_instance(&self, id: &str) -> Result<()> {
        if self.games.is_running(id) || self.is_preparing(id) {
            return Err(Error::launch(crate::msg!("launcher.instanceRunningNoDelete", "Die Instanz läuft gerade und kann nicht gelöscht werden.")));
        }
        self.instances.delete(id).await
    }

    /// Wechselt Minecraft-Version und/oder Modloader einer Instanz. Welten und
    /// Mods bleiben liegen; passende Mod-Versionen findet danach
    /// [`modrinth::plan_migration`].
    pub async fn change_instance_version(&self, id: &str, game_version: &str, loader: Loader) -> Result<Instance> {
        let instance = self.instances.get(id).await?;
        if self.games.is_running(&instance.id) || self.is_preparing(&instance.id) {
            return Err(Error::launch(crate::msg!("launcher.instanceRunningStopFirst", "Die Instanz läuft gerade – bitte erst beenden.")));
        }
        loader.validate()?;
        if instance.game_version == game_version && instance.loader == loader {
            return Ok(instance);
        }
        let manifest = self.version_manifest(false).await?;
        if manifest.find(game_version).is_none() {
            return Err(Error::UnknownGameVersion(game_version.to_owned()));
        }
        let updated = self.instances.set_version(&instance.id, game_version, loader).await?;
        history::record(
            &self.paths,
            &updated.id,
            HistoryEntry::new(HistoryKind::VersionSwitched)
                .from(describe_version(&instance.game_version, &instance.loader))
                .to(describe_version(&updated.game_version, &updated.loader)),
        )
        .await;
        Ok(updated)
    }

    /// Name und Einstellungen einer Instanz ändern; Umbenennen und geänderte
    /// Start-Hooks landen im Verlauf.
    pub async fn update_instance(&self, id: &str, update: UpdateInstance) -> Result<Instance> {
        let before = self.instances.get(id).await?;
        let updated = self.instances.update(&before.id, update).await?;
        if before.name != updated.name {
            let entry = HistoryEntry::new(HistoryKind::Renamed).from(&before.name).to(&updated.name);
            history::record(&self.paths, &updated.id, entry).await;
        }
        if before.overrides.hooks != updated.overrides.hooks {
            let detail = if updated.overrides.hooks.is_some() { "custom" } else { "global" };
            history::record(&self.paths, &updated.id, HistoryEntry::new(HistoryKind::HooksChanged).detail(detail)).await;
        }
        Ok(updated)
    }

    /// Instanz in eine eigene Gruppe verschieben (`None` = aus der Gruppe nehmen).
    pub async fn set_instance_group(&self, id: &str, group: Option<&str>) -> Result<Instance> {
        let before = self.instances.get(id).await?;
        let updated = self.instances.set_group(&before.id, group).await?;
        if before.group != updated.group {
            let mut entry = HistoryEntry::new(HistoryKind::GroupChanged);
            if let Some(from) = &before.group {
                entry = entry.from(from);
            }
            if let Some(to) = &updated.group {
                entry = entry.to(to);
            }
            history::record(&self.paths, &updated.id, entry).await;
        }
        Ok(updated)
    }

    pub async fn instance_history(&self, id: &str) -> Result<Vec<history::HistoryEntry>> {
        let instance = self.instances.get(id).await?;
        history::list(&self.paths, &instance.id).await
    }

    fn is_preparing(&self, id: &str) -> bool {
        self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner).contains(id)
    }

    /// Lädt alles Nötige herunter und startet das Spiel. Liefert die PID.
    ///
    /// `join`: Server, auf den direkt verbunden werden soll – aus der
    /// Launcher-Liste oder eine Adresse (z. B. der geteilte Server eines Freundes).
    pub async fn launch(
        self: &Arc<Self>,
        instance_id: &str,
        join: Option<Join<'_>>,
        on_progress: &ProgressFn,
    ) -> Result<u32> {
        let instance = self.instances.get(instance_id).await?;
        if self.games.is_running(&instance.id) {
            return Err(Error::launch(crate::msg!("launcher.alreadyRunning", "Diese Instanz läuft bereits.")));
        }
        {
            let mut preparing = self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if !preparing.insert(instance.id.clone()) {
                return Err(Error::launch(crate::msg!("launcher.alreadyStarting", "Diese Instanz wird bereits gestartet.")));
            }
        }

        let result = self.launch_inner(&instance, join, on_progress).await;
        self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner).remove(&instance.id);
        result
    }

    async fn launch_inner(
        self: &Arc<Self>,
        instance: &Instance,
        join_request: Option<Join<'_>>,
        on_progress: &ProgressFn,
    ) -> Result<u32> {
        let (join, join_label) = match join_request {
            Some(Join::Server(id)) => {
                let server = self.servers.get(id).await?;
                (Some(servers::join_target(&server.address).await?), Some(server.name))
            }
            Some(Join::Address(address)) => {
                let target = servers::join_target(address).await?;
                let label = target.address.clone();
                (Some(target), Some(label))
            }
            None => (None, None),
        };
        let session = match self.accounts.active_session().await? {
            Some(session) => session,
            None => demo_session()?,
        };
        let settings = self.settings().await;
        // Für Discord zählt, was der Nutzer gewählt hat (Vanilla bleibt Vanilla,
        // auch wenn die TRS-Optimierung unter der Haube Fabric nutzt).
        let discord_game = discord::GameInfo {
            instance_id: instance.id.clone(),
            game_version: instance.game_version.clone(),
            loader: instance.loader.kind,
            started_at: 0,
        };

        // Neuer TRS Client im Update-Kanal? (kurzer Timeout, offline egal)
        self.check_client_mod_updates().await;
        // Vanilla mit TRS-Optimierung läuft unter der Haube als Fabric.
        let (client_mod_dir, catalog) = self.client_mod_catalog().await;
        let effective = boost::effective_instance(&self.http, &self.paths, catalog.builds(), instance).await;
        // Performance-Mods gibt es nur für Fabric; Forge-Boost (1.8.9) bekommt nur den TRS Client.
        if effective.loader.kind == LoaderKind::Fabric && instance.loader.kind != LoaderKind::Fabric {
            boost::ensure_performance(&self.http, &self.paths, &effective).await?;
        }
        let instance = &effective;

        let updates = Some(&self.client_mod_updates);
        // Der Mod liest daraus, ob er die TRS API benutzen darf (nur feste Werte, kein Token).
        let trs_enabled = self.trs.enabled().await;
        if let Err(e) =
            client_mod::sync(&self.http, &self.paths, client_mod_dir.as_deref(), updates, instance, &settings.ui, trs_enabled)
                .await
        {
            tracing::warn!("TRS Client konnte nicht eingerichtet werden: {e}");
        }

        let prepared =
            prepare::prepare(&self.http, &self.paths, &settings, instance, &session.features(), false, on_progress)
                .await?;

        // Letzte Gelegenheit zum Abbrechen – ab hier wird gestartet.
        task::checkpoint().await?;
        on_progress(StageProgress::begin(Stage::Starting));
        // Neue Java-Version? Einmal die Firewall-Freigabe eintragen, damit Windows
        // nicht bei jeder Instanz einzeln nach dem Netzwerkzugriff fragt.
        if settings.auto_firewall {
            let missing = firewall::missing_for_auto(&self.paths).await;
            if !missing.is_empty() {
                match firewall::allow(&self.paths, missing.clone()).await {
                    Ok(n) => tracing::info!("Firewall-Freigabe für {n} Java-Programme eingetragen"),
                    Err(Error::Cancelled) => {
                        let _ = firewall::remember_declined(&self.paths, &missing).await;
                    }
                    Err(e) => tracing::warn!("Firewall-Freigabe fehlgeschlagen: {e}"),
                }
            }
        }
        let game_dir = self.paths.instance_game_dir(&instance.id);
        fsutil::ensure_dir(&game_dir).await?;

        let exit_plan = ExitPlan::new(&self.paths, instance, &settings, Some(prepared.java.clone()));
        if let Some(pre) = &exit_plan.hooks.pre_launch {
            hooks::run(HookKind::PreLaunch, pre, &exit_plan.context, &exit_plan.env, hooks::HOOK_TIMEOUT).await?;
        }
        // Gemeinsame options.txt & Co. holen. Scheitert das, startet das Spiel
        // mit den eigenen Dateien – dann wird auch nichts zurückkopiert.
        if let Err(e) = sync::pull(&exit_plan.sync_dirs, &exit_plan.sync_items).await {
            tracing::warn!("Synchronisierung vor dem Start fehlgeschlagen: {e}");
        }
        // Noch keine options.txt (erster Start, auch nach Import/Modpack ohne Optionen):
        // Standard mit unbegrenzter Bildrate und ohne VSync – nach dem Sync, damit
        // eine gemeinsame options.txt des Spielers Vorrang hat.
        let data_version = instance::client_data_version(&self.paths.version_jar(&instance.game_version)).await;
        if let Err(e) = instance::seed_game_options(&game_dir, data_version).await {
            tracing::warn!("Standard-Optionen konnten nicht geschrieben werden: {e}");
        }
        // Die Launcher-Server sollen in jeder Instanz in der Serverliste stehen.
        if let Err(e) = self.servers.sync_to_instance(&game_dir).await {
            tracing::warn!("servers.dat konnte nicht aktualisiert werden: {e}");
        }
        let mut command = launch::build_command(
            &prepared,
            instance,
            &settings,
            &session,
            launch::LaunchDirs {
                game: &game_dir,
                assets: &self.paths.assets_dir(),
                libraries: &self.paths.libraries_dir(),
            },
            join.as_ref(),
            platform::total_memory_mb(),
        )?;

        let launcher = Arc::clone(self);
        let id = instance.id.clone();
        let sink = self.games.sink();
        let plan = exit_plan.clone();
        let on_exit = Box::new(move |play_seconds: u64| {
            // Spiel zu: Der Launcher meldet sofort wieder „online“.
            launcher.trs.presence.game_exited(&id);
            launcher.clips.game_exited(&id);
            launcher.discord.game_exited(&id);
            tokio::spawn(async move {
                if let Err(e) = launcher.instances.add_play_time(&id, play_seconds).await {
                    tracing::warn!("Spielzeit für '{id}' konnte nicht gespeichert werden: {e}");
                }
                plan.run(sink).await;
            });
        });

        // Windows: Eintrag unter „Grafikeinstellungen“ (Registry), Linux: PRIME-Variablen (unten).
        if settings.prefer_dedicated_gpu {
            gpu::prefer_for_launch(&gpu::WindowsGpuPreferences, &self.paths.java_dir(), &command.program);
        }
        command.high_priority = settings.high_priority;
        command.env = exit_plan.env.clone();
        if settings.prefer_dedicated_gpu {
            for (key, value) in process::prefer_dedicated_gpu(&command.program) {
                // Eigene Variablen des Nutzers (Einstellungen oder Umgebung) haben Vorrang.
                if !command.env.iter().any(|(k, _)| *k == key) && std::env::var_os(&key).is_none() {
                    command.env.push((key, value));
                }
            }
        }
        if let Some(wrapper) = &exit_plan.hooks.wrapper {
            hooks::apply_wrapper(&mut command, wrapper);
        }
        let log_dir = self.paths.instance_dir(&instance.id).join("launcher-logs");
        // Port + Einmal-Token für die Clip-Tasten der Mod (oder "aus").
        self.clips.prepare(&instance.id, &game_dir, &settings.clips).await;
        // Vor dem Start eintragen, damit der Launcher ab jetzt schweigt (der Mod meldet "in-game").
        self.trs.presence.game_started(&instance.id, Some(&session.uuid));
        self.discord.game_started(discord::GameInfo { started_at: chrono::Utc::now().timestamp(), ..discord_game });
        let pid = match self.games.spawn(&instance.id, command, &log_dir, vec![session.access_token.clone()], on_exit) {
            Ok(pid) => pid,
            Err(e) => {
                self.trs.presence.game_exited(&instance.id);
                self.clips.game_exited(&instance.id);
                self.discord.game_exited(&instance.id);
                return Err(e);
            }
        };
        self.clips.game_started(
            clips::RunningGame {
                instance_id: instance.id.clone(),
                instance_name: instance.name.clone(),
                pid,
                game_dir: game_dir.clone(),
            },
            &settings.clips,
        );
        self.instances.touch_last_played(&instance.id).await?;
        let mut entry = HistoryEntry::new(HistoryKind::Launched);
        if let Some(label) = &join_label {
            entry = entry.subject(label);
        }
        history::record(&self.paths, &instance.id, entry).await;
        Ok(pid)
    }
}

impl Launcher {
    /// Prüft alle Spieldateien der Instanz per Prüfsumme und lädt beschädigte
    /// neu (nach einem Absturz wegen kaputter Dateien).
    pub async fn repair_instance(&self, instance_id: &str, on_progress: &ProgressFn) -> Result<()> {
        let instance = self.instances.get(instance_id).await?;
        if self.games.is_running(&instance.id) {
            return Err(Error::launch(crate::msg!("launcher.instanceRunningStopFirst", "Die Instanz läuft gerade – bitte erst beenden.")));
        }
        let (_, catalog) = self.client_mod_catalog().await;
        let effective = boost::effective_instance(&self.http, &self.paths, catalog.builds(), &instance).await;
        let settings = self.settings().await;
        let features = meta::version::Features { custom_resolution: true, ..Default::default() };
        prepare::prepare(&self.http, &self.paths, &settings, &effective, &features, true, on_progress).await?;
        history::record(&self.paths, &instance.id, HistoryEntry::new(HistoryKind::Repaired)).await;
        Ok(())
    }

    /// Lädt den neuesten Log der Instanz geschwärzt auf mclo.gs hoch.
    pub async fn share_log(&self, instance_id: &str) -> Result<String> {
        if !self.settings().await.allow_log_upload {
            return Err(Error::validation(crate::msg!("launcher.logUploadDisabled", "Log-Upload ist in den Datenschutz-Einstellungen ausgeschaltet.")));
        }
        let instance = self.instances.get(instance_id).await?;
        let game_dir = self.paths.instance_game_dir(&instance.id);
        let launcher_logs = self.paths.instance_dir(&instance.id).join("launcher-logs");
        let file = process::latest_log_file(&game_dir, &launcher_logs)
            .ok_or_else(|| Error::validation(crate::msg!("launcher.noLogToShare", "Es gibt noch keinen Log zum Teilen.")))?;
        let raw = process::read_log_file(&file).await?;
        // Gespeicherte Tokens zusätzlich wörtlich schwärzen.
        let secrets = self.accounts.active_session().await.ok().flatten().map(|s| vec![s.access_token]).unwrap_or_default();
        process::share_log(&self.http, &process::redact(&raw, &secrets)).await
    }
}

impl Launcher {
    fn anything_active(&self) -> bool {
        !self.games.running().is_empty()
            || !self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner).is_empty()
    }

    /// Wie [`Self::repair_instance`], lädt aber auch die Spielversion komplett
    /// neu und prüft die TRS-Optimierung beim nächsten Start neu.
    pub async fn reinstall_instance(&self, instance_id: &str, on_progress: &ProgressFn) -> Result<()> {
        let instance = self.instances.get(instance_id).await?;
        if self.anything_active() {
            return Err(Error::launch(crate::msg!("launcher.stopAllGamesFirst", "Bitte erst alle laufenden Spiele beenden.")));
        }
        let _ = tokio::fs::remove_file(self.paths.instance_dir(&instance.id).join("trs-boost.json")).await;
        if meta::is_safe_id(&instance.game_version) {
            let dir = self.paths.version_dir(&instance.game_version);
            if dir.is_dir()
                && let Err(e) = tokio::fs::remove_dir_all(&dir).await
            {
                tracing::warn!("Versionsordner konnte nicht gelöscht werden: {e}");
            }
        }
        self.repair_instance(&instance.id, on_progress).await
    }

    pub async fn storage_stats(&self) -> Result<storage::StorageStats> {
        storage::stats(&self.paths, self.instances.list().await?).await
    }

    /// Löscht Spielversionen, die keine Instanz mehr braucht.
    pub async fn clean_unused_storage(&self) -> Result<u64> {
        if self.anything_active() {
            return Err(Error::launch(crate::msg!("launcher.stopAllGamesFirst", "Bitte erst alle laufenden Spiele beenden.")));
        }
        storage::clean_unused(&self.paths, self.instances.list().await?).await
    }

    /// Prüft die geteilten Spieldateien; beschädigte werden beim nächsten Start neu geladen.
    pub async fn verify_storage(&self) -> Result<storage::VerifyReport> {
        if self.anything_active() {
            return Err(Error::launch(crate::msg!("launcher.stopAllGamesFirst", "Bitte erst alle laufenden Spiele beenden.")));
        }
        storage::verify_assets(&self.paths).await
    }

    pub async fn detect_java(&self) -> Vec<java::JavaInstall> {
        let paths = self.paths.clone();
        tokio::task::spawn_blocking(move || java::detect(&paths)).await.unwrap_or_default()
    }

    /// Installiert die von Mojang empfohlene Runtime für eine Java-Hauptversion.
    pub async fn install_java(&self, major: u32, on_progress: &(dyn Fn(download::Progress) + Sync)) -> Result<PathBuf> {
        let component = java::component_for(major).ok_or_else(|| Error::validation(crate::msg!("launcher.javaVersionUnavailable", "Diese Java-Version gibt es nicht zum Installieren.")))?;
        let concurrency = usize::from(self.settings().await.concurrent_downloads);
        java::ensure_runtime(&self.http, &self.paths, component, concurrency, on_progress).await
    }
}

/// Direkt beitreten: Server aus der Launcher-Liste (ID) oder freie Adresse.
#[derive(Debug, Clone, Copy)]
pub enum Join<'a> {
    Server(&'a str),
    Address(&'a str),
}

/// Was vor dem Start feststeht und nach dem Spielende passieren soll:
/// zurücksynchronisieren und den Nach-Beenden-Hook ausführen.
#[derive(Clone)]
struct ExitPlan {
    hooks: hooks::LaunchHooks,
    context: HookContext,
    sync_dirs: sync::SyncDirs,
    sync_items: Vec<sync::SyncItem>,
    env: Vec<(String, String)>,
}

impl ExitPlan {
    fn new(paths: &Paths, instance: &Instance, settings: &Settings, java: Option<PathBuf>) -> Self {
        let instance_dir = paths.instance_dir(&instance.id);
        let game_dir = paths.instance_game_dir(&instance.id);
        Self {
            hooks: instance.overrides.hooks.clone().unwrap_or_else(|| settings.hooks.clone()),
            env: hooks::env_pairs(instance.overrides.env.as_deref().unwrap_or(&settings.env)),
            context: HookContext {
                instance_id: instance.id.clone(),
                instance_name: instance.name.clone(),
                instance_dir: instance_dir.clone(),
                game_dir: game_dir.clone(),
                java,
            },
            sync_dirs: sync::SyncDirs { shared: paths.shared_dir(), instance: instance_dir, game: game_dir },
            sync_items: sync::active_items(&settings.sync, &instance.overrides.sync_separate),
        }
    }

    /// Fehler gehen als Hinweis ans Frontend – das Spiel ist ja schon zu.
    async fn run(self, sink: EventSink) {
        let id = self.context.instance_id.clone();
        if let Err(e) = sync::push(&self.sync_dirs).await {
            tracing::warn!("Synchronisierung nach dem Beenden fehlgeschlagen: {e}");
            sink(GameEvent::notice(
                id.clone(),
                &crate::msg!(
                    "launcher.syncAfterExitFailed",
                    "Die Einstellungen konnten nicht mit den anderen Instanzen synchronisiert werden."
                ),
            ));
        }
        if let Some(post) = &self.hooks.post_exit
            && let Err(e) = hooks::run(HookKind::PostExit, post, &self.context, &self.env, hooks::HOOK_TIMEOUT).await
        {
            sink(GameEvent::notice_error(id, &e));
        }
    }
}

/// Kurzbeschreibung für den Verlauf, z. B. `1.21.1 Fabric 0.16.10`.
fn describe_version(game_version: &str, loader: &Loader) -> String {
    let name = match loader.kind {
        LoaderKind::Vanilla => "Vanilla",
        LoaderKind::Fabric => "Fabric",
        LoaderKind::Quilt => "Quilt",
        LoaderKind::Forge => "Forge",
        LoaderKind::NeoForge => "NeoForge",
    };
    match &loader.version {
        Some(v) => format!("{game_version} {name} {v}"),
        None => format!("{game_version} {name}"),
    }
}

/// Ohne Account startet nur ein Entwicklungs-Build – und dann im offiziellen
/// Demo-Modus des Spiels. Release-Builds verlangen immer eine Anmeldung.
fn demo_session() -> Result<Session> {
    if cfg!(debug_assertions) {
        tracing::warn!("Kein Account angemeldet – starte im Demo-Modus (nur Entwicklungs-Build)");
        Ok(Session {
            player_name: "Player".into(),
            uuid: "0".repeat(32),
            access_token: "0".into(),
            xuid: String::new(),
            demo: true,
        })
    } else {
        Err(Error::auth(crate::msg!("launcher.signInFirst", "Bitte melde dich zuerst unter „Accounts“ mit deinem Microsoft-Konto an.")))
    }
}
