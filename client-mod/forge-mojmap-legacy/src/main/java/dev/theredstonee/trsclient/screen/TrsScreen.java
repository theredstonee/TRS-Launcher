package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
//? if >=1.16
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Basis aller TRS-Bildschirme. Hier – und nur hier – stehen die versionsabhängigen
 * Einstiegspunkte (Zeichnen mit/ohne PoseStack, Hintergrund); Unterklassen implementieren
 * nur die neutralen Methoden {@link #draw}, {@link #onClick} usw.
 */
public abstract class TrsScreen extends Screen {
	protected TrsScreen(String title) {
		super(Mc.literal(title));
	}

	// --- Neutrale Methoden für Unterklassen ---

	/** Zeichnet den Bildschirm (nach Hintergrund und Vanilla-Widgets). */
	protected abstract void draw(Gfx g, int mouseX, int mouseY, float partialTick);

	/** Eigener Hintergrund statt Vanilla (abgedunkelt bzw. Erde)? */
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

	/** Taste gedrückt (GLFW-Code). true = verbraucht. */
	protected boolean onKey(int key, int modifiers) {
		return false;
	}

	/** Zeichen getippt. true = verbraucht. */
	protected boolean onChar(char c) {
		return false;
	}

	// --- Hilfen ---

	protected boolean shiftDown() {
		return hasShiftDown();
	}

	protected void clickSound() {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}

	protected static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	protected void open(Screen screen) {
		Mc.setScreen(screen);
	}

	protected void save() {
		TrsClient.get().saveConfig();
	}

	// --- Versionsabhängige Einstiegspunkte ---

	//? if >=1.16 {
	@Override
	public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
		if (customBackground()) drawBackground(Gfx.of(pose), partialTick);
		else renderBackground(pose);
		super.render(pose, mouseX, mouseY, partialTick);
		draw(Gfx.of(pose), mouseX, mouseY, partialTick);
	}
	//?} else {
	/*@Override
	public void render(int mouseX, int mouseY, float partialTick) {
		if (customBackground()) drawBackground(Gfx.of(), partialTick);
		else renderBackground();
		super.render(mouseX, mouseY, partialTick);
		draw(Gfx.of(), mouseX, mouseY, partialTick);
	}
	*///?}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return onClick(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return onRelease(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		return onDrag(mouseX, mouseY, button) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return (amount != 0 && onScroll(mouseX, mouseY, amount)) || super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return onKey(keyCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char c, int modifiers) {
		return onChar(c) || super.charTyped(c, modifiers);
	}
}
