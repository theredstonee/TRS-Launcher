<script setup lang="ts">
// Nur in `nuxt dev` (siehe nuxt.config.ts): Hintergrund-Szenen der Designs im normalen Browser, ohne Tauri.
// /design-preview?design=fox|winter&theme=dark|oled|light&accent=…&view=page|hero|zoom|ui&mode=idle|starting|running
import { applyDesign } from '~/utils/design'
import { buildFoxLandscape, denParts } from '~/utils/fox/landscape'
import { FoxPainter } from '~/utils/fox/paint'
import { documentFoxPalette } from '~/utils/fox/palette'
import { Leaves } from '~/utils/fox/particles'
import { foxBreath } from '~/utils/fox/sleep'
import { rng } from '~/utils/redstone/scene'

definePageMeta({ layout: false })

const route = useRoute()
const q = (k: string, d: string) => String(route.query[k] ?? d)
const view = q('view', 'page')
const mode = q('mode', 'idle') as 'idle' | 'starting' | 'running'
const lamp = useTemplateRef<HTMLElement>('lamp')
const zoom = useTemplateRef<HTMLCanvasElement>('zoom')

const root = document.documentElement
const theme = q('theme', 'dark')
if (theme === 'dark') delete root.dataset.theme
else root.dataset.theme = theme
const accent = q('accent', 'fox')
if (accent === 'redstone') delete root.dataset.accent
else root.dataset.accent = accent
applyDesign(q('design', 'fox'))

onMounted(() => {
  if (view !== 'zoom' || !zoom.value) return
  // Nahaufnahme: Bau mit Feuer und Fuchs, 6-fach vergrößert.
  const land = buildFoxLandscape({ cols: 420, rows: 240, seed: 0xf0c5, denX: 0.62 })
  const P = documentFoxPalette()
  const painter = new FoxPainter(land, P)
  const src = document.createElement('canvas')
  src.width = land.w
  src.height = land.h
  const g = src.getContext('2d')!
  const leaves = new Leaves(land.w, land.h, rng(3), 1)
  painter.frame(g, { tick: 40, seconds: Number(q('t', '0.5')), lit: 0, fire: 0.6, calm: false, fox: foxBreath(Number(q('t', '0.5'))), smoke: [], sparks: [], leaves: leaves.leaves })
  const p = denParts(land.den!)
  const x0 = p.fire.x - 30
  const y0 = p.fire.y - 70
  const W = 140
  const H = 80
  const k = 6
  const out = zoom.value
  out.width = W * k
  out.height = H * k
  const o = out.getContext('2d')!
  o.imageSmoothingEnabled = false
  o.drawImage(src, x0, y0, W, H, 0, 0, W * k, H * k)
})
</script>

<template>
  <div class="fixed inset-0 bg-base-950">
    <DesignScene v-if="view === 'page'" fill />
    <section v-else-if="view === 'hero'" class="relative isolate m-6 h-[22rem] overflow-hidden rounded-xl">
      <DesignScene :mode="mode" :progress="55" :anchor="lamp" />
      <div ref="lamp" class="absolute right-24 bottom-8 h-14 w-64 rounded bg-redstone-500/80" />
    </section>
    <div v-else-if="view === 'ui'" class="flex max-w-xl flex-col gap-5 p-8 text-sm text-base-200">
      <div class="flex items-center gap-2"><img src="/icon.png" alt="" class="size-4 [image-rendering:pixelated]" /><PixelFoxMark /><span class="display">TRS Launcher</span></div>
      <div class="flex items-center gap-4"><PixelFoxMark class="!h-[70px] !w-[90px]" /></div>
      <p>35 %</p>
      <RedstoneWire :percent="35" />
      <p>80 %</p>
      <RedstoneWire :percent="80" />
      <p>läuft</p>
      <RedstoneWire powered />
      <p>unbestimmt</p>
      <RedstoneWire indeterminate />
      <div class="h-40"><UpdateBanner kicker="UPDATE 0.30.0 · 25.10.2026" title="Fox Den" accent="#e8702a" /></div>
    </div>
    <canvas v-else ref="zoom" class="[image-rendering:pixelated]" />
  </div>
</template>
