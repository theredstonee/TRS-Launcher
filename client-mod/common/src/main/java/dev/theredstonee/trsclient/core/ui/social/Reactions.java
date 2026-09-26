package dev.theredstonee.trsclient.core.ui.social;

/**
 * Die festen Reaktionen (API.md §18.1) als 8×8-Pixelsymbole in eigener Farbe – Minecraft-Schriften können Emoji nicht
 * zuverlässig zeichnen, Pixelsymbole sehen in jeder Version gleich aus.
 */
public final class Reactions {
	private Reactions() {
	}

	public static String icon(String id) {
		switch (id) {
			case "thumbs_up":
				return "thumb";
			case "heart":
				return "heart";
			case "laugh":
				return "laugh";
			case "wow":
				return "wow";
			case "sad":
				return "sad";
			case "angry":
				return "angry";
			case "party":
				return "party";
			case "fire":
				return "fire";
			case "eyes":
				return "eye";
			case "check":
				return "check";
			default:
				return "smile";
		}
	}

	public static int color(String id) {
		switch (id) {
			case "thumbs_up":
				return 0xFFFFD27A;
			case "heart":
				return 0xFFFF5566;
			case "laugh":
			case "wow":
			case "sad":
				return 0xFFFFD84A;
			case "angry":
				return 0xFFFF7A3A;
			case "party":
				return 0xFFB77CFF;
			case "fire":
				return 0xFFFF8A2A;
			case "eyes":
				return 0xFFE8E8F0;
			case "check":
				return 0xFF58D26A;
			default:
				return 0xFFFFFFFF;
		}
	}
}
