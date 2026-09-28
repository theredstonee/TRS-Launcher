package dev.theredstonee.trsclient.core.screenshot.edit;

/**
 * Eine Zeichnung im Bild-Editor. Koordinaten in Bildpixeln des gedrehten, noch nicht zugeschnittenen Bildes
 * (siehe {@link EditState}); unveränderlich, damit Rückgängig/Wiederholen nur Listen tauscht.
 */
public abstract class Shape {
	public enum Kind {
		ARROW, RECT, PEN, TEXT, PIXELATE
	}

	public final Kind kind;
	/** Farbe (ARGB, deckend). */
	public final int color;
	/** Strichstärke bzw. Schrifthöhe/Blockgröße in Bildpixeln. */
	public final float size;

	Shape(Kind kind, int color, float size) {
		this.kind = kind;
		this.color = color | 0xFF000000;
		this.size = Math.max(1f, size);
	}

	/** Dieselbe Form nach einer Vierteldrehung des Bildes im Uhrzeigersinn ({@code oldH} = Höhe vor der Drehung). */
	public abstract Shape rotated(int oldH);

	/** Umgebendes Rechteck {x1, y1, x2, y2} (ohne Strichstärke). */
	public abstract float[] bounds();

	/** Punkt (x, y) um 90° im Uhrzeigersinn: (H − y, x). */
	static float rx(float x, float y, int oldH) {
		return oldH - y;
	}

	static float ry(float x, float y, int oldH) {
		return x;
	}

	/** Pfeil von (x1, y1) nach (x2, y2); Spitze am Ende. */
	public static final class Arrow extends Shape {
		public final float x1, y1, x2, y2;

		public Arrow(float x1, float y1, float x2, float y2, int color, float size) {
			super(Kind.ARROW, color, size);
			this.x1 = x1;
			this.y1 = y1;
			this.x2 = x2;
			this.y2 = y2;
		}

		@Override
		public Shape rotated(int oldH) {
			return new Arrow(rx(x1, y1, oldH), ry(x1, y1, oldH), rx(x2, y2, oldH), ry(x2, y2, oldH), color, size);
		}

		@Override
		public float[] bounds() {
			return new float[]{Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)};
		}
	}

	/** Rechteck-Rahmen (x1,y1)–(x2,y2), Ecken beliebig. */
	public static final class Rect extends Shape {
		public final float x1, y1, x2, y2;

		public Rect(float x1, float y1, float x2, float y2, int color, float size) {
			super(Kind.RECT, color, size);
			this.x1 = Math.min(x1, x2);
			this.y1 = Math.min(y1, y2);
			this.x2 = Math.max(x1, x2);
			this.y2 = Math.max(y1, y2);
		}

		@Override
		public Shape rotated(int oldH) {
			return new Rect(rx(x1, y1, oldH), ry(x1, y1, oldH), rx(x2, y2, oldH), ry(x2, y2, oldH), color, size);
		}

		@Override
		public float[] bounds() {
			return new float[]{x1, y1, x2, y2};
		}
	}

	/** Verpixelter Bereich (Schutz für Streamer: Namen, Koordinaten …); {@code size} = Blockgröße. */
	public static final class Pixelate extends Shape {
		public final float x1, y1, x2, y2;

		public Pixelate(float x1, float y1, float x2, float y2, float block) {
			super(Kind.PIXELATE, 0xFF000000, block);
			this.x1 = Math.min(x1, x2);
			this.y1 = Math.min(y1, y2);
			this.x2 = Math.max(x1, x2);
			this.y2 = Math.max(y1, y2);
		}

		@Override
		public Shape rotated(int oldH) {
			return new Pixelate(rx(x1, y1, oldH), ry(x1, y1, oldH), rx(x2, y2, oldH), ry(x2, y2, oldH), size);
		}

		@Override
		public float[] bounds() {
			return new float[]{x1, y1, x2, y2};
		}
	}

	/** Freihand-Strich: Punkte x0,y0,x1,y1,… */
	public static final class Pen extends Shape {
		private final float[] points;

		public Pen(float[] points, int color, float size) {
			super(Kind.PEN, color, size);
			this.points = points.clone();
		}

		public int count() {
			return points.length / 2;
		}

		public float x(int i) {
			return points[i * 2];
		}

		public float y(int i) {
			return points[i * 2 + 1];
		}

		@Override
		public Shape rotated(int oldH) {
			float[] p = new float[points.length];
			for (int i = 0; i + 1 < points.length; i += 2) {
				p[i] = rx(points[i], points[i + 1], oldH);
				p[i + 1] = ry(points[i], points[i + 1], oldH);
			}
			return new Pen(p, color, size);
		}

		@Override
		public float[] bounds() {
			float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (int i = 0; i + 1 < points.length; i += 2) {
				b[0] = Math.min(b[0], points[i]);
				b[1] = Math.min(b[1], points[i + 1]);
				b[2] = Math.max(b[2], points[i]);
				b[3] = Math.max(b[3], points[i + 1]);
			}
			return b;
		}
	}

	/**
	 * Text, linke obere Ecke (x, y); {@code size} = Schrifthöhe in Bildpixeln. {@code quarterTurns} dreht den Text mit
	 * dem Bild mit (0–3).
	 */
	public static final class Text extends Shape {
		public static final int MAX_LENGTH = 120;
		public final float x, y;
		public final String text;
		public final int quarterTurns;

		public Text(float x, float y, String text, int color, float size, int quarterTurns) {
			super(Kind.TEXT, color, size);
			this.x = x;
			this.y = y;
			String t = text == null ? "" : text;
			this.text = t.length() > MAX_LENGTH ? t.substring(0, MAX_LENGTH) : t;
			this.quarterTurns = ((quarterTurns % 4) + 4) % 4;
		}

		@Override
		public Shape rotated(int oldH) {
			return new Text(rx(x, y, oldH), ry(x, y, oldH), text, color, size, quarterTurns + 1);
		}

		@Override
		public float[] bounds() {
			return new float[]{x, y, x, y};
		}
	}
}
