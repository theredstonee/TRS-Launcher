package dev.theredstonee.trsclient.core.minigames;

import dev.theredstonee.trsclient.core.minigames.GameTextures.Tex;
import dev.theredstonee.trsclient.core.qol.QolSound;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Loren-Rausch: Stollen je Pixel, Effekte und Ton, Übertragung von {@code app/utils/minigames/lorenView.ts}. Das Bild
 * entsteht in {@link #pixels} (ARGB, 400×225) und geht je Bild als Textur zur Grafikkarte. Beeinflusst das Spiel nicht,
 * darum dürfen hier Zufall und Kommazahlen stehen. Im Rechenweg entstehen keine Objekte je Pixel und kein {@code %} auf
 * Kommazahlen, beides wäre in Java spürbar teurer als in JavaScript.
 */
public final class LorenView {
	public static final int WIDTH = 400;
	public static final int HEIGHT = 225;
	private static final int F = 225;
	private static final int CX = 200;
	private static final int CY = 82;
	private static final double HW = 4;
	private static final double HH = 4.4;
	private static final double EYE = 2.65;
	private static final double ZFAR = 64;
	private static final double CAM_BACK = 4.3;
	private static final int SUPPORT = 14;
	private static final int ZONE_LEN = 420;
	private static final int[] LANES = {-2, 0, 2};
	private static final int POW_ROWS = 90;

	private static final int ORE_PLAIN = 0;
	private static final int ORE_HOT = 1;
	private static final int ORE_REDSTONE = 2;

	private static final class Zone {
		final String name;
		final Tex floor;
		final Tex wall;
		final Tex[] ores = new Tex[4];
		final int[] oreKind = new int[4];
		final Tex wood;
		final Tex roof;
		final int[] amb;
		final int[] torch;
		final Tex floorRail;
		final Tex floorPow;
		final Tex floorOn;

		Zone(GameTextures tx, GameSkin.ZoneDef def, Tex rail, Tex pow, Tex on) {
			this.name = def.name;
			this.floor = tx.block(def.floor);
			this.wall = tx.block(def.wall);
			for (int i = 0; i < 4; i++) {
				String ore = def.ores[i];
				ores[i] = tx.block(ore);
				oreKind[i] = "lava_still".equals(ore) || "magma".equals(ore) ? ORE_HOT : ore.contains("redstone") ? ORE_REDSTONE : ORE_PLAIN;
			}
			this.wood = tx.block(def.wood);
			this.roof = tx.block(def.roof);
			this.amb = def.amb;
			this.torch = def.torch;
			this.floorRail = over(floor, rail);
			this.floorPow = over(floor, pow);
			this.floorOn = over(floor, on);
		}
	}

	private static final class Spark {
		float x;
		float y;
		float w;
		float vx;
		float vy;
		float vw;
		float life;
		int c;
		float s;
		float g;
	}

	private static Tex over(Tex a, Tex b) {
		int[] d = new int[256];
		for (int i = 0; i < 256; i++) d[i] = (b.d[i] >>> 24) > 127 ? b.d[i] : a.d[i];
		return new Tex(16, 16, d);
	}

	private static final GameSkin.ZoneDef MINE = zone("mine", "stone", "stone", new String[] {"coal_ore", "iron_ore", "redstone_ore", "coal_ore"},
			"oak_log", "oak_planks", new int[] {46, 42, 40}, new int[] {300, 205, 110});
	private static final GameSkin.ZoneDef DEEP = zone("deepslate", "deepslate", "deepslate",
			new String[] {"deepslate_redstone_ore", "deepslate_redstone_ore", "diamond_ore", "iron_ore"}, "deepslate_bricks", "deepslate_bricks",
			new int[] {34, 36, 46}, new int[] {250, 180, 130});
	private static final GameSkin.ZoneDef NETHER = zone("nether", "netherrack", "netherrack",
			new String[] {"nether_bricks", "magma", "lava_still", "nether_bricks"}, "nether_bricks", "nether_bricks",
			new int[] {56, 26, 20}, new int[] {215, 125, 60});

	private static GameSkin.ZoneDef zone(String name, String floor, String wall, String[] ores, String wood, String roof, int[] amb, int[] torch) {
		return GameSkin.zoneDef(name, floor, wall, ores, wood, roof, amb, torch);
	}

	public final int[] pixels = new int[WIDTH * HEIGHT];
	private final double[] nx = new double[WIDTH];
	private final byte[][] powTab = new byte[3][POW_ROWS];
	private int powBase;
	private final GameSkin skin;
	private final GameSound sound;
	private final Random random = new Random();
	private final List<Spark> parts = new ArrayList<Spark>();
	private Zone[] zones;
	private Tex tnt;
	private Tex tntTop;
	private Tex planks;
	private Tex log;
	private Tex torch;
	private Tex redstone;
	private Tex creeperHead;
	private Tex creeperBody;
	private Tex creeperFoot;
	private Tex cartSide;
	private Tex cartFloor;
	private Tex steveHead;
	private Tex steveBody;
	private Tex steveArm;
	private double shake;
	private double flash;
	private double red;
	private double lx = 1;
	private double rollT;
	private double camx;
	private double bx;
	private double by;
	private double eye = EYE;
	private double shx;
	private double shy;
	/** Ergebnis von {@link #light}. */
	private double lr;
	private double lg;
	private double lb;
	/** Wackeln und Blitze zeigen (Einstellung). */
	public boolean effects = true;

	private final double[] itemZ = new double[32];
	private final int[] itemKind = new int[32];
	private final int[] itemIndex = new int[32];
	private int itemCount;

	public LorenView(GameTextures textures, GameSkin skin, GameSound sound) {
		this.skin = skin;
		this.sound = sound;
		prepare(textures);
	}

	/** Liest die Texturen neu, zum Beispiel nach einem Ressourcenpaket-Wechsel. */
	public void reload(GameTextures textures) {
		prepare(textures);
	}

	private void prepare(GameTextures tx) {
		Tex rail = tx.block("rail");
		Tex pow = tx.block("powered_rail");
		Tex on = tx.block("powered_rail_on");
		zones = new Zone[] {new Zone(tx, skin.zone != null ? skin.zone : MINE, rail, pow, on), new Zone(tx, DEEP, rail, pow, on),
				new Zone(tx, NETHER, rail, pow, on)};
		tnt = tx.block("tnt_side");
		tntTop = tx.block("tnt_top");
		planks = tx.block("oak_planks");
		log = tx.block("oak_log");
		torch = tx.block("torch");
		redstone = tx.block("redstone");
		creeperHead = tx.entity("creeper", 8, 8, 8, 8, 0xFF4FA04F);
		creeperBody = tx.entity("creeper", 20, 20, 8, 12, 0xFF4FA04F);
		creeperFoot = tx.entity("creeper", 4, 20, 4, 6, 0xFF3F803F);
		cartSide = tx.entity("minecart", 2, 2, 16, 8, 0xFF6F6F70);
		cartFloor = tx.entity("minecart", 2, 12, 20, 16, 0xFF5A5A5C);
		steveHead = tx.entity("steve", 24, 8, 8, 8, 0xFF4A3421);
		steveBody = tx.entity("steve", 32, 20, 8, 12, 0xFF2E8E8E);
		steveArm = tx.entity("steve", 52, 20, 4, 12, 0xFF2E8E8E);
	}

	private static int ifloor(double d) {
		int i = (int) d;
		return d < i ? i - 1 : i;
	}

	private Zone zoneAt(double z) {
		int i = ifloor((z > 0 ? z : 0) / ZONE_LEN);
		return zones[i % 3];
	}

	/** Name der aktuellen Gegend für die Anzeige. */
	public String zoneName(Loren game) {
		return zoneAt(game.pos / (double) Loren.UNIT).name;
	}

	public void reset() {
		parts.clear();
		lx = 0;
		shake = 0;
		flash = 0;
		red = 0;
		rollT = 0;
		sound.clear();
	}

	private void fillPow(Loren g, double zStart) {
		powBase = ifloor(zStart);
		for (int i = 0; i < POW_ROWS; i++) {
			int ri = Math.floorDiv(powBase + i, Loren.ROW_GAP);
			Loren.Row r = ri >= 0 ? g.row(ri) : null;
			for (int l = 0; l < 3; l++) powTab[l][i] = (byte) (r != null && r.pow[l] ? 1 : 0);
		}
	}

	private void drawTunnel(Loren g, double pz) {
		int on = (g.tick >> 3) & 1;
		int[] buf = pixels;
		for (int sx = 0; sx < WIDTH; sx++) nx[sx] = (sx - CX + shx) / F;
		double camx = this.camx;
		double bx = this.bx;
		double by = this.by;
		double eye = this.eye;
		double red110 = red * 110;
		double fr = flash * 200;
		double fg = flash * 170;
		double fb = flash * 130;
		for (int sy = 0; sy < HEIGHT; sy++) {
			double ny0 = (CY - sy + shy) / F;
			int o = sy * WIDTH;
			for (int sx = 0; sx < WIDTH; sx++, o++) {
				double nx0 = nx[sx];
				double z = 14;
				double dx = 0;
				double dy = 0;
				double tf = 0;
				double tw = 0;
				for (int it = 0; it < 2; it++) {
					dx = nx0 - bx * z;
					dy = ny0 - by * z;
					tf = dy < -1e-4 ? eye / -dy : dy > 1e-4 ? (HH - eye) / dy : 1e9;
					tw = dx > 1e-4 ? (HW - camx) / dx : dx < -1e-4 ? (HW + camx) / -dx : 1e9;
					z = tf < tw ? tf : tw;
					if (z > ZFAR) z = ZFAR;
				}
				if (z >= ZFAR) {
					buf[o] = 0xFF020304;
					continue;
				}
				double wz = pz + z;
				int zb = ifloor(wz);
				Zone zone = zoneAt(wz);
				double fz = wz - zb;
				Tex tex;
				int u;
				int v;
				int emit = 0;
				double lat = 1;
				if (tf <= tw) {
					double wx = camx + dx * z;
					if (dy < 0) {
						int q = ifloor(wx + 0.5);
						u = (int) ((wx + 0.5 - q) * 16);
						v = 15 - (int) (fz * 16);
						if (q == -2 || q == 0 || q == 2) {
							int idx = zb - powBase;
							boolean powered = idx >= 0 && idx < POW_ROWS && powTab[q / 2 + 1][idx] != 0;
							tex = powered ? zone.floorOn : zone.floorRail;
							if (powered) emit = 40;
						} else {
							tex = zone.floor;
						}
					} else {
						int q = ifloor(wx);
						u = (int) ((wx - q) * 16);
						v = 15 - (int) (fz * 16);
						tex = zb % SUPPORT < 1 ? zone.roof : zone.wall;
					}
					lat = (wx < 0 ? -wx : wx) / HW;
				} else {
					double y = eye + dy * z;
					int yi = (int) y;
					u = (int) (fz * 16);
					v = 15 - (int) ((y - yi) * 16);
					if (zb % SUPPORT < 1) {
						tex = zone.wood;
					} else {
						int h = (zb * 73856093) ^ ((yi + 3) * 19349663) ^ (dx > 0 ? 83492791 : 0);
						if (Integer.remainderUnsigned(h, 100) < 13) {
							int n = (h >>> 8) & 3;
							tex = zone.ores[n];
							int kind = zone.oreKind[n];
							if (kind == ORE_HOT) emit = 170;
							else if (kind == ORE_REDSTONE) emit = on != 0 ? 38 : 16;
						} else {
							tex = zone.wall;
						}
					}
				}
				int c = tex.d[(v & 15) * 16 + (u & 15)];
				double m = wz - SUPPORT * Math.floor(wz / SUPPORT);
				double dt = m - 7.5;
				if (dt < 0) dt = -dt;
				double tl = dt < 7.5 ? 1 - dt / 7.5 : 0;
				double tq = tl * tl * lat * 0.6;
				double fog = 1 / (1 + z * z * 0.0048);
				double lr = (zone.amb[0] + zone.torch[0] * tq + emit + red110) * fog + fr;
				double lg = (zone.amb[1] + zone.torch[1] * tq + emit * 0.34) * fog + fg;
				double lb = (zone.amb[2] + zone.torch[2] * tq + emit * 0.16) * fog + fb;
				int r = (int) (((c >> 16) & 255) * lr) >> 7;
				int gg = (int) (((c >> 8) & 255) * lg) >> 7;
				int b = (int) ((c & 255) * lb) >> 7;
				if (r > 255) r = 255;
				if (gg > 255) gg = 255;
				if (b > 255) b = 255;
				buf[o] = 0xFF000000 | (r << 16) | (gg << 8) | b;
			}
		}
	}

	private static int shade(int c, double lr, double lg, double lb) {
		int r = (int) (((c >> 16) & 255) * lr) >> 7;
		int g = (int) (((c >> 8) & 255) * lg) >> 7;
		int b = (int) ((c & 255) * lb) >> 7;
		if (r > 255) r = 255;
		if (g > 255) g = 255;
		if (b > 255) b = 255;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Licht an der Stelle; Ergebnis in {@link #lr}, {@link #lg}, {@link #lb}. */
	private void light(double z, double wz) {
		Zone zone = zoneAt(wz);
		double m = wz - SUPPORT * Math.floor(wz / SUPPORT);
		double dt = Math.abs(m - 7.5);
		double tl = dt < 7.5 ? 1 - dt / 7.5 : 0;
		double tq = tl * tl * 0.55;
		double fog = 1 / (1 + z * z * 0.0048);
		lr = (zone.amb[0] + 32 + zone.torch[0] * tq) * fog + flash * 200;
		lg = (zone.amb[1] + 30 + zone.torch[1] * tq) * fog + flash * 170;
		lb = (zone.amb[2] + 28 + zone.torch[2] * tq) * fog + flash * 130;
	}

	private double px(double x, double z) {
		return CX - shx + F * ((x - camx) / z + bx * z);
	}

	private double py(double h, double z) {
		return CY + shy - F * ((h - eye) / z + by * z);
	}

	/** Fläche zum Betrachter; {@code lit} false = ohne Licht (Fackeln). */
	private void face(Tex t, double x0, double x1, double h0, double h1, double z, boolean lit, double r, double g, double b) {
		if (z < 0.5) return;
		double a = px(x0, z);
		double e = px(x1, z);
		double top = py(h1, z);
		double bot = py(h0, z);
		int xs = Math.max(0, (int) Math.ceil(a));
		int xe = Math.min(WIDTH, (int) Math.ceil(e));
		int ys = Math.max(0, (int) Math.ceil(top));
		int ye = Math.min(HEIGHT, (int) Math.ceil(bot));
		double du = t.w / (e - a);
		double dv = t.h / (bot - top);
		for (int y = ys; y < ye; y++) {
			int v = Math.min(t.h - 1, (int) ((y - top) * dv));
			for (int x = xs; x < xe; x++) {
				int c = t.d[v * t.w + Math.min(t.w - 1, (int) ((x - a) * du))];
				if ((c >>> 24) < 128) continue;
				pixels[y * WIDTH + x] = lit ? shade(c, r, g, b) : c | 0xFF000000;
			}
		}
	}

	private void topFace(Tex t, double x0, double x1, double h, double z0, double z1, double base) {
		if (eye <= h) return;
		double near = Math.max(z0, 0.7);
		int ys = Math.max(0, (int) Math.ceil(py(h, z1)));
		int ye = Math.min(HEIGHT, (int) Math.ceil(py(h, near)));
		for (int y = ys; y < ye; y++) {
			double z = F * (eye - h) / Math.max(0.1, y - CY - shy);
			if (z < near || z > z1) continue;
			light(z, base + z);
			double r = lr * 1.12;
			double g = lg * 1.12;
			double b = lb * 1.12;
			double a = px(x0, z);
			double e = px(x1, z);
			int xs = Math.max(0, (int) Math.ceil(a));
			int xe = Math.min(WIDTH, (int) Math.ceil(e));
			double du = t.w / (e - a);
			int v = Math.min(t.h - 1, Math.max(0, (int) (((z - z0) / (z1 - z0)) * t.h)));
			for (int x = xs; x < xe; x++) {
				int c = t.d[v * t.w + Math.min(t.w - 1, (int) ((x - a) * du))];
				if ((c >>> 24) > 127) pixels[y * WIDTH + x] = shade(c, r, g, b);
			}
		}
	}

	private void sideFace(Tex t, double x, double h0, double h1, double z0, double z1, double base) {
		double near = Math.max(z0, 0.7);
		double a = px(x, near);
		double e = px(x, z1);
		int xs = Math.max(0, (int) Math.ceil(Math.min(a, e)));
		int xe = Math.min(WIDTH, (int) Math.ceil(Math.max(a, e)));
		if (xe - xs < 1) return;
		for (int sx = xs; sx < xe; sx++) {
			double k = (sx - a) / (e - a);
			double z = 1 / (1 / near + k * (1 / z1 - 1 / near));
			light(z, base + z);
			double r = lr * 0.72;
			double g = lg * 0.72;
			double b = lb * 0.72;
			double top = py(h1, z);
			double bot = py(h0, z);
			int ys = Math.max(0, (int) Math.ceil(top));
			int ye = Math.min(HEIGHT, (int) Math.ceil(bot));
			int u = Math.min(t.w - 1, Math.max(0, (int) (((z - z0) / (z1 - z0)) * t.w)));
			double dv = t.h / (bot - top);
			for (int y = ys; y < ye; y++) {
				int c = t.d[Math.min(t.h - 1, (int) ((y - top) * dv)) * t.w + u];
				if ((c >>> 24) > 127) pixels[y * WIDTH + sx] = shade(c, r, g, b);
			}
		}
	}

	private void box(Tex front, Tex top, double x0, double x1, double h0, double h1, double z0, double z1, double pz) {
		light(z0, pz + z0);
		double r = lr;
		double g = lg;
		double b = lb;
		if (camx < x0) sideFace(front, x0, h0, h1, z0, z1, pz);
		else if (camx > x1) sideFace(front, x1, h0, h1, z0, z1, pz);
		topFace(top, x0, x1, h1, z0, z1, pz);
		face(front, x0, x1, h0, h1, z0, true, r, g, b);
	}

	private void drawObject(int o, int lane, double z, double pz, int tick) {
		double x = LANES[lane];
		light(z, pz + z);
		double r = lr;
		double g = lg;
		double b = lb;
		if (o == Loren.O_TNT) {
			box(tnt, tntTop, x - 0.5, x + 0.5, 0, 1, z, z + 1, pz);
		} else if (o == Loren.O_CRATE) {
			box(planks, planks, x - 0.8, x + 0.8, 0, 1.2, z, z + 1.4, pz);
			box(planks, planks, x - 0.55, x + 0.55, 1.2, 2.3, z + 0.1, z + 1.2, pz);
		} else if (o == Loren.O_BEAM) {
			face(log, x - 1, x - 0.8, 0, 1.05, z, true, r, g, b);
			face(log, x + 0.8, x + 1, 0, 1.05, z, true, r, g, b);
			box(log, planks, x - 1, x + 1, 1.05, 1.75, z, z + 0.8, pz);
		} else if (o == Loren.O_CREEPER) {
			boolean near = z < 16;
			boolean hot = near && ((tick >> 2) & 1) != 0;
			double cr = hot ? r * 3 + 120 : r;
			double cg = hot ? g * 3 + 120 : g;
			double cb = hot ? b * 3 + 120 : b;
			double s = near ? 1.06 : 1;
			face(creeperFoot, x - 0.4, x - 0.02, 0, 0.36, z, true, cr, cg, cb);
			face(creeperFoot, x + 0.02, x + 0.4, 0, 0.36, z, true, cr, cg, cb);
			face(creeperBody, x - 0.36 * s, x + 0.36 * s, 0.36, 1.38, z, true, cr, cg, cb);
			face(creeperHead, x - 0.42 * s, x + 0.42 * s, 1.38, 2.22 * s, z, true, cr, cg, cb);
		}
	}

	private void drawPlayer(Loren g, double pz, double x, double hop) {
		double z = CAM_BACK;
		boolean duck = g.duckT > 0;
		double top = 0.68;
		box(cartSide, cartFloor, x - 0.56, x + 0.56, hop + 0.1, hop + top, z - 0.15, z + 1.05, pz);
		light(z, pz + z);
		double k = duck ? 0.9 : 1;
		double r = lr * k;
		double gg = lg * k;
		double b = lb * k;
		double shift = duck ? -0.5 : 0;
		double base = hop + top + shift;
		face(steveArm, x - 0.44, x - 0.25, base + 0.02, base + 0.68, z + 0.28, true, r, gg, b);
		face(steveArm, x + 0.25, x + 0.44, base + 0.02, base + 0.68, z + 0.28, true, r, gg, b);
		face(steveBody, x - 0.25, x + 0.25, base, base + 0.72, z + 0.3, true, r, gg, b);
		face(steveHead, x - 0.27, x + 0.27, base + 0.72, base + 1.25, z + 0.3, true, r, gg, b);
	}

	private void drawSparks(double pz) {
		for (int i = 0; i < parts.size(); i++) {
			Spark p = parts.get(i);
			double z = p.w - pz;
			if (z < 0.6 || z > ZFAR) continue;
			int a = (int) px(p.x, z);
			int b = (int) py(p.y, z);
			int s = Math.max(1, (int) Math.round(p.s * F / z));
			int ye = Math.min(HEIGHT, b + s);
			int xe = Math.min(WIDTH, a + s);
			for (int y = Math.max(0, b); y < ye; y++) {
				for (int x = Math.max(0, a); x < xe; x++) pixels[y * WIDTH + x] = p.c;
			}
		}
	}

	private void speedLines(double speedK, boolean turbo) {
		if (speedK <= 0.5 && !turbo) return;
		int n = 6 + ifloor((speedK - 0.5) * 40) + (turbo ? 26 : 0);
		int addR = turbo ? 110 : 34;
		int addG = turbo ? 40 : 34;
		int addB = turbo ? 26 : 34;
		for (int i = 0; i < n; i++) {
			double a = random.nextDouble() * 6.283;
			double cos = Math.cos(a);
			double sin = Math.sin(a);
			double r0 = 90 + random.nextDouble() * 150;
			double len = 8 + 34 * speedK;
			for (int k = 0; k < len; k++) {
				double r = r0 + k;
				int x = (int) (CX + cos * r);
				int y = (int) (CY + sin * r * 0.75);
				if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) continue;
				int o = y * WIDTH + x;
				int c = pixels[o];
				pixels[o] = 0xFF000000 | (Math.min(255, ((c >> 16) & 255) + addR) << 16)
						| (Math.min(255, ((c >> 8) & 255) + addG) << 8) | Math.min(255, (c & 255) + addB);
			}
		}
	}

	private void item(double z, int kind, int index) {
		if (itemCount >= itemZ.length) return;
		// Sortiert einfügen, fern zuerst.
		int at = itemCount++;
		while (at > 0 && itemZ[at - 1] < z) {
			itemZ[at] = itemZ[at - 1];
			itemKind[at] = itemKind[at - 1];
			itemIndex[at] = itemIndex[at - 1];
			at--;
		}
		itemZ[at] = z;
		itemKind[at] = kind;
		itemIndex[at] = index;
	}

	/** Rechnet ein Bild in {@link #pixels}. */
	public void draw(Loren g) {
		double pz = g.pos / (double) Loren.UNIT - CAM_BACK;
		double k = (Loren.LANE_T - g.laneT) / (double) Loren.LANE_T;
		double e = g.laneT > 0 ? k * k * (3 - 2 * k) : 1;
		double xp = LANES[g.fromLane] + (LANES[g.lane] - LANES[g.fromLane]) * e;
		lx += (xp * 0.72 - lx) * 0.22;
		camx = lx;
		double t = pz * 0.0085 + (g.seed % 1000);
		bx = (Math.sin(t) * 0.55 + Math.sin(t * 2.3 + 1) * 0.35) * 0.0016;
		by = Math.sin(t * 1.3 + 2) * 0.4 * 0.0009;
		double hop = g.height() / (double) Loren.UNIT;
		double speedK = Math.min(1, (g.speed - Loren.BASE_SPEED) / (double) (Loren.MAX_SPEED + 34 - Loren.BASE_SPEED));
		double wobble = Math.sin(g.pos / (double) Loren.UNIT * 3.1) * 0.008 * (1 + speedK * 2);
		eye = EYE + hop * 0.32 - (g.duckT > 0 ? 0.28 : 0) + wobble;
		double strength = effects ? shake : 0;
		shx = (random.nextDouble() - 0.5) * strength;
		shy = (random.nextDouble() - 0.5) * strength;
		fillPow(g, pz);
		drawTunnel(g, pz);
		itemCount = 0;
		int near = ifloor(pz / Loren.ROW_GAP);
		for (int i = near + 9; i >= Math.max(0, near); i--) {
			double z = i * Loren.ROW_GAP - pz;
			if (z < 0.7 || z > ZFAR) continue;
			item(z, 0, i);
		}
		int support = ifloor(pz / SUPPORT);
		for (int i = support + 4; i >= Math.max(0, support); i--) {
			double z = i * SUPPORT + 7.5 - pz;
			if (z < 1 || z > ZFAR) continue;
			item(z, 1, i);
		}
		item(CAM_BACK, 2, 0);
		for (int n = 0; n < itemCount; n++) {
			double z = itemZ[n];
			int kind = itemKind[n];
			if (kind == 0) {
				drawRow(g, itemIndex[n], z, pz);
			} else if (kind == 1) {
				face(torch, -HW + 0.01, -HW + 0.7, 1.7, 2.4, z, false, 0, 0, 0);
				face(torch, HW - 0.7, HW - 0.01, 1.7, 2.4, z, false, 0, 0, 0);
			} else {
				drawPlayer(g, pz, xp, hop);
			}
		}
		drawSparks(pz);
		speedLines(speedK, g.turbo > 0);
		if (g.stagger > 0) tint(0xFF1E0A, g.stagger / (double) Loren.STAGGER_T * 0.2);
		if (g.turbo > 0) turboEdge(Math.min(1, g.turbo / 60.0) * 0.44);
	}

	private void drawRow(Loren g, int i, double z, double pz) {
		Loren.Row r = g.row(i);
		for (int l = 0; l < 3; l++) {
			if (r.o[l] != 0 && !r.used[l]) drawObject(r.o[l], l, z, pz, g.tick);
			if (r.item[l] && !r.got[l]) {
				light(z, pz + z);
				double bob = 0.75 + Math.sin(g.tick * 0.1 + i) * 0.12;
				face(redstone, LANES[l] - 0.42, LANES[l] + 0.42, bob, bob + 0.84, z, true, lr + 90, lg + 40, lb + 30);
			}
		}
	}

	private void tint(int rgb, double alpha) {
		int a = (int) Math.round(alpha * 256);
		if (a <= 0) return;
		int inv = 256 - a;
		int sr = ((rgb >> 16) & 255) * a;
		int sg = ((rgb >> 8) & 255) * a;
		int sb = (rgb & 255) * a;
		for (int i = 0; i < pixels.length; i++) {
			int c = pixels[i];
			pixels[i] = 0xFF000000 | ((((c >> 16) & 255) * inv + sr) >> 8 << 16) | ((((c >> 8) & 255) * inv + sg) >> 8 << 8)
					| (((c & 255) * inv + sb) >> 8);
		}
	}

	/** Roter Rand beim Turbo: von Radius 60 bis 260 um den Fluchtpunkt zunehmend. */
	private void turboEdge(double alpha) {
		for (int y = 0; y < HEIGHT; y++) {
			double dy = y - CY;
			int o = y * WIDTH;
			for (int x = 0; x < WIDTH; x++) {
				double dx = x - CX;
				double d2 = dx * dx + dy * dy;
				if (d2 <= 3600) continue;
				double d = (Math.sqrt(d2) - 60) / 200;
				if (d > 1) d = 1;
				int a = (int) (d * alpha * 256);
				int inv = 256 - a;
				int c = pixels[o + x];
				pixels[o + x] = 0xFF000000 | ((((c >> 16) & 255) * inv + 255 * a) >> 8 << 16) | ((((c >> 8) & 255) * inv + 40 * a) >> 8 << 8)
						| (((c & 255) * inv + 10 * a) >> 8);
			}
		}
	}

	private void spark(Loren g, double x, double y, int c, int n, double sp, double life, double s) {
		double w = g.pos / (double) Loren.UNIT;
		for (int i = 0; i < n; i++) {
			Spark p = new Spark();
			p.x = (float) (x + (random.nextDouble() - 0.5) * 0.5);
			p.y = (float) (y + random.nextDouble() * 0.3);
			p.w = (float) (w + random.nextDouble() * 0.6);
			p.vx = (float) ((random.nextDouble() - 0.5) * sp);
			p.vy = (float) (random.nextDouble() * sp * 0.8);
			p.vw = (float) (random.nextDouble() * sp);
			p.life = (float) life;
			p.c = c;
			p.s = (float) (s * (0.6 + random.nextDouble()));
			p.g = 12f;
			parts.add(p);
		}
	}

	public void update(float dt, Loren g) {
		shake *= Math.pow(0.002, dt);
		if (shake < 0.2) shake = 0;
		flash = Math.max(0, flash - dt * 2.6);
		if (!effects) flash = 0;
		red = Math.max(0, red - dt * 2);
		for (int i = parts.size() - 1; i >= 0; i--) {
			Spark p = parts.get(i);
			p.life -= dt;
			if (p.life <= 0f) {
				int last = parts.size() - 1;
				parts.set(i, parts.get(last));
				parts.remove(last);
				continue;
			}
			p.vy -= p.g * dt;
			p.x += p.vx * dt;
			p.y += p.vy * dt;
			p.w += p.vw * dt;
			if (p.y < 0f) {
				p.y = 0f;
				p.vy *= -0.4f;
			}
		}
		while (parts.size() > 500) parts.remove(parts.size() - 1);
		if (g == null || g.finished()) return;
		double x = LANES[g.lane];
		Loren.Row row = g.row(ifloor(g.pos / (double) Loren.UNIT / Loren.ROW_GAP));
		double side = x + (random.nextDouble() < 0.5 ? -0.45 : 0.45);
		if ((row.pow[g.lane] || g.turbo > 0) && g.jumpT == 0 && random.nextDouble() < 0.75) spark(g, side, 0.05, 0xFFFF502A, 1, 3, 0.3, 0.05);
		else if (g.speed > 60 && g.jumpT == 0 && random.nextDouble() < 0.25) spark(g, side, 0.05, 0xFFFFC050, 1, 2.4, 0.24, 0.04);
	}

	/** Rollgeräusch der Lore: Im Spiel gibt es dafür einen Klang, der alle paar Sekunden neu einsetzt. */
	public void updateSound(float dt, Loren g, boolean active) {
		boolean rolling = g != null && active && !g.finished() && g.jumpT == 0;
		if (!rolling) {
			rollT = 0;
			return;
		}
		rollT -= dt;
		if (rollT <= 0) {
			rollT = 2.6;
			sound.play(QolSound.MINECART, 0.1f + Math.min(0.2f, g.speed / 400f), 0.8f + Math.min(0.6f, g.speed / 200f));
		}
	}

	private void boom(Loren g, double k) {
		sound.play(QolSound.EXPLODE, (float) Math.min(1, 0.45 * k), 1.1f - (float) (k * 0.2));
		shake = Math.max(shake, 12 * k);
		flash = Math.max(flash, 0.5 * k);
		double x = LANES[g.lane];
		spark(g, x, 0.8, 0xFFFFB34A, 26, 7, 0.6, 0.1);
		spark(g, x, 0.8, 0xFF4A3A3A, 24, 4, 1, 0.15);
		spark(g, x, 0.8, 0xFFE0261C, 12, 6, 0.7, 0.07);
	}

	public void onEvent(Loren.EventType type, Loren g) {
		switch (type) {
			case LEVER:
				sound.play(QolSound.LEVER, 0.45f, 1.1f);
				break;
			case JUMP:
				sound.play(QolSound.PISTON, 0.22f, 1.7f);
				break;
			case LAND:
				sound.play(QolSound.STONE_PLACE, 0.4f, 0.8f);
				shake = Math.max(shake, 3);
				spark(g, LANES[g.lane], 0.05, 0xFFFFC060, 8, 3, 0.3, 0.05);
				break;
			case DUCK:
				sound.play(QolSound.HAT, 0.2f, 0.7f);
				break;
			case ITEM:
				sound.play(QolSound.ORB, 0.35f, 1.4f);
				sound.later(0.055f, QolSound.PLING, 0.2f, 1.7f);
				spark(g, LANES[g.lane], 0.9, 0xFFFF2020, 12, 3, 0.4, 0.05);
				break;
			case DODGE:
				sound.play(QolSound.PLING, 0.15f, 1.5f);
				break;
			case TURBO:
				sound.play(QolSound.PISTON, 0.6f, 0.6f);
				sound.later(0.1f, QolSound.ORB, 0.5f, 0.8f);
				flash = 0.5;
				shake = Math.max(shake, 7);
				break;
			case SMASH:
				boom(g, 0.7);
				break;
			case HIT:
				boom(g, 1);
				red = 1;
				break;
			case CRASH:
				boom(g, 1.4);
				red = 1;
				break;
			default:
		}
	}
}
