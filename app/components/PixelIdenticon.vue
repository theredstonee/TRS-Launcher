<script setup lang="ts">
// Platzhalter-Bild im Pixel-Look: ein symmetrisches 6×6-Muster aus dem Namen,
// eingefärbt nach Modloader bzw. Inhaltsart. Stabil – derselbe Name ergibt
// immer dasselbe Muster.
const props = withDefaults(defineProps<{ seed: string; color?: string; letter?: string | null }>(), {
  color: 'var(--color-redstone-500)',
  letter: null,
})

function hash(text: string): number {
  // FNV-1a, reicht für ein Muster.
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

const cells = computed(() => {
  let h = hash(props.seed || '?')
  const out: { x: number; y: number; strong: boolean }[] = []
  for (let y = 0; y < 6; y++) {
    for (let x = 0; x < 3; x++) {
      const on = (h & 1) === 1
      const strong = (h & 2) === 2
      h = (h >>> 2) | ((h & 3) << 30)
      if (y * 3 + x === 7) h = hash(`${props.seed}#`)
      if (!on) continue
      out.push({ x: x + 1, y: y + 1, strong }, { x: 6 - x, y: y + 1, strong })
    }
  }
  return out
})
</script>

<template>
  <svg viewBox="0 0 8 8" class="block size-full" shape-rendering="crispEdges" aria-hidden="true">
    <rect width="8" height="8" fill="var(--color-base-800)" />
    <rect v-for="(c, i) in cells" :key="i" :x="c.x" :y="c.y" width="1" height="1" :fill="color" :opacity="c.strong ? 0.95 : 0.5" />
    <text
      v-if="letter"
      x="4"
      y="4.35"
      text-anchor="middle"
      dominant-baseline="middle"
      font-size="4.2"
      class="display"
      fill="#fff"
      style="paint-order: stroke; stroke: rgb(0 0 0 / 0.55); stroke-width: 0.55px"
    >{{ letter }}</text>
  </svg>
</template>
