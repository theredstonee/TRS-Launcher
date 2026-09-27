//! Eigene Bewerbungen fürs Team (`MyApplicationView`, Vertrag: `api/API.md` §24.3).
//!
//! Der Launcher zeigt nur Status und Antwort des Teams und kann zurückziehen;
//! Stellen, Formulare und das Prüfen im Team liegen auf der Website. Das
//! Ereignis `application_updated` (§19) bringt dieselbe Ansicht.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::chat::chat_text;
use super::{Req, validate};
use crate::{Error, Launcher, Result};

pub const APPLICATION_STATUSES: [&str; 6] = ["new", "review", "interview", "accepted", "rejected", "withdrawn"];
/// Sprachen der Stellentexte.
const LANGS: [&str; 3] = ["en", "de", "es"];

/// Bewerbungs-ID: `a` + 16 Hex-Zeichen (tolerant bis 40 Zeichen `[a-z0-9_-]`).
pub fn application_id(id: &str) -> bool {
    (2..=40).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-')
}

fn time(t: Option<String>) -> Option<String> {
    t.map(|t| validate::text(&t, 40)).filter(|t| !t.is_empty())
}

/// Stellentitel je Sprache (`{ en?, de?, es? }`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct JobTitle {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub en: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub de: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub es: Option<String>,
}

impl JobTitle {
    fn cleaned(self) -> Self {
        let c = |t: Option<String>| t.map(|t| validate::text(t.trim(), 80)).filter(|t| !t.is_empty());
        Self { en: c(self.en), de: c(self.de), es: c(self.es) }
    }

    /// Titel in `lang`, sonst Englisch, sonst irgendeiner.
    pub fn get(&self, lang: &str) -> Option<&str> {
        let pick = |l: &str| match l {
            "en" => self.en.as_deref(),
            "de" => self.de.as_deref(),
            "es" => self.es.as_deref(),
            _ => None,
        };
        pick(lang).or_else(|| LANGS.iter().find_map(|l| pick(l)))
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ApplicationJob {
    pub id: String,
    #[serde(default)]
    pub title: JobTitle,
    #[serde(default)]
    pub open: bool,
}

/// Eigene Bewerbung – nie Stimmen, Notizen oder wer entschieden hat.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MyApplication {
    pub id: String,
    pub job: ApplicationJob,
    pub status: String,
    #[serde(default)]
    pub response: Option<String>,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub updated_at: Option<String>,
    #[serde(default)]
    pub decided_at: Option<String>,
    #[serde(default)]
    pub can_withdraw: bool,
}

impl MyApplication {
    pub(crate) fn cleaned(self) -> Option<Self> {
        if !application_id(&self.id)
            || !application_id(&self.job.id)
            || !APPLICATION_STATUSES.contains(&self.status.as_str())
        {
            return None;
        }
        let open = matches!(self.status.as_str(), "new" | "review" | "interview");
        Some(Self {
            job: ApplicationJob { title: self.job.title.cleaned(), ..self.job },
            response: self.response.map(|r| chat_text(&r, 2000)).filter(|r| !r.trim().is_empty()),
            created_at: time(self.created_at),
            updated_at: time(self.updated_at),
            decided_at: time(self.decided_at),
            // Zurückziehen geht nur bei offenen Bewerbungen.
            can_withdraw: self.can_withdraw && open,
            ..self
        })
    }

    pub(crate) fn from_value(value: &Value) -> Option<Self> {
        serde_json::from_value::<Self>(value.clone()).ok()?.cleaned()
    }
}

#[derive(Deserialize)]
struct ApiList {
    #[serde(default)]
    applications: Vec<Value>,
}

#[derive(Deserialize)]
struct ApiEnvelope {
    application: Value,
}

fn application_arg(id: &str) -> Result<&str> {
    if application_id(id) {
        Ok(id)
    } else {
        Err(Error::validation(crate::msg!("applications.invalidId", "Ungültige Bewerbung.")))
    }
}

impl Launcher {
    /// Eigene Bewerbungen (neueste zuerst), kaputte Einträge fallen weg.
    pub async fn trs_my_applications(&self) -> Result<Vec<MyApplication>> {
        let list: ApiList = self.trs_get(Req::get("/v1/me/applications")).await?;
        Ok(list.applications.iter().take(200).filter_map(MyApplication::from_value).collect())
    }

    /// Offene Bewerbung zurückziehen (`409 application_closed`, wenn schon entschieden).
    pub async fn trs_withdraw_application(&self, id: &str) -> Result<MyApplication> {
        let id = application_arg(id)?;
        let envelope: ApiEnvelope = self.trs_get(Req::post_empty(format!("/v1/me/applications/{id}/withdraw"))).await?;
        MyApplication::from_value(&envelope.application).ok_or_else(super::bad_response)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    pub(crate) fn sample() -> Value {
        json!({ "id": "a0123456789abcdef", "job": { "id": "moderator", "title": { "en": "Moderator", "de": "Moderator\u{202E}in" }, "open": true },
            "status": "review", "response": null, "createdAt": "2026-09-27T08:00:00.000Z", "updatedAt": "2026-09-27T09:00:00.000Z",
            "decidedAt": null, "canWithdraw": true })
    }

    #[test]
    fn applications_are_cleaned() {
        let a = MyApplication::from_value(&sample()).unwrap();
        assert_eq!(a.job.title.de.as_deref(), Some("Moderatorin"));
        assert_eq!(a.job.title.get("de"), Some("Moderatorin"));
        assert_eq!(a.job.title.get("fr"), Some("Moderator"), "Rückfall auf Englisch");
        assert!(a.can_withdraw);

        let mut v = sample();
        v["status"] = json!("rejected");
        v["response"] = json!("Danke!\r\n\u{202E}Leider nein.");
        v["canWithdraw"] = json!(true);
        let a = MyApplication::from_value(&v).unwrap();
        assert!(!a.can_withdraw, "entschieden → nicht mehr zurückziehbar");
        assert_eq!(a.response.as_deref(), Some("Danke!\nLeider nein."));

        for (field, bad) in [("status", json!("hired")), ("id", json!("../x")), ("id", json!(""))] {
            let mut v = sample();
            v[field] = bad;
            assert!(MyApplication::from_value(&v).is_none(), "{field}");
        }
        let mut v = sample();
        v["job"]["id"] = json!("Job mit Leerzeichen");
        assert!(MyApplication::from_value(&v).is_none());
    }
}
