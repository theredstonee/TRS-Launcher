package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.online.FriendsView;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.Social;

/** Was die Teilflächen des Sozial-Bildschirms (Liste, Unterhaltung) vom Bildschirm brauchen. */
interface SocialContext {
	Social social();

	TrsOnline online();

	SocialHost host();

	FaceCache faces();

	Kit kit();

	/** Dialog öffnen. */
	void dialog(Dialog d);

	/** Kontextmenü öffnen. */
	void popup(PopupMenu m);

	/** Unterhaltung öffnen (null = keine). */
	void open(String conversationId);

	/** Freunde (null = noch nicht geladen). */
	FriendsView friends();

	/** Menü einer Unterhaltung (Liste: Rechtsklick; Kopfzeile: ≡). */
	void conversationMenu(Chat.Conversation c, int x, int y);

	/** Reiter „Welten“ zeigen (Stand eines Welt-Beitritts). */
	void showWorlds();

	/** Bildschirmgröße (für Menüs/Dialoge). */
	int screenWidth();

	int screenHeight();
}
