<script setup lang="ts">
// Roadmap-Board (§28): sechs Spalten Offen → Geplant → In Arbeit → In Prüfung → Erledigt → Abgelehnt mit Zähler.
// Waagerecht scrollbar; auf dem Handy eine Spalte je Reiter. Team (issues.manage) zieht Karten zwischen den
// Spalten – optimistisch verschoben, bei Fehler zurück. Am Handy (kein HTML-Drag & Drop): Karte gedrückt halten,
// dann auf einen Spalten-Reiter oder die Leiste unten ziehen. Weitere Karten je Spalte mit „Mehr laden“.
import type { IssueStatus, IssueView, RoadmapColumnView } from '#shared/issues'

const props = withDefaults(defineProps<{ columns: RoadmapColumnView[], canMove?: boolean, loadingMore?: string | null, me?: string | null, claiming?: number | null }>(), { canMove: false, loadingMore: null, me: null, claiming: null })
const emit = defineEmits<{ open: [nr: number], move: [nr: number, from: IssueStatus, to: IssueStatus], more: [status: IssueStatus], claim: [nr: number, claim: boolean] }>()
const { it } = useIssueText()

const dragNr = ref<number | null>(null)
const over = ref<IssueStatus | null>(null)
const mobileCol = ref<IssueStatus>('open')

function findStatus(nr: number): IssueStatus | null {
  return props.columns.find((c) => c.issues.some((i) => i.number === nr))?.status ?? null
}
function onDragOver(e: DragEvent, status: IssueStatus) {
  if (!props.canMove || dragNr.value === null) return
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
  over.value = status
}
function onDrop(e: DragEvent, status: IssueStatus) {
  if (!props.canMove) return
  e.preventDefault()
  const nr = Number(e.dataTransfer?.getData('text/x-trs-issue') || dragNr.value)
  over.value = null
  dragNr.value = null
  const from = Number.isSafeInteger(nr) ? findStatus(nr) : null
  if (from && from !== status) emit('move', nr, from, status)
}

// --- Handy: lange drücken + ziehen ------------------------------------------------------------------------
const HOLD_MS = 380
const touch = { timer: 0, x: 0, y: 0, active: false }
const ghost = ref<{ nr: number, title: string, from: IssueStatus, x: number, y: number, dx: number, w: number } | null>(null)

function cancelTouch() {
  if (touch.timer) window.clearTimeout(touch.timer)
  touch.timer = 0
  touch.active = false
  ghost.value = null
  dragNr.value = null
  over.value = null
}
function onTouchStart(e: TouchEvent) {
  if (!props.canMove || e.touches.length !== 1) return cancelTouch()
  const target = e.target as HTMLElement
  if (target.closest('button, select, input, textarea')) return
  const card = target.closest<HTMLElement>('[data-nr]')
  const nr = Number(card?.dataset.nr)
  const from = Number.isSafeInteger(nr) ? findStatus(nr) : null
  if (!card || !from) return
  cancelTouch()
  touch.x = e.touches[0]!.clientX
  touch.y = e.touches[0]!.clientY
  touch.timer = window.setTimeout(() => {
    touch.timer = 0
    touch.active = true
    const r = card.getBoundingClientRect()
    const issue = props.columns.find((c) => c.status === from)?.issues.find((i) => i.number === nr)
    ghost.value = { nr, title: issue?.title ?? `#${nr}`, from, x: touch.x, y: touch.y, dx: touch.x - r.left, w: r.width }
    dragNr.value = nr
    navigator.vibrate?.(12)
  }, HOLD_MS)
}
function dropTarget(x: number, y: number): IssueStatus | null {
  const el = document.elementFromPoint(x, y)?.closest<HTMLElement>('[data-drop]')
  return (el?.dataset.drop as IssueStatus | undefined) ?? null
}
function onTouchMove(e: TouchEvent) {
  const p = e.touches[0]
  if (!p) return
  if (!touch.active) {
    // Vor Ablauf der Haltezeit bewegt = scrollen, kein Ziehen.
    if (touch.timer && Math.hypot(p.clientX - touch.x, p.clientY - touch.y) > 8) cancelTouch()
    return
  }
  e.preventDefault()
  if (ghost.value) {
    ghost.value.x = p.clientX
    ghost.value.y = p.clientY
  }
  over.value = dropTarget(p.clientX, p.clientY)
}
function onTouchEnd(e: TouchEvent) {
  if (!touch.active || !ghost.value) return cancelTouch()
  e.preventDefault()
  const { nr, from } = ghost.value
  const to = over.value
  cancelTouch()
  if (to && to !== from) {
    mobileCol.value = to
    emit('move', nr, from, to)
  }
}
function onContextMenu(e: Event) {
  // Langes Drücken öffnet sonst das Link-Menü des Browsers.
  if (touch.timer || touch.active) e.preventDefault()
}
/** Die schwebende Karte bleibt ganz im Bild. */
const ghostMaxLeft = computed(() => (ghost.value ? window.innerWidth - ghost.value.w - 8 : 0))
onBeforeUnmount(cancelTouch)
const colOf = (s: IssueStatus) => props.columns.find((c) => c.status === s)
const cardOf = (c: RoadmapColumnView): IssueView[] => c.issues
</script>

<template>
  <div
    class="board-wrap"
    @touchstart="onTouchStart"
    @touchmove="onTouchMove"
    @touchend="onTouchEnd"
    @touchcancel="cancelTouch"
    @contextmenu="onContextMenu"
  >
    <div class="tabs" role="tablist" :aria-label="it.board.columns">
      <button
        v-for="c in columns"
        :key="c.status"
        type="button"
        role="tab"
        class="tab"
        :class="{ over: ghost && over === c.status }"
        :aria-selected="mobileCol === c.status"
        :data-drop="c.status"
        @click="mobileCol = c.status"
      >
        <span class="cdot" :data-s="c.status" aria-hidden="true" />{{ it.statuses[c.status] }}<span class="cnt">{{ c.total }}</span>
      </button>
    </div>
    <div class="board" data-testid="issue-board">
      <section
        v-for="(c, ci) in columns"
        :key="c.status"
        class="col"
        :class="{ over: over === c.status, 'mobile-hidden': mobileCol !== c.status }"
        :data-s="c.status"
        :aria-label="it.statuses[c.status]"
        :data-drop="c.status"
        @dragover="onDragOver($event, c.status)"
        @dragleave="over === c.status && (over = null)"
        @drop="onDrop($event, c.status)"
      >
        <header class="col-head">
          <span class="cdot" :data-s="c.status" aria-hidden="true" />
          <h2 class="col-title">{{ it.statuses[c.status] }}</h2>
          <!-- Halloween-Kürbis-Suche: „Auf dem Weg in die Zukunft, über einer Spalte.“ -->
          <HalloweenHunt v-if="ci === 1" id="web-roadmap" />
          <span class="cnt">{{ c.total }}</span>
        </header>
        <ul class="cards">
          <li v-for="i in cardOf(c)" :key="i.number">
            <IssueCard
              :issue="i"
              :can-move="canMove"
              :dragging="dragNr === i.number"
              :me="me"
              :claiming="claiming === i.number"
              @open="emit('open', $event)"
              @claim="(nr, claim) => emit('claim', nr, claim)"
              @dragstart="dragNr = $event"
              @dragend="dragNr = null; over = null"
            />
          </li>
        </ul>
        <p v-if="!c.issues.length" class="empty">{{ over === c.status ? it.board.drop : it.board.empty }}</p>
        <button v-if="c.hasMore" type="button" class="more" :disabled="loadingMore === c.status" @click="emit('more', c.status)">
          {{ loadingMore === c.status ? it.common.loading : it.board.loadMore }} <span class="text-base-400">({{ c.issues.length }}/{{ c.total }})</span>
        </button>
      </section>
    </div>
    <span class="sr-only">{{ colOf(mobileCol)?.total }}</span>
    <Teleport to="body">
      <template v-if="ghost">
        <div class="touch-ghost" :style="{ left: `${Math.max(8, Math.min(ghost.x - ghost.dx, ghostMaxLeft))}px`, top: `${ghost.y - 28}px`, width: `${ghost.w}px` }" aria-hidden="true">
          <span class="nr">#{{ ghost.nr }}</span>{{ ghost.title }}
        </div>
        <div class="touch-drop" aria-hidden="true">
          <span
            v-for="c in columns.filter((x) => x.status !== ghost!.from)"
            :key="c.status"
            class="drop-chip"
            :class="{ over: over === c.status }"
            :data-drop="c.status"
          >
            <span class="cdot" :data-s="c.status" />{{ it.statuses[c.status] }}
          </span>
        </div>
      </template>
    </Teleport>
  </div>
</template>

<style scoped>
.board {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(16rem, 1fr);
  gap: 0.75rem;
  overflow-x: auto;
  padding-bottom: 0.75rem;
  scroll-snap-type: x proximity;
}
.col {
  --c: var(--color-base-400);
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 12rem;
  padding: 0.6rem;
  border-radius: 0.85rem;
  border: 1px solid var(--color-base-800);
  background: color-mix(in srgb, var(--color-base-900) 60%, var(--color-base-950));
  scroll-snap-align: start;
  transition: border-color 0.12s, background-color 0.12s, box-shadow 0.12s;
}
.col[data-s='open'] { --c: var(--color-base-200); }
.col[data-s='planned'] { --c: #7cc4ff; }
.col[data-s='in_progress'] { --c: var(--color-lamp-400); }
.col[data-s='in_review'] { --c: #c4a5ff; }
.col[data-s='done'] { --c: var(--color-ok); }
.col[data-s='rejected'] { --c: var(--color-redstone-300); }
.col.over {
  border-color: var(--c);
  background: color-mix(in srgb, var(--c) 7%, var(--color-base-900));
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--c) 18%, transparent);
}
.col-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 0.35rem 0.65rem;
  border-bottom: 2px solid color-mix(in srgb, var(--c) 45%, transparent);
  margin-bottom: 0.6rem;
}
.col-title {
  font-family: var(--font-display);
  font-size: 1rem;
  line-height: 1;
  color: var(--color-base-50);
}
.cdot {
  --c: var(--color-base-400);
  width: 0.6rem;
  height: 0.6rem;
  border-radius: 2px;
  background: var(--c);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--c) 25%, transparent);
}
.cdot[data-s='open'] { --c: var(--color-base-200); }
.cdot[data-s='planned'] { --c: #7cc4ff; }
.cdot[data-s='in_progress'] { --c: var(--color-lamp-400); box-shadow: 0 0 8px var(--color-lamp-400); }
.cdot[data-s='in_review'] { --c: #c4a5ff; }
.cdot[data-s='done'] { --c: var(--color-ok); }
.cdot[data-s='rejected'] { --c: var(--color-redstone-300); }
.cnt {
  margin-left: auto;
  min-width: 1.6rem;
  padding: 0 0.45rem;
  border-radius: 999px;
  background: var(--color-base-800);
  font-size: 11px;
  font-weight: 600;
  line-height: 1.3rem;
  text-align: center;
  color: var(--color-base-200);
  font-variant-numeric: tabular-nums;
}
.cards {
  display: grid;
  gap: 0.5rem;
}
.empty {
  display: grid;
  place-items: center;
  flex: 1;
  min-height: 5rem;
  border-radius: 0.6rem;
  border: 1px dashed var(--color-base-700);
  font-size: 0.8rem;
  color: var(--color-base-400);
}
.more {
  margin-top: 0.6rem;
  padding: 0.45rem;
  border-radius: 0.5rem;
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--color-base-200);
  background: var(--color-base-850);
}
.more:hover:not(:disabled) {
  background: var(--color-base-800);
  color: var(--color-base-50);
}
.tabs {
  display: none;
}
.touch-ghost {
  position: fixed;
  z-index: 60;
  pointer-events: none;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  padding: 0.7rem 0.8rem;
  border-radius: 0.65rem;
  border: 1px solid var(--color-lamp-400);
  background: var(--color-base-850);
  box-shadow: 0 18px 40px -12px rgb(0 0 0 / 0.85);
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--color-base-50);
  /* über dem Finger, damit das Ziel sichtbar bleibt */
  transform: translateY(-100%) rotate(-2deg);
}
.touch-ghost .nr {
  margin-right: 0.4rem;
  font-size: 11px;
  color: var(--color-base-400);
}
.touch-drop {
  position: fixed;
  z-index: 59;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 0.4rem;
  padding: 0.75rem 0.75rem calc(0.75rem + env(safe-area-inset-bottom));
  border-top: 1px solid var(--color-base-700);
  background: color-mix(in srgb, var(--color-base-950) 92%, transparent);
  backdrop-filter: blur(8px);
}
.drop-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.6rem 0.8rem;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-700);
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--color-base-200);
}
.drop-chip.over,
.tab.over {
  border-color: var(--color-lamp-400);
  background: color-mix(in srgb, var(--color-lamp-400) 16%, transparent);
  color: var(--color-base-50);
}
@media (max-width: 719px) {
  .tabs {
    display: flex;
    gap: 0.35rem;
    overflow-x: auto;
    padding-bottom: 0.6rem;
  }
  .tab {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    flex-shrink: 0;
    padding: 0.35rem 0.6rem;
    border-radius: 999px;
    border: 1px solid var(--color-base-800);
    font-size: 0.8rem;
    color: var(--color-base-200);
  }
  .tab .cnt {
    margin-left: 0.1rem;
    min-width: 1.3rem;
    line-height: 1.1rem;
  }
  .tab[aria-selected='true'] {
    border-color: var(--color-redstone-500);
    background: color-mix(in srgb, var(--color-redstone-500) 12%, transparent);
    color: var(--color-base-50);
  }
  .board {
    grid-auto-flow: row;
    grid-auto-columns: auto;
    overflow: visible;
  }
  .col.mobile-hidden {
    display: none;
  }
}
</style>
