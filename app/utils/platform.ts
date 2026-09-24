import type { AppInfo, PlatformCapabilities } from '~/types'

export type Platform = 'windows' | 'linux' | 'macos'

/**
 * Betriebssystem aus dem User-Agent des Webviews – sofort verfügbar, ohne
 * Rust-Aufruf: WebView2 meldet „Windows NT“, WebKitGTK „X11; Linux“.
 * Für Texte und das Ein-/Ausblenden von Einstellungen reicht das; was das
 * System wirklich kann, steht in `appInfo().capabilities`.
 */
export function detectPlatform(userAgent = globalThis.navigator?.userAgent ?? ''): Platform {
  if (/Windows|Win64|WOW64/i.test(userAgent)) return 'windows'
  if (/Macintosh|Mac OS X/i.test(userAgent)) return 'macos'
  if (/Linux|X11|FreeBSD/i.test(userAgent)) return 'linux'
  return 'windows'
}

export const platform: Platform = detectPlatform()
export const isLinux = platform === 'linux'

/** Rückfall, solange `appInfo` noch nicht geladen ist (oder im Browser ohne Tauri). */
export function defaultCapabilities(p: Platform = platform): PlatformCapabilities {
  return { platform: p, firewall: p === 'windows', trash: true, clips: p === 'windows', updates: p === 'windows' ? 'auto' : 'package' }
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
