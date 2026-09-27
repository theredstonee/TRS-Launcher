package dev.theredstonee.trsclient.core.profile;

import dev.theredstonee.trsclient.core.config.ConfigPart;
import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.hud.HudProfiles;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.ModuleRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-Profile: beim Betreten eines Servers (bzw. einer Einzelspieler-Welt) schaltet der Client automatisch auf ein
 * gespeichertes Setup um – Module an/aus samt Einstellungen und auf Wunsch ein HUD-Profil – und beim Verlassen zurück
 * auf „Standard“.
 *
 * <p><b>Modell:</b> „Standard“ ist der normale Zustand der Module. Ein Server-Profil speichert nur die Module, die
 * vom Standard abweichen ({@link Profile#modules}), plus optional den Namen eines {@link HudProfiles HUD-Profils}
 * (dann gehören die HUD-Module diesem HUD-Profil). Beim Betreten wird der Standard festgehalten ({@code base}) und das
 * Profil darübergelegt; Änderungen während der Sitzung wandern beim Verlassen (und bei jedem Speichern) ins Profil –
 * wie bei den HUD-Profilen. Beim Verlassen kommt der Standard zurück.
 *
 * <p><b>Speichern:</b> {@code trsclient.json} enthält unter {@code modules} (und beim aktiven HUD-Profil) immer den
 * Standard, auch während eine Sitzung läuft ({@link #write}). So startet das Spiel nach einem Absturz sauber im
 * Standard, ältere Versionen lesen die Datei unverändert, und der Client-Sync sieht keinen „geänderten“ Zustand, nur
 * weil man einen Server betritt.
 *
 * <p><b>Sync:</b> bewusst nur lokal – die Profile enthalten Serveradressen, die laut Sync-Vertrag nie ins Konto-Dokument
 * gehören (siehe {@code core.sync.ClientDoc}); außerdem gehört ein Profil oft zu einer bestimmten Instanz (Modpack,
 * Version). Der Standard-Zustand selbst wird wie bisher synchronisiert.
 */
public final class ServerProfiles implements ConfigPart {
	public static final int MAX_PROFILES = 32;
	public static final int MAX_NAME_LENGTH = HudProfiles.MAX_NAME_LENGTH;
	public static final int MAX_PATTERNS = 12;
	/** Module, die nie zu einem Server-Profil gehören (Konto, Sozial, Startbildschirm, Neustart nötig …). */
	static final Set<String> EXCLUDED = new HashSet<String>(Arrays.asList("serverProfiles", "trsOnline", "social",
			"titleScreen", "menuStyle", "builtinOptimizations"));

	/** Ein gespeichertes Server-Profil. */
	public static final class Profile {
		String name;
		final List<String> patterns = new ArrayList<String>();
		String hudProfile;
		Map<String, ModuleConfig> modules = new LinkedHashMap<String, ModuleConfig>();

		Profile(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}

		public List<String> patterns() {
			return Collections.unmodifiableList(patterns);
		}

		/** Name des HUD-Profils oder null. */
		public String hudProfile() {
			return hudProfile;
		}

		/** Anzahl der Module, die vom Standard abweichen. */
		public int moduleCount() {
			return modules.size();
		}

		/** IDs der abweichenden Module (nur lesen). */
		public Set<String> moduleIds() {
			return Collections.unmodifiableSet(modules.keySet());
		}
	}

	private final ModuleRegistry registry;
	private final HudProfiles hud;
	private final Module switch_;
	private final List<Profile> profiles = new ArrayList<Profile>();

	// --- Sitzung (nur zur Laufzeit) ---
	/** Aktueller Kontext: null (keine Welt), {@link ServerPattern#SINGLEPLAYER}, {@link ServerPattern#UNKNOWN_SERVER} oder Adresse. */
	private String context;
	private boolean contextKnown;
	private Profile active;
	/** Standard-Zustand beim Betreten (alle berücksichtigten Module). */
	private Map<String, ModuleConfig> base;
	private String baseHud;
	/** Zustand beim Betreten der Welt (für „aktuelles Setup merken“ ohne passendes Profil). */
	private Map<String, ModuleConfig> joinState;
	private String joinHud;
	/** Profile/Schalter geändert – beim nächsten {@link #update} neu zuordnen. */
	private boolean dirty;

	/**
	 * @param registry alle Module
	 * @param hud      HUD-Profile (müssen vorher registriert sein – dieser Teil liest/schreibt danach)
	 * @param enabled  Modul „Server-Profile“ (aus = kein automatischer Wechsel)
	 */
	public ServerProfiles(ModuleRegistry registry, HudProfiles hud, Module enabled) {
		this.registry = registry;
		this.hud = hud;
		this.switch_ = enabled;
		registry.addPart(this);
	}

	// --- Abfragen ---

	public int size() {
		return profiles.size();
	}

	public Profile get(int index) {
		return profiles.get(index);
	}

	public List<Profile> all() {
		return Collections.unmodifiableList(profiles);
	}

	/** Aktives Server-Profil oder null (= Standard). */
	public Profile active() {
		return active;
	}

	/** Aktueller Kontext (null = keine Welt). */
	public String context() {
		return context;
	}

	public boolean inWorld() {
		return context != null;
	}

	public boolean canCreate() {
		return profiles.size() < MAX_PROFILES;
	}

	/** Bestes passendes Profil für einen Kontext oder null. */
	public Profile match(String ctx) {
		if (ctx == null) return null;
		Profile best = null;
		int bestScore = 0;
		for (Profile p : profiles) {
			for (String pattern : p.patterns) {
				int s = ServerPattern.score(pattern, ctx);
				if (s > bestScore) {
					bestScore = s;
					best = p;
				}
			}
		}
		return best;
	}

	/** Anzeige-Text eines Kontexts (Adresse oder „Einzelspieler“). */
	public static String contextLabel(String ctx) {
		if (ctx == null) return "";
		if (ServerPattern.SINGLEPLAYER.equals(ctx)) return I18n.tr("serverProfiles.singleplayer");
		if (ServerPattern.UNKNOWN_SERVER.equals(ctx)) return I18n.tr("serverProfiles.unknownServer");
		return ctx;
	}

	/** Anzeige-Text eines Musters. */
	public static String patternLabel(String pattern) {
		return ServerPattern.SINGLEPLAYER.equals(pattern) ? I18n.tr("serverProfiles.singleplayer") : pattern;
	}

	// --- Laufzeit ---

	/**
	 * Einmal je Tick mit dem aktuellen Kontext (null = keine Welt, {@link ServerPattern#SINGLEPLAYER} = Einzelspieler,
	 * sonst die Serveradresse). Schaltet bei Bedarf um.
	 *
	 * @return null = nichts geändert; sonst wurde umgeschaltet (der Aufrufer sollte speichern) – die Meldung für die
	 * Aktionsleiste (bereits übersetzt) oder "" (z. B. beim Verlassen der Welt, nichts anzuzeigen)
	 */
	public String update(String ctx) {
		boolean enabled = switch_.isEnabled();
		boolean same = contextKnown && (ctx == null ? context == null : ctx.equals(context));
		if (same && !dirty && enabled == lastEnabled) return null;
		lastEnabled = enabled;
		dirty = false;
		String message = null;
		if (!same) {
			// Welt/Server gewechselt: altes Profil zuerst verlassen, dann den Standard beim Betreten merken.
			if (active != null) {
				leave();
				message = I18n.tr("serverProfiles.toast.standard");
			}
			contextKnown = true;
			context = ctx;
			joinState = ctx == null ? null : snapshot();
			joinHud = ctx == null ? null : hud.activeName();
		}
		Profile wanted = enabled ? match(ctx) : null;
		if (wanted != active) {
			if (active != null) {
				leave();
				message = I18n.tr("serverProfiles.toast.standard");
			}
			if (wanted != null) {
				enter(wanted);
				message = I18n.tr("serverProfiles.toast.active", wanted.name);
			}
		}
		if (message != null && ctx == null) return "";
		return message;
	}

	private boolean lastEnabled;

	/** Beim nächsten {@link #update} neu zuordnen (nach Änderungen an Profilen oder am Schalter). */
	public void markDirty() {
		dirty = true;
	}

	private void enter(Profile p) {
		base = snapshot();
		baseHud = hud.activeName();
		if (p.hudProfile != null) {
			int index = hud.names().indexOf(p.hudProfile);
			if (index >= 0) hud.switchTo(index);
		}
		boolean ownHud = usesHud(p);
		for (Map.Entry<String, ModuleConfig> e : p.modules.entrySet()) {
			Module m = registry.byId(e.getKey());
			if (!eligible(m) || (ownHud && m.inProfiles())) continue;
			m.read(e.getValue().copy().normalized());
		}
		active = p;
	}

	private void leave() {
		Profile p = active;
		if (p == null) return;
		p.modules = diff(snapshot(), base, usesHud(p));
		if (usesHud(p)) {
			int index = baseHud == null ? -1 : hud.names().indexOf(baseHud);
			if (index >= 0) hud.switchTo(index);
		}
		restoreBase(usesHud(p));
		active = null;
		base = null;
		baseHud = null;
	}

	private void restoreBase(boolean skipHudModules) {
		if (base == null) return;
		for (Map.Entry<String, ModuleConfig> e : base.entrySet()) {
			Module m = registry.byId(e.getKey());
			if (!eligible(m) || (skipHudModules && m.inProfiles())) continue;
			m.read(e.getValue().copy().normalized());
		}
	}

	/** Gehört das HUD dieses Profils einem HUD-Profil (das es auch gibt)? */
	private boolean usesHud(Profile p) {
		return p.hudProfile != null && hud.names().contains(p.hudProfile);
	}

	/**
	 * „Aktuelles Setup für diesen Server merken“: im aktiven Profil sofort übernehmen bzw. – ohne passendes Profil – ein
	 * neues für diesen Server anlegen (Name = Adresse, Muster = Host). Was seit dem Betreten verändert wurde, gehört
	 * dann dem Profil; der Standard bleibt so, wie er beim Betreten war.
	 *
	 * @return Fehlermeldung oder null bei Erfolg
	 */
	public String rememberCurrent() {
		if (context == null) return I18n.tr("serverProfiles.error.noWorld");
		if (active != null) {
			active.modules = diff(snapshot(), base, usesHud(active));
			if (!usesHud(active) && baseHud != null && !baseHud.equals(hud.activeName())) {
				// HUD-Profil während der Sitzung gewechselt → gehört ab jetzt zum Profil.
				active.hudProfile = hud.activeName();
				active.modules = diff(snapshot(), base, true);
			}
			return null;
		}
		Profile existing = match(context);
		if (existing != null) {
			// Passendes Profil, aber automatischer Wechsel aus: Abweichungen vom Stand beim Betreten übernehmen.
			existing.modules = diff(snapshot(), joinState != null ? joinState : snapshot(), usesHud(existing));
			return null;
		}
		if (!canCreate()) return I18n.tr("serverProfiles.error.max", MAX_PROFILES);
		String pattern = ServerPattern.forContext(context);
		if (pattern == null) return I18n.tr("serverProfiles.error.pattern", contextLabel(context));
		String name = uniqueName(ServerPattern.SINGLEPLAYER.equals(pattern) ? I18n.tr("serverProfiles.singleplayer")
				: "*".equals(pattern) ? I18n.tr("serverProfiles.anyServer") : pattern);
		Profile p = new Profile(name);
		p.patterns.add(pattern);
		Map<String, ModuleConfig> start = joinState != null ? joinState : snapshot();
		String startHud = joinHud != null ? joinHud : hud.activeName();
		boolean hudChanged = !startHud.equals(hud.activeName());
		if (hudChanged) p.hudProfile = hud.activeName();
		p.modules = diff(snapshot(), start, hudChanged);
		profiles.add(p);
		// Ab jetzt läuft die Sitzung mit dem Stand beim Betreten als Standard.
		base = start;
		baseHud = startHud;
		active = p;
		return null;
	}

	// --- Bearbeiten (Menü) ---

	/** Neues, leeres Profil (Muster = aktueller Server, wenn man in einer Welt ist). @return Fehler oder null */
	public String create(String name) {
		if (!canCreate()) return I18n.tr("serverProfiles.error.max", MAX_PROFILES);
		String clean = HudProfiles.clean(name);
		String error = validate(clean, -1);
		if (error != null) return error;
		Profile p = new Profile(clean);
		String pattern = ServerPattern.forContext(context);
		if (pattern != null && match(context) == null) p.patterns.add(pattern);
		profiles.add(p);
		dirty = true;
		return null;
	}

	/** @return Fehlermeldung oder null */
	public String rename(int index, String name) {
		if (index < 0 || index >= profiles.size()) return I18n.tr("profiles.error.unknown");
		String clean = HudProfiles.clean(name);
		String error = validate(clean, index);
		if (error != null) return error;
		profiles.get(index).name = clean;
		return null;
	}

	/**
	 * Setzt die Muster aus einer Eingabe (getrennt durch ;, Komma oder Leerzeichen).
	 *
	 * @return Fehlermeldung (ungültiges Muster) oder null
	 */
	public String setPatterns(int index, String text) {
		if (index < 0 || index >= profiles.size()) return I18n.tr("profiles.error.unknown");
		List<String> invalid = new ArrayList<String>();
		List<String> parsed = ServerPattern.parseList(text, invalid);
		if (!invalid.isEmpty()) return I18n.tr("serverProfiles.error.pattern", invalid.get(0));
		if (parsed.size() > MAX_PATTERNS) return I18n.tr("serverProfiles.error.patterns", MAX_PATTERNS);
		Profile p = profiles.get(index);
		p.patterns.clear();
		p.patterns.addAll(parsed);
		dirty = true;
		return null;
	}

	/** Muster als Eingabetext (Einzelspieler als {@link ServerPattern#SINGLEPLAYER}). */
	public String patternsText(int index) {
		StringBuilder sb = new StringBuilder();
		for (String s : profiles.get(index).patterns) {
			if (sb.length() > 0) sb.append("; ");
			sb.append(s);
		}
		return sb.toString();
	}

	/** Einzelspieler-Muster an/aus. */
	public void toggleSingleplayer(int index) {
		if (index < 0 || index >= profiles.size()) return;
		List<String> list = profiles.get(index).patterns;
		if (!list.remove(ServerPattern.SINGLEPLAYER)) list.add(0, ServerPattern.SINGLEPLAYER);
		dirty = true;
	}

	/** Nächstes HUD-Profil für dieses Server-Profil (null → erstes → … → null). */
	public void cycleHudProfile(int index) {
		if (index < 0 || index >= profiles.size()) return;
		Profile p = profiles.get(index);
		List<String> names = hud.names();
		int current = p.hudProfile == null ? -1 : names.indexOf(p.hudProfile);
		String next = current + 1 < names.size() ? names.get(current + 1) : null;
		boolean wasActive = p == active;
		if (wasActive) leave();
		p.hudProfile = next;
		dirty = true;
	}

	/** Setzt die Modul-Abweichungen zurück (das Profil gleicht dann dem Standard). */
	public void clearModules(int index) {
		if (index < 0 || index >= profiles.size()) return;
		Profile p = profiles.get(index);
		if (p == active) leave();
		p.modules = new LinkedHashMap<String, ModuleConfig>();
		dirty = true;
	}

	/** Löscht ein Profil (ist es aktiv, kommt zuerst der Standard zurück). */
	public boolean delete(int index) {
		if (index < 0 || index >= profiles.size()) return false;
		Profile p = profiles.get(index);
		if (p == active) leave();
		profiles.remove(index);
		dirty = true;
		return true;
	}

	/** Freier Name wie „Server-Profil 3“. */
	public String suggestName() {
		for (int i = profiles.size() + 1; ; i++) {
			String candidate = I18n.tr("serverProfiles.suggest", i);
			if (indexOf(candidate) < 0) return candidate;
		}
	}

	/** @return Fehler oder null */
	public String validate(String name, int ignoreIndex) {
		String clean = HudProfiles.clean(name);
		if (clean.isEmpty()) return I18n.tr("profiles.error.empty");
		if (clean.length() > MAX_NAME_LENGTH) return I18n.tr("profiles.error.long", MAX_NAME_LENGTH);
		int existing = indexOf(clean);
		if (existing >= 0 && existing != ignoreIndex) return I18n.tr("profiles.error.taken");
		return null;
	}

	private String uniqueName(String wanted) {
		String clean = HudProfiles.clean(wanted);
		if (clean.length() > MAX_NAME_LENGTH) clean = clean.substring(0, MAX_NAME_LENGTH).trim();
		if (clean.isEmpty()) return suggestName();
		if (indexOf(clean) < 0) return clean;
		for (int i = 2; ; i++) {
			String suffix = " " + i;
			String base = clean.length() + suffix.length() > MAX_NAME_LENGTH ? clean.substring(0, MAX_NAME_LENGTH - suffix.length()).trim() : clean;
			if (indexOf(base + suffix) < 0) return base + suffix;
		}
	}

	private int indexOf(String name) {
		String key = name.toLowerCase(Locale.ROOT);
		for (int i = 0; i < profiles.size(); i++) {
			if (profiles.get(i).name.toLowerCase(Locale.ROOT).equals(key)) return i;
		}
		return -1;
	}

	// --- Zustände ---

	private boolean eligible(Module m) {
		return m != null && !EXCLUDED.contains(m.id());
	}

	/** Zustand aller berücksichtigten Module (tiefe Kopie). */
	private Map<String, ModuleConfig> snapshot() {
		Map<String, ModuleConfig> map = new LinkedHashMap<String, ModuleConfig>();
		for (Module m : registry.all()) {
			if (eligible(m)) map.put(m.id(), m.write());
		}
		return map;
	}

	/** Module aus {@code now}, die von {@code standard} abweichen (HUD-Module ggf. ausgenommen). */
	private Map<String, ModuleConfig> diff(Map<String, ModuleConfig> now, Map<String, ModuleConfig> standard, boolean skipHudModules) {
		Map<String, ModuleConfig> out = new LinkedHashMap<String, ModuleConfig>();
		if (now == null) return out;
		for (Map.Entry<String, ModuleConfig> e : now.entrySet()) {
			Module m = registry.byId(e.getKey());
			if (!eligible(m) || (skipHudModules && m.inProfiles())) continue;
			ModuleConfig was = standard == null ? null : standard.get(e.getKey());
			if (was == null || !same(e.getValue(), was)) out.put(e.getKey(), e.getValue().copy());
		}
		return out;
	}

	/** Gleicher gespeicherter Zustand? */
	static boolean same(ModuleConfig a, ModuleConfig b) {
		a.normalized();
		b.normalized();
		return eq(a.enabled, b.enabled) && eq(a.anchor, b.anchor) && eq(a.offsetX, b.offsetX) && eq(a.offsetY, b.offsetY)
				&& a.flags.equals(b.flags) && a.numbers.equals(b.numbers) && a.colors.equals(b.colors)
				&& a.choices.equals(b.choices) && a.keys.equals(b.keys) && a.texts.equals(b.texts);
	}

	private static boolean eq(Object a, Object b) {
		return a == null ? b == null : a.equals(b);
	}

	// --- Config ---

	@Override
	public void read(TrsConfig config) {
		String activeName = active != null ? active.name : null;
		profiles.clear();
		TrsConfig.ServerProfilesData stored = config.serverProfiles;
		if (stored != null && stored.profiles != null) {
			for (TrsConfig.ServerProfileData d : stored.profiles) {
				if (d == null || profiles.size() >= MAX_PROFILES) continue;
				String name = HudProfiles.clean(d.name);
				if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH).trim();
				if (name.isEmpty() || indexOf(name) >= 0) name = suggestName();
				Profile p = new Profile(name);
				if (d.patterns != null) {
					for (String raw : d.patterns) {
						String n = ServerPattern.normalize(raw);
						if (n != null && !p.patterns.contains(n) && p.patterns.size() < MAX_PATTERNS) p.patterns.add(n);
					}
				}
				String hudName = d.hudProfile == null ? null : HudProfiles.clean(d.hudProfile);
				p.hudProfile = hudName == null || hudName.isEmpty() ? null : hudName;
				if (d.modules != null) {
					for (Map.Entry<String, ModuleConfig> e : d.modules.entrySet()) {
						if (e.getKey() != null && e.getValue() != null && !EXCLUDED.contains(e.getKey())) {
							p.modules.put(e.getKey(), e.getValue().normalized());
						}
					}
				}
				profiles.add(p);
			}
		}
		// Die Datei enthält immer den Standard. Lief gerade eine Sitzung (Sync, Rückgängig), wird sie mit dem frisch
		// gelesenen Standard neu aufgebaut.
		active = null;
		base = null;
		baseHud = null;
		if (context != null) {
			joinState = snapshot();
			joinHud = hud.activeName();
			if (activeName != null && switch_.isEnabled()) {
				Profile again = match(context);
				if (again != null) enter(again);
			}
		}
		dirty = true;
	}

	@Override
	public void write(TrsConfig config) {
		boolean ownHud = active != null && usesHud(active);
		if (active != null) active.modules = diff(snapshot(), base, ownHud);
		TrsConfig.ServerProfilesData out = new TrsConfig.ServerProfilesData();
		for (Profile p : profiles) {
			TrsConfig.ServerProfileData d = new TrsConfig.ServerProfileData();
			d.name = p.name;
			d.patterns.addAll(p.patterns);
			d.hudProfile = p.hudProfile;
			for (Map.Entry<String, ModuleConfig> e : p.modules.entrySet()) d.modules.put(e.getKey(), e.getValue().copy());
			out.profiles.add(d);
		}
		config.serverProfiles = out.profiles.isEmpty() ? null : out;
		if (active == null || base == null) return;
		// Während einer Sitzung: Standard statt Profil-Zustand in die Datei.
		for (Map.Entry<String, ModuleConfig> e : base.entrySet()) {
			Module m = registry.byId(e.getKey());
			if (!eligible(m) || (ownHud && m.inProfiles())) continue;
			config.modules.put(e.getKey(), e.getValue().copy());
		}
		if (ownHud && config.hudProfiles != null && baseHud != null) {
			config.hudProfiles.active = baseHud;
			for (TrsConfig.Profile hp : config.hudProfiles.profiles) {
				if (hp.name == null || !hp.name.equals(baseHud)) continue;
				for (Map.Entry<String, ModuleConfig> e : hp.modules.entrySet()) config.modules.put(e.getKey(), e.getValue().copy());
			}
		}
	}
}
