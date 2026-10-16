<script setup lang="ts">
// Profil-Abzeichen eines Spielers (z. B. „Halloween 2026“, „Kürbisjäger 2026“) als kleine
// Pixel-Symbole neben dem Namen. Bilder kommen vom Kern (`/v1/badges/<id>.png`, Data-URL).
// `badges` direkt übergeben oder per `uuid` aus dem Spieler-Lookup holen lassen.
// `chips`: mit Namen als Pille (Freundes-Details).
const props = withDefaults(defineProps<{ uuid?: string; badges?: readonly string[] | null; size?: number; chips?: boolean }>(), {
  uuid: undefined,
  badges: null,
  size: 16,
  chips: false,
})
const halloween = useHalloweenStore()
const list = computed<readonly string[]>(() => props.badges ?? (props.uuid ? (halloween.playerBadges[props.uuid] ?? []) : []))
const shown = computed(() => list.value.filter((id) => halloween.badgeImages[id]))

watchEffect(() => {
  if (props.badges) void halloween.ensureBadges(props.badges)
  else if (props.uuid) halloween.wantPlayers([props.uuid])
})

function label(id: string): string {
  const key = `halloween.badges.${id}`
  return hasKey(key) ? tKey(key) : id
}
</script>

<template>
  <span v-if="shown.length" class="inline-flex shrink-0 items-center gap-1" :class="{ 'flex-wrap gap-1.5': chips }">
    <template v-for="id in shown" :key="id">
      <span v-if="chips" class="hw-badge-chip" :class="{ pk: id.startsWith('pumpkin') }">
        <img :src="halloween.badgeImages[id]!" alt="" class="[image-rendering:pixelated]" :style="{ height: `${size}px` }">{{ label(id) }}
      </span>
      <img
        v-else
        :src="halloween.badgeImages[id]!"
        :alt="label(id)"
        :title="label(id)"
        class="w-auto [image-rendering:pixelated]"
        :style="{ height: `${size}px` }"
        :data-testid="`badge-${id}`"
      >
    </template>
  </span>
</template>

<style scoped>
.hw-badge-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px 3px 6px;
  border: 1px solid rgba(207, 213, 227, 0.4);
  border-radius: 99px;
  background: linear-gradient(90deg, rgba(58, 31, 92, 0.75), rgba(20, 16, 33, 0.8));
  box-shadow: 0 0 18px rgba(185, 162, 234, 0.18);
  font-size: 12px;
  font-weight: 600;
  color: #f3f5fb;
}
.hw-badge-chip.pk {
  border-color: rgba(224, 138, 60, 0.5);
  box-shadow: 0 0 18px rgba(224, 138, 60, 0.18);
}
</style>
