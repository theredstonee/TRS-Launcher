package dev.theredstonee.trsclient.core.hosting.share;

import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Auswahl des Hosts: welche Mods mitgehen (Pflicht/optional) und welches Resource Pack. Ab Werk ist nichts geteilt;
 * erst „Mods teilen“ bzw. „Resource Pack teilen“ blendet die Auswahl ein. Mods kommen aus dem {@code mods}-Ordner der
 * laufenden Instanz ({@link ModScan}), Store-Treffer aus {@link StoreLookup}. Vorauswahl:
 * <ul>
 * <li>TRS Client: nie in der Liste (hat der Gast ohnehin), Fabric API (und Loader): immer Pflicht, gesperrt;</li>
 * <li>Inhalts-Mods (eigene Blöcke/Items/Rezepte, nicht nur Client): Pflicht;</li>
 * <li>reine Client-Mods: nicht gewählt (auf Wunsch optional);</li>
 * <li>andere (z. B. Bibliotheken): optional – Abhängigkeiten von Pflicht-Mods werden selbst Pflicht.</li>
 * </ul>
 * Ohne „Mods direkt vom Host übertragen“ werden Mods ohne Store-Treffer nur als Hinweis „selbst besorgen“ geteilt.
 *
 * <p>Nicht threadsicher: Oberfläche im Spiel-Thread, {@link #scan} im Hintergrund meldet über {@link #poll}.
 */
public final class ShareModel {
	/** IDs, die nie in der Liste stehen (hat jeder TRS-Gast). */
	static final Set<String> IMPLICIT = new HashSet<String>(java.util.Arrays.asList("trsclient"));
	/** IDs, die immer Pflicht sind (Loader-Grundlage). */
	static final Set<String> LOCKED = new HashSet<String>(java.util.Arrays.asList("fabric-api", "fabric", "qsl",
			"quilted_fabric_api"));

	/** Art einer Mod (für Vorauswahl und Anzeige). */
	public enum Kind {
		BASE, CONTENT, CLIENT, OTHER
	}

	/** Eine Zeile der Auswahl. */
	public static final class Row {
		public final ModScan.LocalMod mod;
		public final Kind kind;
		public final StoreLookup.Match match;
		public boolean on;
		public boolean required;
		/** Wird von einer Pflicht-Mod gebraucht (Pflicht erzwungen). */
		public boolean dependency;

		Row(ModScan.LocalMod mod, Kind kind, StoreLookup.Match match) {
			this.mod = mod;
			this.kind = kind;
			this.match = match;
		}

		public boolean locked() {
			return kind == Kind.BASE || dependency;
		}

		/** Woher der Gast sie bekäme (bei aktueller Einstellung „direkt“). */
		public SharedContent.Source source(boolean direct) {
			if (match != null) return match.source;
			return direct && mod.size <= SharedContent.MAX_HOST_FILE ? SharedContent.Source.HOST : SharedContent.Source.MANUAL;
		}
	}

	/** Ein Resource Pack im resourcepacks-Ordner. */
	public static final class PackFile {
		public final Path path;
		public final String file;
		public final long size;
		public final long modified;

		PackFile(Path path, long size, long modified) {
			this.path = path;
			this.file = path.getFileName().toString();
			this.size = size;
			this.modified = modified;
		}

		public String name() {
			String n = file.toLowerCase(Locale.ROOT).endsWith(".zip") ? file.substring(0, file.length() - 4) : file;
			String s = SharedContent.shown(n);
			return s.isEmpty() ? "Resource Pack" : s;
		}

		public boolean tooLarge() {
			return size > SharedContent.MAX_PACK;
		}
	}

	/** Ergebnis für API + Datei-Kanal. */
	public static final class Built {
		public final SharedContent content;
		public final Map<String, FileServer.Entry> files;

		public Built(SharedContent content, Map<String, FileServer.Entry> files) {
			this.content = content;
			this.files = files;
		}
	}

	public enum State {
		IDLE, SCANNING, READY, FAILED
	}

	private final Path gameDir;
	private final String worldKey;
	private final ShareSettings store;
	private final ShareSettings.World settings;
	private volatile State state = State.IDLE;
	private List<Row> rows = Collections.emptyList();
	private List<PackFile> packs = Collections.emptyList();
	private volatile boolean viaLauncher;
	private volatile String lookupProblem;
	private volatile Object pending;
	private int generation;

	public ShareModel(Path gameDir, String worldKey, ShareSettings store) {
		this.gameDir = gameDir;
		this.worldKey = worldKey;
		this.store = store;
		this.settings = store == null ? new ShareSettings.World() : store.get(worldKey);
	}

	// --- Lesen ---

	public State state() {
		return state;
	}

	public int generation() {
		return generation;
	}

	public boolean shareMods() {
		return settings.shareMods;
	}

	public boolean direct() {
		return settings.direct;
	}

	public boolean sharePack() {
		return settings.sharePack;
	}

	public String packFile() {
		return settings.packFile;
	}

	public List<Row> rows() {
		return rows;
	}

	public List<PackFile> packs() {
		return packs;
	}

	/** Wurden die Store-Treffer über den Launcher ermittelt (inkl. CurseForge)? */
	public boolean viaLauncher() {
		return viaLauncher;
	}

	/** Store-Abfrage gescheitert (Liste gilt dann ohne Treffer)? */
	public String lookupProblem() {
		return lookupProblem;
	}

	/** Irgendetwas zu teilen? */
	public boolean active() {
		return settings.shareMods || settings.sharePack;
	}

	public int selectedCount() {
		int n = 0;
		for (Row r : rows) if (r.on) n++;
		return n;
	}

	public int requiredCount() {
		int n = 0;
		for (Row r : rows) if (r.on && r.required) n++;
		return n;
	}

	public PackFile selectedPack() {
		if (settings.packFile == null) return null;
		for (PackFile p : packs) if (p.file.equals(settings.packFile)) return p;
		return null;
	}

	// --- Ändern (Spiel-Thread) ---

	public void setShareMods(boolean on) {
		settings.shareMods = on;
		save();
	}

	public void setDirect(boolean on) {
		settings.direct = on;
		save();
	}

	public void setSharePack(boolean on) {
		settings.sharePack = on;
		save();
	}

	public void choosePack(String file) {
		settings.packFile = file != null && ShareSettings.validPackFile(file) ? file : null;
		save();
	}

	public void toggle(Row r) {
		if (r.locked()) return;
		r.on = !r.on;
		if (r.on && r.kind == Kind.CLIENT) r.required = false;
		remember(r);
		closeDependencies();
		save();
	}

	public void setRequired(Row r, boolean required) {
		if (r.locked() || !r.on) return;
		r.required = required;
		remember(r);
		closeDependencies();
		save();
	}

	private void remember(Row r) {
		settings.choices.put(r.mod.sha1, new ShareSettings.Choice(r.on, r.required));
	}

	private void save() {
		generation++;
		if (store != null && worldKey != null) store.put(worldKey, settings);
	}

	// --- Einlesen (Hintergrund) ---

	/** Mods + Packs einlesen und Store-Treffer holen (im {@code executor}); Ergebnis per {@link #poll}. */
	public void scan(final Executor executor, final Http http) {
		if (state == State.SCANNING) return;
		state = State.SCANNING;
		generation++;
		executor.execute(new Runnable() {
			@Override
			public void run() {
				List<ModScan.LocalMod> mods = ModScan.scan(gameDir == null ? null : gameDir.resolve("mods"));
				List<PackFile> ps = listPacks(gameDir == null ? null : gameDir.resolve("resourcepacks"));
				Map<String, StoreLookup.Match> matches = Collections.emptyMap();
				String problem = null;
				boolean[] launcher = { false };
				try {
					if (http != null && !mods.isEmpty()) matches = StoreLookup.identify(http, mods, launcher);
				} catch (IOException | RuntimeException e) {
					problem = "hosting.share.lookupFailed";
				}
				pending = new Object[] { mods, ps, matches, problem, launcher[0] };
			}
		});
	}

	/** Ergebnis des Einlesens übernehmen (Spiel-Thread); true = neu. */
	@SuppressWarnings("unchecked")
	public boolean poll() {
		Object p = pending;
		if (p == null) return false;
		pending = null;
		Object[] a = (Object[]) p;
		apply((List<ModScan.LocalMod>) a[0], (List<PackFile>) a[1], (Map<String, StoreLookup.Match>) a[2]);
		lookupProblem = (String) a[3];
		viaLauncher = (Boolean) a[4];
		state = State.READY;
		generation++;
		return true;
	}

	/** Zeilen aus Scan + Treffern + gemerkter Wahl bilden (auch für Tests). */
	public void apply(List<ModScan.LocalMod> mods, List<PackFile> ps, Map<String, StoreLookup.Match> matches) {
		List<Row> out = new ArrayList<Row>();
		for (ModScan.LocalMod m : mods) {
			if (m.id != null && IMPLICIT.contains(m.id)) continue;
			Kind kind = m.id != null && LOCKED.contains(m.id) ? Kind.BASE : m.clientOnly() ? Kind.CLIENT
					: m.content ? Kind.CONTENT : Kind.OTHER;
			Row r = new Row(m, kind, matches == null ? null : matches.get(m.sha1));
			ShareSettings.Choice c = settings.choices.get(m.sha1);
			switch (kind) {
				case BASE:
					r.on = true;
					r.required = true;
					break;
				case CONTENT:
					r.on = c == null || c.on;
					r.required = c == null || c.required;
					break;
				case CLIENT:
					r.on = c != null && c.on;
					r.required = false;
					break;
				default:
					r.on = c == null || c.on;
					r.required = c != null && c.required;
			}
			out.add(r);
			if (out.size() >= SharedContent.MAX_MODS) break;
		}
		rows = out;
		packs = ps == null ? Collections.<PackFile>emptyList() : ps;
		closeDependencies();
	}

	/** Abhängigkeiten von Pflicht-Mods werden gewählt + Pflicht (gesperrt, solange die Pflicht-Mod es ist). */
	void closeDependencies() {
		Map<String, Row> byId = new HashMap<String, Row>();
		for (Row r : rows) {
			r.dependency = false;
			if (r.mod.id != null) byId.put(r.mod.id, r);
		}
		boolean changed = true;
		int guard = 0;
		while (changed && guard++ < 50) {
			changed = false;
			for (Row r : rows) {
				if (!r.on || !r.required) continue;
				for (String dep : r.mod.depends) {
					Row d = byId.get(dep);
					if (d == null || d == r || d.kind == Kind.BASE) continue;
					if (!d.on || !d.required || !d.dependency) {
						d.on = true;
						d.required = true;
						d.dependency = true;
						changed = true;
					}
				}
			}
		}
	}

	static List<PackFile> listPacks(Path dir) {
		List<PackFile> out = new ArrayList<PackFile>();
		if (dir == null || !Files.isDirectory(dir)) return out;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.zip")) {
			for (Path p : ds) {
				if (!ShareSettings.validPackFile(p.getFileName().toString())) continue;
				BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				if (!a.isRegularFile()) continue;
				out.add(new PackFile(p, a.size(), a.lastModifiedTime().toMillis()));
				if (out.size() >= 200) break;
			}
		} catch (IOException | RuntimeException e) {
			// dann eben keine
		}
		Collections.sort(out, new java.util.Comparator<PackFile>() {
			@Override
			public int compare(PackFile a, PackFile b) {
				return a.file.compareToIgnoreCase(b.file);
			}
		});
		return out;
	}

	// --- Bauen (Hintergrund: Pack-Hashes) ---

	/** Stand der Auswahl zum Bauen im Hintergrund (Spiel-Thread). */
	public Snapshot snapshot() {
		List<Row> copy = new ArrayList<Row>();
		for (Row r : rows) {
			Row c = new Row(r.mod, r.kind, r.match);
			c.on = r.on;
			c.required = r.required;
			c.dependency = r.dependency;
			copy.add(c);
		}
		boolean ready = state == State.READY;
		return new Snapshot(settings.copy(), ready ? copy : Collections.<Row>emptyList(), selectedPack());
	}

	/** Unveränderlicher Stand; {@link #build} blockiert (Pack-Hash). */
	public static final class Snapshot {
		final ShareSettings.World settings;
		final List<Row> rows;
		final PackFile pack;

		Snapshot(ShareSettings.World settings, List<Row> rows, PackFile pack) {
			this.settings = settings;
			this.rows = rows;
			this.pack = pack;
		}

		public Built build() throws IOException {
			return ShareModel.build(settings, rows, pack);
		}
	}

	/** Wie {@link Snapshot#build} mit dem aktuellen Stand (Tests). */
	public Built build() throws IOException {
		return snapshot().build();
	}

	/**
	 * Was geteilt wird: Mod-Liste (nur bei „Mods teilen“) + Pack (nur bei „Resource Pack teilen“) + Freigabeliste für
	 * den Datei-Kanal (nur Mods „direkt vom Host“ und das Pack). Blockierend (Pack-Hash).
	 */
	static Built build(ShareSettings.World settings, List<Row> rows, PackFile selectedPack) throws IOException {
		List<SharedContent.Mod> mods = new ArrayList<SharedContent.Mod>();
		Map<String, FileServer.Entry> files = new LinkedHashMap<String, FileServer.Entry>();
		if (settings.shareMods) {
			long hostTotal = 0;
			for (Row r : rows) {
				if (!r.on) continue;
				SharedContent.Source src = r.source(settings.direct);
				if (src == SharedContent.Source.HOST && hostTotal + r.mod.size > SharedContent.MAX_HOST_TOTAL) {
					src = SharedContent.Source.MANUAL;
				}
				String file = fileName(r.mod.file);
				if (file == null || r.mod.size > SharedContent.MAX_STORE_FILE) continue;
				String name = SharedContent.shown(r.mod.name);
				if (name.isEmpty()) name = SharedContent.shown(ModScan.stripJar(file));
				if (name.isEmpty()) name = "Mod";
				SharedContent.Mod m = new SharedContent.Mod(name, SharedContent.shown(r.mod.version), file, r.mod.size, r.required, src,
						src.store() ? r.match.projectId : null, src.store() ? r.match.fileId : null, r.mod.sha1, r.mod.sha512,
						r.mod.sha256, r.match != null && src.store() ? r.match.fingerprint : -1);
				if (m.problem() != null) continue;
				mods.add(m);
				if (src == SharedContent.Source.HOST) {
					hostTotal += r.mod.size;
					files.put(r.mod.sha256, new FileServer.Entry(FileChannel.KIND_MOD, r.mod.path, r.mod.size, r.mod.modified,
							r.mod.sha256));
				}
			}
		}
		SharedContent.Pack pack = null;
		PackFile pf = settings.sharePack ? selectedPack : null;
		if (pf != null && !pf.tooLarge()) {
			String[] h = ModScan.hashes(pf.path);
			BasicFileAttributes a = Files.readAttributes(pf.path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			pack = new SharedContent.Pack(pf.name(), a.size(), h[0], h[2]);
			if (pack.problem() == null) {
				files.put(h[2], new FileServer.Entry(FileChannel.KIND_PACK, pf.path, a.size(), a.lastModifiedTime().toMillis(), h[2]));
			} else {
				pack = null;
			}
		}
		return new Built(new SharedContent(mods, pack), files);
	}

	/** Dateiname für die Liste: wie er ist, sonst unerlaubte Zeichen durch „_“ ersetzt; unbrauchbar → null. */
	static String fileName(String f) {
		if (SharedContent.validFileName(f)) return f;
		StringBuilder b = new StringBuilder();
		String base = ModScan.stripJar(f);
		for (int i = 0; i < base.length() && b.length() < 120; i++) {
			char c = base.charAt(i);
			b.append(Character.isLetterOrDigit(c) && c < 128 || c == '.' || c == '-' || c == '_' || c == '+' ? c : '_');
		}
		while (b.length() > 0 && !Character.isLetterOrDigit(b.charAt(0))) b.deleteCharAt(0);
		String out = b.toString().replace("..", "_") + ".jar";
		return SharedContent.validFileName(out) ? out : null;
	}
}
