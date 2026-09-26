package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.chat.ChatMentions;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.hud.HudPosition;
import dev.theredstonee.trsclient.core.pvp.HitFeedback;
import dev.theredstonee.trsclient.core.pvp.ItemCounter;

/**
 * Komfort- und PvP-Paket (TRS Client {@link NewSince#QOL}): Chat-Erwähnungen und -Filter, längerer Verlauf,
 * Auto-Reconnect, Warteschlangen-/Hintergrund-Hinweise, Scoreboard/Tab/Bossleiste/Titel anpassen, Warnungen,
 * Zähler-HUD, Treffer-Feedback und Streamer-Modus. Alles ist nur Anzeige bzw. Komfort – einzige automatische
 * Spielaktion bleibt Auto-GG (ab Werk aus, siehe {@link dev.theredstonee.trsclient.core.chat.AutoGg}).
 *
 * <p>Die Module hängen am selben Register wie alle anderen ({@link TrsModules#qol}); Logik liegt in
 * {@code core.chat}, {@code core.connect}, {@code core.alert}, {@code core.pvp} und {@code core.streamer}.
 */
public final class QolModules {
	// --- Chat (Zusätze am bestehenden Modul „Chat-Verbesserungen“) ---
	public final BoolSetting chatTwelveHour;
	public final NumberSetting chatHistory;
	public final ChoiceSetting<CopyMode> chatCopyMode;
	public final BoolSetting autoGgPresets;

	// --- Erwähnungen ---
	public final Module mentions;
	public final BoolSetting mentionsOwnName;
	public final TextSetting mentionsWords;
	public final ColorSetting mentionsColor;
	public final BoolSetting mentionsBold;
	public final BoolSetting mentionsSound;
	public final ChoiceSetting<ChatMentions.Sound> mentionsSoundType;
	public final NumberSetting mentionsVolume;

	// --- Filter ---
	public final Module chatFilter;
	public final TextSetting chatFilterWords;

	// --- Auto-Reconnect ---
	public final Module autoReconnect;
	public final NumberSetting reconnectDelay;
	public final NumberSetting reconnectAttempts;
	public final ChoiceSetting<ReconnectMode> reconnectMode;
	public final TextSetting reconnectNever;

	// --- Warteschlange & Hintergrund ---
	public final Module queueAlerts;
	public final NumberSetting queuePosition;
	public final TextSetting queuePatterns;
	public final BoolSetting alertMention;
	public final BoolSetting alertDeath;
	public final BoolSetting alertKick;
	public final BoolSetting alertSound;
	public final BoolSetting alertFlash;

	// --- Scoreboard / Tab / Bossleiste / Titel ---
	public final HudModule scoreboard;
	public final BoolSetting scoreboardHideNumbers;
	public final BoolSetting scoreboardTitle;
	public final Module tabPing;
	public final BoolSetting tabPingColors;
	public final HudModule bossBar;
	public final HudModule titles;

	// --- Warnungen ---
	public final HudModule warnings;
	public final BoolSetting warnArmor;
	public final BoolSetting warnTools;
	public final NumberSetting warnDurability;
	public final BoolSetting warnHunger;
	public final NumberSetting warnHungerLevel;
	public final BoolSetting warnHealth;
	public final NumberSetting warnHealthLevel;
	public final BoolSetting warnInventory;
	public final BoolSetting warnSound;
	public final NumberSetting warnCooldown;
	public final BoolSetting warnDeathCompass;

	// --- Zähler ---
	public final HudModule itemCounter;
	public final BoolSetting countArrows;
	public final BoolSetting countTotems;
	public final BoolSetting countHealing;
	public final BoolSetting countSplash;
	public final BoolSetting countGapples;
	public final BoolSetting countPearls;
	public final BoolSetting countBlocks;
	public final BoolSetting countHideEmpty;
	public final ChoiceSetting<ItemCounter.Layout> countLayout;
	public final BoolSetting countTotemPops;

	// --- Treffer-Feedback ---
	public final Module hitFeedback;
	public final BoolSetting hitMarker;
	public final ColorSetting hitMarkerColor;
	public final NumberSetting hitMarkerSize;
	public final NumberSetting hitMarkerDuration;
	public final NumberSetting hitParticles;
	public final ChoiceSetting<HitFeedback.Sound> hitSound;
	public final NumberSetting hitVolume;

	// --- Streamer-Modus ---
	public final Module streamer;
	public final TextSetting streamerName;
	public final BoolSetting streamerOthers;
	public final BoolSetting streamerHideIp;
	public final KeySetting streamerKey;

	/** Womit eine Chat-Zeile kopiert wird. */
	public enum CopyMode implements ChoiceSetting.Option {
		CTRL_CLICK("Ctrl + left click"),
		RIGHT_CLICK("Right click"),
		BOTH("Both");

		private final String label;

		CopyMode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}

		public boolean ctrl() {
			return this != RIGHT_CLICK;
		}

		public boolean right() {
			return this != CTRL_CLICK;
		}
	}

	/** Bei welchen Gründen neu verbunden wird. */
	public enum ReconnectMode implements ChoiceSetting.Option {
		SAFE("Connection loss, restarts, full server"),
		LENIENT("Everything except bans and whitelist");

		private final String label;

		ReconnectMode(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	QolModules(ModuleRegistry registry, TrsModules m) {
		// Chat-Verbesserungen: 12-Stunden-Zeitstempel, längerer Verlauf, Kopieren wahlweise per Rechtsklick.
		chatTwelveHour = m.chat.add(new BoolSetting("timestampTwelveHour", "Timestamps in 12-hour format", false));
		chatHistory = m.chat.add(new NumberSetting("history", "Chat history (lines)", 500, 100, 1000, 50, ""));
		chatCopyMode = m.chat.add(new ChoiceSetting<CopyMode>("copyMode", "Copy a line with", CopyMode.class, CopyMode.BOTH));
		autoGgPresets = m.autoGg.add(new BoolSetting("presets", "Patterns for known servers (Hypixel, Minemen, PvP.Land)", true));

		mentions = registry.register(new Module("chatMentions", "Mentions",
				"Highlights chat lines that mention your name or your own keywords, optionally with a sound. "
						+ "Your own messages do not count. Display only.", false));
		mentionsOwnName = mentions.add(new BoolSetting("ownName", "My name", true));
		mentionsWords = mentions.add(new TextSetting("words", "More keywords (separated by ;)", "", 200, "e.g. team;party"));
		mentionsColor = mentions.add(new ColorSetting("color", "Highlight colour", 0xFFD84A));
		mentionsBold = mentions.add(new BoolSetting("bold", "Bold", true));
		mentionsSound = mentions.add(new BoolSetting("sound", "Sound", true));
		mentionsSoundType = mentions.add(new ChoiceSetting<ChatMentions.Sound>("soundType", "Sound type", ChatMentions.Sound.class,
				ChatMentions.Sound.PLING));
		mentionsVolume = mentions.add(new NumberSetting("volume", "Volume", 60, 10, 100, 10, "", "%"));

		chatFilter = registry.register(new Module("chatFilter", "Chat Filter",
				"Hides chat messages that contain one of your words (e.g. advertising). Only on your screen – "
						+ "nothing is sent or reported.", false));
		chatFilterWords = chatFilter.add(new TextSetting("words", "Hide messages with (separated by ;)", "", 200,
				"e.g. discord.gg;buy now"));

		autoReconnect = registry.register(new Module("autoReconnect", "Auto Reconnect",
				"When the connection drops or the server restarts, the disconnect screen reconnects after a countdown "
						+ "(with a cancel button). Never after a ban, a whitelist kick or a login from another place.",
				false));
		reconnectDelay = autoReconnect.add(new NumberSetting("delay", "Wait before reconnecting", 10, 3, 60, 1, "", " s"));
		reconnectAttempts = autoReconnect.add(new NumberSetting("attempts", "Attempts per server", 5, 1, 20, 1, ""));
		reconnectMode = autoReconnect.add(new ChoiceSetting<ReconnectMode>("mode", "Reconnect on", ReconnectMode.class,
				ReconnectMode.SAFE));
		reconnectNever = autoReconnect.add(new TextSetting("never", "Never on these reasons (separated by ;)", "", 200,
				"e.g. maintenance"));

		queueAlerts = registry.register(new Module("queueAlerts", "Queue & Alerts",
				"Tells you when you are (almost) through a server queue, and – while the game is in the background – when "
						+ "someone mentions you, you die or get kicked: notice, optional sound and a flashing taskbar.",
				false));
		queuePosition = queueAlerts.add(new NumberSetting("position", "Notify from queue position", 1, 1, 20, 1, ""));
		queuePatterns = queueAlerts.add(new TextSetting("patterns", "Own queue patterns (# = position, separated by ;)", "",
				200, "e.g. you are # in line"));
		alertMention = queueAlerts.add(new BoolSetting("mention", "In the background: mentions", true));
		alertDeath = queueAlerts.add(new BoolSetting("death", "In the background: death", true));
		alertKick = queueAlerts.add(new BoolSetting("kick", "In the background: disconnect", true));
		alertSound = queueAlerts.add(new BoolSetting("sound", "Sound", true));
		alertFlash = queueAlerts.add(new BoolSetting("flash", "Flash the taskbar", true));

		scoreboard = registry.register(new HudModule("scoreboard", "Scoreboard",
				"Move and resize the server's scoreboard in the HUD editor, change its background opacity and hide the "
						+ "red numbers.", false, new HudPosition(HudAnchor.CENTER_RIGHT, -0.005, 0.0)));
		scoreboardHideNumbers = scoreboard.add(new BoolSetting("hideNumbers", "Hide red numbers", true));
		scoreboardTitle = scoreboard.add(new BoolSetting("title", "Show title", true));
		tabPing = registry.register(new Module("tabPing", "Tab List Ping",
				"Shows the ping of every player in the tab list in milliseconds instead of bars.", false));
		tabPingColors = tabPing.add(new BoolSetting("colors", "Coloured by latency", true));
		bossBar = registry.register(new HudModule("bossBar", "Boss Bar",
				"Move and resize the boss bar in the HUD editor.", false,
				new HudPosition(HudAnchor.TOP_CENTER, 0.0, 0.005)));
		titles = registry.register(new HudModule("titles", "Titles",
				"Resize the big title texts of servers (e.g. \"VICTORY!\") and move them in the HUD editor "
						+ "(moving from Minecraft 1.20.5, before that only the size).", false,
				new HudPosition(HudAnchor.CENTER, 0.0, -0.12)));

		warnings = registry.register(new HudModule("warnings", "Warnings",
				"Short, subtle notices: armour or tool almost broken, low hunger, low health, inventory full – with an "
						+ "optional sound and a cooldown. After dying it shows the way back to your death point.",
				false, new HudPosition(HudAnchor.TOP_CENTER, 0.0, 0.1)));
		warnArmor = warnings.add(new BoolSetting("armor", "Armour almost broken", true));
		warnTools = warnings.add(new BoolSetting("tools", "Tool almost broken", true));
		warnDurability = warnings.add(new NumberSetting("durability", "Durability warning at", 10, 1, 50, 1, "", "%"));
		warnHunger = warnings.add(new BoolSetting("hunger", "Low hunger", true));
		warnHungerLevel = warnings.add(new NumberSetting("hungerLevel", "Hunger warning at (drumsticks)", 3, 1, 9, 1, ""));
		warnHealth = warnings.add(new BoolSetting("health", "Low health", true));
		warnHealthLevel = warnings.add(new NumberSetting("healthLevel", "Health warning at (hearts)", 3, 1, 9, 1, ""));
		warnInventory = warnings.add(new BoolSetting("inventory", "Inventory full", true));
		warnSound = warnings.add(new BoolSetting("sound", "Sound", false));
		warnCooldown = warnings.add(new NumberSetting("cooldown", "Repeat at most every", 60, 10, 300, 10, "", " s"));
		warnDeathCompass = warnings.add(new BoolSetting("deathCompass", "Way back to the death point", true));

		itemCounter = registry.register(new HudModule("itemCounter", "Item Counter",
				"Counts arrows, totems, healing and splash potions, golden apples, ender pearls and blocks in your "
						+ "inventory, with icons. Also counts how many totems each opponent popped (from 1.11).", false,
				new HudPosition(HudAnchor.CENTER_RIGHT, -0.005, 0.2)));
		countArrows = itemCounter.add(new BoolSetting("arrows", "Arrows", true));
		countTotems = itemCounter.add(new BoolSetting("totems", "Totems", true));
		countHealing = itemCounter.add(new BoolSetting("healing", "Healing potions", true));
		countSplash = itemCounter.add(new BoolSetting("splash", "Splash potions", false));
		countGapples = itemCounter.add(new BoolSetting("gapples", "Golden apples", true));
		countPearls = itemCounter.add(new BoolSetting("pearls", "Ender pearls", true));
		countBlocks = itemCounter.add(new BoolSetting("blocks", "Blocks", true));
		countHideEmpty = itemCounter.add(new BoolSetting("hideEmpty", "Hide empty counters", true));
		countLayout = itemCounter.add(new ChoiceSetting<ItemCounter.Layout>("layout", "Orientation", ItemCounter.Layout.class,
				ItemCounter.Layout.VERTICAL));
		countTotemPops = itemCounter.add(new BoolSetting("totemPops", "Totem pops of opponents", true));

		hitFeedback = registry.register(new Module("hitFeedback", "Hit Feedback",
				"A hit marker at the crosshair when your hit lands, more critical and sharpness particles and an optional "
						+ "hit sound. Display only – your attacks stay unchanged.", false));
		hitMarker = hitFeedback.add(new BoolSetting("marker", "Hit marker", true));
		hitMarkerColor = hitFeedback.add(new ColorSetting("markerColor", "Hit marker colour", 0xFFFFFF));
		hitMarkerSize = hitFeedback.add(new NumberSetting("markerSize", "Hit marker size", 4, 2, 10, 1, ""));
		hitMarkerDuration = hitFeedback.add(new NumberSetting("markerTime", "Hit marker time", 300, 100, 1000, 50, "", " ms"));
		hitParticles = hitFeedback.add(new NumberSetting("particles", "Critical particles", 1, 1, 5, 1, "", "×"));
		hitSound = hitFeedback.add(new ChoiceSetting<HitFeedback.Sound>("sound", "Hit sound", HitFeedback.Sound.class,
				HitFeedback.Sound.OFF));
		hitVolume = hitFeedback.add(new NumberSetting("volume", "Volume", 60, 10, 100, 10, "", "%"));

		streamer = registry.register(new Module("streamerMode", "Streamer Mode",
				"Replaces your name (and optionally other players' names) in chat, tab list, name tags and scoreboard "
						+ "with a name you choose, and hides server addresses. Only on your screen.", false));
		streamerName = streamer.add(new TextSetting("name", "Shown name", "Player", 16, "Player"));
		streamerOthers = streamer.add(new BoolSetting("others", "Also hide other players", false));
		streamerHideIp = streamer.add(new BoolSetting("hideIp", "Hide server addresses", true));
		streamerKey = streamer.add(new KeySetting("key", "Toggle key"));

		mentions.icon("bell").category(Category.CHAT);
		chatFilter.icon("mute").category(Category.CHAT);
		autoReconnect.icon("reset").category(Category.MISC);
		queueAlerts.icon("clock").category(Category.MISC);
		scoreboard.icon("layers").category(Category.HUD);
		tabPing.icon("signal").category(Category.HUD);
		bossBar.icon("crown").category(Category.HUD);
		titles.icon("digits").category(Category.HUD);
		warnings.icon("info").category(Category.HUD);
		itemCounter.icon("bow").category(Category.PVP);
		hitFeedback.icon("crosshair").category(Category.PVP);
		streamer.icon("eye").category(Category.MISC);
	}
}
