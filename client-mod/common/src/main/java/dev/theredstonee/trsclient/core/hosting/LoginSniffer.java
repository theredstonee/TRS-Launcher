package dev.theredstonee.trsclient.core.hosting;

import java.nio.charset.StandardCharsets;

/**
 * Liest die ersten Minecraft-Pakete eines Gasts mit (Handshake + Login-Start – beide unkomprimiert und
 * unverschlüsselt, Rahmen = VarInt-Länge) und prüft den Spielernamen, BEVOR der integrierte Server ihn sieht.
 *
 * <p>Warum: ab 1.19.4 loggt der integrierte Server einen Spieler, der sich mit dem Namen des Hosts meldet, ohne
 * Mojang-Prüfung als Host ein. Über das Welt-Hosting darf das nie gehen. Zusätzlich: Gäste über Relay/Direkt müssen
 * sich mit ihrem eigenen (von der TRS API bestätigten) Namen melden.
 *
 * <p>Status-Abfragen (Serverliste) und das uralte 0xFE-Ping laufen durch. Nicht threadsicher (ein Strom).
 */
public final class LoginSniffer {
	/** Entscheidung über den Namen. */
	public interface Policy {
		/** true = dieser Name darf sich anmelden. */
		boolean allow(String name);
	}

	public enum Verdict {
		/** Noch nicht genug gelesen. */
		MORE,
		/** Durchlassen, nicht weiter mitlesen. */
		PASS,
		/** Verbindung schließen. */
		REJECT
	}

	static final int MAX_SNIFF = 4096;

	private final Policy policy;
	private final byte[] buf = new byte[MAX_SNIFF];
	private int len;
	private Verdict verdict = Verdict.MORE;
	private String name;

	public LoginSniffer(Policy policy) {
		this.policy = policy;
	}

	/** Gelesener Name (nach PASS/REJECT bei Login) oder null. */
	public String name() {
		return name;
	}

	public Verdict verdict() {
		return verdict;
	}

	/** Nächste eingehende Bytes des Gasts. */
	public Verdict feed(byte[] b, int off, int n) {
		if (verdict != Verdict.MORE) return verdict;
		int take = Math.min(n, MAX_SNIFF - len);
		System.arraycopy(b, off, buf, len, take);
		len += take;
		verdict = decide();
		if (verdict == Verdict.MORE && len >= MAX_SNIFF) verdict = Verdict.REJECT;
		return verdict;
	}

	private Verdict decide() {
		if (len == 0) return Verdict.MORE;
		if ((buf[0] & 0xFF) == 0xFE) return Verdict.PASS; // Legacy-Ping (≤1.6-Format)
		int[] pos = { 0 };
		// Paket 1: Handshake
		int frame = varInt(pos);
		if (frame == -2) return Verdict.MORE;
		if (frame < 0 || frame > 1024) return Verdict.REJECT;
		int start = pos[0];
		if (len < start + frame) return Verdict.MORE;
		int end = start + frame;
		int id = varInt(pos);
		if (id != 0) return Verdict.REJECT;
		if (varInt(pos) < 0) return Verdict.REJECT; // Protokoll
		int hostLen = varInt(pos);
		if (hostLen < 0 || hostLen > 1024) return Verdict.REJECT;
		pos[0] += hostLen;
		pos[0] += 2; // Port
		int next = varInt(pos);
		if (pos[0] > end) return Verdict.REJECT;
		if (next == 1) return Verdict.PASS; // Status
		if (next != 2 && next != 3) return Verdict.REJECT;
		// Paket 2: Login-Start (Name als String)
		pos[0] = end;
		int f2 = varInt(pos);
		if (f2 == -2) return Verdict.MORE;
		if (f2 < 0 || f2 > 1024) return Verdict.REJECT;
		int s2 = pos[0];
		if (len < s2 + f2) return Verdict.MORE;
		if (varInt(pos) != 0) return Verdict.REJECT;
		int nameLen = varInt(pos);
		if (nameLen < 1 || nameLen > 64 || pos[0] + nameLen > s2 + f2) return Verdict.REJECT;
		name = new String(buf, pos[0], nameLen, StandardCharsets.UTF_8);
		if (!name.matches("[A-Za-z0-9_]{1,16}")) return Verdict.REJECT;
		return policy.allow(name) ? Verdict.PASS : Verdict.REJECT;
	}

	/** VarInt ab pos[0]; -2 = unvollständig, -1 = kaputt. */
	private int varInt(int[] pos) {
		int value = 0;
		for (int i = 0; i < 5; i++) {
			if (pos[0] >= len) return -2;
			int b = buf[pos[0]++] & 0xFF;
			value |= (b & 0x7F) << (7 * i);
			if ((b & 0x80) == 0) return value < 0 ? -1 : value;
		}
		return -1;
	}
}
