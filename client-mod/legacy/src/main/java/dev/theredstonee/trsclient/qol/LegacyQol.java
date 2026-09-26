package dev.theredstonee.trsclient.qol;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.alert.Warnings;
import dev.theredstonee.trsclient.core.chat.ChatHistory;
import dev.theredstonee.trsclient.core.chat.ChatText;
import dev.theredstonee.trsclient.core.connect.AutoReconnect;
import dev.theredstonee.trsclient.core.hud.HudLayout;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.input.KeyPresses;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.QolModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.pvp.ItemCounter;
import dev.theredstonee.trsclient.core.qol.Qol;
import dev.theredstonee.trsclient.core.qol.QolPanels;
import dev.theredstonee.trsclient.core.qol.QolSound;
import dev.theredstonee.trsclient.core.streamer.StreamerMode;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.hud.HudElement;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.Display;
//? if >=1.9 {
/*import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
*///?} else {
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
//?}

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Komfort-/PvP-Paket für Forge 1.8.9–1.12.2 (ohne Mixins): Forge-Ereignisse, Tick-Abfragen und ein paar über ihren Typ
 * gefundene Felder (keine SRG-Namen). Die Entscheidungen trifft {@code core.qol} wie in allen anderen Versionen.
 *
 * <p>Nicht möglich (ausgeblendet): Titel verschieben/skalieren (Forge zeichnet sie ohne Ereignis), Totem-Pops (kein
 * Haken auf Entity-Ereignis 35), Taskleisten-Blinken (LWJGL 2); Lautstärke der Töne ist fest.
 */
public final class LegacyQol {
	private static final TextWidth MEASURE = new TextWidth() {
		@Override
		public int width(String text) {
			return Mc.font().getStringWidth(text);
		}
	};

	private static LegacyQol instance;
	private final TrsModules modules;
	private final QolModules m;
	private final Qol qol;
	private final KeyPresses keys = new KeyPresses(Keys::isDown);
	private final ChatHistory.Keeper<Object> allLines = new ChatHistory.Keeper<Object>();
	private final ChatHistory.Keeper<Object> drawnLines = new ChatHistory.Keeper<Object>();
	private Field[] chatLists;
	private boolean chatListsLooked;
	private Object lastWorld;
	private boolean wasDead;
	private ServerData lastServer;
	private GuiScreen disconnectScreen;
	private GuiButton reconnectButton;
	private int tickCount;
	private long errorLogged;
	/** Auf das Vanilla-Scoreboard gesetzte Anzeige-Slots (während des HUD-Zeichnens geleert). */
	private ScoreObjective[] hiddenSlots;
	private Field slotsField;
	private boolean slotsLooked;
	private boolean bossMoved;
	/** Serverliste: verborgene Namen während des Zeichnens. */
	private final List<Object[]> hiddenNames = new ArrayList<Object[]>();
	private boolean critPending;
	private boolean magicPending;
	private Entity critTarget;
	private boolean tabInstalled;
	public static final QolPanels.Where WHERE = new QolPanels.Where();

	private LegacyQol(TrsModules modules) {
		this.modules = modules;
		this.m = modules.qol;
		this.qol = Qol.init(modules, new Qol.Platform() {
			@Override
			public void play(QolSound sound, float volume, float pitch) {
				LegacyQol.play(sound, pitch);
			}

			@Override
			public boolean focused() {
				return Display.isActive();
			}

			@Override
			public long windowHandle() {
				return 0;
			}
		});
	}

	/** Beim Start (vor dem HudManager). */
	public static LegacyQol init(TrsModules modules) {
		instance = new LegacyQol(modules);
		return instance;
	}

	public static LegacyQol get() {
		return instance;
	}

	// --- Tick ---

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		try {
			tick(Minecraft.getMinecraft());
		} catch (RuntimeException | LinkageError e) {
			long now = System.currentTimeMillis();
			if (now - errorLogged > 60_000) {
				errorLogged = now;
				TrsClient.LOGGER.warn("Komfort/PvP: {}", e.toString());
			}
		}
	}

	private void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		tickCount++;
		qol.tick(mc.getSession().getUsername(), now);
		Object world = Mc.world();
		if (world != lastWorld) {
			if (lastWorld != null) qol.onWorldChange();
			lastWorld = world;
			wasDead = false;
		}
		EntityPlayer p = Mc.player();
		if (p != null && world != null) {
			ServerData sd = mc.getCurrentServerData();
			if (sd != null && !mc.isSingleplayer()) lastServer = sd;
			qol.reconnect.inWorld(sd == null ? null : sd.serverIP, now);
			tickPlayer(p, now);
			if (tickCount % 20 == 0 && m.streamer.isEnabled() && m.streamerOthers.get() && Mc.connection() != null) {
				for (NetworkPlayerInfo info : Mc.connection().getPlayerInfoMap()) qol.streamer.knowPlayer(info.getGameProfile().getName());
			}
			installTabOverlay(mc);
		} else {
			WHERE.valid = false;
			qol.reconnect.leftWorld();
		}
		tickChatHistory(mc);
		tickDisconnect(mc, now);
		if (Mc.screen() == null) {
			if (keys.pressed(m.streamerKey)) {
				qol.toggleStreamer(now);
				TrsClient.get().saveConfig();
			}
		} else {
			keys.releaseAll();
		}
	}

	private void tickPlayer(EntityPlayer p, long now) {
		WHERE.x = p.posX;
		WHERE.z = p.posZ;
		WHERE.yaw = p.rotationYaw;
		WHERE.dimension = Mc.dimensionId();
		WHERE.valid = true;
		boolean dead = p.getHealth() <= 0;
		if (dead && !wasDead) {
			qol.death.set(Math.floor(p.posX), Math.floor(p.posY), Math.floor(p.posZ), Mc.dimensionId(), now);
			qol.onOwnDeath(now);
		}
		wasDead = dead;

		Warnings.Input in = qol.warnings.input();
		in.alive = !dead && p.isEntityAlive();
		in.survival = !p.capabilities.isCreativeMode && !p.isSpectator();
		in.health = p.getHealth();
		in.maxHealth = p.getMaxHealth();
		in.food = p.getFoodStats().getFoodLevel();
		for (int i = 0; i < 4; i++) {
			ItemStack s = Mc.equipment(p, i);
			if (s != null && s.isItemStackDamageable()) {
				in.armorDamage[i] = s.getItemDamage();
				in.armorMax[i] = s.getMaxDamage();
				in.armorName[i] = s.getDisplayName();
			}
		}
		ItemStack hand = Mc.equipment(p, 4);
		if (hand != null && hand.isItemStackDamageable()) {
			in.handDamage = hand.getItemDamage();
			in.handMax = hand.getMaxDamage();
			in.handName = hand.getDisplayName();
		}
		in.inventoryFull = p.inventory.getFirstEmptyStack() < 0;
		qol.tickWarnings(now);

		if (tickCount % 4 == 0 && m.itemCounter.isEnabled()) {
			ItemCounter c = qol.counter;
			c.clear();
			int size = Math.min(41, p.inventory.getSizeInventory());
			for (int i = 0; i < size; i++) {
				if (i >= 36 && i < 40) continue;
				ItemStack s = p.inventory.getStackInSlot(i);
				if (Mc.isEmpty(s)) continue;
				String id = itemId(s.getItem());
				int mask = ItemCounter.classify(id, s.getItem() instanceof ItemBlock, healing(s), splash(s));
				ItemStack icon = null;
				if (c.needsIcon(mask)) {
					// Symbol ohne Stapelzahl (die Summe steht daneben).
					icon = s.copy();
					//? if >=1.11 {
					/*icon.setCount(1);
					*///?} else
					icon.stackSize = 1;
				}
				c.add(mask, count(s), icon);
			}
		}
	}

	/** Längerer Verlauf: Zeilen, die Vanilla hinten abschneidet, wieder anhängen (Listen per Typ gefunden). */
	@SuppressWarnings("unchecked")
	private void tickChatHistory(Minecraft mc) {
		if (mc.ingameGUI == null) return;
		GuiNewChat chat = mc.ingameGUI.getChatGUI();
		if (chat == null) return;
		if (!chatListsLooked) {
			chatListsLooked = true;
			List<Field> found = new ArrayList<Field>();
			for (Field f : GuiNewChat.class.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) continue;
				Type t = f.getGenericType();
				if (t instanceof ParameterizedType && ((ParameterizedType) t).getActualTypeArguments()[0] == String.class) continue;
				f.setAccessible(true);
				found.add(f);
			}
			if (found.size() == 2) chatLists = found.toArray(new Field[0]);
		}
		if (chatLists == null) return;
		int limit = ChatHistory.limit(modules.chat.isEnabled(), m.chatHistory.get());
		try {
			allLines.keep((List<Object>) chatLists[0].get(chat), limit);
			drawnLines.keep((List<Object>) chatLists[1].get(chat), limit);
		} catch (IllegalAccessException | RuntimeException e) {
			chatLists = null;
		}
	}

	// --- Auto-Reconnect ---

	private void tickDisconnect(Minecraft mc, long now) {
		GuiScreen s = Mc.screen();
		if (s instanceof GuiDisconnected) {
			if (s != disconnectScreen) {
				disconnectScreen = s;
				String reason = reasonOf(s);
				if (m.autoReconnect.isEnabled()) {
					qol.reconnect.onDisconnected(lastServer == null ? null : lastServer.serverIP, reason, now,
							(long) (m.reconnectDelay.get() * 1000), m.reconnectAttempts.getInt(),
							m.reconnectMode.get() == QolModules.ReconnectMode.LENIENT, ChatText.words(m.reconnectNever.get()));
				}
				qol.onKicked(reason, now);
			}
			GuiButton b = reconnectButton;
			if (b != null) {
				String label = reconnectLabel();
				b.visible = label != null;
				b.enabled = qol.reconnect.phase() == AutoReconnect.Phase.COUNTDOWN;
				if (label != null) b.displayString = label;
			}
			if (m.autoReconnect.isEnabled() && qol.reconnect.due(now) && lastServer != null) {
				mc.displayGuiScreen(new GuiConnecting(new GuiMultiplayer(new GuiMainMenu()), mc, lastServer));
			}
		} else if (disconnectScreen != null) {
			disconnectScreen = null;
			reconnectButton = null;
			qol.reconnect.screenClosed();
		}
	}

	private String reconnectLabel() {
		if (!m.autoReconnect.isEnabled()) return null;
		AutoReconnect r = qol.reconnect;
		switch (r.phase()) {
			case COUNTDOWN:
				return I18n.tr("reconnect.countdown", r.secondsLeft(System.currentTimeMillis()), r.attempts() + 1, m.reconnectAttempts.getInt());
			case CANCELLED:
				return I18n.tr("reconnect.cancelled");
			case BLOCKED:
				if (ChatText.containsAny(r.reason(), ChatText.words(m.reconnectNever.get()))) {
					return I18n.tr("reconnect.blocked", I18n.tr("reconnect.kind.custom"));
				}
				return I18n.tr("reconnect.blocked", I18n.trOr("reconnect.kind." + r.kind().name().toLowerCase(java.util.Locale.ROOT),
						I18n.tr("reconnect.kind.unknown")));
			case EXHAUSTED:
				return I18n.tr("reconnect.exhausted", m.reconnectAttempts.getInt());
			default:
				return null;
		}
	}

	/** Nur Selbsttest: Server für den Auto-Reconnect vorgeben (sonst der zuletzt betretene). */
	public static void testServer(String ip) {
		if (instance != null) instance.lastServer = new ServerData("TRS-Test", ip, false);
	}

	/** Knopf „Abbrechen“ (auch für den Selbsttest). */
	public void cancelReconnect() {
		qol.reconnect.cancel();
	}

	/** Grund im Getrennt-Bildschirm: das Feld vom Typ der Chat-Komponente. */
	private static String reasonOf(GuiScreen s) {
		try {
			for (Field f : GuiDisconnected.class.getDeclaredFields()) {
				//? if >=1.9 {
				/*if (f.getType() != ITextComponent.class) continue;
				f.setAccessible(true);
				Object v = f.get(s);
				return v == null ? "" : ((ITextComponent) v).getUnformattedText();
				*///?} else {
				if (f.getType() != IChatComponent.class) continue;
				f.setAccessible(true);
				Object v = f.get(s);
				return v == null ? "" : ((IChatComponent) v).getUnformattedText();
				//?}
			}
		} catch (IllegalAccessException | RuntimeException ignored) {
			// kein Grund lesbar
		}
		return "";
	}

	private static final int RECONNECT_ID = 73180;

	@SubscribeEvent
	public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
		GuiScreen s = Mc.eventGui(event);
		if (!(s instanceof GuiDisconnected) || !m.autoReconnect.isEnabled()) return;
		int w = 260;
		GuiButton b = new GuiButton(RECONNECT_ID, s.width / 2 - w / 2, s.height - 28, w, 20, "");
		b.visible = false;
		//? if >=1.9 {
		/*event.getButtonList().add(b);
		*///?} else
		event.buttonList.add(b);
		reconnectButton = b;
	}

	@SubscribeEvent
	public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
		//? if >=1.9 {
		/*GuiButton b = event.getButton();
		*///?} else
		GuiButton b = event.button;
		if (b != null && b == reconnectButton) {
			qol.reconnect.cancel();
			event.setCanceled(true);
		}
	}

	// --- Chat ---

	/** Vor den Chat-Verbesserungen (die laufen mit LOWEST): Filter, Erwähnungen, Streamer-Modus, Warteschlange. */
	@SubscribeEvent(priority = EventPriority.HIGH)
	public void onChat(ClientChatReceivedEvent event) {
		try {
			//? if >=1.9 {
			/*ITextComponent msg = event.getMessage();
			*///?} else
			IChatComponent msg = event.message;
			if (msg == null) return;
			String raw = msg.getFormattedText();
			long now = System.currentTimeMillis();
			if (dev.theredstonee.trsclient.compat.ChatCompat.isActionBar(event)) {
				qol.onServerText(raw, now);
				return;
			}
			if (qol.hideChat(raw)) {
				event.setCanceled(true);
				return;
			}
			boolean mention = qol.onChat(raw, now);
			boolean mask = qol.streamer.affects(raw);
			if (!mention && !mask) return;
			//? if >=1.9 {
			/*ITextComponent out = rewrite(msg, mention ? qol.mentionWords() : null, mask ? qol.streamer : null);
			event.setMessage(out);
			*///?} else {
			IChatComponent out = rewrite(msg, mention ? qol.mentionWords() : null, mask ? qol.streamer : null);
			event.message = out;
			//?}
		} catch (RuntimeException | LinkageError ignored) {
			// Chat darf nie scheitern
		}
	}

	/** Baut die Nachricht Stück für Stück neu (Stile, Klick-/Hover-Aktionen bleiben). */
	//? if >=1.9 {
	/*private ITextComponent rewrite(ITextComponent msg, List<String> words, StreamerMode streamer) {
		TextComponentString out = new TextComponentString("");
		for (ITextComponent c : msg) {
			String text = c.getUnformattedComponentText();
			Style style = c.getStyle();
	*///?} else {
	private IChatComponent rewrite(IChatComponent msg, List<String> words, StreamerMode streamer) {
		ChatComponentText out = new ChatComponentText("");
		for (IChatComponent c : msg) {
			String text = c.getUnformattedTextForChat();
			ChatStyle style = c.getChatStyle();
	//?}
			String raw = streamer == null ? text : streamer.replace(text);
			List<int[]> hits = words == null ? null : ChatText.find(raw, words);
			if (hits == null || hits.isEmpty()) {
				if (!raw.isEmpty()) out.appendSibling(piece(raw, style, false));
				continue;
			}
			int pos = 0;
			for (int[] h : hits) {
				if (h[0] > pos) out.appendSibling(piece(raw.substring(pos, h[0]), style, false));
				out.appendSibling(piece(ChatText.strip(raw.substring(h[0], h[1])), style, true));
				pos = h[1];
			}
			if (pos < raw.length()) out.appendSibling(piece(ChatText.activeCodes(raw.substring(0, pos)) + raw.substring(pos), style, false));
		}
		return out;
	}

	//? if >=1.9 {
	/*private ITextComponent piece(String text, Style style, boolean mark) {
		Style s = style.createShallowCopy();
		if (mark) {
			s.setColor(nearest(m.mentionsColor.rgb()));
			if (m.mentionsBold.get()) s.setBold(true);
		}
		return new TextComponentString(text).setStyle(s);
	}
	*///?} else {
	private IChatComponent piece(String text, ChatStyle style, boolean mark) {
		ChatStyle s = style.createShallowCopy();
		if (mark) {
			s.setColor(nearest(m.mentionsColor.rgb()));
			if (m.mentionsBold.get()) s.setBold(true);
		}
		return new ChatComponentText(text).setChatStyle(s);
	}
	//?}

	/** Nächste der 16 Farben (alte Versionen kennen keine RGB-Farben). */
	//? if >=1.9 {
	/*private static TextFormatting nearest(int rgb) {
		TextFormatting[] colors = {TextFormatting.YELLOW, TextFormatting.GOLD, TextFormatting.RED, TextFormatting.GREEN,
				TextFormatting.AQUA, TextFormatting.LIGHT_PURPLE, TextFormatting.WHITE, TextFormatting.BLUE};
	*///?} else {
	private static EnumChatFormatting nearest(int rgb) {
		EnumChatFormatting[] colors = {EnumChatFormatting.YELLOW, EnumChatFormatting.GOLD, EnumChatFormatting.RED,
				EnumChatFormatting.GREEN, EnumChatFormatting.AQUA, EnumChatFormatting.LIGHT_PURPLE, EnumChatFormatting.WHITE,
				EnumChatFormatting.BLUE};
	//?}
		int[] values = {0xFFFF55, 0xFFAA00, 0xFF5555, 0x55FF55, 0x55FFFF, 0xFF55FF, 0xFFFFFF, 0x5555FF};
		int best = 0;
		long bestD = Long.MAX_VALUE;
		for (int i = 0; i < values.length; i++) {
			int v = values[i];
			long dr = ((v >> 16) & 0xFF) - ((rgb >> 16) & 0xFF), dg = ((v >> 8) & 0xFF) - ((rgb >> 8) & 0xFF), db = (v & 0xFF) - (rgb & 0xFF);
			long d = dr * dr + dg * dg + db * db;
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return colors[best];
	}

	/** Chat-Zeile kopieren: welche Maustaste ist erlaubt (0 = links mit Strg, 1 = rechts)? */
	public static boolean copyAllowed(int button, boolean control) {
		LegacyQol q = instance;
		if (q == null) return button == 0 && control;
		QolModules.CopyMode mode = q.m.chatCopyMode.get();
		if (button == 0) return control && mode.ctrl();
		return button == 1 && mode.right();
	}

	// --- Streamer-Modus: Namensschilder, Serverliste ---

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onNameFormat(PlayerEvent.NameFormat event) {
		if (!qol.streamer.active()) return;
		//? if >=1.9 {
		/*String name = event.getDisplayname();
		if (name != null) event.setDisplayname(qol.streamer.replace(name));
		*///?} else {
		String name = event.displayname;
		if (name != null) event.displayname = qol.streamer.replace(name);
		//?}
	}

	@SubscribeEvent
	public void onDrawPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
		restoreNames();
		GuiScreen s = Mc.eventGui(event);
		if (!(s instanceof GuiMultiplayer) || !qol.streamer.active() || !m.streamerHideIp.get()) return;
		ServerList list = serverList(s);
		if (list == null) return;
		for (int i = 0; i < list.countServers(); i++) {
			ServerData d = list.getServerData(i);
			if (d != null && StreamerMode.looksLikeAddress(d.serverName)) {
				hiddenNames.add(new Object[]{d, d.serverName});
				d.serverName = StreamerMode.HIDDEN_ADDRESS;
			}
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onDrawPost(GuiScreenEvent.DrawScreenEvent.Post event) {
		restoreNames();
	}

	private void restoreNames() {
		for (Object[] e : hiddenNames) ((ServerData) e[0]).serverName = (String) e[1];
		hiddenNames.clear();
	}

	private static Field serverListField;

	private static ServerList serverList(GuiScreen s) {
		try {
			if (serverListField == null) {
				for (Field f : GuiMultiplayer.class.getDeclaredFields()) {
					if (f.getType() == ServerList.class) {
						f.setAccessible(true);
						serverListField = f;
						break;
					}
				}
			}
			return serverListField == null ? null : (ServerList) serverListField.get(s);
		} catch (IllegalAccessException | RuntimeException e) {
			return null;
		}
	}

	/** Tabliste: eigene Unterklasse (Ping in ms, Streamer-Namen) statt der von Vanilla – einmal je Spielsitzung. */
	private void installTabOverlay(Minecraft mc) {
		if (tabInstalled || mc.ingameGUI == null) return;
		tabInstalled = true;
		try {
			for (Field f : GuiIngame.class.getDeclaredFields()) {
				if (f.getType() != GuiPlayerTabOverlay.class) continue;
				f.setAccessible(true);
				Field mods = Field.class.getDeclaredField("modifiers");
				mods.setAccessible(true);
				mods.setInt(f, f.getModifiers() & ~Modifier.FINAL);
				Object old = f.get(mc.ingameGUI);
				if (old != null && old.getClass() == GuiPlayerTabOverlay.class) f.set(mc.ingameGUI, new TabOverlay(mc, mc.ingameGUI));
				break;
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			TrsClient.LOGGER.warn("Tabliste (Ping in ms) nicht verfügbar: {}", e.toString());
		}
	}

	/** Tabliste mit Ping in ms und Namensschutz. */
	static final class TabOverlay extends GuiPlayerTabOverlay {
		TabOverlay(Minecraft mc, GuiIngame gui) {
			super(mc, gui);
		}

		@Override
		protected void drawPing(int width, int x, int y, NetworkPlayerInfo info) {
			LegacyQol q = instance;
			if (q == null || !q.m.tabPing.isEnabled()) {
				super.drawPing(width, x, y, info);
				return;
			}
			int ms = info.getResponseTime();
			String text = ms < 0 ? "?" : String.valueOf(ms);
			int color = q.m.tabPingColors.get() ? pingColor(ms) : 0xFFFFFFFF;
			FontRenderer font = Mc.font();
			Gfx g = Gfx.of(Mc.scaledResolution().getScaledWidth(), Mc.scaledResolution().getScaledHeight());
			g.push();
			g.translate(x + width - 1, y);
			g.scale(0.75f);
			g.raise(100);
			g.text(font, text, -font.getStringWidth(text), 2, color, true);
			g.pop();
		}

		@Override
		public String getPlayerName(NetworkPlayerInfo info) {
			String name = super.getPlayerName(info);
			LegacyQol q = instance;
			return q == null || !q.qol.streamer.active() ? name : q.qol.streamer.replace(name);
		}
	}

	static int pingColor(int ms) {
		if (ms <= 0) return 0xFFAAAAAA;
		if (ms < 80) return 0xFF55FF55;
		if (ms < 150) return 0xFFFFFF55;
		if (ms < 250) return 0xFFFFAA00;
		return 0xFFFF5555;
	}

	// --- Scoreboard + Bossleiste (HUD-Ereignisse) ---

	private boolean ownsSidebar() {
		return m.scoreboard.isEnabled() || qol.streamer.active();
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
		RenderGameOverlayEvent.ElementType type = Mc.overlayType(event);
		if (type == RenderGameOverlayEvent.ElementType.ALL) {
			// Falls ein Bild vorher abgebrochen wurde: erst zurücksetzen.
			if (hiddenSlots != null) restoreSidebar();
			if (event.isCanceled() || !ownsSidebar()) return;
			hideSidebar();
		} else if (type == RenderGameOverlayEvent.ElementType.BOSSHEALTH && !event.isCanceled() && m.bossBar.isEnabled()) {
			int sw = Mc.resolution(event).getScaledWidth(), sh = Mc.resolution(event).getScaledHeight();
			Gfx g = Gfx.of(sw, sh);
			float scale = m.bossBar.scale.getFloat();
			int x = HudLayout.resolveX(m.bossBar.position(), (int) Math.ceil(Sidebar.BOSS_W * scale), sw);
			int y = HudLayout.resolveY(m.bossBar.position(), (int) Math.ceil(Sidebar.BOSS_H * scale), sh);
			g.push();
			g.translate(x, y);
			g.scale(scale);
			g.translate(-(sw / 2 - 91), -2);
			bossMoved = true;
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
	public void onOverlayPost(RenderGameOverlayEvent.Post event) {
		RenderGameOverlayEvent.ElementType type = Mc.overlayType(event);
		if (type == RenderGameOverlayEvent.ElementType.BOSSHEALTH && bossMoved) {
			bossMoved = false;
			Gfx.of(1, 1).pop();
		} else if (type == RenderGameOverlayEvent.ElementType.ALL) {
			ScoreObjective objective = restoreSidebar();
			if (objective != null && !Mc.hudHidden()) {
				try {
					int sw = Mc.resolution(event).getScaledWidth(), sh = Mc.resolution(event).getScaledHeight();
					Sidebar.drawInGame(Gfx.of(sw, sh), objective);
				} catch (RuntimeException | LinkageError ignored) {
					// Anzeige
				}
			}
		}
	}

	/** Vanilla soll die Seitenleiste nicht zeichnen: Anzeige-Slots 1 und 3–18 für dieses Bild leeren. */
	private void hideSidebar() {
		Scoreboard board = Mc.world() == null ? null : Mc.world().getScoreboard();
		if (board == null) return;
		try {
			if (!slotsLooked) {
				slotsLooked = true;
				for (Field f : Scoreboard.class.getDeclaredFields()) {
					if (f.getType() == ScoreObjective[].class) {
						f.setAccessible(true);
						slotsField = f;
						break;
					}
				}
			}
			if (slotsField == null) return;
			ScoreObjective[] slots = (ScoreObjective[]) slotsField.get(board);
			ScoreObjective shown = sidebarObjective(board);
			if (shown == null) return;
			hiddenSlots = slots.clone();
			for (int i = 1; i < slots.length; i++) {
				if (i == 2) continue;
				slots[i] = null;
			}
			hiddenObjective = shown;
		} catch (IllegalAccessException | RuntimeException e) {
			hiddenSlots = null;
		}
	}

	private ScoreObjective hiddenObjective;

	private ScoreObjective restoreSidebar() {
		ScoreObjective shown = hiddenObjective;
		hiddenObjective = null;
		if (hiddenSlots == null) return null;
		try {
			Scoreboard board = Mc.world() == null ? null : Mc.world().getScoreboard();
			if (board != null && slotsField != null) {
				ScoreObjective[] slots = (ScoreObjective[]) slotsField.get(board);
				System.arraycopy(hiddenSlots, 0, slots, 0, Math.min(slots.length, hiddenSlots.length));
			}
		} catch (IllegalAccessException | RuntimeException ignored) {
			// nichts
		}
		hiddenSlots = null;
		return shown;
	}

	/** Welches Ziel Vanilla anzeigen würde (Team-Farbe vor dem allgemeinen Slot). */
	private static ScoreObjective sidebarObjective(Scoreboard board) {
		EntityPlayer p = Mc.player();
		if (p == null) return null;
		ScorePlayerTeam team = board.getPlayersTeam(p.getName());
		if (team != null) {
			//? if >=1.11 {
			/*int color = team.getColor().getColorIndex();
			*///?} else
			int color = team.getChatFormat().getColorIndex();
			if (color >= 0) {
				ScoreObjective o = board.getObjectiveInDisplaySlot(3 + color);
				if (o != null) return o;
			}
		}
		return board.getObjectiveInDisplaySlot(1);
	}

	// --- Treffer ---

	/** Aus PvpFeatures beim Angriff: Kritisch/Schärfe merken (Partikel kommen bei bestätigtem Treffer dazu). */
	public static void onAttack(EntityPlayer player, Entity target) {
		LegacyQol q = instance;
		if (q == null || player == null || target == null) return;
		q.critTarget = target;
		q.critPending = player.fallDistance > 0 && !player.onGround && !player.isOnLadder() && !player.isInWater()
				&& !player.isRiding();
		q.magicPending = false;
		if (target instanceof EntityLivingBase) {
			//? if >=1.9 {
			/*ItemStack held = player.getHeldItemMainhand();
			*///?} else
			ItemStack held = player.getHeldItem();
			try {
				q.magicPending = !Mc.isEmpty(held)
						&& EnchantmentHelper.getModifierForCreature(held, ((EntityLivingBase) target).getCreatureAttribute()) > 0;
			} catch (RuntimeException ignored) {
				q.magicPending = false;
			}
		}
	}

	/** Aus PvpFeatures: Treffer bestätigt (Ziel zeigt die Schadens-Animation). */
	public static void onHitConfirmed(Entity target) {
		LegacyQol q = instance;
		if (q == null) return;
		long now = System.currentTimeMillis();
		q.qol.onHitConfirmed(q.critPending, now);
		int extra = q.qol.extraParticles();
		if (extra > 0 && target != null && target == q.critTarget) {
			Minecraft mc = Minecraft.getMinecraft();
			for (int i = 0; i < extra; i++) {
				if (q.critPending) mc.effectRenderer.emitParticleAtEntity(target, EnumParticleTypes.CRIT);
				if (q.magicPending) mc.effectRenderer.emitParticleAtEntity(target, EnumParticleTypes.CRIT_MAGIC);
			}
		}
		q.critPending = false;
		q.magicPending = false;
	}

	// --- Server-HUD ---

	public static String maskAddress(String address) {
		LegacyQol q = instance;
		return q == null ? address : q.qol.streamer.address(address, q.m.streamerHideIp.get());
	}

	/** Ist das Modul in dieser Version möglich? */
	public static boolean supported(dev.theredstonee.trsclient.core.module.Module module) {
		LegacyQol q = instance;
		if (q == null) return true;
		return module != q.m.titles;
	}

	// --- Hilfen je Version ---

	static String itemId(Item item) {
		//? if >=1.9 {
		/*ResourceLocation rl = Item.REGISTRY.getNameForObject(item);
		*///?} else
		ResourceLocation rl = (ResourceLocation) Item.itemRegistry.getNameForObject(item);
		if (rl == null) return "";
		String id = rl.toString();
		return id.substring(id.indexOf(':') + 1);
	}

	static int count(ItemStack s) {
		//? if >=1.11 {
		/*return s.getCount();
		*///?} else
		return s.stackSize;
	}

	/** Trank mit Sofortheilung? */
	static boolean healing(ItemStack s) {
		try {
			//? if >=1.9 {
			/*for (net.minecraft.potion.PotionEffect e : net.minecraft.potion.PotionUtils.getEffectsFromStack(s)) {
				if (e.getPotion() == net.minecraft.init.MobEffects.INSTANT_HEALTH) return true;
			}
			*///?} else {
			if (!(s.getItem() instanceof net.minecraft.item.ItemPotion)) return false;
			List<net.minecraft.potion.PotionEffect> effects = ((net.minecraft.item.ItemPotion) s.getItem()).getEffects(s);
			if (effects == null) return false;
			for (net.minecraft.potion.PotionEffect e : effects) {
				if (e.getPotionID() == net.minecraft.potion.Potion.heal.id) return true;
			}
			//?}
		} catch (RuntimeException ignored) {
			// kein Trank
		}
		return false;
	}

	/** 1.8.9: Wurftrank über den Schadenswert; ab 1.9 eigener Gegenstand (splash_potion). */
	static boolean splash(ItemStack s) {
		//? if >=1.9 {
		/*return false;
		*///?} else
		return s.getItem() instanceof net.minecraft.item.ItemPotion && net.minecraft.item.ItemPotion.isSplash(s.getMetadata());
	}

	static void play(QolSound sound, float pitch) {
		try {
			//? if >=1.9 {
			/*String name = sound.legacy != null ? sound.legacy : QolSound.PLING.legacy;
			net.minecraft.util.SoundEvent ev = net.minecraft.util.SoundEvent.REGISTRY.getObject(new ResourceLocation(name));
			if (ev == null) ev = net.minecraft.util.SoundEvent.REGISTRY.getObject(new ResourceLocation(QolSound.PLING.legacy));
			if (ev != null) Minecraft.getMinecraft().getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(ev, pitch));
			*///?} else {
			String name = sound.old != null ? sound.old : QolSound.PLING.old;
			Minecraft.getMinecraft().getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation(name), pitch));
			//?}
		} catch (RuntimeException | LinkageError ignored) {
			// kein Ton
		}
	}

	// --- HUD-Elemente ---

	/** Neue HUD-Elemente für den HudManager. */
	public static List<HudElement> join(List<HudElement> base, TrsModules modules) {
		List<HudElement> all = new ArrayList<HudElement>(base);
		QolModules m = modules.qol;
		all.addAll(Arrays.<HudElement>asList(new WarningsHud(m.warnings), new CounterHud(m.itemCounter, m), new Sidebar(m.scoreboard, m),
				new BossBarHud(m.bossBar)));
		return all;
	}

	/** Nach allen HUD-Elementen: Hitmarker, Hinweise ohne Warnungs-Modul. */
	public static void overlay(Gfx g, FontRenderer font) {
		LegacyQol q = instance;
		if (q == null) return;
		long now = System.currentTimeMillis();
		GfxCanvas c = GfxCanvas.of(g, font);
		QolPanels.drawHitmarker(c, q.qol, g.width(), g.height(), now);
		if (!q.m.warnings.isEnabled() && !q.qol.notices.isEmpty()) {
			HudModule w = q.m.warnings;
			int bw = QolPanels.warningsWidth(q.qol, null, MEASURE, false, now);
			int bh = QolPanels.warningsHeight(q.qol, null, false, now);
			g.push();
			g.translate(HudLayout.resolveX(w.position(), bw, g.width()), HudLayout.resolveY(w.position(), bh, g.height()));
			QolPanels.drawWarnings(c, q.qol, null, MEASURE, false, now, 0x90000000, true);
			g.pop();
		}
	}

	static final class WarningsHud extends HudElement {
		WarningsHud(HudModule module) {
			super(module);
		}

		@Override
		public boolean visible() {
			return instance != null && QolPanels.warningsVisible(instance.qol, WHERE, System.currentTimeMillis());
		}

		@Override
		public int width(FontRenderer font, boolean preview) {
			return QolPanels.warningsWidth(Qol.get(), WHERE, MEASURE, preview, System.currentTimeMillis());
		}

		@Override
		public int height(FontRenderer font, boolean preview) {
			return QolPanels.warningsHeight(Qol.get(), WHERE, preview, System.currentTimeMillis());
		}

		@Override
		public void draw(Gfx g, FontRenderer font, boolean preview) {
			int bg = module.backgroundArgb();
			QolPanels.drawWarnings(GfxCanvas.of(g, font), Qol.get(), WHERE, MEASURE, preview, System.currentTimeMillis(),
					bg == 0 ? 0x5A000000 : bg, module.shadow());
		}
	}

	static final class CounterHud extends HudElement {
		private static final int PAD = 4;
		private static final int ROW = 18;
		private final QolModules m;
		private final List<ItemCounter.Kind> kinds = new ArrayList<ItemCounter.Kind>();
		private final List<String> texts = new ArrayList<String>();
		private ItemStack[] fallback;

		CounterHud(HudModule module, QolModules m) {
			super(module);
			this.m = m;
		}

		private boolean on(ItemCounter.Kind k) {
			switch (k) {
				case ARROWS:
					return m.countArrows.get();
				case TOTEMS:
					//? if >=1.11 {
					/*return m.countTotems.get();
					*///?} else
					return false;
				case HEALING:
					return m.countHealing.get();
				case SPLASH:
					return m.countSplash.get();
				case GAPPLES:
					return m.countGapples.get();
				case PEARLS:
					return m.countPearls.get();
				default:
					return m.countBlocks.get();
			}
		}

		private void collect(boolean preview) {
			kinds.clear();
			texts.clear();
			Qol q = Qol.get();
			if (q == null) return;
			for (ItemCounter.Kind k : ItemCounter.Kind.values()) {
				if (!on(k)) continue;
				int n = preview ? new int[]{64, 1, 3, 2, 5, 16, 128}[k.ordinal()] : q.counter.count(k);
				if (n == 0 && m.countHideEmpty.get() && !preview) continue;
				kinds.add(k);
				texts.add(String.valueOf(n));
			}
		}

		@Override
		public boolean visible() {
			collect(false);
			return !kinds.isEmpty();
		}

		private boolean horizontal() {
			return m.countLayout.get() == ItemCounter.Layout.HORIZONTAL;
		}

		@Override
		public int width(FontRenderer font, boolean preview) {
			collect(preview);
			int w = 0;
			if (horizontal()) {
				for (String t : texts) w += 16 + 2 + font.getStringWidth(t) + 6;
				if (w > 0) w -= 6;
			} else {
				for (String t : texts) w = Math.max(w, 16 + 3 + font.getStringWidth(t));
			}
			return Math.max(20, w + PAD * 2);
		}

		@Override
		public int height(FontRenderer font, boolean preview) {
			collect(preview);
			int h = kinds.isEmpty() ? 0 : (horizontal() ? ROW : kinds.size() * ROW);
			return Math.max(10, h + PAD * 2 - 2);
		}

		@Override
		public void draw(Gfx g, FontRenderer font, boolean preview) {
			int w = width(font, preview);
			int h = height(font, preview);
			collect(preview);
			int bg = module.backgroundArgb();
			if (bg != 0) g.fill(0, 0, w, h, bg);
			Qol q = Qol.get();
			int x = PAD, y = PAD - 1;
			for (int i = 0; i < kinds.size(); i++) {
				ItemCounter.Kind k = kinds.get(i);
				Object icon = q == null ? null : q.counter.icon(k);
				ItemStack stack = icon instanceof ItemStack && !preview ? (ItemStack) icon : fallback(k);
				if (stack != null) g.item(font, stack, x, y);
				String t = texts.get(i);
				g.text(font, t, x + 19, y + 4, "0".equals(t) ? 0xFFFF5555 : textColor(), module.shadow());
				if (horizontal()) x += 16 + 2 + font.getStringWidth(t) + 6;
				else y += ROW;
			}
		}

		private ItemStack fallback(ItemCounter.Kind k) {
			if (fallback == null) {
				String[] names = {"arrow", "totem_of_undying", "potion", "potion", "golden_apple", "ender_pearl", "cobblestone"};
				fallback = new ItemStack[names.length];
				for (int i = 0; i < names.length; i++) {
					Item item = Mc.item(names[i]);
					fallback[i] = item == null ? null : new ItemStack(item);
				}
			}
			return fallback[k.ordinal()];
		}
	}

	/** Scoreboard: Editor-Vorschau und Zeichnen an der gespeicherten Stelle. */
	static final class Sidebar extends HudElement {
		static final int BOSS_W = 182;
		static final int BOSS_H = 19;
		private static final int PAD = 3;
		private static Sidebar instanceSidebar;
		private final QolModules m;

		Sidebar(HudModule module, QolModules m) {
			super(module);
			this.m = m;
			instanceSidebar = this;
		}

		@Override
		public boolean visible() {
			return false;
		}

		@Override
		public int width(FontRenderer font, boolean preview) {
			return layoutWidth(font, sample());
		}

		@Override
		public int height(FontRenderer font, boolean preview) {
			return layoutHeight(sample());
		}

		@Override
		public void draw(Gfx g, FontRenderer font, boolean preview) {
			drawRows(g, font, sample());
		}

		private List<String[]> sample() {
			List<String[]> rows = new ArrayList<String[]>();
			rows.add(new String[]{I18n.tr("hud.scoreboardPreview")});
			rows.add(new String[]{"Kills", "§c12"});
			rows.add(new String[]{"Coins", "§c350"});
			rows.add(new String[]{"", "§c1"});
			rows.add(new String[]{"play.example.net", "§c0"});
			return rows;
		}

		private int layoutWidth(FontRenderer font, List<String[]> rows) {
			int w = 0;
			boolean numbers = !m.scoreboardHideNumbers.get();
			for (int i = 0; i < rows.size(); i++) {
				String[] r = rows.get(i);
				if (i == 0) {
					if (m.scoreboardTitle.get()) w = Math.max(w, font.getStringWidth(r[0]));
					continue;
				}
				int lw = font.getStringWidth(r[0]);
				if (numbers && r.length > 1) lw += font.getStringWidth(": ") + font.getStringWidth(r[1]);
				w = Math.max(w, lw);
			}
			return w + PAD * 2;
		}

		private int layoutHeight(List<String[]> rows) {
			return (m.scoreboardTitle.get() ? 10 : 0) + (rows.size() - 1) * 9 + PAD;
		}

		private void drawRows(Gfx g, FontRenderer font, List<String[]> rows) {
			int w = layoutWidth(font, rows);
			int y = 0;
			int bg = module.backgroundArgb();
			boolean shadow = module.shadow();
			int color = textColor();
			if (m.scoreboardTitle.get() && !rows.isEmpty()) {
				int titleBg = bg == 0 ? 0 : (Math.min(255, ((bg >>> 24) & 0xFF) + 24) << 24) | (bg & 0xFFFFFF);
				if (titleBg != 0) g.fill(0, 0, w, 10, titleBg);
				String t = rows.get(0)[0];
				g.text(font, t, (w - font.getStringWidth(t)) / 2, 1, color, shadow);
				y = 10;
			}
			int lines = rows.size() - 1;
			if (bg != 0 && lines > 0) g.fill(0, y, w, y + lines * 9 + PAD, bg);
			boolean numbers = !m.scoreboardHideNumbers.get();
			for (int i = 1; i < rows.size(); i++) {
				String[] r = rows.get(i);
				g.text(font, r[0], PAD, y + 1, color, shadow);
				if (numbers && r.length > 1) g.text(font, r[1], w - PAD - font.getStringWidth(r[1]), y + 1, color, shadow);
				y += 9;
			}
		}

		static void drawInGame(Gfx g, ScoreObjective objective) {
			Sidebar s = instanceSidebar;
			if (s == null) return;
			FontRenderer font = Mc.font();
			List<String[]> rows = rows(objective);
			int sw = g.width(), sh = g.height();
			float scale = s.module.scale.getFloat();
			int bw = (int) Math.ceil(s.layoutWidth(font, rows) * scale);
			int bh = (int) Math.ceil(s.layoutHeight(rows) * scale);
			g.push();
			g.translate(HudLayout.resolveX(s.module.position(), bw, sw), HudLayout.resolveY(s.module.position(), bh, sh));
			g.scale(scale);
			s.drawRows(g, font, rows);
			g.pop();
		}

		private static List<String[]> rows(ScoreObjective objective) {
			LegacyQol q = instance;
			StreamerMode streamer = q == null ? null : q.qol.streamer;
			List<String[]> rows = new ArrayList<String[]>();
			rows.add(new String[]{mask(streamer, objective.getDisplayName())});
			Scoreboard board = objective.getScoreboard();
			List<Score> list = new ArrayList<Score>();
			for (Score e : board.getSortedScores(objective)) {
				if (e.getPlayerName() != null && !e.getPlayerName().startsWith("#")) list.add(e);
			}
			Collections.sort(list, (a, b) -> a.getScorePoints() != b.getScorePoints() ? Integer.compare(b.getScorePoints(), a.getScorePoints())
					: String.CASE_INSENSITIVE_ORDER.compare(a.getPlayerName(), b.getPlayerName()));
			for (int i = 0; i < list.size() && i < 15; i++) {
				Score e = list.get(i);
				ScorePlayerTeam team = board.getPlayersTeam(e.getPlayerName());
				String name = ScorePlayerTeam.formatPlayerName(team, e.getPlayerName());
				rows.add(new String[]{mask(streamer, name), "§c" + e.getScorePoints()});
			}
			return rows;
		}

		private static String mask(StreamerMode streamer, String text) {
			return streamer == null || !streamer.active() ? text : streamer.replace(text);
		}
	}

	/** Bossleiste: nur Editor-Vorschau (im Spiel verschiebt das HUD-Ereignis Vanillas Leiste). */
	static final class BossBarHud extends HudElement {
		BossBarHud(HudModule module) {
			super(module);
		}

		@Override
		public boolean visible() {
			return false;
		}

		@Override
		public int width(FontRenderer font, boolean preview) {
			return Sidebar.BOSS_W;
		}

		@Override
		public int height(FontRenderer font, boolean preview) {
			return Sidebar.BOSS_H;
		}

		@Override
		public void draw(Gfx g, FontRenderer font, boolean preview) {
			String name = I18n.tr("hud.bossBarPreview");
			g.text(font, name, (Sidebar.BOSS_W - font.getStringWidth(name)) / 2, 1, 0xFFFFFFFF, true);
			g.fill(0, 10, Sidebar.BOSS_W, 15, 0xFF3B0A3A);
			g.fill(0, 10, Sidebar.BOSS_W * 2 / 3, 15, 0xFFD03BD0);
		}
	}

	/** Für Tests: Anzahl eingefügter Schatten-Zeilen. */
	public int keptLines() {
		return allLines.size();
	}
}
