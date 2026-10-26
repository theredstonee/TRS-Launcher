<script setup lang="ts">
import type { CampaignListItem, MailKeyView, SubscriberStats, TeamNotifyKind, TeamNotifyView } from '#shared/newsletter'

// Newsletter (§40) im Team-Bereich: Liste der Kampagnen, Abonnenten-Zahlen, API-Schlüssel (Owner/Admin) und die
// eigenen Mail-Benachrichtigungen (jedes Team-Mitglied für sich). Rechte prüft der Server.
const { a, when, num } = useAdminText()
const { n, fill } = useNewsletterText()
const { api, can, session } = useAdmin()
const route = useRoute()
const router = useRouter()
const t = computed(() => n.value.adm)

const canManage = computed(() => can('newsletter.manage'))
const canKeys = computed(() => !!session.value && (session.value.owner || session.value.rank >= ADMIN_RANK))
type Tab = 'campaigns' | 'subscribers' | 'keys' | 'notifications'
const tabs = computed<Tab[]>(() => [
  ...(canManage.value ? ['campaigns' as const, 'subscribers' as const] : []),
  ...(canKeys.value ? ['keys' as const] : []),
  'notifications' as const,
])
const tab = ref<Tab>('notifications')
watch(tabs, (list) => {
  const q = route.query.tab
  tab.value = typeof q === 'string' && (list as string[]).includes(q) ? q as Tab : list[0]!
}, { immediate: true })
watch(tab, (v) => void router.replace({ query: { ...route.query, tab: v } }))

function human(e: unknown): string {
  return t.value.errors[apiCode(e)] ?? fill(a.value.common.failed, { error: apiMessage(e) })
}

// --- Kampagnen -------------------------------------------------------------------------------------
const campaigns = ref<CampaignListItem[]>([])
const mailAvailable = ref(true)
const loading = ref(false)
const error = ref('')
const busy = ref('')
const removing = ref<CampaignListItem | null>(null)

async function loadCampaigns() {
  loading.value = true
  error.value = ''
  try {
    campaigns.value = (await api<{ campaigns: CampaignListItem[] }>('/v1/admin/newsletter/campaigns')).campaigns
  } catch (e) {
    error.value = human(e)
  } finally {
    loading.value = false
  }
}
async function create() {
  busy.value = 'new'
  try {
    const r = await api<{ campaign: { id: string } }>('/v1/admin/newsletter/campaigns', { method: 'POST', body: { name: t.value.untitled } })
    void router.push(`/admin/newsletter/${r.campaign.id}`)
  } catch (e) {
    error.value = human(e)
  } finally {
    busy.value = ''
  }
}
async function duplicate(c: CampaignListItem) {
  busy.value = `d:${c.id}`
  try {
    const r = await api<{ campaign: { id: string } }>(`/v1/admin/newsletter/campaigns/${c.id}/duplicate`, { method: 'POST' })
    void router.push(`/admin/newsletter/${r.campaign.id}`)
  } catch (e) {
    error.value = human(e)
  } finally {
    busy.value = ''
  }
}
async function remove() {
  const c = removing.value
  if (!c) return
  busy.value = `x:${c.id}`
  try {
    await api(`/v1/admin/newsletter/campaigns/${c.id}`, { method: 'DELETE' })
    removing.value = null
    await loadCampaigns()
  } catch (e) {
    removing.value = null
    error.value = human(e)
  } finally {
    busy.value = ''
  }
}
const tone = (s: string) => (s === 'sent' ? 'tone-ok' : s === 'sending' ? 'tone-warn' : s === 'scheduled' ? 'tone-info' : 'tone-muted')
function line(c: CampaignListItem): string {
  if (c.status === 'scheduled' && c.scheduledAt) return fill(t.value.scheduledFor, { date: when(c.scheduledAt) })
  if (c.status === 'sent' && c.sentAt) return `${fill(t.value.sentAt, { date: when(c.sentAt) })} · ${fill(t.value.progress, { sent: num(c.sent), total: num(c.total), failed: num(c.failed) })}`
  if (c.status === 'sending') return fill(t.value.progress, { sent: num(c.sent), total: num(c.total), failed: num(c.failed) })
  return fill(t.value.editedBy, { date: when(c.updatedAt), name: c.updatedBy.kind === 'key' ? `${c.updatedBy.name} (API)` : c.updatedBy.name })
}

// --- Abonnenten ------------------------------------------------------------------------------------
const stats = ref<SubscriberStats | null>(null)
async function loadStats() {
  try {
    const r = await api<{ stats: SubscriberStats, mailAvailable: boolean }>('/v1/admin/newsletter/stats')
    stats.value = r.stats
    mailAvailable.value = r.mailAvailable
  } catch (e) {
    error.value = human(e)
  }
}

// --- API-Schlüssel ----------------------------------------------------------------------------------
const keys = ref<MailKeyView[]>([])
const keyForm = ref({ name: '', manage: true, send: false, testEmail: '' })
const newToken = ref('')
const copied = ref(false)
const revoking = ref<MailKeyView | null>(null)
const keyMsg = ref('')
async function loadKeys() {
  try {
    keys.value = (await api<{ keys: MailKeyView[] }>('/v1/admin/newsletter/keys')).keys
  } catch (e) {
    keyMsg.value = human(e)
  }
}
async function createKey() {
  const f = keyForm.value
  const permissions = [...(f.manage ? ['newsletter.manage'] : []), ...(f.send ? ['newsletter.send'] : [])]
  if (!f.name.trim() || permissions.length === 0) return
  busy.value = 'key'
  keyMsg.value = ''
  try {
    const r = await api<{ key: MailKeyView, token: string }>('/v1/admin/newsletter/keys', { method: 'POST', body: { name: f.name.trim(), permissions, testEmail: f.testEmail.trim() || null } })
    newToken.value = r.token
    copied.value = false
    keyForm.value = { name: '', manage: true, send: false, testEmail: '' }
    await loadKeys()
  } catch (e) {
    keyMsg.value = human(e)
  } finally {
    busy.value = ''
  }
}
async function copyToken() {
  try {
    await navigator.clipboard.writeText(newToken.value)
    copied.value = true
  } catch {
    copied.value = false
  }
}
async function revoke() {
  const k = revoking.value
  if (!k) return
  busy.value = `r:${k.id}`
  try {
    await api(`/v1/admin/newsletter/keys/${k.id}`, { method: 'DELETE' })
    revoking.value = null
    await loadKeys()
  } catch (e) {
    revoking.value = null
    keyMsg.value = human(e)
  } finally {
    busy.value = ''
  }
}

// --- Eigene Benachrichtigungen ------------------------------------------------------------------------
const notify = ref<TeamNotifyView | null>(null)
const notifyDraft = ref({ email: '', kinds: [] as TeamNotifyKind[], lang: 'de' as 'de' | 'en' | 'es' })
const notifyMsg = ref('')
async function loadNotify() {
  try {
    const r = await api<{ notifications: TeamNotifyView, mailAvailable: boolean }>('/v1/admin/newsletter/notifications')
    setNotify(r.notifications)
    mailAvailable.value = r.mailAvailable
  } catch (e) {
    notifyMsg.value = human(e)
  }
}
function setNotify(v: TeamNotifyView) {
  notify.value = v
  notifyDraft.value = { email: v.pendingEmail ?? v.email ?? '', kinds: [...v.kinds], lang: v.lang }
}
async function saveNotify(removeAddress = false) {
  const d = notifyDraft.value
  const cur = notify.value
  busy.value = 'notify'
  notifyMsg.value = ''
  try {
    const email = removeAddress ? null : d.email.trim() && d.email.trim() !== (cur?.email ?? '') && d.email.trim() !== (cur?.pendingEmail ?? '') ? d.email.trim() : undefined
    const r = await api<{ notifications: TeamNotifyView, confirmationSent: boolean }>('/v1/admin/newsletter/notifications', { method: 'PUT', body: { ...(email !== undefined ? { email } : {}), kinds: d.kinds, lang: d.lang } })
    setNotify(r.notifications)
    notifyMsg.value = r.confirmationSent ? t.value.notify.confirmationSent : t.value.notify.saved
  } catch (e) {
    notifyMsg.value = human(e)
  } finally {
    busy.value = ''
  }
}

watch(tab, (v) => {
  if (v === 'campaigns') void loadCampaigns()
  if (v === 'subscribers' || v === 'campaigns') void loadStats()
  if (v === 'keys') void loadKeys()
  if (v === 'notifications') void loadNotify()
}, { immediate: true })
</script>

<template>
  <div class="adm-page">
    <header class="flex flex-wrap items-end gap-3">
      <div class="min-w-0 flex-1">
        <h1 class="adm-title">{{ t.title }}</h1>
        <p class="adm-lead">{{ t.lead }}</p>
      </div>
      <button v-if="tab === 'campaigns' && canManage" type="button" class="btn btn-primary" :disabled="busy === 'new'" data-testid="nl-new" @click="create">
        <SiteIcon name="plus" class="size-4" />{{ t.newCampaign }}
      </button>
    </header>

    <div class="adm-tabs mt-5" role="tablist">
      <button v-for="x in tabs" :key="x" type="button" role="tab" :aria-selected="tab === x" :aria-pressed="tab === x" @click="tab = x">{{ t.tabs[x] }}</button>
    </div>

    <p v-if="!mailAvailable" class="nl-warn mt-4"><SiteIcon name="warn" class="size-4 shrink-0" />{{ t.mailOff }}</p>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>

    <!-- Kampagnen -->
    <template v-if="tab === 'campaigns'">
      <div v-if="loading" class="skeleton mt-6 h-40 rounded-xl" />
      <div v-else-if="!campaigns.length" class="adm-empty mt-6"><SiteIcon name="mail" class="size-6" />{{ t.empty }}</div>
      <ul v-else class="mt-6 space-y-2" data-testid="nl-list">
        <li v-for="c in campaigns" :key="c.id">
          <article class="adm-row items-center">
            <span class="nl-look" :data-look="c.resolvedLook" :title="t.looks[c.look]" />
            <div class="min-w-0 flex-1">
              <p class="flex flex-wrap items-center gap-2">
                <NuxtLink :to="`/admin/newsletter/${c.id}`" class="font-semibold text-base-50 hover:underline">{{ c.name || t.untitled }}</NuxtLink>
                <span class="tone" :class="tone(c.status)">{{ t.statuses[c.status] }}</span>
              </p>
              <p class="mt-0.5 truncate text-sm text-base-300">{{ c.subject }}</p>
              <p class="mt-0.5 text-xs text-base-400">{{ line(c) }}</p>
            </div>
            <div class="flex flex-wrap gap-1.5">
              <NuxtLink :to="`/admin/newsletter/${c.id}`" class="btn btn-ghost px-2.5 py-1 text-xs">{{ t.open }}</NuxtLink>
              <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="busy === `d:${c.id}`" @click="duplicate(c)"><SiteIcon name="copy" class="size-3.5" />{{ t.duplicate }}</button>
              <button v-if="c.status !== 'scheduled' && c.status !== 'sending'" type="button" class="btn btn-ghost px-2.5 py-1 text-xs text-redstone-300" @click="removing = c"><SiteIcon name="trash" class="size-3.5" /></button>
            </div>
          </article>
        </li>
      </ul>
    </template>

    <!-- Abonnenten -->
    <template v-else-if="tab === 'subscribers'">
      <div v-if="!stats" class="skeleton mt-6 h-40 rounded-xl" />
      <template v-else>
        <div class="mt-6 grid gap-3 sm:grid-cols-4">
          <div class="card p-4"><p class="text-xs text-base-400">{{ t.stats.confirmed }}</p><p class="mt-1 text-3xl font-semibold text-base-50" data-testid="nl-confirmed">{{ num(stats.byStatus.confirmed) }}</p></div>
          <div class="card p-4"><p class="text-xs text-base-400">{{ t.stats.pending }}</p><p class="mt-1 text-3xl font-semibold text-base-50">{{ num(stats.byStatus.pending) }}</p></div>
          <div class="card p-4"><p class="text-xs text-base-400">{{ t.stats.unsubscribed }}</p><p class="mt-1 text-3xl font-semibold text-base-50">{{ num(stats.byStatus.unsubscribed) }}</p></div>
          <div class="card p-4"><p class="text-xs text-base-400">{{ t.stats.last30 }}</p><p class="mt-1 text-3xl font-semibold text-base-50">{{ num(stats.confirmedLast30d) }}</p></div>
        </div>
        <div class="mt-3 grid gap-3 sm:grid-cols-2">
          <div class="card p-4">
            <p class="text-sm font-semibold text-base-100">{{ t.stats.byLang }}</p>
            <dl class="adm-dl mt-2"><template v-for="(v, l) in stats.byLang" :key="l"><dt>{{ t.langs[l] }}</dt><dd>{{ num(v) }}</dd></template></dl>
          </div>
          <div class="card p-4">
            <p class="text-sm font-semibold text-base-100">{{ t.stats.bySource }}</p>
            <dl class="adm-dl mt-2"><template v-for="(v, s) in stats.bySource" :key="s"><dt>{{ t.stats.sources[s] }}</dt><dd>{{ num(v) }}</dd></template></dl>
          </div>
        </div>
        <p class="mt-3 text-xs text-base-500">{{ t.stats.note }}</p>
      </template>
    </template>

    <!-- API-Schlüssel -->
    <template v-else-if="tab === 'keys'">
      <section class="card mt-6 p-5">
        <h2 class="text-lg font-semibold text-base-50">{{ t.keys.title }}</h2>
        <p class="mt-1 text-sm text-base-400">{{ t.keys.lead }}</p>
        <form class="mt-4 grid gap-3 md:grid-cols-[1fr_auto_1fr_auto] md:items-end" @submit.prevent="createKey">
          <div>
            <label class="label" for="nk-name">{{ t.keys.name }}</label>
            <input id="nk-name" v-model="keyForm.name" class="field" maxlength="60" placeholder="Claude" />
          </div>
          <fieldset class="flex flex-col gap-1 text-sm text-base-200">
            <legend class="label">{{ t.keys.perms }}</legend>
            <label class="flex items-center gap-2"><input v-model="keyForm.manage" type="checkbox" />{{ t.keys.manage }}</label>
            <label class="flex items-center gap-2"><input v-model="keyForm.send" type="checkbox" />{{ t.keys.send }}</label>
          </fieldset>
          <div>
            <label class="label" for="nk-test">{{ t.keys.testEmail }}</label>
            <input id="nk-test" v-model="keyForm.testEmail" class="field" type="email" maxlength="254" />
          </div>
          <button type="submit" class="btn btn-primary" :disabled="busy === 'key' || !keyForm.name.trim() || (!keyForm.manage && !keyForm.send)"><SiteIcon name="key" class="size-4" />{{ t.keys.create }}</button>
        </form>
        <div v-if="newToken" class="nl-token mt-4" role="status">
          <p class="text-sm text-base-200">{{ t.keys.created }}</p>
          <div class="mt-2 flex flex-wrap items-center gap-2">
            <code class="min-w-0 flex-1 break-all">{{ newToken }}</code>
            <button type="button" class="btn btn-ghost" @click="copyToken"><SiteIcon :name="copied ? 'check' : 'copy'" class="size-4" />{{ copied ? t.keys.copied : t.keys.copy }}</button>
          </div>
        </div>
        <p v-if="keyMsg" role="alert" class="mt-3 text-sm text-redstone-300">{{ keyMsg }}</p>
      </section>
      <div v-if="!keys.length" class="adm-empty mt-4"><SiteIcon name="key" class="size-6" />{{ t.keys.empty }}</div>
      <ul v-else class="mt-4 space-y-2">
        <li v-for="k in keys" :key="k.id" class="adm-row items-center" :class="{ 'opacity-60': k.revokedAt }">
          <SiteIcon name="key" class="size-5 shrink-0 text-base-400" />
          <div class="min-w-0 flex-1">
            <p class="flex flex-wrap items-center gap-2">
              <span class="font-semibold text-base-50">{{ k.name }}</span>
              <code class="text-xs text-base-400">trsk_…{{ k.hint }}</code>
              <span v-for="p in k.permissions" :key="p" class="tone tone-muted">{{ p === 'newsletter.send' ? t.keys.send : t.keys.manage }}</span>
            </p>
            <p class="mt-0.5 text-xs text-base-400">
              {{ k.revokedAt ? fill(t.keys.revoked, { date: when(k.revokedAt) }) : k.lastUsedAt ? fill(t.keys.lastUsed, { date: when(k.lastUsedAt) }) : t.keys.never }}
              <span v-if="k.testEmail"> · {{ k.testEmail }}</span>
            </p>
          </div>
          <button v-if="!k.revokedAt" type="button" class="btn btn-ghost px-2.5 py-1 text-xs text-redstone-300" @click="revoking = k">{{ t.keys.revoke }}</button>
        </li>
      </ul>
    </template>

    <!-- Eigene Benachrichtigungen -->
    <template v-else-if="tab === 'notifications'">
      <div v-if="!notify" class="skeleton mt-6 h-40 rounded-xl" />
      <form v-else class="card mt-6 grid max-w-2xl gap-4 p-5" data-testid="nl-notify" @submit.prevent="saveNotify()">
        <div>
          <h2 class="text-lg font-semibold text-base-50">{{ t.notify.title }}</h2>
          <p class="mt-1 text-sm text-base-400">{{ t.notify.lead }}</p>
        </div>
        <div>
          <label class="label" for="nn-email">{{ t.notify.email }}</label>
          <div class="flex flex-wrap gap-2">
            <input id="nn-email" v-model="notifyDraft.email" class="field min-w-0 flex-1" type="email" maxlength="254" autocomplete="email" />
            <button v-if="notify.email || notify.pendingEmail" type="button" class="btn btn-ghost" :disabled="busy === 'notify'" @click="saveNotify(true)">{{ t.notify.remove }}</button>
          </div>
          <p v-if="notify.pendingEmail" class="mt-1.5 text-xs text-lamp-300">{{ fill(t.notify.pending, { email: notify.pendingEmail }) }}</p>
          <p v-else-if="notify.email" class="mt-1.5 text-xs text-base-400">{{ fill(t.notify.confirmed, { email: notify.email }) }} · {{ t.notify.testAddress }}</p>
        </div>
        <fieldset>
          <legend class="label">{{ t.notify.kinds }}</legend>
          <p v-if="!notify.available.length" class="text-sm text-base-400">{{ t.notify.none }}</p>
          <label v-for="k in notify.available" :key="k" class="flex items-center gap-2 py-1 text-sm text-base-200">
            <input v-model="notifyDraft.kinds" type="checkbox" :value="k" />{{ t.notify.kindNames[k] }}
          </label>
        </fieldset>
        <div class="max-w-xs">
          <label class="label" for="nn-lang">{{ t.notify.lang }}</label>
          <select id="nn-lang" v-model="notifyDraft.lang" class="field">
            <option v-for="l in (['de', 'en', 'es'] as const)" :key="l" :value="l">{{ t.langs[l] }}</option>
          </select>
        </div>
        <p v-if="notify.fallbackConfigured" class="text-xs text-base-500">{{ t.notify.fallback }}</p>
        <div class="flex flex-wrap items-center gap-3">
          <button type="submit" class="btn btn-primary" :disabled="busy === 'notify'">{{ t.notify.save }}</button>
          <p v-if="notifyMsg" role="status" class="text-sm text-base-300">{{ notifyMsg }}</p>
        </div>
      </form>
    </template>

    <AdminConfirm v-if="removing" :title="t.delete" :text="fill(t.confirmDelete, { name: removing.name })" danger :busy="busy === `x:${removing.id}`" @cancel="removing = null" @confirm="remove" />
    <AdminConfirm v-if="revoking" :title="t.keys.revoke" :text="fill(t.keys.confirmRevoke, { name: revoking.name })" danger :busy="busy === `r:${revoking.id}`" @cancel="revoking = null" @confirm="revoke" />
  </div>
</template>

<style scoped>
.adm-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  padding: 0.25rem;
  width: fit-content;
  border-radius: 0.65rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
}
.adm-tabs button {
  padding: 0.45rem 0.85rem;
  border-radius: 0.45rem;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-base-400);
}
.adm-tabs button[aria-selected='true'] {
  background: var(--color-redstone-600);
  color: var(--color-base-50);
}
.nl-warn {
  display: flex;
  gap: 0.5rem;
  align-items: flex-start;
  padding: 0.7rem 0.9rem;
  border-radius: 0.6rem;
  border: 1px solid color-mix(in srgb, #ffb84d 40%, transparent);
  background: color-mix(in srgb, #ffb84d 8%, transparent);
  font-size: 0.875rem;
  color: #ffd48a;
}
.nl-look {
  width: 0.75rem;
  height: 2.5rem;
  border-radius: 999px;
  flex-shrink: 0;
  background: #e0281e;
}
.nl-look[data-look='halloween'] { background: #e08a3c; }
.nl-look[data-look='winter'] { background: #3aa9cf; }
.nl-look[data-look='fox'] { background: #ff7a2e; }
.nl-token {
  padding: 0.8rem 1rem;
  border-radius: 0.6rem;
  border: 1px dashed var(--color-redstone-500);
  background: var(--color-base-950);
}
.nl-token code {
  font-size: 0.85rem;
  color: var(--color-base-50);
}
</style>
