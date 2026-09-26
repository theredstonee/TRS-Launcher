//! Ende-zu-Ende-Werkzeug für Welt-Hosting mit Mods (Gast-Seite des Launchers):
//! holt die Mod-Liste einer Welt aus der API, prüft die Warn-Pflicht, lädt
//! Store-Mods aus Modrinth und Dateien „direkt vom Host“ über das Relay und
//! prüft alle Hashes – genau wie „Neue Instanz“ im Dialog, nur in einen Ordner.
//!
//! `cargo run -p trs-core --example hosting_mods -- <api-basis> <token-datei> <raum-id> <ziel-ordner> [ergebnis.json]`
//!
//! Das TRS-Token liest es aus einer Datei (nie von der Befehlszeile).

use std::path::PathBuf;

use sha1::{Digest, Sha1};
use trs_core::hosting_mods::{self, PrepareMode, PreparePlan, PrepareProgress, relay::RelayGrant};

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 5 {
        eprintln!("Aufruf: hosting_mods <api-basis> <token-datei> <raum-id> <ziel-ordner> [ergebnis.json]");
        std::process::exit(2);
    }
    let base = args[1].trim_end_matches('/').to_owned();
    let token = std::fs::read_to_string(&args[2]).expect("Token-Datei").trim().to_owned();
    let room = args[3].clone();
    let out = PathBuf::from(&args[4]);
    let result_file = args.get(5).map(PathBuf::from);
    let http = reqwest::Client::builder().user_agent("TRS-Launcher-E2E/hosting-mods").build().unwrap();

    let content_json: serde_json::Value = http
        .get(format!("{base}/v1/hosting/rooms/{room}/content"))
        .bearer_auth(&token)
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .expect("content")
        .json()
        .await
        .expect("content json");
    let content = hosting_mods::parse_content(&room, content_json).expect("Liste ungültig");
    println!("Liste: {} Mods, Pack: {}", content.mods.len(), content.pack.as_ref().map_or("-".into(), |p| p.name.clone()));
    for m in &content.mods {
        println!("  {} {} · {:?} · {} · {} B", m.name, m.version, m.source, if m.required { "Pflicht" } else { "optional" }, m.size);
    }

    let all: Vec<String> = content.mods.iter().map(|m| m.sha1.clone()).collect();
    let plan = |trust: bool| PreparePlan {
        room_id: room.clone(),
        mode: PrepareMode::New,
        base_instance_id: None,
        name: None,
        mods: all.clone(),
        trust_host: trust,
    };
    let host_files = content.mods.iter().any(|m| m.source == hosting_mods::ModSource::Host);
    let warn = hosting_mods::selection(&content, &plan(false)).err().map(|e| e.to_string());
    println!("Ohne „Ich vertraue diesem Host“: {}", warn.clone().unwrap_or_else(|| "erlaubt (keine Host-Dateien)".into()));

    let connect: serde_json::Value = http
        .post(format!("{base}/v1/hosting/rooms/{room}/connect"))
        .bearer_auth(&token)
        .header("content-type", "application/json")
        .body("{}")
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .expect("connect")
        .json()
        .await
        .expect("connect json");
    let relay = &connect["relay"];
    let grant = RelayGrant {
        host: relay["host"].as_str().unwrap_or_default().to_owned(),
        port: relay["tcpPort"].as_u64().unwrap_or(25503) as u16,
        token: relay["token"].as_str().unwrap_or_default().to_owned(),
    };
    let mods_dir = out.join("mods");
    let progress = |p: PrepareProgress| {
        if p.step == "host" && p.bytes == p.total_bytes {
            println!("  vom Host: {} fertig", p.name.unwrap_or_default());
        }
    };
    let started = std::time::Instant::now();
    let result = hosting_mods::install_with_grant(&http, &mods_dir, &content, &plan(true), grant, &progress).await;
    let (ok, detail) = match &result {
        Ok((installed, already)) => (true, format!("{installed} geladen, {already} schon da")),
        Err(e) => (false, format!("Fehler: {e}")),
    };
    println!("Ergebnis: {detail} ({} ms)", started.elapsed().as_millis());
    let mut verified = Vec::new();
    for m in content.mods.iter().filter(|m| m.source != hosting_mods::ModSource::Manual) {
        let found = std::fs::read_dir(&mods_dir).into_iter().flatten().flatten().find(|e| {
            std::fs::read(e.path()).is_ok_and(|b| hex(&Sha1::digest(&b)) == m.sha1)
        });
        println!("  Hash {}: {}", m.name, if found.is_some() { "ok" } else { "FEHLT" });
        verified.push(serde_json::json!({ "name": m.name, "source": m.source, "ok": found.is_some() }));
    }
    let summary = serde_json::json!({
        "ok": ok && verified.iter().all(|v| v["ok"] == true),
        "detail": detail,
        "warnWithoutTrust": host_files && warn.is_some(),
        "mods": verified,
    });
    if let Some(f) = result_file {
        std::fs::write(f, summary.to_string()).expect("Ergebnis schreiben");
    }
    if summary["ok"] != true {
        std::process::exit(1);
    }
}
