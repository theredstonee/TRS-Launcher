package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.ChatLines;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.chat.AutoGg;
import dev.theredstonee.trsclient.core.chat.ChatOut;
import dev.theredstonee.trsclient.core.chat.ChatStacker;
import dev.theredstonee.trsclient.core.chat.ChatTimestamp;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.util.RateLimiter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.LocalTime;
import java.util.List;

/**
 * Chat-Verbesserungen: Zeitstempel, Zusammenfassen gleicher Nachrichten, Kopieren per Strg+Klick,
 * Auto-GG und Text-Hotkeys. Alles, was selbst sendet, ist standardmäßig aus und streng begrenzt.
 */
public final class ChatFeatures {
	private final TrsModules modules;
	private final ChatStacker stacker = new ChatStacker();
	private final AutoGg autoGg = new AutoGg();
	/** Höchstens eine Hotkey-Nachricht pro Sekunde, höchstens drei in zehn Sekunden. */
	private final RateLimiter hotkeys = new RateLimiter(1000, 3, 10_000);
	/** Zuletzt von uns erzeugte Komponente – nur die darf beim Zusammenfassen ersetzt werden. */
	private Component lastAdded;
	/** Solange gesetzt, wird die eigene Ersetzung nicht erneut bearbeitet. */
	private boolean reentrant;

	public ChatFeatures(TrsModules modules) {
		this.modules = modules;
	}

	/**
	 * Eine eingehende Chat-Nachricht; liefert die tatsächlich anzuzeigende Komponente.
	 * Aus dem Mixin auf {@code ChatComponent#addMessage}.
	 */
	public Component onMessage(Component message) {
		if (message == null || reentrant) return message;
		String plain = message.getString();
		Component result = message;
		if (modules.chat.isEnabled()) {
			long now = System.currentTimeMillis();
			if (modules.chatStack.get()) {
				int count = stacker.accept(plain, now, ChatStacker.DEFAULT_WINDOW_MS);
				if (count > 1) {
					reentrant = true;
					try {
						// Vorherige Zeile entfernen – nur wenn sie noch die oberste ist.
						if (ChatLines.removeNewestIfSame(lastAdded)) {
							result = Mc.text("").append(message)
									.append(Mc.text(ChatStacker.suffix(count)).withStyle(ChatFormatting.GRAY));
						} else {
							stacker.reset();
							stacker.accept(plain, now, ChatStacker.DEFAULT_WINDOW_MS);
						}
					} catch (RuntimeException e) {
						// Chat darf nie am Client scheitern.
						TrsClient.LOGGER.error("Chat-Zusammenfassung fehlgeschlagen", e);
						stacker.reset();
					} finally {
						reentrant = false;
					}
				}
			}
			if (modules.chatTimestamps.get()) {
				LocalTime time = LocalTime.now();
				String stamp = ChatTimestamp.format(time.getHour(), time.getMinute(), time.getSecond(),
						modules.chatTimestampSeconds.get(), false);
				result = Mc.text(stamp).withStyle(ChatFormatting.DARK_GRAY).append(result);
			}
		}
		if (modules.autoGg.isEnabled()) {
			List<String> extra = AutoGg.extraTriggers(modules.autoGgTriggers.get());
			autoGg.onMessage(plain, extra, System.currentTimeMillis(),
					(long) (modules.autoGgDelay.get() * 1000));
		}
		lastAdded = result;
		return result;
	}

	/** Chat geleert (F3+D, Weltwechsel): Zusammenfassen neu beginnen. */
	public void onChatCleared() {
		stacker.reset();
		lastAdded = null;
	}

	/** Einmal je Client-Tick: geplante Auto-GG-Nachricht senden. */
	public void tick(Minecraft mc) {
		if (mc.player == null) {
			autoGg.cancel();
			return;
		}
		if (!modules.autoGg.isEnabled()) {
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
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || Mc.screen() != null) return;
		String text = ChatOut.sanitize(modules.hotkeyTexts[index].get());
		if (text.isEmpty()) {
			Mc.actionBar(Mc.text(I18n.tr("chat.hotkeyEmpty", index + 1)));
			return;
		}
		if (!hotkeys.tryAcquire(System.currentTimeMillis())) {
			Mc.actionBar(Mc.text(I18n.tr("chat.hotkeyTooFast")));
			return;
		}
		Mc.sendChat(text);
	}

	/**
	 * Strg+Klick im Chat: Zeile in die Zwischenablage. true = Klick verbraucht.
	 * Aus dem Mixin auf {@code ChatScreen#mouseClicked}.
	 */
	public boolean onChatClick(double mouseX, double mouseY, boolean control) {
		if (!control || !modules.chat.isEnabled() || !modules.chatCopy.get()) return false;
		try {
			String line = ChatLines.lineAt(mouseX, mouseY, Mc.window().getGuiScaledHeight());
			if (line == null || line.isEmpty()) return false;
			Mc.setClipboard(line);
			Mc.actionBar(Mc.text(I18n.tr("chat.lineCopied")));
			return true;
		} catch (RuntimeException e) {
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
