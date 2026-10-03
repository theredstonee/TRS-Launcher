<script setup lang="ts">
import type { Instance } from '~/types'

// Arbeitsspeicher einer Instanz im Big-Picture-Modus: großer Schieberegler
// (Controller: links/rechts, wenn er den Fokus hat) plus −/+ für Maus und Touch.
// Speichert kurz nach der letzten Änderung als eigene Einstellung der Instanz.
const props = defineProps<{ instance: Instance }>()

const settings = useSettingsStore()
const instances = useInstancesStore()
const toasts = useToasts()

const effective = computed(() => props.instance.overrides.maxMemoryMb ?? settings.current?.maxMemoryMb ?? 4096)
const value = ref(clampMemory(effective.value))
const saving = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

watch(
  () => [props.instance.id, effective.value] as const,
  () => {
    if (!timer) value.value = clampMemory(effective.value)
  },
)

function set(mb: number) {
  value.value = clampMemory(mb)
  clearTimeout(timer)
  timer = setTimeout(save, 500)
}

async function save() {
  timer = undefined
  const mb = value.value
  if (mb === effective.value) return
  saving.value = true
  try {
    await backend.setInstanceMemory(props.instance.id, mb)
    await instances.load()
  } catch (e) {
    toasts.error(e)
    value.value = clampMemory(effective.value)
  } finally {
    saving.value = false
  }
}

onBeforeUnmount(() => {
  if (timer) {
    clearTimeout(timer)
    void save()
  }
})

const percent = computed(() => ((value.value - MEMORY_MIN_MB) / (MEMORY_MAX_MB - MEMORY_MIN_MB)) * 100)
</script>

<template>
  <div class="memory">
    <div class="flex items-baseline justify-between gap-4">
      <span class="text-lg text-base-200">{{ t('bigPicture.memory.title') }}</span>
      <span class="display text-3xl tabular-nums text-base-50">{{ formatMemory(value) }}</span>
    </div>
    <div class="mt-3 flex items-center gap-3">
      <button type="button" class="step" :aria-label="t('bigPicture.memory.less')" :disabled="value <= MEMORY_MIN_MB" @click="set(value - MEMORY_STEP_MB)">−</button>
      <input
        type="range"
        class="slider flex-1"
        :min="MEMORY_MIN_MB"
        :max="MEMORY_MAX_MB"
        :step="MEMORY_STEP_MB"
        :value="value"
        :style="{ '--fill': `${percent}%` }"
        :aria-label="t('bigPicture.memory.title')"
        :aria-valuetext="formatMemory(value)"
        @input="set(Number(($event.target as HTMLInputElement).value))"
      />
      <button type="button" class="step" :aria-label="t('bigPicture.memory.more')" :disabled="value >= MEMORY_MAX_MB" @click="set(value + MEMORY_STEP_MB)">+</button>
    </div>
    <p class="mt-2 text-sm text-base-400">{{ saving ? t('common.status.saving') : t('bigPicture.memory.hint') }}</p>
  </div>
</template>

<style scoped>
.step {
  display: grid;
  place-items: center;
  width: 3.25rem;
  height: 3.25rem;
  flex-shrink: 0;
  border-radius: 0.75rem;
  background: var(--color-base-800);
  color: var(--color-base-50);
  font-size: 1.75rem;
  line-height: 1;
  transition: background-color 0.15s ease;
}
.step:hover:not(:disabled) {
  background: var(--color-base-700);
}
.step:disabled {
  opacity: 0.35;
}
.slider {
  appearance: none;
  height: 3.25rem;
  background: transparent;
  border-radius: 0.75rem;
  cursor: pointer;
}
.slider::-webkit-slider-runnable-track {
  height: 0.9rem;
  border-radius: 9999px;
  background: linear-gradient(90deg, var(--color-redstone-500) var(--fill), var(--color-base-700) var(--fill));
}
.slider::-webkit-slider-thumb {
  appearance: none;
  width: 2rem;
  height: 2rem;
  margin-top: -0.55rem;
  border-radius: 0.4rem;
  background: var(--color-base-50);
  box-shadow: 0 0 0 4px var(--color-redstone-500), 0 0 18px color-mix(in srgb, var(--color-redstone-500) 60%, transparent);
}
</style>
