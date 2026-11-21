package dev.theredstonee.trsclient.menus;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.menus.MenuStyle;
import dev.theredstonee.trsclient.core.net.StatusPing;
import dev.theredstonee.trsclient.core.partner.PartnerServer;
import dev.theredstonee.trsclient.core.partner.PartnerServers;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.menus.PartnerSection;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
//? if >=1.17 {
/*import net.minecraft.client.multiplayer.resolver.ServerAddress;
*///?}

import java.util.Collections;
import java.util.List;

/**
 * Partner-Server in der Mehrspieler-Liste (API.md §44.10): Abschnitt vor den eigenen Servern, Platz und Zeichnen über
 * {@code PartnerListMixin}, Klicks über {@code PartnerListMixin}/{@code PartnerClickMixin}. Beitritt wie ein normaler
 * Eintrag, ohne Eintrag in servers.dat.
 */
public final class PartnerList {
	private static final PartnerSection.Source SOURCE = new PartnerSection.Source() {
		@Override
		public TextureRef logo(PartnerServer s) {
			return PartnerServers.get().logo(s, System.currentTimeMillis());
		}

		@Override
		public StatusPing.Result ping(PartnerServer s) {
			return PartnerServers.get().pings().result(s.address, System.currentTimeMillis());
		}
	};

	private static List<PartnerServer> shown = Collections.emptyList();
	private static int mouseX = -1;
	private static int mouseY = -1;
	private static Object drawnList;
	private static long drawnAt;
	private static int drawX;
	private static int drawY;
	private static int drawW;
	private static int drawTop;
	private static int drawBottom;

	private PartnerList() {
	}

	/** Nach init und jedes Bild: Liste holen und Platz in der Serverliste anpassen. */
	public static void update(Screen s, int mx, int my) {
		if (!(s instanceof JoinMultiplayerScreen)) return;
		mouseX = mx;
		mouseY = my;
		try {
			ServerSelectionList list = ((dev.theredstonee.trsclient.mixin.JoinMultiplayerAccessor) s).trsclient$serverList();
			if (!(list instanceof PartnerListHost)) return;
			shown = PartnerServers.get().servers(System.currentTimeMillis());
			if (shown.isEmpty()) drawnList = null;
			((PartnerListHost) list).trsclient$partnerHeight(PartnerSection.height(shown.size()));
		} catch (RuntimeException | LinkageError ignored) {
			shown = Collections.emptyList();
		}
	}

	/** Für Selbsttests: gerade gezeigte Partner-Server. */
	public static List<PartnerServer> shown() {
		return shown;
	}

	/** Für Selbsttests: Lage im letzten Bild {x, y, w, top, bottom} oder null. */
	public static int[] lastDrawn() {
		if (drawnList == null || System.currentTimeMillis() - drawnAt > 1000) return null;
		return new int[]{drawX, drawY, drawW, drawTop, drawBottom};
	}

	/** Aus dem Listen-Mixin: Abschnitt ab ({@code x}, {@code y}) mit Inhaltsbreite {@code w} zeichnen. */
	public static void draw(Object list, Gfx g, int x, int y, int w) {
		if (!(list instanceof ServerSelectionList) || shown.isEmpty() || !(Mc.screen() instanceof JoinMultiplayerScreen)) return;
		try {
			int[] b = VanillaMenus.bounds((AbstractSelectionList<?>) list);
			drawnList = list;
			drawnAt = System.currentTimeMillis();
			drawX = x;
			drawY = y;
			drawW = w;
			drawTop = b[1];
			drawBottom = b[3];
			Canvas c = GfxCanvas.of(g, Mc.mc().font);
			// bis 1.19.3 zeichnen Listen ohne Scissor (Vanilla deckt mit Erde ab, der Menü-Stil nicht)
			//? if <1.19.4
			c.scissor(0, b[1], Mc.screen().width, b[3]);
			try {
				PartnerSection.draw(c, shown, SOURCE, x, y, w, b[1], b[3], mouseX, mouseY, MenuStyle.enabled(MenuStyle.Kind.MULTIPLAYER));
			} finally {
				//? if <1.19.4
				c.noScissor();
			}
			PartnerSection.Hit hover = PartnerSection.hit(shown.size(), x, y, w, b[1], b[3], mouseX, mouseY);
			if (hover.target == PartnerSection.Target.ROW) {
				dev.theredstonee.trsclient.core.connect.FastConnect.prefetch(shown.get(hover.row).address);
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Abschnitt fehlt dann in diesem Bild
		}
	}

	/** Aus dem Klick-Mixin der Liste: true = verbraucht. */
	public static boolean click(Object list, double mx, double my, int button) {
		if (button != 0 || list != drawnList || shown.isEmpty() || System.currentTimeMillis() - drawnAt > 1000) return false;
		PartnerSection.Hit h = PartnerSection.hit(shown.size(), drawX, drawY, drawW, drawTop, drawBottom, (int) mx, (int) my);
		try {
			if (h.target == PartnerSection.Target.HIDE) {
				PartnerServers.get().hide();
				update(Mc.screen(), mouseX, mouseY);
				return true;
			}
			if (h.target == PartnerSection.Target.ROW && h.row < shown.size()) {
				join(shown.get(h.row));
				return true;
			}
		} catch (RuntimeException | LinkageError ignored) {
			return false;
		}
		return false;
	}

	/** Wie ein Doppelklick auf einen eigenen Server, aber ohne Speichern in servers.dat. */
	public static void join(PartnerServer p) {
		Minecraft mc = Mc.mc();
		Screen parent = Mc.screen();
		//? if >=1.20.2 {
		/*ServerData data = new ServerData(p.name, p.address, ServerData.Type.OTHER);
		*///?} else
		ServerData data = new ServerData(p.name, p.address, false);
		PartnerServers.get().reportJoin(p);
		//? if >=1.20.5 {
		/*ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(p.address), data, false, null);
		*///?} elif >=1.20 {
		/*ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(p.address), data, false);
		*///?} elif >=1.17 {
		/*ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(p.address), data);
		*///?} else
		Mc.setScreen(new ConnectScreen(parent, mc, data));
	}
}
