<script setup lang="ts">
// Strafen: alle Arten mit Filter (Status, Art, Bearbeiter, Zeitraum, Sortierung) und Cursor; neue Strafe
// verhängen; jede einzeln aufheben oder die Dauer ändern (mit Begründung, im Verlauf sichtbar).
const { a, fill } = useAdminText()
const { api, session } = useAdmin()

const f = reactive({
  status: 'active' as 'active' | 'expired' | 'lifted' | 'all',
  kind: '' as '' | SanctionKind,
  actor: '' as '' | 'me',
  from: '',
  to: '',
  sort: 'newest' as 'newest' | 'oldest',
})
const items = ref<AdminSanction[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const error = ref('')
const creating = ref(false)

async function load(more = false) {
  loading.value = true
  error.value = ''
  try {
    const q = new URLSearchParams({ status: f.status, sort: f.sort, limit: '30' })
    if (f.kind) q.set('kind', f.kind)
    if (f.actor === 'me' && session.value?.uuid) q.set('actor', session.value.uuid)
    if (f.from) q.set('from', new Date(`${f.from}T00:00:00`).toISOString())
    if (f.to) q.set('to', new Date(`${f.to}T23:59:59`).toISOString())
    if (more && cursor.value) q.set('cursor', cursor.value)
    const r = await api<{ sanctions: AdminSanction[], nextCursor: string | null }>(`/v1/admin/sanctions?${q}`)
    items.value = more ? [...items.value, ...r.sanctions] : r.sanctions
    cursor.value = r.nextCursor
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
watch(f, () => void load())

function changed(s: AdminSanction) {
  const i = items.value.findIndex((x) => x.id === s.id)
  if (i >= 0) items.value[i] = s
}
const { active } = useListKeys(items)
</script>

<template>
  <div class="adm-page">
    <header class="flex flex-wrap items-end gap-3">
      <div class="min-w-0 flex-1">
        <h1 class="adm-title">{{ a.sanctions.title }}</h1>
        <p class="adm-lead">{{ a.sanctions.lead }}</p>
      </div>
      <button type="button" class="btn btn-danger" @click="creating = true"><SiteIcon name="plus" class="size-4" />{{ a.sanctions.newSanction }}</button>
    </header>
    <div class="mt-6 space-y-2">
      <div class="adm-toolbar">
        <div class="adm-seg">
          <button v-for="s in (['active', 'expired', 'lifted', 'all'] as const)" :key="s" type="button" :aria-pressed="f.status === s" @click="f.status = s">{{ a.sanctions.filters[s] }}</button>
        </div>
        <select v-model="f.kind" class="field adm-select" :aria-label="a.sanctions.kind">
          <option value="">{{ a.sanctions.kind }}: {{ a.common.all }}</option>
          <option v-for="k in SANCTION_KINDS" :key="k" :value="k">{{ a.kinds[k] }}</option>
        </select>
        <select v-model="f.actor" class="field adm-select" :aria-label="a.sanctions.actor">
          <option value="">{{ a.sanctions.actor }}: {{ a.common.all }}</option>
          <option value="me">{{ a.sanctions.actorMe }}</option>
        </select>
        <input v-model="f.from" type="date" class="field adm-select" :aria-label="a.common.from" />
        <input v-model="f.to" type="date" class="field adm-select" :aria-label="a.common.to" />
        <select v-model="f.sort" class="field adm-select" :aria-label="a.common.newest">
          <option value="newest">{{ a.common.newest }}</option>
          <option value="oldest">{{ a.common.oldest }}</option>
        </select>
      </div>
    </div>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>

    <div v-if="loading && !items.length" class="mt-6 space-y-2">
      <div v-for="i in 4" :key="i" class="skeleton h-28 rounded-xl" />
    </div>
    <div v-else-if="!items.length" class="adm-empty mt-6"><SiteIcon name="gavel" class="size-6" />{{ a.sanctions.empty }}</div>
    <div v-else class="mt-6 space-y-2">
      <div v-for="(s, i) in items" :key="s.id" :data-row="i">
        <SanctionCard :sanction="s" show-player :active="active === i" @changed="changed" />
      </div>
    </div>
    <button v-if="cursor" type="button" class="btn btn-ghost mt-4" :disabled="loading" @click="load(true)">{{ a.common.loadMore }}</button>

    <SanctionDialog v-if="creating" @close="creating = false" @done="creating = false; load()" />
  </div>
</template>
