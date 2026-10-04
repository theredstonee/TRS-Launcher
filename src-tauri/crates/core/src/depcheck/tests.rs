use std::collections::HashMap;
use std::future::Future;

use super::*;
use crate::modcompat::meta::parse_fabric;
use crate::modrinth::Version;

const MORE_CULLING: &str = "51shyZVL";
const CLOTH: &str = "9s6osm5g";
const FABRIC_API: &str = "P7dR8mSH";

/// Attrappe: Versionen je Projekt (auch per Slug) und Jar-Angaben je Version.
#[derive(Default)]
struct Fake {
    versions: HashMap<String, Vec<Version>>,
    infos: HashMap<String, Vec<ModInfo>>,
}

impl Fake {
    /// `key`: Projekt-ID oder Slug, unter dem Modrinth die Version findet.
    fn with(mut self, key: &str, v: Version, fabric_mod_json: Option<&str>) -> Self {
        if let Some(json) = fabric_mod_json {
            self.infos.insert(v.id.clone(), vec![parse_fabric(json).unwrap()]);
        }
        self.versions.entry(key.into()).or_default().push(v);
        self
    }
}

impl VersionLookup for Fake {
    fn versions(&self, project_id: &str, _kind: ContentKind) -> impl Future<Output = Result<Vec<Version>>> + Send {
        std::future::ready(Ok(self.versions.get(project_id).cloned().unwrap_or_default()))
    }
    fn version(&self, version_id: &str) -> impl Future<Output = Result<Option<Version>>> + Send {
        std::future::ready(Ok(self.versions.values().flatten().find(|v| v.id == version_id).cloned()))
    }
    fn title(&self, project_id: &str) -> impl Future<Output = Option<String>> + Send {
        std::future::ready(Some(format!("Titel {project_id}")))
    }
    fn mod_info(&self, version: &Version) -> impl Future<Output = Vec<ModInfo>> + Send {
        std::future::ready(self.infos.get(&version.id).cloned().unwrap_or_default())
    }
}

fn version(id: &str, project: &str, deps: &[(&str, &str)]) -> Version {
    serde_json::from_value(serde_json::json!({
        "id": id, "project_id": project, "version_number": format!("{id}-nr"), "version_type": "release",
        "game_versions": ["1.21.11"], "loaders": ["fabric"],
        "files": [{"url": format!("https://cdn.modrinth.com/{id}.jar"), "filename": format!("{id}.jar"),
                   "primary": true, "size": 1, "hashes": {"sha1": "a"}}],
        "dependencies": deps.iter().map(|(t, p)| serde_json::json!({"project_id": p, "dependency_type": t})).collect::<Vec<_>>(),
    }))
    .unwrap()
}

const MORE_CULLING_JAR: &str = r#"{"id":"moreculling","name":"More Culling","version":"1.6.2",
    "depends":{"fabricloader":">=0.15.0","minecraft":">=1.21","java":">=21","cloth-config":">=16.0.0"}}"#;
const CLOTH_JAR: &str = r#"{"id":"cloth-config","name":"Cloth Config v20","version":"21.11.153","provides":["cloth-config2"]}"#;

/// More Culling 1.6.2 auf Modrinth: Pflicht-Abhängigkeit Cloth Config (auch per Slug erreichbar).
fn modrinth_like() -> Fake {
    Fake::default()
        .with(MORE_CULLING, version("mc162", MORE_CULLING, &[("required", CLOTH)]), Some(MORE_CULLING_JAR))
        .with(CLOTH, version("cc153", CLOTH, &[]), Some(CLOTH_JAR))
        .with("cloth-config", version("cc153", CLOTH, &[]), Some(CLOTH_JAR))
}

fn more_culling_entry(from_modrinth: bool) -> Entry {
    Entry {
        project_id: from_modrinth.then(|| MORE_CULLING.to_owned()),
        version_id: from_modrinth.then(|| "mc162".to_owned()),
        mods: vec![parse_fabric(MORE_CULLING_JAR).unwrap()],
        file_name: Some("moreculling-fabric-1.21.11-1.6.2.jar".into()),
        ..Default::default()
    }
}

async fn find(fake: &Fake, entries: &[Entry], enabled: &[&str], disabled: &[DisabledMod]) -> (Vec<Found>, Vec<MissingDep>) {
    let missing = modcompat::missing_dependencies(entries, &[]);
    let enabled: HashSet<String> = enabled.iter().map(|e| (*e).to_owned()).collect();
    find_providers(fake, UpdateChannel::Release, entries, &missing, &enabled, disabled).await.unwrap()
}

#[tokio::test]
async fn cloth_config_is_found_through_more_cullings_modrinth_dependencies() {
    let fake = modrinth_like();
    let (found, open) = find(&fake, &[more_culling_entry(true)], &[MORE_CULLING], &[]).await;
    assert!(open.is_empty());
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].missing.id, "cloth-config");
    assert_eq!(found[0].missing.declarer_label, "More Culling 1.6.2");
    assert_eq!(found[0].provider, Provider::Install { project_id: CLOTH.into(), title: format!("Titel {CLOTH}") });
}

#[tokio::test]
async fn a_hand_added_jar_finds_its_dependency_by_slug() {
    // Ohne Modrinth-Herkunft: `cloth-config` als Slug, das Jar bestätigt die Mod-ID.
    let fake = Fake::default().with("cloth-config", version("cc153", CLOTH, &[]), Some(CLOTH_JAR));
    let (found, open) = find(&fake, &[more_culling_entry(false)], &[], &[]).await;
    assert!(open.is_empty());
    assert_eq!(found[0].provider, Provider::Install { project_id: CLOTH.into(), title: format!("Titel {CLOTH}") });

    // Ein gleichnamiges Projekt, dessen Jar etwas anderes ist, wird nicht genommen.
    let fake = Fake::default().with("cloth-config", version("x1", "someother", &[]), Some(r#"{"id":"other","version":"1"}"#));
    let (found, open) = find(&fake, &[more_culling_entry(false)], &[], &[]).await;
    assert!(found.is_empty());
    assert_eq!(open[0].id, "cloth-config");
}

#[tokio::test]
async fn a_disabled_dependency_is_switched_back_on() {
    let fake = modrinth_like();
    let disabled = [DisabledMod {
        file_name: "cloth-config-21.11.153-fabric.jar".into(),
        project_id: Some(CLOTH.into()),
        mods: vec![parse_fabric(CLOTH_JAR).unwrap()],
        nested: Vec::new(),
    }];
    let (found, _) = find(&fake, &[more_culling_entry(true)], &[MORE_CULLING], &disabled).await;
    assert_eq!(found[0].provider, Provider::Enable { file_name: "cloth-config-21.11.153-fabric.jar".into() });
}

#[tokio::test]
async fn installed_libraries_are_not_loaded_again() {
    // Dynamic FPS verlangt ein Fabric-API-Modul; die Fabric API ist da (Modul nicht gelesen):
    // nichts installieren – und kein Projekt mit dem Modul-Namen gibt es auf Modrinth.
    let fake = Fake::default().with("LQ3K71Q1", version("dyn1", "LQ3K71Q1", &[("required", FABRIC_API)]), None);
    let dynamic_fps = Entry {
        project_id: Some("LQ3K71Q1".into()),
        version_id: Some("dyn1".into()),
        mods: vec![parse_fabric(r#"{"id":"dynamic_fps","version":"3.11.6","depends":{"fabric-lifecycle-events-v1":"*"}}"#).unwrap()],
        ..Default::default()
    };
    let (found, open) = find(&fake, std::slice::from_ref(&dynamic_fps), &["LQ3K71Q1", FABRIC_API], &[]).await;
    assert!(found.is_empty());
    assert_eq!(open.len(), 1);

    // Fehlt die Fabric API wirklich, holt sie die Modrinth-Pflichtangabe der Mod.
    let fake = fake.with(FABRIC_API, version("fapi1", FABRIC_API, &[]), Some(r#"{"id":"fabric-api","version":"0.141.6"}"#));
    let (found, _) = find(&fake, &[dynamic_fps], &["LQ3K71Q1"], &[]).await;
    assert_eq!(found[0].provider, Provider::Install { project_id: FABRIC_API.into(), title: format!("Titel {FABRIC_API}") });
}

#[tokio::test]
async fn one_project_for_several_missing_ids() {
    let fake = modrinth_like();
    let needs_alias = Entry {
        mods: vec![parse_fabric(r#"{"id":"old","name":"Old","version":"1","depends":{"cloth-config2":"*"}}"#).unwrap()],
        ..Default::default()
    };
    let entries = [more_culling_entry(true), needs_alias];
    let missing = modcompat::missing_dependencies(&entries, &[]);
    assert_eq!(missing.len(), 2);
    let enabled = HashSet::from([MORE_CULLING.to_owned()]);
    let (found, open) = find_providers(&fake, UpdateChannel::Release, &entries, &missing, &enabled, &[]).await.unwrap();
    // `cloth-config2` hat kein eigenes Projekt – Cloth Config liefert es mit (`provides`).
    assert!(open.is_empty());
    assert_eq!(found.len(), 2);
    assert!(found.iter().all(|f| f.provider == Provider::Install { project_id: CLOTH.into(), title: format!("Titel {CLOTH}") }));
}

// --- Gegen das echte Modrinth (Fehlerbericht 0.18.0: Iris 1.11.4 + Sodium 0.8.9) ---------
// `cargo test -p trs-core real_modrinth_versions -- --ignored --nocapture --test-threads=1`

const SODIUM: &str = "AANobbMI";
const IRIS: &str = "YL57xq9U";
/// Sodium mc26.1.1-0.8.9 (Modrinth führt es für 26.1–26.1.2).
const SODIUM_089: &str = "uGvVQBnw";
/// Iris 1.11.4 (verlangt Sodium 0.9.x).
const IRIS_1114: &str = "sZbVsl2Q";
/// Iris 1.10.9 (für 26.1.1, nimmt Sodium 0.8.x).
const IRIS_1109: &str = "MwcLS51S";

fn real_http() -> reqwest::Client {
    reqwest::Client::builder().user_agent("theredstonee/trs-launcher (depcheck test)").build().unwrap()
}

fn real_instance(kind: LoaderKind, game_version: &str) -> Instance {
    Instance {
        id: "t".into(),
        name: "T".into(),
        game_version: game_version.into(),
        loader: crate::instance::Loader { kind, version: (kind == LoaderKind::Fabric).then(|| "0.19.5".into()) },
        created_at: chrono::Utc::now(),
        last_played: None,
        total_play_seconds: 0,
        icon: None,
        group: None,
        overrides: Default::default(),
    }
}

/// Lädt genau diese Version (ohne Abhängigkeiten) – wie eine von Hand gewählte Version.
async fn put(http: &reqwest::Client, paths: &Paths, instance: &Instance, version_id: &str) {
    let v = modrinth::version_by_id(http, version_id).await.unwrap();
    modrinth::install_version(http, paths, instance, ContentKind::Mod, &v, None, false).await.unwrap();
}

async fn version_of(paths: &Paths, instance: &Instance, project: &str) -> String {
    content::source_of_project(paths, &instance.id, project).await.and_then(|s| s.version_number).unwrap_or_default()
}

async fn conflicts(paths: &Paths, instance: &Instance) -> Vec<String> {
    let entries = modcompat::installed_entries(paths, &instance.id).await.unwrap();
    modcompat::find_conflicts(&entries, &modcompat::loader_builtins(instance)).iter().map(modcompat::Conflict::describe).collect()
}

#[tokio::test]
#[ignore = "braucht Internet (Modrinth)"]
async fn real_modrinth_versions_iris_needs_newer_sodium() {
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    let http = real_http();
    let inst = real_instance(LoaderKind::Fabric, "26.1.2");
    put(&http, &paths, &inst, SODIUM_089).await;
    put(&http, &paths, &inst, IRIS_1114).await;
    let before = conflicts(&paths, &inst).await;
    println!("vorher: {before:?}");
    assert!(before.iter().any(|c| c.starts_with("Iris 1.11.4") && c.contains("Sodium 0.8.9")), "{before:?}");

    let report = ensure_versions_before_launch(&http, &paths, &[], &inst, &|_| {}).await.unwrap();
    println!("{report:#?}");
    assert!(report.unresolved.is_empty());
    assert!(report.changes.iter().any(|c| c.title == "Sodium" && c.to.starts_with("0.9.")), "{report:?}");
    let sodium = version_of(&paths, &inst, SODIUM).await;
    println!("Sodium jetzt: {sodium}");
    assert!(sodium.contains("0.9."), "{sodium}");
    assert!(version_of(&paths, &inst, IRIS).await.starts_with("1.11.4"));
    assert!(conflicts(&paths, &inst).await.is_empty());
    // Genau eine Sodium-Datei, Herkunft gemerkt; der nächste Start ändert nichts mehr.
    let mods = content::list(&paths, &inst.id, ContentKind::Mod).await.unwrap();
    assert_eq!(mods.iter().filter(|m| m.file_name.starts_with("sodium")).count(), 1, "{mods:#?}");
    assert!(versions_for_launch(&http, &paths, &[], &inst, false, &|_| {}).await.unwrap().is_none());
}

#[tokio::test]
#[ignore = "braucht Internet (Modrinth)"]
async fn real_modrinth_versions_change_26_1_1_to_26_1_2() {
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    let http = real_http();
    let old = real_instance(LoaderKind::Fabric, "26.1.1");
    put(&http, &paths, &old, SODIUM_089).await;
    put(&http, &paths, &old, IRIS_1109).await;
    assert!(conflicts(&paths, &old).await.is_empty(), "26.1.1 passt");

    // Versionswechsel: der Plan (wie im Dialog) und dann der Start-Abgleich.
    let new = real_instance(LoaderKind::Fabric, "26.1.2");
    let plan = modrinth::plan_migration(&http, &paths, &new).await.unwrap();
    for p in &plan {
        println!("{} {:?} {:?} → {:?} ({:?})", p.title, p.status, p.current_version, p.target_version_number, p.compat_with);
    }
    for p in plan.iter().filter(|p| p.status == modrinth::MigrationStatus::Update) {
        modrinth::apply_update(&http, &paths, &new, p.kind, &p.file_name, p.target_version_id.as_deref().unwrap()).await.unwrap();
    }
    let report = ensure_versions_before_launch(&http, &paths, &[], &new, &|_| {}).await.unwrap();
    println!("{report:#?}");
    assert!(report.unresolved.is_empty());
    let sodium = version_of(&paths, &new, SODIUM).await;
    println!("Sodium {sodium}, Iris {}", version_of(&paths, &new, IRIS).await);
    assert!(sodium.contains("0.9."), "{sodium}");
    assert!(conflicts(&paths, &new).await.is_empty());

    // Nur der Start-Abgleich (Spieler hat „Später“ gewählt, dann Iris 1.11.4 dazu).
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    put(&http, &paths, &old, SODIUM_089).await;
    put(&http, &paths, &old, IRIS_1114).await;
    let report = ensure_versions_before_launch(&http, &paths, &[], &new, &|_| {}).await.unwrap();
    println!("{report:#?}");
    assert!(version_of(&paths, &new, SODIUM).await.contains("0.9."));
    assert!(conflicts(&paths, &new).await.is_empty());
}

#[tokio::test]
#[ignore = "braucht Internet (Modrinth), lädt ~20 MB"]
async fn real_modrinth_versions_boost_pack_follows_the_game_version() {
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    let http = real_http();
    let builds = client_mod::load_builds(&std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../resources/client-mod"));
    let vanilla = |v: &str| real_instance(LoaderKind::Vanilla, v);
    let old = crate::boost::effective_instance(&http, &paths, &builds, &vanilla("26.1.1")).await;
    assert_eq!(old.loader.kind, LoaderKind::Fabric);
    crate::boost::ensure_performance(&http, &paths, &builds, &old, &|_| {}).await.unwrap();
    let before = version_of(&paths, &old, SODIUM).await;
    println!("26.1.1: Sodium {before}");

    let new = crate::boost::effective_instance(&http, &paths, &builds, &vanilla("26.1.2")).await;
    assert!(crate::boost::needs_performance(&paths, &new).await);
    crate::boost::ensure_performance(&http, &paths, &builds, &new, &|_| {}).await.unwrap();
    let after = version_of(&paths, &new, SODIUM).await;
    println!("26.1.2: Sodium {after}");
    if !before.is_empty() {
        assert!(after.contains("0.9."), "Paket-Sodium für 26.1.2: {after}");
    }
    assert!(conflicts(&paths, &new).await.is_empty(), "{:?}", conflicts(&paths, &new).await);
}

// --- Konflikt-Helfer -----------------------------------------------------------------------

const BETTER_ADV_JAR: &str = r#"{"id":"betteradvancements","name":"Better Advancements","version":"0.4.8.54",
    "depends":{"minecraft":"1.21.x"}}"#;
const SODIUM_JAR: &str = r#"{"id":"sodium","name":"Sodium","version":"0.8.14","breaks":{"iris":"<=1.10.7"}}"#;
const IRIS_JAR: &str = r#"{"id":"iris","name":"Iris","version":"1.10.7"}"#;

/// Legt Jars (ohne Modrinth-Herkunft) in den Mods-Ordner der Instanz `t`.
fn put_jars(paths: &Paths, jars: &[(&str, &str)]) {
    let dir = content::content_dir(paths, "t", ContentKind::Mod);
    std::fs::create_dir_all(&dir).unwrap();
    for (file, json) in jars {
        std::fs::write(dir.join(file), crate::modcompat::meta::tests::jar(&[(crate::modcompat::meta::FABRIC, json)])).unwrap();
    }
}

#[tokio::test]
async fn conflict_report_describes_both_sides() {
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    let inst = real_instance(LoaderKind::Fabric, "26.1");
    put_jars(&paths, &[("betteradv.jar", BETTER_ADV_JAR), ("sodium.jar", SODIUM_JAR), ("iris.jar", IRIS_JAR)]);
    let report = conflict_report(&paths, &[], &inst).await.unwrap();
    assert!(report.blocks_launch);
    assert_eq!(report.conflicts.len(), 2, "{report:#?}");

    let game = report.conflicts.iter().find(|c| c.declarer.mod_id == "betteradvancements").unwrap();
    assert_eq!(game.kind, "depends");
    assert_eq!(game.declarer.name, "Better Advancements");
    assert_eq!(game.declarer.version, "0.4.8.54");
    assert_eq!(game.declarer.file_name.as_deref(), Some("betteradv.jar"));
    assert!(game.declarer.enabled);
    assert!(!game.declarer.adjustable);
    assert_eq!(game.other.kind, "game");
    assert_eq!(game.other.version, "26.1");
    assert_eq!(game.other.file_name, None);
    assert_eq!(game.ranges, vec!["1.21.x".to_owned()]);
    assert_eq!(game.text, "Better Advancements 0.4.8.54 ↔ Minecraft 26.1");

    let mods = report.conflicts.iter().find(|c| c.declarer.mod_id == "sodium").unwrap();
    assert_eq!(mods.kind, "breaks");
    assert_eq!(mods.other.kind, "mod");
    assert_eq!(mods.other.name, "Iris");
    assert_eq!(mods.other.file_name.as_deref(), Some("iris.jar"));
    assert_eq!(mods.ranges, vec!["<=1.10.7".to_owned()]);

    // Als JSON für das Frontend.
    let json = serde_json::to_value(&report).unwrap();
    assert_eq!(json["blocksLaunch"], true);
    assert!(json["conflicts"][0]["declarer"]["fileName"].is_string());
}

#[tokio::test]
async fn conflict_report_is_empty_when_everything_fits() {
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    let inst = real_instance(LoaderKind::Fabric, "1.21.11");
    put_jars(&paths, &[("betteradv.jar", BETTER_ADV_JAR), ("iris.jar", IRIS_JAR)]);
    assert!(conflict_report(&paths, &[], &inst).await.unwrap().conflicts.is_empty());
    // Forge stoppt den Start dabei nicht.
    let forge = real_instance(LoaderKind::Forge, "1.21.11");
    assert!(!conflict_report(&paths, &[], &forge).await.unwrap().blocks_launch);
}

#[tokio::test]
async fn launch_stops_on_conflict_unless_bypassed() {
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    let http = reqwest::Client::new();
    let inst = real_instance(LoaderKind::Fabric, "26.1");
    // Ohne Modrinth-Herkunft lässt sich nichts tauschen (kein Netz nötig).
    put_jars(&paths, &[("betteradv.jar", BETTER_ADV_JAR)]);
    match versions_for_launch(&http, &paths, &[], &inst, false, &|_| {}).await {
        Err(Error::Launch(m)) => {
            assert_eq!(m.code, "launcher.modVersionsConflict");
            assert!(m.text.contains("Better Advancements 0.4.8.54 ↔ Minecraft 26.1"), "{}", m.text);
        }
        other => panic!("Start hätte gestoppt werden müssen: {other:?}"),
    }
    // „Trotzdem starten“: einmal ohne Prüfung.
    assert!(versions_for_launch(&http, &paths, &[], &inst, true, &|_| {}).await.unwrap().is_none());
    // Die Dateien bleiben unangetastet.
    assert_eq!(conflict_report(&paths, &[], &inst).await.unwrap().conflicts.len(), 1);
}

const DYN_JAR: &str = r#"{"id":"dynamiccrosshair","name":"Dynamic Crosshair","version":"9.12","depends":{"minecraft":">=1.21"}}"#;
const DYN_NEW_JAR: &str = r#"{"id":"dynamiccrosshair","name":"Dynamic Crosshair","version":"10.0","depends":{"minecraft":">=26.1"}}"#;

#[tokio::test]
async fn mod_built_for_older_minecraft_is_a_conflict_on_26_1() {
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    let mods = content::content_dir(&paths, "t", ContentKind::Mod);
    std::fs::create_dir_all(&mods).unwrap();
    // Erlaubt „>=1.21“, verweist aber auf Intermediary-Namen (für 1.21.x gebaut).
    let old = crate::modcompat::meta::tests::jar(&[
        (crate::modcompat::meta::FABRIC, DYN_JAR),
        ("mod/crend/dynamiccrosshair/DynamicCrosshairMod.class", "Lnet/minecraft/class_2769;"),
    ]);
    std::fs::write(mods.join("dynamiccrosshair-9.12.jar"), old).unwrap();
    let new = crate::modcompat::meta::tests::jar(&[(crate::modcompat::meta::FABRIC, DYN_NEW_JAR), ("a/B.class", "Lnet/minecraft/world/Foo;")]);
    std::fs::write(mods.join("other.jar"), new).unwrap();

    let report = conflict_report(&paths, &[], &real_instance(LoaderKind::Fabric, "26.1")).await.unwrap();
    assert_eq!(report.conflicts.len(), 1, "{report:#?}");
    let c = &report.conflicts[0];
    assert_eq!(c.kind, "oldBuild");
    assert_eq!(c.declarer.name, "Dynamic Crosshair");
    assert_eq!(c.declarer.file_name.as_deref(), Some("dynamiccrosshair-9.12.jar"));
    assert_eq!((c.other.kind, c.other.version.as_str()), ("game", "26.1"));
    // Der Start stoppt (nichts zu tauschen) – „Trotzdem starten“ geht.
    let http = reqwest::Client::new();
    assert!(versions_for_launch(&http, &paths, &[], &real_instance(LoaderKind::Fabric, "26.1"), false, &|_| {}).await.is_err());
    assert!(versions_for_launch(&http, &paths, &[], &real_instance(LoaderKind::Fabric, "26.1"), true, &|_| {}).await.unwrap().is_none());
    // Unter 1.21.11 ist das richtig so (dort passt nur die andere Mod nicht).
    let older = conflict_report(&paths, &[], &real_instance(LoaderKind::Fabric, "1.21.11")).await.unwrap();
    assert!(older.conflicts.iter().all(|c| c.kind != "oldBuild" && c.declarer.file_name.as_deref() == Some("other.jar")), "{older:#?}");
}

fn dyn_entry() -> Entry {
    let mods = vec![parse_fabric(DYN_JAR).unwrap()];
    Entry {
        project_id: Some("DYNproj".into()),
        version_id: Some("d1".into()),
        installed: Some(("d1".into(), mods.clone())),
        mods,
        adjustable: true,
        file_name: Some("dyn.jar".into()),
        old_build: true,
        installed_old_build: true,
        ..Default::default()
    }
}

#[tokio::test]
async fn mod_built_for_older_minecraft_is_swapped_for_a_newer_version() {
    let inst = real_instance(LoaderKind::Fabric, "26.1");
    let builtins = modcompat::loader_builtins(&inst);
    let fake = Fake::default()
        .with("DYNproj", version("d2", "DYNproj", &[]), Some(DYN_NEW_JAR))
        .with("DYNproj", version("d1", "DYNproj", &[]), Some(DYN_JAR));
    let mut entries = vec![dyn_entry()];
    let left = modcompat::settle(&fake, UpdateChannel::Release, &mut entries, &builtins, None).await.unwrap();
    assert!(left.is_empty(), "{left:?}");
    assert_eq!(entries[0].version_id.as_deref(), Some("d2"));
    assert!(!entries[0].old_build);

    // Nur ältere Versionen: die sind auch für ein älteres Minecraft – nichts tauschen.
    let fake = Fake::default()
        .with("DYNproj", version("d1", "DYNproj", &[]), Some(DYN_JAR))
        .with("DYNproj", version("d0", "DYNproj", &[]), Some(DYN_JAR));
    let mut entries = vec![dyn_entry()];
    let left = modcompat::settle(&fake, UpdateChannel::Release, &mut entries, &builtins, None).await.unwrap();
    assert_eq!(left.len(), 1);
    assert_eq!(entries[0].version_id.as_deref(), Some("d1"));
    // „Passende Version suchen“ findet dann auch nichts.
    let fit = plan_fit(&fake, UpdateChannel::Release, vec![dyn_entry()], &builtins, "dyn.jar").await.unwrap();
    assert!(matches!(fit, Fit::None { .. }), "{fit:?}");
}

fn better_adv_entry(adjustable: bool) -> Entry {
    let mods = vec![parse_fabric(BETTER_ADV_JAR).unwrap()];
    Entry {
        project_id: adjustable.then(|| "BAproj".to_owned()),
        version_id: adjustable.then(|| "ba1".to_owned()),
        installed: adjustable.then(|| ("ba1".to_owned(), mods.clone())),
        mods,
        adjustable,
        file_name: Some("betteradv.jar".into()),
        ..Default::default()
    }
}

#[tokio::test]
async fn fitting_version_takes_one_for_the_game_version() {
    let inst = real_instance(LoaderKind::Fabric, "26.1");
    let builtins = modcompat::loader_builtins(&inst);
    let newer = r#"{"id":"betteradvancements","name":"Better Advancements","version":"0.5.0","depends":{"minecraft":">=26.1"}}"#;
    let fake = Fake::default()
        .with("BAproj", version("ba2", "BAproj", &[]), Some(newer))
        .with("BAproj", version("ba1", "BAproj", &[]), Some(BETTER_ADV_JAR));
    match plan_fit(&fake, UpdateChannel::Release, vec![better_adv_entry(true)], &builtins, "betteradv.jar").await.unwrap() {
        Fit::Found { version, change } => {
            assert_eq!(version.id, "ba2");
            assert_eq!(change.title, "Better Advancements");
            assert_eq!(change.from.as_deref(), Some("0.4.8.54"));
            assert_eq!(change.to, "0.5.0");
        }
        other => panic!("{other:?}"),
    }
}

#[tokio::test]
async fn fitting_version_reports_none_or_unsupported() {
    let inst = real_instance(LoaderKind::Fabric, "26.1");
    let builtins = modcompat::loader_builtins(&inst);
    // Noch keine Version für 26.1.
    let fake = Fake::default().with("BAproj", version("ba1", "BAproj", &[]), Some(BETTER_ADV_JAR));
    let fit = plan_fit(&fake, UpdateChannel::Release, vec![better_adv_entry(true)], &builtins, "betteradv.jar").await.unwrap();
    assert!(matches!(fit, Fit::None { ref title } if title == "Better Advancements"), "{fit:?}");
    // Von Hand hinzugefügt: kein Versionsvergleich möglich.
    let fit = plan_fit(&fake, UpdateChannel::Release, vec![better_adv_entry(false)], &builtins, "betteradv.jar").await.unwrap();
    assert!(matches!(fit, Fit::Unsupported { .. }), "{fit:?}");
    // Unbekannte Datei.
    assert!(plan_fit(&fake, UpdateChannel::Release, vec![better_adv_entry(true)], &builtins, "other.jar").await.is_err());
}
