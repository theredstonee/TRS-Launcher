//! UI-unabhängiger Kern des TRS Launchers.
//!
//! Alles, was nicht Tauri-spezifisch ist, lebt hier: Verzeichnisstruktur,
//! Einstellungen, Instanzen, Mojang-Metadaten, Downloads, Accounts und der
//! Spielstart. Die Tauri-App ist nur eine dünne Command-Schicht darüber.

pub mod auth;
pub mod boost;
pub mod client_mod;
pub mod content;
pub mod download;
pub mod error;
pub mod extras;
pub mod forge;
pub mod fsutil;
pub mod gamelog;
pub mod history;
pub mod icon;
pub mod import;
pub mod instance;
pub mod java;
pub mod launch;
pub mod loaders;
pub mod meta;
pub mod modpack;
pub mod modrinth;
pub mod nbt;
pub mod paths;
pub mod prepare;
pub mod process;
pub mod servers;
pub mod settings;

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::RwLock;

use auth::AccountStore;
pub use error::{Error, Result};
use history::{HistoryEntry, HistoryKind};
use instance::{Instance, InstanceStore, Loader, LoaderKind, NewInstance};
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
    /// Instanzen, die gerade vorbereitet werden (Schutz vor Doppelklicks).
    preparing: Mutex<HashSet<String>>,
}

impl Launcher {
    /// Legt die Verzeichnisstruktur unter `root` an und lädt die Einstellungen.
    /// Über `events` meldet der Kern Spielstart, Logs und Spielende.
    pub async fn init(root: impl Into<PathBuf>, events: EventSink) -> Result<Self> {
        let paths = Paths::new(root);
        paths.ensure().await?;

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

        let launcher = Self {
            instances: InstanceStore::new(paths.clone()),
            accounts: AccountStore::new(paths.clone(), http.clone()),
            games: GameManager::new(events, paths.root().join("running.json")),
            servers: ServerStore::new(paths.clone()),
            import_folders: Mutex::default(),
            client_mod_dir: std::sync::RwLock::default(),
            preparing: Mutex::default(),
            settings: RwLock::new(settings),
            paths,
            http,
        };

        // Spiele, die beim letzten Schließen noch liefen, wieder übernehmen.
        let paths = launcher.paths.clone();
        launcher.games.recover(|id| {
            let (paths, id) = (paths.clone(), id.to_owned());
            Box::new(move |seconds| {
                tokio::spawn(async move {
                    let store = InstanceStore::new(paths);
                    if let Err(e) = store.add_play_time(&id, seconds).await {
                        tracing::warn!("Spielzeit für '{id}' konnte nicht gespeichert werden: {e}");
                    }
                });
            })
        });
        Ok(launcher)
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

    /// Ordner mit den mitgelieferten TRS-Client-Jars.
    pub fn set_client_mod_dir(&self, dir: PathBuf) {
        *self.client_mod_dir.write().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(dir);
    }

    pub fn servers(&self) -> &ServerStore {
        &self.servers
    }

    pub async fn settings(&self) -> Settings {
        self.settings.read().await.clone()
    }

    pub async fn update_settings(&self, new: Settings) -> Result<Settings> {
        new.validate()?;
        let mut guard = self.settings.write().await;
        new.save(&self.paths.settings_file()).await?;
        *guard = new.clone();
        Ok(new)
    }

    pub async fn version_manifest(&self, force_refresh: bool) -> Result<meta::VersionManifest> {
        meta::manifest::fetch(&self.http, &self.paths, force_refresh).await
    }

    /// Wie [`InstanceStore::create`], prüft aber vorher gegen das Manifest,
    /// dass es die Spielversion wirklich gibt.
    pub async fn create_instance(&self, new: NewInstance) -> Result<Instance> {
        self.create_instance_as(new, HistoryEntry::new(HistoryKind::Created)).await
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
            return Err(Error::launch("Die Instanz läuft gerade und kann nicht gelöscht werden."));
        }
        self.instances.delete(id).await
    }

    /// Wechselt Minecraft-Version und/oder Modloader einer Instanz. Welten und
    /// Mods bleiben liegen; passende Mod-Versionen findet danach
    /// [`modrinth::plan_migration`].
    pub async fn change_instance_version(&self, id: &str, game_version: &str, loader: Loader) -> Result<Instance> {
        let instance = self.instances.get(id).await?;
        if self.games.is_running(&instance.id) || self.is_preparing(&instance.id) {
            return Err(Error::launch("Die Instanz läuft gerade – bitte erst beenden."));
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

    pub async fn instance_history(&self, id: &str) -> Result<Vec<history::HistoryEntry>> {
        let instance = self.instances.get(id).await?;
        history::list(&self.paths, &instance.id).await
    }

    fn is_preparing(&self, id: &str) -> bool {
        self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner).contains(id)
    }

    /// Lädt alles Nötige herunter und startet das Spiel. Liefert die PID.
    ///
    /// `join_server`: ID eines Servers aus der Launcher-Liste, auf den direkt
    /// verbunden werden soll.
    pub async fn launch(
        self: &Arc<Self>,
        instance_id: &str,
        join_server: Option<&str>,
        on_progress: &ProgressFn,
    ) -> Result<u32> {
        let instance = self.instances.get(instance_id).await?;
        if self.games.is_running(&instance.id) {
            return Err(Error::launch("Diese Instanz läuft bereits."));
        }
        {
            let mut preparing = self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if !preparing.insert(instance.id.clone()) {
                return Err(Error::launch("Diese Instanz wird bereits gestartet."));
            }
        }

        let result = self.launch_inner(&instance, join_server, on_progress).await;
        self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner).remove(&instance.id);
        result
    }

    async fn launch_inner(
        self: &Arc<Self>,
        instance: &Instance,
        join_server: Option<&str>,
        on_progress: &ProgressFn,
    ) -> Result<u32> {
        let join = match join_server {
            Some(id) => Some(servers::join_target(&self.servers.get(id).await?.address).await?),
            None => None,
        };
        let session = match self.accounts.active_session().await? {
            Some(session) => session,
            None => demo_session()?,
        };
        let settings = self.settings().await;

        // Vanilla mit TRS-Optimierung läuft unter der Haube als Fabric.
        let effective = boost::effective_instance(&self.http, &self.paths, instance).await;
        if effective.loader.kind != instance.loader.kind {
            boost::ensure_performance(&self.http, &self.paths, &effective).await?;
        }
        let instance = &effective;

        let client_mod_dir = self.client_mod_dir.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
        if let Err(e) = client_mod::sync(&self.http, &self.paths, client_mod_dir.as_deref(), instance).await {
            tracing::warn!("TRS Client konnte nicht eingerichtet werden: {e}");
        }

        let prepared =
            prepare::prepare(&self.http, &self.paths, &settings, instance, &session.features(), false, on_progress)
                .await?;

        on_progress(StageProgress::begin(Stage::Starting));
        let game_dir = self.paths.instance_game_dir(&instance.id);
        fsutil::ensure_dir(&game_dir).await?;
        // Die Launcher-Server sollen in jeder Instanz in der Serverliste stehen.
        if let Err(e) = self.servers.sync_to_instance(&game_dir).await {
            tracing::warn!("servers.dat konnte nicht aktualisiert werden: {e}");
        }
        let command = launch::build_command(
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
        )?;

        let launcher = Arc::clone(self);
        let id = instance.id.clone();
        let on_exit = Box::new(move |play_seconds: u64| {
            tokio::spawn(async move {
                if let Err(e) = launcher.instances.add_play_time(&id, play_seconds).await {
                    tracing::warn!("Spielzeit für '{id}' konnte nicht gespeichert werden: {e}");
                }
            });
        });

        if settings.prefer_dedicated_gpu {
            process::prefer_dedicated_gpu(&command.program);
        }
        let log_dir = self.paths.instance_dir(&instance.id).join("launcher-logs");
        let pid = self.games.spawn(&instance.id, command, &log_dir, vec![session.access_token.clone()], on_exit)?;
        self.instances.touch_last_played(&instance.id).await?;
        let mut entry = HistoryEntry::new(HistoryKind::Launched);
        if let Some(id) = join_server
            && let Ok(server) = self.servers.get(id).await
        {
            entry = entry.subject(&server.name);
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
            return Err(Error::launch("Die Instanz läuft gerade – bitte erst beenden."));
        }
        let effective = boost::effective_instance(&self.http, &self.paths, &instance).await;
        let settings = self.settings().await;
        let features = meta::version::Features { custom_resolution: true, ..Default::default() };
        prepare::prepare(&self.http, &self.paths, &settings, &effective, &features, true, on_progress).await?;
        history::record(&self.paths, &instance.id, HistoryEntry::new(HistoryKind::Repaired)).await;
        Ok(())
    }

    /// Lädt den neuesten Log der Instanz geschwärzt auf mclo.gs hoch.
    pub async fn share_log(&self, instance_id: &str) -> Result<String> {
        let instance = self.instances.get(instance_id).await?;
        let game_dir = self.paths.instance_game_dir(&instance.id);
        let launcher_logs = self.paths.instance_dir(&instance.id).join("launcher-logs");
        let file = process::latest_log_file(&game_dir, &launcher_logs)
            .ok_or_else(|| Error::validation("Es gibt noch keinen Log zum Teilen."))?;
        let raw = process::read_log_file(&file).await?;
        // Gespeicherte Tokens zusätzlich wörtlich schwärzen.
        let secrets = self.accounts.active_session().await.ok().flatten().map(|s| vec![s.access_token]).unwrap_or_default();
        process::share_log(&self.http, &process::redact(&raw, &secrets)).await
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
        Err(Error::auth("Bitte melde dich zuerst unter „Accounts“ mit deinem Microsoft-Konto an."))
    }
}
