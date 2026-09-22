package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.hud.HudLayout;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.hud.HudElement;
import dev.theredstonee.trsclient.hud.HudManager;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.SettingRows;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Mouse;

/**
 * "HUD bearbeiten": aktive HUD-Module mit der Maus verschieben (rastet an Rändern und Mitte ein),
 * Mausrad ändert die Größe, Rechtsklick setzt die Position zurück, Shift = ohne Einrasten.
 */
public final class HudEditorScreen extends GuiScreen {
	private static final String HEADLINE = EnumChatFormatting.BOLD + "HUD bearbeiten";
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
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		// Kein Abdunkeln wie in Menüs – das Spiel soll sichtbar bleiben.
		if (mc.theWorld == null) drawBackground(0);
		Brand.rect(0, 0, width, height, 0x40000000);

		// Hilfslinien Bildschirmmitte
		Brand.rect(width / 2, 0, 1, height, dragging != null && guideX ? Brand.RED : 0x22FFFFFF);
		Brand.rect(0, height / 2, width, 1, dragging != null && guideY ? Brand.RED : 0x22FFFFFF);

		HudElement hovered = dragging != null ? dragging : elementAt(mouseX, mouseY);
		for (HudElement e : hud.elements()) {
			if (!e.module().isEnabled()) continue;
			hud.draw(fontRendererObj, e, width, height, true);
			int[] b = hud.bounds(fontRendererObj, e, width, height, true);
			int color = e == dragging ? Brand.AMBER : (e == hovered ? 0xE0FFFFFF : 0x70FFFFFF);
			Brand.outline(b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, color);
			if (e == hovered) {
				String name = e.module().name() + " · " + e.module().scale.display();
				int ly = b[1] - 11 >= 0 ? b[1] - 11 : b[1] + b[3] + 3;
				Brand.text(fontRendererObj, name, b[0], ly, Brand.AMBER, true);
			}
		}

		// Hinweis-Box in der Mitte (HUD-Elemente liegen meist am Rand)
		int boxW = Math.min(width - 16, Math.max(fontRendererObj.getStringWidth(HELP), fontRendererObj.getStringWidth(HEADLINE)) + 16);
		int bx = (width - boxW) / 2;
		int by = height / 2 - 34;
		Brand.rect(bx, by, boxW, 26, 0xD017171E);
		Brand.rect(bx, by, 2, 26, Brand.RED);
		Brand.centered(fontRendererObj, HEADLINE, width / 2, by + 4, Brand.TEXT, true);
		Brand.centered(fontRendererObj, fontRendererObj.trimStringToWidth(HELP, boxW - 8), width / 2, by + 15, Brand.TEXT_DIM, true);

		// Fertig-Knopf darunter
		int fw = 60;
		int fx = (width - fw) / 2;
		int fy = doneY();
		Brand.button(fontRendererObj, fx, fy, fw, 16, "Fertig", true, SettingRows.inside(mouseX, mouseY, fx, fy, fw, 16));
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		int fw = 60;
		int fx = (width - fw) / 2;
		if (button == 0 && SettingRows.inside(mouseX, mouseY, fx, doneY(), fw, 16)) {
			close();
			return;
		}
		HudElement e = elementAt(mouseX, mouseY);
		if (e == null) {
			super.mouseClicked(mouseX, mouseY, button);
			return;
		}
		if (button == 1) {
			e.module().resetPosition();
		} else if (button == 0) {
			int[] b = hud.bounds(fontRendererObj, e, width, height, true);
			dragging = e;
			grabX = mouseX - b[0];
			grabY = mouseY - b[1];
		}
	}

	@Override
	protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
		if (dragging == null || button != 0) return;
		int[] b = hud.bounds(fontRendererObj, dragging, width, height, true);
		int w = b[2];
		int h = b[3];
		int x = mouseX - grabX;
		int y = mouseY - grabY;
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
	}

	/** 1.7.10: {@code state} = -1 bei Mausbewegung, sonst die losgelassene Taste. */
	@Override
	protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
		if (state == 0) dragging = null;
		super.mouseMovedOrUp(mouseX, mouseY, state);
	}

	@Override
	public void handleMouseInput() {
		super.handleMouseInput();
		int wheel = Mouse.getEventDWheel();
		if (wheel == 0) return;
		int mx = Mouse.getEventX() * width / mc.displayWidth;
		int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
		HudElement e = elementAt(mx, my);
		if (e != null) e.module().scale.nudge(wheel > 0 ? 1 : -1);
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) {
		if (keyCode == 1) { // Esc
			close();
			return;
		}
		super.keyTyped(typedChar, keyCode);
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
			int[] b = hud.bounds(fontRendererObj, e, width, height, true);
			if (SettingRows.inside(mx, my, b[0], b[1], b[2], b[3])) found = e;
		}
		return found;
	}

	private void close() {
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
