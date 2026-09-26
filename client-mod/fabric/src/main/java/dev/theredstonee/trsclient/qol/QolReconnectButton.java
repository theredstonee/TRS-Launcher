package dev.theredstonee.trsclient.qol;

/** Vom Getrennt-Bildschirm umgesetzt ({@code DisconnectScreenMixin}): Auto-Reconnect-Knopf beschriften. */
public interface QolReconnectButton {
	/**
	 * @param label  Beschriftung oder null = Knopf ausblenden
	 * @param active anklickbar (nur während des Countdowns)
	 */
	void trsclient$update(String label, boolean active);
}
