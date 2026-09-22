package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.hud.HudLayout;
import dev.theredstonee.trsclient.core.hud.HudProfiles;
import dev.theredstonee.trsclient.core.hud.HudSnap;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Setting;
import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD-Editor: Elemente mit der Maus verschieben (rastet an Bildschirmrändern, Bildschirmmitte und
 * den anderen Elementen ein – mit Hilfslinien), Größe, Hintergrund-Deckkraft, Textschatten und
 * Chroma je Modul einstellen, Profile wechseln. Shift = frei schieben, Rechtsklick = zurücksetzen.
 */
public final class HudEditor extends UiScreen {
	private static final int PANEL_W = 174;
	private static final String HINT = "Ziehen · Mausrad: Größe · Rechtsklick: Reset · Shift: frei";

	private final MenuHost host;
	private final SettingsPanel panel = new SettingsPanel();

	private HudItem dragging;
	private HudItem selected;
	private double grabX;
	private double grabY;
	private int guideX = HudSnap.NO_GUIDE;
	private int guideY = HudSnap.NO_GUIDE;
	private float panelIn;

	public HudEditor(MenuHost host) {
		this.host = host;
		panel.setKeyLabel(new SettingsPanel.KeyLabel() {
			@Override
			public String label(String keyName) {
				return HudEditor.this.host.keyLabel(keyName);
			}
		});
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
		setScreenSize(width, height);
		Canvas c = FadeCanvas.of(raw, alpha());
		if (!host.inWorld()) c.fill(0, 0, width, height, t.background);
		c.fill(0, 0, width, height, ColorMath.withAlpha(t.background, 110));

		// Hilfslinien: Bildschirmmitte immer dezent, aktive Einrastung in Akzentfarbe
		c.fill(width / 2, 0, width / 2 + 1, height, ColorMath.withAlpha(t.text, 26));
		c.fill(0, height / 2, width, height / 2 + 1, ColorMath.withAlpha(t.text, 26));
		if (dragging != null && guideX != HudSnap.NO_GUIDE) c.fill(guideX, 0, guideX + 1, height, t.accent);
		if (dragging != null && guideY != HudSnap.NO_GUIDE) c.fill(0, guideY, width, guideY + 1, t.accent);

		List<HudItem> items = enabled();
		HudItem hovered = dragging != null ? dragging : itemAt(mouseX, mouseY, width, height);
		for (int i = 0; i < items.size(); i++) {
			HudItem item = items.get(i);
			int[] b = bounds(item, width, height);
			c.push();
			c.translate(b[0], b[1]);
			c.scale(item.module().scale.getFloat());
			item.draw(c);
			c.pop();
			int color = item == dragging ? t.accent : (item == selected ? t.on : (item == hovered ? 0xE0FFFFFF : 0x60FFFFFF));
			Paint.roundOutline(c, b[0] - 2, b[1] - 2, b[2] + 4, b[3] + 4, 3, color);
			if (item == hovered || item == selected) {
				String label = item.module().name();
				int ly = b[1] - 11 >= 0 ? b[1] - 11 : b[1] + b[3] + 3;
				Paint.textClipped(c, label, b[0] - 1, ly, Math.max(40, b[2] + 40), t.text, true);
			}
			// Klickflächen für die Elemente gibt es nicht: ein Klick zieht sie (siehe mouseClicked).
		}

		// Leiste und Feld liegen über den Vorschauen (Text hat in Minecraft eine eigene Tiefe).
		c.flush();
		c.push();
		c.raise(300f);
		topBar(c, width, mouseX, mouseY);
		panelIn = Anim.approach(panelIn, selected != null ? 1f : 0f, dt, 0.07f);
		if (panelIn > 0.02f) sidePanel(c, width, height, mouseX, mouseY);
		c.pop();
	}

	private void topBar(Canvas c, int width, int mx, int my) {
		Theme t = Theme.get();
		int h = 19;
		Paint.roundRect(c, 0, -6, width, h + 6, 6, ColorMath.withAlpha(t.background, 215));
		c.fill(0, h, width, h + 1, t.border);
		c.fill(8, 4, 10, 15, t.accent);
		c.text("HUD bearbeiten", 15, 6, t.text, false);

		// Profilwechsel
		final HudProfiles profiles = host.modules().profiles;
		String label = "Profil: " + profiles.activeName();
		int pw = Math.min(140, c.textWidth(label) + 16);
		int px = 15 + c.textWidth("HUD bearbeiten") + 10;
		boolean pHover = inside(mx, my, px, 4, pw, 14);
		Paint.roundRect(c, px, 3, pw, 14, 3, pHover ? t.surfaceHover : t.surfaceHigh);
		Paint.roundOutline(c, px, 3, pw, 14, 3, t.border);
		Paint.textCentered(c, c.clip(label, pw - 6), px + pw / 2, 7, t.text, false);
		hits.add(px, 3, pw, 14, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				profiles.cycle();
				selected = null;
			}
		});

		int doneW = 54;
		int doneX = width - doneW - 8;
		boolean doneHover = inside(mx, my, doneX, 3, doneW, 14);
		Paint.button(c, doneX, 3, doneW, 14, "Fertig", true, doneHover);
		hits.add(doneX, 3, doneW, 14, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				requestClose();
			}
		});
		int menuW = 56;
		int menuX = doneX - menuW - 5;
		boolean menuHover = inside(mx, my, menuX, 3, menuW, 14);
		Paint.button(c, menuX, 3, menuW, 14, "Menü", false, menuHover);
		hits.add(menuX, 3, menuW, 14, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				host.save();
				host.openMenu();
			}
		});

		Paint.textRight(c, c.clip(HINT, Math.max(0, menuX - px - pw - 12)), menuX - 8, 6, t.textDim, false);
	}

	private void sidePanel(Canvas c, int width, int height, int mx, int my) {
		Theme t = Theme.get();
		final HudItem item = selected;
		if (item == null) return;
		HudModule module = item.module();
		List<Setting> settings = new ArrayList<Setting>();
		settings.add(module.scale);
		settings.add(module.backgroundOpacity);
		settings.add(module.textShadow);
		settings.add(module.textColor);

		int h = 40 + panel.height(settings) + 20;
		int x = width - PANEL_W - 8 + Math.round((1 - Anim.easeOut(panelIn)) * 20);
		int y = Math.min(34, Math.max(28, height - h - 8));
		Paint.shadow(c, x, y, PANEL_W, h, 5, alpha());
		Paint.roundRect(c, x, y, PANEL_W, h, 5, t.background);
		Paint.roundOutline(c, x, y, PANEL_W, h, 5, t.border);

		Icons.draw(c, module.icon(), x + 8, y + 7, 1, t.accent);
		Paint.textClipped(c, module.name(), x + 19, y + 7, PANEL_W - 46, t.text, false);
		boolean closeHover = inside(mx, my, x + PANEL_W - 20, y + 5, 12, 12);
		Paint.iconButton(c, x + PANEL_W - 20, y + 5, 12, "close", closeHover, false);
		hits.add(x + PANEL_W - 20, y + 5, 12, 12, new Runnable() {
			@Override
			public void run() {
				selected = null;
			}
		});
		c.fill(x + 6, y + 20, x + PANEL_W - 6, y + 21, t.border);

		int ry = y + 25;
		ry = panel.draw(c, hits, settings, x + 8, ry, PANEL_W - 16, mx, my);

		boolean resetHover = inside(mx, my, x + 8, ry + 2, PANEL_W - 16, 14);
		Paint.button(c, x + 8, ry + 2, PANEL_W - 16, 14, "Zurücksetzen", false, resetHover);
		final HudModule resetTarget = module;
		hits.add(x + 8, ry + 2, PANEL_W - 16, 14, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				resetTarget.resetLayout();
			}
		});
	}

	// --- Eingaben ---

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 1 && !hits.hovers(mouseX, mouseY)) {
			HudItem item = itemAt(mouseX, mouseY, lastWidth, lastHeight);
			if (item != null) {
				item.module().resetLayout();
				return true;
			}
		}
		boolean hit = super.mouseClicked(mouseX, mouseY, button);
		// Nur ziehen, wenn der Klick nicht schon auf der Leiste oder im Einstellungsfeld gelandet ist.
		if (button == 0 && !hit) {
			HudItem item = itemAt(mouseX, mouseY, lastWidth, lastHeight);
			if (item != null && !hits.dragging()) {
				int[] b = bounds(item, lastWidth, lastHeight);
				dragging = item;
				selected = item;
				grabX = mouseX - b[0];
				grabY = mouseY - b[1];
				return true;
			}
		}
		return hit;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (dragging == null) return super.mouseDragged(mouseX, mouseY, button);
		int[] b = bounds(dragging, lastWidth, lastHeight);
		int w = b[2];
		int h = b[3];
		int x = (int) Math.round(mouseX - grabX);
		int y = (int) Math.round(mouseY - grabY);
		HudSnap.Result result = host.shiftDown()
				? HudSnap.clampOnly(x, y, w, h, lastWidth, lastHeight)
				: HudSnap.snap(x, y, w, h, lastWidth, lastHeight, others(dragging), HudSnap.DISTANCE);
		guideX = result.guideX;
		guideY = result.guideY;
		dragging.module().position().set(HudLayout.fromPixels(result.x, result.y, w, h, lastWidth, lastHeight));
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		boolean was = dragging != null;
		dragging = null;
		guideX = HudSnap.NO_GUIDE;
		guideY = HudSnap.NO_GUIDE;
		return super.mouseReleased(mouseX, mouseY, button) || was;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		// Über der Leiste oder dem Einstellungsfeld ändert das Mausrad nichts am Element darunter.
		if (hits.hovers(mouseX, mouseY)) return false;
		HudItem item = itemAt(mouseX, mouseY, lastWidth, lastHeight);
		if (item == null) return false;
		item.module().scale.nudge(amount > 0 ? 1 : -1);
		selected = item;
		return true;
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (panel.captureKey(rawKey, key, host)) return true;
		if (key == UiKey.ESCAPE) {
			if (panel.collapse()) return true;
			if (selected != null) {
				selected = null;
				return true;
			}
			requestClose();
			return true;
		}
		return false;
	}

	/** Wählt das erste aktive Element aus (Selbsttest: zeigt das Einstellungsfeld). */
	public void selectFirst() {
		List<HudItem> items = enabled();
		if (!items.isEmpty()) {
			selected = items.get(0);
			panelIn = 1f;
		}
	}

	// --- Hilfen ---

	private int lastWidth;
	private int lastHeight;

	private List<HudItem> enabled() {
		List<HudItem> out = new ArrayList<HudItem>();
		List<HudItem> all = host.hudItems();
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).module().isEnabled()) out.add(all.get(i));
		}
		return out;
	}

	private List<int[]> others(HudItem except) {
		List<int[]> out = new ArrayList<int[]>();
		List<HudItem> items = enabled();
		for (int i = 0; i < items.size(); i++) {
			HudItem item = items.get(i);
			if (item == except) continue;
			out.add(bounds(item, lastWidth, lastHeight).clone());
		}
		return out;
	}

	/** {x, y, Breite, Höhe} des Elements auf dem Bildschirm. */
	private int[] bounds(HudItem item, int screenW, int screenH) {
		float scale = item.module().scale.getFloat();
		int w = (int) Math.ceil(item.width() * scale);
		int h = (int) Math.ceil(item.height() * scale);
		int x = HudLayout.resolveX(item.module().position(), w, screenW);
		int y = HudLayout.resolveY(item.module().position(), h, screenH);
		return new int[]{x, y, w, h};
	}

	/** Oberstes Element unter der Maus. */
	private HudItem itemAt(double mx, double my, int screenW, int screenH) {
		HudItem found = null;
		List<HudItem> items = enabled();
		for (int i = 0; i < items.size(); i++) {
			int[] b = bounds(items.get(i), screenW, screenH);
			if (inside(mx, my, b[0] - 2, b[1] - 2, b[2] + 4, b[3] + 4)) found = items.get(i);
		}
		return found;
	}

	/** Merkt sich die Bildschirmgröße für die Eingaben (die kommen ohne Größe). */
	public void setScreenSize(int width, int height) {
		lastWidth = width;
		lastHeight = height;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
