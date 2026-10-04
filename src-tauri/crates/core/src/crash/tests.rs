//! Tests des Absturz-Helfers mit gekürzten, anonymisierten Beispiel-Logs
//! (`fixtures/`). Fixture 01 ist der echte Fall aus diesem Projekt.

use std::io::Write;
use std::sync::{Arc, Mutex};

use super::parse::{self, Text, handler_mod, strip_log_prefix};
use super::*;
use crate::gamelog::{Level, LogLine};
use crate::instance::{InstanceStore, Loader, NewInstance};
use crate::paths::Paths;
use crate::process::GameEvent;

fn fixture(name: &str) -> &'static str {
    match name {
        "01" => include_str!("fixtures/01-fabric-trsclient-essential-mixinextras.txt"),
        "02" => include_str!("fixtures/02-fabric-missing-cloth-config.log"),
        "03" => include_str!("fixtures/03-fabric-sodium-iris-incompatible.log"),
        "04" => include_str!("fixtures/04-forge-missing-dependencies.log"),
        "05" => include_str!("fixtures/05-forge-duplicate-mods.log"),
        "06" => include_str!("fixtures/06-fabric-wrong-minecraft-version.log"),
        "07" => include_str!("fixtures/07-out-of-memory-report.txt"),
        "08" => include_str!("fixtures/08-jvm-could-not-reserve.log"),
        "09" => include_str!("fixtures/09-forge-java-too-old.log"),
        "10" => include_str!("fixtures/10-lwjgl2-pixel-format-intel.log"),
        "11" => include_str!("fixtures/11-amd-access-violation.log"),
        "12" => include_str!("fixtures/12-fabric-corrupt-mod-jar.log"),
        "13" => include_str!("fixtures/13-mixin-redirect-conflict.log"),
        "14" => include_str!("fixtures/14-sodium-optifine.log"),
        "15" => include_str!("fixtures/15-unknown-npe-mod-frame.txt"),
        "16" => include_str!("fixtures/16-fabric-iris-without-sodium.log"),
        "17" => include_str!("fixtures/17-neoforge-two-minimaps.txt"),
        "18" => include_str!("fixtures/18-old-forge-java-too-new.log"),
        "19" => include_str!("fixtures/19-fabric-iris-needs-newer-sodium.log"),
        _ => unreachable!(),
    }
}

fn jar(file: &str, id: &str, name: &str, version: &str, packages: &[&str]) -> InstalledMod {
    InstalledMod {
        file_name: file.into(),
        enabled: true,
        id: Some(id.into()),
        name: Some(name.into()),
        version: Some(version.into()),
        packages: packages.iter().map(|p| (*p).to_owned()).collect(),
        mixin_configs: vec![format!("{id}.mixins.json")],
        ..Default::default()
    }
}

fn log(text: &str, installed: &[InstalledMod]) -> Analysis {
    analyze(&CrashInput { log: text, installed, ..Default::default() })
}

fn report(text: &str, installed: &[InstalledMod]) -> Analysis {
    analyze(&CrashInput { report: Some(text), installed, ..Default::default() })
}

fn has_disable(f: &Finding, file: &str) -> bool {
    f.actions.iter().any(|a| matches!(a, CrashAction::DisableMods { files } if files.iter().any(|x| x == file)))
}

fn no_private_data(a: &Analysis) {
    let json = serde_json::to_string(a).unwrap();
    for secret in ["Max Mustermann", "Steve", "0123456789abcdef0123456789abcdef"] {
        assert!(!json.contains(secret), "{secret} steht im Ergebnis: {json}");
    }
}

// --- 01: der echte Fall -------------------------------------------------------------

fn real_case_mods(trs_version: &str) -> Vec<InstalledMod> {
    vec![
        jar("trsclient.jar", "trsclient", "TRS Client", trs_version, &["dev.theredstonee.trsclient"]),
        InstalledMod {
            nested: vec!["mixinextras".into()],
            ..jar("Essential-fabric_1-21-11.jar", "essential", "Essential", "1.3.10.4", &["gg.essential.loader", "gg.essential.mixins"])
        },
        jar("sodium-fabric-0.8.14+mc1.21.11.jar", "sodium", "Sodium", "0.8.14+mc1.21.11", &["net.caffeinemc.mods.sodium"]),
    ]
}

#[test]
fn real_case_trs_client_0_9_0_with_essential_and_mixinextras() {
    let mods = real_case_mods("0.9.0");
    let a = report(fixture("01"), &mods);
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref()), (CrashKind::KnownIssue, Some("trsclient_essential")));
    assert_eq!(f.score, 100);
    assert_eq!(f.params["version"], "0.9.0");
    assert_eq!(f.params["fixed"], rules::TRS_ESSENTIAL_FIXED);
    assert_eq!(f.actions[0], CrashAction::UpdateTrsClient, "Hauptknopf: TRS Client aktualisieren");
    assert!(has_disable(f, "Essential-fabric_1-21-11.jar"), "Ausweg: Essential deaktivieren");
    for id in ["trsclient", "essential", "mixinextras"] {
        assert!(f.mods.iter().any(|m| m == id), "{id} fehlt in {:?}", f.mods);
    }
    // Mods mit Namen/Dateien aus der Instanz; MixinExtras steckt in Essential.
    let extras = a.mods.iter().find(|m| m.id == "mixinextras").unwrap();
    assert_eq!(extras.bundled_in.as_deref(), Some("Essential"));
    assert_eq!(extras.version.as_deref(), Some("0.5.0"));
    let trs = a.mods.iter().find(|m| m.id == "trsclient").unwrap();
    assert_eq!((trs.file.as_deref(), trs.name.as_str()), (Some("trsclient.jar"), "TRS Client"));
    // Dazu der allgemeine Mixin-Befund mit lesbarer Zielklasse.
    let mixin = a.findings.iter().find(|f| f.kind == CrashKind::MixinConflict).unwrap();
    assert_eq!(mixin.params["target"], "IntegratedServer (class_1132)");
    assert!(mixin.mods.contains(&"essential".to_owned()));
    assert!(a.cause.as_deref().unwrap().contains("ClassCastException"));
    assert!(a.first_frame.as_deref().unwrap().contains("handler$bdk000$essential$"));
    assert!(a.excerpt.iter().any(|l| l.contains("Mixin transformation of net.minecraft.class_1132 failed")));

    // Auch ohne Mod-Dateien – nur mit der Mod-Liste des Crash-Reports.
    let listed_only = report(fixture("01"), &[]);
    assert_eq!(listed_only.primary().variant.as_deref(), Some("trsclient_essential"));
    assert_eq!(listed_only.primary().actions, vec![CrashAction::UpdateTrsClient]);

    // Mit dem Hotfix ist es kein bekanntes Problem mehr – dann bleibt der Mixin-Befund.
    let fixed = report(fixture("01"), &real_case_mods("0.9.1"));
    assert_eq!(fixed.primary().kind, CrashKind::MixinConflict);
    assert!(fixed.primary().mods.contains(&"essential".to_owned()));
    assert!(has_disable(fixed.primary(), "Essential-fabric_1-21-11.jar"));
}

// --- 02–06: Abhängigkeiten, Versionen, Duplikate -----------------------------------

#[test]
fn fabric_missing_dependency() {
    let mods = [jar("moreculling-fabric-1.21.11-1.6.2.jar", "moreculling", "More Culling", "1.6.2", &["ca.fxco.moreculling"])];
    let a = log(fixture("02"), &mods);
    let f = a.primary();
    assert_eq!(f.kind, CrashKind::MissingDependency);
    assert_eq!(f.params["name"], "More Culling");
    // Bekannte Mods mit lesbarem Namen.
    assert_eq!(f.params["deps"], "Cloth Config");
    assert_eq!(
        f.actions,
        vec![CrashAction::InstallDependencies { declarer: Some("moreculling".into()), dependencies: vec!["cloth-config".into()] }]
    );
    assert_eq!(a.mods[0].file.as_deref(), Some("moreculling-fabric-1.21.11-1.6.2.jar"));
}

#[test]
fn fabric_incompatible_versions() {
    let mods = [
        jar("sodium.jar", "sodium", "Sodium", "0.8.14+mc1.21.11", &["net.caffeinemc.mods.sodium"]),
        jar("iris.jar", "iris", "Iris", "1.10.7+mc1.21.11", &["net.irisshaders.iris"]),
    ];
    let a = log(fixture("03"), &mods);
    let f = a.primary();
    assert_eq!(f.kind, CrashKind::IncompatibleMod);
    assert_eq!(f.params["name"], "Sodium 0.8.14+mc1.21.11");
    assert_eq!(f.params["other"], "Iris 1.10.7+mc1.21.11");
    assert_eq!(f.actions[0], CrashAction::FixConflict { mod_id: "sodium".into() });
    assert!(has_disable(f, "sodium.jar"));
    assert_eq!(f.mods, ["sodium", "iris"]);
}

/// Fehlerbericht 0.18.0: Iris 1.11.4 verlangt Sodium 0.9.x, da liegt 0.8.9 –
/// getauscht wird Sodium (nicht Iris), Iris abschalten bleibt als Ausweg.
#[test]
fn fabric_dependency_in_wrong_version_swaps_the_dependency() {
    let mods = [
        jar("sodium.jar", "sodium", "Sodium", "0.8.9+mc26.1.1", &["net.caffeinemc.mods.sodium"]),
        jar("iris.jar", "iris", "Iris", "1.11.4+mc26.1.2", &["net.irisshaders.iris"]),
    ];
    let a = log(fixture("19"), &mods);
    let f = a.primary();
    assert_eq!(f.kind, CrashKind::IncompatibleMod);
    assert_eq!(f.params["name"], "Sodium 0.8.9+mc26.1.1");
    assert_eq!(f.params["other"], "Iris 1.11.4+mc26.1.2");
    assert_eq!(f.actions[0], CrashAction::FixConflict { mod_id: "sodium".into() });
    assert!(has_disable(f, "iris.jar"));
}

#[test]
fn forge_missing_dependencies_are_masked() {
    let a = log(fixture("04"), &[]);
    let f = a.primary();
    assert_eq!(f.kind, CrashKind::MissingDependency);
    assert_eq!(
        f.actions,
        vec![CrashAction::InstallDependencies {
            declarer: Some("moreculling".into()),
            dependencies: vec!["cloth_config".into(), "architectury".into()]
        }]
    );
    no_private_data(&a);
}

#[test]
fn forge_duplicate_mods_keep_the_newest() {
    let mods = [
        jar("jei-1.20.1-forge-15.2.0.27.jar", "jei", "Just Enough Items", "15.2.0.27", &["mezz.jei"]),
        jar("jei-1.20.1-forge-15.3.0.4.jar", "jei", "Just Enough Items", "15.3.0.4", &["mezz.jei"]),
    ];
    let a = log(fixture("05"), &mods);
    let f = a.primary();
    assert_eq!((f.kind, f.score), (CrashKind::DuplicateMod, 90));
    assert_eq!(f.params["keep"], "jei-1.20.1-forge-15.3.0.4.jar");
    assert_eq!(
        f.actions,
        vec![CrashAction::RemoveDuplicates { files: vec!["jei-1.20.1-forge-15.2.0.27.jar".into()], keep: vec!["jei-1.20.1-forge-15.3.0.4.jar".into()] }]
    );
    // Auch ohne Log-Meldung fällt es anhand der Mod-Liste auf – nur schwächer.
    let quiet = log("java.lang.IllegalStateException: boom", &mods);
    let dup = quiet.findings.iter().find(|f| f.kind == CrashKind::DuplicateMod).unwrap();
    assert_eq!(dup.score, 70);
}

#[test]
fn fabric_mod_for_wrong_minecraft_version() {
    let mods = [jar("xaerominimap-fabric-1.21-24.2.0.jar", "xaerominimap", "Xaero's Minimap", "24.2.0", &["xaero.common"])];
    let a = log(fixture("06"), &mods);
    let f = a.primary();
    assert_eq!(f.kind, CrashKind::WrongGameVersion);
    assert_eq!(f.params["name"], "Xaero's Minimap");
    assert_eq!(f.params["required"], "1.21");
    assert_eq!(f.params["present"], "1.21.11");
    assert!(has_disable(f, "xaerominimap-fabric-1.21-24.2.0.jar"));
    assert!(!a.findings.iter().any(|f| f.kind == CrashKind::IncompatibleMod), "Minecraft ist keine Mod: {:?}", a.findings);
}

#[test]
fn wrong_loader_and_java_requirements() {
    let text = "Mod 'Sodium' (sodium) 0.6.0 requires version 0.16.0 or later of 'Fabric Loader' (fabricloader), but only the wrong version is present: 0.15.11!\n\
                Mod 'Lithium' (lithium) 0.14.0 requires version 21 or later of 'Java' (java), but only the wrong version is present: 17!";
    let a = log(text, &[jar("sodium.jar", "sodium", "Sodium", "0.6.0", &[])]);
    let loader = a.findings.iter().find(|f| f.kind == CrashKind::WrongLoaderVersion).unwrap();
    assert_eq!((loader.params["loader"].as_str(), loader.params["present"].as_str()), ("Fabric Loader", "0.15.11"));
    assert!(has_disable(loader, "sodium.jar"));
    let java = a.findings.iter().find(|f| f.kind == CrashKind::WrongJava).unwrap();
    assert_eq!(java.actions, vec![CrashAction::SwitchJava { major: Some(21) }]);
    // Forge: falsche Minecraft-Version im Bereich.
    let forge = log(
        "Missing or unsupported mandatory dependencies:\n\tMod ID: 'minecraft', Requested by: 'oldmod', Expected range: '[1.19.2,1.19.3)', Actual version: '1.20.1'",
        &[],
    );
    assert_eq!(forge.primary().kind, CrashKind::WrongGameVersion);
    assert_eq!(forge.primary().params["required"], "[1.19.2,1.19.3)");
}

// --- 07–09, 18: Speicher und Java --------------------------------------------------

#[test]
fn out_of_memory_suggests_more_ram() {
    let a = analyze(&CrashInput { report: Some(fixture("07")), memory_mb: Some(2048), system_memory_mb: Some(16_384), ..Default::default() });
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref()), (CrashKind::OutOfMemory, Some("heap")));
    assert_eq!(f.actions, vec![CrashAction::SetMemory { from_mb: 2048, to_mb: 4096 }]);
    // Ohne Instanz-Wert: aus dem Crash-Report („up to … (2048 MiB)“).
    let from_report = analyze(&CrashInput { report: Some(fixture("07")), system_memory_mb: Some(32_768), ..Default::default() });
    assert_eq!(from_report.primary().params["current"], "2048");
    // Schon am Limit des PCs: kein Knopf, anderer Text.
    let maxed = analyze(&CrashInput { report: Some(fixture("07")), memory_mb: Some(6144), system_memory_mb: Some(8192), ..Default::default() });
    assert_eq!(maxed.primary().variant.as_deref(), Some("heap_max"));
    assert!(maxed.primary().actions.is_empty());
}

#[test]
fn jvm_cannot_reserve_memory() {
    let a = analyze(&CrashInput { log: fixture("08"), memory_mb: Some(16_384), system_memory_mb: Some(8192), ..Default::default() });
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref()), (CrashKind::OutOfMemory, Some("reserve")));
    assert_eq!(f.actions, vec![CrashAction::SetMemory { from_mb: 16_384, to_mb: 4096 }]);
}

#[test]
fn android_engine_exit_report_is_shown_for_unknown_crashes() {
    let log = "[12:00:00] [Render thread/INFO]: hi
[TRS] Spielprozess beendet: nativer Absturz (Status 11)
[TRS] signal 11 (SIGSEGV), SEGV_MAPERR
[TRS]   #00 pc 1a2b libmobileglues.so (glDrawElements+12)
";
    let a = analyze(&CrashInput { log, ..Default::default() });
    let f = a.primary();
    assert_eq!(f.kind, CrashKind::Unknown);
    assert!(f.evidence.iter().any(|l| l.contains("SIGSEGV")), "{:?}", f.evidence);
    assert!(f.evidence.iter().any(|l| l.contains("libmobileglues.so")));
}

#[test]
fn android_low_memory_kill_is_out_of_memory() {
    let log = "[12:00:00] [Render thread/INFO]: Loading 70 mods
[TRS] Spielprozess beendet: zu wenig Arbeitsspeicher (vom System beendet) (Status 9)
";
    let a = analyze(&CrashInput { log, memory_mb: Some(4096), system_memory_mb: Some(12_288), ..Default::default() });
    assert_eq!((a.primary().kind, a.primary().variant.as_deref()), (CrashKind::OutOfMemory, Some("reserve")));
}

#[test]
fn java_too_old_for_a_mod() {
    let mods = [jar("coolmod-1.0.0.jar", "coolmod", "Cool Mod", "1.0.0", &["com.example.coolmod"])];
    let a = log(fixture("09"), &mods);
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref()), (CrashKind::WrongJava, Some("too_old")));
    assert_eq!((f.params["required"].as_str(), f.params["present"].as_str()), ("21", "17"));
    assert_eq!(f.params["name"], "Cool Mod");
    assert_eq!(f.actions, vec![CrashAction::SwitchJava { major: Some(21) }]);
}

#[test]
fn old_forge_on_too_new_java() {
    let a = log(fixture("18"), &[]);
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref()), (CrashKind::WrongJava, Some("too_new")));
    assert_eq!(f.actions, vec![CrashAction::SwitchJava { major: Some(8) }]);
}

// --- 10–12: Grafik und Dateien ------------------------------------------------------

#[test]
fn graphics_driver_without_opengl_is_masked() {
    let a = log(fixture("10"), &[]);
    assert_eq!((a.primary().kind, a.primary().variant.as_deref()), (CrashKind::GraphicsDriver, Some("no_opengl")));
    no_private_data(&a);
}

#[test]
fn amd_driver_crash_with_shaders() {
    let mods = [jar("iris.jar", "iris", "Iris", "1.8.8", &[]), jar("sodium.jar", "sodium", "Sodium", "0.6.0", &[])];
    let a = log(fixture("11"), &mods);
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref()), (CrashKind::GraphicsDriver, Some("amd")));
    assert_eq!(f.params["shaders"], "Iris");
    assert!(has_disable(f, "iris.jar"));
    assert!(f.evidence.iter().any(|l| l.contains("atio6axx.dll")));
    no_private_data(&a);
}

#[test]
fn corrupt_mod_jar() {
    let mods = [jar("journeymap-fabric-1.21.1-6.0.0.jar", "journeymap", "JourneyMap", "6.0.0", &["journeymap.client"])];
    let a = log(fixture("12"), &mods);
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref()), (CrashKind::CorruptFiles, Some("mod_file")));
    assert_eq!(f.params["file"], "journeymap-fabric-1.21.1-6.0.0.jar");
    assert!(has_disable(f, "journeymap-fabric-1.21.1-6.0.0.jar"));
    no_private_data(&a);
    // Ohne Mod-Datei im Text: Spieldateien reparieren.
    let game = log("Caused by: java.util.zip.ZipException: zip END header not found", &[]);
    assert_eq!(game.primary().variant.as_deref(), Some("game_files"));
    assert_eq!(game.primary().actions, vec![CrashAction::Repair]);
    let assets = log("java.io.FileNotFoundException: C:\\Users\\x\\TRS\\assets\\indexes\\17.json (Das System kann die Datei nicht finden)", &[]);
    assert_eq!(assets.primary().variant.as_deref(), Some("assets"));
}

// --- 13–17: Mixin, bekannte Kombis, Rückfall -----------------------------------------

#[test]
fn mixin_redirect_conflict_names_both_mods() {
    let mods = [
        jar("betterchat-3.1.0.jar", "betterchat", "Better Chat", "3.1.0", &["dev.example.betterchat"]),
        jar("chatheads-0.13.4.jar", "chatheads", "Chat Heads", "0.13.4", &["dzwdz.chat_heads"]),
    ];
    let a = log(fixture("13"), &mods);
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref()), (CrashKind::MixinConflict, Some("redirect_conflict")));
    assert_eq!(&f.mods[..2], ["betterchat", "chatheads"]);
    assert_eq!(f.params["names"], "Better Chat, Chat Heads");
    assert!(has_disable(f, "betterchat-3.1.0.jar") && has_disable(f, "chatheads-0.13.4.jar"));
}

#[test]
fn sodium_with_optifine() {
    let mods = [
        jar("sodium-fabric-0.5.11+mc1.20.1.jar", "sodium", "Sodium", "0.5.11+mc1.20.1", &["me.jellysquid.mods.sodium"]),
        jar("optifabric-1.14.3.jar", "optifabric", "OptiFabric", "1.14.3", &["me.modmuss50.optifabric"]),
        InstalledMod { file_name: "OptiFine_1.20.1_HD_U_I6.jar".into(), enabled: true, ..Default::default() },
    ];
    let a = log(fixture("14"), &mods);
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref()), (CrashKind::KnownIssue, Some("sodium_optifine")));
    assert!(has_disable(f, "OptiFine_1.20.1_HD_U_I6.jar") && has_disable(f, "optifabric-1.14.3.jar"));
    assert!(!has_disable(f, "sodium-fabric-0.5.11+mc1.20.1.jar"));
    assert!(a.mods.iter().any(|m| m.id == "optifine" && m.file.as_deref() == Some("OptiFine_1.20.1_HD_U_I6.jar")));
}

#[test]
fn unknown_crash_points_at_the_mod_in_the_stack() {
    let mods = [jar("loyal-pets-2.4.0.jar", "petmod", "Loyal Pets", "2.4.0", &["com.example.petmod"])];
    let a = report(fixture("15"), &mods);
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref()), (CrashKind::Unknown, Some("suspect")));
    assert_eq!(f.params["name"], "Loyal Pets");
    assert!(has_disable(f, "loyal-pets-2.4.0.jar"));
    assert!(a.cause.as_deref().unwrap().starts_with("java.lang.NullPointerException"));
    assert!(a.first_frame.as_deref().unwrap().contains("PetFollowGoal"));
    // Ganz ohne Hinweise: trotzdem ein Befund mit den wichtigsten Zeilen.
    let bare = log("java.lang.IllegalStateException: Something odd\n\tat net.minecraft.class_310.method_1523(class_310.java:1)", &[]);
    assert_eq!((bare.primary().kind, bare.primary().variant.as_deref()), (CrashKind::Unknown, None));
    assert_eq!(bare.cause.as_deref(), Some("java.lang.IllegalStateException: Something odd"));
    assert!(bare.primary().actions.is_empty());
    let empty = log("", &[]);
    assert_eq!(empty.findings.len(), 1);
}

#[test]
fn iris_without_sodium() {
    let mods = [jar("iris-1.8.8+mc1.21.1.jar", "iris", "Iris", "1.8.8+mc1.21.1", &["net.irisshaders.iris"])];
    let a = log(fixture("16"), &mods);
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref()), (CrashKind::KnownIssue, Some("iris_without_sodium")));
    assert_eq!(f.actions, vec![CrashAction::InstallDependencies { declarer: Some("iris".into()), dependencies: vec!["sodium".into()] }]);
    assert!(!a.findings.iter().any(|f| f.kind == CrashKind::MissingDependency), "nicht doppelt: {:?}", a.findings);
}

#[test]
fn two_minimaps_on_neoforge() {
    let mods = [
        jar("journeymap-neoforge-1.21.1-6.0.0-beta.28.jar", "journeymap", "Journeymap", "6.0.0-beta.28", &["journeymap.client"]),
        jar("xaerominimap-neoforge-1.21.1-25.2.0.jar", "xaerominimap", "Xaero's Minimap", "25.2.0", &["xaero.common", "xaero.minimap"]),
    ];
    let a = report(fixture("17"), &mods);
    let f = a.primary();
    assert_eq!((f.kind, f.variant.as_deref(), f.score), (CrashKind::KnownIssue, Some("two_minimaps"), 92));
    assert!(has_disable(f, "journeymap-neoforge-1.21.1-6.0.0-beta.28.jar"));
    assert!(has_disable(f, "xaerominimap-neoforge-1.21.1-25.2.0.jar"));
    // Forge-Mod-Liste aus dem Crash-Report gelesen.
    let text = Text::new("", Some(fixture("17")));
    assert!(text.listed.iter().any(|m| m.id == "journeymap" && m.version.as_deref() == Some("1.21.1-6.0.0-beta.28")));
    // Ohne die Minimaps im Stack nur ein schwacher Hinweis.
    let quiet = log("java.lang.IllegalStateException: x", &mods);
    assert_eq!(quiet.primary().score, 55);
}

// --- Bausteine -----------------------------------------------------------------------

#[test]
fn parsing_helpers() {
    assert_eq!(strip_log_prefix("[12:00:00] [Render thread/ERROR]: boom"), "boom");
    assert_eq!(strip_log_prefix("[26Sep2026 14:22:12.004] [main/ERROR] [net.minecraftforge.fml.loading.ModSorter/LOADING]: Missing"), "Missing");
    assert_eq!(strip_log_prefix("[Essential Loader] Starting"), "[Essential Loader] Starting");
    assert_eq!(handler_mod("handler$zza000$sodium$onInit"), Some("sodium".into()));
    assert_eq!(handler_mod("wrapOperation$bcd012$trsclient$render"), Some("trsclient".into()));
    assert_eq!(handler_mod("method_1523"), None);
    assert_eq!(handler_mod("lambda$static$0"), None);
    assert_eq!(parse::mixin_configs("[sodium.mixins.json:core.X from mod sodium] and mixins.jei.json"), ["sodium.mixins.json", "mixins.jei.json"]);
    assert_eq!(parse::from_mod("Mixin [a.mixins.json:X from mod moda] vs [b.mixins.json:Y from mod modb]"), ["moda", "modb"]);
    // Fabric-Log-Mod-Liste mit eingebetteten Mods.
    let text = Text::new(
        "[09:41:02] [main/INFO]: Loading 3 mods:\n\t- essential 1.3.10.4\n\t   \\-- mixinextras 0.5.0\n\t- trsclient 0.9.0\n[09:41:03] [main/INFO]: weiter",
        None,
    );
    assert_eq!(text.listed.len(), 3);
    assert_eq!(text.listed[1].parent.as_deref(), Some("essential"));
    assert_eq!(text.listed[2].version.as_deref(), Some("0.9.0"));
    // Maskierung.
    assert_eq!(parse::mask("Setting user: Steve"), "Setting user: <player>");
    assert!(parse::mask("C:\\Users\\Max Mustermann\\AppData\\x.jar").contains("C:\\Users\\<user>\\AppData"));
}

#[test]
fn serialized_shape_for_the_frontend() {
    let a = report(fixture("01"), &real_case_mods("0.9.0"));
    let json = serde_json::to_value(&a).unwrap();
    assert_eq!(json["findings"][0]["kind"], "known_issue");
    assert_eq!(json["findings"][0]["variant"], "trsclient_essential");
    assert_eq!(json["findings"][0]["actions"][0]["type"], "updateTrsClient");
    assert_eq!(json["findings"][0]["actions"][1]["type"], "disableMods");
    assert_eq!(json["findings"][0]["actions"][1]["files"][0], "Essential-fabric_1-21-11.jar");
    let mem = serde_json::to_value(CrashAction::SetMemory { from_mb: 2048, to_mb: 4096 }).unwrap();
    assert_eq!(mem, serde_json::json!({ "type": "setMemory", "fromMb": 2048, "toMb": 4096 }));
    assert_eq!(serde_json::to_value(CrashKind::WrongGameVersion).unwrap(), "wrong_game_version");
    for kind in [CrashKind::KnownIssue, CrashKind::OutOfMemory, CrashKind::Unknown] {
        assert_eq!(serde_json::to_value(kind).unwrap(), kind.as_str());
    }
}

#[test]
fn every_fixture_gets_a_useful_primary_finding() {
    let no_mods: &[InstalledMod] = &[];
    for n in 1..=18 {
        let name = format!("{n:02}");
        let text = fixture(&name);
        let a = if text.starts_with("---- Minecraft Crash Report") { report(text, no_mods) } else { log(text, no_mods) };
        assert!(!a.findings.is_empty(), "{name}");
        // Nur Fixture 15 (Spiel-Logik-Fehler ohne Mods) darf „unbekannt“ bleiben.
        if n != 15 {
            assert_ne!(a.primary().kind, CrashKind::Unknown, "{name}: {:?}", a.findings);
        }
        no_private_data(&a);
    }
}

// --- Launcher-Anbindung ---------------------------------------------------------------

fn write_jar(path: &std::path::Path, fabric_json: &str, classes: &[&str], extra: &[&str]) {
    let file = std::fs::File::create(path).unwrap();
    let mut zip = zip::ZipWriter::new(file);
    let opts: zip::write::SimpleFileOptions = zip::write::SimpleFileOptions::default();
    zip.start_file("fabric.mod.json", opts).unwrap();
    zip.write_all(fabric_json.as_bytes()).unwrap();
    for name in classes.iter().chain(extra) {
        zip.start_file(*name, opts).unwrap();
        zip.write_all(b"x").unwrap();
    }
    zip.finish().unwrap();
}

#[tokio::test]
async fn analyzes_after_exit_saves_and_reports() {
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    paths.ensure().await.unwrap();
    let store = InstanceStore::new(paths.clone());
    let instance = store.create(NewInstance { name: "Absturz".into(), game_version: "1.21.11".into(), loader: Loader::vanilla() }).await.unwrap();
    let mods = paths.instance_game_dir(&instance.id).join("mods");
    std::fs::create_dir_all(&mods).unwrap();
    write_jar(
        &mods.join("trsclient.jar"),
        r#"{"schemaVersion":1,"id":"trsclient","version":"0.9.0","name":"TRS Client"}"#,
        &["dev/theredstonee/trsclient/core/Main.class", "com/google/gson/Gson.class"],
        &["trsclient.mixins.json"],
    );
    write_jar(
        &mods.join("Essential-fabric.jar"),
        r#"{"schemaVersion":1,"id":"essential","version":"1.3.10.4","name":"Essential"}"#,
        &["gg/essential/loader/Loader.class"],
        &[],
    );
    // Crash-Report nach dem Start.
    let reports = paths.instance_game_dir(&instance.id).join("crash-reports");
    std::fs::create_dir_all(&reports).unwrap();
    std::fs::write(reports.join("crash-2026-09-26_21.58.44-client.txt"), fixture("01")).unwrap();

    let events = Arc::new(Mutex::new(Vec::new()));
    let sink_events = events.clone();
    let sink: crate::process::EventSink = Arc::new(move |e: GameEvent| sink_events.lock().unwrap().push(e));
    let ctx = CrashContext {
        crash_id: "20260926-215844-00ab".into(),
        lines: vec![LogLine { time: 0, level: Level::Error, thread: None, logger: None, message: "Setting user: Steve".into() }],
        started_at: chrono::Utc::now() - chrono::Duration::minutes(2),
        exit_code: Some(-1),
        play_seconds: 120,
    };
    assert!(analyze_after_exit(&paths, sink, &instance.id, ctx));
    let mut crash = None;
    for _ in 0..100 {
        if let Some(GameEvent::CrashAnalyzed { crash: c, .. }) = events.lock().unwrap().first() {
            crash = Some(c.clone());
        }
        if crash.is_some() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    let crash = crash.expect("crashAnalyzed kommt");
    assert_eq!(crash.analysis.primary().variant.as_deref(), Some("trsclient_essential"));
    assert!(crash.analysis.primary().actions.contains(&CrashAction::DisableMods { files: vec!["Essential-fabric.jar".into()] }));
    assert_eq!(crash.sources, ["crash-reports/crash-2026-09-26_21.58.44-client.txt", "live"]);
    // Pakete aus dem Jar gelesen (Gson als Bibliothek nicht).
    let installed = store::installed_mods(&paths, &instance.id).await;
    let trs = installed.iter().find(|m| m.id.as_deref() == Some("trsclient")).unwrap();
    assert_eq!(trs.packages, ["dev.theredstonee.trsclient"]);
    assert_eq!(trs.mixin_configs, ["trsclient.mixins.json"]);

    // Gespeichert, im Verlauf mit Verweis, wieder abrufbar.
    let saved: CrashAnalysis =
        crate::fsutil::read_json(&paths.instance_dir(&instance.id).join("crashes").join("20260926-215844-00ab.json")).await.unwrap().unwrap();
    assert_eq!(saved, *crash);
    let history = crate::history::list(&paths, &instance.id).await.unwrap();
    assert_eq!(history[0].detail.as_deref(), Some("known_issue"));
    assert_eq!(history[0].crash.as_deref(), Some("20260926-215844-00ab"));
    assert_eq!(history[0].seconds, Some(120));
}

#[test]
fn crash_ids() {
    let id = CrashContext::new_id(chrono::Utc::now());
    assert!(validate_crash_id(&id).is_ok(), "{id}");
    for bad in ["", "../x", "A", "x/y", &"1".repeat(41)] {
        assert!(validate_crash_id(bad).is_err(), "{bad}");
    }
}
