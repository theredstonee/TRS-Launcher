package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TextSetting;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Hotspots;
import dev.theredstonee.trsclient.ui.SettingRows;
import dev.theredstonee.trsclient.ui.TextField;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Kleiner Eingabedialog für eine Text-Einstellung (Auto-GG-Nachricht, Text-Hotkeys):
 * Feld, Abbrechen, Speichern. Enter speichert, Esc verwirft.
 */
public final class TextInputScreen extends GuiScreen {
	private final GuiScreen parent;
	private final TextSetting setting;
	private final Hotspots hot = new Hotspots();
	private final TextField input;

	public TextInputScreen(GuiScreen parent, TextSetting setting) {
		this.parent = parent;
		this.setting = setting;
		this.input = new TextField(setting.get(), setting.maxLength());
	}

	@Override
	public void render(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();
		hot.clear();
		int w = Math.min(280, width - 40);
		int x = (width - w) / 2;
		int y = height / 2 - 9;
		Brand.fill(x - 10, y - 36, x + w + 10, y + 54, Brand.BG);
		Brand.outline(x - 11, y - 37, w + 22, 92, Brand.BORDER);
		Brand.text(fontRenderer, setting.label(), x, y - 26, Brand.TEXT, false);
		if (!setting.placeholder().isEmpty()) {
			Brand.text(fontRenderer, "Beispiel: " + setting.placeholder(), x, y - 14, Brand.TEXT_DIM, false);
		}
		input.draw(fontRenderer, x, y, w, 18, setting.placeholder());

		int by = y + 28;
		int bw = 80;
		button(mouseX, mouseY, x, by, bw, 16, "Abbrechen", false, this::close);
		button(mouseX, mouseY, x + w - bw, by, bw, 16, "Speichern", true, this::apply);
	}

	private void button(int mx, int my, int x, int y, int w, int h, String label, boolean primary, Runnable action) {
		Brand.button(fontRenderer, x, y, w, h, label, primary, SettingRows.inside(mx, my, x, y, w, h));
		hot.add(x, y, w, h, action);
	}

	private void apply() {
		setting.set(input.value());
		TrsClient.get().saveConfig();
		mc.displayGuiScreen(parent);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (hot.click((int) mouseX, (int) mouseY, button)) {
			TrsMenuScreen.clickSound(this);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			apply();
			return true;
		}
		if (input.onKey(key)) return true;
		return super.keyPressed(key, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char typed, int modifiers) {
		return input.onChar(typed);
	}

	/** Zurück zum vorherigen Bildschirm (auch bei Esc – der Text wird dann verworfen). */
	@Override
	public void close() {
		mc.displayGuiScreen(parent);
	}

	@Override
	public boolean doesGuiPauseGame() {
		return false;
	}
}
