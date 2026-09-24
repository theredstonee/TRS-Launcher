package dev.theredstonee.trsclient.perf;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.perf.DynamicFps;
import dev.theredstonee.trsclient.core.perf.FramePacer;
import dev.theredstonee.trsclient.core.perf.GameOptions;
import dev.theredstonee.trsclient.core.perf.GpuInfo;
import dev.theredstonee.trsclient.core.perf.Occlusion;
import dev.theredstonee.trsclient.core.perf.ParticleGate;
import dev.theredstonee.trsclient.core.perf.PerfCompat;
import dev.theredstonee.trsclient.core.perf.PerfFeature;
import dev.theredstonee.trsclient.core.perf.Performance;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.IRenderHandler;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
//? if >=1.9 {
/*import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
*///?} else {
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
//?}

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Leistung für Forge 1.8.9–1.12.2 (ohne Mixins): Dynamische FPS über das Bild-Ereignis, Entfernung und
 * „hinter Wänden“ für Lebewesen über RenderLivingEvent, Namensschilder über RenderLivingEvent.Specials,
 * Partikel werden nach jedem Tick in den Listen des EffectRenderer ausgedünnt (Feld per Typ gesucht),
 * Himmel und Wetter über Forges IRenderHandler der Welt, Nebel über FogDensity, Textur-Animationen durch
 * vorübergehendes Leeren der Animationsliste der Block-Textur. Was es hier nicht sauber gibt (Block-Entities,
 * Items/Rahmen, Sterne einzeln, Partikel-Menge), meldet das Menü als „in dieser Version nicht verfügbar“.
 */
public final class LegacyPerf implements GameOptions {
	private static final LegacyPerf INSTANCE = new LegacyPerf();
	private static final IRenderHandler NOTHING = new IRenderHandler() {
		@Override
		public void render(float partialTicks, WorldClient world, Minecraft mc) {
		}
	};

	private Performance perf;
	private Consumer<String> log = new Consumer<String>() {
		@Override
		public void accept(String s) {
		}
	};
	private long errorLogged;
	private float appliedVolume = 1f;
	private WorldClient lastWorld;
	private IRenderHandler savedSky;
	private IRenderHandler savedWeather;
	private boolean skyReplaced;
	private boolean weatherReplaced;
	private Field particleLayers;
	private boolean particleLayersSearched;
	private Field animatedSprites;
	private boolean animatedSearched;
	private List<Object> savedAnimations;
	private boolean forceUnfocused;
	private final Blocks blocks = new Blocks();

	public long frames;
	public long particlesRemoved;
	public long entitiesCulled;
	public long nameTagsHidden;
	public long fogCleared;

	private LegacyPerf() {
	}

	public static LegacyPerf get() {
		return INSTANCE;
	}

	/** Beim Start (preInit, nach dem Laden der Config). */
	public static void init(TrsModules modules, String minecraft, Consumer<String> logger) {
		LegacyPerf p = INSTANCE;
		if (logger != null) p.log = logger;
		EnumSet<PerfFeature> supported = EnumSet.of(PerfFeature.DYNAMIC_FPS, PerfFeature.BACKGROUND_VOLUME,
				PerfFeature.ENTITY_DISTANCE, PerfFeature.ENTITY_OCCLUSION, PerfFeature.NAMETAG_DISTANCE,
				PerfFeature.PARTICLE_LIMIT, PerfFeature.PARTICLE_EXPLOSIONS, PerfFeature.PARTICLE_RAIN, PerfFeature.PARTICLE_SMOKE,
				PerfFeature.SKY, PerfFeature.FOG, PerfFeature.WEATHER, PerfFeature.TEXTURE_ANIMATIONS);
		PerfCompat compat = new PerfCompat(new PerfCompat.ModCheck() {
			@Override
			public boolean loaded(String id) {
				return Loader.isModLoaded(id);
			}
		}, optifinePresent(), PerfCompat.FORGE, minecraft, supported);
		p.perf = new Performance(modules, compat);
		GpuInfo.adapters();
		if (!compat.detected().isEmpty()) p.log.accept("Leistungs-Mods erkannt: " + compat.detected() + " – deren Funktionen bleiben im TRS Client aus");
	}

	public Performance performance() {
		return perf;
	}

	private static boolean optifinePresent() {
		for (String name : new String[]{"optifine.OptiFineClassTransformer", "optifine.OptiFineForgeTweaker", "net.optifine.Config", "Config"}) {
			try {
				Class<?> c = Class.forName(name, false, LegacyPerf.class.getClassLoader());
				if (!"Config".equals(name) || c.getPackage() == null) return true;
			} catch (ClassNotFoundException | LinkageError e) {
				// nicht da
			}
		}
		return false;
	}

	// --- Ereignisse ---

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END || perf == null) return;
		try {
			Minecraft mc = Minecraft.getMinecraft();
			if (perf.game() == null && mc.gameSettings != null) perf.setGame(this);
			WorldClient world = Mc.world();
			if (world != lastWorld) {
				lastWorld = world;
				skyReplaced = false;
				weatherReplaced = false;
				perf.worldChanged();
			}
			perf.tick(System.currentTimeMillis(), mc.currentScreen != null);
			renderHandlers(world);
			animations(mc);
			particles(mc);
		} catch (RuntimeException | LinkageError e) {
			error(e);
		}
	}

	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		if (event.phase != TickEvent.Phase.START || perf == null) return;
		frames++;
		try {
			Minecraft mc = Minecraft.getMinecraft();
			boolean focused = Display.isActive() && !forceUnfocused;
			boolean minimized = !Display.isVisible();
			int limit = perf.frameLimit(System.currentTimeMillis(), focused, minimized, Mouse.getX(), Mouse.getY(), anyKeyDown(mc));
			applyVolume(mc, perf.volume());
			perf.pacer().pace(limit, new FramePacer.Wake() {
				@Override
				public boolean stillLimited() {
					DynamicFps.State state = perf.dynamicFps().state();
					if (state == DynamicFps.State.AFK) return true;
					// Fenster-Nachrichten holen, damit Fokus/Minimieren sofort ankommen (LWJGL 2).
					Display.processMessages();
					if (state == DynamicFps.State.MINIMIZED) return !Display.isVisible();
					return forceUnfocused || !Display.isActive() || !Display.isVisible();
				}
			});
		} catch (RuntimeException | LinkageError e) {
			error(e);
		}
	}

	@SubscribeEvent
	public void onRenderLiving(RenderLivingEvent.Pre event) {
		if (perf == null || !perf.entitiesActive()) return;
		try {
			//? if >=1.9 {
			/*EntityLivingBase e = event.getEntity();
			double x = event.getX(), y = event.getY(), z = event.getZ();
			*///?} else {
			EntityLivingBase e = event.entity;
			double x = event.x, y = event.y, z = event.z;
			//?}
			if (cull(e, x, y, z)) {
				entitiesCulled++;
				event.setCanceled(true);
			}
		} catch (RuntimeException | LinkageError ex) {
			error(ex);
		}
	}

	@SubscribeEvent
	public void onNameTag(RenderLivingEvent.Specials.Pre event) {
		if (perf == null || !perf.active(PerfFeature.NAMETAG_DISTANCE)) return;
		//? if >=1.9 {
		/*double x = event.getX(), y = event.getY(), z = event.getZ();
		*///?} else
		double x = event.x, y = event.y, z = event.z;
		if (perf.hideNameTag(x * x + y * y + z * z)) {
			nameTagsHidden++;
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onFogDensity(EntityViewRenderEvent.FogDensity event) {
		if (perf == null || !perf.active(PerfFeature.FOG)) return;
		try {
			EntityPlayer player = Mc.player();
			if (player == null) return;
			//? if >=1.9 {
			/*if (event.getState().getMaterial() != net.minecraft.block.material.Material.AIR) return;
			if (player.isPotionActive(net.minecraft.init.MobEffects.BLINDNESS)) return;
			event.setDensity(0f);
			*///?} else {
			if (event.block.getMaterial() != net.minecraft.block.material.Material.air) return;
			if (player.isPotionActive(net.minecraft.potion.Potion.blindness)) return;
			event.density = 0f;
			//?}
			event.setCanceled(true);
			fogCleared++;
		} catch (RuntimeException | LinkageError e) {
			error(e);
		}
	}

	// --- Dynamische FPS ---

	private static boolean anyKeyDown(Minecraft mc) {
		if (mc.gameSettings == null) return false;
		for (KeyBinding key : mc.gameSettings.keyBindings) {
			if (key.isKeyDown()) return true;
		}
		return false;
	}

	private void applyVolume(Minecraft mc, float factor) {
		if (Math.abs(factor - appliedVolume) < 0.001f) return;
		appliedVolume = factor;
		mc.getSoundHandler().setSoundLevel(SoundCategory.MASTER, mc.gameSettings.getSoundLevel(SoundCategory.MASTER) * factor);
	}

	/** Nur für den Autotest: Hintergrund vortäuschen. */
	public void forceUnfocused(boolean on) {
		forceUnfocused = on;
	}

	// --- Lebewesen ---

	/** x/y/z = Abstand zur Kamera (Render-Versatz aus dem Ereignis). */
	private boolean cull(EntityLivingBase e, double x, double y, double z) {
		Minecraft mc = Minecraft.getMinecraft();
		if (e == mc.getRenderViewEntity() || e == Mc.player()) return false;
		//? if >=1.9 {
		/*if (Mc.player() != null && Mc.player().getRidingEntity() == e) return false;
		*///?} else
		if (Mc.player() != null && Mc.player().ridingEntity == e) return false;
		//? if >=1.9 {
		/*if (e.isGlowing()) return false;
		*///?}
		double distSq = x * x + y * y + z * z;
		Performance.EntityKind kind = e instanceof EntityPlayer ? Performance.EntityKind.PLAYER : Performance.EntityKind.MOB;
		if (perf.cullEntity(kind, distSq)) return true;
		if (!perf.active(PerfFeature.ENTITY_OCCLUSION) || Mc.world() == null) return false;
		AxisAlignedBB box = e.getEntityBoundingBox();
		if (box == null || box.maxX - box.minX > 6 || box.maxY - box.minY > 6 || box.maxZ - box.minZ > 6) return false;
		// Kameraposition des laufenden Bildes (die Ereignis-Werte sind relativ dazu).
		net.minecraft.client.renderer.entity.RenderManager rm = mc.getRenderManager();
		double camX = rm.viewerPosX, camY = rm.viewerPosY, camZ = rm.viewerPosZ;
		blocks.world = Mc.world();
		return !perf.occlusion().visible(e.getEntityId(), camX, camY, camZ, box.minX, box.minY, box.minZ, box.maxX, box.maxY,
				box.maxZ, blocks);
	}

	/** Voller, undurchsichtiger Block der Client-Welt. */
	private static final class Blocks implements Occlusion.Blocks {
		WorldClient world;

		@Override
		public boolean opaque(int x, int y, int z) {
			WorldClient w = world;
			if (w == null) return false;
			IBlockState state = w.getBlockState(new BlockPos(x, y, z));
			//? if >=1.9 {
			/*return state.isOpaqueCube() && state.isNormalCube();
			*///?} else
			return state.getBlock().isOpaqueCube() && state.getBlock().isNormalCube();
		}
	}

	// --- Himmel und Wetter (Forge IRenderHandler) ---

	private void renderHandlers(WorldClient world) {
		if (world == null || world.provider == null) return;
		boolean sky = perf.active(PerfFeature.SKY);
		if (sky && !skyReplaced) {
			savedSky = world.provider.getSkyRenderer();
			world.provider.setSkyRenderer(NOTHING);
			skyReplaced = true;
		} else if (!sky && skyReplaced) {
			if (world.provider.getSkyRenderer() == NOTHING) world.provider.setSkyRenderer(savedSky);
			skyReplaced = false;
		}
		boolean weather = perf.active(PerfFeature.WEATHER);
		if (weather && !weatherReplaced) {
			savedWeather = world.provider.getWeatherRenderer();
			world.provider.setWeatherRenderer(NOTHING);
			weatherReplaced = true;
		} else if (!weather && weatherReplaced) {
			if (world.provider.getWeatherRenderer() == NOTHING) world.provider.setWeatherRenderer(savedWeather);
			weatherReplaced = false;
		}
	}

	// --- Textur-Animationen ---

	@SuppressWarnings("unchecked")
	private void animations(Minecraft mc) {
		boolean off = perf.active(PerfFeature.TEXTURE_ANIMATIONS);
		if (!off && savedAnimations == null) return;
		TextureMap map = mc.getTextureMapBlocks();
		if (map == null) return;
		if (!animatedSearched) {
			animatedSearched = true;
			animatedSprites = onlyField(TextureMap.class, List.class);
			if (animatedSprites == null) log.accept("Leistung: Animationsliste der Block-Textur nicht gefunden – Textur-Animationen bleiben an");
		}
		if (animatedSprites == null) return;
		try {
			List<Object> list = (List<Object>) animatedSprites.get(map);
			if (list == null) return;
			if (off) {
				// Nach einem Ressourcen-Neuladen ist die Liste wieder voll → neu merken und leeren.
				if (!list.isEmpty()) {
					savedAnimations = new ArrayList<Object>(list);
					list.clear();
				}
			} else if (savedAnimations != null) {
				if (list.isEmpty()) list.addAll(savedAnimations);
				savedAnimations = null;
			}
		} catch (IllegalAccessException | RuntimeException e) {
			animatedSprites = null;
			error(e);
		}
	}

	/** Das einzige Instanzfeld dieses Typs, sonst null (keine Namen – die Release-Jar hat SRG-Namen). */
	private static Field onlyField(Class<?> owner, Class<?> type) {
		Field found = null;
		for (Field f : owner.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) || f.getType() != type) continue;
			if (found != null) return null;
			found = f;
		}
		if (found != null) found.setAccessible(true);
		return found;
	}

	// --- Partikel ---

	private void particles(Minecraft mc) {
		boolean limit = perf.particleLimitActive();
		boolean kinds = perf.hidesParticle(ParticleGate.Kind.EXPLOSION) || perf.hidesParticle(ParticleGate.Kind.RAIN)
				|| perf.hidesParticle(ParticleGate.Kind.SMOKE);
		if ((!limit && !kinds) || mc.effectRenderer == null) return;
		if (!particleLayersSearched) {
			particleLayersSearched = true;
			for (Field f : mc.effectRenderer.getClass().getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers()) && f.getType().isArray() && f.getType().getComponentType().isArray()) {
					f.setAccessible(true);
					particleLayers = f;
					break;
				}
			}
			if (particleLayers == null) log.accept("Leistung: Partikel-Listen nicht gefunden – Partikel bleiben unverändert");
		}
		if (particleLayers == null) return;
		try {
			Object[] layers = (Object[]) particleLayers.get(mc.effectRenderer);
			List<Collection<?>> all = new ArrayList<Collection<?>>();
			int total = 0;
			for (Object layer : layers) {
				if (!(layer instanceof Object[])) continue;
				for (Object list : (Object[]) layer) {
					if (!(list instanceof Collection)) continue;
					Collection<?> c = (Collection<?>) list;
					if (kinds) {
						for (Iterator<?> it = c.iterator(); it.hasNext(); ) {
							if (perf.hidesParticle(kindOf(it.next()))) {
								it.remove();
								particlesRemoved++;
							}
						}
					}
					total += c.size();
					all.add(c);
				}
			}
			int max = perf.modules().particleLimit.getInt();
			if (!limit || total <= max) return;
			// Älteste zuerst entfernen (vorne in den Listen), gleichmäßig über alle Listen.
			int excess = total - max;
			for (Collection<?> c : all) {
				int share = (int) Math.ceil(excess * (double) c.size() / total);
				Iterator<?> it = c.iterator();
				while (share-- > 0 && it.hasNext()) {
					it.next();
					it.remove();
					particlesRemoved++;
				}
			}
		} catch (IllegalAccessException | RuntimeException e) {
			particleLayers = null;
			error(e);
		}
	}

	private static ParticleGate.Kind kindOf(Object particle) {
		String n = particle == null ? "" : particle.getClass().getSimpleName();
		if (n.contains("Explo")) return ParticleGate.Kind.EXPLOSION;
		if (n.equals("EntityRainFX") || n.equals("ParticleRain")) return ParticleGate.Kind.RAIN;
		if (n.contains("Smoke")) return ParticleGate.Kind.SMOKE;
		return ParticleGate.Kind.OTHER;
	}

	// --- Vanilla-Optionen (GameOptions) ---

	@Override
	public int get(Opt opt) {
		GameSettings s = Minecraft.getMinecraft().gameSettings;
		if (s == null) return NONE;
		switch (opt) {
			case VSYNC:
				return s.enableVsync ? 1 : 0;
			case VIEW_DISTANCE:
				return s.renderDistanceChunks;
			case GRAPHICS:
				return s.fancyGraphics ? 1 : 0;
			case CLOUDS:
				return s.clouds;
			case PARTICLES:
				return s.particleSetting;
			case MIPMAP:
				return s.mipmapLevels;
			case SMOOTH_LIGHTING:
				return s.ambientOcclusion;
			case FULLSCREEN:
				return s.fullScreen ? 1 : 0;
			default:
				return NONE;
		}
	}

	@Override
	public boolean set(Opt opt, int raw) {
		Minecraft mc = Minecraft.getMinecraft();
		GameSettings s = mc.gameSettings;
		int current = get(opt);
		if (s == null || current == NONE) return false;
		int v = opt.clamp(raw);
		if (v == current) return true;
		switch (opt) {
			case VSYNC:
				s.setOptionValue(GameSettings.Options.ENABLE_VSYNC, 1);
				return true;
			case GRAPHICS:
				s.setOptionValue(GameSettings.Options.GRAPHICS, 1);
				return true;
			case VIEW_DISTANCE:
				s.setOptionFloatValue(GameSettings.Options.RENDER_DISTANCE, v);
				return true;
			case MIPMAP:
				s.setOptionFloatValue(GameSettings.Options.MIPMAP_LEVELS, v);
				return true;
			case CLOUDS:
				s.setOptionValue(GameSettings.Options.RENDER_CLOUDS, (v - current + 3) % 3);
				return true;
			case PARTICLES:
				s.setOptionValue(GameSettings.Options.PARTICLES, (v - current + 3) % 3);
				return true;
			case SMOOTH_LIGHTING:
				s.setOptionValue(GameSettings.Options.AMBIENT_OCCLUSION, (v - current + 3) % 3);
				return true;
			default:
				return false;
		}
	}

	@Override
	public boolean smoothLightingIsBoolean() {
		return false;
	}

	@Override
	public void save() {
		GameSettings s = Minecraft.getMinecraft().gameSettings;
		if (s != null) s.saveOptions();
	}

	@Override
	public String renderer() {
		try {
			String r = GL11.glGetString(GL11.GL_RENDERER);
			return r == null ? "" : r;
		} catch (RuntimeException | LinkageError e) {
			return "";
		}
	}

	@Override
	public String vendor() {
		try {
			String v = GL11.glGetString(GL11.GL_VENDOR);
			return v == null ? "" : v;
		} catch (RuntimeException | LinkageError e) {
			return "";
		}
	}

	// --- Fehler ---

	private void error(Throwable e) {
		long now = System.currentTimeMillis();
		if (now - errorLogged < 60_000) return;
		errorLogged = now;
		log.accept("Leistung: " + e);
	}

	public String stats() {
		return "frames=" + frames + ", particlesRemoved=" + particlesRemoved + ", entitiesCulled=" + entitiesCulled
				+ ", nameTagsHidden=" + nameTagsHidden + ", fogCleared=" + fogCleared + ", skyReplaced=" + skyReplaced
				+ ", weatherReplaced=" + weatherReplaced + ", animationsSaved=" + (savedAnimations == null ? 0 : savedAnimations.size());
	}
}
