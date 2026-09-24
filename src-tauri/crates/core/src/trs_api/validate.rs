//! Eingaben und Antworten prüfen – dieselben Regeln wie die API (`schemas.ts`,
//! `ids.ts`), damit offensichtlich ungültige Anfragen gar nicht erst rausgehen.

use crate::{Error, Result};

/// Minecraft-UUID mit oder ohne Bindestriche → 32 Hex-Zeichen klein.
pub fn uuid(input: &str) -> Option<String> {
    let s = input.trim().to_ascii_lowercase();
    let plain = if s.len() == 36 {
        let dashes_ok = [8, 13, 18, 23].iter().all(|&i| s.as_bytes()[i] == b'-');
        if !dashes_ok {
            return None;
        }
        s.replace('-', "")
    } else {
        s
    };
    (plain.len() == 32 && plain.bytes().all(|b| b.is_ascii_hexdigit())).then_some(plain)
}

/// `^[A-Za-z0-9_]{1,16}$`
pub fn mc_name(input: &str) -> bool {
    (1..=16).contains(&input.len()) && input.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

/// `^[a-z0-9][a-z0-9_-]{0,39}$`
pub fn cape_id(input: &str) -> bool {
    let bytes = input.as_bytes();
    (1..=40).contains(&bytes.len())
        && (bytes[0].is_ascii_lowercase() || bytes[0].is_ascii_digit())
        && bytes.iter().all(|&b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-')
}

/// Hochgeladene Umhänge heißen `u` + 20 Hex-Zeichen.
pub fn is_upload_id(id: &str) -> bool {
    id.len() == 21 && id.starts_with('u') && id[1..].bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

/// `^[0-9a-f]{40}$` (Challenge der API).
pub fn server_id(input: &str) -> bool {
    input.len() == 40 && input.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// `^trs_[A-Za-z0-9_-]{43}$`
pub fn session_token(input: &str) -> bool {
    input.len() == 47
        && input.starts_with("trs_")
        && input[4..].bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

/// Freundschafts-/Block-Ziel: Name oder UUID (wie `targetBody`).
pub fn target(input: &str) -> Result<String> {
    let t = input.trim();
    if let Some(u) = uuid(t) {
        return Ok(u);
    }
    if mc_name(t) {
        return Ok(t.to_owned());
    }
    Err(Error::validation(crate::msg!(
        "trsValidate.invalidName",
        "Bitte einen Minecraft-Namen (1–16 Zeichen: A–Z, 0–9, _) eingeben."
    )))
}

/// Einlösecode normalisieren: Groß, ohne Trenner, O→0, I/L→1, 20 Zeichen Crockford-Base32.
pub fn redeem_code(input: &str) -> Option<String> {
    if input.len() > 64 {
        return None;
    }
    let s: String = input
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .map(|c| match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            other => other,
        })
        .collect();
    let crockford = |c: char| c.is_ascii_digit() || (c.is_ascii_uppercase() && !matches!(c, 'I' | 'L' | 'O' | 'U'));
    (s.chars().count() == 20 && s.chars().all(crockford)).then_some(s)
}

/// Code der Website-Anmeldung (`ABCD-1234`): 2 × 4 Zeichen A–Z/0–9. Groß-/
/// Kleinschreibung, Leerzeichen und ein fehlender Bindestrich werden toleriert;
/// heraus geht immer die Form `XXXX-XXXX`.
pub fn web_login_code(input: &str) -> Option<String> {
    if input.len() > 32 {
        return None;
    }
    let s: String = input.chars().filter(|c| !c.is_whitespace()).map(|c| c.to_ascii_uppercase()).collect();
    let plain = match s.len() {
        9 if s.as_bytes()[4] == b'-' => s.replacen('-', "", 1),
        8 => s,
        _ => return None,
    };
    (plain.len() == 8 && plain.bytes().all(|b| b.is_ascii_uppercase() || b.is_ascii_digit()))
        .then(|| format!("{}-{}", &plain[..4], &plain[4..]))
}

/// Welches Freitextfeld geprüft wird – bestimmt die Fehlermeldung.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TextField {
    /// Hinweis zu einer Meldung.
    Comment,
    /// Grund (Ablehnung, Sperre).
    Reason,
    /// Notiz zu Einlösecodes.
    Note,
}

impl TextField {
    fn length_error(self, limit: usize) -> Error {
        Error::validation(match self {
            Self::Comment => crate::msg!("trsValidate.commentLength", "Hinweis: 1 bis {max} Zeichen.", max = limit),
            Self::Reason => crate::msg!("trsValidate.reasonLength", "Grund: 1 bis {max} Zeichen.", max = limit),
            Self::Note => crate::msg!("trsValidate.noteLength", "Notiz: 1 bis {max} Zeichen.", max = limit),
        })
    }

    fn chars_error(self) -> Error {
        Error::validation(match self {
            Self::Comment => crate::msg!("trsValidate.commentInvalidChars", "Hinweis enthält ungültige Zeichen."),
            Self::Reason => crate::msg!("trsValidate.reasonInvalidChars", "Grund enthält ungültige Zeichen."),
            Self::Note => crate::msg!("trsValidate.noteInvalidChars", "Notiz enthält ungültige Zeichen."),
        })
    }
}

/// Freitext ohne Steuerzeichen (`plainText` der API): getrimmt, 1..=max Zeichen.
pub fn plain_text(input: &str, max: usize, field: TextField) -> Result<String> {
    let t = input.trim();
    if t.is_empty() || t.chars().count() > max {
        return Err(field.length_error(max));
    }
    if t.chars().any(is_forbidden_char) {
        return Err(field.chars_error());
    }
    Ok(t.to_owned())
}

/// Steuer-, Format- und private Zeichen (entspricht grob `\p{Cc}\p{Cf}\p{Co}`).
fn is_forbidden_char(c: char) -> bool {
    c.is_control()
        || matches!(c, '\u{00AD}' | '\u{200B}'..='\u{200F}' | '\u{202A}'..='\u{202E}' | '\u{2060}'..='\u{206F}' | '\u{FEFF}')
        || ('\u{E000}'..='\u{F8FF}').contains(&c)
}

/// Name für einen hochgeladenen Umhang: 1–32 Zeichen, Buchstaben, Ziffern,
/// Leerzeichen und `. , ' ! ? & ( ) + - _`.
pub fn upload_name(input: &str) -> Result<String> {
    let t = input.trim();
    let ok = (1..=32).contains(&t.chars().count())
        && t.chars().all(|c| c.is_alphanumeric() || " _.,'!?&()+-".contains(c));
    if ok {
        Ok(t.to_owned())
    } else {
        Err(Error::validation(crate::msg!(
            "trsValidate.invalidUploadName",
            "Name: 1–32 Zeichen – Buchstaben, Ziffern, Leerzeichen und . , ' ! ? & ( ) + - _"
        )))
    }
}

/// Text aus der API für die Anzeige: ohne Steuerzeichen, gekürzt.
pub fn text(input: &str, max: usize) -> String {
    input.chars().filter(|c| !is_forbidden_char(*c)).take(max).collect()
}

/// Spielername aus der API (sollte schon passen, aber sicher ist sicher).
pub fn display_name(input: &str) -> String {
    let t = text(input, 16);
    if t.is_empty() { "?".into() } else { t }
}

pub fn cape_name(input: &str, id: &str) -> String {
    let t = text(input.trim(), 48);
    if t.is_empty() { id.to_owned() } else { t }
}

/// ISO-8601-Zeitpunkt (für Ablaufdaten von Codes).
pub fn iso_datetime(input: &str) -> Result<String> {
    chrono::DateTime::parse_from_rfc3339(input.trim())
        .map(|d| d.with_timezone(&chrono::Utc).to_rfc3339_opts(chrono::SecondsFormat::Millis, true))
        .map_err(|_| Error::validation(crate::msg!("trsValidate.invalidExpiry", "Ungültiges Ablaufdatum.")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uuids_with_and_without_dashes() {
        let plain = "75c1a6f3112240abbdb57b9d21c64232";
        assert_eq!(uuid(plain).as_deref(), Some(plain));
        assert_eq!(uuid("75C1A6F3-1122-40AB-BDB5-7B9D21C64232").as_deref(), Some(plain));
        assert_eq!(uuid("75c1a6f3-112240ab-bdb5-7b9d21c64232x"), None);
        assert_eq!(uuid("zzc1a6f3112240abbdb57b9d21c64232"), None);
        assert_eq!(uuid(""), None);
    }

    #[test]
    fn names_ids_and_tokens() {
        assert!(mc_name("Theredstonee") && mc_name("a_b") && !mc_name("") && !mc_name("x".repeat(17).as_str()));
        assert!(!mc_name("ä") && !mc_name("a b"));
        assert!(cape_id("team") && cape_id("u0123456789abcdef0123") && !cape_id("-x") && !cape_id("Team"));
        assert!(is_upload_id("u0123456789abcdef0123") && !is_upload_id("team"));
        assert!(server_id(&"a1".repeat(20)) && !server_id(&"A1".repeat(20)));
        assert!(session_token(&format!("trs_{}", "a".repeat(43))) && !session_token("trs_short"));
    }

    #[test]
    fn redeem_codes_are_normalized_like_the_server() {
        assert_eq!(redeem_code("7k3qf-m2xpa-9rtvb-c4hjn").as_deref(), Some("7K3QFM2XPA9RTVBC4HJN"));
        assert_eq!(redeem_code("OOOOO IIIII LLLLL 22222").as_deref(), Some("00000111111111122222"));
        assert_eq!(redeem_code("UUUUU-UUUUU-UUUUU-UUUUU"), None, "U gibt es in Crockford nicht");
        assert_eq!(redeem_code("abc"), None);
    }

    #[test]
    fn web_login_codes() {
        assert_eq!(web_login_code("ABCD-1234").as_deref(), Some("ABCD-1234"));
        assert_eq!(web_login_code(" abcd-1234 ").as_deref(), Some("ABCD-1234"));
        assert_eq!(web_login_code("abcd1234").as_deref(), Some("ABCD-1234"));
        assert_eq!(web_login_code("ab cd 12 34").as_deref(), Some("ABCD-1234"));
        assert_eq!(web_login_code("ABC-12345"), None, "Bindestrich an falscher Stelle");
        assert_eq!(web_login_code("ABCD--1234"), None);
        assert_eq!(web_login_code("ABCD-123"), None);
        assert_eq!(web_login_code("ÄBCD-1234"), None);
        assert_eq!(web_login_code("ABCD_1234"), None);
        assert_eq!(web_login_code(""), None);
        assert_eq!(web_login_code(&"A".repeat(40)), None);
    }

    #[test]
    fn text_rules() {
        assert!(plain_text("  ok ", 200, TextField::Note).is_ok());
        let empty = plain_text("", 200, TextField::Note).unwrap_err();
        assert_eq!(empty.message_code(), "trsValidate.noteLength");
        assert_eq!(empty.message_params()["max"], "200");
        assert_eq!(empty.public_message(), "Notiz: 1 bis 200 Zeichen.");
        let bidi = plain_text("a\u{202E}b", 200, TextField::Reason).unwrap_err();
        assert_eq!(bidi.message_code(), "trsValidate.reasonInvalidChars");
        assert!(plain_text(&"x".repeat(201), 200, TextField::Comment).is_err());
        assert_eq!(upload_name(" Mein Umhang! ").unwrap(), "Mein Umhang!");
        assert!(upload_name("<script>").is_err());
        assert_eq!(text("a\u{0007}b", 10), "ab");
        assert_eq!(display_name("\u{0000}"), "?");
        assert!(target("Bob").is_ok() && target("b0b0b0b0-b0b0-b0b0-b0b0-b0b0b0b0b0b0").is_ok());
        assert!(target("no way").is_err());
        assert_eq!(iso_datetime("2026-10-01T12:00:00+02:00").unwrap(), "2026-10-01T10:00:00.000Z");
        assert!(iso_datetime("morgen").is_err());
    }
}
