package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.chat.ChatCoords;
import dev.theredstonee.trsclient.core.chat.ChatText;
import dev.theredstonee.trsclient.core.chatheads.ChatHeadLayout;
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
	 * Textstücke ohne eigene Klick-/Einfüge-Aktion); geteilte Wegpunkte von Xaero's Minimap/JourneyMap bekommen die
	 * Nachricht als Einfüge-Text und einen eigenen Hinweis ({@link ChatCoords.Hit#hover}). Rückgabe: geändert?
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
				if (h.shared != null) {
					// Statt der Rohnachricht: „… shared a waypoint called “Dorf” from Nether! [Add] · Xaero's Minimap“.
					for (dev.theredstonee.trsclient.core.chat.ChatWaypoints.Part p : dev.theredstonee.trsclient.core.chat.ChatWaypoints.display(h.shared)) {
						out.appendSibling(part(p, style));
					}
				} else {
					net.minecraft.util.text.Style mark = style.createShallowCopy();
					mark.setUnderlined(true);
					mark.setInsertion(h.insertion());
					mark.setHoverEvent(new net.minecraft.util.text.event.HoverEvent(net.minecraft.util.text.event.HoverEvent.Action.SHOW_TEXT,
							new TextComponentString(h.hover(hoverText))));
					out.appendSibling(new TextComponentString(text.substring(h.start, h.end)).setStyle(mark));
				}
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
				if (h.shared != null) {
					// Statt der Rohnachricht: „… shared a waypoint called “Dorf” from Nether! [Add] · Xaero's Minimap“.
					for (dev.theredstonee.trsclient.core.chat.ChatWaypoints.Part p : dev.theredstonee.trsclient.core.chat.ChatWaypoints.display(h.shared)) {
						out.appendSibling(part(p, style));
					}
				} else {
					net.minecraft.util.ChatStyle mark = style.createShallowCopy();
					mark.setUnderlined(true);
					mark.setInsertion(h.insertion());
					mark.setChatHoverEvent(new net.minecraft.event.HoverEvent(net.minecraft.event.HoverEvent.Action.SHOW_TEXT,
							new ChatComponentText(h.hover(hoverText))));
					out.appendSibling(new ChatComponentText(text.substring(h.start, h.end)).setChatStyle(mark));
				}
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
	 * Ein Stück der Wegpunkt-Darstellung: Text und Name im Stil der Nachricht (Name unterstrichen, Klick = Fenster),
	 * „[Add]“ grün unterstrichen (Klick = sofort speichern), Mod-Name grau.
	 */
	//? if >=1.9 {
	/*private static ITextComponent part(dev.theredstonee.trsclient.core.chat.ChatWaypoints.Part p, net.minecraft.util.text.Style base) {
		net.minecraft.util.text.Style st;
		switch (p.kind) {
			case NAME:
				st = base.createShallowCopy();
				st.setUnderlined(true);
				break;
			case ADD:
				st = new net.minecraft.util.text.Style();
				st.setColor(net.minecraft.util.text.TextFormatting.GREEN);
				st.setUnderlined(true);
				break;
			case MOD:
				st = new net.minecraft.util.text.Style();
				st.setColor(net.minecraft.util.text.TextFormatting.GRAY);
				break;
			default:
				st = base.createShallowCopy();
		}
		if (p.insertion != null) st.setInsertion(p.insertion);
		if (p.hover != null) {
			st.setHoverEvent(new net.minecraft.util.text.event.HoverEvent(net.minecraft.util.text.event.HoverEvent.Action.SHOW_TEXT,
					new TextComponentString(p.hover)));
		}
		return new TextComponentString(p.text).setStyle(st);
	}

	// Text eines Befehls-Klicks (andere Mods, z. B. Xaeros „[Add]“), sonst null.
	private static String clickText(net.minecraft.util.text.event.ClickEvent e) {
		return e != null && (e.getAction() == net.minecraft.util.text.event.ClickEvent.Action.RUN_COMMAND
				|| e.getAction() == net.minecraft.util.text.event.ClickEvent.Action.SUGGEST_COMMAND) ? e.getValue() : null;
	}
	*///?} else {
	private static IChatComponent part(dev.theredstonee.trsclient.core.chat.ChatWaypoints.Part p, net.minecraft.util.ChatStyle base) {
		net.minecraft.util.ChatStyle st;
		switch (p.kind) {
			case NAME:
				st = base.createShallowCopy();
				st.setUnderlined(true);
				break;
			case ADD:
				st = new net.minecraft.util.ChatStyle();
				st.setColor(net.minecraft.util.EnumChatFormatting.GREEN);
				st.setUnderlined(true);
				break;
			case MOD:
				st = new net.minecraft.util.ChatStyle();
				st.setColor(net.minecraft.util.EnumChatFormatting.GRAY);
				break;
			default:
				st = base.createShallowCopy();
		}
		if (p.insertion != null) st.setInsertion(p.insertion);
		if (p.hover != null) {
			st.setChatHoverEvent(new net.minecraft.event.HoverEvent(net.minecraft.event.HoverEvent.Action.SHOW_TEXT,
					new ChatComponentText(p.hover)));
		}
		return new ChatComponentText(p.text).setChatStyle(st);
	}

	// Text eines Befehls-Klicks (andere Mods, z. B. Xaeros „[Add]“), sonst null.
	private static String clickText(net.minecraft.event.ClickEvent e) {
		return e != null && (e.getAction() == net.minecraft.event.ClickEvent.Action.RUN_COMMAND
				|| e.getAction() == net.minecraft.event.ClickEvent.Action.SUGGEST_COMMAND) ? e.getValue() : null;
	}
	//?}

	/**
	 * Hat die (geschmückte) Nachricht einen „[Add]“-Knopf? Dann nicht über {@link #reprintIndented} neu drucken – der
	 * umbrochene Text verliert die Stile und damit den Knopf.
	 */
	public static boolean hasWaypointButton(ClientChatReceivedEvent event) {
		//? if >=1.9 {
		/*ITextComponent original = event.getMessage();
		if (original == null) return false;
		for (ITextComponent part : original) {
			if (dev.theredstonee.trsclient.core.chat.ChatWaypoints.isAddMarker(part.getStyle().getInsertion())
					|| dev.theredstonee.trsclient.core.chat.ChatWaypoints.fromClickCommand(clickText(part.getStyle().getClickEvent())) != null) return true;
		}
		return false;
		*///?} else {
		IChatComponent original = event.message;
		if (original == null) return false;
		for (IChatComponent part : original) {
			if (dev.theredstonee.trsclient.core.chat.ChatWaypoints.isAddMarker(part.getChatStyle().getInsertion())
					|| dev.theredstonee.trsclient.core.chat.ChatWaypoints.fromClickCommand(clickText(part.getChatStyle().getChatClickEvent())) != null) return true;
		}
		return false;
		//?}
	}

	/** Eigene, einfache Chatzeile (Formatcodes mit § erlaubt), z. B. die Rückmeldung nach „[Add]“. */
	public static void printPlain(String text) {
		GuiNewChat gui = chat();
		if (gui == null) return;
		//? if >=1.9 {
		/*gui.printChatMessage(new TextComponentString(text));
		*///?} else {
		gui.printChatMessage(new ChatComponentText(text));
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

	/** Befehl der Klick-Aktion unter der Maus (z. B. Xaeros „[Add]“: /xaero_waypoint_add:…) oder null. */
	public static String clickCommandAt(int rawMouseX, int rawMouseY) {
		GuiNewChat gui = chat();
		if (gui == null) return null;
		//? if >=1.9 {
		/*ITextComponent c = gui.getChatComponent(rawMouseX, rawMouseY);
		return c == null ? null : clickText(c.getStyle().getClickEvent());
		*///?} else {
		IChatComponent c = gui.getChatComponent(rawMouseX, rawMouseY);
		return c == null ? null : clickText(c.getChatStyle().getChatClickEvent());
		//?}
	}

	/**
	 * Wie {@link #reprint}, aber jede umbrochene Zeile fängt mit zwei Leerzeichen an (Platz für den Kopf).
	 * Koordinaten werden danach neu markiert, weil der umbrochene Text die alten Stile nicht mitnimmt.
	 * {@code coordHover} null = keine Koordinaten-Markierung.
	 */
	public static boolean reprintIndented(ClientChatReceivedEvent event, String prefix, String suffix, int id, String coordHover) {
		GuiNewChat gui = chat();
		if (gui == null) return false;
		float scale = gui.getChatScale();
		if (scale <= 0f) scale = 1f;
		int width = ChatHeadLayout.wrapWidth((int) Math.floor(gui.getChatWidth() / scale), ChatHeadLayout.LEGACY_PAD);
		String padded = ChatHeadLayout.indentLines(Mc.font().listFormattedStringToWidth(
				prefix + formatted(event) + suffix, Math.max(1, width)));
		//? if >=1.9 {
		/*event.setMessage(withCoords19(padded, coordHover));
		event.setCanceled(true);
		gui.printChatMessageWithOptionalDeletion(event.getMessage(), id);
		*///?} else {
		event.message = withCoords18(padded, coordHover);
		event.setCanceled(true);
		gui.printChatMessageWithOptionalDeletion(event.message, id);
		//?}
		return true;
	}

	/** Stelle im formatierten Text, an der das {@code plainIndex}-te sichtbare Zeichen steht. */
	private static int atPlain(String formatted, int plainIndex) {
		if (formatted == null) return 0;
		int plain = 0;
		for (int i = 0; i < formatted.length(); i++) {
			if (formatted.charAt(i) == '§') {
				i++;
				continue;
			}
			if (plain == plainIndex) return i;
			plain++;
		}
		return formatted.length();
	}

	//? if >=1.9 {
	/*private static ITextComponent withCoords19(String formatted, String hover) {
		java.util.List<ChatCoords.Hit> hits = hover == null ? java.util.Collections.<ChatCoords.Hit>emptyList()
				: ChatCoords.find(ChatText.strip(formatted));
		if (hits.isEmpty()) return new TextComponentString(formatted);
		TextComponentString out = new TextComponentString("");
		int plain = 0;
		for (ChatCoords.Hit h : hits) {
			int from = atPlain(formatted, plain);
			int start = atPlain(formatted, h.start);
			int end = atPlain(formatted, h.end);
			if (start > from) out.appendSibling(new TextComponentString(formatted.substring(from, start)));
			if (end > start) {
				net.minecraft.util.text.Style style = new net.minecraft.util.text.Style();
				style.setUnderlined(true);
				style.setInsertion(h.insertion());
				style.setHoverEvent(new net.minecraft.util.text.event.HoverEvent(
						net.minecraft.util.text.event.HoverEvent.Action.SHOW_TEXT, new TextComponentString(h.hover(hover))));
				out.appendSibling(new TextComponentString(formatted.substring(start, end)).setStyle(style));
			}
			plain = h.end;
		}
		int tail = atPlain(formatted, plain);
		if (tail < formatted.length()) out.appendSibling(new TextComponentString(formatted.substring(tail)));
		return out;
	}
	*///?} else {
	private static IChatComponent withCoords18(String formatted, String hover) {
		java.util.List<ChatCoords.Hit> hits = hover == null ? java.util.Collections.<ChatCoords.Hit>emptyList()
				: ChatCoords.find(ChatText.strip(formatted));
		if (hits.isEmpty()) return new ChatComponentText(formatted);
		ChatComponentText out = new ChatComponentText("");
		int plain = 0;
		for (ChatCoords.Hit h : hits) {
			int from = atPlain(formatted, plain);
			int start = atPlain(formatted, h.start);
			int end = atPlain(formatted, h.end);
			if (start > from) out.appendSibling(new ChatComponentText(formatted.substring(from, start)));
			if (end > start) {
				net.minecraft.util.ChatStyle style = new net.minecraft.util.ChatStyle();
				style.setUnderlined(true);
				style.setInsertion(h.insertion());
				style.setChatHoverEvent(new net.minecraft.event.HoverEvent(net.minecraft.event.HoverEvent.Action.SHOW_TEXT,
						new ChatComponentText(h.hover(hover))));
				out.appendSibling(new ChatComponentText(formatted.substring(start, end)).setChatStyle(style));
			}
			plain = h.end;
		}
		int tail = atPlain(formatted, plain);
		if (tail < formatted.length()) out.appendSibling(new ChatComponentText(formatted.substring(tail)));
		return out;
	}
	//?}
}
