package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.chat.AutoGg;
import dev.theredstonee.trsclient.core.chat.ChatOut;
import dev.theredstonee.trsclient.core.chat.ChatStacker;
import dev.theredstonee.trsclient.core.chat.ChatTimestamp;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.util.RateLimiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import java.time.LocalTime;

/**
 * Chat-Verbesserungen ohne Coremod: das Forge-Ereignis {@link ClientChatReceivedEvent} wird
 * abgebrochen und die Zeile selbst gedruckt – mit eigener Zeilen-Kennung, damit eine Wiederholung
 * die vorherige Zeile ersetzen kann ("(x3)").
 *
 * <p>1.7.10 kennt noch keinen Nachrichtentyp am Ereignis (die Meldung über der Hotbar läuft über
 * ein eigenes Paket) – es muss also nichts ausgenommen werden.
 *
 * <p>Alles, was selbst sendet (Auto-GG, Text-Hotkeys), ist standardmäßig aus und streng begrenzt.
 */
public final class ChatFeatures {
	/** Startwert der eigenen Zeilen-Kennungen (Vanilla benutzt 0, andere Mods kleine Zahlen). */
	private static final int ID_BASE = 0x7452_0000;

	private final TrsModules modules;
	private final ChatStacker stacker = new ChatStacker();
	private final AutoGg autoGg = new AutoGg();
	/** Höchstens eine Hotkey-Nachricht pro Sekunde, höchstens drei in zehn Sekunden. */
	private final RateLimiter hotkeys = new RateLimiter(1000, 3, 10_000);
	/** Kennung der zuletzt von uns gedruckten Zeile (0 = keine). */
	private int lastLineId;
	private int nextId = ID_BASE;

	public ChatFeatures(TrsModules modules) {
		this.modules = modules;
	}

	private static GuiNewChat chatGui() {
		Minecraft mc = Minecraft.getMinecraft();
		return mc.ingameGUI == null ? null : mc.ingameGUI.getChatGUI();
	}

	private static void actionBar(String message) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.ingameGUI != null) mc.ingameGUI.func_110326_a(message, false);
	}

	/** Aus dem Forge-Ereignis (niedrigste Priorität – andere Mods sehen die Nachricht zuerst). */
	public void onChatReceived(ClientChatReceivedEvent event) {
		IChatComponent original = event.message;
		if (original == null) return;
		String plain = original.getUnformattedText();
		long now = System.currentTimeMillis();

		String prefix = "";
		String suffix = "";
		boolean rewrite = false;
		if (modules.chat.isEnabled()) {
			if (modules.chatStack.get()) {
				int count = stacker.accept(plain, now, ChatStacker.DEFAULT_WINDOW_MS);
				if (count > 1 && lastLineId != 0) {
					// Die vorherige (eigene) Zeile weicht der zusammengefassten.
					deleteLine(lastLineId);
					suffix = "§7" + ChatStacker.suffix(count);
				} else if (count > 1) {
					// Vorherige Zeile gehört nicht uns – neu anfangen, sonst stünde sie doppelt da.
					stacker.reset();
					stacker.accept(plain, now, ChatStacker.DEFAULT_WINDOW_MS);
				}
				// Auch ohne Wiederholung selbst drucken: nur so lässt sich die Zeile später ersetzen.
				rewrite = true;
			}
			if (modules.chatTimestamps.get()) {
				LocalTime time = LocalTime.now();
				prefix = "§8" + ChatTimestamp.format(time.getHour(), time.getMinute(), time.getSecond(),
						modules.chatTimestampSeconds.get(), false) + "§r";
				rewrite = true;
			}
		}
		if (rewrite) {
			int id = nextId++;
			lastLineId = reprint(event, original, prefix, suffix, id) ? id : 0;
		} else {
			lastLineId = 0;
		}

		if (modules.autoGg.isEnabled()) {
			autoGg.onMessage(plain, AutoGg.extraTriggers(modules.autoGgTriggers.get()), now,
					(long) (modules.autoGgDelay.get() * 1000));
		}
	}

	/**
	 * Bricht das Ereignis ab und zeigt die Nachricht mit Vorsatz/Nachsatz erneut an.
	 * Das Original wird als Kind angehängt – Farben, Links und Hover-Texte bleiben erhalten.
	 *
	 * @param id eigene Zeilen-Kennung (≠ 0), damit die Zeile später ersetzt werden kann
	 */
	private static boolean reprint(ClientChatReceivedEvent event, IChatComponent original,
			String prefix, String suffix, int id) {
		GuiNewChat gui = chatGui();
		if (gui == null) return false;
		ChatComponentText line = new ChatComponentText(prefix);
		line.appendSibling(original);
		if (!suffix.isEmpty()) line.appendSibling(new ChatComponentText(suffix));
		event.setCanceled(true);
		gui.printChatMessageWithOptionalDeletion(line, id);
		return true;
	}

	/** Entfernt die Zeile mit dieser Kennung wieder. */
	private static void deleteLine(int id) {
		GuiNewChat gui = chatGui();
		if (gui != null) gui.deleteChatLine(id);
	}

	/** Welt gewechselt / Chat geleert: Zusammenfassen neu beginnen. */
	public void onWorldChange() {
		stacker.reset();
		lastLineId = 0;
		autoGg.cancel();
	}

	/** Einmal je Client-Tick: geplante Auto-GG-Nachricht senden. */
	public void tick(Minecraft mc) {
		if (mc.thePlayer == null || !modules.autoGg.isEnabled()) {
			autoGg.cancel();
			return;
		}
		if (autoGg.due(System.currentTimeMillis())) {
			String text = ChatOut.sanitize(modules.autoGgText.get());
			if (!text.isEmpty()) mc.thePlayer.sendChatMessage(text);
		}
	}

	/** Text-Hotkey gedrückt (0–3). */
	public void onHotkey(int index) {
		Minecraft mc = Minecraft.getMinecraft();
		if (!modules.textHotkeys.isEnabled() || index < 0 || index >= modules.hotkeyTexts.length) return;
		if (mc.thePlayer == null || mc.currentScreen != null) return;
		String text = ChatOut.sanitize(modules.hotkeyTexts[index].get());
		if (text.isEmpty()) {
			actionBar("Text-Hotkey " + (index + 1) + " ist leer");
			return;
		}
		if (!hotkeys.tryAcquire(System.currentTimeMillis())) {
			actionBar("Text-Hotkey: zu schnell hintereinander");
			return;
		}
		mc.thePlayer.sendChatMessage(text);
	}

	/**
	 * Strg+Klick im Chat: die angeklickte Chat-Komponente in die Zwischenablage.
	 * true = Klick verbraucht.
	 */
	public boolean onChatClick(int rawMouseX, int rawMouseY) {
		if (!modules.chat.isEnabled() || !modules.chatCopy.get()) return false;
		try {
			GuiNewChat gui = chatGui();
			if (gui == null) return false;
			IChatComponent component = gui.getChatComponent(rawMouseX, rawMouseY);
			if (component == null) return false;
			String line = component.getUnformattedText();
			if (line == null || line.isEmpty()) return false;
			GuiScreen.setClipboardString(line);
			actionBar("Zeile kopiert");
			return true;
		} catch (RuntimeException e) {
			// Der Chat darf nie am Client scheitern.
			TrsClient.LOGGER.error("Chat-Zeile konnte nicht kopiert werden", e);
			return false;
		}
	}

	public AutoGg autoGg() {
		return autoGg;
	}

	public ChatStacker stacker() {
		return stacker;
	}
}
