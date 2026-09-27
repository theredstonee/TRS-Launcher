import { circuitTexts, type CircuitTexts } from '~/utils/circuit-i18n'

/** Texte der Schaltungs-Bibliothek (öffentliche Seiten + Team-Bereich), EN/DE/ES. */
export function useCircuitText() {
  const { lang, fill, date } = useLang()
  const c = computed<CircuitTexts>(() => circuitTexts[lang.value])
  return { c, lang, fill, date }
}
