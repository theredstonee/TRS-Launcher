<script setup lang="ts">
// Liste als dichte Tabelle (§28): Stimmen, Art, Titel, Bereich, Status, Priorität, Zuständig, Datum. Klick auf den
// Titel öffnet das Issue als Fenster (Strg/Mittelklick: neuer Tab). Auf dem Handy werden Nebenspalten ausgeblendet.
import { isClosed, type IssueView } from '#shared/issues'

defineProps<{ issues: IssueView[], signedIn: boolean }>()
const emit = defineEmits<{ open: [nr: number], voted: [nr: number, r: { score: number, up: number, down: number, myVote: -1 | 0 | 1 }], error: [message: string] }>()
const { it, fill, date } = useIssueText()
const lp = useLocalePath()

function open(e: MouseEvent, nr: number) {
  if (e.ctrlKey || e.metaKey || e.shiftKey || e.button === 1) return
  e.preventDefault()
  emit('open', nr)
}
</script>

<template>
  <div class="tbl-wrap card">
    <table class="tbl" data-testid="issue-table">
      <thead>
        <tr>
          <th class="c-vote">{{ it.table.score }}</th>
          <th class="c-title">{{ it.table.title }}</th>
          <th class="c-area">{{ it.table.area }}</th>
          <th class="c-status">{{ it.table.status }}</th>
          <th class="c-prio">{{ it.table.priority }}</th>
          <th class="c-assignee">{{ it.table.assignee }}</th>
          <th class="c-date">{{ it.table.date }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="i in issues" :key="i.number" :class="{ closed: isClosed(i.status) }">
          <td class="c-vote">
            <IssueVote
              :number="i.number"
              :score="i.score"
              :my-vote="i.myVote ?? 0"
              :up="i.up"
              :down="i.down"
              :closed="isClosed(i.status)"
              :signed-in="signedIn"
              @voted="emit('voted', i.number, $event)"
              @error="emit('error', $event)"
            />
          </td>
          <td class="c-title">
            <div class="flex items-start gap-2.5">
              <span class="type" :data-t="i.type" :title="it.types[i.type]"><SiteIcon :name="i.type === 'bug' ? 'bug' : 'bolt'" class="size-4" /></span>
              <div class="min-w-0">
                <a :href="lp(`/issues/${i.number}`)" class="title" @click="open($event, i.number)">{{ i.title }}</a>
                <div class="sub">
                  <span class="tabular-nums">#{{ i.number }}</span>
                  <span class="inline-flex items-center gap-1">
                    <PlayerHead v-if="i.author" :uuid="i.author.uuid" :name="i.author.name" :skin="i.author.skin ?? null" :fetch="false" :size="14" />
                    {{ i.author?.name ?? it.common.deletedUser }}<span v-if="i.authorTeam" class="team">{{ it.common.team }}</span>
                  </span>
                  <span class="inline-flex items-center gap-1" :title="i.comments === 1 ? it.common.comment1 : fill(it.common.comments, { n: i.comments })"><SiteIcon name="chat" class="size-3" />{{ i.comments }}</span>
                  <span v-if="i.fixedIn" class="fixed-in"><SiteIcon name="check" class="size-3" />{{ i.fixedIn }}</span>
                  <span v-if="i.duplicateOf">{{ fill(it.common.duplicateOf, { n: i.duplicateOf.number }) }}</span>
                  <span v-for="t in i.tags" :key="t" class="tag">{{ t }}</span>
                  <SiteIcon v-if="i.locked" name="lock" class="size-3" :title="it.common.locked" />
                  <span class="mobile-meta"><IssueArea :area="i.area" /><IssueStatus :status="i.status" /></span>
                </div>
              </div>
            </div>
          </td>
          <td class="c-area"><IssueArea :area="i.area" /></td>
          <td class="c-status"><IssueStatus :status="i.status" /></td>
          <td class="c-prio"><IssuePriority :priority="i.priority" /></td>
          <td class="c-assignee">
            <span v-if="i.assignee" class="inline-flex max-w-full items-center gap-1.5 truncate">
              <PlayerHead :uuid="i.assignee.uuid" :name="i.assignee.name" :skin="i.assignee.skin ?? null" :fetch="false" :size="18" />{{ i.assignee.name }}
            </span>
            <span v-else class="text-base-600">—</span>
          </td>
          <td class="c-date"><time :datetime="i.createdAt">{{ date(i.createdAt) }}</time></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.tbl-wrap {
  overflow-x: auto;
}
.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.85rem;
}
th {
  position: sticky;
  top: 0;
  padding: 0.6rem 0.75rem;
  border-bottom: 1px solid var(--color-base-800);
  background: var(--color-base-900);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-align: left;
  text-transform: uppercase;
  color: var(--color-base-400);
  white-space: nowrap;
}
td {
  padding: 0.55rem 0.75rem;
  border-top: 1px solid var(--color-base-800);
  vertical-align: middle;
  color: var(--color-base-200);
}
tbody tr {
  transition: background-color 0.1s;
}
tbody tr:hover {
  background: var(--color-base-850);
}
tr.closed .title {
  color: var(--color-base-400);
}
.c-vote {
  width: 3.5rem;
  padding-inline: 0.4rem;
  text-align: center;
}
.c-title {
  min-width: 18rem;
}
.c-area,
.c-status,
.c-prio {
  white-space: nowrap;
}
.c-assignee {
  max-width: 10rem;
  white-space: nowrap;
}
.c-date {
  white-space: nowrap;
  font-size: 0.78rem;
  color: var(--color-base-400);
}
.type {
  display: grid;
  place-items: center;
  width: 1.75rem;
  height: 1.75rem;
  flex-shrink: 0;
  margin-top: 0.1rem;
  border-radius: 0.4rem;
  background: color-mix(in srgb, var(--color-redstone-500) 14%, transparent);
  color: var(--color-redstone-300);
}
.type[data-t='feature'] {
  background: color-mix(in srgb, var(--color-lamp-400) 14%, transparent);
  color: var(--color-lamp-300);
}
.title {
  font-weight: 600;
  color: var(--color-base-50);
  overflow-wrap: anywhere;
}
.title:hover {
  color: var(--color-redstone-300);
}
.sub {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.25rem 0.75rem;
  margin-top: 0.2rem;
  font-size: 11px;
  color: var(--color-base-400);
}
.team {
  margin-left: 0.3rem;
  padding: 0 0.35rem;
  border-radius: 999px;
  background: color-mix(in srgb, var(--color-redstone-500) 20%, transparent);
  color: var(--color-redstone-300);
  font-weight: 600;
}
.tag {
  padding: 0 0.4rem;
  border-radius: 999px;
  box-shadow: inset 0 0 0 1px var(--color-base-700);
}
.fixed-in {
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
  font-weight: 600;
  color: var(--color-ok);
}
.mobile-meta {
  display: none;
}
@media (max-width: 899px) {
  .c-prio,
  .c-assignee,
  .c-date {
    display: none;
  }
}
@media (max-width: 639px) {
  .c-area,
  .c-status {
    display: none;
  }
  .c-title {
    min-width: 0;
  }
  .mobile-meta {
    display: inline-flex;
    gap: 0.35rem;
  }
  thead {
    display: none;
  }
}
</style>
