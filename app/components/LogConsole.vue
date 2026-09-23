<script setup lang="ts">
import type { LogLevel, LogLine } from '~/types'

const props = defineProps<{ lines: LogLine[] }>()

// Bei sehr langen Logs nur das Ende rendern – der Rest steht in logs/latest.log.
const MAX_RENDERED = 1500

type Filter = 'all' | 'warn' | 'error'
const filter = ref<Filter>('all')
const search = ref('')
const follow = ref(true)
const copied = ref(false)
const scroller = ref<HTMLElement | null>(null)

const rank: Record<LogLevel, number> = { trace: 0, debug: 1, info: 2, warn: 3, error: 4, fatal: 5 }
const minRank = computed(() => (filter.value === 'error' ? 4 : filter.value === 'warn' ? 3 : 0))

const visible = computed(() => {
  // Bewusst einfacher Teilstring-Vergleich – kein RegExp aus Nutzereingaben.
  const needle = search.value.trim().toLowerCase()
  const matching = props.lines.filter(
    (l) => rank[l.level] >= minRank.value && (!needle || l.message.toLowerCase().includes(needle)),
  )
  return matching.slice(-MAX_RENDERED)
})

const counts = computed(() => ({
  warn: props.lines.filter((l) => l.level === 'warn').length,
  error: props.lines.filter((l) => rank[l.level] >= 4).length,
}))

// Uhrzeit mit Sekunden in der eingestellten Sprache (intlLocale ist reaktiv).
const timeFormat = computed(() => new Intl.DateTimeFormat(intlLocale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' }))

function onScroll() {
  const el = scroller.value
  if (el) follow.value = el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

watch(
  () => [visible.value.length, props.lines.length],
  async () => {
    if (!follow.value) return
    await nextTick()
    scroller.value?.scrollTo({ top: scroller.value.scrollHeight })
  },
  { immediate: true },
)

async function copyAll() {
  const text = visible.value
    .map((l) => `[${timeFormat.value.format(l.time)}] [${l.thread ?? '-'}/${l.level.toUpperCase()}] ${l.message}`)
    .join('\n')
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    // Zwischenablage nicht verfügbar – dann eben nicht.
  }
}
</script>

<template>
  <div class="card flex min-h-0 flex-1 flex-col overflow-hidden">
    <div class="flex flex-wrap items-center gap-2 border-b border-base-800 px-3 py-2">
      <div class="flex overflow-hidden rounded-md border border-base-700 text-xs">
        <button class="seg" :class="{ 'seg-on': filter === 'all' }" @click="filter = 'all'">{{ t('common.labels.all') }}</button>
        <button class="seg" :class="{ 'seg-on': filter === 'warn' }" @click="filter = 'warn'">
          {{ t('logConsole.warnings') }} <span class="text-warn">{{ counts.warn }}</span>
        </button>
        <button class="seg" :class="{ 'seg-on': filter === 'error' }" @click="filter = 'error'">
          {{ t('logConsole.errors') }} <span class="text-redstone-300">{{ counts.error }}</span>
        </button>
      </div>
      <input v-model="search" class="field h-7 max-w-56 py-0 text-xs" maxlength="200" :placeholder="t('logConsole.search')" :aria-label="t('logConsole.search')" spellcheck="false" />
      <span class="ml-auto text-xs text-base-600">{{ t('logConsole.lineCount', lines.length) }}</span>
      <button class="btn btn-ghost h-7 px-2.5 py-0 text-xs" :disabled="!visible.length" @click="copyAll">
        {{ copied ? t('logConsole.copied') : t('common.actions.copy') }}
      </button>
    </div>

    <div ref="scroller" class="min-h-0 flex-1 overflow-y-auto bg-base-950 p-3 font-mono text-xs leading-5 select-text" @scroll.passive="onScroll">
      <p v-if="!lines.length" class="py-10 text-center font-sans text-sm text-base-600">
        {{ t('logConsole.empty') }}
      </p>
      <div v-for="(l, i) in visible" :key="i" class="flex gap-2 whitespace-pre-wrap break-all" :class="`lv-${l.level}`">
        <span class="shrink-0 text-base-600">{{ timeFormat.format(l.time) }}</span>
        <span class="w-11 shrink-0 font-semibold uppercase">{{ l.level }}</span>
        <span class="min-w-0">{{ l.message }}</span>
      </div>
    </div>

    <button v-if="!follow && lines.length" class="border-t border-base-800 bg-base-850 py-1 text-xs text-base-400 hover:text-base-50" @click="follow = true; onScroll(); scroller?.scrollTo({ top: scroller.scrollHeight })">
      {{ t('logConsole.jumpToEnd') }}
    </button>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.seg {
  @apply px-2.5 py-1 text-base-400 transition-colors hover:text-base-50;
}
.seg-on {
  @apply bg-base-700 text-base-50;
}
.lv-trace,
.lv-debug {
  @apply text-base-600;
}
.lv-info {
  @apply text-base-200;
}
.lv-warn {
  @apply text-warn;
}
.lv-error,
.lv-fatal {
  @apply text-redstone-300;
}
</style>
