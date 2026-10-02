package dev.theredstonee.trsclient.core.chatheads;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 *
 * Based on Chat Heads by dzwdz (https://github.com/dzwdz/chat_heads), modified for the TRS Client.
 */

/**
 * Gesicht (UV 8,8) und Hut (UV 40,8) übereinander, wie {@code ChatHeads.blendColors} / {@code extractBlendedHead}.
 * Die Farbe ist ARGB oder ABGR – beide Ebenen müssen dasselbe Format haben. Java 8, ohne {@code Math.clamp}.
 */
public final class ChatHeadBlend {
	private ChatHeadBlend() {
	}

	/** {@code color2} über {@code color1}. Volle Deckkraft übernimmt die obere Farbe, 0 lässt die untere stehen. */
	public static int blend(int color1, int color2) {
		float a1 = ((color1 >>> 24) & 0xFF) / 255f;
		float r1 = ((color1 >> 16) & 0xFF) / 255f;
		float g1 = ((color1 >> 8) & 0xFF) / 255f;
		float b1 = (color1 & 0xFF) / 255f;

		float a2 = ((color2 >>> 24) & 0xFF) / 255f;
		float r2 = ((color2 >> 16) & 0xFF) / 255f;
		float g2 = ((color2 >> 8) & 0xFF) / 255f;
		float b2 = (color2 & 0xFF) / 255f;

		float a3 = a2 * a2 + (1f - a2) * a1;
		float r3 = a2 * r2 + (1f - a2) * r1;
		float g3 = a2 * g2 + (1f - a2) * g1;
		float b3 = a2 * b2 + (1f - a2) * b1;
		return (clamp(a3 * 255f) << 24) | (clamp(r3 * 255f) << 16) | (clamp(g3 * 255f) << 8) | clamp(b3 * 255f);
	}

	/**
	 * Kopf als ARGB-Bild ({@code 8 * Maßstab} Kantenlänge) oder null, wenn die Haut zu klein ist.
	 * 64×32-Skins (Höhe = halbe Breite) nutzen die alte Hut-Zeile.
	 */
	public static int[] extract(int[] pixels, int width, int height, boolean hat) {
		if (pixels == null || width < 64 || height <= 0 || pixels.length < width * height) return null;
		boolean legacy = width / 2 == height;
		int minHeight = legacy ? 32 : 64;
		if (height < minHeight) return null;
		int xScale = width / 64;
		int yScale = height / minHeight;
		if (xScale < 1 || yScale < 1) return null;
		int w = 8 * xScale;
		int h = 8 * yScale;
		int[] out = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int face = at(pixels, width, 8 * xScale + x, 8 * yScale + y);
				int overlay = hat ? at(pixels, width, 40 * xScale + x, 8 * yScale + y) : 0;
				out[y * w + x] = blend(face, overlay);
			}
		}
		return out;
	}

	private static int at(int[] pixels, int width, int x, int y) {
		return pixels[y * width + x];
	}

	private static int clamp(float value) {
		if (value < 0f) return 0;
		if (value > 255f) return 255;
		return (int) value;
	}
}
