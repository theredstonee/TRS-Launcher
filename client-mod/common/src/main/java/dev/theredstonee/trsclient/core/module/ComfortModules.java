package dev.theredstonee.trsclient.core.module;

/**
 * Komfort-Paket 2 (TRS Client {@link NewSince#COMFORT}): bessere Tooltips (Shulker-Inhalt, Karten-Vorschau,
 * Haltbarkeit, Hunger/Sättigung, kompakte Verzauberungen), Server-Profile (automatischer Wechsel je Server) und
 * Panorama-Screenshots. Alles nur Anzeige bzw. Komfort – nichts wird an den Server geschickt.
 *
 * <p>Logik in {@code core.tooltip}, {@code core.profile} und {@code core.panorama}; die Bäume liefern nur dünne Hooks
 * ({@code comfort/ComfortHooks}).
 */
public final class ComfortModules {
	// --- Tooltips ---
	public final Module tooltips;
	public final BoolSetting tipShulker;
	public final BoolSetting tipMap;
	public final ChoiceSetting<MapSize> tipMapSize;
	public final ChoiceSetting<Durability> tipDurability;
	public final BoolSetting tipFood;
	public final BoolSetting tipEnchants;
	public final BoolSetting tipEnchantsShift;

	// --- Server-Profile ---
	public final Module serverProfiles;
	public final BoolSetting profilesNotify;

	// --- Panorama ---
	public final Module panorama;

	// --- Bildschirmfotos (Vorschau, Editor, Teilen) ---
	public final Module screenshots;
	public final BoolSetting shotToast;
	public final NumberSetting shotSeconds;
	public final BoolSetting shotChatActions;
	public final BoolSetting shotReplaceEssential;

	// --- Hunger-Anzeige (Idee von AppleSkin) ---
	public final Module hunger;
	public final BoolSetting hungerSaturation;
	public final BoolSetting hungerHeldFood;
	public final BoolSetting hungerHealth;
	public final BoolSetting hungerExhaustion;

	// --- Suche in der Tastenbelegung ---
	public final Module keySearch;
	public final ChoiceSetting<PanoramaFormat> panoramaFormat;

	/** Größe der Karten-Vorschau. */
	public enum MapSize implements ChoiceSetting.Option {
		SMALL("Small", 64),
		LARGE("Large", 128);

		private final String label;
		public final int pixels;

		MapSize(String label, int pixels) {
			this.label = label;
			this.pixels = pixels;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Haltbarkeit im Tooltip. */
	public enum Durability implements ChoiceSetting.Option {
		OFF("Off"),
		NUMBER("Number"),
		PERCENT("Percent"),
		BOTH("Number and percent");

		private final String label;

		Durability(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Was die Panorama-Aufnahme speichert. */
	public enum PanoramaFormat implements ChoiceSetting.Option {
		BOTH("6 cube images + 360° image"),
		CUBE("6 cube images"),
		EQUIRECT("360° image (equirectangular)");

		private final String label;

		PanoramaFormat(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}

		public boolean cube() {
			return this != EQUIRECT;
		}

		public boolean equirect() {
			return this != CUBE;
		}
	}

	ComfortModules(ModuleRegistry registry) {
		tooltips = registry.register(new Module("tooltips", "Better Tooltips",
				"More information when hovering over items: shulker box contents as a grid in the box colour, a preview "
						+ "of filled maps, durability as a number, hunger and saturation of food and compact enchantments. "
						+ "Display only.", true));
		tipShulker = tooltips.add(new BoolSetting("shulker", "Shulker box contents", true));
		tipMap = tooltips.add(new BoolSetting("map", "Map preview", true));
		tipMapSize = tooltips.add(new ChoiceSetting<MapSize>("mapSize", "Map preview size", MapSize.class, MapSize.SMALL));
		tipDurability = tooltips.add(new ChoiceSetting<Durability>("durability", "Durability", Durability.class, Durability.BOTH));
		tipFood = tooltips.add(new BoolSetting("food", "Hunger and saturation of food", true));
		tipEnchants = tooltips.add(new BoolSetting("enchants", "Compact enchantments", true));
		tipEnchantsShift = tooltips.add(new BoolSetting("enchantsShift", "Hold Shift for details", true));

		serverProfiles = registry.register(new Module("serverProfiles", "Server Profiles",
				"Switches to a saved setup when you join a server (modules on/off, HUD layout) and back to Standard when "
						+ "you leave. Assign profiles by address or pattern (e.g. *.hypixel.net) – or to singleplayer.", true));
		profilesNotify = serverProfiles.add(new BoolSetting("notify", "Show a message when switching", true));

		panorama = registry.register(new Module("panorama", "Panorama Screenshots",
				"Takes a 360° panorama with one key (unbound by default) or from this page: six cube images like the "
						+ "title screen panorama and/or one 360° image, saved in screenshots/panorama. The HUD is hidden "
						+ "while recording.", true));
		panoramaFormat = panorama.add(new ChoiceSetting<PanoramaFormat>("format", "Save as", PanoramaFormat.class,
				PanoramaFormat.BOTH));

		screenshots = registry.register(new Module("screenshots", "Screenshot Tools",
				"After F2 a small preview appears in the top right: edit, favourite, copy the picture or send it to "
						+ "friends (open the chat or inventory to click it). The chat message gets the same actions and "
						+ "shows the picture on hover. The editor crops, rotates, draws arrows, frames, text and pixelates "
						+ "areas – it saves a copy, the original stays untouched.", true));
		shotToast = screenshots.add(new BoolSetting("toast", "Preview after a screenshot", true));
		shotSeconds = screenshots.add(new NumberSetting("seconds", "Preview duration", 5, 3, 15, 1, "", " s"));
		shotChatActions = screenshots.add(new BoolSetting("chatActions", "Actions in the chat message", true));
		shotReplaceEssential = screenshots.add(new BoolSetting("replaceEssential", "Replace Essential's screenshot preview",
				true));

		keySearch = registry.register(new Module("keySearch", "Key Binding Search",
				"Adds a search box to Minecraft's Controls → Key Binds: search by name, by key (key:R, key:2), mouse buttons "
						+ "(mouse, key:mouse4), mod (mod:sodium), double-bound keys (conflict) or free actions (unbound). The "
						+ "keyboard button next to it shows everything on the next key you press.", true));

		hunger = registry.register(new Module("hungerOverlay", "Hunger Overlay",
				"Shows more on the hunger bar: your saturation as a golden outline, what the food in your hand fills up "
						+ "(flashing), how much health it will heal and – in singleplayer – how close the next hunger point "
						+ "is (exhaustion). Idea from AppleSkin.", true));
		hungerSaturation = hunger.add(new BoolSetting("saturation", "Saturation outline", true));
		hungerHeldFood = hunger.add(new BoolSetting("heldFood", "Preview of the food in your hand", true));
		hungerHealth = hunger.add(new BoolSetting("health", "Preview of the health it heals", true));
		hungerExhaustion = hunger.add(new BoolSetting("exhaustion", "Exhaustion bar (singleplayer)", true));

		tooltips.icon("info").category(Category.MISC);
		hunger.icon("food").category(Category.HUD);
		keySearch.icon("keyboard").category(Category.MISC);
		dev.theredstonee.trsclient.core.keys.KeySearch.bind(keySearch);
		serverProfiles.icon("globe").category(Category.MISC);
		panorama.icon("image").category(Category.WORLD);
		screenshots.icon("image").category(Category.MISC);
	}
}
