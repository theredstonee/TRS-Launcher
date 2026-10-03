import { ref, shallowRef } from 'vue'
import type { AppInfo, PlatformCapabilities } from '~/types'

/** Betriebssystem (nicht zu verwechseln mit der Inhaltsquelle `Platform` = Modrinth/CurseForge). */
export type OsName = PlatformCapabilities['platform']

/**
 * Betriebssystem aus dem User-Agent des Webviews – sofort verfügbar, ohne
 * Rust-Aufruf: WebView2 meldet „Windows NT“, WebKitGTK „X11; Linux“.
 * Android meldet zusätzlich „Linux“ – deshalb zuerst prüfen. iPadOS gibt sich
 * als Mac aus und verrät sich nur über Touch-Punkte.
 * Für Texte und das Ein-/Ausblenden von Einstellungen reicht das; was das
 * System wirklich kann, steht in `appInfo().capabilities`.
 */
export function detectOs(
  userAgent = globalThis.navigator?.userAgent ?? '',
  touchPoints = globalThis.navigator?.maxTouchPoints ?? 0,
): OsName {
  if (/Android/i.test(userAgent)) return 'android'
  if (/iPhone|iPad|iPod/i.test(userAgent)) return 'ios'
  if (/Windows|Win64|WOW64/i.test(userAgent)) return 'windows'
  if (/Macintosh|Mac OS X/i.test(userAgent)) return touchPoints > 1 ? 'ios' : 'macos'
  if (/Linux|X11|FreeBSD/i.test(userAgent)) return 'linux'
  return 'windows'
}

export function isMobileOs(os: OsName): boolean {
  return os === 'android' || os === 'ios'
}

export const hostOs: OsName = detectOs()
export const isLinux = hostOs === 'linux'

/** Unter dieser Breite gibt es den Desktop-Launcher nicht (Mindestgröße des Fensters). */
export const MOBILE_MAX_WIDTH = 940

/**
 * Handy-Oberfläche: auf Android/iOS immer, sonst nur bei schmalem Touch-Bildschirm
 * (zum Testen im Browser). Der Desktop-Launcher ist nie schmaler als 940 px.
 */
export function wantsMobileUi(env: { os: OsName; coarse: boolean; width: number }): boolean {
  return isMobileOs(env.os) || (env.coarse && env.width < MOBILE_MAX_WIDTH)
}

/** Rückfall, solange `appInfo` noch nicht geladen ist (oder im Browser ohne Tauri). */
export function defaultCapabilities(os: OsName = hostOs): PlatformCapabilities {
  if (isMobileOs(os)) {
    return {
      platform: os,
      firewall: false,
      trash: false,
      clips: false,
      updates: 'mobile',
      gameLaunch: false,
      java: false,
      windowControls: false,
      pushSupported: false,
    }
  }
  return {
    platform: os,
    firewall: os === 'windows',
    trash: true,
    clips: os === 'windows',
    updates: os === 'windows' ? 'auto' : 'package',
    gameLaunch: true,
    java: true,
    windowControls: true,
    pushSupported: false,
  }
}

/** Ältere Kerne kennen die neuen Schalter noch nicht: fehlende aus den Standardwerten des Systems ergänzen. */
export function normalizeCapabilities(raw: Partial<PlatformCapabilities> | null | undefined): PlatformCapabilities {
  const base = defaultCapabilities(raw?.platform ?? hostOs)
  const out = { ...base }
  for (const key of Object.keys(base) as (keyof PlatformCapabilities)[]) {
    const value = raw?.[key]
    if (value !== undefined && value !== null && typeof value === typeof base[key]) (out as Record<string, unknown>)[key] = value
  }
  return out
}

/** Fähigkeiten dieses Systems – sofort mit Standardwerten, nach `app_info` mit denen des Kerns. */
export const platformCaps = shallowRef<PlatformCapabilities>(defaultCapabilities())

/** Handy-Oberfläche an/aus (setzt `plugins/os.client.ts`, auch bei Größenänderung). */
export const mobileUi = ref(isMobileOs(hostOs))

let cached: Promise<AppInfo> | null = null

/** `app_info` einmal pro Sitzung (Version, Datenordner, Fähigkeiten). */
export function loadAppInfo(): Promise<AppInfo> {
  cached ??= backend
    .appInfo()
    .then((info) => {
      const capabilities = normalizeCapabilities(info?.capabilities)
      platformCaps.value = capabilities
      return { ...info, capabilities }
    })
    .catch((e) => {
      cached = null
      throw e
    })
  return cached
}
