//! Server-Jar und Modloader in den Server-Ordner legen.
//!
//! - Vanilla: `server.jar` von Mojang (SHA-1 geprüft).
//! - Fabric: Vanilla-Jar + offizieller Server-Starter von meta.fabricmc.net (lädt
//!   seine Bibliotheken beim ersten Start; Fabric nennt dafür keine Prüfsumme).
//! - Quilt: Vanilla-Jar + offizieller Installer (`install server`, SHA-1 von Maven).
//! - Forge/NeoForge: offizieller Installer mit `--installServer` (SHA-1 von Maven),
//!   ausgeführt mit dem Java des Launchers.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;

use serde::Deserialize;

use super::files::{LaunchSpec, minor_version};
use crate::download::{self, Task};
use crate::instance::{Loader, LoaderKind};
use crate::meta::version::Artifact;
use crate::paths::Paths;
use crate::{Error, Result, forge, loaders};

const FABRIC_META: &str = "https://meta.fabricmc.net/v2";
const QUILT_META: &str = "https://meta.quiltmc.org/v3";
const QUILT_MAVEN: &str = "https://maven.quiltmc.org/repository/release/";
/// So lange darf ein Installer höchstens laufen.
const INSTALLER_TIMEOUT: Duration = Duration::from_secs(20 * 60);
/// So viele Ausgabe-Zeilen eines gescheiterten Installers kommen ins Log.
const LOG_TAIL_LINES: usize = 40;

pub(super) struct InstallInput<'a> {
    pub http: &'a reqwest::Client,
    pub paths: &'a Paths,
    pub game_version: &'a str,
    pub loader: &'a Loader,
    /// Server-Ordner (Ziel).
    pub dir: &'a Path,
    /// Vanilla-Server laut Version-JSON.
    pub server: &'a Artifact,
    /// Java für Installer (Konsolen-Variante); `None` für Vanilla/Fabric.
    pub installer_java: Option<&'a Path>,
    pub java_major: u32,
    pub concurrency: usize,
}

/// Ergebnis der Installation.
#[derive(Debug, Clone)]
pub(super) struct Installed {
    pub launch: LaunchSpec,
    pub loader_version: Option<String>,
    /// Der Loader lädt beim ersten Start noch Dateien (Fabric).
    pub needs_internet: bool,
}

/// Versionsangaben aus Metadaten: nur harmlose Zeichen.
pub(super) fn is_safe_version(v: &str) -> bool {
    !v.is_empty() && v.len() <= 64 && v.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'+' | b'_'))
}

fn bad_version() -> Error {
    Error::launch(crate::msg!("forge.invalidLoaderVersion", "Die Loader-Version enthält ungültige Zeichen."))
}

pub(super) async fn install(input: &InstallInput<'_>, report: &(dyn Fn(f64) + Sync)) -> Result<Installed> {
    match input.loader.kind {
        LoaderKind::Vanilla => {
            download_server_jar(input, &input.dir.join("server.jar"), report).await?;
            Ok(Installed { launch: LaunchSpec::Jar { jar: "server.jar".into() }, loader_version: None, needs_internet: false })
        }
        LoaderKind::Fabric => install_fabric(input, report).await,
        LoaderKind::Quilt => install_quilt(input, report).await,
        LoaderKind::Forge | LoaderKind::NeoForge => install_forge(input, report).await,
    }
}

async fn download_server_jar(input: &InstallInput<'_>, dest: &Path, report: &(dyn Fn(f64) + Sync)) -> Result<()> {
    let task = Task {
        url: input.server.url.clone(),
        path: dest.to_owned(),
        sha1: input.server.sha1.clone(),
        size: input.server.size,
        sha512: None,
        strict_size: true,
        pack: false,
    };
    download::fetch_all(input.http, vec![task], 1, &|p| report(p.percent())).await
}

async fn loader_version(input: &InstallInput<'_>) -> Result<String> {
    let version = match input.loader.version.clone() {
        Some(v) => v,
        None => loaders::latest_stable(input.http, input.loader.kind, input.game_version)
            .await?
            .ok_or_else(|| not_available(input.game_version))?,
    };
    if is_safe_version(&version) { Ok(version) } else { Err(bad_version()) }
}

fn not_available(game_version: &str) -> Error {
    Error::launch(crate::msg!(
        "serverExport.loaderUnavailable",
        "Für Minecraft {version} gibt es diesen Modloader nicht als Server.",
        version = game_version
    ))
}

#[derive(Deserialize)]
struct FabricInstaller {
    version: String,
    #[serde(default)]
    stable: bool,
}

async fn install_fabric(input: &InstallInput<'_>, report: &(dyn Fn(f64) + Sync)) -> Result<Installed> {
    let loader = loader_version(input).await?;
    let installers: Vec<FabricInstaller> =
        input.http.get(format!("{FABRIC_META}/versions/installer")).send().await?.error_for_status()?.json().await?;
    let installer = installers
        .iter()
        .find(|i| i.stable)
        .or(installers.first())
        .map(|i| i.version.clone())
        .filter(|v| is_safe_version(v))
        .ok_or_else(|| not_available(input.game_version))?;
    report(5.0);
    download_server_jar(input, &input.dir.join("server.jar"), &|p| report(5.0 + p * 0.75)).await?;

    // Der offizielle Server-Starter: lädt Loader-Bibliotheken beim ersten Start.
    let mc = input.game_version;
    let url = format!("{FABRIC_META}/versions/loader/{mc}/{loader}/{installer}/server/jar");
    let launcher = input.dir.join("fabric-server-launch.jar");
    let task = Task { url, path: launcher.clone(), sha1: None, size: None, sha512: None, strict_size: false, pack: false };
    download::fetch_one(input.http, &task).await?;
    if !is_jar(&launcher).await {
        let _ = tokio::fs::remove_file(&launcher).await;
        return Err(not_available(mc));
    }
    report(100.0);
    Ok(Installed {
        launch: LaunchSpec::Jar { jar: "fabric-server-launch.jar".into() },
        loader_version: Some(loader),
        needs_internet: true,
    })
}

/// Ist das ein Jar mit Manifest? (Fabric liefert keine Prüfsumme – so fällt
/// wenigstens eine Fehlerseite statt eines Jars auf.)
async fn is_jar(path: &Path) -> bool {
    let path = path.to_owned();
    tokio::task::spawn_blocking(move || {
        let Ok(file) = std::fs::File::open(&path) else { return false };
        let Ok(mut zip) = zip::ZipArchive::new(file) else { return false };
        zip.by_name("META-INF/MANIFEST.MF").is_ok()
    })
    .await
    .unwrap_or(false)
}

#[derive(Deserialize)]
struct QuiltInstaller {
    url: String,
    version: String,
}

async fn install_quilt(input: &InstallInput<'_>, report: &(dyn Fn(f64) + Sync)) -> Result<Installed> {
    let java = input.installer_java.ok_or_else(|| Error::Internal("Quilt-Installer ohne Java".into()))?;
    let loader = loader_version(input).await?;
    let installers: Vec<QuiltInstaller> =
        input.http.get(format!("{QUILT_META}/versions/installer")).send().await?.error_for_status()?.json().await?;
    let installer = installers
        .into_iter()
        .find(|i| is_safe_version(&i.version) && i.url.starts_with(QUILT_MAVEN) && i.url.ends_with(".jar"))
        .ok_or_else(|| not_available(input.game_version))?;
    let sha1 = forge::fetch_sha1(input.http, &installer.url).await;
    if sha1.is_none() {
        tracing::warn!("Keine Prüfsumme für den Quilt-Installer verfügbar");
    }
    let installer_jar = input.dir.join("quilt-installer.jar");
    let task = Task { url: installer.url, path: installer_jar.clone(), sha1, size: None, sha512: None, strict_size: false, pack: false };
    download::fetch_one(input.http, &task).await?;
    report(10.0);
    download_server_jar(input, &input.dir.join("server.jar"), &|p| report(10.0 + p * 0.3)).await?;

    let dir_arg = format!("--install-dir={}", input.dir.display());
    let args = ["-jar".to_owned(), "quilt-installer.jar".into(), "install".into(), "server".into(), input.game_version.into(), loader.clone(), dir_arg];
    report(45.0);
    run_installer(java, input.dir, &args).await?;
    let _ = tokio::fs::remove_file(&installer_jar).await;
    if !input.dir.join("quilt-server-launch.jar").is_file() {
        return Err(installer_failed());
    }
    report(100.0);
    Ok(Installed {
        launch: LaunchSpec::Jar { jar: "quilt-server-launch.jar".into() },
        loader_version: Some(loader),
        needs_internet: false,
    })
}

async fn install_forge(input: &InstallInput<'_>, report: &(dyn Fn(f64) + Sync)) -> Result<Installed> {
    let java = input.installer_java.ok_or_else(|| Error::Internal("Forge-Installer ohne Java".into()))?;
    let ctx = forge::InstallContext {
        http: input.http,
        paths: input.paths,
        game_version: input.game_version,
        loader: input.loader,
        // Für den Server unnötig – der Installer holt sich, was er braucht.
        client_jar: input.dir,
        java,
        java_major: input.java_major,
        concurrency: input.concurrency,
        runner: None,
    };
    let (cached, version) = forge::server_installer(&ctx, &|p| report(p * 0.15)).await?;
    let installer_jar = input.dir.join("installer.jar");
    tokio::fs::copy(&cached, &installer_jar).await.map_err(|e| Error::io(&installer_jar, e))?;

    // Alte Installer (bis 1.16) suchen das Vanilla-Jar im Server-Ordner – vorher geprüft hinlegen,
    // dann laden sie es nicht selbst (die alten Adressen gibt es teils nicht mehr).
    let legacy = minor_version(input.game_version).is_some_and(|m| m < 17);
    if legacy {
        let name = format!("minecraft_server.{}.jar", input.game_version);
        download_server_jar(input, &input.dir.join(name), &|p| report(15.0 + p * 0.15)).await?;
    }
    report(30.0);
    run_installer(java, input.dir, &["-jar".to_owned(), "installer.jar".into(), "--installServer".into()]).await?;
    report(95.0);

    for leftover in ["installer.jar", "installer.jar.log", "installer.log", "run.bat", "run.sh"] {
        let _ = tokio::fs::remove_file(input.dir.join(leftover)).await;
    }
    let dir = input.dir.to_owned();
    let launch = tokio::task::spawn_blocking(move || detect_forge_launch(&dir))
        .await
        .map_err(|e| Error::Internal(e.to_string()))?
        .ok_or_else(installer_failed)?;
    if matches!(launch, LaunchSpec::ArgsFile { .. }) {
        let jvm_args = input.dir.join("user_jvm_args.txt");
        if !jvm_args.is_file() {
            let text = "# Extra JVM arguments for the server (RAM is set in the start scripts)\n";
            tokio::fs::write(&jvm_args, text).await.map_err(|e| Error::io(&jvm_args, e))?;
        }
    }
    report(100.0);
    Ok(Installed { launch, loader_version: Some(version), needs_internet: false })
}

fn installer_failed() -> Error {
    Error::launch(crate::msg!(
        "serverExport.installerFailed",
        "Der Modloader-Installer ist fehlgeschlagen. Bitte Internetverbindung prüfen und erneut versuchen."
    ))
}

/// Führt einen Installer im Server-Ordner aus (ohne Fenster, abbrechbar, mit Zeitlimit).
async fn run_installer(java: &Path, dir: &Path, args: &[String]) -> Result<()> {
    let mut command = tokio::process::Command::new(java);
    crate::platform::hide_console(&mut command);
    crate::platform::env::clean_tokio(&mut command);
    command
        .args(args)
        .current_dir(dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    let run = tokio::time::timeout(INSTALLER_TIMEOUT, command.output());
    let output = match crate::task::or_cancel(run).await? {
        Ok(Ok(output)) => output,
        Ok(Err(e)) => {
            tracing::error!("Installer konnte nicht gestartet werden: {e}");
            return Err(installer_failed());
        }
        Err(_) => {
            tracing::error!("Installer hat das Zeitlimit überschritten");
            return Err(installer_failed());
        }
    };
    if !output.status.success() {
        let text = String::from_utf8_lossy(&output.stdout);
        let tail: Vec<&str> = text.lines().rev().take(LOG_TAIL_LINES).collect();
        let tail: Vec<&str> = tail.into_iter().rev().collect();
        tracing::error!(
            "Installer fehlgeschlagen ({}):\n{}\n{}",
            output.status,
            tail.join("\n"),
            String::from_utf8_lossy(&output.stderr)
        );
        return Err(installer_failed());
    }
    Ok(())
}

/// Wie das installierte (Neo)Forge startet: neue Versionen mit Argument-Dateien
/// unter `libraries/`, alte mit einem `forge-*.jar` im Server-Ordner.
pub(super) fn detect_forge_launch(dir: &Path) -> Option<LaunchSpec> {
    for base in ["net/minecraftforge/forge", "net/neoforged/neoforge", "net/neoforged/forge"] {
        let root = dir.join("libraries").join(base);
        let Ok(read) = std::fs::read_dir(&root) else { continue };
        let mut versions: Vec<PathBuf> = read.flatten().map(|e| e.path()).filter(|p| p.is_dir()).collect();
        versions.sort();
        for version in versions {
            if version.join("win_args.txt").is_file() && version.join("unix_args.txt").is_file() {
                let rel = |file: &str| -> Option<String> {
                    let path = version.join(file);
                    let rel = path.strip_prefix(dir).ok()?;
                    let parts: Vec<&str> = rel.components().filter_map(|c| c.as_os_str().to_str()).collect();
                    Some(parts.join("/"))
                };
                return Some(LaunchSpec::ArgsFile { windows: rel("win_args.txt")?, unix: rel("unix_args.txt")? });
            }
        }
    }
    let mut jars: Vec<String> = std::fs::read_dir(dir)
        .ok()?
        .flatten()
        .filter_map(|e| e.file_name().into_string().ok())
        .filter(|n| {
            let lower = n.to_ascii_lowercase();
            (lower.starts_with("forge-") || lower.starts_with("neoforge-"))
                && lower.ends_with(".jar")
                && !lower.contains("installer")
        })
        .collect();
    // Bei mehreren: das kürzeste (ohne Zusätze wie `-shim`).
    jars.sort_by_key(|n| (n.len(), n.clone()));
    jars.into_iter().next().map(|jar| LaunchSpec::Jar { jar })
}
