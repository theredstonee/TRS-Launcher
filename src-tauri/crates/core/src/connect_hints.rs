//! „Schnell verbinden“: Der Launcher löst Server-Adressen vorab auf (SRV, alle
//! A/AAAA-Adressen samt TTL), misst per Happy Eyeballs (RFC 8305), welche
//! Adresse am schnellsten eine TCP-Verbindung annimmt, und legt das Ergebnis
//! vor dem Spielstart als `config/trsclient/connect-hints.json` in die Instanz.
//! Der TRS Client füllt damit nur seinen DNS-Zwischenspeicher – Namen aus der
//! Datei nimmt er nie zum Verbinden, und der Handshake-Name bleibt unverändert.
//!
//! Die Messung baut nur Verbindungen auf und schließt sie sofort wieder – es
//! wird nie ein Byte gesendet. Fehler werden nur geloggt, nie gemeldet.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use futures::StreamExt;
use futures::stream::FuturesUnordered;
use serde::Serialize;
use tokio::net::TcpStream;
use tokio::sync::Semaphore;

use crate::platform::dns::{self, AddrLookup, SrvLookup};

/// Datei relativ zum `config`-Ordner der Instanz.
pub const FILE: &str = "trsclient/connect-hints.json";
const MAX_HOSTS: usize = 64;
const MAX_IPS: usize = 16;
const MAX_FILE_BYTES: usize = 64 * 1024;
const MAX_NAME: usize = 253;
const DEFAULT_PORT: u16 = 25565;
/// TTL-Grenzen (Sekunden): höchstens 10 min, unbekannt 2 min, mindestens 5 s.
const MAX_TTL: u64 = 600;
const UNKNOWN_TTL: u64 = 120;
const MIN_TTL: u64 = 5;
/// Gleichzeitige Hintergrund-Messungen (Server-Seite, Ping-Test) höchstens.
const MAX_PROBES: usize = 4;
/// Eine Messung, die jünger ist, wird für Pings wiederverwendet.
const REUSE_MS: i64 = 30_000;
/// So lange wartet ein Ping nach seinem eigenen Ergebnis noch auf die Messung.
const PING_GRACE: Duration = Duration::from_millis(300);
/// Mehr Server misst ein Ping-Test der Instanz nicht vorab.
const MAX_BATCH: usize = 32;
/// So lange steht eine Messung des Ping-Tests höchstens an.
const BATCH_WAIT: Duration = Duration::from_secs(60);

/// Zeiten der Messung (in Tests verkürzt).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Timing {
    /// Nächster Versuch nach dieser Zeit (oder sofort, wenn einer scheitert).
    pub stagger: Duration,
    /// Höchstdauer je Verbindungsversuch.
    pub attempt: Duration,
    /// Höchstdauer der ganzen Messung (DNS + Verbindungen).
    pub overall: Duration,
    /// Nach dem Gewinner dürfen laufende Versuche noch so lange fertig werden.
    pub grace: Duration,
    /// Höchstdauer je DNS-Abfrage.
    pub dns: Duration,
}

impl Timing {
    /// Server-Seite und Ping-Test.
    pub const PING: Self = Self {
        stagger: Duration::from_millis(250),
        attempt: Duration::from_secs(3),
        overall: Duration::from_secs(5),
        grace: Duration::from_millis(250),
        dns: Duration::from_secs(2),
    };
    /// Beim Spielstart: knapp – läuft parallel zur Vorbereitung, der Start wartet höchstens so lange.
    pub const LAUNCH: Self = Self { overall: Duration::from_millis(1500), dns: Duration::from_millis(1000), ..Self::PING };
}

/// Ein Eintrag der Datei (plus Messwerte für die Anzeige).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostHint {
    /// Abgefragter Name; SRV-Ziele mit Schlusspunkt wie aus dem DNS.
    pub name: String,
    /// `None` = nicht geprüft, `""` = sicher kein SRV, sonst "prio gewicht port ziel.".
    pub srv: Option<String>,
    /// Nur IP-Adressen: schnellste zuerst, dann verbundene nach Zeit, dann der Rest.
    pub ips: Vec<IpAddr>,
    pub fastest: Option<IpAddr>,
    /// Verbindungszeit der schnellsten Adresse.
    pub connect_ms: Option<u32>,
    /// Wanduhr in Millisekunden.
    pub measured_ms: i64,
    pub expires_ms: i64,
}

/// Kurzfassung für die Server-Karte („Vorab aufgelöst · IPv6 · 23 ms“).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FastConnect {
    /// `ipv4` | `ipv6`
    pub family: &'static str,
    pub connect_ms: u32,
    /// Bekannte Adressen des Ziels.
    pub addresses: u32,
}

/// Ablauf = Messzeit + min(TTL, 10 min); unbekannte TTL → 2 min; mindestens 5 s.
pub fn expires_at(measured_ms: i64, ttl: Option<u32>) -> i64 {
    let seconds = ttl.map_or(UNKNOWN_TTL, |t| u64::from(t).min(MAX_TTL)).max(MIN_TTL);
    measured_ms.saturating_add(seconds as i64 * 1000)
}

fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

/// DNS-Name, wie er in die Datei darf: ≤ 253 Zeichen, nur `[A-Za-z0-9._-]`.
pub fn valid_name(name: &str) -> bool {
    let bare = name.strip_suffix('.').unwrap_or(name);
    !bare.is_empty()
        && name.len() <= MAX_NAME
        && !bare.starts_with('.')
        && !bare.contains("..")
        && name.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
}

// --- DNS ------------------------------------------------------------------------------------

/// Namensauflösung (blockierend; läuft in `spawn_blocking`). In Tests ersetzbar.
pub trait Resolver: Send + Sync {
    fn srv(&self, name: &str) -> SrvLookup;
    fn addrs(&self, host: &str, v6: bool) -> AddrLookup;
    /// Rückfall über den Resolver des Systems (Hosts-Datei usw.) ohne TTL.
    fn system(&self) -> bool {
        true
    }
}

/// Windows: `DnsQuery_W` (echte TTL), Linux: eigene UDP-Anfrage (echte TTL).
pub struct SystemResolver;

impl Resolver for SystemResolver {
    fn srv(&self, name: &str) -> SrvLookup {
        crate::platform::lookup_srv_records(name)
    }

    fn addrs(&self, host: &str, v6: bool) -> AddrLookup {
        crate::platform::lookup_addrs(host, v6)
    }
}

async fn blocking<T: Send + 'static>(limit: Duration, work: impl FnOnce() -> T + Send + 'static) -> Option<T> {
    tokio::time::timeout(limit, tokio::task::spawn_blocking(work)).await.ok()?.ok()
}

/// Adressen eines Namens: AAAA und A gleichzeitig, abwechselnd sortiert (IPv6
/// zuerst, RFC 8305), höchstens 16. TTL = kleinste bekannte.
async fn resolve_host(resolver: &Arc<dyn Resolver>, host: &str, limit: Duration) -> (Vec<IpAddr>, Option<u32>) {
    let query = |v6: bool| {
        let resolver = resolver.clone();
        let host = host.to_owned();
        async move { blocking(limit, move || resolver.addrs(&host, v6)).await.unwrap_or(AddrLookup::Unknown) }
    };
    let (v6, v4) = tokio::join!(query(true), query(false));
    let mut ttl: Option<u32> = None;
    let mut split = |lookup: AddrLookup| match lookup {
        AddrLookup::Found(ips, t) => {
            ttl = Some(ttl.map_or(t, |old| old.min(t)));
            ips
        }
        AddrLookup::None | AddrLookup::Unknown => Vec::new(),
    };
    let (v6, v4) = (split(v6), split(v4));
    let mut ips = interleave(v6, v4);
    if ips.is_empty() && resolver.system() {
        // z. B. Hosts-Datei oder mDNS: dann eben ohne TTL.
        let lookup = tokio::net::lookup_host((host.to_owned(), 0u16));
        if let Ok(Ok(found)) = tokio::time::timeout(limit, lookup).await {
            let (v6, v4): (Vec<IpAddr>, Vec<IpAddr>) = found.map(|a| a.ip()).partition(IpAddr::is_ipv6);
            ips = interleave(v6, v4);
            ttl = None;
        }
    }
    (ips, ttl)
}

/// Abwechselnd IPv6/IPv4 (mit IPv6 beginnend), ohne Doppelte, höchstens 16.
pub fn interleave(v6: Vec<IpAddr>, v4: Vec<IpAddr>) -> Vec<IpAddr> {
    let mut out: Vec<IpAddr> = Vec::new();
    let (mut a, mut b) = (v6.into_iter(), v4.into_iter());
    loop {
        let (x, y) = (a.next(), b.next());
        if x.is_none() && y.is_none() {
            break;
        }
        for ip in [x, y].into_iter().flatten() {
            if !out.contains(&ip) && out.len() < MAX_IPS {
                out.push(ip);
            }
        }
    }
    out
}

// --- Messung --------------------------------------------------------------------------------

/// Ergebnis des Verbindungs-Wettlaufs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Race {
    /// Verbindungszeit je Adresse (`None` = gescheitert oder nicht versucht).
    pub times: Vec<Option<Duration>>,
    /// Index der Adresse, die zuerst verbunden hat.
    pub winner: Option<usize>,
}

/// Happy Eyeballs (RFC 8305): erster Versuch sofort, der nächste nach
/// `stagger` oder sofort, wenn einer scheitert. Nach dem Gewinner startet
/// nichts Neues mehr; laufende Versuche bekommen noch `grace`. Verbindungen
/// werden sofort wieder geschlossen – gesendet wird nichts.
pub async fn race(addrs: &[SocketAddr], timing: &Timing) -> Race {
    let mut times = vec![None; addrs.len()];
    let mut winner = None;
    if addrs.is_empty() {
        return Race { times, winner };
    }
    let started = Instant::now();
    let deadline = tokio::time::Instant::from_std(started + timing.overall);
    let attempt_limit = timing.attempt;
    let attempt = |index: usize| {
        let addr = addrs[index];
        async move {
            let begin = Instant::now();
            let result = match tokio::time::timeout(attempt_limit, TcpStream::connect(addr)).await {
                // Nur messen: Verbindung sofort schließen.
                Ok(Ok(stream)) => {
                    drop(stream);
                    Some(begin.elapsed())
                }
                _ => None,
            };
            (index, result)
        }
    };
    let mut pending = FuturesUnordered::new();
    pending.push(attempt(0));
    let mut next = 1;
    let mut next_start = tokio::time::Instant::now() + timing.stagger;
    let mut stop_at = deadline;
    loop {
        let can_start = winner.is_none() && next < addrs.len();
        if pending.is_empty() {
            if !can_start {
                break;
            }
            pending.push(attempt(next));
            next += 1;
            next_start = tokio::time::Instant::now() + timing.stagger;
            continue;
        }
        tokio::select! {
            Some((index, result)) = pending.next() => {
                times[index] = result;
                if result.is_some() {
                    if winner.is_none() {
                        winner = Some(index);
                        stop_at = deadline.min(tokio::time::Instant::now() + timing.grace);
                    }
                } else if can_start {
                    pending.push(attempt(next));
                    next += 1;
                    next_start = tokio::time::Instant::now() + timing.stagger;
                }
            }
            _ = tokio::time::sleep_until(next_start), if can_start => {
                pending.push(attempt(next));
                next += 1;
                next_start = tokio::time::Instant::now() + timing.stagger;
            }
            _ = tokio::time::sleep_until(stop_at) => break,
        }
    }
    Race { times, winner }
}

/// Reihenfolge für die Datei: Gewinner, dann übrige verbundene nach Zeit,
/// dann der Rest in Resolver-Reihenfolge.
pub fn order_ips(ips: &[IpAddr], race: &Race) -> Vec<IpAddr> {
    let mut connected: Vec<(usize, Duration)> =
        race.times.iter().enumerate().filter_map(|(i, t)| t.map(|t| (i, t))).filter(|(i, _)| Some(*i) != race.winner).collect();
    connected.sort_by_key(|(_, t)| *t);
    let mut order: Vec<usize> = race.winner.into_iter().chain(connected.into_iter().map(|(i, _)| i)).collect();
    let rest: Vec<usize> = (0..ips.len()).filter(|i| !order.contains(i)).collect();
    order.extend(rest);
    order.into_iter().filter_map(|i| ips.get(i).copied()).collect()
}

fn family(ip: &IpAddr) -> &'static str {
    if ip.is_ipv6() { "ipv6" } else { "ipv4" }
}

/// Löst `address` auf und misst. `None` = nichts Brauchbares (IP-Adresse,
/// ungültig, DNS gescheitert). Die Gesamtdauer begrenzt `timing.overall`.
pub async fn probe(resolver: Arc<dyn Resolver>, address: &str, timing: Timing) -> Option<Vec<HostHint>> {
    let (host, port) = crate::servers::parse_address(address).ok()?;
    // IP-Adressen braucht niemand aufzulösen.
    if host.parse::<IpAddr>().is_ok() || !valid_name(&host) {
        return None;
    }
    let started = Instant::now();
    let remaining = |cap: Duration| timing.overall.saturating_sub(started.elapsed()).min(cap);

    // SRV nur ohne Port oder mit 25565 – wie Vanilla.
    let srv_wanted = matches!(port, None | Some(DEFAULT_PORT));
    let srv_query = async {
        if !srv_wanted {
            return None;
        }
        let resolver = resolver.clone();
        let name = format!("_minecraft._tcp.{host}");
        Some(blocking(remaining(timing.dns), move || resolver.srv(&name)).await.unwrap_or(SrvLookup::Unknown))
    };
    let ((srv, srv_ttl), (own_ips, own_ttl)) = tokio::join!(
        async {
            match srv_query.await {
                Some(lookup) => srv_choice(lookup),
                None => (None, None),
            }
        },
        resolve_host(&resolver, &host, remaining(timing.dns))
    );

    let mut hosts = Vec::new();
    let mut own = HostHint {
        name: host.clone(),
        srv: srv.as_ref().map(|s| s.line.clone()).or_else(|| (srv_ttl == Some(0)).then(String::new)),
        ips: own_ips,
        fastest: None,
        connect_ms: None,
        measured_ms: 0,
        expires_ms: 0,
    };
    let own_ttl = min_ttl(own_ttl, srv_ttl.filter(|&t| t > 0));

    // Ziel der Verbindung: SRV-Ziel oder der Name selbst.
    let (target_ips, target_ttl, connect_port) = match &srv {
        Some(choice) if choice.target_key != host => {
            let (ips, ttl) = resolve_host(&resolver, &choice.target_key, remaining(timing.dns)).await;
            (ips, ttl, choice.port)
        }
        Some(choice) => (own.ips.clone(), own_ttl, choice.port),
        None => (own.ips.clone(), own_ttl, port.unwrap_or(DEFAULT_PORT)),
    };
    let addrs: Vec<SocketAddr> = target_ips.iter().map(|ip| SocketAddr::new(*ip, connect_port)).collect();
    let race_timing = Timing { overall: remaining(timing.overall), ..timing };
    let result = race(&addrs, &race_timing).await;
    let measured = now_ms();
    let measured_hint = |name: String, srv: Option<String>, ttl: Option<u32>| HostHint {
        name,
        srv,
        ips: order_ips(&target_ips, &result),
        fastest: result.winner.and_then(|i| target_ips.get(i).copied()),
        connect_ms: result.winner.and_then(|i| result.times[i]).map(|t| t.as_millis().min(60_000) as u32),
        measured_ms: measured,
        expires_ms: expires_at(measured, ttl),
    };

    match &srv {
        Some(choice) if choice.target_key != host => {
            own.measured_ms = measured;
            own.expires_ms = expires_at(measured, own_ttl);
            if !target_ips.is_empty() {
                hosts.push(measured_hint(choice.target_name.clone(), None, target_ttl));
            }
            if own.srv.is_some() || !own.ips.is_empty() {
                hosts.insert(0, own);
            }
        }
        _ => {
            if own.srv.is_some() || !target_ips.is_empty() {
                hosts.push(measured_hint(own.name.clone(), own.srv.clone(), own_ttl));
            }
        }
    }
    if hosts.is_empty() { None } else { Some(hosts) }
}

fn min_ttl(a: Option<u32>, b: Option<u32>) -> Option<u32> {
    match (a, b) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (a, b) => a.or(b),
    }
}

/// Gewählter SRV-Eintrag.
struct SrvChoice {
    /// "prio gewicht port ziel." für die Datei.
    line: String,
    /// Ziel wie Vanilla es sieht (mit Schlusspunkt).
    target_name: String,
    /// Ziel ohne Schlusspunkt, klein – zum Auflösen.
    target_key: String,
    port: u16,
}

/// SRV-Antwort → (Wahl, TTL). TTL `Some(0)` heißt „sicher keiner“ (Feld `""`),
/// `None` ohne Wahl = nicht feststellbar (Feld fehlt).
fn srv_choice(lookup: SrvLookup) -> (Option<SrvChoice>, Option<u32>) {
    match lookup {
        SrvLookup::Found(records, ttl) => {
            let Some(best) = dns::pick_srv(&records) else { return (None, None) };
            let bare = best.target.trim_end_matches('.');
            // Ziel kommt aus dem DNS – gleiche Regeln wie für Nutzereingaben.
            if crate::servers::parse_address(bare).is_err() || !valid_name(bare) {
                return (None, None);
            }
            // Vanilla (JNDI) liefert das Ziel mit Schlusspunkt und schickt es so im Handshake.
            let target_name = format!("{bare}.");
            let line = format!("{} {} {} {target_name}", best.priority, best.weight, best.port);
            let choice = SrvChoice { line, target_key: bare.to_ascii_lowercase(), target_name, port: best.port };
            (Some(choice), Some(ttl.max(1)))
        }
        SrvLookup::None => (None, Some(0)),
        SrvLookup::Unknown => (None, None),
    }
}

// --- Speicher -------------------------------------------------------------------------------

/// Frische Einträge im Speicher (Schlüssel: Name klein), höchstens 64.
pub struct HintStore {
    entries: Mutex<HashMap<String, HostHint>>,
    resolver: Arc<dyn Resolver>,
    probes: Arc<Semaphore>,
}

impl Default for HintStore {
    fn default() -> Self {
        Self::new(Arc::new(SystemResolver))
    }
}

impl HintStore {
    pub fn new(resolver: Arc<dyn Resolver>) -> Self {
        Self { entries: Mutex::default(), resolver, probes: Arc::new(Semaphore::new(MAX_PROBES)) }
    }

    /// Einträge übernehmen; Abgelaufenes fliegt raus, über 64 die ältesten Messungen.
    pub fn insert(&self, hints: Vec<HostHint>, now: i64) {
        let mut entries = self.entries.lock().unwrap_or_else(PoisonError::into_inner);
        for mut hint in hints {
            if !valid_name(&hint.name) {
                continue;
            }
            hint.ips.truncate(MAX_IPS);
            entries.insert(hint.name.to_ascii_lowercase(), hint);
        }
        entries.retain(|_, h| h.expires_ms > now);
        while entries.len() > MAX_HOSTS {
            let Some(oldest) = entries.iter().min_by_key(|(_, h)| (h.measured_ms, h.expires_ms)).map(|(k, _)| k.clone()) else {
                break;
            };
            entries.remove(&oldest);
        }
    }

    /// Alle noch gültigen Einträge, neueste Messung zuerst.
    pub fn fresh(&self, now: i64) -> Vec<HostHint> {
        let mut entries = self.entries.lock().unwrap_or_else(PoisonError::into_inner);
        entries.retain(|_, h| h.expires_ms > now);
        let mut list: Vec<HostHint> = entries.values().cloned().collect();
        list.sort_by(|a, b| b.measured_ms.cmp(&a.measured_ms).then_with(|| a.name.cmp(&b.name)));
        list
    }

    fn get(&self, name: &str, now: i64) -> Option<HostHint> {
        let entries = self.entries.lock().unwrap_or_else(PoisonError::into_inner);
        entries.get(&name.to_ascii_lowercase()).filter(|h| h.expires_ms > now).cloned()
    }

    /// Kurzfassung für `host` (folgt dem SRV-Ziel), nur wenn frisch gemessen.
    pub fn summary(&self, address: &str, now: i64) -> Option<FastConnect> {
        let (host, _) = crate::servers::parse_address(address).ok()?;
        let own = self.get(&host, now)?;
        if now - own.measured_ms > REUSE_MS {
            return None;
        }
        let target = match own.srv.as_deref().and_then(|s| s.split_whitespace().nth(3)) {
            // SRV, das auf den Namen selbst zeigt, steht nur einmal im Speicher.
            Some(target) if target.trim_end_matches('.').eq_ignore_ascii_case(&host) => own,
            Some(target) => self.get(target, now)?,
            None => own,
        };
        let fastest = target.fastest?;
        Some(FastConnect {
            family: family(&fastest),
            connect_ms: target.connect_ms?,
            addresses: target.ips.len() as u32,
        })
    }

    /// Messen und eintragen (ohne Warteschlange – für den Spielstart).
    pub async fn refresh(&self, address: &str, timing: Timing) -> Option<FastConnect> {
        let hints = probe(self.resolver.clone(), address, timing).await?;
        tracing::debug!("Schnell verbinden: {} Einträge für {address}", hints.len());
        let now = now_ms();
        self.insert(hints, now);
        self.summary(address, now)
    }

    /// Im Hintergrund messen (höchstens [`MAX_PROBES`] gleichzeitig); eine
    /// frische Messung wird wiederverwendet.
    pub fn spawn_refresh(self: &Arc<Self>, address: &str, timing: Timing) -> tokio::task::JoinHandle<Option<FastConnect>> {
        let store = Arc::clone(self);
        let address = address.to_owned();
        tokio::spawn(async move { store.refresh_queued(&address, timing, timing.overall).await })
    }

    /// Spielstart: sofort frisch messen, ohne Warteschlange (begrenzt durch [`Timing::LAUNCH`]).
    pub fn spawn_launch(self: &Arc<Self>, address: &str) -> tokio::task::JoinHandle<Option<FastConnect>> {
        let store = Arc::clone(self);
        let address = address.to_owned();
        tokio::spawn(async move { store.refresh(&address, Timing::LAUNCH).await })
    }

    /// Wie [`Self::refresh`], aber mit Warteschlange; `wait` = so lange höchstens anstehen.
    async fn refresh_queued(&self, address: &str, timing: Timing, wait: Duration) -> Option<FastConnect> {
        if let Some(summary) = self.summary(address, now_ms()) {
            return Some(summary);
        }
        let _permit = tokio::time::timeout(wait, self.probes.acquire()).await.ok()?.ok()?;
        self.refresh(address, timing).await
    }

    /// Ergebnis eines Pings mit Messung: wartet nach dem Ping höchstens kurz.
    pub async fn with_ping<F: std::future::Future>(self: &Arc<Self>, address: &str, ping: F) -> (F::Output, Option<FastConnect>) {
        let mut task = self.spawn_refresh(address, Timing::PING);
        let output = ping.await;
        // Läuft die Messung noch, darf sie im Hintergrund fertig werden.
        let summary = tokio::time::timeout(PING_GRACE, &mut task).await.ok().and_then(Result::ok).flatten();
        (output, summary)
    }

    /// Ping-Test einer Instanz: alle (höchstens 32) im Hintergrund vormessen.
    pub fn spawn_batch(self: &Arc<Self>, addresses: Vec<String>) {
        let store = Arc::clone(self);
        tokio::spawn(async move {
            futures::stream::iter(addresses.into_iter().take(MAX_BATCH))
                .for_each_concurrent(MAX_PROBES, |address| {
                    let store = &store;
                    async move {
                        store.refresh_queued(&address, Timing::PING, BATCH_WAIT).await;
                    }
                })
                .await;
        });
    }

    /// Inhalt von `connect-hints.json` aus allen frischen Einträgen.
    pub fn file_json(&self, join: Option<&str>) -> Option<String> {
        let mut hosts = self.fresh(now_ms());
        // Der Server dieses Starts (und sein SRV-Ziel) zuerst – falls gekürzt wird.
        if let Some((host, _)) = join.and_then(|j| crate::servers::parse_address(j).ok()) {
            let target = hosts
                .iter()
                .find(|h| h.name.eq_ignore_ascii_case(&host))
                .and_then(|h| h.srv.as_deref()?.split_whitespace().nth(3).map(str::to_ascii_lowercase));
            hosts.sort_by_key(|h| {
                let name = h.name.to_ascii_lowercase();
                !(name == host || Some(&name) == target.as_ref())
            });
        }
        file_json(join, &hosts)
    }
}

// --- Datei ----------------------------------------------------------------------------------

#[derive(Serialize)]
struct FileJson<'a> {
    version: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    join: Option<&'a str>,
    hosts: Vec<HostJson<'a>>,
}

#[derive(Serialize)]
struct HostJson<'a> {
    name: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    srv: Option<&'a str>,
    ips: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    fastest: Option<String>,
    expires: i64,
}

/// `join` so, wie die Mod es annimmt (Adresse wie eingegeben).
fn valid_join(join: &str) -> bool {
    !join.is_empty()
        && join.len() <= 261
        && join.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_' | b':' | b'[' | b']'))
}

/// Datei nach Vertrag (Version 1): höchstens 64 Hosts, 16 IPs je Host, ≤ 64 KiB.
pub fn file_json(join: Option<&str>, hosts: &[HostHint]) -> Option<String> {
    let valid: Vec<&HostHint> = hosts.iter().filter(|h| valid_name(&h.name)).take(MAX_HOSTS).collect();
    let join = join.map(str::trim).filter(|j| valid_join(j));
    let mut count = valid.len();
    loop {
        let list = valid[..count]
            .iter()
            .map(|h| {
                let ips: Vec<IpAddr> = h.ips.iter().take(MAX_IPS).copied().collect();
                HostJson {
                    name: &h.name,
                    srv: h.srv.as_deref(),
                    fastest: h.fastest.filter(|f| ips.contains(f)).map(|f| f.to_string()),
                    ips: ips.iter().map(IpAddr::to_string).collect(),
                    expires: h.expires_ms,
                }
            })
            .collect();
        let text = serde_json::to_string_pretty(&FileJson { version: 1, join, hosts: list }).ok()?;
        // Zu groß (nur mit sehr vielen langen Namen): hinten kürzen.
        if text.len() <= MAX_FILE_BYTES || count == 0 {
            return Some(text);
        }
        count -= 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::platform::dns::SrvRecord;
    use std::net::Ipv4Addr;

    const FAST: Timing = Timing {
        stagger: Duration::from_millis(40),
        attempt: Duration::from_millis(300),
        overall: Duration::from_millis(1500),
        grace: Duration::from_millis(50),
        dns: Duration::from_millis(500),
    };

    fn ip(s: &str) -> IpAddr {
        s.parse().unwrap()
    }

    fn hint(name: &str, measured: i64, expires: i64) -> HostHint {
        HostHint {
            name: name.into(),
            srv: None,
            ips: vec![ip("203.0.113.7")],
            fastest: None,
            connect_ms: None,
            measured_ms: measured,
            expires_ms: expires,
        }
    }

    /// Feste DNS-Antworten statt echtem Netz.
    struct Fake {
        srv: SrvLookup,
        hosts: HashMap<String, Vec<IpAddr>>,
    }

    impl Resolver for Fake {
        fn srv(&self, _name: &str) -> SrvLookup {
            self.srv.clone()
        }
        fn addrs(&self, host: &str, v6: bool) -> AddrLookup {
            let ips: Vec<IpAddr> = self.hosts.get(host).into_iter().flatten().copied().filter(|i| i.is_ipv6() == v6).collect();
            if ips.is_empty() { AddrLookup::None } else { AddrLookup::Found(ips, 300) }
        }
        fn system(&self) -> bool {
            false
        }
    }

    #[test]
    fn expiry_follows_ttl_with_caps() {
        assert_eq!(expires_at(1_000, Some(60)), 61_000);
        assert_eq!(expires_at(1_000, Some(86_400)), 601_000, "höchstens 10 min");
        assert_eq!(expires_at(1_000, None), 121_000, "unbekannt = 2 min");
        assert_eq!(expires_at(1_000, Some(0)), 6_000, "mindestens 5 s");
        assert_eq!(expires_at(i64::MAX, Some(60)), i64::MAX);
    }

    #[test]
    fn srv_line_keeps_target_with_dot() {
        let records = vec![
            SrvRecord { priority: 10, weight: 99, port: 1, target: "late.example.net".into() },
            SrvRecord { priority: 0, weight: 5, port: 25577, target: "mc.example.net".into() },
            SrvRecord { priority: 0, weight: 5, port: 25578, target: "tie.example.net".into() },
        ];
        let (choice, ttl) = srv_choice(SrvLookup::Found(records, 300));
        let choice = choice.unwrap();
        assert_eq!(choice.line, "0 5 25577 mc.example.net.");
        assert_eq!((choice.target_name.as_str(), choice.target_key.as_str(), choice.port), ("mc.example.net.", "mc.example.net", 25577));
        assert_eq!(ttl, Some(300));
        assert!(matches!(srv_choice(SrvLookup::None), (None, Some(0))));
        assert!(matches!(srv_choice(SrvLookup::Unknown), (None, None)));
        // Ziel aus dem DNS mit Unsinn: lieber gar kein Hinweis.
        let bad = vec![SrvRecord { priority: 0, weight: 0, port: 25565, target: "x.de;calc".into() }];
        assert!(matches!(srv_choice(SrvLookup::Found(bad, 60)), (None, None)));
    }

    #[test]
    fn names_are_validated() {
        assert!(valid_name("play.example.net"));
        assert!(valid_name("mc.example.net."));
        assert!(valid_name("_minecraft._tcp.x.de"));
        for bad in ["", ".", "a..b", ".a", "a b", "x.de;calc", "ä.de", &"a".repeat(254)] {
            assert!(!valid_name(bad), "{bad:?}");
        }
    }

    #[test]
    fn interleaves_families() {
        let v6 = vec![ip("2001:db8::1"), ip("2001:db8::2"), ip("2001:db8::3")];
        let v4 = vec![ip("203.0.113.1"), ip("203.0.113.1")];
        assert_eq!(interleave(v6, v4), vec![ip("2001:db8::1"), ip("203.0.113.1"), ip("2001:db8::2"), ip("2001:db8::3")]);
        let many: Vec<IpAddr> = (0..40).map(|i| IpAddr::V4(Ipv4Addr::new(10, 0, 0, i))).collect();
        assert_eq!(interleave(Vec::new(), many).len(), MAX_IPS);
    }

    #[test]
    fn ordering_by_measurement() {
        let ips = [ip("2001:db8::1"), ip("203.0.113.1"), ip("2001:db8::2"), ip("203.0.113.2")];
        let ms = Duration::from_millis;
        // Gewinner zuerst (auch wenn ein anderer schneller verband), dann nach Zeit, dann der Rest.
        let race = Race { times: vec![None, Some(ms(30)), Some(ms(20)), Some(ms(10))], winner: Some(1) };
        assert_eq!(order_ips(&ips, &race), vec![ips[1], ips[3], ips[2], ips[0]]);
        let none = Race { times: vec![None; 4], winner: None };
        assert_eq!(order_ips(&ips, &none), ips.to_vec());
    }

    #[test]
    fn json_matches_the_contract() {
        let hosts = vec![
            HostHint {
                name: "play.example.net".into(),
                srv: Some("0 5 25577 mc.example.net.".into()),
                ips: vec![ip("203.0.113.7")],
                fastest: None,
                connect_ms: None,
                measured_ms: 1,
                expires_ms: 1_759_500_600_000,
            },
            HostHint {
                name: "mc.example.net.".into(),
                srv: None,
                ips: vec![ip("203.0.113.7"), ip("2001:db8::7")],
                fastest: Some(ip("203.0.113.7")),
                connect_ms: Some(23),
                measured_ms: 1,
                expires_ms: 1_759_500_600_000,
            },
            HostHint { name: "none.example.net".into(), srv: Some(String::new()), ..hint("x", 1, 5) },
            HostHint { name: "bad name".into(), ..hint("x", 1, 5) },
        ];
        let json: serde_json::Value = serde_json::from_str(&file_json(Some(" play.example.net "), &hosts).unwrap()).unwrap();
        assert_eq!(
            json,
            serde_json::json!({
                "version": 1,
                "join": "play.example.net",
                "hosts": [
                    { "name": "play.example.net", "srv": "0 5 25577 mc.example.net.", "ips": ["203.0.113.7"], "expires": 1_759_500_600_000_i64 },
                    { "name": "mc.example.net.", "ips": ["203.0.113.7", "2001:db8::7"], "fastest": "203.0.113.7", "expires": 1_759_500_600_000_i64 },
                    { "name": "none.example.net", "srv": "", "ips": ["203.0.113.7"], "expires": 5 }
                ]
            })
        );
        // Ohne Beitritt fehlt `join`; ungültiges `join` ebenso.
        for join in [None, Some("x.de;calc"), Some("")] {
            let text = file_json(join, &hosts).unwrap();
            assert!(!text.contains("\"join\""), "{text}");
        }
        // Grenzen: höchstens 64 Hosts, 16 IPs je Host, ≤ 64 KiB.
        let many: Vec<HostHint> = (0..100u16)
            .map(|i| HostHint {
                name: format!("{}{i}.example.net", "a".repeat(200)),
                ips: (0..20).map(|n| IpAddr::V6(std::net::Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, i, n))).collect(),
                fastest: Some(IpAddr::V6(std::net::Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, i, 19))),
                ..hint("x", 1, 5)
            })
            .collect();
        let text = file_json(None, &many).unwrap();
        assert!(text.len() <= MAX_FILE_BYTES);
        let parsed: serde_json::Value = serde_json::from_str(&text).unwrap();
        let list = parsed["hosts"].as_array().unwrap();
        assert!(!list.is_empty() && list.len() <= MAX_HOSTS);
        assert!(list.iter().all(|h| h["ips"].as_array().unwrap().len() == MAX_IPS));
        // Schnellste Adresse jenseits der 16 fällt weg (nur IPs aus der Liste).
        assert!(list.iter().all(|h| h.get("fastest").is_none()));
    }

    #[test]
    fn store_prunes_and_caps() {
        let store = HintStore::new(Arc::new(Fake { srv: SrvLookup::Unknown, hosts: HashMap::new() }));
        store.insert(vec![hint("Old.Example.net", 1, 100), hint("new.example.net", 2, 10_000), hint("bad name", 3, 10_000)], 50);
        assert_eq!(store.fresh(50).len(), 2);
        // Abgelaufenes fliegt raus, Groß-/Kleinschreibung ist egal.
        assert_eq!(store.fresh(200).iter().map(|h| h.name.as_str()).collect::<Vec<_>>(), ["new.example.net"]);
        store.insert(vec![hint("NEW.example.net", 5, 20_000)], 200);
        assert_eq!(store.fresh(200).len(), 1);
        let lots: Vec<HostHint> = (0..100).map(|i| hint(&format!("h{i}.example.net"), 100 + i, 50_000)).collect();
        store.insert(lots, 300);
        let fresh = store.fresh(300);
        assert_eq!(fresh.len(), MAX_HOSTS);
        // Die ältesten Messungen gehen zuerst; neueste stehen vorn.
        assert_eq!(fresh[0].name, "h99.example.net");
        assert!(fresh.iter().all(|h| h.measured_ms >= 136));
    }

    #[tokio::test]
    async fn race_prefers_the_listening_address() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let open = listener.local_addr().unwrap();
        let closed = {
            let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap()
        };
        // Gescheiterter Versuch startet den nächsten sofort (nicht erst nach `stagger`).
        let slow_stagger = Timing { stagger: Duration::from_secs(30), overall: Duration::from_secs(20), attempt: Duration::from_secs(10), ..FAST };
        let started = Instant::now();
        let result = race(&[closed, open], &slow_stagger).await;
        assert!(started.elapsed() < Duration::from_secs(5), "{:?}", started.elapsed());
        assert_eq!(result.winner, Some(1));
        assert!(result.times[0].is_none() && result.times[1].is_some());
        let ips = [closed.ip(), open.ip()];
        assert_eq!(order_ips(&ips, &result)[0], open.ip());
        // Nur verbinden, nie senden: die Gegenseite sieht eine Verbindung ohne Daten.
        let (mut peer, _) = tokio::time::timeout(Duration::from_secs(2), listener.accept()).await.unwrap().unwrap();
        let mut buf = [0u8; 8];
        let read = tokio::time::timeout(Duration::from_secs(2), tokio::io::AsyncReadExt::read(&mut peer, &mut buf)).await;
        assert!(matches!(read, Ok(Ok(0)) | Ok(Err(_))), "{read:?}");
        assert!(race(&[], &FAST).await.winner.is_none());
    }

    #[tokio::test]
    async fn race_times_out_on_unroutable_addresses() {
        // TEST-NET-1 (RFC 5737): nie erreichbar – je nach Netz Zeitüberschreitung oder sofortiger Fehler.
        let dead: SocketAddr = "192.0.2.1:25565".parse().unwrap();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let open = listener.local_addr().unwrap();
        let started = Instant::now();
        let result = race(&[dead, open], &FAST).await;
        assert!(started.elapsed() < Duration::from_secs(2), "{:?}", started.elapsed());
        assert_eq!(result.winner, Some(1));
        assert!(result.times[0].is_none());

        let started = Instant::now();
        let result = race(&[dead, "192.0.2.2:25565".parse().unwrap()], &FAST).await;
        assert!(started.elapsed() < FAST.overall + Duration::from_millis(500), "{:?}", started.elapsed());
        assert_eq!(result.winner, None);
    }

    #[tokio::test]
    async fn probe_follows_srv_and_measures_target() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let fake = Fake {
            srv: SrvLookup::Found(vec![SrvRecord { priority: 0, weight: 5, port, target: "MC.Example.net".into() }], 300),
            hosts: HashMap::from([
                ("play.example.net".to_owned(), vec![ip("192.0.2.9")]),
                ("mc.example.net".to_owned(), vec![ip("127.0.0.1")]),
            ]),
        };
        let store = Arc::new(HintStore::new(Arc::new(fake)));
        let summary = store.refresh("Play.Example.net", FAST).await.unwrap();
        assert_eq!((summary.family, summary.addresses), ("ipv4", 1));
        let hosts = store.fresh(now_ms());
        let own = hosts.iter().find(|h| h.name == "play.example.net").unwrap();
        assert_eq!(own.srv.as_deref(), Some(format!("0 5 {port} MC.Example.net.").as_str()));
        assert_eq!((own.ips.clone(), own.fastest), (vec![ip("192.0.2.9")], None), "nicht gemessen – das Spiel nimmt das SRV-Ziel");
        let target = hosts.iter().find(|h| h.name == "MC.Example.net.").unwrap();
        assert_eq!((target.srv.clone(), target.fastest), (None, Some(ip("127.0.0.1"))));
        assert!(target.expires_ms > now_ms() + 250_000 && target.expires_ms <= now_ms() + 300_000);
        // Die Datei nennt den Beitritt und beide Namen.
        let json: serde_json::Value = serde_json::from_str(&store.file_json(Some("Play.Example.net")).unwrap()).unwrap();
        assert_eq!(json["join"], "Play.Example.net");
        assert_eq!(json["hosts"].as_array().unwrap().len(), 2);
        // Gleich danach wiederverwendet statt neu gemessen.
        assert_eq!(store.summary("play.example.net", now_ms()), Some(summary));
    }

    #[tokio::test]
    async fn probe_marks_missing_or_skipped_srv() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let hosts = HashMap::from([("plain.example.net".to_owned(), vec![ip("127.0.0.1")])]);
        let resolver: Arc<dyn Resolver> = Arc::new(Fake { srv: SrvLookup::None, hosts });
        // Ohne Port: SRV geprüft und sicher keiner → "".
        let no_srv = probe(resolver.clone(), "plain.example.net", FAST).await.unwrap();
        assert_eq!((no_srv.len(), no_srv[0].srv.as_deref()), (1, Some("")));
        // Eigener Port: kein SRV-Feld, gemessen auf diesem Port.
        let with_port = probe(resolver.clone(), &format!("plain.example.net:{port}"), FAST).await.unwrap();
        assert_eq!((with_port[0].srv.clone(), with_port[0].fastest), (None, Some(ip("127.0.0.1"))));
        let json = file_json(None, &with_port).unwrap();
        assert!(!json.contains("\"srv\""), "{json}");
        // IP-Adressen und Ungültiges brauchen keine Hinweise.
        assert!(probe(resolver.clone(), "127.0.0.1:25565", FAST).await.is_none());
        assert!(probe(resolver.clone(), "x.de;calc", FAST).await.is_none());
        assert!(probe(resolver.clone(), "unknown.example.net:25570", FAST).await.is_none());
    }
}
