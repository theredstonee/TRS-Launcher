package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.perf.PerfHooks;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
//? if >=1.21.9 && <26.1 {
/*import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
*///?}

/**
 * Selbsttest Ressourcen-Überblendung ({@code -PtrsAutotestOnly=reload}): hält JEDES Bild des Ein- und
 * Ausblendens fest (vor dem nächsten Bild aus dem Bildpuffer), dazu die ersten Bilder danach – beim ersten
 * Start, beim Neuladen über dem Titelbildschirm (wie F3+T) und, mit {@code -Dtrsclient.autotest.server=host:port},
 * beim Beitritt zu einem Server mit Server-Ressourcenpaket. Außerdem der Verbinden-Bildschirm bei mehreren
 * Fenstergrößen/GUI-Skalen (Adresse ohne Antwort, damit er stehen bleibt). Bilder:
 * trsclient-&lt;mc&gt;-reload-&lt;szene&gt;-&lt;nr&gt;-&lt;phase&gt;-a&lt;deckkraft&gt;.png.
 */
public final class ReloadTest {
	private static final int MAX_FADE = 400;
	private String scene = "start";
	private int frame;
	private int fadeShots;
	private int after;
	private int phase;
	private int wait;
	private int waited;
	private int sizeStep;

	public static void install() {
		final ReloadTest test = new ReloadTest();
		PerfHooks.frameProbe = test::probe;
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Überblendung: {}", text);
	}

	/** Vor jedem Bild: das gerade fertige Bild ablegen, wenn es zur Überblendung gehört (oder kurz danach). */
	private void probe() {
		String p = VanillaMenus.overlayPhase;
		float alpha = VanillaMenus.overlayAlpha;
		VanillaMenus.overlayPhase = null;
		frame++;
		if (p != null) {
			after = 4;
			boolean fade = "in".equals(p) || "out".equals(p);
			// Ein-/Ausblenden: jedes Bild; Laden/Halten: nur ab und zu.
			if (fade ? fadeShots++ < MAX_FADE : frame % 15 == 0) grab(p, alpha);
		} else if (after > 0) {
			after--;
			grab("after", 0f);
		}
	}

	private void grab(String p, float alpha) {
		AutoTest.shot(Minecraft.getInstance(), String.format("trsclient-reload-%s-%04d-%s-a%03d", scene, frame, p, Math.round(alpha * 100)));
	}

	private boolean waitFor(boolean ready, int maxTries) {
		if (!ready && waited++ < maxTries) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Überblendung: Fehler in Phase {}", phase, e);
			PerfHooks.frameProbe = null;
			mc.stop();
			phase = 999;
		}
	}

	private void step(Minecraft mc) {
		switch (phase) {
			case 0:
				// Erster Start: warten, bis die Überblendung ganz weg ist.
				if (Mc.overlay() != null || Mc.screen() == null || after > 0) return;
				mc.options.pauseOnLostFocus = false;
				log("erster Start fertig");
				Mc.setScreen(new TrsTitleScreen());
				phase++;
				wait = 30;
				return;
			case 1:
				// Wie F3+T / Pakete wechseln: Überblendung mit Einblenden über dem Titelbildschirm.
				scene = "reload";
				fadeShots = 0;
				mc.reloadResourcePacks();
				log("neu laden");
				phase++;
				wait = 5;
				return;
			case 2:
				if (!waitFor(Mc.overlay() == null && after == 0, 600)) return;
				log("neu laden fertig");
				phase++;
				wait = 10;
				return;
			case 3:
				if (!connectScreens(mc)) return;
				phase++;
				return;
			case 4:
				if (!joinServer(mc)) return;
				phase++;
				return;
			default:
				if (phase == 5) {
					log("fertig");
					PerfHooks.frameProbe = null;
					phase++;
					mc.stop();
				}
		}
	}

	/** Verbinden-Bildschirm bei 854×480 GUI 2, 1920×1080 GUI auto/2/3. true = fertig. */
	private boolean connectScreens(Minecraft mc) {
		//? if >=1.21.9 && <26.1 {
		/*int[][] sizes = {{854, 480, 2}, {1920, 1080, 0}, {1920, 1080, 2}, {1920, 1080, 3}};
		if (sizeStep == 0) {
			ServerData data = new ServerData("TRS Layout-Test", "10.255.255.1:25565", ServerData.Type.OTHER);
			ConnectScreen.startConnecting(new TrsTitleScreen(), mc, ServerAddress.parseString(data.ip), data, false, null);
		}
		if (sizeStep > sizes.length) return true;
		if (sizeStep > 0) {
			int[] prev = sizes[sizeStep - 1];
			AutoTest.shot(mc, "trsclient-reload-connect-" + prev[0] + "x" + prev[1] + "-gui" + (prev[2] == 0 ? "auto" : String.valueOf(prev[2]))
					+ "-" + Mc.window().getGuiScaledWidth() + "x" + Mc.window().getGuiScaledHeight());
		}
		if (sizeStep == sizes.length) {
			cancelConnect();
			mc.options.guiScale().set(2);
			Mc.window().setWindowed(854, 480);
			mc.resizeDisplay();
			Mc.setScreen(new TrsTitleScreen());
			sizeStep++;
			wait = 20;
			return false;
		}
		int[] s = sizes[sizeStep];
		mc.options.guiScale().set(s[2]);
		Mc.window().setWindowed(s[0], s[1]);
		mc.resizeDisplay();
		sizeStep++;
		wait = 30;
		return false;
		*///?} else {
		log("Verbinden-Bildschirm: nur 1.21.9–1.21.11 geprüft");
		return true;
		//?}
	}

	//? if >=1.21.9 && <26.1 {
	/*/^* „Abbrechen“ drücken – sonst meldet der hängende Verbindungsversuch später „Verbindung fehlgeschlagen“. *^/
	private static void cancelConnect() {
		if (!(Mc.screen() instanceof ConnectScreen)) return;
		for (net.minecraft.client.gui.components.events.GuiEventListener c : Mc.screen().children()) {
			if (!(c instanceof net.minecraft.client.gui.components.Button)) continue;
			for (java.lang.reflect.Method m : net.minecraft.client.gui.components.Button.class.getMethods()) {
				if (!"onPress".equals(m.getName()) || m.getParameterCount() != 1) continue;
				try {
					m.invoke(c, (Object) null);
					return;
				} catch (ReflectiveOperationException e) {
					log("Abbrechen: " + e);
				}
			}
		}
	}
	*///?}

	/** Beitritt zu einem Server mit Server-Ressourcenpaket (nur mit -Dtrsclient.autotest.server). true = fertig. */
	private boolean joinServer(Minecraft mc) {
		String server = System.getProperty("trsclient.autotest.server");
		if (server == null || server.isEmpty()) return true;
		//? if >=1.21.9 && <26.1 {
		/*if (!"server".equals(scene)) {
			scene = "server";
			fadeShots = 0;
			ServerData data = new ServerData("TRS Paket-Test", server, ServerData.Type.OTHER);
			data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
			ConnectScreen.startConnecting(new TrsTitleScreen(), mc, ServerAddress.parseString(server), data, false, null);
			log("verbinde mit " + server);
			wait = 20;
			return false;
		}
		// In der Welt, kein Bildschirm, keine Überblendung mehr – und die letzten Bilder danach sind abgelegt.
		if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null && Mc.overlay() == null && after == 0 && fadeShots > 0, 400)) return false;
		log("Server-Paket fertig (" + fadeShots + " Bilder Ein-/Ausblenden)");
		AutoTest.shot(mc, "trsclient-reload-server-ingame");
		return true;
		*///?} else {
		return true;
		//?}
	}
}
