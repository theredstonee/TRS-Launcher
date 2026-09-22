package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.compat.Keys;
import net.minecraft.network.chat.Component;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;

/**
 * Minecraft-Bildschirm für eine versionsunabhängige Oberfläche ({@link UiScreen}).
 * Hier steht nur noch das Weiterreichen von Zeichnen und Eingaben – das Aussehen und die
 * gesamte Bedienung liegen in {@code core.ui} und gelten damit für alle Minecraft-Versionen.
 */
public class TrsUiScreen extends TrsScreen {
	private final UiScreen ui;

	public TrsUiScreen(String title, UiScreen ui) {
		super(Component.literal(title));
		this.ui = ui;
	}

	public UiScreen ui() {
		return ui;
	}

	@Override
	protected void draw(Gfx g, int mouseX, int mouseY, float partialTick) {
		ui.render(GfxCanvas.of(g, font), width, height, mouseX, mouseY);
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
	protected boolean onKey(int key, int modifiers) {
		return ui.keyPressed(key, Keys.ui(key), shiftDown());
	}

	@Override
	protected boolean onChar(char c) {
		return ui.charTyped(c);
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
		return ui.pausesGame();
	}
}
