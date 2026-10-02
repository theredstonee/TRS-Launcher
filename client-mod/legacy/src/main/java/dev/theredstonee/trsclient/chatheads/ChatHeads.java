package dev.theredstonee.trsclient.chatheads;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * Köpfe über dem schon gezeichneten Chat (1.8.9–1.12.2). Der Chat selbst hat kein Mixin: die Nachricht wird vorher
 * mit zwei Leerzeichen je Zeile neu gedruckt, der Kopf liegt über diesen Leerzeichen.
 *
 * <p>Lage wie Vanilla: Ursprung {@code (0, Höhe−48)}, darin {@code (2, 8)} und die Chat-Skalierung. Zeile 0 ist die
 * unterste, der Text beginnt bei {@code −index·9−8}.
 */
public final class ChatHeads {
	private static final ChatHeadCache cache = new ChatHeadCache();
	private static Field drawnField;
	private static Field scrollField;
	private static boolean looked;
	private static boolean failed;

	private ChatHeads() {
	}

	/** Nach dem HUD, wenn der Vanilla-Chat schon gezeichnet ist. */
	public static void render(int scaledHeight) {
		TrsClient client = TrsClient.get();
		if (client == null || !client.modules().qol.chatHeads.isEnabled() || failed) return;
		Minecraft mc = Minecraft.getMinecraft();
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
			int now = mc.ingameGUI.getUpdateCounter();
			float opacity = mc.gameSettings.chatOpacity * 0.9f + 0.1f;
			float scale = chat.getChatScale();
			if (scale <= 0f) scale = 1f;
			boolean hat = client.modules().qol.chatHeadsHat.get();
			List<String> names = names();
			GlStateManager.pushMatrix();
			GlStateManager.translate(0f, scaledHeight - 48f, 0f);
			GlStateManager.translate(2f, 8f, 0f);
			GlStateManager.scale(scale, scale, 1f);
			for (int i = 0; i < visible; i++) {
				int index = i + scroll;
				if (index < 0 || index >= drawn.size()) continue;
				ChatLine line = drawn.get(index);
				if (line == null || line.getChatComponent() == null) continue;
				int age = now - line.getUpdatedCounter();
				if (age >= 200 && !open) continue;
				int alpha = alpha(age, open, opacity);
				if (alpha <= 3) continue;
				ChatHeadSender sender = senderOf(line, line.getChatComponent().getFormattedText(), names);
				if (!sender.hasHead()) continue;
				int tint = ChatHeadLayout.tint(0xFFFFFF + (alpha << 24));
				TextureRef texture = texture(sender);
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
		GlStateManager.translate(x, y, 0f);
		GfxImage.blit(texture, 8f, 8f, 8, 8, argb);
		if (hat) GfxImage.blit(texture, 40f, 8f, 8, 8, argb);
		GlStateManager.translate(-x, -y, 0f);
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

	private static List<String> names() {
		List<String> names = new ArrayList<String>();
		if (Mc.player() != null && Mc.player().getGameProfile() != null) addName(names, Mc.player().getGameProfile().getName());
		if (Mc.connection() != null) {
			for (NetworkPlayerInfo info : Mc.connection().getPlayerInfoMap()) {
				if (info.getGameProfile() != null) addName(names, info.getGameProfile().getName());
			}
		}
		return names;
	}

	private static void addName(List<String> names, String name) {
		if (name == null || name.isEmpty() || name.startsWith("|slot_") || names.contains(name)) return;
		names.add(name);
	}

	private static TextureRef texture(ChatHeadSender sender) {
		UUID uuid = sender.self && Mc.player() != null ? Mc.player().getUniqueID() : parse(sender.uuid);
		if (Mc.connection() != null) {
			for (NetworkPlayerInfo info : Mc.connection().getPlayerInfoMap()) {
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
				if (generic instanceof java.lang.reflect.ParameterizedType
						&& ((java.lang.reflect.ParameterizedType) generic).getActualTypeArguments()[0] == String.class) continue;
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
