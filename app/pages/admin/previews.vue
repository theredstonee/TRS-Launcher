<script setup lang="ts">
// Team-Bereich „Vorschau-Builds“ (Recht previews.access, API §35.3): dieselbe Liste wie im Creator-Bereich
// (Partner), aber ohne Creator-Status. Der Server prüft das Recht selbst – hier nur die Oberfläche.
</script>

<template>
  <section class="space-y-4" :aria-label="t('team.nav.previews')" data-testid="admin-previews">
    <CreatorPreviewsTab :partner="true" />
  </section>
</template>
