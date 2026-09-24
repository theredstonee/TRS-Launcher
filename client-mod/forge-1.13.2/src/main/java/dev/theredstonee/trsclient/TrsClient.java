package dev.theredstonee.trsclient;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.input.ClickCounter;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.zoom.ZoomState;
import dev.theredstonee.trsclient.dev.AutoTest;
import dev.theredstonee.trsclient.dev.HookStats;
import dev.theredstonee.trsclient.feature.PvpFeatures;
import dev.theredstonee.trsclient.hud.HudManager;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Client-Logik des TRS Clients für Minecraft 1.13.2 (Forge 25.0.x, ModLauncher/FML2).
 * <p>
 * Ohne Mixins/Coremods – alles über Forge-Events auf {@link MinecraftForge#EVENT_BUS}:
 * HUD über {@link RenderGameOverlayEvent.Post}, eigenes Fadenkreuz über {@link RenderGameOverlayEvent.Pre},
 * Zoom über {@link EntityViewRenderEvent.FOVModifier} (Hand ausgenommen: sie fragt das Sichtfeld direkt nach
 * {@link RenderWorldLastEvent} ab), CPS über {@link InputEvent.MouseInputEvent}, Fullbright über einen kurzzeitig
 * ersetzten Gamma-Wert nur während der Lightmap-Berechnung.
 * <p>
 * Was Forge 1.13.2 NICHT bietet und wie es hier gelöst ist:
 * <ul>
 *   <li>kein Mausrad-Event im Spiel → das Mausrad verschiebt den Hotbar-Slot; während des Zooms wird die
 *       Verschiebung erkannt, rückgängig gemacht und als Zoomstufe verwendet;</li>
 *   <li>kein Maus-Drehungs-Event → langsamere Maus, indem die Drehung aus {@code MouseHelper.updatePlayerLook}
 *       (läuft zwischen Client-Tick und Render-Tick) zu Beginn des Render-Ticks durch den Zoom-Faktor geteilt wird.</li>
 * </ul>
 */
public final class TrsClient {
	public static final String MOD_ID = "trsclient";
	public static final Logger LOGGER = LogManager.getLogger("TRS Client");
	/** Gamma für Fullbright (Vanilla-Maximum ist 1.0). */
	private static final double FULLBRIGHT_GAMMA = 16.0;

	private static TrsClient instance;
	/** Module/Einstellungen, die es in 1.13.2 nicht gibt – im Menü ausgeblendet. */
	private static final Set<Object> UNSUPPORTED = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

	private final TrsModules modules = new TrsModules();
	private final ClickCounter leftClicks = new ClickCounter();
	private final ClickCounter rightClicks = new ClickCounter();
	private final ZoomState zoom = new ZoomState();
	/** Filmische Kamera nur während des Zooms (stellt den Wert des Spielers danach wieder her). */
	private final dev.theredstonee.trsclient.core.util.FlagOverride zoomCinematic =
			new dev.theredstonee.trsclient.core.util.FlagOverride();
	private final PvpFeatures pvp = new PvpFeatures(modules);
	/** Redstone-Werkzeuge: Signalstärke, Takt-Messer, Signal-Overlay (Logik in core.redstone). */
	private final dev.theredstonee.trsclient.core.redstone.RedstoneTools redstone =
			new dev.theredstonee.trsclient.core.redstone.RedstoneTools(modules);
	private long redstoneErrorLogged;
	/** Zuletzt benutztes Welt-Sichtfeld (inkl. Zoom) – für die Projektion des Signal-Overlays. */
	private double worldFov = 70;
	private ConfigStore config;
	private HudManager hud;
	private String version = "?";
	/** Nur für den Autotest: Zoom ohne Tastendruck erzwingen. */
	private boolean forceZoom;
	/** Der nächste FOV-Aufruf gehört zur Hand (nicht zoomen). */
	private boolean handPass;
	/** Fullbright: ersetzter Gamma-Wert, solange {@link #gammaSwapped}. */
	private double savedGamma;
	private boolean gammaSwapped;
	/** Blickwinkel vor {@code updatePlayerLook} (für die langsamere Maus). */
	private float refYaw;
	private float refPitch;
	private boolean refValid;
	/** Hotbar-Slot vor einem möglichen Mausrad-Ereignis (Zoom-Mausrad). */
	private int lastSlot = -1;

	private TrsClient() {
	}

	public static TrsClient get() {
		return instance;
	}

	/** Gibt es das Modul/die Einstellung in dieser Minecraft-Version? */
	public static boolean supported(Object moduleOrSetting) {
		return !UNSUPPORTED.contains(moduleOrSetting);
	}

	/** Aus {@link TrsClientMod} (nur auf dem Client). */
	static void bootstrap() {
		TrsClient client = new TrsClient();
		instance = client;
		// Gibt es erst in neueren Versionen bzw. braucht Mixins: Treffer-Farbe (Overlay-Textur ab 1.15),
		// Freelook (Kamera-Hooks) und der TRS-Startbildschirm.
		UNSUPPORTED.addAll(Arrays.<Object>asList(client.modules.hitColor, client.modules.freelook, client.modules.titleScreen));
		// TRS-Online-Funktionen (Abzeichen, TRS-Umhänge, Umhang-Physik, Emotes) sind für 1.13.2 nicht umgesetzt.
		UNSUPPORTED.addAll(Arrays.<Object>asList(client.modules.trsOnline, client.modules.capePhysics, client.modules.emotes,
				client.modules.colors));
		// Leistungs-Kategorie (FPS-Boost, Dynamische FPS, Culling, Partikel, Welt-Details) ist hier nicht umgesetzt.
		UNSUPPORTED.addAll(Arrays.<Object>asList(client.modules.fpsBoost, client.modules.dynamicFps, client.modules.entityCulling, client.modules.particles,
				client.modules.worldDetails));
		client.version = ModList.get().getModContainerById(MOD_ID)
				.map(c -> c.getModInfo().getVersion().toString()).orElse("?");
		// Farben des Launchers (config/trsclient/launcher-theme.json) – fehlt sie, gilt das Standard-Thema.
		dev.theredstonee.trsclient.core.ui.Theme.loadFrom(FMLPaths.CONFIGDIR.get());
		dev.theredstonee.trsclient.core.i18n.I18n.init(FMLPaths.CONFIGDIR.get());
		dev.theredstonee.trsclient.core.clips.Clips.init(FMLPaths.CONFIGDIR.get());
		client.config = new ConfigStore(FMLPaths.CONFIGDIR.get().resolve("trsclient.json"));
		ConfigStore.Status status = client.config.load(client.modules.registry);
		if (status == ConfigStore.Status.RECOVERED) {
			LOGGER.warn("Config war beschädigt – Standardwerte geladen, Sicherung: {}", client.config.brokenFile());
		}
		LOGGER.info("Config {} ({})", status, client.config.file());
		FMLJavaModLoadingContext.get().getModEventBus().addListener(client::clientSetup);
	}

	private void clientSetup(FMLClientSetupEvent event) {
		TrsKeys.register();
		// Die Zoom-Taste ist eine Vanilla-Belegung – im TRS-Menü ändert sie dieselbe Belegung.
		modules.zoomKey.link(TrsKeys.link(TrsKeys.zoom));
		hud = new HudManager(modules);
		MinecraftForge.EVENT_BUS.register(this);
		AutoTest.installIfRequested();
		// Beim Beenden speichern (Forge 1.13.2 hat kein zuverlässiges "Client stoppt"-Ereignis).
		Runtime.getRuntime().addShutdownHook(new Thread(this::saveConfig, "TRS Client config save"));
		LOGGER.info("TRS Client {} initialisiert – {} Module, Forge-Events registriert", version, modules.registry.all().size());
	}

	// --- Ticks ---

	/** Meldungen der Clips in der Aktionsleiste. */
	private static final dev.theredstonee.trsclient.core.clips.Clips.ActionBar CLIP_MESSAGES = text -> {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.ingameGUI != null) minecraft.ingameGUI.setOverlayMessage(text, false);
	};

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		Minecraft mc = Minecraft.getInstance();
		if (event.phase == TickEvent.Phase.START) {
			// Vor der Tick-Verarbeitung (und vor dem Senden des Hotbar-Slots an den Server).
			checkZoomScroll(mc);
			pvp.countPresses(mc);
			return;
		}
		migrateKeys(mc);
		while (TrsKeys.hudProfile.isPressed()) {
			String name = modules.profiles.cycle();
			if (mc.ingameGUI != null) mc.ingameGUI.setOverlayMessage(I18n.tr("toast.hudProfile", name), false);
			saveConfig();
		}
		while (TrsKeys.menu.isPressed()) {
			if (mc.currentScreen == null) mc.displayGuiScreen(new TrsMenuScreen(null));
		}
		while (TrsKeys.redstoneOverlay.isPressed()) {
			modules.redstoneOverlay.toggle();
			if (mc.ingameGUI != null) {
				mc.ingameGUI.setOverlayMessage(I18n.tr("toast.redstoneOverlay", modules.redstoneOverlay.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled")), false);
			}
			saveConfig();
		}
		tickRedstone();
		// Clips & Aufnahme: aufgenommen wird im Launcher, hier nur die Tasten melden.
		while (TrsKeys.saveClip.isPressed()) dev.theredstonee.trsclient.core.clips.Clips.get().saveClip();
		while (TrsKeys.toggleRecording.isPressed()) dev.theredstonee.trsclient.core.clips.Clips.get().toggleRecording();
		dev.theredstonee.trsclient.core.clips.Clips.get().tick(modules.clips.isEnabled(), CLIP_MESSAGES);
		while (TrsKeys.fullbright.isPressed()) {
			modules.fullbright.toggle();
			if (mc.ingameGUI != null) {
				mc.ingameGUI.setOverlayMessage(I18n.tr("toast.fullbright", modules.fullbright.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled")), false);
			}
			saveConfig();
		}
		// runTick kann den Blick setzen (Teleport, Reiten) – Referenz für die langsame Maus danach neu setzen.
		captureLook(mc);
	}

	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		Minecraft mc = Minecraft.getInstance();
		if (event.phase == TickEvent.Phase.START) {
			handPass = false;
			checkZoomScroll(mc);
			updateZoom(mc);
			slowMouse(mc);
			// Fullbright: Gamma nur bis zur Lightmap-Berechnung ersetzen (siehe restoreGamma).
			// In den Video-Einstellungen nicht – dort zeigt/ändert der Regler den echten Wert.
			if (modules.fullbright.isEnabled() && mc.world != null && !(mc.currentScreen instanceof GuiVideoSettings)) {
				savedGamma = mc.gameSettings.gammaSetting;
				mc.gameSettings.gammaSetting = FULLBRIGHT_GAMMA;
				gammaSwapped = true;
				HookStats.lightmap++;
			}
		} else {
			restoreGamma(mc);
			captureLook(mc);
		}
	}

	// --- Rendering ---

	/**
	 * Lightmap ist berechnet ({@code LightTexture.updateLightmap} läuft zu Beginn von {@code renderWorld},
	 * danach kommt als erstes {@code updateFogColor}) → echten Gamma-Wert sofort zurück.
	 */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onFogColors(EntityViewRenderEvent.FogColors event) {
		restoreGamma(Minecraft.getInstance());
	}

	@SubscribeEvent
	public void onFov(EntityViewRenderEvent.FOVModifier event) {
		HookStats.fov++;
		restoreGamma(Minecraft.getInstance());
		if (handPass) {
			// Hand nicht mitzoomen.
			handPass = false;
			return;
		}
		double factor = zoom.factor();
		if (factor != 1.0) event.setFOV(event.getFOV() / factor);
		double fov = event.getFOV();
		if (fov > 1 && fov < 180) worldFov = fov;
	}

	/** Direkt danach zeichnet {@code GameRenderer.renderHand} die Hand und fragt dafür das Sichtfeld ab. */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onRenderLast(RenderWorldLastEvent event) {
		handPass = true;
	}

	@SubscribeEvent
	public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
		if (event.getType() != RenderGameOverlayEvent.ElementType.CROSSHAIRS || !hud.crosshair().replacesVanilla()) return;
		HookStats.crosshair++;
		event.setCanceled(true);
		Minecraft mc = Minecraft.getInstance();
		hud.crosshair().drawInGame(mc.mainWindow.getScaledWidth(), mc.mainWindow.getScaledHeight());
	}

	@SubscribeEvent
	public void onOverlay(RenderGameOverlayEvent.Post event) {
		if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
		HookStats.hud++;
		Minecraft mc = Minecraft.getInstance();
		hud.render(mc.mainWindow.getScaledWidth(), mc.mainWindow.getScaledHeight(), event.getPartialTicks());
	}

	// --- Eingabe ---

	@SubscribeEvent
	public void onMouse(InputEvent.MouseInputEvent event) {
		HookStats.mouseEvent++;
		Minecraft mc = Minecraft.getInstance();
		if (mc.currentScreen != null || event.getAction() != GLFW.GLFW_PRESS) return;
		long now = System.currentTimeMillis();
		if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) leftClicks.record(now);
		else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) rightClicks.record(now);
	}

	/** Direkt vor {@code EntityPlayerSP.livingTick}: umgeschaltete Sprint-/Schleich-Taste halten. */
	@SubscribeEvent
	public void onLivingUpdate(LivingEvent.LivingUpdateEvent event) {
		Minecraft mc = Minecraft.getInstance();
		if (event.getEntityLiving() != mc.player || mc.player == null) return;
		HookStats.toggle++;
		pvp.apply(mc);
	}

	// --- Logik ---

	private void updateZoom(Minecraft mc) {
		boolean on = modules.zoom.isEnabled()
				&& (forceZoom || (TrsKeys.zoom.isKeyDown() && mc.currentScreen == null));
		// Kein Fernrohr vor 1.17; die filmische Kamera gilt auf Wunsch nur, solange gezoomt wird.
		zoom.frame(on, false, modules.zoomFactor.get(), modules.zoomSmooth.get(), System.nanoTime());
		boolean smooth = zoomCinematic.update(mc.gameSettings.smoothCamera, zoom.isActive() && modules.zoomCinematic.get());
		if (smooth != mc.gameSettings.smoothCamera) mc.gameSettings.smoothCamera = smooth;
	}

	/**
	 * Zoom-Mausrad: Vanilla verschiebt beim Scrollen sofort den Hotbar-Slot (Forge 1.13.2 hat kein Event dafür).
	 * Während des Zooms wird die Verschiebung zurückgenommen und als Zoomstufe verwendet (hoch = näher).
	 */
	private void checkZoomScroll(Minecraft mc) {
		EntityPlayerSP p = mc.player;
		if (p == null) {
			lastSlot = -1;
			return;
		}
		int slot = p.inventory.currentItem;
		if (lastSlot >= 0 && slot != lastSlot && zoom.isActive() && modules.zoomScroll.get() && mc.currentScreen == null) {
			int diff = lastSlot - slot;
			if (diff > 4) diff -= 9;
			if (diff < -4) diff += 9;
			HookStats.scroll++;
			for (int i = 0; i < Math.abs(diff); i++) zoom.scroll(Math.signum(diff));
			p.inventory.currentItem = lastSlot;
			return;
		}
		lastSlot = slot;
	}

	/** Blickwinkel nach allem merken, was nicht die Maus ist. */
	private void captureLook(Minecraft mc) {
		EntityPlayerSP p = mc.player;
		refValid = p != null;
		if (p == null) return;
		refYaw = p.rotationYaw;
		refPitch = p.rotationPitch;
	}

	/** Mausdrehung dieses Frames (seit {@link #captureLook}) durch den Zoom-Faktor teilen. */
	private void slowMouse(Minecraft mc) {
		EntityPlayerSP p = mc.player;
		double divisor = mouseDivisor();
		if (p == null || !refValid || divisor == 1.0 || mc.currentScreen != null) return;
		float dYaw = p.rotationYaw - refYaw;
		float dPitch = p.rotationPitch - refPitch;
		if (dYaw == 0 && dPitch == 0) return;
		HookStats.mouse++;
		float yaw = refYaw + (float) (dYaw / divisor);
		float pitch = Math.max(-90.0F, Math.min(90.0F, refPitch + (float) (dPitch / divisor)));
		// Entity.rotateTowards verschiebt prevRotation mit – gleiche Korrektur, damit nichts ruckelt.
		p.prevRotationYaw += yaw - p.rotationYaw;
		p.prevRotationPitch += pitch - p.rotationPitch;
		p.rotationYaw = yaw;
		p.rotationPitch = pitch;
	}

	private void restoreGamma(Minecraft mc) {
		if (!gammaSwapped) return;
		gammaSwapped = false;
		mc.gameSettings.gammaSetting = savedGamma;
	}

	/** Maus-Divisor während des Zooms (1 = unverändert). */
	public double mouseDivisor() {
		return zoom.mouseDivisor(modules.zoom.isEnabled() && modules.zoomSlowMouse.get());
	}

	/** Speichert die Einstellungen (Fehler nur loggen). */
	/**
	 * Einmalige Umstellung alter Standard-Tasten: Zoom lag auf C, was ab Minecraft 1.12 mit
	 * "Schnellleiste speichern" kollidiert. Selbst belegte Tasten bleiben unangetastet.
	 */
	/** Redstone-Werkzeuge; ein Fehler darf nie das Spiel stören (höchstens einmal je Minute geloggt). */
	private void tickRedstone() {
		try {
			dev.theredstonee.trsclient.compat.RedstoneProbe.tick(redstone);
		} catch (RuntimeException e) {
			redstone.reset();
			long now = System.currentTimeMillis();
			if (now - redstoneErrorLogged > 60000) {
				redstoneErrorLogged = now;
				LOGGER.warn("Redstone-Werkzeuge: " + e);
			}
		}
	}

	private void migrateKeys(Minecraft mc) {
		if (!modules.keyDefaults.needsZoomKeyMigration() || mc.gameSettings == null) return;
		if (TrsKeys.migrateZoomKey()) {
			mc.gameSettings.saveOptions();
			LOGGER.info("Zoom-Taste von C auf V umgestellt");
		}
		modules.keyDefaults.markMigrated();
		saveConfig();
	}

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

	public dev.theredstonee.trsclient.core.redstone.RedstoneTools redstone() {
		return redstone;
	}

	/** Zuletzt benutztes Welt-Sichtfeld (Grad). */
	public double worldFov() {
		return worldFov;
	}

	public PvpFeatures pvp() {
		return pvp;
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
