<script setup lang="ts">
import {
  reportFilters,
  type AdminReportSummary,
  type AuditEntry,
  type FilterWord,
  type ReportFilter,
} from '~/utils/moderation'
import { reportKinds, reportReasonIds, type ReportReason } from '~/utils/chat'

// Admin → Meldungen: Liste mit Filtern (aktiv/offen/in Prüfung/erledigt/alle,
// Art), Prüf-Dialog mit Kontext und Beweisbildern, Wortfilter und das
// Moderations-Protokoll. Gleiche Endpunkte wie die Website (API §20.5).
const emit = defineEmits<{ changed: [] }>()
const toasts = useToasts()

const filter = ref<ReportFilter>('active')
const kind = ref<'all' | (typeof reportKinds)[number]>('all')
const reports = ref<AdminReportSummary[] | null>(null)
const cursor = ref<string | null>(null)
const counts = ref({ open: 0, in_review: 0, resolved: 0 })
const openId = ref<string | null>(null)
const loadingMore = ref(false)

const words = ref<FilterWord[] | null>(null)
const wordForm = reactive({ word: '', mode: 'word' as FilterWord['mode'], action: 'mask' as FilterWord['action'] })
const wordError = ref<string | null>(null)
const audit = ref<AuditEntry[] | null>(null)
const busy = ref<string | null>(null)

const reasonLabel = (r: string) => (reportReasonIds as readonly string[]).includes(r) ? t(`social.report.reasons.${r as ReportReason}`) : r

async function loadReports(more = false) {
  if (more) loadingMore.value = true
  try {
    const page = await backend.social.adminReports({
      status: filter.value,
      kind: kind.value === 'all' ? undefined : kind.value,
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

async function loadSide() {
  const [w, a] = await Promise.allSettled([backend.social.adminWordFilter(), backend.social.adminAudit({ limit: 100 })])
  words.value = w.status === 'fulfilled' ? w.value.words : []
  audit.value = a.status === 'fulfilled' ? a.value.entries.filter((e) => /^(chat|report)\./.test(e.action)).slice(0, 40) : []
}

async function addWord() {
  const word = wordForm.word.trim()
  if (!word) return
  if ([...word].length < 2 || [...word].length > 48 || /\s/.test(word)) {
    wordError.value = t('admin.mod.wordRule')
    return
  }
  busy.value = 'word'
  wordError.value = null
  try {
    await backend.social.adminAddWord({ word, mode: wordForm.mode, action: wordForm.action })
    wordForm.word = ''
    words.value = (await backend.social.adminWordFilter()).words
  } catch (e) {
    wordError.value = errorMessage(e)
  } finally {
    busy.value = null
  }
}

async function removeWord(w: FilterWord) {
  busy.value = `word-${w.id}`
  try {
    await backend.social.adminDeleteWord(w.id)
    words.value = (words.value ?? []).filter((x) => x.id !== w.id)
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

function statusClass(r: AdminReportSummary): string {
  if (r.status === 'open') return 'bg-lamp-900 text-lamp-300'
  if (r.status === 'in_review') return 'bg-base-700 text-base-100'
  return r.outcome === 'actioned' ? 'bg-ok/15 text-ok' : 'bg-base-800 text-base-400'
}
function statusText(r: Pick<AdminReportSummary, 'status' | 'outcome'>): string {
  if (r.status === 'resolved' && r.outcome) return t(`admin.mod.outcome.${r.outcome}`)
  return t(`admin.mod.status.${r.status}`)
}

async function changed() {
  emit('changed')
  await Promise.all([loadReports(), loadSide()])
}

// Neue Meldungen erscheinen ohne Neuladen (Admins bekommen kein eigenes Ereignis dafür).
let timer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  void loadReports()
  void loadSide()
  timer = setInterval(() => {
    if (document.visibilityState === 'visible' && !openId.value) void loadReports()
  }, 15_000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
watch([filter, kind], () => {
  reports.value = null
  void loadReports()
})
</script>

<template>
  <section class="grid gap-6 xl:grid-cols-[minmax(0,1fr)_22rem]" :aria-label="t('admin.tabs.reports')" data-testid="admin-reports">
    <div class="min-w-0">
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <div class="flex gap-1 rounded-lg bg-base-850 p-1 text-xs">
          <button
            v-for="f in reportFilters"
            :key="f"
            class="seg rounded-md"
            :class="{ 'seg-on': filter === f }"
            :aria-pressed="filter === f"
            @click="filter = f"
          >
            {{ t(`admin.mod.filters.${f}`) }}
            <span v-if="f === 'open' && counts.open" class="ml-1 text-lamp-300 tabular-nums">{{ counts.open }}</span>
            <span v-if="f === 'in_review' && counts.in_review" class="ml-1 tabular-nums">{{ counts.in_review }}</span>
          </button>
        </div>
        <select v-model="kind" class="field w-auto py-1.5 text-xs" :aria-label="t('admin.mod.kindFilter')">
          <option value="all">{{ t('admin.mod.kinds.all') }}</option>
          <option v-for="k in reportKinds" :key="k" :value="k">{{ t(`social.report.titles.${k}`) }}</option>
        </select>
        <button class="btn btn-ghost ml-auto px-3 py-1.5 text-xs" @click="changed">{{ t('common.actions.refresh') }}</button>
      </div>

      <div v-if="!reports" class="space-y-2"><div v-for="i in 4" :key="i" class="skeleton h-20" /></div>
      <p v-else-if="!reports.length" class="card px-4 py-8 text-center text-sm text-base-400">{{ t('admin.mod.none') }}</p>
      <ul v-else class="space-y-2">
        <li v-for="r in reports" :key="r.id">
          <button class="card card-hover flex w-full flex-col gap-1.5 px-4 py-3 text-left" data-testid="admin-report-row" @click="openId = r.id">
            <span class="flex flex-wrap items-center gap-2">
              <span class="badge" :class="statusClass(r)">{{ statusText(r) }}</span>
              <span class="text-sm font-semibold text-base-50">{{ reasonLabel(r.reason) }}</span>
              <span class="text-xs text-base-400">· {{ t(`social.report.titles.${r.kind}`) }}</span>
              <span v-if="r.lowTrust" class="badge bg-lamp-900 text-lamp-300">{{ t('admin.mod.lowTrust') }}</span>
              <span class="ml-auto text-xs text-base-400">{{ dateTime(r.createdAt) }}</span>
            </span>
            <span v-if="r.preview" class="line-clamp-2 text-sm text-base-200">„{{ r.preview }}“</span>
            <span class="flex flex-wrap gap-x-3 text-xs text-base-400">
              <span>{{ t('admin.mod.target', { name: r.target?.name ?? t('admin.mod.unknown') }) }}</span>
              <span>{{ t('admin.mod.by', { name: r.reporter?.name ?? t('admin.mod.unknown') }) }}</span>
              <span v-if="r.images">{{ t('admin.mod.images', r.images) }}</span>
              <span v-if="r.targetOpenReports > 1" class="text-lamp-300">{{ t('admin.mod.targetOpen', { n: r.targetOpenReports }) }}</span>
              <span v-if="r.assignedTo">{{ t('admin.mod.assigned', { name: r.assignedTo.name }) }}</span>
            </span>
          </button>
        </li>
      </ul>
      <button v-if="cursor" class="btn btn-ghost mt-3" :disabled="loadingMore" @click="loadReports(true)">{{ t('admin.mod.more') }}</button>
    </div>

    <aside class="space-y-5">
      <!-- Wortfilter -->
      <div class="card p-4">
        <h3 class="section-title">{{ t('admin.mod.wordFilter') }}</h3>
        <p class="mt-1 text-xs text-base-400">{{ t('admin.mod.wordFilterHint') }}</p>
        <form class="mt-3 space-y-2" @submit.prevent="addWord">
          <input v-model="wordForm.word" class="field" maxlength="48" :placeholder="t('admin.mod.word')" :aria-label="t('admin.mod.word')" data-testid="word-input" />
          <div class="grid grid-cols-2 gap-2">
            <select v-model="wordForm.mode" class="field py-1.5 text-xs" :aria-label="t('admin.mod.mode.word')">
              <option value="word">{{ t('admin.mod.mode.word') }}</option>
              <option value="contains">{{ t('admin.mod.mode.contains') }}</option>
            </select>
            <select v-model="wordForm.action" class="field py-1.5 text-xs" :aria-label="t('admin.mod.action.mask')">
              <option value="mask">{{ t('admin.mod.action.mask') }}</option>
              <option value="block">{{ t('admin.mod.action.block') }}</option>
            </select>
          </div>
          <button class="btn btn-primary w-full" :disabled="busy === 'word' || !wordForm.word.trim()">{{ t('admin.mod.addWord') }}</button>
          <p v-if="wordError" role="alert" class="text-xs text-redstone-300">{{ wordError }}</p>
        </form>
        <ul v-if="words?.length" class="mt-3 max-h-60 space-y-1 overflow-y-auto">
          <li v-for="w in words" :key="w.id" class="flex items-center gap-2 rounded-md bg-base-850 px-2.5 py-1.5 text-xs">
            <span class="min-w-0 flex-1 truncate font-mono text-base-100">{{ w.word }}</span>
            <span class="text-base-400">{{ t(`admin.mod.mode.${w.mode}`) }} · {{ t(`admin.mod.action.${w.action}`) }}</span>
            <button class="btn-icon size-6" :aria-label="t('common.actions.remove')" :disabled="busy === `word-${w.id}`" @click="removeWord(w)">
              <SocialIcon name="close" class="size-3" />
            </button>
          </li>
        </ul>
        <p v-else-if="words" class="mt-3 text-xs text-base-400">{{ t('admin.mod.noWords') }}</p>
      </div>

      <!-- Protokoll -->
      <div class="card p-4">
        <h3 class="section-title">{{ t('admin.mod.auditTitle') }}</h3>
        <ul v-if="audit?.length" class="mt-2 max-h-96 space-y-1.5 overflow-y-auto text-xs text-base-400">
          <li v-for="a in audit" :key="a.id">
            {{ dateTime(a.at) }} · <span class="text-base-200">{{ a.actorName || a.actor }}</span> ·
            <span class="font-mono">{{ a.action }}</span>
            <span v-if="a.targetName || a.target"> → {{ a.targetName || a.target }}</span>
            <span v-if="a.detail"> · {{ a.detail }}</span>
          </li>
        </ul>
        <p v-else-if="audit" class="mt-2 text-xs text-base-400">{{ t('admin.mod.noAudit') }}</p>
      </div>
    </aside>

    <AdminReportDialog v-if="openId" :key="openId" :report-id="openId" @close="openId = null" @changed="changed" @open="(id) => (openId = id)" />
  </section>
</template>
