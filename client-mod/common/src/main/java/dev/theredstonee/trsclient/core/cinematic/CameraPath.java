package dev.theredstonee.trsclient.core.cinematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Eine Kamera-Fahrt mit festem Zeitplan: Keyframes plus die Zeit (Sekunden ab Start), zu der die Kamera jeden erreicht.
 * {@link #pose} liefert die Lage zu jeder Zeit – unabhängig von der Bildrate, weil nur die verstrichene Zeit zählt.
 *
 * <p><b>Glatt</b> (Catmull-Rom): kubische Hermite-Kurve mit Tangenten {@code (P[i+1] − P[i−1]) / (t[i+1] − t[i−1])} –
 * die Kurve geht durch jeden Keyframe, Geschwindigkeit und Richtung ändern sich nirgends sprunghaft (auch nicht bei
 * ungleich langen Abschnitten). An Anfang und Ende gilt der Endpunkt als verdoppelt, die Fahrt läuft dort sanft an bzw.
 * aus. <b>Linear</b>: gerade Abschnitte. Gierwinkel nehmen immer den kürzeren Weg (350° → 10° dreht 20°, nicht 340°).
 */
public final class CameraPath {
	/** Wie zwischen den Keyframes gefahren wird. */
	public enum Smoothing {
		SMOOTH, LINEAR
	}

	/** Zeitplan: gleich lange Abschnitte oder gleichmäßiges Tempo über die ganze Fahrt. */
	public enum Timing {
		/** Jeder Abschnitt dauert {@code seconds}. */
		PER_SEGMENT,
		/** Die ganze Fahrt dauert {@code seconds}, jeder Abschnitt nach seiner Länge (gleichmäßiges Tempo). */
		TOTAL
	}

	/** Kürzester Abschnitt (Sekunden) – auch bei zwei Keyframes am selben Ort. */
	static final double MIN_SEGMENT = 0.05;

	private static final int X = 0;
	private static final int Y = 1;
	private static final int Z = 2;
	private static final int YAW = 3;
	private static final int PITCH = 4;
	private static final int FOV = 5;

	private final List<Keyframe> keys;
	/** Zeitpunkt je Keyframe (Sekunden ab Start), streng steigend. */
	private final double[] times;
	/** Gierwinkel „entrollt“ (kürzester Weg zwischen Nachbarn). */
	private final double[] yaw;
	private final Smoothing smoothing;
	private final boolean ease;

	private CameraPath(List<Keyframe> keys, double[] times, Smoothing smoothing, boolean ease) {
		this.keys = keys;
		this.times = times;
		this.smoothing = smoothing;
		this.ease = ease;
		this.yaw = new double[keys.size()];
		for (int i = 0; i < keys.size(); i++) {
			yaw[i] = i == 0 ? keys.get(0).yaw : yaw[i - 1] + wrapDegrees(keys.get(i).yaw - keys.get(i - 1).yaw);
		}
	}

	/**
	 * Baut den Zeitplan. Mindestens zwei Keyframes, sonst null.
	 *
	 * @param seconds Sekunden je Abschnitt ({@link Timing#PER_SEGMENT}) bzw. für die ganze Fahrt ({@link Timing#TOTAL})
	 * @param ease    ganze Fahrt sanft anfahren und abbremsen (zusätzlich zur Kurve)
	 */
	public static CameraPath of(List<Keyframe> keyframes, Smoothing smoothing, Timing timing, double seconds, boolean ease) {
		if (keyframes == null || keyframes.size() < 2) return null;
		List<Keyframe> keys = Collections.unmodifiableList(new ArrayList<>(keyframes));
		int segments = keys.size() - 1;
		double s = Double.isFinite(seconds) && seconds > 0 ? seconds : 5;
		double[] times = new double[keys.size()];
		if (timing == Timing.TOTAL) {
			double[] len = new double[segments];
			double sum = 0;
			for (int i = 0; i < segments; i++) {
				// Auch reine Drehungen (gleicher Ort) brauchen Zeit: 30° zählen wie ein Block.
				Keyframe a = keys.get(i);
				Keyframe b = keys.get(i + 1);
				double turn = Math.abs(wrapDegrees(b.yaw - a.yaw)) + Math.abs(b.pitch - a.pitch) + Math.abs(b.fov - a.fov);
				len[i] = Math.max(a.distanceTo(b), turn / 30.0);
				sum += len[i];
			}
			double total = Math.max(s, MIN_SEGMENT * segments);
			for (int i = 0; i < segments; i++) {
				double share = sum > 1e-9 ? len[i] / sum : 1.0 / segments;
				times[i + 1] = times[i] + Math.max(MIN_SEGMENT, total * share);
			}
		} else {
			double seg = Math.max(MIN_SEGMENT, s);
			for (int i = 1; i < times.length; i++) times[i] = times[i - 1] + seg;
		}
		return new CameraPath(keys, times, smoothing == null ? Smoothing.SMOOTH : smoothing, ease);
	}

	/** Gesamtdauer in Sekunden. */
	public double duration() {
		return times[times.length - 1];
	}

	public int size() {
		return keys.size();
	}

	public List<Keyframe> keyframes() {
		return keys;
	}

	/** Zeitpunkt (Sekunden ab Start), zu dem Keyframe {@code i} erreicht wird (ohne Gesamt-Easing). */
	public double timeOf(int i) {
		return times[i];
	}

	/**
	 * Lage zur Zeit {@code seconds} ab Start (vor dem Start = erster, nach dem Ende = letzter Keyframe).
	 *
	 * @return {@code out}
	 */
	public CameraPose pose(double seconds, CameraPose out) {
		double total = duration();
		double t = Double.isFinite(seconds) ? Math.max(0, Math.min(total, seconds)) : 0;
		if (ease && total > 0) t = total * smoothstep(t / total);
		return poseAt(t, out);
	}

	/** Lage zur Zeit {@code t} des Zeitplans (ohne Gesamt-Easing). */
	private CameraPose poseAt(double t, CameraPose out) {
		int i = segment(t);
		double t0 = times[i];
		double dt = times[i + 1] - t0;
		double u = dt > 0 ? Math.max(0, Math.min(1, (t - t0) / dt)) : 0;
		Keyframe a = keys.get(i);
		Keyframe b = keys.get(i + 1);
		if (smoothing == Smoothing.LINEAR) {
			out.x = lerp(a.x, b.x, u);
			out.y = lerp(a.y, b.y, u);
			out.z = lerp(a.z, b.z, u);
			out.yaw = (float) lerp(yaw[i], yaw[i + 1], u);
			out.pitch = (float) lerp(a.pitch, b.pitch, u);
			out.fov = (float) lerp(a.fov, b.fov, u);
		} else {
			out.x = hermite(i, u, dt, X);
			out.y = hermite(i, u, dt, Y);
			out.z = hermite(i, u, dt, Z);
			out.yaw = (float) hermite(i, u, dt, YAW);
			out.pitch = (float) hermite(i, u, dt, PITCH);
			out.fov = (float) hermite(i, u, dt, FOV);
		}
		out.pitch = Keyframe.clampPitch(out.pitch);
		out.fov = Keyframe.clampFov(out.fov);
		out.yaw = (float) wrapDegrees(out.yaw);
		return out;
	}

	/** Abschnitt, in dem {@code t} liegt (0 … n−2). */
	private int segment(double t) {
		int lo = 0;
		int hi = times.length - 2;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (times[mid] <= t) lo = mid;
			else hi = mid - 1;
		}
		return lo;
	}

	private double value(int i, int channel) {
		Keyframe k = keys.get(i);
		switch (channel) {
			case X:
				return k.x;
			case Y:
				return k.y;
			case Z:
				return k.z;
			case YAW:
				return yaw[i];
			case PITCH:
				return k.pitch;
			default:
				return k.fov;
		}
	}

	/** Tangente (Änderung je Sekunde) an Keyframe {@code i}; die Endpunkte gelten als verdoppelt. */
	private double tangent(int i, int channel) {
		int n = keys.size();
		int prev = Math.max(0, i - 1);
		int next = Math.min(n - 1, i + 1);
		double tp = i == 0 ? times[0] - (times[1] - times[0]) : times[prev];
		double tn = i == n - 1 ? times[n - 1] + (times[n - 1] - times[n - 2]) : times[next];
		double span = tn - tp;
		if (span <= 0) return 0;
		return (value(next, channel) - value(prev, channel)) / span;
	}

	private double hermite(int i, double u, double dt, int channel) {
		double p0 = value(i, channel);
		double p1 = value(i + 1, channel);
		double m0 = tangent(i, channel) * dt;
		double m1 = tangent(i + 1, channel) * dt;
		double u2 = u * u;
		double u3 = u2 * u;
		return (2 * u3 - 3 * u2 + 1) * p0 + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * p1 + (u3 - u2) * m1;
	}

	static double lerp(double a, double b, double u) {
		return a + (b - a) * u;
	}

	static double smoothstep(double x) {
		double c = Math.max(0, Math.min(1, x));
		return c * c * (3 - 2 * c);
	}

	/** Winkel auf −180…180 Grad. */
	static double wrapDegrees(double deg) {
		double d = deg % 360.0;
		if (d >= 180.0) d -= 360.0;
		if (d < -180.0) d += 360.0;
		return d;
	}

	/**
	 * Punkte entlang der Fahrt (gleichmäßig über die Zeit des Zeitplans verteilt, etwa alle {@code step} Blöcke,
	 * höchstens {@code max + 1}) – für die Vorschau im Spiel.
	 */
	public List<double[]> sample(double step, int max) {
		double length = 0;
		for (int i = 0; i + 1 < keys.size(); i++) length += keys.get(i).distanceTo(keys.get(i + 1));
		int count = (int) Math.max(1, Math.min(max, Math.ceil(length / Math.max(0.1, step))));
		List<double[]> out = new ArrayList<>(count + 1);
		CameraPose p = new CameraPose();
		double total = duration();
		for (int k = 0; k <= count; k++) {
			poseAt(total * k / count, p);
			out.add(new double[]{p.x, p.y, p.z});
		}
		return out;
	}
}
