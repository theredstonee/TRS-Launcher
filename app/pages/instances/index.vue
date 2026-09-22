<script setup lang="ts">
import type { Instance } from '~/types'

const instances = useInstancesStore()
const settings = useSettingsStore()

const creating = ref(false)
const toDelete = ref<Instance | null>(null)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

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
      <button class="btn btn-primary" @click="creating = true">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        Neue Instanz
      </button>
    </PageHeader>

    <p v-if="instances.error" role="alert" class="card mb-4 border-redstone-600/50 px-4 py-3 text-sm text-redstone-300">
      {{ instances.error }}
    </p>

    <div v-if="instances.loading && !instances.items.length" class="grid grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-4">
      <div v-for="i in 3" :key="i" class="skeleton h-40" />
    </div>

    <div v-else-if="instances.items.length" class="grid grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-4">
      <InstanceCard v-for="i in instances.items" :key="i.id" :instance="i" @delete="toDelete = $event" />
    </div>

    <div v-else-if="!instances.loading && !instances.error" class="card flex flex-col items-center px-6 py-16 text-center">
      <img src="/icon.png" alt="" class="size-14 opacity-80 [image-rendering:pixelated]" />
      <h2 class="mt-5 font-semibold">Noch keine Instanz</h2>
      <p class="mt-1 max-w-sm text-sm text-base-400">
        Erstelle deine erste Instanz – Vanilla oder mit Fabric, Quilt, Forge oder NeoForge.
      </p>
      <button class="btn btn-primary mt-5" @click="creating = true">Instanz erstellen</button>
    </div>

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
