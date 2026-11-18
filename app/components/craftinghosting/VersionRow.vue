<script setup lang="ts">
// Servertyp und Version – wechseln bietet CraftingHosting noch nicht an.
const store = useCraftingHostingStore()
const server = computed(() => store.server!)
const typeName = computed(() => store.info?.serverTypes.find((ty) => ty.id === server.value.serverType)?.name ?? server.value.serverType)
const version = computed(() => (server.value.version === 'latest' ? t('craftinghosting.latest') : server.value.version))
</script>

<template>
  <section class="card flex flex-wrap items-center gap-3 p-4" aria-labelledby="ch-version-title" data-testid="hosting-version-row">
    <span class="grid size-9 shrink-0 place-items-center rounded-lg bg-base-800 text-base-300 ring-1 ring-base-700">
      <CraftinghostingTypeIcon :type="server.serverType" class="size-5" />
    </span>
    <div class="min-w-0 flex-1">
      <h3 id="ch-version-title" class="text-sm font-semibold text-base-50">{{ t('craftinghosting.version.title') }}</h3>
      <p class="text-xs text-base-400">{{ typeName }} · {{ version }}</p>
    </div>
    <span class="badge bg-sky-900/40 text-sky-300 ring-1 ring-sky-700/40">{{ t('craftinghosting.version.soon') }}</span>
    <button class="btn btn-ghost" disabled :title="t('craftinghosting.version.soonHint')">{{ t('craftinghosting.version.change') }}</button>
  </section>
</template>
