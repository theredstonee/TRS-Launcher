//! Gemeinsame Server-Liste des Launchers: einmal pflegen, in jeder Instanz
//! verfügbar (wird beim Start in `servers.dat` eingetragen), mit Live-Status
//! über Minecrafts Server-List-Ping.

use std::path::Path;
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
        let mut root = read_servers_dat(&file).await?;
        let existing = dat_entries(&root);
        nbt::set(&mut root, "servers", Tag::List(10, merge_entries(&servers, existing)));
        fsutil::write_atomic(&file, &nbt::write_root(&root)).await
    }

    /// Server einer Instanz: alles aus ihrer `servers.dat` plus Launcher-Server,
    /// die beim nächsten Start dazukommen (noch ohne `index`).
    pub async fn list_instance(&self, game_dir: &Path) -> Result<Vec<InstanceServer>> {
        let launcher = self.list().await?;
        let root = read_servers_dat(&game_dir.join("servers.dat")).await?;
        let mut out = Vec::new();
        for (index, entry) in dat_entries(&root).iter().enumerate().take(MAX_INSTANCE_SERVERS) {
            let Tag::Compound(fields) = entry else { continue };
            let raw_ip = nbt::get(fields, "ip").and_then(Tag::as_str_lossy).unwrap_or_default();
            let address = clean_text(&raw_ip, 260);
            let normalized_address = normalized(&address).ok();
            let managed = normalized_address.as_ref().and_then(|a| launcher.iter().find(|s| &s.address == a));
            let name = nbt::get(fields, "name").and_then(Tag::as_str_lossy).map(|n| clean_text(&n, 64)).unwrap_or_default();
            let icon = nbt::get(fields, "icon")
                .and_then(Tag::as_str_lossy)
                .map(|data| format!("data:image/png;base64,{}", data.trim()))
                // PNG-Signatur in Base64 – sonst ist es kein Bild, das wir zeigen.
                .filter(|f| is_safe_favicon(f) && f.starts_with("data:image/png;base64,iVBORw0KGgo"));
            let accept = match nbt::get(fields, "acceptTextures") {
                Some(Tag::Byte(v)) => Some(*v != 0),
                _ => None,
            };
            out.push(InstanceServer {
                index: Some(index),
                name: if name.is_empty() { address.clone() } else { name },
                joinable: normalized_address.is_some(),
                address,
                icon,
                accept_textures: accept,
                launcher_id: managed.map(|s| s.id.clone()),
            });
        }
        // Launcher-Server, die noch nicht in der servers.dat stehen.
        for server in &launcher {
            if !out.iter().any(|s| s.launcher_id.as_deref() == Some(server.id.as_str())) {
                out.push(InstanceServer {
                    index: None,
                    name: server.name.clone(),
                    address: server.address.clone(),
                    icon: None,
                    accept_textures: server.auto_resource_pack.then_some(true),
                    launcher_id: Some(server.id.clone()),
                    joinable: true,
                });
            }
        }
        Ok(out)
    }

    /// Neuer Server nur für diese Instanz (am Ende ihrer `servers.dat`).
    pub async fn add_to_instance(&self, game_dir: &Path, input: ServerInput) -> Result<()> {
        let _guard = self.lock.lock().await;
        let file = game_dir.join("servers.dat");
        let mut root = read_servers_dat(&file).await?;
        let mut entries = dat_entries(&root);
        if entries.len() >= MAX_INSTANCE_SERVERS {
            return Err(Error::validation(crate::msg!(
                "servers.tooMany",
                "Mehr als 100 Server werden nicht unterstützt."
            )));
        }
        let address = normalized(&input.address)?;
        if entries.iter().any(|e| entry_address(e).as_deref() == Some(address.as_str())) {
            return Err(Error::validation(crate::msg!("servers.duplicate", "Dieser Server steht schon in der Liste.")));
        }
        let mut fields = Vec::new();
        apply_input(&mut fields, &validate_name(&input.name)?, &address, input.auto_resource_pack);
        entries.push(Tag::Compound(fields));
        nbt::set(&mut root, "servers", Tag::List(10, entries));
        fsutil::write_atomic(&file, &nbt::write_root(&root)).await
    }

    /// Eintrag `index` ändern. `expected` = bisherige Adresse – schützt davor,
    /// dass das Spiel die Liste inzwischen umsortiert hat.
    pub async fn update_in_instance(&self, game_dir: &Path, index: usize, expected: &str, input: ServerInput) -> Result<()> {
        let _guard = self.lock.lock().await;
        let file = game_dir.join("servers.dat");
        let mut root = read_servers_dat(&file).await?;
        let mut entries = dat_entries(&root);
        check_entry(&entries, index, expected)?;
        let address = normalized(&input.address)?;
        if entries.iter().enumerate().any(|(i, e)| i != index && entry_address(e).as_deref() == Some(address.as_str())) {
            return Err(Error::validation(crate::msg!("servers.duplicate", "Dieser Server steht schon in der Liste.")));
        }
        let name = validate_name(&input.name)?;
        if let Some(Tag::Compound(fields)) = entries.get_mut(index) {
            apply_input(fields, &name, &address, input.auto_resource_pack);
        }
        nbt::set(&mut root, "servers", Tag::List(10, entries));
        fsutil::write_atomic(&file, &nbt::write_root(&root)).await
    }

    pub async fn remove_from_instance(&self, game_dir: &Path, index: usize, expected: &str) -> Result<()> {
        let _guard = self.lock.lock().await;
        let file = game_dir.join("servers.dat");
        let mut root = read_servers_dat(&file).await?;
        let mut entries = dat_entries(&root);
        check_entry(&entries, index, expected)?;
        entries.remove(index);
        nbt::set(&mut root, "servers", Tag::List(10, entries));
        fsutil::write_atomic(&file, &nbt::write_root(&root)).await
    }
}

/// Höchstens so viele Einträge einer `servers.dat` werden angezeigt/bearbeitet.
const MAX_INSTANCE_SERVERS: usize = 500;

/// Eintrag aus der `servers.dat` einer Instanz.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstanceServer {
    /// Position in der `servers.dat`; `None` = Launcher-Server, der erst beim
    /// nächsten Start eingetragen wird.
    pub index: Option<usize>,
    pub name: String,
    /// Wie gespeichert (ohne Steuerzeichen).
    pub address: String,
    /// Gecachtes Server-Icon als geprüfte `data:image/png;base64,…`-URL.
    pub icon: Option<String>,
    /// `true` = Server-Ressourcenpakete annehmen, `false` = ablehnen, `None` = nachfragen.
    pub accept_textures: Option<bool>,
    /// Gehört zur Launcher-Serverliste (gilt für alle Instanzen).
    pub launcher_id: Option<String>,
    /// Adresse ist gültig – „Beitreten“ kann direkt verbinden.
    pub joinable: bool,
}

async fn read_servers_dat(file: &Path) -> Result<Vec<(Vec<u8>, Tag)>> {
    Ok(match tokio::fs::metadata(file).await {
        Ok(meta) if meta.len() <= MAX_SERVERS_DAT_BYTES => {
            let bytes = tokio::fs::read(file).await.map_err(|e| Error::io(file, e))?;
            // Eine kaputte servers.dat wird neu angelegt statt den Start zu blockieren.
            nbt::read_root(&bytes).unwrap_or_default()
        }
        _ => Vec::new(),
    })
}

fn dat_entries(root: &[(Vec<u8>, Tag)]) -> Vec<Tag> {
    match nbt::get(root, "servers") {
        Some(Tag::List(_, items)) => items.clone(),
        _ => Vec::new(),
    }
}

fn check_entry(entries: &[Tag], index: usize, expected: &str) -> Result<()> {
    let actual = match entries.get(index) {
        Some(Tag::Compound(fields)) => nbt::get(fields, "ip").and_then(Tag::as_str_lossy).map(|ip| clean_text(&ip, 260)),
        _ => None,
    };
    if actual.as_deref() == Some(expected) {
        Ok(())
    } else {
        Err(Error::validation(crate::msg!(
            "servers.instanceChanged",
            "Die Serverliste der Instanz hat sich geändert – bitte neu laden."
        )))
    }
}

/// Name, Adresse und Ressourcenpaket-Wahl setzen; unbekannte Felder (Icon …) bleiben.
fn apply_input(fields: &mut Vec<(Vec<u8>, Tag)>, name: &str, address: &str, accept: bool) {
    let address_changed = nbt::get(fields, "ip").and_then(Tag::as_str_lossy).is_some_and(|old| normalized(&old).ok().as_deref() != Some(address));
    nbt::set(fields, "name", Tag::string(name));
    nbt::set(fields, "ip", Tag::string(address));
    if accept {
        nbt::set(fields, "acceptTextures", Tag::Byte(1));
    } else {
        fields.retain(|(k, _)| k.as_slice() != b"acceptTextures");
    }
    // Das gecachte Icon gehört zum alten Server.
    if address_changed {
        fields.retain(|(k, _)| k.as_slice() != b"icon");
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

pub(crate) fn is_safe_favicon(favicon: &str) -> bool {
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
    async fn instance_servers_edit_servers_dat() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        let store = ServerStore::new(paths);
        let input = |name: &str, address: &str, auto: bool| ServerInput { name: name.into(), address: address.into(), auto_resource_pack: auto };
        let global = store.add(input("CoolTiers", "play.cooltiers.de", true)).await.unwrap();

        let game_dir = dir.path().join("game");
        tokio::fs::create_dir_all(&game_dir).await.unwrap();
        // Fremde Wurzel-Felder und ein Eintrag mit Icon aus dem Spiel.
        let root = vec![
            (
                b"servers".to_vec(),
                Tag::List(
                    10,
                    vec![Tag::Compound(vec![
                        (b"name".to_vec(), Tag::string("Hypixel")),
                        (b"ip".to_vec(), Tag::string("mc.hypixel.net")),
                        (b"icon".to_vec(), Tag::string("iVBORw0KGgoAAAANSUhEUg==")),
                    ])],
                ),
            ),
            (b"fremd".to_vec(), Tag::Int(7)),
        ];
        tokio::fs::write(game_dir.join("servers.dat"), nbt::write_root(&root)).await.unwrap();

        // Launcher-Server ohne Eintrag erscheint „virtuell“ am Ende.
        let list = store.list_instance(&game_dir).await.unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!((list[0].index, list[0].name.as_str(), list[0].launcher_id.as_deref()), (Some(0), "Hypixel", None));
        assert_eq!(list[0].icon.as_deref(), Some("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="));
        assert_eq!((list[1].index, list[1].launcher_id.as_deref()), (None, Some(global.id.as_str())));

        store.add_to_instance(&game_dir, input("Test", "Test.Example.org:25565", false)).await.unwrap();
        assert!(store.add_to_instance(&game_dir, input("Doppelt", "test.example.org", true)).await.is_err());
        assert!(store.add_to_instance(&game_dir, input("Böse", "x.de --demo", true)).await.is_err());

        let list = store.list_instance(&game_dir).await.unwrap();
        assert_eq!(list[1].address, "test.example.org");
        assert_eq!(list[1].accept_textures, None);

        // Falsche erwartete Adresse → abgelehnt (Liste hat sich geändert).
        assert!(store.update_in_instance(&game_dir, 1, "mc.hypixel.net", input("X", "x.de", true)).await.is_err());
        store.update_in_instance(&game_dir, 0, "mc.hypixel.net", input("Hypixel Network", "mc.hypixel.net", true)).await.unwrap();
        let list = store.list_instance(&game_dir).await.unwrap();
        assert_eq!(list[0].name, "Hypixel Network");
        assert_eq!(list[0].accept_textures, Some(true));
        assert!(list[0].icon.is_some(), "Icon bleibt, solange die Adresse gleich ist");

        // Adresse geändert → altes Icon fällt weg.
        store.update_in_instance(&game_dir, 0, "mc.hypixel.net", input("Anders", "anders.example.org", true)).await.unwrap();
        assert!(store.list_instance(&game_dir).await.unwrap()[0].icon.is_none());

        store.remove_from_instance(&game_dir, 1, "test.example.org").await.unwrap();
        assert!(store.remove_from_instance(&game_dir, 5, "egal").await.is_err());
        let root = nbt::read_root(&tokio::fs::read(game_dir.join("servers.dat")).await.unwrap()).unwrap();
        assert_eq!(nbt::get(&root, "fremd"), Some(&Tag::Int(7)));
        let Some(Tag::List(10, entries)) = nbt::get(&root, "servers") else { panic!() };
        assert_eq!(entries.len(), 1);

        // Beim Start eingetragen: jetzt mit Index und weiterhin als Launcher-Server erkannt.
        store.sync_to_instance(&game_dir).await.unwrap();
        let list = store.list_instance(&game_dir).await.unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!((list[0].index, list[0].launcher_id.as_deref()), (Some(0), Some(global.id.as_str())));
    }

    #[tokio::test]
    async fn unreachable_server_is_offline_not_error() {
        let status = ping("127.0.0.1:9").await.unwrap();
        assert!(!status.online);
    }
}
