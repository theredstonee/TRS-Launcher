import type { AppInfo, PlatformCapabilities } from '~/types'

/** Betriebssystem (nicht zu verwechseln mit der Inhaltsquelle `Platform` = Modrinth/CurseForge). */
export type OsName = PlatformCapabilities['platform']

/**
 * Betriebssystem aus dem User-Agent des Webviews – sofort verfügbar, ohne
 * Rust-Aufruf: WebView2 meldet „Windows NT“, WebKitGTK „X11; Linux“.
 * Für Texte und das Ein-/Ausblenden von Einstellungen reicht das; was das
 * System wirklich kann, steht in `appInfo().capabilities`.
 * Android meldet „Linux; Android“, iPadOS gibt sich als Mac aus (dann mit Touch).
 */
export function detectOs(
  userAgent = globalThis.navigator?.userAgent ?? '',
  touchPoints = globalThis.navigator?.maxTouchPoints ?? 0,
): OsName {
  if (/Windows|Win64|WOW64/i.test(userAgent)) return 'windows'
  if (/Android/i.test(userAgent)) return 'android'
  if (/iPhone|iPad|iPod/i.test(userAgent)) return 'ios'
  if (/Macintosh|Mac OS X/i.test(userAgent)) return touchPoints > 1 ? 'ios' : 'macos'
  if (/Linux|X11|FreeBSD/i.test(userAgent)) return 'linux'
  return 'windows'
}

export const hostOs: OsName = detectOs()
export const isLinux = hostOs === 'linux'
/** Android oder iOS (Begleit-App ohne Java-Spielstart). */
export const isMobile = hostOs === 'android' || hostOs === 'ios'
/**
 * Standard-Anmeldung: Desktop über den Browser (Rückleitung an localhost), mobil per Gerätecode –
 * die Rückleitung an einen lokalen Port ist dort unzuverlässig (App im Hintergrund pausiert).
 */
export const defaultLoginMode: 'browser' | 'code' = isMobile ? 'code' : 'browser'

/** Rückfall, solange `appInfo` noch nicht geladen ist (oder im Browser ohne Tauri). */
export function defaultCapabilities(os: OsName = hostOs): PlatformCapabilities {
  const mobile = os === 'android' || os === 'ios'
  return {
    platform: os,
    firewall: os === 'windows',
    trash: !mobile,
    clips: os === 'windows',
    updates: mobile ? 'mobile' : os === 'windows' ? 'auto' : 'package',
    gameLaunch: !mobile,
    java: !mobile,
    windowControls: !mobile,
    pushSupported: false,
    gameEngine: false,
  }
}

let cached: Promise<AppInfo> | null = null

/** `app_info` einmal pro Sitzung (Version, Datenordner, Fähigkeiten). */
export function loadAppInfo(): Promise<AppInfo> {
  cached ??= backend.appInfo().catch((e) => {
    cached = null
    throw e
  })
  return cached
}
