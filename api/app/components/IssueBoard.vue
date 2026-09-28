<script setup lang="ts">
// Roadmap-Board (§28): sechs Spalten Offen → Geplant → In Arbeit → In Prüfung → Erledigt → Abgelehnt mit Zähler.
// Waagerecht scrollbar; auf dem Handy eine Spalte je Reiter. Team (issues.manage) zieht Karten zwischen den
// Spalten – optimistisch verschoben, bei Fehler zurück. Weitere Karten je Spalte mit „Mehr laden“.
import type { IssueStatus, IssueView, RoadmapColumnView } from '#shared/issues'

const props = withDefaults(defineProps<{ columns: RoadmapColumnView[], canMove?: boolean, loadingMore?: string | null }>(), { canMove: false, loadingMore: null })
const emit = defineEmits<{ open: [nr: number], move: [nr: number, from: IssueStatus, to: IssueStatus], more: [status: IssueStatus] }>()
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
function onMoveMenu(nr: number, to: IssueStatus) {
  const from = findStatus(nr)
  if (from && from !== to) emit('move', nr, from, to)
}
const colOf = (s: IssueStatus) => props.columns.find((c) => c.status === s)
const cardOf = (c: RoadmapColumnView): IssueView[] => c.issues
</script>

<template>
  <div class="board-wrap">
    <div class="tabs" role="tablist" :aria-label="it.board.columns">
      <button
        v-for="c in columns"
        :key="c.status"
        type="button"
        role="tab"
        class="tab"
        :aria-selected="mobileCol === c.status"
        @click="mobileCol = c.status"
      >
        <span class="cdot" :data-s="c.status" aria-hidden="true" />{{ it.statuses[c.status] }}<span class="cnt">{{ c.total }}</span>
      </button>
    </div>
    <div class="board" data-testid="issue-board">
      <section
        v-for="c in columns"
        :key="c.status"
        class="col"
        :class="{ over: over === c.status, 'mobile-hidden': mobileCol !== c.status }"
        :data-s="c.status"
        :aria-label="it.statuses[c.status]"
        @dragover="onDragOver($event, c.status)"
        @dragleave="over === c.status && (over = null)"
        @drop="onDrop($event, c.status)"
      >
        <header class="col-head">
          <span class="cdot" :data-s="c.status" aria-hidden="true" />
          <h2 class="col-title">{{ it.statuses[c.status] }}</h2>
          <span class="cnt">{{ c.total }}</span>
        </header>
        <ul class="cards">
          <li v-for="i in cardOf(c)" :key="i.number">
            <IssueCard
              :issue="i"
              :can-move="canMove"
              :dragging="dragNr === i.number"
              @open="emit('open', $event)"
              @move="onMoveMenu"
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
