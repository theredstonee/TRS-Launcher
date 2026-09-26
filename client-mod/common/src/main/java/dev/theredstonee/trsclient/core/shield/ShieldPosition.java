package dev.theredstonee.trsclient.core.shield;

import dev.theredstonee.trsclient.core.module.BoolSetting;
import dev.theredstonee.trsclient.core.module.ChoiceSetting;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.NumberSetting;

/**
 * Modul „Schild-Position“: rechnet für jede Hand die Haltung des Schilds in der 1. Person aus (Vorlagen, eigene Regler,
 * weicher Übergang ins Blocken, Deckkraft beim Blocken). Kennt Minecraft nicht – die Loader rufen je Bild
 * {@link #transform} auf und wenden das Ergebnis im Arm-Raum an, direkt bevor das Schild gezeichnet wird.
 *
 * <p><b>Blocken ohne Sprung:</b> Vanilla wechselt beim Blocken schlagartig auf ein zweites Modell
 * („shield_blocking“) mit anderer Anzeige-Transformation. Solange das Modul an ist, zeichnen die Loader immer das
 * normale Modell (Kopie des Stapels, siehe Loader) und diese Klasse fährt den Unterschied beider Modelle
 * ({@link #vanillaDelta}) selbst weich an. So ist auch „Vanilla“ (alle Regler neutral) genau die Vanilla-Haltung,
 * nur ohne Sprung.
 */
public final class ShieldPosition {
	/** Reglerbereiche (Verschiebung in Blöcken, Drehung in Grad, Größe in Prozent). */
	public static final double POS_MIN = -1, POS_MAX = 1, POS_STEP = 0.01;
	public static final double ROT_MIN = -180, ROT_MAX = 180, ROT_STEP = 1;
	public static final double SCALE_MIN = 30, SCALE_MAX = 150, SCALE_STEP = 5;
	public static final double OPACITY_MIN = 10, OPACITY_MAX = 90, OPACITY_STEP = 5, OPACITY_DEFAULT = 50;

	/** Hände: 0 = Haupthand, 1 = Nebenhand. */
	public static final int MAIN_HAND = 0;
	public static final int OFF_HAND = 1;

	// --- Vanillas Anzeige-Transformationen des Schilds (models/item/shield.json + shield_blocking.json) ---
	// Rechte Hand in 1.9 – 1.21.11: normal Y 2, blockend Y 5; ab 26.1 1.75 bzw. 3.25 (siehe setVanillaRightY).
	private static volatile double rightNormalY = 2;
	private static volatile double rightBlockY = 5;
	private static volatile double[][] deltaCache;

	private final Module module;
	private final ChoiceSetting<ShieldPreset> preset;
	private final NumberSetting[] normal;
	private final NumberSetting[] blocking;
	private final BoolSetting transparent;
	private final NumberSetting opacity;
	private final ShieldAnimator[] animators = {new ShieldAnimator(), new ShieldAnimator()};

	/** Vorlage beim letzten {@link #sync()} (null = noch nie abgeglichen). */
	private ShieldPreset lastPreset;
	/** Reglerwerte von „Eigene“, bevor auf eine Vorlage gewechselt wurde (normal + blocken, je 7 Werte). */
	private double[] customBackup;

	public ShieldPosition(Module module, ChoiceSetting<ShieldPreset> preset, NumberSetting[] normal, NumberSetting[] blocking,
			BoolSetting transparent, NumberSetting opacity) {
		if (normal.length != 7 || blocking.length != 7) throw new IllegalArgumentException("7 Regler je Haltung");
		this.module = module;
		this.preset = preset;
		this.normal = normal;
		this.blocking = blocking;
		this.transparent = transparent;
		this.opacity = opacity;
	}

	/**
	 * Minecraft 26.1+ hat die rechte Hand im Schildmodell leicht verschoben (Y 1,75 statt 2 bzw. 3,25 statt 5 Pixel);
	 * die Loader dieser Versionen setzen das einmal beim Start.
	 */
	public static void setVanillaRightY(double normalY, double blockY) {
		rightNormalY = normalY;
		rightBlockY = blockY;
		deltaCache = null;
	}

	/** Vanillas Anzeige-Transformation des Schilds in der 1. Person (normal oder blockend). */
	public static double[] vanillaDisplay(boolean leftArm, boolean blockingModel) {
		if (blockingModel) {
			return leftArm ? ShieldMath.display(5, 5, -11, 0, 180, -5, 1.25, true)
					: ShieldMath.display(-15, rightBlockY, -11, 0, 180, -5, 1.25, false);
		}
		return leftArm ? ShieldMath.display(10, 0, -10, 0, 180, 5, 1.25, true)
				: ShieldMath.display(-10, rightNormalY, -10, 0, 180, 5, 1.25, false);
	}

	/**
	 * Unterschied zwischen Vanillas blockendem und normalem Modell im Arm-Raum: {@code D·V_normal = V_blockend}.
	 */
	public static double[] vanillaDelta(boolean leftArm) {
		double[][] cache = deltaCache;
		if (cache == null) {
			cache = new double[2][];
			for (int side = 0; side < 2; side++) {
				boolean left = side == 1;
				cache[side] = ShieldMath.mul(vanillaDisplay(left, true), ShieldMath.invertSimilarity(vanillaDisplay(left, false)));
			}
			deltaCache = cache;
		}
		return cache[leftArm ? 1 : 0].clone();
	}

	/** Gesamter Versatz einer Haltung im Arm-Raum (beim Blocken inkl. Vanillas Block-Bewegung). */
	public static double[] offset(ShieldPose pose, boolean leftArm, boolean blockingPose) {
		double[] m = ShieldMath.pose(pose, leftArm);
		return blockingPose ? ShieldMath.mul(m, vanillaDelta(leftArm)) : m;
	}

	/**
	 * Die eigentliche Rechnung: Überblendung {@code blend} (0 = normal, 1 = blocken) zwischen beiden Haltungen.
	 */
	public static ShieldTransform compute(ShieldPose normalPose, ShieldPose blockingPose, boolean leftArm, double blend, ShieldTransform out) {
		ShieldMath.Decomposed a = ShieldMath.decompose(offset(normalPose, leftArm, false));
		ShieldMath.Decomposed b = ShieldMath.decompose(offset(blockingPose, leftArm, true));
		ShieldMath.interpolate(a, b, blend, out);
		return out;
	}

	public Module module() {
		return module;
	}

	/** Modul an? (Sonst ändern die Loader nichts – nicht einmal das Blockier-Modell.) */
	public boolean active() {
		return module.isEnabled();
	}

	/** Haltung ohne Blocken laut Reglern. */
	public ShieldPose normalPose() {
		return ShieldPose.fromSettings(read(normal));
	}

	/** Haltung beim Blocken laut Reglern. */
	public ShieldPose blockingPose() {
		return ShieldPose.fromSettings(read(blocking));
	}

	/**
	 * Haltung für ein Bild. Rechnet die Überblendung der Hand fort (einmal je Hand und Bild aufrufen).
	 *
	 * @param hand     {@link #MAIN_HAND} oder {@link #OFF_HAND}
	 * @param leftArm  das Schild ist in der linken Hand (Bildschirmseite)
	 * @param blocking diese Hand blockt gerade
	 * @param now      System.nanoTime()
	 */
	public ShieldTransform transform(int hand, boolean leftArm, boolean blocking, long now, ShieldTransform out) {
		sync();
		float blend = animators[hand & 1].update(blocking, now);
		return compute(normalPose(), blockingPose(), leftArm, blend, out);
	}

	/** Aktuelle Überblendung einer Hand (0 = normal, 1 = blocken), ohne fortzuschreiben. */
	public float blend(int hand) {
		return animators[hand & 1].value();
	}

	/**
	 * Deckkraft des Schilds dieser Hand (1 = normal deckend). Nur mit „Beim Blocken durchsichtig“ kleiner als 1 –
	 * und nur so weit, wie die Hand gerade ins Blocken übergeblendet ist.
	 */
	public float alpha(int hand) {
		if (!active() || !transparent.get()) return 1f;
		float target = (float) (opacity.get() / 100.0);
		float blend = animators[hand & 1].value();
		float a = 1f + (target - 1f) * blend;
		return a > 0.995f ? 1f : Math.max(0.05f, a);
	}

	/** „Beim Blocken durchsichtig“ eingeschaltet? */
	public boolean transparentWanted() {
		return transparent.get();
	}

	/** Eingestellte Deckkraft beim Blocken als Anteil 0..1. */
	public float opacityFraction() {
		return (float) (opacity.get() / 100.0);
	}

	/**
	 * Vorlagen und Regler abgleichen (je Bild aufgerufen, billig):
	 * <ul>
	 *   <li>Vorlage gewechselt → deren Werte in die Regler schreiben („Eigene“ bekommt ihre alten Werte zurück).</li>
	 *   <li>Regler bewegt, während eine Vorlage gewählt ist → Vorlage springt auf „Eigene“.</li>
	 * </ul>
	 * Beim ersten Aufruf gewinnt die gespeicherte Vorlage (falls nicht „Eigene“).
	 */
	public synchronized void sync() {
		ShieldPreset p = preset.get();
		if (lastPreset == null) {
			if (p.fixed()) write(p);
			lastPreset = p;
			return;
		}
		if (p != lastPreset) {
			if (lastPreset == ShieldPreset.CUSTOM) customBackup = readAll();
			if (p.fixed()) write(p);
			else if (customBackup != null) writeAll(customBackup);
			lastPreset = p;
			return;
		}
		if (p.fixed() && !(normalPose().near(p.normal()) && blockingPose().near(p.blocking()))) {
			preset.set(ShieldPreset.CUSTOM);
			lastPreset = ShieldPreset.CUSTOM;
		}
	}

	/** Nach „Zurücksetzen“ bzw. Laden der Config neu abgleichen (erster Aufruf von {@link #sync()} gewinnt die Vorlage). */
	public synchronized void resync() {
		lastPreset = null;
	}

	private void write(ShieldPreset p) {
		write(normal, p.normal().toSettings());
		write(blocking, p.blocking().toSettings());
	}

	private double[] readAll() {
		double[] n = read(normal), b = read(blocking), all = new double[14];
		System.arraycopy(n, 0, all, 0, 7);
		System.arraycopy(b, 0, all, 7, 7);
		return all;
	}

	private void writeAll(double[] all) {
		double[] n = new double[7], b = new double[7];
		System.arraycopy(all, 0, n, 0, 7);
		System.arraycopy(all, 7, b, 0, 7);
		write(normal, n);
		write(blocking, b);
	}

	private static double[] read(NumberSetting[] s) {
		double[] v = new double[s.length];
		for (int i = 0; i < s.length; i++) v[i] = s[i].get();
		return v;
	}

	private static void write(NumberSetting[] s, double[] v) {
		for (int i = 0; i < s.length; i++) s[i].set(v[i]);
	}
}
