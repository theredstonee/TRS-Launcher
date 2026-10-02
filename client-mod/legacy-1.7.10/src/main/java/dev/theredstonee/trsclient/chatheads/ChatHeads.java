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
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiPlayerInfo;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
 * Köpfe über dem schon gezeichneten Chat (1.7.10). Folgezeilen werden mit zwei Leerzeichen neu gedruckt.
 * Lage wie Vanilla: Ursprung {@code (0, Höhe−48)}, darin {@code (2, 20)} und die Chat-Skalierung.
 * Skins dieser Version sind 64×32; das Gesicht liegt trotzdem bei (8, 8).
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
		if (client == null || !client.modules().qol.chatHeads.isEnabled() || event.message == null) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.ingameGUI == null || mc.ingameGUI.getChatGUI() == null || mc.fontRenderer == null) return;
		GuiNewChat chat = mc.ingameGUI.getChatGUI();
		// MCP stable_12 hat Breite, Höhe, Skalierung und den Zeilentext nicht umbenannt.
		float scale = chat.func_146244_h();
		if (scale <= 0f) scale = 1f;
		int width = ChatHeadLayout.wrapWidth((int) Math.floor(chat.func_146228_f() / scale), ChatHeadLayout.LEGACY_PAD);
		@SuppressWarnings("unchecked")
		List<String> lines = mc.fontRenderer.listFormattedStringToWidth(event.message.getFormattedText(), Math.max(1, width));
		event.setCanceled(true);
		chat.printChatMessage(new ChatComponentText(ChatHeadLayout.indentLines(lines)));
	}

	/** Nach dem HUD, wenn der Vanilla-Chat schon gezeichnet ist. */
	public static void render(int scaledHeight) {
		TrsClient client = TrsClient.get();
		if (client == null || !client.modules().qol.chatHeads.isEnabled() || failed) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.ingameGUI == null || mc.gameSettings == null || mc.fontRenderer == null) return;
		GuiNewChat chat = mc.ingameGUI.getChatGUI();
		if (chat == null) return;
		try {
			if (!looked) look(chat);
			if (drawnField == null) return;
			@SuppressWarnings("unchecked")
			List<ChatLine> drawn = (List<ChatLine>) drawnField.get(chat);
			if (drawn == null || drawn.isEmpty()) return;
			int scroll = scrollField == null ? 0 : scrollField.getInt(chat);
			int visible = Math.max(1, chat.func_146246_g() / 9);
			boolean open = chat.getChatOpen();
			int now = mc.ingameGUI.getUpdateCounter();
			float opacity = mc.gameSettings.chatOpacity * 0.9f + 0.1f;
			float scale = chat.func_146244_h();
			if (scale <= 0f) scale = 1f;
			boolean hat = client.modules().qol.chatHeadsHat.get();
			List<String> names = names(mc);
			GL11.glPushMatrix();
			GL11.glTranslatef(0f, scaledHeight - 48f, 0f);
			GL11.glTranslatef(2f, 20f, 0f);
			GL11.glScalef(scale, scale, 1f);
			for (int i = 0; i < visible; i++) {
				int index = i + scroll;
				if (index < 0 || index >= drawn.size()) continue;
				ChatLine line = drawn.get(index);
				if (line == null || line.func_151461_a() == null) continue;
				int age = now - line.getUpdatedCounter();
				if (age >= 200 && !open) continue;
				int alpha = alpha(age, open, opacity);
				if (alpha <= 3) continue;
				ChatHeadSender sender = senderOf(line, line.func_151461_a().getFormattedText(), names);
				if (!sender.hasHead()) continue;
				int tint = ChatHeadLayout.tint(0xFFFFFF + (alpha << 24));
				TextureRef texture = texture(mc, sender);
				if (texture == null || tint == 0) continue;
				draw(0, -i * 9 - 8, texture, tint, hat);
			}
			GL11.glPopMatrix();
		} catch (IllegalAccessException | RuntimeException e) {
			failed = true;
			TrsClient.LOGGER.error("Chat-Köpfe konnten nicht gezeichnet werden", e);
		}
	}

	/** Gesicht (UV 8,8) und Hut (UV 40,8), 8×8 an der aktuellen Matrix. */
	private static void draw(int x, int y, TextureRef texture, int argb, boolean hat) {
		GL11.glTranslatef(x, y, 0f);
		GfxImage.blit(texture, 8f, 8f, 8, 8, argb);
		if (hat) GfxImage.blit(texture, 40f, 8f, 8, 8, argb);
		GL11.glTranslatef(-x, -y, 0f);
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

	@SuppressWarnings("unchecked")
	private static List<String> names(Minecraft mc) {
		List<String> names = new ArrayList<String>();
		if (mc.thePlayer != null) addName(names, mc.thePlayer.getCommandSenderName());
		if (mc.getNetHandler() != null && mc.getNetHandler().playerInfoList != null) {
			List<GuiPlayerInfo> list = mc.getNetHandler().playerInfoList;
			for (int i = 0; i < list.size(); i++) {
				GuiPlayerInfo info = list.get(i);
				if (info != null) addName(names, info.name);
			}
		}
		return names;
	}

	private static void addName(List<String> names, String name) {
		if (name == null || name.isEmpty() || name.startsWith("|slot_") || names.contains(name)) return;
		names.add(name);
	}

	private static TextureRef texture(Minecraft mc, ChatHeadSender sender) {
		String wanted = sender.name;
		if (sender.self && mc.thePlayer != null) wanted = mc.thePlayer.getCommandSenderName();
		if (mc.theWorld != null && wanted != null) {
			@SuppressWarnings("unchecked")
			List<Object> players = mc.theWorld.playerEntities;
			for (int i = 0; i < players.size(); i++) {
				Object entity = players.get(i);
				if (!(entity instanceof AbstractClientPlayer)) continue;
				AbstractClientPlayer player = (AbstractClientPlayer) entity;
				if (!wanted.equals(player.getCommandSenderName())) continue;
				ResourceLocation loc = player.getLocationSkin();
				if (loc != null) return new TextureRef(loc, 64, 32);
			}
		}
		Textures.Store store = Textures.store();
		if (store == null) return null;
		UUID fallback = null;
		if (wanted != null) fallback = UUID.nameUUIDFromBytes(wanted.getBytes(StandardCharsets.UTF_8));
		if (fallback == null) fallback = new UUID(0L, 0L);
		try {
			return store.defaultSkin(fallback).texture;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Gezeichnete Zeilen und Scroll-Stand per Typ. 1.7.10 hat drei Listen; die mittlere ist der Verlauf, die letzte gezeichnet. */
	private static void look(GuiNewChat chat) {
		looked = true;
		List<Field> lists = new ArrayList<Field>();
		for (Field field : GuiNewChat.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers())) continue;
			if (!List.class.isAssignableFrom(field.getType())) {
				if (field.getType() == int.class && scrollField == null) {
					field.setAccessible(true);
					scrollField = field;
				}
				continue;
			}
			field.setAccessible(true);
			lists.add(field);
		}
		if (lists.size() >= 3) drawnField = lists.get(2);
		else if (lists.size() >= 2) drawnField = lists.get(1);
	}
}
