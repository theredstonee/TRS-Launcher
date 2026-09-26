<script setup lang="ts">
// Umhänge & Kosmetik prüfen: Status, Name, Sortierung, Cursor; Umhänge im großen Prüf-Dialog (3D + Frames),
// Kosmetik direkt; Mehrfachauswahl → Sammel-Freigabe/-Ablehnung (eine Transaktion, max. 50).
const { a, fill, rel, when, lang } = useAdminText()
const { api, can } = useAdmin()
const route = useRoute()

interface AdminCosmetic {
  id: string
  name: string
  slot: string
  status: 'approved' | 'pending' | 'rejected'
  template: string | null
  texture: { url: string, width: number, height: number, frames: number } | null
  owner: { uuid: string, name: string } | null
  createdAt: string
  rejectReason: string | null
  reports: { count: number, reasons: Record<string, number> }
}
type Tab = 'capes' | 'cosmetics'
type Status = 'pending' | 'reported' | 'approved' | 'rejected'

const tab = ref<Tab>(route.query.kind === 'cosmetics' ? 'cosmetics' : 'capes')
const status = ref<Status>('pending')
const q = ref(typeof route.query.q === 'string' ? route.query.q.slice(0, 32) : '')
const sort = ref<'' | 'oldest' | 'newest'>('')
const capes = ref<AdminCape[]>([])
const cosmetics = ref<AdminCosmetic[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const error = ref('')
const selected = ref(new Set<string>())
let timer: ReturnType<typeof setTimeout> | null = null

const items = computed<{ id: string }[]>(() => (tab.value === 'capes' ? capes.value : cosmetics.value))

async function load(more = false) {
  loading.value = true
  error.value = ''
  try {
    const p = new URLSearchParams({ status: status.value, limit: '60' })
    if (q.value.trim()) p.set('q', q.value.trim())
    if (sort.value) p.set('sort', sort.value)
    if (more && cursor.value) p.set('cursor', cursor.value)
    if (tab.value === 'capes') {
      const r = await api<{ capes: AdminCape[], nextCursor: string | null }>(`/v1/admin/capes?${p}`)
      capes.value = more ? [...capes.value, ...r.capes] : r.capes
      cursor.value = r.nextCursor
    } else {
      const r = await api<{ cosmetics: AdminCosmetic[], nextCursor: string | null }>(`/v1/admin/cosmetics?${p}`)
      cosmetics.value = more ? [...cosmetics.value, ...r.cosmetics] : r.cosmetics
      cursor.value = r.nextCursor
    }
    if (!more) selected.value = new Set()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
watch([tab, status, sort], () => {
  reviewIndex.value = null
  void load()
})
watch(q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void load(), 250)
})

function toggle(id: string) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}
const allSelected = computed(() => items.value.length > 0 && items.value.every((x) => selected.value.has(x.id)))
function toggleAll() {
  selected.value = allSelected.value ? new Set() : new Set(items.value.slice(0, 50).map((x) => x.id))
}

// --- Sammelaktion ---------------------------------------------------------------------------------
const bulk = ref<null | { action: 'approve' | 'reject', ids: string[] }>(null)
const bulkReason = ref('')
const bulkBusy = ref(false)
const bulkError = ref('')
const notice = ref('')
function askBulk(action: 'approve' | 'reject', ids = [...selected.value]) {
  if (!ids.length) return
  bulkReason.value = ''
  bulkError.value = ''
  bulk.value = { action, ids: ids.slice(0, 50) }
}
async function runBulk() {
  if (!bulk.value) return
  bulkBusy.value = true
  bulkError.value = ''
  try {
    const body: Record<string, unknown> = { ids: bulk.value.ids, action: bulk.value.action }
    if (bulk.value.action === 'reject' && bulkReason.value.trim()) body.reason = bulkReason.value.trim().slice(0, 200)
    const r = await api<{ updated: string[], skipped: string[] }>(`/v1/admin/${tab.value}/bulk`, { method: 'POST', body })
    notice.value = fill(a.value.reports.bulkDone, { n: r.updated.length, skipped: r.skipped.length })
    bulk.value = null
    await load()
  } catch (e) {
    bulkError.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    bulkBusy.value = false
  }
}

// --- Umhang-Prüfdialog (vorhanden: CapeReviewDialog) ----------------------------------------------------
const reviewIndex = ref<number | null>(null)
const reviewCape = computed(() => (reviewIndex.value === null ? null : capes.value[reviewIndex.value] ?? null))
const reviewBusy = ref(false)
const reviewError = ref('')
function stepReview(d: 1 | -1) {
  if (reviewIndex.value === null) return
  const n = reviewIndex.value + d
  if (n >= 0 && n < capes.value.length) reviewIndex.value = n
}
async function capeAction(action: 'approve' | 'reject' | 'delete', reason?: string) {
  const c = reviewCape.value
  if (!c || reviewBusy.value) return
  const i = reviewIndex.value!
  const nextId = capes.value[i + 1]?.id ?? null
  reviewBusy.value = true
  reviewError.value = ''
  try {
    if (action === 'delete') await api(`/v1/admin/capes/${c.id}`, { method: 'DELETE' })
    else if (action === 'reject') await api(`/v1/admin/capes/${c.id}/reject`, { method: 'POST', body: reason ? { reason } : {} })
    else await api(`/v1/admin/capes/${c.id}/approve`, { method: 'POST' })
    await load()
    const target = capes.value.find((x) => x.id === nextId) ?? capes.value[capes.value.length - 1]
    reviewIndex.value = target ? capes.value.findIndex((x) => x.id === target.id) : null
  } catch (e) {
    reviewError.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    reviewBusy.value = false
  }
}

function localUrl(url: string): string {
  try {
    const u = new URL(url, location.origin)
    return u.pathname + u.search
  } catch {
    return url
  }
}
const locale = computed(() => (lang.value === 'de' ? 'de' : 'en-GB'))

const { active } = useListKeys(items, {
  open: (x) => {
    if (tab.value === 'capes') reviewIndex.value = capes.value.findIndex((c) => c.id === x.id)
  },
  toggle: (x) => toggle(x.id),
  onA: (x) => askBulk('approve', [x.id]),
  onR: (x) => askBulk('reject', [x.id]),
})
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ a.uploads.title }}</h1>
      <p class="adm-lead">{{ a.uploads.lead }}</p>
    </header>
    <div class="mt-6 space-y-2">
      <div class="adm-toolbar">
        <div class="adm-seg">
          <button type="button" :aria-pressed="tab === 'capes'" @click="tab = 'capes'">{{ a.uploads.capes }}</button>
          <button type="button" :aria-pressed="tab === 'cosmetics'" @click="tab = 'cosmetics'">{{ a.uploads.cosmetics }}</button>
        </div>
        <div class="adm-seg">
          <button v-for="s in (['pending', 'reported', 'approved', 'rejected'] as const)" :key="s" type="button" :aria-pressed="status === s" @click="status = s">{{ a.uploads.filters[s] }}</button>
        </div>
      </div>
      <div class="adm-toolbar">
        <input v-model="q" class="field max-w-60" maxlength="32" :placeholder="a.uploads.name" :aria-label="a.uploads.name" />
        <select v-model="sort" class="field adm-select" :aria-label="a.common.newest">
          <option value="">↕</option>
          <option value="oldest">{{ a.common.oldest }}</option>
          <option value="newest">{{ a.common.newest }}</option>
        </select>
      </div>
    </div>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <p v-if="notice" class="mt-4 text-sm text-ok" role="status">{{ notice }}</p>

    <div v-if="loading && !items.length" class="mt-6 grid gap-3 md:grid-cols-2 xl:grid-cols-3">
      <div v-for="i in 6" :key="i" class="skeleton h-32 rounded-xl" />
    </div>
    <div v-else-if="!items.length" class="adm-empty mt-6"><SiteIcon name="cape" class="size-6" />{{ a.uploads.empty }}</div>
    <template v-else>
      <label class="mt-5 flex items-center gap-2 text-xs text-base-400">
        <input type="checkbox" class="adm-check mt-0" :checked="allSelected" @change="toggleAll" />{{ a.common.selectAll }}
      </label>
      <ul class="mt-2 grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        <template v-if="tab === 'capes'">
          <li v-for="(c, i) in capes" :key="c.id">
            <div class="adm-row h-full" :data-row="i" :data-active="active === i" :data-selected="selected.has(c.id)">
              <input type="checkbox" class="adm-check" :checked="selected.has(c.id)" :aria-label="c.name" @change="toggle(c.id)" />
              <button type="button" class="flex min-w-0 flex-1 gap-4 text-left" @click="active = i; reviewIndex = i">
                <span class="grid w-20 shrink-0 place-items-center rounded-lg bg-base-950 py-2">
                  <CapeThumb :texture="localUrl(c.url)" :scale="c.scale" :frames="c.frames" :frame-time-ms="c.frameTimeMs" :width="44" />
                </span>
                <span class="min-w-0 flex-1">
                  <span class="block truncate font-semibold text-base-50">{{ c.name || c.id }}</span>
                  <span class="block truncate text-xs text-base-400">{{ fill(a.uploads.owner, { name: c.owner?.name || '–' }) }} · <span :title="when(c.createdAt)">{{ rel(c.createdAt) }}</span></span>
                  <span class="mt-2 flex flex-wrap gap-1.5">
                    <span class="chip py-0.5 tabular-nums">{{ c.width }}×{{ c.height }}</span>
                    <span v-if="c.frames > 1" class="chip py-0.5 tabular-nums">{{ c.frames }}f</span>
                    <span class="chip py-0.5 tabular-nums">{{ formatBytes(c.bytes, locale) }}</span>
                    <span v-if="c.reports.count" class="tone tone-warn"><SiteIcon name="flag" class="size-3" />{{ c.reports.count }}</span>
                  </span>
                </span>
              </button>
            </div>
          </li>
        </template>
        <template v-else>
          <li v-for="(c, i) in cosmetics" :key="c.id">
            <div class="adm-row h-full" :data-row="i" :data-active="active === i" :data-selected="selected.has(c.id)">
              <input type="checkbox" class="adm-check" :checked="selected.has(c.id)" :aria-label="c.name" @change="toggle(c.id)" />
              <span class="grid size-20 shrink-0 place-items-center overflow-hidden rounded-lg bg-base-950">
                <img v-if="c.texture" :src="localUrl(c.texture.url)" alt="" class="max-h-18 max-w-18 [image-rendering:pixelated]" loading="lazy" />
                <span v-else class="text-xs text-base-400">{{ c.slot }}</span>
              </span>
              <span class="min-w-0 flex-1">
                <span class="block truncate font-semibold text-base-50">{{ c.name }}</span>
                <span class="block truncate text-xs text-base-400">{{ c.slot }} · {{ fill(a.uploads.owner, { name: c.owner?.name || '–' }) }} · {{ rel(c.createdAt) }}</span>
                <span v-if="c.reports.count" class="tone tone-warn mt-1"><SiteIcon name="flag" class="size-3" />{{ c.reports.count }}</span>
                <span class="mt-2 flex flex-wrap gap-1.5">
                  <button v-if="c.status !== 'approved'" type="button" class="btn btn-primary px-2.5 py-1 text-xs" @click="askBulk('approve', [c.id])">{{ a.uploads.approve }}</button>
                  <button v-if="c.status !== 'rejected'" type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="askBulk('reject', [c.id])">{{ a.uploads.reject }}</button>
                  <NuxtLink v-if="c.owner" :to="`/admin/players/${c.owner.uuid}`" class="btn btn-ghost px-2.5 py-1 text-xs">{{ c.owner.name }}</NuxtLink>
                </span>
              </span>
            </div>
          </li>
        </template>
      </ul>
      <button v-if="cursor" type="button" class="btn btn-ghost mt-4" :disabled="loading" @click="load(true)">{{ a.common.loadMore }}</button>
    </template>

    <div v-if="selected.size" class="adm-bulkbar">
      <span class="text-sm text-base-50">{{ fill(a.common.selected, { n: selected.size }) }}</span>
      <button type="button" class="btn btn-ghost text-xs" @click="selected = new Set()">{{ a.common.clearSelection }}</button>
      <span class="flex-1" />
      <button type="button" class="btn btn-ghost" @click="askBulk('reject')">{{ a.uploads.rejectSelected }}</button>
      <button type="button" class="btn btn-primary" @click="askBulk('approve')"><SiteIcon name="check" class="size-4" />{{ a.uploads.approveSelected }}</button>
    </div>

    <AdminConfirm
      v-if="bulk"
      :title="bulk.action === 'approve' ? a.uploads.approveSelected : a.uploads.rejectSelected"
      :text="fill(a.uploads.confirmBulk, { n: bulk.ids.length, action: bulk.action === 'approve' ? a.uploads.approve : a.uploads.reject })"
      :danger="bulk.action === 'reject'"
      :busy="bulkBusy"
      :error="bulkError"
      @cancel="bulk = null"
      @confirm="runBulk"
    >
      <template v-if="bulk.action === 'reject'">
        <label class="label mt-3" for="bulk-reason">{{ a.uploads.rejectReason }}</label>
        <input id="bulk-reason" v-model="bulkReason" class="field" maxlength="200" />
      </template>
    </AdminConfirm>

    <CapeReviewDialog
      v-if="reviewCape"
      :cape="reviewCape"
      :index="reviewIndex ?? 0"
      :total="capes.length"
      :busy="reviewBusy"
      :error="reviewError"
      @close="reviewIndex = null"
      @prev="stepReview(-1)"
      @next="stepReview(1)"
      @approve="capeAction('approve')"
      @reject="(reason: string) => capeAction('reject', reason)"
      @delete="can('uploads.delete') ? capeAction('delete') : undefined"
    />
  </div>
</template>
