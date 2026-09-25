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
