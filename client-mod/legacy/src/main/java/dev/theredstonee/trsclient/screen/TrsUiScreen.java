package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;

/**
 * Minecraft-Bildschirm für eine versionsunabhängige Oberfläche ({@link UiScreen}) unter
 * Minecraft 1.8.9–1.12.2. GuiScreen meldet Taste und Zeichen zusammen (keyTyped) – beides
 * wird hier getrennt weitergereicht.
 */
public class TrsUiScreen extends TrsScreen {
	private final String title;
	private final UiScreen ui;

	public TrsUiScreen(String title, UiScreen ui) {
		this.title = title;
		this.ui = ui;
	}

	public UiScreen ui() {
		return ui;
	}

	public String title() {
		return title;
	}

	@Override
	protected void draw(Gfx g, int mouseX, int mouseY, float partialTick) {
		ui.render(GfxCanvas.of(g, font), width, height, mouseX, mouseY);
	}

	/** Einblendung über dem Spiel (Schnellantwort): kein abgedunkelter Hintergrund. */
	@Override
	protected boolean customBackground() {
		return ui.overlay();
	}

	@Override
	protected boolean onClick(double mouseX, double mouseY, int button) {
		return ui.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	protected boolean onRelease(double mouseX, double mouseY, int button) {
		return ui.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	protected boolean onDrag(double mouseX, double mouseY, int button) {
		return ui.mouseDragged(mouseX, mouseY, button);
	}

	@Override
	protected boolean onScroll(double mouseX, double mouseY, double amount) {
		return ui.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	protected boolean onKey(int key, char typed) {
		UiKey logical = Keys.ui(key);
		// Strg+V / Strg+A (Cmd unter macOS) für Textfelder.
		if (isCtrlKeyDown() && key == org.lwjgl.input.Keyboard.KEY_V) logical = UiKey.PASTE;
		else if (isCtrlKeyDown() && key == org.lwjgl.input.Keyboard.KEY_A) logical = UiKey.SELECT_ALL;
		boolean used = ui.keyPressed(key, logical, shiftDown());
		if (!used && logical == UiKey.NONE && TextInput.allowed(typed)) used = ui.charTyped(typed);
		return used;
	}

	@Override
	public void onClose() {
		ui.requestClose();
	}

	@Override
	public void removed() {
		save();
	}

	@Override
	public boolean isPauseScreen() {
		return ui.pausesGame() && !ui.overlay();
	}
}
