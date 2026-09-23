//! Datentypen der TRS API (Vertrag: `api/API.md`) und was davon ans Webview geht.
//!
//! Alles, was von der API kommt, wird hier noch einmal geprüft: UUIDs müssen
//! 32 Hex-Zeichen sein, Namen verlieren Steuerzeichen, Server-Adressen müssen
//! das enge Format des Launchers erfüllen. Unbekannte Enum-Werte (neuere
//! API-Version) landen in `Other` statt die ganze Antwort zu verwerfen.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use super::validate;

// --- Profil -------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Visibility {
    Friends,
    Nobody,
}

/// Datenschutz-Einstellungen des TRS-Kontos (liegen auf dem Server).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PrivacySettings {
    pub show_badge: bool,
    pub show_cape_to_others: bool,
    pub presence_visibility: Visibility,
    pub share_server: bool,
}

/// Teiländerung für `PATCH /v1/me` – nur gesetzte Felder werden gesendet.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SettingsPatch {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub show_badge: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub show_cape_to_others: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub presence_visibility: Option<Visibility>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub share_server: Option<bool>,
}

impl SettingsPatch {
    pub fn is_empty(&self) -> bool {
        self.show_badge.is_none()
            && self.show_cape_to_others.is_none()
            && self.presence_visibility.is_none()
            && self.share_server.is_none()
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiMe {
    pub uuid: String,
    pub name: String,
    #[serde(default)]
    pub admin: bool,
    #[serde(default)]
    pub created_at: Option<String>,
    pub settings: PrivacySettings,
    #[serde(default)]
    pub active_cape: Option<ApiCape>,
}

/// Eigenes TRS-Profil fürs Webview.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Me {
    pub uuid: String,
    pub name: String,
    pub admin: bool,
    pub created_at: Option<String>,
    pub settings: PrivacySettings,
    pub active_cape_id: Option<String>,
}

impl ApiMe {
    pub(crate) fn into_view(self) -> Option<Me> {
        Some(Me {
            uuid: validate::uuid(&self.uuid)?,
            name: validate::display_name(&self.name),
            admin: self.admin,
            created_at: self.created_at.map(|t| validate::text(&t, 40)),
            settings: self.settings,
            active_cape_id: self.active_cape.and_then(|c| validate::cape_id(&c.id).then_some(c.id)),
        })
    }
}

// --- Umhänge ------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum CapeKind {
    Builtin,
    Upload,
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Unlock {
    Free,
    Code,
    Admin,
    Owner,
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum CapeStatus {
    Approved,
    Pending,
    Rejected,
    #[serde(other)]
    Other,
}

/// `CapeView` der API (§5.1).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiCape {
    pub id: String,
    #[serde(default)]
    pub name: String,
    pub kind: CapeKind,
    pub unlock: Unlock,
    pub status: CapeStatus,
    pub url: String,
    pub width: u32,
    pub height: u32,
    pub scale: u32,
    #[serde(default)]
    pub frames: u32,
    #[serde(default)]
    pub frame_time_ms: Option<u32>,
}

impl ApiCape {
    /// Plausibel nach §5.1? Sonst wird der Umhang nicht angezeigt.
    pub(crate) fn is_valid(&self) -> bool {
        validate::cape_id(&self.id)
            && (1..=4).contains(&self.scale)
            && self.width == 64 * self.scale
            && self.height == 32 * self.scale
            && (1..=64).contains(&self.frames)
            && (self.frames == 1 || self.frame_time_ms.is_some_and(|t| (20..=10_000).contains(&t)))
    }
}

/// Eintrag aus `GET /v1/capes` (Katalog aus Sicht des Nutzers).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiCatalogCape {
    #[serde(flatten)]
    pub cape: ApiCape,
    #[serde(default)]
    pub owned: bool,
    #[serde(default)]
    pub active: bool,
    #[serde(default)]
    pub reject_reason: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ApiCatalog {
    pub capes: Vec<ApiCatalogCape>,
}

/// Umhang fürs Webview: Metadaten + Textur als Data-URL (ganzer Streifen,
/// alle Frames untereinander – die Seite schneidet den aktuellen Frame aus).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CapeItem {
    pub id: String,
    pub name: String,
    pub kind: CapeKind,
    pub unlock: Unlock,
    pub status: CapeStatus,
    pub width: u32,
    pub height: u32,
    pub scale: u32,
    pub frames: u32,
    pub frame_time_ms: Option<u32>,
    pub owned: bool,
    pub active: bool,
    pub reject_reason: Option<String>,
    pub texture: Option<String>,
}

impl CapeItem {
    pub(crate) fn from_api(cape: &ApiCape, owned: bool, active: bool, reject: Option<&str>, texture: Option<String>) -> Self {
        Self {
            id: cape.id.clone(),
            name: validate::cape_name(&cape.name, &cape.id),
            kind: cape.kind,
            unlock: cape.unlock,
            status: cape.status,
            width: cape.width,
            height: cape.height,
            scale: cape.scale,
            frames: cape.frames.max(1),
            frame_time_ms: if cape.frames > 1 { cape.frame_time_ms } else { None },
            owned,
            active,
            reject_reason: reject.map(|r| validate::text(r, 200)),
            texture,
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiActiveCape {
    pub active_cape: Option<ApiCape>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ApiCapeEnvelope {
    pub cape: ApiCape,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiRedeem {
    pub cape: ApiCape,
    #[serde(default)]
    pub already_owned: bool,
}

/// Ergebnis von „Code einlösen“.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RedeemResult {
    pub cape_id: String,
    pub name: String,
    pub already_owned: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ReportReason {
    Inappropriate,
    Copyright,
    Impersonation,
    Other,
}

// --- Spieler-Lookup -------------------------------------------------------------------

#[derive(Debug, Deserialize)]
pub(crate) struct ApiLookup {
    pub players: Vec<ApiLookupPlayer>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ApiLookupPlayer {
    pub uuid: String,
    #[serde(default)]
    pub badge: bool,
    #[serde(default)]
    pub cape: Option<ApiLookupCape>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiLookupCape {
    pub id: String,
    pub url: String,
    pub scale: u32,
    #[serde(default)]
    pub frames: u32,
    #[serde(default)]
    pub frame_time_ms: Option<u32>,
}

/// Abzeichen + Umhang eines anderen Spielers (Freundesliste).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayerCape {
    pub uuid: String,
    pub badge: bool,
    pub cape_id: Option<String>,
    /// Hochgeladener Umhang eines Spielers (kann gemeldet werden).
    pub upload: bool,
    pub scale: u32,
    pub frames: u32,
    pub frame_time_ms: Option<u32>,
    pub texture: Option<String>,
}

// --- Präsenz & Freunde ----------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GameInfo {
    pub version: String,
    pub loader: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PresenceView {
    pub state: String,
    #[serde(default)]
    pub game: Option<GameInfo>,
    #[serde(default)]
    pub updated_at: Option<String>,
}

impl PresenceView {
    fn cleaned(self) -> Option<Self> {
        let state = match self.state.as_str() {
            "online" | "in-game" => self.state,
            _ => return None,
        };
        let game = self.game.map(|g| GameInfo {
            version: validate::text(&g.version, 32),
            loader: match g.loader.as_str() {
                "vanilla" | "fabric" | "quilt" | "forge" | "neoforge" => g.loader,
                _ => "vanilla".into(),
            },
            // Nur Adressen, die der Launcher auch selbst zum Beitreten akzeptiert.
            server: g.server.filter(|s| crate::servers::parse_address(s).is_ok()),
        });
        Some(Self { state, game, updated_at: self.updated_at.map(|t| validate::text(&t, 40)) })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Friend {
    pub uuid: String,
    pub name: String,
    #[serde(default)]
    pub since: Option<String>,
    #[serde(default)]
    pub presence: Option<PresenceView>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FriendRequest {
    pub uuid: String,
    pub name: String,
    #[serde(default)]
    pub created_at: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct FriendRequests {
    #[serde(default)]
    pub incoming: Vec<FriendRequest>,
    #[serde(default)]
    pub outgoing: Vec<FriendRequest>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct FriendsView {
    pub friends: Vec<Friend>,
    #[serde(default)]
    pub requests: FriendRequests,
}

impl FriendsView {
    /// Einträge mit kaputter UUID fallen weg, Namen/Präsenz werden gesäubert.
    pub(crate) fn cleaned(self) -> Self {
        let request = |r: FriendRequest| {
            Some(FriendRequest {
                uuid: validate::uuid(&r.uuid)?,
                name: validate::display_name(&r.name),
                created_at: r.created_at.map(|t| validate::text(&t, 40)),
            })
        };
        Self {
            friends: self
                .friends
                .into_iter()
                .filter_map(|f| {
                    Some(Friend {
                        uuid: validate::uuid(&f.uuid)?,
                        name: validate::display_name(&f.name),
                        since: f.since.map(|t| validate::text(&t, 40)),
                        presence: f.presence.and_then(PresenceView::cleaned),
                    })
                })
                .collect(),
            requests: FriendRequests {
                incoming: self.requests.incoming.into_iter().filter_map(request).collect(),
                outgoing: self.requests.outgoing.into_iter().filter_map(request).collect(),
            },
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UserRef {
    pub uuid: String,
    pub name: String,
}

/// Antwort auf eine Freundschaftsanfrage: `sent` oder gleich `accepted`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FriendRequestResult {
    pub status: String,
    pub user: UserRef,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BlockedUser {
    pub uuid: String,
    pub name: String,
    #[serde(default)]
    pub since: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ApiBlocks {
    pub blocked: Vec<BlockedUser>,
}

pub(crate) fn clean_blocks(list: Vec<BlockedUser>) -> Vec<BlockedUser> {
    list.into_iter()
        .filter_map(|b| {
            Some(BlockedUser {
                uuid: validate::uuid(&b.uuid)?,
                name: validate::display_name(&b.name),
                since: b.since.map(|t| validate::text(&t, 40)),
            })
        })
        .collect()
}

pub(crate) fn clean_user(user: UserRef) -> Option<UserRef> {
    Some(UserRef { uuid: validate::uuid(&user.uuid)?, name: validate::display_name(&user.name) })
}

// --- Verwaltung -----------------------------------------------------------------------

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct UserStats {
    pub total: u64,
    pub banned: u64,
    pub active_last24h: u64,
    pub online: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct CapeStats {
    pub builtin: u64,
    pub approved: u64,
    pub pending: u64,
    pub rejected: u64,
    pub reported: u64,
    pub active_users: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct CodeStats {
    pub active: u64,
    pub redemptions: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AdminStats {
    pub users: UserStats,
    pub sessions: u64,
    pub capes: CapeStats,
    pub codes: CodeStats,
    pub friendships: u64,
    pub pending_friend_requests: u64,
    pub event_streams: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ReviewList {
    Pending,
    Approved,
    Rejected,
    Reported,
}

impl ReviewList {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Pending => "pending",
            Self::Approved => "approved",
            Self::Rejected => "rejected",
            Self::Reported => "reported",
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Reports {
    pub count: u64,
    pub reasons: BTreeMap<String, u64>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiAdminCape {
    #[serde(flatten)]
    pub cape: ApiCape,
    #[serde(default)]
    pub owner: Option<UserRef>,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub reviewed_at: Option<String>,
    #[serde(default)]
    pub reviewed_by: Option<String>,
    #[serde(default)]
    pub reject_reason: Option<String>,
    #[serde(default)]
    pub reports: Reports,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ApiAdminCapes {
    pub capes: Vec<ApiAdminCape>,
}

/// Hochgeladener Umhang in der Prüfliste (mit Vorschau).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdminCape {
    #[serde(flatten)]
    pub cape: CapeItem,
    pub owner: Option<UserRef>,
    pub created_at: Option<String>,
    pub reviewed_at: Option<String>,
    pub reviewed_by: Option<String>,
    pub reports: Reports,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CodeView {
    pub id: u64,
    #[serde(default)]
    pub hint: String,
    pub cape_id: String,
    pub max_uses: u64,
    #[serde(default)]
    pub uses: u64,
    #[serde(default)]
    pub expires_at: Option<String>,
    #[serde(default)]
    pub revoked_at: Option<String>,
    #[serde(default)]
    pub note: Option<String>,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub created_by: Option<String>,
    /// Klartext-Code – nur direkt nach dem Erstellen.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ApiCodes {
    pub codes: Vec<CodeView>,
}

/// Neue Codes erstellen (`POST /v1/admin/codes`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NewCodes {
    pub cape_id: String,
    #[serde(default = "one")]
    pub max_uses: u32,
    #[serde(default = "one")]
    pub count: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expires_at: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

fn one() -> u32 {
    1
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BanInfo {
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub banned_at: Option<String>,
    #[serde(default)]
    pub banned_by: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Grant {
    pub cape_id: String,
    #[serde(default)]
    pub source: String,
    #[serde(default)]
    pub granted_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdminUser {
    pub uuid: String,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub known: bool,
    #[serde(default)]
    pub admin: bool,
    #[serde(default)]
    pub banned: Option<BanInfo>,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub last_login_at: Option<String>,
    #[serde(default)]
    pub settings: Option<PrivacySettings>,
    #[serde(default)]
    pub active_cape_id: Option<String>,
    #[serde(default)]
    pub granted_capes: Vec<Grant>,
    #[serde(default)]
    pub uploads: u64,
    #[serde(default)]
    pub friends: u64,
    #[serde(default)]
    pub sessions: u64,
    #[serde(default)]
    pub online: bool,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ApiAdminUser {
    pub user: AdminUser,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiGrant {
    #[serde(default)]
    pub already_owned: bool,
}

// --- Anmeldung ------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiChallenge {
    pub server_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiVerify {
    pub token: String,
    pub expires_at: String,
    pub user: ApiMe,
}

/// Zustand der TRS-Dienste fürs Webview (nie mit Token).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrsStatus {
    /// `null` = noch nicht gefragt, sonst die Entscheidung des Nutzers.
    pub consent: Option<super::store::Consent>,
    /// Aktiver Minecraft-Account (UUID ohne Bindestriche).
    pub account: Option<String>,
    /// Für diesen Account liegt ein gültiger TRS-Token vor.
    pub signed_in: bool,
}
