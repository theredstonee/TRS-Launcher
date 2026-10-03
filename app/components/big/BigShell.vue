<script setup lang="ts">
// Rahmen des Big-Picture-Modus (statt Titelleiste und Seitenleiste; das Fenster ist
// im Vollbild): Redstone-Schaltung abgedunkelt im Hintergrund. Die Dialoge (Spielstart,
// Beitreten, Absturz-Helfer, Hinweise) kommen weiter aus dem Standard-Layout.
const settings = useSettingsStore()
const animated = computed(() => settings.current?.ui.animatedBackground !== false)
</script>

<template>
  <div class="big-root relative h-full min-h-0 flex-1 overflow-hidden bg-base-950">
    <div v-if="animated" class="big-scene" aria-hidden="true">
      <RedstoneScene fill />
    </div>
    <div class="big-shade" aria-hidden="true" />
    <slot />
  </div>
</template>

<style scoped>
.big-scene {
  position: absolute;
  inset: 0;
  opacity: 0.35;
  pointer-events: none;
}
/* Abdunkeln, damit große Schrift auf der Schaltung lesbar bleibt. */
.big-shade {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(120% 80% at 20% 0%, color-mix(in srgb, var(--color-redstone-900) 55%, transparent), transparent 60%),
    linear-gradient(180deg, color-mix(in srgb, var(--color-base-950) 55%, transparent), var(--color-base-950) 85%);
}
</style>

<style>
/* Gemeinsame Bausteine der Big-Picture-Bereiche (nicht scoped, damit alle Teile sie nutzen). */
.bp-page :focus {
  outline: none;
}
/* Fokus deutlich sichtbar: Lampen-Rahmen mit Leuchten – mit Controller/Tastatur immer, mit Maus nur bei Tab. */
.bp-page[data-input='nav'] :focus:not(.pixel-corners),
.bp-page :focus-visible:not(.pixel-corners) {
  outline: 4px solid var(--color-lamp-300);
  outline-offset: 4px;
  box-shadow:
    0 0 0 9px color-mix(in srgb, var(--color-lamp-400) 22%, transparent),
    0 0 36px color-mix(in srgb, var(--color-lamp-400) 45%, transparent);
}
/* Pixel-Ecken (clip-path) schneiden alles außen ab: Rahmen nach innen. */
.bp-page[data-input='nav'] .pixel-corners:focus,
.bp-page .pixel-corners:focus-visible {
  outline: 4px solid var(--color-base-50);
  outline-offset: -12px;
}

/* In der Ebene „components“, damit Tailwind-Klassen sie im Einzelfall überschreiben. */
@layer components {
.bp-page :is(button, a, input, [tabindex]) {
  scroll-margin: 7rem 3rem 6rem;
}
.bp-heading {
  font-family: var(--font-display);
  font-size: 2.25rem;
  line-height: 1.1;
  color: var(--color-base-50);
}
.bp-tile {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
  border: 2px solid var(--color-base-800);
  border-radius: 1rem;
  background: color-mix(in srgb, var(--color-base-850) 90%, transparent);
  text-align: left;
  transition:
    transform 0.18s cubic-bezier(0.2, 0.8, 0.2, 1),
    border-color 0.18s ease,
    background-color 0.18s ease;
}
.bp-page[data-input='nav'] .bp-tile:focus,
.bp-page[data-input='pointer'] .bp-tile:hover {
  transform: scale(1.035);
  border-color: var(--color-redstone-500);
  z-index: 1;
}
.bp-btn {
  display: inline-flex;
  min-height: 4rem;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  padding: 0 1.75rem;
  border-radius: 0.9rem;
  background: var(--color-base-800);
  color: var(--color-base-50);
  font-size: 1.25rem;
  font-weight: 600;
  transition:
    background-color 0.15s ease,
    transform 0.15s ease;
}
.bp-btn:hover {
  background: var(--color-base-700);
}
.bp-btn:active {
  transform: translateY(1px);
}
.bp-btn-primary {
  background: var(--color-redstone-500);
  color: #fff;
}
.bp-btn-primary:hover {
  background: var(--color-redstone-600);
}
.bp-btn[aria-disabled='true'] {
  opacity: 0.45;
}
.bp-empty {
  max-width: 40rem;
  padding: 3rem 0;
  font-size: 1.25rem;
  color: var(--color-base-400);
}
}
</style>
