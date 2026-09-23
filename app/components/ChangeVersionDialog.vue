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
  const t = tasks.get(migrateKey.value)
  if (t?.status !== 'running') return null
  const [done, total] = (t.tag ?? '0/0').split('/').map(Number)
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
    toasts.ok(`Instanz läuft jetzt mit ${updated.gameVersion} (${loaderLabels[updated.loader.kind]})`)
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
      stage: 'Inhalte werden angepasst',
      instanceId: instance.id,
      tag: `0/${list.length}`,
      cancellable: true,
      // Der Versionswechsel steht schon im Verlauf.
      record: false,
      doneText: `${list.length} ${list.length === 1 ? 'Inhalt' : 'Inhalte'} auf passende Versionen gebracht`,
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
      if (failed) throw new BackendError('partial', `${failed} von ${list.length} Updates sind fehlgeschlagen.`)
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
  toasts.ok('Nicht passende Inhalte deaktiviert')
  await loadPlan()
}
</script>

<template>
  <BaseDialog :title="step === 'choose' ? 'Version wechseln' : 'Inhalte anpassen'" wide @close="emit('close')">
    <form v-if="step === 'choose'" id="change-version" class="space-y-4" @submit.prevent="submit">
      <p class="text-sm text-base-400">
        Aktuell: <span class="font-mono text-base-200">{{ instance.gameVersion }}</span> mit {{ loaderLabels[instance.loader.kind] }}<span v-if="instance.loader.version"> {{ instance.loader.version }}</span>
      </p>

      <div>
        <div class="flex items-center justify-between">
          <label class="label" for="cv-version">Minecraft-Version</label>
          <label class="mb-1.5 flex items-center gap-1.5 text-xs text-base-400">
            <input v-model="showSnapshots" type="checkbox" class="accent-redstone-500" />
            Snapshots &amp; alte Versionen
          </label>
        </div>
        <select id="cv-version" v-model="gameVersion" class="field font-mono" :disabled="loadingVersions">
          <option v-if="loadingVersions" :value="gameVersion">Lade Versionen …</option>
          <option v-for="v in versions" :key="v.id" :value="v.id">{{ v.id }}{{ v.id === instance.gameVersion ? ' (aktuell)' : '' }}</option>
        </select>
      </div>

      <div>
        <span class="label">Modloader</span>
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
          <label class="label" for="cv-loader">{{ loaderLabels[loaderKind] }}-Version</label>
          <label v-if="loaderOptions?.some((v) => !v.stable)" class="mb-1.5 flex items-center gap-1.5 text-xs text-base-400">
            <input v-model="showUnstable" type="checkbox" class="accent-redstone-500" />
            Betas anzeigen
          </label>
        </div>
        <input v-if="loaderListFailed" id="cv-loader" v-model="loaderVersion" class="field font-mono" maxlength="64" placeholder="Leer = neueste stabile" spellcheck="false" />
        <select v-else id="cv-loader" v-model="loaderVersion" class="field font-mono" :disabled="!loaderOptions">
          <option value="">{{ loaderOptions ? 'Neueste stabile (empfohlen)' : 'Lade Versionen …' }}</option>
          <option v-for="v in visibleLoaderOptions" :key="v.version" :value="v.version">
            {{ v.version }}{{ v.stable ? '' : ' (Beta)' }}{{ v.version === instance.loader.version ? ' (aktuell)' : '' }}
          </option>
        </select>
        <p v-if="loaderOptions && !loaderOptions.length" class="mt-1 text-xs text-warn">
          Für Minecraft {{ gameVersion }} gibt es {{ loaderLabels[loaderKind] }} (noch) nicht.
        </p>
      </div>

      <div class="rounded-lg border border-warn/40 bg-lamp-900/40 px-4 py-3 text-sm text-base-200" role="note">
        <p class="font-medium text-warn">Bitte vorher lesen</p>
        <ul class="mt-1 list-disc space-y-0.5 pl-5 text-xs text-base-200">
          <li v-if="isDowngrade"><strong>Ältere Version:</strong> Welten, die mit einer neueren Version gespielt wurden, können dabei kaputtgehen.</li>
          <li>Welten werden beim ersten Start umgewandelt und lassen sich danach nicht mehr in der alten Version öffnen. Sicherer: Instanz vorher duplizieren.</li>
          <li v-if="loaderChanged">Mods für {{ loaderLabels[instance.loader.kind] }} laufen nicht mit {{ loaderLabels[loaderKind] }}.</li>
          <li>Danach kannst du über Modrinth installierte Mods automatisch auf passende Versionen bringen.</li>
        </ul>
      </div>

      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </form>

    <div v-else>
      <div v-if="planning" class="space-y-2">
        <p class="mb-3 text-sm text-base-400">Suche passende Versionen für deine Inhalte …</p>
        <div v-for="i in 4" :key="i" class="skeleton h-12" />
      </div>
      <template v-else-if="plan">
        <p class="mb-3 text-sm text-base-200">
          <span class="text-lamp-300">{{ toUpdate.length }} mit neuer Version</span>,
          <span class="text-ok">{{ fine.length }} passen schon</span>,
          <span :class="missing.length ? 'text-redstone-300' : 'text-base-400'">{{ missing.length }} ohne passende Version</span>
        </p>
        <ul class="-mr-2 max-h-80 space-y-1 overflow-y-auto pr-2">
          <li v-for="p in [...missing, ...toUpdate, ...fine]" :key="`${p.kind}/${p.fileName}`" class="flex items-center gap-3 rounded-lg bg-base-900 px-2.5 py-2">
            <ModIcon :src="p.iconUrl" :name="p.title" :size="32" />
            <span class="min-w-0 flex-1 truncate text-sm">{{ p.title }}</span>
            <span v-if="p.status === 'update'" class="font-mono text-xs text-base-400">
              {{ p.currentVersion ?? '?' }} <span class="text-base-600">zu</span> <span class="text-lamp-300">{{ p.targetVersionNumber }}</span>
            </span>
            <span v-else-if="p.status === 'missing'" class="badge bg-redstone-900 text-redstone-300">Keine passende Version</span>
            <span v-else class="badge bg-ok/10 text-ok">Passt</span>
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
        <button type="button" class="btn btn-ghost" @click="emit('close')">Abbrechen</button>
        <button type="submit" form="change-version" class="btn btn-primary" :disabled="saving || loadingVersions || unchanged">
          {{ saving ? 'Wechsle …' : 'Version wechseln' }}
        </button>
      </template>
      <template v-else>
        <button class="btn btn-ghost" :disabled="!!applying" @click="emit('close')">{{ toUpdate.length ? 'Später' : 'Fertig' }}</button>
        <button v-if="missing.length" class="btn btn-ghost" :disabled="!!applying || planning" @click="disableMissing">Nicht passende deaktivieren</button>
        <button v-if="toUpdate.length" class="btn btn-primary" :disabled="!!applying || planning" @click="applyPlan">
          Mods auf passende Versionen aktualisieren
        </button>
      </template>
    </template>
  </BaseDialog>
</template>
