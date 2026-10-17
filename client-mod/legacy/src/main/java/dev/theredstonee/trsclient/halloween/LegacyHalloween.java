package dev.theredstonee.trsclient.halloween;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.halloween.Halloween;
import dev.theredstonee.trsclient.core.halloween.HalloweenOverlay;
import dev.theredstonee.trsclient.core.halloween.Jumpscares;
import dev.theredstonee.trsclient.core.halloween.ScareOverlay;
import dev.theredstonee.trsclient.core.halloween.SpookyFx;
import dev.theredstonee.trsclient.core.module.HalloweenModules;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.core.online.PlayerInfo;
import dev.theredstonee.trsclient.online.LegacyOnline;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
//? if >=1.9 {
/*import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
*///?} else {
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
//?}
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.EnumSkyBlock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Halloween im Spiel für Forge 1.8.9–1.12.2: liefert {@link Jumpscares} je Tick den Zustand des Spielers, erzeugt die
 * Spuk-Effekte ({@link SpookyFx}) und zeichnet Effekte, Jumpscares und Toasts im HUD bzw. über dem offenen Bildschirm.
 * Reine Optik – nichts ändert das Spiel.
 */
public final class LegacyHalloween {
	private static final Jumpscares.Input INPUT = new Jumpscares.Input();
	private static int lastHurtTime;
	private static GuiScreen lastScreen;
	private static final Map<Integer, Integer> HURT = new HashMap<Integer, Integer>();
	private static boolean failed;

	private LegacyHalloween() {
	}

	private static HalloweenModules modules() {
		TrsClient c = TrsClient.get();
		return c == null ? null : c.modules().halloween;
	}

	// --- Tick ---

	/** Einmal je Client-Tick (Ende). */
	public static void tick(Minecraft mc) {
		if (failed) return;
		try {
			tickChecked(mc);
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			TrsClient.LOGGER.warn("Halloween: Tick abgeschaltet", e);
		}
	}

	private static void tickChecked(Minecraft mc) {
		HalloweenModules h = modules();
		GuiScreen screen = mc.currentScreen;
		if (h == null || !Halloween.active()) {
			lastScreen = screen;
			return;
		}
		long now = System.currentTimeMillis();
		Jumpscares.Input in = INPUT;
		EntityPlayer p = Mc.player();
		in.now = now;
		in.inWorld = p != null && Mc.world() != null;
		in.hurt = false;
		in.containerOpened = false;
		in.front = false;
		if (in.inWorld) {
			in.health = p.getHealth();
			int hurt = p.hurtTime;
			in.hurt = hurt > lastHurtTime;
			lastHurtTime = hurt;
			long day = Math.floorMod(Mc.world().getWorldTime(), 24000L);
			in.night = p.dimension == 0 && day >= 13000 && day < 23000;
			BlockPos eyes = new BlockPos(Math.floor(p.posX), Math.floor(p.posY + p.getEyeHeight()), Math.floor(p.posZ));
			in.skyLight = Mc.world().getLightFor(EnumSkyBlock.SKY, eyes);
			in.blockLight = Mc.world().getLightFor(EnumSkyBlock.BLOCK, eyes);
			in.front = mc.gameSettings.thirdPersonView == 2;
			in.containerOpened = screen != lastScreen && screen instanceof GuiContainer && !(screen instanceof GuiInventory)
					&& !(screen instanceof GuiContainerCreative);
			effects(h, p, now);
		} else {
			lastHurtTime = 0;
			HURT.clear();
		}
		lastScreen = screen;
		Jumpscares.get().tick(in);
		String scareSound = Jumpscares.get().takeSound();
		if (scareSound != null) playScare(mc, scareSound);
	}

	private static void effects(HalloweenModules h, EntityPlayer self, long now) {
		if (!h.on()) return;
		SpookyFx fx = SpookyFx.get();
		if (h.trail.get() && self.isSprinting()) fx.trail(self.getUniqueID(), self.posX, self.posY, self.posZ, self.rotationYaw, now);
		if (!h.others.get()) return;
		OnlineFeatures<Object> f = LegacyOnline.features();
		if (f == null) return;
		List<EntityPlayer> players = Mc.world().playerEntities;
		for (int i = 0, n = players.size(); i < n; i++) {
			EntityPlayer o = players.get(i);
			if (o == self) continue;
			double dx = o.posX - self.posX, dy = o.posY - self.posY, dz = o.posZ - self.posZ;
			if (dx * dx + dy * dy + dz * dz > SpookyFx.MAX_DISTANCE * SpookyFx.MAX_DISTANCE) continue;
			PlayerInfo info = f.online().info(o.getUniqueID());
			if (info == null || info.fx == 0) continue;
			if ((info.fx & PlayerInfo.FX_TRAIL) != 0 && o.isSprinting()) fx.trail(o.getUniqueID(), o.posX, o.posY, o.posZ, o.rotationYaw, now);
			if ((info.fx & PlayerInfo.FX_HITS) != 0 && o.isSwingInProgress) othersHit(o);
		}
		if (HURT.size() > 512) HURT.clear();
	}

	private static void othersHit(EntityPlayer attacker) {
		AxisAlignedBB box = new AxisAlignedBB(attacker.posX - 4.5, attacker.posY - 2, attacker.posZ - 4.5, attacker.posX + 4.5,
				attacker.posY + 3, attacker.posZ + 4.5);
		List<EntityLivingBase> near = Mc.world().getEntitiesWithinAABB(EntityLivingBase.class, box);
		for (int i = 0, n = near.size(); i < n; i++) {
			EntityLivingBase e = near.get(i);
			if (e == attacker || e == Mc.player()) continue;
			Integer before = HURT.put(e.getEntityId(), e.hurtTime);
			if (before != null && e.hurtTime > before) bats(e);
		}
	}

	private static void bats(Entity e) {
		SpookyFx.get().hit(e.posX, e.posY + e.height / 2, e.posZ);
	}

	/** Eigener Treffer bestätigt (aus LegacyQol): Fledermäuse am Ziel, 10-s-Pause der Jumpscares. */
	public static void onHit(Entity target) {
		try {
			Jumpscares.get().onDamageDealt(System.currentTimeMillis());
			HalloweenModules h = modules();
			if (h == null || !h.on() || !h.hits.get() || target == null) return;
			bats(target);
		} catch (RuntimeException | LinkageError ignored) {
			// nur Optik
		}
	}

	// --- Zeichnen ---

	/** Aus dem HUD (RenderGameOverlayEvent.Post ALL). */
	public static void hud(int width, int height, float partialTicks) {
		try {
			boolean active = Halloween.active();
			if (!active && !SpookyFx.get().any()) {
				if (Mc.screen() == null) HalloweenOverlay.hud(GfxCanvas.of(Gfx.of(width, height), Mc.font()), width, height);
				return;
			}
			GfxCanvas c = GfxCanvas.of(Gfx.of(width, height), Mc.font());
			long now = System.currentTimeMillis();
			SpookyFx fx = SpookyFx.get();
			fx.step(now);
			Entity view = Mc.viewEntity();
			if (view != null && Mc.world() != null && !Mc.hudHidden() && fx.any()) {
				double camX = Mc.lerp(view.lastTickPosX, view.posX, partialTicks);
				double camY = Mc.lerp(view.lastTickPosY, view.posY, partialTicks) + view.getEyeHeight();
				double camZ = Mc.lerp(view.lastTickPosZ, view.posZ, partialTicks);
				float yaw;
				float pitch;
				if (TrsClient.get().pvp().freelook().active()) {
					yaw = TrsClient.get().pvp().freelook().yaw();
					pitch = TrsClient.get().pvp().freelook().pitch();
				} else {
					yaw = (float) Mc.lerp(view.prevRotationYaw, view.rotationYaw, partialTicks);
					pitch = (float) Mc.lerp(view.prevRotationPitch, view.rotationPitch, partialTicks);
				}
				fx.draw(c, camX, camY, camZ, yaw, pitch, TrsClient.get().worldFov(), width, height);
			}
			if (Mc.screen() == null) {
				ScareOverlay.draw(c, width, height, now);
				HalloweenOverlay.hud(c, width, height);
			}
		} catch (RuntimeException | LinkageError ignored) {
			// nur Optik
		}
	}

	/** Nach dem ganzen Bildschirm (DrawScreenEvent.Post): Jumpscare (Kiste) und Toasts über Vanilla-Menüs. */
	public static void overScreen(GuiScreen s) {
		if (s == null || s instanceof TrsUiScreen) return;
		try {
			GfxCanvas c = GfxCanvas.of(Gfx.of(s.width, s.height), Mc.font());
			if (Halloween.active()) ScareOverlay.draw(c, s.width, s.height, System.currentTimeMillis());
			HalloweenOverlay.hud(c, s.width, s.height);
		} catch (RuntimeException | LinkageError ignored) {
			// nur Optik
		}
	}

	/** Registrierter Grusel-Ton (trsclient:scare_*), leise über alles. */
	private static void playScare(Minecraft mc, String id) {
		try {
			//? if >=1.9 {
			/*net.minecraft.util.SoundEvent ev = new net.minecraft.util.SoundEvent(new ResourceLocation("trsclient", id));
			mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(ev, 1.0F));
			*///?} else
			mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("trsclient", id), 1.0F));
		} catch (RuntimeException | LinkageError ignored) {
			// kein Ton
		}
	}
}
