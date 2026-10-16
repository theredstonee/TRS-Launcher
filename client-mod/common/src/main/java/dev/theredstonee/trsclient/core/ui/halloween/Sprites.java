package dev.theredstonee.trsclient.core.ui.halloween;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;

import java.util.HashMap;
import java.util.Map;

/**
 * Pixel-Sprites von Halloween 2026 (gleiche Bilder wie {@code SPR} im freigegebenen Entwurf): Kürbis, Geist, Fledermaus,
 * Mond, Kessel, Grab (Sticker), Mond-Fledermaus ({@code halloween2026}), Spinne, kleiner Kürbis ({@code pumpkinhunter2026})
 * – dazu Kürbislaterne der Szene und das Jumpscare-Gesicht (24×24). Gezeichnet als Rechtecke über {@link Canvas}, in
 * jeder Version gleich.
 */
public final class Sprites {
	/** Ein Sprite: ARGB je Pixel (0 = leer), zeilenweise. */
	public static final class Sprite {
		public final String id;
		public final int w;
		public final int h;
		public final int[] px;

		Sprite(String id, String[] rows, Map<Character, Integer> pal) {
			this.id = id;
			this.h = rows.length;
			int width = 0;
			for (String r : rows) width = Math.max(width, r.length());
			this.w = width;
			this.px = new int[w * h];
			for (int y = 0; y < h; y++) {
				String r = rows[y];
				for (int x = 0; x < r.length(); x++) {
					Integer c = pal.get(r.charAt(x));
					if (c != null) px[y * w + x] = c;
				}
			}
		}

		Sprite(String id, int w, int h, int[] px) {
			this.id = id;
			this.w = w;
			this.h = h;
			this.px = px;
		}

		public int get(int x, int y) {
			return x < 0 || y < 0 || x >= w || y >= h ? 0 : px[y * w + x];
		}
	}

	private static final Map<String, Sprite> ALL = new HashMap<String, Sprite>();
	/** Muss vor den Sprites stehen (das Gesicht nutzt es in der Initialisierung). */
	private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

	public static final Sprite PUMPKIN = add("pumpkin", pal('o', 0xFF8A3F16, 'O', 0xFFD9772F, 'g', 0xFF4D7A45, 'y', 0xFFFFD27A),
			"............", ".....gg.....", "..ooOgOoo...", ".oOOOoOOOo..", "oOyOOoOOyOo.", "oOyyOoOyyOo.",
			"oOOOOoOOOOo.", "oOyOyoyOyOo.", "oOOyOyOyOOo.", ".oOOOoOOOo..", "..ooooooo...", "............");
	public static final Sprite GHOST = add("ghost", pal('w', 0xFFEEF1F8, 's', 0xFFB8BDCC, 'k', 0xFF1A1426),
			"....wwww....", "..wwwwwwww..", ".wwwwwwwwww.", ".wwkkwwkkww.", ".wwkkwwkkww.", ".wwwwwwwwww.",
			".wwwwkkwwww.", ".wwwwkkwwws.", ".wwwwwwwwws.", ".wwwwwwwwss.", ".ww.ww.ww.s.", ".w...w...w..");
	public static final Sprite BAT = add("bat", pal('k', 0xFF2A2240, 'v', 0xFFB9A2EA),
			"............", "............", "k....kk....k", "kk..kkkk..kk", "kkkkkvkvkkkk", ".kkkkkkkkkk.",
			"..kk.kk.kk..", "..k......k..", "............", "............", "............", "............");
	public static final Sprite MOON = add("moon", pal('s', 0xFFE6E9F2, 'd', 0xFFA9AFC2),
			"....ssss....", "..sssss.....", ".ssssd......", ".sssd.......", "sssd........", "sssd........",
			"sssd........", "sssd........", ".sssd.......", ".ssssd......", "..sssss.....", "....ssss....");
	public static final Sprite CAULDRON = add("cauldron", pal('i', 0xFF4A4560, 'h', 0xFF6F6890, 'b', 0xFF9FD6E6),
			"..b...b.....", "....b...b...", ".hhhhhhhhhh.", "ibbbbbbbbbbi", ".iiiiiiiiii.", ".ihiiiiiiii.",
			"iihiiiiiiiii", "iiiiiiiiiiii", ".iiiiiiiiii.", "..iiiiiiii..", "..i......i..", "............");
	public static final Sprite GRAVE = add("grave", pal('c', 0xFF7D7A8C, 'k', 0xFF3B3851, 'g', 0xFF2F4A35),
			"....cccc....", "..cccccccc..", ".cccckkcccc.", ".cckkkkkkcc.", ".cccckkcccc.", ".cccckkcccc.",
			".cccccccccc.", ".cccccccccc.", ".cccccccccc.", "gggggggggggg", "gggggggggggg", "............");
	public static final Sprite MOONBAT = add("moonbat", pal('s', 0xFFE6E9F2, 'k', 0xFFB9A2EA),
			"...sss...", "..ss.....", ".ss......", ".ss.k...k", ".ss.kkkkk", ".ss..k.k.", "..ss.....", "...sss...");
	public static final Sprite SPIDER = add("spider", pal('k', 0xFF0E0B16, 'v', 0xFFB9A2EA, 'l', 0xFF3B3358),
			"l.kkk.l", ".lkkkl.", "lkvkvkl", ".kkkkk.", "l.kkk.l", ".l...l.");
	public static final Sprite PK8 = add("pk8", pal('o', 0xFF8A3F16, 'O', 0xFFD9772F, 'g', 0xFF4D7A45, 'y', 0xFFFFD27A),
			"....g...", "..oOOo..", ".oOyOyO.", ".oOOOOO.", ".oOyyyO.", "..oOOo..", "........", "........");
	/** Kürbislaterne der Szene (9×9, {@code JACK} im Entwurf). */
	public static final Sprite JACK = add("jack", pal('o', 0xFF7A3512, 'O', 0xFFC4651F, 'y', 0xFFFFD27A, 'g', 0xFF4D7A45),
			"....g....", "...g.....", ".oOOoOOo.", "oOyOoOyOo", "oOyyoyyOo", "oOOOoOOOo", "oyOyoyOyo", ".oyyyyyo.", "..ooooo..");
	/** Jumpscare-Gesicht (24×24, wie {@code #scarec} im Entwurf). */
	public static final Sprite FACE = register(face());

	private Sprites() {
	}

	/** Sticker-ID der API → eingebautes Sprite (Vorschau, solange das Bild der API fehlt), sonst null. */
	public static Sprite forSticker(String stickerId) {
		if (stickerId == null) return null;
		if (stickerId.startsWith("pumpkin")) return PUMPKIN;
		if (stickerId.startsWith("ghost")) return GHOST;
		if (stickerId.startsWith("bat")) return BAT;
		if (stickerId.startsWith("moon")) return MOON;
		if (stickerId.startsWith("cauldron")) return CAULDRON;
		if (stickerId.startsWith("grave")) return GRAVE;
		return null;
	}

	/** Abzeichen-ID → eingebautes Sprite (Rückfall, solange das Bild der API fehlt), sonst null. */
	public static Sprite forBadge(String badgeId) {
		if ("halloween2026".equals(badgeId)) return MOONBAT;
		if ("pumpkinhunter2026".equals(badgeId)) return PK8;
		return null;
	}

	public static Sprite get(String id) {
		return ALL.get(id);
	}

	/**
	 * Zeichnet ein Sprite bei (x, y), {@code px} GUI-Pixel je Sprite-Pixel, mit Deckkraft {@code alpha} (0..1).
	 * Gleiche Farben in einer Zeile werden zu einem Rechteck zusammengefasst.
	 */
	public static void draw(Canvas c, Sprite s, int x, int y, int px, float alpha) {
		if (s == null || alpha <= 0.004f) return;
		for (int row = 0; row < s.h; row++) {
			int col = 0;
			while (col < s.w) {
				int argb = s.px[row * s.w + col];
				int end = col + 1;
				while (end < s.w && s.px[row * s.w + end] == argb) end++;
				if (argb != 0) {
					int a = alpha >= 0.999f ? argb : ColorMath.fade(argb, alpha);
					c.fill(x + col * px, y + row * px, x + end * px, y + (row + 1) * px, a);
				}
				col = end;
			}
		}
	}

	/** Wie {@link #draw}, gespiegelt (Blick nach links). */
	public static void drawMirrored(Canvas c, Sprite s, int x, int y, int px, float alpha) {
		if (s == null || alpha <= 0.004f) return;
		for (int row = 0; row < s.h; row++) {
			for (int col = 0; col < s.w; col++) {
				int argb = s.px[row * s.w + col];
				if (argb == 0) continue;
				int a = alpha >= 0.999f ? argb : ColorMath.fade(argb, alpha);
				int dx = s.w - 1 - col;
				c.fill(x + dx * px, y + row * px, x + (dx + 1) * px, y + (row + 1) * px, a);
			}
		}
	}

	/** Wie {@link #draw}, alle Pixel in einer Farbe (Schatten, Silhouetten). */
	public static void silhouette(Canvas c, Sprite s, int x, int y, int px, int argb) {
		if (s == null) return;
		for (int row = 0; row < s.h; row++) {
			int col = 0;
			while (col < s.w) {
				boolean on = s.px[row * s.w + col] != 0;
				int end = col + 1;
				while (end < s.w && (s.px[row * s.w + end] != 0) == on) end++;
				if (on) c.fill(x + col * px, y + row * px, x + end * px, y + (row + 1) * px, argb);
				col = end;
			}
		}
	}

	/** Als ARGB-Bild (für Texturen: Abzeichen, Sticker), {@code scale}-fach vergrößert (nächster Nachbar). */
	public static int[] image(Sprite s, int scale) {
		int w = s.w * scale;
		int h = s.h * scale;
		int[] out = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) out[y * w + x] = s.px[(y / scale) * s.w + x / scale];
		}
		return out;
	}

	/** Bayer 4×4 (0..15)/16 wie {@code bay()} im Entwurf. */
	public static double bayer(int x, int y) {
		return BAYER[(y & 3) * 4 + (x & 3)] / 16.0;
	}


	private static Sprite add(String id, Map<Character, Integer> pal, String... rows) {
		return register(new Sprite(id, rows, pal));
	}

	private static Sprite register(Sprite s) {
		ALL.put(s.id, s);
		return s;
	}

	private static Map<Character, Integer> pal(Object... kv) {
		Map<Character, Integer> m = new HashMap<Character, Integer>();
		for (int i = 0; i + 1 < kv.length; i += 2) m.put((Character) kv[i], (Integer) kv[i + 1]);
		return m;
	}

	private static Sprite face() {
		int[] px = new int[24 * 24];
		for (int y = 0; y < 24; y++) {
			for (int x = 0; x < 24; x++) {
				double u = (x - 11.5) / 10, v = (y - 12) / 11.5, r2 = u * u + v * v;
				if (r2 > 1) continue;
				int col = r2 > 0.62 + bayer(x, y) * 0.25 ? 0xFF7D7A72 : 0xFFC9C6BD;
				if (sq((x - 7.5) / 3.3) + sq((y - 9) / 2.6) < 1 || sq((x - 15.5) / 3.3) + sq((y - 9) / 2.6) < 1) col = 0xFF050203;
				if ((x == 7 || x == 8) && y == 9) col = 0xFFFF2A1A;
				if ((x == 15 || x == 16) && y == 9) col = 0xFFFF2A1A;
				if (sq((x - 11.5) / 4.2) + sq((y - 18) / 4.0) < 1) col = (y == 15 || y == 21) && x % 2 == 0 ? 0xFFE8E1CF : 0xFF0A0204;
				if ((x == 11 || x == 12) && y == 13) col = 0xFF2A2522;
				if ((x - y == -2 && y < 7 && x > 3) || (x + y == 30 && y < 8 && x > 17)) col = 0xFF4A4640;
				px[y * 24 + x] = col;
			}
		}
		return new Sprite("face", 24, 24, px);
	}

	private static double sq(double v) {
		return v * v;
	}
}
