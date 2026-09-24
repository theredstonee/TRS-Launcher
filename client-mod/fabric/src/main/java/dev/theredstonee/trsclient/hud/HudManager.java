package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.hud.HudLayout;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.menu.HudItem;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Hält alle HUD-Elemente und zeichnet sie an ihrer gespeicherten Position. */
public final class HudManager {
	private final Minecraft mc = Minecraft.getInstance();
	private final List<HudElement> elements;
	/** Wiederverwendeter Puffer für {@link #bounds}: x, y, Breite, Höhe (skaliert). */
	private final int[] box = new int[4];
	private final CrosshairRenderer crosshair;
	private final WaypointOverlay waypointOverlay;
	private final MinimapHud minimap;
	private final RedstoneHuds.Overlay redstoneOverlay;
	private List<HudItem> editorItems;

	public HudManager(TrsModules modules) {
		this.crosshair = new CrosshairRenderer(modules);
		this.waypointOverlay = new WaypointOverlay(modules);
		this.minimap = new MinimapHud(modules.minimap, modules);
		dev.theredstonee.trsclient.core.redstone.RedstoneTools redstone = dev.theredstonee.trsclient.TrsClient.get().redstone();
		this.redstoneOverlay = new RedstoneHuds.Overlay(modules, redstone);
		this.elements = Arrays.asList(
				new FpsHud(modules.fps),
				new CpsHud(modules.cps),
				new KeystrokesHud(modules.keystrokes, modules),
				new PingHud(modules.ping),
				new ArmorHud(modules.armor, modules),
				new InfoHuds.Effects(modules.effects),
				new InfoHuds.Coords(modules.coords, modules),
				new InfoHuds.Clock(modules.clock, modules),
				new InfoHuds.Memory(modules.memory),
				new InfoHuds.Server(modules.server),
				new InfoHuds.Packs(modules.packs),
				new InfoHuds.ToggleIndicator(modules.toggleSprint, true),
				new InfoHuds.ToggleIndicator(modules.toggleSneak, false),
				new PvpHuds.Reach(modules.reach, modules),
				new PvpHuds.Combo(modules.combo, modules),
				new PvpHuds.Speed(modules.speed),
				minimap,
				new RedstoneHuds.Signal(modules, redstone),
				new RedstoneHuds.Clock(modules, redstone));
	}

	public CrosshairRenderer crosshair() {
		return crosshair;
	}

	/** Einmal je Client-Tick (Minimap liest dann ein paar Chunks nach). */
	public void tick() {
		minimap.tick();
	}

	/** Weltwechsel: Kartenspeicher leeren. */
	public void onWorldChange() {
		minimap.onWorldChange();
	}

	public List<HudElement> elements() {
		return elements;
	}

	/**
	 * Die HUD-Elemente für den versionsunabhängigen HUD-Editor. Die Liste wird einmal gebaut
	 * und dann wiederverwendet – neue Module des Registers kommen über die Elementliste dazu.
	 */
	public List<HudItem> editorItems() {
		if (editorItems == null) {
			List<HudItem> items = new ArrayList<>(elements.size());
			for (int i = 0; i < elements.size(); i++) items.add(new EditorItem(elements.get(i)));
			editorItems = items;
		}
		return editorItems;
	}

	/** Ein HUD-Element aus Sicht des Editors (Vorschau mit Beispielwerten). */
	private final class EditorItem implements HudItem {
		private final HudElement element;

		EditorItem(HudElement element) {
			this.element = element;
		}

		@Override
		public dev.theredstonee.trsclient.core.module.HudModule module() {
			return element.module();
		}

		@Override
		public int width() {
			return element.width(mc.font, true);
		}

		@Override
		public int height() {
			return element.height(mc.font, true);
		}

		@Override
		public void draw(Canvas canvas) {
			element.draw(((GfxCanvas) dev.theredstonee.trsclient.core.ui.FadeCanvas.unwrap(canvas)).gfx(), mc.font, true);
		}
	}

	/** HUD-Callback (jeden Frame). */
	public void render(Gfx g) {
		if (Mc.hudHidden() || Mc.screen() instanceof TrsUiScreen) return;
		// Bis 1.21.5 wird das Vanilla-Fadenkreuz per Mixin ausgeblendet und das eigene hier gezeichnet;
		// ab 1.21.6 ersetzt TRS die Fabric-HUD-Ebene des Fadenkreuzes direkt (siehe TrsClient).
		//? if <1.21.6
		if (crosshair.replacesVanilla()) crosshair.drawInGame(g);
		Font font = mc.font;
		int sw = g.width();
		int sh = g.height();
		// Wegpunkte liegen in der Welt – vor den Anzeigen zeichnen, damit sie nichts überdecken.
		waypointOverlay.render(g, font);
		redstoneOverlay.render(g, font);
		for (int i = 0, n = elements.size(); i < n; i++) {
			HudElement e = elements.get(i);
			if (e.module().isEnabled() && e.visible()) draw(g, font, e, sw, sh, false);
		}
	}

	/** Zeichnet ein Element an seiner Position (auch vom Editor genutzt). */
	public void draw(Gfx g, Font font, HudElement e, int sw, int sh, boolean preview) {
		int[] b = bounds(font, e, sw, sh, preview);
		float scale = e.module().scale.getFloat();
		g.push();
		g.translate(b[0], b[1]);
		g.scale(scale);
		e.draw(g, font, preview);
		g.pop();
	}

	/**
	 * Berechnet die skalierte Box eines Elements. Achtung: gibt einen geteilten Puffer zurück,
	 * der beim nächsten Aufruf überschrieben wird.
	 */
	public int[] bounds(Font font, HudElement e, int sw, int sh, boolean preview) {
		float scale = e.module().scale.getFloat();
		int w = (int) Math.ceil(e.width(font, preview) * scale);
		int h = (int) Math.ceil(e.height(font, preview) * scale);
		box[0] = HudLayout.resolveX(e.module().position(), w, sw);
		box[1] = HudLayout.resolveY(e.module().position(), h, sh);
		box[2] = w;
		box[3] = h;
		return box;
	}
}
