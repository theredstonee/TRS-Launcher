<script setup lang="ts">
import type { HistoryEntry, HistoryKind, ImportSource, Instance } from '~/types'

// Verlauf einer Instanz: gestartet, abgestürzt, Mods installiert, Version gewechselt …
const props = defineProps<{ instance: Instance; refreshKey?: number }>()

const entries = ref<HistoryEntry[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const filter = ref<'all' | 'play' | 'content' | 'instance'>('all')

const groupsOf: Record<HistoryKind, 'play' | 'content' | 'instance'> = {
  launched: 'play',
  stopped: 'play',
  crashed: 'play',
  mod_installed: 'content',
  mod_updated: 'content',
  mod_removed: 'content',
  mod_enabled: 'content',
  mod_disabled: 'content',
  created: 'instance',
  imported: 'instance',
  version_switched: 'instance',
  repaired: 'instance',
  icon_changed: 'instance',
  files_added: 'content',
  content_bulk: 'content',
  hooks_changed: 'instance',
  group_changed: 'instance',
  renamed: 'instance',
}

const bulkLabels: Record<string, string> = { enable: 'aktiviert', disable: 'deaktiviert', delete: 'gelöscht' }

async function load() {
  error.value = null
  try {
    entries.value = await backend.instanceHistory(props.instance.id)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
watch(() => [props.instance.id, props.refreshKey], load, { immediate: true })

const diagnosisLabels: Record<string, string> = {
  corrupt_files: 'Beschädigte Dateien',
  out_of_memory: 'Zu wenig Arbeitsspeicher',
  wrong_java: 'Falsche Java-Version',
  missing_dependency: 'Fehlende Abhängigkeit',
  mod_conflict: 'Mod-Konflikt',
  graphics_driver: 'Grafiktreiber',
}

function title(e: HistoryEntry): string {
  const s = e.subject ?? ''
  switch (e.kind) {
    case 'created':
      if (e.detail === 'modpack') return `Aus Modpack „${s}“ erstellt`
      if (e.detail === 'duplicate') return `Als Kopie von „${s}“ erstellt`
      return 'Instanz erstellt'
    case 'imported':
      return `Importiert aus ${importSourceLabels[s as ImportSource] ?? 'anderem Launcher'}`
    case 'launched':
      return s ? `Gestartet und mit „${s}“ verbunden` : 'Gestartet'
    case 'stopped':
      return 'Spiel beendet'
    case 'crashed':
      return 'Abgestürzt'
    case 'mod_installed':
      return `${s} installiert`
    case 'mod_updated':
      return e.detail === 'downgrade' ? `${s} zurückgestuft` : `${s} aktualisiert`
    case 'mod_removed':
      return `${s} entfernt`
    case 'mod_enabled':
      return `${s} aktiviert`
    case 'mod_disabled':
      return `${s} deaktiviert`
    case 'version_switched':
      return 'Version gewechselt'
    case 'repaired':
      return 'Dateien geprüft und repariert'
    case 'icon_changed':
      return 'Bild geändert'
    case 'files_added': {
      const count = Number(e.to ?? 1)
      return count > 1 ? `${count} Dateien hinzugefügt` : `${s} hinzugefügt`
    }
    case 'content_bulk':
      return `${e.to ?? 'Mehrere'} Inhalte ${bulkLabels[e.detail ?? ''] ?? 'geändert'}`
    case 'hooks_changed':
      return e.detail === 'global' ? 'Start-Hooks zurück auf global' : 'Eigene Start-Hooks gesetzt'
    case 'group_changed':
      return e.to ? `In Gruppe „${e.to}“ verschoben` : 'Aus der Gruppe genommen'
    case 'renamed':
      return 'Umbenannt'
  }
}

function detail(e: HistoryEntry): string | null {
  if (e.kind === 'stopped' && e.seconds !== undefined) return `Gespielt: ${formatPlayTime(e.seconds)}`
  if (e.kind === 'crashed') {
    const cause = e.detail?.startsWith('exit:') ? `Exit-Code ${e.detail.slice(5)}` : e.detail ? (diagnosisLabels[e.detail] ?? e.detail) : null
    return [cause, e.seconds ? `nach ${formatPlayTime(e.seconds)}` : null].filter(Boolean).join(' · ') || null
  }
  if (e.kind === 'mod_installed' && e.detail === 'dependency') return e.to ? `${e.to} · als Abhängigkeit` : 'Als Abhängigkeit'
  if ((e.kind === 'created' || e.kind === 'imported') && e.to) return e.to
  if (e.kind === 'renamed' && e.from && e.to) return `„${e.from}“ → „${e.to}“`
  if (e.kind === 'group_changed' && e.from) return `vorher: ${e.from}`
  return null
}

const tone: Record<HistoryKind, string> = {
  launched: 'bg-lamp-900 text-lamp-300',
  stopped: 'bg-base-800 text-base-200',
  crashed: 'bg-redstone-900 text-redstone-300',
  mod_installed: 'bg-ok/10 text-ok',
  mod_updated: 'bg-lamp-900 text-lamp-300',
  mod_removed: 'bg-base-800 text-base-400',
  mod_enabled: 'bg-base-800 text-base-200',
  mod_disabled: 'bg-base-800 text-base-400',
  created: 'bg-redstone-900 text-redstone-300',
  imported: 'bg-redstone-900 text-redstone-300',
  version_switched: 'bg-base-800 text-base-50',
  repaired: 'bg-base-800 text-base-200',
  icon_changed: 'bg-base-800 text-base-200',
  files_added: 'bg-ok/10 text-ok',
  content_bulk: 'bg-base-800 text-base-200',
  hooks_changed: 'bg-base-800 text-base-200',
  group_changed: 'bg-base-800 text-base-200',
  renamed: 'bg-base-800 text-base-200',
}

const icons: Record<HistoryKind, string> = {
  launched: 'M8 5v14l11-7z',
  stopped: 'M7 7h10v10H7z',
  crashed: 'M12 4 3 20h18zM12 10v4m0 3v.01',
  mod_installed: 'M12 4v11m0 0-4-4m4 4 4-4M5 20h14',
  mod_updated: 'M12 19V5m0 0-5 5m5-5 5 5',
  mod_removed: 'M5 7h14M10 11v6M14 11v6M7 7l1 13h8l1-13M9 7V4h6v3',
  mod_enabled: 'm5 12 5 5 9-10',
  mod_disabled: 'M6 6l12 12M18 6 6 18',
  created: 'M12 5v14M5 12h14',
  imported: 'M4 12h11m0 0-4-4m4 4-4 4M20 4v16',
  version_switched: 'M4 8h13m0 0-4-4m4 4-4 4M20 16H7m0 0 4-4m-4 4 4 4',
  repaired: 'M14.5 5.5a4 4 0 0 0-5 5L4 16l4 4 5.5-5.5a4 4 0 0 0 5-5L16 12l-4-4z',
  icon_changed: 'M4 5h16v14H4zM4 15l5-5 5 5m-2-2 3-3 5 5',
  files_added: 'M14 3H6v18h12V7zM14 3v4h4M12 11v6m-3-3h6',
  content_bulk: 'M4 6h16M4 12h16M4 18h16',
  hooks_changed: 'M9 4v6a3 3 0 0 0 6 0V4M12 13v7M8 20h8',
  group_changed: 'M3 7h7l2 2h9v10H3z',
  renamed: 'M4 20h4L18 10l-4-4L4 16zM14 6l4 4',
}

const visible = computed(() => entries.value.filter((e) => filter.value === 'all' || groupsOf[e.kind] === filter.value))

const dayFormat = new Intl.DateTimeFormat('de', { weekday: 'long', day: 'numeric', month: 'long' })
const timeFormat = new Intl.DateTimeFormat('de', { hour: '2-digit', minute: '2-digit' })
function dayLabel(iso: string): string {
  const d = new Date(iso)
  const today = new Date()
  const yesterday = new Date(today.getTime() - 86_400_000)
  if (d.toDateString() === today.toDateString()) return 'Heute'
  if (d.toDateString() === yesterday.toDateString()) return 'Gestern'
  return dayFormat.format(d)
}
const grouped = computed(() => {
  const out: { day: string; items: HistoryEntry[] }[] = []
  for (const e of visible.value) {
    const day = dayLabel(e.at)
    const last = out[out.length - 1]
    if (last?.day === day) last.items.push(e)
    else out.push({ day, items: [e] })
  }
  return out
})
</script>

<template>
  <div class="min-h-0 max-w-3xl flex-1 overflow-y-auto pr-1">
    <div class="mb-4 flex items-center gap-2">
      <div class="flex rounded-full bg-base-900 p-0.5 ring-1 ring-base-800">
        <button v-for="[k, label] in ([['all', 'Alles'], ['play', 'Spielen'], ['content', 'Inhalte'], ['instance', 'Instanz']] as const)" :key="k" class="tab px-3 py-1 text-xs" :class="{ 'tab-on': filter === k }" @click="filter = k">
          {{ label }}
        </button>
      </div>
      <span v-if="entries.length" class="text-xs text-base-600">{{ entries.length }} Einträge</span>
    </div>

    <div v-if="loading" class="space-y-2">
      <div v-for="i in 6" :key="i" class="skeleton h-12" />
    </div>
    <p v-else-if="error" role="alert" class="card px-4 py-3 text-sm text-redstone-300">{{ error }}</p>
    <div v-else-if="!visible.length" class="card px-6 py-12 text-center">
      <h2 class="font-semibold">Noch nichts passiert</h2>
      <p class="mx-auto mt-1 max-w-md text-sm text-base-400">Starts, Abstürze, installierte Mods und Versionswechsel erscheinen hier.</p>
    </div>

    <section v-for="g in grouped" v-else :key="g.day" class="mb-5">
      <h3 class="sticky top-0 z-10 mb-2 bg-base-950/90 py-1 text-xs font-medium text-base-400 backdrop-blur">{{ g.day }}</h3>
      <ol class="relative space-y-1 before:absolute before:top-2 before:bottom-2 before:left-[15px] before:w-px before:bg-base-800">
        <li v-for="(e, i) in g.items" :key="`${e.at}-${i}`" class="relative flex items-start gap-3 rounded-lg py-1.5 pr-2">
          <span class="relative z-[1] grid size-8 shrink-0 place-items-center rounded-full ring-4 ring-base-950" :class="tone[e.kind]">
            <svg viewBox="0 0 24 24" class="size-3.5" :fill="e.kind === 'launched' || e.kind === 'stopped' ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path :d="icons[e.kind]" /></svg>
          </span>
          <div class="min-w-0 flex-1 pt-1">
            <p class="text-sm">
              <span class="font-medium text-base-50">{{ title(e) }}</span>
              <template v-if="(e.kind === 'mod_updated' || e.kind === 'version_switched') && (e.from || e.to)">
                <span class="ml-2 font-mono text-xs text-base-400">{{ e.from ?? '?' }}</span>
                <svg viewBox="0 0 24 24" class="mx-1 inline size-3 text-base-600" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M5 12h14m0 0-5-5m5 5-5 5" /></svg>
                <span class="font-mono text-xs text-base-200">{{ e.to ?? '?' }}</span>
              </template>
              <span v-else-if="e.kind === 'mod_installed' && e.to && e.detail !== 'dependency'" class="ml-2 font-mono text-xs text-base-400">{{ e.to }}</span>
            </p>
            <p v-if="detail(e)" class="text-xs text-base-400">{{ detail(e) }}</p>
          </div>
          <time class="shrink-0 pt-1.5 text-xs text-base-600 tabular-nums" :datetime="e.at" :title="formatDate(e.at)">{{ g.day === 'Heute' ? formatRelative(e.at) : timeFormat.format(new Date(e.at)) }}</time>
        </li>
      </ol>
    </section>
  </div>
</template>
