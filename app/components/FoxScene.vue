<script setup lang="ts">
import { SceneLoop } from '~/utils/redstone/loop'
import { rng } from '~/utils/redstone/scene'
import { buildFoxLandscape, denParts, type FoxLandscape } from '~/utils/fox/landscape'
import { fireTop, FoxPainter } from '~/utils/fox/paint'
import { documentFoxPalette } from '~/utils/fox/palette'
import { Leaves, Sparks } from '~/utils/fox/particles'
import { foxBreath } from '~/utils/fox/sleep'
import { Smoke } from '~/utils/winter/snow'

// Hintergrund im Design „Fuchsbau“: ein Minecraft-Wald in der Abenddämmerung von der
// Seite – blockige Hügel im rosa Dunst mit Kirschkronen-Saum, Fichten und viele
// Kirschbäume, die eckige Sonne geht unter. Am Fuchsbau brennt ein Lagerfeuer unter einem
// großen Kirschbaum, daneben schläft ein Fuchs und atmet ruhig; Blütenteppiche,
// Glühwürmchen, treibende Kirschblüten und ein paar Herbstblätter. Auf der
// Startseite läuft ein Fichtenzaun mit Laternen bis zum Spielen-Knopf (`anchor`): beim
// Spielstart gehen sie mit dem Fortschritt an, läuft das Spiel, lodert das Feuer.
//
// Gleiche Regeln wie die Winterszene: Texel-Auflösung (3 Gerätepixel je Texel, CSS
// skaliert pixelig), eigener Takt (80 ms), steht still bei verdecktem Fenster/aus dem
// Bild und zeigt bei reduzierter Bewegung ein Standbild.
const props = withDefaults(
  defineProps<{
    mode?: 'idle' | 'starting' | 'running'
    /** Gesamtfortschritt beim Start, 0–100. */
    progress?: number
    /** Element, bis zu dem der Laternenzaun läuft (der Spielen-Knopf). */
    anchor?: HTMLElement | null
    /** Ganze Fläche füllen (Seiten-Hintergrund). */
    fill?: boolean
    seed?: number
  }>(),
  { mode: 'idle', progress: 0, anchor: null, fill: false, seed: 0xf0c5 },
)

const root = useTemplateRef<HTMLDivElement>('root')
const pix = useTemplateRef<HTMLCanvasElement>('pix')
const glowCanvas = useTemplateRef<HTMLCanvasElement>('glow')
const box = ref({ width: 0, height: 0 })
const { reduced } = useMotion()

/** Ein Bild alle 80 ms – genug für Flammen und treibende Blätter. */
const TICK_MS = 80
/** Texel je Pixel der weichen Licht-Ebene. */
const GLOW_SCALE = 4

let land: FoxLandscape | null = null
let painter: FoxPainter | null = null
let leaves: Leaves | null = null
let smoke: Smoke | null = null
let sparks: Sparks | null = null
let layoutKey = ''
let tickCount = 0
let started = performance.now()
const rand = rng((Date.now() & 0xffffff) ^ 0xf0)

const loop = new SceneLoop({
  tick,
  interval: TICK_MS,
  recover() {
    layoutKey = ''
    layout()
  },
  report: (e) => console.error('[FoxScene]', e),
})

function seconds() {
  return (performance.now() - started) / 1000
}

/** Laternen an (von links) und Feuerstärke je Modus. */
function state() {
  const n = land?.lanterns.length ?? 0
  if (props.mode === 'running') return { lit: n, fire: 1 }
  if (props.mode === 'starting') {
    const p = Math.min(100, Math.max(0, props.progress)) / 100
    return { lit: Math.max(1, Math.round(n * p)), fire: 0.5 + 0.45 * p }
  }
  return { lit: 0, fire: 0.5 }
}

function origin() {
  if (!land?.den) return null
  const top = fireTop(denParts(land.den))
  return { x: top.x + top.w / 2, y: top.y, w: top.w }
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
  land = buildFoxLandscape({ cols, rows, seed: props.seed, anchorRow, anchorEnd: anchor ? anchorEnd : 0, anchorTop, anchorRight })
  leaves = new Leaves(land.w, land.h, rand, props.fill ? 1 : 0.8)
  smoke = new Smoke(rand)
  sparks = new Sparks(rand)
  // Etwas Rauch schon da, damit er nicht erst aus dem Nichts aufsteigt.
  for (let i = 0; i < 60; i++) {
    smoke.step(origin(), i)
    sparks.step(origin(), i, 0.5)
  }
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

/** Aufbau außerhalb des Takts (Mount, Größe, Theme): ein Fehler darf die Seite nicht stören. */
function safely(fn: () => void) {
  try {
    fn()
  } catch (e) {
    console.error('[FoxScene]', e)
  }
}

function repaintStatic() {
  if (!land) return
  painter = new FoxPainter(land, documentFoxPalette())
  draw(true)
}

function draw(withGlow: boolean) {
  const c = pix.value?.getContext('2d')
  if (!c || !land) return
  if (!painter) painter = new FoxPainter(land, documentFoxPalette())
  const s = state()
  const t = seconds()
  const calm = reduced.value
  painter.frame(c, {
    tick: tickCount,
    seconds: t,
    ...s,
    calm,
    fox: foxBreath(t, calm),
    smoke: smoke?.puffs ?? [],
    sparks: sparks?.sparks ?? [],
    leaves: leaves?.leaves ?? [],
  })
  if (withGlow) {
    const g = glowCanvas.value?.getContext('2d')
    if (g) painter.glow(g, { ...s, tick: tickCount, seconds: t, calm }, GLOW_SCALE)
  }
}

function tick() {
  if (!land) return
  tickCount++
  const s = state()
  leaves?.step(tickCount)
  smoke?.step(origin(), tickCount)
  sparks?.step(origin(), tickCount, s.fire)
  // Licht jedes zweite Bild – Feuer und Glühwürmchen ändern sich langsam genug.
  draw(tickCount % 2 === 0)
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
  if (on) safely(() => draw(true))
})

let layoutTimer: ReturnType<typeof setTimeout> | undefined
function scheduleLayout() {
  clearTimeout(layoutTimer)
  layoutTimer = setTimeout(() => safely(layout), land ? 120 : 0)
}

let resize: ResizeObserver | undefined
let intersect: IntersectionObserver | undefined
let themeWatch: MutationObserver | undefined
let wasHidden = false

/** Wie in den anderen Szenen: nach Minimieren/Ruhezustand alle Ebenen frisch zeichnen. */
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
  opacity: 0.85;
  mask-image: linear-gradient(90deg, rgb(0 0 0 / 0.3) 0%, rgb(0 0 0 / 0.45) 35%, #000 62%);
}
:root[data-theme="light"] .glow {
  mix-blend-mode: soft-light;
  opacity: 0.6;
}
</style>
