use serde::{Serialize, Serializer};

/// Fehler des Plugins. Nach außen geht nur der Code (`code()`), Details ins Log.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("Java {0} wird nicht unterstützt")]
    UnsupportedJava(u8),
    #[error("keine Runtime für diese Architektur ({0})")]
    UnsupportedArch(String),
    #[error("Plattform ohne eingebettetes Spiel")]
    UnsupportedPlatform,
    #[error("ungültige Startbeschreibung: {0}")]
    InvalidSpec(&'static str),
    #[error("Runtime für Java {0} ist nicht installiert")]
    RuntimeMissing(u8),
    #[error("Download fehlgeschlagen: {0}")]
    Download(String),
    #[error("Prüfsumme stimmt nicht")]
    Checksum,
    #[error("Archiv ungültig: {0}")]
    Archive(String),
    #[error("E/A-Fehler: {0}")]
    Io(#[from] std::io::Error),
    #[error("Spiel-Engine: {0}")]
    Engine(String),
    // iOS: Engine fehlt im Build, JVM lief schon in diesem Prozess, zu wenig Speicher.
    #[error("Spiel-Engine fehlt in diesem Build")]
    EngineMissing,
    #[error("Engine lief schon – App neu starten")]
    RestartRequired,
    #[error("zu wenig Arbeitsspeicher für das Spiel")]
    NotEnoughMemory,
    /// iOS: SDL3-Spiele (Minecraft 26.3+) – die iOS-Engine hat noch keine SDL-Anbindung.
    #[error("SDL3-Spiel auf dieser Plattform nicht unterstützt")]
    SdlUnsupported,
    #[error(transparent)]
    Tauri(#[from] tauri::Error),
    #[cfg(mobile)]
    #[error(transparent)]
    PluginInvoke(#[from] tauri::plugin::mobile::PluginInvokeError),
}

impl Error {
    /// Stabiler Fehlercode für die Oberfläche (i18n `msg`-Schlüssel im Launcher).
    pub fn code(&self) -> &'static str {
        match self {
            Error::UnsupportedJava(_) => "game.unsupportedJava",
            Error::UnsupportedArch(_) => "game.unsupportedArch",
            Error::UnsupportedPlatform => "game.unsupportedPlatform",
            Error::InvalidSpec(_) => "game.invalidSpec",
            Error::RuntimeMissing(_) => "game.runtimeMissing",
            Error::Download(_) => "game.runtimeDownloadFailed",
            Error::Checksum => "game.runtimeChecksum",
            Error::Archive(_) => "game.runtimeArchive",
            Error::Io(_) => "game.io",
            Error::Engine(_) | Error::Tauri(_) => "game.engine",
            Error::EngineMissing => "game.engineMissing",
            Error::RestartRequired => "game.restartRequired",
            Error::NotEnoughMemory => "game.notEnoughMemory",
            Error::SdlUnsupported => "game.sdlUnsupported",
            #[cfg(mobile)]
            Error::PluginInvoke(_) => "game.engine",
        }
    }
}

impl Serialize for Error {
    fn serialize<S: Serializer>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error> {
        log::warn!("trs-game: {self}");
        serializer.serialize_str(self.code())
    }
}

pub type Result<T> = std::result::Result<T, Error>;
