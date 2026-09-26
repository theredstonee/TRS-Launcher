<script setup lang="ts">
import type { ClipUsage } from '~/types'

// Clips & Aufnahmen aller Instanzen: laufende Aufnahme steuern, Galerie mit
// Player und Zuschneiden, Speicherplatz im Blick. `?instance=…&file=…` öffnet
// direkt einen Clip (z. B. „Im Launcher öffnen“ aus dem Spiel).
const toasts = useToasts()
const route = useRoute()
const instances = useInstancesStore()
const settings = useSettingsStore()
const clipsStore = useClipsStore()

const usage = ref<ClipUsage | null>(null)
const count = ref(0)
const gallery = ref<{ reload: () => Promise<void> } | null>(null)

const enabled = computed(() => settings.current?.clips.enabled ?? false)
const share = computed(() => (usage.value ? usageShare(usage.value.usedBytes, usage.value.limitBytes) : 0))

function refreshUsage() {
  backend.clipUsage().then((u) => (usage.value = u)).catch(() => {})
}

onMounted(() => {
  if (!instances.items.length) instances.load().catch(() => {})
  if (!settings.current) settings.load().catch(() => {})
  refreshUsage()
})
watch(() => clipsStore.version, refreshUsage)

function reload() {
  void gallery.value?.reload()
  refreshUsage()
}

// Deep-Link: /clips?instance=<id>&file=<name>[&n=<zähler>]
const openRequest = computed(() => {
  const instanceId = route.query.instance
  const fileName = route.query.file
  if (typeof instanceId !== 'string' || typeof fileName !== 'string') return null
  return { instanceId, fileName, nonce: Number(route.query.n ?? 0) || 0 }
})

// --- Laufende Aufnahmen ------------------------------------------------------------

const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null
onMounted(() => (ticker = setInterval(() => (now.value = Date.now()), 500)))
onBeforeUnmount(() => ticker && clearInterval(ticker))

const live = computed(() =>
  clipsStore.active.map((s) => ({
    ...s,
    name: instances.items.find((i) => i.id === s.instanceId)?.name ?? s.instanceId,
    elapsed: s.recording ? formatClipDuration(recordingElapsed(s.recordingMs, s.receivedAt, now.value)) : null,
  })),
)

const bufferSeconds = computed(() => settings.current?.clips.bufferSeconds ?? 30)

function openFolder() {
  backend.openClipsFolder().catch((e) => toasts.error(e))
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col p-6">
    <PageHeader :title="t('clips.title')" :subtitle="t('clips.subtitle', count)">
      <button class="btn btn-ghost h-9 py-1 text-xs" @click="openFolder">{{ t('common.actions.openFolder') }}</button>
      <button class="btn-icon" :title="t('clips.settings')" :aria-label="t('clips.settings')" @click="settings.open('clips')">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path :d="icons.gear" /></svg>
      </button>
      <button class="btn-icon" :title="t('clips.reload')" :aria-label="t('clips.reload')" @click="reload">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5" /></svg>
      </button>
    </PageHeader>

    <!-- Aus: kurz erklären, wo es angeht -->
    <section v-if="settings.current && !enabled" class="card mb-4 flex items-center gap-4 px-4 py-3">
      <svg viewBox="0 0 24 24" class="size-6 shrink-0 text-base-400" fill="none" stroke="currentColor" stroke-width="1.8"><path :d="icons.clips" /></svg>
      <div class="min-w-0 flex-1">
        <p class="text-sm font-medium text-base-50">{{ t('clips.off.title') }}</p>
        <p class="text-xs text-base-400">{{ t('clips.off.text') }}</p>
      </div>
      <button class="btn btn-primary shrink-0" @click="settings.open('clips')">{{ t('clips.off.action') }}</button>
    </section>

    <!-- Laufende Spiele -->
    <section v-if="live.length" class="mb-4 space-y-2" :aria-label="t('clips.live.label')">
      <div v-for="s in live" :key="s.instanceId" class="card flex flex-wrap items-center gap-3 px-4 py-3">
        <span
          class="size-3 shrink-0 rounded-full"
          :class="s.recording ? 'animate-pulse bg-redstone-500' : s.buffer ? 'border-2 border-base-400' : 'bg-base-700'"
          aria-hidden="true"
        />
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-medium text-base-50">{{ s.name }}</p>
          <p class="text-xs text-base-400">
            <template v-if="s.recording">{{ t('clips.live.recording', { time: s.elapsed ?? '0:00' }) }}</template>
            <template v-else-if="s.buffer">{{ t('clips.live.buffer', { seconds: bufferSeconds }) }}</template>
            <template v-else>{{ reasonText(s.reason) }}</template>
            <span v-if="s.encoder" class="ml-1 text-base-600">· {{ tKey(`clips.encoders.${s.encoder}`) }}</span>
          </p>
        </div>
        <button class="btn btn-ghost py-1.5 text-xs" :disabled="!s.buffer" @click="clipsStore.action(s.instanceId, false)">
          {{ t('clips.live.saveClip') }}
        </button>
        <button
          class="btn py-1.5 text-xs"
          :class="s.recording ? 'btn-danger' : 'btn-ghost'"
          :disabled="!s.buffer"
          @click="clipsStore.action(s.instanceId, true)"
        >
          {{ s.recording ? t('clips.live.stop') : t('clips.live.record') }}
        </button>
      </div>
    </section>

    <!-- Speicherplatz -->
    <div v-if="usage && usage.count" class="mb-4">
      <div class="flex items-center justify-between text-xs text-base-400">
        <span>{{ t('clips.usage', { used: formatBytes(usage.usedBytes), limit: formatBytes(usage.limitBytes) }) }}</span>
        <span v-if="usage.freeBytes !== null">{{ t('clips.free', { free: formatBytes(usage.freeBytes) }) }}</span>
      </div>
      <div class="mt-1 h-1.5 overflow-hidden rounded-full bg-base-800" role="progressbar" :aria-valuenow="Math.round(share)" aria-valuemin="0" aria-valuemax="100">
        <div class="h-full rounded-full" :class="share > 90 ? 'bg-warn' : 'bg-redstone-500'" :style="{ width: `${share}%` }" />
      </div>
    </div>

    <ClipGallery ref="gallery" :open="openRequest" @changed="refreshUsage" @loaded="(n) => (count = n)" />
  </div>
</template>
