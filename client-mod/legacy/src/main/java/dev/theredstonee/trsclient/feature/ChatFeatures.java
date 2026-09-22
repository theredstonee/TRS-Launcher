package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.ChatCompat;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.chat.AutoGg;
import dev.theredstonee.trsclient.core.chat.ChatOut;
import dev.theredstonee.trsclient.core.chat.ChatStacker;
import dev.theredstonee.trsclient.core.chat.ChatTimestamp;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.util.RateLimiter;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import java.time.LocalTime;

/**
 * Chat-Verbesserungen ohne Mixin: das Forge-Ereignis {@link ClientChatReceivedEvent} wird
 * abgebrochen und die Zeile selbst gedruckt – mit eigener Zeilen-Kennung, damit eine Wiederholung
 * die vorherige Zeile ersetzen kann ("(x3)").
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

	/** Aus dem Forge-Ereignis (niedrigste Priorität – andere Mods sehen die Nachricht zuerst). */
	public void onChatReceived(ClientChatReceivedEvent event) {
		if (ChatCompat.isActionBar(event)) return;
		String plain = ChatCompat.plain(event);
		long now = System.currentTimeMillis();

		String prefix = "";
		String suffix = "";
		boolean rewrite = false;
		if (modules.chat.isEnabled()) {
			if (modules.chatStack.get()) {
				int count = stacker.accept(plain, now, ChatStacker.DEFAULT_WINDOW_MS);
				if (count > 1 && lastLineId != 0) {
					// Die vorherige (eigene) Zeile weicht der zusammengefassten.
					ChatCompat.deleteLine(lastLineId);
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
			if (ChatCompat.reprint(event, prefix, suffix, id)) {
				lastLineId = id;
			} else {
				lastLineId = 0;
			}
		} else {
			lastLineId = 0;
		}

		if (modules.autoGg.isEnabled()) {
			autoGg.onMessage(plain, AutoGg.extraTriggers(modules.autoGgTriggers.get()), now,
					(long) (modules.autoGgDelay.get() * 1000));
		}
	}

	/** Welt gewechselt / Chat geleert: Zusammenfassen neu beginnen. */
	public void onWorldChange() {
		stacker.reset();
		lastLineId = 0;
		autoGg.cancel();
	}

	/** Einmal je Client-Tick: geplante Auto-GG-Nachricht senden. */
	public void tick(Minecraft mc) {
		if (Mc.player() == null || !modules.autoGg.isEnabled()) {
			autoGg.cancel();
			return;
		}
		if (autoGg.due(System.currentTimeMillis())) {
			String text = ChatOut.sanitize(modules.autoGgText.get());
			if (!text.isEmpty()) Mc.sendChat(text);
		}
	}

	/** Text-Hotkey gedrückt (0–3). */
	public void onHotkey(int index) {
		if (!modules.textHotkeys.isEnabled() || index < 0 || index >= modules.hotkeyTexts.length) return;
		if (Mc.player() == null || Mc.screen() != null) return;
		String text = ChatOut.sanitize(modules.hotkeyTexts[index].get());
		if (text.isEmpty()) {
			Mc.actionBar("Text-Hotkey " + (index + 1) + " ist leer");
			return;
		}
		if (!hotkeys.tryAcquire(System.currentTimeMillis())) {
			Mc.actionBar("Text-Hotkey: zu schnell hintereinander");
			return;
		}
		Mc.sendChat(text);
	}

	/**
	 * Strg+Klick im Chat: die angeklickte Chat-Komponente in die Zwischenablage.
	 * true = Klick verbraucht.
	 */
	public boolean onChatClick(int rawMouseX, int rawMouseY) {
		if (!modules.chat.isEnabled() || !modules.chatCopy.get()) return false;
		try {
			String line = ChatCompat.componentAt(rawMouseX, rawMouseY);
			if (line == null || line.isEmpty()) return false;
			Mc.setClipboard(line);
			Mc.actionBar("Zeile kopiert");
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
