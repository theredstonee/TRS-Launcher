package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.bugreport.BugReports;
import dev.theredstonee.trsclient.core.ui.bugreport.BugReportPage;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Selbsttest „Bug melden“ unter 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=bugreport}, mit Attrappe über
 * {@code -PtrsApi=http://127.0.0.1:<port>}): wie der Fabric-Test – Beispiel-Screenshot, Log mit Beispiel-Geheimnissen,
 * Formular, Vorschau samt Log-Kasten, Senden, Erfolg, Fehler „Tageslimit“. Bilder trsclient-&lt;mc&gt;-bugreport-*.png;
 * beendet das Spiel danach.
 */
public final class BugReportTest {
	private int phase;
	private int wait;
	private int waited;
	private final String mcVersion = Mc.version();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new BugReportTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (wait > 0) {
			wait--;
			return;
		}
		int current = phase;
		try {
			step(mc);
		} catch (RuntimeException | LinkageError e) {
			TrsClient.LOGGER.error("[Autotest] Bug melden: Fehler in Phase {} – weiter", current, e);
			if (phase == current) phase++;
			wait = 5;
		}
		if (phase > 100) {
			phase = -1000;
			mc.shutdown();
		}
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Bug melden: {}", text);
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-bugreport-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		log("Bild " + name);
	}

	private boolean waitFor(boolean ok, int maxTicks) {
		if (ok || waited >= maxTicks) {
			waited = 0;
			return true;
		}
		waited++;
		wait = 1;
		return false;
	}

	private void step(Minecraft mc) {
		BugReports br = BugReports.get();
		BugReportPage page = BugReportPage.current();
		GuiScreen screen = mc.currentScreen;
		switch (phase) {
			case 0:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				if (br == null) {
					log("BugReports nicht initialisiert – Abbruch");
					phase = 101;
					return;
				}
				shot(mc, "base");
				TrsClient.LOGGER.info("[Autotest] Beispiel für die Säuberung: --accessToken eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJl"
						+ " C:\\Users\\Beispielnutzer\\AppData\\Roaming 192.168.178.20:25565 max@example.com"
						+ " 853c80ef-3c37-49fd-aa49-938b674adae6 Setting user: {}", mc.getSession().getUsername());
				TrsClient.LOGGER.info("[Autotest] [CHAT] <Bob> geheime Nachricht");
				phase++;
				wait = 40;
				return;
			case 1:
				br.title.setText("Minimap flackert im Nether");
				br.description.setText("Wenn ich im Nether über Lava fliege, flackert die Minimap jede Sekunde kurz schwarz.\n"
						+ "Passiert nur mit Höhlenansicht an. Ohne TRS Client kein Flackern.");
				br.includeLog = true;
				br.includeScreenshot = true;
				br.requestOpen();
				mc.displayGuiScreen(new TrsMenuScreen(new TrsTitleScreen()));
				phase++;
				wait = 10;
				return;
			case 2:
				if (!waitFor(page != null && br.data() != null && br.access() == BugReports.Access.READY, 400)) return;
				log("Zugang " + br.access() + ", Mods " + (br.data() == null ? -1 : br.data().modsTotal) + ", Log-Zeilen "
						+ (br.data() == null ? -1 : br.data().logLines) + ", Screenshots "
						+ (br.data() == null ? -1 : br.data().screenshots.size()));
				pickBaseScreenshot(br);
				phase++;
				wait = 10;
				return;
			case 3:
				shot(mc, "form");
				if (page != null) page.testScroll(260);
				phase++;
				wait = 10;
				return;
			case 4:
				shot(mc, "form-attachments");
				if (page == null || !page.testPreview()) log("Vorschau nicht möglich");
				phase++;
				wait = 10;
				return;
			case 5:
				shot(mc, "preview");
				if (page != null) page.testScroll(420);
				phase++;
				wait = 10;
				return;
			case 6:
				if (page != null) page.testLogScroll(2000);
				phase++;
				wait = 5;
				return;
			case 7:
				shot(mc, "preview-log");
				log("gesendet: " + br.sendAsync());
				phase++;
				return;
			case 8:
				if (!waitFor(!br.busy(), 400)) return;
				log("Ergebnis " + br.phase() + (br.created() == null ? " Fehler " + br.error()
						: " Issue #" + br.created().number + " " + br.created().url));
				if (page != null) page.testScroll(0);
				phase++;
				wait = 10;
				return;
			case 9:
				shot(mc, "done");
				br.reset();
				br.title.setText("Noch ein Bericht #limit");
				br.description.setText("Dieser Bericht soll am Tageslimit scheitern.");
				if (page != null) page.testPreview();
				br.sendAsync();
				phase++;
				return;
			case 10:
				if (!waitFor(!br.busy(), 400)) return;
				log("Fehlerfall " + br.phase() + " " + br.error());
				if (page != null) page.testScroll(9999);
				phase++;
				wait = 10;
				return;
			case 11:
				shot(mc, "error-limit");
				br.reset();
				phase = 101;
				wait = 5;
				return;
			default:
				phase = 101;
		}
	}

	/** Den eben aufgenommenen Beispiel-Screenshot wählen (nicht die Bilder dieses Tests). */
	static void pickBaseScreenshot(BugReports br) {
		BugReports.Data d = br.data();
		if (d == null) return;
		for (BugReports.Screenshot s : d.screenshots) {
			if (s.name.contains("bugreport-base")) {
				br.screenshot = s.path;
				return;
			}
		}
	}
}
