//! Tests gegen eine nachgebaute CurseForge-API (nie gegen die echte). Die
//! Beispieldaten sind gekürzte echte Antworten (JEI, Entity Culling,
//! Fabulously Optimized), aufgezeichnet am 2026-09-24.

use std::io::Write;
use std::path::Path;

use serde_json::{Value, json};

use super::pack::{self, BlockedFile, adopt_from, name_matches, plan_downloads, read_manifest, remember_blocked, resolve};
use super::*;
use crate::content::{self, Platform, Source};
use crate::instance::{Loader, LoaderKind};
use crate::paths::Paths;
use crate::trs_api::testkit::{MockServer, Request, Response};

const KEY: &str = "test-key-0123456789abcdef";

fn client(mock: &MockServer) -> CurseForge {
    CurseForge::with_endpoint(&format!("{}/v1", mock.base), KEY).unwrap()
}

fn instance(version: &str, loader: &str) -> Instance {
    serde_json::from_value(json!({
        "id": "test", "name": "Test", "gameVersion": version, "loader": { "kind": loader, "version": null },
        "createdAt": "2026-01-01T00:00:00Z"
    }))
    .unwrap()
}

fn jei() -> Value {
    json!({
        "id": 238222, "gameId": 432, "name": "Just Enough Items (JEI)", "slug": "jei",
        "summary": "View Items and Recipes",
        "links": { "websiteUrl": "https://www.curseforge.com/minecraft/mc-mods/jei", "wikiUrl": null,
                   "issuesUrl": "https://github.com/mezz/JustEnoughItems/issues", "sourceUrl": "https://github.com/mezz/JustEnoughItems" },
        "downloadCount": 512_000_000, "thumbsUpCount": 900, "classId": 6,
        "categories": [
            { "id": 423, "gameId": 432, "name": "Map and Information", "slug": "map-information", "classId": 6, "parentCategoryId": 6,
              "iconUrl": "https://media.forgecdn.net/avatars/6/38/635351497437388438.png", "isClass": false },
            { "id": 421, "gameId": 432, "name": "API and Library", "slug": "library-api", "classId": 6, "parentCategoryId": 6,
              "iconUrl": "https://media.forgecdn.net/avatars/6/36/635351496947765531.png", "isClass": false }
        ],
        "authors": [{ "id": 17072262, "name": "mezz", "url": "https://www.curseforge.com/members/mezz" }],
        "logo": { "id": 29069, "modId": 238222, "title": "635838945588716414.jpeg", "description": "",
                  "thumbnailUrl": "https://media.forgecdn.net/avatars/thumbnails/29/69/256/256/635838945588716414.jpeg",
                  "url": "https://media.forgecdn.net/avatars/29/69/635838945588716414.jpeg" },
        "screenshots": [{ "title": "Rezepte", "description": "", "thumbnailUrl": "https://evil.example/x.png",
                          "url": "https://media.forgecdn.net/attachments/1/2/recipes.png" }],
        "mainFileId": 8947447,
        "latestFilesIndexes": [
            { "gameVersion": "1.20.1", "fileId": 8947447, "filename": "jei-1.20.1-forge-15.62.0.214.jar", "releaseType": 2, "gameVersionTypeId": 75125, "modLoader": 1 },
            { "gameVersion": "1.20.1", "fileId": 8895264, "filename": "jei-1.20.1-forge-15.59.0.212.jar", "releaseType": 1, "gameVersionTypeId": 75125, "modLoader": 1 },
            { "gameVersion": "1.20.1", "fileId": 8947448, "filename": "jei-1.20.1-fabric-15.62.0.214.jar", "releaseType": 1, "gameVersionTypeId": 75125, "modLoader": 4 },
            { "gameVersion": "1.12.2", "fileId": 3040523, "filename": "jei_1.12.2-4.16.1.301.jar", "releaseType": 1, "gameVersionTypeId": 628, "modLoader": null }
        ],
        "dateModified": "2026-09-22T14:55:10.87Z", "dateCreated": "2015-11-26T07:05:24.433Z",
        "dateReleased": "2026-09-22T14:49:13.09Z", "allowModDistribution": true
    })
}

fn jei_file() -> Value {
    json!({
        "id": 8947447, "gameId": 432, "modId": 238222, "isAvailable": true,
        "displayName": "jei-1.20.1-forge-15.62.0.214.jar", "fileName": "jei-1.20.1-forge-15.62.0.214.jar",
        "releaseType": 2, "fileStatus": 4,
        "hashes": [{ "value": "3045e8440ea44071d8b83c4e7b3c190348fdc527", "algo": 1 }, { "value": "1dee4be93d666e2228039c551e927b35", "algo": 2 }],
        "fileDate": "2026-09-22T14:49:13.09Z", "fileLength": 2231809, "downloadCount": 10,
        "downloadUrl": "https://edge.forgecdn.net/files/8947/447/jei-1.20.1-forge-15.62.0.214.jar",
        "gameVersions": ["Client", "1.20.1", "Forge", "Server"],
        "dependencies": [{ "modId": 1689768, "relationType": 3 }, { "modId": 1700987, "relationType": 2 }],
        "isServerPack": false
    })
}

/// Entity Culling: Autor erlaubt keine Downloads über andere Apps.
fn entity_culling() -> Value {
    json!({
        "id": 448233, "gameId": 432, "name": "Entity Culling Fabric/Forge", "slug": "entityculling", "classId": 6,
        "links": { "websiteUrl": "https://www.curseforge.com/minecraft/mc-mods/entityculling" },
        "authors": [{ "name": "tr7zw" }], "allowModDistribution": false,
        "latestFilesIndexes": [{ "gameVersion": "1.21.1", "fileId": 8053769, "filename": "entityculling-fabric-1.10.2-mc1.21.1.jar", "releaseType": 1, "modLoader": 4 }]
    })
}

fn entity_culling_file() -> Value {
    json!({
        "id": 8053769, "modId": 448233, "displayName": "entityculling-fabric-1.10.2-mc1.21.1.jar",
        "fileName": "entityculling-fabric-1.10.2-mc1.21.1.jar", "releaseType": 1,
        "hashes": [{ "value": "fd43e8a962d7026fa5342c260fd3b3f98809913c", "algo": 1 }],
        "fileDate": "2026-08-01T10:00:00Z", "fileLength": 5, "downloadUrl": null,
        "gameVersions": ["Client", "Fabric", "1.21.1"],
        "dependencies": [{ "modId": 306612, "relationType": 3 }]
    })
}

fn data(value: Value) -> Response {
    Response::json(200, json!({ "data": value }))
}

fn path_of(r: &Request) -> &str {
    r.path.split('?').next().unwrap_or("")
}

fn query_of(r: &Request) -> Vec<(String, String)> {
    let query = r.path.split_once('?').map(|(_, q)| q).unwrap_or("");
    reqwest::Url::parse(&format!("http://x/?{query}")).unwrap().query_pairs().into_owned().collect()
}

fn param(r: &Request, name: &str) -> Option<String> {
    query_of(r).into_iter().find(|(k, _)| k == name).map(|(_, v)| v)
}

fn search_params(value: Value) -> SearchParams {
    serde_json::from_value(value).unwrap()
}

#[tokio::test]
async fn search_sends_key_only_as_header_and_maps_hits() {
    let mock = MockServer::start(|r| match path_of(r) {
        "/v1/mods/search" => Response::json(
            200,
            json!({ "data": [jei()], "pagination": { "index": 0, "pageSize": 50, "resultCount": 1, "totalCount": 160 } }),
        ),
        _ => Response::empty(404),
    })
    .await;
    let cf = client(&mock);
    let params = search_params(json!({
        "query": "jei", "kind": "mod", "gameVersions": ["1.20.1"], "loaders": ["forge"],
        "categories": ["423"], "index": "downloads", "limit": 100, "offset": 0
    }));
    let result = cf.search(&params).await.unwrap();
    assert_eq!(result.total_hits, 160);
    assert_eq!(result.limit, 50, "CurseForge erlaubt höchstens 50 je Seite");
    let hit = &result.hits[0];
    assert_eq!(hit.project_id, "238222");
    assert_eq!(hit.author, "mezz");
    assert_eq!(hit.downloads, 512_000_000);
    assert!(hit.icon_url.as_deref().unwrap().starts_with("https://media.forgecdn.net/"));
    assert_eq!(hit.categories, ["423", "421", "forge", "fabric"]);

    let requests = mock.requests();
    assert_eq!(requests.len(), 1);
    let r = &requests[0];
    assert_eq!(r.header("x-api-key"), Some(KEY));
    assert!(!r.path.contains(KEY), "Schlüssel nie in der Adresse");
    assert_eq!(param(r, "gameId").as_deref(), Some("432"));
    assert_eq!(param(r, "classId").as_deref(), Some("6"));
    assert_eq!(param(r, "searchFilter").as_deref(), Some("jei"));
    assert_eq!(param(r, "gameVersion").as_deref(), Some("1.20.1"));
    assert_eq!(param(r, "modLoaderType").as_deref(), Some("1"));
    assert_eq!(param(r, "categoryIds").as_deref(), Some("[423]"));
    assert_eq!(param(r, "sortField").as_deref(), Some("6"));
    assert_eq!(param(r, "pageSize").as_deref(), Some("50"));

    // Gleiche Suche kommt aus dem Cache.
    cf.search(&params).await.unwrap();
    assert_eq!(mock.requests().len(), 1);

    // Mehrere Versionen / Loader als JSON-Listen, Shader ohne Loader-Filter.
    let many = search_params(json!({
        "query": "", "kind": "shaderpack", "gameVersions": ["1.20.1", "1.21.1"], "loaders": ["fabric", "quilt"], "index": "relevance"
    }));
    cf.search(&many).await.unwrap();
    let r = mock.requests().pop().unwrap();
    assert_eq!(param(&r, "classId").as_deref(), Some("6552"));
    assert_eq!(param(&r, "gameVersions").as_deref(), Some(r#"["1.20.1","1.21.1"]"#));
    assert_eq!(param(&r, "modLoaderTypes"), None);
    assert_eq!(param(&r, "searchFilter"), None);
    assert_eq!(param(&r, "sortField").as_deref(), Some("2"), "Relevanz = Beliebtheit");
}

#[test]
fn search_validation() {
    let bad = [
        json!({ "kind": "mod", "categories": ["magic"] }),
        json!({ "kind": "mod", "categories": ["1","2","3","4","5","6","7","8","9","10","11"] }),
        json!({ "kind": "mod", "offset": 9_980, "limit": 50 }),
        json!({ "kind": "mod", "loaders": ["bukkit"] }),
        json!({ "kind": "mod", "gameVersions": ["1.21\"&x=1"] }),
    ];
    for value in bad {
        assert!(search_query(&search_params(value.clone())).is_err(), "{value}");
    }
    let q = search_query(&search_params(json!({ "kind": "modpack", "query": "a&b=c", "loaders": ["neoforge"] }))).unwrap();
    assert!(q.contains("searchFilter=a%26b%3Dc"), "{q}");
    assert!(q.contains("classId=4471") && q.contains("modLoaderType=6"), "{q}");
}

#[tokio::test]
async fn api_errors_become_translated_messages() {
    let mock = MockServer::start(|r| match path_of(r) {
        "/v1/mods/1" => Response::json(429, json!({})).with_header("retry-after", "120"),
        "/v1/mods/2" => Response::empty(403),
        "/v1/mods/3" => Response::empty(404),
        "/v1/mods/4" => Response::empty(503),
        "/v1/mods/5" => Response::json(200, json!({ "unerwartet": true })),
        _ => Response::empty(500),
    })
    .await;
    let cf = client(&mock);
    let code = |e: Error| e.to_user().code;
    let limited = cf.project_details("1").await.unwrap_err();
    assert_eq!(limited.to_user().params["seconds"], "120");
    assert_eq!(code(limited), "curseforge.rateLimited");
    assert_eq!(code(cf.project_details("2").await.unwrap_err()), "curseforge.accessDenied");
    assert_eq!(code(cf.project_details("3").await.unwrap_err()), "curseforge.notFound");
    assert_eq!(code(cf.project_details("4").await.unwrap_err()), "curseforge.unavailable");
    assert_eq!(code(cf.project_details("5").await.unwrap_err()), "curseforge.badResponse");
    assert_eq!(code(cf.project_details("../x").await.unwrap_err()), "curseforge.invalidProjectId");
    // Kein Fehlertext verrät den Schlüssel.
    for id in ["1", "2", "3", "4", "5"] {
        let e = cf.project_details(id).await.unwrap_err();
        assert!(!format!("{e} {e:?}").contains(KEY));
    }
    assert!(!format!("{cf:?}").contains(KEY));
}

#[tokio::test]
async fn short_rate_limits_are_waited_out_once() {
    let calls = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let counter = calls.clone();
    let mock = MockServer::start(move |r| {
        if path_of(r) != "/v1/mods/238222" {
            return Response::empty(404);
        }
        if counter.fetch_add(1, std::sync::atomic::Ordering::SeqCst) == 0 {
            Response::json(429, json!({})).with_header("retry-after", "1")
        } else {
            data(jei())
        }
    })
    .await;
    let m = client(&mock).mod_info(238222).await.unwrap();
    assert_eq!(m.name, "Just Enough Items (JEI)");
    assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), 2);
}

#[tokio::test]
async fn api_client_never_follows_redirects() {
    // Eine Weiterleitung darf den Schlüssel nicht zu einem anderen Host tragen.
    let elsewhere = MockServer::start(|_| data(jei())).await;
    let target = format!("{}/steal", elsewhere.base);
    let mock = MockServer::start(move |_| Response::empty(302).with_header("location", &target)).await;
    assert!(client(&mock).mod_info(238222).await.is_err());
    assert!(elsewhere.requests().is_empty(), "keine Anfrage beim fremden Host");
}

#[test]
fn download_hosts_are_limited() {
    assert!(is_allowed_download_url("https://edge.forgecdn.net/files/8947/447/jei.jar"));
    assert!(is_allowed_download_url("https://mediafilez.forgecdn.net/files/5923/136/Fabulously%20Optimized-6.2.3.zip"));
    for bad in [
        "http://edge.forgecdn.net/files/1/2/x.jar",
        "https://edge.forgecdn.net.evil.example/x.jar",
        "https://user:pw@edge.forgecdn.net/x.jar",
        "https://edge.forgecdn.net:8443/x.jar",
        "https://media.forgecdn.net/files/1/2/x.jar",
        "https://cdn.modrinth.com/data/x.jar",
        "file:///C:/x.jar",
    ] {
        assert!(!is_allowed_download_url(bad), "{bad}");
    }
}

#[test]
fn files_fit_version_and_loader() {
    let mut forge: RawFile = serde_json::from_value(jei_file()).unwrap();
    assert!(forge.fits(&instance("1.20.1", "forge"), ContentKind::Mod));
    assert!(!forge.fits(&instance("1.20.1", "fabric"), ContentKind::Mod));
    assert!(!forge.fits(&instance("1.21.1", "forge"), ContentKind::Mod));
    assert_eq!(forge.sha1().as_deref(), Some("3045e8440ea44071d8b83c4e7b3c190348fdc527"));
    assert_eq!(forge.release_type(), "beta");
    assert_eq!(forge.version_label(), "jei-1.20.1-forge-15.62.0.214");
    assert_eq!(forge.minecraft_versions(), ["1.20.1"]);

    // Quilt nimmt Fabric-Mods; alte Forge-Dateien nennen keinen Loader.
    forge.game_versions = vec!["1.20.1".into(), "Fabric".into()];
    assert!(forge.fits(&instance("1.20.1", "quilt"), ContentKind::Mod));
    forge.game_versions = vec!["1.12.2".into()];
    assert!(forge.fits(&instance("1.12.2", "forge"), ContentKind::Mod));
    assert!(!forge.fits(&instance("1.12.2", "fabric"), ContentKind::Mod));
    // Ressourcenpakete brauchen keinen Loader.
    assert!(forge.fits(&instance("1.12.2", "vanilla"), ContentKind::ResourcePack));

    let summary = summarize(serde_json::from_value(jei_file()).unwrap(), true).unwrap();
    assert_eq!(summary.id, "8947447");
    assert_eq!(summary.loaders, ["forge"]);
    assert_eq!(summary.dependencies[0].dependency_type, "required");
    assert_eq!(summary.dependencies[1].dependency_type, "optional");

    let mut server_pack: RawFile = serde_json::from_value(jei_file()).unwrap();
    server_pack.is_server_pack = Some(true);
    assert!(summarize(server_pack, true).is_none());
}

#[tokio::test]
async fn details_are_sanitized() {
    let mock = MockServer::start(|r| match path_of(r) {
        "/v1/mods/238222" => data(jei()),
        "/v1/mods/238222/description" => data(json!("<p>Hallo <script>alert(1)</script></p>")),
        _ => Response::empty(404),
    })
    .await;
    let d = client(&mock).project_details("238222").await.unwrap();
    assert_eq!(d.project_type, "mod");
    assert_eq!(d.author.as_deref(), Some("mezz"));
    assert_eq!(d.loaders, ["forge", "fabric"]);
    assert_eq!(d.game_versions, ["1.20.1", "1.12.2"]);
    assert_eq!(d.categories, ["Map and Information", "API and Library"]);
    assert_eq!(d.links[0].kind, "curseforge");
    assert_eq!(d.links[0].url, "https://www.curseforge.com/minecraft/mc-mods/jei");
    assert!(d.links.iter().all(|l| l.kind != "wiki"), "null-Links fallen weg");
    assert_eq!(d.gallery.len(), 1, "Screenshots nur von media.forgecdn.net");
    // Das HTML wird im Frontend bereinigt (DOMPurify); der Kern kürzt nur.
    assert!(d.body.starts_with("<p>Hallo"));
}

#[tokio::test]
async fn blocked_files_are_never_downloaded_but_remembered() {
    let mock = MockServer::start(|r| match path_of(r) {
        "/v1/mods/448233" => data(entity_culling()),
        "/v1/mods/448233/files" => Response::json(
            200,
            json!({ "data": [entity_culling_file()], "pagination": { "index": 0, "pageSize": 50, "resultCount": 1, "totalCount": 1 } }),
        ),
        "/v1/mods/306612" => data(json!({ "id": 306612, "gameId": 432, "name": "Fabric API", "slug": "fabric-api", "classId": 6 })),
        "/v1/mods/306612/files" => Response::json(200, json!({ "data": [], "pagination": { "index": 0, "pageSize": 50, "totalCount": 0 } })),
        _ => Response::empty(404),
    })
    .await;
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    paths.ensure().await.unwrap();
    let inst = instance("1.21.1", "fabric");
    let cf = client(&mock);

    let outcome = cf.install(&paths, &inst, "448233", ContentKind::Mod, None).await.unwrap();
    assert!(outcome.files.is_empty(), "nichts wurde geladen");
    assert_eq!(outcome.blocked.len(), 1);
    let blocked = &outcome.blocked[0];
    assert_eq!(blocked.url, "https://www.curseforge.com/minecraft/mc-mods/entityculling/files/8053769");
    assert_eq!(blocked.sha1.as_deref(), Some("fd43e8a962d7026fa5342c260fd3b3f98809913c"));
    // Die Abhängigkeit wurde trotzdem gesucht (hier ohne passende Datei).
    assert_eq!(mock.hits("GET", "/v1/mods/306612/files").len(), 1);
    // Kein Download-Versuch irgendwohin, keine Datei im Mods-Ordner.
    assert!(!content::content_dir(&paths, "test", ContentKind::Mod).join(&blocked.file_name).exists());

    let pending = pack::blocked_files(&paths, "test").await.unwrap();
    assert_eq!(pending, outcome.blocked);
    assert!(pack::dismiss_blocked(&paths, "test", Some("8053769")).await.unwrap().is_empty());
    assert!(pack::dismiss_blocked(&paths, "test", Some("../x")).await.is_err());
}

fn write_zip(path: &Path, files: &[(&str, &[u8])]) {
    let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
    for (name, bytes) in files {
        zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(bytes).unwrap();
    }
    zip.finish().unwrap();
}

fn manifest(loader: &str, overrides: &str) -> String {
    format!(
        "\u{feff}{}",
        json!({
            "minecraft": { "version": "1.21.1", "modLoaders": [{ "id": loader, "primary": true }] },
            "manifestType": "minecraftModpack", "manifestVersion": 1, "name": "Fabulously Optimized", "version": "6.5.0",
            "author": "robotkoer", "overrides": overrides,
            "files": [
                { "projectID": 394468, "fileID": 6382649, "required": true },
                { "projectID": 448233, "fileID": 8053769, "required": true },
                { "projectID": 855981, "fileID": 8205054, "required": true },
                { "projectID": 1, "fileID": 2, "required": false }
            ]
        })
    )
}

#[tokio::test]
async fn modpack_manifest_resolve_and_plan() {
    let dir = tempfile::tempdir().unwrap();
    let pack = dir.path().join("fo.zip");
    let manifest_text = manifest("fabric-0.19.3", "overrides");
    write_zip(
        &pack,
        &[
            ("manifest.json", manifest_text.as_bytes()),
            ("modlist.html", b"<ul></ul>"),
            ("overrides/config/sodium.json", b"{}"),
            ("overrides/../../evil.txt", b"x"),
            ("other/options.txt", b"nope"),
        ],
    );
    assert!(pack::is_curseforge_pack(&pack));
    let m = read_manifest(&pack).unwrap();
    assert_eq!(m.loader().unwrap(), Loader { kind: LoaderKind::Fabric, version: Some("0.19.3".into()) });
    assert_eq!(m.overrides_prefix().unwrap(), "overrides/");

    let game = dir.path().join("game");
    crate::modpack::extract_folders(&pack, &game, &["overrides/"]).unwrap();
    assert!(game.join("config/sodium.json").is_file());
    assert!(!game.join("options.txt").exists() && !dir.path().join("evil.txt").exists());

    let mock = MockServer::start(|r| match (r.method.as_str(), path_of(r)) {
        ("POST", "/v1/mods/files") => {
            let ids = r.json()["fileIds"].clone();
            assert_eq!(ids, json!([6382649, 8053769, 8205054]), "nur Pflicht-Dateien");
            data(json!([
                { "id": 6382649, "modId": 394468, "displayName": "Sodium 0.6.13", "fileName": "sodium-fabric-0.6.13+mc1.21.1.jar",
                  "releaseType": 1, "hashes": [{ "value": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "algo": 1 }], "fileLength": 10,
                  "downloadUrl": "https://edge.forgecdn.net/files/6382/649/sodium-fabric-0.6.13+mc1.21.1.jar", "gameVersions": ["Fabric", "1.21.1"] },
                entity_culling_file(),
                { "id": 8205054, "modId": 855981, "displayName": "Chat Reporting Helper", "fileName": "Chat Reporting Helper.zip",
                  "releaseType": 1, "hashes": [{ "value": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "algo": 1 }], "fileLength": 20,
                  "downloadUrl": "https://edge.forgecdn.net/files/8205/54/Chat%20Reporting%20Helper.zip", "gameVersions": ["1.21.1"] }
            ]))
        }
        ("POST", "/v1/mods") => data(json!([
            { "id": 394468, "gameId": 432, "name": "Sodium", "slug": "sodium", "classId": 6 },
            entity_culling(),
            { "id": 855981, "gameId": 432, "name": "Chat Reporting Helper", "slug": "chat-reporting-helper", "classId": 12 }
        ])),
        _ => Response::empty(404),
    })
    .await;
    let cf = client(&mock);
    let entries = resolve(&cf, &m).await.unwrap();
    assert_eq!(entries.len(), 3);
    let (tasks, blocked) = plan_downloads(&entries, &game).unwrap();
    assert_eq!(tasks.len(), 2);
    assert!(tasks.iter().any(|t| t.path.ends_with("mods/sodium-fabric-0.6.13+mc1.21.1.jar")
        || t.path.ends_with("mods\\sodium-fabric-0.6.13+mc1.21.1.jar")));
    assert!(tasks.iter().any(|t| t.path.to_string_lossy().contains("resourcepacks")), "Klasse 12 → Ressourcenpakete");
    assert!(tasks.iter().all(|t| t.sha1.is_some()));
    assert_eq!(blocked.len(), 1);
    assert_eq!(blocked[0].title, "Entity Culling Fabric/Forge");

    // Eine Datei auf einem fremden Host bricht die Planung ab.
    let mut evil = entries;
    evil[0].file.download_url = Some("https://evil.example/sodium.jar".into());
    assert!(plan_downloads(&evil, &game).is_err());
}

#[test]
fn manifest_rejects_bad_input() {
    let dir = tempfile::tempdir().unwrap();
    let pack = dir.path().join("p.zip");
    for (loader, overrides) in [("fabric-0.19.3", "../x"), ("fabric-0.19.3", "a/b")] {
        write_zip(&pack, &[("manifest.json", manifest(loader, overrides).as_bytes())]);
        assert!(read_manifest(&pack).is_err(), "{overrides}");
    }
    write_zip(&pack, &[("manifest.json", manifest("rift-1.0", "overrides").as_bytes())]);
    assert_eq!(read_manifest(&pack).unwrap().loader().unwrap_err().to_user().code, "curseforge.unsupportedLoader");
    write_zip(&pack, &[("manifest.json", manifest("neoforge-21.1.72", "overrides").as_bytes())]);
    assert_eq!(read_manifest(&pack).unwrap().loader().unwrap().kind, LoaderKind::NeoForge);
    write_zip(&pack, &[("modrinth.index.json", b"{}"), ("manifest.json", b"{}")]);
    assert!(!pack::is_curseforge_pack(&pack), "Modrinth-Packs bleiben Modrinth-Packs");
    write_zip(&pack, &[("manifest.json", br#"{"minecraft":{"version":"1.21.1"},"manifestType":"other","manifestVersion":1}"#)]);
    assert!(read_manifest(&pack).is_err());
}

#[test]
fn download_names_match_browser_copies() {
    assert!(name_matches("entityculling-fabric-1.10.2-mc1.21.1.jar", "entityculling-fabric-1.10.2-mc1.21.1.jar"));
    assert!(name_matches("EntityCulling-Fabric-1.10.2-mc1.21.1.JAR", "entityculling-fabric-1.10.2-mc1.21.1.jar"));
    assert!(name_matches("entityculling-fabric-1.10.2-mc1.21.1 (1).jar", "entityculling-fabric-1.10.2-mc1.21.1.jar"));
    assert!(name_matches("entityculling-fabric-1.10.2-mc1.21.1(12).jar", "entityculling-fabric-1.10.2-mc1.21.1.jar"));
    for bad in ["entityculling.jar", "entityculling-fabric-1.10.2-mc1.21.1 (x).jar", "entityculling-fabric-1.10.2-mc1.21.1.jar.zip"] {
        assert!(!name_matches(bad, "entityculling-fabric-1.10.2-mc1.21.1.jar"), "{bad}");
    }
}

#[tokio::test]
async fn adoption_needs_matching_name_and_checksum() {
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path().join("data"));
    paths.ensure().await.unwrap();
    let downloads = dir.path().join("Downloads");
    std::fs::create_dir_all(&downloads).unwrap();

    let bytes = b"hello";
    let sha1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";
    let m: RawMod = serde_json::from_value(entity_culling()).unwrap();
    let mut file: RawFile = serde_json::from_value(entity_culling_file()).unwrap();
    file.hashes = vec![RawHash { value: sha1.into(), algo: 1 }];
    let entry = BlockedFile::new(&m, &file, ContentKind::Mod, None);
    let mut other = entry.clone();
    other.project_id = "999".into();
    other.file_id = "1000".into();
    other.file_name = "other.jar".into();
    remember_blocked(&paths, "test", &[entry.clone(), other]).await.unwrap();

    // Falscher Inhalt unter dem richtigen Namen: bleibt liegen.
    std::fs::write(downloads.join(&entry.file_name), b"HELLO").unwrap();
    let r = adopt_from(&paths, "test", Some(downloads.clone())).await.unwrap();
    assert!(r.adopted.is_empty() && r.pending.len() == 2);

    // Browser-Kopie mit passender Prüfsumme wird übernommen und eingetragen.
    std::fs::write(downloads.join("entityculling-fabric-1.10.2-mc1.21.1 (1).jar"), bytes).unwrap();
    let r = adopt_from(&paths, "test", Some(downloads.clone())).await.unwrap();
    assert_eq!(r.adopted, ["Entity Culling Fabric/Forge"]);
    assert_eq!(r.pending.len(), 1);
    let mods = content::content_dir(&paths, "test", ContentKind::Mod);
    assert_eq!(std::fs::read(mods.join(&entry.file_name)).unwrap(), bytes);
    let index = content::read_index(&paths, "test").await;
    let source = index.files.get(&content::index_key(ContentKind::Mod, &entry.file_name)).unwrap();
    assert_eq!(source.platform, Platform::CurseForge);
    assert_eq!((source.project_id.as_str(), source.version_id.as_str()), ("448233", "8053769"));
    assert_eq!(index.projects["cf:448233"].title, "Entity Culling Fabric/Forge");
    // Die Datei im Download-Ordner bleibt (kopiert, nicht verschoben).
    assert!(downloads.join("entityculling-fabric-1.10.2-mc1.21.1 (1).jar").exists());

    // Direkt in den Zielordner gelegt zählt auch.
    std::fs::write(mods.join("other.jar"), bytes).unwrap();
    let r = adopt_from(&paths, "test", None).await.unwrap();
    assert_eq!(r.adopted.len(), 1);
    assert!(r.pending.is_empty());
    assert!(pack::blocked_files(&paths, "test").await.unwrap().is_empty());
}

#[tokio::test]
async fn updates_come_from_file_indexes() {
    let mock = MockServer::start(|r| match (r.method.as_str(), path_of(r)) {
        ("POST", "/v1/mods") => {
            assert_eq!(r.json()["modIds"], json!([238222]));
            data(json!([jei()]))
        }
        _ => Response::empty(404),
    })
    .await;
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    paths.ensure().await.unwrap();
    let mods = content::content_dir(&paths, "test", ContentKind::Mod);
    std::fs::create_dir_all(&mods).unwrap();
    std::fs::write(mods.join("jei-old.jar"), b"x").unwrap();
    let source = Source {
        project_id: "238222".into(),
        version_id: "8879628".into(),
        version_number: Some("jei-1.20.1-forge-15.59.0.211".into()),
        platform: Platform::CurseForge,
    };
    content::remember_source(&paths, "test", ContentKind::Mod, "jei-old.jar", source).await.unwrap();
    let cf = client(&mock);

    // Kanal „stabil“: die neueste Release-Datei für Forge 1.20.1.
    let updates = cf.check_updates(&paths, &instance("1.20.1", "forge")).await.unwrap();
    assert_eq!(updates.len(), 1);
    assert_eq!(updates[0].platform, Platform::CurseForge);
    assert_eq!(updates[0].version_id, "8895264");
    assert_eq!(updates[0].version_number, "jei-1.20.1-forge-15.59.0.212");

    // Beta-Kanal nimmt auch die Beta.
    let mut beta = instance("1.20.1", "forge");
    beta.overrides.update_channel = Some(crate::instance::UpdateChannel::Beta);
    assert_eq!(cf.check_updates(&paths, &beta).await.unwrap()[0].version_id, "8947447");

    // Andere Spielversion: nichts.
    assert!(cf.check_updates(&paths, &instance("1.21.1", "forge")).await.unwrap().is_empty());
}

#[test]
fn page_urls() {
    let mut m: RawMod = serde_json::from_value(jei()).unwrap();
    assert_eq!(m.file_page_url(1), "https://www.curseforge.com/minecraft/mc-mods/jei/files/1");
    m.links.website_url = Some("https://evil.example/jei".into());
    assert_eq!(m.page_url(), "https://www.curseforge.com/minecraft/mc-mods/jei");
    m.slug = "../x".into();
    assert_eq!(m.page_url(), "https://www.curseforge.com/projects/238222");
    // allowModDistribution: false sperrt auch Dateien mit Download-Adresse.
    let file: RawFile = serde_json::from_value(jei_file()).unwrap();
    assert!(m.download_url(&file).is_some());
    m.allow_mod_distribution = Some(false);
    assert!(m.download_url(&file).is_none());
}

#[test]
fn ids_are_plain_numbers() {
    assert_eq!(parse_id("238222"), Some(238222));
    for bad in ["", "0", "-1", "12a", "1e5", "../1", "9999999999999"] {
        assert_eq!(parse_id(bad), None, "{bad}");
    }
}

#[test]
fn categories_map_to_filter_tags() {
    let raw: Vec<RawCategory> = serde_json::from_value(json!([
        { "id": 6, "name": "Mods", "classId": null, "isClass": true },
        { "id": 419, "name": "Magic", "classId": 6, "iconUrl": "https://media.forgecdn.net/avatars/6/34/x.png", "isClass": false },
        { "id": 4473, "name": "Magic", "classId": 4471, "iconUrl": "https://evil.example/x.png" },
        { "id": 5, "name": "Bukkit", "classId": 5 }
    ]))
    .unwrap();
    let tags: Vec<_> = raw.into_iter().filter_map(category_from_raw).collect();
    assert_eq!(tags.len(), 2);
    assert_eq!((tags[0].name.as_str(), tags[0].project_type.as_str()), ("419", "mod"));
    assert_eq!(tags[0].label.as_deref(), Some("Magic"));
    assert!(tags[0].icon_url.is_some() && tags[1].icon_url.is_none());
    assert_eq!(tags[1].project_type, "modpack");
}
