package dev.theredstonee.trsclient.core.chatheads;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 *
 * Based on Chat Heads by dzwdz (https://github.com/dzwdz/chat_heads), modified for the TRS Client.
 */

/**
 * Maße der Chat-Köpfe. Die Deckkraft-Umrechnung folgt Chat Heads. Umbruchbreite und der Einzug der Folgezeilen
 * sind die TRS-Anpassung an den eigenen Chat.
 */
public final class ChatHeadLayout {
	/** Kopf, 8×8 Texel, auf die Zeilenhöhe skaliert. */
	public static final int HEAD = 8;
	/** Abstand rechts vom Kopf, damit der Text nicht am Gesicht klebt. */
	public static final int PAD = 1;
	/** Verschiebung moderner Chats (Kopf + Abstand). */
	public static final int WIDTH = HEAD + PAD;
	/**
	 * Einzug auf 1.7–1.12: zwei Leerzeichen der Standardschrift sind 8 Pixel. Ein 9-Pixel-Raster gibt es dort nicht.
	 */
	public static final int LEGACY_PAD = 8;
	/** Schmaler wird der Umbruch nicht – sonst bleibt von der Zeile nichts übrig. */
	public static final int MIN_WRAP = 16;

	private ChatHeadLayout() {
	}

	/** Vanilla-Zeilenbreite minus Kopf. Unter {@link #MIN_WRAP} bleibt die Vanilla-Breite. */
	public static int wrapWidth(int vanillaWidth, int headWidth) {
		if (headWidth <= 0 || vanillaWidth <= 0) return vanillaWidth;
		int narrowed = vanillaWidth - headWidth;
		return narrowed < MIN_WRAP ? vanillaWidth : narrowed;
	}

	/** Kopf nur auf der ersten sichtbaren Zeile einer Nachricht (0 = oben). */
	public static boolean drawOnLine(int lineIndex) {
		return lineIndex == 0;
	}

	/**
	 * Deckkraft aus der Chat-Farbe ({@code 0xFFFFFF + (alpha << 24)}). Das hohe Byte ist bei voller
	 * Deckkraft negativ – deshalb die Umrechnung über +256.
	 */
	public static int opacityByte(int color) {
		return ((color >> 24) + 256) % 256;
	}

	/** Weiß mit der Deckkraft der Zeile, oder 0 wenn die Zeile praktisch unsichtbar ist. */
	public static int tint(int color) {
		int alpha = opacityByte(color);
		if (alpha < 4) return 0;
		return (alpha << 24) | 0xFFFFFF;
	}

	/**
	 * Jede schon umbrochene Zeile um zwei Leerzeichen einrücken. Die Leerzeichen sind der Platz für den Kopf;
	 * Folgezeilen bleiben so bündig, ohne dass der Text über den Rand läuft.
	 */
	public static String indentLines(java.util.List<String> lines) {
		if (lines == null || lines.isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) sb.append('\n');
			sb.append("  ");
			String line = lines.get(i);
			if (line != null) sb.append(line);
		}
		return sb.toString();
	}
}
