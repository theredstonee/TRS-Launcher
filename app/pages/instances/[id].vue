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
const tab = ref<'content' | 'logs' | 'settings'>('content')

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

    <template v-else-if="instance">
      <header class="mb-5 flex items-center gap-4">
        <div class="flex size-14 shrink-0 items-center justify-center rounded-lg bg-base-800 font-mono text-2xl font-bold text-redstone-400">
          {{ instance.name.charAt(0).toUpperCase() }}
        </div>
        <div class="min-w-0 flex-1">
          <h1 class="truncate text-xl font-semibold tracking-tight">{{ instance.name }}</h1>
          <p class="mt-0.5 flex flex-wrap gap-x-3 text-xs text-base-400">
            <span><span class="font-mono text-base-200">{{ instance.gameVersion }}</span> · {{ loader }}</span>
            <span>Spielzeit: {{ formatPlayTime(instance.totalPlaySeconds) }}</span>
            <span>{{ formatRelative(instance.lastPlayed) }}</span>
          </p>
        </div>
        <div class="flex w-64 shrink-0 items-center gap-2">
          <PlayButton :instance-id="instance.id" />
          <button class="btn btn-ghost px-2.5" title="Ordner öffnen" aria-label="Ordner öffnen" @click="openFolder">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />
            </svg>
          </button>
        </div>
      </header>

      <p v-if="game.error" role="alert" class="card mb-4 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ game.error }}</p>
      <p v-else-if="game.lastExit?.crashed" role="alert" class="card mb-4 border-warn/40 px-4 py-2.5 text-sm text-warn">
        Das Spiel wurde unerwartet beendet (Exit-Code {{ game.lastExit.exitCode ?? '?' }}). Die letzten Zeilen im Log zeigen meist die Ursache.
      </p>

      <div class="mb-3 flex gap-1 border-b border-base-800 text-sm">
        <button class="tab" :class="{ 'tab-on': tab === 'content' }" @click="tab = 'content'">Inhalte</button>
        <button class="tab" :class="{ 'tab-on': tab === 'logs' }" @click="tab = 'logs'">Logs</button>
        <button class="tab" :class="{ 'tab-on': tab === 'settings' }" @click="tab = 'settings'">Einstellungen</button>
      </div>

      <ContentList v-if="tab === 'content'" :instance="instance" />
      <LogConsole v-else-if="tab === 'logs'" :lines="game.logs" />

      <form v-else class="max-w-2xl space-y-5 overflow-y-auto pb-2" @submit.prevent="save">
        <section class="card p-5">
          <label class="label" for="i-name">Name</label>
          <input id="i-name" v-model="name" class="field" maxlength="64" />
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

<style scoped>
@reference "~/assets/css/main.css";

.tab {
  @apply -mb-px border-b-2 border-transparent px-3 py-2 text-base-400 transition-colors hover:text-base-50;
}
.tab-on {
  @apply border-redstone-500 text-base-50;
}
</style>
