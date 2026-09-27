<script setup lang="ts">
// Schaltungs-Bibliothek im Team-Bereich (§25.4, Recht circuits.manage): Liste mit Status-/Kategorie-Filter und Suche,
// Einreichungen der Spieler, neue Schaltung oder Datei-Import (öffnet den Editor).
import { CIRCUIT_CATEGORIES } from '#shared/circuits'
import { circuitText } from '~/utils/circuit-i18n'
import type { AdminCircuitDetail, AdminSubmission, ImportResult } from '~/utils/circuits/types'
import { circuitErrorDetail, uploadCircuitFile } from '~/utils/circuits/types'

const { a, rel, when } = useAdminText()
const { c, lang, fill } = useCircuitText()
const { api, session } = useAdmin()
const route = useRoute()
const router = useRouter()

type Tab = 'circuits' | 'submissions'
const tab = ref<Tab>(route.query.tab === 'submissions' ? 'submissions' : 'circuits')
const status = ref<'all' | 'draft' | 'published' | 'hidden'>('all')
const subStatus = ref<'pending' | 'approved' | 'rejected' | 'all'>('pending')
const category = ref('')
const q = ref('')
const circuits = ref<AdminCircuitDetail[]>([])
const subs = ref<AdminSubmission[]>([])
const counts = ref<{ pendingSubmissions: number, published: number, total: number } | null>(null)
const loading = ref(false)
const error = ref('')
let timer: ReturnType<typeof setTimeout> | null = null

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (tab.value === 'circuits') {
      const p = new URLSearchParams({ status: status.value })
      if (category.value) p.set('category', category.value)
      if (q.value.trim()) p.set('q', q.value.trim().slice(0, 64))
      const r = await api<{ circuits: AdminCircuitDetail[], counts: typeof counts.value }>(`/v1/admin/circuits?${p}`)
      circuits.value = r.circuits
      counts.value = r.counts
    } else {
      const r = await api<{ submissions: AdminSubmission[], counts: typeof counts.value }>(`/v1/admin/circuit-submissions?status=${subStatus.value}`)
      subs.value = r.submissions
      counts.value = r.counts
    }
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch([tab, status, subStatus, category], () => {
  void router.replace({ query: { ...route.query, tab: tab.value === 'submissions' ? 'submissions' : undefined } })
  void load()
})
watch(q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void load(), 250)
})

const name = (x: AdminCircuitDetail) => x.names[lang.value] ?? x.names.en ?? Object.values(x.names)[0] ?? x.id
const tone = (s: string) => (s === 'published' || s === 'approved' ? 'tone-ok' : s === 'hidden' || s === 'rejected' ? 'tone-danger' : 'tone-warn')

// --- Import → Editor --------------------------------------------------------------------------------
const importInput = shallowRef<HTMLInputElement | null>(null)
const importing = ref(false)
const imported = useState<ImportResult | null>('circuit-import', () => null)
async function importFile(file: File | undefined) {
  if (!file) return
  importing.value = true
  error.value = ''
  try {
    imported.value = await uploadCircuitFile('/v1/admin/circuits/import', file, session.value?.csrf)
    void router.push('/admin/circuits/new?import=1')
  } catch (e) {
    const detail = circuitErrorDetail(e)
    error.value = fill(a.value.common.failed, { error: detail || apiMessage(e) })
  } finally {
    importing.value = false
    if (importInput.value) importInput.value.value = ''
  }
}
</script>

<template>
  <div class="adm-page">
    <header class="flex flex-wrap items-start justify-between gap-4">
      <div>
        <h1 class="adm-title">{{ c.adm.title }}</h1>
        <p class="adm-lead">{{ c.adm.lead }}</p>
        <p v-if="counts" class="mt-1 text-xs text-base-400 tabular-nums">{{ fill(c.adm.counts, { published: counts.published, total: counts.total, pending: counts.pendingSubmissions }) }}</p>
      </div>
      <div class="flex flex-wrap gap-2">
        <label class="btn btn-ghost cursor-pointer" :aria-busy="importing">
          <SiteIcon name="import" class="size-4" />{{ c.adm.import }}
          <input ref="importInput" type="file" class="sr-only" accept=".litematic,.schem,.nbt,.json" @change="importFile(($event.target as HTMLInputElement).files?.[0])" />
        </label>
        <NuxtLink to="/admin/circuits/new" class="btn btn-primary"><SiteIcon name="plus" class="size-4" />{{ c.adm.newCircuit }}</NuxtLink>
      </div>
    </header>

    <div class="mt-6 space-y-2">
      <div class="adm-toolbar">
        <div class="adm-seg">
          <button type="button" :aria-pressed="tab === 'circuits'" @click="tab = 'circuits'">{{ c.adm.tabCircuits }}</button>
          <button type="button" :aria-pressed="tab === 'submissions'" @click="tab = 'submissions'">
            {{ c.adm.tabSubmissions }}<span v-if="counts?.pendingSubmissions" class="adm-nav-count ml-1.5">{{ counts.pendingSubmissions }}</span>
          </button>
        </div>
        <div v-if="tab === 'circuits'" class="adm-seg">
          <button v-for="s in (['all', 'published', 'draft', 'hidden'] as const)" :key="s" type="button" :aria-pressed="status === s" @click="status = s">{{ c.adm.status[s] }}</button>
        </div>
        <div v-else class="adm-seg">
          <button v-for="s in (['pending', 'approved', 'rejected', 'all'] as const)" :key="s" type="button" :aria-pressed="subStatus === s" @click="subStatus = s">{{ c.adm.subStatus[s] }}</button>
        </div>
      </div>
      <div v-if="tab === 'circuits'" class="adm-toolbar">
        <input v-model="q" class="field max-w-72" maxlength="64" :placeholder="c.adm.search" :aria-label="c.adm.search" />
        <select v-model="category" class="field adm-select" :aria-label="c.adm.category">
          <option value="">{{ c.adm.allCategories }}</option>
          <option v-for="cat in CIRCUIT_CATEGORIES" :key="cat" :value="cat">{{ c.categories[cat] }}</option>
        </select>
      </div>
    </div>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>

    <div v-if="loading && !(tab === 'circuits' ? circuits.length : subs.length)" class="mt-6 grid gap-3 md:grid-cols-2 xl:grid-cols-3">
      <div v-for="i in 6" :key="i" class="skeleton h-28 rounded-xl" />
    </div>

    <template v-else-if="tab === 'circuits'">
      <div v-if="!circuits.length" class="adm-empty mt-6"><SiteIcon name="blocks" class="size-6" />{{ c.adm.empty }}</div>
      <ul v-else class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        <li v-for="x in circuits" :key="x.id">
          <NuxtLink :to="`/admin/circuits/${x.id}`" class="adm-row h-full">
            <span class="w-28 shrink-0 overflow-hidden rounded-lg bg-base-950">
              <CircuitIso :circuit="x.circuit" :height="96" />
            </span>
            <span class="min-w-0 flex-1">
              <span class="block truncate font-semibold text-base-50">{{ name(x) }}</span>
              <span class="adm-mono block truncate text-xs text-base-400">{{ x.id }} · {{ fill(c.adm.rev, { rev: x.rev }) }}</span>
              <span class="mt-2 flex flex-wrap items-center gap-1.5">
                <span class="tone" :class="tone(x.status)">{{ c.adm.status[x.status] }}</span>
                <span class="chip py-0.5">{{ c.categories[x.category] }}</span>
                <span class="chip py-0.5 tabular-nums">{{ fill(c.common.blocks, { n: x.blockCount }) }}</span>
                <span class="chip py-0.5">{{ c.adm.source[x.source] }}<template v-if="x.source === 'seed' && x.edited"> · {{ c.adm.edited }}</template></span>
              </span>
              <span class="mt-1 block truncate text-xs text-base-400">
                <template v-if="x.author">{{ fill(c.common.by, { name: x.author.name }) }} · </template><span :title="when(x.updatedAt)">{{ rel(x.updatedAt) }}</span>
              </span>
            </span>
          </NuxtLink>
        </li>
      </ul>
    </template>

    <template v-else>
      <div v-if="!subs.length" class="adm-empty mt-6"><SiteIcon name="inbox" class="size-6" />{{ c.adm.emptySubs }}</div>
      <ul v-else class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        <li v-for="s in subs" :key="s.id">
          <NuxtLink :to="`/admin/circuits/submissions/${s.id}`" class="adm-row h-full">
            <span class="w-28 shrink-0 overflow-hidden rounded-lg bg-base-950">
              <CircuitIso :circuit="s.circuit" :height="96" />
            </span>
            <span class="min-w-0 flex-1">
              <span class="block truncate font-semibold text-base-50">{{ s.name }}</span>
              <span class="block truncate text-xs text-base-400">{{ fill(c.adm.submittedBy, { name: s.submitter.name, date: rel(s.createdAt) }) }}</span>
              <span class="mt-2 flex flex-wrap items-center gap-1.5">
                <span class="tone" :class="tone(s.status)">{{ c.adm.subStatus[s.status === 'pending' ? 'pending' : s.status] }}</span>
                <span class="chip py-0.5">{{ c.categories[s.category] }}</span>
                <span class="chip py-0.5 tabular-nums">{{ fill(c.common.blocks, { n: s.blockCount }) }}</span>
                <span class="chip py-0.5 uppercase">{{ s.format }}</span>
              </span>
              <span class="mt-1 line-clamp-1 block text-xs text-base-400">{{ circuitText(s.circuit.texts, s.lang, 'desc') }}</span>
            </span>
          </NuxtLink>
        </li>
      </ul>
    </template>
  </div>
</template>
