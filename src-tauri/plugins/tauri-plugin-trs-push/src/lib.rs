//! Push-Benachrichtigungen der TRS-Apps (Vertrag `api/API.md` §33).
//!
//! - **Android:** UnifiedPush über den Verteiler des Nutzers (ntfy, NextPush …). Die Bibliothek
//!   `org.unifiedpush.android:connector` erzeugt die Web-Push-Schlüssel (privater Schlüssel mit einem
//!   Android-Keystore-Schlüssel versiegelt), entschlüsselt Nachrichten (RFC 8291) und das Plugin zeigt sie
//!   als Benachrichtigung – auch bei geschlossener App. Die Adresse und die öffentlichen Schlüssel holt sich
//!   der Kern über [`TrsPush::state`] und meldet das Gerät beim Server an.
//! - **iOS:** kein APNs (Sideload). Der Kern meldet ein Abhol-Gerät an; [`TrsPush::set_poll`] plant den
//!   Hintergrundabruf, [`TrsPush::notify`] zeigt geholte Hinweise als lokale Benachrichtigung.
//!
//! Auf dem Desktop gibt es nichts davon: [`TrsPush::state`] meldet „nicht unterstützt“.

use serde::{Deserialize, Serialize};
use tauri::plugin::{Builder, TauriPlugin};
use tauri::{Manager, Runtime};

#[cfg(mobile)]
mod mobile;

/// Fehler des nativen Teils (Code wie `no_distributor`).
#[derive(Debug)]
pub struct Error(pub String);

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;

/// Ein installierter UnifiedPush-Verteiler.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Distributor {
    /// Paketname, z. B. `io.heckel.ntfy`.
    pub id: String,
    /// Name der App.
    pub name: String,
}

/// Zustand auf dem Gerät.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PushState {
    /// Installierte Verteiler (nur Android).
    pub distributors: Vec<Distributor>,
    /// Gewählter Verteiler (Paketname), falls einer bestätigt ist.
    pub distributor: Option<String>,
    /// UnifiedPush-Adresse (Geheimnis – nie ins Log, nie ans Webview).
    pub endpoint: Option<String>,
    /// Öffentlicher Web-Push-Schlüssel (P-256, unkomprimiert, base64url).
    pub p256dh: Option<String>,
    /// Auth-Geheimnis (16 Byte, base64url).
    pub auth: Option<String>,
    /// Die Adresse kommt von einem Ersatz-Verteiler und wechselt bald.
    pub temporary: bool,
    /// Letzter Fehler des Verteilers (`NETWORK`, `ACTION_REQUIRED`, `VAPID_REQUIRED`, `INTERNAL_ERROR`).
    pub failure: Option<String>,
    /// Gerätename für die Geräteliste (z. B. „Google Pixel 8“).
    pub device_name: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RegisterArgs<'a> {
    vapid: Option<&'a str>,
    distributor: Option<&'a str>,
}

/// Eine Benachrichtigung zum Anzeigen (geprüfter Inhalt aus §33.5).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalNotification {
    pub id: String,
    pub category: String,
    pub title: String,
    pub body: String,
    pub target: String,
    pub collapse: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct NotifyArgs<'a> {
    notifications: &'a [LocalNotification],
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PollArgs<'a> {
    enabled: bool,
    /// Datenordner des Launchers (Android: für die Hintergrundarbeit).
    root: &'a str,
}

/// Zugriff auf den nativen Teil.
pub struct TrsPush<R: Runtime> {
    #[cfg(mobile)]
    mobile: mobile::Native<R>,
    #[cfg(not(mobile))]
    _marker: std::marker::PhantomData<fn() -> R>,
}

impl<R: Runtime> TrsPush<R> {
    /// Verteiler, Adresse und Schlüssel (Android); Gerätename (beide).
    pub fn state(&self) -> Result<PushState> {
        #[cfg(mobile)]
        return self.mobile.call("state", ());
        #[cfg(not(mobile))]
        Err(Error("unsupported".into()))
    }

    /// Android: beim Verteiler anmelden (mit dem VAPID-Schlüssel des Servers). Ohne gewählten Verteiler nimmt
    /// das Plugin den einzigen installierten oder lässt den Nutzer wählen. Wartet kurz auf die Adresse.
    pub fn register(&self, vapid: Option<&str>, distributor: Option<&str>) -> Result<PushState> {
        #[cfg(mobile)]
        return self.mobile.call("register", RegisterArgs { vapid, distributor });
        #[cfg(not(mobile))]
        {
            let _ = RegisterArgs { vapid, distributor };
            Err(Error("unsupported".into()))
        }
    }

    /// Android: beim Verteiler abmelden und die Adresse vergessen.
    pub fn unregister(&self) -> Result<()> {
        #[cfg(mobile)]
        return self.mobile.call::<serde_json::Value, _>("unregister", ()).map(|_| ());
        #[cfg(not(mobile))]
        Err(Error("unsupported".into()))
    }

    /// Hinweise als Systembenachrichtigung zeigen (Abholen).
    pub fn notify(&self, notifications: &[LocalNotification]) -> Result<()> {
        #[cfg(mobile)]
        return self.mobile.call::<serde_json::Value, _>("notify", NotifyArgs { notifications }).map(|_| ());
        #[cfg(not(mobile))]
        {
            let _ = NotifyArgs { notifications };
            Err(Error("unsupported".into()))
        }
    }

    /// Android: IDs der zuletzt als Benachrichtigung gezeigten Ereignisse (für den Echtzeit-Kanal).
    pub fn shown_ids(&self) -> Result<Vec<String>> {
        #[derive(Deserialize)]
        struct Ids {
            #[serde(default)]
            ids: Vec<String>,
        }
        #[cfg(mobile)]
        return self.mobile.call::<Ids, _>("shownIds", ()).map(|r| r.ids.into_iter().take(200).collect());
        #[cfg(not(mobile))]
        {
            let _ = Ids { ids: Vec::new() }.ids;
            Err(Error("unsupported".into()))
        }
    }

    /// Android: die Verteiler-App öffnen (ntfy verbindet sich erst nach dem ersten Öffnen).
    pub fn open_distributor(&self) -> Result<()> {
        #[cfg(mobile)]
        return self.mobile.call::<serde_json::Value, _>("openDistributor", ()).map(|_| ());
        #[cfg(not(mobile))]
        Err(Error("unsupported".into()))
    }

    /// iOS: Hintergrundabruf planen (`enabled`) oder abbestellen. Android macht das die App selbst.
    pub fn set_poll(&self, enabled: bool, root: &str) -> Result<()> {
        #[cfg(mobile)]
        return self.mobile.call::<serde_json::Value, _>("setPoll", PollArgs { enabled, root }).map(|_| ());
        #[cfg(not(mobile))]
        {
            let _ = PollArgs { enabled, root };
            Err(Error("unsupported".into()))
        }
    }
}

pub trait TrsPushExt<R: Runtime> {
    fn trs_push(&self) -> &TrsPush<R>;
}

impl<R: Runtime, T: Manager<R>> TrsPushExt<R> for T {
    fn trs_push(&self) -> &TrsPush<R> {
        self.state::<TrsPush<R>>().inner()
    }
}

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("trs-push")
        .setup(|app, api| {
            #[cfg(mobile)]
            app.manage(TrsPush { mobile: mobile::Native::init(api)? });
            #[cfg(not(mobile))]
            {
                let _ = api;
                app.manage(TrsPush::<R> { _marker: std::marker::PhantomData });
            }
            Ok(())
        })
        .build()
}
