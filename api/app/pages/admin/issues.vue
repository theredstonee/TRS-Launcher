<script setup lang="ts">
// Issues im Team-Bereich (§28.4, Recht issues.manage oder issues.moderate): Triage-Liste mit Ansichten (neu & ohne
// Zuständige, mir zugewiesen, gelöscht), Filtern und Suche. Bearbeitet wird auf der Issue-Seite (Team-Leiste rechts).
import { ISSUE_AREAS, ISSUE_PRIORITIES, ISSUE_STATUSES, ISSUE_TYPES, type IssueListResult, type IssueView } from '#shared/issues'

const { a, rel, when } = useAdminText()
const { it, fill } = useIssueText()
const { api } = useAdmin()

const view = ref<'all' | 'unassigned' | 'mine' | 'deleted'>('unassigned')
const type = ref('')
const area = ref('')
const status = ref('')
const priority = ref('')
const sort = ref<'new' | 'top' | 'activity'>('new')
const q = ref('')
const page = ref(1)
const data = ref<IssueListResult | null>(null)
const counts = ref<{ new: number, mine: number } | null>(null)
const loading = ref(false)
const error = ref('')
let timer: ReturnType<typeof setTimeout> | null = null

async function load() {
  loading.value = true
  error.value = ''
  try {
    const p = new URLSearchParams({ view: view.value, sort: sort.value, page: String(page.value), per: '30' })
    if (type.value) p.set('type', type.value)
    if (area.value) p.set('area', area.value)
    if (status.value) p.set('status', status.value)
    if (priority.value) p.set('priority', priority.value)
    if (view.value === 'all' && !status.value) p.set('closed', '1')
    if (q.value.trim()) p.set('q', q.value.trim().slice(0, 80))
    data.value = await api<IssueListResult>(`/v1/admin/issues?${p}`)
    const d = await api<{ issues: { new: number, mine: number } | null }>('/v1/admin/dashboard').catch(() => null)
    counts.value = d?.issues ?? null
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch([view, type, area, status, priority, sort], () => {
  page.value = 1
  void load()
})
watch(page, () => void load())
watch(q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => {
    page.value = 1
    void load()
  }, 250)
})
const issues = computed<IssueView[]>(() => data.value?.issues ?? [])
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ it.adm.title }}</h1>
      <p class="adm-lead">{{ it.adm.lead }}</p>
      <p v-if="counts" class="mt-1 text-xs text-base-400 tabular-nums">{{ fill(it.adm.counts, { new: counts.new, mine: counts.mine }) }}</p>
    </header>

    <div class="mt-6 space-y-2">
      <div class="adm-toolbar">
        <div class="adm-seg">
          <button v-for="v in (['unassigned', 'mine', 'all', 'deleted'] as const)" :key="v" type="button" :aria-pressed="view === v" @click="view = v">{{ it.adm.views[v] }}</button>
        </div>
        <select v-model="sort" class="field adm-select" :aria-label="it.list.sort">
          <option v-for="s in (['new', 'top', 'activity'] as const)" :key="s" :value="s">{{ it.sorts[s] }}</option>
        </select>
      </div>
      <div class="adm-toolbar">
        <input v-model="q" class="field max-w-72" maxlength="80" :placeholder="it.list.search" :aria-label="it.list.search" />
        <select v-model="type" class="field adm-select" :aria-label="it.list.type">
          <option value="">{{ it.list.type }}: {{ it.list.all }}</option>
          <option v-for="t in ISSUE_TYPES" :key="t" :value="t">{{ it.types[t] }}</option>
        </select>
        <select v-model="area" class="field adm-select" :aria-label="it.list.area">
          <option value="">{{ it.list.area }}: {{ it.list.all }}</option>
          <option v-for="x in ISSUE_AREAS" :key="x" :value="x">{{ it.areas[x] }}</option>
        </select>
        <select v-model="status" class="field adm-select" :aria-label="it.list.status">
          <option value="">{{ it.list.status }}: {{ it.list.all }}</option>
          <option v-for="s in ISSUE_STATUSES" :key="s" :value="s">{{ it.statuses[s] }}</option>
        </select>
        <select v-model="priority" class="field adm-select" :aria-label="it.adm.priority">
          <option value="">{{ it.adm.anyPriority }}</option>
          <option v-for="p in ISSUE_PRIORITIES" :key="p" :value="p">{{ it.priorities[p] }}</option>
        </select>
      </div>
    </div>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>

    <div v-if="loading && !issues.length" class="mt-6 space-y-2">
      <div v-for="i in 6" :key="i" class="skeleton h-14 rounded-xl" />
    </div>
    <div v-else-if="!issues.length" class="adm-empty mt-6"><SiteIcon name="bug" class="size-6" />{{ it.adm.empty }}</div>
    <ul v-else class="mt-5 space-y-2">
      <li v-for="i in issues" :key="i.number">
        <NuxtLink :to="`/issues/${i.number}`" class="adm-row" :title="it.adm.open">
          <SiteIcon :name="i.type === 'bug' ? 'bug' : 'bolt'" class="size-5 shrink-0" :class="i.type === 'bug' ? 'text-redstone-300' : 'text-lamp-300'" />
          <span class="min-w-0 flex-1">
            <span class="block truncate font-semibold text-base-50">{{ i.title }} <span class="font-normal text-base-400">#{{ i.number }}</span></span>
            <span class="mt-1 flex flex-wrap items-center gap-1.5 text-xs">
              <IssueStatus :status="i.status" />
              <span class="chip py-0.5">{{ it.areas[i.area] }}</span>
              <span v-if="i.priority" class="tone" :class="i.priority === 'critical' ? 'tone-danger' : i.priority === 'high' ? 'tone-warn' : 'tone-muted'">{{ it.priorities[i.priority] }}</span>
              <span v-if="i.deleted" class="tone tone-danger">{{ it.adm.views.deleted }}</span>
              <span v-if="i.source === 'client'" class="chip py-0.5"><SiteIcon name="client" class="mr-1 size-3" />TRS Client</span>
              <span class="text-base-400">{{ i.author?.name ?? it.common.deletedUser }}</span>
            </span>
          </span>
          <span class="hidden w-32 shrink-0 items-center gap-1.5 truncate text-xs text-base-200 sm:flex">
            <template v-if="i.assignee"><PlayerHead :uuid="i.assignee.uuid" :name="i.assignee.name" :skin="i.assignee.skin ?? null" :fetch="false" :size="18" />{{ i.assignee.name }}</template>
            <span v-else class="text-base-400">{{ it.team.nobody }}</span>
          </span>
          <span class="w-14 shrink-0 text-right text-sm font-semibold text-base-50 tabular-nums" :title="fill(it.detail.sidebar.votes, { up: i.up, down: i.down })">{{ i.score }}</span>
          <span class="hidden w-24 shrink-0 text-right text-xs text-base-400 md:block" :title="when(i.activityAt)">{{ rel(i.activityAt) }}</span>
        </NuxtLink>
      </li>
    </ul>
    <nav v-if="data && data.pages > 1" class="mt-4 flex items-center justify-center gap-2 text-sm">
      <button type="button" class="btn-icon" :disabled="page <= 1" :aria-label="it.list.prev" @click="page--"><SiteIcon name="back" class="size-4" /></button>
      <span class="tabular-nums text-base-400">{{ fill(it.list.page, { page: data.page, pages: data.pages }) }}</span>
      <button type="button" class="btn-icon" :disabled="page >= data.pages" :aria-label="it.list.next" @click="page++"><SiteIcon name="arrow" class="size-4" /></button>
    </nav>
  </div>
</template>
