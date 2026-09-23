package dev.theredstonee.trsclient.core.ui;

/**
 * Zeichenhilfen im TRS-Stil: abgerundete Flächen, Schatten, Schalter, Schieberegler.
 * Alles wird aus Rechtecken gebaut – damit sieht das Menü in jeder Minecraft-Version gleich aus.
 */
public final class Paint {
	private Paint() {
	}

	/** Waagerechte Linie (y einschließlich). */
	public static void hLine(Canvas c, int x1, int x2, int y, int argb) {
		c.fill(Math.min(x1, x2), y, Math.max(x1, x2) + 1, y + 1, argb);
	}

	/** Senkrechte Linie (y2 einschließlich). */
	public static void vLine(Canvas c, int x, int y1, int y2, int argb) {
		c.fill(x, Math.min(y1, y2), x + 1, Math.max(y1, y2) + 1, argb);
	}

	/** Rahmen, 1 px. */
	public static void outline(Canvas c, int x, int y, int w, int h, int argb) {
		c.fill(x, y, x + w, y + 1, argb);
		c.fill(x, y + h - 1, x + w, y + h, argb);
		c.fill(x, y + 1, x + 1, y + h - 1, argb);
		c.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
	}

	/**
	 * Einrückung je Zeile für eine abgerundete Ecke (Viertelkreis).
	 * {@code radius} wird auf die halbe Höhe begrenzt.
	 */
	public static int[] corner(int radius) {
		int r = Math.max(0, radius);
		int[] inset = new int[r];
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			double dx = r - Math.sqrt(Math.max(0, r * (double) r - dy * dy));
			inset[i] = (int) Math.round(dx);
		}
		return inset;
	}

	/** Abgerundete Fläche. */
	public static void roundRect(Canvas c, int x, int y, int w, int h, int radius, int argb) {
		if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		if (r == 0) {
			c.fill(x, y, x + w, y + h, argb);
			return;
		}
		int[] inset = corner(r);
		for (int i = 0; i < r; i++) {
			int d = inset[i];
			c.fill(x + d, y + i, x + w - d, y + i + 1, argb);
			c.fill(x + d, y + h - i - 1, x + w - d, y + h - i, argb);
		}
		c.fill(x, y + r, x + w, y + h - r, argb);
	}

	/** Abgerundeter Rahmen (1 px). */
	public static void roundOutline(Canvas c, int x, int y, int w, int h, int radius, int argb) {
		if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		if (r == 0) {
			outline(c, x, y, w, h, argb);
			return;
		}
		int[] inset = corner(r);
		for (int i = 0; i < r; i++) {
			int d = inset[i];
			int next = i + 1 < r ? inset[i + 1] : 0;
			int runLeftEnd = Math.max(d + 1, next);
			// Oben und unten die waagerechten Stücke der Rundung …
			c.fill(x + d, y + i, x + runLeftEnd, y + i + 1, argb);
			c.fill(x + w - runLeftEnd, y + i, x + w - d, y + i + 1, argb);
			c.fill(x + d, y + h - i - 1, x + runLeftEnd, y + h - i, argb);
			c.fill(x + w - runLeftEnd, y + h - i - 1, x + w - d, y + h - i, argb);
		}
		// … dann die geraden Kanten.
		c.fill(x + r, y, x + w - r, y + 1, argb);
		c.fill(x + r, y + h - 1, x + w - r, y + h, argb);
		c.fill(x, y + r, x + 1, y + h - r, argb);
		c.fill(x + w - 1, y + r, x + w, y + h - r, argb);
	}

	/** Weicher Schatten unter einer abgerundeten Fläche (drei Ringe). */
	public static void shadow(Canvas c, int x, int y, int w, int h, int radius, float alpha) {
		for (int i = 3; i >= 1; i--) {
			int a = Math.round(26 * alpha) / i;
			if (a <= 0) continue;
			roundRect(c, x - i, y - i + 1, w + i * 2, h + i * 2, radius + i, (a << 24));
		}
	}

	/** Text, auf {@code maxWidth} gekürzt. */
	public static void textClipped(Canvas c, String text, int x, int y, int maxWidth, int argb, boolean shadow) {
		if ((argb >>> 24) < 8) return;
		String s = c.textWidth(text) <= maxWidth ? text : c.clip(text, Math.max(0, maxWidth - c.textWidth("…"))) + "…";
		c.text(s, x, y, argb, shadow);
	}

	/** Mittig ausgerichteter Text. */
	public static void textCentered(Canvas c, String text, int centerX, int y, int argb, boolean shadow) {
		if ((argb >>> 24) < 8) return;
		c.text(text, centerX - c.textWidth(text) / 2, y, argb, shadow);
	}

	/** Rechtsbündiger Text (x = rechte Kante). */
	public static void textRight(Canvas c, String text, int right, int y, int argb, boolean shadow) {
		if ((argb >>> 24) < 8) return;
		c.text(text, right - c.textWidth(text), y, argb, shadow);
	}

	/**
	 * Umbricht Text auf {@code maxWidth} (an Leerzeichen) und liefert die Zeilen.
	 * Eigene Umbruchlogik, weil die Vanilla-Funktion je Version anders heißt.
	 */
	public static java.util.List<String> wrap(Canvas c, String text, int maxWidth) {
		java.util.List<String> lines = new java.util.ArrayList<String>();
		if (text == null || text.isEmpty() || maxWidth <= 0) return lines;
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (c.textWidth(candidate) <= maxWidth || line.length() == 0) {
				line.setLength(0);
				line.append(candidate);
			} else {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
		}
		if (line.length() > 0) lines.add(line.toString());
		return lines;
	}

	/** Mehrzeiliger Text; liefert das y unter der letzten Zeile. */
	public static int paragraph(Canvas c, String text, int x, int y, int maxWidth, int lineHeight, int argb) {
		for (String line : wrap(c, text, maxWidth)) {
			c.text(line, x, y, argb, false);
			y += lineHeight;
		}
		return y;
	}

	/**
	 * An/Aus-Schalter im Redstone-Stil. {@code progress} 0 = aus, 1 = an – dazwischen wandert der
	 * Knopf (weiche Animation). Siehe {@link Redstone#toggle}.
	 */
	public static void toggle(Canvas c, int x, int y, int w, int h, float progress, boolean hover) {
		Redstone.toggle(c, x, y, w, h, progress, hover);
	}

	/** Schieberegler; die Trefferfläche verwaltet der Aufrufer. Siehe {@link Redstone#slider}. */
	public static void slider(Canvas c, int x, int y, int w, int h, float fraction, boolean hover) {
		Redstone.slider(c, x, y, w, h, fraction, hover);
	}

	/** Knopf mit Beschriftung. {@code primary} = leuchtende Redstone-Lampe. */
	public static void button(Canvas c, int x, int y, int w, int h, String label, boolean primary, boolean hover) {
		Redstone.button(c, x, y, w, h, label, primary, hover);
	}

	/** Kleiner Symbol-Knopf (quadratisch). */
	public static void iconButton(Canvas c, int x, int y, int size, String icon, boolean hover, boolean primary) {
		Redstone.iconButton(c, x, y, size, icon, hover, primary);
	}
}
