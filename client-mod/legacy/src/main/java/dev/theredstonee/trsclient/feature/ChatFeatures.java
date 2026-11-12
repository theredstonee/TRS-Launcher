package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.ChatCompat;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.chat.AutoGg;
import dev.theredstonee.trsclient.core.chat.ChatCoords;
import dev.theredstonee.trsclient.core.chat.ChatOut;
import dev.theredstonee.trsclient.core.chat.ChatStacker;
import dev.theredstonee.trsclient.core.chat.ChatTimestamp;
import dev.theredstonee.trsclient.core.chat.ChatWaypoints;
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
		// Fair-Play-Codes der Karten-Mods (Server erzwingt Fair Play).
		dev.theredstonee.trsclient.core.map.MapEngine maps = dev.theredstonee.trsclient.core.map.MapEngine.get();
		if (maps != null) maps.onServerText(ChatCompat.formatted(event));
		// Server-Codes der freien Kamera (sperren bzw. Fair Play erzwingen).
		dev.theredstonee.trsclient.core.cinematic.Cinematic.serverText(ChatCompat.formatted(event));
		long now = System.currentTimeMillis();
		// Koordinaten anklickbar machen („Als Wegpunkt speichern“, nur lokal).
		if (modules.chat.isEnabled() && modules.chatCoordLinks.get() && !ChatCoords.find(plain).isEmpty()) {
			try {
				ChatCompat.markCoords(event, I18n.tr("chat.coords.hover"));
			} catch (RuntimeException e) {
				TrsClient.LOGGER.error("Koordinaten im Chat konnten nicht markiert werden", e);
			}
		}

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
						modules.chatTimestampSeconds.get(), modules.qol.chatTwelveHour.get()) + "§r";
				rewrite = true;
			}
		}
		// Köpfe auch ohne Zeitstempel/Stapeln: die Zeile muss selbst umbrochen werden, damit Folgezeilen einrücken.
		if (modules.qol.chatHeads.isEnabled()) rewrite = true;
		if (rewrite) {
			int id = nextId++;
			boolean printed;
			// Mit „[Add]“-Knopf (geteilter Wegpunkt) nicht umbrechen: das verlöre den Knopf – dann ohne Einrückung.
			if (modules.qol.chatHeads.isEnabled() && !ChatCompat.hasWaypointButton(event)) {
				String hover = modules.chat.isEnabled() && modules.chatCoordLinks.get() ? I18n.tr("chat.coords.hover") : null;
				printed = ChatCompat.reprintIndented(event, prefix, suffix, id, hover);
			} else {
				printed = ChatCompat.reprint(event, prefix, suffix, id);
			}
			if (printed) {
				lastLineId = id;
			} else {
				lastLineId = 0;
			}
		} else {
			lastLineId = 0;
		}

		if (modules.autoGg.isEnabled()) {
			autoGg.onMessage(plain, AutoGg.extraTriggers(modules.autoGgTriggers.get()), modules.qol.autoGgPresets.get(), now,
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
			Mc.actionBar(I18n.tr("chat.hotkeyEmpty", index + 1));
			return;
		}
		if (!hotkeys.tryAcquire(System.currentTimeMillis())) {
			Mc.actionBar(I18n.tr("chat.hotkeyTooFast"));
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
			Mc.actionBar(I18n.tr("chat.lineCopied"));
			return true;
		} catch (RuntimeException e) {
			// Der Chat darf nie am Client scheitern.
			TrsClient.LOGGER.error("Chat-Zeile konnte nicht kopiert werden", e);
			return false;
		}
	}

	/**
	 * Klick auf markierte Koordinaten (Einfüge-Text „x y z“ ohne eigene Klick-Aktion) bzw. auf einen geteilten Wegpunkt
	 * von Xaero's Minimap/JourneyMap (Einfüge-Text = die Nachricht): Fenster „Als Wegpunkt speichern“. true = Klick
	 * verbraucht.
	 */
	public boolean onLinkClick(int rawMouseX, int rawMouseY) {
		if (!modules.chat.isEnabled() || !modules.chatCoordLinks.get()) return false;
		try {
			// Xaeros eigenes „[Add]“ (Xaero's Minimap installiert): auch in die TRS-Karte – ein Knopf für beide. Nicht
			// verbrauchen, damit Xaero den Wegpunkt selbst übernimmt.
			ChatWaypoints.Shared xaero = ChatWaypoints.fromClickCommand(ChatCompat.clickCommandAt(rawMouseX, rawMouseY));
			if (xaero != null) {
				String result = dev.theredstonee.trsclient.core.waypoint.WaypointShare.addShared(xaero);
				TrsClient.LOGGER.info("[Chat] [Add] {} → {} (Xaero-Knopf)", xaero, result);
				saveHost().notice(result);
				ChatCompat.printPlain("§a[TRS] " + result);
				return false;
			}
			String insertion = ChatCompat.insertionAt(rawMouseX, rawMouseY);
			// „[Hinzufügen]“: sofort auf die Karte, Hinweis in der Aktionsleiste und im Chat.
			ChatWaypoints.Shared add = ChatWaypoints.parseAddMarker(insertion);
			if (add != null) {
				saveHost().playClick();
				String result = dev.theredstonee.trsclient.core.waypoint.WaypointShare.addShared(add);
				TrsClient.LOGGER.info("[Chat] [Add] {} → {} (Welt {}, hier {})", add, result,
						dev.theredstonee.trsclient.core.waypoint.WaypointShare.currentWorldKey(),
						dev.theredstonee.trsclient.core.waypoint.WaypointShare.currentDimension());
				saveHost().notice(result);
				ChatCompat.printPlain("§a[TRS] " + result);
				return true;
			}
			ChatWaypoints.Shared shared = ChatWaypoints.parse(insertion);
			if (shared != null) {
				openSave(new dev.theredstonee.trsclient.core.ui.WaypointSaveUi(saveHost(), shared));
				return true;
			}
			int[] c = ChatCoords.parseInsertion(insertion);
			if (c == null) return false;
			openSave(c[0], c[1], c[2]);
			return true;
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("Koordinaten-Klick fehlgeschlagen", e);
			return false;
		}
	}

	/** Fenster „Als Wegpunkt speichern“ (ersetzt den Chat). */
	public static void openSave(int x, int y, int z) {
		openSave(new dev.theredstonee.trsclient.core.ui.WaypointSaveUi(saveHost(), x, y, z, I18n.tr("waypoint.save.defaultName")));
	}

	private static void openSave(dev.theredstonee.trsclient.core.ui.WaypointSaveUi ui) {
		Mc.setScreen(new dev.theredstonee.trsclient.screen.TrsUiScreen(I18n.tr("waypoint.save.title"), ui));
	}

	private static dev.theredstonee.trsclient.core.ui.WaypointSaveUi.Host saveHost() {
		final dev.theredstonee.trsclient.screen.TrsMenuHost back = new dev.theredstonee.trsclient.screen.TrsMenuHost(null);
		return new dev.theredstonee.trsclient.core.ui.WaypointSaveUi.Host() {
			@Override
			public void closeScreen() {
				Mc.setScreen(null);
			}

			@Override
			public void playClick() {
				back.playClick();
			}

			@Override
			public void notice(String text) {
				Mc.actionBar(text);
			}

			@Override
			public String paste() {
				return Mc.clipboard();
			}
		};
	}

	public AutoGg autoGg() {
		return autoGg;
	}

	public ChatStacker stacker() {
		return stacker;
	}
}
