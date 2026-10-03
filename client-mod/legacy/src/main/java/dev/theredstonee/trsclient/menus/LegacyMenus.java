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
import net.minecraft.client.gui.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerList;
import dev.theredstonee.trsclient.core.net.PingPanel;
import dev.theredstonee.trsclient.core.net.ServerPingTest;
import dev.theredstonee.trsclient.core.net.StatusPing;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenAddServer;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.client.gui.GuiScreenServerList;
import net.minecraft.client.gui.GuiShareToLan;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.ScreenChatOptions;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Redstone-Stil für Vanilla-Menüs unter Minecraft 1.8.9–1.12.2 (ohne Mixins, nur Forge-Ereignisse):
 * Hintergrund von Pausenmenü und Einstellungen ({@code BackgroundDrawnEvent}), Knopfflächen im Stil
 * (nach dem Vanilla-Zeichnen übermalt, Beschriftung neu), Ladebildschirme komplett, TRS-Knöpfe im
 * Pausenmenü. Formulare (Direkt verbinden, Server hinzufügen/bearbeiten) bekommen eine Fläche hinter den
 * Feldern, Textfelder die TRS-Mulde, Bestätigungen ({@code GuiYesNo}) den Stil des Menüs, aus dem sie kommen.
 * Die Menüs selbst – und Knöpfe anderer Mods – bleiben unverändert bedienbar.
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
	/** Mehrspieler: „Ping-Test“ (alle Server messen und nach Ping sortieren). */
	static final int PING_ID = 73_123;
	private final Map<GuiButton, Boolean> compactPing = new WeakHashMap<GuiButton, Boolean>();
	/** Nach dem laufenden Test einmal sortieren. */
	private boolean pingSortPending;
	private Field serverListField;
	private boolean serverListSearched;
	/** Lage des Abzeichens „Öffentlicher Link aktiv“ im Pausemenü (x, y; -1 = keins). */
	static volatile int linkBadgeX = -1;
	static volatile int linkBadgeY = -1;

	/** Knopflisten der offenen Bildschirme (aus InitGuiEvent, schwach referenziert). */
	private final Map<GuiScreen, List<GuiButton>> buttons = new WeakHashMap<GuiScreen, List<GuiButton>>();
	private final Map<GuiButton, String> icons = new WeakHashMap<GuiButton, String>();
	private Class<?> worldsClass;
	private static volatile LegacyMenus current;

	public LegacyMenus() {
		current = this;
	}

	/** Die registrierte Instanz (für Selbsttests). */
	public static LegacyMenus current() {
		return current;
	}
	private Field heightField;
	private boolean heightFailed;

	// --- Einordnen ---

	MenuStyle.Kind kind(GuiScreen s) {
		if (s == null) return null;
		if (dialog(s)) return dialogKind(s);
		if (s instanceof GuiIngameMenu || s instanceof GuiShareToLan) return MenuStyle.Kind.PAUSE;
		if (s instanceof GuiMultiplayer || serverForm(s)) return MenuStyle.Kind.MULTIPLAYER;
		if (s instanceof GuiOptions || s instanceof GuiVideoSettings || s instanceof GuiControls
				|| s instanceof GuiScreenOptionsSounds || s instanceof GuiLanguage || s instanceof ScreenChatOptions
				|| s instanceof GuiCustomizeSkin) {
			return MenuStyle.Kind.OPTIONS;
		}
		if (worldsClass == null) worldsClass = Mc.worldSelectScreen(null).getClass();
		if (s.getClass() == worldsClass) return MenuStyle.Kind.WORLDS;
		if (s instanceof GuiDownloadTerrain || s instanceof GuiConnecting) return MenuStyle.Kind.LOADING;
		if (s instanceof net.minecraft.client.gui.GuiDisconnected) return MenuStyle.Kind.ERROR;
		return null;
	}

	boolean styled(GuiScreen s) {
		MenuStyle.Kind k = kind(s);
		return k != null && MenuStyle.enabled(k);
	}

	/** Formulare der Serverliste: „Direkt verbinden“ und „Server hinzufügen/bearbeiten“. */
	static boolean serverForm(GuiScreen s) {
		return s instanceof GuiScreenServerList || s instanceof GuiScreenAddServer;
	}

	/** Bildschirme mit Vanilla-Liste (GuiSlot übermalt alles – Kopf/Fuß zeichnet onDrawn). */
	boolean listScreen(GuiScreen s) {
		if (s instanceof GuiMultiplayer) return true;
		if (worldsClass == null) worldsClass = Mc.worldSelectScreen(null).getClass();
		return s.getClass() == worldsClass;
	}

	// --- Dialoge: übernehmen die Art des Menüs, aus dem sie kommen ---

	/** Art je offenem Dialog (beim ersten Einordnen festgelegt; schwach referenziert). */
	private final Map<GuiScreen, MenuStyle.Kind> dialogs = new WeakHashMap<GuiScreen, MenuStyle.Kind>();
	/** Art des zuletzt aufgebauten Nicht-Dialog-Bildschirms (= Menü, aus dem ein Dialog geöffnet wird). */
	private MenuStyle.Kind lastKind;

	/** Bestätigungen (Server/Welt löschen, Server-Ressourcenpaket, Link öffnen …). */
	static boolean dialog(GuiScreen s) {
		return s instanceof GuiYesNo;
	}

	MenuStyle.Kind dialogKind(GuiScreen s) {
		if (dialogs.containsKey(s)) return dialogs.get(s);
		MenuStyle.Kind k = MenuStyle.dialogKind(lastKind, Mc.world() != null, Mc.mc().getCurrentServerData() != null);
		dialogs.put(s, k);
		return k;
	}

	// --- Textfelder im Stil ---

	/** Textfelder je Bildschirmklasse (Reflection, einmal gesucht). */
	private final Map<Class<?>, List<Field>> textFieldsByClass = new HashMap<Class<?>, List<Field>>();
	/** Textfelder, deren Vanilla-Rahmen für das laufende Bild abgeschaltet ist: {x, y, width}. */
	private final Map<GuiTextField, int[]> hiddenFields = new IdentityHashMap<GuiTextField, int[]>();

	List<GuiTextField> textFields(GuiScreen s) {
		List<Field> fields = textFieldsByClass.get(s.getClass());
		if (fields == null) {
			fields = new ArrayList<Field>();
			for (Class<?> c = s.getClass(); c != null && c != GuiScreen.class && c != Object.class; c = c.getSuperclass()) {
				for (Field f : c.getDeclaredFields()) {
					if (!GuiTextField.class.isAssignableFrom(f.getType()) || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
					try {
						f.setAccessible(true);
						fields.add(f);
					} catch (RuntimeException e) {
						// dann eben ohne dieses Feld
					}
				}
			}
			textFieldsByClass.put(s.getClass(), fields);
		}
		List<GuiTextField> out = new ArrayList<GuiTextField>();
		for (Field f : fields) {
			try {
				Object v = f.get(s);
				if (v instanceof GuiTextField) out.add((GuiTextField) v);
			} catch (IllegalAccessException | RuntimeException e) {
				// weiter
			}
		}
		return out;
	}

	/**
	 * Vor dem Zeichnen der Textfelder (nach dem Hintergrund): TRS-Fläche zeichnen und den Vanilla-Rahmen für
	 * dieses Bild abschalten. Ohne Rahmen schreibt Vanilla ab (x, y) statt (x+4, mittig) und nutzt die volle
	 * Breite – darum Lage und Breite so verschieben, dass Text und Schreibmarke genau wie mit Rahmen stehen.
	 * Nach dem Bild ({@link #restoreFields()}) wieder zurück.
	 */
	void styleTextFields(GuiScreen s, Canvas c, int mouseX, int mouseY) {
		restoreFields();
		for (GuiTextField f : textFields(s)) {
			if (!f.getVisible() || !f.getEnableBackgroundDrawing()) continue;
			int x = fieldX(f);
			int y = fieldY(f);
			boolean hover = mouseX >= x && mouseY >= y && mouseX < x + f.width && mouseY < y + f.height;
			MenuSkin.textField(c, x - 1, y - 1, f.width + 2, f.height + 2, f.isFocused(), hover, true);
			hiddenFields.put(f, new int[]{x, y, f.width});
			f.setEnableBackgroundDrawing(false);
			setFieldPos(f, x + 4, y + (f.height - 8) / 2);
			f.width = f.width - 8;
		}
	}

	void restoreFields() {
		if (hiddenFields.isEmpty()) return;
		for (Map.Entry<GuiTextField, int[]> e : hiddenFields.entrySet()) {
			GuiTextField f = e.getKey();
			int[] o = e.getValue();
			setFieldPos(f, o[0], o[1]);
			f.width = o[2];
			f.setEnableBackgroundDrawing(true);
		}
		hiddenFields.clear();
	}

	/**
	 * Fläche hinter einem Formular {x1, y1, x2, y2}: der Block aus Knöpfen und Feldern um die Bildschirmmitte, oben Platz
	 * für die Beschriftung; Knöpfe anderer Mods am Rand zählen nicht mit
	 * ({@link dev.theredstonee.trsclient.core.menus.FormPanel}).
	 */
	int[] formBounds(GuiScreen s, List<GuiTextField> fields) {
		List<int[]> rects = new java.util.ArrayList<int[]>();
		List<GuiButton> list = buttons.get(s);
		if (list != null) {
			for (GuiButton b : list) {
				if (b.visible) rects.add(new int[]{x(b), y(b), b.width, height(b)});
			}
		}
		for (GuiTextField f : fields) {
			if (f.getVisible()) rects.add(new int[]{fieldX(f), fieldY(f), f.width, f.height});
		}
		return dev.theredstonee.trsclient.core.menus.FormPanel.bounds(rects, s.width, s.height, MenuSkin.HEADER);
	}

	// --- Ereignisse ---

	@SubscribeEvent
	public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
		GuiScreen s = Mc.eventGui(event);
		List<GuiButton> list = buttonList(event);
		if (s == null || list == null) return;
		restoreFields();
		// Öffner für spätere Dialoge merken (Dialoge selbst werden hier eingeordnet).
		if (dialog(s)) dialogKind(s);
		else lastKind = kind(s);
		MenuStyle.Kind k = kind(s);
		if (k == null) return;
		buttons.put(s, list);
		try {
			if (k == MenuStyle.Kind.PAUSE && s instanceof GuiIngameMenu && MenuStyle.pauseButtons()) pauseButtons(s, list);
		} catch (RuntimeException e) {
			// Menü bleibt dann klassisch.
		}
		try {
			if (k == MenuStyle.Kind.PAUSE && s instanceof GuiIngameMenu) hostingButtons(s, list);
			if (k == MenuStyle.Kind.MULTIPLAYER && s instanceof GuiMultiplayer) joinButton(s, list);
			if (k == MenuStyle.Kind.MULTIPLAYER && MenuStyle.enabled(k) && s instanceof GuiMultiplayer) pingButton(s, list);
		} catch (RuntimeException | LinkageError e) {
			// ohne Hosting-Knöpfe weiter
		}
	}

	@SubscribeEvent
	public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
		GuiButton b = button(event);
		GuiScreen s = Mc.eventGui(event);
		if (b != null && s instanceof GuiMultiplayer && b.id == PING_ID && icons.containsKey(b)) {
			event.setCanceled(true);
			startPingTest((GuiMultiplayer) s);
			return;
		}
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
		if (listScreen(s)) return;
		try {
			Canvas c = canvas(s);
			boolean world = Mc.world() != null;
			int header = k == MenuStyle.Kind.PAUSE || k == MenuStyle.Kind.ERROR || dialog(s) ? 0 : MenuSkin.HEADER;
			MenuSkin.background(c, s.width, s.height, world, header, s.height);
			List<GuiTextField> fields = textFields(s);
			if (serverForm(s)) {
				restoreFields();
				int[] form = formBounds(s, fields);
				if (form != null) MenuSkin.formPanel(c, form[0], form[1], form[2], form[3], world);
			}
			if (!fields.isEmpty()) styleTextFields(s, c, -1, -1);
		} catch (RuntimeException e) {
			restoreFields();
			// klassisch weiter
		}
	}

	/** Nach dem ganzen Bildschirm: Knopfflächen im Stil, Ladebildschirme komplett. */
	@SubscribeEvent
	public void onDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
		GuiScreen s = Mc.eventGui(event);
		// Textfelder nach dem Bild wieder auf Vanilla-Lage (Klicks und Maße stimmen dann wieder).
		restoreFields();
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
				// Schnell verbinden: was gerade passiert, wenn es länger als 1 s dauert.
				String detail = s instanceof GuiConnecting ? dev.theredstonee.trsclient.core.connect.FastConnect.STATUS.line() : null;
				MenuSkin.loading(c, s.width, s.height, title, detail, -1f, false, bottom < 0 ? -1 : top, bottom);
			} else if (listScreen(s)) {
				// Die Liste (GuiSlot) übermalt alles mit Erde – Kopf- und Fußleiste im Stil neu zeichnen.
				if (k == MenuStyle.Kind.MULTIPLAYER && s instanceof GuiMultiplayer) pingLabels((GuiMultiplayer) s, c);
				listFrame(s, c, k);
				if (k == MenuStyle.Kind.MULTIPLAYER && s instanceof GuiMultiplayer) updatePingTest((GuiMultiplayer) s);
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
			String label = I18n.tr("hosting.link.deactivateLong");
			GuiButton d = new GuiButton(LINK_ID, 8, y, Math.min(170, Math.max(100, font.getStringWidth(label) + 34)), 20, label);
			icons.put(d, "lock");
			list.add(d);
			// Das rote Abzeichen steht oben mittig (HUD-Pfad, auch unter dem Pausemenü).
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

	/** Mehrspieler: „Ping-Test“ oben rechts. */
	void pingButton(GuiScreen s, List<GuiButton> list) {
		FontRenderer font = Mc.font();
		int w = Math.max(font.getStringWidth(I18n.tr("menus.pingTest")),
				Math.max(font.getStringWidth(I18n.tr("menus.pingTest.running", 88, 88)), font.getStringWidth(I18n.tr("menus.pingTest.cooldown", 8)))) + 26;
		// Titel „Mehrspieler“ steht mittig (Vanilla): nicht verdecken – sonst nur das Symbol.
		int titleRight = s.width / 2 + font.getStringWidth(net.minecraft.client.resources.I18n.format("multiplayer.title")) / 2 + 4;
		boolean compact = s.width - w - 6 < titleRight;
		if (compact) w = 20;
		GuiButton b = new GuiButton(PING_ID, s.width - w - 6, 6, w, 20, compact ? "" : I18n.tr("menus.pingTest"));
		icons.put(b, "signal");
		if (compact) compactPing.put(b, Boolean.TRUE);
		list.add(b);
	}

	public void startPingTest(GuiMultiplayer s) {
		ServerList servers = s.getServerList();
		if (servers == null) return;
		List<String> addresses = new java.util.ArrayList<String>();
		for (int i = 0; i < servers.countServers(); i++) addresses.add(servers.getServerData(i).serverIP);
		if (ServerPingTest.shared().start(addresses, System.currentTimeMillis())) pingSortPending = true;
	}

	/** Je Bild: Beschriftung (Fortschritt/Wartezeit) und nach dem Test einmal nach Ping sortieren. */
	void updatePingTest(GuiMultiplayer s) {
		ServerPingTest test = ServerPingTest.shared();
		if (pingSortPending && !test.running()) {
			pingSortPending = false;
			sortByPing(s);
		}
		List<GuiButton> list = buttons.get(s);
		if (list == null) return;
		long left = test.cooldownLeft(System.currentTimeMillis());
		for (GuiButton b : list) {
			if (b.id != PING_ID || !icons.containsKey(b)) continue;
			boolean compact = compactPing.containsKey(b);
			if (compact) {
				b.enabled = !test.running() && !(left > 0 && test.hasResults());
				continue;
			}
			if (test.running()) {
				b.displayString = I18n.tr("menus.pingTest.running", test.finished(), test.total());
				b.enabled = false;
			} else if (left > 0 && test.hasResults()) {
				b.displayString = I18n.tr("menus.pingTest.cooldown", (left + 999) / 1000);
				b.enabled = false;
			} else {
				b.displayString = I18n.tr("menus.pingTest");
				b.enabled = true;
			}
		}
	}

	/** Liste nach Ping ordnen (Vanilla-Tausch + speichern), dann die Anzeige neu füllen. */
	void sortByPing(GuiMultiplayer s) {
		ServerList servers = s.getServerList();
		if (servers == null) return;
		List<String> addresses = new java.util.ArrayList<String>();
		for (int i = 0; i < servers.countServers(); i++) addresses.add(servers.getServerData(i).serverIP);
		int[] order = ServerPingTest.shared().order(addresses, null);
		List<int[]> swaps = ServerPingTest.swaps(order);
		if (swaps.isEmpty()) return;
		for (int[] sw : swaps) servers.swapServers(sw[0], sw[1]);
		servers.saveServerList();
		Object selector = serverList(s);
		if (selector == null) return;
		// updateOnlineServers(ServerList) – per Signatur gesucht (in 1.8.9 ohne MCP-Namen).
		for (java.lang.reflect.Method m : selector.getClass().getMethods()) {
			Class<?>[] params = m.getParameterTypes();
			if (params.length == 1 && params[0] == ServerList.class && m.getReturnType() == void.class) {
				try {
					m.invoke(selector, servers);
				} catch (ReflectiveOperationException | RuntimeException ignored) {
					// Liste zeigt die neue Reihenfolge dann beim nächsten Öffnen
				}
				return;
			}
		}
	}

	/** Server-Liste des Mehrspieler-Bildschirms (privates Feld, per Typ gesucht). */
	ServerSelectionList serverList(GuiMultiplayer s) {
		try {
			if (!serverListSearched) {
				serverListSearched = true;
				for (Field f : GuiMultiplayer.class.getDeclaredFields()) {
					if (f.getType() == ServerSelectionList.class) {
						f.setAccessible(true);
						serverListField = f;
						break;
					}
				}
			}
			return serverListField == null ? null : (ServerSelectionList) serverListField.get(s);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** Ergebnis des Ping-Tests rechts in jeder sichtbaren Zeile (unter Vanillas Balken). */
	void pingLabels(GuiMultiplayer s, Canvas c) {
		ServerPingTest test = ServerPingTest.shared();
		if (!test.hasResults()) return;
		ServerSelectionList list = serverList(s);
		ServerList servers = s.getServerList();
		if (list == null || servers == null) return;
		int rowX = list.left + list.width / 2 - list.getListWidth() / 2 + 2;
		int rowW = list.getListWidth();
		int y0 = list.top + 4 - list.getAmountScrolled() + list.headerPadding;
		for (int i = 0; i < servers.countServers(); i++) {
			int y = y0 + i * list.slotHeight;
			if (y + 20 < list.top || y + 10 > list.bottom) continue;
			ServerPingTest.Entry e = test.entry(servers.getServerData(i).serverIP);
			String label = ServerPingTest.label(e);
			if (label == null) continue;
			StatusPing.Result r = e.result;
			int color = r == null ? 0xFFB0B0B0 : !r.ok || r.latencyMs < 0 ? 0xFFF05050 : PingPanel.quality(r.latencyMs);
			int lw = c.textWidth(label);
			int lx = rowX + rowW - 2 - lw;
			c.fill(lx - 2, y + 10, rowX + rowW, y + 19, 0xC0101014);
			c.text(label, lx, y + 10, color, true);
		}
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

	private static int fieldX(GuiTextField f) {
		//? if >=1.11 {
		/*return f.x;
		*///?} else
		return f.xPosition;
	}

	private static int fieldY(GuiTextField f) {
		//? if >=1.11 {
		/*return f.y;
		*///?} else
		return f.yPosition;
	}

	private static void setFieldPos(GuiTextField f, int x, int y) {
		//? if >=1.11 {
		/*f.x = x;
		f.y = y;
		*///?} else {
		f.xPosition = x;
		f.yPosition = y;
		//?}
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
