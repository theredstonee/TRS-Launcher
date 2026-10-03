<script setup lang="ts">
// Big Picture „Start“: zuletzt gespielte Instanz groß mit Spielen-Lampe,
// darunter die nächsten Instanzen zum schnellen Weiterspielen.
const instances = useInstancesStore()
const games = useGamesStore()
const settings = useSettingsStore()
const store = useBigPictureStore()

const featured = computed(() => instances.items[0] ?? null)
const game = computed(() => (featured.value ? games.state(featured.value.id) : null))
const recent = computed(() => instances.items.slice(1, 5))
const showPlayTime = computed(() => settings.current?.ui.showPlayTime !== false)
</script>

<template>
  <div class="space-y-12">
    <section v-if="featured" class="hero" :data-instance="featured.id" :aria-label="t('home.lastPlayed', { name: featured.name })">
      <InstanceBanner :instance="featured" shade="full" class="hero-banner" />
      <div class="relative flex flex-wrap items-end gap-x-10 gap-y-8 p-10">
        <InstanceIcon :instance="featured" :size="144" class="shrink-0 ring-4 ring-base-900" />
        <div class="min-w-0 flex-1">
          <p class="text-xl text-base-200">{{ t('bigPicture.home.continue') }}</p>
          <h2 class="display mt-1 truncate text-7xl leading-[1.05] text-base-50">{{ featured.name }}</h2>
          <p class="mt-4 flex flex-wrap items-center gap-x-6 gap-y-1 text-xl text-base-200">
            <span class="flex items-center gap-2">
              <span class="size-3" :style="{ background: loaderColors[featured.loader.kind] }" />
              <span><span class="font-mono text-base-50">{{ featured.gameVersion }}</span> {{ loaderLabels[featured.loader.kind] }}</span>
            </span>
            <span>{{ formatRelative(featured.lastPlayed) }}</span>
            <span v-if="showPlayTime && featured.totalPlaySeconds >= 60">{{ t('home.playedTime', { time: formatPlayTime(featured.totalPlaySeconds) }) }}</span>
          </p>
        </div>
        <div class="flex w-full items-center gap-4 lg:w-[30rem]">
          <div class="hero-play min-w-0 flex-1" data-bp-autofocus>
            <PlayButton :instance-id="featured.id" large />
          </div>
          <button
            type="button"
            class="bp-btn size-[5.5rem] shrink-0 px-0"
            :aria-label="t('bigPicture.instance.options')"
            :title="t('bigPicture.instance.options')"
            @click="store.openLayer({ kind: 'instance', id: featured.id })"
          >
            <svg viewBox="0 0 24 24" class="size-8" fill="currentColor"><circle cx="5" cy="12" r="2" /><circle cx="12" cy="12" r="2" /><circle cx="19" cy="12" r="2" /></svg>
          </button>
        </div>
        <p v-if="game?.error" role="alert" class="w-full text-lg text-redstone-300">{{ game.error }}</p>
      </div>
    </section>

    <div v-else-if="instances.loaded" class="bp-empty">
      <h2 class="bp-heading">{{ t('bigPicture.home.emptyTitle') }}</h2>
      <p class="mt-3">{{ t('bigPicture.home.emptyText') }}</p>
      <button type="button" class="bp-btn bp-btn-primary mt-8" data-bp-autofocus @click="store.close()">{{ t('bigPicture.exit') }}</button>
    </div>

    <section v-if="recent.length" aria-labelledby="bp-recent">
      <h2 id="bp-recent" class="bp-heading mb-5 text-3xl">{{ t('bigPicture.home.recent') }}</h2>
      <div class="grid grid-cols-2 gap-6 xl:grid-cols-4">
        <BigInstanceTile v-for="i in recent" :key="i.id" :instance="i" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.hero {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  border-radius: 1.5rem;
  border: 2px solid var(--color-base-800);
  min-height: 24rem;
  display: flex;
  align-items: flex-end;
}
.hero-banner {
  position: absolute !important;
  inset: 0;
  z-index: -1;
  width: 100%;
  height: 100%;
}
/* Die Spielen-Lampe der Startseite, für den Fernseher vergrößert. */
.hero-play :deep(.lamp) {
  height: 5.5rem;
}
.hero-play :deep(.lamp .display) {
  font-size: 2.25rem;
}
.hero-play :deep(.lamp svg) {
  width: 2rem;
  height: 2rem;
}
.hero-play :deep(.lamp .text-xs) {
  font-size: 1rem;
}
/* Der Fokusrahmen sitzt innen (Pixel-Ecken), dazu leuchtet die ganze Lampe. */
.hero-play:focus-within {
  filter: drop-shadow(0 0 22px color-mix(in srgb, var(--color-lamp-300) 70%, transparent));
}
</style>
