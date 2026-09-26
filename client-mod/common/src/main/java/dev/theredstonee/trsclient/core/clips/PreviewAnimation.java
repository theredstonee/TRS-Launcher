package dev.theredstonee.trsclient.core.clips;

/**
 * Abspiel-Logik der Clip-Vorschau im Spiel: ein Raster aus kleinen Bildern (etwa eins pro Sekunde Clip), das als
 * Zeitraffer läuft – ohne Ton, ohne Video-Dekoder. Reine Logik (Render-Thread), getestet in {@code ClipPreviewTest}.
 */
public final class PreviewAnimation {
	/** Schnellster/langsamster Bildwechsel (ms). */
	static final int MIN_STEP_MS = 90;
	static final int MAX_STEP_MS = 250;
	/** Größere Sprünge (Spiel hing, Fenster im Hintergrund) nicht nachholen. */
	static final long MAX_DT_MS = 1_000L;

	private int frames;
	private int intervalMs;
	private int index;
	private boolean playing = true;
	private long acc;

	/** Neuer Clip: vorn beginnen und abspielen. */
	public void reset(int frames, int intervalMs) {
		this.frames = Math.max(0, frames);
		this.intervalMs = Math.max(1, intervalMs);
		this.index = 0;
		this.acc = 0;
		this.playing = true;
	}

	/**
	 * Wie lange ein Bild stehen bleibt: ein Viertel der Clip-Zeit zwischen zwei Bildern (Zeitraffer ×4), aber nie
	 * schneller als {@link #MIN_STEP_MS} und nie langsamer als {@link #MAX_STEP_MS}.
	 */
	public static int stepMs(int intervalMs) {
		return Math.max(MIN_STEP_MS, Math.min(MAX_STEP_MS, intervalMs / 4));
	}

	/** Zeit weiterlaufen lassen (ms seit dem letzten Aufruf). */
	public void advance(long dtMs) {
		if (!playing || frames <= 1 || dtMs <= 0) return;
		acc += Math.min(dtMs, MAX_DT_MS);
		int step = stepMs(intervalMs);
		while (acc >= step) {
			acc -= step;
			index = (index + 1) % frames;
		}
	}

	public void toggle() {
		playing = !playing;
		acc = 0;
	}

	public void setPlaying(boolean playing) {
		this.playing = playing;
		acc = 0;
	}

	/** Auf eine Stelle springen (0–1 der Leiste). */
	public void seek(float ratio) {
		if (frames <= 0) return;
		float r = Math.max(0f, Math.min(1f, ratio));
		index = Math.min(frames - 1, Math.round(r * (frames - 1)));
		acc = 0;
	}

	public int index() {
		return index;
	}

	public int frames() {
		return frames;
	}

	public boolean playing() {
		return playing;
	}

	/** Etwaige Stelle im Clip (ms), die das aktuelle Bild zeigt. */
	public long positionMs() {
		return (long) index * intervalMs;
	}

	/** Fortschritt 0–1 für die Leiste. */
	public float progress() {
		return frames <= 1 ? 0f : index / (float) (frames - 1);
	}
}
