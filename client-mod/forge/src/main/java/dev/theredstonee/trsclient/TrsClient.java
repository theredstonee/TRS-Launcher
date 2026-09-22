package dev.theredstonee.trsclient;

import com.mojang.blaze3d.platform.InputConstants;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.compat.Platform;
import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.input.ClickCounter;
import dev.theredstonee.trsclient.core.input.KeyPresses;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.zoom.ZoomState;
import dev.theredstonee.trsclient.dev.AutoTest;
import dev.theredstonee.trsclient.feature.ChatFeatures;
import dev.theredstonee.trsclient.feature.PvpFeatures;
import dev.theredstonee.trsclient.feature.Waypoints;
import dev.theredstonee.trsclient.hud.HudManager;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.WaypointEditScreen;
import dev.theredstonee.trsclient.screen.WaypointListScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
//? if >=1.21.11 {
/*import net.minecraft.resources.Identifier;
*///?} elif >=1.21.6 {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/**
 * TRS Client für Forge (nur Client). Einstieg: {@link TrsClientMod}.
 * Fast alle Hooks laufen über Mixins (wie im Fabric-Baum), damit der Code über alle Forge-Versionen
 * (EventBus 6 bis 1.21.5, EventBus 7 ab 1.21.6) gleich bleibt: Client-Tick (MinecraftMixin), HUD (GuiMixin bzw.
 * Forge-GUI-Event bis 1.20.4), Zoom (FovMixin), Maus (MouseHandlerMixin), Fullbright (LightmapMixin) usw.
 */
public final class TrsClient {
	public static final String MOD_ID = "trsclient";
	public static final Logger LOGGER = LoggerFactory.getLogger("TRS Client");

	private static TrsClient instance;

	private final TrsModules modules = new TrsModules();
	private final ClickCounter leftClicks = new ClickCounter();
	private final ClickCounter rightClicks = new ClickCounter();
	private final ZoomState zoom = new ZoomState();
	private final ConfigStore config;
	private final HudManager hud;
	private final PvpFeatures pvp = new PvpFeatures(modules);
	private final ChatFeatures chat = new ChatFeatures(modules);
	private final Waypoints waypoints;
	/** Tastendruck-Erkennung für die Modul-Tasten (Wegpunkte, Text-Hotkeys). */
	private final KeyPresses moduleKeys = new KeyPresses(dev.theredstonee.trsclient.compat.Keys::isDown);
	/** Zuletzt benutztes Welt-Sichtfeld (für die Wegpunkt-Projektion), aus dem FOV-Mixin. */
	private double worldFov = 70;
	/** Einmalig den Vanilla-Titelbildschirm zulassen ("Klassisch" auf dem TRS-Startbildschirm). */
	private boolean vanillaTitleOnce;
	/** Nur für den Autotest: Zoom ohne Tastendruck erzwingen. */
	private boolean forceZoom;
	private AutoTest autoTest;

	public static TrsClient get() {
		return instance;
	}

	/** Aus dem Mod-Konstruktor (nur auf dem Client). */
	static void init() {
		instance = new TrsClient();
	}

	private TrsClient() {
		instance = this;
		// Farben des Launchers (config/trsclient/launcher-theme.json) – fehlt sie, gilt das Standard-Thema.
		dev.theredstonee.trsclient.core.ui.Theme.loadFrom(Platform.configDir());
		config = new ConfigStore(Platform.configDir().resolve("trsclient.json"));
		ConfigStore.Status status = config.load(modules.registry);
		if (status == ConfigStore.Status.RECOVERED) {
			LOGGER.warn("Config war beschädigt – Standardwerte geladen, Sicherung: {}", config.brokenFile());
		}
		TrsKeys.create();
		waypoints = new Waypoints(modules, Platform.configDir().resolve("trsclient-waypoints.json"));
		hud = new HudManager(modules);
		autoTest = AutoTest.createIfRequested();
		// Beim Beenden speichern (Forge-unabhängig; Änderungen im Menü werden ohnehin sofort gespeichert).
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			saveConfig();
			waypoints.save();
		}, "TRS Client config save"));

		LOGGER.info("TRS Client {} initialisiert (Forge, Minecraft {}) – {} Module, Config {} ({})",
				Platform.modVersion(MOD_ID), Platform.modVersion("minecraft"), modules.registry.all().size(),
				status, config.file());
	}

	/**
	 * Aus dem Mixin in setScreen: ersetzt den Vanilla-Titelbildschirm durch den TRS-Startbildschirm,
	 * solange das Modul "Startbildschirm" an ist.
	 */
	public Screen replaceScreen(Screen screen) {
		if (screen == null || screen.getClass() != TitleScreen.class) return screen;
		if (vanillaTitleOnce) {
			vanillaTitleOnce = false;
			return screen;
		}
		return modules.titleScreen.isEnabled() ? new TrsTitleScreen() : screen;
	}

	/** Öffnet einmalig den Vanilla-Titelbildschirm. */
	public void openVanillaTitle() {
		vanillaTitleOnce = true;
		Mc.setScreen(new TitleScreen());
	}

	//? if >=1.21.11 {
	/*public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
	*///?} elif >=1.21.6 {
	/*public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
	*///?}

	// --- Client-Tick (MinecraftMixin) ---

	/** Anfang des Client-Ticks: vor der Spieler-Bewegung (Toggle-Tasten, Freelook, Treffer-Farbe). */
	public void onStartTick(Minecraft mc) {
		pvp.tick(mc);
	}

	/** Ende des Client-Ticks. */
	public void onEndTick(Minecraft mc) {
		if (TrsKeys.menu == null) return;
		migrateKeys(mc);
		while (TrsKeys.hudProfile.consumeClick()) {
			String name = modules.profiles.cycle();
			Mc.actionBar(Component.literal("HUD-Profil: " + name));
			saveConfig();
		}
		while (TrsKeys.menu.consumeClick()) {
			if (Mc.screen() == null) Mc.setScreen(new TrsMenuScreen(null));
		}
		while (TrsKeys.fullbright.consumeClick()) {
			modules.fullbright.toggle();
			Mc.actionBar(Component.literal("Fullbright: " + (modules.fullbright.isEnabled() ? "An" : "Aus")));
			saveConfig();
		}
		// Wegpunkt- und Hotkey-Tasten gehören den Modulen (Tastenbelegung im TRS-Menü).
		if (Mc.screen() == null) {
			if (moduleKeys.pressed(modules.waypointAddKey) && mc.player != null && modules.waypoints.isEnabled()) {
				Mc.setScreen(new WaypointEditScreen(null, null));
			}
			if (moduleKeys.pressed(modules.waypointListKey) && modules.waypoints.isEnabled()) {
				Mc.setScreen(new WaypointListScreen(null));
			}
			for (int i = 0; i < modules.hotkeyKeys.length; i++) {
				if (moduleKeys.pressed(modules.hotkeyKeys[i])) chat.onHotkey(i);
			}
		} else {
			moduleKeys.releaseAll();
		}
		waypoints.tick(mc);
		chat.tick(mc);
		hud.tick();
		if (autoTest != null) autoTest.tick(mc);
	}

	/**
	 * Einmalige Umstellung alter Standard-Tasten: Zoom lag auf C, was ab Minecraft 1.12 mit
	 * "Hotbar speichern" kollidiert. Selbst belegte Tasten bleiben unangetastet.
	 */
	private void migrateKeys(Minecraft mc) {
		if (!modules.keyDefaults.needsZoomKeyMigration() || mc.options == null) return;
		if (TrsKeys.migrateZoomKey()) {
			mc.options.save();
			LOGGER.info("Zoom-Taste von C auf V umgestellt (C ist ab 1.12 'Hotbar speichern')");
		}
		modules.keyDefaults.markMigrated();
		saveConfig();
	}

	/** Speichert die Einstellungen (Fehler nur loggen). */
	public void saveConfig() {
		try {
			config.save(modules.registry);
		} catch (IOException e) {
			LOGGER.error("Config konnte nicht gespeichert werden: {}", config.file(), e);
		}
	}

	// --- Hooks aus den Mixins ---

	/** Mausklick im Spiel (nicht in Menüs) → CPS-Zähler. */
	public void onMouseClick(int button) {
		if (Mc.screen() != null) return;
		long now = System.currentTimeMillis();
		if (button == InputConstants.MOUSE_BUTTON_LEFT) leftClicks.record(now);
		else if (button == InputConstants.MOUSE_BUTTON_RIGHT) rightClicks.record(now);
	}

	/** Pro Frame aus getFov: aktualisiert den Zoom und liefert den FOV-Divisor. */
	public double updateZoom() {
		boolean active = modules.zoom.isEnabled()
				&& (forceZoom || (TrsKeys.zoom != null && TrsKeys.zoom.isDown() && Mc.screen() == null));
		zoom.update(active, modules.zoomFactor.get(), modules.zoomSmooth.get(), System.nanoTime());
		return zoom.factor();
	}

	/** Mausrad während des Zooms; true = Ereignis verbraucht (Hotbar bleibt). */
	public boolean onScroll(double amount) {
		if (!zoom.isActive() || !modules.zoomScroll.get() || Mc.screen() != null) return false;
		zoom.scroll(amount);
		return true;
	}

	/** Maus-Divisor während des Zooms (1 = unverändert). */
	public double mouseDivisor() {
		return modules.zoomSlowMouse.get() ? zoom.factor() : 1.0;
	}

	public boolean fullbright() {
		return modules.fullbright.isEnabled();
	}

	// --- Zugriff ---

	public TrsModules modules() {
		return modules;
	}

	public HudManager hud() {
		return hud;
	}

	public ClickCounter leftClicks() {
		return leftClicks;
	}

	public ClickCounter rightClicks() {
		return rightClicks;
	}

	public PvpFeatures pvp() {
		return pvp;
	}

	public ChatFeatures chat() {
		return chat;
	}

	public Waypoints waypoints() {
		return waypoints;
	}

	/** Zuletzt gezeichnetes Sichtfeld der Welt (Grad) – Grundlage der Wegpunkt-Projektion. */
	public double worldFov() {
		return worldFov;
	}

	/** Aus dem FOV-Mixin: das tatsächlich benutzte Sichtfeld merken. */
	public void setWorldFov(double fov) {
		if (fov > 1 && fov < 180) worldFov = fov;
	}

	public ToggleState sprintToggle() {
		return pvp.sprint();
	}

	public ToggleState sneakToggle() {
		return pvp.sneak();
	}

	public void setForceZoom(boolean forceZoom) {
		this.forceZoom = forceZoom;
	}
}
