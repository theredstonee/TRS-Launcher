<script setup lang="ts">
import { drawMoonPhase, MOON_SIZE, moonState, phaseKey } from '~/utils/moonnight/moon'

// Ladebalken „Mondlauf“ (Halloween 2026): ein kleiner Mond zieht über einen Silberfaden und
// nimmt dabei zu – Neumond bei 0 %, Vollmond bei 100 % (dann leuchtet er sanft nach).
// Unbestimmt gleitet er hin und her und durchläuft die Phasen. Ersetzt während Halloween
// jeden Leitungs-/Lampen-Balken. „Animationen aus“: Standbild.
const props = withDefaults(defineProps<{ percent?: number; indeterminate?: boolean; powered?: boolean }>(), {
  percent: 0,
  indeterminate: false,
  powered: false,
})

const root = useTemplateRef<HTMLDivElement>('root')
const moon = useTemplateRef<HTMLCanvasElement>('moon')
const { reduced } = useMotion()
const width = ref(0)
const compact = computed(() => width.value > 0 && width.value < 180)
const t0 = performance.now()
const pos = ref(0)
const fill = ref(0)
const full = ref(false)
let painted = -1
let raf = 0

function update(now: number) {
  const still = reduced.value
  const s = still && props.indeterminate && !props.powered
    ? { pos: 0.5, fill: 0, phase: 0.5, full: false }
    : moonState((now - t0) / 1000, props.percent, props.indeterminate, props.powered)
  pos.value = s.pos
  fill.value = s.fill
  full.value = s.full
  const key = phaseKey(s.phase)
  if (moon.value && key !== painted) {
    painted = key
    drawMoonPhase(moon.value, key / 24)
  }
}

function loop(now: number) {
  update(now)
  raf = requestAnimationFrame(loop)
}

function sync() {
  cancelAnimationFrame(raf)
  raf = 0
  update(performance.now())
  if (props.indeterminate && !props.powered && !reduced.value && !document.hidden) raf = requestAnimationFrame(loop)
}

let observer: ResizeObserver | undefined
onMounted(() => {
  if (root.value) {
    observer = new ResizeObserver(() => { width.value = root.value?.clientWidth ?? 0 })
    observer.observe(root.value)
    width.value = root.value.clientWidth
  }
  document.addEventListener('visibilitychange', sync)
  sync()
})
onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  observer?.disconnect()
  document.removeEventListener('visibilitychange', sync)
})
watch(() => [props.percent, props.indeterminate, props.powered, reduced.value], sync)
</script>

<template>
  <div ref="root" class="moonbar" :class="{ full: full && !reduced, compact, indet: indeterminate && !powered }" aria-hidden="true">
    <div class="rail" />
    <div v-if="!indeterminate || powered" class="fill" :style="{ width: `${fill * 100}%` }" />
    <canvas ref="moon" class="moon" :width="MOON_SIZE" :height="MOON_SIZE" :style="{ left: `${pos * 100}%` }" />
  </div>
</template>

<style scoped>
.moonbar {
  --moon: 28px;
  position: relative;
  height: var(--moon);
  /* Der Mond darf an beiden Enden halb über den Faden hinausragen. */
  margin-inline: calc(var(--moon) / 2);
  width: calc(100% - var(--moon));
}
.moonbar.compact {
  --moon: 16px;
}
.rail {
  position: absolute;
  left: 0;
  right: 0;
  top: calc(var(--moon) / 2 - 1px);
  height: 2px;
  border-radius: 2px;
  background: rgba(207, 213, 227, 0.14);
}
.rail::before {
  content: "";
  position: absolute;
  inset: -5px 0;
  background: repeating-linear-gradient(90deg, rgba(185, 162, 234, 0.08) 0 2px, transparent 2px 6px);
}
.compact .rail::before {
  inset: -3px 0;
}
.fill {
  position: absolute;
  left: 0;
  top: calc(var(--moon) / 2 - 2px);
  height: 4px;
  border-radius: 4px;
  background: linear-gradient(90deg, rgba(142, 108, 201, 0.05), rgba(185, 162, 234, 0.55) 60%, #f3f5fb);
  box-shadow: 0 0 12px rgba(207, 213, 227, 0.45);
  transition: width 0.3s ease;
}
.moon {
  position: absolute;
  top: 0;
  width: var(--moon);
  height: var(--moon);
  margin-left: calc(var(--moon) / -2);
  image-rendering: pixelated;
  filter: drop-shadow(0 0 6px rgba(243, 245, 251, 0.55));
  transition: left 0.3s ease;
}
.indet .moon {
  transition: none;
}
.full .moon {
  animation: moon-halo 2.4s ease-in-out infinite;
}
@keyframes moon-halo {
  50% {
    filter: drop-shadow(0 0 14px rgba(243, 245, 251, 0.95));
  }
}
:root[data-reduced-motion] .fill,
:root[data-reduced-motion] .moon {
  transition: none;
}
</style>
