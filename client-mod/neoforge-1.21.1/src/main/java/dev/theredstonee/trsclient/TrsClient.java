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
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Einstiegspunkt des TRS Clients für NeoForge 1.21.1 (nur Client).
 * Alles läuft über NeoForge-Events; einziger Mixin: Gamma der Lightmap (Fullbright).
 */
@Mod(value = TrsClient.MOD_ID, dist = Dist.CLIENT)
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

	public TrsClient(IEventBus modBus, ModContainer container) {
		instance = this;
		config = new ConfigStore(FMLPaths.CONFIGDIR.get().resolve("trsclient.json"));
		ConfigStore.Status status = config.load(modules.registry);
		if (status == ConfigStore.Status.RECOVERED) {
			LOGGER.warn("Config war beschädigt – Standardwerte geladen, Sicherung: {}", config.brokenFile());
		}
		hud = new HudManager(modules);

		modBus.addListener(RegisterKeyMappingsEvent.class, TrsKeys::register);
		modBus.addListener(RegisterGuiLayersEvent.class, e ->
				e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MOD_ID, "hud"), hud::render));

		IEventBus bus = NeoForge.EVENT_BUS;
		bus.addListener(ClientTickEvent.Post.class, e -> onTick(Minecraft.getInstance()));
		bus.addListener(ViewportEvent.ComputeFov.class, this::onComputeFov);
		bus.addListener(InputEvent.MouseButton.Pre.class, this::onMouseButton);
		bus.addListener(InputEvent.MouseScrollingEvent.class, this::onMouseScroll);
		bus.addListener(CalculatePlayerTurnEvent.class, this::onPlayerTurn);
		bus.addListener(GameShuttingDownEvent.class, e -> saveConfig());

		// "Konfigurieren" in der Mod-Liste öffnet das TRS-Menü.
		container.registerExtensionPoint(IConfigScreenFactory.class, (c, parent) -> new TrsMenuScreen(parent));
		AutoTest.installIfRequested();

		LOGGER.info("TRS Client {} initialisiert (NeoForge) – {} Module, Config {} ({})",
				container.getModInfo().getVersion(), modules.registry.all().size(), status, config.file());
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
		double amount = e.getScrollDeltaY();
		if (amount == 0 || !zoom.isActive() || !modules.zoomScroll.get() || Minecraft.getInstance().screen != null) return;
		zoom.scroll(amount);
		e.setCanceled(true);
	}

	/** Langsamere Maus beim Zoomen: Empfindlichkeit so senken, dass die Drehung durch den Faktor geteilt wird. */
	private void onPlayerTurn(CalculatePlayerTurnEvent e) {
		HookStats.turn++;
		if (!modules.zoomSlowMouse.get()) return;
		double divisor = zoom.factor();
		if (divisor > 1.0) e.setMouseSensitivity(MouseSensitivity.divided(e.getMouseSensitivity(), divisor));
	}

	/** Pro Frame aus dem FOV-Event: aktualisiert den Zoom und liefert den FOV-Divisor. */
	private double updateZoom() {
		Minecraft mc = Minecraft.getInstance();
		boolean active = modules.zoom.isEnabled()
				&& (forceZoom || (TrsKeys.zoom.isDown() && mc.screen == null));
		zoom.update(active, modules.zoomFactor.get(), modules.zoomSmooth.get(), System.nanoTime());
		return zoom.factor();
	}

	/** Aus dem Lightmap-Mixin. */
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
