<script setup lang="ts">
// Rollen (nur Admins): Team-Liste, Rolle vergeben/ändern/entziehen, Rechte-Übersicht.
const { a, fill, day, actor } = useAdminText()
const { api, isAdmin, session } = useAdmin()

interface RoleView {
  uuid: string
  name: string | null
  role: 'admin' | 'moderator'
  source: 'env' | 'db'
  grantedAt: string | null
  grantedBy: { uuid: string, name: string | null } | null
  note: string | null
}
const roles = ref<RoleView[]>([])
const error = ref('')
const loading = ref(true)
const form = reactive({ player: '', role: 'moderator' as 'admin' | 'moderator', note: '' })
const busy = ref(false)
const removing = ref<RoleView | null>(null)

async function load() {
  error.value = ''
  try {
    roles.value = (await api<{ roles: RoleView[] }>('/v1/admin/roles')).roles
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  if (isAdmin.value) void load()
  else loading.value = false
})

async function grant() {
  const p = form.player.trim()
  if (!p) return
  busy.value = true
  error.value = ''
  try {
    const u = await api<{ user: { uuid: string } }>(`/v1/admin/users/${encodeURIComponent(p)}`)
    roles.value = (await api<{ roles: RoleView[] }>(`/v1/admin/roles/${u.user.uuid}`, {
      method: 'PUT',
      body: { role: form.role, ...(form.note.trim() ? { note: form.note.trim().slice(0, 200) } : {}) },
    })).roles
    form.player = ''
    form.note = ''
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}
async function change(r: RoleView, role: 'admin' | 'moderator') {
  busy.value = true
  try {
    roles.value = (await api<{ roles: RoleView[] }>(`/v1/admin/roles/${r.uuid}`, { method: 'PUT', body: { role, ...(r.note ? { note: r.note } : {}) } })).roles
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}
async function remove() {
  const r = removing.value
  if (!r) return
  busy.value = true
  try {
    roles.value = (await api<{ roles: RoleView[] }>(`/v1/admin/roles/${r.uuid}`, { method: 'DELETE' })).roles
    removing.value = null
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
    removing.value = null
  } finally {
    busy.value = false
  }
}
const RIGHTS: { key: string, mod: boolean }[] = [
  { key: 'reports', mod: true },
  { key: 'temp', mod: true },
  { key: 'uploads', mod: true },
  { key: 'appeals', mod: true },
  { key: 'permanent', mod: false },
  { key: 'modifyAdmin', mod: false },
  { key: 'roles', mod: false },
  { key: 'codes', mod: false },
]
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ a.roles.title }}</h1>
      <p class="adm-lead">{{ a.roles.lead }}</p>
    </header>
    <div v-if="!isAdmin" class="adm-empty mt-6"><SiteIcon name="key" class="size-6" />{{ a.nav.adminOnly }}</div>
    <template v-else>
      <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
      <div class="mt-6 grid gap-6 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <div class="min-w-0 space-y-2">
          <div v-if="loading" class="skeleton h-40 rounded-xl" />
          <article v-for="r in roles" v-else :key="r.uuid" class="adm-row items-center">
            <PlayerHead :uuid="r.uuid" :name="r.name" :size="36" />
            <div class="min-w-0 flex-1">
              <p class="flex flex-wrap items-center gap-2">
                <NuxtLink :to="`/admin/players/${r.uuid}`" class="font-semibold text-base-50 hover:underline">{{ r.name || r.uuid.slice(0, 8) }}</NuxtLink>
                <span class="tone" :class="r.role === 'admin' ? 'tone-danger' : 'tone-info'">{{ a.role[r.role] }}</span>
                <span v-if="r.source === 'env'" class="tone tone-muted" :title="a.roles.envHint">{{ a.roles.env }}</span>
              </p>
              <p class="text-xs text-base-400">
                <template v-if="r.grantedAt">{{ fill(a.roles.grantedBy, { name: actor(r.grantedBy), date: day(r.grantedAt) }) }}</template>
                <template v-else>{{ a.roles.envHint }}</template>
                <span v-if="r.note"> · {{ r.note }}</span>
              </p>
            </div>
            <div v-if="r.source === 'db' && r.uuid !== session?.uuid" class="flex flex-wrap gap-1.5">
              <select class="field adm-select py-1 text-xs" :value="r.role" :disabled="busy" :aria-label="a.roles.role" @change="change(r, ($event.target as HTMLSelectElement).value as 'admin' | 'moderator')">
                <option value="moderator">{{ a.role.moderator }}</option>
                <option value="admin">{{ a.role.admin }}</option>
              </select>
              <button type="button" class="btn btn-danger px-2.5 py-1 text-xs" :disabled="busy" @click="removing = r">{{ a.roles.remove }}</button>
            </div>
          </article>
        </div>
        <aside class="space-y-4">
          <form class="card p-5" @submit.prevent="grant">
            <h2 class="section-title">{{ a.roles.add }}</h2>
            <label class="label mt-4" for="role-player">{{ a.roles.player }}</label>
            <input id="role-player" v-model="form.player" class="field" maxlength="36" required />
            <label class="label mt-3" for="role-role">{{ a.roles.role }}</label>
            <select id="role-role" v-model="form.role" class="field">
              <option value="moderator">{{ a.role.moderator }}</option>
              <option value="admin">{{ a.role.admin }}</option>
            </select>
            <label class="label mt-3" for="role-note">{{ a.roles.note }}</label>
            <input id="role-note" v-model="form.note" class="field" maxlength="200" />
            <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy || !form.player.trim()">{{ a.roles.grant }}</button>
          </form>
          <div class="card p-5">
            <h2 class="section-title">{{ a.roles.matrix }}</h2>
            <table class="mt-3 w-full text-left text-xs">
              <thead class="text-base-400"><tr><th class="py-1" /><th class="px-2 py-1 text-center">{{ a.role.moderator }}</th><th class="px-2 py-1 text-center">{{ a.role.admin }}</th></tr></thead>
              <tbody class="divide-y divide-base-800">
                <tr v-for="x in RIGHTS" :key="x.key">
                  <td class="py-1.5 pr-2 text-base-200">{{ a.roles.rights[x.key] }}</td>
                  <td class="px-2 text-center"><SiteIcon :name="x.mod ? 'check' : 'minus'" class="inline size-4" :class="x.mod ? 'text-ok' : 'text-base-600'" /></td>
                  <td class="px-2 text-center"><SiteIcon name="check" class="inline size-4 text-ok" /></td>
                </tr>
              </tbody>
            </table>
          </div>
        </aside>
      </div>
    </template>
    <AdminConfirm v-if="removing" :title="a.roles.remove" :text="fill(a.roles.confirmRemove, { name: removing.name || removing.uuid })" danger :busy="busy" @cancel="removing = null" @confirm="remove" />
  </div>
</template>
