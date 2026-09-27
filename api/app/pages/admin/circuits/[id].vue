<script setup lang="ts">
// Schaltung bearbeiten bzw. anlegen (`/admin/circuits/new`, optional mit Import aus der Liste). Speichern erhöht rev,
// Status-Knöpfe veröffentlichen/verstecken, Export als .nbt/JSON (auch ungespeichert), Datei-Import ersetzt die Blöcke.
import type { CircuitData } from '#shared/circuits'
import { circuitText } from '~/utils/circuit-i18n'
import type { AdminCircuitDetail, ImportResult } from '~/utils/circuits/types'
import { blankCircuit, circuitErrorDetail, downloadPost, uploadCircuitFile } from '~/utils/circuits/types'

const { a, when } = useAdminText()
const { c, lang, fill } = useCircuitText()
const { api, session } = useAdmin()
const route = useRoute()
const router = useRouter()

const id = computed(() => String(route.params.id))
const isNew = computed(() => id.value === 'new')
const detail = ref<AdminCircuitDetail | null>(null)
const history = ref<{ at: string, actorName: string | null, actor: string, action: string, detail: string | null }[]>([])
const initial = shallowRef<CircuitData | null>(null)
const editor = shallowRef<{ current: CircuitData | string, check: { ok: boolean }, dirty: boolean, load: (d: CircuitData) => void, replaceBlocks: (d: CircuitData) => void, markClean: () => void } | null>(null)
const dirty = ref(false)
const error = ref('')
const notice = ref('')
const busy = ref(false)
const stale = ref(false)
const deleting = ref(false)

const imported = useState<ImportResult | null>('circuit-import', () => null)

async function load() {
  error.value = ''
  stale.value = false
  if (isNew.value) {
    const imp = route.query.import ? imported.value : null
    initial.value = imp ? imp.circuit : blankCircuit()
    if (imp) notice.value = fill(c.value.adm.imported, { format: imp.format, n: imp.blockCount })
    imported.value = null
    return
  }
  try {
    const r = await api<{ circuit: AdminCircuitDetail, history: typeof history.value }>(`/v1/admin/circuits/${id.value}`)
    detail.value = r.circuit
    history.value = r.history
    // Server-Felder (rev, updatedAt, author) gehören nicht in den Editor.
    const data: Record<string, unknown> = { ...r.circuit.circuit }
    delete data.rev
    delete data.updatedAt
    delete data.author
    initial.value = data as unknown as CircuitData
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  }
}
onMounted(load)
watch(id, load)

const title = computed(() => {
  if (isNew.value) return c.value.adm.newTitle
  return detail.value ? (circuitText(detail.value.circuit.texts, lang.value, 'name') || detail.value.id) : c.value.adm.editTitle
})

function currentCircuit(): CircuitData | null {
  const cur = editor.value?.current
  return cur && typeof cur !== 'string' ? cur : null
}

async function save(status?: 'draft' | 'published' | 'hidden') {
  const circuit = currentCircuit()
  if (!circuit || !editor.value?.check.ok) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    if (isNew.value) {
      const r = await api<{ circuit: AdminCircuitDetail }>('/v1/admin/circuits', { method: 'POST', body: { circuit, status: status ?? 'draft' } })
      editor.value.markClean()
      await router.replace(`/admin/circuits/${r.circuit.id}`)
      notice.value = fill(c.value.adm.saved, { rev: r.circuit.rev })
      return
    }
    const r = await api<{ circuit: AdminCircuitDetail }>(`/v1/admin/circuits/${id.value}`, {
      method: 'PUT',
      body: { circuit, ...(status ? { status } : {}), baseRev: detail.value?.rev },
    })
    detail.value = r.circuit
    editor.value.markClean()
    notice.value = fill(c.value.adm.saved, { rev: r.circuit.rev })
    await refreshHistory()
  } catch (e) {
    if (apiCode(e) === 'stale') stale.value = true
    else error.value = fill(a.value.common.failed, { error: circuitErrorDetail(e) || apiMessage(e) })
  } finally {
    busy.value = false
  }
}

async function setStatus(status: 'draft' | 'published' | 'hidden') {
  if (!detail.value) return
  // Ungespeicherte Änderungen gehen mit dem Status zusammen raus.
  if (dirty.value) return save(status)
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const r = await api<{ circuit: AdminCircuitDetail }>(`/v1/admin/circuits/${id.value}/status`, { method: 'POST', body: { status, baseRev: detail.value.rev } })
    detail.value = r.circuit
    notice.value = fill(c.value.adm.saved, { rev: r.circuit.rev })
    await refreshHistory()
  } catch (e) {
    if (apiCode(e) === 'stale') stale.value = true
    else error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}

async function refreshHistory() {
  try {
    history.value = (await api<{ history: typeof history.value }>(`/v1/admin/circuits/${id.value}`)).history
  } catch {
    // nur Beiwerk
  }
}

async function remove() {
  busy.value = true
  try {
    await api(`/v1/admin/circuits/${id.value}`, { method: 'DELETE' })
    await router.push('/admin/circuits')
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
    deleting.value = false
  }
}

async function exportFile(format: 'nbt' | 'json') {
  const circuit = currentCircuit()
  if (!circuit) return
  error.value = ''
  try {
    await downloadPost(`/v1/admin/circuits/export?format=${format}`, { circuit }, session.value?.csrf, `${circuit.id}.${format}`)
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: circuitErrorDetail(e) || apiMessage(e) })
  }
}

const fileInput = shallowRef<HTMLInputElement | null>(null)
async function importInto(file: File | undefined) {
  if (!file || !editor.value) return
  error.value = ''
  try {
    const r = await uploadCircuitFile('/v1/admin/circuits/import', file, session.value?.csrf)
    editor.value.replaceBlocks(r.circuit)
    notice.value = fill(c.value.adm.imported, { format: r.format, n: r.blockCount })
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: circuitErrorDetail(e) || apiMessage(e) })
  } finally {
    if (fileInput.value) fileInput.value.value = ''
  }
}

const tone = (s: string) => (s === 'published' ? 'tone-ok' : s === 'hidden' ? 'tone-danger' : 'tone-warn')
const valid = computed(() => !!editor.value?.check.ok)

// Warnung beim Verlassen mit ungespeicherten Änderungen.
function beforeUnload(e: BeforeUnloadEvent) {
  if (dirty.value) e.preventDefault()
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<template>
  <div class="adm-page">
    <NuxtLink to="/admin/circuits" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50"><SiteIcon name="back" class="size-4" />{{ c.adm.back }}</NuxtLink>
    <header class="mt-3 flex flex-wrap items-start justify-between gap-4">
      <div class="min-w-0">
        <h1 class="adm-title truncate">{{ title }}</h1>
        <p v-if="detail" class="mt-1 flex flex-wrap items-center gap-2 text-xs text-base-400">
          <span class="tone" :class="tone(detail.status)">{{ c.adm.status[detail.status] }}</span>
          <span class="adm-mono">{{ detail.id }}</span>
          <span>{{ fill(c.adm.rev, { rev: detail.rev }) }}</span>
          <span>{{ c.adm.source[detail.source] }}<template v-if="detail.author"> · {{ fill(c.common.by, { name: detail.author.name }) }}</template></span>
          <a v-if="detail.status === 'published'" :href="`/circuits/${detail.id}`" target="_blank" rel="noopener" class="inline-flex items-center gap-1 hover:text-base-50"><SiteIcon name="external" class="size-3.5" />/circuits/{{ detail.id }}</a>
        </p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <span v-if="dirty" class="tone tone-warn">{{ c.adm.unsaved }}</span>
        <label class="btn btn-ghost cursor-pointer text-sm">
          <SiteIcon name="import" class="size-4" />{{ c.adm.importReplace }}
          <input ref="fileInput" type="file" class="sr-only" accept=".litematic,.schem,.nbt,.json" @change="importInto(($event.target as HTMLInputElement).files?.[0])" />
        </label>
        <button type="button" class="btn btn-ghost text-sm" :disabled="!valid" @click="exportFile('nbt')"><SiteIcon name="download" class="size-4" />{{ c.adm.exportNbt }}</button>
        <button type="button" class="btn btn-ghost text-sm" :disabled="!valid" @click="exportFile('json')">{{ c.adm.exportJson }}</button>
      </div>
    </header>

    <div class="adm-toolbar mt-4">
      <button type="button" class="btn btn-primary" :disabled="busy || !valid || (!dirty && !isNew)" @click="save()"><SiteIcon name="check" class="size-4" />{{ c.adm.save }}</button>
      <template v-if="detail">
        <button v-if="detail.status !== 'published'" type="button" class="btn btn-ghost" :disabled="busy || !valid" @click="setStatus('published')">{{ c.adm.publish }}</button>
        <button v-if="detail.status === 'published'" type="button" class="btn btn-ghost" :disabled="busy" @click="setStatus('hidden')">{{ c.adm.hide }}</button>
        <button v-if="detail.status !== 'draft'" type="button" class="btn btn-ghost" :disabled="busy" @click="setStatus('draft')">{{ c.adm.toDraft }}</button>
        <span class="flex-1" />
        <button type="button" class="btn btn-danger" :disabled="busy" @click="deleting = true"><SiteIcon name="trash" class="size-4" />{{ c.adm.delete }}</button>
      </template>
      <button v-else type="button" class="btn btn-ghost" :disabled="busy || !valid" @click="save('published')">{{ c.adm.publish }}</button>
    </div>

    <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>
    <p v-if="notice" role="status" class="mt-3 text-sm text-ok">{{ notice }}</p>
    <p v-if="stale" role="alert" class="mt-3 flex items-center gap-3 text-sm text-lamp-300">
      {{ c.adm.stale }} <button type="button" class="btn btn-ghost text-xs" @click="load">{{ c.adm.reload }}</button>
    </p>

    <div v-if="!initial && !error" class="skeleton mt-6 h-96 rounded-xl" />
    <CircuitEditor v-if="initial" ref="editor" class="mt-5" :initial="initial" :id-editable="isNew" @dirty="dirty = $event" />

    <section v-if="detail" class="card mt-6 p-4">
      <h2 class="section-title">{{ c.adm.history }}</h2>
      <p v-if="!history.length" class="mt-2 text-sm text-base-400">{{ c.adm.noHistory }}</p>
      <ul v-else class="mt-2 space-y-1 text-xs text-base-300">
        <li v-for="(h, i) in history" :key="i"><span class="text-base-400">{{ when(h.at) }}</span> · {{ h.actorName ?? h.actor }} · <span class="adm-mono">{{ h.action }}</span><template v-if="h.detail"> · {{ h.detail }}</template></li>
      </ul>
    </section>

    <AdminConfirm
      v-if="deleting"
      :title="c.adm.delete"
      :text="fill(c.adm.confirmDelete, { name: title })"
      danger
      :busy="busy"
      @cancel="deleting = false"
      @confirm="remove"
    />
  </div>
</template>
