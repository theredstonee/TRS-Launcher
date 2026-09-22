package dev.theredstonee.trsclient;

import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.input.ClickCounter;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.zoom.ZoomState;
import dev.theredstonee.trsclient.dev.AutoTest;
import dev.theredstonee.trsclient.dev.HookStats;
import dev.theredstonee.trsclient.hud.HudManager;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.util.MouseHelper;
import net.minecraftforge.client.event.EntityViewRenderEvent;
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

/**
 * Einstiegspunkt des TRS Clients für Minecraft 1.8.9 (Forge, nur Client).
 * <p>
 * Kommt ohne Mixins/Coremod aus – alles läuft über Forge-Events:
 * HUD über {@link RenderGameOverlayEvent.Post}, Zoom über {@link EntityViewRenderEvent.FOVModifier},
 * CPS und Zoom-Mausrad über {@link MouseEvent}, langsamere Maus über einen eigenen {@link MouseHelper},
 * Fullbright über einen kurzzeitig ersetzten Gamma-Wert nur während der Lightmap-Berechnung.
 */
@Mod(modid = TrsClient.MOD_ID, name = "TRS Client", useMetadata = true, clientSideOnly = true,
		acceptedMinecraftVersions = "[1.8.9]")
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

	public static TrsClient get() {
		return instance;
	}

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		instance = this;
		version = event.getModMetadata().version;
		File file = new File(event.getModConfigurationDirectory(), "trsclient.json");
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
		// 1.8.9-Forge hat kein "Client stoppt"-Ereignis – beim Beenden trotzdem speichern.
		Runtime.getRuntime().addShutdownHook(new Thread(this::saveConfig, "TRS Client config save"));
	}

	@Mod.EventHandler
	public void postInit(FMLPostInitializationEvent event) {
		Minecraft mc = Minecraft.getMinecraft();
		// Nur den Vanilla-MouseHelper ersetzen – hat ein anderer Mod schon einen eigenen, bleibt der.
		boolean slowMouse = mc.mouseHelper != null && mc.mouseHelper.getClass() == MouseHelper.class;
		if (slowMouse) mc.mouseHelper = new ZoomMouseHelper(this);
		else LOGGER.warn("MouseHelper ist bereits ersetzt ({}) – Zoom verlangsamt die Maus nicht", mc.mouseHelper);
		LOGGER.info("TRS Client {} initialisiert – {} Module, Forge-Events registriert, Maus-Hook {}",
				version, modules.registry.all().size(), slowMouse ? "aktiv" : "inaktiv");
	}

	// --- Forge-Events ---

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		while (TrsKeys.menu.isPressed()) {
			if (mc.currentScreen == null) mc.displayGuiScreen(new TrsMenuScreen(null));
		}
		while (TrsKeys.fullbright.isPressed()) {
			modules.fullbright.toggle();
			if (mc.ingameGUI != null) {
				mc.ingameGUI.setRecordPlaying("Fullbright: " + (modules.fullbright.isEnabled() ? "An" : "Aus"), false);
			}
			saveConfig();
		}
	}

	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		Minecraft mc = Minecraft.getMinecraft();
		if (event.phase == TickEvent.Phase.START) {
			handPass = false;
			updateZoom(mc);
			// Fullbright: Gamma nur bis zur Lightmap-Berechnung ersetzen (siehe restoreGamma).
			// In den Video-Einstellungen nicht – dort zeigt/ändert der Regler den echten Wert.
			if (modules.fullbright.isEnabled() && mc.theWorld != null && !(mc.currentScreen instanceof GuiVideoSettings)) {
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

	@SubscribeEvent
	public void onMouse(MouseEvent event) {
		HookStats.mouseEvent++;
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.currentScreen != null) return;
		if (event.buttonstate) {
			long now = System.currentTimeMillis();
			if (event.button == 0) leftClicks.record(now);
			else if (event.button == 1) rightClicks.record(now);
		}
		// Reines Mausrad-Ereignis während des Zooms → Zoomstufe statt Hotbar.
		if (event.button == -1 && event.dwheel != 0 && zoom.isActive() && modules.zoomScroll.get()) {
			HookStats.scroll++;
			zoom.scroll(event.dwheel);
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onOverlay(RenderGameOverlayEvent.Post event) {
		if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
		HookStats.hud++;
		hud.render(event.resolution.getScaledWidth(), event.resolution.getScaledHeight());
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

	public ZoomState zoom() {
		return zoom;
	}

	public void setForceZoom(boolean forceZoom) {
		this.forceZoom = forceZoom;
	}
}
