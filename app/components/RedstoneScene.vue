<script setup lang="ts">
import { documentPalette, type Palette } from '~/utils/redstone/palette'
import { ITEM_COLORS, TEX, Sprites, noise, noteColor, paintFloor, paintFrame, paintGlow, type Particle } from '~/utils/redstone/paint'
import { buildScene, pickModule, placements, rng, stencilFor, type Placement } from '~/utils/redstone/scene'
import { DX, DY, floorCell, type Cell, type Circuit } from '~/utils/redstone/sim'

// Lebendiger Hintergrund der Startseite: eine Redstone-Schaltung von oben –
// Takte, Lauflicht, Kolben, flackernde Fackeln. Die Hauptleitung läuft zur
// Spielen-Lampe (`anchor`) und lädt sich beim Spielstart mit dem Fortschritt auf.
//
// Sparsam: gezeichnet wird nur im Redstone-Takt (10 Bilder/s, wie im Spiel)
// auf einer Fläche von ein paar hundert Texeln; Hochskalieren macht CSS. Steht
// still, wenn das Fenster verdeckt oder die Szene aus dem Bild gescrollt ist,
// und zeigt bei „Bewegung reduzieren“ nur ein ruhiges Standbild.
const props = withDefaults(
  defineProps<{
    mode?: 'idle' | 'starting' | 'running'
    /** Gesamtfortschritt beim Start, 0–100. */
    progress?: number
    /** Element, unter dem die Hauptleitung endet (der Spielen-Knopf). */
    anchor?: HTMLElement | null
    /** Ganze Fläche füllen (Seiten-Hintergrund) statt eines Streifens über der Leitung. */
    fill?: boolean
    /** Andere Szene (z. B. für kleine Leer-Zustände). */
    seed?: number
  }>(),
  { mode: 'idle', progress: 0, anchor: null, fill: false, seed: 0x7e5 },
)

const root = useTemplateRef<HTMLDivElement>('root')
const pix = useTemplateRef<HTMLCanvasElement>('pix')
const glowCanvas = useTemplateRef<HTMLCanvasElement>('glow')
const box = ref({ width: 0, height: 0, top: 0 })

let circuit: Circuit | null = null
let palette: Palette | null = null
let sprites: Sprites | null = null
let floor: HTMLCanvasElement | OffscreenCanvas | null = null
let busEnd = 0
let layoutKey = ''
let gameTick = 0
let particles: Particle[] = []
let timer: ReturnType<typeof setInterval> | undefined
let visible = true
let reduced = false

/** Ein Redstone-Tick – mehr Bilder braucht die Pixel-Szene nicht. */
const TICK_MS = 100

// Ereignisse (Kettenreaktion, TNT-Funke, Nacht) und der langsame Umbau kommen
// alle paar Minuten – in Ticks gerechnet, damit sie mit der Szene pausieren.
const EVENT_MIN = 1500 // 2,5 min
const EVENT_SPAN = 1500
const REBUILD_MIN = 1800 // 3 min
const REBUILD_SPAN = 1800
const NIGHT_TICKS = 140
const chance = rng(Date.now() & 0xffffff)
let nextEvent = EVENT_MIN + Math.floor(chance() * EVENT_SPAN)
let nextRebuild = REBUILD_MIN + Math.floor(chance() * REBUILD_SPAN)
let nightUntil = 0

/** Laufender Umbau: erst Blöcke abbauen, dann die neue Schaltung Block für Block setzen. */
let rebuild: { spot: Placement; remove: number[]; add: { i: number; cell: Cell }[] } | null = null

function applyMode() {
  if (!circuit) return
  circuit.setExternal(props.mode !== 'idle')
  circuit.busLimit =
    props.mode === 'starting' ? Math.max(1, Math.round((busEnd * Math.min(100, props.progress)) / 100)) : Number.POSITIVE_INFINITY
}

function layout() {
  const el = root.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  if (rect.width < 1 || rect.height < 1) return
  const dpr = window.devicePixelRatio || 1
  // Ganze Gerätepixel je Texel – sonst werden die Pixel ungleich breit.
  const texelDev = Math.max(2, Math.round(3 * dpr))
  const block = (texelDev / dpr) * TEX

  let busRow = -1
  let offsetY = 0
  let end = 0
  const anchor = props.anchor
  if (anchor) {
    const a = anchor.getBoundingClientRect()
    const cy = a.top + a.height / 2 - rect.top
    busRow = Math.floor(cy / block)
    offsetY = cy - (busRow + 0.5) * block
    if (offsetY > 0) {
      busRow += 1
      offsetY -= block
    }
    end = Math.max(0, Math.ceil((a.left - rect.left) / block))
  }
  const cols = Math.ceil(rect.width / block)
  const rows = Math.ceil((rect.height - offsetY) / block)
  box.value = { width: cols * block, height: rows * block, top: offsetY }

  const key = `${cols}x${rows}:${busRow}:${end}`
  if (key === layoutKey && circuit) return
  layoutKey = key
  busEnd = end
  rebuild = null
  circuit = buildScene({ cols, rows, busRow, busEnd: end, seed: props.seed, fill: props.fill })
  particles = []
  applyMode()
  // Etwas vorlaufen lassen, damit die Takte nicht alle im Gleichschritt starten.
  for (let i = 0; i < 7; i++) circuit.step()
  const c = pix.value
  const g = glowCanvas.value
  if (c && g) {
    c.width = cols * TEX
    c.height = rows * TEX
    g.width = cols * 4
    g.height = rows * 4
  }
  repaintStatic()
}

function repaintStatic() {
  if (!circuit) return
  palette = documentPalette()
  sprites = new Sprites(palette)
  floor = paintFloor(circuit, palette)
  if (reduced) settle()
  draw(true)
}

/** Standbild bei reduzierter Bewegung: Zustand einschwingen lassen, dann einmal zeichnen. */
function settle() {
  if (!circuit) return
  for (let i = 0; i < 40; i++) circuit.step()
}

function draw(withGlow: boolean) {
  const c = pix.value?.getContext('2d')
  if (!c || !circuit || !floor || !sprites || !palette) return
  paintFrame(c, floor, circuit, sprites, palette, particles, (i) => noise(i, gameTick, 71) < 0.1)
  if (withGlow) {
    const g = glowCanvas.value?.getContext('2d')
    if (g) paintGlow(g, circuit, palette)
  }
}

/** Funken über geladenem Staub und brennenden Fackeln, wie im Spiel. */
function spawnParticles() {
  if (!circuit) return
  const { w } = circuit
  for (let i = 0; i < circuit.cells.length && particles.length < 70; i++) {
    const c = circuit.cells[i]!
    let chance = 0
    if (c.kind === 'dust' && c.power >= 8) chance = 0.024 * (c.power / 15)
    else if ((c.kind === 'torch' || c.kind === 'wallTorch') && c.on) chance = 0.24
    else if (c.kind === 'lamp' && c.on) chance = 0.02
    if (!chance || Math.random() > chance) continue
    const max = 5 + Math.floor(Math.random() * 6)
    particles.push({
      x: (i % w) * TEX + 4 + Math.random() * 8,
      y: ((i / w) | 0) * TEX + 4 + Math.random() * 8,
      vx: (Math.random() - 0.5) * 0.5,
      vy: -0.25 - Math.random() * 0.35,
      life: max,
      max,
      power: c.kind === 'dust' ? c.power : 15,
    })
  }
}

/** Noten, ausgeworfene Items und Funken aus der Simulation. */
function eventParticles() {
  if (!circuit) return
  for (const e of circuit.events) {
    const cx = e.x * TEX + 6
    const cy = e.y * TEX + 4
    if (e.type === 'note') {
      particles.push({ x: cx, y: cy, vx: (Math.random() - 0.5) * 0.3, vy: -0.6, life: 14, max: 14, power: 15, color: noteColor(e.pitch ?? 0), shape: 'note' })
    } else if (e.type === 'item') {
      const d = e.dir ?? 1
      particles.push({
        x: e.x * TEX + 7 + DX[d] * 9,
        y: e.y * TEX + 7 + DY[d] * 9,
        vx: DX[d] * (1.2 + Math.random()) + (Math.random() - 0.5) * 0.4,
        vy: DY[d] * (1.2 + Math.random()) - 0.8,
        life: 16,
        max: 16,
        power: 15,
        color: ITEM_COLORS[Math.floor(Math.random() * ITEM_COLORS.length)],
        shape: 'item',
        gravity: 0.12,
      })
    } else {
      for (let k = 0; k < 3; k++) {
        particles.push({ x: cx + Math.random() * 6, y: cy, vx: (Math.random() - 0.5) * 1.2, vy: -0.5 - Math.random(), life: 8, max: 8, power: 15, color: k ? '#ffd24a' : '#ffffff', gravity: 0.05 })
      }
    }
  }
  circuit.events.length = 0
}

/** Alle paar Minuten etwas Besonderes: Kettenreaktion, TNT-Funke oder Nacht. */
function maybeEvent() {
  if (!circuit) return
  if (circuit.night && gameTick >= nightUntil) circuit.night = false
  if (gameTick < nextEvent) return
  nextEvent = gameTick + EVENT_MIN + Math.floor(chance() * EVENT_SPAN)
  const tnts: number[] = []
  circuit.cells.forEach((c, i) => c.kind === 'tnt' && tnts.push(i))
  const options = ['flash', 'night', ...(tnts.length ? ['tnt'] : [])]
  const pick = options[Math.floor(chance() * options.length)]
  if (pick === 'flash') circuit.flashCol = 0
  else if (pick === 'night') {
    circuit.night = true
    nightUntil = gameTick + NIGHT_TICKS
  } else {
    const i = tnts[Math.floor(chance() * tnts.length)]!
    circuit.prime(i % circuit.w, (i / circuit.w) | 0, 50)
  }
}

/** Langsamer Umbau: eine Schaltung zerfällt Block für Block, eine neue entsteht. */
function stepRebuild() {
  if (!circuit) return
  if (!rebuild) {
    if (gameTick < nextRebuild) return
    nextRebuild = gameTick + REBUILD_MIN + Math.floor(chance() * REBUILD_SPAN)
    const list = placements.get(circuit) ?? []
    if (!list.length) return
    const spot = list[Math.floor(chance() * list.length)]!
    const remove: number[] = []
    for (let y = spot.y; y < spot.y + spot.h; y++)
      for (let x = spot.x; x < spot.x + spot.w; x++) {
        const c = circuit.at(x, y)
        if (c && c.kind !== 'floor') remove.push(y * circuit.w + x)
      }
    remove.sort(() => chance() - 0.5)
    const used = new Set(list.map((p) => p.name))
    const m = pickModule(chance, spot.w, spot.h, spot.name, used) ?? pickModule(chance, spot.w, spot.h, '', used)
    const add: { i: number; cell: Cell }[] = []
    if (m) {
      const cells = stencilFor(m, chance)
      const oy = spot.y + Math.floor(chance() * (spot.h - cells.length + 1))
      const ox = spot.x + Math.floor(chance() * (spot.w - cells[0]!.length + 1))
      cells.forEach((row, dy) =>
        row.forEach((cell, dx) => {
          if (cell.kind !== 'floor') add.push({ i: (oy + dy) * circuit!.w + ox + dx, cell })
        }),
      )
      // Uhren und Fackeln zuletzt – erst wenn die Leitung steht, geht es los.
      add.sort((a, b) => Number(a.cell.kind === 'hopper' || a.cell.kind === 'torch') - Number(b.cell.kind === 'hopper' || b.cell.kind === 'torch'))
      spot.name = m.name
    }
    rebuild = { spot, remove, add }
    return
  }
  // Etwa zwei Blöcke pro Sekunde abbauen, dann aufbauen.
  if (gameTick % 5 !== 0) return
  const w = circuit.w
  const next = rebuild.remove.shift()
  if (next !== undefined) {
    const x = next % w
    const y = (next / w) | 0
    const old = circuit.cells[next]!
    circuit.set(x, y, floorCell())
    for (let k = 0; k < 5; k++) {
      particles.push({ x: x * TEX + 4 + Math.random() * 8, y: y * TEX + 4 + Math.random() * 8, vx: (Math.random() - 0.5) * 1.4, vy: -Math.random() * 1.2, life: 9, max: 9, power: 15, color: old.kind === 'dust' ? '#b31a12' : '#55555c', gravity: 0.15 })
    }
    circuit.relink()
    return
  }
  const put = rebuild.add.shift()
  if (put) {
    circuit.set(put.i % w, (put.i / w) | 0, put.cell)
    circuit.relink()
    return
  }
  rebuild = null
}

function tick() {
  if (!circuit) return
  gameTick++
  maybeEvent()
  stepRebuild()
  const changed = circuit.step()
  eventParticles()
  for (const p of particles) {
    p.x += p.vx
    p.y += p.vy
    if (p.gravity) p.vy += p.gravity
    p.life--
  }
  particles = particles.filter((p) => p.life > 0)
  spawnParticles()
  draw(changed || particles.length > 0 || rebuild !== null)
}

function start() {
  if (timer || reduced || !visible || document.hidden) return
  timer = setInterval(tick, TICK_MS)
}

function stop() {
  clearInterval(timer)
  timer = undefined
}

function syncRunning() {
  if (visible && !document.hidden && !reduced) start()
  else stop()
}

watch(
  () => [props.mode, props.progress] as const,
  () => {
    applyMode()
    if (reduced) {
      settle()
      draw(true)
    }
  },
)
watch(
  () => props.anchor,
  (anchor) => {
    if (anchor) resize?.observe(anchor)
    layoutKey = ''
    scheduleLayout()
  },
)

let layoutTimer: ReturnType<typeof setTimeout> | undefined
function scheduleLayout() {
  clearTimeout(layoutTimer)
  // Beim Ziehen am Fensterrand nicht jedes Pixel neu aufbauen.
  layoutTimer = setTimeout(layout, circuit ? 120 : 0)
}

let resize: ResizeObserver | undefined
let intersect: IntersectionObserver | undefined
let themeWatch: MutationObserver | undefined
let motion: MediaQueryList | undefined

function onMotion() {
  reduced = !!motion?.matches
  if (reduced) {
    stop()
    particles = []
    settle()
    draw(true)
  } else syncRunning()
}

/** Strg+Alt+R: sofort ein Ereignis und einen Umbau auslösen (zum Ausprobieren). */
function onShortcut(e: KeyboardEvent) {
  if (!e.ctrlKey || !e.altKey || e.key.toLowerCase() !== 'r') return
  nextEvent = gameTick
  if (!rebuild) nextRebuild = gameTick
}

onMounted(() => {
  window.addEventListener('keydown', onShortcut)
  motion = matchMedia('(prefers-reduced-motion: reduce)')
  reduced = motion.matches
  motion.addEventListener('change', onMotion)

  resize = new ResizeObserver(scheduleLayout)
  if (root.value) resize.observe(root.value)
  if (props.anchor) resize.observe(props.anchor)
  intersect = new IntersectionObserver(([entry]) => {
    visible = !!entry?.isIntersecting
    syncRunning()
  })
  if (root.value) intersect.observe(root.value)
  document.addEventListener('visibilitychange', syncRunning)
  // Theme oder Akzent geändert → Farben neu lesen.
  themeWatch = new MutationObserver(() => requestAnimationFrame(repaintStatic))
  themeWatch.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'data-accent'] })

  layout()
  syncRunning()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onShortcut)
  stop()
  clearTimeout(layoutTimer)
  resize?.disconnect()
  intersect?.disconnect()
  themeWatch?.disconnect()
  motion?.removeEventListener('change', onMotion)
  document.removeEventListener('visibilitychange', syncRunning)
})
</script>

<template>
  <div ref="root" class="scene" aria-hidden="true">
    <canvas
      ref="pix"
      class="layer pix"
      :style="{ width: `${box.width}px`, height: `${box.height}px`, top: `${box.top}px` }"
    />
    <!-- Schleier für lesbaren Text: liegt über den Pixeln, aber unter dem Licht. -->
    <slot />
    <canvas
      ref="glow"
      class="layer glow"
      :style="{ width: `${box.width}px`, height: `${box.height}px`, top: `${box.top}px` }"
    />
  </div>
</template>

<style scoped>
.scene {
  position: absolute;
  z-index: 0;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  contain: strict;
}
.layer {
  position: absolute;
  left: 0;
  max-width: none;
}
.pix {
  image-rendering: pixelated;
}
/* Das Licht bleibt weich: grobe, schon weichgezeichnete Ebene, glatt hochskaliert. */
.glow {
  mix-blend-mode: screen;
  opacity: 0.9;
  /* Links steht der Text – dort strahlt das Licht nur gedämpft. */
  mask-image: linear-gradient(90deg, rgb(0 0 0 / 0.3) 0%, rgb(0 0 0 / 0.45) 35%, #000 62%);
}
:root[data-theme="light"] .glow {
  mix-blend-mode: multiply;
  opacity: 0.35;
}
</style>
