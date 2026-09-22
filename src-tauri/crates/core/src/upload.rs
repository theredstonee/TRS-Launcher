//! Inhalte von der Platte hinzufügen – per Dateidialog oder Drag & Drop.
//!
//! Die Pfade kommen immer aus nativen Quellen (Dialog bzw. Drop-Ereignis des
//! Fensters), nie aus dem Webview. Geprüft wird trotzdem alles: Endung,
//! ZIP-Kopf (Magic Bytes), Größe, Inhalt (`pack.mcmeta` bzw. `shaders/`) und
//! der Dateiname. Kopiert wird über eine Temp-Datei in den passenden Ordner.

use std::io::Read;
use std::path::{Path, PathBuf};

use serde::Serialize;

use crate::content::{self, ContentKind};
use crate::history::{self, HistoryEntry, HistoryKind};
use crate::instance::{Instance, LoaderKind};
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

pub const MAX_UPLOAD_BYTES: u64 = 200 * 1024 * 1024;
pub const MAX_UPLOAD_FILES: usize = 50;
/// Mehr Einträge braucht kein Paket zum Erkennen.
const MAX_SCANNED_ENTRIES: usize = 20_000;
const ZIP_MAGIC: &[u8; 4] = b"PK\x03\x04";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadResult {
    pub file_name: String,
    pub kind: Option<ContentKind>,
    /// Nutzertaugliche Meldung, falls die Datei nicht übernommen wurde.
    pub error: Option<String>,
}

/// Erkennt die Inhaltsart: `.jar` = Mod; `.zip` mit `pack.mcmeta` =
/// Ressourcenpaket; `.zip` mit `shaders/` = Shader.
pub fn classify(path: &Path) -> Result<ContentKind> {
    let meta = std::fs::metadata(path).map_err(|e| Error::io(path, e))?;
    if !meta.is_file() {
        return Err(Error::validation("Ordner werden nicht unterstützt – bitte die .zip bzw. .jar wählen."));
    }
    if meta.len() == 0 || meta.len() > MAX_UPLOAD_BYTES {
        return Err(Error::validation("Die Datei ist leer oder größer als 200 MB."));
    }
    let ext = path.extension().and_then(|e| e.to_str()).unwrap_or_default().to_ascii_lowercase();
    if ext != "jar" && ext != "zip" {
        return Err(Error::validation("Nur .jar- und .zip-Dateien können hinzugefügt werden."));
    }

    let mut file = std::fs::File::open(path).map_err(|e| Error::io(path, e))?;
    let mut magic = [0u8; 4];
    if file.read_exact(&mut magic).is_err() || &magic != ZIP_MAGIC {
        return Err(Error::validation("Die Datei ist kein gültiges Archiv."));
    }
    let archive = zip::ZipArchive::new(std::fs::File::open(path).map_err(|e| Error::io(path, e))?)
        .map_err(|_| Error::validation("Die Datei ist kein gültiges Archiv."))?;

    if ext == "jar" {
        return Ok(ContentKind::Mod);
    }
    let names: Vec<&str> = archive.file_names().take(MAX_SCANNED_ENTRIES).collect();
    if names.iter().any(|n| n.eq_ignore_ascii_case("pack.mcmeta")) {
        Ok(ContentKind::ResourcePack)
    } else if names.iter().any(|n| n.starts_with("shaders/")) {
        Ok(ContentKind::ShaderPack)
    } else {
        Err(Error::validation("Weder Ressourcenpaket (pack.mcmeta) noch Shader (shaders/) erkannt."))
    }
}

fn mods_allowed(instance: &Instance) -> bool {
    instance.loader.kind != LoaderKind::Vanilla || crate::boost::wants_boost(instance)
}

async fn import_one(paths: &Paths, instance: &Instance, source: PathBuf, file_name: &str) -> Result<ContentKind> {
    let probe = source.clone();
    let kind = tokio::task::spawn_blocking(move || classify(&probe))
        .await
        .map_err(|e| Error::Internal(e.to_string()))??;
    if kind == ContentKind::Mod && !mods_allowed(instance) {
        return Err(Error::validation("Diese Instanz ist echtes Vanilla – Mods laufen hier nicht."));
    }
    content::validate_file_name(kind, file_name)?;
    if content::existing_file(paths, &instance.id, kind, file_name).is_some() {
        return Err(Error::validation("Eine Datei mit diesem Namen gibt es in der Instanz schon."));
    }
    let dir = content::content_dir(paths, &instance.id, kind);
    fsutil::ensure_dir(&dir).await?;
    let target = dir.join(file_name);
    let tmp = dir.join(format!(".{}.trs-upload", uuid::Uuid::new_v4().simple()));
    if let Err(e) = tokio::fs::copy(&source, &tmp).await {
        let _ = tokio::fs::remove_file(&tmp).await;
        return Err(Error::io(&source, e));
    }
    // Zwischen Prüfung und Kopie könnte die Quelle gewachsen sein.
    let copied = tokio::fs::metadata(&tmp).await.map(|m| m.len()).unwrap_or(u64::MAX);
    if copied > MAX_UPLOAD_BYTES {
        let _ = tokio::fs::remove_file(&tmp).await;
        return Err(Error::validation("Die Datei ist größer als 200 MB."));
    }
    if let Err(e) = tokio::fs::rename(&tmp, &target).await {
        let _ = tokio::fs::remove_file(&tmp).await;
        return Err(Error::io(&target, e));
    }
    Ok(kind)
}

/// Kopiert die Dateien in die passenden Ordner der Instanz. Fehler je Datei
/// landen im Ergebnis; ein Verlaufseintrag fasst alles zusammen.
pub async fn import_files(paths: &Paths, instance: &Instance, files: Vec<PathBuf>) -> Result<Vec<UploadResult>> {
    if files.len() > MAX_UPLOAD_FILES {
        return Err(Error::validation(format!("Höchstens {MAX_UPLOAD_FILES} Dateien auf einmal.")));
    }
    let mut results = Vec::new();
    for source in files {
        let Some(file_name) = source.file_name().and_then(|n| n.to_str()).map(str::to_owned) else {
            results.push(UploadResult { file_name: "?".into(), kind: None, error: Some("Ungültiger Dateiname".into()) });
            continue;
        };
        match import_one(paths, instance, source, &file_name).await {
            Ok(kind) => results.push(UploadResult { file_name, kind: Some(kind), error: None }),
            Err(e) => {
                tracing::info!("Datei {file_name} nicht übernommen: {e}");
                results.push(UploadResult { file_name, kind: None, error: Some(e.public_message()) });
            }
        }
    }
    let added: Vec<&UploadResult> = results.iter().filter(|r| r.error.is_none()).collect();
    if let Some(first) = added.first() {
        let entry = HistoryEntry::new(HistoryKind::FilesAdded).subject(&first.file_name).to(added.len().to_string());
        history::record(paths, &instance.id, entry).await;
    }
    Ok(results)
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use chrono::Utc;

    use super::*;
    use crate::instance::{InstanceOverrides, Loader};

    fn zip_file(path: &Path, entries: &[&str]) {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        for name in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(b"x").unwrap();
        }
        zip.finish().unwrap();
    }

    fn instance(kind: LoaderKind, boost: Option<bool>) -> Instance {
        Instance {
            id: "test".into(),
            name: "Test".into(),
            game_version: "1.21.1".into(),
            loader: Loader { kind, version: None },
            created_at: Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            overrides: InstanceOverrides { boost, ..Default::default() },
            icon: None,
            group: None,
        }
    }

    #[test]
    fn classifies_by_magic_and_content() {
        let dir = tempfile::tempdir().unwrap();
        let p = |n: &str| dir.path().join(n);
        zip_file(&p("mod.jar"), &["fabric.mod.json"]);
        zip_file(&p("pack.zip"), &["pack.mcmeta", "assets/x.png"]);
        zip_file(&p("shader.zip"), &["shaders/final.fsh"]);
        zip_file(&p("anderes.zip"), &["readme.txt"]);
        std::fs::write(p("fake.jar"), b"MZ\x90\x00 kein zip").unwrap();
        std::fs::write(p("leer.jar"), b"").unwrap();
        zip_file(&p("mod.exe"), &["a"]);

        assert_eq!(classify(&p("mod.jar")).unwrap(), ContentKind::Mod);
        assert_eq!(classify(&p("pack.zip")).unwrap(), ContentKind::ResourcePack);
        assert_eq!(classify(&p("shader.zip")).unwrap(), ContentKind::ShaderPack);
        for bad in ["anderes.zip", "fake.jar", "leer.jar", "mod.exe", "fehlt.jar"] {
            assert!(classify(&p(bad)).is_err(), "{bad}");
        }
        assert!(classify(dir.path()).is_err(), "Ordner");
    }

    #[tokio::test]
    async fn imports_into_the_right_folders() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("data"));
        paths.ensure().await.unwrap();
        fsutil::write_json(&paths.instance_file("test"), &serde_json::json!({})).await.unwrap();
        let src = dir.path().join("src");
        std::fs::create_dir_all(&src).unwrap();
        zip_file(&src.join("sodium.jar"), &["fabric.mod.json"]);
        zip_file(&src.join("Faithful.zip"), &["pack.mcmeta"]);
        zip_file(&src.join("BSL.zip"), &["shaders/a.fsh"]);
        std::fs::write(src.join("virus.jar"), b"MZ").unwrap();

        let files = ["sodium.jar", "Faithful.zip", "BSL.zip", "virus.jar"].map(|n| src.join(n)).to_vec();
        let results = import_files(&paths, &instance(LoaderKind::Fabric, None), files.clone()).await.unwrap();
        assert_eq!(results.iter().filter(|r| r.error.is_none()).count(), 3);
        let game = paths.instance_game_dir("test");
        assert!(game.join("mods/sodium.jar").is_file());
        assert!(game.join("resourcepacks/Faithful.zip").is_file());
        assert!(game.join("shaderpacks/BSL.zip").is_file());
        assert!(results[3].error.is_some());

        // Zweites Mal: Namen gibt es schon.
        let again = import_files(&paths, &instance(LoaderKind::Fabric, None), files[..1].to_vec()).await.unwrap();
        assert!(again[0].error.as_deref().unwrap().contains("schon"));

        let history = history::list(&paths, "test").await.unwrap();
        assert_eq!(history[0].kind, HistoryKind::FilesAdded);
        assert_eq!(history[0].to.as_deref(), Some("3"));
    }

    #[tokio::test]
    async fn pure_vanilla_rejects_mods() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("data"));
        paths.ensure().await.unwrap();
        let jar = dir.path().join("m.jar");
        zip_file(&jar, &["a"]);
        let r = import_files(&paths, &instance(LoaderKind::Vanilla, Some(false)), vec![jar.clone()]).await.unwrap();
        assert!(r[0].error.is_some());
        // Vanilla mit TRS-Optimierung läuft als Fabric und nimmt Mods.
        let r = import_files(&paths, &instance(LoaderKind::Vanilla, None), vec![jar]).await.unwrap();
        assert!(r[0].error.is_none());
        assert!(import_files(&paths, &instance(LoaderKind::Fabric, None), vec![PathBuf::new(); 51]).await.is_err());
    }
}
