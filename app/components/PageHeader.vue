<script setup lang="ts">
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <!-- Handy: Titel oben, die Aktionen darunter als wischbare Reihe (Seiten haben dort p-4). -->
  <div class="mb-6 flex items-end justify-between gap-4 mobile:mb-4 mobile:flex-col mobile:items-stretch mobile:gap-3">
    <div class="min-w-0">
      <h1 class="text-xl font-semibold tracking-tight">{{ title }}</h1>
      <p v-if="subtitle" class="mt-0.5 text-sm text-base-400">{{ subtitle }}</p>
    </div>
    <div class="flex shrink-0 items-center gap-2 mobile-scroll-x mobile:-mx-4 mobile:px-4 mobile:empty:hidden mobile:[&>*]:shrink-0">
      <slot />
    </div>
  </div>
</template>
