package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.ui.BrandCanvas;
import net.minecraft.client.gui.GuiScreen;

/**
 * Minecraft-Bildschirm für eine versionsunabhängige Oberfläche ({@link UiScreen}) unter 1.13.2.
 * Das Mausrad kommt hier ohne Position – die wird aus dem MouseHelper nachgeschlagen.
 */
public class TrsUiScreen extends GuiScreen {
	private final UiScreen ui;

	public TrsUiScreen(UiScreen ui) {
		this.ui = ui;
	}

	public UiScreen ui() {
		return ui;
	}

	/** Vanilla-Hintergrund (abgedunkelt bzw. Erde) zeichnen? Der HUD-Editor macht das selbst. */
	protected boolean vanillaBackground() {
		return true;
	}

	@Override
	public void render(int mouseX, int mouseY, float partialTicks) {
		if (vanillaBackground()) drawDefaultBackground();
		ui.render(BrandCanvas.of(fontRenderer), width, height, mouseX, mouseY);
		super.render(mouseX, mouseY, partialTicks);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return ui.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return ui.mouseReleased(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		return ui.mouseDragged(mouseX, mouseY, button) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double delta) {
		if (delta == 0) return false;
		return ui.mouseScrolled(mouseGuiX(), mouseGuiY(), delta) || super.mouseScrolled(delta);
	}

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		if (ui.keyPressed(key, Keys.ui(key), isShiftKeyDown())) return true;
		// Esc: erst die Schließ-Animation, dann schließt der Bildschirm sich selbst.
		if (key == Keys.code("key.keyboard.escape")) {
			ui.requestClose();
			return true;
		}
		return super.keyPressed(key, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char typed, int modifiers) {
		return (TextInput.allowed(typed) && ui.charTyped(typed)) || super.charTyped(typed, modifiers);
	}

	@Override
	public void onGuiClosed() {
		TrsClient.get().saveConfig();
	}

	@Override
	public boolean doesGuiPauseGame() {
		return ui.pausesGame();
	}

	/** Mausposition in GUI-Koordinaten (Mausrad-Ereignisse liefern sie in 1.13.2 nicht mit). */
	private int mouseGuiX() {
		return (int) (mc.mouseHelper.getMouseX() * mc.mainWindow.getScaledWidth() / mc.mainWindow.getWidth());
	}

	private int mouseGuiY() {
		return (int) (mc.mouseHelper.getMouseY() * mc.mainWindow.getScaledHeight() / mc.mainWindow.getHeight());
	}
}
