<script setup lang="ts">
import type { PreviewBuild } from '~/utils/creator'

// Vorschau-Builds (ab Partner): Liste vom Server, „Herunterladen“ öffnet den
// Link im Browser. Die neueste Version je Teil ist hervorgehoben.
const props = defineProps<{ partner: boolean }>()
const toasts = useToasts()

const list = ref<PreviewBuild[] | null>(null)
const error = ref<string | null>(null)

async function load() {
  if (!props.partner) return
  error.value = null
  try {
    list.value = await backend.creator.previews()
  } catch (e) {
    error.value = errorMessage(e)
  }
}
onMounted(load)

/** Neueste Version je Teil (Launcher/TRS Client) – ältere gelten als veraltet. */
const latest = computed(() => {
  const ids = new Set<string>()
  for (const product of ['launcher', 'client'] as const) {
    const first = list.value?.find((p) => p.product === product)
    if (first) ids.add(first.id)
  }
  return ids
})

function download(p: PreviewBuild) {
  void backend.openExternalUrl(p.url).catch((e) => toasts.error(e))
}
</script>

<template>
  <section v-if="!partner" class="card p-4" data-testid="creator-previews-locked">
    <h3 class="section-title">{{ t('creator.previews.title') }}</h3>
    <p class="mt-2 text-sm text-base-400">{{ t('creator.previews.locked') }}</p>
  </section>
  <div v-else class="space-y-3" data-testid="creator-previews">
    <p class="rounded-lg border-l-2 border-redstone-500 bg-base-900 px-3 py-2 text-xs text-base-400">{{ t('creator.previews.note') }}</p>
    <div v-if="!list && !error" class="skeleton h-28" />
    <div v-else-if="error" class="card px-4 py-4 text-sm" role="alert">
      <p class="text-base-400">{{ error }}</p>
      <button class="btn btn-ghost mt-2 px-3 py-1.5 text-xs" @click="load">{{ t('common.actions.retry') }}</button>
    </div>
    <section v-else class="card p-4">
      <p v-if="!list?.length" class="text-sm text-base-400">{{ t('creator.previews.empty') }}</p>
      <ul v-else class="divide-y divide-base-800">
        <li v-for="p in list" :key="p.id" class="flex flex-wrap items-center gap-3 py-2.5">
          <span class="badge bg-base-800 text-base-200">{{ p.product === 'launcher' ? t('creator.previews.launcher') : t('creator.previews.client') }}</span>
          <span class="font-mono text-xs font-semibold text-base-50">{{ p.version }}</span>
          <span v-if="p.notes" class="min-w-0 flex-1 truncate text-xs text-base-400" :title="p.notes">{{ p.notes }}</span>
          <span v-else class="flex-1" />
          <span v-if="p.publishedAt" class="text-[11px] text-base-600">{{ formatRelative(p.publishedAt, true) }}</span>
          <button v-if="latest.has(p.id)" class="btn btn-primary px-3 py-1.5 text-xs" :data-testid="`creator-preview-${p.id}`" @click="download(p)">
            {{ t('creator.previews.download') }} <SocialIcon name="external" class="size-3.5" />
          </button>
          <span v-else class="text-[11px] text-base-600">{{ t('creator.previews.outdated') }}</span>
        </li>
      </ul>
      <p class="mt-3 text-[11px] text-base-400">{{ t('creator.previews.bugs') }}</p>
    </section>
  </div>
</template>
