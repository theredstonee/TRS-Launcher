<script setup lang="ts">
import type { Instance } from '~/types'
import { generatedBanner } from '~/utils/redstone/banner'

// Breites Titelbild einer Instanz. Ohne eigenes Banner entsteht ein Pixel-Motiv:
// eine kleine Redstone-Schaltung aus der Instanz-ID, leicht in der Farbe des
// Modloaders getönt – so hat jede Instanz von Anfang an einen eigenen Auftritt.
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

// Das Motiv folgt Theme und Akzent; erzeugt wird es erst im Browser.
const settings = useSettingsStore()
const generated = ref<string | null>(null)
function regenerate() {
  generated.value = banner.value && !failed.value ? null : generatedBanner(props.instance.id)
}
onMounted(regenerate)
watch([banner, failed, () => props.instance.id, () => settings.current?.ui.theme, () => settings.current?.ui.accent], () =>
  nextTick(regenerate),
)
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
      <img v-if="generated" :src="generated" alt="" class="absolute inset-0 size-full object-cover [image-rendering:pixelated]" draggable="false" />
      <img v-else-if="icon" :src="icon" alt="" class="absolute inset-0 size-full scale-125 object-cover opacity-35 blur-2xl" draggable="false" />
      <div class="tint absolute inset-0" />
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
/* Tönung in der Loader-Farbe über dem Pixel-Motiv. */
.tint {
  background: radial-gradient(120% 140% at 85% 0%, color-mix(in srgb, var(--loader) 30%, transparent), transparent 65%);
  mix-blend-mode: soft-light;
}
</style>
