use std::fmt;
use std::path::PathBuf;

use serde::Serialize;

pub type Result<T> = std::result::Result<T, Error>;

/// Nutzertaugliche Meldung mit stabilem Code.
///
/// Das Frontend übersetzt `code` (Schlüssel `errors.<code>` in
/// `app/locales/*.json`) und setzt `params` ein; `text` ist die deutsche
/// Rückfall-Meldung für Logs und alte Oberflächen.
///
/// Erzeugt wird sie mit [`msg!`](crate::msg):
/// `msg!("settings.memoryRange", "Arbeitsspeicher muss zwischen {min} und {max} MB liegen", min = 512, max = 4096)`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Msg {
    pub code: &'static str,
    pub params: Vec<(&'static str, String)>,
    pub text: String,
}

impl Msg {
    pub fn new(code: &'static str, text: String, params: Vec<(&'static str, String)>) -> Self {
        Self { code, params, text }
    }

    /// Parameter als JSON-Objekt (fürs Frontend).
    pub fn params_json(&self) -> serde_json::Map<String, serde_json::Value> {
        self.params.iter().map(|(k, v)| ((*k).to_owned(), serde_json::Value::String(v.clone()))).collect()
    }
}

/// Als Daten (z. B. in Events): `{ "code", "params", "message" }` – das
/// Frontend übersetzt es wie einen Fehler (`userErrorText`).
impl Serialize for Msg {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error> {
        use serde::ser::SerializeStruct;
        let mut s = serializer.serialize_struct("Msg", 3)?;
        s.serialize_field("code", self.code)?;
        s.serialize_field("params", &self.params_json())?;
        s.serialize_field("message", &self.text)?;
        s.end()
    }
}

impl fmt::Display for Msg {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.text)
    }
}

/// Baut eine [`Msg`]: stabiler Code, deutsche Rückfall-Meldung und benannte
/// Parameter. Die Parameter werden in die Meldung eingesetzt (`{name}`) und
/// gehen zusätzlich als Text ans Frontend, das `{name}` in der Übersetzung
/// genauso ersetzt.
#[macro_export]
macro_rules! msg {
    ($code:literal, $text:literal $(,)?) => {
        $crate::error::Msg::new($code, ::std::string::String::from($text), ::std::vec::Vec::new())
    };
    ($code:literal, $text:literal, $($name:ident = $value:expr),+ $(,)?) => {{
        $(#[allow(clippy::redundant_locals)]
        let $name = $value;)+
        $crate::error::Msg::new(
            $code,
            ::std::format!($text),
            ::std::vec![$((::std::stringify!($name), ::std::string::ToString::to_string(&$name))),+],
        )
    }};
}

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
    Validation(Msg),

    #[error("Instanz '{0}' wurde nicht gefunden")]
    InstanceNotFound(String),

    #[error("Minecraft-Version '{0}' ist unbekannt")]
    UnknownGameVersion(String),

    #[error("Download von {url} fehlgeschlagen: {reason}")]
    Download { url: String, reason: String },

    /// Meldung ist für den Nutzer formuliert.
    #[error("{0}")]
    Launch(Msg),

    /// Meldung ist für den Nutzer formuliert.
    #[error("{0}")]
    Auth(Msg),

    #[error("Die App-Registrierung ist von Mojang noch nicht freigegeben")]
    AuthNotApproved,

    #[error("Vorgang abgebrochen")]
    Cancelled,

    /// Fehler der TRS API. `kind` ist stabil (`trs_offline`, `trs_banned`, …),
    /// `code` der Fehlercode der API (z. B. `cape_locked`), `msg` die
    /// Meldung für den Nutzer (mit eigenem Übersetzungs-Code).
    #[error("{msg}")]
    TrsApi { kind: &'static str, code: String, msg: Msg },

    #[error("Interner Fehler: {0}")]
    Internal(String),
}

/// Was das Frontend von einem Fehler zu sehen bekommt: stabiler `kind`,
/// Übersetzungs-Code + Parameter und eine deutsche Rückfall-Meldung ohne
/// Pfade, URLs oder Fehlerketten (die bleiben im Log).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UserError {
    pub kind: &'static str,
    pub code: String,
    #[serde(skip_serializing_if = "serde_json::Map::is_empty")]
    pub params: serde_json::Map<String, serde_json::Value>,
    pub message: String,
    /// Fehlercode der TRS API (z. B. `cape_locked`), falls vorhanden.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub api_code: Option<String>,
}

impl Error {
    pub fn io(path: impl Into<PathBuf>, source: std::io::Error) -> Self {
        Self::Io { path: path.into(), source }
    }

    pub fn json(context: impl Into<String>, source: serde_json::Error) -> Self {
        Self::Json { context: context.into(), source }
    }

    pub fn validation(msg: Msg) -> Self {
        Self::Validation(msg)
    }

    pub fn download(url: impl Into<String>, reason: impl Into<String>) -> Self {
        Self::Download { url: url.into(), reason: reason.into() }
    }

    pub fn launch(msg: Msg) -> Self {
        Self::Launch(msg)
    }

    pub fn auth(msg: Msg) -> Self {
        Self::Auth(msg)
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

    /// Fehlercode der TRS API (z. B. `cape_locked`).
    pub fn code(&self) -> Option<&str> {
        match self {
            Self::TrsApi { code, .. } if !code.is_empty() => Some(code),
            _ => None,
        }
    }

    /// Übersetzungs-Code (`errors.<code>` im Frontend).
    pub fn message_code(&self) -> &'static str {
        match self {
            Self::Io { .. } => "io",
            Self::Http(_) => "network",
            Self::Json { .. } => "data",
            Self::Download { .. } => "download",
            Self::InstanceNotFound(_) => "instanceNotFound",
            Self::UnknownGameVersion(_) => "unknownVersion",
            Self::AuthNotApproved => "authNotApproved",
            Self::Cancelled => "cancelled",
            Self::Internal(_) => "internal",
            Self::Validation(m) | Self::Launch(m) | Self::Auth(m) | Self::TrsApi { msg: m, .. } => m.code,
        }
    }

    /// Parameter zur Übersetzung.
    pub fn message_params(&self) -> serde_json::Map<String, serde_json::Value> {
        match self {
            Self::InstanceNotFound(id) => [("id".to_owned(), serde_json::Value::String(id.clone()))].into_iter().collect(),
            Self::UnknownGameVersion(v) => {
                [("version".to_owned(), serde_json::Value::String(v.clone()))].into_iter().collect()
            }
            Self::Validation(m) | Self::Launch(m) | Self::Auth(m) | Self::TrsApi { msg: m, .. } => m.params_json(),
            _ => serde_json::Map::new(),
        }
    }

    /// Meldung, die dem Nutzer gezeigt werden darf – ohne Pfade, URLs oder
    /// sonstige interne Details. Die volle Meldung gehört nur ins Log.
    /// Deutsch; das Frontend übersetzt über [`Error::message_code`].
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

    /// Alles, was das Frontend über den Fehler wissen darf.
    pub fn to_user(&self) -> UserError {
        UserError {
            kind: self.kind(),
            code: self.message_code().to_owned(),
            params: self.message_params(),
            message: self.public_message(),
            api_code: self.code().map(str::to_owned),
        }
    }
}

impl From<&Error> for UserError {
    fn from(err: &Error) -> Self {
        err.to_user()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn msg_macro_fills_text_and_params() {
        let min = 512;
        let m = crate::msg!("test.range", "Zwischen {min} und {max} MB", min = min, max = 4096u32);
        assert_eq!(m.code, "test.range");
        assert_eq!(m.text, "Zwischen 512 und 4096 MB");
        assert_eq!(m.params, vec![("min", "512".to_owned()), ("max", "4096".to_owned())]);

        let plain = crate::msg!("test.plain", "Java-Pfad ist ungültig");
        assert_eq!(plain.text, "Java-Pfad ist ungültig");
        assert!(plain.params.is_empty());
    }

    #[test]
    fn user_error_carries_code_params_and_fallback() {
        let err = Error::validation(crate::msg!("test.tooLong", "Name zu lang (max. {max})", max = 64));
        let user = err.to_user();
        assert_eq!(user.kind, "validation");
        assert_eq!(user.code, "test.tooLong");
        assert_eq!(user.message, "Name zu lang (max. 64)");
        let json = serde_json::to_value(&user).unwrap();
        assert_eq!(json["params"]["max"], "64");
        assert!(json.get("apiCode").is_none());

        // Fehler ohne eigene Meldung bekommen feste Codes, interne Details bleiben draußen.
        let io = Error::io("C:\\geheim\\datei", std::io::Error::other("x")).to_user();
        assert_eq!((io.kind, io.code.as_str()), ("io", "io"));
        assert!(!io.message.contains("geheim"));
        let json = serde_json::to_value(&io).unwrap();
        assert!(json.get("params").is_none());

        let missing = Error::InstanceNotFound("abc".into()).to_user();
        assert_eq!(missing.code, "instanceNotFound");
        assert_eq!(missing.params["id"], "abc");
        assert_eq!(Error::Cancelled.to_user().code, "cancelled");
        assert_eq!(Error::AuthNotApproved.to_user().code, "authNotApproved");
    }

    #[test]
    fn trs_api_error_exposes_api_code() {
        let err = Error::TrsApi {
            kind: "trs_api",
            code: "cape_locked".into(),
            msg: crate::msg!("test.capeLocked", "Gesperrt"),
        };
        let json = serde_json::to_value(err.to_user()).unwrap();
        assert_eq!(json["kind"], "trs_api");
        assert_eq!(json["code"], "test.capeLocked");
        assert_eq!(json["apiCode"], "cape_locked");
        assert_eq!(json["message"], "Gesperrt");
    }
}
