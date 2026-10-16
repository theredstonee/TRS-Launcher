<script setup lang="ts">
// Startseiten-Szene zu Halloween 2026: Pixel-Mondnacht 320×180 (app/utils/halloween-scene.ts), ganzzahlig auf die
// Fläche skaliert (Cover). Blitze lösen `trs-bolt` aus (Seiten-Aufflackern, Handabdrücke – HalloweenOverlay).
// Bei „Bewegung reduzieren“ nur ein Standbild. `anchor` meldet die Bildschirm-Position zwischen den Gräbern
// (für den versteckten Kürbis `web-home`).
const emit = defineEmits<{ anchor: [pos: { left: number, top: number }] }>()

const W = 320
const H = 180
const root = useTemplateRef<HTMLDivElement>('root')
const pix = useTemplateRef<HTMLCanvasElement>('pix')
const hw = useHalloween()
const reduced = useReducedMotion()

let frame: ((t: number) => void) | null = null
let graveX = 0
let graveY = 0
let raf = 0
let visible = true
let pageOn = true
let t0 = 0
let resize: ResizeObserver | undefined
let intersect: IntersectionObserver | undefined

function place() {
  const el = root.value
  if (!el) return
  const w = el.clientWidth, h = el.clientHeight
  const k = Math.max(w / W, h / H)
  emit('anchor', { left: (w - W * k) / 2 + graveX * k, top: (h - H * k) / 2 + graveY * k })
}

function stop() {
  cancelAnimationFrame(raf)
  raf = 0
}

function tick(now: number) {
  raf = 0
  frame?.((now - t0) / 1000)
  if (!reduced.value && visible && pageOn) raf = requestAnimationFrame(tick)
}

function start() {
  if (!frame) return
  if (reduced.value) {
    stop()
    frame(4)
    return
  }
  if (!raf && visible && pageOn) raf = requestAnimationFrame(tick)
}

function onPage() {
  pageOn = document.visibilityState !== 'hidden'
  if (pageOn) start()
  else stop()
}

onMounted(() => {
  const c = pix.value
  if (!c) return
  c.width = W
  c.height = H
  const scene = makeScene(c, 2 * 13 + 5, {
    onBolt: () => window.dispatchEvent(new CustomEvent('trs-bolt')),
    flicker: () => hw.flicker.value,
  })
  frame = scene.frame
  // Zwischen dem ersten und zweiten Grab, knapp über dem Boden (links vom Download-Knopf).
  const g = scene.info.graves
  graveX = g.length >= 2 ? (g[0]!.x + g[1]!.x) / 2 : W * 0.53
  graveY = g.length >= 2 ? Math.max(g[0]!.y, g[1]!.y) + 4 : H * 0.8
  t0 = performance.now()
  frame(reduced.value ? 4 : 0)
  resize = new ResizeObserver(place)
  if (root.value) resize.observe(root.value)
  intersect = new IntersectionObserver(([e]) => {
    visible = !!e?.isIntersecting
    if (visible) start()
    else stop()
  })
  if (root.value) intersect.observe(root.value)
  document.addEventListener('visibilitychange', onPage)
  place()
  start()
})
watch(reduced, () => start())

onBeforeUnmount(() => {
  stop()
  resize?.disconnect()
  intersect?.disconnect()
  document.removeEventListener('visibilitychange', onPage)
})
</script>

<template>
  <div ref="root" class="scene" aria-hidden="true">
    <canvas ref="pix" class="pix" />
    <slot />
  </div>
</template>

<style scoped>
.scene {
  position: absolute;
  z-index: 0;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  background: #07060c;
  contain: strict;
}
.pix {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  image-rendering: pixelated;
}
</style>
