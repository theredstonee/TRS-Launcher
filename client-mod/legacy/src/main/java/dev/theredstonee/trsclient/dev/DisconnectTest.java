package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Selbsttest Fehlerbildschirm ({@code -PtrsAutotestOnly=disconnect}) unter 1.8.9–1.12.2: „Anmeldung fehlgeschlagen:
 * Ungültige Sitzung“ nachstellen, Neu anmelden / Fehler kopieren / Server-Status drücken; Bilder und Knopftexte.
 */
public final class DisconnectTest {
	private int phase;
	private int wait;
	private final String mcVersion = Mc.version();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new DisconnectTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		try {
			tick(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Fehlerbildschirm: Fehler in Phase {}", phase, e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void shot(Minecraft mc, String name) {
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), "trsclient-" + mcVersion + "-disconnect-" + name + ".png", mc.displayWidth,
				mc.displayHeight, mc.getFramebuffer());
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		GuiScreen screen = mc.currentScreen;
		switch (phase) {
			case 0:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				// Entwickler-Start hat keine echte UUID (ID = Name) – wie beim Launcher-Start eine einsetzen.
				try {
					for (java.lang.reflect.Field f : Minecraft.class.getDeclaredFields()) {
						if (f.getType() != net.minecraft.util.Session.class) continue;
						f.setAccessible(true);
						f.set(mc, new net.minecraft.util.Session(mc.getSession().getUsername(), "a205b8daefc637ad8e1d84c0239cdd21", "dev-token", "mojang"));
						break;
					}
				} catch (IllegalAccessException e) {
					TrsClient.LOGGER.info("[Autotest] Sitzung nicht setzbar");
				}
				TrsClient.LOGGER.info("[Autotest] Sitzung: Name {}, ID {}", mc.getSession().getUsername(), mc.getSession().getPlayerID());
				TrsClient.disconnect.testTarget(new ServerData("TRS-Test", "127.0.0.1:25599", false));
				mc.displayGuiScreen(new GuiDisconnected(new GuiMultiplayer(screen), "connect.failed", reason()));
				phase++;
				wait = 20;
				return;
			case 1:
				TrsClient.LOGGER.info("[Autotest] Fehlerbildschirm Anfang: {}", TrsClient.disconnect.testLabels());
				shot(mc, "session");
				TrsClient.disconnect.testPress(0);
				TrsClient.disconnect.testPress(3);
				TrsClient.disconnect.testPress(4);
				phase++;
				wait = 80;
				return;
			case 2:
				TrsClient.LOGGER.info("[Autotest] Fehlerbildschirm nach Klicks: {} (Bildschirm {})", TrsClient.disconnect.testLabels(),
						mc.currentScreen == null ? "-" : mc.currentScreen.getClass().getSimpleName());
				TrsClient.LOGGER.info("[Autotest] Zwischenablage: {}", GuiScreen.getClipboardString().replace('\n', '|'));
				shot(mc, "clicked");
				TrsClient.LOGGER.info("[Autotest] fertig, beende das Spiel");
				phase = 999;
				mc.shutdown();
				return;
			default:
		}
	}

	//? if >=1.9 {
	/*private static net.minecraft.util.text.ITextComponent reason() {
		return new net.minecraft.util.text.TextComponentTranslation("disconnect.loginFailedInfo",
				new net.minecraft.util.text.TextComponentTranslation("disconnect.loginFailedInfo.invalidSession"));
	}
	*///?} else {
	private static net.minecraft.util.IChatComponent reason() {
		return new net.minecraft.util.ChatComponentTranslation("disconnect.loginFailedInfo",
				new net.minecraft.util.ChatComponentTranslation("disconnect.loginFailedInfo.invalidSession"));
	}
	//?}
}
