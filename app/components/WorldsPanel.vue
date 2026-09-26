<script setup lang="ts">
import { convertFileSrc } from '@tauri-apps/api/core'
import type { GameMode, Instance, InstanceServer, WorldInfo } from '~/types'

// Tab „Welten“: Einzelspieler-Welten (Name, Modus, Version, Größe; Ordner,
// Sicherung, Löschen) und die Server dieser Instanz (servers.dat).
const props = defineProps<{ instance: Instance }>()

const toasts = useToasts()
const games = useGamesStore()
const tasks = useTasksStore()
const servers = useServersStore()
const game = computed(() => games.state(props.instance.id))
const running = computed(() => game.value.phase !== 'idle')

const worlds = ref<WorldInfo[]>([])
const serverList = ref<InstanceServer[]>([])
const loadingWorlds = ref(true)
const loadingServers = ref(true)
const toDelete = ref<WorldInfo | null>(null)
const editing = ref<InstanceServer | null | 'new'>(null)

async function loadWorlds() {
  loadingWorlds.value = true
  try {
    worlds.value = await backend.instanceWorlds(props.instance.id)
  } catch (e) {
    toasts.error(e)
  } finally {
    loadingWorlds.value = false
  }
}
async function loadServers() {
  loadingServers.value = true
  try {
    serverList.value = await backend.instanceServers(props.instance.id)
  } catch (e) {
    toasts.error(e)
  } finally {
    loadingServers.value = false
  }
}
onMounted(() => {
  loadWorlds()
  loadServers()
  if (!servers.loaded) servers.load().catch(() => {})
})
// Nach dem Spielen: neue Welten/Server aus dem Spiel übernehmen.
watch(
  () => game.value.phase,
  (phase, before) => {
    if (phase === 'idle' && before !== 'idle') {
      loadWorlds()
      loadServers()
    }
  },
)

// --- Welten ---------------------------------------------------------------------
const icon = (w: WorldInfo) => (w.iconPath ? convertFileSrc(w.iconPath) : null)
const MODE_CLASS: Record<GameMode, string> = {
  survival: 'bg-ok/15 text-ok ring-ok/30',
  creative: 'bg-sky-400/15 text-sky-300 ring-sky-400/30',
  adventure: 'bg-lamp-400/15 text-lamp-300 ring-lamp-400/30',
  spectator: 'bg-violet-400/15 text-violet-300 ring-violet-400/30',
}

function backupKey(folder: string) {
  return taskKey('world-backup', props.instance.id, folder)
}
function backupTask(folder: string) {
  return tasks.get(backupKey(folder))
}

function backup(w: WorldInfo) {
  tasks.run(
    {
      key: backupKey(w.folder),
      kind: 'export',
      title: w.name,
      stage: t('worlds.backingUp'),
      instanceId: props.instance.id,
      cancellable: true,
      doneText: t('worlds.backupDone', { name: w.name }),
    },
    (ctx) => backend.backupWorld(props.instance.id, w.folder, (p) => ctx.progress(p), ctx.taskId),
  )
}

function openFolder(w: WorldInfo) {
  backend.openWorldFolder(props.instance.id, w.folder).catch((e) => toasts.error(e))
}

async function confirmDelete() {
  const w = toDelete.value
  toDelete.value = null
  if (!w) return
  try {
    await backend.trashWorld(props.instance.id, w.folder)
    worlds.value = worlds.value.filter((x) => x.folder !== w.folder)
    toasts.ok(t('worlds.deleted', { name: w.name }))
  } catch (e) {
    toasts.error(e)
  }
}

// --- Server ---------------------------------------------------------------------
function statusOf(s: InstanceServer) {
  return s.launcherId ? servers.statuses[s.launcherId] : undefined
}
function join(s: InstanceServer) {
  if (running.value) return
  games.launch(props.instance.id, s.launcherId, s.launcherId ? null : s.address)
}
const joinTitle = (s: InstanceServer) =>
  running.value ? t('worlds.servers.running') : s.joinable ? t('worlds.servers.joinHint', { name: s.name }) : t('worlds.servers.notJoinable')
</script>

<template>
  <div class="min-h-0 flex-1 space-y-6 overflow-y-auto pr-1 pb-4">
    <!-- Welten -->
    <section :aria-label="t('worlds.title')">
      <header class="mb-3 flex items-center gap-2">
        <h2 class="heading text-base">{{ t('worlds.title') }}</h2>
        <span v-if="worlds.length" class="chip">{{ formatNumber(worlds.length) }}</span>
        <button class="btn-icon ml-auto size-8" :title="t('common.actions.refresh')" :aria-label="t('common.actions.refresh')" @click="loadWorlds">
          <svg viewBox="0 0 24 24" class="size-4" :class="{ 'animate-spin': loadingWorlds }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path :d="icons.sync" /></svg>
        </button>
      </header>

      <div v-if="loadingWorlds && !worlds.length" class="grid grid-cols-[repeat(auto-fill,minmax(19rem,1fr))] gap-3">
        <div v-for="i in 3" :key="i" class="skeleton h-28" />
      </div>
      <RedstoneEmpty v-else-if="!worlds.length" compact :seed="0x66" :title="t('worlds.empty.title')" :text="t('worlds.empty.text')" />
      <ul v-else class="grid grid-cols-[repeat(auto-fill,minmax(19rem,1fr))] gap-3">
        <li v-for="w in worlds" :key="w.folder" class="world card card-hover flex gap-3 p-3">
          <div class="relative shrink-0 self-start">
            <img v-if="icon(w)" :src="icon(w)!" alt="" class="size-16 rounded-lg ring-1 ring-base-700 [image-rendering:pixelated]" />
            <div v-else class="world-fallback display flex size-16 items-center justify-center rounded-lg text-2xl text-base-50/80 ring-1 ring-base-700">
              {{ w.name.charAt(0).toUpperCase() }}
            </div>
            <span v-if="w.hardcore" class="absolute -right-1.5 -bottom-1.5 flex size-5 items-center justify-center rounded-full bg-redstone-600 text-[10px] text-white ring-2 ring-base-900" :title="t('worlds.hardcore')">♥</span>
          </div>
          <div class="min-w-0 flex-1">
            <p class="truncate font-medium text-base-50" :title="w.name">{{ w.name }}</p>
            <p class="truncate font-mono text-[11px] text-base-600" :title="`saves/${w.folder}`">{{ w.folder !== w.name ? w.folder : ' ' }}</p>
            <div class="mt-1.5 flex flex-wrap items-center gap-1">
              <span v-if="w.gameMode" class="badge ring-1" :class="MODE_CLASS[w.gameMode]">{{ t(`worlds.modes.${w.gameMode}`) }}</span>
              <span v-if="w.hardcore" class="badge bg-redstone-900 text-redstone-300 ring-1 ring-redstone-600/40">{{ t('worlds.hardcore') }}</span>
              <span v-if="w.cheats" class="badge bg-base-800 text-base-400">{{ t('worlds.cheats') }}</span>
              <span v-if="w.version" class="badge bg-base-800 font-mono text-base-200">{{ w.version }}</span>
            </div>
            <p class="mt-1.5 text-[11px] text-base-400">
              {{ formatRelative(w.lastPlayed) }}<template v-if="w.size !== null"> · {{ formatBytes(w.size) }}</template>
            </p>
            <div v-if="backupTask(w.folder)?.status === 'running'" class="mt-2 flex items-center gap-2">
              <RedstoneWire :percent="backupTask(w.folder)?.percent ?? 0" :segments="14" class="flex-1" />
              <span class="display text-[11px] tabular-nums text-redstone-300">{{ backupTask(w.folder)?.percent ?? 0 }} %</span>
            </div>
          </div>
          <div class="flex shrink-0 flex-col gap-1">
            <button class="btn-icon size-8" :title="t('common.actions.openFolder')" :aria-label="t('worlds.openFolderOf', { name: w.name })" @click="openFolder(w)">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" /></svg>
            </button>
            <button class="btn-icon size-8" :title="t('worlds.backup')" :aria-label="t('worlds.backupOf', { name: w.name })" :disabled="backupTask(w.folder)?.status === 'running'" @click="backup(w)">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M4 7h16v13H4zM2 4h20v3H2zM12 10v6m0 0-2.5-2.5M12 16l2.5-2.5" /></svg>
            </button>
            <button class="btn-icon size-8 hover:text-redstone-300" :title="running ? t('worlds.stopFirst') : t('common.actions.delete')" :aria-label="t('worlds.deleteOf', { name: w.name })" :disabled="running" @click="toDelete = w">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
            </button>
          </div>
        </li>
      </ul>
      <p v-if="worlds.length" class="mt-2 text-[11px] text-base-600">{{ t('worlds.backupHint') }}</p>
    </section>

    <!-- Server -->
    <section :aria-label="t('worlds.servers.title')">
      <header class="mb-3 flex items-center gap-2">
        <h2 class="heading text-base">{{ t('worlds.servers.title') }}</h2>
        <span v-if="serverList.length" class="chip">{{ formatNumber(serverList.length) }}</span>
        <button class="btn btn-primary ml-auto h-8 px-3 py-0 text-xs" @click="editing = 'new'">
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path :d="icons.plus" /></svg>
          {{ t('servers.add') }}
        </button>
      </header>

      <div v-if="loadingServers && !serverList.length" class="space-y-2">
        <div v-for="i in 2" :key="i" class="skeleton h-16" />
      </div>
      <RedstoneEmpty v-else-if="!serverList.length" compact :seed="0x3a" :title="t('worlds.servers.empty.title')" :text="t('worlds.servers.empty.text')">
        <button class="btn btn-primary" @click="editing = 'new'">{{ t('servers.add') }}</button>
      </RedstoneEmpty>
      <ul v-else class="space-y-2">
        <li v-for="s in serverList" :key="`${s.index ?? 'l'}-${s.address}`" class="card flex items-center gap-3 p-3 transition-colors hover:border-base-700">
          <div class="relative shrink-0">
            <img v-if="s.icon ?? statusOf(s)?.favicon" :src="(s.icon ?? statusOf(s)?.favicon)!" alt="" class="size-11 rounded ring-2 ring-base-800 [image-rendering:pixelated]" />
            <div v-else class="display flex size-11 items-center justify-center rounded bg-base-800 text-lg text-base-600 ring-2 ring-base-700">{{ s.name.charAt(0).toUpperCase() }}</div>
            <span
              v-if="s.launcherId"
              class="absolute -right-1 -bottom-1 size-3 border-2 border-base-900"
              :class="statusOf(s) === undefined ? 'animate-lamp bg-base-600' : statusOf(s)!.online ? 'bg-ok shadow-[0_0_8px_var(--color-ok)]' : 'bg-redstone-500'"
            />
          </div>
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2">
              <h3 class="truncate text-sm font-medium">{{ s.name }}</h3>
              <span v-if="s.launcherId" class="badge bg-redstone-900 text-redstone-300" :title="t('worlds.servers.managedTitle')">{{ t('worlds.servers.managed') }}</span>
              <span v-if="s.index === null" class="badge bg-base-800 text-base-400" :title="t('worlds.servers.pendingTitle')">{{ t('worlds.servers.pending') }}</span>
            </div>
            <p class="truncate font-mono text-[11px] text-base-400">
              {{ s.address }}
              <template v-if="statusOf(s)?.online"> · {{ statusOf(s)!.playersOnline }}/{{ statusOf(s)!.playersMax }} · {{ statusOf(s)!.latencyMs }} ms</template>
            </p>
          </div>
          <button class="btn-icon size-8" :title="t('common.actions.edit')" :aria-label="t('worlds.servers.editOf', { name: s.name })" @click="editing = s">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M4 20h4L19 9l-4-4L4 16zM14 6l4 4" /></svg>
          </button>
          <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="running || !s.joinable" :title="joinTitle(s)" @click="join(s)">
            <svg viewBox="0 0 24 24" class="size-3.5" fill="currentColor"><path :d="icons.play" /></svg>
            {{ t('servers.card.join') }}
          </button>
        </li>
      </ul>
      <p v-if="serverList.length" class="mt-2 text-[11px] text-base-600">{{ t('worlds.servers.joinNote') }}</p>
    </section>

    <BaseDialog v-if="toDelete" :title="t('worlds.deleteTitle')" @close="toDelete = null">
      <p class="text-sm text-base-200">{{ isLinux ? t('worlds.deleteTextLinux', { name: toDelete.name }) : t('worlds.deleteText', { name: toDelete.name }) }}</p>
      <p class="mt-2 text-xs text-base-400">{{ t('worlds.deleteBackupHint') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmDelete">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>

    <InstanceServerDialog
      v-if="editing"
      :instance-id="instance.id"
      :server="editing === 'new' ? null : editing"
      @close="editing = null"
      @saved="loadServers"
    />
  </div>
</template>

<style scoped>
.world-fallback {
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--color-ok) 35%, transparent) 0 30%, transparent 30%),
    linear-gradient(180deg, #5b3b24 30%, #3d2718 100%);
}
</style>
