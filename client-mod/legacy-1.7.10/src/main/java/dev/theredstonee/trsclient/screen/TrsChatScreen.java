package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import net.minecraft.client.gui.GuiChat;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;

/**
 * Vanilla-Chat mit einer Ergänzung: Strg+Klick kopiert die angeklickte Zeile.
 * <p>
 * Forge 10.13 (1.7.10) kennt noch kein {@code GuiScreenEvent.MouseInputEvent} – das gibt es erst ab 1.8.
 * Und solange ein Bildschirm offen ist, der keine Spieleingaben durchlässt, wird auch kein
 * {@code MouseEvent} ausgelöst. Deshalb wird der Vanilla-Chat beim Öffnen über {@code GuiOpenEvent}
 * gegen diese Unterklasse getauscht (nur, wenn es wirklich der Vanilla-Chat ist – die Chat-Oberfläche
 * eines anderen Mods bleibt unangetastet).
 */
public final class TrsChatScreen extends GuiChat {
	/** {@code GuiChat.defaultInputFieldText} – privat, kommt aber beim Tauschen mit. */
	private static Field defaultTextField;
	private static boolean defaultTextFieldChecked;

	public TrsChatScreen(String defaultText) {
		super(defaultText);
	}

	/** Liest den vorgegebenen Eingabetext des getauschten Vanilla-Chats ("/" bei der Befehlstaste). */
	public static String defaultText(GuiChat chat) {
		if (!defaultTextFieldChecked) {
			defaultTextFieldChecked = true;
			// Im Entwicklungs-Client MCP-Name, im fertigen Spiel der SRG-Name.
			for (String name : new String[]{"defaultInputFieldText", "field_146409_v"}) {
				try {
					defaultTextField = GuiChat.class.getDeclaredField(name);
					defaultTextField.setAccessible(true);
					break;
				} catch (NoSuchFieldException ignored) {
					// nächsten Namen versuchen
				}
			}
			if (defaultTextField == null) {
				TrsClient.LOGGER.warn("Chat: vorgegebener Eingabetext nicht lesbar – der Chat öffnet immer leer");
			}
		}
		if (defaultTextField == null) return "";
		try {
			Object value = defaultTextField.get(chat);
			return value instanceof String ? (String) value : "";
		} catch (IllegalAccessException | RuntimeException e) {
			return "";
		}
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		if (button == 0 && isCtrlKeyDown()
				&& TrsClient.get().chat().onChatClick(Mouse.getX(), Mouse.getY())) {
			return;
		}
		super.mouseClicked(mouseX, mouseY, button);
	}
}
