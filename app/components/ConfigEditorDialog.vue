<script setup lang="ts">
import type { ConfigDoc, ConfigEntry, ConfigFormat, ConfigGroup, ConfigValue, TextFile } from '~/utils/config/types'
import { ConfigEditError, applyChanges, detectFormat, draftProblem, parseConfig, parseProblem, sameValue } from '~/utils/config/editor'
import { configEditorKey } from '~/utils/config/ui'

// Config-Editor im Dateibrowser. „Einfach“ = Formular aus den erkannten
// Werten, „Erweitert“ = ganzer Text. Gespeichert wird formattreu: im
// Einfach-Modus werden nur die Textstellen geänderter Werte ersetzt.
const props = defineProps<{ instanceId: string; path: string; running: boolean }>()
const emit = defineEmits<{ close: []; saved: [] }>()

const toasts = useToasts()
const name = computed(() => props.path.split('/').pop() ?? props.path)
const FORMAT_LABEL: Record<ConfigFormat, string> = {
  json: 'JSON',
  json5: 'JSON5',
  toml: 'TOML',
  properties: 'Properties',
  options: 'key:value',
  forgecfg: 'Forge cfg',
  yaml: 'YAML',
}

const loading = ref(true)
const loadError = ref<string | null>(null)
const disk = ref<TextFile | null>(null)
const format = ref<ConfigFormat>('json')
/** Text, aus dem das Formular stammt (kann vom Stand auf der Platte abweichen). */
const base = ref('')
const doc = shallowRef<ConfigDoc | null>(null)
const drafts = ref<Record<string, ConfigValue>>({})
const mode = ref<'simple' | 'advanced'>('simple')
const advText = ref('')
const openedAsText = ref(false)
const conflict = ref(false)
const saving = ref(false)
const confirmClose = ref(false)
const confirmInvalid = ref(false)
const query = ref('')
const editor = ref<{ goToLine(n: number): void } | null>(null)

async function load() {
  loading.value = true
  loadError.value = null
  conflict.value = false
  try {
    const file = await backend.readInstanceText(props.instanceId, props.path)
    disk.value = file
    format.value = detectFormat(name.value, file.text)
    useText(file.text)
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

/** Text als neue Grundlage übernehmen; lässt er sich nicht lesen → erweiterter Modus. */
function useText(text: string) {
  drafts.value = {}
  base.value = text
  advText.value = text
  try {
    doc.value = parseConfig(format.value, text)
    openedAsText.value = false
    closed.value = initialClosed(doc.value.root)
  } catch {
    doc.value = null
    openedAsText.value = true
    mode.value = 'advanced'
  }
}

// --- Einfach-Modus --------------------------------------------------------------
const byId = computed(() => new Map((doc.value?.entries ?? []).map((e) => [e.id, e])))
const changedIds = computed(() =>
  Object.entries(drafts.value)
    .filter(([id, v]) => {
      const entry = byId.value.get(id)
      return entry && !sameValue(entry.value, v)
    })
    .map(([id]) => id),
)
const changedSet = computed(() => new Set(changedIds.value))
const problems = computed(() => {
  const out = new Map<string, NonNullable<ReturnType<typeof draftProblem>>>()
  for (const id of changedIds.value) {
    const entry = byId.value.get(id)!
    const problem = draftProblem(entry, drafts.value[id]!)
    if (problem) out.set(id, problem)
  }
  return out
})

/** Text mit allen Änderungen des Formulars (oder Fehler). */
const simpleResult = computed<{ text: string; error: ConfigEditError | null }>(() => {
  if (!doc.value || problems.value.size) return { text: base.value, error: null }
  try {
    return { text: applyChanges(doc.value, base.value, drafts.value), error: null }
  } catch (e) {
    return { text: base.value, error: e instanceof ConfigEditError ? e : new ConfigEditError('', null) }
  }
})
const editErrorKey = computed(() => {
  const id = simpleResult.value.error?.entryId
  return (id && byId.value.get(id)?.key) || name.value
})

// Gruppen: oberste zwei Ebenen offen, tiefere zu.
const closed = ref(new Set<string>())
function initialClosed(root: ConfigGroup): Set<string> {
  const out = new Set<string>()
  const walk = (g: ConfigGroup, depth: number) => {
    for (const c of g.children) {
      if (c.type !== 'group') continue
      if (depth >= 2) out.add(c.group.id)
      walk(c.group, depth + 1)
    }
  }
  walk(root, 0)
  return out
}

// Suche: Schlüssel, Hilfetext, Gruppen und Werte.
const visibleIds = computed<Set<string> | null>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q || !doc.value) return null
  const out = new Set<string>()
  const walk = (g: ConfigGroup): boolean => {
    let any = false
    for (const c of g.children) {
      if (c.type === 'entry') {
        const e = c.entry
        const hay = `${e.key}\n${e.help ?? ''}\n${e.groups.join(' ')}\n${String(e.value)}`.toLowerCase()
        if (hay.includes(q)) {
          out.add(e.id)
          any = true
        }
      } else if (walk(c.group) || c.group.title.toLowerCase().includes(q)) {
        out.add(c.group.id)
        // Passt der Gruppenname, ist der ganze Inhalt sichtbar.
        if (c.group.title.toLowerCase().includes(q)) markAll(c.group, out)
        any = true
      }
    }
    return any
  }
  walk(doc.value.root)
  return out
})
function markAll(g: ConfigGroup, out: Set<string>) {
  for (const c of g.children) {
    if (c.type === 'entry') out.add(c.entry.id)
    else {
      out.add(c.group.id)
      markAll(c.group, out)
    }
  }
}
const rootChildren = computed(() => (doc.value?.root.children ?? []).filter((c) => !visibleIds.value || visibleIds.value.has(c.type === 'entry' ? c.entry.id : c.group.id)))

provide(configEditorKey, {
  value: (entry: ConfigEntry) => (entry.id in drafts.value ? drafts.value[entry.id]! : entry.value),
  set: (entry: ConfigEntry, value: ConfigValue) => {
    drafts.value = { ...drafts.value, [entry.id]: value }
  },
  reset: (entry: ConfigEntry) => {
    const next = { ...drafts.value }
    delete next[entry.id]
    drafts.value = next
  },
  changed: (entry: ConfigEntry) => changedSet.value.has(entry.id),
  problem: (entry: ConfigEntry) => problems.value.get(entry.id) ?? null,
  visible: (id: string) => !visibleIds.value || visibleIds.value.has(id),
  open: (id: string) => !!visibleIds.value || !closed.value.has(id),
  toggle: (id: string) => {
    const next = new Set(closed.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    closed.value = next
  },
  jump: (line: number) => {
    if (switchTo('advanced')) nextTick(() => editor.value?.goToLine(line))
  },
})

// --- Erweiterter Modus: Fehleranzeige leicht verzögert --------------------------
const advProblem = ref<ReturnType<typeof parseProblem>>(null)
let parseTimer: ReturnType<typeof setTimeout> | undefined
watch([advText, mode], () => {
  clearTimeout(parseTimer)
  if (mode.value !== 'advanced') return (advProblem.value = null)
  parseTimer = setTimeout(() => (advProblem.value = parseProblem(format.value, advText.value)), advText.value.length > 200_000 ? 600 : 150)
})
onBeforeUnmount(() => clearTimeout(parseTimer))

// --- Moduswechsel ---------------------------------------------------------------
const canSimple = computed(() => mode.value === 'simple' || !advProblem.value)
const canAdvanced = computed(() => mode.value === 'advanced' || (!problems.value.size && !simpleResult.value.error))

function switchTo(next: 'simple' | 'advanced'): boolean {
  if (next === mode.value) return true
  if (next === 'advanced') {
    if (!canAdvanced.value) return false
    advText.value = simpleResult.value.text
    mode.value = 'advanced'
    return true
  }
  const problem = parseProblem(format.value, advText.value)
  advProblem.value = problem
  if (problem) return false
  const keepClosed = closed.value
  useText(advText.value)
  closed.value = keepClosed
  mode.value = 'simple'
  return true
}

// --- Speichern ------------------------------------------------------------------
const currentText = computed(() => (mode.value === 'advanced' ? advText.value : simpleResult.value.text))
const dirty = computed(() => !!disk.value && (currentText.value !== disk.value.text || (mode.value === 'simple' && changedIds.value.length > 0)))
const blocked = computed(() => mode.value === 'simple' && (problems.value.size > 0 || !!simpleResult.value.error))

async function save(opts: { force?: boolean; allowInvalid?: boolean } = {}): Promise<boolean> {
  const file = disk.value
  if (!file || saving.value || blocked.value) return false
  const text = currentText.value
  if (mode.value === 'advanced' && !opts.allowInvalid) {
    const problem = parseProblem(format.value, text)
    if (problem) {
      advProblem.value = problem
      confirmInvalid.value = true
      return false
    }
  }
  saving.value = true
  try {
    const saved = await backend.writeInstanceText(props.instanceId, props.path, text, opts.force ? null : file.version, file.bom)
    disk.value = { ...file, text, version: saved.version, hasBackup: saved.hasBackup }
    conflict.value = false
    if (mode.value === 'simple') {
      const keepClosed = closed.value
      useText(text)
      closed.value = keepClosed
    }
    toasts.ok(t('configEditor.saved'))
    emit('saved')
    return true
  } catch (e) {
    if (e instanceof BackendError && e.code === 'files.changedOnDisk') conflict.value = true
    else toasts.error(e)
    return false
  } finally {
    saving.value = false
  }
}

async function saveAnyway() {
  confirmInvalid.value = false
  await save({ allowInvalid: true })
}

async function restoreBackup() {
  try {
    const backup = await backend.readInstanceTextBackup(props.instanceId, props.path)
    if (!backup) return
    if (mode.value === 'simple' && !parseProblem(format.value, backup.text)) useText(backup.text)
    else {
      mode.value = 'advanced'
      advText.value = backup.text
    }
    toasts.ok(t('configEditor.restored'))
  } catch (e) {
    toasts.error(e)
  }
}

function openExternally() {
  backend.openInstanceFile(props.instanceId, props.path).catch((e) => toasts.error(e))
}

// --- Schließen ------------------------------------------------------------------
function requestClose() {
  if (confirmClose.value || confirmInvalid.value) return
  if (dirty.value) confirmClose.value = true
  else emit('close')
}
async function saveAndClose() {
  confirmClose.value = false
  if (await save()) emit('close')
}

function onKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    if (dirty.value) save()
  }
}
onMounted(() => {
  load()
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

const resetAll = () => (drafts.value = {})
</script>

<template>
  <BaseDialog :title="t('configEditor.title', { name })" huge @close="requestClose">
    <div class="flex h-[min(70vh,52rem)] w-full flex-col gap-3">
      <!-- Kopf: Pfad, Format, Modus -->
      <div class="flex flex-wrap items-center gap-2">
        <span class="min-w-0 truncate font-mono text-xs text-base-400" :title="`/${path}`">/{{ path }}</span>
        <span v-if="!loading && !loadError" class="badge bg-base-800 text-base-400">{{ FORMAT_LABEL[format] }}</span>
        <div v-if="!loading && !loadError" class="ml-auto inline-flex rounded-md bg-base-900 p-0.5 text-xs ring-1 ring-base-800" role="group" :aria-label="t('configEditor.mode')">
          <button
            type="button"
            class="seg rounded disabled:cursor-not-allowed disabled:opacity-40"
            :class="{ 'seg-on': mode === 'simple' }"
            :aria-pressed="mode === 'simple'"
            :disabled="!canSimple"
            :title="!canSimple ? t('configEditor.simpleBlocked') : undefined"
            @click="switchTo('simple')"
          >
            {{ t('configEditor.simple') }}
          </button>
          <button
            type="button"
            class="seg rounded disabled:cursor-not-allowed disabled:opacity-40"
            :class="{ 'seg-on': mode === 'advanced' }"
            :aria-pressed="mode === 'advanced'"
            :disabled="!canAdvanced"
            :title="!canAdvanced ? t('configEditor.fieldsBlocked') : undefined"
            @click="switchTo('advanced')"
          >
            {{ t('configEditor.advanced') }}
          </button>
        </div>
      </div>

      <!-- Hinweise -->
      <p v-if="running" class="note border-lamp-400/40 bg-lamp-900/40 text-lamp-300">{{ t('configEditor.running') }}</p>
      <div v-if="conflict" role="alert" class="note flex flex-wrap items-center gap-2 border-redstone-600/50 bg-redstone-900/40 text-redstone-300">
        <span class="min-w-0 flex-1">{{ t('configEditor.conflict') }}</span>
        <button type="button" class="btn btn-ghost h-7 px-2.5 py-0 text-xs" @click="load">{{ t('configEditor.reload') }}</button>
        <button type="button" class="btn btn-danger h-7 px-2.5 py-0 text-xs" :disabled="saving" @click="save({ force: true, allowInvalid: true })">{{ t('configEditor.overwrite') }}</button>
      </div>
      <p v-if="openedAsText && mode === 'advanced'" class="note border-base-700 bg-base-900 text-base-400">{{ t('configEditor.simpleUnavailable') }}</p>

      <!-- Laden / Fehler -->
      <div v-if="loading" class="space-y-2">
        <div v-for="i in 6" :key="i" class="skeleton h-9" />
      </div>
      <div v-else-if="loadError" class="flex flex-1 flex-col items-center justify-center gap-3 text-center">
        <p class="text-sm text-redstone-300">{{ loadError }}</p>
        <button type="button" class="btn btn-ghost" @click="openExternally">{{ t('files.openExternally') }}</button>
      </div>

      <!-- Einfach -->
      <template v-else-if="mode === 'simple' && doc">
        <div class="flex flex-wrap items-center gap-2">
          <div class="relative min-w-48 flex-1">
            <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-base-400" fill="none" stroke="currentColor" stroke-width="2"><path :d="icons.search" /></svg>
            <input v-model="query" class="field h-8 py-0 pl-8 text-xs" maxlength="100" :placeholder="t('configEditor.search')" :aria-label="t('configEditor.search')" spellcheck="false" @keydown.esc.stop="query = ''" />
          </div>
          <template v-if="changedIds.length">
            <span class="badge bg-lamp-900 text-lamp-300">{{ t('configEditor.changed', changedIds.length) }}</span>
            <button type="button" class="text-xs text-base-400 hover:text-base-50" @click="resetAll">{{ t('configEditor.resetAll') }}</button>
          </template>
        </div>
        <p v-if="simpleResult.error" role="alert" class="note border-redstone-600/50 bg-redstone-900/40 text-redstone-300">{{ t('configEditor.editError', { key: editErrorKey }) }}</p>
        <div class="min-h-0 flex-1 overflow-y-auto rounded-lg border border-base-800 bg-base-900">
          <template v-for="child in rootChildren" :key="child.type === 'entry' ? child.entry.id : child.group.id">
            <ConfigField v-if="child.type === 'entry'" :entry="child.entry" />
            <ConfigGroup v-else :group="child.group" :depth="0" />
          </template>
          <p v-if="!doc.entries.length" class="px-4 py-10 text-center text-sm text-base-400">{{ t('configEditor.empty') }}</p>
          <p v-else-if="!rootChildren.length" class="px-4 py-10 text-center text-sm text-base-400">{{ t('configEditor.noMatches') }}</p>
        </div>
      </template>

      <!-- Erweitert -->
      <template v-else>
        <ConfigTextEditor ref="editor" v-model="advText" :format="format" :error-line="advProblem?.line ?? null" :label="t('configEditor.title', { name })" />
        <div class="flex min-h-5 items-center gap-2 text-xs">
          <template v-if="advProblem">
            <span role="alert" class="min-w-0 flex-1 truncate text-redstone-300">
              {{ t('configEditor.parseError', { line: advProblem.line, message: t(`configEditor.parse.${advProblem.code}`) }) }}
            </span>
            <button type="button" class="shrink-0 text-redstone-300 underline-offset-2 hover:underline" @click="editor?.goToLine(advProblem.line)">{{ t('configEditor.goToLine') }}</button>
          </template>
          <span v-else class="flex-1 text-base-600">{{ t('configEditor.lines', advText.split('\n').length) }}</span>
        </div>
      </template>
    </div>

    <template #actions>
      <button v-if="disk?.hasBackup" type="button" class="btn btn-ghost mr-auto" :title="t('configEditor.restoreHint')" @click="restoreBackup">{{ t('configEditor.restore') }}</button>
      <button type="button" class="btn btn-ghost" @click="openExternally">{{ t('files.openExternally') }}</button>
      <button type="button" class="btn btn-ghost" @click="requestClose">{{ t('common.actions.close') }}</button>
      <button type="button" class="btn btn-primary" :disabled="!dirty || saving || blocked" @click="save()">{{ t('common.actions.save') }}</button>
    </template>
  </BaseDialog>

  <BaseDialog v-if="confirmClose" :title="t('configEditor.unsavedTitle')" @close="confirmClose = false">
    <p class="text-sm text-base-200">{{ t('configEditor.unsavedText', { name }) }}</p>
    <template #actions>
      <button type="button" class="btn btn-ghost" @click="confirmClose = false">{{ t('common.actions.cancel') }}</button>
      <button type="button" class="btn btn-danger" @click="emit('close')">{{ t('configEditor.discard') }}</button>
      <button type="button" class="btn btn-primary" :disabled="blocked || saving" @click="saveAndClose">{{ t('configEditor.saveAndClose') }}</button>
    </template>
  </BaseDialog>

  <BaseDialog v-if="confirmInvalid" :title="t('configEditor.invalidTitle')" @close="confirmInvalid = false">
    <p class="text-sm text-base-200">{{ t('configEditor.invalidText', { line: advProblem?.line ?? 1 }) }}</p>
    <template #actions>
      <button type="button" class="btn btn-ghost" @click="confirmInvalid = false">{{ t('common.actions.cancel') }}</button>
      <button type="button" class="btn btn-danger" @click="saveAnyway">{{ t('configEditor.saveAnyway') }}</button>
    </template>
  </BaseDialog>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.note {
  @apply rounded-md border px-3 py-2 text-xs leading-relaxed;
}
</style>
