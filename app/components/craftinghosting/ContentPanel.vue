<script setup lang="ts">
import type { WorldInfo } from '~/types'

// Inhalte aus einer Instanz auf den Partner-Server: Mods (nur mit Modrinth-Quelle) und eine Welt.
// Beides gilt beim nächsten Start; die Welt ersetzt die des Servers (Server muss aus sein).
const store = useCraftingHostingStore()
const instances = useInstancesStore()

const modsFrom = ref('')
const worldFrom = ref('')
const worlds = ref<WorldInfo[]>([])
const worldFolder = ref('')
const loadingWorlds = ref(false)
const confirmWorld = ref(false)

const server = computed(() => store.server)
const modsSupported = computed(() => server.value?.serverType !== 'vanilla')
const offline = computed(() => server.value?.state === 'offline' || server.value?.state === 'failed')
const skipped = computed(() => store.modsTransfer?.result?.skipped ?? [])

onMounted(async () => {
  if (!instances.items.length) await instances.load().catch(() => {})
  modsFrom.value = instances.items[0]?.id ?? ''
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

async function transferMods() {
  if (modsFrom.value) await store.transferMods(modsFrom.value)
}

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
  <div class="grid gap-4 md:grid-cols-2">
    <!-- Mods -->
    <div class="card p-4 text-sm" data-testid="hosting-mods">
      <h3 class="mb-1 font-semibold text-base-50">{{ t('craftinghosting.mods.title') }}</h3>
      <p class="mb-3 text-xs text-base-400">{{ t('craftinghosting.mods.text') }}</p>
      <p v-if="!modsSupported" class="text-xs text-lamp-300">{{ t('craftinghosting.mods.vanilla') }}</p>
      <template v-else>
        <div class="flex gap-2">
          <select v-model="modsFrom" class="field flex-1" :aria-label="t('craftinghosting.mods.instance')">
            <option v-for="i in instances.items" :key="i.id" :value="i.id">{{ i.name }} · {{ i.gameVersion }}</option>
          </select>
          <button class="btn btn-ghost" :disabled="!modsFrom || !!store.busy" @click="transferMods">
            {{ store.busy === 'mods' ? t('craftinghosting.mods.working') : t('craftinghosting.mods.button') }}
          </button>
        </div>
        <div v-if="store.modsTransfer" class="mt-3 space-y-2 text-xs">
          <p v-if="store.modsTransfer.result" class="text-ok">
            {{ t('craftinghosting.mods.installed', { n: store.modsTransfer.result.installed.length }) }}
          </p>
          <p v-else class="text-base-400">{{ t('craftinghosting.mods.noneTransferable') }}</p>
          <div v-if="skipped.length">
            <p class="text-base-300">{{ t('craftinghosting.mods.skipped') }}</p>
            <ul class="mt-1 space-y-0.5 text-base-400">
              <li v-for="s in skipped" :key="s.project">
                <span class="font-mono">{{ s.project }}</span> – {{ t(`craftinghosting.mods.reason.${s.reason}`) }}
              </li>
            </ul>
          </div>
          <div v-if="store.modsTransfer.other.length">
            <p class="text-base-300">{{ t('craftinghosting.mods.notYet') }}</p>
            <ul class="mt-1 max-h-32 space-y-0.5 overflow-y-auto text-base-400">
              <li v-for="name in store.modsTransfer.other" :key="name" class="truncate">{{ name }}</li>
            </ul>
          </div>
          <p class="text-base-500">{{ t('craftinghosting.settings.nextStart') }}</p>
        </div>
      </template>
    </div>

    <!-- Welt -->
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
