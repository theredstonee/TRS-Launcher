package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.hud.HudLayout;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.hud.HudElement;
import dev.theredstonee.trsclient.hud.HudManager;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.SettingRows;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextFormatting;

/**
 * "HUD bearbeiten": aktive HUD-Module mit der Maus verschieben (rastet an Rändern und Mitte ein),
 * Mausrad ändert die Größe, Rechtsklick setzt die Position zurück, Shift = ohne Einrasten.
 */
public final class HudEditorScreen extends GuiScreen {
	private static final String HEADLINE = TextFormatting.BOLD + "HUD bearbeiten";
	private static final String HELP = "Ziehen · Mausrad: Größe · Rechtsklick: Reset · Shift: frei";

	private final GuiScreen parent;
	private final HudManager hud = TrsClient.get().hud();

	private HudElement dragging;
	private int grabX;
	private int grabY;
	private boolean guideX;
	private boolean guideY;

	public HudEditorScreen(GuiScreen parent) {
		this.parent = parent;
	}

	@Override
	public void render(int mouseX, int mouseY, float partialTicks) {
		// Kein Abdunkeln wie in Menüs – das Spiel soll sichtbar bleiben.
		if (mc.world == null) drawBackground(0);
		Brand.rect(0, 0, width, height, 0x40000000);

		// Hilfslinien Bildschirmmitte
		Brand.rect(width / 2, 0, 1, height, dragging != null && guideX ? Brand.RED : 0x22FFFFFF);
		Brand.rect(0, height / 2, width, 1, dragging != null && guideY ? Brand.RED : 0x22FFFFFF);

		HudElement hovered = dragging != null ? dragging : elementAt(mouseX, mouseY);
		for (HudElement e : hud.elements()) {
			if (!e.module().isEnabled()) continue;
			hud.draw(fontRenderer, e, width, height, true);
			int[] b = hud.bounds(fontRenderer, e, width, height, true);
			int color = e == dragging ? Brand.AMBER : (e == hovered ? 0xE0FFFFFF : 0x70FFFFFF);
			Brand.outline(b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, color);
			if (e == hovered) {
				String name = e.module().name() + " · " + e.module().scale.display();
				int ly = b[1] - 11 >= 0 ? b[1] - 11 : b[1] + b[3] + 3;
				Brand.text(fontRenderer, name, b[0], ly, Brand.AMBER, true);
			}
		}

		// Hinweis-Box in der Mitte (HUD-Elemente liegen meist am Rand)
		int boxW = Math.min(width - 16, Math.max(fontRenderer.getStringWidth(HELP), fontRenderer.getStringWidth(HEADLINE)) + 16);
		int bx = (width - boxW) / 2;
		int by = height / 2 - 34;
		Brand.rect(bx, by, boxW, 26, 0xD017171E);
		Brand.rect(bx, by, 2, 26, Brand.RED);
		Brand.centered(fontRenderer, HEADLINE, width / 2, by + 4, Brand.TEXT, true);
		Brand.centered(fontRenderer, fontRenderer.trimStringToWidth(HELP, boxW - 8), width / 2, by + 15, Brand.TEXT_DIM, true);

		// Fertig-Knopf darunter
		int fw = 60;
		int fx = (width - fw) / 2;
		int fy = doneY();
		Brand.button(fontRenderer, fx, fy, fw, 16, "Fertig", true, SettingRows.inside(mouseX, mouseY, fx, fy, fw, 16));
	}

	@Override
	public boolean mouseClicked(double mouseXd, double mouseYd, int button) {
		int mouseX = (int) mouseXd;
		int mouseY = (int) mouseYd;
		int fw = 60;
		int fx = (width - fw) / 2;
		if (button == 0 && SettingRows.inside(mouseX, mouseY, fx, doneY(), fw, 16)) {
			close();
			return true;
		}
		HudElement e = elementAt(mouseX, mouseY);
		if (e == null) return super.mouseClicked(mouseXd, mouseYd, button);
		if (button == 1) {
			e.module().resetPosition();
		} else if (button == 0) {
			int[] b = hud.bounds(fontRenderer, e, width, height, true);
			dragging = e;
			grabX = mouseX - b[0];
			grabY = mouseY - b[1];
		}
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseXd, double mouseYd, int button, double dragX, double dragY) {
		if (dragging == null || button != 0) return false;
		int[] b = hud.bounds(fontRenderer, dragging, width, height, true);
		int w = b[2];
		int h = b[3];
		int x = (int) mouseXd - grabX;
		int y = (int) mouseYd - grabY;
		if (isShiftKeyDown()) {
			x = HudLayout.clamp(x, 0, Math.max(0, width - w));
			y = HudLayout.clamp(y, 0, Math.max(0, height - h));
		} else {
			x = HudLayout.snapX(x, w, width);
			y = HudLayout.snapY(y, h, height);
		}
		guideX = HudLayout.isCentered(x, w, width);
		guideY = HudLayout.isCentered(y, h, height);
		dragging.module().position().set(HudLayout.fromPixels(x, y, w, h, width, height));
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == 0) dragging = null;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	/** 1.13.2: Mausrad ohne Position – die Position kommt aus dem MouseHelper. */
	@Override
	public boolean mouseScrolled(double delta) {
		if (delta == 0) return false;
		HudElement e = elementAt(TrsMenuScreen.mouseGuiX(this), TrsMenuScreen.mouseGuiY(this));
		if (e == null) return false;
		e.module().scale.nudge(delta > 0 ? 1 : -1);
		return true;
	}
	private int doneY() {
		return height / 2 - 2;
	}

	/** Oberstes aktives Element unter der Maus (zuletzt gezeichnet = oben). */
	private HudElement elementAt(int mx, int my) {
		HudElement found = null;
		for (HudElement e : hud.elements()) {
			HudModule m = e.module();
			if (!m.isEnabled()) continue;
			int[] b = hud.bounds(fontRenderer, e, width, height, true);
			if (SettingRows.inside(mx, my, b[0], b[1], b[2], b[3])) found = e;
		}
		return found;
	}

	/** Zurück zum vorherigen Bildschirm (auch bei Esc). */
	@Override
	public void close() {
		mc.displayGuiScreen(parent);
	}

	@Override
	public void onGuiClosed() {
		TrsClient.get().saveConfig();
	}

	@Override
	public boolean doesGuiPauseGame() {
		return false;
	}
}
