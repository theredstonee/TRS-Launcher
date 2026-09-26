<script setup lang="ts">
import type { Instance } from '~/types'

// Tab „Teilen“: alles, womit sich eine Instanz weitergeben lässt – als
// Modpack-Datei, als Log-Link (Hilfe bei Problemen), Welten und Screenshots.
const props = defineProps<{ instance: Instance }>()
const emit = defineEmits<{ navigate: [tab: 'worlds' | 'screenshots' | 'logs' | 'files'] }>()

const tasks = useTasksStore()
const exporting = ref(false)
const sharingLog = ref(false)
const exportTask = computed(() => tasks.get(taskKey('export', props.instance.id)))

interface Card {
  key: string
  title: string
  text: string
  icon: string
  tone: string
  action: string
  run: () => void
  busy?: boolean
}
const cards = computed<Card[]>(() => [
  {
    key: 'modpack',
    title: t('share.modpack.title'),
    text: t('share.modpack.text'),
    icon: 'M4 8l8-4 8 4v8l-8 4-8-4zM4 8l8 4 8-4M12 12v8',
    tone: 'text-redstone-400',
    action: exportTask.value?.status === 'running' ? `${exportTask.value.percent ?? 0} %` : t('share.modpack.action'),
    busy: exportTask.value?.status === 'running',
    run: () => (exporting.value = true),
  },
  {
    key: 'log',
    title: t('share.log.title'),
    text: t('share.log.text'),
    icon: 'M6 3h9l4 4v14H6zM14 3v5h5M9 12h7M9 16h4',
    tone: 'text-lamp-400',
    action: t('share.log.action'),
    run: () => (sharingLog.value = true),
  },
  {
    key: 'world',
    title: t('share.world.title'),
    text: t('share.world.text'),
    icon: 'M3 17l5-6 4 4 3-3 6 5M3 5h18v14H3z',
    tone: 'text-ok',
    action: t('share.world.action'),
    run: () => emit('navigate', 'worlds'),
  },
  {
    key: 'files',
    title: t('share.files.title'),
    text: t('share.files.text'),
    icon: 'M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z',
    tone: 'text-sky-400',
    action: t('share.files.action'),
    run: () => emit('navigate', 'files'),
  },
])
</script>

<template>
  <div class="min-h-0 flex-1 overflow-y-auto pr-1 pb-4">
    <p class="mb-4 max-w-2xl text-sm text-base-400">{{ t('share.intro') }}</p>
    <ul class="grid grid-cols-[repeat(auto-fill,minmax(17rem,1fr))] gap-3">
      <li v-for="c in cards" :key="c.key" class="card card-hover flex flex-col p-4">
        <div class="mb-3 flex items-center gap-3">
          <span class="flex size-10 items-center justify-center rounded-lg bg-base-850 ring-1 ring-base-700" :class="c.tone">
            <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="c.icon" /></svg>
          </span>
          <h3 class="font-medium text-base-50">{{ c.title }}</h3>
        </div>
        <p class="flex-1 text-sm text-base-400">{{ c.text }}</p>
        <button class="btn mt-4 self-start px-3 py-1.5 text-xs" :class="c.key === 'modpack' ? 'btn-primary' : 'btn-ghost'" :disabled="c.busy" @click="c.run">{{ c.action }}</button>
      </li>
    </ul>

    <ExportPackDialog v-if="exporting" :instance="instance" @close="exporting = false" />
    <LogShareDialog v-if="sharingLog" :instance-id="instance.id" source="live" :label="t('logViewer.latest')" @close="sharingLog = false" />
  </div>
</template>
