package dev.theredstonee.trsclient.core.net;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * AES/CFB8-Entschlüsselung, wie Minecraft sie für die Verbindung nutzt – nur schneller als die JDK-Variante.
 *
 * <p>Idee (eigene Umsetzung): Bei CFB8 hängt der Schlüsselstrom für Byte {@code i} nur von den 16 Geheimtext-Bytes davor
 * ab. Beim <b>Entschlüsseln</b> sind die alle schon bekannt – also lassen sich viele Positionen auf einmal rechnen:
 * je Position ein 16-Byte-Fenster in einen Puffer legen und den ganzen Puffer mit einem einzigen AES/ECB-Aufruf
 * verschlüsseln (AES-NI arbeitet dann ohne Aufruf-Overhead je Byte). Das JDK-CFB8 ruft dagegen je Byte einzeln die
 * Block-Verschlüsselung auf und verschiebt dazwischen sein Register. Das Ergebnis ist Bit für Bit gleich (Tests
 * vergleichen mit dem JDK). Verschlüsseln geht so nicht (jedes Byte braucht das vorige Ergebnis) – dort bleibt Vanilla.
 *
 * <p>Nur JDK-Kryptografie ({@code AES/ECB/NoPadding}), keine nativen Bibliotheken. Nicht threadsicher (ein Objekt je
 * Verbindung, benutzt nur vom Netty-Thread).
 */
public final class FastCfb8 {
	/** Positionen je ECB-Aufruf (Fenster-Puffer = 16 × CHUNK Bytes). */
	static final int CHUNK = 2048;

	private final Cipher ecb;
	/** Die letzten 16 Geheimtext-Bytes (anfangs der IV). */
	private final byte[] register = new byte[16];
	/** Register + Geheimtext des aktuellen Abschnitts, zusammenhängend. */
	private final byte[] history = new byte[16 + CHUNK];
	private final byte[] windows = new byte[16 * CHUNK];
	private final byte[] keystream = new byte[16 * CHUNK];

	public FastCfb8(byte[] key, byte[] iv) throws GeneralSecurityException {
		if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
			throw new GeneralSecurityException("AES key length");
		}
		if (iv == null || iv.length != 16) throw new GeneralSecurityException("IV length");
		ecb = Cipher.getInstance("AES/ECB/NoPadding");
		ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
		System.arraycopy(iv, 0, register, 0, 16);
	}

	/** Liefert die Arbeitsfläche für bis zu {@link #CHUNK} Geheimtext-Bytes (ab Index 16 beschreiben). */
	byte[] history() {
		return history;
	}

	/**
	 * Entschlüsselt {@code len} Bytes (höchstens {@link #CHUNK}), die vorher ab {@code history()[16]} abgelegt wurden,
	 * nach {@code out[outOff…]}. Das Register wandert mit.
	 */
	void decryptChunk(int len, byte[] out, int outOff) throws GeneralSecurityException {
		if (len <= 0) return;
		byte[] h = history;
		System.arraycopy(register, 0, h, 0, 16);
		byte[] w = windows;
		for (int i = 0, d = 0; i < len; i++, d += 16) System.arraycopy(h, i, w, d, 16);
		int n = ecb.update(w, 0, len * 16, keystream, 0);
		if (n != len * 16) throw new GeneralSecurityException("ECB returned " + n);
		byte[] k = keystream;
		for (int i = 0, d = 0; i < len; i++, d += 16) out[outOff + i] = (byte) (h[16 + i] ^ k[d]);
		// Neues Register = die letzten 16 Bytes von Register+Geheimtext.
		System.arraycopy(h, len, register, 0, 16);
	}

	/** Entschlüsselt {@code in[off…off+len)} nach {@code out[outOff…]} (dürfen dasselbe Feld sein). */
	public void decrypt(byte[] in, int off, int len, byte[] out, int outOff) throws GeneralSecurityException {
		int done = 0;
		while (done < len) {
			int n = Math.min(CHUNK, len - done);
			System.arraycopy(in, off + done, history, 16, n);
			decryptChunk(n, out, outOff + done);
			done += n;
		}
	}
}
