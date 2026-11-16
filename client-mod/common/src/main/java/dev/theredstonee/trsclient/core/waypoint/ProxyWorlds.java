package dev.theredstonee.trsclient.core.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProxyWorlds {
	public static final int MAX_NAME = 64;
	public static final int MAX_PROFILES = 32;
	private static final int MAX_SERVERS = 256;
	private static final long MAX_FILE = 1024 * 1024;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private final Path file;
	private final Map<String, Profiles> servers = new LinkedHashMap<String, Profiles>();
	private boolean writable = true;

	private static final class Profiles {
		List<String> names = new ArrayList<String>();
		String selected;
	}

	private ProxyWorlds(Path file) {
		this.file = file;
	}

	public static ProxyWorlds open(Path file) {
		if (file == null) throw new IllegalArgumentException("file");
		ProxyWorlds worlds = new ProxyWorlds(file);
		worlds.load();
		return worlds;
	}

	public String resolve(String serverAddress, String backendId) {
		String address = address(serverAddress);
		if (address == null) return WaypointStore.serverKey(serverAddress);
		String backend = validBackendId(backendId) ? backendId : null;
		Profiles profiles = servers.get(address);
		String selected = profiles == null ? null : profiles.selected;
		if (backend == null && selected == null) return WaypointStore.serverKey(serverAddress);
		String qualified = backend != null ? "auto:" + backend : "manual:" + selected;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest((address.length() + ":" + address + qualified).getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder("proxy:");
			for (byte value : bytes) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
			return hex.toString();
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public List<String> profiles(String serverAddress) {
		Profiles profiles = servers.get(address(serverAddress));
		return profiles == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(profiles.names));
	}

	public String selected(String serverAddress) {
		Profiles profiles = servers.get(address(serverAddress));
		return profiles == null ? null : profiles.selected;
	}

	public boolean writable() {
		return writable;
	}

	public static boolean validBackendId(String backendId) {
		return validText(backendId, 128, false);
	}

	public void select(String serverAddress, String name) throws IOException {
		String address = address(serverAddress);
		if (!writable) throw new IOException("Proxy profiles file could not be read");
		if (address == null || (name != null && !validName(name))) throw new IllegalArgumentException("Invalid address or profile");
		Profiles old = servers.get(address);
		if (old == null && name == null) return;
		if (old == null && servers.size() >= MAX_SERVERS) throw new IllegalArgumentException("Too many servers");
		Profiles updated = new Profiles();
		if (old != null) updated.names.addAll(old.names);
		if (name != null && !updated.names.contains(name)) {
			if (updated.names.size() >= MAX_PROFILES) throw new IllegalArgumentException("Too many profiles");
			updated.names.add(name);
		}
		updated.selected = name;
		Map<String, Profiles> next = new LinkedHashMap<String, Profiles>(servers);
		next.put(address, updated);
		write(next);
		servers.clear();
		servers.putAll(next);
	}

	private void load() {
		if (!Files.exists(file)) return;
		try {
			if (Files.size(file) > MAX_FILE) throw new IOException("File too large");
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement root = new JsonParser().parse(reader);
				if (!root.isJsonObject()) throw new IOException("Invalid profiles");
				JsonObject object = root.getAsJsonObject();
				if (object.entrySet().size() > MAX_SERVERS) throw new IOException("Too many servers");
				for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
					if (!entry.getKey().equals(address(entry.getKey())) || !entry.getValue().isJsonObject()) throw new IOException("Invalid server");
					JsonObject data = entry.getValue().getAsJsonObject();
					for (Map.Entry<String, JsonElement> field : data.entrySet()) {
						if (!"names".equals(field.getKey()) && !"selected".equals(field.getKey())) throw new IOException("Unknown field");
					}
					if (!data.has("names") || !data.get("names").isJsonArray() || data.getAsJsonArray("names").size() > MAX_PROFILES) throw new IOException("Invalid names");
					Profiles profiles = new Profiles();
					for (JsonElement item : data.getAsJsonArray("names")) {
						if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IOException("Invalid name");
						String name = item.getAsString();
						if (!validName(name) || profiles.names.contains(name)) throw new IOException("Invalid name");
						profiles.names.add(name);
					}
					if (data.has("selected") && !data.get("selected").isJsonNull()) {
						JsonElement selected = data.get("selected");
						if (!selected.isJsonPrimitive() || !selected.getAsJsonPrimitive().isString() || !profiles.names.contains(selected.getAsString())) throw new IOException("Invalid selection");
						profiles.selected = selected.getAsString();
					}
					servers.put(entry.getKey(), profiles);
				}
			}
		} catch (IOException | RuntimeException ex) {
			servers.clear();
			writable = false;
		}
	}

	private void write(Map<String, Profiles> next) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, "proxy-worlds-", ".tmp");
		try {
			try (java.io.Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				GSON.toJson(next, writer);
			}
			if (Files.size(temporary) > MAX_FILE) throw new IOException("File too large");
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ex) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String address(String address) {
		if (address == null) return null;
		String normalized = address.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty() || normalized.length() > 255) return null;
		for (int index = 0; index < normalized.length(); index++) {
			char character = normalized.charAt(index);
			if (Character.isWhitespace(character) || Character.isISOControl(character) || Character.getType(character) == Character.FORMAT || Character.isSurrogate(character)) return null;
		}
		return normalized;
	}

	private static boolean validName(String name) {
		return validText(name, MAX_NAME, true) && name.indexOf('\u00a7') < 0;
	}

	private static boolean validText(String name, int maximum, boolean trimmed) {
		if (name == null || name.isEmpty() || name.length() > maximum || (trimmed && !name.equals(name.trim()))) return false;
		boolean visible = false;
		for (int index = 0; index < name.length();) {
			int character = name.codePointAt(index);
			int type = Character.getType(character);
			if (Character.isISOControl(character) || type == Character.FORMAT || type == Character.SURROGATE) return false;
			if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) visible = true;
			index += Character.charCount(character);
		}
		return visible;
	}
}