<script setup lang="ts">
// Karte auf dem Roadmap-Board (§28): Art, Bereich, Priorität, Nummer, Titel, Score, Kommentare, Zuständige(r).
// Klick auf die ganze Karte öffnet das Issue (Seite oder Fenster, entscheidet der Explorer). Team (issues.manage): ziehen zwischen Spalten oder Status-Menü (Tastatur).
import { ISSUE_STATUSES, type IssueStatus, type IssueView } from '#shared/issues'

const props = withDefaults(defineProps<{ issue: IssueView, canMove?: boolean, dragging?: boolean }>(), { canMove: false, dragging: false })
const emit = defineEmits<{ open: [nr: number], move: [nr: number, status: IssueStatus], dragstart: [nr: number], dragend: [] }>()
const { it, fill } = useIssueText()
const lp = useLocalePath()

function onDragStart(e: DragEvent) {
  if (!props.canMove || !e.dataTransfer) return
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/x-trs-issue', String(props.issue.number))
  e.dataTransfer.setData('text/plain', `#${props.issue.number}`)
  emit('dragstart', props.issue.number)
}
function onSelect(e: Event) {
  const v = (e.target as HTMLSelectElement).value as IssueStatus
  if (v && v !== props.issue.status) emit('move', props.issue.number, v)
}
function open(e: MouseEvent) {
  // Neuer Tab/Fenster: normaler Link.
  if (e.ctrlKey || e.metaKey || e.shiftKey || e.button === 1) return
  e.preventDefault()
  emit('open', props.issue.number)
}
/** Klick irgendwo auf den Block (außer auf Knöpfe/Links/Menüs) öffnet das Issue; Strg/Cmd = neuer Tab. */
function onBlock(e: MouseEvent, nr: number) {
  if ((e.target as HTMLElement).closest('a, button, select, input, textarea, label')) return
  if (window.getSelection()?.toString()) return
  if (e.ctrlKey || e.metaKey || e.shiftKey) {
    window.open(lp(`/issues/${nr}`), '_blank', 'noopener')
    return
  }
  emit('open', nr)
}
</script>

<template>
  <article
    class="card-i"
    :class="{ movable: canMove, dragging }"
    :draggable="canMove"
    :data-nr="issue.number"
    @dragstart="onDragStart"
    @dragend="emit('dragend')"
    @click="onBlock($event, issue.number)"
  >
    <div class="top">
      <SiteIcon :name="issue.type === 'bug' ? 'bug' : 'bolt'" class="size-4 shrink-0" :class="issue.type === 'bug' ? 'text-redstone-300' : 'text-lamp-300'" :title="it.types[issue.type]" />
      <IssueArea :area="issue.area" />
      <IssuePriority v-if="issue.priority" :priority="issue.priority" />
      <span class="nr">#{{ issue.number }}</span>
    </div>
    <a :href="lp(`/issues/${issue.number}`)" class="title" @click="open">{{ issue.title }}</a>
    <div class="bottom">
      <span class="stat" :title="fill(it.detail.sidebar.votes, { up: issue.up, down: issue.down })" :data-v="issue.myVote ?? 0">
        <SiteIcon name="voteUp" class="size-3.5" />{{ issue.score }}
      </span>
      <span class="stat" :title="issue.comments === 1 ? it.common.comment1 : fill(it.common.comments, { n: issue.comments })"><SiteIcon name="chat" class="size-3.5" />{{ issue.comments }}</span>
      <span v-if="issue.fixedIn" class="fixed-in"><SiteIcon name="check" class="size-3" />{{ issue.fixedIn }}</span>
      <span v-if="issue.locked" class="stat" :title="it.common.locked"><SiteIcon name="lock" class="size-3.5" /></span>
      <span class="ml-auto flex items-center gap-1.5">
        <select v-if="canMove" class="move" :value="issue.status" :aria-label="`${it.board.moveTo} #${issue.number}`" :title="it.board.moveTo" @click.stop @change="onSelect">
          <option v-for="s in ISSUE_STATUSES.filter((x) => x !== 'duplicate')" :key="s" :value="s">{{ it.statuses[s] }}</option>
        </select>
        <PlayerHead v-if="issue.assignee" :uuid="issue.assignee.uuid" :name="issue.assignee.name" :skin="issue.assignee.skin ?? null" :fetch="false" :size="20" :title="`${it.detail.sidebar.assignee}: ${issue.assignee.name}`" />
      </span>
    </div>
  </article>
</template>

<style scoped>
.card-i {
  position: relative;
  display: grid;
  gap: 0.5rem;
  padding: 0.7rem 0.8rem 0.65rem;
  border-radius: 0.65rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
  box-shadow: 0 1px 0 rgb(255 255 255 / 0.025) inset, 0 6px 16px -12px rgb(0 0 0 / 0.8);
  transition: border-color 0.12s, transform 0.12s, background-color 0.12s;
}
.card-i {
  cursor: pointer;
}
.card-i:hover {
  border-color: var(--color-base-600);
  background: var(--color-base-850);
}
.card-i.movable {
  cursor: grab;
}
.card-i.movable:active {
  cursor: grabbing;
}
.card-i.dragging {
  opacity: 0.4;
  transform: rotate(-1.5deg);
}
.top {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  min-width: 0;
}
.nr {
  margin-left: auto;
  font-size: 11px;
  color: var(--color-base-400);
  font-variant-numeric: tabular-nums;
}
.title {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 0.9rem;
  font-weight: 600;
  line-height: 1.3;
  color: var(--color-base-50);
  overflow-wrap: anywhere;
}
.title::after {
  /* ganze Karte klickbar, Knöpfe bleiben darüber */
  content: '';
  position: absolute;
  inset: 0;
}
.bottom {
  display: flex;
  align-items: center;
  gap: 0.7rem;
  min-height: 1.25rem;
}
.bottom > * {
  position: relative;
  z-index: 1;
}
.stat {
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
  font-size: 11px;
  font-weight: 600;
  color: var(--color-base-400);
  font-variant-numeric: tabular-nums;
}
.stat[data-v='1'] {
  color: var(--color-redstone-300);
}
.fixed-in {
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
  font-size: 11px;
  font-weight: 600;
  color: var(--color-ok);
}
.move {
  max-width: 6.5rem;
  padding: 0.1rem 0.25rem;
  border-radius: 0.35rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-950);
  font-size: 11px;
  color: var(--color-base-200);
}
.move {
  opacity: 0;
  transition: opacity 0.12s;
}
.card-i:hover .move,
.card-i:focus-within .move {
  opacity: 1;
}
.move:focus-visible {
  outline: 2px solid var(--color-redstone-400);
}
</style>
