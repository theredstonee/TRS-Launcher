package dev.theredstonee.trsclient.core.emote;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Ein Emote: ID (wie in der TRS API), Dauer, Schleife und die Schlüsselbilder je Kanal ({@link Channel}).
 * Die API kennt nur IDs, Freischaltung und Dauer – die Animation steckt hier (für alle Loader gleich).
 *
 * <p>Ein- und Ausblenden übernimmt {@link #weight}: die Pose wird über {@link #FADE_IN_MS} aus der Vanilla-Haltung
 * heraus eingeblendet und am Ende über {@link #FADE_OUT_MS} zurückgeblendet. Die Schlüsselbilder beschreiben
 * daher nur die Bewegung selbst.
 */
public final class EmoteDef {
	public static final int FADE_IN_MS = 180;
	public static final int FADE_OUT_MS = 220;

	private final String id;
	private final int durationMs;
	private final boolean loop;
	private final int cycleMs;
	private final String icon;
	private final List<Track> tracks;
	private final int mask;

	private EmoteDef(String id, int durationMs, boolean loop, int cycleMs, String icon, List<Track> tracks) {
		this.id = id;
		this.durationMs = durationMs;
		this.loop = loop;
		this.cycleMs = cycleMs;
		this.icon = icon;
		this.tracks = Collections.unmodifiableList(tracks);
		int m = 0;
		for (Track t : tracks) m |= Channel.limb(t.channel);
		this.mask = m;
	}

	public String id() {
		return id;
	}

	/** Standard-Dauer (die API kann beim Abspielen eine eigene nennen). */
	public int durationMs() {
		return durationMs;
	}

	public boolean loop() {
		return loop;
	}

	/** Länge eines Durchgangs (bei Schleifen), sonst die Dauer. */
	public int cycleMs() {
		return cycleMs;
	}

	/** Symbol im Emote-Rad ({@code core.ui.Icons}). */
	public String icon() {
		return icon;
	}

	/** Übersetzungsschlüssel des Namens. */
	public String nameKey() {
		return "emote." + id;
	}

	public List<Track> tracks() {
		return tracks;
	}

	/** Glieder, die das Emote selbst bewegt ({@link Channel#MASK_RIGHT_ARM} …). */
	public int mask() {
		return mask;
	}

	/** Pose zur Zeit {@code elapsedMs} seit dem Start (ohne Ein-/Ausblenden) nach {@code out}. */
	public void sample(float elapsedMs, float[] out) {
		Arrays.fill(out, 0f);
		float t;
		int cycle;
		if (loop) {
			t = elapsedMs % cycleMs;
			if (t < 0) t += cycleMs;
			cycle = cycleMs;
		} else {
			t = elapsedMs;
			cycle = 0;
		}
		for (Track track : tracks) out[track.channel] = track.sample(t, cycle);
	}

	/**
	 * Deckkraft der Pose (0..1) zur Zeit {@code elapsedMs} bei einer Gesamtdauer von {@code totalMs}: weich
	 * einblenden, am Ende weich zurück in die Vanilla-Haltung.
	 */
	public static float weight(float elapsedMs, float totalMs) {
		if (elapsedMs <= 0 || elapsedMs >= totalMs) return 0f;
		float in = Math.min(1f, elapsedMs / FADE_IN_MS);
		float out = Math.min(1f, (totalMs - elapsedMs) / FADE_OUT_MS);
		return smooth(Math.min(in, out));
	}

	static float smooth(float k) {
		float x = Math.max(0f, Math.min(1f, k));
		return x * x * (3f - 2f * x);
	}

	// --- Aufbau ---

	static Builder builder(String id, int durationMs, boolean loop, int cycleMs, String icon) {
		return new Builder(id, durationMs, loop, cycleMs, icon);
	}

	/** Baukasten: Winkel in Grad, Verschiebungen in Pixeln. */
	static final class Builder {
		private final String id;
		private final int durationMs;
		private final boolean loop;
		private final int cycleMs;
		private final String icon;
		private final List<Track> tracks = new ArrayList<>();
		private Track.Ease ease = Track.Ease.SMOOTH;

		private Builder(String id, int durationMs, boolean loop, int cycleMs, String icon) {
			if (durationMs <= 0 || (loop && cycleMs <= 0)) throw new IllegalArgumentException(id);
			this.id = id;
			this.durationMs = durationMs;
			this.loop = loop;
			this.cycleMs = loop ? cycleMs : durationMs;
			this.icon = icon;
		}

		/** Verlauf der folgenden Spuren. */
		Builder ease(Track.Ease e) {
			this.ease = e;
			return this;
		}

		/** Schlüsselbilder als Paare (Zeit ms, Wert). */
		Builder key(int channel, float... timeValue) {
			if (timeValue.length < 2 || timeValue.length % 2 != 0) throw new IllegalArgumentException(id + ": Paare");
			int n = timeValue.length / 2;
			int[] times = new int[n];
			float[] values = new float[n];
			for (int i = 0; i < n; i++) {
				times[i] = Math.round(timeValue[2 * i]);
				values[i] = convert(channel, timeValue[2 * i + 1]);
			}
			for (Track t : tracks) {
				if (t.channel == channel) throw new IllegalArgumentException(id + ": Kanal doppelt " + channel);
			}
			tracks.add(new Track(channel, times, values, ease));
			return this;
		}

		/** Fester Wert über die ganze Dauer. */
		Builder hold(int channel, float value) {
			return key(channel, 0, value);
		}

		/**
		 * Pendeln zwischen {@code a} und {@code b}: ab {@code from} alle {@code half} ms ein Wechsel bis {@code to}
		 * (beginnt und endet mit {@code a}, wenn die Anzahl der Hälften gerade ist).
		 */
		Builder osc(int channel, float a, float b, int half, int from, int to) {
			List<Float> pairs = new ArrayList<>();
			boolean first = true;
			for (int t = from; t <= to; t += half) {
				pairs.add((float) t);
				pairs.add(first ? a : b);
				first = !first;
			}
			float[] kv = new float[pairs.size()];
			for (int i = 0; i < kv.length; i++) kv[i] = pairs.get(i);
			return key(channel, kv);
		}

		private static float convert(int channel, float v) {
			return Channel.isOffset(channel) ? v : (float) Math.toRadians(v);
		}

		EmoteDef build() {
			if (tracks.isEmpty()) throw new IllegalStateException(id + ": keine Spuren");
			return new EmoteDef(id, durationMs, loop, cycleMs, icon, tracks);
		}
	}
}
