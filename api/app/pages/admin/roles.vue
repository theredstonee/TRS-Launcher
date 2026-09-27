<script setup lang="ts">
// Rollen & Team (§23.2): feste und eigene Rollen mit Farbe, Rang und Rechte-Raster; Mitglieder mit mehreren Rollen.
// Rang-Regel und „nur eigene Rechte vergeben“ prüft der Server – die Oberfläche sperrt nur vorab, was nicht geht.
import { PERMISSION_GROUPS } from '#shared/team'

const { a, m, fill, day, actor } = useAdminText()
const { t } = useTeamText()
const { api, session, can } = useAdmin()

interface RoleRef { id: string, name: string | null, color: string, builtin: boolean }
interface RoleView extends RoleRef {
  rank: number
  permissions: string[]
  maxSanctionMinutes: number | null
  locked: boolean
  public: boolean
  members: number
  editable: boolean
}
interface MemberView {
  uuid: string
  name: string | null
  source: 'env' | 'db'
  rank: number
  roles: RoleRef[]
  primary: RoleRef | null
  grantedAt: string | null
  grantedBy: { uuid: string, name: string | null } | null
  note: string | null
  editable: boolean
}

const tab = ref<'roles' | 'members'>('roles')
const roles = ref<RoleView[]>([])
const members = ref<MemberView[]>([])
const loading = ref(true)
const error = ref('')
const busy = ref(false)

async function load() {
  error.value = ''
  try {
    const r = await api<{ roles: RoleView[], members: MemberView[] }>('/v1/admin/team')
    roles.value = r.roles
    members.value = r.members
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(load)

const roleName = (r: RoleRef) => r.name ?? t.value.adm.roleNames[r.id] ?? r.id
const myRank = computed(() => session.value?.rank ?? 0)
const owner = computed(() => session.value?.owner ?? false)

// --- Rollen-Editor --------------------------------------------------------------------------------
interface Draft { id: string | null, name: string, color: string, rank: number, permissions: Set<string>, unlimited: boolean, maxDays: number, public: boolean, builtin: boolean, locked: boolean, before: Set<string> }
const draft = ref<Draft | null>(null)
const draftError = ref('')
const deleting = ref<RoleView | null>(null)

function freeRank(): number {
  const taken = new Set(roles.value.map((r) => r.rank))
  let r = Math.min(myRank.value - 1, 400)
  while (r > 1 && taken.has(r)) r--
  return Math.max(1, r)
}
function newRole() {
  draftError.value = ''
  draft.value = { id: null, name: '', color: '#e11d48', rank: freeRank(), permissions: new Set(['dashboard.view']), unlimited: false, maxDays: 1, public: true, builtin: false, locked: false, before: new Set() }
}
function editRole(r: RoleView) {
  draftError.value = ''
  draft.value = {
    id: r.id,
    name: r.name ?? '',
    color: r.color,
    rank: r.rank,
    permissions: new Set(r.permissions),
    unlimited: r.maxSanctionMinutes === null,
    maxDays: r.maxSanctionMinutes === null ? 7 : Math.round((r.maxSanctionMinutes / 1440) * 100) / 100,
    public: r.public,
    builtin: r.builtin,
    locked: r.locked,
    before: new Set(r.permissions),
  }
}
/** Recht ankreuzbar? Nur eigene Rechte dazugeben (bestehende dürfen bleiben bzw. weg). */
function permDisabled(p: string): boolean {
  const d = draft.value
  if (!d || d.locked) return true
  return !owner.value && !can(p) && !d.before.has(p)
}
function togglePerm(p: string) {
  const d = draft.value
  if (!d || permDisabled(p)) return
  if (d.permissions.has(p)) d.permissions.delete(p)
  else d.permissions.add(p)
}
function toggleGroup(perms: string[]) {
  const d = draft.value
  if (!d) return
  const usable = perms.filter((p) => !permDisabled(p))
  const all = usable.every((p) => d.permissions.has(p))
  for (const p of usable) {
    if (all) d.permissions.delete(p)
    else d.permissions.add(p)
  }
}
async function saveRole() {
  const d = draft.value
  if (!d) return
  busy.value = true
  draftError.value = ''
  const body: Record<string, unknown> = { color: d.color, public: d.public }
  if (!d.locked) {
    body.rank = d.rank
    body.permissions = [...d.permissions]
    body.maxSanctionMinutes = d.unlimited ? null : Math.max(1, Math.round(d.maxDays * 1440))
  }
  if (!d.builtin || d.name.trim()) body.name = d.name.trim() || null
  else body.name = null
  try {
    if (d.id) await api(`/v1/admin/team/roles/${d.id}`, { method: 'PATCH', body })
    else await api('/v1/admin/team/roles', { method: 'POST', body })
    draft.value = null
    await load()
  } catch (e) {
    draftError.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}
async function removeRole() {
  if (!deleting.value) return
  busy.value = true
  try {
    await api(`/v1/admin/team/roles/${deleting.value.id}`, { method: 'DELETE' })
    deleting.value = null
    draft.value = null
    await load()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}

// --- Mitglieder ------------------------------------------------------------------------------------
const assignable = computed(() => roles.value.filter((r) => !r.locked && (owner.value || r.rank < myRank.value)))
const member = ref<{ uuid: string, name: string, roles: Set<string>, note: string, isNew: boolean } | null>(null)
const memberError = ref('')
const removing = ref<MemberView | null>(null)
const addQuery = ref('')

async function startAdd() {
  const q = addQuery.value.trim()
  if (!q) return
  busy.value = true
  memberError.value = ''
  try {
    const u = await api<{ user: { uuid: string, name: string | null } }>(`/v1/admin/users/${encodeURIComponent(q)}`)
    const existing = members.value.find((m) => m.uuid === u.user.uuid)
    member.value = { uuid: u.user.uuid, name: u.user.name ?? q, roles: new Set(existing?.roles.map((r) => r.id) ?? []), note: existing?.note ?? '', isNew: !existing }
    addQuery.value = ''
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}
function editMember(m: MemberView) {
  memberError.value = ''
  member.value = { uuid: m.uuid, name: m.name ?? m.uuid.slice(0, 8), roles: new Set(m.roles.map((r) => r.id)), note: m.note ?? '', isNew: false }
}
function toggleMemberRole(id: string) {
  const m = member.value
  if (!m) return
  if (m.roles.has(id)) m.roles.delete(id)
  else m.roles.add(id)
}
const memberMain = computed(() => {
  const m = member.value
  if (!m) return null
  return roles.value.filter((r) => m.roles.has(r.id)).sort((x, y) => y.rank - x.rank)[0] ?? null
})
async function saveMember() {
  const m = member.value
  if (!m) return
  busy.value = true
  memberError.value = ''
  try {
    const r = await api<{ members: MemberView[] }>(`/v1/admin/team/members/${m.uuid}`, {
      method: 'PUT',
      body: { roles: [...m.roles], note: m.note.trim() ? m.note.trim().slice(0, 200) : null },
    })
    members.value = r.members
    member.value = null
    void load()
  } catch (e) {
    memberError.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}
async function removeMember() {
  if (!removing.value) return
  busy.value = true
  try {
    members.value = (await api<{ members: MemberView[] }>(`/v1/admin/team/members/${removing.value.uuid}`, { method: 'DELETE' })).members
    removing.value = null
    void load()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}
const durationText = (r: RoleView) => (r.maxSanctionMinutes === null ? t.value.adm.roles.unlimited : `${Math.round((r.maxSanctionMinutes / 1440) * 10) / 10} ${t.value.adm.roles.days}`)
</script>

<template>
  <div class="adm-page">
    <header class="flex flex-wrap items-end gap-3">
      <div class="min-w-0 flex-1">
        <h1 class="adm-title">{{ t.adm.roles.title }}</h1>
        <p class="adm-lead">{{ t.adm.roles.lead }}</p>
      </div>
      <div class="adm-seg">
        <button type="button" :aria-pressed="tab === 'roles'" @click="tab = 'roles'">{{ t.adm.roles.tabRoles }} · {{ roles.length }}</button>
        <button type="button" :aria-pressed="tab === 'members'" @click="tab = 'members'">{{ t.adm.roles.tabMembers }} · {{ members.length }}</button>
      </div>
    </header>
    <p class="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-base-400">
      <span class="flex items-center gap-1.5"><SiteIcon name="key" class="size-3.5" />{{ fill(t.adm.roles.rankRule, { rank: myRank }) }}</span>
      <span>{{ t.adm.roles.multi }}</span>
    </p>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <div v-if="loading" class="skeleton mt-6 h-64 rounded-xl" />

    <!-- Rollen -->
    <section v-else-if="tab === 'roles'" class="mt-6">
      <div class="flex justify-end">
        <button type="button" class="btn btn-primary" @click="newRole"><SiteIcon name="plus" class="size-4" />{{ t.adm.roles.newRole }}</button>
      </div>
      <ul class="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
        <li v-for="r in roles" :key="r.id">
          <article class="role-card card h-full p-4" :style="{ '--role': r.color }">
            <div class="flex items-start gap-2">
              <RoleBadge :role="r" />
              <span class="ml-auto font-mono text-xs text-base-400" :title="t.adm.roles.rank">#{{ r.rank }}</span>
            </div>
            <p class="mt-3 flex flex-wrap gap-1.5 text-xs">
              <span v-if="r.builtin" class="tone tone-muted">{{ t.adm.roles.builtin }}</span>
              <span v-if="r.locked" class="tone tone-warn"><SiteIcon name="crown" class="size-3" />{{ t.adm.roles.locked }}</span>
              <span class="tone tone-info">{{ fill(t.adm.roles.members, { n: r.members }) }}</span>
              <span v-if="r.public" class="tone tone-ok"><SiteIcon name="users" class="size-3" />{{ m.nav.team }}</span>
            </p>
            <p class="mt-2 text-xs text-base-400">
              {{ r.permissions.length }} {{ t.adm.roles.permissions }}
              <template v-if="r.permissions.some((p: string) => p.startsWith('sanctions.'))"> · {{ t.adm.roles.maxDuration }}: {{ durationText(r) }}</template>
            </p>
            <div class="mt-3 flex gap-2">
              <button v-if="r.editable || (r.locked && owner)" type="button" class="btn btn-ghost px-3 py-1 text-xs" @click="editRole(r)"><SiteIcon name="key" class="size-3.5" />{{ t.adm.roles.editRole }}</button>
              <span v-else class="text-xs text-base-600">{{ t.adm.roles.aboveYou }}</span>
            </div>
          </article>
        </li>
      </ul>
    </section>

    <!-- Mitglieder -->
    <section v-else class="mt-6 grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <div class="min-w-0 space-y-2">
        <p v-if="!members.length" class="adm-empty">{{ t.adm.roles.noMembers }}</p>
        <article v-for="m in members" :key="m.uuid" class="adm-row items-center">
          <PlayerHead :uuid="m.uuid" :name="m.name" :size="36" />
          <div class="min-w-0 flex-1">
            <p class="flex flex-wrap items-center gap-1.5">
              <NuxtLink :to="`/admin/players/${m.uuid}`" class="mr-1 font-semibold text-base-50 hover:underline">{{ m.name || m.uuid.slice(0, 8) }}</NuxtLink>
              <RoleBadge v-for="(r, i) in m.roles" :key="r.id" :role="r" :small="Number(i) > 0" />
              <span v-if="m.uuid === session?.uuid" class="text-xs text-base-400">({{ t.adm.roles.you }})</span>
            </p>
            <p class="mt-0.5 text-xs text-base-400">
              <template v-if="m.source === 'env'">{{ t.adm.roles.owner }}</template>
              <template v-else-if="m.grantedAt">{{ fill(t.adm.roles.grantedBy, { name: actor(m.grantedBy), date: day(m.grantedAt) }) }}</template>
              <span v-if="m.note"> · {{ m.note }}</span>
            </p>
          </div>
          <div v-if="m.editable" class="flex flex-wrap gap-1.5">
            <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="busy" @click="editMember(m)">{{ t.adm.jobs.edit }}</button>
            <button type="button" class="btn btn-danger px-2.5 py-1 text-xs" :disabled="busy" @click="removing = m">{{ t.adm.roles.removeMember }}</button>
          </div>
        </article>
      </div>
      <aside>
        <form class="card p-5" @submit.prevent="startAdd">
          <h2 class="section-title">{{ t.adm.roles.addMember }}</h2>
          <label class="label mt-4" for="member-q">{{ t.adm.roles.player }}</label>
          <input id="member-q" v-model="addQuery" class="field" maxlength="36" required />
          <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy || !addQuery.trim()"><SiteIcon name="plus" class="size-4" />{{ t.adm.roles.addMember }}</button>
        </form>
      </aside>
    </section>

    <!-- Rollen-Dialog -->
    <AdminDialog v-if="draft" :title="draft.id ? t.adm.roles.editRole : t.adm.roles.newRole" size="xl" @close="draft = null">
      <div class="grid gap-4 sm:grid-cols-[minmax(0,1fr)_9rem_7rem]">
        <div>
          <label class="label" for="role-name">{{ t.adm.roles.name }}</label>
          <input id="role-name" v-model="draft.name" class="field" maxlength="32" :required="!draft.builtin" :placeholder="draft.builtin && draft.id ? fill(t.adm.roles.nameDefault, { name: t.adm.roleNames[draft.id] ?? draft.id }) : ''" />
        </div>
        <div>
          <label class="label" for="role-color">{{ t.adm.roles.color }}</label>
          <div class="flex items-center gap-2">
            <input id="role-color" v-model="draft.color" type="color" class="h-10 w-12 cursor-pointer rounded border border-base-700 bg-base-950" />
            <input v-model="draft.color" class="field font-mono text-xs" maxlength="7" pattern="#[0-9a-f]{6}" :aria-label="t.adm.roles.color" />
          </div>
        </div>
        <div>
          <label class="label" for="role-rank">{{ t.adm.roles.rank }}</label>
          <input id="role-rank" v-model.number="draft.rank" type="number" min="1" :max="owner ? 999 : myRank - 1" class="field" :disabled="draft.locked" />
        </div>
      </div>
      <p class="mt-1 text-xs text-base-400">{{ t.adm.roles.rankHint }}</p>
      <div class="mt-4 flex flex-wrap items-center gap-x-6 gap-y-3">
        <label class="flex items-center gap-2 text-sm text-base-200"><input v-model="draft.public" type="checkbox" class="adm-check mt-0" />{{ t.adm.roles.public }}</label>
        <div v-if="!draft.locked" class="flex flex-wrap items-center gap-2 text-sm text-base-200">
          <span>{{ t.adm.roles.maxDuration }}:</span>
          <label class="flex items-center gap-1.5"><input v-model="draft.unlimited" type="checkbox" class="adm-check mt-0" />{{ t.adm.roles.unlimited }}</label>
          <template v-if="!draft.unlimited">
            <input v-model.number="draft.maxDays" type="number" min="0.05" step="0.5" max="3650" class="field w-24 py-1" :aria-label="t.adm.roles.maxDuration" />
            <span>{{ t.adm.roles.days }}</span>
          </template>
        </div>
      </div>
      <p v-if="!draft.locked" class="mt-1 text-xs text-base-400">{{ t.adm.roles.maxHint }}</p>

      <h3 class="section-title mt-6">{{ t.adm.roles.permissions }}</h3>
      <p v-if="draft.locked" class="mt-2 text-sm text-lamp-300">{{ t.adm.roles.locked }}</p>
      <div class="perm-grid mt-3">
        <fieldset v-for="g in PERMISSION_GROUPS" :key="g.id" class="rounded-lg border border-base-800 p-3">
          <legend class="px-1">
            <button type="button" class="text-xs font-semibold tracking-wide text-base-300 uppercase hover:text-base-50" :disabled="draft.locked" @click="toggleGroup(g.permissions)">{{ t.adm.permGroups[g.id] }}</button>
          </legend>
          <label v-for="p in g.permissions" :key="p" class="perm-row" :class="{ off: permDisabled(p) && !draft.permissions.has(p) }">
            <input type="checkbox" class="adm-check mt-0" :checked="draft.locked || draft.permissions.has(p)" :disabled="permDisabled(p)" @change="togglePerm(p)" />
            <span class="min-w-0 flex-1">
              <span class="block text-sm text-base-100">{{ t.adm.perms[p] }}</span>
              <span class="block font-mono text-[10px] text-base-500">{{ p }}</span>
            </span>
          </label>
        </fieldset>
      </div>
      <p v-if="draftError" role="alert" class="mt-3 text-sm text-redstone-300">{{ draftError }}</p>
      <template #footer>
        <button v-if="draft.id && !draft.builtin" type="button" class="btn btn-danger mr-auto" :disabled="busy" @click="deleting = roles.find((x: RoleView) => x.id === draft!.id) ?? null"><SiteIcon name="trash" class="size-4" />{{ t.adm.roles.delete }}</button>
        <button type="button" class="btn btn-ghost" @click="draft = null">{{ a.common.cancel }}</button>
        <button type="button" class="btn btn-primary" :disabled="busy || (!draft.builtin && draft.name.trim().length < 2)" @click="saveRole">{{ draft.id ? t.adm.roles.save : t.adm.roles.create }}</button>
      </template>
    </AdminDialog>

    <!-- Mitglieds-Dialog -->
    <AdminDialog v-if="member" :title="member.name" :kicker="t.adm.roles.pickRoles" @close="member = null">
      <div class="space-y-1.5">
        <label v-for="r in roles.filter((x: RoleView) => !x.locked)" :key="r.id" class="adm-option flex items-center gap-3" :class="{ 'opacity-50': !assignable.includes(r) }">
          <input type="checkbox" class="adm-check mt-0" :checked="member.roles.has(r.id)" :disabled="!assignable.includes(r)" @change="toggleMemberRole(r.id)" />
          <RoleBadge :role="r" />
          <span class="ml-auto font-mono text-xs text-base-400">#{{ r.rank }}</span>
          <span v-if="memberMain?.id === r.id" class="tone tone-info">{{ t.adm.roles.main }}</span>
        </label>
      </div>
      <label class="label mt-4" for="member-note">{{ t.adm.roles.note }}</label>
      <input id="member-note" v-model="member.note" class="field" maxlength="200" />
      <p v-if="memberError" role="alert" class="mt-3 text-sm text-redstone-300">{{ memberError }}</p>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="member = null">{{ a.common.cancel }}</button>
        <button type="button" class="btn btn-primary" :disabled="busy" @click="saveMember">{{ t.adm.roles.saveMember }}</button>
      </template>
    </AdminDialog>

    <AdminConfirm v-if="deleting" :title="t.adm.roles.delete" :text="fill(t.adm.roles.confirmDelete, { name: roleName(deleting), n: deleting.members })" danger :busy="busy" @cancel="deleting = null" @confirm="removeRole" />
    <AdminConfirm v-if="removing" :title="t.adm.roles.removeMember" :text="fill(t.adm.roles.confirmRemove, { name: removing.name || removing.uuid })" danger :busy="busy" @cancel="removing = null" @confirm="removeMember" />
  </div>
</template>

<style scoped>
.role-card {
  border-top: 3px solid var(--role);
}
.perm-grid {
  display: grid;
  gap: 0.75rem;
  grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr));
}
.perm-row {
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  padding: 0.35rem 0.25rem;
  border-radius: 0.375rem;
  cursor: pointer;
}
.perm-row:hover {
  background: var(--color-base-900);
}
.perm-row.off {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
