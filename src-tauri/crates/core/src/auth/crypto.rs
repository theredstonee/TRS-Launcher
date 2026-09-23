//! Token-Verschlüsselung über die Windows-DPAPI: Die Daten sind an das
//! Windows-Benutzerkonto gebunden – eine kopierte `accounts.json` ist auf
//! einem anderen Rechner oder unter einem anderen Benutzer wertlos.

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use windows::Win32::Foundation::{HLOCAL, LocalFree};
use windows::Win32::Security::Cryptography::{
    CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
};
use windows::core::PCWSTR;

use crate::{Error, Result};

/// Zusätzliche Entropie: Andere Programme desselben Benutzers können die
/// Blobs nicht einfach mit einem nackten `CryptUnprotectData` öffnen.
const ENTROPY: &[u8] = b"TRS-Launcher/accounts/v1";

fn blob(data: &[u8]) -> CRYPT_INTEGER_BLOB {
    CRYPT_INTEGER_BLOB { cbData: data.len() as u32, pbData: data.as_ptr().cast_mut() }
}

/// Übernimmt den von der DPAPI allokierten Puffer und gibt ihn frei.
///
/// # Safety
/// `out` muss von `CryptProtectData`/`CryptUnprotectData` befüllt worden sein.
unsafe fn take(out: CRYPT_INTEGER_BLOB) -> Vec<u8> {
    if out.pbData.is_null() {
        return Vec::new();
    }
    // SAFETY: Die DPAPI garantiert `cbData` gültige Bytes ab `pbData`; der
    // Puffer stammt aus LocalAlloc und gehört nach dem Aufruf uns.
    unsafe {
        let bytes = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
        LocalFree(Some(HLOCAL(out.pbData.cast())));
        bytes
    }
}

pub fn protect(plain: &str) -> Result<String> {
    let input = blob(plain.as_bytes());
    let entropy = blob(ENTROPY);
    let mut out = CRYPT_INTEGER_BLOB::default();
    // SAFETY: Alle Zeiger verweisen auf lebende Puffer; `out` wird von der API befüllt.
    let bytes = unsafe {
        CryptProtectData(
            &input,
            PCWSTR::null(),
            Some(&entropy),
            None,
            None,
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut out,
        )
        .map_err(|e| Error::Internal(format!("DPAPI-Verschlüsselung fehlgeschlagen: {e}")))?;
        take(out)
    };
    Ok(STANDARD.encode(bytes))
}

pub fn unprotect(encoded: &str) -> Result<String> {
    let cipher = STANDARD
        .decode(encoded)
        .map_err(|_| Error::auth(crate::msg!("auth.storedCredentialsCorrupt", "Gespeicherte Anmeldedaten sind beschädigt – bitte erneut anmelden.")))?;
    let input = blob(&cipher);
    let entropy = blob(ENTROPY);
    let mut out = CRYPT_INTEGER_BLOB::default();
    // SAFETY: wie oben.
    let bytes = unsafe {
        CryptUnprotectData(&input, None, Some(&entropy), None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut out)
            .map_err(|_| {
                Error::auth(crate::msg!("auth.storedCredentialsUndecryptable", "Gespeicherte Anmeldedaten konnten nicht entschlüsselt werden – bitte erneut anmelden."))
            })?;
        take(out)
    };
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
        assert_eq!(unprotect(&enc).unwrap(), secret);
    }

    #[test]
    fn rejects_garbage() {
        assert!(unprotect("kein base64 !!!").is_err());
        assert!(unprotect(&STANDARD.encode(b"not a dpapi blob")).is_err());
    }
}
