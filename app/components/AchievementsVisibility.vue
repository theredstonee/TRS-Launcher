<script setup lang="ts">
// „Erfolge für Freunde sichtbar“ – Schalter auf der Erfolge-Seite und unter
// Einstellungen → Datenschutz. Liegt auf dem TRS-Server je Minecraft-Account.
const store = useAchievementsStore()
const trs = useTrsStore()

onMounted(() => {
  if (trs.enabled && !store.data) void store.load()
})
</script>

<template>
  <SettingRow :title="t('achievements.visibility.title')" :description="t('achievements.visibility.description')">
    <ToggleSwitch
      :model-value="store.data?.visibleToFriends ?? true"
      :label="t('achievements.visibility.title')"
      :disabled="!store.data || store.savingVisible"
      data-testid="achievements-visible"
      @update:model-value="store.setVisible($event)"
    />
  </SettingRow>
</template>
