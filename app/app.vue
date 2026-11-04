<script setup lang="ts">
import { isTauri } from '@tauri-apps/api/core'
import { MESSE, messeKioskWindow } from '~/utils/messe'

const kiosk = MESSE && messeKioskWindow()

function preventNativeContextMenu(event: MouseEvent) {
  if (!(event.target instanceof Element)) return
  if (event.target.closest('input, textarea, select, [contenteditable]:not([contenteditable="false"]), [role="textbox"]')) return
  event.preventDefault()
}

onMounted(() => {
  if (isTauri()) document.addEventListener('contextmenu', preventNativeContextMenu)
})

onBeforeUnmount(() => document.removeEventListener('contextmenu', preventNativeContextMenu))
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
