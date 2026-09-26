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
