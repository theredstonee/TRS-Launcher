package dev.theredstonee.trsclient.perf;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.compat.PerfOptions;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.perf.DynamicFps;
import dev.theredstonee.trsclient.core.perf.FramePacer;
import dev.theredstonee.trsclient.core.perf.FrameStats;
import dev.theredstonee.trsclient.core.perf.GpuInfo;
import dev.theredstonee.trsclient.core.perf.Occlusion;
import dev.theredstonee.trsclient.core.perf.ParticleGate;
import dev.theredstonee.trsclient.core.perf.PerfCompat;
import dev.theredstonee.trsclient.core.perf.PerfFeature;
import dev.theredstonee.trsclient.core.perf.Performance;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Brücke zwischen den Leistungs-Mixins und {@link Performance} (Mojmap-Bäume Fabric, NeoForge, Forge –
 * dieselbe Datei). Jede Methode ist billig, fängt alle Fehler ab und lässt Vanilla im Zweifel unverändert.
 */
public final class PerfHooks {
	private static Performance perf;
	/** Bildzeiten für den Benchmark (nimmt nur während einer Messung auf). */
	public static final FrameStats FRAME_STATS = new FrameStats();
	private static final PerfOptions OPTIONS = new PerfOptions();
	private static final FramePacer.Wake WAKE = new FramePacer.Wake() {
		@Override
		public boolean stillLimited() {
			return PerfHooks.stillLimited();
		}
	};
	private static Consumer<String> log = new Consumer<String>() {
		@Override
		public void accept(String s) {
		}
	};
	private static long errorLogged;
	private static int particleCount;
	private static float appliedVolume = 1f;
	private static Object lastLevel;
	/** Nur für den Autotest: Fenster gilt als im Hintergrund. */
	private static boolean forceUnfocused;
	/** Fensterzustand „minimiert“ (vom System) – höchstens alle {@link #WINDOW_POLL_NS} neu gefragt. */
	private static boolean minimizedCached;
	private static long minimizedAt;
	/** Tasten für die AFK-Erkennung – ebenso gedrosselt. */
	private static boolean anyKeyCached;
	private static long anyKeyAt;
	private static final long WINDOW_POLL_NS = 250_000_000L;

	// Aufrufzähler (Nachweis im Autotest, praktisch kostenlos)
	public static long frames;
	public static long particlesBlocked;
	public static long entitiesCulled;
	public static long blockEntitiesCulled;
	public static long nameTagsHidden;
	public static long skySkipped;
	public static long starsHidden;
	public static long weatherSkipped;
	public static long fogCleared;
	public static long animationsSkipped;

	private PerfHooks() {
	}

	/**
	 * Beim Start des Clients (nach dem Laden der Config).
	 * @param mixins false = Build ohne Mixins (Forge 1.14.4): dort gibt es nur Dynamische FPS (über ein Event)
	 */
	public static void init(TrsModules modules, PerfCompat.ModCheck mods, String loader, String minecraft, Consumer<String> logger,
			boolean mixins) {
		if (logger != null) log = logger;
		Set<PerfFeature> supported = mixins ? supported() : EnumSet.of(PerfFeature.DYNAMIC_FPS, PerfFeature.BACKGROUND_VOLUME);
		PerfCompat compat = new PerfCompat(mods, optifinePresent(), loader, minecraft, supported);
		perf = new Performance(modules, compat);
		// Eingebaute Grafikkarten im Hintergrund lesen (für den Hinweis „Onboard-Grafik“).
		GpuInfo.adapters();
		if (!compat.detected().isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < compat.detected().size(); i++) {
				if (i > 0) sb.append(", ");
				sb.append(compat.detected().get(i).displayName());
			}
			log.accept("Leistungs-Mods erkannt: " + sb + " – deren Funktionen bleiben im TRS Client aus");
		}
	}

	public static Performance get() {
		return perf;
	}

	/**
	 * HUD gesammelt zeichnen (1.20–1.21.1)? Nicht, wenn ImmediatelyFast da ist – das sammelt das HUD schon selbst,
	 * doppelt gesammelt würde nur früher geleert.
	 */
	public static boolean batchHud() {
		if (forceUnbatchedHud) return false;
		Performance p = perf;
		return p == null || !p.compat().has(dev.theredstonee.trsclient.core.perf.PerfMod.IMMEDIATELY_FAST);
	}

	/** Leistungs-Funktionen, die es in dieser Minecraft-Version gibt. */
	public static Set<PerfFeature> supported() {
		EnumSet<PerfFeature> s = EnumSet.allOf(PerfFeature.class);
		//? if >=1.21.9 && <1.21.11 {
		/*// 1.21.9/1.21.10: Gesamtlautstärke nur über die Optionen – kein Faktor ohne Mixin.
		s.remove(PerfFeature.BACKGROUND_VOLUME);
		*///?}
		//? if <1.15 {
		/*s.remove(PerfFeature.WEATHER);
		*///?}
		//? if <1.17 {
		/*s.remove(PerfFeature.FOG);
		*///?}
		//? if >=1.21.6 {
		/*s.remove(PerfFeature.FOG);
		*///?}
		return s;
	}

	/** OptiFine (Forge) bzw. OptiFabric: an den Klassen erkennbar. */
	private static boolean optifinePresent() {
		for (String name : new String[]{"net.optifine.Config", "optifine.OptiFineClassTransformer"}) {
			try {
				Class.forName(name, false, PerfHooks.class.getClassLoader());
				return true;
			} catch (ClassNotFoundException | LinkageError e) {
				// nicht da
			}
		}
		return false;
	}

	// --- je Tick ---

	public static void tick(Minecraft mc) {
		Performance p = perf;
		if (p == null) return;
		try {
			if (p.game() == null && mc.options != null) p.setGame(OPTIONS);
			if (mc.level != lastLevel) {
				lastLevel = mc.level;
				p.worldChanged();
				// Eigener Blockzugriff für den Occlusion-Thread (eigene Position, nie mit dem Render-Thread geteilt).
				p.occlusion().setBlocks(mc.level == null ? null : new WorldBlocks(mc.level));
			}
			p.tick(System.currentTimeMillis(), Mc.screen() != null);
			if (p.particleLimitActive() && mc.particleEngine != null) particleCount = parseCount(mc.particleEngine.countParticles());
		} catch (RuntimeException | LinkageError e) {
			error(e);
		}
	}

	private static int parseCount(String s) {
		try {
			return s == null ? 0 : Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// --- je Bild (Dynamische FPS) ---

	/** Vor jedem Bild (Minecraft#runTick). Begrenzt ggf. die Bildrate und passt die Lautstärke an. */
	public static void beforeFrame() {
		FRAME_STATS.frame(System.nanoTime());
		Performance p = perf;
		if (p == null) return;
		frames++;
		try {
			Minecraft mc = Minecraft.getInstance();
			boolean focused = mc.isWindowActive() && !forceUnfocused;
			long now = System.nanoTime();
			// Fensterzustand und Tasten nur ein paar Mal je Sekunde (jede Abfrage kostet Zeit im Bild);
			// Maus und Tasten braucht es überhaupt nur für die AFK-Grenze.
			if (now - minimizedAt > WINDOW_POLL_NS || now < minimizedAt) {
				minimizedAt = now;
				minimizedCached = liveMinimized();
			}
			boolean afk = p.afkActive();
			if (afk && (now - anyKeyAt > WINDOW_POLL_NS || now < anyKeyAt)) {
				anyKeyAt = now;
				anyKeyCached = anyKeyDown(mc);
			}
			int limit = p.frameLimit(System.currentTimeMillis(), focused, minimizedCached, afk ? mc.mouseHandler.xpos() : 0,
					afk ? mc.mouseHandler.ypos() : 0, afk && anyKeyCached);
			applyVolume(mc, p.volume());
			p.pacer().pace(limit, WAKE);
		} catch (RuntimeException | LinkageError e) {
			error(e);
		}
	}

	private static boolean anyKeyDown(Minecraft mc) {
		if (mc.options == null) return false;
		for (KeyMapping key : mc.options.keyMappings) {
			if (key.isDown()) return true;
		}
		return false;
	}

	/** Gilt die Grenze noch? Fensterzustand direkt beim System abgefragt (nicht erst nach dem nächsten Bild). */
	private static boolean stillLimited() {
		Performance p = perf;
		if (p == null) return false;
		DynamicFps.State state = p.dynamicFps().state();
		if (state == DynamicFps.State.AFK) return true;
		boolean minimized = liveMinimized();
		if (state == DynamicFps.State.MINIMIZED) return minimized;
		return forceUnfocused || minimized || !liveFocused();
	}

	private static long windowHandle() {
		//? if >=1.21.9 {
		/*return Mc.window().handle();
		*///?} else
		return Mc.window().getWindow();
	}

	private static boolean liveFocused() {
		try {
			//? if >=26.3 {
			/*return (org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(windowHandle()) & org.lwjgl.sdl.SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0;
			*///?} else
			return org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(windowHandle(), org.lwjgl.glfw.GLFW.GLFW_FOCUSED) != 0;
		} catch (RuntimeException | LinkageError e) {
			return Minecraft.getInstance().isWindowActive();
		}
	}

	private static boolean liveMinimized() {
		try {
			//? if >=26.3 {
			/*return (org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(windowHandle()) & org.lwjgl.sdl.SDLVideo.SDL_WINDOW_MINIMIZED) != 0;
			*///?} else
			return org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(windowHandle(), org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) != 0;
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Gesamtlautstärke als Faktor setzen – ohne die Option des Spielers zu ändern. */
	private static void applyVolume(Minecraft mc, float factor) {
		if (Math.abs(factor - appliedVolume) < 0.001f) return;
		appliedVolume = factor;
		//? if >=1.21.11 {
		/*mc.getSoundManager().updateCategoryVolume(SoundSource.MASTER, factor);
		*///?} elif <1.21.9 {
		mc.getSoundManager().updateSourceVolume(SoundSource.MASTER, mc.options.getSoundSourceVolume(SoundSource.MASTER) * factor);
		//?}
	}

	/** Nur für den Autotest: Hintergrund vortäuschen. */
	public static void forceUnfocused(boolean on) {
		forceUnfocused = on;
	}

	// --- Partikel ---

	/** Aus ParticleEngine#createParticle: darf diese Art entstehen? */
	public static boolean allowKind(ParticleOptions options) {
		Performance p = perf;
		if (p == null || options == null) return true;
		ParticleGate.Kind kind = kindOf(options.getType());
		if (kind == ParticleGate.Kind.OTHER || !p.hidesParticle(kind)) return true;
		particlesBlocked++;
		return false;
	}

	/** Aus ParticleEngine#add: Obergrenze und Menge. */
	public static boolean allowAdd() {
		Performance p = perf;
		if (p == null || !p.particlesActive()) return true;
		if (p.allowParticle(ParticleGate.Kind.OTHER, particleCount)) {
			particleCount++;
			return true;
		}
		particlesBlocked++;
		return false;
	}

	private static ParticleGate.Kind kindOf(ParticleType<?> type) {
		if (type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER) return ParticleGate.Kind.EXPLOSION;
		if (type == ParticleTypes.RAIN) return ParticleGate.Kind.RAIN;
		if (type == ParticleTypes.SMOKE || type == ParticleTypes.LARGE_SMOKE || type == ParticleTypes.CAMPFIRE_COSY_SMOKE
				|| type == ParticleTypes.CAMPFIRE_SIGNAL_SMOKE) {
			return ParticleGate.Kind.SMOKE;
		}
		return ParticleGate.Kind.OTHER;
	}

	// --- Wesen, Block-Entities, Namensschilder ---

	/** Nur Benchmark: Zeit in einzelnen Hooks messen (Summe in ns) und das Sammeln des HUD abschalten. */
	public static volatile boolean profile;
	public static long cullNanos;
	public static long hudNanos;
	public static volatile boolean forceUnbatchedHud;

	/** Aus EntityRenderDispatcher#shouldRender: true = nicht zeichnen. */
	public static boolean cullEntity(Entity e, double camX, double camY, double camZ) {
		if (!profile) return cullEntityNow(e, camX, camY, camZ);
		long t0 = System.nanoTime();
		try {
			return cullEntityNow(e, camX, camY, camZ);
		} finally {
			cullNanos += System.nanoTime() - t0;
		}
	}

	private static boolean cullEntityNow(Entity e, double camX, double camY, double camZ) {
		Performance p = perf;
		if (p == null || e == null || !p.entitiesActive()) return false;
		try {
			Minecraft mc = Minecraft.getInstance();
			if (e == mc.getCameraEntity() || e == mc.player) return false;
			if (mc.player != null && mc.player.getVehicle() == e) return false;
			//? if >=1.17 {
			if (e.isCurrentlyGlowing()) return false;
			//?} else
			/*if (e.isGlowing()) return false;*/
			double dx = ex(e) - camX, dy = ey(e) - camY, dz = ez(e) - camZ;
			double distSq = dx * dx + dy * dy + dz * dz;
			Performance.EntityKind kind = e instanceof Player ? Performance.EntityKind.PLAYER
					: e instanceof ItemEntity ? Performance.EntityKind.ITEM
					: e instanceof ItemFrame ? Performance.EntityKind.FRAME : Performance.EntityKind.MOB;
			if (p.cullEntity(kind, distSq)) {
				entitiesCulled++;
				return true;
			}
			if (!p.active(PerfFeature.ENTITY_OCCLUSION) || mc.level == null) return false;
			AABB box = e.getBoundingBox();
			// Riesen (Drache, Wither-Boss-Hitbox, Schiffe aus Mods) nie verstecken.
			if (box.maxX - box.minX > 6 || box.maxY - box.minY > 6 || box.maxZ - box.minZ > 6) return false;
			// Nur das zuletzt berechnete Ergebnis – die Sichtlinien rechnet ein Hintergrund-Thread.
			boolean visible = p.occlusion().visible(e.getId(), camX, camY, camZ, box.minX, box.minY, box.minZ, box.maxX, box.maxY,
					box.maxZ);
			if (!visible) entitiesCulled++;
			return !visible;
		} catch (RuntimeException | LinkageError ex) {
			error(ex);
			return false;
		}
	}

	/** Block-Entity (Truhe, Schild, Banner, Kopf …) weiter weg als eingestellt? Leuchtfeuer bleiben. */
	public static boolean cullBlockEntity(BlockEntity be) {
		Performance p = perf;
		if (p == null || be == null || !p.active(PerfFeature.BLOCK_ENTITY_DISTANCE)) return false;
		try {
			if (be instanceof BeaconBlockEntity) return false;
			BlockPos pos = be.getBlockPos();
			double dx = pos.getX() + 0.5 - Mc.cameraX(), dy = pos.getY() + 0.5 - Mc.cameraY(), dz = pos.getZ() + 0.5 - Mc.cameraZ();
			boolean cull = p.cullBlockEntity(dx * dx + dy * dy + dz * dz);
			if (cull) blockEntitiesCulled++;
			return cull;
		} catch (RuntimeException | LinkageError ex) {
			error(ex);
			return false;
		}
	}

	/** Namensschild weiter weg als eingestellt? ({@code distSq} = Abstand² zur Kamera) */
	public static boolean hideNameTag(double distSq) {
		Performance p = perf;
		if (p == null || !p.hideNameTag(distSq)) return false;
		nameTagsHidden++;
		return true;
	}

	/** Namensschild eines Wesens weiter weg als eingestellt? */
	public static boolean hideNameTag(Entity e) {
		Performance p = perf;
		if (p == null || e == null || !p.active(PerfFeature.NAMETAG_DISTANCE)) return false;
		try {
			double dx = ex(e) - Mc.cameraX(), dy = ey(e) - Mc.cameraY(), dz = ez(e) - Mc.cameraZ();
			return hideNameTag(dx * dx + dy * dy + dz * dz);
		} catch (RuntimeException | LinkageError ex) {
			error(ex);
			return false;
		}
	}

	private static double ex(Entity e) {
		//? if >=1.15 {
		return e.getX();
		//?} else
		/*return e.x;*/
	}

	private static double ey(Entity e) {
		//? if >=1.15 {
		return e.getY();
		//?} else
		/*return e.y;*/
	}

	private static double ez(Entity e) {
		//? if >=1.15 {
		return e.getZ();
		//?} else
		/*return e.z;*/
	}

	// --- Welt-Details ---

	/** Soll die TRS-Variante dieser Funktion gerade eingreifen? */
	public static boolean hide(PerfFeature feature) {
		Performance p = perf;
		return p != null && p.active(feature);
	}

	/**
	 * Voller, undurchsichtiger Block der Client-Welt (für „hinter Wänden“). Wird nur vom Occlusion-Thread benutzt:
	 * liest den Block-Zustand aus den geladenen Chunks (fehlt ein Chunk, gilt Luft); Fehler fängt der Thread ab.
	 */
	private static final class WorldBlocks implements Occlusion.Blocks {
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		private final Level level;

		WorldBlocks(Level level) {
			this.level = level;
		}

		@Override
		public boolean opaque(int x, int y, int z) {
			Level l = level;
			pos.set(x, y, z);
			BlockState state = l.getBlockState(pos);
			return state.canOcclude() && state.isRedstoneConductor(l, pos);
		}
	}

	// --- Fehler ---

	/** Höchstens einmal je Minute loggen; das Spiel läuft immer weiter. */
	public static void error(Throwable e) {
		long now = System.currentTimeMillis();
		if (now - errorLogged < 60_000) return;
		errorLogged = now;
		log.accept("Leistung: " + e);
	}

	/** Zusammenfassung der Hook-Aufrufe (Autotest). */
	public static String stats() {
		return "frames=" + frames + ", particlesBlocked=" + particlesBlocked + ", entitiesCulled=" + entitiesCulled
				+ ", blockEntitiesCulled=" + blockEntitiesCulled + ", nameTagsHidden=" + nameTagsHidden + ", skySkipped=" + skySkipped
				+ ", starsHidden=" + starsHidden + ", weatherSkipped=" + weatherSkipped + ", fogCleared=" + fogCleared
				+ ", animationsSkipped=" + animationsSkipped;
	}
}
