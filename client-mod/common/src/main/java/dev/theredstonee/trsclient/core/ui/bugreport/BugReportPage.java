package dev.theredstonee.trsclient.core.ui.bugreport;

import dev.theredstonee.trsclient.core.bugreport.BugReportBody;
import dev.theredstonee.trsclient.core.bugreport.BugReports;
import dev.theredstonee.trsclient.core.bugreport.LogScrubber;
import dev.theredstonee.trsclient.core.clips.Thumbnails;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.social.ChatInput;
import dev.theredstonee.trsclient.core.ui.social.ChatLayout;
import dev.theredstonee.trsclient.core.util.Links;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Seite „Bug melden“ im TRS-Menü (alle Versionen – gezeichnet nur über {@link Canvas}): Titel, Beschreibung,
 * Häkchen für die Anhänge (TRS-Client-Version, Minecraft + Loader, Mod-Liste, Log-Ausschnitt, Bildschirmfoto),
 * dann eine Vorschau, die genau zeigt, was mitgeht (Log gesäubert, mit Zählung des Entfernten), und das Senden über
 * die TRS API. Nach dem Erfolg: Issue-Nummer und „Im Browser öffnen“.
 */
public final class BugReportPage {
	/** Was die Seite vom Menü braucht. */
	public interface Host {
		void click();

		/** TRS-Menü schließen (für „Neu aufnehmen“). */
		void closeMenu();

		/** Anzeigename der Taste, die das TRS-Menü öffnet. */
		String menuKey();

		/** Inhalt der Zwischenablage oder null. */
		String clipboard();
	}

	private enum Step {
		EDIT, PREVIEW
	}

	private static final int LH = 10;
	private static final int OK = 0xFF7CD88C;
	private static final int WARN = 0xFFF0C050;
	private static final int BAD = 0xFFFF8080;

	private static volatile BugReportPage current;

	private final Host host;
	private Step step = Step.EDIT;
	private int scroll;
	private int maxScroll;
	private final int[] rect = new int[4];
	/** Log-Kasten der Vorschau (eigener Bildlauf). */
	private final int[] logRect = new int[4];
	private int logScroll;
	private int logMaxScroll;
	private boolean takeHelp;
	/** „Weiter“ wurde mit ungültigen Eingaben gedrückt → Hinweise zeigen. */
	private boolean tried;
	private long seenData;
	private Thumbnails thumbs;
	private String openFailed;
	// Umbruch-Zwischenspeicher (Text + Breite → Zeilenbereiche)
	private final WrapCache descWrap = new WrapCache();
	private final WrapCache previewWrap = new WrapCache();
	private final WrapCache logWrap = new WrapCache();
	private final WrapCache modsWrap = new WrapCache();

	public BugReportPage(Host host) {
		this.host = host;
	}

	/** Die gerade offene Seite (Autotest) oder null. */
	public static BugReportPage current() {
		return current;
	}

	/** Seite geöffnet: Anhänge (neu) sammeln. */
	public void opened() {
		current = this;
		scroll = 0;
		takeHelp = false;
		openFailed = null;
		BugReports br = BugReports.get();
		if (br != null) br.collectAsync(true);
	}

	/** Menü geschlossen: Texturen freigeben. */
	public void closed() {
		BugReports br = BugReports.get();
		if (br != null) {
			br.title.setFocused(false);
			br.description.setFocused(false);
		}
		if (thumbs != null) thumbs.releaseAll();
		if (current == this) current = null;
	}

	/** Esc: erst Eingabe verlassen, dann Hilfe schließen, dann zurück zur Bearbeitung. true = verbraucht. */
	public boolean back() {
		BugReports br = BugReports.get();
		if (br == null) return false;
		if (br.title.focused() || br.description.focused()) {
			br.title.setFocused(false);
			br.description.setFocused(false);
			return true;
		}
		if (takeHelp) {
			takeHelp = false;
			return true;
		}
		if (step == Step.PREVIEW && !br.busy()) {
			toEdit();
			return true;
		}
		return false;
	}

	// --- Zeichnen ---

	public void draw(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my, float dt) {
		current = this;
		rect[0] = x;
		rect[1] = y;
		rect[2] = w;
		rect[3] = h;
		logRect[2] = 0;
		Theme t = Theme.get();
		BugReports br = BugReports.get();
		c.scissor(x, y, x + w, y + h);
		hits.clip(x, y, w, h);
		// Unterste Fläche: Klick ins Leere verlässt die Eingabefelder.
		if (br != null) {
			final BugReports b = br;
			hits.add(x, y, w, h, new Runnable() {
				@Override
				public void run() {
					b.title.setFocused(false);
					b.description.setFocused(false);
				}
			});
		}
		int yy = y - scroll;
		if (br == null) {
			yy = Paint.paragraph(c, I18n.tr("bugreport.unavailable"), x, yy, w - 6, LH, t.textDim);
			finish(c, hits, yy, y, h);
			return;
		}
		if (thumbs == null) thumbs = new Thumbnails(br.worker(), 320, 180, 6);
		thumbs.frame();
		applyNewScreenshot(br);
		int cw = w - 6;
		// Kopf
		Icons.draw(c, "bug", x, yy + 1, 1, t.accent);
		c.text(I18n.tr(br.phase() == BugReports.Phase.DONE ? "bugreport.done.title"
				: step == Step.PREVIEW ? "bugreport.preview.title" : "bugreport.title"), x + 13, yy + 1, t.text, false);
		yy += 14;
		if (br.phase() == BugReports.Phase.DONE) {
			yy = drawDone(c, hits, br, x, yy, cw, mx, my);
		} else if (step == Step.PREVIEW) {
			yy = drawPreview(c, hits, br, x, yy, cw, y, h, mx, my);
		} else {
			yy = drawEdit(c, hits, br, x, yy, cw, mx, my);
		}
		finish(c, hits, yy, y, h);
	}

	private int drawEdit(Canvas c, Hits hits, final BugReports br, int x, int yy, int cw, int mx, int my) {
		Theme t = Theme.get();
		yy = Paint.paragraph(c, I18n.tr("bugreport.intro"), x, yy, cw, LH, t.textDim);
		yy += 2;
		yy = accessLine(c, br, x, yy, cw);
		yy += 4;

		// Titel
		String titleText = br.title.text();
		label(c, I18n.tr("bugreport.field.title"), x, yy, cw, titleText.trim().length(), BugReportBody.TITLE_MAX);
		yy += 10;
		singleLine(c, hits, br.title, br.description, I18n.tr("bugreport.field.titleHint"), x, yy, cw);
		yy += 18;
		if (tried && BugReportBody.titleError(titleText) != null) {
			yy = Paint.paragraph(c, I18n.tr(BugReportBody.titleError(titleText)), x, yy, cw, LH, BAD);
		}
		yy += 3;

		// Beschreibung
		String desc = br.description.text();
		label(c, I18n.tr("bugreport.field.description"), x, yy, cw, desc.trim().length(), BugReportBody.DESCRIPTION_MAX);
		yy += 10;
		yy = multiLine(c, hits, br, x, yy, cw);
		if (tried && BugReportBody.descriptionError(desc) != null) {
			yy = Paint.paragraph(c, I18n.tr(BugReportBody.descriptionError(desc)), x, yy, cw, LH, BAD);
		}
		yy += 5;

		// Anhänge
		c.text(I18n.tr("bugreport.attach.title"), x, yy, t.text, false);
		final boolean collecting = br.collecting();
		int rx = x + cw - 14;
		Paint.iconButton(c, rx, yy - 3, 14, "reset", inside(mx, my, rx, yy - 3, 14, 14), false);
		hits.add(rx, yy - 3, 14, 14, click(new Runnable() {
			@Override
			public void run() {
				br.collectAsync(true);
			}
		}));
		if (collecting) Paint.textRight(c, I18n.tr("bugreport.loading"), rx - 4, yy, t.textDim, false);
		yy += 13;
		BugReports.Data d = br.data();
		yy = toggleRow(c, hits, x, yy, cw, I18n.tr("bugreport.attach.modVersion"), br.modVersion(), br.includeModVersion, mx, my,
				new Runnable() {
					@Override
					public void run() {
						br.includeModVersion = !br.includeModVersion;
					}
				});
		yy = toggleRow(c, hits, x, yy, cw, I18n.tr("bugreport.attach.game"), br.mcVersion() + " · " + loaderName(br.loader()),
				br.includeGame, mx, my, new Runnable() {
					@Override
					public void run() {
						br.includeGame = !br.includeGame;
					}
				});
		yy = toggleRow(c, hits, x, yy, cw, I18n.tr("bugreport.attach.mods"),
				d == null ? "…" : I18n.tr("bugreport.attach.modsCount", d.modsTotal), br.includeMods, mx, my, new Runnable() {
					@Override
					public void run() {
						br.includeMods = !br.includeMods;
					}
				});
		String logInfo = d == null ? "…" : br.selectedLog() == null ? I18n.tr("bugreport.attach.noLog")
				: I18n.tr("bugreport.attach.logInfo", lines(br.selectedLog()));
		yy = toggleRow(c, hits, x, yy, cw, I18n.tr("bugreport.attach.log"), logInfo, br.includeLog, mx, my, new Runnable() {
			@Override
			public void run() {
				br.includeLog = !br.includeLog;
			}
		});
		if (br.includeLog && d != null && d.crash != null) yy = logSource(c, hits, br, d, x + 28, yy, cw - 28, mx, my);
		BugReports.Screenshot shot = br.selectedScreenshot();
		yy = toggleRow(c, hits, x, yy, cw, I18n.tr("bugreport.attach.screenshot"),
				shot == null ? I18n.tr("bugreport.attach.noScreenshot") : shot.name, br.includeScreenshot && shot != null, mx, my,
				new Runnable() {
					@Override
					public void run() {
						br.includeScreenshot = !br.includeScreenshot;
					}
				});
		yy = screenshotArea(c, hits, br, d, x + 28, yy, cw - 28, mx, my);
		yy += 4;

		yy = Paint.paragraph(c, I18n.tr("bugreport.privacy.short"), x, yy, cw, LH, t.textDim);
		yy += 3;
		boolean ready = d != null;
		yy = button(c, hits, I18n.tr("bugreport.next"), x, yy, cw, true, ready, mx, my, new Runnable() {
			@Override
			public void run() {
				if (!br.textValid()) {
					tried = true;
					return;
				}
				toPreview();
			}
		});
		return yy;
	}

	private int drawPreview(Canvas c, Hits hits, final BugReports br, int x, int yy, int cw, int top, int h, int mx, int my) {
		Theme t = Theme.get();
		yy = Paint.paragraph(c, I18n.tr("bugreport.preview.public"), x, yy, cw, LH, t.textDim);
		yy += 2;
		yy = accessLine(c, br, x, yy, cw);
		yy += 4;
		BugReportBody.Meta meta = br.meta();
		BugReports.Data d = br.data();
		// Titel + Beschreibung
		yy = section(c, x, yy, cw, I18n.tr("bugreport.field.title"));
		yy = Paint.paragraph(c, br.title.text().trim(), x + 4, yy, cw - 4, LH, t.text);
		yy += 3;
		yy = section(c, x, yy, cw, I18n.tr("bugreport.field.description"));
		yy = wrapped(c, previewWrap, br.description.text().trim(), x + 4, yy, cw - 4, t.text, top, h);
		yy += 3;
		boolean any = false;
		if (meta.modVersion != null) {
			any = true;
			yy = section(c, x, yy, cw, I18n.tr("bugreport.attach.modVersion"));
			c.text(meta.modVersion, x + 4, yy, t.text, false);
			yy += LH + 3;
		}
		if (meta.mcVersion != null) {
			any = true;
			yy = section(c, x, yy, cw, I18n.tr("bugreport.attach.game"));
			c.text(meta.mcVersion + " · " + loaderName(meta.loader), x + 4, yy, t.text, false);
			yy += LH + 3;
		}
		if (meta.mods != null) {
			any = true;
			yy = section(c, x, yy, cw, I18n.tr("bugreport.attach.mods") + " (" + meta.mods.size() + ")");
			String joined = join(meta.mods);
			yy = wrapped(c, modsWrap, joined.isEmpty() ? "–" : joined, x + 4, yy, cw - 4, t.textDim, top, h);
			if (d != null && d.modsTotal > meta.mods.size()) {
				yy = Paint.paragraph(c, I18n.tr("bugreport.preview.modsMore", d.modsTotal - meta.mods.size()), x + 4, yy, cw - 4, LH, WARN);
			}
			yy += 3;
		}
		if (meta.log != null) {
			any = true;
			boolean crash = br.useCrashReport && d != null && d.crash != null;
			yy = section(c, x, yy, cw, crash ? I18n.tr("bugreport.attach.source.crash") : I18n.tr("bugreport.attach.log"));
			LogScrubber.Result r = br.selectedLogScrub();
			String removed = removedText(r);
			yy = Paint.paragraph(c, removed, x + 4, yy, cw - 4, LH, r != null && r.total() > 0 ? OK : t.textDim);
			yy = logBox(c, hits, meta.log, x + 4, yy + 2, cw - 4, top, h, mx, my);
			yy += 3;
		}
		BugReports.Screenshot shot = br.includeScreenshot ? br.selectedScreenshot() : null;
		if (shot != null) {
			any = true;
			yy = section(c, x, yy, cw, I18n.tr("bugreport.attach.screenshot"));
			int tw = Math.min(cw - 4, 200);
			int th = tw * 9 / 16;
			thumb(c, shot, x + 4, yy, tw, th);
			yy += th + 2;
			Paint.textClipped(c, shot.name, x + 4, yy, cw - 4, t.textDim, false);
			yy += LH + 3;
		}
		if (!any) {
			yy = Paint.paragraph(c, I18n.tr("bugreport.preview.nothing"), x, yy, cw, LH, t.textDim);
			yy += 3;
		}
		// Fehler vom letzten Versuch
		if (br.phase() == BugReports.Phase.FAILED) {
			yy = Paint.paragraph(c, errorText(br.error(), br.retryAfterMs()), x, yy, cw, LH, BAD);
			yy += 2;
		}
		boolean busy = br.busy();
		boolean canSend = !busy && br.access() == BugReports.Access.READY && br.textValid();
		int half = (cw - 4) / 2;
		button(c, hits, I18n.tr("bugreport.back"), x, yy, half, false, !busy, mx, my, new Runnable() {
			@Override
			public void run() {
				toEdit();
			}
		});
		String sendLabel = br.phase() == BugReports.Phase.UPLOADING ? I18n.tr("bugreport.uploading")
				: br.phase() == BugReports.Phase.SENDING ? I18n.tr("bugreport.sending")
				: br.phase() == BugReports.Phase.FAILED ? I18n.tr("bugreport.retry") : I18n.tr("bugreport.send");
		yy = button(c, hits, sendLabel, x + half + 4, yy, cw - half - 4, true, canSend, mx, my, new Runnable() {
			@Override
			public void run() {
				br.clearError();
				br.sendAsync();
			}
		});
		return yy;
	}

	private int drawDone(Canvas c, Hits hits, final BugReports br, int x, int yy, int cw, int mx, int my) {
		Theme t = Theme.get();
		final BugReportBody.Created created = br.created();
		Redstone.pip(c, x, yy + 1, 7, 1f);
		yy = Paint.paragraph(c, I18n.tr("bugreport.done.number", created == null ? "?" : created.number), x + 12, yy, cw - 12, LH, OK);
		yy += 4;
		if (created != null && created.url != null) {
			Paint.textClipped(c, created.url, x, yy, cw, t.textDim, false);
			yy += LH + 2;
			yy = button(c, hits, I18n.tr("bugreport.done.open"), x, yy, cw, true, true, mx, my, new Runnable() {
				@Override
				public void run() {
					openFailed = BugReportBody.siteLink(created.url) && Links.open(created.url) ? null : created.url;
				}
			});
			if (openFailed != null) yy = Paint.paragraph(c, I18n.tr("bugreport.done.openFailed", openFailed), x, yy, cw, LH, WARN);
		}
		int half = (cw - 4) / 2;
		button(c, hits, I18n.tr("bugreport.done.new"), x, yy, half, false, true, mx, my, new Runnable() {
			@Override
			public void run() {
				br.reset();
				tried = false;
				step = Step.EDIT;
				scroll = 0;
				br.collectAsync(true);
			}
		});
		yy = button(c, hits, I18n.tr("bugreport.done.close"), x + half + 4, yy, cw - half - 4, false, true, mx, my, new Runnable() {
			@Override
			public void run() {
				br.reset();
				tried = false;
				step = Step.EDIT;
				host.closeMenu();
			}
		});
		return yy;
	}

	// --- Bausteine ---

	/** Zeile „Angemeldet als …“ bzw. warum gerade nicht gesendet werden kann. */
	private int accessLine(Canvas c, BugReports br, int x, int yy, int cw) {
		BugReports.Access a = br.access();
		if (a == BugReports.Access.READY) {
			String name = br.accountName();
			Redstone.pip(c, x, yy + 1, 6, 1f);
			Paint.textClipped(c, I18n.tr("bugreport.access.ready", name == null ? "?" : name), x + 10, yy, cw - 10, Theme.get().textDim,
					false);
			return yy + LH;
		}
		String key = "bugreport.access." + a.name().toLowerCase(java.util.Locale.ROOT);
		int color = a == BugReports.Access.CONNECTING ? Theme.get().textDim : WARN;
		return Paint.paragraph(c, I18n.tr(key), x, yy, cw, LH, color);
	}

	private void label(Canvas c, String text, int x, int yy, int cw, int count, int max) {
		Theme t = Theme.get();
		c.text(text, x, yy, t.textDim, false);
		String n = count + "/" + max;
		Paint.textRight(c, n, x + cw, yy, count > max ? t.dustOn : ColorMath.withAlpha(t.textDim, 170), false);
	}

	private void singleLine(Canvas c, Hits hits, final TextInput input, final ChatInput other, String hint, int x, int y, int w) {
		Theme t = Theme.get();
		Redstone.well(c, x, y, w, 16, input.focused() ? t.accent : t.border);
		String text = input.text();
		if (text.isEmpty() && !input.focused()) {
			Paint.textClipped(c, hint, x + 4, y + 4, w - 8, ColorMath.withAlpha(t.textDim, 170), false);
		} else {
			String before = text.substring(0, Math.min(input.cursor(), text.length()));
			// Cursor sichtbar halten: vorne abschneiden, bis er ins Feld passt.
			int start = 0;
			while (start < before.length() && c.textWidth(text.substring(start, before.length())) > w - 10) start++;
			String shown = c.clip(text.substring(start), w - 8);
			c.text(shown, x + 4, y + 4, t.text, false);
			if (input.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
				int caret = Math.min(x + 4 + c.textWidth(text.substring(start, before.length())), x + w - 3);
				c.fill(caret, y + 3, caret + 1, y + 13, t.dustOn);
			}
		}
		hits.add(x, y, w, 16, new Runnable() {
			@Override
			public void run() {
				input.setFocused(true);
				other.setFocused(false);
			}
		});
	}

	private int multiLine(Canvas c, Hits hits, final BugReports br, int x, int y, int w) {
		Theme t = Theme.get();
		final ChatInput input = br.description;
		String text = input.text();
		List<int[]> lines = descWrap.get(c, text, w - 10);
		int visible = Math.max(5, Math.min(9, lines.size()));
		int fh = visible * LH + 8;
		Redstone.well(c, x, y, w, fh, input.focused() ? t.accent : t.border);
		if (text.isEmpty() && !input.focused()) {
			Paint.paragraph(c, I18n.tr("bugreport.field.descriptionHint"), x + 5, y + 5, w - 10, LH, ColorMath.withAlpha(t.textDim, 170));
		}
		int cursorLine = lines.size() - 1;
		for (int i = 0; i < lines.size(); i++) {
			int[] r = lines.get(i);
			if (input.cursor() >= r[0] && input.cursor() <= r[1]) {
				cursorLine = i;
				break;
			}
		}
		int first = Math.max(0, Math.min(lines.size() - visible, cursorLine - visible + 1));
		if (!input.focused()) first = 0;
		int ty = y + 5;
		for (int i = first; i < first + visible && i < lines.size(); i++) {
			int[] r = lines.get(i);
			c.text(text.substring(r[0], r[1]), x + 5, ty, t.text, false);
			if (input.focused() && i == cursorLine && (System.currentTimeMillis() / 500) % 2 == 0) {
				int col = Math.max(r[0], Math.min(input.cursor(), r[1]));
				int cx = x + 5 + c.textWidth(text.substring(r[0], col));
				c.fill(cx, ty - 1, cx + 1, ty + 9, t.dustOn);
			}
			ty += LH;
		}
		if (text.isEmpty() && input.focused() && (System.currentTimeMillis() / 500) % 2 == 0) c.fill(x + 5, y + 4, x + 6, y + 14, t.dustOn);
		hits.add(x, y, w, fh, new Runnable() {
			@Override
			public void run() {
				input.setFocused(true);
				br.title.setFocused(false);
			}
		});
		return y + fh + 2;
	}

	private int toggleRow(Canvas c, Hits hits, int x, int y, int w, String label, String info, boolean on, int mx, int my,
			Runnable toggle) {
		Theme t = Theme.get();
		boolean hover = inside(mx, my, x, y, w, 16);
		if (hover) Redstone.block(c, x - 2, y, w + 4, 16, t.surfaceHover);
		Paint.toggle(c, x, y + 3, 22, 11, on ? 1f : 0f, hover);
		int lw = c.textWidth(label);
		int maxLabel = Math.max(40, w - 28 - 60);
		Paint.textClipped(c, label, x + 28, y + 4, maxLabel, on ? t.text : t.textDim, false);
		int infoX = x + 28 + Math.min(lw, maxLabel) + 8;
		if (info != null && x + w - infoX > 20) {
			String s = c.clip(info, x + w - infoX);
			Paint.textRight(c, s, x + w, y + 4, ColorMath.withAlpha(t.textDim, on ? 230 : 150), false);
		}
		hits.add(x, y, w, 16, click(toggle));
		return y + 17;
	}

	/** Auswahl latest.log / Absturzbericht (nur wenn ein aktueller Absturzbericht da ist). */
	private int logSource(Canvas c, Hits hits, final BugReports br, BugReports.Data d, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		String a = I18n.tr("bugreport.attach.source.log");
		String b = I18n.tr("bugreport.attach.crashFrom", dateTime(d.crashModified));
		int cx = x;
		for (int i = 0; i < 2; i++) {
			final boolean crash = i == 1;
			String label = crash ? b : a;
			int cw = Math.min(c.textWidth(label) + 10, x + w - cx);
			if (cw < 20) break;
			boolean sel = br.useCrashReport == crash;
			boolean hover = inside(mx, my, cx, y, cw, 13);
			Redstone.stone(c, cx, y, cw, 13, sel ? ColorMath.lerp(t.surface, t.accent, 0.3f) : hover ? t.surfaceHover : t.surface,
					sel ? t.accent : t.border);
			Paint.textClipped(c, label, cx + 5, y + 3, cw - 8, sel ? t.text : t.textDim, false);
			hits.add(cx, y, cw, 13, click(new Runnable() {
				@Override
				public void run() {
					br.useCrashReport = crash;
				}
			}));
			cx += cw + 3;
		}
		return y + 16;
	}

	/** Vorschaubild, Blättern, „Neu aufnehmen“ samt Anleitung. */
	private int screenshotArea(Canvas c, Hits hits, final BugReports br, BugReports.Data d, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		BugReports.Screenshot shot = br.selectedScreenshot();
		if (br.includeScreenshot && shot != null) {
			int tw = Math.min(w, 128);
			int th = tw * 9 / 16;
			thumb(c, shot, x, y, tw, th);
			int ix = x + tw + 6;
			if (x + w - ix > 40) {
				Paint.textClipped(c, dateTime(shot.modified), ix, y + 2, x + w - ix, t.textDim, false);
				if (d != null && d.screenshots.size() > 1) {
					int by = y + 14;
					Paint.iconButton(c, ix, by, 14, "prev", inside(mx, my, ix, by, 14, 14), false);
					hits.add(ix, by, 14, 14, click(new Runnable() {
						@Override
						public void run() {
							br.stepScreenshot(-1);
						}
					}));
					Paint.iconButton(c, ix + 17, by, 14, "next", inside(mx, my, ix + 17, by, 14, 14), false);
					hits.add(ix + 17, by, 14, 14, click(new Runnable() {
						@Override
						public void run() {
							br.stepScreenshot(1);
						}
					}));
					int idx = d.screenshots.indexOf(shot) + 1;
					c.text(idx + "/" + d.screenshots.size(), ix + 36, by + 3, t.textDim, false);
				}
			}
			y += th + 3;
		}
		if (br.awaitScreenshotSince > 0) {
			y = Paint.paragraph(c, I18n.tr("bugreport.take.waiting"), x, y, w, LH, WARN);
			br.collectAsync(false);
		}
		String take = I18n.tr("bugreport.attach.take");
		int bw = Math.min(w, c.textWidth(take) + 16);
		y = button(c, hits, take, x, y, bw, false, true, mx, my, new Runnable() {
			@Override
			public void run() {
				takeHelp = !takeHelp;
			}
		});
		if (takeHelp) {
			y = Paint.paragraph(c, I18n.tr("bugreport.take.help", host.menuKey()), x, y, w, LH, t.text);
			y += 2;
			String close = I18n.tr("bugreport.take.close");
			y = button(c, hits, close, x, y, Math.min(w, c.textWidth(close) + 16), true, true, mx, my, new Runnable() {
				@Override
				public void run() {
					takeHelp = false;
					br.awaitScreenshotSince = System.currentTimeMillis();
					br.requestOpen();
					host.closeMenu();
				}
			});
		}
		return y;
	}

	/** Neuer Screenshot nach „Neu aufnehmen“ da? Dann wählen und anhaken. */
	private void applyNewScreenshot(BugReports br) {
		BugReports.Data d = br.data();
		if (d == null || d.collectedAt == seenData) return;
		seenData = d.collectedAt;
		if (br.awaitScreenshotSince > 0 && !d.screenshots.isEmpty()
				&& d.screenshots.get(0).modified >= br.awaitScreenshotSince - 1000) {
			br.screenshot = d.screenshots.get(0).path;
			br.includeScreenshot = true;
			br.awaitScreenshotSince = 0;
		}
	}

	private void thumb(Canvas c, BugReports.Screenshot shot, int x, int y, int w, int h) {
		Theme t = Theme.get();
		c.fill(x, y, x + w, y + h, 0xFF0B0909);
		TextureRef ref = thumbs == null ? null : thumbs.get(shot.path, shot.modified);
		if (ref != null && c.images()) {
			float s = Math.min(w / (float) ref.width, h / (float) ref.height);
			float dw = ref.width * s;
			float dh = ref.height * s;
			Affine.image(c, ref, x + (w - dw) / 2f, y + (h - dh) / 2f, dw, dh, 0, 0, ref.width, ref.height, 0xFFFFFFFF);
		} else {
			Icons.draw(c, "image", x + w / 2 - 8, y + h / 2 - 8, 2, t.textDim);
		}
		Paint.outline(c, x, y, w, h, t.border);
	}

	/** Log in einem eigenen Kasten mit Bildlauf (alle Zeilen, umbrochen – genau das, was gesendet wird). */
	private int logBox(Canvas c, Hits hits, String log, int x, int y, int w, int top, int h, int mx, int my) {
		Theme t = Theme.get();
		int bh = Math.max(60, Math.min(190, h - 40));
		Redstone.well(c, x, y, w, bh, t.border);
		List<int[]> lines = logWrap.get(c, log, w - 12);
		int contentH = lines.size() * 9;
		logMaxScroll = Math.max(0, contentH - (bh - 8));
		logScroll = Math.max(0, Math.min(logScroll, logMaxScroll));
		// Nur, wenn der Kasten sichtbar ist: eigener Scissor (nie verschachteln – Seite kurz beenden).
		int visTop = Math.max(y + 1, top);
		int visBottom = Math.min(y + bh - 1, top + h);
		if (visBottom > visTop) {
			c.noScissor();
			c.scissor(x + 1, visTop, x + w - 1, visBottom);
			int ty = y + 4 - logScroll;
			for (int[] r : lines) {
				if (ty + 9 >= visTop && ty <= visBottom) c.text(log.substring(r[0], r[1]), x + 4, ty, t.textDim, false);
				ty += 9;
			}
			c.noScissor();
			c.scissor(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]);
			logRect[0] = x;
			logRect[1] = visTop;
			logRect[2] = w;
			logRect[3] = visBottom - visTop;
		}
		// Bildlaufleiste
		if (logMaxScroll > 0) {
			int trackH = bh - 4;
			int barH = Math.max(10, trackH * (bh - 8) / Math.max(1, contentH));
			int barY = y + 2 + (trackH - barH) * logScroll / logMaxScroll;
			c.fill(x + w - 4, barY, x + w - 2, barY + barH, ColorMath.withAlpha(t.textDim, 160));
		}
		return y + bh + 2;
	}

	private int section(Canvas c, int x, int y, int w, String title) {
		Theme t = Theme.get();
		c.text(title, x, y, t.accent, false);
		c.fill(x, y + 10, x + w, y + 11, ColorMath.withAlpha(t.border, 160));
		return y + 14;
	}

	/** Umbrochener Text (Zeilenumbrüche bleiben), nur sichtbare Zeilen werden gezeichnet. */
	private int wrapped(Canvas c, WrapCache cache, String text, int x, int y, int w, int color, int top, int h) {
		List<int[]> lines = cache.get(c, text, w);
		for (int[] r : lines) {
			if (y + LH >= top && y <= top + h) c.text(text.substring(r[0], r[1]), x, y, color, false);
			y += LH;
		}
		return y;
	}

	private int button(Canvas c, Hits hits, String label, int x, int y, int w, boolean primary, boolean enabled, int mx, int my,
			Runnable action) {
		Theme t = Theme.get();
		if (enabled) {
			Paint.button(c, x, y, w, 17, c.clip(label, w - 10), primary, inside(mx, my, x, y, w, 17));
			hits.add(x, y, w, 17, click(action));
		} else {
			Redstone.stone(c, x, y, w, 17, t.surface, t.border);
			Paint.textCentered(c, c.clip(label, w - 10), x + w / 2, y + 5, ColorMath.withAlpha(t.textDim, 140), false);
		}
		return y + 21;
	}

	private Runnable click(final Runnable action) {
		return new Runnable() {
			@Override
			public void run() {
				host.click();
				action.run();
			}
		};
	}

	private void finish(Canvas c, Hits hits, int yy, int y, int h) {
		hits.noClip();
		c.noScissor();
		maxScroll = Math.max(0, yy + scroll - (y + h) + 4);
		scroll = Math.min(scroll, maxScroll);
	}

	private void toPreview() {
		BugReports br = BugReports.get();
		if (br == null) return;
		br.title.setFocused(false);
		br.description.setFocused(false);
		br.clearError();
		tried = false;
		step = Step.PREVIEW;
		scroll = 0;
		logScroll = 0;
	}

	private void toEdit() {
		BugReports br = BugReports.get();
		if (br != null) br.clearError();
		step = Step.EDIT;
		scroll = 0;
	}

	// --- Texte ---

	static String removedText(LogScrubber.Result r) {
		if (r == null || r.total() == 0) return I18n.tr("bugreport.preview.removedNone");
		List<String> parts = new ArrayList<String>();
		if (r.tokens > 0) parts.add(I18n.tr("bugreport.removed.tokens", r.tokens));
		if (r.uuids > 0) parts.add(I18n.tr("bugreport.removed.uuids", r.uuids));
		if (r.names > 0) parts.add(I18n.tr("bugreport.removed.names", r.names));
		if (r.ips > 0) parts.add(I18n.tr("bugreport.removed.ips", r.ips));
		if (r.emails > 0) parts.add(I18n.tr("bugreport.removed.emails", r.emails));
		if (r.paths > 0) parts.add(I18n.tr("bugreport.removed.paths", r.paths));
		if (r.chat > 0) parts.add(I18n.tr("bugreport.removed.chat", r.chat));
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) b.append(", ");
			b.append(parts.get(i));
		}
		return I18n.tr("bugreport.preview.removed", b.toString());
	}

	/** Verständlicher Text zu einem Fehlercode. */
	public static String errorText(String code, long retryAfterMs) {
		if (code == null) return I18n.tr("bugreport.error.unknown", "?");
		switch (code) {
			case "launcher_off":
			case "module_off":
			case "no_account":
			case "banned":
				return I18n.tr("bugreport.access." + code);
			case "issue_daily_limit":
			case "sanctioned":
			case "invalid_request":
			case "upload_not_found":
			case "payload_too_large":
			case "unsupported_media_type":
			case "image_unreadable":
			case "unauthorized":
			case "offline":
			case "rate_limited":
			case "busy":
				String text = I18n.tr("bugreport.error." + code);
				if ("rate_limited".equals(code) && retryAfterMs > 0) {
					text = text + " " + I18n.tr("bugreport.error.retryIn", Math.max(1, (retryAfterMs + 999) / 1000));
				}
				return text;
			default:
				return I18n.tr("bugreport.error.unknown", code);
		}
	}

	static String loaderName(String loader) {
		if (loader == null || loader.isEmpty()) return "?";
		if (loader.equals("neoforge")) return "NeoForge";
		return Character.toUpperCase(loader.charAt(0)) + loader.substring(1);
	}

	private static String dateTime(long ms) {
		return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, I18n.locale()).format(new Date(ms));
	}

	private static int lines(String s) {
		if (s == null || s.isEmpty()) return 0;
		int n = 1;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == '\n') n++;
		}
		return n;
	}

	private static String join(List<String> list) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) b.append(", ");
			b.append(list.get(i));
		}
		return b.toString();
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	// --- Eingaben ---

	public boolean mouseScrolled(double mx, double my, double amount) {
		if (logRect[2] > 0 && inside(mx, my, logRect[0], logRect[1], logRect[2], logRect[3]) && logMaxScroll > 0) {
			logScroll = Math.max(0, Math.min(logMaxScroll, logScroll - (int) Math.signum(amount) * 27));
			return true;
		}
		if (!inside(mx, my, rect[0], rect[1], rect[2], rect[3])) return false;
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(amount) * 24));
		return true;
	}

	public boolean keyPressed(UiKey key) {
		BugReports br = BugReports.get();
		if (br == null) return false;
		if (br.title.focused()) {
			switch (key) {
				case ESCAPE:
					br.title.setFocused(false);
					return true;
				case ENTER:
				case TAB:
				case DOWN:
					br.title.setFocused(false);
					br.description.setFocused(true);
					return true;
				case PASTE:
					String clip = host.clipboard();
					if (clip != null) {
						String line = clip.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
						for (int i = 0; i < line.length(); i++) br.title.type(line.charAt(i));
					}
					return true;
				default:
					br.title.key(key);
					return true;
			}
		}
		if (br.description.focused()) {
			switch (key) {
				case ESCAPE:
					br.description.setFocused(false);
					return true;
				case ENTER:
					br.description.newline();
					return true;
				case TAB:
					br.description.setFocused(false);
					return true;
				case PASTE:
					br.description.key(UiKey.PASTE, host.clipboard());
					return true;
				default:
					br.description.key(key, null);
					return true;
			}
		}
		return false;
	}

	/** Zeichen in das fokussierte Feld; true = verbraucht (sonst landet Tippen nicht in der Modul-Suche). */
	public boolean charTyped(char ch) {
		BugReports br = BugReports.get();
		if (br == null) return false;
		if (br.title.focused()) {
			br.title.type(ch);
			return true;
		}
		if (br.description.focused()) {
			br.description.type(ch);
			return true;
		}
		return true;
	}

	// --- Autotest ---

	/** Zur Vorschau (true = Eingaben gültig und Daten da). */
	public boolean testPreview() {
		BugReports br = BugReports.get();
		if (br == null || !br.textValid() || br.data() == null) return false;
		toPreview();
		return true;
	}

	public void testEdit() {
		toEdit();
	}

	public void testScroll(int px) {
		scroll = Math.max(0, px);
	}

	public void testLogScroll(int px) {
		logScroll = Math.max(0, px);
	}

	public boolean previewing() {
		return step == Step.PREVIEW;
	}

	/** Zeilenumbruch-Zwischenspeicher für einen Text bei einer Breite. */
	private static final class WrapCache {
		private String text;
		private int width = -1;
		private List<int[]> lines;

		List<int[]> get(final Canvas c, String t, int w) {
			if (lines != null && w == width && t.equals(text)) return lines;
			text = t;
			width = w;
			lines = ChatLayout.wrap(new ChatLayout.Measure() {
				@Override
				public int width(String s) {
					return c.textWidth(s);
				}
			}, t, w);
			return lines;
		}
	}
}
