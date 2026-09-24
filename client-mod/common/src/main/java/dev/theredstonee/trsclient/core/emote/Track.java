package dev.theredstonee.trsclient.core.emote;

/**
 * Schlüsselbilder eines Kanals: Zeitpunkte (ms, aufsteigend) und Werte, dazwischen weich (Kosinus), linear
 * oder "zackig" ({@link Ease#SNAP}: in den ersten 30 % des Abschnitts am Ziel, dann halten – Roboter-Tanz).
 */
public final class Track {
	/** Verlauf zwischen zwei Schlüsselbildern. */
	public enum Ease {
		SMOOTH, LINEAR, SNAP
	}

	final int channel;
	final int[] times;
	final float[] values;
	final Ease ease;

	Track(int channel, int[] times, float[] values, Ease ease) {
		if (times.length == 0 || times.length != values.length) throw new IllegalArgumentException("Schlüsselbilder");
		for (int i = 1; i < times.length; i++) {
			if (times[i] <= times[i - 1]) throw new IllegalArgumentException("Zeiten müssen steigen: " + channel);
		}
		this.channel = channel;
		this.times = times;
		this.values = values;
		this.ease = ease;
	}

	public int channel() {
		return channel;
	}

	/**
	 * Wert zur Zeit {@code t} (ms). {@code cycle} > 0: Schleife dieser Länge – nach dem letzten Schlüsselbild geht es
	 * weich zum ersten zurück. Sonst gilt vor dem ersten/nach dem letzten Bild dessen Wert.
	 */
	public float sample(float t, int cycle) {
		int n = times.length;
		if (n == 1) return values[0];
		if (t <= times[0] && cycle <= 0) return values[0];
		if (t >= times[n - 1]) {
			if (cycle <= 0 || times[n - 1] >= cycle) return values[n - 1];
			// Rückweg über das Schleifenende zum ersten Bild.
			float span = cycle - times[n - 1] + times[0];
			return mix(values[n - 1], values[0], (t - times[n - 1]) / span);
		}
		if (t < times[0]) {
			// Schleife: vor dem ersten Bild = noch auf dem Rückweg vom letzten.
			float span = cycle - times[n - 1] + times[0];
			return mix(values[n - 1], values[0], (t + cycle - times[n - 1]) / span);
		}
		int i = 1;
		while (i < n - 1 && times[i] < t) i++;
		float f = (t - times[i - 1]) / (float) (times[i] - times[i - 1]);
		return mix(values[i - 1], values[i], f);
	}

	private float mix(float a, float b, float f) {
		float k = Math.max(0f, Math.min(1f, f));
		switch (ease) {
			case LINEAR:
				break;
			case SNAP:
				k = Math.min(1f, k / 0.3f);
				k = k * k * (3f - 2f * k);
				break;
			default:
				k = (1f - (float) Math.cos(k * Math.PI)) * 0.5f;
				break;
		}
		return a + (b - a) * k;
	}
}
