//! Einwilligung und TRS-Tokens je Minecraft-Account in `<daten>/trs-api.json`.
//! Tokens liegen DPAPI-verschlüsselt auf der Platte (wie die Minecraft-Tokens)
//! und im Klartext nur im Arbeitsspeicher des Kerns.

use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::auth::crypto;
use crate::fsutil;

/// Tokens, die in weniger als dieser Zeit ablaufen, werden nicht mehr benutzt.
const EXPIRY_MARGIN_SECS: i64 = 5 * 60;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Consent {
    Accepted,
    Declined,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredToken {
    /// DPAPI-verschlüsselt.
    token: String,
    expires_at: DateTime<Utc>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StateFile {
    #[serde(default)]
    consent: Option<Consent>,
    #[serde(default)]
    decided_at: Option<DateTime<Utc>>,
    #[serde(default)]
    sessions: BTreeMap<String, StoredToken>,
}

struct Loaded {
    file: StateFile,
    /// Entschlüsselte Tokens (UUID → Token).
    plain: HashMap<String, String>,
}

pub(crate) struct Store {
    path: PathBuf,
    inner: Mutex<Option<Loaded>>,
}

impl Store {
    pub fn new(path: PathBuf) -> Self {
        Self { path, inner: Mutex::new(None) }
    }

    async fn with<R>(&self, f: impl FnOnce(&mut Loaded) -> R) -> R {
        let mut guard = self.inner.lock().await;
        if guard.is_none() {
            let file = match fsutil::read_json::<StateFile>(&self.path).await {
                Ok(file) => file.unwrap_or_default(),
                Err(e) => {
                    tracing::warn!("trs-api.json ist beschädigt – TRS-Anmeldung wird neu aufgebaut: {e}");
                    StateFile::default()
                }
            };
            *guard = Some(Loaded { file, plain: HashMap::new() });
        }
        f(guard.as_mut().expect("gerade geladen"))
    }

    async fn save(&self) {
        let json = {
            let guard = self.inner.lock().await;
            let Some(loaded) = guard.as_ref() else { return };
            match serde_json::to_vec_pretty(&loaded.file) {
                Ok(json) => json,
                Err(e) => {
                    tracing::warn!("trs-api.json konnte nicht serialisiert werden: {e}");
                    return;
                }
            }
        };
        if let Err(e) = fsutil::write_atomic(&self.path, &json).await {
            tracing::warn!("trs-api.json konnte nicht gespeichert werden: {e}");
        }
    }

    pub async fn consent(&self) -> Option<Consent> {
        self.with(|l| l.file.consent).await
    }

    pub async fn set_consent(&self, consent: Consent) {
        self.with(|l| {
            l.file.consent = Some(consent);
            l.file.decided_at = Some(Utc::now());
            if consent == Consent::Declined {
                l.file.sessions.clear();
                l.plain.clear();
            }
        })
        .await;
        self.save().await;
    }

    /// Gültiger Token für den Account (entschlüsselt bei Bedarf).
    pub async fn token(&self, account: &str) -> Option<String> {
        let (token, dirty) = self
            .with(|l| {
                let stored = l.file.sessions.get(account)?;
                if stored.expires_at - Utc::now() < chrono::Duration::seconds(EXPIRY_MARGIN_SECS) {
                    l.file.sessions.remove(account);
                    l.plain.remove(account);
                    return Some((None, true));
                }
                if let Some(plain) = l.plain.get(account) {
                    return Some((Some(plain.clone()), false));
                }
                match crypto::unprotect(&stored.token) {
                    Ok(plain) => {
                        l.plain.insert(account.to_owned(), plain.clone());
                        Some((Some(plain), false))
                    }
                    Err(_) => {
                        // Anderer Windows-Benutzer/Rechner: einfach neu anmelden.
                        l.file.sessions.remove(account);
                        Some((None, true))
                    }
                }
            })
            .await
            .unwrap_or((None, false));
        if dirty {
            self.save().await;
        }
        token
    }

    pub async fn put_token(&self, account: &str, token: &str, expires_at: DateTime<Utc>) -> crate::Result<()> {
        let encrypted = crypto::protect(token)?;
        self.with(|l| {
            l.file.sessions.insert(account.to_owned(), StoredToken { token: encrypted, expires_at });
            l.plain.insert(account.to_owned(), token.to_owned());
        })
        .await;
        self.save().await;
        Ok(())
    }

    /// Entfernt den Token; liefert ihn (für ein Logout beim Server), falls vorhanden.
    pub async fn take_token(&self, account: &str) -> Option<String> {
        let token = self.token(account).await;
        let removed = self
            .with(|l| {
                l.plain.remove(account);
                l.file.sessions.remove(account).is_some()
            })
            .await;
        if removed {
            self.save().await;
        }
        token
    }

    /// Accounts mit gespeichertem Token.
    pub async fn accounts(&self) -> Vec<String> {
        self.with(|l| l.file.sessions.keys().cloned().collect()).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn tokens_are_encrypted_and_expire() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("trs-api.json");
        let store = Store::new(path.clone());
        assert_eq!(store.consent().await, None);
        store.set_consent(Consent::Accepted).await;

        let token = format!("trs_{}", "A".repeat(43));
        store.put_token("a", &token, Utc::now() + chrono::Duration::days(30)).await.unwrap();
        store.put_token("b", &token, Utc::now() + chrono::Duration::seconds(10)).await.unwrap();
        let raw = std::fs::read_to_string(&path).unwrap();
        assert!(!raw.contains(&token), "Token nie im Klartext auf der Platte");

        // Neu laden (wie nach einem Neustart).
        let store = Store::new(path.clone());
        assert_eq!(store.consent().await, Some(Consent::Accepted));
        assert_eq!(store.token("a").await.as_deref(), Some(token.as_str()));
        assert_eq!(store.token("b").await, None, "läuft gleich ab");
        assert_eq!(store.accounts().await, vec!["a".to_owned()]);

        assert_eq!(store.take_token("a").await.as_deref(), Some(token.as_str()));
        assert_eq!(store.token("a").await, None);
    }

    #[tokio::test]
    async fn declining_forgets_all_tokens() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::new(dir.path().join("trs-api.json"));
        store.set_consent(Consent::Accepted).await;
        store.put_token("a", "trs_x", Utc::now() + chrono::Duration::days(1)).await.unwrap();
        store.set_consent(Consent::Declined).await;
        assert!(store.accounts().await.is_empty());
        assert_eq!(store.token("a").await, None);
    }
}
