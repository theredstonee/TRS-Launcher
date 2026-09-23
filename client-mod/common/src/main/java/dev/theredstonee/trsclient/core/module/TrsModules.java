package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.KeyDefaults;
import dev.theredstonee.trsclient.core.hud.Crosshair;
import dev.theredstonee.trsclient.core.hud.HudProfiles;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.hud.HudPosition;

/**
 * Alle Module des TRS Clients mit ihren Standardwerten.
 * Versionsunabhängig – jede Minecraft-Version liefert nur Rendering und Hooks dazu.
 */
public final class TrsModules {
	public final ModuleRegistry registry = new ModuleRegistry();
	/** Gespeicherte HUD-Layouts (wird nach den Modulen erstellt). */
	public final HudProfiles profiles;
	/** Stand der Standard-Tastenbelegungen (Migration alter Belegungen). */
	public final KeyDefaults keyDefaults = new KeyDefaults();

	public final HudModule fps;
	public final HudModule cps;
	public final HudModule keystrokes;
	public final HudModule ping;
	public final HudModule armor;
	public final HudModule effects;
	public final HudModule coords;
	public final HudModule clock;
	public final HudModule memory;
	public final HudModule server;
	public final HudModule packs;
	public final HudModule toggleSprint;
	public final HudModule toggleSneak;
	public final HudModule reach;
	public final HudModule combo;
	public final HudModule speed;
	public final HudModule minimap;
	public final Module crosshair;
	public final Module hitColor;
	public final Module freelook;
	public final Module zoom;
	public final Module fullbright;
	public final Module titleScreen;
	public final Module oldAnimations;
	public final Module lowFire;
	public final Module blockOutline;
	public final Module hitboxes;
	public final Module noHurtCam;
	public final Module chat;
	public final Module autoGg;
	public final Module textHotkeys;
	public final Module waypoints;
	/** TRS API: Abzeichen, TRS-Umhänge, Online-Status (siehe core.online). */
	public final Module trsOnline;
	/** Stoff-Simulation für alle Umhänge (Vanilla, OptiFine, TRS). */
	public final Module capePhysics;

	public final BoolSetting keystrokesShowCps;
	public final BoolSetting keystrokesShowSpace;
	public final NumberSetting zoomFactor;
	public final BoolSetting zoomSmooth;
	public final BoolSetting zoomScroll;
	public final BoolSetting zoomSlowMouse;

	public final BoolSetting armorDurability;
	public final BoolSetting armorPercent;
	public final BoolSetting armorHand;
	public final BoolSetting coordsDirection;
	public final BoolSetting coordsBiome;
	public final BoolSetting clockSeconds;
	public final BoolSetting clockTwelveHour;
	public final ChoiceSetting<Crosshair.Shape> crosshairShape;
	public final ColorSetting crosshairColor;
	public final NumberSetting crosshairSize;
	public final NumberSetting crosshairGap;
	public final NumberSetting crosshairThickness;
	public final BoolSetting crosshairOutline;
	public final BoolSetting crosshairAttack;
	public final ColorSetting hitColorColor;
	public final NumberSetting hitColorOpacity;
	/** Startbildschirm: animierte Redstone-Schaltung im Hintergrund (aus = ruhiges Standbild). */
	public final BoolSetting titleAnimated;

	// --- PvP-Anzeigen ---
	public final NumberSetting reachDecimals;
	public final NumberSetting reachHold;
	public final BoolSetting comboBest;
	public final NumberSetting comboTimeout;
	public final BoolSetting speedVertical;

	// --- PvP-Optik ---
	public final BoolSetting oldAnimationsNoDip;
	public final BoolSetting oldAnimationsBlockHit;
	public final NumberSetting lowFireHeight;
	public final ColorSetting blockOutlineColor;
	public final NumberSetting blockOutlineOpacity;
	public final NumberSetting blockOutlineWidth;
	public final ColorSetting hitboxColor;

	// --- Chat ---
	public final BoolSetting chatTimestamps;
	public final BoolSetting chatTimestampSeconds;
	public final BoolSetting chatStack;
	public final BoolSetting chatCopy;
	public final TextSetting autoGgText;
	public final NumberSetting autoGgDelay;
	public final TextSetting autoGgTriggers;
	public final TextSetting[] hotkeyTexts = new TextSetting[4];
	public final KeySetting[] hotkeyKeys = new KeySetting[4];

	// --- Wegpunkte / Minimap ---
	public final KeySetting waypointAddKey;
	public final KeySetting waypointListKey;
	public final BoolSetting waypointBeam;
	public final BoolSetting waypointDistance;
	public final BoolSetting waypointDeath;
	public final NumberSetting waypointRange;
	public final BoolSetting minimapRotate;
	public final ChoiceSetting<MinimapZoom> minimapZoom;
	public final NumberSetting minimapSize;
	public final BoolSetting minimapWaypoints;
	public final BoolSetting minimapPlayers;
	public final BoolSetting minimapCoords;

	// --- TRS-Online / Umhänge ---
	public final BoolSetting badgeTab;
	public final BoolSetting badgeNametag;
	public final BoolSetting trsCapes;
	public final NumberSetting capeStrength;
	public final NumberSetting capeWind;
	public final ChoiceSetting<CapeScope> capeScope;

	/** Für wen die Umhang-Physik rechnet. */
	public enum CapeScope implements ChoiceSetting.Option {
		OWN("Only my own"),
		ALL("All players");

		private final String label;

		CapeScope(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Zoomstufen der Minimap: Zellgröße in Pixeln und Blöcke je Zelle. */
	public enum MinimapZoom implements ChoiceSetting.Option {
		FAR("Far", 1, 2),
		NORMAL("Normal", 2, 1),
		NEAR("Near", 3, 1),
		CLOSE("Very close", 4, 1);

		private final String label;
		private final int pixels;
		private final int blocks;

		MinimapZoom(String label, int pixels, int blocks) {
			this.label = label;
			this.pixels = pixels;
			this.blocks = blocks;
		}

		@Override
		public String label() {
			return label;
		}

		/** Kantenlänge einer Zelle in GUI-Pixeln. */
		public int pixels() {
			return pixels;
		}

		/** Blöcke je Zelle. */
		public int blocks() {
			return blocks;
		}
	}

	public TrsModules() {
		fps = registry.register(new HudModule("fps", "FPS", "Frames per second", true,
				new HudPosition(HudAnchor.TOP_LEFT, 0.005, 0.01)));
		cps = registry.register(new HudModule("cps", "CPS", "Clicks per second (left | right)", true,
				new HudPosition(HudAnchor.TOP_LEFT, 0.005, 0.075)));
		keystrokes = registry.register(new HudModule("keystrokes", "Keystrokes", "WASD, mouse buttons and space bar", true,
				new HudPosition(HudAnchor.BOTTOM_RIGHT, -0.005, -0.08)));
		ping = registry.register(new HudModule("ping", "Ping", "Latency to the server (not in singleplayer)", true,
				new HudPosition(HudAnchor.TOP_LEFT, 0.005, 0.14)));
		armor = registry.register(new HudModule("armor", "Armor", "Worn armor with durability", false,
				new HudPosition(HudAnchor.CENTER_LEFT, 0.005, 0.0)));
		effects = registry.register(new HudModule("effects", "Potion Effects", "Active effects with remaining time", false,
				new HudPosition(HudAnchor.CENTER_RIGHT, -0.005, 0.0)));
		coords = registry.register(new HudModule("coords", "Coordinates", "Position, facing direction and biome", false,
				new HudPosition(HudAnchor.TOP_LEFT, 0.005, 0.205)));
		clock = registry.register(new HudModule("clock", "Clock", "Your computer's real time", false,
				new HudPosition(HudAnchor.TOP_RIGHT, -0.005, 0.13)));
		memory = registry.register(new HudModule("memory", "Memory", "Memory (RAM) used by the game", false,
				new HudPosition(HudAnchor.TOP_RIGHT, -0.005, 0.195)));
		server = registry.register(new HudModule("server", "Server Address",
				"Address of the current server (not in singleplayer)", false,
				new HudPosition(HudAnchor.TOP_RIGHT, -0.005, 0.26)));
		packs = registry.register(new HudModule("packs", "Active Resource Packs", "List of the enabled resource packs", false,
				new HudPosition(HudAnchor.BOTTOM_LEFT, 0.005, -0.3)));
		toggleSprint = registry.register(new HudModule("toggleSprint", "Toggle Sprint",
				"Press the sprint key once to keep sprinting, press it again to stop. Shown in the HUD while active.", false,
				new HudPosition(HudAnchor.BOTTOM_RIGHT, -0.005, -0.42)));
		toggleSneak = registry.register(new HudModule("toggleSneak", "Toggle Sneak",
				"Press the sneak key once to keep sneaking, press it again to stop. Shown in the HUD while active.", false,
				new HudPosition(HudAnchor.BOTTOM_RIGHT, -0.005, -0.49)));
		reach = registry.register(new HudModule("reach", "Reach",
				"Distance of your last hit (display only – your reach itself stays unchanged)", false,
				new HudPosition(HudAnchor.CENTER_LEFT, 0.005, -0.12)));
		combo = registry.register(new HudModule("combo", "Combo",
				"Counts hits in a row; ends when you get hit yourself or stop hitting for a moment", false,
				new HudPosition(HudAnchor.CENTER_LEFT, 0.005, -0.06)));
		// Geschwindigkeit, Reichweite und Combo stehen übereinander links neben dem Fadenkreuz,
		// oberhalb der Rüstung (die auf 0.0 sitzt).
		speed = registry.register(new HudModule("speed", "Speed", "Blocks per second", false,
				new HudPosition(HudAnchor.CENTER_LEFT, 0.005, -0.18)));
		minimap = registry.register(new HudModule("minimap", "Minimap",
				"Small map of your surroundings (block colors from above) with waypoints. "
						+ "Shows loaded chunks only – no X-ray, no cave view.", false,
				new HudPosition(HudAnchor.TOP_RIGHT, -0.005, 0.01)));
		crosshair = registry.register(new Module("crosshair", "Crosshair",
				"Your own crosshair: shape, color, size and gap are freely adjustable", false));
		hitColor = registry.register(new Module("hitColor", "Hit Color",
				"Color that mobs and players briefly flash in when hit", false));
		freelook = registry.register(new Module("freelook", "Freelook",
				"Hold the key to orbit the camera around your character without changing your walking direction. "
						+ "Some servers forbid freelook – only use it where it is allowed.", false));
		zoom = registry.register(new Module("zoom", "Zoom", "Hold the key to zoom in", true));
		fullbright = registry.register(new Module("fullbright", "Fullbright", "Maximum brightness everywhere", false));
		titleScreen = registry.register(new Module("titleScreen", "Title Screen",
				"TRS title screen instead of the vanilla one", true));
		oldAnimations = registry.register(new Module("oldAnimations", "1.7 Animations",
				"Old hand movements: the hand does not dip during the attack cooldown, "
						+ "swing animation also while using an item (visual only)", false));
		lowFire = registry.register(new Module("lowFire", "Low Fire",
				"Pulls the flames at the edge of the screen down while you are burning", false));
		blockOutline = registry.register(new Module("blockOutline", "Block Outline",
				"Color and width of the outline around the block you are looking at", false));
		hitboxes = registry.register(new Module("hitboxes", "Hitboxes",
				"Shows the hitboxes of mobs like F3+B (display only – the boxes themselves do not change)", false));
		noHurtCam = registry.register(new Module("noHurtCam", "No Hurt Cam",
				"The camera no longer tilts when you take damage", false));
		chat = registry.register(new Module("chat", "Chat Improvements",
				"Timestamps, stacking of identical messages and copying with Ctrl+click", true));
		autoGg = registry.register(new Module("autoGg", "Auto-GG",
				"Automatically sends a message when a game ends. Off by default; at most once per minute. "
						+ "Some servers do not like it – only use it where it is allowed.", false));
		textHotkeys = registry.register(new Module("textHotkeys", "Text Hotkeys",
				"Four freely bindable keys each send a fixed text or command. "
						+ "Off by default, at most one message per second.", false));
		waypoints = registry.register(new Module("waypoints", "Waypoints",
				"Your own markers per world/server: press the key, pick a name and color; "
						+ "shown in game with distance and light beam, plus a death point.", true));
		trsOnline = registry.register(new Module("trsOnline", "TRS Online Features",
				"TRS badges and TRS capes of other players, your own cape and your online status for friends. "
						+ "Signs in with your Minecraft account, just like joining a server. Only shows that someone uses TRS.",
				true));
		capePhysics = registry.register(new Module("capePhysics", "Cape Physics",
				"Capes (Mojang, OptiFine, TRS) move like cloth: they swing when you walk, turn, jump and sneak.",
				true));

		fps.icon("gauge");
		cps.icon("mouse").category(Category.PVP);
		keystrokes.icon("keyboard").category(Category.PVP);
		ping.icon("signal");
		armor.icon("shield").category(Category.PVP);
		effects.icon("potion");
		coords.icon("compass").category(Category.WORLD);
		clock.icon("clock");
		memory.icon("chip");
		server.icon("signal").category(Category.WORLD);
		packs.icon("packs");
		toggleSprint.icon("run").category(Category.PVP);
		toggleSneak.icon("sneak").category(Category.PVP);
		crosshair.icon("crosshair").category(Category.PVP);
		hitColor.icon("hit").category(Category.PVP);
		freelook.icon("eye").category(Category.WORLD);
		zoom.icon("zoom").category(Category.WORLD);
		fullbright.icon("sun").category(Category.WORLD);
		titleScreen.icon("home");
		reach.icon("sword").category(Category.PVP);
		combo.icon("hit").category(Category.PVP);
		speed.icon("run").category(Category.PVP);
		minimap.icon("globe").category(Category.WORLD);
		oldAnimations.icon("sword").category(Category.PVP);
		lowFire.icon("hit").category(Category.PVP);
		blockOutline.icon("layers").category(Category.WORLD);
		hitboxes.icon("shield").category(Category.PVP);
		noHurtCam.icon("eye").category(Category.PVP);
		chat.icon("chat").category(Category.CHAT);
		autoGg.icon("chat").category(Category.CHAT);
		textHotkeys.icon("keyboard").category(Category.CHAT);
		waypoints.icon("compass").category(Category.WORLD);
		trsOnline.icon("redstone");
		capePhysics.icon("cape");

		keystrokesShowCps = keystrokes.add(new BoolSetting("showCps", "CPS below mouse buttons", true));
		keystrokesShowSpace = keystrokes.add(new BoolSetting("showSpace", "Show space bar", true));
		zoomFactor = zoom.add(new NumberSetting("factor", "Zoom factor", 4.0, 2.0, 10.0, 0.5, "×"));
		zoomSmooth = zoom.add(new BoolSetting("smooth", "Smooth transition", true));
		zoomScroll = zoom.add(new BoolSetting("scroll", "Mouse wheel changes zoom", true));
		zoomSlowMouse = zoom.add(new BoolSetting("slowMouse", "Slow down mouse", true));

		armorDurability = armor.add(new BoolSetting("durability", "Show durability", true));
		armorPercent = armor.add(new BoolSetting("percent", "Durability in percent", false));
		armorHand = armor.add(new BoolSetting("hand", "Held item", true));
		coordsDirection = coords.add(new BoolSetting("direction", "Facing direction", true));
		coordsBiome = coords.add(new BoolSetting("biome", "Biome", true));
		clockSeconds = clock.add(new BoolSetting("seconds", "Seconds", false));
		clockTwelveHour = clock.add(new BoolSetting("twelveHour", "12-hour format", false));
		crosshairShape = crosshair.add(new ChoiceSetting<>("shape", "Shape", Crosshair.Shape.class, Crosshair.Shape.CROSS));
		crosshairColor = crosshair.add(new ColorSetting("color", "Color", 0xFFFFFF));
		crosshairSize = crosshair.add(new NumberSetting("size", "Size", 4, 1, 12, 1, ""));
		crosshairGap = crosshair.add(new NumberSetting("gap", "Gap", 2, 0, 8, 1, ""));
		crosshairThickness = crosshair.add(new NumberSetting("thickness", "Thickness", 1, 1, 4, 1, ""));
		crosshairOutline = crosshair.add(new BoolSetting("outline", "Dark outline", true));
		crosshairAttack = crosshair.add(new BoolSetting("attack", "Attack cooldown", true));
		hitColorColor = hitColor.add(new ColorSetting("color", "Color", 0xB07CFF));
		hitColorOpacity = hitColor.add(new NumberSetting("opacity", "Opacity (%)", 70, 10, 100, 10, ""));
		titleAnimated = titleScreen.add(new BoolSetting("animated", "Animated background", true));

		reachDecimals = reach.add(new NumberSetting("decimals", "Decimal places", 2, 0, 3, 1, ""));
		reachHold = reach.add(new NumberSetting("hold", "Display time (s, 0 = always)", 4, 0, 10, 1, ""));
		comboBest = combo.add(new BoolSetting("best", "Also show best combo", false));
		comboTimeout = combo.add(new NumberSetting("timeout", "Combo ends after (s)", 3, 1, 10, 1, ""));
		speedVertical = speed.add(new BoolSetting("vertical", "Count height changes", false));

		oldAnimationsNoDip = oldAnimations.add(new BoolSetting("noDip", "Hand stays up (no cooldown movement)", true));
		oldAnimationsBlockHit = oldAnimations.add(new BoolSetting("blockHit", "Show swinging while using items", true));
		lowFireHeight = lowFire.add(new NumberSetting("height", "Lowering", 0.4, 0.1, 0.8, 0.1, ""));
		blockOutlineColor = blockOutline.add(new ColorSetting("color", "Color", 0x000000));
		blockOutlineOpacity = blockOutline.add(new NumberSetting("opacity", "Opacity (%)", 40, 10, 100, 10, ""));
		blockOutlineWidth = blockOutline.add(new NumberSetting("width", "Width", 2, 1, 6, 1, ""));
		hitboxColor = hitboxes.add(new ColorSetting("color", "Color", 0xFFFFFF));

		chatTimestamps = chat.add(new BoolSetting("timestamps", "Timestamps", false));
		chatTimestampSeconds = chat.add(new BoolSetting("timestampSeconds", "Timestamps with seconds", false));
		chatStack = chat.add(new BoolSetting("stack", "Stack identical messages (x2, x3)", true));
		chatCopy = chat.add(new BoolSetting("copy", "Ctrl+click copies a line", true));
		autoGgText = autoGg.add(new TextSetting("text", "Message", "gg", 100, "gg"));
		autoGgDelay = autoGg.add(new NumberSetting("delay", "Delay (s)", 1.0, 0.5, 5.0, 0.5, ""));
		autoGgTriggers = autoGg.add(new TextSetting("triggers", "Custom triggers (separated by ;)", "", 200,
				"e.g. game over;round ended"));
		for (int i = 0; i < hotkeyTexts.length; i++) {
			// Ein Übersetzungsschlüssel für alle vier: "Text {0}" / "Key {0}".
			TextSetting text = new TextSetting("text" + (i + 1), "Text {0}", "", 100, "empty");
			text.i18n("setting.textHotkeys.text", Integer.valueOf(i + 1));
			hotkeyTexts[i] = textHotkeys.add(text);
			KeySetting key = new KeySetting("key" + (i + 1), "Key {0}");
			key.i18n("setting.textHotkeys.key", Integer.valueOf(i + 1));
			hotkeyKeys[i] = textHotkeys.add(key);
		}

		waypointAddKey = waypoints.add(new KeySetting("addKey", "Create waypoint", "key.keyboard.b"));
		waypointListKey = waypoints.add(new KeySetting("listKey", "Open waypoints", "key.keyboard.n"));
		waypointBeam = waypoints.add(new BoolSetting("beam", "Light beam", true));
		waypointDistance = waypoints.add(new BoolSetting("distance", "Show distance", true));
		waypointDeath = waypoints.add(new BoolSetting("death", "Set death point automatically", true));
		waypointRange = waypoints.add(new NumberSetting("range", "Only up to (blocks, 0 = always)", 0, 0, 2000, 100, ""));
		minimapSize = minimap.add(new NumberSetting("size", "Size (pixels)", 96, 48, 160, 16, ""));
		minimapZoom = minimap.add(new ChoiceSetting<>("zoom", "Zoom", MinimapZoom.class, MinimapZoom.NORMAL));
		minimapRotate = minimap.add(new BoolSetting("rotate", "Rotate with view", true));
		minimapWaypoints = minimap.add(new BoolSetting("showWaypoints", "Show waypoints", true));
		minimapPlayers = minimap.add(new BoolSetting("showPlayers", "Show players (in view range only)", false));
		minimapCoords = minimap.add(new BoolSetting("coords", "Coordinates below the map", true));
		badgeTab = trsOnline.add(new BoolSetting("badgeTab", "Badge in the tab list", true));
		badgeNametag = trsOnline.add(new BoolSetting("badgeNametag", "Badge above names", true));
		trsCapes = trsOnline.add(new BoolSetting("capes", "Show TRS capes", true));
		capeStrength = capePhysics.add(new NumberSetting("strength", "Strength", 100, 20, 200, 10, "", "%"));
		capeWind = capePhysics.add(new NumberSetting("wind", "Wind", 100, 0, 200, 10, "", "%"));
		capeScope = capePhysics.add(new ChoiceSetting<>("scope", "For", CapeScope.class, CapeScope.ALL));

		registry.addPart(keyDefaults);
		// Profile zuletzt: sie sichern den Zustand aller HUD-Module.
		profiles = new HudProfiles(registry);
	}
}
