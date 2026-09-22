//! UI-unabhängiger Kern des TRS Launchers.
//!
//! Alles, was nicht Tauri-spezifisch ist, lebt hier: Verzeichnisstruktur,
//! Einstellungen, Instanzen, Mojang-Metadaten, Downloads, Accounts und der
//! Spielstart. Die Tauri-App ist nur eine dünne Command-Schicht darüber.

pub mod auth;
pub mod content;
pub mod download;
pub mod error;
pub mod extras;
pub mod forge;
pub mod fsutil;
pub mod gamelog;
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
use instance::{Instance, InstanceStore, NewInstance};
use launch::{EventSink, GameManager, Session};
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

        let launcher = Self {
            instances: InstanceStore::new(paths.clone()),
            accounts: AccountStore::new(paths.clone(), http.clone()),
            games: GameManager::new(events, paths.root().join("running.json")),
            servers: ServerStore::new(paths.clone()),
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
        let manifest = self.version_manifest(false).await?;
        if manifest.find(&new.game_version).is_none() {
            return Err(Error::UnknownGameVersion(new.game_version));
        }
        self.instances.create(new).await
    }

    pub async fn delete_instance(&self, id: &str) -> Result<()> {
        if self.games.is_running(id) || self.is_preparing(id) {
            return Err(Error::launch("Die Instanz läuft gerade und kann nicht gelöscht werden."));
        }
        self.instances.delete(id).await
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

        let prepared =
            prepare::prepare(&self.http, &self.paths, &settings, instance, &session.features(), on_progress)
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
        Ok(pid)
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
