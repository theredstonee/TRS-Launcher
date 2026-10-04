//! Minimaler DNS-Client für SRV-Einträge (`_minecraft._tcp.<host>`) und – für
//! „Schnell verbinden“ – A/AAAA samt TTL.
//!
//! Unter Windows fragt der Launcher den System-Resolver (`DnsQuery_W`); unter
//! Linux gibt es dafür keine libc-Funktion ohne Zusatz-Bibliothek. Statt
//! einer großen DNS-Crate reicht hier eine einzelne UDP-Anfrage an die
//! Nameserver aus `/etc/resolv.conf` (bei systemd-resolved `127.0.0.53`).

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

pub const TYPE_A: u16 = 1;
pub const TYPE_AAAA: u16 = 28;
pub const TYPE_SRV: u16 = 33;
const CLASS_IN: u16 = 1;
/// Höchstens so viele Kompressions-Sprünge je Name (Schutz vor Schleifen).
const MAX_POINTERS: usize = 16;
/// Mehr Einträge je Antwort werden nicht ausgewertet.
const MAX_RECORDS: usize = 32;

/// Ein SRV-Eintrag, wie er aus dem DNS kommt (Ziel ohne Schlusspunkt).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SrvRecord {
    pub priority: u16,
    pub weight: u16,
    pub port: u16,
    pub target: String,
}

/// Ergebnis einer SRV-Abfrage.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SrvLookup {
    /// Einträge samt kleinster TTL (Sekunden).
    Found(Vec<SrvRecord>, u32),
    /// Sicher keiner (NXDOMAIN oder Antwort ohne SRV).
    None,
    /// Nicht feststellbar (Zeitüberschreitung, Serverfehler …).
    Unknown,
}

/// Ergebnis einer A- oder AAAA-Abfrage.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AddrLookup {
    /// Adressen in Resolver-Reihenfolge, TTL = kleinste (Sekunden).
    Found(Vec<IpAddr>, u32),
    None,
    Unknown,
}

/// Der Eintrag, den das Spiel nimmt: kleinste Priorität, darin das größte
/// Gewicht, bei Gleichstand der erste (wie `SrvRecord.pick` im TRS Client).
pub fn pick_srv(records: &[SrvRecord]) -> Option<&SrvRecord> {
    let mut best: Option<&SrvRecord> = None;
    for r in records.iter().filter(|r| r.port != 0 && !r.target.is_empty() && r.target != ".") {
        if best.is_none_or(|b| r.priority < b.priority || (r.priority == b.priority && r.weight > b.weight)) {
            best = Some(r);
        }
    }
    best
}

/// Baut eine DNS-Anfrage (Rekursion erwünscht) für `name` vom Typ SRV.
pub fn build_srv_query(id: u16, name: &str) -> Option<Vec<u8>> {
    build_query(id, name, TYPE_SRV)
}

/// Baut eine DNS-Anfrage (Rekursion erwünscht) für `name` vom Typ `qtype`.
pub fn build_query(id: u16, name: &str, qtype: u16) -> Option<Vec<u8>> {
    let name = name.trim_end_matches('.');
    if name.is_empty() || name.len() > 253 {
        return None;
    }
    let mut packet = Vec::with_capacity(18 + name.len());
    packet.extend_from_slice(&id.to_be_bytes());
    packet.extend_from_slice(&0x0100u16.to_be_bytes()); // RD
    packet.extend_from_slice(&1u16.to_be_bytes()); // QDCOUNT
    packet.extend_from_slice(&[0, 0, 0, 0, 0, 0]); // AN/NS/AR
    for label in name.split('.') {
        let ok = !label.is_empty()
            && label.len() <= 63
            && label.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
        if !ok {
            return None;
        }
        packet.push(label.len() as u8);
        packet.extend_from_slice(label.as_bytes());
    }
    packet.push(0);
    packet.extend_from_slice(&qtype.to_be_bytes());
    packet.extend_from_slice(&CLASS_IN.to_be_bytes());
    Some(packet)
}

fn u16_at(packet: &[u8], pos: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*packet.get(pos)?, *packet.get(pos + 1)?]))
}

/// Liest einen (ggf. komprimierten) Namen ab `pos`. Liefert den Namen und die
/// Position direkt hinter dem Namen im ursprünglichen Datenstrom.
fn read_name(packet: &[u8], mut pos: usize) -> Option<(String, usize)> {
    let mut labels: Vec<String> = Vec::new();
    let mut end = None;
    let mut jumps = 0;
    loop {
        let len = *packet.get(pos)? as usize;
        match len {
            0 => {
                let after = end.unwrap_or(pos + 1);
                return Some((labels.join("."), after));
            }
            l if l & 0xC0 == 0xC0 => {
                jumps += 1;
                if jumps > MAX_POINTERS {
                    return None;
                }
                let target = (u16_at(packet, pos)? & 0x3FFF) as usize;
                end.get_or_insert(pos + 2);
                pos = target;
            }
            l if l <= 63 => {
                let label = packet.get(pos + 1..pos + 1 + l)?;
                labels.push(String::from_utf8_lossy(label).into_owned());
                if labels.iter().map(|l| l.len() + 1).sum::<usize>() > 255 {
                    return None;
                }
                pos += 1 + l;
            }
            _ => return None,
        }
    }
}

/// Datensatz aus einer Antwort.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RecordData {
    Srv(SrvRecord),
    Ip(IpAddr),
}

/// Ausgewertete Antwort: passende Einträge (mit TTL) oder sicher keine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Answer {
    Records(Vec<(u32, RecordData)>),
    /// NXDOMAIN oder NOERROR ohne Eintrag dieses Typs.
    Empty,
}

/// Wertet eine Antwort auf eine Anfrage vom Typ `qtype` aus. `None` bei
/// falscher ID, Serverfehler, abgeschnittener oder kaputter Antwort.
pub fn parse_response(id: u16, packet: &[u8], qtype: u16) -> Option<Answer> {
    if u16_at(packet, 0)? != id {
        return None;
    }
    let flags = u16_at(packet, 2)?;
    // Muss eine Antwort sein (QR); NXDOMAIN (3) heißt sicher „gibt es nicht“.
    if flags & 0x8000 == 0 {
        return None;
    }
    match flags & 0x000F {
        0 => {}
        3 => return Some(Answer::Empty),
        _ => return None,
    }
    let truncated = flags & 0x0200 != 0;
    let questions = u16_at(packet, 4)?;
    let answers = u16_at(packet, 6)?;
    let mut pos = 12;
    for _ in 0..questions {
        pos = read_name(packet, pos)?.1 + 4;
    }
    let mut out = Vec::new();
    for _ in 0..answers {
        let (_, after) = read_name(packet, pos)?;
        let kind = u16_at(packet, after)?;
        let ttl = (u32::from(u16_at(packet, after + 4)?) << 16) | u32::from(u16_at(packet, after + 6)?);
        let rdlen = u16_at(packet, after + 8)? as usize;
        let data = after + 10;
        if data + rdlen > packet.len() {
            return None;
        }
        if kind == qtype && out.len() < MAX_RECORDS {
            let record = match kind {
                TYPE_SRV if rdlen >= 7 => Some(RecordData::Srv(SrvRecord {
                    priority: u16_at(packet, data)?,
                    weight: u16_at(packet, data + 2)?,
                    port: u16_at(packet, data + 4)?,
                    target: read_name(packet, data + 6)?.0,
                })),
                TYPE_A if rdlen == 4 => {
                    let b = packet.get(data..data + 4)?;
                    Some(RecordData::Ip(IpAddr::V4(Ipv4Addr::new(b[0], b[1], b[2], b[3]))))
                }
                TYPE_AAAA if rdlen == 16 => {
                    let b: [u8; 16] = packet.get(data..data + 16)?.try_into().ok()?;
                    Some(RecordData::Ip(IpAddr::V6(Ipv6Addr::from(b))))
                }
                _ => None,
            };
            if let Some(record) = record {
                out.push((ttl, record));
            }
        }
        pos = data + rdlen;
    }
    if out.is_empty() {
        // Abgeschnitten ohne Einträge: über UDP nicht entscheidbar.
        return if truncated { None } else { Some(Answer::Empty) };
    }
    Some(Answer::Records(out))
}

/// Wertet eine SRV-Antwort aus: niedrigste Priorität gewinnt, bei Gleichstand das
/// höchste Gewicht. `None` bei falscher ID, Fehlercode oder ohne SRV-Eintrag.
pub fn parse_srv_response(id: u16, packet: &[u8]) -> Option<(String, u16)> {
    let records = srv_records(parse_response(id, packet, TYPE_SRV)?);
    pick_srv(&records).map(|r| (r.target.clone(), r.port))
}

fn srv_records(answer: Answer) -> Vec<SrvRecord> {
    match answer {
        Answer::Records(records) => records
            .into_iter()
            .filter_map(|(_, r)| match r {
                RecordData::Srv(srv) => Some(srv),
                RecordData::Ip(_) => None,
            })
            .collect(),
        Answer::Empty => Vec::new(),
    }
}

/// Antwort → [`SrvLookup`].
pub fn srv_lookup(answer: Option<Answer>) -> SrvLookup {
    let Some(answer) = answer else { return SrvLookup::Unknown };
    let ttl = match &answer {
        Answer::Records(r) => r.iter().map(|(ttl, _)| *ttl).min().unwrap_or(0),
        Answer::Empty => 0,
    };
    let records = srv_records(answer);
    if records.is_empty() { SrvLookup::None } else { SrvLookup::Found(records, ttl) }
}

/// Antwort → [`AddrLookup`].
pub fn addr_lookup(answer: Option<Answer>) -> AddrLookup {
    match answer {
        None => AddrLookup::Unknown,
        Some(Answer::Empty) => AddrLookup::None,
        Some(Answer::Records(records)) => {
            let ttl = records.iter().map(|(ttl, _)| *ttl).min().unwrap_or(0);
            let ips: Vec<IpAddr> = records
                .into_iter()
                .filter_map(|(_, r)| match r {
                    RecordData::Ip(ip) => Some(ip),
                    RecordData::Srv(_) => None,
                })
                .collect();
            if ips.is_empty() { AddrLookup::None } else { AddrLookup::Found(ips, ttl) }
        }
    }
}

/// Nameserver aus dem Inhalt einer `resolv.conf` (höchstens drei, wie glibc).
pub fn nameservers(resolv_conf: &str) -> Vec<IpAddr> {
    resolv_conf
        .lines()
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            (parts.next()? == "nameserver").then(|| parts.next()?.parse().ok())?
        })
        .take(3)
        .collect()
}

/// Fragt die System-Nameserver per UDP (blockierend, je Server höchstens 2 s).
#[cfg(unix)]
pub fn lookup_srv(name: &str) -> Option<(String, u16)> {
    let records = srv_records(lookup(name, TYPE_SRV)?);
    pick_srv(&records).map(|r| (r.target.clone(), r.port))
}

/// Eine Anfrage vom Typ `qtype` an die System-Nameserver (blockierend, je Server
/// höchstens 2 s). `None` = keine verwertbare Antwort.
#[cfg(unix)]
pub fn lookup(name: &str, qtype: u16) -> Option<Answer> {
    use std::net::{SocketAddr, UdpSocket};
    use std::time::Duration;

    let conf = std::fs::read_to_string("/etc/resolv.conf").unwrap_or_default();
    let mut servers = nameservers(&conf);
    if servers.is_empty() {
        servers.push(IpAddr::from([127, 0, 0, 53]));
    }
    let random = uuid::Uuid::new_v4();
    let id = u16::from_be_bytes([random.as_bytes()[0], random.as_bytes()[1]]);
    let query = build_query(id, name, qtype)?;
    for server in servers {
        let bind: SocketAddr = if server.is_ipv4() { ([0, 0, 0, 0], 0).into() } else { (std::net::Ipv6Addr::UNSPECIFIED, 0).into() };
        let Ok(socket) = UdpSocket::bind(bind) else { continue };
        let _ = socket.set_read_timeout(Some(Duration::from_secs(2)));
        if socket.connect((server, 53)).is_err() || socket.send(&query).is_err() {
            continue;
        }
        let mut buf = [0u8; 1500];
        // Fremde/verspätete Pakete mit anderer ID überspringen.
        for _ in 0..3 {
            let Ok(len) = socket.recv(&mut buf) else { break };
            if u16_at(&buf[..len], 0) == Some(id) {
                if let Some(answer) = parse_response(id, &buf[..len], qtype) {
                    return Some(answer);
                }
                break;
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Antwort wie von einem echten Resolver: Frage + zwei SRV-Einträge, Ziel komprimiert.
    fn response(id: u16) -> Vec<u8> {
        let mut p = build_srv_query(id, "_minecraft._tcp.example.org").unwrap();
        p[2] = 0x81;
        p[3] = 0x80; // QR, RD, RA, RCODE 0
        p[7] = 2; // ANCOUNT
        let record = |p: &mut Vec<u8>, priority: u16, weight: u16, port: u16, target: &[u8]| {
            p.extend_from_slice(&[0xC0, 12]); // Name = Frage
            p.extend_from_slice(&TYPE_SRV.to_be_bytes());
            p.extend_from_slice(&CLASS_IN.to_be_bytes());
            p.extend_from_slice(&300u32.to_be_bytes());
            p.extend_from_slice(&((6 + target.len()) as u16).to_be_bytes());
            p.extend_from_slice(&priority.to_be_bytes());
            p.extend_from_slice(&weight.to_be_bytes());
            p.extend_from_slice(&port.to_be_bytes());
            p.extend_from_slice(target);
        };
        // "mc" + Verweis auf "example.org" (Offset 12 + 11 + 5 = 28)
        record(&mut p, 10, 5, 25570, &[2, b'm', b'c', 0xC0, 28]);
        record(&mut p, 5, 1, 25565, &[4, b'm', b'a', b'i', b'n', 0xC0, 28]);
        p
    }

    #[test]
    fn query_layout() {
        let q = build_srv_query(0x1234, "_minecraft._tcp.example.org.").unwrap();
        assert_eq!(&q[..4], &[0x12, 0x34, 0x01, 0x00]);
        assert_eq!(q[12], 10);
        assert_eq!(&q[13..23], b"_minecraft");
        assert!(q.ends_with(&[0, 0, 33, 0, 1]));
        assert!(build_query(1, "example.org", TYPE_AAAA).unwrap().ends_with(&[0, 28, 0, 1]));
        for bad in ["", "a..b", "a b.c", &"x".repeat(64), "ä.de"] {
            assert!(build_srv_query(1, bad).is_none(), "{bad:?}");
        }
    }

    #[test]
    fn picks_lowest_priority() {
        let packet = response(7);
        assert_eq!(parse_srv_response(7, &packet), Some(("main.example.org".into(), 25565)));
        assert_eq!(parse_srv_response(8, &packet), None, "falsche ID");
        let mut error = packet.clone();
        error[3] = 0x83; // NXDOMAIN
        assert_eq!(parse_srv_response(7, &error), None);
        assert_eq!(srv_lookup(parse_response(7, &error, TYPE_SRV)), SrvLookup::None, "NXDOMAIN = sicher keiner");
        let mut failure = packet.clone();
        failure[3] = 0x82; // SERVFAIL
        assert_eq!(srv_lookup(parse_response(7, &failure, TYPE_SRV)), SrvLookup::Unknown);
        // Abgeschnitten → kein Panik, kein Ergebnis.
        assert_eq!(parse_srv_response(7, &packet[..packet.len() - 3]), None);
        let SrvLookup::Found(records, ttl) = srv_lookup(parse_response(7, &packet, TYPE_SRV)) else { panic!() };
        assert_eq!((records.len(), ttl), (2, 300));
    }

    #[test]
    fn srv_pick_rule() {
        let r = |priority, weight, target: &str| SrvRecord { priority, weight, port: 25565, target: target.into() };
        // Kleinste Priorität, darin größtes Gewicht, bei Gleichstand der erste.
        let list = [r(10, 50, "a"), r(5, 1, "b"), r(5, 9, "c"), r(5, 9, "d"), r(0, 0, ".")];
        assert_eq!(pick_srv(&list).unwrap().target, "c");
        assert!(pick_srv(&[r(0, 0, ""), SrvRecord { port: 0, ..r(0, 0, "x") }]).is_none());
        assert!(pick_srv(&[]).is_none());
    }

    #[test]
    fn address_answers() {
        let mut p = build_query(3, "example.org", TYPE_A).unwrap();
        p[2] = 0x81;
        p[3] = 0x80;
        p[7] = 3;
        let mut record = |kind: u16, ttl: u32, data: &[u8]| {
            p.extend_from_slice(&[0xC0, 12]);
            p.extend_from_slice(&kind.to_be_bytes());
            p.extend_from_slice(&CLASS_IN.to_be_bytes());
            p.extend_from_slice(&ttl.to_be_bytes());
            p.extend_from_slice(&(data.len() as u16).to_be_bytes());
            p.extend_from_slice(data);
        };
        record(TYPE_A, 90, &[203, 0, 113, 7]);
        record(5, 10, &[0xC0, 12]); // CNAME wird übersprungen
        record(TYPE_A, 60, &[203, 0, 113, 8]);
        let AddrLookup::Found(ips, ttl) = addr_lookup(parse_response(3, &p, TYPE_A)) else { panic!() };
        assert_eq!(ips, vec![IpAddr::from([203, 0, 113, 7]), IpAddr::from([203, 0, 113, 8])]);
        assert_eq!(ttl, 60);
        // Gleiche Antwort als AAAA gelesen: keine passenden Einträge.
        assert_eq!(addr_lookup(parse_response(3, &p, TYPE_AAAA)), AddrLookup::None);
        assert_eq!(addr_lookup(None), AddrLookup::Unknown);
    }

    #[test]
    fn pointer_loops_are_rejected() {
        let mut p = response(1);
        let len = p.len();
        // Letztes Ziel zeigt auf sich selbst.
        p[len - 2] = 0xC0;
        p[len - 1] = (len - 2) as u8;
        assert_eq!(parse_srv_response(1, &p), None);
    }

    #[test]
    fn reads_resolv_conf() {
        let conf = "# kommentar\nnameserver 127.0.0.53\nsearch lan\nnameserver ::1\nnameserver fe80::1%eth0\noptions edns0\n";
        assert_eq!(nameservers(conf), vec![IpAddr::from([127, 0, 0, 53]), "::1".parse::<IpAddr>().unwrap()]);
    }
}
