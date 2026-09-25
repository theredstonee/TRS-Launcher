package dev.theredstonee.trsclient.core.account;

/** Ein Konto in der Liste des Kontobildschirms (ohne Tokens). */
public final class GameAccount {
	public enum Source {
		/** Aus dem TRS Launcher (Spiel über den Launcher gestartet). */
		LAUNCHER,
		/** Im Spiel hinzugefügt und in dieser Instanz gespeichert. */
		LOCAL,
		/** Das Konto, mit dem das Spiel gestartet wurde (nur im Speicher). */
		STARTUP
	}

	/** 32 Hex-Zeichen ohne Bindestriche. */
	public final String uuid;
	public final String name;
	/** https://textures.minecraft.net/… oder null. */
	public final String skinUrl;
	public final Source source;
	/** Standardkonto des Launchers (nur {@link Source#LAUNCHER}). */
	public final boolean launcherDefault;
	/** Gespeichertes Konto ist hier nicht entschlüsselbar → neu anmelden. */
	public final boolean locked;

	public GameAccount(String uuid, String name, String skinUrl, Source source, boolean launcherDefault, boolean locked) {
		this.uuid = uuid;
		this.name = name;
		this.skinUrl = skinUrl;
		this.source = source;
		this.launcherDefault = launcherDefault;
		this.locked = locked;
	}

	/** Kann aus der Liste entfernt werden (nur im Spiel hinzugefügte). */
	public boolean removable() {
		return source == Source.LOCAL;
	}

	@Override
	public String toString() {
		return "GameAccount{" + name + ", " + source + "}";
	}
}
