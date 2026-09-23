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

    #[error("Download von {url} fehlgeschlagen: {reason}")]
    Download { url: String, reason: String },

    /// Meldung ist für den Nutzer formuliert.
    #[error("{0}")]
    Launch(String),

    /// Meldung ist für den Nutzer formuliert.
    #[error("{0}")]
    Auth(String),

    #[error("Die App-Registrierung ist von Mojang noch nicht freigegeben")]
    AuthNotApproved,

    #[error("Vorgang abgebrochen")]
    Cancelled,

    /// Fehler der TRS API. `kind` ist stabil (`trs_offline`, `trs_banned`, …),
    /// `code` der Fehlercode der API (z. B. `cape_locked`), die Meldung ist für
    /// den Nutzer formuliert.
    #[error("{message}")]
    TrsApi { kind: &'static str, code: String, message: String },

    #[error("Interner Fehler: {0}")]
    Internal(String),
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

    pub fn download(url: impl Into<String>, reason: impl Into<String>) -> Self {
        Self::Download { url: url.into(), reason: reason.into() }
    }

    pub fn launch(msg: impl Into<String>) -> Self {
        Self::Launch(msg.into())
    }

    pub fn auth(msg: impl Into<String>) -> Self {
        Self::Auth(msg.into())
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
            Self::Download { .. } => "download",
            Self::Launch(_) => "launch",
            Self::Auth(_) => "auth",
            Self::AuthNotApproved => "auth_not_approved",
            Self::Cancelled => "cancelled",
            Self::TrsApi { kind, .. } => kind,
            Self::Internal(_) => "internal",
        }
    }

    /// Genauer Fehlercode (bisher nur für die TRS API).
    pub fn code(&self) -> Option<&str> {
        match self {
            Self::TrsApi { code, .. } if !code.is_empty() => Some(code),
            _ => None,
        }
    }

    /// Meldung, die dem Nutzer gezeigt werden darf – ohne Pfade, URLs oder
    /// sonstige interne Details. Die volle Meldung gehört nur ins Log.
    pub fn public_message(&self) -> String {
        match self {
            Self::Io { .. } => "Datei konnte nicht gelesen oder geschrieben werden.".into(),
            Self::Http(_) => "Netzwerkfehler – bitte Internetverbindung prüfen.".into(),
            Self::Json { .. } => "Daten konnten nicht verarbeitet werden.".into(),
            Self::Download { .. } => {
                "Download fehlgeschlagen – bitte Internetverbindung prüfen und erneut versuchen.".into()
            }
            Self::AuthNotApproved => {
                "Der Microsoft-Login ist noch nicht freigeschaltet: Die App-Registrierung wartet \
                 auf die Freigabe durch Mojang."
                    .into()
            }
            Self::Internal(_) => "Ein interner Fehler ist aufgetreten.".into(),
            Self::Validation(_)
            | Self::InstanceNotFound(_)
            | Self::UnknownGameVersion(_)
            | Self::Launch(_)
            | Self::Auth(_)
            | Self::TrsApi { .. }
            | Self::Cancelled => self.to_string(),
        }
    }
}
