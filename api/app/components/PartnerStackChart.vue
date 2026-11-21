<script setup lang="ts">
// Gestapelte Tagessäulen für zwei Reihen (z. B. Einlösungen fester Code + Einmal-Codes) als inline SVG.
// Tooltip bei Maus und Tastatur (Pfeiltasten), Legende und Wertetabelle zum Aufklappen.
const props = defineProps<{
  a: number[]
  b: number[]
  days: string[]
  labels: [string, string]
  title: string
  texts: { showTable: string, hideTable: string, day: string, value: string, total: string }
}>()
const { locale, num } = useAdminText()

const W = 640
const H = 180
const PAD_L = 38
const PAD_R = 8
const PAD_T = 10
const PAD_B = 24
const plotW = W - PAD_L - PAD_R
const plotH = H - PAD_T - PAD_B
const n = computed(() => Math.max(1, props.days.length))
const step = computed(() => plotW / n.value)
const sums = computed(() => props.days.map((_, i) => (props.a[i] ?? 0) + (props.b[i] ?? 0)))
const niceMax = computed(() => {
  const m = Math.max(1, ...sums.value)
  const p = 10 ** Math.floor(Math.log10(m))
  return ([1, 2, 2.5, 5, 10].find((s) => s * p >= m) ?? 10) * p
})
const cx = (i: number) => PAD_L + i * step.value + step.value / 2
const h = (v: number) => (plotH * v) / niceMax.value
const bars = computed(() => props.days.map((_, i) => {
  const w = Math.max(1, Math.min(14, step.value - 2))
  const ha = h(props.a[i] ?? 0)
  const hb = h(props.b[i] ?? 0)
  const base = PAD_T + plotH
  return { x: cx(i) - w / 2, w, ya: base - ha, ha, yb: base - ha - hb, hb }
}))
const fmtDay = (d: string, opts: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short' }) =>
  new Intl.DateTimeFormat(locale.value, { ...opts, timeZone: 'UTC' }).format(new Date(`${d}T12:00:00Z`))
const ticks = computed(() => {
  const len = props.days.length
  if (!len) return []
  const idx = len <= 8 ? props.days.map((_, i) => i) : [0, Math.round((len - 1) / 3), Math.round((2 * (len - 1)) / 3), len - 1]
  return idx.map((i) => ({ x: cx(i), text: fmtDay(props.days[i]!), anchor: i === 0 ? 'start' : i === len - 1 ? 'end' : 'middle' }))
})
const totalA = computed(() => props.a.reduce((s, v) => s + v, 0))
const totalB = computed(() => props.b.reduce((s, v) => s + v, 0))
const summary = computed(() => `${props.title}: ${props.labels[0]} ${num(totalA.value)}, ${props.labels[1]} ${num(totalB.value)}`)

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
  const last = props.days.length - 1
  const cur = active.value ?? last
  const next = e.key === 'ArrowLeft' ? cur - 1 : e.key === 'ArrowRight' ? cur + 1 : e.key === 'Home' ? 0 : e.key === 'End' ? last : null
  if (next === null) return
  e.preventDefault()
  active.value = Math.min(last, Math.max(0, next))
}
const tip = computed(() => {
  const i = active.value
  if (i === null || !props.days[i]) return null
  return {
    i,
    left: `${Math.min(84, Math.max(16, (cx(i) / W) * 100))}%`,
    day: fmtDay(props.days[i], { weekday: 'short', day: 'numeric', month: 'short' }),
    a: num(props.a[i] ?? 0),
    b: num(props.b[i] ?? 0),
  }
})
const showTable = ref(false)
const tableId = useId()
</script>

<template>
  <figure class="stack-chart">
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
        <g v-for="(bar, i) in bars" :key="i" :class="{ dim: tip && tip.i !== i }" class="col">
          <rect v-if="bar.ha > 0" class="mark-a" :x="bar.x" :y="bar.ya" :width="bar.w" :height="Math.max(2, bar.ha)" rx="1.5" />
          <rect v-if="bar.hb > 0" class="mark-b" :x="bar.x" :y="bar.yb - (bar.ha > 0 ? 1 : 0)" :width="bar.w" :height="Math.max(2, bar.hb)" rx="1.5" />
        </g>
        <line v-if="tip" class="cursor" :x1="cx(tip.i)" :x2="cx(tip.i)" :y1="PAD_T" :y2="PAD_T + plotH" />
        <text v-for="t in ticks" :key="t.x" class="axis" :x="t.x" :y="H - 6" :text-anchor="t.anchor">{{ t.text }}</text>
      </svg>
      <div v-if="tip" class="stack-tip" :style="{ left: tip.left }" aria-hidden="true">
        <span class="text-base-400">{{ tip.day }}</span>
        <span><i class="sw sw-a" />{{ tip.a }}</span>
        <span><i class="sw sw-b" />{{ tip.b }}</span>
      </div>
      <p class="sr-only" aria-live="polite">{{ tip ? `${tip.day}: ${labels[0]} ${tip.a}, ${labels[1]} ${tip.b}` : '' }}</p>
    </div>
    <figcaption class="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-base-400">
      <span><i class="sw sw-a" />{{ labels[0] }} · {{ num(totalA) }}</span>
      <span><i class="sw sw-b" />{{ labels[1] }} · {{ num(totalB) }}</span>
      <button type="button" class="toggle ml-auto" :aria-expanded="showTable" :aria-controls="tableId" @click="showTable = !showTable">
        {{ showTable ? texts.hideTable : texts.showTable }}
      </button>
    </figcaption>
    <div v-if="showTable" :id="tableId" class="mt-2 max-h-64 overflow-y-auto rounded-lg border border-base-800">
      <table class="w-full text-left text-xs">
        <caption class="sr-only">{{ title }}</caption>
        <thead class="sticky top-0 bg-base-900 text-base-400">
          <tr>
            <th scope="col" class="px-3 py-1.5">{{ texts.day }}</th>
            <th scope="col" class="px-3 py-1.5 text-right">{{ labels[0] }}</th>
            <th scope="col" class="px-3 py-1.5 text-right">{{ labels[1] }}</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-base-800">
          <tr v-for="i in [...days.keys()].reverse()" :key="days[i]">
            <th scope="row" class="px-3 py-1 font-normal text-base-300">{{ fmtDay(days[i]!, { dateStyle: 'medium' }) }}</th>
            <td class="px-3 py-1 text-right tabular-nums text-base-50">{{ num(a[i] ?? 0) }}</td>
            <td class="px-3 py-1 text-right tabular-nums text-base-50">{{ num(b[i] ?? 0) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </figure>
</template>

<style scoped>
svg:focus-visible {
  outline: 2px solid var(--color-redstone-400);
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
.mark-a {
  fill: var(--color-redstone-500);
}
.mark-b {
  fill: var(--color-lamp-400);
}
.col {
  transition: opacity 0.12s;
}
.col.dim {
  opacity: 0.45;
}
.cursor {
  stroke: var(--color-base-600);
  stroke-width: 1;
}
.sw {
  display: inline-block;
  width: 0.6rem;
  height: 0.6rem;
  margin-right: 0.35rem;
  border-radius: 2px;
  vertical-align: -1px;
}
.sw-a {
  background: var(--color-redstone-500);
}
.sw-b {
  background: var(--color-lamp-400);
}
.stack-tip {
  position: absolute;
  top: 0;
  transform: translateX(-50%);
  display: flex;
  gap: 0.6rem;
  align-items: baseline;
  white-space: nowrap;
  pointer-events: none;
  padding: 0.25rem 0.5rem;
  border-radius: 0.5rem;
  font-size: 0.75rem;
  color: var(--color-base-50);
  background: var(--color-base-850);
  border: 1px solid var(--color-base-700);
  box-shadow: 0 4px 14px rgb(0 0 0 / 0.25);
}
.toggle {
  text-decoration: underline;
  text-underline-offset: 3px;
}
.toggle:hover {
  color: var(--color-base-200);
}
@media (prefers-reduced-motion: reduce) {
  .col {
    transition: none;
  }
}
</style>
