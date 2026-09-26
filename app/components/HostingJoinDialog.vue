<script setup lang="ts">
import { worldVersionLabel } from '~/utils/hosting'

// Gehostete Welt beitreten: Mit welcher Instanz? Angeboten werden nur Instanzen
// mit gleicher Minecraft-Version, passendem Loader und TRS Client (die beste ist
// vorausgewählt). Passt keine, legt „Neue Instanz“ eine mit Version und Loader
// der Welt an – das ist dann auch vorausgewählt.
const hosting = useHostingStore()
const instances = useInstancesStore()
const games = useGamesStore()

/** Instanz-ID oder `''` = neue Instanz anlegen. */
const selected = ref('')
const candidates = computed(() => {
  const ids = hosting.choice?.instanceIds ?? []
  return ids.map((id) => instances.items.find((i) => i.id === id)).filter((i) => !!i)
})

watch(
  () => hosting.choice,
  (c) => {
    if (c) selected.value = c.instanceIds[0] ?? ''
  },
  { immediate: true },
)

function confirm() {
  void hosting.choose(selected.value || null)
}
</script>

<template>
  <BaseDialog v-if="hosting.choice" :title="t('social.hosting.chooseTitle')" @close="hosting.cancelChoice()">
    <p class="mb-3 text-sm text-base-200">
      {{ t('social.hosting.chooseText', { world: hosting.choice.world.name, version: worldVersionLabel(hosting.choice.world) }) }}
    </p>
    <p v-if="!candidates.length" class="mb-3 rounded-lg bg-base-800 px-3 py-2 text-xs text-base-300" data-testid="hosting-no-match">
      {{ t('social.hosting.noMatch', { version: worldVersionLabel(hosting.choice.world) }) }}
    </p>
    <ul class="max-h-72 space-y-1 overflow-y-auto" role="radiogroup" data-testid="hosting-choices">
      <li v-for="i in candidates" :key="i.id">
        <label class="flex cursor-pointer items-center gap-3 rounded-lg px-2.5 py-2 hover:bg-base-800" :class="{ 'bg-base-800': selected === i.id }">
          <input v-model="selected" type="radio" name="hosting-instance" :value="i.id" class="accent-redstone-500" />
          <InstanceIcon :instance="i" :size="32" />
          <span class="min-w-0 flex-1">
            <span class="block truncate text-sm font-semibold text-base-50">{{ i.name }}</span>
            <span class="block truncate text-xs text-base-400">{{ i.gameVersion }} · {{ loaderLabels[i.loader.kind] }}</span>
          </span>
          <span v-if="games.state(i.id).phase !== 'idle'" class="badge bg-lamp-900 text-lamp-300">{{ t('common.status.running') }}</span>
        </label>
      </li>
      <li>
        <label class="flex cursor-pointer items-center gap-3 rounded-lg px-2.5 py-2 hover:bg-base-800" :class="{ 'bg-base-800': selected === '' }">
          <input v-model="selected" type="radio" name="hosting-instance" value="" class="accent-redstone-500" data-testid="hosting-create" />
          <span class="grid size-8 place-items-center rounded-md bg-base-800 text-base-300 ring-1 ring-base-700">
            <SocialIcon name="plus" class="size-4" />
          </span>
          <span class="min-w-0 flex-1">
            <span class="block truncate text-sm font-semibold text-base-50">{{ t('social.hosting.createInstance') }}</span>
            <span class="block truncate text-xs text-base-400">{{ worldVersionLabel(hosting.choice.world) }} · {{ t('social.hosting.withClient') }}</span>
          </span>
        </label>
      </li>
    </ul>
    <template #actions>
      <button class="btn btn-ghost" @click="hosting.cancelChoice()">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" data-testid="hosting-confirm" @click="confirm">
        {{ selected ? t('social.hosting.join') : t('social.hosting.createAndJoin') }}
      </button>
    </template>
  </BaseDialog>
</template>
