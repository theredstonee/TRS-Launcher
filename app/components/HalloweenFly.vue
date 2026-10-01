<script setup lang="ts">
// Hexe und Fledermäuse über jedem Bildschirm, solange das Event läuft.
// Liegt unter Titelleiste, Menüs und Dialogen (z-40 aufwärts) und unter Toasts.
const canvas = useTemplateRef<HTMLCanvasElement>('canvas')
useHalloweenFly(canvas)
</script>

<template>
  <div class="atmosphere" aria-hidden="true">
    <div class="vignette" />
    <canvas ref="canvas" class="fly" />
  </div>
</template>

<style scoped>
/* Über dem Inhalt und der Seitenleiste (z-30), unter Titelleiste, Menüs,
   Assistent und Dialogen (ab z-40) sowie Toasts (ab z-60). Klicks gehen durch. */
.atmosphere {
  position: fixed;
  z-index: 35;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}
.fly {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  image-rendering: pixelated;
}
/* Nacht-Schleier nur am unteren Rand, leicht genug dass Text lesbar bleibt. */
.vignette {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 96px;
  pointer-events: none;
  background: linear-gradient(to top, rgb(32 10 52 / 0.18), rgb(107 47 168 / 0.05) 42%, transparent);
}
:global(html[data-theme="light"]) .vignette {
  background: linear-gradient(to top, rgb(107 47 168 / 0.07), transparent 70%);
}
</style>
