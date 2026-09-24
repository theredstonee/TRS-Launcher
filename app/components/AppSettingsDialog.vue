<script setup lang="ts">
import type { Accent, ClientModStatus, Settings, StorageStats, Theme } from '~/types'
import type { ShellSection } from '~/components/SettingsShell.vue'
import type { Locale } from '~/utils/i18n'

// Globale Einstellungen im Stil der Modrinth App. Alles speichert
// automatisch; Darstellung wirkt sofort als Vorschau.
const store = useSettingsStore()
const accounts = useAccountsStore()
const onboarding = useOnboardingStore()
const toasts = useToasts()

// Namen und Gruppen sind Getter in sections.ts – sie folgen der eingestellten Sprache.
const sections: ShellSection[] = appSettingsSections
const active = computed({
  get: () => (appSettingsSections.some((s) => s.key === store.dialog) ? store.dialog! : 'appearance'),
  set: (v: string) => (store.dialog = v),
})

const form = ref<Settings | null>(null)
const info = ref<{ version: string; os: string; dataDir: string } | null>(null)
const clientMod = ref<ClientModStatus | null>(null)
/** Speicherstatus: Fehler als fertiger Text, „Speichere/Gespeichert“ als Schlüssel (folgt einem Sprachwechsel). */
const status = ref<{ ok: boolean; text: string } | { ok: true; state: 'saving' | 'saved' } | null>(null)
const statusView = computed(() => {
  const s = status.value
  if (!s) return null
  return 'state' in s ? { ok: true, text: t(`common.status.${s.state}`) } : s
})
let lastSaved = ''
let timer: ReturnType<typeof setTimeout> | undefined

onMounted(async () => {
  try {
    form.value = structuredClone(toRaw(store.current ?? (await store.load())))
    lastSaved = JSON.stringify(form.value)
    info.value = await backend.appInfo()
  } catch (e) {
    status.value = { ok: false, text: errorMessage(e) }
  }
  if (!accounts.loaded) accounts.load().catch(() => {})
  backend.clientModStatus().then((s) => (clientMod.value = s)).catch(() => {})
})

// Der TRS Client aktualisiert sich still über seinen eigenen Kanal – hier nur der Stand.
const clientModLine = computed(() => {
  const s = clientMod.value
  if (!s) return null
  if (s.update) return t('settings.footer.clientModUpdate', { version: s.update })
  return s.bundled ? t('settings.footer.clientModCurrent', { version: s.bundled }) : null
})

// Darstellung sofort anwenden, gespeichert wird gleich danach.
watch(() => form.value?.ui, (ui) => ui && applyAppearance(ui), { deep: true })

watch(
  () => JSON.stringify(form.value),
  (json) => {
    clearTimeout(timer)
    if (!form.value || json === lastSaved) return
    timer = setTimeout(save, 500)
  },
)

async function save() {
  timer = undefined
  if (!form.value) return
  const parsed = settingsSchema.safeParse(form.value)
  if (!parsed.success) {
    status.value = { ok: false, text: firstIssue(parsed.error) }
    return
  }
  const json = JSON.stringify(form.value)
  status.value = { ok: true, state: 'saving' }
  try {
    await store.save(parsed.data as Settings)
    lastSaved = json
    status.value = { ok: true, state: 'saved' }
  } catch (e) {
    status.value = { ok: false, text: errorMessage(e) }
  }
}

async function close() {
  if (timer) {
    clearTimeout(timer)
    await save()
  }
  // Nicht gespeicherte Vorschau zurücknehmen.
  applyAppearance(store.current?.ui)
  store.dialog = null
}
onBeforeUnmount(() => clearTimeout(timer))

// --- Aussehen -----------------------------------------------------------------
// Namen kommen beim Rendern aus settings.appearance.themes/accents.
const themes: { key: Theme; bg: string; panel: string; line: string }[] = [
  { key: 'dark', bg: '#111116', panel: '#1d1d26', line: '#333343' },
  { key: 'oled', bg: '#000000', panel: '#0c0c10', line: '#24242e' },
  { key: 'light', bg: '#eeeef3', panel: '#ffffff', line: '#d4d4df' },
  { key: 'system', bg: 'linear-gradient(135deg,#111116 50%,#eeeef3 50%)', panel: '#1d1d26', line: '#333343' },
]
const accents: { key: Accent; color: string }[] = [
  { key: 'redstone', color: '#e0281e' },
  { key: 'lamp', color: '#e0900c' },
  { key: 'emerald', color: '#17a34a' },
  { key: 'lapis', color: '#3563e9' },
  { key: 'amethyst', color: '#9b4ddf' },
]

// --- Sprache ------------------------------------------------------------------
// Sofort umschalten und speichern (die Oberfläche wechselt ohne Neustart).
const language = computed<Locale>({
  get: () => form.value?.ui.language ?? currentLocale.value,
  set: (code) => {
    if (!form.value) return
    form.value.ui.language = code
    void setLocale(code)
  },
})

// --- Profil -------------------------------------------------------------------
async function activateAccount(id: string) {
  try {
    await accounts.setActive(id)
  } catch (e) {
    toasts.error(e)
  }
}

// --- Java ---------------------------------------------------------------------
// Hinweis je Slot: settings.java.slots.<key>
const javaSlots = [
  { major: 8, key: 'java8' },
  { major: 17, key: 'java17' },
  { major: 21, key: 'java21' },
  { major: 25, key: 'java25' },
] as const
function javaModel(key: (typeof javaSlots)[number]['key']) {
  return computed({
    get: () => form.value?.java[key] ?? '',
    set: (v: string) => {
      if (form.value) form.value.java[key] = v.trim() || null
    },
  })
}
const javaModels = Object.fromEntries(javaSlots.map((s) => [s.key, javaModel(s.key)])) as Record<(typeof javaSlots)[number]['key'], WritableComputedRef<string>>
const globalJava = computed({
  get: () => form.value?.javaPath ?? '',
  set: (v: string) => {
    if (form.value) form.value.javaPath = v.trim() || null
  },
})
// Java lädt als Aufgabe weiter, auch wenn der Dialog zugeht.
const tasks = useTasksStore()
const installing = computed(() => {
  const task = tasks.active.find((a) => a.kind === 'java')
  return task ? { major: Number(task.tag), percent: task.percent ?? 0 } : null
})
let dialogOpen = true
onBeforeUnmount(() => (dialogOpen = false))
function installJava(slot: (typeof javaSlots)[number]) {
  tasks.run(
    {
      key: taskKey('java', String(slot.major)),
      kind: 'java',
      title: `Java ${slot.major}`,
      stage: t('settings.java.taskStage'),
      tag: String(slot.major),
      cancellable: true,
      pausable: true,
      doneText: t('settings.java.taskDone', { major: slot.major }),
    },
    async (ctx) => {
      const path = await backend.installJava(slot.major, (p) => ctx.progress(p), ctx.taskId)
      if (dialogOpen && form.value) {
        // Offener Dialog: über das Formular (speichert selbst).
        javaModels[slot.key].value = path
      } else {
        const current = store.current ?? (await store.load())
        await store.save({ ...current, java: { ...current.java, [slot.key]: path } })
      }
      return path
    },
  )
}

// --- Speicher -----------------------------------------------------------------
const stats = ref<StorageStats | null>(null)
const statsBusy = ref(false)
const verifying = ref(false)
const confirmClean = ref(false)
const cleaning = ref(false)
async function loadStats() {
  statsBusy.value = true
  try {
    stats.value = await backend.storageStats()
  } catch (e) {
    toasts.error(e)
  } finally {
    statsBusy.value = false
  }
}
watch(active, (a) => a === 'storage' && !stats.value && loadStats(), { immediate: true })
const bars = computed(() => {
  const s = stats.value
  if (!s) return []
  const shared = s.libraries + s.assets + s.versions - s.unused + s.java + s.shared
  const parts = [
    { key: 'instances', label: t('settings.storage.instances'), bytes: s.instances, color: 'bg-redstone-500' },
    { key: 'shared', label: t('settings.storage.sharedFiles'), bytes: shared, color: 'bg-lamp-400' },
    { key: 'unused', label: t('settings.storage.unused'), bytes: s.unused, color: 'bg-base-600' },
  ]
  const total = parts.reduce((sum, p) => sum + p.bytes, 0) || 1
  return parts.map((p) => ({ ...p, share: (p.bytes / total) * 100 }))
})
async function verify() {
  verifying.value = true
  try {
    const r = await backend.verifyStorage()
    toasts.ok(
      r.removed
        ? t('settings.storage.verifyRemoved', { checked: r.checked, removed: r.removed })
        : t('settings.storage.verifyOk', { checked: r.checked }),
    )
  } catch (e) {
    toasts.error(e)
  } finally {
    verifying.value = false
  }
}
async function clean() {
  confirmClean.value = false
  cleaning.value = true
  try {
    const freed = await backend.cleanUnusedStorage()
    toasts.ok(t('settings.storage.freed', { size: formatBytes(freed) }))
    await loadStats()
  } catch (e) {
    toasts.error(e)
  } finally {
    cleaning.value = false
  }
}
function openDataDir() {
  backend.openDataDir().catch(() => {})
}

// --- Netzwerk -----------------------------------------------------------------
const firewall = ref<{ total: number; missing: number } | null>(null)
const firewallBusy = ref(false)
watch(active, (a) => a === 'network' && !firewall.value && backend.firewallStatus().then((s) => (firewall.value = s)).catch(() => {}), {
  immediate: true,
})
async function allowFirewall() {
  firewallBusy.value = true
  try {
    const n = await backend.firewallAllowAll()
    toasts.ok(n ? t('settings.network.allowed', n) : t('settings.network.noJava'))
    firewall.value = await backend.firewallStatus()
  } catch (e) {
    if (!isCancelled(e)) toasts.error(e)
  } finally {
    firewallBusy.value = false
  }
}
</script>

<template>
  <SettingsShell v-model="active" :title="t('settings.title')" :sections="sections" :status="statusView" @close="close">
    <template #nav-footer>
      <p v-if="info">TRS Launcher v{{ info.version }}</p>
      <p v-if="info">{{ info.os }}</p>
      <p v-if="clientModLine">{{ clientModLine }}</p>
    </template>

    <div v-if="!form" class="space-y-3">
      <div v-for="i in 4" :key="i" class="skeleton h-14" />
    </div>

    <!-- Aussehen --------------------------------------------------------------- -->
    <div v-else-if="active === 'appearance'">
      <h3 class="section-heading">{{ t('settings.appearance.colorScheme') }}</h3>
      <div class="grid grid-cols-4 gap-3 border-b border-base-800 pb-5">
        <button
          v-for="theme in themes"
          :key="theme.key"
          class="overflow-hidden rounded-xl border-2 text-left transition-colors"
          :class="form.ui.theme === theme.key ? 'border-redstone-500' : 'border-base-800 hover:border-base-700'"
          :aria-pressed="form.ui.theme === theme.key"
          @click="form.ui.theme = theme.key"
        >
          <div class="relative h-20 p-2" :style="{ background: theme.bg }">
            <div class="absolute inset-y-2 left-2 w-5 rounded" :style="{ background: theme.panel, border: `1px solid ${theme.line}` }" />
            <div class="absolute top-2 right-2 left-9 h-3 rounded" :style="{ background: theme.panel, border: `1px solid ${theme.line}` }" />
            <div class="absolute right-2 bottom-2 left-9 h-8 rounded" :style="{ background: theme.panel, border: `1px solid ${theme.line}` }" />
            <div class="absolute bottom-4 left-11 h-2 w-8 rounded-full bg-redstone-500" />
          </div>
          <div class="flex items-center gap-2 bg-base-850 px-3 py-2 text-sm font-medium">
            <span class="grid size-4 place-items-center rounded-full border-2" :class="form.ui.theme === theme.key ? 'border-redstone-500' : 'border-base-600'">
              <span v-if="form.ui.theme === theme.key" class="size-2 rounded-full bg-redstone-500" />
            </span>
            {{ t(`settings.appearance.themes.${theme.key}`) }}
          </div>
        </button>
      </div>

      <SettingRow :title="t('settings.appearance.accentTitle')" :description="t('settings.appearance.accentDescription')">
        <div class="flex gap-2">
          <button
            v-for="a in accents"
            :key="a.key"
            class="size-7 rounded-full ring-offset-2 ring-offset-base-900 transition-transform hover:scale-110"
            :class="form.ui.accent === a.key ? 'ring-2 ring-base-50' : ''"
            :style="{ background: a.color }"
            :title="t(`settings.appearance.accents.${a.key}`)"
            :aria-label="t('settings.appearance.accentLabel', { name: t(`settings.appearance.accents.${a.key}`) })"
            :aria-pressed="form.ui.accent === a.key"
            @click="form.ui.accent = a.key"
          />
        </div>
      </SettingRow>
      <SettingRow :title="t('settings.appearance.advancedRenderingTitle')" :description="t('settings.appearance.advancedRenderingDescription')">
        <ToggleSwitch v-model="form.ui.advancedRendering" :label="t('settings.appearance.advancedRenderingTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.appearance.animatedBackgroundTitle')" :description="t('settings.appearance.animatedBackgroundDescription')">
        <ToggleSwitch v-model="form.ui.animatedBackground" :label="t('settings.appearance.animatedBackgroundTitle')" />
      </SettingRow>
    </div>

    <!-- Funktionen ------------------------------------------------------------- -->
    <div v-else-if="active === 'features'">
      <h3 class="section-heading">{{ t('settings.features.instancePage') }}</h3>
      <SettingRow :title="t('settings.features.historyTabTitle')" :description="t('settings.features.historyTabDescription')">
        <ToggleSwitch v-model="form.ui.historyTab" :label="t('settings.features.historyTabTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.features.screenshotsTabTitle')" :description="t('settings.features.screenshotsTabDescription')">
        <ToggleSwitch v-model="form.ui.screenshotsTab" :label="t('settings.features.screenshotsTabTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.features.worldsTabTitle')" :description="t('settings.features.worldsTabDescription')">
        <ToggleSwitch v-model="form.ui.worldsTab" :label="t('settings.features.worldsTabTitle')" />
      </SettingRow>
      <h3 class="section-heading mt-6">{{ t('settings.features.sidebar') }}</h3>
      <SettingRow :title="t('settings.features.sidebarRecentTitle')" :description="t('settings.features.sidebarRecentDescription')">
        <ToggleSwitch v-model="form.ui.sidebarRecent" :label="t('settings.features.sidebarRecentTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.features.sidebarAccountTitle')" :description="t('settings.features.sidebarAccountDescription')">
        <ToggleSwitch v-model="form.ui.sidebarAccount" :label="t('settings.features.sidebarAccountTitle')" />
      </SettingRow>
    </div>

    <!-- Verhalten -------------------------------------------------------------- -->
    <div v-else-if="active === 'behavior'">
      <h3 class="section-heading">{{ t('settings.behavior.title') }}</h3>
      <SettingRow :title="t('settings.behavior.closeOnLaunchTitle')" :description="t('settings.behavior.closeOnLaunchDescription')">
        <ToggleSwitch v-model="form.closeOnLaunch" :label="t('settings.behavior.closeOnLaunchTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.behavior.compactLibraryTitle')" :description="t('settings.behavior.compactLibraryDescription')">
        <ToggleSwitch v-model="form.ui.compactLibrary" :label="t('settings.behavior.compactLibraryTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.behavior.showPlayTimeTitle')" :description="t('settings.behavior.showPlayTimeDescription')">
        <ToggleSwitch v-model="form.ui.showPlayTime" :label="t('settings.behavior.showPlayTimeTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.behavior.showSnapshotsTitle')" :description="t('settings.behavior.showSnapshotsDescription')">
        <ToggleSwitch v-model="form.showSnapshots" :label="t('settings.behavior.showSnapshotsTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.behavior.restartOnboardingTitle')" :description="t('settings.behavior.restartOnboardingDescription')">
        <button class="btn btn-ghost" @click="close(); onboarding.restart()">{{ t('common.actions.start') }}</button>
      </SettingRow>
    </div>

    <!-- Sprache -------------------------------------------------------------- -->
    <div v-else-if="active === 'language'">
      <h3 class="section-heading">{{ t('language.title') }}</h3>
      <p class="mb-4 text-xs leading-relaxed text-base-400">{{ t('language.changeHint') }}</p>
      <LanguagePicker v-model="language" />
    </div>

    <!-- Profil ---------------------------------------------------------------- -->
    <div v-else-if="active === 'profile'">
      <h3 class="section-heading">{{ t('settings.profile.title') }}</h3>
      <ul v-if="accounts.items.length" class="divide-y divide-base-800 rounded-xl border border-base-800">
        <li v-for="a in accounts.items" :key="a.id" class="flex items-center gap-3 px-4 py-3">
          <SkinHead :skin-url="a.skinUrl" :name="a.name" :size="36" />
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-semibold">{{ a.name }}</p>
            <p class="text-xs text-base-400">{{ t('settings.profile.added', { time: formatRelative(a.addedAt) }) }}</p>
          </div>
          <span v-if="a.active" class="badge bg-ok/10 text-ok">{{ t('common.status.active') }}</span>
          <button v-else class="btn btn-ghost py-1.5 text-xs" @click="activateAccount(a.id)">{{ t('settings.profile.use') }}</button>
        </li>
      </ul>
      <p v-else class="text-sm text-base-400">{{ t('settings.profile.none') }}</p>
      <NuxtLink to="/accounts" class="btn btn-primary mt-4" @click="close">{{ t('settings.profile.manage') }}</NuxtLink>
    </div>

    <!-- Datenschutz -------------------------------------------------------------- -->
    <div v-else-if="active === 'privacy'">
      <h3 class="section-heading">{{ t('settings.privacy.title') }}</h3>
      <SettingRow :title="t('settings.privacy.logUploadTitle')" :description="t('settings.privacy.logUploadDescription')">
        <ToggleSwitch v-model="form.allowLogUpload" :label="t('settings.privacy.logUploadTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.privacy.telemetryTitle')" :description="t('settings.privacy.telemetryDescription')">
        <ToggleSwitch :model-value="false" :label="t('settings.privacy.telemetryTitle')" disabled />
      </SettingRow>
      <TrsPrivacySettings />
    </div>

    <!-- Standard-Einstellungen ------------------------------------------------------ -->
    <div v-else-if="active === 'defaults'">
      <h3 class="section-heading">{{ t('settings.defaults.title') }}</h3>
      <p class="mb-2 text-xs text-base-400">{{ t('settings.defaults.hint') }}</p>
      <SettingRow :title="t('settings.defaults.memoryTitle')" :description="t('settings.defaults.memoryDescription')" stacked>
        <div class="flex items-center gap-4">
          <input v-model.number="form.maxMemoryMb" type="range" min="1024" max="32768" step="512" class="flex-1 accent-redstone-500" :aria-label="t('settings.defaults.maxMemoryLabel')" />
          <div class="flex items-center gap-1.5">
            <input v-model.number="form.maxMemoryMb" type="number" min="512" max="131072" step="256" class="field w-28 font-mono" :aria-label="t('settings.defaults.maxMemoryMbLabel')" />
            <span class="text-xs text-base-400">MB</span>
          </div>
        </div>
        <p class="mt-1 text-xs text-base-600">{{ formatMemory(form.maxMemoryMb) }}</p>
      </SettingRow>
      <SettingRow :title="t('settings.defaults.minMemoryTitle')" :description="t('settings.defaults.minMemoryDescription')">
        <input v-model.number="form.minMemoryMb" type="number" min="128" step="128" class="field w-28 font-mono" :aria-label="t('settings.defaults.minMemoryTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.defaults.jvmArgsTitle')" :description="t('settings.defaults.jvmArgsDescription')" stacked>
        <div class="flex gap-2">
          <input
            v-model="form.jvmArgs"
            class="field font-mono text-xs"
            maxlength="4096"
            :placeholder="t('common.status.none')"
            spellcheck="false"
            :aria-label="t('settings.defaults.jvmArgsTitle')"
          />
          <button class="btn btn-ghost shrink-0 py-1.5 text-xs" @click="form.jvmArgs = optimizedJvmArgs">{{ t('settings.defaults.optimizedArgs') }}</button>
        </div>
      </SettingRow>
      <SettingRow :title="t('settings.defaults.envTitle')" :description="t('settings.defaults.envDescription')" stacked>
        <EnvEditor v-model="form.env" />
      </SettingRow>
      <SettingRow :title="t('settings.defaults.fullscreenTitle')" :description="t('settings.defaults.fullscreenDescription')">
        <ToggleSwitch v-model="form.fullscreen" :label="t('settings.defaults.fullscreenTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.defaults.resolutionTitle')" :description="t('settings.defaults.resolutionDescription')">
        <input v-model.number="form.resolution.width" type="number" min="320" class="field w-24 font-mono" :aria-label="t('settings.defaults.windowWidth')" />
        <span class="text-base-600">×</span>
        <input v-model.number="form.resolution.height" type="number" min="240" class="field w-24 font-mono" :aria-label="t('settings.defaults.windowHeight')" />
      </SettingRow>
      <SettingRow :title="t('settings.defaults.dedicatedGpuTitle')" :description="t('settings.defaults.dedicatedGpuDescription')">
        <ToggleSwitch v-model="form.preferDedicatedGpu" :label="t('settings.defaults.dedicatedGpuTitle')" />
      </SettingRow>

      <h3 class="section-heading mt-6">{{ t('settings.hooks.title') }}</h3>
      <SettingRow :title="t('settings.hooks.preLaunchTitle')" :description="t('settings.hooks.preLaunchDescription')" stacked>
        <input
          v-model="form.hooks.preLaunch"
          class="field font-mono text-xs"
          maxlength="1024"
          :placeholder="t('settings.hooks.noCommand')"
          spellcheck="false"
          :aria-label="t('settings.hooks.preLaunchLabel')"
        />
      </SettingRow>
      <SettingRow :title="t('settings.hooks.wrapperTitle')" :description="t('settings.hooks.wrapperDescription')" stacked>
        <input
          v-model="form.hooks.wrapper"
          class="field font-mono text-xs"
          maxlength="1024"
          :placeholder="t('settings.hooks.wrapperPlaceholder')"
          spellcheck="false"
          :aria-label="t('settings.hooks.wrapperLabel')"
        />
      </SettingRow>
      <SettingRow :title="t('settings.hooks.postExitTitle')" :description="t('settings.hooks.postExitDescription')" stacked>
        <input
          v-model="form.hooks.postExit"
          class="field font-mono text-xs"
          maxlength="1024"
          :placeholder="t('settings.hooks.noCommand')"
          spellcheck="false"
          :aria-label="t('settings.hooks.postExitLabel')"
        />
      </SettingRow>

      <h3 class="section-heading mt-6">{{ t('settings.sync.title') }}</h3>
      <p class="mb-1 text-xs leading-relaxed text-base-400">{{ t('settings.sync.description') }}</p>
      <SettingRow v-for="item in syncItemList()" :key="item.key" :title="item.label" :description="item.description">
        <ToggleSwitch v-model="form.sync[item.key]" :label="t('settings.sync.toggleLabel', { item: item.label })" />
      </SettingRow>
    </div>

    <!-- Java-Installationen ------------------------------------------------------------ -->
    <div v-else-if="active === 'java'">
      <h3 class="section-heading">{{ t('settings.java.title') }}</h3>
      <p class="mb-3 text-xs text-base-400">{{ t('settings.java.hint') }}</p>
      <div class="space-y-3">
        <section v-for="slot in javaSlots" :key="slot.major" class="rounded-xl border border-base-800 bg-base-850 p-4">
          <div class="mb-2 flex items-center justify-between">
            <div>
              <h4 class="text-sm font-semibold">Java {{ slot.major }}</h4>
              <p class="text-xs text-base-400">{{ t(`settings.java.slots.${slot.key}`) }}</p>
            </div>
            <button class="btn btn-ghost py-1.5 text-xs" :disabled="!!installing" @click="installJava(slot)">
              {{
                installing?.major === slot.major
                  ? t('settings.java.installing', { percent: Math.floor(installing.percent) })
                  : t('settings.java.installRecommended')
              }}
            </button>
          </div>
          <RedstoneWire v-if="installing?.major === slot.major" class="mb-2" :percent="installing.percent" :segments="40" />
          <JavaPathField v-model="javaModels[slot.key].value" :expected-major="slot.major" :placeholder="t('settings.java.autoPlaceholder', { major: slot.major })" />
        </section>
        <section class="rounded-xl border border-base-800 p-4">
          <h4 class="text-sm font-semibold">{{ t('settings.java.globalTitle') }}</h4>
          <p class="mb-2 text-xs text-base-400">{{ t('settings.java.globalDescription') }}</p>
          <JavaPathField v-model="globalJava" :placeholder="t('settings.java.notSet')" />
        </section>
      </div>
    </div>

    <!-- Clips & Aufnahme -------------------------------------------------------------- -->
    <div v-else-if="active === 'clips'">
      <ClipsSettings v-model="form.clips" />
    </div>

    <!-- Speicherverwaltung --------------------------------------------------------------- -->
    <div v-else-if="active === 'storage'">
      <h3 class="section-heading">{{ t('settings.storage.title') }}</h3>
      <div v-if="!stats" class="skeleton h-24" />
      <div v-else class="rounded-xl border border-base-800 bg-base-850 p-4">
        <div class="flex h-3 overflow-hidden rounded-full bg-base-800">
          <div v-for="b in bars" :key="b.key" :class="b.color" :style="{ width: `${b.share}%` }" />
        </div>
        <ul class="mt-3 grid grid-cols-3 gap-3 text-xs">
          <li v-for="b in bars" :key="b.key" class="flex items-start gap-2">
            <span class="mt-1 size-2.5 shrink-0 rounded-full" :class="b.color" />
            <span><span class="block text-base-400">{{ b.label }}</span><span class="font-mono text-sm text-base-50">{{ formatBytes(b.bytes) }}</span></span>
          </li>
        </ul>
      </div>
      <SettingRow :title="t('settings.storage.verifyTitle')" :description="t('settings.storage.verifyDescription')">
        <button class="btn btn-ghost" :disabled="verifying" @click="verify">{{ verifying ? t('settings.storage.verifying') : t('settings.storage.verifyTitle') }}</button>
      </SettingRow>
      <SettingRow
        :title="t('settings.storage.cleanTitle')"
        :description="stats ? t('settings.storage.cleanDescription', { size: formatBytes(stats.unused) }, stats.unusedVersions) : t('settings.storage.cleanDescriptionEmpty')"
        danger
      >
        <button class="btn btn-danger" :disabled="cleaning || !stats?.unusedVersions" @click="confirmClean = true">
          {{ cleaning ? t('settings.storage.cleaning') : t('settings.storage.cleanTitle') }}
        </button>
      </SettingRow>
      <SettingRow :title="t('settings.storage.dataDirTitle')" stacked>
        <template #description>
          <p class="mt-0.5 truncate font-mono text-xs text-base-400" :title="info?.dataDir">{{ info?.dataDir }}</p>
        </template>
        <button class="btn btn-ghost" @click="openDataDir">{{ t('common.actions.openFolder') }}</button>
      </SettingRow>
    </div>

    <!-- Netzwerk --------------------------------------------------------------------- -->
    <div v-else-if="active === 'network'">
      <h3 class="section-heading">{{ t('settings.network.title') }}</h3>
      <SettingRow :title="t('settings.network.autoFirewallTitle')" :description="t('settings.network.autoFirewallDescription')">
        <ToggleSwitch v-model="form.autoFirewall" :label="t('settings.network.autoFirewallTitle')" />
      </SettingRow>
      <SettingRow :title="t('settings.network.firewallTitle')" :description="t('settings.network.firewallDescription')">
        <template #description>
          <p v-if="firewall" class="mt-1 text-xs" :class="firewall.missing ? 'text-warn' : 'text-ok'">
            {{
              firewall.total === 0
                ? t('settings.network.noJava')
                : firewall.missing
                  ? t('settings.network.missing', { missing: firewall.missing, total: firewall.total })
                  : t('settings.network.allAllowed')
            }}
          </p>
        </template>
        <button class="btn btn-ghost" :disabled="firewallBusy || firewall?.total === 0" @click="allowFirewall">
          {{ firewallBusy ? t('settings.network.waitingForWindows') : t('settings.network.allowAll') }}
        </button>
      </SettingRow>
      <SettingRow :title="t('settings.network.downloadsTitle')" :description="t('settings.network.downloadsDescription')">
        <input v-model.number="form.concurrentDownloads" type="number" min="1" max="64" class="field w-24 font-mono" :aria-label="t('settings.network.downloadsTitle')" />
      </SettingRow>
    </div>
  </SettingsShell>

  <BaseDialog v-if="confirmClean" :title="t('settings.storage.confirmTitle')" @close="confirmClean = false">
    <p class="text-sm text-base-200">
      {{ t('settings.storage.confirmText', { size: formatBytes(stats?.unused ?? 0) }, stats?.unusedVersions ?? 0) }}
    </p>
    <template #actions>
      <button class="btn btn-ghost" @click="confirmClean = false">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-danger" @click="clean">{{ t('common.actions.delete') }}</button>
    </template>
  </BaseDialog>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.section-heading {
  @apply mb-2 text-base font-semibold text-base-50;
}
</style>
