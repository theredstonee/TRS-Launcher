//! Minecraft: Java Edition auf Android/iOS: eingebettete JVM (Amethyst-Engine),
//! Renderer (MobileGlues / GL4ES), Touch-Eingabe. Auf dem Desktop nur Typen und
//! `UnsupportedPlatform` – dort startet der Kern das Spiel als eigenen Prozess.
//!
//! Vertrag: `prepare_runtime(java_major)`, `launch(spec)`, Events
//! `trs-game://state`, `trs-game://log`, `trs-game://runtime-progress`.

use std::path::PathBuf;
use std::sync::RwLock;

use tauri::plugin::{Builder, TauriPlugin};
use tauri::{AppHandle, Emitter, Manager, Runtime};

mod commands;
mod error;
pub mod ios;
mod models;
pub mod runtime;

#[cfg(mobile)]
mod mobile;

pub use error::{Error, Result};
pub use models::*;

/// Zugriff auf die Engine.
pub struct TrsGame<R: Runtime> {
    app: AppHandle<R>,
    /// Vom Launcher gesetzt (TLS-Anbieter, Proxy). Kein eigener Client beim Start: mit
    /// `rustls-no-provider` (mobil) bräche `Client::new()` vor der Einrichtung durch den Kern ab.
    http: RwLock<Option<reqwest::Client>>,
    #[cfg(mobile)]
    mobile: mobile::Engine<R>,
}

impl<R: Runtime> TrsGame<R> {
    /// HTTP-Client des Launchers übernehmen (Proxy, TLS, User-Agent).
    pub fn set_http_client(&self, client: reqwest::Client) {
        *self.http.write().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(client);
    }

    fn http(&self) -> Result<reqwest::Client> {
        if let Some(client) = self.http.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone() {
            return Ok(client);
        }
        reqwest::Client::builder().build().map_err(|e| Error::Download(e.to_string()))
    }

    /// `<App-Daten>/engine/runtimes`.
    pub fn runtimes_root(&self) -> Result<PathBuf> {
        Ok(self.app.path().app_data_dir()?.join("engine").join("runtimes"))
    }

    /// Stellt die Java-Runtime bereit (Download mit SHA-256-Prüfung, Entpacken).
    /// Fortschritt als `trs-game://runtime-progress`.
    pub async fn prepare_runtime(&self, java_major: u8) -> Result<RuntimeInfo> {
        if !cfg!(mobile) {
            return Err(Error::UnsupportedPlatform);
        }
        let archive = runtime::archive_for(runtime::platform_table(), java_major, runtime::current_arch())?;
        let root = self.runtimes_root()?;
        let app = self.app.clone();
        runtime::ensure(&self.http()?, &root, archive, &move |p| {
            let _ = app.emit(EVENT_RUNTIME_PROGRESS, p);
        })
        .await
    }

    /// Installierte Runtimes (nur passende, festgenagelte Versionen).
    pub fn runtimes(&self) -> Vec<RuntimeInfo> {
        let Ok(root) = self.runtimes_root() else { return Vec::new() };
        JAVA_MAJORS
            .iter()
            .filter_map(|major| runtime::archive_for(runtime::platform_table(), *major, runtime::current_arch()).ok())
            .filter_map(|archive| runtime::installed(&root, archive))
            .collect()
    }

    #[cfg(mobile)]
    fn installed_runtime(&self, java_major: u8) -> Result<RuntimeInfo> {
        let archive = runtime::archive_for(runtime::platform_table(), java_major, runtime::current_arch())?;
        runtime::installed(&self.runtimes_root()?, archive).ok_or(Error::RuntimeMissing(java_major))
    }

    /// Startet das Spiel in der Spiel-Activity (eigener Prozess). Die Runtime muss
    /// vorher mit [`Self::prepare_runtime`] bereitstehen.
    pub fn launch(&self, spec: GameLaunchSpec) -> Result<SessionId> {
        spec.validate()?;
        #[cfg(mobile)]
        {
            let runtime = self.installed_runtime(spec.java_major)?;
            self.mobile.launch(&spec, &runtime)
        }
        #[cfg(not(mobile))]
        {
            Err(Error::UnsupportedPlatform)
        }
    }

    /// Kopflose JVM in eigenem Prozess (Forge-/NeoForge-Processors, `java -version`).
    pub async fn run_java(&self, spec: JavaRunSpec) -> Result<JavaRunResult> {
        spec.validate()?;
        #[cfg(mobile)]
        {
            let runtime = self.installed_runtime(spec.java_major)?;
            self.mobile.run_java(&spec, &runtime).await
        }
        #[cfg(not(mobile))]
        {
            Err(Error::UnsupportedPlatform)
        }
    }

    /// Beendet eine laufende Sitzung (Spielprozess wird geschlossen).
    pub fn stop(&self, session: &SessionId) -> Result<()> {
        #[cfg(mobile)]
        {
            self.mobile.stop(session)
        }
        #[cfg(not(mobile))]
        {
            let _ = session;
            Err(Error::UnsupportedPlatform)
        }
    }
}

/// `app.trs_game()` auf `AppHandle`, `App` und Fenstern.
pub trait TrsGameExt<R: Runtime> {
    fn trs_game(&self) -> &TrsGame<R>;
}

impl<R: Runtime, T: Manager<R>> TrsGameExt<R> for T {
    fn trs_game(&self) -> &TrsGame<R> {
        self.state::<TrsGame<R>>().inner()
    }
}

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("trs-game")
        .invoke_handler(tauri::generate_handler![commands::prepare_runtime, commands::launch, commands::runtimes])
        .setup(|app, api| {
            #[cfg(mobile)]
            let mobile = mobile::Engine::init(app, api)?;
            #[cfg(not(mobile))]
            let _ = api;
            app.manage(TrsGame {
                app: app.clone(),
                http: RwLock::new(None),
                #[cfg(mobile)]
                mobile,
            });
            Ok(())
        })
        .build()
}
