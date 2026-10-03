package dev.theredstonee.trsclient.core.connect;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Vom TRS Launcher vorab aufgelöste Server ({@code config/trsclient/connect-hints.json}, geschrieben vor jedem
 * Start): SRV-Ziel, alle A/AAAA-Adressen und welche am schnellsten verband. Nur ein Vorschlag – die Einträge
 * laufen schnell ab (höchstens {@link DnsCache#MAX_TTL_MS}), enthalten nur IP-Adressen (nie DNS beim Lesen) und
 * ändern nie den Namen, den Minecraft im Handshake schickt.
 *
 * <pre>{"version":1,"join":"play.example.net","hosts":[{"name":"play.example.net","srv":"0 5 25577 mc.example.net.","ips":["203.0.113.7"],
 * "fastest":"203.0.113.7","expires":1759500600000}]}</pre>
 * {@code srv}: fehlt = nicht geprüft, "" = sicher keiner. {@code expires}: Wanduhr in ms.
 */
public final class ConnectHints {
	public static final String FILE = "connect-hints.json";
	static final int MAX_BYTES = 64 * 1024;
	static final int MAX_HOSTS = 64;

	/** Gson-2.2.4-taugliche Datei-Form (nur Felder, Objekttypen). */
	static final class FileDto {
		Integer version;
		/** Server, dem dieser Start beitritt (wie eingegeben), oder null. */
		String join;
		List<HostDto> hosts;
	}

	static final class HostDto {
		String name;
		String srv;
		List<String> ips;
		String fastest;
		Long expires;
	}

	private final Path file;
	private long loadedStamp = Long.MIN_VALUE;
	private volatile String join;

	public ConnectHints(Path configDir) {
		this.file = configDir == null ? null : configDir.resolve("trsclient").resolve(FILE);
	}

	/** Neu einlesen, wenn sich die Datei geändert hat; liefert die Zahl übernommener Hosts (-1 = nichts Neues). */
	public synchronized int refresh(DnsCache cache) {
		if (file == null) return -1;
		long stamp;
		try {
			if (!Files.isRegularFile(file)) return -1;
			stamp = Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
		} catch (IOException | RuntimeException e) {
			return -1;
		}
		if (stamp == loadedStamp) return -1;
		loadedStamp = stamp;
		try {
			if (Files.size(file) > MAX_BYTES) return 0;
			FileDto dto;
			try (Reader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
				dto = new Gson().fromJson(r, FileDto.class);
			}
			join = dto == null || dto.version == null || dto.version != 1 ? null : validJoin(dto.join);
			return apply(dto, cache);
		} catch (IOException | RuntimeException e) {
			return 0;
		}
	}

	/** Server, dem dieser Start laut Launcher beitritt ("host[:port]"), oder null. */
	public String join() {
		return join;
	}

	static String validJoin(String s) {
		if (s == null) return null;
		String t = s.trim();
		if (t.isEmpty() || t.length() > 261 || dev.theredstonee.trsclient.core.net.StatusPing.parse(t) == null) return null;
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
					|| c == '_' || c == ':' || c == '[' || c == ']';
			if (!ok) return null;
		}
		return t;
	}

	/** Inhalt übernehmen (abgelaufene und ungültige Einträge werden übersprungen). */
	static int apply(FileDto dto, DnsCache cache) {
		if (dto == null || dto.version == null || dto.version != 1 || dto.hosts == null) return 0;
		int n = 0;
		long now = cache.now();
		for (HostDto h : dto.hosts) {
			if (n >= MAX_HOSTS) break;
			if (h == null || h.name == null || h.expires == null || h.expires <= now) continue;
			String name = h.name.trim();
			if (name.isEmpty() || name.length() > 254 || !hostChars(name)) continue;
			if (h.srv != null) {
				if (h.srv.isEmpty()) cache.putSrvUntil(name, null, h.expires);
				else {
					SrvRecord r = SrvRecord.parse(h.srv);
					if (r != null) cache.putSrvUntil(name, r, h.expires);
				}
			}
			List<byte[]> ips = new ArrayList<byte[]>();
			if (h.ips != null) {
				for (String s : h.ips) {
					byte[] ip = IpLiteral.parse(s);
					if (ip != null) ips.add(ip);
					if (ips.size() >= 16) break;
				}
			}
			if (!ips.isEmpty()) {
				cache.putAddressesUntil(name, ips, h.expires, DnsCache.Source.LAUNCHER);
				byte[] fastest = IpLiteral.parse(h.fastest);
				if (fastest != null) cache.winner(name, fastest);
			}
			n++;
		}
		return n;
	}

	static boolean hostChars(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_';
			if (!ok) return false;
		}
		return true;
	}
}
