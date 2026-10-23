package dev.theredstonee.trsclient.core.serverrules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.BoolSetting;
import dev.theredstonee.trsclient.core.module.ChoiceSetting;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.ModuleRegistry;
import dev.theredstonee.trsclient.core.module.NumberSetting;
import dev.theredstonee.trsclient.core.module.Setting;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TRS Server Rules im Client: Ein Server (Plugin/Mod „TRS Server Rules“) kann TRS-Client-Module sperren oder erzwingen
 * und Einstellungen festlegen bzw. begrenzen. Protokoll (verbindlich, siehe Server-Plugin):
 * <ul>
 *   <li>Kanäle: Server → Client {@link #CHANNEL_RULES} (MC ≥ 1.13) bzw. {@link #LEGACY_RULES} (MC ≤ 1.12),
 *       Client → Server {@link #CHANNEL_HELLO} bzw. {@link #LEGACY_HELLO}. Inhalt = UTF-8-JSON als rohe Bytes (kein
 *       Längenpräfix), höchstens {@link #MAX_BYTES}.</li>
 *   <li>Ablauf: Kanäle registrieren, nach dem Betreten (Spielphase) {@link #helloBytes()} senden. Jede {@code rules}-
 *       Nachricht ERSETZT den bisherigen Regelsatz; beim Trennen gilt nichts mehr.</li>
 * </ul>
 *
 * <p>Die Bäume liefern nur die Netzwerk-Anbindung (dünn):
 * <pre>
 *   ServerRules.init(modules.registry, modVersion, minecraft, "fabric", platform);   // einmal beim Start
 *   // Spielphase betreten:   r.onJoin(); senden(CHANNEL_HELLO, r.helloBytes());
 *   // rules empfangen:        im Render-/Spiel-Thread r.onRules(bytes);
 *   // Verbindung getrennt:    r.onDisconnect();
 * </pre>
 * (jeweils {@code ServerRules r = ServerRules.get(); if (r != null) …}). Alles Weitere – Sperren anwenden, Menü
 * „Vom Server“, Nachfrage für das TRS-Team, Hinweistext – liegt hier in common.
 *
 * <p><b>TRS-Team (offen dokumentiert):</b> Wer im TRS-Team das Recht {@link #BYPASS_PERMISSION} hat, wird beim Versuch,
 * etwas Gesperrtes zu ändern, gefragt („Dieser Server sperrt X. Als TRS-Team trotzdem benutzen?“). Ja hebt die Sperre
 * für dieses Modul bis zum Trennen auf. Der Server erfährt davon nichts (kein Paket).
 *
 * <p>Nur aus dem Spiel-/Render-Thread benutzen (außer {@link #team}).
 */
public final class ServerRules {
	/** Namensraum und Pfade der modernen Kanäle (für Loader-APIs, die beides getrennt wollen). */
	public static final String NAMESPACE = "trsclient";
	public static final String PATH_RULES = "rules";
	public static final String PATH_HELLO = "hello";
	/** Server → Client (Minecraft ≥ 1.13). */
	public static final String CHANNEL_RULES = NAMESPACE + ":" + PATH_RULES;
	/** Client → Server (Minecraft ≥ 1.13). */
	public static final String CHANNEL_HELLO = NAMESPACE + ":" + PATH_HELLO;
	/** Server → Client (Minecraft ≤ 1.12, Plugin-Kanal alter Art). */
	public static final String LEGACY_RULES = "TRS|Rules";
	/** Client → Server (Minecraft ≤ 1.12). */
	public static final String LEGACY_HELLO = "TRS|Hello";
	/** Größte erlaubte Nachricht (Bytes) – größere werden verworfen. */
	public static final int MAX_BYTES = 32 * 1024;
	/** Protokollversion dieses Clients. */
	public static final int PROTOCOL = 1;
	/** Team-Recht der TRS API, das die Nachfrage „trotzdem benutzen?“ freischaltet. */
	public static final String BYPASS_PERMISSION = "serverrules.bypass";

	/** Was der jeweilige Baum beisteuert. */
	public interface Platform {
		/** Einen TRS-Hinweis zeigen (z. B. Chat-Zeile mit TRS-Kennung); Text ist schon übersetzt und bereinigt. */
		void notice(String text);

		/** Log-Zeile. */
		void log(String message);

		/** Gibt es das Modul in diesem Build (sonst nicht im hello)? Standard: {@link Module#available()}. */
		boolean supports(Module module);
	}

	/** Basis ohne Ausgabe (Tests, Bäume ohne Chat). */
	public static class SilentPlatform implements Platform {
		@Override
		public void notice(String text) {
		}

		@Override
		public void log(String message) {
		}

		@Override
		public boolean supports(Module module) {
			return module.available();
		}
	}

	private static volatile ServerRules instance;
	/** Darf der Spieler als TRS-Team Sperren aufheben? (aus {@code GET /v1/me}, beliebiger Thread) */
	private static volatile boolean teamBypass;

	private final ModuleRegistry registry;
	private final String modVersion;
	private final String minecraft;
	private final String loader;
	private final Platform platform;

	private RuleSet rules = RuleSet.EMPTY;
	/** Module, deren Sperre das TRS-Team für diese Verbindung aufgehoben hat. */
	private final Set<String> bypassed = new HashSet<String>();
	/** Schon gezeigte Hinweistexte dieser Verbindung (jeder nur einmal). */
	private final Set<String> shownMessages = new HashSet<String>();
	/** Zahl der empfangenen Regelsätze dieser Verbindung (Anzeige/Tests). */
	private int received;

	public ServerRules(ModuleRegistry registry, String modVersion, String minecraft, String loader, Platform platform) {
		this.registry = registry;
		this.modVersion = modVersion == null ? "?" : modVersion;
		this.minecraft = minecraft == null ? "unknown" : minecraft;
		this.loader = loader == null ? "unknown" : loader;
		this.platform = platform == null ? new SilentPlatform() : platform;
	}

	/** Einmal beim Start des Clients (je Baum). Ein zweiter Aufruf ersetzt die Plattform nicht. */
	public static synchronized ServerRules init(ModuleRegistry registry, String modVersion, String minecraft, String loader,
			Platform platform) {
		if (instance == null) instance = new ServerRules(registry, modVersion, minecraft, loader, platform);
		return instance;
	}

	/** Die Regeln des laufenden Spiels (null vor {@link #init}). */
	public static ServerRules get() {
		return instance;
	}

	/** Für Tests: eigene Instanz einsetzen (null = keine). */
	static void install(ServerRules rules) {
		instance = rules;
	}

	// --- Netzwerk (von den Bäumen) ---

	/** Inhalt der {@code hello}-Nachricht (JSON). */
	public String helloJson() {
		JsonObject o = new JsonObject();
		o.addProperty("v", Integer.valueOf(PROTOCOL));
		o.addProperty("client", NAMESPACE);
		o.addProperty("version", modVersion);
		o.addProperty("mc", minecraft);
		o.addProperty("loader", loader);
		JsonArray modules = new JsonArray();
		for (Module m : registry.all()) {
			boolean supported;
			try {
				supported = platform.supports(m);
			} catch (RuntimeException e) {
				supported = false;
			}
			if (supported) modules.add(new JsonPrimitive(m.id()));
		}
		o.add("modules", modules);
		return o.toString();
	}

	/** {@link #helloJson()} als UTF-8-Bytes (so wie sie über den Kanal gehen). */
	public byte[] helloBytes() {
		return helloJson().getBytes(StandardCharsets.UTF_8);
	}

	/** Neue Verbindung (Spielphase betreten): alles von vorher vergessen. Danach {@link #helloBytes()} senden. */
	public void onJoin() {
		reset();
	}

	/** Verbindung getrennt: alle Regeln und Team-Freigaben verwerfen. */
	public void onDisconnect() {
		reset();
	}

	private void reset() {
		BypassPrompt.cancel();
		bypassed.clear();
		shownMessages.clear();
		received = 0;
		apply(RuleSet.EMPTY);
	}

	/**
	 * Eine {@code rules}-Nachricht (rohe Bytes) – im Spiel-/Render-Thread aufrufen. Ersetzt den bisherigen Regelsatz.
	 * Kaputte oder zu große Nachrichten werden verworfen (die alten Regeln bleiben dann).
	 *
	 * @return true, wenn die Nachricht gelesen werden konnte
	 */
	public boolean onRules(byte[] data) {
		RuleSet set = RuleSet.parse(data);
		if (set == null) {
			platform.log("TRS Server Rules: ungültige Nachricht verworfen (" + (data == null ? 0 : data.length) + " Bytes)");
			return false;
		}
		received++;
		apply(set);
		platform.log("TRS Server Rules: " + set.modules.size() + " Modul-Regeln vom Server");
		if (set.message != null && shownMessages.add(set.message)) {
			notice(I18n.tr("serverrules.message", set.message));
		}
		return true;
	}

	// --- Anwenden ---

	/** Setzt die Sperren aller Module nach {@code set} (Team-Freigaben bleiben ausgenommen). */
	void apply(RuleSet set) {
		rules = set == null ? RuleSet.EMPTY : set;
		for (Module m : registry.all()) m.clearServerLocks();
		for (Map.Entry<String, ModuleLock> e : rules.modules.entrySet()) {
			if (bypassed.contains(e.getKey())) continue;
			Module m = registry.byId(e.getKey());
			if (m != null) applyTo(m, e.getValue());
		}
	}

	private static void applyTo(Module m, ModuleLock lock) {
		if (lock.enabled != null) m.serverLock(lock.enabled);
		if (lock.settings.isEmpty()) return;
		List<Setting> settings = m.settings();
		for (int i = 0; i < settings.size(); i++) {
			Setting s = settings.get(i);
			SettingLock rule = lock.settings.get(s.key());
			if (rule != null) applyTo(s, rule);
		}
	}

	/** Eine Einstellungs-Regel anwenden; passt der Typ nicht, wird sie still übergangen. */
	static void applyTo(Setting s, SettingLock rule) {
		if (s instanceof BoolSetting) {
			if (rule.value instanceof Boolean) ((BoolSetting) s).serverForce((Boolean) rule.value);
		} else if (s instanceof NumberSetting) {
			NumberSetting n = (NumberSetting) s;
			if (rule.value instanceof Double) {
				n.serverForce(((Double) rule.value).doubleValue());
			} else if (rule.value == null) {
				n.serverRange(rule.min, rule.max);
			}
		} else if (s instanceof ChoiceSetting) {
			if (rule.value instanceof String) ((ChoiceSetting<?>) s).serverForce((String) rule.value);
		}
	}

	// --- Abfragen ---

	/** Aktueller Regelsatz (leer, wenn der Server keine schickt). */
	public RuleSet rules() {
		return rules;
	}

	/** Hat der Server in dieser Verbindung schon Regeln geschickt? */
	public boolean received() {
		return received > 0;
	}

	/** Zahl der in dieser Verbindung empfangenen Regelsätze (Anzeige/Tests). */
	public int receivedCount() {
		return received;
	}

	/** Hat das TRS-Team die Sperre dieses Moduls (für diese Verbindung) aufgehoben? */
	public boolean bypassed(String moduleId) {
		return moduleId != null && bypassed.contains(moduleId);
	}

	// --- TRS-Team ---

	/**
	 * Team-Angaben aus {@code GET /v1/me} ({@code team.owner}, {@code team.permissions}); {@code null}/leer = kein Team.
	 * Darf aus jedem Thread kommen.
	 */
	public static void team(Boolean owner, List<String> permissions) {
		boolean allowed = Boolean.TRUE.equals(owner);
		if (!allowed && permissions != null) {
			for (String p : permissions) {
				if (BYPASS_PERMISSION.equals(p)) {
					allowed = true;
					break;
				}
			}
		}
		teamBypass = allowed;
	}

	/** Darf der Spieler als TRS-Team Server-Sperren aufheben (nach Nachfrage)? */
	public static boolean canBypass() {
		return teamBypass;
	}

	/**
	 * Hebt die Sperre eines Moduls (samt seiner Einstellungen) bis zum Trennen auf – nur mit Team-Recht. Der Server
	 * erfährt davon nichts.
	 *
	 * @return true, wenn aufgehoben
	 */
	public boolean bypass(String moduleId) {
		if (!canBypass() || moduleId == null) return false;
		Module m = registry.byId(moduleId);
		if (m == null) return false;
		bypassed.add(moduleId);
		m.clearServerLocks();
		platform.log("TRS Server Rules: Sperre von " + moduleId + " als TRS-Team aufgehoben (bis zum Trennen)");
		notice(I18n.tr("serverrules.bypass.done", m.name()));
		return true;
	}

	/** Modul zu einer Einstellung (null = keines). */
	public Module moduleOf(Setting s) {
		return s == null || s.moduleId() == null ? null : registry.byId(s.moduleId());
	}

	/** Modul nach ID (null = keines). */
	public Module module(String id) {
		return id == null ? null : registry.byId(id);
	}

	private void notice(String text) {
		try {
			platform.notice(text);
		} catch (RuntimeException e) {
			platform.log("TRS Server Rules: Hinweis fehlgeschlagen: " + e);
		}
	}

	/** Für Tests: Team-Recht direkt setzen. */
	static void teamForTests(boolean allowed) {
		teamBypass = allowed;
	}
}
