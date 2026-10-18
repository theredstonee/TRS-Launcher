<script setup lang="ts">
import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import type { ClipEvent } from '~/types'

// Eigenes Fenster über dem Spiel – kein App-Rahmen, kein Hintergrund (transparent).
definePageMeta({ layout: false })

/** Aufnahme-Stand je Spiel (nur was das Overlay braucht). */
interface Live {
  recording: boolean
  /** Geschätzter Startzeitpunkt der Aufnahme (ms), damit die Zeit ruhig hochzählt. */
  startMs: number
}

const live = reactive<Record<string, Live>>({})
const now = ref(Date.now())

/** Erstes gerade aufnehmendes Spiel (ein Overlay für alle). */
const active = computed(() => Object.entries(live).find(([, s]) => s.recording)?.[0] ?? null)
const elapsed = computed(() => {
  const id = active.value
  if (!id) return 0
  return Math.max(0, now.value - live[id]!.startMs)
})

/** "0:07", "12:03", "1:02:03" – Sekunden abgerundet, zählt sauber hoch. */
const label = computed(() => {
  const total = Math.floor(elapsed.value / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total / 60) % 60)
  const ss = String(total % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
})

/** Roter Punkt blinkt im Sekundentakt (an/aus je 500 ms) – wie zuvor im Spiel. */
const dotOn = computed(() => Math.floor(now.value / 500) % 2 === 0)

function apply(id: string, recording: boolean, recordingMs: number) {
  if (recording) {
    // Startzeit nur beim Übergang setzen (sonst würde sie bei jedem Status leicht springen).
    const startMs = Date.now() - recordingMs
    if (!live[id]?.recording) live[id] = { recording: true, startMs }
    else live[id]!.recording = true
  } else {
    delete live[id]
  }
}

function onEvent(event: ClipEvent) {
  if (event.type === 'state') apply(event.instanceId, event.recording, event.recordingMs)
  else if (event.type === 'ended') delete live[event.instanceId]
}

let unlisten: (() => void) | null = null
let tick: ReturnType<typeof setInterval> | null = null
let follow: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  // Fenster transparent halten (nur dieses Fenster; jedes Tauri-Fenster hat ein eigenes Dokument).
  document.documentElement.style.background = 'transparent'
  document.body.style.background = 'transparent'

  // Zeit + Blinken: 250 ms reichen für ruhiges Hochzählen und den Sekunden-Takt.
  tick = setInterval(() => (now.value = Date.now()), 250)

  if (!isTauri()) return
  unlisten = await listen<ClipEvent>('clip-event', (e) => onEvent(e.payload))
  // Eine schon laufende Aufnahme beim Fensteraufbau übernehmen.
  try {
    for (const s of await backend.clipStates()) apply(s.instanceId, s.recording, s.recordingMs)
  } catch {
    // Kein Rekorder aktiv – egal.
  }
  // Position/Größe dem Spielfenster nachführen (verschoben/Größe geändert). Der Launcher hat
  // beim Start schon einmal platziert; hier bleibt es dran, solange aufgenommen wird.
  follow = setInterval(() => {
    const id = active.value
    if (id) void backend.clipOverlayTrack(id).catch(() => {})
  }, 500)
})

onBeforeUnmount(() => {
  unlisten?.()
  if (tick) clearInterval(tick)
  if (follow) clearInterval(follow)
})
</script>

<template>
  <div class="overlay">
    <div v-if="active" class="pill">
      <span class="dot" :class="{ off: !dotOn }" aria-hidden="true" />
      <span class="text">REC {{ label }}</span>
    </div>
  </div>
</template>

<style scoped>
/* Volle (transparente) Fläche über dem Spiel; die Pille sitzt oben-links wie zuvor der HUD-Punkt. */
.overlay {
  position: fixed;
  inset: 0;
  margin: 0;
  background: transparent;
  pointer-events: none;
  overflow: hidden;
}
.pill {
  position: absolute;
  top: 6px;
  left: 6px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  border-radius: 7px;
  background: rgba(10, 12, 15, 0.72);
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.35);
  font-family: ui-monospace, "Cascadia Code", "Consolas", monospace;
  font-size: 13px;
  line-height: 1;
  color: #f2f4f8;
  user-select: none;
  white-space: nowrap;
}
.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  /* Rot wie das bisherige HUD-Signal (0xFFE5322D). */
  background: #e5322d;
  box-shadow: 0 0 5px rgba(229, 50, 45, 0.8);
  transition: opacity 0.12s linear;
}
.dot.off {
  opacity: 0.28;
}
.text {
  font-weight: 600;
  letter-spacing: 0.02em;
}
</style>
