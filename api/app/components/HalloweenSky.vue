<script setup lang="ts">
// Hexe und Fledermäuse über jeder Seite, solange die Klasse theme-halloween am <html> steht.
// Eine feste Fläche ohne Trefferfläche, unter Kopfzeile (z-30), Menüs und Dialogen.
// requestAnimationFrame nur, solange etwas fliegt; sonst ein Timer. Verstecktes Tab und
// „Bewegung reduzieren“ halten alles an – dann bleiben nur die ruhigen Zeichen aus dem CSS.

import {
  BAT_EVERY_MS,
  WITCH_EVERY_MS,
  WITCH_SRC,
  batPose,
  broomTip,
  flyAllowed,
  gapMs,
  makeSpark,
  planSwarm,
  planWitch,
  sparkAlpha,
  stepSpark,
  witchFrameRect,
  witchPose,
  type Spark,
  type SwarmPlan,
  type WitchPlan,
} from '~/utils/halloween-sky'

const SPARK_EVERY = 55
const SPARK_CAP = 24

const pix = useTemplateRef<HTMLCanvasElement>('pix')

let witch: (WitchPlan & { start: number }) | null = null
let swarm: (SwarmPlan & { start: number }) | null = null
let sparks: Spark[] = []
let nextSpark = 0
let sprite: HTMLImageElement | null = null
let clock = 0
let lastNow = 0
let raf = 0
let timer = 0
let reduced = false
let hidden = false
let disposed = false
let witchDue = 0
let batDue = 0
let witchLeft = 0
let batLeft = 0
let motion: MediaQueryList | undefined

const BAT_UP = [
  '  ++   ++  ',
  ' ++++ ++++ ',
  '  ######*  ',
  '   #####   ',
  '    ###    ',
]
const BAT_DOWN = [
  '    ###    ',
  '   #####   ',
  ' ++ ### ++ ',
  '++++ #*++++',
  ' +  ###  + ',
]

function loadSprite() {
  const img = new Image()
  img.onload = () => {
    if (!disposed) sprite = img
  }
  img.src = WITCH_SRC
}

function clearSky() {
  const c = pix.value
  const g = c?.getContext('2d')
  if (c && g) g.clearRect(0, 0, c.width, c.height)
}

function stopLoop() {
  if (raf) cancelAnimationFrame(raf)
  raf = 0
  lastNow = 0
}

function haltFlight() {
  stopLoop()
  window.clearTimeout(timer)
  timer = 0
  witch = null
  swarm = null
  sparks = []
  witchDue = 0
  batDue = 0
  witchLeft = 0
  batLeft = 0
  clearSky()
}

function viewSize(): { w: number, h: number } {
  const c = pix.value
  return {
    w: Math.max(1, Math.floor(c?.clientWidth || window.innerWidth)),
    h: Math.max(1, Math.floor(c?.clientHeight || window.innerHeight)),
  }
}

function fit() {
  const c = pix.value
  if (!c) return
  const w = Math.max(1, Math.floor(c.clientWidth))
  const h = Math.max(1, Math.floor(c.clientHeight))
  if (c.width !== w || c.height !== h) {
    c.width = w
    c.height = h
  }
}

function queue() {
  window.clearTimeout(timer)
  timer = 0
  if (!flyAllowed({ reduced, hidden })) return
  const now = performance.now()
  const waits: number[] = []
  if (!witch && witchDue) waits.push(witchDue - now)
  if (!swarm && batDue) waits.push(batDue - now)
  if (!waits.length) return
  timer = window.setTimeout(launchDue, Math.max(0, Math.min(...waits)))
}

function scheduleWitch(ms: number) {
  witchLeft = ms
  witchDue = performance.now() + ms
  queue()
}

function scheduleBats(ms: number) {
  batLeft = ms
  batDue = performance.now() + ms
  queue()
}

function launchDue() {
  timer = 0
  if (!flyAllowed({ reduced, hidden })) return
  const now = performance.now()
  const view = viewSize()
  if (!witch && witchDue && now >= witchDue - 20) {
    witchDue = 0
    witchLeft = 0
    nextSpark = clock
    witch = { ...planWitch(view), start: clock }
  }
  if (!swarm && batDue && now >= batDue - 20) {
    batDue = 0
    batLeft = 0
    swarm = { ...planSwarm(), start: clock }
  }
  ensureLoop()
  queue()
}

function ensureLoop() {
  if (raf || !flyAllowed({ reduced, hidden })) return
  if (!witch && !swarm && sparks.length === 0) return
  lastNow = 0
  raf = requestAnimationFrame(frame)
}

function drawBat(g: CanvasRenderingContext2D, x: number, y: number, size: number, wing: 0 | 1) {
  const rows = wing ? BAT_DOWN : BAT_UP
  const x0 = Math.round(x)
  const y0 = Math.round(y)
  for (let r = 0; r < rows.length; r++) {
    const row = rows[r]!
    for (let c = 0; c < row.length; c++) {
      const ch = row[c]
      if (!ch || ch === ' ') continue
      g.fillStyle = ch === '*' ? '#ff7a1a' : ch === '+' ? '#8b5cbc' : '#3a2460'
      g.fillRect(x0 + c * size, y0 + r * size, size, size)
    }
  }
}

function draw() {
  const c = pix.value
  if (!c) return
  fit()
  const g = c.getContext('2d')
  if (!g) return
  g.imageSmoothingEnabled = false
  g.clearRect(0, 0, c.width, c.height)
  const view = { w: c.width, h: c.height }

  if (swarm) {
    for (const bat of swarm.bats) {
      const pose = batPose(swarm, bat, clock - swarm.start, view)
      drawBat(g, pose.x, pose.y, pose.size, pose.wing)
    }
  }

  for (const spark of sparks) {
    const alpha = sparkAlpha(spark, clock)
    if (alpha <= 0) continue
    g.globalAlpha = alpha
    g.fillStyle = spark.color
    g.fillRect(Math.round(spark.x), Math.round(spark.y), 2, 2)
  }
  g.globalAlpha = 1

  if (witch && sprite && sprite.naturalWidth) {
    const pose = witchPose(witch, clock - witch.start, view.w)
    const src = witchFrameRect(pose.frame)
    const x = Math.round(pose.x)
    const y = Math.round(pose.y)
    if (witch.dir === -1) {
      g.save()
      g.translate(x + pose.sz, y)
      g.scale(-1, 1)
      g.drawImage(sprite, src.sx, src.sy, src.sw, src.sh, 0, 0, pose.sz, pose.sz)
      g.restore()
    } else {
      g.drawImage(sprite, src.sx, src.sy, src.sw, src.sh, x, y, pose.sz, pose.sz)
    }
  }
}

function frame(now: number) {
  raf = 0
  if (!flyAllowed({ reduced, hidden }) || disposed) return
  if (!lastNow) lastNow = now
  const dt = Math.min(48, now - lastNow)
  lastNow = now
  clock += dt

  if (witch) {
    const elapsed = clock - witch.start
    const pose = witchPose(witch, elapsed, viewSize().w)
    if (elapsed > witch.duration) {
      witch = null
      scheduleWitch(gapMs(WITCH_EVERY_MS))
    } else if (clock >= nextSpark) {
      nextSpark = clock + SPARK_EVERY
      sparks.push(makeSpark(broomTip(pose.x, pose.y, pose.sz, witch.dir), witch.dir, clock))
      if (sparks.length > SPARK_CAP) sparks.splice(0, sparks.length - SPARK_CAP)
    }
  }
  if (swarm && clock - swarm.start > swarm.duration) {
    swarm = null
    scheduleBats(gapMs(BAT_EVERY_MS))
  }
  for (let i = sparks.length - 1; i >= 0; i--) {
    const spark = sparks[i]!
    stepSpark(spark, dt)
    if (sparkAlpha(spark, clock) <= 0) sparks.splice(i, 1)
  }

  draw()
  if (witch || swarm || sparks.length) raf = requestAnimationFrame(frame)
  else {
    lastNow = 0
    clearSky()
  }
}

function pauseForHide() {
  const now = performance.now()
  if (witchDue) witchLeft = Math.max(0, witchDue - now)
  if (batDue) batLeft = Math.max(0, batDue - now)
  witchDue = 0
  batDue = 0
  window.clearTimeout(timer)
  timer = 0
  stopLoop()
}

function resumeFromHide() {
  const now = performance.now()
  if (witchLeft && !witch) witchDue = now + witchLeft
  if (batLeft && !swarm) batDue = now + batLeft
  queue()
  ensureLoop()
}

function onMotion() {
  reduced = !!motion?.matches
  if (reduced) haltFlight()
  else if (!hidden) {
    if (!sprite) loadSprite()
    scheduleWitch(gapMs(WITCH_EVERY_MS))
    scheduleBats(gapMs(BAT_EVERY_MS))
  }
}

function onVis() {
  const was = hidden
  hidden = document.visibilityState === 'hidden'
  if (hidden && !was) pauseForHide()
  else if (!hidden && was && !reduced) resumeFromHide()
}

function onResize() {
  fit()
  if (!raf) clearSky()
}

onMounted(() => {
  motion = window.matchMedia('(prefers-reduced-motion: reduce)')
  reduced = motion.matches
  hidden = document.visibilityState === 'hidden'
  motion.addEventListener('change', onMotion)
  document.addEventListener('visibilitychange', onVis)
  window.addEventListener('resize', onResize)
  fit()
  if (reduced) return
  loadSprite()
  const witchMs = gapMs(WITCH_EVERY_MS)
  const batMs = gapMs(BAT_EVERY_MS)
  if (hidden) {
    witchLeft = witchMs
    batLeft = batMs
  } else {
    scheduleWitch(witchMs)
    scheduleBats(batMs)
  }
})

onBeforeUnmount(() => {
  disposed = true
  haltFlight()
  motion?.removeEventListener('change', onMotion)
  document.removeEventListener('visibilitychange', onVis)
  window.removeEventListener('resize', onResize)
})
</script>

<template>
  <canvas ref="pix" class="hw-sky" aria-hidden="true" />
</template>

<style scoped>
/* Unter der Kopfzeile (z-30), den Menüs darin und den Dialogen (z-50 und höher). */
.hw-sky {
  position: fixed;
  z-index: 24;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  image-rendering: pixelated;
}
</style>
