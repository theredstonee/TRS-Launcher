<script setup lang="ts">
// Kleines Symbol je Servertyp (Paper, Purpur, Vanilla, Fabric, Forge).
defineProps<{ type: string }>()
</script>

<template>
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" stroke-linecap="round" aria-hidden="true">
    <template v-if="type === 'paper'">
      <path d="M7 3h7l4 4v14H7z" />
      <path d="M14 3v4h4M10 12h5M10 15.5h5" />
    </template>
    <template v-else-if="type === 'purpur'">
      <path d="M12 3 20 7.5v9L12 21l-8-4.5v-9z" />
      <path d="m4 7.5 8 4.5 8-4.5M12 12v9" />
    </template>
    <template v-else-if="type === 'vanilla'">
      <path d="M4 7.5 12 3l8 4.5v9L12 21l-8-4.5z" />
      <path d="m4 7.5 8 4.5 8-4.5M4 10.5l8 4.5 8-4.5" />
    </template>
    <template v-else-if="type === 'fabric'">
      <path d="M5 5h14v14H5z" />
      <path d="M5 9.5h14M5 14.5h14M9.5 5v14M14.5 5v14" />
    </template>
    <template v-else-if="type === 'forge'">
      <path d="M4 8h12l4-2v4l-4 2H8z" />
      <path d="M10 12v4M8 20h8l-2-4h-4z" />
    </template>
    <template v-else>
      <rect x="4" y="5" width="16" height="6" rx="1.5" />
      <rect x="4" y="13" width="16" height="6" rx="1.5" />
      <path d="M8 8h.01M8 16h.01" />
    </template>
  </svg>
</template>
