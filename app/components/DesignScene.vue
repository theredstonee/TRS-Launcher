<script setup lang="ts">
// Hintergrund-Szene des gewählten Designs: Redstone-Schaltung oder Winterlandschaft.
// Gleiche Props wie beide Szenen; ohne Angabe gilt deren eigener Standard.
// Solange Halloween läuft, überall die Mondnacht (Bibliothek, Instanzen, Entdecken, Leer-Zustände,
// Big Picture …) – das Event geht vor dem Design. `noEvent` behält das Design (z. B. Update-Titelbilder).
const props = defineProps<{
  mode?: 'idle' | 'starting' | 'running'
  progress?: number
  anchor?: HTMLElement | null
  fill?: boolean
  seed?: number
  /** Halloween: Standbild statt Animation (Einstellung „Animierter Hintergrund“ aus). */
  still?: boolean
  /** Halloween: Blitze an die Atmosphäre melden – nur die eine große Hintergrund-Szene. */
  bolts?: boolean
  /** Event-Szene nie zeigen, immer das Design. */
  noEvent?: boolean
}>()

const design = useDesign()
const events = useEventsStore()
const moonnight = computed(() => events.halloween && !props.noEvent)
</script>

<template>
  <MoonnightScene v-if="moonnight" :seed="seed" :still="still" :bolts="bolts">
    <slot />
  </MoonnightScene>
  <WinterScene v-else-if="design === 'winter'" :mode="mode" :progress="progress" :anchor="anchor" :fill="fill" :seed="seed">
    <slot />
  </WinterScene>
  <RedstoneScene v-else :mode="mode" :progress="progress" :anchor="anchor" :fill="fill" :seed="seed">
    <slot />
  </RedstoneScene>
</template>
