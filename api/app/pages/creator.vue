<script setup lang="ts">
import { WAIT_SONG_UUID } from '#shared/wait-song'
// Creator-Dashboard auf der Website (§35.3 + §35.11) mit der Website-Sitzung: Übersicht/Statistik, öffentliches Profil
// (Beschreibung + Links, Prüfung durch das Team), Codes, Fan-Symbol und Fan-Umhang, Creator-Packs, Stufe und Helfer.
// Helfer wählen oben den Creator, für den sie handeln (`?as=`). Team-Mitglieder mit `creators.manage` öffnen jedes
// Dashboard im Team-Modus (alle Rechte, jede Änderung protokolliert, §35.9) – Einstieg auch per `/creator?as=<uuid>`.
// Nur im Browser (routeRules), nie indexiert.
const { m, fill, date, lang } = useLang()
const lp = useLocalePath()
const { account, loaded, load, api, loginUrl } = useAccount()

// Persönliche Wartezeit (Wunsch des Owners): Lencraft151 sieht das Dashboard erst ab Freitag, 23.10.2026, 18:00 Uhr (MESZ).
// Dazu ein Musikvideo (WaitSongOverlay). Owner/Admins können es mit `?waitsong` ansehen (`&t=<s>` Start, `&still` Standbild).
const WAIT_UUID = WAIT_SONG_UUID
const WAIT_UNTIL = Date.parse('2026-10-23T18:00:00+02:00')
const now = ref(Date.now())
let waitTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => { waitTimer = setInterval(() => (now.value = Date.now()), 15_000) })
onBeforeUnmount(() => clearInterval(waitTimer))
const waiting = computed(() => account.value?.uuid.replace(/-/g, '').toLowerCase() === WAIT_UUID && now.value < WAIT_UNTIL)
const WAIT_MESSAGE = "Mein Lieber, da du so oft gefragt hast, kommt's für dich erst am Freitag um 18 Uhr."
const songOpen = ref(true)
const route = useRoute()
const songPreview = computed(() => {
  const team = account.value?.team
  if (route.query.waitsong === undefined || !team || !(team.owner || team.roles.some((r) => r.id === 'admin'))) return null
  const at = Number(route.query.t)
  return { at: Number.isFinite(at) && at > 0 ? at : undefined, still: route.query.still !== undefined }
})
const previewOpen = ref(true)
const d = computed(() => m.value.creatorDash)
const ct = computed(() => m.value.creators)

usePageSeo(() => ({ path: '/creator', title: `${d.value.title} – TRS Launcher`, description: d.value.lead, noindex: true }))

type Tab = 'overview' | 'profile' | 'codes' | 'uploads' | 'packs' | 'team' | 'pet'

const my = ref<MyCreatorState | null>(null)
const dash = ref<CreatorDashboard | null>(null)
const as = ref<string | null>(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const busy = ref('')
const tab = ref<Tab>('overview')

const helper = computed(() => !!dash.value?.viewer?.helper)
/** Team-Mitglied mit `creators.manage` (Owner automatisch) – darf jedes Creator-Dashboard öffnen. */
const teamManager = computed(() => {
  const t = account.value?.team
  return !!t && (t.owner || t.permissions.includes('creators.manage'))
})
const teamMode = computed(() => !!dash.value?.viewer?.team)
const tm = computed(() => d.value.teamMode)
const rights = computed(() => new Set(dash.value?.viewer?.rights ?? HELPER_RIGHTS))
const may = (r: HelperRight) => !helper.value || rights.value.has(r)
const tabs = computed<Tab[]>(() => {
  const list: Tab[] = ['overview']
  if (!helper.value) list.push('profile')
  if (may('codes')) list.push('codes')
  if (may('uploads')) list.push('uploads')
  if (may('packs')) list.push('packs')
  if (!helper.value) list.push('team')
  // Haustier (§35.13): der Creator immer, Helfer nur mit dem Recht `codes` (Wünsche bleiben beim Creator).
  if (!helper.value || may('codes')) list.push('pet')
  return list
})
const asQuery = computed(() => (as.value ? `as=${as.value}` : ''))
const withAs = (path: string, extra = '') => {
  const q = [extra, asQuery.value].filter(Boolean).join('&')
  return q ? `${path}?${q}` : path
}
const fail = (e: unknown) => {
  const code = apiCode(e)
  error.value = fill(d.value.failed, { error: code === 'rate_limited' ? d.value.uploads.limit : apiMessage(e) })
}

async function loadDash() {
  dash.value = await api<CreatorDashboard>(withAs('/v1/creator/dashboard'))
  resetProfileForm()
  if (!tabs.value.includes(tab.value)) tab.value = 'overview'
}
async function refresh() {
  loading.value = true
  error.value = ''
  try {
    my.value = await api<MyCreatorState>('/v1/me/creator')
    const own = my.value.creator?.state === 'active'
    if (!own && !as.value && my.value.helping.length) as.value = my.value.helping[0]!.creator.uuid
    if (as.value && as.value === account.value?.uuid.replace(/-/g, '').toLowerCase()) as.value = null
    if (own || as.value) await loadDash()
    else dash.value = null
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  if (!(await load())) {
    loading.value = false
    return
  }
  // Einstieg aus dem Team-Bereich: /creator?as=<uuid> (der Server prüft das Recht ohnehin).
  const q = typeof route.query.as === 'string' ? route.query.as.replace(/-/g, '').toLowerCase() : ''
  if (/^[0-9a-f]{32}$/.test(q)) as.value = q
  await refresh()
})
async function switchTo(uuid: string | null) {
  as.value = uuid
  tab.value = 'overview'
  notice.value = ''
  teamQuery.value = ''
  teamHits.value = []
  void navigateTo({ query: { ...route.query, as: uuid ?? undefined } }, { replace: true })
  await refresh()
}

// Team-Modus: jeden Creator per Suche öffnen (Liste wie im Team-Bereich, nur aktive Creator).
interface TeamCreatorHit { uuid: string, name: string, tier: CreatorTier, mainCode: string | null }
const teamQuery = ref('')
const teamHits = ref<TeamCreatorHit[]>([])
const teamSearched = ref(false)
let teamTimer: ReturnType<typeof setTimeout> | undefined
watch(teamQuery, (v) => {
  clearTimeout(teamTimer)
  teamSearched.value = false
  if (!v.trim()) {
    teamHits.value = []
    return
  }
  teamTimer = setTimeout(async () => {
    try {
      teamHits.value = (await api<{ creators: TeamCreatorHit[] }>(`/v1/admin/creators?state=active&q=${encodeURIComponent(v.trim().slice(0, 32))}`)).creators.slice(0, 8)
      teamSearched.value = true
    } catch (e) {
      fail(e)
    }
  }, 250)
})
const teamName = computed(() => (teamMode.value ? dash.value?.name ?? '' : ''))
const teamChanges = computed(() => dash.value?.teamChanges ?? [])

async function act(key: string, fn: () => Promise<void>, ok?: string) {
  busy.value = key
  error.value = ''
  notice.value = ''
  try {
    await fn()
    if (ok) notice.value = ok
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

// ---------------------------------------------------------------- Übersicht
const statCards = computed(() => {
  const s = dash.value?.stats
  if (!s) return []
  return [
    { label: d.value.stats.activeFans, value: s.activeFans },
    { label: d.value.stats.redemptions, value: s.redemptions },
    { label: d.value.stats.fanCapeHolders, value: s.fanCapeHolders, hint: capeShare.value },
    { label: d.value.stats.newPlayers, value: s.newPlayers },
    { label: d.value.stats.packInstalls, value: s.packInstalls },
  ]
})
const num = (n: number) => new Intl.NumberFormat(lang.value === 'en' ? 'en-GB' : lang.value).format(n)
const capeShare = computed(() => {
  const s = dash.value?.stats
  if (!s || !s.activeFans) return null
  // Ältere API ohne fanCapeFans: höchstens alle Fans.
  const held = Math.min(s.activeFans, s.fanCapeFans ?? s.fanCapeHolders)
  return fill(d.value.stats.capeShare, { held: num(held), fans: num(s.activeFans), p: Math.round((held / s.activeFans) * 100) })
})

// ---------------------------------------------------------------- Profil
interface LinkRow { type: ProfileLinkType, url: string }
const profileForm = reactive<{ description: string, links: LinkRow[] }>({ description: '', links: [] })
function resetProfileForm() {
  const p = dash.value?.profile
  const src = p?.pending ?? p?.approved
  profileForm.description = src?.description ?? ''
  profileForm.links = (src?.links ?? []).map((l) => ({ ...l }))
}
function resetToLive() {
  const a = dash.value?.profile?.approved
  profileForm.description = a?.description ?? ''
  profileForm.links = (a?.links ?? []).map((l) => ({ ...l }))
}
const descLength = computed(() => profileForm.description.replace(/\r\n?/g, '\n').trim().length)
const linkProblems = computed(() =>
  profileForm.links.map((l, i) => {
    if (!l.url.trim()) return d.value.profile.problems.invalid_url!
    const r = checkProfileLink(l.type, l.url)
    if ('problem' in r) return d.value.profile.problems[r.problem] ?? r.problem
    const dup = profileForm.links.findIndex((x, j) => j < i && x.url.trim().toLowerCase() === l.url.trim().toLowerCase())
    return dup >= 0 ? d.value.profile.problems.duplicate! : ''
  }),
)
const profileValid = computed(() => descLength.value <= PROFILE_DESCRIPTION_MAX && linkProblems.value.every((p) => !p))
const previewBlocks = computed(() => descriptionBlocks(profileForm.description.replace(/\r\n?/g, '\n')))
function addLink() {
  const used = new Set(profileForm.links.map((l) => l.type))
  profileForm.links.push({ type: PROFILE_LINK_TYPES.find((t) => !used.has(t)) ?? 'website', url: '' })
}
async function submitProfile() {
  await act('profile', async () => {
    const before = JSON.stringify(dash.value?.profile ?? null)
    const state = await api<ProfileState>(withAs('/v1/creator/profile'), {
      method: 'PUT',
      body: { description: profileForm.description, links: profileForm.links.map((l) => ({ type: l.type, url: l.url.trim() })) },
    })
    if (dash.value) dash.value.profile = state
    notice.value = JSON.stringify(state) === before ? d.value.profile.unchanged : d.value.profile.submitted
    resetProfileForm()
  })
}
async function withdrawProfile() {
  await act('profile-withdraw', async () => {
    await api(withAs('/v1/creator/profile/pending'), { method: 'DELETE' })
    await loadDash()
  })
}

// ---------------------------------------------------------------- Codes
const codeForm = reactive({ code: '', maxUses: '', expiresAt: '' })
const extras = computed(() => dash.value?.codes.filter((c) => c.kind === 'extra') ?? [])
const activeExtras = computed(() => extras.value.filter((c) => c.active).length)
const copied = ref('')
async function copyCode(code: string) {
  try {
    await navigator.clipboard.writeText(code)
    copied.value = code
    setTimeout(() => (copied.value = ''), 1500)
  } catch {
    // Zwischenablage gesperrt: nichts tun.
  }
}
async function createCode() {
  await act('code', async () => {
    const body: Record<string, unknown> = { code: codeForm.code.trim() }
    if (codeForm.maxUses.trim()) body.maxUses = Math.max(1, Math.round(Number(codeForm.maxUses)))
    if (codeForm.expiresAt) body.expiresAt = new Date(`${codeForm.expiresAt}T23:59:59`).toISOString()
    const r = await api<{ codes: CreatorCode[] }>(withAs('/v1/creator/codes'), { method: 'POST', body })
    if (dash.value) dash.value.codes = r.codes
    Object.assign(codeForm, { code: '', maxUses: '', expiresAt: '' })
  }, d.value.saved)
}
async function toggleCode(c: CreatorCode) {
  await act(`code:${c.code}`, async () => {
    const r = await api<{ codes: CreatorCode[] }>(withAs(`/v1/creator/codes/${encodeURIComponent(c.code)}`), { method: 'PATCH', body: { active: !c.active } })
    if (dash.value) dash.value.codes = r.codes
  })
}
const expired = (c: CreatorCode) => !!c.expiresAt && Date.parse(c.expiresAt) <= Date.now()

// ---------------------------------------------------------------- Uploads (roh als PNG, wie der Launcher)
const symbolFile = ref<File | null>(null)
const symbolFrameMs = ref(150)
const capeFile = ref<File | null>(null)
const capeForm = reactive({ name: '', frames: 1, frameTimeMs: 100 })
const pick = (e: Event) => (e.target as HTMLInputElement).files?.[0] ?? null
async function uploadPng(path: string, file: File) {
  await apiFetch(path, {
    method: 'PUT',
    body: file,
    headers: { 'Content-Type': 'image/png', 'X-CSRF-Token': account.value?.csrf ?? '' },
    credentials: 'same-origin',
  })
}
async function uploadSymbol() {
  const f = symbolFile.value
  if (!f) return
  await act('symbol', async () => {
    await uploadPng(withAs('/v1/creator/symbol', `frameMs=${Math.min(1000, Math.max(50, Math.round(symbolFrameMs.value)))}`), f)
    symbolFile.value = null
    await loadDash()
  }, d.value.uploads.uploaded)
}
async function uploadCape() {
  const f = capeFile.value
  if (!f) return
  await act('cape', async () => {
    const q = new URLSearchParams()
    if (capeForm.name.trim()) q.set('name', capeForm.name.trim().slice(0, 32))
    const frames = Math.min(32, Math.max(1, Math.round(capeForm.frames)))
    q.set('frames', String(frames))
    if (frames > 1) q.set('frameTimeMs', String(Math.min(10_000, Math.max(50, Math.round(capeForm.frameTimeMs)))))
    await uploadPng(withAs('/v1/creator/fan-cape', q.toString()), f)
    capeFile.value = null
    await loadDash()
  }, d.value.uploads.uploaded)
}
async function withdraw(kind: 'symbol' | 'fan-cape') {
  await act(`withdraw:${kind}`, async () => {
    await api(withAs(`/v1/creator/${kind}/pending`), { method: 'DELETE' })
    await loadDash()
  })
}

// ---------------------------------------------------------------- Packs
async function setListed(code: string, listed: boolean) {
  await act(`pack:${code}`, async () => {
    const r = await api<{ packs: CreatorDashboard['packs'] }>(withAs(`/v1/creator/packs/${encodeURIComponent(code)}`), { method: 'PUT', body: { listed } })
    if (dash.value) dash.value.packs = r.packs
  })
}

// ---------------------------------------------------------------- Stufe, Anfragen, Helfer
const HELPER_LIMITS: Record<CreatorTier, number> = { starter: 1, partner: 3, top: 10 }
const upgradeForm = reactive({ channelUrl: '', followers: '', note: '' })
const openUpgrade = computed(() => dash.value?.requests.some((r) => r.kind === 'upgrade' && r.status === 'open') ?? false)
async function requestUpgrade() {
  await act('upgrade', async () => {
    const body: Record<string, unknown> = { kind: 'upgrade', channelUrl: upgradeForm.channelUrl.trim(), followers: Math.max(0, Math.round(Number(upgradeForm.followers) || 0)) }
    if (upgradeForm.note.trim()) body.note = upgradeForm.note.trim().slice(0, 500)
    const r = await api<{ requests: CreatorDashboard['requests'] }>(withAs('/v1/creator/requests'), { method: 'POST', body })
    if (dash.value) dash.value.requests = r.requests
    Object.assign(upgradeForm, { channelUrl: '', followers: '', note: '' })
  }, d.value.team.sent)
}
async function setHelperRight(h: NonNullable<CreatorDashboard['helpers']>[number], right: HelperRight, on: boolean) {
  const next = on ? [...new Set([...h.rights, right])] : h.rights.filter((r) => r !== right)
  if (!next.length) return
  await act(`helper:${h.uuid}`, async () => {
    const r = await api<{ helpers: CreatorDashboard['helpers'] }>(withAs(`/v1/creator/helpers/${h.uuid}`), { method: 'PATCH', body: { rights: next } })
    if (dash.value) dash.value.helpers = r.helpers
  })
}
async function removeHelper(uuid: string) {
  await act(`helper:${uuid}`, async () => {
    await api(withAs(`/v1/creator/helpers/${uuid}`), { method: 'DELETE' })
    await loadDash()
  })
}
const limitsText = computed(() => {
  const x = dash.value
  if (!x) return ''
  return fill(d.value.team.limits, {
    codes: x.limits.extraCodes === null ? d.value.team.unlimited : x.limits.extraCodes,
    frames: x.limits.symbolFrames,
    cape: d.value.team.capeKinds[x.limits.fanCape || 'none'] ?? '',
    helpers: HELPER_LIMITS[x.tier],
  })
})
const helpingName = computed(() => my.value?.helping.find((h) => h.creator.uuid === as.value)?.creator.name ?? '')

// ---------------------------------------------------------------- Haustier (§35.13)
const petState = ref<CreatorPetState | null>(null)
const petForm = reactive({ count: 1, maxUses: 1, expiresAt: '', note: '' })
const petCreated = ref<string[]>([])
const petCopied = ref(false)
const wishForm = reactive({ species: '', description: '', colors: '' })
const wishFile = ref<File | null>(null)
const wishFileError = ref('')
async function loadPet() {
  petState.value = await api<CreatorPetState>(withAs('/v1/creator/pet'))
}
watch(tab, (v) => {
  if (v === 'pet') void act('pet-load', loadPet)
})
watch(as, () => (petState.value = null))
const clampInt = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, Math.round(Number(v) || lo)))
const petWant = computed(() => clampInt(petForm.count, 1, 20) * clampInt(petForm.maxUses, 1, 100))
const petOver = computed(() => !!petState.value && petWant.value > petState.value.quota.remaining)
const monthName = (month: string) => {
  const d = new Date(`${month}-01T00:00:00Z`)
  return Number.isNaN(d.getTime()) ? month : d.toLocaleDateString(lang.value === 'en' ? 'en-GB' : lang.value, { month: 'long', year: 'numeric', timeZone: 'UTC' })
}
const petCodeState = (c: PetCode) => (c.revokedAt ? 'revoked' : c.expiresAt && Date.parse(c.expiresAt) <= Date.now() ? 'expired' : c.uses >= c.maxUses ? 'used' : 'ok')
async function createPetCodes() {
  await act('pet-codes', async () => {
    const body: Record<string, unknown> = { count: clampInt(petForm.count, 1, 20), maxUses: clampInt(petForm.maxUses, 1, 100) }
    if (petForm.expiresAt) body.expiresAt = new Date(`${petForm.expiresAt}T23:59:59`).toISOString()
    if (petForm.note.trim()) body.note = petForm.note.trim().slice(0, 100)
    const r = await api<{ codes: (PetCode & { code: string })[], quota: PetQuota }>(withAs('/v1/creator/pet/codes'), { method: 'POST', body })
    petCreated.value = r.codes.map((c) => c.code)
    petCopied.value = false
    Object.assign(petForm, { count: 1, maxUses: 1, expiresAt: '', note: '' })
    await loadPet()
  })
}
async function copyPetCodes() {
  try {
    await navigator.clipboard.writeText(petCreated.value.join('\n'))
    petCopied.value = true
  } catch {
    // Zwischenablage gesperrt: Codes stehen markierbar im Feld.
  }
}
async function revokePetCode(id: number) {
  await act(`pet-code:${id}`, async () => {
    const r = await api<{ codes: PetCode[], quota: PetQuota }>(withAs(`/v1/creator/pet/codes/${id}`), { method: 'DELETE' })
    if (petState.value) Object.assign(petState.value, { codes: r.codes, quota: r.quota })
  })
}
function checkImage(f: File | null): string {
  if (!f) return ''
  if (f.type !== 'image/png' && f.type !== 'image/jpeg') return d.value.pet.imageType
  return f.size > PET_IMAGE_MAX_BYTES ? d.value.pet.imageTooBig : ''
}
function pickWishFile(e: Event) {
  const f = pick(e)
  wishFileError.value = checkImage(f)
  wishFile.value = wishFileError.value ? null : f
}
async function putWishImage(id: number, f: File) {
  await apiFetch(withAs(`/v1/creator/pet/requests/${id}/image`), {
    method: 'PUT',
    body: f,
    headers: { 'Content-Type': f.type, 'X-CSRF-Token': account.value?.csrf ?? '' },
    credentials: 'same-origin',
  })
}
async function sendWish() {
  await act('pet-wish', async () => {
    const body: Record<string, unknown> = { species: wishForm.species.trim().slice(0, 60), description: wishForm.description.trim().slice(0, 1000) }
    if (wishForm.colors.trim()) body.colors = wishForm.colors.trim().slice(0, 200)
    const r = await api<{ requests: PetRequest[] }>(withAs('/v1/creator/pet/requests'), { method: 'POST', body })
    const f = wishFile.value
    if (f && r.requests[0]) await putWishImage(r.requests[0].id, f)
    Object.assign(wishForm, { species: '', description: '', colors: '' })
    wishFile.value = null
    await loadPet()
  }, d.value.pet.sent)
}
async function addWishImage(id: number, e: Event) {
  const f = pick(e)
  const problem = checkImage(f)
  if (problem) {
    error.value = problem
    return
  }
  if (!f) return
  await act(`pet-img:${id}`, async () => {
    await putWishImage(id, f)
    await loadPet()
  })
}
async function removeWishImage(id: number) {
  await act(`pet-img:${id}`, async () => {
    await api(withAs(`/v1/creator/pet/requests/${id}/image`), { method: 'DELETE' })
    await loadPet()
  })
}
async function withdrawWish(id: number) {
  await act(`pet-wish:${id}`, async () => {
    await api(withAs(`/v1/creator/pet/requests/${id}`), { method: 'DELETE' })
    await loadPet()
  })
}
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 pt-14 sm:px-6">
    <WaitSongOverlay
      v-if="songPreview && previewOpen && !waiting"
      :name="account!.name"
      :skin="account!.skin"
      :message="WAIT_MESSAGE"
      until="Freitag, 23.10.2026, 18:00 Uhr"
      :preview="songPreview"
      @close="previewOpen = false"
    />
    <header class="flex flex-wrap items-end gap-4">
      <div class="min-w-0 flex-1">
        <p class="text-xs font-semibold tracking-[0.2em] text-redstone-400 uppercase">{{ ct.eyebrow }}</p>
        <h1 class="display mt-2 text-5xl leading-tight text-base-50">{{ d.title }}</h1>
        <p class="mt-3 text-lg text-base-400">{{ d.lead }}</p>
      </div>
      <NuxtLink v-if="dash" :to="lp(creatorPath(dash.name))" class="btn btn-ghost"><SiteIcon name="external" class="size-4" />{{ d.publicPage }}</NuxtLink>
    </header>

    <div v-if="!loaded || loading" class="mt-8 space-y-3"><div v-for="i in 3" :key="i" class="skeleton h-24 rounded-xl" /></div>

    <div v-else-if="!account" class="card mt-8 p-6">
      <p class="text-base-300">{{ d.signIn }}</p>
      <a :href="loginUrl('/creator')" class="btn btn-primary mt-4"><SiteIcon name="user" class="size-4" />{{ d.signInButton }}</a>
    </div>

    <div v-else-if="waiting" class="card mt-8 p-6">
      <WaitSongOverlay v-if="songOpen" :name="account!.name" :skin="account!.skin" :message="WAIT_MESSAGE" until="Freitag, 23.10.2026, 18:00 Uhr" @close="songOpen = false" />
      <template v-else>
        <p class="text-lg font-semibold text-base-50">{{ WAIT_MESSAGE }}</p>
        <p class="mt-2 text-sm text-base-400">Freischaltung: Freitag, 23.10.2026, 18:00 Uhr</p>
        <button type="button" class="btn btn-ghost mt-4" @click="songOpen = true"><SiteIcon name="play" class="size-4" />Lied nochmal ansehen</button>
      </template>
    </div>

    <template v-else>
      <p v-if="error" role="alert" class="mt-6 text-sm text-redstone-300">{{ error }}</p>
      <p v-if="notice" role="status" class="mt-6 text-sm text-ok">{{ notice }}</p>

      <!-- Kein (aktiver) Creator und kein Helfer -->
      <div v-if="!dash" class="card mt-8 p-6">
        <h2 class="heading text-2xl text-base-50">{{ d.notCreatorTitle }}</h2>
        <p v-if="my?.creator && my.creator.state !== 'active'" class="mt-2 text-redstone-300">{{ fill(d.inactive, { reason: my.creator.reason ?? '' }) }}</p>
        <p class="mt-2 text-base-400">{{ d.notCreator }}</p>
        <p v-if="my?.application" class="mt-2 text-sm text-base-300">{{ fill(d.application, { status: my.application.status }) }}</p>
        <NuxtLink :to="lp('/team/creator')" class="btn btn-primary mt-5"><SiteIcon name="star" class="size-4" />{{ d.apply }}</NuxtLink>
      </div>

      <template v-else>
        <!-- Für wen? (eigenes Dashboard oder als Helfer) -->
        <div v-if="my && (my.helping.length || teamManager)" class="mt-6 flex flex-wrap items-center gap-2">
          <label for="dash-as" class="text-sm text-base-400">{{ d.actingAs }}</label>
          <select id="dash-as" class="field w-auto" :value="as ?? ''" @change="switchTo(($event.target as HTMLSelectElement).value || null)">
            <option v-if="my.creator?.state === 'active'" value="">{{ d.mine }}</option>
            <option v-else-if="!as" value="" disabled>{{ tm.choose }}</option>
            <option v-for="h in my.helping" :key="h.creator.uuid" :value="h.creator.uuid">{{ h.creator.name }}</option>
            <option v-if="teamMode && as && !my.helping.some((h) => h.creator.uuid === as)" :value="as">{{ teamName }}</option>
          </select>
          <span v-if="helper" class="text-xs text-base-400">{{ fill(d.helperNote, { name: helpingName, rights: [...rights].map((r) => d.team.rights[r] ?? r).join(', ') }) }}</span>
          <div v-if="teamManager" class="team-pick relative">
            <input v-model="teamQuery" type="search" class="field w-64" maxlength="32" :placeholder="tm.search" :aria-label="tm.search" autocomplete="off" />
            <ul v-if="teamHits.length" class="team-hits card" role="listbox" :aria-label="tm.pick">
              <li v-for="c in teamHits" :key="c.uuid">
                <button type="button" class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-base-850" @click="switchTo(c.uuid)">
                  <PlayerHead :uuid="c.uuid" :name="c.name" :size="20" />
                  <span class="flex-1 text-base-50">{{ c.name }}</span>
                  <span class="text-xs text-base-400">{{ ct.tier[c.tier] }}</span>
                </button>
              </li>
            </ul>
            <p v-else-if="teamSearched" class="team-hits card px-3 py-2 text-sm text-base-400">{{ tm.none }}</p>
          </div>
        </div>
        <p v-if="teamMode" class="team-banner mt-4" role="status">
          <SiteIcon name="shield" class="size-5 shrink-0" />
          <span>{{ tm.banner }}</span>
        </p>

        <section class="card mt-6 flex flex-wrap items-center gap-5 p-5">
          <CreatorAvatar :uuid="dash.uuid" :name="dash.name" :skin="dash.skin ?? null" :tier="dash.tier" :size="64" />
          <div class="min-w-0 flex-1">
            <p class="text-2xl font-bold text-base-50">{{ dash.name }}</p>
            <p class="text-xs font-bold tracking-[0.16em] text-redstone-400 uppercase">{{ ct.tier[dash.tier] }}</p>
            <p class="mt-1 text-xs text-base-400">
              {{ fill(ct.since, { date: date(dash.since) }) }}
              <template v-if="dash.milestone"> · {{ fill(d.milestone, { name: ct.milestones[dash.milestone] ?? dash.milestone }) }}</template>
              <template v-if="dash.nextMilestone"> · {{ fill(d.nextMilestone, { name: ct.milestones[dash.nextMilestone.name] ?? dash.nextMilestone.name, n: num(dash.nextMilestone.at) }) }}</template>
            </p>
          </div>
          <CreatorSymbol v-if="dash.symbol.current" :url="dash.symbol.current.url" :frames="dash.symbol.current.frames" :frame-ms="dash.symbol.current.frameMs" :px="40" :label="ct.fanSymbol" />
        </section>

        <nav class="dash-tabs mt-6" :aria-label="d.title">
          <button v-for="x in tabs" :key="x" type="button" :aria-pressed="tab === x" @click="tab = x; notice = ''">{{ d.tabs[x] }}</button>
        </nav>

        <!-- Übersicht -->
        <section v-if="tab === 'overview'" class="mt-6">
          <div v-if="teamChanges.length" class="card team-changes mb-4 p-4">
            <p class="text-sm font-semibold text-base-100">{{ tm.changesTitle }}</p>
            <p class="mt-0.5 text-xs text-base-400">{{ tm.changesLead }}</p>
            <ul class="mt-2 space-y-1 text-sm">
              <li v-for="(c, i) in teamChanges.slice(0, 8)" :key="i" class="flex flex-wrap gap-x-2">
                <span class="text-base-100">{{ tm.actions[c.action] ?? c.action }}</span>
                <span class="text-xs text-base-400">{{ date(c.at) }}</span>
              </li>
            </ul>
          </div>
          <p v-if="!dash.stats" class="card p-5 text-base-400">{{ d.stats.hidden }}</p>
          <template v-else>
            <ul class="grid grid-cols-2 gap-3 md:grid-cols-5">
              <li v-for="s in statCards" :key="s.label" class="card p-4">
                <p class="text-2xl font-bold text-base-50 tabular-nums">{{ num(s.value) }}</p>
                <p class="mt-1 text-xs text-base-400">{{ s.label }}</p>
                <p v-if="s.hint" class="mt-1 text-[11px] leading-snug text-base-400">{{ s.hint }}</p>
              </li>
            </ul>
            <div class="mt-4 grid gap-4 md:grid-cols-2">
              <div class="card p-4">
                <p class="text-sm font-semibold text-base-200">{{ d.stats.newFans }} · {{ d.stats.daily }}</p>
                <AdminChart class="mt-2" :values="dash.stats.daily.map((x) => x.newFans)" :days="dash.stats.daily.map((x) => x.day)" :label="d.stats.newFans" />
              </div>
              <div class="card p-4">
                <p class="text-sm font-semibold text-base-200">{{ d.stats.redemptionsDaily }} · {{ d.stats.daily }}</p>
                <AdminChart class="mt-2" :values="dash.stats.daily.map((x) => x.redemptions)" :days="dash.stats.daily.map((x) => x.day)" :label="d.stats.redemptionsDaily" />
              </div>
            </div>
          </template>
        </section>

        <!-- Profil -->
        <section v-else-if="tab === 'profile'" class="mt-6 grid gap-6 lg:grid-cols-[1fr_20rem]">
          <form class="card p-5" @submit.prevent="submitProfile">
            <h2 class="section-title">{{ d.profile.title }}</h2>
            <p class="mt-1 text-sm text-base-400">{{ d.profile.lead }}</p>

            <div v-if="dash.profile" class="mt-4 space-y-2 text-sm">
              <p v-if="dash.profile.pending" class="state state-warn">
                <SiteIcon name="clock" class="size-4" />{{ fill(d.profile.pending, { date: date(dash.profile.pending.at) }) }}
                <button type="button" class="ml-auto text-xs underline" :disabled="busy === 'profile-withdraw'" @click="withdrawProfile">{{ d.profile.withdraw }}</button>
              </p>
              <p v-if="dash.profile.rejected" class="state state-danger"><SiteIcon name="warn" class="size-4" />{{ fill(d.profile.rejected, { reason: dash.profile.rejected.reason ?? '' }) }}</p>
              <p v-if="dash.profile.approved" class="state state-ok"><SiteIcon name="check" class="size-4" />{{ fill(d.profile.live, { date: date(dash.profile.approved.at) }) }}</p>
              <p v-else class="state"><SiteIcon name="note" class="size-4" />{{ d.profile.nothingLive }}</p>
            </div>

            <label class="label mt-5" for="pf-desc">{{ d.profile.description }}</label>
            <textarea id="pf-desc" v-model="profileForm.description" class="field" rows="9" :maxlength="PROFILE_DESCRIPTION_MAX + 200" :aria-describedby="'pf-desc-hint'" />
            <p id="pf-desc-hint" class="mt-1 flex gap-3 text-xs text-base-400">
              <span class="flex-1">{{ d.profile.descriptionHint }}</span>
              <span :class="descLength > PROFILE_DESCRIPTION_MAX ? 'text-redstone-300' : ''" class="tabular-nums">{{ fill(d.profile.counter, { n: descLength, max: PROFILE_DESCRIPTION_MAX }) }}</span>
            </p>

            <h3 class="mt-5 text-sm font-semibold text-base-200">{{ d.profile.links }} <span class="font-normal text-base-400">({{ profileForm.links.length }}/{{ PROFILE_MAX_LINKS }})</span></h3>
            <div v-for="(l, i) in profileForm.links" :key="i" class="mt-2">
              <div class="grid gap-2 sm:grid-cols-[9rem_1fr_auto]">
                <select v-model="l.type" class="field" :aria-label="d.profile.type">
                  <option v-for="t in PROFILE_LINK_TYPES" :key="t" :value="t">{{ ct.linkNames[t] }}</option>
                </select>
                <input v-model="l.url" class="field" type="url" inputmode="url" maxlength="300" :placeholder="d.profile.url" :aria-label="d.profile.url" :aria-invalid="!!linkProblems[i]" spellcheck="false" />
                <button type="button" class="btn btn-ghost" @click="profileForm.links.splice(i, 1)">{{ d.profile.remove }}</button>
              </div>
              <p v-if="linkProblems[i] && l.url.trim()" class="mt-1 text-xs text-redstone-300">{{ linkProblems[i] }}</p>
            </div>
            <button v-if="profileForm.links.length < PROFILE_MAX_LINKS" type="button" class="btn btn-ghost mt-2" @click="addLink"><SiteIcon name="plus" class="size-4" />{{ d.profile.addLink }}</button>

            <div class="mt-6 flex flex-wrap gap-2">
              <button type="submit" class="btn btn-primary" :disabled="busy === 'profile' || !profileValid">{{ d.profile.submit }}</button>
              <button v-if="dash.profile?.approved" type="button" class="btn btn-ghost" @click="resetToLive">{{ d.profile.reset }}</button>
            </div>
          </form>

          <aside class="card h-fit p-5">
            <p class="text-[11px] font-semibold tracking-[0.16em] text-base-400 uppercase">{{ d.profile.preview }}</p>
            <div class="preview mt-3">
              <template v-for="(b, i) in previewBlocks" :key="i">
                <p v-if="b.kind === 'p'" class="whitespace-pre-line">{{ b.text }}</p>
                <ul v-else><li v-for="(item, j) in b.items" :key="j">{{ item }}</li></ul>
              </template>
              <p v-if="!previewBlocks.length" class="text-base-400 italic">{{ fill(ct.noDescription, { name: dash.name }) }}</p>
            </div>
            <ul v-if="profileForm.links.length" class="mt-4 flex flex-wrap gap-1.5">
              <li v-for="(l, i) in profileForm.links" :key="i" class="chip" :class="{ bad: !!linkProblems[i] }"><SiteIcon :name="LINK_ICONS[l.type]" class="size-3.5" />{{ ct.linkNames[l.type] }}</li>
            </ul>
          </aside>
        </section>

        <!-- Codes -->
        <section v-else-if="tab === 'codes'" class="mt-6 grid gap-6 lg:grid-cols-[1fr_20rem]">
          <div class="card p-5">
            <h2 class="section-title">{{ d.codes.title }}</h2>
            <p class="mt-1 text-sm text-base-400">{{ d.codes.lead }}</p>
            <ul class="mt-4 space-y-2">
              <li v-for="c in dash.codes" :key="c.code" class="code-row" :data-off="!c.active || expired(c)">
                <div class="min-w-0 flex-1">
                  <p class="flex flex-wrap items-center gap-2">
                    <code class="text-lg font-bold text-lamp-300">{{ c.code }}</code>
                    <span class="text-xs text-base-400">{{ c.kind === 'main' ? d.codes.main : d.codes.extra }}</span>
                    <span v-if="expired(c)" class="pill">{{ d.codes.expired }}</span>
                    <span v-else-if="!c.active" class="pill">{{ d.codes.off }}</span>
                  </p>
                  <p class="mt-0.5 text-xs text-base-400">
                    {{ c.maxUses === null ? fill(d.codes.uses, { n: num(c.uses) }) : fill(d.codes.usesMax, { n: num(c.uses), max: num(c.maxUses) }) }}
                    <template v-if="c.expiresAt"> · {{ fill(d.codes.expires, { date: date(c.expiresAt) }) }}</template>
                    <template v-if="c.kind === 'main'"> · {{ d.codes.mainHint }}</template>
                  </p>
                </div>
                <button type="button" class="btn btn-ghost" @click="copyCode(c.code)"><SiteIcon :name="copied === c.code ? 'check' : 'copy'" class="size-4" />{{ copied === c.code ? d.codes.copied : d.codes.copy }}</button>
                <button v-if="c.kind === 'extra'" type="button" class="btn btn-ghost" :disabled="busy === `code:${c.code}`" @click="toggleCode(c)">{{ c.active ? d.codes.switchOff : d.codes.switchOn }}</button>
              </li>
            </ul>
          </div>
          <form class="card h-fit p-5" @submit.prevent="createCode">
            <h2 class="section-title">{{ d.codes.create }}</h2>
            <p class="mt-1 text-xs text-base-400">{{ dash.limits.extraCodes === null ? d.codes.unlimited : fill(d.codes.limit, { n: activeExtras, max: dash.limits.extraCodes }) }}</p>
            <label class="label mt-4" for="cd-code">{{ d.codes.code }}</label>
            <input id="cd-code" v-model="codeForm.code" class="field font-mono uppercase" maxlength="20" required pattern="[A-Za-z0-9][A-Za-z0-9\-]{3,18}[A-Za-z0-9]" autocomplete="off" spellcheck="false" />
            <p class="mt-1 text-xs text-base-400">{{ d.codes.codeHint }}</p>
            <label class="label mt-3" for="cd-max">{{ d.codes.maxUses }}</label>
            <input id="cd-max" v-model="codeForm.maxUses" class="field" type="number" min="1" :max="dash.limits.maxUsesPerCode ?? undefined" />
            <label class="label mt-3" for="cd-exp">{{ d.codes.expiresAt }}</label>
            <input id="cd-exp" v-model="codeForm.expiresAt" class="field" type="date" />
            <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'code' || (dash.limits.extraCodes !== null && activeExtras >= dash.limits.extraCodes)">{{ d.codes.create }}</button>
          </form>
        </section>

        <!-- Fan-Symbol + Fan-Umhang -->
        <section v-else-if="tab === 'uploads'" class="mt-6 grid gap-6 md:grid-cols-2">
          <div class="card p-5">
            <h2 class="section-title">{{ d.uploads.symbolTitle }}</h2>
            <p class="mt-1 text-sm text-base-400">{{ d.uploads.symbolLead }}</p>
            <div class="mt-4 grid grid-cols-2 gap-3">
              <div class="upload-slot">
                <p class="slot-label">{{ d.uploads.current }}</p>
                <CreatorSymbol v-if="dash.symbol.current" :url="sameOriginPath(dash.symbol.current.url)" :frames="dash.symbol.current.frames" :frame-ms="dash.symbol.current.frameMs" :px="64" />
                <p v-else class="text-xs text-base-400">{{ d.uploads.none }}</p>
              </div>
              <div class="upload-slot">
                <p class="slot-label">{{ d.uploads.pending }}</p>
                <template v-if="dash.symbol.pending">
                  <CreatorSymbol :url="sameOriginPath(dash.symbol.pending.url)" :frames="dash.symbol.pending.frames" :frame-ms="dash.symbol.pending.frameMs" :px="64" />
                  <button type="button" class="mt-2 text-xs underline" :disabled="busy === 'withdraw:symbol'" @click="withdraw('symbol')">{{ d.uploads.withdraw }}</button>
                </template>
                <p v-else class="text-xs text-base-400">–</p>
              </div>
            </div>
            <p v-if="dash.symbol.rejected" class="state state-danger mt-3 text-sm">{{ fill(d.uploads.rejected, { reason: dash.symbol.rejected.reason ?? '' }) }}</p>
            <form class="mt-4 grid gap-3" @submit.prevent="uploadSymbol">
              <input class="field" type="file" accept="image/png" :aria-label="d.uploads.choose" @change="symbolFile = pick($event)" />
              <label v-if="dash.limits.symbolFrames > 1" class="text-sm text-base-300">{{ d.uploads.frameMs }}
                <input v-model.number="symbolFrameMs" class="field mt-1" type="number" min="50" max="1000" step="10" />
              </label>
              <button type="submit" class="btn btn-primary" :disabled="!symbolFile || busy === 'symbol'"><SiteIcon name="image" class="size-4" />{{ d.uploads.upload }}</button>
            </form>
          </div>

          <div class="card p-5">
            <h2 class="section-title">{{ d.uploads.capeTitle }}</h2>
            <p class="mt-1 text-sm text-base-400">{{ d.uploads.capeLead }}</p>
            <p v-if="!dash.limits.fanCape" class="mt-4 rounded-md border border-base-800 bg-base-950 px-4 py-3 text-sm text-base-300"><SiteIcon name="lock" class="mr-1 inline size-4 align-[-3px]" />{{ d.uploads.capeLocked }}</p>
            <template v-else>
              <div class="mt-4 grid grid-cols-2 gap-3">
                <div class="upload-slot">
                  <p class="slot-label">{{ d.uploads.current }}</p>
                  <CapeThumb v-if="dash.fanCape.current" :texture="sameOriginPath(dash.fanCape.current.url)" :width="48" />
                  <p v-if="dash.fanCape.current" class="mt-1 text-xs text-base-300">{{ dash.fanCape.current.name }}</p>
                  <p v-else class="text-xs text-base-400">{{ d.uploads.none }}</p>
                </div>
                <div class="upload-slot">
                  <p class="slot-label">{{ d.uploads.pending }}</p>
                  <template v-if="dash.fanCape.pending">
                    <CapeThumb :texture="sameOriginPath(dash.fanCape.pending.url)" :width="48" />
                    <button type="button" class="mt-2 text-xs underline" :disabled="busy === 'withdraw:fan-cape'" @click="withdraw('fan-cape')">{{ d.uploads.withdraw }}</button>
                  </template>
                  <p v-else class="text-xs text-base-400">–</p>
                </div>
              </div>
              <p v-if="dash.fanCape.rejected" class="state state-danger mt-3 text-sm">{{ fill(d.uploads.rejected, { reason: dash.fanCape.rejected.reason ?? '' }) }}</p>
              <form class="mt-4 grid gap-3" @submit.prevent="uploadCape">
                <input class="field" type="file" accept="image/png" :aria-label="d.uploads.choose" @change="capeFile = pick($event)" />
                <label class="text-sm text-base-300">{{ d.uploads.capeName }}
                  <input v-model="capeForm.name" class="field mt-1" maxlength="32" />
                </label>
                <div v-if="dash.limits.fanCape === 'hd'" class="grid grid-cols-2 gap-3">
                  <label class="text-sm text-base-300">{{ d.uploads.frames }}
                    <input v-model.number="capeForm.frames" class="field mt-1" type="number" min="1" max="32" />
                  </label>
                  <label class="text-sm text-base-300">{{ d.uploads.frameTimeMs }}
                    <input v-model.number="capeForm.frameTimeMs" class="field mt-1" type="number" min="50" max="10000" step="10" :disabled="capeForm.frames <= 1" />
                  </label>
                </div>
                <button type="submit" class="btn btn-primary" :disabled="!capeFile || busy === 'cape'"><SiteIcon name="cape" class="size-4" />{{ d.uploads.upload }}</button>
              </form>
            </template>
          </div>
          <p class="text-xs text-base-400 md:col-span-2">{{ d.uploads.limit }}</p>
        </section>

        <!-- Packs -->
        <section v-else-if="tab === 'packs'" class="card mt-6 p-5">
          <h2 class="section-title">{{ d.packs.title }}</h2>
          <p class="mt-1 text-sm text-base-400">{{ d.packs.lead }}</p>
          <p v-if="!dash.packs.length" class="mt-4 text-base-400">{{ d.packs.empty }}</p>
          <ul v-else class="mt-4 space-y-2">
            <li v-for="p in dash.packs" :key="p.code" class="code-row">
              <div class="min-w-0 flex-1">
                <p class="truncate font-semibold text-base-50">{{ p.name }} <span v-if="p.listed" class="pill pill-ok">{{ d.packs.listed }}</span></p>
                <p class="text-xs text-base-400"><code>{{ p.code }}</code> · {{ fill(d.packs.installs, { n: num(p.installs) }) }}</p>
              </div>
              <button type="button" class="btn" :class="p.listed ? 'btn-ghost' : 'btn-primary'" :disabled="busy === `pack:${p.code}`" @click="setListed(p.code, !p.listed)">{{ p.listed ? d.packs.unlist : d.packs.list }}</button>
            </li>
          </ul>
        </section>

        <!-- Haustier (§35.13) -->
        <section v-else-if="tab === 'pet'" class="mt-6">
          <div v-if="!petState" class="skeleton h-40 rounded-xl" />
          <div v-else class="grid gap-6 lg:grid-cols-[1fr_20rem]">
            <div class="card min-w-0 p-5">
              <h2 class="section-title">{{ d.pet.title }}</h2>
              <p class="mt-1 text-sm text-base-400">{{ d.pet.lead }}</p>
              <div v-if="petState.pet" class="pet-hero mt-4">
                <img v-if="petState.pet.card" :src="sameOriginPath(petState.pet.card)" alt="" class="pet-card" width="112" height="112" />
                <div v-else class="pet-card grid place-items-center text-base-500"><SiteIcon name="star" class="size-8" /></div>
                <div class="min-w-0">
                  <p class="text-xl font-bold text-base-50">{{ petState.pet.name }}</p>
                  <p class="mt-1 text-sm text-base-300">{{ fill(d.pet.count, { n: petState.pet.count }) }}</p>
                  <p class="mt-1.5 flex gap-1" aria-hidden="true"><span v-for="i in 4" :key="i" class="pet-dot" :data-on="i <= petState.pet.count" /></p>
                  <p v-if="!petState.pet.available" class="state state-warn mt-2 text-sm"><SiteIcon name="clock" class="size-4" />{{ d.pet.unavailable }}</p>
                </div>
              </div>
              <p v-else class="state mt-4 text-sm"><SiteIcon name="note" class="size-4" />{{ d.pet.none }}</p>
              <CreatorPetExtras v-if="petState.extras?.length" :extras="petState.extras" :show="petState.showExtras" :helper="helper" @changed="(v: boolean) => { if (petState) petState.showExtras = v }" />

              <template v-if="petState.pet && petState.codes">
                <h3 class="mt-6 text-sm font-semibold text-base-200">{{ d.pet.codesTitle }}</h3>
                <p class="mt-1 text-xs text-base-400">{{ d.pet.codesLead }}</p>
                <div class="mt-3">
                  <div class="quota-bar"><span :style="{ width: `${petState.quota.limit ? Math.min(100, (petState.quota.used / petState.quota.limit) * 100) : 100}%` }" /></div>
                  <p class="mt-1.5 text-xs text-base-300 tabular-nums">{{ fill(d.pet.quota, { used: num(petState.quota.used), limit: num(petState.quota.limit), month: monthName(petState.quota.month), date: date(petState.quota.resetsAt) }) }}</p>
                </div>
                <p v-if="!petState.codes.length" class="mt-4 text-sm text-base-400">{{ d.pet.noCodes }}</p>
                <ul v-else class="mt-4 space-y-2">
                  <li v-for="c in petState.codes" :key="c.id" class="code-row" :data-off="petCodeState(c) !== 'ok'">
                    <div class="min-w-0 flex-1">
                      <p class="flex flex-wrap items-center gap-2">
                        <code class="text-base font-bold text-lamp-300">…{{ c.hint }}</code>
                        <span v-if="petCodeState(c) === 'revoked'" class="pill">{{ d.pet.revoked }}</span>
                        <span v-else-if="petCodeState(c) === 'expired'" class="pill">{{ d.pet.expired }}</span>
                        <span v-if="c.note" class="truncate text-xs text-base-300">{{ c.note }}</span>
                      </p>
                      <p class="mt-0.5 text-xs text-base-400">
                        {{ fill(d.pet.usesMax, { n: num(c.uses), max: num(c.maxUses) }) }} · {{ date(c.createdAt) }}
                        <template v-if="c.expiresAt"> · {{ fill(d.pet.expires, { date: date(c.expiresAt) }) }}</template>
                        <template v-if="c.createdBy.uuid !== dash.uuid"> · {{ fill(d.pet.by, { name: c.createdBy.name }) }}</template>
                      </p>
                    </div>
                    <button v-if="!c.revokedAt" type="button" class="btn btn-ghost" :disabled="busy === `pet-code:${c.id}`" @click="revokePetCode(c.id)">{{ d.pet.revoke }}</button>
                  </li>
                </ul>
              </template>

              <template v-if="!helper && petState.requests.length">
                <h3 class="mt-6 text-sm font-semibold text-base-200">{{ d.pet.wishes }}</h3>
                <ul class="mt-2 space-y-2">
                  <li v-for="r in petState.requests" :key="r.id" class="wish">
                    <img v-if="r.image" :src="sameOriginPath(r.image.url)" alt="" class="wish-img" loading="lazy" />
                    <div class="min-w-0 flex-1">
                      <p class="flex flex-wrap items-center gap-2">
                        <span class="font-semibold text-base-50">{{ r.species }}</span>
                        <span class="pill" :class="{ 'pill-ok': r.status === 'done' }">{{ d.pet.statuses[r.status] }}</span>
                        <span class="text-xs text-base-400">{{ date(r.createdAt) }}</span>
                      </p>
                      <p class="mt-1 text-sm whitespace-pre-line text-base-300 [overflow-wrap:anywhere]">{{ r.description }}</p>
                      <p v-if="r.colors" class="mt-0.5 text-xs text-base-400">{{ d.pet.colorsShort }}: {{ r.colors }}</p>
                      <p v-if="r.reply" class="mt-1 text-xs text-lamp-300">{{ fill(d.pet.reply, { text: r.reply }) }}</p>
                      <div v-if="r.status === 'open'" class="mt-2 flex flex-wrap gap-3 text-xs">
                        <label class="cursor-pointer underline">
                          {{ d.pet.imageAdd }}
                          <input type="file" class="sr-only" accept="image/png,image/jpeg" @change="addWishImage(r.id, $event)" />
                        </label>
                        <button v-if="r.image" type="button" class="underline" :disabled="busy === `pet-img:${r.id}`" @click="removeWishImage(r.id)">{{ d.pet.imageRemove }}</button>
                        <button type="button" class="underline" :disabled="busy === `pet-wish:${r.id}`" @click="withdrawWish(r.id)">{{ d.pet.withdraw }}</button>
                      </div>
                    </div>
                  </li>
                </ul>
              </template>
            </div>

            <div class="space-y-6">
              <form v-if="petState.pet && petState.codes" class="card h-fit p-5" @submit.prevent="createPetCodes">
                <h2 class="section-title">{{ d.pet.create }}</h2>
                <p class="mt-1 text-xs text-base-400">{{ d.pet.quotaHint }}</p>
                <div class="mt-4 grid grid-cols-2 gap-3">
                  <label class="text-sm text-base-300">{{ d.pet.count2 }}
                    <input v-model.number="petForm.count" class="field mt-1" type="number" min="1" max="20" required />
                  </label>
                  <label class="text-sm text-base-300">{{ d.pet.maxUses }}
                    <input v-model.number="petForm.maxUses" class="field mt-1" type="number" min="1" max="100" required />
                  </label>
                </div>
                <label class="label mt-3" for="pc-exp">{{ d.pet.expiresAt }}</label>
                <input id="pc-exp" v-model="petForm.expiresAt" class="field" type="date" />
                <label class="label mt-3" for="pc-note">{{ d.pet.note }}</label>
                <input id="pc-note" v-model="petForm.note" class="field" maxlength="100" />
                <p v-if="petOver" class="mt-2 text-xs text-redstone-300">{{ fill(d.pet.overLimit, { n: petState.quota.remaining }) }}</p>
                <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'pet-codes' || petOver || !petState.pet.available">{{ d.pet.create }}</button>
                <div v-if="petCreated.length" class="mt-4">
                  <p class="text-xs text-lamp-300">{{ d.pet.createdTitle }}</p>
                  <pre class="codes-out mt-2 select-all">{{ petCreated.join('\n') }}</pre>
                  <button type="button" class="btn btn-ghost mt-2 w-full" @click="copyPetCodes"><SiteIcon :name="petCopied ? 'check' : 'copy'" class="size-4" />{{ petCopied ? d.pet.copied : d.pet.copyAll }}</button>
                </div>
              </form>

              <form v-if="!helper && petState.canRequest" class="card h-fit p-5" @submit.prevent="sendWish">
                <h2 class="section-title">{{ d.pet.requestTitle }}</h2>
                <p class="mt-1 text-xs text-base-400">{{ d.pet.requestLead }}</p>
                <label class="label mt-4" for="pw-species">{{ d.pet.species }}</label>
                <input id="pw-species" v-model="wishForm.species" class="field" maxlength="60" required :placeholder="d.pet.speciesPh" />
                <label class="label mt-3" for="pw-desc">{{ d.pet.description }}</label>
                <textarea id="pw-desc" v-model="wishForm.description" class="field" rows="4" maxlength="1000" required :placeholder="d.pet.descriptionPh" />
                <label class="label mt-3" for="pw-colors">{{ d.pet.colors }}</label>
                <input id="pw-colors" v-model="wishForm.colors" class="field" maxlength="200" />
                <label class="label mt-3" for="pw-image">{{ d.pet.image }}</label>
                <input id="pw-image" class="field" type="file" accept="image/png,image/jpeg" @change="pickWishFile" />
                <p v-if="wishFileError" class="mt-1 text-xs text-redstone-300">{{ wishFileError }}</p>
                <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'pet-wish' || !wishForm.species.trim() || !wishForm.description.trim()">{{ d.pet.send }}</button>
              </form>
              <p v-else-if="!helper && !petState.pet" class="card p-5 text-sm text-base-300"><SiteIcon name="clock" class="mr-1 inline size-4 align-[-3px]" />{{ d.pet.hasOpen }}</p>
              <p v-else-if="helper" class="card p-5 text-sm text-base-400">{{ d.pet.ownerOnly }}</p>
            </div>
          </div>
        </section>

        <!-- Stufe, Anfragen, Helfer -->
        <section v-else-if="tab === 'team'" class="mt-6 grid gap-6 md:grid-cols-2">
          <div class="card p-5">
            <h2 class="section-title">{{ d.team.tierTitle }}: {{ ct.tier[dash.tier] }}</h2>
            <p class="mt-1 text-sm text-base-400">{{ limitsText }}</p>
            <p v-if="dash.tier === 'top'" class="mt-4 text-sm text-base-300">{{ d.team.top }}</p>
            <form v-else-if="!openUpgrade" class="mt-4 grid gap-3" @submit.prevent="requestUpgrade">
              <p class="text-sm font-semibold text-base-200">{{ d.team.upgradeTitle }}</p>
              <p class="-mt-2 text-xs text-base-400">{{ d.team.upgradeLead }}</p>
              <input v-model="upgradeForm.channelUrl" class="field" type="url" maxlength="300" required pattern="https://.*" :placeholder="d.team.channelUrl" :aria-label="d.team.channelUrl" />
              <input v-model="upgradeForm.followers" class="field" type="number" min="0" required :placeholder="d.team.followers" :aria-label="d.team.followers" />
              <textarea v-model="upgradeForm.note" class="field" rows="2" maxlength="500" :placeholder="d.team.note" :aria-label="d.team.note" />
              <button type="submit" class="btn btn-primary" :disabled="busy === 'upgrade'">{{ d.team.send }}</button>
            </form>
            <template v-if="dash.requests.length">
              <p class="mt-5 text-sm font-semibold text-base-200">{{ d.team.requests }}</p>
              <ul class="mt-2 space-y-1.5 text-sm">
                <li v-for="r in dash.requests" :key="r.id" class="flex flex-wrap gap-x-2">
                  <span class="text-base-100">{{ d.team.requestKinds[r.kind] }}</span>
                  <span class="pill" :class="{ 'pill-ok': r.status === 'accepted' }">{{ d.team.requestStatus[r.status] }}</span>
                  <span class="text-xs text-base-400">{{ date(r.createdAt) }}</span>
                  <span v-if="r.reason" class="w-full text-xs text-base-400">→ {{ r.reason }}</span>
                </li>
              </ul>
            </template>
          </div>
          <div class="card p-5">
            <h2 class="section-title">{{ d.team.helpersTitle }}</h2>
            <p class="mt-1 text-sm text-base-400">{{ d.team.helpersLead }}</p>
            <p class="mt-1 text-xs text-base-400">{{ fill(d.team.helperLimit, { n: dash.helpers?.length ?? 0, max: HELPER_LIMITS[dash.tier] }) }}</p>
            <p v-if="!dash.helpers?.length" class="mt-4 text-sm text-base-400">{{ d.team.noHelpers }}</p>
            <ul v-else class="mt-4 space-y-3">
              <li v-for="h in dash.helpers" :key="h.uuid" class="rounded-lg border border-base-800 p-3">
                <div class="flex items-center gap-2">
                  <PlayerHead :uuid="h.uuid" :name="h.name" :skin="h.skin" :fetch="false" :size="24" />
                  <span class="flex-1 font-semibold text-base-50">{{ h.name || h.uuid }}</span>
                  <span v-if="h.status === 'pending'" class="pill">{{ d.team.invited }}</span>
                  <button type="button" class="text-xs underline" :disabled="busy === `helper:${h.uuid}`" @click="removeHelper(h.uuid)">{{ d.team.removeHelper }}</button>
                </div>
                <div class="mt-2 flex flex-wrap gap-x-4 gap-y-1">
                  <label v-for="r in HELPER_RIGHTS" :key="r" class="flex items-center gap-1.5 text-sm text-base-300">
                    <input type="checkbox" :checked="h.rights.includes(r)" :disabled="busy === `helper:${h.uuid}` || (h.rights.length === 1 && h.rights.includes(r))" @change="setHelperRight(h, r, ($event.target as HTMLInputElement).checked)" />
                    {{ d.team.rights[r] }}
                  </label>
                </div>
              </li>
            </ul>
          </div>
        </section>
      </template>
    </template>
  </div>
</template>

<style scoped>
.team-banner {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  border-radius: 0.75rem;
  padding: 0.7rem 1rem;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-lamp-300);
  background: color-mix(in srgb, var(--color-lamp-900) 70%, var(--color-base-900));
  border: 1px solid color-mix(in srgb, var(--color-lamp-400) 45%, transparent);
}
.team-hits {
  position: absolute;
  z-index: 20;
  left: 0;
  right: 0;
  top: calc(100% + 4px);
  overflow: hidden;
  padding: 0.25rem 0;
}
.team-changes {
  border-left: 3px solid var(--color-lamp-400);
}
.dash-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  border-bottom: 1px solid var(--color-base-800);
  padding-bottom: 0.6rem;
}
.dash-tabs button {
  border-radius: 999px;
  padding: 0.35rem 0.9rem;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-base-400);
  transition: color 0.15s, background-color 0.15s;
}
.dash-tabs button:hover {
  color: var(--color-base-50);
}
.dash-tabs button[aria-pressed='true'] {
  color: var(--color-base-50);
  background: var(--color-base-800);
}
.state {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  border-radius: 0.5rem;
  padding: 0.45rem 0.7rem;
  background: var(--color-base-850);
  color: var(--color-base-300);
}
.state-ok {
  color: var(--color-ok);
}
.state-warn {
  color: var(--color-lamp-300);
}
.state-danger {
  color: var(--color-redstone-300);
}
.code-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.6rem;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.7rem 0.85rem;
}
.code-row[data-off='true'] code {
  opacity: 0.5;
}
.pill {
  display: inline-flex;
  border-radius: 999px;
  padding: 0.05rem 0.5rem;
  font-size: 0.7rem;
  font-weight: 600;
  background: var(--color-base-800);
  color: var(--color-base-300);
}
.pill-ok {
  background: color-mix(in srgb, var(--color-ok) 16%, transparent);
  color: var(--color-ok);
}
.upload-slot {
  display: flex;
  min-height: 7.5rem;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 0.6rem;
  border: 1px dashed var(--color-base-700);
  background: var(--color-base-950);
  padding: 0.75rem;
}
.slot-label {
  margin-bottom: 0.5rem;
  font-size: 0.65rem;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--color-base-400);
}
.preview {
  display: grid;
  gap: 0.7rem;
  font-size: 0.875rem;
  line-height: 1.6;
  color: var(--color-base-200);
  overflow-wrap: anywhere;
}
.preview ul {
  list-style: disc;
  padding-left: 1.1rem;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  border-radius: 999px;
  border: 1px solid var(--color-base-700);
  padding: 0.15rem 0.55rem;
  font-size: 0.75rem;
  color: var(--color-base-200);
}
.chip.bad {
  border-color: var(--color-redstone-600);
  color: var(--color-redstone-300);
}
.pet-hero {
  display: flex;
  align-items: center;
  gap: 1rem;
  border-radius: 0.75rem;
  border: 1px solid var(--color-base-800);
  background: radial-gradient(120% 140% at 0% 0%, color-mix(in srgb, var(--color-lamp-400) 10%, transparent), var(--color-base-950) 60%);
  padding: 0.9rem;
}
.pet-card {
  width: 7rem;
  height: 7rem;
  flex-shrink: 0;
  border-radius: 0.6rem;
  background: var(--color-base-900);
  object-fit: cover;
}
.pet-dot {
  width: 0.6rem;
  height: 0.6rem;
  border-radius: 999px;
  background: var(--color-base-800);
}
.pet-dot[data-on='true'] {
  background: var(--color-lamp-400);
}
.quota-bar {
  height: 0.45rem;
  overflow: hidden;
  border-radius: 999px;
  background: var(--color-base-800);
}
.quota-bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--color-redstone-500);
}
.wish {
  display: flex;
  gap: 0.75rem;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.7rem 0.85rem;
}
.wish-img {
  width: 4rem;
  height: 4rem;
  flex-shrink: 0;
  border-radius: 0.4rem;
  object-fit: cover;
}
.codes-out {
  max-height: 12rem;
  overflow: auto;
  padding: 0.75rem;
  border-radius: 0.375rem;
  font-family: var(--font-mono);
  font-size: 0.8125rem;
  color: var(--color-lamp-300);
  background: var(--color-base-950);
}
:deep(.adm-chart rect.bar) {
  fill: color-mix(in srgb, var(--color-redstone-500) 80%, transparent);
}
:deep(.adm-chart line.grid) {
  stroke: var(--color-base-800);
}
:deep(.adm-chart text) {
  fill: var(--color-base-400);
  font-size: 10px;
}
</style>
