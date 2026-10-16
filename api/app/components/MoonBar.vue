<script setup lang="ts">
// „Mondlauf“ (Halloween 2026): ersetzt Fortschrittsbalken, solange das Event läuft. Der Mond nimmt mit dem Fortschritt
// zu (0 % Neumond → 100 % Vollmond mit Leuchten); ohne festen Wert (`progress = null`) gleitet er hin und her und
// durchläuft die Phasen. Bei „Bewegung reduzieren“ steht er still.
const props = defineProps<{ progress: number | null, label?: string }>()

const moon = useTemplateRef<HTMLCanvasElement>('moon')
const rail = useTemplateRef<HTMLDivElement>('rail')
const reduced = useReducedMotion()
const pos = ref(0)
let raf = 0

const p = computed(() => (props.progress === null ? null : Math.max(0, Math.min(1, props.progress))))

function draw(phase: number) {
  if (moon.value) moonPhase(moon.value, phase)
}

function tick(now: number) {
  const t = now / 1000
  pos.value = 0.5 - 0.5 * Math.cos(t * 1.2)
  draw(0.5 - 0.5 * Math.cos(t * 0.7))
  raf = requestAnimationFrame(tick)
}

function update() {
  cancelAnimationFrame(raf)
  raf = 0
  if (p.value !== null) {
    pos.value = p.value
    draw(p.value)
  } else if (reduced.value) {
    pos.value = 0.5
    draw(0.5)
  } else raf = requestAnimationFrame(tick)
}

onMounted(update)
watch([p, reduced], update)
onBeforeUnmount(() => cancelAnimationFrame(raf))
</script>

<template>
  <div
    class="moonbar"
    :class="{ full: p !== null && p >= 1 }"
    role="progressbar"
    :aria-valuemin="0"
    :aria-valuemax="100"
    :aria-valuenow="p === null ? undefined : Math.round(p * 100)"
    :aria-label="label"
  >
    <div ref="rail" class="rail" />
    <div v-if="p !== null" class="fill" :style="{ width: `${p * 100}%` }" />
    <canvas ref="moon" width="13" height="13" class="moon" :style="{ left: `${pos * 100}%` }" aria-hidden="true" />
    <span v-if="label" class="lbl">{{ label }}</span>
  </div>
</template>

<style scoped>
.moonbar {
  position: relative;
  height: 28px;
}
.rail {
  position: absolute;
  left: 0;
  right: 0;
  top: 13px;
  height: 2px;
  border-radius: 2px;
  background: rgb(207 213 227 / 0.14);
}
.rail::before {
  content: "";
  position: absolute;
  inset: -5px 0;
  background: repeating-linear-gradient(90deg, rgb(185 162 234 / 0.08) 0 2px, transparent 2px 6px);
}
.fill {
  position: absolute;
  left: 0;
  top: 12px;
  height: 4px;
  border-radius: 4px;
  background: linear-gradient(90deg, rgb(142 108 201 / 0.05), rgb(185 162 234 / 0.55) 60%, #f3f5fb);
  box-shadow: 0 0 12px rgb(207 213 227 / 0.45);
  transition: width 0.4s ease;
}
.moon {
  position: absolute;
  top: 0;
  width: 28px;
  height: 28px;
  margin-left: -14px;
  image-rendering: pixelated;
  filter: drop-shadow(0 0 6px rgb(243 245 251 / 0.55));
  transition: left 0.4s ease;
}
.moonbar.full .moon {
  animation: halo 2.4s ease-in-out infinite;
}
@keyframes halo {
  50% { filter: drop-shadow(0 0 14px rgb(243 245 251 / 0.95)); }
}
.lbl {
  position: absolute;
  right: 0;
  top: -16px;
  font-size: 12px;
  color: #9a94b4;
  font-variant-numeric: tabular-nums;
}
@media (prefers-reduced-motion: reduce) {
  .moonbar.full .moon, .fill, .moon { animation: none; transition: none; }
}
</style>
