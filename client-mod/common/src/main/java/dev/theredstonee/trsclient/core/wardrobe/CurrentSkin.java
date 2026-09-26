package dev.theredstonee.trsclient.core.wardrobe;

import dev.theredstonee.trsclient.core.skin.SkinImage;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * Der Skin, den das Konto gerade trägt – für die eigene Karte „Aktueller Skin“ ganz vorn in der Bibliothek der
 * Garderobe (wie im Launcher). Reine Logik ohne Minecraft (getestet).
 *
 * <p>Quellen in dieser Reihenfolge: (1) das Mojang-Profil, das die Garderobe selbst lädt und nach „Anwenden“ sofort
 * nachführt ({@link WardrobeService.State#activePixels}) – nur, wenn es zum angemeldeten Konto gehört; (2) der
 * Menü-Skin ({@code LocalSkin}: Platten-Cache/sessionserver bzw. der Standard-Skin der Version bei Offline-/Dev-Konten).
 *
 * <p><b>Entscheidung „getragen“ vs. Bibliothek:</b> Die Lampe „getragen“ leuchtet nur auf der Karte „Aktueller Skin“ –
 * so gibt es immer genau eine getragene Karte. Ein Bibliotheks-Skin mit genau denselben Pixeln (und derselben
 * Armform) bekommt statt einer zweiten Lampe den kleinen Hinweis „= aktuell“; „In Bibliothek speichern“ ist dann
 * gesperrt (keine Dopplung). Auswahl (Leuchtrahmen) ist davon unabhängig: sie zeigt nur, was rechts in der
 * Vorschau steht.
 */
public final class CurrentSkin {
	/** Auswahl-Kennung der Karte (kein gültiger Bibliotheks-Schlüssel, siehe {@link SkinFiles#validId}). */
	public static final String ID = "current";

	/** Woher der Skin stammt. */
	public enum Source {
		/** Mojang-Profil (Garderobe). */
		PROFILE,
		/** Eigener Skin aus dem Menü-Skin (Cache/sessionserver). */
		LOOK,
		/** Standard-Skin der Version (Steve, Alex …). */
		DEFAULT
	}

	/** Pixel (64×64) oder null, wenn nur die Textur bekannt ist (dann weder speichern noch bearbeiten). */
	public final int[] pixels;
	public final boolean slim;
	/** Vergleichswert ({@link #lookKey}) oder null ohne Pixel. */
	public final String look;
	public final Source source;
	/** Erster Bibliotheks-Skin mit genau diesem Aussehen oder null. */
	public final String twin;

	CurrentSkin(int[] pixels, boolean slim, Source source, String look, String twin) {
		this.pixels = pixels;
		this.slim = slim;
		this.source = source;
		this.look = look;
		this.twin = twin;
	}

	/**
	 * Bestimmt den aktuellen Skin.
	 *
	 * @param s          Stand der Garderobe
	 * @param fresh      gehört {@code s} zum gerade angemeldeten Konto (nach einem Kontowechsel erst nach dem Laden)?
	 * @param lookPixels Pixel des Menü-Skins oder null
	 * @param lookKey    {@link #lookKey} zu {@code lookPixels}/{@code lookSlim} (vom Aufrufer zwischengespeichert) oder
	 *                   null = hier berechnen
	 * @param lookOwn    ist der Menü-Skin der echte Skin des Kontos (nicht der Standard-Skin)?
	 */
	public static CurrentSkin resolve(WardrobeService.State s, boolean fresh, int[] lookPixels, String lookKey,
			boolean lookSlim, boolean lookOwn) {
		int[] px;
		boolean slim;
		Source src;
		String key;
		if (fresh && s.activePixels != null) {
			px = s.activePixels;
			slim = s.activeSlim;
			src = Source.PROFILE;
			key = s.activeLook != null ? s.activeLook : lookKey(px, slim);
		} else {
			px = lookPixels;
			slim = lookSlim;
			src = lookOwn ? Source.LOOK : Source.DEFAULT;
			key = px == null ? null : (lookKey != null ? lookKey : lookKey(px, slim));
		}
		return new CurrentSkin(px, slim, src, key, twin(s.skins, key));
	}

	/** Erster Skin der Liste mit genau diesem Aussehen oder null. */
	public static String twin(List<WardrobeService.Skin> skins, String look) {
		if (look == null || skins == null) return null;
		for (WardrobeService.Skin sk : skins) if (look.equals(sk.look)) return sk.id;
		return null;
	}

	/** Sieht dieser Bibliotheks-Skin genau aus wie der getragene (Hinweis „= aktuell“, „Anwenden“ unnötig)? */
	public boolean sameAs(WardrobeService.Skin sk) {
		return look != null && sk != null && look.equals(sk.look);
	}

	/** Lässt sich in die Bibliothek übernehmen (Pixel bekannt, noch nicht drin)? */
	public boolean saveable() {
		return pixels != null && twin == null;
	}

	/**
	 * Vergleichswert zweier Skins: SHA-256 der Pixel, so wie Minecraft sie zeigt (Grundebene deckend, 64×32 umgebaut),
	 * plus Armform – gleiche Pixel mit anderer Armform sind ein anderer Skin. null bei fehlenden/falschen Pixeln.
	 */
	public static String lookKey(int[] pixels, boolean slim) {
		if (pixels == null || pixels.length < 64 * 64) return null;
		int[] n = SkinImage.normalize(64, 64, pixels);
		ByteBuffer buf = ByteBuffer.allocate(n.length * 4 + 1);
		for (int p : n) buf.putInt(p);
		buf.put((byte) (slim ? 1 : 0));
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(buf.array());
			StringBuilder sb = new StringBuilder(64);
			for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
