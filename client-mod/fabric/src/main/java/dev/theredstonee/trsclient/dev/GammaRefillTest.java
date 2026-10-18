package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;

/**
 * Selbsttest Gamma + Automatisch nachlegen ({@code -PtrsAutotestOnly=gammarefill}).
 * <ul>
 *   <li>Gamma: Mitternacht ohne Modul (dunkel), dann mit 300 % und 1000 % – wirkt sofort; dazu der Hinweis-Text.</li>
 *   <li>Nachlegen: ein Stapel Stein läuft leer (per Befehl geleert) → aus dem Inventar nachgelegt;
 *   danach ein „zerbrochenes“ Werkzeug (Slot geleert) → durch die Ersatz-Spitzhacke ersetzt.</li>
 * </ul>
 * Screenshots: {@code trsclient-<mc>-gamma-*.png} und {@code trsclient-<mc>-refill-*.png}.
 */
public final class GammaRefillTest {
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
				if (mc.player == null || mc.level == null) return false;
				actions.command("gamerule sendCommandFeedback false");
				actions.command("gamemode survival @p");
				actions.command("effect clear @p");
				actions.command("weather clear");
				actions.command("clear @p");
				// Für ruhige Bilder nur das Nötigste.
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.fullbright.setEnabled(false);
				modules.setFullbrightBoost(false);
				actions.command("time set midnight");
				wait = 40;
				return true;
			case 1:
				refreshLight(mc);
				wait = 3;
				return true;
			case 2:
				dev.theredstonee.trsclient.TrsClient.LOGGER.info("[Autotest] Gamma aus: override={} hooks(updateLightTexture)={}",
						modules.gammaOverride(), HookStats.lightmap);
				actions.shot("trsclient-gamma-off");
				modules.fullbright.setEnabled(true);
				modules.fullbrightLevel.set(300);
				refreshLight(mc);
				wait = 5;
				return true;
			case 3:
				actions.shot("trsclient-gamma-300");
				modules.fullbrightLevel.set(1000);
				refreshLight(mc);
				dev.theredstonee.trsclient.TrsClient.LOGGER.info("[Autotest] Gamma 1000 %: override={} hooks(updateLightTexture)={}",
						modules.gammaOverride(), HookStats.lightmap);
				wait = 5;
				return true;
			case 4:
				actions.shot("trsclient-gamma-1000");
				// Hinweis-Text (wie beim Tastendruck).
				modules.fullbrightLevel.set(300);
				modules.setFullbrightBoost(false);
				Mc.actionBar(Mc.text(modules.gammaToast()));
				wait = 5;
				return true;
			case 5:
				actions.shot("trsclient-gamma-toast");
				// --- Automatisch nachlegen: Block-Stapel ---
				modules.fullbright.setEnabled(true);
				modules.fullbrightLevel.set(1000); // hell, damit das Inventar gut zu sehen ist
				actions.command("time set day");
				modules.autoRefill.setEnabled(true);
				actions.command("clear @p");
				actions.command("item replace entity @p hotbar.0 with stone 1");
				actions.command("item replace entity @p inventory.0 with stone 64");
				wait = 30;
				return true;
			case 6:
				openInventoryShot(mc, actions, "trsclient-refill-before");
				wait = 10;
				return true;
			case 7:
				// Inventar wieder zu – im Spiel merkt sich der Tick-Haken jetzt „Stein“ im ausgewählten Slot
				// (bei offenem Inventar/Fair Play pausiert das Nachlegen und vergisst den Merker).
				Mc.setScreen(null);
				wait = 20;
				return true;
			case 8:
				// Letzter Block gesetzt → ausgewählter Slot leer.
				actions.command("item replace entity @p hotbar.0 with air");
				wait = 20; // der Tick-Haken legt aus inventory.0 nach
				return true;
			case 9:
				logHand(mc, "refill-stone");
				openInventoryShot(mc, actions, "trsclient-refill-after");
				wait = 10;
				return true;
			case 10:
				// --- Automatisch nachlegen: zerbrochenes Werkzeug ---
				Mc.setScreen(null);
				actions.command("clear @p");
				actions.command("item replace entity @p hotbar.0 with diamond_pickaxe 1");
				actions.command("item replace entity @p inventory.0 with diamond_pickaxe 1");
				wait = 30;
				return true;
			case 11:
				openInventoryShot(mc, actions, "trsclient-refill-tool-before");
				wait = 10;
				return true;
			case 12:
				Mc.setScreen(null); // Merker „Spitzhacke“ wieder aufbauen
				wait = 20;
				return true;
			case 13:
				actions.command("item replace entity @p hotbar.0 with air"); // Werkzeug „zerbrochen“
				wait = 20;
				return true;
			case 14:
				logHand(mc, "refill-tool");
				openInventoryShot(mc, actions, "trsclient-refill-tool-after");
				wait = 10;
				return true;
			case 15:
				Mc.setScreen(null);
				actions.command("gamerule sendCommandFeedback true");
				return false;
			default:
				return false;
		}
	}

	/** Öffnet kurz das Inventar und macht ein Bild (zeigt den ausgewählten Slot und den Nachschub). */
	private static void openInventoryShot(Minecraft mc, CapeTest.Actions actions, String name) {
		if (mc.player != null) Mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
		actions.shot(name);
	}

	/**
	 * Erzwingt eine Neuberechnung der Lightmap (im Spiel passiert das beim normalen Weltticken laufend; im
	 * stillstehenden Selbsttest hilft dieser Anstoß, damit das Gamma-Bild sofort stimmt). Über Reflexion,
	 * damit der Test über alle Versionen hinweg kompiliert.
	 */
	private static void refreshLight(Minecraft mc) {
		try {
			Object lt = mc.gameRenderer.getClass().getMethod("lightTexture").invoke(mc.gameRenderer);
			lt.getClass().getMethod("tick").invoke(lt);
		} catch (Throwable ignored) {
		}
	}

	private static void logHand(Minecraft mc, String tag) {
		if (mc.player == null) return;
		dev.theredstonee.trsclient.TrsClient.LOGGER.info("[Autotest] {}: ausgewählter Slot = {}", tag,
				mc.player.getMainHandItem());
	}
}
