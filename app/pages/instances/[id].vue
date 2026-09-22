<script setup lang="ts">
import type { Instance, InstanceOverrides } from '~/types'

const route = useRoute()
const id = computed(() => String(route.params.id))

const games = useGamesStore()
const instances = useInstancesStore()
const settings = useSettingsStore()
const game = computed(() => games.state(id.value))

const instance = ref<Instance | null>(null)
const loadError = ref<string | null>(null)
type Tab = 'content' | 'history' | 'screenshots' | 'worlds' | 'logs' | 'settings'
const tabs: [Tab, string][] = [
  ['content', 'Inhalte'],
  ['history', 'Verlauf'],
  ['screenshots', 'Screenshots'],
  ['worlds', 'Welten'],
  ['logs', 'Logs'],
  ['settings', 'Einstellungen'],
]
const tab = ref<Tab>('content')
const router = useRouter()
const toasts = useToasts()
const duplicating = ref(false)
const changingVersion = ref(false)
const iconMenu = ref(false)
const iconBusy = ref(false)

async function pickIcon() {
  if (!instance.value) return
  iconMenu.value = false
  iconBusy.value = true
  try {
    const updated = await backend.pickInstanceIcon(instance.value.id)
    if (updated) {
      instance.value = { ...instance.value, icon: updated.icon, iconPath: updated.iconPath }
      instances.load()
    }
  } catch (e) {
    toasts.error(e)
  } finally {
    iconBusy.value = false
  }
}

async function removeIcon() {
  if (!instance.value) return
  iconMenu.value = false
  try {
    const updated = await backend.removeInstanceIcon(instance.value.id)
    instance.value = { ...instance.value, icon: updated.icon, iconPath: updated.iconPath }
    instances.load()
  } catch (e) {
    toasts.error(e)
  }
}

function onVersionChanged(updated: Instance) {
  instance.value = updated
  resetForm()
  instances.load()
}

function closeIconMenu(e: MouseEvent) {
  if (!(e.target as HTMLElement | null)?.closest('[data-icon-menu]')) iconMenu.value = false
}
onMounted(() => document.addEventListener('mousedown', closeIconMenu))
onBeforeUnmount(() => document.removeEventListener('mousedown', closeIconMenu))

async function duplicate() {
  if (!instance.value) return
  duplicating.value = true
  try {
    const copy = await backend.duplicateInstance(instance.value.id, `${instance.value.name} (Kopie)`.slice(0, 64))
    await instances.load()
    toasts.ok(`Kopie „${copy.name}“ angelegt`)
    router.push(`/instances/${copy.id}`)
  } catch (e) {
    toasts.error(e)
  } finally {
    duplicating.value = false
  }
}

// Beim Start direkt zu den Logs springen – da passiert dann etwas.
watch(
  () => game.value.phase,
  (phase, before) => {
    if (before === 'idle' && phase !== 'idle') tab.value = 'logs'
  },
)

async function load() {
  try {
    instance.value = await backend.getInstance(id.value)
    resetForm()
  } catch (e) {
    loadError.value = errorMessage(e)
  }
}
onMounted(() => {
  load()
  if (!settings.current) settings.load().catch(() => {})
})

// Nach Spielende lädt der Instanz-Store neu – Spielzeit hier mitziehen.
watch(
  () => instances.items.find((i) => i.id === id.value),
  (fresh) => {
    if (fresh && instance.value) {
      instance.value.totalPlaySeconds = fresh.totalPlaySeconds
      instance.value.lastPlayed = fresh.lastPlayed
    }
  },
)

const loader = computed(() => {
  if (!instance.value) return ''
  const { kind, version } = instance.value.loader
  return version ? `${loaderLabels[kind]} ${version}` : loaderLabels[kind]
})

// --- Einstellungen der Instanz -------------------------------------------
const name = ref('')
const customMemory = ref(false)
const memoryMb = ref(4096)
const javaPath = ref('')
const jvmArgs = ref('')
const customResolution = ref(false)
const trsClient = ref(true)
const boost = ref(true)
const width = ref(1280)
const height = ref(720)
const saving = ref(false)
const status = ref<{ ok: boolean; text: string } | null>(null)

function resetForm() {
  const inst = instance.value
  if (!inst) return
  const o = inst.overrides
  name.value = inst.name
  customMemory.value = o.maxMemoryMb !== null
  memoryMb.value = o.maxMemoryMb ?? settings.current?.maxMemoryMb ?? 4096
  javaPath.value = o.javaPath ?? ''
  jvmArgs.value = o.jvmArgs ?? ''
  customResolution.value = o.resolution !== null
  trsClient.value = o.trsClient !== false
  boost.value = o.boost !== false
  width.value = o.resolution?.width ?? settings.current?.resolution.width ?? 1280
  height.value = o.resolution?.height ?? settings.current?.resolution.height ?? 720
}

async function save() {
  if (!instance.value) return
  status.value = null
  const overrides: InstanceOverrides = {
    maxMemoryMb: customMemory.value ? memoryMb.value : null,
    javaPath: javaPath.value.trim() || null,
    jvmArgs: jvmArgs.value.trim() || null,
    resolution: customResolution.value ? { width: width.value, height: height.value } : null,
    trsClient: trsClient.value ? null : false,
    boost: boost.value ? null : false,
  }
  const parsed = updateInstanceSchema.safeParse({ name: name.value, overrides })
  if (!parsed.success) {
    status.value = { ok: false, text: firstIssue(parsed.error) }
    return
  }
  saving.value = true
  try {
    instance.value = await backend.updateInstance(instance.value.id, parsed.data)
    resetForm()
    instances.load()
    status.value = { ok: true, text: 'Gespeichert' }
  } catch (e) {
    status.value = { ok: false, text: errorMessage(e) }
  } finally {
    saving.value = false
  }
}

function openFolder() {
  backend.openInstanceDir(id.value).catch(() => {})
}
</script>

<template>
  <div class="flex h-full flex-col p-6">
    <NuxtLink to="/instances" class="mb-3 inline-flex w-fit items-center gap-1 text-xs text-base-400 hover:text-base-50">
      <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M15 5l-7 7 7 7" /></svg>
      Instanzen
    </NuxtLink>

    <p v-if="loadError" role="alert" class="card border-redstone-600/50 px-4 py-3 text-sm text-redstone-300">{{ loadError }}</p>

    <div v-else-if="!instance" class="mb-5 flex items-center gap-5">
      <div class="skeleton size-[88px] rounded-xl" />
      <div class="flex-1 space-y-2.5"><div class="skeleton h-8 w-64" /><div class="skeleton h-4 w-80" /></div>
      <div class="skeleton h-10 w-72" />
    </div>

    <template v-else>
      <header class="mb-5 flex items-center gap-5">
        <!-- Instanz-Bild: Klick ändert es, das Menü bietet Entfernen. -->
        <div class="group relative shrink-0" data-icon-menu>
          <button
            class="relative block rounded-xl outline-none focus-visible:ring-2 focus-visible:ring-redstone-500"
            :aria-label="instance.iconPath ? 'Bild ändern oder entfernen' : 'Bild festlegen'"
            :aria-expanded="iconMenu"
            @click="instance.iconPath ? (iconMenu = !iconMenu) : pickIcon()"
          >
            <InstanceIcon :instance="instance" :size="88" />
            <span class="absolute inset-0 grid place-items-center rounded-xl bg-black/55 text-xs font-medium text-white opacity-0 transition-opacity group-hover:opacity-100" :class="{ 'opacity-100': iconBusy }">
              <svg v-if="!iconBusy" viewBox="0 0 24 24" class="size-6" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h3l2-3h6l2 3h3v12H4z" /><circle cx="12" cy="13" r="3.5" /></svg>
              <span v-else>…</span>
            </span>
          </button>
          <div v-if="iconMenu" class="menu top-full left-0 mt-2" role="menu">
            <button class="menu-item" role="menuitem" @click="pickIcon">Bild ändern</button>
            <button class="menu-item text-redstone-300" role="menuitem" @click="removeIcon">Bild entfernen</button>
          </div>
        </div>

        <div class="min-w-0 flex-1">
          <h1 class="display truncate text-3xl leading-tight text-base-50">{{ instance.name }}</h1>
          <div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-base-400">
            <button
              class="chip gap-1.5 ring-1 ring-base-700 transition-colors hover:bg-base-700 hover:text-base-50 disabled:opacity-60"
              title="Version wechseln"
              :disabled="game.phase !== 'idle'"
              @click="changingVersion = true"
            >
              <span class="size-2 rounded-full" :style="{ background: loaderColors[instance.loader.kind] }" />
              <span class="font-mono text-base-50">{{ instance.gameVersion }}</span> {{ loader }}
              <svg viewBox="0 0 24 24" class="size-3 text-base-400" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m7 10 5 5 5-5" /></svg>
            </button>
            <span v-if="instance.totalPlaySeconds > 0" class="flex items-center gap-1.5">
              <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8" /><path d="M12 8v4l3 2" /></svg>
              {{ formatPlayTime(instance.totalPlaySeconds) }}
            </span>
            <span>{{ formatRelative(instance.lastPlayed) }}</span>
          </div>
        </div>

        <div class="flex w-80 shrink-0 items-center gap-2">
          <PlayButton :instance-id="instance.id" large />
          <button class="btn-icon size-12" title="Duplizieren" aria-label="Duplizieren" :disabled="duplicating || game.phase !== 'idle'" @click="duplicate">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><rect x="8" y="8" width="12" height="12" rx="1" /><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3" /></svg>
          </button>
          <button class="btn-icon size-12" title="Ordner öffnen" aria-label="Ordner öffnen" @click="openFolder">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />
            </svg>
          </button>
        </div>
      </header>

      <p v-if="game.error" role="alert" class="card mb-4 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ game.error }}</p>
      <CrashPanel v-else-if="game.lastExit?.crashed" :instance-id="instance.id" :exit-code="game.lastExit.exitCode" :diagnosis="game.lastExit.diagnosis" class="mb-4" />

      <nav class="mb-4 flex flex-wrap gap-1" aria-label="Bereiche">
        <button v-for="[key, label] in tabs" :key="key" class="tab" :class="{ 'tab-on': tab === key }" @click="tab = key">{{ label }}</button>
      </nav>

      <ChangeVersionDialog v-if="changingVersion" :instance="instance" @close="changingVersion = false" @changed="onVersionChanged" />

      <ContentList v-if="tab === 'content'" :key="`${instance.gameVersion}-${instance.loader.kind}`" :instance="instance" />
      <HistoryList v-else-if="tab === 'history'" :instance="instance" />
      <InstanceGallery v-else-if="tab === 'screenshots' || tab === 'worlds'" :instance="instance" :mode="tab" />
      <LogConsole v-else-if="tab === 'logs'" :lines="game.logs" />

      <form v-else class="max-w-2xl space-y-5 overflow-y-auto pb-2" @submit.prevent="save">
        <section class="card flex items-center gap-4 p-5">
          <InstanceIcon :instance="instance" :size="56" />
          <div class="min-w-0 flex-1">
            <label class="label" for="i-name">Name</label>
            <input id="i-name" v-model="name" class="field" maxlength="64" />
          </div>
          <div class="flex shrink-0 flex-col gap-1.5 self-end">
            <button type="button" class="btn btn-ghost py-1.5 text-xs" :disabled="iconBusy" @click="pickIcon">Bild ändern</button>
            <button v-if="instance.iconPath" type="button" class="text-xs text-base-400 hover:text-redstone-300" @click="removeIcon">Bild entfernen</button>
          </div>
        </section>

        <section class="card flex items-center gap-4 p-5">
          <div class="min-w-0 flex-1">
            <h2 class="font-medium">Version</h2>
            <p class="mt-0.5 text-xs text-base-400">
              <span class="font-mono text-base-200">{{ instance.gameVersion }}</span> mit {{ loader }}. Beim Wechsel bleiben Welten und
              Einstellungen erhalten; Mods können danach auf passende Versionen gebracht werden.
            </p>
          </div>
          <button type="button" class="btn btn-ghost shrink-0" :disabled="game.phase !== 'idle'" @click="changingVersion = true">Version wechseln</button>
        </section>

        <section v-if="instance.loader.kind === 'vanilla'" class="card p-5">
          <label class="flex items-start gap-2.5 text-sm text-base-200">
            <input v-model="boost" type="checkbox" class="mt-0.5 accent-redstone-500" />
            <span>
              TRS-Optimierung
              <span class="block text-xs text-base-400">
                Startet diese Instanz unter der Haube mit Fabric, dem TRS Client und Performance-Mods wie Sodium –
                deutlich mehr FPS, sonst wie Vanilla. Aus = echtes Vanilla ohne Mods.
              </span>
            </span>
          </label>
        </section>

        <section class="card p-5">
          <label class="flex items-start gap-2.5 text-sm text-base-200">
            <input v-model="trsClient" type="checkbox" class="mt-0.5 accent-redstone-500" />
            <span>
              TRS Client verwenden
              <span class="block text-xs text-base-400">
                HUD mit FPS, CPS, Tastenanzeige und Ping, dazu Zoom (C) und Fullbright. Das Menü öffnest du im Spiel
                mit der rechten Umschalttaste. Gibt es derzeit für Fabric und Quilt 1.21.1 – weitere Versionen folgen.
              </span>
            </span>
          </label>
        </section>

        <section class="card p-5">
          <h2 class="mb-1 font-medium">Java &amp; Arbeitsspeicher</h2>
          <p class="mb-4 text-xs text-base-400">Leere Felder übernehmen die globalen Einstellungen.</p>

          <label class="flex items-center gap-2.5 text-sm text-base-200">
            <input v-model="customMemory" type="checkbox" class="accent-redstone-500" />
            Eigener Arbeitsspeicher
            <span v-if="customMemory" class="ml-auto font-mono text-base-50">{{ formatMemory(memoryMb) }}</span>
          </label>
          <input v-if="customMemory" v-model.number="memoryMb" type="range" min="1024" max="32768" step="512" class="mt-2 w-full accent-redstone-500" aria-label="Arbeitsspeicher" />

          <div class="mt-4">
            <label class="label" for="i-java">Java-Pfad</label>
            <input id="i-java" v-model="javaPath" class="field font-mono" maxlength="1024" placeholder="Global / automatisch" spellcheck="false" />
          </div>
          <div class="mt-4">
            <label class="label" for="i-jvm">JVM-Argumente</label>
            <input id="i-jvm" v-model="jvmArgs" class="field font-mono" maxlength="4096" placeholder="Global" spellcheck="false" />
          </div>
        </section>

        <section class="card p-5">
          <label class="flex items-center gap-2.5 text-sm text-base-200">
            <input v-model="customResolution" type="checkbox" class="accent-redstone-500" />
            Eigene Fenstergröße
          </label>
          <div v-if="customResolution" class="mt-3 grid grid-cols-2 gap-4">
            <div>
              <label class="label" for="i-w">Breite</label>
              <input id="i-w" v-model.number="width" type="number" min="320" class="field font-mono" />
            </div>
            <div>
              <label class="label" for="i-h">Höhe</label>
              <input id="i-h" v-model.number="height" type="number" min="240" class="field font-mono" />
            </div>
          </div>
        </section>

        <div class="flex items-center gap-3">
          <button type="submit" class="btn btn-primary" :disabled="saving">{{ saving ? 'Speichere …' : 'Speichern' }}</button>
          <span v-if="status" role="status" class="text-sm" :class="status.ok ? 'text-ok' : 'text-redstone-300'">{{ status.text }}</span>
        </div>
      </form>
    </template>
  </div>
</template>
