<script setup lang="ts">
// Nacht-Szene der Startseite, solange Halloween global an ist.
// `background-final.png` wird mit Nearest-Neighbour hochskaliert (ganze Pixel).
// Fledermäuse ziehen durch, die Hexe fliegt ab und zu quer über den Himmel (6 Frames, 110 ms, Wippen ±2 px).
// Bei „Bewegung reduzieren“ nur das Standbild – keine Tiere, kein Flackern.

const BG = '/halloween/background-final.png'
const WITCH = '/halloween/witch-final.png'
const BG_W = 320
const BG_H = 180
const FRAME = 48
const FRAMES = 6
const FRAME_MS = 110
/** Ein Überflug, danach Pause. */
const PASS_MS = 6500
const GAP_MS = 12000

const root = useTemplateRef<HTMLDivElement>('root')
const pix = useTemplateRef<HTMLCanvasElement>('pix')

let bg: HTMLImageElement | null = null
let witch: HTMLImageElement | null = null
let reduced = false
let visible = true
let pageOn = true
let raf = 0
let disposed = false
let resize: ResizeObserver | undefined
let intersect: IntersectionObserver | undefined
let motion: MediaQueryList | undefined

function loadImage(src: string): Promise<HTMLImageElement | null> {
  return new Promise((resolve) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => resolve(null)
    img.src = src
  })
}

function draw(t: number) {
  const el = root.value
  const c = pix.value
  if (!el || !c) return
  const w = Math.max(1, Math.floor(el.clientWidth))
  const h = Math.max(1, Math.floor(el.clientHeight))
  if (c.width !== w || c.height !== h) {
    c.width = w
    c.height = h
  }
  const g = c.getContext('2d')
  if (!g) return
  g.imageSmoothingEnabled = false
  // Ein Hintergrund-Texel = ganze Bildschirm-Pixel, Bild füllt die Fläche (Cover).
  const texel = Math.max(1, Math.ceil(Math.max(w / BG_W, h / BG_H)))
  const dw = BG_W * texel
  const dh = BG_H * texel
  const dx = Math.floor((w - dw) / 2)
  const dy = Math.floor((h - dh) / 2)
  g.fillStyle = '#120a1c'
  g.fillRect(0, 0, w, h)
  if (bg && bg.naturalWidth) g.drawImage(bg, dx, dy, dw, dh)
  if (reduced) return

  const u = texel
  for (let i = 0; i < 5; i++) {
    const span = w + u * 40
    const x = Math.round((((t / 40) + i * (u * 28)) % span) - u * 16)
    const y = Math.round(h * 0.1 + i * u * 7 + Math.sin(t / 400 + i) * u * 2)
    const wing = (Math.floor(t / 120 + i) % 2) ? -u * 2 : u
    g.fillStyle = '#120a1c'
    g.fillRect(x, y, u * 6, u * 3)
    g.fillRect(x - u * 5, y + wing, u * 5, u * 2)
    g.fillRect(x + u * 6, y + wing, u * 5, u * 2)
  }

  const phase = t % (PASS_MS + GAP_MS)
  if (phase < PASS_MS && witch && witch.naturalWidth) {
    const p = phase / PASS_MS
    const sz = Math.max(u * 24, Math.round(Math.min(w * 0.18, h * 0.42) / u) * u)
    const x = Math.round((-sz + p * (w + sz)) / u) * u
    const bob = Math.sin((t / 1900) * Math.PI * 2) * 2 * u
    const y = Math.round((h * 0.14 + Math.sin(t / 500) * u * 3 + bob) / u) * u
    const frame = Math.floor(t / FRAME_MS) % FRAMES
    g.drawImage(witch, frame * FRAME, 0, FRAME, FRAME, x, y, sz, sz)
  }

  g.fillStyle = `rgba(255,122,26,${0.035 + 0.025 * Math.sin(t / 90)})`
  g.fillRect(0, 0, w, h)
}

function stop() {
  if (raf) cancelAnimationFrame(raf)
  raf = 0
}

function tick(t: number) {
  raf = 0
  draw(t)
  if (!reduced && visible && pageOn) raf = requestAnimationFrame(tick)
}

function start() {
  if (raf || reduced || !visible || !pageOn) {
    if (reduced) draw(0)
    return
  }
  raf = requestAnimationFrame(tick)
}

function onMotion() {
  reduced = !!motion?.matches
  if (reduced) {
    stop()
    draw(0)
  } else start()
}

function onPage() {
  pageOn = document.visibilityState !== 'hidden'
  if (pageOn) start()
  else stop()
}

onMounted(async () => {
  motion = window.matchMedia('(prefers-reduced-motion: reduce)')
  reduced = motion.matches
  motion.addEventListener('change', onMotion)
  const [a, b] = await Promise.all([loadImage(BG), loadImage(WITCH)])
  if (disposed) return
  bg = a
  witch = b
  resize = new ResizeObserver(() => {
    if (reduced) draw(0)
    else if (!raf) start()
  })
  if (root.value) resize.observe(root.value)
  intersect = new IntersectionObserver(([entry]) => {
    visible = !!entry?.isIntersecting
    if (visible) start()
    else stop()
  })
  if (root.value) intersect.observe(root.value)
  document.addEventListener('visibilitychange', onPage)
  start()
})

onBeforeUnmount(() => {
  disposed = true
  stop()
  resize?.disconnect()
  intersect?.disconnect()
  motion?.removeEventListener('change', onMotion)
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
  background: #120a1c;
  contain: strict;
}
.pix {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  image-rendering: pixelated;
}
</style>
