import { computed, ref, type ComputedRef, type Ref } from 'vue'
import type { Motion } from '~/types'

// Animationen im ganzen Launcher (Redstone-Hintergrund, Lauflichter,
// Übergänge). Die Einstellung `ui.motion` entscheidet – nicht mehr allein
// „Bewegung reduzieren“ des Systems: WebView2 meldet das schon, wenn in Windows
// nur „Animationen anzeigen“ aus ist (Leistungsoptionen, Remotedesktop,
// Tuning-Tools, manche Energiesparmodi), WebKitGTK bei `gtk-enable-animations=false`.
// Der Hintergrund stand dann still, obwohl niemand das so wollte.
//
// Auf <html> stehen `data-motion` (die Einstellung) und `data-reduced-motion`
// (gilt gerade „reduziert“?). CSS fragt nur Letzteres ab (siehe main.css,
// Varianten `motion-reduce:`/`motion-safe:`), Skripte `useMotion().reduced`.

export const motionModes = ['full', 'system', 'reduced'] as const satisfies readonly Motion[]

export const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)'

/** Ab Werk laufen Animationen immer (auch vor dem Laden der Einstellungen). */
export const DEFAULT_MOTION: Motion = 'full'

/** Unbekannte oder fehlende Werte (ältere Kerne) fallen auf den Standard zurück. */
export function normalizeMotion(value: unknown): Motion {
  return motionModes.includes(value as Motion) ? (value as Motion) : DEFAULT_MOTION
}

/** Gilt gerade „reduziert“? `system` folgt der Systemeinstellung. */
export function isReducedMotion(setting: Motion, systemReduced: boolean): boolean {
  return setting === 'reduced' || (setting === 'system' && systemReduced)
}

export interface MotionController {
  readonly setting: Readonly<Ref<Motion>>
  /** Meldet das System „Bewegung reduzieren“? */
  readonly systemReduced: Readonly<Ref<boolean>>
  /** Das, wonach sich Animationen richten. */
  readonly reduced: ComputedRef<boolean>
  apply(value: unknown): void
  dispose(): void
}

type MediaLike = Pick<MediaQueryList, 'matches'> & {
  addEventListener?: MediaQueryList['addEventListener']
  removeEventListener?: MediaQueryList['removeEventListener']
  addListener?: (cb: (e: MediaQueryListEvent) => void) => void
  removeListener?: (cb: (e: MediaQueryListEvent) => void) => void
}

/** Austauschbar für Tests: `media` = Abfrage „Bewegung reduzieren“, `root` = <html>. */
export function createMotion(options: { media?: MediaLike | null; root?: HTMLElement | null } = {}): MotionController {
  const { media = null, root = null } = options
  const setting = ref<Motion>(DEFAULT_MOTION)
  const systemReduced = ref(!!media?.matches)
  const reduced = computed(() => isReducedMotion(setting.value, systemReduced.value))

  function sync() {
    if (!root) return
    root.dataset.motion = setting.value
    if (reduced.value) root.dataset.reducedMotion = ''
    else delete root.dataset.reducedMotion
  }

  const onChange = (e: { matches: boolean }) => {
    systemReduced.value = e.matches
    sync()
  }
  // Ältere WebKit-Versionen kennen nur addListener.
  if (media?.addEventListener) media.addEventListener('change', onChange)
  else media?.addListener?.(onChange)
  sync()

  return {
    setting,
    systemReduced,
    reduced,
    apply(value) {
      setting.value = normalizeMotion(value)
      sync()
    },
    dispose() {
      if (media?.removeEventListener) media.removeEventListener('change', onChange)
      else media?.removeListener?.(onChange)
    },
  }
}

let shared: MotionController | null = null

/** Die eine Instanz für das Fenster (im Browser mit Systemabfrage und <html>). */
export function sharedMotion(): MotionController {
  if (!shared) {
    const browser = typeof document !== 'undefined'
    shared = createMotion({
      media: browser && typeof matchMedia === 'function' ? matchMedia(REDUCED_MOTION_QUERY) : null,
      root: browser ? document.documentElement : null,
    })
  }
  return shared
}

/** Einstellung übernehmen (aus `applyAppearance`). */
export function applyMotion(value: unknown) {
  sharedMotion().apply(value)
}
