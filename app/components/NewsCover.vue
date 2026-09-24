<script setup lang="ts">
import type { NewsItem } from '~/types'

// Titelbild einer Meldung. Mit Bild: das Bild. Launcher-Versionen: ihre eigene Redstone-Szene.
// Modrinth (nur Icons): Icon groß und scharf auf einem weichgezeichneten Hintergrund aus sich selbst.
// Sonst: Deepslate mit einem Staubfaden.
defineProps<{ item: NewsItem; image?: string; post?: ChangelogEntry | null; large?: boolean }>()
</script>

<template>
  <RedstoneScene v-if="item.source === 'launcher'" fill :seed="versionSeed(post?.version ?? item.title)" class="absolute inset-0" />
  <template v-else-if="image && item.source === 'modrinth'">
    <img :src="image" alt="" class="absolute inset-0 size-full scale-125 object-cover opacity-60 blur-xl" />
    <div class="absolute inset-0 grid place-items-center">
      <img
        :src="image"
        alt=""
        loading="lazy"
        class="rounded-xl shadow-2xl ring-1 ring-white/10 [image-rendering:pixelated] transition-transform duration-500 group-hover:scale-105"
        :class="large ? 'size-28' : 'size-16'"
      />
    </div>
  </template>
  <img
    v-else-if="image"
    :src="image"
    alt=""
    loading="lazy"
    class="absolute inset-0 size-full object-cover transition-transform duration-500 group-hover:scale-[1.03]"
  />
  <div v-else class="cover-fallback absolute inset-0" />
</template>

<style scoped>
/* Platzhalter ohne Bild: Deepslate mit einem Staubfaden. */
.cover-fallback {
  background:
    linear-gradient(90deg, transparent 46%, color-mix(in srgb, var(--color-redstone-500) 55%, transparent) 46% 54%, transparent 54%) center / 100% 8px no-repeat,
    var(--deepslate) 0 0 / 48px 48px,
    var(--color-base-850);
}
</style>
