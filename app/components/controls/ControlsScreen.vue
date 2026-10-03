<script setup lang="ts">
import type { ControlButton, ControlLayout, Insets, Rect } from '~/utils/controls'
import { circleOf, iconPixels, moveButton, resizeButton, safeRect, toPx } from '~/utils/controls'

// Handy-Bildschirm (quer, 2:1) mit den Knöpfen eines Layouts – als kleine
// Vorschau oder im Editor (ziehen = verschieben, Ecke = Größe). Gleiche
// Darstellung wie das Overlay im Spiel: Kreis = kürzere Seite, Symbol mittig,
// kurze Beschriftung (≤ 3 Zeichen) als Tasten-Hinweis unten rechts.
const props = withDefaults(
  defineProps<{
    layout: ControlLayout
    editable?: boolean
    selected?: string | null
    grid?: boolean
    /** Notch links + Gestenleiste unten simulieren. */
    notch?: boolean
    /** Für Screenreader. */
    label: string
  }>(),
  { editable: false, selected: null, grid: false, notch: false },
)
const emit = defineEmits<{ select: [id: string | null]; change: [layout: ControlLayout] }>()

const sky = `trs-ctl-sky-${useId()}`
const W = 1000
const H = 500
const insets = computed<Insets>(() => (props.notch ? { left: 44, top: 0, right: 16, bottom: 14 } : { left: 0, top: 0, right: 0, bottom: 0 }))
const safe = computed(() => safeRect(W, H, insets.value))

interface Drawn {
  b: ControlButton
  r: Rect
  /** Kreis (runde Knöpfe) */
  c: { cx: number; cy: number; r: number }
  /** Symbolpixel in Bildschirmkoordinaten */
  px: { x: number; y: number; s: number }[]
  text: string | null
  badge: string | null
}

const drawn = computed<Drawn[]>(() =>
  props.layout.buttons.map((b) => {
    const r = toPx(b, safe.value)
    const c = circleOf(r)
    const box = b.shape === 'round' ? c.r * 2 : Math.min(r.w, r.h)
    const isJoystick = b.action.type === 'joystick'
    const isHotbar = b.action.type === 'special' && b.action.special === 'hotbarSwipe'
    let px: Drawn['px'] = []
    if (b.icon && !isJoystick && !isHotbar) {
      const s = (box * 0.5) / 8
      const ox = c.cx - s * 4
      const oy = c.cy - s * 4
      px = iconPixels(b.icon).map(([x, y]) => ({ x: ox + x * s, y: oy + y * s, s }))
    }
    const label = b.label?.trim() ?? null
    return {
      b,
      r,
      c,
      px,
      text: !b.icon && label && !isJoystick ? label : null,
      badge: b.icon && label && [...label].length <= 3 ? label : null,
    }
  }),
)

const fontFor = (d: Drawn) => Math.max(9, Math.min(d.r.h * 0.32, (d.r.w * 1.6) / Math.max(1, [...(d.text ?? '')].length)))

// --- Ziehen im Editor -------------------------------------------------------------
const svg = ref<SVGSVGElement | null>(null)
let drag: { id: string; mode: 'move' | 'resize'; start: ControlLayout; x: number; y: number; pointer: number } | null = null

function toView(e: PointerEvent): { x: number; y: number } {
  const rect = svg.value?.getBoundingClientRect()
  if (!rect || rect.width === 0) return { x: 0, y: 0 }
  return { x: ((e.clientX - rect.left) / rect.width) * W, y: ((e.clientY - rect.top) / rect.height) * H }
}

function down(e: PointerEvent, id: string, mode: 'move' | 'resize') {
  if (!props.editable) return
  e.stopPropagation()
  emit('select', id)
  const p = toView(e)
  drag = { id, mode, start: props.layout, x: p.x, y: p.y, pointer: e.pointerId }
  ;(e.currentTarget as Element).setPointerCapture?.(e.pointerId)
}

function move(e: PointerEvent) {
  if (!drag || e.pointerId !== drag.pointer || safe.value.w <= 0) return
  const p = toView(e)
  const dx = (p.x - drag.x) / safe.value.w
  const dy = (p.y - drag.y) / safe.value.h
  const next = drag.mode === 'move' ? moveButton(drag.start, drag.id, dx, dy, props.grid) : resizeButton(drag.start, drag.id, dx, dy, props.grid)
  emit('change', next)
}

function up(e: PointerEvent) {
  if (drag && e.pointerId === drag.pointer) drag = null
}

/** Pfeiltasten verschieben den gewählten Knopf (Shift = größer). */
function key(e: KeyboardEvent, id: string) {
  const step = e.shiftKey ? 0.05 : 0.01
  const d: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }
  const delta = d[e.key]
  if (!delta || !props.editable) return
  e.preventDefault()
  emit('change', moveButton(props.layout, id, delta[0], delta[1], false))
}

const gridLines = computed(() => {
  if (!props.grid || !props.editable) return { xs: [] as number[], ys: [] as number[] }
  const xs = Array.from({ length: 19 }, (_, i) => safe.value.x + ((i + 1) / 20) * safe.value.w)
  const ys = Array.from({ length: 9 }, (_, i) => safe.value.y + ((i + 1) / 10) * safe.value.h)
  return { xs, ys }
})
</script>

<template>
  <svg
    ref="svg"
    :viewBox="`0 0 ${W} ${H}`"
    class="block w-full touch-none select-none rounded-xl ring-1 ring-base-700"
    role="img"
    :aria-label="label"
    @pointermove="move"
    @pointerup="up"
    @pointercancel="up"
    @pointerdown="editable && emit('select', null)"
  >
    <!-- Kulisse: Himmel, Gras, Hotbar – zum Ausrichten der Hotbar-Fläche -->
    <defs>
      <linearGradient :id="sky" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#4c6fae" />
        <stop offset="0.62" stop-color="#8fb2e0" />
        <stop offset="0.62" stop-color="#5d8a3a" />
        <stop offset="1" stop-color="#3f6427" />
      </linearGradient>
    </defs>
    <rect :width="W" :height="H" :fill="`url(#${sky})`" />
    <g opacity="0.85">
      <rect :x="safe.x + safe.w * 0.31" :y="safe.y + safe.h * 0.9" :width="safe.w * 0.38" :height="safe.h * 0.085" fill="#000" fill-opacity="0.45" stroke="#c6c6c6" stroke-width="2" />
      <line
        v-for="i in 8"
        :key="i"
        :x1="safe.x + safe.w * (0.31 + (0.38 / 9) * i)"
        :x2="safe.x + safe.w * (0.31 + (0.38 / 9) * i)"
        :y1="safe.y + safe.h * 0.9"
        :y2="safe.y + safe.h * 0.985"
        stroke="#8b8b8b"
        stroke-width="1.5"
      />
    </g>
    <rect v-if="notch" x="0" :y="H / 2 - 50" width="30" height="100" rx="14" fill="#000" />
    <rect v-if="notch" :x="safe.x" :y="safe.y" :width="safe.w" :height="safe.h" fill="none" stroke="#fff" stroke-opacity="0.35" stroke-dasharray="6 6" />
    <g v-if="gridLines.xs.length" stroke="#fff" stroke-opacity="0.12" stroke-width="1">
      <line v-for="x in gridLines.xs" :key="`x${x}`" :x1="x" :x2="x" :y1="safe.y" :y2="safe.y + safe.h" />
      <line v-for="y in gridLines.ys" :key="`y${y}`" :y1="y" :y2="y" :x1="safe.x" :x2="safe.x + safe.w" />
    </g>

    <g
      v-for="d in drawn"
      :key="d.b.id"
      :opacity="editable && selected === d.b.id ? Math.max(0.85, d.b.opacity) : editable ? Math.max(0.35, d.b.opacity) : d.b.opacity"
      :class="editable ? 'cursor-move outline-none' : ''"
      :tabindex="editable ? 0 : undefined"
      :role="editable ? 'button' : undefined"
      :aria-label="editable ? d.b.label ?? d.b.icon ?? d.b.id : undefined"
      :aria-pressed="editable ? selected === d.b.id : undefined"
      @pointerdown="down($event, d.b.id, 'move')"
      @keydown="key($event, d.b.id)"
      @focus="editable && emit('select', d.b.id)"
    >
      <!-- Joystick: Ring + Knauf -->
      <template v-if="d.b.action.type === 'joystick'">
        <circle :cx="d.c.cx" :cy="d.c.cy" :r="d.c.r" fill="#17171e" fill-opacity="0.55" stroke="#c8c8d8" stroke-width="3" />
        <circle :cx="d.c.cx" :cy="d.c.cy" :r="d.c.r * 0.4" fill="#252531" stroke="#e0281e" stroke-width="3" />
        <text
          v-if="d.b.action.mode === 'camera'"
          :x="d.c.cx"
          :y="d.c.cy"
          fill="#f3f3f8"
          :font-size="d.c.r * 0.28"
          font-family="ui-monospace, monospace"
          font-weight="700"
          text-anchor="middle"
          dominant-baseline="central"
        >◎</text>
      </template>
      <!-- Hotbar-Fläche: 9 Plätze -->
      <template v-else-if="d.b.action.type === 'special' && d.b.action.special === 'hotbarSwipe'">
        <rect :x="d.r.x" :y="d.r.y" :width="d.r.w" :height="d.r.h" fill="#e0281e" fill-opacity="0.25" stroke="#ff5a4d" stroke-width="2" stroke-dasharray="8 5" />
        <line
          v-for="i in 8"
          :key="i"
          :x1="d.r.x + (d.r.w / 9) * i"
          :x2="d.r.x + (d.r.w / 9) * i"
          :y1="d.r.y + d.r.h * 0.2"
          :y2="d.r.y + d.r.h * 0.8"
          stroke="#ff8f85"
          stroke-width="1.5"
        />
      </template>
      <template v-else>
        <circle v-if="d.b.shape === 'round'" :cx="d.c.cx" :cy="d.c.cy" :r="d.c.r" fill="#17171e" stroke="#333343" stroke-width="3" />
        <rect v-else :x="d.r.x" :y="d.r.y" :width="d.r.w" :height="d.r.h" rx="6" fill="#17171e" stroke="#333343" stroke-width="3" />
        <rect v-for="(p, i) in d.px" :key="i" :x="p.x" :y="p.y" :width="p.s + 0.3" :height="p.s + 0.3" fill="#f3f3f8" />
        <text
          v-if="d.text"
          :x="d.c.cx"
          :y="d.c.cy"
          fill="#f3f3f8"
          :font-size="fontFor(d)"
          font-family="ui-monospace, monospace"
          font-weight="700"
          text-anchor="middle"
          dominant-baseline="central"
        >{{ d.text }}</text>
        <text
          v-if="d.badge"
          :x="d.b.shape === 'round' ? d.c.cx + d.c.r * 0.62 : d.r.x + d.r.w - 4"
          :y="d.b.shape === 'round' ? d.c.cy + d.c.r * 0.72 : d.r.y + d.r.h - 4"
          fill="#ffb84d"
          :font-size="Math.max(8, Math.min(d.r.w, d.r.h) * 0.22)"
          font-family="ui-monospace, monospace"
          font-weight="700"
          text-anchor="end"
        >{{ d.badge }}</text>
      </template>
      <!-- Auswahl: Rahmen + Griff zum Vergrößern -->
      <template v-if="editable && selected === d.b.id">
        <rect :x="d.r.x - 3" :y="d.r.y - 3" :width="d.r.w + 6" :height="d.r.h + 6" fill="none" stroke="#e0281e" stroke-width="2.5" stroke-dasharray="7 4" />
        <rect
          :x="d.r.x + d.r.w - 9"
          :y="d.r.y + d.r.h - 9"
          width="18"
          height="18"
          fill="#e0281e"
          stroke="#fff"
          stroke-width="2"
          class="cursor-nwse-resize"
          @pointerdown="down($event, d.b.id, 'resize')"
        />
      </template>
    </g>
  </svg>
</template>
