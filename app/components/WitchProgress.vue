<script setup lang="ts">
import { HALLOWEEN_WITCH, witchMotion } from '~/utils/halloween'

// Hexen-Ladebalken (Vertrag „Ladebalken“, Referenz `trs-studio/public/halloween.js`):
// Deepslate-Rahmen, Redstone-Staub, Fackel links, Lampe rechts bei 100 %,
// Hexe auf der Füllkante, Funken hinter dem Besen. Unbestimmt: sie fliegt hin und her.
const props = withDefaults(defineProps<{ percent?: number; indeterminate?: boolean; powered?: boolean }>(), {
  percent: 0,
  indeterminate: false,
  powered: false,
})

const canvas = useTemplateRef<HTMLCanvasElement>('canvas')
const { reduced } = useMotion()
const witch = new Image()
witch.src = HALLOWEEN_WITCH

type Spark = { x: number; y: number; vx: number; vy: number; life: number }
const sparks: Spark[] = []
let raf = 0
let running = false

const VW = 760
const VH = 130

function paint(now: number) {
  const el = canvas.value
  if (!el) return
  const cssW = el.clientWidth || 280
  const cssH = el.clientHeight || 36
  const dpr = Math.min(2, window.devicePixelRatio || 1)
  const bw = Math.max(1, Math.round(cssW * dpr))
  const bh = Math.max(1, Math.round(cssH * dpr))
  if (el.width !== bw || el.height !== bh) {
    el.width = bw
    el.height = bh
  }
  const ctx = el.getContext('2d')
  if (!ctx) return
  const motion = witchMotion(now, props.percent, props.indeterminate, props.powered)
  const still = reduced.value
  const p = still && props.indeterminate && !props.powered ? 0.5 : motion.p
  const bob = still ? 0 : motion.bob
  const frame = still ? 0 : motion.frame

  ctx.setTransform(bw / VW, 0, 0, bh / VH, 0, 0)
  ctx.imageSmoothingEnabled = false
  ctx.clearRect(0, 0, VW, VH)

  const x0 = 56
  const w = VW - 120
  const y = VH - 34
  ctx.fillStyle = '#3a3a44'
  ctx.fillRect(x0 - 6, y - 6, w + 12, 24)
  ctx.fillStyle = '#24242b'
  ctx.fillRect(x0 - 4, y - 4, w + 8, 20)
  for (let x = 0; x < w; x += 4) {
    const on = x < w * p
    ctx.fillStyle = on ? '#ff2a1a' : '#4a0d0a'
    ctx.fillRect(x0 + x, y + 4, 3, 4)
    if (on) {
      ctx.fillStyle = 'rgba(255,60,30,.25)'
      ctx.fillRect(x0 + x - 1, y + 2, 5, 8)
    }
  }
  ctx.fillStyle = '#6b4a2a'
  ctx.fillRect(x0 - 30, y - 2, 4, 14)
  ctx.fillStyle = '#ff3b1f'
  ctx.fillRect(x0 - 31, y - 6, 6, 5)
  const lit = motion.lamp
  ctx.fillStyle = lit ? '#ffd27a' : '#5a3a22'
  ctx.fillRect(x0 + w + 14, y - 8, 24, 24)
  ctx.fillStyle = lit ? '#fff2c0' : '#3a2416'
  ctx.fillRect(x0 + w + 18, y - 4, 16, 16)

  const wx = x0 + w * p - 70
  const wy = y - 92 + bob
  if (!still) {
    if (Math.random() < 0.6) sparks.push({ x: wx + 16, y: wy + 82, vx: -0.6 - Math.random() * 1.5, vy: Math.random() * 1.2 - 0.8, life: 1 })
    for (const q of sparks) {
      q.x += q.vx
      q.y += q.vy
      q.vy += 0.04
      q.life -= 0.025
      ctx.fillStyle = `rgba(${220 + (Math.random() * 35) | 0},${20 + (Math.random() * 40) | 0},20,${Math.max(0, q.life)})`
      ctx.fillRect(Math.round(q.x / 2) * 2, Math.round(q.y / 2) * 2, 2, 2)
    }
    while (sparks.length && sparks[0]!.life <= 0) sparks.shift()
    if (sparks.length > 80) sparks.splice(0, sparks.length - 80)
  }
  if (witch.complete && witch.naturalWidth) ctx.drawImage(witch, frame * 48, 0, 48, 48, wx, wy, 96, 96)
}

function frame(now: number) {
  paint(now)
  if (running) raf = requestAnimationFrame(frame)
}

function start() {
  if (running || reduced.value) {
    paint(performance.now())
    return
  }
  running = true
  raf = requestAnimationFrame(frame)
}

function stop() {
  running = false
  cancelAnimationFrame(raf)
}

function fit() {
  const el = canvas.value
  if (!el) return
  const w = el.clientWidth
  el.style.height = `${w < 80 ? 14 : w < 180 ? 28 : 40}px`
  paint(performance.now())
}

let observer: ResizeObserver | undefined
function onHide() {
  if (document.hidden) stop()
  else start()
}

onMounted(() => {
  witch.addEventListener('load', () => paint(performance.now()))
  if (canvas.value) {
    observer = new ResizeObserver(fit)
    observer.observe(canvas.value)
  }
  document.addEventListener('visibilitychange', onHide)
  fit()
  start()
})

onBeforeUnmount(() => {
  stop()
  observer?.disconnect()
  document.removeEventListener('visibilitychange', onHide)
})

watch(() => [props.percent, props.indeterminate, props.powered, reduced.value], () => {
  if (reduced.value) {
    stop()
    sparks.length = 0
    paint(performance.now())
  } else start()
})
</script>

<template>
  <canvas ref="canvas" class="witch-progress" aria-hidden="true" />
</template>

<style scoped>
.witch-progress {
  display: block;
  width: 100%;
  height: 36px;
  image-rendering: pixelated;
}
</style>
