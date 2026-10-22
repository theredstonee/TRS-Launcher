<script setup lang="ts">
import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import type { ClipEvent, ClipState } from '~/types'
import {
  OVERLAY_NOTE_MS,
  overlayAlign,
  overlayInstance,
  overlayLine,
  overlayLineText,
  overlayNoteFor,
  overlayNoteText,
  overlayNoteTone,
  overlayNoteWins,
  type OverlayNote,
} from '~/utils/clipOverlay'

// Eigenes Fenster über dem Spiel – kein App-Rahmen, kein Hintergrund (transparent). Zeigt „REC“,
// die Puffer-Anzeige und kurze Clip-Meldungen; das Fenster ist aus jeder Aufnahme ausgeschlossen.
definePageMeta({ layout: false })

/** Stand je Spiel + geschätzter Startzeitpunkt der Aufnahme (ms), damit die Zeit ruhig hochzählt. */
interface Live extends ClipState {
  startMs: number
}

const live = reactive<Record<string, Live>>({})
/** Stehende Meldung je Spiel. */
const notes = reactive<Record<string, { note: OverlayNote; until: number }>>({})
const now = ref(Date.now())

const noteUntil = computed(() => Object.fromEntries(Object.entries(notes).map(([id, n]) => [id, n.until])))
/** Das eine Spiel, das das Overlay gerade zeigt (gleiche Regel wie im Store). */
const active = computed(() => overlayInstance(Object.values(live), noteUntil.value, now.value))
const state = computed(() => (active.value ? live[active.value] : undefined))
const line = computed(() => overlayLine(state.value))
const lineText = computed(() => {
  if (!line.value) return ''
  const elapsed = line.value.kind === 'rec' && state.value ? now.value - state.value.startMs : 0
  return overlayLineText(line.value, elapsed)
})
const note = computed(() => {
  const id = active.value
  const entry = id ? notes[id] : undefined
  return entry && entry.until > now.value ? entry.note : null
})

/** Ausrichtung im Fenster nach dem Anker des HUD-Elements „Clips“. */
const align = computed(() => overlayAlign(state.value?.hudPlace))

/** Roter Punkt blinkt im Sekundentakt (an/aus je 500 ms) – wie zuvor im Spiel. */
const dotOn = computed(() => Math.floor(now.value / 500) % 2 === 0)

function apply(s: ClipState) {
  const prev = live[s.instanceId]
  // Startzeit nur beim Übergang setzen (sonst würde sie bei jedem Status leicht springen).
  const startMs = s.recording && prev?.recording ? prev.startMs : Date.now() - s.recordingMs
  live[s.instanceId] = { ...s, startMs }
}

function onEvent(event: ClipEvent) {
  const previous = 'instanceId' in event ? live[event.instanceId] : undefined
  const n = overlayNoteFor(event, previous)
  const at = Date.now()
  if (n && overlayNoteWins(n, notes[n.instanceId]?.until, at)) notes[n.instanceId] = { note: n, until: at + OVERLAY_NOTE_MS }
  if (event.type === 'state') apply(event)
  else if (event.type === 'ended') {
    delete live[event.instanceId]
    delete notes[event.instanceId]
  }
}

let unlisten: (() => void) | null = null
let tick: ReturnType<typeof setInterval> | null = null
let follow: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  // Fenster transparent halten (nur dieses Fenster; jedes Tauri-Fenster hat ein eigenes Dokument).
  document.documentElement.style.background = 'transparent'
  document.body.style.background = 'transparent'

  // Zeit, Blinken und ablaufende Meldungen: 250 ms reichen.
  tick = setInterval(() => {
    now.value = Date.now()
    for (const [id, n] of Object.entries(notes)) if (n.until <= now.value) delete notes[id]
  }, 250)

  if (!isTauri()) return
  unlisten = await listen<ClipEvent>('clip-event', (e) => onEvent(e.payload))
  // Laufende Spiele beim Fensteraufbau übernehmen.
  try {
    for (const s of await backend.clipStates()) apply(s)
  } catch {
    // Kein Rekorder aktiv – egal.
  }
  // Position dem Spielfenster nachführen (verschoben, Größe geändert, minimiert). Der Launcher hat
  // beim Zeigen schon einmal platziert; hier bleibt es dran, solange etwas zu sehen ist.
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
  <div class="overlay" :class="[`x-${align.x}`, `y-${align.y}`, { 'note-above': align.noteAbove }]">
    <div v-if="line || note" class="anchor">
      <div v-if="line" class="pill" :class="line.kind">
        <span v-if="line.kind === 'rec'" class="dot rec" :class="{ off: !dotOn }" aria-hidden="true" />
        <span v-else class="ring" aria-hidden="true" />
        <span class="text">{{ lineText }}</span>
      </div>
      <div v-if="note" class="pill note" :class="[overlayNoteTone(note), { follow: !!line }]">
        <span class="dot" aria-hidden="true" />
        <span class="text">{{ overlayNoteText(note) }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * Kleines (transparentes) Fenster an der Stelle des HUD-Elements „Clips“. Die Pille sitzt im
 * Fenster wie das Element an seinem Anker (links/Mitte/rechts, oben/Mitte/unten); die Meldung
 * hängt darunter – bzw. darüber, wenn das Element im unteren Bildschirmdrittel liegt.
 */
.overlay {
  position: fixed;
  inset: 0;
  margin: 0;
  background: transparent;
  pointer-events: none;
  overflow: hidden;
  --tx: 0;
  --ty: 0;
}
.anchor {
  position: absolute;
  display: flex;
  flex-direction: column;
  transform: translate(var(--tx), var(--ty));
}
.x-start .anchor {
  left: 2px;
  align-items: flex-start;
}
.x-center .anchor {
  left: 50%;
  --tx: -50%;
  align-items: center;
}
.x-end .anchor {
  right: 2px;
  align-items: flex-end;
}
.y-start .anchor {
  top: 2px;
}
.y-center .anchor {
  top: 50%;
  --ty: -50%;
}
.y-end .anchor {
  bottom: 2px;
}
/* Meldung neben der Pille: absolut, damit die Pille genau am Anker bleibt. */
.note.follow {
  position: absolute;
  top: calc(100% + 4px);
  width: max-content;
  max-width: 420px;
}
.note-above .note.follow {
  top: auto;
  bottom: calc(100% + 4px);
}
.x-start .note.follow {
  left: 0;
}
.x-end .note.follow {
  right: 0;
}
.x-center .note.follow {
  left: 50%;
  transform: translateX(-50%);
}
.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 3px 8px;
  border-radius: 7px;
  background: rgba(10, 12, 15, 0.72);
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.35);
  font-family: ui-monospace, "Cascadia Code", "Consolas", monospace;
  font-size: 13px;
  line-height: 1.15;
  color: #f2f4f8;
  user-select: none;
  white-space: nowrap;
  box-sizing: border-box;
}
/* Puffer: gedämpft, wie zuvor das Puffer-Symbol im Spiel. */
.pill.buffer .text {
  opacity: 0.7;
  font-weight: 500;
}
.text {
  font-weight: 600;
  letter-spacing: 0.02em;
}
/* Meldungen dürfen umbrechen (Fehlertexte sind länger), höchstens zwei Zeilen. */
.note {
  white-space: normal;
  align-items: flex-start;
  font-family: system-ui, "Segoe UI", sans-serif;
  font-size: 12.5px;
}
.note .text {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-weight: 500;
  letter-spacing: 0;
}
.note .dot {
  margin-top: 4px;
}
.dot {
  flex: none;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: currentColor;
}
/* Rot wie das bisherige HUD-Signal (0xFFE5322D). */
.dot.rec {
  background: #e5322d;
  box-shadow: 0 0 5px rgba(229, 50, 45, 0.8);
  transition: opacity 0.12s linear;
}
.dot.off {
  opacity: 0.28;
}
.ring {
  flex: none;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  border: 1.5px solid rgba(242, 244, 248, 0.6);
  box-sizing: border-box;
}
/* Farben der Meldungen wie zuvor im Spiel: Erfolg grün, Hinweis gelb, Fehler rot. */
.note.ok .dot {
  background: #55d86a;
}
.note.warn .dot {
  background: #f2c744;
}
.note.error .dot {
  background: #e5322d;
}
</style>
