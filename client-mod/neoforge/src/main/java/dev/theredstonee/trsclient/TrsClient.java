package dev.theredstonee.trsclient;

import dev.theredstonee.trsclient.core.i18n.I18n;

import com.mojang.blaze3d.platform.InputConstants;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.input.ClickCounter;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.zoom.ZoomState;
import dev.theredstonee.trsclient.dev.AutoTest;
import dev.theredstonee.trsclient.feature.PvpFeatures;
import dev.theredstonee.trsclient.hud.HudManager;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.WaypointEditScreen;
import dev.theredstonee.trsclient.screen.WaypointListScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Consumer;
//? if >=1.20.5 {
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
//?} else {
/*import net.neoforged.neoforge.client.ConfigScreenHandler;
import net.neoforged.neoforge.client.event.RenderGuiOverlayEvent;
import net.neoforged.neoforge.client.gui.overlay.VanillaGuiOverlay;
import net.neoforged.neoforge.event.TickEvent;
*///?}
//? if >=1.21.11 {
/*import net.minecraft.resources.Identifier;
*///?} elif >=1.21.6 {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/**
 * Einstiegspunkt des TRS Clients für NeoForge (nur Client; auf einem Server macht die Mod nichts).
 * NeoForge-Events für Einstieg, Tasten, Ticks, HUD und Fadenkreuz; Zoom, Maus, Fullbright, Freelook,
 * Treffer-Farbe und Startbildschirm über dieselben Vanilla-Mixins wie der Fabric-Build
 * (NeoForge liefert Mixin + MixinExtras mit).
 */
@Mod(TrsClient.MOD_ID)
public final class TrsClient {
	public static final String MOD_ID = "trsclient";
	public static final Logger LOGGER = LoggerFactory.getLogger("TRS Client");

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

	// @Mod(dist = CLIENT) gibt es erst ab NeoForge 20.5 – daher die Seite als Konstruktor-Argument (ab 20.2).
	public TrsClient(IEventBus modBus, ModContainer container, Dist dist) {
		if (dist != Dist.CLIENT) {
			LOGGER.info("TRS Client ist eine reine Client-Mod – auf dem Server inaktiv");
			return;
		}
		instance = this;
		// Farben des Launchers (config/trsclient/launcher-theme.json) – fehlt sie, gilt das Standard-Thema.
		dev.theredstonee.trsclient.core.ui.Theme.loadFrom(FMLPaths.CONFIGDIR.get());
		dev.theredstonee.trsclient.core.i18n.I18n.init(FMLPaths.CONFIGDIR.get());
		dev.theredstonee.trsclient.core.clips.Clips.init(FMLPaths.CONFIGDIR.get());
		// Grafik-Modus „Schön“/„Max FPS“ (config/trsclient/fps-mode.json).
		dev.theredstonee.trsclient.core.perf.FpsConfigMode.init(FMLPaths.CONFIGDIR.get());
		config = new ConfigStore(FMLPaths.CONFIGDIR.get().resolve("trsclient.json"));
		ConfigStore.Status status = config.load(modules.registry);
		// Zoom-/Freelook-Taste sind Vanilla-Belegungen – im TRS-Menü ändern sie dieselbe Belegung.
		modules.zoomKey.link(TrsKeys.link(() -> TrsKeys.zoom));
		modules.freelookKey.link(TrsKeys.link(() -> TrsKeys.freelook));
		if (status == ConfigStore.Status.RECOVERED) {
			LOGGER.warn("Config war beschädigt – Standardwerte geladen, Sicherung: {}", config.brokenFile());
		}

		modBus.addListener(RegisterKeyMappingsEvent.class, TrsKeys::register);
		waypoints = new dev.theredstonee.trsclient.feature.Waypoints(modules,
				FMLPaths.CONFIGDIR.get().resolve("trsclient-waypoints.json"));
		IEventBus bus = NeoForge.EVENT_BUS;
		// HUD über allem anderen (nach der Vanilla-GUI), das eigene Fadenkreuz statt der Vanilla-Ebene.
		bus.addListener(RenderGuiEvent.Post.class, e -> hud().render(Gfx.of(e.getGuiGraphics())));
		//? if >=1.20.5 {
		bus.addListener(RenderGuiLayerEvent.Pre.class, e -> {
			if (e.getName().equals(VanillaGuiLayers.CROSSHAIR) && hud().crosshair().replacesVanilla()) {
				e.setCanceled(true);
				hud().crosshair().drawInGame(Gfx.of(e.getGuiGraphics()));
			}
		});
		//?} else {
		/*bus.addListener(RenderGuiOverlayEvent.Pre.class, e -> {
			if (e.getOverlay().id().equals(VanillaGuiOverlay.CROSSHAIR.id()) && hud().crosshair().replacesVanilla()) {
				e.setCanceled(true);
				hud().crosshair().drawInGame(Gfx.of(e.getGuiGraphics()));
			}
		});
		*///?}
		// Vor der Spieler-Bewegung: Toggle-Tasten, Freelook, Treffer-Farbe. Danach: Menü-/Fullbright-Taste.
		onStartTick(pvp::tick);
		onEndTick(this::onTick);
		bus.addListener(GameShuttingDownEvent.class, e -> {
			saveConfig();
			waypoints.save();
		});

		// "Konfigurieren" in der Mod-Liste öffnet das TRS-Menü.
		//? if >=1.20.5 {
		container.registerExtensionPoint(IConfigScreenFactory.class, (c, parent) -> new TrsMenuScreen(parent));
		//?} else
		/*container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new TrsMenuScreen(parent)));*/
		// TRS API (Abzeichen, TRS-Umhänge, Presence) + Umhang-Physik; nichts davon blockiert den Start.
		dev.theredstonee.trsclient.online.OnlineHooks.init(FMLPaths.CONFIGDIR.get(), modules, modVersion(MOD_ID),
				modVersion("minecraft"), "neoforge", message -> LOGGER.info(message));
		// Konten: Wechsel ohne Neustart (mit TRS Launcher dessen Konten, sonst eigene Anmeldung je Instanz).
		dev.theredstonee.trsclient.core.account.AccountManager.init(new dev.theredstonee.trsclient.online.SessionSwap(
				FMLPaths.CONFIGDIR.get(), "TRS-Client/" + modVersion(MOD_ID) + " (Minecraft " + modVersion("minecraft") + "; neoforge)", message -> LOGGER.info(message)));
		// Leistung (Dynamische FPS, Culling, Partikel, Welt-Details, FPS-Boost); Leistungs-Mods übernehmen ihre Teile.
		dev.theredstonee.trsclient.perf.PerfHooks.init(modules, id -> ModList.get().isLoaded(id),
				dev.theredstonee.trsclient.core.perf.PerfCompat.NEOFORGE, modVersion("minecraft"), message -> LOGGER.info(message), true);
		AutoTest.installIfRequested();

		LOGGER.info("TRS Client {} initialisiert (NeoForge {}) – {} Module, Config {} ({})",
				modVersion(MOD_ID), modVersion("neoforge"), modules.registry.all().size(), status, config.file());
	}

	/** Am Anfang jedes Client-Ticks (ClientTickEvent.Pre bzw. TickEvent-Phase START bis 1.20.4). */
	public static void onStartTick(Consumer<Minecraft> handler) {
		//? if >=1.20.5 {
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, e -> handler.accept(Minecraft.getInstance()));
		//?} else {
		/*NeoForge.EVENT_BUS.addListener(TickEvent.ClientTickEvent.class, e -> {
			if (e.phase == TickEvent.Phase.START) handler.accept(Minecraft.getInstance());
		});
		*///?}
	}

	/** Am Ende jedes Client-Ticks (ClientTickEvent.Post bzw. TickEvent-Phase END bis 1.20.4). */
	public static void onEndTick(Consumer<Minecraft> handler) {
		//? if >=1.20.5 {
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> handler.accept(Minecraft.getInstance()));
		//?} else {
		/*NeoForge.EVENT_BUS.addListener(TickEvent.ClientTickEvent.class, e -> {
			if (e.phase == TickEvent.Phase.END) handler.accept(Minecraft.getInstance());
		});
		*///?}
	}

	/** Version einer geladenen Mod ("minecraft", "neoforge", "trsclient"), sonst "?". */
	public static String modVersion(String modId) {
		return ModList.get().getModContainerById(modId)
				.map(c -> c.getModInfo().getVersion().toString()).orElse("?");
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

	/** Meldungen der Clips in der Aktionsleiste. */
	private static final dev.theredstonee.trsclient.core.clips.Clips.ActionBar CLIP_MESSAGES = text -> Mc.actionBar(Component.literal(text));

	private void onTick(Minecraft mc) {
		migrateKeys(mc);
		while (TrsKeys.hudProfile.consumeClick()) {
			String name = modules.profiles.cycle();
			Mc.actionBar(Component.literal(I18n.tr("toast.hudProfile", name)));
			saveConfig();
		}
		while (TrsKeys.menu.consumeClick()) {
			if (Mc.screen() == null) Mc.setScreen(new TrsMenuScreen(null));
		}
		while (TrsKeys.redstoneOverlay.consumeClick()) {
			modules.redstoneOverlay.toggle();
			Mc.actionBar(Component.literal(I18n.tr("toast.redstoneOverlay", modules.redstoneOverlay.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled"))));
			saveConfig();
		}
		// Clips & Aufnahme: aufgenommen wird im Launcher, hier nur die Tasten melden.
		while (TrsKeys.saveClip.consumeClick()) dev.theredstonee.trsclient.core.clips.Clips.get().saveClip();
		while (TrsKeys.toggleRecording.consumeClick()) dev.theredstonee.trsclient.core.clips.Clips.get().toggleRecording();
		dev.theredstonee.trsclient.core.clips.Clips.get().tick(modules.clips.isEnabled(), CLIP_MESSAGES);
		while (TrsKeys.fullbright.consumeClick()) {
			modules.fullbright.toggle();
			Mc.actionBar(Component.literal(I18n.tr("toast.fullbright", modules.fullbright.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled"))));
			saveConfig();
		}
		// Emote-Rad: Taste halten öffnet es, Loslassen (im Rad abgefragt) spielt das gezeigte Emote.
		while (TrsKeys.emoteWheel.consumeClick()) {
			if (Mc.screen() == null && mc.player != null && dev.theredstonee.trsclient.online.EmoteHooks.enabled()) {
				Mc.setScreen(new dev.theredstonee.trsclient.screen.EmoteWheelScreen());
			}
		}
		// Garderobe (Taste standardmäßig unbelegt)
		while (TrsKeys.wardrobe.consumeClick()) {
			if (Mc.screen() == null && dev.theredstonee.trsclient.screen.WardrobeScreen.available()) {
				Mc.setScreen(dev.theredstonee.trsclient.screen.WardrobeScreen.create(null));
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
		hud().tick();
		tickRedstone();
		dev.theredstonee.trsclient.online.OnlineHooks.tick(mc);
		dev.theredstonee.trsclient.online.EmoteHooks.tick(mc);
		dev.theredstonee.trsclient.perf.PerfHooks.tick(mc);
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
			config.saveLater(modules.registry);
		} catch (IOException e) {
			LOGGER.error("Config konnte nicht gespeichert werden: {}", config.file(), e);
		}
	}

	// --- Hooks aus den Mixins ---

	/** Mausklick im Spiel (nicht in Menüs) → CPS-Zähler. */
	public void onMouseClick(int button) {
		if (Mc.screen() != null) return;
		long now = System.currentTimeMillis();
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			leftClicks.record(now);
			// Angreifen/Abbauen beendet das eigene Emote (wie Bewegung).
			dev.theredstonee.trsclient.online.EmoteHooks.onAttack();
		}
		if (button == InputConstants.MOUSE_BUTTON_RIGHT) rightClicks.record(now);
	}

	/** Pro Frame aus getFov: aktualisiert den Zoom und liefert den FOV-Divisor. */
	public double updateZoom() {
		// Das Fernrohr hat Vorrang; die filmische Kamera gilt auf Wunsch nur, solange gezoomt wird.
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

	/** Maus-Divisor während des Zooms (1 = unverändert). */
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

	/**
	 * HUD-Elemente erst bei Bedarf anlegen: ab NeoForge 21.6 wird die Mod konstruiert, bevor es
	 * Minecraft.getInstance() gibt (die HUD-Klassen merken sich die Instanz).
	 */
	public HudManager hud() {
		if (hud == null) hud = new HudManager(modules);
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

	public dev.theredstonee.trsclient.core.redstone.RedstoneTools redstone() {
		return redstone;
	}

	public dev.theredstonee.trsclient.feature.Waypoints waypoints() {
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
