package dev.theredstonee.trsclient.chatheads;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.chatheads.ChatHeadCache;
import dev.theredstonee.trsclient.core.chatheads.ChatHeadDetect;
import dev.theredstonee.trsclient.core.chatheads.ChatHeadLayout;
import dev.theredstonee.trsclient.core.chatheads.ChatHeadSender;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import dev.theredstonee.trsclient.ui.GfxImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 *
 * Based on Chat Heads by dzwdz (https://github.com/dzwdz/chat_heads), modified for the TRS Client.
 */

/**
 * Köpfe über dem schon gezeichneten Chat (1.13.2). Folgezeilen werden mit zwei Leerzeichen neu gedruckt.
 * Lage wie in 1.12: Ursprung {@code (0, Höhe−48)}, darin {@code (2, 8)} und die Chat-Skalierung.
 */
public final class ChatHeads {
	private static final ChatHeadCache cache = new ChatHeadCache();
	private static Field drawnField;
	private static Field scrollField;
	private static boolean looked;
	private static boolean failed;

	private ChatHeads() {
	}

	/** Eingehende Zeile umbrechen und einrücken, ohne die Fair-Play-Codes der Karten anzufassen. */
	public static void onChat(ClientChatReceivedEvent event) {
		TrsClient client = TrsClient.get();
		if (client == null || !client.modules().qol.chatHeads.isEnabled() || event.getMessage() == null) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.ingameGUI == null || mc.ingameGUI.getChatGUI() == null || mc.fontRenderer == null) return;
		GuiNewChat chat = mc.ingameGUI.getChatGUI();
		double scale = chat.getScale();
		if (scale <= 0d) scale = 1d;
		int width = ChatHeadLayout.wrapWidth((int) Math.floor(chat.getChatWidth() / scale), ChatHeadLayout.LEGACY_PAD);
		String padded = ChatHeadLayout.indentLines(mc.fontRenderer.listFormattedStringToWidth(
				event.getMessage().getFormattedText(), Math.max(1, width)));
		event.setCanceled(true);
		chat.printChatMessage(new TextComponentString(padded));
	}

	/** Nach dem HUD, wenn der Vanilla-Chat schon gezeichnet ist. */
	public static void render(int scaledHeight) {
		TrsClient client = TrsClient.get();
		if (client == null || !client.modules().qol.chatHeads.isEnabled() || failed) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.ingameGUI == null || mc.gameSettings == null) return;
		GuiNewChat chat = mc.ingameGUI.getChatGUI();
		if (chat == null) return;
		try {
			if (!looked) look(chat);
			if (drawnField == null) return;
			@SuppressWarnings("unchecked")
			List<ChatLine> drawn = (List<ChatLine>) drawnField.get(chat);
			if (drawn == null || drawn.isEmpty()) return;
			int scroll = scrollField == null ? 0 : scrollField.getInt(chat);
			int visible = Math.max(1, chat.getChatHeight() / 9);
			boolean open = chat.getChatOpen();
			int now = mc.ingameGUI.getTicks();
			double opacity = mc.gameSettings.chatOpacity * 0.9d + 0.1d;
			double scale = chat.getScale();
			if (scale <= 0d) scale = 1d;
			boolean hat = client.modules().qol.chatHeadsHat.get();
			List<String> names = names(mc);
			GlStateManager.pushMatrix();
			GlStateManager.translatef(0f, scaledHeight - 48f, 0f);
			GlStateManager.translatef(2f, 8f, 0f);
			GlStateManager.scalef((float) scale, (float) scale, 1f);
			for (int i = 0; i < visible; i++) {
				int index = i + scroll;
				if (index < 0 || index >= drawn.size()) continue;
				ChatLine line = drawn.get(index);
				if (line == null || line.getChatComponent() == null) continue;
				int age = now - line.getUpdatedCounter();
				if (age >= 200 && !open) continue;
				int alpha = alpha(age, open, (float) opacity);
				if (alpha <= 3) continue;
				ChatHeadSender sender = senderOf(line, line.getChatComponent().getFormattedText(), names);
				if (!sender.hasHead()) continue;
				int tint = ChatHeadLayout.tint(0xFFFFFF + (alpha << 24));
				TextureRef texture = texture(mc, sender);
				if (texture == null || tint == 0) continue;
				draw(0, -i * 9 - 8, texture, tint, hat);
			}
			GlStateManager.popMatrix();
		} catch (IllegalAccessException | RuntimeException e) {
			failed = true;
			TrsClient.LOGGER.error("Chat-Köpfe konnten nicht gezeichnet werden", e);
		}
	}

	/** Gesicht (UV 8,8) und Hut (UV 40,8), 8×8 an der aktuellen Matrix. */
	private static void draw(int x, int y, TextureRef texture, int argb, boolean hat) {
		GlStateManager.translatef(x, y, 0f);
		GfxImage.blit(texture, 8f, 8f, 8, 8, argb);
		if (hat) GfxImage.blit(texture, 40f, 8f, 8, 8, argb);
		GlStateManager.translatef(-x, -y, 0f);
	}

	private static int alpha(int age, boolean open, float opacity) {
		double fade = 1.0 - age / 200.0;
		fade *= 10.0;
		if (fade < 0) fade = 0;
		if (fade > 1) fade = 1;
		fade = fade * fade;
		int alpha = open ? 255 : (int) (255.0 * fade);
		return (int) (alpha * opacity);
	}

	private static ChatHeadSender senderOf(ChatLine line, String text, List<String> names) {
		ChatHeadSender known = cache.get(line);
		if (known != null) return known;
		ChatHeadDetect.Hit hit = ChatHeadDetect.find(text, names);
		ChatHeadSender sender;
		if (hit != null && hit.self) sender = ChatHeadSender.self();
		else if (hit != null && hit.name != null) sender = ChatHeadSender.name(hit.name);
		else sender = ChatHeadSender.NONE;
		cache.put(line, sender);
		return sender;
	}

	private static List<String> names(Minecraft mc) {
		List<String> names = new ArrayList<String>();
		if (mc.player != null && mc.player.getGameProfile() != null) addName(names, mc.player.getGameProfile().getName());
		if (mc.getConnection() != null) {
			Collection<NetworkPlayerInfo> infos = mc.getConnection().getPlayerInfoMap();
			if (infos != null) {
				for (NetworkPlayerInfo info : infos) {
					if (info.getGameProfile() != null) addName(names, info.getGameProfile().getName());
				}
			}
		}
		return names;
	}

	private static void addName(List<String> names, String name) {
		if (name == null || name.isEmpty() || name.startsWith("|slot_") || names.contains(name)) return;
		names.add(name);
	}

	private static TextureRef texture(Minecraft mc, ChatHeadSender sender) {
		UUID uuid = sender.self && mc.player != null ? mc.player.getUniqueID() : parse(sender.uuid);
		if (mc.getConnection() != null) {
			Collection<NetworkPlayerInfo> infos = mc.getConnection().getPlayerInfoMap();
			if (infos != null) {
				for (NetworkPlayerInfo info : infos) {
					if (info.getGameProfile() == null) continue;
					String name = info.getGameProfile().getName();
					if (name != null && name.startsWith("|slot_")) continue;
					boolean match = uuid != null && uuid.equals(info.getGameProfile().getId());
					if (!match && uuid == null && sender.name != null && sender.name.equals(name)) match = true;
					if (!match) continue;
					ResourceLocation loc = info.getLocationSkin();
					if (loc != null) return new TextureRef(loc, 64, 64);
				}
			}
		}
		Textures.Store store = Textures.store();
		if (store == null) return null;
		UUID fallback = uuid;
		if (fallback == null && sender.name != null) fallback = UUID.nameUUIDFromBytes(sender.name.getBytes(StandardCharsets.UTF_8));
		if (fallback == null) fallback = new UUID(0L, 0L);
		try {
			return store.defaultSkin(fallback).texture;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static UUID parse(String text) {
		if (text == null || text.isEmpty()) return null;
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Gezeichnete Zeilen und Scroll-Stand per Typ – die Feldnamen sind in der fertigen JAR andere. */
	private static void look(GuiNewChat chat) {
		looked = true;
		List<Field> lists = new ArrayList<Field>();
		for (Field field : GuiNewChat.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers())) continue;
			if (List.class.isAssignableFrom(field.getType())) {
				Type generic = field.getGenericType();
				if (generic instanceof ParameterizedType
						&& ((ParameterizedType) generic).getActualTypeArguments()[0] == String.class) continue;
				field.setAccessible(true);
				lists.add(field);
			} else if (field.getType() == int.class && scrollField == null) {
				field.setAccessible(true);
				scrollField = field;
			}
		}
		if (lists.size() >= 2) drawnField = lists.get(1);
	}
}
