//! Eigene Strafen und Einspruch (Moderation v2, Vertrag: `api/API.md` §22).
//!
//! - **Fehlerdetails:** `sanctioned`, `chat_muted` und `banned` tragen im
//!   `error`-Objekt die betroffene Strafe (`MySanctionView`). Der Kern säubert
//!   sie und hängt sie als Parameter an die Fehlermeldung – das Webview zeigt
//!   daraus Art, Ende und Grund statt eines rohen Fehlers.
//! - **Einspruch-Token:** Eine Anmeldung mit gesperrtem Konto liefert
//!   `403 banned` + `appealToken` (1 h). Der Token bleibt – wie alle Tokens –
//!   im Kern (nur im Speicher) und gilt ausschließlich für
//!   `GET /v1/me/sanctions` und `POST /v1/me/sanctions/{id}/appeal`.

use std::collections::HashMap;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::chat::chat_text;
use super::{Failure, Req, TrsApi, validate};
use crate::error::Msg;
use crate::{Error, Launcher, Result};

/// Alle Strafarten (§22.2).
pub const SANCTION_KINDS: [&str; 6] = ["warn", "chat_mute", "social_ban", "upload_ban", "hosting_ban", "account_ban"];
/// Grund-Vorlagen, die das Team wählen kann.
pub const REASON_CODES: [&str; 11] = [
    "spam",
    "insult_hate",
    "harassment",
    "inappropriate_content",
    "inappropriate_name",
    "scam_phishing",
    "impersonation",
    "copyright",
    "cheating",
    "ban_evasion",
    "other",
];
/// Vorlagen, die nur das System setzt.
pub const SYSTEM_REASON_CODES: [&str; 3] = ["auto_spam", "auto_reports", "legacy"];
const STATUSES: [&str; 3] = ["active", "expired", "lifted"];
const APPEAL_STATUSES: [&str; 4] = ["open", "lifted", "shortened", "upheld"];

/// Einspruch: 20 bis 1000 Zeichen (§22.8).
pub const APPEAL_MIN: usize = 20;
pub const APPEAL_MAX: usize = 1000;

fn time(t: Option<String>) -> Option<String> {
    t.map(|t| validate::text(&t, 40)).filter(|t| !t.is_empty())
}

fn id_ok(id: u64) -> bool {
    id > 0 && id <= (1u64 << 53)
}

pub(crate) fn known_reason(code: &str) -> bool {
    REASON_CODES.contains(&code) || SYSTEM_REASON_CODES.contains(&code)
}

/// Eigener Einspruch (`MyAppealView`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MyAppeal {
    pub id: u64,
    pub status: String,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub decided_at: Option<String>,
    #[serde(default)]
    pub response: Option<String>,
}

impl MyAppeal {
    pub(crate) fn cleaned(self) -> Option<Self> {
        if !id_ok(self.id) || !APPEAL_STATUSES.contains(&self.status.as_str()) {
            return None;
        }
        Some(Self {
            created_at: time(self.created_at),
            decided_at: time(self.decided_at),
            response: self.response.map(|r| chat_text(&r, APPEAL_MAX)).filter(|r| !r.is_empty()),
            ..self
        })
    }
}

/// Eigene Strafe (`MySanctionView`) – ohne interne Notiz und ohne Moderator.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MySanction {
    pub id: u64,
    pub kind: String,
    pub reason_code: String,
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub starts_at: Option<String>,
    /// `None` = dauerhaft (bei automatischen Stummschaltungen: bis zur Prüfung).
    #[serde(default)]
    pub ends_at: Option<String>,
    pub status: String,
    #[serde(default)]
    pub lifted_at: Option<String>,
    #[serde(default)]
    pub appeal: Option<MyAppeal>,
    #[serde(default)]
    pub appealable: bool,
}

impl MySanction {
    /// Säubert eine Strafe aus der API; unbekannte Arten/Zustände → `None`,
    /// unbekannte Vorlagen werden „other“.
    pub(crate) fn cleaned(self) -> Option<Self> {
        if !id_ok(self.id) || !SANCTION_KINDS.contains(&self.kind.as_str()) || !STATUSES.contains(&self.status.as_str()) {
            return None;
        }
        let appeal = self.appeal.and_then(MyAppeal::cleaned);
        Some(Self {
            reason_code: if known_reason(&self.reason_code) { self.reason_code } else { "other".into() },
            reason: self.reason.map(|r| chat_text(&r, 500)).filter(|r| !r.is_empty()),
            starts_at: time(self.starts_at),
            ends_at: time(self.ends_at),
            lifted_at: time(self.lifted_at),
            // Einspruch nur einmal und nur gegen aktive Strafen.
            appealable: self.appealable && self.status == "active" && appeal.is_none(),
            appeal,
            ..self
        })
    }

    /// Aus einem beliebigen JSON-Wert (Fehlerdetails, Ereignisse).
    pub(crate) fn from_value(value: &Value) -> Option<Self> {
        serde_json::from_value::<Self>(value.clone()).ok()?.cleaned()
    }
}

/// `GET /v1/me/sanctions`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MySanctions {
    #[serde(default)]
    pub active: Vec<MySanction>,
    #[serde(default)]
    pub past: Vec<MySanction>,
}

impl MySanctions {
    pub(crate) fn cleaned(self) -> Self {
        let clean = |list: Vec<MySanction>| list.into_iter().take(200).filter_map(MySanction::cleaned).collect();
        Self { active: clean(self.active), past: clean(self.past) }
    }
}

#[derive(Deserialize)]
struct ApiEnvelope {
    sanction: MySanction,
}

/// Fehlerdetails einer Strafe aus dem `error`-Objekt der API.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct SanctionDetail {
    pub sanction: Option<MySanction>,
    /// Einspruch-Token nach einer gesperrten Anmeldung (`POST /v1/auth/verify`).
    pub appeal_token: Option<(String, DateTime<Utc>)>,
}

impl SanctionDetail {
    /// Liest `sanction`, `appealToken` und `appealTokenExpiresAt` – nur bei den
    /// Codes, die sie tragen dürfen.
    pub(crate) fn parse(code: &str, error: Option<&Value>) -> Option<Self> {
        if !matches!(code, "sanctioned" | "chat_muted" | "banned") {
            return None;
        }
        let error = error?;
        let sanction = error.get("sanction").and_then(MySanction::from_value);
        let appeal_token = (code == "banned")
            .then(|| {
                let token = error.get("appealToken")?.as_str().filter(|t| validate::session_token(t))?;
                let expires = error
                    .get("appealTokenExpiresAt")
                    .and_then(Value::as_str)
                    .and_then(|t| DateTime::parse_from_rfc3339(t).ok())
                    .map(|d| d.with_timezone(&Utc))
                    .unwrap_or_else(|| Utc::now() + chrono::Duration::hours(1));
                Some((token.to_owned(), expires.min(Utc::now() + chrono::Duration::hours(2))))
            })
            .flatten();
        (sanction.is_some() || appeal_token.is_some()).then_some(Self { sanction, appeal_token })
    }
}

/// Hängt die Strafe als Parameter an die Fehlermeldung (das Webview baut daraus
/// „Chat-Stumm bis morgen, 18:00 (in 5 Std.) – Grund: Spam“).
pub(crate) fn with_sanction(mut msg: Msg, sanction: &MySanction) -> Msg {
    msg.params.push(("sanctionId", sanction.id.to_string()));
    msg.params.push(("sanctionKind", sanction.kind.clone()));
    msg.params.push(("reasonCode", sanction.reason_code.clone()));
    msg.params.push(("reason", sanction.reason.clone().unwrap_or_default()));
    msg.params.push(("endsAt", sanction.ends_at.clone().unwrap_or_default()));
    msg.params.push(("appealable", sanction.appealable.to_string()));
    msg.params.push(("appealStatus", sanction.appeal.as_ref().map(|a| a.status.clone()).unwrap_or_default()));
    msg
}

/// Einspruchstext: getrimmt, `\r` raus, Tabs werden Leerzeichen; Zeilenumbrüche
/// erlaubt, andere Steuer-/Richtungszeichen nicht; 20 bis 1000 Zeichen.
pub fn appeal_text(input: &str) -> Result<String> {
    let text: String = input.chars().filter(|c| *c != '\r').map(|c| if c == '\t' { ' ' } else { c }).collect();
    let text = text.trim();
    let count = text.chars().count();
    if !(APPEAL_MIN..=APPEAL_MAX).contains(&count) {
        return Err(Error::validation(crate::msg!(
            "sanctions.appealLength",
            "Einspruch: {min} bis {max} Zeichen.",
            min = APPEAL_MIN,
            max = APPEAL_MAX
        )));
    }
    if chat_text(text, usize::MAX).chars().filter(|c| *c != '\n').count() != text.chars().filter(|c| *c != '\n').count()
    {
        return Err(Error::validation(crate::msg!("sanctions.appealInvalidChars", "Der Einspruch enthält ungültige Zeichen.")));
    }
    Ok(text.to_owned())
}

pub(crate) fn sanction_id_arg(id: u64) -> Result<u64> {
    if id_ok(id) {
        Ok(id)
    } else {
        Err(Error::validation(crate::msg!("sanctions.invalidId", "Ungültige Strafe.")))
    }
}

/// Einspruch-Tokens je Account (nur im Speicher).
#[derive(Default)]
pub(crate) struct AppealTokens(std::sync::Mutex<HashMap<String, (String, DateTime<Utc>)>>);

impl AppealTokens {
    pub fn put(&self, account: &str, token: String, expires: DateTime<Utc>) {
        if let Ok(mut map) = self.0.lock() {
            map.insert(account.to_owned(), (token, expires));
        }
    }

    pub fn get(&self, account: &str) -> Option<String> {
        let mut map = self.0.lock().ok()?;
        match map.get(account) {
            Some((token, expires)) if *expires > Utc::now() => Some(token.clone()),
            Some(_) => {
                map.remove(account);
                None
            }
            None => None,
        }
    }

    pub fn forget(&self, account: &str) {
        if let Ok(mut map) = self.0.lock() {
            map.remove(account);
        }
    }
}

impl TrsApi {
    /// Anfrage mit dem Einspruch-Token (falls vorhanden). `None` = kein
    /// (gültiger) Token mehr – dann normal anmelden.
    async fn appeal_call(&self, account: &str, req: &Req) -> Option<Result<Vec<u8>>> {
        let token = self.appeal_tokens.get(account)?;
        match self.send(req, Some(&token)).await {
            Ok(bytes) => Some(Ok(bytes)),
            // Sperre vorbei (oder Token abgelaufen): normaler Weg.
            Err(Failure::Api { status: 401, .. }) => {
                self.appeal_tokens.forget(account);
                None
            }
            Err(e) => Some(Err(e.into_error())),
        }
    }

    /// Angemeldete Anfrage, die auch mit gesperrtem Konto geht (Einspruch-Token).
    pub(crate) async fn call_or_appeal(&self, sessions: &dyn super::SessionSource, account: &str, req: &Req) -> Result<Vec<u8>> {
        if let Some(result) = self.appeal_call(account, req).await {
            return result;
        }
        match self.call_raw(sessions, account, req).await {
            // Anmeldung gerade abgelehnt – dabei kam ggf. ein Einspruch-Token.
            Err(Error::TrsApi { kind: "trs_banned", .. }) if self.appeal_tokens.get(account).is_some() => {
                match self.appeal_call(account, req).await {
                    Some(result) => result,
                    None => self.call_raw(sessions, account, req).await,
                }
            }
            other => other,
        }
    }
}

impl Launcher {
    /// Eigene Strafen (aktiv + vergangen) – auch mit gesperrtem Konto.
    pub async fn trs_my_sanctions(&self) -> Result<MySanctions> {
        let account = self.trs_account().await?;
        let bytes = self.trs.call_or_appeal(self.accounts(), &account, &Req::get("/v1/me/sanctions")).await?;
        Ok(super::parse::<MySanctions>(&bytes, "me/sanctions")?.cleaned())
    }

    /// Einspruch gegen eine eigene, aktive Strafe (einmal je Strafe).
    pub async fn trs_appeal(&self, id: u64, text: &str) -> Result<MySanction> {
        let id = sanction_id_arg(id)?;
        let text = appeal_text(text)?;
        let account = self.trs_account().await?;
        let req = Req::post(format!("/v1/me/sanctions/{id}/appeal"), json!({ "text": text }));
        let bytes = self.trs.call_or_appeal(self.accounts(), &account, &req).await?;
        let envelope: ApiEnvelope = super::parse(&bytes, "appeal")?;
        envelope.sanction.cleaned().ok_or_else(super::bad_response)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Value {
        json!({ "id": 5, "kind": "upload_ban", "reasonCode": "copyright", "reason": "Umhang mit\u{202E} fremdem Logo",
            "startsAt": "2026-09-26T10:00:00.000Z", "endsAt": "2026-09-28T15:52:14.904Z", "status": "active",
            "liftedAt": null, "appeal": null, "appealable": true })
    }

    #[test]
    fn sanctions_are_cleaned() {
        let s = MySanction::from_value(&sample()).unwrap();
        assert_eq!(s.reason.as_deref(), Some("Umhang mit fremdem Logo"));
        assert!(s.appealable);
        let mut v = sample();
        v["kind"] = json!("nuke");
        assert!(MySanction::from_value(&v).is_none());
        let mut v = sample();
        v["reasonCode"] = json!("weird");
        v["appeal"] = json!({ "id": 2, "status": "open", "createdAt": "x", "decidedAt": null, "response": null });
        let s = MySanction::from_value(&v).unwrap();
        assert_eq!(s.reason_code, "other");
        assert!(!s.appealable, "Einspruch schon eingelegt");
        let mut v = sample();
        v["status"] = json!("expired");
        assert!(!MySanction::from_value(&v).unwrap().appealable);
        v["appeal"] = json!({ "id": 2, "status": "weird" });
        assert!(MySanction::from_value(&v).unwrap().appeal.is_none());
    }

    #[test]
    fn error_details_are_read() {
        let error = json!({ "code": "banned", "until": null, "sanction": sample(),
            "appealToken": format!("trs_{}", "a".repeat(43)), "appealTokenExpiresAt": "2099-01-01T00:00:00.000Z" });
        let d = SanctionDetail::parse("banned", Some(&error)).unwrap();
        assert_eq!(d.sanction.unwrap().id, 5);
        let (token, expires) = d.appeal_token.unwrap();
        assert!(token.starts_with("trs_"));
        assert!(expires < Utc::now() + chrono::Duration::hours(3), "Ablauf gedeckelt");
        // Andere Codes tragen keine Strafe, kaputte Tokens werden verworfen.
        assert!(SanctionDetail::parse("forbidden", Some(&error)).is_none());
        let bad = json!({ "code": "sanctioned", "sanction": sample(), "appealToken": "nope" });
        let d = SanctionDetail::parse("sanctioned", Some(&bad)).unwrap();
        assert!(d.appeal_token.is_none());
        assert!(SanctionDetail::parse("chat_muted", Some(&json!({ "code": "chat_muted" }))).is_none());
    }

    #[test]
    fn sanction_params_reach_the_message() {
        let s = MySanction::from_value(&sample()).unwrap();
        let msg = with_sanction(crate::msg!("trsApi.sanctioned", "Gesperrt."), &s);
        let params = msg.params_json();
        assert_eq!(params["sanctionKind"], "upload_ban");
        assert_eq!(params["reasonCode"], "copyright");
        assert_eq!(params["endsAt"], "2026-09-28T15:52:14.904Z");
        assert_eq!(params["appealable"], "true");
    }

    #[test]
    fn appeal_texts_are_checked() {
        assert!(appeal_text("zu kurz").is_err());
        assert!(appeal_text(&"x".repeat(1001)).is_err());
        let ok = appeal_text("  Das war ein Missverständnis,\r\nbitte prüfen.\t ").unwrap();
        assert_eq!(ok, "Das war ein Missverständnis,\nbitte prüfen.");
        assert!(appeal_text("Das war ein Missverständnis \u{202E} bitte").is_err());
        assert_eq!(appeal_text(&"ä".repeat(1000)).unwrap().chars().count(), 1000);
    }

    #[test]
    fn appeal_tokens_expire() {
        let tokens = AppealTokens::default();
        tokens.put("a", "t1".into(), Utc::now() + chrono::Duration::minutes(5));
        tokens.put("b", "t2".into(), Utc::now() - chrono::Duration::minutes(5));
        assert_eq!(tokens.get("a").as_deref(), Some("t1"));
        assert!(tokens.get("b").is_none());
        tokens.forget("a");
        assert!(tokens.get("a").is_none());
    }
}
