package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.mixin.ChatComponentAccessor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
//? if >=26.1 {
/*import net.minecraft.client.multiplayer.chat.GuiMessage;
*///?} else
import net.minecraft.client.GuiMessage;

import java.util.List;

/**
 * Zugriff auf die Chat-Zeilen – die einzige Stelle mit Versionsunterschieden der Chat-Anzeige:
 * bis 1.15 sind die Zeilen Komponenten, ab 1.16 vorformatierte Zeichenfolgen, ab 1.19.1 ein
 * eigener Datensatz mit "Ende eines Eintrags" (ab 26.1 in einem anderen Paket).
 */
public final class ChatLines {
	private ChatLines() {
	}

	public static ChatComponent chat() {
		//? if >=26.2 {
		/*return Mc.mc().gui.hud.getChat();
		*///?} else
		return Mc.mc().gui.getChat();
	}

	/** Schreibt eine Nachricht in den Chat (ab 26.1 heißt die Methode addClientSystemMessage). */
	public static void addMessage(Component message) {
		//? if >=26.1 {
		/*chat().addClientSystemMessage(message);
		*///?} else
		chat().addMessage(message);
	}

	/** Entfernt die neueste Nachricht, wenn sie genau diese Komponente ist (für "(x2)"). */
	public static boolean removeNewestIfSame(Component message) {
		ChatComponent component = chat();
		List<?> all = ((ChatComponentAccessor) component).trsclient$allMessages();
		if (all.isEmpty()) return false;
		Object newest = all.get(0);
		//? if >=1.19.1 {
		Component content = ((GuiMessage) newest).content();
		//?} elif >=1.16 {
		/*Component content = ((GuiMessage<Component>) newest).getMessage();
		*///?} else {
		/*Component content = ((GuiMessage) newest).getMessage();
		*///?}
		if (content != message) return false;
		all.remove(0);
		component.rescaleChat();
		return true;
	}

	/**
	 * Text der Chat-Zeile unter dem Mauszeiger (Bildschirmkoordinaten), oder null.
	 * Gerechnet wie Vanilla: der Chat sitzt 40 Pixel über dem unteren Rand, jede Zeile ist
	 * {@link Mc#chatLineHeight()} hoch, alles skaliert mit der Chat-Größe.
	 */
	public static String lineAt(double mouseX, double mouseY, int guiHeight) {
		ChatComponentAccessor accessor = (ChatComponentAccessor) chat();
		List<?> lines = accessor.trsclient$trimmedMessages();
		if (lines.isEmpty()) return null;
		double scale = Mc.chatScale();
		if (scale <= 0) return null;
		int lineHeight = Math.max(1, Mc.chatLineHeight());
		double chatY = (guiHeight - mouseY - 40.0) / scale;
		if (chatY < 0 || mouseX < 0) return null;
		int index = (int) (chatY / lineHeight) + accessor.trsclient$chatScrollbarPos();
		if (index < 0 || index >= lines.size()) return null;
		return text(lines, index);
	}

	/** Baut den Text eines Eintrags ab der angeklickten Zeile zusammen. */
	private static String text(List<?> lines, int index) {
		//? if >=1.19.1 {
		// Die Zeilen eines Eintrags liegen zusammen; die unterste ist mit "endOfEntry" markiert.
		int start = index;
		while (start > 0 && !((GuiMessage.Line) lines.get(start - 1)).endOfEntry()) start--;
		int end = index;
		while (end < lines.size() - 1 && !((GuiMessage.Line) lines.get(end)).endOfEntry()) end++;
		StringBuilder sb = new StringBuilder();
		for (int i = end; i >= start; i--) {
			append(sb, ((GuiMessage.Line) lines.get(i)).content());
		}
		return sb.toString().trim();
		//?} elif >=1.16 {
		/*StringBuilder sb = new StringBuilder();
		append(sb, ((GuiMessage<net.minecraft.util.FormattedCharSequence>) lines.get(index)).getMessage());
		return sb.toString().trim();
		*///?} else {
		/*// Bis 1.15 enthält der Text noch die Farbcodes – die gehören nicht in die Zwischenablage.
		return stripColors(((GuiMessage) lines.get(index)).getMessage().getString()).trim();
		*///?}
	}

	//? if <1.16 {
	/*/^* Entfernt "§x"-Farbcodes aus einem Text. *^/
	private static String stripColors(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '§' && i + 1 < text.length()) {
				i++;
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}
	*///?}

	//? if >=1.16 {
	/** Zeichen einer vorformatierten Zeile anhängen (ohne Farbcodes). */
	private static void append(StringBuilder sb, net.minecraft.util.FormattedCharSequence line) {
		line.accept((pos, style, codePoint) -> {
			sb.appendCodePoint(codePoint);
			return true;
		});
	}
	//?}
}
