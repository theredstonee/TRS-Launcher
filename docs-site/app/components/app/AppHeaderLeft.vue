<script setup lang="ts">
// Logo + Schriftzug „TRS Launcher“ + „Docs“ – führt zur Startseite der Doku in der aktuellen Sprache.
const { localePath } = useDocusI18n()
const icon = useBaseUrl('/icon.png')
</script>

<template>
  <NuxtLink :to="localePath('/')" class="flex min-w-0 items-center gap-2.5 text-highlighted" aria-label="TRS Launcher Docs">
    <img :src="icon" alt="" width="32" height="32" class="pixelated size-8 shrink-0">
    <span class="trs-display hidden text-xl leading-none min-[400px]:inline">TRS Launcher</span>
    <UBadge label="Docs" color="primary" variant="subtle" size="sm" class="trs-display" />
  </NuxtLink>
</template>
