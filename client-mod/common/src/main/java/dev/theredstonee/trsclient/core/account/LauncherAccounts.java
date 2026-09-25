package dev.theredstonee.trsclient.core.account;

import dev.theredstonee.trsclient.core.link.TrsLink;
import dev.theredstonee.trsclient.core.online.Uuids;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Konten des TRS Launchers über die gesicherte Verbindung ({@link TrsLink}, Protokoll 2). Blockierende
 * Aufrufe – nur aus dem Konto-Thread. Tokens kommen versiegelt und werden erst hier entsiegelt.
 */
public final class LauncherAccounts {
	static final long LIST_TIMEOUT_MS = 10_000L;
	static final long SESSION_TIMEOUT_MS = 30_000L;
	/** Anmeldung im Browser dauert (Launcher wartet bis 5 min). */
	static final long ADD_TIMEOUT_MS = 6 * 60_000L;

	/** Fehlercode vom Launcher bzw. {@code offline}/{@code timeout}. */
	public static final class LinkException extends Exception {
		private static final long serialVersionUID = 1L;
		public final String code;

		LinkException(String code) {
			super(code);
			this.code = code;
		}
	}

	private final TrsLink link;

	public LauncherAccounts(TrsLink link) {
		this.link = link;
	}

	public boolean available() {
		TrsLink.Status s = link.status();
		return s.connected && s.accounts;
	}

	private TrsLink.Line call(String op, Map<String, String> args, long timeoutMs) throws LinkException {
		final TrsLink.Line[] result = new TrsLink.Line[1];
		final String[] error = new String[1];
		final CountDownLatch done = new CountDownLatch(1);
		link.request(op, args, timeoutMs, new TrsLink.Callback() {
			@Override
			public void done(TrsLink.Line response) {
				result[0] = response;
				done.countDown();
			}

			@Override
			public void failed(String code) {
				error[0] = code;
				done.countDown();
			}
		});
		try {
			if (!done.await(timeoutMs + 2000, TimeUnit.MILLISECONDS)) throw new LinkException("timeout");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LinkException("cancelled");
		}
		if (error[0] != null) throw new LinkException(TrsLink.safeError(error[0]));
		return result[0];
	}

	static GameAccount account(TrsLink.AccountDto a) {
		if (a == null) return null;
		String uuid = Uuids.normalize(a.id);
		if (uuid == null || a.name == null || !a.name.matches("[A-Za-z0-9_]{1,16}")) return null;
		return new GameAccount(uuid, a.name, MsAuth.safeSkinUrl(a.skinUrl), GameAccount.Source.LAUNCHER,
				Boolean.TRUE.equals(a.active), false);
	}

	public List<GameAccount> list() throws LinkException {
		TrsLink.Line res = call("accounts.list", null, LIST_TIMEOUT_MS);
		List<GameAccount> out = new ArrayList<GameAccount>();
		if (res.accounts != null) {
			for (TrsLink.AccountDto a : res.accounts) {
				GameAccount g = account(a);
				if (g != null) out.add(g);
				if (out.size() >= 50) break;
			}
		}
		return Collections.unmodifiableList(out);
	}

	/** Frische Sitzung für ein Launcher-Konto. */
	public SessionData session(String uuid) throws LinkException {
		Map<String, String> args = Collections.singletonMap("account", uuid);
		TrsLink.Line res = call("accounts.session", args, SESSION_TIMEOUT_MS);
		TrsLink.SessionDto s = res.session;
		if (s == null) throw new LinkException("error");
		byte[] token = link.unseal(s.token);
		if (token == null) throw new LinkException("error");
		String xuid = s.xuid != null && s.xuid.matches("[0-9]{1,20}") ? s.xuid : null;
		SessionData data = new SessionData(s.id, s.name, new String(token, StandardCharsets.UTF_8), xuid);
		java.util.Arrays.fill(token, (byte) 0);
		if (!data.valid() || !uuid.equals(data.uuid)) throw new LinkException("error");
		return data;
	}

	/** Microsoft-Anmeldung im Launcher (öffnet dort den Browser); liefert das neue Konto. */
	public GameAccount add() throws LinkException {
		TrsLink.Line res = call("accounts.add", null, ADD_TIMEOUT_MS);
		GameAccount g = account(res.account);
		if (g == null) throw new LinkException("error");
		return g;
	}
}
