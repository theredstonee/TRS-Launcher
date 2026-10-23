package dev.theredstonee.trsrules.common;

/** Text helpers for config messages. */
public final class Text {
	private static final String CODES = "0123456789abcdefklmnorABCDEFKLMNOR";

	private Text() {
	}

	/** Converts {@code &}-color codes ({@code &c}, {@code &l} …) to {@code §} codes; {@code &&} stays a literal {@code &}. */
	public static String colorize(String s) {
		if (s == null || s.indexOf('&') < 0) return s;
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '&' && i + 1 < s.length()) {
				char n = s.charAt(i + 1);
				if (n == '&') {
					out.append('&');
					i++;
					continue;
				}
				if (CODES.indexOf(n) >= 0) {
					out.append('§').append(Character.toLowerCase(n));
					i++;
					continue;
				}
			}
			out.append(c);
		}
		return out.toString();
	}
}
