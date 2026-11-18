<script setup lang="ts">
import { DIFFICULTIES, GAMEMODES, LEVEL_TYPES, type CraftingSettings, settingsPatch } from '~/utils/craftinghosting'

// Einstellungen des Partner-Servers (server.properties). Gelten beim nächsten Start;
// Seed und Welttyp nur für eine neu erzeugte Welt. Die Whitelist folgt dem Zugang.
const store = useCraftingHostingStore()
const form = ref<CraftingSettings | null>(null)

onMounted(() => {
  if (store.server?.lastStartAt) void store.loadSettings()
})
watch(
  () => store.settings,
  (s) => (form.value = s ? { ...s } : null),
  { immediate: true },
)

const dirty = computed(() => !!form.value && !!store.settings && Object.keys(settingsPatch(store.settings, form.value)).length > 0)

async function save() {
  if (!form.value || !store.settings) return
  await store.saveSettings(settingsPatch(store.settings, form.value))
}

function reset() {
  form.value = store.settings ? { ...store.settings } : null
}
</script>

<template>
  <div class="card p-4" data-testid="hosting-settings">
    <p v-if="!form && (store.settingsPending || !store.server?.lastStartAt)" class="text-sm text-base-400" data-testid="hosting-settings-pending">
      {{ t('craftinghosting.settings.afterFirstStart') }}
    </p>
    <div v-else-if="!form" class="skeleton h-40" />
    <form v-else class="space-y-4" @submit.prevent="save">
      <p class="rounded-lg bg-base-900 px-3 py-2 text-xs text-base-400 ring-1 ring-base-800">{{ t('craftinghosting.settings.nextStart') }}</p>
      <div>
        <label for="chs-motd" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.settings.motd') }}</label>
        <input id="chs-motd" v-model="form.motd" class="field" maxlength="120" autocomplete="off" />
      </div>
      <div class="grid gap-3 sm:grid-cols-2">
        <div>
          <label for="chs-mode" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.settings.gamemode') }}</label>
          <select id="chs-mode" v-model="form.gamemode" class="field">
            <option v-for="m in GAMEMODES" :key="m" :value="m">{{ t(`craftinghosting.settings.gamemodes.${m}`) }}</option>
          </select>
        </div>
        <div>
          <label for="chs-diff" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.settings.difficulty') }}</label>
          <select id="chs-diff" v-model="form.difficulty" class="field">
            <option v-for="d in DIFFICULTIES" :key="d" :value="d">{{ t(`craftinghosting.settings.difficulties.${d}`) }}</option>
          </select>
        </div>
        <div>
          <label for="chs-max" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.settings.maxPlayers') }}</label>
          <input id="chs-max" v-model.number="form.maxPlayers" type="number" min="1" max="100" class="field" />
        </div>
        <div>
          <label for="chs-spawn" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.settings.spawnProtection') }}</label>
          <input id="chs-spawn" v-model.number="form.spawnProtection" type="number" min="0" max="64" class="field" />
        </div>
        <div>
          <label for="chs-view" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.settings.viewDistance', { n: form.viewDistance }) }}</label>
          <input id="chs-view" v-model.number="form.viewDistance" type="range" min="3" max="16" class="w-full accent-redstone-500" />
        </div>
        <div>
          <label for="chs-sim" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.settings.simulationDistance', { n: form.simulationDistance }) }}</label>
          <input id="chs-sim" v-model.number="form.simulationDistance" type="range" min="3" max="16" class="w-full accent-redstone-500" />
        </div>
      </div>
      <div class="grid gap-2 sm:grid-cols-3">
        <label class="flex items-center justify-between gap-2 rounded-lg bg-base-900 px-3 py-2 text-sm ring-1 ring-base-800">
          {{ t('craftinghosting.settings.pvp') }}<ToggleSwitch v-model="form.pvp" :label="t('craftinghosting.settings.pvp')" />
        </label>
        <label class="flex items-center justify-between gap-2 rounded-lg bg-base-900 px-3 py-2 text-sm ring-1 ring-base-800">
          {{ t('craftinghosting.settings.hardcore') }}<ToggleSwitch v-model="form.hardcore" :label="t('craftinghosting.settings.hardcore')" />
        </label>
        <label class="flex items-center justify-between gap-2 rounded-lg bg-base-900 px-3 py-2 text-sm ring-1 ring-base-800">
          {{ t('craftinghosting.settings.allowFlight') }}<ToggleSwitch v-model="form.allowFlight" :label="t('craftinghosting.settings.allowFlight')" />
        </label>
      </div>
      <fieldset class="rounded-lg border border-base-800 p-3">
        <legend class="px-1 text-xs text-base-400">{{ t('craftinghosting.settings.newWorld') }}</legend>
        <div class="grid gap-3 sm:grid-cols-2">
          <div>
            <label for="chs-seed" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.settings.seed') }}</label>
            <input id="chs-seed" v-model="form.seed" class="field font-mono" maxlength="64" :placeholder="t('craftinghosting.settings.seedRandom')" autocomplete="off" />
          </div>
          <div>
            <label for="chs-level" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.settings.levelType') }}</label>
            <select id="chs-level" v-model="form.levelType" class="field">
              <option v-for="l in LEVEL_TYPES" :key="l" :value="l">{{ t(`craftinghosting.settings.levelTypes.${l}`) }}</option>
            </select>
          </div>
        </div>
        <p class="mt-2 text-xs text-base-500">{{ t('craftinghosting.settings.newWorldHint') }}</p>
      </fieldset>
      <p class="text-xs text-base-500">
        {{ form.whitelist ? t('craftinghosting.settings.whitelistOn') : t('craftinghosting.settings.whitelistOff') }}
      </p>
      <div class="flex gap-2">
        <button type="submit" class="btn btn-primary" :disabled="!dirty || !!store.busy">{{ t('common.actions.save') }}</button>
        <button type="button" class="btn btn-ghost" :disabled="!dirty" @click="reset">{{ t('craftinghosting.settings.discard') }}</button>
      </div>
    </form>
  </div>
</template>
