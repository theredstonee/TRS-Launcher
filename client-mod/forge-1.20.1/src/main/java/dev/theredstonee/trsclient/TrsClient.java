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
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * TRS Client für Forge 1.20.1 (nur Client). Fast alles läuft über Forge-Events;
 * Mixins nur für die Lightmap-Gamma (Fullbright) und die langsamere Maus beim Zoomen.
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
	/** Nur für den Autotest: Zoom ohne Tastendruck erzwingen. */
	private boolean forceZoom;

	public static TrsClient get() {
		return instance;
	}

	static void init() {
		instance = new TrsClient();
	}

	// Die statischen *Context.get() sind ab Forge 47.3 veraltet (Konstruktor-Injektion), funktionieren aber
	// in allen 47.x-Versionen – Modpacks laufen oft noch auf älteren 47er-Builds.
	@SuppressWarnings("removal")
	private TrsClient() {
		instance = this;
		config = new ConfigStore(FMLPaths.CONFIGDIR.get().resolve("trsclient.json"));
		ConfigStore.Status status = config.load(modules.registry);
		if (status == ConfigStore.Status.RECOVERED) {
			LOGGER.warn("Config war beschädigt – Standardwerte geladen, Sicherung: {}", config.brokenFile());
		}
		hud = new HudManager(modules);

		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		modBus.addListener((RegisterKeyMappingsEvent e) -> TrsKeys.register(e));
		modBus.addListener((RegisterGuiOverlaysEvent e) ->
				e.registerAboveAll("hud", (gui, g, partialTick, width, height) -> hud.render(g)));

		IEventBus bus = MinecraftForge.EVENT_BUS;
		bus.addListener((TickEvent.ClientTickEvent e) -> {
			if (e.phase == TickEvent.Phase.END) onTick(Minecraft.getInstance());
		});
		bus.addListener(this::onComputeFov);
		bus.addListener(this::onMouseButton);
		bus.addListener(this::onMouseScroll);
		bus.addListener((GameShuttingDownEvent e) -> saveConfig());

		// "Config" in der Mod-Liste öffnet das TRS-Menü.
		ModLoadingContext ctx = ModLoadingContext.get();
		ctx.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
				() -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new TrsMenuScreen(parent)));
		AutoTest.installIfRequested();

		LOGGER.info("TRS Client {} initialisiert (Forge) – {} Module, Config {} ({})",
				ctx.getActiveContainer().getModInfo().getVersion(), modules.registry.all().size(), status, config.file());
	}

	private void onTick(Minecraft mc) {
		while (TrsKeys.menu.consumeClick()) {
			if (mc.screen == null) mc.setScreen(new TrsMenuScreen(null));
		}
		while (TrsKeys.fullbright.consumeClick()) {
			modules.fullbright.toggle();
			mc.gui.setOverlayMessage(Component.literal("Fullbright: " + (modules.fullbright.isEnabled() ? "An" : "Aus")), false);
			saveConfig();
		}
	}

	/** Speichert die Einstellungen (Fehler nur loggen). */
	public void saveConfig() {
		try {
			config.save(modules.registry);
		} catch (IOException e) {
			LOGGER.error("Config konnte nicht gespeichert werden: {}", config.file(), e);
		}
	}

	// --- Event-Hooks ---

	/** Zoom: teilt das Welt-Sichtfeld durch den aktuellen Faktor (Hand bleibt unverändert). */
	private void onComputeFov(ViewportEvent.ComputeFov e) {
		HookStats.fov++;
		if (!e.usedConfiguredFov()) return;
		double factor = updateZoom();
		if (factor != 1.0) e.setFOV(e.getFOV() / factor);
	}

	/** Mausklick im Spiel (nicht in Menüs) → CPS-Zähler. */
	private void onMouseButton(InputEvent.MouseButton.Pre e) {
		HookStats.press++;
		if (e.getAction() != GLFW.GLFW_PRESS || Minecraft.getInstance().screen != null) return;
		long now = System.currentTimeMillis();
		if (e.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) leftClicks.record(now);
		else if (e.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) rightClicks.record(now);
	}

	/** Mausrad während des Zooms ändert die Zoomstufe; das Ereignis wird verbraucht (Hotbar bleibt). */
	private void onMouseScroll(InputEvent.MouseScrollingEvent e) {
		HookStats.scroll++;
		double amount = e.getScrollDelta();
		if (amount == 0 || !zoom.isActive() || !modules.zoomScroll.get() || Minecraft.getInstance().screen != null) return;
		zoom.scroll(amount);
		e.setCanceled(true);
	}

	/** Pro Frame aus dem FOV-Event: aktualisiert den Zoom und liefert den FOV-Divisor. */
	private double updateZoom() {
		Minecraft mc = Minecraft.getInstance();
		boolean active = modules.zoom.isEnabled()
				&& (forceZoom || (TrsKeys.zoom.isDown() && mc.screen == null));
		zoom.update(active, modules.zoomFactor.get(), modules.zoomSmooth.get(), System.nanoTime());
		return zoom.factor();
	}

	// --- Hooks aus den Mixins ---

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

	public void setForceZoom(boolean forceZoom) {
		this.forceZoom = forceZoom;
	}
}
