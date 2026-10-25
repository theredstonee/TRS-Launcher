<script setup lang="ts">
// Fortschritt im Design „Fuchsbau“: ein glimmender Ast. Von links frisst sich die Glut
// durch das dunkle Holz, vorn sitzt eine kleine Flamme, aus der Glut steigen Funken.
// `powered` = Spiel läuft (der ganze Ast glüht golden und atmet), `indeterminate` =
// Fortschritt unbekannt (ein Funke läuft immer wieder durch).
const props = withDefaults(defineProps<{ percent?: number; powered?: boolean; indeterminate?: boolean }>(), {
  percent: 0,
  powered: false,
  indeterminate: false,
})

const fill = computed(() => (props.powered ? 100 : Math.min(100, Math.max(0, props.percent))))
</script>

<template>
  <div class="ember" :class="{ 'ember-wait': indeterminate, 'ember-powered': powered }" :style="{ '--p': `${fill}%` }" aria-hidden="true">
    <template v-if="!indeterminate">
      <span class="ember-fill" />
      <span v-if="fill > 0 && fill < 100" class="ember-tip" />
    </template>
    <span v-else class="ember-spark" />
  </div>
</template>

<style scoped>
/* Spur: verkohltes Holz mit Maserung und kleinen Ästen (Blattansätzen). */
.ember {
  position: relative;
  height: 6px;
  overflow: visible;
  background:
    repeating-linear-gradient(90deg, transparent 0 9px, color-mix(in srgb, #000 30%, transparent) 9px 10px, transparent 10px 17px, color-mix(in srgb, var(--color-redstone-300) 10%, transparent) 17px 18px),
    linear-gradient(180deg, color-mix(in srgb, var(--color-redstone-300) 10%, transparent) 0 1px, transparent 1px),
    color-mix(in srgb, var(--color-redstone-900) 80%, #1a0f08);
  box-shadow: inset 0 -1px 0 color-mix(in srgb, #000 30%, transparent);
}
.ember-fill {
  position: absolute;
  inset-block: 0;
  left: 0;
  width: var(--p);
  overflow: hidden;
  /* Oben heller Glutrand, darunter von dunkelrot zu golden. */
  background:
    linear-gradient(180deg, color-mix(in srgb, #fff3c8 75%, var(--color-lamp-300)) 0 1px, transparent 1px),
    repeating-linear-gradient(90deg, transparent 0 9px, color-mix(in srgb, #3a1404 35%, transparent) 9px 10px),
    linear-gradient(90deg, var(--color-redstone-600), var(--color-redstone-500) 45%, var(--color-lamp-400));
  box-shadow: 0 0 8px color-mix(in srgb, var(--color-redstone-400) 60%, transparent);
  transition: width 0.25s steps(6, end);
}
/* Glut flimmert: helle Pixel wandern durch. */
.ember-fill::after {
  content: "";
  position: absolute;
  inset: 0;
  background: url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' width='48' height='6' shape-rendering='crispEdges'><path d='M4 3h2v1H4zM29 2h1v1h-1z' fill='%23fff2a8'/><path d='M17 4h1v1h-1zM39 3h2v1h-2z' fill='%23ffb340' fill-opacity='.8'/></svg>") 0 0 / 48px 6px repeat-x;
  opacity: 0.9;
  animation: ember-flicker 1.8s steps(9) infinite;
}
/* Kleine Pixel-Flamme an der Glutkante, darüber ein aufsteigender Funke. */
.ember-tip {
  position: absolute;
  top: 50%;
  left: var(--p);
  width: 8px;
  height: 12px;
  translate: -50% -78%;
  background: url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' width='4' height='6' shape-rendering='crispEdges'><path d='M1 0h1v1H1zM1 1h2v1H1zM0 2h4v2H0zM0 4h4v1H0zM1 5h2v1H1z' fill='%23ff6a1a'/><path d='M1 2h2v2H1zM1 4h2v1H1z' fill='%23ffb340'/><path d='M1 3h1v2H1z' fill='%23fff2a8'/></svg>") 0 0 / 8px 12px no-repeat;
  filter: drop-shadow(0 0 4px var(--color-lamp-400));
  transition: left 0.25s steps(6, end);
  animation: ember-flame 0.5s steps(2) infinite;
}
.ember-tip::after {
  content: "";
  position: absolute;
  left: 3px;
  top: -2px;
  width: 2px;
  height: 2px;
  background: var(--color-lamp-300);
  animation: ember-spark-up 1.2s steps(6) infinite;
}
/* Spiel läuft: der ganze Ast glüht golden und atmet. */
.ember-powered .ember-fill {
  background:
    linear-gradient(180deg, #fff6d8 0 1px, transparent 1px),
    repeating-linear-gradient(90deg, transparent 0 9px, color-mix(in srgb, #6b2a00 30%, transparent) 9px 10px),
    linear-gradient(90deg, var(--color-lamp-400), var(--color-lamp-300), var(--color-lamp-400));
  box-shadow: 0 0 10px var(--color-lamp-400);
  animation: ember-glow 2.4s ease-in-out infinite;
}
/* Unbekannter Fortschritt: ein Funke läuft durch. */
.ember-wait {
  overflow: hidden;
}
.ember-spark {
  position: absolute;
  inset-block: 0;
  left: 0;
  width: 30%;
  background: linear-gradient(90deg, transparent, var(--color-redstone-600), var(--color-redstone-500), var(--color-lamp-300), transparent);
  box-shadow: 0 0 8px var(--color-redstone-400);
  animation: ember-pass 1.6s ease-in-out infinite;
}
@keyframes ember-flicker {
  from {
    background-position: 0 0;
  }
  to {
    background-position: 48px 0;
  }
}
@keyframes ember-flame {
  50% {
    transform: scaleY(0.82) translateY(1px);
  }
}
@keyframes ember-spark-up {
  from {
    transform: translate(0, 0);
    opacity: 1;
  }
  to {
    transform: translate(2px, -10px);
    opacity: 0;
  }
}
@keyframes ember-glow {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.75;
  }
}
@keyframes ember-pass {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(340%);
  }
}
:root[data-reduced-motion] .ember-fill::after,
:root[data-reduced-motion] .ember-powered .ember-fill,
:root[data-reduced-motion] .ember-tip,
:root[data-reduced-motion] .ember-tip::after {
  animation: none;
}
:root[data-reduced-motion] .ember-spark {
  animation: none;
  width: 100%;
  opacity: 0.5;
}
</style>
