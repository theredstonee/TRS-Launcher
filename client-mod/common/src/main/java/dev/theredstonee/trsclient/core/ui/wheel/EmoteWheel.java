package dev.theredstonee.trsclient.core.ui.wheel;

import dev.theredstonee.trsclient.core.emote.EmoteController;
import dev.theredstonee.trsclient.core.emote.EmoteDef;
import dev.theredstonee.trsclient.core.emote.WheelMath;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Das Emote-Rad im Redstone-Stil: in der Mitte eine Redstone-Lampe als Nabe, ringsum je Emote eine Lampe mit
 * Pixel-Symbol, dazwischen Redstone-Staub. Die Maus zeigt vom Mittelpunkt aus auf ein Emote (die Leitung dorthin
 * wird bestromt, die Lampe geht an); Loslassen der Rad-Taste spielt es ab. Gesperrte Emotes sind dunkle Steine
 * mit Schloss und lassen sich nicht wählen. Ohne TRS API / Einwilligung zeigt das Rad nur einen Hinweis.
 *
 * <p>Kurz angetippt (Taste schon wieder los, bevor das Rad richtig offen ist) bleibt das Rad offen: dann wählt ein
 * Klick oder ein zweiter Druck auf die Taste, Esc schließt.
 */
public final class EmoteWheel extends UiScreen {
	/** So kurz gedrückt = angetippt (Rad bleibt offen). */
	static final long TAP_MS = 250;

	/** Was das Rad vom Spiel braucht. */
	public interface Host {
		/** Ist die Rad-Taste noch gedrückt? null = nicht abfragbar (z. B. Maustaste) → Klick-Modus. */
		Boolean keyHeld();

		/** Anzeigename der Rad-Taste (für den Hinweis). */
		String keyName();

		/** Minecraft-Bildschirm schließen. */
		void close();

		/** Kurze Meldung über der Schnellleiste (Wartezeit, gesperrt). */
		void actionBar(String text);

		/** Klick-Ton. */
		void click();
	}

	private final EmoteController emotes;
	private final Host host;
	private final long openedAt = System.currentTimeMillis();
	private final List<EmoteDef> defs;
	private final float[] lit;
	private float hubLit;
	private int hovered = -1;
	/** Klick-Modus (Taste nur angetippt oder nicht abfragbar). */
	private boolean sticky;
	private boolean pressedAgain;
	private boolean decided;
	/** Selbsttest hat ein Emote vorgegeben (Maus zählt dann nicht). */
	private boolean forced;
	/** Letzte Geometrie (für Klicks). */
	private int centerX;
	private int centerY;
	private int deadZone = 18;
	private int[] slotX = new int[0];
	private int[] slotY = new int[0];
	private int slotSize;

	private final List<Object[]> texts = new ArrayList<>();

	public EmoteWheel(EmoteController emotes, Host host) {
		this.emotes = emotes;
		this.host = host;
		this.defs = dev.theredstonee.trsclient.core.wardrobe.EmoteSlots.forWheel(emotes.all());
		this.lit = new float[defs.size()];
		emotes.wheelOpened(openedAt);
	}

	// --- Eingabe ---

	private void checkKey(long now) {
		if (decided || isClosing()) return;
		Boolean held = host.keyHeld();
		if (held == null) {
			sticky = true;
			return;
		}
		if (!sticky) {
			if (held) return;
			if (now - openedAt < TAP_MS) sticky = true;
			else confirm(now);
			return;
		}
		// Klick-Modus: zweiter Druck + Loslassen wählt.
		if (held) pressedAgain = true;
		else if (pressedAgain) confirm(now);
	}

	/** Das gezeigte Emote abspielen (falls eines gewählt ist) und schließen. */
	private void confirm(long now) {
		decided = true;
		if (hovered >= 0) play(hovered, now);
		requestClose();
	}

	private void play(int index, long now) {
		EmoteDef def = defs.get(index);
		EmoteController.Result r = emotes.play(def.id(), now);
		switch (r) {
			case PLAYED:
				host.click();
				break;
			case COOLDOWN:
				host.actionBar(I18n.tr("wheel.cooldown", Long.valueOf((emotes.cooldownLeft(now) + 999) / 1000)));
				break;
			case LOCKED:
				host.actionBar(I18n.tr("wheel.lockedToast"));
				break;
			default:
				break;
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (decided || isClosing()) return true;
		long now = System.currentTimeMillis();
		int i = pick(mouseX, mouseY);
		if (button == 0 && i >= 0) {
			if (!emotes.unlocked(defs.get(i).id())) return true;
			hovered = i;
			confirm(now);
			return true;
		}
		decided = true;
		requestClose();
		return true;
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (key == UiKey.ESCAPE) {
			decided = true;
			requestClose();
			return true;
		}
		return false;
	}

	private int pick(double mx, double my) {
		if (emotes.state() != EmoteController.State.READY) return -1;
		return WheelMath.select(mx - centerX, my - centerY, defs.size(), deadZone);
	}

	@Override
	protected void onClosed() {
		host.close();
	}

	/** Welches Emote gerade gezeigt wird (−1 = keines) – für den Selbsttest. */
	public int hovered() {
		return hovered;
	}

	/** Selbsttest: Emote mit dieser ID zeigen (als stünde die Maus darauf). */
	public void hover(String id) {
		for (int i = 0; i < defs.size(); i++) {
			if (defs.get(i).id().equals(id)) {
				hovered = i;
				forced = true;
			}
		}
	}

	/** Selbsttest: gezeigtes Emote abspielen und schließen (wie Loslassen der Taste). */
	public void confirmNow() {
		if (!decided) confirm(System.currentTimeMillis());
	}

	// --- Zeichnen ---

	@Override
	protected void draw(Canvas raw, int width, int height, int mouseX, int mouseY, float dt) {
		long now = System.currentTimeMillis();
		Theme t = Theme.get();
		Canvas c = FadeCanvas.of(raw, alpha());
		texts.clear();
		c.fill(0, 0, width, height, 0x70000000);
		c.push();
		c.raise(300f);
		centerX = width / 2;
		centerY = height / 2;
		EmoteController.State state = emotes.state();
		if (state == EmoteController.State.READY || state == EmoteController.State.LOADING) {
			wheel(c, t, width, height, mouseX, mouseY, dt, now, state);
		} else {
			hint(c, t, width, height, state);
		}
		// Text zuletzt: Minecraft zeichnet ihn mit eigener Tiefe, später gezeichnete Flächen lägen darüber.
		for (Object[] tx : texts) {
			c.text((String) tx[0], (Integer) tx[1], (Integer) tx[2], (Integer) tx[3], (Boolean) tx[4]);
		}
		c.pop();
		checkKey(now);
	}

	private void text(String s, int x, int y, int argb, boolean shadow) {
		texts.add(new Object[] {s, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(argb), Boolean.valueOf(shadow)});
	}

	private void centered(Canvas c, String s, int cx, int y, int argb, int maxWidth) {
		String clipped = c.textWidth(s) <= maxWidth ? s : c.clip(s, maxWidth);
		text(clipped, cx - c.textWidth(clipped) / 2, y, argb, true);
	}

	private void wheel(Canvas c, Theme t, int width, int height, int mouseX, int mouseY, float dt, long now,
			EmoteController.State state) {
		int n = defs.size();
		boolean big = Math.min(width, height) >= 300;
		int s = big ? 28 : 22;
		slotSize = s;
		int hub = big ? 30 : 24;
		deadZone = hub / 2 + 4;
		float grow = 0.55f + 0.45f * Anim.easeOut(open);
		int r = Math.round(WheelMath.radius(width, height, n, s) * grow);
		if (slotX.length != n) {
			slotX = new int[n];
			slotY = new int[n];
		}
		for (int i = 0; i < n; i++) {
			slotX[i] = WheelMath.slotX(centerX, r, i, n);
			slotY[i] = WheelMath.slotY(centerY, r, i, n);
		}
		if (!decided && !forced && state == EmoteController.State.READY) {
			int pick = WheelMath.select(mouseX - centerX, mouseY - centerY, n, deadZone);
			if (pick >= 0 || !sticky) hovered = pick;
		}
		if (state != EmoteController.State.READY) hovered = -1;
		boolean hoveredUnlocked = hovered >= 0 && emotes.unlocked(defs.get(hovered).id());
		hubLit = Anim.approach(hubLit, hoveredUnlocked ? 1f : 0.15f, dt, 0.06f);

		// Staubleitungen von der Nabe zu jedem Emote; die gewählte führt Signal.
		for (int i = 0; i < n; i++) {
			boolean sel = i == hovered;
			dust(c, t, centerX, centerY, slotX[i], slotY[i], hub / 2 + 2, s / 2 + 2, sel ? (hoveredUnlocked ? 1f : 0.35f) : 0f);
		}

		// Nabe: Redstone-Lampe mit Redstone-Symbol.
		int hx = centerX - hub / 2;
		int hy = centerY - hub / 2;
		Redstone.lamp(c, hx, hy, hub, hub, hubLit, 0f);
		int hpx = Math.max(1, (hub - 10) / 8);
		Icons.draw(c, "redstone", centerX - hpx * 4, centerY - hpx * 4, hpx, Redstone.lampTextColor(hubLit));

		// Emotes: freigeschaltet = Lampe, gesperrt = dunkler Stein mit Schloss.
		int px = Math.max(1, (s - 8) / 8);
		for (int i = 0; i < n; i++) {
			EmoteDef def = defs.get(i);
			boolean unlocked = emotes.unlocked(def.id());
			boolean sel = i == hovered;
			lit[i] = Anim.approach(lit[i], sel && unlocked ? 1f : 0f, dt, 0.05f);
			int x = slotX[i] - s / 2;
			int y = slotY[i] - s / 2 - (sel ? 1 : 0);
			if (unlocked) {
				Redstone.lamp(c, x, y, s, s, 0.12f + 0.88f * lit[i], 0f);
				Icons.draw(c, def.icon(), slotX[i] - px * 4, y + (s - px * 8) / 2, px, Redstone.lampTextColor(0.12f + 0.88f * lit[i]));
			} else {
				if (sel) Redstone.glow(c, x, y, s, s, t.border, 0.8f);
				Redstone.stone(c, x, y, s, s, sel ? t.surfaceHover : t.surface, t.border);
				Icons.draw(c, def.icon(), slotX[i] - px * 4, y + (s - px * 8) / 2, px, ColorMath.withAlpha(t.textDim, 110));
				Icons.draw(c, "lock", x + s - 9, y + s - 9, 1, t.textDim);
			}
		}

		// Beschriftung: Titel oben, gewähltes Emote unter der Nabe, Bedienhinweis unten.
		int inner = Math.max(40, 2 * r - s - 10);
		int top = centerY - r - s / 2 - 14;
		if (top >= 2) centered(c, I18n.tr("wheel.title"), centerX, top, t.text, width - 8);
		int labelY = centerY + hub / 2 + 5;
		long cooldown = emotes.cooldownLeft(now);
		if (state == EmoteController.State.LOADING) {
			centered(c, I18n.tr("wheel.loading"), centerX, labelY, t.textDim, inner);
		} else if (hovered >= 0) {
			EmoteDef def = defs.get(hovered);
			boolean unlocked = emotes.unlocked(def.id());
			centered(c, I18n.trOr(def.nameKey(), def.id()), centerX, labelY, unlocked ? t.text : t.textDim, inner);
			if (!unlocked) centered(c, I18n.tr("wheel.locked"), centerX, labelY + 10, t.textDim, inner);
			else if (cooldown > 0) centered(c, I18n.tr("wheel.cooldown", Long.valueOf((cooldown + 999) / 1000)),
					centerX, labelY + 10, t.textDim, inner);
		} else {
			centered(c, I18n.tr("wheel.pick"), centerX, labelY, t.textDim, inner);
		}
		int bottom = centerY + r + s / 2 + 6;
		if (bottom + 9 <= height - 2) {
			String hint = sticky ? I18n.tr("wheel.hintClick") : I18n.tr("wheel.hintHold", host.keyName());
			centered(c, hint, centerX, bottom, t.textDim, width - 8);
		}
	}

	/**
	 * Staubleitung als Pixelpunkte von der Nabe (Abstand {@code from}) bis vor den Platz (Abstand {@code to}).
	 * {@code signal} 0 = unbestromt, 1 = volle Stärke (wird zum Emote hin schwächer, wie echter Staub).
	 */
	private static void dust(Canvas c, Theme t, int x0, int y0, int x1, int y1, int from, int to, float signal) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double len = Math.sqrt(dx * dx + dy * dy);
		if (len < from + to + 2) return;
		double ux = dx / len;
		double uy = dy / len;
		int steps = (int) ((len - from - to) / 3);
		for (int k = 0; k <= steps; k++) {
			double d = from + k * 3;
			int x = (int) Math.round(x0 + ux * d);
			int y = (int) Math.round(y0 + uy * d);
			if (signal > 0.02f) {
				int level = 15 - Math.round(6f * k / Math.max(1, steps));
				int col = ColorMath.lerp(t.dustOff, t.dust(level), signal);
				c.fill(x - 2, y - 2, x + 2, y + 2, ColorMath.withAlpha(t.glow, Math.round(34 * signal)));
				c.fill(x - 1, y - 1, x + 1, y + 1, col);
			} else {
				c.fill(x - 1, y - 1, x + 1, y + 1, ColorMath.withAlpha(t.dustOff, 200));
			}
		}
	}

	/** Kein Rad möglich (Einwilligung, Konto, Verbindung): kleines Fenster mit Hinweis. */
	private void hint(Canvas c, Theme t, int width, int height, EmoteController.State state) {
		String message;
		switch (state) {
			case OFF_LAUNCHER:
				message = I18n.tr("wheel.offLauncher");
				break;
			case OFF_MODULE:
				message = I18n.tr("wheel.offModule");
				break;
			case NO_ACCOUNT:
				message = I18n.tr("wheel.noAccount");
				break;
			case BANNED:
				message = I18n.tr("wheel.banned");
				break;
			case RETRY:
				message = I18n.tr("wheel.retry");
				break;
			default:
				message = I18n.tr("wheel.connecting");
				break;
		}
		int w = Math.min(240, width - 16);
		List<String> lines = Paint.wrap(c, message, w - 20);
		int h = 44 + lines.size() * 10;
		int x = centerX - w / 2;
		int y = centerY - h / 2 + Math.round((1 - Anim.easeOut(open)) * 10);
		Redstone.window(c, x, y, w, h);
		Redstone.lamp(c, x + 8, y + 7, 14, 14, state == EmoteController.State.CONNECTING ? 0.5f : 0f, 0f);
		text(I18n.tr("wheel.title"), x + 28, y + 10, t.text, false);
		Redstone.dustH(c, x + 8, x + w - 8, y + 26, t.dustOff, 0f);
		for (int i = 0; i < lines.size(); i++) text(lines.get(i), x + 10, y + 33 + i * 10, t.textDim, false);
	}
}
