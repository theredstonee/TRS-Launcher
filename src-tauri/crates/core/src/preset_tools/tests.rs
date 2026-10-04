use std::io::Write as _;

use super::*;
use crate::content::{ContentItem, Source};

const INDEX: &str = include_str!("fixtures/modrinth.index.json");
const CF_MANIFEST: &str = include_str!("fixtures/cf-manifest.json");
const CF_MODS: &str = include_str!("fixtures/cf-mods.json");
const MR_VERSIONS: &str = include_str!("fixtures/modrinth-versions.json");

/// `.mrpack` mit dem Index aus den Fixtures und einer mitgebrachten Mod in `overrides/mods/`.
fn write_mrpack(dir: &std::path::Path) -> std::path::PathBuf {
    let path = dir.join("test.mrpack");
    let mut zip = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
    let options = zip::write::SimpleFileOptions::default();
    zip.start_file("modrinth.index.json", options).unwrap();
    zip.write_all(INDEX.as_bytes()).unwrap();
    zip.start_file("overrides/mods/own-mod-2.0.jar", options).unwrap();
    zip.write_all(b"jar").unwrap();
    zip.start_file("overrides/config/x.json", options).unwrap();
    zip.write_all(b"{}").unwrap();
    zip.finish().unwrap();
    path
}

#[tokio::test]
async fn mrpack_lists_mods_packs_and_shaders_without_installing() {
    let dir = tempfile::tempdir().unwrap();
    let list = read_mrpack(write_mrpack(dir.path())).await.unwrap();
    assert_eq!(list.name, "Test Pack");
    assert_eq!(list.game_version.as_deref(), Some("1.21.1"));
    assert_eq!(list.loader, Some(LoaderKind::Fabric));

    let ids: Vec<(Option<&str>, ContentKind)> = list.items.iter().map(|i| (i.project_id.as_deref(), i.kind)).collect();
    assert_eq!(
        ids,
        [
            (Some("AANobbMI"), ContentKind::Mod),
            (Some("gvQqBUqZ"), ContentKind::Mod),
            (None, ContentKind::Mod),
            (Some("BdYrfSSx"), ContentKind::ResourcePack),
            (Some("HVnmMxH1"), ContentKind::ShaderPack),
            (None, ContentKind::Mod),
        ],
        "Server-Mods, Configs, Unterordner und doppelte Projekte fallen weg"
    );
    let sodium = &list.items[0];
    assert_eq!(sodium.source, Some(PresetSource::Modrinth));
    assert_eq!(sodium.title, "sodium-fabric-0.6.0+mc1.21.1");
    assert_eq!(sodium.client_only, Some(true));
    assert_eq!(list.items[1].client_only, Some(false));
    // Ohne Projekt: Datei bleibt sichtbar, lässt sich aber nicht übernehmen.
    assert_eq!(list.items[2].source, None);
    assert_eq!(list.items[2].file_name.as_deref(), Some("handmade-1.0.jar"));
    assert_eq!(list.items[5].title, "own-mod-2.0");
}

#[tokio::test]
async fn broken_pack_is_an_error() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("broken.mrpack");
    std::fs::write(&path, b"not a zip").unwrap();
    assert!(read_mrpack(path).await.is_err());
}

#[test]
fn curseforge_manifest_takes_titles_kinds_and_categories() {
    let manifest: crate::curseforge::Manifest = serde_json::from_str(CF_MANIFEST).unwrap();
    let mods: Vec<RawMod> = serde_json::from_str(CF_MODS).unwrap();
    let mods: HashMap<u64, RawMod> = mods.into_iter().map(|m| (m.id, m)).collect();
    let ids: Vec<u64> = manifest.files.iter().map(|f| f.project_id).collect();
    let items = picks_of_manifest(&ids, &mods);

    let rows: Vec<(&str, &str, ContentKind)> =
        items.iter().map(|i| (i.project_id.as_deref().unwrap(), i.title.as_str(), i.kind)).collect();
    assert_eq!(
        rows,
        [
            ("238222", "Just Enough Items (JEI)", ContentKind::Mod),
            ("581495", "Oculus", ContentKind::Mod),
            ("999999", "CurseForge #999999", ContentKind::Mod),
            ("457570", "Stay True", ContentKind::ResourcePack),
        ],
        "Modpacks im Pack fallen weg, Unbekanntes bleibt als Mod"
    );
    assert!(items.iter().all(|i| i.source == Some(PresetSource::Curseforge) && i.file_name.is_none()));
    assert_eq!(items[0].categories, ["API and Library"]);
    assert!(items[0].icon_url.as_deref().is_some_and(|u| u.starts_with("https://media.forgecdn.net/")));
    assert!(items[1].performance);
    assert_eq!(items[1].icon_url, None, "fremde Bild-Hosts fallen weg");
    assert!(!items[0].performance);
}

#[test]
fn modrinth_meta_fills_title_icon_categories_and_side() {
    let mut item = PickItem::file("sodium-fabric-0.6.0.jar", ContentKind::Mod);
    item.source = Some(PresetSource::Modrinth);
    item.project_id = Some("AANobbMI".into());
    let meta: ModrinthMeta = serde_json::from_value(serde_json::json!({
        "id": "AANobbMI", "title": "Sodium", "icon_url": "https://cdn.modrinth.com/data/AANobbMI/icon.webp",
        "categories": ["optimization"], "client_side": "required", "server_side": "unsupported"
    }))
    .unwrap();
    apply_modrinth(&mut item, &meta);
    assert_eq!(item.title, "Sodium");
    assert!(item.performance);
    assert_eq!(item.client_only, Some(true));
    assert_eq!(item.icon_url.as_deref(), Some("https://cdn.modrinth.com/data/AANobbMI/icon.webp"));

    // Angabe aus dem Pack-Index geht vor.
    let mut item = PickItem { client_only: Some(false), ..item };
    apply_modrinth(&mut item, &meta);
    assert_eq!(item.client_only, Some(false));
}

fn content(file: &str, source: Option<Source>, icon: Option<&str>) -> ContentItem {
    ContentItem {
        file_name: file.into(),
        kind: ContentKind::Mod,
        enabled: true,
        size: 1,
        title: Some(format!("Titel {file}")),
        version: None,
        description: None,
        source,
        author: None,
        icon_url: icon.map(str::to_owned),
        slug: None,
    }
}

#[test]
fn instance_content_keeps_modrinth_and_curseforge_ids() {
    let cf = Source { project_id: "238222".into(), version_id: "1".into(), version_number: None, platform: Platform::CurseForge };
    let bad = Source { project_id: "../x".into(), version_id: "1".into(), version_number: None, platform: Platform::Modrinth };
    let items = picks_of_content(vec![
        content("a.jar", Some(Source::modrinth("AANobbMI".into(), "v".into(), None)), Some("https://cdn.modrinth.com/data/x.png")),
        content("b.jar", Some(cf), Some("data:image/png;base64,AAAA")),
        content("c.jar", None, None),
        content("d.jar", Some(bad), None),
    ]);
    let rows: Vec<(Option<PresetSource>, Option<&str>)> = items.iter().map(|i| (i.source, i.project_id.as_deref())).collect();
    assert_eq!(
        rows,
        [
            (Some(PresetSource::Modrinth), Some("AANobbMI")),
            (Some(PresetSource::Curseforge), Some("238222")),
            (None, None),
            (None, None),
        ]
    );
    assert_eq!(items[0].title, "Titel a.jar");
    assert!(items[0].icon_url.is_some());
    assert_eq!(items[1].icon_url, None, "eingebettete Bilder passen nicht ins Preset");
}

#[test]
fn dedupe_keeps_files_without_project_and_first_of_each_project() {
    let mut a = PickItem::file("a.jar", ContentKind::Mod);
    a.source = Some(PresetSource::Modrinth);
    a.project_id = Some("AANobbMI".into());
    let b = PickItem { file_name: Some("b.jar".into()), ..a.clone() };
    let loose = PickItem::file("loose.jar", ContentKind::Mod);
    let out = dedupe(vec![a.clone(), b, loose.clone(), loose.clone()]);
    assert_eq!(out, [a, loose.clone(), loose]);
}

#[test]
fn dependencies_per_loader_from_the_newest_versions() {
    let versions: Vec<SlimVersion> = serde_json::from_str(MR_VERSIONS).unwrap();
    let deps = deps_by_loader(&versions);
    assert_eq!(
        deps.required,
        BTreeMap::from([
            ("9s6osm5g".to_owned(), vec!["fabric".to_owned(), "quilt".to_owned(), "forge".to_owned()]),
            ("P7dR8mSH".to_owned(), vec!["fabric".to_owned(), "quilt".to_owned()]),
        ]),
        "nur die neueste Version je Loader, ungültige IDs fallen weg"
    );
    assert_eq!(deps.incompatible, HashSet::from(["OptiFxxx".to_owned()]));

    // Gilt eine Abhängigkeit überall, steht kein Loader dran.
    let one: Vec<SlimVersion> = serde_json::from_value(serde_json::json!([
        { "loaders": ["fabric", "quilt"], "version_type": "release", "dependencies": [{ "project_id": "P7dR8mSH", "dependency_type": "required" }] }
    ]))
    .unwrap();
    assert_eq!(deps_by_loader(&one).required, BTreeMap::from([("P7dR8mSH".to_owned(), Vec::new())]));
    assert_eq!(deps_by_loader(&[]), ProjectDeps::default());
}

fn item(source: PresetSource, id: &str, title: &str) -> PresetItem {
    PresetItem { source, project_id: id.into(), title: title.into(), icon_url: None, kind: ContentKind::Mod }
}

#[test]
fn known_conflicts_by_id_name_and_declaration() {
    let items = [
        item(PresetSource::Modrinth, "AANobbMI", "Sodium"),
        item(PresetSource::Modrinth, "sk9rgfiA", "Embeddium"),
        item(PresetSource::Curseforge, "263420", "Xaero's Minimap"),
        item(PresetSource::Modrinth, "abcdefgh", "JourneyMap"),
        item(PresetSource::Modrinth, "YL57xq9U", "Iris Shaders"),
        item(PresetSource::Modrinth, "SfMw2IZN", "Nvidium"),
        item(PresetSource::Modrinth, "k2ZPuTBm", "Essential"),
        item(PresetSource::Modrinth, "zzzzzzzz", "Some Mod"),
        item(PresetSource::Modrinth, "yyyyyyyy", "Other Mod"),
        // Ressourcenpakete zählen nie.
        PresetItem { kind: ContentKind::ResourcePack, ..item(PresetSource::Modrinth, "rrrrrrrr", "Rubidium") },
    ];
    let refs: Vec<&PresetItem> = items.iter().collect();
    let infos = HashMap::from([(
        (PresetSource::Modrinth, "zzzzzzzz".to_owned()),
        ProjectDeps { incompatible: HashSet::from(["yyyyyyyy".to_owned()]), ..Default::default() },
    )]);
    let all = conflicts(&refs, &infos);
    let found: Vec<(&str, Option<&str>, ConflictReason)> =
        all.iter().map(|c| (c.a.title.as_str(), c.b.as_ref().map(|b| b.title.as_str()), c.reason)).collect();
    assert_eq!(
        found,
        [
            ("Sodium", Some("Embeddium"), ConflictReason::Renderer),
            ("Xaero's Minimap", Some("JourneyMap"), ConflictReason::Minimap),
            ("Iris Shaders", Some("Nvidium"), ConflictReason::Nvidium),
            ("Some Mod", Some("Other Mod"), ConflictReason::Declared),
            ("Essential", None, ConflictReason::TrsClient),
        ]
    );

    // Verschiedene Mods ohne bekannte Beziehung: nichts.
    let calm = [item(PresetSource::Modrinth, "AANobbMI", "Sodium"), item(PresetSource::Modrinth, "gvQqBUqZ", "Lithium")];
    assert!(conflicts(&calm.iter().collect::<Vec<_>>(), &HashMap::new()).is_empty());
}

#[test]
fn file_titles_are_readable() {
    assert_eq!(title_of_file("sodium-0.6.jar"), "sodium-0.6");
    assert_eq!(title_of_file("Faithful 32x.zip"), "Faithful 32x");
    assert_eq!(title_of_file("x.jar.disabled"), "x");
    assert_eq!(title_of_file(".jar"), ".jar");
}

/// Gegen das echte Modrinth (nur lesend): Angaben + Abhängigkeiten von More Culling.
/// `cargo test -p trs-core real_modrinth_preset_check -- --ignored --nocapture`
#[tokio::test]
#[ignore = "braucht Internet (Modrinth)"]
async fn real_modrinth_preset_check() {
    let http = reqwest::Client::builder().user_agent("theredstonee/trs-launcher (preset test)").build().unwrap();
    let meta = modrinth_meta(&http, &["51shyZVL".to_owned(), "AANobbMI".to_owned()]).await;
    println!("{:?}", meta.values().map(|m| (&m.title, &m.categories, &m.server_side)).collect::<Vec<_>>());
    assert_eq!(meta.len(), 2);
    let deps = modrinth_deps(&http, &["51shyZVL".to_owned()]).await;
    println!("{deps:?}");
    assert!(deps[0].1.required.keys().any(|d| d == "9s6osm5g"), "More Culling braucht Cloth Config");
}
