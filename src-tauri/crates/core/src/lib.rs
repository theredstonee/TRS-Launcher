//! UI-unabhängiger Kern des TRS Launchers.
//!
//! Alles, was nicht Tauri-spezifisch ist, lebt hier: Verzeichnisstruktur,
//! Einstellungen, Instanzen, Mojang-Metadaten. Die Tauri-App ist nur eine
//! dünne Command-Schicht darüber.

pub mod error;
pub mod fsutil;
pub mod instance;
pub mod meta;
pub mod paths;
pub mod settings;

use std::path::PathBuf;
use std::time::Duration;

use tokio::sync::RwLock;

pub use error::{Error, Result};
use instance::{Instance, InstanceStore, NewInstance};
use paths::Paths;
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
}

impl Launcher {
    /// Legt die Verzeichnisstruktur unter `root` an und lädt die Einstellungen.
    pub async fn init(root: impl Into<PathBuf>) -> Result<Self> {
        let paths = Paths::new(root);
        paths.ensure().await?;

        let http = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(60))
            .build()?;

        let settings = Settings::load(&paths.settings_file()).await?;
        let instances = InstanceStore::new(paths.clone());

        Ok(Self {
            paths,
            http,
            settings: RwLock::new(settings),
            instances,
        })
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
}
