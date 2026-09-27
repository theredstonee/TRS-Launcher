<script setup lang="ts">
// Stellen (§23.3): Liste + Editor mit Texten je Sprache (EN/DE/ES) und Formular-Baukasten (Kurztext, Langtext,
// Einfach-/Mehrfachauswahl, Ja/Nein, Zahl; Pflicht, Grenzen, Hilfetext, Reihenfolge). Speichern prüft der Server.
import type { Lang } from '~/utils/messages'

const { a, fill } = useAdminText()
const { t, lang } = useTeamText()
const { api, can } = useAdmin()

const LANG_LIST: Lang[] = ['en', 'de', 'es']
const TYPES: FieldType[] = ['short', 'long', 'single', 'multi', 'yesno', 'number']

interface RoleOpt { id: string, name: string | null, color: string, builtin: boolean, rank: number }
const jobs = ref<AdminJob[]>([])
const roles = ref<RoleOpt[]>([])
const loading = ref(true)
const error = ref('')
const busy = ref(false)
const manage = computed(() => can('applications.manage'))

async function load() {
  error.value = ''
  try {
    const r = await api<{ jobs: AdminJob[], roles: RoleOpt[] }>('/v1/admin/jobs')
    jobs.value = r.jobs
    roles.value = r.roles
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(load)

// --- Editor ------------------------------------------------------------------------------------------
type L = Record<Lang, string>
const emptyL = (): L => ({ en: '', de: '', es: '' })
interface OptionDraft { key: number, id: string, label: L }
interface FieldDraft { key: number, id: string, type: FieldType, required: boolean, label: L, help: L, min: number | null, max: number | null, options: OptionDraft[] }
interface JobDraft {
  id: string
  isNew: boolean
  status: 'draft' | 'open' | 'closed'
  roleId: string
  cooldownDays: number
  sort: number
  texts: Record<Lang, { title: string, summary: string, description: string, tasks: string, requirements: string }>
  form: FieldDraft[]
}
let seq = 1
const draft = ref<JobDraft | null>(null)
const editLang = ref<Lang>('en')
const draftError = ref('')
const deleting = ref<AdminJob | null>(null)

const fromL = (x?: Localized): L => ({ en: x?.en ?? '', de: x?.de ?? '', es: x?.es ?? '' })
function toL(x: L): Localized | undefined {
  const out: Localized = {}
  for (const l of LANG_LIST) if (x[l].trim()) out[l] = x[l].trim()
  return Object.keys(out).length ? out : undefined
}

function edit(j?: AdminJob) {
  draftError.value = ''
  editLang.value = lang.value
  const texts = {} as JobDraft['texts']
  for (const l of LANG_LIST) {
    const x = j?.texts[l]
    texts[l] = { title: x?.title ?? '', summary: x?.summary ?? '', description: x?.description ?? '', tasks: (x?.tasks ?? []).join('\n'), requirements: (x?.requirements ?? []).join('\n') }
  }
  draft.value = {
    id: j?.id ?? '',
    isNew: !j,
    status: j?.status ?? 'draft',
    roleId: j?.role?.id ?? '',
    cooldownDays: j?.cooldownDays ?? 30,
    sort: 0,
    texts,
    form: (j?.form ?? []).map((f) => ({
      key: seq++,
      id: f.id,
      type: f.type,
      required: f.required,
      label: fromL(f.label),
      help: fromL(f.help),
      min: f.min ?? null,
      max: f.max ?? null,
      options: (f.options ?? []).map((o) => ({ key: seq++, id: o.id, label: fromL(o.label) })),
    })),
  }
}

function slugId(s: string, taken: Set<string>, fallback: string): string {
  let base = s.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/ß/g, 'ss').replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 24)
  if (!/^[a-z]/.test(base)) base = `${fallback}${base ? `_${base}` : ''}`.slice(0, 28)
  if (['discord', 'agegroup', 'age_group', 'website', 'name', 'uuid'].includes(base)) base = `${base}_q`
  let id = base
  let n = 2
  while (taken.has(id)) id = `${base.slice(0, 28)}_${n++}`
  taken.add(id)
  return id
}

function addField(type: FieldType = 'short') {
  const d = draft.value
  if (!d) return
  d.form.push({
    key: seq++,
    id: '',
    type,
    required: false,
    label: emptyL(),
    help: emptyL(),
    min: null,
    max: type === 'short' ? 100 : type === 'long' ? 1000 : null,
    options: type === 'single' || type === 'multi' ? [{ key: seq++, id: 'o1', label: emptyL() }, { key: seq++, id: 'o2', label: emptyL() }] : [],
  })
}
function move(i: number, dir: -1 | 1) {
  const f = draft.value?.form
  if (!f || i + dir < 0 || i + dir >= f.length) return
  const [x] = f.splice(i, 1)
  f.splice(i + dir, 0, x!)
}
function setType(fd: FieldDraft, type: FieldType) {
  fd.type = type
  if ((type === 'single' || type === 'multi') && fd.options.length < 2) {
    fd.options = [{ key: seq++, id: 'o1', label: emptyL() }, { key: seq++, id: 'o2', label: emptyL() }]
  }
  if (type === 'short') fd.max = Math.min(fd.max ?? 100, 200)
  if (type === 'long') fd.max = Math.min(fd.max ?? 1000, 4000)
}
function addOption(fd: FieldDraft) {
  const ids = new Set(fd.options.map((o) => o.id))
  let n = fd.options.length + 1
  while (ids.has(`o${n}`)) n++
  fd.options.push({ key: seq++, id: `o${n}`, label: emptyL() })
}

function body(d: JobDraft) {
  const texts: JobTexts = {}
  for (const l of LANG_LIST) {
    const x = d.texts[l]
    if (!x.title.trim()) continue
    const lines = (s: string) => s.split('\n').map((v) => v.trim()).filter(Boolean).slice(0, 20)
    texts[l] = { title: x.title.trim(), summary: x.summary.trim(), description: x.description.trim(), tasks: lines(x.tasks), requirements: lines(x.requirements) }
  }
  const taken = new Set(d.form.map((f) => f.id).filter(Boolean))
  const form: FormField[] = d.form.map((f, i) => {
    const id = f.id || slugId(f.label.en || f.label.de || f.label.es, taken, `q${i + 1}`)
    f.id = id
    const out: FormField = { id, type: f.type, required: f.required, label: toL(f.label) ?? {} }
    const help = toL(f.help)
    if (help) out.help = help
    if (f.type !== 'yesno' && f.type !== 'single') {
      if (f.min !== null && f.min !== undefined && String(f.min) !== '') out.min = Number(f.min)
      if (f.max !== null && f.max !== undefined && String(f.max) !== '') out.max = Number(f.max)
    }
    if (f.type === 'single' || f.type === 'multi') out.options = f.options.map((o) => ({ id: o.id, label: toL(o.label) ?? {} }))
    return out
  })
  return {
    ...(d.isNew && d.id.trim() ? { id: d.id.trim() } : {}),
    status: d.status,
    roleId: d.roleId || null,
    cooldownDays: Number(d.cooldownDays) || 0,
    sort: d.sort,
    texts,
    form,
  }
}

async function save() {
  const d = draft.value
  if (!d) return
  busy.value = true
  draftError.value = ''
  try {
    if (d.isNew) await api('/v1/admin/jobs', { method: 'POST', body: body(d) })
    else await api(`/v1/admin/jobs/${d.id}`, { method: 'PUT', body: body(d) })
    draft.value = null
    await load()
  } catch (e) {
    const fields = (e as { data?: { error?: { fields?: { path: string, message: string }[] } } }).data?.error?.fields
    draftError.value = fill(a.value.common.failed, { error: fields?.length ? fields.map((x) => `${x.path}: ${x.message}`).join(' · ') : apiMessage(e) })
  } finally {
    busy.value = false
  }
}
async function remove() {
  if (!deleting.value) return
  busy.value = true
  try {
    await api(`/v1/admin/jobs/${deleting.value.id}`, { method: 'DELETE' })
    deleting.value = null
    await load()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
    deleting.value = null
  } finally {
    busy.value = false
  }
}
const roleOf = (id: string) => roles.value.find((r) => r.id === id)
const statusTone = (s: string) => (s === 'open' ? 'tone-ok' : s === 'closed' ? 'tone-muted' : 'tone-warn')
const minMaxLabel = (f: FieldDraft) => (f.type === 'number' ? t.value.adm.jobs.minMaxNumber : f.type === 'multi' ? t.value.adm.jobs.minMaxMulti : t.value.adm.jobs.minMaxText)
</script>

<template>
  <div class="adm-page">
    <header class="flex flex-wrap items-end gap-3">
      <div class="min-w-0 flex-1">
        <h1 class="adm-title">{{ t.adm.jobs.title }}</h1>
        <p class="adm-lead">{{ t.adm.jobs.lead }}</p>
      </div>
      <button v-if="manage" type="button" class="btn btn-primary" @click="edit()"><SiteIcon name="plus" class="size-4" />{{ t.adm.jobs.new }}</button>
    </header>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <div v-if="loading" class="skeleton mt-6 h-40 rounded-xl" />
    <div v-else-if="!jobs.length" class="adm-empty mt-6"><SiteIcon name="briefcase" class="size-6" />{{ t.adm.jobs.empty }}</div>
    <ul v-else class="mt-6 space-y-2">
      <li v-for="j in jobs" :key="j.id">
        <article class="adm-row items-center">
          <SiteIcon name="briefcase" class="size-5 shrink-0 text-base-400" />
          <div class="min-w-0 flex-1">
            <p class="flex flex-wrap items-center gap-2">
              <span class="font-semibold text-base-50">{{ inLang(j.texts, lang)?.title ?? j.id }}</span>
              <span class="tone" :class="statusTone(j.status)">{{ t.adm.jobs.status[j.status] }}</span>
              <RoleBadge v-if="j.role" :role="j.role" small />
              <span v-for="l in LANG_LIST" :key="l" class="font-mono text-[10px] uppercase" :class="j.texts[l] ? 'text-base-300' : 'text-base-600 line-through'">{{ l }}</span>
            </p>
            <p class="mt-0.5 flex flex-wrap gap-x-3 text-xs text-base-400">
              <span class="font-mono">/team/{{ j.id }}</span>
              <NuxtLink :to="`/admin/applications?job=${j.id}`" class="hover:underline">{{ fill(t.adm.jobs.applications, { open: j.applications.open, total: j.applications.total }) }}</NuxtLink>
              <span>{{ fill(t.adm.jobs.questions, { n: j.form.length }) }}</span>
            </p>
          </div>
          <div class="flex flex-wrap gap-1.5">
            <a v-if="j.status !== 'draft'" :href="`/team/${j.id}`" target="_blank" rel="noopener" class="btn btn-ghost px-2.5 py-1 text-xs"><SiteIcon name="external" class="size-3.5" />{{ t.adm.jobs.publicPage }}</a>
            <button v-if="manage" type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="edit(j)">{{ t.adm.jobs.edit }}</button>
            <button v-if="manage && !j.applications.total" type="button" class="btn btn-danger px-2.5 py-1 text-xs" @click="deleting = j">{{ t.adm.jobs.delete }}</button>
          </div>
        </article>
      </li>
    </ul>

    <AdminDialog v-if="draft" :title="draft.isNew ? t.adm.jobs.new : (draft.texts[editLang].title || draft.id)" size="xl" @close="draft = null">
      <div class="grid gap-4 md:grid-cols-4">
        <div>
          <label class="label" for="job-status">{{ t.adm.jobs.statusLabel }}</label>
          <select id="job-status" v-model="draft.status" class="field">
            <option v-for="s in (['draft', 'open', 'closed'] as const)" :key="s" :value="s">{{ t.adm.jobs.status[s] }}</option>
          </select>
        </div>
        <div>
          <label class="label" for="job-role">{{ t.adm.jobs.role }}</label>
          <select id="job-role" v-model="draft.roleId" class="field">
            <option value="">{{ t.adm.jobs.noRole }}</option>
            <option v-for="r in roles" :key="r.id" :value="r.id">{{ r.name ?? t.adm.roleNames[r.id] ?? r.id }} (#{{ r.rank }})</option>
          </select>
        </div>
        <div>
          <label class="label" for="job-cool">{{ t.adm.jobs.cooldown }}</label>
          <input id="job-cool" v-model.number="draft.cooldownDays" type="number" min="0" max="365" class="field" />
        </div>
        <div>
          <label class="label" for="job-id">{{ t.adm.jobs.id }}</label>
          <input id="job-id" v-model="draft.id" class="field font-mono text-sm" maxlength="48" :disabled="!draft.isNew" placeholder="moderator" />
        </div>
      </div>
      <p class="mt-1 text-xs text-base-400">{{ t.adm.jobs.idHint }}</p>

      <div class="mt-5 flex flex-wrap items-center gap-2">
        <span class="label mb-0">{{ t.adm.jobs.lang }}</span>
        <div class="adm-seg">
          <button v-for="l in LANG_LIST" :key="l" type="button" :aria-pressed="editLang === l" @click="editLang = l">
            <span class="font-mono uppercase">{{ l }}</span>
            <span v-if="!draft.texts[l].title.trim()" class="ml-1 text-lamp-300">•</span>
          </button>
        </div>
        <p v-if="!draft.texts[editLang].title.trim()" class="text-xs text-lamp-300">{{ t.adm.jobs.missingLang }}</p>
      </div>
      <div class="mt-3 grid gap-3 md:grid-cols-2">
        <div>
          <label class="label" for="job-title">{{ t.adm.jobs.titleField }}</label>
          <input id="job-title" v-model="draft.texts[editLang].title" class="field" maxlength="80" />
          <label class="label mt-3" for="job-summary">{{ t.adm.jobs.summary }}</label>
          <input id="job-summary" v-model="draft.texts[editLang].summary" class="field" maxlength="240" />
          <label class="label mt-3" for="job-desc">{{ t.adm.jobs.description }}</label>
          <textarea id="job-desc" v-model="draft.texts[editLang].description" class="field min-h-32" maxlength="4000" />
        </div>
        <div>
          <label class="label" for="job-tasks">{{ t.adm.jobs.tasks }}</label>
          <textarea id="job-tasks" v-model="draft.texts[editLang].tasks" class="field min-h-28" />
          <label class="label mt-3" for="job-req">{{ t.adm.jobs.requirements }}</label>
          <textarea id="job-req" v-model="draft.texts[editLang].requirements" class="field min-h-28" />
        </div>
      </div>

      <h3 class="section-title mt-6">{{ t.adm.jobs.form }}</h3>
      <p class="mt-1 text-xs text-base-400">{{ t.adm.jobs.standard }}</p>
      <ol class="mt-3 space-y-3">
        <li v-for="(f, i) in draft.form" :key="f.key" class="field-card rounded-lg border border-base-800 bg-base-950 p-3">
          <div class="flex flex-wrap items-center gap-2">
            <span class="font-mono text-xs text-base-400">{{ Number(i) + 1 }}.</span>
            <select :value="f.type" class="field adm-select w-auto py-1 text-sm" :aria-label="t.adm.jobs.type" @change="setType(f, ($event.target as HTMLSelectElement).value as FieldType)">
              <option v-for="ty in TYPES" :key="ty" :value="ty">{{ t.adm.jobs.types[ty] }}</option>
            </select>
            <label class="flex items-center gap-1.5 text-sm text-base-200"><input v-model="f.required" type="checkbox" class="adm-check mt-0" />{{ t.adm.jobs.required }}</label>
            <span class="ml-auto flex gap-1">
              <button type="button" class="btn-icon size-8" :aria-label="t.adm.jobs.up" :disabled="i === 0" @click="move(i, -1)"><SiteIcon name="chevron" class="size-4 rotate-180" /></button>
              <button type="button" class="btn-icon size-8" :aria-label="t.adm.jobs.down" :disabled="i === draft.form.length - 1" @click="move(i, 1)"><SiteIcon name="chevron" class="size-4" /></button>
              <button type="button" class="btn-icon size-8 text-redstone-300" :aria-label="t.adm.jobs.remove" @click="draft.form.splice(i, 1)"><SiteIcon name="trash" class="size-4" /></button>
            </span>
          </div>
          <div class="mt-2 grid gap-2 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_9rem]">
            <input v-model="f.label[editLang]" class="field" maxlength="120" :placeholder="`${t.adm.jobs.label} (${editLang.toUpperCase()})`" :aria-label="t.adm.jobs.label" />
            <input v-model="f.help[editLang]" class="field" maxlength="300" :placeholder="`${t.adm.jobs.help} (${editLang.toUpperCase()})`" :aria-label="t.adm.jobs.help" />
            <input v-model="f.id" class="field font-mono text-xs" maxlength="32" :placeholder="t.adm.jobs.fieldId" :aria-label="t.adm.jobs.fieldId" />
          </div>
          <div v-if="f.type !== 'yesno' && f.type !== 'single'" class="mt-2 flex flex-wrap items-center gap-2 text-sm text-base-300">
            <span>{{ minMaxLabel(f) }}</span>
            <input v-model.number="f.min" type="number" class="field w-24 py-1" :aria-label="t.adm.jobs.min" :placeholder="t.adm.jobs.min" />
            <input v-model.number="f.max" type="number" class="field w-24 py-1" :aria-label="t.adm.jobs.max" :placeholder="t.adm.jobs.max" />
          </div>
          <div v-if="f.type === 'single' || f.type === 'multi'" class="mt-2 space-y-1.5">
            <p class="text-xs text-base-400">{{ t.adm.jobs.options }}</p>
            <div v-for="(o, oi) in f.options" :key="o.key" class="flex items-center gap-2">
              <span class="w-8 font-mono text-[10px] text-base-500">{{ o.id }}</span>
              <input v-model="o.label[editLang]" class="field py-1" maxlength="120" :aria-label="t.adm.jobs.options" />
              <button type="button" class="btn-icon size-8" :aria-label="t.adm.jobs.remove" :disabled="f.options.length <= 2" @click="f.options.splice(oi, 1)"><SiteIcon name="close" class="size-3.5" /></button>
            </div>
            <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="f.options.length >= 20" @click="addOption(f)"><SiteIcon name="plus" class="size-3.5" />{{ t.adm.jobs.addOption }}</button>
          </div>
        </li>
      </ol>
      <div class="mt-3 flex flex-wrap gap-1.5">
        <button v-for="ty in TYPES" :key="ty" type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="draft.form.length >= 30" @click="addField(ty)"><SiteIcon name="plus" class="size-3.5" />{{ t.adm.jobs.types[ty] }}</button>
      </div>
      <p v-if="draftError" role="alert" class="mt-3 text-sm text-redstone-300">{{ draftError }}</p>
      <template #footer>
        <span v-if="draft.roleId && roleOf(draft.roleId)" class="mr-auto"><RoleBadge :role="roleOf(draft.roleId)!" /></span>
        <button type="button" class="btn btn-ghost" @click="draft = null">{{ a.common.cancel }}</button>
        <button type="button" class="btn btn-primary" :disabled="busy || !LANG_LIST.some((l: Lang) => draft!.texts[l].title.trim())" @click="save">{{ t.adm.jobs.save }}</button>
      </template>
    </AdminDialog>

    <AdminConfirm v-if="deleting" :title="t.adm.jobs.delete" :text="fill(t.adm.jobs.confirmDelete, { title: inLang(deleting.texts, lang)?.title ?? deleting.id })" danger :busy="busy" @cancel="deleting = null" @confirm="remove" />
  </div>
</template>

<style scoped>
.field-card {
  border-left: 3px solid var(--color-redstone-600);
}
</style>
