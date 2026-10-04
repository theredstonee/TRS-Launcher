//! Lokale Server mit Freunden teilen.
//!
//! - **Adressen:** `localhost`, Heimnetz (LAN) und öffentliche IP (dann ist eine
//!   Portfreigabe im Router nötig).
//! - **TRS Relay:** Der Launcher legt über die TRS API (§21) einen Raum nur für
//!   eingeladene Freunde an und ist selbst dessen Host: Er hält die Steuerverbindung
//!   zum Relay und reicht jede Gast-Verbindung an `127.0.0.1:<Port>` weiter. Freunde
//!   treten wie bei gehosteten Welten bei (Einladung → Instanz → TRS Client → Relay).
//!   Ohne Portfreigabe. Ein Konto hat höchstens einen Raum – also ein Server zur Zeit.
//! - **e4mc:** öffentlicher Link `*.e4mc.link` über den fremden Dienst e4mc (jeder mit
//!   der Adresse kann beitreten).

pub mod addresses;
pub mod e4mc;
#[cfg(not(any(target_os = "android", target_os = "ios")))]
pub mod e4mc_quic;
pub mod relay_host;
#[cfg(test)]
mod tests;

use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tokio::sync::{mpsc, watch};

use crate::local_servers;
use crate::trs_api::hosting::{InviteOutcome, ServerRoomSpec};
use crate::{Error, Launcher, Result};

const HEARTBEAT_EVERY: Duration = Duration::from_secs(30);

/// Was eine Freigabe unterwegs meldet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ShareEvent {
    RelayOnline,
    RelayOffline(String),
    E4mcDomain(String),
    E4mcOffline(String),
}

pub type ShareEvents = Arc<dyn Fn(ShareEvent) + Send + Sync>;

/// Warum eine Freigabe endete.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ShareEnd {
    Stopped,
    /// Endgültig (z. B. Raum zu, von einem anderen Gerät ersetzt).
    Fatal(String),
}

/// Wartet, bis `stop` gesetzt (oder der Sender weg) ist.
pub(crate) async fn stopped(stop: &mut watch::Receiver<bool>) {
    let _ = stop.wait_for(|s| *s).await;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ChannelState {
    #[default]
    Off,
    Connecting,
    Online,
    /// Verbindung weg, neuer Versuch läuft.
    Reconnecting,
}

/// Stand eines Weges (Relay oder e4mc).
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelStatus {
    pub state: ChannelState,
    /// Relay: Raum-ID.
    pub room_id: Option<String>,
    /// e4mc: öffentliche Adresse.
    pub address: Option<String>,
    /// Letzter Fehlercode (kurz, ohne Details).
    pub error: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShareStatus {
    pub relay: ChannelStatus,
    pub e4mc: ChannelStatus,
}

/// Ereignis an die Oberfläche (Tauri: `local-server-share`).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShareUpdate {
    pub id: String,
    pub status: ShareStatus,
}

pub type ShareSink = Arc<dyn Fn(ShareUpdate) + Send + Sync>;

/// So erreicht man den Server.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerAddresses {
    pub port: u16,
    /// Auf diesem PC.
    pub local: String,
    /// Im selben Netz (WLAN/LAN).
    pub lan: Option<String>,
    /// Über das Internet (mit Portfreigabe).
    pub public: Option<String>,
}

/// Welcher Weg an- oder ausgeschaltet wird.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ShareKind {
    Relay,
    E4mc,
}

#[derive(Default)]
struct Entry {
    status: ShareStatus,
    relay_stop: Option<watch::Sender<bool>>,
    e4mc_stop: Option<watch::Sender<bool>>,
    /// Eingeladene Freunde (für einen neu angelegten Raum).
    invited: Vec<String>,
}

#[derive(Default)]
pub struct ServerShares {
    entries: Mutex<HashMap<String, Entry>>,
    sink: RwLock<Option<ShareSink>>,
}

impl ServerShares {
    pub fn set_sink(&self, sink: ShareSink) {
        *self.sink.write().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(sink);
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, Entry>> {
        self.entries.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    pub fn status(&self, id: &str) -> ShareStatus {
        self.lock().get(id).map(|e| e.status.clone()).unwrap_or_default()
    }

    /// Stand ändern und melden.
    fn update(&self, id: &str, change: impl FnOnce(&mut ShareStatus)) -> ShareStatus {
        let status = {
            let mut entries = self.lock();
            let entry = entries.entry(id.to_owned()).or_default();
            change(&mut entry.status);
            entry.status.clone()
        };
        let sink = self.sink.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
        if let Some(sink) = sink {
            sink(ShareUpdate { id: id.to_owned(), status: status.clone() });
        }
        status
    }

    /// Stop-Signal eines Weges setzen (Aufgabe räumt selbst auf).
    fn stop(&self, id: &str, kind: ShareKind) -> bool {
        let sender = {
            let mut entries = self.lock();
            let Some(entry) = entries.get_mut(id) else { return false };
            match kind {
                ShareKind::Relay => entry.relay_stop.take(),
                ShareKind::E4mc => entry.e4mc_stop.take(),
            }
        };
        sender.is_some_and(|s| s.send(true).is_ok())
    }

    /// Server mit aktivem Relay-Raum (höchstens einer).
    fn relay_owner(&self) -> Option<(String, Option<String>)> {
        self.lock().iter().find(|(_, e)| e.relay_stop.is_some()).map(|(id, e)| (id.clone(), e.status.relay.room_id.clone()))
    }
}

/// Raum-Angaben aus der Server-Beschreibung.
pub fn room_spec(meta: &local_servers::ServerMeta) -> ServerRoomSpec {
    use crate::instance::LoaderKind;
    let loader = match meta.loader {
        LoaderKind::Vanilla => "vanilla",
        LoaderKind::Fabric => "fabric",
        LoaderKind::Quilt => "quilt",
        LoaderKind::Forge => "forge",
        LoaderKind::NeoForge => "neoforge",
    };
    ServerRoomSpec { name: meta.name.clone(), mc_version: meta.game_version.clone(), loader: loader.into(), max_players: meta.max_players }
}

/// `hosting_signal` → (Raum, Absender, Signal-ID), nur Angebote (`offer`).
pub fn parse_offer(data: &str) -> Option<(String, String, String)> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Raw {
        #[serde(default)]
        room_id: String,
        #[serde(default)]
        from: String,
        #[serde(default)]
        kind: String,
        #[serde(default)]
        sid: Option<String>,
    }
    let raw: Raw = serde_json::from_str(data).ok()?;
    if raw.kind != "offer" || !crate::trs_api::hosting::room_id(&raw.room_id) {
        return None;
    }
    let from = crate::trs_api::validate::uuid(&raw.from)?;
    let sid = raw.sid.filter(|s| crate::trs_api::hosting::signal_id(s))?;
    Some((raw.room_id, from, sid))
}

fn target(port: u16) -> SocketAddr {
    SocketAddr::from((Ipv4Addr::LOCALHOST, port))
}

fn not_running() -> Error {
    Error::validation(crate::msg!("localServer.notRunning", "Der Server läuft nicht."))
}

impl Launcher {
    pub fn server_shares(&self) -> &Arc<ServerShares> {
        &self.server_shares
    }

    async fn running_meta(&self, id: &str) -> Result<(local_servers::ServerMeta, watch::Receiver<bool>)> {
        let dir = local_servers::server_dir(self.paths(), id)?;
        let meta = tokio::task::spawn_blocking(move || local_servers::read_meta(&dir))
            .await
            .map_err(|e| Error::Internal(e.to_string()))??;
        let exit = self.local_servers.exit_watch(id).ok_or_else(not_running)?;
        Ok((meta, exit))
    }

    /// Adressen des Servers (öffentliche IP gemerkt; `refresh` fragt neu).
    pub async fn local_server_addresses(&self, id: &str, refresh: bool) -> Result<ServerAddresses> {
        crate::platform::desktop_only()?;
        let dir = local_servers::server_dir(self.paths(), id)?;
        let meta = tokio::task::spawn_blocking(move || local_servers::read_meta(&dir))
            .await
            .map_err(|e| Error::Internal(e.to_string()))??;
        let port = meta.port;
        let lan = tokio::task::spawn_blocking(addresses::lan_ipv4).await.ok().flatten();
        let public = addresses::public_ip(self.http(), refresh).await;
        Ok(ServerAddresses {
            port,
            local: format!("localhost:{port}"),
            lan: lan.map(|ip| addresses::display(ip.into(), port)),
            public: public.map(|ip| addresses::display(ip, port)),
        })
    }

    pub fn local_server_share_status(&self, id: &str) -> Result<ShareStatus> {
        local_servers::validate_server_id(id)?;
        Ok(self.server_shares.status(id))
    }

    /// Weg ausschalten.
    pub async fn local_server_unshare(&self, id: &str, kind: ShareKind) -> Result<ShareStatus> {
        local_servers::validate_server_id(id)?;
        if !self.server_shares.stop(id, kind) {
            // Nichts lief (mehr) – Stand aufräumen.
            return Ok(self.server_shares.update(id, |s| match kind {
                ShareKind::Relay => s.relay = ChannelStatus::default(),
                ShareKind::E4mc => s.e4mc = ChannelStatus::default(),
            }));
        }
        Ok(self.server_shares.status(id))
    }

    /// Über das TRS Relay teilen: Raum anlegen (nur auf Einladung) und Host-Seite starten.
    pub async fn local_server_share_relay(self: &Arc<Self>, id: &str) -> Result<ShareStatus> {
        crate::platform::desktop_only()?;
        let (meta, exit) = self.running_meta(id).await?;
        if self.server_shares.lock().get(id).is_some_and(|e| e.relay_stop.is_some()) {
            return Ok(self.server_shares.status(id));
        }
        // Ein Konto hat einen Raum: einen anderen geteilten Server zuerst ausschalten.
        if let Some((other, _)) = self.server_shares.relay_owner() {
            self.server_shares.stop(&other, ShareKind::Relay);
        }
        let shares = self.server_shares.clone();
        // Stop-Signal schon vor dem Anlegen: ein zweiter Klick legt keinen zweiten Raum an.
        let (stop_tx, stop_rx) = watch::channel(false);
        shares.lock().entry(id.to_owned()).or_default().relay_stop = Some(stop_tx);
        shares.update(id, |s| s.relay = ChannelStatus { state: ChannelState::Connecting, ..ChannelStatus::default() });
        let created = self.hosting_create_server_room(&room_spec(&meta)).await;
        let clear = |error: Option<String>| {
            if let Some(entry) = shares.lock().get_mut(id) {
                entry.relay_stop = None;
            }
            shares.update(id, |s| s.relay = ChannelStatus { error, ..ChannelStatus::default() })
        };
        let (room, first) = match created {
            Ok(created) => created,
            Err(e) => {
                clear(Some(e.code().unwrap_or(e.kind()).to_owned()));
                return Err(e);
            }
        };
        if *stop_rx.borrow() {
            // Während des Anlegens ausgeschaltet.
            let _ = self.hosting_close_room(&room.id).await;
            return Ok(clear(None));
        }
        let status = shares.update(id, |s| s.relay.room_id = Some(room.id.clone()));

        let launcher = Arc::clone(self);
        let id = id.to_owned();
        tokio::spawn(async move {
            let (mut room_id, mut grant) = (room.id, first);
            let mut renewals = 0;
            let end = loop {
                let end = launcher.relay_room(&id, &meta, &room_id, grant, stop_rx.clone(), exit.clone()).await;
                if end != ShareEnd::Fatal("room_closed".into()) || renewals >= 5 || *stop_rx.borrow() || *exit.borrow() {
                    break end;
                }
                // Raum weg – z. B. räumt der TRS Client beim Start einen „übrig gebliebenen“ Raum
                // desselben Kontos auf. Neu anlegen, außer das Spiel hostet jetzt selbst eine Welt.
                match launcher.hosting_my_rooms().await {
                    Ok(rooms) if rooms.is_empty() => {}
                    Ok(_) => break ShareEnd::Fatal("replaced".into()),
                    Err(_) => break end,
                }
                match launcher.hosting_create_server_room(&room_spec(&meta)).await {
                    Ok((room, fresh)) => {
                        renewals += 1;
                        room_id = room.id;
                        grant = fresh;
                        launcher.server_shares.update(&id, |s| s.relay.room_id = Some(room_id.clone()));
                        // Wer schon eingeladen war, bekommt die Einladung für den neuen Raum (ohne neue Chat-Karte).
                        let invited = launcher.server_shares.lock().get(&id).map(|e| e.invited.clone()).unwrap_or_default();
                        if !invited.is_empty() {
                            let _ = launcher.hosting_invite(&room_id, &invited, false).await;
                        }
                    }
                    Err(e) => break ShareEnd::Fatal(e.code().unwrap_or("room_closed").to_owned()),
                }
            };
            if !matches!(&end, ShareEnd::Fatal(code) if code == "room_closed" || code == "replaced") {
                let _ = tokio::time::timeout(Duration::from_secs(5), launcher.hosting_close_room(&room_id)).await;
            }
            let error = match end {
                ShareEnd::Fatal(code) => Some(code),
                ShareEnd::Stopped => None,
            };
            if let Some(entry) = launcher.server_shares.lock().get_mut(&id) {
                entry.relay_stop = None;
                entry.invited.clear();
            }
            launcher.server_shares.update(&id, |s| s.relay = ChannelStatus { error, ..ChannelStatus::default() });
        });
        Ok(status)
    }

    /// Ein Relay-Raum: Host-Seite, Herzschlag und `bye` auf Direktverbindungs-Angebote,
    /// bis der Raum weg ist, ausgeschaltet wird oder der Server endet.
    async fn relay_room(
        self: &Arc<Self>,
        id: &str,
        meta: &local_servers::ServerMeta,
        room_id: &str,
        first: crate::hosting_mods::relay::RelayGrant,
        stop: watch::Receiver<bool>,
        mut exit: watch::Receiver<bool>,
    ) -> ShareEnd {
        // Angebote für Direktverbindungen sofort ablehnen (`bye`) → Gäste nehmen gleich das Relay.
        let (offers_tx, mut offers_rx) = mpsc::channel::<(String, String)>(16);
        let hook_room = room_id.to_owned();
        self.trs.live.set_signal_hook(Some(Arc::new(move |data: &str| {
            if let Some((room, from, sid)) = parse_offer(data)
                && room == hook_room
            {
                let _ = offers_tx.try_send((from, sid));
            }
        })));
        let events: ShareEvents = {
            let (shares, id) = (self.server_shares.clone(), id.to_owned());
            Arc::new(move |event| {
                shares.update(&id, |s| match event {
                    ShareEvent::RelayOnline => {
                        s.relay.state = ChannelState::Online;
                        s.relay.error = None;
                    }
                    ShareEvent::RelayOffline(code) => {
                        s.relay.state = ChannelState::Reconnecting;
                        s.relay.error = Some(code);
                    }
                    _ => {}
                });
            })
        };
        let grants: relay_host::GrantFn = {
            let (launcher, room) = (Arc::clone(self), room_id.to_owned());
            Arc::new(move || {
                let (launcher, room) = (launcher.clone(), room.clone());
                Box::pin(async move { launcher.hosting_relay_grant(&room).await })
            })
        };
        let relay = relay_host::run(grants, Some(first), target(meta.port), relay_host::Timing::default(), stop, events);
        let heartbeat = async {
            loop {
                tokio::time::sleep(HEARTBEAT_EVERY).await;
                let players = self.local_servers.status(id, meta.max_players).players.unwrap_or(0);
                match self.hosting_heartbeat(room_id, players).await {
                    Err(e) if e.code() == Some("room_not_found") => return ShareEnd::Fatal("room_closed".into()),
                    Err(e) => tracing::debug!("Relay-Raum: Herzschlag fehlgeschlagen ({})", e.kind()),
                    Ok(()) => {}
                }
            }
        };
        let byes = async {
            while let Some((from, sid)) = offers_rx.recv().await {
                let _ = self.hosting_signal_bye(room_id, &from, &sid).await;
            }
            std::future::pending::<()>().await;
        };
        let end = tokio::select! {
            end = relay => end,
            end = heartbeat => end,
            () = byes => ShareEnd::Stopped,
            _ = exit.wait_for(|done| *done) => ShareEnd::Stopped,
        };
        self.trs.live.set_signal_hook(None);
        end
    }

    /// Öffentlichen e4mc-Link einschalten.
    pub async fn local_server_share_e4mc(self: &Arc<Self>, id: &str) -> Result<ShareStatus> {
        crate::platform::desktop_only()?;
        let (meta, exit) = self.running_meta(id).await?;
        if self.server_shares.lock().get(id).is_some_and(|e| e.e4mc_stop.is_some()) {
            return Ok(self.server_shares.status(id));
        }
        let (stop_tx, stop_rx) = watch::channel(false);
        self.server_shares.lock().entry(id.to_owned()).or_default().e4mc_stop = Some(stop_tx);
        let status =
            self.server_shares.update(id, |s| s.e4mc = ChannelStatus { state: ChannelState::Connecting, ..ChannelStatus::default() });
        self.spawn_e4mc(id.to_owned(), meta.port, stop_rx, exit);
        Ok(status)
    }

    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    fn spawn_e4mc(self: &Arc<Self>, id: String, port: u16, stop: watch::Receiver<bool>, mut exit: watch::Receiver<bool>) {
        let launcher = Arc::clone(self);
        tokio::spawn(async move {
            let events: ShareEvents = {
                let (shares, id) = (launcher.server_shares.clone(), id.clone());
                Arc::new(move |event| {
                    shares.update(&id, |s| match event {
                        ShareEvent::E4mcDomain(domain) => {
                            s.e4mc.state = ChannelState::Online;
                            s.e4mc.address = Some(domain);
                            s.e4mc.error = None;
                        }
                        ShareEvent::E4mcOffline(code) => {
                            s.e4mc.state = ChannelState::Reconnecting;
                            s.e4mc.address = None;
                            s.e4mc.error = Some(code);
                        }
                        _ => {}
                    });
                })
            };
            let http = launcher.http().clone();
            tokio::select! {
                _ = e4mc_quic::run(http, target(port), stop, events) => {}
                _ = exit.wait_for(|done| *done) => {}
            }
            {
                let mut entries = launcher.server_shares.lock();
                if let Some(entry) = entries.get_mut(&id) {
                    entry.e4mc_stop = None;
                }
            }
            launcher.server_shares.update(&id, |s| s.e4mc = ChannelStatus::default());
        });
    }

    #[cfg(any(target_os = "android", target_os = "ios"))]
    fn spawn_e4mc(self: &Arc<Self>, _id: String, _port: u16, _stop: watch::Receiver<bool>, _exit: watch::Receiver<bool>) {}

    /// TRS-Freunde in den Relay-Raum des Servers einladen (mit Weltkarte im Chat).
    pub async fn local_server_invite(&self, id: &str, uuids: &[String]) -> Result<Vec<InviteOutcome>> {
        local_servers::validate_server_id(id)?;
        let room = self.server_shares.status(id).relay.room_id.ok_or_else(|| {
            Error::validation(crate::msg!("localServer.relayOff", "Schalte zuerst „Über TRS Relay teilen“ ein."))
        })?;
        let outcomes = self.hosting_invite(&room, uuids, true).await?;
        if let Some(entry) = self.server_shares.lock().get_mut(id) {
            for o in outcomes.iter().filter(|o| o.ok) {
                if !entry.invited.contains(&o.uuid) && entry.invited.len() < 50 {
                    entry.invited.push(o.uuid.clone());
                }
            }
        }
        Ok(outcomes)
    }

    /// Beim Beenden: Relay-Raum schließen (sonst läuft er erst nach 90 s ab), e4mc trennen.
    pub(crate) async fn server_shares_shutdown(&self) {
        let ids: Vec<String> = self.server_shares.lock().keys().cloned().collect();
        let room = self.server_shares.relay_owner().and_then(|(_, room)| room);
        for id in &ids {
            self.server_shares.stop(id, ShareKind::Relay);
            self.server_shares.stop(id, ShareKind::E4mc);
        }
        if let Some(room) = room {
            let _ = tokio::time::timeout(Duration::from_secs(3), self.hosting_close_room(&room)).await;
        }
    }
}
