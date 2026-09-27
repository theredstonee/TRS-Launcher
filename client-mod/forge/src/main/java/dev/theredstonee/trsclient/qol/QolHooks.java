package dev.theredstonee.trsclient.qol;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.chat.ChatText;
import dev.theredstonee.trsclient.core.connect.AutoReconnect;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.input.KeyPresses;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.QolModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.pvp.ItemCounter;
import dev.theredstonee.trsclient.core.qol.Qol;
import dev.theredstonee.trsclient.core.qol.QolPanels;
import dev.theredstonee.trsclient.core.qol.QolSound;
import dev.theredstonee.trsclient.core.alert.Warnings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
//? if >=1.17
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Anbindung des Komfort-/PvP-Pakets ({@link Qol}) an Minecraft – dieselbe Datei in allen Mojmap-Bäumen (Fabric,
 * NeoForge, Forge, Forge 1.14–1.19). Hier stehen nur Lesen/Schreiben von Spielzustand und die Versionsweichen; alle
 * Entscheidungen trifft {@code core.qol}. Jeder Haken fängt Fehler ab – das Spiel darf daran nie scheitern.
 */
public final class QolHooks {
	/** Baum dieser Datei (vom Abgleich-Skript gesetzt): fabric, neoforge, forge, forge-legacy. */
	public static final String TREE = "forge";

	private static Qol qol;
	private static TrsModules modules;
	private static KeyPresses keys;
	private static Object lastLevel;
	private static boolean wasDead;
	private static ServerData lastServer;
	private static Screen disconnectScreen;
	private static Object lastTitle;
	private static Object lastOverlay;
	private static int tickCount;
	private static long errorLogged;
	/** Spielerposition für den Todespunkt-Kompass. */
	public static final QolPanels.Where WHERE = new QolPanels.Where();
	private static Field disconnectField;
	private static boolean disconnectFieldLooked;
	/** Gibt es in diesem Baum/dieser Version Mixins (Forge 1.14.4 hat keine)? */
	private static Boolean mixins;

	private QolHooks() {
	}

	/** Einmal beim Start des Loaders (nach dem Laden der Einstellungen). */
	public static void init(TrsModules m) {
		modules = m;
		keys = new KeyPresses(Keys::isDown);
		qol = Qol.init(m, new Qol.Platform() {
			@Override
			public void play(QolSound sound, float volume, float pitch) {
				QolHooks.play(sound, volume, pitch);
			}

			@Override
			public boolean focused() {
				return Minecraft.getInstance().isWindowActive();
			}

			@Override
			public long windowHandle() {
				try {
					//? if >=1.21.9 {
					/*return Mc.window().handle();
					*///?} else
					return Mc.window().getWindow();
				} catch (RuntimeException | LinkageError e) {
					return 0;
				}
			}
		});
	}

	public static Qol qol() {
		return qol;
	}

	/**
	 * Laufen die Haken per Mixin in diesem Baum? Erkannt am Gui-Mixin (Forge 1.14.4 hat keine Mixins → nur
	 * Tick-Funktionen). Wird gemerkt, sobald das HUD existiert.
	 */
	public static boolean mixins() {
		if (mixins == null) {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.gui == null) return true;
			mixins = guiAccess(mc) != null;
		}
		return mixins;
	}

	/**
	 * Gibt es das Modul in dieser Version/diesem Baum? Titel verschieben/skalieren: Fabric immer (vor 1.20.5 nur Größe),
	 * NeoForge/Forge erst ab 1.20.5 (davor zeichnet der Loader die Titel selbst); ohne Mixins nichts, was Mixins braucht.
	 */
	public static boolean supported(Module module) {
		if (modules == null) return true;
		QolModules m = modules.qol;
		boolean mixinModule = module == m.scoreboard || module == m.tabPing || module == m.bossBar || module == m.titles
				|| module == m.hitFeedback || module == m.chatFilter || module == m.streamer || module == m.mentions;
		if (mixinModule && !mixins()) return false;
		if (module == m.titles) {
			//? if >=1.20.5 {
			return true;
			//?} else
			/*return TREE.equals("fabric");*/
		}
		return true;
	}

	// --- Tick ---

	/** Einmal je Client-Tick (Ende). */
	public static void tick(Minecraft mc) {
		if (qol == null) return;
		try {
			tickInner(mc);
		} catch (RuntimeException | LinkageError e) {
			long now = System.currentTimeMillis();
			if (now - errorLogged > 60_000) {
				errorLogged = now;
				TrsClient.LOGGER.warn("Komfort/PvP: {}", e.toString());
			}
		}
	}

	private static void tickInner(Minecraft mc) {
		long now = System.currentTimeMillis();
		tickCount++;
		qol.tick(mc.getUser().getName(), now);
		Object level = mc.level;
		if (level != lastLevel) {
			if (lastLevel != null) qol.onWorldChange();
			lastLevel = level;
			wasDead = false;
			lastTitle = null;
			lastOverlay = null;
		}
		Player p = mc.player;
		if (p != null && mc.level != null) {
			ServerData sd = mc.getCurrentServer();
			if (sd != null) lastServer = sd;
			qol.reconnect.inWorld(sd == null ? null : sd.ip, now);
			tickPlayer(mc, p, now);
			tickServerTexts(mc, now);
			if (tickCount % 20 == 0 && modules.qol.streamer.isEnabled() && modules.qol.streamerOthers.get()) tickTab(mc);
		} else {
			WHERE.valid = false;
			qol.reconnect.leftWorld();
		}
		tickDisconnect(mc, now);
		if (Mc.screen() == null) {
			if (keys.pressed(modules.qol.streamerKey)) {
				qol.toggleStreamer(now);
				TrsClient.get().saveConfig();
			}
		} else {
			keys.releaseAll();
		}
	}

	private static void tickPlayer(Minecraft mc, Player p, long now) {
		WHERE.x = x(p);
		WHERE.z = z(p);
		WHERE.yaw = yaw(p);
		WHERE.dimension = Mc.dimensionId();
		WHERE.valid = true;
		boolean dead = p.getHealth() <= 0;
		if (dead && !wasDead) {
			qol.death.set(Math.floor(x(p)), Math.floor(y(p)), Math.floor(z(p)), Mc.dimensionId(), now);
			qol.onOwnDeath(now);
		}
		wasDead = dead;

		Warnings.Input in = qol.warnings.input();
		in.alive = !dead && p.isAlive();
		in.survival = !p.isCreative() && !p.isSpectator();
		in.health = p.getHealth();
		in.maxHealth = p.getMaxHealth();
		in.food = p.getFoodData().getFoodLevel();
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int i = 0; i < 4; i++) {
			ItemStack s = p.getItemBySlot(slots[i]);
			if (!s.isEmpty() && s.isDamageableItem()) {
				in.armorDamage[i] = s.getDamageValue();
				in.armorMax[i] = s.getMaxDamage();
				in.armorName[i] = s.getHoverName().getString();
			}
		}
		ItemStack hand = p.getMainHandItem();
		if (!hand.isEmpty() && hand.isDamageableItem()) {
			in.handDamage = hand.getDamageValue();
			in.handMax = hand.getMaxDamage();
			in.handName = hand.getHoverName().getString();
		}
		Inventory inv = inventory(p);
		in.inventoryFull = inv.getFreeSlot() < 0;
		qol.tickWarnings(now);

		if (tickCount % 4 == 0 && modules.qol.itemCounter.isEnabled()) {
			ItemCounter c = qol.counter;
			c.clear();
			int size = Math.min(41, inv.getContainerSize());
			for (int i = 0; i < size; i++) {
				if (i >= 36 && i < 40) continue;
				ItemStack s = inv.getItem(i);
				if (s.isEmpty()) continue;
				int mask = ItemCounter.classify(itemId(s), s.getItem() instanceof BlockItem, healing(s), false);
				ItemStack icon = null;
				if (c.needsIcon(mask)) {
					// Symbol ohne Stapelzahl (die Summe steht daneben).
					icon = s.copy();
					icon.setCount(1);
				}
				c.add(mask, s.getCount(), icon);
			}
		}
	}

	/** Titel und Aktionsleiste vom Server (Warteschlange, Auto-GG am Rundenende). */
	private static void tickServerTexts(Minecraft mc, long now) {
		QolGuiAccess acc = guiAccess(mc);
		if (acc == null) return;
		Object title = acc.trsclient$title();
		if (title != null && title != lastTitle) {
			lastTitle = title;
			String text = text(title);
			qol.onServerText(text, now);
			if (modules.autoGg.isEnabled()) {
				TrsClient.get().chat().autoGg().onTitle(text, dev.theredstonee.trsclient.core.chat.AutoGg.extraTriggers(modules.autoGgTriggers.get()),
						modules.qol.autoGgPresets.get(), now, (long) (modules.autoGgDelay.get() * 1000));
			}
		} else if (title == null) {
			lastTitle = null;
		}
		Object overlay = acc.trsclient$overlay();
		if (overlay != null && overlay != lastOverlay) {
			lastOverlay = overlay;
			qol.onServerText(text(overlay), now);
		}
	}

	private static void tickTab(Minecraft mc) {
		if (mc.getConnection() == null) return;
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			qol.streamer.knowPlayer(profileName(info));
		}
	}

	/** Getrennt-Bildschirm: Auto-Reconnect und Hintergrund-Hinweis. */
	private static void tickDisconnect(Minecraft mc, long now) {
		Screen s = Mc.screen();
		if (s instanceof DisconnectedScreen) {
			if (s != disconnectScreen) {
				disconnectScreen = s;
				String reason = reasonOf(s);
				QolModules m = modules.qol;
				if (m.autoReconnect.isEnabled()) {
					qol.reconnect.onDisconnected(lastServer == null ? null : lastServer.ip, reason, now,
							(long) (m.reconnectDelay.get() * 1000), m.reconnectAttempts.getInt(),
							m.reconnectMode.get() == QolModules.ReconnectMode.LENIENT, ChatText.words(m.reconnectNever.get()));
				}
				qol.onKicked(reason, now);
			}
			if (s instanceof QolReconnectButton) ((QolReconnectButton) s).trsclient$update(reconnectLabel(), reconnectCounting());
			if (modules.qol.autoReconnect.isEnabled() && qol.reconnect.due(now) && lastServer != null) connect(mc, lastServer);
		} else if (disconnectScreen != null) {
			disconnectScreen = null;
			qol.reconnect.screenClosed();
		}
	}

	/** Text des Knopfs im Getrennt-Bildschirm (Countdown/Status) oder null = kein Knopf. */
	public static String reconnectLabel() {
		if (qol == null || !modules.qol.autoReconnect.isEnabled()) return null;
		AutoReconnect r = qol.reconnect;
		long now = System.currentTimeMillis();
		switch (r.phase()) {
			case COUNTDOWN:
				return I18n.tr("reconnect.countdown", r.secondsLeft(now), r.attempts() + 1, modules.qol.reconnectAttempts.getInt());
			case CANCELLED:
				return I18n.tr("reconnect.cancelled");
			case BLOCKED:
				return I18n.tr("reconnect.blocked", kindLabel(r));
			case EXHAUSTED:
				return I18n.tr("reconnect.exhausted", modules.qol.reconnectAttempts.getInt());
			default:
				return null;
		}
	}

	private static String kindLabel(AutoReconnect r) {
		if (ChatText.containsAny(r.reason(), ChatText.words(modules.qol.reconnectNever.get()))) return I18n.tr("reconnect.kind.custom");
		String k = r.kind().name().toLowerCase(java.util.Locale.ROOT);
		return I18n.trOr("reconnect.kind." + k, I18n.tr("reconnect.kind.unknown"));
	}

	/** Soll der Getrennt-Bildschirm einen Auto-Reconnect-Knopf bekommen? */
	public static boolean reconnectEnabled() {
		return modules != null && modules.qol.autoReconnect.isEnabled();
	}

	/** Text als Komponente (Knopf-Beschriftung). */
	public static Component label(String text) {
		//? if >=1.19 {
		return Component.literal(text);
		//?} else
		/*return new net.minecraft.network.chat.TextComponent(text);*/
	}

	/** Nur Selbsttest: Server für den Auto-Reconnect vorgeben (sonst der zuletzt betretene). */
	public static void testServer(String ip) {
		//? if >=1.20.2 {
		lastServer = new ServerData("TRS-Test", ip, ServerData.Type.OTHER);
		//?} else
		/*lastServer = new ServerData("TRS-Test", ip, false);*/
	}

	/** Knopf „Abbrechen“ gedrückt. */
	public static void cancelReconnect() {
		if (qol != null) qol.reconnect.cancel();
	}

	/** Läuft gerade ein Countdown (Knopf aktiv)? */
	public static boolean reconnectCounting() {
		return qol != null && qol.reconnect.phase() == AutoReconnect.Phase.COUNTDOWN;
	}

	/** Mit einem Server verbinden (Auto-Reconnect, Fehlerbildschirm). */
	public static void connect(Minecraft mc, ServerData data) {
		Screen parent = new JoinMultiplayerScreen(new TitleScreen());
		//? if >=1.20.5 {
		ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(data.ip), data, false, null);
		//?} elif >=1.20 {
		/*ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(data.ip), data, false);
		*///?} elif >=1.17 {
		/*ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(data.ip), data);
		*///?} else
		/*Mc.setScreen(new ConnectScreen(parent, mc, data));*/
	}

	/** Grund im Getrennt-Bildschirm als Text (für „Fehler kopieren“). */
	public static String reason(Screen s) {
		return reasonOf(s);
	}

	/** Grund im Getrennt-Bildschirm (Feld per Typ gesucht – keine Namen, die sich je Loader unterscheiden). */
	private static String reasonOf(Screen s) {
		try {
			if (!disconnectFieldLooked) {
				disconnectFieldLooked = true;
				for (Field f : DisconnectedScreen.class.getDeclaredFields()) {
					if (Modifier.isStatic(f.getModifiers())) continue;
					//? if >=1.21 {
					if (f.getType() == net.minecraft.network.DisconnectionDetails.class) {
					//?} else
					/*if (f.getType() == Component.class) {*/
						f.setAccessible(true);
						disconnectField = f;
						break;
					}
				}
			}
			if (disconnectField == null) return "";
			Object v = disconnectField.get(s);
			//? if >=1.21 {
			return v == null ? "" : ((net.minecraft.network.DisconnectionDetails) v).reason().getString();
			//?} else
			/*return v == null ? "" : ((Component) v).getString();*/
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "";
		}
	}

	// --- Chat ---

	/** Chat-Filter (aus dem Chat-Mixin): true = Nachricht verwerfen. */
	public static boolean hideChat(Component message) {
		try {
			return qol != null && message != null && qol.hideChat(message.getString());
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/**
	 * Eingehende Chat-Nachricht vor Zeitstempel/Stapeln: Warteschlange, Erwähnung (Hervorhebung + Ton) und
	 * Streamer-Modus (Namen ersetzen). Gibt die (ggf. neu gebaute) Komponente zurück.
	 */
	public static Component chatIn(Component message) {
		if (qol == null || message == null) return message;
		try {
			String raw = message.getString();
			boolean mention = qol.onChat(raw, System.currentTimeMillis());
			boolean mask = qol.streamer.affects(raw);
			if (!mention && !mask) return message;
			return QolText.rewrite(message, mention ? qol.mentionWords() : null, mask ? qol.streamer : null,
					0xFF000000 | modules.qol.mentionsColor.rgb(), modules.qol.mentionsBold.get());
		} catch (RuntimeException | LinkageError e) {
			return message;
		}
	}

	/** Chat-Zeile kopieren: welche Maustaste (0 links + Strg, 1 rechts) ist erlaubt? */
	public static boolean copyAllowed(int button, boolean control) {
		if (modules == null) return button == 0 && control;
		QolModules.CopyMode mode = modules.qol.chatCopyMode.get();
		if (button == 0) return control && mode.ctrl();
		return button == 1 && mode.right();
	}

	/** Längerer Chat-Verlauf: Grenze statt Vanillas 100. */
	public static int chatHistory(int vanilla) {
		if (modules == null) return vanilla;
		return dev.theredstonee.trsclient.core.chat.ChatHistory.limit(modules.chat.isEnabled(), modules.qol.chatHistory.get());
	}

	// --- Treffer / Entities ---

	/** Aus PvpFeatures: eigener Schlag hat getroffen (Ziel zeigt die Schadens-Animation). */
	public static void onHitConfirmed(Object target) {
		if (qol == null) return;
		try {
			Minecraft mc = Minecraft.getInstance();
			//? if >=1.20 {
			boolean crit = mc.player != null && mc.player.fallDistance > 0 && !mc.player.onGround();
			//?} elif >=1.16 {
			/*boolean crit = mc.player != null && mc.player.fallDistance > 0 && !mc.player.isOnGround();
			*///?} else
			/*boolean crit = mc.player != null && mc.player.fallDistance > 0 && !mc.player.onGround;*/
			qol.onHitConfirmed(crit, System.currentTimeMillis());
		} catch (RuntimeException | LinkageError e) {
			// nur Anzeige
		}
	}

	/** Entity-Ereignis eines Lebewesens (Totem 35, Tod 3). */
	public static void onEntityEvent(LivingEntity entity, byte id) {
		if (qol == null || (id != 35 && id != 3) || !(entity instanceof Player)) return;
		try {
			Minecraft mc = Minecraft.getInstance();
			qol.onEntityEvent(profileName((Player) entity), entity == mc.player, id, System.currentTimeMillis());
		} catch (RuntimeException | LinkageError e) {
			// nur Anzeige
		}
	}

	/** Totem-Emitter an einer Entity gestartet (Vanilla-Ereignis 35). */
	public static void onTotem(net.minecraft.world.entity.Entity entity) {
		if (qol == null || !(entity instanceof Player)) return;
		try {
			qol.onEntityEvent(profileName((Player) entity), entity == Minecraft.getInstance().player, 35, System.currentTimeMillis());
		} catch (RuntimeException | LinkageError e) {
			// nur Anzeige
		}
	}

	/** Zusätzliche Kritisch-/Schärfe-Partikel. */
	public static int extraParticles() {
		return qol == null ? 0 : qol.extraParticles();
	}

	// --- Streamer-Modus ---

	/** Angezeigten Namen (Namensschild, Tabliste) ggf. ersetzen. */
	public static Component maskName(Component name) {
		if (qol == null || name == null || !qol.streamer.active()) return name;
		try {
			// Nur im Spiel-Thread (Anzeige) – nie im integrierten Server (sonst sähen Mitspieler den Ersatznamen).
			if (!Minecraft.getInstance().isSameThread()) return name;
			String raw = name.getString();
			if (!qol.streamer.affects(raw)) return name;
			return QolText.rewrite(name, null, qol.streamer, 0, false);
		} catch (RuntimeException | LinkageError e) {
			return name;
		}
	}

	/** Einzelner Spielername im Streamer-Modus. */
	public static String maskedName(String name) {
		return qol == null ? name : qol.streamer.name(name);
	}

	/** Server-Adresse (Server-HUD) ggf. verbergen. */
	public static String maskAddress(String address) {
		if (qol == null) return address;
		return qol.streamer.address(address, modules.qol.streamerHideIp.get());
	}

	/** Servername in der Serverliste verbergen, wenn er wie eine Adresse aussieht. */
	public static boolean hideServerName(String name) {
		return qol != null && qol.streamer.active() && modules.qol.streamerHideIp.get()
				&& dev.theredstonee.trsclient.core.streamer.StreamerMode.looksLikeAddress(name);
	}

	// --- Hilfen je Version ---

	static QolGuiAccess guiAccess(Minecraft mc) {
		//? if >=26.2 {
		/*Object gui = mc.gui == null ? null : mc.gui.hud;
		*///?} else
		Object gui = mc.gui;
		return gui instanceof QolGuiAccess ? (QolGuiAccess) gui : null;
	}

	static String text(Object o) {
		if (o instanceof Component) return ((Component) o).getString();
		return o == null ? "" : String.valueOf(o);
	}

	static String itemId(ItemStack s) {
		String d = s.getItem().getDescriptionId();
		int dot = d.lastIndexOf('.');
		return dot >= 0 ? d.substring(dot + 1) : d;
	}

	/** Trank mit Sofortheilung (Heiltrank I/II, auch als Wurftrank)? */
	static boolean healing(ItemStack s) {
		try {
			//? if >=1.20.5 {
			net.minecraft.world.item.alchemy.PotionContents pc = s.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
			if (pc == null || !pc.potion().isPresent()) return false;
			String name = pc.potion().get().getRegisteredName();
			//?} else
			/*String name = net.minecraft.world.item.alchemy.PotionUtils.getPotion(s).getName("");*/
			return name != null && name.endsWith("healing");
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	static String profileName(PlayerInfo info) {
		//? if >=1.21.9 {
		/*return info.getProfile().name();
		*///?} else
		return info.getProfile().getName();
	}

	static String profileName(Player p) {
		//? if >=1.21.9 {
		/*return p.getGameProfile().name();
		*///?} else
		return p.getGameProfile().getName();
	}

	static Inventory inventory(Player p) {
		//? if >=1.17 {
		return p.getInventory();
		//?} else
		/*return p.inventory;*/
	}

	static double x(Player p) {
		//? if >=1.15 {
		return p.getX();
		//?} else
		/*return p.x;*/
	}

	static double y(Player p) {
		//? if >=1.15 {
		return p.getY();
		//?} else
		/*return p.y;*/
	}

	static double z(Player p) {
		//? if >=1.15 {
		return p.getZ();
		//?} else
		/*return p.z;*/
	}

	static float yaw(Player p) {
		//? if >=1.17 {
		return p.getYRot();
		//?} else
		/*return p.yRot;*/
	}

	/** Klang abspielen (Name je Version aus {@link QolSound}). */
	static void play(QolSound sound, float volume, float pitch) {
		try {
			String name = sound.modern;
			//? if >=1.21.11 {
			/*SoundEvent ev = SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.withDefaultNamespace(name));
			*///?} elif >=1.21 {
			SoundEvent ev = SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.withDefaultNamespace(name));
			//?} elif >=1.19.3 {
			/*SoundEvent ev = SoundEvent.createVariableRangeEvent(new net.minecraft.resources.ResourceLocation("minecraft", name));
			*///?} else
			/*SoundEvent ev = new SoundEvent(new net.minecraft.resources.ResourceLocation("minecraft", name));*/
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, pitch, volume));
		} catch (RuntimeException | LinkageError ignored) {
			// kein Ton
		}
	}
}
