package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.waypoint.live.LiveShare;

import java.util.List;

/**
 * Wegpunkt anlegen oder bearbeiten – derselbe Dialog auf der Weltkarte, in der Wegpunkt-Liste und auf der Taste im Spiel:
 * Name, Kürzel oder Pixel-Symbol, freie Farbe, Set, Koordinaten, Ziel, mit Freunden teilen.
 */
public final class WaypointDialog {
	/** Verbindung zum Bildschirm, in dem der Dialog liegt. */
	public interface Host {
		void click();

		/** Der Dialog ist zu (gespeichert oder abgebrochen). */
		default void dialogClosed() {
		}
	}

	public static final int[] COLORS = {0xE0281E, 0xFF7A1F, 0xFFB84D, 0xF2E14C, 0x3DDC84, 0x2FA85A, 0x4DD8E0, 0x3D7BFF,
			0xB07CFF, 0xFF6FB5, 0xFFFFFF, 0xB8B8C8};
	private static final int W = 304;
	private static final int H = 164;
	private static final int FIELD_H = 15;
	private static final int CELL = 14;
	private static final int SYMBOLS_PER_ROW = 10;

	private final MapEngine e;
	private final Host host;
	private boolean open;
	private Waypoint editing;
	private String dim;
	private final TextInput name = new TextInput(32);
	private final TextInput mark = new TextInput(2);
	private final TextInput inX = new TextInput(9);
	private final TextInput inY = new TextInput(6);
	private final TextInput inZ = new TextInput(9);
	private final TextInput hex = new TextInput(7);
	private final TextInput newSet = new TextInput(WaypointStore.MAX_SET_NAME);
	private boolean creatingSet;
	private int color = COLORS[0];
	private String symbol = "";
	private String setId = WaypointStore.SET_DEFAULT;
	private boolean shared;
	private boolean target;
	private boolean pickerOpen;
	private final float[] hsv = new float[3];
	private final int[] rect = new int[4];

	public WaypointDialog(MapEngine engine, Host host) {
		this.e = engine;
		this.host = host;
	}

	public boolean isOpen() {
		return open;
	}

	public Waypoint editing() {
		return editing;
	}

	private WaypointStore store() {
		MapPlatform p = e.platform();
		return p == null ? null : p.waypoints();
	}

	private String worldKey() {
		MapPlatform p = e.platform();
		return p == null ? "" : p.waypointWorldKey();
	}

	/** Neuer Wegpunkt an dieser Stelle. */
	public void openNew(String defaultName, int x, int y, int z, String dimension, int rgb) {
		editing = null;
		open = true;
		name.setText(defaultName);
		mark.setText("");
		inX.setText(Integer.toString(x));
		inY.setText(Integer.toString(y));
		inZ.setText(Integer.toString(z));
		color = rgb & 0xFFFFFF;
		symbol = "";
		dim = dimension;
		shared = false;
		target = false;
		WaypointStore s = store();
		setId = s == null ? WaypointStore.SET_DEFAULT : s.lastSet(worldKey());
		reset();
		focus(name);
	}

	/** Vorhandenen Wegpunkt bearbeiten. */
	public void openEdit(Waypoint w) {
		editing = w;
		open = true;
		name.setText(w.name);
		mark.setText(w.mark);
		inX.setText(Integer.toString(w.x));
		inY.setText(Integer.toString(w.y));
		inZ.setText(Integer.toString(w.z));
		color = w.color & 0xFFFFFF;
		symbol = w.symbol == null ? "" : w.symbol;
		dim = w.dimension;
		shared = w.shared;
		setId = w.setId();
		WaypointStore s = store();
		target = s != null && s.target(worldKey()) == w;
		reset();
		focus(name);
	}

	private void reset() {
		pickerOpen = false;
		creatingSet = false;
		newSet.clear();
		hex.setText(hexOf(color));
	}

	public void close() {
		if (!open) return;
		open = false;
		focus(null);
		newSet.setFocused(false);
		host.dialogClosed();
	}

	private static String hexOf(int rgb) {
		return String.format("#%06X", rgb & 0xFFFFFF);
	}

	private void focus(TextInput input) {
		name.setFocused(input == name);
		mark.setFocused(input == mark);
		inX.setFocused(input == inX);
		inY.setFocused(input == inY);
		inZ.setFocused(input == inZ);
		hex.setFocused(input == hex);
	}

	private TextInput focused() {
		if (creatingSet) return newSet;
		if (name.focused()) return name;
		if (mark.focused()) return mark;
		if (inX.focused()) return inX;
		if (inY.focused()) return inY;
		if (inZ.focused()) return inZ;
		if (hex.focused()) return hex;
		return null;
	}

	// --- Zeichnen ---

	public void draw(Canvas c, Hits hits, int w, int h, int mx, int my) {
		if (!open) return;
		Theme t = Theme.get();
		c.fill(0, 0, w, h, 0x80000000);
		int dw = Math.min(W, w - 8), dh = H;
		int x = (w - dw) / 2, y = Math.max(2, (h - dh) / 2);
		rect[0] = x;
		rect[1] = y;
		rect[2] = dw;
		rect[3] = dh;
		c.flush();
		c.push();
		c.raise(100);
		Redstone.window(c, x, y, dw, dh);
		// Klicks auf leere Stellen des Fensters nehmen nur den Fokus.
		hits.add(x, y, dw, dh, () -> focus(null));
		c.text(I18n.tr(editing == null ? "map.newWaypoint" : "map.editWaypoint"), x + 10, y + 8, t.text, false);
		if (dim != null && !dim.isEmpty() && e.platform() != null && !dim.equals(e.platform().dimension())) {
			Paint.textRight(c, WorldMapUi.dimensionName(dim), x + dw - 10, y + 8, t.textDim, false);
		}

		int gap = 10;
		int leftW = (dw - 20 - gap) * 11 / 25;
		int lx = x + 10, rx = lx + leftW + gap, rw = dw - 20 - gap - leftW;

		// Links: Name, Koordinaten, Set, Schalter.
		int ly = y + 22;
		c.text(I18n.tr("waypoint.name"), lx, ly, t.textDim, false);
		field(c, hits, name, lx, ly + 9, leftW, t);
		ly += 28;
		int fw = (leftW - 6) / 3;
		c.text("X", lx, ly, t.textDim, false);
		c.text("Y", lx + fw + 3, ly, t.textDim, false);
		c.text("Z", lx + 2 * (fw + 3), ly, t.textDim, false);
		field(c, hits, inX, lx, ly + 9, fw, t);
		field(c, hits, inY, lx + fw + 3, ly + 9, fw, t);
		field(c, hits, inZ, lx + 2 * (fw + 3), ly + 9, fw, t);
		ly += 28;
		c.text(I18n.tr("waypoint.set"), lx, ly, t.textDim, false);
		setChooser(c, hits, lx, ly + 9, leftW, mx, my, t);
		ly += 29;
		check(c, hits, lx, ly, leftW, I18n.tr("waypoint.setTarget"), target, mx, my, t, () -> target = !target);
		if (LiveShare.ui() != null) {
			ly += 13;
			check(c, hits, lx, ly, leftW, I18n.tr("waypoint.shareFriends"), shared, mx, my, t, () -> shared = !shared);
		}

		// Rechts: Aussehen.
		int ry = y + 22;
		c.text(I18n.tr("waypoint.look"), rx, ry, t.textDim, false);
		String text = mark.isEmpty() ? Waypoint.initial(name.text()) : Waypoint.cleanMark(mark.text());
		WaypointBadge.draw(c, symbol, text, color, rx + rw - 12, ry + 11, 1.5f, 255);
		c.text(I18n.tr("waypoint.mark"), rx, ry + 12, t.textDim, false);
		int mw = c.textWidth(I18n.tr("waypoint.mark")) + 4;
		field(c, hits, mark, rx + mw, ry + 8, 30, t);
		ry += 26;
		if (pickerOpen) {
			picker(c, hits, rx, ry, rw, t);
		} else {
			symbols(c, hits, rx, ry, rw, mx, my, t);
			ry += 2 * CELL + 3;
			colors(c, hits, rx, ry, rw, mx, my, t);
		}

		// Knöpfe.
		int bw = 62;
		int by = y + dh - 22;
		boolean hovSave = inside(mx, my, x + dw - 10 - bw, by, bw, 16);
		Redstone.button(c, x + dw - 10 - bw, by, bw, 16, I18n.tr("map.save"), true, hovSave);
		hits.add(x + dw - 10 - bw, by, bw, 16, this::save);
		boolean hovCancel = inside(mx, my, x + dw - 14 - 2 * bw, by, bw, 16);
		Redstone.button(c, x + dw - 14 - 2 * bw, by, bw, 16, I18n.tr("map.cancel"), false, hovCancel);
		hits.add(x + dw - 14 - 2 * bw, by, bw, 16, () -> {
			host.click();
			close();
		});
		if (editing != null) {
			boolean hovDel = inside(mx, my, x + 10, by, bw, 16);
			Redstone.button(c, x + 10, by, bw, 16, I18n.tr("map.menu.delete"), false, hovDel);
			hits.add(x + 10, by, bw, 16, () -> {
				host.click();
				e.removeWaypoint(editing);
				close();
			});
		}
		c.pop();
	}

	private void field(Canvas c, Hits hits, final TextInput input, int x, int y, int w, Theme t) {
		Redstone.well(c, x, y, w, FIELD_H, input.focused() ? t.accent : t.border);
		String shown = c.clip(input.text(), w - 6);
		c.text(shown, x + 3, y + 4, t.text, false);
		if (input.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cursorX = x + 3 + c.textWidth(input.text().substring(0, Math.min(input.cursor(), input.text().length())));
			if (cursorX < x + w - 2) c.fill(cursorX, y + 2, cursorX + 1, y + 13, t.text);
		}
		hits.add(x, y, w, FIELD_H, () -> {
			creatingSet = false;
			newSet.setFocused(false);
			focus(input);
		});
	}

	private void setChooser(Canvas c, Hits hits, int x, int y, int w, int mx, int my, Theme t) {
		WaypointStore s = store();
		if (s == null) return;
		if (creatingSet) {
			Redstone.well(c, x, y, w - 18, FIELD_H, t.accent);
			String shown = newSet.isEmpty() ? I18n.tr("waypoint.setName") : c.clip(newSet.text(), w - 24);
			c.text(shown, x + 3, y + 4, newSet.isEmpty() ? t.textDim : t.text, false);
			if (!newSet.isEmpty() && (System.currentTimeMillis() / 500) % 2 == 0) {
				int cx = x + 3 + c.textWidth(newSet.text());
				if (cx < x + w - 20) c.fill(cx, y + 2, cx + 1, y + 13, t.text);
			}
			hits.add(x, y, w - 18, FIELD_H, () -> newSet.setFocused(true));
			Redstone.iconButton(c, x + w - 15, y, 15, "check", inside(mx, my, x + w - 15, y, 15, 15), true);
			hits.add(x + w - 15, y, 15, 15, () -> {
				host.click();
				commitNewSet();
			});
			return;
		}
		final List<WaypointSet> sets = s.sets(worldKey());
		int arrows = w - 18;
		boolean hov = inside(mx, my, x, y, arrows, FIELD_H);
		Redstone.button(c, x, y, arrows, FIELD_H, "", false, hov);
		c.text("‹", x + 4, y + 4, t.textDim, false);
		c.text("›", x + arrows - 8, y + 4, t.textDim, false);
		String label = c.clip(setLabel(sets, setId), arrows - 22);
		c.text(label, x + (arrows - c.textWidth(label)) / 2, y + 4, t.text, false);
		hits.add(x, y, arrows / 2, FIELD_H, () -> cycleSet(sets, -1));
		hits.add(x + arrows / 2, y, arrows - arrows / 2, FIELD_H, () -> cycleSet(sets, 1));
		Redstone.iconButton(c, x + w - 15, y, 15, "plus", inside(mx, my, x + w - 15, y, 15, 15), false);
		hits.add(x + w - 15, y, 15, 15, () -> {
			host.click();
			focus(null);
			creatingSet = true;
			newSet.clear();
			newSet.setFocused(true);
		});
	}

	private static String setLabel(List<WaypointSet> sets, String id) {
		for (WaypointSet s : sets) if (s.id.equals(id)) return s.label();
		return I18n.tr("waypoints.set.default");
	}

	private void cycleSet(List<WaypointSet> sets, int dir) {
		host.click();
		int i = 0;
		for (int k = 0; k < sets.size(); k++) if (sets.get(k).id.equals(setId)) i = k;
		setId = sets.get((i + dir + sets.size()) % sets.size()).id;
	}

	private void commitNewSet() {
		WaypointStore s = store();
		if (s != null) {
			WaypointSet set = s.addSet(worldKey(), newSet.text());
			if (set != null) setId = set.id;
			e.waypointEdited();
		}
		creatingSet = false;
		newSet.setFocused(false);
	}

	private void check(Canvas c, Hits hits, int x, int y, int w, String label, boolean on, int mx, int my, Theme t, final Runnable toggle) {
		boolean hov = inside(mx, my, x, y - 2, w, 13);
		Redstone.well(c, x, y, 10, 10, hov ? t.accent : t.border);
		if (on) Icons.draw(c, "check", x + 1, y + 1, 1, t.text);
		c.text(c.clip(label, w - 15), x + 14, y + 1, on ? t.text : t.textDim, false);
		hits.add(x, y - 2, w, 13, () -> {
			host.click();
			toggle.run();
		});
	}

	/** Kästchen mit Text (leer) und alle Pixel-Symbole. */
	private void symbols(Canvas c, Hits hits, int x, int y, int w, int mx, int my, Theme t) {
		int per = Math.max(5, Math.min(SYMBOLS_PER_ROW, w / CELL));
		List<String> ids = WaypointSymbols.ids();
		for (int i = -1; i < ids.size() && i + 1 < 2 * per; i++) {
			final String id = i < 0 ? "" : ids.get(i);
			int cx = x + (i + 1) % per * CELL, cy = y + (i + 1) / per * CELL;
			boolean sel = id.equals(symbol);
			boolean hov = inside(mx, my, cx, cy, CELL, CELL);
			c.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, sel ? ColorMath.withAlpha(t.dustOn, 0xC0) : hov ? 0x50FFFFFF : 0x26FFFFFF);
			if (id.isEmpty()) Paint.textCentered(c, "Aa", cx + (CELL - 1) / 2, cy + 3, sel ? t.text : t.textDim, false);
			else WaypointSymbols.draw(c, id, cx + 2, cy + 2, 1, sel ? 0xFFFFFFFF : t.text);
			hits.add(cx, cy, CELL - 1, CELL - 1, () -> {
				host.click();
				symbol = id;
			});
		}
	}

	private void colors(Canvas c, Hits hits, int x, int y, int w, int mx, int my, Theme t) {
		c.text(I18n.tr("waypoint.color"), x, y, t.textDim, false);
		y += 9;
		int sw = Math.max(9, (w - 1) / COLORS.length);
		for (int i = 0; i < COLORS.length; i++) {
			final int col = COLORS[i];
			int sx = x + i * sw;
			if (col == (color & 0xFFFFFF)) c.fill(sx - 1, y - 1, sx + sw, y + 11, 0xFFFFFFFF);
			Redstone.block(c, sx, y, sw - 1, 10, 0xFF000000 | col);
			hits.add(sx, y, sw - 1, 10, () -> {
				host.click();
				setColor(col);
			});
		}
		y += 13;
		Redstone.block(c, x, y, 12, 12, 0xFF000000 | color);
		field(c, hits, hex, x + 15, y - 1, 52, t);
		String freely = I18n.tr("waypoint.colorFree");
		int bw = Math.min(w - 72, c.textWidth(freely) + 10);
		Redstone.button(c, x + w - bw, y - 1, bw, FIELD_H, c.clip(freely, bw - 6), false, inside(mx, my, x + w - bw, y - 1, bw, FIELD_H));
		hits.add(x + w - bw, y - 1, bw, FIELD_H, () -> {
			host.click();
			float[] v = ColorMath.rgbToHsv(color);
			hsv[0] = v[0];
			hsv[1] = v[1];
			hsv[2] = v[2];
			pickerOpen = true;
			focus(null);
		});
	}

	/** Freier Farbwähler: Sättigung/Helligkeit als Fläche, Farbton als Streifen. */
	private void picker(Canvas c, Hits hits, final int x, final int y, int w, Theme t) {
		final int fieldW = Math.min(w - 20, 110), fieldH = 56;
		int cols = 22, rows = 11;
		for (int cy = 0; cy < rows; cy++) {
			for (int cx = 0; cx < cols; cx++) {
				float s = cx / (float) (cols - 1), v = 1f - cy / (float) (rows - 1);
				int x1 = x + cx * fieldW / cols, x2 = x + (cx + 1) * fieldW / cols;
				int y1 = y + cy * fieldH / rows, y2 = y + (cy + 1) * fieldH / rows;
				c.fill(x1, y1, x2, y2, 0xFF000000 | ColorMath.hsvToRgb(hsv[0], s, v));
			}
		}
		int markX = x + Math.round(hsv[1] * (fieldW - 1)), markY = y + Math.round((1 - hsv[2]) * (fieldH - 1));
		Paint.outline(c, markX - 1, markY - 1, 3, 3, 0xFFFFFFFF);
		hits.addDrag(x, y, fieldW, fieldH, (mx, my) -> {
			hsv[1] = ColorMath.clamp01((float) ((mx - x) / (fieldW - 1)));
			hsv[2] = 1f - ColorMath.clamp01((float) ((my - y) / (fieldH - 1)));
			applyHsv();
		});
		final int hueX = x + fieldW + 5, hueW = 10;
		for (int py = 0; py < fieldH; py += 2) {
			c.fill(hueX, y + py, hueX + hueW, y + Math.min(py + 2, fieldH), 0xFF000000 | ColorMath.hsvToRgb(py / (float) fieldH, 0.9f, 1f));
		}
		int hueMark = y + Math.round(hsv[0] * (fieldH - 1));
		c.fill(hueX - 2, hueMark, hueX + hueW + 2, hueMark + 1, 0xFFFFFFFF);
		hits.addDrag(hueX - 2, y, hueW + 4, fieldH, (mx, my) -> {
			hsv[0] = ColorMath.clamp01((float) ((my - y) / (fieldH - 1)));
			applyHsv();
		});
		int by = y + fieldH + 4;
		Redstone.block(c, x, by + 1, 12, 12, 0xFF000000 | color);
		field(c, hits, hex, x + 15, by, 52, t);
		Redstone.button(c, x + 72, by, Math.max(30, w - 72), FIELD_H, I18n.tr("common.close"), true, false);
		hits.add(x + 72, by, Math.max(30, w - 72), FIELD_H, () -> {
			host.click();
			pickerOpen = false;
		});
	}

	private void applyHsv() {
		color = ColorMath.hsvToRgb(hsv[0], hsv[1], hsv[2]) & 0xFFFFFF;
		hex.setText(hexOf(color));
	}

	private void setColor(int rgb) {
		color = rgb & 0xFFFFFF;
		hex.setText(hexOf(color));
	}

	/** Selbsttest: den freien Farbwähler zeigen. */
	public void testPicker() {
		float[] v = ColorMath.rgbToHsv(color);
		hsv[0] = v[0];
		hsv[1] = v[1];
		hsv[2] = v[2];
		pickerOpen = true;
	}

	// --- Speichern ---

	/** Farbe aus dem Hex-Feld, -1 = ungültig. */
	public static int parseHex(String text) {
		if (text == null) return -1;
		String s = text.trim();
		if (s.startsWith("#")) s = s.substring(1);
		if (s.length() != 6) return -1;
		try {
			return Integer.parseInt(s, 16);
		} catch (NumberFormatException ex) {
			return -1;
		}
	}

	private static int parse(String s, int fallback) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	public void save() {
		host.click();
		if (creatingSet) commitNewSet();
		int fromHex = parseHex(hex.text());
		if (fromHex >= 0) color = fromHex;
		MapPlatform p = e.platform();
		int x = parse(inX.text(), editing != null ? editing.x : p == null ? 0 : (int) Math.floor(p.x()));
		int y = parse(inY.text(), editing != null ? editing.y : p == null ? 64 : (int) Math.floor(p.y()));
		int z = parse(inZ.text(), editing != null ? editing.z : p == null ? 0 : (int) Math.floor(p.z()));
		String n = name.text().trim().isEmpty() ? Waypoint.defaultName() : name.text().trim();
		Waypoint w = editing;
		if (w == null) {
			w = e.addWaypoint(n, x, y, z, color, dim);
			if (w == null) {
				close();
				return;
			}
		} else {
			w.name = n;
			w.x = x;
			w.y = y;
			w.z = z;
			w.color = color;
		}
		w.mark = Waypoint.cleanMark(mark.text());
		w.symbol = symbol;
		w.set = WaypointStore.fieldValue(setId);
		if (w.death && WaypointStore.SET_DEATHS.equals(setId)) w.set = "";
		w.shared = shared;
		WaypointStore s = store();
		if (s != null) {
			String key = worldKey();
			s.rememberSet(key, setId);
			boolean isTarget = s.target(key) == w;
			if (target && !isTarget) s.setTarget(key, w, false);
			else if (!target && isTarget) s.setTarget(key, null, false);
		}
		e.waypointEdited();
		close();
	}

	// --- Eingaben (true = verbraucht) ---

	/** Klick außerhalb des Fensters bricht ab. */
	public boolean clickOutside(double mx, double my) {
		if (inside(mx, my, rect[0], rect[1], rect[2], rect[3])) return false;
		close();
		return true;
	}

	public boolean key(UiKey key, boolean shift) {
		if (key == UiKey.ESCAPE) {
			if (creatingSet) {
				creatingSet = false;
				newSet.setFocused(false);
			} else if (pickerOpen) {
				pickerOpen = false;
			} else {
				close();
			}
			return true;
		}
		if (key == UiKey.ENTER) {
			if (creatingSet) commitNewSet();
			else save();
			return true;
		}
		if (key == UiKey.TAB) {
			TextInput f = focused();
			TextInput[] order = {name, mark, inX, inY, inZ, hex};
			int i = 0;
			for (int k = 0; k < order.length; k++) if (order[k] == f) i = k;
			focus(order[(i + (shift ? order.length - 1 : 1)) % order.length]);
			return true;
		}
		TextInput f = focused();
		boolean used = f != null && f.key(key);
		if (used && f == hex) applyHexField();
		return used || f != null;
	}

	public boolean type(char ch) {
		TextInput f = focused();
		if (f == null) return false;
		if ((f == inX || f == inY || f == inZ) && !(Character.isDigit(ch) || ch == '-')) return true;
		if (f == hex && Character.digit(ch, 16) < 0 && ch != '#') return true;
		f.type(ch);
		if (f == hex) applyHexField();
		return true;
	}

	private void applyHexField() {
		int v = parseHex(hex.text());
		if (v >= 0) color = v;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
