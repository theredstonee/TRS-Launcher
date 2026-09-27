package dev.theredstonee.trsclient.comfort;

import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.comfort.Comfort;
import dev.theredstonee.trsclient.core.module.ComfortModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.panorama.Panorama;
import dev.theredstonee.trsclient.core.panorama.PanoramaToast;
import dev.theredstonee.trsclient.core.tooltip.DyeColors;
import dev.theredstonee.trsclient.core.tooltip.TooltipCard;
import dev.theredstonee.trsclient.core.tooltip.TooltipText;
import dev.theredstonee.trsclient.core.tooltip.Tooltips;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
//? if >=1.20.5 {
/*import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
*///?} else {
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
//?}
//? if >=1.19 {
/*import net.minecraft.network.chat.contents.TranslatableContents;
*///?} else
import net.minecraft.network.chat.TranslatableComponent;
//? if >=1.16
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hooks des Komfort-Pakets 2 (identisch in fabric, neoforge, forge und forge-mojmap-legacy): bessere Tooltips
 * (Text-Zusätze aus {@code TooltipLinesMixin}, Zusatzkarte aus {@code TooltipScreenMixin}), Server-Profile (je Tick)
 * und Panorama-Screenshots (ab 1.17 über Vanillas {@code grabPanoramixScreenshot}). Logik in {@code core.tooltip},
 * {@code core.profile}, {@code core.panorama}; hier nur Lesen/Zeichnen der Minecraft-Daten. Jeder Fehler wird
 * abgefangen – Tooltips und Ticks dürfen nie am TRS Client scheitern.
 */
public final class ComfortHooks {
	private static TrsModules modules;
	/** Umschalt gedrückt (je Tick im Haupt-Thread gelesen – Tooltips entstehen teils in anderen Threads). */
	private static volatile boolean shiftDown;
	/** Zuletzt im Render-Thread berechneter Tooltip (für die Größe der Zusatzkarte). */
	private static ItemStack lastStack;
	private static List<Component> lastLines;
	private static boolean hadWorld;
	private static long errorLogged;

	private ComfortHooks() {
	}

	public static void init(TrsModules m) {
		modules = m;
		Comfort.install(m, Mc.mc().gameDirectory.toPath(), panoramaSupported());
	}

	/**
	 * Gibt es das Modul in diesem Baum? Tooltips brauchen Mixins (Forge 1.14.4 hat keine), das Panorama Vanillas
	 * Aufnahme (ab 1.17).
	 */
	public static boolean supported(dev.theredstonee.trsclient.core.module.Module module) {
		if (modules == null) return true;
		if (module == modules.comfort.tooltips) return dev.theredstonee.trsclient.qol.QolHooks.mixins();
		if (module == modules.comfort.panorama) return panoramaSupported();
		return true;
	}

	/** Vanillas Panorama-Aufnahme gibt es ab Minecraft 1.17. */
	public static boolean panoramaSupported() {
		//? if >=1.17 {
		/*return true;
		*///?} else
		return false;
	}

	/**
	 * Je Client-Tick (Ende): Server-Profile, Panorama-Taste und -Aufnahme.
	 *
	 * @return true = Einstellungen speichern (Profil gewechselt)
	 */
	public static boolean tick(Minecraft mc) {
		if (modules == null) return false;
		boolean save = false;
		try {
			shiftDown = Keys.isDown("key.keyboard.left.shift") || Keys.isDown("key.keyboard.right.shift");
			boolean inWorld = mc.level != null && mc.player != null;
			if (hadWorld && !inWorld) Tooltips.get().clearWorld();
			hadWorld = inWorld;
			String address = null;
			if (inWorld && mc.getSingleplayerServer() == null && mc.getCurrentServer() != null) address = mc.getCurrentServer().ip;
			String message = Comfort.tickProfiles(modules, Comfort.context(inWorld, mc.getSingleplayerServer() != null, address));
			if (message != null) {
				if (!message.isEmpty() && inWorld) actionBar(mc, message);
				save = true;
			}
			long now = System.currentTimeMillis();
			ComfortModules c = modules.comfort;
			while (TrsKeys.panorama.consumeClick()) {
				if (c.panorama.isEnabled() && panoramaSupported() && Mc.screen() == null) {
					Panorama.get().onKey(now, c.panoramaFormat.get().cube(), c.panoramaFormat.get().equirect());
				}
			}
			int poll = Panorama.get().poll(now, inWorld, Mc.screen() != null);
			if (poll == Panorama.POLL_CLOSE_SCREEN) {
				Mc.setScreen(null);
				save = true;
			} else if (poll == Panorama.POLL_CAPTURE) {
				capture(mc, now);
			}
		} catch (RuntimeException e) {
			log("Tick", e);
		}
		return save;
	}

	/** Panorama über Vanilla (sechs Seiten in einem Bild, ohne HUD und Hand). */
	private static void capture(Minecraft mc, long now) {
		Path dir = Panorama.get().beginVanilla(mc.gameDirectory.toPath(), now);
		if (dir == null) return;
		String error = null;
		//? if >=1.17
		/*boolean smartCull = mc.smartCull;*/
		try {
			// Ohne Verdeckungs-Berechnung: die Sichtbarkeit der Abschnitte hinkt sonst beim 90°-Sprung je Seite hinterher
			// (fehlende Blätter/Abschnitte an den Rändern der Seiten).
			//? if >=1.17
			/*mc.smartCull = false;*/
			//? if >=1.21.6 {
			/*Component result = mc.grabPanoramixScreenshot(dir.toFile());
			*///?} elif >=1.17 {
			/*Component result = mc.grabPanoramixScreenshot(dir.toFile(), 1024, 1024);
			*///?} else
			Component result = null;
			if (result == null || failed(result)) error = result == null ? "?" : result.getString();
		} catch (RuntimeException e) {
			error = e.toString();
		} finally {
			//? if >=1.17
			/*mc.smartCull = smartCull;*/
		}
		Panorama.get().vanillaFinished(error, System.currentTimeMillis());
	}

	private static boolean failed(Component c) {
		//? if >=1.19 {
		/*return c.getContents() instanceof TranslatableContents && "screenshot.failure".equals(((TranslatableContents) c.getContents()).getKey());
		*///?} else
		return c instanceof TranslatableComponent && "screenshot.failure".equals(((TranslatableComponent) c).getKey());
	}

	// --- HUD: Toast ---

	/** Toast der Panorama-Aufnahme über dem HUD. */
	public static void hud(Gfx g) {
		try {
			PanoramaToast.Data d = Panorama.get().toast(System.currentTimeMillis());
			if (d == null || Mc.hudHidden()) return;
			String key = TrsKeys.panorama == null || TrsKeys.panorama.isUnbound() ? null : Mc.keyName(TrsKeys.panorama);
			PanoramaToast.draw(GfxCanvas.of(g, Mc.mc().font), g.width(), g.height(), d, key);
		} catch (RuntimeException e) {
			log("Toast", e);
		}
	}

	// --- Tooltips: Text ---

	/** Aus {@code TooltipLinesMixin}: Haltbarkeit und kompakte Verzauberungen (Liste wird verändert). */
	public static void lines(ItemStack stack, List<Component> lines, boolean advanced) {
		if (lines == null || lines.isEmpty() || !Tooltips.get().enabled()) return;
		try {
			boolean main = Mc.mc().isSameThread();
			compactEnchants(lines, main && shiftDown);
			durability(stack, lines, advanced);
			if (main) {
				lastStack = stack;
				lastLines = lines;
			}
		} catch (RuntimeException e) {
			// z. B. unveränderliche Liste (versteckter Tooltip) – dann bleibt er wie er ist.
		}
	}

	private static void durability(ItemStack stack, List<Component> lines, boolean advanced) {
		if (!stack.isDamageableItem()) return;
		// Erweiterte Tooltips (F3+H) zeigen die Haltbarkeit beschädigter Gegenstände schon selbst.
		if (advanced && stack.isDamaged()) return;
		String line = TooltipText.durabilityLine(Tooltips.get().durability(), stack.getMaxDamage(), stack.getDamageValue());
		if (line == null) return;
		ChatFormatting color = ChatFormatting.getByCode(TooltipText.colorCode(stack.getMaxDamage(), stack.getDamageValue()));
		lines.add(text(line).withStyle(color == null ? ChatFormatting.GRAY : color));
	}

	private static void compactEnchants(List<Component> lines, boolean shift) {
		int first = -1, count = 0;
		for (int i = 1; i < lines.size(); i++) {
			if (!enchantLine(lines.get(i))) {
				if (first >= 0) break;
				continue;
			}
			if (first < 0) first = i;
			count++;
		}
		if (first < 0 || !Tooltips.get().compactEnchants(count, shift)) return;
		List<Component> run = new ArrayList<Component>(lines.subList(first, first + count));
		lines.subList(first, first + count).clear();
		final Font font = Mc.mc().font;
		int sep = font == null ? 12 : font.width(TooltipText.ENCHANT_SEPARATOR);
		List<List<Component>> packed = TooltipText.pack(run, c -> font == null ? c.getString().length() * 6 : width(font, c), sep,
				TooltipText.ENCHANT_LINE_WIDTH);
		int at = first;
		for (List<Component> group : packed) lines.add(at++, join(group));
		if (Tooltips.get().shiftHint()) lines.add(at, text(TooltipText.shiftHint()).withStyle(ChatFormatting.DARK_GRAY));
	}

	private static boolean enchantLine(Component c) {
		//? if >=1.19 {
		/*return c.getContents() instanceof TranslatableContents && TooltipText.isEnchantmentKey(((TranslatableContents) c.getContents()).getKey());
		*///?} else
		return c instanceof TranslatableComponent && TooltipText.isEnchantmentKey(((TranslatableComponent) c).getKey());
	}

	private static Component join(List<Component> group) {
		//? if >=1.16 {
		MutableComponent line = text("");
		//?} else
		/*Component line = text("");*/
		for (int i = 0; i < group.size(); i++) {
			if (i > 0) line.append(text(TooltipText.ENCHANT_SEPARATOR).withStyle(ChatFormatting.GRAY));
			line.append(group.get(i));
		}
		return line;
	}

	// --- Tooltips: Zusatzkarte ---

	/** Aus {@code TooltipScreenMixin} am Ende des Inventar-Zeichnens: Shulker-Raster, Karte, Hunger neben dem Tooltip. */
	public static void afterContainerRender(AbstractContainerScreen<?> screen, Slot hovered, Gfx g, int mouseX, int mouseY) {
		if (hovered == null || !Tooltips.get().enabled()) return;
		try {
			Minecraft mc = Mc.mc();
			ItemStack stack = hovered.getItem();
			if (stack == null || stack.isEmpty() || mc.player == null) return;
			//? if >=1.17 {
			/*if (!mc.player.containerMenu.getCarried().isEmpty()) return;
			*///?} else
			if (!mc.player.inventory.getCarried().isEmpty()) return;
			TooltipCard card = Tooltips.get().begin();
			if (card == null) return;
			fill(card, stack, mc);
			if (card.empty()) return;
			List<Component> lines = stack == lastStack ? lastLines : null;
			if (lines == null || lines.isEmpty()) return;
			Font font = mc.font;
			int tw = 0;
			for (Component line : lines) tw = Math.max(tw, width(font, line));
			g.overlayLayer();
			//? if >=1.20 {
			/*boolean modern = true;
			*///?} else
			boolean modern = false;
			Tooltips.get().draw(GfxCanvas.of(g, font), card, mouseX, mouseY, tw, lines.size(), screen.width, screen.height, modern);
		} catch (RuntimeException e) {
			log("Tooltip", e);
		}
	}

	private static void fill(TooltipCard card, ItemStack stack, Minecraft mc) {
		Tooltips t = Tooltips.get();
		Block block = Block.byItem(stack.getItem());
		if (t.shulker() && block instanceof ShulkerBoxBlock) {
			DyeColor color = ((ShulkerBoxBlock) block).getColor();
			card.grid(shulkerItems(stack), 27, 9, DyeColors.argb(color == null ? null : color.getName()));
		}
		if (t.map() && stack.getItem() instanceof MapItem && mc.level != null) {
			MapItemSavedData data = MapItem.getSavedData(stack, mc.level);
			t.map(card, data == null ? 0 : System.identityHashCode(data), data == null ? null : data.colors);
		}
		if (t.food()) {
			//? if >=1.20.5 {
			/*FoodProperties food = stack.get(DataComponents.FOOD);
			if (food != null) card.food(food.nutrition(), food.saturation());
			*///?} else {
			FoodProperties food = stack.getItem().isEdible() ? stack.getItem().getFoodProperties() : null;
			if (food != null) card.food(food.getNutrition(), food.getNutrition() * food.getSaturationModifier() * 2f);
			//?}
		}
	}

	private static Object[] shulkerItems(ItemStack stack) {
		NonNullList<ItemStack> list = NonNullList.withSize(27, ItemStack.EMPTY);
		//? if >=1.20.5 {
		/*ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents != null) contents.copyInto(list);
		*///?} else {
		CompoundTag tag = stack.getTagElement("BlockEntityTag");
		if (tag != null && tag.contains("Items", 9)) ContainerHelper.loadAllItems(tag, list);
		//?}
		Object[] out = new Object[27];
		for (int i = 0; i < 27; i++) out[i] = list.get(i).isEmpty() ? null : list.get(i);
		return out;
	}

	/** Meldung in der Aktionsleiste (Mc.actionBar hat je Baum eine andere Signatur). */
	private static void actionBar(Minecraft mc, String message) {
		//? if >=26.2 {
		/*mc.gui.hud.setOverlayMessage(text(message), false);
		*///?} else
		mc.gui.setOverlayMessage(text(message), false);
	}

	/** Text-Komponente (Mc.text gibt es nicht in jedem Baum). */
	//? if >=1.19 {
	/*private static MutableComponent text(String s) {
		return Component.literal(s);
	}
	*///?} elif >=1.16 {
	private static MutableComponent text(String s) {
		return new net.minecraft.network.chat.TextComponent(s);
	}
	//?} else {
	/*private static Component text(String s) {
		return new net.minecraft.network.chat.TextComponent(s);
	}
	*///?}

	private static int width(Font font, Component text) {
		//? if >=1.16 {
		return font.width(text);
		//?} else
		/*return font.width(text.getColoredString());*/
	}

	private static void log(String where, RuntimeException e) {
		long now = System.currentTimeMillis();
		if (now - errorLogged < 60_000) return;
		errorLogged = now;
		System.err.println("[TRS Client] Komfort (" + where + "): " + e);
	}
}
