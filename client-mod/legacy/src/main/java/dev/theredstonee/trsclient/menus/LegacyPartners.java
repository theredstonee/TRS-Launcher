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
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ServerSelectionList;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/**
 * Partner-Server über den eigenen Servern der Mehrspieler-Liste (1.8.9–1.12.2): Platz über {@code headerPadding} der
 * Liste (verschiebt Einträge, Klicks und Scrollhöhe wie Vanillas Listen-Kopf), gezeichnet nach dem Bildschirm, Klicks
 * über das Maus-Ereignis vor Vanilla.
 */
public final class LegacyPartners {
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
	private static Field listField;
	private static boolean listSearched;
	/** Lage im letzten Bild {x, y, w, top, bottom}, null = nicht gezeichnet. */
	private static int[] drawn;
	private static long drawnAt;
	/** Nur Selbsttests: Mausposition statt der echten. */
	private static int[] testMouse;

	public static List<PartnerServer> shown() {
		return shown;
	}

	public static int[] lastDrawn() {
		return drawn == null || System.currentTimeMillis() - drawnAt > 1000 ? null : drawn.clone();
	}

	public static void testMouse(int x, int y) {
		testMouse = x < 0 ? null : new int[]{x, y};
	}

	static ServerSelectionList list(GuiMultiplayer s) {
		try {
			if (!listSearched) {
				listSearched = true;
				for (Field f : GuiMultiplayer.class.getDeclaredFields()) {
					if (f.getType() == ServerSelectionList.class) {
						f.setAccessible(true);
						listField = f;
						break;
					}
				}
			}
			return listField == null ? null : (ServerSelectionList) listField.get(s);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** Liste holen und Platz setzen (vor dem Zeichnen und nach init). */
	static void update(GuiScreen s) {
		if (!(s instanceof GuiMultiplayer)) return;
		try {
			ServerSelectionList list = list((GuiMultiplayer) s);
			if (list == null) return;
			shown = PartnerServers.get().servers(System.currentTimeMillis());
			if (shown.isEmpty()) drawn = null;
			list.headerPadding = PartnerSection.height(shown.size());
		} catch (RuntimeException | LinkageError e) {
			shown = Collections.emptyList();
		}
	}

	@SubscribeEvent
	public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
		update(Mc.eventGui(event));
	}

	@SubscribeEvent
	public void onDrawPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
		update(Mc.eventGui(event));
	}

	@SubscribeEvent
	public void onDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
		GuiScreen s = Mc.eventGui(event);
		if (!(s instanceof GuiMultiplayer) || shown.isEmpty()) return;
		ServerSelectionList list = list((GuiMultiplayer) s);
		if (list == null) return;
		try {
			int x = list.left + list.width / 2 - list.getListWidth() / 2 + 2;
			int y = list.top + 4 - list.getAmountScrolled();
			int w = list.getListWidth();
			int mx = testMouse != null ? testMouse[0] : mouseX(event);
			int my = testMouse != null ? testMouse[1] : mouseY(event);
			drawn = new int[]{x, y, w, list.top, list.bottom};
			drawnAt = System.currentTimeMillis();
			Canvas c = GfxCanvas.of(Gfx.of(s.width, s.height), Mc.font());
			c.scissor(0, list.top, s.width, list.bottom);
			try {
				PartnerSection.draw(c, shown, SOURCE, x, y, w, list.top, list.bottom, mx, my, MenuStyle.enabled(MenuStyle.Kind.MULTIPLAYER));
			} finally {
				c.noScissor();
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Abschnitt fehlt dann in diesem Bild
		}
	}

	@SubscribeEvent
	public void onMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
		GuiScreen s = Mc.eventGui(event);
		if (!(s instanceof GuiMultiplayer) || !Mouse.getEventButtonState()) return;
		Minecraft mc = Mc.mc();
		int x = Mouse.getEventX() * s.width / mc.displayWidth;
		int y = s.height - Mouse.getEventY() * s.height / mc.displayHeight - 1;
		if (click(x, y, Mouse.getEventButton())) event.setCanceled(true);
	}

	/** true = verbraucht. */
	public static boolean click(int x, int y, int button) {
		int[] d = lastDrawn();
		if (button != 0 || d == null || shown.isEmpty()) return false;
		PartnerSection.Hit h = PartnerSection.hit(shown.size(), d[0], d[1], d[2], d[3], d[4], x, y);
		try {
			if (h.target == PartnerSection.Target.HIDE) {
				PartnerServers.get().hide();
				update(Mc.screen());
				return true;
			}
			if (h.target == PartnerSection.Target.ROW && h.row < shown.size()) {
				join(shown.get(h.row));
				return true;
			}
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
		return false;
	}

	/** Wie ein Doppelklick auf einen eigenen Server, aber ohne Eintrag in servers.dat. */
	static void join(PartnerServer p) {
		PartnerServers.get().reportJoin(p);
		Minecraft mc = Mc.mc();
		mc.displayGuiScreen(new GuiConnecting(Mc.screen(), mc, new ServerData(p.name, p.address, false)));
	}

	private static int mouseX(GuiScreenEvent.DrawScreenEvent.Post event) {
		//? if >=1.9 {
		/*return event.getMouseX();
		*///?} else
		return event.mouseX;
	}

	private static int mouseY(GuiScreenEvent.DrawScreenEvent.Post event) {
		//? if >=1.9 {
		/*return event.getMouseY();
		*///?} else
		return event.mouseY;
	}
}
