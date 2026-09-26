package dev.theredstonee.trsclient.qol;

import dev.theredstonee.trsclient.core.hud.HudLayout;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.QolModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.pvp.ItemCounter;
import dev.theredstonee.trsclient.core.pvp.TotemPops;
import dev.theredstonee.trsclient.core.qol.Qol;
import dev.theredstonee.trsclient.core.qol.QolPanels;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.hud.HudElement;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
//? if >=1.20.3 {
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.PlayerScoreEntry;
//?} else
/*import net.minecraft.world.scores.Score;*/

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * HUD-Elemente des Komfort-/PvP-Pakets (Warnungen, Zähler, Scoreboard, Bossleiste, Titel) und die Zeichen-Haken der
 * Mixins. Scoreboard, Bossleiste und Titel zeichnet weiterhin Minecraft – hier stehen nur die Editor-Vorschau und die
 * Verschiebung. Dieselbe Datei in allen Mojmap-Bäumen.
 */
public final class QolHuds {
	private static final TextWidth MEASURE = new TextWidth() {
		@Override
		public int width(String text) {
			return Minecraft.getInstance().font.width(text);
		}
	};

	private QolHuds() {
	}

	/** Neue HUD-Elemente (werden an die Liste des HudManagers gehängt). */
	public static List<HudElement> create(TrsModules modules) {
		QolModules m = modules.qol;
		return Arrays.<HudElement>asList(new Warnings(m.warnings), new Counter(m.itemCounter, m), new Sidebar(m.scoreboard, m),
				new BossBar(m.bossBar), new Titles(m.titles));
	}

	/** Vorhandene Elemente des HudManagers + die neuen. */
	public static List<HudElement> join(List<HudElement> base, TrsModules modules) {
		List<HudElement> all = new ArrayList<HudElement>(base);
		all.addAll(create(modules));
		return all;
	}

	/** Nach allen HUD-Elementen: Hitmarker; Hinweise auch ohne Warnungs-Modul (Warteschlange, Hintergrund). */
	public static void overlay(Gfx g, Font font) {
		Qol q = QolHooks.qol();
		if (q == null) return;
		long now = System.currentTimeMillis();
		GfxCanvas c = null;
		if (q.m.hitFeedback.isEnabled() && q.m.hitMarker.get()) {
			c = GfxCanvas.of(g, font);
			QolPanels.drawHitmarker(c, q, g.width(), g.height(), now);
		}
		if (!q.m.warnings.isEnabled() && !q.notices.isEmpty()) {
			if (c == null) c = GfxCanvas.of(g, font);
			HudModule w = q.m.warnings;
			int bw = QolPanels.warningsWidth(q, null, MEASURE, false, now);
			int bh = QolPanels.warningsHeight(q, null, false, now);
			int x = HudLayout.resolveX(w.position(), bw, g.width());
			int y = HudLayout.resolveY(w.position(), bh, g.height());
			g.push();
			g.translate(x, y);
			QolPanels.drawWarnings(c, q, null, MEASURE, false, now, 0x90000000, true);
			g.pop();
		}
	}

	// --- Warnungen ---

	static final class Warnings extends HudElement {
		Warnings(HudModule module) {
			super(module);
		}

		@Override
		public boolean visible() {
			return QolPanels.warningsVisible(QolHooks.qol(), QolHooks.WHERE, System.currentTimeMillis());
		}

		@Override
		public int width(Font font, boolean preview) {
			return QolPanels.warningsWidth(QolHooks.qol(), QolHooks.WHERE, MEASURE, preview, System.currentTimeMillis());
		}

		@Override
		public int height(Font font, boolean preview) {
			return QolPanels.warningsHeight(QolHooks.qol(), QolHooks.WHERE, preview, System.currentTimeMillis());
		}

		@Override
		public void draw(Gfx g, Font font, boolean preview) {
			int bg = module.backgroundArgb();
			QolPanels.drawWarnings(GfxCanvas.of(g, font), QolHooks.qol(), QolHooks.WHERE, MEASURE, preview, System.currentTimeMillis(),
					bg == 0 ? 0x5A000000 : bg, module.shadow());
		}
	}

	// --- Zähler ---

	static final class Counter extends HudElement {
		private static final int PAD = 4;
		private static final int ROW = 18;
		private final QolModules m;
		private final List<ItemCounter.Kind> kinds = new ArrayList<ItemCounter.Kind>();
		private final List<String> texts = new ArrayList<String>();
		private final List<String> pops = new ArrayList<String>();
		private ItemStack[] fallback;

		Counter(HudModule module, QolModules m) {
			super(module);
			this.m = m;
		}

		private boolean on(ItemCounter.Kind k) {
			switch (k) {
				case ARROWS:
					return m.countArrows.get();
				case TOTEMS:
					return m.countTotems.get();
				case HEALING:
					return m.countHealing.get();
				case SPLASH:
					return m.countSplash.get();
				case GAPPLES:
					return m.countGapples.get();
				case PEARLS:
					return m.countPearls.get();
				default:
					return m.countBlocks.get();
			}
		}

		/** Sichtbare Zähler und Texte neu sammeln (billig, keine Minecraft-Objekte). */
		private void collect(boolean preview) {
			kinds.clear();
			texts.clear();
			pops.clear();
			Qol q = QolHooks.qol();
			if (q == null) return;
			int sample = 0;
			for (ItemCounter.Kind k : ItemCounter.Kind.values()) {
				if (!on(k)) continue;
				int n = preview ? new int[]{64, 1, 3, 2, 5, 16, 128}[k.ordinal()] : q.counter.count(k);
				if (n == 0 && m.countHideEmpty.get() && !preview) continue;
				kinds.add(k);
				texts.add(String.valueOf(n));
				sample++;
			}
			if (m.countTotemPops.get()) {
				if (preview) {
					pops.add("Steve: 2");
				} else {
					for (TotemPops.Entry e : q.pops.recent(System.currentTimeMillis())) {
						pops.add(QolHooks.maskedName(e.name) + ": " + e.pops);
					}
				}
			}
		}

		@Override
		public boolean visible() {
			collect(false);
			return !kinds.isEmpty() || !pops.isEmpty();
		}

		private boolean horizontal() {
			return m.countLayout.get() == ItemCounter.Layout.HORIZONTAL;
		}

		@Override
		public int width(Font font, boolean preview) {
			collect(preview);
			int w = 0;
			if (horizontal()) {
				for (String t : texts) w += 16 + 2 + font.width(t) + 6;
				if (w > 0) w -= 6;
			} else {
				for (String t : texts) w = Math.max(w, 16 + 3 + font.width(t));
			}
			for (String p : pops) w = Math.max(w, font.width(p));
			if (!pops.isEmpty()) w = Math.max(w, font.width(I18n.tr("hud.totemPops")));
			return Math.max(20, w + PAD * 2);
		}

		@Override
		public int height(Font font, boolean preview) {
			collect(preview);
			int h = kinds.isEmpty() ? 0 : (horizontal() ? ROW : kinds.size() * ROW);
			if (!pops.isEmpty()) h += (h > 0 ? 3 : 0) + 10 + pops.size() * 10;
			return Math.max(10, h + PAD * 2 - 2);
		}

		@Override
		public void draw(Gfx g, Font font, boolean preview) {
			collect(preview);
			int w = width(font, preview);
			int h = height(font, preview);
			collect(preview);
			int bg = module.backgroundArgb();
			if (bg != 0) g.fill(0, 0, w, h, bg);
			Qol q = QolHooks.qol();
			int x = PAD, y = PAD - 1;
			for (int i = 0; i < kinds.size(); i++) {
				ItemCounter.Kind k = kinds.get(i);
				Object icon = q == null ? null : q.counter.icon(k);
				ItemStack stack = icon instanceof ItemStack && !preview ? (ItemStack) icon : fallback(k);
				g.item(font, stack, x, y);
				String t = texts.get(i);
				boolean empty = "0".equals(t);
				g.text(font, t, x + 19, y + 4, empty ? 0xFFFF5555 : textColor(), module.shadow());
				if (horizontal()) x += 16 + 2 + font.width(t) + 6;
				else y += ROW;
			}
			if (!pops.isEmpty()) {
				if (horizontal() && !kinds.isEmpty()) y += ROW;
				if (!kinds.isEmpty()) y += 3;
				g.text(font, I18n.tr("hud.totemPops"), PAD, y, 0xFFFFD84A, module.shadow());
				y += 10;
				for (String p : pops) {
					g.text(font, p, PAD, y, textColor(), module.shadow());
					y += 10;
				}
			}
		}

		private ItemStack fallback(ItemCounter.Kind k) {
			if (fallback == null) {
				fallback = new ItemStack[]{new ItemStack(Items.ARROW), new ItemStack(Items.TOTEM_OF_UNDYING), new ItemStack(Items.POTION),
						new ItemStack(Items.SPLASH_POTION), new ItemStack(Items.GOLDEN_APPLE), new ItemStack(Items.ENDER_PEARL),
						new ItemStack(Items.COBBLESTONE)};
			}
			return fallback[k.ordinal()];
		}
	}

	// --- Scoreboard ---

	static final class Sidebar extends HudElement {
		private static final int PAD = 3;
		private final QolModules m;
		private static Sidebar instance;

		Sidebar(HudModule module, QolModules m) {
			super(module);
			this.m = m;
			instance = this;
		}

		/** Im Spiel zeichnet der Mixin (dort, wo Minecraft das Scoreboard zeichnen würde). */
		@Override
		public boolean visible() {
			return false;
		}

		@Override
		public int width(Font font, boolean preview) {
			return layoutWidth(font, sample());
		}

		@Override
		public int height(Font font, boolean preview) {
			return layoutHeight(sample());
		}

		@Override
		public void draw(Gfx g, Font font, boolean preview) {
			drawRows(g, font, sample());
		}

		private List<String[]> sample() {
			List<String[]> rows = new ArrayList<String[]>();
			rows.add(new String[]{I18n.tr("hud.scoreboardPreview")});
			rows.add(new String[]{"Kills", QolText.RED + "12"});
			rows.add(new String[]{"Coins", QolText.RED + "350"});
			rows.add(new String[]{"", QolText.RED + "1"});
			rows.add(new String[]{"play.example.net", QolText.RED + "0"});
			return rows;
		}

		private int layoutWidth(Font font, List<String[]> rows) {
			int w = 0;
			boolean numbers = !m.scoreboardHideNumbers.get();
			for (int i = 0; i < rows.size(); i++) {
				String[] r = rows.get(i);
				if (i == 0) {
					if (m.scoreboardTitle.get()) w = Math.max(w, font.width(r[0]));
					continue;
				}
				int lw = font.width(r[0]);
				if (numbers && r.length > 1) lw += font.width(": ") + font.width(r[1]);
				w = Math.max(w, lw);
			}
			return w + PAD * 2;
		}

		private int layoutHeight(List<String[]> rows) {
			int lines = rows.size() - 1;
			return (m.scoreboardTitle.get() ? 10 : 0) + lines * 9 + PAD;
		}

		private void drawRows(Gfx g, Font font, List<String[]> rows) {
			int w = layoutWidth(font, rows);
			int y = 0;
			int bg = module.backgroundArgb();
			boolean shadow = module.shadow();
			int color = textColor();
			if (m.scoreboardTitle.get() && !rows.isEmpty()) {
				int titleBg = bg == 0 ? 0 : (Math.min(255, ((bg >>> 24) & 0xFF) + 24) << 24) | (bg & 0xFFFFFF);
				if (titleBg != 0) g.fill(0, 0, w, 10, titleBg);
				String t = rows.get(0)[0];
				g.text(font, t, (w - font.width(t)) / 2, 1, color, shadow);
				y = 10;
			}
			int lines = rows.size() - 1;
			if (bg != 0 && lines > 0) g.fill(0, y, w, y + lines * 9 + PAD, bg);
			boolean numbers = !m.scoreboardHideNumbers.get();
			for (int i = 1; i < rows.size(); i++) {
				String[] r = rows.get(i);
				g.text(font, r[0], PAD, y + 1, color, shadow);
				if (numbers && r.length > 1) g.text(font, r[1], w - PAD - font.width(r[1]), y + 1, color, shadow);
				y += 9;
			}
		}

		/** Aus dem Gui-Mixin: statt Minecrafts Seitenleiste zeichnen. */
		static void drawInGame(Gfx g, Objective objective) {
			Sidebar s = instance;
			if (s == null) return;
			Font font = Minecraft.getInstance().font;
			List<String[]> rows = rows(objective);
			int sw = g.width(), sh = g.height();
			float scale = s.module.scale.getFloat();
			int bw = (int) Math.ceil(s.layoutWidth(font, rows) * scale);
			int bh = (int) Math.ceil(s.layoutHeight(rows) * scale);
			int x = HudLayout.resolveX(s.module.position(), bw, sw);
			int y = HudLayout.resolveY(s.module.position(), bh, sh);
			g.push();
			g.translate(x, y);
			g.scale(scale);
			s.drawRows(g, font, rows);
			g.pop();
		}

		/** Titel + höchstens 15 Zeilen (Name, Punkte) wie Vanilla, Streamer-Modus eingerechnet – als Farbcode-Text. */
		private static List<String[]> rows(Objective objective) {
			List<String[]> rows = new ArrayList<String[]>();
			rows.add(new String[]{QolText.legacy(QolHooks.maskName(objective.getDisplayName()))});
			Scoreboard board = objective.getScoreboard();
			//? if >=1.20.3 {
			NumberFormat format = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
			List<PlayerScoreEntry> list = new ArrayList<PlayerScoreEntry>();
			for (PlayerScoreEntry e : board.listPlayerScores(objective)) {
				if (!e.isHidden()) list.add(e);
			}
			Collections.sort(list, (a, b) -> a.value() != b.value() ? Integer.compare(b.value(), a.value())
					: String.CASE_INSENSITIVE_ORDER.compare(a.owner(), b.owner()));
			for (int i = 0; i < list.size() && i < 15; i++) {
				PlayerScoreEntry e = list.get(i);
				PlayerTeam team = board.getPlayersTeam(e.owner());
				Component name = PlayerTeam.formatNameForTeam(team, e.ownerName());
				rows.add(new String[]{QolText.legacy(QolHooks.maskName(name)), QolText.legacy(e.formatValue(format))});
			}
			//?} else {
			/*List<Score> list = new ArrayList<Score>();
			for (Score e : board.getPlayerScores(objective)) {
				if (e.getOwner() != null && !e.getOwner().startsWith("#")) list.add(e);
			}
			Collections.sort(list, (a, b) -> a.getScore() != b.getScore() ? Integer.compare(b.getScore(), a.getScore())
					: String.CASE_INSENSITIVE_ORDER.compare(a.getOwner(), b.getOwner()));
			for (int i = 0; i < list.size() && i < 15; i++) {
				Score e = list.get(i);
				PlayerTeam team = board.getPlayersTeam(e.getOwner());
				Component name = PlayerTeam.formatNameForTeam(team, QolText.plain(e.getOwner()));
				rows.add(new String[]{QolText.legacy(QolHooks.maskName(name)), QolText.RED + e.getScore()});
			}
			*///?}
			return rows;
		}
	}

	/** Übernimmt TRS die Seitenleiste (Modul an oder Streamer-Modus, der Namen verbergen muss)? */
	public static boolean ownsSidebar() {
		Qol q = QolHooks.qol();
		return q != null && (q.m.scoreboard.isEnabled() || q.streamer.active());
	}

	// --- Bossleiste ---

	static final class BossBar extends HudElement {
		static final int W = 182;
		static final int H = 19;
		private static BossBar instance;

		BossBar(HudModule module) {
			super(module);
			instance = this;
		}

		@Override
		public boolean visible() {
			return false;
		}

		@Override
		public int width(Font font, boolean preview) {
			return W;
		}

		@Override
		public int height(Font font, boolean preview) {
			return H;
		}

		@Override
		public void draw(Gfx g, Font font, boolean preview) {
			String name = I18n.tr("hud.bossBarPreview");
			g.text(font, name, (W - font.width(name)) / 2, 1, 0xFFFFFFFF, true);
			g.fill(0, 10, W, 15, 0xFF3B0A3A);
			g.fill(0, 10, W * 2 / 3, 15, 0xFFD03BD0);
		}

		/** Vor Minecrafts Bossleiste: an die gespeicherte Stelle schieben und skalieren. false = unverändert. */
		static boolean begin(Gfx g) {
			BossBar b = instance;
			if (b == null || !b.module.isEnabled()) return false;
			int sw = g.width(), sh = g.height();
			float scale = b.module.scale.getFloat();
			int x = HudLayout.resolveX(b.module.position(), (int) Math.ceil(W * scale), sw);
			int y = HudLayout.resolveY(b.module.position(), (int) Math.ceil(H * scale), sh);
			g.push();
			g.translate(x, y);
			g.scale(scale);
			g.translate(-(sw / 2 - 91), -3);
			return true;
		}
	}

	// --- Titel ---

	static final class Titles extends HudElement {
		static final int W = 200;
		static final int H = 68;
		private static Titles instance;

		Titles(HudModule module) {
			super(module);
			instance = this;
		}

		@Override
		public boolean visible() {
			return false;
		}

		@Override
		public int width(Font font, boolean preview) {
			return W;
		}

		@Override
		public int height(Font font, boolean preview) {
			return H;
		}

		@Override
		public void draw(Gfx g, Font font, boolean preview) {
			String t = I18n.tr("hud.titlePreview");
			g.push();
			g.translate(W / 2f, 40);
			g.scale(4);
			g.text(font, t, -font.width(t) / 2, -10, 0xFFFFD84A, true);
			g.pop();
			g.push();
			g.translate(W / 2f, 40);
			g.scale(2);
			String sub = "gg";
			g.text(font, sub, -font.width(sub) / 2, 5, 0xFFFFFFFF, true);
			g.pop();
		}

		/** Vor Minecrafts Titel (ab 1.20.5): verschieben + skalieren um die Bildschirmmitte. */
		static boolean begin(Gfx g) {
			Titles t = instance;
			if (t == null || !t.module.isEnabled()) return false;
			int sw = g.width(), sh = g.height();
			float scale = t.module.scale.getFloat();
			int bw = (int) Math.ceil(W * scale), bh = (int) Math.ceil(H * scale);
			int x = HudLayout.resolveX(t.module.position(), bw, sw);
			int y = HudLayout.resolveY(t.module.position(), bh, sh);
			g.push();
			g.translate(x + bw / 2f, y + 40 * scale);
			g.scale(scale);
			g.translate(-sw / 2f, -sh / 2f);
			return true;
		}

		/** Bis 1.20.4: nur die Größe (Faktor für Vanillas 4,0/2,0). */
		static float factor() {
			Titles t = instance;
			return t == null || !t.module.isEnabled() ? 1f : t.module.scale.getFloat();
		}
	}

	// --- Haken aus den Mixins ---

	/** Gui-Mixin: Scoreboard selbst zeichnen? true = Vanilla überspringen. */
	public static boolean sidebar(Gfx g, Objective objective) {
		if (!ownsSidebar() || objective == null) return false;
		try {
			Sidebar.drawInGame(g, objective);
			return true;
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	public static boolean bossBarBegin(Gfx g) {
		try {
			return BossBar.begin(g);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	public static boolean titleBegin(Gfx g) {
		try {
			return Titles.begin(g);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	public static void end(Gfx g) {
		g.pop();
	}

	public static float titleFactor() {
		return Titles.factor();
	}

	/**
	 * Tabliste: Ping in ms statt Balken. true = Vanilla-Symbol übersprungen.
	 *
	 * @param width Breite der Spalte, x/y deren linke obere Ecke (wie Vanilla)
	 */
	public static boolean tabPing(Gfx g, int width, int x, int y, int latency) {
		Qol q = QolHooks.qol();
		if (q == null || !q.m.tabPing.isEnabled()) return false;
		Font font = Minecraft.getInstance().font;
		String text = latency < 0 ? "?" : String.valueOf(latency);
		int color = q.m.tabPingColors.get() ? pingColor(latency) : 0xFFFFFFFF;
		g.push();
		g.translate(x + width - 1, y);
		g.scale(0.75f);
		g.text(font, text, -font.width(text), 2, color, true);
		g.pop();
		return true;
	}

	/** Farbe je Latenz: grün < 80 ms, gelb < 150, orange < 250, rot darüber, grau unbekannt. */
	public static int pingColor(int ms) {
		if (ms <= 0) return 0xFFAAAAAA;
		if (ms < 80) return 0xFF55FF55;
		if (ms < 150) return 0xFFFFFF55;
		if (ms < 250) return 0xFFFFAA00;
		return 0xFFFF5555;
	}

	/** Für Formatierungen, die der Loader nicht kennt. */
	static String red(int v) {
		return ChatFormatting.RED + String.valueOf(v);
	}
}
