package dev.theredstonee.trsclient.core.social;

/**
 * Was die Sozial-Benachrichtigungen (Toasts) vom Spiel wissen müssen – je Loader/Version eine kleine Umsetzung,
 * installiert über {@link SocialOverlay#install}. Alle Aufrufe kommen aus dem Render-/Spiel-Thread.
 */
public interface SocialPlatform {
	/**
	 * Leiser UI-Klick für einen neuen Toast (Vanilla-Knopfklick über die Master-Lautstärke – respektiert also die
	 * Lautstärke des Spielers; Autotests sind stumm).
	 */
	void playToastSound();

	/** Läuft das Spiel gerade im Vollbild? (für „Nicht stören im Vollbild“) */
	boolean fullscreen();

	/** Anzeigename der Taste „Schnellantwort“ (z. B. "Y") oder null = unbelegt. */
	String quickReplyKey();

	/**
	 * Unterkante (GUI-Pixel, ab oben) der gerade sichtbaren Vanilla-Toasts oben rechts – einschließlich solcher, die
	 * noch herein- oder hinausfahren –, 0 = keine. Je Bild gefragt, solange TRS-Toasts oben rechts stehen: billig
	 * halten (siehe {@link VanillaToastProbe}).
	 */
	default int vanillaToastBottom() {
		return 0;
	}
}
