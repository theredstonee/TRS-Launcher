<script setup lang="ts">
// Issue-Liste und Roadmap in einem (§28): Umschalter Board | Liste, Filter mit Suchsyntax + Aufklapp-Filtern, Issue
// als Fenster (?issue=<nr>), Drag & Drop fürs Team. Alles steht in der Adresse (view, q, sort, page, closed, issue)
// – teilbar, Zurück-Taste schließt das Fenster. Serverseitig anonym gerendert, im Browser mit Sitzung neu geladen.
import { ISSUE_SORTS, type IssueDetail, type IssueListResult, type IssueSort, type IssueStatus, type IssueView, type RoadmapColumnView, type RoadmapResult } from '#shared/issues'

const props = defineProps<{ defaultView: 'board' | 'list', title: string, lead: string }>()
const { it, fill, errorText } = useIssueText()
const route = useRoute()
const router = useRouter()
const { account, load, api } = useAccount()

type View = 'board' | 'list'
const view = computed<View>(() => (route.query.view === 'board' || route.query.view === 'list' ? route.query.view : props.defaultView))
const filter = computed(() => (typeof route.query.q === 'string' ? route.query.q.slice(0, 300) : ''))
const sort = computed<IssueSort>(() => (typeof route.query.sort === 'string' && (ISSUE_SORTS as readonly string[]).includes(route.query.sort) ? (route.query.sort as IssueSort) : 'top'))
const page = computed(() => Math.max(1, Math.min(100_000, Number(route.query.page) || 1)))
const closed = computed(() => route.query.closed === '1')
const modalNr = computed(() => {
  const n = Number(route.query.issue)
  return Number.isSafeInteger(n) && n > 0 ? n : null
})

function setQuery(patch: Record<string, string | number | null>, push = false) {
  const next: Record<string, string | undefined> = { ...(route.query as Record<string, string>) }
  for (const [k, v] of Object.entries(patch)) next[k] = v === null || v === '' ? undefined : String(v)
  if (!('page' in patch) && !('issue' in patch)) next.page = undefined
  void (push ? router.push({ query: next }) : router.replace({ query: next }))
}

type Data = { kind: 'board', board: RoadmapResult } | { kind: 'list', list: IssueListResult }
const fetchKey = computed(() => (view.value === 'board' ? `ib-${filter.value}` : `il-${filter.value}-${sort.value}-${page.value}-${closed.value}`))
async function fetchData(): Promise<Data> {
  if (view.value === 'board') {
    return { kind: 'board', board: await $fetch<RoadmapResult>('/v1/issues/roadmap', { query: filter.value ? { filter: filter.value } : {}, credentials: 'same-origin' }) }
  }
  const query: Record<string, string | number> = { sort: sort.value, page: page.value, per: 25 }
  if (filter.value) query.filter = filter.value
  if (closed.value) query.closed = '1'
  return { kind: 'list', list: await $fetch<IssueListResult>('/v1/issues', { query, credentials: 'same-origin' }) }
}
const { data, error, refresh, status } = await useAsyncData<Data>(() => fetchKey.value, fetchData, { deep: true })
onMounted(async () => {
  if (await load()) void refresh()
})

const board = computed<RoadmapColumnView[]>(() => (data.value?.kind === 'board' ? data.value.board.columns : []))
const list = computed<IssueListResult | null>(() => (data.value?.kind === 'list' ? data.value.list : null))
const errors = computed(() => (data.value?.kind === 'board' ? data.value.board.errors : data.value?.list.errors) ?? [])
const total = computed(() => (data.value?.kind === 'board' ? data.value.board.columns.reduce((s, c) => s + c.total, 0) : (list.value?.total ?? 0)))
const people = computed(() => {
  const all: IssueView[] = data.value?.kind === 'board' ? data.value.board.columns.flatMap((c) => c.issues) : (list.value?.issues ?? [])
  return all.flatMap((i) => [i.author?.name, i.assignee?.name]).filter((n): n is string => !!n)
})
const canMove = computed(() => !!account.value?.team?.permissions.includes('issues.manage'))
const message = ref<{ kind: 'ok' | 'error', text: string } | null>(null)
let messageTimer: ReturnType<typeof setTimeout> | null = null
function say(kind: 'ok' | 'error', text: string) {
  message.value = { kind, text }
  if (messageTimer) clearTimeout(messageTimer)
  messageTimer = setTimeout(() => (message.value = null), 5000)
}

// --- Board: Drag & Drop, Mehr laden ------------------------------------------------------------------
function takeCard(nr: number): { card: IssueView, col: RoadmapColumnView, index: number } | null {
  for (const col of board.value) {
    const index = col.issues.findIndex((i) => i.number === nr)
    if (index >= 0) return { card: col.issues[index]!, col, index }
  }
  return null
}
async function move(nr: number, from: IssueStatus, to: IssueStatus) {
  const found = takeCard(nr)
  const target = board.value.find((c) => c.status === to)
  if (!found || !target) return
  // Optimistisch verschieben …
  found.col.issues.splice(found.index, 1)
  found.col.total = Math.max(0, found.col.total - 1)
  const moved = { ...found.card, status: to }
  target.issues.unshift(moved)
  target.total++
  try {
    const r = await api<{ issue: IssueDetail }>(`/v1/admin/issues/${nr}`, { method: 'PATCH', body: { status: to } })
    Object.assign(moved, r.issue)
    say('ok', fill(it.value.board.moved, { n: nr, status: it.value.statuses[to]! }))
    void refreshNuxtData('issue-summary')
  } catch (e) {
    // … und bei Fehler zurück.
    const i = target.issues.indexOf(moved)
    if (i >= 0) target.issues.splice(i, 1)
    target.total = Math.max(0, target.total - 1)
    found.col.issues.splice(found.index, 0, found.card)
    found.col.total++
    say('error', fill(it.value.board.moveFailed, { n: nr, error: errorText(e) }))
    void from
  }
}
const loadingMore = ref<string | null>(null)
async function more(statusKey: IssueStatus) {
  const col = board.value.find((c) => c.status === statusKey)
  if (!col) return
  loadingMore.value = statusKey
  try {
    const q: Record<string, string | number> = { column: statusKey, offset: col.issues.length }
    if (filter.value) q.filter = filter.value
    const r = await $fetch<{ column: RoadmapColumnView }>('/v1/issues/roadmap', { query: q, credentials: 'same-origin' })
    const known = new Set(col.issues.map((i) => i.number))
    col.issues.push(...r.column.issues.filter((i) => !known.has(i.number)))
    col.total = r.column.total
    col.hasMore = r.column.hasMore
  } catch (e) {
    say('error', errorText(e))
  } finally {
    loadingMore.value = null
  }
}

// --- Fenster ------------------------------------------------------------------------------------------
let pushed = false
function openIssue(nr: number) {
  pushed = true
  setQuery({ issue: nr }, true)
}
function closeIssue() {
  if (pushed && window.history.state?.back) {
    pushed = false
    router.back()
  } else setQuery({ issue: null })
}
/** Änderungen aus dem Fenster (Stimme, Status, Folgen …) in Board/Liste übernehmen. */
function onChanged(d: IssueDetail) {
  const patch: Partial<IssueView> = {
    status: d.status, score: d.score, up: d.up, down: d.down, myVote: d.myVote, comments: d.comments, priority: d.priority,
    assignee: d.assignee, tags: d.tags, fixedIn: d.fixedIn, locked: d.locked, title: d.title, type: d.type, area: d.area,
  }
  if (data.value?.kind === 'list') {
    const row = data.value.list.issues.find((i) => i.number === d.number)
    if (row) Object.assign(row, patch)
    return
  }
  const found = takeCard(d.number)
  if (!found) return
  if (found.col.status !== d.status) {
    const target = board.value.find((c) => c.status === d.status)
    found.col.issues.splice(found.index, 1)
    found.col.total = Math.max(0, found.col.total - 1)
    if (target) {
      target.issues.unshift(Object.assign(found.card, patch))
      target.total++
    }
  } else Object.assign(found.card, patch)
}
function onVoted(nr: number, r: { score: number, up: number, down: number, myVote: -1 | 0 | 1 }) {
  const row = list.value?.issues.find((i) => i.number === nr)
  if (row) Object.assign(row, r)
}

const pageLink = (p: number) => ({ query: { ...route.query, page: p > 1 ? String(p) : undefined } })
</script>

<template>
  <div class="explorer">
    <header class="flex flex-wrap items-end justify-between gap-4">
      <div class="min-w-0">
        <h1 class="display flex items-baseline gap-3 text-4xl leading-none text-base-50 sm:text-5xl">
          {{ title }}<span class="font-sans text-sm font-normal text-base-400 tabular-nums">{{ total === 1 ? it.ws.count1 : fill(it.ws.count, { n: total }) }}</span>
        </h1>
        <p class="mt-3 max-w-2xl text-base-400">{{ lead }}</p>
      </div>
      <div class="seg" role="group" :aria-label="it.ws.view">
        <button type="button" :aria-pressed="view === 'board'" data-testid="view-board" @click="setQuery({ view: 'board', page: null })">
          <SiteIcon name="roadmap" class="size-4" />{{ it.ws.board }}
        </button>
        <button type="button" :aria-pressed="view === 'list'" data-testid="view-list" @click="setQuery({ view: 'list' })">
          <SiteIcon name="list" class="size-4" />{{ it.ws.list }}
        </button>
      </div>
    </header>

    <IssueFilterBar class="mt-6" :model-value="filter" :errors="errors" :people="people" @update:model-value="setQuery({ q: $event })">
      <template v-if="view === 'list'">
        <select class="drop-select" :value="sort" :aria-label="it.list.sort" @change="setQuery({ sort: ($event.target as HTMLSelectElement).value === 'top' ? null : ($event.target as HTMLSelectElement).value })">
          <option v-for="s in ISSUE_SORTS" :key="s" :value="s">{{ it.list.sort }}: {{ it.sorts[s] }}</option>
        </select>
        <label class="closed-toggle">
          <input type="checkbox" :checked="closed" @change="setQuery({ closed: ($event.target as HTMLInputElement).checked ? '1' : null })" />{{ it.filter.showClosed }}
        </label>
      </template>
    </IssueFilterBar>

    <p v-if="!account" class="hint mt-4"><SiteIcon name="user" class="size-4 shrink-0" />{{ it.list.signInHint }}</p>
    <p v-else-if="view === 'board' && canMove" class="hint team mt-4"><SiteIcon name="grip" class="size-4 shrink-0" />{{ it.board.dragHint }}</p>
    <p v-if="message" class="toast-line mt-3" :class="message.kind" role="status">{{ message.text }}</p>

    <p v-if="error" class="mt-8 text-lamp-300">{{ errorText(error) }}</p>
    <div v-else-if="!data && status === 'pending'" class="mt-6 grid gap-3 sm:grid-cols-3">
      <div v-for="i in 6" :key="i" class="skeleton h-40 rounded-xl" />
    </div>
    <IssueBoard
      v-else-if="data?.kind === 'board'"
      class="mt-5"
      :columns="board"
      :can-move="canMove"
      :loading-more="loadingMore"
      @open="openIssue"
      @move="move"
      @more="more"
    />
    <template v-else-if="list">
      <div v-if="!list.issues.length" class="card mt-5 px-6 py-12 text-center">
        <SiteIcon name="bug" class="mx-auto size-9 text-base-400" />
        <p class="mt-3 text-base-300">{{ filter || closed ? it.list.noResults : it.list.empty }}</p>
      </div>
      <IssueTable v-else class="mt-5" :issues="list.issues" :signed-in="!!account" @open="openIssue" @voted="onVoted" @error="say('error', $event)" />
      <nav v-if="list.pages > 1" class="mt-5 flex items-center justify-center gap-2 text-sm" :aria-label="fill(it.list.page, { page: list.page, pages: list.pages })">
        <NuxtLink v-if="list.page > 1" :to="pageLink(list.page - 1)" class="btn-icon" :aria-label="it.list.prev"><SiteIcon name="back" class="size-4" /></NuxtLink>
        <span class="text-base-400 tabular-nums">{{ fill(it.list.page, { page: list.page, pages: list.pages }) }}</span>
        <NuxtLink v-if="list.page < list.pages" :to="pageLink(list.page + 1)" class="btn-icon" :aria-label="it.list.next"><SiteIcon name="arrow" class="size-4" /></NuxtLink>
      </nav>
    </template>

    <ClientOnly>
      <IssueModal v-if="modalNr" :key="modalNr" :nr="modalNr" @close="closeIssue" @changed="onChanged" />
    </ClientOnly>
  </div>
</template>

<style scoped>
.seg {
  display: inline-flex;
  padding: 0.2rem;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
}
.seg button {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.4rem 0.8rem;
  border-radius: 0.45rem;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--color-base-400);
}
.seg button:hover {
  color: var(--color-base-50);
}
.seg button[aria-pressed='true'] {
  background: var(--color-base-800);
  color: var(--color-base-50);
  box-shadow: inset 0 -2px 0 var(--color-redstone-500);
}
.drop-select {
  padding: 0.35rem 0.6rem;
  border-radius: 0.45rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
  font-size: 0.8rem;
  color: var(--color-base-200);
}
.closed-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  padding: 0.35rem 0.6rem;
  border-radius: 0.45rem;
  border: 1px solid var(--color-base-800);
  font-size: 0.8rem;
  color: var(--color-base-200);
  cursor: pointer;
}
.closed-toggle input {
  accent-color: var(--color-redstone-500);
}
.hint {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.55rem 0.8rem;
  border-radius: 0.6rem;
  border: 1px dashed var(--color-base-700);
  font-size: 0.8rem;
  color: var(--color-base-200);
}
.hint.team {
  border-color: color-mix(in srgb, var(--color-redstone-500) 45%, transparent);
}
.toast-line {
  padding: 0.5rem 0.8rem;
  border-radius: 0.5rem;
  font-size: 0.8rem;
}
.toast-line.ok {
  background: color-mix(in srgb, var(--color-ok) 12%, transparent);
  color: var(--color-ok);
}
.toast-line.error {
  background: color-mix(in srgb, var(--color-redstone-500) 14%, transparent);
  color: var(--color-redstone-300);
}
</style>
