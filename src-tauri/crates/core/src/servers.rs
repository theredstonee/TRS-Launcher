//! Gemeinsame Server-Liste des Launchers: einmal pflegen, in jeder Instanz
//! verfügbar (wird beim Start in `servers.dat` eingetragen), mit Live-Status
//! über Minecrafts Server-List-Ping.

use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Mutex;

use crate::nbt::{self, Tag};
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

const DEFAULT_PORT: u16 = 25565;
const MAX_SERVERS: usize = 100;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);
const IO_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_STATUS_BYTES: usize = 1 << 20;
const MAX_FAVICON_LEN: usize = 200_000;
const MAX_SERVERS_DAT_BYTES: u64 = 8 << 20;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Server {
    pub id: String,
    pub name: String,
    /// `host` oder `host:port`.
    pub address: String,
    /// Server-Resourcepacks ohne Nachfrage annehmen (Ränge, Icons, Sounds).
    #[serde(default = "yes")]
    pub auto_resource_pack: bool,
}

fn yes() -> bool {
    true
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerInput {
    pub name: String,
    pub address: String,
    #[serde(default = "yes")]
    pub auto_resource_pack: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerStatus {
    pub online: bool,
    pub players_online: u32,
    pub players_max: u32,
    pub motd: String,
    pub version: String,
    /// `data:image/png;base64,…` – geprüft, sonst `None`.
    pub favicon: Option<String>,
    pub latency_ms: u32,
}

impl ServerStatus {
    fn offline() -> Self {
        Self {
            online: false,
            players_online: 0,
            players_max: 0,
            motd: String::new(),
            version: String::new(),
            favicon: None,
            latency_ms: 0,
        }
    }
}

/// `host[:port]` → (host, port). Nur Hostnamen und IPv4 – bewusst eng, weil
/// die Adresse später auf der Java-Kommandozeile landet.
pub fn parse_address(address: &str) -> Result<(String, Option<u16>)> {
    let address = address.trim();
    let (host, port) = match address.rsplit_once(':') {
        Some((h, p)) => {
            let port: u16 =
                p.parse().ok().filter(|&p| p != 0).ok_or_else(|| Error::validation(crate::msg!(
                    "servers.invalidPort",
                    "Ungültiger Port"
                )))?;
            (h, Some(port))
        }
        None => (address, None),
    };
    let host_ok = !host.is_empty()
        && host.len() <= 253
        && !host.starts_with(['-', '.'])
        && !host.ends_with(['-', '.'])
        && !host.contains("..")
        && host.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_'));
    if !host_ok {
        return Err(Error::validation(crate::msg!("servers.invalidAddress", "Ungültige Server-Adresse")));
    }
    Ok((host.to_ascii_lowercase(), port))
}

fn normalized(address: &str) -> Result<String> {
    let (host, port) = parse_address(address)?;
    Ok(match port {
        Some(p) if p != DEFAULT_PORT => format!("{host}:{p}"),
        _ => host,
    })
}

fn validate_name(name: &str) -> Result<String> {
    let name: String = name.trim().chars().filter(|c| !c.is_control()).collect();
    if name.is_empty() || name.chars().count() > 64 {
        return Err(Error::validation(crate::msg!(
            "servers.nameLength",
            "Der Servername muss zwischen 1 und 64 Zeichen lang sein"
        )));
    }
    Ok(name)
}

pub struct ServerStore {
    paths: Paths,
    lock: Mutex<()>,
}

impl ServerStore {
    pub fn new(paths: Paths) -> Self {
        Self { paths, lock: Mutex::new(()) }
    }

    fn file(&self) -> std::path::PathBuf {
        self.paths.root().join("servers.json")
    }

    pub async fn list(&self) -> Result<Vec<Server>> {
        match fsutil::read_json(&self.file()).await {
            Ok(list) => Ok(list.unwrap_or_default()),
            Err(Error::Json { .. }) => Ok(Vec::new()),
            Err(e) => Err(e),
        }
    }

    pub async fn add(&self, input: ServerInput) -> Result<Server> {
        let _guard = self.lock.lock().await;
        let mut list = self.list().await?;
        if list.len() >= MAX_SERVERS {
            return Err(Error::validation(crate::msg!(
                "servers.tooMany",
                "Mehr als 100 Server werden nicht unterstützt."
            )));
        }
        let address = normalized(&input.address)?;
        if list.iter().any(|s| s.address == address) {
            return Err(Error::validation(crate::msg!("servers.duplicate", "Dieser Server steht schon in der Liste.")));
        }
        let server = Server {
            id: uuid::Uuid::new_v4().simple().to_string(),
            name: validate_name(&input.name)?,
            address,
            auto_resource_pack: input.auto_resource_pack,
        };
        list.push(server.clone());
        fsutil::write_json(&self.file(), &list).await?;
        Ok(server)
    }

    pub async fn update(&self, id: &str, input: ServerInput) -> Result<Server> {
        let _guard = self.lock.lock().await;
        let mut list = self.list().await?;
        let address = normalized(&input.address)?;
        if list.iter().any(|s| s.id != id && s.address == address) {
            return Err(Error::validation(crate::msg!("servers.duplicate", "Dieser Server steht schon in der Liste.")));
        }
        let name = validate_name(&input.name)?;
        let server = list
            .iter_mut()
            .find(|s| s.id == id)
            .ok_or_else(|| Error::validation(crate::msg!("servers.notFound", "Dieser Server existiert nicht mehr.")))?;
        server.name = name;
        server.address = address;
        server.auto_resource_pack = input.auto_resource_pack;
        let updated = server.clone();
        fsutil::write_json(&self.file(), &list).await?;
        Ok(updated)
    }

    pub async fn remove(&self, id: &str) -> Result<()> {
        let _guard = self.lock.lock().await;
        let mut list = self.list().await?;
        list.retain(|s| s.id != id);
        fsutil::write_json(&self.file(), &list).await
    }

    pub async fn get(&self, id: &str) -> Result<Server> {
        self.list()
            .await?
            .into_iter()
            .find(|s| s.id == id)
            .ok_or_else(|| Error::validation(crate::msg!("servers.notFound", "Dieser Server existiert nicht mehr.")))
    }

    /// Trägt alle Launcher-Server in die `servers.dat` der Instanz ein. Im
    /// Spiel hinzugefügte Server bleiben erhalten.
    pub async fn sync_to_instance(&self, game_dir: &std::path::Path) -> Result<()> {
        let servers = self.list().await?;
        if servers.is_empty() {
            return Ok(());
        }
        let file = game_dir.join("servers.dat");

        let mut root = match tokio::fs::metadata(&file).await {
            Ok(meta) if meta.len() <= MAX_SERVERS_DAT_BYTES => {
                let bytes = tokio::fs::read(&file).await.map_err(|e| Error::io(&file, e))?;
                // Eine kaputte servers.dat wird neu angelegt statt den Start zu blockieren.
                nbt::read_root(&bytes).unwrap_or_default()
            }
            _ => Vec::new(),
        };

        let existing = match nbt::get(&root, "servers") {
            Some(Tag::List(_, items)) => items.clone(),
            _ => Vec::new(),
        };
        nbt::set(&mut root, "servers", Tag::List(10, merge_entries(&servers, existing)));
        fsutil::write_atomic(&file, &nbt::write_root(&root)).await
    }
}

fn entry_address(entry: &Tag) -> Option<String> {
    let Tag::Compound(fields) = entry else { return None };
    let ip = nbt::get(fields, "ip")?.as_str_lossy()?;
    normalized(&ip).ok()
}

/// Launcher-Server zuerst (in Listen-Reihenfolge), danach alles, was der
/// Spieler im Spiel selbst angelegt hat.
fn merge_entries(servers: &[Server], existing: Vec<Tag>) -> Vec<Tag> {
    let mut rest = existing;
    let mut merged = Vec::with_capacity(servers.len() + rest.len());

    for server in servers {
        let position = rest.iter().position(|e| entry_address(e).as_deref() == Some(server.address.as_str()));
        // Vorhandenen Eintrag weiterverwenden – so bleibt z. B. das gecachte Icon.
        let mut fields = match position.map(|i| rest.remove(i)) {
            Some(Tag::Compound(fields)) => fields,
            _ => Vec::new(),
        };
        nbt::set(&mut fields, "name", Tag::string(&server.name));
        nbt::set(&mut fields, "ip", Tag::string(&server.address));
        if server.auto_resource_pack {
            nbt::set(&mut fields, "acceptTextures", Tag::Byte(1));
        }
        merged.push(Tag::Compound(fields));
    }
    merged.extend(rest);
    merged
}

// --- Server-List-Ping ------------------------------------------------------

fn write_varint(out: &mut Vec<u8>, mut value: u32) {
    loop {
        let byte = (value & 0x7f) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

async fn read_varint(stream: &mut TcpStream) -> std::io::Result<u32> {
    let mut value = 0u32;
    for shift in (0..35).step_by(7) {
        let byte = stream.read_u8().await?;
        value |= u32::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(std::io::ErrorKind::InvalidData.into())
}

fn packet(id: u32, body: &[u8]) -> Vec<u8> {
    let mut inner = Vec::new();
    write_varint(&mut inner, id);
    inner.extend_from_slice(body);
    let mut out = Vec::new();
    write_varint(&mut out, inner.len() as u32);
    out.extend(inner);
    out
}

#[derive(Deserialize)]
struct RawStatus {
    #[serde(default)]
    description: serde_json::Value,
    players: Option<RawPlayers>,
    version: Option<RawVersion>,
    favicon: Option<String>,
}

#[derive(Deserialize)]
struct RawPlayers {
    #[serde(default)]
    online: i64,
    #[serde(default)]
    max: i64,
}

#[derive(Deserialize)]
struct RawVersion {
    #[serde(default)]
    name: String,
}

/// Löst die Adresse so auf, wie es das Spiel beim direkten Verbinden braucht.
pub async fn join_target(address: &str) -> Result<crate::launch::JoinTarget> {
    let (host, port) = parse_address(address)?;
    let (target_host, target_port) = match port {
        Some(p) => (host, p),
        None => resolve_srv(&host).await.unwrap_or((host, DEFAULT_PORT)),
    };
    Ok(crate::launch::JoinTarget { address: normalized(address)?, host: target_host, port: target_port })
}

/// Fragt den Status ab. Nicht erreichbar ist kein Fehler, sondern `online: false`.
pub async fn ping(address: &str) -> Result<ServerStatus> {
    let (host, port) = parse_address(address)?;
    let (target_host, target_port) = match port {
        Some(p) => (host.clone(), p),
        None => resolve_srv(&host).await.unwrap_or((host.clone(), DEFAULT_PORT)),
    };

    match tokio::time::timeout(CONNECT_TIMEOUT + IO_TIMEOUT, ping_inner(&host, &target_host, target_port)).await {
        Ok(Ok(status)) => Ok(status),
        Ok(Err(e)) => {
            tracing::debug!("Ping {address} fehlgeschlagen: {e}");
            Ok(ServerStatus::offline())
        }
        Err(_) => Ok(ServerStatus::offline()),
    }
}

async fn ping_inner(handshake_host: &str, host: &str, port: u16) -> std::io::Result<ServerStatus> {
    let started = Instant::now();
    let mut stream = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect((host, port)))
        .await
        .map_err(|_| std::io::Error::from(std::io::ErrorKind::TimedOut))??;
    stream.set_nodelay(true)?;

    let mut handshake = Vec::new();
    write_varint(&mut handshake, u32::MAX); // Protokollversion -1 = "nur Status"
    write_varint(&mut handshake, handshake_host.len() as u32);
    handshake.extend_from_slice(handshake_host.as_bytes());
    handshake.extend_from_slice(&port.to_be_bytes());
    write_varint(&mut handshake, 1);
    stream.write_all(&packet(0, &handshake)).await?;
    stream.write_all(&packet(0, &[])).await?;

    let _packet_len = read_varint(&mut stream).await?;
    let _packet_id = read_varint(&mut stream).await?;
    let json_len = read_varint(&mut stream).await? as usize;
    if json_len > MAX_STATUS_BYTES {
        return Err(std::io::ErrorKind::InvalidData.into());
    }
    let mut json = vec![0u8; json_len];
    stream.read_exact(&mut json).await?;
    let mut latency = started.elapsed();

    // Echte Latenz per Ping/Pong; manche Server antworten darauf nicht.
    let pong = async {
        let sent = Instant::now();
        stream.write_all(&packet(1, &0x5452_535f_i64.to_be_bytes())).await?;
        let _len = read_varint(&mut stream).await?;
        let _id = read_varint(&mut stream).await?;
        stream.read_i64().await?;
        Ok::<_, std::io::Error>(sent.elapsed())
    };
    if let Ok(Ok(rtt)) = tokio::time::timeout(Duration::from_millis(1500), pong).await {
        latency = rtt;
    }

    let raw: RawStatus = serde_json::from_slice(&json).map_err(|_| std::io::ErrorKind::InvalidData)?;
    let players = raw.players.unwrap_or(RawPlayers { online: 0, max: 0 });
    Ok(ServerStatus {
        online: true,
        players_online: players.online.clamp(0, i64::from(u32::MAX)) as u32,
        players_max: players.max.clamp(0, i64::from(u32::MAX)) as u32,
        motd: clean_text(&flatten_component(&raw.description, 0), 300),
        version: clean_text(&raw.version.map(|v| v.name).unwrap_or_default(), 60),
        favicon: raw.favicon.filter(|f| is_safe_favicon(f)),
        latency_ms: latency.as_millis().min(60_000) as u32,
    })
}

/// Chat-Komponente → reiner Text.
fn flatten_component(value: &serde_json::Value, depth: usize) -> String {
    if depth > 16 {
        return String::new();
    }
    match value {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Array(items) => items.iter().map(|v| flatten_component(v, depth + 1)).collect(),
        serde_json::Value::Object(map) => {
            let mut out = map.get("text").and_then(|t| t.as_str()).unwrap_or_default().to_owned();
            if let Some(extra) = map.get("extra") {
                out.push_str(&flatten_component(extra, depth + 1));
            }
            out
        }
        _ => String::new(),
    }
}

/// Entfernt §-Formatcodes und Steuerzeichen, Zeilenumbrüche bleiben.
fn clean_text(text: &str, max_chars: usize) -> String {
    let mut out = String::new();
    let mut chars = text.chars();
    while let Some(c) = chars.next() {
        match c {
            '§' => {
                chars.next();
            }
            '\n' => out.push('\n'),
            c if c.is_control() => {}
            c => out.push(c),
        }
    }
    let trimmed: Vec<&str> = out.lines().map(str::trim).filter(|l| !l.is_empty()).collect();
    trimmed.join("\n").chars().take(max_chars).collect()
}

fn is_safe_favicon(favicon: &str) -> bool {
    favicon.len() <= MAX_FAVICON_LEN
        && favicon.strip_prefix("data:image/png;base64,").is_some_and(|data| {
            !data.is_empty() && data.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'+' | b'/' | b'='))
        })
}

/// `_minecraft._tcp.<host>` – viele Server (gerade mit Subdomain) sind nur
/// darüber erreichbar. Windows: System-Resolver, Linux: eigene kleine
/// DNS-Anfrage (siehe `platform::dns`) – beides ohne DNS-Crate.
async fn resolve_srv(host: &str) -> Option<(String, u16)> {
    if host.parse::<std::net::Ipv4Addr>().is_ok() {
        return None;
    }
    let name = format!("_minecraft._tcp.{host}");
    let result = tokio::task::spawn_blocking(move || crate::platform::lookup_srv(&name)).await.ok().flatten()?;
    // Das Ziel kommt aus dem DNS – gleiche Regeln wie für Nutzereingaben.
    let (target, _) = parse_address(result.0.trim_end_matches('.')).ok()?;
    Some((target, result.1))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn address_parsing() {
        assert_eq!(parse_address(" Play.CoolTiers.de ").unwrap(), ("play.cooltiers.de".into(), None));
        assert_eq!(parse_address("mc.example.org:25570").unwrap(), ("mc.example.org".into(), Some(25570)));
        assert_eq!(normalized("MC.Example.org:25565").unwrap(), "mc.example.org");
        for bad in ["", "a b", "host:0", "host:99999", "host:abc", "-x.de", "a..b", "x.de;calc", "x.de --demo", "[::1]"] {
            assert!(parse_address(bad).is_err(), "{bad:?}");
        }
    }

    #[test]
    fn varints() {
        for (value, bytes) in [(0u32, vec![0u8]), (127, vec![0x7f]), (128, vec![0x80, 1]), (25565, vec![0xdd, 0xc7, 1])] {
            let mut out = Vec::new();
            write_varint(&mut out, value);
            assert_eq!(out, bytes);
        }
        let mut out = Vec::new();
        write_varint(&mut out, u32::MAX);
        assert_eq!(out, [0xff, 0xff, 0xff, 0xff, 0x0f]);
    }

    #[test]
    fn motd_flattening() {
        let v: serde_json::Value = serde_json::from_str(
            r#"{"text":"","extra":[{"text":"§6Cool","bold":true},{"text":"Tiers\n","extra":["  Duels & §aPractice  "]}]}"#,
        )
        .unwrap();
        assert_eq!(clean_text(&flatten_component(&v, 0), 300), "CoolTiers\nDuels & Practice");
        assert_eq!(clean_text("§cRot§r normal", 300), "Rot normal");
    }

    #[test]
    fn favicon_validation() {
        assert!(is_safe_favicon("data:image/png;base64,iVBORw0KGgo="));
        assert!(!is_safe_favicon("data:image/svg+xml;base64,PHN2Zz4="));
        assert!(!is_safe_favicon("https://evil.example/x.png"));
        assert!(!is_safe_favicon("data:image/png;base64,abc\" onerror=\"x"));
    }

    fn server(name: &str, address: &str, auto: bool) -> Server {
        Server { id: name.into(), name: name.into(), address: address.into(), auto_resource_pack: auto }
    }

    fn entry(name: &str, ip: &str) -> Tag {
        Tag::Compound(vec![
            (b"name".to_vec(), Tag::string(name)),
            (b"ip".to_vec(), Tag::string(ip)),
            (b"icon".to_vec(), Tag::string("CACHED")),
        ])
    }

    #[test]
    fn merge_keeps_player_servers_and_cached_icons() {
        let ours = [server("CoolTiers", "play.cooltiers.de", true), server("Test", "test.example.org", false)];
        let existing = vec![entry("Hypixel", "mc.hypixel.net"), entry("alt", "PLAY.CoolTiers.de:25565")];
        let merged = merge_entries(&ours, existing);

        assert_eq!(merged.len(), 3);
        let Tag::Compound(first) = &merged[0] else { panic!() };
        assert_eq!(nbt::get(first, "name").unwrap().as_str_lossy().as_deref(), Some("CoolTiers"));
        assert_eq!(nbt::get(first, "icon").unwrap().as_str_lossy().as_deref(), Some("CACHED"));
        assert_eq!(nbt::get(first, "acceptTextures"), Some(&Tag::Byte(1)));

        let Tag::Compound(second) = &merged[1] else { panic!() };
        assert!(nbt::get(second, "acceptTextures").is_none());

        let Tag::Compound(third) = &merged[2] else { panic!() };
        assert_eq!(nbt::get(third, "ip").unwrap().as_str_lossy().as_deref(), Some("mc.hypixel.net"));
    }

    #[tokio::test]
    async fn store_and_sync() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        let store = ServerStore::new(paths);

        let input = |name: &str, address: &str| ServerInput { name: name.into(), address: address.into(), auto_resource_pack: true };
        let a = store.add(input("CoolTiers", "Play.CoolTiers.de")).await.unwrap();
        assert_eq!(a.address, "play.cooltiers.de");
        assert!(store.add(input("Doppelt", "play.cooltiers.de:25565")).await.is_err());
        assert!(store.add(input("", "x.de")).await.is_err());
        store.update(&a.id, input("CT", "play.cooltiers.de")).await.unwrap();

        let game_dir = dir.path().join("game");
        tokio::fs::create_dir_all(&game_dir).await.unwrap();
        tokio::fs::write(game_dir.join("servers.dat"), b"kaputt").await.unwrap();
        store.sync_to_instance(&game_dir).await.unwrap();

        let root = nbt::read_root(&tokio::fs::read(game_dir.join("servers.dat")).await.unwrap()).unwrap();
        let Some(Tag::List(10, entries)) = nbt::get(&root, "servers") else { panic!() };
        assert_eq!(entries.len(), 1);

        store.remove(&a.id).await.unwrap();
        assert!(store.list().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn unreachable_server_is_offline_not_error() {
        let status = ping("127.0.0.1:9").await.unwrap();
        assert!(!status.online);
    }
}
