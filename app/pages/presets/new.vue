<script setup lang="ts">
// Neues Preset – leer oder aus einer Vorlage (`?template=performance`).
const route = useRoute()
const template = computed(() => (isPresetTemplateId(route.query.template) ? route.query.template : null))
</script>

<template>
  <PresetEditor :key="template ?? 'empty'" :preset-id="null" :template="template" />
</template>
