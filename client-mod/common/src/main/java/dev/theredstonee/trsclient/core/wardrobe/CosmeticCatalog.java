package dev.theredstonee.trsclient.core.wardrobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.cosmetic.CosmeticModel;
import dev.theredstonee.trsclient.core.cosmetic.CosmeticModels;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Cache;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.HatInfo;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import dev.theredstonee.trsclient.core.online.TrsApi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kopf-Kosmetik für die Garderobe (Reiter „Kosmetik“): Katalog {@code GET /v1/cosmetics} (Slots {@code hat} und
 * {@code companion}; Format 2 und – wenn besessen – die Quietscheente), Vorschaubilder der Karten (Tag/Nacht), Modell +
 * Texturen zum Anprobieren, Aufsetzen/Absetzen über {@code PUT /v1/me/cosmetics} und „Gratis holen“ über
 * {@code POST /v1/me/cosmetics/{id}/claim}. Alles im eigenen Hintergrund-Thread „TRS-Kosmetik“; die Oberfläche liest nur
 * den unveränderlichen {@link State}.
 */
public final class CosmeticCatalog {
	/** Kartenbilder werden auf höchstens diese Kante verkleinert (Speicher, Upload). */
	static final int CARD_EDGE = 160;
	static final int MAX_CARD_BYTES = 2 * 1024 * 1024;
	static final int MAX_ITEMS = 64;

	/** Ein Teil im Katalog. */
	public static final class Item {
		public final String id;
		public final String name;
		/** 1 (Vorlage, z. B. Ente) oder 2 (Studio-Modell). */
		public final int format;
		/** {@code free}, {@code code}, {@code admin}, {@code achievement}, {@code event} … */
		public final String unlock;
		public final boolean owned;
		public final boolean hidden;
		/** {@code hat} oder {@code companion}. */
		public final String slot;
		/** Event-ID, wenn {@link #unlock} {@code event} ist (z. B. {@code halloween}), sonst null. */
		public final String event;
		/** Format 2: Adressen für die Vorschau; Format 1: null. */
		public final HatInfo hat;
		/** Format 1: Vorlage + Textur. */
		public final String template;
		public final String textureUrl;
		/** Format 1: Adresse von {@code template.json} (absolut) oder null → fester Pfad. */
		public final String templateUrl;
		public final String cardUrl;
		public final String cardNightUrl;
		/** Abschaltbare Teile laut Katalog ({@code options}, Anhang D); leer, wenn die API sie nicht mitschickt. */
		public final List<OptionInfo> options;

		Item(String id, String name, int format, String unlock, boolean owned, boolean hidden, String slot, String event,
				HatInfo hat, String template, String textureUrl, String templateUrl, String cardUrl, String cardNightUrl) {
			this(id, name, format, unlock, owned, hidden, slot, event, hat, template, textureUrl, templateUrl, cardUrl, cardNightUrl,
					Collections.<OptionInfo>emptyList());
		}

		Item(String id, String name, int format, String unlock, boolean owned, boolean hidden, String slot, String event,
				HatInfo hat, String template, String textureUrl, String templateUrl, String cardUrl, String cardNightUrl,
				List<OptionInfo> options) {
			this.options = options;
			this.id = id;
			this.name = name;
			this.format = format;
			this.unlock = unlock;
			this.owned = owned;
			this.hidden = hidden;
			this.slot = slot;
			this.event = event;
			this.hat = hat;
			this.template = template;
			this.textureUrl = textureUrl;
			this.templateUrl = templateUrl;
			this.cardUrl = cardUrl;
			this.cardNightUrl = cardNightUrl;
		}

		/** Event-Teil, das man während dieses Events abholen kann? */
		public boolean eventClaim() {
			return "event".equals(unlock) && (event == null || "halloween".equals(event));
		}

		/** Belohnung aus „Süßes oder Saures“ ({@code unlock: "reward"}) – nicht abholbar, nicht per Code. */
		public boolean reward() {
			return "reward".equals(unlock);
		}

		/** Gesperrt (nicht besessen)? */
		public boolean locked() {
			return !owned;
		}
	}

	/** Ein abschaltbares Teil (Anhang D, z. B. {@code beard}): ID, Standard und Anzeigename je Sprache. */
	public static final class OptionInfo {
		public final String id;
		public final boolean defaultOn;
		public final Map<String, String> names;

		public OptionInfo(String id, boolean defaultOn, Map<String, String> names) {
			this.id = id;
			this.defaultOn = defaultOn;
			this.names = names == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(names);
		}

		/** Name in der Sprache {@code lang} ({@code de}, {@code pt-BR} …), sonst Englisch, sonst null. */
		public String name(String lang) {
			String n = lang == null ? null : names.get(lang);
			if (n == null && lang != null && lang.indexOf('-') > 0) n = names.get(lang.substring(0, lang.indexOf('-')));
			if (n == null) n = names.get("en");
			return n == null || n.trim().isEmpty() ? null : n;
		}
	}

	/** Ein Bild (ARGB). */
	public static final class Art {
		public final int[] argb;
		public final int width;
		public final int height;

		Art(int[] argb, int width, int height) {
			this.argb = argb;
			this.width = width;
			this.height = height;
		}
	}

	/** Was die 3D-Vorschau braucht. */
	public static final class Preview {
		/** Format 2 (sonst null). */
		public final CosmeticV2Cache.Loaded v2;
		/** Format 1: Vorlage + erstes Texturbild. */
		public final CosmeticModel v1;
		public final int[] v1Pixels;
		public final int v1Width;
		public final int v1Height;

		Preview(CosmeticV2Cache.Loaded v2, CosmeticModel v1, int[] v1Pixels, int v1Width, int v1Height) {
			this.v2 = v2;
			this.v1 = v1;
			this.v1Pixels = v1Pixels;
			this.v1Width = v1Width;
			this.v1Height = v1Height;
		}
	}

	public static final class State {
		public final List<Item> items;
		/** Getragene Kopf-Kosmetik (ID) oder null. */
		public final String equipped;
		/** Getragener Begleiter (ID) oder null. */
		public final String equippedCompanion;
		/** Getragene Flügel (ID) oder null. */
		public final String equippedWings;
		/** Getragener Begleiter am Boden (ID) oder null. */
		public final String equippedPet;
		/** Wahl der abschaltbaren Teile je Fach ({@code slot → optionId → an}); fehlt ein Eintrag, gilt der Standard. */
		public final Map<String, Map<String, Boolean>> options;
		/** Anzahl der Begleiter am Boden (1–4, Creator-Stufe) laut {@code GET /v1/me/cosmetics}; 0 = unbekannt. */
		public final int petCount;
		/** Größe des getragenen Begleiters am Boden (1 = normal) laut {@code GET /v1/me/cosmetics}. */
		public final double petScale;
		/**
		 * Gefolge des getragenen Begleiters am Boden ({@code equipped.pet.extras}, je mit eigener
		 * {@link HatInfo#petScale}); nie null, leer ohne Gefolge oder bei älteren APIs. Die Teile müssen nicht im Katalog
		 * stehen – {@link #previews} hat sie unter ihrer ID, sobald geladen ({@link CosmeticCatalog#wantPreview}).
		 */
		public final List<HatInfo> petExtras;
		public final boolean loaded;
		public final boolean loading;
		/** Mit der TRS API verbunden (angemeldet)? */
		public final boolean trs;
		/** Gerade aufsetzen/absetzen (ID bzw. „-“ fürs Absetzen) oder null. */
		public final String busy;
		/** i18n-Schlüssel der letzten Meldung oder null. */
		public final String message;
		public final boolean error;
		public final int version;
		public final Map<String, Art> cards;
		public final Map<String, Preview> previews;
		/** IDs, deren Vorschau nicht ladbar war. */
		public final Set<String> failed;

		State(List<Item> items, String equipped, String equippedCompanion, boolean loaded, boolean loading, boolean trs,
				String busy, String message, boolean error, int version, Map<String, Art> cards, Map<String, Preview> previews,
				Set<String> failed) {
			this(items, equipped, equippedCompanion, null, loaded, loading, trs, busy, message, error, version, cards, previews,
					failed);
		}

		State(List<Item> items, String equipped, String equippedCompanion, String equippedWings, boolean loaded,
				boolean loading, boolean trs, String busy, String message, boolean error, int version, Map<String, Art> cards,
				Map<String, Preview> previews, Set<String> failed) {
			this(items, equipped, equippedCompanion, equippedWings, null, loaded, loading, trs, busy, message, error, version, cards,
					previews, failed, Collections.<String, Map<String, Boolean>>emptyMap(), 0);
		}

		State(List<Item> items, String equipped, String equippedCompanion, String equippedWings, String equippedPet,
				boolean loaded, boolean loading, boolean trs, String busy, String message, boolean error, int version,
				Map<String, Art> cards, Map<String, Preview> previews, Set<String> failed, Map<String, Map<String, Boolean>> options,
				int petCount) {
			this(items, equipped, equippedCompanion, equippedWings, equippedPet, loaded, loading, trs, busy, message, error, version,
					cards, previews, failed, options, petCount, 1.0, Collections.<HatInfo>emptyList());
		}

		State(List<Item> items, String equipped, String equippedCompanion, String equippedWings, String equippedPet,
				boolean loaded, boolean loading, boolean trs, String busy, String message, boolean error, int version,
				Map<String, Art> cards, Map<String, Preview> previews, Set<String> failed, Map<String, Map<String, Boolean>> options,
				int petCount, double petScale, List<HatInfo> petExtras) {
			this.petScale = petScale;
			this.petExtras = petExtras == null ? Collections.<HatInfo>emptyList() : petExtras;
			this.items = items;
			this.equipped = equipped;
			this.equippedCompanion = equippedCompanion;
			this.equippedWings = equippedWings;
			this.equippedPet = equippedPet;
			this.options = options;
			this.petCount = petCount;
			this.loaded = loaded;
			this.loading = loading;
			this.trs = trs;
			this.busy = busy;
			this.message = message;
			this.error = error;
			this.version = version;
			this.cards = cards;
			this.previews = previews;
			this.failed = failed;
		}

		/** Getragenes Teil im Fach {@code slot} ({@code hat}, {@code companion}, {@code wings}) oder null. */
		public String equipped(String slot) {
			if ("companion".equals(slot)) return equippedCompanion;
			if ("wings".equals(slot)) return equippedWings;
			if ("pet".equals(slot)) return equippedPet;
			return equipped;
		}

		/** Ist die Option {@code optionId} im Fach {@code slot} an? {@code fallback} = Standard des Modells. */
		public boolean option(String slot, String optionId, boolean fallback) {
			Map<String, Boolean> m = options.get(slot);
			Boolean b = m == null ? null : m.get(optionId);
			return b == null ? fallback : b.booleanValue();
		}

		/** Trägt der Spieler dieses Teil gerade (im passenden Fach)? */
		public boolean worn(Item it) {
			return it != null && it.id.equals(equipped(it.slot));
		}

		public Item item(String id) {
			if (id == null) return null;
			for (Item i : items) if (i.id.equals(id)) return i;
			return null;
		}

		/** Teil des Gefolges mit dieser ID (Format 2) oder null. */
		public HatInfo petExtra(String id) {
			if (id == null) return null;
			for (HatInfo h : petExtras) if (h.id.equals(id)) return h;
			return null;
		}

		/** Kartenbild (Tag oder Nacht) oder null. */
		public Art card(String id, boolean night) {
			Art a = cards.get(id + (night ? "#n" : "#d"));
			return a != null ? a : cards.get(id + "#d");
		}
	}

	private static volatile CosmeticCatalog instance;

	private final WardrobeService.Platform platform;
	private final Http http;
	private final ExecutorService worker;
	private final AtomicReference<State> state = new AtomicReference<State>();

	// --- nur Hintergrund-Thread ---
	private List<Item> items = Collections.emptyList();
	private String equipped;
	private String equippedCompanion;
	private String equippedWings;
	private String equippedPet;
	/** Wahl der Optionen je Fach (nur Hintergrund-Thread). */
	private final Map<String, Map<String, Boolean>> choices = new LinkedHashMap<String, Map<String, Boolean>>();
	private int petCount;
	private double petScale = 1.0;
	private List<HatInfo> petExtras = Collections.emptyList();
	private boolean loaded;
	private boolean loadingFlag;
	private boolean trs;
	private String busy;
	private String message;
	private boolean error;
	private int version;
	private final Map<String, Art> cards = new LinkedHashMap<String, Art>();
	private final Map<String, Preview> previews = new LinkedHashMap<String, Preview>();
	private final Set<String> failed = new HashSet<String>();
	/** Angefragte Vorschauen (Render-Thread schreibt, Hintergrund liest – nur über submit). */
	private final Set<String> previewRequested = Collections.synchronizedSet(new HashSet<String>());
	private volatile long lastRefresh;

	CosmeticCatalog(WardrobeService.Platform platform, Http http, ExecutorService worker) {
		this.platform = platform;
		this.http = http;
		this.worker = worker;
		publish();
	}

	/** Gemeinsame Instanz (beim ersten Öffnen des Reiters angelegt). */
	public static CosmeticCatalog shared(WardrobeService.Platform platform) {
		CosmeticCatalog c = instance;
		if (c != null) return c;
		synchronized (CosmeticCatalog.class) {
			if (instance == null) {
				ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "TRS-Kosmetik");
					t.setDaemon(true);
					t.setPriority(Thread.MIN_PRIORITY + 1);
					return t;
				});
				instance = new CosmeticCatalog(platform, new Http.UrlConnection(platform.userAgent()), worker);
			}
			return instance;
		}
	}

	/** Nur für Tests. */
	static CosmeticCatalog create(WardrobeService.Platform platform, Http http, ExecutorService worker) {
		return new CosmeticCatalog(platform, http, worker);
	}

	public State state() {
		return state.get();
	}

	// ====================================================================================================
	// Aufrufe aus der Oberfläche
	// ====================================================================================================

	/** Katalog (neu) laden – höchstens alle 5 s, außer {@code force}. */
	public void refresh(boolean force) {
		long now = System.currentTimeMillis();
		if (!force && now - lastRefresh < 5000) return;
		lastRefresh = now;
		submit(() -> {
			loadingFlag = true;
			publish();
			try {
				load();
			} finally {
				loadingFlag = false;
				publish();
			}
		});
	}

	/** Modell + Texturen eines Teils für die Vorschau laden (einmal). */
	public void wantPreview(final String id) {
		if (id == null || !previewRequested.add(id)) return;
		submit(() -> loadPreview(id));
	}

	/** Aufsetzen ({@code id}) oder absetzen (null). */
	public void wear(final String id) {
		wearIn("hat", id);
	}

	/** Begleiter aufsetzen ({@code id}) oder absetzen (null). Der Hut bleibt, wie er ist. */
	public void wearCompanion(final String id) {
		wearIn("companion", id);
	}

	/** Flügel anlegen ({@code id}) oder ablegen (null). Hut und Begleiter bleiben, wie sie sind. */
	public void wearWings(final String id) {
		wearIn("wings", id);
	}

	/** Begleiter am Boden (Pinguin) mitnehmen ({@code id}) oder zurücklassen (null). Die anderen Fächer bleiben. */
	public void wearPet(final String id) {
		wearIn("pet", id);
	}

	/** Teil im passenden Fach anlegen bzw. das Fach leeren ({@code id} null). */
	public void wearSlot(String slot, String id) {
		if ("companion".equals(slot) || "wings".equals(slot) || "pet".equals(slot)) wearIn(slot, id);
		else wearIn("hat", id);
	}

	/** {@code PUT /v1/me/cosmetics {<slot>: id|null}} – ein Fach, die anderen bleiben, wie sie sind. */
	private void wearIn(final String slot, final String id) {
		State s = state.get();
		if (s.busy != null) return;
		submit(() -> {
			String token = platform.trsToken();
			if (token == null) {
				fail("wardrobe.cosmetics.error.offline");
				return;
			}
			busy = id == null ? "-" : id;
			message = null;
			publish();
			try {
				String reply = api().setSlot(token, slot, id);
				setEquipped(slot, id);
				readMine(reply);
				message = id == null ? "wardrobe.cosmetics.takenOff" : "wardrobe.cosmetics.putOn";
				error = false;
				// eigenen Lookup auffrischen, damit man es im Spiel sofort trägt
				platform.trsCapeChanged();
			} catch (ApiException e) {
				if (e.unauthorized()) platform.trsTokenRejected(token);
				message = cosmeticError(e);
				error = true;
			} catch (IOException e) {
				message = "wardrobe.cosmetics.error.offline";
				error = true;
			} finally {
				busy = null;
				version++;
				publish();
			}
		});
	}

	private void setEquipped(String slot, String id) {
		if ("companion".equals(slot)) equippedCompanion = id;
		else if ("wings".equals(slot)) equippedWings = id;
		else if ("pet".equals(slot)) {
			if (id == null || !id.equals(equippedPet)) {
				// anderes Teil oder abgelegt: Gefolge kommt mit der Antwort neu
				petExtras = Collections.emptyList();
				petScale = 1.0;
			}
			equippedPet = id;
		} else equipped = id;
	}

	/**
	 * Abschaltbares Teil des getragenen Teils im Fach {@code slot} an-/ausschalten (z. B. Bart der Weihnachtsmütze):
	 * {@code PUT /v1/me/cosmetics {options: {<slot>: {...}}}}. {@code declared} = alle Optionen des Modells (nur die
	 * werden geschickt, jeweils mit der aktuellen Wahl bzw. dem Standard).
	 */
	public void setOption(final String slot, final String optionId, final boolean on, final List<OptionInfo> declared) {
		if (slot == null || optionId == null || declared == null || declared.isEmpty()) return;
		State s = state.get();
		if (s.busy != null) return;
		submit(() -> {
			String token = platform.trsToken();
			if (token == null) {
				fail("wardrobe.cosmetics.error.offline");
				return;
			}
			Map<String, Boolean> current = choices.get(slot);
			Map<String, Boolean> send = new LinkedHashMap<String, Boolean>();
			for (OptionInfo o : declared) {
				Boolean was = current == null ? null : current.get(o.id);
				send.put(o.id, o.id.equals(optionId) ? on : (was == null ? o.defaultOn : was));
			}
			busy = optionId;
			message = null;
			publish();
			try {
				String reply = api().setOptions(token, slot, send);
				choices.put(slot, send);
				readMine(reply);
				message = "wardrobe.cosmetics.optionSaved";
				error = false;
				platform.trsCapeChanged();
			} catch (ApiException e) {
				if (e.unauthorized()) platform.trsTokenRejected(token);
				message = cosmeticError(e);
				error = true;
			} catch (IOException e) {
				message = "wardrobe.cosmetics.error.offline";
				error = true;
			} finally {
				busy = null;
				version++;
				publish();
			}
		});
	}

	/** Event-Teil abholen ({@code POST /v1/me/cosmetics/{id}/claim}) und den Katalog neu lesen. */
	public void claim(final String id) {
		if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,39}")) return;
		State s = state.get();
		if (s.busy != null) return;
		submit(() -> {
			String token = platform.trsToken();
			if (token == null) {
				fail("wardrobe.cosmetics.error.offline");
				return;
			}
			busy = id;
			message = null;
			publish();
			try {
				api().claimCosmetic(token, id);
				message = "wardrobe.cosmetics.claimed";
				error = false;
				load();
			} catch (ApiException e) {
				if (e.unauthorized()) platform.trsTokenRejected(token);
				message = cosmeticError(e);
				error = true;
			} catch (IOException e) {
				message = "wardrobe.cosmetics.error.offline";
				error = true;
			} finally {
				busy = null;
				version++;
				publish();
			}
		});
	}

	private static String cosmeticError(ApiException e) {
		String code = e.code();
		if ("cosmetic_locked".equals(code)) return "wardrobe.cosmetics.error.locked";
		if ("cosmetic_not_found".equals(code)) return "wardrobe.cosmetics.error.notFound";
		if ("event_inactive".equals(code)) return "wardrobe.cosmetics.error.eventInactive";
		if ("not_claimable".equals(code)) return "wardrobe.cosmetics.error.notClaimable";
		return "wardrobe.cosmetics.error.generic";
	}

	// ====================================================================================================
	// Hintergrund
	// ====================================================================================================

	private WardrobeApi api() {
		return new WardrobeApi(http, platform.apiBase());
	}

	private TrsApi trsApi() {
		return new TrsApi(http, config());
	}

	private OnlineConfig config() {
		return new OnlineConfig(true, platform.apiBase(), OnlineConfig.DEFAULT_SESSION);
	}

	private void load() {
		String token = platform.trsToken();
		trs = token != null;
		if (token == null) {
			publish();
			return;
		}
		String raw;
		try {
			raw = api().cosmetics(token);
		} catch (ApiException e) {
			if (e.unauthorized()) platform.trsTokenRejected(token);
			fail("wardrobe.cosmetics.error.generic");
			return;
		} catch (IOException e) {
			fail("wardrobe.cosmetics.error.offline");
			return;
		}
		List<Item> parsed = new ArrayList<Item>();
		String eq = null;
		String eqCompanion = null;
		String eqWings = null;
		String eqPet = null;
		try {
			JsonElement root = new JsonParser().parse(raw);
			JsonArray list = root.isJsonObject() && root.getAsJsonObject().get("cosmetics") != null
					&& root.getAsJsonObject().get("cosmetics").isJsonArray() ? root.getAsJsonObject().getAsJsonArray("cosmetics") : null;
			if (list != null) {
				OnlineConfig cfg = config();
				for (JsonElement e : list) {
					if (!e.isJsonObject() || parsed.size() >= MAX_ITEMS) continue;
					Item it = parse(e.getAsJsonObject(), cfg);
					if (it == null) continue;
					parsed.add(it);
					if (bool(e.getAsJsonObject(), "equipped")) {
						if ("companion".equals(it.slot)) eqCompanion = it.id;
						else if ("wings".equals(it.slot)) eqWings = it.id;
						else if ("pet".equals(it.slot)) eqPet = it.id;
						else eq = it.id;
					}
				}
			}
		} catch (RuntimeException e) {
			fail("wardrobe.cosmetics.error.generic");
			return;
		}
		items = Collections.unmodifiableList(parsed);
		equipped = eq;
		equippedCompanion = eqCompanion;
		equippedWings = eqWings;
		equippedPet = eqPet;
		// Wahl der Optionen (Bart an/aus) und Anzahl der Pinguine – fehlt das bei älteren APIs, bleibt der Standard.
		try {
			readMine(api().myCosmetics(token));
		} catch (ApiException | IOException | RuntimeException ignored) {
			// ohne Optionen weiter
		}
		loaded = true;
		publish();
		// Kartenbilder nachladen (Tag + Nacht)
		TrsApi api = trsApi();
		for (Item it : parsed) {
			loadCard(api, it.id + "#d", it.cardUrl, token);
			loadCard(api, it.id + "#n", it.cardNightUrl, token);
		}
	}

	/** Ein Katalog-Eintrag; nur Hut oder Begleiter, die diese Mod zeichnen kann. */
	static Item parse(JsonObject o, OnlineConfig cfg) {
		String id = str(o, "id");
		if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,39}")) return null;
		String slot = str(o, "slot");
		if (slot == null) slot = "hat";
		if (!"hat".equals(slot) && !"companion".equals(slot) && !"wings".equals(slot) && !"pet".equals(slot)) return null;
		String name = SkinFiles.cleanName(str(o, "name"), id);
		String unlock;
		String event = null;
		JsonElement rawUnlock = o.get("unlock");
		if (rawUnlock != null && rawUnlock.isJsonObject()) {
			JsonObject u = rawUnlock.getAsJsonObject();
			unlock = str(u, "type");
			event = str(u, "event");
		} else {
			unlock = str(o, "unlock");
			event = str(o, "event");
		}
		if (unlock == null || !unlock.matches("[a-z_]{1,24}")) unlock = "code";
		if (event != null && !event.matches("[a-z0-9_-]{1,32}")) event = null;
		boolean owned = bool(o, "owned");
		boolean hidden = bool(o, "hidden");
		// Versteckte nur, wenn besessen (die API liefert sie sonst ohnehin nicht).
		if (hidden && !owned) return null;
		int format = num(o, "format", 1);
		String textureUrl = null;
		JsonElement tex = o.get("texture");
		if (tex != null && tex.isJsonPrimitive()) textureUrl = tex.getAsString();
		else if (tex != null && tex.isJsonObject()) textureUrl = str(tex.getAsJsonObject(), "url");
		String card = resolve(str(o, "card"), cfg);
		String cardNight = resolve(str(o, "cardNight"), cfg);
		if (("companion".equals(slot) || "wings".equals(slot) || "pet".equals(slot)) && format != 2) return null;
		if (format == 2) {
			HatInfo hat = HatInfo.v2(id, str(o, "model"), textureUrl, str(o, "glow"), str(o, "hash"),
					integer(o, "frames"), integer(o, "glowFrames"), cfg);
			if (hat == null) return null;
			return new Item(id, name, 2, unlock, owned, hidden, slot, event, hat, null, null, null, card, cardNight,
					options(o.get("options")));
		}
		String template = str(o, "template");
		if (template == null || !CosmeticModels.supported(template) || !owned) return null;
		String url = resolve(textureUrl, cfg);
		if (url == null) return null;
		return new Item(id, name, 1, unlock, owned, hidden, slot, event, null, template, url, resolve(str(o, "templateUrl"), cfg), card,
				cardNight);
	}

	/** {@code options} eines Katalog-Eintrags ({@code [{id, default, name: {de, en, …}}]}, höchstens 4); sonst leer. */
	static List<OptionInfo> options(JsonElement raw) {
		if (raw == null || !raw.isJsonArray()) return Collections.emptyList();
		List<OptionInfo> out = new ArrayList<OptionInfo>();
		for (JsonElement e : raw.getAsJsonArray()) {
			if (!e.isJsonObject() || out.size() >= 4) continue;
			JsonObject o = e.getAsJsonObject();
			String oid = str(o, "id");
			if (oid == null || !oid.matches("[a-z][a-z0-9_]{0,19}")) continue;
			JsonElement def = o.get("default");
			boolean on = def == null || !def.isJsonPrimitive() || !def.getAsJsonPrimitive().isBoolean() || def.getAsBoolean();
			Map<String, String> names = new LinkedHashMap<String, String>();
			JsonElement n = o.get("name");
			if (n != null && n.isJsonObject()) {
				for (Map.Entry<String, JsonElement> ne : n.getAsJsonObject().entrySet()) {
					JsonElement v = ne.getValue();
					if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && v.getAsString().length() <= 40) {
						names.put(ne.getKey(), v.getAsString());
					}
				}
			} else if (n != null && n.isJsonPrimitive() && n.getAsJsonPrimitive().isString() && n.getAsString().length() <= 40) {
				names.put("en", n.getAsString());
			}
			out.add(new OptionInfo(oid, on, names));
		}
		return Collections.unmodifiableList(out);
	}

	/**
	 * Optionen eines Teils: aus dem geladenen Modell (maßgeblich, wenn die Vorschau da ist), sonst laut Katalog. Leer =
	 * nichts abschaltbar.
	 */
	public static List<OptionInfo> optionsOf(State s, Item it) {
		if (it == null) return Collections.emptyList();
		Preview p = s == null ? null : s.previews.get(it.id);
		if (p != null && p.v2 != null && !p.v2.model.options.isEmpty()) {
			List<OptionInfo> out = new ArrayList<OptionInfo>();
			for (dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2.Option o : p.v2.model.options) {
				out.add(new OptionInfo(o.id, o.defaultOn, o.names));
			}
			return out;
		}
		return it.options;
	}

	private static String resolve(String url, OnlineConfig cfg) {
		if (url == null || url.isEmpty() || url.length() >= 512) return null;
		String abs = url.startsWith("/v1/") ? cfg.apiBase() + url : url;
		return cfg.isApiUrl(abs) ? abs : null;
	}

	private void loadCard(TrsApi api, String key, String url, String token) {
		if (url == null || cards.containsKey(key)) return;
		try {
			PngDecoder.Image img = PngDecoder.decode(api.asset(url, "image/png", MAX_CARD_BYTES, token));
			cards.put(key, shrink(img));
			version++;
			publish();
		} catch (Exception ignored) {
			// ohne Bild (Karte zeigt ein Symbol)
		}
	}

	/** Auf höchstens {@link #CARD_EDGE} verkleinern (Mittelwert je Block, Alpha-gewichtet). */
	static Art shrink(PngDecoder.Image img) {
		int w = img.width;
		int h = img.height;
		int f = 1;
		while (w / f > CARD_EDGE || h / f > CARD_EDGE) f++;
		if (f == 1) return new Art(img.argb, w, h);
		int nw = w / f;
		int nh = h / f;
		int[] out = new int[nw * nh];
		for (int y = 0; y < nh; y++) {
			for (int x = 0; x < nw; x++) {
				long a = 0, r = 0, g = 0, b = 0;
				for (int dy = 0; dy < f; dy++) {
					for (int dx = 0; dx < f; dx++) {
						int p = img.argb[(y * f + dy) * w + x * f + dx];
						int pa = p >>> 24;
						a += pa;
						r += ((p >> 16) & 0xFF) * pa;
						g += ((p >> 8) & 0xFF) * pa;
						b += (p & 0xFF) * pa;
					}
				}
				int n = f * f;
				int oa = (int) (a / n);
				out[y * nw + x] = a == 0 ? 0 : (oa << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
			}
		}
		return new Art(out, nw, nh);
	}

	private void loadPreview(String id) {
		State s = state.get();
		Item it = s.item(id);
		HatInfo extra = it == null ? s.petExtra(id) : null;
		if (it == null && extra == null) {
			previewRequested.remove(id);
			return;
		}
		String token = platform.trsToken();
		try {
			Preview p;
			if (extra != null) {
				// Gefolge (muss nicht im Katalog stehen)
				CosmeticV2Cache cache = new CosmeticV2Cache(platform.configDir().resolve("trsclient").resolve("cosmetics"));
				p = new Preview(cache.load(extra, trsApi(), token), null, null, 0, 0);
			} else if (it.format == 2) {
				CosmeticV2Cache cache = new CosmeticV2Cache(platform.configDir().resolve("trsclient").resolve("cosmetics"));
				p = new Preview(cache.load(it.hat, trsApi(), token), null, null, 0, 0);
			} else {
				final String tokenFinal = token;
				CosmeticModel model = CosmeticModels.load(
						platform.configDir().resolve("trsclient").resolve("cosmetics").resolve("templates"), it.id,
						it.template, it.templateUrl, config(), url -> {
							try {
								return trsApi().asset(url, "application/json", CosmeticModels.MAX_BYTES, tokenFinal);
							} catch (ApiException e) {
								throw new IOException(e.code());
							}
						});
				if (model == null) throw new IOException("Vorlage");
				PngDecoder.Image img = PngDecoder.decode(trsApi().asset(it.textureUrl, "image/png", TrsApi.MAX_TEXTURE_BYTES, token));
				int fw = img.width;
				int fh = Math.min(img.height, Math.max(1, fw / 2));
				int[] px = new int[fw * fh];
				System.arraycopy(img.argb, 0, px, 0, px.length);
				p = new Preview(null, model, px, fw, fh);
			}
			previews.put(id, p);
		} catch (Exception e) {
			platform.log("Garderobe: Kosmetik-Vorschau '" + id + "' nicht ladbar (" + e.getMessage() + ")");
			failed.add(id);
		}
		version++;
		publish();
	}

	private void fail(String key) {
		message = key;
		error = true;
		version++;
		publish();
	}

	private void publish() {
		Map<String, Map<String, Boolean>> opts = new LinkedHashMap<String, Map<String, Boolean>>();
		for (Map.Entry<String, Map<String, Boolean>> e : choices.entrySet()) {
			opts.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<String, Boolean>(e.getValue())));
		}
		state.set(new State(items, equipped, equippedCompanion, equippedWings, equippedPet, loaded, loadingFlag, trs, busy, message,
				error, version, Collections.unmodifiableMap(new LinkedHashMap<String, Art>(cards)),
				Collections.unmodifiableMap(new LinkedHashMap<String, Preview>(previews)),
				Collections.unmodifiableSet(new HashSet<String>(failed)), Collections.unmodifiableMap(opts), petCount, petScale,
				petExtras));
	}

	/**
	 * Antwort von {@code GET/PUT /v1/me/cosmetics} lesen: Wahl der Optionen je Fach ({@code equipped.<slot>.options}
	 * bzw. {@code options.<slot>}) und Anzahl der Begleiter am Boden ({@code equipped.pet.count}). Unbekanntes bleibt.
	 */
	void readMine(String raw) {
		Mine m = parseMine(raw, config());
		if (m == null) return;
		choices.putAll(m.options);
		if (m.petCount >= 0) petCount = m.petCount;
		if (m.petCount == 0) {
			petScale = 1.0;
			petExtras = Collections.emptyList();
		} else if (m.petCount > 0) {
			petScale = m.petScale;
			petExtras = m.petExtras;
		}
	}

	/** Gelesene Antwort von {@code /v1/me/cosmetics}: Optionen je Fach und Anzahl der Begleiter am Boden. */
	static final class Mine {
		final Map<String, Map<String, Boolean>> options = new LinkedHashMap<String, Map<String, Boolean>>();
		/** −1 = unbekannt (ältere API), 0 = kein Begleiter am Boden, sonst 1–4. */
		int petCount = -1;
		/** Größe des Begleiters am Boden ({@code petScale}; Standard 1). */
		double petScale = 1.0;
		/** Gefolge ({@code equipped.pet.extras}, Format 2, höchstens {@link HatInfo#MAX_EXTRAS}); leer, wenn keins. */
		List<HatInfo> petExtras = Collections.emptyList();
	}

	/** Antwort lesen (ohne Gefolge, dafür braucht es die API-Adresse); null, wenn kein JSON-Objekt. */
	static Mine parseMine(String raw) {
		return parseMine(raw, null);
	}

	/** Antwort lesen; {@code cfg} = API (für die Adressen des Gefolges; null = Gefolge überspringen). */
	static Mine parseMine(String raw, OnlineConfig cfg) {
		if (raw == null) return null;
		JsonElement root;
		try {
			root = new JsonParser().parse(raw);
		} catch (RuntimeException e) {
			return null;
		}
		if (root == null || !root.isJsonObject()) return null;
		Mine mine = new Mine();
		Map<String, Map<String, Boolean>> choices = mine.options;
		JsonObject r = root.getAsJsonObject();
		JsonObject eq = r.get("equipped") != null && r.get("equipped").isJsonObject() ? r.getAsJsonObject("equipped") : null;
		JsonObject top = r.get("options") != null && r.get("options").isJsonObject() ? r.getAsJsonObject("options") : null;
		for (String slot : SLOTS) {
			JsonElement piece = eq == null ? null : eq.get(slot);
			JsonObject o = piece != null && piece.isJsonObject() ? piece.getAsJsonObject() : null;
			JsonElement opt = o != null ? o.get("options") : null;
			if ((opt == null || !opt.isJsonObject()) && top != null) opt = top.get(slot);
			if (eq != null && eq.has(slot)) {
				Map<String, Boolean> m = new LinkedHashMap<String, Boolean>();
				if (opt != null && opt.isJsonObject()) {
					for (Map.Entry<String, JsonElement> e : opt.getAsJsonObject().entrySet()) {
						JsonElement v = e.getValue();
						if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()
								&& e.getKey().matches("[a-z][a-z0-9_]{0,19}")) {
							m.put(e.getKey(), v.getAsBoolean());
						}
					}
				}
				choices.put(slot, m);
			}
			if ("pet".equals(slot) && o != null) {
				Integer c = integer(o, "count");
				mine.petCount = c == null ? 1 : Math.max(1, Math.min(4, c));
				mine.petScale = petScale(o);
				if (cfg != null) mine.petExtras = petExtras(o.get("extras"), cfg);
			} else if ("pet".equals(slot) && eq != null && eq.has(slot)) {
				mine.petCount = 0;
			}
		}
		return mine;
	}

	/** Fächer, deren Optionen gelesen werden. */
	static final String[] SLOTS = { "hat", "companion", "wings", "pet" };

	/**
	 * Größe eines Begleiters am Boden: {@code petScale} (begrenzt wie {@link HatInfo#clampScale}), sonst 1. {@code scale}
	 * ist der Pixel-Faktor der Textur und keine Größe.
	 */
	static double petScale(JsonObject o) {
		Double d = number(o.get("petScale"));
		return d == null ? 1.0 : HatInfo.clampScale(d);
	}

	private static Double number(JsonElement e) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
		double d = e.getAsDouble();
		return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
	}

	/** Gefolge ({@code extras}): Format-2-Teile mit eigener Größe; kaputte Einträge fallen weg, höchstens 4. */
	static List<HatInfo> petExtras(JsonElement raw, OnlineConfig cfg) {
		if (raw == null || !raw.isJsonArray()) return Collections.emptyList();
		List<HatInfo> out = new ArrayList<HatInfo>();
		for (JsonElement e : raw.getAsJsonArray()) {
			if (out.size() >= HatInfo.MAX_EXTRAS) break;
			if (e == null || !e.isJsonObject()) continue;
			JsonObject x = e.getAsJsonObject();
			try {
				Integer format = integer(x, "format");
				if (format != null && format != 2) continue;
				String tex = str(x, "url");
				JsonElement t = x.get("texture");
				if (tex == null && t != null && t.isJsonObject()) tex = str(t.getAsJsonObject(), "url");
				HatInfo h = HatInfo.v2(str(x, "id"), str(x, "model"), tex, str(x, "glow"), str(x, "hash"), integer(x, "frames"),
						integer(x, "glowFrames"), cfg);
				if (h != null) out.add(h.withPet(petScale(x), null));
			} catch (RuntimeException ignored) {
				// kaputter Eintrag: nur dieser fällt weg
			}
		}
		return out.isEmpty() ? Collections.<HatInfo>emptyList() : Collections.unmodifiableList(out);
	}

	private void submit(Runnable r) {
		try {
			worker.execute(() -> {
				try {
					r.run();
				} catch (RuntimeException e) {
					platform.log("Garderobe: Kosmetik-Fehler " + e);
				}
			});
		} catch (RejectedExecutionException ignored) {
			// beendet
		}
	}

	// --- JSON ---

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	private static boolean bool(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	private static int num(JsonObject o, String k, int fallback) {
		Integer i = integer(o, k);
		return i == null ? fallback : i;
	}

	private static Integer integer(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
		double d = e.getAsDouble();
		return d == Math.rint(d) && Math.abs(d) < 1e6 ? (int) d : null;
	}
}
