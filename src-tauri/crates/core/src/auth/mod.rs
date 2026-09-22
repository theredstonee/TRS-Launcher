//! Account-Verwaltung: mehrere Microsoft-Accounts, einer ist aktiv.

mod crypto;
pub mod microsoft;

use std::sync::Arc;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::sync::{Mutex, Notify};

use crate::launch::Session;
use crate::paths::Paths;
use crate::{Error, Result, fsutil};
pub use microsoft::DeviceCode;
use microsoft::{MinecraftSession, MsTokens};

/// Minecraft-Tokens gelten 24 h; mit diesem Puffer wird vorher erneuert.
const REFRESH_MARGIN_SECS: i64 = 10 * 60;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredAccount {
    /// Minecraft-UUID ohne Bindestriche.
    id: String,
    name: String,
    skin_url: Option<String>,
    xuid: String,
    /// DPAPI-verschlüsselt, siehe [`crypto`].
    refresh_token: String,
    access_token: String,
    access_expires_at: DateTime<Utc>,
    added_at: DateTime<Utc>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct AccountsFile {
    active: Option<String>,
    #[serde(default)]
    accounts: Vec<StoredAccount>,
}

/// Was das Frontend sieht – ohne Tokens.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    pub id: String,
    pub name: String,
    pub skin_url: Option<String>,
    pub active: bool,
    pub added_at: DateTime<Utc>,
}

pub struct AccountStore {
    paths: Paths,
    http: reqwest::Client,
    file_lock: Mutex<()>,
    /// Es läuft höchstens ein Login; `notify` bricht ihn ab.
    login: Mutex<Option<Arc<Notify>>>,
}

impl AccountStore {
    pub fn new(paths: Paths, http: reqwest::Client) -> Self {
        Self { paths, http, file_lock: Mutex::new(()), login: Mutex::new(None) }
    }

    async fn read(&self) -> Result<AccountsFile> {
        match fsutil::read_json(&self.paths.accounts_file()).await {
            Ok(file) => Ok(file.unwrap_or_default()),
            Err(Error::Json { .. }) => {
                tracing::warn!("accounts.json ist beschädigt – Accounts müssen neu angemeldet werden");
                Ok(AccountsFile::default())
            }
            Err(e) => Err(e),
        }
    }

    async fn write(&self, file: &AccountsFile) -> Result<()> {
        fsutil::write_json(&self.paths.accounts_file(), file).await
    }

    pub async fn list(&self) -> Result<Vec<Account>> {
        let file = self.read().await?;
        Ok(file
            .accounts
            .iter()
            .map(|a| Account {
                id: a.id.clone(),
                name: a.name.clone(),
                skin_url: a.skin_url.clone(),
                active: file.active.as_deref() == Some(a.id.as_str()),
                added_at: a.added_at,
            })
            .collect())
    }

    pub async fn set_active(&self, id: &str) -> Result<()> {
        let _guard = self.file_lock.lock().await;
        let mut file = self.read().await?;
        if !file.accounts.iter().any(|a| a.id == id) {
            return Err(Error::auth("Dieser Account existiert nicht mehr."));
        }
        file.active = Some(id.to_owned());
        self.write(&file).await
    }

    /// Merkt sich die aktuelle Skin-Textur (nach einem Skin-Wechsel).
    /// Unbekannte Accounts werden still übergangen.
    pub async fn set_skin_url(&self, id: &str, url: &str) -> Result<()> {
        let _guard = self.file_lock.lock().await;
        let mut file = self.read().await?;
        let Some(account) = file.accounts.iter_mut().find(|a| a.id == id) else { return Ok(()) };
        if account.skin_url.as_deref() == Some(url) {
            return Ok(());
        }
        account.skin_url = Some(url.to_owned());
        self.write(&file).await
    }

    pub async fn remove(&self, id: &str) -> Result<()> {
        let _guard = self.file_lock.lock().await;
        let mut file = self.read().await?;
        file.accounts.retain(|a| a.id != id);
        if file.active.as_deref() == Some(id) {
            file.active = file.accounts.first().map(|a| a.id.clone());
        }
        self.write(&file).await
    }

    /// Login im Browser (Auth-Code + PKCE über Loopback).
    pub async fn login_browser(&self, open_url: &(dyn Fn(&str) + Sync)) -> Result<Account> {
        let cancel = self.begin_login().await?;
        let result = tokio::select! {
            r = async {
                let tokens = microsoft::browser_login(&self.http, open_url).await?;
                self.finish_login(tokens).await
            } => r,
            () = cancel.notified() => Err(Error::Cancelled),
        };
        self.end_login().await;
        result
    }

    /// Login per Device-Code; `on_code` bekommt den Code zum Anzeigen.
    pub async fn login_device_code(&self, on_code: &(dyn Fn(&DeviceCode) + Sync)) -> Result<Account> {
        let cancel = self.begin_login().await?;
        let result = tokio::select! {
            r = async {
                let code = microsoft::device_code_start(&self.http).await?;
                on_code(&code);
                let tokens = microsoft::device_code_poll(&self.http, &code).await?;
                self.finish_login(tokens).await
            } => r,
            () = cancel.notified() => Err(Error::Cancelled),
        };
        self.end_login().await;
        result
    }

    pub async fn cancel_login(&self) {
        if let Some(notify) = self.login.lock().await.as_ref() {
            notify.notify_one();
        }
    }

    async fn begin_login(&self) -> Result<Arc<Notify>> {
        let mut slot = self.login.lock().await;
        if slot.is_some() {
            return Err(Error::auth("Es läuft bereits eine Anmeldung."));
        }
        let notify = Arc::new(Notify::new());
        *slot = Some(notify.clone());
        Ok(notify)
    }

    async fn end_login(&self) {
        *self.login.lock().await = None;
    }

    async fn finish_login(&self, tokens: MsTokens) -> Result<Account> {
        let session = microsoft::minecraft_login(&self.http, &tokens.access_token).await?;
        let stored = self.upsert(&tokens, &session, true).await?;
        Ok(Account {
            id: stored.id,
            name: stored.name,
            skin_url: stored.skin_url,
            active: true,
            added_at: stored.added_at,
        })
    }

    async fn upsert(&self, tokens: &MsTokens, session: &MinecraftSession, make_active: bool) -> Result<StoredAccount> {
        let _guard = self.file_lock.lock().await;
        let mut file = self.read().await?;
        let added_at =
            file.accounts.iter().find(|a| a.id == session.uuid).map_or_else(Utc::now, |a| a.added_at);

        let stored = StoredAccount {
            id: session.uuid.clone(),
            name: session.name.clone(),
            skin_url: session.skin_url.clone(),
            xuid: session.xuid.clone(),
            refresh_token: crypto::protect(&tokens.refresh_token)?,
            access_token: crypto::protect(&session.access_token)?,
            access_expires_at: session.expires_at,
            added_at,
        };
        file.accounts.retain(|a| a.id != stored.id);
        file.accounts.push(stored.clone());
        if make_active || file.active.is_none() {
            file.active = Some(stored.id.clone());
        }
        self.write(&file).await?;
        Ok(stored)
    }

    /// Spielsitzung für den aktiven Account; erneuert abgelaufene Tokens.
    /// `Ok(None)`, wenn niemand angemeldet ist.
    pub async fn active_session(&self) -> Result<Option<Session>> {
        let file = self.read().await?;
        let Some(account) = file.active.as_ref().and_then(|id| file.accounts.iter().find(|a| &a.id == id))
        else {
            return Ok(None);
        };

        let fresh = account.access_expires_at - Utc::now() > chrono::Duration::seconds(REFRESH_MARGIN_SECS);
        if fresh {
            return Ok(Some(Session {
                player_name: account.name.clone(),
                uuid: account.id.clone(),
                access_token: crypto::unprotect(&account.access_token)?,
                xuid: account.xuid.clone(),
                demo: false,
            }));
        }

        tracing::info!("Erneuere Sitzung für {}", account.name);
        let refresh_token = crypto::unprotect(&account.refresh_token)?;
        // Microsoft rotiert Refresh-Tokens – das neue muss gespeichert werden.
        let renewed = async {
            let tokens = microsoft::refresh(&self.http, &refresh_token).await?;
            let session = microsoft::minecraft_login(&self.http, &tokens.access_token).await?;
            Ok::<_, Error>((tokens, session))
        }
        .await;
        let (tokens, session) = match renewed {
            Ok(pair) => pair,
            // Kein Internet: mit dem alten Token weiterspielen (Einzelspieler
            // klappt, Server lehnen es dann selbst ab) statt den Start zu blockieren.
            Err(Error::Http(e)) if e.is_connect() || e.is_timeout() => {
                tracing::warn!("Sitzung konnte offline nicht erneuert werden – nutze vorhandenes Token");
                return Ok(Some(Session {
                    player_name: account.name.clone(),
                    uuid: account.id.clone(),
                    access_token: crypto::unprotect(&account.access_token)?,
                    xuid: account.xuid.clone(),
                    demo: false,
                }));
            }
            Err(e) => return Err(e),
        };
        if session.uuid != account.id {
            return Err(Error::auth("Die Anmeldung gehört zu einem anderen Account – bitte erneut anmelden."));
        }
        self.upsert(&tokens, &session, false).await?;

        Ok(Some(Session {
            player_name: session.name,
            uuid: session.uuid,
            access_token: session.access_token,
            xuid: session.xuid,
            demo: false,
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn store() -> (tempfile::TempDir, AccountStore) {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        (dir, AccountStore::new(paths, reqwest::Client::new()))
    }

    fn session(uuid: &str, name: &str, expires_in_secs: i64) -> MinecraftSession {
        MinecraftSession {
            access_token: format!("mc-token-{name}"),
            expires_at: Utc::now() + chrono::Duration::seconds(expires_in_secs),
            uuid: uuid.into(),
            name: name.into(),
            skin_url: None,
            xuid: "1".into(),
        }
    }

    fn tokens(name: &str) -> MsTokens {
        MsTokens { access_token: "ms".into(), refresh_token: format!("refresh-{name}") }
    }

    #[tokio::test]
    async fn multiple_accounts_and_active_switching() {
        let (dir, store) = store().await;
        let (a, b) = ("a".repeat(32), "b".repeat(32));

        store.upsert(&tokens("Alex"), &session(&a, "Alex", 3600), true).await.unwrap();
        store.upsert(&tokens("Steve"), &session(&b, "Steve", 3600), true).await.unwrap();

        let list = store.list().await.unwrap();
        assert_eq!(list.len(), 2);
        assert!(list.iter().find(|x| x.name == "Steve").unwrap().active);

        store.set_active(&a).await.unwrap();
        let s = store.active_session().await.unwrap().unwrap();
        assert_eq!((s.player_name.as_str(), s.access_token.as_str()), ("Alex", "mc-token-Alex"));

        // Erneuter Login desselben Accounts erzeugt kein Duplikat.
        store.upsert(&tokens("Alex"), &session(&a, "Alex2", 3600), false).await.unwrap();
        assert_eq!(store.list().await.unwrap().len(), 2);

        // Aktiven Account entfernen → ein anderer rückt nach.
        store.remove(&a).await.unwrap();
        let list = store.list().await.unwrap();
        assert_eq!(list.len(), 1);
        assert!(list[0].active);

        assert!(store.set_active("gibt-es-nicht").await.is_err());

        // Tokens stehen nicht im Klartext auf der Platte.
        let raw = tokio::fs::read_to_string(dir.path().join("accounts.json")).await.unwrap();
        assert!(!raw.contains("mc-token") && !raw.contains("refresh-Steve"));
    }

    #[tokio::test]
    async fn no_account_means_no_session() {
        let (_dir, store) = store().await;
        assert!(store.active_session().await.unwrap().is_none());
    }

    #[tokio::test]
    async fn only_one_login_at_a_time_and_cancel_works() {
        let (_dir, store) = store().await;
        let store = Arc::new(store);

        let first = store.begin_login().await.unwrap();
        assert!(store.begin_login().await.is_err());
        store.cancel_login().await;
        // `notify_one` speichert ein Permit – der wartende Login sieht den Abbruch sofort.
        tokio::time::timeout(std::time::Duration::from_secs(1), first.notified()).await.unwrap();
        store.end_login().await;
        assert!(store.begin_login().await.is_ok());
    }
}
