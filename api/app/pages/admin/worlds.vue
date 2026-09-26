<script setup lang="ts">
// Offene Welten (Welt-Hosting): Host, Version, Spieler, Mitglieder; schließen mit Bestätigung.
const { a, fill, rel, when } = useAdminText()
const { api } = useAdmin()

const rooms = ref<AdminRoom[]>([])
const loading = ref(false)
const error = ref('')
const closing = ref<AdminRoom | null>(null)
const reason = ref('')
const busy = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    rooms.value = (await api<{ rooms: AdminRoom[] }>('/v1/admin/hosting/rooms')).rooms
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function close() {
  if (!closing.value) return
  busy.value = true
  try {
    await api(`/v1/admin/hosting/rooms/${closing.value.id}`, { method: 'DELETE', body: reason.value.trim() ? { reason: reason.value.trim().slice(0, 200) } : undefined })
    closing.value = null
    reason.value = ''
    await load()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
    closing.value = null
  } finally {
    busy.value = false
  }
}
const { active } = useListKeys(rooms)
</script>

<template>
  <div class="adm-page">
    <header class="flex flex-wrap items-end gap-3">
      <div class="min-w-0 flex-1">
        <h1 class="adm-title">{{ a.worlds.title }}</h1>
        <p class="adm-lead">{{ a.worlds.lead }}</p>
      </div>
      <button type="button" class="btn btn-ghost" @click="load"><SiteIcon name="refresh" class="size-4" />{{ a.common.refresh }}</button>
    </header>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <div v-if="loading && !rooms.length" class="mt-6 grid gap-3 md:grid-cols-2">
      <div v-for="i in 4" :key="i" class="skeleton h-28 rounded-xl" />
    </div>
    <div v-else-if="!rooms.length" class="adm-empty mt-6"><SiteIcon name="world" class="size-6" />{{ a.worlds.empty }}</div>
    <ul v-else class="mt-6 grid gap-3 md:grid-cols-2">
      <li v-for="(r, i) in rooms" :key="r.id">
        <article class="adm-row h-full flex-col" :data-row="i" :data-active="active === i">
          <div class="flex w-full flex-wrap items-center gap-2">
            <span class="font-semibold text-base-50">{{ r.name }}</span>
            <span class="chip py-0.5">{{ r.mcVersion }} · {{ r.loader }}</span>
            <span class="tone" :class="r.open ? 'tone-ok' : 'tone-muted'">{{ a.worlds.visibility[r.visibility] }}</span>
          </div>
          <p class="text-sm text-base-200">
            {{ a.worlds.host }}: <NuxtLink :to="`/admin/players/${r.host.uuid}`" class="text-base-50 hover:underline">{{ r.host.name || r.host.uuid.slice(0, 8) }}</NuxtLink>
            · {{ fill(a.worlds.players, { n: r.players, max: r.maxPlayers }) }}
          </p>
          <p class="text-xs text-base-400">{{ fill(a.worlds.members, r.members) }} · <span :title="when(r.heartbeatAt)">{{ fill(a.worlds.heartbeat, { date: rel(r.heartbeatAt) }) }}</span></p>
          <p class="adm-mono text-base-400">{{ a.worlds.code }} {{ r.code }}</p>
          <button type="button" class="btn btn-danger mt-1 self-start px-2.5 py-1 text-xs" @click="closing = r"><SiteIcon name="close" class="size-3.5" />{{ a.worlds.close }}</button>
        </article>
      </li>
    </ul>
    <AdminConfirm
      v-if="closing"
      :title="a.worlds.close"
      :text="fill(a.worlds.confirmClose, { name: closing.name, host: closing.host.name })"
      danger
      :busy="busy"
      @cancel="closing = null"
      @confirm="close"
    >
      <label class="label mt-3" for="close-reason">{{ a.worlds.closeReason }}</label>
      <input id="close-reason" v-model="reason" class="field" maxlength="200" />
    </AdminConfirm>
  </div>
</template>
