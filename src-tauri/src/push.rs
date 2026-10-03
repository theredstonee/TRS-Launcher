//! Push-Benachrichtigungen der Handy-App (Vertrag `api/API.md` §33).
//!
//! Der Kern (`trs_core::trs_api::push`) meldet das Gerät beim Server an und ab; hier kommt zusammen, was nur die
//! App weiß: Verteiler und Adresse (Plugin `trs-push`), das Recht für Benachrichtigungen (Notification-Plugin),
//! App-Version und Gerätename. Abgeglichen wird beim Start, wenn die App wieder nach vorn kommt und nach jeder
//! Änderung in Einstellungen → Benachrichtigungen. Im Hintergrund ist der Echtzeit-Kanal zu – sonst hielte der
//! Server die App für offen und schickte nichts (§33.6).
//!
//! Abholen statt UnifiedPush (iOS immer, Android ohne Verteiler auf Wunsch): iOS plant das System
//! (`BGAppRefreshTask`, Swift ruft [`trs_push_poll_json`]), Android die App (WorkManager, Kotlin ruft
//! `PushPollWorker.nativePoll`).

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::OnceLock;

use serde::Serialize;
use tauri::{AppHandle, Manager, State};
use trs_core::trs_api::push::{PushDevice, PushSettings};

use crate::LauncherState;
use crate::error::CommandResult;

/// Kern und Datenordner für den Hintergrundabruf (ohne Tauri-Zustand).
static LAUNCHER: OnceLock<LauncherState> = OnceLock::new();
static ROOT: OnceLock<PathBuf> = OnceLock::new();

/// Ein Verteiler für die Auswahl in den Einstellungen.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DistributorView {
    pub id: String,
    pub name: String,
}

/// Fehler beim letzten Abgleich: Übersetzungs-Code + Rückfalltext (wie `CommandError`).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PushErrorView {
    pub code: String,
    pub message: String,
}

/// Was die Seite „Benachrichtigungen“ am Handy zeigt.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PushStatus {
    /// Android/iOS mit Plugin.
    pub supported: bool,
    pub settings: PushSettings,
    /// `unsupported` | `off` | `signedOut` | `permission` | `noDistributor` | `chooseDistributor` | `waiting` |
    /// `registered` | `polling` | `error`
    pub state: &'static str,
    /// Installierte UnifiedPush-Verteiler (Android).
    pub distributors: Vec<DistributorView>,
    /// Gewählter Verteiler (Paketname).
    pub distributor: Option<String>,
    /// `granted` | `denied` | `prompt`
    pub permission: &'static str,
    pub device_id: Option<String>,
    pub error: Option<PushErrorView>,
    /// Kategorien in der Reihenfolge der Einstellungen + Standard des Servers.
    pub categories: Vec<String>,
    pub defaults: BTreeMap<String, bool>,
}

impl PushStatus {
    fn unsupported(settings: PushSettings) -> Self {
        Self {
            supported: false,
            settings,
            state: "unsupported",
            distributors: Vec::new(),
            distributor: None,
            permission: "denied",
            device_id: None,
            error: None,
            categories: trs_core::trs_api::push::CATEGORIES.iter().map(|c| (*c).to_owned()).collect(),
            defaults: PushSettings::default().effective(None),
        }
    }
}

#[cfg_attr(not(mobile), allow(dead_code))]
fn error_view(e: trs_core::Error) -> PushErrorView {
    let user = crate::error::CommandError::from(e);
    let json = serde_json::to_value(&user).unwrap_or_default();
    PushErrorView {
        code: json["code"].as_str().unwrap_or("internal").to_owned(),
        message: json["message"].as_str().unwrap_or_default().to_owned(),
    }
}

/// Merkt sich Kern und Datenordner (im `setup`).
pub fn remember(launcher: &LauncherState) {
    let _ = LAUNCHER.set(launcher.clone());
    let _ = ROOT.set(launcher.paths().root().to_path_buf());
}

/// Was bei einem Abgleich passieren darf.
#[derive(Debug, Clone, Default)]
#[cfg_attr(not(mobile), allow(dead_code))]
struct Options {
    /// Nach dem Recht für Benachrichtigungen fragen (nur nach einer Handlung des Nutzers).
    ask_permission: bool,
    /// Android: diesen Verteiler nehmen.
    distributor: Option<String>,
}

#[cfg(mobile)]
mod native {
    use super::*;
    use tauri_plugin_notification::NotificationExt;
    use tauri_plugin_trs_push::{PushState, TrsPushExt};
    use trs_core::trs_api::push::{Platform, PushEnv, Transport};

    /// Plugin-Aufrufe blockieren bis zur Antwort – deshalb abseits der Async-Runtime.
    async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> Option<T> {
        tauri::async_runtime::spawn_blocking(f).await.ok()
    }

    async fn permission(app: &AppHandle, ask: bool) -> &'static str {
        use tauri::plugin::PermissionState;
        let handle = app.clone();
        let state = blocking(move || {
            let n = handle.notification();
            match n.permission_state() {
                Ok(PermissionState::Granted) => Ok(PermissionState::Granted),
                Ok(_) if ask => n.request_permission(),
                other => other,
            }
        })
        .await;
        match state {
            Some(Ok(PermissionState::Granted)) => "granted",
            Some(Ok(PermissionState::Denied)) => "denied",
            Some(Ok(_)) => "prompt",
            _ => "denied",
        }
    }

    async fn plugin_state(app: &AppHandle) -> Option<PushState> {
        let handle = app.clone();
        match blocking(move || handle.trs_push().state()).await? {
            Ok(state) => Some(state),
            Err(e) => {
                log::warn!("Push-Plugin: Zustand nicht lesbar: {e}");
                None
            }
        }
    }

    async fn register(app: &AppHandle, vapid: Option<String>, distributor: Option<String>) -> Result<PushState, String> {
        let handle = app.clone();
        blocking(move || handle.trs_push().register(vapid.as_deref(), distributor.as_deref()).map_err(|e| e.0))
            .await
            .unwrap_or_else(|| Err("internal".into()))
    }

    async fn unregister(app: &AppHandle) {
        let handle = app.clone();
        if let Some(Err(e)) = blocking(move || handle.trs_push().unregister()).await {
            log::warn!("Push-Plugin: Abmelden beim Verteiler fehlgeschlagen: {e}");
        }
    }

    /// Abholen im Hintergrund planen bzw. abbestellen.
    async fn set_poll(app: &AppHandle, enabled: bool) {
        let root = ROOT.get().map(|r| r.display().to_string()).unwrap_or_default();
        #[cfg(target_os = "android")]
        {
            if let Err(e) = crate::mobile::schedule_push_poll(app, enabled, &root).await {
                log::warn!("Abholen im Hintergrund nicht planbar: {e}");
            }
        }
        #[cfg(target_os = "ios")]
        {
            let handle = app.clone();
            if let Some(Err(e)) = blocking(move || handle.trs_push().set_poll(enabled, &root)).await {
                log::warn!("Hintergrundabruf nicht planbar: {e}");
            }
        }
    }

    pub(super) async fn sync(app: &AppHandle, launcher: &LauncherState, opts: Options) -> PushStatus {
        let settings = launcher.push_settings().await;
        let mut status = PushStatus { supported: true, ..PushStatus::unsupported(settings.clone()) };
        if let Ok(config) = launcher.push_config().await {
            status.categories = config.categories.clone();
            status.defaults = config.defaults.clone();
        }
        status.permission = permission(app, opts.ask_permission && settings.enabled).await;
        let platform = if cfg!(target_os = "ios") { Platform::Ios } else { Platform::Android };
        let state = plugin_state(app).await.unwrap_or_default();
        let device_name = state.device_name.clone();
        status.distributors = state.distributors.iter().map(|d| DistributorView { id: d.id.clone(), name: d.name.clone() }).collect();
        status.distributor = state.distributor.clone();

        let mut transport = None;
        let mut why: &'static str = "off";
        if settings.enabled && status.permission != "granted" {
            why = "permission";
        } else if settings.enabled && platform == Platform::Ios {
            transport = Some(Transport::Poll);
        } else if settings.enabled {
            let mut state = state;
            let wants_up = opts.distributor.is_some() || !settings.poll_fallback || state.distributor.is_some();
            if wants_up && !state.distributors.is_empty() && (state.endpoint.is_none() || opts.distributor.is_some()) {
                // Beim Verteiler anmelden (der VAPID-Schlüssel des Servers gehört dazu).
                let vapid = launcher.push_config().await.ok().and_then(|c| c.vapid_public_key);
                match register(app, vapid, opts.distributor.clone()).await {
                    Ok(next) => state = next,
                    Err(code) if code == "choose_distributor" => why = "chooseDistributor",
                    Err(code) if code == "no_distributor" => why = "noDistributor",
                    Err(code) => {
                        log::warn!("Anmeldung beim Verteiler fehlgeschlagen: {code}");
                        why = "error";
                    }
                }
                status.distributor = state.distributor.clone();
            }
            match (&state.endpoint, &state.p256dh, &state.auth) {
                (Some(endpoint), Some(p256dh), Some(auth)) if state.distributor.is_some() => {
                    transport = Some(Transport::UnifiedPush { endpoint: endpoint.clone(), p256dh: p256dh.clone(), auth: auth.clone() });
                }
                _ if settings.poll_fallback => transport = Some(Transport::Poll),
                _ if state.distributors.is_empty() => why = "noDistributor",
                _ if state.distributor.is_none() && why == "off" => why = "chooseDistributor",
                _ if why == "off" => why = if state.failure.is_some() { "error" } else { "waiting" },
                _ => {}
            }
        }

        let env = PushEnv {
            platform,
            transport: transport.clone(),
            device_name,
            app_version: app.package_info().version.to_string(),
        };
        match launcher.push_sync(&env).await {
            Ok(r) => {
                status.device_id = r.device_id;
                status.state = match (&transport, &status.device_id) {
                    (Some(Transport::Poll), Some(_)) => "polling",
                    (Some(_), Some(_)) => "registered",
                    _ => why,
                };
            }
            Err(e @ trs_core::Error::TrsApi { kind: "trs_disabled" | "trs_no_account", .. }) => {
                log::debug!("Push: {e}");
                status.state = "signedOut";
            }
            Err(e) => {
                status.state = "error";
                status.error = Some(error_view(e));
            }
        }
        // Ausgeschaltet: auch beim Verteiler abmelden (der Server kennt das Gerät schon nicht mehr).
        if !settings.enabled && status.distributor.is_some() && cfg!(target_os = "android") {
            unregister(app).await;
            status.distributor = None;
        }
        set_poll(app, status.state == "polling").await;
        status
    }

    /// Android: eigenes Gerät entfernt → auch beim Verteiler abmelden.
    pub(super) async fn after_remove(app: &AppHandle) {
        if cfg!(target_os = "android") {
            unregister(app).await;
        }
    }
}

/// Status (und dabei abgleichen – günstig, wenn sich nichts geändert hat).
#[tauri::command]
pub async fn push_status(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<PushStatus> {
    Ok(sync(&app, &launcher, Options::default()).await)
}

/// Schalter speichern und abgleichen. Einschalten fragt nach dem Recht für Benachrichtigungen.
#[tauri::command]
pub async fn push_set_settings(app: AppHandle, launcher: State<'_, LauncherState>, settings: PushSettings) -> CommandResult<PushStatus> {
    let saved = launcher.push_set_settings(settings).await;
    Ok(sync(&app, &launcher, Options { ask_permission: saved.enabled, distributor: None }).await)
}

/// Android: Verteiler wählen (Paketname) oder `None` = ohne Verteiler abholen.
#[tauri::command]
pub async fn push_choose_distributor(app: AppHandle, launcher: State<'_, LauncherState>, distributor: Option<String>) -> CommandResult<PushStatus> {
    let distributor = distributor.filter(|d| {
        (1..=255).contains(&d.len()) && d.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_'))
    });
    let mut settings = launcher.push_settings().await;
    settings.enabled = true;
    settings.poll_fallback = distributor.is_none();
    launcher.push_set_settings(settings).await;
    Ok(sync(&app, &launcher, Options { ask_permission: true, distributor }).await)
}

/// Alle Geräte des Kontos (Einstellungen → Benachrichtigungen → Geräte).
#[tauri::command]
pub async fn push_devices(launcher: State<'_, LauncherState>) -> CommandResult<Vec<PushDevice>> {
    Ok(launcher.push_devices().await?)
}

/// Gerät entfernen. Ist es dieses Handy, sind die Benachrichtigungen hier danach aus.
#[tauri::command]
pub async fn push_remove_device(app: AppHandle, launcher: State<'_, LauncherState>, id: String) -> CommandResult<PushStatus> {
    let own = launcher.push_devices().await.ok().is_some_and(|list| list.iter().any(|d| d.id == id && d.this_device));
    launcher.push_remove_device(&id).await?;
    #[cfg(mobile)]
    if own {
        native::after_remove(&app).await;
    }
    #[cfg(not(mobile))]
    let _ = own;
    Ok(sync(&app, &launcher, Options::default()).await)
}

async fn sync(app: &AppHandle, launcher: &LauncherState, opts: Options) -> PushStatus {
    #[cfg(mobile)]
    return native::sync(app, launcher, opts).await;
    #[cfg(not(mobile))]
    {
        let _ = (app, opts);
        PushStatus::unsupported(launcher.push_settings().await)
    }
}

/// Lebenslauf der App (Android/iOS): im Hintergrund Echtzeit-Kanal zu, vorn wieder auf + abgleichen.
#[cfg_attr(not(mobile), allow(dead_code))]
pub fn on_foreground(app: &AppHandle, foreground: bool) {
    let Some(launcher) = app.try_state::<LauncherState>().map(|s| s.inner().clone()) else { return };
    log::debug!("App {}", if foreground { "wieder vorn" } else { "im Hintergrund – Echtzeit-Kanal zu" });
    launcher.trs_live_pause(!foreground);
    if !foreground {
        return;
    }
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        // Abholen: was im Hintergrund liegen blieb, sieht man jetzt in der App – nicht noch einmal melden.
        if launcher.push_polling().await
            && let Err(e) = launcher.push_poll().await
        {
            log::debug!("Push: Abholen beim Öffnen fehlgeschlagen: {e}");
        }
        let _ = sync(&app, &launcher, Options::default()).await;
    });
}

/// Beim Start (nach kurzer Pause): Gerät abgleichen (neue App-Version, Sprache, Sitzung).
pub fn start(app: &AppHandle) {
    let Some(launcher) = app.try_state::<LauncherState>().map(|s| s.inner().clone()) else { return };
    remember(&launcher);
    if !cfg!(mobile) {
        return;
    }
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let _ = tauri::async_runtime::spawn_blocking(|| std::thread::sleep(std::time::Duration::from_secs(4))).await;
        let status = sync(&app, &launcher, Options::default()).await;
        log::info!("Benachrichtigungen: {}", status.state);
    });
}

/// Hintergrundabruf: Hinweise als JSON-Liste (`[]` bei Fehlern). Nutzt den laufenden Kern, sonst einen eigenen.
#[cfg_attr(not(mobile), allow(dead_code))]
fn poll_json(root: Option<PathBuf>) -> String {
    let result = tauri::async_runtime::block_on(async move {
        match (LAUNCHER.get(), root.or_else(|| ROOT.get().cloned())) {
            (Some(launcher), _) => launcher.push_poll().await,
            (None, Some(root)) => trs_core::trs_api::push::poll_detached(&root).await,
            (None, None) => Ok(Vec::new()),
        }
    });
    match result {
        Ok(list) => serde_json::to_string(&list).unwrap_or_else(|_| "[]".into()),
        Err(e) => {
            log::debug!("Push: Hintergrundabruf fehlgeschlagen: {e}");
            "[]".into()
        }
    }
}

/// iOS (`BGAppRefreshTask`): neue Hinweise als JSON-Liste (UTF-8, mit [`trs_push_free`] freigeben).
#[cfg(target_os = "ios")]
#[unsafe(no_mangle)]
pub extern "C" fn trs_push_poll_json() -> *mut std::os::raw::c_char {
    let json = poll_json(None);
    std::ffi::CString::new(json).map(std::ffi::CString::into_raw).unwrap_or(std::ptr::null_mut())
}

/// Gibt einen von [`trs_push_poll_json`] gelieferten Text frei.
#[cfg(target_os = "ios")]
#[unsafe(no_mangle)]
pub extern "C" fn trs_push_free(ptr: *mut std::os::raw::c_char) {
    if !ptr.is_null() {
        // SAFETY: stammt aus `CString::into_raw` in `trs_push_poll_json` und wird genau einmal freigegeben.
        drop(unsafe { std::ffi::CString::from_raw(ptr) });
    }
}

/// Android (WorkManager, `PushPollWorker`): Datenordner rein, Hinweise als JSON-Liste raus.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_theredstonee_trslauncher_PushPollWorker_nativePoll<'local>(
    mut env: jni::JNIEnv<'local>,
    _class: jni::objects::JClass<'local>,
    root: jni::objects::JString<'local>,
) -> jni::sys::jstring {
    let root: Option<PathBuf> = env.get_string(&root).ok().map(|s| PathBuf::from(String::from(s))).filter(|p| p.is_absolute());
    let json = std::panic::catch_unwind(move || poll_json(root)).unwrap_or_else(|_| "[]".into());
    env.new_string(json).map(jni::objects::JString::into_raw).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unsupported_status_lists_all_categories() {
        let s = PushStatus::unsupported(PushSettings::default());
        assert!(!s.supported);
        assert_eq!(s.categories.len(), 8);
        assert_eq!(s.defaults.get("friend_online"), Some(&false));
        let json = serde_json::to_value(&s).unwrap();
        assert_eq!(json["state"], "unsupported");
        assert_eq!(json["settings"]["pollFallback"], false);
    }

    #[test]
    fn poll_without_core_is_empty() {
        assert_eq!(poll_json(None), "[]");
    }
}
