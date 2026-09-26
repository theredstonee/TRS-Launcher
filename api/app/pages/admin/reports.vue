<script setup lang="ts">
// Meldungen: Filter (Status, Art, Grund, Bearbeiter, Dringlichkeit, Zeitraum, Sortierung), Cursor-Seiten,
// Mehrfachauswahl mit Sammelaktion (abweisen/erledigen), j/k/Enter/x/a/r. Die Prüfung öffnet sich als
// Unterseite /admin/reports/<id> (Deep-Link) über der Liste.
const { a, m, fill, when, rel } = useAdminText()
const { api } = useAdmin()
const router = useRouter()
const route = useRoute()
const mod = computed(() => m.value.admin.mod)
const rev = useState('admin-reports-rev', () => 0)

const STATUSES: ReportFilter[] = ['active', 'open', 'in_review', 'resolved', 'all']
const KINDS: ReportKind[] = ['message', 'image', 'player', 'group']
const REASONS: ReportReason[] = ['insult_hate', 'spam', 'inappropriate', 'scam_phishing', 'harassment', 'other']

const f = reactive({
  status: 'active' as ReportFilter,
  kind: '' as '' | ReportKind,
  reason: '' as '' | ReportReason,
  assigned: '' as '' | 'me' | 'none',
  priority: false,
  from: '',
  to: '',
  sort: '' as '' | 'oldest' | 'newest',
})
const items = ref<ReportSummaryV2[]>([])
const cursor = ref<string | null>(null)
const counts = ref<{ open: number, in_review: number, resolved: number, highPriority: number } | null>(null)
const loading = ref(false)
const error = ref('')
const selected = ref(new Set<string>())

function query(more: boolean): string {
  const q = new URLSearchParams({ status: f.status, limit: '30' })
  if (f.kind) q.set('kind', f.kind)
  if (f.reason) q.set('reason', f.reason)
  if (f.assigned) q.set('assigned', f.assigned)
  if (f.priority) q.set('priority', 'high')
  if (f.from) q.set('from', new Date(`${f.from}T00:00:00`).toISOString())
  if (f.to) q.set('to', new Date(`${f.to}T23:59:59`).toISOString())
  if (f.sort) q.set('sort', f.sort)
  if (more && cursor.value) q.set('cursor', cursor.value)
  return q.toString()
}

async function load(more = false) {
  loading.value = true
  error.value = ''
  try {
    const r = await api<{ reports: ReportSummaryV2[], nextCursor: string | null, counts: typeof counts.value }>(`/v1/admin/reports?${query(more)}`)
    items.value = more ? [...items.value, ...r.reports] : r.reports
    cursor.value = r.nextCursor
    counts.value = r.counts
    if (!more) selected.value = new Set()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
watch(f, () => void load())
watch(rev, () => void load())

function reset() {
  Object.assign(f, { status: 'active', kind: '', reason: '', assigned: '', priority: false, from: '', to: '', sort: '' })
}

function toggle(id: string) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}
const selectable = computed(() => items.value.filter((r) => r.status !== 'resolved'))
const allSelected = computed(() => selectable.value.length > 0 && selectable.value.every((r) => selected.value.has(r.id)))
function toggleAll() {
  selected.value = allSelected.value ? new Set() : new Set(selectable.value.slice(0, 50).map((r) => r.id))
}

// --- Sammelaktion ---------------------------------------------------------------------------------
const bulk = ref<null | { action: 'dismiss' | 'resolve', ids: string[] }>(null)
const bulkBusy = ref(false)
const bulkError = ref('')
const notice = ref('')
function askBulk(action: 'dismiss' | 'resolve', ids = [...selected.value]) {
  if (!ids.length) return
  bulkError.value = ''
  bulk.value = { action, ids: ids.slice(0, 50) }
}
async function runBulk() {
  if (!bulk.value) return
  bulkBusy.value = true
  bulkError.value = ''
  try {
    const r = await api<{ updated: string[], skipped: string[] }>('/v1/admin/reports/bulk', { method: 'POST', body: { ids: bulk.value.ids, action: bulk.value.action } })
    notice.value = fill(a.value.reports.bulkDone, { n: r.updated.length, skipped: r.skipped.length })
    bulk.value = null
    await load()
  } catch (e) {
    bulkError.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    bulkBusy.value = false
  }
}

function open(r: ReportSummaryV2) {
  void router.push({ path: `/admin/reports/${r.id}` })
}
const { active } = useListKeys(items, {
  open,
  toggle: (r) => r.status !== 'resolved' && toggle(r.id),
  onA: (r) => r.status !== 'resolved' && askBulk('resolve', [r.id]),
  onR: (r) => r.status !== 'resolved' && askBulk('dismiss', [r.id]),
})
const openId = computed(() => (typeof route.params.id === 'string' ? route.params.id : null))

function reportTone(r: ReportSummaryV2): string {
  if (r.status === 'open') return 'tone-warn'
  if (r.status === 'in_review') return 'tone-info'
  return r.outcome === 'actioned' ? 'tone-ok' : 'tone-muted'
}
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ a.reports.title }}</h1>
      <p class="adm-lead">{{ a.reports.lead }}</p>
    </header>

    <!-- Filter -->
    <div class="mt-6 space-y-2">
      <div class="adm-toolbar">
        <div class="adm-seg" role="group" :aria-label="a.reports.status">
          <button v-for="s in STATUSES" :key="s" type="button" :aria-pressed="f.status === s" @click="f.status = s">
            {{ mod.filters[s] }}
            <span v-if="s === 'open' && counts?.open" class="ml-1 tabular-nums text-lamp-300">{{ counts.open }}</span>
            <span v-if="s === 'in_review' && counts?.in_review" class="ml-1 tabular-nums">{{ counts.in_review }}</span>
          </button>
        </div>
        <label class="flex items-center gap-2 rounded-lg border border-base-800 bg-base-900 px-3 py-1.5 text-sm text-base-200">
          <input v-model="f.priority" type="checkbox" class="adm-check mt-0" />{{ a.reports.priority }}
          <span v-if="counts?.highPriority" class="tone tone-danger">{{ counts.highPriority }}</span>
        </label>
      </div>
      <div class="adm-toolbar">
        <select v-model="f.kind" class="field adm-select" :aria-label="a.reports.kind">
          <option value="">{{ mod.kinds.all }}</option>
          <option v-for="k in KINDS" :key="k" :value="k">{{ mod.kinds[k] }}</option>
        </select>
        <select v-model="f.reason" class="field adm-select" :aria-label="a.reports.reason">
          <option value="">{{ a.reports.reason }}: {{ a.common.all }}</option>
          <option v-for="r in REASONS" :key="r" :value="r">{{ mod.reasons[r] }}</option>
        </select>
        <select v-model="f.assigned" class="field adm-select" :aria-label="a.reports.assigned">
          <option value="">{{ a.reports.assigned }}: {{ a.common.all }}</option>
          <option value="me">{{ a.reports.assignedMe }}</option>
          <option value="none">{{ a.reports.assignedNone }}</option>
        </select>
        <input v-model="f.from" type="date" class="field adm-select" :aria-label="a.common.from" />
        <input v-model="f.to" type="date" class="field adm-select" :aria-label="a.common.to" />
        <select v-model="f.sort" class="field adm-select" :aria-label="a.common.newest">
          <option value="">↕</option>
          <option value="oldest">{{ a.common.oldest }}</option>
          <option value="newest">{{ a.common.newest }}</option>
        </select>
        <button type="button" class="btn btn-ghost text-xs" @click="reset">{{ a.common.reset }}</button>
      </div>
    </div>

    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <p v-if="notice" class="mt-4 text-sm text-ok" role="status">{{ notice }}</p>

    <div v-if="loading && !items.length" class="mt-6 space-y-3">
      <div v-for="i in 5" :key="i" class="skeleton h-20 rounded-xl" />
    </div>
    <div v-else-if="!items.length" class="adm-empty mt-6">
      <SiteIcon name="flag" class="size-6" />{{ a.reports.empty }}
    </div>
    <template v-else>
      <label class="mt-5 flex items-center gap-2 text-xs text-base-400">
        <input type="checkbox" class="adm-check mt-0" :checked="allSelected" :disabled="!selectable.length" @change="toggleAll" />{{ a.common.selectAll }}
      </label>
      <ul class="mt-2 space-y-2">
        <li v-for="(r, i) in items" :key="r.id">
          <div class="adm-row" :data-row="i" :data-active="active === i || openId === r.id" :data-selected="selected.has(r.id)">
            <input
              type="checkbox"
              class="adm-check"
              :checked="selected.has(r.id)"
              :disabled="r.status === 'resolved'"
              :aria-label="r.id"
              @change="toggle(r.id)"
            />
            <button type="button" class="flex min-w-0 flex-1 flex-col gap-1.5 text-left" @click="active = i; open(r)">
              <span class="flex flex-wrap items-center gap-2">
                <span class="tone" :class="reportTone(r)">{{ r.status === 'resolved' && r.outcome ? mod.outcome[r.outcome] : mod.status[r.status] }}</span>
                <span v-if="r.priority === 'high' && r.status !== 'resolved'" class="tone tone-danger"><SiteIcon name="warn" class="size-3" />{{ a.reports.high }}</span>
                <span class="chip py-0.5">{{ mod.kinds[r.kind] }}</span>
                <span class="font-semibold text-base-50">{{ mod.reasons[r.reason] }}</span>
                <span v-if="r.lowTrust" class="tone tone-muted">{{ mod.lowTrust }}</span>
                <span class="ml-auto text-xs text-base-400" :title="when(r.createdAt)">{{ rel(r.createdAt) }}</span>
              </span>
              <span v-if="r.preview" class="line-clamp-2 text-sm text-base-100">„{{ r.preview }}“</span>
              <span class="flex flex-wrap gap-x-3 gap-y-1 text-xs text-base-400">
                <span>{{ fill(mod.against, { name: r.target?.name || mod.unknown }) }}</span>
                <span>{{ fill(mod.by, { name: r.reporter?.name || mod.unknown }) }}</span>
                <span v-if="r.images">{{ fill(mod.images, { n: r.images }) }}</span>
                <span v-if="r.targetOpenReports > 1" class="text-lamp-300">{{ fill(mod.targetOpen, { n: r.targetOpenReports }) }}</span>
                <span v-if="r.assignedTo">→ {{ r.assignedTo.name }}</span>
              </span>
            </button>
          </div>
        </li>
      </ul>
      <button v-if="cursor" type="button" class="btn btn-ghost mt-4" :disabled="loading" @click="load(true)">{{ a.common.loadMore }}</button>
    </template>

    <div v-if="selected.size" class="adm-bulkbar">
      <span class="text-sm text-base-50">{{ fill(a.common.selected, { n: selected.size }) }}</span>
      <button type="button" class="btn btn-ghost text-xs" @click="selected = new Set()">{{ a.common.clearSelection }}</button>
      <span class="flex-1" />
      <button type="button" class="btn btn-ghost" @click="askBulk('dismiss')">{{ a.reports.bulkDismiss }}</button>
      <button type="button" class="btn btn-primary" @click="askBulk('resolve')"><SiteIcon name="check" class="size-4" />{{ a.reports.bulkResolve }}</button>
    </div>

    <AdminConfirm
      v-if="bulk"
      :title="bulk.action === 'dismiss' ? a.reports.bulkDismiss : a.reports.bulkResolve"
      :text="fill(a.reports.confirmBulk, { n: bulk.ids.length, action: bulk.action === 'dismiss' ? mod.dismiss : mod.resolve })"
      :busy="bulkBusy"
      :error="bulkError"
      @cancel="bulk = null"
      @confirm="runBulk"
    />

    <NuxtPage />
  </div>
</template>
