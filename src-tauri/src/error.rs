use serde::Serialize;
use trs_core::error::UserError;

/// Was das Frontend von einem Fehler zu sehen bekommt: ein stabiler `kind`,
/// ein Übersetzungs-Code (`errors.<code>`) mit Parametern und eine deutsche
/// Rückfall-Meldung. Pfade, URLs und Fehlerketten bleiben im Log.
#[derive(Debug, Serialize)]
#[serde(transparent)]
pub struct CommandError(UserError);

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
        Self(err.to_user())
    }
}

impl From<tauri_plugin_opener::Error> for CommandError {
    fn from(err: tauri_plugin_opener::Error) -> Self {
        log::error!("Ordner konnte nicht geöffnet werden: {err:?}");
        Self(UserError {
            kind: "io",
            code: "openFolder".into(),
            params: serde_json::Map::new(),
            message: "Ordner konnte nicht geöffnet werden.".into(),
            api_code: None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serializes_code_params_and_fallback() {
        let err: CommandError =
            trs_core::Error::validation(trs_core::msg!("test.range", "Zwischen {min} und 9", min = 1)).into();
        let json = serde_json::to_value(&err).unwrap();
        assert_eq!(json["kind"], "validation");
        assert_eq!(json["code"], "test.range");
        assert_eq!(json["params"]["min"], "1");
        assert_eq!(json["message"], "Zwischen 1 und 9");
    }
}
