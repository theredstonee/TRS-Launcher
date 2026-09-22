package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.ColorSetting;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.Hotspots;
import dev.theredstonee.trsclient.ui.TextField;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/**
 * Wegpunkt anlegen oder bearbeiten: Name, Farbe (Palette) und Position.
 * Beim Anlegen steht die Position des Spielers drin.
 */
public final class WaypointEditScreen extends TrsScreen {
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
	public void initGui() {
		super.initGui();
		Keyboard.enableRepeatEvents(true);
	}

	@Override
	protected void draw(Gfx g, int mouseX, int mouseY, float partialTick) {
		hot.clear();
		int w = Math.min(260, width - 40);
		int x = (width - w) / 2;
		int y = height / 2 - 40;
		g.fill(x - 10, y - 10, x + w + 10, y + 108, Brand.BG);
		Brand.outline(g, x - 11, y - 11, w + 22, 120, Brand.BORDER);
		g.text(font, editing == null ? "Wegpunkt anlegen" : "Wegpunkt bearbeiten", x, y, Brand.TEXT, false);

		g.text(font, "Name", x, y + 16, Brand.TEXT_DIM, false);
		name.draw(g, font, x, y + 26, w, 18, "Name des Wegpunkts");

		g.text(font, "Farbe", x, y + 50, Brand.TEXT_DIM, false);
		int swatch = 16;
		for (int i = 0; i < ColorSetting.PALETTE.length; i++) {
			int cx = x + i * (swatch + 4);
			int rgb = ColorSetting.PALETTE[i];
			g.fill(cx, y + 60, cx + swatch, y + 60 + swatch, 0xFF000000 | rgb);
			Brand.outline(g, cx - 1, y + 59, swatch + 2, swatch + 2, rgb == color ? Brand.AMBER : Brand.BORDER);
			final int picked = rgb;
			hot.add(cx, y + 60, swatch, swatch, () -> color = picked);
		}

		String position = editing == null ? playerPosition() : (editing.x + " / " + editing.y + " / " + editing.z);
		g.text(font, "Position: " + position, x, y + 82, Brand.TEXT_DIM, false);

		int by = y + 92;
		int bw = 76;
		button(g, mouseX, mouseY, x, by, bw, 16, "Abbrechen", false, this::onClose);
		button(g, mouseX, mouseY, x + w - bw, by, bw, 16, editing == null ? "Anlegen" : "Speichern", true, this::apply);
	}

	private String playerPosition() {
		EntityPlayerSP player = Mc.player();
		if (player == null) return "?";
		return (int) Math.floor(player.posX) + " / " + (int) Math.floor(player.posY)
				+ " / " + (int) Math.floor(player.posZ);
	}

	private void button(Gfx g, int mx, int my, int x, int y, int w, int h, String label, boolean primary, Runnable action) {
		Brand.button(g, font, x, y, w, h, label, primary, inside(mx, my, x, y, w, h));
		hot.add(x, y, w, h, action);
	}

	private void apply() {
		String text = name.value().trim();
		if (editing == null) {
			TrsClient.get().waypoints().create(text.isEmpty() ? "Wegpunkt" : text, color);
			Mc.actionBar("Wegpunkt angelegt");
		} else {
			editing.name = text.isEmpty() ? "Wegpunkt" : text;
			editing.color = color;
			TrsClient.get().waypoints().save();
		}
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
		if (name.onKey(key)) return true;
		return name.onChar(typed);
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
