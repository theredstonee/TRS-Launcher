package dev.theredstonee.trsclient.core.wardrobe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Zustand und Werkzeuge des Skin-Editors – ohne Minecraft und ohne Zeichnen (testbar).
 *
 * <p>Die Pixel sind ein 64×64-ARGB-Bild. Gemalt wird immer nur in der gewählten Ebene (Grund oder zweite
 * Ebene); ein Pinselstrich (Drücken bis Loslassen) ist ein Rückgängig-Schritt. Spiegeln malt zusätzlich am
 * gespiegelten Texel ({@link SkinLayout#mirror}), Füllen bleibt in der Quaderseite des Starttexels.
 * {@link #version()} zählt jede Änderung – die Oberfläche lädt die Textur nur dann neu hoch.
 */
public final class SkinEditor {
	public enum Tool {
		BRUSH, ERASER, PICKER, FILL
	}

	/** Größte Anzahl Rückgängig-Schritte (je 16 KB). */
	public static final int MAX_UNDO = 64;
	/** Zuletzt benutzte Farben. */
	public static final int MAX_RECENT = 10;

	private int[] pixels = new int[SkinLayout.SIZE * SkinLayout.SIZE];
	private boolean slim;
	private int layer = SkinLayout.BASE;
	private Tool tool = Tool.BRUSH;
	private boolean mirror;
	private int brushSize = 1;
	private int color = 0xFFB02E26;
	private final List<Integer> recent = new ArrayList<Integer>();
	private final Deque<Snapshot> undo = new ArrayDeque<Snapshot>();
	private final Deque<Snapshot> redo = new ArrayDeque<Snapshot>();
	/** Stand vor dem laufenden Strich (null = kein Strich). */
	private Snapshot stroke;
	private boolean strokeChanged;
	private int version;
	/** Wurde seit dem Laden/Speichern etwas geändert? */
	private boolean dirty;

	private static final class Snapshot {
		final int[] pixels;
		final boolean slim;

		Snapshot(int[] pixels, boolean slim) {
			this.pixels = pixels;
			this.slim = slim;
		}
	}

	/** Lädt ein Bild (64×64) als neuen Ausgangspunkt – leert Rückgängig/Wiederholen. */
	public void load(int[] argb64, boolean slim) {
		if (argb64 == null || argb64.length < SkinLayout.SIZE * SkinLayout.SIZE) throw new IllegalArgumentException("64×64");
		pixels = Arrays.copyOf(argb64, SkinLayout.SIZE * SkinLayout.SIZE);
		this.slim = slim;
		undo.clear();
		redo.clear();
		stroke = null;
		dirty = false;
		version++;
	}

	// --- Zustand ---

	public int[] pixels() {
		return pixels;
	}

	/** Kopie der Pixel (zum Speichern/Hochladen). */
	public int[] copyPixels() {
		return Arrays.copyOf(pixels, pixels.length);
	}

	public int pixel(int x, int y) {
		return pixels[y * SkinLayout.SIZE + x];
	}

	public boolean slim() {
		return slim;
	}

	public SkinLayout layout() {
		return SkinLayout.of(slim);
	}

	/** Armform wechseln (Rückgängig-Schritt; Pixel bleiben). */
	public void setSlim(boolean slim) {
		if (this.slim == slim) return;
		pushUndo(new Snapshot(copyPixels(), this.slim));
		this.slim = slim;
		changed();
	}

	public int layer() {
		return layer;
	}

	public void setLayer(int layer) {
		this.layer = layer == SkinLayout.OVERLAY ? SkinLayout.OVERLAY : SkinLayout.BASE;
	}

	public Tool tool() {
		return tool;
	}

	public void setTool(Tool tool) {
		if (tool != null) this.tool = tool;
	}

	public boolean mirror() {
		return mirror;
	}

	public void setMirror(boolean mirror) {
		this.mirror = mirror;
	}

	public int brushSize() {
		return brushSize;
	}

	public void setBrushSize(int size) {
		brushSize = Math.max(1, Math.min(3, size));
	}

	public int color() {
		return color;
	}

	/** Setzt die Malfarbe (voll deckend). */
	public void setColor(int argb) {
		color = argb | 0xFF000000;
	}

	public List<Integer> recentColors() {
		return recent;
	}

	public int version() {
		return version;
	}

	public boolean dirty() {
		return dirty;
	}

	public void markSaved() {
		dirty = false;
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	// --- Striche ---

	/** Beginnt einen Strich (Maustaste gedrückt). */
	public void beginStroke() {
		if (stroke != null) endStroke();
		stroke = new Snapshot(copyPixels(), slim);
		strokeChanged = false;
	}

	/**
	 * Wendet das aktuelle Werkzeug auf den Texel an (innerhalb eines Strichs; ohne Strich wird einer angelegt
	 * und sofort beendet).
	 *
	 * @return true, wenn sich Pixel geändert haben (Pipette: wenn eine Farbe übernommen wurde)
	 */
	public boolean apply(int x, int y) {
		boolean own = stroke == null;
		if (own) beginStroke();
		boolean changed;
		switch (tool) {
			case PICKER:
				changed = pick(x, y);
				break;
			case FILL:
				changed = fill(x, y);
				break;
			case ERASER:
				changed = paint(x, y, 0x00000000);
				break;
			default:
				changed = paint(x, y, color);
				break;
		}
		if (changed && tool != Tool.PICKER) {
			strokeChanged = true;
			changed();
		}
		if (own) endStroke();
		return changed;
	}

	/** Beendet den Strich; hat er etwas geändert, wird er ein Rückgängig-Schritt. */
	public void endStroke() {
		if (stroke == null) return;
		if (strokeChanged) {
			pushUndo(stroke);
			if (tool == Tool.BRUSH || tool == Tool.FILL) remember(color);
		}
		stroke = null;
		strokeChanged = false;
	}

	/** Linie zwischen zwei Texeln (schnelles Ziehen soll keine Lücken lassen). */
	public boolean line(int x0, int y0, int x1, int y1) {
		if (tool == Tool.PICKER || tool == Tool.FILL) return apply(x1, y1);
		boolean any = false;
		int dx = Math.abs(x1 - x0);
		int dy = -Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1;
		int sy = y0 < y1 ? 1 : -1;
		int err = dx + dy;
		int x = x0;
		int y = y0;
		for (int guard = 0; guard < 256; guard++) {
			any |= apply(x, y);
			if (x == x1 && y == y1) break;
			int e2 = 2 * err;
			if (e2 >= dy) {
				err += dy;
				x += sx;
			}
			if (e2 <= dx) {
				err += dx;
				y += sy;
			}
		}
		return any;
	}

	private boolean paint(int x, int y, int argb) {
		SkinLayout l = layout();
		int region = l.region(x, y);
		if (region < 0 || SkinLayout.layerOf(region) != layer) return false;
		boolean changed = false;
		int r = (brushSize - 1) / 2;
		int r2 = brushSize / 2;
		for (int yy = y - r; yy <= y + r2; yy++) {
			for (int xx = x - r; xx <= x + r2; xx++) {
				if (l.region(xx, yy) != region) continue;
				int i = yy * SkinLayout.SIZE + xx;
				changed |= set(i, argb);
				if (mirror) {
					int m = l.mirror(i);
					if (m >= 0) changed |= set(m, argb);
				}
			}
		}
		return changed;
	}

	private boolean set(int index, int argb) {
		if (pixels[index] == argb) return false;
		pixels[index] = argb;
		return true;
	}

	private boolean pick(int x, int y) {
		if (x < 0 || y < 0 || x >= SkinLayout.SIZE || y >= SkinLayout.SIZE) return false;
		int argb = pixels[y * SkinLayout.SIZE + x];
		if ((argb >>> 24) == 0) return false;
		setColor(argb);
		remember(color);
		tool = Tool.BRUSH;
		return true;
	}

	private boolean fill(int x, int y) {
		SkinLayout l = layout();
		int region = l.region(x, y);
		if (region < 0 || SkinLayout.layerOf(region) != layer) return false;
		boolean changed = flood(l, x, y, region, color);
		if (mirror) {
			int m = l.mirror(y * SkinLayout.SIZE + x);
			if (m >= 0) {
				int mx = m % SkinLayout.SIZE;
				int my = m / SkinLayout.SIZE;
				changed |= flood(l, mx, my, l.region(mx, my), color);
			}
		}
		return changed;
	}

	/** 4er-Nachbarschaft, gleiche Farbe, nur innerhalb der Quaderseite. */
	private boolean flood(SkinLayout l, int x, int y, int region, int argb) {
		int target = pixels[y * SkinLayout.SIZE + x];
		if (target == argb) return false;
		int[] stack = new int[SkinLayout.SIZE * SkinLayout.SIZE];
		boolean[] seen = new boolean[SkinLayout.SIZE * SkinLayout.SIZE];
		int n = 0;
		stack[n++] = y * SkinLayout.SIZE + x;
		seen[y * SkinLayout.SIZE + x] = true;
		boolean changed = false;
		while (n > 0) {
			int i = stack[--n];
			pixels[i] = argb;
			changed = true;
			int cx = i % SkinLayout.SIZE;
			int cy = i / SkinLayout.SIZE;
			for (int k = 0; k < 4; k++) {
				int nx = cx + (k == 0 ? 1 : k == 1 ? -1 : 0);
				int ny = cy + (k == 2 ? 1 : k == 3 ? -1 : 0);
				if (l.region(nx, ny) != region) continue;
				int j = ny * SkinLayout.SIZE + nx;
				if (seen[j] || pixels[j] != target) continue;
				seen[j] = true;
				stack[n++] = j;
			}
		}
		return changed;
	}

	// --- Rückgängig ---

	public boolean undo() {
		if (stroke != null) endStroke();
		Snapshot s = undo.pollLast();
		if (s == null) return false;
		redo.addLast(new Snapshot(copyPixels(), slim));
		restore(s);
		return true;
	}

	public boolean redo() {
		if (stroke != null) endStroke();
		Snapshot s = redo.pollLast();
		if (s == null) return false;
		undo.addLast(new Snapshot(copyPixels(), slim));
		restore(s);
		return true;
	}

	private void restore(Snapshot s) {
		pixels = Arrays.copyOf(s.pixels, s.pixels.length);
		slim = s.slim;
		changed();
	}

	private void pushUndo(Snapshot s) {
		undo.addLast(s);
		while (undo.size() > MAX_UNDO) undo.pollFirst();
		redo.clear();
	}

	/** Ersetzt das ganze Bild (Vorlage) als Rückgängig-Schritt. */
	public void replace(int[] argb64, boolean slim) {
		if (argb64 == null || argb64.length < SkinLayout.SIZE * SkinLayout.SIZE) return;
		pushUndo(new Snapshot(copyPixels(), this.slim));
		pixels = Arrays.copyOf(argb64, SkinLayout.SIZE * SkinLayout.SIZE);
		this.slim = slim;
		changed();
	}

	/** Leert die gewählte Ebene (zweite Ebene: durchsichtig) als Rückgängig-Schritt. */
	public void clearLayer() {
		SkinLayout l = layout();
		int[] before = copyPixels();
		boolean any = false;
		for (int i = 0; i < pixels.length; i++) {
			int r = l.region(i % SkinLayout.SIZE, i / SkinLayout.SIZE);
			if (r < 0 || SkinLayout.layerOf(r) != layer) continue;
			any |= set(i, 0);
		}
		if (any) {
			pushUndo(new Snapshot(before, slim));
			changed();
		}
	}

	private void changed() {
		version++;
		dirty = true;
	}

	private void remember(int argb) {
		Integer c = argb | 0xFF000000;
		recent.remove(c);
		recent.add(0, c);
		while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
	}

	// --- Farben ---

	/**
	 * Liest eine Hex-Farbe: {@code #RGB}, {@code #RRGGBB} oder {@code #AARRGGBB} (mit oder ohne '#').
	 *
	 * @return ARGB (ohne Alpha: deckend) oder null, wenn ungültig
	 */
	public static Integer parseHex(String text) {
		if (text == null) return null;
		String s = text.trim();
		if (s.startsWith("#")) s = s.substring(1);
		if (!s.matches("[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) return null;
		if (s.length() == 3) {
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < 3; i++) b.append(s.charAt(i)).append(s.charAt(i));
			s = b.toString();
		}
		long v = Long.parseLong(s, 16);
		if (s.length() == 6) v |= 0xFF000000L;
		return (int) v;
	}

	/** "#RRGGBB" (Großbuchstaben). */
	public static String hex(int argb) {
		return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
	}

	// --- Vorlagen ---

	/**
	 * Leere Vorlage („Schaufensterpuppe“): Grundebene in schlichten Flächenfarben je Körperteil (Haut, Hemd,
	 * Hose, Schuhe), zweite Ebene durchsichtig. Eigene Pixel – keine Mojang-Texturen.
	 */
	public static int[] blankTemplate(boolean slim) {
		int[] px = new int[SkinLayout.SIZE * SkinLayout.SIZE];
		SkinLayout l = SkinLayout.of(slim);
		int skin = 0xFFC9946E;
		int hair = 0xFF4A3222;
		int shirt = 0xFF2E8B8B;
		int pants = 0xFF3A3F8F;
		int shoes = 0xFF555555;
		for (int y = 0; y < SkinLayout.SIZE; y++) {
			for (int x = 0; x < SkinLayout.SIZE; x++) {
				int r = l.region(x, y);
				if (r < 0 || SkinLayout.layerOf(r) != SkinLayout.BASE) continue;
				int part = SkinLayout.partOf(r);
				int face = r % 6;
				int[] rect = l.rect(r);
				int row = y - rect[1];
				int c;
				switch (part) {
					case SkinLayout.HEAD:
						c = face == SkinLayout.TOP || (face != SkinLayout.BOTTOM && row < 2) || face == SkinLayout.BACK ? hair : skin;
						break;
					case SkinLayout.BODY:
						c = shirt;
						break;
					case SkinLayout.RIGHT_ARM:
					case SkinLayout.LEFT_ARM:
						c = face == SkinLayout.TOP || (face != SkinLayout.BOTTOM && row < 4) ? shirt : skin;
						break;
					default:
						c = face == SkinLayout.BOTTOM || (face != SkinLayout.TOP && row >= rect[3] - 2) ? shoes : pants;
						break;
				}
				px[y * SkinLayout.SIZE + x] = c;
			}
		}
		// Augen und Mund auf der Gesichtsseite
		int[] front = l.rect(SkinLayout.regionId(SkinLayout.HEAD, SkinLayout.BASE, SkinLayout.FRONT));
		int fx = front[0];
		int fy = front[1];
		px[(fy + 4) * 64 + fx + 1] = 0xFFFFFFFF;
		px[(fy + 4) * 64 + fx + 2] = 0xFF3B5BA8;
		px[(fy + 4) * 64 + fx + 5] = 0xFF3B5BA8;
		px[(fy + 4) * 64 + fx + 6] = 0xFFFFFFFF;
		for (int x = 3; x <= 4; x++) px[(fy + 6) * 64 + fx + x] = 0xFF8A4F3A;
		return px;
	}
}
