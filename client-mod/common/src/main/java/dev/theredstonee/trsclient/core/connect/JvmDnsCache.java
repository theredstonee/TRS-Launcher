package dev.theredstonee.trsclient.core.connect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nur Java 8 (Minecraft ohne Haken am Verbindungsaufbau, z. B. Forge 1.7.10–1.12.2): legt die Adressen eines Hosts –
 * schnellste zuerst – in Javas eigenen Adress-Zwischenspeicher. Vanillas {@code InetAddress.getByName(host)} im
 * Verbindungs-Thread bekommt dann sofort diese Adresse, ohne DNS und ohne erst eine tote Adresse zu versuchen. Der
 * Eintrag läuft wie Javas eigene nach {@link #TTL_SECONDS} ab.
 *
 * <p>Zwei Bauarten von Java 8: ältere Updates (z. B. Mojangs 8u51) haben {@code InetAddress.cacheAddresses}, neuere
 * (wie Java 9+) eine {@code ConcurrentMap cache} mit {@code CachedAddresses} und {@code expirySet}. Ab Java 9 ist der
 * Zugriff nicht erlaubt (ab 16 gesperrt) – dann passiert nichts.
 */
public final class JvmDnsCache {
	/** Wie Javas Standard ({@code networkaddress.cache.ttl}). */
	static final long TTL_SECONDS = 30;

	private static volatile Method cacheAddresses;
	private static volatile Map<Object, Object> cacheMap;
	private static volatile Set<Object> expirySet;
	private static volatile Constructor<?> cachedAddresses;
	private static volatile boolean unavailable;
	private static volatile boolean resolved;

	private JvmDnsCache() {
	}

	/** Läuft Java 8 (1.x)? */
	public static boolean supported() {
		String v = System.getProperty("java.specification.version", "");
		return v.startsWith("1.");
	}

	/**
	 * Adressen für {@code host} hinterlegen. {@code addresses} müssen den Namen {@code host} tragen
	 * ({@link DnsCache#inetAddresses}). true = hinterlegt.
	 */
	public static boolean prime(String host, List<InetAddress> addresses) {
		if (unavailable || host == null || addresses == null || addresses.isEmpty() || IpLiteral.is(host)) return false;
		if (!supported()) {
			unavailable = true;
			return false;
		}
		try {
			if (!resolved) resolve();
			InetAddress[] arr = addresses.toArray(new InetAddress[0]);
			if (cacheAddresses != null) {
				cacheAddresses.invoke(null, host, arr, Boolean.TRUE);
				return true;
			}
			if (cacheMap != null && cachedAddresses != null) {
				Object entry = cachedAddresses.newInstance(host, arr, System.nanoTime() + TTL_SECONDS * 1_000_000_000L);
				Object old = cacheMap.put(host, entry);
				if (old != null && expirySet != null) expirySet.remove(old);
				// Ohne Eintrag in expirySet liefe der Eintrag nie ab.
				if (expirySet != null) expirySet.add(entry);
				else cacheMap.remove(host, entry);
				return expirySet != null;
			}
			unavailable = true;
			return false;
		} catch (Exception | LinkageError e) {
			unavailable = true;
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private static synchronized void resolve() throws ReflectiveOperationException {
		if (resolved) return;
		try {
			Method m = InetAddress.class.getDeclaredMethod("cacheAddresses", String.class, InetAddress[].class, boolean.class);
			m.setAccessible(true);
			cacheAddresses = m;
		} catch (NoSuchMethodException e) {
			Field cache = InetAddress.class.getDeclaredField("cache");
			Field expiry = InetAddress.class.getDeclaredField("expirySet");
			cache.setAccessible(true);
			expiry.setAccessible(true);
			Class<?> ca = Class.forName("java.net.InetAddress$CachedAddresses");
			Constructor<?> c = ca.getDeclaredConstructor(String.class, InetAddress[].class, long.class);
			c.setAccessible(true);
			cacheMap = (Map<Object, Object>) cache.get(null);
			expirySet = (Set<Object>) expiry.get(null);
			cachedAddresses = c;
		}
		resolved = true;
	}
}
