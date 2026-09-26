package dev.theredstonee.trsclient.core.hosting.share;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gast im Spiel: passt die laufende Instanz zu den Mods der Welt? Vergleich per SHA-1 mit dem eigenen
 * {@code mods}-Ordner. Fehlt etwas, zeigt die Oberfläche die Liste mit „Im Launcher öffnen“ (dort: neue Instanz,
 * Kopie ergänzen oder ohne Mods); ohne fehlende Pflicht-Mods geht auch „Ohne diese Mods beitreten“.
 */
public final class GuestCheck {
	public final String roomId;
	public final SharedContent content;
	public final List<SharedContent.Mod> missingRequired;
	public final List<SharedContent.Mod> missingOptional;
	public final int present;

	GuestCheck(String roomId, SharedContent content, List<SharedContent.Mod> missingRequired,
			List<SharedContent.Mod> missingOptional, int present) {
		this.roomId = roomId;
		this.content = content;
		this.missingRequired = Collections.unmodifiableList(missingRequired);
		this.missingOptional = Collections.unmodifiableList(missingOptional);
		this.present = present;
	}

	public static GuestCheck of(String roomId, SharedContent c, Set<String> localSha1) {
		List<SharedContent.Mod> req = new ArrayList<SharedContent.Mod>();
		List<SharedContent.Mod> opt = new ArrayList<SharedContent.Mod>();
		int present = 0;
		for (SharedContent.Mod m : c.mods) {
			if (localSha1.contains(m.sha1)) present++;
			else if (m.required) req.add(m);
			else opt.add(m);
		}
		return new GuestCheck(roomId, c, req, opt, present);
	}

	/** SHA-1 aller Mods im Ordner (blockierend). */
	public static Set<String> local(Path modsDir) {
		Set<String> out = new HashSet<String>();
		for (ModScan.LocalMod m : ModScan.scan(modsDir)) out.add(m.sha1);
		return out;
	}

	/** Alles da? */
	public boolean complete() {
		return missingRequired.isEmpty() && missingOptional.isEmpty();
	}

	/** Ohne die fehlenden Mods beitreten erlaubt (nichts Pflicht fehlt)? */
	public boolean canJoinWithout() {
		return missingRequired.isEmpty();
	}

	/** Fehlende Mods direkt vom Host (Warnung „nicht geprüft“ im Launcher). */
	public int missingFromHost() {
		int n = 0;
		for (SharedContent.Mod m : missingRequired) if (m.source == SharedContent.Source.HOST) n++;
		for (SharedContent.Mod m : missingOptional) if (m.source == SharedContent.Source.HOST) n++;
		return n;
	}
}
