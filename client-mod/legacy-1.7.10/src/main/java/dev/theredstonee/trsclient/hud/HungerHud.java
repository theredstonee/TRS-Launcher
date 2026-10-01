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
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemAppleGold;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.FoodStats;

/**
 * Hunger-Anzeige (Idee von AppleSkin) für Forge 1.7.10: sammelt je Bild die Werte für {@link HungerOverlay} und
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
			if (!fill(Minecraft.getMinecraft(), sw, sh)) return;
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
		EntityClientPlayerMP p = mc.thePlayer;
		if (p == null || mc.theWorld == null || mc.playerController == null || !mc.playerController.shouldDrawHUD()) return false;
		s.survival = true;
		s.foodBar = !(p.ridingEntity instanceof EntityLivingBase);
		s.hungerEffect = p.isPotionActive(Potion.hunger);
		s.regenEffect = p.isPotionActive(Potion.regeneration);
		s.modernRegen = false;
		s.width = sw;
		s.height = sh;
		s.guiTicks = mc.ingameGUI.getUpdateCounter();
		FoodStats food = p.getFoodStats();
		s.food = food.getFoodLevel();
		s.saturation = food.getSaturationLevel();
		s.health = p.getHealth();
		s.maxHealth = p.getMaxHealth();
		s.absorption = p.getAbsorptionAmount();
		s.hardcore = mc.theWorld.getWorldInfo().isHardcoreModeEnabled();
		// Erschöpfung schickt der Server nicht – nur im Einzelspieler vom eingebauten Server lesbar.
		IntegratedServer server = mc.getIntegratedServer();
		if (server != null) {
			EntityPlayerMP sp = server.getConfigurationManager().func_152612_a(p.getCommandSenderName());
			if (sp != null) s.exhaustion = FoodReader.exhaustion(sp.getFoodStats());
		}
		heldFood(p, p.getHeldItem());
		return true;
	}

	private static boolean heldFood(EntityPlayer p, ItemStack stack) {
		if (stack == null || !(stack.getItem() instanceof ItemFood)) return false;
		ItemFood food = (ItemFood) stack.getItem();
		// Goldäpfel sind immer essbar (das Feld dazu ist privat).
		if (!p.canEat(stack.getItem() instanceof ItemAppleGold)) return false;
		int heal = food.func_150905_g(stack);
		STATE.heldNutrition = heal;
		STATE.heldSaturation = heal * food.func_150906_h(stack) * 2f;
		STATE.heldCanEat = true;
		return true;
	}
}
