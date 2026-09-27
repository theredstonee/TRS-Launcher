//! Die Wahl „mit/ohne TRS Client“ landet auf allen Wegen in der Instanz:
//! Modrinth-Pack (Datei), CurseForge-Pack (Datei), Import aus einem anderen
//! Launcher – jeweils mit Dialog-Wahl, Vorauswahl und Einstellung „immer …“.
//! Alles offline: Manifest aus dem Cache, TRS-Client-Builds aus einer
//! eigenen `builds.json`, CurseForge auf einer unerreichbaren Adresse.

use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use super::*;
use crate::Launcher;
use crate::instance::{Instance, Loader, LoaderKind};
use crate::settings::Settings;

const MANIFEST: &str = r#"{
    "latest": { "release": "1.21.1", "snapshot": "1.21.1" },
    "versions": [
        { "id": "1.21.1", "type": "release", "url": "https://piston-meta.mojang.com/v1/packages/a/1.21.1.json",
          "time": "2024-08-08T12:24:45+00:00", "releaseTime": "2024-08-08T12:24:45+00:00",
          "sha1": "0000000000000000000000000000000000000000", "complianceLevel": 1 },
        { "id": "1.12.2", "type": "release", "url": "https://piston-meta.mojang.com/v1/packages/b/1.12.2.json",
          "time": "2017-09-18T08:39:46+00:00", "releaseTime": "2017-09-18T08:39:46+00:00",
          "sha1": "0000000000000000000000000000000000000000", "complianceLevel": 0 }
    ]
}"#;

const BUILDS: &str =
    r#"{"version":"0.10.0","builds":[{"loader":"fabric","minecraft":["1.21.1"],"file":"trsclient-fabric-1.21.1.jar"}]}"#;

async fn launcher() -> (tempfile::TempDir, Launcher) {
    let dir = tempfile::tempdir().unwrap();
    let mut launcher = Launcher::init(dir.path().join("data"), Arc::new(|_| {})).await.unwrap();
    let meta = launcher.paths().meta_dir();
    std::fs::create_dir_all(&meta).unwrap();
    std::fs::write(meta.join("version_manifest_v2.json"), MANIFEST).unwrap();
    let builds = dir.path().join("client-mod");
    std::fs::create_dir_all(&builds).unwrap();
    std::fs::write(builds.join("builds.json"), BUILDS).unwrap();
    launcher.set_client_mod_dir(builds);
    // Packs ohne Dateien fragen CurseForge nie – die Adresse ist unerreichbar.
    launcher.curseforge = Some(crate::curseforge::CurseForge::with_endpoint("http://127.0.0.1:9/v1", "test-key").unwrap());
    (dir, launcher)
}

async fn set_policy(launcher: &Launcher, policy: ModpackTrsPolicy) {
    let settings = Settings { modpack_trs_client: policy, ..launcher.settings().await };
    launcher.update_settings(settings).await.unwrap();
}

fn zip(path: &Path, entries: &[(&str, &str)]) -> PathBuf {
    let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
    let opts = zip::write::SimpleFileOptions::default();
    for (name, body) in entries {
        zip.start_file(*name, opts).unwrap();
        zip.write_all(body.as_bytes()).unwrap();
    }
    zip.finish().unwrap();
    path.to_owned()
}

/// `.mrpack` ohne Downloads; `extra_mod` liegt direkt in `overrides/mods/`.
fn mrpack(dir: &Path, name: &str, version: &str, extra_mod: Option<&str>) -> PathBuf {
    let index = format!(
        r#"{{"formatVersion":1,"game":"minecraft","versionId":"1","name":"{name}",
            "dependencies":{{"minecraft":"{version}","fabric-loader":"0.16.10"}},"files":[]}}"#
    );
    let jar = extra_mod.map(|m| format!("overrides/mods/{m}"));
    let mut entries = vec![("modrinth.index.json", index.as_str()), ("overrides/options.txt", "fov:0.5")];
    if let Some(jar) = &jar {
        entries.push((jar.as_str(), "jar"));
    }
    zip(&dir.join(format!("{name}.mrpack")), &entries)
}

/// CurseForge-Zip ohne Pflicht-Dateien; `extra_mod` in `overrides/mods/`.
fn cfpack(dir: &Path, name: &str, extra_mod: Option<&str>, files: &str) -> PathBuf {
    let manifest = format!(
        r#"{{"minecraft":{{"version":"1.21.1","modLoaders":[{{"id":"fabric-0.16.10","primary":true}}]}},
            "manifestType":"minecraftModpack","manifestVersion":1,"name":"{name}","files":{files},"overrides":"overrides"}}"#
    );
    let jar = extra_mod.map(|m| format!("overrides/mods/{m}"));
    let mut entries = vec![("manifest.json", manifest.as_str())];
    if let Some(jar) = &jar {
        entries.push((jar.as_str(), "jar"));
    }
    zip(&dir.join(format!("{name}.zip")), &entries)
}

async fn stored(launcher: &Launcher, instance: &Instance) -> Option<bool> {
    launcher.instances().get(&instance.id).await.unwrap().overrides.trs_client
}

#[tokio::test]
async fn modrinth_pack_file_stores_the_choice() {
    let (dir, launcher) = launcher().await;
    let none = |_| {};

    // Nichts im Weg: Vorauswahl „mit“, ohne Wahl wird genau das gespeichert.
    let plain = mrpack(dir.path(), "Plain", "1.21.1", Some("sodium-fabric-0.6.0.jar"));
    let preview = launcher.preview_pack_file(&plain).await.unwrap();
    assert!(preview.trs_client.asks() && preview.trs_client.recommended);
    assert_eq!(preview.mod_count, 1);
    let i = launcher.import_modpack_file(&plain, None, &none).await.unwrap();
    assert_eq!(stored(&launcher, &i).await, Some(true));

    // Bewusst ohne.
    let i = launcher.import_modpack_file(&plain, Some(false), &none).await.unwrap();
    assert_eq!(stored(&launcher, &i).await, Some(false));

    // Essential im Pack: „ohne“ vorausgewählt – mit Wahl trotzdem „mit“ möglich.
    let clash = mrpack(dir.path(), "Clash", "1.21.1", Some("Essential (fabric_1.21.1).jar"));
    let preview = launcher.preview_pack_file(&clash).await.unwrap();
    assert!(!preview.trs_client.recommended);
    assert_eq!(preview.trs_client.conflicts[0].name, "Essential");
    let i = launcher.import_modpack_file(&clash, None, &none).await.unwrap();
    assert_eq!(stored(&launcher, &i).await, Some(false));
    let i = launcher.import_modpack_file(&clash, Some(true), &none).await.unwrap();
    assert_eq!(stored(&launcher, &i).await, Some(true));

    // Kein Build für die Version: ausgegraut, gespeichert als „ohne“.
    let old = mrpack(dir.path(), "Old", "1.12.2", None);
    let preview = launcher.preview_pack_file(&old).await.unwrap();
    assert!(!preview.trs_client.supported);
    let i = launcher.import_modpack_file(&old, Some(true), &none).await.unwrap();
    assert_eq!(stored(&launcher, &i).await, Some(false));
}

#[tokio::test]
async fn curseforge_pack_file_stores_the_choice() {
    let (dir, launcher) = launcher().await;
    let none = |_| {};

    let plain = cfpack(dir.path(), "CfPlain", None, "[]");
    let i = launcher.import_modpack_file(&plain, None, &none).await.unwrap();
    assert_eq!(stored(&launcher, &i).await, Some(true));
    let i = launcher.import_modpack_file(&plain, Some(false), &none).await.unwrap();
    assert_eq!(stored(&launcher, &i).await, Some(false));

    let clash = cfpack(dir.path(), "CfClash", Some("journeymap-fabric-1.21.1-6.0.0.jar"), "[]");
    let i = launcher.import_modpack_file(&clash, None, &none).await.unwrap();
    assert_eq!(stored(&launcher, &i).await, Some(false), "eigene Minimap → ohne vorausgewählt");

    // Vorschau erkennt Projekt-IDs aus dem Manifest (ohne API).
    let by_id = cfpack(
        dir.path(),
        "CfById",
        None,
        r#"[{"projectID":263420,"fileID":1,"required":true},{"projectID":238222,"fileID":2,"required":true}]"#,
    );
    let preview = launcher.preview_pack_file(&by_id).await.unwrap();
    assert_eq!(preview.mod_count, 2);
    assert!(!preview.trs_client.recommended);
    assert_eq!(preview.trs_client.conflicts[0].name, "Xaero's Minimap");
}

#[tokio::test]
async fn policy_setting_skips_the_question() {
    let (dir, launcher) = launcher().await;
    let none = |_| {};
    let clash = mrpack(dir.path(), "Clash", "1.21.1", Some("Essential (fabric_1.21.1).jar"));

    set_policy(&launcher, ModpackTrsPolicy::Always).await;
    let preview = launcher.preview_pack_file(&clash).await.unwrap();
    assert!(!preview.trs_client.asks());
    let i = launcher.import_modpack_file(&clash, Some(false), &none).await.unwrap();
    assert_eq!(stored(&launcher, &i).await, Some(true), "„immer mit“ gewinnt");

    set_policy(&launcher, ModpackTrsPolicy::Never).await;
    let plain = mrpack(dir.path(), "Plain", "1.21.1", None);
    let i = launcher.import_modpack_file(&plain, Some(true), &none).await.unwrap();
    assert_eq!(stored(&launcher, &i).await, Some(false), "„immer ohne“ gewinnt");

    // Die Einstellung übersteht Speichern und Laden.
    let reloaded = Settings::load(&launcher.paths().settings_file()).await.unwrap();
    assert_eq!(reloaded.modpack_trs_client, ModpackTrsPolicy::Never);
}

/// Import kopiert per `block_in_place` – braucht die Laufzeit mit mehreren Threads.
#[tokio::test(flavor = "multi_thread")]
async fn launcher_import_stores_the_choice() {
    let (dir, launcher) = launcher().await;
    let none = |_| {};
    let game = dir.path().join("Other Launcher");
    std::fs::create_dir_all(game.join("mods")).unwrap();
    std::fs::write(game.join("mods").join("Xaeros_Minimap_24.6.1_Fabric_1.21.jar"), b"jar").unwrap();
    std::fs::write(game.join("options.txt"), b"fov:0.5").unwrap();

    let scan = || {
        let mut c = crate::import::scan_folder(&game, Some("1.21.1")).into_iter().next().expect("Kandidat");
        c.loader = Loader { kind: LoaderKind::Fabric, version: None };
        c
    };
    // Übersicht: Angebot je Kandidat, Minimap erkannt → „ohne“ vorausgewählt.
    let mut listed = vec![scan()];
    launcher.offer_trs_client(&mut listed).await;
    let offer = listed[0].trs_client.clone().expect("Angebot");
    assert!(offer.asks() && !offer.recommended);
    assert_eq!(offer.conflicts[0].name, "Xaero's Minimap");

    let r = launcher.import_candidate(scan(), None, &none).await.unwrap();
    assert_eq!(stored(&launcher, &r.instance).await, Some(false));
    let r = launcher.import_candidate(scan(), Some(true), &none).await.unwrap();
    assert_eq!(stored(&launcher, &r.instance).await, Some(true));

    // Vanilla-Import bleibt wie bisher (keine Frage, nichts gespeichert).
    let mut vanilla = scan();
    vanilla.loader = Loader::vanilla();
    let r = launcher.import_candidate(vanilla, Some(false), &none).await.unwrap();
    assert_eq!(stored(&launcher, &r.instance).await, None);
}

#[tokio::test]
async fn new_instances_without_pack_stay_as_before() {
    let (_dir, launcher) = launcher().await;
    set_policy(&launcher, ModpackTrsPolicy::Never).await;
    let i = launcher
        .create_instance(crate::instance::NewInstance {
            name: "Neu".into(),
            game_version: "1.21.1".into(),
            loader: Loader { kind: LoaderKind::Fabric, version: None },
        })
        .await
        .unwrap();
    assert_eq!(stored(&launcher, &i).await, None, "„Neue Instanz“ fragt nicht und speichert nichts");
}
