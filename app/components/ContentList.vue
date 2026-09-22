<script setup lang="ts">
import type { ContentItem, ContentKind, ContentUpdate, Instance } from '~/types'

const props = defineProps<{ instance: Instance }>()

const kind = ref<ContentKind>(props.instance.loader.kind === 'vanilla' ? 'resourcepack' : 'mod')
const items = ref<ContentItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const filter = ref('')
const toDelete = ref<ContentItem | null>(null)
const toasts = useToasts()
const updates = ref<ContentUpdate[] | null>(null)
const checking = ref(false)
const updatingAll = ref(false)
const packBusy = ref(false)

const updateFor = (item: ContentItem) => updates.value?.find((u) => u.kind === item.kind && u.fileName === item.fileName)
const pendingUpdates = computed(() => (updates.value ?? []).filter((u) => u.kind === kind.value))

async function checkUpdates() {
  checking.value = true
  try {
    updates.value = await backend.checkContentUpdates(props.instance.id)
    if (!updates.value.length) toasts.info('Alles ist aktuell')
  } catch (e) {
    toasts.error(e)
  } finally {
    checking.value = false
  }
}

async function applyUpdate(update: ContentUpdate) {
  await backend.applyContentUpdate(props.instance.id, update)
  updates.value = (updates.value ?? []).filter((u) => u !== update)
}

async function updateAll() {
  updatingAll.value = true
  try {
    for (const u of [...pendingUpdates.value]) await applyUpdate(u)
    toasts.ok('Updates installiert')
  } catch (e) {
    toasts.error(e)
  } finally {
    updatingAll.value = false
    load()
  }
}

async function updateOne(update: ContentUpdate) {
  try {
    await applyUpdate(update)
    toasts.ok(`Auf ${update.versionNumber} aktualisiert`)
  } catch (e) {
    toasts.error(e)
  } finally {
    load()
  }
}

async function installPerformancePack() {
  packBusy.value = true
  try {
    const files = await backend.installPerformancePack(props.instance.id)
    toasts.ok(`Performance-Paket installiert (${files.length} Dateien)`)
    kind.value = 'mod'
    load()
  } catch (e) {
    toasts.error(e)
  } finally {
    packBusy.value = false
  }
}

const isVanilla = computed(() => props.instance.loader.kind === 'vanilla')

const visible = computed(() => {
  const needle = filter.value.trim().toLowerCase()
  if (!needle) return items.value
  return items.value.filter((i) => `${i.title ?? ''} ${i.fileName}`.toLowerCase().includes(needle))
})
const enabledCount = computed(() => items.value.filter((i) => i.enabled).length)

async function load() {
  loading.value = true
  error.value = null
  try {
    items.value = await backend.listContent(props.instance.id, kind.value)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
watch(kind, load, { immediate: true })

async function toggle(item: ContentItem) {
  error.value = null
  try {
    await backend.setContentEnabled(props.instance.id, item.kind, item.fileName, !item.enabled)
    item.enabled = !item.enabled
  } catch (e) {
    error.value = errorMessage(e)
    load()
  }
}

async function confirmDelete() {
  const item = toDelete.value
  toDelete.value = null
  if (!item) return
  try {
    await backend.deleteContent(props.instance.id, item.kind, item.fileName)
    items.value = items.value.filter((i) => i.fileName !== item.fileName)
  } catch (e) {
    error.value = errorMessage(e)
  }
}
</script>

<template>
  <div class="flex min-h-0 flex-1 flex-col">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <div class="flex overflow-hidden rounded-md border border-base-700 text-xs">
        <button v-for="k in contentKinds" :key="k" class="seg" :class="{ 'seg-on': kind === k }" @click="kind = k">
          {{ contentKindLabels[k] }}
        </button>
      </div>
      <input v-model="filter" class="field h-8 max-w-56 py-0 text-xs" maxlength="100" placeholder="Filtern …" spellcheck="false" />
      <span v-if="items.length" class="text-xs text-base-600">{{ enabledCount }} von {{ items.length }} aktiv</span>
      <button class="btn btn-ghost ml-auto h-8 py-0 text-xs" :disabled="checking || !items.length" @click="checkUpdates">
        {{ checking ? 'Prüfe …' : 'Nach Updates suchen' }}
      </button>
      <button v-if="pendingUpdates.length" class="btn h-8 bg-lamp-900 py-0 text-xs text-lamp-300 ring-1 ring-lamp-400/40 hover:bg-base-800" :disabled="updatingAll" @click="updateAll">
        {{ updatingAll ? 'Aktualisiere …' : `${pendingUpdates.length} aktualisieren` }}
      </button>
      <button v-if="!isVanilla" class="btn btn-ghost h-8 py-0 text-xs" :disabled="packBusy" title="Sodium, Lithium & Co. – nur was es für diese Version gibt" @click="installPerformancePack">
        {{ packBusy ? 'Installiere …' : 'Performance-Paket' }}
      </button>
      <NuxtLink :to="{ path: '/browse', query: { instance: instance.id, kind } }" class="btn btn-primary h-8 py-0 text-xs">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        {{ contentKindLabels[kind] }} hinzufügen
      </NuxtLink>
    </div>

    <p v-if="error" role="alert" class="card mb-3 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ error }}</p>

    <p v-if="kind === 'mod' && isVanilla" class="card mb-3 border-warn/40 px-4 py-2.5 text-sm text-warn">
      Das ist eine Vanilla-Instanz – Mods werden hier nicht geladen. Erstelle dafür eine Instanz mit Fabric oder Quilt.
    </p>

    <div v-if="loading && !items.length" class="space-y-1.5">
      <div v-for="i in 5" :key="i" class="skeleton h-14" />
    </div>

    <ul v-else-if="visible.length" class="min-h-0 flex-1 space-y-1.5 overflow-y-auto pr-1">
      <li v-for="item in visible" :key="item.fileName" class="card flex items-center gap-3 px-3 py-2.5" :class="{ 'opacity-55': !item.enabled }">
        <button
          role="switch"
          :aria-checked="item.enabled"
          :aria-label="`${item.title ?? item.fileName} ${item.enabled ? 'deaktivieren' : 'aktivieren'}`"
          class="relative h-5 w-9 shrink-0 rounded-full transition-colors"
          :class="item.enabled ? 'bg-redstone-500' : 'bg-base-700'"
          @click="toggle(item)"
        >
          <span class="absolute top-0.5 left-0.5 size-4 rounded-full bg-white transition-transform" :class="{ 'translate-x-4': item.enabled }" />
        </button>
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-medium">
            {{ item.title ?? item.fileName }}
            <span v-if="item.version" class="ml-1 font-mono text-xs font-normal text-base-400">{{ item.version }}</span>
          </p>
          <p class="truncate text-xs text-base-400" :title="item.description ?? item.fileName">
            {{ item.description ?? item.fileName }}
          </p>
        </div>
        <button v-if="updateFor(item)" class="shrink-0 rounded bg-lamp-900 px-2 py-0.5 text-[11px] font-medium text-lamp-300 hover:bg-base-700" :title="`Update auf ${updateFor(item)!.versionNumber}`" @click="updateOne(updateFor(item)!)">
          Update
        </button>
        <span v-if="item.source" class="shrink-0 rounded bg-base-800 px-1.5 py-0.5 text-[10px] font-medium text-ok" title="Über Modrinth installiert">Modrinth</span>
        <span class="w-16 shrink-0 text-right font-mono text-xs text-base-600">{{ formatFileSize(item.size) }}</span>
        <button class="btn btn-ghost shrink-0 px-2 py-1.5 hover:text-redstone-300" title="Löschen" aria-label="Löschen" @click="toDelete = item">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
          </svg>
        </button>
      </li>
    </ul>

    <div v-else-if="!loading" class="card px-6 py-12 text-center">
      <h2 class="font-semibold">{{ items.length ? 'Nichts gefunden' : `Noch keine ${contentKindLabels[kind]}` }}</h2>
      <p class="mx-auto mt-1 max-w-md text-sm text-base-400">
        {{ items.length ? 'Kein Eintrag passt zum Filter.' : 'Installiere welche über „Entdecken“ oder lege Dateien direkt in den Instanz-Ordner.' }}
      </p>
    </div>

    <BaseDialog v-if="toDelete" title="Datei löschen?" @close="toDelete = null">
      <p class="text-sm text-base-200">
        <strong class="text-base-50">{{ toDelete.title ?? toDelete.fileName }}</strong> wird aus dieser Instanz gelöscht.
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">Abbrechen</button>
        <button class="btn btn-danger" @click="confirmDelete">Löschen</button>
      </template>
    </BaseDialog>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.seg {
  @apply px-3 py-1.5 text-base-400 transition-colors hover:text-base-50;
}
.seg-on {
  @apply bg-base-700 text-base-50;
}
</style>
