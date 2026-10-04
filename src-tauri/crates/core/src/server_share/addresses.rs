//! Wie man einen lokalen Server erreicht: Adresse im Heimnetz (LAN) und öffentliche IP.
//!
//! LAN: die Quell-Adresse, über die dieser PC ins Internet routet (UDP-`connect` schickt
//! dabei kein Paket). Öffentlich: ipify, sonst Cloudflare-Trace – 10 Minuten gemerkt.

use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::Mutex;
use std::time::{Duration, Instant};

const PUBLIC_IP_TTL: Duration = Duration::from_secs(10 * 60);
const PUBLIC_IP_SOURCES: [&str; 2] = ["https://api.ipify.org", "https://1.1.1.1/cdn-cgi/trace"];

static PUBLIC_IP: Mutex<Option<(Instant, IpAddr)>> = Mutex::new(None);

/// `host:port` wie im Minecraft-Feld „Serveradresse“ (IPv6 in eckigen Klammern).
pub fn display(ip: IpAddr, port: u16) -> String {
    SocketAddr::new(ip, port).to_string()
}

/// Private Adresse im Heimnetz (RFC 1918) – nicht Loopback, nicht Link-Local.
pub fn is_lan(ip: Ipv4Addr) -> bool {
    ip.is_private()
}

/// Taugt als öffentliche Adresse (keine privaten, CGNAT-, Loopback- oder Sonderbereiche).
pub fn is_public(ip: IpAddr) -> bool {
    crate::skin_import::is_public_ip(ip)
}

/// Adresse dieses PCs im Heimnetz (`None` = offline oder keine private IPv4).
pub fn lan_ipv4() -> Option<Ipv4Addr> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    // Nur Routenwahl, es wird nichts gesendet.
    socket.connect((Ipv4Addr::new(192, 0, 2, 1), 9)).ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(ip) if is_lan(ip) => Some(ip),
        _ => None,
    }
}

/// Antwort eines IP-Dienstes: reiner Text (ipify) oder `ip=…`-Zeile (Cloudflare-Trace).
pub fn parse_ip_answer(body: &str) -> Option<IpAddr> {
    let text = body.trim();
    let candidate = text.lines().find_map(|l| l.trim().strip_prefix("ip=")).unwrap_or(text);
    candidate.trim().parse::<IpAddr>().ok().filter(|ip| is_public(*ip))
}

/// Öffentliche IP (gemerkt, `None` = unbekannt).
pub async fn public_ip(http: &reqwest::Client, refresh: bool) -> Option<IpAddr> {
    if !refresh
        && let Some((at, ip)) = *PUBLIC_IP.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
        && at.elapsed() < PUBLIC_IP_TTL
    {
        return Some(ip);
    }
    for url in PUBLIC_IP_SOURCES {
        let Ok(response) = http.get(url).timeout(Duration::from_secs(6)).send().await else { continue };
        if !response.status().is_success() {
            continue;
        }
        let Ok(body) = response.bytes().await else { continue };
        if body.len() > 4096 {
            continue;
        }
        if let Some(ip) = parse_ip_answer(&String::from_utf8_lossy(&body)) {
            *PUBLIC_IP.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some((Instant::now(), ip));
            return Some(ip);
        }
    }
    None
}
