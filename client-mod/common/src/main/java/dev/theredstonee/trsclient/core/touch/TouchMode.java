package dev.theredstonee.trsclient.core.touch;

/**
 * Touch-Modus der mobilen Spiel-Engine (Android/iOS): an nur mit {@code -Dtrs.touch=true}, das setzt die
 * Engine des TRS Launchers. Auf dem Desktop ist die Eigenschaft nie gesetzt – jeder Touch-Pfad fragt zuerst
 * {@link #enabled()} und tut sonst nichts.
 *
 * <p>Eigenschaften (alle optional außer {@code trs.touch}):
 * <ul>
 *   <li>{@code trs.touch=true} – Touch-Modus an</li>
 *   <li>{@code trs.touchScale=1.5} – Vergrößerung der TRS-Menüs (1–3, Standard {@link #DEFAULT_SCALE})</li>
 *   <li>{@code trs.safeInsets=l,t,r,b} – sichere Ränder (Notch, Kamera-Loch) in Fensterpixeln</li>
 * </ul>
 * Siehe {@code docs/touch-mode.md}.
 */
public final class TouchMode {
	public static final String PROPERTY = "trs.touch";
	public static final String SCALE_PROPERTY = "trs.touchScale";
	public static final String INSETS_PROPERTY = "trs.safeInsets";

	/** Standard-Vergrößerung der TRS-Menüs im Touch-Modus. */
	public static final float DEFAULT_SCALE = 1.5f;
	public static final float MIN_SCALE = 1f;
	public static final float MAX_SCALE = 3f;
	/** Kleinste virtuelle Fläche, die ein vergrößertes Menü noch braucht (GUI-Pixel). */
	public static final int MIN_VIRTUAL_W = 320;
	public static final int MIN_VIRTUAL_H = 200;
	/** Vergrößerung in Achtel-Schritten (weniger ungleichmäßige Pixel als beliebige Faktoren). */
	private static final float SCALE_STEP = 0.125f;

	/** Gelesener Zustand (null = noch nicht gelesen). */
	private static volatile Boolean enabled;
	private static volatile Float requested;
	private static volatile SafeArea.Insets insets;
	/** GUI-Skalierung (Fensterpixel je GUI-Pixel), vom Spiel je Tick gemeldet. */
	private static volatile double guiScale = 2.0;

	private TouchMode() {
	}

	/** Ist der Touch-Modus an? (einmal gelesen, danach zwischengespeichert) */
	public static boolean enabled() {
		Boolean e = enabled;
		if (e == null) {
			e = Boolean.valueOf("true".equalsIgnoreCase(property(PROPERTY)));
			enabled = e;
		}
		return e.booleanValue();
	}

	/** Gewünschte Vergrößerung aus {@code trs.touchScale} (begrenzt auf 1–3). */
	public static float requestedScale() {
		Float r = requested;
		if (r == null) {
			r = Float.valueOf(parseScale(property(SCALE_PROPERTY)));
			requested = r;
		}
		return r.floatValue();
	}

	/** Sichere Ränder in Fensterpixeln (nie null; ohne Angabe 0,0,0,0). */
	public static SafeArea.Insets insetsPx() {
		SafeArea.Insets i = insets;
		if (i == null) {
			i = SafeArea.parse(property(INSETS_PROPERTY));
			insets = i;
		}
		return i;
	}

	/** Sichere Ränder in GUI-Pixeln (aufgerundet); außerhalb des Touch-Modus immer 0. */
	public static SafeArea.Insets insetsGui() {
		if (!enabled()) return SafeArea.Insets.NONE;
		return insetsPx().toGui(guiScale);
	}

	/** Vom Spiel je Tick: aktuelle GUI-Skalierung (für die Umrechnung der sicheren Ränder). */
	public static void setGuiScale(double scale) {
		if (scale > 0 && !Double.isNaN(scale) && !Double.isInfinite(scale)) guiScale = scale;
	}

	public static double guiScale() {
		return guiScale;
	}

	/** Vergrößerung der TRS-Menüs für diese Bildschirmgröße; außerhalb des Touch-Modus immer 1. */
	public static float uiScale(int width, int height) {
		if (!enabled()) return 1f;
		return effectiveScale(requestedScale(), width, height);
	}

	/**
	 * Vergrößerung, die auf den Bildschirm passt: höchstens {@code requested}, aber so, dass mindestens
	 * {@link #MIN_VIRTUAL_W}×{@link #MIN_VIRTUAL_H} GUI-Pixel Platz bleiben; nie kleiner als 1, in Achtel-Schritten
	 * (abgerundet).
	 */
	public static float effectiveScale(float requested, int width, int height) {
		if (width <= 0 || height <= 0 || Float.isNaN(requested)) return 1f;
		float s = Math.max(MIN_SCALE, Math.min(MAX_SCALE, requested));
		s = Math.min(s, Math.min(width / (float) MIN_VIRTUAL_W, height / (float) MIN_VIRTUAL_H));
		s = (float) Math.floor(s / SCALE_STEP + 1e-4) * SCALE_STEP;
		return Math.max(MIN_SCALE, s);
	}

	/** {@code trs.touchScale} lesen; ungültig/fehlend → {@link #DEFAULT_SCALE}. */
	static float parseScale(String value) {
		if (value == null) return DEFAULT_SCALE;
		try {
			float f = Float.parseFloat(value.trim());
			if (Float.isNaN(f) || Float.isInfinite(f)) return DEFAULT_SCALE;
			return Math.max(MIN_SCALE, Math.min(MAX_SCALE, f));
		} catch (NumberFormatException e) {
			return DEFAULT_SCALE;
		}
	}

	private static String property(String key) {
		try {
			return System.getProperty(key);
		} catch (SecurityException e) {
			return null;
		}
	}

	/** Nur für Tests: Zustand neu lesen bzw. fest setzen (null = aus den Eigenschaften lesen). */
	public static void resetForTests(Boolean forceEnabled) {
		enabled = forceEnabled;
		requested = null;
		insets = null;
		guiScale = 2.0;
	}
}
