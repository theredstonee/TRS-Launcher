<script setup lang="ts">
// Bewerbungen (§24.3): Liste mit Status-, Stellen- und Namensfilter, Stimmen-Übersicht; Klick → Detail.
const { a, fill, rel, when } = useAdminText()
const { t, lang } = useTeamText()
const { api } = useAdmin()
const route = useRoute()
const router = useRouter()

const STATUSES = ['open', 'new', 'review', 'interview', 'accepted', 'rejected', 'withdrawn', 'all'] as const
const f = reactive({
  status: (STATUSES as readonly string[]).includes(String(route.query.status)) ? String(route.query.status) : 'open',
  job: typeof route.query.job === 'string' ? route.query.job : '',
  q: '',
})
const items = ref<AdminApplicationItem[]>([])
const counts = ref<Record<string, number>>({})
const jobs = ref<AdminJob[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const error = ref('')

async function load(more = false) {
  loading.value = true
  error.value = ''
  try {
    const q = new URLSearchParams({ status: f.status, limit: '50' })
    if (f.job) q.set('job', f.job)
    if (/^[A-Za-z0-9_]{1,16}$/.test(f.q.trim())) q.set('q', f.q.trim())
    if (more && cursor.value) q.set('cursor', cursor.value)
    const r = await api<{ applications: AdminApplicationItem[], nextCursor: string | null, counts: Record<string, number> }>(`/v1/admin/applications?${q}`)
    items.value = more ? [...items.value, ...r.applications] : r.applications
    cursor.value = r.nextCursor
    counts.value = r.counts
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  void load()
  try {
    jobs.value = (await api<{ jobs: AdminJob[] }>('/v1/admin/jobs')).jobs
  } catch {
    // Filter ist Beiwerk.
  }
})
let timer: ReturnType<typeof setTimeout> | null = null
watch(() => [f.status, f.job], () => {
  void router.replace({ query: { ...(f.status !== 'open' ? { status: f.status } : {}), ...(f.job ? { job: f.job } : {}) } })
  void load()
})
watch(() => f.q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void load(), 250)
})
const openCount = computed(() => (counts.value.new ?? 0) + (counts.value.review ?? 0) + (counts.value.interview ?? 0))
const label = (s: string) => (s === 'open' ? t.value.adm.apps.statusOpen : s === 'all' ? t.value.adm.apps.statusAll : t.value.myApps.status[s] ?? s)
const count = (s: string) => (s === 'open' ? openCount.value : s === 'all' ? Object.values(counts.value).reduce((x, y) => x + y, 0) : counts.value[s] ?? 0)

const { active } = useListKeys(items, { open: (x) => void router.push(`/admin/applications/${x.id}`) })
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ t.adm.apps.title }}</h1>
      <p class="adm-lead">{{ t.adm.apps.lead }}</p>
    </header>

    <div class="adm-toolbar mt-5">
      <div class="adm-seg flex-wrap">
        <button v-for="s in STATUSES" :key="s" type="button" :aria-pressed="f.status === s" @click="f.status = s">
          {{ label(s) }} <span class="ml-1 text-base-400 tabular-nums">{{ count(s) }}</span>
        </button>
      </div>
      <select v-model="f.job" class="field adm-select w-auto" :aria-label="t.adm.apps.position">
        <option value="">{{ t.adm.apps.allJobs }}</option>
        <option v-for="j in jobs" :key="j.id" :value="j.id">{{ inLang(j.texts, lang)?.title ?? j.id }}</option>
      </select>
      <input v-model="f.q" class="field w-44" maxlength="16" :placeholder="t.adm.apps.search" :aria-label="t.adm.apps.search" />
    </div>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>

    <div v-if="loading && !items.length" class="mt-4 space-y-2"><div v-for="i in 4" :key="i" class="skeleton h-16 rounded-lg" /></div>
    <div v-else-if="!items.length" class="adm-empty mt-6"><SiteIcon name="inbox" class="size-6" />{{ t.adm.apps.empty }}</div>
    <ul v-else class="mt-4 space-y-2">
      <li v-for="(x, i) in items" :key="x.id">
        <NuxtLink :to="`/admin/applications/${x.id}`" class="adm-row items-center" :data-row="i" :data-active="active === i">
          <PlayerHead :uuid="x.applicant.uuid" :name="x.applicant.name" :size="36" :fetch="false" />
          <div class="min-w-0 flex-1">
            <p class="flex flex-wrap items-center gap-2">
              <span class="font-semibold text-base-50">{{ x.applicant.name }}</span>
              <span class="tone" :class="applicationTone(x.status)">{{ t.myApps.status[x.status] }}</span>
              <span class="chip py-0.5 text-xs">{{ inLang(x.job.title, lang) ?? x.job.id }}</span>
            </p>
            <p class="mt-0.5 flex flex-wrap gap-x-3 text-xs text-base-400">
              <span>{{ t.adm.apps.age }}: {{ t.job.ages[x.ageGroup] }}</span>
              <span :title="when(x.createdAt)">{{ t.adm.apps.submitted }} {{ rel(x.createdAt) }}</span>
              <span v-if="x.notes"><SiteIcon name="note" class="mr-0.5 inline size-3.5 align-[-2px]" />{{ x.notes }}</span>
            </p>
          </div>
          <div class="flex items-center gap-3 text-sm tabular-nums" :aria-label="t.adm.apps.votes">
            <span class="flex items-center gap-1" :class="x.votes.mine === 1 ? 'text-ok' : 'text-base-300'"><SiteIcon name="thumbUp" class="size-4" />{{ x.votes.up }}</span>
            <span class="flex items-center gap-1" :class="x.votes.mine === -1 ? 'text-redstone-300' : 'text-base-300'"><SiteIcon name="thumbDown" class="size-4" />{{ x.votes.down }}</span>
          </div>
        </NuxtLink>
      </li>
    </ul>
    <button v-if="cursor" type="button" class="btn btn-ghost mt-4" :disabled="loading" @click="load(true)">{{ a.common.loadMore }}</button>
  </div>
</template>
