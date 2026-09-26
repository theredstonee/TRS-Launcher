<script setup lang="ts">
// „Beitreten“ bei mehreren Instanzen: mit welcher Instanz? Vorausgewählt ist
// die mit passender Version (sonst die zuletzt gespielte).
const join = useJoinStore()
const instances = useInstancesStore()
const games = useGamesStore()

const choice = ref('')
watch(
  () => join.pending,
  (p) => {
    if (!p) return
    const best = trsJoinInstance(instances.items, p.version ? { version: p.version, loader: 'vanilla' } : null)
    choice.value = best?.id ?? instances.items[0]?.id ?? ''
  },
  { immediate: true },
)
</script>

<template>
  <BaseDialog v-if="join.pending" :title="t('social.invite.chooseTitle')" @close="join.cancel()">
    <p class="mb-3 text-sm text-base-200">
      {{ t('social.invite.chooseText', { server: join.pending.address }) }}
    </p>
    <ul class="max-h-72 space-y-1 overflow-y-auto" role="radiogroup">
      <li v-for="i in instances.items" :key="i.id">
        <label class="flex cursor-pointer items-center gap-3 rounded-lg px-2.5 py-2 hover:bg-base-800" :class="{ 'bg-base-800': choice === i.id }">
          <input v-model="choice" type="radio" name="join-instance" :value="i.id" class="accent-redstone-500" />
          <InstanceIcon :instance="i" :size="32" />
          <span class="min-w-0 flex-1">
            <span class="block truncate text-sm font-semibold text-base-50">{{ i.name }}</span>
            <span class="block truncate text-xs text-base-400">{{ i.gameVersion }} · {{ loaderLabels[i.loader.kind] }}</span>
          </span>
          <span v-if="games.state(i.id).phase !== 'idle'" class="badge bg-lamp-900 text-lamp-300">{{ t('common.status.running') }}</span>
          <span v-else-if="join.pending.version && i.gameVersion === join.pending.version" class="badge bg-redstone-900/50 text-redstone-300">{{ t('social.invite.matches') }}</span>
        </label>
      </li>
    </ul>
    <template #actions>
      <button class="btn btn-ghost" @click="join.cancel()">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" :disabled="!choice" data-testid="join-confirm" @click="join.confirm(choice)">{{ t('social.invite.join') }}</button>
    </template>
  </BaseDialog>
</template>
