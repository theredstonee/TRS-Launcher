package dev.theredstonee.trsclient.core.tooltip;

import dev.theredstonee.trsclient.core.module.ComfortModules;
import dev.theredstonee.trsclient.core.ui.Canvas;

/**
 * Fassade der besseren Tooltips für die Bäume. Je Bild, in dem ein Gegenstand im Inventar überfahren wird:
 * <pre>
 *   TooltipCard card = Tooltips.get().begin();          // null = Modul aus
 *   if (card != null) {
 *       ... card.grid(...) / Tooltips.get().map(card, id, colors) / card.food(...)
 *       Tooltips.get().draw(canvas, card, mouseX, mouseY, tooltipWidth, lines, screenW, screenH, modern);
 *   }
 * </pre>
 * Text-Zusätze (Haltbarkeit, Verzauberungen) stehen in {@link TooltipText}.
 */
public final class Tooltips {
	private static final Tooltips INSTANCE = new Tooltips();

	public static Tooltips get() {
		return INSTANCE;
	}

	private ComfortModules modules;
	private final TooltipCard card = new TooltipCard();
	private final MapPreviews maps = new MapPreviews();

	private Tooltips() {
	}

	/** Einmal beim Start. */
	public void init(ComfortModules modules) {
		this.modules = modules;
	}

	public ComfortModules modules() {
		return modules;
	}

	public boolean enabled() {
		return modules != null && modules.tooltips.isEnabled();
	}

	public boolean shulker() {
		return enabled() && modules.tipShulker.get();
	}

	public boolean map() {
		return enabled() && modules.tipMap.get();
	}

	public boolean food() {
		return enabled() && modules.tipFood.get();
	}

	public ComfortModules.Durability durability() {
		return enabled() ? modules.tipDurability.get() : ComfortModules.Durability.OFF;
	}

	/** Verzauberungen zusammenfassen? ({@code count} Verzauberungs-Zeilen) */
	public boolean compactEnchants(int count, boolean shiftDown) {
		return enabled() && TooltipText.compact(modules.tipEnchants.get(), modules.tipEnchantsShift.get(), count, shiftDown);
	}

	/** Hinweis „Umschalt: Details“ anhängen? */
	public boolean shiftHint() {
		return enabled() && modules.tipEnchantsShift.get();
	}

	/** Leere Karte für dieses Bild oder null (Modul aus). */
	public TooltipCard begin() {
		if (!enabled()) return null;
		card.clear();
		return card;
	}

	/** Karten-Vorschau in die Karte legen ({@code colors} null = Daten fehlen noch). Nur im Render-Thread. */
	public void map(TooltipCard target, int id, byte[] colors) {
		int size = modules == null ? 64 : modules.tipMapSize.get().pixels;
		target.map(colors == null ? null : maps.texture(id, colors), size);
	}

	/**
	 * Zeichnet die Karte neben den Vanilla-Tooltip.
	 *
	 * @param tooltipWidth Breite des Tooltip-Texts (breiteste Zeile)
	 * @param lines        Zeilen des Tooltips
	 * @param modern       Positionierung ab Minecraft 1.20
	 */
	public void draw(Canvas c, TooltipCard target, int mouseX, int mouseY, int tooltipWidth, int lines, int screenW,
			int screenH, boolean modern) {
		if (target == null || target.empty()) return;
		int[] tip = TooltipPlacement.tooltip(mouseX, mouseY, tooltipWidth, TooltipPlacement.contentHeight(lines), screenW,
				screenH, modern);
		int w = target.width(c), h = target.height(c);
		int[] at = TooltipPlacement.card(tip, w, h, screenW, screenH);
		c.push();
		c.raise(400f);
		target.draw(c, at[0], at[1]);
		c.pop();
	}

	/** Welt verlassen: Karten-Texturen freigeben. */
	public void clearWorld() {
		maps.clear();
	}
}
