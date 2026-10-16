// Kleiner Ereignis-Kanal der Mondnacht: die Szene meldet Blitze, die Atmosphäre-Schicht
// reagiert (Aufflackern des Launchers, blutige Handabdrücke). Ohne Vue, damit testbar.

type Listener = () => void
const bolts = new Set<Listener>()

export function onBolt(fn: Listener): () => void {
  bolts.add(fn)
  return () => bolts.delete(fn)
}

export function emitBolt(): void {
  for (const fn of [...bolts]) fn()
}

/** Wahrscheinlichkeit für einen Handabdruck nach einem Blitz (Entwurf: 45 %). */
export const HANDPRINT_CHANCE = 0.45

/** Flüster-Texte (Entwurf, je Sprache). Andere Sprachen fallen auf Englisch zurück. */
export const WHISPERS: Record<'en' | 'de' | 'es', readonly string[]> = {
  de: ['… dreh dich nicht um', 'ich sehe dich', 'dreizehn …', 'du bist nicht allein', 'hörst du das?', 'komm näher …', 'es ist hinter dem Mond'],
  en: ['… don’t turn around', 'i see you', 'thirteen …', 'you are not alone', 'do you hear that?', 'come closer …', 'it is behind the moon'],
  es: ['… no te des la vuelta', 'te veo', 'trece …', 'no estás solo', '¿oyes eso?', 'acércate …', 'está detrás de la luna'],
}

export function whispersFor(locale: string): readonly string[] {
  const base = locale.toLowerCase().split('-')[0]
  return base === 'de' || base === 'es' ? WHISPERS[base] : WHISPERS.en
}

/** Zufällige Wartezeit `[min, min+span)` in ms – eigener Zufall für Tests. */
export const gap = (min: number, span: number, rand: () => number = Math.random) => min + rand() * span
