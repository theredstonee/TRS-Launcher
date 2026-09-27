package dev.theredstonee.trsclient.comfort;

import dev.theredstonee.trsclient.TrsKeys;
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
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemMap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.storage.MapData;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
//? if >=1.11 {
/*import net.minecraft.block.BlockShulkerBox;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.NonNullList;
*///?}

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Komfort-Paket 2 unter Legacy-Forge 1.8.9–1.12.2 (ohne Mixins, nur Forge-Ereignisse): bessere Tooltips (Text über
 * {@code ItemTooltipEvent}, Zusatzkarte nach dem Zeichnen des Inventars), Server-Profile (je Tick) und Panorama –
 * Vanilla hat hier keine Panorama-Aufnahme, deshalb eigene Kamera-Drehung: sechs Bilder mit 90° Sichtfeld, HUD und
 * Hand aus, je Bild das mittlere Quadrat des Fensters. Shulker-Kisten gibt es erst ab 1.11.
 */
public final class LegacyComfort {
	private static final LegacyComfort INSTANCE = new LegacyComfort();

	private TrsModules modules;
	private ItemStack lastStack;
	private List<String> lastLines;
	private boolean hadWorld;
	private long errorLogged;
	// Eigene Panorama-Aufnahme
	private Panorama.OwnCapture capture;
	private boolean savedHideGui;
	private boolean savedBobbing;
	private int savedPerspective;
	private float savedYaw, savedPitch, savedPrevYaw, savedPrevPitch;
	private boolean rotated;

	private LegacyComfort() {
	}

	public static LegacyComfort init(TrsModules modules) {
		INSTANCE.modules = modules;
		Comfort.install(modules, Mc.gameDir().toPath(), true);
		dev.theredstonee.trsclient.core.panorama.Panorama.setClipboard(Mc::setClipboard);
		return INSTANCE;
	}

	public static LegacyComfort get() {
		return INSTANCE;
	}

	// --- Tick: Server-Profile, Panorama ---

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END || modules == null) return;
		try {
			Minecraft mc = Minecraft.getMinecraft();
			boolean inWorld = Mc.world() != null && Mc.player() != null;
			if (hadWorld && !inWorld) Tooltips.get().clearWorld();
			hadWorld = inWorld;
			boolean singleplayer = mc.isSingleplayer();
			String address = inWorld && !singleplayer && mc.getCurrentServerData() != null ? mc.getCurrentServerData().serverIP : null;
			String message = Comfort.tickProfiles(modules, Comfort.context(inWorld, singleplayer, address));
			if (message != null) {
				if (!message.isEmpty() && inWorld) Mc.actionBar(message);
				dev.theredstonee.trsclient.TrsClient.get().saveConfig();
			}
			long now = System.currentTimeMillis();
			ComfortModules c = modules.comfort;
			while (TrsKeys.panorama.isPressed()) {
				if (c.panorama.isEnabled() && Mc.screen() == null && capture == null) {
					Panorama.get().onKey(now, c.panoramaFormat.get().cube(), c.panoramaFormat.get().equirect());
				}
			}
			if (capture != null && !inWorld) {
				capture.cancel("world left");
				endCapture(mc);
			}
			int poll = Panorama.get().poll(now, inWorld, Mc.screen() != null);
			if (poll == Panorama.POLL_CLOSE_SCREEN) {
				Mc.setScreen(null);
			} else if (poll == Panorama.POLL_CAPTURE) {
				EntityPlayer p = Mc.player();
				capture = Panorama.get().beginOwn(Mc.gameDir().toPath(), p.rotationYaw, now);
				if (capture != null) {
					savedHideGui = mc.gameSettings.hideGUI;
					savedBobbing = mc.gameSettings.viewBobbing;
					savedPerspective = mc.gameSettings.thirdPersonView;
				}
			}
		} catch (RuntimeException e) {
			log("Tick", e);
		}
	}

	/** Vor dem Bild: Blickrichtung der aktuellen Seite, HUD/Wackeln/3. Person aus. */
	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		if (capture == null) return;
		Minecraft mc = Minecraft.getMinecraft();
		EntityPlayer p = Mc.player();
		if (p == null) return;
		try {
			if (event.phase == TickEvent.Phase.START) {
				mc.gameSettings.hideGUI = true;
				mc.gameSettings.viewBobbing = false;
				mc.gameSettings.thirdPersonView = 0;
				savedYaw = p.rotationYaw;
				savedPitch = p.rotationPitch;
				savedPrevYaw = p.prevRotationYaw;
				savedPrevPitch = p.prevRotationPitch;
				p.rotationYaw = p.prevRotationYaw = capture.yaw();
				p.rotationPitch = p.prevRotationPitch = capture.pitch();
				rotated = true;
				return;
			}
			// Nach dem Bild: Blick zurück (Ticks und Server sehen nie die Aufnahme-Drehung), Bild übernehmen.
			if (rotated) {
				p.rotationYaw = savedYaw;
				p.rotationPitch = savedPitch;
				p.prevRotationYaw = savedPrevYaw;
				p.prevRotationPitch = savedPrevPitch;
				rotated = false;
			}
			if (capture.wantsFrame()) {
				int[] size = new int[2];
				int[] argb = readFrame(mc, size);
				capture.frame(argb, size[0], size[1]);
			}
			if (capture.finished()) endCapture(mc);
		} catch (RuntimeException e) {
			log("Panorama", e);
			capture.cancel(e.toString());
			endCapture(mc);
		}
	}

	/** Bild des Hauptpuffers als ARGB (Zeilen von oben) – wie Vanillas Screenshot, in allen Versionen 1.8.9–1.12.2. */
	private static int[] readFrame(Minecraft mc, int[] size) {
		net.minecraft.client.shader.Framebuffer fb = mc.getFramebuffer();
		boolean useFb = net.minecraft.client.renderer.OpenGlHelper.isFramebufferEnabled() && fb != null;
		int texW = useFb ? fb.framebufferTextureWidth : mc.displayWidth;
		int texH = useFb ? fb.framebufferTextureHeight : mc.displayHeight;
		int w = useFb ? fb.framebufferWidth : mc.displayWidth;
		int h = useFb ? fb.framebufferHeight : mc.displayHeight;
		java.nio.IntBuffer buf = org.lwjgl.BufferUtils.createIntBuffer(texW * texH);
		org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1);
		org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, 1);
		if (useFb) {
			GlStateManager.bindTexture(fb.framebufferTexture);
			org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, 32993, 33639, buf);
		} else {
			org.lwjgl.opengl.GL11.glReadPixels(0, 0, texW, texH, 32993, 33639, buf);
		}
		int[] raw = new int[texW * texH];
		buf.get(raw);
		// Zeilen von unten (OpenGL) → von oben; nur der benutzte Bereich des Puffers.
		int[] out = new int[w * h];
		for (int y = 0; y < h; y++) {
			System.arraycopy(raw, (h - 1 - y) * texW, out, y * w, w);
		}
		for (int i = 0; i < out.length; i++) out[i] |= 0xFF000000;
		size[0] = w;
		size[1] = h;
		return out;
	}

	private void endCapture(Minecraft mc) {
		capture = null;
		mc.gameSettings.hideGUI = savedHideGui;
		mc.gameSettings.viewBobbing = savedBobbing;
		mc.gameSettings.thirdPersonView = savedPerspective;
	}

	/** Blickwinkel exakt setzen (Mausbewegungen während der Aufnahme zählen nicht). */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onCamera(EntityViewRenderEvent.CameraSetup event) {
		if (capture == null) return;
		Mc.setCamera(event, capture.yaw() + 180.0F, capture.pitch());
	}

	/** 90° Sichtfeld (senkrecht) – das mittlere Quadrat des Bildes ist dann genau eine Würfelseite. */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onFov(EntityViewRenderEvent.FOVModifier event) {
		if (capture != null) event.setFOV(capture.fov());
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onHand(RenderHandEvent event) {
		if (capture != null) event.setCanceled(true);
	}

	// --- HUD: Toast ---

	@SubscribeEvent(priority = EventPriority.LOW)
	public void onOverlay(RenderGameOverlayEvent.Post event) {
		if (Mc.overlayType(event) != RenderGameOverlayEvent.ElementType.ALL || capture != null) return;
		try {
			PanoramaToast.Data d = Panorama.get().toast(System.currentTimeMillis());
			if (d == null || Mc.hudHidden()) return;
			int w = Mc.resolution(event).getScaledWidth(), h = Mc.resolution(event).getScaledHeight();
			String key = TrsKeys.panorama.getKeyCode() == 0 ? null : net.minecraft.client.settings.GameSettings.getKeyDisplayString(TrsKeys.panorama.getKeyCode());
			GfxCanvas c = GfxCanvas.of(Gfx.of(w, h), Mc.font());
			PanoramaToast.draw(c, w, h, d, key);
		} catch (RuntimeException e) {
			log("Toast", e);
		}
	}

	// --- Tooltips ---

	@SubscribeEvent(priority = EventPriority.LOW)
	public void onTooltip(ItemTooltipEvent event) {
		if (!Tooltips.get().enabled()) return;
		try {
			//? if >=1.12 {
			/*ItemStack stack = event.getItemStack();
			List<String> lines = event.getToolTip();
			boolean advanced = event.getFlags().isAdvanced();
			*///?} elif >=1.9 {
			/*ItemStack stack = event.getItemStack();
			List<String> lines = event.getToolTip();
			boolean advanced = event.isShowAdvancedItemTooltips();
			*///?} else {
			ItemStack stack = event.itemStack;
			List<String> lines = event.toolTip;
			boolean advanced = event.showAdvancedItemTooltips;
			//?}
			if (stack == null || lines == null || lines.isEmpty()) return;
			compactEnchants(stack, lines, GuiScreen.isShiftKeyDown());
			durability(stack, lines, advanced);
			lastStack = stack;
			lastLines = lines;
		} catch (RuntimeException e) {
			log("Tooltip", e);
		}
	}

	private static void durability(ItemStack stack, List<String> lines, boolean advanced) {
		if (!stack.isItemStackDamageable()) return;
		if (advanced && stack.isItemDamaged()) return;
		String line = TooltipText.durabilityLine(Tooltips.get().durability(), stack.getMaxDamage(), stack.getItemDamage());
		if (line == null) return;
		lines.add("\u00a7" + TooltipText.colorCode(stack.getMaxDamage(), stack.getItemDamage()) + line);
	}

	private static void compactEnchants(ItemStack stack, List<String> lines, boolean shift) {
		Set<String> names = enchantNames(stack);
		if (names.size() < 2) return;
		int first = -1, count = 0;
		for (int i = 1; i < lines.size(); i++) {
			if (!names.contains(strip(lines.get(i)))) {
				if (first >= 0) break;
				continue;
			}
			if (first < 0) first = i;
			count++;
		}
		if (first < 0 || !Tooltips.get().compactEnchants(count, shift)) return;
		List<String> run = new ArrayList<String>(lines.subList(first, first + count));
		lines.subList(first, first + count).clear();
		final FontRenderer font = Mc.font();
		int sep = font.getStringWidth(TooltipText.ENCHANT_SEPARATOR);
		List<List<String>> packed = TooltipText.pack(run, s -> font.getStringWidth(s), sep, TooltipText.ENCHANT_LINE_WIDTH);
		int at = first;
		for (List<String> group : packed) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < group.size(); i++) {
				if (i > 0) sb.append("\u00a77").append(TooltipText.ENCHANT_SEPARATOR);
				String part = group.get(i);
				sb.append(part.startsWith("\u00a7") ? part : "\u00a77" + part);
			}
			lines.add(at++, sb.toString());
		}
		if (Tooltips.get().shiftHint()) lines.add(at, "\u00a78" + TooltipText.shiftHint());
	}

	/** Namen der Verzauberungen (ohne Formatierung), wie Vanilla sie in den Tooltip schreibt. */
	private static Set<String> enchantNames(ItemStack stack) {
		Set<String> out = new HashSet<String>();
		NBTTagCompound tag = stack.getTagCompound();
		if (tag == null) return out;
		for (String key : new String[]{"ench", "StoredEnchantments"}) {
			NBTTagList list = tag.getTagList(key, 10);
			for (int i = 0; i < list.tagCount(); i++) {
				NBTTagCompound e = list.getCompoundTagAt(i);
				//? if >=1.9 {
				/*Enchantment ench = Enchantment.getEnchantmentByID(e.getShort("id"));
				*///?} else
				Enchantment ench = Enchantment.getEnchantmentById(e.getShort("id"));
				if (ench != null) out.add(strip(ench.getTranslatedName(e.getShort("lvl"))));
			}
		}
		return out;
	}

	private static String strip(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch == '\u00a7' && i + 1 < s.length()) {
				i++;
				continue;
			}
			sb.append(ch);
		}
		return sb.toString().trim();
	}

	/** Nach dem ganzen Inventar (samt Tooltip): Zusatzkarte daneben. */
	@SubscribeEvent(priority = EventPriority.LOW)
	public void onScreenDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
		GuiScreen screen = Mc.eventGui(event);
		if (!(screen instanceof GuiContainer) || !Tooltips.get().enabled()) return;
		try {
			Slot slot = ((GuiContainer) screen).getSlotUnderMouse();
			if (slot == null || !slot.getHasStack()) return;
			ItemStack stack = slot.getStack();
			EntityPlayer player = Mc.player();
			if (player == null || !Mc.isEmpty(player.inventory.getItemStack())) return;
			TooltipCard card = Tooltips.get().begin();
			if (card == null) return;
			fill(card, stack);
			if (card.empty()) return;
			List<String> lines = stack == lastStack ? lastLines : null;
			if (lines == null || lines.isEmpty()) return;
			FontRenderer font = Mc.font();
			int width = 0;
			for (String line : lines) width = Math.max(width, font.getStringWidth(line));
			//? if >=1.9 {
			/*int mx = event.getMouseX(), my = event.getMouseY();
			*///?} else
			int mx = event.mouseX, my = event.mouseY;
			GlStateManager.disableDepth();
			Tooltips.get().draw(GfxCanvas.of(Gfx.of(screen.width, screen.height), font), card, mx, my, width, lines.size(),
					screen.width, screen.height, false);
			GlStateManager.enableDepth();
		} catch (RuntimeException e) {
			log("Tooltip-Karte", e);
		}
	}

	private static void fill(TooltipCard card, ItemStack stack) {
		Tooltips t = Tooltips.get();
		//? if >=1.11 {
		/*Block block = Block.getBlockFromItem(stack.getItem());
		if (t.shulker() && block instanceof BlockShulkerBox) {
			EnumDyeColor color = ((BlockShulkerBox) block).getColor();
			card.grid(shulkerItems(stack), 27, 9, DyeColors.argb(color == null ? null : color.getName()));
		}
		*///?}
		if (t.map() && stack.getItem() instanceof ItemMap && Mc.world() != null) {
			MapData data = ((ItemMap) stack.getItem()).getMapData(stack, Mc.world());
			t.map(card, data == null ? 0 : System.identityHashCode(data), data == null ? null : data.colors);
		}
		if (t.food() && stack.getItem() instanceof ItemFood) {
			ItemFood food = (ItemFood) stack.getItem();
			int heal = food.getHealAmount(stack);
			card.food(heal, heal * food.getSaturationModifier(stack) * 2f);
		}
	}

	//? if >=1.11 {
	/*private static Object[] shulkerItems(ItemStack stack) {
		NonNullList<ItemStack> list = NonNullList.withSize(27, ItemStack.EMPTY);
		NBTTagCompound tag = stack.getTagCompound();
		if (tag != null && tag.hasKey("BlockEntityTag", 10)) {
			NBTTagCompound bet = tag.getCompoundTag("BlockEntityTag");
			if (bet.hasKey("Items", 9)) ItemStackHelper.loadAllItems(bet, list);
		}
		Object[] out = new Object[27];
		for (int i = 0; i < 27; i++) out[i] = list.get(i).isEmpty() ? null : list.get(i);
		return out;
	}
	*///?}

	/** Nur Legacy: gibt es das Modul hier? Tooltips und Panorama ja (Shulker erst ab 1.11), Profile immer. */
	public static boolean supported(dev.theredstonee.trsclient.core.module.Module module) {
		return true;
	}

	private void log(String where, RuntimeException e) {
		long now = System.currentTimeMillis();
		if (now - errorLogged < 60_000) return;
		errorLogged = now;
		System.err.println("[TRS Client] Komfort (" + where + "): " + e);
	}
}
