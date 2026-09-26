<script setup lang="ts">
// Audit-Log: jede Aktion (Team, API-Schlüssel, Automatik), filterbar nach Bereich, Akteur, Spieler, Zeitraum.
const { a, fill, when, rel } = useAdminText()
const { api, session } = useAdmin()
const route = useRoute()

const f = reactive({
  action: '',
  actor: '' as '' | 'me' | 'system' | 'api-key',
  target: typeof route.query.target === 'string' ? route.query.target : '',
  from: '',
  to: '',
})
const entries = ref<AuditRow[]>([])
const before = ref<number | null>(null)
const loading = ref(false)
const error = ref('')

async function load(more = false) {
  loading.value = true
  error.value = ''
  try {
    const q = new URLSearchParams({ limit: '100' })
    if (f.action) q.set('action', f.action)
    if (f.actor === 'me' && session.value?.uuid) q.set('actor', session.value.uuid)
    else if (f.actor === 'system' || f.actor === 'api-key') q.set('actor', f.actor)
    const t = f.target.trim().replace(/-/g, '').toLowerCase()
    if (/^[0-9a-f]{32}$/.test(t)) q.set('target', t)
    if (f.from) q.set('from', new Date(`${f.from}T00:00:00`).toISOString())
    if (f.to) q.set('to', new Date(`${f.to}T23:59:59`).toISOString())
    if (more && before.value) q.set('before', String(before.value))
    const r = await api<{ entries: AuditRow[], nextBefore: number | null }>(`/v1/admin/audit?${q}`)
    entries.value = more ? [...entries.value, ...r.entries] : r.entries
    before.value = r.nextBefore
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
watch(f, () => void load())
const who = (e: AuditRow) => e.actorName || (e.actor === 'system' ? a.value.common.system : e.actor === 'api-key' ? a.value.common.apiKey : `${e.actor.slice(0, 8)}…`)
function refLink(ref: string | null): string | null {
  if (!ref) return null
  if (/^r[0-9a-f]{16}$/.test(ref)) return `/admin/reports/${ref}`
  return null
}
const { active } = useListKeys(entries)
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ a.audit.title }}</h1>
      <p class="adm-lead">{{ a.audit.lead }}</p>
    </header>
    <div class="adm-toolbar mt-6">
      <select v-model="f.action" class="field adm-select" :aria-label="a.audit.action">
        <option v-for="(label, key) in a.audit.groups" :key="key" :value="key">{{ label }}</option>
      </select>
      <select v-model="f.actor" class="field adm-select" :aria-label="a.audit.actor">
        <option value="">{{ a.audit.actor }}: {{ a.common.all }}</option>
        <option value="me">{{ a.sanctions.actorMe }}</option>
        <option value="system">{{ a.common.system }}</option>
        <option value="api-key">{{ a.common.apiKey }}</option>
      </select>
      <input v-model="f.target" class="field max-w-72" maxlength="36" :placeholder="`${a.audit.target} (UUID)`" :aria-label="a.audit.target" />
      <input v-model="f.from" type="date" class="field adm-select" :aria-label="a.common.from" />
      <input v-model="f.to" type="date" class="field adm-select" :aria-label="a.common.to" />
    </div>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <div v-if="loading && !entries.length" class="skeleton mt-6 h-64 rounded-xl" />
    <div v-else-if="!entries.length" class="adm-empty mt-6"><SiteIcon name="list" class="size-6" />{{ a.audit.empty }}</div>
    <div v-else class="card mt-6 overflow-x-auto">
      <table class="w-full min-w-[40rem] text-left text-sm">
        <thead class="border-b border-base-800 text-xs text-base-400">
          <tr>
            <th class="px-4 py-2.5">{{ a.audit.when }}</th>
            <th class="px-4 py-2.5">{{ a.audit.actor }}</th>
            <th class="px-4 py-2.5">{{ a.audit.action }}</th>
            <th class="px-4 py-2.5">{{ a.audit.target }}</th>
            <th class="px-4 py-2.5" />
          </tr>
        </thead>
        <tbody class="divide-y divide-base-800">
          <tr v-for="(e, i) in entries" :key="e.id" :data-row="i" :class="{ 'bg-base-850': active === i }">
            <td class="px-4 py-2 whitespace-nowrap text-xs text-base-400" :title="when(e.at)">{{ rel(e.at) }}</td>
            <td class="px-4 py-2 whitespace-nowrap text-base-50">
              <NuxtLink v-if="e.actor.length === 32" :to="`/admin/players/${e.actor}`" class="hover:underline">{{ who(e) }}</NuxtLink>
              <template v-else>{{ who(e) }}</template>
            </td>
            <td class="px-4 py-2"><span class="adm-mono text-base-200">{{ e.action }}</span></td>
            <td class="px-4 py-2 whitespace-nowrap">
              <NuxtLink v-if="e.target && e.target.length === 32" :to="`/admin/players/${e.target}`" class="text-base-200 hover:underline">{{ e.targetName || `${e.target.slice(0, 8)}…` }}</NuxtLink>
            </td>
            <td class="max-w-md px-4 py-2 text-xs text-base-400">
              <span class="break-words">{{ e.detail }}</span>
              <NuxtLink v-if="refLink(e.ref)" :to="refLink(e.ref)!" class="ml-1 text-redstone-300 hover:underline">{{ e.ref }}</NuxtLink>
              <span v-else-if="e.ref" class="adm-mono ml-1">{{ e.ref }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <button v-if="before" type="button" class="btn btn-ghost mt-4" :disabled="loading" @click="load(true)">{{ a.common.loadMore }}</button>
  </div>
</template>
