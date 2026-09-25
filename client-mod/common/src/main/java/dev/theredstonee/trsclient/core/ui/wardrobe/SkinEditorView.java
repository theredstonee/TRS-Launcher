package dev.theredstonee.trsclient.core.ui.wardrobe;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.skin.SkinModel;
import dev.theredstonee.trsclient.core.skin.SkinModelSpec;
import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.wardrobe.SkinEditor;
import dev.theredstonee.trsclient.core.wardrobe.SkinLayout;

import java.util.List;

/**
 * Der volle Skin-Editor als Bereich der Garderobe: links Werkzeuge, Mitte die 3D-Figur (malen mit links, drehen mit
 * rechts oder auf freier Fläche), rechts die UV-Ansicht (64×64) und die Farben (Palette, zuletzt benutzt, Hex).
 * Oben Name, Vorlagen, Speichern, Anwenden, Zurück. Die Logik steckt in {@link SkinEditor}; hier nur Zeichnen/Eingabe.
 * Die Textur wird nur neu hochgeladen, wenn sich Pixel geändert haben (höchstens einmal je Bild).
 */
final class SkinEditorView {
	/** Was der Editor von der Garderobe braucht. */
	interface Actions {
		void save(int[] pixels, boolean slim, String name, String replaceId);

		void apply(int[] pixels, boolean slim);

		/** Editor verlassen. */
		void close();

		/** Pixel des aktuellen Minecraft-Skins oder null. */
		int[] currentSkin();

		boolean currentSlim();

		void click();

		/** Kurze Meldung unten anzeigen (i18n-Schlüssel). */
		void toast(String key);
	}

	static final int[] PALETTE = {
			0xFFFFFFFF, 0xFFC0C0C0, 0xFF808080, 0xFF404040, 0xFF1A1A1A, 0xFF000000,
			0xFFB02E26, 0xFFF9801D, 0xFFFED83D, 0xFF80C71F, 0xFF5E7C16, 0xFF169C9C,
			0xFF3AB3DA, 0xFF3C44AA, 0xFF8932B8, 0xFFC74EBD, 0xFFF38BAA, 0xFF835432,
			0xFFFFDBAC, 0xFFF1C27D, 0xFFE0AC69, 0xFFC68642, 0xFF8D5524, 0xFF5C3A21};

	private static final int ACTION_H = 18;
	private static final int TOOL = 16;
	/** Höhe aller Werkzeuge untereinander (11 Knöpfe + 3 Trennabstände). */
	private static final int TOOLS_HEIGHT = 11 * (16 + 2) + 9;

	private final Actions actions;
	private final WardrobeTextures textures;
	final SkinEditor editor = new SkinEditor();
	private final SkinModel model = new SkinModel();
	private final SkinModelSpec spec = new SkinModelSpec();
	final TextInput name = new TextInput(48);
	final TextInput hex = new TextInput(9);
	/** Bestehender Bibliotheks-Skin (Speichern überschreibt) oder null (neu). */
	String replaceId;
	private boolean showOverlay = true;
	private boolean templatesOpen;
	private boolean discardArmed;
	private long discardUntil;
	private float yaw = 25f;
	private float pitch = 10f;
	private boolean saving;

	// Geometrie des letzten Bildes (für Eingaben)
	private final int[] view3d = new int[4];
	private final int[] view2d = new int[4];
	private int uvScale = 2;
	private float figCx;
	private float figFeet;
	private float figScale;

	// Eingabe
	private int mode; // 0 nichts, 1 malen 3D, 2 malen 2D, 3 drehen
	private double lastX;
	private double lastY;
	private int lastTexelX = -1;
	private int lastTexelY = -1;
	private int hoverTexel = -1;

	SkinEditorView(Actions actions, WardrobeTextures textures) {
		this.actions = actions;
		this.textures = textures;
	}

	/** Neues Bild laden. */
	void load(int[] pixels, boolean slim, String skinName, String id) {
		editor.load(pixels, slim);
		name.setText(skinName == null ? "" : skinName);
		name.setFocused(false);
		hex.setText(SkinEditor.hex(editor.color()));
		replaceId = id;
		templatesOpen = false;
		discardArmed = false;
		mode = 0;
	}

	void saved(String id) {
		replaceId = id;
		editor.markSaved();
		saving = false;
	}

	boolean textFocused() {
		return name.focused() || hex.focused();
	}

	// ============================================================================================
	// Zeichnen
	// ============================================================================================

	void draw(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		if (discardArmed && System.currentTimeMillis() > discardUntil) discardArmed = false;
		actionRow(c, hits, x, y, w, mx, my);
		int top = y + ACTION_H + 4;
		int bodyH = h - ACTION_H - 4;

		// Rechte Spalte: UV-Ansicht + Farben
		int colorsH = 66;
		int scale = Math.max(1, Math.min(6, Math.min((bodyH - colorsH - 4) / 64, (w - 24 - 110) / 64)));
		uvScale = scale;
		int rightW = Math.max(64 * scale, 124);
		int rx = x + w - rightW;
		int uvX = rx + (rightW - 64 * scale) / 2;
		uv(c, hits, uvX, top, scale, mx, my);
		colors(c, hits, rx, top + 64 * scale + 4, rightW, bodyH - 64 * scale - 4, mx, my);

		// Werkzeugleiste (zwei Spalten, wenn eine nicht reicht)
		int toolCols = bodyH >= TOOLS_HEIGHT ? 1 : 2;
		tools(c, hits, x, top, toolCols == 1 ? Integer.MAX_VALUE : (TOOLS_HEIGHT + 1) / 2, mx, my);

		// 3D-Ansicht
		int vx = x + toolCols * (TOOL + 2) + 6;
		int vw = rx - 6 - vx;
		view3d[0] = vx;
		view3d[1] = top;
		view3d[2] = vw;
		view3d[3] = bodyH;
		Redstone.well(c, vx, top, vw, bodyH, t.border);
		TextureRef tex = textures.get("wardrobe/editor", editor.pixels(), 64, 64, editor.version());
		spec.skin = tex;
		spec.slim = editor.slim();
		spec.cape = null;
		spec.yaw = yaw;
		spec.pitch = pitch;
		spec.headYaw = 0f;
		spec.headPitch = 0f;
		spec.walkAmount = 0f;
		spec.idleTime = 0f;
		spec.pose = null;
		boolean overlayLayer = editor.layer() == SkinLayout.OVERLAY;
		spec.layers = overlayLayer || showOverlay;
		spec.base = true;
		spec.tint = 0xFFFFFFFF;
		float figH = bodyH - 16;
		figScale = SkinModel.fitScale(vw - 12, figH);
		figCx = vx + vw / 2f;
		figFeet = top + 6 + figH / 2f + SkinModel.HEIGHT * figScale / 2f;
		c.scissor(vx + 1, top + 1, vx + vw - 1, top + bodyH - 1);
		if (tex != null) model.draw(c, figCx, figFeet, figScale, spec);
		c.noScissor();
		String layerKey = overlayLayer ? "wardrobe.editor.layerOverlay" : "wardrobe.editor.layerBase";
		Paint.textClipped(c, I18n.tr(layerKey), vx + 4, top + 4, vw - 8, overlayLayer ? t.dustOn : t.text, false);
		String hint = I18n.tr("wardrobe.editor.rotateHint");
		Paint.textClipped(c, hint, vx + 4, top + bodyH - 11, vw - 8, t.textDim, false);
		// Hover-Texel aus der 3D-Ansicht bestimmen
		if (inside(mx, my, view3d) && mode != 3) {
			int texel = model.pick(figCx, figFeet, figScale, spec, mx, my, editor.layer());
			hoverTexel = texel;
		} else if (!inside(mx, my, view2d)) {
			hoverTexel = -1;
		}
		if (templatesOpen) templates(c, hits, mx, my);
	}

	private void actionRow(Canvas c, Hits hits, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		String tplLabel = I18n.tr("wardrobe.editor.template");
		String saveLabel = I18n.tr("wardrobe.editor.save");
		String applyLabel = I18n.tr("wardrobe.editor.apply");
		int tw = Math.min(90, c.textWidth(tplLabel) + 14);
		int sw = Math.min(90, c.textWidth(saveLabel) + 14);
		int aw = Math.min(90, c.textWidth(applyLabel) + 14);
		int bx = x + w - ACTION_H;
		boolean hb = inside(mx, my, bx, y, ACTION_H, ACTION_H);
		Paint.iconButton(c, bx, y, ACTION_H, "back", hb, false);
		hits.add(bx, y, ACTION_H, ACTION_H, () -> {
			actions.click();
			tryClose();
		});
		int ax = bx - 4 - aw;
		boolean ha = inside(mx, my, ax, y, aw, ACTION_H);
		Redstone.button(c, ax, y, aw, ACTION_H, applyLabel, false, ha);
		hits.add(ax, y, aw, ACTION_H, () -> {
			actions.click();
			actions.apply(editor.copyPixels(), editor.slim());
		});
		int sx = ax - 4 - sw;
		boolean hs = inside(mx, my, sx, y, sw, ACTION_H);
		Redstone.button(c, sx, y, sw, ACTION_H, saveLabel, true, hs);
		hits.add(sx, y, sw, ACTION_H, () -> {
			actions.click();
			if (saving) return;
			saving = true;
			actions.save(editor.copyPixels(), editor.slim(), name.text(), replaceId);
		});
		int tx = sx - 4 - tw;
		boolean ht = inside(mx, my, tx, y, tw, ACTION_H);
		Redstone.button(c, tx, y, tw, ACTION_H, tplLabel, false, ht || templatesOpen);
		hits.add(tx, y, tw, ACTION_H, () -> {
			actions.click();
			templatesOpen = !templatesOpen;
		});
		templateAnchor[0] = tx;
		templateAnchor[1] = y + ACTION_H + 2;
		// Name
		int nw = tx - 6 - x;
		if (nw > 30) {
			field(c, name, x, y, nw, ACTION_H, I18n.tr("wardrobe.editor.namePlaceholder"), mx, my);
			hits.add(x, y, nw, ACTION_H, () -> {
				name.setFocused(true);
				hex.setFocused(false);
			});
		}
		if (editor.dirty()) c.fill(x + nw - 4, y + 2, x + nw - 2, y + 4, t.dustOn);
	}

	private final int[] templateAnchor = new int[2];

	private void templates(Canvas c, Hits hits, int mx, int my) {
		Theme t = Theme.get();
		String[] keys = {"wardrobe.editor.tplClassic", "wardrobe.editor.tplSlim", "wardrobe.editor.tplCurrent",
				"wardrobe.editor.tplClearLayer"};
		int w = 10;
		for (String k : keys) w = Math.max(w, c.textWidth(I18n.tr(k)) + 12);
		int x = templateAnchor[0];
		int y = templateAnchor[1];
		int h = keys.length * 14 + 4;
		c.flush();
		c.push();
		c.raise(50f);
		Redstone.stone(c, x, y, w, h, t.surfaceHigh, t.border);
		for (int i = 0; i < keys.length; i++) {
			final int idx = i;
			int ry = y + 2 + i * 14;
			boolean hov = inside(mx, my, x + 1, ry, w - 2, 14);
			if (hov) c.fill(x + 2, ry, x + w - 2, ry + 14, t.surfaceHover);
			boolean enabled = idx != 2 || actions.currentSkin() != null;
			Paint.textClipped(c, I18n.tr(keys[i]), x + 6, ry + 3, w - 10, enabled ? (hov ? t.dustOn : t.text) : t.textDim, false);
			if (enabled) {
				hits.add(x + 1, ry, w - 2, 14, () -> {
					actions.click();
					templatesOpen = false;
					applyTemplate(idx);
				});
			}
		}
		c.pop();
	}

	private void applyTemplate(int idx) {
		switch (idx) {
			case 0:
				editor.replace(SkinEditor.blankTemplate(false), false);
				break;
			case 1:
				editor.replace(SkinEditor.blankTemplate(true), true);
				break;
			case 2:
				int[] cur = actions.currentSkin();
				if (cur != null) editor.replace(cur, actions.currentSlim());
				break;
			default:
				editor.clearLayer();
				break;
		}
	}

	private int toolX;
	private int toolTop;
	private int toolMax;

	/** Nächste Werkzeug-Position; bricht bei {@code toolMax} in die zweite Spalte um. */
	private int place(int y, int extra) {
		if (y + TOOL - toolTop > toolMax) {
			toolX += TOOL + 2;
			return toolTop;
		}
		return y + extra;
	}

	private void tools(Canvas c, Hits hits, int x, int y, int maxH, int mx, int my) {
		Theme t = Theme.get();
		toolX = x;
		toolTop = y;
		toolMax = maxH;
		int cy = y;
		cy = toolButton(c, hits, cy, "brush", editor.tool() == SkinEditor.Tool.BRUSH, mx, my, () -> editor.setTool(SkinEditor.Tool.BRUSH));
		cy = toolButton(c, hits, cy, "eraser", editor.tool() == SkinEditor.Tool.ERASER, mx, my, () -> editor.setTool(SkinEditor.Tool.ERASER));
		cy = toolButton(c, hits, cy, "picker", editor.tool() == SkinEditor.Tool.PICKER, mx, my, () -> editor.setTool(SkinEditor.Tool.PICKER));
		cy = toolButton(c, hits, cy, "bucket", editor.tool() == SkinEditor.Tool.FILL, mx, my, () -> editor.setTool(SkinEditor.Tool.FILL));
		cy += 3;
		cy = toolButton(c, hits, cy, "mirror", editor.mirror(), mx, my, () -> editor.setMirror(!editor.mirror()));
		cy = toolButton(c, hits, cy, "layers", editor.layer() == SkinLayout.OVERLAY, mx, my,
				() -> editor.setLayer(editor.layer() == SkinLayout.OVERLAY ? SkinLayout.BASE : SkinLayout.OVERLAY));
		cy = toolButton(c, hits, cy, "eye", showOverlay, mx, my, () -> showOverlay = !showOverlay);
		// Pinselgröße
		cy = place(cy, 0);
		int bx = toolX;
		boolean hs = inside(mx, my, bx, cy, TOOL, TOOL);
		Redstone.stone(c, bx, cy, TOOL, TOOL, hs ? t.surfaceHover : t.surfaceHigh, t.border);
		String size = String.valueOf(editor.brushSize());
		c.text(size, bx + (TOOL - c.textWidth(size)) / 2 + 1, cy + 4, hs ? t.dustOn : t.text, false);
		hits.add(bx, cy, TOOL, TOOL, () -> {
			actions.click();
			editor.setBrushSize(editor.brushSize() % 3 + 1);
		});
		tip(c, "wardrobe.editor.size", bx, cy, hs);
		cy += TOOL + 2 + 3;
		cy = toolButton(c, hits, cy, "undo", false, mx, my, editor::undo);
		cy = toolButton(c, hits, cy, "redo", false, mx, my, editor::redo);
		cy += 3;
		// Armform
		cy = place(cy, 0);
		bx = toolX;
		boolean ha = inside(mx, my, bx, cy, TOOL, TOOL);
		Redstone.stone(c, bx, cy, TOOL, TOOL, ha ? t.surfaceHover : t.surfaceHigh, t.border);
		String arm = editor.slim() ? "S" : "C";
		c.text(arm, bx + (TOOL - c.textWidth(arm)) / 2 + 1, cy + 4, ha ? t.dustOn : t.text, false);
		hits.add(bx, cy, TOOL, TOOL, () -> {
			actions.click();
			editor.setSlim(!editor.slim());
		});
		tip(c, editor.slim() ? "wardrobe.slim" : "wardrobe.classic", bx, cy, ha);
	}

	private int toolButton(Canvas c, Hits hits, int y, String icon, boolean active, int mx, int my, final Runnable run) {
		y = place(y, 0);
		int x = toolX;
		boolean hov = inside(mx, my, x, y, TOOL, TOOL);
		Paint.iconButton(c, x, y, TOOL, icon, hov, active);
		hits.add(x, y, TOOL, TOOL, () -> {
			actions.click();
			run.run();
		});
		tip(c, "wardrobe.editor.tool." + icon, x, y, hov);
		return y + TOOL + 2;
	}

	/** Kleiner Hinweis rechts neben einem Werkzeug. */
	private void tip(Canvas c, String key, int x, int y, boolean hover) {
		if (!hover) return;
		pendingTip = I18n.tr(key);
		pendingTipX = x + TOOL + 4;
		pendingTipY = y + 4;
	}

	private String pendingTip;
	private int pendingTipX;
	private int pendingTipY;

	/** Hinweise zuletzt zeichnen (liegen über der 3D-Ansicht). */
	void drawOverlay(Canvas c) {
		if (pendingTip == null) return;
		Theme t = Theme.get();
		int w = c.textWidth(pendingTip) + 6;
		c.flush();
		c.push();
		c.raise(60f);
		c.fill(pendingTipX, pendingTipY - 2, pendingTipX + w, pendingTipY + 10, 0xE0101010);
		c.text(pendingTip, pendingTipX + 3, pendingTipY, t.text, false);
		c.pop();
		pendingTip = null;
	}

	private void uv(Canvas c, Hits hits, int x, int y, int s, int mx, int my) {
		Theme t = Theme.get();
		int size = 64 * s;
		view2d[0] = x;
		view2d[1] = y;
		view2d[2] = size;
		view2d[3] = size;
		Redstone.well(c, x - 2, y - 2, size + 4, size + 4, t.border);
		// Schachbrett für Durchsichtiges
		int cell = 4 * s;
		for (int cy = 0; cy < 16; cy++) {
			for (int cx = 0; cx < 16; cx++) {
				int col = ((cx + cy) & 1) == 0 ? 0xFF2A2A2A : 0xFF3A3A3A;
				c.fill(x + cx * cell, y + cy * cell, x + (cx + 1) * cell, y + (cy + 1) * cell, col);
			}
		}
		TextureRef tex = textures.get("wardrobe/editor", editor.pixels(), 64, 64, editor.version());
		if (tex != null && c.images()) {
			Affine.image(c, tex, x, y, size, size, 0, 0, 64, 64, 0xFFFFFFFF);
		} else {
			int[] px = editor.pixels();
			for (int i = 0; i < 4096; i++) {
				if ((px[i] >>> 24) == 0) continue;
				int ix = x + (i % 64) * s;
				int iy = y + (i / 64) * s;
				c.fill(ix, iy, ix + s, iy + s, px[i]);
			}
		}
		// Andere Ebene und ungenutzte Flächen abdunkeln
		SkinLayout l = editor.layout();
		int other = editor.layer() == SkinLayout.OVERLAY ? SkinLayout.BASE : SkinLayout.OVERLAY;
		for (int part = 0; part < SkinLayout.PARTS; part++) {
			for (int face = 0; face < 6; face++) {
				int[] r = SkinLayout.faceRect(SkinLayout.box(part, other, editor.slim()), face);
				c.fill(x + r[0] * s, y + r[1] * s, x + (r[0] + r[2]) * s, y + (r[1] + r[3]) * s, 0x90000000);
			}
		}
		// Hover-Texel
		int texel = inside(mx, my, view2d) ? (int) ((my - y) / s) * 64 + (int) ((mx - x) / s) : hoverTexel;
		if (inside(mx, my, view2d)) hoverTexel = texel;
		if (texel >= 0 && texel < 4096) {
			int hx = x + (texel % 64) * s;
			int hy = y + (texel / 64) * s;
			Paint.outline(c, hx - 1, hy - 1, s + 2, s + 2, 0xFFFFFFFF);
			if (editor.mirror()) {
				int m = l.mirror(texel);
				if (m >= 0) Paint.outline(c, x + (m % 64) * s - 1, y + (m / 64) * s - 1, s + 2, s + 2, ColorMath.withAlpha(t.dustOn, 200));
			}
		}
	}

	private void colors(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		int cols = Math.max(6, Math.min(12, (w + 2) / 12));
		int cell = Math.max(8, Math.min(12, (w + 2) / cols - 2));
		int step = cell + 2;
		int cy = y;
		for (int i = 0; i < PALETTE.length; i++) {
			int px = x + (i % cols) * step;
			int py = cy + (i / cols) * step;
			swatch(c, hits, px, py, cell, PALETTE[i], mx, my);
		}
		cy += ((PALETTE.length + cols - 1) / cols) * step + 2;
		List<Integer> recent = editor.recentColors();
		for (int i = 0; i < recent.size() && i < cols; i++) swatch(c, hits, x + i * step, cy, cell, recent.get(i), mx, my);
		if (recent.isEmpty()) Paint.textClipped(c, I18n.tr("wardrobe.editor.recent"), x, cy + (cell - 8) / 2, w, t.textDim, false);
		cy += step + 2;
		// Aktuelle Farbe + Hex
		int box = 14;
		Redstone.block(c, x, cy, box, box, t.bevelDark);
		c.fill(x + 1, cy + 1, x + box - 1, cy + box - 1, editor.color());
		int fx = x + box + 4;
		int fw = Math.min(w - box - 4, 64);
		if (!hex.focused()) {
			Integer shown = SkinEditor.parseHex(hex.text());
			if (shown == null || (shown | 0xFF000000) != (editor.color() | 0xFF000000)) hex.setText(SkinEditor.hex(editor.color()));
		}
		field(c, hex, fx, cy, fw, box, "#RRGGBB", mx, my);
		hits.add(fx, cy, fw, box, () -> {
			hex.setFocused(true);
			name.setFocused(false);
		});
	}

	private void swatch(Canvas c, Hits hits, int x, int y, int size, final int argb, int mx, int my) {
		Theme t = Theme.get();
		boolean hov = inside(mx, my, x, y, size, size);
		boolean sel = (editor.color() | 0xFF000000) == (argb | 0xFF000000);
		Redstone.block(c, x - 1, y - 1, size + 2, size + 2, sel ? t.dustOn : (hov ? t.textDim : t.bevelDark));
		c.fill(x, y, x + size, y + size, argb | 0xFF000000);
		hits.add(x, y, size, size, () -> {
			editor.setColor(argb);
			hex.setText(SkinEditor.hex(argb));
			if (editor.tool() == SkinEditor.Tool.ERASER || editor.tool() == SkinEditor.Tool.PICKER) editor.setTool(SkinEditor.Tool.BRUSH);
		});
	}

	private static void field(Canvas c, TextInput in, int x, int y, int w, int h, String placeholder, int mx, int my) {
		Theme t = Theme.get();
		Redstone.well(c, x, y, w, h, in.focused() ? t.accent : t.border);
		int ty = y + (h - 8) / 2;
		String text = in.text();
		if (text.isEmpty() && !in.focused()) {
			Paint.textClipped(c, placeholder, x + 4, ty, w - 8, t.textDim, false);
			return;
		}
		String shown = text;
		while (c.textWidth(shown) > w - 10 && shown.length() > 0) shown = shown.substring(1);
		c.text(shown, x + 4, ty, t.text, false);
		if (in.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = x + 4 + c.textWidth(shown.substring(0, Math.max(0, Math.min(shown.length(), shown.length() - (text.length() - in.cursor())))));
			c.fill(cx, ty - 1, cx + 1, ty + 9, t.text);
		}
	}

	// ============================================================================================
	// Eingabe
	// ============================================================================================

	/** @return true, wenn der Editor den Klick verbraucht hat (Malen/Drehen). */
	boolean mouseClicked(double mx, double my, int button) {
		if (templatesOpen) return false;
		if (inside(mx, my, view2d) && button == 0) {
			name.setFocused(false);
			hex.setFocused(false);
			int tx = (int) ((mx - view2d[0]) / uvScale);
			int ty = (int) ((my - view2d[1]) / uvScale);
			editor.beginStroke();
			editor.apply(tx, ty);
			afterPick();
			lastTexelX = tx;
			lastTexelY = ty;
			mode = 2;
			return true;
		}
		if (inside(mx, my, view3d)) {
			name.setFocused(false);
			hex.setFocused(false);
			lastX = mx;
			lastY = my;
			if (button == 0) {
				int texel = model.pick(figCx, figFeet, figScale, spec, (float) mx, (float) my, editor.layer());
				if (texel >= 0) {
					editor.beginStroke();
					editor.apply(texel % 64, texel / 64);
					afterPick();
					mode = 1;
					return true;
				}
			}
			mode = 3;
			return true;
		}
		return false;
	}

	boolean mouseDragged(double mx, double my, int button) {
		if (mode == 2) {
			int tx = (int) Math.floor((mx - view2d[0]) / uvScale);
			int ty = (int) Math.floor((my - view2d[1]) / uvScale);
			if (tx != lastTexelX || ty != lastTexelY) {
				if (editor.tool() == SkinEditor.Tool.BRUSH || editor.tool() == SkinEditor.Tool.ERASER) editor.line(lastTexelX, lastTexelY, tx, ty);
				lastTexelX = tx;
				lastTexelY = ty;
			}
			return true;
		}
		if (mode == 1) {
			if (editor.tool() == SkinEditor.Tool.BRUSH || editor.tool() == SkinEditor.Tool.ERASER) {
				double dx = mx - lastX;
				double dy = my - lastY;
				int steps = (int) Math.max(1, Math.ceil(Math.max(Math.abs(dx), Math.abs(dy))));
				for (int i = 1; i <= steps && i <= 200; i++) {
					double px = lastX + dx * i / steps;
					double py = lastY + dy * i / steps;
					int texel = model.pick(figCx, figFeet, figScale, spec, (float) px, (float) py, editor.layer());
					if (texel >= 0) editor.apply(texel % 64, texel / 64);
				}
			}
			lastX = mx;
			lastY = my;
			return true;
		}
		if (mode == 3) {
			yaw += (float) (mx - lastX) * 1.6f;
			pitch = Math.max(-60f, Math.min(60f, pitch + (float) (my - lastY) * 1.2f));
			lastX = mx;
			lastY = my;
			return true;
		}
		return false;
	}

	boolean mouseReleased() {
		boolean was = mode != 0;
		if (mode == 1 || mode == 2) editor.endStroke();
		mode = 0;
		return was;
	}

	boolean mouseScrolled(double mx, double my, double amount) {
		if (inside(mx, my, view3d)) {
			yaw += (float) amount * 15f;
			return true;
		}
		return false;
	}

	private void afterPick() {
		if (editor.tool() == SkinEditor.Tool.BRUSH) hex.setText(SkinEditor.hex(editor.color()));
	}

	boolean keyPressed(UiKey key) {
		if (key == UiKey.ESCAPE) {
			if (templatesOpen) {
				templatesOpen = false;
				return true;
			}
			if (textFocused()) {
				name.setFocused(false);
				hex.setFocused(false);
				return true;
			}
			tryClose();
			return true;
		}
		if (hex.focused()) {
			if (key == UiKey.ENTER) {
				commitHex();
				hex.setFocused(false);
				return true;
			}
			return hex.key(key);
		}
		if (name.focused()) {
			if (key == UiKey.ENTER || key == UiKey.TAB) {
				name.setFocused(false);
				return true;
			}
			return name.key(key);
		}
		return false;
	}

	boolean charTyped(char ch) {
		if (hex.focused()) {
			boolean ok = hex.type(ch);
			if (hex.text().length() == 7 || hex.text().length() == 9) commitHex();
			return ok;
		}
		if (name.focused()) return name.type(ch);
		switch (Character.toLowerCase(ch)) {
			case 'b':
				editor.setTool(SkinEditor.Tool.BRUSH);
				return true;
			case 'e':
				editor.setTool(SkinEditor.Tool.ERASER);
				return true;
			case 'i':
				editor.setTool(SkinEditor.Tool.PICKER);
				return true;
			case 'g':
				editor.setTool(SkinEditor.Tool.FILL);
				return true;
			case 'm':
				editor.setMirror(!editor.mirror());
				return true;
			case 'z':
				editor.undo();
				return true;
			case 'y':
				editor.redo();
				return true;
			default:
				return false;
		}
	}

	private void commitHex() {
		Integer v = SkinEditor.parseHex(hex.text());
		if (v != null) editor.setColor(v);
	}

	private void tryClose() {
		if (editor.dirty() && !discardArmed) {
			discardArmed = true;
			discardUntil = System.currentTimeMillis() + 4000;
			actions.toast("wardrobe.editor.discardHint");
			return;
		}
		actions.close();
	}

	private static boolean inside(double mx, double my, int[] r) {
		return mx >= r[0] && my >= r[1] && mx < r[0] + r[2] && my < r[1] + r[3];
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}
}
