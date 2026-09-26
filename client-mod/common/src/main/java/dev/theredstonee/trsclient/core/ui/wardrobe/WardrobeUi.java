package dev.theredstonee.trsclient.core.ui.wardrobe;

import dev.theredstonee.trsclient.core.emote.Channel;
import dev.theredstonee.trsclient.core.emote.EmoteController;
import dev.theredstonee.trsclient.core.emote.EmoteDef;
import dev.theredstonee.trsclient.core.emote.EmoteRig;
import dev.theredstonee.trsclient.core.emote.Emotes;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.intro.IntroGate;
import dev.theredstonee.trsclient.core.module.NewSince;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.CapeShare;
import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.FriendsView;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.skin.PlayerLook;
import dev.theredstonee.trsclient.core.skin.SkinModel;
import dev.theredstonee.trsclient.core.skin.SkinModelSpec;
import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.core.ui.menu.NewBadge;
import dev.theredstonee.trsclient.core.wardrobe.CurrentSkin;
import dev.theredstonee.trsclient.core.wardrobe.SkinEditor;
import dev.theredstonee.trsclient.core.wardrobe.WardrobeContext;
import dev.theredstonee.trsclient.core.wardrobe.WardrobeDoc;
import dev.theredstonee.trsclient.core.wardrobe.WardrobeService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Die Garderobe im Redstone-Stil (Vorbild Essential): links Kategorien (Skins, Outfits, Umhänge, Emotes), in der
 * Mitte das Raster mit „Favoriten“ und „Bibliothek“ (Herz zum Merken, „+“ zum Hinzufügen), rechts die große
 * 3D-Vorschau mit den Aktionen. Der volle Skin-Editor ({@link SkinEditorView}) übernimmt bei „Bearbeiten“ das Fenster.
 *
 * <p>Umhänge mit Freunden teilen (API.md §5.10): Angebote von Freunden stehen oben bei „Umhänge“ (annehmen/ablehnen),
 * eigene freigegebene und geteilt bekommene Umhänge lassen sich über „Mit Freund teilen“ anbieten; das Fenster dort
 * zeigt auch, wer ihn hat (entziehen). Die Daten dafür kommen aus {@link Friends} (TRS-Thread).
 *
 * <p>Skins: ganz vorn in der Bibliothek steht immer die Karte „Aktueller Skin“ (was das Konto gerade trägt, wie im
 * Launcher) – nur sie hat die Lampe „getragen“; ein gleicher Bibliotheks-Skin zeigt „= aktuell“ (siehe
 * {@link CurrentSkin}). Der Leuchtrahmen ist nur die Auswahl für die Vorschau.
 *
 * <p>Versionsunabhängig; Minecraft kommt nur über {@link WardrobeHost}, alle Daten über {@link WardrobeService}
 * (Hintergrund-Thread) – hier gibt es kein Netz und keine Datei-Zugriffe.
 */
public final class WardrobeUi extends UiScreen {
	enum Category {
		SKINS("wardrobe.cat.skins", "shirt"), OUTFITS("wardrobe.cat.outfits", "layers"), CAPES("wardrobe.cat.capes", "cape"),
		EMOTES("wardrobe.cat.emotes", "dance");

		final String key;
		final String icon;

		Category(String key, String icon) {
			this.key = key;
			this.icon = icon;
		}
	}

	private enum Input {
		NONE, URL, NAME, RENAME_SKIN, RENAME_OUTFIT, NEW_OUTFIT
	}

	private static final int HEADER_H = 26;
	private static final int PAD = 6;
	private static final int GAP = 4;

	private final WardrobeHost host;
	private final WardrobeService service;
	private final WardrobeTextures textures = new WardrobeTextures();
	private final SkinModel model = new SkinModel();
	private final SkinModelSpec spec = new SkinModelSpec();
	private final SkinEditorView editorView;
	private final EmoteRig rig = new EmoteRig();
	private final float[] emoteFrame = new float[Channel.COUNT];
	private final float[] pose = new float[SkinModel.PARTS_POSE * 6];

	Category category = Category.SKINS;
	String selectedSkin;
	String selectedOutfit;
	String selectedCape;
	String selectedEmote;
	int selectedSlot = -1;
	boolean editing;
	private long emoteStart;
	private final int[] scroll = new int[Category.values().length];
	private int maxScroll;
	private final int[] gridRect = new int[4];
	private final int[] previewRect = new int[4];

	// Vorschau drehen
	private float yaw = 20f;
	private float yawVelocity;
	private boolean draggingPreview;
	private double lastDragX;
	private long lastInteraction;

	// Dialoge
	private boolean addMenu;
	private final int[] addAnchor = new int[2];
	private Input input = Input.NONE;
	private String inputTarget;
	private final TextInput field = new TextInput(512);
	private String armedDelete;
	private long armedUntil;
	/** Offenes Fenster „Umhang teilen“ (Umhang-Schlüssel {@code trs:<id>}) oder null. */
	private String sharePanel;
	private int shareScroll;
	private int shareMaxScroll;
	private final int[] shareRect = new int[4];
	private final int[] shareListRect = new int[4];

	// Umhänge teilen: was schon übernommen/gemeldet wurde
	private int seenCapesChanged = -1;
	private int seenOfferNotice = -1;
	private long seenFriendsMessageAt = -1;
	private final Set<String> offerArtRequested = new HashSet<String>();
	private long capesShownSince;

	// Meldungen
	private String toast;
	private long toastUntil;
	private int seenMessageVersion = -1;
	private String lastAdded;
	private boolean awaitingSave;

	/** Getragener Skin (je Bild neu bestimmt) und der zwischengespeicherte Vergleichswert des Menü-Skins. */
	private CurrentSkin current;
	private int[] lookKeyPixels;
	private boolean lookKeySlim;
	private String lookKey;

	public WardrobeUi(WardrobeHost host) {
		this.host = host;
		this.service = WardrobeContext.service(host.configDir(), host.userAgent(), host.features());
		this.editorView = new SkinEditorView(new SkinEditorView.Actions() {
			@Override
			public void save(int[] pixels, boolean slim, String name, String replaceId) {
				awaitingSave = true;
				service.saveEdited(pixels, slim, name, replaceId);
			}

			@Override
			public void apply(int[] pixels, boolean slim) {
				service.applyPixels(pixels, slim);
			}

			@Override
			public void close() {
				editing = false;
			}

			@Override
			public int[] currentSkin() {
				return current == null ? null : current.pixels;
			}

			@Override
			public boolean currentSlim() {
				return current != null && current.slim;
			}

			@Override
			public void click() {
				WardrobeUi.this.host.playClick();
			}

			@Override
			public void toast(String key) {
				showToast(I18n.tr(key));
			}
		}, textures);
		I18n.refresh();
		service.open();
		lastAdded = service.state().added;
		seenMessageVersion = -1;
	}

	/** Öffnet direkt eine Kategorie (Autotest, Tastenkürzel). */
	public WardrobeUi category(String name) {
		for (Category c : Category.values()) if (c.name().equalsIgnoreCase(name)) category = c;
		return this;
	}

	/** Öffnet den Editor mit einer leeren Vorlage (Autotest). */
	public void openEditorBlank() {
		editorView.load(SkinEditor.blankTemplate(false), false, I18n.tr("wardrobe.editor.newName"), null);
		editing = true;
	}

	/** Selbsttest: Skin auswählen. */
	public void selectSkin(String id) {
		selectedSkin = id;
	}

	/** Selbsttest: Karte „Aktueller Skin“ auswählen. */
	public void selectCurrent() {
		selectedSkin = CurrentSkin.ID;
	}

	/** Selbsttest: getragenen Skin in die Bibliothek sichern; false, solange seine Pixel unbekannt sind. */
	public boolean saveCurrentSkin() {
		if (current == null || current.pixels == null) return false;
		service.saveCurrent(current.pixels, current.slim, currentName(host.look()));
		return true;
	}

	/** Selbsttest: der zuletzt bestimmte getragene Skin (null vor dem ersten Bild). */
	public CurrentSkin current() {
		return current;
	}

	/** Selbsttest: Emote auswählen (Vorschau spielt es ab). */
	public void selectEmote(String id) {
		selectedEmote = id;
		emoteStart = System.currentTimeMillis();
	}

	/** Selbsttest: Menü „Skin hinzufügen“ zeigen (am linken Rand des Rasters). */
	public void showAddMenu() {
		addMenu = true;
		addAnchor[0] = gridRect[0] + 4;
		addAnchor[1] = gridRect[1] + 40;
	}

	/** Selbsttest: offene Dialoge/Editor schließen. */
	public void closeOverlays() {
		addMenu = false;
		input = Input.NONE;
		editing = false;
		sharePanel = null;
	}

	/** Selbsttest: Umhang auswählen ({@code trs:<id>}, {@code offer:<id>} …). */
	public void selectCape(String key) {
		selectedCape = key;
	}

	/** Selbsttest: Fenster „Umhang teilen“ für einen TRS-Umhang öffnen (false = nicht teilbar/unbekannt). */
	public boolean openShareFor(String key) {
		WardrobeService.Cape cape = trsCape(service.state(), key);
		if (cape == null || !cape.shareable) return false;
		openShare(cape);
		return true;
	}

	/** Für den Autotest: Editor-Modell. */
	public SkinEditor editorModel() {
		return editorView.editor;
	}

	public WardrobeService service() {
		return service;
	}

	@Override
	protected void onClosed() {
		textures.releaseAll();
		host.closeScreen();
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	/** Getragener Skin: Mojang-Profil der Garderobe (wenn für dieses Konto geladen), sonst der Menü-Skin. */
	private CurrentSkin resolveCurrent(WardrobeService.State s, PlayerLook look) {
		int[] px = look == null ? null : look.pixels;
		boolean slim = look != null && look.slim;
		if (px != lookKeyPixels || slim != lookKeySlim) {
			lookKeyPixels = px;
			lookKeySlim = slim;
			lookKey = px == null ? null : CurrentSkin.lookKey(px, slim);
		}
		return CurrentSkin.resolve(s, service.fresh(s), px, lookKey, slim, look != null && look.ownSkin);
	}

	/** Karte „Aktueller Skin“ ausgewählt (auch, wenn nichts oder ein verschwundener Skin gewählt ist)? */
	private boolean currentSelected(WardrobeService.State s) {
		return selectedSkin == null || CurrentSkin.ID.equals(selectedSkin) || s.skin(selectedSkin) == null;
	}

	/** Textur des getragenen Skins: aus seinen Pixeln, sonst die Textur des Menü-Skins (Standard-Skin). */
	private TextureRef currentTexture(PlayerLook look) {
		if (current != null && current.pixels != null) {
			TextureRef tex = textures.get("wardrobe/current", current.pixels, 64, 64, 0);
			if (tex != null) return tex;
		}
		return look == null ? null : look.skin;
	}

	/** Name für „In Bibliothek speichern“: der Kontoname, sonst „Mein Skin“. */
	private static String currentName(PlayerLook look) {
		String n = look == null ? null : look.name;
		return n != null && n.matches("[A-Za-z0-9_]{1,16}") && !"Player".equals(n) ? n : I18n.tr("wardrobe.mySkin");
	}

	// ============================================================================================
	// Zeichnen
	// ============================================================================================

	@Override
	protected void draw(Canvas raw, int width, int height, int mouseX, int mouseY, float dt) {
		long now = System.currentTimeMillis();
		service.tick(now);
		textures.frame();
		WardrobeService.State s = service.state();
		current = resolveCurrent(s, host.look());
		react(s);
		reactShares(s, now);
		Theme t = Theme.get();
		Canvas c = FadeCanvas.of(raw, alpha());
		c.fill(0, 0, width, height, t.scrim);

		int pw = width - PAD * 2;
		int ph = height - PAD * 2;
		int px = PAD;
		int py = PAD + Math.round((1 - Anim.easeOut(open)) * 12);
		boolean modal = addMenu || input != Input.NONE || sharePanel != null;
		int mx = modal ? -1 : mouseX;
		int my = modal ? -1 : mouseY;

		c.push();
		c.raise(300f);
		Redstone.window(c, px, py, pw, ph);
		header(c, s, px, py, pw, mx, my);
		int bx = px + PAD;
		int by = py + HEADER_H + GAP;
		int bw = pw - PAD * 2;
		int bh = ph - HEADER_H - GAP - PAD;
		if (editing) {
			editorView.draw(c, hits, bx, by, bw, bh, mx, my);
		} else {
			int sideW = bw >= 360 ? 76 : 24;
			sidebar(c, s, bx, by, sideW, bh, mx, my);
			int prevW = Math.max(96, Math.min(170, bw / 3));
			int gx = bx + sideW + GAP;
			int gw = bw - sideW - prevW - GAP * 2;
			grid(c, s, gx, by, gw, bh, mx, my);
			preview(c, s, gx + gw + GAP, by, prevW, bh, mx, my, dt);
		}
		drawToast(c, px, py + ph - 20, pw);
		if (editing) editorView.drawOverlay(c);
		if (addMenu) addMenu(c, mouseX, mouseY);
		if (input != Input.NONE) inputDialog(c, width, height, mouseX, mouseY);
		if (sharePanel != null) sharePanel(c, s, width, height, mouseX, mouseY);
		c.pop();
	}

	// ============================================================================================
	// Umhänge teilen
	// ============================================================================================

	private static Friends friends() {
		TrsOnline online = TrsOnline.current();
		return online == null ? null : online.friends();
	}

	private static String ownUuid() {
		TrsOnline online = TrsOnline.current();
		return online == null ? null : online.ownUuid();
	}

	/** Angebote/Meldungen der Freunde übernehmen: Liste neu laden, Vorschauen holen, Toasts zeigen. */
	private void reactShares(WardrobeService.State s, long now) {
		Friends f = friends();
		if (f == null || !s.trs) return;
		// Angebote gehören zur Garderobe: im Hintergrund-Takt mit abfragen.
		f.want(Friends.Interest.BACKGROUND, false);
		Friends.Snapshot fs = f.snapshot();
		if (seenCapesChanged == -1) seenCapesChanged = fs.capesChanged;
		if (fs.capesChanged != seenCapesChanged) {
			seenCapesChanged = fs.capesChanged;
			service.refreshCapes();
			if (selectedCape != null && selectedCape.startsWith("offer:")) selectedCape = "trs:" + selectedCape.substring(6);
		}
		if (seenOfferNotice == -1) seenOfferNotice = fs.offerNotice;
		if (fs.offerNotice != seenOfferNotice) {
			seenOfferNotice = fs.offerNotice;
			if (fs.offerNoticeArgs.length == 2) showToast(I18n.tr("wardrobe.share.offerToast", fs.offerNoticeArgs));
		}
		if (seenFriendsMessageAt == -1) seenFriendsMessageAt = fs.messageAt;
		if (fs.message != null && fs.messageAt != seenFriendsMessageAt) {
			seenFriendsMessageAt = fs.messageAt;
			showToast(I18n.has(fs.message) ? I18n.tr(fs.message, fs.args) : I18n.tr("friends.error.generic"));
		}
		List<CapeShare.Offer> missing = new ArrayList<CapeShare.Offer>();
		for (CapeShare.Offer o : fs.offerList()) {
			if (offerArtRequested.add(o.capeId)) missing.add(o);
		}
		if (!missing.isEmpty()) service.previewOffers(missing);
		// Gesehen, sobald die Umhänge eine Weile offen sind (dann fällt „NEU“ weg).
		if (category == Category.CAPES && !editing) {
			if (capesShownSince == 0) capesShownSince = now;
			else if (now - capesShownSince > 2500 && fs.unseenOffers > 0) f.markOffersSeen();
		} else {
			capesShownSince = 0;
		}
	}

	private static CapeShare.Offer offer(Friends.Snapshot fs, String key) {
		if (fs == null || key == null || !key.startsWith("offer:")) return null;
		String id = key.substring(6);
		for (CapeShare.Offer o : fs.offerList()) if (o.capeId.equals(id)) return o;
		return null;
	}

	private static WardrobeService.Cape trsCape(WardrobeService.State s, String key) {
		if (s.trsCapes == null || key == null) return null;
		for (WardrobeService.Cape cape : s.trsCapes) if (cape.key.equals(key)) return cape;
		return null;
	}

	/** Bereich neu seit dem letzten Update und noch nie benutzt? */
	private static boolean isNew(String newId) {
		TrsModules m = IntroGate.modules();
		return m != null && m.clientState.news().isNew(newId);
	}

	private static void seen(String newId) {
		TrsModules m = IntroGate.modules();
		if (m != null && m.clientState.news().markSeen(newId)) IntroGate.save(m);
	}

	private void openShare(WardrobeService.Cape cape) {
		sharePanel = cape.key;
		shareScroll = 0;
		addMenu = false;
		seen(NewSince.WARDROBE_CAPE_SHARE);
		Friends f = friends();
		if (f != null) {
			f.loadHolders(cape.id());
			f.refresh();
		}
	}

	/** Fenster „Umhang teilen“: Freunde zum Anbieten, darunter die Inhaber mit Entziehen. */
	private void sharePanel(Canvas c, WardrobeService.State s, int width, int height, int mx, int my) {
		Theme t = Theme.get();
		final WardrobeService.Cape cape = trsCape(s, sharePanel);
		Friends f = friends();
		if (cape == null || !cape.shareable || f == null) {
			sharePanel = null;
			return;
		}
		Friends.Snapshot fs = f.snapshot();
		CapeShare.Holders holders = fs.holdersOf(cape.id());
		int w = Math.min(width - 24, 300);
		int h = Math.min(height - 24, 250);
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		shareRect[0] = x;
		shareRect[1] = y;
		shareRect[2] = w;
		shareRect[3] = h;
		c.flush();
		c.push();
		c.raise(80f);
		c.fill(0, 0, width, height, 0x60000000);
		Redstone.window(c, x, y, w, h);
		Paint.textClipped(c, I18n.tr("wardrobe.share.title", cape.name), x + 8, y + 7, w - 30, t.text, false);
		int cs = 14;
		Paint.iconButton(c, x + w - cs - 5, y + 4, cs, "close", inside(mx, my, x + w - cs - 5, y + 4, cs, cs), false);
		hits.add(x + w - cs - 5, y + 4, cs, cs, () -> {
			host.playClick();
			sharePanel = null;
		});
		// Fußzeile
		List<String> foot = Paint.wrap(c, I18n.tr("wardrobe.share.cascade"), w - 16);
		int footH = foot.size() * 10 + 4;
		int fy = y + h - footH - 2;
		for (String l : foot) {
			c.text(l, x + 8, fy, t.textDim, false);
			fy += 10;
		}
		// Liste
		int lx = x + 6;
		int ly = y + 22;
		int lw = w - 12;
		int lh = h - 22 - footH - 6;
		shareListRect[0] = lx;
		shareListRect[1] = ly;
		shareListRect[2] = lw;
		shareListRect[3] = lh;
		Redstone.well(c, lx, ly, lw, lh, t.border);
		c.scissor(lx + 1, ly + 1, lx + lw - 1, ly + lh - 1);
		hits.clip(lx + 1, ly + 1, lw - 2, lh - 2);
		int iy = ly + 4 - shareScroll;
		int ix = lx + 4;
		int iw = lw - 10;
		boolean busy = fs.busy != null;
		// Freunde
		iy = section(c, I18n.tr("wardrobe.share.pickFriend"), ix, iy, iw);
		FriendsView view = fs.view;
		String self = ownUuid();
		if (holders == null) {
			iy = note(c, I18n.tr("wardrobe.share.loading"), ix, iy, iw);
		} else if (holders.full()) {
			iy = note(c, I18n.tr("wardrobe.share.full", holders.limit), ix, iy, iw);
		} else {
			int shown = 0;
			if (view != null) {
				for (final FriendsView.Friend fr : view.friends) {
					if (holders.has(fr.uuid) || fr.uuid.equals(self)) continue;
					shown++;
					rowBox(c, ix, iy, iw, mx, my);
					Paint.textClipped(c, fr.name, ix + 5, iy + 5, iw - 86, t.text, false);
					final String capeId = cape.id();
					final String capeName = cape.name;
					button(c, ix + iw - 76, iy + 2, 74, 14, I18n.tr(("share:" + fr.uuid).equals(fs.busy) ? "wardrobe.share.sharing" : "wardrobe.share.share"),
							true, !busy, mx, my, () -> {
								Friends ff = friends();
								if (ff != null) ff.offerCape(capeId, capeName, fr.uuid, fr.name);
							});
					iy += 20;
				}
			}
			if (shown == 0) {
				iy = note(c, I18n.tr(view == null || view.friends.isEmpty() ? "wardrobe.share.noFriends" : "wardrobe.share.allHaveIt"), ix, iy, iw);
			}
		}
		iy += 4;
		// Inhaber
		String heading = I18n.tr("wardrobe.share.holders") + (holders == null ? "" : "  " + holders.count + "/" + holders.limit);
		iy = section(c, heading, ix, iy, iw);
		if (holders != null && holders.holders.isEmpty()) {
			iy = note(c, I18n.tr("wardrobe.share.none"), ix, iy, iw);
		} else if (holders != null) {
			long now = System.currentTimeMillis();
			for (final CapeShare.Holder hd : holders.holders) {
				rowBox(c, ix, iy, iw, mx, my);
				Paint.textClipped(c, hd.name, ix + 5, iy + 1, iw - 86, t.text, false);
				String sub = I18n.tr(hd.offered ? "wardrobe.share.offered" : "wardrobe.share.accepted");
				if (self != null && !self.equals(hd.grantedByUuid)) sub += " · " + I18n.tr("wardrobe.share.via", hd.grantedByName);
				Paint.textClipped(c, sub, ix + 5, iy + 10, iw - 86, t.textDim, false);
				final String armKey = "holder:" + hd.uuid;
				final boolean armed = armKey.equals(armedDelete) && now < armedUntil;
				String label = armed ? I18n.tr("wardrobe.share.confirm")
						: I18n.tr(hd.offered ? "wardrobe.share.withdraw" : "wardrobe.share.revoke");
				final String capeId = cape.id();
				button(c, ix + iw - 76, iy + 2, 74, 14, label, armed, !busy, mx, my, () -> {
					if (armKey.equals(armedDelete) && System.currentTimeMillis() < armedUntil) {
						armedDelete = null;
						Friends ff = friends();
						if (ff != null) ff.revokeShare(capeId, hd.uuid, hd.name, hd.offered, false);
					} else {
						armedDelete = armKey;
						armedUntil = System.currentTimeMillis() + 3000;
					}
				});
				iy += 20;
			}
		}
		int content = iy + shareScroll - (ly + 4);
		hits.noClip();
		c.noScissor();
		shareMaxScroll = Math.max(0, content - (lh - 8));
		if (shareScroll > shareMaxScroll) shareScroll = shareMaxScroll;
		if (shareMaxScroll > 0) {
			int barH = Math.max(12, lh * lh / Math.max(1, content));
			int barY = ly + (lh - barH) * Math.min(shareScroll, shareMaxScroll) / Math.max(1, shareMaxScroll);
			c.fill(lx + lw - 3, barY, lx + lw - 1, barY + barH, t.border);
		}
		c.pop();
	}

	private void rowBox(Canvas c, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		Redstone.stone(c, x, y, w, 18, inside(mx, my, x, y, w, 18) ? t.surfaceHover : t.surface, t.border);
	}

	/** Ergebnisse des Dienstes übernehmen (neuer Skin auswählen, Meldungen). */
	private void react(WardrobeService.State s) {
		if (s.added != null && !s.added.equals(lastAdded)) {
			lastAdded = s.added;
			if (awaitingSave && editing) {
				editorView.saved(s.added);
				awaitingSave = false;
			}
			if (s.skin(s.added) != null) {
				selectedSkin = s.added;
				if (!editing) category = Category.SKINS;
			} else if (s.outfit(s.added) != null) {
				selectedOutfit = s.added;
			}
		}
		if (s.message != null && s.version != seenMessageVersion) {
			seenMessageVersion = s.version;
			if (!s.message.equals(lastMessageKey) || !s.busy()) {
				lastMessageKey = s.message;
				showToast(message(s.message));
				if (s.error && awaitingSave) awaitingSave = false;
				if (s.error) editorView.saved(editorView.replaceId);
			}
		} else if (s.message == null) {
			lastMessageKey = null;
		}
	}

	private String lastMessageKey;

	private static String message(String key) {
		return I18n.has(key) ? I18n.tr(key) : I18n.tr("wardrobe.error.error");
	}

	private void showToast(String text) {
		toast = text;
		toastUntil = System.currentTimeMillis() + 4500;
	}

	private void header(Canvas c, WardrobeService.State s, int px, int py, int pw, int mx, int my) {
		Theme t = Theme.get();
		c.fill(px + 1, py + 2, px + pw - 1, py + HEADER_H, t.surfaceHigh);
		c.fill(px + 1, py + HEADER_H - 1, px + pw - 1, py + HEADER_H, t.border);
		int lx = px + PAD + 2;
		int ly = py + (HEADER_H - PixelFont.HEIGHT * 2) / 2;
		List<int[]> rects = PixelFont.rects("TRS");
		int light = ColorMath.lerp(t.accent, 0xFFFFFFFF, 0.3f);
		for (int[] r : rects) c.fill(lx + r[0] * 2, ly + r[1] * 2, lx + r[2] * 2, ly + r[3] * 2, r[1] == 0 ? light : t.accent);
		int titleX = lx + PixelFont.width("TRS") * 2 + 5;
		int closeSize = 16;
		int closeX = px + pw - PAD - closeSize;
		String title = I18n.tr(editing ? "wardrobe.editor.title" : "wardrobe.title");
		// Status rechts (Sync)
		String status;
		int statusColor = t.textDim;
		if (s.task == WardrobeService.Task.LOADING || s.task == WardrobeService.Task.SYNCING) {
			status = I18n.tr("wardrobe.status.syncing");
			statusColor = t.dustOn;
		} else if (s.busy()) {
			status = I18n.tr("wardrobe.status.working");
			statusColor = t.dustOn;
		} else {
			status = I18n.tr(s.trs ? "wardrobe.status.synced" : "wardrobe.status.local");
		}
		int maxStatus = Math.max(0, (closeX - titleX) / 2 - 10);
		int sw = Math.min(c.textWidth(status), maxStatus);
		if (sw > 30) {
			Paint.textRight(c, c.clip(status, sw), closeX - 8, py + (HEADER_H - 8) / 2, statusColor, false);
			Redstone.pip(c, closeX - 8 - sw - 10, py + (HEADER_H - 6) / 2, 6, s.busy() ? (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 150.0)) : (s.trs ? 1f : 0.15f));
		} else {
			sw = 0;
		}
		Paint.textClipped(c, title, titleX, py + (HEADER_H - 8) / 2, closeX - titleX - sw - 24, t.text, false);
		int closeY = py + (HEADER_H - closeSize) / 2;
		Paint.iconButton(c, closeX, closeY, closeSize, "close", inside(mx, my, closeX, closeY, closeSize, closeSize), false);
		hits.add(closeX, closeY, closeSize, closeSize, () -> {
			host.playClick();
			requestClose();
		});
	}

	private void sidebar(Canvas c, WardrobeService.State s, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		boolean icons = w < 50;
		int rowH = 20;
		int cy = y;
		for (final Category cat : Category.values()) {
			boolean active = cat == category;
			boolean hov = inside(mx, my, x, cy, w, rowH);
			if (active) {
				Redstone.glow(c, x, cy, w, rowH, t.glow, 0.3f);
				Redstone.stone(c, x, cy, w, rowH, ColorMath.lerp(t.surface, t.accent, 0.12f), ColorMath.lerp(t.border, t.accent, 0.7f));
			} else {
				Redstone.stone(c, x, cy, w, rowH, hov ? t.surfaceHover : t.surface, t.border);
			}
			int iconColor = active ? t.dustOn : (hov ? t.text : t.textDim);
			if (icons) {
				Icons.draw(c, cat.icon, x + (w - 8) / 2, cy + 6, 1, iconColor);
			} else {
				Icons.draw(c, cat.icon, x + 5, cy + 6, 1, iconColor);
				Paint.textClipped(c, I18n.tr(cat.key), x + 17, cy + 6, w - 20, active ? t.text : ColorMath.lerp(t.text, t.textDim, 0.3f), false);
			}
			if (cat == Category.CAPES) {
				Friends f = friends();
				if (f != null && s.trs && f.snapshot().unseenOffers > 0) NewBadge.dot(c, x + w - 7, cy + 2);
			}
			hits.add(x, cy, w, rowH, () -> {
				host.playClick();
				category = cat;
				addMenu = false;
			});
			cy += rowH + 3;
		}
		// Hinweis unten
		if (!icons) {
			String note = I18n.tr(s.trs ? "wardrobe.note.synced" : "wardrobe.note.local");
			List<String> lines = Paint.wrap(c, note, w - 2);
			int ly = y + h - lines.size() * 10;
			if (ly > cy + 4) {
				for (String l : lines) {
					c.text(l, x + 1, ly, t.textDim, false);
					ly += 10;
				}
			}
		}
	}

	// --- Raster ---

	private void grid(Canvas c, WardrobeService.State s, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		gridRect[0] = x;
		gridRect[1] = y;
		gridRect[2] = w;
		gridRect[3] = h;
		Redstone.well(c, x, y, w, h, t.border);
		int ix = x + 4;
		int iw = w - 8;
		int top = y + 4;
		int ih = h - 8;
		int sc = scroll[category.ordinal()];
		c.scissor(x + 1, y + 1, x + w - 1, y + h - 1);
		hits.clip(x + 1, y + 1, w - 2, h - 2);
		int content;
		switch (category) {
			case OUTFITS:
				content = outfits(c, s, ix, top - sc, iw, mx, my);
				break;
			case CAPES:
				content = capes(c, s, ix, top - sc, iw, mx, my);
				break;
			case EMOTES:
				content = emotes(c, s, ix, top - sc, iw, mx, my);
				break;
			default:
				content = skins(c, s, ix, top - sc, iw, mx, my);
				break;
		}
		hits.noClip();
		c.noScissor();
		maxScroll = Math.max(0, content - ih);
		if (sc > maxScroll) scroll[category.ordinal()] = maxScroll;
		if (maxScroll > 0) {
			int barH = Math.max(12, ih * ih / Math.max(1, content));
			int barY = top + (ih - barH) * Math.min(sc, maxScroll) / Math.max(1, maxScroll);
			c.fill(x + w - 3, barY, x + w - 1, barY + barH, t.border);
		}
	}

	private int columns(int w) {
		return Math.max(2, (w + GAP) / (54 + GAP));
	}

	private int section(Canvas c, String title, int x, int y, int w) {
		Theme t = Theme.get();
		Paint.textClipped(c, title, x + 1, y + 1, w, t.textDim, false);
		c.fill(x, y + 11, x + w, y + 12, t.border);
		return y + 15;
	}

	private int skins(Canvas c, WardrobeService.State s, int x, int y, int w, int mx, int my) {
		int start = y;
		int cols = columns(w);
		int cw = (w - (cols - 1) * GAP) / cols;
		int ch = Math.round(cw * 1.3f);
		List<WardrobeService.Skin> favs = new ArrayList<WardrobeService.Skin>();
		for (String id : s.doc.favorites) {
			WardrobeService.Skin sk = s.skin(id);
			if (sk != null) favs.add(sk);
		}
		if (!favs.isEmpty()) {
			y = section(c, I18n.tr("wardrobe.favorites"), x, y, w);
			for (int i = 0; i < favs.size(); i++) {
				skinCard(c, s, favs.get(i), x + (i % cols) * (cw + GAP), y + (i / cols) * (ch + GAP), cw, ch, mx, my);
			}
			y += ((favs.size() + cols - 1) / cols) * (ch + GAP) + 2;
		}
		y = section(c, I18n.tr("wardrobe.library") + "  " + s.skins.size() + "/60", x, y, w);
		// Zuerst der getragene Skin (wie im Launcher), dann „+“, dann die Bibliothek.
		currentCard(c, s, x, y, cw, ch, mx, my);
		addCard(c, x + (1 % cols) * (cw + GAP), y + (1 / cols) * (ch + GAP), cw, ch, mx, my);
		for (int i = 0; i < s.skins.size(); i++) {
			int k = i + 2;
			skinCard(c, s, s.skins.get(i), x + (k % cols) * (cw + GAP), y + (k / cols) * (ch + GAP), cw, ch, mx, my);
		}
		y += ((s.skins.size() + 2 + cols - 1) / cols) * (ch + GAP);
		if (s.skins.isEmpty()) {
			List<String> lines = Paint.wrap(c, I18n.tr("wardrobe.emptyLibrary"), w - 4);
			for (String l : lines) {
				c.text(l, x + 2, y, Theme.get().textDim, false);
				y += 10;
			}
		}
		return y - start;
	}

	/** Karte „Aktueller Skin“: was das Konto trägt – mit der Lampe „getragen“, ohne Herz/Löschen. */
	private void currentCard(Canvas c, WardrobeService.State s, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		boolean sel = currentSelected(s);
		boolean hov = inside(mx, my, x, y, w, h);
		if (sel) Redstone.glow(c, x, y, w, h, t.glow, 0.45f);
		Redstone.stone(c, x, y, w, h, hov ? t.surfaceHover : t.surface, sel ? ColorMath.lerp(t.border, t.accent, 0.8f) : t.border);
		PlayerLook look = host.look();
		TextureRef tex = currentTexture(look);
		if (tex != null || (current != null && current.pixels != null)) {
			SkinDraw.doll(c, tex, current == null ? null : current.pixels, current != null ? current.slim : look != null && look.slim,
					x + 3, y + 4, w - 6, h - 17);
		} else {
			Paint.textCentered(c, c.clip(I18n.tr("wardrobe.loading"), w - 6), x + w / 2, y + (h - 17) / 2, t.textDim, false);
		}
		// Getragen: die Lampe gibt es nur hier (siehe CurrentSkin).
		Redstone.pip(c, x + 3, y + 3, 6, 1f);
		Paint.textClipped(c, I18n.tr("wardrobe.currentCard"), x + 3, y + h - 11, w - 6, sel ? t.text : ColorMath.lerp(t.text, t.textDim, 0.3f), false);
		hits.add(x, y, w, h, () -> {
			host.playClick();
			selectedSkin = CurrentSkin.ID;
			lastInteraction = System.currentTimeMillis();
		});
	}

	private void addCard(Canvas c, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		boolean hov = inside(mx, my, x, y, w, h);
		Redstone.stone(c, x, y, w, h, hov ? t.surfaceHover : t.surface, hov ? t.accent : t.border);
		Icons.draw(c, "plus", x + w / 2 - 8, y + h / 2 - 14, 2, hov ? t.dustOn : t.textDim);
		List<String> lines = Paint.wrap(c, I18n.tr("wardrobe.addSkin"), w - 6);
		int ly = y + h / 2 + 6;
		for (int i = 0; i < lines.size() && i < 2; i++) {
			String l = lines.get(i);
			c.text(l, x + (w - c.textWidth(l)) / 2, ly, hov ? t.text : t.textDim, false);
			ly += 10;
		}
		hits.add(x, y, w, h, () -> {
			host.playClick();
			addMenu = true;
			addAnchor[0] = x;
			addAnchor[1] = y + h / 2;
		});
	}

	private void skinCard(Canvas c, WardrobeService.State s, final WardrobeService.Skin sk, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		boolean sel = sk.id.equals(selectedSkin);
		boolean hov = inside(mx, my, x, y, w, h);
		if (sel) Redstone.glow(c, x, y, w, h, t.glow, 0.45f);
		Redstone.stone(c, x, y, w, h, hov ? t.surfaceHover : t.surface, sel ? ColorMath.lerp(t.border, t.accent, 0.8f) : t.border);
		TextureRef tex = textures.get("wardrobe/s_" + sk.id, sk.pixels, 64, 64, 0);
		SkinDraw.doll(c, tex, sk.pixels, sk.slim, x + 3, y + 4, w - 6, h - 17);
		Paint.textClipped(c, sk.name, x + 3, y + h - 11, w - 6, sel ? t.text : ColorMath.lerp(t.text, t.textDim, 0.3f), false);
		hits.add(x, y, w, h, () -> {
			host.playClick();
			selectedSkin = sk.id;
			lastInteraction = System.currentTimeMillis();
		});
		// Gleich dem getragenen Skin: Hinweis statt zweiter Lampe (die hat nur „Aktueller Skin“).
		// Als kleines Schild über den Beinen der Figur (der Kopf bleibt frei).
		if (current != null && current.sameAs(sk)) {
			String hint = I18n.tr("wardrobe.sameAsCurrent");
			int hw = Math.min(c.textWidth(hint) + 4, w - 2);
			int hy = y + h - 23;
			c.fill(x + 1, hy, x + 1 + hw, hy + 10, ColorMath.withAlpha(t.surfaceHigh, 0xE0));
			Paint.textClipped(c, hint, x + 3, hy + 1, hw - 2, t.dustOn, false);
		}
		// Herz
		boolean fav = s.doc.favorite(sk.id);
		int hx = x + w - 12;
		int hy = y + 3;
		boolean hh = inside(mx, my, hx - 1, hy - 1, 10, 10);
		if (fav || hov) Icons.draw(c, fav ? "heart" : "heartOutline", hx, hy, 1, fav ? (hh ? t.lampHot : t.dustOn) : (hh ? t.dustOn : t.textDim));
		hits.add(hx - 1, hy - 1, 10, 10, () -> {
			host.playClick();
			service.toggleFavorite(sk.id);
		});
	}

	private int outfits(Canvas c, WardrobeService.State s, int x, int y, int w, int mx, int my) {
		int start = y;
		int cols = columns(w);
		int cw = (w - (cols - 1) * GAP) / cols;
		int ch = Math.round(cw * 1.3f);
		y = section(c, I18n.tr("wardrobe.outfits") + "  " + s.doc.outfits.size() + "/" + WardrobeDoc.MAX_OUTFITS, x, y, w);
		// „+“ = aktuelles Aussehen speichern
		Theme t = Theme.get();
		boolean hov = inside(mx, my, x, y, cw, ch);
		Redstone.stone(c, x, y, cw, ch, hov ? t.surfaceHover : t.surface, hov ? t.accent : t.border);
		Icons.draw(c, "plus", x + cw / 2 - 8, y + ch / 2 - 14, 2, hov ? t.dustOn : t.textDim);
		List<String> lines = Paint.wrap(c, I18n.tr("wardrobe.saveOutfit"), cw - 6);
		int ly = y + ch / 2 + 6;
		for (int i = 0; i < lines.size() && i < 2; i++) {
			c.text(lines.get(i), x + (cw - c.textWidth(lines.get(i))) / 2, ly, hov ? t.text : t.textDim, false);
			ly += 10;
		}
		hits.add(x, y, cw, ch, () -> {
			host.playClick();
			startNewOutfit(s);
		});
		for (int i = 0; i < s.doc.outfits.size(); i++) {
			int k = i + 1;
			outfitCard(c, s, s.doc.outfits.get(i), x + (k % cols) * (cw + GAP), y + (k / cols) * (ch + GAP), cw, ch, mx, my);
		}
		y += ((s.doc.outfits.size() + 1 + cols - 1) / cols) * (ch + GAP);
		return y - start;
	}

	private void outfitCard(Canvas c, WardrobeService.State s, final WardrobeDoc.Outfit o, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		boolean sel = o.id.equals(selectedOutfit);
		boolean hov = inside(mx, my, x, y, w, h);
		if (sel) Redstone.glow(c, x, y, w, h, t.glow, 0.45f);
		Redstone.stone(c, x, y, w, h, hov ? t.surfaceHover : t.surface, sel ? ColorMath.lerp(t.border, t.accent, 0.8f) : t.border);
		WardrobeService.Skin sk = s.skin(o.skin);
		if (sk != null) {
			TextureRef tex = textures.get("wardrobe/s_" + sk.id, sk.pixels, 64, 64, 0);
			SkinDraw.doll(c, tex, sk.pixels, sk.slim, x + 3, y + 4, w - 6, h - 17);
		} else {
			PlayerLook look = host.look();
			if (look != null && look.skin != null) SkinDraw.doll(c, look.skin, null, look.slim, x + 3, y + 4, w - 6, h - 17);
		}
		TextureRef cape = capeTexture(s, o.cape);
		if (cape != null) SkinDraw.cape(c, cape, x + w - 14, y + 4, 10, 16);
		Paint.textClipped(c, o.name, x + 3, y + h - 11, w - 6, sel ? t.text : ColorMath.lerp(t.text, t.textDim, 0.3f), false);
		hits.add(x, y, w, h, () -> {
			host.playClick();
			selectedOutfit = o.id;
		});
	}

	private int capes(Canvas c, WardrobeService.State s, int x, int y, int w, int mx, int my) {
		int start = y;
		int cols = columns(w);
		int cw = (w - (cols - 1) * GAP) / cols;
		int ch = Math.round(cw * 1.3f);
		Theme t = Theme.get();
		Friends f = s.trs ? friends() : null;
		List<CapeShare.Offer> offers = f == null ? new ArrayList<CapeShare.Offer>() : f.snapshot().offerList();
		if (!offers.isEmpty()) {
			y = section(c, I18n.tr("wardrobe.share.offers", offers.size()), x, y, w);
			for (int i = 0; i < offers.size(); i++) {
				offerCard(c, s, f, offers.get(i), x + (i % cols) * (cw + GAP), y + (i / cols) * (ch + GAP), cw, ch, mx, my);
			}
			y += ((offers.size() + cols - 1) / cols) * (ch + GAP) + 2;
		}
		y = section(c, I18n.tr("wardrobe.capes.minecraft"), x, y, w);
		List<WardrobeService.Cape> list = new ArrayList<WardrobeService.Cape>();
		if (s.mojangCapes != null) list.addAll(s.mojangCapes);
		capeCard(c, s, null, x, y, cw, ch, mx, my);
		for (int i = 0; i < list.size(); i++) {
			int k = i + 1;
			capeCard(c, s, list.get(i), x + (k % cols) * (cw + GAP), y + (k / cols) * (ch + GAP), cw, ch, mx, my);
		}
		y += ((list.size() + 1 + cols - 1) / cols) * (ch + GAP);
		if (s.mojangCapes == null) {
			y = note(c, I18n.tr(s.session ? "wardrobe.capes.loading" : "wardrobe.capes.noSession"), x, y, w);
		}
		y += 2;
		y = section(c, I18n.tr("wardrobe.capes.trs"), x, y, w);
		if (s.trsCapes == null) {
			y = note(c, I18n.tr("wardrobe.capes.trsOff"), x, y, w);
		} else if (s.trsCapes.isEmpty()) {
			y = note(c, I18n.tr("wardrobe.capes.trsNone"), x, y, w);
		} else {
			for (int i = 0; i < s.trsCapes.size(); i++) {
				capeCard(c, s, s.trsCapes.get(i), x + (i % cols) * (cw + GAP), y + (i / cols) * (ch + GAP), cw, ch, mx, my);
			}
			y += ((s.trsCapes.size() + cols - 1) / cols) * (ch + GAP);
		}
		y = note(c, I18n.tr("wardrobe.capes.uploadHint"), x, y + 2, w);
		return y - start;
	}

	private int note(Canvas c, String text, int x, int y, int w) {
		for (String l : Paint.wrap(c, text, w - 4)) {
			c.text(l, x + 2, y, Theme.get().textDim, false);
			y += 10;
		}
		return y + 2;
	}

	private void capeCard(Canvas c, WardrobeService.State s, final WardrobeService.Cape cape, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		final String key = cape == null ? "none" : cape.key;
		boolean sel = key.equals(selectedCape);
		boolean hov = inside(mx, my, x, y, w, h);
		boolean active = cape == null ? s.activeMojangCape == null && s.activeTrsCape == null
				: key.equals(s.activeMojangCape) || key.equals(s.activeTrsCape);
		if (sel) Redstone.glow(c, x, y, w, h, t.glow, 0.45f);
		Redstone.stone(c, x, y, w, h, hov ? t.surfaceHover : t.surface, sel ? ColorMath.lerp(t.border, t.accent, 0.8f) : t.border);
		if (cape == null) {
			Icons.draw(c, "close", x + w / 2 - 8, y + (h - 12) / 2 - 8, 2, t.textDim);
		} else {
			TextureRef tex = capeTexture(s, key);
			if (tex != null) SkinDraw.cape(c, tex, x + 4, y + 4, w - 8, h - 17);
			else Icons.draw(c, "cape", x + w / 2 - 8, y + (h - 12) / 2 - 8, 2, t.textDim);
		}
		if (active) Redstone.pip(c, x + 3, y + 3, 6, 1f);
		// Geteilt bekommen bzw. mit Freunden geteilt: kleines Freunde-Symbol oben rechts.
		if (cape != null && (cape.shared() || cape.holders > 0)) {
			Icons.draw(c, "friends", x + w - 11, y + 3, 1, cape.shared() ? t.dustOn : t.textDim);
		}
		String name = cape == null ? I18n.tr("wardrobe.capes.none") : cape.name;
		Paint.textClipped(c, name, x + 3, y + h - 11, w - 6, sel ? t.text : ColorMath.lerp(t.text, t.textDim, 0.3f), false);
		hits.add(x, y, w, h, () -> {
			host.playClick();
			selectedCape = key;
		});
	}

	/** Karte eines Umhang-Angebots (Vorschau, „NEU“ bis angesehen). */
	private void offerCard(Canvas c, WardrobeService.State s, Friends f, final CapeShare.Offer o, int x, int y, int w, int h,
			int mx, int my) {
		Theme t = Theme.get();
		final String key = WardrobeService.offerKey(o.capeId);
		boolean sel = key.equals(selectedCape);
		boolean hov = inside(mx, my, x, y, w, h);
		if (sel) Redstone.glow(c, x, y, w, h, t.glow, 0.45f);
		Redstone.stone(c, x, y, w, h, hov ? t.surfaceHover : t.surface, sel ? ColorMath.lerp(t.border, t.accent, 0.8f)
				: ColorMath.lerp(t.border, t.dustOn, 0.5f));
		TextureRef tex = capeTexture(s, key);
		if (tex != null) SkinDraw.cape(c, tex, x + 4, y + 4, w - 8, h - 17);
		else Icons.draw(c, "cape", x + w / 2 - 8, y + (h - 12) / 2 - 8, 2, t.textDim);
		if (f.unseen(o)) NewBadge.draw(c, x + 2, y + 2);
		Paint.textClipped(c, o.capeName, x + 3, y + h - 11, w - 6, sel ? t.text : ColorMath.lerp(t.text, t.textDim, 0.3f), false);
		hits.add(x, y, w, h, () -> {
			host.playClick();
			selectedCape = key;
		});
	}

	private int emotes(Canvas c, WardrobeService.State s, int x, int y, int w, int mx, int my) {
		int start = y;
		Theme t = Theme.get();
		y = section(c, I18n.tr("wardrobe.emotes.wheel"), x, y, w);
		String[] slots = s.doc.slotArray();
		int slot = Math.max(16, Math.min(24, (w - 7 * 3) / WardrobeDoc.MAX_EMOTE_SLOTS));
		for (int i = 0; i < slots.length; i++) {
			final int idx = i;
			int sx = x + i * (slot + 3);
			boolean sel = selectedSlot == i;
			boolean hov = inside(mx, my, sx, y, slot, slot);
			if (sel) Redstone.glow(c, sx, y, slot, slot, t.glow, 0.5f);
			Redstone.well(c, sx, y, slot, slot, sel ? t.accent : (hov ? t.textDim : t.border));
			EmoteDef d = slots[i] == null ? null : Emotes.byId(slots[i]);
			if (d != null) Icons.draw(c, d.icon(), sx + (slot - 8) / 2, y + (slot - 8) / 2, 1, t.text);
			else c.text(String.valueOf(i + 1), sx + (slot - c.textWidth(String.valueOf(i + 1))) / 2 + 1, y + (slot - 8) / 2 + 1, t.textDim, false);
			hits.add(sx, y, slot, slot, () -> {
				host.playClick();
				selectedSlot = selectedSlot == idx ? -1 : idx;
			});
			hits.add(sx, y, slot, slot, 1, () -> {
				host.playClick();
				service.setEmoteSlot(idx, null);
			});
		}
		y += slot + 3;
		y = note(c, I18n.tr(selectedSlot >= 0 ? "wardrobe.emotes.pickHint" : "wardrobe.emotes.slotHint"), x, y, w);
		y = section(c, I18n.tr("wardrobe.emotes.all"), x, y, w);
		EmoteController ctrl = emoteController();
		int rowH = 16;
		int cols = w >= 200 ? 2 : 1;
		int cw = (w - (cols - 1) * GAP) / cols;
		List<EmoteDef> all = Emotes.ALL;
		for (int i = 0; i < all.size(); i++) {
			final EmoteDef d = all.get(i);
			int ex = x + (i % cols) * (cw + GAP);
			int ey = y + (i / cols) * (rowH + 2);
			boolean sel = d.id().equals(selectedEmote);
			boolean hov = inside(mx, my, ex, ey, cw, rowH);
			boolean locked = ctrl != null && isKnown(ctrl) && !ctrl.unlocked(d.id());
			Redstone.stone(c, ex, ey, cw, rowH, hov ? t.surfaceHover : t.surface, sel ? t.accent : t.border);
			Icons.draw(c, d.icon(), ex + 4, ey + 4, 1, locked ? t.textDim : (sel ? t.dustOn : t.text));
			int inWheel = -1;
			for (int k = 0; k < slots.length; k++) if (d.id().equals(slots[k])) inWheel = k;
			int right = ex + cw - 4;
			if (locked) {
				Icons.draw(c, "lock", right - 8, ey + 4, 1, t.textDim);
				right -= 11;
			}
			if (inWheel >= 0) {
				String n = String.valueOf(inWheel + 1);
				Paint.textRight(c, n, right, ey + 4, t.dustOn, false);
				right -= c.textWidth(n) + 4;
			}
			Paint.textClipped(c, I18n.trOr(d.nameKey(), d.id()), ex + 16, ey + 4, right - ex - 18, locked ? t.textDim : t.text, false);
			hits.add(ex, ey, cw, rowH, () -> {
				host.playClick();
				selectedEmote = d.id();
				emoteStart = System.currentTimeMillis();
				if (selectedSlot >= 0) {
					service.setEmoteSlot(selectedSlot, d.id());
					selectedSlot = selectedSlot + 1 < WardrobeDoc.MAX_EMOTE_SLOTS ? selectedSlot + 1 : -1;
				}
			});
		}
		y += ((all.size() + cols - 1) / cols) * (rowH + 2);
		return y - start;
	}

	private static boolean isKnown(EmoteController ctrl) {
		// Ohne bekannte Liste (offline) nichts als gesperrt zeigen.
		return ctrl.unlocked("winken") || ctrl.unlocked("klatschen");
	}

	private EmoteController emoteController() {
		OnlineFeatures<?> f = host.features();
		return f == null ? null : f.emotes();
	}

	// --- Vorschau ---

	private void preview(Canvas c, WardrobeService.State s, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		previewRect[0] = x;
		previewRect[1] = y;
		previewRect[2] = w;
		previewRect[3] = h;
		Redstone.well(c, x, y, w, h, t.border);
		PlayerLook look = host.look();
		// Was zeigen?
		TextureRef skin = look == null ? null : look.skin;
		boolean slim = look != null && look.slim;
		TextureRef cape = look == null ? null : look.cape;
		String title;
		String subtitle = null;
		float targetYaw = 20f;
		spec.pose = null;
		switch (category) {
			case OUTFITS: {
				WardrobeDoc.Outfit o = s.outfit(selectedOutfit);
				if (o == null && !s.doc.outfits.isEmpty()) {
					o = s.doc.outfits.get(0);
					selectedOutfit = o.id;
				}
				if (o != null) {
					WardrobeService.Skin sk = s.skin(o.skin);
					if (sk != null) {
						skin = textures.get("wardrobe/s_" + sk.id, sk.pixels, 64, 64, 0);
						slim = sk.slim;
					}
					if (o.cape != null) cape = "none".equals(o.cape) ? null : capeTexture(s, o.cape);
					title = o.name;
				} else {
					title = I18n.tr("wardrobe.outfits.none");
				}
				break;
			}
			case CAPES: {
				String key = selectedCape;
				if (key != null) cape = "none".equals(key) ? null : capeTexture(s, key);
				title = key == null ? I18n.tr("wardrobe.cat.capes") : capeName(s, key);
				CapeShare.Offer o = offer(friends() == null ? null : friends().snapshot(), key);
				WardrobeService.Cape tc = trsCape(s, key);
				if (o != null) {
					title = o.capeName;
					subtitle = o.reshared() ? I18n.tr("wardrobe.share.fromVia", o.fromName, o.creatorName)
							: I18n.tr("wardrobe.share.from", o.fromName);
				} else if (tc != null && tc.shared()) {
					subtitle = tc.sharedCreator.equals(tc.sharedFrom) ? I18n.tr("wardrobe.share.sharedBy", tc.sharedFrom)
							: I18n.tr("wardrobe.share.sharedByVia", tc.sharedFrom, tc.sharedCreator);
				} else if (tc != null && tc.holders > 0) {
					subtitle = I18n.tr("wardrobe.share.holderCount", tc.holders);
				}
				targetYaw = 160f;
				break;
			}
			case EMOTES: {
				EmoteDef d = Emotes.byId(selectedEmote);
				if (d != null) {
					applyEmote(d, slim);
					title = I18n.trOr(d.nameKey(), d.id());
				} else {
					title = I18n.tr("wardrobe.cat.emotes");
				}
				break;
			}
			default: {
				WardrobeService.Skin sk = s.skin(selectedSkin);
				if (sk != null) {
					skin = textures.get("wardrobe/s_" + sk.id, sk.pixels, 64, 64, 0);
					slim = sk.slim;
					title = sk.name;
					subtitle = I18n.tr(sk.slim ? "wardrobe.slim" : "wardrobe.classic")
							+ (sk.launcherOnly ? " · " + I18n.tr("wardrobe.fromLauncher") : "");
				} else {
					// Aktueller Skin (Standard-Auswahl)
					TextureRef cur = currentTexture(look);
					if (cur != null) skin = cur;
					if (current != null && current.pixels != null) slim = current.slim;
					title = I18n.tr("wardrobe.current");
					String model = I18n.tr(slim ? "wardrobe.slim" : "wardrobe.classic");
					subtitle = current != null && current.source == CurrentSkin.Source.DEFAULT
							? I18n.tr("wardrobe.defaultSkin") : model;
					if (look != null && look.name != null) subtitle = look.name + " · " + subtitle;
				}
				break;
			}
		}
		// Kopf
		int ty = y + 5;
		if (category == Category.OUTFITS) {
			int ab = 14;
			boolean hp = inside(mx, my, x + 4, ty - 2, ab, ab);
			boolean hn = inside(mx, my, x + w - 4 - ab, ty - 2, ab, ab);
			Paint.iconButton(c, x + 4, ty - 2, ab, "prev", hp, false);
			Paint.iconButton(c, x + w - 4 - ab, ty - 2, ab, "next", hn, false);
			hits.add(x + 4, ty - 2, ab, ab, () -> stepOutfit(s, -1));
			hits.add(x + w - 4 - ab, ty - 2, ab, ab, () -> stepOutfit(s, 1));
			String tt = c.clip(title, w - 2 * ab - 14);
			c.text(tt, x + (w - c.textWidth(tt)) / 2, ty + 1, t.text, false);
		} else {
			String tt = c.clip(title, w - 10);
			c.text(tt, x + (w - c.textWidth(tt)) / 2, ty, t.text, false);
		}
		if (subtitle != null) {
			String st = c.clip(subtitle, w - 10);
			c.text(st, x + (w - c.textWidth(st)) / 2, ty + 11, t.textDim, false);
		}
		// Knöpfe unten
		int buttonsH = buttons(c, s, x + 5, y + h - 5, w - 10, mx, my);
		// Figur
		int figTop = y + 28;
		int figBottom = y + h - 8 - buttonsH;
		long now = System.currentTimeMillis();
		if (!draggingPreview) {
			if (Math.abs(yawVelocity) > 0.5f) {
				yaw += yawVelocity * dt;
				yawVelocity *= (float) Math.pow(0.05, dt);
			} else if (now - lastInteraction > 2500) {
				float diff = ((targetYaw - yaw) % 360f + 540f) % 360f - 180f;
				yaw += diff * Math.min(1f, dt * 2.5f);
			}
		}
		spec.skin = skin;
		spec.slim = slim;
		spec.cape = cape;
		spec.yaw = yaw;
		spec.pitch = 8f;
		spec.headYaw = 0f;
		spec.headPitch = 0f;
		spec.walkAmount = 0f;
		spec.idleTime = now / 1000f % 1000f;
		spec.layers = true;
		spec.base = true;
		spec.tint = 0xFFFFFFFF;
		if (skin != null && figBottom - figTop > 30) {
			c.scissor(x + 1, figTop - 4, x + w - 1, figBottom + 2);
			model.drawFitted(c, x + 6, figTop, w - 12, figBottom - figTop, spec);
			c.noScissor();
		} else if (skin == null) {
			Paint.textCentered(c, c.clip(I18n.tr("wardrobe.loading"), w - 8), x + w / 2, (figTop + figBottom) / 2, t.textDim, false);
		}
		previewFig[0] = x;
		previewFig[1] = figTop;
		previewFig[2] = w;
		previewFig[3] = Math.max(0, figBottom - figTop);
	}

	private final int[] previewFig = new int[4];

	private void applyEmote(EmoteDef d, boolean slim) {
		float total = d.durationMs();
		long now = System.currentTimeMillis();
		float elapsed = (now - emoteStart) % (long) (total + 800);
		SkinModel.restPose(slim, pose);
		if (elapsed <= total) {
			d.sample(elapsed, emoteFrame);
			rig.apply(pose, emoteFrame, d.mask(), EmoteDef.weight(elapsed, total));
		}
		spec.pose = pose;
	}

	private void stepOutfit(WardrobeService.State s, int dir) {
		host.playClick();
		List<WardrobeDoc.Outfit> list = s.doc.outfits;
		if (list.isEmpty()) return;
		int idx = 0;
		for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(selectedOutfit)) idx = i;
		idx = (idx + dir + list.size()) % list.size();
		selectedOutfit = list.get(idx).id;
	}

	/** Aktionsknöpfe der Vorschau; liefert ihre Höhe. Unterkante bei {@code bottom}. */
	private int buttons(Canvas c, final WardrobeService.State s, int x, int bottom, int w, int mx, int my) {
		Theme t = Theme.get();
		int bh = 16;
		int y = bottom - bh;
		boolean busy = s.busy();
		switch (category) {
			case SKINS: {
				final WardrobeService.Skin sk = s.skin(selectedSkin);
				if (sk == null) {
					// Aktueller Skin: bearbeiten (als Vorlage für einen neuen Bibliotheks-Skin) und in die Bibliothek sichern.
					final CurrentSkin cur = current;
					final boolean known = cur != null && cur.pixels != null;
					final String name = currentName(host.look());
					button(c, x, y, w, bh, I18n.tr("wardrobe.edit"), false, known, mx, my,
							() -> openEditor(cur.pixels, cur.slim, name, null));
					int ay = y - bh - 3;
					if (known && cur.twin != null) {
						button(c, x, ay, w, bh, I18n.tr("wardrobe.inLibrary"), false, true, mx, my, () -> selectedSkin = cur.twin);
					} else {
						button(c, x, ay, w, bh, I18n.tr("wardrobe.saveCurrent"), true, known && !busy, mx, my,
								() -> service.saveCurrent(cur.pixels, cur.slim, name));
					}
					int hint = known ? 0 : hintAbove(c, I18n.tr("wardrobe.currentUnknown"), x, ay, w);
					return bh * 2 + 3 + hint;
				}
				// Symbolzeile: Herz, Umbenennen, Löschen
				int iy = y;
				int is = 16;
				int ix = x + w - is;
				if (!sk.launcherOnly) {
					boolean armed = sk.id.equals(armedDelete) && System.currentTimeMillis() < armedUntil;
					boolean hd = inside(mx, my, ix, iy, is, is);
					Paint.iconButton(c, ix, iy, is, "trash", hd, armed);
					hits.add(ix, iy, is, is, () -> {
						host.playClick();
						if (sk.id.equals(armedDelete) && System.currentTimeMillis() < armedUntil) {
							armedDelete = null;
							service.delete(sk.id);
							selectedSkin = null;
						} else {
							armedDelete = sk.id;
							armedUntil = System.currentTimeMillis() + 3000;
							showToast(I18n.tr("wardrobe.deleteHint"));
						}
					});
					ix -= is + 3;
					boolean hr = inside(mx, my, ix, iy, is, is);
					Paint.iconButton(c, ix, iy, is, "pencil", hr, false);
					hits.add(ix, iy, is, is, () -> {
						host.playClick();
						openInput(Input.RENAME_SKIN, sk.id, sk.name);
					});
					ix -= is + 3;
				}
				boolean fav = s.doc.favorite(sk.id);
				boolean hf = inside(mx, my, ix, iy, is, is);
				Paint.iconButton(c, ix, iy, is, fav ? "heart" : "heartOutline", hf, fav);
				hits.add(ix, iy, is, is, () -> {
					host.playClick();
					service.toggleFavorite(sk.id);
				});
				int ew = ix - 3 - x;
				button(c, x, iy, ew, bh, I18n.tr("wardrobe.edit"), false, true, mx, my,
						() -> openEditor(sk.pixels, sk.slim, sk.name, sk.launcherOnly ? null : sk.id));
				int ay = iy - bh - 3;
				boolean worn = current != null && current.sameAs(sk);
				button(c, x, ay, w, bh, I18n.tr(worn ? "wardrobe.worn" : "wardrobe.apply"), !worn, s.session && !busy && !worn, mx, my,
						() -> service.apply(sk.id));
				int hint = s.session ? 0 : hintAbove(c, I18n.tr("wardrobe.needSession"), x, ay, w);
				return bh * 2 + 3 + hint;
			}
			case OUTFITS: {
				final WardrobeDoc.Outfit o = s.outfit(selectedOutfit);
				if (o == null) return 0;
				int is = 16;
				int ix = x + w - is;
				boolean armed = o.id.equals(armedDelete) && System.currentTimeMillis() < armedUntil;
				Paint.iconButton(c, ix, y, is, "trash", inside(mx, my, ix, y, is, is), armed);
				hits.add(ix, y, is, is, () -> {
					host.playClick();
					if (o.id.equals(armedDelete) && System.currentTimeMillis() < armedUntil) {
						armedDelete = null;
						service.deleteOutfit(o.id);
						selectedOutfit = null;
					} else {
						armedDelete = o.id;
						armedUntil = System.currentTimeMillis() + 3000;
						showToast(I18n.tr("wardrobe.deleteHint"));
					}
				});
				ix -= is + 3;
				Paint.iconButton(c, ix, y, is, "pencil", inside(mx, my, ix, y, is, is), false);
				hits.add(ix, y, is, is, () -> {
					host.playClick();
					openInput(Input.RENAME_OUTFIT, o.id, o.name);
				});
				button(c, x, y, ix - 3 - x, bh, I18n.tr("wardrobe.outfits.wear"), true, !busy, mx, my, () -> service.applyOutfit(o.id));
				return bh;
			}
			case CAPES: {
				final String key = selectedCape;
				if (key == null) return 0;
				final Friends f = friends();
				final CapeShare.Offer o = offer(f == null ? null : f.snapshot(), key);
				if (o != null) {
					// Angebot: annehmen / ablehnen
					boolean fbusy = f.snapshot().busy != null;
					int half = (w - 3) / 2;
					button(c, x, y, half, bh, I18n.tr("wardrobe.share.decline"), false, !fbusy, mx, my, () -> {
						f.declineOffer(o);
						selectedCape = null;
					});
					button(c, x + half + 3, y, w - half - 3, bh, I18n.tr("wardrobe.share.accept"), true, !fbusy, mx, my,
							() -> f.acceptOffer(o));
					return bh;
				}
				if (key.startsWith("offer:")) {
					selectedCape = null;
					return 0;
				}
				boolean active = "none".equals(key) ? s.activeMojangCape == null && s.activeTrsCape == null
						: key.equals(s.activeMojangCape) || key.equals(s.activeTrsCape);
				boolean possible = key.startsWith("trs:") ? s.trs : (key.startsWith("mojang:") ? s.session : true);
				button(c, x, y, w, bh, I18n.tr(active ? "wardrobe.worn" : "wardrobe.capes.wear"), !active, !active && possible && !busy,
						mx, my, () -> service.wearCape(key));
				final WardrobeService.Cape tc = trsCape(s, key);
				if (tc == null || f == null || !s.trs) return bh;
				int used = bh;
				int ay = y - bh - 3;
				if (tc.shared()) {
					// Geteilt bekommen: zurückgeben (zweiter Klick bestätigt)
					final String armKey = "giveBack:" + tc.key;
					boolean armed = armKey.equals(armedDelete) && System.currentTimeMillis() < armedUntil;
					button(c, x, ay, w, bh, I18n.tr(armed ? "wardrobe.share.confirm" : "wardrobe.share.giveBack"), false,
							f.snapshot().busy == null, mx, my, () -> {
								if (armKey.equals(armedDelete) && System.currentTimeMillis() < armedUntil) {
									armedDelete = null;
									String self = ownUuid();
									if (self != null) f.revokeShare(tc.id(), self, tc.name, false, true);
								} else {
									armedDelete = armKey;
									armedUntil = System.currentTimeMillis() + 3000;
									showToast(I18n.tr("wardrobe.share.giveBackHint"));
								}
							});
					used += bh + 3;
					ay -= bh + 3;
				}
				if (tc.shareable) {
					String label = tc.holders > 0 ? I18n.tr("wardrobe.share.manage", tc.holders) : I18n.tr("wardrobe.share.button");
					button(c, x, ay, w, bh, label, false, true, mx, my, () -> openShare(tc));
					if (isNew(NewSince.WARDROBE_CAPE_SHARE)) NewBadge.draw(c, x + w - NewBadge.width(c) - 2, ay - 5);
					used += bh + 3;
				} else if (tc.own && tc.pending) {
					used += hintAbove(c, I18n.tr("wardrobe.share.afterApproval"), x, ay + bh, w);
				}
				return used;
			}
			case EMOTES: {
				if (selectedEmote == null) return 0;
				button(c, x, y, w, bh, I18n.tr("wardrobe.emotes.replay"), false, true, mx, my,
						() -> emoteStart = System.currentTimeMillis());
				return bh;
			}
			default:
				return 0;
		}
	}

	/** Hinweis über einem Knopf; liefert die belegte Höhe. */
	private int hintAbove(Canvas c, String text, int x, int y, int w) {
		List<String> lines = Paint.wrap(c, text, w);
		int ly = y - 3 - lines.size() * 10;
		for (String l : lines) {
			c.text(l, x + (w - c.textWidth(l)) / 2, ly, Theme.get().textDim, false);
			ly += 10;
		}
		return lines.size() * 10 + 3;
	}

	private void button(Canvas c, int x, int y, int w, int h, String label, boolean primary, boolean enabled, int mx, int my,
			final Runnable action) {
		Theme t = Theme.get();
		boolean hov = enabled && inside(mx, my, x, y, w, h);
		if (enabled) {
			Redstone.button(c, x, y, w, h, label, primary, hov);
			hits.add(x, y, w, h, () -> {
				host.playClick();
				action.run();
			});
		} else {
			Redstone.stone(c, x, y, w, h, t.surface, t.border);
			String s = c.clip(label, w - 6);
			c.text(s, x + (w - c.textWidth(s)) / 2, y + (h - 8) / 2, t.textDim, false);
		}
	}

	private TextureRef capeTexture(WardrobeService.State s, String key) {
		if (key == null || "none".equals(key)) return null;
		WardrobeService.CapeArt art = s.capeArt.get(key);
		if (art == null) return null;
		String name = "wardrobe/c_" + key.replace(':', '_').toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
		return textures.get(name, art.argb, art.width, art.height, 0);
	}

	private static String capeName(WardrobeService.State s, String key) {
		if ("none".equals(key)) return I18n.tr("wardrobe.capes.none");
		List<WardrobeService.Cape> all = new ArrayList<WardrobeService.Cape>();
		if (s.mojangCapes != null) all.addAll(s.mojangCapes);
		if (s.trsCapes != null) all.addAll(s.trsCapes);
		for (WardrobeService.Cape c : all) if (c.key.equals(key)) return c.name;
		return key;
	}

	private void openEditor(int[] pixels, boolean slim, String name, String id) {
		if (pixels == null) return;
		editorView.load(pixels, slim, name, id);
		editing = true;
		awaitingSave = false;
	}

	private void startNewOutfit(WardrobeService.State s) {
		openInput(Input.NEW_OUTFIT, null, I18n.tr("wardrobe.outfits.defaultName", s.doc.outfits.size() + 1));
	}

	/** Aktuelles Aussehen als Outfit: getragener Skin (falls in der Bibliothek, sonst der gewählte) + Umhang. */
	private void saveCurrentOutfit(String name) {
		WardrobeService.State s = service.state();
		String skinId = current == null ? null : current.twin;
		if (skinId == null && s.skin(selectedSkin) != null) skinId = selectedSkin;
		String cape = s.activeTrsCape != null ? s.activeTrsCape : (s.activeMojangCape != null ? s.activeMojangCape : "none");
		service.saveOutfit(name, skinId, cape);
	}

	// --- Dialoge ---

	private void drawToast(Canvas c, int x, int y, int w) {
		if (toast == null || System.currentTimeMillis() > toastUntil) {
			toast = null;
			return;
		}
		Theme t = Theme.get();
		int tw = Math.min(w - 20, c.textWidth(toast) + 16);
		int tx = x + (w - tw) / 2;
		c.flush();
		c.push();
		c.raise(40f);
		Redstone.stone(c, tx, y, tw, 16, t.surfaceHigh, t.accent);
		Paint.textClipped(c, toast, tx + 8, y + 4, tw - 12, t.text, false);
		c.pop();
	}

	private void addMenu(Canvas c, int mx, int my) {
		Theme t = Theme.get();
		String[] keys = {"wardrobe.add.file", "wardrobe.add.url", "wardrobe.add.name", "wardrobe.add.paint"};
		String[] icons = {"folder", "link", "user", "brush"};
		int w = 40;
		for (String k : keys) w = Math.max(w, c.textWidth(I18n.tr(k)) + 26);
		int h = keys.length * 16 + 4;
		int x = addAnchor[0] + 6;
		int y = addAnchor[1] - 8;
		menuRect[0] = x;
		menuRect[1] = y;
		menuRect[2] = w;
		menuRect[3] = h;
		c.flush();
		c.push();
		c.raise(60f);
		Redstone.glow(c, x, y, w, h, t.glow, 0.3f);
		Redstone.stone(c, x, y, w, h, t.surfaceHigh, t.accent);
		for (int i = 0; i < keys.length; i++) {
			final int idx = i;
			int ry = y + 2 + i * 16;
			boolean hov = inside(mx, my, x + 1, ry, w - 2, 16);
			if (hov) c.fill(x + 2, ry, x + w - 2, ry + 16, t.surfaceHover);
			Icons.draw(c, icons[i], x + 6, ry + 4, 1, hov ? t.dustOn : t.text);
			Paint.textClipped(c, I18n.tr(keys[i]), x + 18, ry + 4, w - 22, hov ? t.text : ColorMath.lerp(t.text, t.textDim, 0.2f), false);
			hits.add(x + 1, ry, w - 2, 16, () -> {
				host.playClick();
				addMenu = false;
				switch (idx) {
					case 0:
						service.importFile(I18n.tr("wardrobe.add.fileTitle"));
						break;
					case 1:
						openInput(Input.URL, null, "https://");
						break;
					case 2:
						openInput(Input.NAME, null, "");
						break;
					default:
						openEditor(SkinEditor.blankTemplate(false), false, I18n.tr("wardrobe.editor.newName"), null);
						break;
				}
			});
		}
		c.pop();
	}

	private void openInput(Input mode, String target, String initial) {
		input = mode;
		inputTarget = target;
		field.setText(initial);
		field.setFocused(true);
	}

	private void inputDialog(Canvas c, int width, int height, int mx, int my) {
		Theme t = Theme.get();
		String titleKey;
		String hintKey;
		switch (input) {
			case URL:
				titleKey = "wardrobe.add.url";
				hintKey = "wardrobe.add.urlHint";
				break;
			case NAME:
				titleKey = "wardrobe.add.name";
				hintKey = "wardrobe.add.nameHint";
				break;
			case NEW_OUTFIT:
				titleKey = "wardrobe.saveOutfit";
				hintKey = "wardrobe.outfits.saveHint";
				break;
			default:
				titleKey = "wardrobe.rename";
				hintKey = null;
				break;
		}
		int w = Math.min(width - 40, 260);
		int h = hintKey == null ? 64 : 80;
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		dialogRect[0] = x;
		dialogRect[1] = y;
		dialogRect[2] = w;
		dialogRect[3] = h;
		c.flush();
		c.push();
		c.raise(80f);
		c.fill(0, 0, width, height, 0x60000000);
		Redstone.window(c, x, y, w, h);
		Paint.textClipped(c, I18n.tr(titleKey), x + 8, y + 7, w - 16, t.text, false);
		int fy = y + 20;
		if (hintKey != null) {
			Paint.textClipped(c, I18n.tr(hintKey), x + 8, fy, w - 16, t.textDim, false);
			fy += 12;
		}
		Redstone.well(c, x + 8, fy, w - 16, 16, t.accent);
		String text = field.text();
		String shown = text;
		while (c.textWidth(shown) > w - 28 && shown.length() > 0) shown = shown.substring(1);
		c.text(shown, x + 12, fy + 4, t.text, false);
		if ((System.currentTimeMillis() / 500) % 2 == 0) {
			int visibleCursor = Math.max(0, shown.length() - (text.length() - field.cursor()));
			int cx = x + 12 + c.textWidth(shown.substring(0, Math.min(shown.length(), visibleCursor)));
			c.fill(cx, fy + 3, cx + 1, fy + 13, t.text);
		}
		int by = y + h - 22;
		int bw = (w - 24) / 2;
		boolean hc = inside(mx, my, x + 8, by, bw, 16);
		boolean ho = inside(mx, my, x + 16 + bw, by, bw, 16);
		Redstone.button(c, x + 8, by, bw, 16, I18n.tr("common.cancel"), false, hc);
		Redstone.button(c, x + 16 + bw, by, bw, 16, I18n.tr("wardrobe.ok"), true, ho);
		hits.add(x + 8, by, bw, 16, () -> {
			host.playClick();
			input = Input.NONE;
		});
		hits.add(x + 16 + bw, by, bw, 16, () -> {
			host.playClick();
			submitInput();
		});
		c.pop();
	}

	private final int[] menuRect = new int[4];
	private final int[] dialogRect = new int[4];

	private void submitInput() {
		String v = field.text().trim();
		Input mode = input;
		input = Input.NONE;
		switch (mode) {
			case URL:
				if (!v.isEmpty()) service.importUrl(v);
				break;
			case NAME:
				if (!v.isEmpty()) service.importName(v);
				break;
			case RENAME_SKIN:
				if (!v.isEmpty()) service.rename(inputTarget, v);
				break;
			case RENAME_OUTFIT:
				if (!v.isEmpty()) service.renameOutfit(inputTarget, v);
				break;
			case NEW_OUTFIT:
				saveCurrentOutfit(v.isEmpty() ? I18n.tr("wardrobe.outfits.defaultName", service.state().doc.outfits.size() + 1) : v);
				break;
			default:
				break;
		}
	}

	// ============================================================================================
	// Eingabe
	// ============================================================================================

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (sharePanel != null) {
			if (inside(mouseX, mouseY, shareRect[0], shareRect[1], shareRect[2], shareRect[3])) hits.click(mouseX, mouseY, button);
			else sharePanel = null;
			return true;
		}
		if (input != Input.NONE) {
			if (inside(mouseX, mouseY, dialogRect[0], dialogRect[1], dialogRect[2], dialogRect[3])) hits.click(mouseX, mouseY, button);
			return true;
		}
		if (addMenu) {
			if (inside(mouseX, mouseY, menuRect[0], menuRect[1], menuRect[2], menuRect[3])) hits.click(mouseX, mouseY, button);
			else addMenu = false;
			return true;
		}
		if (editing) {
			if (hits.click(mouseX, mouseY, button)) return true;
			return editorView.mouseClicked(mouseX, mouseY, button);
		}
		if (hits.click(mouseX, mouseY, button)) return true;
		if (inside(mouseX, mouseY, previewFig[0], previewFig[1], previewFig[2], previewFig[3])) {
			draggingPreview = true;
			lastDragX = mouseX;
			yawVelocity = 0f;
			lastInteraction = System.currentTimeMillis();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (editing && editorView.mouseDragged(mouseX, mouseY, button)) return true;
		if (draggingPreview) {
			float d = (float) (mouseX - lastDragX);
			yaw += d * 1.8f;
			yawVelocity = d * 60f;
			lastDragX = mouseX;
			lastInteraction = System.currentTimeMillis();
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		boolean was = editing && editorView.mouseReleased();
		if (draggingPreview) {
			draggingPreview = false;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button) || was;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (sharePanel != null) {
			shareScroll = Math.max(0, Math.min(shareMaxScroll, shareScroll - (int) Math.round(amount * 20)));
			return true;
		}
		if (input != Input.NONE || addMenu) return true;
		if (editing) return editorView.mouseScrolled(mouseX, mouseY, amount);
		if (inside(mouseX, mouseY, gridRect[0], gridRect[1], gridRect[2], gridRect[3])) {
			int i = category.ordinal();
			scroll[i] = Math.max(0, Math.min(maxScroll, scroll[i] - (int) Math.round(amount * 24)));
			return true;
		}
		if (inside(mouseX, mouseY, previewRect[0], previewRect[1], previewRect[2], previewRect[3])) {
			yaw += (float) amount * 20f;
			lastInteraction = System.currentTimeMillis();
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (sharePanel != null) {
			if (key == UiKey.ESCAPE) sharePanel = null;
			return true;
		}
		if (input != Input.NONE) {
			if (key == UiKey.ESCAPE) {
				input = Input.NONE;
				return true;
			}
			if (key == UiKey.ENTER) {
				submitInput();
				return true;
			}
			field.key(key);
			return true;
		}
		if (addMenu) {
			if (key == UiKey.ESCAPE) addMenu = false;
			return true;
		}
		if (editing) return editorView.keyPressed(key);
		if (key == UiKey.ESCAPE) {
			requestClose();
			return true;
		}
		if (key == UiKey.UP || key == UiKey.DOWN) {
			int n = Category.values().length;
			category = Category.values()[(category.ordinal() + (key == UiKey.DOWN ? 1 : n - 1)) % n];
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(char ch) {
		if (input != Input.NONE) return field.type(ch);
		if (editing) return editorView.charTyped(ch);
		return false;
	}

	@Override
	public boolean pausesGame() {
		return true;
	}
}
