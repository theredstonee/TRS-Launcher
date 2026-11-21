<script setup lang="ts">
// Partnerprogramm im Team-Bereich (§44.7, Recht partners.manage): Partner (Liste, bearbeiten, Kontingent, Kosmetik,
// Server ausblenden, pausieren, löschen, Dashboard im Team-Modus), Anträge (annehmen/einplanen/ablehnen mit Grund),
// Partner anlegen. Der Server prüft jede Anfrage.
const { api } = useAdmin()
const { p } = usePartnerText()
const { fill, date, lang } = useLang()
const route = useRoute()
const t = computed(() => p.value.admin)

type Tab = 'partners' | 'requests' | 'new'
const TABS: Tab[] = ['partners', 'requests', 'new']
const initial = route.query.tab
const tab = ref<Tab>(typeof initial === 'string' && (TABS as string[]).includes(initial) ? (initial as Tab) : 'partners')

const error = ref('')
const notice = ref('')
const busy = ref('')
const loading = ref(true)
const fail = (e: unknown) => (error.value = fill(t.value.failed, { error: p.value.dash.errors[apiCode(e)] ?? apiMessage(e) }))
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

// --- Partner ------------------------------------------------------------------------------------
const partners = ref<AdminPartner[]>([])
const catalog = ref<PartnerCosmeticRef[]>([])
const editing = ref<string | null>(null)
const num = (n: number) => new Intl.NumberFormat(lang.value === 'en' ? 'en-GB' : lang.value).format(n)
async function loadPartners() {
  partners.value = (await api<{ partners: AdminPartner[] }>('/v1/admin/partners')).partners
}
async function loadCatalog() {
  if (!catalog.value.length) catalog.value = (await api<{ items: PartnerCosmeticRef[] }>('/v1/admin/partners/catalog')).items
}
const current = computed(() => partners.value.find((x) => x.slug === editing.value) ?? null)
interface EditForm { name: string, category: PartnerCategory, code: string, prefix: string, owner: string, wikiUrl: string, de: string, en: string, links: PartnerLink[] }
const form = reactive<EditForm>({ name: '', category: 'hosting', code: '', prefix: '', owner: '', wikiUrl: '', de: '', en: '', links: [] })
const grant = ref<500 | 1000 | 5000>(1000)
const pick = ref('')
async function startEdit(x: AdminPartner) {
  editing.value = editing.value === x.slug ? null : x.slug
  if (!editing.value) return
  Object.assign(form, {
    name: x.name, category: x.category, code: x.code ?? '', prefix: x.prefix, owner: x.owner?.name ?? '', wikiUrl: x.wikiUrl ?? '',
    de: x.text.de, en: x.text.en, links: x.links.map((l) => ({ ...l })),
  })
  pick.value = ''
  await loadCatalog().catch(fail)
}
async function save() {
  const x = current.value
  if (!x) return
  const body: Record<string, unknown> = {
    name: form.name.trim(),
    category: form.category,
    prefix: form.prefix.trim().toUpperCase(),
    wikiUrl: form.wikiUrl.trim() || null,
    text: { de: form.de, en: form.en },
    links: form.links.filter((l) => l.label.trim() && l.url.trim()).map((l) => ({ label: l.label.trim(), url: l.url.trim() })),
  }
  if (form.code.trim().toUpperCase() !== (x.code ?? '')) body.code = form.code.trim().toUpperCase()
  if (form.owner.trim() && form.owner.trim() !== x.owner?.name) body.owner = form.owner.trim()
  await act(`save:${x.slug}`, async () => {
    await api(`/v1/admin/partners/${x.slug}`, { method: 'PATCH', body })
    await loadPartners()
  }, t.value.saved)
}
const patch = (x: AdminPartner, body: Record<string, unknown>) => act(`patch:${x.slug}`, async () => {
  await api(`/v1/admin/partners/${x.slug}`, { method: 'PATCH', body })
  await loadPartners()
}, t.value.saved)
const grantQuota = (x: AdminPartner) => act(`quota:${x.slug}`, async () => {
  await api(`/v1/admin/partners/${x.slug}/quota`, { method: 'POST', body: { amount: grant.value } })
  await loadPartners()
}, fill(t.value.granted, { n: num(grant.value) }))
const assign = (x: AdminPartner) => act(`item:${x.slug}`, async () => {
  const [kind, ...rest] = pick.value.split(':')
  await api(`/v1/admin/partners/${x.slug}/items`, { method: 'POST', body: { kind, id: rest.join(':') } })
  pick.value = ''
  await loadPartners()
})
const unassign = (x: AdminPartner, i: PartnerCosmeticRef) => act(`item:${x.slug}`, async () => {
  await api(`/v1/admin/partners/${x.slug}/items/${i.kind}/${encodeURIComponent(i.id)}`, { method: 'DELETE' })
  await loadPartners()
})
const setServerHidden = (x: AdminPartner, hidden: boolean) => act(`server:${x.slug}`, async () => {
  await api(`/v1/admin/partners/${x.slug}/server`, { method: 'PATCH', body: { hidden } })
  await loadPartners()
})
async function remove(x: AdminPartner) {
  if (!confirm(fill(t.value.deleteConfirm, { name: x.name }))) return
  await act(`del:${x.slug}`, async () => {
    await api(`/v1/admin/partners/${x.slug}`, { method: 'DELETE' })
    editing.value = null
    await loadPartners()
  })
}
const available = computed(() => {
  const have = new Set((current.value?.items ?? []).map((i) => `${i.kind}:${i.id}`))
  return catalog.value.filter((i) => !have.has(`${i.kind}:${i.id}`))
})

// --- Anträge ------------------------------------------------------------------------------------
type ReqStatus = 'open' | 'accepted' | 'rejected' | 'withdrawn'
const reqStatus = ref<ReqStatus>('open')
const requests = ref<AdminPartnerRequest[]>([])
const rejecting = ref<number | null>(null)
const reason = ref('')
async function loadRequests() {
  requests.value = (await api<{ requests: AdminPartnerRequest[] }>(`/v1/admin/partners/requests?status=${reqStatus.value}`)).requests
}
watch(reqStatus, () => void loadRequests().catch(fail))
function describe(r: AdminPartnerRequest): string {
  const d = r.data as Record<string, string | number>
  return fill(t.value.kinds[r.kind] ?? r.kind, { ...d, channel: r.channel ? p.value.dash.promo[r.channel] : '' } as Record<string, string | number>)
}
const decide = (r: AdminPartnerRequest, status: 'accepted' | 'rejected') => act(`req:${r.id}`, async () => {
  await api(`/v1/admin/partners/requests/${r.id}`, { method: 'POST', body: status === 'rejected' ? { status, reason: reason.value.trim().slice(0, 300) } : { status } })
  rejecting.value = null
  reason.value = ''
  await Promise.all([loadRequests(), loadPartners()])
})

// --- Anlegen ------------------------------------------------------------------------------------
const create = reactive({ name: '', slug: '', category: 'hosting' as PartnerCategory, code: '', prefix: '', owner: '', quota: 1000 as 0 | 500 | 1000 })
watch(() => create.name, (n, old) => {
  const auto = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 32)
  if (!create.slug || create.slug === auto(old)) create.slug = auto(n)
})
const submitCreate = () => act('create', async () => {
  await api('/v1/admin/partners', {
    method: 'POST',
    body: { ...create, name: create.name.trim(), slug: create.slug.trim(), code: create.code.trim().toUpperCase(), prefix: create.prefix.trim().toUpperCase(), owner: create.owner.trim() },
  })
  Object.assign(create, { name: '', slug: '', code: '', prefix: '', owner: '' })
  await loadPartners()
  tab.value = 'partners'
}, t.value.created)

onMounted(async () => {
  try {
    await Promise.all([loadPartners(), loadRequests()])
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ t.title }}</h1>
    </header>
    <div class="adm-toolbar mt-6">
      <div class="adm-seg">
        <button v-for="x in TABS" :key="x" type="button" :aria-pressed="tab === x" @click="tab = x">
          {{ t.tabs[x] }}<template v-if="x === 'requests' && reqStatus === 'open' && requests.length"> ({{ requests.length }})</template>
        </button>
      </div>
    </div>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <p v-if="notice" class="mt-4 text-sm text-ok" role="status">{{ notice }}</p>
    <div v-if="loading" class="skeleton mt-6 h-40 rounded-xl" />

    <!-- Partner -->
    <template v-else-if="tab === 'partners'">
      <p v-if="!partners.length" class="adm-empty mt-6"><SiteIcon name="hand" class="size-6" />{{ t.none }}</p>
      <div v-else class="card mt-6 overflow-x-auto p-0">
        <table class="adm-table w-full text-sm">
          <thead>
            <tr><th>{{ t.partner }}</th><th>{{ t.category }}</th><th>{{ t.code }}</th><th>{{ t.quota }}</th><th>{{ t.supporters }}</th><th /></tr>
          </thead>
          <tbody>
            <tr v-for="x in partners" :key="x.slug">
              <td>
                <div class="flex items-center gap-2">
                  <PartnerLogo :logo="x.logo" :name="x.name" :size="28" />
                  <span class="font-semibold text-base-50">{{ x.name }}</span>
                  <span v-if="x.state === 'paused'" class="pill pill-warn">{{ t.state.paused }}</span>
                  <span v-if="x.openRequests" class="pill">{{ x.openRequests }}</span>
                </div>
              </td>
              <td>{{ p.category[x.category] }}</td>
              <td><code class="font-mono text-lamp-300">{{ x.code }}</code></td>
              <td class="tabular-nums">{{ num(x.quota.used) }} / {{ num(x.quota.total) }}</td>
              <td class="tabular-nums">{{ num(x.supporters) }}</td>
              <td class="text-right whitespace-nowrap">
                <NuxtLink :to="`/partner/${x.slug}`" class="btn btn-ghost h-8 px-3 text-xs">{{ t.open }}</NuxtLink>
                <button type="button" class="btn btn-ghost h-8 px-3 text-xs" :aria-expanded="editing === x.slug" @click="startEdit(x)">{{ t.edit }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <section v-if="current" class="mt-6 grid gap-4 lg:grid-cols-[1.4fr_1fr]">
        <form class="card p-5" @submit.prevent="save">
          <h2 class="text-lg font-semibold text-base-50">{{ fill(t.editTitle, { name: current.name }) }}</h2>
          <div class="mt-3 grid gap-3 sm:grid-cols-2">
            <label class="adm-label">{{ t.name }}<input v-model="form.name" class="field" maxlength="40" required /></label>
            <label class="adm-label">{{ t.category }}
              <select v-model="form.category" class="field"><option v-for="c in PARTNER_CATEGORIES" :key="c" :value="c">{{ p.category[c] }}</option></select>
            </label>
            <label class="adm-label">{{ t.code }}<input v-model="form.code" class="field font-mono uppercase" maxlength="19" pattern="[A-Za-z0-9]{4,19}" /></label>
            <label class="adm-label">{{ t.prefix }}<input v-model="form.prefix" class="field font-mono uppercase" maxlength="4" pattern="[A-Za-z]{2,4}" /></label>
            <label class="adm-label">{{ t.owner }}<input v-model="form.owner" class="field" maxlength="36" /></label>
            <label class="adm-label">{{ t.wikiUrl }}<input v-model="form.wikiUrl" class="field" type="url" maxlength="300" placeholder="https://" /></label>
          </div>
          <label class="adm-label mt-3">{{ t.textDe }}<textarea v-model="form.de" class="field" rows="4" maxlength="1200" /></label>
          <label class="adm-label mt-3">{{ t.textEn }}<textarea v-model="form.en" class="field" rows="4" maxlength="1200" /></label>
          <p class="adm-label mt-3">{{ t.links }}</p>
          <div v-for="(l, i) in form.links" :key="i" class="mt-1 flex gap-2">
            <input v-model="l.label" class="field w-40" maxlength="40" :placeholder="p.dash.profile.linkLabel" />
            <input v-model="l.url" class="field flex-1" type="url" maxlength="300" placeholder="https://" />
            <button type="button" class="btn-icon" :aria-label="t.unassign" @click="form.links.splice(i, 1)"><SiteIcon name="close" class="size-4" /></button>
          </div>
          <button v-if="form.links.length < 6" type="button" class="btn btn-ghost h-8 px-3 text-xs mt-2" @click="form.links.push({ label: '', url: '' })"><SiteIcon name="plus" class="size-4" />{{ p.dash.profile.addLink }}</button>
          <div class="mt-4"><button type="submit" class="btn btn-primary" :disabled="busy === `save:${current.slug}`">{{ t.save }}</button></div>
        </form>

        <div class="grid content-start gap-4">
          <div class="card p-5">
            <h3 class="font-semibold text-base-50">{{ t.grantTitle }}</h3>
            <p class="mt-1 text-xs text-base-400">{{ fill(p.dash.quota.used, { used: num(current.quota.used), total: num(current.quota.total) }) }}</p>
            <div class="mt-3 flex gap-2">
              <select v-model.number="grant" class="field w-32"><option :value="500">+500</option><option :value="1000">+1.000</option><option :value="5000">+5.000</option></select>
              <button type="button" class="btn btn-primary" :disabled="busy === `quota:${current.slug}`" @click="grantQuota(current)">{{ t.grant }}</button>
            </div>
          </div>
          <div class="card p-5">
            <h3 class="font-semibold text-base-50">{{ t.itemsTitle }}</h3>
            <p class="mt-1 text-xs text-base-400">{{ t.itemsText }}</p>
            <ul class="mt-3 space-y-1">
              <li v-for="i in current.items" :key="`${i.kind}:${i.id}`" class="flex items-center gap-2 text-sm">
                <SiteIcon :name="i.kind === 'cape' ? 'cape' : 'crown'" class="size-4 text-base-400" />
                <span class="flex-1 text-base-100">{{ i.name }} <span class="text-xs text-base-500">{{ i.id }}</span></span>
                <button type="button" class="btn btn-ghost h-8 px-3 text-xs" @click="unassign(current, i)">{{ t.unassign }}</button>
              </li>
            </ul>
            <div class="mt-3 flex gap-2">
              <select v-model="pick" class="field flex-1">
                <option value="" disabled>{{ t.pickItem }}</option>
                <option v-for="i in available" :key="`${i.kind}:${i.id}`" :value="`${i.kind}:${i.id}`">{{ i.kind === 'cape' ? p.pub.cape : p.pub.cosmetic }} · {{ i.name }}</option>
              </select>
              <button type="button" class="btn btn-ghost" :disabled="!pick || busy === `item:${current.slug}`" @click="assign(current)">{{ t.assign }}</button>
            </div>
          </div>
          <div v-if="current.server" class="card p-5">
            <h3 class="font-semibold text-base-50">{{ t.serverTitle }}</h3>
            <p class="mt-1 text-sm text-base-300">{{ current.server.name }} · <code>{{ current.server.address }}</code></p>
            <button type="button" class="btn btn-ghost mt-3" @click="setServerHidden(current, !current.server.hidden)">{{ current.server.hidden ? t.showServer : t.hideServer }}</button>
          </div>
          <div class="card p-5">
            <h3 class="font-semibold text-base-50">{{ t.pauseTitle }}</h3>
            <p class="mt-1 text-xs text-base-400">{{ t.pauseText }}</p>
            <div class="mt-3 flex flex-wrap gap-2">
              <button type="button" class="btn btn-ghost" @click="patch(current, { state: current.state === 'active' ? 'paused' : 'active' })">{{ current.state === 'active' ? t.pause : t.resume }}</button>
              <button type="button" class="btn btn-danger" @click="remove(current)">{{ t.delete }}</button>
            </div>
          </div>
        </div>
      </section>
    </template>

    <!-- Anträge -->
    <template v-else-if="tab === 'requests'">
      <div class="mt-6 flex flex-wrap gap-2">
        <button v-for="s in (['open', 'accepted', 'rejected', 'withdrawn'] as const)" :key="s" type="button" class="chip-btn" :aria-pressed="reqStatus === s" @click="reqStatus = s">{{ t.filter[s] }}</button>
      </div>
      <p v-if="!requests.length" class="adm-empty mt-6"><SiteIcon name="check" class="size-6" />{{ t.noRequests }}</p>
      <ul v-else class="mt-4 space-y-3">
        <li v-for="r in requests" :key="r.id" class="card p-4">
          <div class="flex flex-wrap items-center gap-3">
            <img v-if="r.hasImage" :src="`/v1/admin/partners/requests/${r.id}/image.png`" alt="" class="req-img" />
            <div class="min-w-0 flex-1">
              <p class="font-semibold text-base-50">{{ describe(r) }}</p>
              <p class="text-xs text-base-400">{{ r.partner.name }} · {{ r.createdBy?.name ?? '' }} · {{ date(r.createdAt) }}<template v-if="r.reason"> · {{ fill(p.dash.rejected, { reason: r.reason }) }}</template></p>
            </div>
            <template v-if="r.status === 'open'">
              <button type="button" class="btn btn-primary" :disabled="busy === `req:${r.id}`" @click="decide(r, 'accepted')">{{ r.kind === 'promo' ? t.plan : t.accept }}</button>
              <button type="button" class="btn btn-ghost" @click="rejecting = rejecting === r.id ? null : r.id">{{ t.reject }}</button>
            </template>
            <span v-else class="pill">{{ p.dash.status[r.status] }}</span>
          </div>
          <div v-if="r.kind === 'profile'" class="req-detail mt-3">
            <p class="whitespace-pre-line">{{ (r.data.text as { de: string })?.de }}</p>
            <p class="mt-2 whitespace-pre-line text-base-400">{{ (r.data.text as { en: string })?.en }}</p>
            <p v-for="l in (r.data.links as PartnerLink[])" :key="l.url" class="mt-1 text-xs">{{ l.label }} – {{ l.url }}</p>
          </div>
          <div v-else-if="r.kind === 'promo' || r.kind === 'cosmetic_wish'" class="req-detail mt-3">
            <p class="whitespace-pre-line">{{ r.data.text }}</p>
            <p v-if="r.kind === 'promo'" class="mt-2 text-xs text-base-400">{{ t.promoNote }}</p>
          </div>
          <div v-else-if="r.kind === 'server'" class="req-detail mt-3">
            <p><b>{{ r.data.name }}</b> – {{ r.data.motd }}</p>
          </div>
          <form v-if="rejecting === r.id" class="mt-3 flex flex-wrap gap-2" @submit.prevent="decide(r, 'rejected')">
            <input v-model="reason" class="field flex-1" maxlength="300" required :placeholder="t.rejectReason" />
            <button type="submit" class="btn btn-danger" :disabled="!reason.trim()">{{ t.reject }}</button>
          </form>
        </li>
      </ul>
    </template>

    <!-- Anlegen -->
    <form v-else class="card mt-6 max-w-2xl p-5" @submit.prevent="submitCreate">
      <div class="grid gap-3 sm:grid-cols-2">
        <label class="adm-label">{{ t.name }}<input v-model="create.name" class="field" maxlength="40" required placeholder="CraftingHosting" /></label>
        <label class="adm-label">{{ t.slug }}<span class="flex items-center gap-1"><span class="text-xs text-base-400">/partners/</span><input v-model="create.slug" class="field flex-1" maxlength="32" required pattern="[a-z0-9][a-z0-9\-]{1,30}[a-z0-9]" /></span></label>
        <label class="adm-label">{{ t.category }}
          <select v-model="create.category" class="field"><option v-for="c in PARTNER_CATEGORIES" :key="c" :value="c">{{ p.category[c] }}</option></select>
        </label>
        <label class="adm-label">{{ t.code }}<input v-model="create.code" class="field font-mono uppercase" maxlength="19" required pattern="[A-Za-z0-9]{4,19}" placeholder="CRAFTING" /></label>
        <label class="adm-label">{{ t.prefix }}<input v-model="create.prefix" class="field font-mono uppercase" maxlength="4" required pattern="[A-Za-z]{2,4}" placeholder="CH" /></label>
        <label class="adm-label">{{ t.owner }}<input v-model="create.owner" class="field" maxlength="36" required /></label>
        <label class="adm-label">{{ t.startQuota }}
          <select v-model.number="create.quota" class="field"><option :value="0">0</option><option :value="500">500</option><option :value="1000">1.000</option></select>
        </label>
      </div>
      <button type="submit" class="btn btn-primary mt-4" :disabled="busy === 'create'">{{ t.create }}</button>
    </form>
  </div>
</template>

<style scoped>
.adm-label {
  display: grid;
  gap: 0.3rem;
  font-size: 0.75rem;
  color: var(--color-base-400);
}
.adm-table th {
  text-align: left;
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--color-base-400);
  padding: 0.6rem 0.8rem;
  border-bottom: 1px solid var(--color-base-800);
}
.adm-table td {
  padding: 0.6rem 0.8rem;
  border-bottom: 1px solid var(--color-base-850);
  color: var(--color-base-200);
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
.pill-warn {
  color: var(--color-lamp-300);
}
.chip-btn {
  border-radius: 999px;
  border: 1px solid var(--color-base-700);
  padding: 0.25rem 0.8rem;
  font-size: 0.8rem;
  color: var(--color-base-300);
}
.chip-btn[aria-pressed='true'] {
  color: var(--color-base-50);
  background: var(--color-base-800);
}
.req-img {
  width: 64px;
  height: 64px;
  object-fit: contain;
  image-rendering: pixelated;
  border-radius: 0.4rem;
  background: var(--color-base-950);
}
.req-detail {
  border-radius: 0.6rem;
  background: var(--color-base-950);
  padding: 0.7rem 0.9rem;
  font-size: 0.85rem;
  color: var(--color-base-200);
  overflow-wrap: anywhere;
}
</style>
