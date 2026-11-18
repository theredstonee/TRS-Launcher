<script setup lang="ts">
import type { WorldInfo } from '~/types'
import { WORLD_NAME_MAX, type ServerWorld, dimensionId, suggestWorldName, uniqueWorldName, validWorldName } from '~/utils/craftinghosting'

// Welten des Servers: aktiv setzen, zurücksetzen/löschen, als ZIP herunterladen und hochladen
// (Instanz-Welt, ZIP oder Ordner). Ändern geht nur bei gestopptem Server – sonst erst stoppen.
const store = useCraftingHostingStore()
const instances = useInstancesStore()

const server = computed(() => store.server!)
const offline = computed(() => server.value.state === 'offline' || server.value.state === 'failed')
const installing = computed(() => server.value.state === 'installing')
const list = computed(() => store.worlds?.worlds ?? [])
const names = computed(() => list.value.map((w) => w.name))
const busy = computed(() => !!store.busy || !!store.transfer?.active)

onMounted(async () => {
  if (!installing.value) void store.loadWorlds()
  if (!instances.items.length) await instances.load().catch(() => {})
  worldFrom.value = instances.items[0]?.id ?? ''
})

// --- Erst stoppen ----------------------------------------------------------------------------

const stopAsk = ref<(() => Promise<unknown>) | null>(null)
const stopping = ref(false)
function whenOffline(action: () => Promise<unknown>) {
  if (offline.value) void action()
  else stopAsk.value = action
}
async function stopThen() {
  const action = stopAsk.value
  stopAsk.value = null
  if (!action) return
  stopping.value = true
  try {
    if (await store.stopAndWait()) await action()
  } finally {
    stopping.value = false
  }
}

async function stopNow() {
  stopping.value = true
  try {
    await store.stopAndWait()
  } finally {
    stopping.value = false
  }
}

// --- Aktiv setzen, löschen, herunterladen -------------------------------------------------

const removing = ref<ServerWorld | null>(null)
function askRemove(w: ServerWorld) {
  removing.value = w
}
function confirmRemove() {
  const w = removing.value
  removing.value = null
  if (w) whenOffline(() => store.deleteWorld(w.name))
}
function activate(w: ServerWorld) {
  whenOffline(() => store.setActiveWorld(w.name))
}

const downloading = computed(() => store.worldDownload)
const downloadText = computed(() => {
  const p = downloading.value?.progress
  return p ? t(`craftinghosting.world.phase.${p.phase}`, { percent: p.percent }) : t('craftinghosting.world.phase.list')
})

const DIM_TONE: Record<string, string> = {
  overworld: 'bg-emerald-950/50 text-emerald-300 ring-emerald-800/40',
  nether: 'bg-redstone-950/50 text-redstone-300 ring-redstone-800/40',
  end: 'bg-violet-950/50 text-violet-300 ring-violet-800/40',
}
function dimLabel(d: string): string {
  const id = dimensionId(d)
  return id ? t(`craftinghosting.worlds.dimensions.${id}`) : d
}

// --- Hochladen -------------------------------------------------------------------------------

type Source = 'instance' | 'zip' | 'folder'
const SOURCES: Source[] = ['instance', 'zip', 'folder']
const source = ref<Source>('instance')
const worldFrom = ref('')
const worlds = ref<WorldInfo[]>([])
const worldFolder = ref('')
const loadingWorlds = ref(false)
const targetName = ref('world')
const nameTouched = ref(false)
const activateAfter = ref(true)
const confirmUpload = ref(false)

watch(worldFrom, async (id) => {
  worlds.value = []
  worldFolder.value = ''
  if (!id) return
  loadingWorlds.value = true
  try {
    worlds.value = await backend.instanceWorlds(id)
    worldFolder.value = worlds.value[0]?.folder ?? ''
  } catch (e) {
    useToasts().error(e)
  } finally {
    loadingWorlds.value = false
  }
})

// Zielname folgt der gewählten Welt, bis man ihn selbst ändert.
watch([source, worldFolder, names], () => {
  if (nameTouched.value) return
  const base = source.value === 'instance' && worldFolder.value ? suggestWorldName(worldFolder.value) : 'world'
  targetName.value = uniqueWorldName(base, names.value)
})

const nameValid = computed(() => validWorldName(targetName.value))
const replaces = computed(() => names.value.includes(targetName.value))
const canUpload = computed(() => nameValid.value && !busy.value && (source.value !== 'instance' || !!worldFolder.value))

function startUpload() {
  if (!canUpload.value) return
  if (replaces.value) confirmUpload.value = true
  else whenOffline(upload)
}
function confirmReplace() {
  confirmUpload.value = false
  whenOffline(upload)
}
async function upload() {
  const target = { name: targetName.value, activate: activateAfter.value }
  const ok =
    source.value === 'instance'
      ? await store.uploadWorld(worldFrom.value, worldFolder.value, target)
      : await store.uploadWorldDisk(source.value, target)
  if (ok) {
    nameTouched.value = false
    void store.loadWorlds()
  }
}

const progressText = computed(() => {
  const p = store.worldProgress
  return p ? t(`craftinghosting.world.phase.${p.phase}`, { percent: p.percent }) : ''
})

const SOURCE_ICON: Record<Source, string> = {
  instance: 'M4 6h16v12H4zM4 10h16M8 6v4',
  zip: 'M4 4h16v4H4zM5 8h14v12H5zM10 12h4',
  folder: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
}
</script>

<template>
  <div class="space-y-4" data-testid="hosting-worlds">
    <!-- Server läuft -->
    <div v-if="!offline && !installing" class="flex flex-wrap items-center gap-3 rounded-lg bg-lamp-900/30 px-3 py-2 text-sm text-lamp-200 ring-1 ring-lamp-700/40">
      <svg viewBox="0 0 24 24" class="size-4 shrink-0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 8v4M12 16h.01" /></svg>
      <span class="min-w-0 flex-1">{{ t('craftinghosting.worlds.runningHint') }}</span>
      <button class="btn btn-ghost px-3 py-1 text-xs" :disabled="busy || stopping || server.state === 'stopping'" data-testid="hosting-worlds-stop" @click="stopNow">
        {{ stopping || server.state === 'stopping' ? t('craftinghosting.worlds.stopping') : t('craftinghosting.worlds.stop') }}
      </button>
    </div>

    <!-- Welten -->
    <section class="card" aria-labelledby="ch-worlds-title">
      <div class="flex items-center gap-2 border-b border-base-800 px-4 py-3">
        <div class="min-w-0 flex-1">
          <h3 id="ch-worlds-title" class="text-sm font-semibold text-base-50">{{ t('craftinghosting.worlds.title') }}</h3>
          <p class="text-xs text-base-400">{{ t('craftinghosting.worlds.text') }}</p>
        </div>
        <button class="btn-icon size-8" :disabled="store.worldsLoading || installing" :aria-label="t('common.actions.refresh')" :title="t('common.actions.refresh')" @click="store.loadWorlds()">
          <svg viewBox="0 0 24 24" class="size-4" :class="{ 'animate-spin motion-reduce:animate-none': store.worldsLoading }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7" /></svg>
        </button>
      </div>

      <p v-if="installing" class="p-4 text-sm text-base-400">{{ t('craftinghosting.files.pending') }}</p>
      <div v-else-if="!store.worlds && store.worldsLoading" class="space-y-2 p-4">
        <div v-for="i in 2" :key="i" class="skeleton h-14" />
      </div>
      <template v-else-if="store.worlds">
        <p v-if="!store.worlds.exists" class="mx-4 mt-3 rounded-md bg-base-900 px-3 py-2 text-xs text-base-300 ring-1 ring-base-800" data-testid="hosting-world-generated">
          {{ t('craftinghosting.worlds.notGenerated', { name: store.worlds.active }) }}
        </p>
        <p v-if="!list.length" class="p-4 text-sm text-base-500">{{ t('craftinghosting.worlds.empty') }}</p>
        <ul v-else class="divide-y divide-base-800/60" data-testid="hosting-worlds-list">
          <li v-for="w in list" :key="w.name" class="flex flex-wrap items-center gap-3 px-4 py-3" :data-testid="`hosting-world-${w.name}`">
            <span class="grid size-10 shrink-0 place-items-center rounded-lg ring-1" :class="w.active ? 'bg-emerald-950/40 text-emerald-300 ring-emerald-800/50' : 'bg-base-800 text-base-300 ring-base-700'">
              <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3.6 9h16.8M3.6 15h16.8M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" /></svg>
            </span>
            <div class="min-w-0 flex-1">
              <p class="flex items-center gap-2 text-sm font-semibold text-base-50">
                <span class="truncate font-mono">{{ w.name }}</span>
                <span v-if="w.active" class="badge bg-emerald-900/50 text-emerald-300 ring-1 ring-emerald-700/40">{{ t('craftinghosting.worlds.active') }}</span>
              </p>
              <p class="mt-1 flex flex-wrap gap-1">
                <span v-for="d in w.dimensions" :key="d" class="rounded px-1.5 py-0.5 text-[11px] ring-1" :class="DIM_TONE[dimensionId(d) ?? ''] ?? 'bg-base-800 text-base-300 ring-base-700'">{{ dimLabel(d) }}</span>
              </p>
            </div>
            <div class="flex flex-wrap items-center gap-1.5">
              <button v-if="!w.active" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="busy" :data-testid="`hosting-world-activate-${w.name}`" @click="activate(w)">
                {{ t('craftinghosting.worlds.activate') }}
              </button>
              <button class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="!!downloading" :data-testid="`hosting-world-download-${w.name}`" @click="store.downloadWorld(w.name)">
                {{ t('craftinghosting.worlds.download') }}
              </button>
              <button class="btn btn-ghost px-2.5 py-1 text-xs text-redstone-300" :disabled="busy" :data-testid="`hosting-world-remove-${w.name}`" @click="askRemove(w)">
                {{ w.active ? t('craftinghosting.worlds.resetButton') : t('craftinghosting.worlds.deleteButton') }}
              </button>
            </div>
          </li>
        </ul>
      </template>

      <div v-if="downloading" class="border-t border-base-800 px-4 py-3" aria-live="polite" data-testid="hosting-world-downloading">
        <div class="flex items-center gap-3">
          <div class="min-w-0 flex-1">
            <p class="truncate text-xs text-base-300">{{ t('craftinghosting.worlds.downloading', { name: downloading.name }) }} · {{ downloadText }}</p>
            <div class="mt-1.5 h-1.5 overflow-hidden rounded-full bg-base-800">
              <div class="h-full bg-sky-400 transition-[width] motion-reduce:transition-none" :style="{ width: `${downloading.progress?.percent ?? 0}%` }" />
            </div>
          </div>
          <button class="btn btn-ghost px-2.5 py-1 text-xs" data-testid="hosting-world-download-cancel" @click="store.cancelWorldDownload()">{{ t('common.actions.cancel') }}</button>
        </div>
      </div>
    </section>

    <!-- Hochladen -->
    <section class="card p-4 text-sm" aria-labelledby="ch-upload-title" data-testid="hosting-world">
      <h3 id="ch-upload-title" class="font-semibold text-base-50">{{ t('craftinghosting.worlds.uploadTitle') }}</h3>
      <p class="mt-0.5 mb-3 text-xs text-base-400">{{ t('craftinghosting.worlds.uploadText') }}</p>

      <div class="mb-3 grid grid-cols-3 gap-2" role="radiogroup" :aria-label="t('craftinghosting.worlds.source')">
        <button
          v-for="s in SOURCES"
          :key="s"
          type="button"
          role="radio"
          :aria-checked="source === s"
          class="flex flex-col items-center gap-1.5 rounded-lg px-2 py-2.5 text-xs ring-1 transition-colors motion-reduce:transition-none"
          :class="source === s ? 'bg-redstone-900/30 text-base-50 ring-redstone-500/70' : 'bg-base-900 text-base-300 ring-base-800 hover:ring-base-600'"
          :data-testid="`hosting-world-source-${s}`"
          @click="source = s"
        >
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="SOURCE_ICON[s]" /></svg>
          {{ t(`craftinghosting.worlds.sources.${s}`) }}
        </button>
      </div>

      <div v-if="source === 'instance'" class="grid gap-2 sm:grid-cols-2">
        <select v-model="worldFrom" class="field" :aria-label="t('craftinghosting.world.instance')">
          <option v-for="i in instances.items" :key="i.id" :value="i.id">{{ i.name }} · {{ i.gameVersion }}</option>
        </select>
        <select v-model="worldFolder" class="field" :disabled="loadingWorlds || !worlds.length" :aria-label="t('craftinghosting.world.world')">
          <option v-for="w in worlds" :key="w.folder" :value="w.folder">{{ w.name }}{{ w.version ? ` · ${w.version}` : '' }}</option>
        </select>
        <p v-if="!loadingWorlds && worldFrom && !worlds.length" class="text-xs text-base-500 sm:col-span-2">{{ t('craftinghosting.world.none') }}</p>
      </div>
      <p v-else class="text-xs text-base-400">{{ source === 'zip' ? t('craftinghosting.worlds.sourceHint.zip') : t('craftinghosting.worlds.sourceHint.folder') }}</p>

      <div class="mt-3 grid gap-3 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-end">
        <div>
          <label for="ch-world-name" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.worlds.targetName') }}</label>
          <input
            id="ch-world-name"
            v-model="targetName"
            class="field font-mono"
            :class="{ 'ring-1 ring-redstone-500': !nameValid }"
            :maxlength="WORLD_NAME_MAX"
            autocomplete="off"
            spellcheck="false"
            data-testid="hosting-world-name"
            @input="nameTouched = true"
          />
        </div>
        <label class="flex cursor-pointer items-center gap-2 pb-2 text-sm text-base-100">
          <ToggleSwitch v-model="activateAfter" :label="t('craftinghosting.worlds.activateAfter')" />
          {{ t('craftinghosting.worlds.activateAfter') }}
        </label>
      </div>
      <p class="mt-1 text-xs" :class="!nameValid ? 'text-redstone-300' : replaces ? 'text-lamp-300' : 'text-base-500'">
        {{ !nameValid ? t('errors.craftinghosting.invalidWorldName') : replaces ? t('craftinghosting.worlds.replaces', { name: targetName }) : t('craftinghosting.worlds.nameHint') }}
      </p>

      <div v-if="store.worldProgress" class="mt-3" aria-live="polite">
        <div class="h-1.5 overflow-hidden rounded-full bg-base-800">
          <div class="h-full bg-redstone-500 transition-[width] motion-reduce:transition-none" :style="{ width: `${store.worldProgress.percent}%` }" />
        </div>
        <p class="mt-1 text-xs text-base-400">{{ progressText }}</p>
      </div>
      <button v-else class="btn btn-primary mt-3" :disabled="!canUpload" data-testid="hosting-world-upload" @click="startUpload">
        {{ source === 'instance' ? t('craftinghosting.worlds.upload') : t('craftinghosting.worlds.chooseAndUpload') }}
      </button>
    </section>

    <BaseDialog v-if="removing" :title="t(removing.active ? 'craftinghosting.worlds.resetTitle' : 'craftinghosting.worlds.deleteTitle', { name: removing.name })" @close="removing = null">
      <p class="text-sm text-base-200">{{ removing.active ? t('craftinghosting.worlds.resetText') : t('craftinghosting.worlds.deleteText') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="removing = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" data-testid="hosting-world-remove-confirm" @click="confirmRemove">
          {{ removing.active ? t('craftinghosting.worlds.resetButton') : t('craftinghosting.worlds.deleteButton') }}
        </button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="confirmUpload" :title="t('craftinghosting.world.confirmTitle')" @close="confirmUpload = false">
      <p class="text-sm text-base-200">{{ t('craftinghosting.worlds.replaceText', { name: targetName }) }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="confirmUpload = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" data-testid="hosting-world-confirm" @click="confirmReplace">{{ t('craftinghosting.world.confirm') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="stopAsk" :title="t('craftinghosting.worlds.stopTitle')" @close="stopAsk = null">
      <p class="text-sm text-base-200">{{ t('craftinghosting.worlds.stopText') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="stopAsk = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" data-testid="hosting-worlds-stop-confirm" @click="stopThen">{{ t('craftinghosting.worlds.stopAndContinue') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
