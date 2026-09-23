use serde::Serialize;

/// Was das Frontend von einem Fehler zu sehen bekommt: ein stabiler `kind`
/// und eine nutzertaugliche Meldung. Pfade, URLs und Fehlerketten bleiben
/// im Log.
#[derive(Debug, Serialize)]
pub struct CommandError {
    kind: &'static str,
    message: String,
    /// Genauer Fehlercode (z. B. von der TRS API), falls vorhanden.
    #[serde(skip_serializing_if = "Option::is_none")]
    code: Option<String>,
}

pub type CommandResult<T> = Result<T, CommandError>;

impl From<trs_core::Error> for CommandError {
    fn from(err: trs_core::Error) -> Self {
        match &err {
            trs_core::Error::Validation(_)
            | trs_core::Error::InstanceNotFound(_)
            | trs_core::Error::UnknownGameVersion(_)
            | trs_core::Error::Launch(_)
            | trs_core::Error::Auth(_)
            | trs_core::Error::AuthNotApproved
            | trs_core::Error::TrsApi { .. }
            | trs_core::Error::Cancelled => log::debug!("{err}"),
            _ => log::error!("{err:?}"),
        }
        Self { kind: err.kind(), message: err.public_message(), code: err.code().map(str::to_owned) }
    }
}

impl From<tauri_plugin_opener::Error> for CommandError {
    fn from(err: tauri_plugin_opener::Error) -> Self {
        log::error!("Ordner konnte nicht geöffnet werden: {err:?}");
        Self { kind: "io", message: "Ordner konnte nicht geöffnet werden.".into(), code: None }
    }
}
