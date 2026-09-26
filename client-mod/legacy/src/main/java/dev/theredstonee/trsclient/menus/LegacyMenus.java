package dev.theredstonee.trsclient.menus;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.menus.MenuStyle;
import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.menus.MenuSkin;
import dev.theredstonee.trsclient.screen.AccountsScreen;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiCustomizeSkin;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiLanguage;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.ScreenChatOptions;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Redstone-Stil für Vanilla-Menüs unter Minecraft 1.8.9–1.12.2 (ohne Mixins, nur Forge-Ereignisse):
 * Hintergrund von Pausenmenü und Einstellungen ({@code BackgroundDrawnEvent}), Knopfflächen im Stil
 * (nach dem Vanilla-Zeichnen übermalt, Beschriftung neu), Ladebildschirme komplett, TRS-Knöpfe im
 * Pausenmenü. Die Menüs selbst – und Knöpfe anderer Mods – bleiben unverändert bedienbar.
 */
public final class LegacyMenus {
	/** Knopf-IDs der TRS-Knöpfe im Pausenmenü (weit weg von Vanilla- und üblichen Mod-IDs). */
	static final int FIRST_ID = 73_101;
	static final String[] IDS = {"wardrobe", "accounts", "clips", "friends", "serverInfo"};
	static final String[] ICONS = {"shirt", "profile", "image", "friends", "info"};
	/** Welt-Hosting: „Welt hosten“ (Pause), „Öffentlichen Link deaktivieren“ (Pause), „Mit Code beitreten“ (Mehrspieler). */
	static final int HOST_ID = 73_120;
	static final int LINK_ID = 73_121;
	static final int JOIN_ID = 73_122;
	/** Lage des Abzeichens „Öffentlicher Link aktiv“ im Pausemenü (x, y; -1 = keins). */
	static volatile int linkBadgeX = -1;
	static volatile int linkBadgeY = -1;

	/** Knopflisten der offenen Bildschirme (aus InitGuiEvent, schwach referenziert). */
	private final Map<GuiScreen, List<GuiButton>> buttons = new WeakHashMap<GuiScreen, List<GuiButton>>();
	private final Map<GuiButton, String> icons = new WeakHashMap<GuiButton, String>();
	private Class<?> worldsClass;
	private Field heightField;
	private boolean heightFailed;

	// --- Einordnen ---

	MenuStyle.Kind kind(GuiScreen s) {
		if (s == null) return null;
		if (s instanceof GuiIngameMenu) return MenuStyle.Kind.PAUSE;
		if (s instanceof GuiMultiplayer) return MenuStyle.Kind.MULTIPLAYER;
		if (s instanceof GuiOptions || s instanceof GuiVideoSettings || s instanceof GuiControls
				|| s instanceof GuiScreenOptionsSounds || s instanceof GuiLanguage || s instanceof ScreenChatOptions
				|| s instanceof GuiCustomizeSkin) {
			return MenuStyle.Kind.OPTIONS;
		}
		if (worldsClass == null) worldsClass = Mc.worldSelectScreen(null).getClass();
		if (s.getClass() == worldsClass) return MenuStyle.Kind.WORLDS;
		if (s instanceof GuiDownloadTerrain || s instanceof GuiConnecting) return MenuStyle.Kind.LOADING;
		return null;
	}

	boolean styled(GuiScreen s) {
		MenuStyle.Kind k = kind(s);
		return k != null && MenuStyle.enabled(k);
	}

	// --- Ereignisse ---

	@SubscribeEvent
	public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
		GuiScreen s = Mc.eventGui(event);
		List<GuiButton> list = buttonList(event);
		if (s == null || list == null) return;
		MenuStyle.Kind k = kind(s);
		if (k == null) return;
		buttons.put(s, list);
		try {
			if (k == MenuStyle.Kind.PAUSE && MenuStyle.pauseButtons()) pauseButtons(s, list);
		} catch (RuntimeException e) {
			// Menü bleibt dann klassisch.
		}
		try {
			if (k == MenuStyle.Kind.PAUSE) hostingButtons(s, list);
			if (k == MenuStyle.Kind.MULTIPLAYER) joinButton(s, list);
		} catch (RuntimeException | LinkageError e) {
			// ohne Hosting-Knöpfe weiter
		}
	}

	@SubscribeEvent
	public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
		GuiButton b = button(event);
		GuiScreen s = Mc.eventGui(event);
		if (b != null && s != null && (b.id == HOST_ID || b.id == LINK_ID || b.id == JOIN_ID) && icons.containsKey(b)) {
			event.setCanceled(true);
			if (b.id == HOST_ID) Mc.setScreen(MenuScreens.hosting(s));
			else if (b.id == JOIN_ID) Mc.setScreen(MenuScreens.join(s));
			else {
				dev.theredstonee.trsclient.core.hosting.HostingOverlay.disableLink();
				Mc.setScreen(new GuiIngameMenu());
			}
			return;
		}
		if (b == null || s == null || !(s instanceof GuiIngameMenu)) return;
		int index = b.id - FIRST_ID;
		if (index < 0 || index >= IDS.length || !icons.containsKey(b)) return;
		event.setCanceled(true);
		GuiScreen next = open(s, IDS[index]);
		if (next != null) Mc.setScreen(next);
	}

	/** Nach dem Vanilla-Hintergrund (drawDefaultBackground): Redstone-Hintergrund darüber. */
	@SubscribeEvent
	public void onBackground(GuiScreenEvent.BackgroundDrawnEvent event) {
		GuiScreen s = Mc.eventGui(event);
		MenuStyle.Kind k = kind(s);
		if (k == null || k == MenuStyle.Kind.LOADING || !MenuStyle.enabled(k)) return;
		// Listen-Bildschirme übermalt die Liste ohnehin – dort zeichnet onDrawn Kopf und Fuß.
		if (k == MenuStyle.Kind.MULTIPLAYER || k == MenuStyle.Kind.WORLDS) return;
		try {
			Canvas c = canvas(s);
			int header = k == MenuStyle.Kind.PAUSE ? 0 : MenuSkin.HEADER;
			MenuSkin.background(c, s.width, s.height, Mc.world() != null, header, s.height);
		} catch (RuntimeException e) {
			// klassisch weiter
		}
	}

	/** Nach dem ganzen Bildschirm: Knopfflächen im Stil, Ladebildschirme komplett. */
	@SubscribeEvent
	public void onDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
		GuiScreen s = Mc.eventGui(event);
		MenuStyle.Kind k = kind(s);
		if (k == null || !MenuStyle.enabled(k)) return;
		try {
			int mouseX = mouseX(event);
			int mouseY = mouseY(event);
			Canvas c = canvas(s);
			if (k == MenuStyle.Kind.LOADING) {
				String title = I18n.tr(s instanceof GuiConnecting ? "menus.loading.connecting" : "menus.loading.terrain");
				// „Abbrechen“ (h/4+132) freihalten: Logo, Titel und Lampen stehen darüber.
				int top = Integer.MAX_VALUE;
				int bottom = -1;
				List<GuiButton> own = buttons.get(s);
				if (own != null) {
					for (GuiButton b : own) {
						if (!b.visible) continue;
						top = Math.min(top, y(b));
						bottom = Math.max(bottom, y(b) + height(b));
					}
				}
				MenuSkin.loading(c, s.width, s.height, title, null, -1f, false, bottom < 0 ? -1 : top, bottom);
			} else if (k == MenuStyle.Kind.MULTIPLAYER || k == MenuStyle.Kind.WORLDS) {
				// Die Liste (GuiSlot) übermalt alles mit Erde – Kopf- und Fußleiste im Stil neu zeichnen.
				listFrame(s, c, k);
			}
			List<GuiButton> list = buttons.get(s);
			if (list == null) return;
			FontRenderer font = Mc.font();
			for (GuiButton b : list) {
				if (!b.visible) continue;
				int x = x(b);
				int y = y(b);
				int w = b.width;
				int h = height(b);
				boolean hover = mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;
				MenuSkin.button(c, x, y, w, h, hover, b.enabled);
				String icon = icons.get(b);
				if (icon != null) {
					MenuSkin.buttonIcon(c, x, y, h, icon, b.enabled);
					if ("friends".equals(icon)) {
						TrsOnline online = TrsOnline.current();
						if (online != null) {
							online.friends().want(Friends.Interest.BACKGROUND, false);
							// Anfragen/Angebote + ungelesene Chat-Nachrichten („Sozial“).
							MenuSkin.badge(c, x + w, y, online.friends().snapshot().incoming()
									+ online.social().store().unreadTotal());
						}
					}
				}
				String label = b.displayString == null ? "" : b.displayString;
				int left = icon != null ? 17 : 3;
				int room = icon != null ? w - left - 3 : w - 6;
				if (font.getStringWidth(label) > room) label = font.trimStringToWidth(label, room);
				int color = !b.enabled ? 0xFFA0A0A0 : (hover ? 0xFFFFFFA0 : 0xFFE0E0E0);
				float lx = icon != null ? x + left : x + (w - font.getStringWidth(label)) / 2f;
				font.drawStringWithShadow(label, lx, y + (h - 8) / 2f, color);
			}
		} catch (RuntimeException e) {
			// klassisch weiter
		}
	}

	/** Kopf- und Fußleiste um die Liste (oben 32, unten 64 Pixel wie Vanilla) samt Titel. */
	private void listFrame(GuiScreen s, Canvas c, MenuStyle.Kind k) {
		int top = 32;
		int bottom = s.height - 64;
		boolean world = Mc.world() != null;
		c.scissor(0, 0, s.width, top);
		MenuSkin.background(c, s.width, s.height, world, top, s.height);
		c.noScissor();
		c.scissor(0, bottom, s.width, s.height);
		MenuSkin.background(c, s.width, s.height, world, 0, bottom);
		c.noScissor();
		String title = net.minecraft.client.resources.I18n.format(k == MenuStyle.Kind.MULTIPLAYER ? "multiplayer.title" : "selectWorld.title");
		FontRenderer font = Mc.font();
		font.drawStringWithShadow(title, (s.width - font.getStringWidth(title)) / 2f, 20, 0xFFFFFFFF);
	}

	// --- TRS-Knöpfe im Pausenmenü ---

	void pauseButtons(GuiScreen s, List<GuiButton> list) {
		int free = (s.width - 204) / 2 - 12;
		FontRenderer font = Mc.font();
		int widest = 0;
		for (String id : IDS) widest = Math.max(widest, font.getStringWidth(I18n.tr("menus.pause." + id)));
		// Beschriftung steht hier links neben dem Symbol (selbst gezeichnet) – braucht weniger Platz als mittig.
		int want = widest + 24;
		boolean labels = free >= Math.min(want, 150);
		if (free < 22) return;
		int w = labels ? Math.min(Math.max(want, 86), free) : 20;
		int h = 20;
		int gap = 4;
		int y = Math.max(8, s.height / 2 - (IDS.length * (h + gap)) / 2);
		for (int i = 0; i < IDS.length; i++) {
			GuiButton b = new GuiButton(FIRST_ID + i, 8, y, w, h, labels ? I18n.tr("menus.pause." + IDS[i]) : "");
			b.enabled = available(IDS[i], s);
			icons.put(b, ICONS[i]);
			list.add(b);
			y += h + gap;
		}
	}

	/** Pausemenü: „Welt hosten“ unten links; bei öffentlichem Link darüber „Deaktivieren“ + Abzeichen. */
	void hostingButtons(GuiScreen s, List<GuiButton> list) {
		linkBadgeX = -1;
		boolean hostVisible = dev.theredstonee.trsclient.core.hosting.HostingOverlay.hostButtonVisible();
		boolean link = dev.theredstonee.trsclient.core.hosting.HostingOverlay.linkActive();
		FontRenderer font = Mc.font();
		int y = s.height - 28;
		if (hostVisible) {
			String label = dev.theredstonee.trsclient.core.hosting.HostingOverlay.hostButtonLabel();
			GuiButton b = new GuiButton(HOST_ID, 8, y, Math.min(150, Math.max(100, font.getStringWidth(label) + 34)), 20, label);
			icons.put(b, "globe");
			list.add(b);
			y -= 24;
		}
		if (link) {
			String label = I18n.tr("hosting.link.deactivate");
			GuiButton d = new GuiButton(LINK_ID, 8, y, Math.min(150, Math.max(100, font.getStringWidth(label) + 30)), 20, label);
			icons.put(d, "lock");
			list.add(d);
			linkBadgeX = 8;
			linkBadgeY = y - 17;
		}
	}

	/** Mehrspieler: „Mit Code beitreten“ oben links (nur mit TRS-Online-Funktionen). */
	void joinButton(GuiScreen s, List<GuiButton> list) {
		if (dev.theredstonee.trsclient.core.hosting.Hosting.current() == null) return;
		String label = I18n.tr("hosting.mp.button");
		GuiButton b = new GuiButton(JOIN_ID, 6, 6, Math.min(140, Math.max(90, Mc.font().getStringWidth(label) + 30)), 20, label);
		icons.put(b, "globe");
		list.add(b);
	}

	public static int linkBadgeX() {
		return linkBadgeX;
	}

	public static int linkBadgeY() {
		return linkBadgeY;
	}

	static boolean available(String id, GuiScreen parent) {
		if ("wardrobe".equals(id)) return MenuScreens.wardrobe(parent) != null;
		if ("accounts".equals(id)) return AccountsScreen.available();
		if ("friends".equals(id)) return MenuScreens.friendsAvailable();
		if ("serverInfo".equals(id)) return MenuScreens.serverInfoAvailable();
		return true;
	}

	static GuiScreen open(GuiScreen parent, String id) {
		if ("wardrobe".equals(id)) return MenuScreens.wardrobe(parent);
		if ("accounts".equals(id)) return AccountsScreen.available() ? AccountsScreen.create(parent) : null;
		if ("clips".equals(id)) return MenuScreens.clips(parent);
		if ("friends".equals(id)) return MenuScreens.friendsAvailable() ? MenuScreens.friends(parent) : null;
		return MenuScreens.serverInfo(parent);
	}

	// --- Versionsweichen ---

	private static Canvas canvas(GuiScreen s) {
		return GfxCanvas.of(Gfx.of(s.width, s.height), Mc.font());
	}

	@SuppressWarnings("unchecked")
	private static List<GuiButton> buttonList(GuiScreenEvent.InitGuiEvent.Post event) {
		//? if >=1.9 {
		/*return event.getButtonList();
		*///?} else
		return event.buttonList;
	}

	private static GuiButton button(GuiScreenEvent.ActionPerformedEvent.Pre event) {
		//? if >=1.9 {
		/*return event.getButton();
		*///?} else
		return event.button;
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

	private static int x(GuiButton b) {
		//? if >=1.11 {
		/*return b.x;
		*///?} else
		return b.xPosition;
	}

	private static int y(GuiButton b) {
		//? if >=1.11 {
		/*return b.y;
		*///?} else
		return b.yPosition;
	}

	/** Höhe ist bis 1.12.2 geschützt – einmal per Reflection (MCP- oder SRG-Name) gesucht. */
	private int height(GuiButton b) {
		if (heightField == null && !heightFailed) {
			try {
				heightField = ReflectionHelper.findField(GuiButton.class, "height", "field_146121_g");
			} catch (RuntimeException e) {
				heightFailed = true;
			}
		}
		if (heightField != null) {
			try {
				return heightField.getInt(b);
			} catch (IllegalAccessException | RuntimeException e) {
				heightFailed = true;
				heightField = null;
			}
		}
		return 20;
	}
}
