//! Team-Bereich (Moderation v2, Vertrag: `api/API.md` §22): Übersicht,
//! globale Suche, Spieler-Akte mit Notizen, Strafen (vergeben, aufheben,
//! verkürzen/verlängern), Einsprüche, Rollen, Welten und Sammelaktionen.
//!
//! Wie bei den Meldungen (`moderation.rs`) gehen die großen Antworten
//! **gesäubert** als JSON ans Webview (zod prüft dort noch einmal). Alle
//! Eingaben prüft der Kern vorher mit denselben Regeln wie die API; ob die
//! eigene Rolle etwas darf, entscheidet der Server bei jeder Anfrage.

use serde::Deserialize;
use serde_json::{Value, json};

use super::moderation::{cursor_arg, push_common};
use super::sanctions::{REASON_CODES, SANCTION_KINDS, sanction_id_arg};
use super::texture::TextureSpec;
use super::{Req, encode_query, hosting, validate};
use crate::{Error, Launcher, Result};

/// Dauer-Vorlagen einer Strafe (§22.2).
pub const DURATIONS: [&str; 8] = ["1h", "6h", "1d", "3d", "7d", "30d", "permanent", "custom"];
/// Eigene Dauer: 5 Minuten bis 10 Jahre.
pub const CUSTOM_MINUTES: std::ops::RangeInclusive<u32> = 5..=5_256_000;
/// Sammelaktionen: höchstens 50 Einträge je Anfrage.
pub const BULK_LIMIT: usize = 50;

fn invalid(msg: crate::error::Msg) -> Error {
    Error::validation(msg)
}

fn bad_filter() -> Error {
    invalid(crate::msg!("reports.invalidFilter", "Ungültiger Filter."))
}

fn uuid_arg(input: &str) -> Result<String> {
    validate::uuid(input).ok_or_else(|| invalid(crate::msg!("trsOps.invalidPlayerId", "Ungültige Spieler-ID.")))
}

fn limit(value: Option<u32>, default: u32, max: u32) -> u32 {
    value.unwrap_or(default).clamp(1, max)
}

/// Art + Dauer passen zusammen (nur bekannte Werte).
pub(crate) fn check_kind_duration(kind: &str, duration: &str) -> Result<()> {
    if !SANCTION_KINDS.contains(&kind) {
        return Err(invalid(crate::msg!("team.invalidKind", "Unbekannte Strafart.")));
    }
    if !DURATIONS.contains(&duration) {
        return Err(invalid(crate::msg!("team.invalidDuration", "Unbekannte Dauer.")));
    }
    Ok(())
}

pub(crate) fn custom_minutes(minutes: Option<u32>) -> Result<u32> {
    minutes.filter(|m| CUSTOM_MINUTES.contains(m)).ok_or_else(|| {
        invalid(crate::msg!("team.customMinutes", "Eigene Dauer: 5 Minuten bis 10 Jahre."))
    })
}

pub(crate) fn reason_code_arg(code: &str) -> Result<&str> {
    if REASON_CODES.contains(&code) {
        Ok(code)
    } else {
        Err(invalid(crate::msg!("team.invalidReasonCode", "Bitte eine Grund-Vorlage wählen.")))
    }
}

/// Begründung (eine Zeile, 1–500 Zeichen) – Pflicht beim Aufheben/Ändern.
fn change_reason(input: &str) -> Result<String> {
    validate::plain_text(input, 500, validate::TextField::Reason)
}

/// Mehrzeiliger Text (Notizen, Antworten auf Einsprüche): Zeilenumbrüche
/// erlaubt, sonst keine Steuerzeichen; `min..=max` Zeichen.
fn long_text(input: &str, max: usize) -> Result<String> {
    let text: String = input.chars().filter(|c| *c != '\r').map(|c| if c == '\t' { ' ' } else { c }).collect();
    let text = text.trim();
    if text.is_empty() || text.chars().count() > max {
        return Err(invalid(crate::msg!("trsValidate.noteLength", "Notiz: 1 bis {max} Zeichen.", max = max)));
    }
    if text.split('\n').any(|line| validate::text(line, usize::MAX).chars().count() != line.chars().count()) {
        return Err(invalid(crate::msg!("trsValidate.noteInvalidChars", "Notiz enthält ungültige Zeichen.")));
    }
    Ok(text.to_owned())
}

fn ends_at_arg(input: &str) -> Result<String> {
    let at = super::moderation::date_arg(input)?;
    let parsed = chrono::DateTime::parse_from_rfc3339(&at).map_err(|_| bad_filter())?;
    if parsed <= chrono::Utc::now() {
        return Err(invalid(crate::msg!("trsApi.invalid_duration", "Das Ende muss in der Zukunft liegen – zum Beenden „Aufheben“ nutzen.")));
    }
    Ok(at)
}

// --- Eingaben ---------------------------------------------------------------------------

/// Filter der Strafenliste (`GET /v1/admin/sanctions`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SanctionQuery {
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub uuid: Option<String>,
    #[serde(default)]
    pub actor: Option<String>,
    #[serde(default)]
    pub from: Option<String>,
    #[serde(default)]
    pub to: Option<String>,
    #[serde(default)]
    pub sort: Option<String>,
    #[serde(default)]
    pub cursor: Option<String>,
    #[serde(default)]
    pub limit: Option<u32>,
}

impl SanctionQuery {
    fn path(&self) -> Result<String> {
        let status = self.status.as_deref().unwrap_or("active");
        if !matches!(status, "active" | "expired" | "lifted" | "all") {
            return Err(bad_filter());
        }
        let mut path = format!("/v1/admin/sanctions?status={status}&limit={}", limit(self.limit, 50, 100));
        if let Some(kind) = self.kind.as_deref().filter(|k| !k.is_empty() && *k != "all") {
            if !SANCTION_KINDS.contains(&kind) {
                return Err(bad_filter());
            }
            path.push_str(&format!("&kind={kind}"));
        }
        if let Some(uuid) = self.uuid.as_deref().filter(|u| !u.is_empty()) {
            path.push_str(&format!("&uuid={}", uuid_arg(uuid)?));
        }
        if let Some(actor) = self.actor.as_deref().filter(|a| !a.is_empty()) {
            let actor = match actor {
                "api-key" | "system" => actor.to_owned(),
                other => uuid_arg(other)?,
            };
            path.push_str(&format!("&actor={actor}"));
        }
        push_common(&mut path, self.from.as_deref(), self.to.as_deref(), self.sort.as_deref(), self.cursor.as_deref())?;
        Ok(path)
    }
}

/// Neue Strafe (`POST /v1/admin/sanctions`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NewSanction {
    pub uuid: String,
    pub kind: String,
    pub duration: String,
    #[serde(default)]
    pub minutes: Option<u32>,
    pub reason_code: String,
    /// Öffentlicher Grund (sieht der Spieler).
    #[serde(default)]
    pub reason: Option<String>,
    /// Interne Notiz (nur das Team).
    #[serde(default)]
    pub note: Option<String>,
    #[serde(default)]
    pub report_id: Option<String>,
}

impl NewSanction {
    pub(crate) fn body(&self) -> Result<Value> {
        let uuid = uuid_arg(&self.uuid)?;
        check_kind_duration(&self.kind, &self.duration)?;
        let mut body = json!({
            "uuid": uuid,
            "kind": self.kind,
            "duration": self.duration,
            "reasonCode": reason_code_arg(&self.reason_code)?,
        });
        if self.duration == "custom" {
            body["minutes"] = json!(custom_minutes(self.minutes)?);
        }
        if let Some(reason) = self.reason.as_deref().map(str::trim).filter(|r| !r.is_empty()) {
            body["reason"] = json!(validate::plain_text(reason, 500, validate::TextField::Reason)?);
        }
        if let Some(note) = self.note.as_deref().map(str::trim).filter(|n| !n.is_empty()) {
            body["note"] = json!(long_text(note, 2000)?);
        }
        if let Some(report) = self.report_id.as_deref().filter(|r| !r.is_empty()) {
            if !super::chat::report_id(report) {
                return Err(invalid(crate::msg!("reports.invalidReport", "Ungültige Meldung.")));
            }
            body["reportId"] = json!(report);
        }
        Ok(body)
    }
}

/// Filter der Einspruchsliste.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AppealQuery {
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub cursor: Option<String>,
    #[serde(default)]
    pub limit: Option<u32>,
}

impl AppealQuery {
    fn path(&self) -> Result<String> {
        let status = self.status.as_deref().unwrap_or("open");
        if !matches!(status, "open" | "decided" | "all") {
            return Err(bad_filter());
        }
        let mut path = format!("/v1/admin/appeals?status={status}&limit={}", limit(self.limit, 50, 100));
        if let Some(c) = self.cursor.as_deref().filter(|c| !c.is_empty()) {
            path.push_str(&format!("&cursor={}", cursor_arg(c)?));
        }
        Ok(path)
    }
}

/// Entscheidung über einen Einspruch.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AppealDecision {
    pub decision: String,
    /// Antwort an den Spieler (1–1000 Zeichen).
    pub response: String,
    /// Nur bei `shorten`: neues (früheres) Ende.
    #[serde(default)]
    pub ends_at: Option<String>,
}

impl AppealDecision {
    pub(crate) fn body(&self) -> Result<Value> {
        if !matches!(self.decision.as_str(), "lift" | "shorten" | "uphold") {
            return Err(invalid(crate::msg!("team.invalidDecision", "Unbekannte Entscheidung.")));
        }
        let response = long_text(&self.response, 1000)?;
        let mut body = json!({ "decision": self.decision, "response": response });
        if self.decision == "shorten" {
            let at = self.ends_at.as_deref().filter(|a| !a.trim().is_empty()).ok_or_else(|| {
                invalid(crate::msg!("team.shortenNeedsEnd", "Zum Verkürzen bitte ein neues Ende wählen."))
            })?;
            body["endsAt"] = json!(ends_at_arg(at)?);
        }
        Ok(body)
    }
}

/// Filter der Spielerliste.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlayerQuery {
    /// Anfang eines (auch früheren) Namens.
    #[serde(default)]
    pub q: Option<String>,
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub sort: Option<String>,
    #[serde(default)]
    pub cursor: Option<String>,
    #[serde(default)]
    pub limit: Option<u32>,
}

impl PlayerQuery {
    fn path(&self) -> Result<String> {
        let status = self.status.as_deref().unwrap_or("all");
        if !matches!(status, "all" | "sanctioned" | "banned" | "staff" | "reported") {
            return Err(bad_filter());
        }
        let sort = self.sort.as_deref().unwrap_or("last_login");
        if !matches!(sort, "last_login" | "created") {
            return Err(bad_filter());
        }
        let mut path = format!("/v1/admin/players?status={status}&sort={sort}&limit={}", limit(self.limit, 50, 100));
        if let Some(q) = self.q.as_deref().map(str::trim).filter(|q| !q.is_empty()) {
            if !validate::mc_name(q) {
                return Err(invalid(crate::msg!("team.invalidNameQuery", "Namen bestehen aus 1–16 Buchstaben, Ziffern oder _.")));
            }
            path.push_str(&format!("&q={q}"));
        }
        if let Some(c) = self.cursor.as_deref().filter(|c| !c.is_empty()) {
            path.push_str(&format!("&cursor={}", cursor_arg(c)?));
        }
        Ok(path)
    }
}

/// Filter der Upload-Listen (Umhänge/Kosmetik).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct UploadQuery {
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub owner: Option<String>,
    #[serde(default)]
    pub q: Option<String>,
    #[serde(default)]
    pub sort: Option<String>,
    #[serde(default)]
    pub cursor: Option<String>,
    #[serde(default)]
    pub limit: Option<u32>,
}

impl UploadQuery {
    pub(crate) fn path(&self, segment: &str) -> Result<String> {
        let status = self.status.as_deref().unwrap_or("pending");
        if !matches!(status, "pending" | "approved" | "rejected" | "reported") {
            return Err(bad_filter());
        }
        let mut path = format!("/v1/admin/{segment}?status={status}");
        if let Some(l) = self.limit {
            path.push_str(&format!("&limit={}", l.clamp(1, 500)));
        }
        if let Some(owner) = self.owner.as_deref().filter(|o| !o.is_empty()) {
            path.push_str(&format!("&owner={}", uuid_arg(owner)?));
        }
        if let Some(q) = self.q.as_deref().map(str::trim).filter(|q| !q.is_empty()) {
            let q = super::chat::one_line(q, 32);
            if q.is_empty() {
                return Err(bad_filter());
            }
            path.push_str(&format!("&q={}", encode_query(&q)));
        }
        push_common(&mut path, None, None, self.sort.as_deref(), self.cursor.as_deref())?;
        Ok(path)
    }
}

/// Sammelaktion (höchstens [`BULK_LIMIT`] IDs, eine Transaktion).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BulkAction {
    pub ids: Vec<String>,
    pub action: String,
    #[serde(default)]
    pub reason: Option<String>,
}

/// Was eine Sammelaktion betrifft.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BulkTarget {
    Reports,
    Capes,
    Cosmetics,
}

impl BulkAction {
    pub(crate) fn body(&self, target: BulkTarget) -> Result<Value> {
        if self.ids.is_empty() || self.ids.len() > BULK_LIMIT {
            return Err(invalid(crate::msg!("trsApi.bulk_too_large", "Höchstens 50 Einträge auf einmal.")));
        }
        let mut ids = self.ids.clone();
        ids.sort();
        ids.dedup();
        let (ids_ok, action_ok) = match target {
            BulkTarget::Reports => (
                ids.iter().all(|id| super::chat::report_id(id)),
                matches!(self.action.as_str(), "dismiss" | "resolve"),
            ),
            BulkTarget::Capes | BulkTarget::Cosmetics => (
                ids.iter().all(|id| validate::cape_id(id)),
                matches!(self.action.as_str(), "approve" | "reject"),
            ),
        };
        if !ids_ok || !action_ok {
            return Err(bad_filter());
        }
        let mut body = json!({ "ids": ids, "action": self.action });
        if target != BulkTarget::Reports
            && self.action == "reject"
            && let Some(reason) = self.reason.as_deref().map(str::trim).filter(|r| !r.is_empty())
        {
            body["reason"] = json!(validate::plain_text(reason, 200, validate::TextField::Reason)?);
        }
        Ok(body)
    }
}

/// Suchbegriff der globalen Suche: 1–64 Zeichen, eine Zeile.
pub fn search_query(input: &str) -> Result<String> {
    let q = super::chat::one_line(input, 64);
    if q.is_empty() {
        return Err(invalid(crate::msg!("team.emptySearch", "Bitte einen Suchbegriff eingeben.")));
    }
    Ok(q)
}

// --- Anfragen ---------------------------------------------------------------------------

impl Launcher {
    /// Übersicht: Zahlen, 30-Tage-Verlauf, Server, letzte Aktionen.
    pub async fn admin_dashboard(&self) -> Result<Value> {
        self.admin_json(Req::get("/v1/admin/dashboard")).await
    }

    /// Globale Suche über Spieler (auch frühere Namen), Meldungen, Strafen, Umhänge, Kosmetik.
    pub async fn admin_search(&self, q: &str) -> Result<Value> {
        let q = search_query(q)?;
        self.admin_json(Req::get(format!("/v1/admin/search?q={}", encode_query(&q)))).await
    }

    pub async fn admin_players(&self, query: &PlayerQuery) -> Result<Value> {
        self.admin_json(Req::get(query.path()?)).await
    }

    /// Spieler-Akte mit Strafverlauf, Meldungen, Uploads, Welten und Notizen.
    pub async fn admin_player(&self, uuid: &str) -> Result<Value> {
        let uuid = uuid_arg(uuid)?;
        self.admin_json(Req::get(format!("/v1/admin/players/{uuid}"))).await
    }

    pub async fn admin_player_note(&self, uuid: &str, text: &str) -> Result<Value> {
        let uuid = uuid_arg(uuid)?;
        let text = long_text(text, 2000)?;
        self.admin_json(Req::post(format!("/v1/admin/players/{uuid}/notes"), json!({ "text": text }))).await
    }

    pub async fn admin_player_note_delete(&self, uuid: &str, id: u64) -> Result<Value> {
        let uuid = uuid_arg(uuid)?;
        let id = sanction_id_arg(id)?;
        self.admin_json(Req::delete(format!("/v1/admin/players/{uuid}/notes/{id}"))).await
    }

    pub async fn admin_sanctions(&self, query: &SanctionQuery) -> Result<Value> {
        self.admin_json(Req::get(query.path()?)).await
    }

    pub async fn admin_sanction(&self, id: u64) -> Result<Value> {
        let id = sanction_id_arg(id)?;
        self.admin_json(Req::get(format!("/v1/admin/sanctions/{id}"))).await
    }

    pub async fn admin_sanction_create(&self, new: &NewSanction) -> Result<Value> {
        self.admin_json(Req::post("/v1/admin/sanctions", new.body()?)).await
    }

    pub async fn admin_sanction_lift(&self, id: u64, reason: &str) -> Result<Value> {
        let id = sanction_id_arg(id)?;
        let reason = change_reason(reason)?;
        self.admin_json(Req::post(format!("/v1/admin/sanctions/{id}/lift"), json!({ "reason": reason }))).await
    }

    /// Neues Ende (`None` = dauerhaft). Früher = verkürzen, später = verlängern.
    pub async fn admin_sanction_duration(&self, id: u64, ends_at: Option<&str>, reason: &str) -> Result<Value> {
        let id = sanction_id_arg(id)?;
        let reason = change_reason(reason)?;
        let ends_at = match ends_at.map(str::trim).filter(|a| !a.is_empty()) {
            Some(at) => Value::String(ends_at_arg(at)?),
            None => Value::Null,
        };
        self.admin_json(Req::post(format!("/v1/admin/sanctions/{id}/duration"), json!({ "endsAt": ends_at, "reason": reason })))
            .await
    }

    pub async fn admin_appeals(&self, query: &AppealQuery) -> Result<Value> {
        self.admin_json(Req::get(query.path()?)).await
    }

    pub async fn admin_appeal_decide(&self, id: u64, decision: &AppealDecision) -> Result<Value> {
        let id = sanction_id_arg(id)?;
        self.admin_json(Req::post(format!("/v1/admin/appeals/{id}/decide"), decision.body()?)).await
    }

    pub async fn admin_roles(&self) -> Result<Value> {
        self.admin_json(Req::get("/v1/admin/roles")).await
    }

    pub async fn admin_role_set(&self, uuid: &str, role: &str, note: Option<&str>) -> Result<Value> {
        let uuid = uuid_arg(uuid)?;
        if !matches!(role, "admin" | "moderator") {
            return Err(invalid(crate::msg!("team.invalidRole", "Unbekannte Rolle.")));
        }
        let mut body = json!({ "role": role });
        if let Some(note) = note.map(str::trim).filter(|n| !n.is_empty()) {
            body["note"] = json!(validate::plain_text(note, 200, validate::TextField::Note)?);
        }
        self.admin_json(Req::put(format!("/v1/admin/roles/{uuid}"), body)).await
    }

    pub async fn admin_role_remove(&self, uuid: &str) -> Result<Value> {
        let uuid = uuid_arg(uuid)?;
        self.admin_json(Req::delete(format!("/v1/admin/roles/{uuid}"))).await
    }

    /// Offene Welten (Welt-Hosting) für das Team.
    pub async fn admin_rooms(&self) -> Result<Value> {
        self.admin_json(Req::get("/v1/admin/hosting/rooms")).await
    }

    pub async fn admin_room_close(&self, id: &str, reason: Option<&str>) -> Result<()> {
        if !hosting::room_id(id) {
            return Err(invalid(crate::msg!("team.invalidRoom", "Ungültige Welt.")));
        }
        let req = match reason.map(str::trim).filter(|r| !r.is_empty()) {
            Some(r) => Req::with(
                reqwest::Method::DELETE,
                format!("/v1/admin/hosting/rooms/{id}"),
                super::Body::Json(json!({ "reason": validate::plain_text(r, 200, validate::TextField::Reason)? })),
                super::MAX_JSON_BYTES,
            ),
            None => Req::delete(format!("/v1/admin/hosting/rooms/{id}")),
        };
        self.trs_do(req).await
    }

    /// Sammelaktion auf Meldungen, Umhängen oder Kosmetik.
    pub async fn admin_bulk(&self, target: BulkTarget, bulk: &BulkAction) -> Result<Value> {
        let segment = match target {
            BulkTarget::Reports => "reports",
            BulkTarget::Capes => "capes",
            BulkTarget::Cosmetics => "cosmetics",
        };
        self.admin_json(Req::post(format!("/v1/admin/{segment}/bulk"), bulk.body(target)?)).await
    }

    /// Kosmetik zum Prüfen, jeweils mit Vorschau der Textur (Data-URL als `preview`).
    pub async fn admin_cosmetics(&self, query: &UploadQuery) -> Result<Value> {
        let path = query.path("cosmetics")?;
        let account = self.trs_account().await?;
        let mut value = self.admin_json(Req::get(path)).await?;
        let token = self.trs.store.token(&account).await;
        if let Some(list) = value.get_mut("cosmetics").and_then(Value::as_array_mut) {
            let jobs = list.iter().map(|item| {
                let id = item["id"].as_str().unwrap_or_default().to_owned();
                let texture = &item["texture"];
                let url = texture["url"].as_str().unwrap_or_default().to_owned();
                let width = texture["width"].as_u64().unwrap_or(0) as u32;
                let frames = texture["frames"].as_u64().unwrap_or(1).clamp(1, 64) as u32;
                let height = (texture["height"].as_u64().unwrap_or(0) as u32).saturating_mul(frames);
                let token = token.clone();
                async move {
                    if url.is_empty() || width == 0 || height == 0 || !validate::cape_id(&id) {
                        return None;
                    }
                    let spec = TextureSpec { segment: "cosmetics", id: &id, url: &url, width, total_height: height, cacheable: false };
                    self.trs.texture(&spec, token.as_deref()).await
                }
            });
            let previews = futures::future::join_all(jobs).await;
            for (item, preview) in list.iter_mut().zip(previews) {
                item["preview"] = preview.map_or(Value::Null, Value::String);
            }
        }
        Ok(value)
    }

    /// Kosmetik-Upload löschen (nur Admins; der Server prüft das).
    pub async fn admin_delete_cosmetic(&self, id: &str) -> Result<()> {
        if !validate::cape_id(id) {
            return Err(bad_filter());
        }
        self.trs_do(Req::delete(format!("/v1/admin/cosmetics/{id}"))).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_sanctions_are_checked() {
        let base = NewSanction {
            uuid: "B0B0B0B0-B0B0-B0B0-B0B0-B0B0B0B0B0B0".into(),
            kind: "chat_mute".into(),
            duration: "1d".into(),
            reason_code: "spam".into(),
            reason: Some("  Werbung  ".into()),
            note: Some("Zeile 1\r\nZeile 2".into()),
            ..Default::default()
        };
        assert_eq!(
            base.body().unwrap(),
            json!({ "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "kind": "chat_mute", "duration": "1d", "reasonCode": "spam",
                "reason": "Werbung", "note": "Zeile 1\nZeile 2" })
        );
        assert!(NewSanction { kind: "nuke".into(), ..base.clone() }.body().is_err());
        assert!(NewSanction { duration: "2d".into(), ..base.clone() }.body().is_err());
        assert!(NewSanction { reason_code: "auto_spam".into(), ..base.clone() }.body().is_err(), "System-Vorlagen nicht wählbar");
        assert!(NewSanction { duration: "custom".into(), ..base.clone() }.body().is_err(), "eigene Dauer braucht Minuten");
        assert!(NewSanction { duration: "custom".into(), minutes: Some(4), ..base.clone() }.body().is_err());
        let custom = NewSanction { duration: "custom".into(), minutes: Some(90), report_id: Some("r0123456789abcdef".into()), ..base.clone() };
        let body = custom.body().unwrap();
        assert_eq!(body["minutes"], 90);
        assert_eq!(body["reportId"], "r0123456789abcdef");
        assert!(NewSanction { reason: Some("a\nb".into()), ..base.clone() }.body().is_err(), "öffentlicher Grund einzeilig");
        assert!(NewSanction { note: Some("x".repeat(2001)), ..base.clone() }.body().is_err());
        assert!(NewSanction { note: Some("bö\u{202E}se".into()), ..base }.body().is_err());
    }

    #[test]
    fn list_filters_are_checked() {
        assert_eq!(SanctionQuery::default().path().unwrap(), "/v1/admin/sanctions?status=active&limit=50");
        let q = SanctionQuery { status: Some("all".into()), kind: Some("hosting_ban".into()), actor: Some("system".into()), sort: Some("oldest".into()), cursor: Some("MTIz:NDU".into()), ..Default::default() };
        assert_eq!(q.path().unwrap(), "/v1/admin/sanctions?status=all&limit=50&kind=hosting_ban&actor=system&sort=oldest&cursor=MTIz%3ANDU");
        assert!(SanctionQuery { kind: Some("x".into()), ..Default::default() }.path().is_err());
        assert!(SanctionQuery { cursor: Some("a b".into()), ..Default::default() }.path().is_err());
        assert_eq!(AppealQuery::default().path().unwrap(), "/v1/admin/appeals?status=open&limit=50");
        assert!(AppealQuery { status: Some("x".into()), ..Default::default() }.path().is_err());
        assert_eq!(
            PlayerQuery { q: Some("Steve_".into()), status: Some("banned".into()), ..Default::default() }.path().unwrap(),
            "/v1/admin/players?status=banned&sort=last_login&limit=50&q=Steve_"
        );
        assert!(PlayerQuery { q: Some("a b".into()), ..Default::default() }.path().is_err());
        assert_eq!(
            UploadQuery { q: Some("Rot & Gold".into()), sort: Some("newest".into()), ..Default::default() }.path("cosmetics").unwrap(),
            "/v1/admin/cosmetics?status=pending&q=Rot%20%26%20Gold&sort=newest"
        );
        assert_eq!(search_query("  #12 \n").unwrap(), "#12");
        assert!(search_query("   ").is_err());
    }

    #[test]
    fn decisions_and_bulk_are_checked() {
        let d = AppealDecision { decision: "uphold".into(), response: " Bleibt so.\r\nGruß ".into(), ends_at: None };
        assert_eq!(d.body().unwrap(), json!({ "decision": "uphold", "response": "Bleibt so.\nGruß" }));
        assert!(AppealDecision { decision: "shorten".into(), response: "ok".into(), ends_at: None }.body().is_err());
        assert!(AppealDecision { decision: "shorten".into(), response: "ok".into(), ends_at: Some("2001-01-01T00:00:00Z".into()) }.body().is_err());
        let s = AppealDecision { decision: "shorten".into(), response: "ok".into(), ends_at: Some("2999-01-01T00:00:00+01:00".into()) };
        assert_eq!(s.body().unwrap()["endsAt"], "2998-12-31T23:00:00.000Z");
        assert!(AppealDecision { decision: "nuke".into(), response: "ok".into(), ends_at: None }.body().is_err());
        assert!(AppealDecision { decision: "lift".into(), response: " ".into(), ends_at: None }.body().is_err());

        let bulk = BulkAction { ids: vec!["r0123456789abcdef".into(), "r0123456789abcdef".into()], action: "dismiss".into(), reason: Some("x".into()) };
        assert_eq!(bulk.body(BulkTarget::Reports).unwrap(), json!({ "ids": ["r0123456789abcdef"], "action": "dismiss" }));
        assert!(BulkAction { ids: vec!["team".into()], action: "dismiss".into(), reason: None }.body(BulkTarget::Reports).is_err());
        let capes = BulkAction { ids: vec!["u0123456789abcdef0123".into()], action: "reject".into(), reason: Some("Logo".into()) };
        assert_eq!(capes.body(BulkTarget::Capes).unwrap()["reason"], "Logo");
        let many = BulkAction { ids: (0..51).map(|i| format!("r{i:016x}")).collect(), action: "resolve".into(), reason: None };
        assert!(many.body(BulkTarget::Reports).is_err());
        assert!(BulkAction { ids: vec![], action: "approve".into(), reason: None }.body(BulkTarget::Cosmetics).is_err());
    }
}
