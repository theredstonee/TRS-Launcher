package dev.theredstonee.trsclient;

import dev.theredstonee.trsclient.core.i18n.I18n;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
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
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.util.MouseHelper;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Einstiegspunkt des TRS Clients für Minecraft 1.7.10 (Forge 10.13.4, nur Client).
 * <p>
 * Kommt ohne Mixins/Coremod aus – alles läuft über Forge-/FML-Events:
 * HUD über {@link RenderGameOverlayEvent.Post}, eigenes Fadenkreuz über {@link RenderGameOverlayEvent.Pre},
 * CPS und Zoom-Mausrad über {@link MouseEvent}, langsamere Maus über einen eigenen {@link MouseHelper},
 * Fullbright über einen kurzzeitig ersetzten Gamma-Wert nur während der Lightmap-Berechnung,
 * Zoom über einen kurzzeitig ersetzten FOV-Wert (1.7.10 hat noch kein FOVModifier-Event).
 * <p>
 * 1.7.10 hat ZWEI Event-Busse: Tick-Events laufen über den FML-Bus
 * ({@code FMLCommonHandler.instance().bus()}), Render-/Eingabe-Events über {@link MinecraftForge#EVENT_BUS}.
 */
@Mod(modid = TrsClient.MOD_ID, name = "TRS Client", version = Tags.VERSION,
		acceptedMinecraftVersions = "[1.7.10]", acceptableRemoteVersions = "*")
public final class TrsClient {
	/** Kontowechsel (Spiel-Thread über den Client-Tick). */
	static dev.theredstonee.trsclient.core.account.LegacySessionSwap accountSwap;
	public static final String MOD_ID = "trsclient";
	public static final Logger LOGGER = LogManager.getLogger("TRS Client");
	/** Gamma für Fullbright (Vanilla-Maximum ist 1.0). */
	private static final float FULLBRIGHT_GAMMA = 16.0F;

	private static TrsClient instance;
	/** Module/Einstellungen, die es in 1.7.10 nicht gibt – im Menü ausgeblendet. */
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
	private ConfigStore config;
	private HudManager hud;
	private String version = Tags.VERSION;
	/** Nur für den Autotest: Zoom ohne Tastendruck erzwingen. */
	private boolean forceZoom;
	/** Fullbright: ersetzter Gamma-Wert, solange {@link #gammaSwapped}. */
	private float savedGamma;
	private boolean gammaSwapped;
	/** Zoom: ersetzter FOV-Wert, solange {@link #fovSwapped}. */
	private float savedFov;
	private boolean fovSwapped;
	private boolean active;

	public static TrsClient get() {
		return instance;
	}

	/** Gibt es das Modul/die Einstellung in dieser Minecraft-Version? */
	public static boolean supported(Object moduleOrSetting) {
		return !UNSUPPORTED.contains(moduleOrSetting);
	}

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		if (event.getSide().isServer()) {
			LOGGER.info("TRS Client ist ein reiner Client-Mod – auf dem Server inaktiv");
			return;
		}
		active = true;
		instance = this;
		if (event.getModMetadata() != null && event.getModMetadata().version != null) version = event.getModMetadata().version;
		// Gibt es erst in neueren Versionen: Treffer-Farbe (Overlay-Textur ab 1.8), Freelook (Kamera-Hooks),
		// TRS-Startbildschirm, Angriffs-Abklingzeit am Fadenkreuz (Kampfsystem ab 1.9).
		UNSUPPORTED.addAll(Arrays.<Object>asList(modules.hitColor, modules.freelook, modules.titleScreen, modules.crosshairAttack,
				modules.menuStyle));
		// Wegpunkte (Lichtsäule, Liste) gibt es hier nicht – Wegpunkte und Todespunkt führt die Karte.
		UNSUPPORTED.add(modules.waypoints);
		// TRS-Online-Funktionen (Abzeichen, TRS-Umhänge, Umhang-Physik, Emotes) sind für 1.7.10 nicht umgesetzt.
		UNSUPPORTED.addAll(Arrays.<Object>asList(modules.trsOnline, modules.capePhysics, modules.emotes, modules.colors));
		// Leistungs-Kategorie (FPS-Boost, Dynamische FPS, Culling, Partikel, Welt-Details) ist hier nicht umgesetzt.
		UNSUPPORTED.addAll(Arrays.<Object>asList(modules.fpsBoost, modules.dynamicFps, modules.entityCulling, modules.particles,
				modules.worldDetails));
		File file = new File(event.getModConfigurationDirectory(), "trsclient.json");
		// Farben des Launchers (config/trsclient/launcher-theme.json) – fehlt sie, gilt das Standard-Thema.
		dev.theredstonee.trsclient.core.ui.Theme.loadFrom(file.getParentFile().toPath());
		dev.theredstonee.trsclient.core.i18n.I18n.init(file.getParentFile().toPath());
		dev.theredstonee.trsclient.core.clips.Clips.init(file.getParentFile().toPath());
		// Konten: Wechsel ohne Neustart (mit TRS Launcher dessen Konten, sonst eigene Anmeldung je Instanz).
		accountSwap = new dev.theredstonee.trsclient.core.account.LegacySessionSwap(() -> Minecraft.getMinecraft(),
				net.minecraft.util.Session.class, () -> {
					net.minecraft.util.Session s = Minecraft.getMinecraft().getSession();
					return s == null ? null : new dev.theredstonee.trsclient.core.account.SessionData(s.getPlayerID(), s.getUsername(), s.getToken(), null);
				}, () -> Minecraft.getMinecraft().theWorld != null, file.getParentFile().toPath(), "TRS-Client/" + version + " (Minecraft 1.7.10; forge)", message -> LOGGER.info(message));
		dev.theredstonee.trsclient.core.account.AccountManager.init(accountSwap);
		config = new ConfigStore(file.toPath());
		ConfigStore.Status status = config.load(modules.registry);
		if (status == ConfigStore.Status.RECOVERED) {
			LOGGER.warn("Config war beschädigt – Standardwerte geladen, Sicherung: {}", config.brokenFile());
		}
		LOGGER.info("Config {} ({})", status, config.file());
	}

	@Mod.EventHandler
	public void init(FMLInitializationEvent event) {
		if (!active) return;
		TrsKeys.register();
		// Die Zoom-Taste ist eine Vanilla-Belegung – im TRS-Menü ändert sie dieselbe Belegung.
		modules.zoomKey.link(TrsKeys.link(TrsKeys.zoom));
		modules.worldMapKey.link(TrsKeys.link(TrsKeys.worldMap));
		// Karten (Minimap + Weltkarte): Kartenspeicher unter config/trsclient/maps.
		dev.theredstonee.trsclient.core.map.MapEngine.init(modules, dev.theredstonee.trsclient.core.i18n.I18n.configDir());
		hud = new HudManager(modules);
		MinecraftForge.EVENT_BUS.register(this);
		FMLCommonHandler.instance().bus().register(new TickHandler());
		AutoTest.installIfRequested();
		// 1.7.10-Forge hat kein "Client stoppt"-Ereignis – beim Beenden trotzdem speichern.
		Runtime.getRuntime().addShutdownHook(new Thread(this::saveConfig, "TRS Client config save"));
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (dev.theredstonee.trsclient.core.map.MapEngine.get() != null) dev.theredstonee.trsclient.core.map.MapEngine.get().shutdown();
		}, "TRS Client map save"));
	}

	@Mod.EventHandler
	public void postInit(FMLPostInitializationEvent event) {
		if (!active) return;
		Minecraft mc = Minecraft.getMinecraft();
		// Nur den Vanilla-MouseHelper ersetzen – hat ein anderer Mod schon einen eigenen, bleibt der.
		boolean slowMouse = mc.mouseHelper != null && mc.mouseHelper.getClass() == MouseHelper.class;
		if (slowMouse) mc.mouseHelper = new ZoomMouseHelper(this);
		else LOGGER.warn("MouseHelper ist bereits ersetzt ({}) – Zoom verlangsamt die Maus nicht", mc.mouseHelper);
		LOGGER.info("TRS Client {} initialisiert – {} Module, Forge-Events registriert, Maus-Hook {}",
				version, modules.registry.all().size(), slowMouse ? "aktiv" : "inaktiv");
	}

	// --- FML-Bus (Ticks) ---

	/** Tick-Events kommen in 1.7.10 nur über den FML-Bus. */
	public final class TickHandler {
		/** Meldungen der Clips in der Aktionsleiste. */
		private final dev.theredstonee.trsclient.core.clips.Clips.ActionBar CLIP_MESSAGES = text -> {
			Minecraft minecraft = Minecraft.getMinecraft();
			if (minecraft.ingameGUI != null) minecraft.ingameGUI.func_110326_a(text, false);
		};

		@SubscribeEvent
		public void onClientTick(TickEvent.ClientTickEvent event) {
			Minecraft mc = Minecraft.getMinecraft();
			if (accountSwap != null) accountSwap.drain();
			if (event.phase == TickEvent.Phase.START) {
				pvp.countPresses(mc);
				return;
			}
			migrateKeys(mc);
			while (TrsKeys.hudProfile.isPressed()) {
				String name = modules.profiles.cycle();
				if (mc.ingameGUI != null) mc.ingameGUI.func_110326_a(I18n.tr("toast.hudProfile", name), false);
				saveConfig();
			}
			while (TrsKeys.menu.isPressed()) {
				if (mc.currentScreen == null) mc.displayGuiScreen(new TrsMenuScreen(null));
			}
			// Weltkarte (M)
			while (TrsKeys.worldMap.isPressed()) {
				if (mc.currentScreen == null && mc.thePlayer != null && modules.worldMap.isEnabled()) {
					dev.theredstonee.trsclient.screen.WorldMapScreen screen = dev.theredstonee.trsclient.screen.WorldMapScreen.create();
					if (screen != null) mc.displayGuiScreen(screen);
				}
			}
			if (modules.keyDefaults.needsWorldMapKeyCheck() && mc.gameSettings != null) {
				modules.keyDefaults.markWorldMapKeyChecked();
				if (TrsKeys.resolveWorldMapConflict()) LOGGER.info("Weltkarten-Taste M war doppelt belegt – freigegeben");
				saveConfig();
			}
			if (hud != null) hud.tick();
			// Garderobe (Taste standardmäßig unbelegt)
			while (TrsKeys.wardrobe.isPressed()) {
				if (mc.currentScreen == null && dev.theredstonee.trsclient.screen.WardrobeScreen.available()) {
					mc.displayGuiScreen(dev.theredstonee.trsclient.screen.WardrobeScreen.create(null));
				}
			}
			while (TrsKeys.redstoneOverlay.isPressed()) {
				modules.redstoneOverlay.toggle();
				if (mc.ingameGUI != null) {
					mc.ingameGUI.func_110326_a(I18n.tr("toast.redstoneOverlay", modules.redstoneOverlay.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled")), false);
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
					mc.ingameGUI.func_110326_a(I18n.tr("toast.fullbright", modules.fullbright.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled")), false);
				}
				saveConfig();
			}
		}

		@SubscribeEvent
		public void onRenderTick(TickEvent.RenderTickEvent event) {
			Minecraft mc = Minecraft.getMinecraft();
			if (event.phase == TickEvent.Phase.START) {
				updateZoom(mc);
				// Zoom: FOV-Option nur für diesen Frame ersetzen; nur ohne offenes Menü (dort zeigt/ändert
				// der Regler den echten Wert). Die Hand nutzt feste 70° und zoomt nicht mit.
				double factor = zoom.factor();
				if (factor != 1.0 && mc.theWorld != null && mc.currentScreen == null) {
					savedFov = mc.gameSettings.fovSetting;
					mc.gameSettings.fovSetting = (float) (savedFov / factor);
					fovSwapped = true;
					HookStats.fov++;
				}
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
				restoreFov(mc);
			}
		}
	}

	// --- Forge-Bus ---

	/**
	 * Lightmap ist berechnet (erstes Ereignis nach {@code updateLightmap} in {@code renderWorld}) →
	 * echten Gamma-Wert sofort zurück. So sieht nichts anderes (Menüs, Speichern der Optionen) je 16.0.
	 */
	/** Fair-Play-Codes der Karten-Mods in Server-Nachrichten. */
	@SubscribeEvent
	public void onChat(net.minecraftforge.client.event.ClientChatReceivedEvent event) {
		dev.theredstonee.trsclient.core.map.MapEngine maps = dev.theredstonee.trsclient.core.map.MapEngine.get();
		if (maps != null && event.message != null) maps.onServerText(event.message.getFormattedText());
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onFogColors(EntityViewRenderEvent.FogColors event) {
		restoreGamma(Minecraft.getMinecraft());
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
	public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
		if (event.type != RenderGameOverlayEvent.ElementType.CROSSHAIRS || !hud.crosshair().replacesVanilla()) return;
		HookStats.crosshair++;
		event.setCanceled(true);
		hud.crosshair().drawInGame(event.resolution.getScaledWidth(), event.resolution.getScaledHeight());
	}

	@SubscribeEvent
	public void onOverlay(RenderGameOverlayEvent.Post event) {
		if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
		HookStats.hud++;
		hud.render(event.resolution.getScaledWidth(), event.resolution.getScaledHeight(), event.partialTicks);
	}

	/** Direkt vor {@code EntityPlayerSP.onLivingUpdate}: umgeschaltete Sprint-/Schleich-Taste halten. */
	@SubscribeEvent
	public void onLivingUpdate(LivingEvent.LivingUpdateEvent event) {
		Minecraft mc = Minecraft.getMinecraft();
		if (event.entityLiving != mc.thePlayer || mc.thePlayer == null) return;
		HookStats.toggle++;
		pvp.apply(mc);
	}

	// --- Logik ---

	private void updateZoom(Minecraft mc) {
		boolean on = modules.zoom.isEnabled()
				&& (forceZoom || (TrsKeys.zoom.getIsKeyPressed() && mc.currentScreen == null));
		// Kein Fernrohr vor 1.17; die filmische Kamera gilt auf Wunsch nur, solange gezoomt wird.
		zoom.frame(on, false, modules.zoomFactor.get(), modules.zoomSmooth.get(), System.nanoTime());
		boolean smooth = zoomCinematic.update(mc.gameSettings.smoothCamera, zoom.isActive() && modules.zoomCinematic.get());
		if (smooth != mc.gameSettings.smoothCamera) mc.gameSettings.smoothCamera = smooth;
	}

	private void restoreGamma(Minecraft mc) {
		if (!gammaSwapped) return;
		gammaSwapped = false;
		mc.gameSettings.gammaSetting = savedGamma;
	}

	private void restoreFov(Minecraft mc) {
		if (!fovSwapped) return;
		fovSwapped = false;
		mc.gameSettings.fovSetting = savedFov;
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
			config.saveLater(modules.registry);
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
