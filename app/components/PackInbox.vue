<script setup lang="ts">
// „An dich geschickt“: Modpacks, die Freunde dir geschickt haben – ansehen &
// installieren oder aus der Liste nehmen. Unsichtbar, solange die Liste leer ist.
const packs = usePacksStore()
const trs = useTrsStore()

onMounted(() => {
  if (trs.enabled) void packs.loadInbox()
})
watch(
  () => trs.enabled,
  (on) => {
    if (on) void packs.loadInbox()
  },
)
</script>

<template>
  <section v-if="packs.inbox.length" class="mb-6" aria-labelledby="pack-inbox-heading">
    <h2 id="pack-inbox-heading" class="heading mb-2">{{ t('packs.inbox.title') }}</h2>
    <ul class="grid gap-2 md:grid-cols-2" data-testid="pack-inbox">
      <li v-for="e in packs.inbox" :key="e.pack.id" class="card flex items-center gap-3 px-3 py-2.5">
        <span class="block size-8 shrink-0 overflow-hidden rounded"><PlayerFace :uuid="e.from.uuid" :name="e.from.name" /></span>
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-medium text-base-50">{{ e.pack.name }}</p>
          <p class="truncate text-xs text-base-400">{{ t('packs.inbox.from', { name: e.from.name }) }} · {{ packVersionLine(e.pack) }}</p>
        </div>
        <button class="btn btn-primary shrink-0 px-3 py-1.5 text-xs" @click="packs.openCode(e.pack.code)">{{ t('packs.inbox.view') }}</button>
        <button class="btn btn-ghost shrink-0 px-2 py-1.5 text-xs" :aria-label="t('packs.inbox.dismiss', { name: e.pack.name })" :title="t('packs.inbox.dismiss', { name: e.pack.name })" @click="packs.dismiss(e.pack.id)">
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 6l12 12M18 6 6 18" /></svg>
        </button>
      </li>
    </ul>
  </section>
</template>
