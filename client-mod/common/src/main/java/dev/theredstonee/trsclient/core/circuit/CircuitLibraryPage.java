package dev.theredstonee.trsclient.core.circuit;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;

import java.util.List;

/**
 * Seite „Schaltungs-Bibliothek“ im TRS-Menü: Liste mit Kategorien, Suche und Filter „läuft in meiner Version“;
 * Detailseite mit drehbarer isometrischer Vorschau (Schicht für Schicht), Erklärung, Materialliste samt
 * Inventar-Abgleich und „Als Vorlage einblenden“. Immediate Mode wie das restliche Menü.
 */
public final class CircuitLibraryPage {
	private static volatile boolean openRequested;
	private static volatile Circuit openCircuit;
	/** 0 = Liste/Details, 1 = Einreichen, 2 = Meine Einreichungen. */
	private static volatile int openSub;

	private final Runnable click;
	private final Runnable closeMenu;
	private final TextInput search = new TextInput(40);
	private final IsoPreview preview = new IsoPreview();
	private Circuit.Category category;
	private boolean myVersion = true;
	private int listScroll;
	private int detailScroll;
	private int detailMax;
	private int listMax;
	private Circuit detail;
	private boolean mirror;
	private double dragX = Double.NaN;
	private long lastFrame;
	private final int[] listRect = new int[4];
	private final int[] detailRect = new int[4];
	private String note;
	private long noteUntil;
	/** 0 = Liste/Details, 1 = Einreichen, 2 = Meine Einreichungen. */
	private int sub;
	private final CircuitSubmitPage submitPage;

	public CircuitLibraryPage(Runnable click, Runnable closeMenu) {
		this.click = click;
		this.closeMenu = closeMenu;
		this.submitPage = new CircuitSubmitPage(click, closeMenu);
	}

	/** Beim nächsten Zeichnen des Menüs „Eigene Schaltung einreichen“ zeigen (nach dem Markieren). */
	public static void requestSubmit() {
		openSub = 1;
		openRequested = true;
	}

	/** Beim nächsten Zeichnen des Menüs diese Seite zeigen. */
	public static void requestOpen() {
		openRequested = true;
	}

	/** Beim nächsten Zeichnen direkt die Detailseite dieser Schaltung zeigen (Selbsttest, Tasten). */
	public static void requestOpen(Circuit circuit) {
		openCircuit = circuit;
		openRequested = true;
	}

	public static boolean takeOpenRequest() {
		boolean r = openRequested;
		openRequested = false;
		return r;
	}

	/** Seite (wieder) betreten: Liste, eingeblendete Schaltung vorausgewählt nicht nötig. */
	public void reset() {
		search.setFocused(false);
		dragX = Double.NaN;
		Circuit c = openCircuit;
		openCircuit = null;
		if (c != null) show(c);
		int s = openSub;
		openSub = 0;
		if (s != 0) openSub(s);
	}

	private void openSub(int s) {
		sub = s;
		detail = null;
		submitPage.reset();
		if (s == 2 && Circuits.get().submissions() != null) Circuits.get().submissions().refreshMineAsync();
	}

	/** Direkt die Detailseite einer Schaltung (Selbsttest). */
	public void show(Circuit c) {
		sub = 0;
		detail = c;
		detailScroll = 0;
		preview.reset();
	}

	/** Esc: von der Detailseite zurück zur Liste; false = Seite verlassen. */
	public boolean back() {
		if (search.focused()) {
			search.setFocused(false);
			return true;
		}
		if (sub != 0) {
			if (submitPage.back()) return true;
			sub = 0;
			return true;
		}
		if (detail != null) {
			detail = null;
			return true;
		}
		return false;
	}

	private static String mcVersion() {
		Circuits.Platform p = Circuits.get().platform();
		return p == null ? null : p.minecraftVersion();
	}

	// --- Zeichnen ---

	public void draw(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		long now = System.currentTimeMillis();
		float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		if (sub != 0) {
			drawSubHeader(c, hits, x, y, w, mx, my);
			if (sub == 1) submitPage.drawSubmit(c, hits, x, y + 22, w, h - 22, mx, my, dt);
			else submitPage.drawMine(c, hits, x, y + 22, w, h - 22, mx, my);
		} else if (detail != null) {
			drawDetail(c, hits, x, y, w, h, mx, my, dt);
		} else {
			drawList(c, hits, x, y, w, h, mx, my);
		}
	}

	private void drawSubHeader(Canvas c, Hits hits, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		boolean bh = inside(mx, my, x, y, 16, 16);
		Paint.iconButton(c, x, y, 16, "back", bh, false);
		hits.add(x, y, 16, 16, new Runnable() {
			@Override
			public void run() {
				click.run();
				sub = 0;
			}
		});
		Paint.textClipped(c, I18n.tr(sub == 1 ? "circuits.submit.title" : "circuits.mine.title"), x + 22, y + 4, w - 30, t.text, true);
	}

	private void drawList(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		CircuitTexts texts = CircuitTexts.get();
		// Suche + Versionsfilter
		String ver = mcVersion();
		String toggle = I18n.tr("circuits.myVersion");
		int tw = c.textWidth(toggle) + 22;
		int sw = w - tw - 6;
		Redstone.well(c, x, y, sw, 16, search.focused() ? t.accent : t.border);
		String q = search.text();
		if (q.isEmpty() && !search.focused()) {
			Paint.textClipped(c, I18n.tr("circuits.search"), x + 5, y + 4, sw - 10, t.textDim, false);
		} else {
			String shown = q;
			while (c.textWidth(shown) > sw - 12 && shown.length() > 1) shown = shown.substring(1);
			c.text(shown, x + 5, y + 4, t.text, false);
			if (search.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
				int caret = x + 5 + c.textWidth(shown);
				c.fill(caret, y + 4, caret + 1, y + 12, t.dustOn);
			}
		}
		hits.add(x, y, sw, 16, new Runnable() {
			@Override
			public void run() {
				search.setFocused(true);
			}
		});
		int bx = x + sw + 6;
		boolean th = inside(mx, my, bx, y, tw, 16);
		Redstone.stone(c, bx, y, tw, 16, th ? t.surfaceHover : t.surface, myVersion ? t.accent : t.border);
		Redstone.pip(c, bx + 5, y + 4, 8, myVersion ? 1f : 0f);
		c.text(toggle, bx + 17, y + 4, myVersion ? t.text : t.textDim, false);
		hits.add(bx, y, tw, 16, new Runnable() {
			@Override
			public void run() {
				click.run();
				myVersion = !myVersion;
				listScroll = 0;
			}
		});

		// Kategorien (umbrechend)
		int cy = y + 21;
		int cx = x;
		cy = chip(c, hits, null, I18n.tr("circuits.all"), cx, cy, x, w, mx, my);
		for (Circuit.Category cat : Circuit.Category.values()) {
			cy = chip(c, hits, cat, texts.category(cat), chipX, cy, x, w, mx, my);
		}
		cy += 17;
		cy = subButtons(c, hits, x, cy, w, mx, my);

		// Liste
		CircuitLibrary library = CircuitLibrary.get();
		if (library.isEmpty()) {
			String status;
			CircuitSync.Status st = CircuitSync.status();
			if (st == CircuitSync.Status.RUNNING) status = I18n.tr("circuits.loading");
			else if (st == CircuitSync.Status.DISABLED) status = I18n.tr("circuits.disabled");
			else status = I18n.tr("circuits.notLoaded");
			Paint.paragraph(c, status, x + 4, cy + 6, w - 8, 10, t.textDim);
			return;
		}
		List<Circuit> list = library.filter(search.text(), category, myVersion ? ver : null, texts);
		int top = cy;
		int areaH = y + h - top;
		listRect[0] = x;
		listRect[1] = top;
		listRect[2] = w;
		listRect[3] = areaH;
		int rowH = 26;
		listMax = Math.max(0, list.size() * rowH - areaH);
		listScroll = Math.max(0, Math.min(listScroll, listMax));
		if (list.isEmpty()) {
			Paint.textCentered(c, I18n.tr("circuits.empty"), x + w / 2, top + 12, t.textDim, false);
			return;
		}
		c.scissor(x, top, x + w, top + areaH);
		hits.clip(x, top, w, areaH);
		Circuit active = Circuits.get().active();
		int ry = top - listScroll;
		for (final Circuit circuit : list) {
			if (ry + rowH >= top && ry < top + areaH) {
				boolean hover = inside(mx, my, x, ry, w - 6, rowH - 2) && my >= top && my < top + areaH;
				Redstone.stone(c, x, ry, w - 6, rowH - 2, hover ? t.surfaceHover : t.surface,
						circuit == active ? t.accent : t.border);
				Icons.draw(c, iconOf(circuit.category), x + 5, ry + 7, 1, circuit == active ? t.accent : t.textDim);
				int nameW = w - 60;
				Paint.textClipped(c, texts.name(circuit), x + 18, ry + 3, nameW, t.text, false);
				Paint.textClipped(c, meta(circuit, texts, ver), x + 18, ry + 13, w - 34, t.textDim, false);
				difficulty(c, circuit.difficulty, x + w - 34, ry + 4);
				hits.add(x, ry, w - 6, rowH - 2, new Runnable() {
					@Override
					public void run() {
						click.run();
						show(circuit);
					}
				});
			}
			ry += rowH;
		}
		hits.noClip();
		c.noScissor();
		scrollbar(c, x + w - 3, top, areaH, listScroll, listMax);
	}

	private int chipX;

	/** Zeile mit „Eigene Schaltung einreichen“ und „Meine Einreichungen“. */
	private int subButtons(Canvas c, Hits hits, int x, int y, int w, int mx, int my) {
		String a = I18n.tr("circuits.submit.title");
		String b = I18n.tr("circuits.mine.title");
		int aw = Math.min((w - 4) / 2, c.textWidth(a) + 16);
		int bw = Math.min((w - 4) / 2, c.textWidth(b) + 16);
		Paint.button(c, x, y, aw, 15, c.clip(a, aw - 8), false, inside(mx, my, x, y, aw, 15));
		hits.add(x, y, aw, 15, new Runnable() {
			@Override
			public void run() {
				click.run();
				openSub(1);
			}
		});
		Paint.button(c, x + aw + 4, y, bw, 15, c.clip(b, bw - 8), false, inside(mx, my, x + aw + 4, y, bw, 15));
		hits.add(x + aw + 4, y, bw, 15, new Runnable() {
			@Override
			public void run() {
				click.run();
				openSub(2);
			}
		});
		return y + 19;
	}

	/** Kategorie-Knopf; gibt die (evtl. neue) Zeile zurück und setzt {@link #chipX}. */
	private int chip(Canvas c, Hits hits, final Circuit.Category cat, String label, int cx, int cy, int x, int w, int mx,
			int my) {
		Theme t = Theme.get();
		int cw = c.textWidth(label) + 10;
		if (cx + cw > x + w && cx > x) {
			cx = x;
			cy += 16;
		}
		boolean selected = category == cat;
		boolean hover = inside(mx, my, cx, cy, cw, 14);
		Redstone.stone(c, cx, cy, cw, 14, selected ? ColorMath.lerp(t.surface, t.accent, 0.3f) : hover ? t.surfaceHover : t.surface,
				selected ? t.accent : t.border);
		c.text(label, cx + 5, cy + 3, selected ? t.text : t.textDim, false);
		hits.add(cx, cy, cw, 14, new Runnable() {
			@Override
			public void run() {
				click.run();
				category = cat;
				listScroll = 0;
			}
		});
		chipX = cx + cw + 3;
		return cy;
	}

	private static String iconOf(Circuit.Category c) {
		switch (c) {
			case CLOCKS: return "clock";
			case MEMORY: return "chip";
			case PULSE: return "bolt";
			case DOORS: return "home";
			case FARMS: return "packs";
			case DISPLAYS: return "gauge";
			default: return "redstone";
		}
	}

	private static String meta(Circuit c, CircuitTexts texts, String ver) {
		StringBuilder b = new StringBuilder(texts.category(c.category));
		b.append(" · ").append(c.sizeX).append('×').append(c.sizeY).append('×').append(c.sizeZ);
		b.append(" · ").append(I18n.tr("circuits.blocks", c.blockCount()));
		if (!"1.8".equals(c.since)) b.append(" · ").append(I18n.tr("circuits.sinceShort", c.since));
		if (ver != null && !c.runsIn(ver)) b.append(" · ").append(I18n.tr("circuits.notHere"));
		return b.toString();
	}

	private static void difficulty(Canvas c, int level, int x, int y) {
		Theme t = Theme.get();
		for (int i = 0; i < 3; i++) Redstone.pip(c, x + i * 9, y, 7, i < level ? 1f : 0f);
		if (level <= 0) c.fill(x, y, x, y, t.border);
	}

	private void drawDetail(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		final Circuit circuit = detail;
		CircuitTexts texts = CircuitTexts.get();
		String ver = mcVersion();
		// Kopf
		boolean bh = inside(mx, my, x, y, 16, 16);
		Paint.iconButton(c, x, y, 16, "back", bh, false);
		hits.add(x, y, 16, 16, new Runnable() {
			@Override
			public void run() {
				click.run();
				detail = null;
			}
		});
		Paint.textClipped(c, texts.name(circuit), x + 22, y + 4, w - 60, t.text, true);
		difficulty(c, circuit.difficulty, x + w - 28, y + 5);

		int by = y + 22;
		int bodyH = y + h - by;
		boolean wide = w >= 300;
		int pw = wide ? Math.max(120, (int) (w * 0.44f)) : w;
		int ph = wide ? bodyH - 20 : Math.min(110, bodyH / 2);
		// Vorschau
		Redstone.well(c, x, by, pw, ph, t.border);
		final int prx = x, pry = by, prw = pw, prh = ph;
		c.scissor(x + 1, by + 1, x + pw - 1, by + ph - 1);
		preview.draw(c, circuit, mirror, x + 2, by + 2, pw - 4, ph - 4, dt);
		c.noScissor();
		hits.addDrag(prx, pry, prw, prh, new Hits.Drag() {
			@Override
			public void to(double mouseX, double mouseY) {
				if (!Double.isNaN(dragX)) preview.drag((float) ((mouseX - dragX) * 0.02));
				dragX = mouseX;
			}
		});
		// Vorschau-Knöpfe
		int cy = by + ph + 3;
		int bx = x;
		bx = smallButton(c, hits, "undo", bx, cy, mx, my, new Runnable() {
			@Override
			public void run() {
				preview.turn(-1);
			}
		});
		bx = smallButton(c, hits, "redo", bx, cy, mx, my, new Runnable() {
			@Override
			public void run() {
				preview.turn(1);
			}
		});
		bx += 4;
		bx = smallButton(c, hits, "prev", bx, cy, mx, my, new Runnable() {
			@Override
			public void run() {
				preview.stepLayer(circuit, -1);
			}
		});
		String layerText = preview.layer() < 0 ? I18n.tr("circuits.layerAll")
				: I18n.tr("circuits.layer", preview.layer() + 1, circuit.sizeY);
		int lw = Math.min(c.textWidth(layerText), Math.max(10, x + pw - bx - 22));
		Paint.textClipped(c, layerText, bx + 2, cy + 4, lw, t.textDim, false);
		bx += lw + 4;
		smallButton(c, hits, "next", bx, cy, mx, my, new Runnable() {
			@Override
			public void run() {
				preview.stepLayer(circuit, 1);
			}
		});

		// Text-Spalte
		int tx = wide ? x + pw + 8 : x;
		int ty = wide ? by : cy + 20;
		int tw = wide ? w - pw - 8 : w;
		int th = y + h - ty;
		detailRect[0] = tx;
		detailRect[1] = ty;
		detailRect[2] = tw;
		detailRect[3] = th;
		c.scissor(tx, ty, tx + tw, ty + th);
		hits.clip(tx, ty, tw, th);
		int yy = ty - detailScroll;
		int lh = 10;
		yy = Paint.paragraph(c, meta(circuit, texts, null), tx, yy, tw - 6, lh, t.textDim);
		if (ver != null && !circuit.runsIn(ver)) {
			yy = Paint.paragraph(c, I18n.tr("circuits.notInVersion", circuit.since, ver), tx, yy, tw - 6, lh, 0xFFFF7070);
		} else {
			yy = Paint.paragraph(c, I18n.tr("circuits.since", circuit.since), tx, yy, tw - 6, lh, t.textDim);
		}
		yy = Paint.paragraph(c, circuit.serverOk ? I18n.tr("circuits.serverOk") : I18n.tr("circuits.serverNote", texts.note(circuit)),
				tx, yy, tw - 6, lh, circuit.serverOk ? 0xFF7CD88C : 0xFFF0C050);
		if (circuit.simulated()) yy = Paint.paragraph(c, I18n.tr("circuits.simulated"), tx, yy, tw - 6, lh, t.textDim);
		yy += 4;
		yy = Paint.paragraph(c, texts.desc(circuit), tx, yy, tw - 6, lh, t.text);
		yy += 6;
		c.text(I18n.tr("circuits.materials"), tx, yy, t.text, true);
		yy += 12;
		Circuits ctl = Circuits.get();
		for (Circuit.Material m : circuit.materials()) {
			String item = ctl.itemOf(m.def);
			Object stack = null;
			try {
				Circuits.Platform p = ctl.platform();
				stack = p == null ? null : p.stack(item);
			} catch (RuntimeException ignored) {
				stack = null;
			}
			int rowY = yy;
			if (stack != null) {
				c.flush();
				c.item(stack, tx, rowY - 2);
			} else {
				c.fill(tx + 3, rowY + 1, tx + 11, rowY + 9, BlockLook.color(m.def));
			}
			String line = m.count + "× " + texts.block(m.def);
			int have = ctl.inventoryCount(item);
			String haveText = have < 0 ? null : I18n.tr("circuits.have", have, m.count);
			int hw = haveText == null ? 0 : c.textWidth(haveText) + 4;
			Paint.textClipped(c, line, tx + 20, rowY + 2, tw - 26 - hw, t.text, false);
			if (haveText != null) {
				Paint.textRight(c, haveText, tx + tw - 8, rowY + 2, have >= m.count ? 0xFF7CD88C : 0xFFFF8080, false);
			}
			yy += 16;
		}
		yy += 4;
		// Knöpfe
		boolean inWorld = ctl.inWorld();
		boolean runs = ver == null || circuit.runsIn(ver);
		yy = button(c, hits, I18n.tr("circuits.place"), tx, yy, tw - 6, true, inWorld && runs, mx, my, new Runnable() {
			@Override
			public void run() {
				Circuits.get().startPlacing(circuit, mirror);
				closeMenu.run();
			}
		});
		yy = button(c, hits, I18n.tr("circuits.placeSaved"), tx, yy, tw - 6, false, inWorld && runs && ctl.hasSavedPosition(), mx, my,
				new Runnable() {
					@Override
					public void run() {
						if (Circuits.get().placeAtSaved(circuit, mirror)) closeMenu.run();
					}
				});
		yy = button(c, hits, I18n.tr(mirror ? "circuits.mirrorOn" : "circuits.mirrorOff"), tx, yy, tw - 6, false, true, mx, my,
				new Runnable() {
					@Override
					public void run() {
						mirror = !mirror;
					}
				});
		if (ctl.active() == circuit) {
			yy = button(c, hits, I18n.tr("circuits.remove"), tx, yy, tw - 6, false, true, mx, my, new Runnable() {
				@Override
				public void run() {
					Circuits.get().remove();
					say(I18n.tr("circuits.removed"));
				}
			});
		}
		if (!inWorld) yy = Paint.paragraph(c, I18n.tr("circuits.noWorld"), tx, yy, tw - 6, lh, 0xFFF0C050);
		if (note != null && System.currentTimeMillis() < noteUntil) yy = Paint.paragraph(c, note, tx, yy, tw - 6, lh, t.accent);
		yy += 2;
		yy = Paint.paragraph(c, I18n.tr("circuits.fairPlay"), tx, yy, tw - 6, lh, t.textDim);
		hits.noClip();
		c.noScissor();
		detailMax = Math.max(0, yy + detailScroll - (ty + th) + 4);
		detailScroll = Math.min(detailScroll, detailMax);
		scrollbar(c, tx + tw - 3, ty, th, detailScroll, detailMax);
	}

	private int smallButton(Canvas c, Hits hits, String icon, int x, int y, int mx, int my, final Runnable action) {
		boolean hover = inside(mx, my, x, y, 16, 16);
		Paint.iconButton(c, x, y, 16, icon, hover, false);
		hits.add(x, y, 16, 16, new Runnable() {
			@Override
			public void run() {
				click.run();
				action.run();
			}
		});
		return x + 18;
	}

	private int button(Canvas c, Hits hits, String label, int x, int y, int w, boolean primary, boolean enabled, int mx, int my,
			final Runnable action) {
		Theme t = Theme.get();
		boolean hover = enabled && inside(mx, my, x, y, w, 17);
		if (enabled) {
			Paint.button(c, x, y, w, 17, c.clip(label, w - 10), primary, hover);
			hits.add(x, y, w, 17, new Runnable() {
				@Override
				public void run() {
					click.run();
					action.run();
				}
			});
		} else {
			Redstone.stone(c, x, y, w, 17, t.surface, t.border);
			Paint.textCentered(c, c.clip(label, w - 10), x + w / 2, y + 5, ColorMath.withAlpha(t.textDim, 140), false);
		}
		return y + 21;
	}

	private static void scrollbar(Canvas c, int x, int y, int h, int scroll, int max) {
		if (max <= 0) return;
		Theme t = Theme.get();
		c.fill(x, y, x + 2, y + h, t.border);
		int bar = Math.max(12, h * h / (h + max));
		int by = y + (int) ((h - bar) * (scroll / (double) max));
		c.fill(x, by, x + 2, by + bar, t.accent);
	}

	private void say(String text) {
		note = text;
		noteUntil = System.currentTimeMillis() + 2500;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	// --- Eingaben ---

	public boolean mouseScrolled(double mx, double my, double amount) {
		if (sub != 0) return submitPage.mouseScrolled(mx, my, amount);
		int step = (int) Math.signum(amount) * 18;
		if (detail != null) {
			if (inside(mx, my, detailRect[0], detailRect[1], detailRect[2], detailRect[3])) {
				detailScroll = Math.max(0, Math.min(detailMax, detailScroll - step));
				return true;
			}
			return false;
		}
		if (inside(mx, my, listRect[0], listRect[1], listRect[2], listRect[3])) {
			listScroll = Math.max(0, Math.min(listMax, listScroll - step));
			return true;
		}
		return false;
	}

	public void mouseReleased() {
		dragX = Double.NaN;
		submitPage.mouseReleased();
	}

	public boolean keyPressed(UiKey key) {
		if (sub != 0) return submitPage.keyPressed(key);
		if (search.focused()) {
			if (key == UiKey.ESCAPE || key == UiKey.ENTER) {
				search.setFocused(false);
				return true;
			}
			if (search.key(key)) {
				listScroll = 0;
				return true;
			}
		}
		if (detail != null) {
			if (key == UiKey.LEFT) {
				preview.turn(-1);
				return true;
			}
			if (key == UiKey.RIGHT) {
				preview.turn(1);
				return true;
			}
			if (key == UiKey.UP) {
				preview.stepLayer(detail, 1);
				return true;
			}
			if (key == UiKey.DOWN) {
				preview.stepLayer(detail, -1);
				return true;
			}
		}
		return false;
	}

	public boolean charTyped(char ch) {
		if (sub != 0) return submitPage.charTyped(ch);
		if (detail != null) return false;
		if (search.focused()) {
			boolean typed = search.type(ch);
			if (typed) listScroll = 0;
			return typed;
		}
		if (TextInput.allowed(ch) && ch != ' ') {
			search.setFocused(true);
			listScroll = 0;
			return search.type(ch);
		}
		return false;
	}
}
