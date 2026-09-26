package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.shield.ShieldPosition;
import dev.theredstonee.trsclient.core.shield.ShieldPreset;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/**
 * Selbsttest „Schild-Position“ ({@code -PtrsAutotestOnly=shield}): Schild in der Nebenhand, Blick auf eine Wand mit
 * Ziel. Screenshots: „Seitlich“, Vanilla (Modul aus) zum Vergleich, Blocken mit „Seitlich“, Vanilla-Blocken,
 * durchsichtiges Blocken, Vorlage „Tief“ und die Einstellungsseite mit Live-Vorschau. Protokolliert den Verlauf der
 * Überblendung beim Hochnehmen und Absenken (weich, ohne Sprung).
 */
public final class ShieldTest {
	private int phase;
	private int wait;
	private final StringBuilder raise = new StringBuilder();
	private int samples;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (mc.player == null) return false;
		if (samples > 0) {
			samples--;
			raise.append(String.format(java.util.Locale.ROOT, " %.2f", modules.shield.blend(ShieldPosition.OFF_HAND)));
		}
		// Blick fest nach Süden auf die Wand (Mausbewegungen am Testrechner sollen die Bilder nicht verstellen).
		if (phase > 0) face(mc);
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase++) {
			case 0:
				// Keine Befehls-Meldungen im Chat (sonst verdecken sie die Screenshots).
				actions.command("gamerule logAdminCommands false");
				actions.command("gamerule sendCommandFeedback false");
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("effect clear @p");
				actions.command("execute as @p at @s run fill ~-10 ~ ~-3 ~10 ~8 ~16 air");
				actions.command("execute as @p at @s run fill ~-10 ~-1 ~-3 ~10 ~-1 ~16 grass_block");
				actions.command("execute as @p at @s run fill ~-10 ~ ~12 ~10 ~7 ~12 white_concrete");
				actions.command("execute as @p at @s run fill ~-1 ~1 ~11 ~1 ~3 ~11 red_concrete");
				actions.command("execute as @p at @s run fill ~-6 ~ ~11 ~-4 ~2 ~11 blue_concrete");
				actions.command("execute as @p at @s run fill ~4 ~ ~11 ~6 ~2 ~11 lime_concrete");
				//? if >=1.17 {
				actions.command("item replace entity @p weapon.offhand with shield");
				actions.command("item replace entity @p weapon.mainhand with diamond_sword");
				//?} else {
				/*actions.command("replaceitem entity @p weapon.offhand shield");
				actions.command("replaceitem entity @p weapon.mainhand diamond_sword");
				*///?}
				actions.command("execute as @p at @s run tp @s ~ ~ ~ 0 0");
				// Saubere Bilder: HUD-Module aus (der Autotest stellt am Ende alles wieder her).
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.crosshair.setEnabled(false);
				// Gleichmäßige Bildrate auch im Hintergrund (für den Verlauf der Überblendung).
				modules.dynamicFps.setEnabled(false);
				mc.mouseHandler.releaseMouse();
				modules.shieldPosition.reset();
				modules.shieldPosition.setEnabled(true);
				modules.shieldPreset.set(ShieldPreset.SIDE);
				modules.shield.resync();
				Mc.setScreen(null);
				wait = 40;
				return true;
			case 1:
				dev.theredstonee.trsclient.compat.ChatLines.chat().clearMessages(false);
				wait = 3;
				return true;
			case 2:
				actions.shot("trsclient-shield-side");
				modules.shieldPosition.setEnabled(false);
				wait = 5;
				return true;
			case 3:
				actions.shot("trsclient-shield-vanilla");
				modules.shieldPosition.setEnabled(true);
				wait = 3;
				return true;
			case 4:
				// Blocken beginnen; die Überblendung wird je Tick mitgeschrieben.
				startBlocking(mc);
				samples = 8;
				wait = 12;
				return true;
			case 5:
				hold(mc.options.keyUse, true);
				TrsClient.LOGGER.info("[Autotest] Schild hochnehmen (Überblendung je Tick):{} – blockt={}", raise, mc.player.isUsingItem());
				raise.setLength(0);
				actions.shot("trsclient-shield-block");
				modules.shieldPosition.setEnabled(false);
				wait = 5;
				return true;
			case 6:
				hold(mc.options.keyUse, true);
				actions.shot("trsclient-shield-block-vanilla");
				modules.shieldPosition.setEnabled(true);
				modules.shieldTransparent.set(true);
				modules.shieldOpacity.set(40);
				wait = 5;
				return true;
			case 7:
				hold(mc.options.keyUse, true);
				actions.shot("trsclient-shield-block-transparent");
				modules.shieldTransparent.set(false);
				hold(mc.options.keyUse, false);
				samples = 8;
				wait = 12;
				return true;
			case 8:
				TrsClient.LOGGER.info("[Autotest] Schild absenken (Überblendung je Tick):{} – blockt={}", raise, mc.player.isUsingItem());
				raise.setLength(0);
				modules.shieldPreset.set(ShieldPreset.LOW);
				wait = 5;
				return true;
			case 9:
				actions.shot("trsclient-shield-low");
				modules.shieldPreset.set(ShieldPreset.SIDE);
				Mc.setScreen(new TrsMenuScreen(null).select(modules.shieldPosition));
				wait = 20;
				return true;
			case 10:
				actions.shot("trsclient-shield-menu");
				Mc.setScreen(null);
				modules.shieldPosition.reset();
				return false;
			default:
				return false;
		}
	}

	private static void face(Minecraft mc) {
		//? if >=1.17 {
		mc.player.setYRot(0);
		mc.player.setXRot(0);
		//?} else {
		/*mc.player.yRot = 0;
		mc.player.xRot = 0;
		*///?}
		mc.player.yHeadRot = 0;
	}

	private static void startBlocking(Minecraft mc) {
		hold(mc.options.keyUse, true);
		//? if >=1.19 {
		mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
		//?} else {
		/*mc.gameMode.useItem(mc.player, mc.level, InteractionHand.OFF_HAND);
		*///?}
	}

	private static void hold(KeyMapping key, boolean down) {
		//? if >=1.15 {
		key.setDown(down);
		//?} else {
		/*((dev.theredstonee.trsclient.mixin.KeyMappingAccessor) key).trsclient$setDown(down);
		*///?}
	}
}
