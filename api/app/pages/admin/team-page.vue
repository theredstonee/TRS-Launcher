<script setup lang="ts">
// Team-Seite im Team-Bereich (§26.2, Recht team.page): wer öffentlich steht, in welcher Gruppe und in welcher
// Reihenfolge. Sortieren per Drag & Drop (Griff) oder mit den Pfeil-Knöpfen / „In Gruppe verschieben“ (Tastatur,
// Touch). Titel (EN/DE/ES), Discord und Links im Dialog. Prüfen tut der Server – die Oberfläche prüft vorab gleich.
const { a, fill } = useAdminText()
const { t } = useTeamText()
const { api, can } = useAdmin()
const lp = useLocalePath()

const view = ref<TeamPageAdmin | null>(null)
const loading = ref(true)
const error = ref('')
const busy = ref(false)
const editable = computed(() => view.value?.editable ?? false)
const tp = computed(() => t.value.adm.teamPage)

function errorText(e: unknown): string {
  const code = apiCode(e)
  return tp.value.errors[code] ?? fill(a.value.common.failed, { error: apiMessage(e) })
}

async function load() {
  error.value = ''
  try {
    view.value = await api<TeamPageAdmin>('/v1/admin/team-page')
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const roleName = (r: TeamRole) => r.name ?? t.value.adm.roleNames[r.id] ?? r.id
const roleById = (id: string | null) => view.value?.groups.find((g) => g.role.id === id)?.role ?? null
const total = computed(() => (view.value?.groups.reduce((n, g) => n + g.members.length, 0) ?? 0) + (view.value?.ungrouped.length ?? 0))

// --- Hinzufügen -----------------------------------------------------------------------------------
const addQuery = ref('')
const addGroup = ref('')
const addError = ref('')
async function add() {
  const player = addQuery.value.trim()
  if (!player) return
  busy.value = true
  addError.value = ''
  try {
    view.value = await api<TeamPageAdmin>('/v1/admin/team-page/members', { method: 'POST', body: { player, ...(addGroup.value ? { roleId: addGroup.value } : {}) } })
    addQuery.value = ''
  } catch (e) {
    addError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

// --- Reihenfolge ---------------------------------------------------------------------------------
/** Neue Reihenfolge der betroffenen Gruppen speichern (Antwort ersetzt die Ansicht). */
async function saveOrder(roleIds: string[]) {
  const v = view.value
  if (!v) return
  const groups = [...new Set(roleIds)].map((id) => ({ roleId: id, uuids: v.groups.find((g) => g.role.id === id)?.members.map((m) => m.uuid) ?? [] }))
  busy.value = true
  error.value = ''
  try {
    view.value = await api<TeamPageAdmin>('/v1/admin/team-page/order', { method: 'PUT', body: { groups } })
  } catch (e) {
    error.value = errorText(e)
    await load()
  } finally {
    busy.value = false
  }
}

/** Person lokal verschieben (Gruppe + Position) und speichern. */
function move(uuid: string, toRole: string, toIndex: number) {
  const v = view.value
  if (!v || !editable.value) return
  let from: string | null = null
  let member: TeamPageMember | undefined
  for (const g of v.groups) {
    const i = g.members.findIndex((m) => m.uuid === uuid)
    if (i >= 0) {
      from = g.role.id
      member = g.members.splice(i, 1)[0]
      if (from === toRole && i < toIndex) toIndex--
      break
    }
  }
  if (!member) {
    const i = v.ungrouped.findIndex((m) => m.uuid === uuid)
    if (i < 0) return
    member = v.ungrouped.splice(i, 1)[0]
  }
  const target = v.groups.find((g) => g.role.id === toRole)
  if (!target || !member) return
  member.roleId = toRole
  target.members.splice(Math.max(0, Math.min(toIndex, target.members.length)), 0, member)
  void saveOrder(from && from !== toRole ? [from, toRole] : [toRole])
}

function step(uuid: string, roleId: string, delta: -1 | 1) {
  const g = view.value?.groups.find((x) => x.role.id === roleId)
  if (!g) return
  const i = g.members.findIndex((m) => m.uuid === uuid)
  const j = i + delta
  if (i < 0 || j < 0 || j >= g.members.length) return
  move(uuid, roleId, delta > 0 ? j + 1 : j)
  // Fokus bleibt auf dem gleichen Knopf der verschobenen Zeile.
  void nextTick(() => document.getElementById(`tp-${delta > 0 ? 'down' : 'up'}-${uuid}`)?.focus())
}

// Drag & Drop (Maus/Stift; Touch nutzt die Knöpfe).
const dragging = ref<string | null>(null)
const drop = ref<{ roleId: string, index: number } | null>(null)

function onDragStart(e: DragEvent, uuid: string) {
  if (!editable.value || !e.dataTransfer) return
  dragging.value = uuid
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/plain', uuid)
  const row = (e.target as HTMLElement).closest('.tp-row') as HTMLElement | null
  if (row) e.dataTransfer.setDragImage(row, 24, 24)
}
function onDragEnd() {
  dragging.value = null
  drop.value = null
}
function onRowOver(e: DragEvent, roleId: string, index: number) {
  if (!dragging.value) return
  e.preventDefault()
  const el = e.currentTarget as HTMLElement
  const r = el.getBoundingClientRect()
  drop.value = { roleId, index: e.clientY > r.top + r.height / 2 ? index + 1 : index }
}
function onGroupOver(e: DragEvent, roleId: string, length: number) {
  if (!dragging.value) return
  e.preventDefault()
  if (!drop.value || drop.value.roleId !== roleId) drop.value = { roleId, index: length }
}
function onDrop(e: DragEvent) {
  e.preventDefault()
  const uuid = dragging.value
  const d = drop.value
  onDragEnd()
  if (uuid && d) move(uuid, d.roleId, d.index)
}
const showLine = (roleId: string, index: number) => !!dragging.value && drop.value?.roleId === roleId && drop.value.index === index

// --- Bearbeiten -----------------------------------------------------------------------------------
type LangKey = 'en' | 'de' | 'es'
const LANGS: LangKey[] = ['en', 'de', 'es']
interface Draft { uuid: string, name: string, titles: Record<LangKey, string>, discord: string, links: TeamLink[] }
const draft = ref<Draft | null>(null)
const draftLang = ref<LangKey>('en')
const draftErrors = ref<string[]>([])
const saved = ref(false)

function edit(m: TeamPageMember) {
  draftErrors.value = []
  saved.value = false
  draftLang.value = 'en'
  draft.value = {
    uuid: m.uuid,
    name: m.name,
    titles: { en: m.titles.en ?? '', de: m.titles.de ?? '', es: m.titles.es ?? '' },
    discord: m.discord ?? '',
    links: m.links.map((l) => ({ ...l })),
  }
}

const DISCORD = /^(?!.*\.\.)[a-z0-9_.]{2,32}$/
function validHttps(url: string): boolean {
  try {
    const u = new URL(url.trim())
    return u.protocol === 'https:' && !u.username && !u.password && /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(u.hostname)
  } catch {
    return false
  }
}
/** Gleiche Regeln wie der Server – Fehler direkt am Feld statt eines rohen Codes. */
function checkDraft(d: Draft): string[] {
  const errs: string[] = []
  const discord = d.discord.trim().toLowerCase()
  if (discord && !DISCORD.test(discord)) errs.push(tp.value.errors.discord!)
  d.links.forEach((l, i) => {
    if (!l.label.trim()) errs.push(fill(tp.value.errors.linkLabel!, { n: i + 1 }))
    if (!validHttps(l.url)) errs.push(fill(tp.value.errors.linkUrl!, { n: i + 1 }))
  })
  return errs
}
const linkInvalid = (l: TeamLink) => !!l.url.trim() && !validHttps(l.url)
const discordInvalid = computed(() => {
  const d = draft.value?.discord.trim().toLowerCase() ?? ''
  return !!d && !DISCORD.test(d)
})

async function saveDraft() {
  const d = draft.value
  if (!d) return
  draftErrors.value = checkDraft(d)
  if (draftErrors.value.length) return
  busy.value = true
  try {
    const titles: Partial<Record<LangKey, string>> = {}
    for (const l of LANGS) if (d.titles[l].trim()) titles[l] = d.titles[l].trim().slice(0, 60)
    view.value = await api<TeamPageAdmin>(`/v1/admin/team-page/members/${d.uuid}`, {
      method: 'PATCH',
      body: {
        titles,
        discord: d.discord.trim() ? d.discord.trim().toLowerCase() : null,
        links: d.links.map((l) => ({ label: l.label.trim().slice(0, 30), url: l.url.trim() })),
      },
    })
    draft.value = null
  } catch (e) {
    draftErrors.value = [errorText(e)]
    if (apiCode(e) === 'not_on_team_page') {
      draft.value = null
      await load()
    }
  } finally {
    busy.value = false
  }
}

// --- Entfernen ------------------------------------------------------------------------------------
const removing = ref<TeamPageMember | null>(null)
async function remove() {
  const m = removing.value
  if (!m) return
  busy.value = true
  try {
    view.value = await api<TeamPageAdmin>(`/v1/admin/team-page/members/${m.uuid}`, { method: 'DELETE' })
    removing.value = null
  } catch (e) {
    error.value = errorText(e)
    removing.value = null
  } finally {
    busy.value = false
  }
}

function moveToGroup(m: TeamPageMember, roleId: string) {
  const target = view.value?.groups.find((g) => g.role.id === roleId)
  if (target && roleId !== m.roleId) move(m.uuid, roleId, target.members.length)
}
</script>

<template>
  <div class="adm-page">
    <header class="flex flex-wrap items-end gap-3">
      <div class="min-w-0 flex-1">
        <h1 class="adm-title">{{ tp.title }}</h1>
        <p class="adm-lead">{{ tp.lead }}</p>
      </div>
      <NuxtLink :to="lp('/team')" target="_blank" class="btn btn-ghost text-sm"><SiteIcon name="external" class="size-4" />{{ tp.open }}</NuxtLink>
    </header>
    <p v-if="view && !editable" class="mt-3 text-sm text-lamp-300">{{ tp.readOnly }}</p>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <div v-if="loading" class="skeleton mt-6 h-64 rounded-xl" />

    <div v-else-if="view" class="mt-6 grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <div class="min-w-0 space-y-5" :aria-busy="busy">
        <p class="text-xs text-base-400">{{ fill(tp.members, { n: total }) }}</p>
        <section
          v-for="g in view.groups"
          :key="g.role.id"
          class="tp-group"
          :class="{ 'is-target': dragging && drop?.roleId === g.role.id }"
          :style="{ '--role': g.role.color }"
          :aria-labelledby="`tp-g-${g.role.id}`"
          @dragover="onGroupOver($event, g.role.id, g.members.length)"
          @drop="onDrop"
        >
          <h2 :id="`tp-g-${g.role.id}`" class="flex flex-wrap items-center gap-2">
            <RoleBadge :role="g.role" />
            <span class="text-xs text-base-400">{{ g.members.length }}</span>
            <span v-if="!g.role.public" class="tone tone-warn text-xs"><SiteIcon name="warn" class="size-3" />{{ tp.notPublic }}</span>
          </h2>
          <ul class="mt-3 space-y-1.5">
            <template v-for="(p, i) in g.members" :key="p.uuid">
              <li v-if="showLine(g.role.id, i)" class="tp-line" aria-hidden="true" />
              <li
                class="tp-row adm-row items-center"
                :class="{ 'is-dragging': dragging === p.uuid }"
                @dragover="onRowOver($event, g.role.id, i)"
                @drop.stop="onDrop"
              >
                <span
                  v-if="editable"
                  class="tp-grip"
                  draggable="true"
                  role="img"
                  :aria-label="fill(tp.grip, { name: p.name })"
                  :title="fill(tp.grip, { name: p.name })"
                  @dragstart="onDragStart($event, p.uuid)"
                  @dragend="onDragEnd"
                ><SiteIcon name="grip" class="size-4" /></span>
                <PlayerHead :uuid="p.uuid" :name="p.name" :size="36" />
                <div class="min-w-0 flex-1">
                  <p class="flex flex-wrap items-center gap-1.5">
                    <NuxtLink v-if="can('players.view')" :to="`/admin/players/${p.uuid}`" class="font-semibold text-base-50 hover:underline">{{ p.name }}</NuxtLink>
                    <span v-else class="font-semibold text-base-50">{{ p.name }}</span>
                    <span v-if="p.banned" class="tone tone-danger text-xs">{{ tp.banned }}</span>
                    <span v-if="!p.mainRole" class="tone tone-muted text-xs">{{ tp.noTeam }}</span>
                    <span v-else-if="p.mainRole !== g.role.id && roleById(p.mainRole)" class="text-xs text-base-400">{{ fill(tp.otherMain, { role: roleName(roleById(p.mainRole)!) }) }}</span>
                  </p>
                  <p class="mt-0.5 truncate text-xs text-base-400">
                    <template v-if="p.titles.de || p.titles.en || p.titles.es">{{ p.titles.de || p.titles.en || p.titles.es }}</template>
                    <template v-if="p.discord"> · <SiteIcon name="discord" class="inline size-3" /> {{ p.discord }}</template>
                    <template v-if="p.links.length"> · <SiteIcon name="link" class="inline size-3" /> {{ p.links.length }}</template>
                  </p>
                </div>
                <div v-if="editable" class="flex flex-wrap items-center justify-end gap-1">
                  <button :id="`tp-up-${p.uuid}`" type="button" class="btn btn-ghost px-2 py-1" :disabled="busy || i === 0" :aria-label="`${tp.up}: ${p.name}`" :title="tp.up" @click="step(p.uuid, g.role.id, -1)"><SiteIcon name="up" class="size-3.5" /></button>
                  <button :id="`tp-down-${p.uuid}`" type="button" class="btn btn-ghost px-2 py-1" :disabled="busy || i === g.members.length - 1" :aria-label="`${tp.down}: ${p.name}`" :title="tp.down" @click="step(p.uuid, g.role.id, 1)"><SiteIcon name="chevron" class="size-3.5" /></button>
                  <select class="field adm-select py-1 text-xs" :aria-label="`${tp.moveTo}: ${p.name}`" :disabled="busy" :value="g.role.id" @change="moveToGroup(p, ($event.target as HTMLSelectElement).value)">
                    <option v-for="o in view.groups" :key="o.role.id" :value="o.role.id">{{ roleName(o.role) }}</option>
                  </select>
                  <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="busy" @click="edit(p)">{{ tp.edit }}</button>
                  <button type="button" class="btn btn-danger px-2.5 py-1 text-xs" :disabled="busy" @click="removing = p">{{ tp.remove }}</button>
                </div>
              </li>
            </template>
            <li v-if="showLine(g.role.id, g.members.length) && g.members.length" class="tp-line" aria-hidden="true" />
            <li v-if="!g.members.length" class="tp-empty" :class="{ 'is-hot': dragging && drop?.roleId === g.role.id }">{{ dragging ? tp.dropHere : tp.empty }}</li>
          </ul>
        </section>

        <section v-if="view.ungrouped.length" class="tp-group" style="--role: var(--color-base-600)">
          <h2 class="text-sm font-semibold text-base-200">{{ tp.ungrouped }}</h2>
          <ul class="mt-3 space-y-1.5">
            <li v-for="p in view.ungrouped" :key="p.uuid" class="adm-row items-center">
              <PlayerHead :uuid="p.uuid" :name="p.name" :size="36" />
              <span class="flex-1 font-semibold text-base-50">{{ p.name }}</span>
              <select v-if="editable" class="field adm-select py-1 text-xs" :aria-label="`${tp.moveTo}: ${p.name}`" :disabled="busy" value="" @change="moveToGroup(p, ($event.target as HTMLSelectElement).value)">
                <option value="" disabled>{{ tp.moveTo }}</option>
                <option v-for="o in view.groups" :key="o.role.id" :value="o.role.id">{{ roleName(o.role) }}</option>
              </select>
              <button v-if="editable" type="button" class="btn btn-danger px-2.5 py-1 text-xs" :disabled="busy" @click="removing = p">{{ tp.remove }}</button>
            </li>
          </ul>
        </section>
      </div>

      <aside v-if="editable">
        <form class="card sticky top-4 p-5" @submit.prevent="add">
          <h2 class="section-title">{{ tp.add }}</h2>
          <label class="label mt-4" for="tp-player">{{ tp.player }}</label>
          <input id="tp-player" v-model="addQuery" class="field" maxlength="36" required autocomplete="off" />
          <label class="label mt-3" for="tp-group">{{ tp.group }}</label>
          <select id="tp-group" v-model="addGroup" class="field">
            <option value="">{{ tp.groupMain }}</option>
            <option v-for="o in view.groups" :key="o.role.id" :value="o.role.id">{{ roleName(o.role) }}</option>
          </select>
          <p class="mt-1.5 text-xs text-base-400">{{ tp.addHint }}</p>
          <p v-if="addError" role="alert" class="mt-3 text-sm text-redstone-300">{{ addError }}</p>
          <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy || !addQuery.trim()"><SiteIcon name="plus" class="size-4" />{{ tp.add }}</button>
        </form>
      </aside>
    </div>

    <!-- Bearbeiten -->
    <AdminDialog v-if="draft" :title="fill(tp.editTitle, { name: draft.name })" size="lg" @close="draft = null">
      <form id="tp-edit" class="space-y-5" novalidate @submit.prevent="saveDraft">
        <div>
          <div class="flex flex-wrap items-center gap-3">
            <label class="label mb-0" :for="`tp-title-${draftLang}`">{{ tp.titleLabel }}</label>
            <div class="adm-seg ml-auto">
              <button v-for="l in LANGS" :key="l" type="button" :aria-pressed="draftLang === l" @click="draftLang = l">
                {{ l.toUpperCase() }}<span v-if="draft.titles[l].trim()" aria-hidden="true"> •</span>
              </button>
            </div>
          </div>
          <input :id="`tp-title-${draftLang}`" v-model="draft.titles[draftLang]" class="field mt-2" maxlength="60" :placeholder="draftLang !== 'en' ? draft.titles.en : ''" />
          <p class="mt-1.5 text-xs text-base-400">{{ tp.titleHint }}</p>
        </div>
        <div>
          <label class="label" for="tp-discord">{{ tp.discord }}</label>
          <input id="tp-discord" v-model="draft.discord" class="field" maxlength="32" autocomplete="off" spellcheck="false" :aria-invalid="discordInvalid" aria-describedby="tp-discord-hint" />
          <p id="tp-discord-hint" class="mt-1.5 text-xs" :class="discordInvalid ? 'text-redstone-300' : 'text-base-400'">{{ discordInvalid ? tp.errors.discord : tp.discordHint }}</p>
        </div>
        <div>
          <p class="label">{{ tp.links }}</p>
          <ul class="space-y-2">
            <li v-for="(l, i) in draft.links" :key="i" class="grid gap-2 sm:grid-cols-[10rem_minmax(0,1fr)_auto]">
              <input v-model="l.label" class="field" maxlength="30" :placeholder="tp.linkLabel" :aria-label="`${tp.linkLabel} ${i + 1}`" />
              <input v-model="l.url" class="field" type="url" maxlength="200" :placeholder="tp.linkUrl" :aria-label="`URL ${i + 1}`" :aria-invalid="linkInvalid(l)" spellcheck="false" />
              <button type="button" class="btn btn-ghost px-2.5" :aria-label="`${tp.removeLink} ${i + 1}`" @click="draft.links.splice(i, 1)"><SiteIcon name="trash" class="size-4" /></button>
            </li>
          </ul>
          <button v-if="draft.links.length < 3" type="button" class="btn btn-ghost mt-2 text-xs" @click="draft.links.push({ label: '', url: 'https://' })"><SiteIcon name="plus" class="size-3.5" />{{ tp.addLink }}</button>
          <p class="mt-1.5 text-xs text-base-400">{{ tp.linksHint }}</p>
        </div>
        <ul v-if="draftErrors.length" role="alert" class="space-y-1 rounded-lg border border-redstone-600 bg-redstone-900/50 px-3 py-2 text-sm text-base-50">
          <li v-for="(e, i) in draftErrors" :key="i">{{ e }}</li>
        </ul>
      </form>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="draft = null">{{ a.common.cancel }}</button>
        <button type="submit" form="tp-edit" class="btn btn-primary" :disabled="busy">{{ tp.save }}</button>
      </template>
    </AdminDialog>

    <AdminConfirm v-if="removing" :title="tp.remove" :text="fill(tp.confirmRemove, { name: removing.name })" danger :busy="busy" @cancel="removing = null" @confirm="remove" />
  </div>
</template>

<style scoped>
.tp-group {
  padding: 1rem;
  border-radius: 0.9rem;
  border: 1px solid var(--color-base-800);
  border-top: 3px solid var(--role);
  background: color-mix(in srgb, var(--role) 4%, var(--color-base-950));
  transition: border-color 0.15s, box-shadow 0.15s;
}
.tp-group.is-target {
  border-color: color-mix(in srgb, var(--role) 60%, var(--color-base-700));
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--role) 40%, transparent);
}
.tp-row.is-dragging {
  opacity: 0.4;
}
.tp-grip {
  display: grid;
  place-items: center;
  width: 1.5rem;
  height: 2.25rem;
  margin-left: -0.35rem;
  border-radius: 0.35rem;
  color: var(--color-base-600);
  cursor: grab;
}
.tp-grip:hover {
  color: var(--color-base-200);
  background: var(--color-base-800);
}
.tp-grip:active {
  cursor: grabbing;
}
.tp-line {
  height: 3px;
  margin: 0.1rem 0.5rem;
  border-radius: 999px;
  background: var(--role);
  box-shadow: 0 0 8px var(--role);
}
.tp-empty {
  padding: 1rem;
  border: 1px dashed var(--color-base-700);
  border-radius: 0.6rem;
  text-align: center;
  font-size: 0.8rem;
  color: var(--color-base-400);
}
.tp-empty.is-hot {
  border-color: var(--role);
  color: var(--color-base-50);
}
</style>
