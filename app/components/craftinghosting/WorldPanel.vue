<script setup lang="ts">
import type { WorldInfo } from '~/types'

// Welt einer Instanz einspielen: ersetzt Welt, Nether und Ende des Servers (Server muss aus sein).
const store = useCraftingHostingStore()
const instances = useInstancesStore()

const worldFrom = ref('')
const worlds = ref<WorldInfo[]>([])
const worldFolder = ref('')
const loadingWorlds = ref(false)
const confirmWorld = ref(false)

const offline = computed(() => store.server?.state === 'offline' || store.server?.state === 'failed')

onMounted(async () => {
  if (!instances.items.length) await instances.load().catch(() => {})
  worldFrom.value = instances.items[0]?.id ?? ''
})

watch(worldFrom, async (id) => {
  worlds.value = []
  worldFolder.value = ''
  if (!id) return
  loadingWorlds.value = true
  try {
    worlds.value = await backend.instanceWorlds(id)
    worldFolder.value = worlds.value[0]?.folder ?? ''
  } catch (e) {
    useToasts().error(e)
  } finally {
    loadingWorlds.value = false
  }
})

async function uploadWorld() {
  confirmWorld.value = false
  if (worldFrom.value && worldFolder.value) await store.uploadWorld(worldFrom.value, worldFolder.value)
}

const progressText = computed(() => {
  const p = store.worldProgress
  return p ? t(`craftinghosting.world.phase.${p.phase}`, { percent: p.percent }) : ''
})
const worldName = computed(() => worlds.value.find((w) => w.folder === worldFolder.value)?.name ?? worldFolder.value)
</script>

<template>
  <div class="max-w-2xl">
    <div class="card p-4 text-sm" data-testid="hosting-world">
      <h3 class="mb-1 font-semibold text-base-50">{{ t('craftinghosting.world.title') }}</h3>
      <p class="mb-3 text-xs text-base-400">{{ t('craftinghosting.world.text') }}</p>
      <div class="space-y-2">
        <select v-model="worldFrom" class="field" :aria-label="t('craftinghosting.world.instance')">
          <option v-for="i in instances.items" :key="i.id" :value="i.id">{{ i.name }} · {{ i.gameVersion }}</option>
        </select>
        <select v-model="worldFolder" class="field" :disabled="loadingWorlds || !worlds.length" :aria-label="t('craftinghosting.world.world')">
          <option v-for="w in worlds" :key="w.folder" :value="w.folder">{{ w.name }}{{ w.version ? ` · ${w.version}` : '' }}</option>
        </select>
        <p v-if="!loadingWorlds && worldFrom && !worlds.length" class="text-xs text-base-500">{{ t('craftinghosting.world.none') }}</p>
      </div>
      <p v-if="!offline" class="mt-2 text-xs text-lamp-300">{{ t('craftinghosting.world.stopFirst') }}</p>
      <div v-if="store.worldProgress" class="mt-3">
        <div class="h-1.5 overflow-hidden rounded-full bg-base-800">
          <div class="h-full bg-redstone-500 transition-[width]" :style="{ width: `${store.worldProgress.percent}%` }" />
        </div>
        <p class="mt-1 text-xs text-base-400">{{ progressText }}</p>
      </div>
      <button v-else class="btn btn-ghost mt-3" :disabled="!worldFolder || !offline || !!store.busy" @click="confirmWorld = true">
        {{ t('craftinghosting.world.button') }}
      </button>
    </div>

    <BaseDialog v-if="confirmWorld" :title="t('craftinghosting.world.confirmTitle')" @close="confirmWorld = false">
      <p class="text-sm text-base-200">{{ t('craftinghosting.world.confirmText', { world: worldName }) }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="confirmWorld = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" data-testid="hosting-world-confirm" @click="uploadWorld">{{ t('craftinghosting.world.confirm') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
