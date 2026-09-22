package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else
import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.9 {
/*import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
*///?}

/**
 * Basis aller TRS-Bildschirme. Hier – und nur hier – stehen die versionsabhängigen
 * Einstiegspunkte (Zeichnen, Hintergrund, Maus, Tastatur); Unterklassen implementieren
 * nur die neutralen Methoden {@link #draw}, {@link #onClick} usw.
 * Vanilla-Widgets (z. B. Textfelder) funktionieren weiter, weil jede Eingabe an super geht,
 * wenn die Unterklasse sie nicht verbraucht.
 */
public abstract class TrsScreen extends Screen {
	protected TrsScreen(Component title) {
		super(title);
	}

	// --- Neutrale Methoden für Unterklassen ---

	/** Zeichnet den Bildschirm (nach Hintergrund und Vanilla-Widgets). */
	protected abstract void draw(Gfx g, int mouseX, int mouseY, float partialTick);

	/** Eigener Hintergrund statt Vanilla (Weichzeichner/Verlauf)? */
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

	/** Taste gedrückt (GLFW-/InputConstants-Code). true = verbraucht. */
	protected boolean onKey(int key, int modifiers) {
		return false;
	}

	/** Zeichen eingegeben (Textfelder des TRS-Menüs). true = verbraucht. */
	protected boolean onChar(char c) {
		return false;
	}

	// --- Hilfen ---

	protected boolean shiftDown() {
		//? if >=1.21.9 {
		/*return minecraft.hasShiftDown();
		*///?} else
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

	//? if >=26.1 {
	/*@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (customBackground()) drawBackground(Gfx.of(g), partialTick);
		else super.extractBackground(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		draw(Gfx.of(g), mouseX, mouseY, partialTick);
	}
	*///?} else {
	@Override
	//? if >=1.20.2 {
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (customBackground()) drawBackground(Gfx.of(g), partialTick);
		else super.renderBackground(g, mouseX, mouseY, partialTick);
	}
	//?} else {
	/*public void renderBackground(GuiGraphics g) {
		if (customBackground()) drawBackground(Gfx.of(g), 0f);
		else super.renderBackground(g);
	}
	*///?}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// Bis 1.20.1 zeichnet Screen#render den Hintergrund nicht selbst.
		//? if <1.20.2
		/*renderBackground(g);*/
		super.render(g, mouseX, mouseY, partialTick);
		draw(Gfx.of(g), mouseX, mouseY, partialTick);
	}
	//?}

	//? if >=1.21.9 {
	/*@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return onClick(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return onRelease(event.x(), event.y(), event.button()) || super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return onDrag(event.x(), event.y(), event.button()) || super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return onKey(event.key(), event.modifiers()) || super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		int codepoint = event.codepoint();
		return (codepoint > 0 && codepoint <= 0xFFFF && onChar((char) codepoint)) || super.charTyped(event);
	}
	*///?} else {
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
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return onKey(keyCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char c, int modifiers) {
		return onChar(c) || super.charTyped(c, modifiers);
	}
	//?}

	//? if >=1.20.2 {
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return (scrollY != 0 && onScroll(mouseX, mouseY, scrollY)) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
	//?} else {
	/*@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		return (scrollY != 0 && onScroll(mouseX, mouseY, scrollY)) || super.mouseScrolled(mouseX, mouseY, scrollY);
	}
	*///?}
}
