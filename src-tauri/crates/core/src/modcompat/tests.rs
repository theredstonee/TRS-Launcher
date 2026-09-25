use std::future::Future;
use std::sync::Mutex;

use super::meta::Constraint;
use super::*;
use crate::Result;
use crate::instance::Loader;

/// Attrappe: Versionen je Projekt (neueste zuerst) und Jar-Angaben je Version.
#[derive(Default)]
struct Fake {
    versions: HashMap<String, Vec<Version>>,
    infos: HashMap<String, Vec<ModInfo>>,
    info_calls: Mutex<Vec<String>>,
}

impl Fake {
    /// Hängt eine (ältere) Version an; `info` = was im Jar steht.
    fn with(mut self, project: &str, id: &str, kind: &str, info: ModInfo) -> Self {
        self.versions.entry(project.into()).or_default().push(version(project, id, kind));
        self.infos.insert(id.into(), vec![info]);
        self
    }
}

impl VersionLookup for Fake {
    fn versions(&self, project_id: &str, _kind: ContentKind) -> impl Future<Output = Result<Vec<Version>>> + Send {
        std::future::ready(Ok(self.versions.get(project_id).cloned().unwrap_or_default()))
    }
    fn version(&self, version_id: &str) -> impl Future<Output = Result<Option<Version>>> + Send {
        let found = self.versions.values().flatten().find(|v| v.id == version_id).cloned();
        std::future::ready(Ok(found))
    }
    fn title(&self, project_id: &str) -> impl Future<Output = Option<String>> + Send {
        std::future::ready(Some(project_id.to_owned()))
    }
    fn mod_info(&self, version: &Version) -> impl Future<Output = Vec<ModInfo>> + Send {
        self.info_calls.lock().unwrap().push(version.id.clone());
        std::future::ready(self.infos.get(&version.id).cloned().unwrap_or_default())
    }
}

fn version(project: &str, id: &str, kind: &str) -> Version {
    serde_json::from_value(serde_json::json!({
        "id": id, "project_id": project, "version_number": format!("{id}-nr"), "version_type": kind,
        "game_versions": ["1.21.11"], "loaders": ["fabric"],
        "files": [{"url": format!("https://cdn.modrinth.com/{id}.jar"), "filename": format!("{id}.jar"),
                   "primary": true, "size": 1, "hashes": {"sha1": "a"}}],
    }))
    .unwrap()
}

fn info(id: &str, name: &str, ver: &str, depends: &[(&str, &str)], breaks: &[(&str, &str)]) -> ModInfo {
    let list = |pairs: &[(&str, &str)]| pairs.iter().map(|(i, p)| Constraint { id: (*i).into(), any_of: vec![(*p).into()] }).collect();
    ModInfo {
        id: id.into(),
        name: name.into(),
        version: ver.into(),
        scheme: Scheme::Fabric,
        provides: Vec::new(),
        depends: list(depends),
        breaks: list(breaks),
    }
}

fn sodium(v: &str, breaks_iris: &str) -> ModInfo {
    info("sodium", "Sodium", &format!("{v}+mc1.21.11"), &[("fabricloader", ">=0.16.0")], &[("iris", breaks_iris), ("embeddium", "*")])
}

fn iris(v: &str) -> ModInfo {
    info("iris", "Iris", &format!("{v}+mc1.21.11"), &[("sodium", "0.8.x")], &[])
}

/// Echte Lage für 1.21.11 (Stand 2026-09): Sodium 0.8.13 und 0.8.14 brechen
/// Iris bis 1.10.7, 0.8.12 nur bis 1.10.6; 0.8.15 gibt es nur als Beta.
fn modrinth_like() -> Fake {
    Fake::default()
        .with("AANobbMI", "s-0815b1", "beta", sodium("0.8.15-beta.1", "<=1.10.7"))
        .with("AANobbMI", "s-0814", "release", sodium("0.8.14", "<=1.10.7"))
        .with("AANobbMI", "s-0814b2", "beta", sodium("0.8.14-beta.2", "<=1.10.7"))
        .with("AANobbMI", "s-0813", "release", sodium("0.8.13", "<=1.10.7"))
        .with("AANobbMI", "s-0812", "release", sodium("0.8.12", "<=1.10.6"))
        .with("AANobbMI", "s-0811", "release", sodium("0.8.11", "<=1.10.6"))
        .with("YL57xq9U", "i-1107", "release", iris("1.10.7"))
        .with("YL57xq9U", "i-1106", "release", iris("1.10.6"))
}

fn planned(fake: &Fake, project: &str, id: &str) -> Entry {
    let v = fake.versions[project].iter().find(|v| v.id == id).unwrap().clone();
    Entry {
        project_id: Some(project.into()),
        version_id: Some(id.into()),
        mods: fake.infos[id].clone(),
        version: Some(v),
        adjustable: true,
        ..Default::default()
    }
}

fn installed(fake: &Fake, project: &str, id: &str, file: &str) -> Entry {
    Entry {
        project_id: Some(project.into()),
        version_id: Some(id.into()),
        mods: fake.infos[id].clone(),
        adjustable: true,
        installed: Some((id.into(), fake.infos[id].clone())),
        file_name: Some(file.into()),
        ..Default::default()
    }
}

fn loader(version: &str) -> Vec<ModInfo> {
    vec![info("fabricloader", "Fabric Loader", version, &[], &[])]
}

#[test]
fn finds_breaks_depends_and_ignores_unknown() {
    let fake = modrinth_like();
    let entries = [planned(&fake, "AANobbMI", "s-0814"), planned(&fake, "YL57xq9U", "i-1107")];
    let conflicts = find_conflicts(&entries, &loader("0.19.5"));
    assert_eq!(conflicts.len(), 1);
    let c = &conflicts[0];
    assert_eq!((c.declarer, c.target, c.kind), (0, Party::Entry(1), ConflictKind::Breaks));
    assert_eq!((c.declarer_label.as_str(), c.target_label.as_str()), ("Sodium 0.8.14+mc1.21.11", "Iris 1.10.7+mc1.21.11"));

    // 0.8.12 + Iris 1.10.7 passt.
    let ok = [planned(&fake, "AANobbMI", "s-0812"), planned(&fake, "YL57xq9U", "i-1107")];
    assert!(find_conflicts(&ok, &loader("0.19.5")).is_empty());

    // Zu alter Loader: `depends` verletzt; ohne bekannte Loader-Version kein Konflikt.
    let c = find_conflicts(&ok, &loader("0.15.11"));
    assert_eq!((c[0].declarer, c[0].target, c[0].kind), (0, Party::Builtin(0), ConflictKind::Depends));
    assert!(find_conflicts(&ok, &[]).is_empty());

    // `provides` zählt als die Mod; Text-Versionen melden keinen Konflikt.
    let mut embeddium_alias = info("other", "Other", "1.0.0", &[], &[]);
    embeddium_alias.provides = vec!["embeddium".into()];
    let alias = Entry { mods: vec![embeddium_alias], ..Default::default() };
    assert_eq!(find_conflicts(&[planned(&fake, "AANobbMI", "s-0812"), alias], &[]).len(), 1);
    let odd = Entry { mods: vec![info("iris", "Iris", "custom-build", &[], &[])], ..Default::default() };
    assert!(find_conflicts(&[planned(&fake, "AANobbMI", "s-0812"), odd], &[]).is_empty());
}

#[tokio::test]
async fn picks_an_older_sodium_that_does_not_break_iris() {
    let fake = modrinth_like();
    let mut entries = vec![planned(&fake, "AANobbMI", "s-0814"), planned(&fake, "YL57xq9U", "i-1107")];
    let left = settle(&fake, UpdateChannel::Release, &mut entries, &loader("0.19.5"), None).await.unwrap();
    assert!(left.is_empty(), "{left:?}");
    assert_eq!(entries[0].version_id.as_deref(), Some("s-0812"));
    assert_eq!(entries[0].because.as_deref(), Some("Iris 1.10.7+mc1.21.11"));
    assert!(entries[1].because.is_none());
    // Nur Sodium 0.8.13 und 0.8.12 angesehen – stabile Versionen vor Betas.
    assert_eq!(*fake.info_calls.lock().unwrap(), ["s-0813", "s-0812"]);
}

#[tokio::test]
async fn with_beta_channel_still_stays_in_budget() {
    let fake = modrinth_like();
    let mut entries = vec![planned(&fake, "AANobbMI", "s-0815b1"), planned(&fake, "YL57xq9U", "i-1107")];
    let left = settle(&fake, UpdateChannel::Beta, &mut entries, &[], None).await.unwrap();
    assert!(left.is_empty());
    assert_eq!(entries[0].version_id.as_deref(), Some("s-0812"));
}

#[tokio::test]
async fn gives_up_after_the_budget_and_reports_the_conflict() {
    let mut fake = Fake::default().with("iris", "i1", "release", iris("1.10.7"));
    for n in (0..12).rev() {
        fake = fake.with("sodium", &format!("s{n}"), "release", sodium(&format!("0.8.{n}"), "<=1.10.7"));
    }
    let mut entries = vec![planned(&fake, "sodium", "s11"), planned(&fake, "iris", "i1")];
    let left = settle(&fake, UpdateChannel::Release, &mut entries, &[], None).await.unwrap();
    assert_eq!(left.len(), 1);
    assert_eq!(entries[0].version_id.as_deref(), Some("s11"));
    let calls = fake.info_calls.lock().unwrap();
    assert_eq!(calls.iter().filter(|c| c.starts_with('s')).count(), MAX_TRIES_PER_PROJECT);
}

#[tokio::test]
async fn newer_version_of_the_other_mod_or_preferred_one() {
    let fake = modrinth_like();
    // Sodium ist fest (z. B. von einer anderen Mod verlangt): dann Iris neuer.
    let mut entries = vec![
        Entry { adjustable: false, ..planned(&fake, "AANobbMI", "s-0812") },
        planned(&fake, "YL57xq9U", "i-1106"),
    ];
    let left = settle(&fake, UpdateChannel::Release, &mut entries, &[], None).await.unwrap();
    assert!(left.is_empty());
    assert_eq!(entries[1].version_id.as_deref(), Some("i-1107"));
    assert_eq!(entries[1].because.as_deref(), Some("Sodium 0.8.12+mc1.21.11"));

    // Beide tauschbar, Iris bevorzugt (aus der Absturz-Meldung): Iris wird getauscht.
    let mut entries = vec![planned(&fake, "AANobbMI", "s-0812"), planned(&fake, "YL57xq9U", "i-1106")];
    settle(&fake, UpdateChannel::Release, &mut entries, &[], Some("iris")).await.unwrap();
    assert_eq!(entries[0].version_id.as_deref(), Some("s-0812"));
    assert_eq!(entries[1].version_id.as_deref(), Some("i-1107"));
}

fn fabric_instance() -> Instance {
    Instance {
        id: "t".into(),
        name: "T".into(),
        game_version: "1.21.11".into(),
        loader: Loader { kind: LoaderKind::Fabric, version: Some("0.19.5".into()) },
        created_at: chrono::Utc::now(),
        last_played: None,
        total_play_seconds: 0,
        icon: None,
        group: None,
        overrides: Default::default(),
    }
}

#[tokio::test]
async fn update_check_holds_back_breaking_updates_and_fixes_broken_sets() {
    let fake = modrinth_like();
    let inst = fabric_instance();
    assert_eq!(loader_builtins(&inst)[0].version, "0.19.5");

    // Installiert passt; das Sodium-Update würde Iris brechen → fällt weg.
    let entries = vec![installed(&fake, "AANobbMI", "s-0812", "sodium.jar"), installed(&fake, "YL57xq9U", "i-1107", "iris.jar")];
    let s0814 = fake.versions["AANobbMI"].iter().find(|v| v.id == "s-0814").unwrap().clone();
    let out = vet_updates(&fake, &inst, entries, HashMap::from([("sodium.jar".to_owned(), s0814.clone())])).await.unwrap();
    assert!(out.is_empty(), "{:?}", out.keys());

    // Ein harmloses Update bleibt (auch für deaktivierte Mods, die nicht geprüft werden).
    let entries = vec![installed(&fake, "AANobbMI", "s-0811", "sodium.jar"), installed(&fake, "YL57xq9U", "i-1107", "iris.jar")];
    let s0812 = fake.versions["AANobbMI"].iter().find(|v| v.id == "s-0812").unwrap().clone();
    let updates = HashMap::from([("sodium.jar".to_owned(), s0812), ("off.jar".to_owned(), s0814)]);
    let out = vet_updates(&fake, &inst, entries, updates).await.unwrap();
    assert_eq!(out["sodium.jar"].0.id, "s-0812");
    assert_eq!(out["sodium.jar"].1, None);
    assert!(out.contains_key("off.jar"));

    // Kaputt wie beim Nutzer (Sodium 0.8.14 + Iris 1.10.7): Tausch-Vorschlag auf 0.8.12.
    let entries = vec![installed(&fake, "AANobbMI", "s-0814", "sodium.jar"), installed(&fake, "YL57xq9U", "i-1107", "iris.jar")];
    let out = vet_updates(&fake, &inst, entries, HashMap::new()).await.unwrap();
    assert_eq!(out.len(), 1);
    let (v, because) = &out["sodium.jar"];
    assert_eq!(v.id, "s-0812");
    assert_eq!(because.as_deref(), Some("Iris 1.10.7+mc1.21.11"));
}

#[test]
fn maven_breaks_between_forge_mods() {
    let mut embeddium = info("embeddium", "Embeddium", "0.3.31+mc1.20.1", &[], &[]);
    embeddium.scheme = Scheme::Maven;
    embeddium.breaks = vec![Constraint { id: "oculus".into(), any_of: vec!["(,1.6.9]".into()] }];
    let mut oculus = info("oculus", "Oculus", "1.6.9", &[], &[]);
    oculus.scheme = Scheme::Maven;
    let entries = [Entry { mods: vec![embeddium.clone()], ..Default::default() }, Entry { mods: vec![oculus], ..Default::default() }];
    assert_eq!(find_conflicts(&entries, &[]).len(), 1);
    let mut newer = info("oculus", "Oculus", "1.7.0", &[], &[]);
    newer.scheme = Scheme::Maven;
    let entries = [Entry { mods: vec![embeddium], ..Default::default() }, Entry { mods: vec![newer], ..Default::default() }];
    assert!(find_conflicts(&entries, &[]).is_empty());
}
