<script setup lang="ts">
// „Was ist neu“ nach einem Update – derselbe Beitrag wie auf der Startseite (Banner, Screenshots, Hinweise,
// „Auf Website ansehen“); bei mehreren übersprungenen Versionen mit Umschalter.
const whatsNew = useWhatsNewStore()

function close() {
  whatsNew.open = false
}
</script>

<template>
  <UpdatePostDialog
    v-if="whatsNew.entries.length"
    :entries="whatsNew.entries"
    :heading="t('whatsNew.title', { version: whatsNew.version })"
    @close="close"
  />
</template>
