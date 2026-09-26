//! CurseForge-Fingerprint: MurmurHash2 (32 Bit, Seed 1) über die Datei ohne
//! Leerraum-Bytes (Tab, LF, CR, Leerzeichen) – so rechnet CurseForge selbst.

const M: u32 = 0x5bd1_e995;
const R: u32 = 24;

/// Leerraum, den CurseForge vor dem Hashen entfernt.
fn skipped(b: u8) -> bool {
    matches!(b, 9 | 10 | 13 | 32)
}

/// MurmurHash2 (32 Bit) wie im Original von Austin Appleby.
pub fn murmur2(data: &[u8], seed: u32) -> u32 {
    let mut h = seed ^ data.len() as u32;
    let (chunks, rest) = data.as_chunks::<4>();
    for c in chunks {
        let mut k = u32::from_le_bytes(*c);
        k = k.wrapping_mul(M);
        k ^= k >> R;
        k = k.wrapping_mul(M);
        h = h.wrapping_mul(M);
        h ^= k;
    }
    if !rest.is_empty() {
        if rest.len() >= 3 {
            h ^= u32::from(rest[2]) << 16;
        }
        if rest.len() >= 2 {
            h ^= u32::from(rest[1]) << 8;
        }
        h ^= u32::from(rest[0]);
        h = h.wrapping_mul(M);
    }
    h ^= h >> 13;
    h = h.wrapping_mul(M);
    h ^= h >> 15;
    h
}

/// Fingerprint einer Datei (ganzer Inhalt im Speicher – Mods sind klein genug).
pub fn curseforge_fingerprint(data: &[u8]) -> u32 {
    let filtered: Vec<u8> = data.iter().copied().filter(|b| !skipped(*b)).collect();
    murmur2(&filtered, 1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn murmur2_matches_reference_values() {
        // Referenzwerte der Originalimplementierung (MurmurHash2, 32 Bit).
        assert_eq!(murmur2(b"", 1), 0x5bd1_5e36);
        assert_eq!(murmur2(b"", 0), 0);
        assert_eq!(murmur2(b"hello world", 1), 0x83ea_5dee);
        // Jede Restlänge (0–3 Bytes) läuft durch den Schwanz.
        let a = murmur2(b"abcd", 1);
        let b = murmur2(b"abcde", 1);
        let c = murmur2(b"abcdef", 1);
        let d = murmur2(b"abcdefg", 1);
        assert!(a != b && b != c && c != d);
    }

    #[test]
    fn fingerprint_ignores_whitespace() {
        assert_eq!(curseforge_fingerprint(b"a b\tc\r\nd"), curseforge_fingerprint(b"abcd"));
        assert_ne!(curseforge_fingerprint(b"abcd"), curseforge_fingerprint(b"abce"));
    }
}
