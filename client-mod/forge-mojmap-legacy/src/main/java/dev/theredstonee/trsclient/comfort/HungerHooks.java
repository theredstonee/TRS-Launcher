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
import net.minecraft.client.gui.Font;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Hunger-Anzeige (Idee von AppleSkin): sammelt je Bild die Werte für {@link HungerOverlay} und zeichnet über die
 * Vanilla-Hungerleiste. Aus dem HudManager nach Minecrafts HUD. Gleiche Logik wie in den anderen Mojmap-Bäumen (hier nur 1.14.4–1.19.4).
 */
public final class HungerHooks {
	private static final HungerState STATE = new HungerState();
	private static final HungerOverlay OVERLAY = new HungerOverlay();
	private static HudIcons icons;

	private HungerHooks() {
	}

	/** Aus {@code HudManager.drawAll} (nur wenn das HUD sichtbar ist). */
	public static void hud(Gfx g, Font font) {
		try {
			ComfortModules m = TrsClient.get().modules().comfort;
			if (!m.hunger.isEnabled()) return;
			Minecraft mc = Minecraft.getInstance();
			if (!fill(mc, g)) return;
			OVERLAY.showSaturation = m.hungerSaturation.get();
			OVERLAY.showHeldFood = m.hungerHeldFood.get();
			OVERLAY.showHealth = m.hungerHealth.get();
			OVERLAY.showExhaustion = m.hungerExhaustion.get();
			GfxCanvas c = GfxCanvas.of(g, font);
			OVERLAY.draw(c, STATE, icons(), System.currentTimeMillis());
		} catch (RuntimeException e) {
			// Nur Anzeige – ein Fehler darf das HUD nicht abbrechen.
		}
	}

	private static HudIcons icons() {
		if (icons == null) {
			icons = HudIcons.sheet(Textures.store());
		}
		return icons;
	}

	/** Werte des Spielers; false = Vanilla zeigt hier keine Herzen/Hungerleiste. */
	private static boolean fill(Minecraft mc, Gfx g) {
		HungerState s = STATE;
		s.reset();
		Player p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null || !mc.gameMode.canHurtPlayer()) return false;
		s.survival = true;
		s.foodBar = !(p.getVehicle() instanceof LivingEntity);
		s.width = g.width();
		s.height = g.height();
		s.guiTicks = Mc.guiTicks();
		FoodData food = p.getFoodData();
		s.food = food.getFoodLevel();
		s.saturation = food.getSaturationLevel();
		s.health = p.getHealth();
		s.maxHealth = p.getMaxHealth();
		s.absorption = p.getAbsorptionAmount();
		s.hardcore = mc.level.getLevelData().isHardcore();
		s.hungerEffect = p.hasEffect(MobEffects.HUNGER);
		s.regenEffect = p.hasEffect(MobEffects.REGENERATION);
		s.modernRegen = true;
		// Erschöpfung schickt der Server nicht – nur im Einzelspieler vom eingebauten Server lesbar.
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			ServerPlayer sp = server.getPlayerList().getPlayer(p.getUUID());
			if (sp != null) s.exhaustion = FoodReader.exhaustion(sp.getFoodData());
		}
		if (!heldFood(p, p.getMainHandItem())) heldFood(p, p.getOffhandItem());
		return true;
	}

	/** Trägt Essen aus {@code stack} ein, wenn der Spieler es jetzt essen kann. */
	private static boolean heldFood(Player p, ItemStack stack) {
		if (stack.isEmpty()) return false;
		FoodProperties food = stack.getItem().isEdible() ? stack.getItem().getFoodProperties() : null;
		if (food == null || !p.canEat(food.canAlwaysEat())) return false;
		STATE.heldNutrition = food.getNutrition();
		STATE.heldSaturation = food.getNutrition() * food.getSaturationModifier() * 2f;
		STATE.heldCanEat = true;
		return true;
	}
}
