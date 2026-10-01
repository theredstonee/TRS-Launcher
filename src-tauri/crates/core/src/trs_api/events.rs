//! Events (§31, z. B. Halloween): welche für diesen Spieler aktiv sind, Gratis-Abholen
//! von Event-Kosmetik und die Team-Verwaltung (Schalter + Spieler-Allowlist).

use serde::{Deserialize, Serialize};
use serde_json::json;

use super::{Req, validate};
use crate::{Error, Launcher, Result};

/// Mehr Events gleichzeitig gibt es nicht.
const MAX_EVENTS: usize = 16;
const MAX_PLAYERS: usize = 500;

/// `^[a-z][a-z0-9_-]{0,31}$`
pub fn event_id(id: &str) -> bool {
    let b = id.as_bytes();
    (1..=32).contains(&b.len())
        && b[0].is_ascii_lowercase()
        && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'_' | b'-'))
}

/// Nur gültige, einmalige Event-IDs.
pub(crate) fn clean_events(raw: Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for id in raw {
        if event_id(&id) && !out.contains(&id) && out.len() < MAX_EVENTS {
            out.push(id);
        }
    }
    out
}

/// Spieler auf der Allowlist eines Events.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventPlayer {
    pub uuid: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub added_at: Option<serde_json::Value>,
}

/// Event in der Team-Verwaltung (`GET /v1/admin/events`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdminEvent {
    pub id: String,
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub updated_at: Option<serde_json::Value>,
    #[serde(default)]
    pub players: Vec<EventPlayer>,
}

impl AdminEvent {
    fn cleaned(mut self) -> Option<Self> {
        if !event_id(&self.id) {
            return None;
        }
        self.players = self
            .players
            .into_iter()
            .filter_map(|p| {
                Some(EventPlayer {
                    uuid: validate::uuid(&p.uuid)?,
                    name: validate::display_name(&p.name),
                    added_at: p.added_at.filter(|v| v.is_number() || v.is_string()),
                })
            })
            .take(MAX_PLAYERS)
            .collect();
        self.updated_at = self.updated_at.filter(|v| v.is_number() || v.is_string());
        Some(self)
    }
}

/// Antwort von `GET /v1/admin/events`: Liste direkt oder `{ events: [...] }`.
#[derive(Deserialize)]
#[serde(untagged)]
enum ApiAdminEvents {
    List(Vec<serde_json::Value>),
    Wrapped { events: Vec<serde_json::Value> },
}

fn clean_list(raw: Vec<serde_json::Value>) -> Vec<AdminEvent> {
    raw.into_iter()
        .filter_map(|v| serde_json::from_value::<AdminEvent>(v).ok())
        .filter_map(AdminEvent::cleaned)
        .take(MAX_EVENTS)
        .collect()
}

fn event_arg(id: &str) -> Result<&str> {
    if event_id(id) {
        Ok(id)
    } else {
        Err(Error::validation(crate::msg!("trsEvents.invalidEvent", "Ungültiges Event.")))
    }
}

fn cosmetic_arg(id: &str) -> Result<&str> {
    if validate::cape_id(id) {
        Ok(id)
    } else {
        Err(Error::validation(crate::msg!("trsOps.invalidCosmeticId", "Ungültige Kosmetik-ID.")))
    }
}

impl Launcher {
    /// Events, die für den aktiven Spieler zuletzt aktiv waren (aus `me` bzw. `events_changed`).
    pub fn trs_active_events(&self) -> Vec<String> {
        self.trs.active_events()
    }

    /// Kosmetik-Teil eines laufenden Events gratis holen (idempotent).
    pub async fn trs_claim_cosmetic(&self, id: &str) -> Result<()> {
        let id = cosmetic_arg(id)?;
        self.trs_do(Req::post_empty(format!("/v1/me/cosmetics/{id}/claim"))).await
    }

    /// Event-Umhang gratis holen (idempotent).
    pub async fn trs_claim_cape(&self, id: &str) -> Result<()> {
        let id = cosmetic_arg(id)?;
        self.trs_do(Req::post_empty(format!("/v1/me/capes/{id}/claim"))).await
    }

    /// Begleiter aufsetzen (`Some(id)`) oder absetzen (`None`).
    pub async fn trs_set_companion(&self, id: Option<String>) -> Result<()> {
        if let Some(id) = &id {
            cosmetic_arg(id)?;
        }
        self.trs_do(Req::put("/v1/me/cosmetics", json!({ "companion": id }))).await
    }

    pub async fn trs_admin_events(&self) -> Result<Vec<AdminEvent>> {
        Ok(match self.trs_get::<ApiAdminEvents>(Req::get("/v1/admin/events")).await? {
            ApiAdminEvents::List(l) | ApiAdminEvents::Wrapped { events: l } => clean_list(l),
        })
    }

    pub async fn trs_admin_set_event(&self, id: &str, enabled: bool) -> Result<()> {
        let id = event_arg(id)?;
        self.trs_do(Req::put(format!("/v1/admin/events/{id}"), json!({ "enabled": enabled }))).await
    }

    /// Spieler per Mojang-Name oder UUID auf die Allowlist setzen.
    pub async fn trs_admin_add_event_player(&self, id: &str, player: &str) -> Result<()> {
        let id = event_arg(id)?;
        let target = validate::target(player)?;
        let body = if validate::uuid(&target).is_some() { json!({ "uuid": target }) } else { json!({ "name": target }) };
        self.trs_do(Req::post(format!("/v1/admin/events/{id}/players"), body)).await
    }

    pub async fn trs_admin_remove_event_player(&self, id: &str, uuid: &str) -> Result<()> {
        let id = event_arg(id)?;
        let uuid = validate::uuid(uuid)
            .ok_or_else(|| Error::validation(crate::msg!("trsOps.invalidPlayerId", "Ungültige Spieler-ID.")))?;
        self.trs_do(Req::delete(format!("/v1/admin/events/{id}/players/{uuid}"))).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_ids_are_checked_and_deduplicated() {
        assert!(event_id("halloween") && event_id("x-mas_2026"));
        assert!(!event_id("") && !event_id("Halloween") && !event_id("../x") && !event_id(&"a".repeat(33)));
        let list = clean_events(vec!["halloween".into(), "bad id".into(), "halloween".into(), "xmas".into()]);
        assert_eq!(list, ["halloween", "xmas"]);
    }

    #[test]
    fn cape_unlock_accepts_string_and_event_object() {
        use super::super::types::{Unlock, UnlockSpec};
        let plain: UnlockSpec = serde_json::from_value(json!("code")).unwrap();
        assert_eq!((plain.kind, plain.event), (Unlock::Code, None));
        let ev: UnlockSpec = serde_json::from_value(json!({ "type": "event", "event": "halloween" })).unwrap();
        assert_eq!((ev.kind, ev.event.as_deref()), (Unlock::Event, Some("halloween")));
        let odd: UnlockSpec = serde_json::from_value(json!({ "type": "nuke", "event": "Bad Id" })).unwrap();
        assert_eq!((odd.kind, odd.event), (Unlock::Other, None));
    }

    #[test]
    fn admin_events_accept_list_and_wrapper_and_drop_junk() {
        let raw = json!([
            { "id": "halloween", "enabled": true, "updatedAt": 5, "players": [
                { "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "name": "Notch", "addedAt": 7 },
                { "uuid": "nope", "name": "x" } ] },
            { "id": "../evil", "enabled": true, "players": [] }
        ]);
        let a: ApiAdminEvents = serde_json::from_value(raw.clone()).unwrap();
        let b: ApiAdminEvents = serde_json::from_value(json!({ "events": raw })).unwrap();
        for api in [a, b] {
            let (ApiAdminEvents::List(l) | ApiAdminEvents::Wrapped { events: l }) = api;
            let events = clean_list(l);
            assert_eq!(events.len(), 1);
            assert!(events[0].enabled);
            assert_eq!(events[0].players.len(), 1);
            assert_eq!(events[0].players[0].uuid, "069a79f444e94726a5befca90e38aaf5");
        }
    }

    #[tokio::test]
    async fn me_events_claim_and_companion_use_the_contract_routes() {
        use crate::trs_api::testkit::{MockServer, Response};
        use crate::trs_api::tests::{ACC, auth_routes, launcher};
        use std::sync::atomic::AtomicUsize;

        let issued = AtomicUsize::new(0);
        let server = MockServer::start(move |req| {
            if let Some(r) = auth_routes(req, &issued, "mc-token") {
                return r;
            }
            match (req.method.as_str(), req.path.split('?').next().unwrap_or_default()) {
                ("GET", "/v1/me") => Response::json(
                    200,
                    json!({ "uuid": ACC, "name": "Theredstonee", "admin": false,
                        "settings": { "showBadge": true, "showCapeToOthers": true, "presenceVisibility": "friends", "shareServer": false },
                        "activeCape": null, "events": ["halloween", "../x", "halloween"] }),
                ),
                ("POST", "/v1/me/cosmetics/witch_hat/claim") | ("POST", "/v1/me/capes/halloween/claim") => {
                    Response::json(200, json!({ "owned": true }))
                }
                ("PUT", "/v1/me/cosmetics") => Response::json(200, json!({})),
                ("PUT", "/v1/admin/events/halloween") => Response::json(200, json!({})),
                ("POST", "/v1/admin/events/halloween/players") => Response::json(200, json!({})),
                _ => Response::json(404, json!({ "error": { "code": "not_found", "message": "x" } })),
            }
        })
        .await;
        let (_dir, launcher) = launcher(&server, &[ACC]).await;
        assert!(launcher.trs_active_events().is_empty());
        let me = launcher.trs_me().await.unwrap();
        assert_eq!(me.events, ["halloween"]);
        assert_eq!(launcher.trs_active_events(), ["halloween"], "me füllt den Zwischenspeicher");
        launcher.trs_claim_cosmetic("witch_hat").await.unwrap();
        launcher.trs_claim_cape("halloween").await.unwrap();
        launcher.trs_set_companion(Some("bat_buddy".into())).await.unwrap();
        assert!(launcher.trs_claim_cosmetic("../x").await.is_err());
        launcher.trs_admin_set_event("halloween", true).await.unwrap();
        launcher.trs_admin_add_event_player("halloween", "Notch").await.unwrap();
        let body = &server.hits("POST", "/v1/admin/events/halloween/players")[0];
        assert!(String::from_utf8_lossy(&body.body).contains("\"name\""));
        let put = &server.hits("PUT", "/v1/me/cosmetics")[0];
        assert!(String::from_utf8_lossy(&put.body).contains("\"companion\":\"bat_buddy\""));
    }
}
