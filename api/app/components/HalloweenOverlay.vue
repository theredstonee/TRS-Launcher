<script setup lang="ts">
// Halloween 2026 auf jeder Seite (nur solange das Event global an ist): Filmkorn + Vignette, Aufflackern und blutige
// Handabdrücke nach Blitzen, krabbelnde Spinnen, Flüstern, rote Augen unten links (folgen der Maus, verstecken sich bei
// Annäherung) und der Erst-Hinweis „Flackern & Rauschen“. Alles ohne Zeigerereignisse (außer dem Hinweis). Nur mit
// Event-Look (§32.6); die Meldungen der Kürbis-Suche zeigt HalloweenToast (gehören zum Event, nicht zum Look). „Bewegung reduzieren“: nur die Vignette, nichts bewegt sich, nichts flackert.
const hw = useHalloween()
const reduced = useReducedMotion()

const grain = useTemplateRef<HTMLCanvasElement>('grain')
const eyes = useTemplateRef<HTMLDivElement>('eyes')
const eyesOn = ref(false)
const blink = ref(false)
const pupil = ref({ x: 0, y: 0 })
const whispers = ref<{ id: number, text: string, left: string, top: string }[]>([])
const crawlers = ref<{ id: number, left: number, top: number, kf: Keyframe[], dur: number }[]>([])
let seq = 0
const timers: ReturnType<typeof setTimeout>[] = []
let grainTimer: ReturnType<typeof setInterval> | undefined
let blinkTimer: ReturnType<typeof setInterval> | undefined
let eyesBack: ReturnType<typeof setTimeout> | undefined

const grainOn = computed(() => hw.flicker.value && !reduced.value)

function later(ms: number, fn: () => void) {
  timers.push(setTimeout(fn, ms))
}

function drawGrain() {
  const g = grain.value?.getContext('2d')
  if (!g) return
  const im = g.createImageData(160, 90)
  for (let i = 0; i < im.data.length; i += 4) {
    const v = Math.random() * 255
    im.data[i] = v
    im.data[i + 1] = v
    im.data[i + 2] = v
    im.data[i + 3] = 255
  }
  g.putImageData(im, 0, 0)
}

function syncGrain() {
  clearInterval(grainTimer)
  grainTimer = undefined
  if (grainOn.value) grainTimer = setInterval(drawGrain, 90)
}

const HAND = '<svg viewBox="0 0 60 80"><g fill="#6a0d14" opacity=".85"><ellipse cx="30" cy="54" rx="16" ry="18"/><rect x="13" y="18" width="7" height="30" rx="3.5"/><rect x="22" y="8" width="7" height="36" rx="3.5"/><rect x="31" y="6" width="7" height="38" rx="3.5"/><rect x="40" y="14" width="7" height="32" rx="3.5"/><rect x="2" y="44" width="16" height="7" rx="3.5" transform="rotate(-35 10 47)"/><rect x="24" y="70" width="3" height="10" rx="1.5"/><rect x="35" y="68" width="2" height="7" rx="1"/></g></svg>'

/** Blutiger Handabdruck auf einem sichtbaren Rahmen (`data-hw-frame`). Fester SVG-Inhalt, keine Nutzereingaben. */
function handprint() {
  const f = [...document.querySelectorAll<HTMLElement>('[data-hw-frame]')].find((e) => {
    const r = e.getBoundingClientRect()
    return r.top < innerHeight && r.bottom > 0 && r.width > 0
  })
  if (!f) return
  const h = document.createElement('div')
  h.className = 'hw-hand'
  h.style.left = `${10 + Math.random() * 70}%`
  h.style.top = `${10 + Math.random() * 50}%`
  h.style.transform = `rotate(${(Math.random() - 0.5) * 50}deg)`
  h.innerHTML = HAND
  f.append(h)
  setTimeout(() => h.remove(), 4100)
}

function onBolt() {
  if (reduced.value) return
  if (Math.random() < 0.45) handprint()
  if (!hw.flicker.value) return
  const el = document.documentElement
  el.classList.remove('hw-flash')
  void el.offsetWidth
  el.classList.add('hw-flash')
  later(600, () => el.classList.remove('hw-flash'))
}

function crawler() {
  if (!reduced.value) {
    const fromTop = Math.random() < 0.5
    const x0 = Math.random() * innerWidth
    const dur = 7000 + Math.random() * 5000
    const dx = (Math.random() - 0.5) * 400
    const dy = fromTop ? innerHeight * (0.3 + Math.random() * 0.4) : 0
    const ex = fromTop ? dx : (Math.random() < 0.5 ? -x0 - 40 : innerWidth - x0 + 40)
    const kf: Keyframe[] = [
      { transform: 'translate(0,0)' },
      { transform: `translate(${ex * 0.4}px, ${dy * 0.6}px)`, offset: 0.45 },
      { transform: `translate(${ex * 0.4}px, ${dy * 0.6}px)`, offset: 0.6 },
      { transform: `translate(${ex}px, ${fromTop ? -40 : 0}px)` },
    ]
    const id = ++seq
    crawlers.value.push({ id, left: x0, top: fromTop ? -20 : innerHeight - 18, kf, dur })
    later(dur + 100, () => {
      crawlers.value = crawlers.value.filter((c) => c.id !== id)
    })
  }
  later(6000 + Math.random() * 8000, crawler)
}

function startCrawl(el: unknown, c: { kf: Keyframe[], dur: number }) {
  if (!(el instanceof HTMLCanvasElement) || el.dataset.ready) return
  el.dataset.ready = '1'
  drawSprite(el, 'spider')
  el.animate(c.kf, { duration: c.dur, easing: 'steps(60)', fill: 'forwards' })
}

function whisper() {
  if (!reduced.value) {
    const list = hw.t.value.whispers
    const id = ++seq
    whispers.value.push({ id, text: list[Math.floor(Math.random() * list.length)]!, left: `${8 + Math.random() * 70}vw`, top: `${12 + Math.random() * 74}vh` })
    later(4600, () => {
      whispers.value = whispers.value.filter((w) => w.id !== id)
    })
  }
  later(9000 + Math.random() * 9000, whisper)
}

function onMouse(e: MouseEvent) {
  const el = eyes.value
  if (!el) return
  const r = el.getBoundingClientRect()
  const dx = e.clientX - (r.left + r.width / 2)
  const dy = e.clientY - (r.top + r.height / 2)
  const d = Math.hypot(dx, dy) || 1
  pupil.value = { x: (dx / d) * 4, y: (dy / d) * 1 }
  if (d < 170) {
    eyesOn.value = false
    clearTimeout(eyesBack)
    eyesBack = setTimeout(() => {
      eyesOn.value = true
    }, 7000)
  }
}

watch(grainOn, () => {
  void nextTick(syncGrain)
})

onMounted(() => {
  hw.loadPrefs()
  void nextTick(syncGrain)
  window.addEventListener('trs-bolt', onBolt)
  window.addEventListener('mousemove', onMouse, { passive: true })
  later(6000, () => {
    eyesOn.value = true
  })
  blinkTimer = setInterval(() => {
    blink.value = true
    setTimeout(() => {
      blink.value = false
    }, 140)
  }, 3800)
  later(2500, crawler)
  later(5000, whisper)
})
onBeforeUnmount(() => {
  for (const t of timers) clearTimeout(t)
  clearInterval(grainTimer)
  clearInterval(blinkTimer)
  clearTimeout(eyesBack)
  window.removeEventListener('trs-bolt', onBolt)
  window.removeEventListener('mousemove', onMouse)
  document.documentElement.classList.remove('hw-flash')
})
</script>

<template>
  <div class="hw-overlay" aria-hidden="true">
    <div class="vignette" />
    <canvas v-if="grainOn" ref="grain" class="grain" width="160" height="90" />
  </div>
  <div ref="eyes" class="hw-watch" :class="{ on: eyesOn && !reduced, blink }" aria-hidden="true">
    <i><b :style="{ transform: `translate(${pupil.x}px, ${pupil.y}px)` }" /></i>
    <i><b :style="{ transform: `translate(${pupil.x}px, ${pupil.y}px)` }" /></i>
  </div>
  <div class="hw-fx" aria-hidden="true">
    <canvas
      v-for="c in crawlers"
      :key="c.id"
      :ref="(el: unknown) => startCrawl(el, c)"
      class="hw-crawl"
      width="7"
      height="6"
      :style="{ left: `${c.left}px`, top: `${c.top}px` }"
    />
    <div v-for="w in whispers" :key="w.id" class="hw-whisper" :style="{ left: w.left, top: w.top }">{{ w.text }}</div>
  </div>

  <ClientOnly>
    <div v-if="!hw.hintSeen.value" class="hw-hint" role="dialog" :aria-label="hw.t.value.flickerTitle">
      <div class="flex items-start gap-3">
        <span class="text-2xl leading-none" aria-hidden="true">⚡</span>
        <div>
          <p class="hw-hint-title">{{ hw.t.value.flickerTitle }}</p>
          <p class="mt-1 text-sm text-base-400">{{ hw.t.value.flickerHint }}</p>
          <div class="mt-3 flex flex-wrap gap-2">
            <button type="button" class="btn btn-ghost" @click="hw.setFlicker(false); hw.dismissHint()">{{ hw.t.value.flickerOff }}</button>
            <button type="button" class="btn btn-ghost" @click="hw.dismissHint()">{{ hw.t.value.flickerKeep }}</button>
          </div>
        </div>
      </div>
    </div>
  </ClientOnly>
</template>

<style scoped>
.hw-overlay {
  position: fixed;
  inset: 0;
  z-index: 30;
  pointer-events: none;
}
.vignette {
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse at center, transparent 45%, rgb(0 0 0 / 0.75) 100%);
}
.grain {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0.045;
  mix-blend-mode: screen;
}
.hw-watch {
  position: fixed;
  left: 26px;
  bottom: 26px;
  z-index: 31;
  display: flex;
  gap: 10px;
  opacity: 0;
  transition: opacity 1.5s;
  pointer-events: none;
}
.hw-watch.on {
  opacity: 0.9;
}
.hw-watch i {
  position: relative;
  width: 16px;
  height: 9px;
  overflow: hidden;
  border-radius: 50%;
  background: radial-gradient(circle at 50% 50%, #ffb3a8, #c9241b 70%);
  box-shadow: 0 0 14px rgb(255 40 30 / 0.7);
  transition: height 0.12s;
}
.hw-watch i b {
  position: absolute;
  top: 1px;
  left: 6px;
  width: 4px;
  height: 7px;
  border-radius: 50%;
  background: #120000;
}
.hw-watch.blink i {
  height: 1px;
}
.hw-fx {
  position: fixed;
  inset: 0;
  z-index: 41;
  overflow: hidden;
  pointer-events: none;
}
.hw-crawl {
  position: absolute;
  width: 16px;
  height: 14px;
  image-rendering: pixelated;
}
.hw-whisper {
  position: absolute;
  font-family: "Cormorant Garamond", Georgia, serif;
  font-style: italic;
  font-size: 22px;
  letter-spacing: 0.08em;
  color: rgb(220 180 185 / 0);
  text-shadow: 0 0 8px rgb(201 66 74 / 0.6);
  filter: blur(0.4px);
  animation: whisper 4.5s ease-in-out forwards;
}
@keyframes whisper {
  0% { color: rgb(220 180 185 / 0); transform: translateY(6px); }
  30%, 60% { color: rgb(220 180 185 / 0.75); }
  100% { color: rgb(220 180 185 / 0); transform: translateY(-8px); filter: blur(3px); }
}
.hw-hint {
  position: fixed;
  left: 16px;
  top: 76px;
  z-index: 45;
  width: min(380px, calc(100vw - 32px));
  padding: 16px;
  border: 1px solid rgb(201 66 74 / 0.45);
  border-radius: 14px;
  background: rgb(20 16 33 / 0.95);
  box-shadow: 0 18px 40px rgb(0 0 0 / 0.6);
  animation: rise 0.5s ease;
}
.hw-hint-title {
  font-family: "IM Fell English SC", Georgia, serif;
  font-size: 19px;
  color: #f3f5fb;
}
@keyframes rise {
  from { transform: translateY(10px); opacity: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .hw-hint { animation: none; }
}
</style>
