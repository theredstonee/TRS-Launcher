package dev.theredstonee.trsclient.core.circuit;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.input.KeyPresses;
import dev.theredstonee.trsclient.core.module.CircuitModules;
import dev.theredstonee.trsclient.core.module.KeySetting;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Steuerung der Schaltungs-Vorlagen im Spiel: Platzieren (Vorlage folgt dem Blick, Drehen per Taste, Bestätigen),
 * Schichten, Ausblenden, Speichern je Welt/Server, Abgleich mit der Welt und das Zeichnen der Geisterblöcke samt
 * Fortschritt („32/40 Blöcke“). Ein Aufruf je Client-Tick ({@link #tick}) und je Bild ({@link #draw}) aus dem Baum.
 *
 * <p>Fair Play: nur Anzeige wie eine Litematica-Vorschau – keine Pakete, kein automatisches Bauen.
 */
public final class Circuits {
	/** Was der Baum liefert. */
	public interface Platform {
		/** Minecraft-Version ("1.8.9", "1.21.11", "26.3"). */
		String minecraftVersion();

		/** 1.8.9–1.12.2 (alte Block-/Gegenstandsnamen)? */
		boolean legacy();

		/** Anzahl je Gegenstand im Inventar (ID ohne Namensraum), leer wenn unbekannt. */
		Map<String, Integer> inventory();

		/** Gegenstand für ein Symbol (ID ohne Namensraum) oder null. */
		Object stack(String itemId);

		/** TRS-Menü öffnen (für die Taste „Bibliothek öffnen“). */
		void openMenu();
	}

	/** Zustand des Spiels in diesem Tick (vom Baum gefüllt). */
	public static final class Context {
		public boolean inWorld;
		public boolean singleplayer;
		public String levelName;
		public String serverAddress;
		public String dimension;
		public boolean screenOpen;
		/** Blick trifft einen Block? */
		public boolean hit;
		public int hitX;
		public int hitY;
		public int hitZ;
		/** Seite des getroffenen Blocks ({@code Dir}), -1 = unbekannt. */
		public int face = -1;
		/** Füße des Spielers. */
		public double x;
		public double y;
		public double z;
		public float yaw;

		/** Schlüssel der aktuellen Welt samt Dimension oder null. */
		public String worldKey() {
			if (!inWorld) return null;
			String base;
			if (singleplayer) base = "sp:" + (levelName == null ? "?" : levelName);
			else base = "mp:" + (serverAddress == null || serverAddress.trim().isEmpty() ? "?" : serverAddress.trim().toLowerCase(java.util.Locale.ROOT));
			return base + "|" + (dimension == null ? "" : dimension);
		}
	}

	private static final Circuits INSTANCE = new Circuits();

	private CircuitModules modules;
	private Platform platform;
	private KeyPresses keys;
	private CircuitStore store;
	private final GhostPainter painter = new GhostPainter();

	private String worldKey;
	private Circuit active;
	private Placement placement;
	private CircuitCheck check;
	private int layer = -1;
	private boolean hidden;
	/** Vorlage folgt dem Blick. */
	private boolean placing;
	private int rotationOffset;
	private boolean mirror;
	/** Vor dem Platzieren eingeblendete Vorlage (Abbrechen stellt sie wieder her). */
	private CircuitStore.Entry beforePlacing;
	private int ticks;
	private boolean dirtyCheck = true;
	private String message;
	private long messageUntil;
	private Map<String, Integer> inventory = Collections.emptyMap();

	private Circuits() {
	}

	public static Circuits get() {
		return INSTANCE;
	}

	/** Einmal beim Start (idempotent). */
	public synchronized void install(dev.theredstonee.trsclient.core.module.TrsModules all, Platform platform,
			KeyPresses.Down keyDown, Path configDir) {
		this.modules = all.circuits;
		this.platform = platform;
		dev.theredstonee.trsclient.core.ui.menu.ModulePanel.Registry.set(modules.circuitLibrary, new CircuitPanel());
		if (keys == null && keyDown != null) keys = new KeyPresses(keyDown);
		if (store == null && configDir != null) {
			store = new CircuitStore(configDir.resolve("trsclient").resolve("circuit-templates.json"));
			store.load();
			// Bibliothek: Cache laden, dann einmal je Start beim Server prüfen (Hintergrund, keine Anmeldung nötig).
			dev.theredstonee.trsclient.core.online.OnlineConfig online = dev.theredstonee.trsclient.core.online.OnlineConfig.load(configDir);
			boolean allowed = online.launcherEnabled() && all.trsOnline.isEnabled();
			String version = platform == null ? "?" : platform.minecraftVersion();
			CircuitSync.startOnce(new dev.theredstonee.trsclient.core.online.Http.UrlConnection("TRS-Client (circuits; Minecraft " + version + ")"),
					allowed ? online.apiBase() : null, new CircuitCache(configDir.resolve("trsclient").resolve("circuits")));
			submissions = new CircuitSubmissions(new dev.theredstonee.trsclient.core.online.Http.UrlConnection(
					"TRS-Client (circuits; Minecraft " + version + ")"), online.apiBase());
		}
	}

	private CircuitSubmissions submissions;

	/** Einreichen + „Meine Einreichungen“ (null, solange nicht gestartet). */
	public CircuitSubmissions submissions() {
		return submissions;
	}

	public boolean installed() {
		return modules != null && platform != null;
	}

	public Platform platform() {
		return platform;
	}

	public CircuitModules modules() {
		return modules;
	}

	// --- Tick ---

	public void tick(CircuitWorld world, Context ctx) {
		if (modules == null) return;
		String key = ctx.worldKey();
		if (key == null) {
			if (placing) cancelPlacing();
			worldKey = null;
			clearActive();
			return;
		}
		if (!key.equals(worldKey)) {
			worldKey = key;
			placing = false;
			loadForWorld();
		}
		ticks++;
		if (ticks % 20 == 0 && platform != null) {
			try {
				Map<String, Integer> inv = platform.inventory();
				inventory = inv == null ? Collections.<String, Integer>emptyMap() : inv;
			} catch (RuntimeException e) {
				inventory = Collections.emptyMap();
			}
		}
		boolean enabled = modules.circuitLibrary.isEnabled();
		if (keys != null) {
			if (ctx.screenOpen || !enabled) {
				keys.releaseAll();
			} else {
				handleKeys(ctx);
			}
		}
		lastWorld = world;
		if (selecting) hover = ctx.hit ? new int[] {ctx.hitX, ctx.hitY, ctx.hitZ} : null;
		if (placing && active != null) {
			Placement p = gazePlacement(active, ctx);
			if (!p.equals(placement)) {
				placement = p;
				check = new CircuitCheck(active, p);
				dirtyCheck = true;
			}
		}
		if (check != null && (dirtyCheck || ticks % 5 == 0)) {
			check.run(world, CircuitLibrary.get().catalog());
			dirtyCheck = false;
		}
	}

	private void handleKeys(Context ctx) {
		if (pressed(modules.openKey) && platform != null) {
			CircuitLibraryPage.requestOpen();
			platform.openMenu();
			return;
		}
		if (selecting) {
			if (pressed(modules.confirmKey) && ctx.hit) {
				int[] p = {ctx.hitX, ctx.hitY, ctx.hitZ};
				if (cornerA == null) {
					cornerA = p;
				} else {
					int[] s = CircuitCapture.size(cornerA[0], cornerA[1], cornerA[2], p[0], p[1], p[2]);
					if (s[0] > Circuit.MAX_SIZE || s[1] > Circuit.MAX_SIZE || s[2] > Circuit.MAX_SIZE) {
						say(I18n.tr("circuits.select.tooBig"));
					} else {
						finishSelecting(p);
					}
				}
			}
			if (pressed(modules.hideKey)) {
				selecting = false;
				cornerA = null;
				CircuitLibraryPage.requestSubmit();
				if (platform != null) platform.openMenu();
			}
			return;
		}
		if (placing) {
			if (pressed(modules.rotateKey)) rotationOffset = (rotationOffset + 1) & 3;
			if (pressed(modules.confirmKey)) confirmPlacing();
			if (pressed(modules.hideKey)) cancelPlacing();
			return;
		}
		if (active == null) return;
		if (pressed(modules.hideKey)) {
			hidden = !hidden;
			say(I18n.tr(hidden ? "circuits.hud.hidden" : "circuits.hud.shown"));
			persist();
		}
		if (!hidden) {
			if (pressed(modules.layerUpKey)) setLayer(layer + 1 >= active.sizeY ? -1 : layer + 1);
			if (pressed(modules.layerDownKey)) setLayer(layer <= -1 ? active.sizeY - 1 : layer - 1);
		}
	}

	private boolean pressed(KeySetting key) {
		return keys.pressed(key);
	}

	/** Lage aus Blick und Blickrichtung: Grundfläche mittig vor dem Spieler, „Norden“ der Schaltung zeigt von ihm weg. */
	Placement gazePlacement(Circuit c, Context ctx) {
		int ax, ay, az;
		if (ctx.hit) {
			ax = ctx.hitX;
			ay = ctx.hitY;
			az = ctx.hitZ;
			if (ctx.face >= 0) {
				ax += dev.theredstonee.trsclient.core.redstone.Dir.dx(ctx.face);
				ay += dev.theredstonee.trsclient.core.redstone.Dir.dy(ctx.face);
				az += dev.theredstonee.trsclient.core.redstone.Dir.dz(ctx.face);
			}
		} else {
			double yaw = Math.toRadians(ctx.yaw);
			ax = (int) Math.floor(ctx.x - Math.sin(yaw) * 4);
			ay = (int) Math.floor(ctx.y);
			az = (int) Math.floor(ctx.z + Math.cos(yaw) * 4);
		}
		int rotation = (rotationFromYaw(ctx.yaw) + rotationOffset) & 3;
		// Grundfläche vor den Spieler schieben (nicht mittig auf ihn)
		Placement p = Placement.centered(c, ax, ay, az, rotation, mirror);
		return p;
	}

	/** Blickrichtung → Vierteldrehung, bei der „Norden“ der Schaltung vom Spieler weg zeigt. */
	static int rotationFromYaw(float yaw) {
		int d = ((int) Math.floor(yaw / 90.0 + 0.5)) & 3; // 0 Süden, 1 Westen, 2 Norden, 3 Osten
		return (d + 2) & 3;
	}

	// --- Bereich markieren (Einreichen) ---

	private boolean selecting;
	private int[] cornerA;
	private int[] hover;
	private CircuitWorld lastWorld;
	private volatile CircuitCapture.Result capture;

	/** Bereich markieren: erste Ecke anschauen + Bestätigen, zweite Ecke genauso (höchstens 16×16×16). */
	public void startSelecting() {
		selecting = true;
		cornerA = null;
		placing = false;
		if (keys != null) keys.releaseAll();
	}

	public boolean selecting() {
		return selecting;
	}

	/** Welt des letzten Ticks (für das Auslesen; null außerhalb einer Welt). */
	public CircuitWorld world() {
		return lastWorld;
	}

	/** Markieren beenden (ohne Auslesen). */
	public void cancelSelecting() {
		selecting = false;
		cornerA = null;
	}

	/** Erste Ecke direkt setzen (Selbsttest). */
	public void selectCorner(int[] a) {
		cornerA = a;
	}

	/** Letzter ausgelesener Bereich (oder null). */
	public CircuitCapture.Result capture() {
		return capture;
	}

	public void clearCapture() {
		capture = null;
	}

	/** Bereich direkt auslesen (Selbsttest; sonst über die Tasten). */
	public CircuitCapture.Result captureNow(CircuitWorld world, int[] a, int[] b) {
		String id = "submission";
		CircuitCapture.Result r = CircuitCapture.capture(world, CircuitLibrary.blockCatalog(), a[0], a[1], a[2], b[0], b[1], b[2], id);
		capture = r;
		return r;
	}

	private void finishSelecting(int[] b) {
		int[] a = cornerA;
		selecting = false;
		cornerA = null;
		captureNow(lastWorld, a, b);
		CircuitLibraryPage.requestSubmit();
		if (platform != null) platform.openMenu();
	}

	/** Etwas zu zeichnen (Vorlage oder Markierung)? */
	public boolean wantsDraw() {
		return active != null || selecting;
	}

	// --- Aktionen aus dem Menü ---

	/** Vorlage an den Blick hängen (Platzieren mit Drehen/Bestätigen). */
	public void startPlacing(Circuit c, boolean mirrored) {
		if (c == null) return;
		beforePlacing = entry();
		active = c;
		mirror = mirrored;
		rotationOffset = 0;
		placing = true;
		hidden = false;
		layer = -1;
		placement = null;
		check = null;
		if (keys != null) keys.releaseAll();
	}

	/** An der zuletzt bestätigten Position dieser Welt einblenden; false = keine gespeichert. */
	public boolean placeAtSaved(Circuit c, boolean mirrored) {
		if (c == null || store == null || worldKey == null) return false;
		CircuitStore.Entry a = store.anchor(worldKey);
		if (a == null) return false;
		placing = false;
		active = c;
		mirror = mirrored;
		hidden = false;
		layer = -1;
		// gespeichert ist der Mittelpunkt der Grundfläche
		placement = Placement.centered(c, a.x, a.y, a.z, a.rotation, mirrored);
		check = new CircuitCheck(c, placement);
		dirtyCheck = true;
		persist();
		return true;
	}

	/** An einer festen Lage einblenden (Selbsttest; bestätigt sofort). */
	public void placeAt(Circuit c, int x, int y, int z, int rotation, boolean mirrored) {
		if (c == null) return;
		placing = false;
		beforePlacing = null;
		active = c;
		mirror = mirrored;
		hidden = false;
		layer = -1;
		placement = new Placement(x, y, z, rotation, mirrored);
		check = new CircuitCheck(c, placement);
		dirtyCheck = true;
		persist();
	}

	public boolean hasSavedPosition() {
		return store != null && worldKey != null && store.anchor(worldKey) != null;
	}

	/** Eingeblendete Vorlage entfernen. */
	public void remove() {
		placing = false;
		clearActive();
		if (store != null && worldKey != null) {
			store.setActive(worldKey, null);
			store.save();
		}
	}

	private void confirmPlacing() {
		if (placement == null || active == null) return;
		placing = false;
		beforePlacing = null;
		if (store != null && worldKey != null) {
			CircuitStore.Entry a = new CircuitStore.Entry();
			a.circuit = active.id;
			int w = placement.width(active), d = placement.depth(active);
			a.x = placement.x + (w - 1) / 2;
			a.y = placement.y;
			a.z = placement.z + (d - 1) / 2;
			a.rotation = placement.rotation;
			store.setAnchor(worldKey, a);
		}
		say(I18n.tr("circuits.hud.placed"));
		persist();
	}

	private void cancelPlacing() {
		placing = false;
		CircuitStore.Entry e = beforePlacing;
		beforePlacing = null;
		clearActive();
		if (e != null) apply(e);
	}

	/** Schicht wählen (-1 = alle). */
	public void setLayer(int l) {
		layer = l;
		persist();
	}

	private void clearActive() {
		active = null;
		placement = null;
		check = null;
		layer = -1;
		hidden = false;
	}

	private void loadForWorld() {
		clearActive();
		if (store == null) return;
		CircuitStore.Entry e = store.active(worldKey);
		if (e != null) apply(e);
	}

	private void apply(CircuitStore.Entry e) {
		Circuit c = CircuitLibrary.get().byId(e.circuit);
		if (c == null) return;
		active = c;
		mirror = e.mirror;
		placement = new Placement(e.x, e.y, e.z, e.rotation, e.mirror);
		check = new CircuitCheck(c, placement);
		layer = e.layer >= c.sizeY ? -1 : e.layer;
		hidden = e.hidden;
		dirtyCheck = true;
	}

	private CircuitStore.Entry entry() {
		if (active == null || placement == null || placing) return null;
		CircuitStore.Entry e = new CircuitStore.Entry();
		e.circuit = active.id;
		e.x = placement.x;
		e.y = placement.y;
		e.z = placement.z;
		e.rotation = placement.rotation;
		e.mirror = placement.mirror;
		e.layer = layer;
		e.hidden = hidden;
		return e;
	}

	private void persist() {
		if (store == null || worldKey == null || placing) return;
		store.setActive(worldKey, entry());
		store.save();
	}

	private void say(String text) {
		message = text;
		messageUntil = System.currentTimeMillis() + 2500;
	}

	// --- Abfragen für das Menü ---

	public Circuit active() {
		return active;
	}

	public boolean placing() {
		return placing;
	}

	public boolean hidden() {
		return hidden;
	}

	public int layer() {
		return layer;
	}

	public CircuitCheck check() {
		return check;
	}

	public boolean inWorld() {
		return worldKey != null;
	}

	/** Anzahl eines Gegenstands im Inventar (-1 = unbekannt). */
	public int inventoryCount(String itemId) {
		if (inventory.isEmpty()) return -1;
		Integer n = inventory.get(itemId);
		return n == null ? 0 : n;
	}

	/** Gegenstand, den die Materialliste für einen Block zählt (je nach Version). */
	public String itemOf(BlockDef def) {
		return platform != null && platform.legacy() ? def.legacyItem : def.item;
	}

	// --- Zeichnen (HUD) ---

	/** Geisterblöcke + Fortschritt; aus dem Redstone-Overlay des Baums aufgerufen. */
	public void draw(Canvas c, double camX, double camY, double camZ, float yaw, float pitch, double fov, int width,
			int height) {
		if (modules == null || !modules.circuitLibrary.isEnabled()) return;
		if (selecting) {
			drawSelection(c, camX, camY, camZ, yaw, pitch, fov, width, height);
			return;
		}
		if (active == null || check == null) return;
		if (hidden && !placing) {
			hint(c, width);
			return;
		}
		CircuitTexts texts = CircuitTexts.get();
		painter.draw(c, check, layer, placing, (float) (modules.opacity.get() / 100.0), modules.labels.get(),
				modules.hideCorrect.get(), texts, camX, camY, camZ, yaw, pitch, fov, width, height);
		c.flush();
		panel(c, width, texts);
	}

	private void drawSelection(Canvas c, double camX, double camY, double camZ, float yaw, float pitch, double fov, int width,
			int height) {
		int[] a = cornerA;
		int[] b = hover;
		if (a == null) a = b;
		if (b == null) b = a;
		if (a != null && b != null) {
			int[] s = CircuitCapture.size(a[0], a[1], a[2], b[0], b[1], b[2]);
			boolean ok = s[0] <= Circuit.MAX_SIZE && s[1] <= Circuit.MAX_SIZE && s[2] <= Circuit.MAX_SIZE;
			painter.box(c, Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]) + 1,
					Math.max(a[1], b[1]) + 1, Math.max(a[2], b[2]) + 1, ok ? GhostPainter.PLACING : GhostPainter.RED, camX, camY,
					camZ, yaw, pitch, fov, width, height);
		}
		String title = I18n.tr(cornerA == null ? "circuits.select.first" : "circuits.select.second", keyName(modules.confirmKey));
		String line2;
		if (cornerA != null && b != null) {
			int[] s = CircuitCapture.size(cornerA[0], cornerA[1], cornerA[2], b[0], b[1], b[2]);
			line2 = I18n.tr("circuits.select.size", s[0], s[1], s[2]) + " · " + I18n.tr("circuits.select.cancel", keyName(modules.hideKey));
		} else {
			line2 = I18n.tr("circuits.select.cancel", keyName(modules.hideKey));
		}
		if (message != null && System.currentTimeMillis() < messageUntil) line2 = message;
		int w = Math.max(c.textWidth(title), c.textWidth(line2)) + 16;
		int x = width / 2 - w / 2;
		c.fill(x, 4, x + w, 30, 0xB0101010);
		c.fill(x, 4, x + w, 5, GhostPainter.PLACING);
		c.text(title, x + 8, 8, 0xFFFFFFFF, true);
		c.text(line2, x + 8, 19, 0xFFD0D0D0, false);
	}

	private void hint(Canvas c, int width) {
		if (message != null && System.currentTimeMillis() < messageUntil) {
			int tw = c.textWidth(message);
			c.fill(width / 2 - tw / 2 - 4, 4, width / 2 + tw / 2 + 4, 16, 0xA0000000);
			c.text(message, width / 2 - tw / 2, 6, 0xFFFFFFFF, false);
		}
	}

	/** Kasten oben mittig: Name, Fortschritt, Schicht, Tasten. */
	private void panel(Canvas c, int width, CircuitTexts texts) {
		Theme t = Theme.get();
		String title = texts.name(active);
		String progress = I18n.tr("circuits.hud.progress", check.correct(), check.total());
		String layerText = layer < 0 ? I18n.tr("circuits.hud.allLayers") : I18n.tr("circuits.hud.layer", layer + 1, active.sizeY);
		String line2;
		if (placing) {
			line2 = I18n.tr("circuits.hud.placeKeys", keyName(modules.rotateKey), keyName(modules.confirmKey), keyName(modules.hideKey));
		} else {
			StringBuilder b = new StringBuilder(progress);
			if (check.wrong() > 0) b.append(" · ").append(I18n.tr("circuits.hud.wrong", check.wrong()));
			b.append(" · ").append(layerText);
			line2 = b.toString();
		}
		String line3 = placing ? null : I18n.tr("circuits.hud.keys", keyName(modules.layerUpKey), keyName(modules.layerDownKey),
				keyName(modules.hideKey));
		if (message != null && System.currentTimeMillis() < messageUntil) line3 = message;
		int w = Math.max(c.textWidth(title), c.textWidth(line2));
		if (line3 != null) w = Math.max(w, c.textWidth(line3));
		w += 16;
		int h = line3 == null ? 26 : 36;
		int x = width / 2 - w / 2;
		int y = 4;
		c.fill(x, y, x + w, y + h, 0xB0101010);
		c.fill(x, y, x + w, y + 1, placing ? GhostPainter.PLACING : check.complete() ? GhostPainter.GREEN : t.accent);
		// Fortschrittsbalken
		if (!placing && check.total() > 0) {
			int bw = (int) Math.round((w - 2) * (check.correct() / (double) check.total()));
			c.fill(x + 1, y + h - 2, x + 1 + bw, y + h - 1, ColorMath.withAlpha(GhostPainter.GREEN, 220));
		}
		c.text(title, x + 8, y + 4, 0xFFFFFFFF, true);
		c.text(line2, x + 8, y + 15, check.complete() && !placing ? 0xFF7CF08F : 0xFFD0D0D0, false);
		if (line3 != null) c.text(line3, x + 8, y + 25, 0xFFA0A0A0, false);
	}

	private static String keyName(KeySetting key) {
		String k = key.get();
		if (k == null || !key.isBound()) return "–";
		int dot = k.lastIndexOf('.');
		String n = dot >= 0 ? k.substring(dot + 1) : k;
		if ("up".equals(n)) return "↑";
		if ("down".equals(n)) return "↓";
		if (n.length() == 1) return n.toUpperCase(java.util.Locale.ROOT);
		return Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}
}
