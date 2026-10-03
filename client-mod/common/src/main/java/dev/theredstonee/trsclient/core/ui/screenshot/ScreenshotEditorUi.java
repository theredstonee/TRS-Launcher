package dev.theredstonee.trsclient.core.ui.screenshot;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.panorama.PanoramaPng;
import dev.theredstonee.trsclient.core.screenshot.ImageClipboard;
import dev.theredstonee.trsclient.core.screenshot.ScreenshotNames;
import dev.theredstonee.trsclient.core.screenshot.Screenshots;
import dev.theredstonee.trsclient.core.screenshot.edit.EditHistory;
import dev.theredstonee.trsclient.core.screenshot.edit.EditRender;
import dev.theredstonee.trsclient.core.screenshot.edit.EditState;
import dev.theredstonee.trsclient.core.screenshot.edit.Shape;
import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bild-Editor für Bildschirmfotos (ganzer Bildschirm, TRS-Stil): Kopfzeile mit „Zurück“, Dateiname, Speichern und
 * „Teilen“ (An Freunde senden, Bild kopieren, Link kopieren); Werkzeuge Zuschneiden (Griffe an Ecken/Kanten), Drehen
 * um 90°, Pfeil, Rahmen, Text, Verpixeln (Streamer-Schutz) und Stift; Farben, Strichstärke, Rückgängig/Wiederholen
 * (auch Tasten Z/Y). Gespeichert wird immer eine Kopie „Kopie von &lt;name&gt;.png“ neben dem Original.
 *
 * <p>Laden, Vorschau-Rastern und Speichern laufen im Hintergrund-Thread des {@link Screenshots}-Dienstes; im
 * Render-Thread wird nur die fertige Vorschau hochgeladen (höchstens 1600×900).
 */
public final class ScreenshotEditorUi extends UiScreen {
	enum Tool {
		CROP("crop", "screenshots.tool.crop"),
		ARROW("arrow", "screenshots.tool.arrow"),
		RECT("rect", "screenshots.tool.rect"),
		TEXT("text", "screenshots.tool.text"),
		PIXELATE("pixelate", "screenshots.tool.pixelate"),
		PEN("pencil", "screenshots.tool.pen");

		final String icon;
		final String label;

		Tool(String icon, String label) {
			this.icon = icon;
			this.label = label;
		}
	}

	/** Voreingestellte Farben: Redstone-Rot, Orange, Gelb, Grün, Blau, Lila, Weiß, Schwarz. */
	static final int[] COLORS = {0xFFE0312B, 0xFFFF9A1F, 0xFFFFE03A, 0xFF3FBF4A, 0xFF2F8CF0, 0xFF9B4DE0, 0xFFFFFFFF, 0xFF111111};
	static final float[] STROKE = {1f, 2f, 3.5f};
	static final float[] TEXT = {0.75f, 1f, 1.6f};
	static final float[] BLOCK = {0.6f, 1f, 1.7f};
	static final int PREVIEW_MAX_W = 1600;
	static final int PREVIEW_MAX_H = 900;
	static final int HEADER_H = 26;
	static final int TOOLS_H = 24;
	static final int HANDLE = 6;
	private static int textureCounter;

	private final Screenshots owner;
	private final Path file;
	private final String fileName;

	// Laden (Hintergrund → Render-Thread)
	private volatile int[] base;
	private volatile int baseW;
	private volatile int baseH;
	private volatile String loadError;
	private EditHistory history;

	// Vorschau
	private final Object previewLock = new Object();
	private int previewRotation = -1;
	private int[] previewBase;
	private int previewW;
	private int previewH;
	private volatile Rendered pending;
	private boolean rendering;
	private int requested = -1;
	private TextureRef texture;
	private EditState textureState;
	private int textureRevision = -1;

	private static final class Rendered {
		final int[] pixels;
		final int w, h;
		final int revision;
		final EditState state;

		Rendered(int[] pixels, int w, int h, int revision, EditState state) {
			this.pixels = pixels;
			this.w = w;
			this.h = h;
			this.revision = revision;
			this.state = state;
		}
	}

	// Werkzeuge
	private Tool tool = Tool.ARROW;
	private int color = COLORS[0];
	private int level = 1;

	// Bedienung
	private boolean drawing;
	private float startX, startY, curX, curY;
	private float[] penPoints = new float[256];
	private int penCount;
	/** Zuschnitt ziehen: 0 = nicht, 1–8 = Griff (Ecken/Kanten), 9 = verschieben. */
	private int cropHandle;
	private int[] cropAtStart;
	private float cropMouseX, cropMouseY;
	private TextInput textInput;
	private float textX, textY;
	/** Gerade übernommene Form, bis sie in der Vorschau ist (kein Flackern). */
	private Shape committed;
	private int committedRevision;

	private boolean shareMenu;
	private boolean confirmExit;
	private String notice;
	private boolean noticeError;
	private long noticeAt;
	private volatile boolean saving;
	private Path savedCopy;
	private int savedRevision;
	private String tooltip;

	// Ansicht (jedes Bild neu)
	private int areaX, areaY, areaW, areaH;
	private float viewX, viewY, scale;
	private int srcX, srcY, srcW, srcH;

	public ScreenshotEditorUi(Screenshots owner, Path file) {
		this.owner = owner;
		this.file = file;
		this.fileName = file.getFileName().toString();
		owner.editorFrame();
		load();
	}

	private void load() {
		owner.background(new Runnable() {
			@Override
			public void run() {
				try {
					if (Files.size(file) > 96L * 1024 * 1024) throw new IllegalStateException("zu groß");
					PngDecoder.Image img = PngDecoder.decode(Files.readAllBytes(file), ImageClipboard.MAX_PIXELS);
					baseW = img.width;
					baseH = img.height;
					base = img.argb;
				} catch (Throwable t) {
					loadError = I18n.tr("screenshots.editor.loadFailed");
				}
			}
		});
	}

	// --- Hilfen --------------------------------------------------------------------------------------

	private EditState state() {
		return history == null ? null : history.current();
	}

	private boolean dirty() {
		return history != null && !history.current().pristine() && (savedCopy == null || savedRevision != history.revision());
	}

	private void setNotice(String text, boolean error) {
		notice = text;
		noticeError = error;
		noticeAt = System.currentTimeMillis();
	}

	private Screenshots.Feedback feedback() {
		return new Screenshots.Feedback() {
			@Override
			public void done(String text, boolean error) {
				setNotice(text, error);
			}
		};
	}

	private float maxSide() {
		EditState s = state();
		return s == null ? 1000 : Math.max(s.width(), s.height());
	}

	float strokeSize() {
		return Math.max(2f, maxSide() / 360f) * STROKE[level];
	}

	float textSize() {
		return Math.max(12f, maxSide() / 48f) * TEXT[level];
	}

	float blockSize() {
		return Math.max(4f, maxSide() / 90f) * BLOCK[level];
	}

	private float imgX(double sx) {
		return srcX + (float) (sx - viewX) / scale;
	}

	private float imgY(double sy) {
		return srcY + (float) (sy - viewY) / scale;
	}

	private float scrX(float ix) {
		return viewX + (ix - srcX) * scale;
	}

	private float scrY(float iy) {
		return viewY + (iy - srcY) * scale;
	}

	private boolean inImage(double mx, double my) {
		return scale > 0 && mx >= viewX && my >= viewY && mx < viewX + srcW * scale && my < viewY + srcH * scale;
	}

	// --- Vorschau ------------------------------------------------------------------------------------

	private void requestPreview() {
		if (history == null || rendering || requested == history.revision()) return;
		final EditState s = history.current();
		final int rev = history.revision();
		final int[] src = base;
		rendering = true;
		requested = rev;
		owner.background(new Runnable() {
			@Override
			public void run() {
				try {
					int[] px;
					int w, h;
					float k;
					synchronized (previewLock) {
						if (previewRotation != s.rotation || previewBase == null) {
							int[] rotated = EditRender.rotate(src, s.baseW, s.baseH, s.rotation);
							int rw = s.width(), rh = s.height();
							float f = Math.min(1f, Math.min(PREVIEW_MAX_W / (float) rw, PREVIEW_MAX_H / (float) rh));
							previewW = Math.max(1, Math.round(rw * f));
							previewH = Math.max(1, Math.round(rh * f));
							previewBase = EditRender.downscale(rotated, rw, rh, previewW, previewH);
							previewRotation = s.rotation;
						}
						px = previewBase.clone();
						w = previewW;
						h = previewH;
						k = w / (float) s.width();
					}
					EditRender.draw(px, w, h, s.shapes, k);
					for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
					pending = new Rendered(px, w, h, rev, s);
				} catch (Throwable t) {
					pending = new Rendered(null, 0, 0, rev, s);
				}
			}
		});
	}

	private void uploadPreview() {
		Rendered r = pending;
		if (r == null) return;
		pending = null;
		rendering = false;
		Textures.Store store = Textures.store();
		if (r.pixels == null || store == null) {
			if (r.pixels == null) setNotice(I18n.tr("screenshots.editor.renderFailed"), true);
			return;
		}
		if (texture != null && (texture.width != r.w || texture.height != r.h)) {
			store.release(texture);
			texture = null;
		}
		if (texture == null) textureName = "trs_shot_editor/" + (textureCounter++);
		TextureRef ref = store.upload(textureName, r.w, r.h, r.pixels);
		if (ref != null) {
			texture = ref;
			textureState = r.state;
			textureRevision = r.revision;
		}
	}

	private String textureName;

	// --- Zeichnen ------------------------------------------------------------------------------------

	@Override
	protected void draw(Canvas c, int width, int height, int mx, int my, float dt) {
		owner.editorFrame();
		Theme t = Theme.get();
		tooltip = null;
		if (history == null && base != null) history = new EditHistory(EditState.initial(baseW, baseH));
		uploadPreview();
		requestPreview();
		c.fill(0, 0, width, height, 0xF00C0A0B);
		c.push();
		c.raise(300f);
		areaX = 8;
		areaY = HEADER_H + TOOLS_H + 6;
		areaW = width - 16;
		areaH = height - areaY - 8;
		drawImage(c, t, mx, my);
		header(c, t, width, mx, my);
		toolbar(c, t, width, mx, my);
		if (notice != null && System.currentTimeMillis() - noticeAt < 5_000L) {
			int nw = Math.min(width - 20, c.textWidth(notice) + 14);
			int nx = (width - nw) / 2;
			int ny = height - 22;
			c.flush();
			c.push();
			c.raise(30f);
			Redstone.stone(c, nx, ny, nw, 15, ColorMath.withAlpha(t.surface, 235), noticeError ? t.dustOn : t.accent);
			Paint.textClipped(c, notice, nx + 7, ny + 4, nw - 14, noticeError ? t.dustOn : t.text, false);
			c.flush();
			c.pop();
		}
		if (shareMenu) shareMenu(c, t, width, mx, my);
		if (confirmExit) confirm(c, t, width, height, mx, my);
		c.flush();
		if (tooltip != null) {
			int tw = c.textWidth(tooltip) + 8;
			int tx = Math.max(2, Math.min(mx - tw / 2, width - tw - 2));
			int ty = HEADER_H + TOOLS_H + 2;
			c.push();
			c.raise(80f);
			Redstone.stone(c, tx, ty, tw, 13, t.surfaceHigh, t.border);
			c.text(tooltip, tx + 4, ty + 3, t.text, false);
			c.flush();
			c.pop();
		}
		c.pop();
	}

	private void header(Canvas c, Theme t, int width, int mx, int my) {
		c.fill(0, 0, width, HEADER_H, t.surfaceHigh);
		c.fill(0, HEADER_H - 1, width, HEADER_H, t.border);
		// Zurück
		String back = I18n.tr("screenshots.editor.back");
		int bw = c.textWidth(back) + 22;
		boolean hb = inside(mx, my, 5, 5, bw, 16);
		Redstone.button(c, 5, 5, bw, 16, "", false, hb);
		Icons.draw(c, "back", 9, 9 - (hb ? 1 : 0), 1, t.text);
		c.text(back, 19, 9 - (hb ? 1 : 0), t.text, false);
		hits.add(5, 5, bw, 16, new Runnable() {
			@Override
			public void run() {
				owner.platform().playClick();
				back();
			}
		});
		// rechts: Teilen ▾, Speichern
		int right = width - 5;
		String share = I18n.tr("screenshots.editor.share");
		int sw = c.textWidth(share) + 26;
		int sx = right - sw;
		boolean hs = inside(mx, my, sx, 5, sw, 16);
		Redstone.button(c, sx, 5, sw, 16, "", false, hs || shareMenu);
		Icons.draw(c, "send", sx + 4, 9 - (hs ? 1 : 0), 1, t.accent);
		c.text(share, sx + 14, 9 - (hs ? 1 : 0), t.text, false);
		Icons.draw(c, "chevronDown", sx + sw - 11, 9, 1, t.textDim);
		hits.add(sx, 5, sw, 16, new Runnable() {
			@Override
			public void run() {
				owner.platform().playClick();
				shareMenu = !shareMenu;
			}
		});
		String saveLabel = I18n.tr(saving ? "screenshots.editor.saving" : "screenshots.editor.save");
		int vw = c.textWidth(saveLabel) + 24;
		int vx = sx - 5 - vw;
		boolean canSave = history != null && !saving && dirty();
		boolean hv = canSave && inside(mx, my, vx, 5, vw, 16);
		if (canSave) {
			Redstone.button(c, vx, 5, vw, 16, "", true, hv);
		} else {
			Redstone.stone(c, vx, 5, vw, 16, t.surface, t.border);
		}
		Icons.draw(c, "save", vx + 5, 9 - (hv ? 1 : 0), 1, canSave ? Redstone.lampTextColor(1f) : t.textDim);
		c.text(saveLabel, vx + 16, 9 - (hv ? 1 : 0), canSave ? Redstone.lampTextColor(1f) : t.textDim, false);
		if (canSave) {
			hits.add(vx, 5, vw, 16, new Runnable() {
				@Override
				public void run() {
					owner.platform().playClick();
					save(null);
				}
			});
		}
		if (hv) tooltip = I18n.tr("screenshots.editor.saveHint");
		// Mitte: TRS + Dateiname
		int lx = 5 + bw + 8;
		List<int[]> rects = PixelFont.rects("TRS");
		int ly = (HEADER_H - PixelFont.HEIGHT) / 2;
		for (int[] r : rects) c.fill(lx + r[0], ly + r[1], lx + r[2], ly + r[3], t.accent);
		lx += PixelFont.width("TRS") + 6;
		String title = fileName + (dirty() ? " *" : "");
		Paint.textClipped(c, title, lx, 9, vx - 8 - lx, t.text, false);
	}

	private void toolbar(Canvas c, Theme t, int width, int mx, int my) {
		int y = HEADER_H + 3;
		c.fill(0, HEADER_H, width, HEADER_H + TOOLS_H, ColorMath.withAlpha(t.surface, 230));
		int size = 18;
		int x = 6;
		for (final Tool tl : Tool.values()) {
			boolean hov = inside(mx, my, x, y, size, size);
			Redstone.iconButton(c, x, y, size, tl.icon, hov, tool == tl);
			if (hov) tooltip = I18n.tr(tl.label);
			hits.add(x, y, size, size, new Runnable() {
				@Override
				public void run() {
					owner.platform().playClick();
					selectTool(tl);
				}
			});
			x += size + 2;
			if (tl == Tool.CROP) {
				// Drehen gleich neben dem Zuschneiden
				boolean hr = inside(mx, my, x, y, size, size);
				Redstone.iconButton(c, x, y, size, "rotate", hr, false);
				if (hr) tooltip = I18n.tr("screenshots.tool.rotate");
				hits.add(x, y, size, size, new Runnable() {
					@Override
					public void run() {
						owner.platform().playClick();
						rotate();
					}
				});
				x += size + 2;
			}
		}
		x += 6;
		c.fill(x - 4, y + 2, x - 3, y + size - 2, t.border);
		// Farben (ab 360 px alle, darunter die ersten sechs)
		int colors = width >= 360 ? COLORS.length : 6;
		for (int i = 0; i < colors; i++) {
			final int col = COLORS[i];
			int sw = 12;
			int sy = y + 3;
			boolean sel = color == col;
			boolean hov = inside(mx, my, x, sy, sw, sw);
			if (sel || hov) c.fill(x - 2, sy - 2, x + sw + 2, sy + sw + 2, sel ? t.accent : t.border);
			c.fill(x, sy, x + sw, sy + sw, 0xFF000000);
			c.fill(x + 1, sy + 1, x + sw - 1, sy + sw - 1, col);
			hits.add(x - 1, sy - 1, sw + 2, sw + 2, new Runnable() {
				@Override
				public void run() {
					owner.platform().playClick();
					color = col;
					if (tool == Tool.CROP || tool == Tool.PIXELATE) tool = Tool.ARROW;
				}
			});
			x += sw + 4;
		}
		x += 6;
		c.fill(x - 4, y + 2, x - 3, y + size - 2, t.border);
		// Strichstärke / Schriftgröße
		for (int i = 0; i < 3; i++) {
			final int lv = i;
			boolean hov = inside(mx, my, x, y, size, size);
			Redstone.button(c, x, y, size, size, "", level == lv, hov);
			int d = 2 + i * 2;
			int cx = x + size / 2, cy = y + size / 2 - (hov ? 1 : 0);
			c.fill(cx - d / 2, cy - d / 2, cx - d / 2 + d, cy - d / 2 + d, level == lv ? Redstone.lampTextColor(1f) : t.text);
			if (hov) tooltip = i == 0 ? I18n.tr("screenshots.tool.size1") : i == 1 ? I18n.tr("screenshots.tool.size2") : I18n.tr("screenshots.tool.size3");
			hits.add(x, y, size, size, new Runnable() {
				@Override
				public void run() {
					owner.platform().playClick();
					level = lv;
				}
			});
			x += size + 2;
		}
		x += 6;
		c.fill(x - 4, y + 2, x - 3, y + size - 2, t.border);
		// Rückgängig / Wiederholen
		final boolean canUndo = history != null && history.canUndo();
		final boolean canRedo = history != null && history.canRedo();
		smallIcon(c, t, x, y, size, "undo", canUndo, mx, my, "screenshots.tool.undo", new Runnable() {
			@Override
			public void run() {
				undo();
			}
		});
		x += size + 2;
		smallIcon(c, t, x, y, size, "redo", canRedo, mx, my, "screenshots.tool.redo", new Runnable() {
			@Override
			public void run() {
				redo();
			}
		});
	}

	private void smallIcon(Canvas c, Theme t, int x, int y, int size, String icon, boolean enabled, int mx, int my, String tip,
			final Runnable action) {
		boolean hov = enabled && inside(mx, my, x, y, size, size);
		if (enabled) {
			Redstone.iconButton(c, x, y, size, icon, hov, false);
			hits.add(x, y, size, size, new Runnable() {
				@Override
				public void run() {
					owner.platform().playClick();
					action.run();
				}
			});
		} else {
			Redstone.stone(c, x, y, size, size, t.surface, t.border);
			Icons.draw(c, icon, x + (size - 8) / 2, y + (size - 8) / 2, 1, t.textDim);
		}
		if (inside(mx, my, x, y, size, size)) tooltip = I18n.tr(tip);
	}

	private void drawImage(Canvas c, Theme t, int mx, int my) {
		EditState s = state();
		if (s == null) {
			String text = loadError != null ? loadError : I18n.tr("clips.loading");
			Paint.textCentered(c, text, areaX + areaW / 2, areaY + areaH / 2 - 4, loadError != null ? t.dustOn : t.textDim, false);
			return;
		}
		if (!c.images()) {
			Paint.textCentered(c, I18n.tr("screenshots.unsupported"), areaX + areaW / 2, areaY + areaH / 2 - 4, t.textDim, false);
			return;
		}
		boolean full = tool == Tool.CROP;
		srcX = full ? 0 : s.cropX;
		srcY = full ? 0 : s.cropY;
		srcW = full ? s.width() : s.cropW;
		srcH = full ? s.height() : s.cropH;
		scale = Math.min(areaW / (float) srcW, areaH / (float) srcH);
		float dw = srcW * scale, dh = srcH * scale;
		viewX = areaX + (areaW - dw) / 2f;
		viewY = areaY + (areaH - dh) / 2f;
		Redstone.well(c, Math.round(viewX) - 3, Math.round(viewY) - 3, Math.round(dw) + 6, Math.round(dh) + 6, t.border);
		EditState ts = textureState;
		if (texture != null && ts != null && ts.rotation == s.rotation) {
			float k = texture.width / (float) ts.width();
			Affine.image(c, texture, viewX, viewY, dw, dh, srcX * k, srcY * k, Math.max(1, Math.round(srcW * k)),
					Math.max(1, Math.round(srcH * k)), 0xFFFFFFFF);
		} else {
			c.fill(Math.round(viewX), Math.round(viewY), Math.round(viewX + dw), Math.round(viewY + dh), 0xFF151213);
			Paint.textCentered(c, I18n.tr("clips.loading"), Math.round(viewX + dw / 2), Math.round(viewY + dh / 2) - 4, t.textDim, false);
		}
		c.flush();
		c.scissor(Math.round(viewX), Math.round(viewY), Math.round(viewX + dw), Math.round(viewY + dh));
		// Gerade übernommene Form, bis die Vorschau sie enthält.
		if (committed != null && textureRevision < committedRevision && history.current().shapes.contains(committed)) {
			preview(c, committed);
		}
		if (drawing) preview(c, current());
		if (textInput != null) textEntry(c, t);
		if (full) cropOverlay(c, t, s);
		c.noScissor();
		if (tool == Tool.CROP) {
			String hint = I18n.tr("screenshots.editor.cropHint", s.cropW, s.cropH);
			Paint.textCentered(c, hint, areaX + areaW / 2, Math.round(viewY + dh) + 1 > areaY + areaH - 9 ? areaY + areaH - 9
					: Math.round(viewY + dh) + 1, t.textDim, true);
		}
	}

	/** Form in Bildschirm-Koordinaten andeuten (während des Ziehens). */
	private void preview(Canvas c, Shape shape) {
		if (shape == null) return;
		float th = Math.max(1f, shape.size * scale);
		switch (shape.kind) {
			case ARROW: {
				Shape.Arrow a = (Shape.Arrow) shape;
				float x1 = scrX(a.x1), y1 = scrY(a.y1), x2 = scrX(a.x2), y2 = scrY(a.y2);
				line(c, x1, y1, x2, y2, th, shape.color);
				float dx = x2 - x1, dy = y2 - y1;
				float len = (float) Math.sqrt(dx * dx + dy * dy);
				if (len > 1) {
					float head = Math.min(len, Math.max(th * 4.2f, 10 * scale));
					float ux = dx / len, uy = dy / len;
					float bx = x2 - ux * head, by = y2 - uy * head;
					float half = head * 0.55f;
					line(c, x2, y2, bx - uy * half, by + ux * half, th, shape.color);
					line(c, x2, y2, bx + uy * half, by - ux * half, th, shape.color);
				}
				return;
			}
			case RECT: {
				Shape.Rect r = (Shape.Rect) shape;
				frame(c, scrX(r.x1), scrY(r.y1), scrX(r.x2), scrY(r.y2), th, shape.color);
				return;
			}
			case PIXELATE: {
				Shape.Pixelate p = (Shape.Pixelate) shape;
				int x1 = Math.round(scrX(p.x1)), y1 = Math.round(scrY(p.y1)), x2 = Math.round(scrX(p.x2)), y2 = Math.round(scrY(p.y2));
				int block = Math.max(2, Math.round(p.size * scale));
				for (int yy = y1; yy < y2; yy += block) {
					for (int xx = x1; xx < x2; xx += block) {
						boolean dark = ((xx - x1) / block + (yy - y1) / block) % 2 == 0;
						c.fill(xx, yy, Math.min(x2, xx + block), Math.min(y2, yy + block), dark ? 0xB0303030 : 0xB0606060);
					}
				}
				frame(c, x1, y1, x2, y2, 1, 0xFFFFFFFF);
				return;
			}
			case PEN: {
				Shape.Pen p = (Shape.Pen) shape;
				for (int i = 0; i < p.count(); i++) {
					float x = scrX(p.x(i)), y = scrY(p.y(i));
					if (i == 0) line(c, x, y, x, y, th, shape.color);
					else line(c, scrX(p.x(i - 1)), scrY(p.y(i - 1)), x, y, th, shape.color);
				}
				return;
			}
			case TEXT: {
				Shape.Text tx = (Shape.Text) shape;
				drawText(c, tx.text, scrX(tx.x), scrY(tx.y), tx.size * scale, shape.color);
				return;
			}
			default:
		}
	}

	private static void drawText(Canvas c, String text, float x, float y, float pixelHeight, int color) {
		float f = Math.max(0.5f, pixelHeight / 9f);
		c.push();
		c.translate(x, y);
		c.scale(f);
		c.text(text, 0, 0, color, true);
		c.flush();
		c.pop();
	}

	/** Dicke Linie aus kleinen Quadraten (geht in jeder Version, auch ohne Drehung). */
	static void line(Canvas c, float x1, float y1, float x2, float y2, float th, int color) {
		float dx = x2 - x1, dy = y2 - y1;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		int r = Math.max(1, Math.round(th / 2f));
		int steps = Math.min(800, Math.max(1, (int) Math.ceil(len / Math.max(1f, th / 2f))));
		for (int i = 0; i <= steps; i++) {
			int px = Math.round(x1 + dx * i / steps);
			int py = Math.round(y1 + dy * i / steps);
			c.fill(px - r, py - r, px - r + Math.max(1, r * 2), py - r + Math.max(1, r * 2), color);
		}
	}

	static void frame(Canvas c, float x1, float y1, float x2, float y2, float th, int color) {
		int t = Math.max(1, Math.round(th));
		int a = Math.round(Math.min(x1, x2)), b = Math.round(Math.min(y1, y2));
		int e = Math.round(Math.max(x1, x2)), f = Math.round(Math.max(y1, y2));
		int h = t / 2;
		c.fill(a - h, b - h, e + t - h, b - h + t, color);
		c.fill(a - h, f - h, e + t - h, f - h + t, color);
		c.fill(a - h, b - h, a - h + t, f + t - h, color);
		c.fill(e - h, b - h, e - h + t, f + t - h, color);
	}

	private void textEntry(Canvas c, Theme t) {
		float px = scrX(textX), py = scrY(textY);
		float hpx = textSize() * scale;
		String text = textInput.text();
		float f = Math.max(0.5f, hpx / 9f);
		int w = Math.round(Math.max(40, c.textWidth(text) * f + 6));
		int h = Math.round(Math.max(10, 9 * f + 4));
		c.fill(Math.round(px) - 3, Math.round(py) - 2, Math.round(px) - 3 + w, Math.round(py) - 2 + h, 0x60000000);
		frame(c, px - 3, py - 2, px - 3 + w, py - 2 + h, 1, t.accent);
		drawText(c, text.isEmpty() ? "" : text, px, py, hpx, color);
		if (text.isEmpty()) {
			c.push();
			c.translate(px, py);
			c.scale(f);
			c.text(I18n.tr("screenshots.editor.textHint"), 0, 0, 0x90FFFFFF, false);
			c.flush();
			c.pop();
		}
		if ((System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = Math.round(px + c.textWidth(text.substring(0, Math.min(text.length(), textInput.cursor()))) * f);
			c.fill(cx, Math.round(py), cx + 1, Math.round(py + 9 * f), 0xFFFFFFFF);
		}
	}

	private void cropOverlay(Canvas c, Theme t, EditState s) {
		int[] r = cropDisplay(s);
		int x1 = Math.round(scrX(r[0])), y1 = Math.round(scrY(r[1])), x2 = Math.round(scrX(r[0] + r[2])), y2 = Math.round(scrY(r[1] + r[3]));
		int vx1 = Math.round(viewX), vy1 = Math.round(viewY);
		int vx2 = Math.round(viewX + srcW * scale), vy2 = Math.round(viewY + srcH * scale);
		int dim = 0xA0000000;
		c.fill(vx1, vy1, vx2, y1, dim);
		c.fill(vx1, y2, vx2, vy2, dim);
		c.fill(vx1, y1, x1, y2, dim);
		c.fill(x2, y1, vx2, y2, dim);
		frame(c, x1, y1, x2, y2, 1, 0xFFFFFFFF);
		// Drittel-Linien
		for (int i = 1; i < 3; i++) {
			int gx = x1 + (x2 - x1) * i / 3, gy = y1 + (y2 - y1) * i / 3;
			c.fill(gx, y1, gx + 1, y2, 0x60FFFFFF);
			c.fill(x1, gy, x2, gy + 1, 0x60FFFFFF);
		}
		int[][] handles = handles(x1, y1, x2, y2);
		for (int[] hd : handles) {
			c.fill(hd[0] - HANDLE / 2 - 1, hd[1] - HANDLE / 2 - 1, hd[0] + HANDLE / 2 + 1, hd[1] + HANDLE / 2 + 1, 0xFF000000);
			c.fill(hd[0] - HANDLE / 2, hd[1] - HANDLE / 2, hd[0] + HANDLE / 2, hd[1] + HANDLE / 2, t.accent);
		}
	}

	/** Zuschnitt, der gerade angezeigt wird (während des Ziehens der gezogene, sonst der gespeicherte). */
	private int[] cropDisplay(EditState s) {
		if (cropHandle != 0 && dragCrop != null) return dragCrop;
		return new int[]{s.cropX, s.cropY, s.cropW, s.cropH};
	}

	private int[] dragCrop;

	/** Griffe: 1 oben links, 2 oben, 3 oben rechts, 4 rechts, 5 unten rechts, 6 unten, 7 unten links, 8 links. */
	private static int[][] handles(int x1, int y1, int x2, int y2) {
		int mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
		return new int[][]{{x1, y1}, {mx, y1}, {x2, y1}, {x2, my}, {x2, y2}, {mx, y2}, {x1, y2}, {x1, my}};
	}

	private void shareMenu(Canvas c, Theme t, int width, int mx, int my) {
		String[] labels = {I18n.tr("screenshots.action.send"), I18n.tr("screenshots.action.copy"), I18n.tr("screenshots.action.link")};
		String[] icons = {"send", "copy", "link"};
		final Screenshots.Action[] actions = {Screenshots.Action.SEND, Screenshots.Action.COPY, Screenshots.Action.LINK};
		int w = 0;
		for (String l : labels) w = Math.max(w, c.textWidth(l));
		w += 26;
		int x = width - 5 - w;
		int y = HEADER_H - 2;
		int h = labels.length * 16 + 4;
		c.push();
		c.raise(40f);
		Paint.shadow(c, x, y, w, h, 3, 0.4f);
		Redstone.stone(c, x, y, w, h, t.surfaceHigh, t.border);
		boolean online = owner.platform().online();
		for (int i = 0; i < labels.length; i++) {
			final Screenshots.Action a = actions[i];
			boolean enabled = online || a == Screenshots.Action.COPY;
			int ry = y + 2 + i * 16;
			boolean hov = enabled && inside(mx, my, x + 2, ry, w - 4, 16);
			if (hov) c.fill(x + 2, ry, x + w - 2, ry + 16, ColorMath.withAlpha(t.accent, 0x50));
			Icons.draw(c, icons[i], x + 6, ry + 4, 1, enabled ? t.accent : t.textDim);
			c.text(labels[i], x + 18, ry + 4, enabled ? t.text : t.textDim, false);
			if (!enabled && inside(mx, my, x + 2, ry, w - 4, 16)) tooltip = I18n.tr("screenshots.unsupported");
			hits.add(x + 2, ry, w - 4, 16, new Runnable() {
				@Override
				public void run() {
					owner.platform().playClick();
					shareMenu = false;
					if (a != Screenshots.Action.COPY && !owner.platform().online()) {
						setNotice(I18n.tr("screenshots.unsupported"), true);
						return;
					}
					share(a);
				}
			});
		}
		c.flush();
		c.pop();
	}

	private void confirm(Canvas c, Theme t, int width, int height, int mx, int my) {
		c.fill(0, 0, width, height, 0x90000000);
		int w = Math.min(width - 40, 300);
		int h = 78;
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		c.push();
		c.raise(60f);
		Redstone.window(c, x, y, w, h);
		Paint.textClipped(c, I18n.tr("screenshots.editor.unsavedTitle"), x + 10, y + 10, w - 20, t.text, false);
		Paint.textClipped(c, I18n.tr("screenshots.editor.unsavedHint"), x + 10, y + 23, w - 20, t.textDim, false);
		int bw = (w - 40) / 3;
		button(c, t, x + 10, y + h - 26, bw, 18, I18n.tr("common.cancel"), false, mx, my, new Runnable() {
			@Override
			public void run() {
				confirmExit = false;
			}
		});
		button(c, t, x + 20 + bw, y + h - 26, bw, 18, I18n.tr("screenshots.editor.discard"), false, mx, my, new Runnable() {
			@Override
			public void run() {
				confirmExit = false;
				requestClose();
			}
		});
		button(c, t, x + 30 + bw * 2, y + h - 26, bw, 18, I18n.tr("screenshots.editor.save"), true, mx, my, new Runnable() {
			@Override
			public void run() {
				confirmExit = false;
				save(new Runnable() {
					@Override
					public void run() {
						requestClose();
					}
				});
			}
		});
		c.flush();
		c.pop();
	}

	private void button(Canvas c, Theme t, int x, int y, int w, int h, String label, boolean primary, int mx, int my, final Runnable r) {
		Redstone.button(c, x, y, w, h, c.clip(label, w - 6), primary, inside(mx, my, x, y, w, h));
		hits.add(x, y, w, h, new Runnable() {
			@Override
			public void run() {
				owner.platform().playClick();
				r.run();
			}
		});
	}

	/** Touch: Zeichnen und Zuschneiden folgen dem Finger sofort. */
	@Override
	protected boolean touchDirect(double x, double y) {
		return true;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	// --- Aktionen ------------------------------------------------------------------------------------

	private void selectTool(Tool tl) {
		commitText();
		tool = tl;
	}

	private void rotate() {
		if (history == null) return;
		commitText();
		history.push(history.current().rotatedClockwise());
	}

	private void undo() {
		if (history == null) return;
		if (textInput != null) {
			textInput = null;
			return;
		}
		history.undo();
	}

	private void redo() {
		if (history != null) history.redo();
	}

	private void back() {
		commitText();
		if (dirty()) {
			confirmExit = true;
			return;
		}
		requestClose();
	}

	/** Kopie speichern (im Hintergrund); danach {@code after} im Spiel-Thread. */
	void save(final Runnable after) {
		if (history == null || saving) return;
		commitText();
		final EditState s = history.current();
		final int rev = history.revision();
		final int[] src = base;
		final Path target = savedCopy != null ? savedCopy : ScreenshotNames.copyFor(file, I18n.tr("screenshots.copyPrefix"));
		if (target == null) {
			setNotice(I18n.tr("screenshots.editor.saveFailed"), true);
			return;
		}
		saving = true;
		setNotice(I18n.tr("screenshots.editor.saving"), false);
		owner.background(new Runnable() {
			@Override
			public void run() {
				boolean ok;
				try {
					int[] out = EditRender.export(src, s);
					PanoramaPng.write(target, s.cropW, s.cropH, out);
					ok = true;
				} catch (Throwable t) {
					ok = false;
				}
				final boolean done = ok;
				owner.later(new Runnable() {
					@Override
					public void run() {
						saving = false;
						if (!done) {
							setNotice(I18n.tr("screenshots.editor.saveFailed"), true);
							return;
						}
						savedCopy = target;
						savedRevision = rev;
						setNotice(I18n.tr("screenshots.editor.saved", target.getFileName().toString()), false);
						if (after != null) after.run();
					}
				});
			}
		});
	}

	/** Teilen: unverändert = Original, sonst erst die Kopie speichern. */
	private void share(final Screenshots.Action a) {
		if (history == null) return;
		commitText();
		if (history.current().pristine()) {
			act(a, file);
			return;
		}
		if (!dirty() && savedCopy != null) {
			act(a, savedCopy);
			return;
		}
		save(new Runnable() {
			@Override
			public void run() {
				act(a, savedCopy);
			}
		});
	}

	private void act(Screenshots.Action a, Path target) {
		// „An Freunde senden“ ersetzt den Editor durch den Sozial-Bildschirm – vorher aufräumen.
		if (a == Screenshots.Action.SEND) releaseTexture();
		owner.run(a, target, feedback());
	}

	private void releaseTexture() {
		Textures.Store store = Textures.store();
		if (texture != null && store != null) store.release(texture);
		texture = null;
		textureState = null;
		textureRevision = -1;
		requested = -1;
	}

	// --- Eingaben ------------------------------------------------------------------------------------

	private Shape current() {
		int col = color;
		switch (tool) {
			case ARROW:
				return new Shape.Arrow(startX, startY, curX, curY, col, strokeSize());
			case RECT:
				return new Shape.Rect(startX, startY, curX, curY, col, strokeSize());
			case PIXELATE:
				return new Shape.Pixelate(startX, startY, curX, curY, blockSize());
			case PEN: {
				float[] p = new float[penCount * 2];
				System.arraycopy(penPoints, 0, p, 0, p.length);
				return new Shape.Pen(p, col, strokeSize());
			}
			default:
				return null;
		}
	}

	private float clampX(float x) {
		EditState s = state();
		return Math.max(0, Math.min(s.width(), x));
	}

	private float clampY(float y) {
		EditState s = state();
		return Math.max(0, Math.min(s.height(), y));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (hits.click(mouseX, mouseY, button)) return true;
		if (shareMenu) {
			shareMenu = false;
			return true;
		}
		if (confirmExit || button != 0 || history == null || scale <= 0) return true;
		EditState s = state();
		if (tool == Tool.CROP) {
			startCrop(mouseX, mouseY, s);
			return true;
		}
		if (!inImage(mouseX, mouseY)) {
			commitText();
			return true;
		}
		float ix = clampX(imgX(mouseX)), iy = clampY(imgY(mouseY));
		if (tool == Tool.TEXT) {
			commitText();
			textInput = new TextInput(Shape.Text.MAX_LENGTH);
			textInput.setFocused(true);
			textX = ix;
			textY = iy;
			return true;
		}
		commitText();
		drawing = true;
		startX = curX = ix;
		startY = curY = iy;
		penCount = 0;
		addPen(ix, iy);
		return true;
	}

	private void addPen(float x, float y) {
		if (penCount > 0) {
			float dx = x - penPoints[(penCount - 1) * 2], dy = y - penPoints[(penCount - 1) * 2 + 1];
			float min = Math.max(1f, 1.5f / Math.max(0.01f, scale));
			if (dx * dx + dy * dy < min * min) return;
		}
		if (penCount >= 4000) return;
		if (penCount * 2 + 2 > penPoints.length) {
			float[] n = new float[penPoints.length * 2];
			System.arraycopy(penPoints, 0, n, 0, penPoints.length);
			penPoints = n;
		}
		penPoints[penCount * 2] = x;
		penPoints[penCount * 2 + 1] = y;
		penCount++;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (hits.drag(mouseX, mouseY)) return true;
		if (history == null || scale <= 0) return false;
		if (cropHandle != 0) {
			dragCrop(mouseX, mouseY);
			return true;
		}
		if (!drawing) return false;
		curX = clampX(imgX(mouseX));
		curY = clampY(imgY(mouseY));
		if (tool == Tool.PEN) addPen(curX, curY);
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		super.mouseReleased(mouseX, mouseY, button);
		if (history == null) return false;
		if (cropHandle != 0) {
			int[] r = dragCrop;
			cropHandle = 0;
			dragCrop = null;
			if (r != null) {
				EditState s = state();
				if (r[0] != s.cropX || r[1] != s.cropY || r[2] != s.cropW || r[3] != s.cropH) {
					history.push(s.withCrop(r[0], r[1], r[2], r[3]));
				}
			}
			return true;
		}
		if (!drawing) return false;
		drawing = false;
		Shape shape = current();
		if (shape == null) return true;
		float[] b = shape.bounds();
		float minSize = 3f / Math.max(0.01f, scale);
		boolean big = shape.kind == Shape.Kind.PEN || Math.abs(b[2] - b[0]) >= minSize || Math.abs(b[3] - b[1]) >= minSize;
		if (!big) return true;
		history.push(history.current().withShape(shape));
		committed = shape;
		committedRevision = history.revision();
		return true;
	}

	private void startCrop(double mx, double my, EditState s) {
		int x1 = Math.round(scrX(s.cropX)), y1 = Math.round(scrY(s.cropY));
		int x2 = Math.round(scrX(s.cropX + s.cropW)), y2 = Math.round(scrY(s.cropY + s.cropH));
		int[][] hd = handles(x1, y1, x2, y2);
		cropHandle = 0;
		for (int i = 0; i < hd.length; i++) {
			if (Math.abs(mx - hd[i][0]) <= HANDLE && Math.abs(my - hd[i][1]) <= HANDLE) {
				cropHandle = i + 1;
				break;
			}
		}
		if (cropHandle == 0 && mx >= x1 && my >= y1 && mx < x2 && my < y2) cropHandle = 9;
		if (cropHandle == 0) return;
		cropAtStart = new int[]{s.cropX, s.cropY, s.cropW, s.cropH};
		dragCrop = cropAtStart.clone();
		cropMouseX = imgX(mx);
		cropMouseY = imgY(my);
	}

	private void dragCrop(double mx, double my) {
		EditState s = state();
		int[] r = cropAtStart;
		float dx = imgX(mx) - cropMouseX, dy = imgY(my) - cropMouseY;
		int W = s.width(), H = s.height();
		int l = r[0], t = r[1], rr = r[0] + r[2], b = r[1] + r[3];
		int min = EditState.MIN_CROP;
		switch (cropHandle) {
			case 9: {
				int nx = Math.max(0, Math.min(W - r[2], Math.round(r[0] + dx)));
				int ny = Math.max(0, Math.min(H - r[3], Math.round(r[1] + dy)));
				dragCrop = new int[]{nx, ny, r[2], r[3]};
				return;
			}
			default:
		}
		if (cropHandle == 1 || cropHandle == 7 || cropHandle == 8) l = Math.max(0, Math.min(rr - min, Math.round(r[0] + dx)));
		if (cropHandle == 3 || cropHandle == 4 || cropHandle == 5) rr = Math.min(W, Math.max(l + min, Math.round(r[0] + r[2] + dx)));
		if (cropHandle == 1 || cropHandle == 2 || cropHandle == 3) t = Math.max(0, Math.min(b - min, Math.round(r[1] + dy)));
		if (cropHandle == 5 || cropHandle == 6 || cropHandle == 7) b = Math.min(H, Math.max(t + min, Math.round(r[1] + r[3] + dy)));
		dragCrop = new int[]{l, t, rr - l, b - t};
	}

	private void commitText() {
		TextInput in = textInput;
		textInput = null;
		if (in == null || history == null || in.text().trim().isEmpty()) return;
		Shape s = new Shape.Text(textX, textY, in.text(), color, textSize(), 0);
		history.push(history.current().withShape(s));
		committed = s;
		committedRevision = history.revision();
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (textInput != null) {
			if (key == UiKey.ENTER) {
				commitText();
				return true;
			}
			if (key == UiKey.ESCAPE) {
				textInput = null;
				return true;
			}
			return textInput.key(key) || key != UiKey.NONE;
		}
		if (key == UiKey.ESCAPE) {
			if (confirmExit) confirmExit = false;
			else if (shareMenu) shareMenu = false;
			else if (drawing) drawing = false;
			else back();
			return true;
		}
		if (key == UiKey.ENTER && confirmExit) {
			confirmExit = false;
			save(new Runnable() {
				@Override
				public void run() {
					requestClose();
				}
			});
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(char ch) {
		if (textInput != null) return textInput.type(ch);
		if (confirmExit) return false;
		switch (Character.toLowerCase(ch)) {
			case 'z':
				undo();
				return true;
			case 'y':
				redo();
				return true;
			case 'r':
				rotate();
				return true;
			default:
				return false;
		}
	}

	@Override
	public boolean pausesGame() {
		return true;
	}

	@Override
	protected void onClosed() {
		Textures.Store store = Textures.store();
		if (texture != null && store != null) store.release(texture);
		texture = null;
		base = null;
		synchronized (previewLock) {
			previewBase = null;
		}
		owner.editorClosed();
		owner.platform().closeUi();
	}

	// --- Selbsttest ----------------------------------------------------------------------------------

	/** Bild geladen und Vorschau hochgeladen? */
	public boolean ready() {
		return history != null && texture != null && textureRevision == history.revision();
	}

	/** Für den Selbsttest: ein paar Formen setzen (Pfeil, Rahmen, Text, Verpixeln, Stift). */
	public void testDraw() {
		if (history == null) return;
		EditState s = history.current();
		float w = s.width(), h = s.height();
		history.push(s.withShape(new Shape.Rect(w * 0.1f, h * 0.1f, w * 0.45f, h * 0.45f, COLORS[0], strokeSize()))
				.withShape(new Shape.Arrow(w * 0.8f, h * 0.8f, w * 0.5f, h * 0.5f, COLORS[2], strokeSize()))
				.withShape(new Shape.Pixelate(w * 0.55f, h * 0.1f, w * 0.9f, h * 0.35f, blockSize()))
				.withShape(new Shape.Text(w * 0.1f, h * 0.6f, "TRS Client", COLORS[6], textSize(), 0))
				.withShape(new Shape.Pen(new float[]{w * 0.1f, h * 0.9f, w * 0.2f, h * 0.8f, w * 0.3f, h * 0.9f}, COLORS[4], strokeSize())));
	}

	/** Für den Selbsttest: Werkzeug wählen (0 = Zuschneiden …). */
	public void testTool(int index) {
		selectTool(Tool.values()[Math.max(0, Math.min(Tool.values().length - 1, index))]);
	}

	/** Für den Selbsttest: zuschneiden (Anteile) und drehen. */
	public void testCropRotate() {
		if (history == null) return;
		EditState s = history.current();
		history.push(s.withCrop(Math.round(s.width() * 0.05f), Math.round(s.height() * 0.05f), Math.round(s.width() * 0.9f),
				Math.round(s.height() * 0.9f)));
		history.push(history.current().rotatedClockwise());
	}

	/** Für den Selbsttest: Kopie speichern; Ergebnis über {@link #testSaved()}. */
	public void testSave() {
		save(null);
	}

	public Path testSaved() {
		return savedCopy;
	}

	public boolean testSaving() {
		return saving;
	}

	public String testNotice() {
		return notice;
	}

	/** Für den Selbsttest: Teilen-Menü öffnen. */
	public void testShareMenu() {
		shareMenu = true;
	}
}
