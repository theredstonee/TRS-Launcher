//! Discords lokales IPC-Protokoll (dasselbe, das die offiziellen SDKs nutzen).
//!
//! Windows: Named Pipe `\\.\pipe\discord-ipc-N`; Linux/macOS: Unix-Socket
//! `discord-ipc-N` in `XDG_RUNTIME_DIR`/`TMPDIR`/`/tmp` – auch in den Unterordnern
//! der Flatpak- und Snap-Pakete von Discord. `N` = 0…9 (mehrere Discord-Instanzen).
//!
//! Jede Nachricht: Opcode (u32 LE), Länge (u32 LE), JSON. Nach dem Handshake
//! (`{"v":1,"client_id":…}` → `READY`) setzt `SET_ACTIVITY` den Status.

use std::io;

use serde_json::{Value, json};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use super::{Activity, Connection, Connector};

const OP_HANDSHAKE: u32 = 0;
const OP_FRAME: u32 = 1;
const OP_CLOSE: u32 = 2;
const OP_PING: u32 = 3;
const OP_PONG: u32 = 4;

/// Antworten von Discord sind klein – alles darüber ist kaputt.
const MAX_FRAME: usize = 64 * 1024;
/// Höchstens so viele fremde Nachrichten überspringen, bis die Antwort kommt.
const MAX_SKIPPED: usize = 16;

trait Stream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Stream for T {}

/// Verbindet sich mit der lokal laufenden Discord-App.
pub struct IpcConnector;

pub struct IpcConnection {
    stream: Box<dyn Stream>,
}

impl Connector for IpcConnector {
    type Conn = IpcConnection;

    async fn connect(&self, app_id: &str) -> io::Result<IpcConnection> {
        let mut last = io::Error::new(io::ErrorKind::NotFound, "Discord läuft nicht");
        for i in 0..10 {
            let Some(stream) = open(i) else { continue };
            let mut conn = IpcConnection { stream };
            match conn.handshake(app_id).await {
                Ok(()) => return Ok(conn),
                Err(e) => last = e,
            }
        }
        Err(last)
    }
}

impl Connection for IpcConnection {
    async fn set_activity(&mut self, activity: Option<&Activity>) -> io::Result<()> {
        let nonce = uuid::Uuid::new_v4().to_string();
        let payload = json!({
            "cmd": "SET_ACTIVITY",
            "args": { "pid": std::process::id(), "activity": activity },
            "nonce": nonce,
        });
        self.write(OP_FRAME, &payload).await?;
        for _ in 0..MAX_SKIPPED {
            let (op, reply) = self.read().await?;
            if op == OP_FRAME && reply.get("nonce").and_then(Value::as_str) == Some(nonce.as_str()) {
                if reply.get("evt").and_then(Value::as_str) == Some("ERROR") {
                    // Z. B. ungültige Felder – die Verbindung selbst ist in Ordnung.
                    let message = reply.pointer("/data/message").and_then(Value::as_str).unwrap_or("?");
                    tracing::warn!("Discord-Status abgelehnt: {message}");
                }
                return Ok(());
            }
        }
        Err(io::Error::new(io::ErrorKind::InvalidData, "keine Antwort von Discord"))
    }
}

impl IpcConnection {
    async fn handshake(&mut self, app_id: &str) -> io::Result<()> {
        self.write(OP_HANDSHAKE, &json!({ "v": 1, "client_id": app_id })).await?;
        let (op, reply) = self.read().await?;
        if op == OP_FRAME && reply.get("evt").and_then(Value::as_str) == Some("READY") {
            Ok(())
        } else {
            let message = reply.get("message").and_then(Value::as_str).unwrap_or("unerwartete Antwort");
            Err(io::Error::new(io::ErrorKind::ConnectionRefused, format!("Handshake abgelehnt: {message}")))
        }
    }

    async fn write(&mut self, op: u32, payload: &Value) -> io::Result<()> {
        self.stream.write_all(&encode(op, payload)?).await?;
        self.stream.flush().await
    }

    /// Nächste Nachricht; beantwortet Pings und meldet `CLOSE` als Fehler.
    async fn read(&mut self) -> io::Result<(u32, Value)> {
        loop {
            let mut header = [0u8; 8];
            self.stream.read_exact(&mut header).await?;
            let (op, len) = decode_header(header)?;
            let mut body = vec![0u8; len];
            self.stream.read_exact(&mut body).await?;
            let value: Value = serde_json::from_slice(&body).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
            match op {
                OP_PING => self.write(OP_PONG, &value).await?,
                OP_CLOSE => {
                    let message = value.get("message").and_then(Value::as_str).unwrap_or("geschlossen").to_owned();
                    return Err(io::Error::new(io::ErrorKind::ConnectionAborted, message));
                }
                _ => return Ok((op, value)),
            }
        }
    }
}

pub(crate) fn encode(op: u32, payload: &Value) -> io::Result<Vec<u8>> {
    let body = serde_json::to_vec(payload).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    let len = u32::try_from(body.len()).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "zu groß"))?;
    let mut out = Vec::with_capacity(8 + body.len());
    out.extend_from_slice(&op.to_le_bytes());
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(&body);
    Ok(out)
}

pub(crate) fn decode_header(header: [u8; 8]) -> io::Result<(u32, usize)> {
    let op = u32::from_le_bytes([header[0], header[1], header[2], header[3]]);
    let len = u32::from_le_bytes([header[4], header[5], header[6], header[7]]) as usize;
    if len > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Nachricht zu groß"));
    }
    Ok((op, len))
}

/// Öffnet den Kanal zu Discord-Instanz `i`, falls es ihn gibt.
#[cfg(windows)]
fn open(i: u8) -> Option<Box<dyn Stream>> {
    use tokio::net::windows::named_pipe::ClientOptions;
    let pipe = ClientOptions::new().open(format!(r"\\.\pipe\discord-ipc-{i}")).ok()?;
    Some(Box::new(pipe))
}

#[cfg(unix)]
fn open(i: u8) -> Option<Box<dyn Stream>> {
    let bases = ["XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"]
        .into_iter()
        .filter_map(std::env::var_os)
        .filter(|v| !v.is_empty())
        .map(std::path::PathBuf::from)
        .chain(std::iter::once(std::path::PathBuf::from("/tmp")));
    bases.flat_map(|base| socket_paths(&base, i)).find_map(|path| {
        // `connect` von std ist bei Unix-Sockets sofort fertig (kein Netzwerk).
        let stream = std::os::unix::net::UnixStream::connect(path).ok()?;
        stream.set_nonblocking(true).ok()?;
        let stream = tokio::net::UnixStream::from_std(stream).ok()?;
        Some(Box::new(stream) as Box<dyn Stream>)
    })
}

/// Wo Discord (normal, Flatpak, Snap, Vesktop) seinen Socket anlegt.
#[cfg(any(unix, test))]
pub(crate) fn socket_paths(base: &std::path::Path, i: u8) -> Vec<std::path::PathBuf> {
    const SUBDIRS: [&str; 6] = [
        "",
        "app/com.discordapp.Discord",
        "app/com.discordapp.DiscordCanary",
        "app/dev.vencord.Vesktop",
        "snap.discord",
        "snap.discord-canary",
    ];
    let name = format!("discord-ipc-{i}");
    SUBDIRS.iter().map(|sub| if sub.is_empty() { base.join(&name) } else { base.join(sub).join(&name) }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_roundtrip() {
        let bytes = encode(OP_HANDSHAKE, &json!({ "v": 1, "client_id": "123" })).unwrap();
        let (op, len) = decode_header(bytes[..8].try_into().unwrap()).unwrap();
        assert_eq!((op, len), (OP_HANDSHAKE, bytes.len() - 8));
        let body: Value = serde_json::from_slice(&bytes[8..]).unwrap();
        assert_eq!(body["client_id"], "123");

        let mut huge = [0u8; 8];
        huge[4..].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(decode_header(huge).is_err());
    }

    #[test]
    fn socket_paths_cover_flatpak_and_snap() {
        let paths = socket_paths(std::path::Path::new("/run/user/1000"), 3);
        let paths: Vec<_> = paths.iter().map(|p| p.to_string_lossy().replace('\\', "/")).collect();
        assert!(paths.contains(&"/run/user/1000/discord-ipc-3".to_owned()));
        assert!(paths.contains(&"/run/user/1000/app/com.discordapp.Discord/discord-ipc-3".to_owned()));
        assert!(paths.contains(&"/run/user/1000/snap.discord/discord-ipc-3".to_owned()));
    }

    /// Nur von Hand: Handshake mit einem laufenden Discord, ohne einen Status zu setzen.
    /// `cargo test -p trs-core real_discord_handshake -- --ignored`
    #[tokio::test]
    #[ignore = "braucht ein laufendes Discord"]
    async fn real_discord_handshake() {
        IpcConnector.connect(crate::discord::DISCORD_APP_ID).await.expect("Discord erreichbar");
    }

    /// Handshake und `SET_ACTIVITY` gegen eine nachgebaute Discord-Gegenstelle.
    #[tokio::test]
    async fn talks_the_protocol() {
        let (client, mut server) = tokio::io::duplex(4096);
        let fake = tokio::spawn(async move {
            async fn read(s: &mut tokio::io::DuplexStream) -> (u32, Value) {
                let mut header = [0u8; 8];
                s.read_exact(&mut header).await.unwrap();
                let (op, len) = decode_header(header).unwrap();
                let mut body = vec![0u8; len];
                s.read_exact(&mut body).await.unwrap();
                (op, serde_json::from_slice(&body).unwrap())
            }
            let (op, hello) = read(&mut server).await;
            assert_eq!((op, hello["client_id"].as_str()), (OP_HANDSHAKE, Some("42")));
            server.write_all(&encode(OP_FRAME, &json!({ "cmd": "DISPATCH", "evt": "READY" })).unwrap()).await.unwrap();

            // Ein Ping zwischendurch muss beantwortet werden.
            let (op, set) = read(&mut server).await;
            assert_eq!(op, OP_FRAME);
            assert_eq!(set["cmd"], "SET_ACTIVITY");
            assert_eq!(set["args"]["activity"]["details"], "Im TRS Launcher");
            server.write_all(&encode(OP_PING, &json!({ "x": 1 })).unwrap()).await.unwrap();
            let (op, _) = read(&mut server).await;
            assert_eq!(op, OP_PONG);
            let reply = json!({ "cmd": "SET_ACTIVITY", "evt": null, "nonce": set["nonce"] });
            server.write_all(&encode(OP_FRAME, &reply).unwrap()).await.unwrap();

            // Löschen: `activity` ist null.
            let (_, clear) = read(&mut server).await;
            assert!(clear["args"]["activity"].is_null());
            let reply = json!({ "cmd": "SET_ACTIVITY", "evt": "ERROR", "data": { "message": "x" }, "nonce": clear["nonce"] });
            server.write_all(&encode(OP_FRAME, &reply).unwrap()).await.unwrap();

            // Danach schließt Discord.
            let (_, _) = read(&mut server).await;
            server.write_all(&encode(OP_CLOSE, &json!({ "code": 1000, "message": "bye" })).unwrap()).await.unwrap();
        });

        let mut conn = IpcConnection { stream: Box::new(client) };
        conn.handshake("42").await.unwrap();
        let presence = super::super::DiscordPresence::new("123456789012345678");
        presence.configure(true, crate::settings::Language::De);
        conn.set_activity(presence.activity().as_ref()).await.unwrap();
        conn.set_activity(None).await.unwrap();
        let err = conn.set_activity(None).await.unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::ConnectionAborted);
        fake.await.unwrap();
    }
}
