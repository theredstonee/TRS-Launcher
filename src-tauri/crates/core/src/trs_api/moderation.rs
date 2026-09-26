//! Meldungen und Moderation im Chat (Vertrag: `api/API.md` §20).
//!
//! - **Spieler:** Nachrichten, Bilder, Spieler und Gruppen melden, eigene
//!   Meldungen mit Status ansehen, eigene Stummschaltung/Verwarnungen abfragen.
//! - **Admins:** Meldungen prüfen (Kontext, Beweisbilder, Notizen, Audit-Log),
//!   Entscheidungen treffen, Wortfilter pflegen. Die Admin-Antworten sind groß
//!   und verschachtelt – sie gehen **gesäubert** als JSON ans Webview (alle
//!   Texte ohne Steuer-/Richtungszeichen, keine API-Pfade), das Webview prüft
//!   sie zusätzlich mit zod. Beweisbilder lädt es über `trschat:` aus dem Kern.

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::chat::{attachment_arg, chat_text, conversation_arg, message_arg, report_id};
use super::{Req, validate};
use crate::{Error, Launcher, Result};

fn invalid(msg: crate::error::Msg) -> Error {
    Error::validation(msg)
}

fn report_arg(id: &str) -> Result<&str> {
    if report_id(id) { Ok(id) } else { Err(invalid(crate::msg!("reports.invalidReport", "Ungültige Meldung."))) }
}

fn uuid_arg(input: &str) -> Result<String> {
    validate::uuid(input).ok_or_else(|| invalid(crate::msg!("trsOps.invalidPlayerId", "Ungültige Spieler-ID.")))
}

/// Gründe einer Chat-Meldung (§20.1).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChatReportReason {
    InsultHate,
    Spam,
    Inappropriate,
    ScamPhishing,
    Harassment,
    Other,
}

/// Was gemeldet wird.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum ReportTarget {
    #[serde(rename_all = "camelCase")]
    Message { message_id: String },
    #[serde(rename_all = "camelCase")]
    Image { attachment_id: String },
    #[serde(rename_all = "camelCase")]
    Player { uuid: String, #[serde(default)] conversation_id: Option<String> },
    #[serde(rename_all = "camelCase")]
    Group { conversation_id: String },
}

/// Eine neue Meldung vom Webview.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NewReport {
    pub target: ReportTarget,
    pub reason: ChatReportReason,
    #[serde(default)]
    pub note: Option<String>,
}

impl NewReport {
    pub(crate) fn body(&self) -> Result<Value> {
        let mut body = json!({ "reason": self.reason });
        match &self.target {
            ReportTarget::Message { message_id } => {
                body["kind"] = json!("message");
                body["messageId"] = json!(message_arg(message_id)?);
            }
            ReportTarget::Image { attachment_id } => {
                body["kind"] = json!("image");
                body["attachmentId"] = json!(attachment_arg(attachment_id)?);
            }
            ReportTarget::Player { uuid, conversation_id } => {
                body["kind"] = json!("player");
                body["uuid"] = json!(uuid_arg(uuid)?);
                if let Some(c) = conversation_id {
                    body["conversationId"] = json!(conversation_arg(c)?);
                }
            }
            ReportTarget::Group { conversation_id } => {
                body["kind"] = json!("group");
                body["conversationId"] = json!(conversation_arg(conversation_id)?);
            }
        }
        if let Some(note) = self.note.as_deref().map(str::trim).filter(|n| !n.is_empty()) {
            let note = chat_text(note, usize::MAX);
            if note.chars().count() > 500 {
                return Err(invalid(crate::msg!("trsValidate.commentLength", "Hinweis: 1 bis {max} Zeichen.", max = 500)));
            }
            body["note"] = json!(note);
        }
        Ok(body)
    }
}

/// Eigene Meldung mit Status (§20.1 `MyReportView`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MyReport {
    pub id: String,
    pub kind: String,
    pub reason: String,
    pub status: String,
    #[serde(default)]
    pub outcome: Option<String>,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub updated_at: Option<String>,
}

const KINDS: [&str; 4] = ["message", "image", "player", "group"];
const REASONS: [&str; 6] = ["insult_hate", "spam", "inappropriate", "scam_phishing", "harassment", "other"];
const STATUSES: [&str; 3] = ["open", "in_review", "resolved"];

impl MyReport {
    pub(crate) fn cleaned(self) -> Option<Self> {
        if !report_id(&self.id)
            || !KINDS.contains(&self.kind.as_str())
            || !STATUSES.contains(&self.status.as_str())
        {
            return None;
        }
        Some(Self {
            reason: if REASONS.contains(&self.reason.as_str()) { self.reason } else { "other".into() },
            outcome: self.outcome.filter(|o| matches!(o.as_str(), "actioned" | "dismissed")),
            created_at: self.created_at.map(|t| validate::text(&t, 40)),
            updated_at: self.updated_at.map(|t| validate::text(&t, 40)),
            ..self
        })
    }
}

#[derive(Debug, Deserialize)]
struct ApiMyReport {
    report: MyReport,
}

#[derive(Debug, Deserialize)]
struct ApiMyReports {
    #[serde(default)]
    reports: Vec<MyReport>,
}

/// Eigene Stummschaltung/Verwarnungen (§20.4 `GET /v1/me/moderation`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MyModeration {
    #[serde(default)]
    pub mute: Option<MuteInfo>,
    #[serde(default)]
    pub warnings: Vec<Warning>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MuteInfo {
    #[serde(default)]
    pub until: Option<String>,
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub auto: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Warning {
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub at: Option<String>,
}

impl MyModeration {
    pub(crate) fn cleaned(self) -> Self {
        Self {
            mute: self.mute.map(|m| MuteInfo {
                until: m.until.map(|t| validate::text(&t, 40)),
                reason: m.reason.map(|r| chat_text(&r, 200)),
                auto: m.auto.filter(|a| matches!(a.as_str(), "reports" | "spam")),
            }),
            warnings: self
                .warnings
                .into_iter()
                .take(50)
                .map(|w| Warning { reason: w.reason.map(|r| chat_text(&r, 200)), at: w.at.map(|t| validate::text(&t, 40)) })
                .collect(),
        }
    }
}

// --- Admin ----------------------------------------------------------------------------

/// Filter der Meldungsliste.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReportQuery {
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub target: Option<String>,
    #[serde(default)]
    pub cursor: Option<String>,
    #[serde(default)]
    pub limit: Option<u32>,
}

impl ReportQuery {
    fn path(&self) -> Result<String> {
        let status = self.status.as_deref().unwrap_or("active");
        if !matches!(status, "active" | "open" | "in_review" | "resolved" | "all") {
            return Err(invalid(crate::msg!("reports.invalidFilter", "Ungültiger Filter.")));
        }
        let mut path = format!("/v1/admin/reports?status={status}&limit={}", self.limit.unwrap_or(30).clamp(1, 100));
        if let Some(kind) = self.kind.as_deref().filter(|k| !k.is_empty() && *k != "all") {
            if !KINDS.contains(&kind) {
                return Err(invalid(crate::msg!("reports.invalidFilter", "Ungültiger Filter.")));
            }
            path.push_str(&format!("&kind={kind}"));
        }
        if let Some(target) = self.target.as_deref().filter(|t| !t.is_empty()) {
            path.push_str(&format!("&target={}", uuid_arg(target)?));
        }
        if let Some(cursor) = self.cursor.as_deref() {
            if !super::chat::cursor(cursor) {
                return Err(invalid(crate::msg!("chat.invalidCursor", "Ungültige Seite.")));
            }
            path.push_str(&format!("&cursor={}", super::encode_query(cursor)));
        }
        Ok(path)
    }
}

/// Entscheidung zu einer Meldung (§20.5 `POST …/actions`).
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReportAction {
    pub action: String,
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub minutes: Option<u32>,
    #[serde(default)]
    pub keep_open: bool,
    #[serde(default)]
    pub include_related: bool,
}

impl ReportAction {
    fn body(&self) -> Result<Value> {
        let action = self.action.as_str();
        if !matches!(action, "delete_message" | "warn" | "mute" | "ban" | "dismiss" | "resolve") {
            return Err(invalid(crate::msg!("reports.invalidAction", "Unbekannte Entscheidung.")));
        }
        let mut body = json!({ "action": action });
        if let Some(reason) = self.reason.as_deref().map(str::trim).filter(|r| !r.is_empty())
            && matches!(action, "warn" | "mute" | "ban")
        {
            body["reason"] = json!(validate::plain_text(reason, 200, validate::TextField::Reason)?);
        }
        if action == "mute"
            && let Some(minutes) = self.minutes
        {
            body["minutes"] = json!(mute_minutes(minutes)?);
        }
        if self.keep_open && !matches!(action, "dismiss" | "resolve") {
            body["keepOpen"] = json!(true);
        }
        if self.include_related {
            body["includeRelated"] = json!(true);
        }
        Ok(body)
    }
}

fn mute_minutes(minutes: u32) -> Result<u32> {
    if (5..=525_600).contains(&minutes) {
        Ok(minutes)
    } else {
        Err(invalid(crate::msg!("reports.muteRange", "Stummschaltung: 5 Minuten bis 1 Jahr.")))
    }
}

/// Neuer Eintrag im Wortfilter.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NewFilterWord {
    pub word: String,
    #[serde(default)]
    pub mode: Option<String>,
    #[serde(default)]
    pub action: Option<String>,
}

impl NewFilterWord {
    fn body(&self) -> Result<Value> {
        let word = self.word.trim();
        if !(2..=48).contains(&word.chars().count()) || word.chars().any(|c| c.is_control() || c.is_whitespace()) {
            return Err(invalid(crate::msg!("reports.invalidWord", "Wort: 2 bis 48 Buchstaben oder Ziffern.")));
        }
        let mode = self.mode.as_deref().unwrap_or("word");
        let action = self.action.as_deref().unwrap_or("mask");
        if !matches!(mode, "word" | "contains") || !matches!(action, "mask" | "block") {
            return Err(invalid(crate::msg!("reports.invalidFilter", "Ungültiger Filter.")));
        }
        Ok(json!({ "word": word, "mode": mode, "action": action }))
    }
}

/// Filter für das Audit-Log.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AuditQuery {
    #[serde(default, rename = "ref")]
    pub reference: Option<String>,
    #[serde(default)]
    pub target: Option<String>,
    #[serde(default)]
    pub before: Option<u64>,
    #[serde(default)]
    pub limit: Option<u32>,
}

impl AuditQuery {
    fn path(&self) -> Result<String> {
        let mut path = format!("/v1/admin/audit?limit={}", self.limit.unwrap_or(100).clamp(1, 200));
        if let Some(r) = self.reference.as_deref().filter(|r| !r.is_empty()) {
            path.push_str(&format!("&ref={}", report_arg(r)?));
        }
        if let Some(t) = self.target.as_deref().filter(|t| !t.is_empty()) {
            path.push_str(&format!("&target={}", uuid_arg(t)?));
        }
        if let Some(b) = self.before {
            path.push_str(&format!("&before={b}"));
        }
        Ok(path)
    }
}

/// Höchste Verschachtelung/Länge beim Säubern von Admin-Antworten.
const MAX_DEPTH: usize = 12;
const MAX_ARRAY: usize = 500;
const MAX_STRING: usize = 4000;

/// Säubert eine Admin-Antwort für das Webview: alle Texte ohne Steuer- und
/// Richtungszeichen (Zeilenumbrüche bleiben), gekürzt; API-Pfade (`path`,
/// brauchen den Token) fallen weg; zu tiefe/große Strukturen werden gekappt.
pub fn sanitize(value: Value) -> Value {
    fn walk(value: Value, depth: usize) -> Value {
        if depth > MAX_DEPTH {
            return Value::Null;
        }
        match value {
            Value::String(s) => Value::String(chat_text(&s, MAX_STRING)),
            Value::Array(items) => Value::Array(items.into_iter().take(MAX_ARRAY).map(|v| walk(v, depth + 1)).collect()),
            Value::Object(map) => Value::Object(
                map.into_iter()
                    .filter(|(k, _)| k != "path" && k.len() <= 64)
                    .map(|(k, v)| (k, walk(v, depth + 1)))
                    .collect(),
            ),
            other => other,
        }
    }
    walk(value, 0)
}

impl Launcher {
    // --- Spieler ------------------------------------------------------------------------

    pub async fn chat_report(&self, report: &NewReport) -> Result<MyReport> {
        let body = report.body()?;
        let result: ApiMyReport = self.trs_get(Req::post("/v1/reports", body)).await?;
        result.report.cleaned().ok_or_else(super::bad_response)
    }

    pub async fn chat_my_reports(&self) -> Result<Vec<MyReport>> {
        let result: ApiMyReports = self.trs_get(Req::get("/v1/reports")).await?;
        Ok(result.reports.into_iter().filter_map(MyReport::cleaned).take(100).collect())
    }

    pub async fn chat_my_moderation(&self) -> Result<MyModeration> {
        Ok(self.trs_get::<MyModeration>(Req::get("/v1/me/moderation")).await?.cleaned())
    }

    // --- Admin --------------------------------------------------------------------------

    async fn admin_json(&self, req: Req) -> Result<Value> {
        Ok(sanitize(self.trs_get::<Value>(req).await?))
    }

    pub async fn admin_reports(&self, query: &ReportQuery) -> Result<Value> {
        self.admin_json(Req::get(query.path()?)).await
    }

    pub async fn admin_report(&self, id: &str) -> Result<Value> {
        let id = report_arg(id)?;
        self.admin_json(Req::get(format!("/v1/admin/reports/{id}"))).await
    }

    pub async fn admin_report_status(&self, id: &str, status: &str) -> Result<Value> {
        let id = report_arg(id)?;
        if !matches!(status, "open" | "in_review") {
            return Err(invalid(crate::msg!("reports.invalidFilter", "Ungültiger Filter.")));
        }
        self.admin_json(Req::post(format!("/v1/admin/reports/{id}/status"), json!({ "status": status }))).await
    }

    pub async fn admin_report_action(&self, id: &str, action: &ReportAction) -> Result<Value> {
        let id = report_arg(id)?;
        self.admin_json(Req::post(format!("/v1/admin/reports/{id}/actions"), action.body()?)).await
    }

    pub async fn admin_report_note(&self, id: &str, text: &str) -> Result<Value> {
        let id = report_arg(id)?;
        let text = chat_text(text.trim(), usize::MAX);
        if text.is_empty() || text.chars().count() > 2000 {
            return Err(invalid(crate::msg!("trsValidate.noteLength", "Notiz: 1 bis {max} Zeichen.", max = 2000)));
        }
        self.admin_json(Req::post(format!("/v1/admin/reports/{id}/notes"), json!({ "text": text }))).await
    }

    pub async fn admin_moderation_user(&self, uuid: &str) -> Result<Value> {
        let uuid = uuid_arg(uuid)?;
        self.admin_json(Req::get(format!("/v1/admin/moderation/users/{uuid}"))).await
    }

    pub async fn admin_mute(&self, uuid: &str, minutes: Option<u32>, reason: Option<&str>) -> Result<Value> {
        let uuid = uuid_arg(uuid)?;
        let mut body = json!({});
        if let Some(m) = minutes {
            body["minutes"] = json!(mute_minutes(m)?);
        }
        if let Some(r) = reason.map(str::trim).filter(|r| !r.is_empty()) {
            body["reason"] = json!(validate::plain_text(r, 200, validate::TextField::Reason)?);
        }
        self.admin_json(Req::post(format!("/v1/admin/moderation/users/{uuid}/mute"), body)).await
    }

    pub async fn admin_unmute(&self, uuid: &str) -> Result<Value> {
        let uuid = uuid_arg(uuid)?;
        self.admin_json(Req::delete(format!("/v1/admin/moderation/users/{uuid}/mute"))).await
    }

    pub async fn admin_warn(&self, uuid: &str, reason: &str) -> Result<Value> {
        let uuid = uuid_arg(uuid)?;
        let reason = validate::plain_text(reason, 200, validate::TextField::Reason)?;
        self.admin_json(Req::post(format!("/v1/admin/moderation/users/{uuid}/warn"), json!({ "reason": reason }))).await
    }

    pub async fn admin_word_filter(&self) -> Result<Value> {
        self.admin_json(Req::get("/v1/admin/chat/word-filter")).await
    }

    pub async fn admin_add_word(&self, word: &NewFilterWord) -> Result<Value> {
        self.admin_json(Req::post("/v1/admin/chat/word-filter", word.body()?)).await
    }

    pub async fn admin_delete_word(&self, id: u64) -> Result<()> {
        if id == 0 || id > (1u64 << 53) {
            return Err(invalid(crate::msg!("reports.invalidWord", "Wort: 2 bis 48 Buchstaben oder Ziffern.")));
        }
        self.trs_do(Req::delete(format!("/v1/admin/chat/word-filter/{id}"))).await
    }

    pub async fn admin_audit(&self, query: &AuditQuery) -> Result<Value> {
        self.admin_json(Req::get(query.path()?)).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn report_bodies() {
        let r = NewReport {
            target: ReportTarget::Message { message_id: "m0a1b2c3d4e5f60718293".into() },
            reason: ChatReportReason::InsultHate,
            note: Some("  böse\u{202E} ".into()),
        };
        assert_eq!(
            r.body().unwrap(),
            json!({ "kind": "message", "messageId": "m0a1b2c3d4e5f60718293", "reason": "insult_hate", "note": "böse" })
        );
        let p: NewReport = serde_json::from_value(json!({
            "target": { "kind": "player", "uuid": "B0B0B0B0-B0B0-B0B0-B0B0-B0B0B0B0B0B0" }, "reason": "spam" }))
        .unwrap();
        assert_eq!(p.body().unwrap(), json!({ "kind": "player", "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "reason": "spam" }));
        let bad = NewReport { target: ReportTarget::Group { conversation_id: "x".into() }, reason: ChatReportReason::Other, note: None };
        assert!(bad.body().is_err());
        assert!(serde_json::from_value::<NewReport>(json!({ "target": { "kind": "message", "messageId": "m", "evil": 1 }, "reason": "spam" })).is_err());
        let long = NewReport {
            target: ReportTarget::Image { attachment_id: "a0123456789abcdef01234567".into() },
            reason: ChatReportReason::Other,
            note: Some("x".repeat(501)),
        };
        assert!(long.body().is_err());
    }

    #[test]
    fn admin_inputs() {
        assert_eq!(ReportQuery::default().path().unwrap(), "/v1/admin/reports?status=active&limit=30");
        let q = ReportQuery { status: Some("open".into()), kind: Some("image".into()), cursor: Some("abc".into()), ..Default::default() };
        assert_eq!(q.path().unwrap(), "/v1/admin/reports?status=open&limit=30&kind=image&cursor=abc");
        assert!(ReportQuery { status: Some("x'".into()), ..Default::default() }.path().is_err());
        let a = ReportAction { action: "mute".into(), reason: Some("Spam".into()), minutes: Some(60), keep_open: true, include_related: false };
        assert_eq!(a.body().unwrap(), json!({ "action": "mute", "reason": "Spam", "minutes": 60, "keepOpen": true }));
        let d = ReportAction { action: "dismiss".into(), reason: Some("egal".into()), minutes: None, keep_open: true, include_related: true };
        assert_eq!(d.body().unwrap(), json!({ "action": "dismiss", "includeRelated": true }));
        assert!(ReportAction { action: "nuke".into(), reason: None, minutes: None, keep_open: false, include_related: false }.body().is_err());
        assert!(ReportAction { action: "mute".into(), reason: None, minutes: Some(1), keep_open: false, include_related: false }.body().is_err());
        assert!(NewFilterWord { word: "a".into(), mode: None, action: None }.body().is_err());
        assert_eq!(
            NewFilterWord { word: " idiot ".into(), mode: Some("contains".into()), action: Some("block".into()) }.body().unwrap(),
            json!({ "word": "idiot", "mode": "contains", "action": "block" })
        );
        assert_eq!(AuditQuery { reference: Some("r0123456789abcdef".into()), ..Default::default() }.path().unwrap(), "/v1/admin/audit?limit=100&ref=r0123456789abcdef");
    }

    #[test]
    fn admin_answers_are_sanitized() {
        let v = sanitize(json!({ "report": { "note": "a\u{202E}b\nc", "evidence": { "images": [{ "id": "a1", "path": "/v1/admin/x" }] } } }));
        assert_eq!(v["report"]["note"], "ab\nc");
        assert!(v["report"]["evidence"]["images"][0].get("path").is_none());
        let mut deep = json!("x");
        for _ in 0..20 {
            deep = json!([deep]);
        }
        let flat = sanitize(deep).to_string();
        assert!(flat.contains("null"));
    }

    #[test]
    fn my_reports_are_cleaned() {
        let ok: MyReport = serde_json::from_value(json!({ "id": "r0123456789abcdef", "kind": "message", "reason": "weird", "status": "resolved", "outcome": "actioned", "createdAt": "…", "updatedAt": "…" })).unwrap();
        let ok = ok.cleaned().unwrap();
        assert_eq!(ok.reason, "other");
        let bad: MyReport = serde_json::from_value(json!({ "id": "r1", "kind": "message", "reason": "spam", "status": "open" })).unwrap();
        assert!(bad.cleaned().is_none());
    }
}
