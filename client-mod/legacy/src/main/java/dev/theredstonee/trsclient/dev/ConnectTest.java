package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Selbsttest Verbinden-Bildschirm ({@code -PtrsAutotestOnly=connect}) unter 1.8.9–1.12.2: Logo, Titel und
 * Lampen stehen über „Abbrechen“ – bei GUI-Skala 2 (240 hoch) und 1 (480 hoch, früher überdeckt). Adresse ohne
 * Antwort, damit der Bildschirm stehen bleibt. Bilder: trsclient-&lt;mc&gt;-connect-gui&lt;n&gt;.png.
 */
public final class ConnectTest {
	private static final int[] SCALES = {2, 1, 3};
	private int step = -1;
	private int wait;

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new ConnectTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			tick(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Verbinden: Fehler", e);
			step = 999;
			mc.shutdown();
		}
	}

	private void tick(Minecraft mc) {
		GuiScreen screen = mc.currentScreen;
		if (step == -1) {
			if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
			mc.gameSettings.pauseOnLostFocus = false;
			mc.displayGuiScreen(new GuiConnecting(new TrsTitleScreen(), mc, new ServerData("TRS Layout-Test", "10.255.255.1:25565", false)));
			step = 0;
			wait = 5;
			return;
		}
		if (step > 0) {
			ScaledResolution r = new ScaledResolution(mc);
			String file = "trsclient-" + Mc.version() + "-connect-gui" + SCALES[step - 1] + "-" + r.getScaledWidth() + "x" + r.getScaledHeight() + ".png";
			ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
			TrsClient.LOGGER.info("[Autotest] Verbinden: Bild {}", file);
		}
		if (step >= SCALES.length) {
			TrsClient.LOGGER.info("[Autotest] Verbinden: fertig");
			step = 999;
			mc.gameSettings.guiScale = 2;
			mc.shutdown();
			return;
		}
		if (step > SCALES.length) return;
		mc.gameSettings.guiScale = SCALES[step];
		ScaledResolution r = new ScaledResolution(mc);
		if (mc.currentScreen != null) mc.currentScreen.setWorldAndResolution(mc, r.getScaledWidth(), r.getScaledHeight());
		step++;
		wait = 20;
	}
}
