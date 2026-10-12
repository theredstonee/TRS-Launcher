import type { Ref } from 'vue'

/**
 * Auf- und Abtritt für Dialoge/Sheets: Hintergrund-Element per Ref, der Kasten
 * darin trägt `data-motion-panel`. Am Desktop zoomt er leicht heran, am Handy
 * fährt das Sheet von unten hoch. Fokus, Esc und Zurück-Taste bleiben, wie sie sind.
 */
export function useOverlayMotion(backdrop: Ref<HTMLElement | null | undefined>, kind: () => OverlayKind) {
  onMounted(() => overlayEnter(backdrop.value ?? null, kind()))
  onBeforeUnmount(() => overlayExitGhost(backdrop.value ?? null, kind()))
}
