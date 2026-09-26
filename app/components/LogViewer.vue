<script setup lang="ts">
import type { LogLine, LogSource } from '~/types'
import { LogModel, LogTextParser, highlightMatches, type LevelFilter, type LogEntry, type LogRow } from '~/utils/logview'

// Log-Tab der Instanzseite: Live-Log des laufenden Spiels oder ältere
// Log-Dateien/Absturzberichte. Virtualisiert (feste Zeilenhöhe), neue Zeilen
// werden nur angehängt – auch 100 000+ Zeilen bleiben flüssig.
const props = defineProps<{ instanceId: string; running: boolean; lines: LogLine[]; logTotal: number }>()

const toasts = useToasts()
const ROW = 20
const OVERSCAN = 30

// --- Modell (bewusst nicht reaktiv; `version` stößt das Rendern an) -----------
const model = new LogModel()
const version = ref(0)
const bump = () => version.value++

const source = ref<string>('live')
const sources = ref<LogSource[]>([])
const loadingSource = ref(false)
const truncated = ref(false)
const level = ref<LevelFilter>('all')
const search = ref('')
const follow = ref(true)
const expandedView = ref(false)
const allOpen = ref(false)
const copied = ref(false)
const sharing = ref(false)
const newSinceScroll = ref(0)

const isLive = computed(() => source.value === 'live')
const currentSource = computed(() => sources.value.find((s) => s.id === source.value) ?? null)

// --- Live-Log ------------------------------------------------------------------
let seen = 0
function syncLive() {
  if (!isLive.value) return
  const total = props.logTotal
  // Neustart der Instanz: Zähler fängt bei 0 an.
  if (total < seen) {
    model.clear()
    seen = 0
    follow.value = true
  }
  const fresh = total - seen
  if (fresh <= 0) return
  const lines = props.lines
  model.appendLines(lines.slice(Math.max(0, lines.length - fresh)))
  seen = total
  if (!follow.value) newSinceScroll.value += fresh
  bump()
}
watch(() => props.logTotal, syncLive)

// --- Dateien -------------------------------------------------------------------
async function loadSources() {
  try {
    sources.value = await backend.listLogSources(props.instanceId)
  } catch (e) {
    toasts.error(e)
  }
}

let loadToken = 0
async function selectSource(id: string) {
  source.value = id
  const token = ++loadToken
  model.clear()
  truncated.value = false
  newSinceScroll.value = 0
  if (id === 'live') {
    seen = 0
    follow.value = true
    syncLive()
    bump()
    return
  }
  follow.value = false
  loadingSource.value = true
  bump()
  try {
    const { text, truncated: cut } = await backend.readLogSource(props.instanceId, id)
    if (token !== loadToken) return
    truncated.value = cut
    // In Stücken zerlegen und zwischendurch rendern – große Dateien frieren die Oberfläche nicht ein.
    const parser = new LogTextParser()
    const CHUNK = 512 * 1024
    for (let at = 0; at < text.length; at += CHUNK) {
      const out: LogEntry[] = []
      parser.feed(text.slice(at, at + CHUNK), out)
      if (at + CHUNK >= text.length) parser.flush(out)
      model.appendEntries(out)
      bump()
      await new Promise((resolve) => requestAnimationFrame(() => resolve(null)))
      if (token !== loadToken) return
    }
    if (!text) bump()
  } catch (e) {
    if (token === loadToken) toasts.error(e)
  } finally {
    if (token === loadToken) loadingSource.value = false
  }
}

function onSourceChange(e: Event) {
  selectSource((e.target as HTMLSelectElement).value)
}

// Spiel startet: auf den Live-Log umschalten.
watch(
  () => props.running,
  (running) => {
    if (running && !isLive.value) selectSource('live')
    if (!running) loadSources()
  },
)

// --- Filter & Suche ------------------------------------------------------------
let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(search, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(applyFilter, 120)
})
watch(level, applyFilter)
function applyFilter() {
  model.setFilter(level.value, search.value)
  bump()
}
const needle = computed(() => search.value.trim().slice(0, 200))

const counts = computed(() => {
  void version.value
  return { ...model.counts }
})
const matchCount = computed(() => {
  void version.value
  return model.visibleEntries
})
const levelChips = computed(() => [
  { key: 'all' as const, label: t('logViewer.levels.all'), count: counts.value.all, dot: 'bg-base-400' },
  { key: 'error' as const, label: t('logViewer.levels.error'), count: counts.value.error, dot: 'bg-redstone-400' },
  { key: 'warn' as const, label: t('logViewer.levels.warn'), count: counts.value.warn, dot: 'bg-warn' },
  { key: 'info' as const, label: t('logViewer.levels.info'), count: counts.value.info, dot: 'bg-base-200' },
])

// --- Virtualisierte Liste ------------------------------------------------------
const scroller = ref<HTMLElement | null>(null)
const scrollTop = ref(0)
const viewHeight = ref(400)
let resize: ResizeObserver | null = null

const rowCount = computed(() => {
  void version.value
  return model.rows.length
})
// Nach dem Filtern kann die alte Scrollposition hinter dem Ende liegen – begrenzen,
// sonst bliebe die Liste leer (der Abstand oben hielte die Höhe künstlich groß).
const top = computed(() => Math.min(scrollTop.value, Math.max(0, rowCount.value * ROW + 8 - viewHeight.value)))
const start = computed(() => Math.max(0, Math.floor(top.value / ROW) - OVERSCAN))
const end = computed(() => Math.min(rowCount.value, Math.ceil((top.value + viewHeight.value) / ROW) + OVERSCAN))

interface ViewRow {
  key: string
  row: LogRow
  entry: LogEntry
  head: boolean
  text: string
  open: boolean
}
const visibleRows = computed<ViewRow[]>(() => {
  void version.value
  const out: ViewRow[] = []
  for (let i = start.value; i < end.value; i++) {
    const row = model.rows[i]
    if (!row) break
    const entry = model.entries[row.entry]!
    const head = row.line < 0
    out.push({ key: `${row.entry}:${row.line}`, row, entry, head, text: head ? entry.message : entry.detail[row.line]!, open: head && model.isExpanded(row.entry) })
  }
  return out
})

function onScroll() {
  const el = scroller.value
  if (!el) return
  scrollTop.value = el.scrollTop
  const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < ROW * 2
  if (atBottom !== follow.value) follow.value = atBottom
  if (atBottom) newSinceScroll.value = 0
}

function toBottom() {
  follow.value = true
  newSinceScroll.value = 0
  nextTick(() => {
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// Neue Zeilen: mitlaufen, solange unten. Sonst die (vom Browser begrenzte) Position übernehmen.
watch(version, () => {
  nextTick(() => {
    const el = scroller.value
    if (!el) return
    if (follow.value) el.scrollTop = el.scrollHeight
    scrollTop.value = el.scrollTop
  })
})

function toggleRow(index: number) {
  model.toggle(index)
  bump()
}
function toggleAll() {
  allOpen.value = !allOpen.value
  model.setAllExpanded(allOpen.value)
  bump()
}

// --- Darstellung ---------------------------------------------------------------
const timeFormat = computed(() => new Intl.DateTimeFormat(intlLocale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' }))
function timeOf(entry: LogEntry): string {
  return entry.time !== null ? timeFormat.value.format(entry.time) : (entry.clock ?? '')
}
const levelLabel: Record<string, string> = { trace: 'TRACE', debug: 'DEBUG', info: 'INFO', warn: 'WARN', error: 'ERROR', fatal: 'FATAL' }

function sourceLabel(s: LogSource): string {
  const date = s.modified ? formatShortDate(s.modified) : ''
  return `${s.name}${date ? ` · ${date}` : ''} · ${formatBytes(s.size)}`
}
const gameSources = computed(() => sources.value.filter((s) => s.kind === 'game'))
const crashSources = computed(() => sources.value.filter((s) => s.kind === 'crash'))
const launcherSources = computed(() => sources.value.filter((s) => s.kind === 'launcher'))

// --- Aktionen ------------------------------------------------------------------
function clearView() {
  model.clear()
  newSinceScroll.value = 0
  follow.value = true
  bump()
}

async function copyAll() {
  try {
    await navigator.clipboard.writeText(model.toText(timeOf))
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    toasts.error(t('logViewer.copyFailed'))
  }
}

const searchInput = ref<HTMLInputElement | null>(null)
function onKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
    e.preventDefault()
    searchInput.value?.focus()
    searchInput.value?.select()
  } else if (e.key === 'Escape' && expandedView.value && !sharing.value) {
    expandedView.value = false
  }
}

onMounted(() => {
  loadSources()
  syncLive()
  // Kein laufendes Spiel und nichts im Live-Log → neuesten Log vorschlagen, aber Live bleibt gewählt.
  if (scroller.value) {
    viewHeight.value = scroller.value.clientHeight
    resize = new ResizeObserver(() => {
      if (scroller.value) viewHeight.value = scroller.value.clientHeight
    })
    resize.observe(scroller.value)
  }
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  resize?.disconnect()
  window.removeEventListener('keydown', onKey)
  if (searchTimer) clearTimeout(searchTimer)
  loadToken++
})
// Die Liste hängt erst nach dem Laden am DOM (v-if) – dann beobachten.
watch(scroller, (el, old) => {
  if (old) resize?.unobserve(old)
  if (el) {
    viewHeight.value = el.clientHeight
    resize?.observe(el)
  }
})

const latestFile = computed(() => gameSources.value.find((s) => s.name === 'latest.log') ?? gameSources.value[0] ?? null)
const showEmptyLive = computed(() => {
  void version.value
  return isLive.value && !props.running && model.entries.length === 0
})
const shareSource = computed(() => (isLive.value ? 'live' : source.value))
const shareLabel = computed(() => (isLive.value ? t('logViewer.latest') : (currentSource.value?.name ?? source.value)))
</script>

<template>
  <div v-if="expandedView" class="fixed inset-0 z-40 bg-black/60" aria-hidden="true" @mousedown="expandedView = false" />
  <section
    class="card flex min-h-0 flex-col overflow-hidden"
    :class="expandedView ? 'fixed inset-4 z-40 shadow-2xl shadow-black/60' : 'flex-1'"
    :aria-label="t('logViewer.title')"
  >
    <!-- Kopfzeile: Quelle, Suche, Aktionen -->
    <div class="flex flex-wrap items-center gap-2 border-b border-base-800 px-3 py-2">
      <div class="relative">
        <span class="pointer-events-none absolute top-1/2 left-2.5 size-2 -translate-y-1/2 rounded-full" :class="isLive ? (running ? 'animate-lamp bg-redstone-500 shadow-[0_0_8px_var(--color-redstone-500)]' : 'bg-base-600') : 'bg-lamp-400'" />
        <select
          class="field h-8 w-auto max-w-72 py-0 pr-8 pl-6 text-xs"
          :value="source"
          :aria-label="t('logViewer.source')"
          @change="onSourceChange"
          @focus="loadSources"
        >
          <option value="live">{{ running ? t('logViewer.liveRunning') : t('logViewer.live') }}</option>
          <optgroup v-if="gameSources.length" :label="t('logViewer.groups.game')">
            <option v-for="s in gameSources" :key="s.id" :value="s.id">{{ sourceLabel(s) }}</option>
          </optgroup>
          <optgroup v-if="crashSources.length" :label="t('logViewer.groups.crash')">
            <option v-for="s in crashSources" :key="s.id" :value="s.id">{{ sourceLabel(s) }}</option>
          </optgroup>
          <optgroup v-if="launcherSources.length" :label="t('logViewer.groups.launcher')">
            <option v-for="s in launcherSources" :key="s.id" :value="s.id">{{ sourceLabel(s) }}</option>
          </optgroup>
        </select>
      </div>

      <div class="relative min-w-40 flex-1 sm:max-w-80">
        <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-base-400" fill="none" stroke="currentColor" stroke-width="2"><path :d="icons.search" /></svg>
        <input
          ref="searchInput"
          v-model="search"
          class="field h-8 py-0 pr-16 pl-8 text-xs"
          maxlength="200"
          :placeholder="t('logViewer.search')"
          :aria-label="t('logViewer.search')"
          spellcheck="false"
          @keydown.esc.stop="search = ''"
        />
        <span v-if="needle" class="absolute top-1/2 right-7 -translate-y-1/2 text-[11px] tabular-nums text-base-400" aria-live="polite">{{ formatNumber(matchCount) }}</span>
        <button v-if="search" class="absolute top-1/2 right-1.5 -translate-y-1/2 rounded p-1 text-base-400 hover:text-base-50" :aria-label="t('logViewer.clearSearch')" @click="search = ''">
          <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="2.5"><path :d="icons.close" /></svg>
        </button>
      </div>

      <div class="ml-auto flex items-center gap-1">
        <button v-if="isLive" class="btn-icon size-8" :title="t('logViewer.clear')" :aria-label="t('logViewer.clear')" :disabled="!counts.all" @click="clearView">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
        </button>
        <button class="btn-icon size-8" :title="copied ? t('logConsole.copied') : t('logViewer.copy')" :aria-label="t('logViewer.copy')" :disabled="!matchCount" @click="copyAll">
          <svg v-if="!copied" viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M9 9h11v11H9zM5 15H4V4h11v1" /></svg>
          <svg v-else viewBox="0 0 24 24" class="size-4 text-ok" fill="none" stroke="currentColor" stroke-width="2.5"><path :d="icons.check" /></svg>
        </button>
        <button class="btn-icon size-8" :title="t('logViewer.share')" :aria-label="t('logViewer.share')" @click="sharing = true">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 15V3m0 0L8 7m4-4 4 4M5 12v7a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-7" /></svg>
        </button>
        <button class="btn-icon size-8" :title="expandedView ? t('logViewer.collapse') : t('logViewer.expand')" :aria-label="expandedView ? t('logViewer.collapse') : t('logViewer.expand')" :aria-pressed="expandedView" @click="expandedView = !expandedView">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path v-if="!expandedView" d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />
            <path v-else d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" />
          </svg>
        </button>
      </div>
    </div>

    <!-- Stufen-Filter -->
    <div class="flex flex-wrap items-center gap-1.5 border-b border-base-800 bg-base-900/60 px-3 py-1.5">
      <div class="flex flex-wrap gap-1" role="radiogroup" :aria-label="t('logViewer.levelFilter')">
        <button
          v-for="chip in levelChips"
          :key="chip.key"
          role="radio"
          :aria-checked="level === chip.key"
          class="level-chip"
          :class="{ 'level-chip-on': level === chip.key, [`level-chip-${chip.key}`]: true }"
          @click="level = chip.key"
        >
          <span class="size-1.5 rounded-full" :class="chip.dot" />
          {{ chip.label }}
          <span class="tabular-nums opacity-70">{{ formatNumber(chip.count) }}</span>
        </button>
      </div>
      <button class="ml-auto rounded px-2 py-0.5 text-[11px] text-base-400 hover:bg-base-800 hover:text-base-50" @click="toggleAll">
        {{ allOpen ? t('logViewer.collapseAll') : t('logViewer.expandAll') }}
      </button>
      <span v-if="truncated" class="text-[11px] text-lamp-300" :title="t('logViewer.truncatedHint')">{{ t('logViewer.truncated') }}</span>
    </div>

    <!-- Inhalt -->
    <div class="relative min-h-0 flex-1 bg-base-950">
      <div v-if="showEmptyLive" class="flex h-full min-h-64 flex-col items-center justify-center gap-3 px-6 py-10 text-center">
        <div class="empty-lamp" aria-hidden="true">
          <!-- Hebel (aus) → Redstone-Staub (ohne Signal) → Lampe (aus). 1 Einheit = 1 Texel. -->
          <svg viewBox="0 0 80 34" class="h-24 w-56" shape-rendering="crispEdges">
            <!-- Boden -->
            <rect x="0" y="30" width="80" height="4" class="fill-base-800" />
            <!-- Hebel: Sockel + Stiel nach links gekippt -->
            <rect x="4" y="26" width="10" height="4" class="fill-base-600" />
            <rect x="5" y="27" width="8" height="2" class="fill-base-700" />
            <rect x="6" y="22" width="2" height="4" class="fill-[#7a5a3a]" />
            <rect x="4" y="20" width="2" height="2" class="fill-[#7a5a3a]" />
            <rect x="3" y="18" width="2" height="2" class="fill-redstone-900" />
            <!-- Staubleitung, dunkel -->
            <rect x="14" y="28" width="38" height="2" class="fill-redstone-900" />
            <rect x="18" y="28" width="2" height="1" class="fill-redstone-600 opacity-60" />
            <rect x="26" y="29" width="3" height="1" class="fill-redstone-600 opacity-50" />
            <rect x="35" y="28" width="2" height="1" class="fill-redstone-600 opacity-60" />
            <rect x="44" y="29" width="3" height="1" class="fill-redstone-600 opacity-50" />
            <!-- Lampe (aus) -->
            <rect x="52" y="6" width="24" height="24" class="fill-base-700" />
            <rect x="53" y="7" width="22" height="22" class="fill-[#3b2a1c]" />
            <rect x="55" y="9" width="7" height="7" class="fill-lamp-900" />
            <rect x="66" y="9" width="7" height="7" class="fill-lamp-900" />
            <rect x="55" y="20" width="7" height="7" class="fill-lamp-900" />
            <rect x="66" y="20" width="7" height="7" class="fill-lamp-900" />
            <rect x="62" y="16" width="4" height="4" class="fill-base-600" />
            <rect x="57" y="11" width="2" height="2" class="fill-[#5b4630]" />
            <rect x="69" y="23" width="2" height="2" class="fill-[#5b4630]" />
          </svg>
        </div>
        <p class="heading text-base">{{ t('logViewer.empty.title') }}</p>
        <p class="max-w-sm text-sm text-base-400">{{ t('logViewer.empty.text') }}</p>
        <button v-if="latestFile" class="btn btn-ghost mt-1 px-3 py-1.5 text-xs" @click="selectSource(latestFile.id)">
          {{ t('logViewer.empty.openLatest', { name: latestFile.name }) }}
        </button>
      </div>

      <div v-else-if="loadingSource && !rowCount" class="space-y-1.5 p-3">
        <div v-for="i in 8" :key="i" class="skeleton h-4" :style="{ width: `${40 + ((i * 37) % 55)}%` }" />
      </div>

      <div
        v-else
        ref="scroller"
        class="log-scroll absolute inset-0 overflow-auto font-mono text-xs select-text"
        tabindex="0"
        role="log"
        :aria-label="t('logViewer.lines')"
        @scroll.passive="onScroll"
      >
        <p v-if="!rowCount && !loadingSource" class="px-3 py-10 text-center font-sans text-sm text-base-600">
          {{ needle || level !== 'all' ? t('logViewer.noMatches') : isLive ? t('logViewer.waiting') : t('logViewer.emptyFile') }}
        </p>
        <div v-else class="log-canvas" :style="{ height: `${rowCount * ROW + 8}px`, paddingTop: `${start * ROW + 4}px` }">
          <div
            v-for="r in visibleRows"
            :key="r.key"
            class="log-row"
            :class="[`lv-${r.entry.level}`, r.head ? 'log-head' : 'log-detail']"
          >
            <template v-if="r.head">
              <button
                v-if="r.entry.detail.length"
                class="log-toggle"
                :aria-expanded="r.open"
                :aria-label="r.open ? t('logViewer.hideTrace') : t('logViewer.showTrace', { n: r.entry.detail.length })"
                @click="toggleRow(r.row.entry)"
              >
                <svg viewBox="0 0 24 24" class="size-3 transition-transform" :class="{ 'rotate-90': r.open }" fill="none" stroke="currentColor" stroke-width="3"><path d="m9 6 6 6-6 6" /></svg>
              </button>
              <span v-else class="w-4 shrink-0" />
              <span class="log-time">{{ timeOf(r.entry) }}</span>
              <span class="log-level">{{ levelLabel[r.entry.level] }}</span>
              <span class="log-msg" :title="r.entry.thread ?? undefined">
                <template v-for="(seg, i) in highlightMatches(r.text, needle)" :key="i"><mark v-if="seg.hit" class="log-hit">{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template>
              </span>
              <button v-if="r.entry.detail.length && !r.open" class="log-more" @click="toggleRow(r.row.entry)">+{{ r.entry.detail.length }}</button>
            </template>
            <template v-else>
              <span class="log-indent" />
              <span class="log-msg">
                <template v-for="(seg, i) in highlightMatches(r.text, needle)" :key="i"><mark v-if="seg.hit" class="log-hit">{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template>
              </span>
            </template>
          </div>
        </div>
      </div>

      <Transition name="toast">
        <button
          v-if="!follow && rowCount && !showEmptyLive"
          class="btn btn-primary absolute right-5 bottom-4 z-10 px-3 py-1.5 text-xs shadow-lg shadow-black/40"
          @click="toBottom"
        >
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14m0 0-5-5m5 5 5-5" /></svg>
          {{ newSinceScroll ? t('logViewer.newLines', { n: formatNumber(newSinceScroll) }, newSinceScroll) : t('logViewer.toBottom') }}
        </button>
      </Transition>
    </div>

    <div class="flex items-center gap-3 border-t border-base-800 px-3 py-1 text-[11px] text-base-600">
      <span>{{ t('logConsole.lineCount', { n: formatNumber(counts.all) }, counts.all) }}</span>
      <span v-if="isLive && running" class="flex items-center gap-1 text-redstone-300"><span class="size-1.5 animate-lamp rounded-full bg-redstone-500" />{{ t('logViewer.following') }}</span>
      <span v-if="loadingSource" class="text-lamp-300">{{ t('logViewer.loading') }}</span>
      <span class="ml-auto hidden sm:inline">{{ t('logViewer.shortcut') }}</span>
    </div>

    <LogShareDialog v-if="sharing" :instance-id="instanceId" :source="shareSource" :label="shareLabel" @close="sharing = false" />
  </section>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.level-chip {
  @apply inline-flex items-center gap-1.5 rounded-md border border-transparent px-2 py-0.5 text-[11px] font-medium text-base-400 transition-colors hover:bg-base-800 hover:text-base-50;
}
.level-chip-on {
  @apply border-base-700 bg-base-800 text-base-50;
}
.level-chip-on.level-chip-error {
  @apply border-redstone-600/60 bg-redstone-900/60 text-redstone-300;
}
.level-chip-on.level-chip-warn {
  @apply border-lamp-400/40 bg-lamp-900/60 text-lamp-300;
}

.log-canvas {
  box-sizing: border-box;
  width: max-content;
  min-width: 100%;
}
.log-row {
  @apply flex items-center gap-2 pr-4 pl-1.5 whitespace-pre;
  height: 20px;
  line-height: 20px;
}
.log-row:hover {
  background: color-mix(in srgb, var(--color-base-800) 55%, transparent);
}
.log-toggle {
  @apply flex size-4 shrink-0 items-center justify-center rounded text-base-400 hover:bg-base-700 hover:text-base-50;
}
.log-time {
  @apply w-[4.6rem] shrink-0 text-base-600 tabular-nums;
}
.log-level {
  @apply w-11 shrink-0 text-[10px] font-bold tracking-wide;
}
.log-msg {
  @apply min-w-0;
}
.log-more {
  @apply ml-1 shrink-0 rounded bg-base-800 px-1.5 text-[10px] leading-4 text-base-400 hover:bg-base-700 hover:text-base-50;
}
.log-indent {
  @apply shrink-0;
  width: calc(1rem + 4.6rem + 2.75rem + 1rem);
  border-right: 2px solid var(--color-base-800);
  align-self: stretch;
}
.log-hit {
  @apply rounded-sm bg-lamp-400/35 text-base-50;
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--color-lamp-400) 60%, transparent);
}

.lv-trace,
.lv-debug {
  @apply text-base-600;
}
.lv-info {
  @apply text-base-200;
}
.lv-info .log-level {
  @apply text-base-400;
}
.lv-warn {
  @apply text-warn;
  background: color-mix(in srgb, var(--color-lamp-900) 35%, transparent);
}
.lv-error,
.lv-fatal {
  @apply text-redstone-300;
  background: color-mix(in srgb, var(--color-redstone-900) 45%, transparent);
}
.log-detail.lv-error,
.log-detail.lv-fatal,
.log-detail.lv-warn {
  @apply opacity-85;
}
.lv-fatal .log-level {
  @apply text-redstone-400;
}

.empty-lamp {
  filter: drop-shadow(0 6px 14px rgb(0 0 0 / 0.45));
}
</style>
