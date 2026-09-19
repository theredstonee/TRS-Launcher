use serde::Serialize;

/// Was das Frontend von einem Fehler zu sehen bekommt: ein stabiler `kind`
/// und eine nutzertaugliche Meldung. Pfade, URLs und Fehlerketten bleiben
/// im Log.
#[derive(Debug, Serialize)]
pub struct CommandError {
    kind: &'static str,
    message: String,
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
            | trs_core::Error::Cancelled => log::debug!("{err}"),
            _ => log::error!("{err:?}"),
        }
        Self { kind: err.kind(), message: err.public_message() }
    }
}

impl From<tauri_plugin_opener::Error> for CommandError {
    fn from(err: tauri_plugin_opener::Error) -> Self {
        log::error!("Ordner konnte nicht geöffnet werden: {err:?}");
        Self { kind: "io", message: "Ordner konnte nicht geöffnet werden.".into() }
    }
}
