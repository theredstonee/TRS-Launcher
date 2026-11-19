<script setup lang="ts">
// CraftingHosting-Server aller Konten (§43.8): Besitzer, Zustand, Einrichtung; stoppen/löschen mit Grund.
const { a, fill, rel, when } = useAdminText()
const { api, can } = useAdmin()

const servers = ref<AdminHostingServer[]>([])
const usage = ref<{ used: number, max: number } | null>(null)
const loading = ref(false)
const error = ref('')
const unavailable = ref(false)
const acting = ref<{ server: AdminHostingServer, kind: 'stop' | 'delete' | 'block' } | null>(null)
const reason = ref('')
const busy = ref(false)
const t = computed(() => a.value.hosting)

// Steuerung: Ankündigung (Markdown), Wartung, Funktions-Schalter
const cfg = ref<AdminHostingConfig | null>(null)
const noticeText = ref('')
const noticeLevel = ref<'info' | 'warning' | 'success'>('info')
const maint = ref({ active: false, message: '' })
const featureState = ref<Record<string, 'default' | 'on' | 'off'>>({})
const rules = ref<HostingRules>({ enabled: true, budgetHours: 24, cooldownHours: 2, idleResetDays: 3, weeklyOfflineHours: 8, warnMinutes: 30 })
type RuleKey = Exclude<keyof HostingRules, 'enabled'>
const RULE_FIELDS: { k: RuleKey, min: number, max: number }[] = [
  { k: 'budgetHours', min: 1, max: 168 }, { k: 'cooldownHours', min: 0, max: 48 }, { k: 'idleResetDays', min: 1, max: 30 },
  { k: 'weeklyOfflineHours', min: 0, max: 48 }, { k: 'warnMinutes', min: 0, max: 120 },
]
const cfgBusy = ref(false)
const cfgSaved = ref(false)
const FEATURES = ['create', 'console', 'files', 'properties', 'addons', 'worlds', 'worldDownload', 'ownAddress', 'wakeOnJoin', 'versionChange', 'backups']
const noticeHtml = computed(() => renderMarkdown(noticeText.value))

function applyCfg(c: AdminHostingConfig) {
  cfg.value = c
  noticeText.value = c.notice?.markdown ?? ''
  noticeLevel.value = c.notice?.level ?? 'info'
  maint.value = { active: c.maintenance.active, message: c.maintenance.message ?? '' }
  featureState.value = Object.fromEntries(FEATURES.map((k) => [k, c.overrides[k] === undefined ? 'default' : c.overrides[k] ? 'on' : 'off']))
  if (c.rules) rules.value = { ...c.rules }
}
async function loadCfg() {
  try {
    applyCfg(await api<AdminHostingConfig>('/v1/admin/craftinghosting/config'))
  } catch (e) {
    if (apiCode(e) !== 'craftinghosting_unavailable') error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  }
}
async function saveCfg() {
  cfgBusy.value = true
  cfgSaved.value = false
  try {
    const features = Object.fromEntries(FEATURES.map((k) => [k, featureState.value[k] === 'default' ? null : featureState.value[k] === 'on']))
    applyCfg(await api<AdminHostingConfig>('/v1/admin/craftinghosting/config', {
      method: 'PUT',
      body: {
        notice: noticeText.value.trim() ? { markdown: noticeText.value, level: noticeLevel.value } : null,
        maintenance: { active: maint.value.active, message: maint.value.message.trim() || null },
        features,
        rules: rules.value,
      },
    }))
    cfgSaved.value = true
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    cfgBusy.value = false
  }
}
onMounted(loadCfg)

interface WebhookState { configured: boolean, url: string | null, expectedUrl: string, pending: number }
const webhook = ref<WebhookState | null>(null)
const webhookBusy = ref(false)
async function loadWebhook() {
  try {
    webhook.value = await api<WebhookState>('/v1/admin/craftinghosting/webhook')
  } catch {
    webhook.value = null
  }
}
async function webhookAction(action: 'setup' | 'test' | 'remove') {
  webhookBusy.value = true
  try {
    webhook.value = await api<WebhookState>('/v1/admin/craftinghosting/webhook', { method: 'POST', body: { action } })
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    webhookBusy.value = false
  }
}
onMounted(loadWebhook)
const featureAvailable = (k: string) => (k === 'ownAddress' ? cfg.value?.available.ownAddress : k === 'wakeOnJoin' ? cfg.value?.available.wakeOnJoin : true)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await api<{ usage: { used: number, max: number }, servers: AdminHostingServer[] }>('/v1/admin/craftinghosting/servers')
    servers.value = res.servers
    usage.value = res.usage
    unavailable.value = false
  } catch (e) {
    if (apiCode(e) === 'craftinghosting_unavailable') unavailable.value = true
    else error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(load)

const ownerName = (s: AdminHostingServer) => s.owner?.name || s.owner?.uuid.slice(0, 8) || t.value.unknownOwner
const tone = (s: AdminHostingServer) =>
  s.state === 'running' ? 'tone-ok' : s.state === 'failed' || s.state === 'suspended' ? 'tone-danger' : 'tone-muted'

async function resetQuota(s: AdminHostingServer) {
  try {
    await api(`/v1/admin/craftinghosting/servers/${s.id}/reset-quota`, { method: 'POST' })
    await load()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  }
}

async function unblock(s: AdminHostingServer) {
  try {
    await api(`/v1/admin/craftinghosting/servers/${s.id}/block`, { method: 'DELETE' })
    await load()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  }
}

async function act() {
  if (!acting.value) return
  const { server, kind } = acting.value
  busy.value = true
  try {
    const body = reason.value.trim() ? { reason: reason.value.trim().slice(0, 200) } : undefined
    if (kind === 'block') {
      if (!body) return
      await api(`/v1/admin/craftinghosting/servers/${server.id}/block`, { method: 'POST', body })
    } else {
      await api(`/v1/admin/craftinghosting/servers/${server.id}${kind === 'stop' ? '/stop' : ''}`, { method: kind === 'stop' ? 'POST' : 'DELETE', body })
    }
    acting.value = null
    reason.value = ''
    await load()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
    acting.value = null
  } finally {
    busy.value = false
  }
}
const { active } = useListKeys(servers)
</script>

<template>
  <div class="adm-page">
    <header class="flex flex-wrap items-end gap-3">
      <div class="min-w-0 flex-1">
        <h1 class="adm-title">{{ t.title }}</h1>
        <p class="adm-lead">{{ t.lead }}</p>
        <p v-if="usage" class="mt-1 text-sm text-base-300" data-testid="hosting-usage">{{ fill(t.usage, usage) }}</p>
      </div>
      <button type="button" class="btn btn-ghost" @click="load"><SiteIcon name="refresh" class="size-4" />{{ a.common.refresh }}</button>
    </header>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <section v-if="cfg" class="card mt-6 space-y-5 p-4" data-testid="hosting-control">
      <h2 class="text-base font-semibold text-base-50">{{ t.control }}</h2>
      <div>
        <label class="label" for="hosting-notice">{{ t.notice }}</label>
        <p class="mb-2 text-xs text-base-400">{{ t.noticeHint }}</p>
        <div class="grid gap-3 md:grid-cols-2">
          <div class="space-y-2">
            <select v-model="noticeLevel" class="field" :disabled="!can('worlds.close')">
              <option v-for="lvl in ['info', 'warning', 'success']" :key="lvl" :value="lvl">{{ t.noticeLevel[lvl] }}</option>
            </select>
            <textarea id="hosting-notice" v-model="noticeText" class="field min-h-32 font-mono text-sm" maxlength="4000" :disabled="!can('worlds.close')" />
          </div>
          <div class="rounded-lg bg-base-900 p-3 ring-1 ring-base-800">
            <p class="mb-1 text-xs text-base-400">{{ t.preview }}</p>
            <!-- eslint-disable-next-line vue/no-v-html -- gesäuberter Markdown-Weg der Website (utils/markdown.ts) -->
            <div class="prose-md text-sm" v-html="noticeHtml" />
          </div>
        </div>
      </div>
      <div>
        <label class="flex items-center gap-2 text-sm text-base-100">
          <input v-model="maint.active" type="checkbox" :disabled="!can('worlds.close')">
          <span class="font-semibold">{{ t.maintenance }}</span>
        </label>
        <p class="mt-1 text-xs text-base-400">{{ t.maintenanceHint }}</p>
        <label class="label mt-2" for="hosting-maint">{{ t.maintenanceMessage }}</label>
        <input id="hosting-maint" v-model="maint.message" class="field" maxlength="300" :disabled="!can('worlds.close')">
      </div>
      <div>
        <p class="text-sm font-semibold text-base-100">{{ t.features }}</p>
        <p class="mb-2 text-xs text-base-400">{{ t.featuresHint }}</p>
        <ul class="grid gap-2 md:grid-cols-2">
          <li v-for="k in FEATURES" :key="k" class="flex items-center justify-between gap-2 rounded-lg bg-base-900 px-3 py-2 ring-1 ring-base-800">
            <span class="text-sm text-base-100">
              {{ t.featureNames[k] ?? k }}
              <span class="ml-1 text-xs" :class="cfg.features[k] ? 'text-emerald-300' : 'text-base-400'">({{ cfg.features[k] ? t.featureOn : t.featureOff }})</span>
              <span v-if="!featureAvailable(k)" class="block text-xs text-base-400">{{ t.notSetUp }}</span>
            </span>
            <select v-model="featureState[k]" class="field w-auto py-1 text-xs" :disabled="!can('worlds.close') || !featureAvailable(k)">
              <option value="default">{{ t.featureDefault }}</option>
              <option value="on">{{ t.featureOn }}</option>
              <option value="off">{{ t.featureOff }}</option>
            </select>
          </li>
        </ul>
      </div>
      <div class="rounded-lg bg-base-900 p-3 ring-1 ring-base-800">
        <label class="flex items-center gap-2 text-sm font-semibold text-base-100">
          <input v-model="rules.enabled" type="checkbox" class="check" :disabled="!can('worlds.close')">{{ t.rules }}
        </label>
        <p class="text-xs text-base-400">{{ t.rulesHint }}</p>
        <div class="mt-2 grid gap-2 sm:grid-cols-3 lg:grid-cols-5">
          <label v-for="f in RULE_FIELDS" :key="f.k" class="text-xs text-base-300">
            {{ t.ruleNames[f.k as RuleKey] }}
            <input v-model.number="rules[f.k as RuleKey]" type="number" class="field mt-1 py-1" :min="f.min" :max="f.max" :disabled="!can('worlds.close') || !rules.enabled">
          </label>
        </div>
      </div>
      <div v-if="webhook" class="rounded-lg bg-base-900 p-3 ring-1 ring-base-800">
        <p class="text-sm font-semibold text-base-100">{{ t.webhook }}</p>
        <p class="text-xs text-base-400">{{ t.webhookHint }}</p>
        <p class="mt-1 text-xs" :class="webhook.configured ? 'text-emerald-300' : 'text-base-400'">
          {{ webhook.configured ? fill(t.webhookOn, { url: webhook.url ?? '' }) : t.webhookOff }}
          <template v-if="webhook.pending"> · {{ fill(t.webhookPending, { n: webhook.pending }) }}</template>
        </p>
        <div v-if="can('worlds.close')" class="mt-2 flex flex-wrap gap-2">
          <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="webhookBusy" @click="webhookAction('setup')">{{ t.webhookSetup }}</button>
          <button v-if="webhook.configured" type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="webhookBusy" @click="webhookAction('test')">{{ t.webhookTest }}</button>
          <button v-if="webhook.configured" type="button" class="btn btn-danger px-2.5 py-1 text-xs" :disabled="webhookBusy" @click="webhookAction('remove')">{{ t.webhookRemove }}</button>
        </div>
      </div>
      <div v-if="can('worlds.close')" class="flex items-center gap-3">
        <button type="button" class="btn btn-primary" :disabled="cfgBusy" @click="saveCfg">{{ t.save }}</button>
        <span v-if="cfgSaved" class="text-sm text-emerald-300">{{ t.saved }}</span>
      </div>
    </section>
    <div v-if="unavailable" class="adm-empty mt-6"><SiteIcon name="server" class="size-6" />{{ t.unavailable }}</div>
    <div v-else-if="loading && !servers.length" class="mt-6 grid gap-3 md:grid-cols-2">
      <div v-for="i in 4" :key="i" class="skeleton h-28 rounded-xl" />
    </div>
    <div v-else-if="!servers.length" class="adm-empty mt-6"><SiteIcon name="server" class="size-6" />{{ t.empty }}</div>
    <ul v-else class="mt-6 grid gap-3 md:grid-cols-2">
      <li v-for="(s, i) in servers" :key="s.id">
        <article class="adm-row h-full flex-col" :data-row="i" :data-active="active === i">
          <div class="flex w-full flex-wrap items-center gap-2">
            <span class="font-semibold text-base-50">{{ s.name }}</span>
            <span class="chip py-0.5">{{ s.serverType }} {{ s.version }}</span>
            <span class="tone" :class="tone(s)">{{ t.state[s.state] ?? s.state }}</span>
            <span class="tone tone-muted">{{ t.access[s.access] }}</span>
          </div>
          <p class="text-sm text-base-200">
            {{ t.owner }}:
            <NuxtLink v-if="s.owner" :to="`/admin/players/${s.owner.uuid}`" class="text-base-50 hover:underline">{{ ownerName(s) }}</NuxtLink>
            <span v-else class="adm-mono text-base-400">{{ t.unknownOwner }} {{ s.externalId }}</span>
          </p>
          <p v-if="s.address" class="adm-mono text-base-400">{{ s.address }}</p>
          <p class="text-xs text-base-400">
            {{ t.setup[s.setup] }} · {{ s.plugin ? fill(t.plugin, { platform: s.plugin }) : t.noPlugin }}
          </p>
          <p class="text-xs text-base-400">
            <template v-if="s.session.active">{{ fill(t.remaining, { h: Math.floor(s.session.remainingSeconds / 3600) }) }}</template>
            <template v-else-if="s.lastStartAt"><span :title="when(s.lastStartAt)">{{ fill(t.lastStart, { date: rel(s.lastStartAt) }) }}</span></template>
            <template v-else>{{ t.neverStarted }}</template>
            <template v-if="s.session.active && !s.session.hostPresent"> · {{ t.hostAway }}</template>
            <template v-if="s.deleteAt && !s.session.active"> · <span :title="when(s.deleteAt)">{{ fill(t.deleteAt, { date: rel(s.deleteAt) }) }}</span></template>
          </p>
          <p v-if="s.quota" class="text-xs text-base-400">
            {{ fill(t.quota, { used: Math.floor(s.quota.usedSeconds / 3600), budget: Math.round(s.quota.budgetSeconds / 3600), week: Math.floor(s.quota.weekUsedSeconds / 3600), cap: Math.round(s.quota.weekCapSeconds / 3600) }) }}
            <template v-if="s.quota.cooldownUntil"> · <span :title="when(s.quota.cooldownUntil)">{{ fill(t.cooldownUntil, { date: rel(s.quota.cooldownUntil) }) }}</span></template>
          </p>
          <p v-if="s.blocked" class="text-xs text-redstone-300">{{ fill(t.blocked, { reason: s.blocked.reason }) }}</p>
          <div v-if="can('worlds.close')" class="mt-1 flex flex-wrap gap-2">
            <button v-if="!s.blocked" type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="acting = { server: s, kind: 'block' }"><SiteIcon name="ban" class="size-3.5" />{{ t.block }}</button>
            <button v-else type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="unblock(s)"><SiteIcon name="check" class="size-3.5" />{{ t.unblock }}</button>
            <button v-if="s.quota && (s.quota.usedSeconds > 0 || s.quota.cooldownUntil)" type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="resetQuota(s)"><SiteIcon name="refresh" class="size-3.5" />{{ t.resetQuota }}</button>
            <button v-if="s.session.active" type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="acting = { server: s, kind: 'stop' }"><SiteIcon name="pause" class="size-3.5" />{{ t.stop }}</button>
            <button type="button" class="btn btn-danger px-2.5 py-1 text-xs" @click="acting = { server: s, kind: 'delete' }"><SiteIcon name="trash" class="size-3.5" />{{ t.remove }}</button>
          </div>
        </article>
      </li>
    </ul>
    <AdminConfirm
      v-if="acting"
      :title="acting.kind === 'stop' ? t.stop : acting.kind === 'block' ? t.block : t.remove"
      :text="fill(acting.kind === 'stop' ? t.confirmStop : acting.kind === 'block' ? t.confirmBlock : t.confirmDelete, { name: acting.server.name, owner: ownerName(acting.server) })"
      danger
      :busy="busy"
      @cancel="acting = null"
      @confirm="act"
    >
      <label class="label mt-3" for="hosting-reason">{{ acting.kind === 'block' ? t.blockReason : t.reason }}</label>
      <input id="hosting-reason" v-model="reason" class="field" maxlength="200" />
    </AdminConfirm>
  </div>
</template>
