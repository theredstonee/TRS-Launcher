//! Ereignisse der Swift-Engine → Vertrags-Events (`trs-game://state` / `log`).
//!
//! Ablauf: (JIT warten) → starting → running → exited/crashed. Auf iOS läuft die JVM im
//! App-Prozess; beendet sich das Spiel, endet auch die App. Das Ende wird deshalb
//! zusätzlich in `last-session.json` geschrieben und beim nächsten Start nachgereicht.

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::models::{GameLogEvent, GameState, GameStateEvent};

pub const LAST_SESSION_FILE: &str = "trs-last-session.json";
const MAX_LOG_LINE: usize = 4096;
/// Zeilen im `logTail` beim Ende.
const MAX_TAIL_LINES: usize = 200;

/// Was das Swift-Plugin über den Kanal schickt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum EngineEvent {
    /// Hilfe-Bildschirm offen, wartet auf JIT.
    JitWaiting,
    JitReady,
    /// JVM wird gestartet.
    Starting,
    /// Erstes Bild / Fenster da.
    Running,
    Log { line: String },
    Exited { code: i32, #[serde(default)] log_tail: Option<String> },
    /// Nutzer hat auf dem JIT-Bildschirm abgebrochen.
    Cancelled,
    /// Start in der Engine gescheitert (Code wie `jli_missing`).
    Failed { reason: String },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Phase {
    WaitingForJit,
    Starting,
    Running,
    Done,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Output {
    State(GameStateEvent),
    Log(GameLogEvent),
}

/// Zustandsautomat einer Sitzung; ignoriert Ereignisse nach dem Ende und Rücksprünge.
#[derive(Debug)]
pub struct SessionTracker {
    session: String,
    phase: Phase,
}

impl SessionTracker {
    /// Liefert den Tracker und das erste `starting`-Event.
    pub fn new(session: &str, waiting_for_jit: bool) -> (Self, Output) {
        let tracker = Self { session: session.into(), phase: if waiting_for_jit { Phase::WaitingForJit } else { Phase::Starting } };
        let first = tracker.state(GameState::Starting, None, Vec::new());
        (tracker, first)
    }

    pub fn finished(&self) -> bool {
        self.phase == Phase::Done
    }

    fn state(&self, state: GameState, exit_code: Option<i32>, log_tail: Vec<String>) -> Output {
        Output::State(GameStateEvent { session: self.session.clone(), state, exit_code, log_tail })
    }

    pub fn apply(&mut self, event: EngineEvent) -> Option<Output> {
        if self.phase == Phase::Done {
            return None;
        }
        match event {
            EngineEvent::JitWaiting => {
                if self.phase == Phase::Starting {
                    self.phase = Phase::WaitingForJit;
                }
                None
            }
            EngineEvent::JitReady | EngineEvent::Starting => {
                if self.phase == Phase::WaitingForJit {
                    self.phase = Phase::Starting;
                }
                None
            }
            EngineEvent::Running => {
                if self.phase == Phase::Running {
                    return None;
                }
                self.phase = Phase::Running;
                Some(self.state(GameState::Running, None, Vec::new()))
            }
            EngineEvent::Log { line } => {
                Some(Output::Log(GameLogEvent { session: self.session.clone(), lines: vec![truncate(&redact(&line), MAX_LOG_LINE)] }))
            }
            EngineEvent::Exited { code, log_tail } => {
                self.phase = Phase::Done;
                let state = if code == 0 { GameState::Exited } else { GameState::Crashed };
                Some(self.state(state, Some(code), tail_lines(log_tail.as_deref().unwrap_or(""))))
            }
            EngineEvent::Cancelled => {
                self.phase = Phase::Done;
                Some(self.state(GameState::Exited, None, Vec::new()))
            }
            EngineEvent::Failed { reason } => {
                self.phase = Phase::Done;
                Some(self.state(GameState::Crashed, None, vec![truncate(&reason, 256)]))
            }
        }
    }
}

fn truncate(s: &str, max: usize) -> String {
    if s.len() <= max {
        return s.to_owned();
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    s[..end].to_owned()
}

/// Die letzten Zeilen, ohne Zugangsdaten und gekürzt.
fn tail_lines(text: &str) -> Vec<String> {
    let lines: Vec<&str> = text.lines().collect();
    let start = lines.len().saturating_sub(MAX_TAIL_LINES);
    lines[start..].iter().map(|l| truncate(&redact(l), MAX_LOG_LINE)).collect()
}

/// Entfernt Zugangsdaten aus Log-Text (Session-ID alter Versionen, `--accessToken`).
pub fn redact(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for (i, line) in text.split('\n').enumerate() {
        if i > 0 {
            out.push('\n');
        }
        out.push_str(&redact_line(line));
    }
    out
}

fn redact_line(line: &str) -> String {
    let mut line = line.to_owned();
    if let Some(pos) = line.find("(Session ID is ") {
        line.truncate(pos);
        line.push_str("(Session ID is <redacted>)");
    }
    if let Some(pos) = line.find("--accessToken") {
        let after = pos + "--accessToken".len();
        let rest = line[after..].trim_start_matches([' ', ',', '=']);
        let token_end = rest.find([' ', ',', ']']).unwrap_or(rest.len());
        let suffix = rest[token_end..].to_owned();
        line.truncate(after);
        line.push_str(" <redacted>");
        line.push_str(&suffix);
    }
    line
}

/// Liest und löscht das Ende der letzten Sitzung (von der Engine vor `exit` geschrieben).
pub fn take_last_session(home_dir: &Path) -> Option<GameStateEvent> {
    let path = home_dir.join(LAST_SESSION_FILE);
    let raw = std::fs::read(&path).ok()?;
    let _ = std::fs::remove_file(&path);
    if raw.len() > 256 * 1024 {
        return None;
    }
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Raw {
        session: String,
        code: i32,
        #[serde(default)]
        log_tail: Option<String>,
    }
    let raw: Raw = serde_json::from_slice(&raw).ok()?;
    let mut tracker = SessionTracker { session: truncate(&raw.session, 64), phase: Phase::Running };
    match tracker.apply(EngineEvent::Exited { code: raw.code, log_tail: raw.log_tail }) {
        Some(Output::State(ev)) => Some(ev),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn happy_path() {
        let (mut t, first) = SessionTracker::new("s", true);
        assert!(matches!(first, Output::State(GameStateEvent { state: GameState::Starting, .. })));
        assert_eq!(t.apply(EngineEvent::JitWaiting), None);
        assert_eq!(t.apply(EngineEvent::JitReady), None);
        assert_eq!(t.apply(EngineEvent::Starting), None);
        assert!(matches!(t.apply(EngineEvent::Running), Some(Output::State(GameStateEvent { state: GameState::Running, .. }))));
        assert_eq!(t.apply(EngineEvent::Running), None);
        let log = t.apply(EngineEvent::Log { line: "hi".into() });
        assert!(matches!(log, Some(Output::Log(GameLogEvent { ref lines, .. })) if lines == &["hi"]));
        let end = t.apply(EngineEvent::Exited { code: 0, log_tail: Some("bye".into()) });
        assert!(matches!(end, Some(Output::State(GameStateEvent { state: GameState::Exited, exit_code: Some(0), .. }))));
        assert!(t.finished());
        assert_eq!(t.apply(EngineEvent::Log { line: "late".into() }), None);
        assert_eq!(t.apply(EngineEvent::Exited { code: 1, log_tail: None }), None);
    }

    #[test]
    fn crash_and_cancel_and_failure() {
        let (mut t, _) = SessionTracker::new("s", false);
        assert!(matches!(t.apply(EngineEvent::Exited { code: 137, log_tail: None }), Some(Output::State(GameStateEvent { state: GameState::Crashed, exit_code: Some(137), .. }))));
        let (mut t, _) = SessionTracker::new("s", true);
        assert!(matches!(t.apply(EngineEvent::Cancelled), Some(Output::State(GameStateEvent { state: GameState::Exited, exit_code: None, .. }))));
        let (mut t, _) = SessionTracker::new("s", false);
        let failed = t.apply(EngineEvent::Failed { reason: "jli_missing".into() });
        assert!(matches!(failed, Some(Output::State(GameStateEvent { state: GameState::Crashed, ref log_tail, .. })) if log_tail == &["jli_missing"]));
    }

    #[test]
    fn jit_waiting_only_before_running() {
        let (mut t, _) = SessionTracker::new("s", false);
        t.apply(EngineEvent::Running);
        assert_eq!(t.apply(EngineEvent::JitWaiting), None);
        assert_eq!(t.phase, Phase::Running);
    }

    #[test]
    fn swift_event_json() {
        let ev: EngineEvent = serde_json::from_str(r#"{"type":"exited","code":1,"logTail":"x"}"#).unwrap();
        assert_eq!(ev, EngineEvent::Exited { code: 1, log_tail: Some("x".into()) });
        let ev: EngineEvent = serde_json::from_str(r#"{"type":"jitWaiting"}"#).unwrap();
        assert_eq!(ev, EngineEvent::JitWaiting);
        let ev: EngineEvent = serde_json::from_str(r#"{"type":"failed","reason":"jli_missing"}"#).unwrap();
        assert_eq!(ev, EngineEvent::Failed { reason: "jli_missing".into() });
    }

    #[test]
    fn redacts_tokens() {
        assert_eq!(redact("Setting user: Steve (Session ID is token:abc:def)"), "Setting user: Steve (Session ID is <redacted>)");
        assert_eq!(redact("args [--username, Steve, --accessToken, eyJ.abc, --uuid, x]"), "args [--username, Steve, --accessToken <redacted>, --uuid, x]");
        assert_eq!(redact("--accessToken eyJ.abc --version 1.8.9"), "--accessToken <redacted> --version 1.8.9");
        assert_eq!(redact("a\nb (Session ID is x)"), "a\nb (Session ID is <redacted>)");
        assert_eq!(redact("nothing here"), "nothing here");
    }

    #[test]
    fn tail_and_truncate_respect_utf8() {
        assert_eq!(truncate("äöü", 3), "ä");
        let many: String = (0..300).map(|i| format!("z{i}\n")).collect();
        let tail = tail_lines(&many);
        assert_eq!(tail.len(), MAX_TAIL_LINES);
        assert_eq!(tail.last().map(String::as_str), Some("z299"));
        assert!(tail_lines("").is_empty());
    }

    #[test]
    fn last_session_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        assert!(take_last_session(dir.path()).is_none());
        std::fs::write(dir.path().join(LAST_SESSION_FILE), r#"{"session":"ios-5","code":1,"logTail":"(Session ID is a:b)"}"#).unwrap();
        let ev = take_last_session(dir.path()).unwrap();
        assert_eq!(ev.session, "ios-5");
        assert_eq!(ev.state, GameState::Crashed);
        assert_eq!(ev.log_tail, vec!["(Session ID is <redacted>)".to_string()]);
        assert!(!dir.path().join(LAST_SESSION_FILE).exists());
        std::fs::write(dir.path().join(LAST_SESSION_FILE), "kaputt").unwrap();
        assert!(take_last_session(dir.path()).is_none());
    }
}
