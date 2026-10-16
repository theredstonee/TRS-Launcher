package dev.theredstonee.trsclient.core.ui.halloween;

import java.util.ArrayList;
import java.util.List;

/**
 * Malt die Mondnacht (Port von {@code makeScene} aus dem freigegebenen Entwurf, Stufe „Horror“, gleiche Zahlen): Himmel
 * mit Bayer-Verlauf und Mondhof, Blutmond (grinst alle ~21 s), Herrenhaus mit flackernden Fenstern, kahle Bäume mit Raben,
 * Zaun mit Schädeln, Gräber mit Kerzen und Zombie-Händen, sieben Kürbislaternen, zwei Geister, Irrlichter, rote Augen,
 * Blitz mit Schattengestalt, Hexe vor dem Mond, Fledermäuse, drei+eins Nebelbänder, Spinnennetz mit Spinne, roter Hauch.
 *
 * <p>Statische Ebenen entstehen einmal je Größe; {@link #frame} setzt daraus das Bild in einen deckenden ARGB-Puffer
 * zusammen. Die Blitz-Aufhellung selbst zeichnet {@link HorrorLayer} (Einstellung „Flackern &amp; Rauschen“).
 */
final class NightPainter {
	/** mulberry32 wie {@code rng()} im Entwurf. */
	static final class Rng {
		private int s;

		Rng(int seed) {
			this.s = seed;
		}

		double next() {
			s += 0x6D2B79F5;
			int t = (s ^ (s >>> 15)) * (1 | s);
			t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
			return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
		}
	}

	/** Ebene mit Alpha (nicht vormultipliziert) und Liste der belegten Pixel. */
	static final class Layer {
		final int w;
		final int h;
		final int[] px;
		int[] idx;

		Layer(int w, int h) {
			this.w = w;
			this.h = h;
			this.px = new int[w * h];
		}

		void fill(int x, int y, int fw, int fh, int argb) {
			int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(w, x + fw), y1 = Math.min(h, y + fh);
			for (int yy = y0; yy < y1; yy++) {
				int row = yy * w;
				for (int xx = x0; xx < x1; xx++) px[row + xx] = over(px[row + xx], argb);
			}
		}

		void seal() {
			int n = 0;
			for (int p : px) if (p != 0) n++;
			idx = new int[n];
			n = 0;
			for (int i = 0; i < px.length; i++) if (px[i] != 0) idx[n++] = i;
		}
	}

	private static final class Star {
		int x, y;
		double p, s, b;
	}

	private static final class Crater {
		double x, y, r;
	}

	private static final class Grave {
		double x;
		int y, cx, cy;
	}

	private static final class Lit {
		int x, y;
		double p;
	}

	private static final class Pumpkin {
		int x, y;
		double p;
	}

	private static final class Ghost {
		double x, y, v, p;
	}

	private static final class Raven {
		int x, y;
		double p;
	}

	private static final class Fog {
		Layer c;
		double y, v;
	}

	private static final class Cloud {
		double x, y, len, v;
	}

	private static final class Flyer {
		char k;
		double x, y, v, vy, p;
	}

	private static final class Eye {
		int x, y;
		double until, next;
	}

	private static final class Wisp {
		double x, y, life, p;
	}

	private static final class Bolt {
		double t0;
		List<double[]> pts = new ArrayList<double[]>();
		int fx, fy;
	}

	static final String[] WITCH = {".......k........", "......kk........", ".....kkk........", "...kkkkkkk......", ".....kkk........",
			"....kkkkk.......", "...kkkkkkk......", "kkkkkkkkkkkkkkkk", "kkkk..k..k......"};
	static final String[] GHOST = {".www.", "wwwww", "wkwkw", "wwwww", "wwkww", "wwwww", "w.w.w"};

	final int W;
	final int H;
	private final int seed;
	private final Rng R;
	private final int mx, my, mr;
	private final int[] sky;
	private final List<Star> stars = new ArrayList<Star>();
	private final Layer blood;
	private final Layer far;
	private final Layer mid;
	private final Layer fore;
	private final Layer web;
	private final Layer glow;
	private final int hx, hb;
	private final List<Lit> lits = new ArrayList<Lit>();
	private final List<Grave> graves = new ArrayList<Grave>();
	private final List<Pumpkin> pumpkins = new ArrayList<Pumpkin>();
	private final List<Ghost> ghosts = new ArrayList<Ghost>();
	private final List<Raven> ravens = new ArrayList<Raven>();
	private final int spiderX;
	private final double spiderLen, spiderP;
	private final Fog[] fogs = new Fog[3];
	private final List<Cloud> clouds = new ArrayList<Cloud>();
	private final List<Wisp> wisps = new ArrayList<Wisp>();
	private final List<Flyer> flyers = new ArrayList<Flyer>();
	private final Eye[] eyes = new Eye[6];
	private final int fence0, fence1;
	private double nextWitch, nextBats, nextSwarm, nextBolt, last;
	private Bolt bolt;
	private int[] out;

	NightPainter(int w, int h, int seed, double moonX) {
		this.W = Math.max(16, w);
		this.H = Math.max(16, h);
		this.seed = seed;
		this.R = new Rng(seed);
		mx = (int) Math.round(W * moonX);
		my = (int) Math.round(H * 0.24);
		mr = (int) Math.round(H * 0.11);

		// Himmel mit Bayer-Verlauf und Mondhof
		sky = new int[W * H];
		double[][] stops = {stop(0, 0x06050b), stop(0.42, 0x110c20), stop(0.72, 0x21173a), stop(1, 0x2e2348)};
		double[] halo = rgb(0x4a406e);
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				double t = y / (double) H;
				int i = 1;
				while (i < 3 && t > stops[i][0]) i++;
				double k = Math.min(1, Math.max(0, (t - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0])));
				double[] col = mix(rgbOf(stops[i - 1]), rgbOf(stops[i]), Math.floor(k * 4 + bay(x, y)) / 4);
				double d = Math.hypot(x - mx, y - my);
				if (d < mr * 2.8) {
					double inten = Math.pow(Math.max(0, 1 - (d - mr) / (mr * 1.8)), 2);
					col = mix(col, halo, (Math.floor(inten * 3 + bay(x, y)) / 3) * 0.6);
				}
				sky[y * W + x] = opaque(col);
			}
		}
		for (int i = 0; i < W * 0.28; i++) {
			int x = (int) Math.floor(R.next() * W), y = (int) Math.floor(R.next() * H * 0.62);
			if (Math.hypot(x - mx, y - my) < mr * 2.4) continue;
			Star s = new Star();
			s.x = x;
			s.y = y;
			s.p = R.next() * 6.28;
			s.s = 0.6 + R.next() * 2.2;
			s.b = R.next();
			stars.add(s);
		}
		// Blutmond mit Kratern und rotem Hof
		List<Crater> craters = new ArrayList<Crater>();
		for (int i = 0; i < 6; i++) {
			Crater q = new Crater();
			q.x = (R.next() - 0.5) * mr * 1.2;
			q.y = (R.next() - 0.5) * mr * 1.2;
			q.r = 1 + R.next() * mr * 0.22;
			craters.add(q);
		}
		blood = new Layer(W, H);
		int rr = (int) Math.round(mr * 2.8);
		for (int y = -rr; y <= rr; y++) {
			for (int x = -rr; x <= rr; x++) {
				double d = Math.hypot(x, y);
				if (x * x + y * y <= mr * mr + mr * 0.6) {
					double n = (x * 0.6 + y * 0.45) / mr;
					boolean shade = n > 0.42 + bay(x + 64, y + 64) * 0.18;
					int c = shade ? 0xFF9C2F26 : 0xFFD8553B;
					for (Crater q : craters) {
						if ((x - q.x) * (x - q.x) + (y - q.y) * (y - q.y) < q.r * q.r) {
							c = n > 0.42 ? 0xFF7E231D : 0xFFB8432F;
							break;
						}
					}
					blood.fill(mx + x, my + y, 1, 1, c);
				} else {
					double inten = Math.pow(Math.max(0, 1 - (d - mr) / (mr * 1.8)), 2);
					if (bay(x + 64, y + 64) < inten * 0.75) blood.fill(mx + x, my + y, 1, 1, argb(0.42, 150, 32, 36));
				}
			}
		}
		blood.seal();
		// Ferne Hügel + Herrenhaus
		far = new Layer(W, H);
		for (int x = 0; x < W; x++) far.fill(x, hill(x), 1, H, 0xFF1E1638);
		hx = (int) Math.round(W * 0.2);
		hb = hill(hx + 14);
		int house = 0xFF17112D;
		far.fill(hx, hb - 14, 28, 16, house);
		far.fill(hx - 3, hb - 24, 7, 26, house);
		far.fill(hx + 24, hb - 20, 7, 22, house);
		far.fill(hx + 11, hb - 19, 6, 6, house);
		roof(far, hx, hb - 32, 9, house);
		roof(far, hx + 27, hb - 27, 8, house);
		roof(far, hx + 14, hb - 25, 7, house);
		int[][] dark = {{hx + 4, hb - 10}, {hx + 9, hb - 10}, {hx + 19, hb - 10}, {hx + 26, hb - 15}, {hx - 1, hb - 12}};
		for (int[] p : dark) far.fill(p[0], p[1], 1, 2, 0xFF2A2140);
		far.seal();
		int[][] litPos = {{hx, hb - 19}, {hx + 9, hb - 10}, {hx + 26, hb - 15}, {hx + 19, hb - 6}, {hx + 14, hb - 17}};
		double[] litP = {0, 1.3, 2.1, 3.7, 4.4};
		for (int i = 0; i < litPos.length; i++) {
			Lit l = new Lit();
			l.x = litPos[i][0];
			l.y = litPos[i][1];
			l.p = litP[i];
			lits.add(l);
		}
		// Mitte: Boden, Bäume, Zaun, Gräber
		mid = new Layer(W, H);
		for (int x = 0; x < W; x++) mid.fill(x, ground(x), 1, H, 0xFF120E20);
		List<int[]> tips = new ArrayList<int[]>();
		double[][] trees = {{0.06, 1}, {0.37, 0.8}, {0.93, 1.1}};
		for (double[] tr : trees) {
			int x = (int) Math.round(W * tr[0]);
			double ang = Math.PI / 2 + (R.next() - 0.5) * 0.2;
			tree(x, ground(x), H * 0.11 * tr[1], ang, 6, tips);
		}
		fence0 = (int) Math.round(W * 0.45);
		fence1 = (int) Math.round(W * 0.66);
		int dark2 = 0xFF0D0A17;
		for (int x = fence0; x < fence1; x += 4) {
			int g = ground(x);
			mid.fill(x, g - 9, 1, 9, dark2);
			mid.fill(x, g - 10, 1, 1, dark2);
		}
		for (int x = fence0; x < fence1; x++) {
			int g = ground(x);
			mid.fill(x, g - 7, 1, 1, dark2);
			mid.fill(x, g - 3, 1, 1, dark2);
		}
		double[] gfx = {0.5, 0.56, 0.62, 0.74, 0.8};
		for (int i = 0; i < gfx.length; i++) {
			int x = (int) Math.round(W * gfx[i]) + (i % 2 == 1 ? 1 : 0);
			int g = ground(x) + 3;
			int gh = 6 + (i % 3) * 2;
			int gw = 5 + (i % 2);
			mid.fill(x, g - gh, gw, gh, 0xFF1C1730);
			mid.fill(x + 1, g - gh - 1, gw - 2, 1, 0xFF1C1730);
			mid.fill(x + gw - 1, g - gh + 1, 1, gh - 2, 0xFF2D2648);
			Grave gr = new Grave();
			gr.x = x + gw / 2.0;
			gr.y = g - gh;
			gr.cx = x + gw + 1;
			gr.cy = g - 3;
			graves.add(gr);
			mid.fill(x + gw + 1, g - 3, 1, 3, 0xFFD9D2C0);
		}
		mid.seal();
		// Vordergrund + Kürbisse
		fore = new Layer(W, H);
		for (int x = 0; x < W; x++) {
			int g = (int) Math.round(H * 0.93 + Math.sin(x * 0.05) * 2);
			fore.fill(x, g, 1, H, 0xFF08070E);
			if (R.next() < 0.25) fore.fill(x, g - 1 - (int) Math.floor(R.next() * 2), 1, 2, 0xFF08070E);
		}
		double[] pfx = {0.06, 0.16, 0.29, 0.53, 0.66, 0.79, 0.9};
		for (int i = 0; i < pfx.length; i++) {
			Pumpkin p = new Pumpkin();
			p.x = (int) Math.round(W * pfx[i]);
			p.y = (int) Math.round(H * (0.935 + (i % 2) * 0.012)) - 8;
			p.p = R.next() * 9;
			pumpkins.add(p);
		}
		int warm = argb(0.10, 224, 138, 60);
		for (int y = (int) Math.round(H * 0.86); y < H; y++) {
			for (int x = 0; x < W; x++) {
				double k = (y - H * 0.86) / (H * 0.14);
				if (bay(x, y) < k * 0.18) fore.fill(x, y, 1, 1, warm);
			}
		}
		Sprites.Sprite jack = Sprites.JACK;
		for (Pumpkin p : pumpkins) {
			for (int ry = 0; ry < jack.h; ry++) {
				for (int rx = 0; rx < jack.w; rx++) {
					int c = jack.get(rx, ry);
					if (c != 0) fore.fill(p.x + rx, p.y + ry, 1, 1, c);
				}
			}
		}
		fore.seal();
		glow = new Layer(36, 36);
		int glowC = argb(0.26, 232, 140, 58);
		for (int y = 0; y < 36; y++) {
			for (int x = 0; x < 36; x++) {
				double d = Math.hypot(x - 18, y - 18) / 18;
				if (d < 1 && bay(x, y) < Math.pow(1 - d, 1.4)) glow.fill(x, y, 1, 1, glowC);
			}
		}
		glow.seal();
		// Spinnennetz in der linken oberen Ecke
		web = new Layer(W, H);
		int webC = argb(0.22, 207, 213, 227);
		int wr = (int) Math.round(H * 0.22);
		for (int a = 0; a <= 4; a++) {
			double ang = (a / 4.0) * Math.PI / 2;
			for (int r = 0; r < wr; r++) web.fill((int) Math.round(Math.cos(ang) * r), (int) Math.round(Math.sin(ang) * r), 1, 1, webC);
		}
		for (int ring = 1; ring <= 4; ring++) {
			double rr2 = ring * wr / 4.4;
			for (double a = 0; a < 90; a += 1.5) {
				double ang = a * Math.PI / 180;
				double sag = Math.sin((a % 22.5) / 22.5 * Math.PI) * ring * 0.8;
				web.fill((int) Math.round(Math.cos(ang) * (rr2 - sag)), (int) Math.round(Math.sin(ang) * (rr2 - sag)), 1, 1, webC);
			}
		}
		web.seal();
		Ghost g1 = new Ghost();
		g1.x = W * 0.55;
		g1.y = H * 0.74;
		g1.v = 3.2;
		g1.p = 0;
		Ghost g2 = new Ghost();
		g2.x = W * 0.2;
		g2.y = H * 0.8;
		g2.v = -2.4;
		g2.p = 2;
		ghosts.add(g1);
		ghosts.add(g2);
		// Raben auf drei zufälligen Astspitzen
		for (int i = tips.size() - 1; i > 0; i--) {
			int j = (int) Math.floor(R.next() * (i + 1));
			int[] tmp = tips.get(i);
			tips.set(i, tips.get(j));
			tips.set(j, tmp);
		}
		for (int i = 0; i < Math.min(3, tips.size()); i++) {
			Raven r = new Raven();
			r.x = tips.get(i)[0];
			r.y = tips.get(i)[1];
			r.p = R.next() * 9;
			ravens.add(r);
		}
		spiderX = (int) Math.round(W * 0.44);
		spiderLen = H * 0.18;
		spiderP = R.next() * 6;
		fogs[0] = fog(fogBand((int) Math.round(H * 0.12), 3, 7, 13, 34), H * 0.64, 2.2);
		fogs[1] = fog(fogBand((int) Math.round(H * 0.1), 4, 9, 17, 42), H * 0.77, 4.5);
		fogs[2] = fog(fogBand((int) Math.round(H * 0.09), 2, 5, 11, 50), H * 0.88, 8);
		for (int i = 0; i < 4; i++) {
			Cloud c = new Cloud();
			c.x = R.next() * W;
			c.y = H * (0.12 + i * 0.07) + R.next() * 6;
			c.len = 18 + R.next() * 30;
			c.v = 0.8 + R.next() * 1.4;
			clouds.add(c);
		}
		nextWitch = 2.5 + R.next() * 2;
		nextBats = 1 + R.next() * 2;
		nextSwarm = 8 + R.next() * 4;
		for (int i = 0; i < 6; i++) {
			eyes[i] = new Eye();
			eyes[i].next = R.next() * 3;
		}
		nextBolt = 3 + R.next() * 3;
	}

	/** Sekunden seit dem letzten Blitz (Szenenzeit) oder groß, wenn keiner war. */
	double boltAge(double t) {
		return bolt == null ? 99 : t - bolt.t0;
	}

	/**
	 * Ein Bild zur Szenenzeit {@code t} (Sekunden) in {@code dst} (W×H, deckend). {@code bolts} = Blitze erlaubt (aus bei
	 * Standbild).
	 */
	void frame(int[] dst, double t, boolean bolts) {
		out = dst;
		double dt = Math.max(0, Math.min(0.1, t - last));
		last = t;
		System.arraycopy(sky, 0, dst, 0, sky.length);
		for (Star s : stars) {
			double b = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(t * s.s + s.p)) * s.b;
			px(s.x, s.y, argb(b, 230, 233, 242));
		}
		layer(blood, 0, 0, 1);
		if ((t + seed) % 21 > 18.6) {
			int e = Math.max(3, (int) Math.round(mr * 0.4));
			int c = 0xFF2A0808;
			rect(mx - e, my - 4, 2, 3, c);
			rect(mx + e - 1, my - 4, 2, 3, c);
			for (int i = -e; i <= e; i++) {
				int yy = my + 3 + (int) Math.round(2 - 2.0 * i * i / (e * e));
				px(mx + i, yy, c);
				if (i % 2 != 0) px(mx + i, yy + 1, c);
			}
		}
		for (Cloud c : clouds) {
			c.x += c.v * dt;
			if (c.x > W + 10) c.x = -c.len - 10;
			int cc = argb(0.82, 25, 19, 44);
			rect(r(c.x), r(c.y), r(c.len), 1, cc);
			rect(r(c.x + 4), r(c.y + 1), r(c.len - 9), 1, cc);
			rect(r(c.x + 2), r(c.y - 1), r(c.len * 0.6), 1, argb(0.18, 120, 112, 160));
		}
		// Flieger am Himmel
		if (t > nextWitch) {
			Flyer f = new Flyer();
			f.k = 'w';
			f.x = -18;
			f.y = my - 6 + R.next() * 10;
			f.v = W / 9.0;
			flyers.add(f);
			nextWitch = t + 14 + R.next() * 8;
		}
		if (t > nextBats) {
			int n = 2 + (int) Math.floor(R.next() * 3);
			double y0 = H * (0.18 + R.next() * 0.25);
			for (int i = 0; i < n; i++) {
				Flyer f = new Flyer();
				f.k = 'b';
				f.x = W + 4 + i * 7;
				f.y = y0 + (R.next() - 0.5) * 8;
				f.v = -(W / 7.0) * (0.8 + R.next() * 0.4);
				f.p = R.next() * 6;
				flyers.add(f);
			}
			nextBats = t + 4 + R.next() * 4;
		}
		if (t > nextSwarm) {
			for (int i = 0; i < 12; i++) {
				Flyer f = new Flyer();
				f.k = 'b';
				f.x = hx + 2 + R.next() * 4;
				f.y = hb - 30 - R.next() * 6;
				f.v = (W / 6.0) * (0.5 + R.next() * 0.8);
				f.vy = -(4 + R.next() * 10);
				f.p = R.next() * 6;
				flyers.add(f);
			}
			nextSwarm = t + 20 + R.next() * 10;
		}
		for (int i = flyers.size() - 1; i >= 0; i--) {
			Flyer q = flyers.get(i);
			q.x += q.v * dt;
			if (q.vy != 0) q.y += q.vy * dt;
			if (q.x < -30 || q.x > W + 30) {
				flyers.remove(i);
				continue;
			}
			int c = 0xFF06050B;
			if (q.k == 'w') {
				int yy = r(q.y + Math.sin(t * 2) * 1.5);
				for (int ry = 0; ry < WITCH.length; ry++) {
					String row = WITCH[ry];
					for (int rx = 0; rx < row.length(); rx++) if (row.charAt(rx) == 'k') px(r(q.x) + rx, yy + ry, c);
				}
			} else {
				boolean up = Math.sin(t * 14 + q.p) > 0;
				int x = r(q.x), y = r(q.y + Math.sin(t * 3 + q.p) * 2);
				px(x + 1, y, c);
				if (up) {
					px(x, y - 1, c);
					px(x + 2, y - 1, c);
				} else {
					px(x, y, c);
					px(x + 2, y, c);
				}
			}
		}
		if (bolts && t > nextBolt) {
			bolt = makeBolt(t);
			nextBolt = t + 9 + R.next() * 9;
		}
		double bt = bolt != null ? t - bolt.t0 : 99;
		if (bt < 0.32) {
			for (int i = 1; i < bolt.pts.size(); i++) line(bolt.pts.get(i - 1), bolt.pts.get(i), i % 3 != 0 ? 0xFFF1ECFF : 0xFFC9B8FF);
		}
		layer(far, 0, 0, 1);
		for (Lit l : lits) {
			double a = 0.6 + 0.4 * Math.abs(Math.sin(t * 1.3 + l.p) * Math.sin(t * 4.7 + l.p));
			rect(l.x, l.y, 1, 2, argb(a, 240, 160, 70));
		}
		fogDraw(fogs[0].c, fogs[0].y, fogs[0].v, t);
		layer(mid, 0, 0, 1);
		for (Raven rv : ravens) {
			int c = 0xFF0A0812;
			rect(rv.x - 1, rv.y - 2, 3, 2, c);
			boolean turn = Math.sin(t * 0.7 + rv.p) > 0.6;
			px(rv.x + (turn ? -2 : 2), rv.y - 3, c);
			px(rv.x + (turn ? -3 : 3), rv.y - 3, c);
		}
		for (Grave g : graves) {
			double fl = 0.6 + 0.4 * Math.sin(t * 9 + g.x) * Math.sin(t * 5.3 + g.y);
			px(g.cx, g.cy - 1, argb(fl, 255, 200, 110));
			rect(g.cx - 1, g.cy - 2, 3, 3, argb(fl * 0.25, 255, 170, 80));
		}
		for (Ghost gh : ghosts) {
			gh.x += gh.v * dt;
			if (gh.x > W + 8) gh.x = -8;
			if (gh.x < -8) gh.x = W + 8;
			double a = 0.28 + 0.16 * Math.sin(t * 0.9 + gh.p);
			int yy = r(gh.y + Math.sin(t * 1.6 + gh.p) * 2);
			int dark = argb(Math.min(1, a * 1.6), 20, 14, 34);
			int light = argb(a, 230, 236, 248);
			for (int ry = 0; ry < GHOST.length; ry++) {
				String row = GHOST[ry];
				for (int rx = 0; rx < row.length(); rx++) {
					char ch = row.charAt(rx);
					if (ch == '.') continue;
					px(r(gh.x) + (gh.v < 0 ? 4 - rx : rx), yy + ry, ch == 'k' ? dark : light);
				}
			}
		}
		// Zombie-Hände aus den Gräbern
		for (int gi = 0; gi < graves.size(); gi++) {
			Grave g = graves.get(gi);
			double ph = (t * 0.22 + gi * 0.37) % 1;
			double h = ph < 0.3 ? ph / 0.3 * 6 : ph < 0.6 ? 6 : ph < 0.8 ? (0.8 - ph) / 0.2 * 6 : 0;
			if (h < 0.5) continue;
			int hx0 = r(g.x - 5), base = g.cy + 3, top = base - r(h);
			int skin = 0xFF6F8063;
			rect(hx0, top, 1, base - top, skin);
			if (h > 4) {
				boolean w = Math.sin(t * 6 + gi) > 0;
				rect(hx0 - 1, top, 3, 1, skin);
				px(hx0 - 1, top - 1, skin);
				px(hx0 + 1, top - (w ? 2 : 1), skin);
				px(hx0, top - 2, skin);
				px(hx0 + 2, top + (w ? 0 : -1), skin);
			}
			rect(hx0 - 2, base, 5, 1, 0xFF2A2130);
		}
		// Schädel auf dem Zaun mit glühenden Augen
		for (int x = fence0; x < fence1; x += 12) {
			int g = ground(x);
			rect(x - 1, g - 13, 3, 3, 0xFFD6D1C4);
			px(x, g - 10, 0xFFD6D1C4);
			int eye = argb(0.6 + 0.4 * Math.sin(t * 3 + x), 255, 59, 47);
			px(x - 1, g - 12, eye);
			px(x + 1, g - 12, eye);
		}
		// Rote Augen im Dunkeln
		for (int i = 0; i < 6; i++) {
			Eye e = eyes[i];
			if (t > e.next) {
				e.x = r(W * (0.04 + R.next() * 0.92));
				e.y = r(H * (0.72 + R.next() * 0.16));
				e.until = t + 2.5 + R.next() * 4;
				e.next = e.until + 1 + R.next() * 5;
			}
			if (t < e.until && Math.sin(t * 2.7 + i * 7) < 0.92) {
				int halo = argb(0.28, 255, 40, 30);
				rect(e.x - 1, e.y - 1, 3, 3, halo);
				rect(e.x + 2, e.y - 1, 3, 3, halo);
				px(e.x, e.y, 0xFFFF4433);
				px(e.x + 3, e.y, 0xFFFF4433);
			}
		}
		// Schattengestalt – nur im Licht des Blitzes
		if (bolt != null && bt < 1.8) {
			double a = bt < 0.6 ? 1 : 1 - (bt - 0.6) / 1.2;
			int x = bolt.fx, y = bolt.fy;
			int sc = argb(a, 2, 1, 4);
			rect(x - 1, y - 17, 3, 3, sc);
			rect(x - 1, y - 14, 3, 9, sc);
			rect(x - 1, y - 5, 1, 5, sc);
			rect(x + 1, y - 5, 1, 5, sc);
			rect(x - 2, y - 13, 1, 8, sc);
			rect(x + 2, y - 13, 1, 8, sc);
			rect(x - 3, y - 6, 1, 2, sc);
			rect(x + 3, y - 6, 1, 2, sc);
			int white = argb(a, 255, 255, 255);
			px(x - 1, y - 16, white);
			px(x + 1, y - 16, white);
		}
		fogDraw(fogs[1].c, fogs[1].y, fogs[1].v, t);
		fogDraw(fogs[0].c, fogs[1].y - 4, -fogs[0].v * 1.6, t);
		layer(fore, 0, 0, 1);
		for (Pumpkin p : pumpkins) {
			double a = 0.7 + 0.3 * Math.sin(t * 7 + p.p) * Math.sin(t * 3.1 + p.p);
			layer(glow, p.x - 14, p.y - 13, a);
		}
		if (R.next() < dt * 1.6 && !graves.isEmpty()) {
			Grave g = graves.get((int) Math.floor(R.next() * graves.size()));
			Wisp w = new Wisp();
			w.x = g.x + (R.next() - 0.5) * 3;
			w.y = g.y;
			w.life = 1;
			w.p = R.next() * 6;
			wisps.add(w);
		}
		for (int i = wisps.size() - 1; i >= 0; i--) {
			Wisp w = wisps.get(i);
			w.y -= 4 * dt;
			w.life -= dt * 0.35;
			if (w.life <= 0) {
				wisps.remove(i);
				continue;
			}
			px(r(w.x + Math.sin(t * 2 + w.p) * 1.5), r(w.y), argb(w.life * 0.8, 169, 220, 234));
		}
		fogDraw(fogs[2].c, fogs[2].y, fogs[2].v, t);
		layer(web, 0, 0, 1);
		double sl = spiderLen * (0.75 + 0.25 * Math.sin(t * 0.8 + spiderP));
		int sy = r(sl);
		rect(spiderX, 0, 1, sy, argb(0.35, 207, 213, 227));
		rect(spiderX - 1, sy, 3, 2, 0xFF0A0812);
		px(spiderX - 2, sy + 1, 0xFF0A0812);
		px(spiderX + 2, sy + 1, 0xFF0A0812);
		px(spiderX, sy + 1, 0xFFB9A2EA);
		// Roter Hauch über allem (rgba(60,0,10,.10)) – linear, darum direkt je Pixel.
		for (int i = 0; i < dst.length; i++) {
			int c = dst[i];
			int rr = (((c >> 16) & 255) * 230 + 60 * 26) >> 8;
			int gg = (((c >> 8) & 255) * 230) >> 8;
			int bb = ((c & 255) * 230 + 10 * 26) >> 8;
			dst[i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
		}
		out = null;
	}

	private Bolt makeBolt(double t) {
		Bolt b = new Bolt();
		b.t0 = t;
		double x = W * (0.08 + R.next() * 0.55), y = 0;
		b.pts.add(new double[]{x, y});
		while (y < H * 0.62) {
			y += 3 + R.next() * 6;
			x += (R.next() - 0.5) * 12;
			b.pts.add(new double[]{x, y});
		}
		b.fx = r(W * (0.1 + R.next() * 0.8));
		b.fy = ground(b.fx);
		return b;
	}

	// --- Bauen ---

	private int hill(int x) {
		return (int) Math.round((H * 0.70 + Math.sin(x * 0.021 + seed) * H * 0.035 + Math.sin(x * 0.057) * H * 0.014) / 2) * 2;
	}

	private int ground(int x) {
		return (int) Math.round(H * 0.83 + Math.sin(x * 0.03 + 2) * H * 0.02);
	}

	private static void roof(Layer l, int cx, int top, int n, int c) {
		for (int i = 0; i < n; i++) l.fill(cx - i / 2, top + i, 1 + (i / 2) * 2, 1, c);
	}

	private void tree(double x, double y, double len, double ang, int d, List<int[]> tips) {
		if (d == 2 && len > 4) tips.add(new int[]{r(x + Math.cos(ang) * len), r(y - Math.sin(ang) * len)});
		double ex = x + Math.cos(ang) * len, ey = y - Math.sin(ang) * len;
		int steps = (int) Math.floor(len);
		for (int t = 0; t <= steps; t++) {
			int px = r(x + (ex - x) * t / len), py = r(y + (ey - y) * t / len);
			mid.fill(px, py, d > 3 ? 2 : 1, 1, 0xFF0D0A17);
		}
		if (d > 0) {
			double l1 = len * (0.62 + R.next() * 0.15);
			double a1 = ang + 0.35 + R.next() * 0.35;
			tree(ex, ey, l1, a1, d - 1, tips);
			double l2 = len * (0.6 + R.next() * 0.15);
			double a2 = ang - 0.35 - R.next() * 0.35;
			tree(ex, ey, l2, a2, d - 1, tips);
			if (R.next() < 0.4) tree(ex, ey, len * 0.5, ang + (R.next() - 0.5) * 0.4, d - 2, tips);
		}
	}

	private Layer fogBand(int fh, double k1, double k2, double k3, int alpha) {
		int tw = W * 2;
		fh = Math.max(2, fh);
		Layer l = new Layer(tw, fh);
		int c = (alpha << 24) | (150 << 16) | (144 << 8) | 182;
		for (int y = 0; y < fh; y++) {
			for (int x = 0; x < tw; x++) {
				double n = 0.5 + 0.25 * Math.sin(2 * Math.PI * k1 * x / tw + seed) + 0.17 * Math.sin(2 * Math.PI * k2 * x / tw + 1.7)
						+ 0.08 * Math.sin(2 * Math.PI * k3 * x / tw + 4);
				double fall = 1 - Math.abs(y - fh / 2.0) / (fh / 2.0);
				if (bay(x, y) < n * fall * 0.85) l.px[y * tw + x] = c;
			}
		}
		l.seal();
		return l;
	}

	private static Fog fog(Layer c, double y, double v) {
		Fog f = new Fog();
		f.c = c;
		f.y = y;
		f.v = v;
		return f;
	}

	// --- Zeichnen ins Bild ---

	private void fogDraw(Layer c, double y, double v, double t) {
		int tw = W * 2;
		int off = (int) Math.floor((((t * v) % tw) + tw) % tw);
		layer(c, -off, r(y), 1);
		layer(c, tw - off, r(y), 1);
	}

	private void layer(Layer l, int dx, int dy, double alpha) {
		int[] dst = out;
		int[] idx = l.idx;
		int lw = l.w;
		int am = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 256);
		for (int k = 0; k < idx.length; k++) {
			int i = idx[k];
			int x = i % lw + dx;
			if (x < 0 || x >= W) continue;
			int y = i / lw + dy;
			if (y < 0 || y >= H) continue;
			int s = l.px[i];
			if (am < 256) s = (((s >>> 24) * am >> 8) << 24) | (s & 0xFFFFFF);
			int o = y * W + x;
			dst[o] = blendOpaque(dst[o], s);
		}
	}

	private void px(int x, int y, int argb) {
		if (x < 0 || y < 0 || x >= W || y >= H) return;
		int o = y * W + x;
		out[o] = blendOpaque(out[o], argb);
	}

	private void rect(int x, int y, int w, int h, int argb) {
		int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(W, x + w), y1 = Math.min(H, y + h);
		for (int yy = y0; yy < y1; yy++) {
			int row = yy * W;
			for (int xx = x0; xx < x1; xx++) out[row + xx] = blendOpaque(out[row + xx], argb);
		}
	}

	private void line(double[] a, double[] b, int c) {
		int n = (int) Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
		if (n <= 0) n = 1;
		for (int i = 0; i <= n; i++) px(r(a[0] + (b[0] - a[0]) * i / n), r(a[1] + (b[1] - a[1]) * i / n), c);
	}

	// --- Farben ---

	static int r(double v) {
		return (int) Math.round(v);
	}

	static double bay(int x, int y) {
		return Sprites.bayer(x, y);
	}

	static int argb(double a, int r, int g, int b) {
		int al = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
		return (al << 24) | (r << 16) | (g << 8) | b;
	}

	private static double[] hex(int rgb) {
		return new double[]{(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
	}

	private static double[] stop(double t, int rgb) {
		return new double[]{t, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
	}

	private static double[] rgb(int rgb) {
		return hex(rgb);
	}

	private static double[] rgbOf(double[] stop) {
		return new double[]{stop[1], stop[2], stop[3]};
	}

	private static double[] mix(double[] a, double[] b, double k) {
		return new double[]{a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k};
	}

	private static int opaque(double[] c) {
		return 0xFF000000 | (clamp(c[0]) << 16) | (clamp(c[1]) << 8) | clamp(c[2]);
	}

	private static int clamp(double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	/** Quelle über deckendes Ziel. */
	static int blendOpaque(int d, int s) {
		int a = s >>> 24;
		if (a == 255) return s;
		if (a == 0) return d;
		int ia = 255 - a;
		int r = (((s >> 16) & 255) * a + ((d >> 16) & 255) * ia + 127) / 255;
		int g = (((s >> 8) & 255) * a + ((d >> 8) & 255) * ia + 127) / 255;
		int b = ((s & 255) * a + (d & 255) * ia + 127) / 255;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Quelle über (evtl. durchsichtiges) Ziel, nicht vormultipliziert (wie Canvas source-over). */
	static int over(int d, int s) {
		int sa = s >>> 24;
		if (sa == 255) return s;
		if (sa == 0) return d;
		int da = d >>> 24;
		if (da == 0) return s;
		double a = sa / 255.0, b = da / 255.0 * (1 - a), oa = a + b;
		int r = (int) Math.round((((s >> 16) & 255) * a + ((d >> 16) & 255) * b) / oa);
		int g = (int) Math.round((((s >> 8) & 255) * a + ((d >> 8) & 255) * b) / oa);
		int bl = (int) Math.round(((s & 255) * a + (d & 255) * b) / oa);
		return ((int) Math.round(oa * 255) << 24) | (r << 16) | (g << 8) | bl;
	}
}
