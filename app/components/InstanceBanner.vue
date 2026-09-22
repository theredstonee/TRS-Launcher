<script setup lang="ts">
import type { Instance } from '~/types'

// Breites Titelbild einer Instanz. Ohne eigenes Banner entsteht eines aus dem
// Instanz-Bild (weich gezeichnet) bzw. aus der Farbe des Modloaders – so hat
// jede Instanz von Anfang an einen eigenen Auftritt.
const props = withDefaults(
  defineProps<{
    instance: Pick<Instance, 'id' | 'name' | 'loader' | 'iconPath' | 'bannerPath'>
    /** Abdunklung für Text darüber: `none`, `bottom` (Verlauf) oder `full`. */
    shade?: 'none' | 'bottom' | 'full'
  }>(),
  { shade: 'bottom' },
)

const banner = computed(() => instanceBannerSrc(props.instance))
const icon = computed(() => instanceIconSrc(props.instance))
const color = computed(() => loaderColors[props.instance.loader.kind])
const failed = ref(false)
watch(banner, () => (failed.value = false))
</script>

<template>
  <div class="banner relative overflow-hidden" :style="{ '--loader': color }">
    <img
      v-if="banner && !failed"
      :src="banner"
      alt=""
      class="absolute inset-0 size-full object-cover"
      draggable="false"
      @error="failed = true"
    />
    <template v-else>
      <img v-if="icon" :src="icon" alt="" class="absolute inset-0 size-full scale-125 object-cover opacity-35 blur-2xl" draggable="false" />
      <div class="absolute inset-0 pixels" />
    </template>

    <!-- Der Schleier ist bewusst schwarz statt aus den Theme-Farben: Text auf
         einem Bild bleibt so in jedem Farbschema lesbar. -->
    <div v-if="shade === 'bottom'" class="absolute inset-0 bg-gradient-to-t from-black/85 via-black/55 to-black/20" />
    <div v-else-if="shade === 'full'" class="absolute inset-0 bg-black/60" />

    <div class="relative size-full">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.banner {
  background:
    radial-gradient(120% 160% at 12% 0%, color-mix(in srgb, var(--loader) 38%, transparent), transparent 60%),
    linear-gradient(120deg, var(--color-base-850), var(--color-base-950));
}
/* Deepslate-Raster – derselbe Look wie die Startrampe, nur als Füllung. */
.pixels {
  background:
    repeating-linear-gradient(0deg, rgb(255 255 255 / 0.03) 0 1px, transparent 1px 34px),
    repeating-linear-gradient(90deg, rgb(255 255 255 / 0.03) 0 1px, transparent 1px 34px);
}
</style>
