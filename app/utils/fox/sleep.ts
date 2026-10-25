import { noise } from '../redstone/paint'
import type { SleepView } from './sprites'

// Atmen des schlafenden Fuchses (rein, testbar): ein ruhiger Atemzug alle 3,4 s –
// einatmen 1,4 s, ausatmen länger. Ab und zu zuckt ein Ohr kurz. Ruhig (reduzierte
// Bewegung): ausgeatmet, kein Zucken.

export const BREATH_PERIOD = 3.4
export const INHALE = 1.4

export function foxBreath(seconds: number, calm = false): SleepView {
  if (calm) return { inhale: false, twitch: false }
  const phase = ((seconds % BREATH_PERIOD) + BREATH_PERIOD) % BREATH_PERIOD
  // Etwa alle 20 Sekunden ein kurzes Ohrzucken (0,3 s).
  const slot = Math.floor(seconds / 20)
  const at = 4 + noise(slot, 3, 97) * 12
  const local = seconds - slot * 20
  return { inhale: phase < INHALE, twitch: local >= at && local < at + 0.3 }
}
