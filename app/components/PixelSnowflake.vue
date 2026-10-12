<script setup lang="ts">
// Kleine Pixel-Schneeflocke (9 × 9) neben dem Logo im Design „Winternacht“.
</script>

<template>
  <svg class="snowflake" viewBox="0 0 9 9" shape-rendering="crispEdges" aria-hidden="true">
    <path
      fill="currentColor"
      d="M4 0h1v9H4zM0 4h9v1H0zM1 1h1v1H1zM7 1h1v1H7zM1 7h1v1H1zM7 7h1v1H7zM2 2h1v1H2zM6 2h1v1H6zM2 6h1v1H2zM6 6h1v1H6zM3 3h3v3H3z"
    />
    <path class="glint" fill="#fff" d="M4 4h1v1H4zM4 0h1v1H4zM0 4h1v1H0zM8 4h1v1H8zM4 8h1v1H4z" />
  </svg>
</template>

<style scoped>
.snowflake {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
  pointer-events: none;
  color: var(--color-redstone-300);
  filter: drop-shadow(0 0 3px color-mix(in srgb, var(--color-redstone-400) 70%, transparent));
}
/* Die Spitzen glitzern ab und zu kurz auf. */
.glint {
  opacity: 0.55;
  animation: snowflake-glint 5s steps(1) infinite;
}
@keyframes snowflake-glint {
  0%,
  88% {
    opacity: 0.55;
  }
  90%,
  96% {
    opacity: 1;
  }
}
:root[data-reduced-motion] .glint {
  animation: none;
}
</style>
