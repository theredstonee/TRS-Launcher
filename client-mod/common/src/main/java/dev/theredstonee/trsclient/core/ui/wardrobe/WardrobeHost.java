package dev.theredstonee.trsclient.core.ui.wardrobe;

import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.core.skin.PlayerLook;

import java.nio.file.Path;

/**
 * Was die Garderobe ({@link WardrobeUi}) von Minecraft braucht – je Version eine kleine Umsetzung in
 * {@code screen/WardrobeScreen}. Alles Weitere (Konto, TRS API, Mojang) holt sich die Garderobe selbst.
 */
public interface WardrobeHost {
	void playClick();

	/** Zurück zum vorherigen Bildschirm. */
	void closeScreen();

	/** {@code config}-Ordner der Instanz. */
	Path configDir();

	String userAgent();

	/** TRS-Online-Funktionen der Version (Token, eigener Skin, TRS-Umhang) oder null (1.7.10/1.13.2). */
	OnlineFeatures<?> features();

	/** Eigenes Aussehen (Render-Thread) oder null. */
	default PlayerLook look() {
		OnlineFeatures<?> f = features();
		return f == null ? null : f.look();
	}
}
