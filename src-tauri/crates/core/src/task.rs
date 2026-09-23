//! Steuerung laufender Hintergrund-Aufgaben: Abbrechen, Pausieren und ein
//! Byte-Zähler für Geschwindigkeit, Größe und Restzeit.
//!
//! Die Steuerung hängt als Task-Local an der Aufgabe ([`TaskControl::scope`]).
//! So sieht jeder Download darin sie automatisch, ohne dass sie durch alle
//! Funktionen gereicht werden muss. Außerhalb einer Aufgabe sind alle Helfer
//! wirkungslos (kein Abbruch, keine Pause, nichts wird gezählt).
//!
//! Abbrechen ist kooperativ: [`checkpoint`] liefert danach
//! [`Error::Cancelled`], der Fehler läuft über die normalen Fehlerpfade (und
//! deren Aufräumarbeiten) nach oben. Der Zustand bleibt gesetzt, damit auch
//! Stellen, die einen Fehler bewusst schlucken, spätestens beim nächsten
//! Prüfpunkt anhalten.

use std::future::Future;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration;

use tokio::sync::Notify;

use crate::{Error, Result};

tokio::task_local! {
    static CURRENT: TaskControl;
}

#[derive(Default)]
struct Inner {
    cancelled: AtomicBool,
    paused: AtomicBool,
    /// Weckt Wartende bei Abbruch und beim Fortsetzen.
    wake: Notify,
    done_bytes: AtomicU64,
    total_bytes: AtomicU64,
}

/// Steuerung einer Aufgabe. Klonen teilt denselben Zustand.
#[derive(Clone, Default)]
pub struct TaskControl(Arc<Inner>);

/// Momentaufnahme des Byte-Zählers.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Bytes {
    pub done: u64,
    pub total: u64,
}

impl TaskControl {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cancel(&self) {
        self.0.cancelled.store(true, Ordering::SeqCst);
        self.0.wake.notify_waiters();
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.cancelled.load(Ordering::SeqCst)
    }

    pub fn set_paused(&self, paused: bool) {
        self.0.paused.store(paused, Ordering::SeqCst);
        if !paused {
            self.0.wake.notify_waiters();
        }
    }

    pub fn is_paused(&self) -> bool {
        self.0.paused.load(Ordering::SeqCst)
    }

    pub fn bytes(&self) -> Bytes {
        let total = self.0.total_bytes.load(Ordering::Relaxed);
        let done = self.0.done_bytes.load(Ordering::Relaxed);
        Bytes { done: if total > 0 { done.min(total) } else { done }, total }
    }

    /// Neu bekannte Download-Größe (jeder Download-Stapel meldet seine Summe).
    pub fn add_total(&self, bytes: u64) {
        self.0.total_bytes.fetch_add(bytes, Ordering::Relaxed);
    }

    /// Geladene Bytes; negativ, wenn ein Fehlversuch zurückgenommen wird.
    pub fn add_done(&self, delta: i64) {
        if delta >= 0 {
            self.0.done_bytes.fetch_add(delta as u64, Ordering::Relaxed);
        } else {
            let sub = delta.unsigned_abs();
            let _ = self.0.done_bytes.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| Some(v.saturating_sub(sub)));
        }
    }

    /// Kehrt zurück, sobald die Aufgabe abgebrochen wurde.
    pub async fn cancelled(&self) {
        loop {
            let woken = self.0.wake.notified();
            if self.is_cancelled() {
                return;
            }
            woken.await;
        }
    }

    /// Abgebrochen → Fehler; pausiert → warten, bis es weitergeht.
    pub async fn checkpoint(&self) -> Result<()> {
        loop {
            if self.is_cancelled() {
                return Err(Error::Cancelled);
            }
            if !self.is_paused() {
                return Ok(());
            }
            // Erst anmelden, dann erneut prüfen – sonst geht ein Wecken verloren.
            let woken = self.0.wake.notified();
            if self.is_cancelled() || !self.is_paused() {
                continue;
            }
            woken.await;
        }
    }

    /// Führt `fut` mit dieser Steuerung als aktueller Aufgabe aus.
    pub async fn scope<F: Future>(self, fut: F) -> F::Output {
        CURRENT.scope(self, fut).await
    }
}

/// Führt `fut` als Aufgabe mit `control` aus und meldet währenddessen
/// regelmäßig den Byte-Stand und die Pause – aber nur, wenn sich etwas
/// geändert hat. Am Ende kommt immer noch ein letzter Stand.
pub async fn drive<F: Future>(control: TaskControl, every: Duration, fut: F, report: impl Fn(Bytes, bool)) -> F::Output {
    let work = control.clone().scope(fut);
    tokio::pin!(work);
    let mut tick = tokio::time::interval(every);
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut last = None;
    let output = loop {
        tokio::select! {
            out = &mut work => break out,
            _ = tick.tick() => {
                let now = (control.bytes(), control.is_paused());
                if last != Some(now) {
                    last = Some(now);
                    report(now.0, now.1);
                }
            }
        }
    };
    report(control.bytes(), false);
    output
}

/// Steuerung der Aufgabe, in der der Aufrufer gerade läuft.
pub fn current() -> Option<TaskControl> {
    CURRENT.try_with(TaskControl::clone).ok()
}

/// Prüfpunkt der aktuellen Aufgabe (außerhalb einer Aufgabe immer `Ok`).
pub async fn checkpoint() -> Result<()> {
    match current() {
        Some(control) => control.checkpoint().await,
        None => Ok(()),
    }
}

pub fn is_cancelled() -> bool {
    CURRENT.try_with(TaskControl::is_cancelled).unwrap_or(false)
}

pub(crate) fn add_total(bytes: u64) {
    let _ = CURRENT.try_with(|c| c.add_total(bytes));
}

pub(crate) fn add_done(delta: i64) {
    let _ = CURRENT.try_with(|c| c.add_done(delta));
}

/// Führt `fut` aus, verwirft es aber, sobald die Aufgabe abgebrochen wird
/// (etwa einen Kindprozess mit `kill_on_drop`).
pub async fn or_cancel<F: Future>(fut: F) -> Result<F::Output> {
    match current() {
        Some(control) => tokio::select! {
            out = fut => Ok(out),
            () = control.cancelled() => Err(Error::Cancelled),
        },
        None => Ok(fut.await),
    }
}

/// Wartet `duration`, endet aber sofort mit [`Error::Cancelled`], wenn die
/// Aufgabe abgebrochen wird.
pub async fn sleep(duration: Duration) -> Result<()> {
    match current() {
        Some(control) => tokio::select! {
            () = tokio::time::sleep(duration) => Ok(()),
            () = control.cancelled() => Err(Error::Cancelled),
        },
        None => {
            tokio::time::sleep(duration).await;
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn helpers_are_inert_outside_a_task() {
        assert!(current().is_none());
        assert!(checkpoint().await.is_ok());
        assert!(!is_cancelled());
        add_done(10);
        add_total(10);
    }

    #[tokio::test]
    async fn counts_bytes_inside_the_scope() {
        let control = TaskControl::new();
        control
            .clone()
            .scope(async {
                add_total(100);
                add_done(40);
                add_done(-10);
                add_done(-500);
                add_done(25);
            })
            .await;
        assert_eq!(control.bytes(), Bytes { done: 25, total: 100 });
    }

    #[tokio::test]
    async fn cancel_ends_checkpoints_and_sleeps() {
        let control = TaskControl::new();
        let inner = control.clone();
        let task = tokio::spawn(inner.scope(async {
            checkpoint().await?;
            sleep(Duration::from_secs(60)).await?;
            Ok::<_, Error>(())
        }));
        tokio::time::sleep(Duration::from_millis(20)).await;
        control.cancel();
        let result = tokio::time::timeout(Duration::from_secs(5), task).await.unwrap().unwrap();
        assert!(matches!(result, Err(Error::Cancelled)));
        assert!(control.checkpoint().await.is_err(), "Abbruch bleibt gesetzt");
    }

    #[tokio::test]
    async fn drive_reports_changes_and_a_final_state() {
        let control = TaskControl::new();
        let seen = std::sync::Mutex::new(Vec::new());
        let out = drive(
            control.clone(),
            Duration::from_millis(5),
            async {
                add_total(10);
                add_done(4);
                tokio::time::sleep(Duration::from_millis(40)).await;
                add_done(6);
                7
            },
            |bytes, _| seen.lock().unwrap().push(bytes),
        )
        .await;
        assert_eq!(out, 7);
        let seen = seen.into_inner().unwrap();
        assert!(seen.len() >= 2);
        assert_eq!(*seen.last().unwrap(), Bytes { done: 10, total: 10 });
        // Unverändert → nicht erneut gemeldet (nur der Endstand darf doppelt sein).
        let middle = &seen[..seen.len() - 1];
        assert!(middle.windows(2).all(|w| w[0] != w[1]));
    }

    #[tokio::test]
    async fn or_cancel_drops_the_future() {
        let control = TaskControl::new();
        let inner = control.clone();
        let task = tokio::spawn(inner.scope(or_cancel(tokio::time::sleep(Duration::from_secs(60)))));
        tokio::time::sleep(Duration::from_millis(20)).await;
        control.cancel();
        let result = tokio::time::timeout(Duration::from_secs(5), task).await.unwrap().unwrap();
        assert!(matches!(result, Err(Error::Cancelled)));
        assert!(or_cancel(async { 1 }).await.is_ok(), "außerhalb einer Aufgabe ohne Wirkung");
    }

    #[tokio::test]
    async fn pause_waits_until_resumed_or_cancelled() {
        let control = TaskControl::new();
        control.set_paused(true);
        let waiting = tokio::spawn(control.clone().scope(async { checkpoint().await }));
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert!(!waiting.is_finished(), "pausiert → wartet");
        control.set_paused(false);
        let result = tokio::time::timeout(Duration::from_secs(5), waiting).await.unwrap().unwrap();
        assert!(result.is_ok());

        control.set_paused(true);
        let waiting = tokio::spawn(control.clone().scope(async { checkpoint().await }));
        tokio::time::sleep(Duration::from_millis(30)).await;
        control.cancel();
        let result = tokio::time::timeout(Duration::from_secs(5), waiting).await.unwrap().unwrap();
        assert!(matches!(result, Err(Error::Cancelled)), "Abbruch beendet auch eine Pause");
    }
}
