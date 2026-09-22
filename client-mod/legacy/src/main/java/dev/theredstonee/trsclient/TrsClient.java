package dev.theredstonee.trsclient;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.input.ClickCounter;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.zoom.ZoomState;
import dev.theredstonee.trsclient.dev.AutoTest;
import dev.theredstonee.trsclient.dev.HookStats;
import dev.theredstonee.trsclient.feature.PvpFeatures;
import dev.theredstonee.trsclient.hud.HudManager;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.MouseHelper;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Einstiegspunkt des TRS Clients für Legacy-Forge (Minecraft 1.8.9 – 1.12.2, nur Client).
 * <p>
 * Kommt ohne Mixins/Coremod aus – alles läuft über Forge-Events:
 * HUD über {@link RenderGameOverlayEvent.Post}, eigenes Fadenkreuz durch Abbrechen von
 * {@link RenderGameOverlayEvent.Pre} (CROSSHAIRS), Zoom über {@link EntityViewRenderEvent.FOVModifier},
 * Freelook über {@link EntityViewRenderEvent.CameraSetup}, CPS und Zoom-Mausrad über {@link MouseEvent},
 * langsamere Maus über einen eigenen {@link MouseHelper}, Fullbright über einen kurzzeitig ersetzten
 * Gamma-Wert nur während der Lightmap-Berechnung, TRS-Startbildschirm über {@link GuiOpenEvent}.
 */
@Mod(modid = TrsClient.MOD_ID, name = "TRS Client", version = BuildInfo.VERSION, useMetadata = true, clientSideOnly = true,
		acceptedMinecraftVersions = BuildInfo.ACCEPTED_MINECRAFT)
public final class TrsClient {
	public static final String MOD_ID = "trsclient";
	public static final Logger LOGGER = LogManager.getLogger("TRS Client");
	/** Gamma für Fullbright (Vanilla-Maximum ist 1.0). */
	private static final float FULLBRIGHT_GAMMA = 16.0F;

	private static TrsClient instance;

	private final TrsModules modules = new TrsModules();
	private final ClickCounter leftClicks = new ClickCounter();
	private final ClickCounter rightClicks = new ClickCounter();
	private final ZoomState zoom = new ZoomState();
	private final PvpFeatures pvp = new PvpFeatures(modules);
	/** Module im TRS-Menü (ohne die, die unter Legacy-Forge nicht umsetzbar sind). */
	private final List<Module> menuModules;
	private ConfigStore config;
	private HudManager hud;
	private String version = "?";
	/** Nur für den Autotest: Zoom ohne Tastendruck erzwingen. */
	private boolean forceZoom;
	/** Der nächste FOV-Aufruf gehört zur Hand (nicht zoomen). */
	private boolean handPass;
	/** Fullbright: ersetzter Gamma-Wert, solange {@link #gammaSwapped}. */
	private float savedGamma;
	private boolean gammaSwapped;
	/** Einmalig den Vanilla-Titelbildschirm zulassen ("Klassisch" auf dem TRS-Startbildschirm). */
	private boolean vanillaTitleOnce;

	public TrsClient() {
		List<Module> list = new ArrayList<>(modules.registry.all());
		// Treffer-Farbe: in RendererLivingEntity fest einprogrammiert – ohne Coremod nicht änderbar.
		list.remove(modules.hitColor);
		menuModules = Collections.unmodifiableList(list);
	}

	public static TrsClient get() {
		return instance;
	}

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		instance = this;
		version = event.getModMetadata().version;
		File file = new File(event.getModConfigurationDirectory(), "trsclient.json");
		// Farben des Launchers (config/trsclient/launcher-theme.json) – fehlt sie, gilt das Standard-Thema.
		dev.theredstonee.trsclient.core.ui.Theme.loadFrom(file.getParentFile().toPath());
		config = new ConfigStore(file.toPath());
		ConfigStore.Status status = config.load(modules.registry);
		if (status == ConfigStore.Status.RECOVERED) {
			LOGGER.warn("Config war beschädigt – Standardwerte geladen, Sicherung: {}", config.brokenFile());
		}
		LOGGER.info("Config {} ({})", status, config.file());
	}

	@Mod.EventHandler
	public void init(FMLInitializationEvent event) {
		TrsKeys.register();
		hud = new HudManager(modules);
		MinecraftForge.EVENT_BUS.register(this);
		AutoTest.installIfRequested();
		// Legacy-Forge hat kein "Client stoppt"-Ereignis – beim Beenden trotzdem speichern.
		Runtime.getRuntime().addShutdownHook(new Thread(this::saveConfig, "TRS Client config save"));
	}

	@Mod.EventHandler
	public void postInit(FMLPostInitializationEvent event) {
		Minecraft mc = Minecraft.getMinecraft();
		// Nur den Vanilla-MouseHelper ersetzen – hat ein anderer Mod schon einen eigenen, bleibt der.
		boolean ownMouse = mc.mouseHelper != null && mc.mouseHelper.getClass() == MouseHelper.class;
		if (ownMouse) mc.mouseHelper = new ZoomMouseHelper(this);
		else LOGGER.warn("MouseHelper ist bereits ersetzt ({}) – Zoom verlangsamt die Maus nicht, kein Freelook", mc.mouseHelper);
		LOGGER.info("TRS Client {} initialisiert (Minecraft {}) – {} Module, Forge-Events registriert, Maus-Hook {}",
				version, Mc.version(), modules.registry.all().size(), ownMouse ? "aktiv" : "inaktiv");
	}

	// --- Forge-Events ---

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		migrateKeys(mc);
		while (TrsKeys.hudProfile.isPressed()) {
			Mc.actionBar("HUD-Profil: " + modules.profiles.cycle());
			saveConfig();
		}
		while (TrsKeys.menu.isPressed()) {
			if (mc.currentScreen == null) mc.displayGuiScreen(new TrsMenuScreen(null));
		}
		while (TrsKeys.fullbright.isPressed()) {
			modules.fullbright.toggle();
			Mc.actionBar("Fullbright: " + (modules.fullbright.isEnabled() ? "An" : "Aus"));
			saveConfig();
		}
		pvp.tick(mc);
	}

	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		Minecraft mc = Minecraft.getMinecraft();
		if (event.phase == TickEvent.Phase.START) {
			handPass = false;
			updateZoom(mc);
			// Fullbright: Gamma nur bis zur Lightmap-Berechnung ersetzen (siehe restoreGamma).
			// In den Video-Einstellungen nicht – dort zeigt/ändert der Regler den echten Wert.
			if (modules.fullbright.isEnabled() && Mc.world() != null && !(mc.currentScreen instanceof GuiVideoSettings)) {
				savedGamma = mc.gameSettings.gammaSetting;
				mc.gameSettings.gammaSetting = FULLBRIGHT_GAMMA;
				gammaSwapped = true;
				HookStats.lightmap++;
			}
		} else {
			restoreGamma(mc);
		}
	}

	/**
	 * Lightmap ist berechnet (erstes Ereignis nach {@code updateLightmap} in {@code renderWorld}) →
	 * echten Gamma-Wert sofort zurück. So sieht nichts anderes (Menüs, Speichern der Optionen) je 16.0.
	 */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onFogColors(EntityViewRenderEvent.FogColors event) {
		restoreGamma(Minecraft.getMinecraft());
	}

	@SubscribeEvent
	public void onFov(EntityViewRenderEvent.FOVModifier event) {
		HookStats.fov++;
		restoreGamma(Minecraft.getMinecraft());
		if (handPass) {
			// Hand nicht mitzoomen.
			handPass = false;
			return;
		}
		double factor = zoom.factor();
		if (factor != 1.0) event.setFOV((float) (event.getFOV() / factor));
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onRenderHand(RenderHandEvent event) {
		// Direkt danach fragt renderHand() das Sichtfeld ab.
		handPass = true;
	}

	/** Freelook: Kamera mit eigenen Blickwinkeln um die Spielfigur drehen. */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
		if (!pvp.freelook().active()) return;
		HookStats.camera++;
		// Vanilla übergibt yaw + 180° (Blickrichtung → Kameradrehung).
		Mc.setCamera(event, pvp.freelook().yaw() + 180.0F, pvp.freelook().pitch());
	}

	@SubscribeEvent
	public void onMouse(MouseEvent event) {
		HookStats.mouseEvent++;
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.currentScreen != null) return;
		int button = Mc.mouseButton(event);
		if (Mc.mouseButtonDown(event)) {
			long now = System.currentTimeMillis();
			if (button == 0) leftClicks.record(now);
			else if (button == 1) rightClicks.record(now);
		}
		// Reines Mausrad-Ereignis während des Zooms → Zoomstufe statt Hotbar.
		int wheel = Mc.mouseWheel(event);
		if (button == -1 && wheel != 0 && zoom.isActive() && modules.zoomScroll.get()) {
			HookStats.scroll++;
			zoom.scroll(wheel);
			event.setCanceled(true);
		}
	}

	/** Vanilla-Fadenkreuz ausblenden, solange das eigene aktiv ist. */
	@SubscribeEvent
	public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
		if (Mc.overlayType(event) == RenderGameOverlayEvent.ElementType.CROSSHAIRS && hud.crosshair().replacesVanilla()) {
			HookStats.crosshair++;
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onOverlay(RenderGameOverlayEvent.Post event) {
		if (Mc.overlayType(event) != RenderGameOverlayEvent.ElementType.ALL) return;
		HookStats.hud++;
		ScaledResolution res = Mc.resolution(event);
		hud.render(Gfx.of(res.getScaledWidth(), res.getScaledHeight()));
	}

	/** Ersetzt den Vanilla-Titelbildschirm durch den TRS-Startbildschirm (Modul "Startbildschirm"). */
	@SubscribeEvent
	public void onGuiOpen(GuiOpenEvent event) {
		GuiScreen screen = Mc.openedGui(event);
		if (screen == null || screen.getClass() != GuiMainMenu.class) return;
		if (vanillaTitleOnce) {
			vanillaTitleOnce = false;
			return;
		}
		if (modules.titleScreen.isEnabled()) Mc.setOpenedGui(event, new TrsTitleScreen());
	}

	/** Öffnet einmalig den Vanilla-Titelbildschirm. */
	public void openVanillaTitle() {
		vanillaTitleOnce = true;
		Mc.setScreen(new GuiMainMenu());
	}

	// --- Logik ---

	private void updateZoom(Minecraft mc) {
		boolean active = modules.zoom.isEnabled()
				&& (forceZoom || (TrsKeys.zoom.isKeyDown() && mc.currentScreen == null));
		zoom.update(active, modules.zoomFactor.get(), modules.zoomSmooth.get(), System.nanoTime());
	}

	private void restoreGamma(Minecraft mc) {
		if (!gammaSwapped) return;
		gammaSwapped = false;
		mc.gameSettings.gammaSetting = savedGamma;
	}

	/** Maus-Divisor während des Zooms (1 = unverändert). */
	public double mouseDivisor() {
		return modules.zoom.isEnabled() && modules.zoomSlowMouse.get() ? zoom.factor() : 1.0;
	}

	/**
	 * Einmalige Umstellung alter Standard-Tasten: Zoom lag auf C, was ab Minecraft 1.12 mit
	 * "Schnellleiste speichern" kollidiert. Selbst belegte Tasten bleiben unangetastet.
	 */
	private void migrateKeys(Minecraft mc) {
		if (!modules.keyDefaults.needsZoomKeyMigration() || mc.gameSettings == null) return;
		if (TrsKeys.migrateZoomKey()) {
			mc.gameSettings.saveOptions();
			LOGGER.info("Zoom-Taste von C auf V umgestellt (C ist ab 1.12 'Schnellleiste speichern')");
		}
		modules.keyDefaults.markMigrated();
		saveConfig();
	}

	/** Speichert die Einstellungen (Fehler nur loggen). */
	public void saveConfig() {
		if (config == null) return;
		try {
			config.save(modules.registry);
		} catch (IOException e) {
			LOGGER.error("Config konnte nicht gespeichert werden: " + config.file(), e);
		}
	}

	// --- Zugriff ---

	public String version() {
		return version;
	}

	public TrsModules modules() {
		return modules;
	}

	public List<Module> menuModules() {
		return menuModules;
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

	public ZoomState zoom() {
		return zoom;
	}

	public PvpFeatures pvp() {
		return pvp;
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
