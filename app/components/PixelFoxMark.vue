<script setup lang="ts">
// Kleines Pixel-Fuchsgesicht (9 × 7, Minecraft-Fuchs von vorn) neben dem Logo im Design
// „Fuchsbau“. Fell in der Akzentfarbe, weiße Wangen; ab und zu blinzelt er.
</script>

<template>
  <svg class="fox-mark" viewBox="0 0 9 7" shape-rendering="crispEdges" aria-hidden="true">
    <path fill="currentColor" d="M1 0h1v1H1zM7 0h1v1H7zM1 1h2v1H1zM6 1h2v1H6zM0 2h9v1H0zM0 3h9v1H0zM0 4h9v1H0zM2 5h5v1H2z" />
    <path fill="#2a1a16" d="M1 0h1v1H1zM7 0h1v1H7zM4 5h1v1H4z" />
    <path fill="#fff4ea" d="M0 4h2v1H0zM7 4h2v1H7zM1 5h1v1H1zM7 5h1v1H7zM2 6h5v1H2z" />
    <path class="eyes" fill="#2a1a16" d="M2 3h1v1H2zM6 3h1v1H6z" />
  </svg>
</template>

<style scoped>
.fox-mark {
  width: 13px;
  height: 10px;
  flex-shrink: 0;
  pointer-events: none;
  color: var(--color-redstone-400);
  filter: drop-shadow(0 0 3px color-mix(in srgb, var(--color-redstone-500) 60%, transparent));
}
/* Blinzeln: die Augen sind kurz zu. */
.eyes {
  animation: fox-blink 6s steps(1) infinite;
}
@keyframes fox-blink {
  0%,
  93% {
    opacity: 1;
  }
  94%,
  97% {
    opacity: 0;
  }
}
:root[data-reduced-motion] .eyes {
  animation: none;
}
</style>
