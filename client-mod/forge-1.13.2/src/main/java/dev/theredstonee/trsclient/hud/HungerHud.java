package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.hunger.FoodReader;
import dev.theredstonee.trsclient.core.hunger.HudIcons;
import dev.theredstonee.trsclient.core.hunger.HungerOverlay;
import dev.theredstonee.trsclient.core.hunger.HungerState;
import dev.theredstonee.trsclient.core.module.ComfortModules;
import dev.theredstonee.trsclient.core.ui.Textures;
import dev.theredstonee.trsclient.ui.BrandCanvas;
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

/**
 * Hunger-Anzeige (Idee von AppleSkin) für Forge 1.13.2: sammelt je Bild die Werte für {@link HungerOverlay} und
 * zeichnet über die Vanilla-Hungerleiste (aus {@code HudManager.render}).
 */
public final class HungerHud {
	private static final HungerState STATE = new HungerState();
	private static final HungerOverlay OVERLAY = new HungerOverlay();
	private static HudIcons icons;

	private HungerHud() {
	}

	/** Aus {@code HudManager.render}; Größe in GUI-Pixeln. */
	public static void render(FontRenderer font, int sw, int sh) {
		try {
			ComfortModules m = TrsClient.get().modules().comfort;
			if (!TrsClient.supported(m.hunger) || !m.hunger.isEnabled()) return;
			if (!fill(Minecraft.getInstance(), sw, sh)) return;
			OVERLAY.showSaturation = m.hungerSaturation.get();
			OVERLAY.showHeldFood = m.hungerHeldFood.get();
			OVERLAY.showHealth = m.hungerHealth.get();
			OVERLAY.showExhaustion = m.hungerExhaustion.get();
			BrandCanvas c = BrandCanvas.of(font);
			if (icons == null) icons = HudIcons.sheet(Textures.store());
			OVERLAY.draw(c, STATE, icons, System.currentTimeMillis());
		} catch (RuntimeException e) {
			// Nur Anzeige – ein Fehler darf das HUD nicht abbrechen.
		}
	}

	private static boolean fill(Minecraft mc, int sw, int sh) {
		HungerState s = STATE;
		s.reset();
		EntityPlayerSP p = mc.player;
		if (p == null || mc.world == null || mc.playerController == null || !mc.playerController.shouldDrawHUD()) return false;
		s.survival = true;
		s.foodBar = !(p.getRidingEntity() instanceof EntityLivingBase);
		s.hungerEffect = p.isPotionActive(net.minecraft.init.MobEffects.HUNGER);
		s.regenEffect = p.isPotionActive(net.minecraft.init.MobEffects.REGENERATION);
		s.modernRegen = true;
		s.width = sw;
		s.height = sh;
		s.guiTicks = mc.ingameGUI.getTicks();
		FoodStats food = p.getFoodStats();
		s.food = food.getFoodLevel();
		s.saturation = food.getSaturationLevel();
		s.health = p.getHealth();
		s.maxHealth = p.getMaxHealth();
		s.absorption = p.getAbsorptionAmount();
		s.hardcore = mc.world.getWorldInfo().isHardcore();
		// Erschöpfung schickt der Server nicht – nur im Einzelspieler vom eingebauten Server lesbar.
		IntegratedServer server = mc.getIntegratedServer();
		if (server != null) {
			EntityPlayerMP sp = server.getPlayerList().getPlayerByUUID(p.getUniqueID());
			if (sp != null) s.exhaustion = FoodReader.exhaustion(sp.getFoodStats());
		}
		if (!heldFood(p, p.getHeldItemMainhand())) heldFood(p, p.getHeldItemOffhand());
		return true;
	}

	private static boolean heldFood(EntityPlayer p, ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof ItemFood)) return false;
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
