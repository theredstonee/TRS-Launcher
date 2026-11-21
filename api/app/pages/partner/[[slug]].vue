<script setup lang="ts">
// Partner-Dashboard (§44.4) mit der Website-Sitzung, im Stil des Creator-Dashboards: Übersicht (erste Schritte,
// Kennzahlen mit Vergleich zu den 30 Tagen davor, Verlauf), Codes, Werbe-Links, Partner-Server (nur wenn vom Team
// erlaubt), API, Webhook, Vorstellung, Kosmetik (3D-Vorschau, Partner-Umhang aus eigenen Uploads), Profil (Logo-Zuschnitt,
// Vollständigkeit, Live-Vorschau des Fensters), Zugänge.
// /partner/<slug> bei mehreren Zugängen; Team-Mitglieder mit partners.manage öffnen jedes Dashboard im Team-Modus.
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

async function loadDash(keepDraft = true) {
  if (!slug.value) {
    dash.value = null
    return
  }
  // Ungesendete Profil-Änderungen überstehen das Neuladen (z. B. nach dem Logo-Upload).
  const draft = keepDraft && profileDirty.value && !profileLocked.value
    ? { de: profileForm.de, en: profileForm.en, links: profileForm.links.map((l) => ({ ...l })) }
    : null
  dash.value = await api<PartnerDashboard>(`${base.value}/dashboard`)
  resetForms()
  if (draft && !openOf('profile')) Object.assign(profileForm, draft)
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
  selectable.value = null
  dropCropped()
  void loadDash(false).catch(fail)
})
function switchTo(s: string) {
  void navigateTo(`/partner/${s}`)
}
function openTab(t: Tab) {
  tab.value = t
  notice.value = ''
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

// ---------------------------------------------------------------- Übersicht
const stats = computed(() => dash.value?.stats ?? null)
const pct = (a: number, b: number) => (b > 0 ? Math.round((a / b) * 100) : 0)

type CheckKey = 'logo' | 'textDe' | 'textEn' | 'links' | 'server' | 'webhook' | 'api' | 'cosmetics'
const PART_CHECK: Record<ProfilePart, CheckKey> = { logo: 'logo', de: 'textDe', en: 'textEn', links: 'links' }
const checklist = computed(() => {
  const x = dash.value
  if (!x) return []
  const items: { key: CheckKey, tab: Tab, right: PartnerRight, done: boolean, review: boolean }[] = [
    // Profil-Teile wie im Reiter „Profil“ (gleiche Quelle).
    ...profileParts.value.map((q) => ({ key: PART_CHECK[q.key], tab: 'profile' as Tab, right: 'profile' as PartnerRight, done: q.live, review: q.review })),
    { key: 'server', tab: 'server', right: 'server', done: !!x.server?.current, review: !!openOf('server') },
    { key: 'webhook', tab: 'webhook', right: 'webhook', done: !!x.webhook?.config, review: false },
    { key: 'api', tab: 'api', right: 'api', done: !!x.apiKey, review: false },
    { key: 'cosmetics', tab: 'cosmetics', right: 'cosmetics', done: x.cosmetics.some((c) => c.kind === 'cape'), review: !!openOf('cape') },
  ]
  // Partner-Server nur, wenn das Team ihn erlaubt; Schritte ohne Recht kann der Betrachter nicht prüfen.
  return items.filter((i) => (i.key !== 'server' || x.serverAllowed) && may(i.right))
})
const checkDone = computed(() => checklist.value.filter((i) => i.done).length)
const checkPct = computed(() => pct(checkDone.value, checklist.value.length))

function change(now: number, before: number): { text: string, tone: 'up' | 'down' | 'flat' } {
  const c = percentChange(now, before)
  if (c === null) return { text: d.value.compare.new, tone: 'up' }
  if (c === 0) return { text: '±0 %', tone: 'flat' }
  return { text: `${c > 0 ? '+' : '−'}${Math.abs(c)} %`, tone: c > 0 ? 'up' : 'down' }
}
const tiles = computed(() => {
  const s = stats.value
  const x = dash.value
  if (!s || !x) return []
  const c = s.current30d
  const pv = s.previous30d
  const m = d.value.compare.metrics
  const list: { key: string, label: string, value: string, hint?: string, delta?: ReturnType<typeof change> }[] = [
    { key: 'supporters', label: d.value.overview.supporters, value: num(s.supporters), hint: fill(d.value.overview.supportersHint, { n: num(s.newSupporters7d) }) },
    { key: 'newSupporters', label: m.newSupporters!, value: num(c.newSupporters), delta: change(c.newSupporters, pv.newSupporters) },
    { key: 'redemptions', label: m.redemptions!, value: num(c.redemptions), delta: change(c.redemptions, pv.redemptions), hint: `${num(c.fixed)} ${m.fixed} · ${num(c.onetime)} ${m.onetime}` },
    { key: 'newPlayers', label: m.newPlayers!, value: num(c.newPlayers), delta: change(c.newPlayers, pv.newPlayers), hint: d.value.overview.newPlayersHint },
  ]
  if (x.serverAllowed) list.push({ key: 'serverJoins', label: m.serverJoins!, value: num(c.serverJoins), delta: change(c.serverJoins, pv.serverJoins) })
  list.push({ key: 'downloads', label: m.downloads!, value: num(c.downloads), delta: change(c.downloads, pv.downloads), hint: `${num(c.clicks)} ${m.clicks}` })
  list.push({ key: 'worn', label: d.value.overview.worn, value: `${pct(s.capeWorn, s.supporters)} %`, hint: fill(d.value.overview.wornHint, { p: pct(s.cosmeticWorn, s.supporters) }) })
  return list
})
const compareRows = computed(() => {
  const s = stats.value
  if (!s) return []
  const keys: (keyof PartnerPeriod)[] = ['newSupporters', 'redemptions', 'fixed', 'onetime', 'newPlayers', ...(dash.value?.serverAllowed ? ['serverJoins' as const] : []), 'clicks', 'downloads', 'left']
  return keys.map((k) => ({ key: k, sub: k === 'fixed' || k === 'onetime', now: s.current30d[k], before: s.previous30d[k], delta: change(s.current30d[k], s.previous30d[k]) }))
})
const days = computed(() => stats.value?.daily.map((x) => x.day) ?? [])
const series = (f: (x: PartnerStatDay) => number | null) => stats.value?.daily.map(f) ?? []

// ---------------------------------------------------------------- Codes
const batchForm = reactive({ label: '', count: 10 })
const createdCodes = ref<string[]>([])
const createdBatch = ref<number | null>(null)
const codeChange = ref('')
const codeAction = ref<'change' | 'add'>('add')
const fixedCodes = computed<PartnerFixedCode[]>(() => dash.value?.fixedCodes ?? (dash.value?.code ? [{ code: dash.value.code, main: true, active: true, redemptions: 0, redemptions30d: 0, createdAt: '' }] : []))
const codeLimitReached = computed(() => fixedCodes.value.length >= PARTNER_FIXED_CODES_MAX)
watch(codeLimitReached, (full) => {
  if (full) codeAction.value = 'change'
}, { immediate: true })
const requestCode = () => sendRequest({ kind: 'code_change', code: codeChange.value.trim().toUpperCase(), action: codeAction.value }, 'code').then(() => {
  if (!error.value) codeChange.value = ''
})
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
const selectable = ref<SelectableCape[] | null>(null)
const pickedCape = ref('')
const capeName = ref('')
const wish = ref('')
const previewKey = ref('')
async function loadSelectable() {
  if (selectable.value || !may('cosmetics')) return
  try {
    selectable.value = (await api<{ capes: SelectableCape[] }>(`${base.value}/capes`)).capes
  } catch (e) {
    selectable.value = []
    fail(e)
  }
}
watch(tab, (t) => {
  if (t === 'cosmetics') void loadSelectable()
})
const pendingCape = computed(() => openOf('cape'))
/** Vorschau-Kandidaten: zugeordnete Teile, dazu der gewählte bzw. eingereichte Umhang. */
const previewItems = computed(() => {
  const out: { key: string, name: string, kind: 'cape' | 'cosmetic', cape: CapeTexture | null, scale: number, hat: SiteHat | null, note?: string }[] = []
  for (const c of dash.value?.cosmetics ?? []) {
    out.push({ key: `${c.kind}:${c.id}`, name: c.name, kind: c.kind, cape: c.cape ? { id: c.id, url: sameOriginPath(c.cape.url), frames: c.cape.frames, frameTimeMs: c.cape.frameTimeMs } : null, scale: c.cape?.scale ?? 1, hat: c.hat })
  }
  const pendingId = pendingCape.value?.data.capeId
  for (const id of new Set([pickedCape.value, typeof pendingId === 'string' ? pendingId : ''])) {
    const s = id ? selectable.value?.find((x) => x.id === id) : undefined
    if (s) out.push({ key: `sel:${s.id}`, name: s.name, kind: 'cape', cape: { id: s.id, url: sameOriginPath(s.url), frames: s.frames, frameTimeMs: s.frameTimeMs }, scale: s.scale, hat: null, note: id === pendingId ? d.value.checklist.review : undefined })
  }
  return out
})
const previewed = computed(() => previewItems.value.find((x) => x.key === previewKey.value) ?? previewItems.value.find((x) => x.cape || x.hat) ?? null)
function pickCape(c: SelectableCape) {
  pickedCape.value = c.id
  capeName.value = c.name
  previewKey.value = `sel:${c.id}`
}
const submitCape = () => sendRequest({ kind: 'cape', capeId: pickedCape.value, name: capeName.value.trim().slice(0, 32) || undefined }, 'cape').then(() => {
  if (!error.value) pickedCape.value = ''
})
const sendWish = () => sendRequest({ kind: 'cosmetic_wish', text: wish.value.trim() }, 'wish').then(() => {
  if (!error.value) wish.value = ''
})

// ---------------------------------------------------------------- Profil
interface EditLink extends PartnerLink { id: number }
let linkSeq = 0
const toEdit = (l: PartnerLink): EditLink => ({ id: ++linkSeq, label: l.label, url: l.url })
const profileForm = reactive<{ de: string, en: string, links: EditLink[] }>({ de: '', en: '', links: [] })
async function putPng(path: string, body: Blob) {
  await apiFetch(path, { method: 'PUT', body, headers: { 'Content-Type': 'image/png', 'X-CSRF-Token': account.value?.csrf ?? '' }, credentials: 'same-origin' })
}
function resetForms() {
  const x = dash.value
  if (!x) return
  const pending = openOf('profile')?.data as { text?: { de: string, en: string }, links?: PartnerLink[] } | undefined
  profileForm.de = pending?.text?.de ?? x.profile.text.de
  profileForm.en = pending?.text?.en ?? x.profile.text.en
  profileForm.links = (pending?.links ?? x.profile.links).map(toEdit)
  const s = x.server?.current
  const ps = openOf('server')?.data as typeof serverForm | undefined
  Object.assign(serverForm, ps ?? (s ? { name: s.name, address: s.address, minVersion: s.minVersion, maxVersion: s.maxVersion, motd: s.motd } : { name: `${x.name} Lobby`, address: '', minVersion: '1.8', maxVersion: '1.21.11', motd: '' }))
  if (x.webhook?.config) {
    hookForm.url = x.webhook.config.url
    hookForm.events = [...x.webhook.config.events]
  }
}

// Links: leere Zeilen fallen weg, sonst Bezeichnung + https-Adresse (wie der Server).
function linkError(l: EditLink): 'label' | 'url' | null {
  if (!l.label.trim() && !l.url.trim()) return null
  if (!isPartnerLinkUrl(l.url)) return 'url'
  if (!l.label.trim()) return 'label'
  return null
}
function fixUrl(l: EditLink) {
  const u = l.url.trim()
  // „example.com/x“ → https://example.com/x
  if (u && !/^[a-z][a-z0-9+.-]*:/i.test(u) && /^[^\s/]+\.[^\s/]{2,}/.test(u)) l.url = `https://${u}`
  else l.url = u
}
function moveLink(i: number, by: -1 | 1) {
  const j = i + by
  if (j < 0 || j >= profileForm.links.length) return
  const list = profileForm.links
  ;[list[i], list[j]] = [list[j]!, list[i]!]
}
const cleanLinks = computed<PartnerLink[]>(() => profileForm.links.filter((l) => l.label.trim() || l.url.trim()).map((l) => ({ label: l.label.trim(), url: l.url.trim() })))
const linksInvalid = computed(() => profileForm.links.some((l) => linkError(l) !== null))
const profileLocked = computed(() => !!openOf('profile'))
const profileDirty = computed(() => {
  const x = dash.value
  if (!x) return false
  return profileForm.de !== x.profile.text.de || profileForm.en !== x.profile.text.en || JSON.stringify(cleanLinks.value) !== JSON.stringify(x.profile.links)
})
const sendProfile = () => {
  if (linksInvalid.value || profileLocked.value || !profileDirty.value) return
  void sendRequest({ kind: 'profile', text: { de: profileForm.de, en: profileForm.en }, links: cleanLinks.value }, 'profile')
}
/** Antrag zurückziehen, der Text bleibt zum Weiterbearbeiten im Formular. */
async function withdrawProfile() {
  const r = openOf('profile')
  if (!r) return
  const keep = { de: profileForm.de, en: profileForm.en, links: profileForm.links.map((l) => ({ ...l })) }
  await withdraw(r)
  if (!error.value) Object.assign(profileForm, keep)
}
function resetProfile() {
  const x = dash.value
  if (!x) return
  profileForm.de = x.profile.text.de
  profileForm.en = x.profile.text.en
  profileForm.links = x.profile.links.map(toEdit)
}
const counterTone = (n: number) => (n >= PARTNER_TEXT_MAX ? 'full' : n >= PARTNER_TEXT_MAX * 0.9 ? 'near' : '')

// Logo: Datei wählen oder hineinziehen → zuschneiden → als Antrag hochladen.
const logoInput = ref<HTMLInputElement | null>(null)
const cropFile = ref<File | null>(null)
const cropOpen = ref(false)
const cropped = shallowRef<{ blob: Blob, url: string, size: number } | null>(null)
const dropActive = ref(false)
async function chooseLogo(f: File | null | undefined) {
  if (!f) return
  error.value = ''
  const bad = await checkLogoFile(f)
  if (bad) {
    error.value = d.value.cropper.errors[bad] ?? ''
    return
  }
  cropFile.value = f
  cropOpen.value = true
}
function onLogoInput(e: Event) {
  const input = e.target as HTMLInputElement
  void chooseLogo(input.files?.[0])
  input.value = ''
}
function onLogoDrop(e: DragEvent) {
  dropActive.value = false
  void chooseLogo(e.dataTransfer?.files?.[0])
}
function onCropped(blob: Blob, size: number) {
  dropCropped()
  cropped.value = { blob, url: URL.createObjectURL(blob), size }
  cropOpen.value = false
}
function onCropError(key: string) {
  cropOpen.value = false
  error.value = d.value.cropper.errors[key] ?? ''
}
function dropCropped() {
  if (cropped.value) URL.revokeObjectURL(cropped.value.url)
  cropped.value = null
}
onBeforeUnmount(dropCropped)
const uploadLogo = () => act('logo', async () => {
  if (!cropped.value) return
  await putPng(`${base.value}/logo`, cropped.value.blob)
  dropCropped()
  cropFile.value = null
  await loadDash()
}, d.value.sent)
const pendingLogoUrl = computed(() => {
  const r = openOf('logo')
  return r?.hasImage ? `${base.value}/requests/${r.id}/image.png` : null
})

// Vollständigkeit: live, in Prüfung, im Formular (nicht gesendet) oder fehlt – auch für die Startliste.
type ProfilePart = 'logo' | 'de' | 'en' | 'links'
type PartState = 'missing' | 'draft' | 'review' | 'done'
const profileParts = computed(() => {
  const x = dash.value
  if (!x) return []
  const pending = openOf('profile')?.data as { text?: { de?: string, en?: string }, links?: PartnerLink[] } | undefined
  const live: Record<ProfilePart, boolean> = { logo: !!x.logo, de: !!x.profile.text.de.trim(), en: !!x.profile.text.en.trim(), links: x.profile.links.length > 0 }
  const review: Record<ProfilePart, boolean> = { logo: !!openOf('logo'), de: !!pending?.text?.de?.trim(), en: !!pending?.text?.en?.trim(), links: !!pending?.links?.length }
  const draft: Record<ProfilePart, boolean> = { logo: !!cropped.value, de: !!profileForm.de.trim(), en: !!profileForm.en.trim(), links: cleanLinks.value.length > 0 }
  return (['logo', 'de', 'en', 'links'] as const).map((key) => {
    const state: PartState = live[key] ? 'done' : review[key] ? 'review' : draft[key] ? 'draft' : 'missing'
    return { key, state, live: live[key], review: review[key] }
  })
})
const profileDone = computed(() => profileParts.value.filter((q) => q.state === 'done').length)
const profilePct = computed(() => pct(profileDone.value, profileParts.value.length || 1))
const partState = (k: ProfilePart) => profileParts.value.find((q) => q.key === k)?.state ?? 'missing'
const PART_TARGET: Record<ProfilePart, string> = { logo: 'pf-logo', de: 'pf-de', en: 'pf-en', links: 'pf-links' }
function focusPart(k: ProfilePart) {
  const el = document.getElementById(PART_TARGET[k])
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  el?.focus({ preventScroll: true })
}

// Live-Vorschau des öffentlichen Fensters mit den aktuellen Eingaben.
const previewLang = ref<'de' | 'en'>('de')
watch(lang, (l) => (previewLang.value = l === 'de' ? 'de' : 'en'), { immediate: true })
const previewLarge = ref(false)
const previewCard = computed<PartnerCard | null>(() => {
  const x = dash.value
  if (!x) return null
  const s = x.server?.current
  return {
    slug: x.slug,
    name: x.name,
    category: x.category,
    logo: cropped.value?.url ?? pendingLogoUrl.value ?? x.logo,
    text: { de: profileForm.de, en: profileForm.en },
    links: cleanLinks.value.filter((l) => l.label && isPartnerLinkUrl(l.url)),
    code: x.code,
    codes: x.code ? [x.code] : [],
    server: x.serverAllowed && s && !s.hidden ? { name: s.name, address: s.address } : null,
    wikiUrl: x.wikiUrl,
    supporters: x.stats?.supporters ?? 0,
    cosmetics: x.cosmetics.map((c) => ({ kind: c.kind, id: c.id, name: c.name, previewUrl: c.previewUrl })),
  }
})

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
        <h2 class="section-title">{{ d.invitesTitle }}</h2>
        <ul class="mt-3 space-y-2">
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
            <p class="text-2xl font-bold break-words text-base-50">{{ dash.name }}</p>
            <p class="text-xs font-bold tracking-[0.16em] text-redstone-400 uppercase">{{ p.category[dash.category] }}</p>
          </div>
          <div v-if="dash.code" class="head-code">
            <PartnerCode :code="dash.code" :max="1.05" />
            <button type="button" class="btn-icon size-8" :aria-label="copied === 'code' ? d.links.copied : d.links.copy" @click="copy(dash.code, 'code')">
              <SiteIcon :name="copied === 'code' ? 'check' : 'copy'" class="size-4" />
            </button>
          </div>
        </section>

        <nav class="dash-tabs mt-6" :aria-label="d.title">
          <button v-for="x in tabs" :key="x" type="button" :aria-pressed="tab === x" @click="openTab(x)">{{ d.tabs[x] }}</button>
        </nav>

        <!-- Übersicht -->
        <section v-if="tab === 'overview'" class="mt-6">
          <div v-if="dash.teamChanges.length" class="card team-changes mb-4 p-4">
            <p class="section-title">{{ d.teamChanges }}</p>
            <ul class="mt-2 space-y-1 text-sm text-base-300">
              <li v-for="(c, i) in dash.teamChanges.slice(0, 6)" :key="i">{{ c.action }} · {{ date(c.at) }}</li>
            </ul>
          </div>

          <div v-if="checklist.length && checkDone < checklist.length" class="card checklist mb-4 p-5">
            <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <h2 class="section-title">{{ d.checklist.title }}</h2>
              <p class="text-xs text-base-400">{{ d.checklist.lead }}</p>
              <p class="ml-auto text-sm font-semibold text-base-100 tabular-nums">{{ checkPct }} %</p>
            </div>
            <div class="meter mt-3" role="progressbar" :aria-valuenow="checkPct" aria-valuemin="0" aria-valuemax="100" :aria-label="fill(d.checklist.progress, { done: checkDone, total: checklist.length })"><div :style="{ width: `${checkPct}%` }" /></div>
            <p class="mt-1 text-xs text-base-400">{{ fill(d.checklist.progress, { done: checkDone, total: checklist.length }) }}</p>
            <ul class="mt-3 grid gap-2 sm:grid-cols-2">
              <li v-for="i in checklist" :key="i.key" class="check-row" :data-done="i.done">
                <span class="check-mark" aria-hidden="true"><SiteIcon v-if="i.done" name="check" class="size-3.5" /><SiteIcon v-else-if="i.review" name="clock" class="size-3.5" /></span>
                <span class="min-w-0 flex-1">
                  <span class="block text-sm font-semibold" :class="i.done ? 'text-base-400 line-through decoration-base-600' : 'text-base-50'">{{ d.checklist.items[i.key] }}</span>
                  <span class="block text-xs text-base-400">{{ i.done ? d.checklist.done : i.review ? d.checklist.review : d.checklist.hints[i.key] }}</span>
                </span>
                <button v-if="!i.done" type="button" class="btn btn-ghost h-8 px-3 text-xs" @click="openTab(i.tab)">{{ d.checklist.open }}<SiteIcon name="arrow" class="size-3.5" /></button>
              </li>
            </ul>
          </div>

          <p v-if="!stats" class="card p-5 text-base-400">{{ d.overview.noStats }}</p>
          <template v-else>
            <ul class="grid grid-cols-2 gap-3 md:grid-cols-4">
              <li v-for="s in tiles" :key="s.key" class="card p-4">
                <p class="text-2xl font-bold text-base-50 tabular-nums">{{ s.value }}</p>
                <p class="mt-1 text-xs text-base-400">{{ s.label }}<template v-if="s.delta"> · {{ d.compare.current }}</template></p>
                <p v-if="s.delta" class="delta mt-1" :data-tone="s.delta.tone" :title="fill(d.compare.vsPrev, { change: s.delta.text })">{{ s.delta.text }}</p>
                <p v-if="s.hint" class="mt-1 text-[11px] leading-snug text-base-400">{{ s.hint }}</p>
              </li>
            </ul>

            <div class="mt-6 flex flex-wrap items-baseline gap-x-3">
              <h2 class="section-title">{{ d.charts.title }}</h2>
              <p class="text-xs text-base-400">{{ d.charts.lead }}</p>
            </div>
            <div class="mt-3 grid gap-4 md:grid-cols-2">
              <div class="card p-4">
                <p class="text-sm font-semibold text-base-200">{{ d.charts.redemptions }}</p>
                <PartnerStackChart class="mt-2" :a="series((x) => x.fixed) as number[]" :b="series((x) => x.onetime) as number[]" :days="days" :labels="[d.overview.fixed, d.overview.onetime]" :title="d.charts.redemptions" :texts="d.charts.table" />
              </div>
              <div class="card p-4">
                <p class="text-sm font-semibold text-base-200">{{ d.charts.newSupporters }}</p>
                <AdminSeriesChart class="mt-2" :values="series((x) => x.newSupporters)" :days="days" :label="d.charts.newSupporters" :texts="d.charts.table" />
              </div>
              <div v-if="dash.serverAllowed" class="card p-4">
                <p class="text-sm font-semibold text-base-200">{{ d.charts.serverJoins }}</p>
                <AdminSeriesChart class="mt-2" :values="series((x) => x.serverJoins)" :days="days" :label="d.charts.serverJoins" tone="lamp" :texts="d.charts.table" />
              </div>
              <div class="card p-4">
                <p class="text-sm font-semibold text-base-200">{{ d.charts.downloads }}</p>
                <AdminSeriesChart class="mt-2" :values="series((x) => x.downloads)" :days="days" :label="d.charts.downloads" tone="lamp" :texts="d.charts.table" />
              </div>
              <div class="card p-4">
                <p class="text-sm font-semibold text-base-200">{{ d.charts.active }}</p>
                <AdminSeriesChart class="mt-2" :values="series((x) => x.activeSupporters)" :days="days" :label="d.charts.active" kind="line" tone="ok" :texts="d.charts.table" />
              </div>
            </div>

            <div class="mt-6 grid gap-4 lg:grid-cols-[1fr_20rem]">
              <div class="card overflow-x-auto p-5">
                <h2 class="section-title">{{ d.compare.title }}</h2>
                <p class="mt-1 text-xs text-base-400">{{ d.compare.lead }}</p>
                <table class="tbl mt-3 w-full text-sm">
                  <thead><tr><th>{{ d.compare.metric }}</th><th class="text-right">{{ d.compare.current }}</th><th class="text-right">{{ d.compare.previous }}</th><th class="text-right">{{ d.compare.change }}</th></tr></thead>
                  <tbody>
                    <tr v-for="r in compareRows" :key="r.key">
                      <td :class="r.sub ? 'pl-6 text-base-400' : ''">{{ d.compare.metrics[r.key] }}</td>
                      <td class="text-right font-semibold text-base-50 tabular-nums">{{ num(r.now) }}</td>
                      <td class="text-right tabular-nums">{{ num(r.before) }}</td>
                      <td class="text-right"><span class="delta" :data-tone="r.key === 'left' ? 'flat' : r.delta.tone">{{ r.delta.text }}</span></td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div class="grid content-start gap-4">
                <div class="card p-5">
                  <h2 class="section-title">{{ d.overview.activity }}</h2>
                  <table class="kv mt-2 w-full text-sm">
                    <tbody>
                      <tr><td>{{ d.overview.onlineToday }}</td><td>{{ num(stats.onlineToday) }}</td></tr>
                      <tr><td>{{ d.overview.playedToday }}</td><td>{{ num(stats.playedToday) }}</td></tr>
                      <tr><td>{{ d.overview.left }}</td><td>{{ num(stats.left30d) }}</td></tr>
                      <tr v-if="dash.serverAllowed"><td>{{ d.overview.joins }}</td><td>{{ num(stats.serverJoins7d) }}</td></tr>
                    </tbody>
                  </table>
                  <p class="mt-2 text-[11px] text-base-400">{{ d.overview.numbersOnly }}</p>
                </div>
                <div class="card p-5">
                  <h2 class="section-title">{{ d.quota.title }}</h2>
                  <p class="mt-2 flex text-sm text-base-300"><span class="flex-1">{{ fill(d.quota.used, { used: num(dash.quota.used), total: num(dash.quota.total) }) }}</span>{{ fill(d.quota.free, { n: num(dash.quota.free) }) }}</p>
                  <div class="meter mt-2"><div :style="{ width: `${pct(dash.quota.used, dash.quota.total)}%` }" /></div>
                  <button v-if="may('codes')" type="button" class="btn btn-ghost mt-3" @click="openTab('codes')">{{ d.quota.manage }}</button>
                </div>
              </div>
            </div>
          </template>
        </section>

        <!-- Codes -->
        <section v-else-if="tab === 'codes'" class="mt-6 grid gap-6 lg:grid-cols-[1fr_20rem]">
          <div class="grid content-start gap-6">
            <div class="card p-5">
              <h2 class="section-title">{{ d.codes.fixedTitle }}</h2>
              <p class="mt-1 text-sm text-base-400">{{ d.codes.fixedText }}</p>
              <ul class="mt-4 grid gap-2">
                <li v-for="c in fixedCodes" :key="c.code" class="fixed-row" :data-main="c.main" :data-active="c.active">
                  <div class="min-w-0 flex-1">
                    <PartnerCode v-if="c.main" :code="c.code" :max="1.15" />
                    <code v-else class="font-mono text-base font-bold break-all text-base-100">{{ c.code }}</code>
                    <p class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-base-400">
                      <span class="pill" :class="c.main ? 'pill-ok' : c.active ? '' : 'pill-warn'">{{ c.main ? d.codes.main : c.active ? d.codes.extra : d.codes.inactive }}</span>
                      <template v-if="dash.fixedCodes">
                        <span class="tabular-nums">{{ d.codes.colTotal }}: <b class="text-base-100">{{ num(c.redemptions) }}</b></span>
                        <span class="tabular-nums">{{ d.codes.col30d }}: <b class="text-base-100">{{ num(c.redemptions30d) }}</b></span>
                      </template>
                    </p>
                  </div>
                  <button v-if="c.active" type="button" class="btn btn-ghost h-9 px-3 text-sm" @click="copy(c.code, `fixed:${c.code}`)"><SiteIcon :name="copied === `fixed:${c.code}` ? 'check' : 'copy'" class="size-4" />{{ copied === `fixed:${c.code}` ? d.links.copied : d.links.copy }}</button>
                </li>
              </ul>
              <p v-if="fixedCodes.some((c) => !c.active)" class="mt-2 text-xs text-base-400">{{ d.codes.codesNote }}</p>

              <h3 class="mt-5 text-sm font-semibold text-base-200">{{ d.codes.requestTitle }}</h3>
              <p v-if="openOf('code_change')" class="state state-warn mt-2 text-sm"><SiteIcon name="clock" class="size-4" />{{ fill(openOf('code_change')!.data.action === 'add' ? d.codes.addPending : d.codes.changePending, { code: String(openOf('code_change')!.data.code) }) }}
                <button type="button" class="ml-auto text-xs underline" @click="withdraw(openOf('code_change')!)">{{ d.withdraw }}</button></p>
              <form v-else class="mt-2 grid gap-2" @submit.prevent="requestCode">
                <div class="seg w-fit" role="radiogroup" :aria-label="d.codes.requestTitle">
                  <button type="button" role="radio" :aria-checked="codeAction === 'add'" :aria-pressed="codeAction === 'add'" :disabled="codeLimitReached" @click="codeAction = 'add'">{{ d.codes.actionAdd }}</button>
                  <button type="button" role="radio" :aria-checked="codeAction === 'change'" :aria-pressed="codeAction === 'change'" @click="codeAction = 'change'">{{ d.codes.actionChange }}</button>
                </div>
                <div class="flex flex-wrap gap-2">
                  <input v-model="codeChange" class="field w-52 font-mono uppercase" maxlength="19" pattern="[A-Za-z0-9]{4,19}" required :placeholder="d.codes.newCode" :aria-label="d.codes.newCode" />
                  <button type="submit" class="btn btn-ghost" :disabled="busy === 'code'">{{ d.codes.request }}</button>
                </div>
                <p v-if="codeLimitReached" class="text-xs text-base-400">{{ fill(d.codes.limit, { n: PARTNER_FIXED_CODES_MAX }) }}</p>
              </form>
              <p v-if="lastDecided('code_change')?.status === 'rejected'" class="state state-danger mt-2 text-xs">{{ fill(d.rejected, { reason: lastDecided('code_change')!.reason ?? '' }) }}</p>
            </div>
            <div class="card overflow-x-auto p-5">
              <h2 class="section-title">{{ d.codes.batches }}</h2>
              <p v-if="!dash.batches?.length" class="mt-2 text-sm text-base-400">{{ d.codes.none }}</p>
              <table v-else class="tbl mt-3 w-full text-sm">
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
          </div>
          <div class="grid content-start gap-6">
            <form class="card p-5" @submit.prevent="createBatch">
              <h2 class="section-title">{{ d.codes.createTitle }}</h2>
              <p class="mt-1 text-xs text-base-400">{{ d.codes.createHint }}</p>
              <label class="label mt-4" for="bt-label">{{ d.codes.label }}</label>
              <input id="bt-label" v-model="batchForm.label" class="field" maxlength="60" />
              <label class="label mt-3" for="bt-count">{{ d.codes.count }}</label>
              <input id="bt-count" v-model.number="batchForm.count" class="field" type="number" min="1" max="500" />
              <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'batch' || dash.state !== 'active'">{{ d.codes.create }}</button>
              <div v-if="createdCodes.length" class="mt-4">
                <p class="text-sm text-ok">{{ fill(d.codes.created, { n: createdCodes.length }) }}</p>
                <pre class="code-list mt-2">{{ createdCodes.join('\n') }}</pre>
                <div class="mt-2 flex flex-wrap gap-2">
                  <button type="button" class="btn btn-ghost" @click="copy(createdCodes.join('\n'), 'codes')"><SiteIcon :name="copied === 'codes' ? 'check' : 'copy'" class="size-4" />{{ d.codes.copyAll }}</button>
                  <a v-if="createdBatch" :href="`${base}/batches/${createdBatch}.csv`" class="btn btn-ghost"><SiteIcon name="download" class="size-4" />{{ d.codes.csv }}</a>
                </div>
              </div>
            </form>
            <div class="card p-5">
              <h2 class="section-title">{{ d.quota.title }}</h2>
              <p class="mt-2 flex text-sm text-base-300"><span class="flex-1">{{ fill(d.quota.used, { used: num(dash.quota.used), total: num(dash.quota.total) }) }}</span>{{ fill(d.quota.free, { n: num(dash.quota.free) }) }}</p>
              <div class="meter mt-2"><div :style="{ width: `${pct(dash.quota.used, dash.quota.total)}%` }" /></div>
              <p class="mt-2 text-xs text-base-400">{{ d.quota.text }}</p>
              <p v-if="dash.quota.requested" class="state state-warn mt-3 text-sm"><SiteIcon name="clock" class="size-4" />{{ fill(d.quota.requested, { n: num(dash.quota.requested) }) }}</p>
              <div v-else class="mt-3 flex gap-2">
                <select v-model.number="quotaAmount" class="field w-28" :aria-label="d.quota.request"><option :value="500">+500</option><option :value="1000">+1.000</option><option :value="5000">+5.000</option></select>
                <button type="button" class="btn btn-ghost" :disabled="busy === 'quota'" @click="sendRequest({ kind: 'quota', amount: quotaAmount }, 'quota')">{{ d.quota.request }}</button>
              </div>
            </div>
          </div>
        </section>

        <!-- Werbe-Links -->
        <section v-else-if="tab === 'links' && dash.links" class="mt-6 grid gap-6 lg:grid-cols-[1fr_20rem]">
          <div class="grid content-start gap-6">
            <div class="card p-5">
              <h2 class="section-title">{{ d.links.title }}</h2>
              <p class="mt-1 text-sm text-base-400">{{ fill(d.links.text, { name: dash.name, code: dash.code ?? '' }) }}</p>
              <div class="code-row mt-4">
                <code class="min-w-0 flex-1 text-sm break-all text-lamp-300">{{ goUrl() }}</code>
                <button type="button" class="btn btn-ghost" @click="copy(goUrl(), 'go')"><SiteIcon :name="copied === 'go' ? 'check' : 'copy'" class="size-4" />{{ copied === 'go' ? d.links.copied : d.links.copy }}</button>
              </div>
            </div>
            <div class="card overflow-x-auto p-5">
              <h2 class="section-title">{{ d.links.campaigns }}</h2>
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
          </div>
          <div class="grid content-start gap-6">
            <form class="card p-5" @submit.prevent="addCampaign">
              <h2 class="section-title">{{ d.links.add }}</h2>
              <label class="label mt-4" for="ln-c">{{ d.links.campaigns }}</label>
              <input id="ln-c" v-model="campaign" class="field" maxlength="24" pattern="[a-z0-9\-]{1,24}" required :placeholder="d.links.placeholder" />
              <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'link'">{{ d.links.add }}</button>
            </form>
            <p class="note-box">{{ d.links.privacy }}</p>
          </div>
        </section>

        <!-- Partner-Server -->
        <section v-else-if="tab === 'server' && dash.server" class="mt-6">
          <div v-if="!dash.serverAllowed" class="card max-w-2xl p-6">
            <p class="flex items-center gap-2 section-title"><SiteIcon name="lock" class="size-4 text-base-400" />{{ d.serverOff.title }}</p>
            <p class="mt-2 text-sm text-base-400">{{ d.serverOff.text }}</p>
          </div>
          <div v-else class="grid gap-6 lg:grid-cols-[1fr_20rem]">
            <div class="grid content-start gap-6">
              <div class="card p-5">
                <h2 class="flex flex-wrap items-center gap-2 section-title">{{ d.server.preview }}
                  <span v-if="openOf('server')" class="pill pill-warn">{{ d.server.waiting }}</span>
                  <span v-else-if="dash.server.current?.hidden" class="pill pill-warn">{{ d.server.hidden }}</span>
                  <span v-else-if="dash.server.current" class="pill pill-ok">{{ d.server.live }}</span>
                </h2>
                <div class="mc mt-4">
                  <div class="srv">
                    <PartnerLogo :logo="dash.logo" :name="dash.name" :size="40" />
                    <div class="min-w-0 flex-1">
                      <p class="nm">{{ serverForm.name || dash.name }}<span class="tag">{{ d.server.tag }}</span></p>
                      <p class="motd">{{ serverForm.motd }}</p>
                    </div>
                    <span v-if="dash.server.current?.players" class="motd">{{ dash.server.current.players.online }}/{{ dash.server.current.players.max }}</span>
                  </div>
                </div>
                <table class="kv mt-4 w-full text-sm">
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
              <p class="note-box">{{ d.server.rules }}</p>
            </div>
            <form class="card h-fit p-5" @submit.prevent="sendServer">
              <h2 class="section-title">{{ d.server.title }}</h2>
              <p class="mt-1 text-xs text-base-400">{{ d.server.keepOld }}</p>
              <label class="label mt-4" for="sv-name">{{ d.server.name }}</label>
              <input id="sv-name" v-model="serverForm.name" class="field" maxlength="40" required />
              <label class="label mt-3" for="sv-addr">{{ d.server.address }}</label>
              <input id="sv-addr" v-model="serverForm.address" class="field" maxlength="261" required placeholder="play.example.com" />
              <div class="mt-3 grid grid-cols-2 gap-2">
                <div><label class="label" for="sv-min">{{ d.server.minVersion }}</label><input id="sv-min" v-model="serverForm.minVersion" class="field" maxlength="15" required pattern="\d{1,3}(\.\d{1,3}){1,3}" /></div>
                <div><label class="label" for="sv-max">{{ d.server.maxVersion }}</label><input id="sv-max" v-model="serverForm.maxVersion" class="field" maxlength="15" required pattern="\d{1,3}(\.\d{1,3}){1,3}" /></div>
              </div>
              <label class="label mt-3" for="sv-motd">{{ d.server.motd }}</label>
              <input id="sv-motd" v-model="serverForm.motd" class="field" maxlength="60" />
              <button v-if="!openOf('server')" type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'server'">{{ d.server.submit }}</button>
              <button v-else type="button" class="btn btn-ghost mt-4 w-full" @click="withdraw(openOf('server')!)">{{ d.withdraw }}</button>
              <p v-if="lastDecided('server')?.status === 'rejected'" class="state state-danger mt-3 text-xs">{{ fill(d.rejected, { reason: lastDecided('server')!.reason ?? '' }) }}</p>
            </form>
          </div>
        </section>

        <!-- API -->
        <section v-else-if="tab === 'api'" class="mt-6 grid gap-6">
          <div class="card p-5">
            <h2 class="section-title">{{ d.api.keyTitle }}</h2>
            <p class="mt-1 text-sm text-base-400">{{ d.api.keyText }}</p>
            <div class="code-row mt-4">
              <code v-if="shownKey" class="min-w-0 flex-1 text-sm break-all text-lamp-300">{{ shownKey }}</code>
              <code v-else-if="dash.apiKey" class="min-w-0 flex-1 font-mono text-sm text-base-200">trsp_live_••••••••••••{{ dash.apiKey.hint }}</code>
              <span v-else class="flex-1 text-sm text-base-400">{{ d.api.noKey }}</span>
              <button v-if="shownKey" type="button" class="btn-icon" :aria-label="d.links.copy" @click="copy(shownKey, 'key')"><SiteIcon :name="copied === 'key' ? 'check' : 'copy'" class="size-4" /></button>
              <button type="button" class="btn btn-ghost" :disabled="busy === 'key'" @click="rotateKey">{{ dash.apiKey ? d.api.rotate : d.api.newKey }}</button>
              <button v-if="dash.apiKey" type="button" class="btn btn-ghost" :disabled="busy === 'key'" @click="revokeKey">{{ d.api.revoke }}</button>
            </div>
            <p v-if="shownKey" class="mt-2 text-xs text-lamp-300">{{ d.api.shownOnce }}</p>
            <p v-if="dash.apiKey" class="mt-1 text-xs text-base-400">{{ fill(d.api.created, { date: date(dash.apiKey.createdAt) }) }}<template v-if="dash.apiKey.lastUsedAt"> · {{ fill(d.api.lastUsed, { date: date(dash.apiKey.lastUsedAt) }) }}</template></p>
          </div>
          <div class="card p-5">
            <h2 class="section-title">{{ d.api.createTitle }}</h2>
            <pre class="code-list mt-3">POST {{ apiBase }}/v1/partner-api/codes
Authorization: Bearer trsp_live_…
Content-Type: application/json

{ "count": 1, "label": "Orders", "ref": "order-48213" }

→ 201 { "codes": ["{{ dash.prefix }}-7K2M-9QXA"], "quota": { "total": {{ dash.quota.total }}, "used": {{ dash.quota.used + 1 }} } }</pre>
            <p class="mt-2 text-xs text-base-400">{{ d.api.refText }}</p>
          </div>
          <div class="grid gap-6 md:grid-cols-2">
            <div class="card p-5">
              <h2 class="section-title">{{ d.api.statusTitle }}</h2>
              <pre class="code-list mt-3">GET /v1/partner-api/codes/{{ dash.prefix }}-7K2M-9QXA
→ { "redeemed": true, "redeemedAt": "…", "blocked": false, "ref": "order-48213" }</pre>
              <p class="mt-2 text-xs text-base-400">{{ d.api.statusText }}</p>
            </div>
            <div class="card p-5">
              <h2 class="section-title">{{ d.api.quotaTitle }}</h2>
              <pre class="code-list mt-3">GET /v1/partner-api/quota
→ { "total": {{ dash.quota.total }}, "used": {{ dash.quota.used }}, "requested": {{ !!dash.quota.requested }} }</pre>
            </div>
          </div>
          <p class="note-box">{{ d.api.limits }}</p>
        </section>

        <!-- Webhook -->
        <section v-else-if="tab === 'webhook' && dash.webhook" class="mt-6 grid gap-6">
          <div class="grid gap-6 lg:grid-cols-[1fr_20rem]">
            <div class="card p-5">
              <h2 class="section-title">{{ d.webhook.what }}</h2>
              <p class="mt-1 text-sm text-base-400">{{ d.webhook.whatText }}</p>
              <pre class="code-list mt-3">POST {{ hookForm.url || 'https://…' }}
User-Agent: TRS-Webhooks/1
X-TRS-Timestamp: 1763719924
X-TRS-Signature: sha256=HMAC(secret, "timestamp.body")

{ "type": "code.redeemed",
  "at": "2026-11-21T10:12:04.000Z",
  "code": "{{ dash.prefix }}-7K2M-9QXA",
  "ref": "order-48213" }</pre>
            </div>
            <form class="card h-fit p-5" @submit.prevent="saveHook">
              <h2 class="section-title">{{ d.webhook.title }}</h2>
              <label class="label mt-4" for="wh-url">{{ d.webhook.url }}</label>
              <input id="wh-url" v-model="hookForm.url" class="field" type="url" maxlength="500" required placeholder="https://" />
              <p class="label mt-3">{{ d.webhook.events }}</p>
              <label v-for="ev in HOOK_EVENTS" :key="ev" class="chk"><input v-model="hookForm.events" type="checkbox" :value="ev" /><span><code class="text-xs">{{ ev }}</code><span class="block text-xs text-base-400">{{ d.webhook.ev[ev] }}</span></span></label>
              <template v-if="dash.webhook.config">
                <p class="label mt-3">{{ d.webhook.secret }}</p>
                <div class="flex flex-wrap items-center gap-2">
                  <code v-if="shownSecret" class="min-w-0 flex-1 text-xs break-all text-lamp-300">{{ shownSecret }}</code>
                  <code v-else class="font-mono text-sm text-base-200">whsec_••••••••{{ dash.webhook.config.secretHint }}</code>
                  <button v-if="shownSecret" type="button" class="btn-icon" :aria-label="d.links.copy" @click="copy(shownSecret, 'secret')"><SiteIcon :name="copied === 'secret' ? 'check' : 'copy'" class="size-4" /></button>
                  <button type="button" class="btn btn-ghost h-8 px-3 text-xs" :disabled="busy === 'hook'" @click="rotateSecret">{{ d.webhook.newSecret }}</button>
                </div>
              </template>
              <p v-if="shownSecret" class="mt-2 text-xs text-lamp-300">{{ d.webhook.secretOnce }}</p>
              <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'hook'">{{ d.webhook.save }}</button>
              <div v-if="dash.webhook.config" class="mt-2 flex gap-2">
                <button type="button" class="btn btn-ghost flex-1" :disabled="busy === 'hook-test'" @click="testHook">{{ d.webhook.test }}</button>
                <button type="button" class="btn btn-ghost flex-1" @click="removeHook">{{ d.webhook.remove }}</button>
              </div>
            </form>
          </div>
          <div class="card overflow-x-auto p-5">
            <h2 class="section-title">{{ d.webhook.log }}</h2>
            <p v-if="!dash.webhook.deliveries.length" class="mt-2 text-sm text-base-400">{{ d.webhook.none }}</p>
            <table v-else class="tbl mt-3 w-full text-sm">
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
        <section v-else-if="tab === 'promo'" class="mt-6 grid gap-6">
          <div class="grid gap-4 md:grid-cols-3">
            <div v-for="c in (['wiki', 'blog', 'newsletter'] as const)" :key="c" class="card p-5">
              <p class="flex items-center gap-2 section-title">{{ d.promo[c] }}<span class="pill" :class="promoState(c).cls">{{ promoState(c).label }}</span></p>
              <p class="mt-2 text-xs text-base-400">{{ d.promo[`${c}Text`] }}</p>
              <a v-if="c === 'wiki' && dash.wikiUrl" :href="dash.wikiUrl" target="_blank" rel="noopener" class="mt-2 inline-block text-xs break-all text-redstone-300 hover:underline">{{ dash.wikiUrl }}</a>
              <button v-if="openOf('promo', c)" type="button" class="btn btn-ghost mt-3" @click="withdraw(openOf('promo', c)!)">{{ d.withdraw }}</button>
              <button v-else type="button" class="btn btn-ghost mt-3" @click="promoForm.channel = c">{{ d.promo.request }}</button>
            </div>
          </div>
          <div class="grid gap-6 lg:grid-cols-[1fr_20rem]">
            <form class="card p-5" @submit.prevent="sendPromo">
              <h2 class="section-title">{{ d.promo.formTitle }}</h2>
              <p class="mt-1 text-xs text-base-400">{{ d.promo.teamWrites }}</p>
              <div class="mt-4 grid gap-3 sm:grid-cols-[12rem_1fr]">
                <div>
                  <label class="label" for="pr-ch">{{ d.promo.channel }}</label>
                  <select id="pr-ch" v-model="promoForm.channel" class="field"><option v-for="c in (['blog', 'newsletter', 'wiki'] as const)" :key="c" :value="c" :disabled="!!openOf('promo', c)">{{ d.promo[c] }}</option></select>
                </div>
                <div>
                  <label class="label" for="pr-topic">{{ d.promo.topic }}</label>
                  <input id="pr-topic" v-model="promoForm.topic" class="field" maxlength="120" required :placeholder="d.promo.topicPlaceholder" />
                </div>
              </div>
              <label class="label mt-3" for="pr-text">{{ d.promo.text }}</label>
              <textarea id="pr-text" v-model="promoForm.text" class="field" rows="5" maxlength="2000" required />
              <button type="submit" class="btn btn-primary mt-4" :disabled="busy === 'promo' || !!openOf('promo', promoForm.channel)">{{ d.promo.send }}</button>
            </form>
            <div class="card h-fit p-5">
              <h2 class="section-title">{{ d.promo.history }}</h2>
              <p v-if="!requestsOf('promo').length" class="mt-2 text-sm text-base-400">–</p>
              <ul v-else class="mt-3 space-y-3 text-sm">
                <li v-for="r in requestsOf('promo')" :key="r.id">
                  <p class="font-semibold text-base-100">{{ r.data.topic }}</p>
                  <p class="text-xs text-base-400">{{ r.channel ? d.promo[r.channel] : '' }} · {{ date(r.createdAt) }} · {{ d.status[r.status] }}</p>
                  <p v-if="r.reason" class="text-xs text-redstone-300">{{ r.reason }}</p>
                </li>
              </ul>
            </div>
          </div>
        </section>

        <!-- Kosmetik -->
        <section v-else-if="tab === 'cosmetics'" class="mt-6 grid gap-6">
          <div class="grid gap-6 lg:grid-cols-[1fr_20rem]">
            <div class="card overflow-hidden p-0">
              <div class="flex flex-wrap items-baseline gap-x-3 px-5 pt-5">
                <h2 class="section-title">{{ d.cosmetics.preview }}</h2>
                <p class="text-xs text-base-400">{{ d.cosmetics.previewLead }}</p>
              </div>
              <div class="stage mt-3">
                <ClientOnly>
                  <CapeViewer v-if="previewed?.cape" :cape="previewed.cape" :height="360" />
                  <HatViewer v-else-if="previewed?.hat" :hat="previewed.hat" :height="360" />
                  <div v-else class="grid h-[360px] place-items-center px-6 text-center text-sm text-base-400">{{ previewItems.length ? d.cosmetics.noPreview : d.cosmetics.none }}</div>
                  <template #fallback><div class="h-[360px]" /></template>
                </ClientOnly>
              </div>
            </div>
            <div class="card h-fit p-5">
              <h2 class="section-title">{{ d.cosmetics.assigned }}</h2>
              <p v-if="!previewItems.length" class="mt-2 text-sm text-base-400">{{ d.cosmetics.none }}</p>
              <ul v-else class="mt-3 grid gap-2">
                <li v-for="c in previewItems" :key="c.key">
                  <button type="button" class="item-card w-full text-left" :aria-pressed="previewed?.key === c.key" @click="previewKey = c.key">
                    <ClientOnly>
                      <CapeThumb v-if="c.cape" :texture="c.cape.url" :frames="c.cape.frames" :frame-time-ms="c.cape.frameTimeMs" :scale="c.scale" :width="32" />
                      <img v-else-if="c.hat" :src="c.hat.card" alt="" class="hat-card" />
                      <span v-else class="item-icon"><SiteIcon name="crown" class="size-5" /></span>
                    </ClientOnly>
                    <span class="min-w-0">
                      <span class="block truncate font-semibold text-base-50">{{ c.name }}</span>
                      <span class="block text-xs text-base-400">{{ c.note ?? (c.kind === 'cape' ? p.pub.cape : p.pub.cosmetic) }}</span>
                    </span>
                  </button>
                </li>
              </ul>
            </div>
          </div>

          <div class="card p-5">
            <h2 class="section-title">{{ d.cosmetics.selectTitle }}</h2>
            <p class="mt-1 max-w-3xl text-sm text-base-400">{{ d.cosmetics.selectLead }}</p>
            <p v-if="pendingCape" class="state state-warn mt-4 text-sm">
              <SiteIcon name="clock" class="size-4" />{{ fill(d.cosmetics.pending, { name: String(pendingCape.data.name ?? '') }) }}
              <button type="button" class="ml-auto text-xs underline" @click="withdraw(pendingCape)">{{ d.withdraw }}</button>
            </p>
            <p v-if="lastDecided('cape')?.status === 'rejected'" class="state state-danger mt-3 text-sm">{{ fill(d.rejected, { reason: lastDecided('cape')!.reason ?? '' }) }}</p>
            <p v-if="selectable === null" class="mt-4 text-sm text-base-400">{{ d.cosmetics.loading }}</p>
            <p v-else-if="!selectable.length" class="mt-4 rounded-md border border-base-800 bg-base-950 px-4 py-3 text-sm text-base-300">{{ d.cosmetics.selectEmpty }}</p>
            <form v-else class="mt-4" @submit.prevent="submitCape">
              <ul class="cape-grid" role="radiogroup" :aria-label="d.cosmetics.selectTitle">
                <li v-for="c in selectable" :key="c.id">
                  <button type="button" role="radio" class="cape-pick" :aria-checked="pickedCape === c.id" @click="pickCape(c)">
                    <ClientOnly><CapeThumb :texture="sameOriginPath(c.url)" :scale="c.scale" :frames="c.frames" :frame-time-ms="c.frameTimeMs" :width="48" /></ClientOnly>
                    <span class="mt-2 block w-full truncate text-sm font-semibold text-base-50">{{ c.name }}</span>
                    <span class="block w-full truncate text-xs text-base-400">{{ fill(d.cosmetics.by, { name: c.owner.name }) }}<template v-if="c.frames > 1"> · {{ fill(d.cosmetics.animated, { n: c.frames }) }}</template></span>
                  </button>
                </li>
              </ul>
              <div v-if="pickedCape" class="mt-4 flex flex-wrap items-end gap-3">
                <div class="w-full max-w-xs">
                  <label class="label" for="cp-name">{{ d.cosmetics.capeName }}</label>
                  <input id="cp-name" v-model="capeName" class="field" maxlength="32" required />
                </div>
                <button type="submit" class="btn btn-primary" :disabled="busy === 'cape'"><SiteIcon name="cape" class="size-4" />{{ d.cosmetics.submit }}</button>
              </div>
            </form>
          </div>

          <div class="grid gap-6 lg:grid-cols-[1fr_20rem]">
            <form class="card p-5" @submit.prevent="sendWish">
              <h2 class="flex items-center gap-2 section-title">{{ d.cosmetics.wishTitle }}<span v-if="openOf('cosmetic_wish')" class="pill pill-warn">{{ d.status.open }}</span></h2>
              <p class="mt-1 text-sm text-base-400">{{ d.cosmetics.wishText }}</p>
              <p v-if="openOf('cosmetic_wish')" class="req-detail mt-4">{{ openOf('cosmetic_wish')!.data.text }}</p>
              <template v-else>
                <label class="label mt-4" for="cs-wish">{{ d.cosmetics.wish }}</label>
                <textarea id="cs-wish" v-model="wish" class="field" rows="4" maxlength="1000" required />
                <button type="submit" class="btn btn-primary mt-4" :disabled="busy === 'wish'">{{ d.cosmetics.sendWish }}</button>
              </template>
            </form>
            <p class="note-box h-fit">{{ d.cosmetics.proprietary }}</p>
          </div>
        </section>

        <!-- Profil -->
        <section v-else-if="tab === 'profile'" class="profile-grid mt-6">
          <div class="grid min-w-0 content-start gap-6">
            <div class="card p-5">
              <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                <h2 class="section-title">{{ d.profile.complete }}</h2>
                <p class="text-xs text-base-400">{{ d.profile.completeHint }}</p>
                <p class="ml-auto text-sm font-semibold text-base-100 tabular-nums">{{ profilePct }} %</p>
              </div>
              <div class="meter mt-3" role="progressbar" :aria-valuenow="profilePct" aria-valuemin="0" aria-valuemax="100" :aria-label="fill(d.checklist.progress, { done: profileDone, total: profileParts.length })"><div :style="{ width: `${profilePct}%` }" /></div>
              <ul class="part-list mt-3">
                <li v-for="q in profileParts" :key="q.key">
                  <button type="button" class="part" :data-state="q.state" @click="focusPart(q.key)">
                    <span class="part-mark" aria-hidden="true"><SiteIcon v-if="q.state === 'done'" name="check" class="size-3.5" /><SiteIcon v-else-if="q.state === 'review'" name="clock" class="size-3.5" /><SiteIcon v-else-if="q.state === 'draft'" name="pencil" class="size-3.5" /><SiteIcon v-else name="warn" class="size-3.5" /></span>
                    <span class="min-w-0 flex-1 text-left">
                      <span class="block text-sm font-semibold break-words text-base-100">{{ d.profile.item[q.key] }}</span>
                      <span class="part-state block">{{ d.profile.state[q.state] }}</span>
                    </span>
                  </button>
                </li>
              </ul>
            </div>

            <!-- Logo -->
            <div class="card p-5">
              <h2 class="flex flex-wrap items-center gap-2 section-title">{{ d.profile.logo }}
                <span v-if="partState('logo') === 'missing'" class="pill pill-miss">{{ d.profile.state.missing }}</span>
                <span v-if="openOf('logo')" class="pill pill-warn">{{ d.status.open }}</span>
              </h2>
              <p v-if="lastDecided('logo')?.status === 'rejected'" class="state state-danger mt-3 text-sm">{{ fill(d.rejected, { reason: lastDecided('logo')!.reason ?? '' }) }}</p>
              <div class="mt-4 flex flex-wrap items-start gap-5">
                <figure class="logo-fig">
                  <PartnerLogo :logo="dash.logo" :name="dash.name" :size="80" />
                  <figcaption>{{ d.profile.live }}</figcaption>
                </figure>
                <figure v-if="pendingLogoUrl" class="logo-fig">
                  <PartnerLogo :logo="pendingLogoUrl" :name="dash.name" :size="80" class="ring-review" />
                  <figcaption>{{ d.profile.inReview }}</figcaption>
                </figure>
                <figure v-if="cropped" class="logo-fig">
                  <PartnerLogo :logo="cropped.url" :name="dash.name" :size="80" class="ring-new" />
                  <figcaption>{{ d.profile.fresh }}</figcaption>
                </figure>
              </div>
              <div v-if="cropped" class="mt-4 flex flex-wrap gap-2">
                <button type="button" class="btn btn-primary" :disabled="busy === 'logo'" @click="uploadLogo"><SiteIcon name="check" class="size-4" />{{ d.profile.sendLogo }}</button>
                <button v-if="cropFile" type="button" class="btn btn-ghost" @click="cropOpen = true"><SiteIcon name="pencil" class="size-4" />{{ d.profile.recrop }}</button>
                <button type="button" class="btn btn-ghost" @click="dropCropped">{{ d.profile.discard }}</button>
              </div>
              <div
                v-else
                class="drop mt-4"
                :data-active="dropActive"
                @dragenter.prevent="dropActive = true"
                @dragover.prevent="dropActive = true"
                @dragleave="dropActive = false"
                @drop.prevent="onLogoDrop"
              >
                <SiteIcon name="image" class="size-6 text-base-400" />
                <div class="min-w-0 flex-1">
                  <button id="pf-logo" type="button" class="btn btn-ghost" @click="logoInput?.click()"><SiteIcon name="image" class="size-4" />{{ d.profile.pickImage }}</button>
                  <span class="ml-2 text-sm text-base-400">{{ dropActive ? d.profile.dropHere : d.profile.dropHint }}</span>
                </div>
                <input ref="logoInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif" class="sr-only" tabindex="-1" aria-hidden="true" @change="onLogoInput" />
              </div>
              <p class="mt-3 text-xs text-base-400">{{ d.profile.logoText }}</p>
              <button v-if="openOf('logo') && !cropped" type="button" class="mt-2 text-xs text-base-300 underline" @click="withdraw(openOf('logo')!)">{{ d.withdraw }}</button>
            </div>

            <!-- Texte + Links -->
            <form class="card p-5" novalidate @submit.prevent="sendProfile">
              <h2 class="flex flex-wrap items-center gap-2 section-title">{{ d.profile.title }}<span v-if="profileLocked" class="pill pill-warn">{{ d.status.open }}</span></h2>
              <p class="mt-1 text-sm text-base-400">{{ d.profile.onlyAfter }}</p>
              <p v-if="lastDecided('profile')?.status === 'rejected'" class="state state-danger mt-3 text-sm">{{ fill(d.rejected, { reason: lastDecided('profile')!.reason ?? '' }) }}</p>
              <p v-if="profileLocked" class="state state-warn mt-3 text-sm"><SiteIcon name="clock" class="size-4 shrink-0" />{{ d.profile.locked }}</p>
              <div class="mt-4 grid gap-3 sm:grid-cols-2">
                <div><p class="label">{{ d.profile.name }}</p><p class="text-sm font-semibold text-base-100">{{ dash.name }}</p></div>
                <div><p class="label">{{ d.profile.category }}</p><p class="text-sm font-semibold text-base-100">{{ p.category[dash.category] }}</p></div>
              </div>

              <div v-for="k in (['de', 'en'] as const)" :key="k" class="mt-5">
                <div class="flex items-center gap-2">
                  <label class="label mb-0" :for="`pf-${k}`">{{ k === 'de' ? d.profile.textDe : d.profile.textEn }}</label>
                  <span v-if="partState(k) === 'missing'" class="pill pill-miss">{{ d.profile.state.missing }}</span>
                </div>
                <textarea :id="`pf-${k}`" v-model="profileForm[k]" class="field mt-1.5" rows="6" :maxlength="PARTNER_TEXT_MAX" :readonly="profileLocked" :aria-describedby="`pf-${k}-count`" />
                <p :id="`pf-${k}-count`" class="counter mt-1" :data-tone="counterTone(profileForm[k].length)">{{ fill(d.profile.counter, { n: profileForm[k].length, max: PARTNER_TEXT_MAX }) }}</p>
              </div>

              <div class="mt-4 flex flex-wrap items-center gap-2">
                <h3 id="pf-links-title" class="text-sm font-semibold text-base-200">{{ d.profile.links }}</h3>
                <span class="text-xs text-base-400 tabular-nums">{{ fill(d.profile.linkCount, { n: profileForm.links.length, max: PARTNER_LINKS_MAX }) }}</span>
                <span v-if="partState('links') === 'missing'" class="pill pill-miss">{{ d.profile.state.missing }}</span>
              </div>
              <p class="mt-0.5 text-xs text-base-400">{{ d.profile.linksHint }}</p>
              <ol id="pf-links" class="mt-2 grid gap-2" aria-labelledby="pf-links-title" tabindex="-1">
                <li v-for="(l, i) in profileForm.links" :key="l.id" class="link-row" :data-error="linkError(l) ?? undefined">
                  <span class="link-num" aria-hidden="true">{{ i + 1 }}</span>
                  <div class="link-fields">
                    <input v-model="l.label" class="field" :maxlength="PARTNER_LINK_LABEL_MAX" :placeholder="d.profile.linkLabel" :aria-label="`${d.profile.linkLabel} ${i + 1}`" :readonly="profileLocked" :aria-invalid="linkError(l) === 'label'" />
                    <input v-model="l.url" class="field" type="url" inputmode="url" :maxlength="PARTNER_LINK_URL_MAX" placeholder="https://" :aria-label="`URL ${i + 1}`" :readonly="profileLocked" :aria-invalid="linkError(l) === 'url'" @blur="fixUrl(l)" />
                    <p v-if="linkError(l)" class="link-err">{{ linkError(l) === 'url' ? d.profile.linkUrlInvalid : d.profile.linkLabelMissing }}</p>
                  </div>
                  <div v-if="!profileLocked" class="link-tools">
                    <button type="button" class="btn-icon size-8" :aria-label="`${d.profile.linkUp} (${i + 1})`" :disabled="i === 0" @click="moveLink(i, -1)"><SiteIcon name="up" class="size-4" /></button>
                    <button type="button" class="btn-icon size-8" :aria-label="`${d.profile.linkDown} (${i + 1})`" :disabled="i === profileForm.links.length - 1" @click="moveLink(i, 1)"><SiteIcon name="up" class="size-4 rotate-180" /></button>
                    <button type="button" class="btn-icon size-8" :aria-label="`${d.profile.linkRemove} (${i + 1})`" @click="profileForm.links.splice(i, 1)"><SiteIcon name="trash" class="size-4" /></button>
                  </div>
                </li>
              </ol>
              <button v-if="!profileLocked && profileForm.links.length < PARTNER_LINKS_MAX" type="button" class="btn btn-ghost mt-2" @click="profileForm.links.push(toEdit({ label: '', url: '' }))"><SiteIcon name="plus" class="size-4" />{{ d.profile.addLink }}</button>

              <p v-if="linksInvalid && !profileLocked" class="mt-4 text-sm text-redstone-300" role="status">{{ d.profile.fixLinks }}</p>
              <div class="mt-5 flex flex-wrap items-center gap-2">
                <template v-if="!profileLocked">
                  <button type="submit" class="btn btn-primary" :disabled="busy === 'profile' || linksInvalid || !profileDirty">{{ d.profile.submit }}</button>
                  <button v-if="profileDirty" type="button" class="btn btn-ghost" @click="resetProfile">{{ d.profile.reset }}</button>
                  <span v-else class="text-xs text-base-400">{{ d.profile.unchanged }}</span>
                </template>
                <button v-else type="button" class="btn btn-ghost" :disabled="busy.startsWith('wd:')" @click="withdrawProfile">{{ d.withdraw }}</button>
              </div>
            </form>
          </div>

          <!-- Live-Vorschau -->
          <aside class="preview-col">
            <div class="flex flex-wrap items-center gap-2">
              <h2 class="section-title flex-1">{{ d.profile.previewTitle }}</h2>
              <div class="seg" role="group" :aria-label="d.profile.previewText">
                <button v-for="l in (['de', 'en'] as const)" :key="l" type="button" :aria-pressed="previewLang === l" @click="previewLang = l">{{ l.toUpperCase() }}</button>
              </div>
            </div>
            <p class="mt-1 text-xs text-base-400">{{ fill(d.profile.previewLead, { slug: dash.slug }) }}</p>
            <div v-if="previewCard" class="preview-frame mt-3" inert :aria-label="d.profile.previewInert">
              <PartnerModal :partner="previewCard" embedded :text-lang="previewLang" />
            </div>
            <div class="mt-2 flex flex-wrap items-center gap-2">
              <p class="flex-1 text-[11px] text-base-400">{{ d.profile.previewInert }}</p>
              <button type="button" class="btn btn-ghost h-8 px-3 text-xs" @click="previewLarge = true"><SiteIcon name="external" class="size-3.5" />{{ d.profile.previewLarge }}</button>
            </div>
          </aside>
          <PartnerModal v-if="previewLarge && previewCard" :partner="previewCard" :text-lang="previewLang" @close="previewLarge = false" @redeem="() => {}" />
          <LogoCropper v-if="cropOpen && cropFile" :file="cropFile" :name="dash.name" @done="onCropped" @close="cropOpen = false" @error="onCropError" />
        </section>

        <!-- Zugänge -->
        <section v-else-if="tab === 'members' && dash.members" class="mt-6 grid gap-6 lg:grid-cols-[1fr_20rem]">
          <div class="card p-5">
            <h2 class="section-title">{{ d.members.title }}</h2>
            <p class="mt-1 text-sm text-base-400">{{ d.members.text }}</p>
            <ul class="mt-4 divide-y divide-base-800">
              <li v-if="dash.owner" class="flex items-center gap-3 py-2">
                <PlayerHead :uuid="dash.owner.uuid" :name="dash.owner.name" :size="24" />
                <span class="flex-1 font-semibold text-base-50">{{ dash.owner.name }}</span>
                <span class="pill pill-ok">{{ d.members.owner }}</span>
              </li>
              <li v-for="m in dash.members" :key="m.uuid" class="py-3">
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
          </div>
          <form v-if="dash.viewer.owner" class="card h-fit p-5" @submit.prevent="invite">
            <h2 class="section-title">{{ d.members.invite }}</h2>
            <label class="label mt-4" for="mb-name">{{ d.members.invitePlaceholder }}</label>
            <input id="mb-name" v-model="inviteName" class="field" maxlength="16" pattern="[A-Za-z0-9_]{1,16}" required />
            <p class="label mt-3">{{ d.members.rights }}</p>
            <label v-for="r in PARTNER_RIGHTS" :key="r" class="chk"><input v-model="inviteRights" type="checkbox" :value="r" />{{ d.members.right[r] }}</label>
            <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'invite' || !inviteRights.length">{{ d.members.invite }}</button>
          </form>
          <p v-else class="note-box h-fit">{{ d.members.teamNoInvite }}</p>
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
.head-code {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  min-width: 0;
}
@media (min-width: 640px) {
  .head-code {
    width: 16rem;
  }
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
.state-warn {
  color: var(--color-lamp-300);
}
.state-danger {
  color: var(--color-redstone-300);
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
.checklist {
  border-left: 3px solid var(--color-redstone-500);
}
.check-row {
  display: flex;
  align-items: center;
  gap: 0.7rem;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.55rem 0.7rem;
}
.check-mark {
  display: grid;
  place-items: center;
  width: 1.35rem;
  height: 1.35rem;
  flex-shrink: 0;
  border-radius: 999px;
  border: 1.5px solid var(--color-base-600);
  color: var(--color-lamp-300);
}
.check-row[data-done='true'] .check-mark {
  border-color: var(--color-ok);
  background: color-mix(in srgb, var(--color-ok) 18%, transparent);
  color: var(--color-ok);
}
.delta {
  display: inline-flex;
  border-radius: 999px;
  padding: 0 0.45rem;
  font-size: 0.7rem;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  background: var(--color-base-800);
  color: var(--color-base-300);
}
.delta[data-tone='up'] {
  background: color-mix(in srgb, var(--color-ok) 16%, transparent);
  color: var(--color-ok);
}
.delta[data-tone='down'] {
  background: color-mix(in srgb, var(--color-redstone-500) 16%, transparent);
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
.chk {
  display: flex;
  align-items: flex-start;
  gap: 0.45rem;
  margin-top: 0.35rem;
  font-size: 0.85rem;
  color: var(--color-base-200);
}
.chk input {
  margin-top: 0.2rem;
}
.meter {
  height: 0.45rem;
  border-radius: 99px;
  background: var(--color-base-800);
  overflow: hidden;
}
.meter > div {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--color-redstone-600), var(--color-redstone-400));
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
.tbl th.text-right {
  text-align: right;
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
  overflow-wrap: anywhere;
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
.stage {
  border-top: 1px solid var(--color-base-800);
  background: radial-gradient(80% 70% at 50% 35%, color-mix(in srgb, var(--color-redstone-500) 9%, transparent), var(--color-base-950) 75%);
}
.item-card {
  display: flex;
  align-items: center;
  gap: 0.7rem;
  border-radius: 0.7rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.5rem 0.7rem;
  transition: border-color 0.15s, background-color 0.15s;
}
.item-card:hover {
  border-color: var(--color-base-700);
}
.item-card[aria-pressed='true'] {
  border-color: var(--color-redstone-500);
  background: var(--color-base-850);
}
.item-icon,
.hat-card {
  display: grid;
  place-items: center;
  width: 32px;
  height: 51px;
  flex-shrink: 0;
  border-radius: 0.3rem;
  background: var(--color-base-800);
  color: var(--color-lamp-300);
  object-fit: cover;
}
.cape-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(8.5rem, 1fr));
  gap: 0.6rem;
}
.cape-pick {
  display: flex;
  width: 100%;
  flex-direction: column;
  align-items: center;
  border-radius: 0.7rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.8rem 0.6rem 0.65rem;
  text-align: center;
  transition: border-color 0.15s, background-color 0.15s;
}
.cape-pick:hover {
  border-color: var(--color-base-700);
}
.cape-pick[aria-checked='true'] {
  border-color: var(--color-redstone-500);
  background: var(--color-base-850);
  box-shadow: 0 0 0 1px var(--color-redstone-500);
}
.profile-grid {
  display: grid;
  gap: 1.5rem;
}
@media (min-width: 1024px) {
  .profile-grid {
    grid-template-columns: minmax(0, 1fr) minmax(0, 25rem);
    align-items: start;
  }
  .preview-col {
    position: sticky;
    top: 1rem;
  }
}
.preview-col {
  min-width: 0;
}
.preview-frame {
  border-radius: 1.1rem;
  outline: 1px dashed var(--color-base-700);
  outline-offset: 4px;
  user-select: none;
}
.seg {
  display: inline-flex;
  border-radius: 999px;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-950);
  padding: 2px;
}
.seg button {
  border-radius: 999px;
  padding: 0.25rem 0.75rem;
  font-size: 0.75rem;
  font-weight: 700;
  color: var(--color-base-300);
}
.seg button[aria-pressed='true'] {
  background: var(--color-base-800);
  color: var(--color-base-50);
}
.seg button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.part-list {
  display: grid;
  gap: 0.5rem;
}
@media (min-width: 640px) {
  .part-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.part {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 0.6rem;
  border-radius: 0.7rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.55rem 0.7rem;
  transition: border-color 0.15s;
}
.part:hover,
.part:focus-visible {
  border-color: var(--color-base-600);
}
.part-mark {
  display: grid;
  place-items: center;
  width: 1.4rem;
  height: 1.4rem;
  flex-shrink: 0;
  border-radius: 999px;
}
.part-state {
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.part[data-state='done'] .part-mark {
  background: color-mix(in srgb, var(--color-ok) 22%, transparent);
  color: var(--color-ok);
}
.part[data-state='done'] .part-state {
  color: var(--color-ok);
}
.part[data-state='review'] .part-mark,
.part[data-state='draft'] .part-mark {
  background: color-mix(in srgb, var(--color-lamp-400) 20%, transparent);
  color: var(--color-lamp-300);
}
.part[data-state='review'] .part-state,
.part[data-state='draft'] .part-state {
  color: var(--color-lamp-300);
}
.part[data-state='missing'] {
  border-color: color-mix(in srgb, var(--color-redstone-500) 45%, var(--color-base-800));
}
.part[data-state='missing'] .part-mark {
  background: color-mix(in srgb, var(--color-redstone-500) 22%, transparent);
  color: var(--color-redstone-300);
}
.part[data-state='missing'] .part-state {
  color: var(--color-redstone-300);
}
.pill-miss {
  border-color: color-mix(in srgb, var(--color-redstone-500) 55%, transparent);
  color: var(--color-redstone-300);
  text-transform: none;
  letter-spacing: 0;
}
.logo-fig {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.7rem;
  font-weight: 600;
  color: var(--color-base-400);
}
.ring-review {
  border-style: dashed !important;
  border-color: var(--color-lamp-400) !important;
}
.ring-new {
  border-color: var(--color-redstone-500) !important;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-redstone-500) 25%, transparent);
}
.drop {
  display: flex;
  align-items: center;
  gap: 0.8rem;
  border-radius: 0.8rem;
  border: 1.5px dashed var(--color-base-700);
  background: var(--color-base-950);
  padding: 0.9rem 1rem;
  transition: border-color 0.15s, background-color 0.15s;
}
.drop[data-active='true'] {
  border-color: var(--color-redstone-400);
  background: color-mix(in srgb, var(--color-redstone-500) 8%, var(--color-base-950));
}
.counter {
  text-align: right;
  font-size: 0.75rem;
  color: var(--color-base-400);
  font-variant-numeric: tabular-nums;
}
.counter[data-tone='near'] {
  color: var(--color-lamp-300);
}
.counter[data-tone='full'] {
  color: var(--color-redstone-300);
  font-weight: 700;
}
.link-row {
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  border-radius: 0.7rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.55rem;
}
.link-row[data-error] {
  border-color: color-mix(in srgb, var(--color-redstone-500) 60%, var(--color-base-800));
}
.link-num {
  display: grid;
  place-items: center;
  width: 1.5rem;
  height: 2.5rem;
  flex-shrink: 0;
  font-size: 0.75rem;
  font-weight: 700;
  color: var(--color-base-500);
}
.link-fields {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 0.4rem;
}
@media (min-width: 640px) {
  .link-fields {
    grid-template-columns: 9rem minmax(0, 1fr);
  }
  .link-err {
    grid-column: 1 / -1;
  }
}
.link-fields [aria-invalid='true'] {
  border-color: var(--color-redstone-500);
}
.link-err {
  font-size: 0.75rem;
  color: var(--color-redstone-300);
}
.link-tools {
  display: flex;
  flex-shrink: 0;
  flex-wrap: wrap;
  gap: 0.15rem;
  width: 2rem;
}
@media (min-width: 640px) {
  .link-tools {
    width: auto;
    flex-wrap: nowrap;
  }
}
.fixed-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  border-radius: 0.7rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.7rem 0.8rem;
}
.fixed-row[data-main='true'] {
  border-color: color-mix(in srgb, var(--color-redstone-500) 45%, var(--color-base-800));
}
.fixed-row[data-active='false'] code {
  color: var(--color-base-500);
  text-decoration: line-through;
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
