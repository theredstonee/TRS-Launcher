package dev.theredstonee.trsclient.core.ui;

/**
 * Weiche Animationen ohne Zeitgeber: ein Wert läuft je Frame ein Stück auf sein Ziel zu.
 * Die Geschwindigkeit hängt an der echten Zeit, damit sie bei 30 und 300 FPS gleich wirkt.
 */
public final class Anim {
	/** Wert, der seinem Ziel exponentiell folgt. */
	public static final class Smooth {
		private float value;
		private float target;
		/** Zeit in Sekunden, bis etwa 63 % der Strecke zurückgelegt sind. */
		private final float tau;

		public Smooth(float value, float tau) {
			this.value = value;
			this.target = value;
			this.tau = Math.max(0.001f, tau);
		}

		public float get() {
			return value;
		}

		public float target() {
			return target;
		}

		public void setTarget(float target) {
			this.target = target;
		}

		/** Setzt Wert und Ziel sofort. */
		public void snap(float value) {
			this.value = value;
			this.target = value;
		}

		/** Einen Frame weiterrechnen ({@code dt} in Sekunden). */
		public float update(float dt) {
			value = approach(value, target, dt, tau);
			return value;
		}

		public boolean done() {
			return Math.abs(target - value) < 0.002f;
		}
	}

	private Anim() {
	}

	/** Exponentielle Annäherung, bildrate-unabhängig. */
	public static float approach(float value, float target, float dt, float tau) {
		if (dt <= 0) return value;
		float f = (float) (1 - Math.exp(-Math.min(dt, 0.25f) / Math.max(0.001f, tau)));
		float next = value + (target - value) * f;
		return Math.abs(target - next) < 0.001f ? target : next;
	}

	/** Weiches Ein-/Ausblenden (cubic ease-out). */
	public static float easeOut(float t) {
		float x = ColorMath.clamp01(t);
		float inv = 1 - x;
		return 1 - inv * inv * inv;
	}

	/** Weiches Starten und Enden. */
	public static float easeInOut(float t) {
		float x = ColorMath.clamp01(t);
		return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * ColorMath.clamp01(t);
	}
}
