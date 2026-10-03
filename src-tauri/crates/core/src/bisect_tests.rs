use std::io::Write;

use super::*;
use crate::modcompat::meta::{Constraint, Scheme};

fn info(id: &str, depends: &[&str]) -> ModInfo {
    ModInfo {
        id: id.into(),
        name: String::new(),
        version: "1.0.0".into(),
        scheme: Scheme::Fabric,
        provides: Vec::new(),
        depends: depends.iter().map(|d| Constraint { id: (*d).into(), any_of: vec!["*".into()] }).collect(),
        breaks: Vec::new(),
    }
}

fn jar(file: &str, id: &str, depends: &[&str]) -> JarInfo {
    JarInfo { file: file.into(), mods: vec![info(id, depends)], nested: Vec::new() }
}

fn all_on(jars: &[JarInfo]) -> BTreeMap<String, bool> {
    jars.iter().map(|j| (j.file.clone(), true)).collect()
}

fn new_state(jars: &[JarInfo]) -> BisectState {
    BisectState::new("test", all_on(jars), jars, Utc::now()).unwrap()
}

/// Jede Runde muss ladbar sein: Was an ist, hat alle Abhängigkeiten an;
/// Geschützte sind immer an, ursprünglich Deaktivierte immer aus.
fn assert_loadable(state: &BisectState) {
    let desired = state.desired();
    for node in &state.nodes {
        if desired[&node.file] {
            for r in &node.requires {
                assert!(desired[r], "{} ist an, braucht aber {r}", node.file);
            }
        }
    }
    for p in &state.protected {
        assert!(desired[p], "{p} ist geschützt");
    }
    for (file, on) in &state.original {
        if !on {
            assert!(!desired[file], "{file} war aus und muss aus bleiben");
        }
    }
    for f in &state.excluded {
        assert!(!desired[f], "{f} ist ausgeschlossen");
    }
}

/// Spielt den Nutzer: Der Fehler tritt auf, wenn alle `culprits` an sind.
fn run(state: &mut BisectState, culprits: &[&str]) -> u32 {
    let mut rounds = 0;
    while state.outcome.is_none() {
        assert_loadable(state);
        let desired = state.desired();
        let failed = culprits.iter().all(|c| desired.get(*c) == Some(&true));
        state.record(state.round(), failed).unwrap();
        rounds += 1;
        assert!(rounds < 60, "Suche endet nicht");
    }
    rounds
}

fn found(state: &BisectState) -> Vec<String> {
    match &state.outcome {
        Some(Outcome::Found { files }) => files.clone(),
        other => panic!("kein Fund: {other:?}"),
    }
}

#[test]
fn graph_uses_ids_provides_and_nested_jars() {
    let mut cloth = jar("cloth.jar", "cloth-config", &["fabricloader", "minecraft"]);
    cloth.nested = vec!["cloth-basic-math".into()];
    cloth.mods[0].provides = vec!["cloth-config2".into()];
    let jars = [
        cloth,
        jar("a.jar", "a", &["cloth-config2", "java", "fabric-api"]),
        jar("b.jar", "b", &["cloth-basic-math", "fehlt-ganz"]),
        // Liefert die Abhängigkeit selbst mit.
        JarInfo { file: "c.jar".into(), mods: vec![info("c", &["c-lib"]), info("c-lib", &[])], nested: Vec::new() },
        jar("fapi.jar", "fabric-api", &[]),
    ];
    let nodes = build_nodes(&jars);
    let requires = |f: &str| nodes.iter().find(|n| n.file == f).unwrap().requires.clone();
    assert!(requires("cloth.jar").is_empty(), "Loader und Minecraft liefert keine Datei");
    assert_eq!(requires("a.jar"), ["cloth.jar", "fapi.jar"]);
    assert_eq!(requires("b.jar"), ["cloth.jar"], "eingebettete Jars zählen, Fehlendes nicht");
    assert!(requires("c.jar").is_empty());
}

#[test]
fn trs_client_and_loader_libraries_are_never_suspects() {
    let jars = [
        jar("trsclient.jar", "trsclient", &["fabric-api"]),
        jar("fabric-api.jar", "fabric-api", &["indium"]),
        jar("indium.jar", "indium", &[]),
        jar("kotlin.jar", "fabric-language-kotlin", &[]),
        jar("a.jar", "a", &["fabric-api"]),
        jar("b.jar", "b", &[]),
    ];
    let state = new_state(&jars);
    assert_eq!(
        state.protected.iter().map(String::as_str).collect::<Vec<_>>(),
        ["fabric-api.jar", "indium.jar", "kotlin.jar", "trsclient.jar"]
    );
    assert_eq!(state.suspects.iter().map(String::as_str).collect::<Vec<_>>(), ["a.jar", "b.jar"]);

    // Nur eine Mod außer den Geschützten: nichts zu suchen.
    let few = [jar("trsclient.jar", "trsclient", &[]), jar("a.jar", "a", &[])];
    assert!(BisectState::new("test", all_on(&few), &few, Utc::now()).is_err());
    // Ursprünglich deaktivierte Mods sind nicht dabei.
    let mut original = all_on(&jars[4..]);
    original.insert("b.jar".into(), false);
    assert!(BisectState::new("test", original, &jars[4..], Utc::now()).is_err());
}

#[test]
fn finds_every_single_culprit_in_log2_rounds() {
    for n in [2usize, 3, 5, 8, 13, 32, 100] {
        let jars: Vec<JarInfo> = (0..n).map(|i| jar(&format!("m{i:03}.jar"), &format!("m{i}"), &[])).collect();
        for culprit in &jars {
            let mut state = new_state(&jars);
            let rounds = run(&mut state, &[&culprit.file]);
            assert_eq!(found(&state), std::slice::from_ref(&culprit.file));
            // Plus höchstens eine Bestätigungsrunde für die letzte Verdächtige.
            assert!(rounds <= ceil_log2(n) + 1, "{n} Mods: {rounds} Runden");
        }
    }
}

/// Bibliotheken, Ketten, ein Kreis und Einzelgänger.
fn mixed() -> Vec<JarInfo> {
    vec![
        jar("trsclient.jar", "trsclient", &["fabric-api"]),
        jar("fabric-api.jar", "fabric-api", &[]),
        jar("lib-a.jar", "lib-a", &[]),
        jar("m1.jar", "m1", &["lib-a"]),
        jar("m2.jar", "m2", &["lib-a"]),
        jar("m3.jar", "m3", &["lib-a", "fabric-api"]),
        jar("lib-b.jar", "lib-b", &[]),
        jar("m4.jar", "m4", &["lib-b"]),
        jar("m5.jar", "m5", &["m4"]),
        jar("c1.jar", "c1", &["c2"]),
        jar("c2.jar", "c2", &["c1"]),
        jar("s1.jar", "s1", &[]),
        jar("s2.jar", "s2", &[]),
        jar("s3.jar", "s3", &[]),
        jar("s4.jar", "s4", &[]),
    ]
}

#[test]
fn keeps_dependency_chains_together_and_finds_libraries() {
    let jars = mixed();
    let state = new_state(&jars);
    // Die erste Runde teilt etwa in der Hälfte.
    assert!((5..=7).contains(&state.testing.len()), "{:?}", state.testing);
    for culprit in jars.iter().map(|j| j.file.as_str()).filter(|f| !state.protected.contains(*f)) {
        let mut state = new_state(&jars);
        run(&mut state, &[culprit]);
        let result = found(&state);
        if culprit.starts_with('c') {
            // Gegenseitig abhängig: nur zusammen prüfbar – kleinste Gruppe.
            assert_eq!(result, ["c1.jar", "c2.jar"]);
        } else {
            assert_eq!(result, [culprit.to_owned()], "Schuldig: {culprit}");
        }
    }
}

#[test]
fn interaction_of_two_mods_points_at_one_of_them() {
    let jars = mixed();
    for pair in [["s1.jar", "m5.jar"], ["m1.jar", "m2.jar"], ["s3.jar", "s4.jar"]] {
        let mut state = new_state(&jars);
        run(&mut state, &pair);
        let result = found(&state);
        assert_eq!(result.len(), 1);
        assert!(pair.contains(&result[0].as_str()), "{pair:?} → {result:?}");
    }
}

#[test]
fn reports_not_found_when_the_error_never_returns() {
    let jars = mixed();
    let mut state = new_state(&jars);
    run(&mut state, &["gibt-es-nicht.jar"]);
    assert_eq!(state.outcome, Some(Outcome::NotFound));
    // Am Ende gilt wieder das Original.
    assert_eq!(state.desired(), state.original);
}

#[test]
fn stale_or_finished_rounds_are_rejected() {
    let jars = mixed();
    let mut state = new_state(&jars);
    assert!(state.record(5, true).is_err());
    state.record(1, true).unwrap();
    assert!(state.record(1, false).is_err(), "Runde 1 schon beantwortet");
    run(&mut state, &["s1.jar"]);
    assert!(state.record(state.round(), true).is_err());
}

#[test]
fn keep_searching_excludes_the_find_and_its_dependents() {
    let jars = mixed();
    let mut state = new_state(&jars);
    run(&mut state, &["lib-b.jar"]);
    assert_eq!(found(&state), ["lib-b.jar"]);
    state.continue_search().unwrap();
    assert_eq!(state.excluded.iter().map(String::as_str).collect::<Vec<_>>(), ["lib-b.jar", "m4.jar", "m5.jar"]);
    assert!(state.outcome.is_none());
    run(&mut state, &["s2.jar"]);
    assert_eq!(found(&state), ["s2.jar"]);
    // Nicht nach einem „nicht gefunden“.
    let mut nothing = new_state(&jars);
    run(&mut nothing, &["gibt-es-nicht.jar"]);
    assert!(nothing.continue_search().is_err());
}

#[test]
fn estimates_rounds() {
    assert_eq!((ceil_log2(1), ceil_log2(2), ceil_log2(3), ceil_log2(8), ceil_log2(9)), (0, 1, 2, 3, 4));
    let jars: Vec<JarInfo> = (0..16).map(|i| jar(&format!("m{i:02}.jar"), &format!("m{i}"), &[])).collect();
    let mut state = new_state(&jars);
    assert_eq!((state.round(), state.estimated_rounds()), (1, 4));
    state.record(1, false).unwrap();
    assert_eq!((state.round(), state.estimated_rounds()), (2, 4));
}

// --- Mit echten Dateien -------------------------------------------------------------

fn write_jar(dir: &std::path::Path, name: &str, fabric: &str) {
    let mut zip = zip::ZipWriter::new(std::fs::File::create(dir.join(name)).unwrap());
    zip.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
    zip.write_all(fabric.as_bytes()).unwrap();
    zip.finish().unwrap();
}

/// Datei → aktiviert, wie es gerade auf der Platte liegt.
fn on_disk(mods: &std::path::Path) -> BTreeMap<String, bool> {
    std::fs::read_dir(mods)
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .map(|n| match n.strip_suffix(".disabled") {
            Some(base) => (base.to_owned(), false),
            None => (n, true),
        })
        .collect()
}

async fn setup() -> (tempfile::TempDir, Paths, std::path::PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    paths.ensure().await.unwrap();
    let mods = content::content_dir(&paths, "test", ContentKind::Mod);
    std::fs::create_dir_all(&mods).unwrap();
    write_jar(&mods, "trsclient.jar", r#"{"version":"1.0.0","id":"trsclient","depends":{"fabric-api":"*"}}"#);
    write_jar(&mods, "fabric-api.jar", r#"{"version":"1.0.0","id":"fabric-api"}"#);
    write_jar(&mods, "lib.jar", r#"{"version":"1.0.0","id":"lib"}"#);
    write_jar(&mods, "a.jar", r#"{"version":"1.0.0","id":"a","depends":{"lib":"*","minecraft":"*"}}"#);
    write_jar(&mods, "b.jar", r#"{"version":"1.0.0","id":"b"}"#);
    write_jar(&mods, "c.jar", r#"{"version":"1.0.0","id":"c"}"#);
    write_jar(&mods, "d.jar", r#"{"version":"1.0.0","id":"d"}"#);
    write_jar(&mods, "alt.jar.disabled", r#"{"version":"1.0.0","id":"alt"}"#);
    (dir, paths, mods)
}

#[tokio::test]
async fn start_answer_and_restore_exactly() {
    let (_dir, paths, mods) = setup().await;
    let before = on_disk(&mods);
    let state = start(&paths, "test").await.unwrap();
    assert!(state_path(&paths, "test").is_file(), "Zustand liegt auf der Platte");
    let now = on_disk(&mods);
    assert_eq!(now, state.desired());
    assert!(now["trsclient.jar"] && now["fabric-api.jar"] && !now["alt.jar"]);
    assert!(now.values().filter(|on| !**on).count() > 1, "etwa die Hälfte ist aus");
    if now["a.jar"] {
        assert!(now["lib.jar"], "a braucht lib");
    }
    assert!(start(&paths, "test").await.is_err(), "nur eine Suche je Instanz");

    // Runde beantworten; falsche Runde wird abgelehnt.
    assert!(answer(&paths, "test", 7, true).await.is_err());
    let state = answer(&paths, "test", 1, true).await.unwrap();
    assert_eq!(on_disk(&mods), state.desired());

    // Launcher stürzt ab: Beim nächsten Start ist der Zustand noch da und
    // lässt sich exakt wiederherstellen.
    let reloaded = load(&paths, "test").await.unwrap().unwrap();
    assert_eq!(reloaded, state);
    finish(&paths, "test", false).await.unwrap();
    assert_eq!(on_disk(&mods), before);
    assert!(!state_path(&paths, "test").exists());
    // Zweimal beenden schadet nicht.
    finish(&paths, "test", false).await.unwrap();
    assert!(answer(&paths, "test", 1, true).await.is_err());
}

#[tokio::test]
async fn result_can_be_disabled_and_vanished_files_are_skipped() {
    let (_dir, paths, mods) = setup().await;
    let mut before = on_disk(&mods);
    let mut state = start(&paths, "test").await.unwrap();
    // Während der Suche löscht der Nutzer eine Datei.
    let gone = if mods.join("d.jar").exists() { mods.join("d.jar") } else { mods.join("d.jar.disabled") };
    std::fs::remove_file(gone).unwrap();
    before.remove("d.jar");
    while state.outcome.is_none() {
        let failed = state.desired()["b.jar"];
        state = answer(&paths, "test", state.round(), failed).await.unwrap();
    }
    assert_eq!(found(&state), ["b.jar"]);
    // Gefunden: Mods sind schon wieder wie vorher – bis auf Wunsch die Schuldige aus.
    assert_eq!(on_disk(&mods), before);
    finish(&paths, "test", true).await.unwrap();
    let after = on_disk(&mods);
    assert!(!after["b.jar"]);
    before.insert("b.jar".into(), false);
    assert_eq!(after, before);
}

#[tokio::test]
async fn unreadable_state_is_ignored() {
    let (_dir, paths, _mods) = setup().await;
    std::fs::write(state_path(&paths, "test"), b"{kaputt").unwrap();
    assert!(load(&paths, "test").await.unwrap().is_none());
    assert!(load(&paths, "../x").await.is_err());
}
