package dev.theredstonee.trsclient.core.screenshot;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Vorschau oben rechts nach einem Bildschirmfoto. Ist ein Bildschirm offen (Chat, Inventar …), reagiert sie auf die
 * Maus: beim Überfahren dunkelt das Bild ab und vier Symbol-Knöpfe erscheinen – Bearbeiten (oben links), Favorit
 * (oben rechts), Bild kopieren (unten links), An Freunde senden (unten rechts), jeweils mit Tooltip. Solange der Zeiger
 * darauf liegt, läuft die Zeit nicht ab. Zusätzlich die Bild-Vorschau am Zeiger über der Chatzeile.
 */
public final class ScreenshotToast {
	static final int WIDTH = 152;
	static final int PAD = 4;
	static final int MARGIN = 4;
	static final int CAPTION_H = 22;
	static final int BUTTON = 20;
	static final long SLIDE_MS = 220L;
	/** Öffnet der Spieler einen Bildschirm, bleibt die Vorschau noch mindestens so lange. */
	static final long GRACE_MS = 3_000L;
	static final int HOVER_W = 208;

	private static final Screenshots.Action[] CORNERS = {Screenshots.Action.EDIT, Screenshots.Action.FAVORITE,
			Screenshots.Action.COPY, Screenshots.Action.SEND};

	private final Screenshots owner;
	private Path file;
	private long modified;
	private long elapsed;
	private long shownAt;
	private long lastFrame;
	private boolean hovered;
	private boolean graceGiven;
	private boolean wasFree;
	/** Rechtecke des letzten Bilds {x, y, w, h}: Karte und die vier Knöpfe; live = Maus aktiv. */
	private final int[] box = new int[4];
	private final int[][] buttons = new int[4][4];
	private boolean live;
	// Vorschau über der Chatzeile (gilt nur für das nächste Bild)
	private Path hoverFile;
	private int hoverX;
	private int hoverY;

	ScreenshotToast(Screenshots owner) {
		this.owner = owner;
	}

	void show(Path f, long now) {
		file = f;
		try {
			modified = Files.getLastModifiedTime(f).toMillis();
		} catch (IOException | RuntimeException e) {
			modified = now;
		}
		elapsed = 0;
		shownAt = now;
		lastFrame = 0;
		hovered = false;
		graceGiven = false;
	}

	void hide() {
		file = null;
		live = false;
	}

	public boolean visible() {
		return file != null;
	}

	/** Datei der sichtbaren Vorschau oder null (Selbsttest). */
	public Path file() {
		return file;
	}

	/** Liegt der Zeiger gerade auf der Vorschau (Knöpfe sichtbar)? */
	public boolean hovered() {
		return hovered;
	}

	/** Mitte eines Knopfes im letzten Bild {x, y} (Selbsttest; 0 = Bearbeiten … 3 = Senden) oder null. */
	public int[] buttonCenter(int index) {
		if (!visible() || index < 0 || index > 3) return null;
		int[] b = buttons[index];
		return b[2] == 0 ? null : new int[]{b[0] + b[2] / 2, b[1] + b[3] / 2};
	}

	/** Mitte der Karte im letzten Bild (Selbsttest) oder null. */
	public int[] center() {
		return visible() && box[2] > 0 ? new int[]{box[0] + box[2] / 2, box[1] + box[3] / 2} : null;
	}

	boolean chatHoverPending() {
		return hoverFile != null;
	}

	void chatHover(Path f, int mx, int my) {
		hoverFile = f;
		hoverX = mx;
		hoverY = my;
	}

	void render(Canvas c, int width, int height, int mx, int my, boolean mouseFree) {
		long now = System.currentTimeMillis();
		Path hover = hoverFile;
		hoverFile = null;
		if (file != null) drawToast(c, width, height, mx, my, mouseFree, now);
		else live = false;
		if (hover != null) drawHoverPreview(c, width, height, hover);
		wasFree = mouseFree;
	}

	private void drawToast(Canvas c, int width, int height, int mx, int my, boolean mouseFree, long now) {
		long duration = owner.toastMillis();
		long dt = lastFrame == 0 ? 0 : Math.max(0, Math.min(250, now - lastFrame));
		lastFrame = now;
		if (mouseFree && !wasFree && !graceGiven) {
			graceGiven = true;
			elapsed = Math.min(elapsed, Math.max(0, duration - GRACE_MS));
		}
		if (!hovered) elapsed += dt;
		if (elapsed >= duration) {
			hide();
			return;
		}
		Theme t = Theme.get();
		float aspect = owner.thumbs().aspect(file);
		if (aspect <= 0) aspect = 16f / 9f;
		// Breite nach dem längsten Text (Hinweis „Chat öffnen …“ bzw. Dateiname), höchstens 220 px.
		String title = I18n.tr("screenshots.toast.title");
		String hint = I18n.tr("screenshots.toast.hint");
		String name = file.getFileName().toString();
		int need = Math.max(c.textWidth(title), Math.max(c.textWidth(hint), c.textWidth(name))) + PAD * 2 + 4;
		int w = Math.min(Math.max(WIDTH, need), Math.min(220, width - MARGIN * 2));
		int imgW = w - PAD * 2;
		int imgH = Math.max(40, Math.min(120, Math.round(imgW / aspect)));
		int h = PAD + imgH + CAPTION_H;
		long age = now - shownAt;
		long left = duration - elapsed;
		float in = Math.min(1f, age / (float) SLIDE_MS);
		float out = Math.min(1f, left / (float) SLIDE_MS);
		float vis = Math.min(in, out);
		int x = width - w - MARGIN + Math.round((1 - vis) * (w + MARGIN * 2));
		int y = MARGIN;
		box[0] = x;
		box[1] = y;
		box[2] = w;
		box[3] = h;
		hovered = mouseFree && mx >= x && my >= y && mx < x + w && my < y + h;
		live = mouseFree;

		c.push();
		c.raise(200f);
		Paint.shadow(c, x, y, w, h, 3, 0.35f * vis);
		Redstone.stone(c, x, y, w, h, t.surface, hovered ? t.accent : ColorMath.lerp(t.border, t.accent, 0.5f));
		int ix = x + PAD;
		int iy = y + PAD;
		c.fill(ix, iy, ix + imgW, iy + imgH, 0xFF0B0909);
		TextureRef ref = owner.thumbs().get(file, modified);
		if (ref != null && c.images()) {
			fit(c, ref, ix, iy, imgW, imgH, hovered ? 0xFF6A6A6A : 0xFFFFFFFF);
		} else {
			Icons.draw(c, "image", ix + imgW / 2 - 8, iy + imgH / 2 - 8, 2, t.textDim);
		}
		boolean fav = owner.isFavorite(file);
		if (fav && !hovered) Icons.draw(c, "heart", ix + imgW - 11, iy + 3, 1, t.dustOn);
		// Zeitbalken (Redstone-Staub) an der Unterkante des Bildes.
		int barW = Math.round(imgW * Math.max(0f, 1f - elapsed / (float) duration));
		if (barW > 0) Redstone.dustH(c, ix, ix + barW, iy + imgH - 2, t.dustOn, hovered ? 1f : 0.4f);

		String sub = mouseFree ? name : hint;
		Paint.textClipped(c, title, x + PAD + 1, iy + imgH + 3, w - PAD * 2 - 2, t.text, false);
		Paint.textClipped(c, sub, x + PAD + 1, iy + imgH + 12, w - PAD * 2 - 2, t.textDim, false);

		int hoveredButton = -1;
		for (int i = 0; i < 4; i++) buttons[i][2] = 0;
		if (hovered) {
			for (int i = 0; i < 4; i++) {
				int bx = i % 2 == 0 ? ix + 3 : ix + imgW - BUTTON - 3;
				int by = i < 2 ? iy + 3 : iy + imgH - BUTTON - 3;
				buttons[i][0] = bx;
				buttons[i][1] = by;
				buttons[i][2] = BUTTON;
				buttons[i][3] = BUTTON;
				boolean over = mx >= bx && my >= by && mx < bx + BUTTON && my < by + BUTTON;
				if (over) hoveredButton = i;
				boolean enabled = enabled(CORNERS[i]);
				String icon = icon(CORNERS[i], fav);
				if (enabled) {
					Redstone.iconButton(c, bx, by, BUTTON, icon, over, CORNERS[i] == Screenshots.Action.FAVORITE && fav);
				} else {
					Redstone.stone(c, bx, by, BUTTON, BUTTON, t.surface, t.border);
					Icons.draw(c, icon, bx + 2, by + 2, 2, t.textDim);
				}
			}
		}
		c.flush();
		if (hoveredButton >= 0) {
			Screenshots.Action a = CORNERS[hoveredButton];
			String tip = enabled(a) ? I18n.tr(label(a, fav)) : I18n.tr("screenshots.unsupported");
			tooltip(c, tip, x, y + h + 3, w, width);
		}
		c.pop();
	}

	private boolean enabled(Screenshots.Action a) {
		return a != Screenshots.Action.SEND || owner.platform().online();
	}

	static String icon(Screenshots.Action a, boolean fav) {
		switch (a) {
			case EDIT:
				return "pencil";
			case FAVORITE:
				return fav ? "heart" : "heartOutline";
			case COPY:
				return "copy";
			case SEND:
				return "send";
			case LINK:
				return "link";
			default:
				return "image";
		}
	}

	static String label(Screenshots.Action a, boolean fav) {
		switch (a) {
			case EDIT:
				return "screenshots.action.edit";
			case FAVORITE:
				return fav ? "screenshots.action.unfavorite" : "screenshots.action.favorite";
			case COPY:
				return "screenshots.action.copy";
			case SEND:
				return "screenshots.action.send";
			case LINK:
				return "screenshots.action.link";
			default:
				return "screenshots.action.edit";
		}
	}

	/** Kleiner Hinweis unter der Vorschau (rechtsbündig, nie über den Rand). */
	static void tooltip(Canvas c, String text, int x, int y, int w, int screenW) {
		Theme t = Theme.get();
		int tw = c.textWidth(text) + 8;
		int tx = Math.max(2, Math.min(x + w - tw, screenW - tw - 2));
		c.push();
		c.raise(50f);
		Redstone.stone(c, tx, y, tw, 13, t.surfaceHigh, t.border);
		c.text(text, tx + 4, y + 3, t.text, false);
		c.flush();
		c.pop();
	}

	private void drawHoverPreview(Canvas c, int width, int height, Path f) {
		long mod;
		try {
			mod = Files.getLastModifiedTime(f).toMillis();
		} catch (IOException | RuntimeException e) {
			return;
		}
		TextureRef ref = owner.thumbs().get(f, mod);
		Theme t = Theme.get();
		float aspect = owner.thumbs().aspect(f);
		if (aspect <= 0) aspect = 16f / 9f;
		int w = Math.min(HOVER_W, width - 8);
		int h = Math.round((w - 6) / aspect) + 6;
		int x = Math.max(4, Math.min(hoverX + 10, width - w - 4));
		int y = hoverY - h - 10;
		if (y < 4) y = Math.min(height - h - 4, hoverY + 14);
		c.push();
		c.raise(260f);
		Paint.shadow(c, x, y, w, h, 3, 0.4f);
		Redstone.stone(c, x, y, w, h, t.surface, ColorMath.lerp(t.border, t.accent, 0.6f));
		c.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFF0B0909);
		if (ref != null && c.images()) fit(c, ref, x + 3, y + 3, w - 6, h - 6, 0xFFFFFFFF);
		else Paint.textCentered(c, I18n.tr("clips.loading"), x + w / 2, y + h / 2 - 4, t.textDim, false);
		c.flush();
		c.pop();
	}

	/** Bild ins Rechteck einpassen (Seitenverhältnis bleibt, mittig). */
	static void fit(Canvas c, TextureRef ref, int x, int y, int w, int h, int argb) {
		float s = Math.min(w / (float) ref.width, h / (float) ref.height);
		float dw = ref.width * s;
		float dh = ref.height * s;
		Affine.image(c, ref, x + (w - dw) / 2f, y + (h - dh) / 2f, dw, dh, 0, 0, ref.width, ref.height, argb);
	}

	/** Klick (GUI-Koordinaten) – nur solange die Maus frei ist und die Vorschau zu sehen war. */
	boolean click(double mx, double my, int button) {
		if (file == null || !live || button != 0) return false;
		if (mx < box[0] || my < box[1] || mx >= box[0] + box[2] || my >= box[1] + box[3]) return false;
		Path f = file;
		for (int i = 0; i < 4; i++) {
			int[] b = buttons[i];
			if (b[2] > 0 && mx >= b[0] && my >= b[1] && mx < b[0] + b[2] && my < b[1] + b[3]) {
				owner.platform().playClick();
				if (!enabled(CORNERS[i])) return true;
				owner.run(CORNERS[i], f, null);
				return true;
			}
		}
		owner.platform().playClick();
		owner.run(Screenshots.Action.EDIT, f, null);
		return true;
	}
}
