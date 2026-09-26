package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.ChatLines;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.QolModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.qol.QolHooks;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Selbsttest „Komfort &amp; PvP“ ({@code -PtrsAutotestOnly=qol}): Chat (Zeitstempel, Stapeln, Erwähnung, Filter),
 * Zähler-HUD und Warnungen, Hitmarker, Titel, Scoreboard im HUD-Editor, Streamer-Modus (Chat, Scoreboard, Tabliste) und
 * der Auto-Reconnect-Knopf im Getrennt-Bildschirm. Screenshots trsclient-&lt;mc&gt;-qol-*.png.
 */
public final class QolTest {
	private int phase;
	private int wait;
	private LivingEntity target;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (wait > 0) {
			wait--;
			if (phase == 7) tab(mc, true);
			return true;
		}
		QolModules q = modules.qol;
		String me = mc.getUser().getName();
		switch (phase++) {
			case 0: {
				if (mc.player == null) return false;
				actions.command("gamerule logAdminCommands false");
				actions.command("gamerule sendCommandFeedback false");
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("gamemode survival @p");
				actions.command("clear @p");
				actions.command("execute as @p at @s run fill ~-6 ~ ~-3 ~6 ~5 ~8 air");
				actions.command("execute as @p at @s run fill ~-6 ~-1 ~-3 ~6 ~-1 ~8 grass_block");
				actions.command("execute as @p at @s run tp @s ~ ~ ~ 0 0");
				actions.command("give @p arrow 64");
				actions.command("give @p golden_apple 5");
				actions.command("give @p ender_pearl 16");
				actions.command("give @p cobblestone 64");
				actions.command("give @p totem_of_undying 1");
				//? if >=1.20.5 {
				actions.command("give @p splash_potion[potion_contents={potion:\"minecraft:healing\"}] 3");
				actions.command("item replace entity @p armor.head with diamond_helmet[damage=340]");
				//?} elif >=1.17 {
				/*actions.command("give @p splash_potion{Potion:\"minecraft:healing\"} 3");
				actions.command("item replace entity @p armor.head with diamond_helmet{Damage:340}");
				*///?} else {
				/*actions.command("give @p splash_potion{Potion:\"minecraft:healing\"} 3");
				actions.command("replaceitem entity @p armor.head diamond_helmet{Damage:340}");
				*///?}
				actions.command("scoreboard objectives add trs dummy \"TRS Test\"");
				actions.command("scoreboard objectives setdisplay sidebar trs");
				actions.command("scoreboard objectives setdisplay list trs");
				actions.command("scoreboard players set Kills trs 12");
				actions.command("scoreboard players set Coins trs 350");
				actions.command("scoreboard players set " + me + " trs 5");
				// Saubere Bilder: andere HUD-Module aus (der Autotest stellt am Ende alles wieder her).
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.dynamicFps.setEnabled(false);
				modules.chat.setEnabled(true);
				modules.chatTimestamps.set(true);
				modules.chatStack.set(true);
				q.mentions.setEnabled(true);
				q.mentionsWords.set("ping");
				q.mentionsSound.set(false);
				q.chatFilter.setEnabled(true);
				q.chatFilterWords.set("spam-test");
				q.queueAlerts.setEnabled(true);
				q.alertSound.set(false);
				q.warnings.setEnabled(true);
				q.warnDurability.set(20);
				q.warnSound.set(false);
				q.itemCounter.setEnabled(true);
				q.countSplash.set(true);
				q.hitFeedback.setEnabled(true);
				q.hitMarkerDuration.set(1000);
				q.hitParticles.set(3);
				q.scoreboard.setEnabled(true);
				q.scoreboard.position().set(new dev.theredstonee.trsclient.core.hud.HudPosition(HudAnchor.TOP_LEFT, 0.01, 0.02));
				q.scoreboard.scale.set(0.9);
				q.tabPing.setEnabled(true);
				q.titles.setEnabled(true);
				q.titles.scale.set(0.6);
				q.bossBar.setEnabled(true);
				mc.mouseHandler.releaseMouse();
				Mc.setScreen(null);
				wait = 40;
				return true;
			}
			case 1:
				ChatLines.chat().clearMessages(false);
				for (int i = 0; i < 3; i++) ChatLines.addMessage(Mc.text("[Server] Restart in 5 minutes"));
				ChatLines.addMessage(Mc.text("<Alex> hey " + me + ", ping me later"));
				ChatLines.addMessage(Mc.text("spam-test BUY NOW cheap coins"));
				ChatLines.addMessage(Mc.text("Position in queue: 1"));
				actions.command("effect give @p minecraft:hunger 3 255");
				//? if >=1.19.4 {
				actions.command("damage @p 15");
				//?} else
				/*actions.command("effect give @p minecraft:instant_damage 1 1");*/
				//? if >=1.21.9 {
				/*Mc.setScreen(new ChatScreen("", false));
				*///?} else
				Mc.setScreen(new ChatScreen(""));
				wait = 30;
				return true;
			case 2:
				actions.shot("trsclient-qol-chat");
				TrsClient.LOGGER.info("[Autotest] Chat-Zeilen: {}", ((dev.theredstonee.trsclient.mixin.ChatComponentAccessor) ChatLines.chat()).trsclient$allMessages().size());
				Mc.setScreen(null);
				actions.command("execute as @p at @s run summon sheep ~ ~ ~2.5 {NoAI:1b,Silent:1b}");
				wait = 30;
				return true;
			case 3:
				actions.shot("trsclient-qol-hud");
				target = null;
				for (Entity e : mc.level.entitiesForRendering()) {
					if (e instanceof LivingEntity && e != mc.player) target = (LivingEntity) e;
				}
				if (target != null && mc.gameMode != null) mc.gameMode.attack(mc.player, target);
				TrsClient.LOGGER.info("[Autotest] Ziel gefunden: {}", target != null);
				wait = 4;
				return true;
			case 4:
				actions.shot("trsclient-qol-hitmarker");
				actions.command("title @p times 5 100 20");
				actions.command("title @p subtitle {\"text\":\"gg\"}");
				actions.command("title @p title {\"text\":\"VICTORY!\",\"color\":\"gold\"}");
				wait = 20;
				return true;
			case 5:
				actions.shot("trsclient-qol-title");
				q.streamer.setEnabled(true);
				q.streamerName.set("Streamer");
				Mc.setScreen(new HudEditorScreen(null).selectFirst());
				wait = 20;
				return true;
			case 6:
				actions.shot("trsclient-qol-editor");
				Mc.setScreen(null);
				ChatLines.addMessage(Mc.text("<Alex> gg " + me + "!"));
				// Tabliste zeigen (Taste halten, siehe oben).
				wait = 20;
				return true;
			case 7:
				actions.shot("trsclient-qol-streamer");
				tab(mc, false);
				q.streamer.setEnabled(false);
				q.autoReconnect.setEnabled(true);
				q.reconnectDelay.set(30);
				QolHooks.testServer("127.0.0.1:1");
				AutoTest.disconnect(mc);
				wait = 20;
				return true;
			case 8:
				//? if >=1.16 {
				Mc.setScreen(new DisconnectedScreen(new TitleScreen(), Mc.text("Connection Lost"), Mc.text("Timed out")));
				//?} else
				/*Mc.setScreen(new DisconnectedScreen(new TitleScreen(), "connect.failed", Mc.text("Timed out")));*/
				wait = 30;
				return true;
			case 9:
				actions.shot("trsclient-qol-reconnect");
				QolHooks.cancelReconnect();
				wait = 5;
				return true;
			case 10:
				actions.shot("trsclient-qol-reconnect-cancelled");
				Mc.setScreen(new TitleScreen());
				return false;
			default:
				return false;
		}
	}

	/** Tabliste zeigen (Taste gedrückt halten). */
	private static void tab(Minecraft mc, boolean down) {
		if (mc.options == null) return;
		//? if >=1.15 {
		mc.options.keyPlayerList.setDown(down);
		//?} else
		/*((dev.theredstonee.trsclient.mixin.KeyMappingAccessor) mc.options.keyPlayerList).trsclient$setDown(down);*/
	}
}
