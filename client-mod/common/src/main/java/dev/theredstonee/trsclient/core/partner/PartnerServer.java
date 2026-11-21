package dev.theredstonee.trsclient.core.partner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.menus.ServerPins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Ein Partner-Server aus {@code GET /v1/partners/servers} (API.md §44.3), geprüft und bereinigt. */
public final class PartnerServer {
	/** Höchstens so viele zeigt die Liste (die API liefert nie mehr). */
	public static final int MAX = 3;
	static final int MAX_NAME = 48;
	static final int MAX_MOTD = 60;

	private static final Pattern ID = Pattern.compile("ps_[A-Za-z0-9_-]{1,64}");
	private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,30}[a-z0-9]");
	private static final Pattern ICON = Pattern.compile("/v1/partners/[a-z0-9][a-z0-9-]{1,30}[a-z0-9]/logo\\.png(\\?v=[A-Za-z0-9_-]{1,64})?");
	private static final Pattern VERSION = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){0,3}");

	public final String id;
	public final String partnerSlug;
	public final String partnerName;
	public final String name;
	/** Normalisierte Adresse (klein, ohne Standard-Port). */
	public final String address;
	public final String motd;
	/** Pfad des Logos unter der API ({@code /v1/partners/<slug>/logo.png?v=…}) oder null. */
	public final String icon;
	public final String minVersion;
	public final String maxVersion;

	PartnerServer(String id, String partnerSlug, String partnerName, String name, String address, String motd, String icon,
			String minVersion, String maxVersion) {
		this.id = id;
		this.partnerSlug = partnerSlug;
		this.partnerName = partnerName;
		this.name = name;
		this.address = address;
		this.motd = motd;
		this.icon = icon;
		this.minVersion = minVersion;
		this.maxVersion = maxVersion;
	}

	/** Liste aus der Antwort ({@code servers}); ungültige Einträge fallen weg, doppelte Adressen auch. */
	public static List<PartnerServer> parseList(JsonElement servers) {
		if (servers == null || !servers.isJsonArray()) return Collections.emptyList();
		List<PartnerServer> out = new ArrayList<PartnerServer>();
		for (JsonElement e : servers.getAsJsonArray()) {
			if (out.size() >= MAX) break;
			PartnerServer s = parse(e);
			if (s == null) continue;
			boolean dup = false;
			for (PartnerServer o : out) dup |= o.address.equals(s.address) || o.id.equals(s.id);
			if (!dup) out.add(s);
		}
		return Collections.unmodifiableList(out);
	}

	/** Ein Eintrag oder null, wenn er nicht taugt. */
	public static PartnerServer parse(JsonElement e) {
		if (e == null || !e.isJsonObject()) return null;
		JsonObject o = e.getAsJsonObject();
		String id = str(o, "id");
		if (id == null || !ID.matcher(id).matches()) return null;
		String address = ServerPins.normalize(str(o, "address"));
		if (address == null || address.length() > 255) return null;
		String slug = null;
		String partner = null;
		if (o.has("partner") && o.get("partner").isJsonObject()) {
			JsonObject p = o.getAsJsonObject("partner");
			slug = str(p, "slug");
			partner = clean(str(p, "name"), MAX_NAME);
		}
		if (slug != null && !SLUG.matcher(slug).matches()) slug = null;
		String name = clean(str(o, "name"), MAX_NAME);
		if (name == null) name = partner != null ? partner : address;
		String icon = str(o, "icon");
		if (icon != null && !ICON.matcher(icon).matches()) icon = null;
		String min = str(o, "minVersion");
		String max = str(o, "maxVersion");
		if (min != null && !VERSION.matcher(min).matches()) min = null;
		if (max != null && !VERSION.matcher(max).matches()) max = null;
		return new PartnerServer(id, slug, partner, name, address, clean(str(o, "motd"), MAX_MOTD), icon, min, max);
	}

	/** Passt der Server zur gespielten Version ({@code null}/unbekannt = ja)? */
	public boolean supports(String mc) {
		if (mc == null || !VERSION.matcher(mc).matches()) return true;
		if (minVersion != null && compare(mc, minVersion) < 0) return false;
		return maxVersion == null || compare(mc, maxVersion) <= 0;
	}

	/** Vergleicht Versionen Teil für Teil („1.8“ &lt; „1.8.9“ &lt; „1.21.11“ &lt; „26.1“). */
	static int compare(String a, String b) {
		String[] x = a.split("\\.");
		String[] y = b.split("\\.");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? Integer.parseInt(x[i]) : 0;
			int q = i < y.length ? Integer.parseInt(y[i]) : 0;
			if (p != q) return p < q ? -1 : 1;
		}
		return 0;
	}

	private static String str(JsonObject o, String key) {
		JsonElement v = o.get(key);
		if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) return null;
		return v.getAsString();
	}

	/** Klartext ohne §-Codes und Steuerzeichen, gekürzt; leer → null. */
	static String clean(String s, int max) {
		if (s == null) return null;
		StringBuilder b = new StringBuilder(Math.min(s.length(), max));
		for (int i = 0; i < s.length() && b.length() < max; i++) {
			char c = s.charAt(i);
			if (c == '§') {
				i++;
				continue;
			}
			if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) c = ' ';
			if (c == ' ' && (b.length() == 0 || b.charAt(b.length() - 1) == ' ')) continue;
			b.append(c);
		}
		String t = b.toString().trim();
		return t.isEmpty() ? null : t;
	}
}
