<script setup lang="ts">
// Einsprüche: Liste (offen: älteste zuerst), Entscheidung aufheben / verkürzen / bestehen lassen mit
// Antwort an den Spieler. Eigene Strafen entscheidet jemand anderes; Admin-Strafen heben nur Admins auf.
const { a, fill, when, rel, actor } = useAdminText()
const { api, session, isAdmin } = useAdmin()

const status = ref<'open' | 'decided' | 'all'>('open')
const items = ref<AdminAppeal[]>([])
const cursor = ref<string | null>(null)
const open = ref(0)
const loading = ref(false)
const error = ref('')

async function load(more = false) {
  loading.value = true
  error.value = ''
  try {
    const q = new URLSearchParams({ status: status.value, limit: '30' })
    if (more && cursor.value) q.set('cursor', cursor.value)
    const r = await api<{ appeals: AdminAppeal[], nextCursor: string | null, open: number }>(`/v1/admin/appeals?${q}`)
    items.value = more ? [...items.value, ...r.appeals] : r.appeals
    cursor.value = r.nextCursor
    open.value = r.open
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
watch(status, () => void load())

// --- Entscheidung -----------------------------------------------------------------------------------
const deciding = ref<AdminAppeal | null>(null)
const decision = ref<'lift' | 'shorten' | 'uphold'>('uphold')
const response = ref('')
const end = ref('')
const busy = ref(false)
const decideError = ref('')
const confirming = ref(false)

function own(x: AdminAppeal): boolean {
  return !isAdmin.value && x.sanction.createdBy.uuid === session.value?.uuid
}
function adminOnly(x: AdminAppeal): boolean {
  return !isAdmin.value && (x.sanction.createdRole === 'admin' || x.sanction.kind === 'account_ban')
}
function start(x: AdminAppeal, d: 'lift' | 'shorten' | 'uphold') {
  deciding.value = x
  decision.value = d
  response.value = ''
  decideError.value = ''
  const now = Date.now()
  const endMs = x.sanction.endsAt ? new Date(x.sanction.endsAt).getTime() : now + 7 * 86_400_000
  end.value = toLocalInput(new Date(Math.max(now + 3_600_000, now + (endMs - now) / 2)).toISOString())
}
async function submit() {
  const x = deciding.value
  if (!x) return
  busy.value = true
  decideError.value = ''
  try {
    const body: Record<string, unknown> = { decision: decision.value, response: response.value.trim().slice(0, 1000) }
    if (decision.value === 'shorten') body.endsAt = fromLocalInput(end.value)
    await api(`/v1/admin/appeals/${x.id}/decide`, { method: 'POST', body })
    deciding.value = null
    confirming.value = false
    await load()
  } catch (e) {
    decideError.value = fill(a.value.common.failed, { error: apiMessage(e) })
    confirming.value = false
  } finally {
    busy.value = false
  }
}

const { active } = useListKeys(items, {
  onA: (x) => x.status === 'open' && !own(x) && !adminOnly(x) && start(x, 'lift'),
  onR: (x) => x.status === 'open' && !own(x) && start(x, 'uphold'),
  open: (x) => x.status === 'open' && !own(x) && start(x, 'uphold'),
})
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ a.appeals.title }}</h1>
      <p class="adm-lead">{{ a.appeals.lead }}</p>
    </header>
    <div class="adm-toolbar mt-6">
      <div class="adm-seg">
        <button v-for="s in (['open', 'decided', 'all'] as const)" :key="s" type="button" :aria-pressed="status === s" @click="status = s">
          {{ a.appeals.filters[s] }}<span v-if="s === 'open' && open" class="ml-1 tabular-nums text-lamp-300">{{ open }}</span>
        </button>
      </div>
    </div>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>

    <div v-if="loading && !items.length" class="mt-6 space-y-3">
      <div v-for="i in 3" :key="i" class="skeleton h-40 rounded-xl" />
    </div>
    <div v-else-if="!items.length" class="adm-empty mt-6"><SiteIcon name="appeal" class="size-6" />{{ a.appeals.empty }}</div>
    <ul v-else class="mt-6 space-y-3">
      <li v-for="(x, i) in items" :key="x.id">
        <article class="adm-row flex-col" :data-row="i" :data-active="active === i">
          <div class="flex w-full flex-wrap items-center gap-2">
            <PlayerHead :uuid="x.sanction.player.uuid" :name="x.sanction.player.name" :size="28" :fetch="false" />
            <NuxtLink :to="`/admin/players/${x.sanction.player.uuid}`" class="font-semibold text-base-50 hover:underline">{{ x.sanction.player.name || x.sanction.player.uuid.slice(0, 8) }}</NuxtLink>
            <span class="tone" :class="x.status === 'open' ? 'tone-warn' : x.status === 'upheld' ? 'tone-muted' : 'tone-ok'">{{ a.appealStatus[x.status] }}</span>
            <span class="ml-auto text-xs text-base-400" :title="when(x.createdAt)">{{ rel(x.createdAt) }}</span>
          </div>
          <div class="grid w-full gap-3 md:grid-cols-2">
            <div>
              <p class="label">{{ a.appeals.text }}</p>
              <p class="adm-note rounded-md bg-base-950 px-3 py-2 text-sm text-base-100">{{ x.text }}</p>
            </div>
            <div>
              <p class="label">{{ a.appeals.sanction }}</p>
              <SanctionCard :sanction="x.sanction" :actions="false" />
            </div>
          </div>
          <p v-if="x.response" class="text-sm text-base-200">→ {{ x.response }} <span class="text-xs text-base-400">· {{ fill(a.appeals.decidedBy, { name: actor(x.decidedBy), date: when(x.decidedAt) }) }}</span></p>
          <template v-if="x.status === 'open'">
            <p v-if="own(x)" class="text-xs text-lamp-300">{{ a.appeals.ownSanction }}</p>
            <div v-else class="flex flex-wrap gap-2">
              <button type="button" class="btn btn-primary text-sm" :disabled="adminOnly(x)" :title="adminOnly(x) ? a.appeals.adminSanction : ''" @click="start(x, 'lift')"><SiteIcon name="check" class="size-4" />{{ a.appeals.lift }}</button>
              <button type="button" class="btn btn-ghost text-sm" :disabled="adminOnly(x) || x.sanction.status !== 'active'" @click="start(x, 'shorten')"><SiteIcon name="clock" class="size-4" />{{ a.appeals.shorten }}</button>
              <button type="button" class="btn btn-ghost text-sm" @click="start(x, 'uphold')">{{ a.appeals.uphold }}</button>
              <p v-if="adminOnly(x)" class="w-full text-xs text-base-400">{{ a.appeals.adminSanction }}</p>
            </div>
          </template>
        </article>
      </li>
    </ul>
    <button v-if="cursor" type="button" class="btn btn-ghost mt-4" :disabled="loading" @click="load(true)">{{ a.common.loadMore }}</button>

    <AdminDialog v-if="deciding" :title="decision === 'lift' ? a.appeals.lift : decision === 'shorten' ? a.appeals.shorten : a.appeals.uphold" :kicker="`${a.kinds[deciding.sanction.kind]} · ${deciding.sanction.player.name || ''}`" @close="deciding = null">
      <div class="adm-seg">
        <button type="button" :aria-pressed="decision === 'lift'" :disabled="adminOnly(deciding)" @click="decision = 'lift'">{{ a.appeals.lift }}</button>
        <button type="button" :aria-pressed="decision === 'shorten'" :disabled="adminOnly(deciding) || deciding.sanction.status !== 'active'" @click="decision = 'shorten'">{{ a.appeals.shorten }}</button>
        <button type="button" :aria-pressed="decision === 'uphold'" @click="decision = 'uphold'">{{ a.appeals.uphold }}</button>
      </div>
      <div v-if="decision === 'shorten'" class="mt-4">
        <label class="label" for="ap-end">{{ a.appeals.newEnd }}</label>
        <input id="ap-end" v-model="end" type="datetime-local" class="field" />
      </div>
      <label class="label mt-4" for="ap-response">{{ a.appeals.response }} *</label>
      <textarea id="ap-response" v-model="response" class="field min-h-28" maxlength="1000" autofocus />
      <p class="mt-1 text-xs text-base-400">{{ a.appeals.responseHint }}</p>
      <p v-if="decideError" role="alert" class="mt-3 text-sm text-redstone-300">{{ decideError }}</p>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="deciding = null">{{ a.common.cancel }}</button>
        <button type="button" class="btn btn-primary" :disabled="busy || !response.trim()" @click="confirming = true">{{ a.common.confirm }}</button>
      </template>
      <AdminConfirm v-if="confirming" :title="a.appeals.confirm" :text="response" :busy="busy" @cancel="confirming = false" @confirm="submit" />
    </AdminDialog>
  </div>
</template>
