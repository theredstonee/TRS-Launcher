<script setup lang="ts">
// Creator-Dashboard auf der Website (§35.3 + §35.11) mit der Website-Sitzung: Übersicht/Statistik, öffentliches Profil
// (Beschreibung + Links, Prüfung durch das Team), Codes, Fan-Symbol und Fan-Umhang, Creator-Packs, Stufe und Helfer.
// Helfer wählen oben den Creator, für den sie handeln (`?as=`). Nur im Browser (routeRules), nie indexiert.
const { m, fill, date, lang } = useLang()
const lp = useLocalePath()
const { account, loaded, load, api, loginUrl } = useAccount()

// Persönliche Wartezeit (Wunsch des Owners): Lencraft151 sieht das Dashboard erst ab Freitag, 23.10.2026, 18:00 Uhr (MESZ).
const WAIT_UUID = '889912aeb76346ce9911a1ce629540a5'
const WAIT_UNTIL = Date.parse('2026-10-23T18:00:00+02:00')
const now = ref(Date.now())
let waitTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => { waitTimer = setInterval(() => (now.value = Date.now()), 15_000) })
onBeforeUnmount(() => clearInterval(waitTimer))
const waiting = computed(() => account.value?.uuid.replace(/-/g, '').toLowerCase() === WAIT_UUID && now.value < WAIT_UNTIL)
const WAIT_MESSAGE = "Mein Lieber, da du so oft gefragt hast, kommt's für dich erst am Freitag um 18 Uhr."
const songOpen = ref(true)
const d = computed(() => m.value.creatorDash)
const ct = computed(() => m.value.creators)

usePageSeo(() => ({ path: '/creator', title: `${d.value.title} – TRS Launcher`, description: d.value.lead, noindex: true }))

type Tab = 'overview' | 'profile' | 'codes' | 'uploads' | 'packs' | 'team'

const my = ref<MyCreatorState | null>(null)
const dash = ref<CreatorDashboard | null>(null)
const as = ref<string | null>(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const busy = ref('')
const tab = ref<Tab>('overview')

const helper = computed(() => !!dash.value?.viewer?.helper)
const rights = computed(() => new Set(dash.value?.viewer?.rights ?? HELPER_RIGHTS))
const may = (r: HelperRight) => !helper.value || rights.value.has(r)
const tabs = computed<Tab[]>(() => {
  const list: Tab[] = ['overview']
  if (!helper.value) list.push('profile')
  if (may('codes')) list.push('codes')
  if (may('uploads')) list.push('uploads')
  if (may('packs')) list.push('packs')
  if (!helper.value) list.push('team')
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
    if (own || as.value) await loadDash()
    else dash.value = null
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  if (await load()) await refresh()
  else loading.value = false
})
async function switchTo(uuid: string | null) {
  as.value = uuid
  tab.value = 'overview'
  notice.value = ''
  await refresh()
}

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
    { label: d.value.stats.fanCapeHolders, value: s.fanCapeHolders },
    { label: d.value.stats.newPlayers, value: s.newPlayers },
    { label: d.value.stats.packInstalls, value: s.packInstalls },
  ]
})
const num = (n: number) => new Intl.NumberFormat(lang.value === 'en' ? 'en-GB' : lang.value).format(n)

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
    const r = await api<{ requests: CreatorDashboard['requests'] }>('/v1/creator/requests', { method: 'POST', body })
    if (dash.value) dash.value.requests = r.requests
    Object.assign(upgradeForm, { channelUrl: '', followers: '', note: '' })
  }, d.value.team.sent)
}
async function setHelperRight(h: NonNullable<CreatorDashboard['helpers']>[number], right: HelperRight, on: boolean) {
  const next = on ? [...new Set([...h.rights, right])] : h.rights.filter((r) => r !== right)
  if (!next.length) return
  await act(`helper:${h.uuid}`, async () => {
    const r = await api<{ helpers: CreatorDashboard['helpers'] }>(`/v1/creator/helpers/${h.uuid}`, { method: 'PATCH', body: { rights: next } })
    if (dash.value) dash.value.helpers = r.helpers
  })
}
async function removeHelper(uuid: string) {
  await act(`helper:${uuid}`, async () => {
    await api(`/v1/creator/helpers/${uuid}`, { method: 'DELETE' })
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
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 pt-14 sm:px-6">
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
      <WaitSongOverlay v-if="songOpen" :name="account!.name" :message="WAIT_MESSAGE" until="Freitag, 23.10.2026, 18:00 Uhr" @close="songOpen = false" />
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
        <div v-if="my && my.helping.length" class="mt-6 flex flex-wrap items-center gap-2">
          <label for="dash-as" class="text-sm text-base-400">{{ d.actingAs }}</label>
          <select id="dash-as" class="field w-auto" :value="as ?? ''" @change="switchTo(($event.target as HTMLSelectElement).value || null)">
            <option v-if="my.creator?.state === 'active'" value="">{{ d.mine }}</option>
            <option v-for="h in my.helping" :key="h.creator.uuid" :value="h.creator.uuid">{{ h.creator.name }}</option>
          </select>
          <span v-if="helper" class="text-xs text-base-400">{{ fill(d.helperNote, { name: helpingName, rights: [...rights].map((r) => d.team.rights[r] ?? r).join(', ') }) }}</span>
        </div>

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
          <p v-if="!dash.stats" class="card p-5 text-base-400">{{ d.stats.hidden }}</p>
          <template v-else>
            <ul class="grid grid-cols-2 gap-3 md:grid-cols-5">
              <li v-for="s in statCards" :key="s.label" class="card p-4">
                <p class="text-2xl font-bold text-base-50 tabular-nums">{{ num(s.value) }}</p>
                <p class="mt-1 text-xs text-base-400">{{ s.label }}</p>
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
