<script setup lang="ts">
import type { ContentKind, Instance, ModrinthVersion } from '~/types'

// Wählt eine zur Instanz passende Version – zum Installieren oder, mit
// `currentVersionId`, zum Wechseln (auch auf ältere Versionen).
const props = defineProps<{
  instance: Instance
  projectId: string
  title: string
  kind: ContentKind
  currentVersionId?: string | null
}>()
const emit = defineEmits<{ close: []; pick: [version: ModrinthVersion] }>()

const versions = ref<ModrinthVersion[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const showPrerelease = ref(false)
const open = ref<string | null>(null)

const currentIndex = computed(() => versions.value.findIndex((v) => v.id === props.currentVersionId))
const visible = computed(() =>
  showPrerelease.value
    ? versions.value
    : versions.value.filter((v) => v.versionType === 'release' || v.id === props.currentVersionId),
)

onMounted(async () => {
  try {
    versions.value = await backend.modrinthVersions(props.instance.id, props.projectId, props.kind)
    // Gibt es nur Betas, sollen die nicht hinter dem Schalter verschwinden.
    if (!versions.value.some((v) => v.versionType === 'release')) showPrerelease.value = true
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
})

function relation(v: ModrinthVersion): string | null {
  if (!props.currentVersionId || currentIndex.value < 0 || v.id === props.currentVersionId) return null
  return versions.value.indexOf(v) < currentIndex.value ? 'Neuer' : 'Älter'
}
</script>

<template>
  <BaseDialog :title="currentVersionId ? `Version wechseln – ${title}` : `Version wählen – ${title}`" wide @close="emit('close')">
    <div class="mb-3 flex items-center justify-between text-xs text-base-400">
      <span>Passend zu {{ instance.gameVersion }} ({{ loaderLabels[instance.loader.kind] }})</span>
      <label class="flex items-center gap-1.5">
        <input v-model="showPrerelease" type="checkbox" class="accent-redstone-500" />
        Beta und Alpha zeigen
      </label>
    </div>

    <div v-if="loading" class="space-y-2">
      <div v-for="i in 4" :key="i" class="skeleton h-14" />
    </div>
    <p v-else-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    <p v-else-if="!visible.length" class="py-6 text-center text-sm text-base-400">Keine passende Version gefunden.</p>

    <ul v-else class="-mr-2 max-h-[26rem] space-y-1.5 overflow-y-auto pr-2">
      <li v-for="(v, i) in visible" :key="v.id" class="overflow-hidden rounded-lg border bg-base-900" :class="v.id === currentVersionId ? 'border-redstone-600/60' : 'border-base-700'">
        <div class="flex items-center gap-2 px-2 py-2">
          <button
            class="flex size-7 shrink-0 items-center justify-center rounded-md text-base-400 hover:bg-base-800 hover:text-base-50 disabled:opacity-30"
            :disabled="!v.changelog"
            :aria-expanded="open === v.id"
            :aria-label="open === v.id ? 'Changelog zuklappen' : 'Changelog zeigen'"
            @click="open = open === v.id ? null : v.id"
          >
            <svg viewBox="0 0 24 24" class="size-4 transition-transform" :class="{ 'rotate-90': open === v.id }" fill="none" stroke="currentColor" stroke-width="2.2"><path d="m9 6 6 6-6 6" /></svg>
          </button>
          <div class="min-w-0 flex-1">
            <p class="flex items-center gap-1.5 truncate text-sm font-medium">
              <span class="truncate">{{ v.versionNumber }}</span>
              <span v-if="v.id === currentVersionId" class="badge bg-redstone-900 text-redstone-300">Installiert</span>
              <span v-else-if="i === 0" class="badge bg-base-800 text-base-200">Neueste</span>
              <span v-if="relation(v)" class="badge bg-base-850 text-base-400">{{ relation(v) }}</span>
            </p>
            <p class="truncate text-xs text-base-400">
              <span :class="v.versionType === 'release' ? 'text-ok' : 'text-lamp-400'">{{ versionTypeLabels[v.versionType] ?? v.versionType }}</span>
              · {{ formatDate(v.datePublished) }} · {{ formatFileSize(v.size) }}
            </p>
          </div>
          <button
            v-if="v.id !== currentVersionId"
            class="btn shrink-0 px-3 py-1.5 text-xs"
            :class="currentVersionId ? 'btn-ghost' : 'btn-primary'"
            @click="emit('pick', v)"
          >
            {{ !currentVersionId ? 'Installieren' : relation(v) === 'Älter' ? 'Zurückstufen' : relation(v) === 'Neuer' ? 'Aktualisieren' : 'Wechseln' }}
          </button>
        </div>
        <div v-if="open === v.id && v.changelog" class="border-t border-base-800 bg-base-950/40 px-4 py-3">
          <MarkdownView :source="v.changelog" />
        </div>
      </li>
    </ul>

    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">Schließen</button>
    </template>
  </BaseDialog>
</template>
