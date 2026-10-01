package dev.theredstonee.trsclient.comfort;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.hunger.FoodReader;
import dev.theredstonee.trsclient.core.hunger.HudIcons;
import dev.theredstonee.trsclient.core.hunger.HungerOverlay;
import dev.theredstonee.trsclient.core.hunger.HungerState;
import dev.theredstonee.trsclient.core.module.ComfortModules;
import dev.theredstonee.trsclient.core.ui.Textures;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemAppleGold;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.FoodStats;
import net.minecraft.world.World;

/**
 * Hunger-Anzeige (Idee von AppleSkin) für Forge 1.8.9–1.12.2: sammelt je Bild die Werte für {@link HungerOverlay}
 * und zeichnet über die Vanilla-Hungerleiste (aus {@code HudManager.render}).
 */
public final class LegacyHunger {
	private static final HungerState STATE = new HungerState();
	private static final HungerOverlay OVERLAY = new HungerOverlay();
	private static HudIcons icons;

	private LegacyHunger() {
	}

	/** Aus {@code HudManager.render} (RenderGameOverlayEvent.Post ALL). */
	public static void hud(Gfx g, FontRenderer font) {
		try {
			ComfortModules m = TrsClient.get().modules().comfort;
			if (!m.hunger.isEnabled()) return;
			if (!fill(Mc.mc(), g)) return;
			OVERLAY.showSaturation = m.hungerSaturation.get();
			OVERLAY.showHeldFood = m.hungerHeldFood.get();
			OVERLAY.showHealth = m.hungerHealth.get();
			OVERLAY.showExhaustion = m.hungerExhaustion.get();
			GfxCanvas c = GfxCanvas.of(g, font);
			if (icons == null) icons = HudIcons.sheet(Textures.store());
			OVERLAY.draw(c, STATE, icons, System.currentTimeMillis());
		} catch (RuntimeException e) {
			// Nur Anzeige – ein Fehler darf das HUD nicht abbrechen.
		}
	}

	private static boolean fill(Minecraft mc, Gfx g) {
		HungerState s = STATE;
		s.reset();
		EntityPlayerSP p = Mc.player();
		World world = Mc.world();
		if (p == null || world == null || mc.playerController == null || !mc.playerController.shouldDrawHUD()) return false;
		s.survival = true;
		//? if >=1.9 {
		/*s.foodBar = !(p.getRidingEntity() instanceof EntityLivingBase);
		s.hungerEffect = p.isPotionActive(net.minecraft.init.MobEffects.HUNGER);
		s.regenEffect = p.isPotionActive(net.minecraft.init.MobEffects.REGENERATION);
		*///?} else {
		s.foodBar = !(p.ridingEntity instanceof EntityLivingBase);
		s.hungerEffect = p.isPotionActive(net.minecraft.potion.Potion.hunger);
		s.regenEffect = p.isPotionActive(net.minecraft.potion.Potion.regeneration);
		//?}
		//? if >=1.11 {
		/*s.modernRegen = true;
		*///?} else
		s.modernRegen = false;
		s.width = g.width();
		s.height = g.height();
		s.guiTicks = mc.ingameGUI.getUpdateCounter();
		FoodStats food = p.getFoodStats();
		s.food = food.getFoodLevel();
		s.saturation = food.getSaturationLevel();
		s.health = p.getHealth();
		s.maxHealth = p.getMaxHealth();
		s.absorption = p.getAbsorptionAmount();
		s.hardcore = world.getWorldInfo().isHardcoreModeEnabled();
		// Erschöpfung schickt der Server nicht – nur im Einzelspieler vom eingebauten Server lesbar.
		IntegratedServer server = mc.getIntegratedServer();
		if (server != null) {
			//? if >=1.9 {
			/*EntityPlayerMP sp = server.getPlayerList().getPlayerByUUID(p.getUniqueID());
			*///?} else
			EntityPlayerMP sp = server.getConfigurationManager().getPlayerByUUID(p.getUniqueID());
			if (sp != null) s.exhaustion = FoodReader.exhaustion(sp.getFoodStats());
		}
		//? if >=1.9 {
		/*if (!heldFood(p, p.getHeldItemMainhand())) heldFood(p, p.getHeldItemOffhand());
		*///?} else
		heldFood(p, p.getHeldItem());
		return true;
	}

	/** Trägt Essen aus {@code stack} ein, wenn der Spieler es jetzt essen kann. */
	private static boolean heldFood(EntityPlayer p, ItemStack stack) {
		if (Mc.isEmpty(stack) || !(stack.getItem() instanceof ItemFood)) return false;
		ItemFood food = (ItemFood) stack.getItem();
		// Goldäpfel sind immer essbar (das Feld dazu ist privat).
		if (!p.canEat(stack.getItem() instanceof ItemAppleGold)) return false;
		int heal = food.getHealAmount(stack);
		STATE.heldNutrition = heal;
		STATE.heldSaturation = heal * food.getSaturationModifier(stack) * 2f;
		STATE.heldCanEat = true;
		return true;
	}
}
