package dev.theredstonee.trsclient.core.screenshot.edit;

import java.util.ArrayDeque;
import java.util.Deque;

/** Rückgängig/Wiederholen über {@link EditState}-Stände (klein – nur Listen, keine Bilddaten). */
public final class EditHistory {
	static final int MAX_STEPS = 200;

	private final Deque<EditState> undo = new ArrayDeque<>();
	private final Deque<EditState> redo = new ArrayDeque<>();
	private EditState current;
	private int revision;

	public EditHistory(EditState initial) {
		this.current = initial;
	}

	public EditState current() {
		return current;
	}

	/** Zählt jede Änderung (auch Rückgängig) – Vorschau neu zeichnen, wenn sie sich ändert. */
	public int revision() {
		return revision;
	}

	/** Neuer Stand; gleiche Stände werden ignoriert. Löscht „Wiederholen“. */
	public void push(EditState next) {
		if (next == null || next == current) return;
		undo.push(current);
		while (undo.size() > MAX_STEPS) undo.removeLast();
		redo.clear();
		current = next;
		revision++;
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	public boolean undo() {
		if (undo.isEmpty()) return false;
		redo.push(current);
		current = undo.pop();
		revision++;
		return true;
	}

	public boolean redo() {
		if (redo.isEmpty()) return false;
		undo.push(current);
		current = redo.pop();
		revision++;
		return true;
	}
}
