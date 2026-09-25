package dev.theredstonee.trsclient.core.account;

import dev.theredstonee.trsclient.core.online.Uuids;

/** Eine Spielsitzung zum Einsetzen – das Zugangs-Token lebt nur im Speicher und wird nie ausgegeben. */
public final class SessionData {
	/** 32 Hex-Zeichen ohne Bindestriche. */
	public final String uuid;
	public final String name;
	public final String accessToken;
	/** Xbox-Benutzer-ID oder null. */
	public final String xuid;

	public SessionData(String uuid, String name, String accessToken, String xuid) {
		this.uuid = Uuids.normalize(uuid);
		this.name = name;
		this.accessToken = accessToken;
		this.xuid = xuid;
	}

	/** Mit Bindestrichen (für {@code UUID.fromString}). */
	public String dashedUuid() {
		String u = uuid;
		if (u == null || u.length() != 32) return u;
		return u.substring(0, 8) + "-" + u.substring(8, 12) + "-" + u.substring(12, 16) + "-" + u.substring(16, 20) + "-"
				+ u.substring(20);
	}

	public boolean valid() {
		return uuid != null && uuid.matches("[0-9a-f]{32}") && name != null && name.matches("[A-Za-z0-9_]{1,16}")
				&& accessToken != null && !accessToken.isEmpty() && accessToken.length() < 16384;
	}

	@Override
	public String toString() {
		return "SessionData{" + name + "}";
	}
}
