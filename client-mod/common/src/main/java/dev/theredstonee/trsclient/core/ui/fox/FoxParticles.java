package dev.theredstonee.trsclient.core.ui.fox;

import dev.theredstonee.trsclient.core.ui.winter.WinterMath;

/**
 * Bewegtes in der Fuchsbau-Szene (Port von {@code app/utils/fox/particles.ts} und {@code sleep.ts}), nur Zahlen. Ein
 * Takt = ein Bild (80 ms): treibende Blätter und ein paar Kirschblüten, Glühwürmchen über der Wiese, Funken vom
 * Lagerfeuer und das Atmen des schlafenden Fuchses.
 */
public final class FoxParticles {
	private FoxParticles() {
	}

	/** Wind je Takt: ein leichter Zug nach rechts, der langsam an- und abschwillt. */
	public static double breeze(int tick) {
		return 0.05 + 0.08 * Math.sin(tick * 0.007) + 0.04 * Math.sin(tick * 0.031 + 2);
	}

	/** Treibende Blätter und Blüten als parallele Felder (kein Objekt je Blatt). */
	public static final class Leaves {
		public final int count;
		public final double[] x;
		public final double[] y;
		final double[] vy;
		final double[] sway;
		final double[] phase;
		/** Blüte (rosa) statt Blatt. */
		public final boolean[] petal;
		/** Farbindex in der Palette. */
		public final int[] tone;
		/** 0 = fern (1 Texel), 1 = nah (2 Texel, dreht sich). */
		public final byte[] size;
		private final int w;
		private final int h;
		private final WinterMath.Rng rand;

		public Leaves(int w, int h, WinterMath.Rng rand, double density) {
			this.w = w;
			this.h = h;
			this.rand = rand;
			count = Math.max(6, Math.min(90, (int) Math.round((w / 14.0) * density)));
			x = new double[count];
			y = new double[count];
			vy = new double[count];
			sway = new double[count];
			phase = new double[count];
			petal = new boolean[count];
			tone = new int[count];
			size = new byte[count];
			for (int i = 0; i < count; i++) spawn(i, true);
		}

		private void spawn(int i, boolean anywhere) {
			petal[i] = rand.next() < 0.22;
			size[i] = (byte) (rand.next() < 0.35 ? 1 : 0);
			x[i] = rand.next() * w;
			y[i] = anywhere ? rand.next() * h : -2 - rand.next() * 10;
			vy[i] = (size[i] == 1 ? 0.2 : 0.12) * (0.75 + rand.next() * 0.5);
			sway[i] = 0.25 + rand.next() * 0.45;
			phase[i] = rand.next() * Math.PI * 2;
			tone[i] = (int) Math.floor(rand.next() * 4);
		}

		public void step(int tick) {
			double wnd = breeze(tick);
			for (int i = 0; i < count; i++) {
				y[i] += vy[i] * (0.8 + 0.4 * Math.sin(phase[i] + tick * 0.05));
				x[i] += wnd * (0.6 + size[i] * 0.6) + Math.sin(phase[i] + tick * 0.06) * sway[i] * 0.35;
				if (y[i] > h + 2) spawn(i, false);
				if (x[i] < -3) x[i] += w + 6;
				else if (x[i] > w + 3) x[i] -= w + 6;
			}
		}

		/** Blätter drehen sich im Fallen: breit (2 × 1) oder schmal (1 × 1) – je Takt. */
		public boolean wide(int i, int tick) {
			return size[i] == 1 && Math.sin(phase[i] * 3 + tick * 0.18) > -0.2;
		}
	}

	/** Glühwürmchen: kreisen langsam um ihre Ruhelage und leuchten im eigenen Rhythmus auf. */
	public static final class Fireflies {
		public final int count;
		final double[] hx;
		final double[] hy;
		final double[] phase;
		final double[] speed;
		final double[] blink;
		/** Ergebnis von {@link #at}: Lage und Helligkeit 0…1. */
		public int ax;
		public int ay;
		public double aa;

		public Fireflies(WinterMath.Rng rand, int n, double x0, double x1, double y0, double y1) {
			count = Math.max(0, n);
			hx = new double[count];
			hy = new double[count];
			phase = new double[count];
			speed = new double[count];
			blink = new double[count];
			for (int i = 0; i < count; i++) {
				hx[i] = x0 + rand.next() * (x1 - x0);
				hy[i] = y0 + rand.next() * (y1 - y0);
				phase[i] = rand.next() * 40;
				speed[i] = 0.6 + rand.next() * 0.8;
				blink[i] = 0.7 + rand.next() * 0.9;
			}
		}

		/** Lage und Helligkeit des {@code i}-ten zur Zeit {@code seconds}; ruhig = steht still und glimmt. */
		public void at(int i, double seconds, boolean calm) {
			if (calm) {
				ax = (int) Math.round(hx[i]);
				ay = (int) Math.round(hy[i]);
				aa = 0.7;
				return;
			}
			double t = seconds * speed[i] + phase[i];
			double x = hx[i] + Math.sin(t * 0.45) * 7 + Math.sin(t * 1.3) * 1.5;
			double y = hy[i] + Math.cos(t * 0.6) * 3 + Math.sin(t * 1.7) * 1;
			double a = Math.max(0, Math.sin(seconds * blink[i] + phase[i]));
			ax = (int) Math.round(x);
			ay = (int) Math.round(y);
			aa = a * a;
		}
	}

	/** Funken vom Lagerfeuer: steigen auf, flackern, vergehen (höchstens 30). */
	public static final class Sparks {
		public static final int MAX = 30;
		public final double[] x = new double[MAX];
		public final double[] y = new double[MAX];
		final double[] vx = new double[MAX];
		final double[] vy = new double[MAX];
		public final int[] life = new int[MAX];
		public final int[] max = new int[MAX];
		public int count;
		private final WinterMath.Rng rand;

		public Sparks(WinterMath.Rng rand) {
			this.rand = rand;
		}

		/** {@code origin} = {x, y, w} der Flammen oder null; {@code power} 0…1 (mehr, wenn das Spiel läuft). */
		public void step(double[] origin, int tick, double power) {
			double wnd = breeze(tick);
			int n = 0;
			for (int i = 0; i < count; i++) {
				x[i] += vx[i] + wnd * 0.5;
				y[i] += vy[i];
				vy[i] *= 0.97;
				life[i]--;
				if (life[i] > 0) {
					x[n] = x[i];
					y[n] = y[i];
					vx[n] = vx[i];
					vy[n] = vy[i];
					life[n] = life[i];
					max[n] = max[i];
					n++;
				}
			}
			count = n;
			if (origin == null) return;
			int every = power > 0.8 ? 2 : (power > 0.4 ? 4 : 7);
			if (tick % every == 0 && count < MAX) {
				int m = 14 + (int) Math.floor(rand.next() * 18);
				x[count] = origin[0] + rand.next() * origin[2];
				y[count] = origin[1];
				vx[count] = (rand.next() - 0.5) * 0.25;
				vy[count] = -(0.45 + rand.next() * 0.5);
				life[count] = m;
				max[count] = m;
				count++;
			}
		}
	}

	// --- Atmen des Fuchses ---

	/** Ein ruhiger Atemzug alle 3,4 s – einatmen 1,4 s, ausatmen länger. */
	public static final double BREATH_PERIOD = 3.4;
	public static final double INHALE = 1.4;

	/** Eingeatmet? (Rücken einen Texel höher.) Ruhig = ausgeatmet. */
	public static boolean inhale(double seconds, boolean calm) {
		if (calm) return false;
		double phase = ((seconds % BREATH_PERIOD) + BREATH_PERIOD) % BREATH_PERIOD;
		return phase < INHALE;
	}

	/** Etwa alle 20 Sekunden zuckt kurz ein Ohr (0,3 s). */
	public static boolean twitch(double seconds, boolean calm) {
		if (calm) return false;
		int slot = (int) Math.floor(seconds / 20);
		double at = 4 + WinterMath.noise(slot, 3, 97) * 12;
		double local = seconds - slot * 20;
		return local >= at && local < at + 0.3;
	}
}
