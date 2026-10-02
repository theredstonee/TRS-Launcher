<script setup lang="ts">
// Hinweis in der Instanz: gehört zu einem geteilten Modpack, und es gibt eine
// neue Version → „Aktualisieren“ (eigene geänderte Dateien bleiben). Für eigene
// geteilte Packs: Code + „Neue Version teilen“.
const props = defineProps<{ instanceId: string }>()
const emit = defineEmits<{ share: [] }>()
const packs = usePacksStore()
const tasks = useTasksStore()

const link = computed(() => packs.linkOf(props.instanceId))
const update = computed(() => packs.updateOf(props.instanceId))
const updateKey = computed(() => taskKey('packupdate', props.instanceId))
const running = computed(() => tasks.get(updateKey.value)?.status === 'running')

onMounted(() => {
  void packs.checkUpdates()
})
</script>

<template>
  <div v-if="update" class="card mb-4 border-lamp-400/40 px-4 py-3" role="status" data-testid="pack-update">
    <div class="flex flex-wrap items-center gap-3">
      <svg viewBox="0 0 24 24" class="size-5 shrink-0 text-lamp-400" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 4v11M7 10l5 5 5-5M5 20h14" /></svg>
      <p class="min-w-0 flex-1 text-sm text-base-200">
        {{ t('packs.update.available', { name: update.latest.name, version: update.latest.packVersion, owner: update.latest.owner.name }) }}
        <span class="block text-xs text-base-400">{{ t('packs.update.keepHint') }}</span>
      </p>
      <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="running" @click="packs.update(instanceId)">{{ running ? t('packs.update.running') : t('packs.update.action') }}</button>
    </div>
    <TaskTransfer v-if="running" class="mt-3 w-full" :task-key="updateKey" />
  </div>
  <div v-else-if="link?.role === 'shared'" class="mb-4 flex flex-wrap items-center gap-2 text-xs text-base-400">
    <span>{{ t('packs.linked.shared', { code: link.code }) }}</span>
    <button class="text-redstone-300 underline-offset-2 hover:underline" @click="emit('share')">{{ t('packs.linked.newVersion') }}</button>
  </div>
</template>
