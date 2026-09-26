<script setup lang="ts">
// Kleine Säulengrafik als inline SVG (keine Bibliothek, CSP-freundlich). Tage auf der x-Achse,
// höchster Wert als Skala; jede Säule hat einen Tooltip (<title>) mit Datum und Wert.
const props = defineProps<{ values: number[], days: string[], label: string }>()
const { locale } = useAdminText()

const W = 600
const H = 150
const PAD_L = 28
const PAD_B = 18
const max = computed(() => Math.max(1, ...props.values))
const niceMax = computed(() => {
  const m = max.value
  const p = 10 ** Math.floor(Math.log10(m))
  const steps = [1, 2, 2.5, 5, 10]
  return (steps.find((s) => s * p >= m) ?? 10) * p
})
const bars = computed(() => {
  const n = props.values.length || 1
  const w = (W - PAD_L) / n
  return props.values.map((v, i) => {
    const h = ((H - PAD_B - 6) * v) / niceMax.value
    return { x: PAD_L + i * w + w * 0.15, w: Math.max(1, w * 0.7), y: H - PAD_B - h, h, v, day: props.days[i] ?? '' }
  })
})
const fmt = (d: string) => new Intl.DateTimeFormat(locale.value, { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(new Date(`${d}T12:00:00Z`))
const ticks = computed(() => {
  const n = props.days.length
  if (!n) return []
  const idx = n <= 7 ? props.days.map((_, i) => i) : [0, Math.floor(n / 3), Math.floor((2 * n) / 3), n - 1]
  const w = (W - PAD_L) / n
  return idx.map((i) => ({ x: PAD_L + i * w + w / 2, text: fmt(props.days[i]!) }))
})
const total = computed(() => props.values.reduce((s, v) => s + v, 0))
</script>

<template>
  <svg class="adm-chart block h-auto w-full" :viewBox="`0 0 ${W} ${H}`" role="img" :aria-label="`${label}: ${total}`">
    <line class="grid" :x1="PAD_L" :x2="W" :y1="H - PAD_B" :y2="H - PAD_B" />
    <line class="grid" :x1="PAD_L" :x2="W" :y1="6 + (H - PAD_B - 6) / 2" :y2="6 + (H - PAD_B - 6) / 2" stroke-dasharray="3 4" />
    <line class="grid" :x1="PAD_L" :x2="W" y1="6" y2="6" stroke-dasharray="3 4" />
    <text x="0" y="12">{{ niceMax }}</text>
    <text x="0" :y="H - PAD_B + 3">0</text>
    <rect v-for="(b, i) in bars" :key="i" class="bar" :x="b.x" :y="b.y" :width="b.w" :height="Math.max(b.v > 0 ? 1.5 : 0, b.h)" rx="1.5">
      <title>{{ fmt(b.day) }}: {{ b.v }}</title>
    </rect>
    <text v-for="t in ticks" :key="t.x" :x="t.x" :y="H - 4" text-anchor="middle">{{ t.text }}</text>
  </svg>
</template>
