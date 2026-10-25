<script setup lang="ts">
// Creator-Programm (§35.6): Prüfung (Fan-Symbole 64/16 px inkl. Animation, Fan-Umhänge), Creator (Liste, Suche,
// anlegen, Stufe/Haupt-Code/Kanäle, entziehen/sperren/wiederherstellen, Helfer nur lesend), Anfragen, Vorschau-Builds.
// Prüfen braucht creators.review, alles andere creators.manage – der Server prüft ohnehin jede Anfrage.
// Reiter „Statistik“ (§35.14): Rangliste aller Creator, Details unter /admin/creators/<uuid>. „Dashboard öffnen (Team)“
// öffnet /creator?as=<uuid> im Team-Modus (§35.9).
const { a, fill, rel, day, num } = useAdminText()
const { api, can } = useAdmin()
const route = useRoute()

type Tab = 'queue' | 'roster' | 'stats' | 'requests' | 'pets' | 'previews'
type Tier = 'starter' | 'partner' | 'top'
type State = 'active' | 'revoked' | 'banned'
interface SymbolInfo { url: string, size: number, frames: number, frameMs: number, sha256: string }
interface ProfileLinkInfo { type: string, url: string }
interface ProfileInfo { description: string, links: ProfileLinkInfo[] }
interface QueueItem {
  id: string
  kind: 'symbol' | 'fan_cape' | 'profile'
  creator: { uuid: string, name: string, tier: Tier }
  symbol?: SymbolInfo
  cape?: { capeId: string, url: string, name: string, frames: number, scale: number, frameTimeMs: number | null }
  profile?: ProfileInfo & { previous: ProfileInfo | null }
  createdAt: string
}
interface Channel { platform: string, url: string, followers: number }
interface Helper { uuid: string, name: string, rights: string[], status: 'pending' | 'active' }
interface Creator {
  uuid: string
  name: string
  tier: Tier
  state: State
  reason: string | null
  fans: number
  mainCode: string | null
  since: string
  channels: Channel[]
  symbol: SymbolInfo | null
  milestone: string | null
  helpers: Helper[]
}
interface Request {
  id: number
  kind: 'upgrade' | 'team_cape'
  status: 'open' | 'accepted' | 'declined'
  createdAt: string
  reason: string | null
  creator: { uuid: string, name: string, tier: Tier }
  data: { channelUrl?: string, followers?: number, note?: string, text?: string }
}
interface Preview { id: number, product: 'launcher' | 'client', version: string, notes: string, url: string, sha256: string, publishedAt: string }

const TIERS: Tier[] = ['starter', 'partner', 'top']
const PLATFORMS = ['youtube', 'twitch', 'tiktok', 'instagram', 'x', 'kick', 'other'] as const
const t = computed(() => a.value.creators)
const review = computed(() => can('creators.review'))
const manage = computed(() => can('creators.manage'))
const tabs = computed<Tab[]>(() => [
  ...(review.value ? ['queue' as const] : []),
  'roster',
  ...(manage.value ? ['stats' as const, 'requests' as const, 'pets' as const, 'previews' as const] : []),
])
const initial = route.query.tab
const tab = ref<Tab>(typeof initial === 'string' && ['queue', 'roster', 'stats', 'requests', 'pets', 'previews'].includes(initial) ? initial as Tab : review.value ? 'queue' : 'roster')

const error = ref('')
const notice = ref('')
const loading = ref(false)
const busy = ref('')
const fail = (e: unknown) => (error.value = fill(a.value.common.failed, { error: apiMessage(e) }))

/** Texturen immer über diese Seite laden (Cookie für wartende Uploads), auch wenn die API eine andere Adresse nennt. */
function localUrl(url: string): string {
  try {
    const u = new URL(url, location.origin)
    return u.pathname + u.search
  } catch {
    return url
  }
}

// --- Prüfung -------------------------------------------------------------------------------------
const queue = ref<QueueItem[]>([])
const rejecting = ref<QueueItem | null>(null)
const rejectPreset = ref('')
const rejectText = ref('')
async function loadQueue() {
  queue.value = (await api<{ items: QueueItem[] }>('/v1/admin/creators/queue')).items
}
async function approve(q: QueueItem) {
  busy.value = q.id
  error.value = ''
  try {
    await api(`/v1/admin/creators/queue/${q.id}/approve`, { method: 'POST' })
    notice.value = t.value.queue.approved
    await loadQueue()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}
const presets = computed(() => (rejecting.value?.kind === 'profile' ? t.value.queue.profilePresets : t.value.queue.presets))
function askReject(q: QueueItem) {
  rejectPreset.value = (q.kind === 'profile' ? t.value.queue.profilePresets : t.value.queue.presets)[0]!
  rejectText.value = ''
  rejecting.value = q
}
async function reject() {
  const q = rejecting.value
  if (!q) return
  const reason = [rejectPreset.value, rejectText.value.replace(/\s+/g, ' ').trim()].filter(Boolean).join(' – ').slice(0, 300)
  busy.value = q.id
  error.value = ''
  try {
    await api(`/v1/admin/creators/queue/${q.id}/reject`, { method: 'POST', body: { reason } })
    rejecting.value = null
    notice.value = t.value.queue.rejected
    await loadQueue()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

// --- Creator -------------------------------------------------------------------------------------
const creators = ref<Creator[]>([])
const q = ref('')
const stateFilter = ref<'' | State>('')
let searchTimer: ReturnType<typeof setTimeout> | null = null
async function loadCreators() {
  const p = new URLSearchParams()
  if (q.value.trim()) p.set('q', q.value.trim().slice(0, 32))
  if (stateFilter.value) p.set('state', stateFilter.value)
  creators.value = (await api<{ creators: Creator[] }>(`/v1/admin/creators?${p}`)).creators
}
watch(q, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void run(loadCreators), 250)
})
watch(stateFilter, () => void run(loadCreators))

const adding = ref(false)
const addForm = reactive({ player: '', tier: 'starter' as Tier, mainCode: '' })
async function addCreator() {
  busy.value = 'add'
  error.value = ''
  try {
    await api('/v1/admin/creators', {
      method: 'POST',
      body: { player: addForm.player.trim(), tier: addForm.tier, ...(addForm.mainCode.trim() ? { mainCode: addForm.mainCode.trim() } : {}) },
    })
    adding.value = false
    Object.assign(addForm, { player: '', tier: 'starter', mainCode: '' })
    await loadCreators()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

const editing = ref<Creator | null>(null)
const editForm = reactive({ tier: 'starter' as Tier, mainCode: '', channels: [] as Channel[] })
function edit(c: Creator) {
  Object.assign(editForm, { tier: c.tier, mainCode: c.mainCode ?? '', channels: c.channels.map((x) => ({ ...x })) })
  editing.value = c
}
async function saveEdit() {
  const c = editing.value
  if (!c) return
  busy.value = 'edit'
  error.value = ''
  try {
    const body: Record<string, unknown> = {
      tier: editForm.tier,
      channels: editForm.channels.filter((x) => x.url.trim()).map((x) => ({ platform: x.platform, url: x.url.trim(), followers: Math.max(0, Math.round(Number(x.followers) || 0)) })),
    }
    if (editForm.mainCode.trim() && editForm.mainCode.trim().toUpperCase() !== c.mainCode) body.mainCode = editForm.mainCode.trim()
    await api(`/v1/admin/creators/${c.uuid}`, { method: 'PATCH', body })
    editing.value = null
    notice.value = t.value.roster.saved
    await loadCreators()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

const revoking = ref<{ c: Creator, ban: boolean } | null>(null)
const revokeForm = reactive({ reason: '', removeFanCapes: false })
function askRevoke(c: Creator, ban: boolean) {
  Object.assign(revokeForm, { reason: '', removeFanCapes: false })
  revoking.value = { c, ban }
}
async function revoke() {
  const r = revoking.value
  if (!r || !revokeForm.reason.trim()) return
  busy.value = 'revoke'
  error.value = ''
  try {
    await api(`/v1/admin/creators/${r.c.uuid}/revoke`, {
      method: 'POST',
      body: { reason: revokeForm.reason.trim().slice(0, 300), ban: r.ban, removeFanCapes: revokeForm.removeFanCapes },
    })
    revoking.value = null
    await loadCreators()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}
async function restore(c: Creator) {
  busy.value = `restore:${c.uuid}`
  error.value = ''
  try {
    await api(`/v1/admin/creators/${c.uuid}/restore`, { method: 'POST' })
    await loadCreators()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}
const deleting = ref<Creator | null>(null)
async function removeCreator() {
  const c = deleting.value
  if (!c) return
  busy.value = 'delete'
  error.value = ''
  try {
    await api(`/v1/admin/creators/${c.uuid}`, { method: 'DELETE' })
    deleting.value = null
    await loadCreators()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}
const stateTone = (s: State) => (s === 'active' ? 'tone-ok' : s === 'banned' ? 'tone-danger' : 'tone-muted')

// --- Anfragen ------------------------------------------------------------------------------------
const requests = ref<Request[]>([])
const answering = ref<{ r: Request, status: 'accepted' | 'declined' } | null>(null)
const answerReason = ref('')
async function loadRequests() {
  requests.value = (await api<{ requests: Request[] }>('/v1/admin/creators/requests')).requests
}
function askAnswer(r: Request, status: 'accepted' | 'declined') {
  answerReason.value = ''
  answering.value = { r, status }
}
async function answer() {
  const x = answering.value
  if (!x) return
  busy.value = 'answer'
  error.value = ''
  try {
    const res = await api<{ requests: Request[] }>(`/v1/admin/creators/requests/${x.r.id}`, {
      method: 'POST',
      body: { status: x.status, ...(answerReason.value.trim() ? { reason: answerReason.value.replace(/\s+/g, ' ').trim().slice(0, 300) } : {}) },
    })
    requests.value = res.requests
    answering.value = null
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

// --- Vorschau-Builds -------------------------------------------------------------------------------
const previews = ref<Preview[]>([])
const previewForm = reactive({ product: 'launcher' as 'launcher' | 'client', version: '', notes: '', url: '', sha256: '' })
const removingPreview = ref<Preview | null>(null)
async function loadPreviews() {
  previews.value = (await api<{ previews: Preview[] }>('/v1/admin/creators/previews')).previews
}
async function addPreview() {
  busy.value = 'preview'
  error.value = ''
  try {
    await api('/v1/admin/creators/previews', {
      method: 'POST',
      body: {
        product: previewForm.product,
        version: previewForm.version.trim(),
        notes: previewForm.notes.trim().slice(0, 2000),
        url: previewForm.url.trim(),
        sha256: previewForm.sha256.trim(),
      },
    })
    Object.assign(previewForm, { version: '', notes: '', url: '', sha256: '' })
    await loadPreviews()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}
async function removePreview() {
  const p = removingPreview.value
  if (!p) return
  busy.value = 'preview-rm'
  try {
    await api(`/v1/admin/creators/previews/${p.id}`, { method: 'DELETE' })
    removingPreview.value = null
    await loadPreviews()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

// --- Haustiere (§35.13) ---------------------------------------------------------------------------
type PetStatus = 'open' | 'in_progress' | 'done' | 'declined'
const PET_STATUSES: PetStatus[] = ['open', 'in_progress', 'done', 'declined']
interface PetRef { cosmeticId: string, name: string, card: string | null, count: number, available: boolean, assignedAt: string }
interface PetQuota { month: string, limit: number, used: number, remaining: number, resetsAt: string }
interface PetAssignment { creator: { uuid: string, name: string, tier: Tier, state: State }, pet: PetRef, assignedBy: { uuid: string, name: string }, quota: PetQuota }
interface PetOption { id: string, name: string, unlock: string, hidden: boolean, card: string | null, available: boolean, owner: { uuid: string, name: string } | null }
interface PetWish {
  id: number
  status: PetStatus
  species: string
  description: string
  colors: string
  image: { url: string, mime: string, width: number, height: number } | null
  reply: string | null
  cosmeticId: string | null
  createdAt: string
  decidedAt: string | null
  creator: { uuid: string, name: string, tier: Tier, state: State }
}
const pt = computed(() => t.value.pets)
const petAssignments = ref<PetAssignment[]>([])
const petOptions = ref<PetOption[]>([])
const petLimits = reactive({ starter: 10, partner: 50, top: 200 })
const petWishes = ref<PetWish[]>([])
const wishFilter = ref<'' | PetStatus>('')
async function loadPets() {
  const [o, w] = await Promise.all([
    api<{ pets: PetAssignment[], cosmetics: PetOption[], limits: Record<Tier, number> }>('/v1/admin/creators/pets'),
    api<{ requests: PetWish[] }>(`/v1/admin/creators/pet-requests${wishFilter.value ? `?status=${wishFilter.value}` : ''}`),
  ])
  petAssignments.value = o.pets
  petOptions.value = o.cosmetics
  Object.assign(petLimits, o.limits)
  petWishes.value = w.requests
}
watch(wishFilter, () => void run(loadPets))
const petName = (id: string | null) => (id ? petOptions.value.find((p) => p.id === id)?.name ?? id : '')

// Zuordnen: Creator per Name/UUID/Code suchen (wie die Creator-Liste), Haustier wählen.
const assignQuery = ref('')
const assignHits = ref<Creator[]>([])
const assignTo = ref<Creator | null>(null)
const assignPet = ref('')
let assignTimer: ReturnType<typeof setTimeout> | null = null
watch(assignQuery, (v) => {
  if (assignTimer) clearTimeout(assignTimer)
  if (assignTo.value && v === assignTo.value.name) return
  assignTo.value = null
  if (!v.trim()) {
    assignHits.value = []
    return
  }
  assignTimer = setTimeout(async () => {
    try {
      assignHits.value = (await api<{ creators: Creator[] }>(`/v1/admin/creators?q=${encodeURIComponent(v.trim().slice(0, 32))}&state=active`)).creators.slice(0, 8)
    } catch (e) {
      fail(e)
    }
  }, 250)
})
function chooseCreator(c: Creator) {
  assignTo.value = c
  assignQuery.value = c.name
  assignHits.value = []
  assignPet.value = petAssignments.value.find((x) => x.creator.uuid === c.uuid)?.pet.cosmeticId ?? ''
}
const takenBy = (o: PetOption) => (o.owner && o.owner.uuid !== assignTo.value?.uuid ? o.owner : null)
async function doAssign() {
  const c = assignTo.value
  if (!c || !assignPet.value) return
  busy.value = 'pet-assign'
  error.value = ''
  try {
    await api(`/v1/admin/creators/${c.uuid}/pet`, { method: 'PUT', body: { cosmeticId: assignPet.value } })
    notice.value = pt.value.assigned
    assignTo.value = null
    assignQuery.value = ''
    assignPet.value = ''
    await loadPets()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}
const removingPet = ref<PetAssignment | null>(null)
async function removePet() {
  const x = removingPet.value
  if (!x) return
  busy.value = 'pet-remove'
  error.value = ''
  try {
    await api(`/v1/admin/creators/${x.creator.uuid}/pet`, { method: 'DELETE' })
    removingPet.value = null
    await loadPets()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}
async function saveLimits() {
  busy.value = 'pet-limits'
  error.value = ''
  try {
    const body = Object.fromEntries(TIERS.map((x) => [x, Math.max(0, Math.min(1_000_000, Math.round(Number(petLimits[x]) || 0)))]))
    Object.assign(petLimits, (await api<{ limits: Record<Tier, number> }>('/v1/admin/creators/pets/limits', { method: 'PUT', body })).limits)
    notice.value = pt.value.limitsSaved
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

// Wunsch bearbeiten: Status + Antwort, mit „Fertig“ optional gleich zuordnen.
const editingWish = ref<PetWish | null>(null)
const wishForm = reactive({ status: 'in_progress' as PetStatus, reply: '', cosmeticId: '' })
function editWish(w: PetWish) {
  Object.assign(wishForm, { status: w.status === 'open' ? 'in_progress' : w.status, reply: w.reply ?? '', cosmeticId: '' })
  editingWish.value = w
}
async function saveWish() {
  const w = editingWish.value
  if (!w) return
  busy.value = 'pet-wish'
  error.value = ''
  try {
    const reply = wishForm.reply.replace(/\s+/g, ' ').trim().slice(0, 500)
    await api(`/v1/admin/creators/pet-requests/${w.id}`, {
      method: 'POST',
      body: { status: wishForm.status, reply: reply || null, ...(wishForm.status === 'done' && wishForm.cosmeticId ? { cosmeticId: wishForm.cosmeticId } : {}) },
    })
    editingWish.value = null
    await loadPets()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}
const wishTone = (s: PetStatus) => (s === 'open' ? 'tone-warn' : s === 'in_progress' ? 'tone-info' : s === 'done' ? 'tone-ok' : 'tone-muted')

// --- Laden ---------------------------------------------------------------------------------------
async function run(fn: () => Promise<void>) {
  loading.value = true
  error.value = ''
  try {
    await fn()
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
}
// Die Statistik lädt sich selbst (eigene Filter).
const loaders: Record<Tab, () => Promise<void>> = { queue: loadQueue, roster: loadCreators, stats: async () => {}, requests: loadRequests, pets: loadPets, previews: loadPreviews }
watch(tab, (v) => {
  notice.value = ''
  void navigateTo({ query: { ...route.query, tab: v } }, { replace: true })
  void run(loaders[v])
})
const lp = useLocalePath()
const dashboardLink = (uuid: string) => lp(`/creator?as=${uuid}`)
onMounted(() => {
  if (!tabs.value.includes(tab.value)) tab.value = tabs.value[0] ?? 'roster'
  void run(loaders[tab.value])
})
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ t.title }}</h1>
      <p class="adm-lead">{{ t.lead }}</p>
    </header>
    <div class="adm-toolbar mt-6">
      <div class="adm-seg">
        <button v-for="x in tabs" :key="x" type="button" :aria-pressed="tab === x" @click="tab = x">{{ t.tabs[x] }}</button>
      </div>
    </div>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <p v-if="notice" class="mt-4 text-sm text-ok" role="status">{{ notice }}</p>
    <div v-if="loading" class="skeleton mt-6 h-40 rounded-xl" />

    <!-- Prüfung -->
    <template v-else-if="tab === 'queue'">
      <p v-if="!queue.length" class="adm-empty mt-6"><SiteIcon name="check" class="size-6" />{{ t.queue.empty }}</p>
      <ul v-else class="mt-6 space-y-3">
        <li v-for="item in queue" :key="item.id" class="card flex flex-wrap items-center gap-4 p-4">
          <div v-if="item.symbol" class="flex items-end gap-3 rounded-lg bg-base-950 p-3">
            <CreatorSymbol :url="localUrl(item.symbol.url)" :frames="item.symbol.frames" :frame-ms="item.symbol.frameMs" :px="64" :label="t.queue.symbol" />
            <CreatorSymbol :url="localUrl(item.symbol.url)" :frames="item.symbol.frames" :frame-ms="item.symbol.frameMs" :px="16" />
          </div>
          <div v-else-if="item.cape" class="rounded-lg bg-base-950 p-2">
            <CapeThumb :texture="localUrl(item.cape.url)" :scale="item.cape.scale" :frames="item.cape.frames" :frame-time-ms="item.cape.frameTimeMs" :width="56" />
          </div>
          <div v-else-if="item.profile" class="grid size-16 shrink-0 place-items-center rounded-lg bg-base-950 text-base-400">
            <SiteIcon name="note" class="size-7" />
          </div>
          <div class="min-w-0 flex-1">
            <p class="font-semibold text-base-50">{{ item.kind === 'symbol' ? t.queue.symbol : item.kind === 'profile' ? t.queue.profile : t.queue.fanCape }}<span v-if="item.cape"> · {{ item.cape.name }}</span></p>
            <p class="text-xs text-base-400">
              {{ fill(t.queue.by, { name: item.creator.name || item.creator.uuid, tier: t.roster.tiers[item.creator.tier] ?? item.creator.tier }) }} ·
              {{ fill(t.queue.submitted, { date: rel(item.createdAt) }) }}
              <template v-if="item.symbol"> · {{ item.symbol.size }}×{{ item.symbol.size }} · {{ fill(t.queue.frames, { n: item.symbol.frames, ms: item.symbol.frameMs }) }}</template>
            </p>
          </div>
          <button type="button" class="btn btn-primary" :disabled="busy === item.id" @click="approve(item)">{{ t.queue.approve }}</button>
          <button type="button" class="btn btn-ghost" :disabled="busy === item.id" @click="askReject(item)">{{ t.queue.reject }}</button>
          <!-- Profil: neue Fassung (reiner Text, Links im neuen Tab) neben der bisher öffentlichen -->
          <div v-if="item.profile" class="grid w-full gap-3 md:grid-cols-2">
            <div class="profile-box profile-new">
              <p class="profile-label">{{ t.queue.profileHint }}</p>
              <p class="mt-2 text-sm whitespace-pre-line text-base-100 [overflow-wrap:anywhere]">{{ item.profile.description || t.queue.noDescription }}</p>
              <ul v-if="item.profile.links.length" class="mt-3 space-y-1 text-xs">
                <li v-for="l in item.profile.links" :key="l.url" class="flex gap-2">
                  <span class="w-20 shrink-0 font-semibold text-base-300">{{ l.type }}</span>
                  <a :href="l.url" target="_blank" rel="noopener noreferrer nofollow" class="break-all text-lamp-300 hover:underline">{{ l.url }}</a>
                </li>
              </ul>
            </div>
            <div class="profile-box">
              <p class="profile-label">{{ t.queue.previous }}</p>
              <template v-if="item.profile.previous">
                <p class="mt-2 text-sm whitespace-pre-line text-base-300 [overflow-wrap:anywhere]">{{ item.profile.previous.description || t.queue.noDescription }}</p>
                <ul v-if="item.profile.previous.links.length" class="mt-3 space-y-1 text-xs">
                  <li v-for="l in item.profile.previous.links" :key="l.url" class="flex gap-2">
                    <span class="w-20 shrink-0 font-semibold text-base-400">{{ l.type }}</span>
                    <span class="break-all text-base-400">{{ l.url }}</span>
                  </li>
                </ul>
              </template>
              <p v-else class="mt-2 text-sm text-base-400">{{ t.queue.noPrevious }}</p>
            </div>
          </div>
        </li>
      </ul>
    </template>

    <!-- Creator -->
    <template v-else-if="tab === 'roster'">
      <div class="adm-toolbar mt-4">
        <input v-model="q" class="field max-w-60" maxlength="32" :placeholder="t.roster.search" :aria-label="t.roster.search" />
        <select v-model="stateFilter" class="field adm-select" :aria-label="t.roster.allStates">
          <option value="">{{ t.roster.allStates }}</option>
          <option v-for="s in (['active', 'revoked', 'banned'] as const)" :key="s" :value="s">{{ t.roster.states[s] }}</option>
        </select>
        <button v-if="manage" type="button" class="btn btn-primary ml-auto" @click="adding = true"><SiteIcon name="plus" class="size-4" />{{ t.roster.add }}</button>
      </div>
      <p v-if="!creators.length" class="adm-empty mt-6"><SiteIcon name="crown" class="size-6" />{{ t.roster.empty }}</p>
      <ul v-else class="mt-4 space-y-2">
        <li v-for="c in creators" :key="c.uuid" class="card p-4">
          <div class="flex flex-wrap items-center gap-3">
            <PlayerHead :uuid="c.uuid" :name="c.name" :size="32" />
            <div class="min-w-0 flex-1">
              <p class="flex flex-wrap items-center gap-2">
                <NuxtLink v-if="can('players.view')" :to="`/admin/players/${c.uuid}`" class="font-semibold text-base-50 hover:underline">{{ c.name || c.uuid }}</NuxtLink>
                <span v-else class="font-semibold text-base-50">{{ c.name || c.uuid }}</span>
                <span class="tone tone-info">{{ t.roster.tiers[c.tier] }}</span>
                <span class="tone" :class="stateTone(c.state)">{{ t.roster.states[c.state] }}</span>
                <CreatorSymbol v-if="c.symbol" :url="localUrl(c.symbol.url)" :frames="c.symbol.frames" :frame-ms="c.symbol.frameMs" :px="16" />
              </p>
              <p class="mt-0.5 flex flex-wrap gap-x-3 text-xs text-base-400">
                <span>{{ t.roster.fans }}: {{ num(c.fans) }}</span>
                <span v-if="c.mainCode">{{ t.roster.code }}: <code class="text-lamp-300">{{ c.mainCode }}</code></span>
                <span>{{ fill(t.roster.since, { date: day(c.since) }) }}</span>
              </p>
              <p v-if="c.reason" class="mt-1 text-xs text-redstone-300">{{ c.reason }}</p>
            </div>
            <template v-if="manage">
              <template v-if="c.state === 'active'">
                <NuxtLink :to="`/admin/creators/${c.uuid}`" class="btn btn-ghost"><SiteIcon name="chart" class="size-4" />{{ t.stats.details }}</NuxtLink>
                <a :href="dashboardLink(c.uuid)" class="btn btn-ghost" :title="t.stats.openDashboardHint"><SiteIcon name="shield" class="size-4" />{{ t.stats.openDashboard }}</a>
                <button type="button" class="btn btn-ghost" @click="edit(c)">{{ t.roster.edit }}</button>
                <button type="button" class="btn btn-ghost" @click="askRevoke(c, false)">{{ t.roster.revoke }}</button>
                <button type="button" class="btn btn-danger" @click="askRevoke(c, true)">{{ t.roster.ban }}</button>
              </template>
              <button v-else type="button" class="btn btn-ghost" :disabled="busy === `restore:${c.uuid}`" @click="restore(c)">{{ t.roster.restore }}</button>
              <button type="button" class="btn btn-danger" :title="t.roster.deleteHint" @click="deleting = c">{{ t.roster.delete }}</button>
            </template>
          </div>
          <div class="mt-3 border-t border-base-800 pt-2 text-xs text-base-400">
            <span class="font-semibold text-base-300">{{ t.roster.helpers }}:</span>
            <span v-if="!c.helpers.length"> {{ t.roster.noHelpers }}</span>
            <span v-for="h in c.helpers" :key="h.uuid" class="ml-2 inline-flex items-center gap-1">
              {{ h.name || h.uuid }}
              <span class="text-base-500">({{ h.rights.map((r: string) => t.roster.rights[r] ?? r).join(', ') }}<template v-if="h.status === 'pending'">, {{ t.roster.pending }}</template>)</span>
            </span>
          </div>
        </li>
      </ul>
      <p class="mt-4 text-xs text-base-400">{{ t.roster.note }}</p>
    </template>

    <!-- Statistik (§35.14) -->
    <AdminCreatorStats v-else-if="tab === 'stats'" />

    <!-- Anfragen -->
    <template v-else-if="tab === 'requests'">
      <p v-if="!requests.length" class="adm-empty mt-6"><SiteIcon name="inbox" class="size-6" />{{ t.requests.empty }}</p>
      <ul v-else class="mt-6 space-y-2">
        <li v-for="r in requests" :key="r.id" class="card flex flex-wrap items-start gap-3 p-4">
          <PlayerHead :uuid="r.creator.uuid" :name="r.creator.name" :size="28" />
          <div class="min-w-0 flex-1">
            <p class="flex flex-wrap items-center gap-2">
              <span class="font-semibold text-base-50">{{ t.requests.kinds[r.kind] }}</span>
              <span class="tone" :class="r.status === 'open' ? 'tone-warn' : r.status === 'accepted' ? 'tone-ok' : 'tone-muted'">{{ t.requests.statuses[r.status] }}</span>
              <span class="text-xs text-base-400">{{ r.creator.name }} · {{ t.roster.tiers[r.creator.tier] }} · {{ rel(r.createdAt) }}</span>
            </p>
            <p v-if="r.data.channelUrl" class="mt-1 text-sm">
              {{ t.requests.channel }}:
              <a :href="r.data.channelUrl" target="_blank" rel="noopener noreferrer nofollow" class="text-lamp-300 hover:underline">{{ r.data.channelUrl }}</a>
              <span v-if="r.data.followers !== undefined" class="text-base-400"> · {{ fill(t.requests.followers, { n: num(r.data.followers) }) }}</span>
            </p>
            <p v-if="r.data.note || r.data.text" class="mt-1 whitespace-pre-line text-sm text-base-300">{{ r.data.note || r.data.text }}</p>
            <p v-if="r.reason" class="mt-1 text-xs text-base-400">→ {{ r.reason }}</p>
          </div>
          <template v-if="r.status === 'open'">
            <button type="button" class="btn btn-primary" :title="r.kind === 'upgrade' ? t.requests.upgradeHint : undefined" @click="askAnswer(r, 'accepted')">{{ t.requests.accept }}</button>
            <button type="button" class="btn btn-ghost" @click="askAnswer(r, 'declined')">{{ t.requests.decline }}</button>
          </template>
        </li>
      </ul>
    </template>

    <!-- Haustiere (§35.13) -->
    <template v-else-if="tab === 'pets'">
      <p class="mt-4 max-w-3xl text-sm text-base-400">{{ pt.lead }}</p>
      <div class="mt-4 grid gap-6 lg:grid-cols-[1fr_22rem]">
        <div class="min-w-0">
          <div class="adm-toolbar">
            <h2 class="section-title">{{ pt.requestsTitle }}</h2>
            <select v-model="wishFilter" class="field adm-select ml-auto" :aria-label="pt.status">
              <option value="">{{ pt.allStatuses }}</option>
              <option v-for="x in PET_STATUSES" :key="x" :value="x">{{ pt.statuses[x] }}</option>
            </select>
          </div>
          <p v-if="!petWishes.length" class="adm-empty mt-3"><SiteIcon name="inbox" class="size-6" />{{ pt.noRequests }}</p>
          <ul v-else class="mt-3 space-y-2">
            <li v-for="w in petWishes" :key="w.id" class="card flex flex-wrap items-start gap-3 p-4">
              <a v-if="w.image" :href="localUrl(w.image.url)" target="_blank" rel="noopener" class="shrink-0" :title="`${w.image.width}×${w.image.height}`">
                <img :src="localUrl(w.image.url)" alt="" class="wish-thumb" loading="lazy" />
              </a>
              <div v-else class="wish-thumb grid place-items-center text-center text-[10px] text-base-500">{{ pt.noImage }}</div>
              <div class="min-w-0 flex-1">
                <p class="flex flex-wrap items-center gap-2">
                  <span class="font-semibold text-base-50">{{ w.species }}</span>
                  <span class="tone" :class="wishTone(w.status)">{{ pt.statuses[w.status] }}</span>
                  <span class="text-xs text-base-400">{{ w.creator.name }} · {{ t.roster.tiers[w.creator.tier] }} · {{ rel(w.createdAt) }}</span>
                  <span v-if="w.creator.state !== 'active'" class="tone tone-danger">{{ t.roster.states[w.creator.state] }}</span>
                </p>
                <p class="mt-1 text-sm whitespace-pre-line text-base-200 [overflow-wrap:anywhere]">{{ w.description }}</p>
                <p v-if="w.colors" class="mt-1 text-xs text-base-400">{{ pt.colors }}: {{ w.colors }}</p>
                <p v-if="w.cosmeticId" class="mt-1 text-xs text-ok">→ {{ petName(w.cosmeticId) }}</p>
                <p v-if="w.reply" class="mt-1 text-xs text-base-400">„{{ w.reply }}“</p>
              </div>
              <button type="button" class="btn btn-ghost" @click="editWish(w)">{{ pt.answer }}</button>
            </li>
          </ul>

          <h2 class="section-title mt-8">{{ pt.listTitle }}</h2>
          <p v-if="!petAssignments.length" class="adm-empty mt-3"><SiteIcon name="star" class="size-6" />{{ pt.noPets }}</p>
          <ul v-else class="mt-3 grid gap-3 sm:grid-cols-2">
            <li v-for="x in petAssignments" :key="x.creator.uuid" class="card flex gap-3 p-4">
              <img v-if="x.pet.card" :src="localUrl(x.pet.card)" alt="" class="pet-thumb" loading="lazy" />
              <div v-else class="pet-thumb grid place-items-center text-base-500"><SiteIcon name="star" class="size-6" /></div>
              <div class="min-w-0 flex-1">
                <p class="font-semibold text-base-50">{{ x.pet.name }} <span class="text-xs font-normal text-base-400">· {{ fill(pt.animals, { n: x.pet.count }) }}</span></p>
                <p class="mt-0.5 flex items-center gap-1.5 text-sm text-base-200">
                  <PlayerHead :uuid="x.creator.uuid" :name="x.creator.name" :size="16" />{{ x.creator.name }}
                  <span class="tone tone-info">{{ t.roster.tiers[x.creator.tier] }}</span>
                  <span v-if="x.creator.state !== 'active'" class="tone tone-danger">{{ t.roster.states[x.creator.state] }}</span>
                </p>
                <p class="mt-1 text-xs text-base-400">{{ fill(pt.month, { used: num(x.quota.used), limit: num(x.quota.limit) }) }}</p>
                <p class="text-xs text-base-500">{{ fill(pt.by, { date: day(x.pet.assignedAt), name: x.assignedBy.name || x.assignedBy.uuid }) }}</p>
                <p v-if="!x.pet.available" class="mt-1 text-xs text-redstone-300">{{ pt.unavailable }}</p>
                <button type="button" class="mt-2 text-xs text-redstone-300 underline" @click="removingPet = x">{{ pt.remove }}</button>
              </div>
            </li>
          </ul>

          <AdminPetExtras />
        </div>

        <div class="space-y-6">
          <form class="card h-fit p-5" @submit.prevent="doAssign">
            <h2 class="section-title">{{ pt.assignTitle }}</h2>
            <p class="mt-1 text-xs text-base-400">{{ pt.assignLead }}</p>
            <label class="label mt-4" for="pa-creator">{{ t.tabs.roster }}</label>
            <div class="relative">
              <input id="pa-creator" v-model="assignQuery" class="field" maxlength="32" autocomplete="off" spellcheck="false" :placeholder="pt.creatorSearch" />
              <ul v-if="assignHits.length" class="assign-hits" role="listbox">
                <li v-for="c in assignHits" :key="c.uuid">
                  <button type="button" role="option" :aria-selected="false" @click="chooseCreator(c)">
                    <PlayerHead :uuid="c.uuid" :name="c.name" :size="20" />
                    <span class="flex-1 truncate text-left">{{ c.name }}</span>
                    <span class="text-xs text-base-400">{{ t.roster.tiers[c.tier] }}<template v-if="c.mainCode"> · {{ c.mainCode }}</template></span>
                  </button>
                </li>
              </ul>
              <p v-else-if="assignQuery.trim() && !assignTo" class="mt-1 text-xs text-base-500">{{ pt.noMatch }}</p>
            </div>
            <p v-if="assignTo" class="mt-2 flex items-center gap-2 text-sm text-ok"><SiteIcon name="check" class="size-4" />{{ assignTo.name }} · {{ t.roster.tiers[assignTo.tier] }}</p>
            <label class="label mt-3" for="pa-pet">{{ pt.pet }}</label>
            <select id="pa-pet" v-model="assignPet" class="field" :disabled="!assignTo">
              <option value="" disabled>{{ pt.choosePet }}</option>
              <option v-for="o in petOptions" :key="o.id" :value="o.id" :disabled="!!takenBy(o)">
                {{ o.name }}{{ takenBy(o) ? ` – ${fill(pt.ownedBy, { name: takenBy(o)!.name })}` : '' }}{{ o.available ? '' : ` (${pt.unavailable})` }}
              </option>
            </select>
            <div v-if="assignPet" class="mt-3 flex items-center gap-3 rounded-lg bg-base-950 p-2">
              <img v-if="petOptions.find((o) => o.id === assignPet)?.card" :src="localUrl(petOptions.find((o) => o.id === assignPet)!.card!)" alt="" class="pet-thumb" />
              <span class="text-sm text-base-200">{{ petName(assignPet) }}</span>
            </div>
            <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'pet-assign' || !assignTo || !assignPet">{{ pt.assign }}</button>
          </form>

          <form class="card h-fit p-5" @submit.prevent="saveLimits">
            <h2 class="section-title">{{ pt.limitsTitle }}</h2>
            <p class="mt-1 text-xs text-base-400">{{ pt.limitsLead }}</p>
            <div class="mt-4 grid grid-cols-3 gap-2">
              <label v-for="x in TIERS" :key="x" class="text-xs text-base-300">{{ t.roster.tiers[x] }}
                <input v-model.number="petLimits[x]" class="field mt-1" type="number" min="0" max="1000000" required />
              </label>
            </div>
            <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'pet-limits'">{{ a.common.save }}</button>
          </form>
        </div>
      </div>
    </template>

    <!-- Vorschau-Builds -->
    <template v-else-if="tab === 'previews'">
      <p class="mt-4 text-sm text-base-400">{{ t.previews.lead }}</p>
      <div class="mt-4 grid gap-6 lg:grid-cols-[22rem_1fr]">
        <form class="card h-fit p-5" @submit.prevent="addPreview">
          <h2 class="section-title">{{ t.previews.add }}</h2>
          <label class="label mt-4" for="pv-product">{{ t.previews.product }}</label>
          <select id="pv-product" v-model="previewForm.product" class="field">
            <option v-for="p in (['launcher', 'client'] as const)" :key="p" :value="p">{{ t.previews.products[p] }}</option>
          </select>
          <label class="label mt-3" for="pv-version">{{ t.previews.version }}</label>
          <input id="pv-version" v-model="previewForm.version" class="field" maxlength="40" required pattern="[0-9A-Za-z._+\-]{1,40}" />
          <label class="label mt-3" for="pv-url">{{ t.previews.url }}</label>
          <input id="pv-url" v-model="previewForm.url" class="field" type="url" maxlength="300" required pattern="https://.*" />
          <label class="label mt-3" for="pv-sha">{{ t.previews.sha256 }}</label>
          <input id="pv-sha" v-model="previewForm.sha256" class="field font-mono" maxlength="64" required pattern="[0-9a-fA-F]{64}" spellcheck="false" />
          <label class="label mt-3" for="pv-notes">{{ t.previews.notes }}</label>
          <textarea id="pv-notes" v-model="previewForm.notes" class="field" rows="4" maxlength="2000" />
          <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'preview'">{{ t.previews.add }}</button>
        </form>
        <div class="min-w-0">
          <p v-if="!previews.length" class="adm-empty"><SiteIcon name="download" class="size-6" />{{ t.previews.empty }}</p>
          <ul v-else class="space-y-2">
            <li v-for="p in previews" :key="p.id" class="card flex flex-wrap items-start gap-3 p-4">
              <div class="min-w-0 flex-1">
                <p class="font-semibold text-base-50">{{ t.previews.products[p.product] }} {{ p.version }} <span class="text-xs font-normal text-base-400">· {{ day(p.publishedAt) }}</span></p>
                <p class="truncate text-xs"><a :href="p.url" target="_blank" rel="noopener noreferrer nofollow" class="text-lamp-300 hover:underline">{{ p.url }}</a></p>
                <p class="truncate font-mono text-[11px] text-base-500">{{ p.sha256 }}</p>
                <p v-if="p.notes" class="mt-1 whitespace-pre-line text-sm text-base-300">{{ p.notes }}</p>
              </div>
              <button type="button" class="btn btn-danger" @click="removingPreview = p">{{ t.previews.remove }}</button>
            </li>
          </ul>
        </div>
      </div>
    </template>

    <!-- Dialoge -->
    <AdminDialog v-if="rejecting" :title="t.queue.rejectTitle" size="sm" @close="rejecting = null">
      <form @submit.prevent="reject">
        <label class="label" for="rj-reason">{{ t.queue.reason }}</label>
        <select id="rj-reason" v-model="rejectPreset" class="field">
          <option v-for="p in presets" :key="p" :value="p">{{ p }}</option>
        </select>
        <label class="label mt-3" for="rj-text">{{ t.queue.message }}</label>
        <textarea id="rj-text" v-model="rejectText" class="field" rows="3" maxlength="200" />
        <div class="mt-4 flex justify-end gap-2">
          <button type="button" class="btn btn-ghost" @click="rejecting = null">{{ a.common.cancel }}</button>
          <button type="submit" class="btn btn-danger" :disabled="busy === rejecting.id">{{ t.queue.rejectTitle }}</button>
        </div>
      </form>
    </AdminDialog>

    <AdminDialog v-if="adding" :title="t.roster.add" size="sm" @close="adding = false">
      <form @submit.prevent="addCreator">
        <label class="label" for="cr-player">{{ t.roster.player }}</label>
        <input id="cr-player" v-model="addForm.player" class="field" maxlength="36" required autocomplete="off" spellcheck="false" />
        <label class="label mt-3" for="cr-tier">{{ t.roster.tier }}</label>
        <select id="cr-tier" v-model="addForm.tier" class="field">
          <option v-for="x in TIERS" :key="x" :value="x">{{ t.roster.tiers[x] }}</option>
        </select>
        <label class="label mt-3" for="cr-code">{{ t.roster.mainCode }}</label>
        <input id="cr-code" v-model="addForm.mainCode" class="field font-mono uppercase" maxlength="20" autocomplete="off" spellcheck="false" />
        <p class="mt-1 text-xs text-base-400">{{ t.roster.mainCodeHint }}</p>
        <div class="mt-4 flex justify-end gap-2">
          <button type="button" class="btn btn-ghost" @click="adding = false">{{ a.common.cancel }}</button>
          <button type="submit" class="btn btn-primary" :disabled="busy === 'add' || !addForm.player.trim()">{{ t.roster.add }}</button>
        </div>
      </form>
    </AdminDialog>

    <AdminDialog v-if="editing" :title="fill(t.roster.editTitle, { name: editing.name })" size="md" @close="editing = null">
      <form @submit.prevent="saveEdit">
        <div class="grid gap-3 sm:grid-cols-2">
          <div>
            <label class="label" for="ed-tier">{{ t.roster.tier }}</label>
            <select id="ed-tier" v-model="editForm.tier" class="field">
              <option v-for="x in TIERS" :key="x" :value="x">{{ t.roster.tiers[x] }}</option>
            </select>
          </div>
          <div>
            <label class="label" for="ed-code">{{ t.roster.mainCode }}</label>
            <input id="ed-code" v-model="editForm.mainCode" class="field font-mono uppercase" maxlength="20" autocomplete="off" spellcheck="false" />
          </div>
        </div>
        <h3 class="mt-5 font-semibold text-base-50">{{ t.roster.channels }}</h3>
        <div v-for="(ch, i) in editForm.channels" :key="i" class="mt-2 grid gap-2 sm:grid-cols-[8rem_1fr_7rem_auto]">
          <select v-model="ch.platform" class="field" :aria-label="t.roster.platform">
            <option v-for="p in PLATFORMS" :key="p" :value="p">{{ p }}</option>
          </select>
          <input v-model="ch.url" class="field" type="url" maxlength="300" :placeholder="t.roster.url" :aria-label="t.roster.url" />
          <input v-model.number="ch.followers" class="field" type="number" min="0" :aria-label="t.roster.followers" />
          <button type="button" class="btn btn-ghost" @click="editForm.channels.splice(i, 1)">{{ t.roster.remove }}</button>
        </div>
        <button v-if="editForm.channels.length < 10" type="button" class="btn btn-ghost mt-2" @click="editForm.channels.push({ platform: 'youtube', url: '', followers: 0 })">
          <SiteIcon name="plus" class="size-4" />{{ t.roster.channelAdd }}
        </button>
        <div class="mt-5 flex justify-end gap-2">
          <button type="button" class="btn btn-ghost" @click="editing = null">{{ a.common.cancel }}</button>
          <button type="submit" class="btn btn-primary" :disabled="busy === 'edit'">{{ a.common.save }}</button>
        </div>
      </form>
    </AdminDialog>

    <AdminDialog v-if="revoking" :title="fill(revoking.ban ? t.roster.banTitle : t.roster.revokeTitle, { name: revoking.c.name })" size="sm" @close="revoking = null">
      <form @submit.prevent="revoke">
        <label class="label" for="rv-reason">{{ t.roster.reasonLabel }}</label>
        <input id="rv-reason" v-model="revokeForm.reason" class="field" maxlength="300" required />
        <label class="mt-3 flex items-center gap-2 text-sm text-base-300">
          <input v-model="revokeForm.removeFanCapes" type="checkbox" class="adm-check mt-0" />{{ t.roster.removeFanCapes }}
        </label>
        <p class="mt-3 text-xs text-base-400">{{ t.roster.note }}</p>
        <div class="mt-4 flex justify-end gap-2">
          <button type="button" class="btn btn-ghost" @click="revoking = null">{{ a.common.cancel }}</button>
          <button type="submit" class="btn btn-danger" :disabled="busy === 'revoke' || !revokeForm.reason.trim()">{{ revoking.ban ? t.roster.ban : t.roster.revoke }}</button>
        </div>
      </form>
    </AdminDialog>

    <AdminDialog v-if="answering" :title="answering.status === 'accepted' ? t.requests.accept : t.requests.decline" size="sm" @close="answering = null">
      <form @submit.prevent="answer">
        <p v-if="answering.status === 'accepted' && answering.r.kind === 'upgrade'" class="mb-3 text-sm text-base-300">{{ t.requests.upgradeHint }}</p>
        <label class="label" for="rq-reason">{{ t.requests.reason }}</label>
        <textarea id="rq-reason" v-model="answerReason" class="field" rows="3" maxlength="300" />
        <div class="mt-4 flex justify-end gap-2">
          <button type="button" class="btn btn-ghost" @click="answering = null">{{ a.common.cancel }}</button>
          <button type="submit" class="btn" :class="answering.status === 'accepted' ? 'btn-primary' : 'btn-danger'" :disabled="busy === 'answer'">
            {{ answering.status === 'accepted' ? t.requests.accept : t.requests.decline }}
          </button>
        </div>
      </form>
    </AdminDialog>

    <AdminConfirm
      v-if="deleting"
      :title="fill(t.roster.deleteTitle, { name: deleting.name })"
      :text="t.roster.deleteConfirm"
      danger
      :busy="busy === 'delete'"
      @cancel="deleting = null"
      @confirm="removeCreator"
    />

    <AdminDialog v-if="editingWish" :title="fill(pt.answerTitle, { name: editingWish.creator.name })" size="sm" @close="editingWish = null">
      <form @submit.prevent="saveWish">
        <p class="mb-3 text-sm text-base-300"><strong class="text-base-50">{{ editingWish.species }}</strong> – {{ editingWish.description.slice(0, 160) }}</p>
        <label class="label" for="pw-status">{{ pt.status }}</label>
        <select id="pw-status" v-model="wishForm.status" class="field">
          <option v-for="x in PET_STATUSES" :key="x" :value="x">{{ pt.statuses[x] }}</option>
        </select>
        <label class="label mt-3" for="pw-reply">{{ pt.reply }}</label>
        <textarea id="pw-reply" v-model="wishForm.reply" class="field" rows="3" maxlength="500" />
        <template v-if="wishForm.status === 'done'">
          <label class="label mt-3" for="pw-pet">{{ pt.assignWith }}</label>
          <select id="pw-pet" v-model="wishForm.cosmeticId" class="field">
            <option value="">{{ pt.noPet }}</option>
            <option v-for="o in petOptions" :key="o.id" :value="o.id" :disabled="!!o.owner && o.owner.uuid !== editingWish.creator.uuid">
              {{ o.name }}{{ o.owner && o.owner.uuid !== editingWish.creator.uuid ? ` – ${fill(pt.ownedBy, { name: o.owner.name })}` : '' }}
            </option>
          </select>
        </template>
        <div class="mt-4 flex justify-end gap-2">
          <button type="button" class="btn btn-ghost" @click="editingWish = null">{{ a.common.cancel }}</button>
          <button type="submit" class="btn btn-primary" :disabled="busy === 'pet-wish'">{{ a.common.save }}</button>
        </div>
      </form>
    </AdminDialog>

    <AdminConfirm
      v-if="removingPet"
      :title="fill(pt.removeTitle, { pet: removingPet.pet.name, name: removingPet.creator.name })"
      :text="pt.removeConfirm"
      danger
      :busy="busy === 'pet-remove'"
      @cancel="removingPet = null"
      @confirm="removePet"
    />

    <AdminConfirm
      v-if="removingPreview"
      :title="t.previews.remove"
      :text="fill(t.previews.removeConfirm, { version: removingPreview.version })"
      danger
      :busy="busy === 'preview-rm'"
      @cancel="removingPreview = null"
      @confirm="removePreview"
    />
  </div>
</template>

<style scoped>
.wish-thumb {
  width: 4.5rem;
  height: 4.5rem;
  border-radius: 0.5rem;
  background: var(--color-base-950);
  object-fit: cover;
}
.pet-thumb {
  width: 3.5rem;
  height: 3.5rem;
  flex-shrink: 0;
  border-radius: 0.5rem;
  background: var(--color-base-950);
  object-fit: cover;
}
.assign-hits {
  position: absolute;
  inset-inline: 0;
  top: calc(100% + 0.25rem);
  z-index: 20;
  overflow: hidden;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-900);
  box-shadow: 0 12px 30px rgb(0 0 0 / 0.35);
}
.assign-hits button {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 0.5rem;
  padding: 0.45rem 0.7rem;
  font-size: 0.875rem;
  color: var(--color-base-100);
}
.assign-hits button:hover,
.assign-hits button:focus-visible {
  background: var(--color-base-800);
}
.profile-box {
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.8rem 0.95rem;
}
.profile-new {
  border-left: 3px solid var(--color-lamp-400);
}
.profile-label {
  font-size: 0.65rem;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--color-base-400);
}
</style>
