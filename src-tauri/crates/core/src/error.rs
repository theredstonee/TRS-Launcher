use std::path::PathBuf;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("Ein-/Ausgabefehler bei {path}: {source}")]
    Io {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },

    #[error("Netzwerkfehler: {0}")]
    Http(#[from] reqwest::Error),

    #[error("Ungültige Daten in {context}: {source}")]
    Json {
        context: String,
        #[source]
        source: serde_json::Error,
    },

    #[error("{0}")]
    Validation(String),

    #[error("Instanz '{0}' wurde nicht gefunden")]
    InstanceNotFound(String),

    #[error("Minecraft-Version '{0}' ist unbekannt")]
    UnknownGameVersion(String),
}

impl Error {
    pub fn io(path: impl Into<PathBuf>, source: std::io::Error) -> Self {
        Self::Io { path: path.into(), source }
    }

    pub fn json(context: impl Into<String>, source: serde_json::Error) -> Self {
        Self::Json { context: context.into(), source }
    }

    pub fn validation(msg: impl Into<String>) -> Self {
        Self::Validation(msg.into())
    }

    /// Stabiler Bezeichner fürs Frontend.
    pub fn kind(&self) -> &'static str {
        match self {
            Self::Io { .. } => "io",
            Self::Http(_) => "network",
            Self::Json { .. } => "data",
            Self::Validation(_) => "validation",
            Self::InstanceNotFound(_) => "not_found",
            Self::UnknownGameVersion(_) => "unknown_version",
        }
    }

    /// Meldung, die dem Nutzer gezeigt werden darf – ohne Pfade, URLs oder
    /// sonstige interne Details. Die volle Meldung gehört nur ins Log.
    pub fn public_message(&self) -> String {
        match self {
            Self::Io { .. } => "Datei konnte nicht gelesen oder geschrieben werden.".into(),
            Self::Http(_) => "Netzwerkfehler – bitte Internetverbindung prüfen.".into(),
            Self::Json { .. } => "Daten konnten nicht verarbeitet werden.".into(),
            Self::Validation(_) | Self::InstanceNotFound(_) | Self::UnknownGameVersion(_) => {
                self.to_string()
            }
        }
    }
}
