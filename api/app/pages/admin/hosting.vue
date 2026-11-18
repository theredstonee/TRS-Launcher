<script setup lang="ts">
// CraftingHosting-Server aller Konten (§43.8): Besitzer, Zustand, Einrichtung; stoppen/löschen mit Grund.
const { a, fill, rel, when } = useAdminText()
const { api, can } = useAdmin()

const servers = ref<AdminHostingServer[]>([])
const usage = ref<{ used: number, max: number } | null>(null)
const loading = ref(false)
const error = ref('')
const unavailable = ref(false)
const acting = ref<{ server: AdminHostingServer, kind: 'stop' | 'delete' } | null>(null)
const reason = ref('')
const busy = ref(false)
const t = computed(() => a.value.hosting)

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

async function act() {
  if (!acting.value) return
  const { server, kind } = acting.value
  busy.value = true
  try {
    const body = reason.value.trim() ? { reason: reason.value.trim().slice(0, 200) } : undefined
    await api(`/v1/admin/craftinghosting/servers/${server.id}${kind === 'stop' ? '/stop' : ''}`, { method: kind === 'stop' ? 'POST' : 'DELETE', body })
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
          <div v-if="can('worlds.close')" class="mt-1 flex gap-2">
            <button v-if="s.session.active" type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="acting = { server: s, kind: 'stop' }"><SiteIcon name="pause" class="size-3.5" />{{ t.stop }}</button>
            <button type="button" class="btn btn-danger px-2.5 py-1 text-xs" @click="acting = { server: s, kind: 'delete' }"><SiteIcon name="trash" class="size-3.5" />{{ t.remove }}</button>
          </div>
        </article>
      </li>
    </ul>
    <AdminConfirm
      v-if="acting"
      :title="acting.kind === 'stop' ? t.stop : t.remove"
      :text="fill(acting.kind === 'stop' ? t.confirmStop : t.confirmDelete, { name: acting.server.name, owner: ownerName(acting.server) })"
      danger
      :busy="busy"
      @cancel="acting = null"
      @confirm="act"
    >
      <label class="label mt-3" for="hosting-reason">{{ t.reason }}</label>
      <input id="hosting-reason" v-model="reason" class="field" maxlength="200" />
    </AdminConfirm>
  </div>
</template>
