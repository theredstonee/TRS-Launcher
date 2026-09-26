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
import dev.theredstonee.trsclient.dev.HookStats;
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
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MouseHelper;
import dev.theredstonee.trsclient.compat.BlockOutline;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import org.lwjgl.input.Mouse;
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
	/** Kontowechsel (Spiel-Thread über den Client-Tick). */
	static dev.theredstonee.trsclient.core.account.LegacySessionSwap accountSwap;
	public static final String MOD_ID = "trsclient";
	public static final Logger LOGGER = LogManager.getLogger("TRS Client");
	/** Gamma für Fullbright (Vanilla-Maximum ist 1.0). */
	private static final float FULLBRIGHT_GAMMA = 16.0F;

	private static TrsClient instance;

	private final TrsModules modules = new TrsModules();
	private final ClickCounter leftClicks = new ClickCounter();
	private final ClickCounter rightClicks = new ClickCounter();
	private final ZoomState zoom = new ZoomState();
	/** Filmische Kamera nur während des Zooms (stellt den Wert des Spielers danach wieder her). */
	private final dev.theredstonee.trsclient.core.util.FlagOverride zoomCinematic =
			new dev.theredstonee.trsclient.core.util.FlagOverride();
	private final PvpFeatures pvp = new PvpFeatures(modules);
	private final ChatFeatures chat = new ChatFeatures(modules);
	/** Redstone-Werkzeuge: Signalstärke, Takt-Messer, Signal-Overlay (Logik in core.redstone). */
	private final dev.theredstonee.trsclient.core.redstone.RedstoneTools redstone =
			new dev.theredstonee.trsclient.core.redstone.RedstoneTools(modules);
	private long redstoneErrorLogged;
	/** Tastendruck-Erkennung für die Modul-Tasten (Wegpunkte, Text-Hotkeys). */
	private final dev.theredstonee.trsclient.core.input.KeyPresses moduleKeys =
			new dev.theredstonee.trsclient.core.input.KeyPresses(dev.theredstonee.trsclient.compat.Keys::isDown);
	/** Module im TRS-Menü (ohne die, die unter Legacy-Forge nicht umsetzbar sind). */
	private final List<Module> menuModules;
	private ConfigStore config;
	private Waypoints waypoints;
	private HudManager hud;
	/** Tatsächlich benutztes senkrechtes Sichtfeld (aus dem FOV-Ereignis) – für die Wegpunkt-Projektion. */
	private double worldFov = 70;
	/** Welt des letzten Ticks – Wechsel verwirft Minimap-Speicher und Chat-Zusammenfassung. */
	private WorldClient lastWorld;
	/** Kein Schadens-Wackeln: ersetzter {@code hurtTime}-Wert, solange {@link #hurtSwapped}. */
	private int savedHurtTime;
	private boolean hurtSwapped;
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
		// Niedriges Feuer braucht den Feuer-Overlay-Renderer – ohne Coremod nicht machbar.
		list.remove(modules.lowFire);
		menuModules = Collections.unmodifiableList(list);
	}

	public static TrsClient get() {
		return instance;
	}

	private static final boolean BENCH_VANILLA = Boolean.getBoolean("trsclient.bench.vanilla");

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		instance = this;
		version = event.getModMetadata().version;
		// Benchmark „Vanilla“ (-PtrsBenchVanilla): TRS Client bleibt ganz aus, nur der Messablauf läuft (siehe init).
		if (BENCH_VANILLA) return;
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
				}, () -> Mc.world() != null, file.getParentFile().toPath(), "TRS-Client/" + version + " (Minecraft " + Mc.version() + "; forge)", message -> LOGGER.info(message));
		dev.theredstonee.trsclient.core.account.AccountManager.init(accountSwap);
		// Grafik-Modus „Schön“/„Max FPS“ (config/trsclient/fps-mode.json).
		dev.theredstonee.trsclient.core.perf.FpsConfigMode.init(file.getParentFile().toPath());
		initWaypoints(event.getModConfigurationDirectory());
		// Karten (Minimap + Weltkarte): Kartenspeicher unter config/trsclient/maps.
		dev.theredstonee.trsclient.core.map.MapEngine.init(modules, event.getModConfigurationDirectory().toPath());
		config = new ConfigStore(file.toPath());
		ConfigStore.Status status = config.load(modules.registry);
		// Menü-Stil für Vanilla-Menüs (Pause, Serverliste, Laden, Optionen, Welten).
		dev.theredstonee.trsclient.core.menus.MenuStyle.install(modules);
		if (status == ConfigStore.Status.RECOVERED) {
			LOGGER.warn("Config war beschädigt – Standardwerte geladen, Sicherung: {}", config.brokenFile());
		}
		LOGGER.info("Config {} ({})", status, config.file());
		// TRS API (Abzeichen, TRS-Umhänge, Presence) + Umhang-Physik; nichts davon blockiert den Start.
		dev.theredstonee.trsclient.online.LegacyOnline.init(event.getModConfigurationDirectory().toPath(), modules,
				version, Mc.version(), message -> LOGGER.info(message));
		// Leistung (Dynamische FPS, Culling, Partikel, Welt-Details, FPS-Boost); Leistungs-Mods übernehmen ihre Teile.
		dev.theredstonee.trsclient.perf.LegacyPerf.init(modules, Mc.version(), message -> LOGGER.info(message));
	}

	@Mod.EventHandler
	public void init(FMLInitializationEvent event) {
		if (BENCH_VANILLA) {
			LOGGER.info("TRS Client aus (Benchmark Vanilla)");
			AutoTest.installIfRequested();
			return;
		}
		TrsKeys.register();
		// Zoom-/Freelook-Taste sind Vanilla-Belegungen – im TRS-Menü ändern sie dieselbe Belegung.
		modules.zoomKey.link(TrsKeys.link(TrsKeys.zoom));
		modules.freelookKey.link(TrsKeys.link(TrsKeys.freelook));
		modules.worldMapKey.link(TrsKeys.link(TrsKeys.worldMap));
		installSocialOverlay();
		hud = new HudManager(modules);
		MinecraftForge.EVENT_BUS.register(this);
		MinecraftForge.EVENT_BUS.register(new dev.theredstonee.trsclient.menus.LegacyMenus());
		MinecraftForge.EVENT_BUS.register(new dev.theredstonee.trsclient.online.LegacyOnline.NameTags());
		MinecraftForge.EVENT_BUS.register(dev.theredstonee.trsclient.perf.LegacyPerf.get());
		AutoTest.installIfRequested();
		// Legacy-Forge hat kein "Client stoppt"-Ereignis – beim Beenden trotzdem speichern.
		Runtime.getRuntime().addShutdownHook(new Thread(this::saveConfig, "TRS Client config save"));
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (dev.theredstonee.trsclient.core.map.MapEngine.get() != null) dev.theredstonee.trsclient.core.map.MapEngine.get().shutdown();
		}, "TRS Client map save"));
	}

	/** Sozial-Benachrichtigungen (Toasts): Ton, Vollbild, Name der Schnelltaste. */
	private void installSocialOverlay() {
		try {
			dev.theredstonee.trsclient.core.social.SocialOverlay.install(new dev.theredstonee.trsclient.core.social.SocialPlatform() {
				@Override
				public void playToastSound() {
					try {
						Mc.toastSound();
					} catch (RuntimeException ignored) {
						// ohne Ton weiter
					}
				}

				@Override
				public boolean fullscreen() {
					return Mc.fullscreen();
				}

				@Override
				public String quickReplyKey() {
					if (TrsKeys.quickReply == null || TrsKeys.quickReply.getKeyCode() == org.lwjgl.input.Keyboard.KEY_NONE) return null;
					return net.minecraft.client.settings.GameSettings.getKeyDisplayString(TrsKeys.quickReply.getKeyCode());
				}
			}, modules);
		} catch (RuntimeException e) {
			LOGGER.warn("Sozial-Benachrichtigungen nicht verfügbar: " + e);
		}
	}

	/** Wegpunkt-Datei neben der Config (erst hier, weil das Verzeichnis aus preInit kommt). */
	private void initWaypoints(File configDir) {
		waypoints = new Waypoints(modules, new File(configDir, "trsclient-waypoints.json").toPath());
	}

	@Mod.EventHandler
	public void postInit(FMLPostInitializationEvent event) {
		if (BENCH_VANILLA) return;
		Minecraft mc = Minecraft.getMinecraft();
		// Nur den Vanilla-MouseHelper ersetzen – hat ein anderer Mod schon einen eigenen, bleibt der.
		boolean ownMouse = mc.mouseHelper != null && mc.mouseHelper.getClass() == MouseHelper.class;
		if (ownMouse) mc.mouseHelper = new ZoomMouseHelper(this);
		else LOGGER.warn("MouseHelper ist bereits ersetzt ({}) – Zoom verlangsamt die Maus nicht, kein Freelook", mc.mouseHelper);
		LOGGER.info("TRS Client {} initialisiert (Minecraft {}) – {} Module, Forge-Events registriert, Maus-Hook {}",
				version, Mc.version(), modules.registry.all().size(), ownMouse ? "aktiv" : "inaktiv");
	}

	// --- Forge-Events ---

	/** Meldungen der Clips in der Aktionsleiste. */
	private static final dev.theredstonee.trsclient.core.clips.Clips.ActionBar CLIP_MESSAGES = new dev.theredstonee.trsclient.core.clips.Clips.ActionBar() {
		@Override
		public void show(String text) {
			Mc.actionBar(text);
		}

		@Override
		public String keyLabel(boolean record) {
			return net.minecraft.client.settings.GameSettings.getKeyDisplayString(
					(record ? TrsKeys.toggleRecording : TrsKeys.saveClip).getKeyCode());
		}
	};

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		if (accountSwap != null) accountSwap.drain();
		Minecraft mc = Minecraft.getMinecraft();
		migrateKeys(mc);
		while (TrsKeys.hudProfile.isPressed()) {
			Mc.actionBar(I18n.tr("toast.hudProfile", modules.profiles.cycle()));
			saveConfig();
		}
		while (TrsKeys.menu.isPressed()) {
			if (mc.currentScreen == null) mc.displayGuiScreen(new TrsMenuScreen(null));
		}
		while (TrsKeys.redstoneOverlay.isPressed()) {
			modules.redstoneOverlay.toggle();
			Mc.actionBar(I18n.tr("toast.redstoneOverlay", modules.redstoneOverlay.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled")));
			saveConfig();
		}
		// Clips & Aufnahme: aufgenommen wird im Launcher, hier nur die Tasten melden.
		while (TrsKeys.saveClip.isPressed()) dev.theredstonee.trsclient.core.clips.Clips.get().saveClip();
		while (TrsKeys.toggleRecording.isPressed()) dev.theredstonee.trsclient.core.clips.Clips.get().toggleRecording();
		dev.theredstonee.trsclient.core.clips.Clips.get().tick(modules.clips.isEnabled(), CLIP_MESSAGES);
		while (TrsKeys.fullbright.isPressed()) {
			modules.fullbright.toggle();
			Mc.actionBar(I18n.tr("toast.fullbright", modules.fullbright.isEnabled() ? I18n.tr("common.enabled") : I18n.tr("common.disabled")));
			saveConfig();
		}
		// Emote-Rad: Taste halten öffnet es, Loslassen (im Rad abgefragt) spielt das gezeigte Emote.
		while (TrsKeys.emoteWheel.isPressed()) {
			if (mc.currentScreen == null && Mc.player() != null && dev.theredstonee.trsclient.online.LegacyEmotes.enabled()) {
				mc.displayGuiScreen(new dev.theredstonee.trsclient.screen.EmoteWheelScreen());
			}
		}
		// Weltkarte (M)
		while (TrsKeys.worldMap.isPressed()) {
			if (mc.currentScreen == null && Mc.player() != null && modules.worldMap.isEnabled()) {
				dev.theredstonee.trsclient.screen.WorldMapScreen screen = dev.theredstonee.trsclient.screen.WorldMapScreen.create();
				if (screen != null) mc.displayGuiScreen(screen);
			}
		}
		if (modules.keyDefaults.needsWorldMapKeyCheck() && mc.gameSettings != null) {
			modules.keyDefaults.markWorldMapKeyChecked();
			if (TrsKeys.resolveWorldMapConflict()) LOGGER.info("Weltkarten-Taste M war doppelt belegt – freigegeben");
			saveConfig();
		}
		// Garderobe (Taste standardmäßig unbelegt)
		while (TrsKeys.wardrobe.isPressed()) {
			if (mc.currentScreen == null && dev.theredstonee.trsclient.screen.WardrobeScreen.available()) {
				mc.displayGuiScreen(dev.theredstonee.trsclient.screen.WardrobeScreen.create(null));
			}
		}
		// Sozial-Bildschirm (Taste standardmäßig unbelegt) und Schnelltaste zum neuesten Toast (Y).
		while (TrsKeys.social.isPressed()) {
			if (mc.currentScreen == null && dev.theredstonee.trsclient.screen.MenuScreens.friendsAvailable()) {
				mc.displayGuiScreen(dev.theredstonee.trsclient.screen.MenuScreens.social(null));
			}
		}
		while (TrsKeys.quickReply.isPressed()) {
			if (mc.currentScreen != null) continue;
			try {
				dev.theredstonee.trsclient.core.social.SocialOverlay.QuickAction action =
						dev.theredstonee.trsclient.core.social.SocialOverlay.takeQuickAction();
				if (action != null) mc.displayGuiScreen(dev.theredstonee.trsclient.screen.MenuScreens.socialAction(action, null));
			} catch (RuntimeException e) {
				LOGGER.warn("Schnellantwort: " + e);
			}
		}
		// Wegpunkt- und Hotkey-Tasten gehören den Modulen (Tastenbelegung im TRS-Menü).
		if (mc.currentScreen == null) {
			if (moduleKeys.pressed(modules.waypointAddKey) && Mc.player() != null && modules.waypoints.isEnabled()) {
				mc.displayGuiScreen(new WaypointEditScreen(null, null));
			}
			if (moduleKeys.pressed(modules.waypointListKey) && modules.waypoints.isEnabled()) {
				mc.displayGuiScreen(new WaypointListScreen(null));
			}
			for (int i = 0; i < modules.hotkeyKeys.length; i++) {
				if (moduleKeys.pressed(modules.hotkeyKeys[i])) chat.onHotkey(i);
			}
		} else {
			moduleKeys.releaseAll();
		}
		checkWorldChange();
		pvp.tick(mc);
		chat.tick(mc);
		waypoints.tick();
		hud.tick();
		tickRedstone();
		dev.theredstonee.trsclient.online.LegacyOnline.tick(mc);
		dev.theredstonee.trsclient.online.LegacyEmotes.tick(mc);
	}

	/** Weltwechsel erkennen (Legacy-Forge hat dafür kein eigenes Ereignis). */
	private void checkWorldChange() {
		WorldClient world = Mc.world();
		if (world == lastWorld) return;
		lastWorld = world;
		hud.onWorldChange();
		chat.onWorldChange();
		if (world == null) waypoints.onDisconnect();
	}

	/** Reichweite und Combo: jeder Schlag des eigenen Spielers (reine Anzeige). */
	@SubscribeEvent
	public void onAttackEntity(AttackEntityEvent event) {
		EntityPlayer player = Mc.player();
		//? if >=1.9 {
		/*if (event.getEntityPlayer() == player) pvp.onAttack(player, event.getTarget());
		*///?} else
		if (event.entityPlayer == player) pvp.onAttack(player, event.target);
	}

	/** Chat-Verbesserungen (zuletzt, damit andere Mods die Nachricht unverändert sehen). */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onChatReceived(ClientChatReceivedEvent event) {
		if (event.isCanceled()) return;
		chat.onChatReceived(event);
	}

	/** Eigene Farbe/Stärke für den Rahmen um den anvisierten Block. */
	@SubscribeEvent
	public void onBlockHighlight(DrawBlockHighlightEvent event) {
		if (!modules.blockOutline.isEnabled()) return;
		float alpha = (float) (modules.blockOutlineOpacity.get() / 100.0);
		if (BlockOutline.draw(event, modules.blockOutlineColor.rgb(), alpha, modules.blockOutlineWidth.getFloat())) {
			event.setCanceled(true);
		}
	}

	/** Strg+Klick im Chat kopiert die angeklickte Zeile. */
	@SubscribeEvent
	public void onGuiMouseInput(GuiScreenEvent.MouseInputEvent.Pre event) {
		if (!(Mc.eventGui(event) instanceof GuiChat)) return;
		if (!Mouse.getEventButtonState() || Mouse.getEventButton() != 0) return;
		if (!GuiScreen.isCtrlKeyDown()) return;
		if (chat.onChatClick(Mouse.getX(), Mouse.getY())) event.setCanceled(true);
	}

	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		Minecraft mc = Minecraft.getMinecraft();
		if (event.phase == TickEvent.Phase.START) {
			handPass = false;
			dev.theredstonee.trsclient.render.ColorPass.frameStart();
			updateZoom(mc);
			// Fullbright: Gamma nur bis zur Lightmap-Berechnung ersetzen (siehe restoreGamma).
			// In den Video-Einstellungen nicht – dort zeigt/ändert der Regler den echten Wert.
			if (modules.fullbright.isEnabled() && Mc.world() != null && !(mc.currentScreen instanceof GuiVideoSettings)) {
				savedGamma = mc.gameSettings.gammaSetting;
				mc.gameSettings.gammaSetting = FULLBRIGHT_GAMMA;
				gammaSwapped = true;
				HookStats.lightmap++;
			}
			// Kein Schadens-Wackeln: hurtTime nur für dieses Bild auf 0 (die Kamera kippt dann nicht).
			if (modules.noHurtCam.isEnabled() && Mc.player() != null && Mc.player().hurtTime > 0) {
				savedHurtTime = Mc.player().hurtTime;
				Mc.player().hurtTime = 0;
				hurtSwapped = true;
			}
		} else {
			restoreGamma(mc);
			restoreHurtTime();
			// Farben bei ausgeblendeter Oberfläche (F1): dann gibt es kein Overlay-Ereignis.
			if (Mc.world() != null && Mc.hudHidden() && mc.currentScreen == null) {
				dev.theredstonee.trsclient.render.ColorPass.afterLevel(event.renderTickTime);
			}
		}
	}

	private void restoreHurtTime() {
		if (!hurtSwapped) return;
		hurtSwapped = false;
		if (Mc.player() != null) Mc.player().hurtTime = savedHurtTime;
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
		// Tatsächlich benutztes Sichtfeld merken – damit rechnet die Wegpunkt-Projektion.
		float fov = event.getFOV();
		if (fov > 1 && fov < 180) worldFov = fov;
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
			if (button == 0) {
				leftClicks.record(now);
				// Angreifen/Abbauen beendet das eigene Emote (wie Bewegung).
				dev.theredstonee.trsclient.online.LegacyEmotes.onAttack();
			} else if (button == 1) {
				rightClicks.record(now);
			}
		}
		// Reines Mausrad-Ereignis während des Zooms → Zoomstufe statt Hotbar.
		int wheel = Mc.mouseWheel(event);
		if (button == -1 && wheel != 0 && zoom.isActive() && modules.zoomScroll.get()) {
			HookStats.scroll++;
			zoom.scroll(wheel);
			event.setCanceled(true);
		}
	}

	/** Farben: Welt und Hand sind gezeichnet, HUD und Menüs kommen erst danach. */
	@SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
	public void onOverlayColors(RenderGameOverlayEvent.Pre event) {
		if (Mc.overlayType(event) != RenderGameOverlayEvent.ElementType.ALL) return;
		dev.theredstonee.trsclient.render.ColorPass.afterLevel(Mc.partialTicks(event));
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
		hud.render(Gfx.of(res.getScaledWidth(), res.getScaledHeight()), Mc.partialTicks(event));
		// Sozial-Toasts im Spiel (über Bildschirmen zeichnet sie onScreenDrawn).
		if (Mc.screen() == null && !Mc.hudHidden()) drawToasts(res.getScaledWidth(), res.getScaledHeight());
	}

	/** Sozial-Toasts über jedem Bildschirm (nach allem anderen, auch nach dem Redstone-Menü-Stil). */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onScreenDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
		GuiScreen s = Mc.eventGui(event);
		if (s == null) return;
		drawToasts(s.width, s.height);
	}

	private static void drawToasts(int width, int height) {
		try {
			if (!dev.theredstonee.trsclient.core.social.SocialOverlay.active()) return;
			Gfx g = Gfx.of(width, height);
			dev.theredstonee.trsclient.ui.GfxCanvas c = dev.theredstonee.trsclient.ui.GfxCanvas.of(g, Mc.font());
			c.push();
			c.raise(400f);
			dev.theredstonee.trsclient.core.social.SocialOverlay.render(c, width, height);
			c.pop();
		} catch (RuntimeException ignored) {
			// Benachrichtigungen dürfen das Spiel nie stören.
		}
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
		boolean wanted = modules.zoom.isEnabled()
				&& (forceZoom || (TrsKeys.zoom.isKeyDown() && mc.currentScreen == null));
		// Kein Fernrohr vor 1.17; die filmische Kamera gilt auf Wunsch nur, solange gezoomt wird.
		zoom.frame(wanted, false, modules.zoomFactor.get(), modules.zoomSmooth.get(), System.nanoTime());
		boolean smooth = zoomCinematic.update(mc.gameSettings.smoothCamera, zoom.isActive() && modules.zoomCinematic.get());
		if (smooth != mc.gameSettings.smoothCamera) mc.gameSettings.smoothCamera = smooth;
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
			LOGGER.info("Zoom-Taste von C auf V umgestellt (C ist ab 1.12 'Schnellleiste speichern')");
		}
		modules.keyDefaults.markMigrated();
		saveConfig();
	}

	/** Speichert die Einstellungen (Fehler nur loggen). */
	public void saveConfig() {
		if (config == null) return;
		try {
			config.saveLater(modules.registry);
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

	public ChatFeatures chat() {
		return chat;
	}

	public dev.theredstonee.trsclient.core.redstone.RedstoneTools redstone() {
		return redstone;
	}

	public Waypoints waypoints() {
		return waypoints;
	}

	/** Tatsächlich benutztes senkrechtes Sichtfeld (Grad). */
	public double worldFov() {
		return worldFov;
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
