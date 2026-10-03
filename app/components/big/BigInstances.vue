<script setup lang="ts">
// Big Picture „Bibliothek“: alle Instanzen als große Kacheln (zuletzt gespielt zuerst).
const instances = useInstancesStore()
const store = useBigPictureStore()
</script>

<template>
  <section aria-labelledby="bp-instances">
    <h2 id="bp-instances" class="bp-heading mb-6">{{ t('bigPicture.sections.instances') }}</h2>
    <div v-if="instances.items.length" class="grid gap-7" style="grid-template-columns: repeat(auto-fill, minmax(21rem, 1fr))">
      <BigInstanceTile v-for="(i, index) in instances.items" :key="i.id" :instance="i" :data-bp-autofocus="index === 0 ? '' : undefined" />
    </div>
    <div v-else-if="instances.loaded" class="bp-empty">
      <p>{{ t('bigPicture.home.emptyText') }}</p>
      <button type="button" class="bp-btn bp-btn-primary mt-8" data-bp-autofocus @click="store.close()">{{ t('bigPicture.exit') }}</button>
    </div>
  </section>
</template>
