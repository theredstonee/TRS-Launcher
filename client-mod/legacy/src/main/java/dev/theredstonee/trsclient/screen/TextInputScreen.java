package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.core.module.TextSetting;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.Hotspots;
import dev.theredstonee.trsclient.ui.TextField;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/**
 * Kleiner Eingabedialog für eine Text-Einstellung (Auto-GG-Nachricht, Text-Hotkeys):
 * Feld, Abbrechen, Speichern. Enter speichert, Esc verwirft.
 */
public final class TextInputScreen extends TrsScreen {
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
		super.initGui();
		Keyboard.enableRepeatEvents(true);
	}

	@Override
	protected void draw(Gfx g, int mouseX, int mouseY, float partialTick) {
		hot.clear();
		int w = Math.min(280, width - 40);
		int x = (width - w) / 2;
		int y = height / 2 - 9;
		g.fill(x - 10, y - 36, x + w + 10, y + 54, Brand.BG);
		Brand.outline(g, x - 11, y - 37, w + 22, 92, Brand.BORDER);
		g.text(font, setting.label(), x, y - 26, Brand.TEXT, false);
		if (!setting.placeholder().isEmpty()) {
			g.text(font, "Beispiel: " + setting.placeholder(), x, y - 14, Brand.TEXT_DIM, false);
		}
		input.draw(g, font, x, y, w, 18, setting.placeholder());

		int by = y + 28;
		int bw = 80;
		button(g, mouseX, mouseY, x, by, bw, 16, "Abbrechen", false, this::onClose);
		button(g, mouseX, mouseY, x + w - bw, by, bw, 16, "Speichern", true, this::apply);
	}

	private void button(Gfx g, int mx, int my, int x, int y, int w, int h, String label, boolean primary, Runnable action) {
		Brand.button(g, font, x, y, w, h, label, primary, inside(mx, my, x, y, w, h));
		hot.add(x, y, w, h, action);
	}

	private void apply() {
		setting.set(input.value());
		save();
		open(parent);
	}

	@Override
	protected boolean onClick(double mouseX, double mouseY, int button) {
		if (hot.click(mouseX, mouseY, button)) {
			clickSound();
			return true;
		}
		return false;
	}

	@Override
	protected boolean onKey(int key, char typed) {
		if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
			apply();
			return true;
		}
		if (input.onKey(key)) return true;
		return input.onChar(typed);
	}

	@Override
	public void onClose() {
		open(parent);
	}

	@Override
	public void removed() {
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
