<script setup lang="ts">
import { dimensionLabel, waypointColor, waypointCoords, waypointCoordsLabel, waypointPlace, type ChatWaypoint } from '~/utils/waypoint'

// Wegpunkt-Karte aus dem TRS Client (API §18.10): Name, Koordinaten, Dimension
// und Server bzw. Einzelspielerwelt. Im Launcher gibt es nichts zu „übernehmen“ –
// nur die Koordinaten zum Kopieren (z. B. für /tp oder eine andere Karte).
const props = defineProps<{ waypoint: ChatWaypoint }>()
const toasts = useToasts()

const color = computed(() => waypointColor(props.waypoint))
const place = computed(() => waypointPlace(props.waypoint))

async function copy() {
  try {
    await navigator.clipboard.writeText(waypointCoords(props.waypoint))
    toasts.ok(t('social.waypoint.copied', { coords: waypointCoords(props.waypoint) }))
  } catch (e) {
    toasts.error(e)
  }
}
</script>

<template>
  <div class="waypoint-card flex items-center gap-3 rounded-lg p-2.5" data-testid="waypoint-card">
    <span class="grid size-12 shrink-0 place-items-center rounded-md bg-base-950 ring-1 ring-base-700" :style="{ color }">
      <SocialIcon name="pin" class="size-6" />
    </span>
    <span class="min-w-0 flex-1">
      <span class="block truncate text-sm font-semibold text-base-50">{{ waypoint.name }}</span>
      <span class="block truncate font-mono text-xs text-base-200" data-testid="waypoint-coords">{{ waypointCoordsLabel(waypoint) }}</span>
      <span class="block truncate text-[11px] text-base-400">
        {{ dimensionLabel(waypoint.dimension) }} · <span :class="waypoint.world.type === 'server' ? 'font-mono' : ''">{{ place }}</span>
      </span>
    </span>
    <button
      class="btn btn-ghost shrink-0 px-2.5 py-1.5 text-xs"
      :title="t('social.waypoint.copyTitle')"
      data-testid="waypoint-copy"
      @click="copy"
    >
      <SocialIcon name="copy" class="size-3.5" />{{ t('social.waypoint.copy') }}
    </button>
  </div>
</template>

<style scoped>
.waypoint-card {
  min-width: 17rem;
  background: var(--color-base-900);
  box-shadow: inset 0 0 0 1px var(--color-base-700);
  color: var(--color-base-50);
}
</style>
