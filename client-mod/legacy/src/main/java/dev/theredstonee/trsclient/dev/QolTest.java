package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.hud.HudPosition;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.QolModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.qol.LegacyQol;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
//? if >=1.9 {
/*import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
*///?} else {
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
//?}

import java.util.ArrayList;

/**
 * Selbsttest „Komfort &amp; PvP“ unter 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=qol}): Chat (Zeitstempel, Stapeln,
 * Erwähnung, Filter), Zähler und Warnungen, Hitmarker, Scoreboard an eigener Stelle, HUD-Editor, Streamer-Modus und
 * der Auto-Reconnect-Knopf. Bilder: trsclient-&lt;mc&gt;-qol-*.png.
 */
public final class QolTest {
	private final String mcVersion = Mc.version();
	private final String world = "trs-qol-" + Mc.version();
	private int phase = -1;
	private int wait;
	private int waited;
	/** Einstellungen vor dem Test (werden am Ende wiederhergestellt). */
	private dev.theredstonee.trsclient.core.config.TrsConfig before;

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new QolTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (phase == 8) hold(mc, true);
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			tick(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Komfort/PvP: Fehler", e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void tick(Minecraft mc) {
		GuiScreen screen = mc.currentScreen;
		TrsModules modules = TrsClient.get().modules();
		QolModules q = modules.qol;
		String me = mc.getSession().getUsername();
		switch (phase) {
			case -1:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				// Immer frisch (sonst stehen Schafe, Tote und Punktestände früherer Läufe herum).
				mc.getSaveLoader().deleteWorldDirectory(world);
				mc.launchIntegratedServer(world, world, Mc.creativeWorld(20260926L));
				phase++;
				return;
			case 0:
				if (Mc.player() != null && Mc.player().getHealth() <= 0) {
					// Aus einem früheren Lauf tot gespeichert: wiederbeleben.
					Mc.player().respawnPlayer();
					mc.displayGuiScreen(null);
					return;
				}
				if (Mc.world() == null || Mc.player() == null || mc.currentScreen != null) {
					if (waited++ > 900) throw new IllegalStateException("Welt lädt nicht");
					return;
				}
				command(mc, "gamerule logAdminCommands false");
				command(mc, "gamerule sendCommandFeedback false");
				command(mc, "time set 1000");
				command(mc, "weather clear");
				before = modules.registry.capture();
				command(mc, "gamemode 0 " + me);
				command(mc, "effect " + me + " clear");
				command(mc, "effect " + me + " 6 1 10");
				command(mc, "clear " + me);
				command(mc, "give " + me + " arrow 64");
				command(mc, "give " + me + " golden_apple 5");
				command(mc, "give " + me + " ender_pearl 16");
				command(mc, "give " + me + " cobblestone 64");
				//? if >=1.9 {
				/*command(mc, "give " + me + " splash_potion 3 0 {Potion:\"minecraft:healing\"}");
				command(mc, "replaceitem entity " + me + " slot.armor.head diamond_helmet 1 340");
				*///?} else {
				command(mc, "give " + me + " potion 3 16421");
				command(mc, "replaceitem entity " + me + " slot.armor.head diamond_helmet 1 340");
				//?}
				command(mc, "scoreboard objectives add trs dummy TRS");
				command(mc, "scoreboard objectives setdisplay sidebar trs");
				command(mc, "scoreboard objectives setdisplay list trs");
				command(mc, "scoreboard players set Kills trs 12");
				command(mc, "scoreboard players set Coins trs 350");
				command(mc, "scoreboard players set " + me + " trs 5");
				command(mc, "tp " + me + " ~ ~ ~ 0 0");
				// Freie Fläche vor dem Spieler (die Testwelt kann den Spieler in einen Hang setzen).
				command(mc, "execute " + me + " ~ ~ ~ fill ~-5 ~ ~-2 ~5 ~4 ~6 air");
				command(mc, "execute " + me + " ~ ~ ~ fill ~-5 ~-1 ~-2 ~5 ~-1 ~6 grass");
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
				q.scoreboard.position().set(new HudPosition(HudAnchor.TOP_LEFT, 0.01, 0.02));
				q.scoreboard.scale.set(0.9);
				q.tabPing.setEnabled(true);
				q.bossBar.setEnabled(true);
				phase++;
				wait = 60;
				return;
			case 1:
				//? if >=1.11 {
				/*mc.ingameGUI.getChatGUI().clearChatMessages(false);
				*///?} else
				mc.ingameGUI.getChatGUI().clearChatMessages();
				for (int i = 0; i < 3; i++) chat("[Server] Restart in 5 minutes");
				chat("<Alex> hey " + me + ", ping me later");
				chat("spam-test BUY NOW cheap coins");
				chat("Position in queue: 1");
				for (int i = 0; i < 3; i++) command(mc, "effect " + me + " 7 1 0");
				command(mc, "effect " + me + " 17 3 255");
				mc.displayGuiScreen(new GuiChat());
				phase++;
				wait = 30;
				return;
			case 2:
				shot(mc, "qol-chat");
				mc.displayGuiScreen(null);
				//? if >=1.11 {
				/*command(mc, "execute " + me + " ~ ~ ~ summon sheep ~ ~ ~2.5 {NoAI:1b,Silent:1b}");
				*///?} else
				command(mc, "execute " + me + " ~ ~ ~ summon Sheep ~ ~ ~2.5 {NoAI:1,Silent:1}");
				phase++;
				wait = 30;
				return;
			case 3: {
				shot(mc, "qol-hud");
				Entity target = null;
				for (Entity e : new ArrayList<Entity>(Mc.world().loadedEntityList)) {
					if (e instanceof EntitySheep && near(e, Mc.player())) target = e;
				}
				if (target != null) mc.playerController.attackEntity(Mc.player(), target);
				TrsClient.LOGGER.info("[Autotest] Ziel gefunden: {}", target != null);
				phase++;
				wait = 4;
				return;
			}
			case 4:
				shot(mc, "qol-hitmarker");
				q.streamer.setEnabled(true);
				q.streamerName.set("Streamer");
				mc.displayGuiScreen(new HudEditorScreen(null).selectFirst());
				phase++;
				wait = 20;
				return;
			case 5:
				shot(mc, "qol-editor");
				mc.displayGuiScreen(null);
				phase++;
				wait = 5;
				return;
			case 6:
				chat("<Alex> gg " + me + "!");
				phase++;
				wait = 5;
				return;
			case 7:
				// Tabliste halten (siehe onTick), dann Bild.
				phase++;
				wait = 20;
				return;
			case 8:
				shot(mc, "qol-streamer");
				hold(mc, false);
				q.streamer.setEnabled(false);
				q.autoReconnect.setEnabled(true);
				q.reconnectDelay.set(30);
				LegacyQol.testServer("127.0.0.1:1");
				// Getrennt-Bildschirm über der Welt (Welt verlassen aus dem Tick heraus kann unter 1.8.9 hängen bleiben).
				//? if >=1.9 {
				/*mc.displayGuiScreen(new GuiDisconnected(new GuiMainMenu(), "connect.failed", new TextComponentString("Timed out")));
				*///?} else
				mc.displayGuiScreen(new GuiDisconnected(new GuiMainMenu(), "connect.failed", new ChatComponentText("Timed out")));
				phase++;
				wait = 30;
				return;
			case 9:
				shot(mc, "qol-reconnect");
				LegacyQol.get().cancelReconnect();
				phase++;
				wait = 5;
				return;
			case 10:
				shot(mc, "qol-reconnect-cancelled");
				TrsClient.LOGGER.info("[Autotest] Komfort/PvP: fertig");
				if (before != null) {
					modules.registry.apply(before);
					TrsClient.get().saveConfig();
				}
				phase = 999;
				mc.displayGuiScreen(new GuiMainMenu());
				wait = 10;
				return;
			case 999:
				mc.shutdown();
				return;
			default:
		}
	}

	/** Wie eine Server-Nachricht: über das Forge-Ereignis (dort greifen Filter, Erwähnungen, Zeitstempel). */
	private static void chat(String text) {
		Minecraft mc = Minecraft.getMinecraft();
		//? if >=1.12 {
		/*ClientChatReceivedEvent ev = new ClientChatReceivedEvent(net.minecraft.util.text.ChatType.SYSTEM, new TextComponentString(text));
		if (!MinecraftForge.EVENT_BUS.post(ev) && ev.getMessage() != null) mc.ingameGUI.getChatGUI().printChatMessage(ev.getMessage());
		*///?} elif >=1.9 {
		/*ClientChatReceivedEvent ev = new ClientChatReceivedEvent((byte) 0, new TextComponentString(text));
		if (!MinecraftForge.EVENT_BUS.post(ev) && ev.getMessage() != null) mc.ingameGUI.getChatGUI().printChatMessage(ev.getMessage());
		*///?} else {
		ClientChatReceivedEvent ev = new ClientChatReceivedEvent((byte) 0, new ChatComponentText(text));
		if (!MinecraftForge.EVENT_BUS.post(ev) && ev.message != null) mc.ingameGUI.getChatGUI().printChatMessage(ev.message);
		//?}
	}

	/** Näher als 4 Blöcke (eigene Rechnung – der Methodenname unterscheidet sich je Version). */
	private static boolean near(Entity a, Entity b) {
		double dx = a.posX - b.posX, dy = a.posY - b.posY, dz = a.posZ - b.posZ;
		return dx * dx + dy * dy + dz * dz < 16;
	}

	private static void hold(Minecraft mc, boolean down) {
		KeyBinding.setKeyBindState(mc.gameSettings.keyBindPlayerList.getKeyCode(), down);
	}

	private static void command(Minecraft mc, final String command) {
		final IntegratedServer server = mc.getIntegratedServer();
		if (server == null) return;
		server.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				server.getCommandManager().executeCommand(server, command);
			}
		});
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Screenshot {}", file);
	}
}
