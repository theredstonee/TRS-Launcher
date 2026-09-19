//! Spiel-Logs. Mit Mojangs Log4j-Konfiguration schreibt das Spiel XML-Events
//! auf stdout; ohne (sehr alte Versionen, Modloader-Ausgaben, stderr) kommen
//! normale Textzeilen. Beides wird zu [`LogLine`] vereinheitlicht.

use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Level {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
    Fatal,
}

impl Level {
    fn parse(s: &str) -> Option<Self> {
        Some(match s.to_ascii_uppercase().as_str() {
            "TRACE" => Self::Trace,
            "DEBUG" => Self::Debug,
            "INFO" => Self::Info,
            "WARN" | "WARNING" => Self::Warn,
            "ERROR" | "SEVERE" => Self::Error,
            "FATAL" => Self::Fatal,
            _ => return None,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LogLine {
    /// Unix-Millisekunden.
    pub time: i64,
    pub level: Level,
    pub thread: Option<String>,
    pub message: String,
}

/// Obergrenze für ein einzelnes XML-Event, damit ein nie geschlossenes
/// `<log4j:Event>` nicht endlos puffert.
const MAX_EVENT_LINES: usize = 2000;

/// Zustandsbehafteter Parser für einen Ausgabestrom.
pub struct LogParser {
    default_level: Level,
    event: Option<Vec<String>>,
}

impl LogParser {
    pub fn stdout() -> Self {
        Self { default_level: Level::Info, event: None }
    }

    pub fn stderr() -> Self {
        Self { default_level: Level::Error, event: None }
    }

    pub fn feed(&mut self, raw: &str, now_ms: i64) -> Option<LogLine> {
        let line = raw.trim_end_matches(['\r', '\n']);

        if let Some(buffer) = &mut self.event {
            buffer.push(line.to_owned());
            if line.contains("</log4j:Event>") || buffer.len() >= MAX_EVENT_LINES {
                let xml = self.event.take().unwrap_or_default().join("\n");
                return Some(parse_event(&xml, now_ms, self.default_level));
            }
            return None;
        }

        if line.trim_start().starts_with("<log4j:Event") {
            if line.contains("</log4j:Event>") {
                return Some(parse_event(line, now_ms, self.default_level));
            }
            self.event = Some(vec![line.to_owned()]);
            return None;
        }

        if line.trim().is_empty() {
            return None;
        }
        Some(parse_plain(line, now_ms, self.default_level))
    }
}

fn attr<'a>(xml: &'a str, name: &str) -> Option<&'a str> {
    let head = &xml[..xml.find('>')?];
    let start = head.find(&format!("{name}=\""))? + name.len() + 2;
    let end = head[start..].find('"')? + start;
    Some(&head[start..end])
}

fn cdata(xml: &str, tag: &str) -> Option<String> {
    let open = format!("<log4j:{tag}>");
    let close = format!("</log4j:{tag}>");
    let start = xml.find(&open)? + open.len();
    let end = xml[start..].find(&close)? + start;
    let inner = xml[start..end].trim();
    let inner = inner.strip_prefix("<![CDATA[").unwrap_or(inner);
    let inner = inner.strip_suffix("]]>").unwrap_or(inner);
    // Log4j teilt ein wörtliches "]]>" in zwei CDATA-Abschnitte.
    Some(inner.replace("]]]]><![CDATA[>", "]]>"))
}

fn parse_event(xml: &str, now_ms: i64, default_level: Level) -> LogLine {
    let mut message = cdata(xml, "Message").unwrap_or_default();
    if let Some(throwable) = cdata(xml, "Throwable").filter(|t| !t.is_empty()) {
        message.push('\n');
        message.push_str(throwable.trim_end());
    }
    LogLine {
        time: attr(xml, "timestamp").and_then(|t| t.parse().ok()).unwrap_or(now_ms),
        level: attr(xml, "level").and_then(Level::parse).unwrap_or(default_level),
        thread: attr(xml, "thread").map(str::to_owned),
        message,
    }
}

/// `[12:34:56] [Render thread/INFO]: Nachricht` – sonst die Zeile unverändert.
fn parse_plain(line: &str, now_ms: i64, default_level: Level) -> LogLine {
    let parsed = (|| {
        let rest = line.strip_prefix('[')?;
        let (_time, rest) = rest.split_once("] [")?;
        let (source, message) = rest.split_once("]: ")?;
        let (thread, level) = source.rsplit_once('/')?;
        Some((thread.to_owned(), Level::parse(level)?, message.to_owned()))
    })();

    match parsed {
        Some((thread, level, message)) => LogLine { time: now_ms, level, thread: Some(thread), message },
        None => LogLine {
            time: now_ms,
            level: legacy_level(line).unwrap_or(default_level),
            thread: None,
            message: line.to_owned(),
        },
    }
}

/// Versionen vor 1.7 loggen über java.util.logging auf stderr – ohne diese
/// Erkennung sähe dort jede Info-Zeile wie ein Fehler aus.
fn legacy_level(line: &str) -> Option<Level> {
    const MARKERS: [(&str, Level); 6] = [
        ("[SEVERE]", Level::Error),
        ("[WARNING]", Level::Warn),
        ("[INFO]", Level::Info),
        ("[FINE]", Level::Debug),
        ("[FINER]", Level::Trace),
        ("[FINEST]", Level::Trace),
    ];
    if let Some((_, level)) = MARKERS.iter().find(|(marker, _)| line.contains(marker)) {
        return Some(*level);
    }
    let (prefix, _) = line.split_once(':')?;
    match prefix {
        "INFO" | "INFORMATION" => Some(Level::Info),
        "WARNING" | "WARNUNG" => Some(Level::Warn),
        "SEVERE" | "SCHWERWIEGEND" => Some(Level::Error),
        // Kopfzeile eines JUL-Eintrags: "Sep 19, 2026 10:41:59 AM klasse methode"
        _ if line.ends_with(" log") && line.contains("LogWrapper") => Some(Level::Debug),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_multiline_xml_event() {
        let mut p = LogParser::stdout();
        assert!(p.feed(r#"<log4j:Event logger="net.minecraft.client.Minecraft" timestamp="1700000000123" level="WARN" thread="Render thread">"#, 1).is_none());
        assert!(p.feed("  <log4j:Message><![CDATA[Etwas ist <komisch> & seltsam]]></log4j:Message>", 1).is_none());
        let line = p.feed("</log4j:Event>", 1).unwrap();
        assert_eq!(line.time, 1_700_000_000_123);
        assert_eq!(line.level, Level::Warn);
        assert_eq!(line.thread.as_deref(), Some("Render thread"));
        assert_eq!(line.message, "Etwas ist <komisch> & seltsam");
    }

    #[test]
    fn appends_throwable() {
        let mut p = LogParser::stdout();
        p.feed(r#"<log4j:Event logger="x" timestamp="5" level="ERROR" thread="main">"#, 1);
        p.feed("<log4j:Message><![CDATA[Boom]]></log4j:Message>", 1);
        p.feed("<log4j:Throwable><![CDATA[java.lang.RuntimeException: x", 1);
        p.feed("\tat a.b.C.d(C.java:1)", 1);
        p.feed("]]></log4j:Throwable>", 1);
        let line = p.feed("</log4j:Event>", 1).unwrap();
        assert_eq!(line.level, Level::Error);
        assert!(line.message.starts_with("Boom\njava.lang.RuntimeException: x\n\tat a.b.C.d"));
    }

    #[test]
    fn parses_plain_lines() {
        let mut p = LogParser::stdout();
        let l = p.feed("[12:00:01] [Render thread/INFO]: Setting user: Steve\r\n", 42).unwrap();
        assert_eq!((l.level, l.thread.as_deref(), l.message.as_str()), (Level::Info, Some("Render thread"), "Setting user: Steve"));
        assert_eq!(l.time, 42);

        let l = p.feed("irgendeine Ausgabe", 1).unwrap();
        assert_eq!((l.level, l.thread), (Level::Info, None));

        let l = LogParser::stderr().feed("Exception in thread \"main\"", 1).unwrap();
        assert_eq!(l.level, Level::Error);

        assert!(p.feed("   ", 1).is_none());
    }

    #[test]
    fn legacy_jul_lines_on_stderr_keep_their_level() {
        let mut p = LogParser::stderr();
        let level = |p: &mut LogParser, s: &str| p.feed(s, 1).unwrap().level;
        assert_eq!(level(&mut p, "2026-09-19 10:42:00 [CLIENT] [INFO] Setting user: Player"), Level::Info);
        assert_eq!(level(&mut p, "2026-09-19 10:42:00 [CLIENT] [WARNING] Skipping bad option"), Level::Warn);
        assert_eq!(level(&mut p, "INFORMATION: Launching wrapped minecraft"), Level::Info);
        assert_eq!(level(&mut p, "java.lang.NullPointerException: boom"), Level::Error);
        assert_eq!(level(&mut p, "\tat a.b.C.d(C.java:1)"), Level::Error);
    }

    #[test]
    fn unterminated_event_is_flushed() {
        let mut p = LogParser::stdout();
        p.feed(r#"<log4j:Event level="INFO">"#, 1);
        let mut out = None;
        for _ in 0..MAX_EVENT_LINES {
            out = p.feed("x", 1);
            if out.is_some() {
                break;
            }
        }
        assert!(out.is_some());
        assert!(p.feed("[12:00:01] [main/INFO]: weiter", 1).is_some());
    }
}
