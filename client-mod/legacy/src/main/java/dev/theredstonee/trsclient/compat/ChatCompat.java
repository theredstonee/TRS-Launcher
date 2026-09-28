package dev.theredstonee.trsclient.compat;

import net.minecraft.client.gui.GuiNewChat;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
//? if >=1.9 {
/*import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
*///?} else {
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
//?}

/**
 * Alles rund um Chat-Komponenten, was sich zwischen 1.8.9 und 1.12.2 unterscheidet
 * (ab 1.9 heißen die Klassen {@code ITextComponent}/{@code TextComponentString} und liegen in
 * {@code util.text}, ab 1.12 ist der Nachrichtentyp ein {@code ChatType}).
 *
 * <p>Statt eines Mixins auf den Chat wird die Nachricht abgebrochen und selbst gedruckt –
 * mit einer eigenen Zeilen-Kennung, damit sie für das Zusammenfassen später ersetzt werden kann
 * ({@code GuiNewChat.printChatMessageWithOptionalDeletion}/{@code deleteChatLine}, gibt es in allen Versionen).
 */
public final class ChatCompat {
	private ChatCompat() {
	}

	private static GuiNewChat chat() {
		return Mc.mc().ingameGUI == null ? null : Mc.mc().ingameGUI.getChatGUI();
	}

	/** Reiner Text der Nachricht (ohne Farbcodes). */
	public static String plain(ClientChatReceivedEvent event) {
		//? if >=1.9 {
		/*ITextComponent c = event.getMessage();
		*///?} else {
		IChatComponent c = event.message;
		//?}
		return c == null ? "" : c.getUnformattedText();
	}

	/** Text mit Farbcodes (für die Fair-Play-Codes der Karten-Mods). */
	public static String formatted(ClientChatReceivedEvent event) {
		//? if >=1.9 {
		/*ITextComponent c = event.getMessage();
		*///?} else {
		IChatComponent c = event.message;
		//?}
		return c == null ? "" : c.getFormattedText();
	}

	/** Meldung über der Hotbar (nicht im Chat) – die wird nicht angefasst. */
	public static boolean isActionBar(ClientChatReceivedEvent event) {
		//? if >=1.12 {
		/*return event.getType() == net.minecraft.util.text.ChatType.GAME_INFO;
		*///?} elif >=1.9 {
		/*return event.getType() == 2;
		*///?} else
		return event.type == 2;
	}

	/**
	 * Bricht das Ereignis ab und zeigt die Nachricht mit Vorsatz/Nachsatz erneut an.
	 * Das Original wird als Kind angehängt – Farben, Links und Hover-Texte bleiben erhalten.
	 *
	 * @param id eigene Zeilen-Kennung (≠ 0), damit die Zeile später ersetzt werden kann
	 * @return true, wenn die Zeile ersetzt wurde
	 */
	public static boolean reprint(ClientChatReceivedEvent event, String prefix, String suffix, int id) {
		GuiNewChat gui = chat();
		if (gui == null) return false;
		//? if >=1.9 {
		/*ITextComponent original = event.getMessage();
		if (original == null) return false;
		TextComponentString line = new TextComponentString(prefix);
		line.appendSibling(original);
		if (!suffix.isEmpty()) line.appendSibling(new TextComponentString(suffix));
		*///?} else {
		IChatComponent original = event.message;
		if (original == null) return false;
		ChatComponentText line = new ChatComponentText(prefix);
		line.appendSibling(original);
		if (!suffix.isEmpty()) line.appendSibling(new ChatComponentText(suffix));
		//?}
		event.setCanceled(true);
		gui.printChatMessageWithOptionalDeletion(line, id);
		return true;
	}

	/** Entfernt die Zeile mit dieser Kennung wieder. */
	public static void deleteLine(int id) {
		GuiNewChat gui = chat();
		if (gui != null) gui.deleteChatLine(id);
	}

	/**
	 * Koordinaten in der Nachricht unterstreichen, als Einfüge-Text „x y z“ markieren und mit Hinweis versehen (nur
	 * Textstücke ohne eigene Klick-/Einfüge-Aktion). Rückgabe: geändert?
	 */
	public static boolean markCoords(ClientChatReceivedEvent event, String hoverText) {
		//? if >=1.9 {
		/*ITextComponent original = event.getMessage();
		if (original == null) return false;
		TextComponentString out = new TextComponentString("");
		boolean changed = false;
		for (ITextComponent part : original) {
			String text = part.getUnformattedComponentText();
			net.minecraft.util.text.Style style = part.getStyle().createDeepCopy();
			java.util.List<dev.theredstonee.trsclient.core.chat.ChatCoords.Hit> hits = style.getClickEvent() == null
					&& style.getInsertion() == null ? dev.theredstonee.trsclient.core.chat.ChatCoords.find(text)
					: java.util.Collections.<dev.theredstonee.trsclient.core.chat.ChatCoords.Hit>emptyList();
			int pos = 0;
			for (dev.theredstonee.trsclient.core.chat.ChatCoords.Hit h : hits) {
				if (h.start > pos) out.appendSibling(new TextComponentString(text.substring(pos, h.start)).setStyle(style.createShallowCopy()));
				net.minecraft.util.text.Style mark = style.createShallowCopy();
				mark.setUnderlined(true);
				mark.setInsertion(h.insertion());
				mark.setHoverEvent(new net.minecraft.util.text.event.HoverEvent(net.minecraft.util.text.event.HoverEvent.Action.SHOW_TEXT,
						new TextComponentString(hoverText)));
				out.appendSibling(new TextComponentString(text.substring(h.start, h.end)).setStyle(mark));
				pos = h.end;
				changed = true;
			}
			if (pos < text.length()) out.appendSibling(new TextComponentString(text.substring(pos)).setStyle(style.createShallowCopy()));
		}
		if (changed) event.setMessage(out);
		return changed;
		*///?} else {
		IChatComponent original = event.message;
		if (original == null) return false;
		ChatComponentText out = new ChatComponentText("");
		boolean changed = false;
		for (IChatComponent part : original) {
			String text = part.getUnformattedTextForChat();
			net.minecraft.util.ChatStyle style = part.getChatStyle().createDeepCopy();
			java.util.List<dev.theredstonee.trsclient.core.chat.ChatCoords.Hit> hits = style.getChatClickEvent() == null
					&& style.getInsertion() == null ? dev.theredstonee.trsclient.core.chat.ChatCoords.find(text)
					: java.util.Collections.<dev.theredstonee.trsclient.core.chat.ChatCoords.Hit>emptyList();
			int pos = 0;
			for (dev.theredstonee.trsclient.core.chat.ChatCoords.Hit h : hits) {
				if (h.start > pos) out.appendSibling(new ChatComponentText(text.substring(pos, h.start)).setChatStyle(style.createShallowCopy()));
				net.minecraft.util.ChatStyle mark = style.createShallowCopy();
				mark.setUnderlined(true);
				mark.setInsertion(h.insertion());
				mark.setChatHoverEvent(new net.minecraft.event.HoverEvent(net.minecraft.event.HoverEvent.Action.SHOW_TEXT,
						new ChatComponentText(hoverText)));
				out.appendSibling(new ChatComponentText(text.substring(h.start, h.end)).setChatStyle(mark));
				pos = h.end;
				changed = true;
			}
			if (pos < text.length()) out.appendSibling(new ChatComponentText(text.substring(pos)).setChatStyle(style.createShallowCopy()));
		}
		if (changed) event.message = out;
		return changed;
		//?}
	}

	/**
	 * Eigene Chatzeile aus Teilen der Screenshot-Werkzeuge (Aktionen gold mit Einfüge-Text + Hinweis, Dateiname
	 * unterstrichen).
	 */
	public static void printScreenshotLine(java.util.List<dev.theredstonee.trsclient.core.screenshot.Screenshots.Part> parts) {
		GuiNewChat gui = chat();
		if (gui == null) return;
		//? if >=1.9 {
		/*TextComponentString out = new TextComponentString("");
		for (dev.theredstonee.trsclient.core.screenshot.Screenshots.Part p : parts) {
			net.minecraft.util.text.Style st = new net.minecraft.util.text.Style();
			if (p.kind == dev.theredstonee.trsclient.core.screenshot.Screenshots.Part.Kind.ACTION) {
				st.setColor(net.minecraft.util.text.TextFormatting.GOLD);
			} else if (p.kind == dev.theredstonee.trsclient.core.screenshot.Screenshots.Part.Kind.NAME) {
				st.setUnderlined(true);
			}
			if (p.insertion != null) st.setInsertion(p.insertion);
			if (p.hover != null) {
				st.setHoverEvent(new net.minecraft.util.text.event.HoverEvent(net.minecraft.util.text.event.HoverEvent.Action.SHOW_TEXT,
						new TextComponentString(p.hover)));
			}
			out.appendSibling(new TextComponentString(p.text).setStyle(st));
		}
		gui.printChatMessage(out);
		*///?} else {
		ChatComponentText out = new ChatComponentText("");
		for (dev.theredstonee.trsclient.core.screenshot.Screenshots.Part p : parts) {
			net.minecraft.util.ChatStyle st = new net.minecraft.util.ChatStyle();
			if (p.kind == dev.theredstonee.trsclient.core.screenshot.Screenshots.Part.Kind.ACTION) {
				st.setColor(net.minecraft.util.EnumChatFormatting.GOLD);
			} else if (p.kind == dev.theredstonee.trsclient.core.screenshot.Screenshots.Part.Kind.NAME) {
				st.setUnderlined(true);
			}
			if (p.insertion != null) st.setInsertion(p.insertion);
			if (p.hover != null) {
				st.setChatHoverEvent(new net.minecraft.event.HoverEvent(net.minecraft.event.HoverEvent.Action.SHOW_TEXT,
						new ChatComponentText(p.hover)));
			}
			out.appendSibling(new ChatComponentText(p.text).setChatStyle(st));
		}
		gui.printChatMessage(out);
		//?}
	}

	/** Einfüge-Text der Chat-Komponente unter der Maus – nur ohne eigene Klick-Aktion –, sonst null. */
	public static String insertionAt(int rawMouseX, int rawMouseY) {
		GuiNewChat gui = chat();
		if (gui == null) return null;
		//? if >=1.9 {
		/*ITextComponent c = gui.getChatComponent(rawMouseX, rawMouseY);
		if (c == null || c.getStyle().getClickEvent() != null) return null;
		return c.getStyle().getInsertion();
		*///?} else {
		IChatComponent c = gui.getChatComponent(rawMouseX, rawMouseY);
		if (c == null || c.getChatStyle().getChatClickEvent() != null) return null;
		return c.getChatStyle().getInsertion();
		//?}
	}

	/** Text der Chat-Komponente unter der Maus (rohe LWJGL-Mauskoordinaten), null = keine. */
	public static String componentAt(int rawMouseX, int rawMouseY) {
		GuiNewChat gui = chat();
		if (gui == null) return null;
		//? if >=1.9 {
		/*ITextComponent c = gui.getChatComponent(rawMouseX, rawMouseY);
		*///?} else {
		IChatComponent c = gui.getChatComponent(rawMouseX, rawMouseY);
		//?}
		return c == null ? null : c.getUnformattedText();
	}
}
