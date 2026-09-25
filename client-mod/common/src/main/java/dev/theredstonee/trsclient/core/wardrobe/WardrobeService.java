package dev.theredstonee.trsclient.core.wardrobe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.account.SessionData;
import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.skin.CapeImage;
import dev.theredstonee.trsclient.core.skin.MojangProfile;
import dev.theredstonee.trsclient.core.skin.SkinImage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Das Herz der Garderobe (versionsunabhängig, ohne Zeichnen): Skin-Bibliothek („Meine Skins“, per TRS-Sync gleich wie
 * im Launcher), Favoriten/Outfits/Emote-Rad im {@code wardrobe}-Dokument, Mojang-Umhänge und TRS-Umhänge, Skin
 * anwenden (aktiver Minecraft-Skin), Skins hinzufügen (Datei, URL, Spielername).
 *
 * <p>Alles Netz und alle Dateien laufen im Thread „TRS-Garderobe“; die Oberfläche liest nur den unveränderlichen
 * {@link State} ({@link #state()}) und stößt Aufgaben an. Ohne TRS-Dienste (keine Einwilligung, offline) bleibt alles
 * lokal unter {@code config/trsclient/wardrobe/<konto>/} und wird später abgeglichen.
 */
public final class WardrobeService {
	/** Abstand der automatischen Abgleiche, solange die Garderobe offen ist. */
	static final long SYNC_INTERVAL_MS = 5 * 60_000L;
	/** Wartezeit nach einer lokalen Änderung, bevor sie hochgeladen wird. */
	static final long PUSH_DELAY_MS = 3_000L;
	/** Höchstens so viele Skin-Uploads je Abgleich (API: 30/min). */
	static final int UPLOADS_PER_SYNC = 20;
	private static final Gson GSON = new Gson();

	/** Was die Garderobe vom Spiel braucht (Umsetzung: {@link WardrobeContext}). */
	public interface Platform {
		/** Spielsitzung mit Zugangs-Token oder null. */
		SessionData session();

		/** Token der TRS API (angemeldet + Einwilligung) oder null. */
		String trsToken();

		/** Adresse der TRS API. */
		String apiBase();

		/** Das TRS-Token wurde abgelehnt (401). */
		void trsTokenRejected(String token);

		/** Eigenes Aussehen neu setzen (Menü-Figur, Cache) – siehe {@code LocalSkin#update}. */
		void lookChanged(byte[] skinPng, boolean slim, byte[] capePng, boolean capeChanged);

		/** Eigenen TRS-Umhang neu nachschlagen. */
		void trsCapeChanged();

		Path configDir();

		String userAgent();

		void log(String message);
	}

	public enum Task {
		NONE, LOADING, SYNCING, IMPORTING, APPLYING, SAVING
	}

	/** Ein Skin für die Oberfläche. */
	public static final class Skin {
		public final String id;
		public final String name;
		public final boolean slim;
		/** 64×64 ARGB (wie gespeichert, nicht „deckend gemacht“). */
		public final int[] pixels;
		/** Nur in der Launcher-Bibliothek (nicht synchronisiert, hier nicht löschbar). */
		public final boolean launcherOnly;
		/** Wartet auf Upload. */
		public final boolean pending;
		/** Vergleichswert der Pixel (wie Minecraft sie zeigt). */
		public final int look;

		Skin(String id, String name, boolean slim, int[] pixels, boolean launcherOnly, boolean pending) {
			this.id = id;
			this.name = name;
			this.slim = slim;
			this.pixels = pixels;
			this.launcherOnly = launcherOnly;
			this.pending = pending;
			this.look = lookHash(pixels);
		}
	}

	/** Umhang-Vorschau (erstes Bild, Vanilla-Aufteilung). */
	public static final class CapeArt {
		public final int[] argb;
		public final int width;
		public final int height;

		CapeArt(int[] argb, int width, int height) {
			this.argb = argb;
			this.width = width;
			this.height = height;
		}
	}

	/** Ein wählbarer Umhang: {@code key} = {@code mojang:<id>} bzw. {@code trs:<id>}. */
	public static final class Cape {
		public final String key;
		public final String name;
		public final boolean trs;
		public final boolean animated;

		Cape(String key, String name, boolean trs, boolean animated) {
			this.key = key;
			this.name = name;
			this.trs = trs;
			this.animated = animated;
		}
	}

	/** Unveränderlicher Stand für die Oberfläche. */
	public static final class State {
		public final List<Skin> skins;
		public final WardrobeDoc doc;
		/** Mojang-Umhänge (null = unbekannt, z. B. offline). */
		public final List<Cape> mojangCapes;
		/** Eigene/freigeschaltete TRS-Umhänge (null = keine TRS-Dienste). */
		public final List<Cape> trsCapes;
		/** Getragener Mojang-/TRS-Umhang (Schlüssel) oder null. */
		public final String activeMojangCape;
		public final String activeTrsCape;
		public final Map<String, CapeArt> capeArt;
		/** Pixel-Vergleichswert des aktiven Minecraft-Skins (0 = unbekannt). */
		public final int activeLook;
		public final Task task;
		public final String message;
		public final Object[] args;
		public final boolean error;
		/** TRS-Dienste verfügbar (Sync an)? */
		public final boolean trs;
		/** Echte Mojang-Sitzung (Skin anwenden möglich)? */
		public final boolean session;
		/** Zuletzt hinzugefügter/gespeicherter Skin (zum Auswählen) oder null. */
		public final String added;
		public final int version;

		State(List<Skin> skins, WardrobeDoc doc, List<Cape> mojangCapes, List<Cape> trsCapes, String activeMojangCape,
				String activeTrsCape, Map<String, CapeArt> capeArt, int activeLook, Task task, String message, Object[] args,
				boolean error, boolean trs, boolean session, String added, int version) {
			this.skins = skins;
			this.doc = doc;
			this.mojangCapes = mojangCapes;
			this.trsCapes = trsCapes;
			this.activeMojangCape = activeMojangCape;
			this.activeTrsCape = activeTrsCape;
			this.capeArt = capeArt;
			this.activeLook = activeLook;
			this.task = task;
			this.message = message;
			this.args = args;
			this.error = error;
			this.trs = trs;
			this.session = session;
			this.added = added;
			this.version = version;
		}

		public boolean busy() {
			return task != Task.NONE;
		}

		public Skin skin(String id) {
			if (id == null) return null;
			for (Skin s : skins) if (s.id.equals(id)) return s;
			return null;
		}

		public WardrobeDoc.Outfit outfit(String id) {
			if (id == null) return null;
			for (WardrobeDoc.Outfit o : doc.outfits) if (o.id.equals(id)) return o;
			return null;
		}
	}

	private static volatile WardrobeService instance;

	private final Platform platform;
	private final Http http;
	private final MojangServices mojang;
	private final SafeFetch fetch;
	private final ExecutorService worker;
	private final AtomicReference<State> state = new AtomicReference<State>();

	// --- nur im Garderoben-Thread ---
	private String accountKey;
	private SkinLibrary library;
	private WardrobeDoc doc = WardrobeDoc.EMPTY;
	private String docUpdatedAt;
	private boolean docDirty;
	private final Map<String, int[]> pixelCache = new HashMap<String, int[]>();
	private List<LauncherLibrary.Item> launcherItems = Collections.emptyList();
	private List<Cape> mojangCapes;
	private List<Cape> trsCapes;
	private final Map<String, String> mojangCapeUrls = new HashMap<String, String>();
	private String activeMojangCape;
	private String activeTrsCape;
	private final Map<String, CapeArt> capeArt = new LinkedHashMap<String, CapeArt>();
	private int activeLook;
	private boolean lastTrs;
	private boolean lastSession;
	private int version;

	/** Pixel (64×64) und Armform des aktiven Minecraft-Skins – für die Editor-Vorlage „Aktueller Skin“. */
	private volatile int[] activePixels;
	private volatile boolean activeSlim;

	// --- Render-Thread ---
	private long nextSync;
	private volatile long pushAt;
	private volatile boolean syncQueued;

	WardrobeService(Platform platform, Http http, MojangServices mojang, SafeFetch fetch, ExecutorService worker) {
		this.platform = platform;
		this.http = http;
		this.mojang = mojang;
		this.fetch = fetch;
		this.worker = worker;
		state.set(new State(Collections.<Skin>emptyList(), WardrobeDoc.EMPTY, null, null, null, null,
				Collections.<String, CapeArt>emptyMap(), 0, Task.LOADING, null, new Object[0], false, false, false, null, 0));
	}

	/** Gemeinsame Instanz (beim ersten Öffnen der Garderobe angelegt). */
	public static synchronized WardrobeService shared(Platform platform) {
		if (instance == null) {
			ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "TRS-Garderobe");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY + 1);
				return t;
			});
			Http http = new Http.UrlConnection(platform.userAgent());
			instance = new WardrobeService(platform, http, new MojangServices(http), new SafeFetch(platform.userAgent()), worker);
		}
		return instance;
	}

	/** Nur für Tests. */
	static WardrobeService create(Platform platform, Http http, MojangServices mojang, SafeFetch fetch, ExecutorService worker) {
		return new WardrobeService(platform, http, mojang, fetch, worker);
	}

	public State state() {
		return state.get();
	}

	/** Pixel des aktiven Minecraft-Skins (64×64) oder null, solange unbekannt. */
	public int[] activePixels() {
		return activePixels;
	}

	public boolean activeSlim() {
		return activeSlim;
	}

	// ====================================================================================================
	// Aufrufe aus der Oberfläche (Render-Thread) – alles läuft im Hintergrund
	// ====================================================================================================

	/** Garderobe geöffnet: laden und abgleichen. */
	public void open() {
		submit(Task.LOADING, () -> {
			ensureAccount();
			refreshRemote(true);
		});
		nextSync = System.currentTimeMillis() + SYNC_INTERVAL_MS;
	}

	/** Jedes Bild, solange die Garderobe offen ist: fällige Abgleiche anstoßen. */
	public void tick(long now) {
		if (pushAt != 0 && now >= pushAt) {
			pushAt = 0;
			submit(null, () -> {
				ensureAccount();
				pushDoc();
			});
		}
		if (now >= nextSync && !syncQueued) {
			nextSync = now + SYNC_INTERVAL_MS;
			syncQueued = true;
			submit(null, () -> {
				try {
					ensureAccount();
					refreshRemote(false);
				} finally {
					syncQueued = false;
				}
			});
		}
	}

	/** Datei wählen (nativer Dialog) und hinzufügen. */
	public void importFile(final String dialogTitle) {
		submit(Task.IMPORTING, () -> {
			ensureAccount();
			FileChooser.Choice c = FileChooser.openPng(dialogTitle);
			if (!c.available) {
				fail("wardrobe.error.no_dialog");
				return;
			}
			if (c.path == null) {
				publish(Task.NONE, null, false, null);
				return;
			}
			Path p = java.nio.file.Paths.get(c.path);
			if (!Files.isRegularFile(p)) {
				fail("wardrobe.error.not_found");
				return;
			}
			if (Files.size(p) > SkinFiles.MAX_BYTES) {
				fail("wardrobe.error.too_large");
				return;
			}
			byte[] data = Files.readAllBytes(p);
			add(data, SkinFiles.nameFromFile(p.getFileName().toString(), "Skin"), null);
		});
	}

	/** Bild von einer https-Adresse hinzufügen. */
	public void importUrl(final String url) {
		submit(Task.IMPORTING, () -> {
			ensureAccount();
			SafeFetch.Result r;
			try {
				r = fetch.get(url, SkinFiles.MAX_BYTES);
			} catch (SafeFetch.FetchException e) {
				fail("wardrobe.error." + e.code);
				return;
			}
			String ct = r.contentType == null ? "" : r.contentType.toLowerCase(java.util.Locale.ROOT);
			if (!ct.isEmpty() && !ct.startsWith("image/png") && !ct.startsWith("application/octet-stream")
					&& !ct.startsWith("binary/octet-stream")) {
				fail("wardrobe.error.not_png");
				return;
			}
			add(r.body, SkinFiles.nameFromFile(pathOf(r.finalUrl), "Skin"), null);
		});
	}

	/** Skin eines Spielers (per Name) hinzufügen. */
	public void importName(final String name) {
		submit(Task.IMPORTING, () -> {
			ensureAccount();
			String n = name == null ? "" : name.trim();
			if (!n.matches("[A-Za-z0-9_]{1,16}")) {
				fail("wardrobe.error.invalid_name");
				return;
			}
			String url = null;
			Boolean slim = null;
			String token = platform.trsToken();
			if (token != null) {
				try {
					WardrobeApi.SkinView v = api().skinByName(token, n);
					if (v == null) {
						fail("wardrobe.error.player_not_found");
						return;
					}
					url = v.textureUrl;
					slim = "slim".equals(v.model);
					if (url == null) {
						fail("wardrobe.error.default_skin");
						return;
					}
				} catch (ApiException e) {
					if (e.unauthorized()) platform.trsTokenRejected(token);
					url = null; // weiter direkt bei Mojang
				} catch (IOException e) {
					url = null;
				}
			}
			if (url == null) {
				String uuid = mojang.uuidOf(n);
				if (uuid == null) {
					fail("wardrobe.error.player_not_found");
					return;
				}
				Http.Response res = http.send(new Http.Request("GET", MojangProfile.profileUrl(uuid)));
				if (res.status != 200) {
					fail(res.status == 204 || res.status == 404 ? "wardrobe.error.player_not_found" : "wardrobe.error.mojang_unavailable");
					return;
				}
				MojangProfile profile = MojangProfile.parse(res.text());
				if (profile == null || profile.skinUrl == null) {
					fail("wardrobe.error.default_skin");
					return;
				}
				url = profile.skinUrl;
				slim = profile.slim;
			}
			byte[] png = mojang.texture(url);
			add(png, n, slim);
		});
	}

	/** Bild aus dem Editor speichern: {@code replaceId} = bestehenden Skin überschreiben, sonst neu anlegen. */
	public void saveEdited(final int[] pixels, final boolean slim, final String name, final String replaceId) {
		final int[] px = Arrays.copyOf(pixels, 64 * 64);
		submit(Task.SAVING, () -> {
			ensureAccount();
			byte[] png = PngWriter.write(64, 64, px);
			String id = replaceId != null && library.find(replaceId) != null ? replaceId : null;
			if (id == null && library.size() >= SkinLibrary.MAX_SKINS) {
				fail("wardrobe.error.skin_limit");
				return;
			}
			if (id == null) id = SkinFiles.newId();
			storeSkin(id, SkinFiles.cleanName(name, "Skin"), slim, png, px);
			publish(Task.NONE, "wardrobe.msg.saved", false, id);
			pushSkins();
		});
	}

	/** Skin löschen (lokal + auf dem Server). */
	public void delete(final String id) {
		submit(Task.SAVING, () -> {
			ensureAccount();
			if (library.find(id) == null) {
				publish(Task.NONE, null, false, null);
				return;
			}
			library.remove(id, true);
			pixelCache.remove(id);
			library.save();
			changeDoc(doc.forgetSkin(id));
			publish(Task.NONE, "wardrobe.msg.deleted", false, null);
			pushSkins();
		});
	}

	/** Skin umbenennen. */
	public void rename(final String id, final String name) {
		submit(Task.SAVING, () -> {
			ensureAccount();
			SkinLibrary.Entry e = library.find(id);
			if (e == null) return;
			e.name = SkinFiles.cleanName(name, e.name);
			e.pendingPut = true;
			e.changedAt = System.currentTimeMillis();
			library.save();
			publish(Task.NONE, null, false, null);
			pushSkins();
		});
	}

	/** Skin aus der Bibliothek als aktiven Minecraft-Skin setzen. */
	public void apply(final String id) {
		submit(Task.APPLYING, () -> {
			ensureAccount();
			byte[] png;
			boolean slim;
			SkinLibrary.Entry e = library.find(id);
			if (e != null) {
				png = library.png(id);
				slim = e.slim;
			} else {
				LauncherLibrary.Item item = launcherItem(id);
				if (item == null) {
					fail("wardrobe.error.not_found");
					return;
				}
				png = Files.readAllBytes(item.file);
				slim = item.slim;
			}
			applyPng(png, slim);
		});
	}

	/** Pixel (aus dem Editor) direkt als Minecraft-Skin setzen. */
	public void applyPixels(final int[] pixels, final boolean slim) {
		final int[] px = Arrays.copyOf(pixels, 64 * 64);
		submit(Task.APPLYING, () -> {
			ensureAccount();
			applyPng(PngWriter.write(64, 64, px), slim);
		});
	}

	/** Umhang tragen: {@code mojang:<id>}, {@code trs:<id>} oder {@code none} (beides ablegen). */
	public void wearCape(final String key) {
		submit(Task.APPLYING, () -> {
			ensureAccount();
			wear(key);
			publish(Task.NONE, "wardrobe.msg.capeChanged", false, null);
		});
	}

	public void toggleFavorite(final String id) {
		submit(null, () -> {
			ensureAccount();
			changeDoc(doc.toggleFavorite(id));
			publish(null, null, false, null);
		});
	}

	/** Aktuellen Skin + Umhang als Outfit speichern. */
	public void saveOutfit(final String name, final String skinId, final String cape) {
		submit(null, () -> {
			ensureAccount();
			if (doc.outfits.size() >= WardrobeDoc.MAX_OUTFITS) {
				fail("wardrobe.error.outfit_limit");
				return;
			}
			String capeKey = WardrobeDoc.validCape(cape) ? cape : null;
			WardrobeDoc.Outfit o = new WardrobeDoc.Outfit(SkinFiles.newId(), SkinFiles.cleanName(name, "Outfit"),
					SkinFiles.validId(skinId) ? skinId : null, capeKey, null);
			changeDoc(doc.addOutfit(o));
			publish(Task.NONE, "wardrobe.msg.outfitSaved", false, o.id);
		});
	}

	public void renameOutfit(final String id, final String name) {
		submit(null, () -> {
			ensureAccount();
			for (WardrobeDoc.Outfit o : doc.outfits) {
				if (o.id.equals(id)) changeDoc(doc.replaceOutfit(o.rename(name)));
			}
			publish(null, null, false, null);
		});
	}

	public void deleteOutfit(final String id) {
		submit(null, () -> {
			ensureAccount();
			changeDoc(doc.removeOutfit(id));
			publish(null, null, false, null);
		});
	}

	/** Outfit anziehen: Skin (falls gesetzt) und Umhang (falls gesetzt). */
	public void applyOutfit(final String id) {
		submit(Task.APPLYING, () -> {
			ensureAccount();
			WardrobeDoc.Outfit o = null;
			for (WardrobeDoc.Outfit x : doc.outfits) if (x.id.equals(id)) o = x;
			if (o == null) return;
			if (o.cape != null) wear(o.cape);
			if (o.skin != null) {
				SkinLibrary.Entry e = library.find(o.skin);
				LauncherLibrary.Item item = e == null ? launcherItem(o.skin) : null;
				if (e == null && item == null) {
					fail("wardrobe.error.not_found");
					return;
				}
				applyPng(e != null ? library.png(o.skin) : Files.readAllBytes(item.file), e != null ? e.slim : item.slim);
				return;
			}
			publish(Task.NONE, "wardrobe.msg.applied", false, null);
		});
	}

	/** Emote-Rad: Platz belegen/leeren. */
	public void setEmoteSlot(final int slot, final String emoteId) {
		submit(null, () -> {
			ensureAccount();
			changeDoc(doc.setEmoteSlot(slot, emoteId));
			publish(null, null, false, null);
		});
	}

	/** Meldung ausblenden. */
	public void dismissMessage() {
		State s = state.get();
		if (s.message == null) return;
		state.compareAndSet(s, new State(s.skins, s.doc, s.mojangCapes, s.trsCapes, s.activeMojangCape, s.activeTrsCape,
				s.capeArt, s.activeLook, s.task, null, new Object[0], false, s.trs, s.session, s.added, s.version + 1));
	}

	// ====================================================================================================
	// Hintergrund
	// ====================================================================================================

	private interface Job {
		void run() throws Exception;
	}

	private void submit(final Task task, final Job job) {
		if (task != null) {
			State s;
			do {
				s = state.get();
			} while (!state.compareAndSet(s, new State(s.skins, s.doc, s.mojangCapes, s.trsCapes, s.activeMojangCape,
					s.activeTrsCape, s.capeArt, s.activeLook, task, null, new Object[0], false, s.trs, s.session, s.added,
					s.version + 1)));
		}
		try {
			worker.execute(() -> {
				try {
					job.run();
				} catch (CancelledException e) {
					// Fehler schon gemeldet
				} catch (MojangServices.ServiceException e) {
					fail("wardrobe.error." + e.code);
				} catch (SafeFetch.FetchException e) {
					fail("wardrobe.error." + e.code);
				} catch (SkinFiles.SkinException e) {
					fail("wardrobe.error." + e.code);
				} catch (IOException e) {
					fail("wardrobe.error.offline");
				} catch (Throwable t) {
					platform.log("TRS Garderobe: " + t.getClass().getSimpleName());
					fail("wardrobe.error.error");
				}
			});
		} catch (RuntimeException e) {
			fail("wardrobe.error.busy");
		}
	}

	/** Konto gewechselt? Dann Bibliothek/Dokument des neuen Kontos laden. */
	private void ensureAccount() {
		SessionData s = platform.session();
		String key = s != null && s.uuid != null ? s.uuid : "offline";
		if (key.equals(accountKey) && library != null) return;
		accountKey = key;
		Path dir = platform.configDir().resolve("trsclient").resolve("wardrobe").resolve(key);
		library = new SkinLibrary(dir);
		library.load();
		pixelCache.clear();
		mojangCapes = null;
		trsCapes = null;
		activeMojangCape = null;
		activeTrsCape = null;
		activeLook = 0;
		capeArt.clear();
		mojangCapeUrls.clear();
		loadDoc(dir);
		launcherItems = LauncherLibrary.read(platform.configDir());
		EmoteSlots.init(platform.configDir());
		EmoteSlots.set(doc.emoteSlots);
		publish(null, null, false, null);
	}

	/** Mojang-Profil, TRS-Sync und TRS-Umhänge. */
	private void refreshRemote(boolean loadingTask) throws Exception {
		SessionData session = platform.session();
		boolean usable = session != null && session.valid() && session.accessToken.length() > 8;
		lastSession = usable;
		if (usable) {
			try {
				MojangServices.Profile p = mojang.profile(session.accessToken);
				applyProfile(p);
			} catch (MojangServices.ServiceException | IOException e) {
				// Offline/abgelaufen: Garderobe geht trotzdem (lokal)
			}
		}
		publish(loadingTask ? Task.SYNCING : null, null, false, null);
		sync();
		publish(Task.NONE, null, false, null);
	}

	private void applyProfile(MojangServices.Profile p) {
		if (p == null) return;
		List<Cape> capes = new ArrayList<Cape>();
		activeMojangCape = null;
		for (MojangServices.Cape c : p.capes) {
			String key = "mojang:" + c.id;
			capes.add(new Cape(key, c.alias.isEmpty() ? "Cape" : c.alias, false, false));
			if (c.active) activeMojangCape = key;
			if (c.url != null) mojangCapeUrls.put(key, c.url);
			if (c.url != null && !capeArt.containsKey(key)) {
				try {
					PngDecoder.Image img = PngDecoder.decode(mojang.texture(c.url));
					int[] px = CapeImage.normalize(img.width, img.height, img.argb);
					if (px != null) {
						int w = CapeImage.width(img.width, img.height);
						capeArt.put(key, new CapeArt(px, w, w / 2));
					}
				} catch (Exception ignored) {
					// ohne Vorschau
				}
			}
		}
		mojangCapes = capes;
		if (p.skinUrl != null) {
			try {
				PngDecoder.Image img = PngDecoder.decode(mojang.texture(p.skinUrl));
				if (SkinImage.validSize(img.width, img.height)) {
					int[] px = img.height == 32 ? SkinImage.normalize(img.width, img.height, img.argb) : Arrays.copyOf(img.argb, 64 * 64);
					activeLook = lookHash(px);
					activePixels = px;
					activeSlim = p.slim;
				}
			} catch (Exception ignored) {
				activeLook = 0;
			}
		} else {
			activeLook = 0;
		}
	}

	/** Neuer Skin aus PNG-Bytes (geprüft, gespeichert, hochgeladen). */
	private void add(byte[] png, String name, Boolean slimHint) throws Exception {
		SkinFiles.Decoded d = SkinFiles.decode(png);
		if (library.size() >= SkinLibrary.MAX_SKINS) {
			fail("wardrobe.error.skin_limit");
			return;
		}
		boolean slim = slimHint != null ? slimHint : d.slim;
		// Alte 64×32-Skins werden im neuen Format gespeichert (wie Minecraft sie zeigt).
		byte[] stored = d.legacy ? PngWriter.write(64, 64, d.pixels) : png;
		String id = SkinFiles.newId();
		storeSkin(id, name, slim, stored, d.pixels);
		publish(Task.NONE, "wardrobe.msg.added", false, id);
		pushSkins();
	}

	private void storeSkin(String id, String name, boolean slim, byte[] png, int[] pixels) throws IOException {
		library.put(id, name, slim, png, true);
		library.save();
		pixelCache.put(id, pixels);
	}

	private void applyPng(byte[] png, boolean slim) throws Exception {
		SessionData session = platform.session();
		if (session == null || !session.valid() || session.accessToken.length() <= 8) {
			fail("wardrobe.error.no_session");
			return;
		}
		SkinFiles.Decoded d = SkinFiles.decode(png);
		MojangServices.Profile p = mojang.uploadSkin(session.accessToken, png, slim);
		activeLook = lookHash(d.pixels);
		activePixels = d.pixels;
		activeSlim = slim;
		if (p != null) {
			// Umhänge bleiben, aber die Antwort kennt den neuen Stand.
			List<Cape> keep = mojangCapes;
			applyProfileCapesOnly(p);
			if (mojangCapes == null) mojangCapes = keep;
		}
		platform.lookChanged(png, slim, null, false);
		String token = platform.trsToken();
		if (token != null) {
			try {
				api().skinChanged(token);
			} catch (ApiException e) {
				if (e.unauthorized()) platform.trsTokenRejected(token);
			} catch (IOException ignored) {
				// nicht schlimm – andere sehen ihn dann etwas später
			}
		}
		publish(Task.NONE, "wardrobe.msg.applied", false, null);
	}

	private void applyProfileCapesOnly(MojangServices.Profile p) {
		List<Cape> capes = new ArrayList<Cape>();
		activeMojangCape = null;
		for (MojangServices.Cape c : p.capes) {
			String key = "mojang:" + c.id;
			capes.add(new Cape(key, c.alias.isEmpty() ? "Cape" : c.alias, false, false));
			if (c.active) activeMojangCape = key;
			if (c.url != null) mojangCapeUrls.put(key, c.url);
		}
		mojangCapes = capes;
	}

	/** Umhang wechseln (Mojang und/oder TRS). */
	private void wear(String key) throws Exception {
		if (key == null) return;
		SessionData session = platform.session();
		boolean usable = session != null && session.valid() && session.accessToken.length() > 8;
		String token = platform.trsToken();
		if (key.equals("none")) {
			if (usable && activeMojangCape != null) {
				MojangServices.Profile p = mojang.setCape(session.accessToken, null);
				activeMojangCape = null;
				if (p != null) applyProfileCapesOnly(p);
				platform.lookChanged(null, false, null, true);
			}
			if (token != null && activeTrsCape != null) {
				setTrsCape(token, null);
			}
			return;
		}
		if (key.startsWith("mojang:")) {
			if (!usable) {
				fail("wardrobe.error.no_session");
				return;
			}
			MojangServices.Profile p = mojang.setCape(session.accessToken, key.substring(7));
			if (p != null) applyProfileCapesOnly(p);
			else activeMojangCape = key;
			byte[] cape = null;
			String url = mojangCapeUrls.get(key);
			if (url != null) {
				try {
					cape = mojang.texture(url);
				} catch (Exception ignored) {
					cape = null;
				}
			}
			platform.lookChanged(null, false, cape, true);
			// Ein TRS-Umhang würde den Mojang-Umhang im TRS Client verdecken → ablegen.
			if (token != null && activeTrsCape != null) setTrsCape(token, null);
			return;
		}
		if (key.startsWith("trs:")) {
			if (token == null) {
				fail("wardrobe.error.trs_offline");
				return;
			}
			setTrsCape(token, key.substring(4));
		}
	}

	private void setTrsCape(String token, String id) throws Exception {
		try {
			api().setCape(token, id);
			activeTrsCape = id == null ? null : "trs:" + id;
			platform.trsCapeChanged();
		} catch (ApiException e) {
			if (e.unauthorized()) platform.trsTokenRejected(token);
			fail("wardrobe.error." + (e.code().matches("[a-z_]{1,40}") ? e.code() : "error"));
			throw new CancelledException();
		}
	}

	/** Bricht eine Aufgabe nach bereits gemeldetem Fehler ab. */
	private static final class CancelledException extends RuntimeException {
		CancelledException() {
			super(null, null, false, false);
		}
	}

	// --- Sync (API.md §17) ---

	private WardrobeApi api() {
		return new WardrobeApi(http, platform.apiBase());
	}

	/** Voller Abgleich: Skins in beide Richtungen, dann das Dokument; ohne TRS nichts. */
	private void sync() throws Exception {
		String token = platform.trsToken();
		lastTrs = token != null;
		if (token == null) return;
		WardrobeApi api = api();
		WardrobeApi.SyncState remote;
		try {
			remote = api.sync(token);
		} catch (ApiException e) {
			if (e.unauthorized()) platform.trsTokenRejected(token);
			return;
		}
		boolean changed = false;
		Map<String, WardrobeApi.SyncSkin> byId = new HashMap<String, WardrobeApi.SyncSkin>();
		for (WardrobeApi.SyncSkin s : remote.skins) if (s != null && SkinFiles.validId(s.id)) byId.put(s.id, s);
		Set<String> tomb = new HashSet<String>();
		for (WardrobeApi.Tombstone t : remote.deletedSkins) if (t != null && SkinFiles.validId(t.id)) tomb.add(t.id);

		// 1) Lokal gelöschte auf dem Server löschen
		for (String id : new ArrayList<String>(library.pendingDeletes())) {
			try {
				api.deleteSkin(token, id);
				library.pendingDeletes().remove(id);
				byId.remove(id);
				changed = true;
			} catch (ApiException e) {
				if (e.unauthorized()) {
					platform.trsTokenRejected(token);
					return;
				}
			}
		}
		// 2) Server → lokal
		for (WardrobeApi.SyncSkin r : byId.values()) {
			SkinLibrary.Entry e = library.find(r.id);
			boolean slim = "slim".equals(r.variant);
			if (e != null && e.pendingPut) continue; // lokale Änderung gewinnt (wird unten hochgeladen)
			if (e == null || e.sha256 == null || !e.sha256.equals(r.sha256)) {
				try {
					byte[] png = api.skinPng(token, r.id);
					SkinFiles.Decoded d = SkinFiles.decode(png);
					SkinLibrary.Entry n = library.put(r.id, r.name, slim, png, false);
					n.sha256 = r.sha256;
					n.updatedAt = r.updatedAt;
					n.synced = true;
					n.pendingPut = false;
					pixelCache.put(r.id, d.pixels);
					changed = true;
				} catch (ApiException ex) {
					if (ex.unauthorized()) {
						platform.trsTokenRejected(token);
						return;
					}
				} catch (SkinFiles.SkinException ignored) {
					// kaputte Datei überspringen
				}
			} else if (!r.name.equals(e.name) || slim != e.slim) {
				e.name = SkinFiles.cleanName(r.name, e.name);
				e.slim = slim;
				e.updatedAt = r.updatedAt;
				changed = true;
			}
		}
		// 3) Grabsteine und still verschwundene
		for (SkinLibrary.Entry e : new ArrayList<SkinLibrary.Entry>(library.entries())) {
			if (byId.containsKey(e.id)) continue;
			if (tomb.contains(e.id) && !e.pendingPut) {
				library.remove(e.id, false);
				pixelCache.remove(e.id);
				doc = doc.forgetSkin(e.id);
				changed = true;
			} else if (e.synced && !e.pendingPut) {
				library.remove(e.id, false);
				pixelCache.remove(e.id);
				changed = true;
			} else if (!e.pendingPut) {
				e.pendingPut = true; // nur lokal → hochladen
			}
		}
		if (changed) library.save();
		// 4) Lokal → Server
		uploadPending(api, token);
		// 5) Dokument (LWW)
		mergeDoc(remote.wardrobe);
		pushDoc();
		// 6) TRS-Umhänge
		loadTrsCapes(api, token);
		publish(null, null, false, null);
	}

	private void uploadPending(WardrobeApi api, String token) throws IOException {
		int uploads = 0;
		boolean changed = false;
		for (SkinLibrary.Entry e : new ArrayList<SkinLibrary.Entry>(library.entries())) {
			if (!e.pendingPut) continue;
			if (uploads >= UPLOADS_PER_SYNC) break;
			try {
				byte[] png = library.png(e.id);
				WardrobeApi.SyncSkin r = api.putSkin(token, e.id, e.name, e.slim, png);
				uploads++;
				e.pendingPut = false;
				e.synced = true;
				if (r != null) {
					e.sha256 = r.sha256;
					e.updatedAt = r.updatedAt;
				}
				changed = true;
			} catch (ApiException ex) {
				if (ex.unauthorized()) {
					platform.trsTokenRejected(token);
					break;
				}
				if (ex.rateLimited()) break;
				if ("skin_limit".equals(ex.code())) {
					publishMessage("wardrobe.error.sync_limit", true);
					break;
				}
				if (ex.status() == 400 || ex.status() == 413) {
					e.pendingPut = false; // der Server nimmt diesen Skin nie – nur lokal behalten
					changed = true;
				}
			} catch (IOException ex) {
				break;
			}
		}
		if (changed) library.save();
	}

	/** Skins nach einer lokalen Änderung hochladen (wenn TRS verfügbar). */
	private void pushSkins() throws IOException {
		String token = platform.trsToken();
		if (token == null) return;
		WardrobeApi api = api();
		for (String id : new ArrayList<String>(library.pendingDeletes())) {
			try {
				api.deleteSkin(token, id);
				library.pendingDeletes().remove(id);
			} catch (ApiException e) {
				if (e.unauthorized()) platform.trsTokenRejected(token);
				break;
			}
		}
		library.save();
		uploadPending(api, token);
		publish(null, null, false, null);
	}

	private void loadTrsCapes(WardrobeApi api, String token) {
		try {
			List<WardrobeApi.Cape> list = api.capes(token);
			List<Cape> out = new ArrayList<Cape>();
			activeTrsCape = null;
			for (WardrobeApi.Cape c : list) {
				if (c == null || c.id == null || !c.id.matches("[a-z0-9][a-z0-9_-]{0,39}")) continue;
				if (!Boolean.TRUE.equals(c.owned)) continue;
				String key = "trs:" + c.id;
				out.add(new Cape(key, SkinFiles.cleanName(c.name, c.id), true, c.frames > 1));
				if (Boolean.TRUE.equals(c.active)) activeTrsCape = key;
				if (!capeArt.containsKey(key) && c.url != null) {
					try {
						Http.Response r = trsTexture(c.url, token);
						PngDecoder.Image img = PngDecoder.decode(r.body);
						int fw = c.width > 0 ? c.width : img.width;
						int fh = c.height > 0 ? c.height : fw / 2;
						if (fw == img.width && fh <= img.height && CapeImage.width(fw, fh) == fw) {
							int[] frame = Arrays.copyOf(img.argb, fw * fh);
							capeArt.put(key, new CapeArt(frame, fw, fh));
						}
					} catch (Exception ignored) {
						// ohne Vorschau
					}
				}
				if (out.size() >= 64) break;
			}
			trsCapes = out;
		} catch (ApiException e) {
			if (e.unauthorized()) platform.trsTokenRejected(token);
		} catch (IOException ignored) {
			// später
		}
	}

	private Http.Response trsTexture(String url, String token) throws IOException, ApiException {
		dev.theredstonee.trsclient.core.online.OnlineConfig cfg = new dev.theredstonee.trsclient.core.online.OnlineConfig(true,
				platform.apiBase(), dev.theredstonee.trsclient.core.online.OnlineConfig.DEFAULT_SESSION);
		return new dev.theredstonee.trsclient.core.online.TrsApi(http, cfg).texture(url, null, token);
	}

	// --- Dokument ---

	private void loadDoc(Path dir) {
		doc = WardrobeDoc.EMPTY;
		docUpdatedAt = null;
		docDirty = false;
		Path f = dir.resolve("wardrobe.json");
		try {
			if (Files.isRegularFile(f) && Files.size(f) < 256 * 1024) {
				JsonObject o = new JsonParser().parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject();
				doc = WardrobeDoc.fromJson(o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null);
				docUpdatedAt = o.has("updatedAt") && o.get("updatedAt").isJsonPrimitive() ? o.get("updatedAt").getAsString() : null;
				docDirty = o.has("dirty") && o.get("dirty").isJsonPrimitive() && o.get("dirty").getAsBoolean();
				if (parseTime(docUpdatedAt) == null) docUpdatedAt = null;
			}
		} catch (Exception e) {
			doc = WardrobeDoc.EMPTY;
		}
	}

	private void saveDoc() {
		try {
			Path dir = library.dir();
			Files.createDirectories(dir);
			JsonObject o = new JsonObject();
			o.addProperty("version", 1);
			if (docUpdatedAt != null) o.addProperty("updatedAt", docUpdatedAt);
			o.addProperty("dirty", docDirty);
			o.add("data", doc.toJson());
			Path tmp = dir.resolve("wardrobe.json.tmp");
			Files.write(tmp, GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, dir.resolve("wardrobe.json"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			platform.log("TRS Garderobe: wardrobe.json nicht schreibbar");
		}
	}

	/** Lokale Änderung: speichern, später hochladen. */
	private void changeDoc(WardrobeDoc next) {
		if (next == doc) return;
		doc = next;
		docUpdatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
		docDirty = true;
		saveDoc();
		EmoteSlots.set(doc.emoteSlots);
		pushAt = System.currentTimeMillis() + PUSH_DELAY_MS;
	}

	/** Server-Stand übernehmen, wenn er neuer ist als der lokale (oder lokal nichts Ungesendetes liegt). */
	void mergeDoc(WardrobeApi.Doc remote) {
		if (remote == null || remote.data == null) return;
		Instant r = parseTime(remote.updatedAt);
		Instant l = parseTime(docUpdatedAt);
		if (r == null) return;
		if (docDirty && l != null && !l.isBefore(r)) return; // lokal neuer → wird hochgeladen
		if (!docDirty && l != null && l.equals(r)) return; // gleich
		doc = WardrobeDoc.fromJson(remote.data);
		docUpdatedAt = r.toString();
		docDirty = false;
		saveDoc();
		EmoteSlots.set(doc.emoteSlots);
	}

	/** Ungesendete Änderung hochladen; 409 = Server neuer → übernehmen. */
	private void pushDoc() throws IOException {
		if (!docDirty || docUpdatedAt == null) return;
		String token = platform.trsToken();
		if (token == null) return;
		try {
			WardrobeApi.DocResult r = api().putWardrobe(token, doc.toJson(), docUpdatedAt);
			if (r.stale) {
				docDirty = false;
				mergeDoc(r.doc);
			} else {
				docDirty = false;
				saveDoc();
			}
			publish(null, null, false, null);
		} catch (ApiException e) {
			if (e.unauthorized()) platform.trsTokenRejected(token);
			if (e.status() == 400 || e.status() == 413) {
				docDirty = false; // nie annehmbar – nicht endlos erneut senden
				saveDoc();
			}
		}
	}

	static Instant parseTime(String s) {
		if (s == null || s.length() > 40) return null;
		try {
			return Instant.parse(s).truncatedTo(ChronoUnit.MILLIS);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// --- Zustand veröffentlichen ---

	private void fail(String key) {
		publish(Task.NONE, key, true, null);
	}

	private void publishMessage(String key, boolean error) {
		State s;
		do {
			s = state.get();
		} while (!state.compareAndSet(s, new State(s.skins, s.doc, s.mojangCapes, s.trsCapes, s.activeMojangCape,
				s.activeTrsCape, s.capeArt, s.activeLook, s.task, key, new Object[0], error, s.trs, s.session, s.added,
				s.version + 1)));
	}

	/** Neuen Stand bauen ({@code task} null = unverändert; {@code message} null = alte behalten, außer bei neuem Task). */
	private void publish(Task task, String message, boolean error, String added) {
		State prev = state.get();
		List<Skin> skins = new ArrayList<Skin>();
		Set<String> seen = new HashSet<String>();
		if (library != null) {
			List<SkinLibrary.Entry> entries = library.snapshot();
			for (SkinLibrary.Entry e : entries) {
				int[] px = pixels(e.id);
				if (px == null) continue;
				skins.add(new Skin(e.id, e.name, e.slim, px, false, e.pendingPut && lastTrs));
				seen.add(e.id);
			}
			for (LauncherLibrary.Item item : launcherItems) {
				if (seen.contains(item.id) || library.pendingDeletes().contains(item.id)) continue;
				int[] px = launcherPixels(item);
				if (px == null) continue;
				skins.add(new Skin(item.id, item.name, item.slim, px, true, false));
				seen.add(item.id);
			}
		}
		Task t = task == null ? prev.task : task;
		String msg;
		boolean err;
		if (message != null) {
			msg = message;
			err = error;
		} else if (task != null && task != Task.NONE) {
			msg = null;
			err = false;
		} else {
			msg = prev.message;
			err = prev.error;
		}
		version++;
		state.set(new State(Collections.unmodifiableList(skins), doc,
				mojangCapes == null ? null : Collections.unmodifiableList(new ArrayList<Cape>(mojangCapes)),
				trsCapes == null ? null : Collections.unmodifiableList(new ArrayList<Cape>(trsCapes)), activeMojangCape,
				activeTrsCape, Collections.unmodifiableMap(new LinkedHashMap<String, CapeArt>(capeArt)), activeLook, t, msg,
				new Object[0], err, lastTrs || platform.trsToken() != null, lastSession, added != null ? added : prev.added,
				prev.version + 1));
	}

	private int[] pixels(String id) {
		int[] px = pixelCache.get(id);
		if (px != null) return px;
		try {
			px = SkinFiles.decode(library.png(id)).pixels;
			pixelCache.put(id, px);
			return px;
		} catch (IOException | SkinFiles.SkinException e) {
			return null;
		}
	}

	private int[] launcherPixels(LauncherLibrary.Item item) {
		String key = "launcher:" + item.id;
		int[] px = pixelCache.get(key);
		if (px != null) return px;
		try {
			if (Files.size(item.file) > SkinFiles.MAX_BYTES) return null;
			px = SkinFiles.decode(Files.readAllBytes(item.file)).pixels;
			pixelCache.put(key, px);
			return px;
		} catch (IOException | SkinFiles.SkinException e) {
			return null;
		}
	}

	private LauncherLibrary.Item launcherItem(String id) {
		for (LauncherLibrary.Item i : launcherItems) if (i.id.equals(id)) return i;
		return null;
	}

	/** Vergleichswert: Pixel so, wie Minecraft sie zeigt (Grundebene deckend). */
	static int lookHash(int[] px) {
		if (px == null) return 0;
		int[] n = SkinImage.normalize(64, 64, px);
		int h = Arrays.hashCode(n);
		return h == 0 ? 1 : h;
	}

	private static String pathOf(String url) {
		try {
			String p = new java.net.URI(url).getPath();
			return p == null ? "" : p;
		} catch (Exception e) {
			return "";
		}
	}
}
