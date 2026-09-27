package dev.theredstonee.trsclient.menus;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.keys.KeySearch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Suche in der Tastenbelegung (Optionen → Steuerung) für Forge 1.8.9–1.12.2: Suchfeld und „Taste…“-Knopf oben links,
 * gefiltert wird das Eintrags-Array der Tastenliste (per Feldtyp gefunden – SRG-/MCP-Namen egal). Eingaben ins Feld
 * über die Forge-Ereignisse (Tastatur/Maus vor dem Bildschirm), damit die Steuerung selbst unverändert bleibt.
 * Die Suchlogik liegt in {@link KeySearch}.
 */
public final class LegacyKeySearch {
	private static final int CAPTURE_ID = 73201;
	private static final Object NONE = new Object();
	private static final Map<Class<?>, Object> KEY_FIELDS = new HashMap<Class<?>, Object>();
	private static final Map<String, String> LWJGL_NAMES = new HashMap<String, String>();

	static {
		String[][] names = {
				{ "LSHIFT", "left.shift" }, { "RSHIFT", "right.shift" }, { "LCONTROL", "left.control" }, { "RCONTROL", "right.control" },
				{ "LMENU", "left.alt" }, { "RMENU", "right.alt" }, { "LMETA", "left.win" }, { "RMETA", "right.win" },
				{ "RETURN", "enter" }, { "BACK", "backspace" }, { "CAPITAL", "caps.lock" }, { "GRAVE", "grave.accent" },
				{ "EQUALS", "equal" }, { "LBRACKET", "left.bracket" }, { "RBRACKET", "right.bracket" }, { "PRIOR", "page.up" },
				{ "NEXT", "page.down" }, { "SCROLL", "scroll.lock" }, { "NUMLOCK", "num.lock" }, { "SYSRQ", "print.screen" },
				{ "DECIMAL", "keypad.decimal" }, { "ADD", "keypad.add" }, { "SUBTRACT", "keypad.subtract" },
				{ "MULTIPLY", "keypad.multiply" }, { "DIVIDE", "keypad.divide" }, { "NUMPADENTER", "keypad.enter" },
				{ "NUMPADEQUALS", "keypad.equal" },
		};
		for (String[] n : names) LWJGL_NAMES.put(n[0], n[1]);
		for (int i = 0; i <= 9; i++) LWJGL_NAMES.put("NUMPAD" + i, "keypad." + i);
	}

	private String query = "";
	private GuiScreen screen;
	private Object list;
	private Field entriesField;
	private Object originalEntries;
	private GuiTextField box;
	private GuiButton captureButton;
	private boolean capturing;
	private boolean waitRelease;
	private boolean noMatch;

	// --- Ereignisse ---

	@SubscribeEvent
	public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
		GuiScreen s = Mc.eventGui(event);
		if (s != screen) query = "";
		screen = null;
		list = null;
		box = null;
		capturing = false;
		if (!(s instanceof GuiControls) || !KeySearch.enabled()) return;
		try {
			Object l = fieldOfType(s, "GuiKeyBindingList");
			if (l == null) return;
			Field f = arrayField(l);
			if (f == null) return;
			screen = s;
			list = l;
			entriesField = f;
			originalEntries = f.get(l);
			FontRenderer font = Mc.font();
			String captureLabel = I18n.tr("keysearch.captureShort");
			int buttonW = font.getStringWidth(captureLabel) + 12;
			int titleLeft = s.width / 2 - font.getStringWidth(net.minecraft.client.resources.I18n.format("controls.title")) / 2 - 8;
			int w = Math.max(70, Math.min(170, titleLeft - 6 - buttonW - 4));
			box = new GuiTextField(CAPTURE_ID + 1, font, 6, 3, w, 13);
			box.setMaxStringLength(100);
			box.setText(query);
			captureButton = new GuiButton(CAPTURE_ID, 6 + w + 4, 2, buttonW, 15, captureLabel);
			buttonList(event).add(captureButton);
			if (!query.isEmpty()) apply();
		} catch (RuntimeException | IllegalAccessException | LinkageError e) {
			screen = null;
			list = null;
		}
	}

	@SubscribeEvent
	public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
		GuiButton b = button(event);
		if (b == null || b != captureButton || Mc.eventGui(event) != screen) return;
		event.setCanceled(true);
		capturing = true;
		waitRelease = true;
		query = "";
		if (box != null) {
			box.setText("");
			box.setFocused(false);
		}
		apply();
	}

	@SubscribeEvent
	public void onKeyboard(GuiScreenEvent.KeyboardInputEvent.Pre event) {
		if (box == null || Mc.eventGui(event) != screen || !box.isFocused()) return;
		event.setCanceled(true);
		if (!Keyboard.getEventKeyState()) return;
		int key = Keyboard.getEventKey();
		if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN) {
			box.setFocused(false);
			return;
		}
		String before = box.getText();
		box.textboxKeyTyped(Keyboard.getEventCharacter(), key);
		if (!before.equals(box.getText())) {
			query = box.getText();
			apply();
		}
	}

	@SubscribeEvent
	public void onMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
		GuiScreen s = Mc.eventGui(event);
		if (box == null || s != screen || !Mouse.getEventButtonState()) return;
		Minecraft mc = Mc.mc();
		int x = Mouse.getEventX() * s.width / mc.displayWidth;
		int y = s.height - Mouse.getEventY() * s.height / mc.displayHeight - 1;
		boolean inside = x >= xOf(box) && x < xOf(box) + box.getWidth() && y >= yOf(box) && y < yOf(box) + 13;
		box.mouseClicked(x, y, Mouse.getEventButton());
		if (inside) {
			capturing = false;
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
		if (box == null || Mc.eventGui(event) != screen) return;
		poll();
		box.setTextColor(noMatch ? 0xFF6B6B : 0xE0E0E0);
		box.drawTextBox();
		if (box.getText().isEmpty() && !box.isFocused()) {
			FontRenderer font = Mc.font();
			String hint = fit(capturing ? I18n.tr("keysearch.capturing") : I18n.tr("keysearch.hint"), box.getWidth() - 8);
			font.drawString(hint, xOf(box) + 4, yOf(box) + 3, 0x808080);
		}
	}

	// --- Taste drücken zum Suchen ---

	private void poll() {
		if (!capturing) return;
		boolean anyDown = Mouse.isButtonDown(0);
		String code = null;
		for (int k = 1; k < 256; k++) {
			if (!Keyboard.isKeyDown(k)) continue;
			anyDown = true;
			if (code == null) code = keyCode(k);
		}
		for (int b = 1; b < Math.min(16, Mouse.getButtonCount()); b++) {
			if (!Mouse.isButtonDown(b)) continue;
			anyDown = true;
			if (code == null) code = keyCode(b - 100);
		}
		if (waitRelease) {
			if (!anyDown) waitRelease = false;
			return;
		}
		if (code == null) return;
		capturing = false;
		if (code.equals("key.keyboard.escape")) return;
		query = KeySearch.queryFor(code);
		if (box != null) box.setText(query);
		apply();
	}

	// --- Filtern ---

	private void apply() {
		Object l = list;
		Object all = originalEntries;
		if (l == null || all == null || entriesField == null) return;
		int n = Array.getLength(all);
		List<KeySearch.Entry> entries = new ArrayList<KeySearch.Entry>();
		for (int i = 0; i < n; i++) {
			Object o = Array.get(all, i);
			KeyBinding kb = keyOf(o);
			if (kb != null) entries.add(entry(kb, o));
		}
		List<KeySearch.Entry> hits = KeySearch.filter(entries, query);
		Set<Object> keep = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
		for (KeySearch.Entry e : hits) keep.add(e.handle);
		List<Object> out = new ArrayList<Object>();
		Object category = null;
		for (int i = 0; i < n; i++) {
			Object o = Array.get(all, i);
			if (keyOf(o) == null) {
				category = o;
				continue;
			}
			if (!keep.contains(o)) continue;
			if (category != null) {
				out.add(category);
				category = null;
			}
			out.add(o);
		}
		Object arr = Array.newInstance(all.getClass().getComponentType(), out.size());
		for (int i = 0; i < out.size(); i++) Array.set(arr, i, out.get(i));
		try {
			entriesField.set(l, arr);
			if (l instanceof net.minecraft.client.gui.GuiSlot) ((net.minecraft.client.gui.GuiSlot) l).scrollBy(-100000);
		} catch (IllegalAccessException | RuntimeException e) {
			// Liste bleibt ungefiltert.
		}
		noMatch = !query.trim().isEmpty() && hits.isEmpty();
	}

	private static KeySearch.Entry entry(KeyBinding kb, Object handle) {
		String cat = kb.getKeyCategory();
		int code = kb.getKeyCode();
		return new KeySearch.Entry(net.minecraft.client.resources.I18n.format(kb.getKeyDescription()), kb.getKeyDescription(),
				net.minecraft.client.resources.I18n.format(cat), cat, keyCode(code), GameSettings.getKeyDisplayString(code), handle);
	}

	/** LWJGL-2-Tastencode → Name wie ab 1.13 („key.keyboard.left.shift“, „key.mouse.4“), damit die Suche gleich ist. */
	static String keyCode(int code) {
		if (code == 0) return "key.keyboard.unknown";
		if (code < 0) {
			int b = code + 100;
			if (b == 0) return "key.mouse.left";
			if (b == 1) return "key.mouse.right";
			if (b == 2) return "key.mouse.middle";
			return "key.mouse." + (b + 1);
		}
		String name = Keyboard.getKeyName(code);
		if (name == null) return "key.keyboard.unknown";
		String mapped = LWJGL_NAMES.get(name);
		return "key.keyboard." + (mapped != null ? mapped : name.toLowerCase(Locale.ROOT));
	}

	private static KeyBinding keyOf(Object entry) {
		if (entry == null) return null;
		Class<?> c = entry.getClass();
		Object f = KEY_FIELDS.get(c);
		if (f == null) {
			f = NONE;
			search:
			for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
				for (Field field : k.getDeclaredFields()) {
					if (KeyBinding.class.isAssignableFrom(field.getType()) && !Modifier.isStatic(field.getModifiers())) {
						field.setAccessible(true);
						f = field;
						break search;
					}
				}
			}
			KEY_FIELDS.put(c, f);
		}
		if (f == NONE) return null;
		try {
			return (KeyBinding) ((Field) f).get(entry);
		} catch (IllegalAccessException | RuntimeException e) {
			return null;
		}
	}

	/** Feld eines Objekts, dessen Typ so heißt (einfacher Klassenname) – die Tastenliste der Steuerung. */
	private static Object fieldOfType(Object o, String simpleName) throws IllegalAccessException {
		for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
			for (Field f : k.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) continue;
				for (Class<?> t = f.getType(); t != null && t != Object.class; t = t.getSuperclass()) {
					if (t.getSimpleName().equals(simpleName)) {
						f.setAccessible(true);
						return f.get(o);
					}
				}
			}
		}
		return null;
	}

	/** Das Eintrags-Array der Liste (IGuiListEntry[]). */
	private static Field arrayField(Object l) {
		for (Class<?> k = l.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
			for (Field f : k.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers()) && f.getType().isArray() && !f.getType().getComponentType().isPrimitive()
						&& f.getType().getComponentType().getSimpleName().contains("IGuiListEntry")) {
					f.setAccessible(true);
					return f;
				}
			}
		}
		return null;
	}

	private static String fit(String s, int max) {
		FontRenderer f = Mc.font();
		if (f.getStringWidth(s) <= max) return s;
		String t = s;
		while (!t.isEmpty() && f.getStringWidth(t + "…") > max) t = t.substring(0, t.length() - 1);
		return t.trim() + "…";
	}

	@SuppressWarnings("unchecked")
	private static List<GuiButton> buttonList(GuiScreenEvent.InitGuiEvent.Post event) {
		//? if >=1.9 {
		/*return event.getButtonList();
		*///?} else
		return event.buttonList;
	}

	private static GuiButton button(GuiScreenEvent.ActionPerformedEvent.Pre event) {
		//? if >=1.9 {
		/*return event.getButton();
		*///?} else
		return event.button;
	}

	private static int xOf(GuiTextField f) {
		//? if >=1.11 {
		/*return f.x;
		*///?} else
		return f.xPosition;
	}

	private static int yOf(GuiTextField f) {
		//? if >=1.11 {
		/*return f.y;
		*///?} else
		return f.yPosition;
	}

	// --- Nur für den Autotest ---

	public int testRows() {
		if (list == null || entriesField == null) return -1;
		try {
			return Array.getLength(entriesField.get(list));
		} catch (IllegalAccessException e) {
			return -1;
		}
	}

	public void testQuery(String q) {
		query = q;
		if (box != null) box.setText(q);
		apply();
	}

	public void testCapture(String keyCode) {
		capturing = false;
		testQuery(KeySearch.queryFor(keyCode));
	}
}
