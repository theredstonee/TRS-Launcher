<script setup lang="ts">
import type { ExportEntry, ExportProgress, Instance } from '~/types'
import { PACK_DURATIONS, type OwnPack, type PackDuration, type SharePackOutcome } from '~/utils/packs'

// Instanz als Modpack teilen: vorbereiten (.mrpack), eigene JARs bestätigen,
// Zusammenfassung, dann ein eigener Upload. Vor dem Upload verwirft Schließen die Datei.
const props = defineProps<{ instance: Instance }>()
const emit = defineEmits<{ close: [] }>()

const trs = useTrsStore()
const packs = usePacksStore()
const tasks = useTasksStore()
const toasts = useToasts()
const key = computed(() => taskKey('packshare', props.instance.id))
const uploadKey = computed(() => taskKey('packupload', props.instance.id))
const task = computed(() => tasks.get(key.value))
const progress = computed(() => (task.value?.status === 'running' ? { percent: task.value.percent ?? 0, stage: task.value.stage } : null))
const uploading = computed(() => tasks.get(uploadKey.value)?.status === 'running')

const link = computed(() => {
  const l = packs.linkOf(props.instance.id)
  return l?.role === 'shared' ? l : null
})
const asUpdate = ref(true)
/** Instanz mit aktuellem Bild (der Symbol-Editor kann es hier ändern). */
const packInstance = ref<Instance>({ ...props.instance })
const iconOpen = ref(false)
function onIconSaved(updated: Instance) {
  packInstance.value = { ...packInstance.value, icon: updated.icon, iconPath: updated.iconPath }
  void useInstancesStore().load()
}
const entries = ref<ExportEntry[]>([])
const selected = ref<string[]>([])
const loading = ref(true)
const name = ref(props.instance.name)
const version = ref('1.0.0')
const summary = ref('')
const duration = ref<PackDuration>('7d')
const formError = ref<string | null>(null)
/** Eigene Mod-Dateien (nicht von Modrinth), die erst bestätigt werden müssen. */
const ownJars = ref<string[] | null>(null)
const plan = ref<Pick<SharePackOutcome, 'token' | 'downloads' | 'uploaded' | 'bytes'> | null>(null)
const result = ref<OwnPack | null>(null)
const preparedName = ref(props.instance.name)
const preparedUpdate = ref(false)
// Dialog noch offen? Schließen während der Vorbereitung verwirft den Token, sobald er da ist.
let alive = true
let closing = false
// Zurück und Schließen dürfen denselben Token nicht zweimal verwerfen.
let claimed: string | null = null

onBeforeUnmount(() => {
  alive = false
  // Seite weg, ohne dass der Dialog „Schließen“ gesehen hat: Vorbereitung stoppen, Datei loslassen.
  if (!uploading.value && tasks.isRunning(key.value)) void tasks.cancel(key.value)
  if (uploading.value || result.value) return
  const token = claimToken()
  if (token) void backend.packs.discardShare(token).catch(() => {})
})

onMounted(async () => {
  await packs.loadLinks()
  try {
    entries.value = await backend.exportCandidates(props.instance.id)
    const remembered = link.value?.include.filter((n) => entries.value.some((e) => e.name === n)) ?? []
    selected.value = remembered.length ? remembered : entries.value.filter((e) => e.recommended).map((e) => e.name)
    if (link.value) {
      name.value = link.value.name
      version.value = `1.0.${link.value.revision}`
    }
  } catch (e) {
    toasts.error(e)
  } finally {
    loading.value = false
  }
})

const totalSize = computed(() => entries.value.filter((e) => selected.value.includes(e.name)).reduce((sum, e) => sum + e.size, 0))
const planLine = computed(() => {
  const current = plan.value
  if (!current) return ''
  return `${t('packs.share.planLinks', current.downloads)} · ${t('packs.share.planFiles', current.uploaded)} · ${formatBytes(current.bytes)}`
})

function toggle(entry: ExportEntry) {
  selected.value = selected.value.includes(entry.name) ? selected.value.filter((n) => n !== entry.name) : [...selected.value, entry.name]
}

function remember(outcome: SharePackOutcome) {
  plan.value = { token: outcome.token, downloads: outcome.downloads, uploaded: outcome.uploaded, bytes: outcome.bytes }
  ownJars.value = outcome.status === 'confirmOwnJars' ? outcome.files : null
}

function claimToken(): string | null {
  const token = plan.value?.token
  if (!token || claimed === token) return null
  claimed = token
  return token
}

async function start() {
  if (closing) return
  const parsed = exportOptionsSchema.safeParse({ name: name.value, version: version.value, summary: summary.value.trim() || null, include: selected.value })
  if (!parsed.success) {
    formError.value = firstIssue(parsed.error)
    return
  }
  formError.value = null
  ownJars.value = null
  const update = !!link.value && asUpdate.value
  preparedName.value = parsed.data.name
  preparedUpdate.value = update
  const options = { ...parsed.data, summary: parsed.data.summary ?? null, duration: duration.value, allowOwnJars: false }
  await tasks.run(
    { key: key.value, kind: 'export', title: parsed.data.name, stage: t('packs.share.preparing'), instanceId: props.instance.id, cancellable: true, pausable: false },
    async (ctx) => {
      try {
        const outcome = await backend.packs.share(
          props.instance.id,
          options,
          update,
          (p: ExportProgress) => ctx.progress(p.percent, `${t(`exportPack.phase.${p.phase}`)} …`),
          ctx.taskId,
        )
        // Noch kein „geteilt“: der Upload ist eine eigene Aufgabe.
        ctx.discard()
        if (!alive) {
          void backend.packs.discardShare(outcome.token).catch(() => {})
          return
        }
        remember(outcome)
      } catch (e) {
        if (!alive) ctx.discard()
        throw e
      }
    },
  )
}

function confirmJars() {
  if (plan.value) ownJars.value = null
}

async function backToForm() {
  if (closing || uploading.value) return
  const token = claimToken()
  if (!token) return
  try {
    await backend.packs.discardShare(token)
  } catch (e) {
    claimed = null
    toasts.error(e)
    return
  }
  plan.value = null
  ownJars.value = null
}

async function upload() {
  const current = plan.value
  if (closing || !current || uploading.value) return
  const update = preparedUpdate.value
  const run = await tasks.run(
    {
      key: uploadKey.value,
      kind: 'export',
      title: preparedName.value,
      stage: t('packs.share.uploading'),
      instanceId: props.instance.id,
      pausable: true,
      cancellable: true,
    },
    async (ctx) => {
      const pack = await backend.packs.uploadShare(current.token, ctx.taskId)
      ctx.update({ doneText: t(update ? 'packs.share.doneUpdate' : 'packs.share.done', { name: pack.name, code: pack.code }) })
      return pack
    },
  )
  if (!run.ok) return
  result.value = run.value
  plan.value = null
  void packs.loadLinks()
}

async function close() {
  if (closing) return
  closing = true
  alive = false
  if (uploading.value || result.value) {
    emit('close')
    return
  }
  if (tasks.isRunning(key.value)) void tasks.cancel(key.value)
  const token = claimToken()
  if (token) {
    try {
      await backend.packs.discardShare(token)
    } catch (e) {
      toasts.error(e)
    }
  }
  plan.value = null
  ownJars.value = null
  emit('close')
}
</script>

<template>
  <BaseDialog :title="result ? t('packs.share.resultTitle') : t('packs.share.title')" wide @close="close">
    <!-- Geteilt: Code, Link, an Freunde -->
    <template v-if="result">
      <p class="mb-3 text-sm text-base-200">{{ t('packs.share.resultIntro', { name: result.name }) }}</p>
      <PackCodeCard :pack="result" />
      <template v-if="trs.enabled">
        <p class="label mt-4">{{ t('packs.send.title') }}</p>
        <PackSendPicker :pack-id="result.id" :pack-name="result.name" />
      </template>
    </template>

    <!-- Eigene Mod-Dateien bestätigen -->
    <template v-else-if="ownJars">
      <p class="text-sm text-base-200">{{ t('packs.share.ownJarsIntro', ownJars.length) }}</p>
      <ul class="mt-2 max-h-40 overflow-y-auto rounded-md border border-base-800 bg-base-950 px-3 py-2 font-mono text-xs text-base-300">
        <li v-for="f in ownJars" :key="f" class="truncate">{{ f }}</li>
      </ul>
      <p class="mt-3 text-xs leading-relaxed text-base-400">{{ t('packs.share.ownJarsHint') }}</p>
    </template>

    <template v-else-if="plan">
      <p class="text-sm text-base-200">{{ planLine }}</p>
      <TaskTransfer v-if="uploading" class="mt-4" :task-key="uploadKey" />
    </template>

    <template v-else>
      <div v-if="!trs.enabled" class="mb-3 rounded-md border border-base-700 bg-base-900 px-3 py-2 text-sm text-base-300">{{ t('packs.needsTrs') }}</div>
      <div v-if="link" class="mb-4 space-y-1.5" role="radiogroup" :aria-label="t('packs.share.modeLabel')">
        <label class="flex cursor-pointer items-start gap-2.5 text-sm">
          <input v-model="asUpdate" type="radio" class="mt-1 accent-redstone-500" :value="true" :disabled="!!progress" />
          <span><span class="text-base-50">{{ t('packs.share.modeUpdate', { code: link.code }) }}</span><br /><span class="text-xs text-base-400">{{ t('packs.share.modeUpdateHint') }}</span></span>
        </label>
        <label class="flex cursor-pointer items-start gap-2.5 text-sm">
          <input v-model="asUpdate" type="radio" class="mt-1 accent-redstone-500" :value="false" :disabled="!!progress" />
          <span class="text-base-200">{{ t('packs.share.modeNew') }}</span>
        </label>
      </div>
      <div class="grid gap-3 sm:grid-cols-[1fr_9rem]">
        <div>
          <label class="label" for="sp-name">{{ t('common.labels.name') }}</label>
          <input id="sp-name" v-model="name" class="field" maxlength="64" :disabled="!!progress" />
        </div>
        <div>
          <label class="label" for="sp-version">{{ t('common.labels.version') }}</label>
          <input id="sp-version" v-model="version" class="field" maxlength="32" :disabled="!!progress" />
        </div>
      </div>
      <div class="mt-3 grid gap-3 sm:grid-cols-[1fr_12rem]">
        <div>
          <label class="label" for="sp-summary">{{ t('exportPack.summary') }}</label>
          <input id="sp-summary" v-model="summary" class="field" maxlength="300" :placeholder="t('exportPack.summaryPlaceholder')" :disabled="!!progress" />
        </div>
        <div v-if="!link || !asUpdate">
          <label class="label" for="sp-duration">{{ t('packs.duration.label') }}</label>
          <select id="sp-duration" v-model="duration" class="field" :disabled="!!progress">
            <option v-for="d in PACK_DURATIONS" :key="d" :value="d">{{ durationLabel(d) }}</option>
          </select>
        </div>
      </div>

      <!-- Pack-Symbol: reist im Pack mit und wird beim Installieren zum Instanz-Bild. -->
      <div class="mt-3 flex items-center gap-3 rounded-md border border-base-800 bg-base-900 px-3 py-2">
        <InstanceIcon :instance="packInstance" :size="40" />
        <div class="min-w-0 flex-1">
          <p class="text-sm text-base-100">{{ t('packs.share.icon') }}</p>
          <p class="text-xs text-base-400">{{ t('packs.share.iconHint') }}</p>
        </div>
        <button type="button" class="btn btn-ghost shrink-0 py-1.5 text-xs" data-icon-editor :disabled="!!progress" @click="iconOpen = true">{{ t('iconEditor.open') }}</button>
      </div>

      <p class="label mt-4">{{ t('packs.share.included') }}</p>
      <div v-if="loading" class="space-y-1.5">
        <div v-for="i in 4" :key="i" class="skeleton h-9" />
      </div>
      <p v-else-if="!entries.length" class="text-sm text-base-400">{{ t('exportPack.nothing') }}</p>
      <ul v-else class="max-h-48 space-y-1 overflow-y-auto pr-1">
        <li v-for="entry in entries" :key="entry.name">
          <label class="flex cursor-pointer items-center gap-3 rounded-md px-2.5 py-1.5 hover:bg-base-850">
            <input type="checkbox" class="size-4 accent-redstone-500" :checked="selected.includes(entry.name)" :disabled="!!progress" @change="toggle(entry)" />
            <span class="min-w-0 flex-1 truncate font-mono text-xs">{{ entry.name }}</span>
            <span class="shrink-0 text-[11px] text-base-400">{{ entry.isDir ? t('exportPack.dirSize', { size: formatBytes(entry.size) }, entry.files) : formatBytes(entry.size) }}</span>
          </label>
        </li>
      </ul>
      <p class="mt-3 text-xs leading-relaxed text-base-400">{{ t('packs.share.hint', { size: formatBytes(totalSize) }) }}</p>
      <p v-if="formError" role="alert" class="mt-2 text-xs text-redstone-300">{{ formError }}</p>

      <div v-if="progress" class="mt-4 flex items-center gap-3">
        <RedstoneWire class="flex-1" :percent="progress.percent" :segments="40" />
        <span class="display shrink-0 text-sm text-redstone-300 tabular-nums">{{ Math.floor(progress.percent) }} %</span>
      </div>
      <p v-if="progress" class="mt-1 text-xs text-base-400">{{ progress.stage }}</p>
    </template>

    <template #actions>
      <template v-if="result">
        <button class="btn btn-ghost" @click="packs.mineOpen = true; close()">{{ t('packs.mine.open') }}</button>
        <button class="btn btn-primary" @click="close()">{{ t('common.actions.close') }}</button>
      </template>
      <template v-else-if="ownJars">
        <button class="btn btn-ghost" @click="backToForm">{{ t('common.actions.back') }}</button>
        <button class="btn btn-primary" @click="confirmJars">{{ t('packs.share.ownJarsConfirm') }}</button>
      </template>
      <template v-else-if="plan">
        <button class="btn btn-ghost" :disabled="uploading" @click="backToForm">{{ t('common.actions.back') }}</button>
        <button class="btn btn-primary" :disabled="uploading" @click="upload">{{ uploading ? t('packs.share.uploading') : t('packs.share.upload') }}</button>
      </template>
      <template v-else>
        <button class="btn btn-ghost" @click="close()">{{ progress ? t('common.actions.close') : t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="!!progress || loading || !selected.length || !trs.enabled" @click="start()">
          {{ progress ? t('packs.share.sharing') : link && asUpdate ? t('packs.share.actionUpdate') : t('packs.share.action') }}
        </button>
      </template>
    </template>
  </BaseDialog>
  <IconInstanceEditor v-if="iconOpen" :instance="packInstance" @close="iconOpen = false" @saved="onIconSaved" />
</template>
