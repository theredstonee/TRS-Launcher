package dev.theredstonee.trsclient.core.online;

/** Das Minecraft-Konto des laufenden Spiels (Zugangs-Token bleibt im Speicher, wird nie geloggt). */
public final class GameSession {
	/** 32 Hex-Ziffern ohne Bindestriche. */
	public final String uuid;
	public final String name;
	public final String accessToken;

	public GameSession(String uuid, String name, String accessToken) {
		this.uuid = Uuids.normalize(uuid);
		this.name = name;
		this.accessToken = accessToken;
	}

	/**
	 * Kann dieses Konto sich überhaupt anmelden? Offline-/Demo-Konten haben kein echtes Token
	 * ("0", leer) – dann gar nicht erst bei Mojang anklopfen.
	 */
	public boolean usable() {
		return uuid != null && name != null && name.matches("[A-Za-z0-9_]{1,16}")
				&& accessToken != null && accessToken.length() > 8;
	}

	@Override
	public String toString() {
		return "GameSession{" + name + "}";
	}
}
