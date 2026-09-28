//! Anmeldung auf der Website per TRS Launcher (Vertrag `api/API.md` §29).
//!
//! Die Website zeigt einen kurzen Code und öffnet `trs-launcher://web-login/<token>`. Der Launcher schlägt die Anfrage
//! nach (per Link-Token oder – anderer PC – per eingetipptem Code), zeigt Website, Code, Browser und Zeit und bestätigt
//! sie **nur nach einem Klick** mit dem TRS-Token des gewählten Kontos. Nie automatisch.
//!
//! Alles vom Server wird gesäubert: IDs und Codes nach festem Muster, Texte gekürzt und ohne Steuerzeichen.

use serde::{Deserialize, Serialize};
use serde_json::json;

use super::{Req, validate};
use crate::{Error, Launcher, Result};

/// Zeichen der Bestätigungscodes (wie der Server: ohne 0/O, 1/I/L und U).
const CODE_ALPHABET: &str = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

/// Link-Token aus `trs-launcher://web-login/<token>` – genau 43 Zeichen base64url, sonst `None`. Streng wie
/// [`crate::pack_share::pack_code_from_link`]: kein Pfad, keine Query, kein Fragment.
pub fn web_login_token_from_link(url: &str) -> Option<String> {
    let url = url.trim();
    if url.len() > 200 {
        return None;
    }
    let (scheme, rest) = url.split_once("://")?;
    if !scheme.eq_ignore_ascii_case(crate::pack_share::LINK_SCHEME) {
        return None;
    }
    let rest = rest.strip_suffix('/').unwrap_or(rest);
    let (kind, token) = rest.split_once('/')?;
    if !kind.eq_ignore_ascii_case("web-login") {
        return None;
    }
    is_link_token(token).then(|| token.to_owned())
}

fn is_link_token(token: &str) -> bool {
    token.len() == 43 && token.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// Eingabe → `XXX-XXX` (Groß/klein egal, mit oder ohne Strich/Leerzeichen), sonst `None`.
pub fn normalize_login_code(input: &str) -> Option<String> {
    if input.len() > 20 {
        return None;
    }
    let code: String = input.trim().to_ascii_uppercase().chars().filter(|c| !matches!(c, ' ' | '-')).collect();
    (code.len() == 6 && code.chars().all(|c| CODE_ALPHABET.contains(c))).then(|| format!("{}-{}", &code[..3], &code[3..]))
}

fn request_id(input: &str) -> bool {
    input.len() == 22 && input.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// Anmelde-Anfrage, wie der Bestätigungsdialog sie zeigt (gesäubert).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WebLoginRequest {
    /// Bezug für Bestätigen/Ablehnen (nicht der Link-Token).
    pub id: String,
    /// `XXX-XXX`
    pub code: String,
    /// Host der Website, z. B. `trs-launcher.theredstonee.de`.
    pub site: String,
    /// Grobe Browser-Angabe („Firefox · Windows“) oder `None`.
    pub browser: Option<String>,
    pub created_at: Option<String>,
    pub expires_at: Option<String>,
}

/// Ein Konto, mit dem bestätigt werden kann.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WebLoginAccount {
    pub id: String,
    pub name: String,
    pub skin_url: Option<String>,
    /// Aktives Minecraft-Konto (vorausgewählt).
    pub active: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiRequest {
    id: String,
    code: String,
    #[serde(default)]
    site: String,
    #[serde(default)]
    browser: Option<String>,
    #[serde(default)]
    created_at: Option<String>,
    #[serde(default)]
    expires_at: Option<String>,
}

#[derive(Debug, Deserialize)]
struct Envelope {
    request: ApiRequest,
}

/// Host aus der Antwort – nur ein schlichter Hostname (mit Port), sonst der Host der eigenen API.
fn clean_site(site: &str, own_base: &str) -> String {
    let ok = !site.is_empty()
        && site.len() <= 100
        && site.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | ':'));
    if ok {
        return site.to_ascii_lowercase();
    }
    own_base.split_once("://").map_or(own_base, |(_, host)| host).trim_end_matches('/').to_owned()
}

impl ApiRequest {
    fn cleaned(self, own_base: &str) -> Option<WebLoginRequest> {
        if !request_id(&self.id) {
            return None;
        }
        let time = |t: Option<String>| t.map(|t| validate::text(&t, 40)).filter(|t| !t.is_empty());
        Some(WebLoginRequest {
            id: self.id,
            code: normalize_login_code(&self.code)?,
            site: clean_site(&self.site, own_base),
            browser: self.browser.map(|b| validate::text(&b, 40)).filter(|b| !b.is_empty()),
            created_at: time(self.created_at),
            expires_at: time(self.expires_at),
        })
    }
}

fn invalid_code() -> Error {
    Error::validation(crate::msg!("trsWebLogin.invalidCode", "Das ist kein gültiger Code (Form: XXX-XXX)."))
}

/// Eigene Texte für die Fehler der Anmelde-Anfragen.
fn web_login_error(e: Error) -> Error {
    let Error::TrsApi { kind, code, msg } = e else { return e };
    let msg = match code.as_str() {
        "login_request_expired" | "not_found" => crate::msg!(
            "trsWebLogin.expired",
            "Diese Anmelde-Anfrage gibt es nicht (mehr) – starte die Anmeldung auf der Website neu."
        ),
        "login_request_used" => crate::msg!("trsWebLogin.used", "Diese Anmelde-Anfrage wurde schon beantwortet."),
        "invalid_code" => crate::msg!("trsWebLogin.invalidCode", "Das ist kein gültiger Code (Form: XXX-XXX)."),
        _ => msg,
    };
    Error::TrsApi { kind, code, msg }
}

impl Launcher {
    /// Konten, mit denen bestätigt werden kann: das aktive (vorausgewählt) und alle mit TRS-Anmeldung.
    pub async fn trs_web_login_accounts(&self) -> Result<Vec<WebLoginAccount>> {
        self.trs.ensure_enabled().await?;
        let mut out = Vec::new();
        for a in self.accounts().list().await? {
            if a.active || self.trs.has_token(&a.id).await {
                out.push(WebLoginAccount { id: a.id, name: a.name, skin_url: a.skin_url, active: a.active });
            }
        }
        out.sort_by_key(|a| !a.active);
        Ok(out)
    }

    /// Konto für die Anfrage: das gewählte (muss in [`Self::trs_web_login_accounts`] stehen) oder das aktive.
    async fn web_login_account(&self, account: Option<&str>) -> Result<String> {
        let allowed = self.trs_web_login_accounts().await?;
        let chosen = match account {
            Some(id) => allowed.iter().find(|a| a.id == id),
            None => allowed.iter().find(|a| a.active),
        };
        chosen.map(|a| a.id.clone()).ok_or_else(super::no_account)
    }

    /// Anfrage nachschlagen – per Link-Token (`trs-launcher://web-login/<token>`) oder per eingetipptem Code.
    pub async fn trs_web_login_lookup(&self, account: Option<&str>, token: Option<&str>, code: Option<&str>) -> Result<WebLoginRequest> {
        let body = match (token, code) {
            (Some(t), _) if is_link_token(t) => json!({ "token": t }),
            (_, Some(c)) => json!({ "code": normalize_login_code(c).ok_or_else(invalid_code)? }),
            _ => return Err(invalid_code()),
        };
        let account = self.web_login_account(account).await?;
        let env: Envelope =
            self.trs.call(self.accounts(), &account, &Req::post("/v1/launcher-login/lookup", body)).await.map_err(web_login_error)?;
        env.request.cleaned(self.trs.base()).ok_or_else(super::bad_response)
    }

    /// Bestätigen (`approve`) oder ablehnen – nur aus dem Dialog nach einem Klick.
    pub async fn trs_web_login_decide(&self, account: Option<&str>, id: &str, code: &str, approve: bool) -> Result<()> {
        if !request_id(id) {
            return Err(web_login_error(super::api_error(404, "login_request_expired", None)));
        }
        let code = normalize_login_code(code).ok_or_else(invalid_code)?;
        let account = self.web_login_account(account).await?;
        let path = if approve { "/v1/launcher-login/approve" } else { "/v1/launcher-login/deny" };
        self.trs
            .call_raw(self.accounts(), &account, &Req::post(path, json!({ "id": id, "code": code })))
            .await
            .map(|_| ())
            .map_err(web_login_error)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOKEN: &str = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";

    #[test]
    fn link_is_parsed_strictly() {
        assert_eq!(TOKEN.len(), 43);
        assert_eq!(web_login_token_from_link(&format!("trs-launcher://web-login/{TOKEN}")).as_deref(), Some(TOKEN));
        assert_eq!(web_login_token_from_link(&format!("TRS-LAUNCHER://Web-Login/{TOKEN}/")).as_deref(), Some(TOKEN));
        assert_eq!(web_login_token_from_link(&format!("  trs-launcher://web-login/{TOKEN}  ")).as_deref(), Some(TOKEN));
        for bad in [
            format!("trs-launcher://web-login/{TOKEN}?x=1"),
            format!("trs-launcher://web-login/{TOKEN}#a"),
            format!("trs-launcher://web-login/{TOKEN}/extra"),
            format!("trs-launcher://web-login/{}", &TOKEN[..42]),
            format!("trs-launcher://web-login/{TOKEN}a"),
            format!("trs-launcher://web-login/{}.", &TOKEN[..42]),
            format!("trs-launcher://pack/{TOKEN}"),
            format!("https://web-login/{TOKEN}"),
            format!("trs-launcher:web-login/{TOKEN}"),
            format!("trs-launcher://web-login/../{}", &TOKEN[..40]),
            format!("trs-launcher://web-login/{}", "a".repeat(300)),
            "trs-launcher://web-login/".to_owned(),
            String::new(),
        ] {
            assert_eq!(web_login_token_from_link(&bad), None, "{bad}");
        }
        // Der Pack-Link bleibt ein Pack-Link.
        assert_eq!(crate::pack_share::pack_code_from_link(&format!("trs-launcher://web-login/{TOKEN}")), None);
    }

    #[test]
    fn codes_are_normalized() {
        assert_eq!(normalize_login_code("k7q-2mx").as_deref(), Some("K7Q-2MX"));
        assert_eq!(normalize_login_code(" K7Q 2MX ").as_deref(), Some("K7Q-2MX"));
        assert_eq!(normalize_login_code("K7Q2MX").as_deref(), Some("K7Q-2MX"));
        for bad in ["K0Q2MX", "K1Q2MX", "KIQ2MX", "KLQ2MX", "KOQ2MX", "KUQ2MX", "K7Q2M", "K7Q2MXX", "", "Ä7Q2MX"] {
            assert_eq!(normalize_login_code(bad), None, "{bad}");
        }
        assert_eq!(normalize_login_code(&"A".repeat(30)), None);
    }

    #[test]
    fn server_answers_are_cleaned() {
        let raw = ApiRequest {
            id: "U_2cX3IFwaxWR5RiGnDP8A".into(),
            code: "mnk-2mq".into(),
            site: "trs-launcher.theredstonee.de".into(),
            browser: Some("Firefox · Windows\u{7}".into()),
            created_at: Some("2026-09-28T10:05:02.272Z".into()),
            expires_at: None,
        };
        let r = raw.cleaned("https://trs-launcher.theredstonee.de").unwrap();
        assert_eq!(r.code, "MNK-2MQ");
        assert_eq!(r.site, "trs-launcher.theredstonee.de");
        assert_eq!(r.browser.as_deref(), Some("Firefox · Windows"));
        // Kaputte ID oder Code → verworfen; fremde Zeichen in `site` → eigener Host.
        let bad = |id: &str, code: &str| ApiRequest { id: id.into(), code: code.into(), site: String::new(), browser: None, created_at: None, expires_at: None };
        assert!(bad("short", "MNK2MQ").cleaned("https://x.test").is_none());
        assert!(bad("U_2cX3IFwaxWR5RiGnDP8A", "MNK2M0").cleaned("https://x.test").is_none());
        let evil = ApiRequest { site: "evil.example/<script>".into(), ..bad("U_2cX3IFwaxWR5RiGnDP8A", "MNK2MQ") };
        assert_eq!(evil.cleaned("https://trs-launcher.theredstonee.de").unwrap().site, "trs-launcher.theredstonee.de");
    }
}
