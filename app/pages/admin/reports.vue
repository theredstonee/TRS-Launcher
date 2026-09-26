<script setup lang="ts">
import { reportKinds, reportReasonIds, type ReportReason } from '~/utils/chat'
import { reportFilters, type AdminReportSummary, type ReportFilter } from '~/utils/moderation'

// Meldungen: Filter (Status, Art, Grund, Zuständig, Priorität, Sortierung),
// Mehrfachauswahl mit „abweisen“/„erledigt“ (höchstens 50, eine Transaktion),
// Tasten j/k, Enter, a (erledigt), r (abweisen), x (auswählen). Der Prüf-Dialog
// zeigt Kontext, Beweise, Notizen und Entscheidungen inkl. Strafe.
const toasts = useToasts()
const team = useTeam()
const route = useRoute()
const router = useRouter()

const filter = ref<ReportFilter>('active')
const kind = ref<'all' | (typeof reportKinds)[number]>('all')
const reason = ref<'all' | ReportReason>('all')
const assigned = ref<'all' | 'me' | 'none'>('all')
const highOnly = ref(false)
const sort = ref<'default' | 'oldest' | 'newest'>('default')
const reports = ref<AdminReportSummary[] | null>(null)
const cursor = ref<string | null>(null)
const counts = ref({ open: 0, in_review: 0, resolved: 0, highPriority: 0 })
const loadingMore = ref(false)
const openId = ref<string | null>(typeof route.query.open === 'string' ? route.query.open : null)
const { selected, toggle, setAll, clear, limit } = useSelection()
const bulk = ref<{ action: 'dismiss' | 'resolve'; ids: string[] } | null>(null)
const bulkBusy = ref(false)

const reasonLabel = (r: string) => ((reportReasonIds as readonly string[]).includes(r) ? t(`social.report.reasons.${r as ReportReason}`) : r)
const items = computed(() => reports.value ?? [])
const selectable = computed(() => items.value.filter((r) => r.status !== 'resolved'))

async function load(more = false) {
  if (more) loadingMore.value = true
  try {
    const page = await backend.social.adminReports({
      status: filter.value,
      kind: kind.value === 'all' ? undefined : kind.value,
      reason: reason.value === 'all' ? undefined : reason.value,
      assigned: assigned.value === 'all' ? undefined : assigned.value,
      highPriority: highOnly.value || undefined,
      sort: sort.value === 'default' ? undefined : sort.value,
      cursor: more ? (cursor.value ?? undefined) : undefined,
      limit: 30,
    })
    reports.value = more ? [...(reports.value ?? []), ...page.reports] : page.reports
    cursor.value = page.nextCursor
    counts.value = page.counts
  } catch (e) {
    if (!more) reports.value = []
    toasts.error(e)
  } finally {
    loadingMore.value = false
  }
}

function open(r: AdminReportSummary) {
  openId.value = r.id
}
watch(openId, (id) => void router.replace({ query: { ...route.query, open: id ?? undefined } }))

function ask(action: 'dismiss' | 'resolve', ids = [...selected.value]) {
  if (ids.length) bulk.value = { action, ids }
}

async function runBulk() {
  const b = bulk.value
  if (!b) return
  bulkBusy.value = true
  try {
    const r = await backend.team.bulk('reports', { ids: b.ids, action: b.action })
    toasts.ok(t('team.bulk.done', { updated: r.updated.length, skipped: r.skipped.length }))
    bulk.value = null
    clear()
    await changed()
  } catch (e) {
    toasts.error(e)
  } finally {
    bulkBusy.value = false
  }
}

async function changed() {
  await Promise.all([load(), team.refreshCounts()])
}

const { active } = useListKeys(items, {
  open,
  onA: (r) => r.status !== 'resolved' && ask('resolve', [r.id]),
  onR: (r) => r.status !== 'resolved' && ask('dismiss', [r.id]),
  toggle: (r) => r.status !== 'resolved' && toggle(r.id),
})

function statusClass(r: AdminReportSummary): string {
  if (r.status === 'open') return 'bg-lamp-900 text-lamp-300'
  if (r.status === 'in_review') return 'bg-base-700 text-base-100'
  return r.outcome === 'actioned' ? 'bg-ok/15 text-ok' : 'bg-base-800 text-base-400'
}
function statusText(r: Pick<AdminReportSummary, 'status' | 'outcome'>): string {
  if (r.status === 'resolved' && r.outcome) return t(`admin.mod.outcome.${r.outcome}`)
  return t(`admin.mod.status.${r.status}`)
}

// Neue Meldungen erscheinen ohne Neuladen (das Team bekommt dafür kein Ereignis).
let timer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  void load()
  timer = setInterval(() => {
    if (document.visibilityState === 'visible' && !openId.value && !selected.value.size) void load()
  }, 15_000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
watch([filter, kind, reason, assigned, highOnly, sort], () => {
  reports.value = null
  clear()
  void load()
})
</script>

<template>
  <section :aria-label="t('team.nav.reports')" data-testid="admin-reports">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <div class="flex gap-1 rounded-lg bg-base-850 p-1 text-xs">
        <button v-for="f in reportFilters" :key="f" class="seg rounded-md" :class="{ 'seg-on': filter === f }" :aria-pressed="filter === f" @click="filter = f">
          {{ t(`admin.mod.filters.${f}`) }}
          <span v-if="f === 'open' && counts.open" class="ml-1 text-lamp-300 tabular-nums">{{ counts.open }}</span>
          <span v-if="f === 'in_review' && counts.in_review" class="ml-1 tabular-nums">{{ counts.in_review }}</span>
        </button>
      </div>
      <button
        class="chip gap-1.5"
        :class="highOnly ? 'bg-redstone-900 text-redstone-300 ring-1 ring-redstone-600' : 'hover:bg-base-700'"
        :aria-pressed="highOnly"
        data-testid="filter-priority"
        @click="highOnly = !highOnly"
      >
        {{ t('team.reports.highPriority') }}
        <span v-if="counts.highPriority" class="tabular-nums">{{ counts.highPriority }}</span>
      </button>
      <button class="btn btn-ghost ml-auto px-3 py-1.5 text-xs" @click="changed">{{ t('common.actions.refresh') }}</button>
    </div>
    <div class="mb-4 flex flex-wrap gap-2">
      <select v-model="kind" class="field w-auto py-1.5 text-xs" :aria-label="t('admin.mod.kindFilter')">
        <option value="all">{{ t('admin.mod.kinds.all') }}</option>
        <option v-for="k in reportKinds" :key="k" :value="k">{{ t(`social.report.titles.${k}`) }}</option>
      </select>
      <select v-model="reason" class="field w-auto py-1.5 text-xs" :aria-label="t('team.reports.reason')">
        <option value="all">{{ t('team.reports.allReasons') }}</option>
        <option v-for="r in reportReasonIds" :key="r" :value="r">{{ reasonLabel(r) }}</option>
      </select>
      <select v-model="assigned" class="field w-auto py-1.5 text-xs" :aria-label="t('team.reports.assigned')">
        <option value="all">{{ t('team.reports.assignedAll') }}</option>
        <option value="me">{{ t('team.reports.assignedMe') }}</option>
        <option value="none">{{ t('team.reports.assignedNone') }}</option>
      </select>
      <select v-model="sort" class="field w-auto py-1.5 text-xs" :aria-label="t('team.common.sort')">
        <option value="default">{{ t('team.common.sortDefault') }}</option>
        <option value="oldest">{{ t('team.common.oldest') }}</option>
        <option value="newest">{{ t('team.common.newest') }}</option>
      </select>
      <label v-if="selectable.length" class="ml-auto flex items-center gap-2 text-xs text-base-400">
        <input
          type="checkbox"
          class="accent-redstone-500"
          :checked="selected.size > 0 && selected.size >= Math.min(limit, selectable.length)"
          @change="setAll(selectable.map((r) => r.id), ($event.target as HTMLInputElement).checked)"
        />
        {{ t('team.common.selectAll') }}
      </label>
    </div>

    <div v-if="!reports" class="space-y-2"><div v-for="i in 4" :key="i" class="skeleton h-20" /></div>
    <RedstoneEmpty v-else-if="!reports.length" :title="t('admin.mod.none')" compact :seed="0x3a" />
    <ul v-else class="space-y-2">
      <li v-for="(r, i) in reports" :key="r.id" :data-row="i">
        <div class="adm-row card flex items-stretch" :class="{ 'adm-row-active': active === i, 'adm-row-selected': selected.has(r.id), 'border-redstone-600/50': r.priority === 'high' && r.status !== 'resolved' }">
          <label class="flex items-center px-3" :class="{ invisible: r.status === 'resolved' }">
            <input type="checkbox" class="accent-redstone-500" :checked="selected.has(r.id)" :aria-label="r.id" @change="toggle(r.id)" />
          </label>
          <button class="flex min-w-0 flex-1 flex-col gap-1.5 py-3 pr-4 text-left" data-testid="admin-report-row" @click="open(r)">
            <span class="flex flex-wrap items-center gap-2">
              <span class="badge" :class="statusClass(r)">{{ statusText(r) }}</span>
              <span v-if="r.priority === 'high' && r.status !== 'resolved'" class="badge bg-redstone-600/30 text-redstone-300">{{ t('team.reports.high') }}</span>
              <span class="text-sm font-semibold text-base-50">{{ reasonLabel(r.reason) }}</span>
              <span class="text-xs text-base-400">· {{ t(`social.report.titles.${r.kind}`) }}</span>
              <span v-if="r.lowTrust" class="badge bg-lamp-900 text-lamp-300">{{ t('admin.mod.lowTrust') }}</span>
              <span class="ml-auto text-xs text-base-400" :title="formatDate(r.createdAt)">{{ formatRelative(r.createdAt) }}</span>
            </span>
            <span v-if="r.preview" class="line-clamp-2 text-sm text-base-200">„{{ r.preview }}“</span>
            <span class="flex flex-wrap gap-x-3 text-xs text-base-400">
              <span>{{ t('admin.mod.target', { name: r.target?.name || t('admin.mod.unknown') }) }}</span>
              <span>{{ t('admin.mod.by', { name: r.reporter?.name || t('admin.mod.unknown') }) }}</span>
              <span v-if="r.images">{{ t('admin.mod.images', r.images) }}</span>
              <span v-if="r.targetOpenReports > 1" class="text-lamp-300">{{ t('admin.mod.targetOpen', { n: r.targetOpenReports }) }}</span>
              <span v-if="r.assignedTo">{{ t('admin.mod.assigned', { name: r.assignedTo.name }) }}</span>
            </span>
          </button>
        </div>
      </li>
    </ul>
    <button v-if="cursor" class="btn btn-ghost mt-3" :disabled="loadingMore" @click="load(true)">{{ t('admin.mod.more') }}</button>

    <AdminBulkBar :count="selected.size" :limit="limit" @clear="clear">
      <button class="btn btn-ghost" data-testid="bulk-dismiss" @click="ask('dismiss')">{{ t('team.reports.dismissSelected') }}</button>
      <button class="btn btn-primary" data-testid="bulk-resolve" @click="ask('resolve')"><SocialIcon name="check" class="size-4" />{{ t('team.reports.resolveSelected') }}</button>
    </AdminBulkBar>

    <AdminConfirm
      v-if="bulk"
      :title="bulk.action === 'resolve' ? t('team.reports.resolveSelected') : t('team.reports.dismissSelected')"
      :text="t(`team.reports.confirm.${bulk.action}`, { n: bulk.ids.length })"
      :confirm-label="bulk.action === 'resolve' ? t('admin.mod.resolve') : t('admin.mod.dismiss')"
      :busy="bulkBusy"
      @confirm="runBulk"
      @close="bulk = null"
    />
    <AdminReportDialog v-if="openId" :key="openId" :report-id="openId" @close="openId = null" @changed="changed" @open="(id) => (openId = id)" />
  </section>
</template>
