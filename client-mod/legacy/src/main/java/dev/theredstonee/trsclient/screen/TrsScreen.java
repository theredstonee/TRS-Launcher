package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * Basis aller TRS-Bildschirme (Gegenstück zu {@code screen/TrsScreen} der Fabric-Fassung).
 * Hier stehen die GuiScreen-Einstiegspunkte (Zeichnen, Hintergrund, Maus, Tastatur); Unterklassen
 * implementieren nur die neutralen Methoden {@link #draw}, {@link #onClick} usw.
 * Vanilla-Knöpfe/Textfelder funktionieren weiter, weil jede Eingabe an super geht, wenn die
 * Unterklasse sie nicht verbraucht.
 */
public abstract class TrsScreen extends GuiScreen {
	/** Schrift (1.12 heißt das GuiScreen-Feld fontRenderer, davor fontRendererObj). */
	protected FontRenderer font;
	protected Minecraft minecraft;

	// --- Neutrale Methoden für Unterklassen ---

	/** Zeichnet den Bildschirm (nach Hintergrund und Vanilla-Widgets). */
	protected abstract void draw(Gfx g, int mouseX, int mouseY, float partialTick);

	/** Eigener Hintergrund statt Vanilla (abgedunkelt/Erde)? */
	protected boolean customBackground() {
		return false;
	}

	/** Eigener Hintergrund, nur wenn {@link #customBackground()}. */
	protected void drawBackground(Gfx g, float partialTick) {
	}

	protected boolean onClick(double mouseX, double mouseY, int button) {
		return false;
	}

	protected boolean onRelease(double mouseX, double mouseY, int button) {
		return false;
	}

	protected boolean onDrag(double mouseX, double mouseY, int button) {
		return false;
	}

	protected boolean onScroll(double mouseX, double mouseY, double amount) {
		return false;
	}

	/** Taste gedrückt (LWJGL-2-Code). true = verbraucht. */
	protected boolean onKey(int key, char typed) {
		return false;
	}

	/** Esc bzw. "Schließen". */
	public void onClose() {
		open(null);
	}

	/** Darf Esc den Bildschirm schließen? */
	public boolean shouldCloseOnEsc() {
		return true;
	}

	/** Beim Verlassen des Bildschirms (auch beim Wechsel zu einem anderen). */
	public void removed() {
	}

	public boolean isPauseScreen() {
		return false;
	}

	// --- Hilfen ---

	protected boolean shiftDown() {
		return isShiftKeyDown();
	}

	protected void clickSound() {
		Mc.clickSound();
	}

	protected static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	protected void open(GuiScreen screen) {
		Mc.setScreen(screen);
	}

	protected void save() {
		TrsClient.get().saveConfig();
	}

	// --- GuiScreen-Einstiegspunkte ---

	@Override
	public void setWorldAndResolution(Minecraft mc, int width, int height) {
		this.minecraft = mc;
		this.font = Mc.font();
		super.setWorldAndResolution(mc, width, height);
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		Gfx g = Gfx.of(width, height);
		if (customBackground()) drawBackground(g, partialTicks);
		else drawDefaultBackground();
		super.drawScreen(mouseX, mouseY, partialTicks);
		draw(Gfx.of(width, height), mouseX, mouseY, partialTicks);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
		if (!onClick(mouseX, mouseY, button)) super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	protected void mouseReleased(int mouseX, int mouseY, int button) {
		if (!onRelease(mouseX, mouseY, button)) super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
		if (!onDrag(mouseX, mouseY, button)) super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
	}

	@Override
	public void handleMouseInput() throws IOException {
		super.handleMouseInput();
		int wheel = Mouse.getEventDWheel();
		if (wheel == 0) return;
		int mx = Mouse.getEventX() * width / mc.displayWidth;
		int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
		onScroll(mx, my, wheel > 0 ? 1 : -1);
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) throws IOException {
		if (onKey(keyCode, typedChar)) return;
		if (keyCode == 1) { // Esc
			if (shouldCloseOnEsc()) onClose();
			return;
		}
		super.keyTyped(typedChar, keyCode);
	}

	@Override
	public void onGuiClosed() {
		removed();
	}

	@Override
	public boolean doesGuiPauseGame() {
		return isPauseScreen();
	}
}
