package dev.theredstonee.trsclient.core.ui;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.social.SafeText;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;

/**
 * Kleines Fenster „Als Wegpunkt speichern“ nach einem Klick auf Koordinaten im Minecraft-Chat: Name (vorbelegt),
 * Farbe, Speichern. Legt den Wegpunkt in der aktuellen Welt/Dimension an – nur lokal, es wird nichts gesendet.
 */
public final class WaypointSaveUi extends UiScreen {
	/** Anbindung an den Loader. */
	public interface Host {
		void closeScreen();

		void playClick();

		/** Hinweis nach dem Schließen (Aktionsleiste). */
		void notice(String text);

		/** Text aus der Zwischenablage oder null. */
		String paste();
	}

	static final int[] COLORS = {0xE0281E, 0xFF7A1F, 0xF2E14C, 0x3DDC84, 0x4DD8E0, 0x3D7BFF, 0xB07CFF, 0xFF6FB5, 0xFFFFFF};
	private static final int W = 230;
	private static final int H = 118;

	private final Host host;
	private final int x;
	private final int y;
	private final int z;
	private final TextInput name = new TextInput(SafeText.MAX_NAME);
	private int color = COLORS[5];
	private final int[] rect = new int[4];

	public WaypointSaveUi(Host host, int x, int y, int z, String defaultName) {
		this.host = host;
		this.x = x;
		this.y = y;
		this.z = z;
		I18n.refresh();
		name.setText(defaultName == null ? "" : SafeText.line(defaultName, SafeText.MAX_NAME));
		name.setFocused(true);
	}

	@Override
	protected void draw(Canvas raw, int width, int height, int mx, int my, float dt) {
		Canvas c = FadeCanvas.of(raw, alpha());
		Theme t = Theme.get();
		int w = Math.min(W, width - 16);
		int h = H;
		int px = (width - w) / 2;
		int py = (height - h) / 2 - 20 + Math.round((1 - alpha()) * 10);
		rect[0] = px;
		rect[1] = py;
		rect[2] = w;
		rect[3] = h;
		c.push();
		c.raise(300f);
		Paint.shadow(c, px, py, w, h, 2, 0.6f);
		Redstone.window(c, px, py, w, h);
		Icons.draw(c, "pin", px + 7, py + 7, 1, 0xFF000000 | color);
		Paint.textClipped(c, I18n.tr("waypoint.save.title"), px + 19, py + 7, w - 40, t.text, false);
		iconButton(c, px + w - 18, py + 4, "close", mx, my, this::requestClose);
		int cy = py + 22;
		String where = x + " " + y + " " + z;
		String dim = WaypointShare.currentDimension();
		if (!dim.isEmpty()) where += " · " + dev.theredstonee.trsclient.core.map.WorldMapUi.dimensionName(dim);
		Paint.textClipped(c, where, px + 8, cy, w - 16, t.textDim, false);
		cy += 12;
		input(c, px + 8, cy, w - 16, 16, t);
		cy += 22;
		int sx = px + 8;
		for (final int col : COLORS) {
			boolean sel = col == color;
			c.fill(sx, cy, sx + 14, cy + 14, sel ? t.text : t.border);
			c.fill(sx + 1, cy + 1, sx + 13, cy + 13, 0xFF000000 | col);
			hits.add(sx, cy, 14, 14, () -> {
				host.playClick();
				color = col;
			});
			sx += 17;
		}
		cy += 22;
		int bw = Math.min(90, (w - 22) / 2);
		button(c, px + w - 8 - bw, cy, bw, 18, I18n.tr("waypoint.save.save"), true, mx, my, this::save);
		button(c, px + w - 14 - bw * 2, cy, bw, 18, I18n.tr("social.cancel"), false, mx, my, this::requestClose);
		c.pop();
	}

	private void input(Canvas c, int ix, int iy, int w, int h, Theme t) {
		Redstone.well(c, ix, iy, w, h, name.focused() ? t.accent : t.border);
		String text = name.text();
		String visible = text;
		while (c.textWidth(visible) > w - 10 && visible.length() > 1) visible = visible.substring(1);
		if (text.isEmpty()) c.text(I18n.tr("waypoint.save.nameHint"), ix + 5, iy + (h - 8) / 2, t.textDim, false);
		else c.text(visible, ix + 5, iy + (h - 8) / 2, t.text, false);
		if (name.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = ix + 5 + c.textWidth(visible);
			c.fill(cx, iy + 3, cx + 1, iy + h - 3, t.text);
		}
		hits.add(ix, iy, w, h, () -> name.setFocused(true));
	}

	private void button(Canvas c, int bx, int by, int w, int h, String label, boolean primary, int mx, int my, final Runnable r) {
		Redstone.button(c, bx, by, w, h, label, primary, mx >= bx && my >= by && mx < bx + w && my < by + h);
		hits.add(bx, by, w, h, () -> {
			host.playClick();
			r.run();
		});
	}

	private void iconButton(Canvas c, int bx, int by, String icon, int mx, int my, final Runnable r) {
		Paint.iconButton(c, bx, by, 14, icon, mx >= bx && my >= by && mx < bx + 14 && my < by + 14, false);
		hits.add(bx, by, 14, 14, () -> {
			host.playClick();
			r.run();
		});
	}

	private void save() {
		String n = SafeText.line(name.text(), SafeText.MAX_NAME);
		if (n == null || n.isEmpty()) n = I18n.tr("waypoint.save.defaultName");
		WaypointShare.Result r = WaypointShare.saveHere(n, x, y, z, color);
		String text = r == WaypointShare.Result.ADDED || r == WaypointShare.Result.ALREADY ? I18n.tr(r.key(), n) : I18n.tr(r.key());
		requestClose();
		host.notice(text);
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (key == UiKey.ESCAPE) {
			requestClose();
			return true;
		}
		if (key == UiKey.ENTER) {
			save();
			return true;
		}
		if (key == UiKey.PASTE) {
			String p = host.paste();
			if (p != null) {
				for (char ch : SafeText.line(p, SafeText.MAX_NAME).toCharArray()) name.type(ch);
			}
			return true;
		}
		return name.key(key);
	}

	@Override
	public boolean charTyped(char ch) {
		return name.type(ch);
	}

	@Override
	protected void onClosed() {
		host.closeScreen();
	}

	@Override
	public boolean overlay() {
		return true;
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	/** Selbsttest: Name setzen und speichern. */
	public void testSave(String n) {
		name.setText(n);
		save();
	}
}
