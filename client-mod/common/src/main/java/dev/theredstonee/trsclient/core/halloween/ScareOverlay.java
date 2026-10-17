package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.halloween.Sprites;

/**
 * Zeichnet die Jumpscares im Spiel über alles (HUD bzw. über dem offenen Bildschirm): die Schattengestalt hinter dem
 * Spieler („Hinter dir!“), das Gesicht aus der Kiste und die Höhlen-Folge (Flüstern → rote Augen am Rand → „…“ →
 * Gesicht). Zahlen wie {@code #scare}/{@code #behind} im Entwurf (Gesicht 86 % der kleineren Bildschirmseite, „boo“
 * mit Überschwingen und Wackeln).
 */
public final class ScareOverlay {
	/** Anzahl Flüster-Texte (i18n {@code halloween.whisper.1} …). */
	static final int WHISPERS = 7;

	private ScareOverlay() {
	}

	/** Zeichnet die laufende Szene (falls eine läuft). @return true, wenn etwas gezeichnet wurde */
	public static boolean draw(Canvas c, int width, int height, long now) {
		Jumpscares.Scene s = Jumpscares.get().current(now);
		if (s == null) return false;
		c.push();
		c.raise(500f);
		try {
			switch (s.type) {
				case BEHIND_YOU:
					behind(c, width, height, s.age);
					break;
				case CHEST:
					face(c, width, height, s.age, false);
					break;
				case CAVE:
					cave(c, width, height, s);
					break;
				case FACE_PRESS:
					facePress(c, width, height, s.age);
					break;
				case WHISPER_CRAWL:
					whisperCrawl(c, width, height, s);
					break;
				case THIRTEENTH:
					thirteenth(c, width, height, s.age);
					break;
				case FLASH:
					flash(c, width, height, s.age);
					break;
				default:
					break;
			}
		} finally {
			c.pop();
		}
		return true;
	}

	/** Schattengestalt hinter dem Spieler (Ansicht von vorne: Spieler in der Bildmitte). */
	static void behind(Canvas c, int width, int height, long age) {
		// Entwurf 160×90: Spieler Kopf 74..86/44..56, Gestalt 84..102 / 26..76, Augen bei y 30.
		float s = height / 90f * 0.9f;
		float ox = width / 2f - 80 * s;
		float oy = height / 2f - 58 * s;
		float a = age < 60 ? age / 60f : (age > Jumpscares.BEHIND_MS - 80 ? Math.max(0f, (Jumpscares.BEHIND_MS - age) / 80f) : 1f);
		int body = ColorMath.fade(0xFF020104, 0.92f * a);
		rect(c, ox, oy, s, 88, 26, 10, 10, body);
		rect(c, ox, oy, s, 87, 36, 12, 40, body);
		rect(c, ox, oy, s, 84, 38, 3, 30, body);
		rect(c, ox, oy, s, 99, 38, 3, 30, body);
		int eye = ColorMath.fade(0xFFFF3B2F, a);
		rect(c, ox, oy, s, 90, 30, 2, 1, eye);
		rect(c, ox, oy, s, 95, 30, 2, 1, eye);
	}

	private static void rect(Canvas c, float ox, float oy, float s, int x, int y, int w, int h, int argb) {
		c.fill(Math.round(ox + x * s), Math.round(oy + y * s), Math.round(ox + (x + w) * s), Math.round(oy + (y + h) * s), argb);
	}

	/**
	 * Schwarzer Schirm, dann das Gesicht mit „boo“ (Größe 0,6 → 1,08 → 1, 12× Wackeln à 50 ms). {@code dots} = vorher
	 * „…“ ({@link Jumpscares#DOTS_MS}); {@code age} zählt ab Beginn dieses Teils.
	 */
	static void face(Canvas c, int width, int height, long age, boolean dots) {
		float in = Math.min(1f, age / 80f);
		long faceAge = dots ? age - Jumpscares.DOTS_MS : age;
		long faceLen = Jumpscares.FACE_MS;
		float out = faceAge > faceLen - 200 ? Math.max(0f, (faceLen - faceAge) / 200f) : 1f;
		c.fill(0, 0, width, height, ColorMath.fade(0xFF000000, Math.min(in, out)));
		if (faceAge < 0) {
			String d = "…";
			int blink = (int) (age / 350) % 2;
			if (blink == 0) c.text(d, (width - c.textWidth(d)) / 2, height / 2 - 4, 0xFF9A94B4, false);
			return;
		}
		float t = Math.min(1f, faceAge / 250f);
		// cubic-bezier(.2,1.6,.4,1) ungefähr: Überschwingen auf 1,08, dann 1.
		float scale = t < 0.7f ? 0.6f + (1.08f - 0.6f) * (t / 0.7f) : 1.08f - 0.08f * ((t - 0.7f) / 0.3f);
		int size = Math.round(Math.min(width, height) * 0.86f * scale);
		int px = Math.max(1, size / 24);
		int shakeX = 0;
		int shakeY = 0;
		if (faceAge < 600) {
			int step = (int) (faceAge / 50);
			shakeX = (step % 2 == 0 ? 1 : -1) * Math.max(1, px / 2);
			shakeY = (step % 3 == 0 ? 1 : -1) * Math.max(1, px / 3);
		}
		int x = (width - px * 24) / 2 + shakeX;
		int y = (height - px * 24) / 2 + shakeY;
		Sprites.draw(c, Sprites.FACE, x, y, px, out);
	}

	/** Höhle: Flüstern → rote Augen am Rand → „…“ → Gesicht. */
	static void cave(Canvas c, int width, int height, Jumpscares.Scene s) {
		long age = s.age;
		if (age < Jumpscares.WHISPER_MS) {
			int n = 1 + Math.floorMod(s.seed, WHISPERS);
			String text = I18n.tr("halloween.whisper." + n);
			float a = age < 600 ? age / 600f : (age > Jumpscares.WHISPER_MS - 700 ? Math.max(0f, (Jumpscares.WHISPER_MS - age) / 700f) : 1f);
			int x = (int) (width * (0.08f + (s.seed % 70) / 100f));
			int y = (int) (height * (0.15f + ((s.seed >> 7) % 60) / 100f));
			x = Math.max(4, Math.min(width - c.textWidth(text) - 4, x));
			c.text(text, x, y, ColorMath.fade(0xFFB9A2EA, a * 0.85f), false);
			return;
		}
		age -= Jumpscares.WHISPER_MS;
		if (age < Jumpscares.EYES_MS) {
			boolean left = (s.seed & 1) == 0;
			float a = Math.min(1f, age / 500f);
			boolean blink = (age / 900) % 3 == 2 && age % 900 < 140;
			if (!blink) {
				int ey = (int) (height * (0.35f + ((s.seed >> 3) % 30) / 100f));
				int ex = left ? 10 : width - 22;
				eyes(c, ex, ey, Math.max(1, height / 180), a);
			}
			return;
		}
		age -= Jumpscares.EYES_MS;
		long dotsShort = Jumpscares.DOTS_MS / 2;
		face(c, width, height, age + (Jumpscares.DOTS_MS - dotsShort), true);
	}

	/**
	 * Gesicht gegen die Scheibe: ein bleiches Gesicht quetscht sich (breiter als hoch) an den Bildschirm, hält kurz
	 * und weicht zurück. Ein dünner kalter Schleier und ein Beschlag-Rand verstärken die „Glas“-Wirkung.
	 */
	static void facePress(Canvas c, int width, int height, long age) {
		float len = Jumpscares.PRESS_MS;
		float in = Math.min(1f, age / 160f);
		float out = age > len - 200 ? Math.max(0f, (len - age) / 200f) : 1f;
		float a = Math.min(in, out);
		// Kalter Schleier (Glas) über dem ganzen Bild.
		c.fill(0, 0, width, height, ColorMath.fade(0xFF060A08, 0.55f * a));
		int min = Math.min(width, height);
		// Dicht an der „Scheibe“: größer als das Kisten-Gesicht, kommt schnell heran.
		float grow = 0.9f + 0.14f * Math.min(1f, age / 240f);
		int size = Math.round(min * 1.02f * grow);
		int px = Math.max(1, size / 24);
		int x = (width - px * 24) / 2;
		int y = (height - px * 24) / 2;
		Sprites.draw(c, Sprites.FACE, x, y, px, a);
		// Zwei beschlagene, kalte Augenpunkte verstärken den „Gesicht am Glas“-Eindruck.
		int glow = ColorMath.fade(0x33BFE9E2, a);
		c.fill(x + px * 7, y + px * 9, x + px * 10, y + px * 11, glow);
		c.fill(x + px * 14, y + px * 9, x + px * 17, y + px * 11, glow);
	}

	/**
	 * Die Welt entsättigt (grauer Schleier, der kurz pulst) und ein Flüstern kriecht quer über die Anzeige.
	 */
	static void whisperCrawl(Canvas c, int width, int height, Jumpscares.Scene s) {
		long age = s.age;
		float len = Jumpscares.CRAWL_MS;
		float a = age < 500 ? age / 500f : (age > len - 700 ? Math.max(0f, (len - age) / 700f) : 1f);
		// Entsättigung andeuten: ein gedeckter grauer Schleier über dem Bild (kein Blitzen).
		float pulse = 0.18f + 0.07f * (float) Math.sin(age / 180.0);
		c.fill(0, 0, width, height, ColorMath.fade(0xFF2A2834, (pulse + 0.12f) * a));
		int n = 1 + Math.floorMod(s.seed, WHISPERS);
		String text = I18n.tr("halloween.whisper." + n);
		int tw = c.textWidth(text);
		// Quer von rechts nach links kriechen.
		float travel = age / len;
		int x = Math.round(width + tw - travel * (width + 2 * tw));
		int y = (int) (height * (0.42f + ((s.seed >> 5) % 16) / 100f));
		c.text(text, x, y, ColorMath.fade(0xFFCBB9F2, 0.9f * a), true);
	}

	/**
	 * Für einen Herzschlag zeigt die Schnellleiste ein 13. Feld mit einem Auge darin (unten mittig, rechts neben der
	 * Leiste). Blendet ein und wieder aus.
	 */
	static void thirteenth(Canvas c, int width, int height, long age) {
		float len = Jumpscares.THIRTEENTH_MS;
		float a = age < 150 ? age / 150f : (age > len - 250 ? Math.max(0f, (len - age) / 250f) : 1f);
		int slot = Math.max(18, height / 12);
		// Rechts neben der Vanilla-Schnellleiste (die ist 182 px breit, mittig).
		int barHalf = Math.min(width / 2 - slot - 4, 91 * slot / 20);
		int x = width / 2 + barHalf + 2;
		int y = height - slot - 2;
		// Feldrahmen im Vanilla-Stil (dunkel mit hellem Rand).
		c.fill(x, y, x + slot, y + slot, ColorMath.fade(0xFF8B8B8B, a));
		c.fill(x + 1, y + 1, x + slot - 1, y + slot - 1, ColorMath.fade(0xFF2B2B2B, a));
		int ex = x + slot / 2 - 2;
		int ey = y + slot / 2 - 1;
		eyes(c, ex, ey, Math.max(1, slot / 10), a);
	}

	/**
	 * Kurzer, invertiert wirkender Lichtblitz (wie bei einem Blitzschlag). Bei „Flackern &amp; Rauschen“ aus wird
	 * daraus ein sanfter dunkler Puls (reduzierte Bewegung, kein Stroboskop).
	 */
	static void flash(Canvas c, int width, int height, long age) {
		float len = Jumpscares.FLASH_MS;
		float t = Math.min(1f, age / len);
		if (!HalloweenOverlay.flicker()) {
			float a = (float) Math.sin(Math.PI * t) * 0.6f;
			c.fill(0, 0, width, height, ColorMath.fade(0xFF0A0A12, a));
			return;
		}
		// „Invertiert“ angedeutet durch schnelle Hell-Dunkel-Wechsel, die abklingen.
		float env = 1f - t;
		int phase = (int) (age / 70);
		int col = phase % 2 == 0 ? ColorMath.fade(0xFFF2F2FF, 0.85f * env) : ColorMath.fade(0xFF05040A, 0.9f * env);
		c.fill(0, 0, width, height, col);
	}

	/** Zwei rote Augen mit Schein (wie die Augen in der Szene: Kern #ff4433, Schein rgba(255,40,30,.28)). */
	public static void eyes(Canvas c, int x, int y, int px, float alpha) {
		int glow = ColorMath.fade(0x47FF281E, alpha);
		int core = ColorMath.fade(0xFFFF4433, alpha);
		c.fill(x - px, y - px, x + 2 * px, y + 2 * px, glow);
		c.fill(x + 3 * px, y - px, x + 6 * px, y + 2 * px, glow);
		c.fill(x, y, x + px, y + px, core);
		c.fill(x + 4 * px, y, x + 5 * px, y + px, core);
	}
}
