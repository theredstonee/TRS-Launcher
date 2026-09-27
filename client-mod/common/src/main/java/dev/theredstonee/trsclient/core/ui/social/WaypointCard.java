package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;

/**
 * Wegpunkt-Karte im Chat (API.md §18.10): „📍 Name · X Y Z · Dimension · Server/Einzelspielerwelt“ mit „Übernehmen“
 * (legt den Wegpunkt an, wenn die Welt passt) und „Anzeigen“ (Weltkarte auf den Punkt). Größe wie die Einladungskarte.
 */
public final class WaypointCard {
	private WaypointCard() {
	}

	/** Wohin Hinweise gehen (Sozial-Bildschirm: {@code Social.hint}). */
	public interface Hint {
		void show(String key, Object[] args, boolean error);
	}

	public static final int DEFAULT_COLOR = 0x3D7BFF;

	/** Zeichnet die Karte (Höhe {@link ChatLayout#INVITE_H}); {@code buttons} = Knöpfe anzeigen. */
	public static void draw(Canvas c, Kit kit, final Chat.Waypoint wp, int x, int y, int w, int mx, int my, boolean buttons,
			final Hint hint) {
		Theme t = Theme.get();
		int color = 0xFF000000 | (wp.color >= 0 ? wp.color : DEFAULT_COLOR);
		Redstone.stone(c, x, y, w, ChatLayout.INVITE_H, t.deep, ColorMath.lerp(t.border, color, 0.45f));
		int ix = x + 4;
		int iy = y + 4;
		Redstone.block(c, ix, iy, 28, 28, t.bevelDark);
		Icons.draw(c, "pin", ix + 6, iy + 6, 2, color);
		String take = I18n.tr("waypoint.card.take");
		String show = I18n.tr("waypoint.card.show");
		int bw = buttons ? Math.min(62, Math.max(40, Math.max(c.textWidth(take), c.textWidth(show)) + 10)) : 0;
		int bx = x + w - bw - 4;
		int tx = ix + 33;
		int tw = (buttons ? bx : x + w - 2) - tx - 3;
		Paint.textClipped(c, wp.name, tx, y + 4, tw, t.text, false);
		Paint.textClipped(c, wp.coords() + " · " + WorldMapUi.dimensionName(wp.dimension), tx, y + 14, tw, t.textDim, false);
		String where = wp.server() ? wp.address : I18n.tr("waypoint.card.singleplayer");
		Paint.textClipped(c, where, tx, y + 24, tw, ColorMath.lerp(t.textDim, color, 0.35f), false);
		if (!buttons) return;
		WaypointShare.Result match = WaypointShare.check(wp);
		boolean here = match == WaypointShare.Result.OK;
		kit.button(c, bx, y + 2, bw, 15, take, here, true, mx, my, new Runnable() {
			@Override
			public void run() {
				WaypointShare.Result r = WaypointShare.adopt(wp);
				hint.show(r.key(), args(r, wp), r != WaypointShare.Result.ADDED && r != WaypointShare.Result.ALREADY);
			}
		});
		kit.button(c, bx, y + 19, bw, 15, show, false, true, mx, my, new Runnable() {
			@Override
			public void run() {
				WaypointShare.Result r = WaypointShare.show(wp);
				if (r != WaypointShare.Result.OK) hint.show(r.key(), args(r, wp), true);
			}
		});
	}

	/** Platzhalter der Hinweise je Ergebnis. */
	public static Object[] args(WaypointShare.Result r, Chat.Waypoint wp) {
		switch (r) {
			case ADDED:
			case ALREADY:
				return new Object[]{wp.name};
			case OTHER_SERVER:
				return new Object[]{wp.server() ? wp.address : I18n.tr("waypoint.card.singleplayer")};
			case OTHER_DIMENSION:
				return new Object[]{WorldMapUi.dimensionName(wp.dimension)};
			default:
				return new Object[0];
		}
	}

	/** Einzeilige Beschreibung (Composer-Chip, Vorschau). */
	public static String line(Chat.Waypoint wp) {
		return wp.name + " · " + wp.coords();
	}
}
