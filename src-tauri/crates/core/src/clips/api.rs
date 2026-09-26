//! Clip-Bibliothek für die Oberfläche (Auflisten, Umbenennen, Löschen, Speicherplatz).

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

use chrono::{DateTime, Utc};
use serde::Serialize;

use super::edit::{self, ClipStrip, TrimMode, TrimPlan, TrimRequest};
use super::library::{self, ClipUsage};
use super::media::{self, MediaInfo};
use super::serve;
use crate::link::{HandlerResult, LinkPreview};
use crate::{Error, Launcher, Result};

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipView {
    pub instance_id: String,
    /// Name der Instanz; gelöschte Instanzen behalten ihre Clips (dann die ID).
    pub instance_name: String,
    pub file_name: String,
    pub size: u64,
    pub created_at: Option<DateTime<Utc>>,
    pub duration_ms: Option<u64>,
}

/// „Im Launcher öffnen“ aus dem Spiel (Tauri-Ereignis `clip-open`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipOpenRequest {
    pub instance_id: String,
    pub file_name: String,
}

pub type ClipOpenSink = Arc<dyn Fn(ClipOpenRequest) + Send + Sync>;

impl Launcher {
    /// Die App holt das Fenster nach vorn und öffnet den Player.
    pub fn set_clip_open_sink(&self, sink: ClipOpenSink) {
        *self.clip_open_sink.write().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(sink);
    }

    /// Nur ein laufendes Spiel darf fragen – und nur nach Clips seiner eigenen Instanz.
    async fn game_clip(&self, instance_id: &str, clip: &str) -> HandlerResult<PathBuf> {
        if !self.games.is_running(instance_id) {
            return Err("not_allowed");
        }
        self.clip_path(instance_id, clip).await.map_err(|_| "unknown_clip")
    }

    /// `clips.preview` aus dem Spiel: Vorschau-Leiste (erzeugt bzw. aus dem Cache).
    pub(crate) async fn clip_preview_for_game(&self, instance_id: &str, clip: &str) -> HandlerResult<LinkPreview> {
        let video = self.game_clip(instance_id, clip).await?;
        match self.clips().strip(&video).await {
            Ok(Some((png, strip))) => Ok(LinkPreview {
                path: png.to_str().ok_or("error")?.to_owned(),
                frames: strip.frames,
                cols: strip.cols,
                rows: strip.rows,
                frame_width: strip.frame_width,
                frame_height: strip.frame_height,
                interval_ms: strip.interval_ms,
                duration_ms: strip.duration_ms,
            }),
            Ok(None) => Err("no_ffmpeg"),
            Err(e) => {
                tracing::warn!("Clip-Vorschau für das Spiel fehlgeschlagen: {e}");
                Err("error")
            }
        }
    }

    /// `clips.open` aus dem Spiel: Launcher nach vorn, Player mit diesem Clip.
    pub(crate) async fn open_clip_from_game(&self, instance_id: &str, clip: &str) -> HandlerResult<()> {
        self.game_clip(instance_id, clip).await?;
        let sink = self.clip_open_sink.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
        let sink = sink.ok_or("error")?;
        tracing::info!("Clip aus dem Spiel im Launcher öffnen ('{instance_id}')");
        sink(ClipOpenRequest { instance_id: instance_id.to_owned(), file_name: clip.to_owned() });
        Ok(())
    }

    /// Ordner aller Clips (Einstellung oder `<Daten>/clips`).
    pub async fn clips_root(&self) -> PathBuf {
        library::clips_root(self.paths(), &self.settings().await.clips)
    }

    pub async fn list_clips(&self) -> Result<Vec<ClipView>> {
        let root = self.clips_root().await;
        let clips = tokio::task::spawn_blocking(move || library::list(&root))
            .await
            .map_err(|e| Error::Internal(e.to_string()))?;
        let names: HashMap<String, String> =
            self.instances().list().await.unwrap_or_default().into_iter().map(|i| (i.id, i.name)).collect();
        Ok(clips
            .into_iter()
            .map(|c| ClipView {
                instance_name: names.get(&c.instance_id).cloned().unwrap_or_else(|| c.instance_id.clone()),
                instance_id: c.instance_id,
                file_name: c.file_name,
                size: c.size,
                created_at: c.created_at,
                duration_ms: c.duration_ms,
            })
            .collect())
    }

    /// Geprüfter Pfad eines Clips (zum Abspielen, Zeigen, Löschen).
    pub async fn clip_path(&self, instance_id: &str, file_name: &str) -> Result<PathBuf> {
        library::clip_path(&self.clips_root().await, instance_id, file_name)
    }

    pub async fn clip_usage(&self) -> Result<ClipUsage> {
        let settings = self.settings().await.clips;
        let root = library::clips_root(self.paths(), &settings);
        tokio::task::spawn_blocking(move || library::usage(&root, &settings))
            .await
            .map_err(|e| Error::Internal(e.to_string()))
    }

    /// Neuer Dateiname (ohne Pfad; `.mp4` wird ergänzt). Liefert den tatsächlichen Namen.
    pub async fn rename_clip(&self, instance_id: &str, file_name: &str, new_name: &str) -> Result<String> {
        let root = self.clips_root().await;
        let (id, file, new) = (instance_id.to_owned(), file_name.to_owned(), new_name.to_owned());
        tokio::task::spawn_blocking(move || library::rename(&root, &id, &file, &new))
            .await
            .map_err(|e| Error::Internal(e.to_string()))?
    }

    /// In den Papierkorb (sonst endgültig löschen).
    pub async fn trash_clip(&self, instance_id: &str, file_name: &str) -> Result<()> {
        let path = self.clip_path(instance_id, file_name).await?;
        tokio::task::spawn_blocking(move || {
            if crate::screenshots::recycle(&path).is_err() {
                std::fs::remove_file(&path).map_err(|e| Error::io(&path, e))?;
            }
            Ok(())
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))?
    }

    /// Ein einzelner Clip wie in der Liste.
    pub async fn clip_view(&self, instance_id: &str, file_name: &str) -> Result<ClipView> {
        let path = self.clip_path(instance_id, file_name).await?;
        let meta = tokio::fs::metadata(&path).await.map_err(|e| Error::io(&path, e))?;
        let name = self.instances().get(instance_id).await.map(|i| i.name).unwrap_or_else(|_| instance_id.to_owned());
        let probe = path.clone();
        let duration_ms = tokio::task::spawn_blocking(move || library::mp4_duration_ms(&probe)).await.ok().flatten();
        Ok(ClipView {
            instance_id: instance_id.to_owned(),
            instance_name: name,
            file_name: file_name.to_owned(),
            size: meta.len(),
            created_at: meta.modified().ok().map(DateTime::<Utc>::from),
            duration_ms,
        })
    }

    /// Dauer, Größe und Keyframes (für Zeitleiste und Zuschneiden).
    pub async fn clip_details(&self, instance_id: &str, file_name: &str) -> Result<MediaInfo> {
        let path = self.clip_path(instance_id, file_name).await?;
        tokio::task::spawn_blocking(move || media::probe(&path))
            .await
            .map_err(|e| Error::Internal(e.to_string()))?
            .ok_or_else(|| Error::validation(crate::msg!("clips.unreadable", "Der Clip lässt sich nicht lesen.")))
    }

    /// Vorschau-Leiste (Pfad + Aufbau); `None` ohne FFmpeg.
    pub async fn clip_strip(&self, instance_id: &str, file_name: &str) -> Result<Option<(std::path::PathBuf, ClipStrip)>> {
        let path = self.clip_path(instance_id, file_name).await?;
        self.clips().strip(&path).await
    }

    /// Was beim Zuschneiden passieren würde (Kopieren oder Neukodieren).
    pub async fn plan_clip_trim(&self, instance_id: &str, file_name: &str, start_ms: u64, end_ms: u64, mode: TrimMode) -> Result<TrimPlan> {
        let info = self.clip_details(instance_id, file_name).await?;
        edit::plan_trim(&info, start_ms, end_ms, mode)
    }

    /// Schneidet einen Bereich als **neuen** Clip aus (das Original bleibt).
    /// `progress` bekommt beim Neukodieren 0–100.
    pub async fn trim_clip(
        &self,
        instance_id: &str,
        file_name: &str,
        request: &TrimRequest,
        progress: Option<Arc<dyn Fn(f64) + Send + Sync>>,
    ) -> Result<ClipView> {
        let path = self.clip_path(instance_id, file_name).await?;
        let info = self.clip_details(instance_id, file_name).await?;
        let plan = edit::plan_trim(&info, request.start_ms, request.end_ms, request.mode)?;
        let Some(exe) = self.clips().ffmpeg_exe().await else {
            return Err(Error::validation(crate::msg!("clips.noFfmpeg", "Zum Zuschneiden wird FFmpeg gebraucht – lade es in den Clip-Einstellungen.")));
        };
        let settings = self.settings().await.clips;
        let codecs = if plan.method == edit::TrimMethod::Reencode { self.clips().trim_codecs(&exe, &settings).await } else { Vec::new() };
        let size = tokio::fs::metadata(&path).await.map(|m| m.len()).unwrap_or(0);
        let kbps = edit::source_kbps(size, info.duration_ms);
        let length_us = (plan.end_ms - plan.start_ms).max(1) * 1000;
        let progress: Option<edit::Progress> = progress.map(|p| -> edit::Progress {
            Arc::new(move |us: u64| {
                #[allow(clippy::cast_precision_loss)]
                p((us as f64 / length_us as f64 * 100.0).clamp(0.0, 100.0));
            })
        });
        let runner = edit::ProcessRunner { exe };
        tracing::info!("Clip zuschneiden: {file_name} {}–{} ms ({:?})", plan.start_ms, plan.end_ms, plan.method);
        let name = edit::trim(&runner, &path, &plan, &request.name, &codecs, kbps, progress).await.map_err(|e| {
            tracing::warn!("Zuschneiden fehlgeschlagen: {e}");
            match e {
                Error::Validation(_) => e,
                _ => Error::validation(crate::msg!("clips.trimFailed", "Der Clip konnte nicht zugeschnitten werden.")),
            }
        })?;
        self.clip_view(instance_id, &name).await
    }

    /// Kopiert einen Clip an einen gewählten Ort (Speichern-Dialog der App).
    pub async fn export_clip(&self, instance_id: &str, file_name: &str, dest: &std::path::Path) -> Result<()> {
        let path = self.clip_path(instance_id, file_name).await?;
        if dest == path {
            return Ok(());
        }
        let tmp = dest.with_extension("mp4.part");
        tokio::fs::copy(&path, &tmp).await.map_err(|e| Error::io(&tmp, e))?;
        tokio::fs::rename(&tmp, dest).await.map_err(|e| Error::io(dest, e))
    }

    /// Legt den Clip als Datei in die Zwischenablage.
    pub async fn copy_clip_file(&self, instance_id: &str, file_name: &str) -> Result<()> {
        let path = self.clip_path(instance_id, file_name).await?;
        tokio::task::spawn_blocking(move || super::share::copy_files(&[path])).await.map_err(|e| Error::Internal(e.to_string()))?
    }

    /// Anfrage an das `trsclip:`-Protokoll beantworten (nur Dateien im Clip-Ordner).
    pub async fn serve_clip(&self, path: &str, range: Option<String>) -> serve::Response {
        let Some(request) = serve::parse_path(path) else { return serve::Response::status(400) };
        let root = self.clips_root().await;
        let Ok(video) = serve::resolve(&root, &request) else { return serve::Response::status(404) };
        let file = match request.asset {
            serve::Asset::Video => Some(video),
            serve::Asset::Poster => self.clips().thumbnail(&video).await.ok().flatten(),
            serve::Asset::Strip => self.clips().strip(&video).await.ok().flatten().map(|(png, _)| png),
        };
        let Some(file) = file else { return serve::Response::status(404) };
        let asset = request.asset;
        tokio::task::spawn_blocking(move || serve::respond_file(&file, asset, range.as_deref()))
            .await
            .unwrap_or_else(|_| serve::Response::status(500))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Anfragen aus dem Spiel und das `trsclip:`-Protokoll gegen einen echten Launcher (ohne FFmpeg).
    #[cfg(windows)]
    #[tokio::test]
    async fn spiel_darf_nur_eigene_clips_oeffnen_und_protokoll_bleibt_im_ordner() {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;

        let dir = tempfile::tempdir().unwrap();
        let mut game = std::process::Command::new("cmd")
            .args(["/c", "ping -n 120 127.0.0.1 >nul"])
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .unwrap();
        let handle = crate::platform::ProcessHandle::open(game.id()).unwrap();
        let record = serde_json::json!([{
            "instanceId": "survival",
            "pid": game.id(),
            "startedAt": "2026-09-26T10:00:00Z",
            "creationTime": handle.creation_time().unwrap(),
            "stdoutLog": dir.path().join("stdout.log"),
            "stderrLog": dir.path().join("stderr.log"),
        }]);
        std::fs::write(dir.path().join("running.json"), record.to_string()).unwrap();
        let launcher = Arc::new(Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap());
        let clips = dir.path().join("clips").join("survival");
        std::fs::create_dir_all(&clips).unwrap();
        std::fs::write(clips.join("a.mp4"), super::super::media::tests::sample_mp4(10)).unwrap();
        std::fs::write(dir.path().join("geheim.mp4"), b"x").unwrap();
        let opened = Arc::new(std::sync::Mutex::new(Vec::new()));
        let sink = opened.clone();
        launcher.set_clip_open_sink(Arc::new(move |r| sink.lock().unwrap().push(r)));

        // Öffnen: nur laufendes Spiel, nur Clips seiner Instanz.
        assert_eq!(launcher.open_clip_from_game("survival", "a.mp4").await, Ok(()));
        assert_eq!(
            *opened.lock().unwrap(),
            vec![ClipOpenRequest { instance_id: "survival".into(), file_name: "a.mp4".into() }]
        );
        assert_eq!(launcher.open_clip_from_game("survival", "b.mp4").await, Err("unknown_clip"));
        assert_eq!(launcher.open_clip_from_game("survival", "../geheim.mp4").await, Err("unknown_clip"));
        assert_eq!(launcher.open_clip_from_game("fremd", "a.mp4").await, Err("not_allowed"));
        assert_eq!(opened.lock().unwrap().len(), 1);
        // Vorschau ohne FFmpeg: klarer Code statt Fehler.
        assert_eq!(launcher.clip_preview_for_game("survival", "a.mp4").await, Err("no_ffmpeg"));
        assert_eq!(launcher.clip_preview_for_game("fremd", "a.mp4").await, Err("not_allowed"));

        // Index und Zuschneiden (ohne FFmpeg: verständlicher Fehler, nichts angelegt).
        let info = launcher.clip_details("survival", "a.mp4").await.unwrap();
        assert_eq!(info.keyframes_ms, vec![0, 2000, 4000, 6000, 8000]);
        let request = TrimRequest { start_ms: 2000, end_ms: 6000, mode: TrimMode::Auto, name: "kurz".into() };
        let err = launcher.trim_clip("survival", "a.mp4", &request, None).await.unwrap_err();
        assert!(matches!(&err, Error::Validation(m) if m.code == "clips.noFfmpeg"), "{err:?}");
        assert!(!clips.join("kurz.mp4").exists());

        // Protokoll: Bereiche, nur Clip-Ordner.
        let part = launcher.serve_clip("/v/survival/a.mp4", Some("bytes=0-7".into())).await;
        assert_eq!(part.status, 206);
        assert_eq!(part.body.len(), 8);
        assert_eq!(launcher.serve_clip("/v/survival/..%2F..%2Fgeheim.mp4", None).await.status, 400);
        assert_eq!(launcher.serve_clip("/v/survival/fehlt.mp4", None).await.status, 404);
        assert_eq!(launcher.serve_clip("/p/survival/a.mp4", None).await.status, 404, "ohne FFmpeg kein Vorschaubild");

        let _ = game.kill();
        let _ = game.wait();
    }
}
