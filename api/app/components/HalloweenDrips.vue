<script setup lang="ts">
// Blut-Tropfen an der Oberkante eines Rahmens (Halloween 2026, Stufe „Horror“). Positionen fest je Instanz (fester
// Zufall, damit SSR und Hydration gleich bleiben). „Bewegung reduzieren“: stehende Tropfen.
import { rng } from '#shared/halloween-art'

const props = withDefaults(defineProps<{ count?: number, seed?: number }>(), { count: 14, seed: 7 })
const drops = computed(() => {
  const r = rng(props.seed)
  return Array.from({ length: props.count }, () => ({
    left: `${3 + r() * 94}%`,
    h: `${8 + r() * 34}px`,
    d: `${5 + r() * 9}s`,
    delay: `${-r() * 10}s`,
    w: `${2 + r() * 4}px`,
  }))
})
</script>

<template>
  <div class="hw-drips" aria-hidden="true">
    <span v-for="(d, i) in drops" :key="i" :style="{ 'left': d.left, 'width': d.w, '--h': d.h, '--d': d.d, 'animationDelay': d.delay }" />
  </div>
</template>

<style scoped>
.hw-drips {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 0;
  z-index: 7;
  pointer-events: none;
}
.hw-drips span {
  position: absolute;
  top: 0;
  border-radius: 0 0 3px 3px;
  background: linear-gradient(180deg, #3d070c, #7a0f18);
  animation: drip var(--d) ease-in infinite;
}
.hw-drips span::after {
  content: "";
  position: absolute;
  bottom: -4px;
  left: -1px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #7a0f18;
}
@keyframes drip {
  0% { height: 0; opacity: 1; }
  70% { height: var(--h); opacity: 1; }
  100% { height: calc(var(--h) + 6px); opacity: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .hw-drips span { animation: none; height: calc(var(--h) * 0.6); }
}
</style>
