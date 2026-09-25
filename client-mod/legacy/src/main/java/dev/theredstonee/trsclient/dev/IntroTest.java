package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.intro.IntroGate;
import dev.theredstonee.trsclient.core.intro.IntroUi;
import dev.theredstonee.trsclient.core.intro.KeyBind;
import dev.theredstonee.trsclient.core.module.ModulePacks;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.menu.ModMenu;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Selbsttest Einführung ({@code -PtrsAutotestOnly=intro}): setzt die Einführung zurück, öffnet den Startbildschirm
 * (die Einführung muss von selbst erscheinen), Screenshot je Schritt, wählt das Paket „PvP“, schließt ab; danach das
 * TRS-Menü mit „NEU“-Markierungen (Stand künstlich auf 0.3.0 gesetzt), eine Einstellungsseite mit neuer Zeile und die
 * Seite „Modul-Pakete“. Stellt am Ende die Config wieder her und beendet das Spiel.
 */
public final class IntroTest {
	private int phase;
	private int wait;
	private int waited;
	private TrsConfig before;

	private final String mcVersion = Mc.version();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new IntroTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase == TickEvent.Phase.END) tick(Minecraft.getMinecraft());
	}

	private void shot(Minecraft mc, String name) {
		String file = name.replace("trsclient-", "trsclient-" + mcVersion + "-") + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Screenshot {}", file);
	}

	private static IntroUi intro() {
		if (Mc.screen() instanceof TrsUiScreen && ((TrsUiScreen) Mc.screen()).ui() instanceof IntroUi) {
			return (IntroUi) ((TrsUiScreen) Mc.screen()).ui();
		}
		return null;
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		TrsModules modules = TrsClient.get().modules();
		switch (phase) {
			case 0:
				if (!(mc.currentScreen instanceof TrsTitleScreen) && !(mc.currentScreen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				before = modules.registry.capture();
				modules.titleScreen.setEnabled(true);
				modules.clientState.resetIntro();
				modules.clientState.news().set("0.3.0", null);
				IntroGate.reset();
				Mc.setScreen(new TrsTitleScreen());
				phase++;
				wait = 5;
				return;
			case 1: {
				// Die Einführung erscheint von selbst (höchstens ~5 s Warten auf den ersten Sync).
				IntroUi ui = intro();
				if (ui == null) {
					if (waited++ < 200) return;
					TrsClient.LOGGER.error("[Autotest] Einführung: erschien NICHT von selbst");
					phase = 20;
					return;
				}
				TrsClient.LOGGER.info("[Autotest] Einführung: erschien nach {} Ticks, TRS-Tasten={}", waited, ui.trsKeys().size());
				java.util.List<KeyBind> all = new dev.theredstonee.trsclient.screen.TrsMenuHost(null).keyBindings();
				for (KeyBind b : ui.trsKeys()) {
					TrsClient.LOGGER.info("[Autotest] Einführung: Taste {} = {} (Konflikte: {})", b.id, b.key(), b.conflicts(all).size());
				}
				phase++;
				wait = 20;
				return;
			}
			case 2:
				shot(mc, "trsclient-intro-1-look");
				intro().goTo(1);
				phase++;
				wait = 15;
				return;
			case 3:
				shot(mc, "trsclient-intro-2-perf");
				intro().goTo(2);
				phase++;
				wait = 15;
				return;
			case 4:
				shot(mc, "trsclient-intro-3-keys");
				intro().goTo(3);
				intro().packs().select(ModulePacks.PVP);
				phase++;
				wait = 15;
				return;
			case 5:
				shot(mc, "trsclient-intro-4-packs");
				intro().finish();
				phase++;
				wait = 30;
				return;
			case 6:
				shot(mc, "trsclient-intro-done");
				TrsClient.LOGGER.info("[Autotest] Einführung: erledigt={} wie={} paket={} reach={} keystrokes={} minimap={}",
						modules.clientState.introDone(), modules.clientState.introHow(), modules.clientState.introPack(),
						modules.reach.isEnabled(), modules.keystrokes.isEnabled(), modules.minimap.isEnabled());
				Mc.setScreen(new TrsMenuScreen(new TrsTitleScreen()));
				phase++;
				wait = 20;
				return;
			case 7:
				shot(mc, "trsclient-menu-new");
				Mc.setScreen(new TrsMenuScreen(new TrsTitleScreen()).select(modules.trsOnline));
				phase++;
				wait = 20;
				return;
			case 8:
				shot(mc, "trsclient-menu-new-settings");
				TrsClient.LOGGER.info("[Autotest] NEU: trsOnline noch neu={} (Kachel), sync-Zeile gesehen={}",
						modules.clientState.news().hasNew(modules.trsOnline), modules.clientState.news().seen().contains("trsOnline.sync"));
				TrsMenuScreen menu = new TrsMenuScreen(new TrsTitleScreen());
				((ModMenu) menu.ui()).showPacks().packsPage().select(ModulePacks.REDSTONE);
				Mc.setScreen(menu);
				phase++;
				wait = 20;
				return;
			case 9:
				shot(mc, "trsclient-menu-packs");
				phase = 20;
				return;
			case 20:
				TrsClient.LOGGER.info("[Autotest] Einführung fertig");
				if (before != null) {
					modules.registry.apply(before);
					TrsClient.get().saveConfig();
				}
				phase++;
				wait = 20;
				return;
			case 21:
				phase++;
				mc.shutdown();
				return;
			default:
		}
	}
}
