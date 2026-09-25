package dev.theredstonee.trsclient.core.ui;

import java.util.UUID;

/**
 * Dynamische Texturen für die versionsunabhängige Oberfläche: {@code core} lädt eigene Pixel (ARGB)
 * hoch – Skins, Umhänge, Vorschaubilder – und zeichnet sie danach über {@link Canvas#image}.
 * Jede Loader-/Versionsvariante installiert beim ersten Zeichnen ihre {@link Store}-Umsetzung
 * ({@code ui/TextureStore}: NativeImage → DynamicTexture → TextureManager, Legacy: DynamicTexture(int[])).
 *
 * <p>Alle Aufrufe nur aus dem Render-/Spiel-Thread. Ohne installierten Store (Unit-Tests, Versionen ohne
 * Texturen im Menü) liefert {@link #store()} null – Aufrufer zeichnen dann ohne Textur.
 */
public final class Textures {
	/** Hochladen/Freigeben in der jeweiligen Version. */
	public interface Store {
		/**
		 * Lädt ein Bild hoch oder ersetzt die Pixel einer schon hochgeladenen Textur gleichen Namens
		 * (gleiche Größe: in place, sonst neu angelegt). {@code name}: nur {@code [a-z0-9_/.-]}.
		 *
		 * @return die Textur oder null, wenn es nicht ging (z. B. Grafikkontext fehlt)
		 */
		TextureRef upload(String name, int width, int height, int[] argb);

		/** Gibt eine mit {@link #upload} angelegte Textur frei (danach nicht mehr zeichnen). */
		void release(TextureRef texture);

		/**
		 * Textur aus den Spiel-Ressourcen, z. B. {@code "minecraft:textures/entity/player/wide/steve.png"};
		 * Minecraft lädt sie beim ersten Zeichnen selbst. Größe wie angegeben.
		 */
		TextureRef game(String location, int width, int height);

		/** Standard-Skin dieser Minecraft-Version für die UUID (Steve/Alex/…), nie null. */
		DefaultSkin defaultSkin(UUID uuid);
	}

	/** Standard-Skin: Textur (64×64) und Armform. */
	public static final class DefaultSkin {
		public final TextureRef texture;
		public final boolean slim;

		public DefaultSkin(TextureRef texture, boolean slim) {
			this.texture = texture;
			this.slim = slim;
		}
	}

	private static volatile Store store;

	private Textures() {
	}

	/** Installiert die Umsetzung der Version (idempotent, von {@code GfxCanvas} beim ersten Gebrauch). */
	public static void install(Store s) {
		if (store == null) store = s;
	}

	/** Umsetzung der Version oder null (keine Texturen möglich). */
	public static Store store() {
		return store;
	}

	/** Nur für Tests: Umsetzung ersetzen (null = keine). */
	public static void replaceForTests(Store s) {
		store = s;
	}

	/** Prüft einen Texturnamen ({@code [a-z0-9_/.-]}, 1..96 Zeichen, kein "..") – die Versionen verlangen das. */
	public static boolean validName(String name) {
		if (name == null || name.isEmpty() || name.length() > 96 || name.contains("..")) return false;
		for (int i = 0; i < name.length(); i++) {
			char ch = name.charAt(i);
			boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '/' || ch == '.' || ch == '-';
			if (!ok) return false;
		}
		return name.charAt(0) != '/' && name.charAt(name.length() - 1) != '/';
	}
}
