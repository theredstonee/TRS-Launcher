<script setup lang="ts">
import { type VersionChange, versionChangeValid } from '~/utils/craftinghosting'

// Servertyp/Version wechseln (nur mit Schalter `versionChange`): Dialog mit Typ-Karten, Version und „Welt behalten“.
const store = useCraftingHostingStore()
const server = computed(() => store.server!)
const types = computed(() => store.info?.serverTypes ?? [])
const typeName = (id: string) => types.value.find((ty) => ty.id === id)?.name ?? id
const versionLabel = (v: string) => (v === 'latest' ? t('craftinghosting.latest') : v)

const open = ref(false)
const form = ref<VersionChange>({ serverType: '', version: '', keepWorld: true })
const versions = computed(() => types.value.find((ty) => ty.id === form.value.serverType)?.versions ?? [])
const valid = computed(() => versionChangeValid(form.value, server.value, store.info))

function start() {
  form.value = { serverType: server.value.serverType, version: server.value.version, keepWorld: true }
  open.value = true
}
watch(
  () => form.value.serverType,
  () => {
    if (!versions.value.includes(form.value.version)) form.value.version = versions.value[0] ?? ''
  },
)
async function submit() {
  if (!valid.value) return
  if (await store.changeVersion({ ...form.value })) open.value = false
}
</script>

<template>
  <section class="card flex flex-wrap items-center gap-3 p-4" aria-labelledby="ch-version-title" data-testid="hosting-version-card">
    <span class="grid size-9 shrink-0 place-items-center rounded-lg bg-base-800 text-base-300 ring-1 ring-base-700">
      <CraftinghostingTypeIcon :type="server.serverType" class="size-5" />
    </span>
    <div class="min-w-0 flex-1">
      <h3 id="ch-version-title" class="text-sm font-semibold text-base-50">{{ t('craftinghosting.version.title') }}</h3>
      <p class="text-xs text-base-400">{{ typeName(server.serverType) }} · {{ versionLabel(server.version) }}</p>
    </div>
    <button class="btn btn-ghost" :disabled="!!store.busy" data-testid="hosting-version-open" @click="start">{{ t('craftinghosting.version.change') }}</button>

    <BaseDialog v-if="open" :title="t('craftinghosting.version.change')" @close="open = false">
      <div class="space-y-4">
        <div class="grid grid-cols-2 gap-2 sm:grid-cols-3" role="radiogroup" :aria-label="t('craftinghosting.version.type')">
          <button
            v-for="ty in types"
            :key="ty.id"
            type="button"
            role="radio"
            :aria-checked="form.serverType === ty.id"
            class="flex flex-col items-center gap-1.5 rounded-lg px-2 py-3 text-xs ring-1 transition-colors motion-reduce:transition-none"
            :class="form.serverType === ty.id ? 'bg-redstone-900/30 text-base-50 ring-redstone-500/70' : 'bg-base-900 text-base-300 ring-base-800 hover:ring-base-600'"
            :data-testid="`hosting-version-type-${ty.id}`"
            @click="form.serverType = ty.id"
          >
            <CraftinghostingTypeIcon :type="ty.id" class="size-5" />
            {{ ty.name }}
          </button>
        </div>
        <div>
          <label for="ch-version-select" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.version.version') }}</label>
          <select id="ch-version-select" v-model="form.version" class="field" data-testid="hosting-version-select">
            <option v-for="v in versions" :key="v" :value="v">{{ versionLabel(v) }}</option>
          </select>
        </div>
        <label class="flex items-center justify-between gap-3 rounded-lg bg-base-900 px-3 py-2 text-sm ring-1 ring-base-800">
          {{ t('craftinghosting.version.keepWorld') }}
          <ToggleSwitch v-model="form.keepWorld" :label="t('craftinghosting.version.keepWorld')" />
        </label>
        <p class="rounded-lg bg-lamp-900/30 px-3 py-2 text-xs text-lamp-200 ring-1 ring-lamp-700/40" data-testid="hosting-version-warning">
          {{ form.keepWorld ? t('craftinghosting.version.warning') : t('craftinghosting.version.warningNewWorld') }}
        </p>
      </div>
      <template #actions>
        <button class="btn btn-ghost" @click="open = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="!valid || !!store.busy" data-testid="hosting-version-confirm" @click="submit">
          {{ store.busy === 'version' ? t('craftinghosting.version.working') : t('craftinghosting.version.confirm') }}
        </button>
      </template>
    </BaseDialog>
  </section>
</template>
