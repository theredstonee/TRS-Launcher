package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.hud.HudProfiles;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.Category;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.Setting;
import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.TileGrid;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Das TRS-Menü: Kacheln aller Module mit Suche und Kategorie-Reitern, dahinter je Modul eine
 * Einstellungsseite und die Verwaltung der HUD-Profile. Alles versionsunabhängig – gezeichnet
 * wird über {@link Canvas}, Minecraft kommt nur über {@link MenuHost} ins Spiel.
 */
public final class ModMenu extends UiScreen {
	private enum Page {
		GRID, SETTINGS, PROFILES
	}

	private static final int HEADER_H = 30;
	private static final int RAIL_W = 112;
	/** Kacheln: 2–4 Spalten, nie schmaler als das, damit Namen ganz passen. */
	private static final int TILE_MIN_W = 116;
	private static final int TILE_H = 60;
	private static final int TILE_GAP = 8;
	private static final int PAD = 10;
	private static final int MAX_COLUMNS = 4;
	/** Breite der Leiste und Mindestbreite der Kacheln – in kleinen Fenstern schmaler (siehe draw). */
	private int railW = RAIL_W;
	private int tileMin = TILE_MIN_W;

	private final MenuHost host;
	private final SettingsPanel panel = new SettingsPanel();
	private final TextInput search = new TextInput(32);
	private final TextInput nameInput = new TextInput(HudProfiles.MAX_NAME_LENGTH);
	private final Map<String, Float> hover = new HashMap<String, Float>();

	private Page page = Page.GRID;
	private Category category;
	private Module selected;
	private int gridScroll;
	private int settingsScroll;
	private int settingsHeight;
	/** -1 = kein Umbenennen, -2 = neues Profil, sonst Profil-Index. */
	private int editingProfile = -1;
	private String profileError;
	/** Zuletzt gezeichneter Inhaltsbereich (für das Mausrad). */
	private final int[] contentRect = new int[4];
	/** Spieler-Vorschau: Drehung (Grad), letzte Maus-x beim Ziehen, Zeitpunkt der letzten Bedienung. */
	private float previewYaw = 150f;
	private double previewDragX = Double.NaN;
	private long previewTouched;

	public ModMenu(MenuHost host) {
		this.host = host;
		// Sprache neu lesen (Launcher-Datei oder Minecraft-Sprache geändert?) – zwei Zeitstempel.
		I18n.refresh();
		panel.setKeyLabel(new SettingsPanel.KeyLabel() {
			@Override
			public String label(String keyName) {
				return ModMenu.this.host.keyLabel(keyName);
			}
		});
	}

	/** Öffnet das Menü direkt auf der Einstellungsseite eines Moduls (Autotest, Verknüpfungen). */
	public ModMenu select(Module module) {
		if (module != null) {
			selected = module;
			page = Page.SETTINGS;
			panel.reset();
		}
		return this;
	}

	/** Einstellungsseite nach unten rollen (Autotest: untere Teile einer langen Seite zeigen). */
	public ModMenu scrollSettings(int pixels) {
		settingsScroll = Math.max(0, settingsScroll + pixels);
		return this;
	}

	/** Öffnet das Menü direkt bei einer Kategorie (Kachel-Ansicht). */
	public ModMenu showCategory(Category category) {
		this.category = category;
		page = Page.GRID;
		return this;
	}

	/** Öffnet das Menü direkt bei den HUD-Profilen. */
	public ModMenu showProfiles() {
		page = Page.PROFILES;
		return this;
	}

	@Override
	protected void onClosed() {
		host.save();
		host.closeScreen();
	}

	// --- Zeichnen ---

	@Override
	protected void draw(Canvas raw, int width, int height, int mouseX, int mouseY, float dt) {
		Theme t = Theme.get();
		Canvas c = FadeCanvas.of(raw, alpha());
		c.fill(0, 0, width, height, t.scrim);

		// Fenster: nutzt den Bildschirm (GUI-Pixel – wächst also mit kleinerer GUI-Größe mit).
		int pw = Math.min(width - 16, Math.max(Math.min(440, width - 16), Math.min(780, Math.round(width * 0.86f))));
		int ph = Math.min(height - 16, Math.max(Math.min(264, height - 16), Math.min(470, Math.round(height * 0.86f))));
		int px = (width - pw) / 2;
		int py = (height - ph) / 2 + Math.round((1 - Anim.easeOut(open)) * 14);

		// Das Fenster liegt über allem, was vorher gezeichnet wurde (HUD-Text hat eigene Tiefe).
		c.push();
		c.raise(300f);
		Redstone.window(c, px, py, pw, ph);
		boolean narrow = pw < 520;
		railW = narrow ? 96 : RAIL_W;
		tileMin = narrow ? 98 : TILE_MIN_W;

		header(c, px, py, pw, mouseX, mouseY);
		rail(c, px + PAD, py + HEADER_H + PAD, railW - PAD - 6, ph - HEADER_H - PAD * 2, mouseX, mouseY, dt);
		// Trennlinie zwischen Leiste und Inhalt: unbestromter Staub.
		Redstone.dustV(c, px + railW, py + HEADER_H + PAD, py + ph - PAD, Theme.get().dustOff, 0f);

		int cx = px + railW + PAD;
		int cy = py + HEADER_H + PAD;
		int cw = pw - railW - PAD * 2;
		int ch = ph - HEADER_H - PAD * 2;
		contentRect[0] = cx;
		contentRect[1] = cy;
		contentRect[2] = cw;
		contentRect[3] = ch;
		switch (page) {
			case SETTINGS:
				settingsPage(c, cx, cy, cw, ch, mouseX, mouseY, dt);
				break;
			case PROFILES:
				profilesPage(c, cx, cy, cw, ch, mouseX, mouseY);
				break;
			default:
				grid(c, cx, cy, cw, ch, mouseX, mouseY, dt);
				break;
		}
		c.pop();
	}

	private void header(Canvas c, int px, int py, int pw, int mx, int my) {
		Theme t = Theme.get();
		c.fill(px + 1, py + 2, px + pw - 1, py + HEADER_H, t.surfaceHigh);
		c.fill(px + 1, py + HEADER_H - 1, px + pw - 1, py + HEADER_H, t.border);
		// Schriftzug: "TRS" in Pixelschrift mit Leuchten, daneben "Client"
		int lx = px + PAD;
		int ly = py + (HEADER_H - PixelFont.HEIGHT * 2) / 2;
		List<int[]> rects = PixelFont.rects("TRS");
		int glow = ColorMath.withAlpha(t.glow, 40);
		for (int[] r : rects) c.fill(lx + r[0] * 2 - 1, ly + r[1] * 2 - 1, lx + r[2] * 2 + 1, ly + r[3] * 2 + 1, glow);
		int light = ColorMath.lerp(t.accent, 0xFFFFFFFF, 0.3f);
		for (int[] r : rects) c.fill(lx + r[0] * 2, ly + r[1] * 2, lx + r[2] * 2, ly + r[3] * 2, r[1] == 0 ? light : t.accent);
		c.text(I18n.tr("menu.client"), lx + PixelFont.width("TRS") * 2 + 5, py + (HEADER_H - 8) / 2, t.textDim, false);

		// Schließen
		int closeSize = 16;
		int closeX = px + pw - PAD - closeSize;
		int closeY = py + (HEADER_H - closeSize) / 2;
		boolean closeHover = inside(mx, my, closeX, closeY, closeSize, closeSize);
		Paint.iconButton(c, closeX, closeY, closeSize, "close", closeHover, false);
		hits.add(closeX, closeY, closeSize, closeSize, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				requestClose();
			}
		});

		// Suchfeld (Mulde)
		int searchH = 16;
		int searchW = Math.min(180, Math.max(80, pw / 3));
		int searchX = closeX - 8 - searchW;
		int searchY = py + (HEADER_H - searchH) / 2;
		boolean searchHover = inside(mx, my, searchX, searchY, searchW, searchH);
		Redstone.well(c, searchX, searchY, searchW, searchH, search.focused() ? t.accent : (searchHover ? t.textDim : t.border));
		if (search.focused()) Redstone.glow(c, searchX, searchY, searchW, searchH, t.glow, 0.5f);
		Icons.draw(c, "search", searchX + 5, searchY + 4, 1, search.focused() ? t.dustOn : t.textDim);
		String shown = search.isEmpty() && !search.focused() ? I18n.tr("menu.search") : search.text();
		int textColor = search.isEmpty() && !search.focused() ? t.textDim : t.text;
		Paint.textClipped(c, shown, searchX + 16, searchY + 4, searchW - 22, textColor, false);
		if (search.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
			int caret = searchX + 16 + c.textWidth(search.text());
			c.fill(Math.min(caret, searchX + searchW - 3), searchY + 4, Math.min(caret + 1, searchX + searchW - 2), searchY + 12, t.dustOn);
		}
		hits.add(searchX, searchY, searchW, searchH, new Runnable() {
			@Override
			public void run() {
				search.setFocused(true);
			}
		});
	}

	private void rail(Canvas c, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		// Zeilenhöhe und Abstand so wählen, dass alle Einträge (und möglichst die Fußzeile) passen.
		// "Alle" + Kategorien + HUD-Editor + Profile (+ Packs) (+ Konten)
		int items = 3 + Category.values().length + (host.hasPacks() ? 1 : 0) + (host.hasAccounts() ? 1 : 0)
				+ (host.hasFriends() ? 1 : 0) + (host.hasClips() ? 1 : 0);
		int rowH = 18;
		int gap = 3;
		int footer = 32;
		while (items * (rowH + gap) + 11 + footer > h && (gap > 1 || rowH > 13)) {
			if (gap > 1) gap--;
			else rowH--;
		}
		if (items * (rowH + gap) + 11 + footer > h) footer = 0;
		int cy = y;
		railItem(c, x, cy, w, rowH, "layers", I18n.tr("menu.all"), category == null && page == Page.GRID, mx, my, new Runnable() {
			@Override
			public void run() {
				category = null;
				page = Page.GRID;
				gridScroll = 0;
			}
		});
		cy += rowH + gap;
		Category[] categories = Category.values();
		for (int i = 0; i < categories.length; i++) {
			final Category cat = categories[i];
			// Reiter ohne ein einziges Modul dieser Version (z. B. Leistung in Forge 1.7.10) weglassen.
			if (!hasModules(cat)) continue;
			railItem(c, x, cy, w, rowH, cat.icon(), cat.label(), category == cat && page == Page.GRID, mx, my, new Runnable() {
				@Override
				public void run() {
					category = cat;
					page = Page.GRID;
					gridScroll = 0;
				}
			});
			cy += rowH + gap;
		}

		cy += 4;
		c.fill(x + 2, cy, x + w - 2, cy + 1, t.border);
		cy += 7;

		railItem(c, x, cy, w, rowH, "move", I18n.tr("menu.hudEditor"), false, mx, my, new Runnable() {
			@Override
			public void run() {
				host.save();
				host.openHudEditor();
			}
		});
		cy += rowH + gap;
		railItem(c, x, cy, w, rowH, "profile", I18n.tr("menu.profiles"), page == Page.PROFILES, mx, my, new Runnable() {
			@Override
			public void run() {
				page = Page.PROFILES;
				editingProfile = -1;
				profileError = null;
			}
		});
		cy += rowH + gap;
		if (host.hasPacks()) {
			railItem(c, x, cy, w, rowH, "packs", I18n.tr("menu.packs"), false, mx, my, new Runnable() {
				@Override
				public void run() {
					host.save();
					host.openPacks();
				}
			});
			cy += rowH + gap;
		}
		if (host.hasAccounts()) {
			railItem(c, x, cy, w, rowH, "accounts", I18n.tr("menu.accounts"), false, mx, my, new Runnable() {
				@Override
				public void run() {
					host.save();
					host.openAccounts();
				}
			});
			cy += rowH + gap;
		}
		if (host.hasFriends()) {
			railItem(c, x, cy, w, rowH, "friends", I18n.tr("menu.friends"), false, mx, my, new Runnable() {
				@Override
				public void run() {
					host.save();
					host.openFriends();
				}
			});
			cy += rowH + gap;
		}
		if (host.hasClips()) {
			railItem(c, x, cy, w, rowH, "image", I18n.tr("menu.clips"), false, mx, my, new Runnable() {
				@Override
				public void run() {
					host.save();
					host.openClips();
				}
			});
			cy += rowH + gap;
		}

		// Fußzeile der Leiste: wie viele Module unter Strom stehen, und die Taste des Menüs.
		int active = 0;
		int total = 0;
		List<Module> all = host.modules().registry.all();
		for (int i = 0; i < all.size(); i++) {
			if (!host.supports(all.get(i))) continue;
			total++;
			if (all.get(i).isEnabled()) active++;
		}
		int footerY = y + h - 30;
		if (footer == 0 || footerY < cy + 2) return;
		Redstone.pip(c, x + 2, footerY + 1, 7, active > 0 ? 1f : 0f);
		Paint.textClipped(c, I18n.tr("menu.activeCount", active, total), x + 14, footerY, w - 14, t.text, false);
		Redstone.keycap(c, x, footerY + 14, host.menuKeyLabel(), w);
	}

	private boolean hasModules(Category cat) {
		List<Module> all = host.modules().registry.all();
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).category() == cat && host.supports(all.get(i))) return true;
		}
		return false;
	}

	private void railItem(Canvas c, int x, int y, int w, int h, String icon, String label, boolean active,
			int mx, int my, Runnable action) {
		Theme t = Theme.get();
		boolean hovered = inside(mx, my, x, y, w, h);
		if (active) {
			Redstone.block(c, x, y, w, h, ColorMath.withAlpha(t.accent, 44));
			Redstone.dustV(c, x, y + 2, y + h - 2, t.dustOn, 1f);
		} else if (hovered) {
			Redstone.block(c, x, y, w, h, t.surfaceHover);
			Redstone.dustV(c, x, y + 3, y + h - 3, t.dustOff, 0f);
		}
		Icons.draw(c, icon, x + 7, y + (h - 8) / 2, 1, active ? t.dustOn : (hovered ? t.text : t.textDim));
		Paint.textClipped(c, label, x + 19, y + (h - 8) / 2, w - 22, active || hovered ? t.text : t.textDim, false);
		hits.add(x, y, w, h, new Click(action));
	}

	// --- Kachel-Ansicht ---

	private List<Module> visibleModules() {
		List<Module> out = new ArrayList<Module>();
		String query = search.text().trim();
		List<Module> all = host.modules().registry.all();
		for (int i = 0; i < all.size(); i++) {
			Module m = all.get(i);
			if (!host.supports(m)) continue;
			if (!query.isEmpty()) {
				if (m.matches(query)) out.add(m);
				continue;
			}
			if (category == null || m.category() == category) out.add(m);
		}
		return out;
	}

	private void grid(Canvas c, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		List<Module> modules = visibleModules();
		int usable = w - 7;
		int minW = Math.max(tileMin, (usable - (MAX_COLUMNS - 1) * TILE_GAP) / MAX_COLUMNS);
		TileGrid layout = TileGrid.of(usable, h - 6, modules.size(), minW, TILE_H, TILE_GAP);
		gridScroll = layout.clampScroll(gridScroll);

		if (modules.isEmpty()) {
			Paint.textCentered(c, I18n.tr("menu.noMatch", search.text().trim()), x + w / 2, y + h / 2 - 10, t.text, false);
			Paint.textCentered(c, I18n.tr("menu.clearSearch"), x + w / 2, y + h / 2 + 2, t.textDim, false);
			return;
		}
		c.scissor(x, y, x + w, y + h);
		hits.clip(x, y, w, h);
		for (int i = 0; i < modules.size(); i++) {
			final Module m = modules.get(i);
			// 3 px Rand für das Leuchten der Kacheln
			int tx = x + 3 + layout.x(i);
			int ty = y + 3 + layout.y(i) - gridScroll;
			if (ty + TILE_H < y || ty > y + h) continue;
			boolean tileHover = inside(mx, my, tx, ty, layout.tileWidth, TILE_H) && inside(mx, my, x, y, w, h);
			float hv = Anim.approach(hoverOf(m.id()), tileHover ? 1f : 0f, dt, 0.05f);
			hover.put(m.id(), Float.valueOf(hv));
			tile(c, m, tx, ty, layout.tileWidth, TILE_H, tileHover, mx, my, dt);
		}
		hits.noClip();
		c.noScissor();

		if (layout.maxScroll > 0) scrollbar(c, x + w - 2, y, h, gridScroll, layout.maxScroll);
	}

	private void tile(Canvas c, final Module m, int x, int y, int w, int h, boolean hovered, int mx, int my, float dt) {
		Theme t = Theme.get();
		float hv = hoverOf(m.id());
		float progress = Anim.approach(hoverOf(m.id() + "#on"), m.isEnabled() ? 1f : 0f, dt, 0.05f);
		hover.put(m.id() + "#on", Float.valueOf(progress));

		// Eingeschaltete Kacheln stehen "unter Strom": Akzent-Kante und Leuchten.
		int fill = ColorMath.lerp(ColorMath.lerp(t.surface, t.surfaceHover, hv), ColorMath.lerp(ColorMath.lerp(t.surface, t.surfaceHover, hv), t.accent, 0.09f), progress);
		int edge = ColorMath.lerp(ColorMath.lerp(t.border, t.textDim, hv * 0.6f), ColorMath.lerp(t.border, t.accent, 0.75f + 0.25f * hv), progress);
		if (progress > 0.02f) Redstone.glow(c, x, y, w, h, t.glow, progress * (0.45f + 0.35f * hv));
		Redstone.stone(c, x, y, w, h, fill, edge);

		int iconColor = ColorMath.lerp(hovered ? t.text : t.textDim, t.dustOn, progress);
		Redstone.iconWell(c, x + 8, y + 8, 2, m.icon(), iconColor, progress);

		int tw = 24;
		int tx = x + w - tw - 8;
		int ty = y + 10;
		boolean toggleHover = inside(mx, my, tx - 3, ty - 3, tw + 6, 18);
		Paint.toggle(c, tx, ty, tw, 12, progress, toggleHover);

		// Name ungekürzt: notfalls in zwei Zeilen.
		boolean gear = !m.settings().isEmpty();
		int nameW = w - 16 - (gear ? 12 : 0);
		List<String> lines = Paint.wrap(c, m.name(), nameW);
		int nameColor = ColorMath.lerp(t.text, 0xFFFFFFFF, progress * 0.4f);
		if (lines.size() <= 1) {
			Paint.textClipped(c, m.name(), x + 8, y + h - 16, nameW, nameColor, false);
		} else {
			Paint.textClipped(c, lines.get(0), x + 8, y + h - 25, nameW, nameColor, false);
			Paint.textClipped(c, Paint.join(lines, 1), x + 8, y + h - 15, nameW, nameColor, false);
		}
		if (gear) Icons.draw(c, "gear", x + w - 16, y + h - 15, 1, hovered ? t.dustOn : ColorMath.withAlpha(t.textDim, 170));

		hits.add(x, y, w, h, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				selected = m;
				page = Page.SETTINGS;
				settingsScroll = 0;
				panel.reset();
			}
		});
		hits.add(tx - 3, ty - 3, tw + 6, 18, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				m.toggle();
			}
		});
	}

	/** Schmaler Rollbalken: unbestromter Staub, der sichtbare Teil leuchtet. */
	private static void scrollbar(Canvas c, int x, int y, int h, int scroll, int maxScroll) {
		Theme t = Theme.get();
		int barH = Math.max(14, h * h / (h + maxScroll));
		int barY = y + (h - barH) * scroll / maxScroll;
		c.fill(x, y, x + 2, y + h, t.dustOff);
		c.fill(x, barY, x + 2, barY + barH, t.dustOn);
	}

	private float hoverOf(String key) {
		Float v = hover.get(key);
		return v == null ? 0f : v.floatValue();
	}

	// --- Einstellungsseite ---

	private void settingsPage(Canvas c, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		if (selected == null) {
			page = Page.GRID;
			return;
		}
		final Module m = selected;
		float progress = Anim.approach(hoverOf(m.id() + "#on"), m.isEnabled() ? 1f : 0f, dt, 0.05f);
		hover.put(m.id() + "#on", Float.valueOf(progress));

		// Kopf: Zurück, Symbol, Name, Zurücksetzen, Schalter
		int headH = 22;
		boolean backHover = inside(mx, my, x, y + 2, 18, 18);
		Paint.iconButton(c, x, y + 2, 18, "back", backHover, false);
		hits.add(x, y + 2, 18, 18, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				page = Page.GRID;
				panel.reset();
			}
		});
		Redstone.iconWell(c, x + 24, y + 1, 2, m.icon(), ColorMath.lerp(t.textDim, t.dustOn, progress), progress);

		int tw = 26;
		int tx = x + w - tw - 2;
		boolean toggleHover = inside(mx, my, tx - 3, y + 2, tw + 6, 18);
		Paint.toggle(c, tx, y + 5, tw, 13, progress, toggleHover);
		hits.add(tx - 3, y + 2, tw + 6, 18, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				m.toggle();
			}
		});
		int resetX = tx - 26;
		boolean resetHover = inside(mx, my, resetX, y + 2, 18, 18);
		Paint.iconButton(c, resetX, y + 2, 18, "reset", resetHover, false);
		hits.add(resetX, y + 2, 18, 18, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				m.reset();
			}
		});
		int nameX = x + 52;
		c.text(c.clip(m.name(), resetX - nameX - 6), nameX, y + 4, t.text, false);
		String state = m.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled");
		c.text(c.clip(state + " · " + m.category().label(), resetX - nameX - 6), nameX, y + 14, m.isEnabled() ? t.dustOn : t.textDim, false);

		int top = y + headH + 8;
		c.fill(x, top - 4, x + w, top - 3, t.border);
		int bottom = y + h;
		List<MenuAction> actions = host.actions(m);
		if (!actions.isEmpty()) bottom -= 24;

		// Umhang-Physik: Live-Vorschau des eigenen Spielers – rechts daneben oder (schmal) darüber.
		int listX = x;
		int listW = w;
		int listTop = top;
		boolean preview = m == host.modules().capePhysics && host.playerPreviewState() != MenuHost.PREVIEW_UNSUPPORTED;
		if (preview) {
			if (w >= 260) {
				int pw = Math.max(100, Math.min(170, Math.round(w * 0.38f)));
				previewPanel(c, x + w - pw, top, pw, bottom - top, mx, my, dt);
				listW = w - pw - 8;
			} else {
				int ph = Math.min(130, Math.max(90, (bottom - top) / 2));
				previewPanel(c, x, top, w, ph, mx, my, dt);
				listTop = top + ph + 6;
			}
		}

		c.scissor(listX, listTop, listX + listW, bottom);
		hits.clip(listX, listTop, listW, bottom - listTop);
		int ry = listTop + 2 - settingsScroll;
		ry = Paint.paragraph(c, m.description(), listX + 2, ry, Math.min(listW - 8, 360), 10, t.textDim) + 6;
		ModulePanel extra = ModulePanel.Registry.of(m);
		if (extra != null) {
			ry = extra.draw(c, hits, listX + 2, ry, listW - 10, mx, my, new Runnable() {
				@Override
				public void run() {
					host.playClick();
				}
			});
		}
		List<Setting> settings = m.settings();
		if (settings.isEmpty() && extra != null) {
			// Nur der Zusatzbereich (z. B. FPS-Boost) – kein „keine Einstellungen“.
		} else if (settings.isEmpty()) {
			ry = Paint.paragraph(c, I18n.tr("menu.noSettings"), listX + 2, ry + 2, Math.min(listW - 8, 360), 10, t.textDim) + 4;
		} else {
			ry = panel.draw(c, hits, settings, listX + 2, ry, listW - 10, mx, my);
		}
		settingsHeight = ry + settingsScroll - listTop;
		hits.noClip();
		c.noScissor();
		c.flush();

		int maxScroll = Math.max(0, settingsHeight - (bottom - listTop));
		settingsScroll = Math.max(0, Math.min(settingsScroll, maxScroll));
		if (maxScroll > 0) scrollbar(c, listX + listW - 2, listTop, bottom - listTop, settingsScroll, maxScroll);

		if (actions.isEmpty()) return;
		c.fill(x, bottom + 3, x + w, bottom + 4, t.border);
		int bx = x;
		for (int i = 0; i < actions.size(); i++) {
			final MenuAction action = actions.get(i);
			int bw = Math.min(w - (bx - x), c.textWidth(action.label()) + 20);
			boolean hovered = inside(mx, my, bx, y + h - 17, bw, 17);
			Paint.button(c, bx, y + h - 17, bw, 17, action.label(), i == 0, hovered);
			hits.add(bx, y + h - 17, bw, 17, new Runnable() {
				@Override
				public void run() {
					host.playClick();
					host.save();
					action.run();
				}
			});
			bx += bw + 6;
		}
	}

	/**
	 * Vorschau-Feld: der eigene Spieler dreht sich langsam (Ziehen mit der Maus dreht von Hand), darunter
	 * ob er gerade „steht“ oder „geht“ und ein Knopf zum Zurücksetzen aller Umhang-Einstellungen.
	 */
	private void previewPanel(Canvas c, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		final Module m = selected;
		int buttonH = 17;
		int boxH = h - buttonH - 16;
		Redstone.well(c, x, y, w, boxH, t.border);
		long now = System.currentTimeMillis();
		boolean dragging = !Double.isNaN(previewDragX);
		// Nach 2 s ohne Bedienung dreht sich der Spieler wieder von selbst.
		if (!dragging && now - previewTouched > 2000) previewYaw = (previewYaw + dt * 30f) % 360f;
		int state = host.playerPreviewState();
		if (state == MenuHost.PREVIEW_OK) {
			c.flush();
			host.drawPlayerPreview(c, x + 1, y + 1, w - 2, boxH - 12, previewYaw);
			String caption = host.previewHasCape()
					? I18n.tr(host.previewWalking() ? "preview.walking" : "preview.standing")
					: I18n.tr("preview.noCape");
			Paint.textClipped(c, caption, x + 4, y + boxH - 11, w - 8, host.previewHasCape() ? t.textDim : t.dustOn, false);
			hits.addDrag(x, y, w, boxH, new Hits.Drag() {
				@Override
				public void to(double mouseX, double mouseY) {
					if (!Double.isNaN(previewDragX)) previewYaw = ((previewYaw + (float) (mouseX - previewDragX) * 2f) % 360f + 360f) % 360f;
					previewDragX = mouseX;
					previewTouched = System.currentTimeMillis();
				}
			});
		} else {
			List<String> lines = Paint.wrap(c, I18n.tr("preview.noPlayer"), w - 10);
			int ly = y + boxH / 2 - lines.size() * 5;
			for (int i = 0; i < lines.size(); i++) {
				Paint.textCentered(c, lines.get(i), x + w / 2, ly + i * 10, t.textDim, false);
			}
		}
		String label = I18n.tr("preview.reset");
		int by = y + boxH + 6;
		boolean hovered = inside(mx, my, x, by, w, buttonH);
		Paint.button(c, x, by, w, buttonH, label, false, hovered);
		hits.add(x, by, w, buttonH, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				boolean on = m.isEnabled();
				m.reset();
				m.setEnabled(on);
			}
		});
	}

	// --- Profile ---

	private void profilesPage(Canvas c, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		final HudProfiles profiles = host.modules().profiles;
		c.text(c.clip(I18n.tr("profiles.title"), w - 4), x + 2, y + 4, t.text, false);
		String keyLabel = host.profileKeyLabel();
		boolean unbound = keyLabel == null || keyLabel.isEmpty();
		if (!unbound) {
			String hint = I18n.tr("profiles.switches");
			int hw = c.textWidth(hint);
			Paint.textRight(c, hint, x + w, y + 4, t.textDim, false);
			int kw = Math.min(90, c.textWidth(keyLabel) + 8);
			Redstone.keycap(c, x + w - hw - 6 - kw, y + 1, keyLabel, kw);
		}

		int rowH = 22;
		int ry = y + 20;
		int listBottom = y + h - 26;
		for (int i = 0; i < profiles.size() && ry + rowH <= listBottom; i++) {
			final int index = i;
			boolean active = profiles.activeIndex() == i;
			boolean rowHover = inside(mx, my, x, ry, w, rowH - 3);
			if (active) {
				Redstone.glow(c, x, ry, w, rowH - 3, t.glow, 0.35f);
				Redstone.stone(c, x, ry, w, rowH - 3, ColorMath.lerp(t.surface, t.accent, 0.1f), ColorMath.lerp(t.border, t.accent, 0.75f));
			} else {
				Redstone.stone(c, x, ry, w, rowH - 3, rowHover ? t.surfaceHover : t.surface, t.border);
			}
			Redstone.pip(c, x + 7, ry + 6, 7, active ? 1f : 0f);
			if (editingProfile == index) {
				drawInput(c, x + 20, ry + 2, w - 80, mx, my);
			} else {
				Paint.textClipped(c, profiles.name(i), x + 20, ry + 6, w - 76, active ? t.text : t.textDim, false);
				hits.add(x, ry, w - 44, rowH - 3, new Runnable() {
					@Override
					public void run() {
						host.playClick();
						profiles.switchTo(index);
					}
				});
			}
			int bx = x + w - 19;
			boolean delHover = inside(mx, my, bx, ry + 2, 15, 15);
			Paint.iconButton(c, bx, ry + 2, 15, "trash", delHover, false);
			hits.add(bx, ry + 2, 15, 15, new Runnable() {
				@Override
				public void run() {
					host.playClick();
					if (!profiles.delete(index)) profileError = I18n.tr("profiles.error.last");
					else profileError = null;
					editingProfile = -1;
				}
			});
			bx -= 18;
			boolean editHover = inside(mx, my, bx, ry + 2, 15, 15);
			Paint.iconButton(c, bx, ry + 2, 15, "pencil", editHover, false);
			hits.add(bx, ry + 2, 15, 15, new Runnable() {
				@Override
				public void run() {
					host.playClick();
					editingProfile = index;
					profileError = null;
					nameInput.setText(profiles.name(index));
					nameInput.setFocused(true);
				}
			});
			ry += rowH;
		}

		if (editingProfile == -2) {
			drawInput(c, x, ry + 2, w - 70, mx, my);
			int okX = x + w - 64;
			boolean okHover = inside(mx, my, okX, ry + 1, 64, 17);
			Paint.button(c, okX, ry + 1, 64, 17, I18n.tr("common.create"), true, okHover);
			hits.add(okX, ry + 1, 64, 17, new Runnable() {
				@Override
				public void run() {
					confirmProfile();
				}
			});
			ry += 22;
		} else if (profiles.canCreate()) {
			String newLabel = I18n.tr("profiles.new");
			int bw = Math.min(w, c.textWidth(newLabel) + 30);
			boolean addHover = inside(mx, my, x, ry + 1, bw, 17);
			Paint.button(c, x, ry + 1, bw, 17, "", false, addHover);
			Icons.draw(c, "plus", x + 7, ry + 5, 1, addHover ? t.dustOn : t.text);
			Paint.textClipped(c, newLabel, x + 20, ry + 5 - (addHover ? 1 : 0), bw - 24, t.text, false);
			hits.add(x, ry + 1, bw, 17, new Runnable() {
				@Override
				public void run() {
					host.playClick();
					editingProfile = -2;
					profileError = null;
					nameInput.setText(profiles.suggestName());
					nameInput.setFocused(true);
				}
			});
			ry += 22;
		}

		String note = profileError != null ? profileError
				: I18n.tr("profiles.note") + (unbound ? " " + I18n.tr("profiles.noteKey") : "");
		List<String> lines = Paint.wrap(c, note, w - 4);
		int ly = y + h - 9 - (Math.min(3, lines.size()) - 1) * 10;
		for (int i = 0; i < Math.min(3, lines.size()); i++) {
			Paint.textClipped(c, lines.get(i), x + 2, ly + i * 10, w - 4, profileError != null ? t.dustOn : t.textDim, false);
		}
	}

	private void drawInput(Canvas c, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		Redstone.well(c, x, y, w, 15, t.accent);
		Paint.textClipped(c, nameInput.text(), x + 4, y + 4, w - 8, t.text, false);
		if ((System.currentTimeMillis() / 500) % 2 == 0) {
			int caret = x + 4 + c.textWidth(nameInput.text());
			c.fill(Math.min(caret, x + w - 3), y + 4, Math.min(caret + 1, x + w - 2), y + 12, t.dustOn);
		}
		hits.add(x, y, w, 15, new Runnable() {
			@Override
			public void run() {
				nameInput.setFocused(true);
			}
		});
	}

	private void confirmProfile() {
		HudProfiles profiles = host.modules().profiles;
		String name = nameInput.text();
		String error = editingProfile == -2 ? profiles.create(name) : profiles.rename(editingProfile, name);
		if (error != null) {
			profileError = error;
			return;
		}
		profileError = null;
		editingProfile = -1;
		nameInput.setFocused(false);
		host.playClick();
	}

	// --- Eingaben ---

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		boolean hit = super.mouseClicked(mouseX, mouseY, button);
		if (!hit && button == 0) {
			search.setFocused(false);
			if (panel.collapse()) return true;
		}
		return hit;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		previewDragX = Double.NaN;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!inside(mouseX, mouseY, contentRect[0], contentRect[1], contentRect[2], contentRect[3])) return false;
		int step = (int) Math.signum(amount) * (page == Page.SETTINGS ? 16 : TILE_H + TILE_GAP);
		if (page == Page.SETTINGS) settingsScroll = Math.max(0, settingsScroll - step);
		else if (page == Page.GRID) gridScroll = Math.max(0, gridScroll - step);
		return true;
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (panel.captureKey(rawKey, key, host)) return true;
		if (panel.typeKey(key)) return true;
		if (nameInput.focused() && editingProfile != -1) {
			if (key == UiKey.ENTER) {
				confirmProfile();
				return true;
			}
			if (key == UiKey.ESCAPE) {
				editingProfile = -1;
				profileError = null;
				nameInput.setFocused(false);
				return true;
			}
			if (nameInput.key(key)) return true;
		}
		if (search.focused()) {
			if (key == UiKey.ESCAPE) {
				if (!search.isEmpty()) search.clear();
				else search.setFocused(false);
				return true;
			}
			if (key == UiKey.ENTER) {
				search.setFocused(false);
				return true;
			}
			if (search.key(key)) return true;
		}
		if (key == UiKey.ESCAPE) {
			if (panel.collapse()) return true;
			if (page != Page.GRID) {
				page = Page.GRID;
				return true;
			}
			requestClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(char c) {
		if (panel.typeChar(c)) return true;
		if (nameInput.focused() && editingProfile != -1) return nameInput.type(c);
		if (search.focused()) {
			boolean typed = search.type(c);
			if (typed) gridScroll = 0;
			return typed;
		}
		// Tippen ohne Fokus landet in der Suche (wie bei Lunar).
		if (TextInput.allowed(c) && c != ' ') {
			search.setFocused(true);
			page = Page.GRID;
			gridScroll = 0;
			return search.type(c);
		}
		return false;
	}

	/** Für den Autotest: gerade sichtbares Modul der Einstellungsseite. */
	public Module selected() {
		return selected;
	}

	/** True, wenn das Menü gerade ein HUD-Modul zeigt (Sprung in den Editor). */
	public boolean showsHudModule() {
		return selected instanceof HudModule && page == Page.SETTINGS;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/** Klick mit Geräusch. */
	private final class Click implements Runnable {
		private final Runnable action;

		Click(Runnable action) {
			this.action = action;
		}

		@Override
		public void run() {
			host.playClick();
			action.run();
		}
	}
}
