//! Dateien direkt vom Host holen: eigene Verbindung über das TRS Relay
//! (`relay/PROTOCOL.md` §2, Gast-Handschlag) und darauf der Datei-Kanal
//! (`docs/hosting-files.md`). Der Host liefert nur Dateien aus seiner
//! Freigabeliste, angefragt per SHA-256 – nie per Pfad.
//!
//! Prüfungen hier: Größe (angekündigt, höchstens die Grenze), SHA-256 und SHA-1
//! gegen die Ankündigung aus der API, Zeitlimits je Lesevorgang. Geschrieben wird
//! in eine Datei, die der Aufrufer vorgibt (Teil-Datei im mods-Ordner).

use std::path::Path;
use std::time::Duration;

use sha1::Sha1;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::{Error, Result};

/// Präambel des Relay: `"TRSR"` + Version 1.
const PREAMBLE: [u8; 5] = [0x54, 0x52, 0x53, 0x52, 0x01];
const GUEST_HELLO: u8 = 0x02;
const WELCOME: u8 = 0x81;
const RELAY_ERROR: u8 = 0x8F;
const MAX_RELAY_FRAME: usize = 1024;

/// Magie des Datei-Kanals: `0x00` (nie ein Minecraft-Anfang) + `"TRSF"` + Version 1.
pub const MAGIC: [u8; 6] = [0x00, 0x54, 0x52, 0x53, 0x46, 0x01];
pub const GET: u8 = 0x01;
pub const BYE: u8 = 0x02;
pub const FILE: u8 = 0x81;
pub const DATA: u8 = 0x82;
pub const END: u8 = 0x83;
pub const ERROR: u8 = 0x8F;
pub const KIND_MOD: u8 = 1;
pub const KIND_PACK: u8 = 2;
/// Größte DATA-Nutzlast (64 KiB).
pub const CHUNK: usize = 64 * 1024;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(6);
/// Relay wartet bis 10 s auf den Host.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(14);
const READ_TIMEOUT: Duration = Duration::from_secs(45);

/// Zugang zum Relay für einen angenommenen Gast (aus `POST …/connect`).
/// Das Token ist geheim: kein `Debug`, nie loggen.
pub struct RelayGrant {
    pub host: String,
    pub port: u16,
    pub token: String,
}

fn fail(code: &str) -> Error {
    Error::Internal(format!("hosting-files: {code}"))
}

/// Code aus einem Fehler dieses Moduls (`None` = anderer Fehler).
pub fn error_code(e: &Error) -> Option<&str> {
    match e {
        Error::Internal(m) => m.strip_prefix("hosting-files: "),
        _ => None,
    }
}

fn safe_code(raw: &[u8]) -> String {
    let s = String::from_utf8_lossy(raw);
    if !s.is_empty() && s.len() <= 40 && s.bytes().all(|b| b.is_ascii_lowercase() || b == b'_') { s.into_owned() } else { "error".into() }
}

/// Relay-Host aus der API: nur Hostname/IPv4-Literal.
pub fn valid_relay_host(host: &str) -> bool {
    let b = host.as_bytes();
    (1..=253).contains(&b.len())
        && b[0].is_ascii_alphanumeric()
        && b[b.len() - 1].is_ascii_alphanumeric()
        && b.iter().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'-'))
}

/// Offener Datei-Kanal zum Host.
pub struct FileChannel {
    stream: TcpStream,
    /// Mitten in einer Antwort abgebrochen (Größe/Hash/Zeit) – der Strom ist nicht mehr im Takt.
    broken: bool,
    /// Zwischen Anfrage und END/ERROR.
    busy: bool,
}

impl FileChannel {
    /// Gast-Verbindung über das Relay öffnen und den Datei-Kanal ankündigen.
    pub async fn open(grant: &RelayGrant) -> Result<Self> {
        if !valid_relay_host(&grant.host) || grant.port == 0 || grant.token.len() > 512 || !grant.token.starts_with("trsr1.") {
            return Err(fail("bad_grant"));
        }
        let addr = format!("{}:{}", grant.host, grant.port);
        let mut stream = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(&addr))
            .await
            .map_err(|_| fail("relay_timeout"))?
            .map_err(|_| fail("relay_unreachable"))?;
        let _ = stream.set_nodelay(true);
        let token = grant.token.as_bytes();
        let mut hello = Vec::with_capacity(PREAMBLE.len() + 3 + token.len());
        hello.extend_from_slice(&PREAMBLE);
        hello.push(GUEST_HELLO);
        hello.extend_from_slice(&(token.len() as u16).to_be_bytes());
        hello.extend_from_slice(token);
        // Die Magie darf direkt folgen – das Relay puffert bis zum WELCOME (≤ 64 KiB).
        hello.extend_from_slice(&MAGIC);
        stream.write_all(&hello).await.map_err(|_| fail("relay_failed"))?;
        let (kind, payload) = tokio::time::timeout(HANDSHAKE_TIMEOUT, read_relay_frame(&mut stream))
            .await
            .map_err(|_| fail("host_timeout"))??;
        match kind {
            WELCOME => Ok(Self { stream, broken: false, busy: false }),
            RELAY_ERROR => Err(fail(&safe_code(&payload))),
            _ => Err(fail("bad_frame")),
        }
    }

    /// Für Tests: schon verbundener Strom (die Magie wird hier geschickt).
    pub async fn over(mut stream: TcpStream) -> Result<Self> {
        stream.write_all(&MAGIC).await.map_err(|_| fail("closed"))?;
        Ok(Self { stream, broken: false, busy: false })
    }

    /// Eine Datei holen. `sha256` = angekündigter Hash (API), `size` = angekündigte
    /// Größe, `max` = harte Grenze; `sha1` zusätzlich prüfen (falls angekündigt).
    /// Schreibt nach `out` (wird neu angelegt); bei jedem Fehler ist `out` gelöscht.
    #[allow(clippy::too_many_arguments)]
    pub async fn fetch(
        &mut self,
        kind: u8,
        sha256: &str,
        size: u64,
        max: u64,
        sha1: Option<&str>,
        out: &Path,
        mut progress: impl FnMut(u64, u64),
    ) -> Result<()> {
        if self.broken {
            return Err(fail("closed"));
        }
        let result = self.fetch_inner(kind, sha256, size, max, sha1, out, &mut progress).await;
        if self.busy {
            // Antwort nicht zu Ende gelesen: diesen Kanal nicht weiter benutzen.
            self.broken = true;
        }
        if result.is_err() {
            let _ = tokio::fs::remove_file(out).await;
        }
        result
    }

    #[allow(clippy::too_many_arguments)]
    async fn fetch_inner(
        &mut self,
        kind: u8,
        sha256: &str,
        size: u64,
        max: u64,
        sha1: Option<&str>,
        out: &Path,
        progress: &mut impl FnMut(u64, u64),
    ) -> Result<()> {
        let want = unhex32(sha256).ok_or_else(|| fail("bad_request"))?;
        if size == 0 || size > max {
            return Err(fail("size"));
        }
        let mut req = vec![GET, 0, 0, 0, 33, kind];
        req.extend_from_slice(&want);
        self.busy = true;
        self.stream.write_all(&req).await.map_err(|_| fail("closed"))?;
        let (t, payload) = self.read_frame(1024).await?;
        match t {
            ERROR => {
                self.busy = false;
                return Err(fail(&safe_code(&payload)));
            }
            FILE if payload.len() == 8 => {}
            _ => return Err(fail("bad_frame")),
        }
        let announced = u64::from_be_bytes(payload[..8].try_into().map_err(|_| fail("bad_frame"))?);
        if announced != size {
            return Err(fail("size"));
        }
        let mut file = tokio::fs::File::create(out).await.map_err(|e| Error::io(out, e))?;
        let mut h256 = Sha256::new();
        let mut h1 = Sha1::new();
        let mut got: u64 = 0;
        loop {
            let (t, data) = self.read_frame(CHUNK).await?;
            match t {
                DATA => {
                    got += data.len() as u64;
                    if got > size {
                        return Err(fail("size"));
                    }
                    h256.update(&data);
                    h1.update(&data);
                    file.write_all(&data).await.map_err(|e| Error::io(out, e))?;
                    progress(got, size);
                }
                END => {
                    self.busy = false;
                    break;
                }
                ERROR => {
                    self.busy = false;
                    return Err(fail(&safe_code(&data)));
                }
                _ => return Err(fail("bad_frame")),
            }
        }
        file.flush().await.map_err(|e| Error::io(out, e))?;
        file.sync_all().await.map_err(|e| Error::io(out, e))?;
        drop(file);
        if got != size {
            return Err(fail("size"));
        }
        if hex(&h256.finalize()) != sha256 {
            return Err(fail("hash"));
        }
        if let Some(expected) = sha1
            && hex(&h1.finalize()) != expected
        {
            return Err(fail("hash"));
        }
        Ok(())
    }

    /// Kanal höflich beenden.
    pub async fn close(mut self) {
        let _ = self.stream.write_all(&[BYE, 0, 0, 0, 0]).await;
        let _ = self.stream.shutdown().await;
    }

    async fn read_frame(&mut self, max: usize) -> Result<(u8, Vec<u8>)> {
        tokio::time::timeout(READ_TIMEOUT, async {
            let mut head = [0u8; 5];
            self.stream.read_exact(&mut head).await.map_err(|_| fail("closed"))?;
            let len = u32::from_be_bytes([head[1], head[2], head[3], head[4]]) as usize;
            if len > max {
                return Err(fail("bad_frame"));
            }
            let mut data = vec![0u8; len];
            self.stream.read_exact(&mut data).await.map_err(|_| fail("closed"))?;
            Ok((head[0], data))
        })
        .await
        .map_err(|_| fail("timeout"))?
    }
}

async fn read_relay_frame(stream: &mut TcpStream) -> Result<(u8, Vec<u8>)> {
    let mut head = [0u8; 3];
    stream.read_exact(&mut head).await.map_err(|_| fail("relay_failed"))?;
    let len = u16::from_be_bytes([head[1], head[2]]) as usize;
    if len > MAX_RELAY_FRAME {
        return Err(fail("bad_frame"));
    }
    let mut payload = vec![0u8; len];
    stream.read_exact(&mut payload).await.map_err(|_| fail("relay_failed"))?;
    Ok((head[0], payload))
}

pub(crate) fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex32(s: &str) -> Option<[u8; 32]> {
    if s.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (i, chunk) in s.as_bytes().chunks(2).enumerate() {
        let hi = (chunk[0] as char).to_digit(16)?;
        let lo = (chunk[1] as char).to_digit(16)?;
        out[i] = (hi * 16 + lo) as u8;
    }
    Some(out)
}
