package dev.theredstonee.trsclient.compat;

import com.mojang.blaze3d.platform.Window;
import dev.theredstonee.trsclient.core.pack.PackList;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
//? if >=1.19 {
/*import net.minecraft.client.OptionInstance;
*///?} else
import net.minecraft.network.chat.TextComponent;
//? if >=1.16 {
import net.minecraft.client.CameraType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
//?} else {
/*import net.minecraft.client.resources.UnopenedResourcePack;
import net.minecraft.server.packs.repository.PackRepository;
*///?}
//? if >=1.16 && <1.18.2 {
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
//?}

/**
 * Zugriffe auf Minecraft, die sich zwischen 1.14.4 und 1.19.4 geändert haben
 * (Optionen als Felder → OptionInstance ab 1.19, Kamera-Perspektive, Spieler-Rotation,
 * Biome, Resourcepacks, Text-Komponenten). Alles Versionsabhängige außerhalb des Zeichnens steht hier.
 */
public final class Mc {
	private Mc() {
	}

	public static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public static Window window() {
		//? if >=1.15 {
		return mc().getWindow();
		//?} else
		/*return mc().window;*/
	}

	/** Aktuell offener Bildschirm (null = im Spiel). */
	public static Screen screen() {
		return mc().screen;
	}

	public static void setScreen(Screen screen) {
		mc().setScreen(screen);
	}

	/** HUD per F1 ausgeblendet? */
	public static boolean hudHidden() {
		return mc().options.hideGui;
	}

	/** Text-Komponente ohne Übersetzung. */
	public static Component literal(String text) {
		//? if >=1.19 {
		/*return Component.literal(text);
		*///?} else
		return new TextComponent(text);
	}

	/** Kurzer Hinweis über der Hotbar. */
	public static void actionBar(String message) {
		mc().gui.setOverlayMessage(literal(message), false);
	}

	/** Version eines geladenen Mods (auch "minecraft"/"forge"), sonst "?". */
	public static String modVersion(String modId) {
		return ModList.get().getModContainerById(modId)
				.map(c -> c.getModInfo().getVersion().toString()).orElse("?");
	}

	private static String fpsSource;
	private static int fpsValue;

	/** Bilder pro Sekunde (bis 1.19.2 nur als Text "60 fps T: …" verfügbar). */
	public static int fps() {
		//? if >=1.19.3 {
		/*return mc().getFps();
		*///?} else {
		String s = mc().fpsString;
		if (s != fpsSource) {
			fpsSource = s;
			int n = 0;
			for (int i = 0; s != null && i < s.length() && Character.isDigit(s.charAt(i)); i++) n = n * 10 + (s.charAt(i) - '0');
			fpsValue = n;
		}
		return fpsValue;
		//?}
	}

	// --- Tasten ---

	/** Angezeigter Name der belegten Taste. */
	public static String keyName(KeyMapping key) {
		//? if >=1.16 {
		return key.getTranslatedKeyMessage().getString();
		//?} else
		/*return key.getTranslatedKeyMessage();*/
	}

	/** Taste als gedrückt/losgelassen markieren (Toggle-Sprint/-Schleichen). */
	public static void setDown(KeyMapping key, boolean down) {
		//? if >=1.15 {
		key.setDown(down);
		//?} else
		/*KeyMapping.set(key.getKey(), down);*/
	}

	/** Schleichen-Taste (bis 1.14.4 "keySneak", danach "keyShift"). */
	public static KeyMapping sneakKey() {
		//? if >=1.15 {
		return mc().options.keyShift;
		//?} else
		/*return mc().options.keySneak;*/
	}

	// --- Optionen ---

	/** Vanillas eigene Umschalt-Option für Sprinten (ab 1.15). */
	public static boolean vanillaToggleSprint() {
		//? if >=1.19 {
		/*return mc().options.toggleSprint().get();
		*///?} elif >=1.15 {
		return mc().options.toggleSprint;
		//?} else
		/*return false;*/
	}

	public static boolean vanillaToggleCrouch() {
		//? if >=1.19 {
		/*return mc().options.toggleCrouch().get();
		*///?} elif >=1.15 {
		return mc().options.toggleCrouch;
		//?} else
		/*return false;*/
	}

	/** Angriffs-Abklinganzeige am Fadenkreuz aktiv? */
	public static boolean attackIndicatorOnCrosshair() {
		//? if >=1.19 {
		/*return mc().options.attackIndicator().get() == net.minecraft.client.AttackIndicatorStatus.CROSSHAIR;
		*///?} else
		return mc().options.attackIndicator == net.minecraft.client.AttackIndicatorStatus.CROSSHAIR;
	}

	/** Gamma (Helligkeit) – nur für den Fullbright-Ersatz ohne Mixin (1.14.4). */
	public static double gamma() {
		//? if >=1.19 {
		/*return mc().options.gamma().get();
		*///?} else
		return mc().options.gamma;
	}

	public static void setGamma(double value) {
		//? if <1.19
		mc().options.gamma = value;
	}

	// --- Kamera-Perspektive (bis 1.15.2 ein int, ab 1.16 CameraType) ---

	/** 0 = Ego-Perspektive, 1 = dritte Person hinten, 2 = dritte Person vorne. */
	public static int cameraMode() {
		//? if >=1.16 {
		return mc().options.getCameraType().ordinal();
		//?} else
		/*return mc().options.thirdPersonView;*/
	}

	public static void setCameraMode(int mode) {
		//? if >=1.16 {
		mc().options.setCameraType(CameraType.values()[mode]);
		//?} else
		/*mc().options.thirdPersonView = mode;*/
	}

	public static boolean firstPerson() {
		return cameraMode() == 0;
	}

	// --- Spieler ---

	public static double x(Player p) {
		//? if >=1.15 {
		return p.getX();
		//?} else
		/*return p.x;*/
	}

	public static double y(Player p) {
		//? if >=1.15 {
		return p.getY();
		//?} else
		/*return p.y;*/
	}

	public static double z(Player p) {
		//? if >=1.15 {
		return p.getZ();
		//?} else
		/*return p.z;*/
	}

	public static float yRot(Player p) {
		//? if >=1.17 {
		/*return p.getYRot();
		*///?} else
		return p.yRot;
	}

	public static float xRot(Player p) {
		//? if >=1.17 {
		/*return p.getXRot();
		*///?} else
		return p.xRot;
	}

	public static BlockPos blockPos(Player p) {
		//? if >=1.16 {
		return p.blockPosition();
		//?} else
		/*return new BlockPos(p);*/
	}

	/** Übersetzter Name des Bioms an der Position (z. B. "Ebene"). */
	public static String biomeName(Level level, BlockPos pos) {
		//? if >=1.18.2 {
		/*return level.getBiome(pos).unwrapKey()
				.map(key -> I18n.get("biome." + key.location().getNamespace() + "." + key.location().getPath()))
				.orElse("?");
		*///?} elif >=1.16 {
		ResourceLocation id = level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY).getKey(level.getBiome(pos));
		return id == null ? "?" : I18n.get("biome." + id.getNamespace() + "." + id.getPath());
		//?} else
		/*return level.getBiome(pos).getName().getString();*/
	}

	/** Effekt ohne Ablaufzeit (ab 1.19.4)? */
	public static boolean infinite(MobEffectInstance effect) {
		//? if >=1.19.4 {
		/*return effect.isInfiniteDuration();
		*///?} else
		return false;
	}

	// --- Resourcepacks (bis 1.15.2 generisches PackRepository<UnopenedResourcePack>) ---

	/** Wählbare Packs (ohne Pflicht-Packs) für das Resourcepack-Menü. */
	public static List<PackList.Entry> availablePacks(List<String> fixedIdsOut) {
		List<PackList.Entry> out = new ArrayList<>();
		//? if >=1.16 {
		PackRepository repo = mc().getResourcePackRepository();
		repo.reload();
		for (Pack p : repo.getAvailablePacks()) {
		//?} else {
		/*PackRepository<UnopenedResourcePack> repo = mc().getResourcePackRepository();
		repo.reload();
		for (UnopenedResourcePack p : repo.getAvailable()) {
		*///?}
			if (p.isFixedPosition()) fixedIdsOut.add(p.getId());
			// Pflicht-Packs (Standard, Mod-Ressourcen) sind immer aktiv und nicht schaltbar → nicht auflisten.
			if (p.isRequired()) continue;
			out.add(new PackList.Entry(p.getId(), p.getTitle().getString(), p.getDescription().getString(),
					false, p.getCompatibility().isCompatible()));
		}
		return out;
	}

	/** IDs der aktiven Packs (letztes = höchste Priorität). */
	public static List<String> selectedPackIds() {
		List<String> ids = new ArrayList<>();
		//? if >=1.16 {
		ids.addAll(mc().getResourcePackRepository().getSelectedIds());
		//?} else {
		/*for (UnopenedResourcePack p : mc().getResourcePackRepository().getSelected()) ids.add(p.getId());
		*///?}
		return ids;
	}

	/** Titel der aktiven, abwählbaren Packs – höchste Priorität zuerst (HUD). */
	public static List<String> selectedPackTitles() {
		List<String> titles = new ArrayList<>();
		//? if >=1.16 {
		for (Pack p : mc().getResourcePackRepository().getSelectedPacks()) {
		//?} else
		/*for (UnopenedResourcePack p : mc().getResourcePackRepository().getSelected()) {*/
			if (p.isRequired() || p.isFixedPosition()) continue;
			titles.add(0, p.getTitle().getString());
		}
		return titles;
	}

	/** Übernimmt eine neue Auswahl, speichert sie in options.txt und lädt die Ressourcen neu. */
	public static void applyPacks(List<String> ids) {
		Minecraft mc = mc();
		//? if >=1.16 {
		PackRepository repo = mc.getResourcePackRepository();
		repo.setSelected(ids);
		Collection<Pack> selected = repo.getSelectedPacks();
		mc.options.resourcePacks.clear();
		mc.options.incompatibleResourcePacks.clear();
		for (Pack p : selected) {
		//?} else {
		/*PackRepository<UnopenedResourcePack> repo = mc.getResourcePackRepository();
		List<UnopenedResourcePack> chosen = new ArrayList<>();
		for (String id : ids) {
			UnopenedResourcePack p = repo.getPack(id);
			if (p != null) chosen.add(p);
		}
		repo.setSelected(chosen);
		Collection<UnopenedResourcePack> selected = repo.getSelected();
		mc.options.resourcePacks.clear();
		mc.options.incompatibleResourcePacks.clear();
		for (UnopenedResourcePack p : selected) {
		*///?}
			if (p.isFixedPosition()) continue;
			mc.options.resourcePacks.add(p.getId());
			if (!p.getCompatibility().isCompatible()) mc.options.incompatibleResourcePacks.add(p.getId());
		}
		mc.options.save();
		mc.reloadResourcePacks();
	}

	public static Path resourcePackDir() {
		//? if >=1.19.3 {
		/*return mc().getResourcePackDirectory();
		*///?} else
		return mc().getResourcePackDirectory().toPath();
	}
}
