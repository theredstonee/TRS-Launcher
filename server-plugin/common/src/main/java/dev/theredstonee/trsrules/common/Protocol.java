package dev.theredstonee.trsrules.common;

import java.nio.charset.Charset;

/**
 * Wire protocol shared with the TRS Client (binding contract, protocol version 1).
 *
 * <p>Payloads are UTF-8 JSON as raw bytes (no length prefix), at most {@link #MAX_PAYLOAD} bytes.
 * The client sends {@code hello} after entering the play phase; the server answers with {@code rules}
 * (always, also when empty) and sends {@code rules} again after a world/server change and after a reload.
 * Every {@code rules} message replaces the previous rule set completely.</p>
 */
public final class Protocol {
	/** Protocol version written into {@code "v"}. */
	public static final int VERSION = 1;
	/** Maximum payload size in bytes (both directions). */
	public static final int MAX_PAYLOAD = 32 * 1024;
	/** Maximum UTF-8 length of an optional proxy backend identifier. */
	public static final int MAX_BACKEND_ID_BYTES = 256;
	/** Maximum UTF-16 length accepted by the client. */
	public static final int MAX_BACKEND_ID_LENGTH = 128;
	/** Value of {@code "client"} in a hello from the TRS Client. */
	public static final String CLIENT_ID = "trsclient";

	/** Server → client, Minecraft 1.13+ (namespaced channel). */
	public static final String RULES = "trsclient:rules";
	/** Server → client, Minecraft 1.12.2 and older (legacy channel name). */
	public static final String RULES_LEGACY = "TRS|Rules";
	/** Client → server, Minecraft 1.13+. */
	public static final String HELLO = "trsclient:hello";
	/** Client → server, Minecraft 1.12.2 and older. */
	public static final String HELLO_LEGACY = "TRS|Hello";

	/** Protocol number of Minecraft 1.13 (first version with namespaced plugin channels). */
	public static final int PROTOCOL_1_13 = 393;

	public static final Charset UTF8 = Charset.forName("UTF-8");

	private Protocol() {
	}

	static boolean validBackendId(String id) {
		if (id == null || id.length() == 0 || id.length() > MAX_BACKEND_ID_LENGTH) return false;
		boolean hasVisibleCharacter = false;
		for (int index = 0; index < id.length();) {
			int character = id.codePointAt(index);
			int type = Character.getType(character);
			if (Character.isISOControl(character) || type == Character.FORMAT || type == Character.SURROGATE) return false;
			if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) hasVisibleCharacter = true;
			index += Character.charCount(character);
		}
		return hasVisibleCharacter && id.getBytes(UTF8).length <= MAX_BACKEND_ID_BYTES;
	}

	public static boolean isHelloChannel(String channel) {
		return HELLO.equals(channel) || HELLO_LEGACY.equals(channel);
	}

	public static boolean isRulesChannel(String channel) {
		return RULES.equals(channel) || RULES_LEGACY.equals(channel);
	}

	/** Rules channel for a client protocol number (proxies know the client version, not its channel list). */
	public static String rulesChannelFor(int clientProtocol) {
		return clientProtocol >= PROTOCOL_1_13 || clientProtocol < 0 ? RULES : RULES_LEGACY;
	}
}
