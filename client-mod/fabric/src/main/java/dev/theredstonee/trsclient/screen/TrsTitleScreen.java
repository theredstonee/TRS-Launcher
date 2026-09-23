package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.server.QuickJoin;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.Hotspots;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
//? if >=1.17
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
//? if >=1.21 {
import net.minecraft.client.gui.screens.options.OptionsScreen;
//?} else
/*import net.minecraft.client.gui.screens.OptionsScreen;*/

/**
 * TRS-Startbildschirm (ersetzt den Vanilla-Titelbildschirm, abschaltbar im Modul "Startbildschirm"):
 * im Code gezeichneter Pixel-Schriftzug in Markenfarben, Hauptknöpfe und eine Schnellbeitritt-Leiste
 * mit den ersten Servern aus servers.dat (die der Launcher mit seiner Serverliste abgleicht).
 */
public final class TrsTitleScreen extends TrsScreen {
	private static final int MAX_SERVERS = 4;
	private static final int BTN_W = 180;
	private static final int BTN_H = 18;
	private static final int BTN_GAP = 4;
	private static final String MODMENU_SCREEN = "com.terraformersmc.modmenu.gui.ModsScreen";

	private final Hotspots hot = new Hotspots();
	/** Zuletzt gezeichnete Knöpfe {x, y, w, h} nach Beschriftung (Selbsttest: echte Klicks). */
	private final java.util.Map<String, int[]> spots = new java.util.HashMap<>();
	private final List<QuickJoin.Server> servers = new ArrayList<>();
	private final List<ServerData> serverData = new ArrayList<>();
	private final String versionLine;
	private final boolean modMenu = FabricLoader.getInstance().isModLoaded("modmenu");

	public TrsTitleScreen() {
		super(Mc.text("TRS Client"));
		String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		String trsVersion = FabricLoader.getInstance().getModContainer(TrsClient.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		versionLine = "Minecraft " + mcVersion + " · TRS Client " + trsVersion;
	}

	@Override
	protected void init() {
		super.init();
		loadServers();
	}

	/** Liest servers.dat (Vanilla-Serverliste) und wählt die ersten Einträge für die Schnellbeitritt-Leiste. */
	private void loadServers() {
		servers.clear();
		serverData.clear();
		if (!TrsClient.get().modules().titleServers.get()) return;
		try {
			ServerList list = new ServerList(minecraft);
			list.load();
			List<QuickJoin.Server> all = new ArrayList<>();
			for (int i = 0; i < list.size(); i++) all.add(new QuickJoin.Server(list.get(i).name, list.get(i).ip));
			for (QuickJoin.Server s : QuickJoin.pick(all, MAX_SERVERS)) {
				servers.add(s);
				for (int i = 0; i < list.size(); i++) {
					ServerData d = list.get(i);
					if (d.ip != null && d.ip.trim().equals(s.address()) && !serverData.contains(d)) {
						serverData.add(d);
						break;
					}
				}
			}
		} catch (RuntimeException e) {
			TrsClient.LOGGER.warn("Serverliste konnte nicht gelesen werden", e);
			servers.clear();
			serverData.clear();
		}
	}

	@Override
	protected boolean customBackground() {
		return true;
	}

	@Override
	protected void drawBackground(Gfx g, float partialTick) {
		g.fill(0, 0, width, height, Brand.BG);
		// Dezentes "Schaltkreis"-Raster in Tiefenschiefer mit einzelnen Lampen.
		for (int x = 12; x < width; x += 24) g.fill(x, 0, x + 1, height, 0xFF1B1B23);
		for (int y = 12; y < height; y += 24) g.fill(0, y, width, y + 1, 0xFF1B1B23);
		for (int i = 0; i < 9; i++) {
			int lx = 12 + ((i * 7 + 3) % Math.max(1, width / 24)) * 24;
			int ly = 12 + ((i * 5 + 2) % Math.max(1, height / 24)) * 24;
			g.fill(lx - 1, ly - 1, lx + 2, ly + 2, i % 3 == 0 ? 0x60FFB84D : 0x40E0281E);
		}
		// Redstone-Linie oben
		g.fill(0, 0, width, 2, Brand.RED);
	}

	@Override
	protected void draw(Gfx g, int mouseX, int mouseY, float partialTick) {
		hot.clear();
		boolean compact = height < 300;

		// Schriftzug "TRS" groß, "CLIENT" klein darunter
		int big = compact ? 6 : 8;
		int small = compact ? 2 : 3;
		int logoW = PixelFont.width("TRS") * big;
		int logoX = (width - logoW) / 2;
		int logoY = compact ? 14 : 30;
		pixelText(g, "TRS", logoX + big / 2, logoY + big / 2, big, 0xFF5A0F0B);
		pixelText(g, "TRS", logoX, logoY, big, Brand.RED);
		int subW = PixelFont.width("CLIENT") * small;
		int subY = logoY + PixelFont.HEIGHT * big + (compact ? 5 : 8);
		pixelText(g, "CLIENT", (width - subW) / 2, subY, small, Brand.TEXT);
		// Lampe rechts neben "CLIENT"
		int lampX = (width + subW) / 2 + small * 2;
		g.fill(lampX, subY + small * 2, lampX + small * 3, subY + small * 5, Brand.AMBER);

		// Knöpfe
		int y = subY + PixelFont.HEIGHT * small + (compact ? 10 : 18);
		int x = (width - BTN_W) / 2;
		y = button(g, mouseX, mouseY, x, y, "Einzelspieler", true, () -> open(new SelectWorldScreen(this)));
		y = button(g, mouseX, mouseY, x, y, "Mehrspieler", false, () -> open(new JoinMultiplayerScreen(this)));
		int half = (BTN_W - BTN_GAP) / 2;
		halfButton(g, mouseX, mouseY, x, y, half, "Einstellungen", this::openOptions);
		halfButton(g, mouseX, mouseY, x + half + BTN_GAP, y, half, "TRS-Menü", () -> open(new TrsMenuScreen(this)));
		y += BTN_H + BTN_GAP;
		if (modMenu) {
			halfButton(g, mouseX, mouseY, x, y, half, "Mods", this::openModMenu);
			halfButton(g, mouseX, mouseY, x + half + BTN_GAP, y, half, "Beenden", minecraft::stop);
		} else {
			halfButton(g, mouseX, mouseY, x + (BTN_W - half) / 2, y, half, "Beenden", minecraft::stop);
		}
		y += BTN_H + BTN_GAP;

		drawServers(g, mouseX, mouseY, y + (compact ? 4 : 12));

		// Fußzeile
		g.text(font, versionLine, 4, height - 10, Brand.TEXT_DIM, false);
		String classic = "Klassischer Titelbildschirm";
		int cw = font.width(classic);
		boolean hover = inside(mouseX, mouseY, width - cw - 4, height - 11, cw, 10);
		g.text(font, classic, width - cw - 4, height - 10, hover ? Brand.AMBER : Brand.TEXT_DIM, false);
		hot.add(width - cw - 4, height - 11, cw, 10, () -> TrsClient.get().openVanillaTitle());
	}

	private void drawServers(Gfx g, int mx, int my, int y) {
		if (servers.isEmpty()) return;
		int n = servers.size();
		int cardW = Math.min(120, (width - 24 - (n - 1) * BTN_GAP) / n);
		int cardH = 26;
		if (y + 12 + cardH > height - 14) return; // Fenster zu klein
		int total = n * cardW + (n - 1) * BTN_GAP;
		int x0 = (width - total) / 2;
		g.text(font, "Server", x0, y, Brand.TEXT_DIM, false);
		y += 11;
		for (int i = 0; i < n; i++) {
			QuickJoin.Server s = servers.get(i);
			int cx = x0 + i * (cardW + BTN_GAP);
			boolean hover = inside(mx, my, cx, y, cardW, cardH);
			g.fill(cx, y, cx + cardW, y + cardH, hover ? Brand.SURFACE_HOVER : Brand.SURFACE);
			g.fill(cx, y, cx + 2, y + cardH, hover ? Brand.AMBER : Brand.RED);
			g.text(font, Gfx.clip(font, s.label(), cardW - 10), cx + 6, y + 4, Brand.TEXT, false);
			g.text(font, Gfx.clip(font, s.address(), cardW - 10), cx + 6, y + 15, Brand.TEXT_DIM, false);
			if (i < serverData.size()) {
				ServerData data = serverData.get(i);
				hot.add(cx, y, cardW, cardH, () -> join(data));
			}
		}
	}

	private void join(ServerData data) {
		//? if >=1.20.5 {
		ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(data.ip), data, false, null);
		//?} elif >=1.20 {
		/*ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(data.ip), data, false);
		*///?} elif >=1.17 {
		/*ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(data.ip), data);
		*///?} else
		/*open(new ConnectScreen(this, minecraft, data));*/
	}

	private void openOptions() {
		//? if >=26.1 && <26.3 {
		/*open(new OptionsScreen(this, minecraft.options, false));
		*///?} else
		open(new OptionsScreen(this, minecraft.options));
	}

	/** ModMenu ist optional – daher per Reflection, ohne Abhängigkeit. */
	private void openModMenu() {
		try {
			Class<?> type = Class.forName(MODMENU_SCREEN);
			open((Screen) type.getConstructor(Screen.class).newInstance(this));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			TrsClient.LOGGER.warn("ModMenu konnte nicht geöffnet werden", e);
		}
	}

	private void pixelText(Gfx g, String text, int x, int y, int scale, int color) {
		for (int[] r : PixelFont.rects(text)) {
			g.fill(x + r[0] * scale, y + r[1] * scale, x + r[2] * scale, y + r[3] * scale, color);
		}
	}

	private int button(Gfx g, int mx, int my, int x, int y, String label, boolean primary, Runnable action) {
		Brand.button(g, font, x, y, BTN_W, BTN_H, label, primary, inside(mx, my, x, y, BTN_W, BTN_H));
		spots.put(label, new int[]{x, y, BTN_W, BTN_H});
		hot.add(x, y, BTN_W, BTN_H, action);
		return y + BTN_H + BTN_GAP;
	}

	private void halfButton(Gfx g, int mx, int my, int x, int y, int w, String label, Runnable action) {
		Brand.button(g, font, x, y, w, BTN_H, label, false, inside(mx, my, x, y, w, BTN_H));
		spots.put(label, new int[]{x, y, w, BTN_H});
		hot.add(x, y, w, BTN_H, action);
	}

	@Override
	protected boolean onClick(double mouseX, double mouseY, int button) {
		if (hot.click(mouseX, mouseY, button)) {
			clickSound();
			return true;
		}
		return false;
	}

	/** Lage eines Knopfs {x, y, w, h} im letzten Bild oder null (Selbsttest). */
	public int[] spot(String label) {
		return spots.get(label);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
