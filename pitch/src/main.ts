// TRS Launcher – Showcase (trade-fair pitch). Framework-free; runs from file://, any http sub-path and a Tauri webview.
// Query: mode=loop|manual (loop), lang=de|en (de), demo=1 (kiosk CTA), calm=0 (start animation with flicker),
// sound=1 (start with sound), slide=<n> (start slide).
// Embedding API: window.TRSPitch = { play(), pause(), goTo(i), next(), prev(), slides }, event 'trs-pitch:demo'
// on window plus postMessage({ source: 'trs-pitch', type: 'demo' }) to the parent frame.

import { introDesignById } from '../../app/utils/intro/designs'
import { IntroPlayer } from '../../app/utils/intro/player'
import { startBackground } from './bg'
import { setLang, t, type Lang } from './h'
import { SLIDES, type Ctx } from './slides'

declare const __QR__: string
declare const __LOGO__: string

declare global {
  interface Window {
    TRSPitch?: { play(): void; pause(): void; goTo(i: number): void; next(): void; prev(): void; readonly slides: number; readonly current: number; readonly mode: string }
    __TRS_PITCH_JINGLE?: string
  }
}

const q = new URLSearchParams(location.search)
const lang: Lang = q.get('lang') === 'en' ? 'en' : 'de'
const demo = q.get('demo') === '1'
const calm = q.get('calm') !== '0'
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches
let mode: 'loop' | 'manual' = q.get('mode') === 'manual' ? 'manual' : 'loop'
let sound = q.get('sound') === '1'

setLang(lang)
document.documentElement.lang = lang

const $ = <E extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as E
const body = document.body
const stage = $('stage')
const introCv = $<HTMLCanvasElement>('intro')
const progress = $('progress').firstElementChild as HTMLElement
const dots = $('dots')
const counter = $('counter')
const hint = $('hint')
const sweep = $('sweep')
const toast = $('toast')
const pill = $('demo-pill')

const ctx: Ctx = { demo, reduced, qr: __QR__ }
const N = SLIDES.length

// ───────── build DOM ─────────
const els: HTMLElement[] = SLIDES.map((s, i) => {
  const el = document.createElement('section')
  el.className = `slide s-${s.id}`
  el.dataset.i = String(i)
  el.style.setProperty('--kdur', `${s.dur + 1.5}s`)
  el.setAttribute('aria-roledescription', 'slide')
  el.setAttribute('aria-label', `${i + 1} / ${N}`)
  el.innerHTML = s.html(ctx)
  stage.appendChild(el)
  return el
})
dots.innerHTML = SLIDES.map(() => '<i></i>').join('')
const dotEls = Array.from(dots.children) as HTMLElement[]
if (demo) {
  body.classList.add('demo')
  pill.textContent = `▶ ${t({ de: 'Antippen zum Ausprobieren', en: 'Tap to try it' })}`
}

function fit() {
  const s = Math.min(innerWidth / 1920, innerHeight / 1080)
  document.documentElement.style.setProperty('--s', String(s))
}
fit()
addEventListener('resize', fit)
startBackground($<HTMLCanvasElement>('bg'), reduced)

// ───────── start animation (slide 1) ─────────
let player: IntroPlayer | null = null
let introStopTimer = 0

function b64ToBuffer(b64: string): ArrayBuffer {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out.buffer
}
function loadJingle(): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    if (window.__TRS_PITCH_JINGLE) return resolve(b64ToBuffer(window.__TRS_PITCH_JINGLE))
    const s = document.createElement('script')
    s.src = 'assets/jingle-redstone.js'
    s.onload = () => (window.__TRS_PITCH_JINGLE ? resolve(b64ToBuffer(window.__TRS_PITCH_JINGLE)) : reject(new Error('jingle missing')))
    s.onerror = () => reject(new Error('jingle failed'))
    document.head.appendChild(s)
  })
}

function startIntro() {
  clearTimeout(introStopTimer)
  player?.stop(0.1)
  player = new IntroPlayer({
    canvas: introCv,
    design: introDesignById('redstone'),
    length: 'long',
    reduced,
    calm: calm || reduced,
    sound,
    volume: 0.8,
    jingle: sound ? loadJingle : null,
    logo: __LOGO__,
    mobile: Math.min(innerWidth, innerHeight) < 700,
  })
  player.start()
}
function stopIntro(delay: number) {
  const p = player
  player = null
  if (!p) return
  p.fadeAudio(0.4)
  introStopTimer = window.setTimeout(() => p.stop(0.1), delay)
}

// ───────── counters ─────────
const nf = new Intl.NumberFormat(lang === 'de' ? 'de-DE' : 'en-US')
let counterRuns: number[] = []
function runCounters(el: HTMLElement) {
  for (const id of counterRuns) clearTimeout(id)
  counterRuns = []
  for (const c of el.querySelectorAll<HTMLElement>('.count')) {
    const to = Number(c.dataset.to)
    const dur = Number(c.dataset.dur ?? 1400)
    const delay = Number(c.dataset.delay ?? 0)
    c.dataset.v = reduced ? nf.format(to) : '0'
    counterRuns.push(
      window.setTimeout(() => {
        if (reduced) return
        const t0 = performance.now()
        const step = (now: number) => {
          const k = Math.min(1, (now - t0) / dur)
          const e = 1 - Math.pow(1 - k, 3)
          c.dataset.v = nf.format(Math.round(to * e))
          if (k < 1) requestAnimationFrame(step)
        }
        requestAnimationFrame(step)
      }, delay),
    )
  }
}

// ───────── navigation + timing ─────────
let cur = -1
let timer = 0
let startedAt = 0
let remaining = 0
let hidden = false
const leaveTimers = new Map<HTMLElement, number>()

function restartClass(el: HTMLElement, cls: string) {
  el.classList.remove(cls)
  void el.offsetWidth
  el.classList.add(cls)
}

function goTo(i: number) {
  i = ((i % N) + N) % N
  const prev = cur
  const prevEl = prev >= 0 ? els[prev] : null
  const nextEl = els[i]!
  if (prevEl && prev !== i) {
    prevEl.classList.remove('on')
    prevEl.classList.add('leave')
    clearTimeout(leaveTimers.get(prevEl))
    leaveTimers.set(
      prevEl,
      window.setTimeout(() => prevEl.classList.remove('leave'), 900),
    )
  }
  clearTimeout(leaveTimers.get(nextEl))
  nextEl.classList.remove('leave', 'on')
  void nextEl.offsetWidth
  nextEl.classList.add('on')
  if (prev !== -1 && !reduced) restartClass(sweep, 'run')
  cur = i

  body.classList.toggle('on-intro', i === 0)
  if (i === 0) startIntro()
  else if (prev === 0) stopIntro(1000)

  runCounters(nextEl)
  dotEls.forEach((d, k) => d.classList.toggle('on', k === i))
  counter.textContent = `${String(i + 1).padStart(2, '0')} / ${String(N).padStart(2, '0')}`
  if (mode === 'loop') schedule(SLIDES[i]!.dur * 1000, true)
}

function schedule(ms: number, fresh: boolean) {
  clearTimeout(timer)
  remaining = ms
  if (fresh) {
    progress.style.setProperty('--pdur', `${ms}ms`)
    restartClass(progress, 'run')
  }
  if (hidden) return
  startedAt = performance.now()
  timer = window.setTimeout(() => goTo(cur + 1), ms)
}

function pauseClock() {
  if (mode !== 'loop') return
  clearTimeout(timer)
  remaining = Math.max(0, remaining - (performance.now() - startedAt))
}

function setMode(m: 'loop' | 'manual') {
  if (m === mode) return
  mode = m
  body.classList.toggle('loop', m === 'loop')
  body.classList.toggle('manual', m === 'manual')
  if (m === 'manual') {
    clearTimeout(timer)
    progress.classList.remove('run')
  } else {
    schedule(SLIDES[cur]!.dur * 1000, true)
  }
  showHint()
}

document.addEventListener('visibilitychange', () => {
  hidden = document.hidden
  body.classList.toggle('paused', hidden)
  if (hidden) pauseClock()
  else if (mode === 'loop') schedule(remaining, false)
})

// ───────── hints, toast, demo ─────────
let hintTimer = 0
function showHint() {
  clearTimeout(hintTimer)
  if (mode === 'loop') {
    if (demo) {
      hint.classList.add('gone')
      return
    }
    hint.innerHTML = t({ de: 'Tippen oder Taste drücken, um selbst zu blättern', en: 'Tap or press a key to browse yourself' })
  } else {
    hint.innerHTML = t({
      de: '<kbd>←</kbd> <kbd>→</kbd> / Wischen / Klick · <kbd>F</kbd> Vollbild · <kbd>P</kbd> Auto-Play · <kbd>M</kbd> Ton',
      en: '<kbd>←</kbd> <kbd>→</kbd> / swipe / click · <kbd>F</kbd> fullscreen · <kbd>P</kbd> auto-play · <kbd>M</kbd> sound',
    })
  }
  hint.classList.remove('gone')
  hintTimer = window.setTimeout(() => hint.classList.add('gone'), mode === 'loop' ? 12000 : 7000)
}

let toastTimer = 0
function showToast(text: string) {
  toast.textContent = text
  toast.classList.add('show')
  clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => toast.classList.remove('show'), 1600)
}

let lastDemo = 0
function emitDemo() {
  const now = performance.now()
  if (now - lastDemo < 800) return
  lastDemo = now
  window.dispatchEvent(new CustomEvent('trs-pitch:demo'))
  if (window.parent !== window) window.parent.postMessage({ source: 'trs-pitch', type: 'demo' }, '*')
}

function toggleFullscreen() {
  if (document.fullscreenElement) void document.exitFullscreen?.().catch(() => {})
  else void document.documentElement.requestFullscreen?.().catch(() => {})
}

function toggleSound() {
  sound = !sound
  showToast(sound ? t({ de: '🔊 Ton an', en: '🔊 Sound on' }) : t({ de: '🔇 Ton aus', en: '🔇 Sound off' }))
  if (cur === 0) startIntro()
}

// ───────── input ─────────
document.addEventListener('keydown', (e) => {
  if (e.ctrlKey || e.metaKey || e.altKey) return
  const k = e.key
  if (k === 'f' || k === 'F') return void (e.preventDefault(), toggleFullscreen())
  if (k === 'm' || k === 'M') return void (e.preventDefault(), toggleSound())
  if (k === 'Escape') {
    if (document.fullscreenElement) void document.exitFullscreen?.().catch(() => {})
    return
  }
  if (['Shift', 'Control', 'Alt', 'Meta', 'Tab'].includes(k)) return
  if (mode === 'loop' && demo) return void (e.preventDefault(), emitDemo())
  let act: (() => void) | null = null
  if (k === 'ArrowRight' || k === 'PageDown' || k === ' ' || k === 'Enter') act = () => goTo(cur + 1)
  else if (k === 'ArrowLeft' || k === 'PageUp' || k === 'Backspace') act = () => goTo(cur - 1)
  else if (k === 'Home') act = () => goTo(0)
  else if (k === 'End') act = () => goTo(N - 1)
  else if (k === 'p' || k === 'P') act = () => setMode('loop')
  e.preventDefault()
  if (mode === 'loop') {
    if (k === 'p' || k === 'P') return
    setMode('manual')
  }
  act?.()
})

let down: { x: number; y: number } | null = null
document.addEventListener('pointerdown', (e) => {
  if (e.button !== 0) return
  down = { x: e.clientX, y: e.clientY }
})
document.addEventListener('pointerup', (e) => {
  if (!down) return
  const dx = e.clientX - down.x
  const dy = e.clientY - down.y
  down = null
  const target = e.target as HTMLElement | null
  if (target?.closest('[data-demo]')) return emitDemo()
  if (mode === 'loop') {
    if (demo) return emitDemo()
    setMode('manual')
    return
  }
  if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) goTo(dx < 0 ? cur + 1 : cur - 1)
  else if (Math.abs(dx) < 12 && Math.abs(dy) < 12) {
    // click: right 2/3 → next, left third → back
    goTo(e.clientX < innerWidth / 3 ? cur - 1 : cur + 1)
  }
})
document.addEventListener('contextmenu', (e) => e.preventDefault())

let idleTimer = 0
document.addEventListener('pointermove', () => {
  body.classList.remove('idle')
  clearTimeout(idleTimer)
  idleTimer = window.setTimeout(() => body.classList.add('idle'), 2500)
})

// ───────── API ─────────
window.TRSPitch = {
  play: () => setMode('loop'),
  pause: () => setMode('manual'),
  goTo: (i: number) => goTo(Math.max(0, Math.min(N - 1, Math.floor(Number(i) || 0)))),
  next: () => goTo(cur + 1),
  prev: () => goTo(cur - 1),
  get slides() {
    return N
  },
  get current() {
    return cur
  },
  get mode() {
    return mode
  },
}

// ───────── go ─────────
body.classList.add(mode)
hidden = document.hidden
body.classList.toggle('paused', hidden)
const startAt = Math.max(0, Math.min(N - 1, Number(q.get('slide') ?? 0) || 0))
goTo(startAt)
showHint()
