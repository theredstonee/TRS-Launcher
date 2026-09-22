package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.core.render.Projection;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Hotspots;
import dev.theredstonee.trsclient.ui.SettingRows;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Mouse;

import java.util.List;

/**
 * Liste der Wegpunkte dieser Welt: ein-/ausblenden, bearbeiten, löschen, neu anlegen.
 * Die Wegpunkte gehören immer zur aktuellen Welt bzw. zum aktuellen Server.
 */
public final class WaypointListScreen extends GuiScreen {
	private static final String TITLE = EnumChatFormatting.BOLD + "Wegpunkte";
	private static final int ROW_H = 18;

	private final GuiScreen parent;
	private final Hotspots hot = new Hotspots();
	private int scroll;
	private int maxScroll;
	private int listX;
	private int listY;
	private int listW;
	private int listH;

	public WaypointListScreen(GuiScreen parent) {
		this.parent = parent;
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();
		hot.clear();
		int pw = Math.min(360, width - 16);
		int ph = Math.min(220, height - 16);
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;
		Brand.rect(px, py, pw, ph, Brand.BG);
		Brand.outline(px - 1, py - 1, pw + 2, ph + 2, Brand.BORDER);
		Brand.rect(px, py, pw, 24, Brand.SURFACE);
		Brand.rect(px, py, 3, 24, Brand.RED);
		Brand.text(fontRendererObj, TITLE, px + 11, py + 8, Brand.TEXT, false);
		String world = TrsClient.get().waypoints().worldKey();
		if (world.isEmpty()) world = "keine Welt";
		String worldShort = fontRendererObj.trimStringToWidth(world, pw / 2);
		Brand.text(fontRendererObj, worldShort, px + pw - 10 - fontRendererObj.getStringWidth(worldShort), py + 8,
				Brand.TEXT_DIM, false);

		List<Waypoint> list = TrsClient.get().waypoints().all();
		listX = px + 10;
		listY = py + 30;
		listW = pw - 20;
		listH = ph - 30 - 26;
		maxScroll = Math.max(0, list.size() * ROW_H - listH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));

		Brand.scissor(listX, listY, listX + listW, listY + listH);
		if (list.isEmpty()) {
			Brand.text(fontRendererObj, "Noch keine Wegpunkte – Taste "
					+ GameSettings.getKeyDisplayString(TrsKeys.waypointAdd.getKeyCode())
					+ " im Spiel legt einen an.", listX, listY + 4, Brand.TEXT_DIM, false);
		}
		EntityClientPlayerMP player = mc.thePlayer;
		for (int i = 0; i < list.size(); i++) {
			final Waypoint waypoint = list.get(i);
			int ry = listY + i * ROW_H - scroll;
			if (ry + ROW_H < listY || ry > listY + listH) continue;
			boolean hover = SettingRows.inside(mouseX, mouseY, listX, ry, listW, ROW_H - 2)
					&& SettingRows.inside(mouseX, mouseY, listX, listY, listW, listH);
			Brand.rect(listX, ry, listW, ROW_H - 2, hover ? Brand.SURFACE_HOVER : Brand.SURFACE);
			Brand.rect(listX + 4, ry + 5, 6, 6, 0xFF000000 | waypoint.color);
			String label = waypoint.name + (waypoint.death ? " (Tod)" : "");
			Brand.text(fontRendererObj, fontRendererObj.trimStringToWidth(label, listW - 190), listX + 16, ry + 5,
					Brand.TEXT, false);
			String info = waypoint.x + " / " + waypoint.y + " / " + waypoint.z;
			if (player != null) {
				info += "   " + Projection.distanceLabel(
						waypoint.distanceTo(player.posX, player.boundingBox.minY, player.posZ));
			}
			Brand.text(fontRendererObj, info, listX + listW - 176, ry + 5, Brand.TEXT_DIM, false);

			int bx = listX + listW - 66;
			Brand.pill(fontRendererObj, bx, ry + 4, waypoint.visible, SettingRows.inside(mouseX, mouseY, bx, ry + 4, 26, 11));
			hot.add(bx, ry + 4, 26, 11, () -> {
				waypoint.visible = !waypoint.visible;
				TrsClient.get().waypoints().save();
			});
			int ex = bx + 30;
			Brand.button(fontRendererObj, ex, ry + 3, 16, 13, "...", false,
					SettingRows.inside(mouseX, mouseY, ex, ry + 3, 16, 13));
			hot.add(ex, ry + 3, 16, 13, () -> mc.displayGuiScreen(new WaypointEditScreen(this, waypoint)));
			int dx = ex + 18;
			Brand.button(fontRendererObj, dx, ry + 3, 16, 13, "X", false,
					SettingRows.inside(mouseX, mouseY, dx, ry + 3, 16, 13));
			hot.add(dx, ry + 3, 16, 13, () -> TrsClient.get().waypoints().remove(waypoint));
		}
		Brand.noScissor();

		int by = py + ph - 20;
		String add = "Hier anlegen";
		int aw = fontRendererObj.getStringWidth(add) + 12;
		Brand.button(fontRendererObj, px + 10, by, aw, 16, add, true, SettingRows.inside(mouseX, mouseY, px + 10, by, aw, 16));
		hot.add(px + 10, by, aw, 16, () -> mc.displayGuiScreen(new WaypointEditScreen(this, null)));
		int cw = 58;
		int cx = px + pw - 10 - cw;
		Brand.button(fontRendererObj, cx, by, cw, 16, "Schließen", false, SettingRows.inside(mouseX, mouseY, cx, by, cw, 16));
		hot.add(cx, by, cw, 16, this::close);
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
	public void handleMouseInput() {
		super.handleMouseInput();
		int wheel = Mouse.getEventDWheel();
		if (wheel == 0) return;
		int mx = Mouse.getEventX() * width / mc.displayWidth;
		int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
		if (!SettingRows.inside(mx, my, listX, listY, listW, listH)) return;
		scroll = Math.max(0, Math.min(maxScroll, scroll - Integer.signum(wheel) * ROW_H));
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) {
		if (keyCode == 1) { // Esc
			close();
			return;
		}
		super.keyTyped(typedChar, keyCode);
	}

	private void close() {
		mc.displayGuiScreen(parent);
	}

	@Override
	public boolean doesGuiPauseGame() {
		return false;
	}
}
