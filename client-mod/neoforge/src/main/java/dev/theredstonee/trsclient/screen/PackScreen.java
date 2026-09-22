package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.pack.PackList;
import dev.theredstonee.trsclient.core.util.PlatformOpen;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.Hotspots;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resourcepack-Menü: Suche, Filter (Alle/Aktiv/Verfügbar), Ein/Aus per Klick, Priorität ▲/▼,
 * "Ordner öffnen". Änderungen werden erst mit "Übernehmen" angewendet (ein einziger Reload).
 */
public final class PackScreen extends TrsScreen {
	private static final Component TITLE = Component.literal("Resourcepacks").withStyle(ChatFormatting.BOLD);
	private static final int ROW_H = 24;

	private final Screen parent;
	private final Hotspots hot = new Hotspots();
	private final List<PackList.Entry> entries = new ArrayList<>();
	private final List<String> fixedIds = new ArrayList<>();
	private List<String> enabledIds = new ArrayList<>();
	private List<String> originalIds = List.of();
	private PackList.Filter filter = PackList.Filter.ALL;
	private EditBox search;
	private int scroll;
	private int maxScroll;
	private int listX;
	private int listY;
	private int listW;
	private int listH;
	private String status = "";

	public PackScreen(Screen parent) {
		super(Component.literal("Resourcepacks"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		if (entries.isEmpty()) loadPacks();
		int pw = panelW();
		int px = (width - pw) / 2;
		int py = panelY();
		String query = search == null ? "" : search.getValue();
		search = new EditBox(font, px + 10, py + 30, pw - 20 - 96, 16, Component.literal("Suche"));
		search.setHint(Component.literal("Packs durchsuchen …"));
		search.setMaxLength(64);
		search.setValue(query);
		search.setResponder(q -> scroll = 0);
		addRenderableWidget(search);
		setInitialFocus(search);
	}

	private void loadPacks() {
		PackRepository repo = minecraft.getResourcePackRepository();
		repo.reload();
		entries.clear();
		fixedIds.clear();
		for (Pack p : repo.getAvailablePacks()) {
			if (p.isFixedPosition()) fixedIds.add(p.getId());
			// Pflicht-Packs (Standard, Mod-Ressourcen) sind immer aktiv und nicht schaltbar → nicht auflisten.
			// Mod-Packs von NeoForge sind immer aktiv – nicht umschaltbar anzeigen.
			if (p.isRequired() || dev.theredstonee.trsclient.compat.Mc.modPack(p)) continue;
			entries.add(new PackList.Entry(p.getId(), p.getTitle().getString(), p.getDescription().getString(),
					false, p.getCompatibility().isCompatible()));
		}
		enabledIds = new ArrayList<>(repo.getSelectedIds());
		originalIds = List.copyOf(enabledIds);
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
		g.fill(0, 0, width, height, minecraft.level == null ? Brand.BG : 0xC00C0C11);
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
		String count = enabledCount() + " aktiv · " + entries.size() + " verfügbar";
		g.text(font, count, px + pw - 10 - font.width(count), py + 9, Brand.TEXT_DIM, false);

		// Filter-Knopf rechts neben der Suche
		int fx = px + pw - 10 - 90;
		String fl = "Filter: " + filter.label;
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
		List<PackList.Entry> visible = PackList.visible(entries, enabledIds, filter, search.getValue());
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
			g.centered(font, "Keine Packs gefunden", listX + listW / 2, listY + listH / 2 - 4, Brand.TEXT_DIM);
		}
		if (maxScroll > 0) {
			int barH = Math.max(12, listH * listH / (listH + maxScroll));
			int barY = listY + (listH - barH) * scroll / maxScroll;
			g.fill(listX + listW - 2, barY, listX + listW, barY + barH, Brand.TEXT_DIM);
		}

		// Fußzeile
		int by = py + ph - 24;
		int ow = 92;
		Brand.button(g, font, px + 10, by, ow, 16, "Ordner öffnen", false, inside(mouseX, mouseY, px + 10, by, ow, 16));
		hot.add(px + 10, by, ow, 16, this::openFolder);
		if (!status.isEmpty()) g.text(font, status, px + 10 + ow + 8, by + 4, Brand.TEXT_DIM, false);
		int dw = 76;
		int cw = 70;
		int dx = px + pw - 10 - dw;
		int cx = dx - 6 - cw;
		boolean changed = !enabledIds.equals(originalIds);
		Brand.button(g, font, dx, by, dw, 16, changed ? "Übernehmen" : "Fertig", true, inside(mouseX, mouseY, dx, by, dw, 16));
		hot.add(dx, by, dw, 16, this::apply);
		Brand.button(g, font, cx, by, cw, 16, "Abbrechen", false, inside(mouseX, mouseY, cx, by, cw, 16));
		hot.add(cx, by, cw, 16, this::onClose);
	}

	private void drawRow(Gfx g, PackList.Entry e, int x, int y, int w, int mx, int my, boolean mouseInList) {
		boolean on = enabledIds.contains(e.id());
		boolean hover = mouseInList && inside(mx, my, x, y, w, ROW_H - 2);
		g.fill(x + 1, y + 1, x + w - 1, y + ROW_H - 1, hover ? Brand.SURFACE_HOVER : Brand.SURFACE);
		g.fill(x + 1, y + 1, x + 3, y + ROW_H - 1, on ? Brand.AMBER : Brand.OFF);
		int textRight = x + w - 8 - 26 - (on ? 30 : 0);
		String title = e.title() + (e.compatible() ? "" : "  (nicht kompatibel)");
		g.text(font, font.plainSubstrByWidth(title, textRight - x - 10), x + 8, y + 4, e.compatible() ? Brand.TEXT : 0xFFFF8080, false);
		g.text(font, font.plainSubstrByWidth(e.description().replace('\n', ' '), textRight - x - 10), x + 8, y + 14, Brand.TEXT_DIM, false);

		int pillX = x + w - 8 - 26;
		int pillY = y + 7;
		if (e.required()) {
			g.text(font, "Pflicht", pillX - 2, pillY + 2, Brand.TEXT_DIM, false);
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
			PlatformOpen.open(minecraft.getResourcePackDirectory());
			status = "Ordner geöffnet";
		} catch (IOException | RuntimeException e) {
			TrsClient.LOGGER.warn("Resourcepack-Ordner konnte nicht geöffnet werden", e);
			status = "Ordner konnte nicht geöffnet werden";
		}
	}

	/** Übernimmt die Auswahl (Minecraft speichert sie in options.txt und lädt die Ressourcen neu). */
	private void apply() {
		if (!enabledIds.equals(originalIds)) {
			PackRepository repo = minecraft.getResourcePackRepository();
			repo.setSelected(enabledIds);
			minecraft.options.updateResourcePacks(repo);
			originalIds = List.copyOf(enabledIds);
		}
		onClose();
	}

	@Override
	protected boolean onClick(double mouseX, double mouseY, int button) {
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
	public void onClose() {
		open(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
