package dev.theredstonee.trsclient.core.hosting.net;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Schlüssel einer direkten Verbindung: beide Seiten legen je 16 Zufallsbytes in Angebot/Antwort (Signalisierung über
 * die TRS API – nur Host und Gast sehen sie). Daraus: Sitzungsschlüssel (HMAC-SHA256) und Verbindungs-ID. Jedes
 * Datagramm trägt eine auf {@link #TAG} Bytes gekürzte HMAC – fremde oder gefälschte Pakete werden verworfen.
 */
public final class P2pKeys {
	public static final int NONCE = 16;
	public static final int TAG = 12;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final byte[] key;
	private final int connId;

	public P2pKeys(byte[] guestNonce, byte[] hostNonce, String sid) {
		if (guestNonce == null || hostNonce == null || guestNonce.length != NONCE || hostNonce.length != NONCE) {
			throw new IllegalArgumentException("nonce");
		}
		byte[] ikm = new byte[NONCE * 2];
		System.arraycopy(guestNonce, 0, ikm, 0, NONCE);
		System.arraycopy(hostNonce, 0, ikm, NONCE, NONCE);
		this.key = hmac(ikm, ("TRS-P2P-1|" + (sid == null ? "" : sid)).getBytes(StandardCharsets.UTF_8));
		byte[] id = hmac(key, "id".getBytes(StandardCharsets.UTF_8));
		this.connId = Stun.getInt(id, 0);
	}

	public static byte[] nonce() {
		byte[] b = new byte[NONCE];
		RANDOM.nextBytes(b);
		return b;
	}

	public int connId() {
		return connId;
	}

	/** Neuer MAC (nicht threadsicher – je Thread einen). */
	public Mac mac() {
		try {
			Mac m = Mac.getInstance("HmacSHA256");
			m.init(new SecretKeySpec(key, "HmacSHA256"));
			return m;
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Prüfsumme über {@code b[0..len)} an {@code b[len..len+TAG)} schreiben. */
	public static void seal(Mac mac, byte[] b, int len) {
		mac.reset();
		mac.update(b, 0, len);
		byte[] full = mac.doFinal();
		System.arraycopy(full, 0, b, len, TAG);
	}

	/** Stimmt die Prüfsumme am Ende von {@code b[off..off+len)}? (Zeitkonstant.) */
	public static boolean verify(Mac mac, byte[] b, int off, int len) {
		if (len < TAG) return false;
		mac.reset();
		mac.update(b, off, len - TAG);
		byte[] full = mac.doFinal();
		return MessageDigest.isEqual(Arrays.copyOf(full, TAG), Arrays.copyOfRange(b, off + len - TAG, off + len));
	}

	static byte[] hmac(byte[] key, byte[] data) {
		try {
			Mac m = Mac.getInstance("HmacSHA256");
			m.init(new SecretKeySpec(key, "HmacSHA256"));
			return m.doFinal(data);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
