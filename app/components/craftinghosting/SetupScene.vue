<script setup lang="ts">
import { documentDesign } from '~/utils/design'
import { type CraftingDisplay, fillDisplay, pickText } from '~/utils/craftinghosting'

// Ersteinrichtung des Servers: 3D-Block mit zwei kreisenden Funken, Schritte und Texte kommen von der API.
const props = defineProps<{ setup: NonNullable<CraftingDisplay['setup']> }>()
const emit = defineEmits<{ retry: [] }>()
const store = useCraftingHostingStore()
const motion = useMotion()

const stage = useTemplateRef<HTMLElement>('stage')
const text = (v: Record<string, string>) => fillDisplay(pickText(v, currentLocale.value), 0, intlLocale())
const now = ref(Date.now())
const elapsed = computed(() => {
  const at = props.setup.startedAt ? Date.parse(props.setup.startedAt) : Number.NaN
  return Number.isFinite(at) ? hostingCountdown(Math.max(0, (now.value - at) / 1000)) : ''
})

const hash2 = (x: number, y: number, s: number) => {
  const v = Math.sin(x * 127.1 + y * 311.7 + s * 74.7) * 43758.5453
  return v - Math.floor(v)
}
const PALETTES = {
  red: ['#6e0d09', '#8f120c', '#b3180f', '#cf2216', '#e83322', '#ff5a48'],
  ice: ['#2a66a6', '#3a80c4', '#4d9ade', '#6bb6f0', '#94d2fb', '#c9ecff'],
  slate: ['#17171d', '#1e1e26', '#262630', '#2f2f3b', '#3a3a48', '#4a4a5a'],
  frost: ['#16294a', '#1d3660', '#274676', '#33598f', '#4470ac', '#6f9ad0'],
} as const
type Tex = keyof typeof PALETTES

/** Textur einer Fläche: grobe und feine Körner, Kante, Glanzpunkte. */
function texture(kind: Tex, seed: number): HTMLCanvasElement {
  const n = 128
  const c = document.createElement('canvas')
  c.width = c.height = n
  const g = c.getContext('2d')
  if (!g) return c
  const P = PALETTES[kind]
  for (let y = 0; y < n; y += 16) for (let x = 0; x < n; x += 16) {
    g.fillStyle = P[1 + Math.floor(hash2(x, y, seed) * 4)]!
    g.fillRect(x, y, 16, 16)
  }
  for (let y = 0; y < n; y += 4) for (let x = 0; x < n; x += 4) {
    const r = hash2(x, y, seed + 9)
    if (r <= 0.62) continue
    g.globalAlpha = r > 0.93 ? 0.9 : 0.35
    g.fillStyle = r > 0.93 ? P[5] : r > 0.8 ? P[4] : P[0]
    g.fillRect(x, y, 4, 4)
  }
  g.globalAlpha = 1
  if (kind === 'red' || kind === 'ice') {
    for (let i = 0; i < 9; i++) {
      const x = 14 + hash2(i, 1, seed) * 100
      const y = 14 + hash2(i, 2, seed) * 100
      const s = 6 + hash2(i, 3, seed) * 8
      const gr = g.createRadialGradient(x, y, 0, x, y, s * 1.8)
      gr.addColorStop(0, kind === 'red' ? 'rgba(255,190,170,.9)' : 'rgba(255,255,255,.9)')
      gr.addColorStop(1, 'rgba(255,255,255,0)')
      g.fillStyle = gr
      g.fillRect(x - s * 2, y - s * 2, s * 4, s * 4)
      g.fillStyle = kind === 'red' ? '#ffd9cf' : '#ffffff'
      g.fillRect(Math.round(x / 4) * 4, Math.round(y / 4) * 4, 4, 4)
    }
    g.fillStyle = kind === 'red' ? '#3b0806' : '#16406f'
    g.fillRect(0, 0, n, 8)
    g.fillRect(0, n - 8, n, 8)
    g.fillRect(0, 0, 8, n)
    g.fillRect(n - 8, 0, 8, n)
    g.fillStyle = 'rgba(255,255,255,.22)'
    g.fillRect(8, 8, n - 16, 3)
    g.fillRect(8, 8, 3, n - 16)
    g.fillStyle = 'rgba(0,0,0,.3)'
    g.fillRect(8, n - 11, n - 16, 3)
    g.fillRect(n - 11, 8, 3, n - 16)
  } else {
    g.fillStyle = 'rgba(0,0,0,.4)'
    for (let y = 0; y < n; y += 32) {
      g.fillRect(0, y, n, 2)
      for (let x = ((y / 32) % 2) * 32; x < n; x += 64) g.fillRect(x, y, 2, 32)
    }
    if (kind === 'frost') {
      g.fillStyle = 'rgba(234,244,255,.85)'
      for (let x = 0; x < n; x += 4) g.fillRect(x, 0, 4, 6 + Math.round(hash2(x, 0, seed) * 3) * 4)
    }
  }
  return c
}

interface Face {
  shade: HTMLElement
  shine: HTMLElement
  n: [number, number, number]
}
const FACES: [string, number, number, number][] = [
  ['translateZ', 0, 0, 1],
  ['rotateY(180deg) translateZ', 0, 0, -1],
  ['rotateY(90deg) translateZ', 1, 0, 0],
  ['rotateY(-90deg) translateZ', -1, 0, 0],
  ['rotateX(90deg) translateZ', 0, -1, 0],
  ['rotateX(-90deg) translateZ', 0, 1, 0],
]
function makeBox(w: number, h: number, d: number, kind: Tex, seed: number) {
  const el = document.createElement('div')
  el.className = 'ch3-box'
  const dims = [[w, h, d], [w, h, d], [d, h, w], [d, h, w], [w, d, h], [w, d, h]] as const
  const faces: Face[] = FACES.map(([tr, nx, ny, nz], i) => {
    const [fw, fh, off] = dims[i]!
    const f = document.createElement('div')
    f.className = 'ch3-face'
    Object.assign(f.style, { width: `${fw}px`, height: `${fh}px`, margin: `${-fh / 2}px 0 0 ${-fw / 2}px`, transform: `${tr}(${off / 2}px)` })
    const shade = document.createElement('u')
    const shine = document.createElement('s')
    f.append(texture(kind, seed + i), shade, shine)
    el.appendChild(f)
    return { shade, shine, n: [nx, ny, nz] }
  })
  return { el, faces }
}
const LIGHT = (() => {
  const v = [-0.45, -0.75, 0.5]
  const m = Math.hypot(...v)
  return v.map((x) => x / m)
})()
function light(faces: Face[], transform: string, emissive: number) {
  const M = new DOMMatrix(transform)
  for (const f of faces) {
    const p = M.transformPoint(new DOMPoint(f.n[0], f.n[1], f.n[2], 0))
    const b = Math.max(0, p.x * LIGHT[0]! + p.y * LIGHT[1]! + p.z * LIGHT[2]!)
    f.shade.style.opacity = String((1 - b) * (0.62 - emissive * 0.25))
    f.shine.style.opacity = String(Math.max(0, b - 0.55) * 1.1)
  }
}

let raf = 0
let clock: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  clock = setInterval(() => (now.value = Date.now()), 1000)
  const root = stage.value
  if (!root) return
  const winter = documentDesign() === 'winter'
  root.dataset.look = winter ? 'ice' : 'red'
  const world = root.querySelector<HTMLElement>('.ch3-world')
  if (!world) return
  const S = 0.78
  const slab = makeBox(230 * S, 26 * S, 230 * S, winter ? 'frost' : 'slate', 11)
  slab.el.style.transform = `translateY(${118 * S}px)`
  const blob = document.createElement('div')
  blob.className = 'ch3-blob'
  const cube = makeBox(112 * S, 112 * S, 112 * S, winter ? 'ice' : 'red', 1)
  world.append(slab.el, blob, cube.el)
  const orbs = [
    { r: 150 * S, tiltX: 74, tiltZ: -16, sp: 2.4, ph: 0, size: 18 },
    { r: 128 * S, tiltX: 66, tiltZ: 24, sp: -1.9, ph: 2.1, size: 14 },
  ].map((o) => {
    const M = new DOMMatrix(`rotateZ(${o.tiltZ}deg) rotateX(${o.tiltX}deg)`)
    const ring = document.createElement('div')
    ring.className = 'ch3-ring'
    Object.assign(ring.style, { width: `${o.r * 2}px`, height: `${o.r * 2}px`, margin: `${-o.r}px`, transform: M.toString() })
    world.appendChild(ring)
    const ghosts = Array.from({ length: 14 }, (_, i) => {
      const gEl = document.createElement('div')
      gEl.className = 'ch3-ghost'
      const sz = o.size * (0.8 - i * 0.045)
      Object.assign(gEl.style, { width: `${sz}px`, height: `${sz}px`, margin: `${-sz / 2}px`, opacity: String(0.6 - i * 0.04) })
      world.appendChild(gEl)
      return gEl
    })
    const head = document.createElement('div')
    head.className = 'ch3-spark'
    Object.assign(head.style, { width: `${o.size}px`, height: `${o.size}px`, margin: `${-o.size / 2}px` })
    world.appendChild(head)
    return { ...o, M, head, ghosts }
  })
  const calm = motion.reduced.value
  light(slab.faces, 'rotateX(-16deg)', 0)
  const frame = (t: number) => {
    const T = calm ? 0.6 : t / 1000
    const failed = props.setup.failed
    const pace = failed ? 0 : [0.7, 1.3, 1, 0.5][props.setup.index] ?? 1
    const bob = Math.sin(T * 1.6) * 7
    const tr = `translateY(${bob - 6}px) rotateX(${Math.sin(T * 0.7) * 9}deg) rotateY(${T * 42 * (pace || 0.15)}deg) rotateZ(${Math.sin(T * 0.5) * 5}deg)`
    cube.el.style.transform = tr
    light(cube.faces, `rotateX(-16deg) ${tr}`, 0.6 + 0.2 * Math.sin(T * 3))
    blob.style.transform = `translateY(${104 * S + 0.5}px) rotateX(90deg) scale(${1 - bob / 60})`
    for (const o of orbs) {
      const at = (a: number) => o.M.transformPoint(new DOMPoint(Math.cos(a) * o.r, Math.sin(a) * o.r, 0))
      const a = T * o.sp * (pace || 0.2) + o.ph
      const p = at(a)
      o.head.style.transform = `translate3d(${p.x}px,${p.y}px,${p.z}px)`
      o.ghosts.forEach((gEl, i) => {
        const q = at(a - (i + 1) * 0.075 * Math.sign(o.sp))
        gEl.style.transform = `translate3d(${q.x}px,${q.y}px,${q.z}px)`
      })
    }
    if (!calm) raf = requestAnimationFrame(frame)
  }
  raf = requestAnimationFrame(frame)
})
onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  if (clock) clearInterval(clock)
})
</script>

<template>
  <section class="ch3 card relative overflow-hidden" :class="{ 'ch3-failed': setup.failed }" data-testid="hosting-setup">
    <div ref="stage" class="ch3-stage" aria-hidden="true">
      <div class="ch3-view"><div class="ch3-world" /></div>
    </div>
    <div class="relative px-6 pb-6 text-center mobile:px-4">
      <h2 class="display text-2xl text-base-50" role="status" data-testid="hosting-setup-title">{{ text(setup.title) }}</h2>
      <p class="mt-1 text-sm text-base-200">{{ text(setup.hint) }}</p>
      <ol class="mt-4 flex flex-wrap justify-center gap-1.5">
        <li
          v-for="(s, i) in setup.steps"
          :key="i"
          class="flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs transition-colors"
          :class="
            setup.failed && i === setup.index
              ? 'border-redstone-700 text-redstone-300'
              : i < setup.index
                ? 'border-emerald-700/60 text-emerald-300'
                : i === setup.index
                  ? 'border-redstone-500/70 bg-redstone-500/10 text-base-50'
                  : 'border-base-800 text-base-400'
          "
        >
          <svg v-if="i < setup.index" viewBox="0 0 10 10" class="size-2.5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 5.3l2 2 4-4.6" /></svg>
          <span v-else-if="i === setup.index && !setup.failed" class="ch3-dot" aria-hidden="true" />
          {{ text(s) }}
        </li>
      </ol>
      <button v-if="setup.failed" class="btn btn-primary mt-5" :disabled="!!store.busy" data-testid="hosting-setup-retry" @click="emit('retry')">{{ text(setup.retry) }}</button>
      <p v-else class="mt-4 text-xs text-base-400">
        {{ text(setup.locked) }}<template v-if="elapsed"> · <span class="tabular-nums">{{ elapsed }}</span></template>
      </p>
    </div>
  </section>
</template>

<style>
.ch3-stage { position: relative; height: 330px; --ch3-a: #ff3b2f; --ch3-hi: #ffe1da; --ch3-ring: rgba(255, 80, 60, 0.28); --ch3-halo: rgba(224, 40, 30, 0.2); background: radial-gradient(60% 60% at 50% 46%, var(--ch3-halo), transparent 72%); }
.ch3-stage[data-look='ice'] { --ch3-a: #7fd3ff; --ch3-hi: #ffffff; --ch3-ring: rgba(143, 220, 255, 0.3); --ch3-halo: rgba(95, 196, 245, 0.2); }
.ch3-view { position: absolute; inset: 0; perspective: 1000px; perspective-origin: 50% 38%; }
.ch3-world { position: absolute; left: 50%; top: 44%; transform-style: preserve-3d; transform: rotateX(-16deg); }
.ch3-world * { transform-style: preserve-3d; }
.ch3-box { position: absolute; }
.ch3-face { position: absolute; left: 50%; top: 50%; overflow: hidden; backface-visibility: hidden; }
.ch3-face canvas { position: absolute; inset: 0; width: 100%; height: 100%; }
.ch3-face u { position: absolute; inset: 0; background: #000; opacity: 0; }
.ch3-face s { position: absolute; inset: 0; background: linear-gradient(135deg, rgba(255, 255, 255, 0.55), transparent 55%); opacity: 0; mix-blend-mode: screen; }
.ch3-ring { position: absolute; border-radius: 50%; border: 1.5px solid var(--ch3-ring); }
.ch3-spark { position: absolute; border-radius: 50%; background: radial-gradient(circle at 38% 32%, #fff 0 20%, var(--ch3-hi) 35%, var(--ch3-a) 72%); box-shadow: 0 0 18px 5px var(--ch3-a); }
.ch3-ghost { position: absolute; border-radius: 50%; background: var(--ch3-a); box-shadow: 0 0 8px var(--ch3-a); }
.ch3-blob { position: absolute; width: 120px; height: 120px; margin: -60px; border-radius: 50%; background: radial-gradient(rgba(0, 0, 0, 0.6), transparent 68%); }
.ch3-failed .ch3-stage { filter: grayscale(0.85) brightness(0.7); }
.ch3-dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; animation: ch3-pulse 1.1s ease-in-out infinite; }
@keyframes ch3-pulse { 50% { opacity: 0.3; } }
:root[data-reduced-motion] .ch3-dot { animation: none; }
</style>
