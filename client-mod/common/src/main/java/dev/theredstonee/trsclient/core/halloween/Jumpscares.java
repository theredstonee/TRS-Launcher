package dev.theredstonee.trsclient.core.halloween;

/**
 * Jumpscares zu Halloween (Vertrag „Client-only“): „Hinter dir!“, Kiste im Dunkeln, tiefe Höhle, Garderoben-Spiegel,
 * Ladebildschirm bei 99 %. Schutzregeln: nie 10 s nach Schaden (genommen/ausgeteilt), nie unter 6 Herzen, höchstens
 * einer je 20 Minuten, nie in den ersten 2 Minuten. Ton nur mit „Grusel-Töne“.
 */
public final class Jumpscares {
	private static final Jumpscares INSTANCE = new Jumpscares();

	/** Was der Ladebildschirm gerade zeigen soll. */
	public static final class LoadingView {
		/** Anzuzeigender Fortschritt (bleibt beim Schreck bei 0,99 stehen). */
		public final float progress;
		/** Abdunkeln „Licht aus“ 0..1. */
		public final float dark;
		/** Augen öffnen sich 0..1. */
		public final float eyes;

		public LoadingView(float progress, float dark, float eyes) {
			this.progress = progress;
			this.dark = dark;
			this.eyes = eyes;
		}
	}

	private Jumpscares() {
	}

	public static Jumpscares get() {
		return INSTANCE;
	}

	/**
	 * Ladebildschirm (Mondlauf): {@code progress} wie vom Spiel (−1 = unbestimmt). Liefert, was gezeichnet werden soll.
	 */
	public LoadingView loading(float progress, long nowMs) {
		return new LoadingView(progress, 0f, 0f);
	}

	/** Garderoben-Spiegel: jetzt das Grinsen über das Gesicht der Vorschau zeichnen? (jedes Bild fragen) */
	public boolean mirrorGrin(long nowMs) {
		return false;
	}
}
