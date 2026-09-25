//! `<daten>/link-sessions.json`: Link-Sitzungen laufender Spiele, damit ein
//! neu gestarteter Launcher sie wieder annehmen kann (die Mod behält ihren
//! Schlüssel aus der Umgebungsvariable). Der Schlüssel liegt nur verschlüsselt
//! (DPAPI/Schlüsselbund, siehe [`crate::auth::crypto`]) auf der Platte.

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::auth::crypto;
use crate::{Result, fsutil};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredSession {
    pub instance_id: String,
    pub sid: String,
    /// Verschlüsselter Hex-Schlüssel.
    pub secret: String,
    pub pid: Option<u32>,
    /// Mod ≤ 0.5.0 (Clips nur über das alte Token in clips.json).
    #[serde(default)]
    pub legacy: bool,
}

/// Eine wiederhergestellte Sitzung (Schlüssel entschlüsselt).
#[derive(Debug, Clone)]
pub struct Restored {
    pub instance_id: String,
    pub sid: String,
    pub key: Vec<u8>,
    pub pid: Option<u32>,
    pub legacy: bool,
}

pub fn protect_key(key: &[u8]) -> Result<String> {
    crypto::protect(&super::proto::hex(key))
}

pub async fn save(file: &Path, sessions: &[StoredSession]) -> Result<()> {
    if sessions.is_empty() {
        let _ = tokio::fs::remove_file(file).await;
        return Ok(());
    }
    fsutil::write_json(file, &sessions).await
}

/// Liest die Datei; unlesbare/unentschlüsselbare Einträge fallen weg.
pub async fn load(file: &Path) -> Vec<Restored> {
    let stored: Vec<StoredSession> = match fsutil::read_json(file).await {
        Ok(Some(list)) => list,
        _ => return Vec::new(),
    };
    stored
        .into_iter()
        .filter_map(|s| {
            let hex = crypto::unprotect(&s.secret).ok()?;
            let key = super::proto::unhex(&hex).filter(|k| k.len() == super::proto::KEY_LEN)?;
            super::proto::is_hex(&s.sid, super::proto::SID_HEX_LEN).then_some(Restored {
                instance_id: s.instance_id,
                sid: s.sid,
                key,
                pid: s.pid,
                legacy: s.legacy,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn rundreise_ohne_klartext() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("link-sessions.json");
        let key: Vec<u8> = (100u8..132).collect();
        let hex = super::super::proto::hex(&key);
        let stored = vec![StoredSession {
            instance_id: "survival".into(),
            sid: "0123456789abcdef".into(),
            secret: protect_key(&key).unwrap(),
            pid: Some(42),
            legacy: false,
        }];
        save(&file, &stored).await.unwrap();
        let raw = std::fs::read_to_string(&file).unwrap();
        assert!(!raw.contains(&hex), "Schlüssel nie im Klartext");
        let back = load(&file).await;
        assert_eq!(back.len(), 1);
        assert_eq!((back[0].instance_id.as_str(), back[0].pid, back[0].key.clone()), ("survival", Some(42), key));
        save(&file, &[]).await.unwrap();
        assert!(!file.exists());
        assert!(load(&file).await.is_empty());
    }
}
