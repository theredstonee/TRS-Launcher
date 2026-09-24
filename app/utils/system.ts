import type { AppInfo, PlatformCapabilities } from '~/types'

/** Betriebssystem (nicht zu verwechseln mit der Inhaltsquelle `Platform` = Modrinth/CurseForge). */
export type OsName = PlatformCapabilities['platform']

/**
 * Betriebssystem aus dem User-Agent des Webviews – sofort verfügbar, ohne
 * Rust-Aufruf: WebView2 meldet „Windows NT“, WebKitGTK „X11; Linux“.
 * Für Texte und das Ein-/Ausblenden von Einstellungen reicht das; was das
 * System wirklich kann, steht in `appInfo().capabilities`.
 */
export function detectOs(userAgent = globalThis.navigator?.userAgent ?? ''): OsName {
  if (/Windows|Win64|WOW64/i.test(userAgent)) return 'windows'
  if (/Macintosh|Mac OS X/i.test(userAgent)) return 'macos'
  if (/Linux|X11|FreeBSD/i.test(userAgent)) return 'linux'
  return 'windows'
}

export const hostOs: OsName = detectOs()
export const isLinux = hostOs === 'linux'

/** Rückfall, solange `appInfo` noch nicht geladen ist (oder im Browser ohne Tauri). */
export function defaultCapabilities(os: OsName = hostOs): PlatformCapabilities {
  return { platform: os, firewall: os === 'windows', trash: true, clips: os === 'windows', updates: os === 'windows' ? 'auto' : 'package' }
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
