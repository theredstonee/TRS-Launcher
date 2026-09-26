package dev.theredstonee.trsclient.core.hosting.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.zip.CRC32;

/**
 * STUN Binding (RFC 5389) – nur das, was die NAT-Durchdringung braucht: eine Binding-Anfrage bauen und die Antwort
 * des TRS Relay (oder eines beliebigen STUN-Servers) zerlegen. Nur IPv4, keine Authentifizierung.
 */
public final class Stun {
	public static final int MAGIC_COOKIE = 0x2112A442;
	public static final int BINDING_REQUEST = 0x0001;
	public static final int BINDING_SUCCESS = 0x0101;
	static final int ATTR_MAPPED_ADDRESS = 0x0001;
	static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
	static final int ATTR_FINGERPRINT = 0x8028;
	static final int FINGERPRINT_XOR = 0x5354554E;
	/** Größte Antwort, die wir lesen. */
	public static final int MAX_RESPONSE = 548;

	private Stun() {
	}

	/** Binding-Anfrage (20 Bytes) mit der Transaktions-ID {@code txid} (12 Bytes). */
	public static byte[] bindingRequest(byte[] txid) {
		if (txid == null || txid.length != 12) throw new IllegalArgumentException("txid");
		byte[] b = new byte[20];
		putShort(b, 0, BINDING_REQUEST);
		putShort(b, 2, 0);
		putInt(b, 4, MAGIC_COOKIE);
		System.arraycopy(txid, 0, b, 8, 12);
		return b;
	}

	/** Sieht das Datagramm wie STUN aus (erste zwei Bits 0 + Magic Cookie)? */
	public static boolean isStun(byte[] b, int off, int len) {
		return len >= 20 && (b[off] & 0xC0) == 0 && getInt(b, off + 4) == MAGIC_COOKIE;
	}

	/**
	 * Öffentliche Adresse aus einer Binding-Erfolgsantwort zu {@code txid}: XOR-MAPPED-ADDRESS, sonst MAPPED-ADDRESS.
	 * Falsche Transaktion, kaputte Länge, falscher FINGERPRINT oder keine IPv4-Adresse → null.
	 */
	public static InetSocketAddress parseBindingResponse(byte[] b, int off, int len, byte[] txid) {
		if (!isStun(b, off, len) || len > MAX_RESPONSE) return null;
		if (getShort(b, off) != BINDING_SUCCESS) return null;
		if (getShort(b, off + 2) != len - 20) return null;
		if (txid != null) {
			for (int i = 0; i < 12; i++) if (b[off + 8 + i] != txid[i]) return null;
		}
		InetSocketAddress xor = null;
		InetSocketAddress plain = null;
		int o = 20;
		while (o + 4 <= len) {
			int type = getShort(b, off + o);
			int alen = getShort(b, off + o + 2);
			int v = off + o + 4;
			if (o + 4 + alen > len) return null;
			if ((type == ATTR_XOR_MAPPED_ADDRESS || type == ATTR_MAPPED_ADDRESS) && alen == 8 && b[v + 1] == 0x01) {
				int port = getShort(b, v + 2);
				int ip = getInt(b, v + 4);
				if (type == ATTR_XOR_MAPPED_ADDRESS) {
					port ^= MAGIC_COOKIE >>> 16;
					ip ^= MAGIC_COOKIE;
				}
				InetSocketAddress a = address(ip, port);
				if (type == ATTR_XOR_MAPPED_ADDRESS) xor = a;
				else plain = a;
			} else if (type == ATTR_FINGERPRINT && alen == 4) {
				CRC32 crc = new CRC32();
				crc.update(b, off, o);
				int expect = (int) crc.getValue() ^ FINGERPRINT_XOR;
				if (getInt(b, v) != expect) return null;
			}
			o += 4 + alen + ((4 - (alen % 4)) % 4);
		}
		return xor != null ? xor : plain;
	}

	private static InetSocketAddress address(int ip, int port) {
		byte[] a = { (byte) (ip >>> 24), (byte) (ip >>> 16), (byte) (ip >>> 8), (byte) ip };
		try {
			return new InetSocketAddress(InetAddress.getByAddress(a), port & 0xFFFF);
		} catch (UnknownHostException e) {
			return null;
		}
	}

	/** Für Tests: Antwort wie das TRS Relay (XOR-MAPPED + MAPPED + FINGERPRINT). */
	static byte[] bindingResponse(byte[] txid, InetSocketAddress mapped) {
		byte[] ip = mapped.getAddress().getAddress();
		int ipInt = getInt(ip, 0);
		int port = mapped.getPort();
		byte[] out = new byte[20 + 12 + 12 + 8];
		putShort(out, 0, BINDING_SUCCESS);
		putShort(out, 2, out.length - 20);
		putInt(out, 4, MAGIC_COOKIE);
		System.arraycopy(txid, 0, out, 8, 12);
		int o = 20;
		putShort(out, o, ATTR_XOR_MAPPED_ADDRESS);
		putShort(out, o + 2, 8);
		out[o + 5] = 0x01;
		putShort(out, o + 6, port ^ (MAGIC_COOKIE >>> 16));
		putInt(out, o + 8, ipInt ^ MAGIC_COOKIE);
		o += 12;
		putShort(out, o, ATTR_MAPPED_ADDRESS);
		putShort(out, o + 2, 8);
		out[o + 5] = 0x01;
		putShort(out, o + 6, port);
		System.arraycopy(ip, 0, out, o + 8, 4);
		o += 12;
		putShort(out, o, ATTR_FINGERPRINT);
		putShort(out, o + 2, 4);
		CRC32 crc = new CRC32();
		crc.update(out, 0, o);
		putInt(out, o + 4, (int) crc.getValue() ^ FINGERPRINT_XOR);
		return out;
	}

	static int getShort(byte[] b, int o) {
		return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
	}

	static int getInt(byte[] b, int o) {
		return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
	}

	static void putShort(byte[] b, int o, int v) {
		b[o] = (byte) (v >>> 8);
		b[o + 1] = (byte) v;
	}

	static void putInt(byte[] b, int o, int v) {
		b[o] = (byte) (v >>> 24);
		b[o + 1] = (byte) (v >>> 16);
		b[o + 2] = (byte) (v >>> 8);
		b[o + 3] = (byte) v;
	}
}
