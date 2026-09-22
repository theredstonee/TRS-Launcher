<script setup lang="ts">
import type { Instance } from '~/types'

const instances = useInstancesStore()
const settings = useSettingsStore()

const creating = ref(false)
const importing = ref(false)
const toDelete = ref<Instance | null>(null)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

const search = ref('')
const sort = ref<'played' | 'name'>('played')
const visible = computed(() => {
  const needle = search.value.trim().toLowerCase()
  const list = needle
    ? instances.items.filter((i) => `${i.name} ${i.gameVersion} ${loaderLabels[i.loader.kind]}`.toLowerCase().includes(needle))
    : [...instances.items]
  if (sort.value === 'name') list.sort((a, b) => a.name.localeCompare(b.name, 'de'))
  return list
})

onMounted(() => {
  instances.load()
  if (!settings.current) settings.load().catch(() => {})
})

async function confirmDelete() {
  if (!toDelete.value) return
  deleting.value = true
  deleteError.value = null
  try {
    await instances.remove(toDelete.value.id)
    toDelete.value = null
  } catch (e) {
    deleteError.value = errorMessage(e)
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div class="p-6">
    <PageHeader title="Instanzen" subtitle="Jede Instanz hat eigene Welten, Mods und Einstellungen.">
      <button class="btn btn-ghost" @click="importing = true">Importieren</button>
      <button class="btn btn-primary" @click="creating = true">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        Neue Instanz
      </button>
    </PageHeader>

    <p v-if="instances.error" role="alert" class="card mb-4 border-redstone-600/50 px-4 py-3 text-sm text-redstone-300">
      {{ instances.error }}
    </p>

    <div v-if="instances.items.length > 1" class="mb-4 flex items-center gap-2">
      <div class="relative">
        <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-base-600" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="11" cy="11" r="6" /><path d="m20 20-4.5-4.5" /></svg>
        <input v-model="search" class="field w-64 rounded-full pl-9" maxlength="64" placeholder="Instanzen durchsuchen …" spellcheck="false" aria-label="Instanzen durchsuchen" />
      </div>
      <div class="ml-auto flex rounded-full bg-base-900 p-0.5 ring-1 ring-base-800">
        <button class="tab px-3 py-1 text-xs" :class="{ 'tab-on': sort === 'played' }" @click="sort = 'played'">Zuletzt gespielt</button>
        <button class="tab px-3 py-1 text-xs" :class="{ 'tab-on': sort === 'name' }" @click="sort = 'name'">Name</button>
      </div>
    </div>

    <div v-if="instances.loading && !instances.items.length" class="grid grid-cols-[repeat(auto-fill,minmax(17rem,1fr))] gap-4">
      <div v-for="i in 3" :key="i" class="skeleton h-[164px] rounded-xl" />
    </div>

    <div v-else-if="visible.length" class="grid grid-cols-[repeat(auto-fill,minmax(17rem,1fr))] gap-4">
      <InstanceCard v-for="i in visible" :key="i.id" :instance="i" @delete="toDelete = $event" />
    </div>
    <p v-else-if="instances.items.length" class="py-16 text-center text-sm text-base-400">Keine Instanz passt zu „{{ search }}“.</p>

    <div v-else-if="!instances.loading && !instances.error" class="card flex flex-col items-center px-6 py-16 text-center">
      <img src="/icon.png" alt="" class="size-14 opacity-80 [image-rendering:pixelated]" />
      <h2 class="mt-5 font-semibold">Noch keine Instanz</h2>
      <p class="mt-1 max-w-sm text-sm text-base-400">
        Erstelle deine erste Instanz – Vanilla oder mit Fabric, Quilt, Forge oder NeoForge.
      </p>
      <div class="mt-5 flex justify-center gap-2">
        <button class="btn btn-primary" @click="creating = true">Instanz erstellen</button>
        <button class="btn btn-ghost" @click="importing = true">Aus anderem Launcher importieren</button>
      </div>
    </div>

    <ImportDialog v-if="importing" @close="importing = false" />
    <CreateInstanceDialog v-if="creating" @close="creating = false" @created="creating = false" />

    <BaseDialog v-if="toDelete" title="Instanz löschen?" @close="toDelete = null">
      <p class="text-sm text-base-200">
        <strong class="text-base-50">{{ toDelete.name }}</strong> wird mit allen Welten, Mods und Screenshots
        unwiderruflich gelöscht.
      </p>
      <p v-if="deleteError" role="alert" class="mt-3 text-sm text-redstone-300">{{ deleteError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">Abbrechen</button>
        <button class="btn btn-danger" :disabled="deleting" @click="confirmDelete">
          {{ deleting ? 'Lösche …' : 'Endgültig löschen' }}
        </button>
      </template>
    </BaseDialog>
  </div>
</template>
