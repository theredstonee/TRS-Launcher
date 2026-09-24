//! Token-Verschlüsselung für `accounts.json` und `trs-api.json`.
//!
//! - Windows: DPAPI – die Daten sind an das Windows-Benutzerkonto gebunden.
//! - Linux: ChaCha20-Poly1305 mit einem Schlüssel aus dem Schlüsselbund
//!   (Secret Service), ersatzweise aus einer Datei mit Rechten 0600.
//!
//! Die eigentliche Arbeit steckt in [`crate::platform::secret`].

use std::path::Path;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;

use crate::platform::secret;
use crate::{Error, Result};

/// Richtet den Schlüssel ein (Linux: Schlüsselbund/Datei). Blockiert – nur
/// beim Start aus `spawn_blocking` aufrufen.
pub fn init(root: &Path) {
    secret::init(root);
}

/// Wie die Tokens geschützt sind: `dpapi`, `keyring`, `file` oder `none`.
pub fn protection() -> &'static str {
    secret::protection()
}

pub fn protect(plain: &str) -> Result<String> {
    let bytes = secret::encrypt(plain.as_bytes()).map_err(Error::Internal)?;
    Ok(STANDARD.encode(bytes))
}

pub fn unprotect(encoded: &str) -> Result<String> {
    let cipher = STANDARD
        .decode(encoded)
        .map_err(|_| Error::auth(crate::msg!("auth.storedCredentialsCorrupt", "Gespeicherte Anmeldedaten sind beschädigt – bitte erneut anmelden.")))?;
    let bytes = secret::decrypt(&cipher).ok_or_else(|| {
        Error::auth(crate::msg!("auth.storedCredentialsUndecryptable", "Gespeicherte Anmeldedaten konnten nicht entschlüsselt werden – bitte erneut anmelden."))
    })?;
    String::from_utf8(bytes).map_err(|_| Error::auth(crate::msg!("auth.storedCredentialsInvalid", "Gespeicherte Anmeldedaten sind beschädigt.")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let secret = "M.C123_refresh-token.äöü";
        let enc = protect(secret).unwrap();
        assert!(!enc.contains("refresh"));
        assert_ne!(enc, protect(secret).unwrap(), "jede Verschlüsselung ist anders");
        assert_eq!(unprotect(&enc).unwrap(), secret);
    }

    #[test]
    fn rejects_garbage() {
        assert!(unprotect("kein base64 !!!").is_err());
        assert!(unprotect(&STANDARD.encode(b"not a dpapi blob")).is_err());
        assert!(unprotect(&STANDARD.encode(b"x")).is_err());
    }
}
