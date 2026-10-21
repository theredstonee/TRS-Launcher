// Eigenständige Vorschau der Start-Animation (eine HTML-Datei, läuft per file://). Nutzt genau den Motor des Launchers.
// Gebaut mit `node scripts/intro-preview/build.mjs <Zielordner>`.

import { introDesignById, INTRO_DESIGNS } from '../../app/utils/intro/designs'
import { IntroPlayer } from '../../app/utils/intro/player'
import { INTRO_LENGTHS, type IntroLength } from '../../app/utils/intro/timeline'

declare global {
  interface Window {
    __TRS_INTRO_AUDIO?: Record<string, string>
    __TRS_INTRO_LOGO?: string
    __intro?: { play: (o?: Partial<State>) => void; player: () => IntroPlayer | null; still: (t: number) => void }
  }
}

interface State {
  design: string
  length: IntroLength
  sound: boolean
  reduced: boolean
  calm: boolean
}

const q = new URLSearchParams(location.search)
const state: State = {
  design: introDesignById(q.get('design')).id,
  length: (INTRO_LENGTHS as readonly string[]).includes(q.get('length') ?? '') ? (q.get('length') as IntroLength) : 'long',
  sound: q.get('sound') === '1',
  reduced: q.get('reduced') === '1',
  calm: q.get('calm') === '1',
}

const stage = document.getElementById('stage') as HTMLDivElement
const cv = document.getElementById('cv') as HTMLCanvasElement
const status = document.getElementById('status') as HTMLSpanElement
let player: IntroPlayer | null = null
let fadeTimer = 0

function b64ToBuffer(b64: string): ArrayBuffer {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out.buffer
}

function play(patch: Partial<State> = {}) {
  Object.assign(state, patch)
  player?.stop(0.15)
  clearTimeout(fadeTimer)
  stage.classList.remove('gone')
  const design = introDesignById(state.design)
  const b64 = window.__TRS_INTRO_AUDIO?.[design.id]
  player = new IntroPlayer({
    canvas: cv,
    design,
    length: state.length,
    reduced: state.reduced,
    calm: state.calm,
    sound: state.sound,
    volume: 0.85,
    jingle: b64 ? async () => b64ToBuffer(b64) : null,
    logo: window.__TRS_INTRO_LOGO ?? null,
    onReady: () => {
      // Übergabe an den „Launcher“: Bühne blendet aus
      stage.classList.add('gone')
      showBannerIfWanted()
      const cur = player
      fadeTimer = window.setTimeout(() => cur?.stop(0.2), 500)
    },
  })
  player.start()
  sync()
}

function sync() {
  for (const b of document.querySelectorAll<HTMLButtonElement>('[data-design]')) b.classList.toggle('on', b.dataset.design === state.design)
  for (const b of document.querySelectorAll<HTMLButtonElement>('[data-length]')) b.classList.toggle('on', b.dataset.length === state.length)
  const s = document.getElementById('sound') as HTMLButtonElement
  s.classList.toggle('on', state.sound)
  s.textContent = state.sound ? 'Ton an' : 'Ton aus'
  const r = document.getElementById('reduced') as HTMLButtonElement
  r.classList.toggle('on', state.reduced)
  const cb = document.getElementById('calm') as HTMLButtonElement
  cb.classList.toggle('on', state.calm)
  cb.textContent = state.calm ? 'Ohne Flackern' : 'Mit Flackern'
  const u = new URL(location.href)
  u.searchParams.set('design', state.design)
  u.searchParams.set('length', state.length)
  u.searchParams.set('sound', state.sound ? '1' : '0')
  u.searchParams.set('calm', state.calm ? '1' : '0')
  history.replaceState(null, '', u)
}

for (const b of document.querySelectorAll<HTMLButtonElement>('[data-design]')) b.onclick = () => play({ design: b.dataset.design! })
for (const b of document.querySelectorAll<HTMLButtonElement>('[data-length]')) b.onclick = () => play({ length: b.dataset.length as IntroLength })
;(document.getElementById('replay') as HTMLButtonElement).onclick = () => play()
;(document.getElementById('sound') as HTMLButtonElement).onclick = () => play({ sound: !state.sound })
;(document.getElementById('reduced') as HTMLButtonElement).onclick = () => play({ reduced: !state.reduced })
;(document.getElementById('calm') as HTMLButtonElement).onclick = () => play({ calm: !state.calm })

// „Erster Start (Banner)“: wie im Launcher – die Animation läuft, danach erscheint einmal der Hinweis in der App
const banner = document.getElementById('banner') as HTMLElement
const bannerCalm = document.getElementById('banner-calm') as HTMLInputElement
const note = document.getElementById('note') as HTMLElement
let wantBanner = false
function firstStart() {
  banner.hidden = true
  note.textContent = ''
  wantBanner = true
  play({ length: 'long', sound: true })
}
function showBannerIfWanted() {
  if (!wantBanner) return
  wantBanner = false
  bannerCalm.checked = state.calm
  setTimeout(() => (banner.hidden = false), 450)
}
for (const b of banner.querySelectorAll<HTMLButtonElement>('[data-keep]')) {
  b.onclick = () => {
    banner.hidden = true
    state.calm = bannerCalm.checked
    sync()
    note.textContent = b.dataset.keep === '1'
      ? `Kurze Animation bleibt an${state.calm ? ' – ruhig, ohne Flackern' : ''}.`
      : `Kurze 4-s-Animation aus (Tagesstart und Updates laufen trotzdem)${state.calm ? ' – ruhig, ohne Flackern' : ''}.`
  }
}
;(document.getElementById('first') as HTMLButtonElement).onclick = () => firstStart()
// Überspringen wie im Launcher: Klick auf die Bühne
stage.onclick = () => {
  if (!player || stage.classList.contains('gone')) return
  player.fadeAudio(0.25)
  stage.classList.add('gone')
  showBannerIfWanted()
  const cur = player
  fadeTimer = window.setTimeout(() => cur.stop(0.1), 350)
}

setInterval(() => {
  if (player) status.textContent = `${player.time.toFixed(2)} s / ${player.duration.toFixed(1)} s · ${INTRO_DESIGNS.length} Designs`
}, 100)

window.__intro = { play, player: () => player, still: (t: number) => player?.still(t) }
play()
