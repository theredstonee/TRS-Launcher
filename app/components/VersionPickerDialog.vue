<script setup lang="ts">
import type { ContentKind, Instance, ModrinthVersion } from '~/types'

const props = defineProps<{ instance: Instance; projectId: string; title: string; kind: ContentKind }>()
const emit = defineEmits<{ close: []; pick: [version: ModrinthVersion] }>()

const versions = ref<ModrinthVersion[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const showPrerelease = ref(false)

const visible = computed(() =>
  showPrerelease.value ? versions.value : versions.value.filter((v) => v.versionType === 'release'),
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

const typeLabels: Record<string, string> = { release: 'Stabil', beta: 'Beta', alpha: 'Alpha' }
</script>

<template>
  <BaseDialog :title="`Version wählen – ${title}`" @close="emit('close')">
    <div class="mb-3 flex items-center justify-between text-xs text-base-400">
      <span>Passend zu {{ instance.gameVersion }} ({{ loaderLabels[instance.loader.kind] }})</span>
      <label class="flex items-center gap-1.5">
        <input v-model="showPrerelease" type="checkbox" class="accent-redstone-500" />
        Beta und Alpha zeigen
      </label>
    </div>

    <div v-if="loading" class="space-y-2">
      <div v-for="i in 4" :key="i" class="skeleton h-12" />
    </div>
    <p v-else-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    <p v-else-if="!visible.length" class="py-6 text-center text-sm text-base-400">Keine passende Version gefunden.</p>

    <ul v-else class="-mr-2 max-h-80 space-y-1.5 overflow-y-auto pr-2">
      <li v-for="(v, i) in visible" :key="v.id">
        <button class="flex w-full items-center gap-3 rounded-md border border-base-700 bg-base-900 px-3 py-2 text-left transition-colors hover:border-redstone-500" @click="emit('pick', v)">
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">
              {{ v.versionNumber }}
              <span v-if="i === 0" class="ml-1.5 rounded bg-redstone-900 px-1.5 py-0.5 text-[10px] text-redstone-300">Neueste</span>
            </p>
            <p class="truncate text-xs text-base-400">{{ formatDate(v.datePublished) }}</p>
          </div>
          <span class="shrink-0 text-xs" :class="v.versionType === 'release' ? 'text-ok' : 'text-lamp-400'">
            {{ typeLabels[v.versionType] ?? v.versionType }}
          </span>
          <span class="w-14 shrink-0 text-right font-mono text-xs text-base-600">{{ formatFileSize(v.size) }}</span>
        </button>
      </li>
    </ul>

    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">Abbrechen</button>
    </template>
  </BaseDialog>
</template>
