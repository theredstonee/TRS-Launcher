use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;

use serde_json::json;
use sha1::Sha1;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

use super::relay::{self, FileChannel, RelayGrant};
use super::*;
use crate::instance::{Loader, LoaderKind, NewInstance};
use crate::trs_api::types::UserRef;

const ROOM: &str = "h0123456789abcdef0123";

fn h(c: char, n: usize) -> String {
    std::iter::repeat_n(c, n).collect()
}

fn hex(b: &[u8]) -> String {
    relay::hex(b)
}

fn host_mod(name: &str, data: &[u8], required: bool) -> SharedMod {
    SharedMod {
        name: name.into(),
        version: "1.0".into(),
        file: format!("{name}.jar"),
        size: data.len() as u64,
        required,
        source: ModSource::Host,
        project_id: None,
        file_id: None,
        sha1: hex(&Sha1::digest(data)),
        sha512: None,
        sha256: Some(hex(&Sha256::digest(data))),
        fingerprint: None,
    }
}

// --- Liste ------------------------------------------------------------------------------

#[test]
fn content_is_cleaned_like_the_api() {
    let raw: ApiContent = serde_json::from_value(json!({
        "roomId": ROOM,
        "mods": [
            { "name": "Gut", "version": "1", "file": "gut.jar", "size": 10, "required": true, "source": "host", "sha1": h('a', 40), "sha256": h('b', 64) },
            { "name": "Pfad", "file": "../../evil.jar", "size": 10, "required": true, "source": "host", "sha1": h('1', 40), "sha256": h('b', 64) },
            { "name": "Groß", "file": "big.jar", "size": MAX_HOST_FILE + 1, "required": true, "source": "host", "sha1": h('2', 40), "sha256": h('b', 64) },
            { "name": "OhneHash", "file": "x.jar", "size": 10, "required": true, "source": "host", "sha1": h('3', 40) },
            { "name": "Store", "file": "s.jar", "size": 10, "required": false, "source": "modrinth", "projectId": "AANobbMI", "fileId": "Yp8wLY1P", "sha1": h('4', 40), "sha512": h('c', 128) },
            { "name": "StoreKaputt", "file": "s2.jar", "size": 10, "required": false, "source": "modrinth", "projectId": "../x", "fileId": "Yp8wLY1P", "sha1": h('5', 40), "sha512": h('c', 128) },
            { "name": "CF", "file": "c.jar", "size": 10, "required": false, "source": "curseforge", "projectId": "238222", "fileId": "5846800", "sha1": h('6', 40), "fingerprint": 42 },
            { "name": "Doppelt", "file": "d.jar", "size": 10, "required": false, "source": "manual", "sha1": h('a', 40) },
            { "name": "Farbe\u{a7}c \u{202e}rot", "file": "f.jar", "size": 10, "required": false, "source": "manual", "sha1": h('7', 40) },
            { "name": "URL", "file": "u.jar", "size": 10, "required": false, "source": "url", "sha1": h('8', 40) },
            "kaputt"
        ],
        "pack": { "name": "Pack", "size": 5, "sha1": h('9', 40), "sha256": "zz" }
    }))
    .unwrap();
    let c = raw.cleaned(ROOM).unwrap();
    let files: Vec<&str> = c.mods.iter().map(|m| m.file.as_str()).collect();
    assert_eq!(files, ["gut.jar", "s.jar", "c.jar", "f.jar"]);
    assert_eq!(c.mods[3].name, "Farbec rot", "Format-/Steuerzeichen und § fallen weg");
    assert!(c.pack.is_none());
    // Fremde Raum-ID → unbrauchbar.
    let other: ApiContent = serde_json::from_value(json!({ "roomId": "h99999999999999999999", "mods": [] })).unwrap();
    assert!(other.cleaned(ROOM).is_none());
}

#[test]
fn host_total_is_capped() {
    let mods: Vec<serde_json::Value> = (0..9)
        .map(|i| json!({ "name": "m", "file": "m.jar", "size": 60 * 1024 * 1024, "required": true, "source": "host",
            "sha1": format!("{i:040x}"), "sha256": h('b', 64) }))
        .collect();
    let c = ApiContent { room_id: ROOM.into(), mods, pack: None }.cleaned(ROOM).unwrap();
    assert_eq!(c.mods.len(), 8, "höchstens 512 MB vom Host");
}

#[test]
fn file_names_never_leave_the_mods_folder() {
    for input in ["../../evil.jar", "..\\..\\evil.jar", "C:\\Windows\\evil.jar", "/etc/evil.jar", "a/b/c.jar", ".hidden.jar", "x.jar.exe"] {
        let name = safe_file_name(input);
        if let Some(n) = name {
            assert!(!n.contains('/') && !n.contains('\\') && !n.contains("..") && !n.starts_with('.'), "{input} → {n}");
            assert!(n.ends_with(".jar"));
            assert_eq!(Path::new(&n).components().count(), 1);
        }
    }
    assert_eq!(safe_file_name("fabric-api-0.119.2+1.21.11.jar").as_deref(), Some("fabric-api-0.119.2+1.21.11.jar"));
    assert_eq!(safe_file_name("my mod (1).jar").as_deref(), Some("my_mod__1_.jar"));
    assert!(safe_file_name("???.jar").is_none());
    assert!(!valid_jar_name("../x.jar") && !valid_jar_name("x.zip") && valid_jar_name("x (1).jar"));
}

// --- Auswahl / Warn-Pflicht -----------------------------------------------------------------

fn plan(mods: &[&str], trust: bool) -> PreparePlan {
    PreparePlan {
        room_id: ROOM.into(),
        mode: PrepareMode::New,
        base_instance_id: None,
        name: None,
        mods: mods.iter().map(|s| (*s).to_owned()).collect(),
        trust_host: trust,
    }
}

#[test]
fn host_files_need_trust_every_time() {
    let req = host_mod("req", b"required", true);
    let opt = host_mod("opt", b"optional", false);
    let manual = SharedMod { source: ModSource::Manual, sha256: None, ..host_mod("manual", b"m", true) };
    let content = RoomContent { room_id: ROOM.into(), mods: vec![req.clone(), opt.clone(), manual], pack: None };
    // Pflicht vom Host ohne Häkchen → Fehler mit eigenem Code.
    let err = selection(&content, &plan(&[], false)).unwrap_err();
    assert!(matches!(&err, Error::Validation(m) if m.code == "hosting.trustRequired"), "{err:?}");
    // Mit Häkchen: Pflicht immer, optional nur gewählt, „selbst besorgen“ nie.
    let sel = selection(&content, &plan(&[], true)).unwrap();
    assert_eq!(sel.iter().map(|m| m.name.as_str()).collect::<Vec<_>>(), ["req"]);
    let sel = selection(&content, &plan(&[&opt.sha1], true)).unwrap();
    assert_eq!(sel.len(), 2);
    // Nur Store-Mods → kein Häkchen nötig.
    let store = SharedMod {
        source: ModSource::Modrinth,
        project_id: Some("AANobbMI".into()),
        file_id: Some("Yp8wLY1P".into()),
        sha512: Some(h('c', 128)),
        sha256: None,
        ..host_mod("store", b"s", true)
    };
    let only_store = RoomContent { room_id: ROOM.into(), mods: vec![store], pack: None };
    assert_eq!(selection(&only_store, &plan(&[], false)).unwrap().len(), 1);
}

// --- Datei-Kanal über ein nachgebautes Relay -------------------------------------------------

/// Relay + Host in einem: Gast-Handschlag prüfen, dann Dateien aus `files` (SHA-256 → Bytes) liefern.
/// `corrupt`: liefert andere Bytes als angekündigt (Hash muss beim Gast scheitern).
async fn fake_relay(files: HashMap<String, Vec<u8>>, corrupt: bool) -> (RelayGrant, tokio::task::JoinHandle<Vec<String>>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = tokio::spawn(async move {
        let mut log = Vec::new();
        let (mut s, _) = listener.accept().await.unwrap();
        let mut pre = [0u8; 5];
        s.read_exact(&mut pre).await.unwrap();
        assert_eq!(&pre, b"TRSR\x01");
        let mut head = [0u8; 3];
        s.read_exact(&mut head).await.unwrap();
        assert_eq!(head[0], 0x02, "GUEST_HELLO");
        let mut token = vec![0u8; u16::from_be_bytes([head[1], head[2]]) as usize];
        s.read_exact(&mut token).await.unwrap();
        log.push(String::from_utf8(token).unwrap());
        let welcome = br#"{"room":"h0123456789abcdef0123"}"#;
        let mut w = vec![0x81, 0, welcome.len() as u8];
        w.extend_from_slice(welcome);
        s.write_all(&w).await.unwrap();
        let mut magic = [0u8; 6];
        s.read_exact(&mut magic).await.unwrap();
        assert_eq!(magic, relay::MAGIC);
        loop {
            let mut fh = [0u8; 5];
            if s.read_exact(&mut fh).await.is_err() {
                break;
            }
            let len = u32::from_be_bytes([fh[1], fh[2], fh[3], fh[4]]) as usize;
            let mut p = vec![0u8; len];
            s.read_exact(&mut p).await.unwrap();
            if fh[0] == relay::BYE {
                log.push("bye".into());
                break;
            }
            let sha = hex(&p[1..33]);
            log.push(format!("get {}", &sha[..8]));
            let Some(data) = files.get(&sha) else {
                let code = b"not_shared";
                let mut e = vec![relay::ERROR, 0, 0, 0, code.len() as u8];
                e.extend_from_slice(code);
                s.write_all(&e).await.unwrap();
                continue;
            };
            let mut f = vec![relay::FILE, 0, 0, 0, 8];
            f.extend_from_slice(&(data.len() as u64).to_be_bytes());
            let body: Vec<u8> = if corrupt { data.iter().map(|b| b ^ 1).collect() } else { data.clone() };
            let mut out = f;
            for chunk in body.chunks(relay::CHUNK) {
                out.push(relay::DATA);
                out.extend_from_slice(&(chunk.len() as u32).to_be_bytes());
                out.extend_from_slice(chunk);
            }
            out.extend_from_slice(&[relay::END, 0, 0, 0, 32]);
            out.extend_from_slice(&Sha256::digest(&body));
            // Bricht der Gast ab (Größe/Hash), schließt er – dann ist hier Schluss.
            if s.write_all(&out).await.is_err() {
                log.push("aborted".into());
                break;
            }
        }
        log
    });
    (RelayGrant { host: "127.0.0.1".into(), port, token: "trsr1.test.token".into() }, handle)
}

#[tokio::test]
async fn file_channel_checks_size_and_hashes() {
    let dir = tempfile::tempdir().unwrap();
    let data: Vec<u8> = (0..150_000u32).map(|i| (i % 251) as u8).collect();
    let m = host_mod("big", &data, true);
    let sha = m.sha256.clone().unwrap();
    let mut files = HashMap::new();
    files.insert(sha.clone(), data.clone());
    let (grant, server) = fake_relay(files, false).await;
    let mut ch = FileChannel::open(&grant).await.unwrap();
    let out = dir.path().join("big.part");
    let mut seen = 0;
    ch.fetch(relay::KIND_MOD, &sha, data.len() as u64, MAX_HOST_FILE, Some(&m.sha1), &out, |got, _| seen = got).await.unwrap();
    assert_eq!(std::fs::read(&out).unwrap(), data);
    assert_eq!(seen, data.len() as u64);
    // Nicht in der Freigabe.
    let other = dir.path().join("other.part");
    let e = ch.fetch(relay::KIND_MOD, &h('0', 64), 5, MAX_HOST_FILE, None, &other, |_, _| {}).await.unwrap_err();
    assert_eq!(relay::error_code(&e), Some("not_shared"));
    assert!(!other.exists());
    // Angekündigte Größe weicht ab → Abbruch ohne Datei; der Kanal ist danach nicht mehr benutzbar.
    let e = ch.fetch(relay::KIND_MOD, &sha, 10, MAX_HOST_FILE, None, &other, |_, _| {}).await.unwrap_err();
    assert_eq!(relay::error_code(&e), Some("size"));
    assert!(!other.exists());
    let e = ch.fetch(relay::KIND_MOD, &sha, data.len() as u64, MAX_HOST_FILE, None, &other, |_, _| {}).await.unwrap_err();
    assert_eq!(relay::error_code(&e), Some("closed"));
    ch.close().await;
    let log = server.await.unwrap();
    assert_eq!(log[0], "trsr1.test.token");
    assert!(log.contains(&format!("get {}", &sha[..8])));

    // Falscher Inhalt (Host liefert andere Bytes) → Hash-Fehler, Datei weg.
    let mut files = HashMap::new();
    files.insert(sha.clone(), data.clone());
    let (grant, _server) = fake_relay(files, true).await;
    let mut ch = FileChannel::open(&grant).await.unwrap();
    let e = ch.fetch(relay::KIND_MOD, &sha, data.len() as u64, MAX_HOST_FILE, None, &out, |_, _| {}).await.unwrap_err();
    assert_eq!(relay::error_code(&e), Some("hash"));
    assert!(!out.exists());
    // Größer als erlaubt → gar nicht erst anfragen.
    let e = ch.fetch(relay::KIND_MOD, &sha, MAX_HOST_FILE + 1, MAX_HOST_FILE, None, &out, |_, _| {}).await.unwrap_err();
    assert_eq!(relay::error_code(&e), Some("size"));
}

#[tokio::test]
async fn relay_errors_and_bad_grants() {
    // Relay antwortet mit ERROR host_offline.
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        let (mut s, _) = listener.accept().await.unwrap();
        let mut buf = [0u8; 64];
        let _ = s.read(&mut buf).await;
        let code = b"host_offline";
        let mut e = vec![0x8F, 0, code.len() as u8];
        e.extend_from_slice(code);
        let _ = s.write_all(&e).await;
    });
    let grant = RelayGrant { host: "127.0.0.1".into(), port, token: "trsr1.x.y".into() };
    let e = FileChannel::open(&grant).await.err().unwrap();
    assert_eq!(relay::error_code(&e), Some("host_offline"));
    for (host, token) in [("evil host", "trsr1.x"), ("127.0.0.1", "not-a-token"), ("-bad", "trsr1.x")] {
        let g = RelayGrant { host: host.into(), port: 1, token: token.into() };
        assert_eq!(relay::error_code(&FileChannel::open(&g).await.err().unwrap()), Some("bad_grant"));
    }
}

// --- Instanz: Kopie ergänzen, Original bleibt --------------------------------------------------

fn room(version: &str, loader: &str) -> HostingRoom {
    HostingRoom {
        id: ROOM.into(),
        code: None,
        name: "Insel".into(),
        host: UserRef { uuid: h('a', 32), name: "Host".into() },
        mc_version: version.into(),
        loader: loader.into(),
        max_players: 4,
        game_mode: "survival".into(),
        pvp: true,
        cheats: false,
        open: true,
        visibility: None,
        players: 1,
        created_at: None,
        expires_at: None,
        members: Vec::new(),
        my_state: Some("accepted".into()),
        content: None,
    }
}

#[tokio::test]
async fn copy_instance_gets_mods_original_stays() {
    let dir = tempfile::tempdir().unwrap();
    let launcher = Arc::new(Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap());
    let base = launcher
        .instances()
        .create(NewInstance {
            name: "Mein Fabric".into(),
            game_version: "1.21.11".into(),
            loader: Loader { kind: LoaderKind::Fabric, version: None },
        })
        .await
        .unwrap();
    let base_mods = content::content_dir(launcher.paths(), &base.id, ContentKind::Mod);
    std::fs::create_dir_all(&base_mods).unwrap();
    let have = b"schon da".to_vec();
    std::fs::write(base_mods.join("schon-da.jar"), &have).unwrap();

    // Falsche Version → abgelehnt.
    let mut p = plan(&[], true);
    p.mode = PrepareMode::Copy;
    p.base_instance_id = Some(base.id.clone());
    let e = launcher.hosting_instance_for(&room("1.20.1", "fabric"), &p).await.unwrap_err();
    assert!(matches!(&e, Error::Validation(m) if m.code == "hosting.instanceMismatch"), "{e:?}");

    let copy = launcher.hosting_instance_for(&room("1.21.11", "fabric"), &p).await.unwrap();
    assert_ne!(copy.id, base.id);
    let copy_mods = content::content_dir(launcher.paths(), &copy.id, ContentKind::Mod);
    let new_data = b"neue mod vom host".to_vec();
    let known = host_mod("schon-da", &have, true);
    let mut fresh = host_mod("../../neu", &new_data, true);
    fresh.file = "neu (1).jar".into();
    let mut files = HashMap::new();
    files.insert(fresh.sha256.clone().unwrap(), new_data.clone());
    let (grant, _server) = fake_relay(files, false).await;
    let mods = [&known, &fresh];
    let events = std::sync::Mutex::new(Vec::new());
    let on = |p: PrepareProgress| events.lock().unwrap().push(p.step);
    let (installed, already, _) =
        install_into(launcher.http(), None, &copy_mods, &mods, Box::pin(async move { Ok(grant) }), &on).await.unwrap();
    assert_eq!((installed, already), (1, 1));
    assert_eq!(std::fs::read(copy_mods.join("neu__1_.jar")).unwrap(), new_data);
    assert!(events.lock().unwrap().contains(&"host"));
    // Original unverändert, keine Teil-Dateien übrig.
    let base_files: Vec<_> = std::fs::read_dir(&base_mods).unwrap().map(|e| e.unwrap().file_name()).collect();
    assert_eq!(base_files.len(), 1);
    let copy_files: Vec<String> =
        std::fs::read_dir(&copy_mods).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
    assert!(copy_files.iter().all(|f| !f.ends_with(".part")), "{copy_files:?}");
    assert_eq!(launcher.hosting_instance_mods(&copy.id).await.unwrap().len(), 2);
}

#[tokio::test]
async fn failed_host_download_leaves_no_file() {
    let dir = tempfile::tempdir().unwrap();
    let data = b"soll".to_vec();
    let m = host_mod("x", &data, true);
    let mut files = HashMap::new();
    files.insert(m.sha256.clone().unwrap(), data);
    let (grant, _server) = fake_relay(files, true).await;
    let on = |_p: PrepareProgress| {};
    let mods = [&m];
    let e = install_into(&reqwest::Client::new(), None, dir.path(), &mods, Box::pin(async move { Ok(grant) }), &on).await.unwrap_err();
    assert_eq!(relay::error_code(&e), Some("hash"));
    assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 0);
}

// --- Store-Abgleich (Host) ------------------------------------------------------------------

#[test]
fn store_matches_need_exact_hashes() {
    let locals = vec![
        LocalJar { sha1: h('1', 40), sha512: h('a', 128), fingerprint: 11 },
        LocalJar { sha1: h('2', 40), sha512: h('b', 128), fingerprint: 22 },
        LocalJar { sha1: h('3', 40), sha512: h('c', 128), fingerprint: 33 },
    ];
    let version = |project: &str, id: &str, sha1: &str, sha512: &str| -> crate::modrinth::Version {
        serde_json::from_value(json!({
            "id": id, "project_id": project, "date_published": null,
            "files": [{ "url": "https://cdn.modrinth.com/x.jar", "filename": "x.jar", "primary": true, "size": 1,
                "hashes": { "sha1": sha1, "sha512": sha512 } }]
        }))
        .unwrap()
    };
    let mut found = HashMap::new();
    found.insert(h('a', 128), version("AANobbMI", "Yp8wLY1P", &h('1', 40), &h('a', 128)));
    // SHA-512 passt, SHA-1 nicht → kein Treffer.
    found.insert(h('b', 128), version("AANobbMI", "Yp8wLY1Q", &h('9', 40), &h('b', 128)));
    let mr = modrinth_matches(&locals, &found);
    assert_eq!(mr.len(), 1);
    assert_eq!((mr[0].source, mr[0].project_id.as_str(), mr[0].file_id.as_str()), ("modrinth", "AANobbMI", "Yp8wLY1P"));

    let cf: Vec<crate::curseforge::FingerprintMatch> = serde_json::from_value(json!([
        { "id": 238222, "file": { "id": 5846800, "modId": 238222, "fileFingerprint": 33, "hashes": [{ "value": h('3', 40), "algo": 1 }] } },
        { "id": 1, "file": { "id": 2, "modId": 1, "fileFingerprint": 22, "hashes": [{ "value": h('8', 40), "algo": 1 }] } }
    ]))
    .unwrap();
    let rest: Vec<&LocalJar> = locals.iter().skip(1).collect();
    let cm = curseforge_matches(&rest, &cf);
    assert_eq!(cm.len(), 1, "Fingerprint 22 mit falschem SHA-1 zählt nicht");
    assert_eq!((cm[0].project_id.as_str(), cm[0].file_id.as_str(), cm[0].fingerprint), ("238222", "5846800", Some(33)));
}
