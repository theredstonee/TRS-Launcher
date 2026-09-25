/**
 * Sollen Animationen gerade laufen? Richtet sich nach der Launcher-Einstellung
 * „Animationen“ (Standard: immer) und – nur bei „Wie System“ – nach
 * „Bewegung reduzieren“ von Windows/Linux. Siehe `utils/motion.ts`.
 */
export function useMotion() {
  const motion = sharedMotion()
  return { reduced: motion.reduced, setting: motion.setting }
}
