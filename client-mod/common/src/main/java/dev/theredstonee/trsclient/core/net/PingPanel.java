package dev.theredstonee.trsclient.core.net;

import dev.theredstonee.trsclient.core.hosting.Hosting;
import dev.theredstonee.trsclient.core.hosting.net.PeerStream;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.TextWidth;

import java.util.Locale;

/**
 * HUD „Ping“: aktueller Ping, Jitter, kleiner Verlauf und auf Wunsch TPS-Schätzung und Zeitüberschreitungen. Zeichnet
 * über {@link Canvas} bei (0, 0), Größen unskaliert – in allen Minecraft-Versionen gleich.
 */
public final class PingPanel {
	private static final int PAD_X = 5;
	private static final int PAD_Y = 4;
	private static final int LINE_H = 10;
	static final int GRAPH_W = 60;
	private static final int GRAPH_H = 12;
	private static final long REFRESH_MS = 100;

	private final TrsModules modules;
	private final HudModule module;
	private final PingMeter.Snapshot snap = new PingMeter.Snapshot();
	private final PingMeter.Snapshot preview = previewSnapshot();
	private long snapAt = Long.MIN_VALUE;
	private boolean lastPreview;
	private int lastGeneration = -1;
	private String line1 = "";
	private String jitterText = "";
	private String tag = "";
	private String details = "";
	private String spikeText = "";

	public PingPanel(TrsModules modules) {
		this.modules = modules;
		this.module = modules.ping;
	}

	private static PingMeter.Snapshot previewSnapshot() {
		PingMeter.Snapshot s = new PingMeter.Snapshot();
		s.source = PingMeter.Source.ACTIVE;
		s.current = 42;
		s.average = 44;
		s.jitter = 3;
		s.tps = 20;
		s.timeoutPercent = 0;
		long[] demo = {41, 43, 40, 44, 46, 42, 41, 39, 45, 58, 47, 43, 42, 41, 44, 43, 40, 42, 45, 42};
		for (int i = 0; i < 45; i++) s.history[s.historyCount++] = demo[i % demo.length];
		return s;
	}

	/**
	 * Im Spiel sichtbar, sobald man mit einem Server verbunden ist – auch vor dem ersten Wert („– ms“): ältere Server
	 * schicken ihren Messwert erst nach bis zu 30 s.
	 */
	public boolean visible() {
		NetPlatform p = NetBoost.platform();
		return p != null && p.multiplayer();
	}

	private PingMeter.Snapshot data(boolean preview) {
		refresh(preview);
		return preview && (snap.current < 0 && snap.historyCount == 0) ? this.preview : snap;
	}

	private boolean usePreview(boolean preview) {
		return preview && snap.current < 0 && snap.historyCount == 0;
	}

	/** Werte höchstens alle 100 ms neu holen und Texte bauen (nicht je Bild). */
	private void refresh(boolean preview) {
		NetPlatform p = NetBoost.platform();
		long now = p != null ? p.millis() : System.nanoTime() / 1_000_000L;
		if (snapAt != Long.MIN_VALUE && now - snapAt < REFRESH_MS && now >= snapAt && preview == lastPreview
				&& I18n.generation() == lastGeneration) {
			return;
		}
		snapAt = now;
		lastPreview = preview;
		lastGeneration = I18n.generation();
		NetBoost.ping().snapshot(snap, now, System.nanoTime());
		PingMeter.Snapshot s = usePreview(preview) ? this.preview : snap;
		line1 = s.current >= 0 ? I18n.tr("hud.ping.value", s.current) : I18n.tr("hud.ping.none");
		jitterText = modules.pingJitter.get() && s.jitter >= 0 ? I18n.tr("hud.ping.jitter", round(s.jitter)) : "";
		tag = tag(s);
		StringBuilder d = new StringBuilder();
		if (modules.pingDetails.get()) {
			if (!Double.isNaN(s.tps)) d.append(I18n.tr("hud.ping.tps", String.format(Locale.ROOT, "%.1f", s.tps)));
			if (s.timeoutPercent >= 0) {
				if (d.length() > 0) d.append("  ");
				d.append(I18n.tr("hud.ping.timeouts", round(s.timeoutPercent)));
			}
		}
		details = d.toString();
		spikeText = modules.pingSpikeWarning.get() && s.spike ? I18n.tr("hud.ping.spike", s.spikeValue) : "";
	}

	private static String tag(PingMeter.Snapshot s) {
		Hosting h = Hosting.current();
		if (h != null && h.guestState() == Hosting.GuestState.CONNECTED && h.guestPath() != null) {
			return h.guestPath() == PeerStream.Path.DIRECT ? I18n.tr("hosting.path.direct") : I18n.tr("hosting.path.relay");
		}
		NetPlatform p = NetBoost.platform();
		boolean serverValue = s.source == PingMeter.Source.SERVER
				|| (s.source == PingMeter.Source.NONE && p != null && !p.activePing());
		return serverValue ? I18n.tr("hud.ping.source.server") : "";
	}

	private static long round(double v) {
		return Math.round(v);
	}

	public int width(TextWidth tw, boolean preview) {
		refresh(preview);
		int row = tw.width(line1);
		if (!jitterText.isEmpty()) row += 4 + tw.width(jitterText);
		if (!tag.isEmpty()) row += 4 + tw.width(tag);
		int w = row;
		if (modules.pingGraph.get()) w = Math.max(w, GRAPH_W);
		if (!details.isEmpty()) w = Math.max(w, tw.width(details));
		if (!spikeText.isEmpty()) w = Math.max(w, tw.width(spikeText));
		return w + PAD_X * 2;
	}

	public int height(boolean preview) {
		refresh(preview);
		int h = LINE_H;
		if (modules.pingGraph.get()) h += GRAPH_H + 2;
		if (!details.isEmpty()) h += LINE_H;
		if (!spikeText.isEmpty()) h += LINE_H;
		return h - 2 + PAD_Y * 2;
	}

	public void draw(Canvas c, TextWidth tw, boolean preview) {
		PingMeter.Snapshot s = data(preview);
		int w = width(tw, preview);
		int h = height(preview);
		int bg = module.backgroundArgb();
		boolean shadow = module.shadow();
		int text = module.textColor.argb();
		int dim = ColorMath.withAlpha(text, 150);
		if (bg != 0) c.fill(0, 0, w, h, bg);
		int x = PAD_X;
		int y = PAD_Y;
		int valueColor = s.current >= 0 ? quality(s.current) : dim;
		if (s.spike && !spikeText.isEmpty()) valueColor = 0xFFFF5555;
		c.text(line1, x, y, valueColor, shadow);
		int tx = x + tw.width(line1) + 4;
		if (!jitterText.isEmpty()) {
			c.text(jitterText, tx, y, dim, shadow);
			tx += tw.width(jitterText) + 4;
		}
		if (!tag.isEmpty()) c.text(tag, tx, y, dim, shadow);
		y += LINE_H;
		if (modules.pingGraph.get()) {
			graph(c, s, x, y, w - PAD_X * 2);
			y += GRAPH_H + 2;
		}
		if (!details.isEmpty()) {
			c.text(details, x, y, dim, shadow);
			y += LINE_H;
		}
		if (!spikeText.isEmpty()) c.text(spikeText, x, y, 0xFFFF5555, shadow);
	}

	/** Säulen je Messung (neueste rechts), Höhe relativ zu mindestens 100 ms; Zeitüberschreitung = rote Säule. */
	private static void graph(Canvas c, PingMeter.Snapshot s, int x, int y, int w) {
		c.fill(x, y, x + w, y + GRAPH_H, 0x30000000);
		int n = Math.min(s.historyCount, w / 2);
		long max = 100;
		for (int i = s.historyCount - n; i < s.historyCount; i++) max = Math.max(max, s.history[i]);
		int bx = x + w - n * 2;
		for (int i = s.historyCount - n; i < s.historyCount; i++, bx += 2) {
			long v = s.history[i];
			if (v < 0) {
				c.fill(bx, y, bx + 1, y + GRAPH_H, 0xC0FF4040);
				continue;
			}
			int bh = Math.max(1, (int) Math.round((double) v / max * GRAPH_H));
			c.fill(bx, y + GRAPH_H - bh, bx + 1, y + GRAPH_H, quality(v));
		}
	}

	/** Farbe nach Güte: grün bis rot. */
	public static int quality(long ms) {
		if (ms < 60) return 0xFF55E060;
		if (ms < 120) return 0xFFB8E050;
		if (ms < 200) return 0xFFF0D040;
		if (ms < 300) return 0xFFF09030;
		return 0xFFF04040;
	}
}
