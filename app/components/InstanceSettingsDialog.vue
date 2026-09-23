<script setup lang="ts">
import type { EnvVar, Instance, InstanceOverrides, SyncItem, UpdateChannel } from '~/types'
import type { ShellSection } from '~/components/SettingsShell.vue'

// Instanz-Einstellungen im Stil der Modrinth App: Modal mit Bereichen links,
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

const sections: ShellSection[] = instanceSettingsSections
const active = ref(sections.some((s) => s.key === props.initial) ? props.initial : 'general')

// --- Formularzustand ----------------------------------------------------------
const o = inst.value.overrides
const name = ref(inst.value.name)
const channel = ref<UpdateChannel>(o.updateChannel ?? 'release')
const trsClient = ref(o.trsClient !== false)
const boost = ref(o.boost !== false)

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
const status = ref<{ ok: boolean; text: string } | null>(null)
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
    status.value = { ok: false, text: firstIssue(parsed.error) }
    return
  }
  const json = JSON.stringify(payload.value)
  status.value = { ok: true, text: 'Speichere …' }
  try {
    const updated = await backend.updateInstance(inst.value.id, parsed.data)
    lastSaved = json
    inst.value = { ...updated, iconPath: inst.value.iconPath, bannerPath: inst.value.bannerPath }
    emit('updated', inst.value)
    instances.load()
    status.value = { ok: true, text: 'Gespeichert' }
  } catch (e) {
    status.value = { ok: false, text: errorMessage(e) }
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
  return [...set].sort((a, b) => a.localeCompare(b, 'de'))
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
  const result = await tasks.run(
    { key: taskKey('duplicate', source.id), kind: 'duplicate', title: `${source.name} (Kopie)`.slice(0, 64), stage: 'Dateien werden kopiert', instanceId: source.id },
    async (ctx) => {
      const copy = await backend.duplicateInstance(source.id, `${source.name} (Kopie)`.slice(0, 64))
      ctx.update({ instanceId: copy.id, title: copy.name, doneText: `Kopie „${copy.name}“ angelegt` })
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
    toasts.ok(`„${inst.value.name}“ gelöscht`)
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
  <SettingsShell v-model="active" :title="`Einstellungen · ${inst.name}`" :sections="sections" :status="status" @close="close">
    <template #title-icon>
      <InstanceIcon :instance="inst" :size="32" />
    </template>
    <template #nav-footer>
      <p class="truncate font-medium text-base-400">{{ inst.name }}</p>
      <p class="font-mono">{{ inst.gameVersion }} · {{ loaderLabels[inst.loader.kind] }}</p>
    </template>

    <!-- Allgemein ------------------------------------------------------------ -->
    <div v-if="active === 'general'">
      <h3 class="section-heading">Allgemein</h3>
      <div class="flex items-center gap-5 border-b border-base-800 pb-5">
        <div class="group relative">
          <InstanceIcon :instance="inst" :size="88" />
          <button class="absolute inset-0 grid place-items-center rounded-xl bg-black/55 text-xs font-medium text-white opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100" :disabled="iconBusy" aria-label="Bild ändern" @click="pickIcon">
            {{ iconBusy ? '…' : 'Ändern' }}
          </button>
        </div>
        <div class="min-w-0 flex-1">
          <label class="label" for="is-name">Name</label>
          <input id="is-name" v-model="name" class="field" maxlength="64" />
          <div class="mt-2 flex gap-2">
            <button class="btn btn-ghost py-1.5 text-xs" :disabled="iconBusy" @click="pickIcon">Bild ändern</button>
            <button v-if="inst.iconPath" class="btn btn-ghost py-1.5 text-xs hover:text-redstone-300" @click="removeIcon">Bild entfernen</button>
          </div>
        </div>
      </div>

      <SettingRow
        title="Banner"
        description="Breites Titelbild für Startseite und Instanz-Kopf. PNG, JPEG oder WebP, höchstens 10 MB – oder im Tab „Screenshots“ ein eigenes Bild übernehmen."
        stacked
      >
        <div class="group relative overflow-hidden rounded-xl border border-base-800">
          <InstanceBanner :instance="inst" shade="none" class="h-28 w-full" />
          <div class="absolute inset-0 flex items-end justify-end gap-2 bg-gradient-to-t from-base-950/80 to-transparent p-2.5">
            <button class="btn btn-ghost py-1.5 text-xs" :disabled="bannerBusy" @click="pickBanner">
              {{ bannerBusy ? 'Wähle …' : inst.bannerPath ? 'Banner ändern' : 'Banner wählen' }}
            </button>
            <button v-if="inst.bannerPath" class="btn btn-ghost py-1.5 text-xs hover:text-redstone-300" @click="removeBanner">Entfernen</button>
          </div>
        </div>
      </SettingRow>

      <SettingRow title="Bibliotheksgruppe" description="Gruppen ordnen die Instanzen in der Bibliothek. Eine Instanz gehört zu höchstens einer Gruppe." stacked>
        <div class="flex flex-wrap items-center gap-1.5">
          <button class="group-chip" :class="{ 'group-chip-on': !inst.group }" @click="setGroup(null)">Keine</button>
          <button v-for="gr in groups" :key="gr" class="group-chip" :class="{ 'group-chip-on': inst.group === gr }" @click="setGroup(gr)">{{ gr }}</button>
          <form v-if="newGroup !== null" class="flex items-center gap-1.5" @submit.prevent="setGroup(newGroup)">
            <input v-model="newGroup" class="field h-8 w-44 py-0 text-xs" maxlength="32" placeholder="Name der Gruppe" aria-label="Name der neuen Gruppe" autofocus />
            <button type="submit" class="btn btn-primary h-8 py-0 text-xs" :disabled="!newGroup.trim()">Anlegen</button>
            <button type="button" class="btn btn-ghost h-8 py-0 text-xs" @click="newGroup = null">Abbrechen</button>
          </form>
          <button v-else class="group-chip border-dashed" @click="newGroup = ''">+ Neue Gruppe</button>
        </div>
      </SettingRow>

      <SettingRow title="Update-Kanal für Inhalte" description="Welche Modrinth-Versionen „Aktualisieren“ und „neueste passende Version“ nehmen." stacked>
        <div class="grid grid-cols-3 gap-2">
          <button
            v-for="c in ([['release', 'Stabil', 'Nur fertige Versionen'], ['beta', 'Beta', 'Stabil + Betas'], ['alpha', 'Alpha', 'Alles, auch Alphas']] as const)"
            :key="c[0]"
            class="rounded-lg border px-3 py-2.5 text-left transition-colors"
            :class="channel === c[0] ? 'border-redstone-500 bg-redstone-900/40' : 'border-base-700 hover:border-base-600'"
            :aria-pressed="channel === c[0]"
            @click="channel = c[0]"
          >
            <span class="block text-sm font-semibold">{{ c[1] }}</span>
            <span class="block text-xs text-base-400">{{ c[2] }}</span>
          </button>
        </div>
      </SettingRow>

      <SettingRow title="Duplizieren" description="Legt eine Kopie mit allen Welten, Mods und Einstellungen an – praktisch vor einem Versionswechsel.">
        <button class="btn btn-ghost" :disabled="duplicating || running" @click="duplicate">{{ duplicating ? 'Kopiere …' : 'Duplizieren' }}</button>
      </SettingRow>

      <SettingRow title="Als Modpack exportieren" description="Schreibt eine .mrpack-Datei zum Weitergeben oder Sichern. Mods von Modrinth werden verlinkt statt kopiert.">
        <button class="btn btn-ghost" :disabled="running" @click="exporting = true">Exportieren</button>
      </SettingRow>

      <SettingRow title="Instanz löschen" description="Löscht die Instanz mit allen Welten, Mods und Screenshots unwiderruflich." danger>
        <button class="btn btn-danger" :disabled="running" @click="deleting = true">Instanz löschen</button>
      </SettingRow>
    </div>

    <!-- Installation ---------------------------------------------------------- -->
    <div v-else-if="active === 'installation'">
      <h3 class="section-heading">Installation</h3>
      <div class="card divide-y divide-base-800 bg-base-850">
        <div class="flex items-center justify-between px-4 py-3">
          <span class="text-sm text-base-400">Plattform</span>
          <span class="flex items-center gap-2 text-sm font-medium"><span class="size-2 rounded-full" :style="{ background: loaderColors[inst.loader.kind] }" />{{ loaderLabels[inst.loader.kind] }}</span>
        </div>
        <div class="flex items-center justify-between px-4 py-3">
          <span class="text-sm text-base-400">Spielversion</span>
          <span class="font-mono text-sm">{{ inst.gameVersion }}</span>
        </div>
        <div v-if="inst.loader.kind !== 'vanilla'" class="flex items-center justify-between px-4 py-3">
          <span class="text-sm text-base-400">{{ loaderLabels[inst.loader.kind] }}-Version</span>
          <span class="text-right font-mono text-sm">
            <template v-if="inst.loader.version">{{ inst.loader.version }}</template>
            <template v-else>
              <span class="font-sans text-base-400">Neueste stabile</span>
              <span v-if="resolvedLoader"> (derzeit {{ resolvedLoader }})</span>
            </template>
          </span>
        </div>
      </div>
      <div class="mt-3 flex items-start gap-3">
        <p class="flex-1 text-xs leading-relaxed text-base-400">
          Beim Wechsel bleiben Welten und Einstellungen erhalten; über Modrinth installierte Mods lassen sich danach auf passende
          Versionen bringen. Welten werden beim ersten Start umgewandelt – sicherer ist es, die Instanz vorher zu duplizieren.
        </p>
        <button class="btn btn-primary shrink-0" :disabled="running" @click="changingVersion = true">Bearbeiten</button>
      </div>

      <h3 class="section-heading mt-6">TRS</h3>
      <SettingRow
        v-if="inst.loader.kind === 'vanilla'"
        title="TRS-Optimierung"
        description="Startet die Instanz unter der Haube mit Fabric, dem TRS Client und Performance-Mods wie Sodium – deutlich mehr FPS. Aus = echtes Vanilla."
      >
        <ToggleSwitch v-model="boost" label="TRS-Optimierung" />
      </SettingRow>
      <SettingRow title="TRS Client" description="HUD mit FPS, CPS, Tastenanzeige und Ping, Zoom (C) und Fullbright. Menü im Spiel mit der rechten Umschalttaste.">
        <ToggleSwitch v-model="trsClient" label="TRS Client" />
      </SettingRow>

      <h3 class="section-heading mt-6">Wartung</h3>
      <div v-if="repairing" class="mb-3 flex items-center gap-3">
        <RedstoneWire class="flex-1" :percent="repairing.percent" :segments="40" />
        <span class="display text-sm text-redstone-300 tabular-nums">{{ Math.floor(repairing.percent) }} %</span>
      </div>
      <SettingRow title="Reparieren" description="Prüft alle Spieldateien per Prüfsumme und lädt beschädigte neu. Welten und Mods bleiben unberührt.">
        <button class="btn btn-ghost" :disabled="!!repairing || running" @click="repair('repair')">Reparieren</button>
      </SettingRow>
      <SettingRow title="Neu installieren" description="Lädt Spielversion, Modloader und Bibliotheken komplett neu. Welten, Mods und Einstellungen bleiben erhalten.">
        <button class="btn btn-ghost" :disabled="!!repairing || running" @click="confirmReinstall = true">Neu installieren</button>
      </SettingRow>
    </div>

    <!-- Fenster ----------------------------------------------------------------- -->
    <div v-else-if="active === 'window'">
      <h3 class="section-heading">Fenster</h3>
      <SettingRow title="Eigene Fenstereinstellungen" description="Aus = die globalen Einstellungen gelten.">
        <ToggleSwitch v-model="customWindow" label="Eigene Fenstereinstellungen" />
      </SettingRow>
      <SettingRow title="Vollbild" description="Startet das Spiel direkt im Vollbild.">
        <ToggleSwitch v-model="fullscreen" label="Vollbild" :disabled="!customWindow" />
      </SettingRow>
      <SettingRow title="Breite" description="Breite des Spielfensters in Pixeln.">
        <input v-model.number="width" type="number" min="320" max="16384" class="field w-32 font-mono" :placeholder="String(g?.resolution.width ?? 1280)" :disabled="!customWindow || fullscreen" aria-label="Breite" />
      </SettingRow>
      <SettingRow title="Höhe" description="Höhe des Spielfensters in Pixeln.">
        <input v-model.number="height" type="number" min="240" max="16384" class="field w-32 font-mono" :placeholder="String(g?.resolution.height ?? 720)" :disabled="!customWindow || fullscreen" aria-label="Höhe" />
      </SettingRow>
    </div>

    <!-- Java & Arbeitsspeicher ------------------------------------------------------ -->
    <div v-else-if="active === 'java'">
      <h3 class="section-heading">Java &amp; Arbeitsspeicher</h3>
      <SettingRow title="Eigene Java- und Speichereinstellungen" description="Aus = die globalen Einstellungen gelten. Leere Felder übernehmen ebenfalls die globalen Werte.">
        <ToggleSwitch v-model="customJava" label="Eigene Java- und Speichereinstellungen" />
      </SettingRow>
      <SettingRow title="Java-Installation" :description="`Diese Version braucht Java ${neededJava}. Leer = ${globalJava ? 'globaler Pfad' : 'automatisch installieren'}.`" stacked>
        <JavaPathField v-model="javaPath" :placeholder="globalJava ?? `Automatisch (Java ${neededJava})`" :expected-major="neededJava" :disabled="!customJava" input-id="is-java" />
      </SettingRow>
      <SettingRow title="Arbeitsspeicher" description="Wie viel RAM das Spiel höchstens nutzen darf. Mehr als die Hälfte des Rechners ist selten sinnvoll." stacked>
        <div class="flex items-center gap-4">
          <input v-model.number="memory" type="range" min="1024" :max="maxRam" step="512" class="flex-1 accent-redstone-500" :disabled="!customJava" aria-label="Arbeitsspeicher" />
          <div class="flex items-center gap-1.5">
            <input v-model.number="memory" type="number" min="512" max="131072" step="256" class="field w-28 font-mono" :disabled="!customJava" aria-label="Arbeitsspeicher in MB" />
            <span class="text-xs text-base-400">MB</span>
          </div>
        </div>
        <p class="mt-1 text-xs text-base-600">{{ formatMemory(memory) }}</p>
      </SettingRow>
      <SettingRow title="JVM-Argumente" description="Zusätzliche Argumente für Java, durch Leerzeichen getrennt." stacked>
        <div class="flex gap-2">
          <input v-model="jvmArgs" class="field font-mono text-xs" maxlength="4096" :placeholder="g?.jvmArgs || 'Keine'" spellcheck="false" :disabled="!customJava" aria-label="JVM-Argumente" />
          <button class="btn btn-ghost shrink-0 py-1.5 text-xs" :disabled="!customJava" title="Bewährte G1-Einstellungen" @click="jvmArgs = optimizedJvmArgs">Optimierte Argumente</button>
        </div>
      </SettingRow>
      <SettingRow title="Umgebungsvariablen" description="Gelten für das Spiel und die Start-Hooks." stacked>
        <EnvEditor v-model="env" :disabled="!customJava" :placeholder="g?.env" />
      </SettingRow>
    </div>

    <!-- Start-Hooks -------------------------------------------------------------------- -->
    <div v-else-if="active === 'hooks'">
      <h3 class="section-heading">Start-Hooks</h3>
      <p class="mb-2 text-xs leading-relaxed text-base-400">
        Befehle laufen über <code class="rounded bg-base-800 px-1 font-mono text-lamp-300">cmd /C</code> ohne Fenster, höchstens 2 Minuten.
        Verfügbar sind <code class="font-mono text-base-200">%INST_ID%</code>, <code class="font-mono text-base-200">%INST_NAME%</code>,
        <code class="font-mono text-base-200">%INST_DIR%</code>, <code class="font-mono text-base-200">%INST_MC_DIR%</code> und
        <code class="font-mono text-base-200">%INST_JAVA%</code>.
      </p>
      <SettingRow title="Eigene Start-Hooks" description="Aus = die globalen Hooks gelten.">
        <ToggleSwitch v-model="customHooks" label="Eigene Start-Hooks" />
      </SettingRow>
      <SettingRow title="Vor dem Start" description="Läuft, bevor das Spiel startet. Schlägt er fehl, startet das Spiel nicht." stacked>
        <input v-model="preLaunch" class="field font-mono text-xs" maxlength="1024" :placeholder="g?.hooks.preLaunch || 'Kein Befehl'" spellcheck="false" :disabled="!customHooks" aria-label="Befehl vor dem Start" />
      </SettingRow>
      <SettingRow title="Wrapper" description="Wird vor die Java-Kommandozeile gesetzt, z. B. ein Profiler oder Starter." stacked>
        <input v-model="wrapper" class="field font-mono text-xs" maxlength="1024" :placeholder="g?.hooks.wrapper || 'Kein Wrapper'" spellcheck="false" :disabled="!customHooks" aria-label="Wrapper-Befehl" />
      </SettingRow>
      <SettingRow title="Nach dem Beenden" description="Läuft, nachdem das Spiel beendet wurde. Fehler erscheinen als Hinweis." stacked>
        <input v-model="postExit" class="field font-mono text-xs" maxlength="1024" :placeholder="g?.hooks.postExit || 'Kein Befehl'" spellcheck="false" :disabled="!customHooks" aria-label="Befehl nach dem Beenden" />
      </SettingRow>
    </div>

    <!-- Synchronisierung ------------------------------------------------------------------ -->
    <div v-else-if="active === 'sync'">
      <h3 class="section-heading">Synchronisierung</h3>
      <p class="mb-2 text-xs leading-relaxed text-base-400">
        Global eingeschaltete Dinge werden vor dem Start aus dem gemeinsamen Speicher geholt und nach dem Beenden zurückgeschrieben.
        Hier kannst du sie für diese Instanz separat halten. Überschriebene Dateien werden vorher gesichert.
      </p>
      <SettingRow v-for="item in syncItemList" :key="item.key" :title="item.label" :description="g?.sync[item.key] ? item.description : 'Global ausgeschaltet – in den Einstellungen unter Instanzen einschalten.'">
        <ToggleSwitch
          :model-value="!!g?.sync[item.key] && !syncSeparate.includes(item.key)"
          :label="`${item.label} synchronisieren`"
          :disabled="!g?.sync[item.key]"
          @update:model-value="toggleSync(item.key, $event)"
        />
      </SettingRow>
    </div>
  </SettingsShell>

  <ChangeVersionDialog v-if="changingVersion" :instance="inst" @close="changingVersion = false" @changed="onVersionChanged" />

  <ExportPackDialog v-if="exporting" :instance="inst" @close="exporting = false" />

  <BaseDialog v-if="confirmReinstall" title="Neu installieren?" @close="confirmReinstall = false">
    <p class="text-sm text-base-200">Spielversion und Bibliotheken werden neu heruntergeladen. Welten, Mods und Einstellungen bleiben erhalten.</p>
    <template #actions>
      <button class="btn btn-ghost" @click="confirmReinstall = false">Abbrechen</button>
      <button class="btn btn-primary" @click="repair('reinstall')">Neu installieren</button>
    </template>
  </BaseDialog>

  <BaseDialog v-if="deleting" title="Instanz löschen?" @close="deleting = false">
    <p class="text-sm text-base-200">
      <strong class="text-base-50">{{ inst.name }}</strong> wird mit allen Welten, Mods und Screenshots unwiderruflich gelöscht.
    </p>
    <template #actions>
      <button class="btn btn-ghost" @click="deleting = false">Abbrechen</button>
      <button class="btn btn-danger" :disabled="deleteBusy" @click="confirmDelete">
        {{ deleteBusy ? 'Lösche …' : 'Endgültig löschen' }}
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
