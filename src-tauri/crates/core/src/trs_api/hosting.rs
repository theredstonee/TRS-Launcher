//! Welt-Hosting über die TRS API (Vertrag: `api/API.md` §21) – die Seite des
//! Launchers.
//!
//! Gehostet wird im Spiel (TRS Client). Der Launcher zeigt nur an und hilft
//! beim Beitreten: offene Welten von Freunden, Weltkarten im Chat, Einladungen
//! und Anfragen (`POST /v1/hosting/join`). Ist man drin, startet er die
//! passende Instanz und reicht dem Spiel über den TRS-Link **nur** Raum-ID und
//! Code weiter ([`HostedWorld`]); die Verbindung (P2P/Relay) baut die Mod
//! selbst mit ihrer eigenen Anmeldung auf.
//!
//! **Nie ans Webview oder ans Spiel:** Relay-Token, STUN-Liste und Signale.
//! Die `join`-Antwort trägt sie mit – hier werden sie verworfen.

use serde::{Deserialize, Serialize};
use serde_json::json;

use super::chat::one_line;
use super::types::{UserRef, clean_user};
use super::{Req, validate};
use crate::{Error, Launcher, Result};

/// Zeichen eines Beitrittscodes (ohne `0/O` und `1/I/L`).
pub const CODE_ALPHABET: &str = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
/// Länge eines Beitrittscodes.
pub const CODE_LEN: usize = 6;
/// Loader, die ein Raum haben kann.
pub const LOADERS: [&str; 5] = ["vanilla", "fabric", "forge", "neoforge", "quilt"];
const GAME_MODES: [&str; 4] = ["survival", "creative", "adventure", "spectator"];
const MEMBER_STATES: [&str; 4] = ["invited", "requested", "accepted", "banned"];
const MY_STATES: [&str; 3] = ["invited", "requested", "accepted"];
/// Gründe in `hosting_room_closed`.
pub const CLOSE_REASONS: [&str; 6] = ["closed", "expired", "replaced", "host_unavailable", "hidden", "left"];
/// Höchstens so viele Spieler je Welt (inklusive Host).
pub const MAX_PLAYERS: u8 = 10;

// --- Prüfen ---------------------------------------------------------------------------

/// `^h[0-9a-f]{20}$`
pub fn room_id(input: &str) -> bool {
    input.len() == 21
        && input.starts_with('h')
        && input[1..].bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// Beitrittscode aus einer Eingabe: Groß-/Kleinschreibung egal, Leerzeichen und
/// Bindestriche werden ignoriert. `None` = kein gültiger Code.
pub fn join_code(input: &str) -> Option<String> {
    if input.len() > 32 {
        return None;
    }
    let code: String = input.chars().filter(|c| !matches!(c, ' ' | '-')).map(|c| c.to_ascii_uppercase()).collect();
    (code.len() == CODE_LEN && code.chars().all(|c| CODE_ALPHABET.contains(c))).then_some(code)
}

/// `^[0-9A-Za-z][0-9A-Za-z._+ -]{0,31}$`
pub fn mc_version(input: &str) -> bool {
    let bytes = input.as_bytes();
    (1..=32).contains(&bytes.len())
        && bytes[0].is_ascii_alphanumeric()
        && bytes.iter().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'+' | b' ' | b'-'))
}

pub fn loader(input: &str) -> bool {
    LOADERS.contains(&input)
}

fn world_name(input: &str) -> Option<String> {
    let name = one_line(input, 32);
    (!name.is_empty()).then_some(name)
}

fn time(value: Option<String>) -> Option<String> {
    value.map(|t| validate::text(&t, 40)).filter(|t| !t.is_empty())
}

fn invalid_room() -> Error {
    Error::validation(crate::msg!("hosting.invalidRoom", "Ungültige Welt."))
}

fn invalid_code() -> Error {
    Error::validation(crate::msg!("hosting.invalidCode", "Der Beitrittscode hat 6 Zeichen, z. B. K7Q-M2X."))
}

pub(crate) fn room_arg(id: &str) -> Result<&str> {
    if room_id(id) { Ok(id) } else { Err(invalid_room()) }
}

// --- Ansichten ------------------------------------------------------------------------

/// Mitglied einer eigenen Welt (nur der Host sieht die Liste).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HostingMember {
    pub uuid: String,
    pub name: String,
    /// `invited`, `requested`, `accepted` oder `banned`.
    pub state: String,
    pub since: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiMember {
    #[serde(default)]
    uuid: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    state: String,
    #[serde(default)]
    since: Option<String>,
}

/// Eine gehostete Welt (§21.1): `HostRoomView` für den Host (mit `code`,
/// `visibility`, `expiresAt`, `members`) bzw. `RoomView` für alle anderen (mit
/// `myState`). Gesäubert, ohne Verbindungsdaten.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HostingRoom {
    pub id: String,
    /// Nur für den Host (bzw. aus einer Weltkarte).
    pub code: Option<String>,
    pub name: String,
    pub host: UserRef,
    pub mc_version: String,
    pub loader: String,
    pub max_players: u8,
    pub game_mode: String,
    pub pvp: bool,
    pub cheats: bool,
    pub open: bool,
    /// `friends` oder `invited` (nur Host).
    pub visibility: Option<String>,
    /// Spieler gerade in der Welt, inklusive Host.
    pub players: u8,
    pub created_at: Option<String>,
    pub expires_at: Option<String>,
    /// Nur Host.
    pub members: Vec<HostingMember>,
    /// Eigener Stand: `invited`, `requested`, `accepted` oder `null` (nur als Freund sichtbar).
    pub my_state: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiRoom {
    id: String,
    #[serde(default)]
    code: Option<String>,
    #[serde(default)]
    name: String,
    #[serde(default)]
    host: Option<UserRef>,
    #[serde(default)]
    mc_version: String,
    #[serde(default)]
    loader: String,
    #[serde(default)]
    max_players: u32,
    #[serde(default)]
    game_mode: String,
    #[serde(default)]
    pvp: bool,
    #[serde(default)]
    cheats: bool,
    #[serde(default)]
    open: bool,
    #[serde(default)]
    visibility: Option<String>,
    #[serde(default)]
    players: u32,
    #[serde(default)]
    created_at: Option<String>,
    #[serde(default)]
    expires_at: Option<String>,
    #[serde(default)]
    members: Vec<ApiMember>,
    #[serde(default)]
    my_state: Option<String>,
}

impl ApiRoom {
    /// `None` bei kaputten Pflichtfeldern (ID, Host, Version, Loader).
    pub(crate) fn cleaned(self) -> Option<HostingRoom> {
        if !room_id(&self.id) || !mc_version(&self.mc_version) || !loader(&self.loader) {
            return None;
        }
        let host = self.host.and_then(clean_user)?;
        let max_players = (self.max_players.clamp(2, u32::from(MAX_PLAYERS))) as u8;
        let members = self
            .members
            .into_iter()
            .filter_map(|m| {
                MEMBER_STATES.contains(&m.state.as_str()).then_some(())?;
                Some(HostingMember {
                    uuid: validate::uuid(&m.uuid)?,
                    name: validate::display_name(&m.name),
                    state: m.state,
                    since: time(m.since),
                })
            })
            .take(600)
            .collect();
        Some(HostingRoom {
            code: self.code.as_deref().and_then(join_code),
            name: world_name(&self.name).unwrap_or_else(|| format!("{}'s world", host.name)),
            host,
            mc_version: self.mc_version,
            loader: self.loader,
            max_players,
            game_mode: if GAME_MODES.contains(&self.game_mode.as_str()) { self.game_mode } else { "survival".into() },
            pvp: self.pvp,
            cheats: self.cheats,
            open: self.open,
            visibility: self.visibility.filter(|v| matches!(v.as_str(), "friends" | "invited")),
            players: self.players.clamp(1, u32::from(MAX_PLAYERS)) as u8,
            created_at: time(self.created_at),
            expires_at: time(self.expires_at),
            members,
            my_state: self.my_state.filter(|s| MY_STATES.contains(&s.as_str())),
            id: self.id,
        })
    }
}

/// Weltkarte einer Chat-Nachricht (`MessageView.world`, §21.8) – ein Schnappschuss.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatWorld {
    pub room_id: String,
    pub code: String,
    pub name: String,
    pub mc_version: String,
    pub loader: String,
    pub host: UserRef,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiWorld {
    #[serde(default)]
    room_id: String,
    #[serde(default)]
    code: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    mc_version: String,
    #[serde(default)]
    loader: String,
    #[serde(default)]
    host: Option<UserRef>,
}

impl ApiWorld {
    pub(crate) fn cleaned(self) -> Option<ChatWorld> {
        if !room_id(&self.room_id) || !mc_version(&self.mc_version) || !loader(&self.loader) {
            return None;
        }
        let host = self.host.and_then(clean_user)?;
        Some(ChatWorld {
            room_id: self.room_id,
            code: join_code(&self.code)?,
            name: world_name(&self.name).unwrap_or_else(|| format!("{}'s world", host.name)),
            mc_version: self.mc_version,
            loader: self.loader,
            host,
        })
    }
}

/// Was nach dem Beitreten bekannt ist (ohne Relay-Token/STUN – die holt das Spiel selbst).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HostingJoinResult {
    /// `accepted` (drin) oder `requested` (Anfrage wartet auf den Host).
    pub status: String,
    pub room: HostingRoom,
}

#[derive(Debug, Deserialize)]
struct ApiJoin {
    #[serde(default)]
    status: String,
    room: ApiRoom,
}

#[derive(Debug, Deserialize)]
struct ApiRooms {
    #[serde(default)]
    rooms: Vec<ApiRoom>,
}

#[derive(Debug, Deserialize)]
struct ApiRoomEnvelope {
    room: ApiRoom,
}

/// Wohin „Beitreten“ geht: Raum-ID (Einladung, Freundesliste) oder Code (Karte, Eingabe).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct JoinTarget {
    #[serde(default)]
    pub room_id: Option<String>,
    #[serde(default)]
    pub code: Option<String>,
}

impl JoinTarget {
    fn body(&self) -> Result<serde_json::Value> {
        if let Some(id) = self.room_id.as_deref().filter(|i| !i.is_empty()) {
            return Ok(json!({ "roomId": room_arg(id)? }));
        }
        let code = self.code.as_deref().and_then(join_code).ok_or_else(invalid_code)?;
        Ok(json!({ "code": code }))
    }
}

/// Die Anweisung „tritt dieser Welt bei“ an das Spiel (TRS-Link `hostingJoin`,
/// `docs/hosting-link.md`). Enthält **keine** Tokens – nur, was die Mod braucht,
/// um selbst `POST /v1/hosting/rooms/{roomId}/connect` aufzurufen.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HostedWorld {
    pub room_id: String,
    #[serde(default)]
    pub code: Option<String>,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub host: Option<UserRef>,
    pub mc_version: String,
    pub loader: String,
}

impl HostedWorld {
    /// Prüft und säubert (vom Webview kommend). `None` = unbrauchbar.
    pub fn checked(self) -> Option<Self> {
        if !room_id(&self.room_id) || !mc_version(&self.mc_version) || !loader(&self.loader) {
            return None;
        }
        let code = match self.code.as_deref().filter(|c| !c.is_empty()) {
            Some(c) => Some(join_code(c)?),
            None => None,
        };
        Some(Self {
            code,
            name: world_name(&self.name).unwrap_or_default(),
            host: self.host.and_then(clean_user),
            ..self
        })
    }

    /// Wie [`Self::checked`], aber mit Fehlermeldung.
    pub fn validated(self) -> Result<Self> {
        self.checked().ok_or_else(invalid_room)
    }
}

/// Passt eine Instanz (Loader, wie der Nutzer ihn gewählt hat) zu einer Welt?
/// Vanilla-Instanzen laufen mit der TRS-Optimierung unter der Haube als Fabric –
/// Vanilla- und Fabric-Welten gelten deshalb untereinander als passend.
pub fn loaders_compatible(room_loader: &str, instance_loader: &str) -> bool {
    room_loader == instance_loader
        || (matches!(room_loader, "vanilla" | "fabric") && matches!(instance_loader, "vanilla" | "fabric"))
}

/// Fehler der Hosting-Endpunkte, deren Code anderswo etwas anderes heißt.
fn hosting_error(e: Error) -> Error {
    match e {
        Error::TrsApi { kind: "trs_api", code, .. } if code == "too_many_requests" => Error::TrsApi {
            kind: "trs_api",
            msg: crate::msg!(
                "trsApi.world_too_many_requests",
                "Bei dieser Welt warten schon zu viele Anfragen – versuch es später noch einmal."
            ),
            code,
        },
        Error::TrsApi { kind: "trs_api", code, .. } if code == "not_found" => Error::TrsApi {
            kind: "trs_api",
            msg: crate::msg!("trsApi.room_not_found", "Diese Welt gibt es nicht (mehr) oder du kannst sie nicht sehen."),
            code,
        },
        other => other,
    }
}

// --- Operationen ----------------------------------------------------------------------

impl Launcher {
    /// Offene Welten der Freunde plus alle, zu denen man eingeladen ist, angefragt hat
    /// oder schon zugelassen ist (§21.3). Neueste zuerst.
    pub async fn hosting_friends_rooms(&self) -> Result<Vec<HostingRoom>> {
        let rooms: ApiRooms = self.trs_get(Req::get("/v1/hosting/friends-rooms")).await.map_err(hosting_error)?;
        Ok(clean_rooms(rooms.rooms))
    }

    /// Die eigene offene Welt (0 oder 1) – z. B. wenn man gerade im Spiel hostet.
    pub async fn hosting_my_rooms(&self) -> Result<Vec<HostingRoom>> {
        let rooms: ApiRooms = self.trs_get(Req::get("/v1/hosting/rooms/mine")).await.map_err(hosting_error)?;
        Ok(clean_rooms(rooms.rooms))
    }

    /// Aktueller Stand einer Welt (Weltkarte). `None` = geschlossen oder nicht sichtbar.
    pub async fn hosting_room(&self, id: &str) -> Result<Option<HostingRoom>> {
        let id = room_arg(id)?;
        match self.trs_get::<ApiRoomEnvelope>(Req::get(format!("/v1/hosting/rooms/{id}"))).await {
            Ok(envelope) => Ok(envelope.room.cleaned().filter(|r| r.id == id)),
            Err(Error::TrsApi { code, .. }) if code == "room_not_found" => Ok(None),
            Err(e) => Err(hosting_error(e)),
        }
    }

    /// Beitreten (Einladung → sofort drin) oder anfragen (Host muss zustimmen, `requested`).
    pub async fn hosting_join(&self, target: &JoinTarget) -> Result<HostingJoinResult> {
        let body = target.body()?;
        let result: ApiJoin = self.trs_get(Req::post("/v1/hosting/join", body)).await.map_err(hosting_error)?;
        let status = match result.status.as_str() {
            "accepted" | "requested" => result.status,
            _ => return Err(super::bad_response()),
        };
        let room = result.room.cleaned().ok_or_else(super::bad_response)?;
        if let Some(id) = target.room_id.as_deref().filter(|i| !i.is_empty())
            && room.id != id
        {
            return Err(super::bad_response());
        }
        Ok(HostingJoinResult { status, room })
    }

    /// Welt verlassen, Anfrage zurückziehen oder Einladung ablehnen.
    pub async fn hosting_leave(&self, id: &str) -> Result<()> {
        let id = room_arg(id)?;
        match self.trs_do(Req::post_empty(format!("/v1/hosting/rooms/{id}/leave"))).await {
            // Schon weg – Ziel erreicht.
            Err(Error::TrsApi { code, .. }) if code == "room_not_found" => Ok(()),
            other => other.map_err(hosting_error),
        }
    }
}

fn clean_rooms(rooms: Vec<ApiRoom>) -> Vec<HostingRoom> {
    let mut out: Vec<HostingRoom> = Vec::new();
    for room in rooms.into_iter().filter_map(ApiRoom::cleaned) {
        if !out.iter().any(|r| r.id == room.id) {
            out.push(room);
        }
        if out.len() >= 200 {
            break;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const HOST: &str = "75c1a6f3112240abbdb57b9d21c64232";

    pub(crate) fn room_json() -> serde_json::Value {
        json!({
            "id": "h0123456789abcdef0123", "code": "k7q-m2x", "name": "Meine\u{202E} Welt\nzwei",
            "host": { "uuid": HOST, "name": "Theredstonee" }, "mcVersion": "1.21.11", "loader": "fabric",
            "maxPlayers": 40, "gameMode": "hardcore", "pvp": true, "cheats": false, "open": true,
            "visibility": "friends", "players": 0, "createdAt": "2026-09-26T10:00:00Z", "expiresAt": "2026-09-26T10:01:30Z",
            "members": [
                { "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "name": "Bob", "state": "accepted", "since": "…" },
                { "uuid": "nope", "name": "X", "state": "accepted" },
                { "uuid": "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0", "name": "Carl", "state": "owner" }
            ],
            "myState": "weird"
        })
    }

    #[test]
    fn rooms_are_cleaned() {
        let r = serde_json::from_value::<ApiRoom>(room_json()).unwrap().cleaned().unwrap();
        assert_eq!(r.code.as_deref(), Some("K7QM2X"));
        assert_eq!(r.name, "Meine Welt zwei");
        assert_eq!(r.max_players, 10, "höchstens 10");
        assert_eq!(r.players, 1, "mindestens der Host");
        assert_eq!(r.game_mode, "survival", "unbekannter Modus");
        assert_eq!(r.members.len(), 1, "kaputte UUID und unbekannter Stand fallen weg");
        assert!(r.my_state.is_none());

        let mut bad = room_json();
        bad["id"] = json!("h0123");
        assert!(serde_json::from_value::<ApiRoom>(bad).unwrap().cleaned().is_none());
        let mut bad = room_json();
        bad["loader"] = json!("rift");
        assert!(serde_json::from_value::<ApiRoom>(bad).unwrap().cleaned().is_none());
        let mut bad = room_json();
        bad["host"] = json!(null);
        assert!(serde_json::from_value::<ApiRoom>(bad).unwrap().cleaned().is_none());
    }

    #[test]
    fn codes_and_ids() {
        assert_eq!(join_code("k7q-m2x").as_deref(), Some("K7QM2X"));
        assert_eq!(join_code(" K7Q M2X ").as_deref(), Some("K7QM2X"));
        assert!(join_code("K7QM2").is_none() && join_code("K7QM20").is_none(), "0 ist nicht im Alphabet");
        assert!(join_code("K7QMIX").is_none() && join_code("ÄÖÜABC").is_none());
        assert!(room_id("h0123456789abcdef0123") && !room_id("h0123456789ABCDEF0123") && !room_id("c0123456789abcdef0123"));
        assert!(mc_version("1.21.11") && mc_version("25w14a") && mc_version("1.20.1-pre1") && !mc_version(".1") && !mc_version("1.21/../x"));
        assert!(loaders_compatible("fabric", "vanilla") && loaders_compatible("forge", "forge"));
        assert!(!loaders_compatible("forge", "fabric") && !loaders_compatible("neoforge", "vanilla"));
    }

    #[test]
    fn world_cards_need_a_code() {
        let card = json!({ "roomId": "h0123456789abcdef0123", "code": "K7QM2X", "name": "", "mcVersion": "1.21.11",
                           "loader": "fabric", "host": { "uuid": HOST, "name": "Theredstonee" } });
        let w = serde_json::from_value::<ApiWorld>(card.clone()).unwrap().cleaned().unwrap();
        assert_eq!(w.name, "Theredstonee's world");
        let mut bad = card;
        bad["code"] = json!("");
        assert!(serde_json::from_value::<ApiWorld>(bad).unwrap().cleaned().is_none());
    }

    #[test]
    fn join_targets_and_hosted_worlds() {
        let by_id = JoinTarget { room_id: Some("h0123456789abcdef0123".into()), code: None };
        assert_eq!(by_id.body().unwrap(), json!({ "roomId": "h0123456789abcdef0123" }));
        let by_code = JoinTarget { room_id: None, code: Some("k7q-m2x".into()) };
        assert_eq!(by_code.body().unwrap(), json!({ "code": "K7QM2X" }));
        assert_eq!(JoinTarget::default().body().unwrap_err().message_code(), "hosting.invalidCode");
        assert_eq!(
            JoinTarget { room_id: Some("../x".into()), code: None }.body().unwrap_err().message_code(),
            "hosting.invalidRoom"
        );

        let world = HostedWorld {
            room_id: "h0123456789abcdef0123".into(),
            code: Some("k7q-m2x".into()),
            name: "Welt\u{0007}".into(),
            host: Some(UserRef { uuid: HOST.to_uppercase(), name: "T".into() }),
            mc_version: "1.21.11".into(),
            loader: "fabric".into(),
        };
        let w = world.clone().checked().unwrap();
        assert_eq!(w.code.as_deref(), Some("K7QM2X"));
        assert_eq!(w.name, "Welt");
        assert_eq!(w.host.unwrap().uuid, HOST);
        assert!(HostedWorld { code: Some("nope".into()), ..world.clone() }.checked().is_none());
        assert!(HostedWorld { loader: "rift".into(), ..world }.checked().is_none());
    }
}
