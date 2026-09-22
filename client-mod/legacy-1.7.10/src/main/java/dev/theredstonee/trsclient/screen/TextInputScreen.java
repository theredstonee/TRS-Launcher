package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TextSetting;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Hotspots;
import dev.theredstonee.trsclient.ui.SettingRows;
import dev.theredstonee.trsclient.ui.TextField;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

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
	public void initGui() {
		// Ohne das wiederholt eine gehaltene Taste (z. B. Rücktaste) nicht.
		Keyboard.enableRepeatEvents(true);
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();
		hot.clear();
		int w = Math.min(280, width - 40);
		int x = (width - w) / 2;
		int y = height / 2 - 9;
		Brand.fill(x - 10, y - 36, x + w + 10, y + 54, Brand.BG);
		Brand.outline(x - 11, y - 37, w + 22, 92, Brand.BORDER);
		Brand.text(fontRendererObj, setting.label(), x, y - 26, Brand.TEXT, false);
		if (!setting.placeholder().isEmpty()) {
			Brand.text(fontRendererObj, "Beispiel: " + setting.placeholder(), x, y - 14, Brand.TEXT_DIM, false);
		}
		input.draw(fontRendererObj, x, y, w, 18, setting.placeholder());

		int by = y + 28;
		int bw = 80;
		button(mouseX, mouseY, x, by, bw, 16, "Abbrechen", false, this::close);
		button(mouseX, mouseY, x + w - bw, by, bw, 16, "Speichern", true, this::apply);
	}

	private void button(int mx, int my, int x, int y, int w, int h, String label, boolean primary, Runnable action) {
		Brand.button(fontRendererObj, x, y, w, h, label, primary, SettingRows.inside(mx, my, x, y, w, h));
		hot.add(x, y, w, h, action);
	}

	private void apply() {
		setting.set(input.value());
		TrsClient.get().saveConfig();
		close();
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		if (hot.click(mouseX, mouseY, button)) {
			TrsMenuScreen.clickSound(this);
			return;
		}
		super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) {
		if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
			apply();
			return;
		}
		if (keyCode == Keyboard.KEY_ESCAPE) {
			close();
			return;
		}
		if (input.onKey(keyCode)) return;
		input.onChar(typedChar);
	}

	private void close() {
		mc.displayGuiScreen(parent);
	}

	@Override
	public void onGuiClosed() {
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	public boolean doesGuiPauseGame() {
		return false;
	}
}
