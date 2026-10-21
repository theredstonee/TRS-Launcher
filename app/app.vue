<script setup lang="ts">
import { MESSE, messeKioskWindow } from '~/utils/messe'

const kiosk = MESSE && messeKioskWindow()
</script>

<template>
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
  <!-- Nur im Messe-Build: Präsentation im Leerlauf, Personal-Panel, Schließen nur mit PIN -->
  <LazyMesseKiosk v-if="kiosk" />
  <!-- Start-Animation über der startenden App (geht von selbst weg) -->
  <StartIntro />
</template>
