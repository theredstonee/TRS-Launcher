//! Baut die Java-Kommandozeile und verwaltet laufende Spielprozesse.

use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use chrono::{DateTime, Utc};
use serde::Serialize;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::{mpsc, oneshot};

use crate::gamelog::{LogLine, LogParser};
use crate::instance::Instance;
use crate::meta::version::{Argument, Features, VersionInfo, rules_allow};
use crate::prepare::Prepared;
use crate::settings::Settings;
use crate::{Error, LAUNCHER_NAME, LAUNCHER_VERSION, Result};

const LOG_HISTORY: usize = 5000;
const LOG_BATCH_MAX: usize = 400;
const LOG_BATCH_INTERVAL: Duration = Duration::from_millis(60);
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// Mit wem gespielt wird.
#[derive(Clone)]
pub struct Session {
    pub player_name: String,
    /// UUID ohne Bindestriche.
    pub uuid: String,
    pub access_token: String,
    pub xuid: String,
    /// Offizieller Demo-Modus des Spiels (nur Entwicklungs-Builds ohne Account).
    pub demo: bool,
}

impl Session {
    pub fn features(&self) -> Features {
        Features { demo_user: self.demo, custom_resolution: true }
    }
}

#[derive(Debug)]
pub struct Command {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub cwd: PathBuf,
}

pub fn build_command(
    prepared: &Prepared,
    instance: &Instance,
    settings: &Settings,
    session: &Session,
    game_dir: &Path,
    assets_root: &Path,
    libraries_dir: &Path,
) -> Result<Command> {
    let version = &prepared.version;
    let features = session.features();
    let resolution = instance.overrides.resolution.unwrap_or(settings.resolution);
    let max_mb = instance.overrides.max_memory_mb.unwrap_or(settings.max_memory_mb);
    let min_mb = settings.min_memory_mb.min(max_mb);

    let classpath = prepared
        .classpath
        .iter()
        .map(|p| p.display().to_string())
        .collect::<Vec<_>>()
        .join(";");

    let vars: HashMap<&str, String> = HashMap::from([
        ("auth_player_name", session.player_name.clone()),
        ("auth_uuid", session.uuid.clone()),
        ("auth_access_token", session.access_token.clone()),
        ("auth_session", format!("token:{}:{}", session.access_token, session.uuid)),
        ("auth_xuid", session.xuid.clone()),
        ("clientid", String::new()),
        ("user_type", "msa".into()),
        ("user_properties", "{}".into()),
        ("version_name", version.id.clone()),
        ("version_type", version.kind.clone().unwrap_or_else(|| "release".into())),
        ("game_directory", game_dir.display().to_string()),
        ("assets_root", assets_root.display().to_string()),
        ("game_assets", prepared.game_assets.display().to_string()),
        ("assets_index_name", version.assets.clone().unwrap_or_else(|| "legacy".into())),
        ("natives_directory", prepared.natives_dir.display().to_string()),
        ("library_directory", libraries_dir.display().to_string()),
        ("classpath_separator", ";".into()),
        ("classpath", classpath),
        ("launcher_name", LAUNCHER_NAME.into()),
        ("launcher_version", LAUNCHER_VERSION.into()),
        ("resolution_width", resolution.width.to_string()),
        ("resolution_height", resolution.height.to_string()),
    ]);
    let fill = |template: &str| substitute(template, &vars);

    let mut args = vec![
        format!("-Xms{min_mb}M"),
        format!("-Xmx{max_mb}M"),
        "-Dfile.encoding=UTF-8".into(),
        "-Dstdout.encoding=UTF-8".into(),
        "-Dstderr.encoding=UTF-8".into(),
        // Zusätzlich zu Mojangs gepatchter Log-Konfiguration (Log4Shell).
        "-Dlog4j2.formatMsgNoLookups=true".into(),
    ];

    match &version.arguments {
        Some(a) if !a.jvm.is_empty() => args.extend(expand(&a.jvm, &features).map(&fill)),
        // Vor 1.13 gibt es keine JVM-Argumente im JSON.
        _ => args.extend(
            [
                "-Djava.library.path=${natives_directory}",
                "-Dminecraft.launcher.brand=${launcher_name}",
                "-Dminecraft.launcher.version=${launcher_version}",
                "-cp",
                "${classpath}",
            ]
            .map(fill),
        ),
    }

    if let (Some(path), Some(cfg)) =
        (&prepared.log_config, version.logging.as_ref().and_then(|l| l.client.as_ref()))
    {
        args.push(cfg.argument.replace("${path}", &path.display().to_string()));
    }

    let user_jvm = instance.overrides.jvm_args.as_deref().unwrap_or(&settings.jvm_args);
    args.extend(split_args(user_jvm));

    args.push(main_class(version)?.to_owned());

    match (&version.arguments, &version.minecraft_arguments) {
        (Some(a), _) if !a.game.is_empty() => args.extend(expand(&a.game, &features).map(&fill)),
        (_, Some(legacy)) => {
            // Erst trennen, dann ersetzen – Werte mit Leerzeichen bleiben ein Argument.
            args.extend(legacy.split_whitespace().map(fill));
            args.extend(["--width".into(), resolution.width.to_string()]);
            args.extend(["--height".into(), resolution.height.to_string()]);
            if session.demo {
                args.push("--demo".into());
            }
        }
        _ => return Err(Error::launch("Die Versions-Metadaten enthalten keine Startargumente.")),
    }

    Ok(Command { program: prepared.java.clone(), args, cwd: game_dir.to_owned() })
}

fn main_class(version: &VersionInfo) -> Result<&str> {
    version
        .main_class
        .as_deref()
        .ok_or_else(|| Error::launch("Die Versions-Metadaten enthalten keine Hauptklasse."))
}

fn expand<'a>(args: &'a [Argument], features: &'a Features) -> impl Iterator<Item = &'a str> {
    args.iter().flat_map(move |arg| -> Box<dyn Iterator<Item = &'a str> + 'a> {
        match arg {
            Argument::Plain(s) => Box::new(std::iter::once(s.as_str())),
            Argument::Conditional { rules, value } if rules_allow(rules, features) => Box::new(value.iter()),
            Argument::Conditional { .. } => Box::new(std::iter::empty()),
        }
    })
}

fn substitute(template: &str, vars: &HashMap<&str, String>) -> String {
    let mut out = String::with_capacity(template.len());
    let mut rest = template;
    while let Some(start) = rest.find("${") {
        out.push_str(&rest[..start]);
        match rest[start + 2..].find('}') {
            Some(len) => {
                let key = &rest[start + 2..start + 2 + len];
                match vars.get(key) {
                    Some(value) => out.push_str(value),
                    None => out.push_str(&rest[start..start + 3 + len]),
                }
                rest = &rest[start + 3 + len..];
            }
            None => {
                out.push_str(&rest[start..]);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out
}

/// Trennt an Leerzeichen, doppelte Anführungszeichen halten zusammen.
fn split_args(input: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();
    let mut quoted = false;
    let mut has_token = false;
    for c in input.chars() {
        match c {
            '"' => {
                quoted = !quoted;
                has_token = true;
            }
            c if c.is_whitespace() && !quoted => {
                if has_token {
                    out.push(std::mem::take(&mut current));
                    has_token = false;
                }
            }
            c => {
                current.push(c);
                has_token = true;
            }
        }
    }
    if has_token {
        out.push(current);
    }
    out
}

// --- Prozessverwaltung -------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum GameEvent {
    Started { instance_id: String, pid: u32 },
    Logs { instance_id: String, lines: Vec<LogLine> },
    Exited { instance_id: String, exit_code: Option<i32>, crashed: bool, play_seconds: u64 },
}

pub type EventSink = Arc<dyn Fn(GameEvent) + Send + Sync>;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunningGame {
    pub instance_id: String,
    pub pid: u32,
    pub started_at: DateTime<Utc>,
}

struct Running {
    info: RunningGame,
    kill: Option<oneshot::Sender<()>>,
}

#[derive(Default)]
struct State {
    running: HashMap<String, Running>,
    logs: HashMap<String, VecDeque<LogLine>>,
}

pub struct GameManager {
    state: Arc<Mutex<State>>,
    sink: EventSink,
}

/// Was nach dem Spielende passieren soll (Spielzeit verbuchen).
pub type OnExit = Box<dyn FnOnce(u64) + Send>;

impl GameManager {
    pub fn new(sink: EventSink) -> Self {
        Self { state: Arc::default(), sink }
    }

    pub fn running(&self) -> Vec<RunningGame> {
        self.lock().running.values().map(|r| r.info.clone()).collect()
    }

    pub fn is_running(&self, instance_id: &str) -> bool {
        self.lock().running.contains_key(instance_id)
    }

    pub fn logs(&self, instance_id: &str) -> Vec<LogLine> {
        self.lock().logs.get(instance_id).map(|l| l.iter().cloned().collect()).unwrap_or_default()
    }

    pub fn kill(&self, instance_id: &str) -> bool {
        self.lock()
            .running
            .get_mut(instance_id)
            .and_then(|r| r.kill.take())
            .is_some_and(|tx| tx.send(()).is_ok())
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// `secrets` werden aus allen Log-Zeilen entfernt – alte Versionen geben
    /// das Session-Token beim Start aus.
    pub fn spawn(
        &self,
        instance_id: &str,
        command: Command,
        secrets: Vec<String>,
        on_exit: OnExit,
    ) -> Result<u32> {
        if self.is_running(instance_id) {
            return Err(Error::launch("Diese Instanz läuft bereits."));
        }

        let mut cmd = tokio::process::Command::new(&command.program);
        cmd.args(&command.args)
            .current_dir(&command.cwd)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .creation_flags(CREATE_NO_WINDOW)
            // Das Spiel soll den Launcher überleben dürfen.
            .kill_on_drop(false);

        let mut child = cmd.spawn().map_err(|e| {
            tracing::error!("Java konnte nicht gestartet werden ({}): {e}", command.program.display());
            Error::launch("Java konnte nicht gestartet werden.")
        })?;
        let pid = child.id().unwrap_or_default();
        let started_at = Utc::now();

        let (line_tx, mut line_rx) = mpsc::unbounded_channel::<LogLine>();
        let secrets: Arc<Vec<String>> = Arc::new(secrets.into_iter().filter(|s| s.len() >= 8).collect());

        if let Some(stdout) = child.stdout.take() {
            tokio::spawn(pump(BufReader::new(stdout), LogParser::stdout(), line_tx.clone(), secrets.clone()));
        }
        if let Some(stderr) = child.stderr.take() {
            tokio::spawn(pump(BufReader::new(stderr), LogParser::stderr(), line_tx.clone(), secrets.clone()));
        }
        drop(line_tx);

        let (kill_tx, kill_rx) = oneshot::channel();
        {
            let mut state = self.lock();
            state.logs.insert(instance_id.to_owned(), VecDeque::new());
            state.running.insert(
                instance_id.to_owned(),
                Running {
                    info: RunningGame { instance_id: instance_id.to_owned(), pid, started_at },
                    kill: Some(kill_tx),
                },
            );
        }
        (self.sink)(GameEvent::Started { instance_id: instance_id.to_owned(), pid });

        // Log-Zeilen gebündelt weiterreichen, damit das Frontend bei
        // Ausgabe-Stürmen nicht mit Einzel-Events geflutet wird.
        let forwarder = {
            let (state, sink, id) = (self.state.clone(), self.sink.clone(), instance_id.to_owned());
            tokio::spawn(async move {
                while let Some(first) = line_rx.recv().await {
                    let mut batch = vec![first];
                    while batch.len() < LOG_BATCH_MAX
                        && let Ok(line) = line_rx.try_recv()
                    {
                        batch.push(line);
                    }
                    {
                        let mut state = state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                        let history = state.logs.entry(id.clone()).or_default();
                        history.extend(batch.iter().cloned());
                        while history.len() > LOG_HISTORY {
                            history.pop_front();
                        }
                    }
                    sink(GameEvent::Logs { instance_id: id.clone(), lines: batch });
                    tokio::time::sleep(LOG_BATCH_INTERVAL).await;
                }
            })
        };

        let (state, sink, id) = (self.state.clone(), self.sink.clone(), instance_id.to_owned());
        tokio::spawn(async move {
            let (status, killed) = tokio::select! {
                status = child.wait() => (status.ok(), false),
                _ = kill_rx => {
                    let _ = child.kill().await;
                    (child.wait().await.ok(), true)
                }
            };
            // Erst alle Logs ausliefern, dann das Ende melden.
            let _ = forwarder.await;

            let exit_code = status.and_then(|s| s.code());
            let play_seconds = (Utc::now() - started_at).num_seconds().max(0) as u64;
            state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).running.remove(&id);
            on_exit(play_seconds);
            sink(GameEvent::Exited {
                instance_id: id,
                exit_code,
                crashed: !killed && exit_code != Some(0),
                play_seconds,
            });
        });

        Ok(pid)
    }
}

async fn pump<R: tokio::io::AsyncRead + Unpin>(
    mut reader: BufReader<R>,
    mut parser: LogParser,
    tx: mpsc::UnboundedSender<LogLine>,
    secrets: Arc<Vec<String>>,
) {
    let mut buf = Vec::new();
    loop {
        buf.clear();
        match reader.read_until(b'\n', &mut buf).await {
            Ok(0) | Err(_) => break,
            Ok(_) => {}
        }
        // Nicht jede Ausgabe ist gültiges UTF-8 (alte Versionen, native Libs).
        let text = String::from_utf8_lossy(&buf);
        if let Some(mut line) = parser.feed(&text, Utc::now().timestamp_millis()) {
            for secret in secrets.iter() {
                if line.message.contains(secret.as_str()) {
                    line.message = line.message.replace(secret.as_str(), "********");
                }
            }
            if tx.send(line).is_err() {
                break;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::instance::{InstanceOverrides, Loader};
    use crate::settings::Resolution;

    fn session() -> Session {
        Session {
            player_name: "Steve".into(),
            uuid: "00000000000000000000000000000001".into(),
            access_token: "geheimes-token-123".into(),
            xuid: "42".into(),
            demo: false,
        }
    }

    fn instance() -> Instance {
        Instance {
            id: "test".into(),
            name: "Test".into(),
            game_version: "1.21.1".into(),
            loader: Loader::vanilla(),
            created_at: Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            overrides: InstanceOverrides::default(),
        }
    }

    fn prepared(version_json: &str) -> Prepared {
        Prepared {
            version: serde_json::from_str(version_json).unwrap(),
            java: PathBuf::from(r"C:\java\bin\javaw.exe"),
            classpath: vec![PathBuf::from(r"C:\l\a.jar"), PathBuf::from(r"C:\v\client.jar")],
            natives_dir: PathBuf::from(r"C:\natives"),
            game_assets: PathBuf::from(r"C:\assets\virtual\legacy"),
            log_config: Some(PathBuf::from(r"C:\assets\log_configs\client.xml")),
        }
    }

    fn build(p: &Prepared, inst: &Instance, s: &Session) -> Vec<String> {
        build_command(
            p,
            inst,
            &Settings::default(),
            s,
            Path::new(r"C:\game dir"),
            Path::new(r"C:\assets"),
            Path::new(r"C:\l"),
        )
        .unwrap()
        .args
    }

    const MODERN: &str = r#"{
        "id":"1.21.1","type":"release","assets":"17","mainClass":"net.minecraft.client.main.Main",
        "logging":{"client":{"argument":"-Dlog4j.configurationFile=${path}","type":"log4j2-xml",
            "file":{"id":"client-1.12.xml","sha1":"x","size":1,"url":"https://x"}}},
        "arguments":{
          "jvm":[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},
                 {"rules":[{"action":"allow","os":{"name":"windows"}}],"value":"-XX:HeapDumpPath=x.heapdump"},
                 "-Djava.library.path=${natives_directory}","-cp","${classpath}"],
          "game":["--username","${auth_player_name}","--gameDir","${game_directory}","--accessToken","${auth_access_token}",
                 {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"},
                 {"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],
                  "value":["--width","${resolution_width}","--height","${resolution_height}"]},
                 {"rules":[{"action":"allow","features":{"has_quick_plays_support":true}}],
                  "value":["--quickPlayPath","${quickPlayPath}"]}]}}"#;

    #[test]
    fn modern_arguments() {
        let args = build(&prepared(MODERN), &instance(), &session());
        let joined = args.join(" | ");

        assert!(args.contains(&"-Xmx4096M".to_owned()));
        assert!(args.contains(&"-XX:HeapDumpPath=x.heapdump".to_owned()));
        assert!(!joined.contains("XstartOnFirstThread"));
        assert!(args.contains(&r"-Djava.library.path=C:\natives".to_owned()));
        assert!(args.contains(&r"C:\l\a.jar;C:\v\client.jar".to_owned()));
        assert!(args.contains(&r"-Dlog4j.configurationFile=C:\assets\log_configs\client.xml".to_owned()));
        // Pfad mit Leerzeichen bleibt EIN Argument.
        assert!(args.contains(&r"C:\game dir".to_owned()));
        assert!(!joined.contains("--demo") && !joined.contains("quickPlay"));
        assert!(joined.ends_with("--width | 1280 | --height | 720"));

        // JVM-Argumente vor der Hauptklasse, Spielargumente danach.
        let main = args.iter().position(|a| a == "net.minecraft.client.main.Main").unwrap();
        assert!(args.iter().position(|a| a == "-cp").unwrap() < main);
        assert!(args.iter().position(|a| a == "--username").unwrap() > main);
    }

    #[test]
    fn demo_and_overrides() {
        let mut inst = instance();
        inst.overrides = InstanceOverrides {
            max_memory_mb: Some(8192),
            jvm_args: Some(r#"-XX:+UseG1GC "-Dfoo=a b""#.into()),
            resolution: Some(Resolution { width: 1920, height: 1080 }),
            java_path: None,
        };
        let args = build(&prepared(MODERN), &inst, &Session { demo: true, ..session() });
        assert!(args.contains(&"-Xmx8192M".to_owned()));
        assert!(args.contains(&"-Dfoo=a b".to_owned()));
        assert!(args.contains(&"--demo".to_owned()));
        assert!(args.contains(&"1920".to_owned()));
    }

    #[test]
    fn legacy_arguments() {
        let p = prepared(
            r#"{"id":"1.6.4","assets":"legacy","mainClass":"net.minecraft.client.main.Main",
                "minecraftArguments":"--username ${auth_player_name} --session ${auth_session} --gameDir ${game_directory} --assetsDir ${game_assets}"}"#,
        );
        let args = build(&p, &instance(), &session());
        assert!(args.contains(&r"-Djava.library.path=C:\natives".to_owned()));
        assert!(args.contains(&"token:geheimes-token-123:00000000000000000000000000000001".to_owned()));
        assert!(args.contains(&r"C:\assets\virtual\legacy".to_owned()));
        assert!(args.contains(&r"C:\game dir".to_owned()));
        assert!(args.contains(&"--width".to_owned()));
    }

    #[test]
    fn substitution_keeps_unknown_placeholders() {
        let vars = HashMap::from([("a", "1".to_owned())]);
        assert_eq!(substitute("x${a}y${b}z${a", &vars), "x1y${b}z${a");
    }

    #[test]
    fn arg_splitting() {
        assert_eq!(split_args(r#"  -a  "-b c"  -d"e f"  "#), ["-a", "-b c", "-de f"]);
        assert!(split_args("   ").is_empty());
        assert_eq!(split_args(r#""""#), [""]);
    }
}
