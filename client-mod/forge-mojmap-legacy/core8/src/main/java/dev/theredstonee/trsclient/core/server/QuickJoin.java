package dev.theredstonee.trsclient.core.server;

import java.util.ArrayList;
import java.util.List;

/** Auswahl der Server für die Schnellbeitritt-Leiste des TRS-Startbildschirms. */
public final class QuickJoin {
	/** Ein Eintrag aus servers.dat. */
	public static final class Server {
		private final String name;
		private final String address;

		public Server(String name, String address) {
			this.name = name;
			this.address = address;
		}

		public String name() {
			return name;
		}

		public String address() {
			return address;
		}

		/** Anzeigename; leerer Name → Adresse. */
		public String label() {
			return name == null || name.trim().isEmpty() ? address : name.trim();
		}
	}

	private QuickJoin() {
	}

	/** Die ersten {@code max} Server mit gültiger Adresse, Reihenfolge wie in der Serverliste. */
	public static List<Server> pick(List<Server> all, int max) {
		List<Server> out = new ArrayList<>();
		for (Server s : all) {
			if (out.size() >= max) break;
			if (s == null || s.address() == null || s.address().trim().isEmpty()) continue;
			out.add(new Server(s.name(), s.address().trim()));
		}
		return out;
	}
}
