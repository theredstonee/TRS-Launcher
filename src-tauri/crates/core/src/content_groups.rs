//! Eigene Gruppen im Inhalte-Tab (Name + Farbe, ein-/ausklappbar, als Ganzes
//! schaltbar) und die Herkunft „vom Modpack“ / „selbst hinzugefügt“.
//!
//! Gespeichert je Instanz in `content-groups.json`. Schlüssel ist das Projekt
//! (`p:<projekt>`, übersteht Updates mit neuem Dateinamen), sonst die Datei
//! (`f:mods/<datei>`). Wird eine Datei später erkannt (Modrinth per Hash),
//! wandert ihr Eintrag beim nächsten Laden auf den Projekt-Schlüssel.

use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::content::{self, BulkTarget, ContentKind, MAX_BULK_ITEMS, Source};
use crate::instance::validate_id;
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

pub const MAX_GROUPS: usize = 50;
pub const MAX_GROUP_NAME: usize = 40;
/// Mehr Einträge hat keine Instanz – schützt vor kaputten Dateien.
const MAX_ENTRIES: usize = 20_000;
const FILE_NAME: &str = "content-groups.json";

/// Serialisiert Lesen-Ändern-Schreiben (Tab und Modpack-Installation parallel).
static LOCK: Mutex<()> = Mutex::const_new(());

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum GroupColor {
    Red,
    Orange,
    Yellow,
    Green,
    Cyan,
    Blue,
    Purple,
    Pink,
    Gray,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContentGroup {
    pub id: String,
    pub name: String,
    pub color: GroupColor,
    #[serde(default)]
    pub collapsed: bool,
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct GroupsFile {
    #[serde(default)]
    groups: Vec<ContentGroup>,
    /// Schlüssel (siehe Modul-Doku) → Gruppen-ID.
    #[serde(default)]
    members: BTreeMap<String, String>,
    /// Schlüssel der Inhalte, die ein Modpack mitgebracht hat.
    #[serde(default)]
    from_pack: BTreeSet<String>,
    /// Die Instanz stammt aus einem Modpack und die Herkunft wurde erfasst.
    #[serde(default)]
    pack_known: bool,
}

/// Änderungen an einer Gruppe – `None` = bleibt.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GroupPatch {
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub color: Option<GroupColor>,
    #[serde(default)]
    pub collapsed: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ContentRef {
    pub kind: ContentKind,
    pub file_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GroupAssignment {
    pub kind: ContentKind,
    pub file_name: String,
    pub group_id: String,
}

/// Gruppen und Herkunft, aufgelöst auf die Dateien, die gerade da sind.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ContentOrganization {
    pub groups: Vec<ContentGroup>,
    pub assignments: Vec<GroupAssignment>,
    pub from_pack: Vec<ContentRef>,
    /// Herkunft bekannt (Modpack-Instanz) – sonst zeigt der Tab keinen Filter dafür.
    pub pack_known: bool,
}

/// Eine vorhandene Datei mit Herkunft (aus dem Inhalts-Index).
#[derive(Debug, Clone)]
pub(crate) struct FileRef {
    pub kind: ContentKind,
    pub file_name: String,
    pub source: Option<Source>,
}

fn path(paths: &Paths, instance_id: &str) -> PathBuf {
    paths.instance_dir(instance_id).join(FILE_NAME)
}

fn file_key(kind: ContentKind, file_name: &str) -> String {
    format!("f:{}", content::index_key(kind, file_name))
}

/// Schlüssel eines Inhalts: Projekt, wenn bekannt, sonst die Datei.
pub(crate) fn member_key(kind: ContentKind, file_name: &str, source: Option<&Source>) -> String {
    match source {
        Some(s) => format!("p:{}", s.project_key()),
        None => file_key(kind, file_name),
    }
}

async fn read(paths: &Paths, instance_id: &str) -> GroupsFile {
    let mut file: GroupsFile = fsutil::read_json(&path(paths, instance_id)).await.ok().flatten().unwrap_or_default();
    // Kaputte oder riesige Dateien nicht ungeprüft übernehmen.
    file.groups.retain(|g| valid_group_id(&g.id) && clean_name(&g.name).is_some());
    file.groups.truncate(MAX_GROUPS);
    if file.members.len() > MAX_ENTRIES || file.from_pack.len() > MAX_ENTRIES {
        file.members.clear();
        file.from_pack.clear();
    }
    file
}

async fn modify<T>(paths: &Paths, instance_id: &str, change: impl FnOnce(&mut GroupsFile) -> Result<T>) -> Result<T> {
    validate_id(instance_id)?;
    let _guard = LOCK.lock().await;
    let mut file = read(paths, instance_id).await;
    let before = file.clone();
    let result = change(&mut file)?;
    if file != before {
        fsutil::write_json(&path(paths, instance_id), &file).await?;
    }
    Ok(result)
}

fn valid_group_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 40 && id.bytes().all(|b| b.is_ascii_alphanumeric())
}

/// Name ohne Steuerzeichen, getrimmt, 1–40 Zeichen.
fn clean_name(name: &str) -> Option<String> {
    let name = name.trim();
    let ok = !name.is_empty() && name.chars().count() <= MAX_GROUP_NAME && !name.chars().any(char::is_control);
    ok.then(|| name.to_owned())
}

fn invalid_name() -> Error {
    Error::validation(crate::msg!("contentGroups.invalidName", "Der Gruppenname muss 1–40 Zeichen lang sein."))
}

fn unknown_group() -> Error {
    Error::validation(crate::msg!("contentGroups.unknownGroup", "Diese Gruppe gibt es nicht mehr."))
}

/// Datei-Schlüssel, deren Herkunft inzwischen bekannt ist, auf das Projekt
/// umstellen; Einträge verschwundener Dateien und gelöschter Gruppen entfernen.
/// `true`, wenn sich etwas geändert hat.
fn reconcile(file: &mut GroupsFile, present: &[FileRef]) -> bool {
    let before = file.clone();
    for f in present {
        let Some(source) = &f.source else { continue };
        let (old, new) = (file_key(f.kind, &f.file_name), member_key(f.kind, &f.file_name, Some(source)));
        if let Some(group) = file.members.remove(&old) {
            file.members.entry(new.clone()).or_insert(group);
        }
        if file.from_pack.remove(&old) {
            file.from_pack.insert(new);
        }
    }
    let on_disk: HashSet<String> = present.iter().map(|f| file_key(f.kind, &f.file_name)).collect();
    let groups: HashSet<&str> = file.groups.iter().map(|g| g.id.as_str()).collect();
    file.members.retain(|k, g| groups.contains(g.as_str()) && (!k.starts_with("f:") || on_disk.contains(k)));
    file.from_pack.retain(|k| !k.starts_with("f:") || on_disk.contains(k));
    *file != before
}

fn resolve(file: &GroupsFile, present: &[FileRef]) -> ContentOrganization {
    let mut out = ContentOrganization { groups: file.groups.clone(), pack_known: file.pack_known, ..Default::default() };
    for f in present {
        let key = member_key(f.kind, &f.file_name, f.source.as_ref());
        if let Some(group) = file.members.get(&key) {
            out.assignments.push(GroupAssignment { kind: f.kind, file_name: f.file_name.clone(), group_id: group.clone() });
        }
        if file.from_pack.contains(&key) {
            out.from_pack.push(ContentRef { kind: f.kind, file_name: f.file_name.clone() });
        }
    }
    out
}

/// Alle Inhaltsdateien (aktiviert oder nicht) mit Herkunft – ohne die Jars zu lesen.
pub(crate) async fn present_files(paths: &Paths, instance_id: &str) -> Result<Vec<FileRef>> {
    validate_id(instance_id)?;
    let index = content::read_index(paths, instance_id).await;
    let mut out = Vec::new();
    for kind in ContentKind::ALL {
        let dir = content::content_dir(paths, instance_id, kind);
        let mut entries = match tokio::fs::read_dir(&dir).await {
            Ok(entries) => entries,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(e) => return Err(Error::io(&dir, e)),
        };
        while let Some(entry) = entries.next_entry().await.map_err(|e| Error::io(&dir, e))? {
            let Some(raw) = entry.file_name().to_str().map(str::to_owned) else { continue };
            if !entry.file_type().await.is_ok_and(|t| t.is_file()) {
                continue;
            }
            let file_name = raw.strip_suffix(content::DISABLED_SUFFIX).map_or(raw.clone(), str::to_owned);
            if content::validate_file_name(kind, &file_name).is_err() {
                continue;
            }
            let source = index.files.get(&content::index_key(kind, &file_name)).cloned();
            out.push(FileRef { kind, file_name, source });
        }
    }
    Ok(out)
}

/// Gruppen und Herkunft für den Inhalte-Tab.
pub async fn organization(paths: &Paths, instance_id: &str) -> Result<ContentOrganization> {
    validate_id(instance_id)?;
    let present = present_files(paths, instance_id).await?;
    // Geteilte Modpacks (vor dieser Funktion installiert) kennen ihre Dateien schon.
    let link = crate::pack_share::read_link(paths, instance_id).await;
    modify(paths, instance_id, |file| {
        if !file.pack_known
            && let Some(link) = link.filter(|l| l.role == crate::pack_share::PackRole::Installed)
        {
            let listed: HashSet<&str> = link.files.keys().map(String::as_str).collect();
            for f in &present {
                if listed.contains(content::index_key(f.kind, &f.file_name).as_str()) {
                    file.from_pack.insert(member_key(f.kind, &f.file_name, f.source.as_ref()));
                }
            }
            file.pack_known = true;
        }
        reconcile(file, &present);
        Ok(resolve(file, &present))
    })
    .await
}

pub async fn create_group(paths: &Paths, instance_id: &str, name: &str, color: GroupColor) -> Result<ContentGroup> {
    let name = clean_name(name).ok_or_else(invalid_name)?;
    modify(paths, instance_id, |file| {
        if file.groups.len() >= MAX_GROUPS {
            return Err(Error::validation(crate::msg!(
                "contentGroups.tooMany",
                "Höchstens {max} Gruppen je Instanz",
                max = MAX_GROUPS
            )));
        }
        let group = ContentGroup { id: uuid::Uuid::new_v4().simple().to_string(), name, color, collapsed: false };
        file.groups.push(group.clone());
        Ok(group)
    })
    .await
}

pub async fn update_group(paths: &Paths, instance_id: &str, group_id: &str, patch: GroupPatch) -> Result<ContentGroup> {
    let name = patch.name.as_deref().map(|n| clean_name(n).ok_or_else(invalid_name)).transpose()?;
    modify(paths, instance_id, |file| {
        let group = file.groups.iter_mut().find(|g| g.id == group_id).ok_or_else(unknown_group)?;
        if let Some(name) = name {
            group.name = name;
        }
        if let Some(color) = patch.color {
            group.color = color;
        }
        if let Some(collapsed) = patch.collapsed {
            group.collapsed = collapsed;
        }
        Ok(group.clone())
    })
    .await
}

/// Löscht die Gruppe – ihre Inhalte bleiben, nur ohne Gruppe.
pub async fn delete_group(paths: &Paths, instance_id: &str, group_id: &str) -> Result<()> {
    modify(paths, instance_id, |file| {
        file.groups.retain(|g| g.id != group_id);
        file.members.retain(|_, g| g != group_id);
        Ok(())
    })
    .await
}

/// Inhalte einer Gruppe zuordnen (`None` = aus ihrer Gruppe nehmen).
pub async fn assign(paths: &Paths, instance_id: &str, targets: &[BulkTarget], group_id: Option<&str>) -> Result<usize> {
    validate_id(instance_id)?;
    if targets.len() > MAX_BULK_ITEMS {
        return Err(Error::validation(crate::msg!(
            "content.tooManyItems",
            "Höchstens {max} Einträge auf einmal",
            max = MAX_BULK_ITEMS
        )));
    }
    for t in targets {
        content::validate_file_name(t.kind, &t.file_name)?;
    }
    let index = content::read_index(paths, instance_id).await;
    let keys: Vec<String> = targets
        .iter()
        .map(|t| member_key(t.kind, &t.file_name, index.files.get(&content::index_key(t.kind, &t.file_name))))
        .collect();
    modify(paths, instance_id, |file| {
        if let Some(id) = group_id
            && !file.groups.iter().any(|g| g.id == id)
        {
            return Err(unknown_group());
        }
        if file.members.len() + keys.len() > MAX_ENTRIES {
            return Err(Error::validation(crate::msg!("contentGroups.tooManyEntries", "Zu viele Einträge in Gruppen.")));
        }
        for key in &keys {
            match group_id {
                Some(id) => {
                    file.members.insert(key.clone(), id.to_owned());
                }
                None => {
                    file.members.remove(key);
                }
            }
        }
        Ok(keys.len())
    })
    .await
}

/// Nach einer Modpack-Installation: alles, was jetzt in der Instanz liegt, kam
/// aus dem Pack (der TRS Client kommt erst beim Start dazu). `extra`: weitere
/// Projekte des Packs, die der Nutzer noch selbst laden muss (CurseForge).
pub(crate) async fn record_pack_contents(paths: &Paths, instance_id: &str, extra: &[String]) -> Result<()> {
    let present = present_files(paths, instance_id).await?;
    modify(paths, instance_id, |file| {
        for f in present.iter().filter(|f| !crate::client_mod::is_client_mod_file(f.kind, &f.file_name)) {
            file.from_pack.insert(member_key(f.kind, &f.file_name, f.source.as_ref()));
        }
        file.from_pack.extend(extra.iter().map(|project| format!("p:{project}")));
        file.pack_known = true;
        Ok(())
    })
    .await
}

/// Nach dem Update eines geteilten Modpacks: diese Dateien (relativ zum
/// Spielordner, z. B. `mods/x.jar`) gehören zum Pack.
pub(crate) async fn record_pack_paths(paths: &Paths, instance_id: &str, files: &[String]) -> Result<()> {
    let present = present_files(paths, instance_id).await?;
    let listed: HashSet<&str> = files.iter().map(String::as_str).collect();
    modify(paths, instance_id, |file| {
        for f in present.iter().filter(|f| listed.contains(content::index_key(f.kind, &f.file_name).as_str())) {
            file.from_pack.insert(member_key(f.kind, &f.file_name, f.source.as_ref()));
        }
        file.pack_known = true;
        Ok(())
    })
    .await
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn setup() -> (tempfile::TempDir, Paths) {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        for kind in [ContentKind::Mod, ContentKind::ResourcePack] {
            fsutil::ensure_dir(&content::content_dir(&paths, "test", kind)).await.unwrap();
        }
        (dir, paths)
    }

    fn touch(paths: &Paths, kind: ContentKind, name: &str) {
        std::fs::write(content::content_dir(paths, "test", kind).join(name), b"x").unwrap();
    }

    fn target(kind: ContentKind, name: &str) -> BulkTarget {
        BulkTarget { kind, file_name: name.into() }
    }

    fn src(project: &str) -> Source {
        Source::modrinth(project.into(), "v1".into(), None)
    }

    #[tokio::test]
    async fn groups_survive_updates_by_project_key() {
        let (_dir, paths) = setup().await;
        touch(&paths, ContentKind::Mod, "sodium-0.5.jar");
        touch(&paths, ContentKind::Mod, "local.jar.disabled");
        touch(&paths, ContentKind::ResourcePack, "faithful.zip");
        content::remember_source(&paths, "test", ContentKind::Mod, "sodium-0.5.jar", src("AANobbMI")).await.unwrap();

        let perf = create_group(&paths, "test", "  Performance ", GroupColor::Green).await.unwrap();
        assert_eq!(perf.name, "Performance");
        let looks = create_group(&paths, "test", "Optik", GroupColor::Purple).await.unwrap();
        let n = assign(
            &paths,
            "test",
            &[target(ContentKind::Mod, "sodium-0.5.jar"), target(ContentKind::Mod, "local.jar")],
            Some(&perf.id),
        )
        .await
        .unwrap();
        assert_eq!(n, 2);
        assign(&paths, "test", &[target(ContentKind::ResourcePack, "faithful.zip")], Some(&looks.id)).await.unwrap();

        // Update: neue Datei, gleiches Projekt – bleibt in der Gruppe.
        std::fs::remove_file(content::content_dir(&paths, "test", ContentKind::Mod).join("sodium-0.5.jar")).unwrap();
        touch(&paths, ContentKind::Mod, "sodium-0.6.jar");
        content::remember_source(&paths, "test", ContentKind::Mod, "sodium-0.6.jar", src("AANobbMI")).await.unwrap();

        let org = organization(&paths, "test").await.unwrap();
        let mut got: Vec<(String, String)> = org.assignments.iter().map(|a| (a.file_name.clone(), a.group_id.clone())).collect();
        got.sort();
        assert_eq!(
            got,
            [
                ("faithful.zip".into(), looks.id.clone()),
                ("local.jar".into(), perf.id.clone()),
                ("sodium-0.6.jar".into(), perf.id.clone())
            ]
        );
        assert!(!org.pack_known);

        // Gruppe löschen: Inhalte bleiben, nur ohne Gruppe.
        delete_group(&paths, "test", &looks.id).await.unwrap();
        let org = organization(&paths, "test").await.unwrap();
        assert_eq!(org.groups.len(), 1);
        assert!(org.assignments.iter().all(|a| a.group_id == perf.id));

        // Aus der Gruppe nehmen.
        assign(&paths, "test", &[target(ContentKind::Mod, "local.jar")], None).await.unwrap();
        assert_eq!(organization(&paths, "test").await.unwrap().assignments.len(), 1);
    }

    #[tokio::test]
    async fn file_keys_move_to_project_once_identified() {
        let (_dir, paths) = setup().await;
        touch(&paths, ContentKind::Mod, "lithium.jar");
        record_pack_contents(&paths, "test", &["cf:1234".into()]).await.unwrap();
        let g = create_group(&paths, "test", "Basis", GroupColor::Blue).await.unwrap();
        assign(&paths, "test", &[target(ContentKind::Mod, "lithium.jar")], Some(&g.id)).await.unwrap();

        // Später per Hash erkannt …
        content::remember_source(&paths, "test", ContentKind::Mod, "lithium.jar", src("gvQqBUqZ")).await.unwrap();
        let org = organization(&paths, "test").await.unwrap();
        assert_eq!(org.from_pack, [ContentRef { kind: ContentKind::Mod, file_name: "lithium.jar".into() }]);
        let saved = read(&paths, "test").await;
        assert!(saved.members.contains_key("p:gvQqBUqZ") && saved.from_pack.contains("p:gvQqBUqZ"));
        assert!(saved.from_pack.contains("p:cf:1234"), "noch nicht geladene Pack-Dateien bleiben vorgemerkt");

        // … und dann aktualisiert: Herkunft und Gruppe bleiben.
        std::fs::remove_file(content::content_dir(&paths, "test", ContentKind::Mod).join("lithium.jar")).unwrap();
        touch(&paths, ContentKind::Mod, "lithium-new.jar");
        content::remember_source(&paths, "test", ContentKind::Mod, "lithium-new.jar", src("gvQqBUqZ")).await.unwrap();
        // Von Hand hinzugefügt: nicht vom Pack.
        touch(&paths, ContentKind::Mod, "mine.jar");
        let org = organization(&paths, "test").await.unwrap();
        assert!(org.pack_known);
        assert_eq!(org.from_pack, [ContentRef { kind: ContentKind::Mod, file_name: "lithium-new.jar".into() }]);
        assert_eq!(org.assignments.len(), 1);
    }

    #[tokio::test]
    async fn pack_contents_skip_trs_client_and_shared_pack_links() {
        let (_dir, paths) = setup().await;
        touch(&paths, ContentKind::Mod, "trsclient.jar");
        touch(&paths, ContentKind::Mod, "a.jar");
        touch(&paths, ContentKind::Mod, "b.jar");
        record_pack_paths(&paths, "test", &["mods/a.jar".into(), "config/x.toml".into()]).await.unwrap();
        let org = organization(&paths, "test").await.unwrap();
        assert_eq!(org.from_pack, [ContentRef { kind: ContentKind::Mod, file_name: "a.jar".into() }]);

        record_pack_contents(&paths, "test", &[]).await.unwrap();
        let mut names: Vec<String> = organization(&paths, "test").await.unwrap().from_pack.into_iter().map(|r| r.file_name).collect();
        names.sort();
        assert_eq!(names, ["a.jar", "b.jar"]);
    }

    #[tokio::test]
    async fn validates_names_ids_and_limits() {
        let (_dir, paths) = setup().await;
        for bad in ["", "   ", "a\nb", &"x".repeat(41)] {
            assert!(create_group(&paths, "test", bad, GroupColor::Red).await.is_err(), "{bad:?}");
        }
        assert!(create_group(&paths, "../x", "Ok", GroupColor::Red).await.is_err());
        let g = create_group(&paths, "test", "Ok", GroupColor::Red).await.unwrap();
        let patched = update_group(
            &paths,
            "test",
            &g.id,
            GroupPatch { name: Some("Neu".into()), color: Some(GroupColor::Gray), collapsed: Some(true) },
        )
        .await
        .unwrap();
        assert_eq!((patched.name.as_str(), patched.color, patched.collapsed), ("Neu", GroupColor::Gray, true));
        assert!(update_group(&paths, "test", "fehlt", GroupPatch::default()).await.is_err());
        assert!(update_group(&paths, "test", &g.id, GroupPatch { name: Some(" ".into()), ..Default::default() }).await.is_err());
        assert!(assign(&paths, "test", &[target(ContentKind::Mod, "../a.jar")], Some(&g.id)).await.is_err());
        assert!(assign(&paths, "test", &[target(ContentKind::Mod, "a.jar")], Some("fehlt")).await.is_err());
        for i in 1..MAX_GROUPS {
            create_group(&paths, "test", &format!("G{i}"), GroupColor::Blue).await.unwrap();
        }
        assert!(create_group(&paths, "test", "Zu viel", GroupColor::Blue).await.is_err());
    }

    #[test]
    fn reconcile_drops_vanished_files_and_groups() {
        let mut file = GroupsFile {
            groups: vec![ContentGroup { id: "g1".into(), name: "A".into(), color: GroupColor::Red, collapsed: false }],
            members: BTreeMap::from([
                ("f:mods/gone.jar".into(), "g1".into()),
                ("f:mods/here.jar".into(), "g1".into()),
                ("p:proj".into(), "g1".into()),
                ("f:mods/orphan.jar".into(), "g2".into()),
            ]),
            from_pack: BTreeSet::from(["f:mods/gone.jar".into(), "p:other".into()]),
            pack_known: true,
        };
        let present = [
            FileRef { kind: ContentKind::Mod, file_name: "here.jar".into(), source: None },
            FileRef { kind: ContentKind::Mod, file_name: "orphan.jar".into(), source: None },
        ];
        assert!(reconcile(&mut file, &present));
        assert_eq!(file.members.keys().collect::<Vec<_>>(), ["f:mods/here.jar", "p:proj"]);
        assert_eq!(file.from_pack.iter().collect::<Vec<_>>(), ["p:other"]);
        assert!(!reconcile(&mut file, &present), "zweiter Durchlauf ändert nichts");
    }
}
