package dev.theredstonee.trsclient.core.ui.clips;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.clips.ClipLibrary;
import dev.theredstonee.trsclient.core.clips.ClipNotice;
import dev.theredstonee.trsclient.core.clips.ClipPreview;
import dev.theredstonee.trsclient.core.clips.PreviewAnimation;
import dev.theredstonee.trsclient.core.clips.ClipStatus;
import dev.theredstonee.trsclient.core.clips.Clips;
import dev.theredstonee.trsclient.core.clips.Thumbnails;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.intro.IntroGate;
import dev.theredstonee.trsclient.core.module.NewSince;
import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.menu.NewBadge;
import dev.theredstonee.trsclient.core.ui.menus.WindowUi;
import dev.theredstonee.trsclient.core.util.OpenPath;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * „Clips &amp; Bilder“: gespeicherte Clips (vom TRS Launcher aufgenommen) und Bildschirmfotos als Kacheln mit
 * Vorschaubild, Aufnahme starten/stoppen und Clip speichern über die bestehende Verbindung zum Launcher,
 * Ordner öffnen, Bilder groß ansehen, Löschen mit Rückfrage. Dateien lesen und Bilder dekodieren läuft in einem
 * eigenen Hintergrund-Thread, im Render-Thread wird nur hochgeladen (höchstens zwei Bilder je Frame).
 */
public final class ClipsUi extends WindowUi {
	private static final int MIN_TILE_W = 112;
	private static final int GAP = 6;
	private static final long RESCAN_MS = 4_000L;

	enum Filter {
		ALL, CLIPS, SCREENSHOTS
	}

	private final ClipsHost host;
	private final ThreadPoolExecutor worker;
	private final ClipLibrary library;
	private final Thumbnails thumbs;
	private final Thumbnails large;
	private Filter filter = Filter.ALL;
	private int scroll;
	private int maxScroll;
	private final int[] gridRect = new int[4];
	private long lastScan;
	/** Groß angezeigtes Bild (Index in der gefilterten Liste) oder −1. */
	private int preview = -1;
	/** Rückfrage „Löschen?“ für diesen Eintrag. */
	private ClipLibrary.Entry confirmDelete;
	private String notice;
	private boolean noticeError;
	private long noticeAt;
	private volatile Boolean deleteResult;
	/** Clip in der Vorschau (Index in der gefilterten Liste) oder −1. */
	private int clipView = -1;
	private final ClipPreview clipPreview;
	/** Ergebnis von „Im Launcher öffnen“ (Netz-Thread → Render-Thread); "" = geklappt. */
	private volatile String openResult;

	public ClipsUi(ClipsHost host) {
		this.host = host;
		this.worker = new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(256), r -> {
			Thread t = new Thread(r, "TRS-Clips");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		});
		worker.allowCoreThreadTimeOut(true);
		this.library = new ClipLibrary(host.configDir(), host.gameDir(), worker);
		this.thumbs = new Thumbnails(worker, 224, 126, 48);
		this.large = new Thumbnails(worker, 1280, 720, 3);
		this.clipPreview = new ClipPreview(ClipPreview.shared(), worker);
		I18n.refresh();
		library.refresh();
		lastScan = System.currentTimeMillis();
	}

	/** Für den Selbsttest: Liste fertig durchsucht und sichtbare Vorschaubilder geladen? */
	public boolean settled() {
		if (!library.listing().scanned || library.scanning()) return false;
		for (ClipLibrary.Entry e : visible()) {
			if (e.type == ClipLibrary.Type.SCREENSHOT && !thumbs.settled(e.path)) return false;
		}
		return true;
	}

	/** Für den Selbsttest: erstes Bild groß anzeigen. */
	public void openFirstImage() {
		List<ClipLibrary.Entry> list = filtered();
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).type == ClipLibrary.Type.SCREENSHOT) {
				preview = i;
				return;
			}
		}
	}

	/** Für den Selbsttest: ersten Clip in der Vorschau öffnen. */
	public void openFirstClip() {
		List<ClipLibrary.Entry> list = filtered();
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).type == ClipLibrary.Type.CLIP) {
				openClip(i, list.get(i));
				return;
			}
		}
	}

	/** Für den Selbsttest: Zustand der Clip-Vorschau. */
	public ClipPreview clipPreview() {
		return clipPreview;
	}

	/** Für den Selbsttest: Löschen-Rückfrage für den ersten Eintrag zeigen. */
	public void confirmFirst() {
		List<ClipLibrary.Entry> list = filtered();
		if (!list.isEmpty()) confirmDelete = list.get(0);
	}

	@Override
	protected String title() {
		return I18n.tr("clips.title");
	}

	@Override
	protected void playClick() {
		host.playClick();
	}

	@Override
	protected void onClosed() {
		clipPreview.release();
		thumbs.releaseAll();
		large.releaseAll();
		worker.shutdown();
		host.closeScreen();
	}

	@Override
	protected int[] size(int width, int height) {
		int pw = Math.min(width - 12, Math.max(Math.min(340, width - 12), Math.round(width * 0.86f)));
		int ph = Math.min(height - 12, Math.max(Math.min(240, height - 12), Math.round(height * 0.88f)));
		return new int[]{Math.min(pw, 720), Math.min(ph, 460)};
	}

	private List<ClipLibrary.Entry> filtered() {
		List<ClipLibrary.Entry> all = library.listing().entries;
		if (filter == Filter.ALL) return all;
		List<ClipLibrary.Entry> out = new ArrayList<>();
		for (ClipLibrary.Entry e : all) {
			if ((filter == Filter.CLIPS) == (e.type == ClipLibrary.Type.CLIP)) out.add(e);
		}
		return out;
	}

	private final List<ClipLibrary.Entry> visibleCache = new ArrayList<>();

	private List<ClipLibrary.Entry> visible() {
		return visibleCache;
	}

	@Override
	protected void content(Canvas c, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		long now = System.currentTimeMillis();
		thumbs.frame();
		large.frame();
		clipPreview.frame(now);
		String opened = openResult;
		if (opened != null) {
			openResult = null;
			setNotice(opened.isEmpty() ? I18n.tr("clips.preview.opened") : previewError(opened, true), !opened.isEmpty());
		}
		if (now - lastScan > RESCAN_MS && !library.scanning()) {
			lastScan = now;
			library.refresh();
		}
		Boolean deleted = deleteResult;
		if (deleted != null) {
			deleteResult = null;
			setNotice(I18n.tr(deleted ? "clips.deleted" : "clips.deleteFailed"), !deleted);
		}
		ClipLibrary.Listing listing = library.listing();
		List<ClipLibrary.Entry> list = filtered();
		if (preview >= list.size()) preview = -1;
		if (clipView >= list.size() || (clipView >= 0 && list.get(clipView).type != ClipLibrary.Type.CLIP)) closeClip();

		// Werkzeugleiste: Filter links, Aufnahme/Clip/Ordner rechts
		String[] labels = {
				I18n.tr("clips.filter.all", listing.count(null)),
				I18n.tr("clips.filter.clips", listing.count(ClipLibrary.Type.CLIP)),
				I18n.tr("clips.filter.screenshots", listing.count(ClipLibrary.Type.SCREENSHOT))};
		int tabsRight = x;
		int tx = x;
		for (int i = 0; i < 3; i++) {
			final Filter f = Filter.values()[i];
			int tw = c.textWidth(labels[i]) + 12;
			tab(c, tx, y, tw, 16, labels[i], filter == f, mx, my, new Runnable() {
				@Override
				public void run() {
					filter = f;
					scroll = 0;
					preview = -1;
					closeClip();
				}
			});
			// Neu: Clip-Vorschau im Spiel – Schild am Reiter „Clips“, bis die erste Vorschau offen war.
			if (f == Filter.CLIPS && isNew(NewSince.CLIPS_PREVIEW)) NewBadge.draw(c, tx + tw - NewBadge.width(c) + 3, y - 6);
			tx += tw + 3;
			tabsRight = tx;
		}
		final Clips clips = Clips.get();
		ClipStatus st = clips == null ? ClipStatus.OFFLINE : clips.status();
		int right = x + w;
		// Ordner öffnen
		int fx = right - 16;
		iconButton(c, fx, y, 16, "folder", false, mx, my, new Runnable() {
			@Override
			public void run() {
				openFolder();
			}
		});
		right = fx - 4;
		if (st.offersEnable()) {
			// Clips sind aus: statt Aufnahme/Clip ein deutlicher „Jetzt einschalten“ (Hinweis zur Aufnahme darunter).
			String label = I18n.tr("clips.enable.button");
			int ew = Math.min(140, c.textWidth(label) + 16);
			int ex = right - ew;
			if (ex > tabsRight) {
				button(c, ex, y, ew, 16, label, true, true, mx, my, new Runnable() {
					@Override
					public void run() {
						if (clips != null) clips.enableClips();
					}
				});
			}
		} else {
			// Aufnahme – immer klickbar: geht es gerade nicht, sagt die Meldung darunter warum.
			String recLabel = st.recording ? I18n.tr("clips.stop", clock(st.recordingMillis(now))) : I18n.tr("clips.record");
			int rw = Math.min(110, c.textWidth(recLabel) + 26);
			int rx = right - rw;
			boolean canRecord = st.connected && (st.available || st.recording);
			recordButton(c, rx, y, rw, 16, recLabel, st.recording, canRecord, mx, my, now);
			right = rx - 4;
			// Clip speichern
			String clipLabel = I18n.tr("clips.save");
			int cw = Math.min(100, c.textWidth(clipLabel) + 12);
			int clipX = right - cw;
			final boolean ready = st.connected && st.available && st.buffer;
			if (clipX > tabsRight) {
				button(c, clipX, y, cw, 16, clipLabel, false, true, mx, my, new Runnable() {
					@Override
					public void run() {
						if (clips != null) clips.saveClip();
						if (ready) {
							setNotice(I18n.tr("clips.saving"), false);
							lastScan = System.currentTimeMillis() - RESCAN_MS + 2_500L;
						}
					}
				});
			}
		}
		int cy = y + 21;

		// Zustand der Verbindung / Meldung (die neueste gewinnt)
		String line;
		int lineColor = t.textDim;
		int maxLines = 2;
		ClipNotice clipNotice = clips == null ? null : clips.currentNotice();
		long clipNoticeAt = clipNotice == null ? 0 : clipNoticeAt(clipNotice, now);
		boolean localNotice = notice != null && now - noticeAt < 5_000L;
		if (clipNotice != null && clipNotice.type != ClipNotice.Type.OFFER && (!localNotice || clipNoticeAt >= noticeAt)) {
			line = clipNotice.text();
			lineColor = clipNotice.success() ? t.text : t.dustOn;
		} else if (localNotice) {
			line = notice;
			lineColor = noticeError ? t.dustOn : t.text;
		} else if (st.offersEnable()) {
			line = I18n.tr("clips.enable.text", ClipNotice.what(st.audio, st.mic));
			lineColor = t.text;
			maxLines = 3;
		} else if (!st.connected) {
			line = clips == null ? I18n.tr("clips.link.none") : clips.offlineText();
		} else if (st.disabled()) {
			line = I18n.tr("clips.hint.disabled");
		} else if (st.recording) {
			line = I18n.tr("clips.link.recording", clock(st.recordingMillis(now)));
			lineColor = t.dustOn;
		} else if (st.available) {
			line = st.buffer && st.clipSeconds > 0 ? I18n.tr("clips.link.ready", st.clipSeconds) : I18n.tr("clips.link.readyNoBuffer");
		} else {
			String reason = ClipNotice.reasonText(st);
			line = reason != null ? reason : I18n.tr("clips.link.unavailable");
		}
		List<String> statusLines = Paint.wrap(c, line, w);
		for (int i = 0; i < Math.min(maxLines, statusLines.size()); i++) {
			String l = i == maxLines - 1 && statusLines.size() > maxLines ? Paint.join(statusLines, i) : statusLines.get(i);
			Paint.textClipped(c, l, x, cy, w, lineColor, false);
			cy += 10;
		}
		cy += 3;

		int gh = y + h - cy;
		gridRect[0] = x;
		gridRect[1] = cy;
		gridRect[2] = w;
		gridRect[3] = gh;
		Redstone.well(c, x, cy, w, gh, t.border);
		int gx = x + 4;
		int gy = cy + 4;
		int gw = w - 12;
		int ghh = gh - 8;
		visibleCache.clear();
		if (list.isEmpty()) {
			String empty = !listing.scanned ? I18n.tr("clips.loading")
					: filter == Filter.CLIPS ? (listing.clipsDir == null ? I18n.tr("clips.empty.noFolder") : I18n.tr("clips.empty.clips"))
					: filter == Filter.SCREENSHOTS ? I18n.tr("clips.empty.screenshots") : I18n.tr("clips.empty.all");
			List<String> lines = Paint.wrap(c, empty, gw - 20);
			int ty = cy + Math.max(10, gh / 2 - lines.size() * 6);
			for (String l : lines) {
				Paint.textCentered(c, l, x + w / 2, ty, t.textDim, false);
				ty += 11;
			}
		} else {
			grid(c, list, gx, gy, gw, ghh, mx, my);
		}

		if (preview >= 0) previewOverlay(c, list, mx, my);
	if (clipView >= 0) clipOverlay(c, list, mx, my, now);
		if (confirmDelete != null) confirmOverlay(c, mx, my);
	}

	private void recordButton(Canvas c, int x, int y, int w, int h, String label, boolean recording, boolean enabled, int mx, int my, long now) {
		Theme t = Theme.get();
		boolean hov = enabled && inside(mx, my, x, y, w, h);
		if (recording) {
			float pulse = (float) (0.75 + 0.25 * Math.sin(now / 200.0));
			Redstone.lamp(c, x, y, w, h, pulse, 0f);
			c.fill(x + 6, y + 5, x + 12, y + 11, t.dustOn);
			Paint.textClipped(c, label, x + 16, y + 4, w - 18, Redstone.lampTextColor(1f), false);
		} else if (enabled) {
			Redstone.button(c, x, y, w, h, "", false, hov);
			Redstone.pip(c, x + 5, y + 5, 6, hov ? 1f : 0.3f);
			Paint.textClipped(c, label, x + 15, y + 4 - (hov ? 1 : 0), w - 17, t.text, false);
		} else {
			Redstone.stone(c, x, y, w, h, t.surface, t.border);
			Redstone.pip(c, x + 5, y + 5, 6, 0f);
			Paint.textClipped(c, label, x + 15, y + 4, w - 17, t.textDim, false);
		}
		// Auch ohne Verbindung klickbar – dann erklärt die Meldung, warum nichts aufgenommen wird.
		hits.add(x, y, w, h, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				if (Clips.get() != null) Clips.get().toggleRecording();
			}
		});
	}

	private void grid(Canvas c, final List<ClipLibrary.Entry> list, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		int cols = Math.max(1, (w + GAP) / (MIN_TILE_W + GAP));
		int tileW = (w - GAP * (cols - 1)) / cols;
		int thumbH = tileW * 9 / 16;
		int tileH = thumbH + 26;
		int rows = (list.size() + cols - 1) / cols;
		int content = rows * (tileH + GAP) - GAP;
		maxScroll = Math.max(0, content - h);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		c.scissor(x - 1, y - 1, x + w + 1, y + h + 1);
		hits.clip(x, y, w, h);
		boolean overlay = preview >= 0 || clipView >= 0 || confirmDelete != null;
		for (int i = 0; i < list.size(); i++) {
			int tx = x + (i % cols) * (tileW + GAP);
			int ty = y + (i / cols) * (tileH + GAP) - scroll;
			if (ty + tileH < y || ty > y + h) continue;
			final ClipLibrary.Entry e = list.get(i);
			visibleCache.add(e);
			final int index = i;
			boolean hover = !overlay && inside(mx, my, tx, ty, tileW, tileH) && inside(mx, my, x, y, w, h);
			if (hover) Redstone.glow(c, tx, ty, tileW, tileH, t.glow, 0.45f);
			Redstone.stone(c, tx, ty, tileW, tileH, hover ? t.surfaceHover : t.surface, hover ? ColorMath.lerp(t.border, t.accent, 0.5f) : t.border);
			int ix = tx + 3;
			int iy = ty + 3;
			int iw = tileW - 6;
			int ih = thumbH - 2;
			c.fill(ix, iy, ix + iw, iy + ih, 0xFF0B0909);
			if (e.type == ClipLibrary.Type.SCREENSHOT) {
				TextureRef ref = thumbs.get(e.path, e.modified);
				if (ref != null && c.images()) {
					fit(c, ref, ix, iy, iw, ih);
				} else {
					Icons.draw(c, "image", ix + iw / 2 - 8, iy + ih / 2 - 8, 2, t.textDim);
				}
			} else {
				Icons.draw(c, "film", ix + iw / 2 - 8, iy + ih / 2 - 8, 2, ColorMath.lerp(t.textDim, t.dustOn, hover ? 0.8f : 0.3f));
				if (e.durationMs >= 0) {
					String d = clock(e.durationMs);
					int dw = c.textWidth(d) + 6;
					c.fill(ix + iw - dw - 2, iy + ih - 12, ix + iw - 2, iy + ih - 2, 0xC0000000);
					c.text(d, ix + iw - dw + 1, iy + ih - 11, 0xFFFFFFFF, false);
				}
				c.fill(ix + 2, iy + 2, ix + 8, iy + 8, t.dustOn);
			}
			Paint.textClipped(c, stem(e.name), tx + 4, ty + thumbH + 3, tileW - 8, t.text, false);
			Paint.textClipped(c, meta(e), tx + 4, ty + thumbH + 13, tileW - 8, t.textDim, false);
			if (hover) {
				int bx = tx + tileW - 18;
				iconButton(c, bx, ty + 5, 14, "trash", false, mx, my, new Runnable() {
					@Override
					public void run() {
						confirmDelete = e;
					}
				});
			}
			if (!overlay) {
				hits.add(tx, ty, tileW, tileH, new Runnable() {
					@Override
					public void run() {
						host.playClick();
						if (e.type == ClipLibrary.Type.SCREENSHOT) {
							preview = index;
						} else {
							openClip(index, e);
						}
					}
				});
			}
		}
		hits.noClip();
		c.noScissor();
		scrollbar(c, x + w + 3, y, h, scroll, maxScroll, content);
	}

	/** Bild ins Rechteck einpassen (Seitenverhältnis bleibt, mittig). */
	private static void fit(Canvas c, TextureRef ref, int x, int y, int w, int h) {
		float s = Math.min(w / (float) ref.width, h / (float) ref.height);
		float dw = ref.width * s;
		float dh = ref.height * s;
		Affine.image(c, ref, x + (w - dw) / 2f, y + (h - dh) / 2f, dw, dh, 0, 0, ref.width, ref.height, 0xFFFFFFFF);
	}

	private void previewOverlay(Canvas c, final List<ClipLibrary.Entry> list, int mx, int my) {
		Theme t = Theme.get();
		int px = window[0] + 4;
		int py = window[1] + HEADER_H;
		int pw = window[2] - 8;
		int ph = window[3] - HEADER_H - 4;
		c.fill(px, py, px + pw, py + ph, 0xFF0A0808);
		final ClipLibrary.Entry e = list.get(preview);
		int barH = 22;
		int ix = px + 6;
		int iy = py + 6;
		int iw = pw - 12;
		int ih = ph - barH - 12;
		TextureRef ref = large.get(e.path, e.modified);
		if (ref == null) ref = thumbs.get(e.path, e.modified);
		if (ref != null && c.images()) {
			fit(c, ref, ix, iy, iw, ih);
		} else {
			Paint.textCentered(c, I18n.tr("clips.loading"), ix + iw / 2, iy + ih / 2 - 4, t.textDim, false);
		}
		int by = py + ph - barH;
		c.fill(px, by, px + pw, py + ph, 0xFF151112);
		Redstone.dustH(c, px, px + pw, by, t.dustOff, 0f);
		int bx = px + 6;
		iconButton(c, bx, by + 4, 14, "back", false, mx, my, new Runnable() {
			@Override
			public void run() {
				step(list, -1);
			}
		});
		bx += 17;
		iconButton(c, bx, by + 4, 14, "next", false, mx, my, new Runnable() {
			@Override
			public void run() {
				step(list, 1);
			}
		});
		bx += 20;
		int right = px + pw - 6;
		int close = right - 14;
		iconButton(c, close, by + 4, 14, "close", false, mx, my, new Runnable() {
			@Override
			public void run() {
				preview = -1;
			}
		});
		int del = close - 17;
		iconButton(c, del, by + 4, 14, "trash", false, mx, my, new Runnable() {
			@Override
			public void run() {
				confirmDelete = e;
			}
		});
		int open = del - 17;
		iconButton(c, open, by + 4, 14, "folder", false, mx, my, new Runnable() {
			@Override
			public void run() {
				if (!OpenPath.open(e.path)) setNotice(I18n.tr("clips.openFailed"), true);
			}
		});
		Paint.textClipped(c, e.name + "  ·  " + meta(e), bx, by + 7, open - 6 - bx, t.text, false);
		// Klick daneben bleibt im Overlay (keine Kacheln darunter auslösen).
		hits.add(px, py, pw, ph - barH, new Runnable() {
			@Override
			public void run() {
				step(list, 1);
			}
		});
	}

	private void step(List<ClipLibrary.Entry> list, int dir) {
		if (list.isEmpty()) return;
		int n = list.size();
		int i = preview;
		for (int k = 0; k < n; k++) {
			i = ((i + dir) % n + n) % n;
			if (list.get(i).type == ClipLibrary.Type.SCREENSHOT) {
				preview = i;
				return;
			}
		}
	}

	// --- Clip-Vorschau ------------------------------------------------------------------------------

	private void openClip(int index, ClipLibrary.Entry e) {
		clipView = index;
		clipPreview.load(e.name);
		seen(NewSince.CLIPS_PREVIEW);
	}

	private void closeClip() {
		clipView = -1;
		clipPreview.release();
	}

	private void stepClip(List<ClipLibrary.Entry> list, int dir) {
		if (list.isEmpty() || clipView < 0) return;
		int n = list.size();
		int i = clipView;
		for (int k = 0; k < n; k++) {
			i = ((i + dir) % n + n) % n;
			if (list.get(i).type == ClipLibrary.Type.CLIP) {
				openClip(i, list.get(i));
				return;
			}
		}
	}

	/** Vorschau eines Clips: Zeitraffer aus der Vorschau-Leiste des Launchers, „Im Launcher öffnen“. */
	private void clipOverlay(Canvas c, final List<ClipLibrary.Entry> list, int mx, int my, long now) {
		Theme t = Theme.get();
		int px = window[0] + 4;
		int py = window[1] + HEADER_H;
		int pw = window[2] - 8;
		int ph = window[3] - HEADER_H - 4;
		c.fill(px, py, px + pw, py + ph, 0xFF0A0808);
		final ClipLibrary.Entry e = list.get(clipView);
		int barH = 22;
		int timeH = 14;
		int ix = px + 6;
		int iy = py + 6;
		int iw = pw - 12;
		int ih = ph - barH - timeH - 14;
		final ClipPreview.Sheet sheet = clipPreview.sheet();
		final PreviewAnimation anim = clipPreview.animation();
		TextureRef ref = clipPreview.texture();
		if (sheet != null && ref != null && c.images()) {
			int frame = anim.index();
			float s = Math.min(iw / (float) sheet.frameWidth, ih / (float) sheet.frameHeight);
			float dw = sheet.frameWidth * s;
			float dh = sheet.frameHeight * s;
			Affine.image(c, ref, ix + (iw - dw) / 2f, iy + (ih - dh) / 2f, dw, dh, sheet.u(frame), sheet.v(frame), sheet.frameWidth,
					sheet.frameHeight, 0xFFFFFFFF);
			if (!anim.playing()) {
				// Pausiert: großes Symbol in der Mitte.
				Icons.draw(c, "play", ix + iw / 2 - 12, iy + ih / 2 - 12, 3, 0xC0FFFFFF);
			}
			hits.add(ix, iy, iw, ih, new Runnable() {
				@Override
				public void run() {
					anim.toggle();
				}
			});
		} else {
			ClipPreview.State state = clipPreview.state();
			boolean waiting = state == ClipPreview.State.LOADING || state == ClipPreview.State.IDLE;
			String text = waiting ? I18n.tr("clips.preview.loading") : previewError(clipPreview.code(), false);
			Icons.draw(c, "film", ix + iw / 2 - 8, iy + ih / 2 - 30, 2, waiting ? ColorMath.lerp(t.textDim, t.dustOn,
					(float) (0.5 + 0.5 * Math.sin(now / 250.0))) : t.textDim);
			List<String> lines = Paint.wrap(c, text, Math.min(iw - 20, 300));
			int ty = iy + ih / 2 - 6;
			for (String l : lines) {
				Paint.textCentered(c, l, ix + iw / 2, ty, waiting ? t.textDim : t.text, false);
				ty += 11;
			}
			if (state == ClipPreview.State.FAILED && retryable(clipPreview.code())) {
				String retry = I18n.tr("clips.preview.retry");
				int rw = c.textWidth(retry) + 16;
				button(c, ix + (iw - rw) / 2, ty + 4, rw, 16, retry, false, true, mx, my, new Runnable() {
					@Override
					public void run() {
						clipPreview.retry();
					}
				});
			}
		}

		// Zeitleiste (Stelle im Clip) – Klick springt dorthin.
		int ty = iy + ih + 4;
		long total = sheet != null ? sheet.durationMs : e.durationMs;
		String time = (sheet != null ? clock(anim.positionMs()) : "0:00") + " / " + (total >= 0 ? clock(total) : "–");
		int timeW = c.textWidth(time);
		final int trackX = ix;
		final int trackW = Math.max(10, iw - timeW - 8);
		c.fill(trackX, ty + 4, trackX + trackW, ty + 7, 0xFF2A2224);
		if (sheet != null) {
			int filled = Math.round(trackW * anim.progress());
			c.fill(trackX, ty + 4, trackX + filled, ty + 7, t.dustOn);
			c.fill(trackX + filled - 1, ty + 2, trackX + filled + 2, ty + 9, t.dustOn);
			final int clickX = mx;
			hits.add(trackX, ty, trackW, timeH - 2, new Runnable() {
				@Override
				public void run() {
					anim.seek((clickX - trackX) / (float) Math.max(1, trackW - 1));
				}
			});
		}
		c.text(time, ix + iw - timeW, ty + 2, t.textDim, false);

		// Leiste unten: blättern, abspielen, Name – rechts „Im Launcher öffnen“, System-Player, Löschen, Schließen.
		int by = py + ph - barH;
		c.fill(px, by, px + pw, py + ph, 0xFF151112);
		Redstone.dustH(c, px, px + pw, by, t.dustOff, 0f);
		int bx = px + 6;
		iconButton(c, bx, by + 4, 14, "back", false, mx, my, new Runnable() {
			@Override
			public void run() {
				stepClip(list, -1);
			}
		});
		bx += 17;
		iconButton(c, bx, by + 4, 14, "next", false, mx, my, new Runnable() {
			@Override
			public void run() {
				stepClip(list, 1);
			}
		});
		bx += 17;
		if (sheet != null) {
			iconButton(c, bx, by + 4, 14, anim.playing() ? "pause" : "play", false, mx, my, new Runnable() {
				@Override
				public void run() {
					anim.toggle();
				}
			});
			bx += 17;
		}
		bx += 3;
		int right = px + pw - 6;
		int close = right - 14;
		iconButton(c, close, by + 4, 14, "close", false, mx, my, new Runnable() {
			@Override
			public void run() {
				closeClip();
			}
		});
		int del = close - 17;
		iconButton(c, del, by + 4, 14, "trash", false, mx, my, new Runnable() {
			@Override
			public void run() {
				confirmDelete = e;
			}
		});
		int external = del - 17;
		iconButton(c, external, by + 4, 14, "film", false, mx, my, new Runnable() {
			@Override
			public void run() {
				if (!OpenPath.open(e.path)) setNotice(I18n.tr("clips.openFailed"), true);
			}
		});
		String openLabel = I18n.tr("clips.preview.openLauncher");
		int ow = Math.min(150, c.textWidth(openLabel) + 16);
		int ox = external - 6 - ow;
		boolean canOpen = clipPreview.unavailableReason() == null;
		if (ox > bx + 40) {
			button(c, ox, by + 3, ow, 16, openLabel, true, canOpen, mx, my, new Runnable() {
				@Override
				public void run() {
					clipPreview.openInLauncher(e.name, new ClipPreview.OpenResult() {
						@Override
						public void done(String code) {
							openResult = code == null ? "" : code;
						}
					});
				}
			});
			if (isNew(NewSince.CLIPS_PREVIEW)) NewBadge.draw(c, ox + ow - NewBadge.width(c) + 2, by - 6);
		} else {
			ox = external;
		}
		// Meldung (z. B. „Im Launcher geöffnet“) statt Name, solange sie frisch ist.
		boolean fresh = notice != null && now - noticeAt < 4_000L;
		Paint.textClipped(c, fresh ? notice : stem(e.name) + "  ·  " + meta(e), bx, by + 7, ox - 6 - bx,
				fresh ? (noticeError ? t.dustOn : t.text) : t.text, false);
	}

	/** Verständlicher Text zu einem Code der Vorschau bzw. von „Im Launcher öffnen“. */
	public static String previewError(String code, boolean opening) {
		if ("no_launcher".equals(code)) return I18n.tr("clips.preview.noLauncher");
		if ("old_launcher".equals(code)) return I18n.tr("clips.preview.oldLauncher");
		if ("offline".equals(code) || "timeout".equals(code)) return I18n.tr("clips.preview.offline");
		if ("no_ffmpeg".equals(code)) return I18n.tr("clips.preview.noFfmpeg");
		if ("unknown_clip".equals(code)) return I18n.tr("clips.preview.gone");
		if ("busy".equals(code) || "rate_limited".equals(code)) return I18n.tr("clips.preview.busy");
		return I18n.tr(opening ? "clips.preview.openFailed" : "clips.preview.failed");
	}

	private static boolean retryable(String code) {
		return "busy".equals(code) || "rate_limited".equals(code) || "timeout".equals(code) || "offline".equals(code)
				|| "error".equals(code);
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

	private void confirmOverlay(Canvas c, int mx, int my) {
		Theme t = Theme.get();
		final ClipLibrary.Entry e = confirmDelete;
		int w = Math.min(window[2] - 40, 280);
		int h = 84;
		int x = window[0] + (window[2] - w) / 2;
		int y = window[1] + (window[3] - h) / 2;
		c.fill(window[0] + 1, window[1] + HEADER_H, window[0] + window[2] - 1, window[1] + window[3] - 1, 0x90000000);
		Redstone.window(c, x, y, w, h);
		Paint.textClipped(c, I18n.tr(e.type == ClipLibrary.Type.CLIP ? "clips.delete.clip" : "clips.delete.image"), x + 10, y + 9, w - 20, t.text, false);
		Paint.textClipped(c, e.name, x + 10, y + 22, w - 20, t.dustOn, false);
		Paint.textClipped(c, I18n.tr("clips.delete.hint"), x + 10, y + 35, w - 20, t.textDim, false);
		int bw = (w - 30) / 2;
		button(c, x + 10, y + h - 26, bw, 18, I18n.tr("common.cancel"), false, true, mx, my, new Runnable() {
			@Override
			public void run() {
				confirmDelete = null;
			}
		});
		button(c, x + 20 + bw, y + h - 26, bw, 18, I18n.tr("clips.delete.confirm"), true, true, mx, my, new Runnable() {
			@Override
			public void run() {
				confirmDelete = null;
				preview = -1;
				closeClip();
				library.delete(e, new ClipLibrary.DeleteCallback() {
					@Override
					public void done(boolean ok) {
						deleteResult = ok;
					}
				});
			}
		});
	}

	private void openFolder() {
		ClipLibrary.Listing l = library.listing();
		java.nio.file.Path dir = filter == Filter.CLIPS ? l.clipsDir : filter == Filter.SCREENSHOTS ? l.screenshotsDir
				: (l.clipsDir != null && java.nio.file.Files.isDirectory(l.clipsDir) ? l.clipsDir : l.screenshotsDir);
		if (dir != null && !java.nio.file.Files.isDirectory(dir) && dir.equals(l.screenshotsDir)) {
			try {
				java.nio.file.Files.createDirectories(dir);
			} catch (java.io.IOException | RuntimeException ignored) {
				// dann eben nicht
			}
		}
		if (dir == null || !OpenPath.open(dir)) setNotice(I18n.tr("clips.openFailed"), true);
	}

	/** Erster Zeitpunkt, zu dem diese Clip-Meldung hier zu sehen war (zum Vergleich mit eigenen Meldungen). */
	private ClipNotice seenNotice;
	private long seenNoticeAt;

	private long clipNoticeAt(ClipNotice n, long now) {
		if (n != seenNotice) {
			seenNotice = n;
			seenNoticeAt = now;
		}
		return seenNoticeAt;
	}

	private void setNotice(String text, boolean error) {
		notice = text;
		noticeError = error;
		noticeAt = System.currentTimeMillis();
	}

	static String stem(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	private static String meta(ClipLibrary.Entry e) {
		Locale locale = I18n.locale();
		String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(new Date(e.modified));
		return date + " · " + size(e.size);
	}

	static String size(long bytes) {
		if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
		double mb = bytes / (1024.0 * 1024.0);
		return mb >= 100 ? Math.round(mb) + " MB" : String.format(Locale.ROOT, "%.1f MB", mb);
	}

	/** m:ss bzw. h:mm:ss. */
	static String clock(long ms) {
		long s = Math.max(0, ms / 1000);
		long h = s / 3600;
		long m = (s / 60) % 60;
		long sec = s % 60;
		return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (preview >= 0 || clipView >= 0 || confirmDelete != null) return true;
		if (!inside(mouseX, mouseY, gridRect[0], gridRect[1], gridRect[2], gridRect[3])) return false;
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(amount * 30)));
		return true;
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (key == UiKey.ESCAPE) {
			if (confirmDelete != null) confirmDelete = null;
			else if (clipView >= 0) closeClip();
			else if (preview >= 0) preview = -1;
			else requestClose();
			return true;
		}
		if (clipView >= 0 && confirmDelete == null && (key == UiKey.LEFT || key == UiKey.RIGHT)) {
			stepClip(filtered(), key == UiKey.LEFT ? -1 : 1);
			return true;
		}
		if (clipView >= 0 && confirmDelete == null && key == UiKey.ENTER) {
			clipPreview.animation().toggle();
			return true;
		}
		if (preview >= 0 && (key == UiKey.LEFT || key == UiKey.RIGHT)) {
			step(filtered(), key == UiKey.LEFT ? -1 : 1);
			return true;
		}
		if (confirmDelete != null && key == UiKey.ENTER) {
			return true;
		}
		return false;
	}
}
