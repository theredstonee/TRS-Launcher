package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.core.render.Projection;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.Hotspots;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Liste der Wegpunkte dieser Welt: ein-/ausblenden, bearbeiten, löschen, neu anlegen.
 * Die Wegpunkte gehören immer zur aktuellen Welt bzw. zum aktuellen Server.
 */
public final class WaypointListScreen extends TrsScreen {
	private final Component TITLE = Component.literal(I18n.tr("waypoints.title")).withStyle(ChatFormatting.BOLD);
	private static final int ROW_H = 18;

	private final Screen parent;
	private final Hotspots hot = new Hotspots();
	private int scroll;
	private int maxScroll;
	private int listX;
	private int listY;
	private int listW;
	private int listH;

	public WaypointListScreen(Screen parent) {
		super(Component.literal(I18n.tr("waypoints.title")));
		this.parent = parent;
	}

	@Override
	protected void draw(Gfx g, int mouseX, int mouseY, float partialTick) {
		hot.clear();
		int pw = Math.min(360, width - 16);
		int ph = Math.min(220, height - 16);
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;
		g.fill(px, py, px + pw, py + ph, Brand.BG);
		Brand.outline(g, px - 1, py - 1, pw + 2, ph + 2, Brand.BORDER);
		g.fill(px, py, px + pw, py + 24, Brand.SURFACE);
		g.fill(px, py, px + 3, py + 24, Brand.RED);
		g.text(font, TITLE, px + 11, py + 8, Brand.TEXT, false);
		String world = TrsClient.get().waypoints().worldKey();
		g.text(font, Gfx.clip(font, world.isEmpty() ? I18n.tr("waypoints.noWorld") : world, pw / 2), px + pw - 10 - Math.min(pw / 2,
				font.width(world)), py + 8, Brand.TEXT_DIM, false);

		List<Waypoint> list = TrsClient.get().waypoints().all();
		listX = px + 10;
		listY = py + 30;
		listW = pw - 20;
		listH = ph - 30 - 26;
		maxScroll = Math.max(0, list.size() * ROW_H - listH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));

		g.scissor(listX, listY, listX + listW, listY + listH);
		if (list.isEmpty()) {
			String key = TrsClient.get().modules().waypointAddKey.get();
			g.text(font, I18n.tr("waypoints.empty", Keys.display(key)), listX, listY + 4, Brand.TEXT_DIM, false);
		}
		for (int i = 0; i < list.size(); i++) {
			Waypoint waypoint = list.get(i);
			int ry = listY + i * ROW_H - scroll;
			if (ry + ROW_H < listY || ry > listY + listH) continue;
			boolean hover = inside(mouseX, mouseY, listX, ry, listW, ROW_H - 2)
					&& inside(mouseX, mouseY, listX, listY, listW, listH);
			g.fill(listX, ry, listX + listW, ry + ROW_H - 2, hover ? Brand.SURFACE_HOVER : Brand.SURFACE);
			g.fill(listX + 4, ry + 5, listX + 10, ry + 11, 0xFF000000 | waypoint.color);
			String label = waypoint.name + (waypoint.death ? " (" + I18n.tr("waypoints.death") + ")" : "");
			g.text(font, Gfx.clip(font, label, listW - 190), listX + 16, ry + 5, Brand.TEXT, false);
			String info = waypoint.x + " / " + waypoint.y + " / " + waypoint.z;
			if (minecraft != null && minecraft.player != null) {
				info += "   " + Projection.distanceLabel(waypoint.distanceTo(minecraft.player.getX(),
						minecraft.player.getY(), minecraft.player.getZ()));
			}
			g.text(font, info, listX + listW - 176, ry + 5, Brand.TEXT_DIM, false);

			int bx = listX + listW - 66;
			Brand.pill(g, font, bx, ry + 4, waypoint.visible, inside(mouseX, mouseY, bx, ry + 4, 26, 11));
			hot.add(bx, ry + 4, 26, 11, () -> {
				waypoint.visible = !waypoint.visible;
				TrsClient.get().waypoints().save();
			});
			int ex = bx + 30;
			Brand.button(g, font, ex, ry + 3, 16, 13, "✎", false, inside(mouseX, mouseY, ex, ry + 3, 16, 13));
			hot.add(ex, ry + 3, 16, 13, () -> open(new WaypointEditScreen(this, waypoint)));
			int dx = ex + 18;
			Brand.button(g, font, dx, ry + 3, 16, 13, "✕", false, inside(mouseX, mouseY, dx, ry + 3, 16, 13));
			hot.add(dx, ry + 3, 16, 13, () -> TrsClient.get().waypoints().remove(waypoint));
		}
		g.noScissor();

		int by = py + ph - 20;
		String add = I18n.tr("waypoints.addHere");
		int aw = font.width(add) + 12;
		Brand.button(g, font, px + 10, by, aw, 16, add, true, inside(mouseX, mouseY, px + 10, by, aw, 16));
		hot.add(px + 10, by, aw, 16, () -> open(new WaypointEditScreen(this, null)));
		int cw = 58;
		int cx = px + pw - 10 - cw;
		Brand.button(g, font, cx, by, cw, 16, I18n.tr("common.close"), false, inside(mouseX, mouseY, cx, by, cw, 16));
		hot.add(cx, by, cw, 16, this::onClose);
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
	protected boolean onScroll(double mouseX, double mouseY, double amount) {
		if (!inside(mouseX, mouseY, listX, listY, listW, listH)) return false;
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(amount) * ROW_H));
		return true;
	}

	@Override
	public void onClose() {
		open(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
