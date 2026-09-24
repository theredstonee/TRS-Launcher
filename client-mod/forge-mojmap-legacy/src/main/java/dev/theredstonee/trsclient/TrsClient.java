package dev.theredstonee.trsclient;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.input.ClickCounter;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.Module;
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
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
//? if >=1.19 {
/*import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
*///?} else {
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
//?}
//? if >=1.18 && <1.19 {
/*import net.minecraftforge.client.ConfigGuiHandler;
import net.minecraftforge.client.event.ScreenOpenEvent;
import net.minecraftforge.client.gui.ForgeIngameGui;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.network.NetworkConstants;
*///?} elif >=1.17 && <1.18 {
/*import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.gui.ForgeIngameGui;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fmlclient.ConfigGuiHandler;
import net.minecraftforge.fmllegacy.network.FMLNetworkConstants;
*///?} elif <1.17 {
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;
//?}
//? if <1.15 {
/*import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
*///?}

/**
 * TRS Client für Forge 1.14.4–1.19.4 (nur Client). HUD, Fadenkreuz, Tick, Tasten und Titelbildschirm
 * laufen über Forge-Events; Zoom, Maus, Fullbright, Freelook und Treffer-Farbe über Mixins
 * (Forge liefert Mixin erst ab 1.15.2 mit – auf 1.14.4 gibt es Event-Ersatz, siehe {@link #legacyHooks}).
 */
public final class TrsClient {
	public static final String MOD_ID = "trsclient";
	public static final Logger LOGGER = LogManager.getLogger("TRS Client");

	private static TrsClient instance;

	private final TrsModules modules = new TrsModules();
	private final ClickCounter leftClicks = new ClickCounter();
	private final ClickCounter rightClicks = new ClickCounter();
	private final ZoomState zoom = new ZoomState();
	/** Filmische Kamera nur während des Zooms (stellt den Wert des Spielers danach wieder her). */
	private final dev.theredstonee.trsclient.core.util.FlagOverride zoomCinematic =
			new dev.theredstonee.trsclient.core.util.FlagOverride();
	private final ConfigStore config;
	private final HudManager hud;
	private final PvpFeatures pvp = new PvpFeatures(modules);
	private final ChatFeatures chat = new ChatFeatures(modules);
	/** Redstone-Werkzeuge: Signalstärke, Takt-Messer, Signal-Overlay (Logik in core.redstone). */
	private final dev.theredstonee.trsclient.core.redstone.RedstoneTools redstone =
			new dev.theredstonee.trsclient.core.redstone.RedstoneTools(modules);
	private long redstoneErrorLogged;
	private final Waypoints waypoints;
	/** Tastendruck-Erkennung für die Modul-Tasten (Wegpunkte, Text-Hotkeys). */
	private final dev.theredstonee.trsclient.core.input.KeyPresses moduleKeys =
			new dev.theredstonee.trsclient.core.input.KeyPresses(dev.theredstonee.trsclient.compat.Keys::isDown);
	private final List<Module> visibleModules = new ArrayList<>();
	/** Einmalig den Vanilla-Titelbildschirm zulassen ("Klassisch" auf dem TRS-Startbildschirm). */
	private boolean vanillaTitleOnce;
	/** Nur für den Autotest: Zoom ohne Tastendruck erzwingen. */
	private boolean forceZoom;
	/** Zuletzt benutztes Welt-Sichtfeld (für die Wegpunkt-Projektion), aus dem FOV-Hook. */
	private double worldFov = 70;
	/** Welt, zu der der Kartenspeicher der Minimap gehört (Wechsel → leeren). */
	private boolean hadLevel;

	public static TrsClient get() {
		return instance;
	}

	static void init() {
		instance = new TrsClient();
	}

	private TrsClient() {
		instance = this;
		// Farben des Launchers (config/trsclient/launcher-theme.json) – fehlt sie, gilt das Standard-Thema.
		dev.theredstonee.trsclient.core.ui.Theme.loadFrom(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
		dev.theredstonee.trsclient.core.i18n.I18n.init(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
		dev.theredstonee.trsclient.core.clips.Clips.init(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
		config = new ConfigStore(FMLPaths.CONFIGDIR.get().resolve("trsclient.json"));
		ConfigStore.Status status = config.load(modules.registry);
		// Zoom-/Freelook-Taste sind Vanilla-Belegungen – im TRS-Menü ändern sie dieselbe Belegung.
		modules.zoomKey.link(TrsKeys.link(() -> TrsKeys.zoom));
		modules.freelookKey.link(TrsKeys.link(() -> TrsKeys.freelook));
		if (status == ConfigStore.Status.RECOVERED) {
			LOGGER.warn("Config war beschädigt – Standardwerte geladen, Sicherung: {}", config.brokenFile());
		}
		waypoints = new Waypoints(modules, FMLPaths.CONFIGDIR.get().resolve("trsclient-waypoints.json"));
		hud = new HudManager(modules);
		for (Module m : modules.registry.all()) {
			// Ohne Mixin (Forge 1.14.4) fehlen alle Module, die einen Mixin-Hook brauchen.
			if (!PvpFeatures.mixinFeatures() && (m == modules.freelook || m == modules.hitColor
					|| m == modules.reach || m == modules.combo || m == modules.chat || m == modules.autoGg
					|| m == modules.noHurtCam || m == modules.lowFire || m == modules.blockOutline
					|| m == modules.capePhysics || m == modules.emotes)) {
				continue;
			}
			visibleModules.add(m);
		}

		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		IEventBus bus = MinecraftForge.EVENT_BUS;
		//? if >=1.19 {
		/*modBus.addListener((RegisterKeyMappingsEvent e) -> TrsKeys.register(e));
		bus.addListener((RenderGuiEvent.Post e) -> hud.render(Gfx.of(e.getPoseStack())));
		bus.addListener((RenderGuiOverlayEvent.Pre e) -> {
			if (e.getOverlay().id().equals(VanillaGuiOverlay.CROSSHAIR.id()) && hud.crosshair().replacesVanilla()) e.setCanceled(true);
		});
		bus.addListener((ScreenEvent.Opening e) -> e.setNewScreen(replaceScreen(e.getNewScreen())));
		*///?} else {
		modBus.addListener((FMLClientSetupEvent e) -> TrsKeys.register());
		bus.addListener((RenderGameOverlayEvent.Post e) -> {
			//? if >=1.16 {
			if (e.getType() == RenderGameOverlayEvent.ElementType.ALL) hud.render(Gfx.of(e.getMatrixStack()));
			//?} else
			/*if (e.getType() == RenderGameOverlayEvent.ElementType.ALL) hud.render(Gfx.of());*/
		});
		//?}
		//? if >=1.17 && <1.19 {
		/*bus.addListener((RenderGameOverlayEvent.PreLayer e) -> {
			if (e.getOverlay() == ForgeIngameGui.CROSSHAIR_ELEMENT && hud.crosshair().replacesVanilla()) e.setCanceled(true);
		});
		*///?} elif <1.17 {
		bus.addListener((RenderGameOverlayEvent.Pre e) -> {
			if (e.getType() == RenderGameOverlayEvent.ElementType.CROSSHAIRS && hud.crosshair().replacesVanilla()) e.setCanceled(true);
		});
		//?}
		//? if >=1.18 && <1.19 {
		/*bus.addListener((ScreenOpenEvent e) -> e.setScreen(replaceScreen(e.getScreen())));
		*///?} elif <1.18 {
		bus.addListener((GuiOpenEvent e) -> e.setGui(replaceScreen(e.getGui())));
		//?}

		bus.addListener((TickEvent.ClientTickEvent e) -> {
			// Vor der Spieler-Bewegung: Toggle-Tasten, Freelook, Treffer-Farbe; danach Menü-Tasten.
			if (e.phase == TickEvent.Phase.START) pvp.tick(Minecraft.getInstance());
			else onTick(Minecraft.getInstance());
		});
		//? if <1.15
		/*legacyHooks(bus);*/

		// "Config" in der Mod-Liste öffnet das TRS-Menü; reine Client-Mod → Server-Versionsabgleich egal.
		ModLoadingContext ctx = ModLoadingContext.get();
		//? if >=1.19 {
		/*ctx.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
				() -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new TrsMenuScreen(parent)));
		*///?} elif >=1.17 {
		/*ctx.registerExtensionPoint(ConfigGuiHandler.ConfigGuiFactory.class,
				() -> new ConfigGuiHandler.ConfigGuiFactory((mc, parent) -> new TrsMenuScreen(parent)));
		*///?} else
		ctx.registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY, () -> (mc, parent) -> new TrsMenuScreen(parent));
		//? if >=1.18 && <1.19 {
		/*ctx.registerExtensionPoint(IExtensionPoint.DisplayTest.class,
				() -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, network) -> true));
		*///?} elif >=1.17 && <1.18 {
		/*ctx.registerExtensionPoint(IExtensionPoint.DisplayTest.class,
				() -> new IExtensionPoint.DisplayTest(() -> FMLNetworkConstants.IGNORESERVERONLY, (remote, network) -> true));
		*///?} elif <1.17 {
		ctx.registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
				() -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (remote, network) -> true));
		//?}
		// TRS API (Abzeichen, TRS-Umhänge, Presence) + Umhang-Physik; nichts davon blockiert den Start.
		dev.theredstonee.trsclient.online.OnlineHooks.init(FMLPaths.CONFIGDIR.get(), modules, Mc.modVersion(MOD_ID),
				Mc.modVersion("minecraft"), "forge", message -> LOGGER.info(message));
		AutoTest.installIfRequested();

		LOGGER.info("TRS Client {} initialisiert (Forge {}) – {} Module, Config {} ({})",
				Mc.modVersion(MOD_ID), Mc.modVersion("forge"), visibleModules.size(), status, config.file());
	}

	//? if <1.15 {
	/*private boolean handRendering;
	private double savedGamma = Double.NaN;

	/^*
	 * Forge 1.14.4 liefert noch kein Mixin mit – dort übernehmen Events die Mixin-Hooks:
	 * Zoom über das FOV-Event (die Hand wird nach RenderWorldLastEvent gezeichnet und nicht gezoomt),
	 * CPS/Mausrad über InputEvent, Fullbright über Gamma nur zwischen Frame-Beginn und Lightmap-Update.
	 * Langsamere Maus, Freelook und Treffer-Farbe gibt es hier nicht.
	 ^/
	private void legacyHooks(IEventBus bus) {
		bus.addListener((EntityViewRenderEvent.FOVModifier e) -> {
			dev.theredstonee.trsclient.dev.HookStats.fov++;
			restoreGamma();
			if (handRendering) return;
			double factor = updateZoom();
			if (factor != 1.0) e.setFOV(e.getFOV() / factor);
			// Sichtfeld der Welt merken – daraus rechnet die Wegpunkt-Anzeige ihre Positionen.
			setWorldFov(e.getFOV());
		});
		bus.addListener((RenderWorldLastEvent e) -> handRendering = true);
		bus.addListener((TickEvent.RenderTickEvent e) -> {
			if (e.phase == TickEvent.Phase.START) {
				handRendering = false;
				if (fullbright() && Mc.mc().level != null && !(Mc.screen() instanceof net.minecraft.client.gui.screens.VideoSettingsScreen)) {
					savedGamma = Mc.gamma();
					Mc.setGamma(16.0);
					dev.theredstonee.trsclient.dev.HookStats.lightmap++;
				}
			} else {
				restoreGamma();
			}
		});
		bus.addListener((InputEvent.MouseInputEvent e) -> {
			dev.theredstonee.trsclient.dev.HookStats.press++;
			if (e.getAction() == GLFW.GLFW_PRESS) onMouseClick(e.getButton());
		});
		bus.addListener((InputEvent.MouseScrollEvent e) -> {
			dev.theredstonee.trsclient.dev.HookStats.scroll++;
			if (e.getScrollDelta() != 0 && onScroll(e.getScrollDelta())) e.setCanceled(true);
		});
	}

	private void restoreGamma() {
		if (!Double.isNaN(savedGamma)) {
			Mc.setGamma(savedGamma);
			savedGamma = Double.NaN;
		}
	}
	*///?}

	/** Ersetzt den Vanilla-Titelbildschirm durch den TRS-Startbildschirm, solange das Modul an ist. */
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

	/** Meldungen der Clips in der Aktionsleiste. */
	private static final dev.theredstonee.trsclient.core.clips.Clips.ActionBar CLIP_MESSAGES = text -> Mc.actionBar(text);

	private void onTick(Minecraft mc) {
		migrateKeys(mc);
		while (TrsKeys.hudProfile.consumeClick()) {
			String name = modules.profiles.cycle();
			Mc.actionBar(I18n.tr("toast.hudProfile", name));
			saveConfig();
		}
		while (TrsKeys.menu.consumeClick()) {
			if (Mc.screen() == null) Mc.setScreen(new TrsMenuScreen(null));
		}
		while (TrsKeys.redstoneOverlay.consumeClick()) {
			modules.redstoneOverlay.toggle();
			Mc.actionBar(I18n.tr("toast.redstoneOverlay", modules.redstoneOverlay.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled")));
			saveConfig();
		}
		// Clips & Aufnahme: aufgenommen wird im Launcher, hier nur die Tasten melden.
		while (TrsKeys.saveClip.consumeClick()) dev.theredstonee.trsclient.core.clips.Clips.get().saveClip();
		while (TrsKeys.toggleRecording.consumeClick()) dev.theredstonee.trsclient.core.clips.Clips.get().toggleRecording();
		dev.theredstonee.trsclient.core.clips.Clips.get().tick(modules.clips.isEnabled(), CLIP_MESSAGES);
		while (TrsKeys.fullbright.consumeClick()) {
			modules.fullbright.toggle();
			Mc.actionBar(I18n.tr("toast.fullbright", modules.fullbright.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled")));
			saveConfig();
		}
		// Emote-Rad: Taste halten öffnet es, Loslassen (im Rad abgefragt) spielt das gezeigte Emote.
		while (TrsKeys.emoteWheel.consumeClick()) {
			if (Mc.screen() == null && mc.player != null && visibleModules.contains(modules.emotes)
					&& dev.theredstonee.trsclient.online.EmoteHooks.enabled()) {
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
		// Welt verlassen/betreten: Kartenspeicher der Minimap leeren.
		boolean hasLevel = mc.level != null;
		if (hasLevel != hadLevel) {
			hud.onWorldChange();
			hadLevel = hasLevel;
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
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			leftClicks.record(now);
			// Angreifen/Abbauen beendet das eigene Emote (wie Bewegung).
			dev.theredstonee.trsclient.online.EmoteHooks.onAttack();
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) rightClicks.record(now);
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

	/** Module, die es auf dieser Version gibt (Menü-Reihenfolge). */
	public List<Module> visibleModules() {
		return visibleModules;
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

	public dev.theredstonee.trsclient.core.redstone.RedstoneTools redstone() {
		return redstone;
	}

	public Waypoints waypoints() {
		return waypoints;
	}

	/** Zuletzt gezeichnetes Sichtfeld der Welt (Grad) – Grundlage der Wegpunkt-Projektion. */
	public double worldFov() {
		return worldFov;
	}

	/** Aus dem FOV-Hook: das tatsächlich benutzte Sichtfeld merken. */
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
