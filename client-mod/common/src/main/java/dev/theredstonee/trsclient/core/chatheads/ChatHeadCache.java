package dev.theredstonee.trsclient.core.chatheads;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Köpfe je Nachrichten-Objekt (Identität, nicht Textgleichheit), damit eine Zeile nicht in jedem Bild neu
 * gelesen wird. Eigener TRS-Code (GPL-3.0-only, nicht von Chat Heads abgeleitet).
 */
public final class ChatHeadCache {
	public static final int CAP = 2048;

	private final Map<Object, ChatHeadSender> map = new IdentityHashMap<Object, ChatHeadSender>();
	private final ArrayList<Object> order = new ArrayList<Object>();

	public ChatHeadSender get(Object key) {
		if (key == null) return null;
		return map.get(key);
	}

	public void put(Object key, ChatHeadSender sender) {
		if (key == null || sender == null) return;
		if (!map.containsKey(key)) {
			order.add(key);
			while (order.size() > CAP) {
				Object old = order.remove(0);
				map.remove(old);
			}
		}
		map.put(key, sender);
	}

	public int size() {
		return map.size();
	}

	public void clear() {
		map.clear();
		order.clear();
	}
}
