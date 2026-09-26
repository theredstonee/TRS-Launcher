package dev.theredstonee.trsclient.core.hosting.share;

import dev.theredstonee.trsclient.core.hosting.Hosting;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Brücke der Pack-Mixins (≥ 1.20.3) zum Welt-Hosting:
 * <ul>
 * <li><b>Host:</b> Während der Konfiguration eines Gasts ({@link #enter}/{@link #exit} um
 * {@code ServerConfigurationPacketListenerImpl#startConfiguration}) liefert {@code MinecraftServer#getServerResourcePack}
 * das geteilte Pack – nur für angenommene TRS-Gäste, nie für den Host selbst oder Gäste über den öffentlichen Link.
 * Die Adresse ist ein Platzhalter ({@link PackServer#marker}), nie ein Pfad oder eine IP.</li>
 * <li><b>Gast:</b> {@code parseResourcePackUrl} schreibt den Platzhalter auf den lokalen Endpunkt
 * ({@link PackServer}) um. Minecraft stellt dann die normale Frage „Server-Resource-Pack verwenden?“.</li>
 * </ul>
 */
public final class HostingPack {
	private static final ThreadLocal<String> CONFIGURING = new ThreadLocal<String>();
	/** Sind die Pack-Mixins aktiv (ab 1.20.3, Fabric/NeoForge/Forge)? Setzt der Server-Mixin beim Anlegen des Servers. */
	private static volatile boolean supported;

	private HostingPack() {
	}

	/** Aus dem Server-Mixin: dieser Baum kann Gästen ein Pack anbieten. */
	public static void markSupported() {
		supported = true;
	}

	public static boolean supported() {
		return supported;
	}

	/** Host: Konfiguration eines Spielers beginnt (Name aus dem Profil). */
	public static void enter(String playerName) {
		CONFIGURING.set(playerName);
	}

	public static void exit() {
		CONFIGURING.remove();
	}

	/**
	 * Host: Pack für den gerade konfigurierten Spieler oder null (Vanilla-Verhalten). Rückgabe {id, url, sha1}; die
	 * Mixins bauen daraus {@code ServerResourcePackInfo(id, url, sha1, false, null)}.
	 */
	public static Object[] offer() {
		String name = CONFIGURING.get();
		Hosting h = Hosting.current();
		if (name == null || h == null) return null;
		String sha1;
		try {
			sha1 = h.packOfferFor(name);
		} catch (RuntimeException e) {
			return null;
		}
		if (sha1 == null) return null;
		return new Object[] { UUID.nameUUIDFromBytes(("trs-pack:" + sha1).getBytes(StandardCharsets.US_ASCII)),
				PackServer.marker(sha1), sha1 };
	}

	/** Gast: Platzhalter → lokale Adresse; null = nicht unseres (Vanilla prüft dann selbst). */
	public static String rewrite(String url) {
		String sha1 = PackServer.markerSha1(url);
		Hosting h = Hosting.current();
		if (sha1 == null || h == null) return null;
		try {
			return h.packUrlFor(sha1);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
