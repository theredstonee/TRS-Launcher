package dev.theredstonee.trsclient.core.ui.winter;

/**
 * Schneefall in drei Tiefen und Schornsteinrauch (Port von {@code app/utils/winter/snow.ts}). Ein Takt = ein Bild
 * (80 ms). Nur Zahlen, ohne Zeichnen.
 */
public final class Snow {
	private static final double[] SPEED = {0.28, 0.55, 0.95};

	private Snow() {
	}

	/** Wind je Takt: meist ein leichter Zug nach rechts, ab und zu dreht er kurz. */
	public static double wind(int tick) {
		return 0.06 + 0.12 * Math.sin(tick * 0.009) + 0.05 * Math.sin(tick * 0.041 + 1);
	}

	/** Flocken als parallele Felder (kein Objekt je Flocke). */
	public static final class Fall {
		public final int count;
		public final double[] x;
		public final double[] y;
		final double[] vy;
		final double[] sway;
		final double[] phase;
		/** 0 = fern (blass), 1 = mittel, 2 = nah (größer, schneller). */
		public final byte[] size;
		private final int w;
		private final int h;
		private final WinterMath.Rng rand;

		public Fall(int w, int h, WinterMath.Rng rand, double density) {
			this.w = w;
			this.h = h;
			this.rand = rand;
			count = Math.max(12, Math.min(700, (int) Math.round((w * (double) h) / 300.0 * density)));
			x = new double[count];
			y = new double[count];
			vy = new double[count];
			sway = new double[count];
			phase = new double[count];
			size = new byte[count];
			for (int i = 0; i < count; i++) spawn(i, true);
		}

		private void spawn(int i, boolean anywhere) {
			double r = rand.next();
			int s = r < 0.55 ? 0 : (r < 0.9 ? 1 : 2);
			size[i] = (byte) s;
			x[i] = rand.next() * w;
			y[i] = anywhere ? rand.next() * h : -2 - rand.next() * 8;
			vy[i] = SPEED[s] * (0.8 + rand.next() * 0.4);
			sway[i] = 0.15 + rand.next() * 0.35;
			phase[i] = rand.next() * Math.PI * 2;
		}

		public void step(int tick) {
			double wnd = wind(tick);
			for (int i = 0; i < count; i++) {
				y[i] += vy[i];
				x[i] += wnd * (0.4 + size[i] * 0.4) + Math.sin(phase[i] + tick * 0.08) * sway[i] * 0.3;
				if (y[i] > h + 2) spawn(i, false);
				if (x[i] < -3) x[i] += w + 6;
				else if (x[i] > w + 3) x[i] -= w + 6;
			}
		}
	}

	/** Rauchwölkchen: steigen auf, treiben mit dem Wind und vergehen (höchstens 24). */
	public static final class Smoke {
		public static final int MAX = 24;
		public final double[] x = new double[MAX];
		public final double[] y = new double[MAX];
		public final int[] life = new int[MAX];
		public final int[] max = new int[MAX];
		public int count;
		private final WinterMath.Rng rand;

		public Smoke(WinterMath.Rng rand) {
			this.rand = rand;
		}

		/** {@code originX/Y} = Schornstein; {@code has} = false: keine neuen Wölkchen. */
		public void step(boolean has, double originX, double originY, int tick) {
			double wnd = wind(tick);
			int n = 0;
			for (int i = 0; i < count; i++) {
				y[i] -= 0.22;
				x[i] += 0.05 + wnd * 0.6 + (rand.next() - 0.5) * 0.15;
				life[i]--;
				if (life[i] > 0) {
					x[n] = x[i];
					y[n] = y[i];
					life[n] = life[i];
					max[n] = max[i];
					n++;
				}
			}
			count = n;
			if (has && tick % 7 == 0 && count < MAX) {
				int m = 46 + (int) Math.floor(rand.next() * 18);
				x[count] = originX + (rand.next() - 0.5);
				y[count] = originY;
				life[count] = m;
				max[count] = m;
				count++;
			}
		}
	}
}
