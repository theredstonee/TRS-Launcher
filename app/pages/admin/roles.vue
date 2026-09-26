<script setup lang="ts">
import type { PlayerListItem, RoleView } from '~/utils/team'

// Rollen (nur Admins): wer Admin oder Moderator ist. Admins aus der
// Server-Konfiguration sind fest (nicht änderbar). Neue Team-Mitglieder müssen
// sich einmal angemeldet haben.
const toasts = useToasts()
const team = useTeam()
const trs = useTrsStore()
const roles = ref<RoleView[] | null>(null)
const q = ref('')
const matches = ref<PlayerListItem[]>([])
const pick = ref<PlayerListItem | null>(null)
const role = ref<'moderator' | 'admin'>('moderator')
const note = ref('')
const busy = ref(false)
const removing = ref<RoleView | null>(null)

async function load() {
  try {
    roles.value = (await backend.team.roles()).roles
  } catch (e) {
    roles.value = []
    toasts.error(e)
  }
}
onMounted(() => team.isAdmin.value && void load())

let timer: ReturnType<typeof setTimeout> | null = null
watch(q, (v) => {
  if (timer) clearTimeout(timer)
  pick.value = null
  const name = v.trim()
  if (!/^[A-Za-z0-9_]{1,16}$/.test(name)) {
    matches.value = []
    return
  }
  timer = setTimeout(async () => {
    try {
      matches.value = (await backend.team.players({ q: name, limit: 6 })).players
    } catch {
      matches.value = []
    }
  }, 250)
})

async function add() {
  const p = pick.value
  if (!p) return
  busy.value = true
  try {
    roles.value = (await backend.team.setRole(p.uuid, role.value, note.value.trim() || null)).roles
    toasts.ok(t('team.rolesPage.added', { name: p.name, role: t(`team.roles.${role.value}`) }))
    q.value = ''
    note.value = ''
    pick.value = null
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}

async function change(r: RoleView, next: 'admin' | 'moderator') {
  try {
    roles.value = (await backend.team.setRole(r.uuid, next, r.note)).roles
  } catch (e) {
    toasts.error(e)
  }
}

async function remove() {
  const r = removing.value
  if (!r) return
  busy.value = true
  try {
    roles.value = (await backend.team.removeRole(r.uuid)).roles
    toasts.ok(t('team.rolesPage.removed', { name: r.name ?? r.uuid }))
    removing.value = null
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section :aria-label="t('team.nav.roles')" data-testid="admin-roles">
    <p v-if="!team.isAdmin.value" class="card px-4 py-6 text-center text-sm text-base-400">{{ t('team.common.adminOnly') }}</p>
    <div v-else class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <div class="min-w-0">
        <div v-if="!roles" class="skeleton h-40" />
        <ul v-else class="space-y-2">
          <li v-for="r in roles" :key="r.uuid" class="card flex flex-wrap items-center gap-3 px-4 py-3">
            <span class="block size-9 overflow-hidden rounded-md"><PlayerFace :uuid="r.uuid" :name="r.name ?? '?'" /></span>
            <div class="min-w-0 flex-1">
              <NuxtLink :to="`/admin/players/${r.uuid}`" class="font-semibold text-base-50 hover:text-redstone-300">{{ r.name ?? r.uuid }}</NuxtLink>
              <p class="text-xs text-base-400">
                <template v-if="r.source === 'env'">{{ t('team.rolesPage.fixed') }}</template>
                <template v-else>{{ t('team.rolesPage.granted', { date: formatShortDate(r.grantedAt), name: r.grantedBy?.name ?? '–' }) }}</template>
                <span v-if="r.note"> · {{ r.note }}</span>
              </p>
            </div>
            <select
              v-if="r.source === 'db' && r.uuid !== trs.me?.uuid"
              :value="r.role"
              class="field w-36 py-1.5 text-xs"
              :aria-label="t('team.rolesPage.role')"
              @change="change(r, ($event.target as HTMLSelectElement).value === 'admin' ? 'admin' : 'moderator')"
            >
              <option value="moderator">{{ t('team.roles.moderator') }}</option>
              <option value="admin">{{ t('team.roles.admin') }}</option>
            </select>
            <span v-else class="badge bg-redstone-900/60 text-redstone-300">{{ t(`team.roles.${r.role}`) }}</span>
            <button v-if="r.source === 'db' && r.uuid !== trs.me?.uuid" class="btn btn-ghost px-2.5 py-1 text-xs hover:text-redstone-300" @click="removing = r">
              {{ t('common.actions.remove') }}
            </button>
          </li>
        </ul>
      </div>
      <form class="card h-fit space-y-3 p-4" @submit.prevent="add">
        <h2 class="section-title">{{ t('team.rolesPage.add') }}</h2>
        <div class="relative">
          <input v-model="q" class="field" maxlength="16" :placeholder="t('team.rolesPage.search')" :aria-label="t('team.rolesPage.search')" />
          <ul v-if="matches.length && !pick" class="menu top-full right-0 left-0 mt-1">
            <li v-for="m in matches" :key="m.uuid">
              <button type="button" class="menu-item" @click="(pick = m), (matches = [])">
                <span class="block size-5 overflow-hidden rounded"><PlayerFace :uuid="m.uuid" :name="m.name" /></span>{{ m.name }}
              </button>
            </li>
          </ul>
        </div>
        <p v-if="pick" class="text-xs text-base-200">{{ t('team.rolesPage.picked', { name: pick.name }) }}</p>
        <select v-model="role" class="field" :aria-label="t('team.rolesPage.role')">
          <option value="moderator">{{ t('team.roles.moderator') }}</option>
          <option value="admin">{{ t('team.roles.admin') }}</option>
        </select>
        <input v-model="note" class="field" maxlength="200" :placeholder="t('team.rolesPage.note')" :aria-label="t('team.rolesPage.note')" />
        <p class="text-[11px] text-base-400">{{ t(`team.rolesPage.help.${role}`) }}</p>
        <button class="btn btn-primary w-full" :disabled="!pick || busy">{{ t('team.rolesPage.add') }}</button>
      </form>
    </div>
    <AdminConfirm
      v-if="removing"
      :title="t('team.rolesPage.removeTitle', { name: removing.name ?? removing.uuid })"
      :text="t('team.rolesPage.removeText')"
      :confirm-label="t('common.actions.remove')"
      danger
      :busy="busy"
      @confirm="remove"
      @close="removing = null"
    />
  </section>
</template>
