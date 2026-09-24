import { getVersion } from '@tauri-apps/api/app'
import { isTauri } from '@tauri-apps/api/core'
import changelogText from '~~/CHANGELOG.md?raw'

/** Zuletzt gesehene Launcher-Version („Was ist neu“ erscheint pro Version nur einmal). */
const SEEN_KEY = 'trs.whatsNew.seen'

// localStorage kann im Webview werfen (z. B. gesperrter Speicher) – dann eben kein Hinweis.
function readSeen(): string | null {
  try {
    return localStorage.getItem(SEEN_KEY)
  } catch {
    return null
  }
}

function writeSeen(version: string) {
  try {
    localStorage.setItem(SEEN_KEY, version)
  } catch {
    // egal
  }
}

/**
 * Nach einem Update einmal zeigen, was neu ist – aus der mitgelieferten CHANGELOG.md,
 * in der Sprache des Launchers (Deutsch → deutscher Teil, sonst Englisch).
 */
export const useWhatsNewStore = defineStore('whatsNew', () => {
  const open = ref(false)
  const version = ref('')
  const entries = ref<ChangelogEntry[]>([])

  /** `freshInstall`: erster Start überhaupt – dann nichts zeigen, nur merken. */
  async function check(freshInstall: boolean) {
    if (!isTauri()) return
    let current: string
    try {
      current = await getVersion()
    } catch {
      return
    }
    version.value = current
    const seen = readSeen()
    writeSeen(current)
    if (freshInstall || (seen && compareVersions(current, seen) <= 0)) return
    const all = parseChangelog(changelogText)
    // Ohne gemerkte Version (vor Einführung dieses Hinweises installiert): nur die aktuelle zeigen.
    const list = seen ? changesSince(all, seen, current) : [changelogFor(all, current)].filter((e) => e !== null)
    if (!list.length) return
    entries.value = list
    open.value = true
  }

  return { open, version, entries, check }
})
