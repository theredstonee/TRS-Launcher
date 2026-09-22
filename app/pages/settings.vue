<script setup lang="ts">
// Die Einstellungen sind ein Modal (wie in der Modrinth App). Alte Links auf
// /settings öffnen es und kehren zur Startseite zurück.
const settings = useSettingsStore()
const router = useRouter()
onMounted(() => {
  settings.open()
  router.replace('/')
})
</script>

<template>
  <div />
</template>
