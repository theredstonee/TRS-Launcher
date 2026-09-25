package dev.theredstonee.trsclient.core.intro;

import dev.theredstonee.trsclient.core.module.KeySetting;

import java.util.ArrayList;
import java.util.List;

/**
 * Eine Minecraft-Tastenbelegung (Vanilla, TRS oder andere Mods) für den Einführungsschritt „Tastenbelegung“:
 * Kennung ({@code key.trsclient.zoom}, {@code key.hotbar.1} …), Anzeigename, aktuelle und Standard-Taste als
 * Tastenname ({@code key.keyboard.v}) und – für TRS-Tasten – die Verbindung zum Ändern.
 */
public final class KeyBind {
	public final String id;
	public final String label;
	public final String defaultKey;
	private final KeySetting.Link link;

	public KeyBind(String id, String label, String defaultKey, KeySetting.Link link) {
		this.id = id;
		this.label = label;
		this.defaultKey = defaultKey == null ? KeySetting.NONE : defaultKey;
		this.link = link;
	}

	/** Aktuelle Taste ({@link KeySetting#NONE} = unbelegt). */
	public String key() {
		try {
			String k = link == null ? null : link.get();
			return k == null ? KeySetting.NONE : k;
		} catch (RuntimeException e) {
			return KeySetting.NONE;
		}
	}

	public void set(String keyName) {
		if (link != null) link.set(keyName == null ? KeySetting.NONE : keyName);
	}

	public boolean trs() {
		return id.startsWith("key.trsclient.");
	}

	public boolean bound() {
		return !KeySetting.NONE.equals(key());
	}

	/** Andere Belegungen auf derselben Taste (Konflikte). */
	public List<KeyBind> conflicts(List<KeyBind> all) {
		List<KeyBind> out = new ArrayList<KeyBind>();
		if (!bound()) return out;
		String k = key();
		for (KeyBind b : all) {
			// F3-Kombinationen (ab 1.21.9 eigene Belegungen „key.debug.*“) gelten nur zusammen mit F3 – kein Konflikt.
			if (b.id.startsWith("key.debug.")) continue;
			if (b != this && !b.id.equals(id) && k.equals(b.key())) out.add(b);
		}
		return out;
	}
}
