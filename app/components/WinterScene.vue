<script setup lang="ts">
import { SceneLoop } from '~/utils/redstone/loop'
import { rng } from '~/utils/redstone/scene'
import { auroraEnvelope, buildLandscape, CABINS, type Landscape } from '~/utils/winter/landscape'
import { Penguin } from '~/utils/winter/penguin'
import { WinterPainter } from '~/utils/winter/paint'
import { documentWinterPalette } from '~/utils/winter/palette'
import { Smoke, Snowfall } from '~/utils/winter/snow'

// Hintergrund im Design „Winternacht“: eine verschneite Pixel-Landschaft von der
// Seite – Berge, Tannen, eine Hütte mit warmem Fenster, Mond und Sterne, leiser
// Schneefall und hin und wieder Polarlicht. Auf der Startseite steht eine Reihe
// Eislaternen bis zum Spielen-Knopf (`anchor`): beim Spielstart gehen sie mit dem
// Fortschritt an, läuft das Spiel, leuchtet auch das Polarlicht.
//
// Gleiche Regeln wie die Redstone-Szene: Texel-Auflösung (3 Gerätepixel je
// Texel, CSS skaliert pixelig), eigener Takt (80 ms), steht still bei
// verdecktem Fenster/aus dem Bild und zeigt bei reduzierter Bewegung ein Standbild.
const props = withDefaults(
  defineProps<{
    mode?: 'idle' | 'starting' | 'running'
    /** Gesamtfortschritt beim Start, 0–100. */
    progress?: number
    /** Element, bis zu dem die Laternenreihe läuft (der Spielen-Knopf). */
    anchor?: HTMLElement | null
    /** Ganze Fläche füllen (Seiten-Hintergrund). */
    fill?: boolean
    seed?: number
  }>(),
  { mode: 'idle', progress: 0, anchor: null, fill: false, seed: 0x5a0 },
)

const root = useTemplateRef<HTMLDivElement>('root')
const pix = useTemplateRef<HTMLCanvasElement>('pix')
const glowCanvas = useTemplateRef<HTMLCanvasElement>('glow')
const box = ref({ width: 0, height: 0 })
const { reduced } = useMotion()

/** Ein Bild alle 80 ms – genug für ruhigen Schneefall. */
const TICK_MS = 80
/** Texel je Pixel der weichen Licht-Ebene. */
const GLOW_SCALE = 4

let land: Landscape | null = null
let painter: WinterPainter | null = null
let snow: Snowfall | null = null
let smoke: Smoke | null = null
let penguin: Penguin | null = null
let layoutKey = ''
let tickCount = 0
let started = performance.now()
const rand = rng((Date.now() & 0xffffff) ^ 0x51)

const loop = new SceneLoop({
  tick,
  interval: TICK_MS,
  recover() {
    layoutKey = ''
    layout()
  },
  report: (e) => console.error('[WinterScene]', e),
})

function seconds() {
  return (performance.now() - started) / 1000
}

/** Laternen an (von links) und Polarlicht-Stärke je Modus. */
function state() {
  const n = land?.lanterns.length ?? 0
  const t = seconds()
  const envelope = reduced.value ? 0.75 : auroraEnvelope(t, props.seed)
  if (props.mode === 'running') return { lit: n, aurora: 1, flicker: !reduced.value }
  if (props.mode === 'starting') {
    const p = Math.min(100, Math.max(0, props.progress)) / 100
    return { lit: Math.max(1, Math.round(n * p)), aurora: Math.max(envelope, p * 0.85), flicker: false }
  }
  return { lit: 0, aurora: envelope, flicker: false }
}

function layout() {
  const el = root.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  if (rect.width < 1 || rect.height < 1) return
  const dpr = window.devicePixelRatio || 1
  // Ganze Gerätepixel je Texel – sonst werden die Pixel ungleich breit.
  const texelDev = Math.max(2, Math.round(3 * dpr))
  const texel = texelDev / dpr

  let anchorRow = -1
  let anchorEnd = 0
  let anchorTop: number | undefined
  let anchorRight: number | undefined
  const anchor = props.anchor
  if (anchor) {
    const a = anchor.getBoundingClientRect()
    anchorRow = Math.floor((a.top + a.height / 2 - rect.top) / texel)
    anchorEnd = Math.max(0, Math.floor((a.left - rect.left) / texel))
    anchorTop = Math.floor((a.top - rect.top) / texel)
    anchorRight = Math.floor((a.right - rect.left) / texel)
  }
  const cols = Math.ceil(rect.width / texel)
  const rows = Math.ceil(rect.height / texel)
  box.value = { width: cols * texel, height: rows * texel }

  const key = `${cols}x${rows}:${anchorRow}:${anchorEnd}:${anchorTop}:${anchorRight}`
  if (key === layoutKey && land) return
  layoutKey = key
  land = buildLandscape({ cols, rows, seed: props.seed, anchorRow, anchorEnd: anchor ? anchorEnd : 0, anchorTop, anchorRight })
  snow = new Snowfall(land.w, land.h, rand, props.fill ? 1 : 0.8)
  smoke = new Smoke(rand)
  // Der Pinguin bleibt bei Größenänderungen derselbe (seine Lage ist relativ zur Hütte).
  if (land.cabin?.size !== 'large') penguin = null
  else if (!penguin) penguin = new Penguin(rand, { day: isDay(), still: reduced.value })
  // Etwas Rauch schon da, damit er nicht erst aus dem Nichts aufsteigt.
  for (let i = 0; i < 60; i++) smoke.step(chimney(), i)
  const c = pix.value
  const g = glowCanvas.value
  if (c && g) {
    c.width = cols
    c.height = rows
    g.width = Math.ceil(cols / GLOW_SCALE)
    g.height = Math.ceil(rows / GLOW_SCALE)
  }
  repaintStatic()
}

function chimney() {
  if (!land?.cabin) return null
  const spec = CABINS[land.cabin.size]
  return { x: land.cabin.x + spec.chimney.x, y: land.cabin.y - spec.h + spec.chimney.y }
}

/** Heller Wintertag (Farbschema Hell): der Pinguin ist öfter draußen. */
function isDay() {
  return document.documentElement.dataset.theme === 'light'
}

/** Aufbau außerhalb des Takts (Mount, Größe, Theme): ein Fehler darf die Seite nicht stören. */
function safely(fn: () => void) {
  try {
    fn()
  } catch (e) {
    console.error('[WinterScene]', e)
  }
}

function repaintStatic() {
  if (!land) return
  painter = new WinterPainter(land, documentWinterPalette())
  penguin?.setDay(isDay())
  draw(true)
}

function draw(withGlow: boolean) {
  const c = pix.value?.getContext('2d')
  if (!c || !land) return
  if (!painter) painter = new WinterPainter(land, documentWinterPalette())
  const s = { ...state(), penguin: penguin?.view() ?? null }
  painter.frame(c, { tick: tickCount, seconds: seconds(), ...s, flakes: snow?.flakes ?? [], smoke: smoke?.puffs ?? [] })
  if (withGlow) {
    const g = glowCanvas.value?.getContext('2d')
    if (g) painter.glow(g, { ...s, tick: tickCount }, GLOW_SCALE)
  }
}

let lastGlow = ''
function tick() {
  if (!land) return
  tickCount++
  snow?.step(tickCount)
  smoke?.step(chimney(), tickCount)
  penguin?.step()
  const s = state()
  // Licht nur neu, wenn sich etwas ändert (Laternen, Polarlicht, Flackern, Hüttentür).
  const glowKey = `${s.lit}:${s.aurora.toFixed(2)}:${s.flicker ? tickCount >> 1 : 0}:${penguin?.view().door ?? 0}`
  draw(glowKey !== lastGlow)
  lastGlow = glowKey
}

watch(
  () => [props.mode, props.progress] as const,
  () => {
    if (reduced.value) safely(() => draw(true))
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
watch(reduced, (on) => {
  loop.set('motion', on)
  penguin?.setStill(on)
  if (on) safely(() => draw(true))
})

/** Strg+Alt+P: der Pinguin kommt sofort heraus (auch `window.__trsPenguin()` im Entwicklungsmodus). */
function penguinOut() {
  penguin?.trigger()
}
function onShortcut(e: KeyboardEvent) {
  if (e.ctrlKey && e.altKey && e.key.toLowerCase() === 'p') penguinOut()
}

let layoutTimer: ReturnType<typeof setTimeout> | undefined
function scheduleLayout() {
  clearTimeout(layoutTimer)
  layoutTimer = setTimeout(() => safely(layout), land ? 120 : 0)
}

let resize: ResizeObserver | undefined
let intersect: IntersectionObserver | undefined
let themeWatch: MutationObserver | undefined
let wasHidden = false

/** Wie in der Redstone-Szene: nach Minimieren/Ruhezustand alle Ebenen frisch zeichnen. */
function syncHidden() {
  const hidden = document.hidden && !document.hasFocus()
  if (wasHidden && !hidden) safely(repaintStatic)
  wasHidden = hidden
  loop.set('hidden', hidden)
}

function onContextRestored() {
  safely(repaintStatic)
}

onMounted(() => {
  window.addEventListener('keydown', onShortcut)
  if (import.meta.dev) {
    ;(window as unknown as { __trsPenguin?: () => void }).__trsPenguin = () =>
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'p', ctrlKey: true, altKey: true }))
  }
  started = performance.now()
  loop.set('motion', reduced.value)
  syncHidden()
  resize = new ResizeObserver(scheduleLayout)
  if (root.value) resize.observe(root.value)
  if (props.anchor) resize.observe(props.anchor)
  intersect = new IntersectionObserver((entries) => {
    const entry = entries[entries.length - 1]
    if (entry) loop.set('offscreen', !entry.isIntersecting)
  })
  if (root.value) intersect.observe(root.value)
  document.addEventListener('visibilitychange', syncHidden)
  window.addEventListener('focus', syncHidden)
  window.addEventListener('pageshow', syncHidden)
  // Farbschema oder Akzent geändert → Farben neu lesen.
  themeWatch = new MutationObserver(() => requestAnimationFrame(() => safely(repaintStatic)))
  themeWatch.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'data-accent'] })
  pix.value?.addEventListener('contextrestored', onContextRestored)
  glowCanvas.value?.addEventListener('contextrestored', onContextRestored)
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
  pix.value?.removeEventListener('contextrestored', onContextRestored)
  glowCanvas.value?.removeEventListener('contextrestored', onContextRestored)
})
</script>

<template>
  <div ref="root" class="scene" aria-hidden="true">
    <canvas ref="pix" class="layer pix" :style="{ width: `${box.width}px`, height: `${box.height}px` }" />
    <!-- Schleier für lesbaren Text: liegt über den Pixeln, aber unter dem Licht. -->
    <slot />
    <canvas ref="glow" class="layer glow" :style="{ width: `${box.width}px`, height: `${box.height}px` }" />
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
  top: 0;
  left: 0;
  max-width: none;
}
.pix {
  image-rendering: pixelated;
}
.glow {
  mix-blend-mode: screen;
  opacity: 0.9;
  mask-image: linear-gradient(90deg, rgb(0 0 0 / 0.3) 0%, rgb(0 0 0 / 0.45) 35%, #000 62%);
}
:root[data-theme="light"] .glow {
  mix-blend-mode: multiply;
  opacity: 0.35;
}
</style>
