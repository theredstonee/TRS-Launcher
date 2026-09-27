//! Eigene Team-Zugehörigkeit: Rollen, Rechte, Rang und Straf-Grenzen
//! (`MyTeamView`, Vertrag: `api/API.md` §24.2).
//!
//! Die API schickt `team` in `GET /v1/me` (neben dem alten `admin`/`role`).
//! Der Kern säubert die Ansicht streng: nur bekannte Rechte und Strafarten,
//! Rang 0–1000, Farben `#rrggbb`, höchstens 16 Rollen. Ältere Server ohne
//! `team` bekommen aus `role` die Rechte der früheren Standardrollen – so
//! sieht der Team-Bereich gegen alte und neue API dasselbe.
//!
//! Die Oberfläche blendet damit nur aus; geprüft wird jede Anfrage vom Server.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::sanctions::SANCTION_KINDS;
use super::validate;

/// Alle Rechte der API (§24.2). Unbekannte Rechte einer neueren API fallen weg.
pub const PERMISSIONS: [&str; 29] = [
    "dashboard.view",
    "stats.view",
    "audit.view",
    "reports.view",
    "reports.content",
    "reports.handle",
    "sanctions.warn",
    "sanctions.mute",
    "sanctions.social",
    "sanctions.upload",
    "sanctions.hosting",
    "sanctions.ban",
    "sanctions.permanent",
    "sanctions.lift",
    "appeals.handle",
    "players.view",
    "players.notes",
    "uploads.review",
    "uploads.delete",
    "items.grant",
    "worlds.view",
    "worlds.close",
    "codes",
    "wordfilter",
    "roles.manage",
    "applications.view",
    "applications.review",
    "applications.manage",
    "applications.decide",
];

pub const OWNER_RANK: u32 = 1000;
pub const ADMIN_RANK: u32 = 900;
pub const MODERATOR_RANK: u32 = 500;
/// Längste eigene Strafdauer (§22.2: 5 256 000 Minuten ≈ 10 Jahre).
const MAX_MINUTES: u64 = 5_256_000;
const MAX_ROLES: usize = 16;

/// Rechte der früheren Rolle „moderator“ (= Standardrolle `moderator`).
const MODERATOR_PERMISSIONS: [&str; 17] = [
    "dashboard.view",
    "audit.view",
    "reports.view",
    "reports.content",
    "reports.handle",
    "sanctions.warn",
    "sanctions.mute",
    "sanctions.social",
    "sanctions.upload",
    "sanctions.hosting",
    "sanctions.lift",
    "appeals.handle",
    "players.view",
    "players.notes",
    "uploads.review",
    "worlds.view",
    "worlds.close",
];

pub fn is_permission(p: &str) -> bool {
    PERMISSIONS.contains(&p)
}

/// Kurzform einer Rolle (`RoleRef`). Standardrollen haben `name: None` – die
/// Oberfläche übersetzt dann die `id`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TeamRoleRef {
    pub id: String,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub color: String,
    #[serde(default)]
    pub builtin: bool,
}

/// Grenzen für Strafen (`limits`): erlaubte Arten, Höchstdauer (`None` =
/// unbegrenzt), Höchstdauer für Verwarnungen, dauerhafte Strafen.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TeamLimits {
    #[serde(default)]
    pub kinds: Vec<String>,
    #[serde(default)]
    pub max_minutes: Option<u64>,
    #[serde(default)]
    pub max_warn_minutes: Option<u64>,
    #[serde(default)]
    pub permanent: bool,
}

/// `MyTeamView` – nur für Team-Mitglieder, sonst `None`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MyTeam {
    #[serde(default)]
    pub owner: bool,
    #[serde(default)]
    pub rank: u32,
    #[serde(default)]
    pub roles: Vec<TeamRoleRef>,
    #[serde(default)]
    pub permissions: Vec<String>,
    pub limits: TeamLimits,
}

fn color(input: &str) -> String {
    let ok = input.len() == 7 && input.starts_with('#') && input[1..].bytes().all(|b| b.is_ascii_hexdigit());
    if ok { input.to_ascii_lowercase() } else { "#9ca3af".to_owned() }
}

fn role_id(input: &str) -> bool {
    (1..=32).contains(&input.len())
        && input.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-')
}

impl TeamRoleRef {
    fn cleaned(self) -> Option<Self> {
        if !role_id(&self.id) {
            return None;
        }
        Some(Self {
            name: self.name.map(|n| validate::text(&n, 32)).filter(|n| !n.is_empty()),
            color: color(&self.color),
            ..self
        })
    }
}

impl TeamLimits {
    fn cleaned(self) -> Self {
        let mut kinds: Vec<String> = Vec::new();
        for k in self.kinds {
            if SANCTION_KINDS.contains(&k.as_str()) && !kinds.contains(&k) {
                kinds.push(k);
            }
        }
        Self {
            kinds,
            max_minutes: self.max_minutes.map(|m| m.min(MAX_MINUTES)),
            max_warn_minutes: self.max_warn_minutes.map(|m| m.min(MAX_MINUTES)),
            permanent: self.permanent,
        }
    }
}

impl MyTeam {
    /// Säubert die Ansicht. Ohne Rang (0) ist man kein Team-Mitglied → `None`.
    pub(crate) fn cleaned(self) -> Option<Self> {
        let rank = if self.owner { OWNER_RANK } else { self.rank.min(OWNER_RANK) };
        if rank == 0 {
            return None;
        }
        let mut permissions: Vec<String> = Vec::new();
        for p in self.permissions {
            if is_permission(&p) && !permissions.contains(&p) {
                permissions.push(p);
            }
        }
        // Reihenfolge wie in der API-Liste (stabil für Vergleiche und Tests).
        permissions.sort_by_key(|p| PERMISSIONS.iter().position(|x| x == p));
        Some(Self {
            owner: self.owner,
            rank,
            roles: self.roles.into_iter().filter_map(TeamRoleRef::cleaned).take(MAX_ROLES).collect(),
            permissions,
            limits: self.limits.cleaned(),
        })
    }

    pub(crate) fn from_value(value: &Value) -> Option<Self> {
        serde_json::from_value::<Self>(value.clone()).ok()?.cleaned()
    }

    /// Ersatz für ältere Server ohne `team`: Rechte der früheren Rolle.
    pub(crate) fn legacy(role: &str) -> Option<Self> {
        let (rank, permissions, limits): (u32, Vec<&str>, TeamLimits) = match role {
            "admin" => (
                ADMIN_RANK,
                PERMISSIONS.to_vec(),
                TeamLimits {
                    kinds: SANCTION_KINDS.iter().map(|k| (*k).to_owned()).collect(),
                    max_minutes: None,
                    max_warn_minutes: None,
                    permanent: true,
                },
            ),
            "moderator" => (
                MODERATOR_RANK,
                MODERATOR_PERMISSIONS.to_vec(),
                TeamLimits {
                    kinds: SANCTION_KINDS.iter().filter(|k| **k != "account_ban").map(|k| (*k).to_owned()).collect(),
                    max_minutes: Some(7 * 1440),
                    max_warn_minutes: Some(30 * 1440),
                    permanent: false,
                },
            ),
            _ => return None,
        };
        Some(Self {
            owner: false,
            rank,
            roles: vec![TeamRoleRef { id: role.to_owned(), name: None, color: String::new(), builtin: true }.cleaned()?],
            permissions: permissions.into_iter().map(str::to_owned).collect(),
            limits,
        })
    }

    pub fn can(&self, permission: &str) -> bool {
        self.owner || self.permissions.iter().any(|p| p == permission)
    }

    /// Rang-Regel (§24.2): Strafen von Mitgliedern mit HÖHEREM Rang bleiben
    /// tabu; gleicher Rang und eigene Strafen gehen immer.
    pub fn may_modify_sanction(&self, created_rank: u32, own: bool) -> bool {
        self.owner || own || created_rank <= self.rank
    }

    /// Alte Rolle für ältere Stellen (`admin` ab Rang 900, sonst `moderator`).
    pub fn legacy_role(&self) -> &'static str {
        if self.rank >= ADMIN_RANK { "admin" } else { "moderator" }
    }
}

/// Parameter `permission` aus einem `missing_permission`-Fehler (nur bekannte
/// Rechte; sonst bleibt die allgemeine Meldung).
pub(crate) fn error_permission(code: &str, error: Option<&Value>) -> Option<String> {
    if code != "missing_permission" {
        return None;
    }
    let p = error?.get("permission")?.as_str()?;
    is_permission(p).then(|| p.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn sample() -> Value {
        json!({
            "owner": false, "rank": 300,
            "roles": [
                { "id": "supporter", "name": null, "color": "#22C55E", "builtin": true },
                { "id": "event_team", "name": "Event\u{202E}-Team", "color": "red", "builtin": false },
                { "id": "Bad Id!", "name": "x", "color": "#000000", "builtin": false }
            ],
            "permissions": ["reports.view", "dashboard.view", "sanctions.warn", "nuke.all", "reports.view", "players.view"],
            "limits": { "kinds": ["warn", "nuke", "warn"], "maxMinutes": 1440, "maxWarnMinutes": 43200, "permanent": false }
        })
    }

    #[test]
    fn team_view_is_cleaned() {
        let t = MyTeam::from_value(&sample()).unwrap();
        assert_eq!(t.rank, 300);
        assert_eq!(t.permissions, ["dashboard.view", "reports.view", "sanctions.warn", "players.view"], "nur bekannte, ohne Doppelte, in API-Reihenfolge");
        assert_eq!(t.roles.len(), 2, "kaputte Rollen-ID fällt weg");
        assert_eq!(t.roles[0].color, "#22c55e");
        assert_eq!(t.roles[1].name.as_deref(), Some("Event-Team"), "Steuerzeichen raus");
        assert_eq!(t.roles[1].color, "#9ca3af", "ungültige Farbe → neutral");
        assert_eq!(t.limits.kinds, ["warn"]);
        assert_eq!(t.limits.max_minutes, Some(1440));
        assert!(t.can("reports.view") && !t.can("reports.content"));
        assert_eq!(t.legacy_role(), "moderator");
    }

    #[test]
    fn owner_and_rank_rules() {
        let mut v = sample();
        v["rank"] = json!(0);
        assert!(MyTeam::from_value(&v).is_none(), "Rang 0 = kein Team");
        v["rank"] = json!(5000);
        assert_eq!(MyTeam::from_value(&v).unwrap().rank, 1000, "gedeckelt");
        v["owner"] = json!(true);
        v["rank"] = json!(0);
        let owner = MyTeam::from_value(&v).unwrap();
        assert_eq!(owner.rank, OWNER_RANK);
        assert!(owner.can("roles.manage"), "Owner darf alles");
        assert!(owner.may_modify_sanction(1000, false));
        assert!(MyTeam::from_value(&json!({ "rank": "x" })).is_none());

        let t = MyTeam::from_value(&sample()).unwrap();
        assert!(t.may_modify_sanction(300, false), "gleicher Rang darf");
        assert!(t.may_modify_sanction(0, false), "System-Strafen");
        assert!(!t.may_modify_sanction(500, false), "höherer Rang nicht");
        assert!(t.may_modify_sanction(500, true), "eigene Strafe immer");
    }

    #[test]
    fn legacy_roles_get_the_old_rights() {
        let admin = MyTeam::legacy("admin").unwrap();
        assert_eq!((admin.rank, admin.permissions.len()), (ADMIN_RANK, PERMISSIONS.len()));
        assert!(admin.limits.permanent && admin.limits.max_minutes.is_none());
        let moderator = MyTeam::legacy("moderator").unwrap();
        assert!(moderator.can("reports.content") && !moderator.can("roles.manage") && !moderator.can("sanctions.ban"));
        assert!(!moderator.limits.kinds.iter().any(|k| k == "account_ban"));
        assert_eq!(moderator.limits.max_minutes, Some(10_080));
        assert!(MyTeam::legacy("owner").is_none());
    }

    #[test]
    fn missing_permission_param_is_checked() {
        let e = json!({ "code": "missing_permission", "permission": "reports.content" });
        assert_eq!(error_permission("missing_permission", Some(&e)).as_deref(), Some("reports.content"));
        let e = json!({ "code": "missing_permission", "permission": "<script>" });
        assert_eq!(error_permission("missing_permission", Some(&e)), None);
        assert_eq!(error_permission("forbidden", Some(&json!({ "permission": "codes" }))), None);
    }
}
