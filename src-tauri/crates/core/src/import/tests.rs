use std::path::Path;

use super::copy::{Limits, TooLarge, collect};
use super::*;

fn write(path: &Path, bytes: impl AsRef<[u8]>) {
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, bytes).unwrap();
}

fn fabric_jar(path: &Path) {
    use std::io::Write;
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
    zip.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
    zip.write_all(b"{}").unwrap();
    zip.finish().unwrap();
}

fn rels(entries: &[super::copy::Entry]) -> Vec<String> {
    let mut out: Vec<String> = entries.iter().map(|e| e.rel.to_string_lossy().replace('\\', "/")).collect();
    out.sort();
    out
}

#[test]
fn version_ids() {
    let (g, l) = parse_version_id("fabric-loader-0.16.10-1.21.1").unwrap();
    assert_eq!((g.as_str(), l.kind, l.version.as_deref()), ("1.21.1", LoaderKind::Fabric, Some("0.16.10")));
    let (g, l) = parse_version_id("1.20.1-forge-47.3.0").unwrap();
    assert_eq!((g.as_str(), l.kind, l.version.as_deref()), ("1.20.1", LoaderKind::Forge, Some("47.3.0")));
    let (g, l) = parse_version_id("neoforge-21.1.77").unwrap();
    assert_eq!((g.as_str(), l.kind), ("1.21.1", LoaderKind::NeoForge));
    assert_eq!(parse_version_id("neoforge-21.0.10").unwrap().0, "1.21");
    let (g, l) = parse_version_id("1.21.11").unwrap();
    assert_eq!((g.as_str(), l.kind), ("1.21.11", LoaderKind::Vanilla));
}

#[test]
fn curse_loaders() {
    let l = parse_curse_loader("forge-47.3.33", "1.20.1");
    assert_eq!((l.kind, l.version.as_deref()), (LoaderKind::Forge, Some("47.3.33")));
    let l = parse_curse_loader("fabric-0.16.10-1.21.1", "1.21.1");
    assert_eq!((l.kind, l.version.as_deref()), (LoaderKind::Fabric, Some("0.16.10")));
    assert_eq!(parse_curse_loader("unbekannt", "1.20.1").kind, LoaderKind::Vanilla);
}

#[test]
fn loader_names() {
    let (l, mapped) = loader_from_name("Forge", Some("1.20.1-47.3.0"), "1.20.1").unwrap();
    assert_eq!((l.kind, l.version.as_deref(), mapped), (LoaderKind::Forge, Some("47.3.0"), false));
    let (l, mapped) = loader_from_name("LegacyFabric", Some("0.15.0"), "1.8.9").unwrap();
    assert_eq!((l.kind, l.version, mapped), (LoaderKind::Fabric, None, true));
    assert_eq!(loader_from_name("Neoforge", Some("21.1.77"), "1.21.1").unwrap().0.kind, LoaderKind::NeoForge);
    assert!(loader_from_name("Paper", None, "1.21.1").is_none(), "Server-Instanzen nicht");
    assert_eq!(loader_from_name("Fabric", Some("../x"), "1.21").unwrap().0.version, None);
}

#[test]
fn folder_scan_detects_formats() {
    let dir = tempfile::tempdir().unwrap();
    // Nackter Spielordner mit Fabric-Mod
    let game = dir.path().join("Lunar-Profil");
    fabric_jar(&game.join("mods/a.jar"));
    let found = scan_folder(&game, Some("1.21.1"));
    assert_eq!(found.len(), 1);
    assert!(found[0].version_guessed);
    assert_eq!((found[0].source, found[0].loader.kind), (ImportSource::Folder, LoaderKind::Fabric));

    // Leerer Ordner: nichts
    let empty = dir.path().join("leer");
    std::fs::create_dir_all(&empty).unwrap();
    assert!(scan_folder(&empty, Some("1.21.1")).is_empty());

    // Einzelne CurseForge-Instanz direkt gewählt
    let cf = dir.path().join("cf");
    write(&cf.join("minecraftinstance.json"), r#"{"name":"CF","gameVersion":"1.20.1","baseModLoader":{"name":"forge-47.3.0"}}"#);
    let found = scan_folder(&cf, None);
    assert_eq!((found.len(), found[0].version_guessed), (1, false));

    // Datenordner eines Launchers mit `instances` darin (ATLauncher)
    let atl = dir.path().join("ATLauncher");
    write(&atl.join("instances/Pack/instance.json"), r#"{"id":"1.20.1","launcher":{"name":"Pack"}}"#);
    let found = scan_folder(&atl, None);
    assert_eq!(found.iter().map(|c| c.source).collect::<Vec<_>>(), [ImportSource::AtLauncher]);
}

#[test]
fn reads_modrinth_app_database() {
    let dir = tempfile::tempdir().unwrap();
    let app = dir.path().join("ModrinthApp");
    let custom = dir.path().join("Modrinth");
    std::fs::create_dir_all(&app).unwrap();
    std::fs::create_dir_all(custom.join("profiles/Freizeitpark/mods")).unwrap();
    std::fs::create_dir_all(custom.join("profiles/Neo")).unwrap();
    let db = rusqlite::Connection::open(app.join("app.db")).unwrap();
    db.execute_batch(&format!(
        "CREATE TABLE settings (id INTEGER, custom_dir TEXT);
         INSERT INTO settings VALUES (0, '{}');
         CREATE TABLE instances (id TEXT, path TEXT, applied_content_set_id TEXT, name TEXT);
         CREATE TABLE instance_content_sets (id TEXT, game_version TEXT, loader TEXT, loader_version TEXT);
         INSERT INTO instances VALUES ('a', 'Freizeitpark', 'ca', 'Freizeitpark');
         INSERT INTO instances VALUES ('b', 'Neo', 'cb', 'Create Live');
         INSERT INTO instances VALUES ('c', '..', 'ca', 'Böse');
         INSERT INTO instance_content_sets VALUES ('ca', '1.21.1', 'fabric', '0.16.13');
         INSERT INTO instance_content_sets VALUES ('cb', '1.21.1', 'neoforge', '21.1.180');",
        custom.display().to_string().replace('\'', "''")
    ))
    .unwrap();
    drop(db);

    let found = scan_modrinth(&app);
    assert_eq!(found.len(), 2, "{found:#?}");
    let fp = found.iter().find(|c| c.name == "Freizeitpark").unwrap();
    assert_eq!((fp.loader.kind, fp.loader.version.as_deref()), (LoaderKind::Fabric, Some("0.16.13")));
    assert!(found.iter().any(|c| c.loader.kind == LoaderKind::NeoForge));
}

#[test]
fn exclusions_and_secrets() {
    for name in ["versions", "Libraries", "launcher_profiles.json", "BLClient-Menu-Styles", "badlion_settings.json", "webcache2", ".oneclient-mods.json"] {
        assert!(is_excluded(name), "{name}");
    }
    for name in ["saves", "mods", "config", "options.txt", "servers.dat", "resourcepacks", "kubejs"] {
        assert!(!is_excluded(name), "{name}");
    }
    for name in [
        "accounts.json",
        "microsoft_accounts.json",
        "launcher_accounts_microsoft_store.json",
        "launcher_msa_credentials_microsoft_store.bin",
        "TlauncherProfiles.json",
        "login_cache.dat",
        "auth.json",
        "session_token.json",
    ] {
        assert!(is_secret_file(name), "{name}");
    }
    for name in ["options.txt", "servers.dat", "sodium-options.json", "tokenizer.jar", "level.dat"] {
        assert!(!is_secret_file(name), "{name}");
    }
}

#[test]
fn custom_prism_instance_dir() {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("PrismLauncher");
    std::fs::create_dir_all(&data).unwrap();
    assert_eq!(mmc_instance_dir(data.clone(), "prismlauncher.cfg"), data.join("instances"));
    std::fs::write(data.join("prismlauncher.cfg"), "[General]\nInstanceDir=meine\n").unwrap();
    assert_eq!(mmc_instance_dir(data.clone(), "prismlauncher.cfg"), data.join("meine"));
    let abs = dir.path().join("woanders");
    std::fs::write(data.join("prismlauncher.cfg"), format!("InstanceDir={}\n", abs.display())).unwrap();
    assert_eq!(mmc_instance_dir(data.clone(), "prismlauncher.cfg"), abs);
    std::fs::write(data.join("prismlauncher.cfg"), "InstanceDir=../../etc\n").unwrap();
    assert_eq!(mmc_instance_dir(data.clone(), "prismlauncher.cfg"), data.join("instances"));
}

#[test]
fn scans_all_formats_and_copies() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path();

    // Offizieller Launcher
    let mc = root.join(".minecraft");
    write(&mc.join("saves/Welt/level.dat"), b"x");
    std::fs::create_dir_all(mc.join("versions/1.21.1")).unwrap();
    write(&mc.join("options.txt"), b"fov:90");
    write(&mc.join("servers.dat"), b"srv");
    write(&mc.join("launcher_accounts.json"), b"geheim");
    write(&mc.join("essential/microsoft_accounts.json"), b"geheim");
    write(&mc.join("essential/config.json"), b"{}");
    write(
        &mc.join("launcher_profiles.json"),
        r#"{"profiles":{
            "a":{"type":"custom","name":"Mein Profil","lastVersionId":"1.21.1"},
            "b":{"type":"latest-release","lastVersionId":"latest-release"},
            "c":{"type":"latest-snapshot","lastVersionId":"latest-snapshot"}}}"#,
    );

    // Prism
    let prism = root.join("prism");
    let inst = prism.join("Fabric Pack");
    write(&inst.join(".minecraft/mods/sodium.jar"), b"jar");
    write(&inst.join("instance.cfg"), "[General]\nname=Fabric Pack\n");
    write(
        &inst.join("mmc-pack.json"),
        r#"{"components":[{"uid":"net.minecraft","version":"1.21.1"},{"uid":"net.fabricmc.fabric-loader","version":"0.16.10"}]}"#,
    );

    // CurseForge
    let curse = root.join("curse");
    let cf = curse.join("ATM9");
    write(&cf.join("mods/a.jar"), b"jar");
    write(
        &cf.join("minecraftinstance.json"),
        r#"{"name":"ATM9","gameVersion":"1.20.1","baseModLoader":{"name":"forge-47.3.33"},"installPath":"C:\\woanders"}"#,
    );

    let roots = ImportRoots { minecraft: vec![mc.clone()], prism: vec![prism], curseforge: vec![curse], ..Default::default() };
    let found = roots.scan(Some("1.21.4"));
    assert_eq!(found.len(), 4, "{found:#?}");

    let vanilla = found.iter().find(|c| c.name == "Mein Profil").unwrap();
    assert_eq!((vanilla.world_count, vanilla.has_options, vanilla.has_servers), (1, true, true));
    assert!(found.iter().any(|c| c.source == ImportSource::Vanilla && c.game_version == "1.21.4"));
    let fabric = found.iter().find(|c| c.source == ImportSource::Prism).unwrap();
    assert_eq!((fabric.loader.kind, fabric.mod_count), (LoaderKind::Fabric, 1));
    let atm = found.iter().find(|c| c.source == ImportSource::CurseForge).unwrap();
    assert_eq!(atm.game_dir, cf);

    // IDs bleiben über Scans hinweg gleich.
    assert_eq!(roots.scan(Some("1.21.4")).iter().find(|c| c.name == "Mein Profil").unwrap().id, vanilla.id);
    assert_eq!(roots.present(), [ImportSource::Vanilla, ImportSource::Prism, ImportSource::CurseForge]);

    let target = root.join("ziel");
    copy::copy_candidate(&mc, &target, &CopyExtras::default(), false, &|_| {}).unwrap();
    assert!(target.join("saves/Welt/level.dat").is_file());
    assert!(target.join("options.txt").is_file());
    assert!(target.join("essential/config.json").is_file());
    assert!(!target.join("launcher_accounts.json").exists(), "Zugangsdaten dürfen nicht mitkopiert werden");
    assert!(!target.join("essential/microsoft_accounts.json").exists(), "auch nicht in Unterordnern");
    assert!(!target.join("versions").exists());
}

#[test]
fn curseforge_addons_keep_their_origin() {
    let dir = tempfile::tempdir().unwrap();
    let cf = dir.path().join("BMC");
    write(&cf.join("mods/jei.jar"), b"jar");
    write(&cf.join("mods/off.jar.disabled"), b"jar");
    write(&cf.join("resourcepacks/Faithful.zip"), b"zip");
    // Felder wie in einer echten minecraftinstance.json der CurseForge-App.
    write(
        &cf.join("minecraftinstance.json"),
        r#"{"name":"BMC","gameVersion":"1.21.1","baseModLoader":{"name":"fabric-0.16.9-1.21.1"},
            "installedAddons":[
              {"addonID":238222,"categoryClassID":6,"installedFile":{"id":5101366,"fileName":"jei.jar","fileNameOnDisk":"jei.jar"}},
              {"addonID":1,"categoryClassID":6,"installedFile":{"id":2,"fileNameOnDisk":"off.jar"}},
              {"addonID":3,"categoryClassID":12,"installedFile":{"id":4,"fileNameOnDisk":"Faithful.zip"}},
              {"addonID":5,"categoryClassID":6552,"installedFile":{"id":6,"fileNameOnDisk":"Shader.zip"}},
              {"addonID":7,"categoryClassID":6,"installedFile":{"id":8,"fileNameOnDisk":"../../böse.jar"}},
              {"addonID":9,"categoryClassID":4471,"installedFile":{"id":10,"fileNameOnDisk":"pack.zip"}}
            ]}"#,
    );
    let c = curse_instance(&cf).unwrap();
    assert_eq!((c.loader.kind, c.loader.version.as_deref()), (LoaderKind::Fabric, Some("0.16.9")));
    assert_eq!((c.tracked_count, c.missing_count, c.mod_count, c.resource_pack_count), (3, 1, 2, 1));
    assert!(c.notes.contains(&ImportNote::CurseForgeDownloads));
    let jei = c.extras.tracked.iter().find(|t| t.file_name == "jei.jar").unwrap();
    assert_eq!((jei.source.project_key(), jei.source.version_id.as_str()), ("cf:238222".into(), "5101366"));
    assert_eq!(c.extras.missing[0].kind, ContentKind::ShaderPack);
}

/// Lunar-Datenbank mit dem Schema einer echten `profiles.db` (Lunar-Launcher 3.7).
fn lunar_fixture(home: &Path, minecraft: &Path) {
    std::fs::create_dir_all(home.join("db")).unwrap();
    write(&home.join("settings/game/accounts.json"), b"geheim");
    write(
        &home.join("settings/launcher.json"),
        serde_json::json!({ "settings": { "gameDirectory": minecraft.display().to_string(), "allocatedMemory": 4096 } }).to_string(),
    );
    fabric_jar(&home.join("profiles/lunar-1.21/mods/eigener.jar"));
    let db = rusqlite::Connection::open(home.join("db/profiles.db")).unwrap();
    db.execute_batch("PRAGMA journal_mode=WAL;").unwrap();
    db.execute_batch(
        "CREATE TABLE profiles (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT, icon_path TEXT, featured_image_path TEXT,
            path TEXT NOT NULL UNIQUE, type TEXT NOT NULL, major_game_version TEXT NOT NULL, game_version TEXT NOT NULL,
            loaders TEXT NOT NULL DEFAULT '[]', use_lunar_features INTEGER NOT NULL DEFAULT 1, is_badlion INTEGER NOT NULL DEFAULT 0,
            provider TEXT, modrinth TEXT, curseforge TEXT, user_modpack TEXT, allocated_memory INTEGER, game_resolution TEXT,
            loader_version TEXT, game_directory TEXT, mods_directory TEXT, jvm_arguments TEXT, pre_launch_command TEXT,
            wrapper_command TEXT, post_exit_command TEXT, environment_variables TEXT, lunar_module TEXT,
            created_at INTEGER NOT NULL, config_version INTEGER NOT NULL DEFAULT 3, update_channel TEXT);",
    )
    .unwrap();
    db.execute(
        "INSERT INTO profiles (id, name, path, type, major_game_version, game_version, loaders, is_badlion, game_directory, mods_directory, lunar_module, created_at)
         VALUES ('b', 'Minecraft 1.21', 'badlion-1.21', 'badlion', '1.21', '1.21.10', '[]', 1, ?1, ?2, 'badlion', 1)",
        rusqlite::params![minecraft.display().to_string(), home.join("profiles/badlion-1.21/mods").display().to_string()],
    )
    .unwrap();
    db.execute(
        "INSERT INTO profiles (id, name, path, type, major_game_version, game_version, loaders, created_at)
         VALUES ('l', 'Lunar 1.21.1', 'lunar-1.21', 'lunar', '1.21', '1.21.1', '[]', 2)",
        [],
    )
    .unwrap();
    db.execute(
        "INSERT INTO profiles (id, name, path, type, major_game_version, game_version, loaders, loader_version, created_at)
         VALUES ('f', 'Fabric-Profil', '../../weg', 'vanilla', '1.20', '1.20.1', '[\"fabric\"]', '0.16.9', 3)",
        [],
    )
    .unwrap();
    // Verbindung offen lassen wäre wie ein laufender Launcher – hier schließen, WAL bleibt nicht zwingend.
    drop(db);
}

#[test]
fn reads_lunar_profiles_without_touching_them() {
    let dir = tempfile::tempdir().unwrap();
    let home = dir.path().join(".lunarclient");
    let mc = dir.path().join(".minecraft");
    write(&mc.join("options.txt"), b"x");
    write(&mc.join("mods/autotip/x.cfg"), b"badlion");
    write(&mc.join("saves/Welt/level.dat"), b"x");
    lunar_fixture(&home, &mc);
    let before = std::fs::read(home.join("db/profiles.db")).unwrap();

    let found = clients::scan_lunar(&home, None);
    assert_eq!(found.len(), 3, "{found:#?}");
    let badlion = found.iter().find(|c| c.name == "Minecraft 1.21").unwrap();
    assert_eq!((badlion.source, badlion.game_version.as_str(), badlion.loader.kind), (ImportSource::Badlion, "1.21.10", LoaderKind::Vanilla));
    assert!(badlion.notes.contains(&ImportNote::ClientModsSkipped) && badlion.notes.contains(&ImportNote::SharedGameDir));
    assert_eq!(badlion.mod_count, 0, "Badlions eigene Mods im .minecraft zählen nicht");
    let lunar = found.iter().find(|c| c.name == "Lunar 1.21.1").unwrap();
    assert_eq!((lunar.source, lunar.loader.kind, lunar.mod_count, lunar.world_count), (ImportSource::Lunar, LoaderKind::Fabric, 1, 1));
    // Unsicherer Profilpfad: kein eigener Ordner, aber der Spielordner aus den Einstellungen.
    let fabric = found.iter().find(|c| c.name == "Fabric-Profil").unwrap();
    assert_eq!((fabric.loader.kind, fabric.loader.version.as_deref(), fabric.mod_count), (LoaderKind::Fabric, Some("0.16.9"), 0));

    // Kopieren: nur eigene Mods aus dem Profil, nichts aus .minecraft/mods, keine Zugangsdaten.
    let entries = collect(&lunar.game_dir, &lunar.extras, true, Limits::default()).unwrap();
    assert_eq!(rels(&entries), ["mods/eigener.jar", "options.txt", "saves/Welt/level.dat"]);
    assert_eq!(std::fs::read(home.join("db/profiles.db")).unwrap(), before, "fremde Datenbank bleibt unverändert");
}

#[test]
fn lunar_loader_column() {
    assert_eq!(clients::lunar_loader(r#"["fabric"]"#, Some("0.16.9".into()), None).kind, LoaderKind::Fabric);
    assert_eq!(clients::lunar_loader(r#"[{"type":"NeoForge"}]"#, None, None).kind, LoaderKind::NeoForge);
    assert_eq!(clients::lunar_loader("kaputt", None, None).kind, LoaderKind::Vanilla);
}

#[test]
fn badlion_uses_the_last_started_version() {
    let dir = tempfile::tempdir().unwrap();
    let badlion = dir.path().join("Badlion Client");
    let mc = dir.path().join(".minecraft");
    write(&badlion.join("Data/1.21.10.jar"), b"jar");
    write(&badlion.join("Data/jdk-21.0.2.json"), b"{}");
    write(&badlion.join("accounts.dat"), b"geheim");
    write(&mc.join("options.txt"), b"x");
    assert!(clients::scan_badlion(&badlion, &mc, Some("1.21.11")).is_empty(), "ohne badlion_settings.json nie gestartet");
    write(&mc.join("badlion_settings.json"), b"{}");
    write(&mc.join("BLClient-Mod-Profiles/Vanilla.zip"), b"zip");
    let found = clients::scan_badlion(&badlion, &mc, Some("1.21.11"));
    assert_eq!(found.len(), 1);
    assert_eq!((found[0].game_version.as_str(), found[0].version_guessed), ("1.21.10", true));
    let entries = collect(&mc, &found[0].extras, false, Limits::default()).unwrap();
    assert_eq!(rels(&entries), ["options.txt"], "Badlion-Dateien bleiben zurück");
}

#[test]
fn version_json_reveals_the_loader() {
    let dir = tempfile::tempdir().unwrap();
    let mc = dir.path().join(".minecraft");
    write(&mc.join("options.txt"), b"x");
    write(
        &mc.join("versions/Fabric 1.20.1/Fabric 1.20.1.json"),
        r#"{"id":"Fabric 1.20.1","inheritsFrom":"1.20.1","libraries":[{"name":"org.ow2.asm:asm:9.6"},{"name":"net.fabricmc:fabric-loader:0.15.7"}]}"#,
    );
    write(
        &mc.join("versions/1.20.1-forge-47.2.0/1.20.1-forge-47.2.0.json"),
        r#"{"inheritsFrom":"1.20.1","libraries":[{"name":"net.minecraftforge:fmlloader:1.20.1-47.2.0"}]}"#,
    );
    write(&mc.join("versions/1.21.4/1.21.4.json"), r#"{"id":"1.21.4"}"#);
    let (g, l) = clients::inspect_version_json(&mc, "Fabric 1.20.1").unwrap();
    assert_eq!((g.as_str(), l.kind, l.version.as_deref()), ("1.20.1", LoaderKind::Fabric, Some("0.15.7")));
    assert!(clients::inspect_version_json(&mc, "../x").is_none());

    let found = clients::scan_tlauncher(&mc, None);
    let names: Vec<_> = found.iter().map(|c| (c.name.as_str(), c.loader.kind)).collect();
    assert_eq!(names, [("1.20.1-forge-47.2.0", LoaderKind::Forge), ("Fabric 1.20.1", LoaderKind::Fabric), ("TLauncher", LoaderKind::Vanilla)]);
    assert_eq!(found[2].game_version, "1.21.4");

    // Auch Profile des offiziellen Launchers mit eigenem Versionsnamen.
    write(&mc.join("launcher_profiles.json"), r#"{"profiles":{"x":{"type":"custom","name":"F","lastVersionId":"Fabric 1.20.1"}}}"#);
    let vanilla = scan_vanilla(&mc, None);
    assert_eq!((vanilla[0].game_version.as_str(), vanilla[0].loader.kind), ("1.20.1", LoaderKind::Fabric));
}

#[test]
fn reads_oneclient_clusters() {
    let dir = tempfile::tempdir().unwrap();
    let app = dir.path().join("OneClient");
    let data = dir.path().join("verlegt");
    write(&app.join("settings.json"), serde_json::json!({ "data_dir": data.display().to_string(), "curseforge_api_key": "geheim" }).to_string());
    write(&app.join("auth.json"), b"geheim");
    assert_eq!(launchers::oneclient_data_dir(&app), app, "Ziel fehlt noch → Standardordner");
    std::fs::create_dir_all(&data).unwrap();
    assert_eq!(launchers::oneclient_data_dir(&app), data);
    // Schema aus oneclient_db/migrations (vereinfacht auf die gelesenen Spalten).
    std::fs::create_dir_all(&data).unwrap();
    let db = rusqlite::Connection::open(data.join("user_data.db")).unwrap();
    db.execute_batch(
        "CREATE TABLE clusters (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, folder_name TEXT NOT NULL,
            setting_profile_name TEXT, mc_version TEXT NOT NULL DEFAULT 'unknown', mc_loader INTEGER NOT NULL DEFAULT 0,
            stage INTEGER NOT NULL DEFAULT 0, mc_loader_version TEXT, created_at TEXT, last_played TEXT, overall_played INTEGER,
            linked_modpack_hash TEXT);
         INSERT INTO clusters (name, folder_name, mc_version, mc_loader, mc_loader_version) VALUES ('PvP 1.8.9', 'pvp', '1.8.9', 5, '0.16.0');
         INSERT INTO clusters (name, folder_name, mc_version, mc_loader, mc_loader_version) VALUES ('Eigen', 'eigen', '1.21.1', 4, '0.16.10');
         INSERT INTO clusters (name, folder_name, mc_version, mc_loader) VALUES ('Weg', '..', '1.21.1', 4);",
    )
    .unwrap();
    drop(db);
    fabric_jar(&data.join("clusters/pvp/mods/patcher.jar"));
    write(&data.join(".minecraft/options.txt"), b"x");
    write(&data.join(".minecraft/mods/pvp/patcher.jar"), b"link");
    write(&data.join("clusters/eigen/.dedicated_directory"), b"");
    write(&data.join("clusters/eigen/options.txt"), b"x");
    fabric_jar(&data.join("clusters/eigen/mods/sodium.jar"));

    let found = launchers::scan_oneclient(&launchers::oneclient_data_dir(&app));
    assert_eq!(found.len(), 2, "{found:#?}");
    let pvp = found.iter().find(|c| c.name == "PvP 1.8.9").unwrap();
    assert_eq!((pvp.loader.kind, pvp.loader.version.as_deref(), pvp.mod_count), (LoaderKind::Fabric, None, 1));
    assert!(pvp.notes.contains(&ImportNote::LoaderMapped) && pvp.notes.contains(&ImportNote::SharedGameDir));
    let entries = collect(&pvp.game_dir, &pvp.extras, true, Limits::default()).unwrap();
    assert_eq!(rels(&entries), ["mods/patcher.jar", "options.txt"]);
    let own = found.iter().find(|c| c.name == "Eigen").unwrap();
    assert_eq!((own.game_dir.clone(), own.mod_count), (data.join("clusters/eigen"), 1));
}

#[test]
fn reads_atlauncher_instances() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("ATLauncher");
    let inst = root.join("instances/Fabulously");
    write(&root.join("configs/accounts.json"), b"geheim");
    write(&inst.join("mods/sodium.jar"), b"jar");
    write(&inst.join("disabledmods/iris.jar"), b"jar");
    write(&inst.join("resourcepacks/pack.zip"), b"zip");
    write(&inst.join("options.txt"), b"x");
    write(&inst.join("instance.png"), b"png");
    // Felder wie in com.atlauncher.data.Instance / InstanceLauncher / DisableableMod.
    write(
        &inst.join("instance.json"),
        r#"{"id":"1.21.1","inheritsFrom":null,"launcher":{"name":"Fabulously Optimized",
            "loaderVersion":{"version":"0.16.10","rawVersion":"0.16.10","recommended":false,"type":"Fabric"},
            "mods":[
              {"name":"Sodium","file":"sodium.jar","type":"mods","disabled":false,"modrinthProject":{"id":"AANobbMI"},"modrinthVersion":{"id":"abc"}},
              {"name":"Iris","file":"iris.jar","type":"mods","disabled":true,"curseForgeProjectId":455508,"curseForgeFileId":123},
              {"name":"Pack","file":"pack.zip","type":"resourcepack","curseForgeProjectId":1,"curseForgeFileId":2},
              {"name":"Weg","file":"weg.jar","type":"mods","curseForgeProjectId":3,"curseForgeFileId":4}
            ]}}"#,
    );
    write(&root.join("instances/Server/instance.json"), r#"{"id":"1.21.1","launcher":{"name":"S","loaderVersion":{"type":"Paper","version":"1"}}}"#);
    let found = launchers::scan_atlauncher(&root);
    assert_eq!(found.len(), 1, "Server (Paper) werden nicht angeboten");
    let c = &found[0];
    assert_eq!((c.name.as_str(), c.loader.kind, c.loader.version.as_deref()), ("Fabulously Optimized", LoaderKind::Fabric, Some("0.16.10")));
    assert_eq!((c.mod_count, c.tracked_count), (2, 3));
    let entries = collect(&c.game_dir, &c.extras, true, Limits::default()).unwrap();
    assert_eq!(rels(&entries), ["mods/iris.jar.disabled", "mods/sodium.jar", "options.txt", "resourcepacks/pack.zip"]);
    let sodium = c.extras.tracked.iter().find(|t| t.file_name == "sodium.jar").unwrap();
    assert_eq!(sodium.source.project_key(), "AANobbMI");
}

#[test]
fn reads_gdlauncher_legacy_and_carbon() {
    let dir = tempfile::tempdir().unwrap();
    // GDLauncher (alt), neues und altes config.json-Format.
    let next = dir.path().join("gdlauncher_next");
    let moved = dir.path().join("GDL-Daten");
    write(&next.join("override.data"), moved.display().to_string());
    std::fs::create_dir_all(&moved).unwrap();
    assert_eq!(launchers::gdl_base(&next), moved);
    let a = moved.join("instances/RLCraft");
    write(&a.join("mods/rl.jar"), b"jar");
    write(
        &a.join("config.json"),
        r#"{"loader":{"loaderType":"forge","mcVersion":"1.12.2","loaderVersion":"1.12.2-14.23.5.2860","fileID":1,"projectID":2,"source":"curseforge"},
            "mods":[{"projectID":285109,"fileID":3575916,"fileName":"rl.jar","displayName":"RL"}]}"#,
    );
    let b = moved.join("instances/Alt");
    write(&b.join("options.txt"), b"x");
    write(&b.join("config.json"), r#"{"modloader":["fabric","1.16.5","0.11.3"]}"#);
    let found = launchers::scan_gdlauncher(&launchers::gdl_base(&next));
    assert_eq!(found.len(), 2);
    let rl = found.iter().find(|c| c.name == "RLCraft").unwrap();
    assert_eq!((rl.loader.kind, rl.loader.version.as_deref(), rl.tracked_count), (LoaderKind::Forge, Some("14.23.5.2860"), 1));
    assert!(collect(&rl.game_dir, &rl.extras, true, Limits::default()).unwrap().iter().all(|e| e.rel != Path::new("config.json")));
    assert_eq!(found.iter().find(|c| c.name == "Alt").unwrap().loader.kind, LoaderKind::Fabric);

    // Carbon: Laufzeitordner `data`, Spielordner `instance`.
    let carbon = dir.path().join("gdlauncher_carbon");
    let runtime = launchers::carbon_runtime(&carbon);
    assert_eq!(runtime, carbon.join("data"));
    let c = runtime.join("instances/Mein Pack");
    write(&c.join("instance/options.txt"), b"x");
    write(
        &c.join("instance.json"),
        r#"{"name":"Mein Pack","game_configuration":{"version":{"release":"1.21.1","modloaders":[{"type":"Neoforge","version":"21.1.77"}]},"global_java_args":true}}"#,
    );
    let found = launchers::scan_carbon(&runtime);
    assert_eq!(found.len(), 1);
    assert_eq!((found[0].loader.kind, found[0].game_dir.clone()), (LoaderKind::NeoForge, c.join("instance")));
    // Ordner direkt gewählt: Carbon- und ATLauncher-instance.json werden unterschieden.
    assert_eq!(scan_folder(&c, None)[0].source, ImportSource::GdLauncherCarbon);
}

#[test]
fn copy_limits_and_links() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("quelle");
    write(&src.join("a.txt"), b"12345");
    write(&src.join("b.txt"), b"12345");
    let outside = dir.path().join("draussen");
    write(&outside.join("geheim.txt"), b"x");

    assert_eq!(collect(&src, &CopyExtras::default(), true, Limits { files: 1, bytes: u64::MAX }).unwrap_err(), TooLarge);
    assert_eq!(collect(&src, &CopyExtras::default(), true, Limits { files: 10, bytes: 6 }).unwrap_err(), TooLarge);

    // Links: nach draußen nie, ins Erlaubte ja (nur wenn das System Symlinks erlaubt).
    #[cfg(unix)]
    let linked = std::os::unix::fs::symlink(&outside, src.join("raus")).is_ok()
        && std::os::unix::fs::symlink(src.join("a.txt"), src.join("innen.txt")).is_ok();
    #[cfg(windows)]
    let linked = std::os::windows::fs::symlink_dir(&outside, src.join("raus")).is_ok()
        && std::os::windows::fs::symlink_file(src.join("a.txt"), src.join("innen.txt")).is_ok();
    let entries = collect(&src, &CopyExtras::default(), true, Limits::default()).unwrap();
    if linked {
        assert_eq!(rels(&entries), ["a.txt", "b.txt", "innen.txt"]);
        let extras = CopyExtras { roots: vec![outside.clone()], ..Default::default() };
        assert!(rels(&collect(&src, &extras, true, Limits::default()).unwrap()).contains(&"raus/geheim.txt".to_owned()));
    } else {
        assert_eq!(rels(&entries), ["a.txt", "b.txt"]);
    }

    // Kopieren landet nur im Ziel.
    let target = dir.path().join("ziel");
    copy::copy_entries(&entries, &target, &|_| {}).unwrap();
    assert!(target.join("a.txt").is_file());
    assert!(!dir.path().join("geheim.txt").exists());
}

#[test]
fn detected_launchers_include_unsupported() {
    let present = [ImportSource::Vanilla, ImportSource::Feather];
    let list = detected_launchers(&present, &[]);
    assert_eq!(list.len(), 2);
    assert!(!list.iter().find(|l| l.source == ImportSource::Feather).unwrap().supported);
    let json = serde_json::to_value(ImportSource::GdLauncherCarbon).unwrap();
    assert_eq!(json, "gdlaunchercarbon");
}
