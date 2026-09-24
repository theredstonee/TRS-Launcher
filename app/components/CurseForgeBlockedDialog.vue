<script setup lang="ts">
import type { BlockedFile } from '~/types'

// Dateien, die ihr Autor nur auf CurseForge selbst anbietet: Der Launcher lädt
// sie NICHT über Umwege. Der Nutzer öffnet die Dateiseite, lädt die Datei, und
// der Launcher übernimmt sie aus dem Download-Ordner, sobald Name und SHA1
// passen (oder wenn sie direkt im Zielordner liegt).
const props = defineProps<{ instanceId: string }>()
const emit = defineEmits<{ close: [] }>()

const toasts = useToasts()
const curseforge = useCurseForgeStore()
const instances = useInstancesStore()

const files = ref<BlockedFile[]>([])
const watchFolder = ref<string | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const opened = ref<Set<string>>(new Set())
const instanceName = computed(() => instances.items.find((i) => i.id === props.instanceId)?.name ?? '')
/** Zielordner der ersten Datei (meist Mods). */
const folderKind = computed(() => files.value[0]?.kind ?? 'mod')

let timer: ReturnType<typeof setInterval> | undefined
let busy = false

async function check() {
  if (busy) return
  busy = true
  try {
    const result = await backend.curseforge.adoptDownloads(props.instanceId)
    const hadFiles = files.value.length > 0
    files.value = result.pending
    watchFolder.value = result.watchFolder
    error.value = null
    if (result.adopted.length) {
      toasts.ok(t('curseforge.blocked.adopted', { count: result.adopted.length }, result.adopted.length))
      curseforge.touch()
    }
    if (hadFiles && !result.pending.length) {
      toasts.ok(t('curseforge.blocked.allDone'))
      close()
    }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy = false
    loading.value = false
  }
}

onMounted(() => {
  if (!instances.items.length) void instances.load()
  void check()
  // Solange der Dialog offen ist, alle 3 Sekunden im Download-Ordner nachsehen.
  timer = setInterval(() => void check(), 3000)
})
onBeforeUnmount(() => clearInterval(timer))

function open(file: BlockedFile) {
  opened.value = new Set([...opened.value, file.fileId])
  backend.openExternalUrl(file.url).catch((e) => toasts.error(e))
}

async function skip(file: BlockedFile | null) {
  try {
    files.value = await backend.curseforge.dismissBlocked(props.instanceId, file?.fileId ?? null)
    curseforge.touch()
    if (!files.value.length) close()
  } catch (e) {
    toasts.error(e)
  }
}

function openFolder() {
  backend.openContentDir(props.instanceId, folderKind.value).catch((e) => toasts.error(e))
}

function close() {
  clearInterval(timer)
  emit('close')
}
</script>

<template>
  <BaseDialog :title="t('curseforge.blocked.title', { name: instanceName })" wide @close="close">
    <p class="text-sm leading-relaxed text-base-200">{{ t('curseforge.blocked.intro') }}</p>
    <p class="mt-2 flex items-center gap-2 text-xs text-base-400">
      <span class="relative flex size-2">
        <span class="absolute inline-flex size-full animate-ping rounded-full bg-redstone-400 opacity-60 motion-reduce:animate-none" />
        <span class="relative inline-flex size-2 rounded-full bg-redstone-500" />
      </span>
      <span v-if="watchFolder" class="truncate">{{ t('curseforge.blocked.watching', { folder: watchFolder }) }}</span>
      <span v-else>{{ t('curseforge.blocked.noWatchFolder') }}</span>
    </p>

    <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>

    <div v-if="loading" class="mt-4 space-y-2">
      <div v-for="i in 2" :key="i" class="skeleton h-14" />
    </div>
    <ul v-else class="-mr-2 mt-4 max-h-[22rem] space-y-1.5 overflow-y-auto pr-2">
      <li v-for="f in files" :key="f.fileId" class="flex items-center gap-3 rounded-lg border border-base-700 bg-base-900 px-3 py-2">
        <ModIcon :src="f.iconUrl" :name="f.title" :size="36" />
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-medium">{{ f.title }}</p>
          <p class="truncate font-mono text-[11px] text-base-400" :title="f.fileName">
            {{ f.fileName }}<template v-if="f.size"> · {{ formatFileSize(f.size) }}</template>
          </p>
          <p v-if="!f.sha1" class="text-[11px] text-warn">{{ t('curseforge.blocked.noChecksum') }}</p>
          <p v-else-if="opened.has(f.fileId)" class="text-[11px] text-base-400">{{ t('curseforge.blocked.waiting') }}</p>
        </div>
        <button class="btn btn-primary shrink-0 px-3 py-1.5 text-xs" @click="open(f)">
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" /></svg>
          {{ t('curseforge.openOnCurseForge') }}
        </button>
        <button class="shrink-0 text-xs text-base-400 hover:text-base-50" @click="skip(f)">{{ t('curseforge.blocked.skip') }}</button>
      </li>
    </ul>

    <template #actions>
      <button v-if="files.length > 1" class="btn btn-ghost mr-auto text-xs" @click="skip(null)">{{ t('curseforge.blocked.skipAll') }}</button>
      <button class="btn btn-ghost" @click="openFolder">{{ t('curseforge.blocked.openFolder') }}</button>
      <button class="btn btn-ghost" @click="close">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>
</template>
