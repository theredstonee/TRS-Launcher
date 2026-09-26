package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.ui.friends.FriendsHost;

import java.nio.file.Path;

/**
 * Was der Sozial-Bildschirm ({@link SocialUi}) und die Schnellantwort ({@link QuickReplyUi}) von Minecraft brauchen.
 * Je Loader/Version eine kleine Umsetzung in {@code screen/MenuScreens}.
 */
public interface SocialHost extends FriendsHost {
	/** Spielordner der Instanz ({@code screenshots/} liegt darin); null = unbekannt. */
	Path gameDir();

	/** Adresse des aktuellen Servers ("host[:port]") oder null (Einzelspieler, Menü). */
	String currentServer();

	/** Ist man gerade in einer Welt (Einzelspieler oder Server)? Dann fragt „Beitreten“ vorher nach. */
	boolean inWorld();

	/**
	 * Mit einem Server verbinden (Einladung „Beitreten“). Ist man schon in einer Welt, wird sie vorher verlassen
	 * (Einzelspieler: speichern). Die Rückfrage stellt die Oberfläche selbst.
	 */
	void joinServer(String address, String label);

	/** Text in die Zwischenablage (Kopieren). */
	void copy(String text);

	/** Text aus der Zwischenablage (Einfügen) oder null. */
	String paste();
}
