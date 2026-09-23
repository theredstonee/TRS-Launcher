//! Minimales NBT (unkomprimiert, Big-Endian) – gerade genug, um Minecrafts
//! `servers.dat` zu lesen und zu schreiben, ohne fremde Felder zu verlieren.

use crate::{Error, Result};

const MAX_DEPTH: usize = 32;
/// Schutz gegen absurde Längenangaben in kaputten Dateien.
const MAX_ELEMENTS: usize = 1 << 20;

#[derive(Debug, Clone, PartialEq)]
pub enum Tag {
    Byte(i8),
    Short(i16),
    Int(i32),
    Long(i64),
    Float(f32),
    Double(f64),
    ByteArray(Vec<u8>),
    /// Rohbytes (Javas „Modified UTF-8“) – so bleiben fremde Strings beim
    /// Zurückschreiben bitgenau erhalten.
    String(Vec<u8>),
    List(u8, Vec<Tag>),
    Compound(Vec<(Vec<u8>, Tag)>),
    IntArray(Vec<i32>),
    LongArray(Vec<i64>),
}

impl Tag {
    fn id(&self) -> u8 {
        match self {
            Self::Byte(_) => 1,
            Self::Short(_) => 2,
            Self::Int(_) => 3,
            Self::Long(_) => 4,
            Self::Float(_) => 5,
            Self::Double(_) => 6,
            Self::ByteArray(_) => 7,
            Self::String(_) => 8,
            Self::List(..) => 9,
            Self::Compound(_) => 10,
            Self::IntArray(_) => 11,
            Self::LongArray(_) => 12,
        }
    }

    /// Für eigene Werte. Zeichen außerhalb der BMP und NUL kodiert Java anders
    /// als UTF-8 – die lassen wir weg, statt kaputte Strings zu schreiben.
    pub fn string(text: &str) -> Self {
        let clean: String = text.chars().filter(|&c| c != '\0' && (c as u32) <= 0xFFFF).collect();
        Self::String(clean.into_bytes())
    }

    pub fn as_str_lossy(&self) -> Option<String> {
        match self {
            Self::String(bytes) => Some(String::from_utf8_lossy(bytes).into_owned()),
            _ => None,
        }
    }
}

/// Zugriff auf Compound-Felder.
pub fn get<'a>(fields: &'a [(Vec<u8>, Tag)], key: &str) -> Option<&'a Tag> {
    fields.iter().find(|(k, _)| k == key.as_bytes()).map(|(_, v)| v)
}

pub fn set(fields: &mut Vec<(Vec<u8>, Tag)>, key: &str, value: Tag) {
    match fields.iter_mut().find(|(k, _)| k == key.as_bytes()) {
        Some((_, slot)) => *slot = value,
        None => fields.push((key.as_bytes().to_vec(), value)),
    }
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

fn corrupt() -> Error {
    Error::validation(crate::msg!("nbt.corrupt", "Die NBT-Datei ist beschädigt."))
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.pos.checked_add(n).filter(|&e| e <= self.data.len()).ok_or_else(corrupt)?;
        let slice = &self.data[self.pos..end];
        self.pos = end;
        Ok(slice)
    }

    fn array<const N: usize>(&mut self) -> Result<[u8; N]> {
        Ok(self.take(N)?.try_into().expect("Länge stimmt"))
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_be_bytes(self.array()?))
    }

    fn len(&mut self) -> Result<usize> {
        usize::try_from(self.i32()?).ok().filter(|&n| n <= MAX_ELEMENTS).ok_or_else(corrupt)
    }

    fn string_bytes(&mut self) -> Result<Vec<u8>> {
        let len = usize::from(u16::from_be_bytes(self.array()?));
        Ok(self.take(len)?.to_vec())
    }

    fn payload(&mut self, id: u8, depth: usize) -> Result<Tag> {
        if depth > MAX_DEPTH {
            return Err(corrupt());
        }
        Ok(match id {
            1 => Tag::Byte(self.u8()? as i8),
            2 => Tag::Short(i16::from_be_bytes(self.array()?)),
            3 => Tag::Int(self.i32()?),
            4 => Tag::Long(i64::from_be_bytes(self.array()?)),
            5 => Tag::Float(f32::from_be_bytes(self.array()?)),
            6 => Tag::Double(f64::from_be_bytes(self.array()?)),
            7 => {
                let n = self.len()?;
                Tag::ByteArray(self.take(n)?.to_vec())
            }
            8 => Tag::String(self.string_bytes()?),
            9 => {
                let elem = self.u8()?;
                let n = self.len()?;
                let mut items = Vec::with_capacity(n.min(1024));
                for _ in 0..n {
                    items.push(self.payload(elem, depth + 1)?);
                }
                Tag::List(elem, items)
            }
            10 => {
                let mut fields = Vec::new();
                loop {
                    let field_id = self.u8()?;
                    if field_id == 0 {
                        break;
                    }
                    let name = self.string_bytes()?;
                    fields.push((name, self.payload(field_id, depth + 1)?));
                }
                Tag::Compound(fields)
            }
            11 => {
                let n = self.len()?;
                (0..n).map(|_| self.i32()).collect::<Result<_>>().map(Tag::IntArray)?
            }
            12 => {
                let n = self.len()?;
                (0..n)
                    .map(|_| Ok(i64::from_be_bytes(self.array()?)))
                    .collect::<Result<_>>()
                    .map(Tag::LongArray)?
            }
            _ => return Err(corrupt()),
        })
    }
}

/// Liest eine Datei mit benanntem Wurzel-Compound; liefert dessen Felder.
pub fn read_root(data: &[u8]) -> Result<Vec<(Vec<u8>, Tag)>> {
    let mut reader = Reader { data, pos: 0 };
    if reader.u8()? != 10 {
        return Err(corrupt());
    }
    reader.string_bytes()?;
    match reader.payload(10, 0)? {
        Tag::Compound(fields) => Ok(fields),
        _ => Err(corrupt()),
    }
}

fn write_string(out: &mut Vec<u8>, bytes: &[u8]) {
    let len = bytes.len().min(usize::from(u16::MAX));
    out.extend_from_slice(&(len as u16).to_be_bytes());
    out.extend_from_slice(&bytes[..len]);
}

fn write_payload(out: &mut Vec<u8>, tag: &Tag) {
    match tag {
        Tag::Byte(v) => out.push(*v as u8),
        Tag::Short(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::Int(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::Long(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::Float(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::Double(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::ByteArray(v) => {
            out.extend_from_slice(&(v.len() as i32).to_be_bytes());
            out.extend_from_slice(v);
        }
        Tag::String(v) => write_string(out, v),
        Tag::List(elem, items) => {
            out.push(if items.is_empty() { 0 } else { *elem });
            out.extend_from_slice(&(items.len() as i32).to_be_bytes());
            items.iter().for_each(|item| write_payload(out, item));
        }
        Tag::Compound(fields) => {
            for (name, value) in fields {
                out.push(value.id());
                write_string(out, name);
                write_payload(out, value);
            }
            out.push(0);
        }
        Tag::IntArray(v) => {
            out.extend_from_slice(&(v.len() as i32).to_be_bytes());
            v.iter().for_each(|x| out.extend_from_slice(&x.to_be_bytes()));
        }
        Tag::LongArray(v) => {
            out.extend_from_slice(&(v.len() as i32).to_be_bytes());
            v.iter().for_each(|x| out.extend_from_slice(&x.to_be_bytes()));
        }
    }
}

pub fn write_root(fields: &[(Vec<u8>, Tag)]) -> Vec<u8> {
    let mut out = vec![10, 0, 0];
    write_payload(&mut out, &Tag::Compound(fields.to_vec()));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_preserves_unknown_fields() {
        let entry = Tag::Compound(vec![
            (b"name".to_vec(), Tag::string("Mein Server")),
            (b"ip".to_vec(), Tag::string("play.example.org")),
            (b"icon".to_vec(), Tag::String(vec![0xC0, 0x80, b'x'])), // Modified-UTF-8-NUL bleibt roh erhalten
            (b"acceptTextures".to_vec(), Tag::Byte(1)),
            (b"zahlen".to_vec(), Tag::LongArray(vec![1, -2, i64::MAX])),
        ]);
        let root = vec![(b"servers".to_vec(), Tag::List(10, vec![entry]))];
        let bytes = write_root(&root);
        assert_eq!(read_root(&bytes).unwrap(), root);
    }

    #[test]
    fn reads_vanilla_layout() {
        // TAG_Compound "" { TAG_List "servers" [ { TAG_String "ip" = "a.b" } ] }
        let bytes = [
            10, 0, 0, 9, 0, 7, b's', b'e', b'r', b'v', b'e', b'r', b's', 10, 0, 0, 0, 1, 8, 0, 2, b'i', b'p', 0, 3, b'a',
            b'.', b'b', 0, 0,
        ];
        let root = read_root(&bytes).unwrap();
        let Some(Tag::List(10, servers)) = get(&root, "servers") else { panic!("keine Liste") };
        let Tag::Compound(fields) = &servers[0] else { panic!("kein Compound") };
        assert_eq!(get(fields, "ip").unwrap().as_str_lossy().as_deref(), Some("a.b"));
    }

    #[test]
    fn rejects_garbage_without_panicking() {
        assert!(read_root(&[]).is_err());
        assert!(read_root(&[1, 2, 3]).is_err());
        assert!(read_root(&[10, 0, 0, 9, 0, 1, b'x', 10, 0x7f, 0xff, 0xff, 0xff]).is_err());
        // Endlos verschachtelte Listen
        let mut deep = vec![10, 0, 0, 9, 0, 1, b'l'];
        for _ in 0..100 {
            deep.extend_from_slice(&[9, 0, 0, 0, 1]);
        }
        assert!(read_root(&deep).is_err());
    }

    #[test]
    fn own_strings_drop_unencodable_chars() {
        assert_eq!(Tag::string("a\0b😀c"), Tag::String(b"abc".to_vec()));
    }
}
