<script setup lang="ts">
import type { Rgba } from '~/utils/capeImage'
import { CAPE_REGIONS, capeRegionAt, scaleRect, type CapeRegion } from '~/utils/capeLayout'
import { cloneRgba, fillRgba, getPixel, mirrorInPart, paintInPlace, partRect, shiftRect, type CapeTool, type Color } from '~/utils/capeEditor'
import { linePoints, type MirrorMode, type Point } from '~/utils/iconEditor'

// Zeichenfläche des Umhang-Editors: ein Frame (64·s × 32·s) vergrößert, mit
// Zwiebelhaut des vorigen Frames, Vorlage (Teile des Umhangs) und Raster. Ein Strich
// läuft auf einer Arbeitskopie; erst beim Loslassen geht das neue Bild nach außen.

const props = defineProps<{
  image: Rgba
  onion: Rgba | null
  scale: number
  tool: CapeTool
  color: Color
  brush: number
  mirror: MirrorMode
  zoom: number
  grid: boolean
  guides: boolean
  /** HD-Umhang: vergrößert glatt zeigen (wie im Spiel) statt scharfer Pixel. */
  smooth?: boolean
  readonly?: boolean
}>()
const emit = defineEmits<{ commit: [image: Rgba]; pick: [color: Color]; hover: [info: { x: number; y: number; region: CapeRegion | null } | null] }>()

const canvas = ref<HTMLCanvasElement | null>(null)
const working = shallowRef<Rgba | null>(null)
const shown = computed(() => working.value ?? props.image)

const GUIDE = { face: 'rgba(255, 90, 69, 0.95)', edge: 'rgba(242, 194, 48, 0.9)' }

let source: HTMLCanvasElement | null = null
const onionCanvases = new WeakMap<Rgba, HTMLCanvasElement>()

function toCanvas(img: Rgba, target?: HTMLCanvasElement): HTMLCanvasElement {
  const c = target ?? document.createElement('canvas')
  if (c.width !== img.width || c.height !== img.height) {
    c.width = img.width
    c.height = img.height
  }
  c.getContext('2d')?.putImageData(new ImageData(new Uint8ClampedArray(img.data), img.width, img.height), 0, 0)
  return c
}

let checker: CanvasPattern | null = null
function checkerPattern(ctx: CanvasRenderingContext2D): CanvasPattern | null {
  if (checker) return checker
  const c = document.createElement('canvas')
  c.width = 16
  c.height = 16
  const g = c.getContext('2d')!
  g.fillStyle = '#2b2833'
  g.fillRect(0, 0, 16, 16)
  g.fillStyle = '#35313d'
  g.fillRect(0, 0, 8, 8)
  g.fillRect(8, 8, 8, 8)
  checker = ctx.createPattern(c, 'repeat')
  return checker
}

function draw() {
  const el = canvas.value
  if (!el) return
  const img = shown.value
  const z = props.zoom
  const W = Math.round(img.width * z)
  const H = Math.round(img.height * z)
  if (el.width !== W || el.height !== H) {
    el.width = W
    el.height = H
  }
  const ctx = el.getContext('2d')
  if (!ctx) return
  ctx.imageSmoothingEnabled = !!props.smooth
  ctx.imageSmoothingQuality = 'high'
  ctx.fillStyle = checkerPattern(ctx) ?? '#2b2833'
  ctx.fillRect(0, 0, W, H)
  if (props.onion) {
    let oc = onionCanvases.get(props.onion)
    if (!oc) {
      oc = toCanvas(props.onion)
      onionCanvases.set(props.onion, oc)
    }
    ctx.globalAlpha = 0.3
    ctx.drawImage(oc, 0, 0, W, H)
    ctx.globalAlpha = 1
  }
  source = toCanvas(img, source ?? undefined)
  ctx.drawImage(source, 0, 0, W, H)
  if (props.guides) drawGuides(ctx, z)
  if (props.grid) drawGrid(ctx, img, z)
}

function drawGuides(ctx: CanvasRenderingContext2D, z: number) {
  const s = props.scale
  const W = 64 * s * z
  const H = 32 * s * z
  // Ungenutzte Flächen abdunkeln (sieht im Spiel niemand).
  ctx.fillStyle = 'rgba(6, 6, 10, 0.55)'
  ctx.beginPath()
  ctx.rect(0, 0, W, H)
  for (const r of CAPE_REGIONS) {
    const p = scaleRect(r, s * z)
    ctx.rect(p.x, p.y, p.w, p.h)
  }
  ctx.fill('evenodd')
  ctx.lineWidth = 1
  ctx.font = '600 11px Inter, system-ui, sans-serif'
  ctx.textBaseline = 'top'
  for (const r of CAPE_REGIONS) {
    const p = scaleRect(r, s * z)
    ctx.strokeStyle = r.kind === 'face' ? GUIDE.face : GUIDE.edge
    ctx.setLineDash(r.kind === 'edge' ? [3, 3] : [])
    ctx.strokeRect(p.x + 0.5, p.y + 0.5, p.w - 1, p.h - 1)
    if (r.kind === 'face' && p.w >= 64) {
      const label = t(`capes.studio.regions.${r.id}`)
      ctx.fillStyle = 'rgba(0, 0, 0, 0.55)'
      const w = ctx.measureText(label).width + 8
      ctx.fillRect(p.x + 2, p.y + 2, w, 15)
      ctx.fillStyle = '#fff'
      ctx.fillText(label, p.x + 6, p.y + 4)
    }
  }
  ctx.setLineDash([])
}

function drawGrid(ctx: CanvasRenderingContext2D, img: Rgba, z: number) {
  // Feines Raster je Pixel ab 6 px Zoom, sonst das Minecraft-Raster (64×32).
  const step = z >= 6 ? 1 : props.scale * z >= 6 ? props.scale : 0
  if (!step) return
  ctx.strokeStyle = 'rgba(0, 0, 0, 0.3)'
  ctx.lineWidth = 1
  ctx.beginPath()
  for (let x = step; x < img.width; x += step) {
    ctx.moveTo(x * z + 0.5, 0)
    ctx.lineTo(x * z + 0.5, img.height * z)
  }
  for (let y = step; y < img.height; y += step) {
    ctx.moveTo(0, y * z + 0.5)
    ctx.lineTo(img.width * z, y * z + 0.5)
  }
  ctx.stroke()
}

watch([shown, () => props.onion, () => props.zoom, () => props.grid, () => props.guides, () => props.scale, () => props.smooth], draw, { flush: 'post' })
onMounted(draw)

// --- Zeichnen ---------------------------------------------------------------------------

let start: Point | null = null
let last: Point | null = null
let erasing = false
let movePart: { x: number; y: number; w: number; h: number } | null = null

function cellOf(e: PointerEvent): Point {
  const rect = canvas.value!.getBoundingClientRect()
  const img = props.image
  const x = Math.floor(((e.clientX - rect.left) / rect.width) * img.width)
  const y = Math.floor(((e.clientY - rect.top) / rect.height) * img.height)
  return [Math.min(img.width - 1, Math.max(0, x)), Math.min(img.height - 1, Math.max(0, y))]
}

/** Pinsel (quadratisch, `brush` Pixel) + Spiegelung im Teil. */
function stamp(points: Point[]): Point[] {
  const n = Math.max(1, props.brush)
  const lo = -Math.floor((n - 1) / 2)
  const out: Point[] = []
  for (const [x, y] of points) {
    for (let dy = 0; dy < n; dy++) for (let dx = 0; dx < n; dx++) out.push([x + lo + dx, y + lo + dy])
  }
  return mirrorInPart(out, props.image, props.scale, props.mirror)
}

function strokeColor(): Color {
  return erasing || props.tool === 'eraser' ? [0, 0, 0, 0] : props.color
}

function onDown(e: PointerEvent) {
  if (props.readonly || (e.button !== 0 && e.button !== 2)) return
  e.preventDefault()
  canvas.value?.setPointerCapture(e.pointerId)
  erasing = e.button === 2
  const cell = cellOf(e)
  const base = props.image
  if (props.tool === 'picker') {
    const c = getPixel(base, cell[0], cell[1])
    if (c[3] > 0) emit('pick', c)
    return
  }
  if (props.tool === 'fill') {
    emit('commit', fillRgba(base, cell[0], cell[1], strokeColor(), props.scale))
    return
  }
  start = cell
  last = cell
  if (props.tool === 'move') {
    movePart = partRect(cell[0], cell[1], props.scale) ?? { x: 0, y: 0, w: base.width, h: base.height }
    working.value = base
    return
  }
  const copy = cloneRgba(base)
  if (props.tool === 'pencil' || props.tool === 'eraser') paintInPlace(copy, stamp([cell]), strokeColor())
  else paintInPlace(copy, stamp(linePoints(cell[0], cell[1], cell[0], cell[1])), strokeColor())
  working.value = copy
}

function onMove(e: PointerEvent) {
  const cell = cellOf(e)
  emit('hover', { x: cell[0], y: cell[1], region: capeRegionAt(cell[0], cell[1], props.scale) })
  if (!start || !working.value) return
  if (last && cell[0] === last[0] && cell[1] === last[1]) return
  if (props.tool === 'pencil' || props.tool === 'eraser') {
    // Gleiche Arbeitskopie weiter bemalen; neues Objekt, damit neu gezeichnet wird.
    const img = working.value
    paintInPlace(img, stamp(linePoints(last![0], last![1], cell[0], cell[1])), strokeColor())
    working.value = { ...img }
  } else if (props.tool === 'line') {
    const copy = cloneRgba(props.image)
    paintInPlace(copy, stamp(linePoints(start[0], start[1], cell[0], cell[1])), strokeColor())
    working.value = copy
  } else if (props.tool === 'move' && movePart) {
    working.value = shiftRect(props.image, movePart, cell[0] - start[0], cell[1] - start[1])
  }
  last = cell
}

function onUp() {
  if (working.value && working.value !== props.image) emit('commit', { ...working.value })
  working.value = null
  start = null
  last = null
  movePart = null
  erasing = false
}

function onLeave() {
  emit('hover', null)
}
</script>

<template>
  <canvas
    ref="canvas"
    class="block touch-none"
    :class="readonly ? 'cursor-default' : tool === 'move' ? 'cursor-move' : 'cursor-crosshair'"
    :style="{ width: `${image.width * zoom}px`, height: `${image.height * zoom}px`, imageRendering: smooth ? 'auto' : 'pixelated' }"
    role="img"
    :aria-label="t('capes.studio.canvasLabel', { w: image.width, h: image.height })"
    data-testid="cape-pixel-canvas"
    @pointerdown="onDown"
    @pointermove="onMove"
    @pointerup="onUp"
    @pointercancel="onUp"
    @pointerleave="onLeave"
    @contextmenu.prevent
  />
</template>
