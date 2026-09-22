package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.pack.PackList;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
//? if >=1.16 {
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
//?} else {
/*import net.minecraft.client.resources.UnopenedResourcePack;
import net.minecraft.server.packs.repository.PackRepository;
*///?}

/**
 * Resourcepack-Zugriffe für Menü und HUD. Ab 1.16 arbeitet Minecraft mit Pack-IDs,
 * davor (1.14–1.15) mit generischen "UnopenedResourcePack"-Objekten.
 */
public final class Packs {
	private Packs() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/**
	 * Lädt die Pack-Liste neu und liefert alle schaltbaren Packs (ohne Pflicht-Packs).
	 * IDs von Packs mit fester Position landen zusätzlich in {@code fixedIds}.
	 */
	public static List<PackList.Entry> available(List<String> fixedIds) {
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
			if (p.isFixedPosition()) fixedIds.add(p.getId());
			// Pflicht-Packs (Standard, Mod-Ressourcen) sind immer aktiv und nicht schaltbar → nicht auflisten.
			if (p.isRequired()) continue;
			out.add(new PackList.Entry(p.getId(), p.getTitle().getString(), p.getDescription().getString(),
					false, p.getCompatibility().isCompatible()));
		}
		return out;
	}

	/** IDs der aktiven Packs (letztes = höchste Priorität). */
	public static List<String> selectedIds() {
		//? if >=1.16 {
		return new ArrayList<>(mc().getResourcePackRepository().getSelectedIds());
		//?} else {
		/*List<String> ids = new ArrayList<>();
		for (UnopenedResourcePack p : mc().getResourcePackRepository().getSelected()) ids.add(p.getId());
		return ids;
		*///?}
	}

	/** Titel der aktiven, nicht verpflichtenden Packs – höchste Priorität zuerst (für das HUD). */
	public static List<String> activeTitles() {
		List<String> titles = new ArrayList<>();
		//? if >=1.16 {
		for (Pack p : mc().getResourcePackRepository().getSelectedPacks()) {
		//?} else
		/*for (UnopenedResourcePack p : mc().getResourcePackRepository().getSelected()) {*/
			if (!p.isRequired() && !p.isFixedPosition()) titles.add(p.getTitle().getString());
		}
		Collections.reverse(titles);
		return titles;
	}

	/** Übernimmt die Auswahl: in options.txt speichern und Ressourcen neu laden. */
	public static void apply(List<String> ids) {
		Minecraft mc = mc();
		//? if >=1.19.4 {
		PackRepository repo = mc.getResourcePackRepository();
		repo.setSelected(ids);
		mc.options.updateResourcePacks(repo);
		//?} elif >=1.16 {
		/*PackRepository repo = mc.getResourcePackRepository();
		repo.setSelected(ids);
		mc.options.resourcePacks.clear();
		mc.options.incompatibleResourcePacks.clear();
		for (Pack p : repo.getSelectedPacks()) {
			if (p.isFixedPosition()) continue;
			mc.options.resourcePacks.add(p.getId());
			if (!p.getCompatibility().isCompatible()) mc.options.incompatibleResourcePacks.add(p.getId());
		}
		mc.options.save();
		mc.reloadResourcePacks();
		*///?} else {
		/*PackRepository<UnopenedResourcePack> repo = mc.getResourcePackRepository();
		List<UnopenedResourcePack> selected = new ArrayList<>();
		for (String id : ids) {
			UnopenedResourcePack p = repo.getPack(id);
			if (p != null) selected.add(p);
		}
		repo.setSelected(selected);
		mc.options.resourcePacks.clear();
		mc.options.incompatibleResourcePacks.clear();
		for (UnopenedResourcePack p : repo.getSelected()) {
			if (p.isFixedPosition()) continue;
			mc.options.resourcePacks.add(p.getId());
			if (!p.getCompatibility().isCompatible()) mc.options.incompatibleResourcePacks.add(p.getId());
		}
		mc.options.save();
		mc.reloadResourcePacks();
		*///?}
	}

	/** Resourcepack-Ordner (bis 1.19.2 als File). */
	public static Path folder() {
		//? if >=1.19.3 {
		return mc().getResourcePackDirectory();
		//?} else
		/*return mc().getResourcePackDirectory().toPath();*/
	}
}
