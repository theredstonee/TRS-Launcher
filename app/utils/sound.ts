// Kurzer Hinweiston für Benachrichtigungen – mit WebAudio erzeugt (keine Datei,
// kein Netzwerk): zwei leise Klicks wie ein Redstone-Verstärker.

let context: AudioContext | null = null
let lastPlayed = 0

/** Spielt den Ton (höchstens alle 700 ms). Fehler (kein Audio) sind egal. */
export function playNotificationSound(volume = 0.12) {
  try {
    const now = Date.now()
    if (now - lastPlayed < 700) return
    lastPlayed = now
    const Ctor = globalThis.AudioContext ?? (globalThis as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
    if (!Ctor) return
    context ??= new Ctor()
    const ctx = context
    if (ctx.state === 'suspended') void ctx.resume()
    const start = ctx.currentTime + 0.01
    for (const [offset, freq] of [
      [0, 880],
      [0.09, 1320],
    ] as const) {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'square'
      osc.frequency.value = freq
      gain.gain.setValueAtTime(0, start + offset)
      gain.gain.linearRampToValueAtTime(volume, start + offset + 0.005)
      gain.gain.exponentialRampToValueAtTime(0.0001, start + offset + 0.08)
      osc.connect(gain).connect(ctx.destination)
      osc.start(start + offset)
      osc.stop(start + offset + 0.1)
    }
  } catch {
    // Kein Ton möglich – still weiter.
  }
}
