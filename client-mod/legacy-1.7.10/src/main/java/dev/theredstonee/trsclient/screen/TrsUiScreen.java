package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.ui.BrandCanvas;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

/**
 * Minecraft-Bildschirm für eine versionsunabhängige Oberfläche ({@link UiScreen}) unter 1.7.10.
 * GuiScreen meldet Taste und Zeichen zusammen (keyTyped) – beides wird hier getrennt weitergereicht.
 */
public class TrsUiScreen extends GuiScreen {
	private final UiScreen ui;
	private int lastMouseX;
	private int lastMouseY;
	private boolean dragging;

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
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		if (vanillaBackground()) drawDefaultBackground();
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		if (dragging && Mouse.isButtonDown(0)) ui.mouseDragged(mouseX, mouseY, 0);
		ui.render(BrandCanvas.of(fontRendererObj), width, height, mouseX, mouseY);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		dragging = button == 0;
		if (!ui.mouseClicked(mouseX, mouseY, button)) super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
		// state == -1: Maus bewegt (gedrückt), sonst die losgelassene Taste
		if (state >= 0) {
			dragging = false;
			ui.mouseReleased(mouseX, mouseY, state);
		}
		super.mouseMovedOrUp(mouseX, mouseY, state);
	}

	@Override
	public void handleMouseInput() {
		super.handleMouseInput();
		int wheel = Mouse.getEventDWheel();
		if (wheel == 0) return;
		int mx = Mouse.getEventX() * width / mc.displayWidth;
		int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
		ui.mouseScrolled(mx, my, wheel > 0 ? 1 : -1);
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) {
		UiKey logical = Keys.ui(keyCode);
		if (ui.keyPressed(keyCode, logical, isShiftKeyDown())) return;
		if (logical == UiKey.NONE && TextInput.allowed(typedChar) && ui.charTyped(typedChar)) return;
		if (keyCode == 1) { // Esc
			ui.requestClose();
			return;
		}
		super.keyTyped(typedChar, keyCode);
	}

	@Override
	public void onGuiClosed() {
		TrsClient.get().saveConfig();
	}

	@Override
	public boolean doesGuiPauseGame() {
		return ui.pausesGame();
	}

	protected int mouseX() {
		return lastMouseX;
	}

	protected int mouseY() {
		return lastMouseY;
	}
}
