<script setup lang="ts">
// Spieler-Liste: Namensanfang (auch frühere Namen), Status-Filter, Sortierung, Cursor. Öffnet die Akte.
const { a, fill, rel, when } = useAdminText()
const { api } = useAdmin()
const router = useRouter()

interface Row {
  uuid: string
  name: string
  role: string | null
  online: boolean
  createdAt: string
  lastLoginAt: string
  activeSanctions: SanctionKind[]
  openReports: number
}
const f = reactive({ q: '', status: 'all' as 'all' | 'sanctioned' | 'banned' | 'staff' | 'reported', sort: 'last_login' as 'last_login' | 'created' })
const items = ref<Row[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const error = ref('')
let timer: ReturnType<typeof setTimeout> | null = null

async function load(more = false) {
  loading.value = true
  error.value = ''
  try {
    const q = new URLSearchParams({ status: f.status, sort: f.sort, limit: '40' })
    const name = f.q.trim()
    if (name && /^[A-Za-z0-9_]{1,16}$/.test(name)) q.set('q', name)
    if (more && cursor.value) q.set('cursor', cursor.value)
    const r = await api<{ players: Row[], nextCursor: string | null }>(`/v1/admin/players?${q}`)
    items.value = more ? [...items.value, ...r.players] : r.players
    cursor.value = r.nextCursor
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
watch(() => [f.status, f.sort], () => void load())
watch(() => f.q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void load(), 250)
})

function open(p: Row) {
  void router.push(`/admin/players/${p.uuid}`)
}
const { active } = useListKeys(items, { open })
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ a.players.title }}</h1>
      <p class="adm-lead">{{ a.players.lead }}</p>
    </header>
    <div class="adm-toolbar mt-6">
      <input v-model="f.q" class="field max-w-60" maxlength="16" :placeholder="a.players.name" :aria-label="a.players.name" />
      <div class="adm-seg">
        <button v-for="s in (['all', 'sanctioned', 'banned', 'reported', 'staff'] as const)" :key="s" type="button" :aria-pressed="f.status === s" @click="f.status = s">{{ a.players.filters[s] }}</button>
      </div>
      <select v-model="f.sort" class="field adm-select" :aria-label="a.players.sortLogin">
        <option value="last_login">{{ a.players.sortLogin }}</option>
        <option value="created">{{ a.players.sortCreated }}</option>
      </select>
    </div>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>

    <div v-if="loading && !items.length" class="mt-6 space-y-2">
      <div v-for="i in 6" :key="i" class="skeleton h-14 rounded-xl" />
    </div>
    <div v-else-if="!items.length" class="adm-empty mt-6"><SiteIcon name="users" class="size-6" />{{ a.players.empty }}</div>
    <ul v-else class="mt-6 grid gap-2 md:grid-cols-2">
      <li v-for="(p, i) in items" :key="p.uuid">
        <NuxtLink :to="`/admin/players/${p.uuid}`" class="adm-row items-center" :data-row="i" :data-active="active === i">
          <PlayerHead :uuid="p.uuid" :name="p.name" :size="36" :fetch="false" />
          <span class="min-w-0 flex-1">
            <span class="flex flex-wrap items-center gap-1.5">
              <span class="truncate font-semibold text-base-50">{{ p.name }}</span>
              <span v-if="p.online" class="size-2 bg-ok" :title="a.file.online" />
              <span v-if="p.role" class="tone tone-info">{{ a.role[p.role] }}</span>
              <span v-for="k in p.activeSanctions" :key="k" class="tone" :class="kindTone(k)">{{ a.kinds[k] }}</span>
              <span v-if="p.openReports" class="tone tone-warn">{{ fill(a.players.openReports, { n: p.openReports }) }}</span>
            </span>
            <span class="block truncate text-xs text-base-400">
              <span :title="when(p.lastLoginAt)">{{ a.players.lastLogin }} {{ rel(p.lastLoginAt) }}</span> · <span :title="when(p.createdAt)">{{ a.players.firstLogin }} {{ rel(p.createdAt) }}</span>
            </span>
          </span>
          <SiteIcon name="arrow" class="size-4 text-base-400" />
        </NuxtLink>
      </li>
    </ul>
    <button v-if="cursor" type="button" class="btn btn-ghost mt-4" :disabled="loading" @click="load(true)">{{ a.common.loadMore }}</button>
  </div>
</template>
