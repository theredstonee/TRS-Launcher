<script setup lang="ts">
import { emitBolt } from '~/utils/moonnight/bus'
import { makeScene, SCENE_H, type SceneFrame, type SceneOptions } from '~/utils/moonnight/scene'
import { drawSprite } from '~/utils/moonnight/sprites'

// Mondnacht (Halloween 2026, Stufe „Horror“) als Bühne der Startseite. Echte Pixel-Art:
// 180 Texel hoch, Breite nach Seitenverhältnis (die Szene rechnet in Anteilen von W),
// gezeichnet mit ~30 Bildern/s. „Animationen aus“: Standbild ohne Blitz.
// Auch Hintergrund aller anderen Seiten (über DesignScene): `still` = Standbild (Einstellung
// „Animierter Hintergrund“ aus), `bolts` = Blitze an die Atmosphäre melden (Aufflackern,
// Handabdrücke) – nur die eine große Szene, nicht jede kleine Vorschau.
const props = withDefaults(defineProps<{ seed?: number; moonX?: number; still?: boolean; bolts?: boolean }>(), {
  seed: 18,
  moonX: 0.5,
  still: false,
  bolts: true,
})

const root = useTemplateRef<HTMLDivElement>('root')
const canvas = useTemplateRef<HTMLCanvasElement>('canvas')
const spider = useTemplateRef<HTMLCanvasElement>('spider')
const { reduced } = useMotion()
const look = useHalloweenLook()
/** Standbild: „Animationen aus“ oder ausdrücklich gewünscht. */
const quiet = computed(() => reduced.value || props.still)

const opts: SceneOptions = {
  flicker: true,
  lightning: true,
  onBolt: () => {
    if (props.bolts) emitBolt()
  },
}
let frame: SceneFrame | null = null
let raf = 0
let lastDraw = 0
let builtWidth = 0
let visible = true
const t0 = performance.now()
/** Standbild: bewusst ein ruhiger Moment (vor dem ersten Blitz und vor der Hexe). */
const STILL_T = 2.2

function build() {
  const el = canvas.value
  const box = root.value
  if (!el || !box) return
  const aspect = box.clientWidth > 0 && box.clientHeight > 0 ? box.clientWidth / box.clientHeight : 16 / 9
  const w = Math.max(240, Math.min(720, Math.round(SCENE_H * aspect)))
  if (w === builtWidth && frame) return
  builtWidth = w
  el.width = w
  el.height = SCENE_H
  frame = makeScene(el, props.seed * 13 + 5, props.moonX, opts)
  paintStill()
}

function paintStill() {
  if (!frame) return
  const lightning = opts.lightning
  opts.lightning = false
  frame(STILL_T)
  opts.lightning = lightning
}

function tick(now: number) {
  raf = requestAnimationFrame(tick)
  // ~30 Bilder/s reichen für Pixel-Art und schonen den Rechner.
  if (now - lastDraw < 32) return
  lastDraw = now
  frame?.((now - t0) / 1000)
}

function sync() {
  cancelAnimationFrame(raf)
  raf = 0
  opts.flicker = look.flicker.value
  opts.lightning = !quiet.value
  if (quiet.value || document.hidden || !visible) {
    if (quiet.value) paintStill()
    return
  }
  raf = requestAnimationFrame(tick)
}

let resize: ResizeObserver | undefined
let io: IntersectionObserver | undefined
let resizeTimer: ReturnType<typeof setTimeout> | undefined
onMounted(() => {
  build()
  if (spider.value) drawSprite(spider.value, 'spider')
  if (root.value) {
    resize = new ResizeObserver(() => {
      clearTimeout(resizeTimer)
      resizeTimer = setTimeout(() => {
        build()
        sync()
      }, 120)
    })
    resize.observe(root.value)
    io = new IntersectionObserver(([e]) => {
      visible = e?.isIntersecting ?? true
      sync()
    })
    io.observe(root.value)
  }
  document.addEventListener('visibilitychange', sync)
  sync()
})
onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  clearTimeout(resizeTimer)
  resize?.disconnect()
  io?.disconnect()
  document.removeEventListener('visibilitychange', sync)
})
watch([quiet, look.flicker], sync)
</script>

<template>
  <div ref="root" class="moonnight" aria-hidden="true">
    <canvas ref="canvas" class="pix" />
    <slot />
    <div class="dangle" :class="{ still: quiet }" style="left: 38%; --len: 90px">
      <i /><canvas ref="spider" width="7" height="6" />
    </div>
  </div>
</template>

<style scoped>
.moonnight {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  background: #07060c;
  contain: strict;
}
.pix {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  image-rendering: pixelated;
}
/* Hängende Spinne (Entwurf `.spider`). */
.dangle {
  position: absolute;
  top: 0;
  width: 14px;
  transform-origin: top center;
  animation: dangle 5s ease-in-out infinite;
}
.dangle i {
  display: block;
  width: 1px;
  margin: 0 auto;
  height: var(--len, 60px);
  background: rgba(207, 213, 227, 0.45);
  animation: drop 6s ease-in-out infinite;
}
.dangle canvas {
  display: block;
  width: 14px;
  height: 12px;
  image-rendering: pixelated;
}
.dangle.still,
.dangle.still i {
  animation: none;
}
@keyframes dangle {
  50% {
    transform: rotate(4deg);
  }
}
@keyframes drop {
  50% {
    height: calc(var(--len, 60px) * 0.55);
  }
}
</style>
