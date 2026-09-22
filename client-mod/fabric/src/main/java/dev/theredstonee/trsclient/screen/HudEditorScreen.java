package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.hud.HudLayout;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.hud.HudElement;
import dev.theredstonee.trsclient.hud.HudManager;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else
import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
*///?}

/**
 * "HUD bearbeiten": aktive HUD-Module mit der Maus verschieben (rastet an Rändern und Mitte ein),
 * Mausrad ändert die Größe, Rechtsklick setzt die Position zurück, Shift = ohne Einrasten.
 */
public final class HudEditorScreen extends Screen {
	private static final Component HEADLINE = Component.literal("HUD bearbeiten").withStyle(ChatFormatting.BOLD);
	private static final String HELP = "Ziehen · Mausrad: Größe · Rechtsklick: Reset · Shift: frei";

	private final Screen parent;
	private final HudManager hud = TrsClient.get().hud();

	private HudElement dragging;
	private double grabX;
	private double grabY;
	private boolean guideX;
	private boolean guideY;

	public HudEditorScreen(Screen parent) {
		super(Component.literal("HUD bearbeiten"));
		this.parent = parent;
	}

	// --- Versionsabhängige Einstiegspunkte (Zeichnen/Eingabe) → neutrale Methoden unten ---

	//? if >=26.1 {
	/*@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// Kein Weichzeichner – das Spiel soll sichtbar bleiben.
		if (minecraft.level == null) extractPanorama(g, partialTick);
		g.fill(0, 0, width, height, 0x40000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		draw(Gfx.of(g), mouseX, mouseY);
	}
	*///?} else {
	@Override
	//? if >=1.20.2 {
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
	//?} else
	/*public void renderBackground(GuiGraphics g) {*/
		// Kein Weichzeichner – das Spiel soll sichtbar bleiben.
		if (minecraft.level == null) {
			//? if >=1.20.5 {
			renderPanorama(g, partialTick);
			//?} else
			/*g.fill(0, 0, width, height, Brand.BG);*/
		}
		g.fill(0, 0, width, height, 0x40000000);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		//? if <1.20.2
		/*renderBackground(g);*/
		super.render(g, mouseX, mouseY, partialTick);
		draw(Gfx.of(g), mouseX, mouseY);
	}
	//?}

	//? if >=1.21.9 {
	/*@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return onClick(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return onDrag(event.x(), event.y(), event.button()) || super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return onRelease(event.button()) || super.mouseReleased(event);
	}
	*///?} else {
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return onClick(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		return onDrag(mouseX, mouseY, button) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return onRelease(button) || super.mouseReleased(mouseX, mouseY, button);
	}
	//?}

	//? if >=1.20.2 {
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return onScroll(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
	//?} else {
	/*@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		return onScroll(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollY);
	}
	*///?}

	private boolean shiftDown() {
		//? if >=1.21.9 {
		/*return minecraft.hasShiftDown();
		*///?} else
		return hasShiftDown();
	}

	// --- Neutrale Logik ---

	private void draw(Gfx g, int mouseX, int mouseY) {
		// Hilfslinien Bildschirmmitte
		g.vLine(width / 2, -1, height, dragging != null && guideX ? Brand.RED : 0x22FFFFFF);
		g.hLine(0, width, height / 2, dragging != null && guideY ? Brand.RED : 0x22FFFFFF);

		HudElement hovered = dragging != null ? dragging : elementAt(mouseX, mouseY);
		for (HudElement e : hud.elements()) {
			if (!e.module().isEnabled()) continue;
			hud.draw(g, font, e, width, height, true);
			int[] b = hud.bounds(font, e, width, height, true);
			int color = e == dragging ? Brand.AMBER : (e == hovered ? 0xE0FFFFFF : 0x70FFFFFF);
			Brand.outline(g, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, color);
			if (e == hovered) {
				String name = e.module().name() + " · " + e.module().scale.display();
				int ly = b[1] - 11 >= 0 ? b[1] - 11 : b[1] + b[3] + 3;
				g.text(font, name, b[0], ly, Brand.AMBER, true);
			}
		}

		// Hinweis-Box in der Mitte (HUD-Elemente liegen meist am Rand)
		int boxW = Math.min(width - 16, Math.max(font.width(HELP), font.width(HEADLINE)) + 16);
		int bx = (width - boxW) / 2;
		int by = height / 2 - 34;
		g.fill(bx, by, bx + boxW, by + 26, 0xD017171E);
		g.fill(bx, by, bx + 2, by + 26, Brand.RED);
		g.centered(font, HEADLINE, width / 2, by + 4, Brand.TEXT);
		g.centered(font, font.plainSubstrByWidth(HELP, boxW - 8), width / 2, by + 15, Brand.TEXT_DIM);

		// Fertig-Knopf darunter
		int fw = 60;
		int fx = (width - fw) / 2;
		int fy = doneY();
		Brand.button(g, font, fx, fy, fw, 16, "Fertig", true, inside(mouseX, mouseY, fx, fy, fw, 16));
	}

	private boolean onClick(double mouseX, double mouseY, int button) {
		int fw = 60;
		int fx = (width - fw) / 2;
		if (button == 0 && inside(mouseX, mouseY, fx, doneY(), fw, 16)) {
			onClose();
			return true;
		}
		HudElement e = elementAt(mouseX, mouseY);
		if (e == null) return false;
		if (button == 1) {
			e.module().resetPosition();
			return true;
		}
		if (button == 0) {
			int[] b = hud.bounds(font, e, width, height, true);
			dragging = e;
			grabX = mouseX - b[0];
			grabY = mouseY - b[1];
			return true;
		}
		return false;
	}

	private boolean onDrag(double mouseX, double mouseY, int button) {
		if (dragging == null || button != 0) return false;
		int[] b = hud.bounds(font, dragging, width, height, true);
		int w = b[2];
		int h = b[3];
		int x = (int) Math.round(mouseX - grabX);
		int y = (int) Math.round(mouseY - grabY);
		if (shiftDown()) {
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

	private boolean onRelease(int button) {
		if (button == 0 && dragging != null) {
			dragging = null;
			return true;
		}
		return false;
	}

	private boolean onScroll(double mouseX, double mouseY, double scrollY) {
		HudElement e = elementAt(mouseX, mouseY);
		if (e == null || scrollY == 0) return false;
		e.module().scale.nudge(scrollY > 0 ? 1 : -1);
		return true;
	}

	private int doneY() {
		return height / 2 - 2;
	}

	/** Oberstes aktives Element unter der Maus (zuletzt gezeichnet = oben). */
	private HudElement elementAt(double mx, double my) {
		HudElement found = null;
		for (HudElement e : hud.elements()) {
			HudModule m = e.module();
			if (!m.isEnabled()) continue;
			int[] b = hud.bounds(font, e, width, height, true);
			if (inside(mx, my, b[0], b[1], b[2], b[3])) found = e;
		}
		return found;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	@Override
	public void onClose() {
		Mc.setScreen(parent);
	}

	@Override
	public void removed() {
		TrsClient.get().saveConfig();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
