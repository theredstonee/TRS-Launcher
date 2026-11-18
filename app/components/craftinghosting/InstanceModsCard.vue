<script setup lang="ts">
// Mods einer Instanz auf den Server (nur mit Modrinth-Quelle). Gilt beim nächsten Start.
const store = useCraftingHostingStore()
const instances = useInstancesStore()

const modsFrom = ref('')
const modsSupported = computed(() => store.server?.serverType !== 'vanilla')
const skipped = computed(() => store.modsTransfer?.result?.skipped ?? [])

onMounted(async () => {
  if (!instances.items.length) await instances.load().catch(() => {})
  modsFrom.value = instances.items[0]?.id ?? ''
})

async function transferMods() {
  if (modsFrom.value) await store.transferMods(modsFrom.value)
}
</script>

<template>
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
</template>
