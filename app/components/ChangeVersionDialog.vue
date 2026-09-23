<script setup lang="ts">
import type { Instance, LoaderKind, LoaderVersionInfo, MigrationItem } from '~/types'
import { cancelledError } from '~/stores/tasks'

// Minecraft-Version und/oder Modloader einer Instanz wechseln. Danach werden
// die über Modrinth installierten Inhalte auf passende Versionen gebracht.
const props = defineProps<{ instance: Instance }>()
const emit = defineEmits<{ close: []; changed: [instance: Instance] }>()

const meta = useMetaStore()
const toasts = useToasts()

const step = ref<'choose' | 'migrate'>('choose')
const gameVersion = ref(props.instance.gameVersion)
const loaderKind = ref<LoaderKind>(props.instance.loader.kind)
const loaderVersion = ref(props.instance.loader.version ?? '')
const showSnapshots = ref(false)
const loadingVersions = ref(true)
const saving = ref(false)
const error = ref<string | null>(null)

const plan = ref<MigrationItem[] | null>(null)
const planning = ref(false)
// Das Nachziehen der Inhalte läuft als Aufgabe weiter, auch wenn der Dialog zugeht.
const tasks = useTasksStore()
const migrateKey = computed(() => taskKey('migrate', props.instance.id))
const applying = computed(() => {
  const task = tasks.get(migrateKey.value)
  if (task?.status !== 'running') return null
  const [done, total] = (task.tag ?? '0/0').split('/').map(Number)
  return { done: done ?? 0, total: total ?? 0 }
})
let dialogOpen = true
onBeforeUnmount(() => (dialogOpen = false))

const versions = computed(() => {
  const list = (meta.manifest?.versions ?? []).filter((v) => v.type === 'release' || showSnapshots.value)
  // Die aktuelle Version muss wählbar bleiben, auch wenn sie ein Snapshot ist.
  if (!list.some((v) => v.id === props.instance.gameVersion)) {
    const current = meta.manifest?.versions.find((v) => v.id === props.instance.gameVersion)
    if (current) list.unshift(current)
  }
  return list
})
const unchanged = computed(
  () =>
    gameVersion.value === props.instance.gameVersion &&
    loaderKind.value === props.instance.loader.kind &&
    (loaderVersion.value.trim() || null) === (props.instance.loader.version ?? null),
)
const loaderChanged = computed(() => loaderKind.value !== props.instance.loader.kind)
const isDowngrade = computed(() => {
  const all = meta.manifest?.versions ?? []
  const from = all.findIndex((v) => v.id === props.instance.gameVersion)
  const to = all.findIndex((v) => v.id === gameVersion.value)
  // Das Manifest ist neueste zuerst sortiert.
  return from >= 0 && to > from
})

onMounted(async () => {
  try {
    await meta.loadManifest()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loadingVersions.value = false
  }
})

watch(loaderKind, (kind) => {
  if (kind === 'vanilla' || kind !== props.instance.loader.kind) loaderVersion.value = ''
})

// Auswählbare Loader-Versionen für die gewählte Spielversion.
const loaderOptions = ref<LoaderVersionInfo[] | null>(null)
const loaderListFailed = ref(false)
const showUnstable = ref(false)
let loaderRequest = 0
watch(
  [loaderKind, gameVersion],
  async ([kind, game]) => {
    const request = ++loaderRequest
    loaderOptions.value = null
    loaderListFailed.value = false
    if (kind === 'vanilla') return
    try {
      const list = await backend.loaderVersions(kind, game)
      if (request === loaderRequest) loaderOptions.value = list
    } catch {
      if (request === loaderRequest) loaderListFailed.value = true
    }
  },
  { immediate: true },
)
const visibleLoaderOptions = computed(() => {
  const list = (loaderOptions.value ?? []).filter((v) => v.stable || showUnstable.value)
  // Die aktuell eingestellte Version bleibt wählbar.
  if (loaderVersion.value && !list.some((v) => v.version === loaderVersion.value)) {
    list.unshift({ version: loaderVersion.value, stable: true })
  }
  return list
})

/** Eintrag der Loader-Versionsliste mit Hinweis auf Beta und aktuelle Version. */
function loaderOptionLabel(v: LoaderVersionInfo): string {
  const current = v.version === props.instance.loader.version
  if (!v.stable) return current ? t('changeVersion.optionBetaCurrent', { version: v.version }) : t('changeVersion.optionBeta', { version: v.version })
  return current ? t('changeVersion.optionCurrent', { version: v.version }) : v.version
}

async function submit() {
  error.value = null
  const parsed = newInstanceSchema.pick({ gameVersion: true, loader: true }).safeParse({
    gameVersion: gameVersion.value,
    loader: { kind: loaderKind.value, version: loaderKind.value === 'vanilla' ? null : loaderVersion.value.trim() || null },
  })
  if (!parsed.success) {
    error.value = firstIssue(parsed.error)
    return
  }
  saving.value = true
  try {
    const updated = await backend.changeInstanceVersion(props.instance.id, parsed.data.gameVersion, parsed.data.loader)
    emit('changed', updated)
    toasts.ok(t('changeVersion.toasts.changed', { version: updated.gameVersion, loader: loaderLabels[updated.loader.kind] }))
    tasks.note({
      kind: 'version-change',
      title: `${updated.name} → ${updated.gameVersion}`,
      outcome: 'done',
      instanceId: updated.id,
    })
    step.value = 'migrate'
    await loadPlan()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function loadPlan() {
  planning.value = true
  try {
    plan.value = await backend.planContentMigration(props.instance.id)
    if (!plan.value.length) emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    planning.value = false
  }
}

const toUpdate = computed(() => (plan.value ?? []).filter((p) => p.status === 'update'))
const missing = computed(() => (plan.value ?? []).filter((p) => p.status === 'missing'))
const fine = computed(() => (plan.value ?? []).filter((p) => p.status === 'compatible'))

async function applyPlan() {
  const list = toUpdate.value
  const instance = props.instance
  await tasks.run(
    {
      key: migrateKey.value,
      kind: 'content-update',
      title: instance.name,
      stage: t('changeVersion.task.stage'),
      instanceId: instance.id,
      tag: `0/${list.length}`,
      cancellable: true,
      // Der Versionswechsel steht schon im Verlauf.
      record: false,
      doneText: t('changeVersion.task.done', list.length),
    },
    async (ctx) => {
      let failed = 0
      for (const [i, p] of list.entries()) {
        if (ctx.cancelled()) throw cancelledError()
        ctx.progress((i / list.length) * 100, p.title || p.fileName)
        try {
          await backend.applyContentUpdate(instance.id, {
            kind: p.kind,
            fileName: p.fileName,
            projectId: p.projectId,
            versionId: p.targetVersionId!,
            versionNumber: p.targetVersionNumber ?? '',
          })
        } catch {
          failed++
        }
        ctx.update({ tag: `${i + 1}/${list.length}` })
      }
      if (failed) throw new BackendError('partial', t('changeVersion.task.partial', { total: list.length }, failed))
    },
  )
  if (dialogOpen) await loadPlan()
}

async function disableMissing() {
  for (const p of missing.value) {
    try {
      await backend.setContentEnabled(props.instance.id, p.kind, p.fileName, false)
    } catch (e) {
      toasts.error(e)
    }
  }
  toasts.ok(t('changeVersion.toasts.disabledMissing'))
  await loadPlan()
}
</script>

<template>
  <BaseDialog :title="step === 'choose' ? t('changeVersion.title') : t('changeVersion.migrateTitle')" wide @close="emit('close')">
    <form v-if="step === 'choose'" id="change-version" class="space-y-4" @submit.prevent="submit">
      <i18n-t keypath="changeVersion.current" tag="p" scope="global" class="text-sm text-base-400">
        <template #version><span class="font-mono text-base-200">{{ instance.gameVersion }}</span></template>
        <template #loader>{{ loaderLabels[instance.loader.kind] }}<span v-if="instance.loader.version"> {{ instance.loader.version }}</span></template>
      </i18n-t>

      <div>
        <div class="flex items-center justify-between">
          <label class="label" for="cv-version">{{ t('changeVersion.gameVersion') }}</label>
          <label class="mb-1.5 flex items-center gap-1.5 text-xs text-base-400">
            <input v-model="showSnapshots" type="checkbox" class="accent-redstone-500" />
            {{ t('changeVersion.showSnapshots') }}
          </label>
        </div>
        <select id="cv-version" v-model="gameVersion" class="field font-mono" :disabled="loadingVersions">
          <option v-if="loadingVersions" :value="gameVersion">{{ t('changeVersion.loadingVersions') }}</option>
          <option v-for="v in versions" :key="v.id" :value="v.id">{{ v.id === instance.gameVersion ? t('changeVersion.optionCurrent', { version: v.id }) : v.id }}</option>
        </select>
      </div>

      <div>
        <span class="label">{{ t('common.labels.loader') }}</span>
        <div class="grid grid-cols-5 gap-1.5">
          <button
            v-for="kind in loaderKinds"
            :key="kind"
            type="button"
            class="rounded-md border px-1 py-2 text-xs font-medium transition-colors"
            :class="loaderKind === kind ? 'border-redstone-500 bg-redstone-900 text-base-50' : 'border-base-700 bg-base-900 text-base-400 hover:border-base-600 hover:text-base-50'"
            @click="loaderKind = kind"
          >
            {{ loaderLabels[kind] }}
          </button>
        </div>
      </div>

      <div v-if="loaderKind !== 'vanilla'">
        <div class="flex items-center justify-between">
          <label class="label" for="cv-loader">{{ t('changeVersion.loaderVersion', { loader: loaderLabels[loaderKind] }) }}</label>
          <label v-if="loaderOptions?.some((v) => !v.stable)" class="mb-1.5 flex items-center gap-1.5 text-xs text-base-400">
            <input v-model="showUnstable" type="checkbox" class="accent-redstone-500" />
            {{ t('changeVersion.showBetas') }}
          </label>
        </div>
        <input v-if="loaderListFailed" id="cv-loader" v-model="loaderVersion" class="field font-mono" maxlength="64" :placeholder="t('changeVersion.loaderEmptyPlaceholder')" spellcheck="false" />
        <select v-else id="cv-loader" v-model="loaderVersion" class="field font-mono" :disabled="!loaderOptions">
          <option value="">{{ loaderOptions ? t('changeVersion.latestRecommended') : t('changeVersion.loadingVersions') }}</option>
          <option v-for="v in visibleLoaderOptions" :key="v.version" :value="v.version">{{ loaderOptionLabel(v) }}</option>
        </select>
        <p v-if="loaderOptions && !loaderOptions.length" class="mt-1 text-xs text-warn">
          {{ t('changeVersion.loaderUnavailable', { version: gameVersion, loader: loaderLabels[loaderKind] }) }}
        </p>
      </div>

      <div class="rounded-lg border border-warn/40 bg-lamp-900/40 px-4 py-3 text-sm text-base-200" role="note">
        <p class="font-medium text-warn">{{ t('changeVersion.notice.title') }}</p>
        <ul class="mt-1 list-disc space-y-0.5 pl-5 text-xs text-base-200">
          <i18n-t v-if="isDowngrade" keypath="changeVersion.notice.downgrade" tag="li" scope="global">
            <template #label><strong>{{ t('changeVersion.notice.downgradeLabel') }}</strong></template>
          </i18n-t>
          <li>{{ t('changeVersion.notice.worlds') }}</li>
          <li v-if="loaderChanged">{{ t('changeVersion.notice.loaderChanged', { from: loaderLabels[instance.loader.kind], to: loaderLabels[loaderKind] }) }}</li>
          <li>{{ t('changeVersion.notice.migrate') }}</li>
        </ul>
      </div>

      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </form>

    <div v-else>
      <div v-if="planning" class="space-y-2">
        <p class="mb-3 text-sm text-base-400">{{ t('changeVersion.planning') }}</p>
        <div v-for="i in 4" :key="i" class="skeleton h-12" />
      </div>
      <template v-else-if="plan">
        <p class="mb-3 text-sm text-base-200">
          <span class="text-lamp-300">{{ t('changeVersion.updateCount', toUpdate.length) }}</span>,
          <span class="text-ok">{{ t('changeVersion.fineCount', fine.length) }}</span>,
          <span :class="missing.length ? 'text-redstone-300' : 'text-base-400'">{{ t('changeVersion.missingCount', missing.length) }}</span>
        </p>
        <ul class="-mr-2 max-h-80 space-y-1 overflow-y-auto pr-2">
          <li v-for="p in [...missing, ...toUpdate, ...fine]" :key="`${p.kind}/${p.fileName}`" class="flex items-center gap-3 rounded-lg bg-base-900 px-2.5 py-2">
            <ModIcon :src="p.iconUrl" :name="p.title" :size="32" />
            <span class="min-w-0 flex-1 truncate text-sm">{{ p.title }}</span>
            <i18n-t v-if="p.status === 'update'" keypath="changeVersion.itemChange" tag="span" scope="global" class="font-mono text-xs text-base-600">
              <template #from><span class="text-base-400">{{ p.currentVersion ?? '?' }}</span></template>
              <template #to><span class="text-lamp-300">{{ p.targetVersionNumber }}</span></template>
            </i18n-t>
            <span v-else-if="p.status === 'missing'" class="badge bg-redstone-900 text-redstone-300">{{ t('changeVersion.noMatch') }}</span>
            <span v-else class="badge bg-ok/10 text-ok">{{ t('changeVersion.fits') }}</span>
          </li>
        </ul>
        <div v-if="applying" class="mt-4 flex items-center gap-3">
          <RedstoneWire class="flex-1" :percent="(applying.done / Math.max(1, applying.total)) * 100" :segments="32" />
          <span class="display text-sm text-redstone-300 tabular-nums">{{ applying.done }} / {{ applying.total }}</span>
        </div>
      </template>
      <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>
    </div>

    <template #actions>
      <template v-if="step === 'choose'">
        <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
        <button type="submit" form="change-version" class="btn btn-primary" :disabled="saving || loadingVersions || unchanged">
          {{ saving ? t('changeVersion.saving') : t('changeVersion.title') }}
        </button>
      </template>
      <template v-else>
        <button class="btn btn-ghost" :disabled="!!applying" @click="emit('close')">{{ toUpdate.length ? t('common.actions.later') : t('common.actions.done') }}</button>
        <button v-if="missing.length" class="btn btn-ghost" :disabled="!!applying || planning" @click="disableMissing">{{ t('changeVersion.disableMissing') }}</button>
        <button v-if="toUpdate.length" class="btn btn-primary" :disabled="!!applying || planning" @click="applyPlan">
          {{ t('changeVersion.applyUpdates') }}
        </button>
      </template>
    </template>
  </BaseDialog>
</template>
