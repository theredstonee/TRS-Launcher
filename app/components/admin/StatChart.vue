<script setup lang="ts">
import { lineSegments, niceMax, seriesSummary, type ChartPoint } from '~/utils/creatorStats'

// Tagesreihe als schlanke Säulen (`bar`) oder Linie (`line`) – immer EINE Reihe,
// eine Achse, Akzentfarbe. Nicht erfasste Tage (`null`) bleiben als Lücke stehen.
// Bedienung: Maus/Touch über der Fläche oder Fokus + Pfeiltasten (eine Tab-Stelle);
// der gewählte Tag steht als Text oben (aria-live). „Als Tabelle anzeigen“ zeigt
// dieselben Zahlen als echte Tabelle – nichts ist nur über die Grafik lesbar.
const props = defineProps<{ points: ChartPoint[]; label: string; kind?: 'bar' | 'line' }>()

const W = 600
const H = 160
const TOP = 6
const kind = computed(() => props.kind ?? 'bar')
const summary = computed(() => seriesSummary(props.points))
const max = computed(() => niceMax(summary.value.peak))
const n = computed(() => props.points.length)
const hover = ref<number | null>(null)
const table = ref(false)

const bars = computed(() => {
  const w = W / Math.max(1, n.value)
  const gap = n.value > 120 ? 0 : Math.min(2, w * 0.25)
  return props.points.map((p, i) => {
    const h = p.value ? Math.max(1.5, ((H - TOP) * p.value) / max.value) : 0
    return { x: i * w + gap / 2, w: Math.max(0.6, w - gap), y: H - h, h, known: p.value !== null }
  })
})
const segments = computed(() => lineSegments(props.points, W, H, TOP))
/** Einzelne erfasste Tage zwischen zwei Lücken wären als Linie unsichtbar → als Punkt zeichnen. */
const dots = computed(() => {
  if (kind.value !== 'line') return []
  const x = (i: number) => (n.value === 1 ? W / 2 : (i / (n.value - 1)) * W)
  return props.points
    .map((p, i) => ({ p, i }))
    .filter(({ p, i }) => p.value !== null && props.points[i - 1]?.value == null && props.points[i + 1]?.value == null)
    .map(({ p, i }) => ({ cx: x(i), cy: TOP + (H - TOP) * (1 - (p.value ?? 0) / max.value) }))
})
/** Bereich vor dem ersten erfassten Tag (Linie) – dezent schraffiert. */
const unknownUntil = computed(() => {
  if (kind.value !== 'line') return 0
  const first = props.points.findIndex((p) => p.value !== null)
  if (first < 0) return W
  return n.value <= 1 ? 0 : (first / (n.value - 1)) * W
})

const cursorX = computed(() => {
  if (hover.value === null || !n.value) return null
  if (kind.value === 'bar') return ((hover.value + 0.5) / n.value) * W
  return n.value === 1 ? W / 2 : (hover.value / (n.value - 1)) * W
})
const cursorY = computed(() => {
  const p = hover.value !== null ? props.points[hover.value] : null
  if (!p || p.value === null || kind.value !== 'line') return null
  return TOP + (H - TOP) * (1 - p.value / max.value)
})

function dayLabel(day: string, long = false): string {
  const d = new Date(`${day}T12:00:00Z`)
  if (Number.isNaN(d.getTime())) return day
  return new Intl.DateTimeFormat(intlLocale(), long ? { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' } : { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(d)
}
const valueText = (v: number | null) => (v === null ? t('team.creatorStats.detail.notRecorded') : formatNumber(v))
const current = computed(() => (hover.value !== null ? props.points[hover.value] ?? null : null))
const ticks = computed(() => {
  const len = n.value
  if (!len) return []
  const idx = len <= 7 ? props.points.map((_, i) => i) : [0, Math.round((len - 1) / 3), Math.round((2 * (len - 1)) / 3), len - 1]
  return idx.map((i) => ({ left: `${((kind.value === 'bar' ? i + 0.5 : i) / Math.max(1, kind.value === 'bar' ? len : len - 1)) * 100}%`, text: dayLabel(props.points[i]!.day), i }))
})

const area = useTemplateRef<HTMLElement>('area')
function pointAt(clientX: number) {
  const el = area.value
  if (!el || !n.value) return
  const r = el.getBoundingClientRect()
  const f = Math.min(1, Math.max(0, (clientX - r.left) / r.width))
  hover.value = kind.value === 'bar' ? Math.min(n.value - 1, Math.floor(f * n.value)) : Math.round(f * (n.value - 1))
}
function onKey(e: KeyboardEvent) {
  if (!n.value) return
  const last = n.value - 1
  const now = hover.value ?? last
  const next = e.key === 'ArrowLeft' ? now - 1 : e.key === 'ArrowRight' ? now + 1 : e.key === 'Home' ? 0 : e.key === 'End' ? last : null
  if (next === null) return
  e.preventDefault()
  hover.value = Math.min(last, Math.max(0, next))
}
const describe = computed(() =>
  t('team.creatorStats.detail.chartSummary', {
    label: props.label,
    days: n.value,
    total: formatNumber(summary.value.total),
    peak: formatNumber(summary.value.peak),
  }),
)
</script>

<template>
  <figure class="min-w-0">
    <div class="mb-2 flex flex-wrap items-baseline gap-x-3 gap-y-1 text-xs text-base-400">
      <span v-if="kind === 'bar'">{{ t('team.creatorStats.detail.total', { n: formatNumber(summary.total) }) }}</span>
      <span>{{ t('team.creatorStats.detail.peak', { n: formatNumber(summary.peak) }) }}</span>
      <span class="ml-auto min-h-4 font-medium text-base-50 tabular-nums" aria-live="polite">
        <template v-if="current">{{ dayLabel(current.day, true) }} · {{ valueText(current.value) }}</template>
      </span>
    </div>
    <div class="flex gap-2">
      <!-- y-Achse: 0, Hälfte, Obergrenze -->
      <div class="relative h-36 w-8 shrink-0 text-right text-[10px] text-base-400 tabular-nums" aria-hidden="true">
        <span class="absolute right-0 top-0 -translate-y-1/2">{{ formatNumber(max) }}</span>
        <span class="absolute right-0 top-1/2 -translate-y-1/2">{{ formatNumber(max / 2, 1) }}</span>
        <span class="absolute right-0 bottom-0 translate-y-1/2">0</span>
      </div>
      <div class="min-w-0 flex-1">
        <div
          ref="area"
          class="stat-chart relative h-36 cursor-crosshair rounded-sm outline-none focus-visible:ring-2 focus-visible:ring-redstone-400"
          tabindex="0"
          role="img"
          :aria-label="describe"
          @mousemove="pointAt($event.clientX)"
          @mouseleave="hover = null"
          @touchstart.passive="pointAt($event.touches[0]!.clientX)"
          @touchmove.passive="pointAt($event.touches[0]!.clientX)"
          @focus="hover ??= n ? n - 1 : null"
          @blur="hover = null"
          @keydown="onKey"
        >
          <svg class="absolute inset-0 size-full overflow-visible" :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" aria-hidden="true">
            <defs>
              <pattern id="stat-chart-hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
                <line x1="0" y1="0" x2="0" y2="6" class="stat-hatch" stroke-width="2" />
              </pattern>
            </defs>
            <line class="stat-grid" x1="0" :x2="W" :y1="TOP" :y2="TOP" stroke-dasharray="3 4" vector-effect="non-scaling-stroke" />
            <line class="stat-grid" x1="0" :x2="W" :y1="TOP + (H - TOP) / 2" :y2="TOP + (H - TOP) / 2" stroke-dasharray="3 4" vector-effect="non-scaling-stroke" />
            <line class="stat-axis" x1="0" :x2="W" :y1="H" :y2="H" vector-effect="non-scaling-stroke" />
            <rect v-if="unknownUntil > 0" x="0" :y="TOP" :width="unknownUntil" :height="H - TOP" fill="url(#stat-chart-hatch)" class="stat-unknown" />
            <template v-if="kind === 'bar'">
              <rect
                v-for="(b, i) in bars"
                :key="i"
                :x="b.x"
                :y="b.y"
                :width="b.w"
                :height="b.h"
                :class="hover === i ? 'stat-bar-on' : 'stat-bar'"
              />
            </template>
            <template v-else>
              <path v-for="(d, i) in segments" :key="i" :d="d" class="stat-line" fill="none" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" vector-effect="non-scaling-stroke" />
            </template>
            <line v-if="cursorX !== null" class="stat-cursor" :x1="cursorX" :x2="cursorX" :y1="TOP" :y2="H" vector-effect="non-scaling-stroke" />
          </svg>
          <!-- Punkte als HTML, damit sie trotz gestreckter Fläche rund bleiben -->
          <span
            v-for="(d, i) in dots"
            :key="`d${i}`"
            class="stat-dot pointer-events-none absolute size-2 -translate-x-1/2 -translate-y-1/2 rounded-full"
            :style="{ left: `${(d.cx / W) * 100}%`, top: `${(d.cy / H) * 100}%` }"
          />
          <span
            v-if="cursorX !== null && cursorY !== null"
            class="stat-dot pointer-events-none absolute size-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full ring-2 ring-base-900"
            :style="{ left: `${(cursorX / W) * 100}%`, top: `${(cursorY / H) * 100}%` }"
          />
          <p v-if="kind === 'line' && !summary.known" class="absolute inset-0 grid place-items-center text-xs text-base-400">{{ t('team.creatorStats.detail.noActivityYet') }}</p>
        </div>
        <div class="relative mt-1 h-4 text-[10px] text-base-400" aria-hidden="true">
          <span
            v-for="(tk, k) in ticks"
            :key="tk.i"
            class="absolute whitespace-nowrap"
            :class="k === 0 ? '' : k === ticks.length - 1 ? '-translate-x-full' : '-translate-x-1/2'"
            :style="{ left: k === 0 ? '0' : k === ticks.length - 1 ? '100%' : tk.left }"
          >{{ tk.text }}</span>
        </div>
      </div>
    </div>
    <button type="button" class="mt-2 text-xs text-base-400 underline-offset-2 hover:text-base-50 hover:underline" :aria-expanded="table" @click="table = !table">
      {{ table ? t('team.creatorStats.detail.hideTable') : t('team.creatorStats.detail.showTable') }}
    </button>
    <div v-if="table" class="mt-2 max-h-64 overflow-y-auto rounded-lg border border-base-800">
      <table class="w-full text-xs">
        <caption class="sr-only">{{ label }}</caption>
        <thead class="sticky top-0 bg-base-900">
          <tr class="text-left text-[11px] tracking-wide text-base-400 uppercase">
            <th scope="col" class="px-3 py-1.5 font-medium">{{ t('team.creatorStats.detail.day') }}</th>
            <th scope="col" class="px-3 py-1.5 text-right font-medium">{{ t('team.creatorStats.detail.value') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in [...points].reverse()" :key="p.day" class="border-t border-base-800">
            <th scope="row" class="px-3 py-1 text-left font-normal text-base-200">{{ dayLabel(p.day, true) }}</th>
            <td class="px-3 py-1 text-right tabular-nums" :class="p.value === null ? 'text-base-400' : 'text-base-50'">{{ valueText(p.value) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </figure>
</template>

<style scoped>
/* Farben aus den Design-Tokens (hell/dunkel über die Basis- und Akzent-Skalen). */
.stat-grid { stroke: var(--color-base-800); stroke-width: 1; }
.stat-axis { stroke: var(--color-base-700); stroke-width: 1; }
.stat-cursor { stroke: var(--color-base-400); stroke-width: 1; stroke-dasharray: 2 3; }
.stat-bar { fill: color-mix(in oklab, var(--color-redstone-500) 78%, transparent); }
.stat-bar-on { fill: var(--color-redstone-400); }
.stat-line { stroke: var(--color-redstone-400); }
.stat-dot { background: var(--color-redstone-400); }
.stat-hatch { stroke: var(--color-base-800); }
</style>
