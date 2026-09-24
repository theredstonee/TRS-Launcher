package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.camera.FreelookState;
import dev.theredstonee.trsclient.core.cape.CapeSettings;
import dev.theredstonee.trsclient.core.config.KeyDefaults;
import dev.theredstonee.trsclient.core.hud.Crosshair;
import dev.theredstonee.trsclient.core.hud.HudProfiles;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.hud.HudPosition;
import dev.theredstonee.trsclient.core.render.ColorGrade;

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
	/** Leistung: alte Werte vor FPS-Boost/Leistungs-Check (für „Rückgängig“, wird gespeichert). */
	public final dev.theredstonee.trsclient.core.perf.UndoLog perfUndo = new dev.theredstonee.trsclient.core.perf.UndoLog();

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
	/** Farb-Nachbearbeitung des Spielbilds (Sättigung, Kontrast, Helligkeit …), HUD und Menüs bleiben unberührt. */
	public final Module colors;
	/** Emote-Rad und Emote-Animationen (braucht die TRS API, siehe core.emote). */
	public final Module emotes;
	/** Redstone: Signalstärke des angeschauten Bauteils (HUD). */
	public final HudModule redstoneSignal;
	/** Redstone: Signalstärke als Zahl über jedem Staub in der Nähe (Taste zum Umschalten). */
	public final Module redstoneOverlay;
	/** Redstone: Takt-Messer mit Oszilloskop (HUD). */
	public final HudModule redstoneClock;
	/** Clips & Aufnahme: Status der Aufnahme im Launcher (Tasten: Steuerung → TRS Client). */
	public final HudModule clips;

	// --- Leistung (Logik in core.perf, siehe Performance) ---
	/** FPS-Boost: Hauptschalter aller Leistungs-Funktionen, Voreinstellungen, Leistungs-Check. */
	public final Module fpsBoost;
	public final Module dynamicFps;
	public final Module entityCulling;
	public final Module particles;
	public final Module worldDetails;

	public final NumberSetting dynamicFpsUnfocused;
	public final NumberSetting dynamicFpsMinimized;
	public final NumberSetting dynamicFpsAfk;
	public final NumberSetting dynamicFpsAfkMinutes;
	public final BoolSetting dynamicFpsQuieter;
	public final NumberSetting dynamicFpsVolume;
	public final BoolSetting cullOcclusion;
	public final NumberSetting cullEntities;
	public final BoolSetting cullKeepPlayers;
	public final NumberSetting cullBlockEntities;
	public final NumberSetting cullNameTags;
	public final NumberSetting cullItems;
	public final NumberSetting cullFrames;
	public final NumberSetting particleLimit;
	public final NumberSetting particleAmount;
	public final BoolSetting particleNoExplosions;
	public final BoolSetting particleNoRain;
	public final BoolSetting particleNoSmoke;
	public final BoolSetting detailNoSky;
	public final BoolSetting detailNoStars;
	public final BoolSetting detailNoFog;
	public final BoolSetting detailNoWeather;
	public final BoolSetting detailNoAnimations;

	public final BoolSetting keystrokesShowCps;
	public final BoolSetting keystrokesShowSpace;
	public final KeySetting zoomKey;
	public final NumberSetting zoomFactor;
	public final BoolSetting zoomSmooth;
	public final BoolSetting zoomScroll;
	public final BoolSetting zoomSlowMouse;
	/** Vanillas filmische Kamera (weiche Maus) nur während des Zooms. */
	public final BoolSetting zoomCinematic;

	// --- Freelook ---
	public final KeySetting freelookKey;
	public final BoolSetting freelookToggle;
	public final ChoiceSetting<FreelookState.Perspective> freelookPerspective;
	/** Server, auf denen Freelook automatisch aus ist (Semikolon-getrennt). */
	public final TextSetting freelookServers;

	// --- Toggle-Sprint/-Schleichen ---
	public final BoolSetting toggleSprintOnlyForward;
	public final BoolSetting toggleSprintRemember;
	public final BoolSetting toggleSprintFlyBoost;
	public final NumberSetting toggleSprintFlyBoostFactor;
	public final BoolSetting toggleSneakRemember;

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
	public final ChoiceSetting<CapeSettings.Style> capeStyle;
	public final ChoiceSetting<CapeSettings.Wind> capeWindMode;
	public final NumberSetting capeWind;
	public final ChoiceSetting<CapeSettings.Movement> capeMovement;
	public final NumberSetting capeGravity;
	public final NumberSetting capeHeight;
	public final NumberSetting capeStiffness;
	public final ChoiceSetting<CapeSettings.Detail> capeDetail;
	public final ChoiceSetting<CapeScope> capeScope;

	// --- Farben ---
	public final NumberSetting colorSaturation;
	public final NumberSetting colorContrast;
	public final NumberSetting colorBrightness;
	public final NumberSetting colorVibrance;
	public final NumberSetting colorTemperature;
	public final ChoiceSetting<EmoteCamera> emoteCamera;
	public final BoolSetting emoteOthers;

	/** Kamera während des eigenen Emotes (nur wenn sie in der Ich-Perspektive ist). */
	public enum EmoteCamera implements ChoiceSetting.Option {
		OFF("Unchanged"),
		BACK("Third person (behind)"),
		FRONT("Third person (front)");

		private final String label;

		EmoteCamera(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	// --- Redstone ---
	public final BoolSetting redstoneSignalName;
	public final BoolSetting redstoneSignalBar;
	public final BoolSetting redstoneSignalDetails;
	public final NumberSetting redstoneOverlayRadius;
	public final BoolSetting redstoneOverlayVisibleOnly;
	public final BoolSetting redstoneOverlayZero;
	public final NumberSetting redstoneClockWindow;
	public final BoolSetting redstoneClockScope;
	public final BoolSetting redstoneClockKeep;
	public final BoolSetting clipsBufferIcon;

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
				"Press the sprint key once to keep sprinting, press it again to stop. "
						+ "The HUD shows the state, e.g. [Sprinting (Toggled)]. Optional fly boost in creative mode.", false,
				new HudPosition(HudAnchor.BOTTOM_RIGHT, -0.005, -0.42)));
		toggleSneak = registry.register(new HudModule("toggleSneak", "Toggle Sneak",
				"Press the sneak key once to keep sneaking, press it again to stop. "
						+ "The HUD shows the state, e.g. [Sneaking (Toggled)].", false,
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
				"Hold the key to orbit the camera around your character; you keep walking and looking straight ahead "
						+ "and the server only ever sees your real view direction. Some servers forbid freelook – "
						+ "respect the server rules. On servers in the list it switches itself off.", false));
		zoom = registry.register(new Module("zoom", "Zoom",
				"Hold the key to zoom in smoothly. The mouse wheel changes the zoom while held and the mouse "
						+ "slows down with it. Pauses while you look through a spyglass.", true));
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
		colors = registry.register(new Module("colors", "Colors",
				"Color grading of the game image: saturation, contrast, brightness, vibrance and color temperature. "
						+ "The HUD and menus stay untouched.", false));
		emotes = registry.register(new Module("emotes", "Emotes",
				"Hold G for the emote wheel, point with the mouse and release to play the emote. Other TRS players "
						+ "see it too. Uses the TRS Online Features; only unlocked emotes can be played.",
				true));
		redstoneSignal = registry.register(new HudModule("redstoneSignal", "Signal Strength",
				"Look at dust, repeaters, comparators, pistons, lamps, levers and more: shows the block, its signal "
						+ "strength (0–15), repeater delay, comparator mode and output, piston state and the comparator "
						+ "output of containers you have opened.", true,
				new HudPosition(HudAnchor.CENTER, 0.2, -0.1)));
		redstoneOverlay = registry.register(new Module("redstoneOverlay", "Signal Overlay",
				"Shows the signal strength as a number above every piece of redstone dust around you, from grey (0) "
						+ "to bright red (15). Switch it on and off with its key (controls menu).", false));
		redstoneClock = registry.register(new HudModule("redstoneClock", "Clock Meter",
				"Measures how fast the redstone component you look at switches: frequency in Hz, period and pulse "
						+ "length in redstone ticks, plus a small oscilloscope.", true,
				new HudPosition(HudAnchor.CENTER, 0.2, 0.16)));
		clips = registry.register(new HudModule("clips", "Clips & Recording",
				"Save the last seconds as a clip (F9) or start and stop a recording (F10) – the TRS Launcher records "
						+ "the game window, nothing leaves your PC. Turn it on in the launcher under Settings → Clips. "
						+ "Shows a red dot while recording and a message when a clip is saved.", true,
				new HudPosition(HudAnchor.CENTER_RIGHT, -0.005, -0.2)));

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
		capePhysics.icon("cape").profiled();
		colors.icon("palette").category(Category.WORLD).profiled();
		emotes.icon("wave");
		fpsBoost = registry.register(new Module("fpsBoost", "FPS Boost",
				"Main switch of all performance features (off = compare without them). One click sets everything to "
						+ "Low, Medium or High, the performance check finds FPS killers in your video settings, and you "
						+ "see the FPS before and after. Every change can be undone.", true));
		dynamicFps = registry.register(new Module("dynamicFps", "Dynamic FPS",
				"Limits the frame rate while the game is in the background, minimized or you are AFK, and makes it "
						+ "quieter. Full FPS the moment you come back.", true));
		entityCulling = registry.register(new Module("entityCulling", "Entity Culling",
				"Skips drawing mobs, chests, signs, dropped items and item frames that are hidden behind walls or "
						+ "further away than you set. Display only – nothing changes in the world.", false));
		particles = registry.register(new Module("particles", "Particles",
				"Fewer particles: an upper limit, a share of all particles and single kinds switched off "
						+ "(explosions, rain splashes, smoke).", false));
		worldDetails = registry.register(new Module("worldDetails", "World Details",
				"Switch off details that cost frames: sky, stars, fog, rain and snow, animated textures "
						+ "(water, lava, fire). Only what works cleanly in this version.", false));

		redstoneSignal.icon("strength").category(Category.REDSTONE);
		redstoneOverlay.icon("digits").category(Category.REDSTONE);
		redstoneClock.icon("wave").category(Category.REDSTONE);
		fpsBoost.icon("bolt").category(Category.PERFORMANCE);
		dynamicFps.icon("moon").category(Category.PERFORMANCE);
		entityCulling.icon("cull").category(Category.PERFORMANCE);
		particles.icon("sparkle").category(Category.PERFORMANCE);
		worldDetails.icon("cloud").category(Category.PERFORMANCE);
		for (Module m : new Module[]{fpsBoost, dynamicFps, entityCulling, particles, worldDetails}) m.profiled();
		clips.icon("record").category(Category.MISC);

		keystrokesShowCps = keystrokes.add(new BoolSetting("showCps", "CPS below mouse buttons", true));
		keystrokesShowSpace = keystrokes.add(new BoolSetting("showSpace", "Show space bar", true));
		// Zoom- und Freelook-Taste sind Vanilla-Tastenbelegungen (Steuerung → TRS Client); der
		// Loader verbindet sie beim Start (KeySetting.link), dann sind sie auch hier änderbar.
		zoomKey = zoom.add(new KeySetting("key", "Key", "key.keyboard.v"));
		zoomFactor = zoom.add(new NumberSetting("factor", "Zoom factor", 4.0, 2.0, 10.0, 0.5, "×"));
		zoomSmooth = zoom.add(new BoolSetting("smooth", "Smooth transition", true));
		zoomScroll = zoom.add(new BoolSetting("scroll", "Mouse wheel changes zoom", true));
		zoomSlowMouse = zoom.add(new BoolSetting("slowMouse", "Slow down mouse", true));
		zoomCinematic = zoom.add(new BoolSetting("cinematic", "Cinematic camera", false));

		freelookKey = freelook.add(new KeySetting("key", "Key", "key.keyboard.left.alt"));
		freelookToggle = freelook.add(new BoolSetting("toggle", "Toggle instead of hold", false));
		freelookPerspective = freelook.add(new ChoiceSetting<>("perspective", "Perspective",
				FreelookState.Perspective.class, FreelookState.Perspective.BACK));
		freelookServers = freelook.add(new TextSetting("servers", "Off on these servers", "", 300,
				"e.g. example.net;other.org"));

		toggleSprintOnlyForward = toggleSprint.add(new BoolSetting("onlyForward", "Sprint only forward", true));
		toggleSprintRemember = toggleSprint.add(new BoolSetting("remember", "Remember state (death, world change)", true));
		toggleSprintFlyBoost = toggleSprint.add(new BoolSetting("flyBoost", "Fly boost (creative mode)", false));
		toggleSprintFlyBoostFactor = toggleSprint.add(new NumberSetting("flyBoostFactor", "Fly boost", 2.0, 1.5, 5.0, 0.5, "×"));
		toggleSneakRemember = toggleSneak.add(new BoolSetting("remember", "Remember state (death, world change)", false));

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
		capeStyle = capePhysics.add(new ChoiceSetting<>("style", "Style", CapeSettings.Style.class, CapeSettings.Style.SMOOTH));
		capeWindMode = capePhysics.add(new ChoiceSetting<>("windMode", "Wind", CapeSettings.Wind.class, CapeSettings.Wind.WAVES));
		capeWind = capePhysics.add(new NumberSetting("wind", "Wind strength", 100, 0, 200, 10, "", "%"));
		capeMovement = capePhysics.add(new ChoiceSetting<>("movement", "Movement", CapeSettings.Movement.class,
				CapeSettings.Movement.SWINGING));
		capeGravity = capePhysics.add(new NumberSetting("gravity", "Gravity", 100, 25, 200, 5, "", "%"));
		capeHeight = capePhysics.add(new NumberSetting("height", "Lift when moving", 100, 0, 200, 10, "", "%"));
		capeStiffness = capePhysics.add(new NumberSetting("stiffness", "Stiffness", 100, 0, 200, 10, "", "%"));
		capeDetail = capePhysics.add(new ChoiceSetting<>("detail", "Detail", CapeSettings.Detail.class, CapeSettings.Detail.HIGH));
		capeScope = capePhysics.add(new ChoiceSetting<>("scope", "For", CapeScope.class, CapeScope.ALL));
		colorSaturation = colors.add(new NumberSetting("saturation", "Saturation", 100, 0, 200, 5, "", "%"));
		colorContrast = colors.add(new NumberSetting("contrast", "Contrast", 100, 50, 150, 5, "", "%"));
		colorBrightness = colors.add(new NumberSetting("brightness", "Brightness", 100, 50, 150, 5, "", "%"));
		colorVibrance = colors.add(new NumberSetting("vibrance", "Vibrance", 0, -100, 100, 5, "", "%"));
		colorTemperature = colors.add(new NumberSetting("temperature", "Color temperature", 0, -100, 100, 5, "", "%"));
		emoteCamera = emotes.add(new ChoiceSetting<>("camera", "Camera during your emote", EmoteCamera.class,
				EmoteCamera.FRONT));
		emoteOthers = emotes.add(new BoolSetting("others", "Show emotes of other players", true));
		redstoneSignalName = redstoneSignal.add(new BoolSetting("name", "Block name", true));
		redstoneSignalBar = redstoneSignal.add(new BoolSetting("bar", "Signal bar", true));
		redstoneSignalDetails = redstoneSignal.add(new BoolSetting("details", "Details (delay, mode, state)", true));
		redstoneOverlayRadius = redstoneOverlay.add(new NumberSetting("radius", "Radius (blocks)", 8, 4, 16, 1, ""));
		redstoneOverlayVisibleOnly = redstoneOverlay.add(new BoolSetting("visibleOnly", "Only dust in sight", true));
		redstoneOverlayZero = redstoneOverlay.add(new BoolSetting("zero", "Also label 0", true));
		redstoneClockWindow = redstoneClock.add(new NumberSetting("window", "Measuring window (s)", 5, 2, 10, 1, ""));
		redstoneClockScope = redstoneClock.add(new BoolSetting("scope", "Oscilloscope", true));
		redstoneClockKeep = redstoneClock.add(new BoolSetting("keep", "Keep measuring after looking away", true));
		clipsBufferIcon = clips.add(new BoolSetting("bufferIcon", "Show buffer indicator", true));

		dynamicFpsUnfocused = dynamicFps.add(new NumberSetting("unfocused", "FPS in the background", 15, 1, 60, 1, "", " FPS"));
		dynamicFpsMinimized = dynamicFps.add(new NumberSetting("minimized", "FPS when minimized", 1, 1, 30, 1, "", " FPS"));
		dynamicFpsAfk = dynamicFps.add(new NumberSetting("afk", "FPS when AFK (0 = off)", 30, 0, 60, 5, "", " FPS"));
		dynamicFpsAfkMinutes = dynamicFps.add(new NumberSetting("afkMinutes", "AFK after (minutes)", 3, 1, 15, 1, ""));
		dynamicFpsQuieter = dynamicFps.add(new BoolSetting("quieter", "Quieter in the background", true));
		dynamicFpsVolume = dynamicFps.add(new NumberSetting("volume", "Background volume", 30, 0, 100, 10, "", "%"));
		cullOcclusion = entityCulling.add(new BoolSetting("occlusion", "Hide entities behind walls", true));
		cullEntities = entityCulling.add(new NumberSetting("entities", "Mobs up to (blocks, 0 = all)", 64, 0, 160, 8, ""));
		cullKeepPlayers = entityCulling.add(new BoolSetting("players", "Always show players at any distance", true));
		cullBlockEntities = entityCulling.add(new NumberSetting("blockEntities", "Chests & signs up to (blocks)", 48, 0, 128, 8, ""));
		cullNameTags = entityCulling.add(new NumberSetting("nameTags", "Name tags up to (blocks)", 32, 0, 64, 4, ""));
		cullItems = entityCulling.add(new NumberSetting("items", "Dropped items up to (blocks)", 32, 0, 128, 8, ""));
		cullFrames = entityCulling.add(new NumberSetting("frames", "Item frames up to (blocks)", 32, 0, 128, 8, ""));
		particleLimit = particles.add(new NumberSetting("limit", "Max. particles (0 = no limit)", 2000, 0, 8000, 250, ""));
		particleAmount = particles.add(new NumberSetting("amount", "Amount", 100, 10, 100, 10, "", "%"));
		particleNoExplosions = particles.add(new BoolSetting("explosions", "No explosion particles", false));
		particleNoRain = particles.add(new BoolSetting("rain", "No rain splashes", true));
		particleNoSmoke = particles.add(new BoolSetting("smoke", "No smoke", false));
		detailNoSky = worldDetails.add(new BoolSetting("sky", "Hide the sky", false));
		detailNoStars = worldDetails.add(new BoolSetting("stars", "Hide the stars", true));
		detailNoFog = worldDetails.add(new BoolSetting("fog", "No distance fog", false));
		detailNoWeather = worldDetails.add(new BoolSetting("weather", "No rain and snow", false));
		detailNoAnimations = worldDetails.add(new BoolSetting("animations", "No texture animations (water, lava, fire)", false));

		registry.addPart(keyDefaults);
		registry.addPart(perfUndo);
		// Profile zuletzt: sie sichern den Zustand aller HUD-Module.
		profiles = new HudProfiles(registry);
	}

	/** Aktuelle Umhang-Einstellungen in {@code out} (je Tick, keine Allokation). */
	public CapeSettings capeSettings(CapeSettings out) {
		out.style = capeStyle.get();
		out.wind = capeWindMode.get();
		out.windStrength = (float) (capeWind.get() / 100.0);
		out.movement = capeMovement.get();
		out.gravity = (float) (capeGravity.get() / 100.0);
		out.height = (float) (capeHeight.get() / 100.0);
		out.stiffness = (float) (capeStiffness.get() / 100.0);
		out.detail = capeDetail.get();
		return out;
	}

	/** Aktuelle Farb-Einstellungen in {@code out}. */
	public ColorGrade colorGrade(ColorGrade out) {
		return out.set(colorSaturation.get(), colorContrast.get(), colorBrightness.get(), colorVibrance.get(),
				colorTemperature.get());
	}
}
