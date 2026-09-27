package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.menus.DisconnectUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * Selbsttest Fehlerbildschirm ({@code -PtrsAutotestOnly=disconnect}): stellt „Anmeldung fehlgeschlagen: Ungültige
 * Sitzung“ nach und drückt die Zusatzknöpfe (Neu anmelden ohne Launcher, Fehler kopieren, Server-Status gegen einen
 * nicht erreichbaren Server). Bilder trsclient-&lt;mc&gt;-disconnect-*.png, Knopftexte im Log.
 */
public final class DisconnectTest {
	private int phase;
	private int wait;

	public boolean step(Minecraft mc, CapeTest.Actions actions) {
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase++) {
			case 0:
				// Welt verlassen (Fehlerbildschirme gibt es nur ohne Welt), Ziel: ein Port, auf dem niemand lauscht.
				AutoTest.disconnect(mc);
				wait = 20;
				return true;
			case 1:
				DisconnectUi.target(server("TRS-Test", "127.0.0.1:25599"));
				Mc.setScreen(screen(new JoinMultiplayerScreen(new TitleScreen())));
				wait = 20;
				return true;
			case 2:
				log(mc, "Anfang");
				actions.shot("trsclient-disconnect-session");
				press(mc, "disconnect.reauth");
				press(mc, "disconnect.copy");
				press(mc, "disconnect.status");
				wait = 80;
				return true;
			case 3:
				log(mc, "nach Klicks");
				TrsClient.LOGGER.info("[Autotest] Zwischenablage: {}", mc.keyboardHandler.getClipboard().replace('\n', '|'));
				actions.shot("trsclient-disconnect-clicked");
				Mc.setScreen(null);
				wait = 5;
				return true;
			default:
				return false;
		}
	}

	private static void log(Minecraft mc, String when) {
		Screen s = Mc.screen();
		StringBuilder sb = new StringBuilder();
		if (s != null) {
			for (GuiEventListener c : s.children()) {
				if (c instanceof AbstractWidget && ((AbstractWidget) c).visible) sb.append('[').append(label((AbstractWidget) c)).append("] ");
			}
		}
		TrsClient.LOGGER.info("[Autotest] Fehlerbildschirm {}: {}", when, sb);
	}

	/** Knopf mit dem Text dieses Schlüssels drücken. */
	private static void press(Minecraft mc, String key) {
		String want = dev.theredstonee.trsclient.core.i18n.I18n.tr(key);
		Screen s = Mc.screen();
		if (s == null) return;
		for (GuiEventListener c : s.children()) {
			if (c instanceof net.minecraft.client.gui.components.Button && label((AbstractWidget) c).equals(want)) {
				//? if >=1.21.9 {
				/*((net.minecraft.client.gui.components.Button) c).onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
				*///?} else
				((net.minecraft.client.gui.components.Button) c).onPress();
				return;
			}
		}
		TrsClient.LOGGER.info("[Autotest] Knopf „{}“ FEHLT", want);
	}

	private static String label(AbstractWidget w) {
		//? if >=1.16 {
		return w.getMessage() == null ? "" : w.getMessage().getString();
		//?} else
		/*return w.getMessage() == null ? "" : w.getMessage();*/
	}

	private static ServerData server(String name, String ip) {
		//? if >=1.20.2 {
		return new ServerData(name, ip, ServerData.Type.OTHER);
		//?} else
		/*return new ServerData(name, ip, false);*/
	}

	private static Screen screen(Screen parent) {
		//? if >=1.16 {
		return new DisconnectedScreen(parent, title(), reason());
		//?} else
		/*return new DisconnectedScreen(parent, "connect.failed", reason());*/
	}

	private static Component title() {
		//? if >=1.19 {
		return Component.translatable("connect.failed");
		//?} else
		/*return new net.minecraft.network.chat.TranslatableComponent("connect.failed");*/
	}

	private static Component reason() {
		//? if >=1.19 {
		return Component.translatable("disconnect.loginFailedInfo", Component.translatable("disconnect.loginFailedInfo.invalidSession"));
		//?} else {
		/*return new net.minecraft.network.chat.TranslatableComponent("disconnect.loginFailedInfo",
				new net.minecraft.network.chat.TranslatableComponent("disconnect.loginFailedInfo.invalidSession"));
		*///?}
	}
}
