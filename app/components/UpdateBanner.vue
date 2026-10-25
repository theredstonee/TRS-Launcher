<script setup lang="ts">
// Banner eines Updates – feste Vorlage für Launcher und Website (gleiche Datei in beiden Apps):
// Deepslate-Grund, leuchtende Redstone-Leitung oben und unten, Pixel-Schrift mit Version darüber.
// Je Update ändern sich nur die Akzentfarbe (Glühen, Unterstrich) und das Motiv rechts
// (HD-Pixel-Art aus TRS Studio, Vorlage „Update-Banner“; Angabe im CHANGELOG.md).
const props = withDefaults(
  defineProps<{
    /** z. B. „v0.4.3 · 24. September 2026“ */
    kicker: string
    title: string
    accent?: string | null
    /** Bildadresse des Motivs, sonst nur Rahmen und Glühen. */
    motif?: string | null
    size?: 'sm' | 'md' | 'lg'
    tag?: 'h1' | 'h2' | 'h3' | 'p'
    /** Ohne Schrift (wenn der Titel schon daneben steht). */
    bare?: boolean
  }>(),
  { accent: null, motif: null, size: 'md', tag: 'h3', bare: false },
)

const style = computed(() => ({ '--accent': props.accent && /^#[0-9a-f]{6}$/i.test(props.accent) ? props.accent : '#ff5a4d' }))
</script>

<template>
  <div class="update-banner" :class="`ub-${size}`" :style="style">
    <div class="ub-glow" aria-hidden="true" />
    <span class="ub-wire ub-wire-top" aria-hidden="true" />
    <span class="ub-wire ub-wire-bottom" aria-hidden="true" />
    <img v-if="motif" :src="motif" alt="" class="ub-motif" loading="lazy" decoding="async" />
    <div v-if="!bare" class="ub-text">
      <p class="ub-kicker">{{ kicker }}</p>
      <component :is="tag" class="ub-title">{{ title }}</component>
      <span class="ub-bar" aria-hidden="true" />
    </div>
    <slot />
  </div>
</template>

<style scoped>
.update-banner {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  height: 100%;
  min-height: 8rem;
  background-color: #111116;
  background-image: var(--deepslate);
  background-size: 48px 48px;
}
/* Leuchten in der Farbe des Updates hinter dem Motiv, links dunkel für die Schrift. */
.ub-glow {
  position: absolute;
  inset: 0;
  z-index: -1;
  background:
    radial-gradient(60% 90% at 78% 50%, color-mix(in srgb, var(--accent) 38%, transparent), transparent 70%),
    linear-gradient(90deg, rgb(12 11 14 / 0.92) 0%, rgb(12 11 14 / 0.55) 45%, rgb(12 11 14 / 0.1) 75%);
}
/* Redstone-Leitung: dunkle Spur, glühende Staubpunkte, Knoten an den Enden. */
.ub-wire {
  position: absolute;
  left: 0;
  right: 0;
  height: 6px;
  background:
    radial-gradient(circle, #ff5a4d 0 1.5px, transparent 2.5px) 0 50% / 22px 6px repeat-x,
    linear-gradient(#b31a12, #b31a12) 0 50% / 100% 2px no-repeat;
  filter: drop-shadow(0 0 5px rgb(255 60 40 / 0.75));
}
.ub-wire::before,
.ub-wire::after {
  content: '';
  position: absolute;
  top: -2px;
  width: 10px;
  height: 10px;
  background: #ff5a4d;
  box-shadow: 0 0 10px #ff5a4d;
}
.ub-wire::before {
  left: 14px;
}
.ub-wire::after {
  right: 14px;
  background: var(--accent);
  box-shadow: 0 0 12px var(--accent);
}
.ub-wire-top {
  top: 8px;
}
.ub-wire-bottom {
  bottom: 8px;
}
/*
 * Design „Winternacht“ (nur im Launcher, `data-design` auf <html>): Nachtblau und
 * Frostlinien mit Eiskristallen statt der Redstone-Leitung. Motiv und Schrift bleiben.
 */
:root[data-design="winter"] .update-banner {
  background-color: #0d1424;
  background-image: var(--winter-tile, var(--deepslate));
}
:root[data-design="winter"] .ub-glow {
  background:
    radial-gradient(60% 90% at 78% 50%, color-mix(in srgb, var(--accent) 38%, transparent), transparent 70%),
    linear-gradient(90deg, rgb(8 13 25 / 0.92) 0%, rgb(8 13 25 / 0.55) 45%, rgb(8 13 25 / 0.1) 75%);
}
:root[data-design="winter"] .ub-wire {
  background: url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' width='22' height='6' shape-rendering='crispEdges'><path d='M0 2h22v2H0z' fill='%233f8fc4'/><path d='M0 2h22v1H0z' fill='%23b8e7ff'/><path d='M10 0h2v1h-2zM9 1h4v1H9zM8 2h6v2H8zM9 4h4v1H9zM10 5h2v1h-2z' fill='%23dff4ff'/><path d='M10 2h2v2h-2z' fill='%23fff'/></svg>") 0 50% / 22px 6px repeat-x;
  filter: drop-shadow(0 0 5px rgb(127 211 255 / 0.7));
}
:root[data-design="winter"] .ub-wire::before,
:root[data-design="winter"] .ub-wire::after {
  top: -2px;
  rotate: 45deg;
  background: #eaf6ff;
  box-shadow:
    inset 0 0 0 2px #7fd3ff,
    0 0 10px #7fd3ff;
}
:root[data-design="winter"] .ub-wire::after {
  background: color-mix(in srgb, var(--accent) 55%, #eaf6ff);
  box-shadow:
    inset 0 0 0 2px var(--accent),
    0 0 12px var(--accent);
}
/*
 * Design „Fuchsbau“: warme Dämmerung und eine Ranke mit Herbstblättern und Süßbeeren statt
 * der Leitung, an den Enden glimmen kleine Laternen. Motiv und Schrift bleiben.
 */
:root[data-design="fox"] .update-banner {
  background-color: #1a1119;
  background-image: var(--fox-tile, var(--deepslate));
}
:root[data-design="fox"] .ub-glow {
  background:
    radial-gradient(60% 90% at 78% 50%, color-mix(in srgb, var(--accent) 38%, transparent), transparent 70%),
    linear-gradient(90deg, rgb(24 14 22 / 0.92) 0%, rgb(24 14 22 / 0.55) 45%, rgb(24 14 22 / 0.1) 75%);
}
:root[data-design="fox"] .ub-wire {
  background: url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' width='24' height='6' shape-rendering='crispEdges'><path d='M0 3h24v1H0z' fill='%234e3420'/><path d='M0 2h24v1H0z' fill='%236b4a2e'/><path d='M6 0h2v1H6zM5 1h4v1H5zM7 2h1v1H7z' fill='%23e8702a'/><path d='M6 1h1v1H6z' fill='%23ffb878'/><path d='M16 4h2v1h-2zM17 5h2v1h-2z' fill='%23c8102e'/><path d='M16 4h1v1h-1z' fill='%23ff5a6a'/><path d='M12 4h1v1h-1zM11 5h1v1h-1z' fill='%233f8a4a'/></svg>") 0 50% / 24px 6px repeat-x;
  filter: drop-shadow(0 0 4px rgb(255 154 82 / 0.45));
}
:root[data-design="fox"] .ub-wire::before,
:root[data-design="fox"] .ub-wire::after {
  top: -2px;
  background: #ffc362;
  box-shadow:
    inset 0 0 0 2px #2a1a16,
    0 0 10px #ff9a52;
}
:root[data-design="fox"] .ub-wire::after {
  background: color-mix(in srgb, var(--accent) 55%, #ffe1a6);
  box-shadow:
    inset 0 0 0 2px #2a1a16,
    0 0 12px var(--accent);
}
.ub-motif {
  position: absolute;
  right: 4%;
  top: 50%;
  height: 100%;
  aspect-ratio: 1;
  translate: 0 -50%;
  object-fit: contain;
  image-rendering: pixelated;
  filter: drop-shadow(0 6px 0 rgb(0 0 0 / 0.45)) drop-shadow(0 0 22px color-mix(in srgb, var(--accent) 45%, transparent));
  animation: ub-float 5s ease-in-out infinite;
}
@keyframes ub-float {
  50% {
    transform: translateY(-4px);
  }
}
:root[data-reduced-motion] .ub-motif {
  animation: none;
}
.ub-text {
  position: absolute;
  left: 1.5rem;
  right: 42%;
  bottom: 1.4rem;
}
.ub-kicker {
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: #ffd48a;
}
.ub-title {
  margin-top: 0.3rem;
  font-family: var(--font-display);
  line-height: 1.05;
  color: #f3f3f8;
  text-wrap: balance;
  text-shadow: 0 2px 0 rgb(12 11 14 / 0.8);
}
.ub-bar {
  display: block;
  margin-top: 0.6rem;
  width: 3.5rem;
  height: 4px;
  background: var(--accent);
  box-shadow: 0 0 12px var(--accent);
}
.ub-sm .ub-title {
  font-size: 1.5rem;
}
.ub-sm .ub-text {
  left: 1.1rem;
  bottom: 1.15rem;
  right: 44%;
}
.ub-sm .ub-motif {
  height: 74%;
  right: 3%;
}
.ub-sm .ub-kicker {
  font-size: 0.64rem;
  letter-spacing: 0.12em;
}
.ub-md .ub-title {
  font-size: 2.25rem;
}
.ub-lg .ub-title {
  font-size: clamp(2.5rem, 5vw, 4rem);
}
.ub-lg .ub-text {
  left: clamp(1.5rem, 6vw, 4rem);
  bottom: 2.2rem;
}
@media (max-width: 640px) {
  .ub-motif {
    right: -6%;
    opacity: 0.55;
  }
  .ub-text {
    right: 1.25rem;
  }
}
</style>
