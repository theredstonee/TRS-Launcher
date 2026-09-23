package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.pack.PackList;
import dev.theredstonee.trsclient.core.util.PlatformOpen;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.Hotspots;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.ResourcePackRepository;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resourcepack-Menü: Suche, Filter (Alle/Aktiv/Verfügbar), Ein/Aus per Klick, Priorität ▲/▼,
 * "Ordner öffnen". Änderungen werden erst mit "Übernehmen" angewendet (ein einziger Reload).
 * Legacy: arbeitet direkt mit {@link ResourcePackRepository} wie Vanillas GuiScreenResourcePacks.
 */
public final class PackScreen extends TrsScreen {
	private final String TITLE = "§l" + I18n.tr("packs.title");
	private final String HINT = I18n.tr("packs.searchHint");
	private static final int ROW_H = 24;

	private final GuiScreen parent;
	private final Hotspots hot = new Hotspots();
	private final List<PackList.Entry> entries = new ArrayList<>();
	private final List<String> fixedIds = new ArrayList<>();
	private final Map<String, ResourcePackRepository.Entry> packs = new HashMap<>();
	private List<String> enabledIds = new ArrayList<>();
	private List<String> originalIds = Collections.emptyList();
	private PackList.Filter filter = PackList.Filter.ALL;
	private GuiTextField search;
	private int scroll;
	private int maxScroll;
	private int listX;
	private int listY;
	private int listW;
	private int listH;
	private String status = "";

	public PackScreen(GuiScreen parent) {
		this.parent = parent;
	}

	@Override
	public void initGui() {
		super.initGui();
		Keyboard.enableRepeatEvents(true);
		if (entries.isEmpty()) loadPacks();
		int pw = panelW();
		int px = (width - pw) / 2;
		int py = panelY();
		String query = search == null ? "" : search.getText();
		search = new GuiTextField(0, font, px + 11, py + 31, pw - 22 - 96, 14);
		search.setMaxStringLength(64);
		search.setText(query);
		search.setFocused(true);
	}

	@Override
	public void updateScreen() {
		super.updateScreen();
		if (search != null) search.updateCursorCounter();
	}

	private void loadPacks() {
		ResourcePackRepository repo = minecraft.getResourcePackRepository();
		repo.updateRepositoryEntriesAll();
		entries.clear();
		fixedIds.clear();
		packs.clear();
		List<ResourcePackRepository.Entry> all = new ArrayList<>(repo.getRepositoryEntriesAll());
		for (ResourcePackRepository.Entry e : repo.getRepositoryEntries()) if (!all.contains(e)) all.add(e);
		for (ResourcePackRepository.Entry p : all) {
			String id = p.getResourcePackName();
			if (packs.containsKey(id)) continue;
			packs.put(id, p);
			entries.add(new PackList.Entry(id, id, stripFormatting(p.getTexturePackDescription()), false, true));
		}
		// Minecraft-Reihenfolge: erster Eintrag = niedrigste, letzter = höchste Priorität.
		enabledIds = new ArrayList<>();
		for (ResourcePackRepository.Entry e : repo.getRepositoryEntries()) enabledIds.add(e.getResourcePackName());
		originalIds = new ArrayList<>(enabledIds);
	}

	private static String stripFormatting(String s) {
		return s == null ? "" : s.replaceAll("(?i)§[0-9A-FK-OR]", "");
	}

	private int panelW() {
		return Math.min(440, width - 16);
	}

	private int panelY() {
		return Math.max(8, (height - panelH()) / 2);
	}

	private int panelH() {
		return Math.min(280, height - 16);
	}

	/** Abgedunkelter Hintergrund + Panel; das Suchfeld (Vanilla-Widget) wird danach darüber gezeichnet. */
	@Override
	protected boolean customBackground() {
		return true;
	}

	@Override
	protected void drawBackground(Gfx g, float partialTick) {
		g.fill(0, 0, width, height, Mc.world() == null ? Brand.BG : 0xC00C0C11);
		int pw = panelW();
		int px = (width - pw) / 2;
		int py = panelY();
		g.fill(px, py, px + pw, py + panelH(), Brand.BG);
	}

	@Override
	protected void draw(Gfx g, int mouseX, int mouseY, float partialTick) {
		hot.clear();
		int pw = panelW();
		int ph = panelH();
		int px = (width - pw) / 2;
		int py = panelY();
		g.fill(px, py, px + pw, py + 26, Brand.SURFACE);
		g.fill(px, py, px + 3, py + 26, Brand.RED);
		Brand.outline(g, px - 1, py - 1, pw + 2, ph + 2, Brand.BORDER);
		g.text(font, TITLE, px + 11, py + 9, Brand.TEXT, false);
		// Suchfeld (Vanilla-Textfeld) mit Platzhalter
		search.drawTextBox();
		if (search.getText().isEmpty()) g.text(font, HINT, px + 17, py + 34, Brand.TEXT_DIM, false);
		String count = I18n.tr("packs.count", enabledCount(), entries.size());
		g.text(font, count, px + pw - 10 - font.getStringWidth(count), py + 9, Brand.TEXT_DIM, false);

		// Filter-Knopf rechts neben der Suche
		int fx = px + pw - 10 - 90;
		String fl = I18n.tr("packs.filter", filter.display());
		Brand.button(g, font, fx, py + 30, 90, 16, fl, false, inside(mouseX, mouseY, fx, py + 30, 90, 16));
		hot.add(fx, py + 30, 90, 16, () -> {
			filter = filter.next();
			scroll = 0;
		});

		// Liste
		listX = px + 10;
		listY = py + 52;
		listW = pw - 20;
		listH = ph - 52 - 30;
		g.fill(listX, listY, listX + listW, listY + listH, Brand.BG);
		List<PackList.Entry> visible = PackList.visible(entries, enabledIds, filter, search.getText());
		maxScroll = Math.max(0, visible.size() * ROW_H - listH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		boolean mouseInList = inside(mouseX, mouseY, listX, listY, listW, listH);
		g.scissor(listX, listY, listX + listW, listY + listH);
		for (int i = 0; i < visible.size(); i++) {
			int ry = listY + i * ROW_H - scroll;
			if (ry + ROW_H < listY || ry > listY + listH) continue;
			drawRow(g, visible.get(i), listX, ry, listW, mouseX, mouseY, mouseInList);
		}
		g.noScissor();
		if (visible.isEmpty()) {
			g.centered(font, I18n.tr("packs.empty"), listX + listW / 2, listY + listH / 2 - 4, Brand.TEXT_DIM);
		}
		if (maxScroll > 0) {
			int barH = Math.max(12, listH * listH / (listH + maxScroll));
			int barY = listY + (listH - barH) * scroll / maxScroll;
			g.fill(listX + listW - 2, barY, listX + listW, barY + barH, Brand.TEXT_DIM);
		}

		// Fußzeile
		int by = py + ph - 24;
		int ow = 92;
		Brand.button(g, font, px + 10, by, ow, 16, I18n.tr("packs.openFolder"), false, inside(mouseX, mouseY, px + 10, by, ow, 16));
		hot.add(px + 10, by, ow, 16, this::openFolder);
		if (!status.isEmpty()) g.text(font, status, px + 10 + ow + 8, by + 4, Brand.TEXT_DIM, false);
		int dw = 76;
		int cw = 70;
		int dx = px + pw - 10 - dw;
		int cx = dx - 6 - cw;
		boolean changed = !enabledIds.equals(originalIds);
		Brand.button(g, font, dx, by, dw, 16, changed ? I18n.tr("common.apply") : I18n.tr("common.done"), true, inside(mouseX, mouseY, dx, by, dw, 16));
		hot.add(dx, by, dw, 16, this::apply);
		Brand.button(g, font, cx, by, cw, 16, I18n.tr("common.cancel"), false, inside(mouseX, mouseY, cx, by, cw, 16));
		hot.add(cx, by, cw, 16, this::onClose);
	}

	private void drawRow(Gfx g, PackList.Entry e, int x, int y, int w, int mx, int my, boolean mouseInList) {
		boolean on = enabledIds.contains(e.id());
		boolean hover = mouseInList && inside(mx, my, x, y, w, ROW_H - 2);
		g.fill(x + 1, y + 1, x + w - 1, y + ROW_H - 1, hover ? Brand.SURFACE_HOVER : Brand.SURFACE);
		g.fill(x + 1, y + 1, x + 3, y + ROW_H - 1, on ? Brand.AMBER : Brand.OFF);
		int textRight = x + w - 8 - 26 - (on ? 30 : 0);
		String title = e.title() + (e.compatible() ? "" : "  " + I18n.tr("packs.incompatible"));
		g.text(font, font.trimStringToWidth(title, textRight - x - 10), x + 8, y + 4, e.compatible() ? Brand.TEXT : 0xFFFF8080, false);
		g.text(font, font.trimStringToWidth(e.description().replace('\n', ' '), textRight - x - 10), x + 8, y + 14, Brand.TEXT_DIM, false);

		int pillX = x + w - 8 - 26;
		int pillY = y + 7;
		if (e.required()) {
			g.text(font, I18n.tr("packs.required"), pillX - 2, pillY + 2, Brand.TEXT_DIM, false);
		} else {
			Brand.pill(g, font, pillX, pillY, on, mouseInList && inside(mx, my, pillX, pillY, 26, 11));
			if (mouseInList) hot.add(pillX, pillY, 26, 11, () -> enabledIds = new ArrayList<>(PackList.toggle(enabledIds, e)));
		}
		if (on && !fixedIds.contains(e.id())) {
			// ▲ = höhere Priorität (weiter hinten in der Liste), ▼ = niedrigere
			int ax = pillX - 30;
			arrow(g, ax, y + 4, "▲", mx, my, mouseInList, () -> enabledIds = PackList.move(enabledIds, e.id(), +1, fixedIds));
			arrow(g, ax + 13, y + 4, "▼", mx, my, mouseInList, () -> enabledIds = PackList.move(enabledIds, e.id(), -1, fixedIds));
		}
		if (mouseInList && !e.required()) {
			// Klick auf die Zeile schaltet ebenfalls um
			hot.add(x, y, pillX - 32 - x, ROW_H - 2, () -> enabledIds = new ArrayList<>(PackList.toggle(enabledIds, e)));
		}
	}

	private void arrow(Gfx g, int x, int y, String label, int mx, int my, boolean mouseInList, Runnable action) {
		boolean hover = mouseInList && inside(mx, my, x, y, 11, 15);
		g.fill(x, y, x + 11, y + 15, hover ? Brand.RED : Brand.OFF);
		g.centered(font, label, x + 6, y + 4, Brand.TEXT);
		if (mouseInList) hot.add(x, y, 11, 15, action);
	}

	private int enabledCount() {
		int n = 0;
		for (PackList.Entry e : entries) if (enabledIds.contains(e.id()) && !e.required()) n++;
		return n;
	}

	private void openFolder() {
		try {
			PlatformOpen.open(minecraft.getResourcePackRepository().getDirResourcepacks().toPath());
			status = I18n.tr("packs.folderOpened");
		} catch (IOException | RuntimeException e) {
			TrsClient.LOGGER.warn("Resourcepack-Ordner konnte nicht geöffnet werden", e);
			status = I18n.tr("packs.folderFailed");
		}
	}

	/** Übernimmt die Auswahl (Minecraft speichert sie in options.txt und lädt die Ressourcen neu). */
	private void apply() {
		if (!enabledIds.equals(originalIds)) {
			// Wie GuiScreenResourcePacks: Reihenfolge setzen, in options.txt speichern, Ressourcen neu laden.
			ResourcePackRepository repo = minecraft.getResourcePackRepository();
			List<ResourcePackRepository.Entry> chosen = new ArrayList<>();
			for (String id : enabledIds) {
				ResourcePackRepository.Entry e = packs.get(id);
				if (e != null) chosen.add(e);
			}
			repo.setRepositories(chosen);
			minecraft.gameSettings.resourcePacks.clear();
			for (ResourcePackRepository.Entry e : repo.getRepositoryEntries()) minecraft.gameSettings.resourcePacks.add(e.getResourcePackName());
			minecraft.gameSettings.saveOptions();
			minecraft.refreshResources();
			originalIds = new ArrayList<>(enabledIds);
		}
		onClose();
	}

	@Override
	protected boolean onClick(double mouseX, double mouseY, int button) {
		search.mouseClicked((int) mouseX, (int) mouseY, button);
		if (hot.click(mouseX, mouseY, button)) {
			clickSound();
			return true;
		}
		return false;
	}

	@Override
	protected boolean onScroll(double mouseX, double mouseY, double amount) {
		if (!inside(mouseX, mouseY, listX, listY, listW, listH)) return false;
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(amount) * ROW_H));
		return true;
	}

	@Override
	protected boolean onKey(int key, char typed) {
		if (key == Keyboard.KEY_ESCAPE || !search.isFocused()) return false;
		if (search.textboxKeyTyped(typed, key)) scroll = 0;
		return true;
	}

	@Override
	public void onClose() {
		open(parent);
	}

	@Override
	public void removed() {
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
