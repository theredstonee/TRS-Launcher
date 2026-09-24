<script setup lang="ts">
import type { EnvVar, Instance, InstanceOverrides, SyncItem, UpdateChannel } from '~/types'
import type { ShellSection } from '~/components/SettingsShell.vue'

// Instanz-Einstellungen: Modal mit Bereichen links,
// alles speichert automatisch (nach kurzer Pause, erst nach Prüfung).
const props = withDefaults(defineProps<{ instance: Instance; initial?: string }>(), { initial: 'general' })
const emit = defineEmits<{ close: []; updated: [instance: Instance]; deleted: [] }>()

const settings = useSettingsStore()
const instances = useInstancesStore()
const games = useGamesStore()
const toasts = useToasts()
const router = useRouter()

const inst = ref<Instance>(structuredClone(toRaw(props.instance)))
const running = computed(() => games.state(inst.value.id).phase !== 'idle')
const g = computed(() => settings.current)

// Die Beschriftungen sind Getter und folgen der eingestellten Sprache.
const sections: ShellSection[] = instanceSettingsSections
const active = ref(sections.some((s) => s.key === props.initial) ? props.initial : 'general')

// --- Formularzustand ----------------------------------------------------------
const o = inst.value.overrides
const name = ref(inst.value.name)
const channel = ref<UpdateChannel>(o.updateChannel ?? 'release')
const trsClient = ref(o.trsClient !== false)
const boost = ref(o.boost !== false)
/** FPS-Boost beim Start: wie global, an oder aus. */
const tuning = ref<'global' | 'on' | 'off'>(o.performanceTuning == null ? 'global' : o.performanceTuning ? 'on' : 'off')

const customWindow = ref(o.fullscreen !== null || o.resolution !== null)
const fullscreen = ref(o.fullscreen ?? false)
const width = ref<number | null>(o.resolution?.width ?? null)
const height = ref<number | null>(o.resolution?.height ?? null)

const customJava = ref(o.javaPath !== null || o.maxMemoryMb !== null || o.jvmArgs !== null || o.env !== null)
const javaPath = ref(o.javaPath ?? '')
const memory = ref(o.maxMemoryMb ?? 4096)
const jvmArgs = ref(o.jvmArgs ?? '')
const env = ref<EnvVar[]>(structuredClone(toRaw(o.env ?? [])))

const customHooks = ref(o.hooks !== null)
const preLaunch = ref(o.hooks?.preLaunch ?? '')
const wrapper = ref(o.hooks?.wrapper ?? '')
const postExit = ref(o.hooks?.postExit ?? '')

const syncSeparate = ref<SyncItem[]>([...o.syncSeparate])

onMounted(async () => {
  if (!settings.current) await settings.load().catch(() => {})
  // Ohne eigene Werte zeigen die Felder die globalen Werte als Ausgangspunkt.
  if (o.maxMemoryMb === null && g.value) memory.value = g.value.maxMemoryMb
  if (!customWindow.value && g.value) fullscreen.value = g.value.fullscreen
  loadResolvedLoader()
})

function overrides(): InstanceOverrides {
  const text = (v: string) => v.trim() || null
  return {
    ...inst.value.overrides,
    updateChannel: channel.value === 'release' ? null : channel.value,
    trsClient: trsClient.value ? null : false,
    boost: boost.value ? null : false,
    performanceTuning: tuning.value === 'global' ? null : tuning.value === 'on',
    fullscreen: customWindow.value ? fullscreen.value : null,
    resolution:
      customWindow.value && width.value && height.value ? { width: width.value, height: height.value } : null,
    javaPath: customJava.value ? text(javaPath.value) : null,
    maxMemoryMb: customJava.value ? memory.value : null,
    jvmArgs: customJava.value ? text(jvmArgs.value) : null,
    env: customJava.value && env.value.length ? env.value : null,
    hooks: customHooks.value
      ? { preLaunch: text(preLaunch.value), wrapper: text(wrapper.value), postExit: text(postExit.value) }
      : null,
    syncSeparate: syncSeparate.value,
  }
}

// --- Automatisch speichern ------------------------------------------------------
type SaveState = { kind: 'saving' | 'saved' } | { kind: 'error'; text: string }
const saveState = ref<SaveState | null>(null)
const status = computed(() => {
  const s = saveState.value
  if (!s) return null
  if (s.kind === 'error') return { ok: false, text: s.text }
  return { ok: true, text: s.kind === 'saving' ? t('common.status.saving') : t('common.status.saved') }
})
const payload = computed(() => ({ name: name.value, overrides: overrides() }))
let lastSaved = JSON.stringify({ name: inst.value.name, overrides: overrides() })
let timer: ReturnType<typeof setTimeout> | undefined

watch(
  () => JSON.stringify(payload.value),
  (json) => {
    clearTimeout(timer)
    if (json === lastSaved) return
    timer = setTimeout(save, 600)
  },
)
onBeforeUnmount(() => {
  // Falls der Dialog anders als über „Schließen“ verschwindet.
  if (timer) {
    clearTimeout(timer)
    save()
  }
})

/** Ausstehendes erst speichern, dann schließen – so sieht die Seite den neuen Stand. */
async function close() {
  if (timer) {
    clearTimeout(timer)
    await save()
  }
  emit('close')
}

async function save() {
  timer = undefined
  const parsed = updateInstanceSchema.safeParse(payload.value)
  if (!parsed.success) {
    saveState.value = { kind: 'error', text: firstIssue(parsed.error) }
    return
  }
  const json = JSON.stringify(payload.value)
  saveState.value = { kind: 'saving' }
  try {
    const updated = await backend.updateInstance(inst.value.id, parsed.data)
    lastSaved = json
    inst.value = { ...updated, iconPath: inst.value.iconPath, bannerPath: inst.value.bannerPath }
    emit('updated', inst.value)
    instances.load()
    saveState.value = { kind: 'saved' }
  } catch (e) {
    saveState.value = { kind: 'error', text: errorMessage(e) }
  }
}

// --- Allgemein ------------------------------------------------------------------
const iconBusy = ref(false)
async function pickIcon() {
  iconBusy.value = true
  try {
    const updated = await backend.pickInstanceIcon(inst.value.id)
    if (updated) applyMeta(updated)
  } catch (e) {
    toasts.error(e)
  } finally {
    iconBusy.value = false
  }
}
async function removeIcon() {
  try {
    applyMeta(await backend.removeInstanceIcon(inst.value.id))
  } catch (e) {
    toasts.error(e)
  }
}

const bannerBusy = ref(false)
async function pickBanner() {
  bannerBusy.value = true
  try {
    const updated = await backend.pickInstanceBanner(inst.value.id)
    if (updated) applyMeta(updated)
  } catch (e) {
    toasts.error(e)
  } finally {
    bannerBusy.value = false
  }
}
async function removeBanner() {
  try {
    applyMeta(await backend.removeInstanceBanner(inst.value.id))
  } catch (e) {
    toasts.error(e)
  }
}

/** Bild, Banner, Gruppe, Version: sofort gespeichert – nur diese Felder übernehmen. */
function applyMeta(updated: Instance) {
  inst.value = { ...inst.value, icon: updated.icon, iconPath: updated.iconPath, bannerPath: updated.bannerPath, group: updated.group, gameVersion: updated.gameVersion, loader: updated.loader }
  emit('updated', inst.value)
  instances.load()
}

const groups = computed(() => {
  const set = new Set(instances.items.map((i) => i.group).filter((x): x is string => !!x))
  if (inst.value.group) set.add(inst.value.group)
  return [...set].sort(compareText)
})
const newGroup = ref<string | null>(null)
async function setGroup(group: string | null) {
  const parsed = groupSchema.safeParse(group ?? '')
  if (!parsed.success) {
    toasts.error(firstIssue(parsed.error))
    return
  }
  try {
    applyMeta(await backend.setInstanceGroup(inst.value.id, parsed.data || null))
    newGroup.value = null
  } catch (e) {
    toasts.error(e)
  }
}

const exporting = ref(false)
// Kopieren, Reparieren und Neu installieren laufen als Aufgaben weiter, auch wenn der Dialog zugeht.
const tasks = useTasksStore()
const duplicating = computed(() => tasks.isRunning(taskKey('duplicate', inst.value.id)))
let dialogOpen = true
onBeforeUnmount(() => (dialogOpen = false))
async function duplicate() {
  const source = inst.value
  const copyName = t('instanceSettings.general.copyName', { name: source.name }).slice(0, 64)
  const result = await tasks.run(
    { key: taskKey('duplicate', source.id), kind: 'duplicate', title: copyName, stage: t('instanceSettings.general.copyStage'), instanceId: source.id },
    async (ctx) => {
      const copy = await backend.duplicateInstance(source.id, copyName)
      ctx.update({ instanceId: copy.id, title: copy.name, doneText: t('instanceSettings.general.copyDone', { name: copy.name }) })
      await instances.load()
      return copy
    },
  )
  // Wer noch im Dialog wartet, landet direkt bei der Kopie.
  if (result.ok && dialogOpen) {
    emit('close')
    router.push(`/instances/${result.value.id}`)
  }
}

const deleting = ref(false)
const deleteBusy = ref(false)
async function confirmDelete() {
  deleteBusy.value = true
  try {
    await instances.remove(inst.value.id)
    toasts.ok(t('instanceSettings.general.deleted', { name: inst.value.name }))
    clearTimeout(timer)
    timer = undefined
    emit('deleted')
  } catch (e) {
    toasts.error(e)
  } finally {
    deleteBusy.value = false
  }
}

// --- Installation --------------------------------------------------------------
const changingVersion = ref(false)
const resolvedLoader = ref<string | null>(null)
async function loadResolvedLoader() {
  resolvedLoader.value = null
  const { kind, version } = inst.value.loader
  if (kind === 'vanilla' || version) return
  try {
    resolvedLoader.value = await backend.latestLoaderVersion(kind, inst.value.gameVersion)
  } catch {
    // Offline: dann bleibt es bei „neueste stabile“.
  }
}
function onVersionChanged(updated: Instance) {
  applyMeta(updated)
  loadResolvedLoader()
}

const repairTask = computed(() => tasks.get(repairTaskKey(inst.value.id)))
const repairing = computed(() =>
  repairTask.value?.status === 'running' ? { kind: repairTask.value.kind, percent: repairTask.value.percent ?? 0 } : null,
)
const confirmReinstall = ref(false)
function repair(kind: 'repair' | 'reinstall') {
  confirmReinstall.value = false
  repairInstanceTask(inst.value, kind)
}

// --- Java -----------------------------------------------------------------------
const neededJava = computed(() => javaMajorFor(inst.value.gameVersion))
const globalJava = computed(() => {
  const j = g.value?.java
  const byMajor = j ? { 8: j.java8, 17: j.java17, 21: j.java21, 25: j.java25 }[neededJava.value] : null
  return byMajor ?? g.value?.javaPath ?? null
})
const maxRam = 32768

// --- Synchronisierung --------------------------------------------------------------
function toggleSync(item: SyncItem, synced: boolean) {
  syncSeparate.value = synced ? syncSeparate.value.filter((i) => i !== item) : [...syncSeparate.value, item]
}

const loaderLine = computed(() => {
  const { kind, version } = inst.value.loader
  return `${loaderLabels[kind]}${version ? ` ${version}` : ''}`
})
</script>

<template>
  <SettingsShell v-model="active" :title="t('instanceSettings.title', { name: inst.name })" :sections="sections" :status="status" @close="close">
    <template #title-icon>
      <InstanceIcon :instance="inst" :size="32" />
    </template>
    <template #nav-footer>
      <p class="truncate font-medium text-base-400">{{ inst.name }}</p>
      <p class="font-mono">{{ inst.gameVersion }} · {{ loaderLabels[inst.loader.kind] }}</p>
    </template>

    <!-- Allgemein ------------------------------------------------------------ -->
    <div v-if="active === 'general'">
      <h3 class="section-heading">{{ t('instanceSettings.sections.general') }}</h3>
      <div class="flex items-center gap-5 border-b border-base-800 pb-5">
        <div class="group relative">
          <InstanceIcon :instance="inst" :size="88" />
          <button class="absolute inset-0 grid place-items-center rounded-xl bg-black/55 text-xs font-medium text-white opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100" :disabled="iconBusy" :aria-label="t('instanceSettings.general.changeImage')" @click="pickIcon">
            {{ iconBusy ? '…' : t('common.actions.change') }}
          </button>
        </div>
        <div class="min-w-0 flex-1">
          <label class="label" for="is-name">{{ t('common.labels.name') }}</label>
          <input id="is-name" v-model="name" class="field" maxlength="64" />
          <div class="mt-2 flex gap-2">
            <button class="btn btn-ghost py-1.5 text-xs" :disabled="iconBusy" @click="pickIcon">{{ t('instanceSettings.general.changeImage') }}</button>
            <button v-if="inst.iconPath" class="btn btn-ghost py-1.5 text-xs hover:text-redstone-300" @click="removeIcon">{{ t('instanceSettings.general.removeImage') }}</button>
          </div>
        </div>
      </div>

      <SettingRow
        :title="t('instanceSettings.general.bannerTitle')"
        :description="t('instanceSettings.general.bannerDescription')"
        stacked
      >
        <div class="group relative overflow-hidden rounded-xl border border-base-800">
          <InstanceBanner :instance="inst" shade="none" class="h-28 w-full" />
          <div class="absolute inset-0 flex items-end justify-end gap-2 bg-gradient-to-t from-base-950/80 to-transparent p-2.5">
            <button class="btn btn-ghost py-1.5 text-xs" :disabled="bannerBusy" @click="pickBanner">
              {{ bannerBusy ? t('instanceSettings.general.bannerPicking') : inst.bannerPath ? t('instanceSettings.general.bannerChange') : t('instanceSettings.general.bannerPick') }}
            </button>
            <button v-if="inst.bannerPath" class="btn btn-ghost py-1.5 text-xs hover:text-redstone-300" @click="removeBanner">{{ t('common.actions.remove') }}</button>
          </div>
        </div>
      </SettingRow>

      <SettingRow :title="t('instanceSettings.general.groupTitle')" :description="t('instanceSettings.general.groupDescription')" stacked>
        <div class="flex flex-wrap items-center gap-1.5">
          <button class="group-chip" :class="{ 'group-chip-on': !inst.group }" @click="setGroup(null)">{{ t('instanceSettings.general.groupNone') }}</button>
          <button v-for="gr in groups" :key="gr" class="group-chip" :class="{ 'group-chip-on': inst.group === gr }" @click="setGroup(gr)">{{ gr }}</button>
          <form v-if="newGroup !== null" class="flex items-center gap-1.5" @submit.prevent="setGroup(newGroup)">
            <input v-model="newGroup" class="field h-8 w-44 py-0 text-xs" maxlength="32" :placeholder="t('instanceSettings.general.groupNamePlaceholder')" :aria-label="t('instanceSettings.general.groupNameLabel')" autofocus />
            <button type="submit" class="btn btn-primary h-8 py-0 text-xs" :disabled="!newGroup.trim()">{{ t('instanceSettings.general.groupCreate') }}</button>
            <button type="button" class="btn btn-ghost h-8 py-0 text-xs" @click="newGroup = null">{{ t('common.actions.cancel') }}</button>
          </form>
          <button v-else class="group-chip border-dashed" @click="newGroup = ''">{{ t('instanceSettings.general.groupNew') }}</button>
        </div>
      </SettingRow>

      <SettingRow :title="t('instanceSettings.general.channelTitle')" :description="t('instanceSettings.general.channelDescription')" stacked>
        <div class="grid grid-cols-3 gap-2">
          <button
            v-for="c in (['release', 'beta', 'alpha'] as const)"
            :key="c"
            class="rounded-lg border px-3 py-2.5 text-left transition-colors"
            :class="channel === c ? 'border-redstone-500 bg-redstone-900/40' : 'border-base-700 hover:border-base-600'"
            :aria-pressed="channel === c"
            @click="channel = c"
          >
            <span class="block text-sm font-semibold">{{ t(`instanceSettings.general.channel.${c}.label`) }}</span>
            <span class="block text-xs text-base-400">{{ t(`instanceSettings.general.channel.${c}.hint`) }}</span>
          </button>
        </div>
      </SettingRow>

      <SettingRow :title="t('instanceSettings.general.duplicateTitle')" :description="t('instanceSettings.general.duplicateDescription')">
        <button class="btn btn-ghost" :disabled="duplicating || running" @click="duplicate">{{ duplicating ? t('instanceSettings.general.duplicating') : t('instanceSettings.general.duplicateTitle') }}</button>
      </SettingRow>

      <SettingRow :title="t('instanceSettings.general.exportTitle')" :description="t('instanceSettings.general.exportDescription')">
        <button class="btn btn-ghost" :disabled="running" @click="exporting = true">{{ t('common.actions.export') }}</button>
      </SettingRow>

      <SettingRow :title="t('instanceSettings.general.deleteTitle')" :description="t('instanceSettings.general.deleteDescription')" danger>
        <button class="btn btn-danger" :disabled="running" @click="deleting = true">{{ t('instanceSettings.general.deleteTitle') }}</button>
      </SettingRow>
    </div>

    <!-- Installation ---------------------------------------------------------- -->
    <div v-else-if="active === 'installation'">
      <h3 class="section-heading">{{ t('instanceSettings.sections.installation') }}</h3>
      <div class="card divide-y divide-base-800 bg-base-850">
        <div class="flex items-center justify-between px-4 py-3">
          <span class="text-sm text-base-400">{{ t('instanceSettings.installation.platform') }}</span>
          <span class="flex items-center gap-2 text-sm font-medium"><span class="size-2 rounded-full" :style="{ background: loaderColors[inst.loader.kind] }" />{{ loaderLabels[inst.loader.kind] }}</span>
        </div>
        <div class="flex items-center justify-between px-4 py-3">
          <span class="text-sm text-base-400">{{ t('common.labels.gameVersion') }}</span>
          <span class="font-mono text-sm">{{ inst.gameVersion }}</span>
        </div>
        <div v-if="inst.loader.kind !== 'vanilla'" class="flex items-center justify-between px-4 py-3">
          <span class="text-sm text-base-400">{{ t('instanceSettings.installation.loaderVersion', { loader: loaderLabels[inst.loader.kind] }) }}</span>
          <span class="text-right font-mono text-sm">
            <template v-if="inst.loader.version">{{ inst.loader.version }}</template>
            <template v-else>
              <span class="font-sans text-base-400">{{ t('instanceSettings.installation.latestStable') }}</span>
              <span v-if="resolvedLoader"> {{ t('instanceSettings.installation.currently', { version: resolvedLoader }) }}</span>
            </template>
          </span>
        </div>
      </div>
      <div class="mt-3 flex items-start gap-3">
        <p class="flex-1 text-xs leading-relaxed text-base-400">{{ t('instanceSettings.installation.changeHint') }}</p>
        <button class="btn btn-primary shrink-0" :disabled="running" @click="changingVersion = true">{{ t('common.actions.edit') }}</button>
      </div>

      <h3 class="section-heading mt-6">TRS</h3>
      <SettingRow
        v-if="inst.loader.kind === 'vanilla'"
        :title="t('instanceSettings.installation.boostTitle')"
        :description="t('instanceSettings.installation.boostDescription')"
      >
        <ToggleSwitch v-model="boost" :label="t('instanceSettings.installation.boostTitle')" />
      </SettingRow>
      <SettingRow title="TRS Client" :description="t('instanceSettings.installation.clientDescription')">
        <ToggleSwitch v-model="trsClient" label="TRS Client" />
      </SettingRow>
      <SettingRow :title="t('instanceSettings.installation.tuningTitle')" :description="t('instanceSettings.installation.tuningDescription')">
        <select v-model="tuning" class="field w-44 py-1.5" :aria-label="t('instanceSettings.installation.tuningTitle')">
          <option value="global">{{ t('instanceSettings.installation.tuningGlobal', { state: g?.performanceTuning === false ? t('instanceSettings.installation.tuningOff') : t('instanceSettings.installation.tuningOn') }) }}</option>
          <option value="on">{{ t('instanceSettings.installation.tuningOn') }}</option>
          <option value="off">{{ t('instanceSettings.installation.tuningOff') }}</option>
        </select>
      </SettingRow>

      <h3 class="section-heading mt-6">{{ t('instanceSettings.installation.maintenance') }}</h3>
      <div v-if="repairing" class="mb-3 flex items-center gap-3">
        <RedstoneWire class="flex-1" :percent="repairing.percent" :segments="40" />
        <span class="display text-sm text-redstone-300 tabular-nums">{{ Math.floor(repairing.percent) }} %</span>
      </div>
      <SettingRow :title="t('instanceSettings.installation.repairTitle')" :description="t('instanceSettings.installation.repairDescription')">
        <button class="btn btn-ghost" :disabled="!!repairing || running" @click="repair('repair')">{{ t('instanceSettings.installation.repairTitle') }}</button>
      </SettingRow>
      <SettingRow :title="t('instanceSettings.installation.reinstallTitle')" :description="t('instanceSettings.installation.reinstallDescription')">
        <button class="btn btn-ghost" :disabled="!!repairing || running" @click="confirmReinstall = true">{{ t('instanceSettings.installation.reinstallTitle') }}</button>
      </SettingRow>
    </div>

    <!-- Fenster ----------------------------------------------------------------- -->
    <div v-else-if="active === 'window'">
      <h3 class="section-heading">{{ t('instanceSettings.sections.window') }}</h3>
      <SettingRow :title="t('instanceSettings.window.customTitle')" :description="t('instanceSettings.window.customDescription')">
        <ToggleSwitch v-model="customWindow" :label="t('instanceSettings.window.customTitle')" />
      </SettingRow>
      <SettingRow :title="t('instanceSettings.window.fullscreenTitle')" :description="t('instanceSettings.window.fullscreenDescription')">
        <ToggleSwitch v-model="fullscreen" :label="t('instanceSettings.window.fullscreenTitle')" :disabled="!customWindow" />
      </SettingRow>
      <SettingRow :title="t('instanceSettings.window.widthTitle')" :description="t('instanceSettings.window.widthDescription')">
        <input v-model.number="width" type="number" min="320" max="16384" class="field w-32 font-mono" :placeholder="String(g?.resolution.width ?? 1280)" :disabled="!customWindow || fullscreen" :aria-label="t('instanceSettings.window.widthTitle')" />
      </SettingRow>
      <SettingRow :title="t('instanceSettings.window.heightTitle')" :description="t('instanceSettings.window.heightDescription')">
        <input v-model.number="height" type="number" min="240" max="16384" class="field w-32 font-mono" :placeholder="String(g?.resolution.height ?? 720)" :disabled="!customWindow || fullscreen" :aria-label="t('instanceSettings.window.heightTitle')" />
      </SettingRow>
    </div>

    <!-- Java & Arbeitsspeicher ------------------------------------------------------ -->
    <div v-else-if="active === 'java'">
      <h3 class="section-heading">{{ t('instanceSettings.sections.java') }}</h3>
      <SettingRow :title="t('instanceSettings.java.customTitle')" :description="t('instanceSettings.java.customDescription')">
        <ToggleSwitch v-model="customJava" :label="t('instanceSettings.java.customTitle')" />
      </SettingRow>
      <SettingRow
        :title="t('instanceSettings.java.installTitle')"
        :description="globalJava ? t('instanceSettings.java.installDescriptionGlobal', { major: neededJava }) : t('instanceSettings.java.installDescriptionAuto', { major: neededJava })"
        stacked
      >
        <JavaPathField v-model="javaPath" :placeholder="globalJava ?? t('instanceSettings.java.autoPlaceholder', { major: neededJava })" :expected-major="neededJava" :disabled="!customJava" input-id="is-java" />
      </SettingRow>
      <SettingRow :title="t('instanceSettings.java.memoryTitle')" :description="t('instanceSettings.java.memoryDescription')" stacked>
        <div class="flex items-center gap-4">
          <input v-model.number="memory" type="range" min="1024" :max="maxRam" step="512" class="flex-1 accent-redstone-500" :disabled="!customJava" :aria-label="t('instanceSettings.java.memoryTitle')" />
          <div class="flex items-center gap-1.5">
            <input v-model.number="memory" type="number" min="512" max="131072" step="256" class="field w-28 font-mono" :disabled="!customJava" :aria-label="t('instanceSettings.java.memoryMbLabel')" />
            <span class="text-xs text-base-400">MB</span>
          </div>
        </div>
        <p class="mt-1 text-xs text-base-600">{{ formatMemory(memory) }}</p>
      </SettingRow>
      <SettingRow :title="t('instanceSettings.java.jvmArgsTitle')" :description="t('instanceSettings.java.jvmArgsDescription')" stacked>
        <div class="flex gap-2">
          <input v-model="jvmArgs" class="field font-mono text-xs" maxlength="4096" :placeholder="g?.jvmArgs || t('instanceSettings.java.jvmArgsNone')" spellcheck="false" :disabled="!customJava" :aria-label="t('instanceSettings.java.jvmArgsTitle')" />
          <button class="btn btn-ghost shrink-0 py-1.5 text-xs" :disabled="!customJava" :title="t('instanceSettings.java.optimizedTitle')" @click="jvmArgs = optimizedJvmArgs">{{ t('instanceSettings.java.optimized') }}</button>
        </div>
      </SettingRow>
      <SettingRow :title="t('instanceSettings.java.envTitle')" :description="t('instanceSettings.java.envDescription')" stacked>
        <EnvEditor v-model="env" :disabled="!customJava" :placeholder="g?.env" />
      </SettingRow>
    </div>

    <!-- Start-Hooks -------------------------------------------------------------------- -->
    <div v-else-if="active === 'hooks'">
      <h3 class="section-heading">{{ t('instanceSettings.sections.hooks') }}</h3>
      <i18n-t keypath="instanceSettings.hooks.intro" tag="p" scope="global" class="mb-2 text-xs leading-relaxed text-base-400">
        <template #cmd><code class="rounded bg-base-800 px-1 font-mono text-lamp-300">cmd /C</code></template>
        <template #id><code class="font-mono text-base-200">%INST_ID%</code></template>
        <template #name><code class="font-mono text-base-200">%INST_NAME%</code></template>
        <template #dir><code class="font-mono text-base-200">%INST_DIR%</code></template>
        <template #mcDir><code class="font-mono text-base-200">%INST_MC_DIR%</code></template>
        <template #java><code class="font-mono text-base-200">%INST_JAVA%</code></template>
      </i18n-t>
      <SettingRow :title="t('instanceSettings.hooks.customTitle')" :description="t('instanceSettings.hooks.customDescription')">
        <ToggleSwitch v-model="customHooks" :label="t('instanceSettings.hooks.customTitle')" />
      </SettingRow>
      <SettingRow :title="t('instanceSettings.hooks.preLaunchTitle')" :description="t('instanceSettings.hooks.preLaunchDescription')" stacked>
        <input v-model="preLaunch" class="field font-mono text-xs" maxlength="1024" :placeholder="g?.hooks.preLaunch || t('instanceSettings.hooks.noCommand')" spellcheck="false" :disabled="!customHooks" :aria-label="t('instanceSettings.hooks.preLaunchLabel')" />
      </SettingRow>
      <SettingRow :title="t('instanceSettings.hooks.wrapperTitle')" :description="t('instanceSettings.hooks.wrapperDescription')" stacked>
        <input v-model="wrapper" class="field font-mono text-xs" maxlength="1024" :placeholder="g?.hooks.wrapper || t('instanceSettings.hooks.noWrapper')" spellcheck="false" :disabled="!customHooks" :aria-label="t('instanceSettings.hooks.wrapperLabel')" />
      </SettingRow>
      <SettingRow :title="t('instanceSettings.hooks.postExitTitle')" :description="t('instanceSettings.hooks.postExitDescription')" stacked>
        <input v-model="postExit" class="field font-mono text-xs" maxlength="1024" :placeholder="g?.hooks.postExit || t('instanceSettings.hooks.noCommand')" spellcheck="false" :disabled="!customHooks" :aria-label="t('instanceSettings.hooks.postExitLabel')" />
      </SettingRow>
    </div>

    <!-- Synchronisierung ------------------------------------------------------------------ -->
    <div v-else-if="active === 'sync'">
      <h3 class="section-heading">{{ t('instanceSettings.sections.sync') }}</h3>
      <p class="mb-2 text-xs leading-relaxed text-base-400">{{ t('instanceSettings.sync.intro') }}</p>
      <SettingRow v-for="item in syncItemList()" :key="item.key" :title="item.label" :description="g?.sync[item.key] ? item.description : t('instanceSettings.sync.globallyOff')">
        <ToggleSwitch
          :model-value="!!g?.sync[item.key] && !syncSeparate.includes(item.key)"
          :label="t('instanceSettings.sync.toggleLabel', { item: item.label })"
          :disabled="!g?.sync[item.key]"
          @update:model-value="toggleSync(item.key, $event)"
        />
      </SettingRow>
    </div>
  </SettingsShell>

  <ChangeVersionDialog v-if="changingVersion" :instance="inst" @close="changingVersion = false" @changed="onVersionChanged" />

  <ExportPackDialog v-if="exporting" :instance="inst" @close="exporting = false" />

  <BaseDialog v-if="confirmReinstall" :title="t('instanceSettings.installation.reinstallConfirmTitle')" @close="confirmReinstall = false">
    <p class="text-sm text-base-200">{{ t('instanceSettings.installation.reinstallConfirmText') }}</p>
    <template #actions>
      <button class="btn btn-ghost" @click="confirmReinstall = false">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" @click="repair('reinstall')">{{ t('instanceSettings.installation.reinstallTitle') }}</button>
    </template>
  </BaseDialog>

  <BaseDialog v-if="deleting" :title="t('library.delete.title')" @close="deleting = false">
    <i18n-t keypath="library.delete.text" tag="p" scope="global" class="text-sm text-base-200">
      <template #name><strong class="text-base-50">{{ inst.name }}</strong></template>
    </i18n-t>
    <template #actions>
      <button class="btn btn-ghost" @click="deleting = false">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-danger" :disabled="deleteBusy" @click="confirmDelete">
        {{ deleteBusy ? t('library.delete.deleting') : t('library.delete.confirm') }}
      </button>
    </template>
  </BaseDialog>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.section-heading {
  @apply mb-2 text-base font-semibold text-base-50;
}
.group-chip {
  @apply rounded-full border border-base-700 px-3 py-1 text-xs font-medium text-base-200 transition-colors hover:border-base-600 hover:text-base-50;
}
.group-chip-on {
  @apply border-redstone-500 bg-redstone-500 text-white hover:text-white;
}
</style>
