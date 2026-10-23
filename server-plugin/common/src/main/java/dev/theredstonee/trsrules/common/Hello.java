package dev.theredstonee.trsrules.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A {@code hello} from the TRS Client:
 * {@code {"v":1,"client":"trsclient","version":"0.20.0","mc":"1.21.11","loader":"fabric","modules":["fps",…]}}.
 */
public final class Hello {
	private static final int MAX_MODULES = 512;
	private static final int MAX_TEXT = 64;

	private final int protocolVersion;
	private final String clientVersion;
	private final String minecraft;
	private final String loader;
	private final List<String> modules;

	private Hello(int protocolVersion, String clientVersion, String minecraft, String loader, List<String> modules) {
		this.protocolVersion = protocolVersion;
		this.clientVersion = clientVersion;
		this.minecraft = minecraft;
		this.loader = loader;
		this.modules = modules;
	}

	/**
	 * Parses a hello payload. Returns {@code null} if it is not a valid TRS Client hello (too large, no JSON object,
	 * {@code client} is not {@value Protocol#CLIENT_ID}). Optional fields may be missing; newer protocol versions are
	 * accepted (only known fields are read).
	 */
	public static Hello parse(byte[] payload) {
		if (payload == null || payload.length == 0 || payload.length > Protocol.MAX_PAYLOAD) return null;
		Map<String, Object> root;
		try {
			root = Json.parseObject(new String(payload, Protocol.UTF8));
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (!Protocol.CLIENT_ID.equals(root.get("client"))) return null;
		Object v = root.get("v");
		int version = v instanceof Number ? ((Number) v).intValue() : 1;
		if (version < 1) return null;
		List<String> mods = new ArrayList<String>();
		Object m = root.get("modules");
		if (m instanceof List) {
			for (Object o : (List<?>) m) {
				if (o instanceof String && mods.size() < MAX_MODULES) mods.add(clip((String) o));
			}
		}
		return new Hello(version, text(root.get("version")), text(root.get("mc")), text(root.get("loader")),
				Collections.unmodifiableList(mods));
	}

	private static String text(Object o) {
		return o instanceof String ? clip((String) o) : null;
	}

	private static String clip(String s) {
		return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
	}

	public int protocolVersion() {
		return protocolVersion;
	}

	/** TRS Client version, e.g. "0.20.0" (may be {@code null}). */
	public String clientVersion() {
		return clientVersion;
	}

	/** Minecraft version of the client (may be {@code null}). */
	public String minecraft() {
		return minecraft;
	}

	/** Mod loader of the client, e.g. "fabric" (may be {@code null}). */
	public String loader() {
		return loader;
	}

	/** Module ids the client knows. */
	public List<String> modules() {
		return modules;
	}

	/** "0.20.0, MC 1.21.11, fabric" */
	public String describe() {
		StringBuilder sb = new StringBuilder();
		sb.append(clientVersion == null ? "unknown version" : clientVersion);
		if (minecraft != null) sb.append(", MC ").append(minecraft);
		if (loader != null) sb.append(", ").append(loader);
		sb.append(", ").append(modules.size()).append(" modules");
		return sb.toString();
	}
}
