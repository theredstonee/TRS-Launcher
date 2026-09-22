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
	public final Module crosshair;
	public final Module hitColor;
	public final Module freelook;
	public final Module zoom;
	public final Module fullbright;
	public final Module titleScreen;

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
	public final BoolSetting titleServers;

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
		titleServers = titleScreen.add(new BoolSetting("servers", "Server-Schnellbeitritt", true));

		registry.addPart(keyDefaults);
		// Profile zuletzt: sie sichern den Zustand aller HUD-Module.
		profiles = new HudProfiles(registry);
	}
}
