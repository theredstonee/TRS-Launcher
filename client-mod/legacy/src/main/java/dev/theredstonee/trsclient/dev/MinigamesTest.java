package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.minigames.MinigameSelfTest;
import dev.theredstonee.trsclient.core.minigames.MinigamesUi;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.screen.TrsMenuHost;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Selbsttest Minispiele unter 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=minigames}): Der Ablauf steht in
 * {@link MinigameSelfTest}, hier nur die Anbindung an Minecraft. Screenshots: trsclient-&lt;mc&gt;-minigames-*.png; Messwerte im
 * Log unter „[Autotest] Minispiele“.
 */
public final class MinigamesTest implements MinigameSelfTest.Hook {
	private final MinigameSelfTest test = new MinigameSelfTest(this);
	private final String mcVersion = Mc.version();
	private final String world = "trs-autotest-" + Mc.version();
	private Minecraft mc;
	private boolean started;

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new MinigamesTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		mc = Minecraft.getMinecraft();
		if (!started) {
			GuiScreen screen = mc.currentScreen;
			if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
			mc.gameSettings.pauseOnLostFocus = false;
			mc.gameSettings.renderDistanceChunks = 2;
			started = true;
		}
		try {
			test.tick();
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Minispiele: Fehler", e);
			mc.shutdown();
		}
	}

	@Override
	public MinigamesUi open() {
		MinigamesUi ui = new MinigamesUi(new TrsMenuHost(new TrsTitleScreen()));
		mc.displayGuiScreen(new TrsUiScreen("minigames", ui));
		return ui;
	}

	@Override
	public void shot(String name) {
		String file = "trsclient-" + mcVersion + "-minigames-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		log("Bild " + name);
	}

	@Override
	public void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Minispiele: {}", text);
	}

	@Override
	public void theme(String design) {
		Theme.set(Theme.of("dark", design, null, design));
	}

	@Override
	public void reloadResources() {
		mc.refreshResources();
	}

	@Override
	public void startWorld() {
		if (mc.getSaveLoader().canLoadWorld(world)) {
			mc.launchIntegratedServer(world, world, null);
		} else {
			mc.launchIntegratedServer(world, world, Mc.creativeWorld(System.nanoTime()));
		}
	}

	@Override
	public boolean worldReady() {
		return Mc.world() != null && Mc.player() != null && mc.currentScreen == null;
	}

	@Override
	public void finish() {
		Theme.set(Theme.of("dark", "redstone", null));
		Theme.loadFrom(new java.io.File(Mc.gameDir(), "config").toPath());
		mc.shutdown();
	}
}
