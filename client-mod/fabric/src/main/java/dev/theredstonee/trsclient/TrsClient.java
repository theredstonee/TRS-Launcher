package dev.theredstonee.trsclient;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.input.ClickCounter;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.zoom.ZoomState;
import dev.theredstonee.trsclient.dev.AutoTest;
import dev.theredstonee.trsclient.feature.PvpFeatures;
import dev.theredstonee.trsclient.hud.HudManager;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.WaypointEditScreen;
import dev.theredstonee.trsclient.screen.WaypointListScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
// Logging: SLF4J ab 1.17, davor nur Log4j (gleiche {}-Platzhalter).
//? if >=1.17 {
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//?} else {
/*import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
*///?}

import java.io.IOException;
//? if >=1.21.6 {
/*import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
*///?} elif >=1.15
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//? if >=1.21.11 {
/*import net.minecraft.resources.Identifier;
*///?} elif >=1.21.6 {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/** Einstiegspunkt des TRS Clients (nur Client). */
public final class TrsClient implements ClientModInitializer {
	public static final String MOD_ID = "trsclient";
	//? if >=1.17 {
	public static final Logger LOGGER = LoggerFactory.getLogger("TRS Client");
	//?} else
	/*public static final Logger LOGGER = LogManager.getLogger("TRS Client");*/

	private static TrsClient instance;

	private final TrsModules modules = new TrsModules();
	private final ClickCounter leftClicks = new ClickCounter();
	private final ClickCounter rightClicks = new ClickCounter();
	private final ZoomState zoom = new ZoomState();
	/** Filmische Kamera nur während des Zooms (stellt den Wert des Spielers danach wieder her). */
	private final dev.theredstonee.trsclient.core.util.FlagOverride zoomCinematic =
			new dev.theredstonee.trsclient.core.util.FlagOverride();
	private ConfigStore config;
	private HudManager hud;
	private final PvpFeatures pvp = new PvpFeatures(modules);
	private final dev.theredstonee.trsclient.feature.ChatFeatures chat =
			new dev.theredstonee.trsclient.feature.ChatFeatures(modules);
	/** Redstone-Werkzeuge: Signalstärke, Takt-Messer, Signal-Overlay (Logik in core.redstone). */
	private final dev.theredstonee.trsclient.core.redstone.RedstoneTools redstone =
			new dev.theredstonee.trsclient.core.redstone.RedstoneTools(modules);
	private long redstoneErrorLogged;
	private dev.theredstonee.trsclient.feature.Waypoints waypoints;
	/** Tastendruck-Erkennung für die Modul-Tasten (Wegpunkte, Text-Hotkeys). */
	private final dev.theredstonee.trsclient.core.input.KeyPresses moduleKeys =
			new dev.theredstonee.trsclient.core.input.KeyPresses(dev.theredstonee.trsclient.compat.Keys::isDown);
	/** Zuletzt benutztes Welt-Sichtfeld (für die Wegpunkt-Projektion), aus dem FOV-Mixin. */
	private double worldFov = 70;
	/** Einmalig den Vanilla-Titelbildschirm zulassen ("Klassisch" auf dem TRS-Startbildschirm). */
	private boolean vanillaTitleOnce;
	/** Nur für den Autotest: Zoom ohne Tastendruck erzwingen. */
	private boolean forceZoom;

	public static TrsClient get() {
		return instance;
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		// Farben des Launchers (config/trsclient/launcher-theme.json) – fehlt sie, gilt das Standard-Thema.
		Theme.loadFrom(FabricLoader.getInstance().getConfigDir());
		dev.theredstonee.trsclient.core.i18n.I18n.init(FabricLoader.getInstance().getConfigDir());
		config = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("trsclient.json"));
		ConfigStore.Status status = config.load(modules.registry);
		if (status == ConfigStore.Status.RECOVERED) {
			LOGGER.warn("Config war beschädigt – Standardwerte geladen, Sicherung: {}", config.brokenFile());
		}

		TrsKeys.register();
		// Zoom-/Freelook-Taste sind Vanilla-Belegungen – im TRS-Menü ändern sie dieselbe Belegung.
		modules.zoomKey.link(TrsKeys.link(TrsKeys.zoom));
		modules.freelookKey.link(TrsKeys.link(TrsKeys.freelook));
		waypoints = new dev.theredstonee.trsclient.feature.Waypoints(modules,
				FabricLoader.getInstance().getConfigDir().resolve("trsclient-waypoints.json"));
		hud = new HudManager(modules);
		registerHud();
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		// Vor der Spieler-Bewegung: Toggle-Tasten, Freelook, Treffer-Farbe, PvP-Zähler.
		ClientTickEvents.START_CLIENT_TICK.register(pvp::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			saveConfig();
			waypoints.save();
		});
		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		String minecraft = FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		// TRS API (Abzeichen, TRS-Umhänge, Presence) + Umhang-Physik; nichts davon blockiert den Start.
		dev.theredstonee.trsclient.online.OnlineHooks.init(FabricLoader.getInstance().getConfigDir(), modules, version,
				minecraft, "fabric", message -> LOGGER.info(message));
		AutoTest.installIfRequested();

		LOGGER.info("TRS Client {} initialisiert – {} Module, Config {} ({})",
				version, modules.registry.all().size(), status, config.file());
	}

	/**
	 * HUD einhängen: Fabric-HUD-Ebenen ab 1.21.6 (dort ersetzt TRS auch die Fadenkreuz-Ebene),
	 * davor der klassische HUD-Callback (Vanilla-Fadenkreuz blendet dann CrosshairMixin aus).
	 */
	private void registerHud() {
		//? if >=1.21.6 {
		/*HudElementRegistry.addLast(id("hud"), (g, delta) -> hud.render(Gfx.of(g)));
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (g, delta) -> {
			if (hud.crosshair().replacesVanilla()) hud.crosshair().drawInGame(Gfx.of(g));
			//? if >=26.1 {
			else vanilla.extractRenderState(g, delta);
			//?} else
			else vanilla.render(g, delta);
		});
		*///?} elif >=1.16 {
		HudRenderCallback.EVENT.register((g, delta) -> hud.render(Gfx.of(g)));
		//?} elif >=1.15 {
		/*HudRenderCallback.EVENT.register(delta -> hud.render(Gfx.of()));
		*///?}
		// 1.14 hat keinen HUD-Callback: dort ruft HudMixin (Gui#render) renderHud() auf.
	}

	/** HUD zeichnen – aus dem HUD-Callback bzw. in 1.14 aus HudMixin. */
	public void renderHud(Gfx g) {
		hud.render(g);
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

	private void onTick(Minecraft mc) {
		migrateKeys(mc);
		while (TrsKeys.hudProfile.consumeClick()) {
			String name = modules.profiles.cycle();
			Mc.actionBar(Mc.text(I18n.tr("toast.hudProfile", name)));
			saveConfig();
		}
		while (TrsKeys.menu.consumeClick()) {
			if (Mc.screen() == null) Mc.setScreen(new TrsMenuScreen(null));
		}
		while (TrsKeys.redstoneOverlay.consumeClick()) {
			modules.redstoneOverlay.toggle();
			Mc.actionBar(Mc.text(I18n.tr("toast.redstoneOverlay", modules.redstoneOverlay.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled"))));
			saveConfig();
		}
		while (TrsKeys.fullbright.consumeClick()) {
			modules.fullbright.toggle();
			Mc.actionBar(Mc.text(I18n.tr("toast.fullbright", modules.fullbright.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled"))));
			saveConfig();
		}
		// Emote-Rad: Taste halten öffnet es, Loslassen (im Rad abgefragt) spielt das gezeigte Emote.
		while (TrsKeys.emoteWheel.consumeClick()) {
			if (Mc.screen() == null && mc.player != null && dev.theredstonee.trsclient.online.EmoteHooks.enabled()) {
				Mc.setScreen(new dev.theredstonee.trsclient.screen.EmoteWheelScreen());
			}
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
		tickRedstone();
		dev.theredstonee.trsclient.online.OnlineHooks.tick(mc);
		dev.theredstonee.trsclient.online.EmoteHooks.tick(mc);
	}

	/** Redstone-Werkzeuge; ein Fehler darf nie das Spiel stören (höchstens einmal je Minute geloggt). */
	private void tickRedstone() {
		try {
			dev.theredstonee.trsclient.compat.RedstoneProbe.tick(redstone);
		} catch (RuntimeException e) {
			redstone.reset();
			long now = System.currentTimeMillis();
			if (now - redstoneErrorLogged > 60_000) {
				redstoneErrorLogged = now;
				LOGGER.warn("Redstone-Werkzeuge: {}", e.toString());
			}
		}
	}

	/**
	 * Einmalige Umstellung alter Standard-Tasten: Zoom lag auf C, was ab Minecraft 1.12 mit
	 * "Hotbar speichern" kollidiert. Umgestellt wird nur, wenn die Taste noch auf dem alten
	 * Standard liegt – selbst belegte Tasten bleiben unangetastet.
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
		if (button == Keys.MOUSE_LEFT) {
			leftClicks.record(now);
			// Angreifen/Abbauen beendet das eigene Emote (wie Bewegung).
			dev.theredstonee.trsclient.online.EmoteHooks.onAttack();
		}
		if (button == Keys.MOUSE_RIGHT) rightClicks.record(now);
	}

	/**
	 * Pro Frame aus getFov: aktualisiert den Zoom und liefert den FOV-Divisor. Das Fernrohr (ab 1.17)
	 * hat Vorrang; die filmische Kamera gilt auf Wunsch nur, solange gezoomt wird.
	 */
	public double updateZoom() {
		boolean wanted = modules.zoom.isEnabled()
				&& (forceZoom || (TrsKeys.zoom.isDown() && Mc.screen() == null));
		double factor = zoom.frame(wanted, Mc.scoping(), modules.zoomFactor.get(), modules.zoomSmooth.get(), System.nanoTime());
		boolean smooth = zoomCinematic.update(Mc.smoothCamera(), zoom.isActive() && modules.zoomCinematic.get());
		if (smooth != Mc.smoothCamera()) Mc.setSmoothCamera(smooth);
		return factor;
	}

	/** Mausrad während des Zooms; true = Ereignis verbraucht (Hotbar bleibt). */
	public boolean onScroll(double amount) {
		if (!zoom.isActive() || !modules.zoomScroll.get() || Mc.screen() != null) return false;
		zoom.scroll(amount);
		return true;
	}

	/** Maus-Divisor während des Zooms (1 = unverändert), proportional zur Zoomstufe. */
	public double mouseDivisor() {
		return zoom.mouseDivisor(modules.zoomSlowMouse.get());
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

	public dev.theredstonee.trsclient.feature.ChatFeatures chat() {
		return chat;
	}

	public dev.theredstonee.trsclient.feature.Waypoints waypoints() {
		return waypoints;
	}

	public dev.theredstonee.trsclient.core.redstone.RedstoneTools redstone() {
		return redstone;
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
