<script setup lang="ts">
// Minecraft-Gesicht eines beliebigen Spielers (Freunde, Admin-Suche): Gesicht + Hut-Ebene aus der
// Skin-Textur, füllt den umgebenden Rahmen. Bis der Skin da ist – oder bei Standard-Skin/Fehler –
// das Pixel-Symbol aus der UUID. Links kommen aus dem Kern und sind nur textures.minecraft.net.
const props = defineProps<{ uuid: string; name: string }>()

const url = ref<string | null>(null)

watch(
  () => props.uuid,
  async (uuid) => {
    url.value = null
    const found = await playerSkinUrl(uuid)
    if (uuid === props.uuid && found?.startsWith('https://textures.minecraft.net/')) url.value = found
  },
  { immediate: true },
)

</script>

<template>
  <span class="relative block size-full overflow-hidden" role="img" :aria-label="name">
    <template v-if="url">
      <!-- Textur 8-fach breit, um eine Gesichtsbreite verschoben: passt für 64×64- und alte 64×32-Skins. -->
      <img :src="url" alt="" class="face-layer" style="left: -100%" />
      <img :src="url" alt="" class="face-layer" style="left: -500%" />
    </template>
    <PixelIdenticon v-else :seed="uuid" :letter="name.charAt(0).toUpperCase()" />
  </span>
</template>

<style scoped>
.face-layer {
  position: absolute;
  top: -100%;
  width: 800%;
  max-width: none;
  height: auto;
  image-rendering: pixelated;
}
</style>
