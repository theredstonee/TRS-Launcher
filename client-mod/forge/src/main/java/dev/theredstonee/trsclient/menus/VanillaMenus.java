package dev.theredstonee.trsclient.menus;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.menus.MenuStyle;
import dev.theredstonee.trsclient.core.menus.ServerPins;
import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.FriendsView;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.menus.MenuSkin;
import dev.theredstonee.trsclient.screen.AccountsScreen;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
//? if >=1.21 {
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
//?} elif >=1.16 {
/*import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.OptionsSubScreen;
*///?} else
/*import net.minecraft.client.gui.screens.OptionsScreen;*/
//? if <1.21.9 {
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
//?}
//? if >=1.20.5 {
import net.minecraft.client.gui.screens.GenericMessageScreen;
//?} else
/*import net.minecraft.client.gui.screens.GenericDirtMessageScreen;*/

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Redstone-Stil für Vanilla-Menüs – die Minecraft-Seite: welches Menü welche Art ist, Hintergrund und Knöpfe
 * zeichnen (aus den Mixins), TRS-Knöpfe im Pausenmenü, Anheften in der Serverliste, Ladebildschirme. Die
 * Menüs selbst bleiben die von Minecraft – mit allen Knöpfen anderer Mods. Dieselbe Datei in allen
 * Mojmap-Bäumen (Fabric, NeoForge, Forge, Forge-Mojmap-Legacy).
 */
public final class VanillaMenus {
	/** Symbole der TRS-Knöpfe (schwach referenziert – die Knöpfe gehören dem Bildschirm). */
	private static final Map<Object, String> ICONS = new WeakHashMap<>();
	private static final Map<Object, Boolean> PIN_BUTTONS = new WeakHashMap<>();
	private static final int PIN_W = 86;

	private VanillaMenus() {
	}

	// --- Einordnen ---

	/** Art des Menüs oder null. */
	public static MenuStyle.Kind kind(Screen s) {
		if (s == null) return null;
		if (s instanceof PauseScreen) return MenuStyle.Kind.PAUSE;
		if (s instanceof JoinMultiplayerScreen) return MenuStyle.Kind.MULTIPLAYER;
		if (s instanceof OptionsScreen || optionsSub(s)) return MenuStyle.Kind.OPTIONS;
		if (s instanceof SelectWorldScreen || s instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen) {
			return MenuStyle.Kind.WORLDS;
		}
		if (loading(s)) return MenuStyle.Kind.LOADING;
		return null;
	}

	/** Unterseiten der Einstellungen (Grafik, Steuerung, …); bis 1.15 ohne gemeinsame Oberklasse. */
	static boolean optionsSub(Screen s) {
		//? if >=1.16 {
		return s instanceof OptionsSubScreen;
		//?} else
		/*return s.getClass().getSimpleName().endsWith("SettingsScreen") || s instanceof net.minecraft.client.gui.screens.controls.ControlsScreen;*/
	}

	static boolean loading(Screen s) {
		if (s instanceof LevelLoadingScreen || s instanceof ConnectScreen || s instanceof ProgressScreen) return true;
		//? if <1.21.9 {
		if (s instanceof ReceivingLevelScreen) return true;
		//?}
		//? if >=1.20.5 {
		return s instanceof GenericMessageScreen;
		//?} else
		/*return s instanceof GenericDirtMessageScreen;*/
	}

	/** Wird dieses Menü im Redstone-Stil gezeichnet? */
	public static boolean styled(Screen s) {
		MenuStyle.Kind k = kind(s);
		return k != null && MenuStyle.enabled(k);
	}

	static boolean inWorld() {
		return Mc.mc().level != null;
	}

	private static Canvas canvas(Gfx g) {
		return GfxCanvas.of(g, Mc.mc().font);
	}

	// --- Hintergrund ---

	/** Aus Screen#renderBackground (HEAD): true = Vanilla-Hintergrund ersetzt. */
	public static boolean drawBackground(final Screen s, Gfx g) {
		final MenuStyle.Kind k = kind(s);
		if (k == null || !MenuStyle.enabled(k)) return false;
		if (k == MenuStyle.Kind.LOADING) return true; // Ladebildschirme zeichnet afterRender komplett
		try {
			final int w = s.width;
			final int h = s.height;
			final int[] list = k == MenuStyle.Kind.PAUSE ? null : listBounds(s);
			final int header = k == MenuStyle.Kind.PAUSE ? 0 : (list != null ? Math.max(0, list[1]) : MenuSkin.HEADER);
			final int footer = list != null ? Math.min(h, list[3]) : h;
			final boolean world = inWorld();
			final Canvas c = canvas(g);
			g.managed(new Runnable() {
				@Override
				public void run() {
					MenuSkin.background(c, w, h, world, header, footer);
					//? if <1.20.5 {
					/*if (list != null) MenuSkin.listWell(c, list[0], list[1], list[2], list[3], world);
					*///?}
				}
			});
			if (k == MenuStyle.Kind.MULTIPLAYER) updatePinButton(s);
			return true;
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Lage der ersten Liste des Bildschirms {x1, y1, x2, y2} oder null. */
	static int[] listBounds(Screen s) {
		for (GuiEventListener child : s.children()) {
			if (child instanceof AbstractSelectionList) return bounds((AbstractSelectionList<?>) child);
		}
		return null;
	}

	static int[] bounds(AbstractSelectionList<?> l) {
		//? if >=1.20.3 {
		return new int[]{l.getX(), l.getY(), l.getX() + l.getWidth(), l.getY() + l.getHeight()};
		//?} else {
		/*dev.theredstonee.trsclient.mixin.SelectionListAccessor a = (dev.theredstonee.trsclient.mixin.SelectionListAccessor) l;
		return new int[]{a.trsclient$x0(), a.trsclient$y0(), a.trsclient$x1(), a.trsclient$y1()};
		*///?}
	}

	/** Liste ab 1.20.5 (renderListBackground): true = ersetzt. */
	public static boolean listBackground(AbstractSelectionList<?> l, Gfx g) {
		if (!styled(Mc.screen())) return false;
		int[] b = bounds(l);
		MenuSkin.listWell(canvas(g), b[0], b[1], b[2], b[3], inWorld());
		return true;
	}

	/** Trennlinien ab 1.20.5 (renderListSeparators): true = ersetzt. */
	public static boolean listSeparators(AbstractSelectionList<?> l, Gfx g) {
		if (!styled(Mc.screen())) return false;
		int[] b = bounds(l);
		MenuSkin.listSeparators(canvas(g), b[0], b[1], b[2], b[3]);
		return true;
	}

	// --- Nach dem Zeichnen: Ladebildschirme ---

	/**
	 * Nach dem ganzen Bildschirm (inkl. Tooltips): Ladebildschirme komplett im Redstone-Stil, darüber die
	 * Sozial-Benachrichtigungen (Toasts) auf jedem Bildschirm.
	 */
	public static void afterRender(Screen s, Gfx g, int mouseX, int mouseY) {
		afterRenderLoading(s, g, mouseX, mouseY);
		dev.theredstonee.trsclient.social.SocialHooks.overScreen(g);
	}

	// --- Welt-Hosting: Pausemenü + Mehrspieler ---

	/** Lage des Abzeichens „Öffentlicher Link aktiv“ im Pausemenü (x, y; -1 = keins). */
	private static volatile int linkBadgeX = -1;
	private static volatile int linkBadgeY = -1;

	public static int linkBadgeX() {
		return linkBadgeX;
	}

	public static int linkBadgeY() {
		return linkBadgeY;
	}

	/**
	 * Pausemenü: „Welt hosten“ (bzw. „Hosting verwalten“) unten links – dort ist in allen Versionen Platz, das
	 * Vanilla-Raster (samt „Im LAN öffnen“) bleibt unverändert. Ist der öffentliche Link an, darüber „Deaktivieren“ und
	 * darüber das rote Abzeichen.
	 */
	static void hostingButtons(final Screen s, WidgetHost host) {
		linkBadgeX = -1;
		boolean hostVisible = dev.theredstonee.trsclient.core.hosting.HostingOverlay.hostButtonVisible();
		boolean link = dev.theredstonee.trsclient.core.hosting.HostingOverlay.linkActive();
		if (!hostVisible && !link) return;
		int y = s.height - 28;
		if (hostVisible) {
			String label = dev.theredstonee.trsclient.core.hosting.HostingOverlay.hostButtonLabel();
			int w = Math.min(150, Math.max(100, Mc.mc().font.width(label) + 34));
			Object b = button(8, y, w, 20, label, new Runnable() {
				@Override
				public void run() {
					Mc.setScreen(MenuScreens.hosting(s));
				}
			});
			ICONS.put(b, "globe");
			host.trsclient$addWidget(b);
			y -= 24;
		}
		if (link) {
			String label = I18n.tr("hosting.link.deactivateLong");
			int w = Math.min(170, Math.max(100, Mc.mc().font.width(label) + 34));
			Object d = button(8, y, w, 20, label, new Runnable() {
				@Override
				public void run() {
					dev.theredstonee.trsclient.core.hosting.HostingOverlay.disableLink();
					Mc.setScreen(s);
				}
			});
			ICONS.put(d, "lock");
			host.trsclient$addWidget(d);
			// Das rote Abzeichen „Öffentlicher Link aktiv“ steht oben mittig (HUD, auch unter dem Pausemenü sichtbar).
		}
	}

	/** Mehrspieler: „Mit Code beitreten“ oben links. */
	static void joinButton(final Screen s, WidgetHost host) {
		if (dev.theredstonee.trsclient.core.hosting.Hosting.current() == null) return;
		if (!dev.theredstonee.trsclient.core.hosting.Hosting.platform().channelConnect()) return;
		String label = I18n.tr("hosting.mp.button");
		int w = Math.min(140, Math.max(90, Mc.mc().font.width(label) + 30));
		Object b = button(6, 6, w, 20, label, new Runnable() {
			@Override
			public void run() {
				Mc.setScreen(MenuScreens.join(s));
			}
		});
		ICONS.put(b, "globe");
		host.trsclient$addWidget(b);
	}

	private static void afterRenderLoading(Screen s, Gfx g, int mouseX, int mouseY) {
		if (s == null || !loading(s) || !MenuStyle.enabled(MenuStyle.Kind.LOADING)) return;
		try {
			String title;
			if (s instanceof LevelLoadingScreen) title = I18n.tr("menus.loading.world");
			else if (s instanceof ConnectScreen) title = I18n.tr("menus.loading.connecting");
			else if (s instanceof ProgressScreen) title = I18n.tr("menus.loading.generic");
			else {
				String t = s.getTitle() == null ? "" : s.getTitle().getString();
				//? if <1.21.9 {
				if (s instanceof ReceivingLevelScreen) t = I18n.tr("menus.loading.terrain");
				//?}
				title = t.isEmpty() ? I18n.tr("menus.loading.generic") : t;
			}
			String detail = null;
			if (s instanceof ConnectScreen) {
				ServerData data = Mc.mc().getCurrentServer();
				if (data != null) detail = data.name;
			}
			g.overlayLayer();
			g.push();
			g.raise(400f);
			Canvas c = canvas(g);
			final String ft = title;
			final String fd = detail;
			final int w = s.width;
			final int h = s.height;
			final boolean world = inWorld() && !(s instanceof LevelLoadingScreen);
			final Canvas fc = c;
			// Bereich der Knöpfe (z. B. „Abbrechen“ bei h/4+132): Logo, Titel und Lampen darüber, nie darunter.
			int top = Integer.MAX_VALUE;
			int bottom = -1;
			for (GuiEventListener child : s.children()) {
				if (child instanceof AbstractWidget && ((AbstractWidget) child).visible) {
					int[] r = rect((AbstractWidget) child);
					top = Math.min(top, r[1]);
					bottom = Math.max(bottom, r[1] + r[3]);
				}
			}
			final int avoidTop = bottom < 0 ? -1 : top;
			final int avoidBottom = bottom;
			g.managed(new Runnable() {
				@Override
				public void run() {
					MenuSkin.loading(fc, w, h, ft, fd, -1f, world, avoidTop, avoidBottom);
				}
			});
			// Knöpfe (z. B. „Abbrechen“ beim Verbinden) liegen unter der Fläche – im Stil neu zeichnen.
			for (GuiEventListener child : s.children()) {
				if (child instanceof AbstractWidget) {
					AbstractWidget b = (AbstractWidget) child;
					if (!b.visible) continue;
					int[] r = rect(b);
					boolean hover = mouseX >= r[0] && mouseY >= r[1] && mouseX < r[0] + r[2] && mouseY < r[1] + r[3];
					MenuSkin.button(c, r[0], r[1], r[2], r[3], hover, b.active);
					label(g, message(b), r[0], r[1], r[2], r[3], b.active);
				}
			}
			g.pop();
		} catch (RuntimeException | LinkageError e) {
			// Ein Fehler im Stil darf das Laden nie stören.
		}
	}

	// --- Knöpfe ---

	/**
	 * Aus dem Knopf-Mixin: Fläche im Redstone-Stil zeichnen, wenn der offene Bildschirm gestylt ist.
	 * Rückgabe true = Vanilla-Fläche nicht zeichnen.
	 */
	public static boolean drawButton(Object widget, Gfx g, int x, int y, int w, int h, boolean hover, boolean active) {
		if (!styled(Mc.screen())) return false;
		try {
			Canvas c = canvas(g);
			MenuSkin.button(c, x, y, w, h, hover, active);
			String icon = ICONS.get(widget);
			if (icon != null) {
				MenuSkin.buttonIcon(c, x, y, h, icon, active);
				if ("friends".equals(icon)) {
					TrsOnline online = TrsOnline.current();
					if (online != null) {
						online.friends().want(Friends.Interest.BACKGROUND, false);
						// Anfragen + Umhang-Angebote + ungelesene Chat-Nachrichten.
						MenuSkin.badge(c, x + w, y, online.friends().snapshot().incoming()
								+ online.social().store().unreadTotal());
					}
				}
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Beschriftung wie Vanilla (für Versionen, in denen der Mixin sie selbst zeichnen muss). */
	public static void label(Gfx g, String message, int x, int y, int w, int h, boolean active) {
		int color = active ? 0xFFFFFFFF : 0xFFA0A0A0;
		Minecraft mc = Mc.mc();
		String s = message == null ? "" : message;
		if (mc.font.width(s) > w - 6) s = Gfx.clip(mc.font, s, w - 6);
		g.centered(mc.font, s, x + w / 2, y + (h - 8) / 2, color);
	}

	/** {x, y, w, h} eines Widgets. */
	static int[] rect(AbstractWidget b) {
		//? if >=1.19.3 {
		return new int[]{b.getX(), b.getY(), b.getWidth(), b.getHeight()};
		//?} elif >=1.16 {
		/*return new int[]{b.x, b.y, b.getWidth(), b.getHeight()};
		*///?} else
		/*return new int[]{b.x, b.y, b.getWidth(), 20};*/
	}

	/** Linke Kante eines Widgets (ab 1.19.3 sind x/y privat). */
	public static int x(AbstractWidget b) {
		//? if >=1.19.3 {
		return b.getX();
		//?} else
		/*return b.x;*/
	}

	/** Obere Kante eines Widgets (ab 1.19.3 sind x/y privat). */
	public static int y(AbstractWidget b) {
		//? if >=1.19.3 {
		return b.getY();
		//?} else
		/*return b.y;*/
	}

	// --- Nach init(): Listen, TRS-Knöpfe, Anheften ---

	/** Nach Screen#init bzw. #rebuildWidgets. */
	public static void afterInit(Screen s, WidgetHost host) {
		MenuStyle.Kind k = kind(s);
		if (k == null) return;
		try {
			if (MenuStyle.enabled(k)) {
				for (GuiEventListener child : s.children()) {
					if (child instanceof AbstractSelectionList) plainList((AbstractSelectionList<?>) child);
				}
			}
			if (k == MenuStyle.Kind.PAUSE && MenuStyle.pauseButtons() && !s.children().isEmpty()) pauseButtons(s, host);
			if (k == MenuStyle.Kind.PAUSE && s instanceof PauseScreen && !s.children().isEmpty()) hostingButtons(s, host);
			if (k == MenuStyle.Kind.MULTIPLAYER && s instanceof JoinMultiplayerScreen) joinButton(s, host);
			if (k == MenuStyle.Kind.MULTIPLAYER && MenuStyle.enabled(k) && s instanceof JoinMultiplayerScreen) {
				applyPins((JoinMultiplayerScreen) s);
				int x = s.width - PIN_W - 6;
				Object pin = button(x, 6, PIN_W, 20, I18n.tr("menus.pin"), new Runnable() {
					@Override
					public void run() {
						togglePin();
					}
				});
				ICONS.put(pin, "pin");
				PIN_BUTTONS.put(pin, Boolean.TRUE);
				host.trsclient$addWidget(pin);
			}
		} catch (RuntimeException | LinkageError e) {
			// Menü bleibt dann eben klassisch.
		}
	}

	/** Bis 1.20.4 zeichnen Listen Erde hinter sich und oben/unten – das übernimmt der Redstone-Hintergrund
	 * (1.16.2/1.16.3 haben die Schalter noch nicht, dort bleibt die Erde in der Liste). */
	static void plainList(AbstractSelectionList<?> l) {
		//? if >=1.16.4 && <1.20.5 {
		/*l.setRenderBackground(false);
		*///?}
		//? if >=1.16.4 && <1.20.2 {
		/*l.setRenderTopAndBottom(false);
		*///?}
	}

	/** Beschriftung eines Widgets als Text. */
	static String message(AbstractWidget b) {
		//? if >=1.16 {
		return b.getMessage() == null ? "" : b.getMessage().getString();
		//?} else
		/*return b.getMessage() == null ? "" : b.getMessage();*/
	}

	static void setMessage(AbstractWidget b, String text) {
		//? if >=1.16 {
		b.setMessage(text(text));
		//?} else
		/*b.setMessage(text);*/
	}

	/** Knöpfe Garderobe, Konten, Clips, Freunde, Server-Info links neben dem Pausenmenü. */
	static void pauseButtons(final Screen s, WidgetHost host) {
		int free = (s.width - 204) / 2 - 12;
		String[] ids = {"wardrobe", "accounts", "clips", "friends", "serverInfo"};
		int widest = 0;
		for (String id : ids) widest = Math.max(widest, Mc.mc().font.width(I18n.tr("menus.pause." + id)));
		// Platz für Symbol links + Text in der Mitte: Text darf das Symbol nicht berühren.
		int want = widest + 32;
		boolean labels = free >= Math.min(want, 150);
		if (free < 22) return;
		int w = labels ? Math.max(86, Math.min(want, free)) : 20;
		int h = 20;
		int gap = 4;
		int count = 5;
		int y = Math.max(8, s.height / 2 - (count * (h + gap)) / 2);
		int x = 8;
		String[] icons = {"shirt", "profile", "image", "friends", "info"};
		for (int i = 0; i < ids.length; i++) {
			final String id = ids[i];
			Object b = button(x, y, w, h, labels ? I18n.tr("menus.pause." + id) : "", new Runnable() {
				@Override
				public void run() {
					openFromPause(s, id);
				}
			});
			ICONS.put(b, icons[i]);
			if (b instanceof AbstractWidget) ((AbstractWidget) b).active = available(id, s);
			host.trsclient$addWidget(b);
			y += h + gap;
		}
	}

	static boolean available(String id, Screen parent) {
		switch (id) {
			case "wardrobe":
				return MenuScreens.wardrobe(parent) != null;
			case "accounts":
				return AccountsScreen.available();
			case "friends":
				return MenuScreens.friendsAvailable();
			case "serverInfo":
				return MenuScreens.serverInfoAvailable();
			default:
				return true;
		}
	}

	static void openFromPause(Screen parent, String id) {
		Screen next;
		switch (id) {
			case "wardrobe":
				next = MenuScreens.wardrobe(parent);
				break;
			case "accounts":
				next = AccountsScreen.available() ? AccountsScreen.create(parent) : null;
				break;
			case "clips":
				next = MenuScreens.clips(parent);
				break;
			case "friends":
				next = MenuScreens.friendsAvailable() ? MenuScreens.friends(parent) : null;
				break;
			default:
				next = MenuScreens.serverInfo(parent);
				break;
		}
		if (next != null) Mc.setScreen(next);
	}

	/** Vanilla-Knopf dieser Version. */
	static Object button(int x, int y, int w, int h, String label, final Runnable action) {
		//? if >=1.19.3 {
		return Button.builder(text(label), b -> action.run()).bounds(x, y, w, h).build();
		//?} elif >=1.16 {
		/*return new Button(x, y, w, h, text(label), b -> action.run());
		*///?} else
		/*return new Button(x, y, w, h, label, b -> action.run());*/
	}

	static Component text(String s) {
		//? if >=1.19 {
		return Component.literal(s);
		//?} else
		/*return new net.minecraft.network.chat.TextComponent(s);*/
	}

	// --- Serverliste: Anheften ---

	private static ServerSelectionList serverList(Screen s) {
		return ((dev.theredstonee.trsclient.mixin.JoinMultiplayerAccessor) s).trsclient$serverList();
	}

	static ServerPins pins() {
		Path config = Mc.mc().gameDirectory.toPath().toAbsolutePath().resolve("config");
		return ServerPins.shared(config);
	}

	/** Ausgewählter Server der Liste oder null. */
	static ServerData selected(Screen s) {
		ServerSelectionList list = serverList(s);
		if (list == null) return null;
		Object e = list.getSelected();
		if (e instanceof ServerSelectionList.OnlineServerEntry) return ((ServerSelectionList.OnlineServerEntry) e).getServerData();
		return null;
	}

	/** Angeheftete Server nach oben (Vanilla-Tausch + speichern), dann Liste neu aufbauen. */
	static void applyPins(JoinMultiplayerScreen s) {
		ServerList servers = s.getServers();
		if (servers == null) return;
		List<String> addresses = new ArrayList<>();
		for (int i = 0; i < servers.size(); i++) addresses.add(servers.get(i).ip);
		List<int[]> swaps = pins().swaps(addresses);
		if (swaps.isEmpty()) return;
		for (int[] sw : swaps) servers.swap(sw[0], sw[1]);
		servers.save();
		ServerSelectionList list = serverList(s);
		if (list != null) list.updateOnlineServers(servers);
	}

	static void togglePin() {
		Screen s = Mc.screen();
		if (!(s instanceof JoinMultiplayerScreen)) return;
		ServerData data = selected(s);
		if (data == null) return;
		pins().toggle(data.ip);
		applyPins((JoinMultiplayerScreen) s);
		updatePinButton(s);
	}

	/** Beschriftung/Freigabe des Anheften-Knopfs zur Auswahl passend halten (billig, jedes Bild). */
	static void updatePinButton(Screen s) {
		ServerData data = selected(s);
		boolean pinned = data != null && pins().isPinned(data.ip);
		String want = I18n.tr(pinned ? "menus.unpin" : "menus.pin");
		for (GuiEventListener child : s.children()) {
			if (child instanceof AbstractWidget && PIN_BUTTONS.containsKey(child)) {
				AbstractWidget b = (AbstractWidget) child;
				b.active = data != null;
				if (!want.equals(message(b))) setMessage(b, want);
			}
		}
	}

	// --- Serverliste: Karten ---

	/** Vor dem Vanilla-Eintrag: Karte (Fläche) zeichnen. */
	public static void serverEntryBefore(Gfx g, Object entry, ServerData data, int x, int y, int w, int h, boolean hovered) {
		Screen s = Mc.screen();
		if (!(s instanceof JoinMultiplayerScreen) || !MenuStyle.enabled(MenuStyle.Kind.MULTIPLAYER)) return;
		try {
			ServerSelectionList list = serverList(s);
			boolean selected = list != null && list.getSelected() == entry;
			boolean pinned = data != null && pins().isPinned(data.ip);
			MenuSkin.card(canvas(g), x - 2, y - 2, w + 4, h + 4, selected, hovered, pinned);
		} catch (RuntimeException | LinkageError ignored) {
			// klassisch weiter
		}
	}

	/** Nach dem Vanilla-Eintrag: Ping als Staub-Balken, Anheft-Fackel, Freunde auf dem Server. */
	public static void serverEntryAfter(Gfx g, Object entry, ServerData data, int x, int y, int w, int h, boolean hovered) {
		Screen s = Mc.screen();
		if (!(s instanceof JoinMultiplayerScreen) || !MenuStyle.enabled(MenuStyle.Kind.MULTIPLAYER) || data == null) return;
		try {
			Canvas c = canvas(g);
			ServerSelectionList list = serverList(s);
			boolean selected = list != null && list.getSelected() == entry;
			if (pingOk(data)) MenuSkin.pingBars(c, x + w - 15, y, data.ping, false, MenuSkin.cardFill(selected, hovered));
			int right = x + w - 2;
			if (pins().isPinned(data.ip)) {
				MenuSkin.pin(c, right - 9, y + h - 14);
				right -= 12;
			}
			TrsOnline online = TrsOnline.current();
			if (online != null && online.online()) {
				online.friends().want(Friends.Interest.BACKGROUND, false);
				FriendsView view = online.friends().snapshot().view;
				List<FriendsView.Friend> here = view == null ? null : view.onServer(data.ip);
				if (here != null && !here.isEmpty()) {
					FaceCache faces = FaceCache.shared("TRS-Client");
					int n = Math.min(3, here.size());
					int fx = right - n * 10;
					int fy = y + h - 10;
					for (int i = 0; i < n; i++) {
						FriendsView.Friend f = here.get(i);
						Faces.draw(c, faces.face(f.uuid, null), f.uuid, f.name, fx + i * 10, fy, 1, true);
					}
					if (here.size() > n) {
						String more = "+" + (here.size() - n);
						c.text(more, fx - c.textWidth(more) - 2, fy, 0xFFFFFFFF, true);
					}
				}
			}
		} catch (RuntimeException | LinkageError ignored) {
			// klassisch weiter
		}
	}

	/** Server hat geantwortet (sonst bleibt die Vanilla-Anzeige: Suchen bzw. rotes Kreuz)? */
	static boolean pingOk(ServerData data) {
		//? if >=1.20.5 {
		return data.ping >= 0 && data.state() == ServerData.State.SUCCESSFUL;
		//?} else
		/*return data.ping >= 0 && data.pinged;*/
	}

	/** Welt-Eintrag: Karte hinter dem Vanilla-Inhalt. */
	public static void worldEntryBefore(Gfx g, Object entry, int x, int y, int w, int h, boolean hovered) {
		Screen s = Mc.screen();
		if (!(s instanceof SelectWorldScreen) || !MenuStyle.enabled(MenuStyle.Kind.WORLDS)) return;
		try {
			boolean selected = false;
			for (GuiEventListener child : s.children()) {
				if (child instanceof AbstractSelectionList) {
					selected = ((AbstractSelectionList<?>) child).getSelected() == entry;
					break;
				}
			}
			MenuSkin.card(canvas(g), x - 2, y - 2, w + 4, h + 4, selected, hovered, false);
		} catch (RuntimeException | LinkageError ignored) {
			// klassisch weiter
		}
	}

	// --- Ressourcen laden (Mojang-Logo) ---

	/** Letztes Bild der Überblendung (für Selbsttests): „in“, „load“, „hold“, „out“; null = keins seitdem. */
	public static volatile String overlayPhase;
	/** Deckkraft der Überblendung in diesem Bild. */
	public static volatile float overlayAlpha;

	/** Ressourcen-Überblendung im Redstone-Stil (Menü-Stil → Ladebildschirme an)? */
	public static boolean resourceOverlayStyled() {
		return MenuStyle.enabled(MenuStyle.Kind.LOADING);
	}

	/** Was unter der Überblendung liegt: der offene Bildschirm ({@code screen}) bzw. nur die Untertitel. */
	public interface OverlayUnder {
		void draw(boolean screen, int mouseX, int mouseY, float partialTick);
	}

	/**
	 * Aus LoadingOverlay#render (statt Vanilla): eigene Überblendung mit der Deckkraft {@code alpha}.
	 * Rückgabe false = Zeichnen fehlgeschlagen (Aufrufer blendet schlicht ab).
	 */
	public static boolean resourceOverlay(Gfx g, int width, int height, float progress, float alpha) {
		if (!resourceOverlayStyled()) return false;
		boolean pushed = false;
		try {
			g.overlayLayer();
			g.push();
			pushed = true;
			g.raise(400f);
			final Canvas c = canvas(g);
			final int w = width;
			final int h = height;
			final float p = progress;
			final float a = alpha;
			g.managed(new Runnable() {
				@Override
				public void run() {
					MenuSkin.resourceOverlay(c, w, h, p, a);
				}
			});
			return true;
		} catch (RuntimeException | LinkageError e) {
			return false;
		} finally {
			if (pushed) g.pop();
		}
	}
}
