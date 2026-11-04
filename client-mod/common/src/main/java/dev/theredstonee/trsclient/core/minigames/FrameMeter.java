package dev.theredstonee.trsclient.core.minigames;

import java.util.Arrays;
import java.util.Locale;

/**
 * Misst das Bild der Minispiele: Rechnen und Hochladen je Bild sowie den Abstand der Bilder. Daran entscheidet sich, ob ein
 * Bild je Frame ruhig genug läuft. Kostet fast nichts und bleibt deshalb auch im Spiel an; ausgelesen wird nur im Autotest.
 */
public final class FrameMeter {
	private static final int KEEP = 3600;

	private long frames;
	private long renderSum;
	private long renderMax;
	private long uploadSum;
	private long uploadMax;
	private final float[] gaps = new float[KEEP];
	private int gapCount;
	private int gapNext;
	private long lastFrame;

	/** Ein Bild ist fertig: gerechnet in {@code renderNanos}, hochgeladen in {@code uploadNanos}. */
	public void record(long renderNanos, long uploadNanos) {
		frames++;
		renderSum += renderNanos;
		uploadSum += uploadNanos;
		renderMax = Math.max(renderMax, renderNanos);
		uploadMax = Math.max(uploadMax, uploadNanos);
	}

	/** Ein Frame des Spiels beginnt, ob mit oder ohne neues Bild. */
	public void frame() {
		long now = System.nanoTime();
		if (lastFrame != 0) {
			gaps[gapNext] = (now - lastFrame) / 1_000_000f;
			gapNext = (gapNext + 1) % KEEP;
			gapCount = Math.min(KEEP, gapCount + 1);
		}
		lastFrame = now;
	}

	public void reset() {
		frames = 0;
		renderSum = 0;
		renderMax = 0;
		uploadSum = 0;
		uploadMax = 0;
		gapCount = 0;
		gapNext = 0;
		lastFrame = 0;
	}

	/** Mittleres Rechnen in Mikrosekunden. */
	public double renderMicros() {
		return frames == 0 ? 0 : renderSum / 1000.0 / frames;
	}

	/** Mittleres Hochladen in Mikrosekunden. */
	public double uploadMicros() {
		return frames == 0 ? 0 : uploadSum / 1000.0 / frames;
	}

	public long frames() {
		return frames;
	}

	/** Zusammenfassung für das Protokoll. */
	public String summary() {
		float[] sorted = Arrays.copyOf(gaps, gapCount);
		Arrays.sort(sorted);
		float sum = 0f;
		int over20 = 0;
		int over33 = 0;
		int over50 = 0;
		for (float g : sorted) {
			sum += g;
			if (g > 20f) over20++;
			if (g > 33.4f) over33++;
			if (g > 50f) over50++;
		}
		float mean = gapCount == 0 ? 0f : sum / gapCount;
		float p99 = gapCount == 0 ? 0f : sorted[Math.min(gapCount - 1, (int) (gapCount * 0.99f))];
		float max = gapCount == 0 ? 0f : sorted[gapCount - 1];
		return String.format(Locale.ROOT,
				"Bilder %d, Rechnen %.0f us (max %.0f), Hochladen %.0f us (max %.0f), Frames %d: Abstand %.2f ms, 99 %% %.2f ms, max %.2f ms, ueber 20 ms: %d, ueber 33,4 ms: %d, ueber 50 ms: %d",
				frames, renderMicros(), renderMax / 1000.0, uploadMicros(), uploadMax / 1000.0, gapCount, mean, p99, max, over20, over33, over50);
	}
}
