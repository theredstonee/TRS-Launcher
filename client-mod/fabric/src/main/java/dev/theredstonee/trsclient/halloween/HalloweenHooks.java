package dev.theredstonee.trsclient.halloween;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.halloween.Halloween;
import dev.theredstonee.trsclient.core.halloween.HalloweenOverlay;
import dev.theredstonee.trsclient.core.halloween.Jumpscares;
import dev.theredstonee.trsclient.core.halloween.Miyako;
import dev.theredstonee.trsclient.core.halloween.MiyakoOverlay;
import dev.theredstonee.trsclient.core.halloween.ScareOverlay;
import dev.theredstonee.trsclient.core.halloween.SpookyFx;
import dev.theredstonee.trsclient.core.module.HalloweenModules;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.core.online.PlayerInfo;
import dev.theredstonee.trsclient.online.OnlineHooks;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Halloween im Spiel (Mojmap-Bäume, identische Datei in fabric/neoforge/forge; forge-mojmap-legacy per Mini-Stonecutter):
 * liefert {@link Jumpscares} je Tick den Zustand des Spielers, erzeugt die Spuk-Effekte ({@link SpookyFx}) und zeichnet
 * Effekte, Jumpscares und Toasts im HUD bzw. über dem offenen Bildschirm. Reine Optik – nichts ändert das Spiel.
 */
public final class HalloweenHooks {
	/** Gibt es den Haken nach jedem Bildschirm (Kisten-Schreck über dem Behälter)? Wie {@code SocialHooks}. */
	//? if >=1.19.4 {
	static final boolean SCREEN_HOOK = true;
	//?} else
	/*static final boolean SCREEN_HOOK = false;*/

	private static final Jumpscares.Input INPUT = new Jumpscares.Input();
	private static int lastHurtTime;
	private static boolean prevInWorld;
	private static Screen lastScreen;
	/** Schadens-Animation je Entity (Treffer anderer Spieler mit Spuk-Treffern erkennen). */
	private static final Map<Integer, Integer> HURT = new HashMap<Integer, Integer>();
	private static boolean failed;

	private HalloweenHooks() {
	}

	private static HalloweenModules modules() {
		TrsClient c = TrsClient.get();
		return c == null ? null : c.modules().halloween;
	}

	// --- Tick ---

	/** Einmal je Client-Tick (aus TrsClient). */
	public static void tick(Minecraft mc) {
		if (failed) return;
		try {
			tickChecked(mc);
		} catch (RuntimeException | LinkageError e) {
			// Nur Optik – nie das Spiel stören; einmal melden, dann ruhen.
			failed = true;
			TrsClient.LOGGER.warn("Halloween: Tick abgeschaltet", e);
		}
	}

	private static void tickChecked(Minecraft mc) {
		HalloweenModules h = modules();
		if (h == null || !Halloween.active()) {
			lastScreen = Mc.screen();
			return;
		}
		long now = System.currentTimeMillis();
		Jumpscares.Input in = INPUT;
		Player p = mc.player;
		Screen screen = Mc.screen();
		in.now = now;
		in.inWorld = p != null && mc.level != null;
		in.hurt = false;
		in.containerOpened = false;
		in.front = false;
		if (in.inWorld) {
			in.health = p.getHealth();
			int hurt = p.hurtTime;
			in.hurt = hurt > lastHurtTime;
			lastHurtTime = hurt;
			String dim = Mc.dimensionId();
			boolean overworld = dim.isEmpty() || dim.endsWith("overworld");
			long day = Math.floorMod(dayTime(mc), 24000L);
			in.night = overworld && day >= 13000 && day < 23000;
			BlockPos eyes = new BlockPos((int) Math.floor(x(p)), (int) Math.floor(y(p) + p.getEyeHeight()), (int) Math.floor(z(p)));
			in.skyLight = mc.level.getChunkSource().getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(eyes);
			in.blockLight = mc.level.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(eyes);
			in.front = cameraMode() == 2;
			in.containerOpened = SCREEN_HOOK && screen != lastScreen && container(screen);
			effects(mc, h, p, now);
		} else {
			lastHurtTime = 0;
			HURT.clear();
		}
		lastScreen = screen;
		Jumpscares.get().tick(in);
		String scareSound = Jumpscares.get().takeSound();
		if (scareSound != null) playScare(scareSound);
		// MiyakoMC (persönlich, nur seine UUID): Timer beim Betreten einer Welt bewaffnen, Bild-Schreck auslösen.
		if (in.inWorld && !prevInWorld) Miyako.get().armTimer(now);
		prevInWorld = in.inWorld;
		Miyako.get().tick(now, in.inWorld, in.health, in.hurt);
		String miyakoSound = Miyako.get().takeImageSound();
		if (miyakoSound != null) playScare(miyakoSound);
		// Kann die Version das Video nicht (alles ausser modernem Fabric), das Intro still überspringen.
		if (Miyako.get().wantsIntro() && !Miyako.get().videoSupported()) Miyako.get().introSkipped();
	}

	/** Behälter (Kiste, Fass …), nicht das eigene Inventar. */
	private static boolean container(Screen s) {
		return s instanceof AbstractContainerScreen && !(s instanceof InventoryScreen) && !(s instanceof CreativeModeInventoryScreen);
	}

	private static long dayTime(Minecraft mc) {
		//? if >=26.1 {
		/*return mc.level.getDefaultClockTime();
		*///?} else
		return mc.level.getDayTime();
	}

	/** Spuk-Spur (eigene + andere mit {@code fx}) und Treffer anderer Spieler mit Spuk-Treffern. */
	private static void effects(Minecraft mc, HalloweenModules h, Player self, long now) {
		if (!h.on()) return;
		SpookyFx fx = SpookyFx.get();
		if (h.trail.get() && self.isSprinting()) fx.trail(self.getUUID(), x(self), y(self), z(self), yRot(self), now);
		if (!h.others.get()) return;
		OnlineFeatures<Object> f = OnlineHooks.features();
		if (f == null) return;
		List<AbstractClientPlayer> players = mc.level.players();
		double sx = x(self), sy = y(self), sz = z(self);
		for (int i = 0, n = players.size(); i < n; i++) {
			AbstractClientPlayer o = players.get(i);
			if (o == self) continue;
			double dx = x(o) - sx, dy = y(o) - sy, dz = z(o) - sz;
			if (dx * dx + dy * dy + dz * dz > SpookyFx.MAX_DISTANCE * SpookyFx.MAX_DISTANCE) continue;
			PlayerInfo info = f.online().info(o.getUUID());
			if (info == null || info.fx == 0) continue;
			if ((info.fx & PlayerInfo.FX_TRAIL) != 0 && o.isSprinting()) fx.trail(o.getUUID(), x(o), y(o), z(o), yRot(o), now);
			if ((info.fx & PlayerInfo.FX_HITS) != 0 && swinging(o)) othersHit(mc, o);
		}
		if (HURT.size() > 512) HURT.clear();
	}

	/** Schwingt der Spieler gerade (Schlag)? Ab 26.3 ohne öffentliches Feld – dann zählt jeder nahe Spieler. */
	private static boolean swinging(Player p) {
		//? if >=26.3 {
		/*return true;
		*///?} else
		return p.swinging;
	}

	/** Ein schwingender Spieler mit Spuk-Treffern: getroffene Lebewesen in 4,5 Blöcken bekommen Fledermäuse. */
	private static void othersHit(Minecraft mc, AbstractClientPlayer attacker) {
		double x = x(attacker), y = y(attacker), z = z(attacker);
		AABB box = new AABB(x - 4.5, y - 2, z - 4.5, x + 4.5, y + 3, z + 4.5);
		List<LivingEntity> near = mc.level.getEntitiesOfClass(LivingEntity.class, box);
		for (int i = 0, n = near.size(); i < n; i++) {
			LivingEntity e = near.get(i);
			if (e == attacker || e == mc.player) continue;
			Integer before = HURT.put(e.getId(), e.hurtTime);
			if (before != null && e.hurtTime > before) bats(e);
		}
	}

	private static void bats(Entity e) {
		SpookyFx.get().hit(x(e), y(e) + e.getBbHeight() / 2, z(e));
	}

	/** Eigener Treffer bestätigt (aus QolHooks): Fledermäuse am Ziel, 10-s-Pause der Jumpscares. */
	public static void onHit(Object target) {
		try {
			Jumpscares.get().onDamageDealt(System.currentTimeMillis());
			HalloweenModules h = modules();
			if (h == null || !h.on() || !h.hits.get() || !(target instanceof Entity)) return;
			bats((Entity) target);
		} catch (RuntimeException | LinkageError ignored) {
			// nur Optik
		}
	}

	// --- Zeichnen ---

	/** Aus dem HUD (jedes Bild): Effekte in der Welt, Jumpscare, Toasts. */
	public static void hud(Gfx g) {
		if (!Halloween.active() && !SpookyFx.get().any()) {
			if (Mc.screen() == null) HalloweenOverlay.hud(GfxCanvas.of(g, Mc.mc().font), g.width(), g.height());
			return;
		}
		try {
			Minecraft mc = Mc.mc();
			GfxCanvas c = GfxCanvas.of(g, mc.font);
			long now = System.currentTimeMillis();
			SpookyFx fx = SpookyFx.get();
			fx.step(now);
			if (mc.player != null && mc.level != null && !Mc.hudHidden() && fx.any()) {
				fx.draw(c, Mc.cameraX(), Mc.cameraY(), Mc.cameraZ(), Mc.cameraYaw(), Mc.cameraPitch(), TrsClient.get().worldFov(),
						g.width(), g.height());
			}
			if (Mc.screen() == null) {
				ScareOverlay.draw(c, g.width(), g.height(), now);
				MiyakoOverlay.draw(c, g.width(), g.height(), now);
				HalloweenOverlay.hud(c, g.width(), g.height());
			}
		} catch (RuntimeException | LinkageError ignored) {
			// nur Optik
		}
	}

	/** Nach dem ganzen Bildschirm (ab 1.19.4): Jumpscare (Kiste) und Toasts über Vanilla-Menüs. */
	public static void overScreen(Gfx g) {
		Screen s = Mc.screen();
		if (s == null || s instanceof TrsUiScreen) return;
		try {
			GfxCanvas c = GfxCanvas.of(g, Mc.mc().font);
			long now = System.currentTimeMillis();
			if (Halloween.active()) {
				ScareOverlay.draw(c, g.width(), g.height(), now);
				MiyakoOverlay.draw(c, g.width(), g.height(), now);
			}
			HalloweenOverlay.hud(c, g.width(), g.height());
		} catch (RuntimeException | LinkageError ignored) {
			// nur Optik
		}
	}

	/** Weltwechsel. */
	public static void onWorldChange() {
		SpookyFx.get().clear();
		HURT.clear();
	}

	// --- Versionsweichen (eigene Kopie, weil compat/Mc je Baum andere Hilfen hat) ---

	private static double x(Entity e) {
		//? if >=1.15 {
		return e.getX();
		//?} else
		/*return e.x;*/
	}

	private static double y(Entity e) {
		//? if >=1.15 {
		return e.getY();
		//?} else
		/*return e.y;*/
	}

	private static double z(Entity e) {
		//? if >=1.15 {
		return e.getZ();
		//?} else
		/*return e.z;*/
	}

	private static float yRot(Entity e) {
		//? if >=1.17 {
		return e.getYRot();
		//?} else
		/*return e.yRot;*/
	}

	/** Perspektive: 0 = Ego, 1 = von hinten, 2 = von vorne. */
	private static int cameraMode() {
		//? if >=1.16 {
		return Mc.mc().options.getCameraType().ordinal();
		//?} else
		/*return Mc.mc().options.thirdPersonView;*/
	}

	/** Registrierter Grusel-Ton (trsclient:scare_*), über alles leise abgespielt. */
	private static void playScare(String id) {
		try {
			//? if >=1.21.11 {
			/*SoundEvent ev = SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath("trsclient", id));
			*///?} elif >=1.21 {
			SoundEvent ev = SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("trsclient", id));
			//?} elif >=1.19.3 {
			/*SoundEvent ev = SoundEvent.createVariableRangeEvent(new net.minecraft.resources.ResourceLocation("trsclient", id));
			*///?} else
			/*SoundEvent ev = new SoundEvent(new net.minecraft.resources.ResourceLocation("trsclient", id));*/
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, 0.9f, 1.0f));
		} catch (RuntimeException | LinkageError ignored) {
			// kein Ton
		}
	}
}
