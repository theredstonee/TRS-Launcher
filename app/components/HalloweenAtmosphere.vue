<script setup lang="ts">
import { gap, HANDPRINT_CHANCE, onBolt, whispersFor } from '~/utils/moonnight/bus'
import { drawSprite } from '~/utils/moonnight/sprites'

// Halloween 2026 über jeder Seite (Stufe „Horror“ aus dem Entwurf): Filmkorn + Vignette,
// Blut-Tropfen an der Oberkante, krabbelnde und hängende Spinnen, Flüstern, blutige
// Handabdrücke nach Blitzen, rote Augen unten links (folgen der Maus, verstecken sich
// bei Annäherung) und Fledermäuse quer über das Fenster. Alles `pointer-events: none`
// außer dem Erst-Hinweis. „Animationen aus“: nur Vignette, stille Tropfen und Augen.
const settings = useSettingsStore()

const look = useHalloweenLook()
const reduced = look.reduced

const grain = useTemplateRef<HTMLCanvasElement>('grain')
const layer = useTemplateRef<HTMLDivElement>('layer')
const eyes = useTemplateRef<HTMLDivElement>('eyes')
const hanging = useTemplateRef<HTMLCanvasElement>('hanging')

interface Drip { left: number; h: number; d: number; delay: number; w: number }
const drips: Drip[] = Array.from({ length: 14 }, () => ({
  left: 3 + Math.random() * 94,
  h: 8 + Math.random() * 34,
  d: 5 + Math.random() * 9,
  delay: -Math.random() * 10,
  w: 2 + Math.random() * 4,
}))

interface Whisper { id: number; text: string; left: number; top: number }
interface Hand { id: number; left: number; top: number; rot: number }
const whispers = ref<Whisper[]>([])
const hands = ref<Hand[]>([])
let seq = 0

const eyesOn = ref(false)
const blink = ref(false)
const pupil = ref({ x: 0, y: 0 })

// ---------- Erst-Hinweis „Flackern & Rauschen“ ----------
const hintOpen = computed(() => !!settings.current && settings.current.ui.halloweenFlickerHintSeen !== true)
const savingHint = ref(false)
async function closeHint(off: boolean) {
  const base = settings.current
  if (!base || savingHint.value) return
  savingHint.value = true
  try {
    await settings.save({ ...base, ui: { ...base.ui, halloweenFlickerHintSeen: true, halloweenFlicker: off ? false : base.ui.halloweenFlicker } })
  } catch (e) {
    useToasts().error(e)
  } finally {
    savingHint.value = false
  }
}

// ---------- Timer (pausieren, solange das Fenster versteckt ist) ----------
const timers = new Set<ReturnType<typeof setTimeout>>()
function later(ms: number, fn: () => void) {
  const id = setTimeout(() => {
    timers.delete(id)
    fn()
  }, ms)
  timers.add(id)
}
const active = () => !reduced.value && !document.hidden

function crawler() {
  if (active() && layer.value) {
    const c = document.createElement('canvas')
    c.className = 'hw-crawl'
    c.width = 7
    c.height = 6
    drawSprite(c, 'spider')
    const W = innerWidth
    const H = innerHeight
    const fromTop = Math.random() < 0.5
    const x0 = Math.random() * W
    const dur = 7000 + Math.random() * 5000
    c.style.left = `${x0}px`
    c.style.top = fromTop ? '-20px' : `${H - 18}px`
    layer.value.append(c)
    const dx = (Math.random() - 0.5) * 400
    const dy = fromTop ? H * (0.3 + Math.random() * 0.4) : 0
    const ex = fromTop ? dx : Math.random() < 0.5 ? -x0 - 40 : W - x0 + 40
    const anim = c.animate(
      [
        { transform: 'translate(0,0)' },
        { transform: `translate(${ex * 0.4}px, ${dy * 0.6}px)`, offset: 0.45 },
        { transform: `translate(${ex * 0.4}px, ${dy * 0.6}px)`, offset: 0.6 },
        { transform: `translate(${ex}px, ${fromTop ? -40 : 0}px)` },
      ],
      { duration: dur, easing: 'steps(60)' },
    )
    anim.onfinish = () => c.remove()
    anim.oncancel = () => c.remove()
  }
  later(gap(6000, 8000), crawler)
}

function whisper() {
  if (active()) {
    const list = whispersFor(currentLocale.value)
    const w: Whisper = { id: ++seq, text: list[Math.floor(Math.random() * list.length)] ?? '', left: 8 + Math.random() * 70, top: 12 + Math.random() * 74 }
    whispers.value.push(w)
    later(4600, () => (whispers.value = whispers.value.filter((x) => x.id !== w.id)))
  }
  later(gap(9000, 9000), whisper)
}

function pageBats() {
  if (active() && layer.value) {
    const n = 2 + Math.floor(Math.random() * 4)
    const ltr = Math.random() < 0.5
    const y = 80 + Math.random() * Math.max(40, innerHeight - 260)
    for (let i = 0; i < n; i++) {
      const c = document.createElement('canvas')
      c.className = 'hw-bat'
      c.width = 12
      c.height = 8
      drawSprite(c, 'bat')
      const x0 = ltr ? -60 - i * 40 : innerWidth + 40 + i * 40
      const x1 = ltr ? innerWidth + 80 : -80
      c.style.setProperty('--x0', `${x0}px`)
      c.style.setProperty('--x1', `${x1}px`)
      c.style.setProperty('--y0', `${y + (Math.random() - 0.5) * 50}px`)
      c.style.setProperty('--y1', `${y - 40 + (Math.random() - 0.5) * 80}px`)
      c.style.setProperty('--d', `${5 + Math.random() * 3}s`)
      c.style.animationDelay = `${i * 0.25}s`
      layer.value.append(c)
      requestAnimationFrame(() => c.classList.add('go'))
      setTimeout(() => c.remove(), 10000)
    }
  }
  later(gap(9000, 9000), pageBats)
}

function handprint() {
  const h: Hand = { id: ++seq, left: 10 + Math.random() * 70, top: 10 + Math.random() * 50, rot: (Math.random() - 0.5) * 50 }
  hands.value.push(h)
  later(4100, () => (hands.value = hands.value.filter((x) => x.id !== h.id)))
}

function flash() {
  const body = document.body
  body.classList.remove('hw-flash')
  void body.offsetWidth
  body.classList.add('hw-flash')
  later(520, () => body.classList.remove('hw-flash'))
}

// ---------- Filmkorn ----------
let grainTimer: ReturnType<typeof setInterval> | undefined
function grainOn(on: boolean) {
  clearInterval(grainTimer)
  grainTimer = undefined
  if (!on) return
  const g = grain.value?.getContext('2d')
  if (!g) return
  const img = g.createImageData(160, 90)
  grainTimer = setInterval(() => {
    if (document.hidden) return
    for (let i = 0; i < img.data.length; i += 4) {
      const v = Math.random() * 255
      img.data[i] = v
      img.data[i + 1] = v
      img.data[i + 2] = v
      img.data[i + 3] = 255
    }
    g.putImageData(img, 0, 0)
  }, 90)
}

// ---------- Augen in der Ecke ----------
let eyesTimer: ReturnType<typeof setTimeout> | undefined
let blinkTimer: ReturnType<typeof setInterval> | undefined
function onMove(e: MouseEvent) {
  const el = eyes.value
  if (!el) return
  const r = el.getBoundingClientRect()
  const dx = e.clientX - (r.left + r.width / 2)
  const dy = e.clientY - (r.top + r.height / 2)
  const d = Math.hypot(dx, dy)
  pupil.value = { x: (dx / (d || 1)) * 4, y: (dy / (d || 1)) * 1 }
  if (d < 170) {
    eyesOn.value = false
    clearTimeout(eyesTimer)
    eyesTimer = setTimeout(() => (eyesOn.value = true), 7000)
  }
}

let offBolt: (() => void) | undefined
onMounted(() => {
  if (hanging.value) drawSprite(hanging.value, 'spider')
  offBolt = onBolt(() => {
    if (Math.random() < HANDPRINT_CHANCE) handprint()
    if (look.flicker.value) flash()
  })
  later(2500, crawler)
  later(5000, whisper)
  later(1500, pageBats)
  eyesTimer = setTimeout(() => (eyesOn.value = true), 6000)
  blinkTimer = setInterval(() => {
    if (reduced.value || document.hidden) return
    blink.value = true
    setTimeout(() => (blink.value = false), 140)
  }, 3800)
  addEventListener('mousemove', onMove, { passive: true })
  grainOn(look.flicker.value)
})
watch(look.flicker, grainOn)
onBeforeUnmount(() => {
  offBolt?.()
  for (const id of timers) clearTimeout(id)
  timers.clear()
  clearTimeout(eyesTimer)
  clearInterval(blinkTimer)
  grainOn(false)
  removeEventListener('mousemove', onMove)
  document.body.classList.remove('hw-flash')
})
</script>

<template>
  <div ref="layer" class="hw-atmosphere" :class="{ still: reduced }" aria-hidden="true">
    <div class="horror">
      <canvas v-show="look.flicker.value" ref="grain" width="160" height="90" />
    </div>
    <div class="drips">
      <span
        v-for="(d, i) in drips"
        :key="i"
        :style="{ left: `${d.left}%`, width: `${d.w}px`, '--h': `${d.h}px`, '--d': `${d.d}s`, animationDelay: `${d.delay}s` }"
      />
    </div>
    <div class="dangle" style="left: 64%; --len: 64px"><i /><canvas ref="hanging" width="7" height="6" /></div>
    <div v-for="w in whispers" :key="w.id" class="whisper hw-italic" :style="{ left: `${w.left}vw`, top: `${w.top}vh` }">{{ w.text }}</div>
    <!-- Blutiger Handabdruck: einmal definiert, jeder Abdruck verweist darauf. Fingerglieder als einzelne Flecken
         (Lücken an den Gelenkfalten), Ränder per Rauschen verschmiert, dazu Tropfen und Spritzer. -->
    <svg width="0" height="0" class="absolute" aria-hidden="true">
      <defs>
        <radialGradient id="hw-hp-g" cx="50%" cy="62%" r="62%"><stop offset="0" stop-color="#8e0f18" /><stop offset=".7" stop-color="#6e0a12" /><stop offset="1" stop-color="#4f070d" /></radialGradient>
        <filter id="hw-hp-f" x="-10%" y="-10%" width="120%" height="120%">
          <feTurbulence type="fractalNoise" baseFrequency="0.7" numOctaves="2" seed="3" result="n" />
          <feDisplacementMap in="SourceGraphic" in2="n" scale="3.4" />
        </filter>
        <symbol id="hw-hp" viewBox="0 0 100 136">
          <path d="M26 68 C23 84 28 101 36 112 C44 122 62 124 72 114 C80 104 83 86 81 66 C78 59 70 58 64 60 C58 57 50 56 44 59 C39 57 31 59 28 63 C27 64.5 26.4 66 26 68 Z" />
          <g transform="rotate(-10 36 60)"><ellipse cx="36" cy="52" rx="5.8" ry="7.6" /><ellipse cx="36" cy="38.5" rx="5.5" ry="7" /><ellipse cx="36" cy="25.5" rx="5.2" ry="7" /></g>
          <g transform="rotate(-2 50 58)"><ellipse cx="50" cy="50" rx="6.1" ry="8.2" /><ellipse cx="50" cy="34.5" rx="5.8" ry="7.6" /><ellipse cx="50" cy="19.5" rx="5.5" ry="7.6" /></g>
          <g transform="rotate(7 63 59)"><ellipse cx="63" cy="51" rx="5.8" ry="7.8" /><ellipse cx="63" cy="36.5" rx="5.5" ry="7.1" /><ellipse cx="63" cy="22.5" rx="5.2" ry="7.1" /></g>
          <g transform="rotate(16 75 63)"><ellipse cx="75" cy="56" rx="4.9" ry="6.3" /><ellipse cx="75" cy="45" rx="4.6" ry="5.9" /><ellipse cx="75" cy="34.5" rx="4.3" ry="5.8" /></g>
          <g transform="rotate(-46 26 90)"><ellipse cx="26" cy="88" rx="7.2" ry="10" /><ellipse cx="26" cy="71" rx="6.4" ry="8.6" /></g>
          <path d="M44 118 C44 124 43.4 129 44.6 131 C46 129 45.6 124 46 118 Z" /><circle cx="45.3" cy="131.5" r="2.1" />
          <path d="M62 117 C62 121 61.6 124 62.5 126 C63.5 124 63.2 121 63.6 117 Z" /><circle cx="62.6" cy="126.4" r="1.6" />
          <circle cx="88" cy="40" r="1.4" /><circle cx="92" cy="52" r=".9" /><circle cx="14" cy="58" r="1.2" /><circle cx="85" cy="96" r="1" /><circle cx="20" cy="110" r=".8" />
        </symbol>
      </defs>
    </svg>
    <div v-for="h in hands" :key="h.id" class="hand" :style="{ left: `${h.left}%`, top: `${h.top}%`, transform: `rotate(${h.rot}deg)` }">
      <svg viewBox="0 0 100 136"><g fill="url(#hw-hp-g)" filter="url(#hw-hp-f)" opacity=".9"><use href="#hw-hp" /></g></svg>
    </div>
    <div ref="eyes" class="watch" :class="{ on: eyesOn, blink }">
      <i><b :style="{ transform: `translate(${pupil.x}px, ${pupil.y}px)` }" /></i>
      <i><b :style="{ transform: `translate(${pupil.x}px, ${pupil.y}px)` }" /></i>
    </div>
  </div>
  <!-- Erst-Hinweis: einmal, beim ersten Halloween-Start (Einstellung „gesehen“). -->
  <Transition name="hw-hint">
    <section v-if="hintOpen" class="hw-hint card" role="dialog" :aria-label="t('halloween.flickerHint.title')" data-testid="halloween-flicker-hint">
      <div class="flex items-start gap-2.5">
        <div class="text-[22px] leading-none" aria-hidden="true">⚡</div>
        <div class="min-w-0">
          <p class="font-semibold text-base-50">{{ t('halloween.flickerHint.title') }}</p>
          <p class="mt-0.5 text-xs text-base-400">{{ t('halloween.flickerHint.text') }}</p>
          <div class="mt-2.5 flex flex-wrap gap-2">
            <button type="button" class="btn btn-primary h-8 px-3 text-sm" :disabled="savingHint" data-testid="halloween-flicker-off" @click="closeHint(true)">{{ t('halloween.flickerHint.off') }}</button>
            <button type="button" class="btn btn-ghost h-8 px-3 text-sm" :disabled="savingHint" @click="closeHint(false)">{{ t('halloween.flickerHint.keep') }}</button>
          </div>
        </div>
      </div>
    </section>
  </Transition>
</template>

<style scoped>
.hw-atmosphere {
  position: fixed;
  inset: 0;
  z-index: 35;
  pointer-events: none;
  overflow: hidden;
}
.horror {
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse at center, transparent 45%, rgba(0, 0, 0, 0.75) 100%);
}
.horror canvas {
  width: 100%;
  height: 100%;
  opacity: 0.045;
  mix-blend-mode: screen;
}
/* Blut-Tropfen unter der Titelleiste. */
.drips {
  position: absolute;
  left: 0;
  right: 0;
  top: 36px;
  height: 0;
}
:root.is-mobile .drips {
  top: var(--safe-top);
}
.drips span {
  position: absolute;
  top: 0;
  border-radius: 0 0 3px 3px;
  background: linear-gradient(180deg, #3d070c, #7a0f18);
  animation: hw-drip var(--d) ease-in infinite;
}
.drips span::after {
  content: "";
  position: absolute;
  bottom: -4px;
  left: -1px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #7a0f18;
}
.still .drips span {
  animation: none;
  height: calc(var(--h) * 0.5);
}
@keyframes hw-drip {
  0% { height: 0; opacity: 1; }
  70% { height: var(--h); opacity: 1; }
  100% { height: calc(var(--h) + 6px); opacity: 0; }
}
.dangle {
  position: absolute;
  top: 36px;
  width: 14px;
  transform-origin: top center;
  animation: hw-dangle 5s ease-in-out infinite;
}
.dangle i {
  display: block;
  width: 1px;
  margin: 0 auto;
  height: var(--len, 60px);
  background: rgba(207, 213, 227, 0.45);
  animation: hw-drop 6s ease-in-out infinite;
}
.dangle canvas {
  display: block;
  width: 14px;
  height: 12px;
  image-rendering: pixelated;
}
.still .dangle,
.still .dangle i {
  animation: none;
}
@keyframes hw-dangle {
  50% { transform: rotate(4deg); }
}
@keyframes hw-drop {
  50% { height: calc(var(--len, 60px) * 0.55); }
}
.whisper {
  position: absolute;
  font-size: 22px;
  letter-spacing: 0.08em;
  color: rgba(220, 180, 185, 0);
  text-shadow: 0 0 8px rgba(201, 66, 74, 0.6);
  filter: blur(0.4px);
  animation: hw-whisper 4.5s ease-in-out forwards;
}
@keyframes hw-whisper {
  0% { color: rgba(220, 180, 185, 0); transform: translateY(6px); }
  30%, 60% { color: rgba(220, 180, 185, 0.75); }
  100% { color: rgba(220, 180, 185, 0); transform: translateY(-8px); filter: blur(3px); }
}
.hand {
  position: absolute;
  width: 90px;
  opacity: 0;
  animation: hw-hand 4s ease-out forwards;
}
.still .hand {
  animation: none;
  opacity: 0.55;
}
@keyframes hw-hand {
  0% { opacity: 0; }
  4% { opacity: 0.9; }
  70% { opacity: 0.55; }
  100% { opacity: 0; }
}
.watch {
  position: absolute;
  left: 26px;
  bottom: 26px;
  display: flex;
  gap: 10px;
  opacity: 0;
  transition: opacity 1.5s;
}
.watch.on {
  opacity: 0.9;
}
.watch i {
  position: relative;
  width: 16px;
  height: 9px;
  overflow: hidden;
  border-radius: 50%;
  background: radial-gradient(circle at 50% 50%, #ffb3a8, #c9241b 70%);
  box-shadow: 0 0 14px rgba(255, 40, 30, 0.7);
  transition: height 0.12s;
}
.watch i b {
  position: absolute;
  top: 1px;
  left: 6px;
  width: 4px;
  height: 7px;
  border-radius: 50%;
  background: #120000;
}
.watch.blink i {
  height: 1px;
}
.hw-hint {
  position: fixed;
  left: 88px;
  top: 54px;
  z-index: 45;
  width: min(380px, calc(100vw - 32px));
  padding: 14px 16px;
  border-color: rgba(201, 66, 74, 0.45);
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.6);
  pointer-events: auto;
}
:root.is-mobile .hw-hint {
  left: 16px;
  top: calc(var(--safe-top) + 64px);
}
.hw-hint-enter-active,
.hw-hint-leave-active {
  transition: opacity 0.4s ease, transform 0.4s ease;
}
.hw-hint-enter-from,
.hw-hint-leave-to {
  opacity: 0;
  transform: translateY(10px);
}
</style>

<style>
/* Außerhalb des Bausteins erzeugt (Spinnen, Fledermäuse) bzw. am <body>. */
.hw-crawl {
  position: fixed;
  z-index: 41;
  width: 16px;
  height: 14px;
  image-rendering: pixelated;
  pointer-events: none;
}
.hw-bat {
  position: absolute;
  left: 0;
  top: 0;
  width: 30px;
  height: 20px;
  opacity: 0;
  image-rendering: pixelated;
}
.hw-bat.go {
  animation: hw-batfly var(--d, 7s) linear forwards;
}
@keyframes hw-batfly {
  0% { opacity: 0.9; transform: translate(var(--x0), var(--y0)); }
  50% { transform: translate(calc((var(--x0) + var(--x1)) / 2), calc(var(--y0) - 60px)); }
  100% { opacity: 0.9; transform: translate(var(--x1), var(--y1)); }
}
body.hw-flash {
  animation: hw-flash 0.5s linear;
}
@keyframes hw-flash {
  0% { filter: brightness(2.2) contrast(1.2); }
  12% { filter: brightness(0.5); }
  22% { filter: brightness(1.8); }
  100% { filter: none; }
}
</style>
