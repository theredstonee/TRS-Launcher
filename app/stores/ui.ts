import { defineStore } from 'pinia'

const NAV_KEY = 'trs.nav.expanded'
const RECENT_KEY = 'trs.palette.recent'
const MAX_RECENT = 6

function read(key: string, fallback: string): string {
  try {
    return localStorage.getItem(key) ?? fallback
  } catch {
    return fallback
  }
}

function write(key: string, value: string) {
  try {
    localStorage.setItem(key, value)
  } catch {
    // Gesperrter Speicher: dann gilt die Einstellung nur für diese Sitzung.
  }
}

/**
 * Oberflächen-Zustand, der über Seiten hinweg gilt: Befehlspalette, die beiden
 * globalen Dialoge (neue Instanz / Import) und die Breite der Seitenleiste.
 */
export const useUiStore = defineStore('ui', () => {
  const palette = ref(false)
  const creating = ref(false)
  const importing = ref(false)
  const navExpanded = ref(false)
  /** Zuletzt benutzte Befehle (IDs), neueste zuerst. */
  const recentCommands = ref<string[]>([])

  function restore() {
    navExpanded.value = read(NAV_KEY, '0') === '1'
    try {
      const raw: unknown = JSON.parse(read(RECENT_KEY, '[]'))
      recentCommands.value = Array.isArray(raw)
        ? raw.filter((v): v is string => typeof v === 'string' && v.length <= 200).slice(0, MAX_RECENT)
        : []
    } catch {
      recentCommands.value = []
    }
  }

  function toggleNav() {
    navExpanded.value = !navExpanded.value
    write(NAV_KEY, navExpanded.value ? '1' : '0')
  }

  function rememberCommand(id: string) {
    recentCommands.value = [id, ...recentCommands.value.filter((v) => v !== id)].slice(0, MAX_RECENT)
    write(RECENT_KEY, JSON.stringify(recentCommands.value))
  }

  function openPalette() {
    palette.value = true
  }

  function togglePalette() {
    palette.value = !palette.value
  }

  return {
    palette,
    creating,
    importing,
    navExpanded,
    recentCommands,
    restore,
    toggleNav,
    rememberCommand,
    openPalette,
    togglePalette,
  }
})
