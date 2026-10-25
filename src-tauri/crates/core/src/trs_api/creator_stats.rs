//! Creator-Statistik für das Team (Vertrag: `api/API.md` §35.14, Recht
//! `creators.manage`): Rangliste aller Creator (Fans, neue Fans, neue Spieler,
//! Haustier-Codes, aktive Fans) und je Creator die Tagesreihen der letzten Tage.
//!
//! Nur Zahlen – nie Namen von Fans. Antworten werden wie überall gesäubert:
//! unbekannte Stufen/Zustände fallen weg, kaputte Tage werden übersprungen.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::chat::one_line;
use super::creator::{STATES, TIERS, creator_code, invalid, opt_u64, pick, str_of, time, u64_of};
use super::{Req, encode_query, validate};
use crate::{Launcher, Result};

/// Sortierschlüssel der Rangliste (gleiche Namen wie die API).
pub const SORT_KEYS: [&str; 15] = [
    "fans",
    "fansToday",
    "fans7d",
    "fans30d",
    "newPlayers",
    "newPlayers7d",
    "newPlayers30d",
    "petCodes",
    "petRedemptions",
    "online7d",
    "online30d",
    "played7d",
    "played30d",
    "name",
    "since",
];
/// Tage der Reihen (Standard 90).
pub const SERIES_DAYS: std::ops::RangeInclusive<u32> = 7..=365;
pub const SERIES_DAYS_DEFAULT: u32 = 90;
/// Größte Rangliste.
const MAX_ROWS: usize = 500;

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatsDefinitions {
    /// Neue Spieler: Konto höchstens so viele Stunden vor der ersten Einlösung angelegt.
    pub new_player_window_hours: u32,
    /// Ab diesem Tag wird Aktivität je Tag erfasst (`None` = noch nie).
    pub activity_since: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FanCounts {
    /// Aktuelle Fans.
    pub total: u64,
    /// Neue Unterstützungen heute / in 7 / in 30 Tagen.
    pub today: u64,
    pub d7: u64,
    pub d30: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NewPlayerCounts {
    pub total: u64,
    pub d7: u64,
    pub d30: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatsPet {
    pub cosmetic_id: String,
    pub name: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PetMonth {
    /// Kalendermonat `YYYY-MM`.
    pub month: Option<String>,
    /// Schon vergebene Einlösungen (Summe `maxUses`) und Monatsgrenze der Stufe.
    pub used: u64,
    pub limit: u64,
    /// Tatsächliche Einlösungen in diesem Monat.
    pub redemptions: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PetStats {
    pub pet: Option<StatsPet>,
    pub codes: u64,
    pub active_codes: u64,
    pub redemptions: u64,
    pub month: PetMonth,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FanActivity {
    pub online_7d: u64,
    pub online_30d: u64,
    pub played_7d: u64,
    pub played_30d: u64,
}

/// Eine Zeile der Rangliste (auch Kopf der Detailseite).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorStatRow {
    pub uuid: String,
    pub name: String,
    pub tier: String,
    pub state: String,
    pub since: Option<String>,
    pub main_code: Option<String>,
    pub fans: FanCounts,
    pub new_players: NewPlayerCounts,
    pub pets: PetStats,
    pub activity: FanActivity,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatsTotals {
    pub creators: u64,
    pub fans: u64,
    pub fans_7d: u64,
    pub fans_30d: u64,
    pub new_players: u64,
    pub new_players_30d: u64,
    pub pet_redemptions: u64,
    pub online_7d: u64,
    pub online_30d: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorStatsList {
    pub generated_at: Option<String>,
    pub definitions: StatsDefinitions,
    pub totals: StatsTotals,
    pub creators: Vec<CreatorStatRow>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SeriesDay {
    pub day: String,
    pub new_fans: u64,
    pub new_players: u64,
    pub redemptions: u64,
    pub pet_redemptions: u64,
    /// `None` = an diesem Tag noch nicht erfasst (vor `activitySince`).
    pub active_fans: Option<u64>,
    pub playing_fans: Option<u64>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FanCapeShare {
    /// Alle Besitzer eines Fan-Umhangs dieses Creators (auch ehemalige Fans).
    pub holders: u64,
    /// Davon aktuelle Fans.
    pub fan_holders: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorStatsDetail {
    pub generated_at: Option<String>,
    pub definitions: StatsDefinitions,
    pub creator: CreatorStatRow,
    pub fan_cape: FanCapeShare,
    /// Älteste zuerst, letzter Tag = heute.
    pub series: Vec<SeriesDay>,
}

/// Filter und Sortierung der Rangliste.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CreatorStatsQuery {
    #[serde(default)]
    pub q: Option<String>,
    #[serde(default)]
    pub tier: Option<String>,
    #[serde(default)]
    pub state: Option<String>,
    #[serde(default)]
    pub sort: Option<String>,
    #[serde(default)]
    pub order: Option<String>,
    #[serde(default)]
    pub limit: Option<u32>,
}

fn invalid_filter() -> crate::Error {
    invalid(crate::msg!("reports.invalidFilter", "Ungültiger Filter."))
}

impl CreatorStatsQuery {
    pub(crate) fn path(&self) -> Result<String> {
        let mut parts: Vec<String> = Vec::new();
        if let Some(q) = self.q.as_deref().map(|q| one_line(q, 32)).filter(|q| !q.is_empty()) {
            parts.push(format!("q={}", encode_query(&q)));
        }
        if let Some(tier) = self.tier.as_deref().filter(|t| !t.is_empty() && *t != "all") {
            if !TIERS.contains(&tier) {
                return Err(invalid_filter());
            }
            parts.push(format!("tier={tier}"));
        }
        if let Some(state) = self.state.as_deref().filter(|s| !s.is_empty()) {
            if state != "all" && !STATES.contains(&state) {
                return Err(invalid_filter());
            }
            parts.push(format!("state={state}"));
        }
        if let Some(sort) = self.sort.as_deref().filter(|s| !s.is_empty()) {
            if !SORT_KEYS.contains(&sort) {
                return Err(invalid_filter());
            }
            parts.push(format!("sort={sort}"));
        }
        if let Some(order) = self.order.as_deref().filter(|o| !o.is_empty()) {
            if order != "asc" && order != "desc" {
                return Err(invalid_filter());
            }
            parts.push(format!("order={order}"));
        }
        if let Some(limit) = self.limit {
            parts.push(format!("limit={}", limit.clamp(1, MAX_ROWS as u32)));
        }
        let mut path = "/v1/admin/creators/stats".to_owned();
        if !parts.is_empty() {
            path.push('?');
            path.push_str(&parts.join("&"));
        }
        Ok(path)
    }
}

// --- Rohdaten → Ansicht ------------------------------------------------------------------

fn day_of(v: Option<&Value>) -> Option<String> {
    let day = v?.as_str()?;
    (day.len() == 10 && chrono::NaiveDate::parse_from_str(day, "%Y-%m-%d").is_ok()).then(|| day.to_owned())
}

fn obj(v: &Value, key: &str) -> Value {
    v.get(key).filter(|o| o.is_object()).cloned().unwrap_or(Value::Null)
}

fn clean_definitions(v: Option<&Value>) -> StatsDefinitions {
    let null = Value::Null;
    let v = v.unwrap_or(&null);
    StatsDefinitions {
        new_player_window_hours: opt_u64(v, "newPlayerWindowHours").map_or(24, |h| h.clamp(1, 24 * 30) as u32),
        activity_since: day_of(v.get("activitySince")),
    }
}

fn clean_row(v: &Value) -> Option<CreatorStatRow> {
    let fans = obj(v, "fans");
    let players = obj(v, "newPlayers");
    let pets = obj(v, "pets");
    let month = obj(&pets, "month");
    let activity = obj(v, "activity");
    let pet = pets.get("pet").filter(|p| p.is_object()).and_then(|p| {
        let id = p.get("cosmeticId")?.as_str()?;
        let ok = (1..=40).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-');
        ok.then(|| StatsPet { cosmetic_id: id.to_owned(), name: one_line(p.get("name").and_then(Value::as_str).unwrap_or(id), 48) })
    });
    Some(CreatorStatRow {
        uuid: validate::uuid(v.get("uuid")?.as_str()?)?,
        name: validate::display_name(v.get("name").and_then(Value::as_str).unwrap_or_default()),
        tier: pick(str_of(v, "tier"), &TIERS)?,
        state: pick(str_of(v, "state"), &STATES)?,
        since: time(str_of(v, "since")),
        main_code: str_of(v, "mainCode").and_then(|c| creator_code(&c)),
        fans: FanCounts { total: u64_of(&fans, "total"), today: u64_of(&fans, "today"), d7: u64_of(&fans, "d7"), d30: u64_of(&fans, "d30") },
        new_players: NewPlayerCounts { total: u64_of(&players, "total"), d7: u64_of(&players, "d7"), d30: u64_of(&players, "d30") },
        pets: PetStats {
            pet,
            codes: u64_of(&pets, "codes"),
            active_codes: u64_of(&pets, "activeCodes"),
            redemptions: u64_of(&pets, "redemptions"),
            month: PetMonth {
                month: str_of(&month, "month").filter(|m| m.len() == 7 && chrono::NaiveDate::parse_from_str(&format!("{m}-01"), "%Y-%m-%d").is_ok()),
                used: u64_of(&month, "used"),
                limit: u64_of(&month, "limit"),
                redemptions: u64_of(&month, "redemptions"),
            },
        },
        activity: FanActivity {
            online_7d: u64_of(&activity, "online7d"),
            online_30d: u64_of(&activity, "online30d"),
            played_7d: u64_of(&activity, "played7d"),
            played_30d: u64_of(&activity, "played30d"),
        },
    })
}

fn clean_list(raw: &Value) -> Option<CreatorStatsList> {
    let creators: Vec<CreatorStatRow> =
        raw.get("creators")?.as_array()?.iter().take(MAX_ROWS).filter_map(clean_row).collect();
    let t = obj(raw, "totals");
    Some(CreatorStatsList {
        generated_at: time(str_of(raw, "generatedAt")),
        definitions: clean_definitions(raw.get("definitions")),
        totals: StatsTotals {
            creators: opt_u64(&t, "creators").unwrap_or(creators.len() as u64),
            fans: u64_of(&t, "fans"),
            fans_7d: u64_of(&t, "fans7d"),
            fans_30d: u64_of(&t, "fans30d"),
            new_players: u64_of(&t, "newPlayers"),
            new_players_30d: u64_of(&t, "newPlayers30d"),
            pet_redemptions: u64_of(&t, "petRedemptions"),
            online_7d: u64_of(&t, "online7d"),
            online_30d: u64_of(&t, "online30d"),
        },
        creators,
    })
}

fn clean_detail(raw: &Value) -> Option<CreatorStatsDetail> {
    let creator = clean_row(raw.get("creator")?)?;
    let cape = obj(raw, "fanCape");
    let holders = u64_of(&cape, "holders");
    let mut series: Vec<SeriesDay> = raw
        .get("series")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .take(*SERIES_DAYS.end() as usize + 1)
        .filter_map(|d| {
            Some(SeriesDay {
                day: day_of(d.get("day"))?,
                new_fans: u64_of(d, "newFans"),
                new_players: u64_of(d, "newPlayers"),
                redemptions: u64_of(d, "redemptions"),
                pet_redemptions: u64_of(d, "petRedemptions"),
                active_fans: opt_u64(d, "activeFans"),
                playing_fans: opt_u64(d, "playingFans"),
            })
        })
        .collect();
    series.sort_by(|a, b| a.day.cmp(&b.day));
    series.dedup_by(|a, b| a.day == b.day);
    Some(CreatorStatsDetail {
        generated_at: time(str_of(raw, "generatedAt")),
        definitions: clean_definitions(raw.get("definitions")),
        creator,
        // Aktuelle Fans mit Umhang können nie mehr sein als alle Besitzer.
        fan_cape: FanCapeShare { holders, fan_holders: u64_of(&cape, "fanHolders").min(holders) },
        series,
    })
}

// --- Anfragen ----------------------------------------------------------------------------

impl Launcher {
    /// Rangliste aller Creator (`GET /v1/admin/creators/stats`).
    pub async fn admin_creator_stats(&self, query: &CreatorStatsQuery) -> Result<CreatorStatsList> {
        let raw: Value = self.trs_get(Req::get(query.path()?)).await?;
        clean_list(&raw).ok_or_else(super::bad_response)
    }

    /// Kennzahlen + Tagesreihen eines Creators (`GET /v1/admin/creators/{uuid}/stats?days=`).
    pub async fn admin_creator_stats_detail(&self, uuid: &str, days: Option<u32>) -> Result<CreatorStatsDetail> {
        let uuid = validate::uuid(uuid).ok_or_else(|| invalid(crate::msg!("trsOps.invalidPlayerId", "Ungültige Spieler-ID.")))?;
        let days = days.unwrap_or(SERIES_DAYS_DEFAULT).clamp(*SERIES_DAYS.start(), *SERIES_DAYS.end());
        let raw: Value = self.trs_get(Req::get(format!("/v1/admin/creators/{uuid}/stats?days={days}"))).await?;
        clean_detail(&raw).ok_or_else(super::bad_response)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn row(uuid: &str, name: &str) -> Value {
        json!({
            "uuid": uuid, "name": name, "tier": "partner", "state": "active", "since": "2026-10-03T00:00:00.000Z", "mainCode": "pixel",
            "fans": { "total": 842, "today": 3, "d7": 51, "d30": 190 },
            "newPlayers": { "total": 77, "d7": 4, "d30": 20 },
            "pets": { "pet": { "cosmeticId": "penguin", "name": "Pinguin" }, "codes": 12, "activeCodes": 3, "redemptions": 33,
                      "month": { "month": "2026-10", "used": 40, "limit": 50, "redemptions": 9 } },
            "activity": { "online7d": 400, "online30d": 610, "played7d": 300, "played30d": 500 }
        })
    }

    #[test]
    fn query_path_is_checked() {
        assert_eq!(CreatorStatsQuery::default().path().unwrap(), "/v1/admin/creators/stats");
        let q = CreatorStatsQuery {
            q: Some(" Pix el ".into()),
            tier: Some("top".into()),
            state: Some("all".into()),
            sort: Some("newPlayers30d".into()),
            order: Some("asc".into()),
            limit: Some(9999),
        };
        assert_eq!(q.path().unwrap(), "/v1/admin/creators/stats?q=Pix%20el&tier=top&state=all&sort=newPlayers30d&order=asc&limit=500");
        assert!(CreatorStatsQuery { tier: Some("gold".into()), ..Default::default() }.path().is_err());
        assert!(CreatorStatsQuery { state: Some("gone".into()), ..Default::default() }.path().is_err());
        assert!(CreatorStatsQuery { sort: Some("drop table".into()), ..Default::default() }.path().is_err());
        assert!(CreatorStatsQuery { order: Some("up".into()), ..Default::default() }.path().is_err());
        assert_eq!(CreatorStatsQuery { tier: Some("all".into()), ..Default::default() }.path().unwrap(), "/v1/admin/creators/stats");
    }

    #[test]
    fn list_is_cleaned() {
        let raw = json!({
            "generatedAt": "2026-10-25T12:00:00.000Z",
            "definitions": { "newPlayerWindowHours": 24, "activitySince": "2026-10-25" },
            "totals": { "creators": 2, "fans": 845, "fans7d": 51, "newPlayers": 77, "online7d": 400 },
            "creators": [
                row("B0B0B0B0-B0B0-B0B0-B0B0-B0B0B0B0B0B0", "Pixel\u{202E}maus"),
                { "uuid": "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0", "name": "Neu", "tier": "starter", "state": "revoked" },
                { "uuid": "kaputt", "name": "x", "tier": "top", "state": "active" },
                { "uuid": "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0", "name": "x", "tier": "gold", "state": "active" }
            ]
        });
        let list = clean_list(&raw).unwrap();
        assert_eq!(list.creators.len(), 2, "kaputte Zeilen fallen weg");
        let a = &list.creators[0];
        assert_eq!(a.uuid, "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0");
        assert_eq!(a.name, "Pixelmaus");
        assert_eq!(a.main_code.as_deref(), Some("PIXEL"));
        assert_eq!(a.fans, FanCounts { total: 842, today: 3, d7: 51, d30: 190 });
        assert_eq!(a.pets.month.limit, 50);
        assert_eq!(a.pets.pet.as_ref().unwrap().cosmetic_id, "penguin");
        assert_eq!(a.activity.played_30d, 500);
        let b = &list.creators[1];
        assert_eq!((b.fans.total, b.pets.pet.is_none(), b.pets.month.month.is_none()), (0, true, true), "fehlende Blöcke = 0");
        assert_eq!(list.totals.fans, 845);
        assert_eq!(list.definitions.activity_since.as_deref(), Some("2026-10-25"));
        let view = serde_json::to_value(&list).unwrap();
        assert_eq!(view["creators"][0]["activity"]["online7d"], 400, "Namen wie die API");
        assert_eq!(view["totals"]["newPlayers30d"], 0);
        assert!(clean_list(&json!({ "nope": true })).is_none());
    }

    #[test]
    fn detail_is_cleaned() {
        let raw = json!({
            "definitions": { "newPlayerWindowHours": 0, "activitySince": "kaputt" },
            "creator": row("b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "Pixelmaus"),
            "fanCape": { "holders": 6, "fanHolders": 9 },
            "series": [
                { "day": "2026-10-25", "newFans": 2, "newPlayers": 1, "redemptions": 2, "petRedemptions": 0, "activeFans": 5, "playingFans": 3 },
                { "day": "2026-10-24", "newFans": 1, "activeFans": null, "playingFans": null },
                { "day": "2026-10-24", "newFans": 9 },
                { "day": "gestern", "newFans": 1 }
            ]
        });
        let d = clean_detail(&raw).unwrap();
        assert_eq!(d.definitions.new_player_window_hours, 1);
        assert!(d.definitions.activity_since.is_none());
        assert_eq!(d.fan_cape, FanCapeShare { holders: 6, fan_holders: 6 }, "nie mehr als alle Besitzer");
        assert_eq!(d.series.iter().map(|s| s.day.as_str()).collect::<Vec<_>>(), ["2026-10-24", "2026-10-25"], "sortiert, doppelte/kaputte weg");
        assert_eq!(d.series[0].active_fans, None, "null = nicht erfasst");
        assert_eq!(d.series[1].active_fans, Some(5));
    }
}
