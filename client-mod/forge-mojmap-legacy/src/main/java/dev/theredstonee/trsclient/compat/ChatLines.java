package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.mixin.ChatComponentAccessor;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Zugriff auf die Chat-Zeilen – die einzige Stelle mit Versionsunterschieden der Chat-Anzeige:
 * bis 1.15.2 sind die Zeilen Komponenten, ab 1.16 vorformatierte Zeichenfolgen
 * ({@code GuiMessage<FormattedCharSequence>}), ab 1.19.1 ein eigener Datensatz
 * ({@code GuiMessage.Line}) mit der Markierung "Ende eines Eintrags".
 *
 * <p>Braucht den Mixin-Accessor {@link ChatComponentAccessor} – auf 1.14.4 (Forge ohne Mixin)
 * sind die Chat-Module deshalb ausgeblendet und diese Klasse wird nie aufgerufen.
 */
public final class ChatLines {
	private ChatLines() {
	}

	public static ChatComponent chat() {
		return Mc.mc().gui.getChat();
	}

	/** Schreibt eine Nachricht in den Chat. */
	public static void addMessage(Component message) {
		chat().addMessage(message);
	}

	/** Entfernt die neueste Nachricht, wenn sie genau diese Komponente ist (für "(x2)"). */
	public static boolean removeNewestIfSame(Component message) {
		ChatComponent component = chat();
		List<?> all = ((ChatComponentAccessor) component).trsclient$allMessages();
		if (all.isEmpty()) return false;
		Object newest = all.get(0);
		//? if >=1.19.1 {
		/*Component content = ((GuiMessage) newest).content();
		*///?} elif >=1.16 {
		@SuppressWarnings("unchecked")
		Component content = ((GuiMessage<Component>) newest).getMessage();
		//?} else {
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
		/*// Die Zeilen eines Eintrags liegen zusammen; die unterste ist mit "endOfEntry" markiert.
		int start = index;
		while (start > 0 && !((GuiMessage.Line) lines.get(start - 1)).endOfEntry()) start--;
		int end = index;
		while (end < lines.size() - 1 && !((GuiMessage.Line) lines.get(end)).endOfEntry()) end++;
		StringBuilder sb = new StringBuilder();
		for (int i = end; i >= start; i--) {
			append(sb, ((GuiMessage.Line) lines.get(i)).content());
		}
		return sb.toString().trim();
		*///?} elif >=1.16 {
		StringBuilder sb = new StringBuilder();
		@SuppressWarnings("unchecked")
		GuiMessage<net.minecraft.util.FormattedCharSequence> line =
				(GuiMessage<net.minecraft.util.FormattedCharSequence>) lines.get(index);
		append(sb, line.getMessage());
		return sb.toString().trim();
		//?} else {
		/*return ((GuiMessage) lines.get(index)).getMessage().getString().trim();
		*///?}
	}

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
