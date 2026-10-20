//! Team-Verwaltung der empfohlenen Modpacks (Recht `featured.manage`): Link einfügen,
//! Texte/Abzeichen/Anheften/Zeitraum ändern, Reihenfolge, neu laden, entfernen.
//! Die öffentliche Liste für die Startseite steht in [`crate::featured`].

use serde::Deserialize;
use serde_json::{Value, json};

use super::Req;
use crate::featured::{
    self, AdminFeaturedPack, FeaturedBadge, FeaturedSource, MAX_ADMIN, MAX_TEXT_BODY, MAX_TEXT_TITLE,
};
use crate::{Error, Launcher, Result};

/// Längster Link, den das Team einfügen kann.
const MAX_LINK: usize = 512;

fn invalid_link() -> Error {
    Error::validation(crate::msg!(
        "featured.invalidLink",
        "Bitte einen Link zu einem Modpack auf Modrinth oder CurseForge einfügen."
    ))
}

fn invalid_id() -> Error {
    Error::validation(crate::msg!("featured.invalidId", "Diesen Eintrag gibt es nicht."))
}

fn id_arg(id: &str) -> Result<&str> {
    let ok = (1..=64).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'));
    if ok { Ok(id) } else { Err(invalid_id()) }
}

/// Eingefügter Link → `https://…` auf modrinth.com oder (www.)curseforge.com. Ohne Schema wird `https://` ergänzt,
/// `http://` wird zu `https://`. Ob es wirklich ein Modpack ist, prüft der Server.
pub fn featured_link(input: &str) -> Result<String> {
    let input = input.trim();
    if input.is_empty() || input.len() > MAX_LINK {
        return Err(invalid_link());
    }
    let url = if let Some(rest) = input.strip_prefix("http://") {
        format!("https://{rest}")
    } else if input.starts_with("https://") {
        input.to_owned()
    } else if input.contains("://") {
        return Err(invalid_link());
    } else {
        format!("https://{input}")
    };
    let ok = [FeaturedSource::Modrinth, FeaturedSource::Curseforge].into_iter().any(|s| featured::is_project_link(s, &url));
    if ok { Ok(url) } else { Err(invalid_link()) }
}

/// Eigene Texte des Teams für eine Sprache (leer = Text von Modrinth/CurseForge).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TextPatch {
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub text: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TextsPatch {
    #[serde(default)]
    pub en: TextPatch,
    #[serde(default)]
    pub de: TextPatch,
}

/// Änderung an einem Eintrag. Fehlende Felder bleiben, `null` leert (Abzeichen, Zeitraum).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FeaturedPatch {
    #[serde(default)]
    pub texts: Option<TextsPatch>,
    #[serde(default, with = "double_option")]
    pub badge: Option<Option<FeaturedBadge>>,
    #[serde(default)]
    pub pinned: Option<bool>,
    #[serde(default, with = "double_option")]
    pub starts_at: Option<Option<String>>,
    #[serde(default, with = "double_option")]
    pub ends_at: Option<Option<String>>,
}

mod double_option {
    use serde::{Deserialize, Deserializer};

    pub fn deserialize<'de, T: Deserialize<'de>, D: Deserializer<'de>>(d: D) -> Result<Option<Option<T>>, D::Error> {
        Option::<T>::deserialize(d).map(Some)
    }
}

fn text_too_long() -> Error {
    Error::validation(crate::msg!(
        "featured.textTooLong",
        "Titel höchstens {title} Zeichen, Text höchstens {text} Zeichen.",
        title = MAX_TEXT_TITLE,
        text = MAX_TEXT_BODY
    ))
}

/// Ein Team-Text: Steuerzeichen raus, leer = `null`, zu lang = Fehler (statt still zu kürzen).
fn team_text(value: Option<&str>, max: usize) -> Result<Value> {
    let Some(value) = value else { return Ok(Value::Null) };
    let clean = featured::clean_text(value, 1000);
    if clean.chars().count() > max {
        return Err(text_too_long());
    }
    Ok(if clean.is_empty() { Value::Null } else { json!(clean) })
}

fn window_time(value: Option<&str>) -> Result<Value> {
    match value.map(str::trim).filter(|v| !v.is_empty()) {
        None => Ok(Value::Null),
        Some(at) => Ok(json!(super::validate::iso_datetime(at)?)),
    }
}

impl FeaturedPatch {
    pub(crate) fn body(&self) -> Result<Value> {
        let mut body = json!({});
        if let Some(texts) = &self.texts {
            let lang = |t: &TextPatch| -> Result<Value> {
                Ok(json!({
                    "title": team_text(t.title.as_deref(), MAX_TEXT_TITLE)?,
                    "text": team_text(t.text.as_deref(), MAX_TEXT_BODY)?,
                }))
            };
            body["texts"] = json!({ "en": lang(&texts.en)?, "de": lang(&texts.de)? });
        }
        if let Some(badge) = self.badge {
            body["badge"] = json!(badge);
        }
        if let Some(pinned) = self.pinned {
            body["pinned"] = json!(pinned);
        }
        if let Some(at) = &self.starts_at {
            body["startsAt"] = window_time(at.as_deref())?;
        }
        if let Some(at) = &self.ends_at {
            body["endsAt"] = window_time(at.as_deref())?;
        }
        if let (Some(start), Some(end)) = (body["startsAt"].as_str(), body["endsAt"].as_str())
            && start >= end
        {
            // Gleiches Format (ISO, UTC, Millisekunden) – der Textvergleich ist ein Zeitvergleich.
            return Err(Error::validation(crate::msg!("featured.invalidWindow", "Das Ende muss nach dem Beginn liegen.")));
        }
        if body.as_object().is_some_and(serde_json::Map::is_empty) {
            return Err(Error::validation(crate::msg!("creator.nothingToChange", "Es gibt nichts zu ändern.")));
        }
        Ok(body)
    }
}

/// Neue Reihenfolge: nur gültige, eindeutige IDs.
fn order_arg(ids: &[String]) -> Result<Vec<&str>> {
    let invalid = || Error::validation(crate::msg!("featured.invalidOrder", "Die Reihenfolge passt nicht – bitte die Liste neu laden."));
    if ids.is_empty() || ids.len() > MAX_ADMIN {
        return Err(invalid());
    }
    let mut out: Vec<&str> = Vec::with_capacity(ids.len());
    for id in ids {
        let id = id_arg(id).map_err(|_| invalid())?;
        if out.contains(&id) {
            return Err(invalid());
        }
        out.push(id);
    }
    Ok(out)
}

impl Launcher {
    fn clean_featured_one(&self, value: &Value) -> Result<AdminFeaturedPack> {
        let pack = value.get("pack").unwrap_or(value);
        featured::clean_admin(pack, &self.trs().trusted_bases()).ok_or_else(super::bad_response)
    }

    /// Nach einer Änderung soll die Startseite nicht den alten Stand aus dem Cache zeigen.
    async fn featured_changed(&self) {
        featured::forget_cache(self.paths()).await;
    }

    pub async fn trs_admin_featured_packs(&self) -> Result<Vec<AdminFeaturedPack>> {
        let value: Value = self.trs_get(Req::get("/v1/admin/featured-packs")).await?;
        Ok(featured::parse_admin_list(&value, &self.trs().trusted_bases()))
    }

    /// Modpack per Link hinzufügen (Modrinth oder CurseForge).
    pub async fn trs_admin_add_featured_pack(&self, url: &str) -> Result<AdminFeaturedPack> {
        let url = featured_link(url)?;
        let value: Value = self.trs_get(Req::post("/v1/admin/featured-packs", json!({ "url": url }))).await?;
        self.featured_changed().await;
        self.clean_featured_one(&value)
    }

    pub async fn trs_admin_update_featured_pack(&self, id: &str, patch: &FeaturedPatch) -> Result<AdminFeaturedPack> {
        let id = id_arg(id)?;
        let body = patch.body()?;
        let value: Value = self.trs_get(Req::patch(format!("/v1/admin/featured-packs/{id}"), body)).await?;
        self.featured_changed().await;
        self.clean_featured_one(&value)
    }

    pub async fn trs_admin_delete_featured_pack(&self, id: &str) -> Result<()> {
        let id = id_arg(id)?;
        self.trs_do(Req::delete(format!("/v1/admin/featured-packs/{id}"))).await?;
        self.featured_changed().await;
        Ok(())
    }

    /// Reihenfolge setzen (alle IDs, Angeheftete zählen mit) – Antwort ist die neue Liste.
    pub async fn trs_admin_order_featured_packs(&self, ids: &[String]) -> Result<Vec<AdminFeaturedPack>> {
        let ids = order_arg(ids)?;
        let value: Value = self.trs_get(Req::put("/v1/admin/featured-packs/order", json!({ "ids": ids }))).await?;
        self.featured_changed().await;
        Ok(featured::parse_admin_list(&value, &self.trs().trusted_bases()))
    }

    /// Daten und Bilder neu von Modrinth/CurseForge holen.
    pub async fn trs_admin_refresh_featured_pack(&self, id: &str) -> Result<AdminFeaturedPack> {
        let id = id_arg(id)?;
        let value: Value = self.trs_get(Req::post_empty(format!("/v1/admin/featured-packs/{id}/refresh"))).await?;
        self.featured_changed().await;
        self.clean_featured_one(&value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn featured_links_are_normalised() {
        assert_eq!(featured_link(" modrinth.com/modpack/fo ").unwrap(), "https://modrinth.com/modpack/fo");
        assert_eq!(featured_link("http://www.curseforge.com/minecraft/modpacks/atm10").unwrap(), "https://www.curseforge.com/minecraft/modpacks/atm10");
        for bad in ["", "ftp://modrinth.com/x", "https://evil.example/modpack/x", "javascript:alert(1)", "https://modrinth.com.evil.example/x"] {
            assert_eq!(featured_link(bad).unwrap_err().message_code(), "featured.invalidLink", "{bad}");
        }
        assert!(featured_link(&format!("https://modrinth.com/{}", "a".repeat(600))).is_err());
    }

    #[test]
    fn featured_patch_body() {
        let patch: FeaturedPatch = serde_json::from_value(json!({
            "texts": { "en": { "title": " Our pick ", "text": "" }, "de": { "title": null, "text": "Schnell\nund schön" } },
            "badge": null, "pinned": true, "startsAt": "2026-10-20T10:00:00+02:00", "endsAt": null
        }))
        .unwrap();
        assert_eq!(
            patch.body().unwrap(),
            json!({
                "texts": { "en": { "title": "Our pick", "text": null }, "de": { "title": null, "text": "Schnell und schön" } },
                "badge": null, "pinned": true, "startsAt": "2026-10-20T08:00:00.000Z", "endsAt": null
            })
        );
        let only_badge: FeaturedPatch = serde_json::from_value(json!({ "badge": "event" })).unwrap();
        assert_eq!(only_badge.body().unwrap(), json!({ "badge": "event" }));
        assert!(serde_json::from_value::<FeaturedPatch>(json!({ "badge": "hot" })).is_err());
        assert!(serde_json::from_value::<FeaturedPatch>(json!({ "evil": 1 })).is_err());
        assert_eq!(FeaturedPatch::default().body().unwrap_err().message_code(), "creator.nothingToChange");
        let long: FeaturedPatch = serde_json::from_value(json!({ "texts": { "en": { "title": "x".repeat(61) } } })).unwrap();
        assert_eq!(long.body().unwrap_err().message_code(), "featured.textTooLong");
        let window: FeaturedPatch =
            serde_json::from_value(json!({ "startsAt": "2026-10-20T10:00:00Z", "endsAt": "2026-10-20T09:00:00Z" })).unwrap();
        assert_eq!(window.body().unwrap_err().message_code(), "featured.invalidWindow");
        let bad_time: FeaturedPatch = serde_json::from_value(json!({ "endsAt": "morgen" })).unwrap();
        assert!(bad_time.body().is_err());
    }

    #[test]
    fn featured_order_ids_are_checked() {
        assert_eq!(order_arg(&["a".into(), "b-2".into()]).unwrap(), ["a", "b-2"]);
        assert!(order_arg(&[]).is_err());
        assert!(order_arg(&["a".into(), "a".into()]).is_err());
        assert!(order_arg(&["../x".into()]).is_err());
        assert!(order_arg(&vec!["a".to_owned(); MAX_ADMIN + 1]).is_err());
    }
}
