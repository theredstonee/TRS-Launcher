package dev.theredstonee.trsclient.menus;

import com.mojang.blaze3d.platform.InputConstants;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.keys.KeySearch;
import dev.theredstonee.trsclient.mixin.KeySearchListInvoker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suche in der Minecraft-Tastenbelegung: Suchfeld + „Taste drücken zum Suchen“ oben links im Bildschirm, der eine Liste
 * mit Tasten-Einträgen hat (Tastenbelegung ab 1.18, Steuerung davor – auch Mods wie Controlling). Erkannt wird die
 * Liste an ihren Einträgen (Feld vom Typ {@link KeyMapping}), nicht am Klassennamen – der wechselt je Version.
 * Gefiltert wird mit {@code replaceEntries}: Kategorie-Zeilen, Knöpfe und Scrollen bleiben die von Minecraft.
 * Die Suchlogik liegt in {@link KeySearch}.
 */
public final class KeySearchUi {
	private KeySearchUi() {
	}

	private static final Object NONE = new Object();
	private static final Map<Class<?>, Object> KEY_FIELDS = new HashMap<Class<?>, Object>();

	private static String query = "";
	private static Screen screen;
	private static AbstractSelectionList<?> list;
	private static List<Object> original;
	private static EditBox box;
	private static Object captureButton;
	private static boolean capturing;
	private static boolean waitRelease;

	// --- Nach init(): Liste finden, Suchfeld und Knopf einsetzen ---

	public static void afterInit(Screen s, WidgetHost host) {
		if (s != screen) query = "";
		screen = null;
		list = null;
		original = null;
		box = null;
		capturing = false;
		if (!KeySearch.enabled()) return;
		try {
			AbstractSelectionList<?> found = null;
			for (GuiEventListener child : s.children()) {
				if (child instanceof AbstractSelectionList && hasKeyEntries((AbstractSelectionList<?>) child)) {
					found = (AbstractSelectionList<?>) child;
					break;
				}
			}
			if (found == null) return;
			screen = s;
			list = found;
			original = new ArrayList<Object>(found.children());
			addWidgets(s, host);
			if (!query.isEmpty()) apply();
		} catch (RuntimeException | LinkageError e) {
			// Ohne Suche weiter – die Tastenbelegung selbst bleibt unberührt.
			screen = null;
			list = null;
		}
	}

	private static void addWidgets(Screen s, WidgetHost host) {
		net.minecraft.client.gui.Font font = Mc.mc().font;
		String title = s.getTitle() == null ? "" : s.getTitle().getString();
		int titleLeft = s.width / 2 - font.width(title) / 2 - 8;
		//? if >=1.18 {
		/*int y = 6;
		int h = 20;
		*///?} else {
		// Bis 1.17 liegen oben bei y=18 die Knöpfe „Maus-Einstellungen“/„Automatisch springen“ – das Feld passt darüber.
		int y = 3;
		int h = 13;
		//?}
		String captureLabel = I18n.tr("keysearch.captureShort");
		int buttonW = font.width(captureLabel) + 12;
		int w = Math.max(70, Math.min(170, titleLeft - 6 - buttonW - 4));
		EditBox b = newBox(font, 6, y, w, h);
		b.setMaxLength(100);
		b.setValue(query);
		b.setResponder(value -> {
			if (value.equals(query)) return;
			query = value;
			apply();
		});
		updateHint(b);
		//? if >=1.19.3 {
		/*b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(VanillaMenus.text(I18n.tr("keysearch.help"))));
		*///?}
		box = b;
		host.trsclient$addWidget(b);
		Object capture = VanillaMenus.button(6 + w + 4, y, buttonW, h, captureLabel, new Runnable() {
			@Override
			public void run() {
				startCapture();
			}
		});
		//? if >=1.19.3 {
		/*((net.minecraft.client.gui.components.AbstractWidget) capture).setTooltip(net.minecraft.client.gui.components.Tooltip.create(VanillaMenus.text(I18n.tr("keysearch.capture"))));
		*///?}
		captureButton = capture;
		host.trsclient$addWidget(capture);
	}

	private static EditBox newBox(net.minecraft.client.gui.Font font, int x, int y, int w, int h) {
		//? if >=1.16 {
		return new EditBox(font, x, y, w, h, VanillaMenus.text(I18n.tr("module.keySearch")));
		//?} else
		/*return new EditBox(font, x, y, w, h, I18n.tr("module.keySearch"));*/
	}

	private static void updateHint(EditBox b) {
		String hint = capturing ? I18n.tr("keysearch.capturing") : I18n.tr("keysearch.hint");
		// Der Vorschlag wird nicht beschnitten – auf die Feldbreite kürzen, sonst läuft er in den Titel.
		b.setSuggestion(b.getValue().isEmpty() ? fit(hint, b.getWidth() - 10) : null);
	}

	private static String fit(String s, int max) {
		net.minecraft.client.gui.Font f = Mc.mc().font;
		if (f.width(s) <= max) return s;
		String t = s;
		while (!t.isEmpty() && f.width(t + "…") > max) t = t.substring(0, t.length() - 1);
		return t.trim() + "…";
	}

	// --- Filtern ---

	private static void apply() {
		AbstractSelectionList<?> l = list;
		List<Object> all = original;
		if (l == null || all == null) return;
		List<KeySearch.Entry> entries = new ArrayList<KeySearch.Entry>();
		for (Object o : all) {
			KeyMapping km = keyOf(o);
			if (km != null) entries.add(entry(km, o));
		}
		List<KeySearch.Entry> hits = KeySearch.filter(entries, query);
		Set<Object> keep = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
		for (KeySearch.Entry e : hits) keep.add(e.handle);
		List<Object> out = new ArrayList<Object>();
		Object category = null;
		for (Object o : all) {
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
		((KeySearchListInvoker) l).trsclient$replaceEntries(out);
		l.setScrollAmount(0);
		EditBox b = box;
		if (b != null) {
			b.setTextColor(!query.trim().isEmpty() && hits.isEmpty() ? 0xFFFF6B6B : 0xFFE0E0E0);
			updateHint(b);
		}
	}

	private static KeySearch.Entry entry(KeyMapping km, Object handle) {
		String catKey;
		String catName;
		//? if >=1.21.9 {
		/*KeyMapping.Category c = km.getCategory();
		catKey = c.id().toString();
		catName = c.label().getString();
		*///?} else {
		catKey = km.getCategory();
		catName = net.minecraft.client.resources.language.I18n.get(catKey);
		//?}
		//? if >=1.16 {
		String label = km.getTranslatedKeyMessage().getString();
		//?} else
		/*String label = km.getTranslatedKeyMessage();*/
		return new KeySearch.Entry(net.minecraft.client.resources.language.I18n.get(km.getName()), km.getName(), catName, catKey,
				km.saveString(), label, handle);
	}

	private static boolean hasKeyEntries(AbstractSelectionList<?> l) {
		int checked = 0;
		for (Object o : l.children()) {
			if (keyOf(o) != null) return true;
			if (++checked > 4) break;
		}
		return false;
	}

	/** Die {@link KeyMapping} eines Listeneintrags (per Feldtyp, damit es mit jeder Namensgebung geht) oder {@code null}. */
	private static KeyMapping keyOf(Object entry) {
		if (entry == null) return null;
		Class<?> c = entry.getClass();
		Object f = KEY_FIELDS.get(c);
		if (f == null) {
			f = NONE;
			search:
			for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
				for (Field field : k.getDeclaredFields()) {
					if (KeyMapping.class.isAssignableFrom(field.getType()) && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
						try {
							field.setAccessible(true);
							f = field;
						} catch (RuntimeException e) {
							f = NONE;
						}
						break search;
					}
				}
			}
			KEY_FIELDS.put(c, f);
		}
		if (f == NONE) return null;
		try {
			return (KeyMapping) ((Field) f).get(entry);
		} catch (IllegalAccessException | RuntimeException e) {
			return null;
		}
	}

	// --- Nur für den Autotest ---

	/** Anzahl sichtbarer Zeilen (Kategorien + Tasten) oder -1, wenn keine Tastenliste erkannt wurde. */
	public static int testRows() {
		return list == null ? -1 : list.children().size();
	}

	/** Doppelt belegte Tasten mit Namen (Diagnose). */
	public static String testConflicts() {
		if (original == null) return "";
		List<KeySearch.Entry> entries = new ArrayList<KeySearch.Entry>();
		for (Object o : original) {
			KeyMapping km = keyOf(o);
			if (km != null) entries.add(entry(km, o));
		}
		KeySearch.markConflicts(entries);
		StringBuilder sb = new StringBuilder();
		for (KeySearch.Entry e : entries) if (e.conflict()) sb.append(e.keyCode).append('=').append(e.nameKey).append(' ');
		return sb.toString();
	}

	public static void testQuery(String q) {
		if (box != null) box.setValue(q);
	}

	/** Wie ein Tastendruck im Such-Modus (die echte Abfrage liest den Tastaturzustand, den der Test nicht setzen kann). */
	public static void testCapture(String keyCode) {
		startCapture();
		capturing = false;
		String q = KeySearch.queryFor(keyCode);
		query = q;
		if (box != null) box.setValue(q);
		apply();
	}

	public static boolean testCapturing() {
		return capturing;
	}

	public static void testStartCapture() {
		startCapture();
	}

	// --- Taste drücken zum Suchen ---

	private static void startCapture() {
		if (screen == null || box == null) return;
		capturing = true;
		waitRelease = true;
		query = "";
		box.setValue("");
		//? if >=1.19.4 {
		/*box.setFocused(false);
		*///?} else
		box.setFocus(false);
		screen.setFocused(null);
		updateHint(box);
	}

	/** Jede Tick bzw. jedes Bild: gedrückte Taste abfragen (außer der linken Maustaste – die klickt in der Liste). */
	public static void poll() {
		if (!capturing) return;
		Screen s = Mc.screen();
		if (s == null || s != screen || box == null) {
			capturing = false;
			return;
		}
		boolean anyDown;
		String code = null;
		//? if >=26.3 {
		/*// Ab 26.3 SDL: Tasten über InputConstants (Scancodes), Maus nur rechts/mitte (Seitentasten meldet 26.x hier nicht).
		net.minecraft.client.MouseHandler mouse = Mc.mc().mouseHandler;
		anyDown = mouse.isLeftPressed();
		for (int k = 4; k < 512; k++) {
			if (!InputConstants.isKeyDown(k)) continue;
			anyDown = true;
			if (code == null) code = InputConstants.Type.KEYBOARD.getOrCreate(k).getName();
		}
		if (mouse.isRightPressed()) {
			anyDown = true;
			if (code == null) code = InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_RIGHT).getName();
		}
		if (mouse.isMiddlePressed()) {
			anyDown = true;
			if (code == null) code = InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_MIDDLE).getName();
		}
		*///?} else {
		long handle = windowHandle();
		anyDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
		for (int k = org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE; k <= org.lwjgl.glfw.GLFW.GLFW_KEY_LAST; k++) {
			if (org.lwjgl.glfw.GLFW.glfwGetKey(handle, k) != org.lwjgl.glfw.GLFW.GLFW_PRESS) continue;
			anyDown = true;
			if (code == null) code = InputConstants.Type.KEYSYM.getOrCreate(k).getName();
		}
		for (int m = org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_2; m <= org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LAST; m++) {
			if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, m) != org.lwjgl.glfw.GLFW.GLFW_PRESS) continue;
			anyDown = true;
			if (code == null) code = InputConstants.Type.MOUSE.getOrCreate(m).getName();
		}
		//?}
		if (waitRelease) {
			if (!anyDown) waitRelease = false;
			return;
		}
		if (code == null) return;
		capturing = false;
		if (code.equals("key.keyboard.escape")) {
			updateHint(box);
			return;
		}
		String q = KeySearch.queryFor(code);
		query = q;
		box.setValue(q);
		apply();
	}

	//? if >=1.21.9 && <26.3 {
	/*private static long windowHandle() {
		return Mc.window().handle();
	}
	*///?} elif <1.21.9 {
	private static long windowHandle() {
		return Mc.window().getWindow();
	}
	//?}
}
