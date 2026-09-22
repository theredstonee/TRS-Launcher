package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.ColorSetting;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Hotspots;
import dev.theredstonee.trsclient.ui.SettingRows;
import dev.theredstonee.trsclient.ui.TextField;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Wegpunkt anlegen oder bearbeiten: Name, Farbe (Palette) und Position.
 * Beim Anlegen steht die Position des Spielers drin.
 */
public final class WaypointEditScreen extends GuiScreen {
	private final GuiScreen parent;
	/** null = neuer Wegpunkt an der Spielerposition. */
	private final Waypoint editing;
	private final Hotspots hot = new Hotspots();
	private final TextField name;
	private int color;

	public WaypointEditScreen(GuiScreen parent, Waypoint editing) {
		this.parent = parent;
		this.editing = editing;
		this.name = new TextField(editing == null ? defaultName() : editing.name, 32);
		this.color = editing == null ? ColorSetting.PALETTE[1] : editing.color;
	}

	private static String defaultName() {
		return "Punkt " + (TrsClient.get().waypoints().all().size() + 1);
	}

	@Override
	public void render(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();
		hot.clear();
		int w = Math.min(260, width - 40);
		int x = (width - w) / 2;
		int y = height / 2 - 40;
		Brand.fill(x - 10, y - 10, x + w + 10, y + 108, Brand.BG);
		Brand.outline(x - 11, y - 11, w + 22, 120, Brand.BORDER);
		Brand.text(fontRenderer, editing == null ? "Wegpunkt anlegen" : "Wegpunkt bearbeiten", x, y, Brand.TEXT, false);

		Brand.text(fontRenderer, "Name", x, y + 16, Brand.TEXT_DIM, false);
		name.draw(fontRenderer, x, y + 26, w, 18, "Name des Wegpunkts");

		Brand.text(fontRenderer, "Farbe", x, y + 50, Brand.TEXT_DIM, false);
		int swatch = 16;
		for (int i = 0; i < ColorSetting.PALETTE.length; i++) {
			int cx = x + i * (swatch + 4);
			int rgb = ColorSetting.PALETTE[i];
			Brand.fill(cx, y + 60, cx + swatch, y + 60 + swatch, 0xFF000000 | rgb);
			Brand.outline(cx - 1, y + 59, swatch + 2, swatch + 2, rgb == color ? Brand.AMBER : Brand.BORDER);
			final int picked = rgb;
			hot.add(cx, y + 60, swatch, swatch, () -> color = picked);
		}

		String position = editing == null ? playerPosition() : (editing.x + " / " + editing.y + " / " + editing.z);
		Brand.text(fontRenderer, "Position: " + position, x, y + 82, Brand.TEXT_DIM, false);

		int by = y + 92;
		int bw = 76;
		button(mouseX, mouseY, x, by, bw, 16, "Abbrechen", false, this::close);
		button(mouseX, mouseY, x + w - bw, by, bw, 16, editing == null ? "Anlegen" : "Speichern", true, this::apply);
	}

	private String playerPosition() {
		EntityPlayerSP player = mc.player;
		if (player == null) return "?";
		return (int) Math.floor(player.posX) + " / " + (int) Math.floor(player.posY)
				+ " / " + (int) Math.floor(player.posZ);
	}

	private void button(int mx, int my, int x, int y, int w, int h, String label, boolean primary, Runnable action) {
		Brand.button(fontRenderer, x, y, w, h, label, primary, SettingRows.inside(mx, my, x, y, w, h));
		hot.add(x, y, w, h, action);
	}

	private void apply() {
		String text = name.value().trim();
		if (editing == null) {
			TrsClient.get().waypoints().create(text.isEmpty() ? "Wegpunkt" : text, color);
			if (mc.ingameGUI != null) mc.ingameGUI.setOverlayMessage("Wegpunkt angelegt", false);
		} else {
			editing.name = text.isEmpty() ? "Wegpunkt" : text;
			editing.color = color;
			TrsClient.get().waypoints().save();
		}
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
		if (name.onKey(key)) return true;
		return super.keyPressed(key, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char typed, int modifiers) {
		return name.onChar(typed);
	}

	@Override
	public void close() {
		mc.displayGuiScreen(parent);
	}

	@Override
	public boolean doesGuiPauseGame() {
		return false;
	}
}
