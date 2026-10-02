package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.compat.ChatLines;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Selbsttest Chat-Köpfe ({@code -PtrsAutotestOnly=chatheads}): ein paar Zeilen in den Formaten, die der Kopf
 * erkennen soll, Chat auf, Screenshot. Der eigene Name steht in der Tab-Liste, deshalb bekommt er einen Kopf.
 * Screenshots trsclient-&lt;mc&gt;-chatheads-*.png.
 */
public final class ChatHeadsTest {
	private int phase;
	private int wait;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase++) {
			case 0:
				if (mc.player == null || mc.getUser() == null) return false;
				String me = mc.getUser().getName();
				modules.qol.chatHeads.setEnabled(true);
				modules.qol.chatHeadsHat.set(true);
				modules.chat.setEnabled(false);
				ChatLines.chat().clearMessages(false);
				ChatLines.addMessage(Mc.text("<" + me + "> hello from brackets"));
				ChatLines.addMessage(Mc.text(me + ": hello from colon"));
				ChatLines.addMessage(Mc.text("[Rank] " + me + " » ranked"));
				ChatLines.addMessage(Mc.text(me + " whispers to you: psst"));
				ChatLines.addMessage(Mc.text("[" + me + " -> me] secret"));
				ChatLines.addMessage(Mc.text("From " + me + ": mail"));
				StringBuilder wrapped = new StringBuilder(me).append(": ");
				for (int i = 0; i < 30; i++) wrapped.append("wrapped line ");
				ChatLines.addMessage(Mc.text(wrapped.toString()));
				//? if >=1.21.9 {
				/*Mc.setScreen(new ChatScreen("", false));
				*///?} else
				Mc.setScreen(new ChatScreen(""));
				wait = 30;
				return true;
			case 1:
				actions.shot("trsclient-chatheads-lines");
				Mc.setScreen(null);
				return false;
			default:
				return false;
		}
	}
}
