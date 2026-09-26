package dev.theredstonee.trsclient.core.skin;

import dev.theredstonee.trsclient.core.ui.TextureRef;

/**
 * Aussehen des eigenen Spielers für Menüs (Startbildschirm, Garderobe …): Name, Skin, Armform, Umhang.
 * Wird von {@link LocalSkin} je Bild aktualisiert (dasselbe Objekt, nur Render-Thread).
 */
public final class PlayerLook {
	/** Spielername der Sitzung (oder "Player"). */
	public String name = "Player";
	/** Skin-Textur (64×64) – eigener Skin, sonst Standard-Skin der Version; null = keine Texturen möglich. */
	public TextureRef skin;
	public boolean slim;
	/** Umhang (TRS-Umhang hat Vorrang vor dem Mojang-Umhang) oder null. */
	public TextureRef cape;
	/** Ist das der TRS-Umhang? */
	public boolean trsCape;
	/** Eigener Skin wird noch geladen (bis dahin Standard-Skin). */
	public boolean loading;
	/** Ist das der echte Skin des Kontos (nicht der Standard-Skin)? */
	public boolean ownSkin;
	/**
	 * Pixel (64×64 ARGB, wie Minecraft sie aufbereitet) des gezeigten Skins – eigener oder Standard-Skin – oder null,
	 * solange sie unbekannt sind (z. B. Standard-Skin nicht aus den Ressourcen lesbar). Nicht verändern.
	 */
	public int[] pixels;

	/** Überträgt alles auf ein {@link SkinModelSpec} (Pose bleibt). */
	public SkinModelSpec applyTo(SkinModelSpec spec) {
		spec.skin = skin;
		spec.slim = slim;
		spec.cape = cape;
		return spec;
	}
}
