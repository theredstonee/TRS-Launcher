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
		OWN("Nur eigener"),
		ALL("Alle Spieler");

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
		FAR("Weit", 1, 2),
		NORMAL("Normal", 2, 1),
		NEAR("Nah", 3, 1),
		CLOSE("Sehr nah", 4, 1);

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
		fps = registry.register(new HudModule("fps", "FPS", "Bilder pro Sekunde", true,
				new HudPosition(HudAnchor.TOP_LEFT, 0.005, 0.01)));
		cps = registry.register(new HudModule("cps", "CPS", "Klicks pro Sekunde (links | rechts)", true,
				new HudPosition(HudAnchor.TOP_LEFT, 0.005, 0.075)));
		keystrokes = registry.register(new HudModule("keystrokes", "Tastenanzeige", "WASD, Maustasten und Leertaste", true,
				new HudPosition(HudAnchor.BOTTOM_RIGHT, -0.005, -0.08)));
		ping = registry.register(new HudModule("ping", "Ping", "Latenz zum Server (nicht im Einzelspieler)", true,
				new HudPosition(HudAnchor.TOP_LEFT, 0.005, 0.14)));
		armor = registry.register(new HudModule("armor", "Rüstung", "Getragene Rüstung mit Haltbarkeit", false,
				new HudPosition(HudAnchor.CENTER_LEFT, 0.005, 0.0)));
		effects = registry.register(new HudModule("effects", "Trank-Effekte", "Aktive Effekte mit Restzeit", false,
				new HudPosition(HudAnchor.CENTER_RIGHT, -0.005, 0.0)));
		coords = registry.register(new HudModule("coords", "Koordinaten", "Position, Blickrichtung und Biom", false,
				new HudPosition(HudAnchor.TOP_LEFT, 0.005, 0.205)));
		clock = registry.register(new HudModule("clock", "Uhrzeit", "Echte Uhrzeit des Computers", false,
				new HudPosition(HudAnchor.TOP_RIGHT, -0.005, 0.13)));
		memory = registry.register(new HudModule("memory", "Speicher", "Belegter Arbeitsspeicher (RAM) des Spiels", false,
				new HudPosition(HudAnchor.TOP_RIGHT, -0.005, 0.195)));
		server = registry.register(new HudModule("server", "Server-Adresse",
				"Adresse des aktuellen Servers (nicht im Einzelspieler)", false,
				new HudPosition(HudAnchor.TOP_RIGHT, -0.005, 0.26)));
		packs = registry.register(new HudModule("packs", "Aktive Resourcepacks", "Liste der eingeschalteten Resourcepacks", false,
				new HudPosition(HudAnchor.BOTTOM_LEFT, 0.005, -0.3)));
		toggleSprint = registry.register(new HudModule("toggleSprint", "Toggle-Sprint",
				"Sprint-Taste einmal drücken = Dauersprint, erneut drücken = aus. Anzeige im HUD, solange aktiv.", false,
				new HudPosition(HudAnchor.BOTTOM_RIGHT, -0.005, -0.42)));
		toggleSneak = registry.register(new HudModule("toggleSneak", "Toggle-Schleichen",
				"Schleich-Taste einmal drücken = Dauerschleichen, erneut drücken = aus. Anzeige im HUD, solange aktiv.", false,
				new HudPosition(HudAnchor.BOTTOM_RIGHT, -0.005, -0.49)));
		reach = registry.register(new HudModule("reach", "Reichweite",
				"Entfernung des letzten Treffers (reine Anzeige – die Reichweite selbst bleibt unverändert)", false,
				new HudPosition(HudAnchor.CENTER_LEFT, 0.005, -0.12)));
		combo = registry.register(new HudModule("combo", "Combo",
				"Zählt Treffer in Folge; endet, wenn du selbst getroffen wirst oder eine Pause kommt", false,
				new HudPosition(HudAnchor.CENTER_LEFT, 0.005, -0.06)));
		// Geschwindigkeit, Reichweite und Combo stehen übereinander links neben dem Fadenkreuz,
		// oberhalb der Rüstung (die auf 0.0 sitzt).
		speed = registry.register(new HudModule("speed", "Geschwindigkeit", "Blöcke pro Sekunde", false,
				new HudPosition(HudAnchor.CENTER_LEFT, 0.005, -0.18)));
		minimap = registry.register(new HudModule("minimap", "Minimap",
				"Kleine Karte der Umgebung (Blockfarben von oben) mit Wegpunkten. "
						+ "Zeigt nur geladene Chunks – kein Röntgenblick, keine Höhlenansicht.", false,
				new HudPosition(HudAnchor.TOP_RIGHT, -0.005, 0.01)));
		crosshair = registry.register(new Module("crosshair", "Fadenkreuz",
				"Eigenes Fadenkreuz: Form, Farbe, Größe und Abstand frei einstellbar", false));
		hitColor = registry.register(new Module("hitColor", "Treffer-Farbe",
				"Farbe, in der getroffene Kreaturen und Spieler kurz aufleuchten", false));
		freelook = registry.register(new Module("freelook", "Freelook",
				"Taste halten: Kamera frei um die Spielfigur drehen, ohne die Laufrichtung zu ändern. "
						+ "Manche Server verbieten Freelook – nur nutzen, wo es erlaubt ist.", false));
		zoom = registry.register(new Module("zoom", "Zoom", "Taste halten zum Heranzoomen", true));
		fullbright = registry.register(new Module("fullbright", "Fullbright", "Maximale Helligkeit überall", false));
		titleScreen = registry.register(new Module("titleScreen", "Startbildschirm",
				"TRS-Startbildschirm statt des Vanilla-Titelbildschirms", true));
		oldAnimations = registry.register(new Module("oldAnimations", "1.7-Animationen",
				"Alte Handbewegungen: kein Absenken der Hand durch die Angriffs-Abklingzeit, "
						+ "Schlaganimation auch beim Benutzen eines Gegenstands (nur Optik)", false));
		lowFire = registry.register(new Module("lowFire", "Niedriges Feuer",
				"Zieht die Flammen am Bildschirmrand nach unten, wenn du brennst", false));
		blockOutline = registry.register(new Module("blockOutline", "Block-Umrandung",
				"Farbe und Stärke des Rahmens um den anvisierten Block", false));
		hitboxes = registry.register(new Module("hitboxes", "Hitboxen",
				"Zeigt die Trefferboxen der Kreaturen wie F3+B (nur Anzeige – die Boxen selbst ändern sich nicht)", false));
		noHurtCam = registry.register(new Module("noHurtCam", "Kein Schadens-Wackeln",
				"Die Kamera kippt nicht mehr, wenn du Schaden nimmst", false));
		chat = registry.register(new Module("chat", "Chat-Verbesserungen",
				"Zeitstempel, Zusammenfassen gleicher Nachrichten und Kopieren per Strg+Klick", true));
		autoGg = registry.register(new Module("autoGg", "Auto-GG",
				"Schickt nach dem Spielende automatisch einen Text. Standardmäßig aus; höchstens einmal pro Minute. "
						+ "Manche Server mögen das nicht – nur nutzen, wo es erlaubt ist.", false));
		textHotkeys = registry.register(new Module("textHotkeys", "Text-Hotkeys",
				"Vier frei belegbare Tasten senden je einen festen Text oder Befehl. "
						+ "Standardmäßig aus, höchstens eine Nachricht pro Sekunde.", false));
		waypoints = registry.register(new Module("waypoints", "Wegpunkte",
				"Eigene Markierungen je Welt/Server: Taste drücken, Name und Farbe wählen; "
						+ "im Spiel mit Entfernung und Lichtsäule, dazu ein Todespunkt.", true));
		trsOnline = registry.register(new Module("trsOnline", "TRS-Online-Funktionen",
				"TRS-Abzeichen und TRS-Umhänge anderer Spieler, dein eigener Umhang und dein Online-Status für Freunde. "
						+ "Meldet sich wie bei einem Server über dein Minecraft-Konto an. Zeigt nur, dass jemand TRS nutzt.",
				true));
		capePhysics = registry.register(new Module("capePhysics", "Umhang-Physik",
				"Umhänge (Mojang, OptiFine, TRS) bewegen sich wie Stoff: schwingen beim Laufen, Drehen, Springen und Schleichen.",
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

		keystrokesShowCps = keystrokes.add(new BoolSetting("showCps", "CPS unter Maustasten", true));
		keystrokesShowSpace = keystrokes.add(new BoolSetting("showSpace", "Leertaste anzeigen", true));
		zoomFactor = zoom.add(new NumberSetting("factor", "Zoom-Faktor", 4.0, 2.0, 10.0, 0.5, "×"));
		zoomSmooth = zoom.add(new BoolSetting("smooth", "Weicher Übergang", true));
		zoomScroll = zoom.add(new BoolSetting("scroll", "Mausrad ändert Zoom", true));
		zoomSlowMouse = zoom.add(new BoolSetting("slowMouse", "Maus verlangsamen", true));

		armorDurability = armor.add(new BoolSetting("durability", "Haltbarkeit anzeigen", true));
		armorPercent = armor.add(new BoolSetting("percent", "Haltbarkeit in Prozent", false));
		armorHand = armor.add(new BoolSetting("hand", "Gegenstand in der Hand", true));
		coordsDirection = coords.add(new BoolSetting("direction", "Blickrichtung", true));
		coordsBiome = coords.add(new BoolSetting("biome", "Biom", true));
		clockSeconds = clock.add(new BoolSetting("seconds", "Sekunden", false));
		clockTwelveHour = clock.add(new BoolSetting("twelveHour", "12-Stunden-Format", false));
		crosshairShape = crosshair.add(new ChoiceSetting<>("shape", "Form", Crosshair.Shape.class, Crosshair.Shape.CROSS));
		crosshairColor = crosshair.add(new ColorSetting("color", "Farbe", 0xFFFFFF));
		crosshairSize = crosshair.add(new NumberSetting("size", "Größe", 4, 1, 12, 1, ""));
		crosshairGap = crosshair.add(new NumberSetting("gap", "Abstand", 2, 0, 8, 1, ""));
		crosshairThickness = crosshair.add(new NumberSetting("thickness", "Stärke", 1, 1, 4, 1, ""));
		crosshairOutline = crosshair.add(new BoolSetting("outline", "Dunkler Rand", true));
		crosshairAttack = crosshair.add(new BoolSetting("attack", "Angriffs-Abklingzeit", true));
		hitColorColor = hitColor.add(new ColorSetting("color", "Farbe", 0xB07CFF));
		hitColorOpacity = hitColor.add(new NumberSetting("opacity", "Deckkraft (%)", 70, 10, 100, 10, ""));
		titleAnimated = titleScreen.add(new BoolSetting("animated", "Animierter Hintergrund", true));

		reachDecimals = reach.add(new NumberSetting("decimals", "Nachkommastellen", 2, 0, 3, 1, ""));
		reachHold = reach.add(new NumberSetting("hold", "Anzeigedauer (s, 0 = immer)", 4, 0, 10, 1, ""));
		comboBest = combo.add(new BoolSetting("best", "Beste Combo mitzeigen", false));
		comboTimeout = combo.add(new NumberSetting("timeout", "Combo endet nach (s)", 3, 1, 10, 1, ""));
		speedVertical = speed.add(new BoolSetting("vertical", "Höhenunterschied mitzählen", false));

		oldAnimationsNoDip = oldAnimations.add(new BoolSetting("noDip", "Hand bleibt oben (keine Abklingzeit-Bewegung)", true));
		oldAnimationsBlockHit = oldAnimations.add(new BoolSetting("blockHit", "Schlagen beim Benutzen zeigen", true));
		lowFireHeight = lowFire.add(new NumberSetting("height", "Absenkung", 0.4, 0.1, 0.8, 0.1, ""));
		blockOutlineColor = blockOutline.add(new ColorSetting("color", "Farbe", 0x000000));
		blockOutlineOpacity = blockOutline.add(new NumberSetting("opacity", "Deckkraft (%)", 40, 10, 100, 10, ""));
		blockOutlineWidth = blockOutline.add(new NumberSetting("width", "Stärke", 2, 1, 6, 1, ""));
		hitboxColor = hitboxes.add(new ColorSetting("color", "Farbe", 0xFFFFFF));

		chatTimestamps = chat.add(new BoolSetting("timestamps", "Zeitstempel", false));
		chatTimestampSeconds = chat.add(new BoolSetting("timestampSeconds", "Zeitstempel mit Sekunden", false));
		chatStack = chat.add(new BoolSetting("stack", "Gleiche Nachrichten zusammenfassen (x2, x3)", true));
		chatCopy = chat.add(new BoolSetting("copy", "Strg+Klick kopiert eine Zeile", true));
		autoGgText = autoGg.add(new TextSetting("text", "Nachricht", "gg", 100, "gg"));
		autoGgDelay = autoGg.add(new NumberSetting("delay", "Verzögerung (s)", 1.0, 0.5, 5.0, 0.5, ""));
		autoGgTriggers = autoGg.add(new TextSetting("triggers", "Eigene Auslöser (; trennt)", "", 200,
				"z. B. spiel vorbei;runde beendet"));
		for (int i = 0; i < hotkeyTexts.length; i++) {
			hotkeyTexts[i] = textHotkeys.add(new TextSetting("text" + (i + 1), "Text " + (i + 1), "", 100, "leer"));
			hotkeyKeys[i] = textHotkeys.add(new KeySetting("key" + (i + 1), "Taste " + (i + 1)));
		}

		waypointAddKey = waypoints.add(new KeySetting("addKey", "Wegpunkt anlegen", "key.keyboard.b"));
		waypointListKey = waypoints.add(new KeySetting("listKey", "Wegpunkte öffnen", "key.keyboard.n"));
		waypointBeam = waypoints.add(new BoolSetting("beam", "Lichtsäule", true));
		waypointDistance = waypoints.add(new BoolSetting("distance", "Entfernung anzeigen", true));
		waypointDeath = waypoints.add(new BoolSetting("death", "Todespunkt automatisch setzen", true));
		waypointRange = waypoints.add(new NumberSetting("range", "Nur bis (Blöcke, 0 = immer)", 0, 0, 2000, 100, ""));
		minimapSize = minimap.add(new NumberSetting("size", "Größe (Pixel)", 96, 48, 160, 16, ""));
		minimapZoom = minimap.add(new ChoiceSetting<>("zoom", "Zoom", MinimapZoom.class, MinimapZoom.NORMAL));
		minimapRotate = minimap.add(new BoolSetting("rotate", "Mit Blickrichtung drehen", true));
		minimapWaypoints = minimap.add(new BoolSetting("showWaypoints", "Wegpunkte anzeigen", true));
		minimapPlayers = minimap.add(new BoolSetting("showPlayers", "Spieler anzeigen (nur in Sichtweite)", false));
		minimapCoords = minimap.add(new BoolSetting("coords", "Koordinaten unter der Karte", true));
		badgeTab = trsOnline.add(new BoolSetting("badgeTab", "Abzeichen in der Tabliste", true));
		badgeNametag = trsOnline.add(new BoolSetting("badgeNametag", "Abzeichen über Namen", true));
		trsCapes = trsOnline.add(new BoolSetting("capes", "TRS-Umhänge anzeigen", true));
		capeStrength = capePhysics.add(new NumberSetting("strength", "Stärke", 100, 20, 200, 10, "", "%"));
		capeWind = capePhysics.add(new NumberSetting("wind", "Wind", 100, 0, 200, 10, "", "%"));
		capeScope = capePhysics.add(new ChoiceSetting<>("scope", "Für", CapeScope.class, CapeScope.ALL));

		registry.addPart(keyDefaults);
		// Profile zuletzt: sie sichern den Zustand aller HUD-Module.
		profiles = new HudProfiles(registry);
	}
}
