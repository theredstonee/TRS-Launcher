//! Partner-Programm (Vertrag: `api/API.md` §44): ein Code-Feld für Creator-,
//! Partner- und Umhang-Codes, der unterstützte Partner mit seiner Kosmetik und
//! die Partner, deren Dashboard man auf der Website öffnen darf.
//!
//! Gelesen wird großzügig: unbekannte Felder werden ignoriert, kaputte Einträge
//! fallen weg. Das Logo lädt der Kern selbst und gibt es als Data-URL weiter.

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde::Serialize;
use serde_json::{Value, json};

use super::chat::{chat_text, one_line};
use super::creator::{CreatorRedeem, https_url, looks_like_creator_code, pick, str_of, time, u64_of};
use super::live::LiveEvent;
use super::types::{ApiRedeem, RedeemResult};
use super::{Req, png, validate};
use crate::{Error, Launcher, Result};

pub const CATEGORIES: [&str; 3] = ["hosting", "network", "other"];
/// Rechte eines Partner-Mitglieds (§44.1).
pub const MEMBER_RIGHTS: [&str; 9] = ["stats", "codes", "api", "links", "server", "webhook", "promo", "cosmetics", "profile"];
const MAX_LOGO_BYTES: usize = 512 * 1024;
const MAX_LOGO_EDGE: u32 = 512;

// --- Prüfungen --------------------------------------------------------------------------

/// `^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$`
pub fn partner_slug(input: &str) -> Option<String> {
    let b = input.as_bytes();
    let edge = |c: &u8| c.is_ascii_lowercase() || c.is_ascii_digit();
    let ok = (3..=32).contains(&b.len())
        && b.first().is_some_and(edge)
        && b.last().is_some_and(edge)
        && b.iter().all(|c| edge(c) || *c == b'-');
    ok.then(|| input.to_owned())
}

/// Eingabe für das gemeinsame Code-Feld: ohne Leerzeichen, groß, 4–32 Zeichen aus A–Z, 0–9 und `-`.
/// Welche Art von Code es ist, entscheidet der Server.
pub fn any_code(input: &str) -> Option<String> {
    let s: String = input.chars().filter(|c| !c.is_whitespace()).collect::<String>().to_ascii_uppercase();
    let ok = (4..=32).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'-');
    ok.then_some(s)
}

fn invalid_code() -> Error {
    Error::validation(crate::msg!(
        "partner.invalidCode",
        "Codes haben 4 bis 32 Zeichen: Buchstaben, Ziffern und Bindestriche."
    ))
}

fn item_id(v: &Value, key: &str) -> Option<String> {
    v.get(key)?.as_str().filter(|id| validate::cape_id(id)).map(str::to_owned)
}

fn name_of(v: &Value, max: usize) -> String {
    one_line(v.get("name").and_then(Value::as_str).unwrap_or_default(), max)
}

/// Serveradresse `host[:port]`.
fn server_address(input: &str) -> Option<String> {
    let s = input.trim();
    let ok = (3..=255).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b':' | b'_'));
    ok.then(|| s.to_ascii_lowercase())
}

// --- Ansichten ---------------------------------------------------------------------------

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct PartnerText {
    pub de: String,
    pub en: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PartnerLink {
    pub label: String,
    pub url: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PartnerServer {
    pub name: String,
    pub address: String,
}

/// Kosmetik, die ein Partner vergibt (`cape` oder `cosmetic`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PartnerCosmetic {
    pub kind: String,
    pub id: String,
    pub name: String,
}

/// `PartnerCard` (§44.2). `logo` = Data-URL (vom Kern geladen).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PartnerCard {
    pub slug: String,
    pub name: String,
    pub category: String,
    #[serde(skip)]
    pub logo_url: Option<String>,
    pub logo: Option<String>,
    pub text: PartnerText,
    pub links: Vec<PartnerLink>,
    pub code: Option<String>,
    pub server: Option<PartnerServer>,
    pub wiki_url: Option<String>,
    pub supporters: u64,
    pub cosmetics: Vec<PartnerCosmetic>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PartnerSupport {
    pub partner: Option<PartnerCard>,
    pub started_at: Option<String>,
    pub switch_available_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PartnerRef {
    pub slug: String,
    pub name: String,
}

/// Eigene Kosmetik von einem Partner (bleibt auch nach einem Wechsel). `partner` fehlt, wenn es ihn nicht mehr gibt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct OwnedPartnerItem {
    pub kind: String,
    pub id: String,
    pub name: String,
    pub partner: Option<PartnerRef>,
}

/// Partner, dessen Dashboard man auf der Website öffnen darf.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PartnerMembership {
    pub slug: String,
    pub name: String,
    pub rights: Vec<String>,
    pub owner: bool,
}

/// `GET /v1/me/partner`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct MyPartner {
    pub support: PartnerSupport,
    pub owned: Vec<OwnedPartnerItem>,
    pub member: Vec<PartnerMembership>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GrantedCape {
    pub cape_id: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct GrantedCosmetic {
    pub id: String,
    pub name: String,
    pub slot: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct PartnerGranted {
    pub capes: Vec<GrantedCape>,
    pub cosmetics: Vec<GrantedCosmetic>,
}

/// Partner-Code eingelöst.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PartnerRedeem {
    pub partner: PartnerCard,
    pub support_changed: bool,
    pub switch_available_at: Option<String>,
    pub granted: PartnerGranted,
    pub one_time: bool,
}

/// Ergebnis des gemeinsamen Code-Felds: `{ kind, result }`.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "kind", content = "result", rename_all = "lowercase")]
pub enum RedeemOutcome {
    Creator(CreatorRedeem),
    Partner(PartnerRedeem),
    Cape(RedeemResult),
}

// --- Rohdaten → Ansicht ------------------------------------------------------------------

fn clean_text(v: Option<&Value>) -> PartnerText {
    let lang = |l: &str| v.and_then(|t| t.get(l)).and_then(Value::as_str).map(|t| chat_text(t, 1200).trim().to_owned()).unwrap_or_default();
    PartnerText { de: lang("de"), en: lang("en") }
}

fn clean_links(v: Option<&Value>) -> Vec<PartnerLink> {
    v.and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|l| {
            let url = https_url(l.get("url")?.as_str()?)?;
            let label = one_line(l.get("label").and_then(Value::as_str).unwrap_or_default(), 48);
            Some(PartnerLink { label: if label.is_empty() { url.clone() } else { label }, url })
        })
        .take(6)
        .collect()
}

fn clean_cosmetic(v: &Value) -> Option<PartnerCosmetic> {
    let id = item_id(v, "id")?;
    Some(PartnerCosmetic {
        kind: pick(str_of(v, "kind"), &["cape", "cosmetic"])?,
        name: validate::cape_name(v.get("name").and_then(Value::as_str).unwrap_or_default(), &id),
        id,
    })
}

/// Partner-Code: fester Code (`CRAFTING`) oder Einmal-Code (`CH-XXXX-XXXX`).
fn clean_partner_code(v: Option<&Value>) -> Option<String> {
    let s = v?.as_str()?;
    any_code(s).filter(|c| c.len() <= 20 || c.matches('-').count() == 2)
}

pub(crate) fn clean_card(v: &Value) -> Option<PartnerCard> {
    let v = v.as_object().map(|_| v)?;
    let slug = partner_slug(v.get("slug")?.as_str()?)?;
    let name = Some(name_of(v, 48)).filter(|n| !n.is_empty()).unwrap_or_else(|| slug.clone());
    let server = v.get("server").filter(|s| s.is_object()).and_then(|s| {
        let address = server_address(s.get("address")?.as_str()?)?;
        let name = Some(name_of(s, 64)).filter(|n| !n.is_empty()).unwrap_or_else(|| address.clone());
        Some(PartnerServer { name, address })
    });
    Some(PartnerCard {
        name,
        category: pick(str_of(v, "category"), &CATEGORIES).unwrap_or_else(|| "other".into()),
        logo_url: str_of(v, "logo").filter(|l| l.len() <= 300),
        logo: None,
        text: clean_text(v.get("text")),
        links: clean_links(v.get("links")),
        code: clean_partner_code(v.get("code")),
        server,
        wiki_url: str_of(v, "wikiUrl").and_then(|u| https_url(&u)),
        supporters: u64_of(v, "supporters"),
        cosmetics: v.get("cosmetics").and_then(Value::as_array).into_iter().flatten().filter_map(clean_cosmetic).take(50).collect(),
        slug,
    })
}

fn partner_ref(v: Option<&Value>) -> Option<PartnerRef> {
    let v = v?;
    let slug = partner_slug(v.get("slug")?.as_str()?)?;
    let name = Some(name_of(v, 48)).filter(|n| !n.is_empty()).unwrap_or_else(|| slug.clone());
    Some(PartnerRef { slug, name })
}

fn clean_rights(v: Option<&Value>) -> Vec<String> {
    let list: Vec<&str> = v.and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str).collect();
    MEMBER_RIGHTS.iter().filter(|r| list.contains(r)).map(|r| (*r).to_owned()).collect()
}

pub(crate) fn clean_me(v: &Value) -> Option<MyPartner> {
    v.as_object()?;
    let s = v.get("support").filter(|s| s.is_object());
    let support = PartnerSupport {
        partner: s.and_then(|s| s.get("partner")).and_then(clean_card),
        started_at: time(s.and_then(|s| str_of(s, "startedAt"))),
        switch_available_at: time(s.and_then(|s| str_of(s, "switchAvailableAt"))),
    };
    let owned = v
        .get("owned")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|o| {
            let id = item_id(o, "id")?;
            Some(OwnedPartnerItem {
                kind: pick(str_of(o, "kind"), &["cape", "cosmetic"])?,
                name: validate::cape_name(o.get("name").and_then(Value::as_str).unwrap_or_default(), &id),
                partner: partner_ref(o.get("partner")),
                id,
            })
        })
        .take(200)
        .collect();
    let member = v
        .get("member")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|m| {
            let r = partner_ref(Some(m))?;
            let owner = m.get("owner").and_then(Value::as_bool).unwrap_or(false);
            Some(PartnerMembership {
                slug: r.slug,
                name: r.name,
                rights: if owner { MEMBER_RIGHTS.iter().map(|r| (*r).to_owned()).collect() } else { clean_rights(m.get("rights")) },
                owner,
            })
        })
        .take(20)
        .collect();
    Some(MyPartner { support, owned, member })
}

fn clean_granted(v: Option<&Value>) -> PartnerGranted {
    let list = |key: &str| v.and_then(|g| g.get(key)).and_then(Value::as_array).into_iter().flatten().take(50);
    PartnerGranted {
        capes: list("capes")
            .filter_map(|c| {
                let id = item_id(c, "capeId").or_else(|| item_id(c, "id"))?;
                Some(GrantedCape { name: validate::cape_name(c.get("name").and_then(Value::as_str).unwrap_or_default(), &id), cape_id: id })
            })
            .collect(),
        cosmetics: list("cosmetics")
            .filter_map(|c| {
                let id = item_id(c, "id")?;
                Some(GrantedCosmetic {
                    name: validate::cape_name(c.get("name").and_then(Value::as_str).unwrap_or_default(), &id),
                    slot: pick(str_of(c, "slot"), super::ops::EQUIP_SLOTS),
                    id,
                })
            })
            .collect(),
    }
}

pub(crate) fn clean_redeem(v: &Value) -> Option<PartnerRedeem> {
    Some(PartnerRedeem {
        partner: clean_card(v.get("partner")?)?,
        support_changed: v.get("supportChanged").and_then(Value::as_bool).unwrap_or(false),
        switch_available_at: time(str_of(v, "switchAvailableAt")),
        granted: clean_granted(v.get("granted")),
        one_time: v.get("oneTime").and_then(Value::as_bool).unwrap_or(false),
    })
}

/// Fehlt `POST /v1/me/codes/redeem` (ältere API)? Dann gibt es nur die alten Wege.
fn route_missing(e: &Error) -> bool {
    matches!(e, Error::TrsApi { kind: "trs_api", code, .. } if code == "not_found" || code == "method_not_allowed")
}

// --- Echtzeit (§44.8) --------------------------------------------------------------------

pub(crate) fn decode_live(event: &str, data: &str) -> Option<LiveEvent> {
    let d: Value = serde_json::from_str(data).ok()?;
    match event {
        "partner_support_changed" => Some(LiveEvent::PartnerSupportChanged),
        "partner_updated" => Some(LiveEvent::PartnerUpdated {
            slug: partner_slug(d.get("slug")?.as_str()?)?,
            kind: str_of(&d, "kind").filter(|k| (1..=40).contains(&k.len()) && k.bytes().all(|b| b.is_ascii_lowercase() || b == b'_')),
        }),
        _ => None,
    }
}

// --- Anfragen ----------------------------------------------------------------------------

impl Launcher {
    async fn partner_logo(&self, card: &mut PartnerCard) {
        let Some(path) = card.logo_url.as_deref().and_then(|u| self.trs.own_path(u)).map(str::to_owned) else { return };
        let Some(bytes) = self.trs.own_png(&path, None, MAX_LOGO_BYTES).await else { return };
        if png::size(&bytes).is_some_and(|(w, h)| (1..=MAX_LOGO_EDGE).contains(&w) && (1..=MAX_LOGO_EDGE).contains(&h)) {
            card.logo = Some(format!("data:image/png;base64,{}", STANDARD.encode(&bytes)));
        }
    }

    /// Unterstützter Partner, eigene Partner-Kosmetik, Mitgliedschaften (§44.2).
    pub async fn partner_me(&self) -> Result<MyPartner> {
        let raw: Value = self.trs_get(Req::get("/v1/me/partner")).await?;
        let mut me = clean_me(&raw).ok_or_else(super::bad_response)?;
        if let Some(card) = me.support.partner.as_mut() {
            self.partner_logo(card).await;
        }
        Ok(me)
    }

    /// Partner nicht mehr unterstützen – die Kosmetik bleibt.
    pub async fn partner_end_support(&self) -> Result<()> {
        self.trs_do(Req::delete("/v1/me/partner/support")).await
    }

    /// Gemeinsames Code-Feld (§44.2): Creator-, Partner- oder Umhang-Code. Ältere APIs ohne die
    /// Route bekommen den Code wie bisher je nach Muster ans Creator- oder Umhang-Einlösen.
    pub async fn trs_redeem_any(&self, code: &str) -> Result<RedeemOutcome> {
        let code = any_code(code).ok_or_else(invalid_code)?;
        let raw: Value = match self.trs_get(Req::post("/v1/me/codes/redeem", json!({ "code": code }))).await {
            Ok(raw) => raw,
            Err(e) if route_missing(&e) => return self.redeem_legacy(&code).await,
            Err(e) => return Err(e),
        };
        match raw.get("kind").and_then(Value::as_str) {
            Some("creator") => Ok(RedeemOutcome::Creator(self.creator_redeem_result(&raw).await?)),
            Some("partner") => {
                let mut r = clean_redeem(&raw).ok_or_else(super::bad_response)?;
                self.partner_logo(&mut r.partner).await;
                Ok(RedeemOutcome::Partner(r))
            }
            Some("cape") => {
                let api: ApiRedeem = serde_json::from_value(raw).map_err(|_| super::bad_response())?;
                Ok(RedeemOutcome::Cape(super::ops::redeem_result(api)?))
            }
            _ => Err(super::bad_response()),
        }
    }

    async fn redeem_legacy(&self, code: &str) -> Result<RedeemOutcome> {
        if looks_like_creator_code(code) {
            Ok(RedeemOutcome::Creator(self.creator_redeem(code).await?))
        } else {
            Ok(RedeemOutcome::Cape(self.trs_redeem(code).await?))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_and_slugs() {
        assert_eq!(any_code(" crafting ").as_deref(), Some("CRAFTING"));
        assert_eq!(any_code("ch-7k2m-9qxa").as_deref(), Some("CH-7K2M-9QXA"));
        assert_eq!(any_code("7K3QF M2XPA 9RTVB C4HJN").as_deref(), Some("7K3QFM2XPA9RTVBC4HJN"));
        assert_eq!(any_code("7K3QF-M2XPA-9RTVB-C4HJN").as_deref(), Some("7K3QF-M2XPA-9RTVB-C4HJN"));
        for bad in ["abc", "", "CODE!", "ÄÖÜÄ", &"A".repeat(33)] {
            assert_eq!(any_code(bad), None, "{bad}");
        }
        assert_eq!(partner_slug("craftinghosting").as_deref(), Some("craftinghosting"));
        for bad in ["ab", "-abc", "abc-", "Abc", "a_b", &"a".repeat(33)] {
            assert_eq!(partner_slug(bad), None, "{bad}");
        }
    }

    fn card() -> Value {
        json!({ "slug": "craftinghosting", "name": "CraftingHosting", "category": "hosting", "logo": "/v1/partners/craftinghosting/logo.png?v=abc",
            "text": { "de": "Kostenlose Server", "en": "Free servers", "fr": "x" },
            "links": [{ "label": "Website", "url": "https://craftinghosting.de" }, { "label": "bad", "url": "http://x.de" }, { "url": "https://discord.gg/abc" }],
            "code": "CRAFTING", "server": { "name": "Lobby", "address": "Lobby.CraftingHosting.free:25565" },
            "wikiUrl": "javascript:alert(1)", "supporters": 41,
            "cosmetics": [{ "kind": "cape", "id": "partner_ch_cube", "name": "Würfel", "previewUrl": "/x.png" }, { "kind": "pet", "id": "x" }, { "kind": "cosmetic", "id": "../x" }],
            "future": { "a": 1 } })
    }

    #[test]
    fn card_is_cleaned() {
        let c = clean_card(&card()).unwrap();
        assert_eq!(c.category, "hosting");
        assert_eq!(c.text, PartnerText { de: "Kostenlose Server".into(), en: "Free servers".into() });
        assert_eq!(c.links.len(), 2);
        assert_eq!(c.links[1].label, "https://discord.gg/abc");
        assert_eq!(c.code.as_deref(), Some("CRAFTING"));
        assert_eq!(c.server.as_ref().unwrap().address, "lobby.craftinghosting.free:25565");
        assert_eq!(c.wiki_url, None);
        assert_eq!(c.cosmetics.len(), 1);
        let json = serde_json::to_value(&c).unwrap();
        assert!(json.get("logoUrl").is_none() && json["logo"].is_null());

        let minimal = clean_card(&json!({ "slug": "net-work", "category": "weird", "server": null, "links": null })).unwrap();
        assert_eq!((minimal.name.as_str(), minimal.category.as_str()), ("net-work", "other"));
        assert!(minimal.server.is_none() && minimal.links.is_empty() && minimal.cosmetics.is_empty());
        assert!(clean_card(&json!({ "slug": "NOPE" })).is_none());
        assert!(clean_card(&json!(null)).is_none());
    }

    #[test]
    fn me_and_redeem() {
        let me = clean_me(&json!({
            "support": { "partner": card(), "startedAt": "2026-11-20T10:00:00.000Z", "switchAvailableAt": "2026-12-20T10:00:00.000Z" },
            "owned": [{ "kind": "cape", "id": "partner_ch_cube", "name": "Würfel", "partner": { "slug": "craftinghosting", "name": "CraftingHosting" } },
                      { "kind": "cape", "id": "x", "name": "ohne Partner", "partner": null }, { "kind": "badge", "id": "y" }],
            "member": [{ "slug": "craftinghosting", "name": "CraftingHosting", "rights": ["stats", "root", "codes"], "owner": false },
                       { "slug": "other-one", "name": "Other", "rights": [], "owner": true }]
        }))
        .unwrap();
        assert_eq!(me.support.partner.unwrap().slug, "craftinghosting");
        assert_eq!(me.owned.len(), 2);
        assert_eq!(me.owned[1].partner, None);
        assert_eq!(me.member[0].rights, ["stats", "codes"]);
        assert_eq!(me.member[1].rights.len(), MEMBER_RIGHTS.len());

        let empty = clean_me(&json!({ "support": { "partner": null } })).unwrap();
        assert_eq!(empty, MyPartner::default());

        let r = clean_redeem(&json!({ "kind": "partner", "partner": card(), "supportChanged": true, "switchAvailableAt": null,
            "granted": { "capes": [{ "capeId": "partner_ch_cube", "name": "Würfel" }], "cosmetics": [{ "id": "ch_cube_hat", "name": "Schwebender Würfel", "slot": "hat" }, { "id": "y", "slot": "tail" }] },
            "oneTime": true }))
        .unwrap();
        assert!(r.support_changed && r.one_time);
        assert_eq!(r.granted.capes[0].cape_id, "partner_ch_cube");
        assert_eq!(r.granted.cosmetics[1].slot, None);
        let json = serde_json::to_value(RedeemOutcome::Partner(r)).unwrap();
        assert_eq!(json["kind"], "partner");
        assert_eq!(json["result"]["granted"]["capes"][0]["capeId"], "partner_ch_cube");
    }

    #[test]
    fn live_events() {
        assert_eq!(decode_live("partner_support_changed", "{}"), Some(LiveEvent::PartnerSupportChanged));
        assert_eq!(
            decode_live("partner_updated", r#"{"slug":"craftinghosting","kind":"request_decided"}"#),
            Some(LiveEvent::PartnerUpdated { slug: "craftinghosting".into(), kind: Some("request_decided".into()) })
        );
        assert_eq!(decode_live("partner_updated", r#"{"slug":"../x"}"#), None);
        assert_eq!(decode_live("partner_unknown", "{}"), None);
    }
}
