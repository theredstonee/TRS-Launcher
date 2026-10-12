<script setup lang="ts">
// Fortschritt im Design „Winternacht“: ein Eisbalken, der von links zufriert.
// Vorn sitzt ein Eiskristall, über das Eis laufen kleine Glitzerpunkte.
// `powered` = Spiel läuft (ganz zugefroren, sanftes Leuchten), `indeterminate` =
// Fortschritt unbekannt (ein Frostschimmer läuft immer wieder durch).
const props = withDefaults(defineProps<{ percent?: number; powered?: boolean; indeterminate?: boolean }>(), {
  percent: 0,
  powered: false,
  indeterminate: false,
})

const fill = computed(() => (props.powered ? 100 : Math.min(100, Math.max(0, props.percent))))
</script>

<template>
  <div class="frost" :class="{ 'frost-wait': indeterminate, 'frost-powered': powered }" :style="{ '--p': `${fill}%` }" aria-hidden="true">
    <template v-if="!indeterminate">
      <span class="frost-fill" />
      <span v-if="fill > 0 && fill < 100" class="frost-tip" />
    </template>
    <span v-else class="frost-shimmer" />
  </div>
</template>

<style scoped>
/* Spur: dunkles Eis mit Fugen wie Eisblöcke. */
.frost {
  position: relative;
  height: 6px;
  overflow: visible;
  background:
    repeating-linear-gradient(90deg, transparent 0 13px, color-mix(in srgb, var(--color-redstone-300) 14%, transparent) 13px 14px),
    var(--color-redstone-900);
  box-shadow: inset 0 -1px 0 color-mix(in srgb, #000 25%, transparent);
}
.frost-fill {
  position: absolute;
  inset-block: 0;
  left: 0;
  width: var(--p);
  overflow: hidden;
  /* Oben eine helle Kante, darunter Eisblau; Fugen und Glitzer darüber. */
  background:
    linear-gradient(180deg, color-mix(in srgb, #fff 70%, var(--color-redstone-300)) 0 1px, transparent 1px),
    repeating-linear-gradient(90deg, transparent 0 13px, color-mix(in srgb, #fff 45%, transparent) 13px 14px),
    linear-gradient(90deg, var(--color-redstone-600), var(--color-redstone-500) 40%, var(--color-redstone-400));
  box-shadow: 0 0 8px color-mix(in srgb, var(--color-redstone-400) 55%, transparent);
  transition: width 0.25s steps(6, end);
}
/* Glitzer: einzelne helle Pixel, die über das Eis wandern. */
.frost-fill::after {
  content: "";
  position: absolute;
  inset: 0;
  background: url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' width='60' height='6' shape-rendering='crispEdges'><path d='M5 2h2v2H5zM54 1h2v2h-2z' fill='%23fff'/><path d='M31 3h1v1h-1zM43 2h1v1h-1z' fill='%23fff' fill-opacity='.7'/></svg>") 0 0 / 60px 6px repeat-x;
  opacity: 0.85;
  animation: frost-glitter 2.4s steps(12) infinite;
}
/* Eiskristall an der Gefrierkante. */
.frost-tip {
  position: absolute;
  top: 50%;
  left: var(--p);
  width: 8px;
  height: 8px;
  translate: -50% -50%;
  rotate: 45deg;
  background: #f2faff;
  box-shadow:
    inset 0 0 0 2px var(--color-redstone-300),
    0 0 8px var(--color-redstone-400);
  transition: left 0.25s steps(6, end);
}
/* Spiel läuft: ganz zugefroren und leuchtet ruhig. */
.frost-powered .frost-fill {
  background:
    linear-gradient(180deg, #fff 0 1px, transparent 1px),
    repeating-linear-gradient(90deg, transparent 0 13px, color-mix(in srgb, #fff 55%, transparent) 13px 14px),
    linear-gradient(90deg, var(--color-lamp-400), var(--color-lamp-300), var(--color-lamp-400));
  box-shadow: 0 0 10px var(--color-lamp-400);
  animation: frost-glow 2.4s ease-in-out infinite;
}
/* Unbekannter Fortschritt: ein Frostschimmer läuft durch. */
.frost-wait {
  overflow: hidden;
}
.frost-shimmer {
  position: absolute;
  inset-block: 0;
  left: 0;
  width: 35%;
  background: linear-gradient(90deg, transparent, var(--color-redstone-500), #fff, var(--color-redstone-400), transparent);
  box-shadow: 0 0 8px var(--color-redstone-400);
  animation: frost-pass 1.6s ease-in-out infinite;
}
@keyframes frost-glitter {
  from {
    background-position: 0 0;
  }
  to {
    background-position: 60px 0;
  }
}
@keyframes frost-glow {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.7;
  }
}
@keyframes frost-pass {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(290%);
  }
}
:root[data-reduced-motion] .frost-fill::after,
:root[data-reduced-motion] .frost-powered .frost-fill {
  animation: none;
}
:root[data-reduced-motion] .frost-shimmer {
  animation: none;
  width: 100%;
  opacity: 0.5;
}
</style>
