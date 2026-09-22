<script setup lang="ts">
// Spiel-Events, Accounts und Instanzen einmal zentral laden – unabhängig von der Seite.
const games = useGamesStore()
const accounts = useAccountsStore()
const instances = useInstancesStore()
const onboarding = useOnboardingStore()
const settings = useSettingsStore()

onMounted(async () => {
  games.init()
  // Darstellung (Theme, Akzent) und Oberflächen-Schalter früh laden.
  settings.load().catch(() => {})
  // Erst wenn beides geladen ist, entscheiden, ob der Einrichtungs-Assistent kommt.
  await Promise.allSettled([accounts.load(), instances.load()])
  onboarding.openIfFirstRun()
})
</script>

<template>
  <div class="flex h-full flex-col">
    <TitleBar />
    <!-- Der Assistent überdeckt alles unter der Titelleiste; die Fenstersteuerung bleibt bedienbar. -->
    <div class="relative flex min-h-0 flex-1 flex-col">
      <UpdateBanner />
      <div class="flex min-h-0 flex-1">
        <SideNav />
        <main class="min-w-0 flex-1 overflow-y-auto bg-base-950">
          <slot />
        </main>
      </div>
      <Transition name="onboarding" appear>
        <OnboardingWizard v-if="onboarding.open" />
      </Transition>
    </div>
    <AppSettingsDialog v-if="settings.dialog" />
    <ToastHost />
  </div>
</template>

<style scoped>
/* Einmaliger Auftritt: kurz einblenden und leicht heranzoomen. */
.onboarding-enter-active {
  transition: opacity 0.28s ease-out, transform 0.28s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.onboarding-leave-active {
  transition: opacity 0.16s ease-in;
}
.onboarding-enter-from {
  opacity: 0;
  transform: scale(0.985);
}
.onboarding-leave-to {
  opacity: 0;
}
</style>
