<script setup lang="ts">
// Fortschritt einer pausierbaren Aufgabe. Die Leitung wird bei Halloween von selbst zum Hexen-Balken.
const props = defineProps<{ taskKey: string }>()

const tasks = useTasksStore()
const task = computed(() => tasks.get(props.taskKey))

const percent = computed(() => {
  const current = task.value
  if (!current) return 0
  const value = current.totalBytes > 0 ? (current.doneBytes / current.totalBytes) * 100 : (current.percent ?? 0)
  return Math.max(0, Math.min(100, value))
})

const eta = computed(() => {
  const current = task.value
  if (!current || current.totalBytes <= 0) return null
  return formatEta(Math.max(0, current.totalBytes - current.doneBytes), current.speed)
})

const detail = computed(() => {
  const current = task.value
  if (!current) return ''
  const parts: string[] = []
  if (current.paused) parts.push(t('tasks.paused'))
  else if (current.speed > 0) parts.push(formatSpeed(current.speed))
  if (eta.value) parts.push(t('tasks.remaining', { time: eta.value }))
  if (current.totalBytes > 0) parts.push(formatProgressBytes(current.doneBytes, current.totalBytes))
  return parts.join(' · ')
})

function togglePause() {
  const current = task.value
  if (!current) return
  void tasks.pause(props.taskKey, !current.paused)
}
</script>

<template>
  <div v-if="task">
    <div class="flex items-center gap-3">
      <RedstoneWire class="min-w-0 flex-1" :class="{ 'opacity-50 grayscale': task.paused }" :percent="percent" :segments="40" />
      <span class="display shrink-0 text-sm text-redstone-300 tabular-nums">{{ Math.floor(percent) }} %</span>
      <button
        v-if="task.pausable"
        class="grid size-6 shrink-0 place-items-center rounded-md text-base-400 transition-colors hover:bg-base-700 hover:text-base-50 disabled:opacity-40"
        :aria-label="task.paused ? t('tasks.resumeNamed', { title: task.title }) : t('tasks.pauseNamed', { title: task.title })"
        :title="task.paused ? t('tasks.resume') : t('tasks.pause')"
        :disabled="task.cancelling"
        @click="togglePause"
      >
        <svg v-if="task.paused" viewBox="0 0 24 24" class="size-3.5" fill="currentColor"><path :d="icons.play" /></svg>
        <svg v-else viewBox="0 0 24 24" class="size-3.5" fill="currentColor"><path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" /></svg>
      </button>
    </div>
    <p v-if="detail" class="mt-1 text-xs text-base-400">{{ detail }}</p>
  </div>
</template>
