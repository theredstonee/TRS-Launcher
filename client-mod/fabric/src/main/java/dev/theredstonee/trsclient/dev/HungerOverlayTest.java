package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Selbsttest Hunger-Anzeige ({@code -PtrsAutotestOnly=hunger}): halber Hunger + Steak in der Hand (Vorschau blinkt –
 * zwei Bilder), danach ohne Essen mit Sättigung und Erschöpfung. Screenshots trsclient-&lt;mc&gt;-hunger-*.png.
 */
public final class HungerOverlayTest {
	private int phase;
	private int wait;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase++) {
			case 0:
				if (mc.player == null) return false;
				actions.command("gamerule sendCommandFeedback false");
				actions.command("gamerule naturalRegeneration false");
				actions.command("gamemode survival @p");
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("clear @p");
				actions.command("item replace entity @p weapon.mainhand with cooked_beef 16");
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.comfort.hunger.setEnabled(true);
				food(mc, 9, 3f, 0.5f, 11f);
				wait = 40;
				return true;
			case 1:
				dev.theredstonee.trsclient.TrsClient.LOGGER.info("[Autotest] Hunger: food={} sat={} health={} hand={}",
						mc.player.getFoodData().getFoodLevel(), mc.player.getFoodData().getSaturationLevel(), mc.player.getHealth(),
						mc.player.getMainHandItem());
				actions.shot("trsclient-hunger-held-a");
				wait = 7;
				return true;
			case 2:
				actions.shot("trsclient-hunger-held-b");
				wait = 7;
				return true;
			case 3:
				actions.shot("trsclient-hunger-held-c");
				actions.command("clear @p");
				food(mc, 16, 9.5f, 2.6f, 20f);
				wait = 30;
				return true;
			case 4:
				dev.theredstonee.trsclient.TrsClient.LOGGER.info("[Autotest] Hunger: food={} sat={} health={} hand={}",
						mc.player.getFoodData().getFoodLevel(), mc.player.getFoodData().getSaturationLevel(), mc.player.getHealth(),
						mc.player.getMainHandItem());
				actions.shot("trsclient-hunger-saturation");
				actions.command("gamerule naturalRegeneration true");
				return false;
			default:
				return false;
		}
	}

	/** Hunger, Sättigung, Erschöpfung und Leben über den eingebauten Server setzen (der Client bekommt sie geschickt). */
	private static void food(Minecraft mc, int food, float saturation, float exhaustion, float health) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) return;
		java.util.UUID id = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) return;
			p.getFoodData().setFoodLevel(food);
			p.getFoodData().setSaturation(saturation);
			p.getFoodData().addExhaustion(exhaustion);
			p.setHealth(health);
		});
	}
}
