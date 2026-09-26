package dev.theredstonee.trsclient.qol;

import dev.theredstonee.trsclient.core.chat.ChatText;
import dev.theredstonee.trsclient.core.streamer.StreamerMode;
import net.minecraft.network.chat.Component;
//? if >=1.16 {
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
//?}

import java.util.List;
import java.util.Optional;

/**
 * Baut Chat-Komponenten neu auf: Namen ersetzen (Streamer-Modus) und Erwähnungen hervorheben. Ab 1.16 bleibt jedes
 * Textstück mit seinem Stil (Farbe, Klick-/Hover-Aktionen) erhalten; 1.14/1.15 arbeiten mit dem farbcodierten Text
 * (Klick-Aktionen gehen dort bei betroffenen Zeilen verloren). Dieselbe Datei in allen Mojmap-Bäumen.
 */
public final class QolText {
	private QolText() {
	}

	/**
	 * @param words    Wörter zum Hervorheben (null = keine)
	 * @param streamer Namen ersetzen (null = nicht)
	 */
	public static Component rewrite(Component message, List<String> words, StreamerMode streamer, int argb, boolean bold) {
		//? if >=1.16 {
		final MutableComponent out = literal("");
		message.visit((FormattedText.StyledContentConsumer<Object>) (style, text) -> {
			String raw = streamer == null ? text : streamer.replace(text);
			List<int[]> hits = words == null ? null : ChatText.find(raw, words);
			if (hits == null || hits.isEmpty()) {
				if (!raw.isEmpty()) out.append(literal(raw).setStyle(style));
				return Optional.empty();
			}
			int pos = 0;
			for (int[] h : hits) {
				if (h[0] > pos) out.append(literal(raw.substring(pos, h[0])).setStyle(style));
				Style mark = style.withColor(TextColor.fromRgb(argb & 0xFFFFFF));
				if (bold) mark = mark.withBold(true);
				out.append(literal(ChatText.strip(raw.substring(h[0], h[1]))).setStyle(mark));
				pos = h[1];
			}
			if (pos < raw.length()) {
				out.append(literal(ChatText.activeCodes(raw.substring(0, pos)) + raw.substring(pos)).setStyle(style));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return out;
		//?} else {
		/*String raw = message.getColoredString();
		if (streamer != null) raw = streamer.replace(raw);
		if (words != null) {
			List<int[]> hits = ChatText.find(raw, words);
			if (!hits.isEmpty()) {
				StringBuilder sb = new StringBuilder();
				int pos = 0;
				for (int[] h : hits) {
					sb.append(raw, pos, h[0]);
					sb.append(ChatText.CODE).append('e');
					if (bold) sb.append(ChatText.CODE).append('l');
					sb.append(ChatText.strip(raw.substring(h[0], h[1])));
					sb.append(ChatText.CODE).append('r').append(ChatText.activeCodes(raw.substring(0, h[1])));
					pos = h[1];
				}
				sb.append(raw.substring(pos));
				raw = sb.toString();
			}
		}
		return new net.minecraft.network.chat.TextComponent(raw);
		*///?}
	}

	/** Farbcode für Rot (Punktzahlen wie Vanilla). */
	public static final String RED = ChatText.CODE + "c";

	/**
	 * Komponente als Text mit alten Farbcodes – den kann jede Version über {@code Font#draw(String)} zeichnen. Eigene
	 * RGB-Farben (ab 1.16) werden auf die nächste der 16 Minecraft-Farben gerundet.
	 */
	public static String legacy(Component c) {
		if (c == null) return "";
		//? if >=1.16 {
		final StringBuilder sb = new StringBuilder();
		c.visit((FormattedText.StyledContentConsumer<Object>) (style, text) -> {
			if (text.isEmpty()) return Optional.empty();
			sb.append(codes(style)).append(text);
			return Optional.empty();
		}, Style.EMPTY);
		return sb.toString();
		//?} else
		/*return c.getColoredString();*/
	}

	//? if >=1.16 {
	private static String codes(Style s) {
		StringBuilder b = new StringBuilder();
		b.append(ChatText.CODE).append('r');
		TextColor color = s.getColor();
		if (color != null) b.append(ChatText.CODE).append(nearest(color.getValue()));
		if (s.isBold()) b.append(ChatText.CODE).append('l');
		if (s.isItalic()) b.append(ChatText.CODE).append('o');
		if (s.isUnderlined()) b.append(ChatText.CODE).append('n');
		if (s.isStrikethrough()) b.append(ChatText.CODE).append('m');
		if (s.isObfuscated()) b.append(ChatText.CODE).append('k');
		return b.toString();
	}

	// Nächste der 16 Minecraft-Farben (Farbcode-Zeichen), feste Tabelle – unabhängig von der ChatFormatting-API.
	private static char nearest(int rgb) {
		char best = 'f';
		long bestD = Long.MAX_VALUE;
		for (int i = 0; i < 16; i++) {
			int v = COLORS[i];
			int dr = ((v >> 16) & 0xFF) - ((rgb >> 16) & 0xFF);
			int dg = ((v >> 8) & 0xFF) - ((rgb >> 8) & 0xFF);
			int db = (v & 0xFF) - (rgb & 0xFF);
			long d = (long) dr * dr + (long) dg * dg + (long) db * db;
			if (d < bestD) {
				bestD = d;
				best = "0123456789abcdef".charAt(i);
			}
		}
		return best;
	}

	private static final int[] COLORS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555,
			0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
	//?}

	/** Breite einer Komponente in der Spielschrift. */
	public static int width(net.minecraft.client.gui.Font font, Component text) {
		//? if >=1.16 {
		return font.width(text);
		//?} else
		/*return font.width(text.getColoredString());*/
	}

	/** Einfacher Text als Komponente. */
	public static Component plain(String text) {
		//? if >=1.19 {
		/*return Component.literal(text);
		*///?} else
		return new net.minecraft.network.chat.TextComponent(text);
	}

	/** Punktzahl rot wie Vanillas Scoreboard. */
	public static Component score(int value) {
		//? if >=1.19 {
		/*return Component.literal(String.valueOf(value)).withStyle(net.minecraft.ChatFormatting.RED);
		*///?} else
		return new net.minecraft.network.chat.TextComponent(String.valueOf(value)).withStyle(net.minecraft.ChatFormatting.RED);
	}

	//? if >=1.19 {
	/*static MutableComponent literal(String text) {
		return Component.literal(text);
	}
	*///?} elif >=1.16 {
	static MutableComponent literal(String text) {
		return new net.minecraft.network.chat.TextComponent(text);
	}
	//?}
}
