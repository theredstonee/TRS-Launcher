<script setup lang="ts">
// Urheberhinweis fehlt oder wurde geändert: rote Leiste über allen Seiten. Nur ein Hinweis, sperrt nichts und sendet nichts.
const settings = useSettingsStore()
</script>

<template>
  <div v-if="!authorNotice.intact" role="alert" class="relative z-40 flex shrink-0 items-center justify-center gap-3 bg-redstone-600 px-4 py-1.5 text-xs font-medium text-white" data-testid="author-notice-banner">
    <span>{{ authorNotice.warningShort }}</span>
    <button type="button" class="underline" @click="settings.open('about')">{{ t('settings.about.warningDetails') }}</button>
  </div>
</template>
