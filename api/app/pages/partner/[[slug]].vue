<script setup lang="ts">
// Partner-Dashboard (§44.4) mit der Website-Sitzung: Übersicht, Codes, Werbe-Links, Partner-Server, API, Webhook,
// Vorstellung, Kosmetik, Profil, Zugänge – je nach Rechten. /partner/<slug> bei mehreren Zugängen; Team-Mitglieder mit
// partners.manage öffnen jedes Dashboard im Team-Modus. Nur im Browser (routeRules), nie indexiert.
const { fill, date, lang } = useLang()
const { p } = usePartnerText()
const lp = useLocalePath()
const route = useRoute()
const { account, loaded, load, api, loginUrl } = useAccount()
const d = computed(() => p.value.dash)

usePageSeo(() => ({ path: '/partner', title: `${d.value.title} – TRS Launcher`, description: d.value.lead, noindex: true }))

type Tab = 'overview' | 'codes' | 'links' | 'server' | 'api' | 'webhook' | 'promo' | 'cosmetics' | 'profile' | 'members'

const me = ref<MyPartnerState | null>(null)
const dash = ref<PartnerDashboard | null>(null)
const teamList = ref<{ slug: string, name: string }[]>([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const busy = ref('')
const tab = ref<Tab>('overview')

const teamManager = computed(() => {
  const t = account.value?.team
  return !!t && (t.owner || t.permissions.includes('partners.manage'))
})
const choices = computed(() => {
  const list = [...(me.value?.member ?? []).map((m) => ({ slug: m.slug, name: m.name }))]
  for (const x of teamList.value) if (!list.some((y) => y.slug === x.slug)) list.push(x)
  return list
})
const slug = computed(() => {
  const s = typeof route.params.slug === 'string' ? route.params.slug.toLowerCase() : ''
  return s || choices.value[0]?.slug || ''
})
const rights = computed(() => new Set(dash.value?.viewer.rights ?? []))
const may = (r: PartnerRight) => rights.value.has(r)
const manager = computed(() => !!dash.value && (dash.value.viewer.owner || dash.value.viewer.team))
const tabs = computed<Tab[]>(() => {
  const list: Tab[] = ['overview']
  for (const [t, r] of [['codes', 'codes'], ['links', 'links'], ['server', 'server'], ['api', 'api'], ['webhook', 'webhook'], ['promo', 'promo'], ['cosmetics', 'cosmetics'], ['profile', 'profile']] as const) {
    if (may(r)) list.push(t)
  }
  if (manager.value) list.push('members')
  return list
})
const base = computed(() => `/v1/partner/${slug.value}`)
const num = (n: number) => new Intl.NumberFormat(lang.value === 'en' ? 'en-GB' : lang.value).format(n)

const fail = (e: unknown) => {
  const code = apiCode(e)
  error.value = d.value.errors[code] ?? fill(d.value.failed, { error: apiMessage(e) })
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

async function loadDash() {
  if (!slug.value) {
    dash.value = null
    return
  }
  dash.value = await api<PartnerDashboard>(`${base.value}/dashboard`)
  resetForms()
  if (!tabs.value.includes(tab.value)) tab.value = 'overview'
}
async function refresh() {
  loading.value = true
  error.value = ''
  try {
    me.value = await api<MyPartnerState>('/v1/me/partner')
    if (teamManager.value) {
      teamList.value = (await api<{ partners: { slug: string, name: string }[] }>('/v1/admin/partners').catch(() => ({ partners: [] }))).partners
        .map((x) => ({ slug: x.slug, name: x.name }))
    }
    await loadDash()
  } catch (e) {
    dash.value = null
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
  await refresh()
})
watch(slug, () => {
  tab.value = 'overview'
  createdCodes.value = []
  shownKey.value = ''
  shownSecret.value = ''
  void loadDash().catch(fail)
})
function switchTo(s: string) {
  void navigateTo(`/partner/${s}`)
}
const answerInvite = (s: string, accept: boolean) => act(`inv:${s}`, async () => {
  me.value = await api<MyPartnerState>(`/v1/me/partner/invites/${s}`, { method: 'POST', body: { accept } })
  if (accept) switchTo(s)
})

const requestsOf = (kind: PartnerRequest['kind'], channel?: string) =>
  (dash.value?.requests ?? []).filter((r) => r.kind === kind && (channel === undefined || r.channel === channel))
const openOf = (kind: PartnerRequest['kind'], channel?: string) => requestsOf(kind, channel).find((r) => r.status === 'open') ?? null
const lastDecided = (kind: PartnerRequest['kind']) => requestsOf(kind).find((r) => r.status === 'accepted' || r.status === 'rejected') ?? null
const withdraw = (r: PartnerRequest) => act(`wd:${r.id}`, async () => {
  await api(`${base.value}/requests/${r.id}`, { method: 'DELETE' })
  await loadDash()
})
const sendRequest = (body: Record<string, unknown>, key: string) => act(key, async () => {
  await api(`${base.value}/requests`, { method: 'POST', body })
  await loadDash()
}, d.value.sent)

// ---------------------------------------------------------------- Übersicht
const stats = computed(() => dash.value?.stats ?? null)
const pct = (a: number, b: number) => (b > 0 ? Math.round((a / b) * 100) : 0)
const chart = computed(() => {
  const days = stats.value?.daily.slice(-14) ?? []
  const max = Math.max(1, ...days.map((x) => x.fixed + x.onetime))
  return days.map((x) => ({ ...x, total: x.fixed + x.onetime, h: ((x.fixed + x.onetime) / max) * 100, split: x.fixed + x.onetime ? (x.onetime / (x.fixed + x.onetime)) * 100 : 0 }))
})

// ---------------------------------------------------------------- Codes
const batchForm = reactive({ label: '', count: 10 })
const createdCodes = ref<string[]>([])
const createdBatch = ref<number | null>(null)
const codeChange = ref('')
const quotaAmount = ref<500 | 1000 | 5000>(1000)
const createBatch = () => act('batch', async () => {
  const r = await api<{ batch: PartnerBatch, codes: string[] }>(`${base.value}/batches`, { method: 'POST', body: { label: batchForm.label.trim() || 'Dashboard', count: Math.max(1, Math.min(500, Math.floor(batchForm.count) || 1)) } })
  createdCodes.value = r.codes
  createdBatch.value = r.batch.id
  batchForm.label = ''
  await loadDash()
}, '')
const blockBatch = (b: PartnerBatch) => {
  if (!confirm(fill(d.value.codes.blockConfirm, { label: b.label }))) return
  void act(`block:${b.id}`, async () => {
    await api(`${base.value}/batches/${b.id}/block`, { method: 'POST' })
    await loadDash()
  })
}
const copied = ref('')
async function copy(text: string, key: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = key
    setTimeout(() => (copied.value = ''), 1600)
  } catch {
    // Zwischenablage gesperrt.
  }
}

// ---------------------------------------------------------------- Links
const campaign = ref('')
const goUrl = (c = '') => `${location.origin}${dash.value?.links?.path ?? ''}${c ? `?c=${c}` : ''}`
const addCampaign = () => act('link', async () => {
  await api(`${base.value}/links`, { method: 'POST', body: { campaign: campaign.value.trim().toLowerCase() } })
  campaign.value = ''
  await loadDash()
})
const removeCampaign = (c: string) => act(`link:${c}`, async () => {
  await api(`${base.value}/links/${c}`, { method: 'DELETE' })
  await loadDash()
})
const linkTotals = computed(() => {
  const l = dash.value?.links?.campaigns ?? []
  return { clicks: l.reduce((a, x) => a + x.clicks30d, 0), downloads: l.reduce((a, x) => a + x.downloads30d, 0) }
})

// ---------------------------------------------------------------- Server
const serverForm = reactive({ name: '', address: '', minVersion: '1.8', maxVersion: '1.21.11', motd: '' })
const sendServer = () => sendRequest({ kind: 'server', ...serverForm, name: serverForm.name.trim(), address: serverForm.address.trim(), motd: serverForm.motd.trim().slice(0, 60) }, 'server')

// ---------------------------------------------------------------- API
const shownKey = ref('')
const rotateKey = () => act('key', async () => {
  shownKey.value = (await api<{ key: string }>(`${base.value}/api-key`, { method: 'POST' })).key
  await loadDash()
})
const revokeKey = () => {
  if (!confirm(d.value.api.revokeConfirm)) return
  void act('key', async () => {
    await api(`${base.value}/api-key`, { method: 'DELETE' })
    shownKey.value = ''
    await loadDash()
  })
}
const apiBase = computed(() => (import.meta.client ? location.origin : ''))

// ---------------------------------------------------------------- Webhook
const HOOK_EVENTS = ['code.redeemed', 'partner.supported', 'partner.left', 'quota.low'] as const
const hookForm = reactive<{ url: string, events: string[] }>({ url: '', events: ['code.redeemed', 'partner.supported', 'quota.low'] })
const shownSecret = ref('')
const saveHook = () => act('hook', async () => {
  const r = await api<{ secret?: string }>(`${base.value}/webhook`, { method: 'PUT', body: { url: hookForm.url.trim(), events: hookForm.events } })
  if (r.secret) shownSecret.value = r.secret
  await loadDash()
}, d.value.saved)
const rotateSecret = () => act('hook', async () => {
  shownSecret.value = (await api<{ secret: string }>(`${base.value}/webhook/secret`, { method: 'POST' })).secret
  await loadDash()
})
const testHook = () => act('hook-test', async () => {
  await api(`${base.value}/webhook/test`, { method: 'POST' })
  await loadDash()
})
const retryDelivery = (id: number) => act(`dl:${id}`, async () => {
  await api(`${base.value}/webhook/deliveries/${id}/retry`, { method: 'POST' })
  await loadDash()
})
const removeHook = () => {
  if (!confirm(d.value.webhook.removeConfirm)) return
  void act('hook', async () => {
    await api(`${base.value}/webhook`, { method: 'DELETE' })
    shownSecret.value = ''
    await loadDash()
  })
}

// ---------------------------------------------------------------- Vorstellung
const promoForm = reactive<{ channel: 'blog' | 'newsletter' | 'wiki', topic: string, text: string }>({ channel: 'blog', topic: '', text: '' })
const sendPromo = () => sendRequest({ kind: 'promo', channel: promoForm.channel, topic: promoForm.topic.trim(), text: promoForm.text.trim() }, 'promo')
function promoState(c: 'blog' | 'newsletter' | 'wiki'): { label: string, cls: string } {
  if (openOf('promo', c)) return { label: d.value.promo.requested, cls: 'pill-warn' }
  if (requestsOf('promo', c).some((r) => r.status === 'accepted')) return { label: d.value.promo.planned, cls: 'pill-ok' }
  if (c === 'wiki' && dash.value?.wikiUrl) return { label: d.value.promo.online, cls: 'pill-ok' }
  return { label: d.value.promo.free, cls: '' }
}

// ---------------------------------------------------------------- Kosmetik
const capeFile = ref<File | null>(null)
const capeName = ref('')
const capeFrameMs = ref(150)
const wish = ref('')
function pickFile(e: Event): File | null {
  const f = (e.target as HTMLInputElement).files?.[0] ?? null
  return f && f.type === 'image/png' ? f : null
}
async function putPng(path: string, f: File) {
  await apiFetch(path, { method: 'PUT', body: f, headers: { 'Content-Type': 'image/png', 'X-CSRF-Token': account.value?.csrf ?? '' }, credentials: 'same-origin' })
}
async function pngFrames(f: File): Promise<number> {
  const img = await createImageBitmap(f)
  const w = img.width
  const h = img.height
  img.close()
  const frameH = w / 2
  return w % 64 === 0 && h % frameH === 0 && h > frameH ? h / frameH : 1
}
const uploadCape = () => act('cape', async () => {
  const f = capeFile.value
  if (!f) return
  const frames = await pngFrames(f)
  const q = new URLSearchParams()
  if (capeName.value.trim()) q.set('name', capeName.value.trim().slice(0, 32))
  if (frames > 1) q.set('frameTimeMs', String(Math.max(50, Math.min(10_000, Math.floor(capeFrameMs.value) || 150))))
  await putPng(`${base.value}/cape${q.toString() ? `?${q}` : ''}`, f)
  capeFile.value = null
  await loadDash()
}, d.value.sent)
const sendWish = () => sendRequest({ kind: 'cosmetic_wish', text: wish.value.trim() }, 'wish').then(() => {
  if (!error.value) wish.value = ''
})

// ---------------------------------------------------------------- Profil
const profileForm = reactive<{ de: string, en: string, links: PartnerLink[] }>({ de: '', en: '', links: [] })
const logoFile = ref<File | null>(null)
function resetForms() {
  const x = dash.value
  if (!x) return
  const pending = openOf('profile')?.data as { text?: { de: string, en: string }, links?: PartnerLink[] } | undefined
  profileForm.de = pending?.text?.de ?? x.profile.text.de
  profileForm.en = pending?.text?.en ?? x.profile.text.en
  profileForm.links = (pending?.links ?? x.profile.links).map((l) => ({ ...l }))
  const s = x.server?.current
  const ps = openOf('server')?.data as typeof serverForm | undefined
  Object.assign(serverForm, ps ?? (s ? { name: s.name, address: s.address, minVersion: s.minVersion, maxVersion: s.maxVersion, motd: s.motd } : { name: `${x.name} Lobby`, address: '', minVersion: '1.8', maxVersion: '1.21.11', motd: '' }))
  if (x.webhook?.config) {
    hookForm.url = x.webhook.config.url
    hookForm.events = [...x.webhook.config.events]
  }
}
const sendProfile = () => sendRequest({
  kind: 'profile',
  text: { de: profileForm.de, en: profileForm.en },
  links: profileForm.links.filter((l) => l.label.trim() && l.url.trim()).map((l) => ({ label: l.label.trim(), url: l.url.trim() })),
}, 'profile')
const uploadLogo = () => act('logo', async () => {
  if (!logoFile.value) return
  await putPng(`${base.value}/logo`, logoFile.value)
  logoFile.value = null
  await loadDash()
}, d.value.sent)

// ---------------------------------------------------------------- Zugänge
const inviteName = ref('')
const inviteRights = ref<PartnerRight[]>(['stats'])
const editRights = reactive<Record<string, PartnerRight[]>>({})
watch(() => dash.value?.members, (list) => {
  for (const m of list ?? []) editRights[m.uuid] = [...m.rights]
}, { immediate: true })
const invite = () => act('invite', async () => {
  await api(`${base.value}/members`, { method: 'POST', body: { name: inviteName.value.trim(), rights: inviteRights.value } })
  inviteName.value = ''
  await loadDash()
})
const saveRights = (uuid: string) => act(`m:${uuid}`, async () => {
  await api(`${base.value}/members/${uuid}`, { method: 'PATCH', body: { rights: editRights[uuid] } })
  await loadDash()
}, d.value.saved)
const removeMember = (m: { uuid: string, name: string }) => {
  if (!confirm(fill(d.value.members.removeConfirm, { name: m.name }))) return
  void act(`m:${m.uuid}`, async () => {
    await api(`${base.value}/members/${m.uuid}`, { method: 'DELETE' })
    await loadDash()
  })
}
const rightsLabel = (r: PartnerRight[]) => r.map((x) => d.value.members.right[x] ?? x).join(', ')
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 pt-14 sm:px-6">
    <header class="flex flex-wrap items-end gap-4">
      <div class="min-w-0 flex-1">
        <p class="text-xs font-semibold tracking-[0.2em] text-redstone-400 uppercase">{{ p.pub.eyebrow }}</p>
        <h1 class="display mt-2 text-5xl leading-tight text-base-50">{{ d.title }}</h1>
        <p class="mt-3 text-lg text-base-400">{{ d.lead }}</p>
      </div>
      <NuxtLink v-if="dash && dash.state === 'active'" :to="lp(partnerPath(dash.slug))" class="btn btn-ghost"><SiteIcon name="external" class="size-4" />{{ d.publicPage }}</NuxtLink>
    </header>

    <div v-if="!loaded || loading" class="mt-8 space-y-3"><div v-for="i in 3" :key="i" class="skeleton h-24 rounded-xl" /></div>

    <div v-else-if="!account" class="card mt-8 p-6">
      <p class="text-base-300">{{ d.signIn }}</p>
      <a :href="loginUrl('/partner')" class="btn btn-primary mt-4"><SiteIcon name="user" class="size-4" />{{ d.signInButton }}</a>
    </div>

    <template v-else>
      <p v-if="error" role="alert" class="mt-6 text-sm text-redstone-300">{{ error }}</p>
      <p v-if="notice" role="status" class="mt-6 text-sm text-ok">{{ notice }}</p>

      <section v-if="me?.invites.length" class="card mt-6 p-5">
        <h2 class="text-sm font-semibold text-base-100">{{ d.invitesTitle }}</h2>
        <ul class="mt-2 space-y-2">
          <li v-for="i in me.invites" :key="i.slug" class="flex flex-wrap items-center gap-3 text-sm">
            <span class="flex-1 text-base-200">{{ fill(d.invite, { name: i.name, rights: rightsLabel(i.rights) }) }}</span>
            <button type="button" class="btn btn-primary" :disabled="busy === `inv:${i.slug}`" @click="answerInvite(i.slug, true)">{{ d.accept }}</button>
            <button type="button" class="btn btn-ghost" :disabled="busy === `inv:${i.slug}`" @click="answerInvite(i.slug, false)">{{ d.decline }}</button>
          </li>
        </ul>
      </section>

      <div v-if="!dash" class="card mt-8 p-6">
        <h2 class="heading text-2xl text-base-50">{{ d.noAccess }}</h2>
        <p class="mt-2 text-base-400">{{ d.noAccessHint }}</p>
      </div>

      <template v-else>
        <div v-if="choices.length > 1" class="mt-6 flex flex-wrap items-center gap-2">
          <label for="partner-pick" class="text-sm text-base-400">{{ teamManager ? d.teamSearch : d.pick }}</label>
          <select id="partner-pick" class="field w-auto" :value="dash.slug" @change="switchTo(($event.target as HTMLSelectElement).value)">
            <option v-for="c in choices" :key="c.slug" :value="c.slug">{{ c.name }}</option>
          </select>
        </div>
        <p v-if="dash.viewer.team" class="team-banner mt-4" role="status"><SiteIcon name="shield" class="size-5 shrink-0" /><span>{{ d.teamBanner }}</span></p>
        <p v-if="dash.state === 'paused'" class="state state-warn mt-4"><SiteIcon name="pause" class="size-4" />{{ d.paused }}</p>

        <section class="card mt-6 flex flex-wrap items-center gap-5 p-5">
          <PartnerLogo :logo="dash.logo" :name="dash.name" :size="64" />
          <div class="min-w-0 flex-1">
            <p class="text-2xl font-bold text-base-50">{{ dash.name }}</p>
            <p class="text-xs font-bold tracking-[0.16em] text-redstone-400 uppercase">{{ p.category[dash.category] }}</p>
          </div>
          <code v-if="dash.code" class="big-code">{{ dash.code }}</code>
        </section>

        <nav class="dash-tabs mt-6" :aria-label="d.title">
          <button v-for="x in tabs" :key="x" type="button" :aria-pressed="tab === x" @click="tab = x; notice = ''">{{ d.tabs[x] }}</button>
        </nav>

        <!-- Übersicht -->
        <section v-if="tab === 'overview'" class="mt-6">
          <div v-if="dash.teamChanges.length" class="card team-changes mb-4 p-4">
            <p class="text-sm font-semibold text-base-100">{{ d.teamChanges }}</p>
            <ul class="mt-2 space-y-1 text-sm text-base-300">
              <li v-for="(c, i) in dash.teamChanges.slice(0, 6)" :key="i">{{ c.action }} · {{ date(c.at) }}</li>
            </ul>
          </div>
          <template v-if="stats">
            <ul class="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <li class="card p-4">
                <p class="text-2xl font-bold text-base-50 tabular-nums">{{ num(stats.supporters) }}</p>
                <p class="mt-1 text-xs text-base-400">{{ d.overview.supporters }}</p>
                <p class="mt-1 text-[11px] text-ok">{{ fill(d.overview.supportersHint, { n: num(stats.newSupporters7d) }) }}</p>
              </li>
              <li class="card p-4">
                <p class="text-2xl font-bold text-base-50 tabular-nums">{{ num(stats.redemptions.total) }}</p>
                <p class="mt-1 text-xs text-base-400">{{ d.overview.redemptions }}</p>
                <p class="mt-1 text-[11px] text-base-400">{{ fill(d.overview.redemptionsHint, { code: dash.code ?? '', fixed: num(stats.redemptions.fixed), onetime: num(stats.redemptions.onetime) }) }}</p>
              </li>
              <li class="card p-4">
                <p class="text-2xl font-bold text-base-50 tabular-nums">{{ num(stats.newPlayers) }}</p>
                <p class="mt-1 text-xs text-base-400">{{ d.overview.newPlayers }}</p>
                <p class="mt-1 text-[11px] text-base-400">{{ d.overview.newPlayersHint }}</p>
              </li>
              <li class="card p-4">
                <p class="text-2xl font-bold text-base-50 tabular-nums">{{ pct(stats.capeWorn, stats.supporters) }} %</p>
                <p class="mt-1 text-xs text-base-400">{{ d.overview.worn }}</p>
                <p class="mt-1 text-[11px] text-base-400">{{ fill(d.overview.wornHint, { p: pct(stats.cosmeticWorn, stats.supporters) }) }}</p>
              </li>
            </ul>
            <div class="card mt-4 p-4">
              <p class="text-sm font-semibold text-base-200">{{ d.overview.chart }}</p>
              <div class="bars mt-4" role="img" :aria-label="d.overview.chart">
                <div v-for="x in chart" :key="x.day" class="bar" :title="`${x.day}: ${x.fixed} + ${x.onetime}`" :style="{ height: `${Math.max(2, x.h)}%`, '--split': `${x.split}%` }" />
              </div>
              <div class="legend mt-2"><span><i class="lg-fixed" />{{ d.overview.fixed }}</span><span><i class="lg-once" />{{ d.overview.onetime }}</span></div>
            </div>
          </template>
          <p v-else class="card mt-0 p-4 text-sm text-base-400">{{ d.overview.noStats }}</p>
          <div class="mt-4 grid gap-4 md:grid-cols-2">
            <div v-if="stats" class="card p-4">
              <p class="text-sm font-semibold text-base-200">{{ d.overview.activity }}</p>
              <table class="kv mt-2 w-full text-sm">
                <tbody>
                  <tr><td>{{ d.overview.onlineToday }}</td><td>{{ num(stats.onlineToday) }}</td></tr>
                  <tr><td>{{ d.overview.playedToday }}</td><td>{{ num(stats.playedToday) }}</td></tr>
                  <tr><td>{{ d.overview.left }}</td><td>{{ num(stats.left30d) }}</td></tr>
                  <tr><td>{{ d.overview.joins }}</td><td>{{ num(stats.serverJoins7d) }}</td></tr>
                  <tr><td>{{ d.overview.downloads }}</td><td>{{ num(stats.downloads30d) }}</td></tr>
                </tbody>
              </table>
              <p class="mt-2 text-[11px] text-base-400">{{ d.overview.numbersOnly }}</p>
            </div>
            <div class="card p-4">
              <p class="text-sm font-semibold text-base-200">{{ d.quota.title }}</p>
              <p class="mt-2 flex text-sm text-base-300"><span class="flex-1">{{ fill(d.quota.used, { used: num(dash.quota.used), total: num(dash.quota.total) }) }}</span>{{ fill(d.quota.free, { n: num(dash.quota.free) }) }}</p>
              <div class="meter mt-2"><div :style="{ width: `${pct(dash.quota.used, dash.quota.total)}%` }" /></div>
              <button v-if="may('codes')" type="button" class="btn btn-ghost mt-3" @click="tab = 'codes'">{{ d.quota.manage }}</button>
            </div>
          </div>
        </section>

        <!-- Codes -->
        <section v-else-if="tab === 'codes'" class="mt-6 grid gap-4">
          <div class="grid gap-4 md:grid-cols-2">
            <div class="card p-4">
              <p class="text-sm font-semibold text-base-200">{{ d.codes.fixedTitle }}</p>
              <p class="mt-2 flex items-center gap-2"><code class="big-code">{{ dash.code }}</code><span class="pill pill-ok">{{ d.codes.active }}</span></p>
              <p class="mt-2 text-xs text-base-400">{{ d.codes.fixedText }}</p>
              <p v-if="openOf('code_change')" class="state state-warn mt-3">{{ fill(d.codes.changePending, { code: String(openOf('code_change')!.data.code) }) }}
                <button type="button" class="ml-auto text-xs underline" @click="withdraw(openOf('code_change')!)">{{ d.withdraw }}</button></p>
              <form v-else class="mt-3 flex gap-2" @submit.prevent="sendRequest({ kind: 'code_change', code: codeChange.trim().toUpperCase() }, 'code')">
                <input v-model="codeChange" class="field w-40 font-mono uppercase" maxlength="19" pattern="[A-Za-z0-9]{4,19}" required :placeholder="d.codes.newCode" />
                <button type="submit" class="btn btn-ghost" :disabled="busy === 'code'">{{ d.codes.change }}</button>
              </form>
              <p v-if="lastDecided('code_change')?.status === 'rejected'" class="mt-2 text-xs text-redstone-300">{{ fill(d.rejected, { reason: lastDecided('code_change')!.reason ?? '' }) }}</p>
            </div>
            <div class="card p-4">
              <p class="text-sm font-semibold text-base-200">{{ d.quota.title }}</p>
              <p class="mt-2 flex text-sm text-base-300"><span class="flex-1">{{ fill(d.quota.used, { used: num(dash.quota.used), total: num(dash.quota.total) }) }}</span>{{ fill(d.quota.free, { n: num(dash.quota.free) }) }}</p>
              <div class="meter mt-2"><div :style="{ width: `${pct(dash.quota.used, dash.quota.total)}%` }" /></div>
              <p class="mt-2 text-xs text-base-400">{{ d.quota.text }}</p>
              <p v-if="dash.quota.requested" class="state state-warn mt-3">{{ fill(d.quota.requested, { n: num(dash.quota.requested) }) }}</p>
              <div v-else class="mt-3 flex gap-2">
                <select v-model.number="quotaAmount" class="field w-28"><option :value="500">+500</option><option :value="1000">+1.000</option><option :value="5000">+5.000</option></select>
                <button type="button" class="btn btn-ghost" :disabled="busy === 'quota'" @click="sendRequest({ kind: 'quota', amount: quotaAmount }, 'quota')">{{ d.quota.request }}</button>
              </div>
            </div>
          </div>
          <form class="card p-4" @submit.prevent="createBatch">
            <p class="flex flex-wrap items-baseline gap-2 text-sm font-semibold text-base-200">{{ d.codes.createTitle }}<span class="text-xs font-normal text-base-400">{{ d.codes.createHint }}</span></p>
            <div class="mt-3 flex flex-wrap gap-2">
              <input v-model="batchForm.label" class="field max-w-xs flex-1" maxlength="60" :placeholder="d.codes.label" />
              <input v-model.number="batchForm.count" class="field w-24" type="number" min="1" max="500" :aria-label="d.codes.count" />
              <button type="submit" class="btn btn-primary" :disabled="busy === 'batch' || dash.state !== 'active'">{{ d.codes.create }}</button>
            </div>
            <div v-if="createdCodes.length" class="created mt-3">
              <p class="text-sm text-ok">{{ fill(d.codes.created, { n: createdCodes.length }) }}</p>
              <pre class="code-list mt-2">{{ createdCodes.join('\n') }}</pre>
              <div class="mt-2 flex gap-2">
                <button type="button" class="btn btn-ghost" @click="copy(createdCodes.join('\n'), 'codes')"><SiteIcon :name="copied === 'codes' ? 'check' : 'copy'" class="size-4" />{{ d.codes.copyAll }}</button>
                <a v-if="createdBatch" :href="`${base}/batches/${createdBatch}.csv`" class="btn btn-ghost"><SiteIcon name="download" class="size-4" />{{ d.codes.csv }}</a>
              </div>
            </div>
          </form>
          <div class="card overflow-x-auto p-4">
            <p class="text-sm font-semibold text-base-200">{{ d.codes.batches }}</p>
            <p v-if="!dash.batches?.length" class="mt-2 text-sm text-base-400">{{ d.codes.none }}</p>
            <table v-else class="tbl mt-2 w-full text-sm">
              <thead><tr><th>{{ d.codes.batchLabel }}</th><th>{{ d.codes.batchCount }}</th><th>{{ d.codes.batchRedeemed }}</th><th>{{ d.codes.batchVia }}</th><th>{{ d.codes.batchDate }}</th><th /></tr></thead>
              <tbody>
                <tr v-for="b in dash.batches" :key="b.id">
                  <td>{{ b.label }}</td>
                  <td class="tabular-nums">{{ num(b.count) }}</td>
                  <td class="tabular-nums">{{ num(b.redeemed) }} <span class="text-xs text-base-400">({{ pct(b.redeemed, b.count) }} %)</span></td>
                  <td>{{ d.codes.via[b.via] }}</td>
                  <td>{{ date(b.createdAt) }}</td>
                  <td class="text-right whitespace-nowrap">
                    <a :href="`${base}/batches/${b.id}.csv`" class="btn btn-ghost h-8 px-3 text-xs">{{ d.codes.csv }}</a>
                    <button v-if="!b.blockedAt" type="button" class="btn btn-ghost h-8 px-3 text-xs" :disabled="busy === `block:${b.id}`" @click="blockBatch(b)">{{ d.codes.block }}</button>
                  </td>
                </tr>
              </tbody>
            </table>
            <p class="mt-2 text-xs text-base-400">{{ d.codes.blockedNote }}</p>
          </div>
        </section>

        <!-- Werbe-Links -->
        <section v-else-if="tab === 'links' && dash.links" class="mt-6 grid gap-4">
          <div class="card p-4">
            <p class="text-sm font-semibold text-base-200">{{ d.links.title }}</p>
            <p class="mt-2 flex flex-wrap items-center gap-2"><code class="big-code text-base">{{ goUrl() }}</code>
              <button type="button" class="btn btn-ghost" @click="copy(goUrl(), 'go')"><SiteIcon :name="copied === 'go' ? 'check' : 'copy'" class="size-4" />{{ copied === 'go' ? d.links.copied : d.links.copy }}</button></p>
            <p class="mt-2 text-xs text-base-400">{{ fill(d.links.text, { name: dash.name, code: dash.code ?? '' }) }}</p>
          </div>
          <div class="card overflow-x-auto p-4">
            <form class="flex flex-wrap items-center gap-2" @submit.prevent="addCampaign">
              <p class="flex-1 text-sm font-semibold text-base-200">{{ d.links.campaigns }}</p>
              <input v-model="campaign" class="field w-48" maxlength="24" pattern="[a-z0-9\-]{1,24}" required :placeholder="d.links.placeholder" />
              <button type="submit" class="btn btn-primary" :disabled="busy === 'link'">{{ d.links.add }}</button>
            </form>
            <table class="tbl mt-3 w-full text-sm">
              <thead><tr><th>{{ d.links.link }}</th><th>{{ d.links.clicks }}</th><th>{{ d.links.downloads }}</th><th>{{ d.links.rate }}</th><th /></tr></thead>
              <tbody>
                <tr v-for="c in dash.links.campaigns" :key="c.campaign">
                  <td><code class="text-xs">{{ dash.links.path }}{{ c.campaign ? `?c=${c.campaign}` : '' }}</code><span v-if="!c.campaign" class="ml-2 text-xs text-base-400">{{ d.links.noCampaign }}</span></td>
                  <td class="tabular-nums">{{ num(c.clicks30d) }}</td>
                  <td class="tabular-nums">{{ num(c.downloads30d) }}</td>
                  <td class="tabular-nums">{{ pct(c.downloads30d, c.clicks30d) }} %</td>
                  <td class="text-right whitespace-nowrap">
                    <button type="button" class="btn btn-ghost h-8 px-3 text-xs" @click="copy(goUrl(c.campaign), `c:${c.campaign}`)">{{ copied === `c:${c.campaign}` ? d.links.copied : d.links.copy }}</button>
                    <button v-if="c.campaign" type="button" class="btn btn-ghost h-8 px-3 text-xs" @click="removeCampaign(c.campaign)">{{ d.links.remove }}</button>
                  </td>
                </tr>
              </tbody>
            </table>
            <p class="mt-3 text-xs text-base-400">{{ d.links.last30 }}: {{ num(linkTotals.clicks) }} {{ d.links.clicks }} · {{ num(linkTotals.downloads) }} {{ d.links.downloads }}</p>
          </div>
          <p class="note-box">{{ d.links.privacy }}</p>
        </section>

        <!-- Partner-Server -->
        <section v-else-if="tab === 'server' && dash.server" class="mt-6 grid gap-4 md:grid-cols-2">
          <form class="card p-4" @submit.prevent="sendServer">
            <p class="flex flex-wrap items-center gap-2 text-sm font-semibold text-base-200">{{ d.server.title }}
              <span v-if="openOf('server')" class="pill pill-warn">{{ d.server.waiting }}</span>
              <span v-else-if="dash.server.current?.hidden" class="pill pill-warn">{{ d.server.hidden }}</span>
              <span v-else-if="dash.server.current" class="pill pill-ok">{{ d.server.live }}</span>
            </p>
            <label class="lbl">{{ d.server.name }}<input v-model="serverForm.name" class="field" maxlength="40" required /></label>
            <label class="lbl">{{ d.server.address }}<input v-model="serverForm.address" class="field" maxlength="261" required placeholder="play.example.com" /></label>
            <div class="grid grid-cols-2 gap-2">
              <label class="lbl">{{ d.server.minVersion }}<input v-model="serverForm.minVersion" class="field" maxlength="15" required pattern="\d{1,3}(\.\d{1,3}){1,3}" /></label>
              <label class="lbl">{{ d.server.maxVersion }}<input v-model="serverForm.maxVersion" class="field" maxlength="15" required pattern="\d{1,3}(\.\d{1,3}){1,3}" /></label>
            </div>
            <label class="lbl">{{ d.server.motd }}<input v-model="serverForm.motd" class="field" maxlength="60" /></label>
            <div class="mt-3 flex flex-wrap items-center gap-2">
              <button v-if="!openOf('server')" type="submit" class="btn btn-primary" :disabled="busy === 'server'">{{ d.server.submit }}</button>
              <button v-else type="button" class="btn btn-ghost" @click="withdraw(openOf('server')!)">{{ d.withdraw }}</button>
              <span class="text-xs text-base-400">{{ d.server.keepOld }}</span>
            </div>
            <p v-if="lastDecided('server')?.status === 'rejected'" class="mt-2 text-xs text-redstone-300">{{ fill(d.rejected, { reason: lastDecided('server')!.reason ?? '' }) }}</p>
          </form>
          <div class="card p-4">
            <p class="text-sm font-semibold text-base-200">{{ d.server.preview }}</p>
            <div class="mc mt-3">
              <div class="srv">
                <PartnerLogo :logo="dash.logo" :name="dash.name" :size="40" />
                <div class="min-w-0 flex-1">
                  <p class="nm">{{ serverForm.name || dash.name }}<span class="tag">{{ d.server.tag }}</span></p>
                  <p class="motd">{{ serverForm.motd }}</p>
                </div>
                <span v-if="dash.server.current?.players" class="motd">{{ dash.server.current.players.online }}/{{ dash.server.current.players.max }}</span>
              </div>
            </div>
            <table class="kv mt-3 w-full text-sm">
              <tbody>
                <tr><td>{{ d.server.joins }}</td><td>{{ num(dash.server.joins7d) }}</td></tr>
                <tr><td>{{ d.server.players }}</td><td>{{ num(dash.server.players7d) }}</td></tr>
                <tr>
                  <td>{{ d.server.reach }}</td>
                  <td>
                    <span v-if="!dash.server.current" class="text-base-400">{{ d.server.none }}</span>
                    <span v-else-if="dash.server.current.online" class="pill pill-ok">{{ fill(d.server.online, { ms: dash.server.current.latencyMs ?? '?' }) }}</span>
                    <span v-else-if="dash.server.current.lastCheckedAt" class="pill pill-warn">{{ d.server.offline }}</span>
                    <span v-else class="pill">{{ d.server.unchecked }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <p class="mt-2 text-xs text-base-400">{{ d.server.hideNote }}</p>
          </div>
          <p class="note-box md:col-span-2">{{ d.server.rules }}</p>
        </section>

        <!-- API -->
        <section v-else-if="tab === 'api'" class="mt-6 grid gap-4">
          <div class="card p-4">
            <p class="text-sm font-semibold text-base-200">{{ d.api.keyTitle }}</p>
            <div class="mt-2 flex flex-wrap items-center gap-2">
              <code v-if="shownKey" class="big-code text-sm break-all">{{ shownKey }}</code>
              <code v-else-if="dash.apiKey" class="font-mono text-sm text-base-200">trsp_live_••••••••••••{{ dash.apiKey.hint }}</code>
              <span v-else class="text-sm text-base-400">{{ d.api.noKey }}</span>
              <button v-if="shownKey" type="button" class="btn-icon" :aria-label="d.links.copy" @click="copy(shownKey, 'key')"><SiteIcon :name="copied === 'key' ? 'check' : 'copy'" class="size-4" /></button>
              <span class="flex-1" />
              <button type="button" class="btn btn-ghost" :disabled="busy === 'key'" @click="rotateKey">{{ dash.apiKey ? d.api.rotate : d.api.newKey }}</button>
              <button v-if="dash.apiKey" type="button" class="btn btn-ghost" :disabled="busy === 'key'" @click="revokeKey">{{ d.api.revoke }}</button>
            </div>
            <p v-if="shownKey" class="mt-2 text-xs text-lamp-300">{{ d.api.shownOnce }}</p>
            <p v-if="dash.apiKey" class="mt-1 text-xs text-base-400">{{ fill(d.api.created, { date: date(dash.apiKey.createdAt) }) }}<template v-if="dash.apiKey.lastUsedAt"> · {{ fill(d.api.lastUsed, { date: date(dash.apiKey.lastUsedAt) }) }}</template></p>
            <p class="mt-2 text-xs text-base-400">{{ d.api.keyText }}</p>
          </div>
          <div class="card p-4">
            <p class="text-sm font-semibold text-base-200">{{ d.api.createTitle }}</p>
            <pre class="code-list mt-2">POST {{ apiBase }}/v1/partner-api/codes
Authorization: Bearer trsp_live_…
Content-Type: application/json

{ "count": 1, "label": "Orders", "ref": "order-48213" }

→ 201 { "codes": ["{{ dash.prefix }}-7K2M-9QXA"], "quota": { "total": {{ dash.quota.total }}, "used": {{ dash.quota.used + 1 }} } }</pre>
            <p class="mt-2 text-xs text-base-400">{{ d.api.refText }}</p>
          </div>
          <div class="grid gap-4 md:grid-cols-2">
            <div class="card p-4">
              <p class="text-sm font-semibold text-base-200">{{ d.api.statusTitle }}</p>
              <pre class="code-list mt-2">GET /v1/partner-api/codes/{{ dash.prefix }}-7K2M-9QXA
→ { "redeemed": true, "redeemedAt": "…", "blocked": false, "ref": "order-48213" }</pre>
              <p class="mt-2 text-xs text-base-400">{{ d.api.statusText }}</p>
            </div>
            <div class="card p-4">
              <p class="text-sm font-semibold text-base-200">{{ d.api.quotaTitle }}</p>
              <pre class="code-list mt-2">GET /v1/partner-api/quota
→ { "total": {{ dash.quota.total }}, "used": {{ dash.quota.used }}, "requested": {{ !!dash.quota.requested }} }</pre>
            </div>
          </div>
          <p class="note-box">{{ d.api.limits }}</p>
        </section>

        <!-- Webhook -->
        <section v-else-if="tab === 'webhook' && dash.webhook" class="mt-6 grid gap-4">
          <div class="grid gap-4 md:grid-cols-2">
            <form class="card p-4" @submit.prevent="saveHook">
              <p class="text-sm font-semibold text-base-200">{{ d.webhook.title }}</p>
              <label class="lbl">{{ d.webhook.url }}<input v-model="hookForm.url" class="field" type="url" maxlength="500" required placeholder="https://" /></label>
              <p class="lbl">{{ d.webhook.events }}</p>
              <label v-for="ev in HOOK_EVENTS" :key="ev" class="chk"><input v-model="hookForm.events" type="checkbox" :value="ev" /><code>{{ ev }}</code> – {{ d.webhook.ev[ev] }}</label>
              <template v-if="dash.webhook.config">
                <p class="lbl">{{ d.webhook.secret }}</p>
                <div class="flex flex-wrap items-center gap-2">
                  <code v-if="shownSecret" class="big-code text-xs break-all">{{ shownSecret }}</code>
                  <code v-else class="font-mono text-sm text-base-200">whsec_••••••••{{ dash.webhook.config.secretHint }}</code>
                  <button v-if="shownSecret" type="button" class="btn-icon" :aria-label="d.links.copy" @click="copy(shownSecret, 'secret')"><SiteIcon :name="copied === 'secret' ? 'check' : 'copy'" class="size-4" /></button>
                  <button type="button" class="btn btn-ghost h-8 px-3 text-xs" :disabled="busy === 'hook'" @click="rotateSecret">{{ d.webhook.newSecret }}</button>
                </div>
              </template>
              <p v-if="shownSecret" class="mt-2 text-xs text-lamp-300">{{ d.webhook.secretOnce }}</p>
              <div class="mt-3 flex flex-wrap gap-2">
                <button type="submit" class="btn btn-primary" :disabled="busy === 'hook'">{{ d.webhook.save }}</button>
                <button v-if="dash.webhook.config" type="button" class="btn btn-ghost" :disabled="busy === 'hook-test'" @click="testHook">{{ d.webhook.test }}</button>
                <button v-if="dash.webhook.config" type="button" class="btn btn-ghost" @click="removeHook">{{ d.webhook.remove }}</button>
              </div>
            </form>
            <div class="card p-4">
              <p class="text-sm font-semibold text-base-200">{{ d.webhook.what }}</p>
              <pre class="code-list mt-2">POST {{ hookForm.url || 'https://…' }}
User-Agent: TRS-Webhooks/1
X-TRS-Timestamp: 1763719924
X-TRS-Signature: sha256=HMAC(secret, "timestamp.body")

{ "type": "code.redeemed",
  "at": "2026-11-21T10:12:04.000Z",
  "code": "{{ dash.prefix }}-7K2M-9QXA",
  "ref": "order-48213" }</pre>
              <p class="mt-2 text-xs text-base-400">{{ d.webhook.whatText }}</p>
            </div>
          </div>
          <div class="card overflow-x-auto p-4">
            <p class="text-sm font-semibold text-base-200">{{ d.webhook.log }}</p>
            <p v-if="!dash.webhook.deliveries.length" class="mt-2 text-sm text-base-400">{{ d.webhook.none }}</p>
            <table v-else class="tbl mt-2 w-full text-sm">
              <thead><tr><th>{{ d.webhook.time }}</th><th>{{ d.webhook.event }}</th><th>{{ d.webhook.answer }}</th><th>{{ d.webhook.attempts }}</th><th /></tr></thead>
              <tbody>
                <tr v-for="x in dash.webhook.deliveries" :key="x.id">
                  <td class="font-mono text-xs">{{ new Date(x.updatedAt).toLocaleString(lang === 'en' ? 'en-GB' : lang) }}</td>
                  <td class="font-mono text-xs">{{ x.event }}</td>
                  <td>
                    <span class="pill" :class="x.status === 'delivered' ? 'pill-ok' : 'pill-warn'">{{ d.webhook.st[x.status] }}</span>
                    <span class="ml-1 text-xs text-base-400">{{ x.lastStatus ?? x.error ?? '' }}</span>
                  </td>
                  <td class="tabular-nums">{{ x.attempts }}</td>
                  <td class="text-right"><button v-if="x.status === 'failed'" type="button" class="btn btn-ghost h-8 px-3 text-xs" :disabled="busy === `dl:${x.id}`" @click="retryDelivery(x.id)">{{ d.webhook.retry }}</button></td>
                </tr>
              </tbody>
            </table>
            <p class="mt-2 text-xs text-base-400">{{ d.webhook.retryText }}</p>
          </div>
        </section>

        <!-- Vorstellung -->
        <section v-else-if="tab === 'promo'" class="mt-6 grid gap-4">
          <div class="grid gap-4 md:grid-cols-3">
            <div v-for="c in (['wiki', 'blog', 'newsletter'] as const)" :key="c" class="card p-4">
              <p class="flex items-center gap-2 text-sm font-semibold text-base-200">{{ d.promo[c] }}<span class="pill" :class="promoState(c).cls">{{ promoState(c).label }}</span></p>
              <p class="mt-2 text-xs text-base-400">{{ d.promo[`${c}Text`] }}</p>
              <a v-if="c === 'wiki' && dash.wikiUrl" :href="dash.wikiUrl" target="_blank" rel="noopener" class="mt-2 inline-block text-xs text-redstone-300 hover:underline">{{ dash.wikiUrl }}</a>
              <button v-if="openOf('promo', c)" type="button" class="btn btn-ghost mt-3" @click="withdraw(openOf('promo', c)!)">{{ d.withdraw }}</button>
              <button v-else type="button" class="btn btn-ghost mt-3" @click="promoForm.channel = c">{{ d.promo.request }}</button>
            </div>
          </div>
          <form class="card p-4" @submit.prevent="sendPromo">
            <p class="text-sm font-semibold text-base-200">{{ d.promo.formTitle }}</p>
            <div class="grid gap-2 sm:grid-cols-[12rem_1fr]">
              <label class="lbl">{{ d.promo.channel }}
                <select v-model="promoForm.channel" class="field"><option v-for="c in (['blog', 'newsletter', 'wiki'] as const)" :key="c" :value="c" :disabled="!!openOf('promo', c)">{{ d.promo[c] }}</option></select>
              </label>
              <label class="lbl">{{ d.promo.topic }}<input v-model="promoForm.topic" class="field" maxlength="120" required :placeholder="d.promo.topicPlaceholder" /></label>
            </div>
            <label class="lbl">{{ d.promo.text }}<textarea v-model="promoForm.text" class="field" rows="4" maxlength="2000" required /></label>
            <button type="submit" class="btn btn-primary mt-3" :disabled="busy === 'promo' || !!openOf('promo', promoForm.channel)">{{ d.promo.send }}</button>
            <p class="mt-2 text-xs text-base-400">{{ d.promo.teamWrites }}</p>
          </form>
          <div v-if="requestsOf('promo').length" class="card p-4">
            <p class="text-sm font-semibold text-base-200">{{ d.promo.history }}</p>
            <ul class="mt-2 space-y-1 text-sm">
              <li v-for="r in requestsOf('promo')" :key="r.id" class="flex flex-wrap gap-2 text-base-300">
                <span>{{ r.channel ? d.promo[r.channel] : '' }}</span>·<span class="text-base-100">{{ r.data.topic }}</span>·<span>{{ date(r.createdAt) }}</span>·<span>{{ d.status[r.status] }}</span>
                <span v-if="r.reason" class="text-redstone-300">{{ r.reason }}</span>
              </li>
            </ul>
          </div>
        </section>

        <!-- Kosmetik -->
        <section v-else-if="tab === 'cosmetics'" class="mt-6 grid gap-4">
          <div class="card p-4">
            <p class="text-sm font-semibold text-base-200">{{ d.cosmetics.assigned }}</p>
            <p v-if="!dash.cosmetics.length" class="mt-2 text-sm text-base-400">{{ d.cosmetics.none }}</p>
            <ul v-else class="mt-3 flex flex-wrap gap-3">
              <li v-for="c in dash.cosmetics" :key="`${c.kind}:${c.id}`" class="item-card">
                <PartnerCapeThumb v-if="c.kind === 'cape'" :id="c.id" :width="40" />
                <span v-else class="item-icon"><SiteIcon name="crown" class="size-6" /></span>
                <span><span class="block font-semibold text-base-50">{{ c.name }}</span><span class="text-xs text-base-400">{{ c.kind === 'cape' ? p.pub.cape : p.pub.cosmetic }}</span></span>
              </li>
            </ul>
          </div>
          <div class="grid gap-4 md:grid-cols-2">
            <form class="card p-4" @submit.prevent="uploadCape">
              <p class="flex items-center gap-2 text-sm font-semibold text-base-200">{{ d.cosmetics.capeTitle }}<span v-if="openOf('cape')" class="pill pill-warn">{{ d.status.open }}</span></p>
              <p class="mt-2 text-xs text-base-400">{{ d.cosmetics.capeText }}</p>
              <div v-if="openOf('cape')?.hasImage" class="mt-3 flex items-center gap-3">
                <img :src="`${base}/requests/${openOf('cape')!.id}/image.png`" alt="" class="req-img" />
                <button type="button" class="btn btn-ghost" @click="withdraw(openOf('cape')!)">{{ d.withdraw }}</button>
              </div>
              <label class="lbl">{{ d.cosmetics.capeName }}<input v-model="capeName" class="field" maxlength="32" /></label>
              <label class="lbl">{{ d.cosmetics.frameTime }}<input v-model.number="capeFrameMs" class="field w-32" type="number" min="50" max="10000" /></label>
              <label class="lbl">{{ d.cosmetics.file }}<input type="file" accept="image/png" class="field" @change="capeFile = pickFile($event)" /></label>
              <button type="submit" class="btn btn-primary mt-3" :disabled="!capeFile || busy === 'cape'">{{ d.cosmetics.upload }}</button>
              <p v-if="lastDecided('cape')?.status === 'rejected'" class="mt-2 text-xs text-redstone-300">{{ fill(d.rejected, { reason: lastDecided('cape')!.reason ?? '' }) }}</p>
            </form>
            <form class="card p-4" @submit.prevent="sendWish">
              <p class="flex items-center gap-2 text-sm font-semibold text-base-200">{{ d.cosmetics.wishTitle }}<span v-if="openOf('cosmetic_wish')" class="pill pill-warn">{{ d.status.open }}</span></p>
              <p class="mt-2 text-xs text-base-400">{{ d.cosmetics.wishText }}</p>
              <p v-if="openOf('cosmetic_wish')" class="req-detail mt-3">{{ openOf('cosmetic_wish')!.data.text }}</p>
              <template v-else>
                <label class="lbl">{{ d.cosmetics.wish }}<textarea v-model="wish" class="field" rows="4" maxlength="1000" required /></label>
                <button type="submit" class="btn btn-primary mt-3" :disabled="busy === 'wish'">{{ d.cosmetics.sendWish }}</button>
              </template>
            </form>
          </div>
          <p class="note-box">{{ d.cosmetics.proprietary }}</p>
        </section>

        <!-- Profil -->
        <section v-else-if="tab === 'profile'" class="mt-6 grid gap-4 md:grid-cols-[1.5fr_1fr]">
          <form class="card p-4" @submit.prevent="sendProfile">
            <p class="flex items-center gap-2 text-sm font-semibold text-base-200">{{ d.profile.title }}<span v-if="openOf('profile')" class="pill pill-warn">{{ d.status.open }}</span></p>
            <label class="lbl">{{ d.profile.name }}<input :value="dash.name" class="field" disabled /></label>
            <label class="lbl">{{ d.profile.category }}<input :value="p.category[dash.category]" class="field" disabled /></label>
            <label class="lbl">{{ d.profile.textDe }} <span class="text-[11px]">{{ fill(d.profile.chars, { n: profileForm.de.length }) }}</span><textarea v-model="profileForm.de" class="field" rows="5" maxlength="1200" /></label>
            <label class="lbl">{{ d.profile.textEn }} <span class="text-[11px]">{{ fill(d.profile.chars, { n: profileForm.en.length }) }}</span><textarea v-model="profileForm.en" class="field" rows="5" maxlength="1200" /></label>
            <p class="lbl">{{ d.profile.links }}</p>
            <div v-for="(l, i) in profileForm.links" :key="i" class="mt-1 flex gap-2">
              <input v-model="l.label" class="field w-36" maxlength="40" :placeholder="d.profile.linkLabel" />
              <input v-model="l.url" class="field flex-1" type="url" maxlength="300" placeholder="https://" />
              <button type="button" class="btn-icon" :aria-label="d.withdraw" @click="profileForm.links.splice(i, 1)"><SiteIcon name="close" class="size-4" /></button>
            </div>
            <button v-if="profileForm.links.length < 6" type="button" class="btn btn-ghost mt-2 h-8 px-3 text-xs" @click="profileForm.links.push({ label: '', url: '' })"><SiteIcon name="plus" class="size-4" />{{ d.profile.addLink }}</button>
            <div class="mt-3 flex flex-wrap items-center gap-2">
              <button v-if="!openOf('profile')" type="submit" class="btn btn-primary" :disabled="busy === 'profile'">{{ d.profile.submit }}</button>
              <button v-else type="button" class="btn btn-ghost" @click="withdraw(openOf('profile')!)">{{ d.withdraw }}</button>
              <span class="text-xs text-base-400">{{ d.profile.onlyAfter }}</span>
            </div>
            <p v-if="lastDecided('profile')?.status === 'rejected'" class="mt-2 text-xs text-redstone-300">{{ fill(d.rejected, { reason: lastDecided('profile')!.reason ?? '' }) }}</p>
          </form>
          <form class="card p-4" @submit.prevent="uploadLogo">
            <p class="flex items-center gap-2 text-sm font-semibold text-base-200">{{ d.profile.logo }}<span v-if="openOf('logo')" class="pill pill-warn">{{ d.status.open }}</span></p>
            <div class="mt-3 flex items-center gap-4">
              <PartnerLogo :logo="dash.logo" :name="dash.name" :size="96" />
              <img v-if="openOf('logo')?.hasImage" :src="`${base}/requests/${openOf('logo')!.id}/image.png`" alt="" class="size-24 rounded-full border-2 border-dashed border-lamp-400 object-cover" />
            </div>
            <p class="mt-3 text-xs text-base-400">{{ d.profile.logoText }}</p>
            <input type="file" accept="image/png" class="field mt-3" :aria-label="d.profile.replaceLogo" @change="logoFile = pickFile($event)" />
            <button type="submit" class="btn btn-ghost mt-3" :disabled="!logoFile || busy === 'logo'">{{ d.profile.replaceLogo }}</button>
          </form>
        </section>

        <!-- Zugänge -->
        <section v-else-if="tab === 'members' && dash.members" class="mt-6">
          <div class="card p-4">
            <p class="text-sm font-semibold text-base-200">{{ d.members.title }}</p>
            <ul class="mt-3 divide-y divide-base-800">
              <li v-if="dash.owner" class="flex items-center gap-3 py-2">
                <PlayerHead :uuid="dash.owner.uuid" :name="dash.owner.name" :size="24" />
                <span class="flex-1 font-semibold text-base-50">{{ dash.owner.name }}</span>
                <span class="pill pill-ok">{{ d.members.owner }}</span>
              </li>
              <li v-for="m in dash.members" :key="m.uuid" class="py-2">
                <div class="flex flex-wrap items-center gap-3">
                  <PlayerHead :uuid="m.uuid" :name="m.name" :size="24" />
                  <span class="flex-1 font-semibold text-base-50">{{ m.name }}<span v-if="m.status === 'pending'" class="pill ml-2">{{ d.members.pending }}</span></span>
                  <button type="button" class="btn btn-ghost h-8 px-3 text-xs" :disabled="busy === `m:${m.uuid}`" @click="saveRights(m.uuid)">{{ d.members.save }}</button>
                  <button type="button" class="btn btn-ghost h-8 px-3 text-xs" :disabled="busy === `m:${m.uuid}`" @click="removeMember(m)">{{ d.members.remove }}</button>
                </div>
                <div class="mt-2 flex flex-wrap gap-x-4 gap-y-1">
                  <label v-for="r in PARTNER_RIGHTS" :key="r" class="chk"><input v-model="editRights[m.uuid]" type="checkbox" :value="r" />{{ d.members.right[r] }}</label>
                </div>
              </li>
            </ul>
            <form v-if="dash.viewer.owner" class="mt-4 border-t border-base-800 pt-4" @submit.prevent="invite">
              <div class="flex flex-wrap gap-2">
                <input v-model="inviteName" class="field w-56" maxlength="16" pattern="[A-Za-z0-9_]{1,16}" required :placeholder="d.members.invitePlaceholder" />
                <button type="submit" class="btn btn-primary" :disabled="busy === 'invite' || !inviteRights.length">{{ d.members.invite }}</button>
              </div>
              <div class="mt-2 flex flex-wrap gap-x-4 gap-y-1">
                <label v-for="r in PARTNER_RIGHTS" :key="r" class="chk"><input v-model="inviteRights" type="checkbox" :value="r" />{{ d.members.right[r] }}</label>
              </div>
            </form>
            <p v-else class="mt-3 text-xs text-base-400">{{ d.members.teamNoInvite }}</p>
            <p class="mt-3 text-xs text-base-400">{{ d.members.text }}</p>
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
  font-size: 0.85rem;
  background: var(--color-base-850);
  color: var(--color-base-300);
}
.state-warn {
  color: var(--color-lamp-300);
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
.pill-warn {
  background: color-mix(in srgb, var(--color-lamp-400) 14%, transparent);
  color: var(--color-lamp-300);
}
.big-code {
  font-family: var(--font-mono);
  font-size: 1.1rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--color-lamp-300);
  border: 1px dashed var(--color-base-600);
  border-radius: 0.5rem;
  padding: 0.2rem 0.7rem;
}
.lbl {
  display: grid;
  gap: 0.3rem;
  margin-top: 0.7rem;
  font-size: 0.75rem;
  color: var(--color-base-400);
}
.chk {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  margin-top: 0.3rem;
  font-size: 0.85rem;
  color: var(--color-base-200);
}
.meter {
  height: 8px;
  border-radius: 99px;
  background: var(--color-base-800);
  overflow: hidden;
}
.meter > div {
  height: 100%;
  background: linear-gradient(90deg, var(--color-redstone-600), var(--color-redstone-400));
}
.bars {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 120px;
}
.bar {
  flex: 1;
  border-radius: 3px 3px 0 0;
  background: linear-gradient(180deg, var(--color-lamp-300) var(--split), var(--color-redstone-500) var(--split));
}
.legend {
  display: flex;
  gap: 1rem;
  font-size: 0.75rem;
  color: var(--color-base-400);
}
.legend i {
  display: inline-block;
  width: 10px;
  height: 10px;
  margin-right: 0.35rem;
  border-radius: 3px;
  vertical-align: -1px;
}
.lg-fixed {
  background: var(--color-redstone-500);
}
.lg-once {
  background: var(--color-lamp-300);
}
.kv td {
  padding: 0.35rem 0;
  border-bottom: 1px solid var(--color-base-850);
  color: var(--color-base-300);
}
.kv td:last-child {
  text-align: right;
  font-weight: 700;
  color: var(--color-base-50);
}
.tbl th {
  text-align: left;
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--color-base-400);
  padding: 0.45rem 0.5rem;
  border-bottom: 1px solid var(--color-base-800);
}
.tbl td {
  padding: 0.5rem;
  border-bottom: 1px solid var(--color-base-850);
  color: var(--color-base-200);
}
.code-list {
  max-height: 16rem;
  overflow: auto;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.7rem 0.85rem;
  font-family: var(--font-mono);
  font-size: 0.75rem;
  line-height: 1.55;
  color: var(--color-base-200);
  white-space: pre;
}
.note-box {
  border-left: 3px solid var(--color-redstone-500);
  border-radius: 0 0.6rem 0.6rem 0;
  background: var(--color-base-900);
  padding: 0.6rem 0.9rem;
  font-size: 0.85rem;
  color: var(--color-base-300);
}
.mc {
  border-radius: 0.6rem;
  padding: 0.6rem;
  background: repeating-conic-gradient(#2b1f17 0 25%, #33261c 0 50%) 0 0 / 32px 32px;
  font-family: var(--font-mono);
}
.srv {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.4rem;
  border: 2px solid color-mix(in srgb, var(--color-redstone-400) 55%, transparent);
  background: rgb(0 0 0 / 0.55);
}
.srv :deep(.partner-logo) {
  border-radius: 0;
}
.nm {
  color: #fff;
  font-weight: 700;
  font-size: 0.85rem;
}
.motd {
  color: #aaa;
  font-size: 0.75rem;
}
.tag {
  margin-left: 0.4rem;
  border-radius: 3px;
  padding: 0 0.35rem;
  font-size: 0.6rem;
  font-weight: 700;
  color: #fff;
  background: var(--color-redstone-500);
  vertical-align: 1px;
}
.item-card {
  display: flex;
  align-items: center;
  gap: 0.7rem;
  border-radius: 0.7rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-850);
  padding: 0.55rem 0.8rem;
}
.item-icon {
  display: grid;
  place-items: center;
  width: 40px;
  height: 64px;
  border-radius: 0.3rem;
  background: var(--color-base-800);
  color: var(--color-lamp-300);
}
.req-img {
  width: 72px;
  height: 72px;
  object-fit: contain;
  image-rendering: pixelated;
  border-radius: 0.4rem;
  background: var(--color-base-950);
}
.req-detail {
  border-radius: 0.6rem;
  background: var(--color-base-950);
  padding: 0.6rem 0.8rem;
  font-size: 0.85rem;
  color: var(--color-base-200);
  white-space: pre-line;
  overflow-wrap: anywhere;
}
</style>
