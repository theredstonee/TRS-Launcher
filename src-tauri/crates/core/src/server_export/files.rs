//! Dateien, die der Server-Export selbst schreibt: Startskripte,
//! `server.properties`, `eula.txt` und die Anleitung. Alles reine Funktionen.

use serde::{Deserialize, Serialize};

use crate::instance::LoaderKind;

/// Wie der Server gestartet wird (nach `java -Xms… -Xmx…`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum LaunchSpec {
    /// `-jar <jar> nogui`
    #[serde(rename_all = "camelCase")]
    Jar { jar: String },
    /// Neues (Neo)Forge: `@user_jvm_args.txt @<args> nogui` – je System eine Argument-Datei.
    #[serde(rename_all = "camelCase")]
    ArgsFile { windows: String, unix: String },
}

impl LaunchSpec {
    /// Argumente nach den Speicher-Angaben (Pfade relativ zum Server-Ordner, immer mit `/`).
    pub fn args(&self, windows: bool) -> Vec<String> {
        match self {
            Self::Jar { jar } => vec!["-jar".into(), jar.clone(), "nogui".into()],
            Self::ArgsFile { windows: win, unix } => {
                let file = if windows { win } else { unix };
                vec!["@user_jvm_args.txt".into(), format!("@{file}"), "nogui".into()]
            }
        }
    }
}

/// Speicher-Argumente: Xmx wie gewählt, Xms höchstens 1 GB (der Server wächst selbst).
pub fn memory_args(ram_mb: u32) -> Vec<String> {
    vec![format!("-Xms{}M", ram_mb.min(1024)), format!("-Xmx{ram_mb}M")]
}

/// Einstellungen für `server.properties`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerProperties {
    pub port: u16,
    pub motd: String,
    pub max_players: u32,
    pub online_mode: bool,
}

/// Wert für eine Java-Properties-Datei: `\` und Steuerzeichen raus bzw.
/// maskiert, alles außerhalb ASCII als `\uXXXX` (alte Server lesen ISO-8859-1).
pub fn escape_property(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for c in value.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            c if c.is_control() => {}
            c if c.is_ascii() => out.push(c),
            c => {
                let mut buf = [0u16; 2];
                for unit in c.encode_utf16(&mut buf) {
                    out.push_str(&format!("\\u{unit:04x}"));
                }
            }
        }
    }
    out
}

/// Nur die Werte, die der Nutzer gewählt hat – den Rest ergänzt der Server beim ersten Start.
pub fn server_properties(props: &ServerProperties, has_world: bool) -> String {
    let mut out = String::from("# Minecraft server properties (TRS Launcher)\n");
    out.push_str(&format!("server-port={}\n", props.port));
    out.push_str(&format!("query.port={}\n", props.port));
    out.push_str(&format!("motd={}\n", escape_property(&props.motd)));
    out.push_str(&format!("max-players={}\n", props.max_players));
    out.push_str(&format!("online-mode={}\n", props.online_mode));
    out.push_str("level-name=world\n");
    if !has_world {
        out.push_str("level-seed=\n");
    }
    out
}

/// `eula.txt` – nur, wenn der Nutzer die EULA ausdrücklich angenommen hat.
pub fn eula_txt(accepted_at: &str) -> String {
    format!(
        "# By changing the setting below to TRUE you are indicating your agreement to our EULA \
         (https://aka.ms/MinecraftEULA).\n# Accepted in the TRS Launcher on {accepted_at}\neula=true\n"
    )
}

/// Startskript für Windows. `MC_JAVA` darf auf eine andere `java.exe` zeigen.
pub fn start_bat(spec: &LaunchSpec, ram_mb: u32, java_major: u32) -> String {
    let args = [memory_args(ram_mb), spec.args(true)].concat().join(" ");
    format!(
        "@echo off\r\n\
         rem Minecraft server - created with the TRS Launcher\r\n\
         rem Needs Java {java_major}. Set MC_JAVA to the full path of java.exe to use a specific Java.\r\n\
         cd /d \"%~dp0\"\r\n\
         if not defined MC_JAVA set \"MC_JAVA=java\"\r\n\
         \"%MC_JAVA%\" {args}\r\n\
         if errorlevel 1 (\r\n\
         \x20 echo.\r\n\
         \x20 echo The server stopped with an error. It needs Java {java_major} - see README.txt.\r\n\
         )\r\n\
         pause\r\n"
    )
}

/// Startskript für Linux/macOS.
pub fn start_sh(spec: &LaunchSpec, ram_mb: u32, java_major: u32) -> String {
    let args = [memory_args(ram_mb), spec.args(false)].concat().join(" ");
    format!(
        "#!/usr/bin/env sh\n\
         # Minecraft server - created with the TRS Launcher\n\
         # Needs Java {java_major}. Set MC_JAVA to the full path of java to use a specific Java.\n\
         cd \"$(dirname \"$0\")\" || exit 1\n\
         exec \"${{MC_JAVA:-java}}\" {args}\n"
    )
}

pub fn loader_label(kind: LoaderKind) -> &'static str {
    match kind {
        LoaderKind::Vanilla => "Vanilla",
        LoaderKind::Fabric => "Fabric",
        LoaderKind::Quilt => "Quilt",
        LoaderKind::Forge => "Forge",
        LoaderKind::NeoForge => "NeoForge",
    }
}

/// Angaben für die Anleitung.
#[derive(Debug, Clone)]
pub struct ReadmeInfo<'a> {
    pub name: &'a str,
    pub game_version: &'a str,
    pub loader: LoaderKind,
    pub loader_version: Option<&'a str>,
    pub java_major: u32,
    pub port: u16,
    pub ram_mb: u32,
    pub eula_accepted: bool,
    pub mods: usize,
    pub left_out: &'a [String],
    /// Fabric lädt seine Bibliotheken erst beim ersten Start.
    pub needs_internet_first_start: bool,
}

/// `README.txt` (Englisch und Deutsch).
pub fn readme(info: &ReadmeInfo<'_>) -> String {
    let loader = match info.loader_version {
        Some(v) if info.loader != LoaderKind::Vanilla => format!("{} {v}", loader_label(info.loader)),
        _ => loader_label(info.loader).to_owned(),
    };
    let left_out = if info.left_out.is_empty() {
        "-".to_owned()
    } else {
        info.left_out.iter().map(|m| format!("  - {m}")).collect::<Vec<_>>().join("\n")
    };
    let (eula_en, eula_de) = if info.eula_accepted {
        ("The Minecraft EULA was accepted in the launcher (eula.txt).", "Die Minecraft-EULA wurde im Launcher akzeptiert (eula.txt).")
    } else {
        (
            "The Minecraft EULA has NOT been accepted yet. Read https://aka.ms/MinecraftEULA – the server creates\n\
             eula.txt on the first start; set eula=true there if you agree, then start again.",
            "Die Minecraft-EULA wurde noch NICHT akzeptiert. Lies https://aka.ms/MinecraftEULA – der Server legt beim\n\
             ersten Start eula.txt an; setze dort eula=true, wenn du zustimmst, und starte erneut.",
        )
    };
    let (net_en, net_de) = if info.needs_internet_first_start {
        ("The first start needs internet: the loader downloads its libraries once.\n", "Der erste Start braucht Internet: der Loader lädt einmalig seine Bibliotheken.\n")
    } else {
        ("", "")
    };
    format!(
        "{name} – Minecraft {game} server ({loader})\n\
         Created with the TRS Launcher\n\
         =====================================================================\n\n\
         ENGLISH\n\
         -------\n\
         Start: Windows start.bat, Linux/macOS start.sh (chmod +x start.sh).\n\
         Java:  needs Java {java}. If several are installed, set MC_JAVA to the full path of the right java.\n\
         RAM:   {ram} MB (change -Xmx in the start scripts).\n\
         Port:  {port} (TCP). Players in your network join with <your-ip>:{port}. For the internet, forward\n\
         \x20      this port in your router and allow Java in the firewall.\n\
         {eula_en}\n{net_en}\
         Stop the server with the command \"stop\" so the world is saved.\n\
         Mods on the server: {mods}. Left out as client-only (players keep them in their game):\n{left_out}\n\n\
         DEUTSCH\n\
         -------\n\
         Start: Windows start.bat, Linux/macOS start.sh (chmod +x start.sh).\n\
         Java:  braucht Java {java}. Sind mehrere installiert, setze MC_JAVA auf den vollen Pfad zur richtigen java.\n\
         RAM:   {ram} MB (in den Startskripten bei -Xmx änderbar).\n\
         Port:  {port} (TCP). Spieler im selben Netz verbinden sich mit <deine-ip>:{port}. Fürs Internet den Port\n\
         \x20      im Router weiterleiten und Java in der Firewall erlauben.\n\
         {eula_de}\n{net_de}\
         Den Server mit dem Befehl \"stop\" beenden, damit die Welt gespeichert wird.\n\
         Mods auf dem Server: {mods}. Als reine Client-Mods weggelassen (Spieler behalten sie im Spiel):\n{left_out}\n",
        name = info.name,
        game = info.game_version,
        java = info.java_major,
        ram = info.ram_mb,
        port = info.port,
        mods = info.mods,
    )
}

/// Ordnername aus dem Server-Namen: nur Buchstaben, Ziffern, `-` und `_`.
pub fn slug(name: &str) -> String {
    let mut out = String::new();
    for c in name.trim().chars() {
        if c.is_ascii_alphanumeric() || c == '_' {
            out.push(c.to_ascii_lowercase());
        } else if !out.ends_with('-') && !out.is_empty() {
            out.push('-');
        }
        if out.len() >= 40 {
            break;
        }
    }
    let out = out.trim_matches('-').to_owned();
    if out.is_empty() { "server".to_owned() } else { out }
}

/// Minecraft-Nebenversion (`1.20.1` → 20); Snapshots und Unbekanntes → `None`.
pub fn minor_version(game_version: &str) -> Option<u32> {
    let mut parts = game_version.split('.');
    let major = parts.next()?;
    let minor = parts.next()?.split(|c: char| !c.is_ascii_digit()).next()?;
    if major == "1" { minor.parse().ok() } else { major.parse::<u32>().ok().map(|m| m + 100) }
}
