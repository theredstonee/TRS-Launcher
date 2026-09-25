//! Konten des Launchers für den TRS-Link (Kontowechsel im Spiel).

use std::sync::Weak;

use futures::future::BoxFuture;

use super::{AccountsHandler, HandlerResult, LinkAccount, LinkSession};
use crate::{Error, Launcher};

pub(crate) struct AccountsBridge {
    pub(crate) launcher: Weak<Launcher>,
}

/// Fester Fehlercode für die Mod – nie die interne Meldung.
fn code(e: &Error) -> &'static str {
    match e {
        Error::Cancelled => "cancelled",
        Error::Http(_) => "offline",
        Error::Auth(msg) if msg.code == "auth.loginInProgress" => "busy",
        Error::Auth(_) | Error::AuthNotApproved => "auth_failed",
        _ => "error",
    }
}

impl AccountsHandler for AccountsBridge {
    fn list(&self) -> BoxFuture<'static, HandlerResult<Vec<LinkAccount>>> {
        let launcher = self.launcher.clone();
        Box::pin(async move {
            let launcher = launcher.upgrade().ok_or("error")?;
            let accounts = launcher.accounts.list().await.map_err(|e| code(&e))?;
            Ok(accounts
                .into_iter()
                .map(|a| LinkAccount { id: a.id, name: a.name, skin_url: a.skin_url, active: a.active })
                .collect())
        })
    }

    fn session(&self, instance_id: String, account: String) -> BoxFuture<'static, HandlerResult<LinkSession>> {
        let launcher = self.launcher.clone();
        Box::pin(async move {
            let launcher = launcher.upgrade().ok_or("error")?;
            let session = match launcher.accounts.session_for(&account, false).await {
                Ok(Some(session)) => session,
                Ok(None) => return Err("unknown_account"),
                Err(e) => {
                    tracing::warn!("Sitzung für den Kontowechsel im Spiel fehlgeschlagen: {e}");
                    return Err(code(&e));
                }
            };
            tracing::info!("Kontowechsel im Spiel ('{instance_id}') → {}", session.player_name);
            // In diesem Spiel spielt jetzt dieses Konto (Präsenz des Launchers).
            launcher.trs.presence.game_started(&instance_id, Some(&session.uuid));
            launcher.trs.presence_kick();
            Ok(LinkSession { id: session.uuid, name: session.player_name, xuid: session.xuid, token: session.access_token })
        })
    }

    fn add(&self, instance_id: String) -> BoxFuture<'static, HandlerResult<LinkAccount>> {
        let launcher = self.launcher.clone();
        Box::pin(async move {
            let launcher = launcher.upgrade().ok_or("error")?;
            let opener = launcher.url_opener().ok_or("error")?;
            let open = move |url: &str| opener(url);
            tracing::info!("Konto hinzufügen aus dem Spiel ('{instance_id}')");
            let account = launcher.accounts.login_browser_as(&open, false).await.map_err(|e| {
                tracing::warn!("Konto hinzufügen aus dem Spiel fehlgeschlagen: {e}");
                code(&e)
            })?;
            launcher.emit_accounts_changed();
            Ok(LinkAccount { id: account.id, name: account.name, skin_url: account.skin_url, active: account.active })
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fehlercodes_verraten_nichts() {
        assert_eq!(code(&Error::Cancelled), "cancelled");
        assert_eq!(code(&Error::auth(crate::msg!("auth.loginInProgress", "x"))), "busy");
        assert_eq!(code(&Error::auth(crate::msg!("auth.sessionExpired", "x"))), "auth_failed");
        assert_eq!(code(&Error::Internal("geheim".into())), "error");
    }
}
