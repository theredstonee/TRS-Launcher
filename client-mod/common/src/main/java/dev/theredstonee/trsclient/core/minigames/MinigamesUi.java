package dev.theredstonee.trsclient.core.minigames;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.MinigameModules;
import dev.theredstonee.trsclient.core.qol.QolSound;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Textures;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.core.ui.menu.MenuHost;

import java.text.NumberFormat;
import java.util.List;
import java.util.Random;

/**
 * Bildschirm der Minispiele: Auswahl mit Rangliste, darunter je Spiel die Partie. Kennt Minecraft nicht; Tastenzustand,
 * Fensterfokus und Ton kommen über {@link MenuHost}. Ein Menü-Bildschirm wie jeder andere, die Welt läuft im Mehrspieler
 * weiter und das Spiel verschafft dort keinen Vorteil.
 */
public final class MinigamesUi extends UiScreen {
	private static final String KEY = "key.keyboard.";
	private static final int WHITE = 0xFFFFFFFF;
	private static final int DIM = 0xB0FFFFFF;
	private static final int BOX = 0x8C000000;
	private static final int BOX_EDGE = 0x1CFFFFFF;
	private static final int DARKEN = 0xA0000000;
	/** So lange wartet „Start“ auf den Startwert des Servers; danach läuft die Partie lokal. */
	private static final long START_WAIT_MS = 2500;

	/** Taste und Bit einer Tastenmaske. */
	private static final class Bind {
		final String key;
		final int bit;

		Bind(String key, int bit) {
			this.key = KEY + key;
			this.bit = bit;
		}
	}

	private static final Bind[] STAPLER_KEYS = {new Bind("left", Stapler.K_LEFT), new Bind("right", Stapler.K_RIGHT), new Bind("down", Stapler.K_DOWN),
			new Bind("up", Stapler.K_CW), new Bind("x", Stapler.K_CW), new Bind("z", Stapler.K_CCW), new Bind("y", Stapler.K_CCW),
			new Bind("space", Stapler.K_HARD), new Bind("c", Stapler.K_HOLD), new Bind("left.shift", Stapler.K_HOLD), new Bind("e", Stapler.K_PULSE)};
	private static final Bind[] LOREN_KEYS = {new Bind("left", Loren.K_LEFT), new Bind("a", Loren.K_LEFT), new Bind("right", Loren.K_RIGHT),
			new Bind("d", Loren.K_RIGHT), new Bind("up", Loren.K_JUMP), new Bind("w", Loren.K_JUMP), new Bind("space", Loren.K_JUMP),
			new Bind("down", Loren.K_DUCK), new Bind("s", Loren.K_DUCK), new Bind("e", Loren.K_TURBO)};

	private static final int[] RULE_TILES = {Stapler.RED, Stapler.TNT, Stapler.LAMP, Stapler.PISTON};
	private static final String[] RULE_TITLES = {"minigames.stapler.rules.redstoneTitle", "minigames.stapler.rules.tntTitle",
			"minigames.stapler.rules.lampTitle", "minigames.stapler.rules.pistonTitle"};
	private static final String[] RULE_TEXTS = {"minigames.stapler.rules.redstone", "minigames.stapler.rules.tnt",
			"minigames.stapler.rules.lamp", "minigames.stapler.rules.piston"};

	private final MenuHost host;
	private final MinigameModules module;
	private final MinigamesOnline online;
	private final GameSound sound;
	private final Random random = new Random();
	private GameTextures textures;
	private GameSkin skin = GameSkin.of(Theme.get());
	private int generation;
	private final PixelFrame atlas = new PixelFrame("minigames/atlas", StaplerView.ATLAS_COLS * StaplerView.TILE, StaplerView.ATLAS_ROWS * StaplerView.TILE);
	private final PixelFrame hubTiles = new PixelFrame("minigames/hub", 8 * 16, 16);

	private String selected = Replay.STAPLER;
	private MinigameSession session;
	private MinigamesOnline.Pending<MinigamesApi.Start> starting;
	private long startingSince;
	private int heldIgnored;
	private MinigameSession.Finished lastRun;
	private boolean lastWasBest;
	private MinigamesOnline.Pending<MinigamesOnline.Submit> submit;
	private MinigamesOnline.Submit submitResult;
	private boolean boardAfterSubmit;
	private String submittedGame;
	private final MinigamesOnline.BoardResult[] boards = new MinigamesOnline.BoardResult[2];
	@SuppressWarnings("unchecked")
	private final MinigamesOnline.Pending<MinigamesOnline.BoardResult>[] boardLoads = new MinigamesOnline.Pending[2];
	private NumberFormat numbers;
	private int numbersFor = -1;

	// Nur für den Selbsttest: feste Tasten statt Tastatur, Fokus egal.
	private boolean testControl;
	private int testMask;

	public MinigamesUi(MenuHost host) {
		this(host, MinigamesOnline.live());
	}

	public MinigamesUi(final MenuHost host, MinigamesOnline online) {
		this.host = host;
		this.module = host.modules().minigames;
		this.online = online;
		this.sound = new GameSound(new GameSound.Player() {
			@Override
			public void play(QolSound s, float volume, float pitch) {
				host.playSound(s, volume, pitch);
			}
		});
		loadBoard(Replay.STAPLER);
		loadBoard(Replay.LOREN);
	}

	private static int slot(String game) {
		return Replay.STAPLER.equals(game) ? 0 : 1;
	}

	private void loadBoard(String game) {
		boardLoads[slot(game)] = online.leaderboard(game);
	}

	private GameTextures textures() {
		if (textures == null) textures = GameTextures.fromGame();
		return textures;
	}

	// --- Ablauf ---

	/** Öffnet die Partie-Ansicht eines Spiels. */
	private void enter(String game) {
		if (session != null) session.dispose();
		selected = game;
		session = new MinigameSession(game, textures(), skin, sound);
		applyEffects();
		if (session.staplerView != null) atlas.upload(session.staplerView.atlas);
		lastRun = null;
		submitResult = null;
		starting = null;
	}

	private void applyEffects() {
		if (session == null) return;
		if (session.staplerView != null) session.staplerView.effects = module.effects.get();
		if (session.lorenView != null) session.lorenView.effects = module.effects.get();
	}

	private void leaveGame() {
		if (session != null) session.dispose();
		session = null;
		starting = null;
		loadBoard(selected);
	}

	/** „Start“: Startwert vom Server holen, ohne Anmeldung oder Antwort läuft die Partie lokal. */
	private void play() {
		if (session == null || starting != null) return;
		if (!module.ranked.get()) {
			beginRun(random.nextInt() & 0xFFFFFFFFL, null);
			return;
		}
		starting = online.start(session.game);
		startingSince = System.currentTimeMillis();
	}

	private void beginRun(long seed, String runId) {
		lastRun = null;
		submitResult = null;
		session.start(seed, runId);
		// Tasten, die beim Start schon gedrückt sind (Leertaste auf „Start“), zählen erst nach dem Loslassen.
		heldIgnored = heldKeys();
	}

	private void pollStart() {
		if (starting == null) return;
		if (starting.done()) {
			MinigamesApi.Start s = starting.value();
			starting = null;
			if (s != null) beginRun(s.seed, s.runId);
			else beginRun(random.nextInt() & 0xFFFFFFFFL, null);
		} else if (System.currentTimeMillis() - startingSince > START_WAIT_MS) {
			starting = null;
			beginRun(random.nextInt() & 0xFFFFFFFFL, null);
		}
	}

	private int heldKeys() {
		if (session == null) return 0;
		if (testControl) return testMask;
		int mask = 0;
		for (Bind b : session.isStapler() ? STAPLER_KEYS : LOREN_KEYS) {
			if (host.keyDown(b.key)) mask |= b.bit;
		}
		return mask;
	}

	private void toggleSound() {
		module.sound.set(!module.sound.get());
		host.save();
	}

	private void afterRun(MinigameSession.Finished run) {
		lastRun = run;
		lastWasBest = module.best.record(run.game, run.score);
		if (lastWasBest) host.save();
		submit = module.ranked.get() ? online.submit(run) : null;
		submittedGame = run.game;
		boardAfterSubmit = submit != null;
	}

	private void refreshSkin() {
		GameSkin wanted = GameSkin.of(Theme.get());
		Textures.Store store = Textures.store();
		int gen = store == null ? 0 : store.resourceGeneration();
		if (wanted == skin && gen == generation) return;
		boolean skinChanged = wanted != skin;
		skin = wanted;
		generation = gen;
		textures = null;
		hubFilled = false;
		if (session == null) return;
		if (skinChanged) {
			// Der Anstrich steckt im Atlas und im Hintergrund, die Partie läuft aber weiter.
			String game = session.game;
			if (session.state == MinigameSession.State.READY) enter(game);
			return;
		}
		session.reload(textures());
		if (session.staplerView != null) atlas.upload(session.staplerView.atlas);
	}

	// --- Zeichnen ---

	@Override
	protected void draw(Canvas raw, int width, int height, int mouseX, int mouseY, float dt) {
		Canvas c = FadeCanvas.of(raw, alpha());
		refreshSkin();
		sound.master = module.sound.get() ? (float) (module.volume.get() / 100.0) : 0f;
		backdrop(c, width, height);
		pollBoards();
		pollSubmit();
		if (session == null) {
			drawHub(c, width, height, mouseX, mouseY);
			return;
		}
		pollStart();
		applyEffects();
		if (session.state == MinigameSession.State.PLAYING) {
			if (!testControl && !host.windowFocused()) {
				session.blur();
			} else {
				int held = heldKeys();
				heldIgnored &= held;
				session.held(held & ~heldIgnored);
			}
		}
		session.frame(dt);
		MinigameSession.Finished done = session.takeFinished();
		if (done != null) afterRun(done);
		if (session.isStapler()) drawStapler(c, width, height);
		else drawLoren(c, width, height);
		if (session.state != MinigameSession.State.PLAYING) drawCard(c, width, height, mouseX, mouseY);
	}

	/** Das Ergebnis kommt auch an, wenn der Spieler schon zurück in der Auswahl ist; danach stimmt die Rangliste wieder. */
	private void pollSubmit() {
		if (submit == null || !submit.done()) return;
		submitResult = submit.value();
		submit = null;
		if (boardAfterSubmit && submittedGame != null) loadBoard(submittedGame);
		boardAfterSubmit = false;
	}

	private void pollBoards() {
		for (int i = 0; i < 2; i++) {
			if (boardLoads[i] != null && boardLoads[i].done()) {
				boards[i] = boardLoads[i].value();
				boardLoads[i] = null;
			}
		}
	}

	private void backdrop(Canvas c, int width, int height) {
		c.fill(0, 0, width, height, skin.backdropEdge);
		for (int i = 1; i <= 4; i++) {
			float k = i / 4f;
			int insetX = Math.round(width * 0.42f * (1f - k) + width * 0.04f);
			int insetY = Math.round(height * 0.34f * (1f - k) + height * 0.04f);
			c.fill(insetX, insetY, width - insetX, height - insetY, ColorMath.withAlpha(skin.backdrop, Math.round(70 * k)));
		}
	}

	private String number(long value) {
		if (numbers == null || numbersFor != I18n.generation()) {
			numbers = NumberFormat.getIntegerInstance(I18n.locale());
			numbersFor = I18n.generation();
		}
		return numbers.format(value);
	}

	private void box(Canvas c, int x, int y, int w, int h) {
		c.fill(x, y, x + w, y + h, BOX);
		Redstone.frame(c, x, y, w, h, BOX_EDGE);
	}

	private static String gameName(String game) {
		return I18n.tr(Replay.STAPLER.equals(game) ? "minigames.stapler.name" : "minigames.loren.name");
	}

	// --- Auswahl ---

	private static final String[] HUB_TILES = {"tnt_side", "redstone_lamp_on", "redstone_block", "piston_top", "rail", "powered_rail_on", "torch", "redstone"};
	private boolean hubFilled;

	private void fillHubTiles() {
		if (hubFilled) return;
		hubFilled = true;
		int[] pixels = new int[8 * 16 * 16];
		for (int i = 0; i < HUB_TILES.length; i++) {
			GameTextures.Tex t = textures().block(HUB_TILES[i]);
			for (int y = 0; y < 16; y++) System.arraycopy(t.d, y * 16, pixels, y * 128 + i * 16, 16);
		}
		hubTiles.upload(pixels);
	}

	private void drawHub(Canvas c, int width, int height, int mx, int my) {
		fillHubTiles();
		Theme t = Theme.get();
		int pad = 10;
		int headerY = 8;
		boolean backHover = inside(mx, my, pad, headerY, 18, 18);
		Redstone.iconButton(c, pad, headerY, 18, "back", backHover, false);
		hits.add(pad, headerY, 18, 18, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				requestClose();
			}
		});
		c.text(I18n.tr("minigames.title"), pad + 24, headerY + 5, WHITE, true);

		int top = headerY + 24;
		int contentH = height - top - pad;
		int boardW = Math.min(width * 44 / 100, 240);
		int cardsW = width - boardW - pad * 3;
		int gap = 8;
		int cardH = (contentH - gap) / 2;
		drawGameCard(c, Replay.STAPLER, pad, top, cardsW, cardH, 0, mx, my);
		drawGameCard(c, Replay.LOREN, pad, top + cardH + gap, cardsW, cardH, 4, mx, my);
		drawBoard(c, width - boardW - pad, top, boardW, contentH, t);
	}

	private void drawGameCard(Canvas c, final String game, int x, int y, int w, int h, int firstTile, int mx, int my) {
		Theme t = Theme.get();
		boolean active = selected.equals(game);
		Redstone.stone(c, x, y, w, h, active ? t.surfaceHigh : t.surface, active ? t.accent : t.border);
		hits.add(x, y, w, h, new Runnable() {
			@Override
			public void run() {
				if (!selected.equals(game)) host.playClick();
				selected = game;
			}
		});
		int ix = x + 7;
		int iy = y + 7;
		int size = 16;
		for (int i = 0; i < 4; i++) hubTiles.draw(c, ix + i * (size + 2), iy, size, size, (firstTile + i) * 16, 0, 16, 16);
		int textY = iy + size + 5;
		c.text(gameName(game), x + 7, textY, t.text, false);
		textY += 11;
		String tagline = I18n.tr(Replay.STAPLER.equals(game) ? "minigames.stapler.tagline" : "minigames.loren.tagline");
		int buttonH = 18;
		int buttonW = Math.min(90, w - 14);
		int bottom = y + h - buttonH - 7;
		List<String> lines = Paint.wrap(c, tagline, w - 14);
		for (int i = 0; i < lines.size() && textY + 9 <= bottom - 2; i++) {
			c.text(lines.get(i), x + 7, textY, t.textDim, false);
			textY += 10;
		}
		String best = I18n.tr("minigames.localBest") + ": " + number(module.best.get(game));
		if (bottom + 5 + 9 <= y + h) c.text(c.clip(best, Math.max(10, w - buttonW - 22)), x + 7, bottom + 5, t.textDim, false);
		int bx = x + w - buttonW - 7;
		boolean hover = inside(mx, my, bx, bottom, buttonW, buttonH);
		Redstone.button(c, bx, bottom, buttonW, buttonH, I18n.tr("minigames.start"), true, hover);
		hits.add(bx, bottom, buttonW, buttonH, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				enter(game);
				play();
			}
		});
	}

	private void drawBoard(Canvas c, int x, int y, int w, int h, Theme t) {
		Redstone.stone(c, x, y, w, h, t.surface, t.border);
		int tx = x + 7;
		int ty = y + 7;
		c.text(c.clip(gameName(selected), w - 14), tx, ty, t.text, false);
		ty += 11;
		c.text(c.clip(I18n.tr("minigames.board.title"), w - 14), tx, ty, t.textDim, false);
		ty += 14;
		int slot = slot(selected);
		MinigamesOnline.BoardResult result = boards[slot];
		if (result == null) {
			Paint.paragraph(c, I18n.tr("minigames.board.loading"), tx, ty, w - 14, 10, t.textDim);
			return;
		}
		if (result.status == MinigamesOnline.BoardStatus.NEEDS_TRS) {
			Paint.paragraph(c, I18n.tr("minigames.board.needsTrs"), tx, ty, w - 14, 10, t.textDim);
			return;
		}
		if (result.status == MinigamesOnline.BoardStatus.ERROR || result.board == null) {
			Paint.paragraph(c, I18n.tr("minigames.board.error"), tx, ty, w - 14, 10, t.textDim);
			return;
		}
		MinigamesApi.Board board = result.board;
		if (board.entries.isEmpty()) {
			Paint.paragraph(c, I18n.tr("minigames.board.empty"), tx, ty, w - 14, 10, t.textDim);
			return;
		}
		int footer = board.meRank > 0 ? 14 : 0;
		int rowH = 11;
		int rows = Math.max(1, (y + h - 7 - footer - ty) / rowH);
		int right = x + w - 7;
		for (int i = 0; i < board.entries.size() && i < rows; i++) {
			MinigamesApi.Entry e = board.entries.get(i);
			String rank = e.rank + ".";
			String score = number(e.score);
			int scoreW = c.textWidth(score);
			c.text(rank, tx, ty, e.rank <= 3 ? t.dustOn : t.textDim, false);
			Paint.textClipped(c, e.name, tx + 22, ty, Math.max(10, w - 14 - 22 - scoreW - 6), t.text, false);
			Paint.textRight(c, score, right, ty, t.text, false);
			ty += rowH;
		}
		if (board.meRank > 0) {
			String me = I18n.tr("minigames.board.me", board.meRank, number(board.meScore));
			c.text(c.clip(me, w - 14), tx, y + h - 7 - 9, t.dustOn, false);
		}
	}

	// --- Redstone-Stapler ---

	private void tile(Canvas c, int index, int x, int y, int size) {
		int col = index % StaplerView.ATLAS_COLS;
		int row = index / StaplerView.ATLAS_COLS;
		atlas.draw(c, x, y, size, size, col * StaplerView.TILE, row * StaplerView.TILE, StaplerView.TILE, StaplerView.TILE);
	}

	private void mini(Canvas c, Stapler.Piece piece, int ox, int oy, int s) {
		if (piece == null) return;
		int minX = 9;
		int maxX = -9;
		int minY = 9;
		int maxY = -9;
		for (int[] p : piece.b) {
			minX = Math.min(minX, p[0]);
			maxX = Math.max(maxX, p[0]);
			minY = Math.min(minY, p[1]);
			maxY = Math.max(maxY, p[1]);
		}
		int w = maxX - minX + 1;
		int h = maxY - minY + 1;
		int dx = ox + (4 - w) * s / 2 - minX * s;
		int dy = oy + (2 - h) * s / 2 - minY * s;
		for (int i = 0; i < piece.b.length; i++) tile(c, piece.mats[i], dx + piece.b[i][0] * s, dy + piece.b[i][1] * s, s);
	}

	private void meter(Canvas c, int x, int y, int w, int h, int signal) {
		Theme t = Theme.get();
		int cell = Math.max(1, (w - 14) / 15);
		int on = t.dustOn;
		for (int i = 0; i < 15; i++) {
			int cx = x + i * (cell + 1);
			c.fill(cx, y, cx + cell, y + h, i < signal ? on : 0x18FFFFFF);
		}
	}

	private void drawStapler(Canvas c, int width, int height) {
		int fh = height - 8;
		int fw = fh * StaplerView.WIDTH / StaplerView.HEIGHT;
		int fx = (width - fw) / 2;
		int fy = 4;
		session.present(c, fx, fy, fw, fh);
		Stapler sim = session.stapler;
		int sideW = Math.min(120, (width - fw) / 2 - 10);
		if (sideW < 56) return;
		int gap = 4;
		int s = Math.max(4, Math.min(12, (sideW - 14) / 4));
		int lh = c.lineHeight() + 1;

		int lx = fx - 6 - sideW;
		int ly = fy;
		int holdH = lh + 2 * s + 10;
		box(c, lx, ly, sideW, holdH);
		c.text(c.clip(I18n.tr("minigames.stapler.hold"), sideW - 8), lx + 4, ly + 4, DIM, false);
		if (sim != null) mini(c, sim.hold, lx + (sideW - 4 * s) / 2, ly + 4 + lh + 1, s);
		ly += holdH + gap;
		int signal = session.signal();
		boolean ready = signal >= 15;
		int signalH = lh + 8 + lh + 8;
		box(c, lx, ly, sideW, signalH);
		c.text(c.clip(I18n.tr("minigames.signal"), sideW - 8), lx + 4, ly + 4, DIM, false);
		meter(c, lx + 4, ly + 4 + lh, sideW - 8, 6, signal);
		String hint = ready ? I18n.tr("minigames.stapler.pulseReady") : signal + " / 15";
		c.text(c.clip(hint, sideW - 8), lx + 4, ly + 4 + lh + 8, ready ? WHITE : DIM, false);
		ly += signalH + gap;
		if (sim != null) value(c, lx, ly, sideW, I18n.tr("minigames.stapler.lamps"), String.valueOf(sim.litLamps()));

		int rx = fx + fw + 6;
		int ry = fy;
		int nextH = lh + 3 * (2 * s + 3) + 8;
		box(c, rx, ry, sideW, nextH);
		c.text(c.clip(I18n.tr("minigames.stapler.next"), sideW - 8), rx + 4, ry + 4, DIM, false);
		if (sim != null) {
			for (int i = 0; i < 3 && i < sim.queue.size(); i++) {
				mini(c, sim.queue.get(i), rx + (sideW - 4 * s) / 2, ry + 4 + lh + i * (2 * s + 3), s);
			}
		}
		ry += nextH + gap;
		ry = value(c, rx, ry, sideW, I18n.tr("minigames.score"), number(session.score())) + gap;
		ry = value(c, rx, ry, sideW, I18n.tr("minigames.localBest"), number(module.best.get(session.game))) + gap;
		if (sim != null) {
			ry = value(c, rx, ry, sideW, I18n.tr("minigames.stapler.levelLines"), sim.level + " · " + sim.lines) + gap;
			value(c, rx, ry, sideW, I18n.tr("minigames.stapler.combo"), sim.combo > 1 ? "×" + sim.combo : "–");
		}
	}

	private int value(Canvas c, int x, int y, int w, String label, String value) {
		int lh = c.lineHeight() + 1;
		int h = lh * 2 + 6;
		box(c, x, y, w, h);
		c.text(c.clip(label, w - 8), x + 4, y + 3, DIM, false);
		c.text(c.clip(value, w - 8), x + 4, y + 3 + lh, WHITE, false);
		return y + h;
	}

	// --- Loren-Rausch ---

	private void drawLoren(Canvas c, int width, int height) {
		int fw = Math.min(width, height * LorenView.WIDTH / LorenView.HEIGHT);
		int fh = fw * LorenView.HEIGHT / LorenView.WIDTH;
		int fx = (width - fw) / 2;
		int fy = (height - fh) / 2;
		session.present(c, fx, fy, fw, fh);
		Loren sim = session.loren;
		if (sim == null || session.state == MinigameSession.State.READY) return;
		c.fill(fx, fy, fx + fw, fy + 26, 0x70000000);
		c.fill(fx, fy + fh - 28, fx + fw, fy + fh, 0x70000000);
		int pad = 8;
		c.text(I18n.tr("minigames.score"), fx + pad, fy + 4, DIM, true);
		c.text(number(sim.score), fx + pad, fy + 14, WHITE, true);
		int right = fx + fw - pad;
		String zone = I18n.tr("minigames.loren.zones." + session.lorenView.zoneName(sim));
		Paint.textRight(c, zone, right, fy + 4, DIM, true);
		long tenth = Math.round(sim.speed * 600.0 / Loren.UNIT);
		String speed = I18n.tr("minigames.loren.speed", String.format(I18n.locale(), "%.1f", tenth / 10.0));
		String blocks = I18n.tr("minigames.loren.blocks", number(sim.pos / Loren.UNIT));
		Paint.textRight(c, speed + " · " + blocks, right, fy + 14, WHITE, true);
		int signal = sim.signal;
		boolean ready = signal >= 15;
		meter(c, fx + pad, fy + fh - 25, Math.min(fw - pad * 2, 140), 6, signal);
		String hint = sim.turbo > 0 ? I18n.tr("minigames.loren.turboActive") : ready ? I18n.tr("minigames.loren.turboReady")
				: signal >= 5 ? I18n.tr("minigames.loren.shield", signal) : signal + " / 15";
		c.text(c.clip(hint, fw - pad * 2), fx + pad, fy + fh - 15, ready || sim.turbo > 0 ? WHITE : DIM, true);
	}

	// --- Karte über dem Spiel ---

	private void drawCard(Canvas c, int width, int height, int mx, int my) {
		c.flush();
		c.push();
		c.raise(300f);
		c.fill(0, 0, width, height, DARKEN);
		MinigameSession.State state = session.state;
		boolean over = state == MinigameSession.State.OVER && lastRun != null;
		boolean paused = state == MinigameSession.State.PAUSED;
		boolean stapler = session.isStapler();
		int cardW = Math.min(width - 16, 320);
		int textW = cardW - 20;

		String title = over ? I18n.tr(stapler ? "minigames.stapler.over" : "minigames.loren.over")
				: paused ? I18n.tr("minigames.paused") : gameName(session.game);
		String line = null;
		String line2 = null;
		if (over) {
			line = number(lastRun.score);
			line2 = lastWasBest ? I18n.tr("minigames.newBest") : null;
		} else if (!paused) {
			line = I18n.tr(stapler ? "minigames.stapler.tagline" : "minigames.loren.tagline");
		}
		String status = over ? statusText() : null;
		String keys = !over && !paused ? I18n.tr(stapler ? "minigames.stapler.keys" : "minigames.loren.keys") : null;

		int lh = 10;
		int buttonsH = 18 + 12;
		int h = 12 + lh + 4;
		List<String> lineWrapped = line == null ? null : Paint.wrap(c, line, textW);
		if (lineWrapped != null) h += lineWrapped.size() * lh + 2;
		if (line2 != null) h += lh;
		List<String> statusWrapped = status == null ? null : Paint.wrap(c, status, textW);
		if (statusWrapped != null) h += statusWrapped.size() * lh + 3;
		List<String> keysWrapped = keys == null ? null : Paint.wrap(c, keys, textW);
		h += buttonsH + 10;
		int room = height - 16;
		// Regeln und Tasten nur, wenn sie ganz passen: im kleinen Fenster bleibt der Knopf das Wichtigste.
		boolean showRules = stapler && !over && !paused;
		int rulesH = 0;
		if (showRules) {
			for (int i = 0; i < 4; i++) rulesH += ruleHeight(c, i, textW) + 3;
			rulesH += 5;
			if (h + rulesH > room) showRules = false;
		}
		if (showRules) h += rulesH;
		boolean showKeys = keysWrapped != null && h + keysWrapped.size() * lh + 6 <= room;
		if (showKeys) h += keysWrapped.size() * lh + 6;
		h = Math.min(h, room);

		int x = (width - cardW) / 2;
		int y = (height - h) / 2;
		Redstone.window(c, x, y, cardW, h);
		Theme t = Theme.get();
		int cy = y + 8;
		Paint.textCentered(c, title, x + cardW / 2, cy, t.text, false);
		cy += lh + 4;
		if (lineWrapped != null) {
			for (String s : lineWrapped) {
				Paint.textCentered(c, s, x + cardW / 2, cy, over ? WHITE : t.textDim, false);
				cy += lh;
			}
			cy += 2;
		}
		if (line2 != null) {
			Paint.textCentered(c, line2, x + cardW / 2, cy, t.dustOn, false);
			cy += lh;
		}
		if (statusWrapped != null) {
			for (String s : statusWrapped) {
				Paint.textCentered(c, s, x + cardW / 2, cy, t.textDim, false);
				cy += lh;
			}
			cy += 3;
		}
		if (showRules) {
			cy += 2;
			for (int i = 0; i < 4; i++) {
				cy = drawRule(c, i, x + 10, cy, textW) + 3;
			}
			cy += 3;
		}
		if (showKeys) {
			cy += 3;
			for (String s : keysWrapped) {
				Paint.textCentered(c, s, x + cardW / 2, cy, t.textDim, false);
				cy += lh;
			}
		}
		// Knöpfe stehen unten in der Karte.
		int by = y + h - 18 - 7 - 11;
		String primary = paused ? I18n.tr("minigames.resume") : over ? I18n.tr("minigames.again") : I18n.tr("minigames.start");
		int bw = Math.min(110, (cardW - 30) / 2);
		int bx1 = x + cardW / 2 - bw - 3;
		int bx2 = x + cardW / 2 + 3;
		boolean waiting = starting != null;
		boolean primaryHover = !waiting && inside(mx, my, bx1, by, bw, 18);
		Redstone.button(c, bx1, by, bw, 18, waiting ? I18n.tr("minigames.starting") : primary, true, primaryHover);
		if (!waiting) {
			final boolean resume = paused;
			hits.add(bx1, by, bw, 18, new Runnable() {
				@Override
				public void run() {
					host.playClick();
					if (resume) resume();
					else play();
				}
			});
		}
		boolean backHover = inside(mx, my, bx2, by, bw, 18);
		Redstone.button(c, bx2, by, bw, 18, I18n.tr("minigames.back"), false, backHover);
		hits.add(bx2, by, bw, 18, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				leaveGame();
			}
		});
		Paint.textCentered(c, I18n.tr("minigames.enterHint"), x + cardW / 2, y + h - 7 - 8, t.textDim, false);
		c.pop();
	}

	private int ruleHeight(Canvas c, int i, int width) {
		String text = I18n.tr(RULE_TITLES[i]) + " " + I18n.tr(RULE_TEXTS[i]);
		return Math.max(16, Paint.wrap(c, text, width - 22).size() * 10);
	}

	private int drawRule(Canvas c, int i, int x, int y, int width) {
		Theme t = Theme.get();
		tile(c, RULE_TILES[i], x, y, 16);
		String text = I18n.tr(RULE_TITLES[i]) + " " + I18n.tr(RULE_TEXTS[i]);
		List<String> lines = Paint.wrap(c, text, width - 22);
		int ty = y;
		for (String s : lines) {
			c.text(s, x + 22, ty, t.textDim, false);
			ty += 10;
		}
		return y + Math.max(16, lines.size() * 10);
	}

	private String statusText() {
		if (submit != null) return I18n.tr("minigames.result.checking");
		if (lastRun == null || lastRun.runId == null || submitResult == null) return I18n.tr("minigames.result.local");
		switch (submitResult.kind) {
			case RANKED:
				return submitResult.improved ? I18n.tr("minigames.result.improved", submitResult.rank)
						: I18n.tr("minigames.result.notImproved", submitResult.rank, number(submitResult.best));
			case REJECTED:
				return I18n.tr("minigames.result.rejected");
			case FAILED:
				return I18n.tr("minigames.result.failed");
			default:
				return I18n.tr("minigames.result.local");
		}
	}

	private static boolean inside(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	private void resume() {
		if (session == null) return;
		session.pause(false);
		heldIgnored = heldKeys();
	}

	// --- Eingaben ---

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		String name = host.keyNameOf(rawKey);
		if (session == null) {
			if (key == UiKey.ESCAPE) {
				requestClose();
				return true;
			}
			if (key == UiKey.ENTER) {
				enter(selected);
				play();
				return true;
			}
			if (key == UiKey.UP || key == UiKey.DOWN) {
				selected = Replay.STAPLER.equals(selected) ? Replay.LOREN : Replay.STAPLER;
				return true;
			}
			return false;
		}
		if ((KEY + "m").equals(name)) {
			toggleSound();
			return true;
		}
		MinigameSession.State state = session.state;
		if (state == MinigameSession.State.READY || state == MinigameSession.State.OVER) {
			if (key == UiKey.ENTER || (KEY + "space").equals(name)) {
				play();
				return true;
			}
			if (key == UiKey.ESCAPE) {
				leaveGame();
				return true;
			}
			return true;
		}
		if (key == UiKey.ESCAPE || (KEY + "p").equals(name)) {
			if (state == MinigameSession.State.PLAYING) session.pause(true);
			else resume();
			return true;
		}
		if (state == MinigameSession.State.PAUSED && (key == UiKey.ENTER || (KEY + "space").equals(name))) {
			resume();
			return true;
		}
		if ((KEY + "r").equals(name)) {
			play();
			return true;
		}
		if (state == MinigameSession.State.PLAYING && name != null) {
			for (Bind b : session.isStapler() ? STAPLER_KEYS : LOREN_KEYS) {
				if (b.key.equals(name)) session.pressed(b.bit);
			}
		}
		return true;
	}

	/** Keine Texteingabe: Sonst käme ab Minecraft 26.3 bei jeder Taste ein Zeichen. */
	@Override
	public boolean wantsTextInput() {
		return false;
	}

	/** Im Einzelspieler ruht die Welt, solange gespielt wird. */
	@Override
	public boolean pausesGame() {
		return true;
	}

	@Override
	protected void onClosed() {
		if (session != null) session.dispose();
		session = null;
		atlas.release();
		hubTiles.release();
		host.closeScreen();
	}

	// --- Selbsttest ---

	/** Selbsttest: öffnet ein Spiel und startet eine lokale Partie mit festem Startwert. */
	public void testStart(String game, long seed) {
		testControl = true;
		testMask = 0;
		enter(game);
		beginRun(seed, null);
	}

	/** Selbsttest: feste Tastenmaske statt Tastatur. */
	public void testMask(int mask) {
		testMask = mask;
	}

	/** Selbsttest: kurzer Tastendruck für einen Takt. */
	public void testPress(int bit) {
		if (session != null) session.pressed(bit);
	}

	/** Selbsttest: öffnet ein Spiel, ohne zu starten. */
	public void testEnter(String game) {
		testControl = true;
		enter(game);
	}

	/** Selbsttest: zurück zur Auswahl. */
	public void testLeave() {
		leaveGame();
	}

	/** Selbsttest: Pause an oder aus. */
	public void testPause(boolean on) {
		if (session != null) session.pause(on);
	}

	/** Laufende Partie oder null (Selbsttest). */
	public MinigameSession session() {
		return session;
	}
}
