package dev.theredstonee.trsclient.core.pvp;

import java.util.Locale;

/**
 * Geschwindigkeit in Blöcken pro Sekunde, gemittelt über die letzten Ticks
 * (sonst zappelt die Anzeige). Wird einmal je Client-Tick gefüttert.
 */
public final class SpeedTracker {
	/** Mittelung über 10 Ticks = eine halbe Sekunde. */
	public static final int WINDOW = 10;
	private static final double TICKS_PER_SECOND = 20.0;

	private final double[] samples = new double[WINDOW];
	private int count;
	private int next;
	private boolean hasLast;
	private double lastX;
	private double lastY;
	private double lastZ;

	/** Position dieses Ticks; {@code withY} = Auf-/Abstieg mitzählen. */
	public void tick(double x, double y, double z, boolean withY) {
		if (hasLast) {
			double dx = x - lastX;
			double dz = z - lastZ;
			double dy = withY ? y - lastY : 0;
			add(Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		lastX = x;
		lastY = y;
		lastZ = z;
		hasLast = true;
	}

	private void add(double distance) {
		samples[next] = distance;
		next = (next + 1) % WINDOW;
		if (count < WINDOW) count++;
	}

	/** Blöcke pro Sekunde. */
	public double blocksPerSecond() {
		if (count == 0) return 0;
		double sum = 0;
		for (int i = 0; i < count; i++) sum += samples[i];
		return sum / count * TICKS_PER_SECOND;
	}

	/** Neue Welt/Teleport: Messung verwerfen. */
	public void reset() {
		count = 0;
		next = 0;
		hasLast = false;
	}

	/** "4.32 b/s". */
	public static String format(double blocksPerSecond) {
		return String.format(Locale.ROOT, "%.2f b/s", blocksPerSecond);
	}
}
