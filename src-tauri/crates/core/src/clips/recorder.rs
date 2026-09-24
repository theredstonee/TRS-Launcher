//! Ein laufender FFmpeg-Prozess, der das Spielfenster in 2-Sekunden-Segmente
//! aufnimmt, plus der Ton-Mischer, der ihm über stdin zuliefert.

use std::collections::VecDeque;
use std::io::{BufRead, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use super::audio::AudioPipe;
use super::encoder::{self, Codec, Plan};
use crate::{Error, Result};

const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const BELOW_NORMAL_PRIORITY_CLASS: u32 = 0x0000_4000;
const TAIL_LINES: usize = 40;

/// FFmpeg-Aufruf ohne Konsolenfenster.
pub fn command(exe: &Path) -> std::process::Command {
    #[cfg(windows)]
    use std::os::windows::process::CommandExt;
    let mut cmd = std::process::Command::new(exe);
    #[cfg(windows)]
    cmd.creation_flags(CREATE_NO_WINDOW);
    cmd.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped());
    cmd
}

/// Kurzer FFmpeg-Lauf (Probe, Export, Vorschaubild) mit Zeitlimit.
pub async fn run(exe: &Path, args: Vec<String>, timeout: Duration) -> Result<()> {
    let exe = exe.to_owned();
    tokio::task::spawn_blocking(move || {
        let mut child = command(&exe).args(&args).spawn().map_err(|e| Error::Internal(format!("ffmpeg: {e}")))?;
        let stderr = child.stderr.take();
        let tail = Arc::new(Mutex::new(VecDeque::new()));
        let reader = stderr.map(|s| spawn_tail(s, tail.clone()));
        let started = std::time::Instant::now();
        let status = loop {
            if let Some(status) = child.try_wait().map_err(|e| Error::Internal(format!("ffmpeg: {e}")))? {
                break status;
            }
            if started.elapsed() > timeout {
                let _ = child.kill();
                let _ = child.wait();
                return Err(Error::Internal("ffmpeg: Zeitüberschreitung".into()));
            }
            std::thread::sleep(Duration::from_millis(20));
        };
        if let Some(r) = reader {
            let _ = r.join();
        }
        if status.success() {
            Ok(())
        } else {
            let text = tail.lock().unwrap_or_else(std::sync::PoisonError::into_inner).iter().cloned().collect::<Vec<_>>().join(" | ");
            Err(Error::Internal(format!("ffmpeg ({status}): {text}")))
        }
    })
    .await
    .map_err(|e| Error::Internal(e.to_string()))?
}

fn spawn_tail(stderr: impl std::io::Read + Send + 'static, tail: Arc<Mutex<VecDeque<String>>>) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || {
        let reader = std::io::BufReader::new(stderr);
        for line in reader.split(b'\n').map_while(std::result::Result::ok) {
            let line = String::from_utf8_lossy(&line).trim().to_owned();
            if line.is_empty() {
                continue;
            }
            let mut tail = tail.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if tail.len() >= TAIL_LINES {
                tail.pop_front();
            }
            tail.push_back(line);
        }
    })
}

/// Nummern aller Segmente im Ordner (aufsteigend).
pub fn segments(dir: &Path) -> Vec<u64> {
    let mut list: Vec<u64> = std::fs::read_dir(dir)
        .map(|entries| {
            entries
                .flatten()
                .filter_map(|e| e.file_name().to_str().and_then(encoder::parse_segment_name))
                .collect()
        })
        .unwrap_or_default();
    list.sort_unstable();
    list
}

pub struct Recorder {
    child: Child,
    audio: Option<AudioPipe>,
    tail: Arc<Mutex<VecDeque<String>>>,
    pub codec: Codec,
    pub dir: PathBuf,
}

impl Recorder {
    /// Startet FFmpeg (und ggf. den Ton) für `plan`; Segmente landen in `dir`.
    pub fn spawn(exe: &Path, dir: &Path, plan: &Plan, system_audio: bool, microphone: bool) -> Result<Self> {
        #[cfg(windows)]
        use std::os::windows::process::CommandExt;
        std::fs::create_dir_all(dir).map_err(|e| Error::io(dir, e))?;
        let mut cmd = command(exe);
        cmd.args(plan.record_args(dir));
        cmd.stdin(Stdio::piped());
        #[cfg(windows)]
        if plan.codec == Codec::X264 {
            // Software-Kodierung soll dem Spiel keine Rechenzeit wegnehmen.
            cmd.creation_flags(CREATE_NO_WINDOW | BELOW_NORMAL_PRIORITY_CLASS);
        }
        let mut child = cmd.spawn().map_err(|e| Error::Internal(format!("ffmpeg: {e}")))?;
        let tail = Arc::new(Mutex::new(VecDeque::new()));
        if let Some(stderr) = child.stderr.take() {
            spawn_tail(stderr, tail.clone());
        }
        let audio = if plan.audio {
            let stdin = child.stdin.take().ok_or_else(|| Error::Internal("ffmpeg: kein stdin".into()))?;
            Some(AudioPipe::start(system_audio, microphone, plan.origin_us, stdin))
        } else {
            None
        };
        tracing::info!("Aufnahme gestartet: {} {}×{} @{} fps, Ton: {}", plan.codec.ffmpeg_name(), plan.width, plan.height, plan.fps, plan.audio);
        Ok(Self { child, audio, tail, codec: plan.codec, dir: dir.to_owned() })
    }

    pub fn pid(&self) -> u32 {
        self.child.id()
    }

    /// Läuft FFmpeg noch?
    pub fn alive(&mut self) -> bool {
        matches!(self.child.try_wait(), Ok(None))
    }

    /// Letzte Zeilen der FFmpeg-Ausgabe (für das Log).
    pub fn tail(&self) -> String {
        self.tail.lock().unwrap_or_else(std::sync::PoisonError::into_inner).iter().cloned().collect::<Vec<_>>().join(" | ")
    }

    /// Beenden: ohne Ton sauber per `q`, sonst schließt das Ende des Tons den Eingang.
    /// Fertige Segmente sind in jedem Fall vollständig.
    pub fn stop(&mut self) {
        if let Some(mut audio) = self.audio.take() {
            audio.stop();
        } else if let Some(mut stdin) = self.child.stdin.take() {
            let _ = stdin.write_all(b"q");
            let _ = stdin.flush();
        }
        for _ in 0..40 {
            if !self.alive() {
                return;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        // Nur der eigene Prozess (über sein Handle), nie nach Namen.
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl Drop for Recorder {
    fn drop(&mut self) {
        if self.alive() {
            self.stop();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn segmente_werden_sortiert_gelesen() {
        let dir = tempfile::tempdir().unwrap();
        for name in ["seg000010.ts", "seg000002.ts", "seg000003.ts", "list.txt", "segxx.ts"] {
            std::fs::write(dir.path().join(name), b"").unwrap();
        }
        assert_eq!(segments(dir.path()), vec![2, 3, 10]);
        assert!(segments(&dir.path().join("fehlt")).is_empty());
    }
}
