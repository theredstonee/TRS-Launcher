//! TRS-Funktionen des Launchers: immer für den **aktiven** Minecraft-Account.

use std::sync::Arc;
use std::time::Duration;

use serde_json::json;

use super::texture::TextureSpec;
use super::types::{
    AdminCape, AdminStats, AdminUser, ApiActiveCape, ApiAdminCapes, ApiAdminUser, ApiBlocks, ApiCapeEnvelope,
    ApiCapeOffers, ApiCatalog, ApiCodes, ApiGrant, ApiLookup, ApiMe, ApiRedeem, BlockedUser, CapeHolders, CapeItem,
    CapeOffers, CodeView, Friend, FriendRequestResult, FriendsView, IncomingCapeOffer, Me, NewCodes,
    OutgoingCapeOffer, PlayerCape, ReportReason, ReviewList, SettingsPatch, TrsStatus, UserRef,
};
use super::{Consent, PRESENCE_INTERVAL, Req, TrsApi, me_view, no_account, png, validate};
use crate::{Error, Launcher, Result};

/// Wartezeit nach Fehlern, die sich nicht von selbst lösen (Sperre, Anmeldung abgelehnt).
const PRESENCE_BACKOFF: Duration = Duration::from_secs(5 * 60);

/// Nächster Versuch nach einer Präsenz-Meldung (`None` = gesendet).
fn presence_retry(error: Option<Error>) -> Duration {
    match error {
        None
        | Some(Error::TrsApi { kind: "trs_offline" | "trs_disabled", .. }) => PRESENCE_INTERVAL,
        Some(Error::TrsApi { kind: "trs_rate_limited", .. }) => Duration::from_secs(120),
        Some(e) => {
            tracing::debug!("Präsenz nicht gesendet: {e}");
            PRESENCE_BACKOFF
        }
    }
}

fn bad_response() -> Error {
    super::bad_response()
}

fn uuid_arg(input: &str) -> Result<String> {
    validate::uuid(input).ok_or_else(|| Error::validation(crate::msg!(
        "trsOps.invalidPlayerId",
        "Ungültige Spieler-ID."
    )))
}

fn cape_arg(input: &str) -> Result<&str> {
    if validate::cape_id(input) { Ok(input) } else { Err(Error::validation(crate::msg!(
        "trsOps.invalidCapeId",
        "Ungültige Umhang-ID."
    ))) }
}

/// Prozent-Kodierung für Query-Werte (alles außer `A–Z a–z 0–9 - _ . ~`).
pub(crate) fn encode_query(value: &str) -> String {
    let mut out = String::with_capacity(value.len() * 3);
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b'~') {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

async fn catalog_item(trs: &TrsApi, entry: super::types::ApiCatalogCape, token: Option<&str>) -> CapeItem {
    let texture = trs.texture(&TextureSpec::of(&entry.cape), token).await;
    let mut item = CapeItem::from_api(&entry.cape, entry.owned, entry.active, entry.reject_reason.as_deref(), texture);
    item.shared = entry.shared.and_then(super::types::CapeShareSource::cleaned);
    item.shareable = entry.shareable;
    item.holders = entry.holders.min(1000);
    item
}

/// Umhang eines Angebots mit Vorschau (freigegeben → öffentlich, ohne Token).
async fn offer_cape(trs: &TrsApi, cape: &super::types::ApiCape) -> CapeItem {
    let texture = trs.texture(&TextureSpec::of(cape), None).await;
    CapeItem::from_api(cape, false, false, None, texture)
}

async fn admin_item(trs: &TrsApi, entry: super::types::ApiAdminCape, token: Option<&str>) -> AdminCape {
    let texture = trs.texture(&TextureSpec::of(&entry.cape), token).await;
    AdminCape {
        cape: CapeItem::from_api(&entry.cape, false, false, entry.reject_reason.as_deref(), texture),
        owner: entry.owner.and_then(super::types::clean_user),
        created_at: entry.created_at.map(|t| validate::text(&t, 40)),
        reviewed_at: entry.reviewed_at.map(|t| validate::text(&t, 40)),
        reviewed_by: entry.reviewed_by.map(|t| validate::text(&t, 64)),
        reports: entry.reports,
        bytes: entry.bytes,
        owner_stats: entry.owner_stats,
    }
}

async fn player_item(trs: &TrsApi, player: super::types::ApiLookupPlayer) -> Option<PlayerCape> {
    let uuid = validate::uuid(&player.uuid)?;
    let cape = player.cape.filter(|c| {
        validate::cape_id(&c.id)
            && (1..=super::types::MAX_CAPE_SCALE).contains(&c.scale)
            && (1..=64).contains(&c.frames.max(1))
            && (c.frames <= 1 || c.frame_time_ms.is_some_and(|t| (20..=10_000).contains(&t)))
    });
    let Some(cape) = cape else {
        return Some(PlayerCape {
            uuid,
            badge: player.badge,
            cape_id: None,
            upload: false,
            scale: 1,
            frames: 1,
            frame_time_ms: None,
            texture: None,
        });
    };
    let frames = cape.frames.max(1);
    let spec = TextureSpec {
        id: &cape.id,
        url: &cape.url,
        width: 64 * cape.scale,
        total_height: 32 * cape.scale * frames,
        cacheable: true,
    };
    let texture = trs.texture(&spec, None).await;
    Some(PlayerCape {
        uuid,
        badge: player.badge,
        upload: validate::is_upload_id(&cape.id),
        cape_id: Some(cape.id.clone()),
        scale: cape.scale,
        frames,
        frame_time_ms: if frames > 1 { cape.frame_time_ms } else { None },
        texture,
    })
}

impl Launcher {
    pub fn trs(&self) -> &TrsApi {
        &self.trs
    }

    /// Aktiver Account – nur, wenn die TRS-Dienste eingeschaltet sind.
    pub(super) async fn trs_account(&self) -> Result<String> {
        self.trs.ensure_enabled().await?;
        self.accounts().active_id().await?.ok_or_else(no_account)
    }

    pub(super) async fn trs_get<T: serde::de::DeserializeOwned>(&self, req: Req) -> Result<T> {
        let account = self.trs_account().await?;
        self.trs.call(self.accounts(), &account, &req).await
    }

    /// Rohe Antwort (z. B. Bilder) mit dem Token des aktiven Accounts.
    pub(super) async fn trs_raw(&self, req: Req) -> Result<Vec<u8>> {
        let account = self.trs_account().await?;
        self.trs.call_raw(self.accounts(), &account, &req).await
    }

    pub(super) async fn trs_do(&self, req: Req) -> Result<()> {
        let account = self.trs_account().await?;
        self.trs.call_raw(self.accounts(), &account, &req).await.map(|_| ())
    }

    // --- Einwilligung & Konto -----------------------------------------------------------

    pub async fn trs_status(&self) -> Result<TrsStatus> {
        let account = self.accounts().active_id().await?;
        let signed_in = match &account {
            Some(a) if self.trs.enabled().await => self.trs.has_token(a).await,
            _ => false,
        };
        Ok(TrsStatus { consent: self.trs.consent().await, account, signed_in })
    }

    /// Zustimmen schaltet die Dienste ein; Ablehnen meldet alle Accounts ab,
    /// nimmt die Präsenz zurück und löscht die lokalen Tokens. Danach geht
    /// keine Anfrage mehr an die API.
    pub async fn trs_set_consent(&self, accepted: bool) -> Result<TrsStatus> {
        if accepted {
            self.trs.store.set_consent(Consent::Accepted).await;
            self.trs.presence.kick();
            self.trs.sync.kick();
        } else {
            if self.trs.enabled().await {
                for account in self.trs.store.accounts().await {
                    self.trs.send_offline(&account).await;
                    self.trs.presence.set_in_game(&account, false);
                    self.trs.logout(&account).await;
                }
            }
            self.trs.store.set_consent(Consent::Declined).await;
            self.trs.presence.set_online_for(None);
        }
        self.trs_status().await
    }

    pub async fn trs_me(&self) -> Result<Me> {
        me_view(self.trs_get::<ApiMe>(Req::get("/v1/me")).await?)
    }

    pub async fn trs_update_me(&self, patch: SettingsPatch) -> Result<Me> {
        if patch.is_empty() {
            return Err(Error::validation(crate::msg!("trsOps.noChange", "Keine Änderung angegeben.")));
        }
        let body = serde_json::to_value(&patch).map_err(|e| Error::json("Einstellungen", e))?;
        me_view(self.trs_get::<ApiMe>(Req::patch("/v1/me", body)).await?)
    }

    /// Löscht alle TRS-Daten des aktiven Accounts beim Server (DSGVO Art. 17)
    /// und schaltet die TRS-Dienste danach aus – sonst würde die nächste
    /// Präsenz-Meldung das Konto sofort neu anlegen.
    pub async fn trs_delete_me(&self) -> Result<TrsStatus> {
        let account = self.trs_account().await?;
        self.trs.call_raw(self.accounts(), &account, &Req::delete("/v1/me")).await?;
        let _ = self.trs.store.take_token(&account).await;
        // Auch die synchronisierten Skins/Presets/Einstellungen sind auf dem Server weg:
        // ein späterer Abgleich beginnt wie beim ersten Mal (lokal wird nichts gelöscht).
        self.trs.sync_store.forget(&account).await;
        if self.trs.presence.online_for().as_deref() == Some(account.as_str()) {
            self.trs.presence.set_online_for(None);
        }
        self.trs_set_consent(false).await
    }

    /// Account wird aus dem Launcher entfernt: Präsenz zurücknehmen, beim
    /// Server abmelden, Token vergessen. Fehler sind egal.
    pub async fn trs_forget_account(&self, account: &str) {
        if self.trs.enabled().await {
            self.trs.send_offline(account).await;
            self.trs.logout(account).await;
        } else {
            let _ = self.trs.store.take_token(account).await;
        }
        if self.trs.presence.online_for().as_deref() == Some(account) {
            self.trs.presence.set_online_for(None);
        }
        self.trs.presence.set_in_game(account, false);
        self.trs.presence.kick();
        self.trs.sync_store.forget(account).await;
        self.trs.sync.kick();
    }

    // --- Umhänge ------------------------------------------------------------------------

    /// Katalog aus Sicht des Accounts (Standard-Umhänge + eigene Uploads) mit Texturen.
    pub async fn trs_capes(&self) -> Result<Vec<CapeItem>> {
        let account = self.trs_account().await?;
        let catalog: ApiCatalog = self.trs.call(self.accounts(), &account, &Req::get("/v1/capes")).await?;
        let token = self.trs.store.token(&account).await;
        let valid: Vec<_> = catalog.capes.into_iter().filter(|c| c.cape.is_valid()).collect();
        let mut jobs = Vec::with_capacity(valid.len());
        for entry in valid {
            jobs.push(catalog_item(&self.trs, entry, token.as_deref()));
        }
        Ok(futures::future::join_all(jobs).await)
    }

    /// Umhang anlegen (`Some(id)`) oder ablegen (`None`). Liefert die aktive ID.
    pub async fn trs_set_cape(&self, cape_id: Option<String>) -> Result<Option<String>> {
        if let Some(id) = &cape_id {
            cape_arg(id)?;
        }
        let result: ApiActiveCape = self.trs_get(Req::put("/v1/me/cape", json!({ "capeId": cape_id }))).await?;
        Ok(result.active_cape.map(|c| c.id).filter(|id| validate::cape_id(id)))
    }

    /// Prüft den fertigen Streifen aus dem Zuschneide-Dialog (PNG, `frames` Frames
    /// untereinander) noch einmal vollständig und lädt ihn als eigenen Umhang hoch
    /// (Status „pending“).
    pub async fn trs_upload_cape(
        &self,
        bytes: Vec<u8>,
        frames: u32,
        frame_time_ms: Option<u32>,
        name: Option<&str>,
    ) -> Result<CapeItem> {
        let name = name.map(str::trim).filter(|n| !n.is_empty()).map(validate::upload_name).transpose()?;
        let layout = png::validate_upload(&bytes, frames, frame_time_ms)?;
        let mut path = format!("/v1/capes/upload?frames={}", layout.frames);
        if layout.frames > 1
            && let Some(ms) = frame_time_ms
        {
            path.push_str(&format!("&frameTimeMs={ms}"));
        }
        if let Some(n) = &name {
            path.push_str(&format!("&name={}", encode_query(n)));
        }
        let account = self.trs_account().await?;
        let req = Req::with(reqwest::Method::POST, path, super::Body::Png(bytes), super::MAX_JSON_BYTES);
        let result: ApiCapeEnvelope = self.trs.call(self.accounts(), &account, &req).await?;
        if !result.cape.is_valid() {
            return Err(bad_response());
        }
        let token = self.trs.store.token(&account).await;
        let texture = self.trs.texture(&TextureSpec::of(&result.cape), token.as_deref()).await;
        Ok(CapeItem::from_api(&result.cape, true, false, None, texture))
    }

    /// Eigenen Upload löschen.
    pub async fn trs_delete_cape(&self, id: &str) -> Result<()> {
        if !validate::is_upload_id(id) {
            return Err(Error::validation(crate::msg!(
                "trsOps.onlyOwnCapesDeletable",
                "Nur eigene Umhänge können gelöscht werden."
            )));
        }
        self.trs_do(Req::delete(format!("/v1/capes/{id}"))).await
    }

    /// Umhang eines anderen Spielers melden.
    pub async fn trs_report_cape(&self, id: &str, reason: ReportReason, note: Option<&str>) -> Result<()> {
        if !validate::is_upload_id(id) {
            return Err(Error::validation(crate::msg!(
                "trsOps.onlyUploadedReportable",
                "Nur hochgeladene Umhänge können gemeldet werden."
            )));
        }
        let mut body = json!({ "reason": reason });
        if let Some(note) = note.map(str::trim).filter(|n| !n.is_empty()) {
            body["note"] = json!(validate::plain_text(note, 200, validate::TextField::Comment)?);
        }
        self.trs_do(Req::post(format!("/v1/capes/{id}/report"), body)).await
    }

    pub async fn trs_redeem(&self, code: &str) -> Result<super::types::RedeemResult> {
        let code = validate::redeem_code(code)
            .ok_or_else(|| Error::validation(crate::msg!(
                "trsOps.invalidCodeFormat",
                "Codes haben 20 Zeichen, z. B. 7K3QF-M2XPA-9RTVB-C4HJN."
            )))?;
        let result: ApiRedeem = self.trs_get(Req::post("/v1/capes/redeem", json!({ "code": code }))).await?;
        if !validate::cape_id(&result.cape.id) {
            return Err(bad_response());
        }
        Ok(super::types::RedeemResult {
            name: validate::cape_name(&result.cape.name, &result.cape.id),
            cape_id: result.cape.id,
            already_owned: result.already_owned,
        })
    }

    /// Abzeichen und Umhänge anderer Spieler (z. B. der Freunde), höchstens 100.
    pub async fn trs_player_capes(&self, uuids: &[String]) -> Result<Vec<PlayerCape>> {
        let mut list: Vec<String> = uuids.iter().filter_map(|u| validate::uuid(u)).collect();
        list.sort();
        list.dedup();
        if list.is_empty() {
            return Ok(Vec::new());
        }
        if list.len() > 100 {
            return Err(Error::validation(crate::msg!("trsOps.tooManyPlayers", "Höchstens 100 Spieler auf einmal.")));
        }
        let lookup: ApiLookup = self.trs_get(Req::post("/v1/players/lookup", json!({ "uuids": list }))).await?;
        let mut jobs = Vec::with_capacity(lookup.players.len());
        for player in lookup.players {
            jobs.push(player_item(&self.trs, player));
        }
        Ok(futures::future::join_all(jobs).await.into_iter().flatten().collect())
    }

    // --- Umhänge teilen (§5.10) ---------------------------------------------------------

    /// Offene Angebote an mich (mit Vorschau) und von mir.
    pub async fn trs_cape_offers(&self) -> Result<CapeOffers> {
        let offers: ApiCapeOffers = self.trs_get(Req::get("/v1/cape-offers")).await?;
        let incoming = offers.incoming.into_iter().filter(|o| o.cape.is_valid()).take(200).map(|o| async move {
            let from = super::types::clean_user(o.from)?;
            let creator = super::types::clean_user(o.creator)?;
            Some(IncomingCapeOffer {
                cape: offer_cape(&self.trs, &o.cape).await,
                from,
                creator,
                created_at: o.created_at.map(|t| validate::text(&t, 40)),
            })
        });
        let outgoing = offers.outgoing.into_iter().filter(|o| o.cape.is_valid()).take(200).map(|o| async move {
            let to = super::types::clean_user(o.to)?;
            Some(OutgoingCapeOffer {
                cape: offer_cape(&self.trs, &o.cape).await,
                to,
                created_at: o.created_at.map(|t| validate::text(&t, 40)),
            })
        });
        let (incoming, outgoing) =
            futures::future::join(futures::future::join_all(incoming), futures::future::join_all(outgoing)).await;
        Ok(CapeOffers {
            incoming: incoming.into_iter().flatten().collect(),
            outgoing: outgoing.into_iter().flatten().collect(),
        })
    }

    /// Eigenen freigegebenen (oder angenommenen geteilten) Umhang einem Freund anbieten.
    pub async fn trs_offer_cape(&self, cape_id: &str, friend: &str) -> Result<()> {
        let cape_id = cape_arg(cape_id)?;
        let friend = uuid_arg(friend)?;
        self.trs_do(Req::post("/v1/cape-offers", json!({ "capeId": cape_id, "friend": friend }))).await
    }

    pub async fn trs_accept_cape_offer(&self, cape_id: &str) -> Result<()> {
        let cape_id = cape_arg(cape_id)?;
        self.trs_do(Req::post_empty(format!("/v1/cape-offers/{cape_id}/accept"))).await
    }

    pub async fn trs_decline_cape_offer(&self, cape_id: &str) -> Result<()> {
        let cape_id = cape_arg(cape_id)?;
        self.trs_do(Req::post_empty(format!("/v1/cape-offers/{cape_id}/decline"))).await
    }

    /// Wer hat diesen Umhang von mir (Ersteller: alle, Inhaber: der eigene Ast)?
    pub async fn trs_cape_holders(&self, cape_id: &str) -> Result<CapeHolders> {
        let cape_id = cape_arg(cape_id)?;
        Ok(self.trs_get::<CapeHolders>(Req::get(format!("/v1/capes/{cape_id}/holders"))).await?.cleaned())
    }

    /// Teilung entziehen / Angebot zurückziehen (samt Weitergegebenem); eigene UUID = zurückgeben.
    pub async fn trs_revoke_cape_share(&self, cape_id: &str, holder: &str) -> Result<()> {
        let cape_id = cape_arg(cape_id)?;
        let holder = uuid_arg(holder)?;
        self.trs_do(Req::delete(format!("/v1/capes/{cape_id}/holders/{holder}"))).await
    }

    // --- Freunde ------------------------------------------------------------------------

    pub async fn trs_friends(&self) -> Result<FriendsView> {
        Ok(self.trs_get::<FriendsView>(Req::get("/v1/friends")).await?.cleaned())
    }

    pub async fn trs_blocks(&self) -> Result<Vec<BlockedUser>> {
        Ok(super::types::clean_blocks(self.trs_get::<ApiBlocks>(Req::get("/v1/blocks")).await?.blocked))
    }

    pub async fn trs_friend_request(&self, target: &str) -> Result<FriendRequestResult> {
        let target = validate::target(target)?;
        let result: FriendRequestResult =
            self.trs_get(Req::post("/v1/friends/requests", json!({ "target": target }))).await?;
        let user = super::types::clean_user(result.user).ok_or_else(bad_response)?;
        let status = if result.status == "accepted" { "accepted" } else { "sent" };
        Ok(FriendRequestResult { status: status.into(), user })
    }

    pub async fn trs_friend_accept(&self, uuid: &str) -> Result<Friend> {
        #[derive(serde::Deserialize)]
        struct Accepted {
            friend: Friend,
        }
        let uuid = uuid_arg(uuid)?;
        let result: Accepted = self.trs_get(Req::post_empty(format!("/v1/friends/requests/{uuid}/accept"))).await?;
        let view = FriendsView { friends: vec![result.friend], ..Default::default() }.cleaned();
        view.friends.into_iter().next().ok_or_else(bad_response)
    }

    pub async fn trs_friend_decline(&self, uuid: &str) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        self.trs_do(Req::post_empty(format!("/v1/friends/requests/{uuid}/decline"))).await
    }

    pub async fn trs_friend_cancel(&self, uuid: &str) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        self.trs_do(Req::delete(format!("/v1/friends/requests/{uuid}"))).await
    }

    pub async fn trs_friend_remove(&self, uuid: &str) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        self.trs_do(Req::delete(format!("/v1/friends/{uuid}"))).await
    }

    pub async fn trs_block(&self, target: &str) -> Result<UserRef> {
        #[derive(serde::Deserialize)]
        struct Blocked {
            blocked: UserRef,
        }
        let target = validate::target(target)?;
        let result: Blocked = self.trs_get(Req::post("/v1/blocks", json!({ "target": target }))).await?;
        super::types::clean_user(result.blocked).ok_or_else(bad_response)
    }

    pub async fn trs_unblock(&self, uuid: &str) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        self.trs_do(Req::delete(format!("/v1/blocks/{uuid}"))).await
    }

    // --- Website-Anmeldung --------------------------------------------------------------

    /// Bestätigt eine Anmeldung auf der Website (`trs-launcher.theredstonee.de`):
    /// Die Website zeigt einen Code, der Launcher schickt ihn mit dem TRS-Token
    /// des **aktiven** Accounts an die API. Der Token verlässt den Kern nie.
    ///
    /// Angenommener Vertrag (wird parallel in `api/API.md` festgelegt):
    /// `POST /v1/web-login/approve` mit `{ "code": "ABCD-1234" }` → `204`
    /// (jede 2xx-Antwort gilt, ein Body wird ignoriert); Fehler
    /// `{ "error": { "code": "invalid_code" | "expired" | "not_admin" | "rate_limited" } }`.
    /// Naheliegende Varianten der Codes (z. B. `code_expired`, `forbidden`,
    /// `not_found`) werden gleich behandelt.
    pub async fn trs_web_login_approve(&self, code: &str) -> Result<()> {
        let code = validate::web_login_code(code).ok_or_else(|| {
            Error::validation(crate::msg!("trsOps.invalidWebLoginCode", "Der Code hat die Form ABCD-1234 (Buchstaben und Ziffern)."))
        })?;
        match self.trs_do(Req::post("/v1/web-login/approve", json!({ "code": code }))).await {
            Err(Error::TrsApi { kind, code, msg }) => {
                let msg = web_login_message(kind, &code).unwrap_or(msg);
                Err(Error::TrsApi { kind, code, msg })
            }
            other => other,
        }
    }

    // --- Verwaltung (nur Admins; der Server prüft das selbst) -------------------------

    pub async fn trs_admin_stats(&self) -> Result<AdminStats> {
        self.trs_get(Req::get("/v1/admin/stats")).await
    }

    pub async fn trs_admin_capes(&self, list: ReviewList) -> Result<Vec<AdminCape>> {
        let account = self.trs_account().await?;
        let req = Req::get(format!("/v1/admin/capes?status={}", list.as_str()));
        let result: ApiAdminCapes = self.trs.call(self.accounts(), &account, &req).await?;
        let token = self.trs.store.token(&account).await;
        let mut jobs = Vec::new();
        for entry in result.capes.into_iter().filter(|c| c.cape.is_valid()) {
            jobs.push(admin_item(&self.trs, entry, token.as_deref()));
        }
        Ok(futures::future::join_all(jobs).await)
    }

    pub async fn trs_admin_approve(&self, id: &str) -> Result<()> {
        let id = cape_arg(id)?;
        self.trs_do(Req::post_empty(format!("/v1/admin/capes/{id}/approve"))).await
    }

    pub async fn trs_admin_reject(&self, id: &str, reason: Option<&str>) -> Result<()> {
        let id = cape_arg(id)?;
        let req = match reason.map(str::trim).filter(|r| !r.is_empty()) {
            Some(r) => Req::post(
                format!("/v1/admin/capes/{id}/reject"),
                json!({ "reason": validate::plain_text(r, 200, validate::TextField::Reason)? }),
            ),
            None => Req::post_empty(format!("/v1/admin/capes/{id}/reject")),
        };
        self.trs_do(req).await
    }

    pub async fn trs_admin_delete_cape(&self, id: &str) -> Result<()> {
        if !validate::is_upload_id(id) {
            return Err(Error::validation(crate::msg!(
                "trsOps.onlyUploadedDeletable",
                "Nur hochgeladene Umhänge können gelöscht werden."
            )));
        }
        self.trs_do(Req::delete(format!("/v1/admin/capes/{id}"))).await
    }

    pub async fn trs_admin_codes(&self) -> Result<Vec<CodeView>> {
        Ok(self.trs_get::<ApiCodes>(Req::get("/v1/admin/codes")).await?.codes)
    }

    /// Erstellt Codes. Die Klartext-Codes gibt es **nur** in dieser Antwort.
    pub async fn trs_admin_create_codes(&self, new: NewCodes) -> Result<Vec<CodeView>> {
        let new = validate_new_codes(new)?;
        let body = serde_json::to_value(&new).map_err(|e| Error::json("Codes", e))?;
        Ok(self.trs_get::<ApiCodes>(Req::post("/v1/admin/codes", body)).await?.codes)
    }

    pub async fn trs_admin_revoke_code(&self, id: u64) -> Result<()> {
        if id == 0 || id > (1u64 << 53) {
            return Err(Error::validation(crate::msg!("trsOps.invalidCodeId", "Ungültige Code-ID.")));
        }
        self.trs_do(Req::delete(format!("/v1/admin/codes/{id}"))).await
    }

    /// Spieler per Name oder UUID. Kennt die API den Namen nicht (nie
    /// angemeldet), wird er über Mojang in eine UUID übersetzt – so lassen sich
    /// auch vorsorglich gesperrte Spieler finden.
    pub async fn trs_admin_user(&self, query: &str) -> Result<AdminUser> {
        let query = validate::target(query)?;
        let account = self.trs_account().await?;
        let req = Req::get(format!("/v1/admin/users/{query}"));
        match self.trs.call::<ApiAdminUser>(self.accounts(), &account, &req).await {
            Ok(r) => clean_admin_user(r.user),
            Err(Error::TrsApi { code, .. }) if code == "user_not_found" && validate::mc_name(&query) => {
                let uuid = self.trs.mojang_uuid(&query).await?.ok_or_else(user_not_found)?;
                let req = Req::get(format!("/v1/admin/users/{uuid}"));
                clean_admin_user(self.trs.call::<ApiAdminUser>(self.accounts(), &account, &req).await?.user)
            }
            Err(e) => Err(e),
        }
    }

    /// Name/UUID → UUID für Admin-Aktionen (bekannte Nutzer zuerst, sonst Mojang).
    async fn trs_admin_resolve(&self, player: &str, must_be_known: bool) -> Result<String> {
        let target = validate::target(player)?;
        if let Some(uuid) = validate::uuid(&target) {
            return Ok(uuid);
        }
        let account = self.trs_account().await?;
        let req = Req::get(format!("/v1/admin/users/{target}"));
        match self.trs.call::<ApiAdminUser>(self.accounts(), &account, &req).await {
            Ok(r) => validate::uuid(&r.user.uuid).ok_or_else(bad_response),
            Err(Error::TrsApi { code, .. }) if code == "user_not_found" && !must_be_known => {
                self.trs.mojang_uuid(&target).await?.ok_or_else(user_not_found)
            }
            Err(e) => Err(e),
        }
    }

    /// Umhang an einen Spieler vergeben (er muss TRS schon einmal benutzt haben).
    /// Liefert `true`, wenn er ihn schon hatte.
    pub async fn trs_admin_grant(&self, player: &str, cape_id: &str) -> Result<bool> {
        let cape_id = cape_arg(cape_id)?;
        let uuid = self.trs_admin_resolve(player, true).await?;
        let result: ApiGrant =
            self.trs_get(Req::post(format!("/v1/admin/users/{uuid}/capes"), json!({ "capeId": cape_id }))).await?;
        Ok(result.already_owned)
    }

    pub async fn trs_admin_revoke_grant(&self, uuid: &str, cape_id: &str) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        let cape_id = cape_arg(cape_id)?;
        self.trs_do(Req::delete(format!("/v1/admin/users/{uuid}/capes/{cape_id}"))).await
    }

    pub async fn trs_admin_ban(&self, player: &str, reason: Option<&str>) -> Result<AdminUser> {
        let uuid = self.trs_admin_resolve(player, false).await?;
        let req = match reason.map(str::trim).filter(|r| !r.is_empty()) {
            Some(r) => {
                Req::post(format!("/v1/admin/users/{uuid}/ban"), json!({ "reason": validate::plain_text(r, 200, validate::TextField::Reason)? }))
            }
            None => Req::post_empty(format!("/v1/admin/users/{uuid}/ban")),
        };
        clean_admin_user(self.trs_get::<ApiAdminUser>(req).await?.user)
    }

    pub async fn trs_admin_unban(&self, uuid: &str) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        self.trs_do(Req::delete(format!("/v1/admin/users/{uuid}/ban"))).await
    }

    // --- Präsenz ------------------------------------------------------------------------

    /// Hintergrund-Schleife: meldet `online` im 60-s-Takt (siehe `presence.rs`).
    /// Läuft, solange der Launcher läuft; ohne Einwilligung passiert nichts.
    pub async fn run_trs_presence(self: Arc<Self>) {
        tokio::time::sleep(Duration::from_secs(4)).await;
        loop {
            let wait = self.presence_tick().await;
            tokio::select! {
                () = tokio::time::sleep(wait) => {}
                () = self.trs.presence.notify.notified() => {}
            }
        }
    }

    pub(crate) async fn presence_tick(&self) -> Duration {
        if !self.trs.enabled().await {
            return PRESENCE_INTERVAL;
        }
        let Ok(active) = self.accounts().active_id().await else { return PRESENCE_INTERVAL };
        let plan = self.trs.presence.plan(active.as_deref());
        if plan.is_empty() {
            return PRESENCE_INTERVAL;
        }
        let gap = self.trs.presence.gap_left();
        if !gap.is_zero() {
            tokio::time::sleep(gap).await;
        }
        if let Some(previous) = &plan.offline {
            self.trs.send_offline(previous).await;
            self.trs.presence.set_online_for(None);
        }
        for account in &plan.ended {
            self.trs.send_offline(account).await;
            self.trs.presence.set_in_game(account, false);
        }
        let mut wait = PRESENCE_INTERVAL;
        // Vom Launcher gestartete Spiele: `in-game` (daran hängt das Live-Abzeichen).
        for (account, game) in &plan.in_game {
            let mut body = json!({ "state": "in-game", "via": "launcher" });
            if let Some(g) = game {
                body["game"] = json!({ "version": g.version, "loader": g.loader });
            }
            let result = self.trs.call_raw(self.accounts(), account, &Req::post("/v1/presence", body)).await;
            self.trs.presence.mark_sent();
            match result {
                Ok(_) => self.trs.presence.set_in_game(account, true),
                // Nur das Rate-Limit bremst die ganze Schleife; sonst (z. B. Demo-Konto ohne TRS) weiter im Takt.
                Err(e @ Error::TrsApi { kind: "trs_rate_limited", .. }) => wait = wait.max(presence_retry(Some(e))),
                Err(e) => tracing::debug!("Präsenz (im Spiel) nicht gesendet: {e}"),
            }
        }
        let Some(account) = plan.online else { return wait };
        let req = Req::post("/v1/presence", json!({ "state": "online", "via": "launcher" }));
        let result = self.trs.call_raw(self.accounts(), &account, &req).await;
        self.trs.presence.mark_sent();
        if result.is_ok() {
            self.trs.presence.set_online_for(Some(&account));
        }
        wait.max(presence_retry(result.err()))
    }

    /// Beim Beenden des Launchers: eigene Meldungen zurücknehmen (kurzer
    /// Timeout). Läuft ein Spiel weiter, meldet der Mod es selbst, solange man
    /// in einer Welt ist – dafür weiß der Launcher dann nichts mehr davon.
    pub async fn trs_shutdown(&self) {
        if !self.trs.enabled().await {
            return;
        }
        let mut accounts = self.trs.presence.in_game_for();
        if let Some(account) = self.trs.presence.online_for()
            && !accounts.contains(&account)
        {
            accounts.push(account);
        }
        for account in accounts {
            self.trs.send_offline(&account).await;
        }
    }
}

/// Verständliche Meldungen für Fehler der Website-Anmeldung. `None` = die
/// allgemeine Meldung passt schon (offline, Rate-Limit mit Wartezeit, Sperre …).
fn web_login_message(kind: &str, code: &str) -> Option<crate::error::Msg> {
    if !matches!(kind, "trs_api" | "trs_forbidden") {
        return None;
    }
    Some(match code {
        "invalid_code" | "unknown_code" | "code_not_found" | "not_found" => crate::msg!(
            "trsWebLogin.invalidCode",
            "Diesen Anmeldecode gibt es nicht – vergleiche ihn mit dem Code auf der Website."
        ),
        "expired" | "code_expired" | "web_login_expired" => crate::msg!(
            "trsWebLogin.expired",
            "Der Anmeldecode ist abgelaufen – lade die Website neu und gib den neuen Code ein."
        ),
        "not_admin" | "forbidden" => {
            crate::msg!("trsWebLogin.notAdmin", "Nur TRS-Admins können sich auf der Website anmelden.")
        }
        "already_used" | "already_approved" | "code_used" => {
            crate::msg!("trsWebLogin.alreadyUsed", "Dieser Anmeldecode wurde schon bestätigt.")
        }
        _ => return None,
    })
}

fn user_not_found() -> Error {
    super::api_error(404, "user_not_found", None)
}

fn clean_admin_user(mut user: AdminUser) -> Result<AdminUser> {
    user.uuid = validate::uuid(&user.uuid).ok_or_else(bad_response)?;
    user.name = user.name.map(|n| validate::display_name(&n));
    user.granted_capes.retain(|g| validate::cape_id(&g.cape_id));
    if let Some(ban) = &mut user.banned {
        ban.reason = ban.reason.as_deref().map(|r| validate::text(r, 200));
    }
    Ok(user)
}

fn validate_new_codes(new: NewCodes) -> Result<NewCodes> {
    cape_arg(&new.cape_id)?;
    if !(1..=100_000).contains(&new.max_uses) {
        return Err(Error::validation(crate::msg!("trsOps.redemptionsRange", "Einlösungen je Code: 1 bis 100 000.")));
    }
    if !(1..=100).contains(&new.count) {
        return Err(Error::validation(crate::msg!("trsOps.countRange", "Anzahl: 1 bis 100 Codes auf einmal.")));
    }
    let expires_at = match new.expires_at.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(at) => {
            let at = validate::iso_datetime(at)?;
            let parsed = chrono::DateTime::parse_from_rfc3339(&at).map_err(|_| Error::validation(crate::msg!(
                "trsValidate.invalidExpiry",
                "Ungültiges Ablaufdatum."
            )))?;
            if parsed < chrono::Utc::now() {
                return Err(Error::validation(crate::msg!(
                    "trsOps.expiryInPast",
                    "Das Ablaufdatum liegt in der Vergangenheit."
                )));
            }
            Some(at)
        }
        None => None,
    };
    let note = match new.note.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(n) => Some(validate::plain_text(n, 200, validate::TextField::Note)?),
        None => None,
    };
    Ok(NewCodes { expires_at, note, ..new })
}

impl TrsApi {
    /// Minecraft-Name → UUID über die öffentliche Mojang-API (`None` = gibt es nicht).
    pub(crate) async fn mojang_uuid(&self, name: &str) -> Result<Option<String>> {
        #[derive(serde::Deserialize)]
        struct Profile {
            id: String,
        }
        if !validate::mc_name(name) {
            return Ok(None);
        }
        let response = self
            .http
            .get(format!("{}/users/profiles/minecraft/{name}", self.mojang_api))
            .send()
            .await
            .map_err(|_| super::offline())?;
        match response.status().as_u16() {
            200 => {
                let profile: Profile = response.json().await.map_err(|_| bad_response())?;
                Ok(validate::uuid(&profile.id))
            }
            204 | 404 => Ok(None),
            429 => Err(super::api_error(429, "rate_limited", Some(60))),
            _ => Err(super::offline()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn query_values_are_percent_encoded() {
        assert_eq!(encode_query("Mein Umhang!"), "Mein%20Umhang%21");
        assert_eq!(encode_query("ä&b"), "%C3%A4%26b");
    }

    #[test]
    fn new_codes_are_checked() {
        let base = NewCodes { cape_id: "team".into(), max_uses: 1, count: 1, expires_at: None, note: None };
        assert!(validate_new_codes(base.clone()).is_ok());
        assert!(validate_new_codes(NewCodes { count: 0, ..base.clone() }).is_err());
        assert!(validate_new_codes(NewCodes { count: 101, ..base.clone() }).is_err());
        assert!(validate_new_codes(NewCodes { max_uses: 100_001, ..base.clone() }).is_err());
        assert!(validate_new_codes(NewCodes { cape_id: "Team!".into(), ..base.clone() }).is_err());
        assert!(validate_new_codes(NewCodes { expires_at: Some("2001-01-01T00:00:00Z".into()), ..base.clone() }).is_err());
        let ok = validate_new_codes(NewCodes {
            expires_at: Some("2999-01-01T00:00:00+01:00".into()),
            note: Some("  Discord  ".into()),
            ..base
        })
        .unwrap();
        assert_eq!(ok.expires_at.as_deref(), Some("2998-12-31T23:00:00.000Z"));
        assert_eq!(ok.note.as_deref(), Some("Discord"));
    }
}
