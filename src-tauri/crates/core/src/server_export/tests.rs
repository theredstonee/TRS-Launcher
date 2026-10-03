use std::io::Write;
use std::path::Path;

use super::files::{self, LaunchSpec, ReadmeInfo, ServerProperties};
use super::sides::{self, JarSide, ModSide, SideSignals, SideSource};
use super::*;
use crate::local_servers::{self, LineEvent, ServerState, ServerStatus};

fn jar(entries: &[(&str, &str)]) -> Vec<u8> {
    let mut buf = std::io::Cursor::new(Vec::new());
    {
        let mut zip = zip::ZipWriter::new(&mut buf);
        let options = zip::write::SimpleFileOptions::default();
        for (name, text) in entries {
            zip.start_file(*name, options).unwrap();
            zip.write_all(text.as_bytes()).unwrap();
        }
        zip.finish().unwrap();
    }
    buf.into_inner()
}

fn signals() -> SideSignals {
    SideSignals::default()
}

fn modrinth(client: &str, server: &str) -> Option<(String, String)> {
    Some((client.to_owned(), server.to_owned()))
}

// --- Einordnung ------------------------------------------------------------------

#[test]
fn trs_client_and_known_mods_are_client_only() {
    let s = SideSignals { trs_client: true, modrinth: modrinth("required", "required"), ..signals() };
    assert_eq!(sides::classify(&s), (ModSide::Client, SideSource::Trs));
    let s = SideSignals { known_client: true, jar: Some(JarSide::Both), ..signals() };
    assert_eq!(sides::classify(&s), (ModSide::Client, SideSource::List));
    assert!(sides::is_known_client(&["sodium".into()]));
    assert!(sides::is_known_client(&["xyz".into(), "AANobbMI".into()]));
    assert!(!sides::is_known_client(&["lithium".into(), "fabric-api".into()]));
    assert!(sides::is_trs_client_file("TRSClient.jar"));
    assert!(!sides::is_trs_client_file("trsclient.jar.disabled"));
}

#[test]
fn modrinth_unsupported_server_beats_jar_both() {
    let s = SideSignals { jar: Some(JarSide::Both), modrinth: modrinth("required", "unsupported"), ..signals() };
    assert_eq!(sides::classify(&s), (ModSide::Client, SideSource::Modrinth));
}

#[test]
fn explicit_client_jar_beats_modrinth_both() {
    let s = SideSignals { jar: Some(JarSide::Client), modrinth: modrinth("required", "optional"), ..signals() };
    assert_eq!(sides::classify(&s), (ModSide::Client, SideSource::Jar));
}

#[test]
fn server_only_and_both_and_unknown() {
    let s = SideSignals { modrinth: modrinth("unsupported", "required"), ..signals() };
    assert_eq!(sides::classify(&s), (ModSide::Server, SideSource::Modrinth));
    let s = SideSignals { jar: Some(JarSide::Server), ..signals() };
    assert_eq!(sides::classify(&s).0, ModSide::Server);
    let s = SideSignals { modrinth: modrinth("required", "required"), jar: Some(JarSide::LikelyClient), ..signals() };
    assert_eq!(sides::classify(&s), (ModSide::Both, SideSource::Modrinth));
    // Modrinth weiß nichts → schwacher Jar-Hinweis zählt.
    let s = SideSignals { modrinth: modrinth("unknown", "unknown"), jar: Some(JarSide::LikelyClient), ..signals() };
    assert_eq!(sides::classify(&s), (ModSide::Client, SideSource::Jar));
    let s = SideSignals { curseforge: Some((true, true)), ..signals() };
    assert_eq!(sides::classify(&s), (ModSide::Both, SideSource::Curseforge));
    let s = SideSignals { curseforge: Some((true, false)), ..signals() };
    assert_eq!(sides::classify(&s), (ModSide::Client, SideSource::Curseforge));
    assert_eq!(sides::classify(&signals()), (ModSide::Unknown, SideSource::None));
    assert!(sides::included_by_default(ModSide::Unknown));
    assert!(sides::included_by_default(ModSide::Both));
    assert!(!sides::included_by_default(ModSide::Client));
}

#[test]
fn curseforge_env_tags() {
    let v = |list: &[&str]| list.iter().map(|s| (*s).to_owned()).collect::<Vec<_>>();
    assert_eq!(sides::curseforge_env(&v(&["Client", "1.20.1", "Forge", "Server"])), Some((true, true)));
    assert_eq!(sides::curseforge_env(&v(&["Client", "Fabric"])), Some((true, false)));
    assert_eq!(sides::curseforge_env(&v(&["1.20.1", "Forge"])), None);
}

#[test]
fn reads_sides_from_jar_metadata() {
    assert_eq!(sides::fabric_side(r#"{"id":"a","environment":"client"}"#), Some(JarSide::Client));
    assert_eq!(sides::fabric_side(r#"{"id":"a","environment":"*"}"#), Some(JarSide::Both));
    assert_eq!(sides::fabric_side(r#"{"id":"a"}"#), None);
    assert_eq!(sides::quilt_side(r#"{"minecraft":{"environment":"dedicated_server"}}"#), Some(JarSide::Server));
    assert_eq!(sides::toml_side("clientSideOnly=true\n[[mods]]\nmodId=\"x\""), Some(JarSide::Client));
    assert_eq!(sides::toml_side("[[mods]]\nmodId=\"x\"\ndisplayTest=\"IGNORE_ALL_VERSION\""), Some(JarSide::LikelyClient));
    assert_eq!(sides::toml_side("[[mods]]\nmodId=\"x\"\ndisplayTest=\"IGNORE_SERVER_VERSION\""), Some(JarSide::Server));
    assert_eq!(sides::toml_side("[[mods]]\nmodId=\"x\""), None);

    let bytes = jar(&[("fabric.mod.json", r#"{"id":"sodium","name":"Sodium","version":"0.6.0","environment":"client"}"#)]);
    let facts = sides::read_jar_facts(std::io::Cursor::new(bytes));
    assert_eq!(facts.side, Some(JarSide::Client));
    assert_eq!(facts.ids, vec!["sodium".to_owned()]);
    assert_eq!(facts.name.as_deref(), Some("Sodium"));
    assert!(sides::read_jar_facts(std::io::Cursor::new(b"kein zip".to_vec())).side.is_none());
}

fn write_mod(dir: &Path, name: &str, entries: &[(&str, &str)]) {
    std::fs::write(dir.join(name), jar(entries)).unwrap();
}

#[test]
fn offline_plan_picks_server_mods() {
    let tmp = tempfile::tempdir().unwrap();
    let mods = tmp.path().join("mods");
    std::fs::create_dir_all(&mods).unwrap();
    write_mod(&mods, "sodium.jar", &[("fabric.mod.json", r#"{"id":"sodium","version":"1","environment":"*"}"#)]);
    write_mod(&mods, "lithium.jar", &[("fabric.mod.json", r#"{"id":"lithium","name":"Lithium","version":"1","environment":"*"}"#)]);
    write_mod(&mods, "hud.jar", &[("fabric.mod.json", r#"{"id":"myhud","version":"1","environment":"client"}"#)]);
    write_mod(&mods, "old.jar", &[("mcmod.info", "[]")]);
    write_mod(&mods, "trsclient.jar", &[("fabric.mod.json", r#"{"id":"trsclient","version":"1"}"#)]);
    std::fs::write(mods.join("off.jar.disabled"), b"x").unwrap();
    std::fs::write(mods.join("readme.txt"), b"x").unwrap();

    let scanned = scan_mods(&mods, true);
    let plan = classify_offline(&scanned);
    let by_file = |f: &str| plan.iter().find(|m| m.file_name == f).unwrap().clone();
    assert_eq!(plan.len(), 5, "nur .jar, nichts Deaktiviertes");
    assert_eq!(by_file("sodium.jar").side, ModSide::Client);
    assert!(!by_file("sodium.jar").included);
    assert_eq!(by_file("lithium.jar").side, ModSide::Both);
    assert!(by_file("lithium.jar").included);
    assert_eq!(by_file("lithium.jar").name, "Lithium");
    assert!(!by_file("hud.jar").included);
    assert_eq!(by_file("old.jar").side, ModSide::Unknown);
    assert!(by_file("old.jar").included);
    let trs = by_file("trsclient.jar");
    assert!(trs.locked && !trs.included);
}

fn options() -> ServerExportOptions {
    ServerExportOptions {
        name: "  Mein Server  ".into(),
        mods: vec!["a.jar".into(), "A.JAR".into(), "b.jar".into()],
        include_configs: true,
        world: Some("Welt 1".into()),
        port: 25565,
        motd: "Hallo\nWelt".into(),
        max_players: 10,
        online_mode: true,
        ram_mb: 4096,
        eula_accepted: false,
        zip: true,
        local: false,
    }
}

#[test]
fn options_are_checked_and_normalised() {
    let checked = check_options(&options()).unwrap();
    assert_eq!(checked.name, "Mein Server");
    assert_eq!(checked.mods, vec!["a.jar".to_owned(), "b.jar".to_owned()]);
    assert_eq!(checked.world.as_deref(), Some("Welt 1"));
    assert_eq!(checked.props.motd, "HalloWelt");

    let code = |o: ServerExportOptions| check_options(&o).unwrap_err().message_code();
    assert_eq!(code(ServerExportOptions { name: " ".into(), ..options() }), "serverExport.nameRequired");
    assert_eq!(code(ServerExportOptions { zip: false, local: false, ..options() }), "serverExport.noTarget");
    // Lokal starten geht nur mit akzeptierter EULA.
    assert_eq!(code(ServerExportOptions { local: true, ..options() }), "serverExport.eulaRequired");
    assert!(check_options(&ServerExportOptions { local: true, eula_accepted: true, ..options() }).is_ok());
    assert_eq!(code(ServerExportOptions { port: 80, ..options() }), "serverExport.invalidPort");
    assert_eq!(code(ServerExportOptions { max_players: 0, ..options() }), "serverExport.invalidMaxPlayers");
    assert_eq!(code(ServerExportOptions { ram_mb: 1, ..options() }), "settings.memoryRange");
    for bad in ["../x.jar", "x.zip", "trsclient.jar", "a/b.jar"] {
        assert_eq!(code(ServerExportOptions { mods: vec![bad.into()], ..options() }), "serverExport.invalidMods", "{bad}");
    }
    assert_eq!(code(ServerExportOptions { world: Some("../saves".into()), ..options() }), "serverExport.invalidWorld");
}

#[test]
fn copy_tree_skips_private_files_and_session_lock() {
    let tmp = tempfile::tempdir().unwrap();
    let src = tmp.path().join("config");
    std::fs::create_dir_all(src.join("trsclient")).unwrap();
    std::fs::create_dir_all(src.join("sub")).unwrap();
    std::fs::write(src.join("a.toml"), b"a").unwrap();
    std::fs::write(src.join("sub/b.json"), b"b").unwrap();
    std::fs::write(src.join("trsclient.json"), b"geheim").unwrap();
    std::fs::write(src.join("trsclient/keys.json"), b"geheim").unwrap();
    let dest = tmp.path().join("out/config");
    let copied = copy_tree(&src, Path::new("config"), &dest, &modpack_export::is_private_file, &|| false).unwrap();
    assert_eq!(copied, 2);
    assert!(dest.join("a.toml").is_file() && dest.join("sub/b.json").is_file());
    assert!(!dest.join("trsclient.json").exists() && !dest.join("trsclient").exists());

    let world = tmp.path().join("saves/Welt");
    std::fs::create_dir_all(world.join("region")).unwrap();
    std::fs::write(world.join("level.dat"), b"l").unwrap();
    std::fs::write(world.join("session.lock"), b"s").unwrap();
    std::fs::write(world.join("region/r.0.0.mca"), b"r").unwrap();
    let skip = |rel: &Path| rel.file_name().is_some_and(|n| n == "session.lock");
    let out = tmp.path().join("srv/world");
    assert_eq!(copy_tree(&world, Path::new("world"), &out, &skip, &|| false).unwrap(), 2);
    assert!(out.join("level.dat").is_file() && !out.join("session.lock").exists());
    assert!(matches!(copy_tree(&world, Path::new("world"), &out, &skip, &|| true), Err(Error::Cancelled)));
}

#[test]
fn zip_has_root_folder_and_executable_script() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path().join("srv");
    std::fs::create_dir_all(dir.join("mods")).unwrap();
    std::fs::write(dir.join("start.sh"), b"#!/bin/sh").unwrap();
    std::fs::write(dir.join("mods/a.jar"), b"a").unwrap();
    let dest = tmp.path().join("out.zip");
    write_zip(&dir, "mein-server", &dest, &|_| {}, &|| false).unwrap();
    let mut zip = zip::ZipArchive::new(std::fs::File::open(&dest).unwrap()).unwrap();
    let mut names: Vec<String> = zip.file_names().map(str::to_owned).collect();
    names.sort();
    assert_eq!(names, vec!["mein-server/mods/a.jar".to_owned(), "mein-server/start.sh".to_owned()]);
    assert_eq!(zip.by_name("mein-server/start.sh").unwrap().unix_mode().map(|m| m & 0o777), Some(0o755));
    // Kein Rest der Zwischendatei.
    assert_eq!(std::fs::read_dir(tmp.path()).unwrap().count(), 2);
}

// --- Skripte und Dateien ---------------------------------------------------------------

#[test]
fn start_scripts_use_ram_and_launch_spec() {
    let jar = LaunchSpec::Jar { jar: "fabric-server-launch.jar".into() };
    let bat = files::start_bat(&jar, 4096, 21);
    assert!(bat.contains("\"%MC_JAVA%\" -Xms1024M -Xmx4096M -jar fabric-server-launch.jar nogui\r\n"), "{bat}");
    assert!(bat.contains("Java 21"));
    assert!(bat.contains("cd /d \"%~dp0\""));
    assert!(bat.lines().all(|l| l.ends_with('\r') || l.is_empty()) || bat.contains("\r\n"));
    let sh = files::start_sh(&jar, 768, 17);
    assert!(sh.starts_with("#!/usr/bin/env sh\n"));
    assert!(sh.contains("exec \"${MC_JAVA:-java}\" -Xms768M -Xmx768M -jar fabric-server-launch.jar nogui\n"), "{sh}");
    assert!(!sh.contains('\r'));

    let args = LaunchSpec::ArgsFile {
        windows: "libraries/net/minecraftforge/forge/1.20.1-47.4.10/win_args.txt".into(),
        unix: "libraries/net/minecraftforge/forge/1.20.1-47.4.10/unix_args.txt".into(),
    };
    assert!(files::start_bat(&args, 6144, 17).contains("@user_jvm_args.txt @libraries/net/minecraftforge/forge/1.20.1-47.4.10/win_args.txt nogui"));
    assert!(files::start_sh(&args, 6144, 17).contains("@libraries/net/minecraftforge/forge/1.20.1-47.4.10/unix_args.txt nogui"));
}

#[test]
fn launch_spec_round_trips_as_json() {
    let spec = LaunchSpec::ArgsFile { windows: "a/win_args.txt".into(), unix: "a/unix_args.txt".into() };
    let json = serde_json::to_value(&spec).unwrap();
    assert_eq!(json["type"], "argsFile");
    assert_eq!(serde_json::from_value::<LaunchSpec>(json).unwrap(), spec);
}

#[test]
fn properties_escape_and_basics() {
    let props = ServerProperties { port: 25570, motd: "Grüße \\ von §6TRS".into(), max_players: 8, online_mode: true };
    let text = files::server_properties(&props, true);
    assert!(text.contains("server-port=25570\n"));
    assert!(text.contains("max-players=8\n"));
    assert!(text.contains("online-mode=true\n"));
    assert!(text.contains("level-name=world\n"));
    assert!(text.contains("motd=Gr\\u00fc\\u00dfe \\\\ von \\u00a76TRS\n"), "{text}");
    assert!(text.is_ascii());
    assert_eq!(files::escape_property("a\nb\u{1f600}"), "ab\\ud83d\\ude00");
    assert!(files::eula_txt("2026-10-03").contains("eula=true"));
}

#[test]
fn readme_mentions_eula_state_and_left_out_mods() {
    let left = vec!["Sodium".to_owned()];
    let info = ReadmeInfo {
        name: "Test",
        game_version: "1.21.1",
        loader: LoaderKind::Fabric,
        loader_version: Some("0.16.9"),
        java_major: 21,
        port: 25565,
        ram_mb: 4096,
        eula_accepted: false,
        mods: 3,
        left_out: &left,
        needs_internet_first_start: true,
    };
    let text = files::readme(&info);
    assert!(text.contains("Fabric 0.16.9"));
    assert!(text.contains("NOT been accepted"));
    assert!(text.contains("  - Sodium"));
    assert!(text.contains("Java 21"));
    assert!(text.contains("first start needs internet"));
    let accepted = files::readme(&ReadmeInfo { eula_accepted: true, needs_internet_first_start: false, ..info });
    assert!(accepted.contains("was accepted"));
    assert!(!accepted.contains("needs internet"));
}

#[test]
fn slug_and_versions() {
    assert_eq!(files::slug("  Mein Server! 1.21 "), "mein-server-1-21");
    assert_eq!(files::slug("ÄÖÜ"), "server");
    assert_eq!(files::slug(&"x".repeat(100)).len(), 40);
    assert_eq!(files::minor_version("1.20.1"), Some(20));
    assert_eq!(files::minor_version("1.7.10"), Some(7));
    assert_eq!(files::minor_version("26.1"), Some(126));
    assert_eq!(files::minor_version("24w14a"), None);
    assert!(local_servers::validate_server_id(&files::slug("Mein Server")).is_ok());
    assert_eq!(suggested_zip_name("Mein Server"), "mein-server-server.zip");
}

#[test]
fn detects_forge_launch_layouts() {
    let tmp = tempfile::tempdir().unwrap();
    let modern = tmp.path().join("modern");
    let lib = modern.join("libraries/net/neoforged/neoforge/21.1.251");
    std::fs::create_dir_all(&lib).unwrap();
    std::fs::write(lib.join("win_args.txt"), b"").unwrap();
    std::fs::write(lib.join("unix_args.txt"), b"").unwrap();
    assert_eq!(
        install::detect_forge_launch(&modern),
        Some(LaunchSpec::ArgsFile {
            windows: "libraries/net/neoforged/neoforge/21.1.251/win_args.txt".into(),
            unix: "libraries/net/neoforged/neoforge/21.1.251/unix_args.txt".into(),
        })
    );
    let legacy = tmp.path().join("legacy");
    std::fs::create_dir_all(&legacy).unwrap();
    std::fs::write(legacy.join("forge-1.12.2-14.23.5.2860.jar"), b"").unwrap();
    std::fs::write(legacy.join("forge-1.12.2-14.23.5.2860-installer.jar"), b"").unwrap();
    std::fs::write(legacy.join("minecraft_server.1.12.2.jar"), b"").unwrap();
    assert_eq!(install::detect_forge_launch(&legacy), Some(LaunchSpec::Jar { jar: "forge-1.12.2-14.23.5.2860.jar".into() }));
    assert_eq!(install::detect_forge_launch(&tmp.path().join("leer")), None);
    assert!(install::is_safe_version("0.16.9+build.1"));
    assert!(!install::is_safe_version("1.0; rm"));
}

// --- Lokale Server ----------------------------------------------------------------------

#[test]
fn parses_server_log_lines() {
    let p = local_servers::parse_line;
    assert_eq!(p("[12:00:01] [Server thread/INFO]: Done (3.512s)! For help, type \"help\""), Some(LineEvent::Ready));
    assert_eq!(p("[12:00:02] [Server thread/INFO]: Steve joined the game"), Some(LineEvent::Joined("Steve".into())));
    assert_eq!(p("[12:00:03] [Server thread/INFO] [minecraft/MinecraftServer]: Alex_2 left the game"), Some(LineEvent::Left("Alex_2".into())));
    assert_eq!(
        p("[12:00:04] [Server thread/INFO]: There are 2 of a max of 20 players online: Steve, Alex"),
        Some(LineEvent::Count(2, Some(20)))
    );
    assert_eq!(p("[12:00:04] [Server thread/INFO]: There are 1/10 players online:"), Some(LineEvent::Count(1, Some(10))));
    // Chat kann „joined the game“ nicht vortäuschen (Name mit Leerzeichen/Klammern).
    assert_eq!(p("[12:00:05] [Server thread/INFO]: <Bob> Eve joined the game"), None);
    assert_eq!(p("[12:00:06] [Server thread/WARN]: Steve joined the game"), None);
    assert_eq!(p("irgendwas"), None);
}

#[test]
fn status_follows_line_events() {
    let mut status = ServerStatus {
        state: ServerState::Starting,
        players: Some(0),
        player_names: Vec::new(),
        max_players: 20,
        started_at: None,
        exit_code: None,
    };
    local_servers::apply_line_event(&mut status, LineEvent::Ready);
    assert_eq!(status.state, ServerState::Running);
    local_servers::apply_line_event(&mut status, LineEvent::Joined("Steve".into()));
    local_servers::apply_line_event(&mut status, LineEvent::Joined("Alex".into()));
    local_servers::apply_line_event(&mut status, LineEvent::Joined("Steve".into()));
    assert_eq!(status.players, Some(2));
    assert_eq!(status.player_names, vec!["Alex".to_owned(), "Steve".to_owned()]);
    local_servers::apply_line_event(&mut status, LineEvent::Left("Steve".into()));
    assert_eq!(status.players, Some(1));
    local_servers::apply_line_event(&mut status, LineEvent::Count(3, Some(30)));
    assert_eq!((status.players, status.max_players), (Some(3), 30));
}

#[test]
fn cleans_lines_and_commands() {
    assert_eq!(local_servers::clean_line("\u{1b}[32mGrün\u{1b}[0m\tText\u{7}"), "Grün Text");
    assert_eq!(local_servers::clean_command(" /say Hallo ").unwrap(), "say Hallo");
    assert!(local_servers::clean_command("").is_err());
    assert!(local_servers::clean_command("stop\nop Eve").is_err());
    assert!(local_servers::clean_command(&"a".repeat(300)).is_err());
}

#[test]
fn server_ids_and_eula() {
    assert!(local_servers::validate_server_id("mein-server-2").is_ok());
    for bad in ["", ".staging-1", "../x", "A", "a b", "-x"] {
        assert!(local_servers::validate_server_id(bad).is_err(), "{bad}");
    }
    let tmp = tempfile::tempdir().unwrap();
    assert!(!local_servers::eula_accepted(tmp.path()));
    std::fs::write(tmp.path().join("eula.txt"), "#x\neula=false\n").unwrap();
    assert!(!local_servers::eula_accepted(tmp.path()));
    std::fs::write(tmp.path().join("eula.txt"), files::eula_txt("heute")).unwrap();
    assert!(local_servers::eula_accepted(tmp.path()));

    let paths = crate::paths::Paths::new(tmp.path());
    std::fs::create_dir_all(local_servers::servers_dir(&paths).join("srv")).unwrap();
    assert_eq!(local_servers::free_id(&paths, "srv"), "srv-2");
    assert_eq!(local_servers::free_id(&paths, "neu"), "neu");
}

#[tokio::test]
async fn lists_local_servers_with_meta_only() {
    let tmp = tempfile::tempdir().unwrap();
    let paths = crate::paths::Paths::new(tmp.path());
    let base = local_servers::servers_dir(&paths);
    std::fs::create_dir_all(base.join("ohne-meta")).unwrap();
    std::fs::create_dir_all(base.join(".staging-abc")).unwrap();
    let dir = base.join("mein-server");
    std::fs::create_dir_all(&dir).unwrap();
    let meta = local_servers::ServerMeta {
        name: "Mein Server".into(),
        game_version: "1.21.1".into(),
        loader: LoaderKind::Fabric,
        loader_version: Some("0.16.9".into()),
        java_major: 21,
        java_component: "java-runtime-delta".into(),
        ram_mb: 4096,
        port: 25565,
        max_players: 20,
        launch: LaunchSpec::Jar { jar: "fabric-server-launch.jar".into() },
        instance_id: None,
        created_at: chrono::Utc::now(),
    };
    std::fs::write(dir.join(local_servers::META_FILE), serde_json::to_vec(&meta).unwrap()).unwrap();
    let servers = std::sync::Arc::new(local_servers::LocalServers::default());
    let list = servers.list(&paths).await.unwrap();
    assert_eq!(list.len(), 1);
    assert_eq!(list[0].id, "mein-server");
    assert_eq!(list[0].status.state, ServerState::Stopped);
    assert!(!list[0].eula_accepted);
    // Ohne EULA startet nichts (und es wird kein Prozess gestartet).
    let err = servers.start("mein-server", &dir, &meta, Path::new("java-gibt-es-nicht")).unwrap_err();
    assert_eq!(err.message_code(), "localServer.eulaMissing");
    assert!(servers.command("mein-server", "list").is_err());
    assert_eq!(local_servers::read_meta(&dir).unwrap(), meta);
}
