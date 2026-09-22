//! Start-Hooks: ein Befehl vor dem Start, ein Wrapper vor Java, ein Befehl nach
//! dem Beenden und eigene Umgebungsvariablen – wie bei Prism, Modrinth App und
//! OneLauncher. Global in den Einstellungen, pro Instanz überschreibbar.
//!
//! Befehle laufen über `cmd /C` ohne Konsolenfenster und mit Zeitlimit. Sie
//! bekommen `INST_ID`, `INST_NAME`, `INST_DIR`, `INST_MC_DIR` und `INST_JAVA`
//! als Umgebungsvariablen (dieselben Namen wie bei Prism/MultiMC).

use std::path::PathBuf;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::launch::Command;
use crate::{Error, Result};

pub const MAX_COMMAND_LEN: usize = 1024;
pub const MAX_ENV_VARS: usize = 32;
pub const MAX_ENV_KEY_LEN: usize = 64;
pub const MAX_ENV_VALUE_LEN: usize = 1024;
/// Länger darf ein Hook nicht laufen – sonst hängt der Start ewig.
pub const HOOK_TIMEOUT: Duration = Duration::from_secs(120);

const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct LaunchHooks {
    /// Läuft vor dem Start; schlägt er fehl, startet das Spiel nicht.
    pub pre_launch: Option<String>,
    /// Wird vor die Java-Kommandozeile gesetzt (z. B. `prime-run`).
    pub wrapper: Option<String>,
    /// Läuft nach dem Beenden des Spiels.
    pub post_exit: Option<String>,
    /// Zusätzliche Umgebungsvariablen für Spiel und Hooks.
    pub env: Vec<EnvVar>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EnvVar {
    pub key: String,
    pub value: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HookKind {
    PreLaunch,
    PostExit,
}

impl HookKind {
    fn label(self) -> &'static str {
        match self {
            Self::PreLaunch => "Der Befehl vor dem Start",
            Self::PostExit => "Der Befehl nach dem Beenden",
        }
    }
}

impl LaunchHooks {
    /// Leere Befehle werden zu `None`, Leerraum außen fällt weg.
    pub fn normalized(mut self) -> Self {
        let clean = |c: Option<String>| c.map(|s| s.trim().to_owned()).filter(|s| !s.is_empty());
        self.pre_launch = clean(self.pre_launch);
        self.wrapper = clean(self.wrapper);
        self.post_exit = clean(self.post_exit);
        for var in &mut self.env {
            var.key = var.key.trim().to_owned();
        }
        self
    }

    pub fn validate(&self) -> Result<()> {
        for (label, command) in [
            ("Befehl vor dem Start", &self.pre_launch),
            ("Wrapper-Befehl", &self.wrapper),
            ("Befehl nach dem Beenden", &self.post_exit),
        ] {
            if let Some(c) = command {
                validate_command(label, c)?;
            }
        }
        if self.env.len() > MAX_ENV_VARS {
            return Err(Error::validation(format!("Höchstens {MAX_ENV_VARS} Umgebungsvariablen")));
        }
        let mut seen = std::collections::HashSet::new();
        for var in &self.env {
            validate_env_key(&var.key)?;
            if var.value.len() > MAX_ENV_VALUE_LEN || var.value.chars().any(char::is_control) {
                return Err(Error::validation(format!("Der Wert von {} ist ungültig oder zu lang", var.key)));
            }
            // Windows unterscheidet bei Umgebungsvariablen nicht nach Groß/klein.
            if !seen.insert(var.key.to_ascii_uppercase()) {
                return Err(Error::validation(format!("Die Variable {} ist doppelt", var.key)));
            }
        }
        Ok(())
    }

    pub fn is_empty(&self) -> bool {
        self.pre_launch.is_none() && self.wrapper.is_none() && self.post_exit.is_none() && self.env.is_empty()
    }

    pub fn env_pairs(&self) -> Vec<(String, String)> {
        self.env.iter().map(|v| (v.key.clone(), v.value.clone())).collect()
    }
}

fn validate_command(label: &str, command: &str) -> Result<()> {
    if command.trim().is_empty() || command.len() > MAX_COMMAND_LEN {
        return Err(Error::validation(format!("{label}: höchstens {MAX_COMMAND_LEN} Zeichen")));
    }
    // Zeilenumbrüche & Co. würden in `cmd /C` weitere Befehle anhängen.
    if command.chars().any(char::is_control) {
        return Err(Error::validation(format!("{label} enthält ungültige Zeichen")));
    }
    Ok(())
}

/// `[A-Za-z_][A-Za-z0-9_]*` – kein `=`, keine Leerzeichen.
pub fn validate_env_key(key: &str) -> Result<()> {
    let mut chars = key.chars();
    let ok = key.len() <= MAX_ENV_KEY_LEN
        && chars.next().is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_');
    if ok {
        Ok(())
    } else {
        Err(Error::validation("Namen von Umgebungsvariablen: nur Buchstaben, Ziffern und _ (nicht vorne)"))
    }
}

/// Was die Hooks über die Instanz erfahren.
#[derive(Debug, Clone)]
pub struct HookContext {
    pub instance_id: String,
    pub instance_name: String,
    pub instance_dir: PathBuf,
    pub game_dir: PathBuf,
    pub java: Option<PathBuf>,
}

impl HookContext {
    fn env(&self) -> Vec<(String, String)> {
        vec![
            ("INST_ID".into(), self.instance_id.clone()),
            ("INST_NAME".into(), self.instance_name.clone()),
            ("INST_DIR".into(), self.instance_dir.display().to_string()),
            ("INST_MC_DIR".into(), self.game_dir.display().to_string()),
            ("INST_JAVA".into(), self.java.as_ref().map(|j| j.display().to_string()).unwrap_or_default()),
        ]
    }
}

/// Setzt den Wrapper vor die Java-Kommandozeile: `wrapper args… java args…`.
pub fn apply_wrapper(command: &mut Command, wrapper: &str) {
    let mut tokens = crate::launch::split_args(wrapper).into_iter();
    let Some(program) = tokens.next().filter(|p| !p.is_empty()) else { return };
    let mut args: Vec<String> = tokens.collect();
    args.push(command.program.display().to_string());
    args.append(&mut command.args);
    command.program = PathBuf::from(program);
    command.args = args;
}

/// Führt einen Hook über `cmd /C` aus. Nicht-Null-Exit-Code, Zeitüberschreitung
/// oder ein fehlendes `cmd` sind Fehler mit nutzertauglicher Meldung.
pub async fn run(kind: HookKind, command: &str, ctx: &HookContext, user_env: &[(String, String)], timeout: Duration) -> Result<()> {
    validate_command(kind.label(), command)?;
    let shell = std::env::var_os("ComSpec").filter(|s| !s.is_empty()).unwrap_or_else(|| "cmd.exe".into());
    let mut cmd = tokio::process::Command::new(shell);
    // `/S /C "…"`: cmd entfernt genau das äußere Anführungszeichenpaar und
    // übernimmt den Rest wörtlich.
    cmd.raw_arg(format!("/D /S /C \"{command}\""))
        .current_dir(if ctx.game_dir.is_dir() { &ctx.game_dir } else { &ctx.instance_dir })
        .envs(ctx.env())
        .envs(user_env.iter().cloned())
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .creation_flags(CREATE_NO_WINDOW)
        .kill_on_drop(true);

    let child = cmd.spawn().map_err(|e| {
        tracing::error!("Hook konnte nicht gestartet werden: {e}");
        Error::launch(format!("{} konnte nicht gestartet werden.", kind.label()))
    })?;
    let output = match tokio::time::timeout(timeout, child.wait_with_output()).await {
        Ok(Ok(output)) => output,
        Ok(Err(e)) => {
            tracing::error!("Hook fehlgeschlagen: {e}");
            return Err(Error::launch(format!("{} konnte nicht ausgeführt werden.", kind.label())));
        }
        // Beim Verwerfen des Futures beendet `kill_on_drop` den Prozess.
        Err(_) => {
            return Err(Error::launch(format!(
                "{} lief länger als {} Sekunden und wurde abgebrochen.",
                kind.label(),
                timeout.as_secs()
            )));
        }
    };
    let tail = |bytes: &[u8]| {
        let text = String::from_utf8_lossy(bytes);
        let start = text.len().saturating_sub(2000);
        text.get(start..).unwrap_or_default().trim().to_owned()
    };
    if !output.stdout.is_empty() {
        tracing::info!("Hook-Ausgabe ({}): {}", ctx.instance_id, tail(&output.stdout));
    }
    if !output.stderr.is_empty() {
        tracing::warn!("Hook-Fehlerausgabe ({}): {}", ctx.instance_id, tail(&output.stderr));
    }
    if output.status.success() {
        Ok(())
    } else {
        let code = output.status.code().map_or_else(|| "?".to_owned(), |c| c.to_string());
        Err(Error::launch(format!("{} ist fehlgeschlagen (Exit-Code {code}).", kind.label())))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx(dir: &std::path::Path) -> HookContext {
        HookContext {
            instance_id: "test".into(),
            instance_name: "Test".into(),
            instance_dir: dir.to_owned(),
            game_dir: dir.join("minecraft"),
            java: None,
        }
    }

    #[test]
    fn validation() {
        let ok = LaunchHooks {
            pre_launch: Some("echo hallo & exit 0".into()),
            wrapper: Some(r#""C:\Program Files\x\wrap.exe" --flag"#.into()),
            post_exit: None,
            env: vec![EnvVar { key: "_JAVA_OPTIONS".into(), value: "-Dx=1".into() }],
        };
        ok.validate().unwrap();

        let bad_cmd = LaunchHooks { pre_launch: Some("echo a\r\ndel x".into()), ..Default::default() };
        assert!(bad_cmd.validate().is_err());
        let long = LaunchHooks { post_exit: Some("x".repeat(MAX_COMMAND_LEN + 1)), ..Default::default() };
        assert!(long.validate().is_err());

        for key in ["", "1ABC", "A B", "A=B", "Ä", &"K".repeat(65)] {
            assert!(validate_env_key(key).is_err(), "{key:?}");
        }
        let dup = LaunchHooks {
            env: vec![EnvVar { key: "path".into(), value: "a".into() }, EnvVar { key: "PATH".into(), value: "b".into() }],
            ..Default::default()
        };
        assert!(dup.validate().is_err());
        let bad_value = LaunchHooks { env: vec![EnvVar { key: "A".into(), value: "x\ny".into() }], ..Default::default() };
        assert!(bad_value.validate().is_err());
        let too_many = LaunchHooks {
            env: (0..=MAX_ENV_VARS).map(|i| EnvVar { key: format!("K{i}"), value: String::new() }).collect(),
            ..Default::default()
        };
        assert!(too_many.validate().is_err());
    }

    #[test]
    fn normalizes_empty_commands() {
        let hooks = LaunchHooks { pre_launch: Some("   ".into()), wrapper: Some(" w ".into()), ..Default::default() }.normalized();
        assert_eq!(hooks.pre_launch, None);
        assert_eq!(hooks.wrapper.as_deref(), Some("w"));
        assert!(LaunchHooks::default().is_empty());
    }

    #[test]
    fn wrapper_prepends_program() {
        let mut command = Command {
            program: PathBuf::from(r"C:\java\bin\javaw.exe"),
            args: vec!["-Xmx2G".into(), "Main".into()],
            cwd: PathBuf::from(r"C:\game"),
            env: Vec::new(),
        };
        apply_wrapper(&mut command, r#""C:\Tools\my wrap.exe" --gpu 1"#);
        assert_eq!(command.program, PathBuf::from(r"C:\Tools\my wrap.exe"));
        assert_eq!(command.args, ["--gpu", "1", r"C:\java\bin\javaw.exe", "-Xmx2G", "Main"]);

        let before = command.args.clone();
        apply_wrapper(&mut command, "   ");
        assert_eq!(command.args, before);
    }

    #[tokio::test]
    async fn runs_commands_with_context() {
        let dir = tempfile::tempdir().unwrap();
        let ctx = ctx(dir.path());
        let env = vec![("TRS_TEST".to_owned(), "wert".to_owned())];
        // Schreibt Instanz-ID und eigene Variable in eine Datei im Instanz-Ordner.
        run(HookKind::PreLaunch, "echo %INST_ID%-%TRS_TEST%> out.txt", &ctx, &env, HOOK_TIMEOUT).await.unwrap();
        let out = std::fs::read_to_string(dir.path().join("out.txt")).unwrap();
        assert_eq!(out.trim(), "test-wert");

        let err = run(HookKind::PostExit, "exit 3", &ctx, &[], HOOK_TIMEOUT).await.unwrap_err();
        assert!(err.to_string().contains("Exit-Code 3"), "{err}");

        let err = run(HookKind::PreLaunch, "ping -n 5 127.0.0.1 >nul", &ctx, &[], Duration::from_millis(300)).await.unwrap_err();
        assert!(err.to_string().contains("abgebrochen"), "{err}");
    }
}
