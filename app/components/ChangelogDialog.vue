<script setup lang="ts">
import type { ContentItem, Instance, ModrinthVersion } from '~/types'

// Was hat sich seit der installierten Version geändert? Zeigt die Changelogs
// aller neueren passenden Versionen (neueste zuerst).
const props = defineProps<{ instance: Instance; item: ContentItem }>()
const emit = defineEmits<{ close: []; install: [version: ModrinthVersion] }>()

const versions = ref<ModrinthVersion[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const latest = computed(() => versions.value[0] ?? null)
const installedLabel = computed(() => props.item.version ?? props.item.source?.versionNumber ?? t('changelog.installedVersion'))

onMounted(async () => {
  const source = props.item.source
  if (!source) return
  try {
    versions.value = await backend.contentChangelog(props.instance.id, source.projectId, props.item.kind, source.versionId)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <BaseDialog :title="t('changelog.title', { name: item.title ?? item.fileName })" wide @close="emit('close')">
    <div class="mb-4 flex items-center gap-3">
      <ModIcon :src="item.iconUrl" :name="item.title ?? item.fileName" :size="44" />
      <p class="min-w-0 flex-1 text-sm text-base-200">
        <template v-if="loading">{{ t('changelog.searching') }}</template>
        <template v-else-if="latest">
          <span class="font-mono text-base-400">{{ installedLabel }}</span>
          <svg viewBox="0 0 24 24" class="mx-1.5 inline size-3.5 text-base-600" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M5 12h14m0 0-5-5m5 5-5 5" /></svg>
          <span class="font-mono text-lamp-300">{{ latest.versionNumber }}</span>
          <span class="ml-2 text-xs text-base-400">{{ t('changelog.newVersions', versions.length) }}</span>
        </template>
        <template v-else-if="!error">{{ t('changelog.upToDate', { version: installedLabel }) }}</template>
      </p>
    </div>

    <div v-if="loading" class="space-y-3">
      <div v-for="i in 3" :key="i" class="skeleton h-20" />
    </div>
    <p v-else-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>

    <ol v-else-if="versions.length" class="-mr-2 max-h-[26rem] space-y-5 overflow-y-auto pr-2">
      <li v-for="v in versions" :key="v.id" class="relative border-l-2 border-base-700 pl-4">
        <span class="absolute top-1 -left-[5px] size-2 rounded-full bg-redstone-500" />
        <p class="flex items-center gap-2 text-sm font-medium">
          {{ v.versionNumber }}
          <span class="badge" :class="v.versionType === 'release' ? 'bg-ok/10 text-ok' : 'bg-lamp-900 text-lamp-300'">{{ versionTypeLabel(v.versionType) }}</span>
          <span class="text-xs font-normal text-base-400">{{ formatDate(v.datePublished) }}</span>
        </p>
        <MarkdownView v-if="v.changelog" :source="v.changelog" class="mt-1.5" />
        <p v-else class="mt-1 text-xs text-base-400">{{ t('changelog.noChangelog') }}</p>
      </li>
    </ol>

    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
      <button v-if="latest" class="btn btn-primary" @click="emit('install', latest)">{{ t('changelog.updateTo', { version: latest.versionNumber }) }}</button>
    </template>
  </BaseDialog>
</template>
