<script setup lang="ts">
// Öffentliche Issue-Liste (§28): Fehler und Wünsche für Launcher, TRS Client und Website mit Hoch-/Runter-Stimmen,
// Filtern, Suche und Seiten. Serverseitig ohne persönliche Daten gerendert; im Browser mit Sitzung neu geladen
// (eigene Stimmen). Filter stehen in der Adresse (teilbar, Zurück-Taste).
import { breadcrumbLd } from '#shared/seo'
import { ISSUE_AREAS, ISSUE_SORTS, ISSUE_STATUSES, ISSUE_TYPES, isClosed, type IssueListResult, type IssueView } from '#shared/issues'

const { it, lang, fill, date, errorText } = useIssueText()
const { m } = useLang()
const lp = useLocalePath()
const route = useRoute()
const router = useRouter()
const siteUrl = useSiteUrl()
const { account, load } = useAccount()

const one = <T extends string>(v: unknown, allowed: readonly T[]): T | '' => (typeof v === 'string' && (allowed as readonly string[]).includes(v) ? (v as T) : '')
const filters = computed(() => ({
  sort: one(route.query.sort, ISSUE_SORTS) || 'top',
  type: one(route.query.type, ISSUE_TYPES),
  area: one(route.query.area, ISSUE_AREAS),
  status: one(route.query.status, ISSUE_STATUSES),
  closed: route.query.closed === '1',
  q: typeof route.query.q === 'string' ? route.query.q.slice(0, 80) : '',
  page: Math.max(1, Math.min(100_000, Number(route.query.page) || 1)),
}))

function apiQuery() {
  const f = filters.value
  const q: Record<string, string | number> = { sort: f.sort, page: f.page, per: 20 }
  if (f.type) q.type = f.type
  if (f.area) q.area = f.area
  if (f.status) q.status = f.status
  if (f.closed) q.closed = '1'
  if (f.q.trim()) q.q = f.q.trim()
  return q
}

const { data, error, refresh, status: loadState } = await useAsyncData<IssueListResult>(
  () => `issues-${JSON.stringify(apiQuery())}`,
  () => $fetch<IssueListResult>('/v1/issues', { query: apiQuery(), credentials: 'same-origin' }),
  { watch: [filters] },
)
const issues = computed<IssueView[]>(() => data.value?.issues ?? [])

onMounted(async () => {
  if (await load()) void refresh()
})

function setQuery(patch: Record<string, string | number | boolean | undefined>) {
  const next: Record<string, string | undefined> = { ...(route.query as Record<string, string>) }
  for (const [k, v] of Object.entries(patch)) next[k] = v === '' || v === false || v === undefined ? undefined : String(v)
  if (!('page' in patch)) next.page = undefined
  if (next.sort === 'top') next.sort = undefined
  void router.replace({ query: next })
}

// Suche entprellt in die Adresse schreiben.
const search = ref(filters.value.q)
let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(search, (v) => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => setQuery({ q: v.trim() }), 300)
})
watch(() => filters.value.q, (v) => {
  if (v !== search.value.trim()) search.value = v
})
const filtered = computed(() => !!(filters.value.type || filters.value.area || filters.value.status || filters.value.q || filters.value.closed))
function reset() {
  search.value = ''
  void router.replace({ query: route.query.lang ? { lang: route.query.lang } : {} })
}

const voteError = ref('')
function onVoted(i: IssueView, r: { score: number, up: number, down: number, myVote: -1 | 0 | 1 }) {
  Object.assign(i, r)
}

/** Seitenzahlen: erste, letzte, um die aktuelle herum; Lücken als null. */
const pageList = computed<(number | null)[]>(() => {
  const pages = data.value?.pages ?? 1
  const cur = data.value?.page ?? 1
  const set = new Set([1, pages, cur - 1, cur, cur + 1].filter((p) => p >= 1 && p <= pages))
  const sorted = [...set].sort((a, b) => a - b)
  const out: (number | null)[] = []
  sorted.forEach((p, i) => {
    if (i > 0 && p - sorted[i - 1]! > 1) out.push(null)
    out.push(p)
  })
  return out
})
const pageLink = (p: number) => ({ query: { ...route.query, page: p > 1 ? String(p) : undefined } })

usePageSeo(() => ({
  path: '/issues',
  title: it.value.seo.list.title,
  description: it.value.seo.list.description,
  jsonLd: [breadcrumbLd(siteUrl, lang.value, [{ name: m.value.nav.home, path: '/' }, { name: it.value.list.title, path: '/issues' }])],
}))
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-12 pb-6 sm:px-6">
    <header class="flex flex-wrap items-end justify-between gap-6">
      <div class="max-w-2xl">
        <h1 class="display text-5xl leading-tight text-base-50">{{ it.list.title }}</h1>
        <p class="mt-3 text-lg text-base-400">{{ it.list.lead }}</p>
      </div>
      <div class="flex flex-wrap gap-2">
        <NuxtLink :to="lp('/roadmap')" class="btn btn-ghost"><SiteIcon name="roadmap" class="size-4" />{{ it.list.roadmap }}</NuxtLink>
        <NuxtLink v-if="account" :to="lp('/issues/mine')" class="btn btn-ghost"><SiteIcon name="bell" class="size-4" />{{ it.list.mine }}</NuxtLink>
        <NuxtLink :to="lp('/issues/new')" class="btn btn-primary" data-testid="new-issue"><SiteIcon name="plus" class="size-4" />{{ it.list.newIssue }}</NuxtLink>
      </div>
    </header>

    <div class="toolbar mt-8">
      <div class="relative min-w-0 flex-1 basis-64">
        <SiteIcon name="search" class="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-base-400" />
        <input v-model="search" type="search" class="field pl-9" maxlength="80" :placeholder="it.list.search" :aria-label="it.list.search" />
      </div>
      <select class="field w-auto" :aria-label="it.list.type" :value="filters.type" @change="setQuery({ type: ($event.target as HTMLSelectElement).value })">
        <option value="">{{ it.list.type }}: {{ it.list.all }}</option>
        <option v-for="t in ISSUE_TYPES" :key="t" :value="t">{{ it.types[t] }}</option>
      </select>
      <select class="field w-auto" :aria-label="it.list.area" :value="filters.area" @change="setQuery({ area: ($event.target as HTMLSelectElement).value })">
        <option value="">{{ it.list.area }}: {{ it.list.all }}</option>
        <option v-for="a in ISSUE_AREAS" :key="a" :value="a">{{ it.areas[a] }}</option>
      </select>
      <select class="field w-auto" :aria-label="it.list.status" :value="filters.status" @change="setQuery({ status: ($event.target as HTMLSelectElement).value })">
        <option value="">{{ it.list.status }}: {{ it.list.allStatuses }}</option>
        <option v-for="s in ISSUE_STATUSES" :key="s" :value="s">{{ it.statuses[s] }}</option>
      </select>
      <select class="field w-auto" :aria-label="it.list.sort" :value="filters.sort" @change="setQuery({ sort: ($event.target as HTMLSelectElement).value })">
        <option v-for="s in ISSUE_SORTS" :key="s" :value="s">{{ it.list.sort }}: {{ it.sorts[s] }}</option>
      </select>
      <label class="closed-toggle">
        <input type="checkbox" :checked="filters.closed" @change="setQuery({ closed: ($event.target as HTMLInputElement).checked ? '1' : '' })" />
        <span>{{ it.list.showClosed }}</span>
      </label>
    </div>

    <div class="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-base-400">
      <span v-if="data" class="tabular-nums">{{ data.total === 1 ? it.list.total1 : fill(it.list.total, { n: data.total }) }}</span>
      <button v-if="filtered" type="button" class="text-base-200 underline-offset-4 hover:underline" @click="reset">{{ it.list.reset }}</button>
    </div>

    <p v-if="!account" class="login-hint mt-4">
      <SiteIcon name="user" class="size-4 shrink-0" />
      <span>{{ it.list.signInHint }}</span>
    </p>
    <p v-if="voteError" role="alert" class="mt-3 text-sm text-redstone-300">{{ voteError }}</p>

    <p v-if="error" class="mt-10 text-lamp-300">{{ errorText(error) }}</p>
    <div v-else-if="!issues.length" class="card mt-6 px-6 py-12 text-center">
      <SiteIcon name="bug" class="mx-auto size-9 text-base-400" />
      <p class="mt-3 text-base-300">{{ filtered ? it.list.noResults : it.list.empty }}</p>
    </div>
    <ul v-else class="issue-list card mt-4" :aria-busy="loadState === 'pending'">
      <li v-for="i in issues" :key="i.number" class="issue-row" :class="{ closed: isClosed(i.status) }">
        <IssueVote
          :number="i.number"
          :score="i.score"
          :my-vote="i.myVote ?? 0"
          :up="i.up"
          :down="i.down"
          :closed="isClosed(i.status)"
          :signed-in="!!account"
          @voted="onVoted(i, $event)"
          @error="voteError = $event"
        />
        <span class="type-icon" :data-t="i.type" :title="it.types[i.type]">
          <SiteIcon :name="i.type === 'bug' ? 'bug' : 'bolt'" class="size-4.5" />
        </span>
        <div class="min-w-0 flex-1">
          <NuxtLink :to="lp(`/issues/${i.number}`)" class="title">{{ i.title }}</NuxtLink>
          <div class="meta">
            <IssueStatus :status="i.status" />
            <span class="badge area">{{ it.areas[i.area] }}</span>
            <span class="badge kind" :data-t="i.type">{{ it.types[i.type] }}</span>
            <span v-if="i.fixedIn" class="badge fixed-in"><SiteIcon name="check" class="size-3" />{{ fill(it.common.fixedIn, { v: i.fixedIn }) }}</span>
            <span v-if="i.duplicateOf" class="badge area">{{ fill(it.common.duplicateOf, { n: i.duplicateOf.number }) }}</span>
            <span v-for="t in i.tags" :key="t" class="badge tag">{{ t }}</span>
            <span v-if="i.locked" class="text-base-400" :title="it.common.locked"><SiteIcon name="lock" class="size-3.5" /></span>
            <span class="num">#{{ i.number }}</span>
          </div>
        </div>
        <div class="side">
          <span class="author">
            <PlayerHead v-if="i.author" :uuid="i.author.uuid" :name="i.author.name" :skin="i.author.skin ?? null" :fetch="false" :size="18" />
            <span class="truncate">{{ i.author?.name ?? it.common.deletedUser }}</span>
            <span v-if="i.authorTeam" class="badge team">{{ it.common.team }}</span>
          </span>
          <span class="text-xs text-base-400"><time :datetime="i.createdAt">{{ date(i.createdAt) }}</time></span>
          <span class="comments" :title="i.comments === 1 ? it.common.comment1 : fill(it.common.comments, { n: i.comments })">
            <SiteIcon name="chat" class="size-3.5" />{{ i.comments }}
          </span>
        </div>
      </li>
    </ul>

    <nav v-if="data && data.pages > 1" class="mt-6 flex flex-wrap items-center justify-center gap-1.5" :aria-label="fill(it.list.page, { page: data.page, pages: data.pages })">
      <NuxtLink v-if="data.page > 1" :to="pageLink(data.page - 1)" class="btn-icon" :aria-label="it.list.prev"><SiteIcon name="back" class="size-4" /></NuxtLink>
      <template v-for="(p, idx) in pageList" :key="idx">
        <span v-if="p === null" class="px-1 text-base-400">…</span>
        <NuxtLink v-else :to="pageLink(p)" class="page" :aria-current="p === data.page ? 'page' : undefined" :aria-label="fill(it.list.pageN, { n: p })">{{ p }}</NuxtLink>
      </template>
      <NuxtLink v-if="data.page < data.pages" :to="pageLink(data.page + 1)" class="btn-icon" :aria-label="it.list.next"><SiteIcon name="arrow" class="size-4" /></NuxtLink>
    </nav>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}
.toolbar select {
  flex: 0 1 auto;
  max-width: 100%;
}
.closed-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  background: var(--color-base-800);
  font-size: 0.875rem;
  color: var(--color-base-200);
  cursor: pointer;
  user-select: none;
}
.closed-toggle input {
  accent-color: var(--color-redstone-500);
}
.login-hint {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.6rem 0.85rem;
  border-radius: 0.6rem;
  border: 1px dashed var(--color-base-700);
  font-size: 0.85rem;
  color: var(--color-base-200);
}
.issue-list {
  overflow: hidden;
}
.issue-row {
  display: flex;
  align-items: center;
  gap: 0.9rem;
  padding: 0.85rem 1rem 0.85rem 0.6rem;
  border-top: 1px solid var(--color-base-800);
  transition: background-color 0.12s;
}
.issue-row:first-child {
  border-top: 0;
}
.issue-row:hover {
  background: var(--color-base-850);
}
.issue-row.closed .title {
  color: var(--color-base-400);
}
.type-icon {
  display: grid;
  place-items: center;
  width: 2.1rem;
  height: 2.1rem;
  flex-shrink: 0;
  border-radius: 0.45rem;
  background: color-mix(in srgb, var(--color-redstone-500) 14%, transparent);
  color: var(--color-redstone-300);
}
.type-icon[data-t='feature'] {
  background: color-mix(in srgb, var(--color-lamp-400) 14%, transparent);
  color: var(--color-lamp-300);
}
.title {
  display: block;
  font-weight: 600;
  color: var(--color-base-50);
  overflow-wrap: anywhere;
}
.title:hover {
  color: var(--color-redstone-300);
}
.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.35rem;
  margin-top: 0.35rem;
}
.badge.area {
  background: var(--color-base-800);
  color: var(--color-base-200);
}
.badge.kind {
  background: color-mix(in srgb, var(--color-redstone-500) 12%, transparent);
  color: var(--color-redstone-300);
}
.badge.kind[data-t='feature'] {
  background: color-mix(in srgb, var(--color-lamp-400) 12%, transparent);
  color: var(--color-lamp-300);
}
.badge.fixed-in {
  background: color-mix(in srgb, var(--color-ok) 14%, transparent);
  color: var(--color-ok);
}
.badge.tag {
  background: transparent;
  color: var(--color-base-400);
  box-shadow: inset 0 0 0 1px var(--color-base-700);
}
.badge.team {
  background: color-mix(in srgb, var(--color-redstone-500) 20%, transparent);
  color: var(--color-redstone-300);
}
.num {
  font-size: 0.75rem;
  color: var(--color-base-400);
  font-variant-numeric: tabular-nums;
}
.side {
  display: grid;
  justify-items: end;
  gap: 0.2rem;
  flex-shrink: 0;
  width: 11rem;
  text-align: right;
}
.author {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  max-width: 100%;
  font-size: 0.85rem;
  color: var(--color-base-200);
}
.comments {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.75rem;
  color: var(--color-base-400);
  font-variant-numeric: tabular-nums;
}
.page {
  display: inline-grid;
  place-items: center;
  min-width: 2.25rem;
  height: 2.25rem;
  padding: 0 0.5rem;
  border-radius: 0.375rem;
  background: var(--color-base-800);
  font-size: 0.875rem;
  color: var(--color-base-200);
  font-variant-numeric: tabular-nums;
}
.page:hover {
  background: var(--color-base-700);
}
.page[aria-current='page'] {
  background: var(--color-redstone-500);
  color: white;
}
@media (max-width: 640px) {
  .issue-row {
    flex-wrap: wrap;
    align-items: flex-start;
    gap: 0.6rem;
    padding: 0.75rem;
  }
  .type-icon {
    display: none;
  }
  .side {
    width: auto;
    flex-basis: 100%;
    display: flex;
    justify-content: flex-start;
    align-items: center;
    gap: 0.75rem;
    padding-left: 3.4rem;
    text-align: left;
  }
}
</style>
