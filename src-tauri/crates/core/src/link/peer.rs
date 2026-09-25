//! Wem gehört die Gegenstelle einer Loopback-Verbindung? Der Link nimmt
//! Konten-Anfragen nur vom Prozessbaum des gestarteten Spiels an.
//!
//! - Windows: `GetExtendedTcpTable` (Besitzer-PID je Verbindung).
//! - Linux: Socket-Inode aus `/proc/net/tcp`, dann die offenen Dateien der
//!   Prozesse des Spielbaums (`/proc/<pid>/fd`).

use std::collections::HashSet;

/// `Some(true)`: Gegenstelle gehört zum Spiel (PID oder ein Kindprozess).
/// `Some(false)`: gehört nachweislich jemand anderem.
/// `None`: nicht feststellbar (Plattform, Fehler, PID des Spiels unbekannt).
pub fn verify(peer_port: u16, listen_port: u16, game_pid: Option<u32>) -> Option<bool> {
    let game_pid = game_pid?;
    let tree = process_tree(game_pid);
    owned_by(peer_port, listen_port, &tree)
}

#[cfg(windows)]
pub fn process_tree(root: u32) -> HashSet<u32> {
    crate::clips::window::process_tree(root)
}

#[cfg(windows)]
fn owned_by(peer_port: u16, listen_port: u16, tree: &HashSet<u32>) -> Option<bool> {
    use windows::Win32::NetworkManagement::IpHelper::{
        GetExtendedTcpTable, MIB_TCPROW_OWNER_PID, TCP_TABLE_OWNER_PID_CONNECTIONS,
    };
    const AF_INET: u32 = 2;
    const NO_ERROR: u32 = 0;
    const ERROR_INSUFFICIENT_BUFFER: u32 = 122;

    let mut size: u32 = 0;
    // SAFETY: Größenabfrage ohne Puffer.
    let first = unsafe { GetExtendedTcpTable(None, &raw mut size, false, AF_INET, TCP_TABLE_OWNER_PID_CONNECTIONS, 0) };
    if first != ERROR_INSUFFICIENT_BUFFER && first != NO_ERROR {
        return None;
    }
    // Puffer als u32 (4-Byte-Ausrichtung wie die Tabelle); etwas Luft für neue Verbindungen.
    let mut buf: Vec<u32> = vec![0; (size as usize).div_ceil(4) + 256];
    let mut size = (buf.len() * 4) as u32;
    // SAFETY: `buf` ist `size` Bytes groß und lebt während des Aufrufs.
    let rc = unsafe {
        GetExtendedTcpTable(Some(buf.as_mut_ptr().cast()), &raw mut size, false, AF_INET, TCP_TABLE_OWNER_PID_CONNECTIONS, 0)
    };
    if rc != NO_ERROR {
        return None;
    }
    let count = buf[0] as usize;
    let row_words = std::mem::size_of::<MIB_TCPROW_OWNER_PID>() / 4;
    if 1 + count * row_words > buf.len() {
        return None;
    }
    let port = |raw: u32| u16::from_be((raw & 0xffff) as u16);
    for i in 0..count {
        let row = &buf[1 + i * row_words..1 + (i + 1) * row_words];
        // Felder: State, LocalAddr, LocalPort, RemoteAddr, RemotePort, OwningPid.
        let (local_port, remote_port, pid) = (port(row[2]), port(row[4]), row[5]);
        if local_port == peer_port && remote_port == listen_port {
            return Some(tree.contains(&pid));
        }
    }
    None
}

#[cfg(unix)]
pub fn process_tree(root: u32) -> HashSet<u32> {
    let mut pairs = Vec::new();
    if let Ok(dir) = std::fs::read_dir("/proc") {
        for entry in dir.flatten() {
            let Some(pid) = entry.file_name().to_str().and_then(|s| s.parse::<u32>().ok()) else { continue };
            let Ok(stat) = std::fs::read_to_string(entry.path().join("stat")) else { continue };
            // Format: pid (comm) state ppid … – comm kann Leerzeichen/Klammern enthalten.
            let Some(rest) = stat.rfind(')').map(|i| &stat[i + 1..]) else { continue };
            if let Some(ppid) = rest.split_whitespace().nth(1).and_then(|s| s.parse::<u32>().ok()) {
                pairs.push((pid, ppid));
            }
        }
    }
    let mut tree = HashSet::from([root]);
    loop {
        let before = tree.len();
        for &(pid, parent) in &pairs {
            if pid != 0 && tree.contains(&parent) {
                tree.insert(pid);
            }
        }
        if tree.len() == before || tree.len() > 64 {
            break;
        }
    }
    tree
}

#[cfg(unix)]
fn owned_by(peer_port: u16, listen_port: u16, tree: &HashSet<u32>) -> Option<bool> {
    let table = std::fs::read_to_string("/proc/net/tcp").ok()?;
    let inode = socket_inode(&table, peer_port, listen_port)?;
    let needle = format!("socket:[{inode}]");
    for pid in tree {
        let Ok(fds) = std::fs::read_dir(format!("/proc/{pid}/fd")) else { continue };
        for fd in fds.flatten() {
            if std::fs::read_link(fd.path()).is_ok_and(|target| target.as_os_str() == needle.as_str()) {
                return Some(true);
            }
        }
    }
    // Die Verbindung gibt es, aber kein Prozess des Spiels hält sie.
    Some(false)
}

/// Inode der Verbindung `127.0.0.1:peer_port → :listen_port` aus `/proc/net/tcp`.
#[cfg(any(unix, test))]
fn socket_inode(table: &str, peer_port: u16, listen_port: u16) -> Option<u64> {
    let port_of = |addr: &str| addr.rsplit(':').next().and_then(|p| u16::from_str_radix(p, 16).ok());
    table.lines().skip(1).find_map(|line| {
        let cols: Vec<&str> = line.split_whitespace().collect();
        let (local, remote, inode) = (cols.get(1)?, cols.get(2)?, cols.get(9)?);
        (port_of(local)? == peer_port && port_of(remote)? == listen_port).then(|| inode.parse().ok()).flatten()
    })
}

#[cfg(not(any(windows, unix)))]
pub fn process_tree(root: u32) -> HashSet<u32> {
    HashSet::from([root])
}

#[cfg(not(any(windows, unix)))]
fn owned_by(_peer_port: u16, _listen_port: u16, _tree: &HashSet<u32>) -> Option<bool> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn liest_inode_aus_proc_net_tcp() {
        let table = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n\
   0: 0100007F:D431 0100007F:1F90 01 00000000:00000000 00:00000000 00000000  1000        0 424242 1 0000000000000000 20 4 30 10 -1\n\
   1: 0100007F:1F90 0100007F:D431 01 00000000:00000000 00:00000000 00000000  1000        0 424243 1 0000000000000000 20 4 30 10 -1\n";
        assert_eq!(socket_inode(table, 0xD431, 0x1F90), Some(424242));
        assert_eq!(socket_inode(table, 0x1F90, 0xD431), Some(424243));
        assert_eq!(socket_inode(table, 1, 2), None);
    }

    #[test]
    fn ohne_spiel_pid_nicht_feststellbar() {
        assert_eq!(verify(1234, 5678, None), None);
    }

    #[test]
    fn eigener_prozessbaum_enthaelt_mich() {
        assert!(process_tree(std::process::id()).contains(&std::process::id()));
    }
}
