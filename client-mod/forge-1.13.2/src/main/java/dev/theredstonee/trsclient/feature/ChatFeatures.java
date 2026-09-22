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
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import java.time.LocalTime;

/**
 * Chat-Verbesserungen ohne Mixin: das Forge-Ereignis {@link ClientChatReceivedEvent} wird
 * abgebrochen und die Zeile selbst gedruckt – mit eigener Zeilen-Kennung, damit eine Wiederholung
 * die vorherige Zeile ersetzen kann ("(x3)").
 *
 * <p>1.13.2: der Nachrichtentyp ist ein {@link ChatType} ({@code GAME_INFO} = Meldung über der
 * Hotbar, die bleibt unangetastet), die Zeile unter der Maus liefert
 * {@code GuiNewChat.getTextComponent(double, double)} (in MCP stable_47 heißt
 * {@code getChatComponent} so) – und zwar in SKALIERten GUI-Koordinaten, genau wie
 * {@code GuiChat.mouseClicked} sie weitergibt.
 *
 * <p>Alles, was selbst sendet (Auto-GG, Text-Hotkeys), ist standardmäßig aus und streng begrenzt.
 */
public final class ChatFeatures {
	/** Startwert der eigenen Zeilen-Kennungen (Vanilla benutzt 0, andere Mods kleine Zahlen). */
	private static final int ID_BASE = 0x7452_0000;

	private final Minecraft mc = Minecraft.getInstance();
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

	private GuiNewChat chat() {
		return mc.ingameGUI == null ? null : mc.ingameGUI.getChatGUI();
	}

	/** Aus dem Forge-Ereignis (niedrigste Priorität – andere Mods sehen die Nachricht zuerst). */
	public void onChatReceived(ClientChatReceivedEvent event) {
		// Meldung über der Hotbar (nicht im Chat) – die wird nicht angefasst.
		if (event.getType() == ChatType.GAME_INFO) return;
		ITextComponent original = event.getMessage();
		if (original == null) return;
		String plain = original.getString();
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
	 */
	private boolean reprint(ClientChatReceivedEvent event, ITextComponent original,
			String prefix, String suffix, int id) {
		GuiNewChat gui = chat();
		if (gui == null) return false;
		TextComponentString line = new TextComponentString(prefix);
		line.appendSibling(original);
		if (!suffix.isEmpty()) line.appendSibling(new TextComponentString(suffix));
		event.setCanceled(true);
		gui.printChatMessageWithOptionalDeletion(line, id);
		return true;
	}

	/** Entfernt die Zeile mit dieser Kennung wieder. */
	private void deleteLine(int id) {
		GuiNewChat gui = chat();
		if (gui != null) gui.deleteChatLine(id);
	}

	/** Welt gewechselt / Chat geleert: Zusammenfassen neu beginnen. */
	public void onWorldChange() {
		stacker.reset();
		lastLineId = 0;
		autoGg.cancel();
	}

	/** Einmal je Client-Tick: geplante Auto-GG-Nachricht senden. */
	public void tick() {
		if (mc.player == null || !modules.autoGg.isEnabled()) {
			autoGg.cancel();
			return;
		}
		if (autoGg.due(System.currentTimeMillis())) {
			String text = ChatOut.sanitize(modules.autoGgText.get());
			if (!text.isEmpty()) mc.player.sendChatMessage(text);
		}
	}

	/** Text-Hotkey gedrückt (0–3). */
	public void onHotkey(int index) {
		if (!modules.textHotkeys.isEnabled() || index < 0 || index >= modules.hotkeyTexts.length) return;
		if (mc.player == null || mc.currentScreen != null) return;
		String text = ChatOut.sanitize(modules.hotkeyTexts[index].get());
		if (text.isEmpty()) {
			actionBar("Text-Hotkey " + (index + 1) + " ist leer");
			return;
		}
		if (!hotkeys.tryAcquire(System.currentTimeMillis())) {
			actionBar("Text-Hotkey: zu schnell hintereinander");
			return;
		}
		mc.player.sendChatMessage(text);
	}

	/**
	 * Strg+Klick im Chat: die angeklickte Chat-Komponente in die Zwischenablage.
	 * Die Koordinaten sind die skalierten GUI-Koordinaten des Ereignisses.
	 * true = Klick verbraucht.
	 */
	public boolean onChatClick(double mouseX, double mouseY) {
		if (!modules.chat.isEnabled() || !modules.chatCopy.get()) return false;
		try {
			GuiNewChat gui = chat();
			if (gui == null) return false;
			ITextComponent component = gui.getTextComponent(mouseX, mouseY);
			if (component == null) return false;
			String line = component.getString();
			if (line.isEmpty()) return false;
			mc.keyboardListener.setClipboardString(line);
			actionBar("Zeile kopiert");
			return true;
		} catch (RuntimeException e) {
			// Der Chat darf nie am Client scheitern.
			TrsClient.LOGGER.error("Chat-Zeile konnte nicht kopiert werden", e);
			return false;
		}
	}

	private void actionBar(String message) {
		if (mc.ingameGUI != null) mc.ingameGUI.setOverlayMessage(message, false);
	}

	public AutoGg autoGg() {
		return autoGg;
	}

	public ChatStacker stacker() {
		return stacker;
	}
}
