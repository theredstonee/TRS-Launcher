<script setup lang="ts">
import { documentPalette, type Palette } from '~/utils/redstone/palette'
import { TEX, Sprites, noise, paintFloor, paintFrame, paintGlow, type Particle } from '~/utils/redstone/paint'
import { buildScene, rng } from '~/utils/redstone/scene'
import { Rebuilder } from '~/utils/redstone/rebuild'
import { SceneLoop } from '~/utils/redstone/loop'
import type { Circuit } from '~/utils/redstone/sim'

// Lebendiger Hintergrund der Startseite: eine Redstone-Schaltung von oben –
// Takte, Lauflicht, Kolben, flackernde Fackeln. Die Hauptleitung läuft zur
// Spielen-Lampe (`anchor`) und lädt sich beim Spielstart mit dem Fortschritt auf.
//
// Sparsam: gezeichnet wird nur im Redstone-Takt (10 Bilder/s, wie im Spiel)
// auf einer Fläche von ein paar hundert Texeln; Hochskalieren macht CSS. Steht
// still, wenn das Fenster verdeckt oder die Szene aus dem Bild gescrollt ist,
// und zeigt ein ruhiges Standbild, wenn die Einstellung „Animationen“ das will
// (Standard: immer animiert – siehe `useMotion`).
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
const { reduced } = useMotion()

let circuit: Circuit | null = null
let palette: Palette | null = null
let sprites: Sprites | null = null
let floor: HTMLCanvasElement | OffscreenCanvas | null = null
let busEnd = 0
let layoutKey = ''
let gameTick = 0
let particles: Particle[] = []

/** Ein Redstone-Tick – mehr Bilder braucht die Pixel-Szene nicht. */
const TICK_MS = 100

const rebuilder = new Rebuilder(rng(Date.now() & 0xffffff))

// Läuft nur ohne Pausengrund (Bewegung reduziert, aus dem Bild, Fenster verdeckt).
// Ein Fehler im Tick hält die Szene nicht mehr an: nach ein paar Fehlern in
// Folge wird sie neu aufgebaut.
const loop = new SceneLoop({
  tick,
  interval: TICK_MS,
  recover() {
    layoutKey = ''
    layout()
  },
  report: (e) => console.error('[RedstoneScene]', e),
})

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
  rebuilder.reset()
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

/** Aufbau außerhalb des Takts (Mount, Größe, Theme): ein Fehler darf die Seite nicht stören. */
function safely(fn: () => void) {
  try {
    fn()
  } catch (e) {
    console.error('[RedstoneScene]', e)
  }
}

function repaintStatic() {
  if (!circuit) return
  palette = documentPalette()
  sprites = new Sprites(palette)
  floor = paintFloor(circuit, palette)
  if (reduced.value) settle()
  draw(true)
}

/** Standbild bei reduzierter Bewegung: Zustand einschwingen lassen, dann einmal zeichnen. */
function settle() {
  if (!circuit) return
  for (let i = 0; i < 40; i++) circuit.step()
}

function draw(withGlow: boolean) {
  const c = pix.value?.getContext('2d')
  if (!c || !circuit) return
  // Farben/Boden fehlen (Aufbau scheiterte vorher)? Dann jetzt nachholen –
  // ein erneuter Fehler landet beim Takt und führt zum Neuaufbau.
  if (!floor || !sprites || !palette) {
    palette = documentPalette()
    sprites = new Sprites(palette)
    floor = paintFloor(circuit, palette)
    withGlow = true
  }
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

function tick() {
  if (!circuit) return
  gameTick++
  try {
    rebuilder.step(circuit, gameTick, particles)
  } catch (e) {
    // Ein misslungener Umbau darf die Schaltung nicht anhalten – dann eben keiner.
    rebuilder.reset()
    throw e
  }
  const changed = circuit.step()
  for (const p of particles) {
    p.x += p.vx
    p.y += p.vy
    if (p.gravity) p.vy += p.gravity
    p.life--
  }
  particles = particles.filter((p) => p.life > 0)
  spawnParticles()
  draw(changed || particles.length > 0 || rebuilder.active)
}

watch(
  () => [props.mode, props.progress] as const,
  () => {
    applyMode()
    if (reduced.value) {
      settle()
      safely(() => draw(true))
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

// Einstellung „Animationen“ oder „Bewegung reduzieren“ des Systems geändert.
watch(reduced, (on) => {
  loop.set('motion', on)
  if (on) {
    particles = []
    settle()
    safely(() => draw(true))
  }
})

let layoutTimer: ReturnType<typeof setTimeout> | undefined
function scheduleLayout() {
  clearTimeout(layoutTimer)
  // Beim Ziehen am Fensterrand nicht jedes Pixel neu aufbauen.
  layoutTimer = setTimeout(() => safely(layout), circuit ? 120 : 0)
}

let resize: ResizeObserver | undefined
let intersect: IntersectionObserver | undefined
let themeWatch: MutationObserver | undefined

/**
 * Fenster verdeckt/minimiert? `visibilitychange` kommt in WebView2/WebKitGTK
 * nach dem Wiederherstellen (Taskleiste, nach dem Spiel) nicht immer zuverlässig –
 * deshalb auch bei Fokus/`pageshow` neu prüfen, und ein Fenster mit Fokus gilt
 * immer als sichtbar.
 */
function syncHidden() {
  loop.set('hidden', document.hidden && !document.hasFocus())
}

/** Strg+Alt+R: sofort einen Umbau auslösen (zum Ausprobieren). */
function onShortcut(e: KeyboardEvent) {
  if (!e.ctrlKey || !e.altKey || e.key.toLowerCase() !== 'r') return
  rebuilder.trigger(gameTick)
}

onMounted(() => {
  window.addEventListener('keydown', onShortcut)
  loop.set('motion', reduced.value)
  syncHidden()

  resize = new ResizeObserver(scheduleLayout)
  if (root.value) resize.observe(root.value)
  if (props.anchor) resize.observe(props.anchor)
  // Mehrere Meldungen auf einmal möglich (schnell raus und wieder rein) –
  // die letzte ist der aktuelle Stand.
  intersect = new IntersectionObserver((entries) => {
    const entry = entries[entries.length - 1]
    if (entry) loop.set('offscreen', !entry.isIntersecting)
  })
  if (root.value) intersect.observe(root.value)
  document.addEventListener('visibilitychange', syncHidden)
  window.addEventListener('focus', syncHidden)
  window.addEventListener('pageshow', syncHidden)
  // Theme oder Akzent geändert → Farben neu lesen.
  themeWatch = new MutationObserver(() => requestAnimationFrame(() => safely(repaintStatic)))
  themeWatch.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'data-accent'] })

  safely(layout)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onShortcut)
  loop.dispose()
  clearTimeout(layoutTimer)
  resize?.disconnect()
  intersect?.disconnect()
  themeWatch?.disconnect()
  document.removeEventListener('visibilitychange', syncHidden)
  window.removeEventListener('focus', syncHidden)
  window.removeEventListener('pageshow', syncHidden)
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
