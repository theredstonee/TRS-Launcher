<script setup lang="ts">
// Ein Issue als Karte in der Issue-Liste (§28): Stimmen links, oben Art/Status/Bereich + Nummer, großer Titel,
// unten Ersteller, Kommentare und Datum. Klick irgendwo auf die Karte führt zur ganzen Issue-Seite (Strg-Klick:
// neuer Tab); Stimmen-Pfeile und Links bleiben eigene Klickziele.
import { isClosed, type IssueView } from '#shared/issues'

const props = defineProps<{ issue: IssueView, signedIn: boolean }>()
const emit = defineEmits<{ open: [nr: number], voted: [r: { score: number, up: number, down: number, myVote: -1 | 0 | 1 }], error: [message: string] }>()
const { it, fill, date } = useIssueText()
const lp = useLocalePath()
const closed = computed(() => isClosed(props.issue.status))
const href = computed(() => lp(`/issues/${props.issue.number}`))
const openedBy = computed<[string, string]>(() => {
  const [a = '', b = ''] = it.value.detail.openedBy.split('{name}')
  return [a, b]
})

function open(e: MouseEvent) {
  if (e.ctrlKey || e.metaKey || e.shiftKey || e.button === 1) return
  e.preventDefault()
  emit('open', props.issue.number)
}
function onBlock(e: MouseEvent) {
  if ((e.target as HTMLElement).closest('a, button, select, input, textarea, label')) return
  if (window.getSelection()?.toString()) return
  if (e.ctrlKey || e.metaKey || e.shiftKey) {
    window.open(href.value, '_blank', 'noopener')
    return
  }
  emit('open', props.issue.number)
}
</script>

<template>
  <article class="item" :class="{ closed }" :data-nr="issue.number" @click="onBlock">
    <div class="votes">
      <IssueVote
        :number="issue.number"
        :score="issue.score"
        :my-vote="issue.myVote ?? 0"
        :up="issue.up"
        :down="issue.down"
        :closed="closed"
        :signed-in="signedIn"
        @voted="emit('voted', $event)"
        @error="emit('error', $event)"
      />
    </div>
    <div class="body">
      <div class="top">
        <span class="type-badge" :data-t="issue.type"><SiteIcon :name="issue.type === 'bug' ? 'bug' : 'bolt'" class="size-3.5" />{{ it.types[issue.type] }}</span>
        <IssueStatus :status="issue.status" />
        <IssueArea :area="issue.area" />
        <span v-if="issue.fixedIn" class="fixed-in"><SiteIcon name="check" class="size-3" />{{ issue.fixedIn }}</span>
        <span v-if="issue.duplicateOf" class="text-xs text-base-400">{{ fill(it.common.duplicateOf, { n: issue.duplicateOf.number }) }}</span>
        <span class="nr">#{{ issue.number }}</span>
      </div>
      <h2 class="title display"><a :href="href" @click="open">{{ issue.title }}</a></h2>
      <div class="bottom">
        <span class="inline-flex min-w-0 items-center gap-1.5">
          <PlayerHead v-if="issue.author" :uuid="issue.author.uuid" :name="issue.author.name" :skin="issue.author.skin ?? null" :fetch="false" :size="18" />
          <span class="truncate">
            <template v-if="issue.author">{{ openedBy[0] }}<strong class="font-medium text-base-200">{{ issue.author.name }}</strong>{{ openedBy[1] }}</template>
            <template v-else>{{ it.common.deletedUser }}</template>
          </span>
          <span v-if="issue.authorTeam" class="team">{{ it.common.team }}</span>
        </span>
        <span class="inline-flex items-center gap-1" :title="issue.comments === 1 ? it.common.comment1 : fill(it.common.comments, { n: issue.comments })"><SiteIcon name="chat" class="size-3.5" />{{ issue.comments }}</span>
        <SiteIcon v-if="issue.locked" name="lock" class="size-3.5" :title="it.common.locked" />
        <span v-for="t in issue.tags.slice(0, 3)" :key="t" class="tag">#{{ t }}</span>
        <time class="date" :datetime="issue.createdAt">{{ date(issue.createdAt) }}</time>
      </div>
    </div>
  </article>
</template>

<style scoped>
.item {
  display: grid;
  grid-template-columns: 4.25rem minmax(0, 1fr);
  border-radius: 0.7rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
  cursor: pointer;
  transition: border-color 0.12s, background-color 0.12s;
}
.item:hover {
  border-color: var(--color-base-600);
  background: var(--color-base-850);
}
.item.closed .title {
  color: var(--color-base-400);
}
.votes {
  display: grid;
  place-items: center;
  padding: 0.75rem 0.25rem;
  border-right: 1px solid var(--color-base-800);
}
.body {
  display: grid;
  gap: 0.55rem;
  min-width: 0;
  padding: 0.85rem 1.1rem 0.9rem;
}
.top {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.4rem;
}
.nr {
  margin-left: auto;
  font-size: 0.8rem;
  color: var(--color-base-400);
  font-variant-numeric: tabular-nums;
}
.type-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.15rem 0.5rem;
  border-radius: 0.3rem;
  font-size: 0.65rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-redstone-300);
  background: color-mix(in srgb, var(--color-redstone-500) 14%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--color-redstone-500) 40%, transparent);
}
.type-badge[data-t='feature'] {
  color: var(--color-lamp-300);
  background: color-mix(in srgb, var(--color-lamp-400) 12%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--color-lamp-400) 38%, transparent);
}
.fixed-in {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.05rem 0.45rem;
  border-radius: 999px;
  font-size: 0.7rem;
  color: var(--color-ok);
  background: color-mix(in srgb, var(--color-ok) 14%, transparent);
}
.title {
  font-size: clamp(1.05rem, 0.9rem + 0.6vw, 1.35rem);
  line-height: 1.2;
  color: var(--color-base-50);
  overflow-wrap: anywhere;
}
.title a:hover {
  text-decoration: underline;
  text-underline-offset: 4px;
  text-decoration-thickness: 1px;
}
.bottom {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.35rem 0.9rem;
  font-size: 0.8rem;
  color: var(--color-base-400);
}
.team {
  padding: 0 0.35rem;
  border-radius: 999px;
  font-size: 0.65rem;
  color: var(--color-redstone-300);
  background: color-mix(in srgb, var(--color-redstone-500) 20%, transparent);
}
.tag {
  font-size: 0.72rem;
  color: var(--color-base-400);
}
.date {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
@media (max-width: 520px) {
  .item {
    grid-template-columns: 3.25rem minmax(0, 1fr);
  }
  .body {
    padding: 0.75rem 0.8rem;
  }
}
</style>
