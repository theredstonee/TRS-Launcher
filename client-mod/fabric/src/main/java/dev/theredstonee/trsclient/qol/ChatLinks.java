package dev.theredstonee.trsclient.qol;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.ChatLines;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.chat.ChatCoords;
import dev.theredstonee.trsclient.core.chat.ChatText;
import dev.theredstonee.trsclient.core.clips.ScreenshotShare;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.screenshot.Screenshots;
import dev.theredstonee.trsclient.core.ui.WaypointSaveUi;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.network.chat.Component;
//? if >=1.16 {
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
//?}

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Anklickbares im Minecraft-Chat (nur lokal, sendet nichts): Koordinaten werden unterstrichen und öffnen per Klick
 * „Als Wegpunkt speichern“; die Vanilla-Zeile „Bildschirmfoto gespeichert …“ bekommt „[Als Link teilen]“. Erkannt
 * wird der Klick am Einfüge-Text des Stils ({@code "x y z"} bzw. {@code trs-share:<datei>}) – Umschalt-Klick fügt
 * wie in Vanilla nur diesen Text ins Eingabefeld ein. Ab 1.16; davor bleibt der Chat unverändert.
 * Dieselbe Datei in allen Mojmap-Bäumen.
 */
public final class ChatLinks {
	private static boolean uploading;

	/** Hängt diese Version Aktionen an die Vanilla-Chatzeile „Bildschirmfoto gespeichert …“? */
	//? if >=1.16 {
	public static final boolean DECORATES = true;
	//?} else
	/*public static final boolean DECORATES = false;*/

	private ChatLinks() {
	}

	private static TrsModules modules() {
		TrsClient c = TrsClient.get();
		return c == null ? null : c.modules();
	}

	private static boolean coordsOn(TrsModules m) {
		return m != null && m.chat.isEnabled() && m.chatCoordLinks.get();
	}

	/** Eingehende Nachricht schmücken (aus ChatFeatures#onMessage). Nie Ausnahmen nach außen. */
	public static Component decorate(Component message) {
		//? if >=1.16 {
		if (message == null) return null;
		try {
			TrsModules m = modules();
			boolean coords = coordsOn(m) && !ChatCoords.find(message.getString()).isEmpty();
			String shot = screenshotName(message);
			if (!coords && shot == null) return message;
			MutableComponent out = coords ? withCoords(message) : message.copy();
			if (shot != null) {
				Screenshots svc = Screenshots.get();
				if (svc != null && svc.onVanillaLine(shot)) {
					// Screenshot-Werkzeuge: [Bearbeiten] [Link kopieren] [Bild kopieren]
					for (Screenshots.Part p : svc.actionParts(shot)) out.append(part(p));
				} else if (svc == null || !svc.enabled()) {
					Style st = Style.EMPTY.applyFormat(ChatFormatting.GOLD).withUnderlined(true)
							.withInsertion(ScreenshotShare.marker(shot)).withHoverEvent(hover(I18n.tr("chat.screenshot.shareHover")));
					out.append(QolText.literal(" ")).append(QolText.literal(I18n.tr("chat.screenshot.share")).setStyle(st));
				}
			}
			return out;
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("Chat-Links fehlgeschlagen", e);
			return message;
		}
		//?} else
		/*return message;*/
	}

	//? if >=1.16 {
	// Koordinaten in Textstücken ohne eigene Klick-/Einfüge-Aktion unterstreichen und markieren.
	private static MutableComponent withCoords(Component message) {
		final MutableComponent out = QolText.literal("");
		final String hoverText = I18n.tr("chat.coords.hover");
		message.visit((FormattedText.StyledContentConsumer<Object>) (style, text) -> {
			if (text.isEmpty()) return Optional.empty();
			List<ChatCoords.Hit> hits = style.getClickEvent() == null && style.getInsertion() == null ? ChatCoords.find(text)
					: Collections.<ChatCoords.Hit>emptyList();
			if (hits.isEmpty()) {
				out.append(QolText.literal(text).setStyle(style));
				return Optional.empty();
			}
			int pos = 0;
			for (ChatCoords.Hit h : hits) {
				if (h.start > pos) out.append(QolText.literal(ChatText.activeCodes(text.substring(0, pos)) + text.substring(pos, h.start)).setStyle(style));
				Style mark = style.withUnderlined(true).withInsertion(h.insertion()).withHoverEvent(hover(hoverText));
				out.append(QolText.literal(ChatText.activeCodes(text.substring(0, h.start)) + text.substring(h.start, h.end)).setStyle(mark));
				pos = h.end;
			}
			if (pos < text.length()) out.append(QolText.literal(ChatText.activeCodes(text.substring(0, pos)) + text.substring(pos)).setStyle(style));
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}

	// Bildschirmfoto-Zeile: Stil mit „Datei öffnen“ auf eine Datei unter screenshots/ → relativer Name, sonst null.
	private static String screenshotName(Component message) {
		final String[] found = {null};
		message.visit((FormattedText.StyledContentConsumer<Object>) (style, text) -> {
			String p = openFile(style.getClickEvent());
			if (p != null && found[0] == null) found[0] = p;
			return Optional.empty();
		}, Style.EMPTY);
		if (found[0] == null) return null;
		try {
			return ScreenshotShare.relative(gameDir(), java.nio.file.Paths.get(found[0]));
		} catch (RuntimeException e) {
			return null;
		}
	}

	// Klick auf einen Stil im Chat (ohne Umschalt): unsere Marker behandeln. true = verbraucht. Stile mit eigener
	// Klick-Aktion (Links des Servers) bleiben unberührt.
	public static boolean onClick(Style style) {
		if (style == null || style.getClickEvent() != null) return false;
		String ins = style.getInsertion();
		if (ins == null) return false;
		try {
			int[] c = coordsOn(modules()) ? ChatCoords.parseInsertion(ins) : null;
			if (c != null) {
				openSave(c[0], c[1], c[2]);
				return true;
			}
			String shot = ScreenshotShare.parseMarker(ins);
			if (shot != null) {
				share(shot);
				return true;
			}
			Screenshots svc = Screenshots.get();
			if (svc != null && svc.onMarker(ins)) return true;
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("Chat-Klick fehlgeschlagen", e);
		}
		return false;
	}
	//?}

	//? if >=1.16 {
	// Ein Teil der Screenshot-Chatzeile als Komponente (Aktion = gold, Dateiname unterstrichen).
	private static MutableComponent part(Screenshots.Part p) {
		Style st = Style.EMPTY;
		if (p.kind == Screenshots.Part.Kind.ACTION) st = st.applyFormat(ChatFormatting.GOLD);
		else if (p.kind == Screenshots.Part.Kind.NAME) st = st.withUnderlined(true);
		if (p.insertion != null) st = st.withInsertion(p.insertion);
		if (p.hover != null) st = st.withHoverEvent(hover(p.hover));
		return QolText.literal(p.text).setStyle(st);
	}
	//?}

	/**
	 * Eigene Zeile „Bildschirmfoto gespeichert: &lt;name&gt; [Bearbeiten] …“ – wenn keine Vanilla-Zeile kam (z. B. weil
	 * Essential sie verschluckt). Ab 1.16; davor false.
	 */
	public static boolean addScreenshotLine(String relativeName) {
		//? if >=1.16 {
		Screenshots svc = Screenshots.get();
		if (svc == null) return false;
		MutableComponent line = QolText.literal("");
		for (Screenshots.Part p : svc.chatLine(relativeName)) line.append(part(p));
		ChatLines.addMessage(line);
		return true;
		//?} else
		/*return false;*/
	}

	// Klick-/Hover-Ereignisse: ab 1.21.5 eigene Datensätze je Art.
	//? if >=1.21.5 {
	/*private static String openFile(ClickEvent e) {
		return e instanceof ClickEvent.OpenFile ? ((ClickEvent.OpenFile) e).path() : null;
	}

	private static HoverEvent hover(String text) {
		return new HoverEvent.ShowText(QolText.literal(text));
	}
	*///?} elif >=1.16 {
	private static String openFile(ClickEvent e) {
		return e != null && e.getAction() == ClickEvent.Action.OPEN_FILE ? e.getValue() : null;
	}

	private static HoverEvent hover(String text) {
		return new HoverEvent(HoverEvent.Action.SHOW_TEXT, QolText.literal(text));
	}
	//?}

	private static Path gameDir() {
		return Mc.mc().gameDirectory.toPath().toAbsolutePath();
	}

	public static void notice(String text) {
		// Direkt statt Mc.actionBar: dessen Parameter unterscheidet sich zwischen den Bäumen (Text/Komponente).
		//? if >=26.2 {
		/*Mc.mc().gui.hud.setOverlayMessage(QolText.plain(text), false);
		*///?} else
		Mc.mc().gui.setOverlayMessage(QolText.plain(text), false);
	}

	/** Fenster „Als Wegpunkt speichern“ (ersetzt den Chat). */
	public static void openSave(int x, int y, int z) {
		WaypointSaveUi.Host host = new WaypointSaveUi.Host() {
			@Override
			public void closeScreen() {
				Mc.setScreen(null);
			}

			@Override
			public void playClick() {
				new dev.theredstonee.trsclient.screen.TrsMenuHost(null).playClick();
			}

			@Override
			public void notice(String text) {
				ChatLinks.notice(text);
			}

			@Override
			public String paste() {
				return null;
			}
		};
		Mc.setScreen(new TrsUiScreen(I18n.tr("waypoint.save.title"),
				new WaypointSaveUi(host, x, y, z, I18n.tr("waypoint.save.defaultName"))));
	}

	/** „[Als Link teilen]“: hochladen, Link in die Zwischenablage, Hinweis + Chatzeile mit dem Link. */
	public static void share(String name) {
		if (uploading) return;
		Path file = ScreenshotShare.resolve(gameDir(), name);
		if (file == null) {
			notice(I18n.tr("social.error.image_unreadable"));
			return;
		}
		uploading = true;
		boolean started = ScreenshotShare.share(file, (value, error) -> {
			uploading = false;
			if (value == null) {
				notice(I18n.tr(error == null ? "social.error.generic" : error));
				return;
			}
			Mc.setClipboard(value.url);
			String text = ScreenshotShare.copiedText(value, true);
			notice(text);
			ChatLines.addMessage(QolText.plain("TRS: " + text + " – " + value.url));
		});
		if (!started) {
			uploading = false;
			notice(I18n.tr("clips.share.offline"));
		} else if (uploading) {
			notice(I18n.tr("chat.screenshot.uploading"));
		}
	}
}
