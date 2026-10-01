<script setup lang="ts">
import { HALLOWEEN_BACKGROUND, HALLOWEEN_WITCH } from '~/utils/halloween'

// Nacht-Startseite (Vertrag, Referenz `halloween2.js`): Pixel-Hintergrund,
// Fledermäuse, die Hexe fliegt ab und zu durch, Lampen-Flackern.
// Still, wenn die Einstellung „Animationen“ das will (wie die Redstone-Szene).
const canvas = useTemplateRef<HTMLCanvasElement>('canvas')
const { reduced } = useMotion()

const bg = new Image()
bg.src = HALLOWEEN_BACKGROUND
const witch = new Image()
witch.src = HALLOWEEN_WITCH

let raf = 0
let running = false

function paint(t: number) {
  const el = canvas.value
  const parent = el?.parentElement
  if (!el || !parent) return
  const w = parent.clientWidth
  const h = parent.clientHeight
  if (w < 2 || h < 2) return
  const dpr = Math.min(2, window.devicePixelRatio || 1)
  const bw = Math.round(w * dpr)
  const bh = Math.round(h * dpr)
  if (el.width !== bw || el.height !== bh) {
    el.width = bw
    el.height = bh
  }
  const ctx = el.getContext('2d')
  if (!ctx) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.imageSmoothingEnabled = false
  ctx.fillStyle = '#120a1c'
  ctx.fillRect(0, 0, w, h)
  if (bg.complete && bg.naturalWidth) {
    const scale = Math.max(w / bg.naturalWidth, h / bg.naturalHeight)
    const dw = Math.round(bg.naturalWidth * scale)
    const dh = Math.round(bg.naturalHeight * scale)
    ctx.drawImage(bg, Math.round((w - dw) / 2), Math.round((h - dh) / 2), dw, dh)
  }
  if (reduced.value) return

  // Wie die Referenz auf 960 px, auf größeren Bühnen mit ganzzahligem Faktor.
  const s = Math.max(1, Math.round(w / 960))
  for (let i = 0; i < 5; i++) {
    const x = ((t / 40 + i * 170) % (w + 80)) - 40
    const y = 28 + i * 18 * s + Math.sin(t / 400 + i) * 10
    const wing = Math.floor(t / 120 + i) % 2 ? 3 : -1
    ctx.fillStyle = '#120a1c'
    ctx.fillRect(x, y, 6 * s, 4 * s)
    ctx.fillRect(x - 6 * s, y - wing * s, 6 * s, 2 * s)
    ctx.fillRect(x + 6 * s, y - wing * s, 6 * s, 2 * s)
  }
  // Ein Durchflug, dann Pause – „ab und zu“, nicht dauernd.
  const cycle = 14000
  const flight = 7000
  const phase = t % cycle
  if (phase < flight && witch.complete && witch.naturalWidth) {
    const x = (phase / flight) * (w + 220) - 120
    const y = h * 0.16 + Math.sin(t / 500) * 14
    const frame = Math.floor(t / 110) % 6
    const size = 96 * s
    ctx.drawImage(witch, frame * 48, 0, 48, 48, Math.round(x), Math.round(y), size, size)
  }
  ctx.fillStyle = `rgba(255,120,30,${0.04 + 0.03 * Math.sin(t / 90)})`
  ctx.fillRect(0, 0, w, h)
}

function frame(t: number) {
  paint(t)
  if (running) raf = requestAnimationFrame(frame)
}

function start() {
  if (reduced.value) {
    paint(0)
    return
  }
  if (running) return
  running = true
  raf = requestAnimationFrame(frame)
}

function stop() {
  running = false
  cancelAnimationFrame(raf)
}

function onHide() {
  if (document.hidden) stop()
  else start()
}

let observer: ResizeObserver | undefined
onMounted(() => {
  const redraw = () => paint(performance.now())
  bg.addEventListener('load', redraw)
  witch.addEventListener('load', redraw)
  const parent = canvas.value?.parentElement
  if (parent) {
    observer = new ResizeObserver(redraw)
    observer.observe(parent)
  }
  document.addEventListener('visibilitychange', onHide)
  start()
})

onBeforeUnmount(() => {
  stop()
  observer?.disconnect()
  document.removeEventListener('visibilitychange', onHide)
})

watch(reduced, (on) => {
  if (on) {
    stop()
    paint(0)
  } else start()
})
</script>

<template>
  <div class="scene" aria-hidden="true">
    <canvas ref="canvas" class="pix" />
  </div>
</template>

<style scoped>
.scene {
  position: absolute;
  z-index: 0;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  background: #120a1c;
}
.pix {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  image-rendering: pixelated;
}
</style>
