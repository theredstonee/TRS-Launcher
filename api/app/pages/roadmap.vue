<script setup lang="ts">
// Roadmap (§28): Board „Geplant / In Arbeit / Fertig“ aus den Issues – öffentlich, serverseitig gerendert.
// Die Spalten sind wie eine Redstone-Leitung verbunden: Geplant (aus), In Arbeit (Signal läuft), Fertig (Lampe an).
import { breadcrumbLd } from '#shared/seo'
import type { IssueView, RoadmapResult } from '#shared/issues'

const { it, lang, fill, date } = useIssueText()
const { m } = useLang()
const lp = useLocalePath()
const siteUrl = useSiteUrl()

const { data, error } = await useAsyncData<RoadmapResult>('roadmap', () => $fetch<RoadmapResult>('/v1/issues/roadmap'))
const columns = computed(() => {
  const d = data.value
  return [
    { id: 'planned', title: it.value.roadmap.planned, hint: '', items: d?.planned ?? [] },
    { id: 'progress', title: it.value.roadmap.inProgress, hint: '', items: d?.inProgress ?? [] },
    { id: 'done', title: it.value.roadmap.done, hint: fill(it.value.roadmap.doneHint, { n: d?.doneDays ?? 30 }), items: d?.done ?? [] },
  ]
})
const when = (i: IssueView) => (i.closedAt ? date(i.closedAt) : date(i.activityAt))

usePageSeo(() => ({
  path: '/roadmap',
  title: it.value.seo.roadmap.title,
  description: it.value.seo.roadmap.description,
  jsonLd: [breadcrumbLd(siteUrl, lang.value, [{ name: m.value.nav.home, path: '/' }, { name: it.value.roadmap.title, path: '/roadmap' }])],
}))
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-12 pb-6 sm:px-6">
    <header class="flex flex-wrap items-end justify-between gap-6">
      <div class="max-w-2xl">
        <h1 class="display text-5xl leading-tight text-base-50">{{ it.roadmap.title }}</h1>
        <p class="mt-3 text-lg text-base-400">{{ it.roadmap.lead }}</p>
      </div>
      <div class="flex flex-wrap gap-2">
        <NuxtLink :to="lp('/issues')" class="btn btn-ghost"><SiteIcon name="list" class="size-4" />{{ it.roadmap.allIssues }}</NuxtLink>
        <NuxtLink :to="lp('/issues/new')" class="btn btn-primary"><SiteIcon name="plus" class="size-4" />{{ it.list.newIssue }}</NuxtLink>
      </div>
    </header>

    <p v-if="error" class="mt-10 text-lamp-300">{{ m.common.error }}</p>
    <div v-else class="board mt-10">
      <section v-for="col in columns" :key="col.id" class="column" :data-col="col.id">
        <header class="col-head">
          <span class="lamp" aria-hidden="true" />
          <h2 class="heading text-lg">{{ col.title }}</h2>
          <span class="count tabular-nums">{{ col.items.length }}</span>
          <span v-if="col.hint" class="ml-auto text-xs text-base-400">{{ col.hint }}</span>
        </header>
        <p v-if="!col.items.length" class="empty">{{ it.roadmap.empty }}</p>
        <ul v-else class="space-y-2.5">
          <li v-for="i in col.items" :key="i.number">
            <NuxtLink :to="lp(`/issues/${i.number}`)" class="card card-hover item">
              <div class="flex items-start gap-2">
                <SiteIcon :name="i.type === 'bug' ? 'bug' : 'bolt'" class="mt-0.5 size-4 shrink-0" :class="i.type === 'bug' ? 'text-redstone-300' : 'text-lamp-300'" />
                <span class="min-w-0 flex-1 font-medium break-words text-base-50">{{ i.title }}</span>
              </div>
              <div class="mt-2.5 flex flex-wrap items-center gap-1.5 text-xs">
                <span class="badge bg-base-800 text-base-200">{{ it.areas[i.area] }}</span>
                <IssueStatus v-if="i.status === 'in_review'" :status="i.status" />
                <span v-if="i.fixedIn" class="badge fixed"><SiteIcon name="check" class="size-3" />{{ i.fixedIn }}</span>
                <span v-if="i.priority === 'critical' || i.priority === 'high'" class="badge prio" :data-p="i.priority">{{ it.priorities[i.priority] }}</span>
                <span class="ml-auto flex items-center gap-2 text-base-400">
                  <span class="inline-flex items-center gap-0.5 tabular-nums" :title="fill(it.common.score, { n: i.score })"><SiteIcon name="voteUp" class="size-3.5" />{{ i.score }}</span>
                  <span>#{{ i.number }}</span>
                </span>
              </div>
              <p v-if="col.id === 'done'" class="mt-1.5 text-[11px] text-base-400">{{ when(i) }}</p>
            </NuxtLink>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<style scoped>
.board {
  display: grid;
  gap: 1.25rem;
}
@media (min-width: 860px) {
  .board {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    align-items: start;
  }
}
.column {
  position: relative;
  padding: 1rem;
  border-radius: 0.9rem;
  background: color-mix(in srgb, var(--color-base-900) 70%, transparent);
  border: 1px solid var(--color-base-800);
}
.col-head {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  margin-bottom: 0.9rem;
}
.lamp {
  width: 0.85rem;
  height: 0.85rem;
  border-radius: 2px;
  background: var(--color-base-700);
  box-shadow: inset 0 0 0 2px var(--color-base-600);
}
.column[data-col='progress'] .lamp {
  background: var(--color-lamp-400);
  box-shadow: 0 0 10px var(--color-lamp-400);
  animation: var(--animate-lamp);
}
.column[data-col='done'] .lamp {
  background: var(--color-ok);
  box-shadow: 0 0 10px color-mix(in srgb, var(--color-ok) 70%, transparent);
}
.column[data-col='progress'] {
  border-color: color-mix(in srgb, var(--color-lamp-400) 30%, var(--color-base-800));
}
/* Redstone-Leitung zwischen den Spalten (nur nebeneinander). */
@media (min-width: 860px) {
  .column + .column::before {
    content: '';
    position: absolute;
    top: 1.45rem;
    left: -1.25rem;
    width: 1.25rem;
    height: 2px;
    background: var(--color-redstone-600);
    box-shadow: 0 0 6px var(--color-redstone-500);
  }
}
.count {
  padding: 0.05rem 0.45rem;
  border-radius: 999px;
  background: var(--color-base-800);
  font-size: 0.75rem;
  color: var(--color-base-200);
}
.item {
  display: block;
  padding: 0.8rem 0.9rem;
}
.empty {
  padding: 1.25rem 0.5rem;
  text-align: center;
  font-size: 0.875rem;
  color: var(--color-base-400);
  border: 1px dashed var(--color-base-700);
  border-radius: 0.6rem;
}
.badge.fixed {
  background: color-mix(in srgb, var(--color-ok) 14%, transparent);
  color: var(--color-ok);
}
.badge.prio {
  background: color-mix(in srgb, var(--color-lamp-400) 14%, transparent);
  color: var(--color-lamp-300);
}
.badge.prio[data-p='critical'] {
  background: color-mix(in srgb, var(--color-redstone-500) 18%, transparent);
  color: var(--color-redstone-300);
}
</style>
