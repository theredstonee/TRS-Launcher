<script setup lang="ts">
// Tagesverlauf einer Kennzahl als inline SVG (keine Bibliothek, CSP-freundlich): Säulen (Mengen je Tag) oder Linie
// (Bestand je Tag, `null` = nicht erfasst → Lücke). Eine Reihe, eine Achse. Zugänglich: role="img" mit Zusammenfassung,
// Tooltip bei Maus UND Tastatur (Pfeiltasten, Pos1/Ende), dazu eine echte Tabelle zum Aufklappen.
const props = withDefaults(defineProps<{
  values: (number | null)[]
  days: string[]
  label: string
  kind?: 'bar' | 'line'
  tone?: 'redstone' | 'lamp' | 'ok'
  texts: { showTable: string, hideTable: string, day: string, value: string, notRecorded: string, total: string }
}>(), { kind: 'bar', tone: 'redstone' })
const { locale, num } = useAdminText()

const W = 640
const H = 180
const PAD_L = 38
const PAD_R = 8
const PAD_T = 10
const PAD_B = 24
const plotW = W - PAD_L - PAD_R
const plotH = H - PAD_T - PAD_B
const n = computed(() => Math.max(1, props.values.length))
const step = computed(() => plotW / n.value)

const max = computed(() => Math.max(1, ...props.values.map((v) => v ?? 0)))
const niceMax = computed(() => {
  const m = max.value
  const p = 10 ** Math.floor(Math.log10(m))
  return ([1, 2, 2.5, 5, 10].find((s) => s * p >= m) ?? 10) * p
})
const y = (v: number) => PAD_T + plotH - (plotH * v) / niceMax.value
const cx = (i: number) => PAD_L + i * step.value + step.value / 2

const bars = computed(() => props.values.map((v, i) => {
  const val = v ?? 0
  const h = (plotH * val) / niceMax.value
  const w = Math.max(1, Math.min(14, step.value - 2))
  return { x: cx(i) - w / 2, w, y: PAD_T + plotH - h, h: val > 0 ? Math.max(2, h) : 0 }
}))

/** Linie in Stücken – `null` trennt (nicht erfasst). */
const segments = computed(() => {
  const out: string[] = []
  let cur: string[] = []
  props.values.forEach((v, i) => {
    if (v === null) {
      if (cur.length) out.push(cur.join(' '))
      cur = []
      return
    }
    cur.push(`${cur.length ? 'L' : 'M'}${cx(i).toFixed(1)},${y(v).toFixed(1)}`)
  })
  if (cur.length) out.push(cur.join(' '))
  return out
})
/** Einzelne Punkte ohne Nachbarn sichtbar machen (z. B. nur heute erfasst). */
const lonely = computed(() => props.values
  .map((v, i) => ({ v, i }))
  .filter(({ v, i }) => v !== null && (props.values[i - 1] ?? null) === null && (props.values[i + 1] ?? null) === null))

const fmtDay = (d: string, opts: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short' }) =>
  new Intl.DateTimeFormat(locale.value, { ...opts, timeZone: 'UTC' }).format(new Date(`${d}T12:00:00Z`))
const ticks = computed(() => {
  const len = props.days.length
  if (!len) return []
  const idx = len <= 8 ? props.days.map((_, i) => i) : [0, Math.round((len - 1) / 3), Math.round((2 * (len - 1)) / 3), len - 1]
  return idx.map((i) => ({ x: cx(i), text: fmtDay(props.days[i]!), anchor: i === 0 ? 'start' : i === len - 1 ? 'end' : 'middle' }))
})

const recorded = computed(() => props.values.filter((v): v is number => v !== null))
const total = computed(() => recorded.value.reduce((s, v) => s + v, 0))
const summary = computed(() => {
  if (!recorded.value.length) return `${props.label}: ${props.texts.notRecorded}`
  const first = props.days[0] ? fmtDay(props.days[0], { dateStyle: 'medium' }) : ''
  const last = props.days.at(-1) ? fmtDay(props.days.at(-1)!, { dateStyle: 'medium' }) : ''
  const peak = Math.max(...recorded.value)
  return props.kind === 'bar'
    ? `${props.label}, ${first} – ${last}: ${props.texts.total} ${num(total.value)}, max. ${num(peak)}`
    : `${props.label}, ${first} – ${last}: max. ${num(peak)}, ${props.texts.value} ${num(recorded.value.at(-1)!)}`
})

// Tooltip: Maus über der Fläche oder Fokus + Pfeiltasten.
const active = ref<number | null>(null)
const svg = ref<SVGSVGElement | null>(null)
function pick(e: PointerEvent) {
  const el = svg.value
  if (!el) return
  const r = el.getBoundingClientRect()
  const x = ((e.clientX - r.left) / r.width) * W
  active.value = Math.min(n.value - 1, Math.max(0, Math.floor((x - PAD_L) / step.value)))
}
function key(e: KeyboardEvent) {
  const last = props.values.length - 1
  const cur = active.value ?? last
  const next = e.key === 'ArrowLeft' ? cur - 1 : e.key === 'ArrowRight' ? cur + 1 : e.key === 'Home' ? 0 : e.key === 'End' ? last : null
  if (next === null) return
  e.preventDefault()
  active.value = Math.min(last, Math.max(0, next))
}
const tip = computed(() => {
  const i = active.value
  if (i === null || !props.days[i]) return null
  const v = props.values[i] ?? null
  return {
    i,
    // Am Rand nicht abschneiden.
    left: `${Math.min(88, Math.max(12, (cx(i) / W) * 100))}%`,
    day: fmtDay(props.days[i], { weekday: 'short', day: 'numeric', month: 'short' }),
    value: v === null ? props.texts.notRecorded : num(v),
    y: v === null ? PAD_T + plotH : y(v),
  }
})
const showTable = ref(false)
const tableId = useId()
</script>

<template>
  <figure class="adm-series" :class="`series-${tone}`">
    <div class="relative">
      <svg
        ref="svg"
        class="block h-auto w-full touch-pan-y"
        :viewBox="`0 0 ${W} ${H}`"
        role="img"
        :aria-label="summary"
        tabindex="0"
        @pointermove="pick"
        @pointerleave="active = null"
        @blur="active = null"
        @keydown="key"
      >
        <line class="grid" :x1="PAD_L" :x2="W - PAD_R" :y1="PAD_T + plotH" :y2="PAD_T + plotH" />
        <line class="grid soft" :x1="PAD_L" :x2="W - PAD_R" :y1="PAD_T + plotH / 2" :y2="PAD_T + plotH / 2" />
        <line class="grid soft" :x1="PAD_L" :x2="W - PAD_R" :y1="PAD_T" :y2="PAD_T" />
        <text class="axis" :x="PAD_L - 6" :y="PAD_T + 4" text-anchor="end">{{ num(niceMax) }}</text>
        <text class="axis" :x="PAD_L - 6" :y="PAD_T + plotH / 2 + 4" text-anchor="end">{{ num(niceMax / 2) }}</text>
        <text class="axis" :x="PAD_L - 6" :y="PAD_T + plotH + 4" text-anchor="end">0</text>
        <template v-if="kind === 'bar'">
          <path
            v-for="(b, i) in bars"
            v-show="b.h > 0"
            :key="i"
            class="mark"
            :class="{ dim: tip && tip.i !== i }"
            :d="`M${b.x},${b.y + b.h} V${b.y + Math.min(3, b.h / 2)} Q${b.x},${b.y} ${b.x + Math.min(3, b.w / 2)},${b.y} H${b.x + b.w - Math.min(3, b.w / 2)} Q${b.x + b.w},${b.y} ${b.x + b.w},${b.y + Math.min(3, b.h / 2)} V${b.y + b.h} Z`"
          />
        </template>
        <template v-else>
          <path v-for="(d, i) in segments" :key="i" class="line" :d="d" />
          <circle v-for="p in lonely" :key="`p${p.i}`" class="dot" :cx="cx(p.i)" :cy="y(p.v!)" r="3.5" />
        </template>
        <template v-if="tip">
          <line class="cursor" :x1="cx(tip.i)" :x2="cx(tip.i)" :y1="PAD_T" :y2="PAD_T + plotH" />
          <circle v-if="kind === 'line' && values[tip.i] !== null" class="dot ring" :cx="cx(tip.i)" :cy="tip.y" r="4.5" />
        </template>
        <text v-for="t in ticks" :key="t.x" class="axis" :x="t.x" :y="H - 6" :text-anchor="t.anchor">{{ t.text }}</text>
      </svg>
      <div v-if="tip" class="adm-series-tip" :style="{ left: tip.left }" aria-hidden="true">
        <span class="text-base-400">{{ tip.day }}</span>
        <strong class="tabular-nums text-base-50">{{ tip.value }}</strong>
      </div>
      <p class="sr-only" aria-live="polite">{{ tip ? `${tip.day}: ${tip.value}` : '' }}</p>
    </div>
    <button type="button" class="adm-series-toggle" :aria-expanded="showTable" :aria-controls="tableId" @click="showTable = !showTable">
      {{ showTable ? texts.hideTable : texts.showTable }}
    </button>
    <div v-if="showTable" :id="tableId" class="mt-2 max-h-64 overflow-y-auto rounded-lg border border-base-800">
      <table class="w-full text-left text-xs">
        <caption class="sr-only">{{ label }}</caption>
        <thead class="sticky top-0 bg-base-900 text-base-400">
          <tr><th scope="col" class="px-3 py-1.5">{{ texts.day }}</th><th scope="col" class="px-3 py-1.5 text-right">{{ texts.value }}</th></tr>
        </thead>
        <tbody class="divide-y divide-base-800">
          <tr v-for="(d, i) in [...days].reverse()" :key="d">
            <th scope="row" class="px-3 py-1 font-normal text-base-300">{{ fmtDay(d, { dateStyle: 'medium' }) }}</th>
            <td class="px-3 py-1 text-right tabular-nums text-base-50">{{ values[days.length - 1 - i] === null ? texts.notRecorded : num(values[days.length - 1 - i]!) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </figure>
</template>

<style scoped>
.adm-series {
  --mark: var(--color-redstone-500);
  --mark-hi: var(--color-redstone-400);
}
.adm-series.series-lamp {
  --mark: var(--color-lamp-400);
  --mark-hi: var(--color-lamp-300);
}
.adm-series.series-ok {
  --mark: var(--color-ok);
  --mark-hi: var(--color-ok);
}
svg:focus-visible {
  outline: 2px solid var(--mark-hi);
  outline-offset: 4px;
  border-radius: 6px;
}
.grid {
  stroke: var(--color-base-700);
  stroke-width: 1;
}
.grid.soft {
  stroke: var(--color-base-800);
  stroke-dasharray: 3 4;
}
.axis {
  fill: var(--color-base-400);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.mark {
  fill: var(--mark);
  transition: opacity 0.12s;
}
.mark.dim {
  opacity: 0.45;
}
.line {
  fill: none;
  stroke: var(--mark);
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}
.dot {
  fill: var(--mark);
}
.dot.ring {
  stroke: var(--color-base-900);
  stroke-width: 2;
}
.cursor {
  stroke: var(--color-base-600);
  stroke-width: 1;
}
.adm-series-tip {
  position: absolute;
  top: 0;
  transform: translateX(-50%);
  display: flex;
  gap: 0.5rem;
  align-items: baseline;
  white-space: nowrap;
  pointer-events: none;
  padding: 0.25rem 0.5rem;
  border-radius: 0.5rem;
  font-size: 0.75rem;
  background: var(--color-base-850);
  border: 1px solid var(--color-base-700);
  box-shadow: 0 4px 14px rgb(0 0 0 / 0.25);
}
.adm-series-toggle {
  margin-top: 0.25rem;
  font-size: 0.75rem;
  color: var(--color-base-400);
  text-decoration: underline;
  text-underline-offset: 3px;
}
.adm-series-toggle:hover {
  color: var(--color-base-200);
}
@media (prefers-reduced-motion: reduce) {
  .mark {
    transition: none;
  }
}
</style>
